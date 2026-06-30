package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-text contract tests for D6 (audit findings agent-runtime:F-8, F-9).
 *
 * F-8: requestModelChange used `pendingModelChange.set(modelId)` which is semantically
 * equivalent but not as explicit as `updateAndGet { modelId }`. Concurrent calls could
 * leave `currentBrainModelId` and `pendingModelChange` inconsistent if the VM reorders
 * the two adjacent writes. Fix: use `updateAndGet` to make the atomic write intent
 * explicit; ensures exactly one model wins the slot per atomic operation.
 *
 * F-9: dialectDriftFlag was consumed inline as a function call argument to
 * `SystemPrompt.build(dialectDriftDetected = messageStateRef?.consumeDialectDriftFlag() ?: false)`.
 * While `consumeDialectDriftFlag()` is already atomic (AtomicBoolean.getAndSet), reading it
 * as an inline argument prevents future maintainers from accidentally reading it a second time
 * if the call site is refactored. Fix: snapshot into a named local `dialectDriftSnapshot`
 * before the `SystemPrompt.build(...)` call.
 *
 * Why source-text: AgentService cannot be instantiated in a plain JUnit 5 test. Source-text
 * pins are the established pattern for structural invariants (see AgentServiceActiveTaskMutexTest).
 */
class AgentServiceModelChangeCasTest {

    private val src: String by lazy {
        java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt"
        ).readText()
    }

    // -------------------------------------------------------------------------
    // F-8: updateAndGet for requestModelChange
    // -------------------------------------------------------------------------

    @Test
    fun `requestModelChange uses updateAndGet for atomic model slot update`() {
        assertTrue(src.contains("pendingModelChange.updateAndGet { modelId }"),
            "requestModelChange must use updateAndGet { modelId } for atomic pending slot update")
    }

    @Test
    fun `requestModelChange does not use bare set for pendingModelChange`() {
        // The old plain `.set(modelId)` call inside requestModelChange must be gone.
        // We verify by checking the requestModelChange function body.
        val funcIdx = src.indexOf("fun requestModelChange(")
        assertTrue(funcIdx >= 0, "requestModelChange function must exist")
        val funcBodyEnd = src.indexOf("\n    }", funcIdx)
        val funcBody = src.substring(funcIdx, funcBodyEnd + 10)
        // In the function body, `pendingModelChange.set(` should no longer appear
        assertTrue(!funcBody.contains("pendingModelChange.set(modelId)"),
            "requestModelChange must not use pendingModelChange.set(modelId); use updateAndGet instead")
    }

    // -------------------------------------------------------------------------
    // F-9: dialectDriftFlag snapshotted into a named local
    // -------------------------------------------------------------------------

    @Test
    fun `dialectDriftFlag is snapshotted into a named local before SystemPrompt build`() {
        assertTrue(src.contains("val dialectDriftSnapshot = messageStateRef?.consumeDialectDriftFlag() ?: false"),
            "dialectDriftFlag must be snapshotted into dialectDriftSnapshot before SystemPrompt.build()")
    }

    @Test
    fun `dialectDriftDetected uses the snapshot local not a second consumeDialectDriftFlag call`() {
        val snapshotIdx = src.indexOf("val dialectDriftSnapshot = messageStateRef?.consumeDialectDriftFlag()")
        assertTrue(snapshotIdx >= 0, "dialectDriftSnapshot local must exist")
        val buildIdx = src.indexOf("SystemPrompt.build(", snapshotIdx)
        assertTrue(buildIdx > snapshotIdx && buildIdx < snapshotIdx + 300,
            "SystemPrompt.build() must appear within 300 chars after dialectDriftSnapshot")
        // The parameter must use the snapshot local, not the flag directly.
        // Window widened from 2000→3500: Task 11 added parameters between SystemPrompt.build(
        // and dialectDriftDetected (availableModels, integrationFlags, etc.), pushing the
        // distance to ~2812 chars. The snapshot-before-build LOGIC is unchanged and correct.
        val driftParamIdx = src.indexOf("dialectDriftDetected = dialectDriftSnapshot", buildIdx)
        assertTrue(driftParamIdx > buildIdx && driftParamIdx < buildIdx + 3500,
            "dialectDriftDetected parameter must be set to dialectDriftSnapshot (the local), not consumeDialectDriftFlag()")
        // Also verify: consumeDialectDriftFlag() appears BEFORE the snapshot, not AFTER it inside build()
        // (i.e., the pattern is: val snapshot = consume(); build(x = snapshot) — not build(x = consume()))
        val consumeAfterSnapshot = src.indexOf("dialectDriftDetected = messageStateRef?.consumeDialectDriftFlag()", driftParamIdx)
        assertTrue(consumeAfterSnapshot == -1 || consumeAfterSnapshot > driftParamIdx + 500,
            "consumeDialectDriftFlag() must NOT appear as dialectDriftDetected argument inside SystemPrompt.build()")
    }
}
