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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Fix #4c — `close` action must warn the caller when it closes a RUNNING delegation,
 * because closing an active channel aborts in-flight work on IDE-B.
 *
 * Tests are fail-before / pass-after:
 *  - Before the fix, `handleClose` always returns the same bare JSON regardless of
 *    whether the handle was active or already terminal.
 *  - After the fix, closing an Active handle surfaces "aborted" in the content/summary.
 */
class DelegationCloseAbortWarnTest {

    private val outbound = mockk<DelegationOutboundService>(relaxed = true)
    private val project = mockk<Project>(relaxed = true)
    private val tool = DelegationTool()

    @BeforeEach fun setup() {
        installReadActionInlineShim()
        mockkObject(PluginSettings.Companion)
        val settings = mockk<PluginSettings>(relaxed = true)
        val state = mockk<PluginSettings.State>(relaxed = true)
        every { state.enableOutboundCrossIdeDelegation } returns true
        every { settings.state } returns state
        every { PluginSettings.getInstance(any()) } returns settings
        every { project.getService(DelegationOutboundService::class.java) } returns outbound
    }

    @AfterEach fun tearDown() { unmockkAll() }

    private fun closeParams(handle: String) = buildJsonObject {
        put("action", JsonPrimitive("close"))
        put("handle", JsonPrimitive(handle))
    }

    // ── Fix #4c: abort warning on RUNNING close ─────────────────────────────

    @Test
    fun `closing a RUNNING handle warns that in-flight work was aborted`() = runBlocking {
        val handle = "handle-running"
        every { outbound.handleState(handle) } returns HandleState.Active("RUNNING", "frontend")
        every { outbound.close(handle) } returns true

        val result = tool.execute(closeParams(handle), project)

        assertFalse(result.isError, "close must succeed, not fail")
        assertTrue(result.content.contains("\"closed\":true"), "closed field must remain true")
        val combined = result.content + " " + result.summary
        assertTrue(
            combined.contains("RUNNING") || combined.contains("abort") || combined.contains("Abort"),
            "Result must mention that the handle was RUNNING and the work was aborted; got: $combined"
        )
    }

    @Test
    fun `closing a RUNNING handle adds was_running field to result JSON`() = runBlocking {
        val handle = "handle-was-running"
        every { outbound.handleState(handle) } returns HandleState.Active("RUNNING", "backend")
        every { outbound.close(handle) } returns true

        val result = tool.execute(closeParams(handle), project)

        assertFalse(result.isError)
        assertTrue(
            result.content.contains("\"was_running\":true"),
            "Content JSON must include was_running:true when the handle was active; got: ${result.content}"
        )
    }

    @Test
    fun `closing an already-terminal handle does NOT mention abort`() = runBlocking {
        val handle = "handle-done"
        every { outbound.handleState(handle) } returns HandleState.ClosedRetained("COMPLETED", "backend", 1L)
        every { outbound.close(handle) } returns false

        val result = tool.execute(closeParams(handle), project)

        assertFalse(result.isError, "close must succeed")
        val combined = result.content + " " + result.summary
        assertFalse(
            combined.contains("abort") || combined.contains("Abort"),
            "Terminal handle close must NOT mention abort; got: $combined"
        )
        assertFalse(
            result.content.contains("\"was_running\":true"),
            "was_running must not be true for a terminal handle"
        )
    }

    @Test
    fun `closing an unknown handle does NOT mention abort`() = runBlocking {
        val handle = "handle-ghost"
        every { outbound.handleState(handle) } returns HandleState.Unknown
        every { outbound.close(handle) } returns false

        val result = tool.execute(closeParams(handle), project)

        assertFalse(result.isError, "close is idempotent — unknown handle is not an error")
        val combined = result.content + " " + result.summary
        assertFalse(
            combined.contains("abort") || combined.contains("Abort"),
            "Unknown handle close must NOT mention abort; got: $combined"
        )
    }

    @Test
    fun `close result always contains closed boolean and handle fields`() = runBlocking {
        // Backward-compatibility: existing JSON fields must still be present regardless of state.
        val handle = "handle-compat"
        every { outbound.handleState(handle) } returns HandleState.Active("RUNNING", "svc")
        every { outbound.close(handle) } returns true

        val result = tool.execute(closeParams(handle), project)

        assertTrue(result.content.contains("\"closed\":"), "closed field must be present")
        assertTrue(result.content.contains("\"handle\":"), "handle field must be present")
    }
}
