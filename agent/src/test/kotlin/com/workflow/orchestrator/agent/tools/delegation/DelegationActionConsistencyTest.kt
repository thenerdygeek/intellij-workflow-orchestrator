package com.workflow.orchestrator.agent.tools.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.delegation.DelegationException
import com.workflow.orchestrator.agent.delegation.DelegationOutboundService
import com.workflow.orchestrator.agent.delegation.DelegationStatusResult
import com.workflow.orchestrator.agent.delegation.HandleState
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Fix A — cross-action consistency at the [DelegationTool] layer. For a CLOSED-but-retained
 * handle, `status`, `answer`, and `send`-continuation must agree: all three must report a
 * coherent "closed/known within retention" classification, NOT three different answers
 * (`status:closed` vs `answer:HandleNotFound` vs `send:handle_not_found`).
 *
 * `answer` runs with auto-approve ON so the flow reaches the existence check without opening
 * an EDT dialog.
 */
class DelegationActionConsistencyTest {

    private val outbound = mockk<DelegationOutboundService>(relaxed = true)
    private val project = mockk<Project>(relaxed = true)
    private val tool = DelegationTool()

    @BeforeEach fun setup() {
        installReadActionInlineShim()
        mockkObject(PluginSettings.Companion)
        val settings = mockk<PluginSettings>(relaxed = true)
        val state = mockk<PluginSettings.State>(relaxed = true)
        every { state.enableOutboundCrossIdeDelegation } returns true
        every { state.autoApproveDelegationAnswers } returns true
        every { settings.state } returns state
        every { PluginSettings.getInstance(any()) } returns settings
        every { project.getService(DelegationOutboundService::class.java) } returns outbound
    }

    @AfterEach fun tearDown() { unmockkAll() }

    private fun params(action: String, handle: String, extra: Map<String, String> = emptyMap()): JsonObject =
        buildJsonObject {
            put("action", JsonPrimitive(action))
            put("handle", JsonPrimitive(handle))
            extra.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }

    // ── closed-but-retained: status + answer agree on "closed/known" ───────────

    @Test
    fun `status and answer agree a closed-but-retained handle is closed-not-unknown`() = runBlocking {
        val handle = "h-closed"
        every { outbound.statusOf(handle) } returns DelegationStatusResult.Closed("COMPLETED", "frontend", 1L)
        every { outbound.handleState(handle) } returns HandleState.ClosedRetained("COMPLETED", "frontend", 1L)

        // status → closed
        val status = tool.execute(params("status", handle), project)
        assertFalse(status.isError)
        assertTrue(status.content.contains("\"status\":\"closed\""))

        // answer → must NOT claim the handle is simply unknown/not-found; it must reflect that
        // the session is CLOSED (consistent with status), and must NOT attempt the write.
        val answer = tool.execute(
            params("answer", handle, mapOf("question_id" to "q1", "answer" to "yes")),
            project,
        )
        val text = answer.content + " " + answer.summary
        assertTrue(answer.isError, "answer on a closed handle must be an error")
        assertTrue(
            text.contains("closed") || text.contains("completed"),
            "answer must report the session as closed/completed (consistent with status); got: $text",
        )
    }

    @Test
    fun `answer on a closed-but-retained handle does not call sendAnswer`() = runBlocking {
        val handle = "h-closed2"
        every { outbound.handleState(handle) } returns HandleState.ClosedRetained("COMPLETED", "backend", 1L)
        coEvery { outbound.sendAnswer(any(), any(), any()) } returns true

        tool.execute(params("answer", handle, mapOf("question_id" to "q", "answer" to "x")), project)

        io.mockk.coVerify(exactly = 0) { outbound.sendAnswer(any(), any(), any()) }
    }

    // ── unknown: status + answer agree on not-found ────────────────────────────

    @Test
    fun `status and answer agree an unknown handle is not-found`() = runBlocking {
        val handle = "ghost"
        every { outbound.statusOf(handle) } returns DelegationStatusResult.Unknown
        every { outbound.handleState(handle) } returns HandleState.Unknown

        val status = tool.execute(params("status", handle), project)
        assertTrue(status.isError)

        val answer = tool.execute(params("answer", handle, mapOf("question_id" to "q", "answer" to "x")), project)
        assertTrue(answer.isError)
        assertTrue(
            (answer.content + answer.summary).contains("DelegationHandleNotFound"),
            "unknown handle answer must be DelegationHandleNotFound",
        )
    }

    // ── active: answer proceeds to the write ───────────────────────────────────

    @Test
    fun `answer on an active handle proceeds to sendAnswer`() = runBlocking {
        val handle = "h-active"
        every { outbound.handleState(handle) } returns HandleState.Active("RUNNING", "frontend")
        coEvery { outbound.sendAnswer(handle, "q", "x") } returns true

        val answer = tool.execute(params("answer", handle, mapOf("question_id" to "q", "answer" to "x")), project)
        assertFalse(answer.isError)
        assertTrue(answer.content.contains("\"sent\":true"))
        io.mockk.coVerify(exactly = 1) { outbound.sendAnswer(handle, "q", "x") }
    }
}
