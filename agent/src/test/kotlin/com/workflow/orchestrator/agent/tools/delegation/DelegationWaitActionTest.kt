package com.workflow.orchestrator.agent.tools.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.delegation.DelegationOutboundService
import com.workflow.orchestrator.agent.delegation.DelegationWaitOutcome
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Formatting + error-mapping coverage for the `delegation(action="wait")` tool handler. */
class DelegationWaitActionTest {

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

    private fun waitParams(handle: String, timeoutSeconds: Int? = null): JsonObject = buildJsonObject {
        put("action", JsonPrimitive("wait"))
        put("handle", JsonPrimitive(handle))
        if (timeoutSeconds != null) put("timeout_seconds", JsonPrimitive(timeoutSeconds))
    }

    // ── Fix #5: timeout_seconds clamp surfacing ─────────────────────────────

    @Test
    fun `clamp note appears in TimedOut content when requested value is below minimum`() = runBlocking {
        coEvery { outbound.awaitResult("h", any()) } returns DelegationWaitOutcome.TimedOut("h", "backend")
        val result = tool.execute(waitParams("h", timeoutSeconds = 2), project)
        assertFalse(result.isError)
        assertTrue(
            result.content.contains("clamped") || result.content.contains("requested timeout_seconds=2"),
            "Content must mention the clamp when raw=2 is below the 5s minimum"
        )
        assertTrue(
            result.content.contains("2") && result.content.contains("5"),
            "Content must reference both the requested value (2) and the effective value (5)"
        )
    }

    @Test
    fun `clamp note appears in Completed content when requested value is above maximum`() = runBlocking {
        coEvery { outbound.awaitResult("h", any()) } returns DelegationWaitOutcome.Completed(
            DelegationMessage.Result(status = DelegationMessage.ResultStatus.COMPLETED, summary = "done"),
            "backend",
        )
        val result = tool.execute(waitParams("h", timeoutSeconds = 9999), project)
        assertFalse(result.isError)
        assertTrue(
            result.content.contains("clamped") || result.content.contains("requested timeout_seconds=9999"),
            "Content must mention the clamp when raw=9999 is above the 1800s maximum"
        )
    }

    @Test
    fun `clamp note absent when timeout_seconds is within valid range`() = runBlocking {
        coEvery { outbound.awaitResult("h", any()) } returns DelegationWaitOutcome.TimedOut("h", "backend")
        val result = tool.execute(waitParams("h", timeoutSeconds = 60), project)
        assertFalse(result.isError)
        assertFalse(
            result.content.contains("clamped"),
            "No clamp note when in-range timeout_seconds=60 is used"
        )
    }

    @Test
    fun `clamp note absent when timeout_seconds is omitted (default)`() = runBlocking {
        coEvery { outbound.awaitResult("h", any()) } returns DelegationWaitOutcome.TimedOut("h", "backend")
        val result = tool.execute(waitParams("h"), project)
        assertFalse(result.isError)
        assertFalse(
            result.content.contains("clamped"),
            "No clamp note when timeout_seconds is omitted (default applies, no raw vs effective mismatch)"
        )
    }

    @Test
    fun `clamp note appears in Question content when requested value is out of range`() = runBlocking {
        coEvery { outbound.awaitResult("h", any()) } returns DelegationWaitOutcome.Question(
            DelegationMessage.Question(questionId = "q1", text = "Which env?", options = emptyList()),
            "backend",
        )
        val result = tool.execute(waitParams("h", timeoutSeconds = 2), project)
        assertFalse(result.isError)
        assertTrue(
            result.content.contains("clamped") || result.content.contains("requested timeout_seconds=2"),
            "Content must mention the clamp on Question outcome too"
        )
    }

    // ── Original tests ──────────────────────────────────────────────────────

    @Test
    fun `wait returns the result inline when the delegation completes`() = runBlocking {
        coEvery { outbound.awaitResult("h", any()) } returns DelegationWaitOutcome.Completed(
            DelegationMessage.Result(status = DelegationMessage.ResultStatus.COMPLETED, summary = "did the thing"),
            "backend",
        )
        val result = tool.execute(waitParams("h"), project)
        assertFalse(result.isError)
        assertTrue(result.content.contains("DELEGATION RESULT"))
        assertTrue(result.content.contains("did the thing"))
    }

