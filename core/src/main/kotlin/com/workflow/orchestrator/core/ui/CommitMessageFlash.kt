package com.workflow.orchestrator.core.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.vcs.ui.CommitMessage
import javax.swing.BorderFactory
import javax.swing.Timer

/**
 * Phase 6 — Success border flash for the commit message field.
 *
 * On successful AI generation the commit message editor border pulses to
 * [StatusColors.SUCCESS] for [flashDurationMs] ms then restores the original border.
 * This gives unambiguous visual confirmation that the field was written.
 *
 * All Swing mutations run on the EDT via [ApplicationManager.invokeLater] with
 * the correct [ModalityState] so they execute while the commit dialog is open.
 * The restore timer is one-shot ([isRepeats] = false).
 *
 * The [flashDurationMs] parameter is exposed for tests — pass a small value (e.g. 1ms)
 * to ensure the timer fires quickly so tests can verify the restore behaviour without
 * holding the thread or leaving undisposed timers.
 */
object CommitMessageFlash {

    const val DEFAULT_FLASH_DURATION_MS = 600

    /**
     * Briefly pulse the commit message editor border to [StatusColors.SUCCESS]
     * then restore the original border after [flashDurationMs] ms.
     *
     * Safe to call from any thread — all UI operations are dispatched to EDT.
     */
    fun flashSuccess(
        commitMessage: CommitMessage,
        modalityState: ModalityState,
        flashDurationMs: Int = DEFAULT_FLASH_DURATION_MS
    ) {
        val editorField = commitMessage.editorField
        val originalBorder = editorField.border
        val successBorder = BorderFactory.createLineBorder(StatusColors.SUCCESS, 2)

        // Set the success border on EDT
        ApplicationManager.getApplication().invokeLater({
            editorField.border = successBorder
        }, modalityState)

        // Restore the original border after the flash duration
        Timer(flashDurationMs) {
            ApplicationManager.getApplication().invokeLater({
                editorField.border = originalBorder
            }, modalityState)
        }.apply {
            isRepeats = false
            start()
        }
    }
}
