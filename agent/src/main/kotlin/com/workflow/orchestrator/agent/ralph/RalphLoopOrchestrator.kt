package com.workflow.orchestrator.agent.ralph

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Core Ralph Loop manager. Tracks state, makes decisions about continuing or stopping,
 * builds iteration context for system prompt injection.
 *
 * Deliberately decoupled from IntelliJ services — takes a directory for persistence
 * and pure data for decisions. AgentController wires it to the IDE.
 */
class RalphLoopOrchestrator(
    private val ralphDir: File,
) {
    companion object {
        private val LOG = Logger.getInstance(RalphLoopOrchestrator::class.java)
        private const val MAX_AUTO_EXPANDS = 3
        private const val AUTO_EXPAND_INCREMENT = 5
        private const val MAX_CONSECUTIVE_IMPROVES_WITHOUT_PROGRESS = 3
        private const val BUDGET_SAFETY_MARGIN = 0.95
    }

    @Volatile private var state: RalphLoopState? = null

    fun startLoop(prompt: String, config: RalphLoopConfig): RalphLoopState {
        val loopId = UUID.randomUUID().toString().take(12)
        val newState = RalphLoopState(
            loopId = loopId,
            projectPath = ralphDir.absolutePath,
            originalPrompt = prompt,
            maxIterations = config.maxIterations,
            maxCostUsd = config.maxCostUsd,
            reviewerEnabled = config.reviewerEnabled,
            phase = RalphPhase.EXECUTING,
            iteration = 1,
            totalCostUsd = 0.0,
            totalTokensUsed = 0,
            reviewerFeedback = null,
            priorAccomplishments = null,
            iterationHistory = emptyList(),
            autoExpandCount = 0,
            consecutiveImprovesWithoutProgress = 0,
            startedAt = Instant.now().toString(),
            lastIterationAt = Instant.now().toString(),
            completedAt = null,
            currentSessionId = null,
            allSessionIds = emptyList()
        )
        state = newState
        persistState(newState)
        LOG.info("RalphLoop: started loop $loopId — maxIter=${config.maxIterations}, budget=$${config.maxCostUsd}, reviewer=${config.reviewerEnabled}")
        return newState
    }

    fun getCurrentState(): RalphLoopState? = state

    fun cancel(): RalphLoopState? {
        val current = state ?: return null
        val cancelled = current.copy(
            phase = RalphPhase.CANCELLED,
            completedAt = Instant.now().toString()
        )
        state = cancelled
        persistState(cancelled)
        LOG.info("RalphLoop: cancelled ${current.loopId} at iteration ${current.iteration}")
        return cancelled
    }

    fun resumeInterrupted(interrupted: RalphLoopState) {
        val resumed = interrupted.copy(phase = RalphPhase.EXECUTING)
        state = resumed
        persistState(resumed)
        LOG.info("RalphLoop: resumed ${interrupted.loopId} at iteration ${interrupted.iteration}")
    }

    /**
     * Called after the agent session completes. Records the iteration, checks budget/limits.
     * Does NOT run the reviewer — caller must call [onReviewerResult] separately if reviewer is enabled.
     * Returns a decision if the loop should stop (budget/iterations), or [RalphLoopDecision.Continue]
     * with empty context as a signal that the caller should now run the reviewer.
     */
    fun onIterationCompleted(
        costUsd: Double,
        tokensUsed: Long,
        durationMs: Long,
        filesChanged: List<String>,
        completionSummary: String,
        sessionId: String,
    ): RalphLoopDecision {
        val current = state ?: return RalphLoopDecision.ForcedCompletion("No active loop", 0.0, 0)
        val newTotalCost = current.totalCostUsd + costUsd

        val record = RalphIterationRecord(
            iteration = current.iteration,
            sessionId = sessionId,
            costUsd = costUsd,
            tokensUsed = tokensUsed,
            durationMs = durationMs,
            reviewerVerdict = null,
            reviewerFeedback = null,
            filesChanged = filesChanged
        )

        var updated = current.copy(
            totalCostUsd = newTotalCost,
            totalTokensUsed = current.totalTokensUsed + tokensUsed,
            iterationHistory = current.iterationHistory + record,
            currentSessionId = sessionId,
            allSessionIds = current.allSessionIds + sessionId,
            phase = if (current.reviewerEnabled) RalphPhase.AWAITING_REVIEW else current.phase,
            priorAccomplishments = buildAccomplishments(current, completionSummary)
        )

        // Budget check
        if (updated.maxCostUsd > 0 && newTotalCost >= updated.maxCostUsd * BUDGET_SAFETY_MARGIN) {
            updated = updated.copy(phase = RalphPhase.FORCE_COMPLETED, completedAt = Instant.now().toString())
            state = updated
            persistState(updated)
            LOG.info("RalphLoop: budget exhausted ($${String.format("%.2f", newTotalCost)}/$${String.format("%.2f", updated.maxCostUsd)})")
            return RalphLoopDecision.ForcedCompletion(
                "Budget exhausted ($${String.format("%.2f", newTotalCost)}/$${String.format("%.2f", updated.maxCostUsd)})",
                newTotalCost, updated.iteration
            )
        }

        // Max iterations check (with auto-expand)
        if (updated.maxIterations > 0 && updated.iteration >= updated.maxIterations) {
            if (filesChanged.isNotEmpty() && updated.autoExpandCount < MAX_AUTO_EXPANDS) {
                updated = updated.copy(
                    maxIterations = updated.maxIterations + AUTO_EXPAND_INCREMENT,
                    autoExpandCount = updated.autoExpandCount + 1
                )
                LOG.info("RalphLoop: auto-expanded to ${updated.maxIterations} (expansion ${updated.autoExpandCount})")
            } else {
                updated = updated.copy(phase = RalphPhase.FORCE_COMPLETED, completedAt = Instant.now().toString())
                state = updated
                persistState(updated)
                LOG.info("RalphLoop: max iterations reached (${updated.iteration}/${updated.maxIterations})")
                return RalphLoopDecision.ForcedCompletion(
                    "Max iterations reached (${updated.iteration}/${updated.maxIterations})",
                    newTotalCost, updated.iteration
                )
            }
        }

        state = updated
        persistState(updated)

        // If reviewer disabled, continue to next iteration
        if (!updated.reviewerEnabled) {
            val next = updated.copy(
                iteration = updated.iteration + 1,
                lastIterationAt = Instant.now().toString()
            )
            state = next
            persistState(next)
            return RalphLoopDecision.Continue(buildIterationContext())
        }

        // Reviewer enabled — caller should now spawn reviewer and call onReviewerResult
        return RalphLoopDecision.Continue("")
    }

    /**
     * Called after the reviewer returns a verdict.
     * Updates state and returns the final decision for this iteration.
     */
    fun onReviewerResult(result: ReviewResult, reviewerCostUsd: Double): RalphLoopDecision {
        val current = state ?: return RalphLoopDecision.ForcedCompletion("No active loop", 0.0, 0)

        // Transition to REVIEWING phase (spec Section 2 state machine)
        state = current.copy(phase = RalphPhase.REVIEWING)
        persistState(state!!)

        val newTotalCost = current.totalCostUsd + reviewerCostUsd

        // Update the last iteration record with reviewer verdict
        val updatedHistory = current.iterationHistory.toMutableList()
        if (updatedHistory.isNotEmpty()) {
            val lastRecord = updatedHistory.last()
            updatedHistory[updatedHistory.lastIndex] = lastRecord.copy(
                reviewerVerdict = result.verdict.name,
                reviewerFeedback = result.feedback
            )
        }

        when (result.verdict) {
            ReviewVerdict.ACCEPT -> {
                val completed = current.copy(
                    phase = RalphPhase.COMPLETED,
                    totalCostUsd = newTotalCost,
                    completedAt = Instant.now().toString(),
                    iterationHistory = updatedHistory
                )
                state = completed
                persistState(completed)
                LOG.info("RalphLoop: reviewer ACCEPTED at iteration ${current.iteration}")
                return RalphLoopDecision.Completed(
                    "Reviewer accepted after ${current.iteration} iterations",
                    newTotalCost, current.iteration
                )
            }
            ReviewVerdict.IMPROVE -> {
                val lastFilesChanged = current.iterationHistory.lastOrNull()?.filesChanged ?: emptyList()
                val newConsecutive = if (lastFilesChanged.isEmpty()) {
                    current.consecutiveImprovesWithoutProgress + 1
                } else 0

                // Stuck detection
                if (newConsecutive >= MAX_CONSECUTIVE_IMPROVES_WITHOUT_PROGRESS) {
                    val forced = current.copy(
                        phase = RalphPhase.FORCE_COMPLETED,
                        totalCostUsd = newTotalCost,
                        completedAt = Instant.now().toString(),
                        iterationHistory = updatedHistory,
                        consecutiveImprovesWithoutProgress = newConsecutive
                    )
                    state = forced
                    persistState(forced)
                    LOG.info("RalphLoop: force-completed — $newConsecutive consecutive IMPROVEs without file changes")
                    return RalphLoopDecision.ForcedCompletion(
                        "Reviewer requested improvements $newConsecutive consecutive times without progress. Force-completing.",
                        newTotalCost, current.iteration
                    )
                }

                val next = current.copy(
                    phase = RalphPhase.EXECUTING,
                    iteration = current.iteration + 1,
                    totalCostUsd = newTotalCost,
                    reviewerFeedback = result.feedback,
                    lastIterationAt = Instant.now().toString(),
                    iterationHistory = updatedHistory,
                    consecutiveImprovesWithoutProgress = newConsecutive
                )
                state = next
                persistState(next)
                LOG.info("RalphLoop: reviewer IMPROVE at iteration ${current.iteration} — continuing to ${next.iteration}")
                return RalphLoopDecision.Continue(buildIterationContext())
            }
        }
    }

    fun buildIterationContext(): String {
        val current = state ?: return ""
        return buildString {
            appendLine("<ralph_iteration>")
            appendLine("You are on iteration ${current.iteration} of a self-improvement loop.")
            appendLine("Your task is to review and improve upon work done in previous iterations.")
            appendLine()
            appendLine("## Original Task")
            appendLine(current.originalPrompt)
            if (!current.priorAccomplishments.isNullOrBlank()) {
                appendLine()
                appendLine("## What Was Done in Previous Iterations")
                appendLine(current.priorAccomplishments)
            }
            if (!current.reviewerFeedback.isNullOrBlank()) {
                appendLine()
                appendLine("## Reviewer Feedback (from iteration ${current.iteration - 1})")
                appendLine("The reviewer evaluated the previous iteration's work and requested improvements:")
                appendLine(current.reviewerFeedback)
            }
            appendLine()
            appendLine("## Instructions")
            appendLine("1. Read the files that were modified in previous iterations")
            appendLine("2. Review the current state of the code against the original task")
            if (!current.reviewerFeedback.isNullOrBlank()) {
                appendLine("3. Address the reviewer's feedback specifically")
                appendLine("4. Make improvements and call attempt_completion when done")
            } else {
                appendLine("3. Make improvements and call attempt_completion when done")
            }
            appendLine("</ralph_iteration>")
        }
    }

    /** For testing — force iteration to a specific value. */
    internal fun forceIteration(iteration: Int) {
        val current = state ?: return
        state = current.copy(iteration = iteration)
        persistState(state!!)
    }

    /** For testing — set reviewer feedback and accomplishments. */
    internal fun setReviewerFeedback(feedback: String, accomplishments: String) {
        val current = state ?: return
        state = current.copy(reviewerFeedback = feedback, priorAccomplishments = accomplishments)
    }

    private fun buildAccomplishments(current: RalphLoopState, latestSummary: String): String {
        val prior = current.priorAccomplishments
        return if (prior.isNullOrBlank()) {
            "- Iteration ${current.iteration}: $latestSummary"
        } else {
            "$prior\n- Iteration ${current.iteration}: $latestSummary"
        }
    }

    private fun persistState(s: RalphLoopState) {
        val dir = File(ralphDir, s.loopId)
        RalphLoopState.save(s, dir)
    }
}
