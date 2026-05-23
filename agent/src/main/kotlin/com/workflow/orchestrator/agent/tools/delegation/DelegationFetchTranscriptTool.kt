package com.workflow.orchestrator.agent.tools.delegation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.delegation.DelegationOutboundService
import com.workflow.orchestrator.agent.delegation.FetchTranscriptResult
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * `delegation_fetch_transcript(handle)` — retrieves the full message history of
 * a delegated Agent-B session and writes it to disk as `transcript-export.json`
 * under the delegated session's storage directory in IDE-B. Returns the
 * absolute path plus the first ~2 KB of the file as a preview, so the caller
 * can use `read_file` to page in specific sections.
 *
 * Plan 3 spec §5.7.
 */
class DelegationFetchTranscriptTool : AgentTool {

    override val name = "delegation_fetch_transcript"

    override val description = """
        Retrieve the full message history of a delegated Agent-B session. Returns
        an absolute file path to a transcript-export.json on IDE-B's filesystem
        plus a head preview, so the caller can read_file specific sections.

        When to use:
        - You want to inspect what Agent-B has done so far in a live or recently-closed
          delegation without waiting for the terminal Result message.
        - You need to diagnose a delegation that produced unexpected output.
        - You want to share specific context from a completed delegated session.

        How it works:
        1. IDE-A sends a FetchTranscript request to IDE-B over the existing IPC channel.
        2. IDE-B copies its api_conversation_history.json to transcript-export.json and
           replies with the file path on IDE-B's local filesystem.
        3. This tool returns the path plus the first 2 KiB as a preview.
        4. Use read_file on the returned path to access specific sections.

        Args:
          handle  (required) The channel handle returned by delegation_send.

        Returns on success:
          transcript_path — absolute path to transcript-export.json on IDE-B's filesystem
          size_bytes, token_estimate — for context budget planning
          head (first 2 KiB) — inline preview of the transcript content
        Returns on failure:
          DelegationExpired — the handle has expired, is unknown, or the session has
          been pruned. The transcript is no longer accessible via this channel.
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "handle" to ParameterProperty(
                type = "string",
                description = "The channel handle returned by delegation_send."
            ),
        ),
        required = listOf("handle"),
    )

    override val allowedWorkers = setOf(
        WorkerType.ORCHESTRATOR,
        WorkerType.CODER,
        WorkerType.ANALYZER,
    )

    /**
     * Test/internal entry point that takes pre-validated args, bypasses the
     * settings gate, and calls the outbound service directly.
     */
    suspend fun executeRaw(project: Project, handleId: String): ToolResult {
        val outbound = project.getService(DelegationOutboundService::class.java)
            ?: return ToolResult.error("delegation_fetch_transcript: DelegationOutboundService unavailable")
        return when (val outcome = outbound.fetchTranscript(handleId)) {
            is FetchTranscriptResult.Ok -> {
                val path = Path.of(outcome.transcriptPath)
                val bytes = try { Files.size(path) } catch (e: Exception) { -1L }
                val head = try {
                    Files.newInputStream(path).use { ins ->
                        ins.readNBytes(2048).toString(Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    "(head read failed: ${e.message})"
                }
                val tokenEstimate = if (bytes > 0) (bytes / 4).toInt() else 0
                val content = buildString {
                    append("transcript_path: ")
                    append(outcome.transcriptPath)
                    append("\nsize_bytes: ").append(bytes)
                    append("\ntoken_estimate: ").append(tokenEstimate)
                    append("\nhead (first 2 KiB):\n")
                    append(head)
                    if (bytes > 2048) {
                        append("\n[…truncated; use read_file on transcript_path for full content…]")
                    }
                }
                val shortId = handleId.take(8)
                LOG.debug("[DelegationFetchTranscript] handle=$shortId size=$bytes bytes")
                ToolResult(
                    content = content,
                    summary = content,
                    tokenEstimate = tokenEstimate.coerceAtLeast(ToolResult.ERROR_TOKEN_ESTIMATE),
                )
            }
            is FetchTranscriptResult.NotFound -> ToolResult.error(
                "DelegationExpired: ${outcome.reason}"
            )
        }
    }

    override suspend fun execute(
        params: JsonObject,
        project: Project,
    ): ToolResult {
        if (!PluginSettings.getInstance(project).state.enableOutboundCrossIdeDelegation) {
            return ToolResult.error(
                "DelegationOutboundDisabled: cross-IDE delegation is currently disabled in settings " +
                    "(Tools → Workflow Orchestrator → Agent → Enable outbound cross-IDE delegation)"
            )
        }
        val handleId = params["handle"]?.jsonPrimitive?.content
            ?: return ToolResult.error("delegation_fetch_transcript: 'handle' is required")
        return executeRaw(project, handleId)
    }

    companion object {
        private val LOG = Logger.getInstance(DelegationFetchTranscriptTool::class.java)
    }
}
