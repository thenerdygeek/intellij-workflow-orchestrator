package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.delegation.ui.ConsentChoice
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.delegation.KnockOutcome
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Unit tests for [DelegationDoorbellService] consent application + dedupe (NOT the socket).
 *
 * Also satisfies the spec's named `DelegationConsentFlowTest` (REVIEW FIX N1) — the
 * consent-flow assertions (Allow once → transient bind + preauth; Allow always → persists
 * setting; Cancel → declined marker) are covered here via [applyConsent].
 */
class DelegationDoorbellServiceTest {

    @AfterEach fun tearDown() { io.mockk.unmockkAll() }

    // ── applyConsent ───────────────────────────────────────────────────────────

    @Test
    fun `ALLOW_ALWAYS sets the setting true and records the preauth nonce`(@TempDir tmp: Path) {
        val settings = PluginSettings() // real instance with a real State
        assertFalse(settings.state.enableInboundCrossIdeDelegation)

        val project = mockk<Project>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings

        val service = DelegationDoorbellService(project, CoroutineScope(SupervisorJob()))
        val inbound = mockk<DelegationInboundService>(relaxed = true)
        val store = PendingDelegationStore(tmp)
        val knock = knock(nonce = "n1", delegator = "sess-X")

        service.applyConsent(knock, ConsentChoice.ALLOW_ALWAYS, store, inbound)

        assertTrue(settings.state.enableInboundCrossIdeDelegation)
        verify { inbound.recordPreauth("n1") }
        // no transient bind, no declined marker
        verify(exactly = 0) { inbound.startTransient() }
        assertFalse(store.isDeclined("n1"))
    }

    @Test
    fun `ALLOW_ONCE transient-binds and records preauth but leaves the setting unchanged`(@TempDir tmp: Path) {
        val settings = PluginSettings()
        val project = mockk<Project>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings

        val service = DelegationDoorbellService(project, CoroutineScope(SupervisorJob()))
        val inbound = mockk<DelegationInboundService>(relaxed = true)
        val store = PendingDelegationStore(tmp)
        val knock = knock(nonce = "n2", delegator = "sess-Y")

        service.applyConsent(knock, ConsentChoice.ALLOW_ONCE, store, inbound)

        verify { inbound.startTransient() }
        verify { inbound.recordPreauth("n2") }
        assertFalse(settings.state.enableInboundCrossIdeDelegation)
        assertFalse(store.isDeclined("n2"))
    }

    @Test
    fun `ALLOW_ONCE records the preauth nonce BEFORE binding the socket`(@TempDir tmp: Path) {
        // Bug D: once the socket is bound, IDE-A's poll can detect it and fire its Connect
        // immediately. If recordPreauth hasn't run yet, consumePreauth returns false and IDE-B
        // falls to the redundant Accept dialog. The nonce must be recorded before the bind so the
        // window where a bound-but-not-yet-authorized socket exists is never observable.
        val project = mockk<Project>(relaxed = true)
        val service = DelegationDoorbellService(project, CoroutineScope(SupervisorJob()))
        val inbound = mockk<DelegationInboundService>(relaxed = true)
        val store = PendingDelegationStore(tmp)
        val knock = knock(nonce = "n2b", delegator = "sess-Yb")

        service.applyConsent(knock, ConsentChoice.ALLOW_ONCE, store, inbound)

        io.mockk.verifyOrder {
            inbound.recordPreauth("n2b")
            inbound.startTransient()
        }
    }

    @Test
    fun `CANCEL writes the declined marker and does not bind or preauth`(@TempDir tmp: Path) {
        val project = mockk<Project>(relaxed = true)
        val service = DelegationDoorbellService(project, CoroutineScope(SupervisorJob()))
        val inbound = mockk<DelegationInboundService>(relaxed = true)
        val store = PendingDelegationStore(tmp)
        val knock = knock(nonce = "n3", delegator = "sess-Z")

        service.applyConsent(knock, ConsentChoice.CANCEL, store, inbound)

        assertTrue(store.isDeclined("n3"))
        verify(exactly = 0) { inbound.startTransient() }
        verify(exactly = 0) { inbound.recordPreauth(any()) }
    }

    // ── Fix D: receiver consumes the pending file so it is not replayed / does not accumulate ──

    @Test
    fun `ALLOW_ALWAYS clears the consumed pending file so it is not replayed later`(@TempDir tmp: Path) {
        // Fix D — cold-launch path: the SENDER's 90s wait may have already timed out before the
        // user consented, so the sender will NOT clear the pending .json. The RECEIVER must drop it
        // on consent, or a later IDE restart would replay the same request and re-pop the dialog.
        val settings = PluginSettings()
        val project = mockk<Project>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        val service = DelegationDoorbellService(project, CoroutineScope(SupervisorJob()))
        val inbound = mockk<DelegationInboundService>(relaxed = true)
        val store = PendingDelegationStore(tmp)
        store.write(PendingDelegationRequest("IntelliJ IDEA", "frontend", "sess-A", "do the thing", "nAA", 1L))
        assertFalse(store.readFresh(Long.MAX_VALUE).isEmpty(), "precondition: pending file on disk")

        service.applyConsent(knock(nonce = "nAA", delegator = "sess-A"), ConsentChoice.ALLOW_ALWAYS, store, inbound)

        assertTrue(store.readFresh(Long.MAX_VALUE).isEmpty(), "ALLOW_ALWAYS must drop the consumed pending file")
    }

