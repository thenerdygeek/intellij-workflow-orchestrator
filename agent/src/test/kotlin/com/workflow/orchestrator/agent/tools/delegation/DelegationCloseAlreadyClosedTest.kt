package com.workflow.orchestrator.agent.tools.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.delegation.DelegationOutboundService
import com.workflow.orchestrator.agent.delegation.HandleState
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * H — closing an already-closed / unknown handle must return
 * `{"closed":false,"already_closed":true,"handle":"..."}` and a
 * success-toned summary ("was already closed (no-op)"), so the LLM
 * doesn't misread a no-op close as a failure.
 *
 * Tests are fail-before / pass-after:
 *  - Before the fix, the no-op path returns bare `{"closed":false,"handle":"..."}` with
 *    summary "Handle X already closed" — no `already_closed` field.
 *  - After the fix, both ClosedRetained and Unknown handles carry `already_closed:true`
 *    in the JSON and a success-toned summary that includes "already closed (no-op)".
 */
class DelegationCloseAlreadyClosedTest {

    private val outbound = mockk<DelegationOutboundService>(relaxed = true)
    private val project = mockk<Project>(relaxed = true)
    private val tool = DelegationTool()

    @BeforeEach
    fun setup() {
        installReadActionInlineShim()
        mockkObject(PluginSettings.Companion)
        val settings = mockk<PluginSettings>(relaxed = true)
        val state = mockk<PluginSettings.State>(relaxed = true)
        every { state.enableOutboundCrossIdeDelegation } returns true
        every { settings.state } returns state
        every { PluginSettings.getInstance(any()) } returns settings
        every { project.getService(DelegationOutboundService::class.java) } returns outbound
    }

    @AfterEach
    fun tearDown() { unmockkAll() }

    private fun closeParams(handle: String) = buildJsonObject {
        put("action", JsonPrimitive("close"))
        put("handle", JsonPrimitive(handle))
    }

    // ── H: already-closed / unknown handle → already_closed:true ──────────────

    @Test
    fun `closing an already-terminal ClosedRetained handle returns already_closed true`() = runBlocking {
        val handle = "handle-done-ab1cdef2"
        every { outbound.handleState(handle) } returns HandleState.ClosedRetained("COMPLETED", "frontend", 1L)
        every { outbound.close(handle) } returns false

        val result = tool.execute(closeParams(handle), project)

        assertFalse(result.isError, "close must succeed even for an already-closed handle")
        assertTrue(
            result.content.contains("\"already_closed\":true"),
            "Content JSON must include already_closed:true for an already-closed handle; got: ${result.content}"
        )
    }

    @Test
    fun `closing an unknown handle returns already_closed true`() = runBlocking {
        val handle = "handle-unknown-12345678"
        every { outbound.handleState(handle) } returns HandleState.Unknown
        every { outbound.close(handle) } returns false

        val result = tool.execute(closeParams(handle), project)

        assertFalse(result.isError, "close must succeed (idempotent) for an unknown handle")
        assertTrue(
            result.content.contains("\"already_closed\":true"),
            "Content JSON must include already_closed:true for an unknown handle; got: ${result.content}"
        )
    }

    @Test
    fun `already-closed summary reads as success-toned no-op`() = runBlocking {
        val handle = "handle-done-abcdef12"
        every { outbound.handleState(handle) } returns HandleState.ClosedRetained("COMPLETED", "backend", 1L)
        every { outbound.close(handle) } returns false

        val result = tool.execute(closeParams(handle), project)

        // Summary must contain "already closed" AND "no-op" — success-toned, not failure-toned.
        val combined = result.content + " " + result.summary
        assertTrue(
            combined.contains("already closed") && combined.contains("no-op"),
            "Summary must read as success-toned no-op (contain 'already closed' and 'no-op'); got: $combined"
        )
    }

    @Test
    fun `already-closed no-op still echoes handle field`() = runBlocking {
        val handle = "handle-echo-99aabbcc"
        every { outbound.handleState(handle) } returns HandleState.ClosedRetained("FAILED", "svc", 2L)
        every { outbound.close(handle) } returns false

        val result = tool.execute(closeParams(handle), project)

        assertTrue(
            result.content.contains("\"handle\":"),
            "Backward-compat: handle field must be echoed in the no-op close result; got: ${result.content}"
        )
    }

    @Test
    fun `closing a LIVE handle does NOT set already_closed in JSON`() = runBlocking {
        // Regression guard: the live-close path must not accidentally emit already_closed.
        val handle = "handle-live-00112233"
        every { outbound.handleState(handle) } returns HandleState.Active("RUNNING", "api")
        every { outbound.close(handle) } returns true

        val result = tool.execute(closeParams(handle), project)

        assertFalse(result.isError)
        assertFalse(
            result.content.contains("\"already_closed\":true"),
            "Live close must NOT emit already_closed:true; got: ${result.content}"
        )
    }

    @Test
    fun `closing a LIVE handle still emits was_running true and abort warning`() = runBlocking {
        // Regression guard: the live-close abort warning introduced by #4c must be unaffected.
        val handle = "handle-live-aabb1122"
        every { outbound.handleState(handle) } returns HandleState.Active("RUNNING", "ui")
        every { outbound.close(handle) } returns true

        val result = tool.execute(closeParams(handle), project)

        assertFalse(result.isError)
        assertTrue(
            result.content.contains("\"was_running\":true"),
            "Live close must retain was_running:true; got: ${result.content}"
        )
        val combined = result.content + " " + result.summary
        assertTrue(
            combined.contains("RUNNING") || combined.contains("abort") || combined.contains("Abort"),
            "Live close must still carry the abort warning; got: $combined"
        )
    }
}
