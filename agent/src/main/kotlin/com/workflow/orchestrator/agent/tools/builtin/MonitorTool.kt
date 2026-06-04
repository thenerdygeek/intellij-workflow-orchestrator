package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.monitor.BambooDiff
import com.workflow.orchestrator.agent.monitor.BambooMonitorSource
import com.workflow.orchestrator.agent.monitor.MonitorBridge
import com.workflow.orchestrator.agent.monitor.MonitorHandle
import com.workflow.orchestrator.agent.monitor.MonitorPool
import com.workflow.orchestrator.agent.monitor.ShellCommandSource
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.estimateTokens
import com.workflow.orchestrator.agent.tools.integration.ServiceLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class MonitorTool(
    private val sessionIdProvider: () -> String?,
    /** Lifecycle-bound scope; AgentService injects its own managed scope when registering this tool (Task 8). */
    private val cs: CoroutineScope,
    /**
     * Optional provider for [BambooService] — injected as a lambda for testability.
     * Production default uses [ServiceLookup.bamboo]; tests can pass a fake.
     */
    private val bambooProvider: (Project) -> com.workflow.orchestrator.core.services.BambooService? =
        { project -> ServiceLookup.bamboo(project) },
) : AgentTool {
    override val name = "monitor"
    override val description =
        "Watch a long-running source and get proactive notifications when matching events occur. " +
        "source=shell runs a command and notifies on stdout lines matching `filter` (regex). " +
        "source=bamboo polls a Bamboo build plan and notifies on state transitions (build/stage/job). " +
        "Make the shell filter failure-inclusive (e.g. 'done|ERROR|FAILED') — silence is not success. " +
        "Use action=status with a monitor_id to inspect one monitor's state plus its buffered matched lines. " +
        "Note: a shell monitor auto-removes itself when its process exits, so stopping/inspecting an already-exited " +
        "monitor returns 'No monitor with id …' (expected); its final 'process exited' notification may still " +
        "arrive shortly after due to async delivery."
    override val parameters = FunctionParameters(
        properties = linkedMapOf(
            "action" to ParameterProperty(type = "string",
                description = "start | list | stop | status", enumValues = listOf("start", "list", "stop", "status")),
            "source" to ParameterProperty(type = "string",
                description = "[start] Event source: 'shell' or 'bamboo'.",
                enumValues = listOf("shell", "bamboo")),
            "command" to ParameterProperty(type = "string", description = "[start/shell] Command to run."),
            "filter" to ParameterProperty(type = "string",
                description = "[start/shell] Regex; matching stdout lines notify. Matching is CASE-SENSITIVE by " +
                    "default — prefix with '(?i)' for case-insensitive (e.g. '(?i)error|failed')."),
            "plan_key" to ParameterProperty(type = "string", description = "[start/bamboo] Bamboo plan key (e.g. PROJ-PLAN)."),
            "branch" to ParameterProperty(type = "string", description = "[start/bamboo] Branch name to watch (omit for main plan)."),
            "level" to ParameterProperty(type = "string",
                description = "[start/bamboo] Granularity: build | stage | job (default: build).",
                enumValues = listOf("build", "stage", "job")),
            "stage_name" to ParameterProperty(type = "string",
                description = "[start/bamboo] Stage name (required when level=stage or level=job)."),
            "job_name" to ParameterProperty(type = "string",
                description = "[start/bamboo] Job name (required when level=job)."),
            "description" to ParameterProperty(type = "string", description = "[start] Short label shown in every notification."),
            "monitor_id" to ParameterProperty(type = "string", description = "[stop/status] The id returned by start."),
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
                } else err("No monitor with id $id (it may have already exited and been auto-removed — use action=list to see active monitors)")
            }
            "status" -> {
                val id = params["monitor_id"]?.jsonPrimitive?.content ?: return err("status requires monitor_id")
                val handle = pool.get(sessionId, id)
                    ?: return err("No monitor with id $id (it may have already exited and been auto-removed — use action=list to see active monitors)")
                ok(renderStatus(handle))
            }
            "start" -> {
                val source = params["source"]?.jsonPrimitive?.content ?: "shell"
                when (source) {
                    "shell" -> startShell(params, project, sessionId, pool)
                    "bamboo" -> startBamboo(params, project, sessionId, pool)
                    else -> err("source '$source' is not supported (use 'shell' or 'bamboo').")
                }
            }
            else -> err("Unknown action '$action'")
        }
    }

    private suspend fun startShell(
        params: JsonObject,
        project: Project,
        sessionId: String,
        pool: MonitorPool,
    ): ToolResult {
        val command = params["command"]?.jsonPrimitive?.content
        val filter = params["filter"]?.jsonPrimitive?.content
        validateStart("shell", command, filter)?.let { return err(it) }
        val desc = params["description"]?.jsonPrimitive?.content ?: command!!.take(40)
        val id = "shell-" + java.util.UUID.randomUUID().toString().take(8)
        val workingDir = project.basePath?.let { java.io.File(it) }
        val onExit: (Int?) -> Unit = { code ->
            val sev = if (code == 0) com.workflow.orchestrator.agent.monitor.Severity.NOTABLE
                      else com.workflow.orchestrator.agent.monitor.Severity.ALERT
            MonitorBridge.emit(project, sessionId,
                com.workflow.orchestrator.agent.monitor.MonitorEvent(id, sev, "process exited (code=${code ?: "unknown"})"))
            pool.markExited(sessionId, id, code)
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
        src.start { event -> handle.appendLine(event.formatLine()); MonitorBridge.emit(project, sessionId, event) }
        return ok("Started monitor $id. Matching lines will be delivered as notifications.")
    }

    private suspend fun startBamboo(
        params: JsonObject,
        project: Project,
        sessionId: String,
        pool: MonitorPool,
    ): ToolResult {
        val planKey = params["plan_key"]?.jsonPrimitive?.content
        val levelStr = params["level"]?.jsonPrimitive?.content
        val stageName = params["stage_name"]?.jsonPrimitive?.content
        val jobName = params["job_name"]?.jsonPrimitive?.content
        validateBambooStart(planKey, levelStr, stageName, jobName)?.let { return err(it) }

        val bamboo = bambooProvider(project) ?: return err("Bamboo is not configured.")

        val level = when ((levelStr ?: "build").lowercase()) {
            "stage" -> BambooDiff.Level.STAGE
            "job"   -> BambooDiff.Level.JOB
            else    -> BambooDiff.Level.BUILD
        }
        val branch = params["branch"]?.jsonPrimitive?.content
        val id = "bamboo-" + java.util.UUID.randomUUID().toString().take(8)
        val defaultDesc = "bamboo ${planKey!!} ${branch ?: ""} $level".trim()
        val desc = params["description"]?.jsonPrimitive?.content ?: defaultDesc

        val src = BambooMonitorSource(id, desc, bamboo, planKey, branch, level, stageName, jobName, cs)
        val handle = MonitorHandle(src, sessionId, System.currentTimeMillis())
        try {
            pool.register(sessionId, handle)
        } catch (e: MonitorPool.MaxConcurrentReached) {
            return err(e.message ?: "Too many monitors")
        }
        project.service<AgentService>().ensureMonitorManager(sessionId)
        src.start { event -> handle.appendLine(event.formatLine()); MonitorBridge.emit(project, sessionId, event) }
        return ok("Started monitor $id (bamboo $planKey level=$level). Notifications delivered on state transitions.")
    }

    private fun ok(text: String) = ToolResult(content = text, summary = text.take(80), tokenEstimate = estimateTokens(text))
    private fun err(text: String) = ToolResult(content = text, summary = text.take(80), tokenEstimate = estimateTokens(text), isError = true)

    companion object {
        private val ALLOWED_SOURCES = setOf("shell", "bamboo")

        /**
         * Render a single monitor's status: id, label, current state, and its buffered
         * matched-event lines (the ring buffer — matched stdout lines + the exit line, not
         * the process's full stdout). Pure for unit testing.
         */
        fun renderStatus(handle: MonitorHandle, tailLines: Int = 20): String {
            val out = handle.readOutput(tailLines = tailLines)
            val body = if (out.content.isBlank()) "(no matching events yet)" else out.content
            val note = if (out.truncated) "\n… (showing last $tailLines event lines)" else ""
            val stateStr = if (handle.state() == com.workflow.orchestrator.agent.tools.background.BackgroundState.EXITED)
                "EXITED code=${handle.exitCode()}" else handle.state().name
            return "${handle.bgId} — ${handle.label} ($stateStr)\n$body$note"
        }

        /** Pure validation for `action=start source=shell`. Returns an error string, or null when valid. */
        fun validateStart(source: String?, command: String?, filter: String?): String? {
            val s = source ?: "shell"
            if (s !in ALLOWED_SOURCES) return "source '$s' is not supported (use 'shell' or 'bamboo')."
            if (s != "shell") return null  // non-shell sources have their own validate
            if (command.isNullOrBlank()) return "shell monitor requires 'command'."
            if (filter.isNullOrBlank()) return "shell monitor requires 'filter' (a regex)."
            return try { Regex(filter); null } catch (e: Exception) { "filter is not a valid regex: ${e.message}" }
        }

        /**
         * Pure validation for `action=start source=bamboo`.
         * Returns an error string, or null when valid.
         *
         * Rules:
         * - [planKey] is required.
         * - [level] must be one of build|stage|job (null defaults to build — valid).
         * - level=stage requires [stageName].
         * - level=job requires both [stageName] AND [jobName].
         */
        fun validateBambooStart(planKey: String?, level: String?, stageName: String?, jobName: String?): String? {
            if (planKey.isNullOrBlank()) return "bamboo monitor requires 'plan_key'."
            val lvl = level?.lowercase() ?: "build"
            if (lvl !in setOf("build", "stage", "job")) return "level must be one of build|stage|job, got '$lvl'."
            if (lvl == "stage" && stageName.isNullOrBlank()) return "level=stage requires 'stage_name'."
            if (lvl == "job") {
                if (stageName.isNullOrBlank()) return "level=job requires 'stage_name'."
                if (jobName.isNullOrBlank()) return "level=job requires 'job_name'."
            }
            return null
        }
    }
}
