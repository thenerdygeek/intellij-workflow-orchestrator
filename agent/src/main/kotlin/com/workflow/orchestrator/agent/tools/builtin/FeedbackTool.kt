package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Collects tool-quality feedback from the LLM after task completion.
 *
 * Registered as a core tool only when [AgentSettings.agentFeedbackEnabled] is true.
 * The agent loop prompts the LLM to call this tool immediately after
 * [AttemptCompletionTool] — one feedback entry per session, written to a
 * shared append-only log at ~/.workflow-orchestrator/feedback.md.
 *
 * Cross-process write safety is handled via [java.nio.channels.FileLock] so
 * concurrent IDE instances never interleave partial entries.
 */
class FeedbackTool(private val projectName: String) : AgentTool {

    override val name = "feedback"

    override val description = """
        Share feedback about the tools you used during this task. Call this after attempt_completion to report any issues, unexpected behaviors, or confusion encountered.

        Report issues such as:
        - A tool did not perform the expected action
        - Tool parameters were unclear, confusing, or contradictory
        - A tool returned incorrect, incomplete, or unexpected results
        - A tool failed or timed out unexpectedly

        If you have no feedback to share, call this with an empty string.
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "feedback" to ParameterProperty(
                type = "string",
                description = "Feedback about the tools used in this task. Describe which tool(s) had issues, what was expected, what actually happened, and any confusion about parameters. Pass an empty string if you have no feedback."
            )
        ),
        required = listOf("feedback")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val feedback = params["feedback"]?.jsonPrimitive?.content
            ?: return ToolResult.error(
                message = "Missing required parameter: feedback",
                summary = "feedback: missing parameter"
            )

        if (feedback.isBlank()) {
            return ToolResult(
                content = "No feedback recorded.",
                summary = "feedback: no issues reported",
                tokenEstimate = 5
            )
        }

        return try {
            appendFeedback(feedback)
            ToolResult(
                content = "Feedback recorded.",
                summary = "feedback: recorded",
                tokenEstimate = 5
            )
        } catch (e: Exception) {
            ToolResult.error(
                message = "Failed to write feedback: ${e.message}",
                summary = "feedback: write error"
            )
        }
    }

    private fun appendFeedback(feedback: String) {
        val dir = File(System.getProperty("user.home"), ".workflow-orchestrator")
        dir.mkdirs()
        val feedbackFile = File(dir, "feedback.md")

        val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val entry = buildString {
            append("\n---\n\n")
            append("**Timestamp:** $timestamp  \n")
            append("**Project:** $projectName  \n\n")
            append(feedback.trim())
            append("\n")
        }

        // RandomAccessFile + FileChannel.lock() gives OS-level advisory locking,
        // preventing interleaved writes from concurrent IDE instances on the same machine.
        RandomAccessFile(feedbackFile, "rw").use { raf ->
            val lock = raf.channel.lock()
            try {
                raf.seek(raf.length())
                raf.writeBytes(entry)
            } finally {
                lock.release()
            }
        }
    }
}
