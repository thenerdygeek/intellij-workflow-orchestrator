package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import com.workflow.orchestrator.core.delegation.DelegationFraming
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.delegation.DelegationPaths
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * BUG #5 (MEDIUM) — resumed/resurrected delegation channels never installed an [IdleTimer], so a
 * hung-but-alive IDE-B leaked the channel + reader coroutine forever (no socket read timeout, no
 * idle-timeout close, no "timed out due to inactivity" nudge), and the leaked channel also kept
 * counting toward MAX_CHANNELS.
 *
 * The ONLY timer-creation site used to be [DelegationOutboundService.send]. These tests pin that
 * EVERY path which re-registers a live channel — [DelegationOutboundService.resurrectAndContinue]
 * and [DelegationOutboundService.attemptResume] — now also installs + starts an [IdleTimer] keyed
 * by handle id, firing the same close + inactivity nudge as a hung fresh `send()`.
 */
class DelegationResumeIdleTimerTest {

    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    @BeforeEach fun setup() { installReadActionInlineShim() }
    @AfterEach fun tearDown() { unmockkAll() }

    // ---- reflection helpers -------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun idleTimers(svc: DelegationOutboundService): java.util.concurrent.ConcurrentHashMap<String, IdleTimer> =
        (DelegationOutboundService::class.java.getDeclaredField("idleTimers")
            .apply { isAccessible = true }.get(svc)
            as java.util.concurrent.ConcurrentHashMap<String, IdleTimer>)

    @Suppress("UNCHECKED_CAST")
    private fun lastSeenMap(svc: DelegationOutboundService): java.util.concurrent.ConcurrentHashMap<String, Long> =
        (DelegationOutboundService::class.java.getDeclaredField("lastSeenAt")
            .apply { isAccessible = true }.get(svc)
            as java.util.concurrent.ConcurrentHashMap<String, Long>)

