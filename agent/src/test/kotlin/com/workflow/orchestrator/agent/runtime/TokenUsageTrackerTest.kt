package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

class TokenUsageTrackerTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var tracker: TokenUsageTracker

    @BeforeEach
    fun setUp() {
        tracker = TokenUsageTracker(tempDir)
    }

    @Test
    fun `recordUsage accumulates correctly`() {
        tracker.recordUsage(promptTokens = 100, completionTokens = 50)
        tracker.recordUsage(promptTokens = 200, completionTokens = 75)

        val task = tracker.currentTask()
        assertEquals(300, task.promptTokens)
        assertEquals(125, task.completionTokens)
        assertEquals(425, task.totalTokens)
        assertEquals(2, task.llmCalls)
    }

    @Test
    fun `currentTask returns current task usage`() {
        val initial = tracker.currentTask()
        assertEquals(0, initial.promptTokens)
        assertEquals(0, initial.completionTokens)
        assertEquals(0, initial.totalTokens)
        assertEquals(0, initial.llmCalls)

        tracker.recordUsage(promptTokens = 500, completionTokens = 200)

        val updated = tracker.currentTask()
        assertEquals(500, updated.promptTokens)
        assertEquals(200, updated.completionTokens)
        assertEquals(700, updated.totalTokens)
        assertEquals(1, updated.llmCalls)
    }

    @Test
    fun `dailyTotal accumulates across tasks`() {
        tracker.recordUsage(promptTokens = 100, completionTokens = 50)
        tracker.startNewTask()
        tracker.recordUsage(promptTokens = 200, completionTokens = 75)

        val daily = tracker.dailyTotal()
        assertEquals(425, daily.totalTokens)
        assertEquals(2, daily.totalCalls)
    }

    @Test
    fun `startNewTask resets task but keeps daily`() {
        tracker.recordUsage(promptTokens = 100, completionTokens = 50)
        tracker.startNewTask()

        val task = tracker.currentTask()
        assertEquals(0, task.promptTokens)
        assertEquals(0, task.completionTokens)
        assertEquals(0, task.totalTokens)
        assertEquals(0, task.llmCalls)

        val daily = tracker.dailyTotal()
        assertEquals(150, daily.totalTokens)
        assertEquals(1, daily.totalCalls)
        assertEquals(1, daily.tasksCompleted)
    }

    @Test
    fun `startNewTask does not increment tasksCompleted when no tokens used`() {
        tracker.startNewTask()
        assertEquals(0, tracker.dailyTotal().tasksCompleted)
    }

    @Test
    fun `isDailyBudgetExceeded returns true when over limit`() {
        tracker.recordUsage(promptTokens = 800, completionTokens = 300)

        assertTrue(tracker.isDailyBudgetExceeded(1000))
        assertTrue(tracker.isDailyBudgetExceeded(1100))
        assertFalse(tracker.isDailyBudgetExceeded(1200))
    }

    @Test
    fun `isDailyBudgetExceeded returns false when under limit`() {
        tracker.recordUsage(promptTokens = 100, completionTokens = 50)

        assertFalse(tracker.isDailyBudgetExceeded(200))
        assertFalse(tracker.isDailyBudgetExceeded(1000000))
    }

    @Test
    fun `save and load persists daily usage`() {
        tracker.recordUsage(promptTokens = 500, completionTokens = 200)
        tracker.startNewTask()
        tracker.recordUsage(promptTokens = 300, completionTokens = 100)
        tracker.save()

        // Create a new tracker from the same directory — should load persisted data
        val reloaded = TokenUsageTracker(tempDir)
        val daily = reloaded.dailyTotal()
        assertEquals(1100, daily.totalTokens)
        assertEquals(2, daily.totalCalls)
        assertEquals(1, daily.tasksCompleted)
        assertEquals(LocalDate.now().toString(), daily.date)
    }

    @Test
    fun `new day resets daily counters`() {
        // Write a usage file with yesterday's date
        val yesterday = LocalDate.now().minusDays(1).toString()
        val usageFile = File(tempDir, "usage.json")
        usageFile.writeText("""
            {
                "date": "$yesterday",
                "totalTokens": 99999,
                "totalCalls": 50,
                "tasksCompleted": 10
            }
        """.trimIndent())

        val freshTracker = TokenUsageTracker(tempDir)
        val daily = freshTracker.dailyTotal()
        assertEquals(LocalDate.now().toString(), daily.date)
        assertEquals(0, daily.totalTokens)
        assertEquals(0, daily.totalCalls)
        assertEquals(0, daily.tasksCompleted)
    }

    @Test
    fun `estimateCost returns reasonable values`() {
        val cost = tracker.estimateCost(promptTokens = 1000, completionTokens = 1000)

        // 1000 prompt tokens: (1000/1000) * 0.003 = 0.003
        // 1000 completion tokens: (1000/1000) * 0.015 = 0.015
        // Total: 0.018
        assertEquals(0.018, cost, 0.001)
    }

    @Test
    fun `estimateCost with zero tokens returns zero`() {
        val cost = tracker.estimateCost(promptTokens = 0, completionTokens = 0)
        assertEquals(0.0, cost, 0.0001)
    }

    @Test
    fun `forProject creates tracker with correct storage directory`() {
        val tracker = TokenUsageTracker.forProject("/some/project")
        // Just verify it doesn't throw — the directory is lazily created on save
        assertNotNull(tracker)
        assertEquals(0, tracker.currentTask().totalTokens)
    }

    @Test
    fun `load handles corrupt usage file gracefully`() {
        val usageFile = File(tempDir, "usage.json")
        usageFile.writeText("not valid json{{{")

        val freshTracker = TokenUsageTracker(tempDir)
        val daily = freshTracker.dailyTotal()
        assertEquals(LocalDate.now().toString(), daily.date)
        assertEquals(0, daily.totalTokens)
    }
}
