package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger

/**
 * C3: Controls how strictly completion gates are enforced.
 *
 * - [NORMAL]: All gates run and can block completion (default behavior).
 * - [LENIENT]: Only the plan gate runs — useful for forceTextOnly mode where
 *   the agent is already degraded and strict gating wastes iterations.
 * - [FORCED]: All gates run for metrics collection but never block completion —
 *   used when the caller has already decided to accept (e.g., max-attempts exceeded).
 */
enum class CompletionMode { NORMAL, LENIENT, FORCED }

/**
 * Orchestrates completion gates before accepting task completion.
 * Gates run in order — first block wins. Force-accepts after MAX_TOTAL_COMPLETION_ATTEMPTS.
 *
 * Gate order: Plan → SelfCorrection → LoopGuard
 */
class CompletionGatekeeper(
    private val planManager: PlanManager?,
    private val selfCorrectionGate: SelfCorrectionGate,
    private val loopGuard: LoopGuard
) {
    companion object {
        private val LOG = Logger.getInstance(CompletionGatekeeper::class.java)
        private const val MAX_PLAN_BLOCKS_WITHOUT_PROGRESS = 3
        const val MAX_TOTAL_COMPLETION_ATTEMPTS = 5
    }

    private var planGateBlockCount = 0
    private var lastPlanIncompleteCount = Int.MAX_VALUE
    private var totalCompletionAttempts = 0

    /**
     * Name of the gate that blocked the most recent completion attempt, or null if all passed
     * or completion was force-accepted. Callers can read this after [checkCompletion] returns
     * a non-null block message to record per-gate block counts in metrics.
     */
    var lastBlockedGate: String? = null
        private set

    /**
     * True when the last call to [checkCompletion] returned null because the max-attempts
     * limit was exceeded (force-accept), rather than because all gates passed normally.
     */
    var wasForceAccepted: Boolean = false
        private set

    /** Returns block message (String) if completion denied, null if all gates pass. */
    fun checkCompletion(): String? = checkCompletion(CompletionMode.NORMAL)

    /**
     * Check completion with the specified [mode].
     *
     * - [CompletionMode.NORMAL]: all gates run, any can block.
     * - [CompletionMode.LENIENT]: only plan gate runs (lighter check for degraded sessions).
     * - [CompletionMode.FORCED]: gates run for metrics but always returns null (never blocks).
     */
    fun checkCompletion(mode: CompletionMode): String? {
        lastBlockedGate = null
        wasForceAccepted = false
        totalCompletionAttempts++

        // FORCED mode: run gates for metrics but never block
        if (mode == CompletionMode.FORCED) {
            checkPlanCompletion()?.let { lastBlockedGate = "plan" }
            checkSelfCorrection()?.let { lastBlockedGate = "self_correction" }
            checkLoopGuard()?.let { lastBlockedGate = "loop_guard" }
            wasForceAccepted = true
            return null
        }

        if (totalCompletionAttempts > MAX_TOTAL_COMPLETION_ATTEMPTS) {
            LOG.warn("CompletionGatekeeper: force-accepting after $totalCompletionAttempts attempts")
            wasForceAccepted = true
            return null
        }

        // Plan gate always runs (NORMAL and LENIENT)
        checkPlanCompletion()?.let { lastBlockedGate = "plan"; return it }

        // LENIENT mode: skip self-correction and loop guard
        if (mode == CompletionMode.LENIENT) return null

        // NORMAL mode: all gates
        checkSelfCorrection()?.let { lastBlockedGate = "self_correction"; return it }
        checkLoopGuard()?.let { lastBlockedGate = "loop_guard"; return it }
        return null
    }

    private fun checkPlanCompletion(): String? {
        val plan = planManager?.currentPlan ?: return null
        val incomplete = plan.steps.filter { it.status != "done" && it.status != "skipped" }
        if (incomplete.isEmpty()) return null

        if (incomplete.size == lastPlanIncompleteCount) {
            planGateBlockCount++
        } else {
            planGateBlockCount = 0
        }
        lastPlanIncompleteCount = incomplete.size

        if (planGateBlockCount >= MAX_PLAN_BLOCKS_WITHOUT_PROGRESS) {
            return "COMPLETION BLOCKED (${planGateBlockCount}x): ${incomplete.size} plan steps " +
                "still incomplete with no progress. Continue working on them:\n" +
                incomplete.mapIndexed { i, step ->
                    "${i + 1}. [${step.status}] ${step.title}"
                }.joinToString("\n")
        }

        return "COMPLETION BLOCKED: Your plan has ${incomplete.size} incomplete steps:\n" +
            incomplete.mapIndexed { i, step ->
                "${i + 1}. [${step.status}] ${step.title}"
            }.joinToString("\n") +
            "\n\nContinue working on the next incomplete step."
    }

    private fun checkSelfCorrection(): String? {
        return selfCorrectionGate.checkCompletionReadiness()?.content
    }

    private fun checkLoopGuard(): String? {
        return loopGuard.beforeCompletion()?.content
    }
}
