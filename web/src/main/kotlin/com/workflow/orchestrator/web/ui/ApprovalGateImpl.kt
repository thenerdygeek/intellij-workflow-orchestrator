package com.workflow.orchestrator.web.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.web.service.ApprovalGate
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Production [ApprovalGate] that shows [ApprovalDialog] on the EDT.
 *
 * - Uses [withTimeoutOrNull] so that if the dialog is never answered within
 *   [ApprovalGate.ApprovalPrompt.timeoutMs], the coroutine resumes with [ApprovalGate.Decision.TimedOut].
 * - Uses [suspendCancellableCoroutine] + `invokeLater` so the calling coroutine
 *   suspends cleanly while the EDT renders and waits for user input.
 * - Cancellation: if the coroutine is cancelled while the dialog is open, the dialog
 *   is not closed explicitly — it becomes orphaned until the user dismisses it.
 *   This is acceptable because ApprovalGate calls are inherently user-interactive.
 */
class ApprovalGateImpl(private val project: Project) : ApprovalGate {

    override suspend fun ask(prompt: ApprovalGate.ApprovalPrompt): ApprovalGate.Decision =
        withTimeoutOrNull(prompt.timeoutMs) {
            suspendCancellableCoroutine { cont ->
                ApplicationManager.getApplication().invokeLater {
                    val dlg = ApprovalDialog(
                        project = project,
                        finalUrl = prompt.finalUrl,
                        originalUrl = prompt.originalUrl,
                        screenerFlags = prompt.screenerFlags,
                        resolvedIp = prompt.resolvedIp,
                        contentLength = prompt.contentLength,
                        agentContext = prompt.agentContext,
                    )
                    val ok = dlg.showAndGet()
                    val decision: ApprovalGate.Decision = when {
                        !ok -> ApprovalGate.Decision.Denied
                        dlg.decision == ApprovalDialog.Decision.ALLOW_ONCE ->
                            ApprovalGate.Decision.AllowOnce
                        dlg.decision == ApprovalDialog.Decision.ADD_TO_ALLOWLIST ->
                            ApprovalGate.Decision.AddToAllowlist(
                                subdomainGlob = dlg.addSubdomainGlob,
                                allowHttp = dlg.addAllowHttp,
                            )
                        else -> ApprovalGate.Decision.Denied
                    }
                    if (cont.isActive) cont.resume(decision)
                }
            }
        } ?: ApprovalGate.Decision.TimedOut
}
