package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DelegationLowFindingsTest {

    @BeforeEach fun setup() { installReadActionInlineShim() }
    @AfterEach fun tearDown() { unmockkAll() }

    /** Plan 1 F7: concurrent start() must bind the socket exactly once. */
    @Test
    fun `start is marked Synchronized in source`() {
        val src = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/delegation/DelegationInboundService.kt"
        ).readText()
        assertTrue(
            src.contains("@Synchronized") && src.contains("fun start()"),
            "DelegationInboundService.start() must carry @Synchronized to prevent double-bind",
        )
    }

    /**
     * .23#5: DelegationException.Expired is now a real first-class error meaning the handle is
     * GONE — distinct from a transient, retryable [DelegationException.Rejected]. A BUSY target
     * (ide_b_busy) is retryable and must map to Rejected, NOT Expired. (Supersedes the old
     * "Plan 4 scaffolding" marker, which is obsolete now that Expired carries a real contract.)
     */
    @Test
    fun `Expired documents it is handle-gone and busy maps to Rejected`() {
        val src = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/delegation/DelegationException.kt"
        ).readText()
        assertTrue(
            src.contains("Expired") && src.contains("Rejected"),
            "DelegationException must define both Expired (handle-gone) and Rejected (retryable)",
        )
        assertTrue(
            Regex("busy[^.]*Rejected", RegexOption.IGNORE_CASE).containsMatchIn(src),
            "DelegationException must document that a busy target maps to Rejected (retryable), not Expired",
        )
    }

    /** Plan 2 F10: distinct error kinds for "handle not in map" vs "write failed". */
    @Test
    fun `DelegationException has HandleNotFound and WriteFailed kinds`() {
        val a: DelegationException = DelegationException.HandleNotFound("h-x")
        val b: DelegationException = DelegationException.WriteFailed("io error")
        assertEquals("handle_not_found: h-x", a.message)
        assertEquals("write_failed: io error", b.message)
    }
}
