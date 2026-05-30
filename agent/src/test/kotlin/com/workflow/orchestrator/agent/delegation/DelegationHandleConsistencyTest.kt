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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path

/**
 * Fix A (2026-05-30 cross-IDE bugfix campaign): the three handle actions (`status`,
 * `answer`-existence-check, `send`-continuation-existence-check) must AGREE about whether a
 * handle is known and in what state. The reported bug: for ONE completed delegation handle,
 * `status` said `{closed, last_state:RUNNING}`, `send` said `DelegationExpired:handle_not_found`,
 * and `answer` said `DelegationHandleNotFound` — three contradictory answers for one handle.
 *
 * These tests drive the new single-source-of-truth [DelegationOutboundService.handleState] and
 * assert (1) consistency across all three call sites after close, (2) the retained `lastState`
 * reflects the terminal Result state (COMPLETED) rather than the stale seed "RUNNING", and
 * (3) a genuinely unknown handle is reported Unknown by all three.
 */
class DelegationHandleConsistencyTest {

    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    @AfterEach fun tearDown() { unmockkAll() }

    private fun newService(): DelegationOutboundService {
        val project = mockk<Project>(relaxed = true)
        return DelegationOutboundService(project, CoroutineScope(SupervisorJob()))
    }

    private fun seedMap(svc: DelegationOutboundService, field: String, key: String, value: String) {
        val f = DelegationOutboundService::class.java.getDeclaredField(field).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(svc) as java.util.concurrent.ConcurrentHashMap<String, String>)[key] = value
    }

    // ── (3) Unknown handle: all three agree it is Unknown ──────────────────────

    @Test
    fun `handleState reports Unknown for a never-seen handle`() {
        val svc = newService()
        assertInstanceOf(HandleState.Unknown::class.java, svc.handleState("ghost"))
    }

    @Test
    fun `statusOf and hasOpenChannel and sendContinuation all treat an unknown handle as not-found`() {
        val svc = newService()
        // status → Unknown
        assertEquals(DelegationStatusResult.Unknown, svc.statusOf("ghost"))
        // answer existence check → not open
        assertTrue(!svc.hasOpenChannel("ghost"))
        // send-continuation → Expired(handle_not_found), distinct from a retained-closed handle
        val ex = runCatching {
            runBlocking { svc.sendContinuation("ghost", "follow up", "sess-a") }
        }.exceptionOrNull()
        val expired = assertInstanceOf(DelegationException.Expired::class.java, ex)
        assertEquals("handle_not_found", expired.expireReason)
    }

    // ── (1) Closed-but-retained handle: all three agree it is "closed/known" ───

    @Test
    fun `after close all three call sites agree the handle is closed-but-known`() {
        val svc = newService()
        val handleId = "h-closed"
        // Seed enough live state that close() snapshots a RetainedHandle.
        seedMap(svc, "handleToSessionId", handleId, "sess-a")
        seedMap(svc, "handleToBSessionId", handleId, "sess-b")
        seedMap(svc, "handleToTargetPath", handleId, "/target")
        seedMap(svc, "handleToRepoName", handleId, "frontend")
        seedMap(svc, "handleToLastSeenState", handleId, "RUNNING")

        svc.close(handleId)

        // Single source of truth says ClosedRetained.
        val hs = assertInstanceOf(HandleState.ClosedRetained::class.java, svc.handleState(handleId))
        assertEquals("frontend", hs.repoName)

        // status → Closed (same classification).
        assertInstanceOf(DelegationStatusResult.Closed::class.java, svc.statusOf(handleId))

        // send-continuation → Fix 3 (true continuation): a closed-but-retained handle now RESURRECTS
        // the completed IDE-B conversation rather than dead-ending. With no live IDE-B at /target the
        // resurrection dial fails with a DISTINCT reattach reason (ide_b_not_running / io_error /
        // session_closed / session_not_found) — crucially still NOT a bare handle_not_found (which is
        // reserved for a truly-unknown/pruned handle).
        val ex = runCatching {
            runBlocking { svc.sendContinuation(handleId, "follow up", "sess-a") }
        }.exceptionOrNull()
        val expired = assertInstanceOf(DelegationException.Expired::class.java, ex)
        val reason = expired.expireReason ?: ""
        assertTrue(reason != "handle_not_found", "closed-but-retained must NOT be bare handle_not_found; got: $reason")
        assertTrue(
            reason.contains("ide_b_not_running") || reason.contains("io_error") ||
                reason.contains("session_closed") || reason.contains("session_not_found"),
            "closed-but-retained continuation must surface a distinct resurrection/reattach reason; got: $reason",
        )
    }

    // ── (2) Terminal Result updates retained lastState to COMPLETED ────────────

    private class Wired(
        val svc: DelegationOutboundService,
        val peer: SocketChannel,
        val handleId: String,
        val onResult: CompletableDeferred<DelegationMessage.Result>,
        val cs: CoroutineScope,
        val server: ServerSocketChannel,
        val client: SocketChannel,
    )

    /** Stand up a real socket pair and start the outbound reader loop on the client end. */
    private suspend fun wire(tmp: Path): Wired {
        val addr = UnixDomainSocketAddress.of(tmp.resolve("consistency.sock"))
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(addr)
        val (client, peer) = coroutineScope {
            val acc = async(Dispatchers.IO) { server.accept() }
            val c = withContext(Dispatchers.IO) { SocketChannel.open(addr) }
            c to acc.await()
        }

        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { settings.state.delegationIdleTimeoutMinutes } returns 0
        every { project.getService(PluginSettings::class.java) } returns settings

        val cs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val svc = DelegationOutboundService(project, cs)
        svc.pingFn = { DelegationMessage.Pong(projectPath = tmp.toString()) }
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
    fun `a terminal COMPLETED Result updates the retained lastState (not stale RUNNING)`(@TempDir tmp: Path) = runBlocking {
        val w = wire(tmp)
        try {
            // The handle is RUNNING until the terminal frame.
            assertInstanceOf(HandleState.Active::class.java, w.svc.handleState(w.handleId))

            // Peer sends the terminal Result; the reader loop processes it, then its finally
            // closes the handle and snapshots the RetainedHandle.
            writeFrame(w, DelegationMessage.Result(status = DelegationMessage.ResultStatus.COMPLETED, summary = "done"))
            withTimeout(5_000) { w.onResult.await() }

            // Wait for the reader loop's finally → close() to have snapshotted the retained handle.
            var hs = w.svc.handleState(w.handleId)
            val deadline = System.currentTimeMillis() + 5_000
            while (hs !is HandleState.ClosedRetained && System.currentTimeMillis() < deadline) {
                delay(10)
                hs = w.svc.handleState(w.handleId)
            }
            val closed = assertInstanceOf(HandleState.ClosedRetained::class.java, hs)
            assertEquals("COMPLETED", closed.lastState, "retained lastState must reflect the terminal Result status")

            // statusOf surfaces the same terminal state to the tool layer.
            val status = assertInstanceOf(DelegationStatusResult.Closed::class.java, w.svc.statusOf(w.handleId))
            assertEquals("COMPLETED", status.lastState)
        } finally { cleanup(w) }
    }

    @Test
    fun `a terminal FAILED Result is recorded as the retained lastState`(@TempDir tmp: Path) = runBlocking {
        val w = wire(tmp)
        try {
            writeFrame(
                w,
                DelegationMessage.Result(status = DelegationMessage.ResultStatus.FAILED, summary = "boom", reason = "nope"),
            )
            withTimeout(5_000) { w.onResult.await() }

            var hs = w.svc.handleState(w.handleId)
            val deadline = System.currentTimeMillis() + 5_000
            while (hs !is HandleState.ClosedRetained && System.currentTimeMillis() < deadline) {
                delay(10)
                hs = w.svc.handleState(w.handleId)
            }
            val closed = assertInstanceOf(HandleState.ClosedRetained::class.java, hs)
            assertEquals("FAILED", closed.lastState)
        } finally { cleanup(w) }
    }
}
