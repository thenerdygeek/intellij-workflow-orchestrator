package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import com.workflow.orchestrator.agent.ui.DelegatedStartOutcome
import com.workflow.orchestrator.core.delegation.DelegationClient
import com.workflow.orchestrator.core.delegation.DelegationFraming
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.delegation.DelegationPaths
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.util.ProjectIdentifier
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * TDD bugfix (2026-06-01, cross-ide): a transient ("Allow once") inbound work socket must remain
 * BOUND for a retention window after its delegated session completes, so IDE-A can continue the SAME
 * session within the ~30-min continuation window. Previously the socket was torn down the instant the
 * delegated session finished (stopIfTransientAndIdle → stop()), so IDE-A's resurrectAndContinue re-dial
 * hit a dead socket and failed with `ide_b_not_running` even though IDE-B was alive.
 *
 * Security invariant under test (test 3): keeping the socket bound during the window must NOT let a
 * brand-new delegation slip in without consent. A ChannelResume of the just-completed retained session
 * is accepted; a fresh Connect (no preauth nonce) must still hit the Accept-dialog/consent path.
 */
class DelegationTransientRetentionTest {

    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    @BeforeEach fun setup() { installReadActionInlineShim() }
    @AfterEach fun tearDown() { unmockkAll() }

    private class Fixture(val inbound: DelegationInboundService, val scope: CoroutineScope, val socket: Path, val root: Path)

    private fun newFixture(tmp: Path, retentionMillis: Long): Fixture {
        val root = Files.createDirectory(tmp.resolve("ide-b-${System.nanoTime()}"))
        val settings = PluginSettings()
        val project = mockk<Project>(relaxed = true).also {
            every { it.basePath } returns root.toString()
            every { it.getService(PluginSettings::class.java) } returns settings
            every { it.messageBus } returns mockk(relaxed = true)
        }
        val scope = CoroutineScope(Job())
        val inbound = DelegationInboundService(project, scope)
        inbound.transientRetentionMillis = retentionMillis
        return Fixture(inbound, scope, DelegationPaths.socketFor(root), root)
    }

