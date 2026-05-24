package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import com.workflow.orchestrator.core.delegation.DelegationMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for Plan 4 code-review follow-up fixes (H1–H4, M2).
 *
 * H3: UserTurn branch in DelegationInboundService read-loop.
 * H1: Resumed outbound channel gets a reader loop.
 * H2: handleChannelResume calls runInboundReadLoop (source-text pin).
 * H4: sendContinuation enqueues re-attach nudge on resume.
 * M2: showSession re-pushes delegation-question banner state.
 */
class Plan4ReviewFollowupsTest {

    @BeforeEach fun setup() { installReadActionInlineShim() }
    @AfterEach fun tearDown() { unmockkAll() }

    // ── H3: UserTurn dispatch in DelegationInboundService ─────────────────────

    /**
     * H3: Inject a UserTurn into runInboundReadLoop and verify that
     * AgentService.enqueueNudgeForSession is called with the UserTurn text.
     */
    @Test
    fun `H3 - UserTurn message is dispatched to AgentService enqueueNudgeForSession`() {
        val capturedNudges = mutableListOf<String>()
        val agentService = mockk<AgentService>(relaxed = true)
        every {
            agentService.enqueueNudgeForSession(any(), capture(capturedNudges))
        } returns Unit

        val project = mockk<Project>(relaxed = true)
        every { project.messageBus } returns mockk(relaxed = true)
        every { project.getService(AgentService::class.java) } returns agentService

        val cs = CoroutineScope(SupervisorJob())
        val service = DelegationInboundService(project, cs)
        val sessionId = "sess-b-h3"
        service.registerSessionChannel(sessionId) { /* no-op replyWith */ }

        val userTurnText = "Here is the follow-up from IDE-A"
        var callCount = 0
        val readMessage: suspend () -> DelegationMessage = {
            when (callCount++) {
                0 -> DelegationMessage.UserTurn(sessionId = sessionId, text = userTurnText)
                else -> throw java.nio.channels.ClosedChannelException()
            }
        }

        runBlocking {
            service.runInboundReadLoop(sessionId, readMessage) { }
        }

        assertTrue(
            capturedNudges.any { it == userTurnText },
            "Expected enqueueNudgeForSession called with '$userTurnText', got: $capturedNudges"
        )
    }

