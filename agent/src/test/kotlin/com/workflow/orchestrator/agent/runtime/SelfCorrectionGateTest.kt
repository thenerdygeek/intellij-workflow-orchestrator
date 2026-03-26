package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SelfCorrectionGateTest {

    private lateinit var gate: SelfCorrectionGate

    @BeforeEach
    fun setup() {
        gate = SelfCorrectionGate(maxRetriesPerFile = 3)
    }

    // --- Edit tracking ---

    @Test
    fun `recordEdit marks file as unverified`() {
        gate.recordEdit("/src/Main.kt")
        assertTrue(gate.getUnverifiedFiles().contains("/src/Main.kt"))
    }

    @Test
    fun `recordEdit resets verification status on re-edit`() {
        gate.recordEdit("/src/Main.kt")
        gate.recordVerification("/src/Main.kt", passed = true)
        assertTrue(gate.getUnverifiedFiles().isEmpty())

        // Re-edit resets verification
        gate.recordEdit("/src/Main.kt")
        assertTrue(gate.getUnverifiedFiles().contains("/src/Main.kt"))
    }

    // --- Verification demand ---

    @Test
    fun `getVerificationDemand returns message after edit`() {
        gate.recordEdit("/src/Main.kt")
        val demand = gate.getVerificationDemand()
        assertNotNull(demand)
        assertTrue(demand!!.content!!.contains("Main.kt"))
        assertTrue(demand.content!!.contains("diagnostics"))
    }

    @Test
    fun `getVerificationDemand returns null when no edits`() {
        assertNull(gate.getVerificationDemand())
    }

    @Test
    fun `getVerificationDemand returns null after already requested`() {
        gate.recordEdit("/src/Main.kt")
        gate.getVerificationDemand() // First call marks as requested
        assertNull(gate.getVerificationDemand()) // Second call returns null
    }

    @Test
    fun `getVerificationDemand returns null after verification passed`() {
        gate.recordEdit("/src/Main.kt")
        gate.recordVerification("/src/Main.kt", passed = true)
        assertNull(gate.getVerificationDemand())
    }

    @Test
    fun `getVerificationDemand includes multiple files`() {
        gate.recordEdit("/src/A.kt")
        gate.recordEdit("/src/B.kt")
        val demand = gate.getVerificationDemand()
        assertNotNull(demand)
        assertTrue(demand!!.content!!.contains("A.kt"))
        assertTrue(demand.content!!.contains("B.kt"))
    }

    // --- Verification recording ---

    @Test
    fun `recordVerification passed marks file as verified`() {
        gate.recordEdit("/src/Main.kt")
        gate.recordVerification("/src/Main.kt", passed = true)
        assertTrue(gate.getUnverifiedFiles().isEmpty())
    }

    @Test
    fun `recordVerification failed increments retry count`() {
        gate.recordEdit("/src/Main.kt")
        gate.recordVerification("/src/Main.kt", passed = false, errorDetails = "NPE at line 42")
        assertFalse(gate.isRetryExhausted("/src/Main.kt"))

        val state = gate.getFileStates()["/src/Main.kt"]!!
        assertEquals(1, state.retryCount)
        assertEquals("NPE at line 42", state.lastError)
    }

    @Test
    fun `recordVerification failed re-enables verification demand`() {
        gate.recordEdit("/src/Main.kt")
        gate.getVerificationDemand() // Marks as requested
        gate.recordVerification("/src/Main.kt", passed = false, errorDetails = "error")

        // After failure, should allow new demand
        val demand = gate.getVerificationDemand()
        assertNotNull(demand)
    }

    @Test
    fun `recordVerification with null filePath affects all unverified files`() {
        gate.recordEdit("/src/A.kt")
        gate.recordEdit("/src/B.kt")
        gate.recordVerification(null, passed = true) // compile_module style

        assertTrue(gate.getUnverifiedFiles().isEmpty())
    }

    // --- Retry exhaustion ---

    @Test
    fun `isRetryExhausted returns true after max retries`() {
        gate.recordEdit("/src/Main.kt")
        gate.recordVerification("/src/Main.kt", passed = false, errorDetails = "error 1")
        gate.recordVerification("/src/Main.kt", passed = false, errorDetails = "error 2")
        gate.recordVerification("/src/Main.kt", passed = false, errorDetails = "error 3")

        assertTrue(gate.isRetryExhausted("/src/Main.kt"))
    }

    @Test
    fun `isRetryExhausted returns false below max retries`() {
        gate.recordEdit("/src/Main.kt")
        gate.recordVerification("/src/Main.kt", passed = false, errorDetails = "error 1")

        assertFalse(gate.isRetryExhausted("/src/Main.kt"))
    }

    @Test
    fun `getVerificationDemand skips exhausted files`() {
        gate.recordEdit("/src/Main.kt")
        repeat(3) { gate.recordVerification("/src/Main.kt", passed = false, errorDetails = "error") }

        // File is exhausted — no more demands
        assertNull(gate.getVerificationDemand())
    }

    @Test
    fun `getExhaustedFiles returns files that exceeded max retries`() {
        gate.recordEdit("/src/A.kt")
        gate.recordEdit("/src/B.kt")
        repeat(3) { gate.recordVerification("/src/A.kt", passed = false, errorDetails = "error") }
        gate.recordVerification("/src/B.kt", passed = true)

        val exhausted = gate.getExhaustedFiles()
        assertTrue(exhausted.contains("/src/A.kt"))
        assertFalse(exhausted.contains("/src/B.kt"))
    }

    // --- Reflection prompts ---

    @Test
    fun `buildReflectionPrompt returns structured message after failure`() {
        gate.recordEdit("/src/Main.kt")
        gate.recordVerification("/src/Main.kt", passed = false, errorDetails = "NPE")

        val reflection = gate.buildReflectionPrompt("/src/Main.kt", "diagnostics", "Unresolved reference 'foo' at line 42")
        assertNotNull(reflection)
        assertTrue(reflection!!.content!!.contains("<self_correction>"))
        assertTrue(reflection.content!!.contains("VERIFICATION FAILED"))
        assertTrue(reflection.content!!.contains("Main.kt"))
        assertTrue(reflection.content!!.contains("Unresolved reference"))
        assertTrue(reflection.content!!.contains("REFLECT"))
    }

    @Test
    fun `buildReflectionPrompt shows last retry warning`() {
        gate.recordEdit("/src/Main.kt")
        gate.recordVerification("/src/Main.kt", passed = false, errorDetails = "e1")
        gate.recordVerification("/src/Main.kt", passed = false, errorDetails = "e2")

        // 2 retries used, 1 remaining — should show warning
        val reflection = gate.buildReflectionPrompt("/src/Main.kt", "diagnostics", "error")
        assertNotNull(reflection)
        assertTrue(reflection!!.content!!.contains("last retry"))
    }

    @Test
    fun `buildReflectionPrompt returns null when retries exhausted`() {
        gate.recordEdit("/src/Main.kt")
        repeat(3) { gate.recordVerification("/src/Main.kt", passed = false, errorDetails = "error") }

        val reflection = gate.buildReflectionPrompt("/src/Main.kt", "diagnostics", "error")
        assertNull(reflection)
    }

    @Test
    fun `buildReflectionPrompt returns null for unknown file`() {
        val reflection = gate.buildReflectionPrompt("/src/Unknown.kt", "diagnostics", "error")
        assertNull(reflection)
    }

    // --- Completion readiness ---

    @Test
    fun `checkCompletionReadiness returns null when all verified`() {
        gate.recordEdit("/src/A.kt")
        gate.recordEdit("/src/B.kt")
        gate.recordVerification("/src/A.kt", passed = true)
        gate.recordVerification("/src/B.kt", passed = true)

        assertNull(gate.checkCompletionReadiness())
    }

    @Test
    fun `checkCompletionReadiness blocks when unverified files exist`() {
        gate.recordEdit("/src/A.kt")
        gate.recordVerification("/src/A.kt", passed = true)
        gate.recordEdit("/src/B.kt") // Not verified

        val msg = gate.checkCompletionReadiness()
        assertNotNull(msg)
        assertTrue(msg!!.content!!.contains("COMPLETION BLOCKED"))
        assertTrue(msg.content!!.contains("B.kt"))
    }

    @Test
    fun `checkCompletionReadiness allows completion when retries exhausted`() {
        gate.recordEdit("/src/Main.kt")
        repeat(3) { gate.recordVerification("/src/Main.kt", passed = false, errorDetails = "error") }

        // Exhausted files don't block completion
        assertNull(gate.checkCompletionReadiness())
    }

    @Test
    fun `checkCompletionReadiness allows when no edits made`() {
        assertNull(gate.checkCompletionReadiness())
    }

    // --- File path extraction ---

    @Test
    fun `extractFilePathFromArgs parses diagnostics path`() {
        val path = gate.extractFilePathFromArgs("diagnostics", """{"path": "/src/Main.kt"}""")
        assertEquals("/src/Main.kt", path)
    }

    @Test
    fun `extractFilePathFromArgs parses file_path variant`() {
        val path = gate.extractFilePathFromArgs("run_inspections", """{"file_path": "/src/Main.kt"}""")
        assertEquals("/src/Main.kt", path)
    }

    @Test
    fun `extractFilePathFromArgs returns null for compile_module`() {
        assertNull(gate.extractFilePathFromArgs("compile_module", """{"module": "app"}"""))
    }

    @Test
    fun `extractFilePathFromArgs returns null for unparseable args`() {
        assertNull(gate.extractFilePathFromArgs("diagnostics", "invalid json"))
    }

    // --- Reset ---

    @Test
    fun `reset clears all state`() {
        gate.recordEdit("/src/A.kt")
        gate.recordEdit("/src/B.kt")
        gate.recordVerification("/src/A.kt", passed = false, errorDetails = "error")

        gate.reset()

        assertTrue(gate.getUnverifiedFiles().isEmpty())
        assertTrue(gate.getExhaustedFiles().isEmpty())
        assertTrue(gate.getFileStates().isEmpty())
        assertNull(gate.getVerificationDemand())
        assertNull(gate.checkCompletionReadiness())
    }

    // --- Edge cases ---

    @Test
    fun `verification for untracked file is ignored`() {
        gate.recordVerification("/src/Unknown.kt", passed = true)
        assertTrue(gate.getFileStates().isEmpty())
    }

    @Test
    fun `isTracked returns correct status`() {
        assertFalse(gate.isTracked("/src/Main.kt"))
        gate.recordEdit("/src/Main.kt")
        assertTrue(gate.isTracked("/src/Main.kt"))
    }

    @Test
    fun `multiple edits to same file accumulate edit count`() {
        gate.recordEdit("/src/Main.kt")
        gate.recordEdit("/src/Main.kt")
        gate.recordEdit("/src/Main.kt")

        val state = gate.getFileStates()["/src/Main.kt"]!!
        assertEquals(3, state.editCount)
    }

    @Test
    fun `error details truncated in reflection prompt`() {
        gate.recordEdit("/src/Main.kt")
        gate.recordVerification("/src/Main.kt", passed = false, errorDetails = "e")

        val longError = "x".repeat(3000)
        val reflection = gate.buildReflectionPrompt("/src/Main.kt", "diagnostics", longError)
        assertNotNull(reflection)
        // Content should contain truncated error (1500 chars max)
        assertTrue(reflection!!.content!!.length < 3000)
    }
}
