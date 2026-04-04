// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.tools.subagent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SubagentModelsTest {

    @Test
    fun `SubagentRunStats has sensible defaults`() {
        val stats = SubagentRunStats()
        assertEquals(0, stats.toolCalls)
        assertEquals(0, stats.inputTokens)
        assertEquals(0, stats.outputTokens)
        assertEquals(0, stats.cacheWriteTokens)
        assertEquals(0, stats.cacheReadTokens)
        assertEquals(0.0, stats.totalCost)
        assertEquals(0, stats.contextTokens)
        assertEquals(0, stats.contextWindow)
        assertEquals(0.0, stats.contextUsagePercentage)
    }

    @Test
    fun `SubagentRunResult completed has result`() {
        val result = SubagentRunResult(
            status = SubagentRunStatus.COMPLETED,
            result = "Task finished successfully",
        )
        assertEquals(SubagentRunStatus.COMPLETED, result.status)
        assertEquals("Task finished successfully", result.result)
        assertNull(result.error)
    }

    @Test
    fun `SubagentRunResult failed has error`() {
        val result = SubagentRunResult(
            status = SubagentRunStatus.FAILED,
            error = "Context window exceeded",
        )
        assertEquals(SubagentRunStatus.FAILED, result.status)
        assertNull(result.result)
        assertEquals("Context window exceeded", result.error)
    }

    @Test
    fun `SubagentStatusItem defaults to pending`() {
        val item = SubagentStatusItem(index = 0, prompt = "Explore the codebase")
        assertEquals("pending", item.status)
        assertEquals(0, item.toolCalls)
        assertEquals(0.0, item.totalCost)
        assertNull(item.latestToolCall)
        assertNull(item.result)
        assertNull(item.error)
    }

    @Test
    fun `SubagentExecutionStatus has all lifecycle states`() {
        val statuses = SubagentExecutionStatus.entries
        assertEquals(4, statuses.size)
        assertTrue(statuses.contains(SubagentExecutionStatus.PENDING))
        assertTrue(statuses.contains(SubagentExecutionStatus.RUNNING))
        assertTrue(statuses.contains(SubagentExecutionStatus.COMPLETED))
        assertTrue(statuses.contains(SubagentExecutionStatus.FAILED))
    }

    @Test
    fun `SubagentProgressUpdate allows partial updates`() {
        val update = SubagentProgressUpdate(latestToolCall = "read_file")
        assertNull(update.stats)
        assertEquals("read_file", update.latestToolCall)
        assertNull(update.status)
        assertNull(update.result)
        assertNull(update.error)
    }

    @Test
    fun `SubagentUsageInfo defaults source to subagents`() {
        val usage = SubagentUsageInfo(tokensIn = 1000, tokensOut = 500)
        assertEquals("subagents", usage.source)
        assertEquals(1000, usage.tokensIn)
        assertEquals(500, usage.tokensOut)
        assertEquals(0, usage.cacheWrites)
        assertEquals(0, usage.cacheReads)
        assertEquals(0.0, usage.cost)
    }
}
