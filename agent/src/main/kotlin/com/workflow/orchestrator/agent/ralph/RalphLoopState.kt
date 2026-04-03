package com.workflow.orchestrator.agent.ralph

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
private const val STATE_FILE = "ralph-state.json"

@Serializable
enum class RalphPhase {
    EXECUTING,
    AWAITING_REVIEW,
    REVIEWING,
    COMPLETED,
    FORCE_COMPLETED,
    CANCELLED,
    INTERRUPTED;

    val isTerminal: Boolean get() = this in setOf(COMPLETED, FORCE_COMPLETED, CANCELLED)
}

@Serializable
data class RalphIterationRecord(
    val iteration: Int,
    val sessionId: String,
    val costUsd: Double,
    val tokensUsed: Long,
    val durationMs: Long,
    val reviewerVerdict: String?,
    val reviewerFeedback: String?,
    val filesChanged: List<String>,
)

@Serializable
data class RalphLoopState(
    val loopId: String,
    val projectPath: String,
    val originalPrompt: String,
    val maxIterations: Int,
    val maxCostUsd: Double,
    val reviewerEnabled: Boolean,
    val phase: RalphPhase,
    val iteration: Int,
    val totalCostUsd: Double,
    val totalTokensUsed: Long,
    val reviewerFeedback: String?,
    val priorAccomplishments: String?,
    val iterationHistory: List<RalphIterationRecord>,
    val autoExpandCount: Int,
    val consecutiveImprovesWithoutProgress: Int,
    val startedAt: String,
    val lastIterationAt: String?,
    val completedAt: String?,
    val currentSessionId: String?,
    val allSessionIds: List<String>,
) {
    companion object {
        fun save(state: RalphLoopState, dir: File) {
            dir.mkdirs()
            File(dir, STATE_FILE).writeText(json.encodeToString(serializer(), state))
        }

        fun load(dir: File): RalphLoopState? {
            val file = File(dir, STATE_FILE)
            if (!file.exists()) return null
            return try {
                json.decodeFromString(serializer(), file.readText())
            } catch (_: Exception) {
                null
            }
        }

        fun delete(dir: File) {
            File(dir, STATE_FILE).delete()
        }
    }
}

data class RalphLoopConfig(
    val maxIterations: Int = 10,
    val maxCostUsd: Double = 10.0,
    val reviewerEnabled: Boolean = true,
)

sealed class RalphLoopDecision {
    data class Continue(val iterationContext: String) : RalphLoopDecision()
    data class Completed(val summary: String, val totalCost: Double, val iterations: Int) : RalphLoopDecision()
    data class ForcedCompletion(val reason: String, val totalCost: Double, val iterations: Int) : RalphLoopDecision()
}
