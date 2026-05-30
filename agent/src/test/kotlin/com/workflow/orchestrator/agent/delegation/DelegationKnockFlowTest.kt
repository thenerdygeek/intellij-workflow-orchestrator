package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.delegation.ui.PickerEntry
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.util.ProjectIdentifier
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.StandardProtocolFamily
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plan 6 Task 7 — outbound knock-and-wait flow.
 *
 * Drives [DelegationOutboundService.send] through the four §8.1 scenarios by
 * overriding the test-injectable seams (ping / knock / connect / launch /
 * pickTarget). Uses a real [PendingDelegationStore] under a [TempDir] so the
 * declined-marker and pending-file assertions exercise real file I/O.
 */
class DelegationKnockFlowTest {

    private val settings = mockk<com.workflow.orchestrator.core.settings.PluginSettings>(relaxed = true)

    private val project = mockk<Project>(relaxed = true).also {
        every { it.name } returns "delegator-repo"
        // send() starts an IdleTimer after a successful connect, which reads
        // PluginSettings off the project service container. A relaxed Project mock
        // returns a raw Object for getService(); stub the settings lookup so the
        // post-connect bookkeeping doesn't ClassCastException. basePath null →
        // persistHandlesForSession early-returns harmlessly.
        every { it.getService(com.workflow.orchestrator.core.settings.PluginSettings::class.java) } returns settings
        every { it.basePath } returns null
    }

    /** Unconnected real channel — closeable; any framed read throws (→ FAILED reader result). */
    private fun fakeChannel(): SocketChannel = SocketChannel.open(StandardProtocolFamily.UNIX)

    /**
     * Runs a suspend block on real (wall-clock) dispatch so an exception thrown
     * mid-suspend propagates synchronously through [assertThrows]. Avoids nesting
     * a virtual-time [runTest] scheduler inside another. Test-only — the
     * runBlocking ban applies to main/ sources, not test fixtures.
     */
    private fun runBlockingTestSend(block: suspend () -> Unit) = runBlocking { block() }

    private fun newService(targetRoot: Path): Pair<DelegationOutboundService, PickerEntry> {
        val svc = DelegationOutboundService(project, CoroutineScope(Job()))
        val entry = PickerEntry(
            path = targetRoot,
            displayName = "target-repo",
            status = PickerEntry.Status.CLOSED,
        )
        svc.pickTargetOverride = { entry }
        return svc to entry
    }

    /** Agent-dir computed by the service from the target path string (B4 helper). */
    private fun pendingDir(targetRoot: Path): Path =
        ProjectIdentifier.agentDir(targetRoot.toString()).toPath().resolve("pending-delegation")

    @Test
    fun `door reachable on first ping uses existing path with no knock and no pending file`(@TempDir tmp: Path) = runTest {
        val targetRoot = Files.createDirectory(tmp.resolve("targetA"))
        val (svc, _) = newService(targetRoot)

        svc.pingFn = { DelegationMessage.Pong(it.toString()) } // door is up
        var knockCount = 0
        svc.knockFn = { _, _ -> knockCount++; null }
        var launchCount = 0
        svc.launchFn = { launchCount++; SpawnResult.Started() }
        val capturedConnect = CompletableDeferred<DelegationMessage.Connect>()
        svc.connectFn = { _, connect ->
            capturedConnect.complete(connect)
            fakeChannel() to DelegationMessage.AcceptResult(accepted = true, bSessionId = "bSess")
        }

        val handle = svc.send("do the thing", null, "aSess") { _, _ -> }

        assertEquals("target-repo", handle.targetRepoName)
        assertEquals(0, knockCount, "reachable door must NOT knock")
        assertEquals(0, launchCount, "reachable door must NOT launch")
        assertNull(capturedConnect.await().preauthNonce, "existing path: preauthNonce stays null")
        assertFalse(Files.exists(pendingDir(targetRoot)), "no pending file should be written")
    }

