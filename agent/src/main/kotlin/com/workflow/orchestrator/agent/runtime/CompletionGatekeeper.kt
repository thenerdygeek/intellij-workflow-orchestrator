package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger

/**
 * Orchestrates completion gates before accepting task completion.
 * Gates run in order — first block wins. Force-accepts after MAX_TOTAL_COMPLETION_ATTEMPTS.
 *
 * Gate order: PostCompression → Plan → SelfCorrection → LoopGuard
 */
class CompletionGatekeeper(
    private val planManager: PlanManager?,
    private val selfCorrectionGate: SelfCorrectionGate,
    private val loopGuard: LoopGuard,
    private val iterationsSinceCompression: () -> Int,
    private val postCompressionCompletionAttempted: () -> Boolean,
    private val onPostCompressionAttempted: () -> Unit
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
    fun checkCompletion(): String? {
        lastBlockedGate = null
        wasForceAccepted = false
        totalCompletionAttempts++
        if (totalCompletionAttempts > MAX_TOTAL_COMPLETION_ATTEMPTS) {
            LOG.warn("CompletionGatekeeper: force-accepting after $totalCompletionAttempts attempts")
            wasForceAccepted = true
            return null
        }
        checkPostCompression()?.let { lastBlockedGate = "post_compression"; return it }
        checkPlanCompletion()?.let { lastBlockedGate = "plan"; return it }
        checkSelfCorrection()?.let { lastBlockedGate = "self_correction"; return it }
        checkLoopGuard()?.let { lastBlockedGate = "loop_guard"; return it }
        return null
    }

    private fun checkPostCompression(): String? {
        if (iterationsSinceCompression() > 2) return null
        if (postCompressionCompletionAttempted()) return null
        onPostCompressionAttempted()
        return "COMPLETION BLOCKED: Context was compressed recently. You may have lost " +
            "track of the task. Review the [CONTEXT COMPRESSED] summary above and the " +
            "active plan (if any). If there is remaining work, continue. " +
            "If truly done, call attempt_completion again."
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
                "still incomplete with no progress. To proceed, call update_plan_step for each:\n" +
                incomplete.joinToString("\n") {
                    "- update_plan_step(step=\"${it.title}\", status=\"skipped\", comment=\"Not needed\")"
                } + "\n\nOr continue working on them."
        }

        return "COMPLETION BLOCKED: Your plan has ${incomplete.size} incomplete steps:\n" +
            incomplete.mapIndexed { i, step ->
                "${i + 1}. [${step.status}] ${step.title}"
            }.joinToString("\n") +
            "\n\nContinue working on the next incomplete step. " +
            "If a step is no longer needed, call update_plan_step to mark it as skipped."
    }

    private fun checkSelfCorrection(): String? {
        return selfCorrectionGate.checkCompletionReadiness()?.content
    }

    private fun checkLoopGuard(): String? {
        return loopGuard.beforeCompletion()?.content
    }
}
