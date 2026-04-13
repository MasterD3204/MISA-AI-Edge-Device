package com.google.ai.edge.gallery.ui.common.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.voicechat.PiperTtsEngine

private const val TAG = "ChatTtsController"

// ── State ─────────────────────────────────────────────────────────────────────

enum class SttState { IDLE, LISTENING }

// ── Composable bar ────────────────────────────────────────────────────────────

/**
 * Thanh điều khiển TTS/STT hiển thị phía dưới danh sách chat.
 *
 * - Nút loa: bật/tắt TTS (đọc phản hồi AI)
 * - Nút mic: nhấn để nói, nhận kết quả qua [onSpeechResult]
 *
 * @param ttsEnabled    TTS đang bật hay tắt
 * @param onTtsToggle   callback khi user toggle nút loa
 * @param onSpeechResult callback trả về text khi STT nhận dạng xong
 * @param llmInProgress LLM đang xử lý — disable mic trong thời gian này
 */
@Composable
fun ChatVoiceBar(
    ttsEnabled: Boolean,
    onTtsToggle: (Boolean) -> Unit,
    onSpeechResult: (String) -> Unit,
    llmInProgress: Boolean = false,
) {
    val context = LocalContext.current

    var sttState by remember { mutableStateOf(SttState.IDLE) }
    var partialText by remember { mutableStateOf("") }

    // ── Permission ────────────────────────────────────────────────────────
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMicPermission = granted }

    // ── SpeechRecognizer ──────────────────────────────────────────────────
    val recognizerAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    val recognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }

    fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        putExtra("android.speech.extra.PREFER_OFFLINE", true)
    }

    DisposableEffect(recognizer) {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onSegmentResults(segmentResults: Bundle) {}
            override fun onEndOfSegmentedSession() {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialText = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
            }
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
                partialText = ""
                if (text.isNotBlank()) {
                    Log.d(TAG, "STT result: \"$text\"")
                    onSpeechResult(text)
                }
                // Tự restart để mic luôn bật cho đến khi user tự tắt
                if (sttState == SttState.LISTENING) {
                    recognizer.startListening(buildIntent())
                }
            }
            override fun onError(error: Int) {
                Log.w(TAG, "STT error code=$error")
                partialText = ""
                // Tự restart trừ khi lỗi nghiêm trọng (không có kết quả match vẫn retry)
                if (sttState == SttState.LISTENING) {
                    recognizer.startListening(buildIntent())
                }
            }
        })
        onDispose { recognizer.destroy() }
    }

    // Reset STT nếu LLM đang chạy
    LaunchedEffect(llmInProgress) {
        if (llmInProgress && sttState == SttState.LISTENING) {
            recognizer.stopListening()
            sttState = SttState.IDLE
        }
    }

    // ── Pulse animation khi listening ─────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            tween(500, easing = LinearEasing), RepeatMode.Reverse
        ), label = "micPulse"
    )

    // ── UI ────────────────────────────────────────────────────────────────
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Partial text hint
        if (partialText.isNotEmpty()) {
            Text(
                text = partialText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }

        // Nút loa — toggle TTS
        FilledIconToggleButton(
            checked = ttsEnabled,
            onCheckedChange = onTtsToggle,
            modifier = Modifier.size(40.dp),
            colors = androidx.compose.material3.IconButtonDefaults.filledIconToggleButtonColors(
                checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Icon(
                imageVector = if (ttsEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                contentDescription = if (ttsEnabled) "Tắt đọc" else "Bật đọc",
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.width(8.dp))

        // Nút mic — STT
        Box(contentAlignment = Alignment.Center) {
            if (sttState == SttState.LISTENING) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .scale(scale)
                        .background(
                            MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                            CircleShape,
                        )
                )
            }
            IconButton(
                onClick = {
                    when {
                        !recognizerAvailable -> return@IconButton
                        !hasMicPermission -> permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        llmInProgress -> return@IconButton
                        sttState == SttState.LISTENING -> {
                            recognizer.stopListening()
                            sttState = SttState.IDLE
                        }
                        else -> {
                            sttState = SttState.LISTENING
                            partialText = ""
                            recognizer.startListening(buildIntent())
                        }
                    }
                },
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = when {
                        !recognizerAvailable || !hasMicPermission ->
                            MaterialTheme.colorScheme.surfaceVariant
                        sttState == SttState.LISTENING ->
                            MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = when {
                        !recognizerAvailable || !hasMicPermission ->
                            MaterialTheme.colorScheme.onSurfaceVariant
                        sttState == SttState.LISTENING ->
                            MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSecondaryContainer
                    },
                ),
            ) {
                Icon(
                    imageVector = when {
                        !recognizerAvailable || !hasMicPermission -> Icons.Outlined.MicOff
                        sttState == SttState.LISTENING -> Icons.Outlined.Stop
                        else -> Icons.Outlined.Mic
                    },
                    contentDescription = "Mic",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ── TTS helper ────────────────────────────────────────────────────────────────

/**
 * Khởi tạo và quản lý vòng đời của [PiperTtsEngine] trong một Composable.
 * Trả về instance engine đã sẵn sàng (hoặc null nếu init thất bại).
 */
@Composable
fun rememberPiperTtsEngine(context: Context): PiperTtsEngine? {
    var engine by remember { mutableStateOf<PiperTtsEngine?>(null) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val e = PiperTtsEngine(context)
            if (e.init()) {
                Log.d(TAG, "PiperTtsEngine init OK")
                engine = e
            } else {
                Log.w(TAG, "PiperTtsEngine init FAILED — TTS disabled")
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { engine?.shutdown() }
    }
    return engine
}