    @Test
    fun `ALLOW_ONCE clears the consumed pending file so it is not replayed later`(@TempDir tmp: Path) {
        val project = mockk<Project>(relaxed = true)
        val service = DelegationDoorbellService(project, CoroutineScope(SupervisorJob()))
        val inbound = mockk<DelegationInboundService>(relaxed = true)
        val store = PendingDelegationStore(tmp)
        store.write(PendingDelegationRequest("IntelliJ IDEA", "frontend", "sess-B", "do the thing", "nAO", 1L))

        service.applyConsent(knock(nonce = "nAO", delegator = "sess-B"), ConsentChoice.ALLOW_ONCE, store, inbound)

        assertTrue(store.readFresh(Long.MAX_VALUE).isEmpty(), "ALLOW_ONCE must drop the consumed pending file")
    }

    @Test
    fun `CANCEL preserves the declined marker for the still-polling sender`(@TempDir tmp: Path) {
        // The sender's poll observes isDeclined() to bail promptly; CANCEL must NOT remove that
        // marker (only the sender clears it once observed).
        val project = mockk<Project>(relaxed = true)
        val service = DelegationDoorbellService(project, CoroutineScope(SupervisorJob()))
        val inbound = mockk<DelegationInboundService>(relaxed = true)
        val store = PendingDelegationStore(tmp)
        store.write(PendingDelegationRequest("IntelliJ IDEA", "frontend", "sess-C", "do the thing", "nC", 1L))

        service.applyConsent(knock(nonce = "nC", delegator = "sess-C"), ConsentChoice.CANCEL, store, inbound)

        assertTrue(store.isDeclined("nC"), "declined marker must survive for the polling sender")
    }

    // ── dedupe / rate-limit ──────────────────────────────────────────────────────

    @Test
    fun `second knock with the same nonce while a dialog is pending is DUPLICATE`() {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/proj-doorbell"
        val service = DelegationDoorbellService(project, CoroutineScope(SupervisorJob()))
        // No-op launcher: the dialog never runs, so the dedupe slot stays reserved and the
        // second synchronous knock observes the pending state.
        service.dialogLauncher = { /* no dialog in unit test */ }
        val knock = knock(nonce = "dupe", delegator = "sess-dupe")

        val first = service.handleKnock(knock)
        val second = service.handleKnock(knock)

        assertEquals(KnockOutcome.RINGING, first)
        assertEquals(KnockOutcome.DUPLICATE, second)
    }

    @Test
    fun `second knock from same delegator session within rate-limit window is DUPLICATE`() {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/proj-doorbell"
        val service = DelegationDoorbellService(project, CoroutineScope(SupervisorJob()))
        service.dialogLauncher = { /* no dialog in unit test */ }

        val first = service.handleKnock(knock(nonce = "a", delegator = "sess-rl"))
        // Different nonce, SAME delegator session → still deduped (in-flight) / rate-limited.
        val second = service.handleKnock(knock(nonce = "b", delegator = "sess-rl"))

        assertEquals(KnockOutcome.RINGING, first)
        assertEquals(KnockOutcome.DUPLICATE, second)
    }

    @Test
    fun `a second knock from the same delegator after the first dialog resolved is NOT suppressed`() {
        // Bug B secondary: showDialogAndApply's finally cleared pendingNonces +
        // pendingDelegatorSessions but LEAKED lastDialogAt, so a legitimate SECOND delegation from
        // the same delegator session — arriving after the first dialog was resolved but still
        // within RATE_LIMIT_WINDOW_MS — was wrongly rate-limited as DUPLICATE and raised no dialog
        // (and on IDE-A that turns into a 90s dead-poll → TargetNotReachable). Once a dialog has
        // RESOLVED, a fresh knock must be allowed to ring again.
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/proj-doorbell-resolved"
        val service = DelegationDoorbellService(project, CoroutineScope(SupervisorJob()))
        service.dialogLauncher = { /* no real dialog; we drive resolution explicitly below */ }

        val first = service.handleKnock(knock(nonce = "first", delegator = "sess-repeat"))
        assertEquals(KnockOutcome.RINGING, first)

        // Simulate the dialog resolving: this is exactly what showDialogAndApply's finally does.
        service.clearDedupeSlots(knock(nonce = "first", delegator = "sess-repeat"))

        // A fresh delegation (new nonce) from the SAME delegator session, immediately after the
        // first resolved (well within RATE_LIMIT_WINDOW_MS), must ring again — not DUPLICATE.
        val second = service.handleKnock(knock(nonce = "second", delegator = "sess-repeat"))
        assertEquals(
            KnockOutcome.RINGING,
            second,
            "after the first dialog resolved, a later knock from the same delegator must ring (lastDialogAt cleared)",
        )
    }

    // ── security boundary ────────────────────────────────────────────────────────

    @Test
    fun `non-Knock non-Ping doorbell message starts no session`() {
        // The accept loop's handleConnection dispatch acts on Ping (liveness) and Knock (consent);
        // everything else is dropped. We assert the security contract by source-text: there is no
        // path from an unknown message to startDelegatedSession / DelegationInboundService.
        val src = Path.of(
            "src/main/kotlin/com/workflow/orchestrator/agent/delegation/DelegationDoorbellService.kt",
        ).toFile().readText()
        // The two valid message branches.
        assertTrue(src.contains("is DelegationMessage.Ping"), "Ping→Pong liveness branch must exist")
        assertTrue(src.contains("is DelegationMessage.Knock"), "Knock→KnockAck branch must exist")
        // The else branch logs + closes and never starts a session.
        assertTrue(src.contains("Unexpected message on doorbell socket"))
        assertFalse(src.contains("startDelegatedSession"))
    }

    private fun knock(nonce: String, delegator: String) = DelegationMessage.Knock(
        delegatorIde = "IntelliJ IDEA",
        delegatorRepo = "frontend",
        delegatorSessionId = delegator,
        requestPreview = "do the thing",
        nonce = nonce,
    )
}