    @Test
    fun `door unreachable and knock RINGING does not launch and connects with nonce`(@TempDir tmp: Path) = runTest {
        val targetRoot = Files.createDirectory(tmp.resolve("targetB"))
        val (svc, _) = newService(targetRoot)
        val store = PendingDelegationStore(ProjectIdentifier.agentDir(targetRoot.toString()).toPath())

        // Door down for the first probe; binds after the doorbell rings.
        val pingCalls = AtomicInteger(0)
        svc.pingFn = { if (pingCalls.getAndIncrement() == 0) null else DelegationMessage.Pong(it.toString()) }
        var knockCount = 0
        svc.knockFn = { _, knock ->
            knockCount++
            // Pending file must already be on disk by the time we knock.
            assertTrue(store.readFresh(300_000).any { it.nonce == knock.nonce }, "pending file written before knock")
            DelegationMessage.KnockAck(knock.nonce, com.workflow.orchestrator.core.delegation.KnockOutcome.RINGING)
        }
        var launchCount = 0
        svc.launchFn = { launchCount++; SpawnResult.Started() }
        val capturedConnect = CompletableDeferred<DelegationMessage.Connect>()
        svc.connectFn = { _, connect ->
            capturedConnect.complete(connect)
            fakeChannel() to DelegationMessage.AcceptResult(accepted = true, bSessionId = "bSess")
        }

        svc.send("do the thing", null, "aSess") { _, _ -> }

        assertEquals(1, knockCount)
        assertEquals(0, launchCount, "RINGING means IDE-B is running — no launcher spawn")
        val connect = capturedConnect.await()
        assertTrue(!connect.preauthNonce.isNullOrBlank(), "knock-flow Connect must carry the preauth nonce")
        // Pending file cleared after the flow resolves.
        assertTrue(store.readFresh(300_000).isEmpty(), "pending file cleared on success")
    }

    @Test
    fun `door unreachable and knock null spawns launcher then connects with nonce`(@TempDir tmp: Path) = runTest {
        val targetRoot = Files.createDirectory(tmp.resolve("targetC"))
        val (svc, _) = newService(targetRoot)

        val pingCalls = AtomicInteger(0)
        svc.pingFn = { if (pingCalls.getAndIncrement() == 0) null else DelegationMessage.Pong(it.toString()) }
        svc.knockFn = { _, _ -> null } // doorbell didn't answer → not running
        var launchCount = 0
        svc.launchFn = { launchCount++; SpawnResult.Started() }
        val capturedConnect = CompletableDeferred<DelegationMessage.Connect>()
        svc.connectFn = { _, connect ->
            capturedConnect.complete(connect)
            fakeChannel() to DelegationMessage.AcceptResult(accepted = true, bSessionId = "bSess")
        }

        svc.send("do the thing", null, "aSess") { _, _ -> }

        assertEquals(1, launchCount, "null KnockAck must spawn the launcher")
        assertTrue(!capturedConnect.await().preauthNonce.isNullOrBlank())
    }

