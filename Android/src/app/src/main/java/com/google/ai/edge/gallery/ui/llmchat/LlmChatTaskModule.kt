/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.theme.emptyStateContent
import com.google.ai.edge.gallery.ui.theme.emptyStateTitle
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

////////////////////////////////////////////////////////////////////////////////////////////////////
// AI Chat — unified chat with smart modality switching.
//
// Strategy:
//  - Model is initialized with supportImage=true, supportAudio=true so all backends are ready.
//  - showImagePicker=true and showAudioPicker=true expose the UI controls for image and audio.
//  - When the user taps the reset button, the session resets with full multimodal support so
//    the engine remains ready for any subsequent input type.
//
// The runtime (LlmChatModelHelper) adds Content.ImageBytes / Content.AudioBytes before text in
// the sendMessageAsync payload, so the model always receives the correct multimodal context.
// No manual modality switching is needed because the single conversation instance (initialised
// with both visionBackend and audioBackend) can handle all three input types in the same session.

class LlmChatTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.LLM_CHAT,
      label = "AI Chat",
      category = Category.LLM,
      icon = Icons.Outlined.Forum,
      models = mutableListOf(),
      description = "Trò chuyện với AI on-device, hỗ trợ ảnh và âm thanh",
      shortDescription = "Trò chuyện với AI on-device",
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
    )

  // Initialise with all backends enabled so one conversation handles text + image + audio.
  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    model.runtimeHelper.initialize(
      context = context,
      model = model,
      supportImage = true,
      supportAudio = true,
      onDone = onDone,
      coroutineScope = coroutineScope,
    )
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    model.runtimeHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    LlmChatScreen(
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      // Expose image and audio pickers in the input UI.
      showImagePicker = true,
      showAudioPicker = true,
      // onResetSessionClickedOverride = null (default): ChatViewWrapper will call
      // viewModel.resetSession(supportImage = showImagePicker, supportAudio = showAudioPicker)
      // which means supportImage=true, supportAudio=true — keeps full multimodal support.
      emptyStateComposable = {
        Box(modifier = Modifier.fillMaxSize()) {
          Column(
            modifier =
              Modifier.align(Alignment.Center)
                .padding(horizontal = 48.dp)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(stringResource(R.string.aichat_emptystate_title), style = emptyStateTitle)
            Text(
              stringResource(R.string.aichat_emptystate_content),
              style = emptyStateContent,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
            )
          }
        }
      },
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object LlmChatTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask {
    return LlmChatTask()
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////
// Ask image - Removed: image support is now integrated into AI Chat.

@Module
@InstallIn(SingletonComponent::class)
internal object LlmAskImageModule {
  // LlmAskImageTask removed - image support merged into AI Chat
}

////////////////////////////////////////////////////////////////////////////////////////////////////
// Ask audio - Removed: audio support is now integrated into AI Chat.

@Module
@InstallIn(SingletonComponent::class)
internal object LlmAskAudioModule {
  // LlmAskAudioTask removed - audio support merged into AI Chat
}
