package com.workflow.orchestrator.agent.tools.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.delegation.DelegationException
import com.workflow.orchestrator.agent.delegation.DelegationOutboundService
import com.workflow.orchestrator.agent.delegation.DelegationStatusResult
import com.workflow.orchestrator.agent.delegation.DelegationWaitOutcome
import com.workflow.orchestrator.agent.delegation.FetchTranscriptResult
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

        // The `send` action resolves AgentService to read the delegator session id; stub it so the
        // continuation branch reaches sendContinuation (where the HandleNotFound mapping lives).
        val agentService = mockk<com.workflow.orchestrator.agent.AgentService>(relaxed = true)
        every { agentService.currentSessionState() } returns
            com.workflow.orchestrator.agent.session.PerSessionAgentState("sess-a-test")
        every { project.getService(com.workflow.orchestrator.agent.AgentService::class.java) } returns agentService
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

    @Test
    fun `all five handle-reading actions surface DelegationHandleNotFound for the same unknown handle`() = runBlocking {
        val handle = "phantom"
        // Single source of truth says Unknown for every existence check.
        every { outbound.handleState(handle) } returns HandleState.Unknown
        every { outbound.statusOf(handle) } returns DelegationStatusResult.Unknown
        // send-continuation throws HandleNotFound for the unknown case (service-layer fix).
        coEvery { outbound.sendContinuation(handle, any(), any()) } throws
            DelegationException.HandleNotFound(handle)
        // fetch_transcript classifies the unknown handle as HANDLE_UNKNOWN.
        coEvery { outbound.fetchTranscript(handle) } returns
            FetchTranscriptResult.NotFound("handle_not_found", FetchTranscriptResult.NotFoundKind.HANDLE_UNKNOWN)
        // wait → NotActive with a non-"already_completed" reason (truly unknown).
        coEvery { outbound.awaitResult(handle, any()) } returns
            DelegationWaitOutcome.NotActive("handle_not_found")

        fun typeOf(r: com.workflow.orchestrator.agent.tools.ToolResult): String =
            r.content + " " + r.summary

        val status = tool.execute(params("status", handle), project)
        val wait = tool.execute(params("wait", handle), project)
        val answer = tool.execute(params("answer", handle, mapOf("question_id" to "q", "answer" to "x")), project)
        val fetch = tool.execute(params("fetch_transcript", handle), project)
        val send = tool.execute(params("send", handle, mapOf("request" to "follow up")), project)

        for ((name, r) in listOf("status" to status, "wait" to wait, "answer" to answer, "fetch_transcript" to fetch, "send" to send)) {
            assertTrue(r.isError, "$name must be an error for an unknown handle")
            assertTrue(
                typeOf(r).contains("DelegationHandleNotFound"),
                "$name must surface DelegationHandleNotFound for an unknown handle; got: ${typeOf(r)}",
            )
            assertFalse(
                typeOf(r).contains("DelegationExpired"),
                "$name must NOT surface DelegationExpired for an unknown handle; got: ${typeOf(r)}",
            )
        }
    }

    @Test
    fun `fetch_transcript still returns the transcript for a closed-but-retained handle`() = runBlocking {
        val handle = "h-retained"
        coEvery { outbound.fetchTranscript(handle) } returns
            FetchTranscriptResult.Ok(transcriptPath = "/tmp/delegation-transcript-$handle.json")

        val result = tool.executeFetchTranscriptRaw(project, handle)
        // Ok path attempts to read the (non-existent) file head but is NOT a handle-not-found error;
        // the path is surfaced regardless.
        assertTrue(
            (result.content + result.summary).contains("/tmp/delegation-transcript-$handle.json"),
            "closed-but-retained fetch must surface the transcript path; got: ${result.content}",
        )
        assertFalse(
            (result.content + result.summary).contains("DelegationHandleNotFound"),
            "a retained transcript must not be reported as not-found",
        )
    }

    @Test
    fun `fetch_transcript maps a genuine transcript-unreachable expiry to DelegationExpired`() = runBlocking {
        val handle = "h-known-no-disk"
        coEvery { outbound.fetchTranscript(handle) } returns
            FetchTranscriptResult.NotFound(
                "no conversation history on disk for session sess-b",
                FetchTranscriptResult.NotFoundKind.TRANSCRIPT_UNREACHABLE,
            )

        val result = tool.executeFetchTranscriptRaw(project, handle)
        assertTrue(result.isError)
        val text = result.content + result.summary
        assertTrue(text.contains("DelegationExpired"), "genuine transcript-unreachable must map to DelegationExpired; got: $text")
        assertFalse(text.contains("DelegationHandleNotFound"))
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
