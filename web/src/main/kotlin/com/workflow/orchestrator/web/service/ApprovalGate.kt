package com.workflow.orchestrator.web.service

import com.workflow.orchestrator.core.web.UrlScreener

/**
 * Service-layer approval gate for unlisted-domain fetches. Decoupled from the UI so
 * the [WebFetchEngine] can be unit-tested with a [FakeApprovalGate] that never touches
 * the EDT or Swing.
 *
 * The production implementation ([com.workflow.orchestrator.web.ui.ApprovalGateImpl])
 * shows an [com.workflow.orchestrator.web.ui.ApprovalDialog] via `invokeLater` wrapped
 * in `suspendCancellableCoroutine` + `withTimeoutOrNull`.
 */
interface ApprovalGate {

    suspend fun ask(prompt: ApprovalPrompt): Decision

    data class ApprovalPrompt(
        val finalUrl: String,
        val originalUrl: String?,
        val screenerFlags: Set<UrlScreener.Flag>,
        val resolvedIp: String?,
        val contentLength: Long?,
        val agentContext: String,
        val timeoutMs: Long,
    )

    sealed class Decision {
        /** User clicked "Allow once" — fetch proceeds, domain is NOT added to allowlist. */
        object AllowOnce : Decision()

        /** User clicked "Add to allowlist" — fetch proceeds and entry is persisted. */
        data class AddToAllowlist(
            val subdomainGlob: Boolean,
            val allowHttp: Boolean,
        ) : Decision()

        /** User clicked "Deny" — fetch is cancelled. */
        object Denied : Decision()

        /** Approval dialog timed out (default 60 s) — fetch is cancelled. */
        object TimedOut : Decision()
    }
}
