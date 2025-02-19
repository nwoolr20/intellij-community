// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.inline.completion

import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionContext.Companion.initOrGetInlineCompletionContext
import com.intellij.codeInsight.inline.completion.InlineState.Companion.getInlineCompletionState
import com.intellij.codeInsight.inline.completion.InlineState.Companion.initOrGetInlineCompletionState
import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.takeWhile
import org.jetbrains.annotations.ApiStatus
import java.util.concurrent.atomic.AtomicBoolean

@ApiStatus.Experimental
class InlineCompletionHandler(private val scope: CoroutineScope) : CodeInsightActionHandler {
  private var runningJob: Job? = null

  private fun getProvider(event: InlineCompletionEvent): InlineCompletionProvider? {
    return InlineCompletionProvider.extensions().filter { it.isEnabled(event) }.firstOrNull()
  }

  override fun invoke(project: Project, editor: Editor, file: PsiFile) {
    val inlineState = editor.getInlineCompletionState() ?: return

    showInlineSuggestion(editor, inlineState, editor.caretModel.offset)
  }

  fun invoke(event: DocumentEvent, editor: Editor) = invoke(InlineCompletionEvent.Document(event, editor))
  fun invoke(event: EditorMouseEvent) = invoke(InlineCompletionEvent.Caret(event))
  fun invoke(event: LookupEvent) = invoke(InlineCompletionEvent.Lookup(event))
  fun invoke(editor: Editor, file: PsiFile, caret: Caret) = invoke(InlineCompletionEvent.DirectCall(editor, file, caret))

  private fun invoke(event: InlineCompletionEvent) {
    if (isMuted.get()) {
      return
    }
    val request = event.toRequest() ?: return
    val provider = getProvider(event) ?: return

    runningJob?.cancel()
    runningJob = scope.launch {
      val modificationStamp = request.document.modificationStamp
      val resultFlow = provider.getProposals(request)

      val editor = request.editor
      val offset = request.endOffset

      val inlineState = editor.initOrGetInlineCompletionState()

      withContext(Dispatchers.EDT) {
        resultFlow.let {
          // In case lookup is shown - request must be only single line, due to lookup render issues
          if (LookupManager.getActiveLookup(request.editor) != null) {
            it.takeFirstLine()
          }
          else {
            it
          }
        }.collectIndexed { index, value ->
          if (index == 0 && modificationStamp != request.document.modificationStamp) {
            cancel()
            return@collectIndexed
          }

          inlineState.suggestions = listOf(InlineCompletionElement(value.text))
          showInlineSuggestion(editor, inlineState, offset)
        }
      }
    }
  }

  private fun showInlineSuggestion(editor: Editor, inlineContext: InlineState, startOffset: Int) {
    val suggestions = inlineContext.suggestions
    if (suggestions.isEmpty()) {
      return
    }

    val idOffset = 1 // TODO: replace with 0?
    val size = suggestions.size

    val suggestionIndex = (inlineContext.suggestionIndex + idOffset + size) % size
    if (suggestions.getOrNull(suggestionIndex) == null) {
      return
    }

    editor.initOrGetInlineCompletionContext().update(suggestions, suggestionIndex, startOffset)

    inlineContext.suggestionIndex = suggestionIndex
    inlineContext.lastStartOffset = startOffset
    inlineContext.lastModificationStamp = editor.document.modificationStamp
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun Flow<InlineCompletionElement>.takeFirstLine(): Flow<InlineCompletionElement> {
    var found = false
    return takeWhile {
      val value = it.text
      !found.also {
        if (value.contains("\n")) found = true
      }
    }.mapLatest {
      it.withText(it.text.takeWhile { c -> c != '\n' })
    }
  }


  companion object {
    val KEY = Key.create<InlineCompletionHandler>("inline.completion.handler")

    val isMuted: AtomicBoolean = AtomicBoolean(false)
    fun mute(): Unit = isMuted.set(true)
    fun unmute(): Unit = isMuted.set(false)
  }
}
