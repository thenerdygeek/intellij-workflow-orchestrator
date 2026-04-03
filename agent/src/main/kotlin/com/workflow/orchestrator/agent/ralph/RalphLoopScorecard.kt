package com.workflow.orchestrator.agent.ralph

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

@Serializable
data class RalphLoopScorecard(
    val loopId: String,
    val totalIterations: Int,
    val totalCostUsd: Double,
    val totalTokensUsed: Long,
    val totalDurationMs: Long,
    val totalFilesModified: Int,
    val outcome: String,
) {
    companion object {
        fun fromState(state: RalphLoopState): RalphLoopScorecard {
            val allFiles = state.iterationHistory.flatMap { it.filesChanged }.distinct()
            val totalDuration = state.iterationHistory.sumOf { it.durationMs }
            return RalphLoopScorecard(
                loopId = state.loopId,
                totalIterations = state.iterationHistory.size,
                totalCostUsd = state.totalCostUsd,
                totalTokensUsed = state.totalTokensUsed,
                totalDurationMs = totalDuration,
                totalFilesModified = allFiles.size,
                outcome = state.phase.name
            )
        }

        fun save(scorecard: RalphLoopScorecard, metricsDir: File) {
            metricsDir.mkdirs()
            File(metricsDir, "ralph-${scorecard.loopId}.json")
                .writeText(json.encodeToString(serializer(), scorecard))
        }

        fun load(loopId: String, metricsDir: File): RalphLoopScorecard? {
            val file = File(metricsDir, "ralph-$loopId.json")
            if (!file.exists()) return null
            return try { json.decodeFromString(serializer(), file.readText()) } catch (_: Exception) { null }
        }
    }
}