    @Test
    fun `H3 - source text pin - runInboundReadLoop has UserTurn branch calling enqueueNudgeForSession`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/workflow/orchestrator/agent/delegation/DelegationInboundService.kt")
        )
        assertTrue(
            source.contains("is DelegationMessage.UserTurn"),
            "DelegationInboundService.runInboundReadLoop must have a UserTurn branch (H3)"
        )
        assertTrue(
            source.contains("enqueueNudgeForSession"),
            "DelegationInboundService must call enqueueNudgeForSession for UserTurn delivery (H3)"
        )
    }

    // ── H1 + H4: Outbound resumed channel gets reader loop + re-attach nudge ──

    /**
     * H4: When sendContinuation triggers attemptResume and gets Resumed,
     * it must enqueue a nudge containing "re-attached" with the last-known and
     * current state names BEFORE writing the UserTurn frame.
     */
    @Test
    fun `H4 - sendContinuation enqueues re-attach nudge with state names on Resumed`() {
        val capturedNudges = mutableListOf<String>()
        val agentService = mockk<AgentService>(relaxed = true)
        every {
            agentService.enqueueNudgeForSession(any(), capture(capturedNudges))
        } returns Unit

        val project = mockk<Project>(relaxed = true)
        every { project.messageBus } returns mockk(relaxed = true)
        every { project.getService(AgentService::class.java) } returns agentService
        every { project.basePath } returns "/tmp/test-proj-h4"

        val cs = CoroutineScope(SupervisorJob())
        val outbound = DelegationOutboundService(project, cs)

        seedOutboundMap(outbound, "handleToSessionId",   "h-h4", "sess-a-h4")
        seedOutboundMap(outbound, "handleToBSessionId",  "h-h4", "sess-b-h4")
        seedOutboundMap(outbound, "handleToLastSeenState","h-h4", "AWAITING_ANSWER")
        seedOutboundMap(outbound, "handleToTargetPath",  "h-h4", "/tmp/target-h4")
        seedOutboundMap(outbound, "handleToRepoName",    "h-h4", "backend-repo")

        outbound.testResumeProbe = { _, _ ->
            DelegationMessage.ChannelResumed(sessionId = "sess-b-h4", currentState = "RUNNING")
        }

        runBlocking {
            try {
                outbound.sendContinuation("h-h4", "do this task", "sess-a-h4")
            } catch (_: DelegationException) {
                // Expected — testResumeProbe returns ChannelResumed but no real SocketChannel
                // was opened, so activeChannels[h-h4] is null → resume_inconsistent.
            }
        }

        val reattachNudge = capturedNudges.firstOrNull { it.contains("re-attached") }
        assertFalse(reattachNudge.isNullOrBlank(),
            "Expected re-attach nudge but none found. All nudges: $capturedNudges")
        assertTrue(reattachNudge!!.contains("AWAITING_ANSWER"),
            "re-attach nudge must include last-known state 'AWAITING_ANSWER' but got: $reattachNudge")
        assertTrue(reattachNudge.contains("RUNNING"),
            "re-attach nudge must include current state 'RUNNING' from ChannelResumed but got: $reattachNudge")
        assertTrue(reattachNudge.contains("backend-repo"),
            "re-attach nudge must include the repo name but got: $reattachNudge")
    }

    @Test
    fun `H1 - source text pin - runOutboundReaderLoop is defined and called from send coroutine`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/workflow/orchestrator/agent/delegation/DelegationOutboundService.kt")
        )
        assertTrue(
            source.contains("private suspend fun runOutboundReaderLoop"),
            "DelegationOutboundService must declare private suspend fun runOutboundReaderLoop (H1)"
        )
        // send() must launch it in a coroutine (the same cs.launch that used to inline the loop).
        assertTrue(
            source.contains("runOutboundReaderLoop(handle, channel, onResult)"),
            "DelegationOutboundService.send coroutine must call runOutboundReaderLoop(handle, channel, onResult) (H1)"
        )
        // sendContinuation must also spawn it for resumed channels.
        assertTrue(
            source.contains("runOutboundReaderLoop(handle, resumedChannel)"),
            "DelegationOutboundService.sendContinuation must call runOutboundReaderLoop on the resumed channel (H1)"
        )
    }

    // ── H2: handleChannelResume runs the read-loop ─────────────────────────────

    @Test
    fun `H2 - source text pin - handleChannelResume calls runInboundReadLoop`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/workflow/orchestrator/agent/delegation/DelegationInboundService.kt")
        )
        assertTrue(
            source.contains("internal suspend fun runInboundReadLoop"),
            "DelegationInboundService must declare runInboundReadLoop as internal (H2)"
        )
        // Count call sites — both handleConnect (via 'try { runInboundReadLoop') and
        // handleChannelResume must call it.
        val occurrences = source.split("runInboundReadLoop(").size - 1
        assertTrue(
            occurrences >= 2,
            "runInboundReadLoop must be called from at least 2 sites (handleConnect + handleChannelResume) but found $occurrences (H2)"
        )
    }

    @Test
    fun `H2 - source text pin - DelegationServer onChannelResume callback accepts readMessage param`() {
        val coreSource = Files.readString(
            Path.of("../core/src/main/kotlin/com/workflow/orchestrator/core/delegation/DelegationServer.kt")
        )
        assertTrue(
            coreSource.contains("readMessage: suspend () -> DelegationMessage"),
            "DelegationServer.onChannelResume lambda must include readMessage param (H2)"
        )
        // The server must pass readMessage when calling the callback.
        assertTrue(
            coreSource.contains("onChannelResume(msg, replyWith, readMessage, closeChannel)"),
            "DelegationServer must pass readMessage when invoking onChannelResume callback (H2)"
        )
    }

    // ── M2: showSession re-pushes banner state ─────────────────────────────────

    @Test
    fun `M2 - source text pin - showSession re-queries pending question state after setting viewedSessionId`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt")
        )
        assertTrue(
            source.contains("inboundService.hasPendingQuestion(sessionId)"),
            "AgentController.showSession must call hasPendingQuestion to re-check banner state (M2)"
        )
        assertTrue(
            source.contains("pushDelegationQuestionPending(sessionId, active = true"),
            "AgentController.showSession must push active=true when a pending question exists (M2)"
        )
        assertTrue(
            source.contains("pushDelegationQuestionPending(sessionId, active = false"),
            "AgentController.showSession must push active=false to clear any stale banner (M2)"
        )
        // The banner re-push must appear AFTER viewedSessionId = sessionId in source ordering
        // (needs viewedSessionId to already be set so pushDelegationQuestionPending's
        // viewedSessionId == sessionId guard passes).
        val viewedAssignPos = source.indexOf("viewedSessionId = sessionId")
        val hasPendingPos   = source.indexOf("inboundService.hasPendingQuestion(sessionId)")
        assertTrue(
            hasPendingPos > viewedAssignPos,
            "hasPendingQuestion check (pos $hasPendingPos) must appear AFTER viewedSessionId = sessionId " +
                "(pos $viewedAssignPos) in showSession (M2)"
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun seedOutboundMap(svc: DelegationOutboundService, field: String, key: String, value: String) {
        val f = DelegationOutboundService::class.java.getDeclaredField(field).apply { isAccessible = true }
        (f.get(svc) as java.util.concurrent.ConcurrentHashMap<String, String>)[key] = value
    }
}