    @Test
    fun `wait surfaces a clarifying question with answer instructions`() = runBlocking {
        coEvery { outbound.awaitResult("h", any()) } returns DelegationWaitOutcome.Question(
            DelegationMessage.Question(questionId = "q1", text = "Which env?", options = listOf("dev", "prod")),
            "backend",
        )
        val result = tool.execute(waitParams("h"), project)
        assertFalse(result.isError)
        assertTrue(result.content.contains("Which env?"))
        assertTrue(result.content.contains("action=\"answer\""))
        assertTrue(result.content.contains("q1"))
    }

    @Test
    fun `wait timeout is not an error and points to auto-delivery`() = runBlocking {
        coEvery { outbound.awaitResult("h", any()) } returns DelegationWaitOutcome.TimedOut("h", "backend")
        val result = tool.execute(waitParams("h"), project)
        assertFalse(result.isError)
        assertTrue(result.content.contains("still running"))
    }

    @Test
    fun `wait on an already-completed handle points to fetch_transcript`() = runBlocking {
        coEvery { outbound.awaitResult("h", any()) } returns DelegationWaitOutcome.NotActive("already_completed")
        val result = tool.execute(waitParams("h"), project)
        assertFalse(result.isError)
        assertTrue(result.content.contains("fetch_transcript"))
    }

    @Test
    fun `wait on an unknown handle errors`() = runBlocking {
        coEvery { outbound.awaitResult("h", any()) } returns DelegationWaitOutcome.NotActive("handle_not_found")
        val result = tool.execute(waitParams("h"), project)
        assertTrue(result.isError)
        assertTrue(result.summary.contains("DelegationHandleNotFound") || result.content.contains("DelegationHandleNotFound"))
    }

    // ── Fix A (.23 #3): per-terminal-state wording, NOT a blanket "already completed" ──

    @Test
    fun `wait on a canceled handle says canceled and is not an error`() = runBlocking {
        coEvery { outbound.awaitResult("h", any()) } returns DelegationWaitOutcome.NotActive("already_canceled")
        val result = tool.execute(waitParams("h"), project)
        assertFalse(result.isError, "a canceled handle is a known terminal state, not a tool failure")
        val text = result.content + " " + result.summary
        assertTrue(text.contains("cancel", ignoreCase = true), "must say canceled: $text")
        assertFalse(text.contains("has already completed"), "must NOT claim completion for a canceled handle")
        // points the LLM at recovery affordances
        assertTrue(text.contains("fetch_transcript") || text.contains("send"), "must offer a recovery action: $text")
    }

    @Test
    fun `wait on a failed handle says failed and is not the completed wording`() = runBlocking {
        coEvery { outbound.awaitResult("h", any()) } returns DelegationWaitOutcome.NotActive("already_failed")
        val result = tool.execute(waitParams("h"), project)
        assertFalse(result.isError)
        val text = result.content + " " + result.summary
        assertTrue(text.contains("fail", ignoreCase = true), "must say failed: $text")
        assertFalse(text.contains("has already completed"))
    }

    @Test
    fun `wait on a rejected handle says rejected and is not the completed wording`() = runBlocking {
        coEvery { outbound.awaitResult("h", any()) } returns DelegationWaitOutcome.NotActive("already_rejected")
        val result = tool.execute(waitParams("h"), project)
        assertFalse(result.isError)
        val text = result.content + " " + result.summary
        assertTrue(text.contains("reject", ignoreCase = true), "must say rejected: $text")
        assertFalse(text.contains("has already completed"))
    }

    @Test
    fun `wait on a completed handle still says completed and points to fetch_transcript`() = runBlocking {
        coEvery { outbound.awaitResult("h", any()) } returns DelegationWaitOutcome.NotActive("already_completed")
        val result = tool.execute(waitParams("h"), project)
        assertFalse(result.isError)
        assertTrue(result.content.contains("completed"))
        assertTrue(result.content.contains("fetch_transcript"))
    }

    @Test
    fun `wait requires a handle`() = runBlocking {
        val result = tool.execute(buildJsonObject { put("action", JsonPrimitive("wait")) }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("handle"))
    }
}
