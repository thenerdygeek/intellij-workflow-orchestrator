package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.core.util.ProjectIdentifier
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * Tracks token usage across agent tasks for cost visibility and budget enforcement.
 * Persists daily usage to {agentDir}/usage.json.
 */
class TokenUsageTracker(
    private val storageDir: File
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val usageFile = File(storageDir, "usage.json")

    private var currentTaskUsage = TaskUsage()
    private var dailyUsage: DailyUsage = loadOrCreateDailyUsage()

    /** Record tokens used by a single LLM call. */
    fun recordUsage(promptTokens: Int, completionTokens: Int) {
        currentTaskUsage = currentTaskUsage.copy(
            promptTokens = currentTaskUsage.promptTokens + promptTokens,
            completionTokens = currentTaskUsage.completionTokens + completionTokens,
            totalTokens = currentTaskUsage.totalTokens + promptTokens + completionTokens,
            llmCalls = currentTaskUsage.llmCalls + 1
        )
        dailyUsage = dailyUsage.copy(
            totalTokens = dailyUsage.totalTokens + promptTokens + completionTokens,
            totalCalls = dailyUsage.totalCalls + 1
        )
    }

    /** Get current task usage. */
    fun currentTask(): TaskUsage = currentTaskUsage

    /** Get today's cumulative usage. */
    fun dailyTotal(): DailyUsage = dailyUsage

    /** Reset for a new task. */
    fun startNewTask() {
        if (currentTaskUsage.totalTokens > 0) {
            dailyUsage = dailyUsage.copy(tasksCompleted = dailyUsage.tasksCompleted + 1)
        }
        currentTaskUsage = TaskUsage()
    }

    /** Check if daily budget is exceeded. */
    fun isDailyBudgetExceeded(maxDailyTokens: Int): Boolean {
        return dailyUsage.totalTokens >= maxDailyTokens
    }

    /** Estimate cost based on token usage (rough estimate). */
    fun estimateCost(promptTokens: Int, completionTokens: Int): Double {
        // Approximate pricing for Claude Sonnet via Sourcegraph
        // These are rough estimates — actual pricing depends on the Sourcegraph contract
        val promptCostPer1K = 0.003   // $3 per 1M input tokens
        val completionCostPer1K = 0.015  // $15 per 1M output tokens
        return (promptTokens / 1000.0) * promptCostPer1K + (completionTokens / 1000.0) * completionCostPer1K
    }

    /** Persist daily usage to disk. */
    fun save() {
        try {
            storageDir.mkdirs()
            usageFile.writeText(json.encodeToString(DailyUsage.serializer(), dailyUsage))
        } catch (_: Exception) { /* best effort */ }
    }

    private fun loadOrCreateDailyUsage(): DailyUsage {
        return try {
            if (usageFile.exists()) {
                val loaded = json.decodeFromString(DailyUsage.serializer(), usageFile.readText())
                if (loaded.date == LocalDate.now().toString()) loaded
                else DailyUsage(date = LocalDate.now().toString()) // new day, reset
            } else {
                DailyUsage(date = LocalDate.now().toString())
            }
        } catch (_: Exception) {
            DailyUsage(date = LocalDate.now().toString())
        }
    }

    companion object {
        fun forProject(projectBasePath: String): TokenUsageTracker {
            return TokenUsageTracker(ProjectIdentifier.agentDir(projectBasePath))
        }
    }
}

@Serializable
data class TaskUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val llmCalls: Int = 0
)

@Serializable
data class DailyUsage(
    val date: String = "",
    val totalTokens: Int = 0,
    val totalCalls: Int = 0,
    val tasksCompleted: Int = 0
)
