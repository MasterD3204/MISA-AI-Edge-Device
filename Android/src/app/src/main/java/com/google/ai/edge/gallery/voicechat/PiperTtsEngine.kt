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
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Item trong ordered synthesis queue.
 * Mỗi chunk mang một index tăng dần để đảm bảo phát đúng thứ tự.
 */
private data class SynthesisItem(
    val index: Int,
    val text: String,
) : Comparable<SynthesisItem> {
    override fun compareTo(other: SynthesisItem) = index.compareTo(other.index)
}

/**
 * Audio đã được synthesize, chờ phát theo thứ tự index.
 */
private data class AudioItem(
    val index: Int,
    val samples: FloatArray,
    val sampleRate: Int,
) : Comparable<AudioItem> {
    override fun compareTo(other: AudioItem) = index.compareTo(other.index)
}

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
    private val playLock = Any()

    // ── Ordered synthesis pipeline ────────────────────────────────────────
    // synthQueue: chunks chờ synthesize (LinkedBlockingQueue, vào theo thứ tự)
    private val synthQueue = LinkedBlockingQueue<SynthesisItem>()
    // audioQueue: audio đã synthesize, sắp xếp theo index để phát đúng thứ tự
    private val audioQueue = PriorityBlockingQueue<AudioItem>()
    // nextPlayIndex: index tiếp theo cần phát
    private val nextPlayIndex = AtomicInteger(0)
    // nextEnqueueIndex: index tiếp theo sẽ gán khi enqueue chunk
    private val nextEnqueueIndex = AtomicInteger(0)
    // pendingSynthCount: số chunk đang chờ synthesize hoặc chờ phát
    private val pendingSynthCount = AtomicInteger(0)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var synthWorker: Job? = null
    private var playWorker: Job? = null

    private var preProcessor: PiperTextPreProcessor? = null
    private var accumulator: StreamingChunkAccumulator? = null

    // ── Init ──────────────────────────────────────────────────────────────

    fun init(): Boolean {
        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  PiperTtsEngine.init() BẮT ĐẦU")
        Log.i(TAG, "  modelDir   = $modelDir")
        Log.i(TAG, "  modelName  = $modelName")
        Log.i(TAG, "  espDataDir = $espDataDir")
        Log.i(TAG, "═══════════════════════════════════════")

        // BƯỚC 0: Kiểm tra assets tồn tại
        Log.i(TAG, "--- BƯỚC 0: Kiểm tra assets ---")
        try {
            val rootList = context.assets.list("")
            Log.i(TAG, "Assets root: ${rootList?.take(20)?.joinToString()}")
        } catch (t: Throwable) {
            Log.e(TAG, "Không list được assets root", t)
        }
        try {
            val modelFiles = context.assets.list(modelDir)
            Log.i(TAG, "Assets '$modelDir': ${modelFiles?.joinToString() ?: "KHÔNG TÌM THẤY THƯ MỤC"}")
            if (modelFiles.isNullOrEmpty()) {
                Log.e(TAG, "❌ Thư mục assets '$modelDir' không tồn tại hoặc rỗng!")
                return false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Lỗi khi list assets '$modelDir'", t)
            return false
        }
        try {
            val espFiles = context.assets.list(espDataDir)
            Log.i(TAG, "Assets '$espDataDir': count=${espFiles?.size ?: "KHÔNG TÌM THẤY"}")
            if (espFiles.isNullOrEmpty()) {
                Log.e(TAG, "❌ Thư mục espeak-ng-data '$espDataDir' không tồn tại!")
                return false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Lỗi khi list assets '$espDataDir'", t)
            return false
        }

        // BƯỚC 1: Kiểm tra .so file trên thiết bị
        Log.i(TAG, "--- BƯỚC 1: Kiểm tra native library ---")
        try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            Log.i(TAG, "nativeLibraryDir = $nativeLibDir")
            val libDir = java.io.File(nativeLibDir)
            val soFiles = libDir.listFiles()?.map { it.name } ?: emptyList()
            Log.i(TAG, "Danh sách .so trong nativeLibraryDir: ${soFiles.joinToString()}")
            val hasSherpa = soFiles.any { it.contains("sherpa") }
            Log.i(TAG, "Có libsherpa-onnx-jni.so: $hasSherpa")
            if (!hasSherpa) {
                Log.e(TAG, "❌ libsherpa-onnx-jni.so KHÔNG có trong nativeLibraryDir!")
                Log.e(TAG, "   => Kiểm tra jniLibs/ đã được build vào APK chưa")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Lỗi khi kiểm tra nativeLibraryDir", t)
        }

        // BƯỚC 2: Load native library
        Log.i(TAG, "--- BƯỚC 2: System.loadLibrary ---")
        try {
            System.loadLibrary("sherpa-onnx-jni")
            Log.i(TAG, "✅ loadLibrary('sherpa-onnx-jni') thành công")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ UnsatisfiedLinkError khi load sherpa-onnx-jni", e)
            Log.e(TAG, "   message: ${e.message}")
            return false
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Throwable khi load sherpa-onnx-jni", t)
            return false
        }

        // BƯỚC 3: Copy espeak-ng-data
        Log.i(TAG, "--- BƯỚC 3: Copy espeak-ng-data ---")
        val externalDataDir: String
        try {
            externalDataDir = copyDataDir(espDataDir)
            Log.i(TAG, "✅ espeak-ng-data path: $externalDataDir")
            val dir = java.io.File(externalDataDir)
            Log.i(TAG, "   Tồn tại: ${dir.exists()}, isDir: ${dir.isDirectory}")
            val count = dir.listFiles()?.size ?: 0
            Log.i(TAG, "   Số file/folder trong espeak-ng-data: $count")
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Lỗi khi copy espeak-ng-data", t)
            return false
        }

        // BƯỚC 4: Build config
        Log.i(TAG, "--- BƯỚC 4: Build OfflineTtsConfig ---")
        val config: OfflineTtsConfig
        try {
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
            config = OfflineTtsConfig(model = modelConfig)
            Log.i(TAG, "✅ Config built:")
            Log.i(TAG, "   vits.model   = ${vitsConfig.model}")
            Log.i(TAG, "   vits.tokens  = ${vitsConfig.tokens}")
            Log.i(TAG, "   vits.dataDir = ${vitsConfig.dataDir}")
            Log.i(TAG, "   numThreads   = $numThreads")
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Lỗi khi build config", t)
            return false
        }

        // BƯỚC 5: Tạo OfflineTts
        Log.i(TAG, "--- BƯỚC 5: Tạo OfflineTts ---")
        try {
            Log.i(TAG, "⚡ Gọi OfflineTts(assetManager=context.assets, config=...)...")
            tts = OfflineTts(assetManager = context.assets, config = config)
            Log.i(TAG, "✅ OfflineTts() constructor thành công")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ UnsatisfiedLinkError trong OfflineTts constructor", e)
            return false
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Throwable trong OfflineTts constructor: ${t::class.java.name}", t)
            Log.e(TAG, "   message: ${t.message}")
            return false
        }

        // BƯỚC 6: Kiểm tra sampleRate
        Log.i(TAG, "--- BƯỚC 6: Kiểm tra sampleRate ---")
        try {
            val sr = tts!!.sampleRate()
            Log.i(TAG, "sampleRate = $sr Hz")
            if (sr <= 0) {
                Log.e(TAG, "❌ sampleRate không hợp lệ: $sr")
                return false
            }
            Log.i(TAG, "✅ sampleRate OK")
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Lỗi khi gọi sampleRate()", t)
            return false
        }

        // BƯỚC 7: Khởi động workers
        Log.i(TAG, "--- BƯỚC 7: Start workers ---")
        try {
            preProcessor = PiperTextPreProcessor(context)
            accumulator  = StreamingChunkAccumulator()
            startSynthWorker()
            startPlayWorker()
            Log.i(TAG, "✅ Workers started")
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Lỗi khi start workers", t)
            return false
        }

        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  ✅ PiperTtsEngine.init() HOÀN THÀNH")
        Log.i(TAG, "═══════════════════════════════════════")
        return true
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Phát toàn bộ text (không streaming).
     * Dùng khi có sẵn toàn bộ response.
     */
    fun speak(text: String) {
        if (tts == null || text.isBlank()) return
        val processed = preProcessor?.process(text) ?: text
        enqueueChunk(processed.trim())
    }

    /**
     * Nạp partial text streaming từ LLM.
     * Gọi lặp lại mỗi khi có partial mới.
     * Các chunk đủ dài sẽ được enqueue ngay vào pipeline TTS.
     */
    fun speakStreaming(partial: String) {
        if (tts == null || partial.isEmpty()) return
        val acc = accumulator ?: return
        val chunks = acc.feed(partial)
        chunks.forEach { chunk ->
            val processed = preProcessor?.process(chunk) ?: chunk
            if (processed.isNotBlank()) enqueueChunk(processed.trim())
        }
    }

    /**
     * Gọi khi LLM done để flush phần text còn lại trong accumulator.
     */
    fun flushStreaming() {
        if (tts == null) return
        val acc = accumulator ?: return
        val remaining = acc.flush()
        if (!remaining.isNullOrBlank()) {
            val processed = preProcessor?.process(remaining) ?: remaining
            if (processed.isNotBlank()) enqueueChunk(processed.trim())
        }
    }

    fun stop() {
        synthQueue.clear()
        audioQueue.clear()
        pendingSynthCount.set(0)
        nextPlayIndex.set(nextEnqueueIndex.get()) // skip tất cả pending
        accumulator = StreamingChunkAccumulator()  // reset accumulator
        isSpeaking.set(false)
        synchronized(playLock) {
            try { audioTrack?.stop() } catch (_: Exception) {}
            audioTrack?.release()
            audioTrack = null
        }
    }

    /**
     * Reset pipeline streaming cho turn mới.
     * Gọi trước khi bắt đầu speakStreaming() cho response mới.
     */
    fun resetStreaming() {
        synthQueue.clear()
        audioQueue.clear()
        pendingSynthCount.set(0)
        val current = nextEnqueueIndex.get()
        nextPlayIndex.set(current)
        accumulator = StreamingChunkAccumulator()
        Log.d(TAG, "resetStreaming() — indices reset to $current")
    }

    fun isSpeaking(): Boolean = isSpeaking.get()

    fun shutdown() {
        stop()
        synthWorker?.cancel()
        playWorker?.cancel()
        scope.cancel()
        tts?.free()
        tts = null
        Log.i(TAG, "PiperTtsEngine shutdown")
    }

    // ── Internal enqueue ──────────────────────────────────────────────────

    /**
     * Reset counter khi pipeline rỗng, tránh index tăng vô hạn.
     * Gán index và enqueue vào synthQueue.
     */
    private fun enqueueChunk(text: String) {
        val idx = nextEnqueueIndex.getAndIncrement()
        pendingSynthCount.incrementAndGet()
        synthQueue.offer(SynthesisItem(index = idx, text = text))
        Log.d(TAG, "Enqueued chunk #$idx: \"${text.take(60)}\"")
    }

    // ── Synth Worker ──────────────────────────────────────────────────────

    /**
     * Worker tổng hợp audio: lấy chunk từ synthQueue theo thứ tự enqueue,
     * synthesize, rồi đưa vào audioQueue (PriorityQueue theo index).
     */
    private fun startSynthWorker() {
        synthWorker = scope.launch {
            while (isActive) {
                val item = synthQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try {
                    Log.d(TAG, "🔊 Synthesizing #${item.index}: \"${item.text.take(60)}\"")
                    val engine = tts ?: continue
                    val audio = engine.generate(text = item.text, sid = speakerId, speed = speed)
                    if (audio.samples.isNotEmpty()) {
                        audioQueue.offer(AudioItem(item.index, audio.samples, audio.sampleRate))
                        Log.d(TAG, "   #${item.index} synthesized: ${audio.samples.size} samples")
                    } else {
                        Log.w(TAG, "   #${item.index} empty audio — skip")
                        pendingSynthCount.decrementAndGet()
                        checkAllDone()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Synthesis #${item.index} failed", e)
                    pendingSynthCount.decrementAndGet()
                    checkAllDone()
                }
            }
        }
    }

    // ── Play Worker ───────────────────────────────────────────────────────

    /**
     * Worker phát audio: lấy AudioItem từ audioQueue theo thứ tự index tăng dần.
     * Chỉ phát item có index == nextPlayIndex, còn lại giữ trong queue chờ.
     */
    private fun startPlayWorker() {
        playWorker = scope.launch {
            while (isActive) {
                val next = nextPlayIndex.get()
                val item = audioQueue.peek()

                if (item == null || item.index != next) {
                    // Chưa có audio đúng thứ tự → chờ ngắn
                    kotlinx.coroutines.delay(20)
                    continue
                }

                // Đúng thứ tự → lấy ra và phát
                audioQueue.poll()
                nextPlayIndex.incrementAndGet()

                Log.d(TAG, "▶ Playing #${item.index}")
                playAudio(item.samples, item.sampleRate)

                pendingSynthCount.decrementAndGet()
                checkAllDone()
            }
        }
    }

    private fun checkAllDone() {
        if (pendingSynthCount.get() <= 0 && synthQueue.isEmpty() && audioQueue.isEmpty()) {
            onAllDone?.invoke()
        }
    }

    // ── Audio Playback ────────────────────────────────────────────────────

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
