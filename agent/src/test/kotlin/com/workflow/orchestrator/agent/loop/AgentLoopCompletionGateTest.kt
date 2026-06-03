// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-text pins for the AgentLoop integration of the completion-gate chain.
 * Same pattern (and same prior approval) as [AgentLoopStreamingEditTest]: the
 * behavioral contract is covered by CompletionGateChainTest; these pins fail
 * loudly if a refactor drops the wiring or resurrects the old flag-based logic.
 */
class AgentLoopCompletionGateTest {

    private val src: String by lazy {
        java.io.File("src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt").readText()
    }

    @Test
    fun `AgentLoop builds a CompletionGateChain`() {
        assertTrue("CompletionGateChain(" in src) {
            "AgentLoop must construct a CompletionGateChain from the enabled gates."
        }
    }

    @Test
    fun `Completion branch delegates to the chain`() {
        assertTrue("completionGates.onCompletionAttempt(" in src) {
            "The Completion branch must drive the chain via onCompletionAttempt(...)."
        }
        assertTrue("completionGates.onToolUsed(" in src) {
            "The Standard/Error branch must drive the chain via onToolUsed(...)."
        }
    }

    @Test
    fun `old flag-based feedback state is gone`() {
        assertFalse("awaitingFeedback" in src) {
            "awaitingFeedback must be removed — its state now lives in CompletionGateChain."
        }
        assertFalse("private var pendingCompletion" in src) {
            "pendingCompletion field must be removed — its state now lives in CompletionGateChain."
        }
    }
}
