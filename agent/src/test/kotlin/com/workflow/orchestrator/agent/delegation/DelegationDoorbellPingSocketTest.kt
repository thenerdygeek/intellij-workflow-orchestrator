package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.delegation.DelegationClient
import com.workflow.orchestrator.core.delegation.DelegationFraming
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.delegation.DelegationPaths
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * REAL-SOCKET regression tests for the doorbell Ping→Pong bug.
 *
 * Root cause: [DelegationDoorbellService]'s connection handler dispatched only on
 * [DelegationMessage.Knock] and silently dropped everything else (including
 * [DelegationMessage.Ping]). [DelegationClient.ping] therefore always returned null
 * when probing the doorbell socket, making [com.workflow.orchestrator.agent.delegation.TargetStatusResolver.dualProbeStatus]
 * set `doorbellReachable=false` → the AVAILABLE branch was dead code → a running IDE
 * with inbound OFF was always reported as CLOSED instead of AVAILABLE.
 *
 * These tests exercise the REAL [DelegationDoorbellService] socket accept loop so they
 * cannot be satisfied by the fake-pingFn injectable in [TargetStatusResolverTest].
 *
 * Test 1 (regression guard): start a real doorbell server; call [DelegationClient.ping]
 *   against its socket. MUST fail before the fix (server drops Ping → null),
 *   MUST pass after (server replies Pong).
 *
 * Test 2 (side-effect-free liveness): a Ping does NOT trigger the consent dialog or
 *   any knock-handling side effect ([DelegationDoorbellService.dialogLauncher] must
 *   never be invoked).
 *
 * Test 3 (Knock handling preserved): after adding the Ping→Pong branch, a Knock still
 *   produces a KnockAck (regression guard for the existing accept-result path).
 *
 * Test 4 (resolver end-to-end): [TargetStatusResolver.dualProbeStatus] with a REAL
 *   doorbell socket bound produces AVAILABLE rather than CLOSED — the original bug.
 */
class DelegationDoorbellPingSocketTest {

    @AfterEach
    fun tearDown() { unmockkAll() }

