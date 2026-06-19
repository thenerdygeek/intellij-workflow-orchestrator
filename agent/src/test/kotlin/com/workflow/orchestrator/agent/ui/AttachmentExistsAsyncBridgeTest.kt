package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-contract pin for audit P2-18: the `_attachmentExists` JCEF bridge handler used to
 * construct an AttachmentStore and run `store.readBlocking(sha256)` (directory scan + full
 * byte read) synchronously inside the bridge handler — per attached file, on the UI-blocking
 * bridge thread.
 *
 * The fix keeps the JS Promise contract (AttachmentManager.uploadAll awaits the bridge) but
 * resolves it asynchronously: the handler ACKs immediately, the existence check runs on the
 * panel's IO scope, and the answer arrives via the injected
 * `window.__resolveAttachmentExists(sha, exists)` callback (option (b) of the audit plan —
 * fits the existing promise-based bridge architecture without webview changes; the injected
 * JS lives in AgentCefPanel.kt, not in the webview sources).
 *
 * AgentCefPanel is not unit-instantiable (JCEF), so per repo precedent this pins source text.
 * Slice boundaries: `attachmentExistsQuery = registerQuery` → `contextUsageQuery =` (do NOT
 * insert new bridges between these sentinels).
 */
class AttachmentExistsAsyncBridgeTest {

    private val src = java.io.File(
        "src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt"
    ).readText()

    private val handlerSlice = src
        .substringAfter("attachmentExistsQuery = registerQuery")
        .substringBefore("contextUsageQuery =")

    @Test
    fun `handler runs the existence check on the IO scope, not inline`() {
        assertTrue(
            handlerSlice.contains("scope.launch"),
            "the attachment existence check must hop to the panel's IO scope"
        )
        assertFalse(
            handlerSlice.contains("readBlocking("),
            "the handler must not read attachment bytes synchronously (P2-18) — " +
                "use the cheap findExtensionForBlocking existence probe off-thread"
        )
    }

    @Test
    fun `handler ACKs immediately instead of carrying the answer in the query response`() {
        assertTrue(
            handlerSlice.contains("JBCefJSQuery.Response(\"ok\")"),
            "the bridge response must be an immediate ACK; the answer travels via callJs"
        )
        assertFalse(
            handlerSlice.contains("{\"exists\":\$exists}"),
            "the synchronous exists-in-response payload must be gone"
        )
    }

    @Test
    fun `async answer is delivered through the resolver callback on both branches`() {
        // Both the null-session-dir early answer and the IO-scan result must resolve the
        // webview promise through the same callback.
        val occurrences = Regex("resolveAttachmentExists\\(").findAll(handlerSlice).count()
        assertTrue(
            occurrences >= 2,
            "both branches (no session dir / scan complete) must call resolveAttachmentExists; found $occurrences"
        )
    }

    @Test
    fun `injected JS defines the waiter registry and resolver consumed by Kotlin`() {
        assertTrue(
            src.contains("window.__resolveAttachmentExists = function(sha, exists)"),
            "the injected _attachmentExists bridge must define the async resolver"
        )
        assertTrue(
            src.contains("window.__attachmentExistsWaiters"),
            "the injected bridge must keep per-sha waiter lists for concurrent calls"
        )
        // Safety: a dropped callback must degrade to a redundant upload, never a hung send.
        assertTrue(
            src.contains("setTimeout(function()"),
            "the injected bridge must self-resolve {exists:false} on timeout"
        )
    }

    @Test
    fun `Kotlin resolver escapes the sha and routes through callJs`() {
        val resolver = src
            .substringAfter("private fun resolveAttachmentExists(")
            .substringBefore("\n    }")
        assertTrue(resolver.contains("callJs("), "resolver must route through the bridge dispatcher (thread-safe)")
        assertTrue(
            resolver.contains("JsEscape.toJsString(sha256)"),
            "the webview-supplied sha must be escaped before embedding in JS"
        )
    }
}
