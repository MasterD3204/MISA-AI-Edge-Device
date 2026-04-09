package com.google.ai.edge.gallery.voicechat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PiperTtsEngine(
    private val context: Context,
    private val modelDir: String   = "vits-piper-vi-huongly",
    private val modelName: String  = "huongly.onnx",
    private val tokensName: String = "tokens.txt",
    private val espDataDir: String = "vits-piper-vi-huongly/espeak-ng-data",
    private val speakerId: Int     = 0,
    private val speed: Float       = 0.85f,
    private val numThreads: Int    = 2,
) {
    companion object {
        private const val TAG = "PiperTtsEngine"
    }

    var onSpeakingChanged: ((speaking: Boolean) -> Unit)? = null
    var onAllDone: (() -> Unit)? = null

    private var tts: OfflineTts? = null

    @Volatile private var audioTrack: AudioTrack? = null
    private val isSpeaking = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var worker: Job? = null
    private val playLock = Any()
    private var preProcessor: PiperTextPreProcessor? = null

    // ── Init ──────────────────────────────────────────────────────────────

    fun init(): Boolean {
        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  PiperTtsEngine.init() BẮT ĐẦU")
        Log.i(TAG, "  modelDir   = $modelDir")
        Log.i(TAG, "  modelName  = $modelName")
        Log.i(TAG, "  espDataDir = $espDataDir")
        Log.i(TAG, "═══════════════════════════════════════")

        // Kiểm tra assets tồn tại
        try {
            val modelFiles = context.assets.list(modelDir)
            Log.i(TAG, "Assets in '$modelDir': ${modelFiles?.joinToString() ?: "NONE / DIR NOT FOUND"}")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot list assets dir '$modelDir'", e)
        }
        try {
            val espFiles = context.assets.list(espDataDir)
            Log.i(TAG, "Assets in '$espDataDir': count=${espFiles?.size ?: "NONE"}")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot list assets dir '$espDataDir'", e)
        }

        return try {
            // BƯỚC 1: Load native library
            Log.i(TAG, "⚡ Loading native library sherpa-onnx-jni...")
            System.loadLibrary("sherpa-onnx-jni")
            Log.i(TAG, "✅ Native library loaded")

            // BƯỚC 2: Copy espeak-ng-data từ assets ra external storage
            val externalDataDir = copyDataDir(espDataDir)
            Log.i(TAG, "espeak-ng-data copied to: $externalDataDir")

            // BƯỚC 3: Build config
            val vitsConfig = OfflineTtsVitsModelConfig(
                model   = "$modelDir/$modelName",
                tokens  = "$modelDir/$tokensName",
                dataDir = externalDataDir,
            )
            val modelConfig = OfflineTtsModelConfig(
                vits       = vitsConfig,
                numThreads = numThreads,
                provider   = "cpu",
                debug      = true,
            )
            val config = OfflineTtsConfig(model = modelConfig)

            Log.i(TAG, "⚡ Config:")
            Log.i(TAG, "   model   = ${vitsConfig.model}")
            Log.i(TAG, "   tokens  = ${vitsConfig.tokens}")
            Log.i(TAG, "   dataDir = ${vitsConfig.dataDir}")

            // BƯỚC 4: Tạo OfflineTts với assetManager
            Log.i(TAG, "⚡ Đang gọi OfflineTts(assetManager=...)...")
            tts = OfflineTts(assetManager = context.assets, config = config)
            Log.i(TAG, "✅ OfflineTts tạo thành công!")

            val sr = tts!!.sampleRate()
            Log.i(TAG, "   sampleRate = $sr Hz")
            if (sr <= 0) {
                Log.e(TAG, "❌ sampleRate không hợp lệ ($sr)")
                return false
            }

            preProcessor = PiperTextPreProcessor(context)
            startWorker()
            Log.i(TAG, "✅ PiperTtsEngine READY")
            true

        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ UnsatisfiedLinkError — libsherpa-onnx-jni.so không load được", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "❌ init() thất bại: ${e::class.qualifiedName}", e)
            false
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun speak(text: String) {
        if (tts == null || text.isBlank()) return
        val processed = preProcessor?.process(text) ?: text
        queue.offer(processed.trim())
    }

    fun stop() {
        queue.clear()
        isSpeaking.set(false)
        synchronized(playLock) {
            try { audioTrack?.stop() } catch (_: Exception) {}
            audioTrack?.release()
            audioTrack = null
        }
    }

    fun isSpeaking(): Boolean = isSpeaking.get()

    fun shutdown() {
        stop()
        worker?.cancel()
        scope.cancel()
        tts?.free()
        tts = null
        Log.i(TAG, "PiperTtsEngine shutdown")
    }

    // ── Worker ────────────────────────────────────────────────────────────

    private fun startWorker() {
        worker = scope.launch {
            while (isActive) {
                val text = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                synthesizeAndPlay(text)
                if (queue.isEmpty()) onAllDone?.invoke()
            }
        }
    }

    private fun synthesizeAndPlay(text: String) {
        val engine = tts ?: return
        try {
            Log.d(TAG, "🔊 Synthesizing: \"${text.take(80)}\"")
            val audio = engine.generate(text = text, sid = speakerId, speed = speed)
            Log.d(TAG, "   -> ${audio.samples.size} samples @ ${audio.sampleRate}Hz")
            if (audio.samples.isEmpty()) {
                Log.w(TAG, "⚠ Empty audio")
                return
            }
            playAudio(audio.samples, audio.sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Synthesis failed", e)
            isSpeaking.set(false)
            onSpeakingChanged?.invoke(false)
        }
    }

    private fun playAudio(samples: FloatArray, sampleRate: Int) {
        synchronized(playLock) {
            val shorts = ShortArray(samples.size) { i ->
                (samples[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
            }
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return

            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(minBuf)
                    .build()
            } catch (e: Exception) {
                Log.e(TAG, "❌ AudioTrack build failed", e); return
            }

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release(); return
            }

            audioTrack?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
            audioTrack = track

            isSpeaking.set(true)
            onSpeakingChanged?.invoke(true)
            track.play()

            val chunkSize = 2048
            var offset = 0
            while (offset < shorts.size && isSpeaking.get()) {
                val end = minOf(offset + chunkSize, shorts.size)
                val written = track.write(shorts, offset, end - offset)
                if (written < 0) break
                offset += written
            }

            val drainMs = (minBuf.toLong() * 1000) / (sampleRate * 2)
            Thread.sleep(drainMs + 50)

            try { if (isSpeaking.get()) track.stop() } catch (_: Exception) {}
            track.release()
            if (audioTrack === track) audioTrack = null
            isSpeaking.set(false)
            onSpeakingChanged?.invoke(false)
        }
    }

    // ── Asset copy ────────────────────────────────────────────────────────

    private fun copyDataDir(dataDir: String): String {
        copyAssets(dataDir)
        return "${context.getExternalFilesDir(null)!!.absolutePath}/$dataDir"
    }

    private fun copyAssets(path: String) {
        try {
            val list = context.assets.list(path)
            if (list.isNullOrEmpty()) {
                copyFile(path)
            } else {
                File("${context.getExternalFilesDir(null)}/$path").mkdirs()
                for (asset in list) copyAssets(if (path.isEmpty()) asset else "$path/$asset")
            }
        } catch (e: IOException) {
            Log.e(TAG, "copyAssets failed: $path", e)
        }
    }

    private fun copyFile(filename: String) {
        try {
            val dest = File("${context.getExternalFilesDir(null)}/$filename")
            if (dest.exists() && dest.length() > 0) return
            context.assets.open(filename).use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(4096); var read: Int
                    while (input.read(buf).also { read = it } != -1) output.write(buf, 0, read)
                    output.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "copyFile failed: $filename", e)
        }
    }
}
