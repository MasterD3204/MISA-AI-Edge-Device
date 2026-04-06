package com.google.ai.edge.gallery.voicechat

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "VoiceChatScreen"

// ── Data ──────────────────────────────────────────────────────────────────────

enum class VoiceSender { USER, AI }
data class VoiceMessage(val sender: VoiceSender, val text: String)

enum class VoiceChatState {
    IDLE,       // Chờ user bấm mic
    LISTENING,  // Đang lắng nghe
    PROCESSING, // Đang chạy LLM
    SPEAKING,   // TTS đang phát
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceChatScreen(
    model: Model?,
    navigateUp: () -> Unit,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val listState = rememberLazyListState()

    Log.d(TAG, "VoiceChatScreen recompose — model=${model?.name} instance=${model?.instance}")

    // Guard: model null
    if (model == null) {
        Log.e(TAG, "model is null — hiển thị màn hình lỗi")
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Chưa có model được chọn", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Vui lòng tải model trước rồi quay lại.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val messages  = remember { mutableStateListOf<VoiceMessage>() }
    var chatState by remember { mutableStateOf(VoiceChatState.IDLE) }
    var statusText by remember { mutableStateOf("Nhấn mic để bắt đầu nói") }
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
    ) { granted ->
        Log.d(TAG, "RECORD_AUDIO permission granted=$granted")
        hasMicPermission = granted
    }

    // ── TTS Engine ────────────────────────────────────────────────────────
    val ttsEngine = remember {
        Log.d(TAG, "Creating PiperTtsEngine")
        PiperTtsEngine(context).also { engine ->
            engine.onSpeakingChanged = { speaking ->
                Log.d(TAG, "onSpeakingChanged speaking=$speaking")
                if (!speaking) chatState = VoiceChatState.IDLE
            }
            engine.onAllDone = {
                Log.d(TAG, "onAllDone — TTS queue empty")
                chatState = VoiceChatState.IDLE
            }
        }
    }
    var ttsReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        Log.d(TAG, "Initializing TTS engine...")
        withContext(Dispatchers.IO) {
            try {
                ttsReady = ttsEngine.init()
                Log.d(TAG, "TTS init result: ttsReady=$ttsReady")
            } catch (e: Throwable) {
                Log.e(TAG, "TTS init threw exception", e)
                ttsReady = false
            }
        }
    }

    // ── SpeechRecognizer ──────────────────────────────────────────────────
    val recognizerAvailable = remember {
        val avail = SpeechRecognizer.isRecognitionAvailable(context)
        Log.d(TAG, "SpeechRecognizer.isRecognitionAvailable=$avail")
        avail
    }
    val recognizer = remember {
        Log.d(TAG, "Creating SpeechRecognizer")
        SpeechRecognizer.createSpeechRecognizer(context)
    }

    fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            putExtra("android.speech.extra.PREFER_OFFLINE", true)
        }

    fun startListening() {
        Log.d(TAG, "startListening() — hasMicPermission=$hasMicPermission recognizerAvailable=$recognizerAvailable")
        if (!hasMicPermission) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!recognizerAvailable) {
            statusText = "Thiết bị không hỗ trợ nhận dạng giọng nói"
            return
        }
        partialText = ""
        chatState   = VoiceChatState.LISTENING
        statusText  = "Đang nghe..."
        recognizer.startListening(buildRecognizerIntent())
    }

    fun stopListening() {
        Log.d(TAG, "stopListening()")
        recognizer.stopListening()
    }

    // ── LLM + TTS ─────────────────────────────────────────────────────────
    fun handleUserSpeech(userText: String) {
        Log.d(TAG, "handleUserSpeech: \"${userText.take(80)}\"")
        if (userText.isBlank()) {
            Log.w(TAG, "userText blank — reset to IDLE")
            chatState = VoiceChatState.IDLE
            return
        }
        messages.add(VoiceMessage(VoiceSender.USER, userText))
        chatState  = VoiceChatState.PROCESSING
        statusText = "Đang xử lý..."

        scope.launch(Dispatchers.Default) {
            Log.d(TAG, "Waiting for model instance... current=${model.instance}")
            var waited = 0
            while (model.instance == null && waited < 30_000) {
                kotlinx.coroutines.delay(100); waited += 100
            }
            if (model.instance == null) {
                Log.e(TAG, "Model instance still null after ${waited}ms")
                withContext(Dispatchers.Main) {
                    statusText = "Model chưa sẵn sàng — hãy chờ load xong"
                    chatState  = VoiceChatState.IDLE
                }
                return@launch
            }
            Log.d(TAG, "Model instance ready, running inference")

            val responseBuilder = StringBuilder()
            val done = kotlinx.coroutines.CompletableDeferred<Unit>()

            LlmChatModelHelper.runInference(
                model   = model,
                input   = userText,
                resultListener = { partial, isDone, _ ->
                    if (partial.isNotEmpty()) {
                        Log.v(TAG, "LLM partial: \"${partial.take(40)}\"")
                        responseBuilder.append(partial)
                    }
                    if (isDone) {
                        Log.d(TAG, "LLM done, total length=${responseBuilder.length}")
                        done.complete(Unit)
                    }
                },
                cleanUpListener = {
                    Log.d(TAG, "LLM cleanUp callback")
                    done.complete(Unit)
                },
                onError = { err ->
                    Log.e(TAG, "LLM error: $err")
                    done.complete(Unit)
                },
            )

            done.await()
            val aiText = responseBuilder.toString().trim()
            Log.d(TAG, "AI response (${aiText.length} chars): \"${aiText.take(80)}\"")

            withContext(Dispatchers.Main) {
                if (aiText.isNotEmpty()) {
                    messages.add(VoiceMessage(VoiceSender.AI, aiText))
                    if (ttsReady) {
                        Log.d(TAG, "Calling ttsEngine.speak()")
                        chatState  = VoiceChatState.SPEAKING
                        statusText = "Đang nói..."
                        ttsEngine.speak(aiText)
                    } else {
                        Log.w(TAG, "TTS not ready — skipping speak")
                        statusText = "TTS chưa sẵn sàng (thiếu model Piper)"
                        chatState  = VoiceChatState.IDLE
                    }
                } else {
                    Log.w(TAG, "AI response empty")
                    chatState  = VoiceChatState.IDLE
                    statusText = "Nhấn mic để bắt đầu nói"
                }
            }
        }
    }

    // ── RecognitionListener ───────────────────────────────────────────────
    DisposableEffect(recognizer) {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "onReadyForSpeech")
                statusText = "Đang nghe..."
            }
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech")
                statusText = "Đang nhận dạng..."
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
                Log.v(TAG, "onPartialResults: \"$partial\"")
                partialText = partial
            }
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
                Log.d(TAG, "onResults: \"$text\"")
                partialText = ""
                handleUserSpeech(text)
            }
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH        -> "Không nhận ra giọng nói"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT  -> "Hết thời gian — thử lại"
                    SpeechRecognizer.ERROR_AUDIO           -> "Lỗi audio"
                    SpeechRecognizer.ERROR_NETWORK         -> "Lỗi mạng"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Timeout mạng"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer bận"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Thiếu quyền microphone"
                    else -> "Lỗi nhận dạng (code=$error)"
                }
                Log.w(TAG, "STT onError code=$error: $msg")
                partialText = ""
                chatState   = VoiceChatState.IDLE
                statusText  = msg
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onSegmentResults(segmentResults: Bundle) {}
            override fun onEndOfSegmentedSession() {}
        })
        onDispose {
            Log.d(TAG, "Disposing recognizer")
            recognizer.destroy()
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────
    DisposableEffect(Unit) {
        onDispose {
            Log.d(TAG, "Disposing ttsEngine")
            ttsEngine.shutdown()
        }
    }

    // Auto-scroll
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // ── UI ────────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Voice Chat", fontWeight = FontWeight.SemiBold)
                        Text(
                            model.name,
                            style = MaterialTheme.typography.labelSmall,
                            color  = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = navigateUp) {
                        Icon(Icons.Outlined.MicOff, contentDescription = "Quay lại")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                state     = listState,
                modifier  = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                items(messages) { msg -> MessageBubble(msg) }
                if (partialText.isNotEmpty()) {
                    item {
                        MessageBubble(VoiceMessage(VoiceSender.USER, partialText), isPartial = true)
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text  = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (chatState == VoiceChatState.PROCESSING) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                MicButton(
                    state            = chatState,
                    ttsReady         = ttsReady,
                    onStartListening = { startListening() },
                    onStopListening  = { stopListening() },
                    onStopSpeaking   = {
                        Log.d(TAG, "User stopped TTS")
                        ttsEngine.stop()
                        chatState  = VoiceChatState.IDLE
                        statusText = "Nhấn mic để bắt đầu nói"
                    },
                )
            }
        }
    }
}

// ── Composables ───────────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(message: VoiceMessage, isPartial: Boolean = false) {
    val isUser = message.sender == VoiceSender.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.82f),
            shape = RoundedCornerShape(
                topStart    = if (isUser) 16.dp else 4.dp,
                topEnd      = if (isUser) 4.dp else 16.dp,
                bottomStart = 16.dp,
                bottomEnd   = 16.dp,
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text  = if (isUser) "Bạn" else "AI",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isUser)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isPartial)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun MicButton(
    state: VoiceChatState,
    ttsReady: Boolean,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onStopSpeaking: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.15f,
        animationSpec = infiniteRepeatable(
            tween(600, easing = LinearEasing), RepeatMode.Reverse
        ),
        label = "micScale",
    )

    Box(contentAlignment = Alignment.Center) {
        if (state == VoiceChatState.LISTENING) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .scale(scale)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        CircleShape,
                    )
            )
        }
        FloatingActionButton(
            onClick = when (state) {
                VoiceChatState.IDLE       -> onStartListening
                VoiceChatState.LISTENING  -> onStopListening
                VoiceChatState.SPEAKING   -> onStopSpeaking
                VoiceChatState.PROCESSING -> { {} }
            },
            modifier       = Modifier.size(64.dp),
            containerColor = when (state) {
                VoiceChatState.IDLE       -> MaterialTheme.colorScheme.primary
                VoiceChatState.LISTENING  -> Color(0xFFE53935)
                VoiceChatState.SPEAKING   -> MaterialTheme.colorScheme.tertiary
                VoiceChatState.PROCESSING -> MaterialTheme.colorScheme.surfaceVariant
            },
        ) {
            Icon(
                imageVector = when (state) {
                    VoiceChatState.IDLE,
                    VoiceChatState.PROCESSING -> Icons.Outlined.Mic
                    VoiceChatState.LISTENING,
                    VoiceChatState.SPEAKING   -> Icons.Outlined.Stop
                },
                contentDescription = null,
                tint     = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }

    if (!ttsReady) {
        Spacer(Modifier.height(4.dp))
        Text(
            "⚠ TTS chưa sẵn sàng — thiếu model Piper trong assets",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
