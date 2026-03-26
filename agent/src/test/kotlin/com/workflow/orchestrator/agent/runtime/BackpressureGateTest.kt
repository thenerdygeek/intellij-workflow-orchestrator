package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BackpressureGateTest {

    private lateinit var gate: BackpressureGate

    @BeforeEach
    fun setup() {
        gate = BackpressureGate(editThreshold = 3)
    }

    @Test
    fun `no nudge below threshold`() {
        gate.recordEdit("/src/A.kt")
        gate.recordEdit("/src/B.kt")
        val nudge = gate.checkAndGetNudge()
        assertNull(nudge)
    }

    @Test
    fun `nudge at threshold`() {
        gate.recordEdit("/src/A.kt")
        gate.recordEdit("/src/B.kt")
        gate.recordEdit("/src/C.kt")
        val nudge = gate.checkAndGetNudge()
        assertNotNull(nudge)
        assertTrue(nudge!!.content!!.contains("diagnostics"))
        assertTrue(nudge.content!!.contains("A.kt"))
    }

    @Test
    fun `counter resets after nudge acknowledged`() {
        gate.recordEdit("/src/A.kt")
        gate.recordEdit("/src/B.kt")
        gate.recordEdit("/src/C.kt")
        gate.checkAndGetNudge()
        gate.acknowledgeVerification()
        assertNull(gate.checkAndGetNudge())
    }

    @Test
    fun `strong nudge when verification not performed`() {
        gate.recordEdit("/src/A.kt")
        gate.recordEdit("/src/B.kt")
        gate.recordEdit("/src/C.kt")
        gate.checkAndGetNudge()
        gate.recordEdit("/src/D.kt")
        val strong = gate.checkAndGetNudge()
        assertNotNull(strong)
        assertTrue(strong!!.content!!.contains("REQUIRED"))
    }

    @Test
    fun `test failure generates backpressure error`() {
        val error = gate.createBackpressureError(
            toolName = "run_tests",
            errorOutput = "FAILED: testAuth — Expected 200 but got 401"
        )
        assertTrue(error.content!!.contains("<backpressure_error>"))
        assertTrue(error.content!!.contains("testAuth"))
    }

    @Test
    fun `disabled when threshold is zero`() {
        val disabled = BackpressureGate(editThreshold = 0)
        disabled.recordEdit("/src/A.kt")
        disabled.recordEdit("/src/B.kt")
        disabled.recordEdit("/src/C.kt")
        assertNull(disabled.checkAndGetNudge())
    }

    @Test
    fun `verification tools acknowledged`() {
        gate.recordEdit("/src/A.kt")
        gate.recordEdit("/src/B.kt")
        gate.recordEdit("/src/C.kt")
        gate.checkAndGetNudge()
        assertTrue(gate.isVerificationPending())
        gate.acknowledgeVerification()
        assertFalse(gate.isVerificationPending())
    }

    @Test
    fun `reset clears all state`() {
        gate.recordEdit("/src/A.kt")
        gate.recordEdit("/src/B.kt")
        gate.recordEdit("/src/C.kt")
        gate.checkAndGetNudge()
        gate.reset()
        assertFalse(gate.isVerificationPending())
        assertNull(gate.checkAndGetNudge())
    }
}
