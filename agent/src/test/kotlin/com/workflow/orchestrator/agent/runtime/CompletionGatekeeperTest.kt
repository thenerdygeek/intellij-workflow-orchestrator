package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CompletionGatekeeperTest {

    private lateinit var planManager: PlanManager
    private lateinit var selfCorrectionGate: SelfCorrectionGate
    private lateinit var loopGuard: LoopGuard

    private var iterationsSinceCompression = 10
    private var postCompressionAttempted = false
    private var postCompressionAttemptedCallCount = 0

    private fun makeGatekeeper(pm: PlanManager? = planManager): CompletionGatekeeper =
        CompletionGatekeeper(
            planManager = pm,
            selfCorrectionGate = selfCorrectionGate,
            loopGuard = loopGuard,
            iterationsSinceCompression = { iterationsSinceCompression },
            postCompressionCompletionAttempted = { postCompressionAttempted },
            onPostCompressionAttempted = { postCompressionAttemptedCallCount++ }
        )

    @BeforeEach
    fun setup() {
        planManager = mockk(relaxed = true)
        selfCorrectionGate = mockk(relaxed = true)
        loopGuard = mockk(relaxed = true)

        // By default: no plan, no gates blocking
        every { planManager.currentPlan } returns null
        every { selfCorrectionGate.checkCompletionReadiness() } returns null
        every { loopGuard.beforeCompletion() } returns null

        iterationsSinceCompression = 10
        postCompressionAttempted = false
        postCompressionAttemptedCallCount = 0
    }

    // --- All gates pass ---

    @Test
    fun `all gates pass when no plan, no compression, no blocks`() {
        val gk = makeGatekeeper()
        assertNull(gk.checkCompletion())
    }

    @Test
    fun `returns null with no plan and old compression`() {
        iterationsSinceCompression = 5
        val gk = makeGatekeeper()
        assertNull(gk.checkCompletion())
    }

    // --- Post-compression gate ---

    @Test
    fun `post-compression gate blocks on recent compression when not yet attempted`() {
        iterationsSinceCompression = 1
        postCompressionAttempted = false
        val gk = makeGatekeeper()

        val result = gk.checkCompletion()

        assertNotNull(result)
        assertTrue(result!!.contains("COMPLETION BLOCKED"))
        assertTrue(result.contains("compressed recently"))
        assertEquals(1, postCompressionAttemptedCallCount)
    }

    @Test
    fun `post-compression gate passes when already attempted once`() {
        iterationsSinceCompression = 0
        postCompressionAttempted = true  // already attempted
        val gk = makeGatekeeper()

        // Should not block on post-compression; no other gates block
        assertNull(gk.checkCompletion())
        assertEquals(0, postCompressionAttemptedCallCount) // callback not called again
    }

    @Test
    fun `post-compression gate passes when compression is old (more than 2 iterations)`() {
        iterationsSinceCompression = 3
        postCompressionAttempted = false
        val gk = makeGatekeeper()

        assertNull(gk.checkCompletion())
        assertEquals(0, postCompressionAttemptedCallCount) // gate not triggered
    }

    @Test
    fun `post-compression gate passes exactly at boundary of 2 iterations`() {
        iterationsSinceCompression = 2
        postCompressionAttempted = false
        val gk = makeGatekeeper()

        val result = gk.checkCompletion()

        // iterationsSinceCompression == 2 is NOT > 2, so gate fires
        assertNotNull(result)
        assertTrue(result!!.contains("compressed recently"))
    }

    // --- Plan gate ---

    @Test
    fun `plan gate blocks when plan has incomplete steps`() {
        val plan = AgentPlan(
            goal = "Implement feature",
            steps = listOf(
                PlanStep(id = "1", title = "Write code", status = "pending"),
                PlanStep(id = "2", title = "Write tests", status = "done")
            )
        )
        every { planManager.currentPlan } returns plan
        val gk = makeGatekeeper()

        val result = gk.checkCompletion()

        assertNotNull(result)
        assertTrue(result!!.contains("COMPLETION BLOCKED"))
        assertTrue(result.contains("1 incomplete steps"))
        assertTrue(result.contains("Write code"))
        assertTrue(result.contains("[pending]"))
    }

    @Test
    fun `plan gate passes when all steps are done`() {
        val plan = AgentPlan(
            goal = "Implement feature",
            steps = listOf(
                PlanStep(id = "1", title = "Write code", status = "done"),
                PlanStep(id = "2", title = "Write tests", status = "done")
            )
        )
        every { planManager.currentPlan } returns plan
        val gk = makeGatekeeper()

        assertNull(gk.checkCompletion())
    }

    @Test
    fun `plan gate passes when all steps are skipped`() {
        val plan = AgentPlan(
            goal = "Implement feature",
            steps = listOf(
                PlanStep(id = "1", title = "Step A", status = "skipped"),
                PlanStep(id = "2", title = "Step B", status = "skipped")
            )
        )
        every { planManager.currentPlan } returns plan
        val gk = makeGatekeeper()

        assertNull(gk.checkCompletion())
    }

    @Test
    fun `plan gate passes when mix of done and skipped`() {
        val plan = AgentPlan(
            goal = "Implement feature",
            steps = listOf(
                PlanStep(id = "1", title = "Step A", status = "done"),
                PlanStep(id = "2", title = "Step B", status = "skipped")
            )
        )
        every { planManager.currentPlan } returns plan
        val gk = makeGatekeeper()

        assertNull(gk.checkCompletion())
    }

    @Test
    fun `plan gate passes with no plan manager`() {
        val gk = makeGatekeeper(pm = null)
        assertNull(gk.checkCompletion())
    }

    @Test
    fun `plan gate passes when currentPlan is null`() {
        every { planManager.currentPlan } returns null
        val gk = makeGatekeeper()
        assertNull(gk.checkCompletion())
    }

    @Test
    fun `plan gate escalates to persistent message after 3 blocks without progress`() {
        val plan = AgentPlan(
            goal = "Implement feature",
            steps = listOf(
                PlanStep(id = "1", title = "Write code", status = "pending")
            )
        )
        every { planManager.currentPlan } returns plan
        val gk = makeGatekeeper()

        // Call 1: incomplete.size(1) != lastIncomplete(MAX_INT) -> planGateBlockCount stays 0, standard message
        val r1 = gk.checkCompletion()
        assertNotNull(r1)
        // Standard message references update_plan_step but does NOT have the (Nx) escalation pattern
        assertFalse(r1!!.contains("(1x)") || r1.contains("no progress"))

        // Call 2: same count -> planGateBlockCount = 1, standard message
        val r2 = gk.checkCompletion()
        assertNotNull(r2)
        assertFalse(r2!!.contains("no progress"))

        // Call 3: same count -> planGateBlockCount = 2, standard message
        val r3 = gk.checkCompletion()
        assertNotNull(r3)
        assertFalse(r3!!.contains("no progress"))

        // Call 4: same count -> planGateBlockCount = 3 >= MAX(3): escalation message
        val r4 = gk.checkCompletion()
        assertNotNull(r4)
        assertTrue(r4!!.contains("(3x)"), "Expected escalation message on 4th call, got: $r4")
        assertTrue(r4.contains("no progress"))
        assertTrue(r4.contains("update_plan_step"))
        assertTrue(r4.contains("skipped"))
    }

    @Test
    fun `plan gate resets block count when progress is made`() {
        val pendingPlan = AgentPlan(
            goal = "Implement feature",
            steps = listOf(
                PlanStep(id = "1", title = "Step A", status = "pending"),
                PlanStep(id = "2", title = "Step B", status = "pending")
            )
        )
        val progressPlan = AgentPlan(
            goal = "Implement feature",
            steps = listOf(
                PlanStep(id = "1", title = "Step A", status = "done"),
                PlanStep(id = "2", title = "Step B", status = "pending")
            )
        )
        every { planManager.currentPlan } returnsMany listOf(pendingPlan, pendingPlan, progressPlan)
        val gk = makeGatekeeper()

        gk.checkCompletion() // block 1 — 2 incomplete
        gk.checkCompletion() // block 2 — 2 incomplete (same count)

        // Progress: now 1 incomplete
        val r3 = gk.checkCompletion()
        assertNotNull(r3)
        // Block count reset, so the escalation counter goes back to 0
        assertFalse(r3!!.contains("(3x)"))
    }

    // --- Force-accept after MAX_TOTAL_COMPLETION_ATTEMPTS ---

    @Test
    fun `force-accepts after MAX_TOTAL_COMPLETION_ATTEMPTS exceeded`() {
        val plan = AgentPlan(
            goal = "Implement feature",
            steps = listOf(PlanStep(id = "1", title = "Write code", status = "pending"))
        )
        every { planManager.currentPlan } returns plan
        val gk = makeGatekeeper()

        // Calls 1 through MAX_TOTAL_COMPLETION_ATTEMPTS should block
        repeat(CompletionGatekeeper.MAX_TOTAL_COMPLETION_ATTEMPTS) { i ->
            val result = gk.checkCompletion()
            assertNotNull(result, "Expected block on attempt ${i + 1}")
        }

        // The MAX_TOTAL_COMPLETION_ATTEMPTS + 1th call should force-accept
        val forced = gk.checkCompletion()
        assertNull(forced, "Expected force-accept after ${CompletionGatekeeper.MAX_TOTAL_COMPLETION_ATTEMPTS} blocked attempts")
    }

    @Test
    fun `passes immediately on first call when no gates block (not counting toward force)`() {
        val gk = makeGatekeeper()

        // Even on first call, if nothing blocks, returns null
        assertNull(gk.checkCompletion())
    }

    // --- SelfCorrectionGate propagation ---

    @Test
    fun `self-correction block propagates`() {
        every { selfCorrectionGate.checkCompletionReadiness() } returns ChatMessage(
            role = "system",
            content = "COMPLETION BLOCKED: unverified edits in Foo.kt"
        )
        val gk = makeGatekeeper()

        val result = gk.checkCompletion()

        assertNotNull(result)
        assertTrue(result!!.contains("unverified edits in Foo.kt"))
    }

    @Test
    fun `self-correction gate does not block when it returns null`() {
        every { selfCorrectionGate.checkCompletionReadiness() } returns null
        val gk = makeGatekeeper()

        assertNull(gk.checkCompletion())
    }

    @Test
    fun `self-correction message with null content does not block`() {
        every { selfCorrectionGate.checkCompletionReadiness() } returns ChatMessage(
            role = "system",
            content = null
        )
        val gk = makeGatekeeper()

        assertNull(gk.checkCompletion())
    }

    // --- LoopGuard propagation ---

    @Test
    fun `loop guard block propagates`() {
        every { loopGuard.beforeCompletion() } returns ChatMessage(
            role = "system",
            content = "Before completing, verify your changes: run diagnostics on Main.kt"
        )
        val gk = makeGatekeeper()

        val result = gk.checkCompletion()

        assertNotNull(result)
        assertTrue(result!!.contains("verify your changes"))
    }

    @Test
    fun `loop guard does not block when it returns null`() {
        every { loopGuard.beforeCompletion() } returns null
        val gk = makeGatekeeper()

        assertNull(gk.checkCompletion())
    }

    // --- Gate ordering ---

    @Test
    fun `post-compression gate fires before plan gate`() {
        iterationsSinceCompression = 0
        postCompressionAttempted = false

        val plan = AgentPlan(
            goal = "Implement feature",
            steps = listOf(PlanStep(id = "1", title = "Write code", status = "pending"))
        )
        every { planManager.currentPlan } returns plan
        val gk = makeGatekeeper()

        val result = gk.checkCompletion()

        assertNotNull(result)
        // Post-compression message is returned, not the plan message
        assertTrue(result!!.contains("compressed recently"))
        assertFalse(result.contains("incomplete steps"))
    }

    @Test
    fun `plan gate fires before self-correction gate`() {
        every { selfCorrectionGate.checkCompletionReadiness() } returns ChatMessage(
            role = "system",
            content = "COMPLETION BLOCKED: unverified edits"
        )

        val plan = AgentPlan(
            goal = "Implement feature",
            steps = listOf(PlanStep(id = "1", title = "Write code", status = "pending"))
        )
        every { planManager.currentPlan } returns plan
        val gk = makeGatekeeper()

        val result = gk.checkCompletion()

        assertNotNull(result)
        // Plan gate fires first
        assertTrue(result!!.contains("incomplete steps"))
        assertFalse(result.contains("unverified edits"))
    }

    @Test
    fun `self-correction gate fires before loop guard`() {
        every { selfCorrectionGate.checkCompletionReadiness() } returns ChatMessage(
            role = "system",
            content = "COMPLETION BLOCKED: unverified edits in Foo.kt"
        )
        every { loopGuard.beforeCompletion() } returns ChatMessage(
            role = "system",
            content = "Before completing, verify Main.kt"
        )
        val gk = makeGatekeeper()

        val result = gk.checkCompletion()

        assertNotNull(result)
        assertTrue(result!!.contains("unverified edits in Foo.kt"))
        assertFalse(result.contains("verify Main.kt"))
    }

    // --- Gate methods called appropriately ---

    @Test
    fun `loop guard beforeCompletion is called on each checkCompletion`() {
        val gk = makeGatekeeper()

        gk.checkCompletion()
        gk.checkCompletion()

        verify(exactly = 2) { loopGuard.beforeCompletion() }
    }

    @Test
    fun `self-correction gate is not called if plan gate blocks`() {
        val plan = AgentPlan(
            goal = "Implement feature",
            steps = listOf(PlanStep(id = "1", title = "Write code", status = "pending"))
        )
        every { planManager.currentPlan } returns plan
        val gk = makeGatekeeper()

        gk.checkCompletion()

        verify(exactly = 0) { selfCorrectionGate.checkCompletionReadiness() }
    }
}
