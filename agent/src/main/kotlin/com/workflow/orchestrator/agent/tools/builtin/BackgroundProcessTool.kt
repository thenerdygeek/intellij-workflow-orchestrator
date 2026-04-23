package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.background.BackgroundHandle
import com.workflow.orchestrator.agent.tools.background.BackgroundPool
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class BackgroundProcessTool : AgentTool {
    override val name = "background_process"
    override val description = "Manage background processes spawned in this session via run_command(background: true). " +
        "With no args, lists all background processes in this session. " +
        "With an id and no action, returns status. " +
        "Actions: status, output, attach, send_stdin, kill. " +
        "Background processes are session-scoped — killed on session transitions."
    override val parameters: FunctionParameters = FunctionParameters(
        properties = linkedMapOf(
            "id" to ParameterProperty(type = "string",
                description = "Background process ID (e.g., bg_a1b2c3d4). Omit to list all processes."),
            "action" to ParameterProperty(type = "string",
                description = "Operation. If id is given without action, returns status.",
                enumValues = listOf("status", "output", "attach", "send_stdin", "kill")),
            "tail_lines" to ParameterProperty(type = "integer",
                description = "[output] Return last N lines."),
            "since_offset" to ParameterProperty(type = "integer",
                description = "[output] Return bytes after this offset."),
            "grep_pattern" to ParameterProperty(type = "string",
                description = "[output] Filter output lines matching this regex."),
            "output_file" to ParameterProperty(type = "boolean",
                description = "[output] When true, writes full output to disk and returns a preview + path."),
            "input" to ParameterProperty(type = "string",
                description = "[send_stdin] Text to send. Include \\n for Enter."),
            "timeout_seconds" to ParameterProperty(type = "integer",
                description = "[attach] Max seconds to wait in monitor loop. Default: 600, Max: 600."),
        ),
        required = emptyList(),
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    companion object {
        var currentSessionId: ThreadLocal<String?> = ThreadLocal.withInitial { null }
        val WRITE_ACTIONS = setOf("kill", "send_stdin", "attach")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val sessionId = currentSessionId.get()
            ?: return toolError("NO_SESSION_CONTEXT: session id not set by AgentLoop")
        val pool = BackgroundPool.getInstance(project)
        val id = params["id"]?.jsonPrimitive?.content
        val action = params["action"]?.jsonPrimitive?.content ?: if (id != null) "status" else "list"

        return when (action) {
            "list" -> doList(pool, sessionId)
            "status" -> {
                val h = pool.get(sessionId, id!!)
                    ?: return toolError("NO_SUCH_ID_IN_SESSION: '$id' not in session '$sessionId'")
                doStatus(h)
            }
            "output" -> {
                val h = pool.get(sessionId, id!!)
                    ?: return toolError("NO_SUCH_ID_IN_SESSION: '$id' not in session '$sessionId'")
                doOutput(h, params)
            }
            else -> toolError("UNSUPPORTED_ACTION: '$action' (supported: list, status, output)")
        }
    }

    private fun doList(pool: BackgroundPool, sessionId: String): ToolResult {
        val handles = pool.list(sessionId)
        if (handles.isEmpty()) {
            return ToolResult(
                content = "No background processes in this session.",
                summary = "No bg processes",
                tokenEstimate = 10,
            )
        }
        val rows = handles.joinToString("\n") { h ->
            val exit = h.exitCode()?.toString() ?: "—"
            "${h.bgId}  ${h.kind}  ${truncate(h.label, 60)}  ${h.state()}  " +
                "${formatRuntime(h.runtimeMs())}  out=${h.outputBytes()}B  exit=$exit"
        }
        val content = "Background processes (${handles.size}):\n$rows"
        return ToolResult(
            content = content,
            summary = "${handles.size} bg processes",
            tokenEstimate = TokenEstimator.estimate(content),
        )
    }

    private fun doStatus(h: BackgroundHandle): ToolResult {
        val exit = h.exitCode()?.toString() ?: "—"
        val tail = h.readOutput(tailLines = 5).content.lines().joinToString("\n") { "  $it" }
        val content = buildString {
            appendLine("Background process: ${h.bgId}")
            appendLine("Kind: ${h.kind}")
            appendLine("Label: ${h.label}")
            appendLine("State: ${h.state()}")
            appendLine("Exit code: $exit")
            appendLine("Runtime: ${formatRuntime(h.runtimeMs())}")
            appendLine("Output: ${h.outputBytes()} bytes")
            appendLine("Last 5 lines:")
            append(tail)
        }
        return ToolResult(
            content = content,
            summary = "${h.bgId}: ${h.state()}",
            tokenEstimate = TokenEstimator.estimate(content),
        )
    }

    private fun doOutput(h: BackgroundHandle, params: JsonObject): ToolResult {
        val tailLines = params["tail_lines"]?.jsonPrimitive?.content?.toIntOrNull()
        val sinceOffset = params["since_offset"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val grepPattern = params["grep_pattern"]?.jsonPrimitive?.content

        val chunk = h.readOutput(sinceOffset = sinceOffset, tailLines = tailLines)
        var content = chunk.content
        if (grepPattern != null) {
            val re = runCatching { Regex(grepPattern) }.getOrNull()
                ?: return toolError("INVALID_GREP_PATTERN: $grepPattern")
            content = content.lines().filter { re.containsMatchIn(it) }.joinToString("\n")
        }
        val header = "[bgId=${h.bgId}] offset=${chunk.nextOffset}, bytes=${h.outputBytes()}\n"
        val body = header + content
        return ToolResult(
            content = body,
            summary = "${h.bgId}: ${content.lineSequence().count()} lines",
            tokenEstimate = TokenEstimator.estimate(body),
            isError = false,
        )
    }

    private fun truncate(s: String, max: Int) = if (s.length > max) s.take(max - 1) + "…" else s
    private fun formatRuntime(ms: Long): String {
        val s = ms / 1000
        return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
    }

    private fun toolError(msg: String) = ToolResult(msg, "Error: $msg", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
}