    @Test
    fun `cold-launch bind-timeout LEAVES the pending file for the receiver to replay`(@TempDir tmp: Path) = runTest {
        // Fix D — the cross-service lifetime race. IDE-A delegates to a target whose IDE is
        // CLOSED: doorbell unreachable → launcher spawned, but the freshly-launched IDE cannot
        // boot + index + bind its delegation socket within CONSENT_WAIT_TIMEOUT_MILLIS. The
        // sender's 90s in-memory wait elapses → TargetNotReachable. The pending file MUST survive
        // that timeout so the cold IDE's replayPendingRequests() can consume it minutes later;
        // before the fix, knockAndWaitForBind's `finally { store.clear(nonce) }` deleted it on the
        // timeout path and the consent dialog never appeared.
        val targetRoot = Files.createDirectory(tmp.resolve("targetCold"))
        val (svc, _) = newService(targetRoot)
        val store = PendingDelegationStore(ProjectIdentifier.agentDir(targetRoot.toString()).toPath())

        svc.pingFn = { null } // cold IDE: door never binds within the wait
        svc.knockFn = { _, _ -> null } // doorbell not bound → IDE-B isn't running yet
        var launchCount = 0
        svc.launchFn = { launchCount++; SpawnResult.Started() }
        svc.connectFn = { _, _ -> fakeChannel() to DelegationMessage.AcceptResult(accepted = true) }

        // Call send() directly inside runTest so the 90s consent wait elapses in VIRTUAL time
        // (the poll-loop delay() uses runTest's TestCoroutineScheduler). On the timeout path no
        // connect/reader-loop is reached, so no background coroutine is left dangling.
        var thrown: Throwable? = null
        try {
            svc.send("do the thing", null, "aSess") { _, _ -> }
        } catch (e: DelegationException.TargetNotReachable) {
            thrown = e
        }
        assertTrue(thrown is DelegationException.TargetNotReachable, "cold-launch wait must time out → TargetNotReachable")
        // sanity: it was the cold-launch path (launcher spawned) that timed out
        assertEquals(1, launchCount, "null KnockAck must spawn the launcher")
        // The pending file SURVIVES the sender's timeout/finally.
        val survivors = store.readFresh(DelegationDoorbellService.REPLAY_TTL_MS)
        assertEquals(1, survivors.size, "pending file must survive the cold-launch bind timeout")
        assertEquals("do the thing", survivors.single().requestPreview)
        val nonce = survivors.single().nonce
        // No decline happened — the marker must NOT exist.
        assertFalse(store.isDeclined(nonce), "no .declined marker on a bare timeout")

        // After the cold IDE finishes indexing, the receiver-side replay surface still consumes
        // it: handleKnock (the exact call replayPendingRequests() makes per fresh entry) returns
        // RINGING — i.e. the consent dialog would be raised. We use a no-op dialogLauncher so the
        // gate logic runs without an Application/EDT.
        val replayKnock = DelegationMessage.Knock(
            delegatorIde = survivors.single().delegatorIde,
            delegatorRepo = survivors.single().delegatorRepo,
            delegatorSessionId = survivors.single().delegatorSessionId,
            requestPreview = survivors.single().requestPreview,
            nonce = nonce,
        )
        val doorbell = DelegationDoorbellService(project, CoroutineScope(Job()))
        doorbell.dialogLauncher = { /* no-op: avoid EDT/Application in unit test */ }
        val outcome = doorbell.handleKnock(replayKnock)
        assertEquals(
            com.workflow.orchestrator.core.delegation.KnockOutcome.RINGING,
            outcome,
            "post-indexing replay must raise the consent dialog (RINGING)",
        )
    }

    @Test
    fun `declined marker during wait throws Rejected inbound_consent_declined`(@TempDir tmp: Path) = runTest {
        val targetRoot = Files.createDirectory(tmp.resolve("targetD"))
        val (svc, _) = newService(targetRoot)
        val store = PendingDelegationStore(ProjectIdentifier.agentDir(targetRoot.toString()).toPath())

        svc.pingFn = { null } // door never binds
        var knockedNonce: String? = null
        // Simulate the IDE-B user declining: the doorbell rings, then the consent
        // dialog returns Cancel and writes the .declined marker. We model that by
        // marking declined synchronously when the knock lands, so the very next
        // wait-loop tick observes it.
        svc.knockFn = { _, knock ->
            knockedNonce = knock.nonce
            store.markDeclined(knock.nonce)
            DelegationMessage.KnockAck(knock.nonce, com.workflow.orchestrator.core.delegation.KnockOutcome.RINGING)
        }
        svc.launchFn = { SpawnResult.Started() }
        svc.connectFn = { _, _ -> fakeChannel() to DelegationMessage.AcceptResult(accepted = true) }

        val ex = assertThrows(DelegationException.Rejected::class.java) {
            runBlockingTestSend { svc.send("do the thing", null, "aSess") { _, _ -> } }
        }
        assertEquals("inbound_consent_declined", ex.rejectReason)
        // declined marker cleared on exit (clear() removes both .json and .declined)
        assertFalse(store.isDeclined(knockedNonce!!), "declined marker cleared on exit")
    }
}
