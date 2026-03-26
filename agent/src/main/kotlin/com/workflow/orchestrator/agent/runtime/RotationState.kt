package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.context.TokenEstimator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Externalized agent state for context rotation.
 *
 * When the context budget is exhausted, the agent serializes its working state
 * to this structure, which is loaded into a new session's system prompt.
 * This enables graceful handoff instead of abrupt termination.
 */
@Serializable
data class RotationState(
    val goal: String,
    val accomplishments: String,
    val remainingWork: String,
    val modifiedFiles: List<String>,
    val guardrails: List<String>,
    val factsSnapshot: List<String>
) {
    companion object {
        private const val ROTATION_FILE = "rotation-state.json"
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        fun save(state: RotationState, sessionDir: File) {
            sessionDir.mkdirs()
            File(sessionDir, ROTATION_FILE).writeText(json.encodeToString(serializer(), state))
        }

        fun load(sessionDir: File): RotationState? {
            val file = File(sessionDir, ROTATION_FILE)
            if (!file.exists()) return null
            return json.decodeFromString(serializer(), file.readText())
        }
    }

    fun toContextString(maxTokens: Int = 10000): String {
        val full = buildString {
            appendLine("<rotated_context>")
            appendLine("This session continues work from a previous session whose context was full.")
            appendLine()
            appendLine("## Goal")
            appendLine(goal)
            appendLine()
            appendLine("## Accomplished")
            appendLine(accomplishments)
            appendLine()
            appendLine("## Remaining Work")
            appendLine(remainingWork)
            if (modifiedFiles.isNotEmpty()) {
                appendLine()
                appendLine("## Modified Files")
                for (f in modifiedFiles) appendLine("- $f")
            }
            if (guardrails.isNotEmpty()) {
                appendLine()
                appendLine("## Guardrails")
                for (g in guardrails) appendLine("- $g")
            }
            if (factsSnapshot.isNotEmpty()) {
                appendLine()
                appendLine("## Key Facts")
                for (f in factsSnapshot) appendLine("- $f")
            }
            appendLine("</rotated_context>")
        }.trimEnd()

        if (TokenEstimator.estimate(full) <= maxTokens) return full

        // Progressive truncation: drop facts
        val truncated = buildString {
            appendLine("<rotated_context>")
            appendLine("This session continues work from a previous session whose context was full.")
            appendLine()
            appendLine("## Goal")
            appendLine(goal)
            appendLine()
            appendLine("## Accomplished")
            appendLine(accomplishments)
            appendLine()
            appendLine("## Remaining Work")
            appendLine(remainingWork)
            if (modifiedFiles.isNotEmpty()) {
                appendLine()
                appendLine("## Modified Files")
                for (f in modifiedFiles) appendLine("- $f")
            }
            if (guardrails.isNotEmpty()) {
                appendLine()
                appendLine("## Guardrails")
                for (g in guardrails) appendLine("- $g")
            }
            appendLine()
            appendLine("(Facts truncated for token budget)")
            appendLine("</rotated_context>")
        }.trimEnd()

        return truncated
    }
}
