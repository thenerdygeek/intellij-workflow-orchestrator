package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ArtifactPayload
import com.workflow.orchestrator.agent.tools.ArtifactRenderResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Correlates interactive-artifact render requests with their async render outcomes.
 *
 * [RenderArtifactTool] drives the full lifecycle through this registry:
 *   1. Tool generates a `renderId`, calls [renderAndAwait] with the payload.
 *   2. Registry stores a [CompletableDeferred], invokes the push callback to forward
 *      the artifact to the webview (which hands it off to the sandbox iframe).
 *   3. Sandbox iframe renders, posts the outcome back → JCEF bridge in
 *      `AgentCefPanel` calls [reportResult] with the result keyed by the same renderId.
 *   4. The deferred completes, [renderAndAwait] returns, tool builds a structured
 *      [ToolResult] so the LLM sees success/failure instead of the previous
 *      fire-and-forget optimistic success.
 *
 * Push callback is set by [AgentController] when the dashboard panel is available.
 * If no callback is registered (headless tests, race at session start), [renderAndAwait]
 * returns [ArtifactRenderResult.Skipped] so the tool can fall back to the legacy
 * optimistic success message.
 *
 * Thread-safe via [ConcurrentHashMap] — multiple parallel `render_artifact` calls
 * (e.g., orchestrator + coder subagent) each get their own deferred.
 */
@Service(Service.Level.PROJECT)
class ArtifactResultRegistry : Disposable {
    private val logger = thisLogger()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ArtifactRenderResult>>()

    @Volatile
    private var pushCallback: ((ArtifactPayload) -> Unit)? = null

    /**
     * Register the callback used to forward a payload from Kotlin into the webview.
     * Called by [AgentController] during controller initialization.
     */
    fun setPushCallback(callback: ((ArtifactPayload) -> Unit)?) {
        pushCallback = callback
    }

    /**
     * Push [payload] to the UI and suspend until the sandbox reports the outcome
     * (via [reportResult]) or the timeout expires.
     *
     * Returns [ArtifactRenderResult.Skipped] immediately if no UI listener is registered.
     * Returns [ArtifactRenderResult.Timeout] if [timeoutMillis] elapses without a report.
     */
    suspend fun renderAndAwait(
        payload: ArtifactPayload,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    ): ArtifactRenderResult {
        val push = pushCallback ?: return ArtifactRenderResult.Skipped(
            "No UI listener registered (likely headless test or pre-init render)"
        )

        val deferred = CompletableDeferred<ArtifactRenderResult>()
        pending[payload.renderId] = deferred
        try {
            // Push on caller's dispatcher — AgentController wraps the push in invokeLater for EDT
            push(payload)
            return withTimeoutOrNull(timeoutMillis) { deferred.await() }
                ?: ArtifactRenderResult.Timeout(timeoutMillis)
        } finally {
            pending.remove(payload.renderId)
        }
    }

    /**
     * Report a render outcome for [renderId]. Called from the JCEF JS→Kotlin bridge
     * (see `AgentCefPanel._reportArtifactResult`) after the sandbox iframe posts its
     * `rendered` / `error` message.
     *
     * No-op (with log) if the renderId is unknown — happens if the registration was
     * already cleaned up by a timeout or a disposed project.
     */
    fun reportResult(renderId: String, result: ArtifactRenderResult) {
        val deferred = pending[renderId]
        if (deferred == null) {
            logger.debug("reportResult for unknown renderId=$renderId (result=$result)")
            return
        }
        deferred.complete(result)
    }

    override fun dispose() {
        pending.values.forEach { it.cancel() }
        pending.clear()
        pushCallback = null
    }

    companion object {
        /** Default wall-clock budget for a single sandbox render round-trip. */
        const val DEFAULT_TIMEOUT_MS: Long = 30_000L

        fun getInstance(project: Project): ArtifactResultRegistry =
            project.getService(ArtifactResultRegistry::class.java)
    }
}
