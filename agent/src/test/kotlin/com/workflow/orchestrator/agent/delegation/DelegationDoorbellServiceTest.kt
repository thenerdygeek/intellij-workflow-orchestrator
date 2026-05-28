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
import org.junit.jupiter.api.BeforeEach
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

    // ── security boundary ────────────────────────────────────────────────────────

    @Test
    fun `a non-Knock doorbell message starts no session`() {
        // The accept loop's handleConnection dispatch only acts on Knock; everything else is
        // dropped. We assert the security contract by source-text: there is no path from a
        // non-Knock message to startDelegatedSession / DelegationInboundService.
        val src = Path.of(
            "src/main/kotlin/com/workflow/orchestrator/agent/delegation/DelegationDoorbellService.kt",
        ).toFile().readText()
        // The only message branch that does work is `is DelegationMessage.Knock`.
        assertTrue(src.contains("is DelegationMessage.Knock"))
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
