// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.loop.completion

import com.workflow.orchestrator.agent.loop.LoopResult

/**
 * Pure state machine that sequences post-`attempt_completion` gates. Dependency-free
 * (no IntelliJ / settings) so the whole ordering contract is unit-testable.
 *
 * [gates] is built pre-filtered and in order by [com.workflow.orchestrator.agent.loop.AgentLoop]
 * (memory before feedback). An empty list finishes on the first attempt — the normal
 * no-gates fast path.
 *
 * Two entry points feed it signals:
 *  - [onCompletionAttempt] — the agent called `attempt_completion`.
 *  - [onToolUsed] — any non-completion tool result.
 *
 * Universal rule: re-issuing `attempt_completion` advances past whatever gate is armed
 * (a gate's only required exit signal). A gate may ALSO be cleared early by a specific
 * tool via [CompletionGate.isSatisfiedByTool]. Single-use per AgentLoop run.
 */
class CompletionGateChain(private val gates: List<CompletionGate>) {

    private var pending: LoopResult.Completed? = null
    private var cursor = 0
    private var armed = false

    sealed interface Outcome {
        /** Inject [nudge] and continue the loop. */
        data class Arm(val nudge: String) : Outcome
        /** Return [result] from the loop — the chain is exhausted. */
        data class Finish(val result: LoopResult.Completed) : Outcome
        /** Not gate-relevant; proceed normally. */
        data object Ignore : Outcome
    }

    /** Drive the chain from the Completion branch. Never returns [Outcome.Ignore]. */
    fun onCompletionAttempt(fresh: LoopResult.Completed): Outcome {
        if (pending == null) {
            // First attempt_completion of this run: capture the result we will ultimately
            // return. Captured ONCE so later memory writes don't pollute filesModified/diff.
            pending = fresh
        } else if (armed) {
            // Re-completion is the universal "advance past the armed gate" signal.
            cursor++
            armed = false
        }
        return armNextOrFinish()
    }

    /** Drive the chain from the Standard/Error tool-result branch. */
    fun onToolUsed(toolName: String): Outcome {
        if (!armed) return Outcome.Ignore
        if (!gates[cursor].isSatisfiedByTool(toolName)) return Outcome.Ignore
        cursor++
        armed = false
        return armNextOrFinish()
    }

    private fun armNextOrFinish(): Outcome {
        if (cursor < gates.size) {
            armed = true
            return Outcome.Arm(gates[cursor].nudge())
        }
        return Outcome.Finish(pending!!)
    }
}
