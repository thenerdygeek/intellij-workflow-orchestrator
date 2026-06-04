package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.monitor.MonitorBridge
import com.workflow.orchestrator.agent.monitor.MonitorHandle
import com.workflow.orchestrator.agent.monitor.MonitorPool
import com.workflow.orchestrator.agent.monitor.ShellCommandSource
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.estimateTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class MonitorTool(
    private val sessionIdProvider: () -> String?,
    /** Lifecycle-bound scope; AgentService injects its own managed scope when registering this tool (Task 8). */
    private val cs: CoroutineScope,
) : AgentTool {
    override val name = "monitor"
    override val description =
        "Watch a long-running source and get proactive notifications when matching events occur. " +
        "Phase 1: source=shell runs a command and notifies on stdout lines matching `filter` (regex). " +
        "Make the filter failure-inclusive (e.g. 'done|ERROR|FAILED') — silence is not success."
    override val parameters = FunctionParameters(
        properties = linkedMapOf(
            "action" to ParameterProperty(type = "string",
                description = "start | list | stop", enumValues = listOf("start", "list", "stop")),
            "source" to ParameterProperty(type = "string",
                description = "[start] Event source. Phase 1 supports only 'shell'.", enumValues = listOf("shell")),
            "command" to ParameterProperty(type = "string", description = "[start/shell] Command to run."),
            "filter" to ParameterProperty(type = "string", description = "[start/shell] Regex; matching stdout lines notify."),
            "description" to ParameterProperty(type = "string", description = "[start] Short label shown in every notification."),
            "monitor_id" to ParameterProperty(type = "string", description = "[stop] The id returned by start."),
        ),
        required = emptyList(),
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val sessionId = sessionIdProvider() ?: return err("monitor requires an active session")
        val action = params["action"]?.jsonPrimitive?.content ?: "list"
        val pool = MonitorPool.getInstance(project)
        return when (action) {
            "list" -> {
                val items = pool.list(sessionId)
                val text = if (items.isEmpty()) "No active monitors." else
                    items.joinToString("\n") { "${it.bgId} — ${it.label} (${it.state()})" }
                ok(text)
            }
            "stop" -> {
                val id = params["monitor_id"]?.jsonPrimitive?.content ?: return err("stop requires monitor_id")
                if (pool.stop(sessionId, id)) {
                    project.service<AgentService>().forgetMonitor(sessionId, id)
                    ok("Stopped monitor $id")
                } else err("No monitor with id $id")
            }
            "start" -> {
                val source = params["source"]?.jsonPrimitive?.content ?: "shell"
                val command = params["command"]?.jsonPrimitive?.content
                val filter = params["filter"]?.jsonPrimitive?.content
                validateStart(source, command, filter)?.let { return err(it) }
                val desc = params["description"]?.jsonPrimitive?.content ?: command!!.take(40)
                val id = "shell-" + java.util.UUID.randomUUID().toString().take(8)
                val workingDir = project.basePath?.let { java.io.File(it) }
                val onExit: (Int?) -> Unit = { code ->
                    val sev = if (code == 0) com.workflow.orchestrator.agent.monitor.Severity.NOTABLE
                              else com.workflow.orchestrator.agent.monitor.Severity.ALERT
                    MonitorBridge.emit(project, sessionId,
                        com.workflow.orchestrator.agent.monitor.MonitorEvent(id, sev, "process exited (code=${code ?: "unknown"})"))
                    pool.deregister(sessionId, id)
                }
                val src = ShellCommandSource(id, desc, command!!, Regex(filter!!), workingDir, cs, project, onExit)
                val handle = MonitorHandle(src, sessionId, System.currentTimeMillis())
                try {
                    pool.register(sessionId, handle)
                } catch (e: MonitorPool.MaxConcurrentReached) {
                    src.stop()
                    return err(e.message ?: "Too many monitors")
                }
                // Pre-create the manager BEFORE the source emits — the bridge router is get-only
                // (no resurrection of a disposed session), so without this the first events drop.
                project.service<AgentService>().ensureMonitorManager(sessionId)
                // Capture sessionId in the sink so events reach the right MonitorManager.
                src.start { event -> handle.appendLine(event.formatLine()); MonitorBridge.emit(project, sessionId, event) }
                ok("Started monitor $id. Matching lines will be delivered as notifications.")
            }
            else -> err("Unknown action '$action'")
        }
    }

    private fun ok(text: String) = ToolResult(content = text, summary = text.take(80), tokenEstimate = estimateTokens(text))
    private fun err(text: String) = ToolResult(content = text, summary = text.take(80), tokenEstimate = estimateTokens(text), isError = true)

    companion object {
        private val ALLOWED_SOURCES = setOf("shell")

        /** Pure validation for `action=start`. Returns an error string, or null when valid. */
        fun validateStart(source: String?, command: String?, filter: String?): String? {
            val s = source ?: "shell"
            if (s !in ALLOWED_SOURCES) return "source '$s' is not supported in Phase 1 (only 'shell')."
            if (command.isNullOrBlank()) return "shell monitor requires 'command'."
            if (filter.isNullOrBlank()) return "shell monitor requires 'filter' (a regex)."
            return try { Regex(filter); null } catch (e: Exception) { "filter is not a valid regex: ${e.message}" }
        }
    }
}
