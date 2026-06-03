// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.loop.completion

import com.workflow.orchestrator.agent.loop.LoopResult
import com.workflow.orchestrator.agent.loop.completion.CompletionGateChain.Outcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CompletionGateChainTest {

    private fun completed(summary: String) = LoopResult.Completed(
        summary = summary,
        iterations = 1,
        tokensUsed = 0,
        completionData = null,
        inputTokens = 0,
        outputTokens = 0,
        filesModified = emptyList(),
        linesAdded = 0,
        linesRemoved = 0,
    )

    /** Fake gate: optional satisfying tool; nudge is "nudge:<id>". */
    private fun gate(gid: String, satisfyingTool: String? = null) = object : CompletionGate {
        override val id = gid
        override fun nudge() = "nudge:$gid"
        override fun isSatisfiedByTool(toolName: String) = toolName == satisfyingTool
    }

    @Test
    fun `empty chain finishes immediately on first attempt`() {
        val chain = CompletionGateChain(emptyList())
        val fresh = completed("done")
        val outcome = chain.onCompletionAttempt(fresh)
        assertInstanceOf(Outcome.Finish::class.java, outcome)
        assertSame(fresh, (outcome as Outcome.Finish).result)
    }

    @Test
    fun `memory-only gate arms then finishes on re-completion`() {
        val chain = CompletionGateChain(listOf(gate("memory")))
        val arm = chain.onCompletionAttempt(completed("first"))
        assertEquals("nudge:memory", (arm as Outcome.Arm).nudge)
        // A non-satisfying tool while armed is ignored (the edit just executes).
        assertInstanceOf(Outcome.Ignore::class.java, chain.onToolUsed("edit_file"))
        // Re-completion advances past the memory gate → finish.
        assertInstanceOf(Outcome.Finish::class.java, chain.onCompletionAttempt(completed("second")))
    }

    @Test
    fun `feedback-only gate finishes when the feedback tool fires`() {
        val chain = CompletionGateChain(listOf(gate("feedback", satisfyingTool = "feedback")))
        assertInstanceOf(Outcome.Arm::class.java, chain.onCompletionAttempt(completed("x")))
        assertInstanceOf(Outcome.Ignore::class.java, chain.onToolUsed("read_file"))
        assertInstanceOf(Outcome.Finish::class.java, chain.onToolUsed("feedback"))
    }

    @Test
    fun `re-completion bypasses a tool-satisfied gate too`() {
        val chain = CompletionGateChain(listOf(gate("feedback", satisfyingTool = "feedback")))
        assertInstanceOf(Outcome.Arm::class.java, chain.onCompletionAttempt(completed("x")))
        // Agent re-issues attempt_completion instead of calling feedback → still finishes.
        assertInstanceOf(Outcome.Finish::class.java, chain.onCompletionAttempt(completed("y")))
    }

    @Test
    fun `two gates arm in order memory then feedback`() {
        val chain = CompletionGateChain(
            listOf(gate("memory"), gate("feedback", satisfyingTool = "feedback"))
        )
        assertEquals("nudge:memory", (chain.onCompletionAttempt(completed("a")) as Outcome.Arm).nudge)
        assertEquals("nudge:feedback", (chain.onCompletionAttempt(completed("b")) as Outcome.Arm).nudge)
        assertInstanceOf(Outcome.Finish::class.java, chain.onToolUsed("feedback"))
    }

    @Test
    fun `tool result before any gate is armed is ignored`() {
        val chain = CompletionGateChain(listOf(gate("memory")))
        assertInstanceOf(Outcome.Ignore::class.java, chain.onToolUsed("feedback"))
    }

    @Test
    fun `finish returns the first completion, not later re-completions`() {
        val chain = CompletionGateChain(listOf(gate("memory")))
        chain.onCompletionAttempt(completed("first"))
        val finish = chain.onCompletionAttempt(completed("second")) as Outcome.Finish
        assertEquals("first", finish.result.summary)
    }
}
