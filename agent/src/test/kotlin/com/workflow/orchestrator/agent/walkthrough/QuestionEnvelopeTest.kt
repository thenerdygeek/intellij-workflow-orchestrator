package com.workflow.orchestrator.agent.walkthrough

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QuestionEnvelopeTest {
    @Test
    fun `formats step context, question, and the answer-action instruction`() {
        val step = WalkthroughStep("agent/src/AgentLoop.kt", 44, 47, "Entry", "x")
        val envelope = QuestionEnvelope.format(stepNumber = 3, step = step, question = "Why suspend here?")
        assertEquals(
            "[Walkthrough question about step 3 — agent/src/AgentLoop.kt:44-47] Why suspend here? " +
                "(Reply using the walkthrough tool with action=\"answer\".)",
            envelope
        )
    }
}