    /**
     * Build a [DelegationDoorbellService] whose [Project.basePath] is the given temp dir,
     * then start it on a [CoroutineScope] that is returned so the caller can cancel it.
     *
     * The socket file is at [DelegationPaths.doorbellSocketFor] of the temp dir, which
     * lives inside the real ~/.workflow-orchestrator/ipc/ directory. The service deletes
     * the socket file on [DelegationDoorbellService.stop] so tests clean up after themselves.
     */
    private fun startDoorbellService(basePath: Path): Pair<DelegationDoorbellService, CoroutineScope> {
        // Ensure the IPC directory exists (the service calls this, but harmless to call again).
        DelegationPaths.ensureIpcDir()

        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns basePath.toAbsolutePath().normalize().toString()

        val cs = CoroutineScope(Job() + Dispatchers.IO)
        val svc = DelegationDoorbellService(project, cs)
        // Inhibit the real EDT dialog: replace with a no-op so the accept loop can run in test.
        svc.dialogLauncher = { /* no real dialog in unit test */ }
        svc.start()
        return svc to cs
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1 — THE regression guard
    // MUST FAIL before the fix (doorbell drops Ping → null) and PASS after.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `REAL-SOCKET - DelegationClient ping against real doorbell returns non-null Pong`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val projectRoot = Files.createDirectory(tmp.resolve("proj-ping"))
        val (svc, cs) = startDoorbellService(projectRoot)
        val doorbellSocket = DelegationPaths.doorbellSocketFor(
            projectRoot.toAbsolutePath().normalize(),
        )
        try {
            val pong = withTimeout(3_000) {
                DelegationClient.ping(doorbellSocket, timeoutMillis = 500)
            }
            assertNotNull(pong, "DelegationClient.ping against the real doorbell must return a Pong; " +
                    "got null — doorbell is still silently dropping Ping messages (fix not applied)")
        } finally {
            svc.stop()
            cs.cancel()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2 — side-effect-free liveness: Ping must NOT trigger the consent dialog
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `REAL-SOCKET - Ping does NOT invoke dialogLauncher — liveness is side-effect-free`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val projectRoot = Files.createDirectory(tmp.resolve("proj-no-dialog"))
        var dialogInvoked = false
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns projectRoot.toAbsolutePath().normalize().toString()
        DelegationPaths.ensureIpcDir()
        val cs = CoroutineScope(Job() + Dispatchers.IO)
        val svc = DelegationDoorbellService(project, cs)
        // Sentinel: if this is called, the Ping triggered knock-handling (the bug).
        svc.dialogLauncher = { dialogInvoked = true }
        svc.start()
        val doorbellSocket = DelegationPaths.doorbellSocketFor(
            projectRoot.toAbsolutePath().normalize(),
        )
        try {
            // Ping the real doorbell socket (post-fix: receives Pong; pre-fix: null — both
            // should leave dialogInvoked=false since Ping was dropped before the fix too).
            DelegationClient.ping(doorbellSocket, timeoutMillis = 500)
            // Give the service coroutine a moment to process any lingering async work.
            delay(100)
            assert(!dialogInvoked) {
                "dialogLauncher was invoked on a Ping — Ping must be side-effect-free (no consent dialog)"
            }
        } finally {
            svc.stop()
            cs.cancel()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3 — Knock handling still works after adding the Ping branch
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `REAL-SOCKET - Knock still gets KnockAck after Ping branch is added`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val projectRoot = Files.createDirectory(tmp.resolve("proj-knock"))
        val (svc, cs) = startDoorbellService(projectRoot)
        val doorbellSocket = DelegationPaths.doorbellSocketFor(
            projectRoot.toAbsolutePath().normalize(),
        )
        try {
            val ack = withTimeout(3_000) {
                DelegationClient.knock(
                    doorbellSocket,
                    DelegationMessage.Knock(
                        delegatorIde = "IntelliJ IDEA",
                        delegatorRepo = "backend",
                        delegatorSessionId = "sess-knock-test",
                        requestPreview = "do it",
                        nonce = "knock-test-nonce",
                    ),
                    timeoutMillis = 500,
                )
            }
            assertNotNull(ack, "Knock must still get a KnockAck after the Ping→Pong branch is added")
        } finally {
            svc.stop()
            cs.cancel()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4 — resolver end-to-end: real doorbell → AVAILABLE (not CLOSED)
    // This is the user-visible symptom: list_targets and picker showed CLOSED.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `REAL-SOCKET - dualProbeStatus returns AVAILABLE when only doorbell is bound`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val projectRoot = Files.createDirectory(tmp.resolve("proj-resolver"))
        val (svc, cs) = startDoorbellService(projectRoot)
        try {
            // No delegation socket bound (inbound OFF is the default, only doorbell is up).
            val status = withTimeout(5_000) {
                TargetStatusResolver.dualProbeStatus(projectRoot)
                // dualProbeStatus uses the real DelegationClient.ping with a 200ms timeout;
                // delegation socket is not bound → null → probe doorbell → Pong (post-fix) → AVAILABLE.
            }
            assertEquals(
                TargetStatusResolver.TargetStatus.AVAILABLE,
                status,
                "A running IDE with inbound delegation OFF must be AVAILABLE, not CLOSED. " +
                        "Got $status — the doorbell Ping→Pong fix is not yet applied.",
            )
        } finally {
            svc.stop()
            cs.cancel()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5 — protocol check: Ping reply carries the project path
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `REAL-SOCKET - Pong reply carries the project path from the doorbell service`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val projectRoot = Files.createDirectory(tmp.resolve("proj-pong-path"))
        val (svc, cs) = startDoorbellService(projectRoot)
        val doorbellSocket = DelegationPaths.doorbellSocketFor(
            projectRoot.toAbsolutePath().normalize(),
        )
        try {
            val pong = withTimeout(3_000) {
                DelegationClient.ping(doorbellSocket, timeoutMillis = 500)
            }
            assertNotNull(pong, "Pong must not be null")
            // The Pong.projectPath should be the basePath string the service was configured with.
            val expectedPath = projectRoot.toAbsolutePath().normalize().toString()
            assertEquals(
                expectedPath,
                pong!!.projectPath,
                "Pong.projectPath should reflect the service's project basePath",
            )
        } finally {
            svc.stop()
            cs.cancel()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 6 — unknown message (not Ping, not Knock) is still dropped silently
    // Security boundary: non-Knock, non-Ping messages must not start a session.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `REAL-SOCKET - unknown message on doorbell is dropped and connection is closed`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val projectRoot = Files.createDirectory(tmp.resolve("proj-unknown"))
        val (svc, cs) = startDoorbellService(projectRoot)
        val doorbellSocket = DelegationPaths.doorbellSocketFor(
            projectRoot.toAbsolutePath().normalize(),
        )
        val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
        try {
            // Send a Heartbeat (not Knock or Ping) — should be dropped, connection closed, no reply.
            val result = withTimeout(3_000) {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val ch = java.nio.channels.SocketChannel.open(
                        java.net.StandardProtocolFamily.UNIX,
                    )
                    ch.connect(java.net.UnixDomainSocketAddress.of(doorbellSocket))
                    DelegationFraming.writeFramed(
                        ch,
                        DelegationMessage.Heartbeat(sessionId = "sess-heartbeat-probe"),
                        json,
                    )
                    // Read should throw (EOF / connection closed) — no reply expected.
                    runCatching { DelegationFraming.readFramed(ch, json) }.also { ch.close() }
                }
            }
            // The server closed the connection → readFramed throws IOException("unexpected EOF").
            assert(result.isFailure) {
                "Unexpected reply to a non-Knock, non-Ping message on the doorbell socket: ${result.getOrNull()}"
            }
        } finally {
            svc.stop()
            cs.cancel()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 7 — no reply when no server bound (control: ping returns null)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `REAL-SOCKET - ping returns null when no doorbell server is bound`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val projectRoot = Files.createDirectory(tmp.resolve("proj-no-server"))
        val doorbellSocket = DelegationPaths.doorbellSocketFor(
            projectRoot.toAbsolutePath().normalize(),
        )
        // No service started — socket file does not exist.
        val pong = DelegationClient.ping(doorbellSocket, timeoutMillis = 200)
        assertNull(pong, "ping must return null when no server is bound")
    }
}
