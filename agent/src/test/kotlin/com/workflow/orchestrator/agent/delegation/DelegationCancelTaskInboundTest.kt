package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import com.workflow.orchestrator.core.delegation.DelegationMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * IDE-B side of the orphan-cancel fix. Drives [DelegationInboundService.runInboundReadLoop]
 * directly (the same harness Plan4ReviewFollowupsTest uses for the UserTurn branch) and asserts:
 *  - a [DelegationMessage.CancelTask] cancels the registered delegated agent Job;
 *  - a socket EOF while the session is NON-terminal cancels the Job;
 *  - a socket EOF AFTER the session reached terminal does NOT cancel (no spurious cancel on a
 *    normal completion-close).
 */
class DelegationCancelTaskInboundTest {

    @BeforeEach fun setup() = installReadActionInlineShim()
    @AfterEach fun tearDown() = unmockkAll()

    private fun newInbound(): DelegationInboundService {
        val agentService = mockk<AgentService>(relaxed = true)
        val project = mockk<Project>(relaxed = true)
        every { project.messageBus } returns mockk(relaxed = true)
        every { project.getService(AgentService::class.java) } returns agentService
        val cs = CoroutineScope(SupervisorJob())
        return DelegationInboundService(project, cs)
    }

    /** A long-running job we can assert gets cancelled. */
    private fun CoroutineScope.longRunningJob(): Job =
        launch(Dispatchers.IO) {
            // Never completes on its own within the test window.
            delay(60_000)
        }

    @Test
    fun `CancelTask cancels the registered delegated Job`() = runBlocking {
        val inbound = newInbound()
        val sid = "sess-cancel-1"
        inbound.registerSessionChannel(sid) { /* no-op replyWith */ }

        val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = jobScope.longRunningJob()
        inbound.attachJob(sid, job)

        var n = 0
        val readMessage: suspend () -> DelegationMessage = {
            when (n++) {
                0 -> DelegationMessage.CancelTask(sessionId = sid, reason = "parent_canceled")
                else -> throw java.nio.channels.ClosedChannelException()
            }
        }

        inbound.runInboundReadLoop(sid, readMessage) { }

        withTimeout(3_000) { job.join() }
        assertTrue(job.isCancelled, "CancelTask must cancel the delegated agent Job")
        jobScope.cancel()
    }

    @Test
    fun `socket EOF while NON-terminal cancels the Job`() = runBlocking {
        val inbound = newInbound()
        val sid = "sess-eof-nonterminal"
        inbound.registerSessionChannel(sid) { }

        val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = jobScope.longRunningJob()
        inbound.attachJob(sid, job)
        // markTerminal NOT called → still running.

        // First read throws EOF immediately (IDE-A's socket dropped with no frame).
        val readMessage: suspend () -> DelegationMessage = {
            throw java.io.IOException("unexpected EOF")
        }

        inbound.runInboundReadLoop(sid, readMessage) { }

        withTimeout(3_000) { job.join() }
        assertTrue(job.isCancelled, "non-terminal socket EOF must cancel the orphaned Job")
        jobScope.cancel()
    }

    @Test
    fun `socket EOF AFTER terminal does NOT cancel the Job`() = runBlocking {
        val inbound = newInbound()
        val sid = "sess-eof-terminal"
        inbound.registerSessionChannel(sid) { }

        // A job that finishes promptly on its own (simulating a completed loop).
        val finished = CompletableDeferred<Unit>()
        val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = jobScope.launch {
            finished.await() // we control completion below
        }
        inbound.attachJob(sid, job)

        // The loop reached a terminal state — mark it BEFORE the close-driven EOF arrives.
        inbound.markTerminal(sid)

        val readMessage: suspend () -> DelegationMessage = {
            throw java.nio.channels.ClosedChannelException()
        }

        inbound.runInboundReadLoop(sid, readMessage) { }

        // The reader must NOT have cancelled the job. Let it finish naturally.
        finished.complete(Unit)
        withTimeout(3_000) { job.join() }
        assertFalse(job.isCancelled, "a normal completion-close must NOT cancel the (finished) Job")
        jobScope.cancel()
    }