    private fun cleanup(fx: Fixture) {
        fx.inbound.stop()
        fx.scope.cancel()
        runCatching { Files.deleteIfExists(fx.socket) }
        runCatching {
            Files.walk(ProjectIdentifier.agentDir(fx.root.toString()).toPath())
                .sorted(Comparator.reverseOrder())
                .forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /** Simulate a delegated session beginning + ending on a transient bind. */
    private fun beginAndCompleteSession(inbound: DelegationInboundService, sid: String) {
        inbound.registerSessionChannel(sid, mockk(relaxed = true))
        inbound.unregisterSessionChannel(sid)
        // Mirror AgentService's terminal callback: count is now 0.
        inbound.stopIfTransientAndIdle(0)
    }

    // ── Test 1: socket stays bound within the retention window ───────────────────

    @Test
    fun `transient socket stays bound within the retention window after session completes`(@TempDir tmp: Path) = runBlocking {
        val fx = newFixture(tmp, retentionMillis = 5_000)
        try {
            fx.inbound.recordPreauth("ignored")
            fx.inbound.startTransient()
            beginAndCompleteSession(fx.inbound, "sess-1")

            // Within the window the socket must still answer a ping.
            val pong = withTimeout(2_000) { DelegationClient.ping(fx.socket, timeoutMillis = 1_000) }
            assertNotNull(pong, "transient socket must remain bound within the retention window")
        } finally {
            cleanup(fx)
        }
        Unit
    }

    // ── Test 2: socket unbinds after the window elapses with no activity ─────────

    @Test
    fun `transient socket unbinds after the retention window elapses with no activity`(@TempDir tmp: Path) = runBlocking {
        val fx = newFixture(tmp, retentionMillis = 150)
        try {
            fx.inbound.startTransient()
            beginAndCompleteSession(fx.inbound, "sess-1")

            // Wait past the window.
            delay(600)
            val pong = withTimeout(2_000) { DelegationClient.ping(fx.socket, timeoutMillis = 500) }
            assertNull(pong, "transient socket must be unbound once the retention window expires")
        } finally {
            cleanup(fx)
        }
        Unit
    }

    // ── Test 3 (SECURITY): resume accepted, fresh delegation rejected/consent-gated ─

    @Test
    fun `during the window a ChannelResume of the retained session is accepted`(@TempDir tmp: Path) = runBlocking {
        val fx = newFixture(tmp, retentionMillis = 10_000)
        try {
            val sid = "retained-sess-1"
            // Seed a persisted delegated HistoryItem so handleChannelResume resurrects it.
            seedDelegatedHistoryItem(fx.root, sid)

            // Resume seam: accept the resume, deliver the same sid, then complete.
            fx.inbound.testDelegatedResumeStarter =
                DelegatedResumeStarter { resumeSid, _turn, _md, _reply, onResult, onStarted ->
                    onStarted?.invoke(resumeSid)
                    fx.scope.launch {
                        delay(10)
                        onResult(DelegationMessage.Result(status = DelegationMessage.ResultStatus.COMPLETED, summary = "continued"))
                    }
                    DelegatedStartOutcome.STARTED
                }

            fx.inbound.startTransient()
            beginAndCompleteSession(fx.inbound, sid)

            // IDE-A reattaches: ChannelResume + follow-up UserTurn → expect ChannelResumed verdict.
            val ch = withTimeout(10_000) { DelegationClient.openChannel(fx.socket) }
            DelegationFraming.writeFramed(ch, DelegationMessage.ChannelResume(sessionId = sid, lastSeenState = "CLOSED"), json)
            DelegationFraming.writeFramed(ch, DelegationMessage.UserTurn(sessionId = sid, text = "please continue"), json)
            val verdict = withTimeout(10_000) { DelegationFraming.readFramed(ch, json) }
            assertTrue(
                verdict is DelegationMessage.ChannelResumed,
                "ChannelResume of the retained session must be accepted during the window; got $verdict",
            )
            ch.close()
        } finally {
            cleanup(fx)
        }
        Unit
    }

    @Test
    fun `during the window a fresh delegation without preauth is consent-gated not auto-accepted`(@TempDir tmp: Path) = runBlocking {
        val fx = newFixture(tmp, retentionMillis = 10_000)
        try {
            // Track whether the session starter was ever invoked. A correctly consent-gated fresh
            // Connect (no preauth nonce, no live Application for the Accept dialog) must NEVER reach
            // the starter — it should be rejected before any session begins.
            var starterInvoked = false
            fx.inbound.testDelegatedSessionStarter =
                DelegatedSessionStarter { _req, _md, _reply, _onResult, _onStarted ->
                    starterInvoked = true
                    DelegatedStartOutcome.STARTED
                }

            fx.inbound.startTransient()
            beginAndCompleteSession(fx.inbound, "completed-sess")

            // Fresh Connect with NO preauth nonce. In a headless test there is no EDT/Application to
            // show the Accept dialog, so consumePreauth=false → the consent path is taken and the
            // delegation is NOT auto-accepted. The starter must not run.
            val ch = withTimeout(10_000) { DelegationClient.openChannel(fx.socket) }
            DelegationFraming.writeFramed(
                ch,
                DelegationMessage.Connect(
                    delegatorIde = "ide-A",
                    delegatorRepo = "repo",
                    delegatorSessionId = "a-fresh",
                    request = "do something new",
                    preauthNonce = null,
                ),
                json,
            )
            // Read the reply (rejection / failure). Either way the starter must not have run.
            runCatching { withTimeout(5_000) { DelegationFraming.readFramed(ch, json) } }
            ch.close()
            assertTrue(!starterInvoked, "a fresh un-preauth'd delegation must be consent-gated, not auto-accepted during retention")
        } finally {
            cleanup(fx)
        }
        Unit
    }

    // ── Test 4: project close during the window unbinds immediately ──────────────

    @Test
    fun `project close during the window unbinds the transient socket immediately`(@TempDir tmp: Path) = runBlocking {
        val fx = newFixture(tmp, retentionMillis = 30_000)
        try {
            fx.inbound.startTransient()
            beginAndCompleteSession(fx.inbound, "sess-1")

            fx.inbound.closeAllForProjectClose()

            val pong = withTimeout(2_000) { DelegationClient.ping(fx.socket, timeoutMillis = 500) }
            assertNull(pong, "project close must unbind the transient socket immediately, even mid-window")
        } finally {
            cleanup(fx)
        }
        Unit
    }

    // ── Test 5: a new session before expiry cancels the pending unbind ───────────

    @Test
    fun `a new delegated session before expiry cancels the pending unbind`(@TempDir tmp: Path) = runBlocking {
        val fx = newFixture(tmp, retentionMillis = 200)
        try {
            fx.inbound.startTransient()
            beginAndCompleteSession(fx.inbound, "sess-1")

            // Before the 200ms window expires, a new delegated session starts.
            delay(50)
            fx.inbound.registerSessionChannel("sess-2", mockk(relaxed = true))

            // Wait well past the original window; the pending unbind should have been cancelled.
            delay(400)
            val pong = withTimeout(2_000) { DelegationClient.ping(fx.socket, timeoutMillis = 1_000) }
            assertNotNull(pong, "starting a new session before expiry must cancel the pending unbind")
        } finally {
            cleanup(fx)
        }
        Unit
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun seedDelegatedHistoryItem(root: Path, sid: String) {
        val agentDir = ProjectIdentifier.agentDir(root.toString())
        val sessionsDir = java.io.File(agentDir, "sessions/$sid")
        sessionsDir.mkdirs()
        // Minimal api history so handleChannelResume's resume path can proceed if it reads it.
        java.io.File(sessionsDir, "api_conversation_history.json").writeText("[]")
        // Index entry carrying delegation metadata.
        val index = java.io.File(agentDir, "sessions.json")
        index.writeText(
            """
            [
              {
                "id": "$sid",
                "ts": 1,
                "task": "delegated task",
                "tokensIn": 0,
                "tokensOut": 0,
                "totalCost": 0.0,
                "delegated": { "delegatorIde": "ide-A", "delegatorRepo": "repo", "delegatorSessionId": "a-1", "startedAt": 1 }
              }
            ]
            """.trimIndent(),
        )
    }
}
