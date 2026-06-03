// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.loop.completion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompletionGatesTest {

    @Test
    fun `memory gate is never satisfied by a tool and nudges toward memory + index + re-completion`() {
        val gate = MemoryReviewGate("/proj/agent/memory")
        assertEquals("memory", gate.id)
        assertFalse(gate.isSatisfiedByTool("edit_file"))
        assertFalse(gate.isSatisfiedByTool("create_file"))
        assertFalse(gate.isSatisfiedByTool("attempt_completion"))
        val nudge = gate.nudge()
        assertTrue(nudge.contains("/proj/agent/memory")) { "nudge must name the memory dir" }
        assertTrue(nudge.contains("MEMORY.md")) { "nudge must tell the agent to update the index" }
        assertTrue(nudge.contains("attempt_completion")) { "nudge must tell the agent how to proceed" }
    }

    @Test
    fun `feedback gate is satisfied only by the feedback tool and keeps the original nudge text`() {
        val gate = FeedbackGate()
        assertEquals("feedback", gate.id)
        assertTrue(gate.isSatisfiedByTool("feedback"))
        assertFalse(gate.isSatisfiedByTool("read_file"))
        assertEquals(FeedbackGate.FEEDBACK_NUDGE, gate.nudge())
        assertTrue(gate.nudge().contains("`feedback` tool"))
    }
}