    @Test
    fun `end-to-end - IDE-A CancelTask over a real socket cancels IDE-B running Job`(
        @TempDir tmp: java.nio.file.Path,
    ) = runBlocking {
        // IDE-B: real DelegationServer + real handleConnect, with the delegated-session leg faked
        // via testDelegatedSessionStarter (no live AgentController in a headless test). The fake
        // starter registers the channel itself? No — registerSessionChannel + attachJob live in
        // AgentService.startDelegatedSession, which the controller calls in production. Here we
        // simulate exactly that wiring inside the fake starter so the orphan-cancel path is exercised
        // end-to-end over the real socket.
        val ideBRoot = java.nio.file.Files.createDirectory(tmp.resolve("ide-b"))
        val settingsB = com.workflow.orchestrator.core.settings.PluginSettings()
        settingsB.state.enableInboundCrossIdeDelegation = true
        val agentService = mockk<AgentService>(relaxed = true)
        val projectB = mockk<Project>(relaxed = true).also {
            every { it.basePath } returns ideBRoot.toString()
            every { it.messageBus } returns mockk(relaxed = true)
            every { it.getService(com.workflow.orchestrator.core.settings.PluginSettings::class.java) } returns settingsB
            every { it.getService(AgentService::class.java) } returns agentService
        }
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val inbound = DelegationInboundService(projectB, scopeB)

        val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val delegatedJob = jobScope.longRunningJob()
        val jobAttached = CompletableDeferred<Unit>()

        // Mirror AgentService.startDelegatedSession's orphan-cancel wiring inside the fake starter.
        inbound.testDelegatedSessionStarter =
            DelegatedSessionStarter { _request, _md, _reply, _onResult, onSessionStarted, _onBusy ->
                val sid = "b-sess-e2e"
                inbound.registerSessionChannel(sid, _reply)
                inbound.attachJob(sid, delegatedJob)
                // NOTE: do NOT markTerminal — the job is still running.
                onSessionStarted?.invoke(sid)
                jobAttached.complete(Unit)
                com.workflow.orchestrator.agent.ui.DelegatedStartOutcome.STARTED
            }
        inbound.start()
        val doorSocket = com.workflow.orchestrator.core.delegation.DelegationPaths.socketFor(ideBRoot)
        try {
            // IDE-A: real connect over the socket, preauth skips the Accept dialog.
            // No preauth nonce here → handleConnect would show the Accept dialog (needs EDT) — so
            // instead record a preauth nonce and carry it on Connect to skip the dialog headlessly.
            val nonce = "nonce-e2e"
            inbound.recordPreauth(nonce)
            val pair = com.workflow.orchestrator.core.delegation.DelegationClient.connectAndAwaitAccept(
                doorSocket,
                DelegationMessage.Connect(
                    delegatorIde = "ide-a",
                    delegatorRepo = "repo-a",
                    delegatorSessionId = "a-sess",
                    request = "do work",
                    preauthNonce = nonce,
                ),
            )
            assertTrue(pair != null, "connect must succeed")
            val (channel, ack) = pair!!
            assertTrue(ack.accepted, "delegation must be accepted via preauth")

            withTimeout(5_000) { jobAttached.await() }

            // IDE-A cancels: send a CancelTask frame on the live channel (what
            // DelegationOutboundService.close does just before ch.close()).
            com.workflow.orchestrator.core.delegation.DelegationFraming.writeFramed(
                channel,
                DelegationMessage.CancelTask(sessionId = ack.bSessionId ?: "b-sess-e2e", reason = "parent_canceled"),
                Json { ignoreUnknownKeys = true; classDiscriminator = "type" },
            )

            withTimeout(5_000) { delegatedJob.join() }
            assertTrue(delegatedJob.isCancelled, "IDE-A CancelTask must cancel IDE-B's running delegated Job")
            channel.close()
        } finally {
            inbound.stop(); scopeB.cancel(); jobScope.cancel()
            runCatching { java.nio.file.Files.deleteIfExists(doorSocket) }
        }
        Unit
    }

    @Test
    fun `attachJob and markTerminal are no-ops on an unknown session`() {
        val inbound = newInbound()
        // No registerSessionChannel — both must silently no-op (no NPE).
        val jobScope = CoroutineScope(SupervisorJob())
        val job = jobScope.launch { delay(1_000) }
        // Both calls must silently no-op (no NPE) and must NOT touch the unrelated job.
        inbound.attachJob("nope", job)
        inbound.markTerminal("nope")
        assertFalse(job.isCancelled, "attach/markTerminal on an unknown session must not touch any job")
        jobScope.cancel()
    }
}