    private fun seedMap(svc: DelegationOutboundService, field: String, key: String, value: String) {
        val f = DelegationOutboundService::class.java.getDeclaredField(field).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(svc) as java.util.concurrent.ConcurrentHashMap<String, String>)[key] = value
    }

    // ---- service-under-test wiring ------------------------------------------

    private class Harness(
        val svc: DelegationOutboundService,
        val cs: CoroutineScope,
        val nudges: MutableList<Pair<String, String>>,
        val idleTimeoutMinutes: () -> Int,
    )

    private fun newHarness(idleMinutes: Int): Harness {
        val nudges = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val agentService = mockk<AgentService>(relaxed = true)
        every { agentService.enqueueNudgeForSession(any(), any()) } answers {
            nudges.add(firstArg<String>() to secondArg<String>())
        }
        val settings = mockk<PluginSettings>(relaxed = true)
        every { settings.state.delegationIdleTimeoutMinutes } returns idleMinutes
        val project = mockk<Project>(relaxed = true).also {
            every { it.getService(PluginSettings::class.java) } returns settings
            every { it.getService(AgentService::class.java) } returns agentService
        }
        val cs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val svc = DelegationOutboundService(project, cs)
        // Fast tick so the idle-timeout fires in milliseconds, not the 30 s production cadence.
        svc.testIdleCheckIntervalMillis = 25L
        return Harness(svc, cs, nudges, { idleMinutes })
    }

    /** A live but silent socket pair: returns the client end (handed to the reader loop) + the
     *  server-side peer that we deliberately never write to (the "hung IDE-B"). */
    private suspend fun silentSocketPair(tmp: Path, name: String): Triple<SocketChannel, SocketChannel, ServerSocketChannel> {
        val addr = UnixDomainSocketAddress.of(tmp.resolve(name))
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(addr)
        val (client, peer) = coroutineScope {
            val acc = async(Dispatchers.IO) { server.accept() }
            val c = withContext(Dispatchers.IO) { SocketChannel.open(addr) }
            c to acc.await()
        }
        return Triple(client, peer, server)
    }

    // =========================================================================
    // resurrectAndContinue — installs + fires an IdleTimer on the resurrected channel
    // =========================================================================

    @Test
    fun `resurrectAndContinue installs an IdleTimer that fires on a hung IDE-B`(@TempDir tmp: Path) = runBlocking {
        val h = newHarness(idleMinutes = 1)  // 1 min timeout; we seed lastSeenAt far in the past
        val (client, peer, server) = silentSocketPair(tmp, "resurrect.sock")
        try {
            // The handle is closed-retained: seed a retained snapshot so resurrectAndContinue finds it.
            val handleId = "h-resurrect"
            seedMap(h.svc, "handleToBSessionId", handleId, "b-sess")
            seedMap(h.svc, "handleToTargetPath", handleId, tmp.toString())
            h.svc.close(handleId)  // produces a RetainedHandle (bSessionId + targetPath present)

            // testResurrectProbe returns ChannelResumed + the live (silent) client channel, so the
            // resurrection re-registers the channel and arms the reader loop — exactly the leak path.
            h.svc.testResurrectProbe = { _, _, _ ->
                DelegationMessage.ChannelResumed(sessionId = "b-sess", currentState = "RUNNING") to client
            }

            val handle = h.svc.sendContinuation(
                handleId = handleId,
                request = "follow up",
                delegatorSessionId = "a-sess",
            )

            // (1) An IdleTimer MUST be registered for the resurrected handle (the fix).
            assertNotNull(
                idleTimers(h.svc)[handle.id],
                "resurrectAndContinue must install an IdleTimer for the re-registered channel",
            )
            assertTrue(h.svc.hasOpenChannel(handle.id), "channel should be open right after resurrection")

            // (2) Make the channel look idle (older than the 1-min timeout), then the fast-ticking
            //     timer must close it and enqueue the inactivity nudge — same as a hung fresh send().
            lastSeenMap(h.svc)[handle.id] = System.currentTimeMillis() - 5L * 60_000L

            withTimeout(5_000) {
                while (h.svc.hasOpenChannel(handle.id)) delay(10)
            }
            withTimeout(5_000) {
                while (h.nudges.none { it.second.contains("timed out due to inactivity") }) delay(10)
            }
            // (3) close() must have removed the timer (cleanup keyed by handle id).
            withTimeout(2_000) {
                while (idleTimers(h.svc)[handle.id] != null) delay(10)
            }
        } finally {
            runCatching { peer.close() }
            runCatching { client.close() }
            runCatching { server.close() }
            h.cs.cancel()
            runCatching { Files.deleteIfExists(tmp.resolve("resurrect.sock")) }
        }
        Unit
    }

    // =========================================================================
    // attemptResume (production socket path) — installs + fires an IdleTimer
    // =========================================================================

    /**
     * A minimal IDE-B over a real UDS: answers Ping→Pong, then ChannelResume→ChannelResumed, then
     * stays silent (the hung-but-alive IDE-B). Returns the bound [ServerSocketChannel] AND the
     * resume connection's peer channel so the test can deterministically close BOTH in its `finally`
     * — closing the server unblocks the accept() coroutine (no leaked blocking-accept thread), and
     * closing the peer is belt-and-suspenders. [attemptResume] opens exactly two connections (ping,
     * then resume), so we accept exactly two and stop — no infinite accept loop to leak.
     */
    private fun startMiniIdeB(
        socketPath: Path,
        scope: CoroutineScope,
        resumePeerOut: java.util.concurrent.atomic.AtomicReference<SocketChannel?>,
    ): ServerSocketChannel {
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            .bind(UnixDomainSocketAddress.of(socketPath))
        scope.launch(Dispatchers.IO) {
            try {
                // Connection 1: the ping probe.
                server.accept().use { pingCh ->
                    if (DelegationFraming.readFramed(pingCh, json) is DelegationMessage.Ping) {
                        DelegationFraming.writeFramed(pingCh, DelegationMessage.Pong(projectPath = socketPath.toString()), json)
                    }
                }
                // Connection 2: the ChannelResume handshake — reply Resumed, then go silent.
                val resumeCh = server.accept()
                resumePeerOut.set(resumeCh)
                val msg = DelegationFraming.readFramed(resumeCh, json)
                if (msg is DelegationMessage.ChannelResume) {
                    DelegationFraming.writeFramed(
                        resumeCh,
                        DelegationMessage.ChannelResumed(sessionId = msg.sessionId, currentState = "RUNNING"),
                        json,
                    )
                }
                // Stop accepting — the hung IDE-B holds resumeCh open but writes nothing more.
            } catch (_: Exception) {
                // Server closed during teardown unblocks accept()/read — expected.
            }
        }
        return server
    }

    @Test
    fun `attemptResume installs an IdleTimer that fires on a hung IDE-B`(@TempDir tmp: Path) = runBlocking {
        val h = newHarness(idleMinutes = 1)
        // Real IDE-B socket so attemptResume's PRODUCTION branch runs (it re-registers the channel).
        val projectRoot = Files.createDirectories(tmp.resolve("ide-b"))
        val socketPath = DelegationPaths.socketFor(projectRoot)
        Files.createDirectories(socketPath.parent)
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val resumePeer = java.util.concurrent.atomic.AtomicReference<SocketChannel?>(null)
        val server = startMiniIdeB(socketPath, serverScope, resumePeer)
        try {
            // A dead-but-persisted handle (rehydrated state): live maps present, NO open channel.
            val handleId = "h-attemptResume"
            seedMap(h.svc, "handleToBSessionId", handleId, "b-sess")
            seedMap(h.svc, "handleToSessionId", handleId, "a-sess")
            seedMap(h.svc, "handleToTargetPath", handleId, projectRoot.toString())
            seedMap(h.svc, "handleToLastSeenState", handleId, "RUNNING")
            seedMap(h.svc, "handleToRepoName", handleId, "frontend")

            val outcome = withTimeout(10_000) { h.svc.attemptResume(handleId) }
            assertTrue(outcome is ResumeOutcome.Resumed, "expected Resumed, got $outcome")
            assertTrue(h.svc.hasOpenChannel(handleId), "attemptResume must re-register the live channel")

            // (1) IdleTimer installed for the resumed handle (the fix).
            assertNotNull(
                idleTimers(h.svc)[handleId],
                "attemptResume must install an IdleTimer for the re-registered channel",
            )

            // (2) Idle → fast-ticking timer closes the channel + nudges, same as a hung fresh send().
            lastSeenMap(h.svc)[handleId] = System.currentTimeMillis() - 5L * 60_000L
            withTimeout(5_000) {
                while (h.svc.hasOpenChannel(handleId)) delay(10)
            }
            withTimeout(5_000) {
                while (h.nudges.none { it.second.contains("timed out due to inactivity") }) delay(10)
            }
        } finally {
            // Close everything so no thread stays blocked in accept()/read (ThreadLeakTracker).
            runCatching { resumePeer.get()?.close() }
            runCatching { server.close() }
            h.cs.cancel()
            serverScope.cancel()
            withContext(Dispatchers.IO) { serverScope.coroutineContext[kotlinx.coroutines.Job]?.join() }
            runCatching { Files.deleteIfExists(socketPath) }
        }
        Unit
    }

    // =========================================================================
    // Source pin — every live-channel re-registration site arms an IdleTimer
    // =========================================================================

    @Test
    fun `source pin - resume and resurrect paths install an idle timer`() {
        val src = Files.readString(
            Path.of("src/main/kotlin/com/workflow/orchestrator/agent/delegation/DelegationOutboundService.kt")
        )
        // The shared installer is invoked from each path that re-registers a live channel.
        val installerCalls = Regex("installIdleTimer\\(").findAll(src).count()
        assertTrue(
            installerCalls >= 3,
            "send(), attemptResume(), and resurrectAndContinue() must each install an IdleTimer " +
                "(found $installerCalls installIdleTimer call sites)",
        )
    }
}
