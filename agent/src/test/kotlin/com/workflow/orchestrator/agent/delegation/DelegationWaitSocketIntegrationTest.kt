package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.delegation.ui.PickerEntry
import com.workflow.orchestrator.core.delegation.DelegationFraming
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path

/**
 * REAL-SOCKET integration coverage for the explicit `wait` attach path: drives the actual
 * private [DelegationOutboundService] outbound reader loop over a live Unix-domain socket
 * pair (via the test-injectable `pingFn` / `connectFn` / `pickTargetOverride` seams), then
 * writes real protocol frames from the peer end and asserts:
 *
 *  1. a `Result` frame completes a pending `awaitResult` waiter (inline delivery),
 *  2. a `Question` frame completes the waiter so the LLM can answer,
 *  3. with NO waiter registered, a `Result` still reaches the async `onResult` callback
 *     (the non-wait path is preserved — nudge suppression only applies to waiters).
 *
 * This upgrades the reader-loop ↔ waiter wiring from a source-text pin to behavioral.
 */
class DelegationWaitSocketIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    @AfterEach fun tearDown() { unmockkAll() }

    @Suppress("UNCHECKED_CAST")
    private fun waiterFor(svc: DelegationOutboundService, handleId: String): CompletableDeferred<DelegationWaitOutcome>? =
        (DelegationOutboundService::class.java.getDeclaredField("pendingResultWaiters")
            .apply { isAccessible = true }.get(svc)
            as java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<DelegationWaitOutcome>>)[handleId]

    private class Wired(
        val svc: DelegationOutboundService,
        val peer: SocketChannel,        // server side — we write frames here
        val handleId: String,
        val onResult: CompletableDeferred<DelegationMessage.Result>,
        val cs: CoroutineScope,
        val server: ServerSocketChannel,
        val client: SocketChannel,      // outbound side — the reader loop reads here
    )

    /** Stand up a real socket pair and start the outbound reader loop on the client end. */
    private suspend fun wire(tmp: Path): Wired {
        val addr = UnixDomainSocketAddress.of(tmp.resolve("wait-int.sock"))
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(addr)
        val (client, peer) = coroutineScope {
            val acc = async(Dispatchers.IO) { server.accept() }
            val c = withContext(Dispatchers.IO) { SocketChannel.open(addr) }
            c to acc.await()
        }

        val project = mockk<Project>(relaxed = true)
        // Idle timer reads this; 0 disables it so it never interferes.
        val settings = mockk<PluginSettings>(relaxed = true)
        every { settings.state.delegationIdleTimeoutMinutes } returns 0
        every { project.getService(PluginSettings::class.java) } returns settings

        val cs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val svc = DelegationOutboundService(project, cs)
        svc.pingFn = { DelegationMessage.Pong(projectPath = tmp.toString()) }  // existing-channel path
        svc.connectFn = { _, _ -> client to DelegationMessage.AcceptResult(accepted = true, bSessionId = "sess-b") }
        svc.pickTargetOverride = { PickerEntry(path = tmp, displayName = "repo", status = PickerEntry.Status.RUNNING) }

        val onResult = CompletableDeferred<DelegationMessage.Result>()
        val handle = svc.send(request = "do it", suggestedRepo = null, delegatorSessionId = "sess-a") { _, r ->
            onResult.complete(r)
        }
        return Wired(svc, peer, handle.id, onResult, cs, server, client)
    }

    private fun cleanup(w: Wired) {
        runCatching { w.peer.close() }
        runCatching { w.client.close() }
        runCatching { w.server.close() }
        w.cs.cancel()
    }

    private suspend fun writeFrame(w: Wired, msg: DelegationMessage) =
        withContext(Dispatchers.IO) { DelegationFraming.writeFramed(w.peer, msg, json) }

    @Test
    fun `wait returns Completed when a Result frame arrives over the socket`(@TempDir tmp: Path) = runBlocking {
        val w = wire(tmp)
        try {
            val waitJob = async { w.svc.awaitResult(w.handleId, 5_000L) }
            while (waiterFor(w.svc, w.handleId) == null) delay(5)   // ensure the waiter is registered first
            writeFrame(w, DelegationMessage.Result(status = DelegationMessage.ResultStatus.COMPLETED, summary = "socket done"))
            val outcome = withTimeout(5_000) { waitJob.await() }
            val c = assertInstanceOf(DelegationWaitOutcome.Completed::class.java, outcome)
            assertEquals("socket done", c.result.summary)
        } finally { cleanup(w) }
    }

    @Test
    fun `wait returns Question when a Question frame arrives over the socket`(@TempDir tmp: Path) = runBlocking {
        val w = wire(tmp)
        try {
            val waitJob = async { w.svc.awaitResult(w.handleId, 5_000L) }
            while (waiterFor(w.svc, w.handleId) == null) delay(5)
            writeFrame(w, DelegationMessage.Question(questionId = "q1", text = "which env?"))
            val outcome = withTimeout(5_000) { waitJob.await() }
            val q = assertInstanceOf(DelegationWaitOutcome.Question::class.java, outcome)
            assertEquals("q1", q.question.questionId)
            assertEquals("which env?", q.question.text)
        } finally { cleanup(w) }
    }

    @Test
    fun `without a waiter a Result frame reaches the async onResult callback`(@TempDir tmp: Path) = runBlocking {
        val w = wire(tmp)
        try {
            writeFrame(w, DelegationMessage.Result(status = DelegationMessage.ResultStatus.COMPLETED, summary = "async path"))
            val r = withTimeout(5_000) { w.onResult.await() }
            assertEquals("async path", r.summary)
        } finally { cleanup(w) }
    }
}
