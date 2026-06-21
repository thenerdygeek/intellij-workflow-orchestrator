package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.monitor.BambooDiff
import com.workflow.orchestrator.agent.monitor.MonitorBridge
import com.workflow.orchestrator.agent.monitor.MonitorHandle
import com.workflow.orchestrator.agent.monitor.MonitorPersistence
import com.workflow.orchestrator.agent.monitor.MonitorPool
import com.workflow.orchestrator.agent.monitor.MonitorSpec
import com.workflow.orchestrator.agent.monitor.MonitorSourceFactory
import com.workflow.orchestrator.agent.monitor.PollingSource
import com.workflow.orchestrator.agent.monitor.PullRequestMonitorSource
import com.workflow.orchestrator.agent.security.DefaultCommandFilter
import com.workflow.orchestrator.agent.security.FilterResult
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.estimateTokens
import com.workflow.orchestrator.agent.tools.integration.ServiceLookup
import com.workflow.orchestrator.agent.tools.integration.sonar.IssueSeverity
import com.workflow.orchestrator.agent.tools.process.ShellResolver
import com.workflow.orchestrator.agent.tools.process.ShellType
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.util.ProjectIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
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
    /**
     * Optional provider for [BitbucketService] — injected as a lambda for testability.
     * Production default uses [ServiceLookup.bitbucket]; tests can pass a fake.
     */
    private val bitbucketProvider: (Project) -> com.workflow.orchestrator.core.services.BitbucketService? =
        { project -> ServiceLookup.bitbucket(project) },
    /**
     * Optional provider for the EventBus [SharedFlow] — injected as a lambda for testability.
     * Production default reads from the project-scoped [EventBus] service; tests can pass a fake.
     */
    private val eventBusProvider: (Project) -> SharedFlow<WorkflowEvent>? =
        { project -> project.service<EventBus>().events },
    /**
     * Optional provider for [JiraService] — injected as a lambda for testability.
     * Production default uses [ServiceLookup.jira]; tests can pass a fake.
     */
    private val jiraProvider: (Project) -> com.workflow.orchestrator.core.services.JiraService? =
        { project -> ServiceLookup.jira(project) },
    /**
     * Optional provider for [SonarService] — injected as a lambda for testability.
     * Production default uses [ServiceLookup.sonar]; tests can pass a fake.
     */
    private val sonarProvider: (Project) -> com.workflow.orchestrator.core.services.SonarService? =
        { project -> ServiceLookup.sonar(project) },
    /**
     * Optional provider for [MonitorPersistence] — injected as a lambda for testability.
     * Production default constructs from [ProjectIdentifier.agentDir]; tests can pass a fake.
     */
    private val monitorPersistenceProvider: (Project) -> MonitorPersistence =
        { project -> MonitorPersistence(ProjectIdentifier.agentDir(project.basePath!!).toPath()) },
) : AgentTool {
    override val name = "monitor"
    override val description =
        "Watch a long-running source and get proactive notifications when matching events occur. " +
        "Sources: shell (run a command, notify on stdout lines matching `filter` regex), " +
        "bamboo (watch a build plan through its full lifecycle — queued/running/finished — at build/stage/job level; " +
        "sees in-flight (queued/running) builds, not just the last finished one, and notifies on each state transition and new build), " +
        "pull_request (poll a Bitbucket PR, notify on state/reviews/comments changes), " +
        "jira_ticket (poll a Jira ticket, notify on status/assignee changes), " +
        "jira_sprint (poll a Jira sprint's issue list, notify on issue status/membership changes), " +
        "sonar_gate (subscribe to Sonar quality gate results for a project key), " +
        "sonar_issues (poll Sonar for open issues, notify on new/resolved issues filtered by min_severity). " +
        "Make the shell filter failure-inclusive (e.g. 'done|ERROR|FAILED') — silence is not success. " +
        "Shell filter matching is CASE-SENSITIVE by default — prefix with '(?i)' for case-insensitive. " +
        "Use action=status with a monitor_id to inspect one monitor's state plus its buffered matched lines. " +
        "Note: a shell monitor auto-removes itself when its process exits, so stopping/inspecting an already-exited " +
        "monitor returns 'No monitor with id …' (expected); its final 'process exited' notification may still " +
        "arrive shortly after due to async delivery."
    override val parameters = FunctionParameters(
        properties = linkedMapOf(
            "action" to ParameterProperty(type = "string",
                description = "start | list | stop | status", enumValues = listOf("start", "list", "stop", "status")),
            "source" to ParameterProperty(type = "string",
                description = "[start] Event source: 'shell', 'bamboo', 'pull_request', 'jira_ticket', 'jira_sprint', 'sonar_gate', or 'sonar_issues'.",
                enumValues = listOf("shell", "bamboo", "pull_request", "jira_ticket", "jira_sprint", "sonar_gate", "sonar_issues")),
            "command" to ParameterProperty(type = "string", description = "[start/shell] Command to run."),
            "filter" to ParameterProperty(type = "string",
                description = "[start/shell] Regex; matching stdout lines notify. Matching is CASE-SENSITIVE by " +
                    "default — prefix with '(?i)' for case-insensitive (e.g. '(?i)error|failed')."),
            "plan_key" to ParameterProperty(type = "string", description = "[start/bamboo] Bamboo plan key (e.g. PROJ-PLAN)."),
            "branch" to ParameterProperty(type = "string", description = "[start] Branch to watch (for sources that support branches, e.g. bamboo, sonar_gate, sonar_issues)."),
            "project_key" to ParameterProperty(type = "string", description = "[start/sonar_gate|sonar_issues] Sonar project key."),
            "level" to ParameterProperty(type = "string",
                description = "[start/bamboo] Granularity: build | stage | job (default: build).",
                enumValues = listOf("build", "stage", "job")),
            "stage_name" to ParameterProperty(type = "string",
                description = "[start/bamboo] Stage name (required when level=stage or level=job)."),
            "job_name" to ParameterProperty(type = "string",
                description = "[start/bamboo] Job name (required when level=job)."),
            "pr_id" to ParameterProperty(type = "string", description = "[start/pull_request] Pull request id (numeric, e.g. '42')."),
            "aspects" to ParameterProperty(type = "string",
                description = "[start/pull_request] Comma list: state,reviews,comments; default all. " +
                    "state=lifecycle transitions (merged/declined/approved); " +
                    "reviews=participant approval-status changes; " +
                    "comments=blocker count + total comment count."),
            "repo_name" to ParameterProperty(type = "string",
                description = "[start/pull_request] Optional repository slug override (null = primary repo from settings)."),
            "ticket_key" to ParameterProperty(type = "string",
                description = "[start/jira_ticket] Jira ticket key, e.g. PROJ-123."),
            "board_id" to ParameterProperty(type = "string",
                description = "[start/jira_sprint] Jira board id (numeric). Provide board_id OR sprint_id (or both)."),
            "sprint_id" to ParameterProperty(type = "string",
                description = "[start/jira_sprint] Jira sprint id (numeric). When given, board_id is ignored and the sprint is polled directly."),
            "min_severity" to ParameterProperty(type = "string",
                description = "[start/sonar_issues] Minimum severity to watch; default: all severities when omitted.",
                enumValues = listOf("INFO", "MINOR", "MAJOR", "CRITICAL", "BLOCKER")),
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
                    monitorPersistenceProvider(project).remove(sessionId, id)
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
                    "pull_request" -> startPullRequest(params, project, sessionId, pool)
                    "jira_ticket" -> startJiraTicket(params, project, sessionId, pool)
                    "jira_sprint" -> startJiraSprint(params, project, sessionId, pool)
                    "sonar_gate" -> startSonarGate(params, project, sessionId, pool)
                    "sonar_issues" -> startSonarIssues(params, project, sessionId, pool)
                    else -> err("source '$source' is not supported (use 'shell', 'bamboo', 'pull_request', 'jira_ticket', 'jira_sprint', 'sonar_gate', or 'sonar_issues').")
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
        val filter  = params["filter"]?.jsonPrimitive?.content
        validateStart("shell", command, filter)?.let { return err(it) }
        // A1: gate the LLM-supplied command through the same hard-block filter run_command uses,
        // BEFORE spawning a process. monitor(source=shell) is NOT in APPROVAL_TOOLS, so this
        // pre-spawn filter is the only safety layer between a prompt-injected command and RCE
        // (it closes the former TODO(Phase 2) gap in ShellCommandSource.start).
        val shellType = runCatching { ShellResolver.resolve(null, project).shellType }
            .getOrDefault(ShellType.BASH)
        (DefaultCommandFilter().check(command!!, shellType) as? FilterResult.Reject)
            ?.let { return err("monitor blocked by command filter — ${it.reason}") }
        val desc = params["description"]?.jsonPrimitive?.content ?: command.take(40)
        val id = "shell-" + java.util.UUID.randomUUID().toString().take(8)
        val specParams = buildMap {
            put("command", command!!)
            put("filter", filter!!)
        }
        val spec = MonitorSpec(id, "shell", desc, specParams)
        val onExit: (Int?) -> Unit = { code ->
            val sev = if (code == 0) com.workflow.orchestrator.agent.monitor.Severity.NOTABLE
                      else com.workflow.orchestrator.agent.monitor.Severity.ALERT
            MonitorBridge.emit(project, sessionId,
                com.workflow.orchestrator.agent.monitor.MonitorEvent(id, sev, "process exited (code=${code ?: "unknown"})"))
            pool.markExited(sessionId, id, code)
            monitorPersistenceProvider(project).remove(sessionId, id)
        }
        val result = MonitorSourceFactory.build(spec, project, cs, bambooProvider, bitbucketProvider, jiraProvider, sonarProvider, eventBusProvider, onShellExit = onExit)
        val src = when (result) {
            is MonitorSourceFactory.BuildResult.Failed -> return err(result.error)
            is MonitorSourceFactory.BuildResult.Built  -> result.source
        }
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
        monitorPersistenceProvider(project).add(sessionId, spec)
        src.start { event -> handle.appendLine(event.formatLine()); MonitorBridge.emit(project, sessionId, event) }
        return ok("Started monitor $id. Matching lines will be delivered as notifications.")
    }

    private suspend fun startBamboo(
        params: JsonObject,
        project: Project,
        sessionId: String,
        pool: MonitorPool,
    ): ToolResult {
        val planKey   = params["plan_key"]?.jsonPrimitive?.content
        val levelStr  = params["level"]?.jsonPrimitive?.content
        val stageName = params["stage_name"]?.jsonPrimitive?.content
        val jobName   = params["job_name"]?.jsonPrimitive?.content
        validateBambooStart(planKey, levelStr, stageName, jobName)?.let { return err(it) }

        val level = when ((levelStr ?: "build").lowercase()) {
            "stage" -> BambooDiff.Level.STAGE
            "job"   -> BambooDiff.Level.JOB
            else    -> BambooDiff.Level.BUILD
        }
        val branch = params["branch"]?.jsonPrimitive?.content
        val id = "bamboo-" + java.util.UUID.randomUUID().toString().take(8)
        val defaultDesc = "bamboo ${planKey!!} ${branch ?: ""} ${level.name.lowercase()}".trim()
        val desc = params["description"]?.jsonPrimitive?.content ?: defaultDesc

        val specParams = buildMap {
            put("plan_key", planKey)
            put("level", (levelStr ?: "build").lowercase())
            if (branch != null) put("branch", branch)
            if (stageName != null) put("stage_name", stageName)
            if (jobName != null) put("job_name", jobName)
        }
        val spec = MonitorSpec(id, "bamboo", desc, specParams)
        val result = MonitorSourceFactory.build(spec, project, cs, bambooProvider, bitbucketProvider, jiraProvider, sonarProvider, eventBusProvider)
        val src = when (result) {
            is MonitorSourceFactory.BuildResult.Failed -> return err(result.error)
            is MonitorSourceFactory.BuildResult.Built  -> result.source
        }
        val handle = MonitorHandle(src, sessionId, System.currentTimeMillis())
        try {
            pool.register(sessionId, handle)
        } catch (e: MonitorPool.MaxConcurrentReached) {
            // Invariant: register BEFORE start; on register failure stop the source so future
            // EventBus/polling sources (Phase 3+) copy the correct cleanup template. Harmless today
            // (no SmartPoller running before start), but prevents a leaked subscription/poller.
            src.stop()
            return err(e.message ?: "Too many monitors")
        }
        project.service<AgentService>().ensureMonitorManager(sessionId)
        monitorPersistenceProvider(project).add(sessionId, spec)
        // P1-8 (W5-C1 review): a source that self-stops on a terminal transition must not leave
        // its handle RUNNING in the pool (untruthful status + a permanently consumed slot of the
        // 5-per-session cap) — mirror the shell path's onExit and mark the handle EXITED.
        // Persistence is deliberately KEPT: a session resume re-arms the watch with a fresh
        // snapshot, and the first-poll rule means it waits for the NEXT build instead of
        // insta-stopping on the already-terminal one.
        (src as? PollingSource<*>)?.onSelfStop = { pool.markExited(sessionId, id, null) }
        src.start { event -> handle.appendLine(event.formatLine()); MonitorBridge.emit(project, sessionId, event) }
        return ok("Started monitor $id (bamboo $planKey level=$level). Notifications delivered on state transitions.")
    }

    private suspend fun startPullRequest(
        params: JsonObject,
        project: Project,
        sessionId: String,
        pool: MonitorPool,
    ): ToolResult {
        val prIdRaw    = params["pr_id"]?.jsonPrimitive?.content
        val aspectsRaw = params["aspects"]?.jsonPrimitive?.content
        validatePullRequestStart(prIdRaw, aspectsRaw)?.let { return err(it) }

        val prId     = prIdRaw!!.toInt()
        val aspects  = PullRequestMonitorSource.parseAspects(aspectsRaw)
        val repoName = params["repo_name"]?.jsonPrimitive?.content

        val id = "pr-" + java.util.UUID.randomUUID().toString().take(8)
        val defaultDesc = "pr #$prId ${aspects.map { it.name.lowercase() }.sorted().joinToString(",")}"
        val desc = params["description"]?.jsonPrimitive?.content ?: defaultDesc

        val specParams = buildMap {
            put("pr_id", prIdRaw)
            if (aspectsRaw != null) put("aspects", aspectsRaw)
            if (repoName != null) put("repo_name", repoName)
        }
        val spec = MonitorSpec(id, "pull_request", desc, specParams)
        val result = MonitorSourceFactory.build(spec, project, cs, bambooProvider, bitbucketProvider, jiraProvider, sonarProvider, eventBusProvider)
        val src = when (result) {
            is MonitorSourceFactory.BuildResult.Failed -> return err(result.error)
            is MonitorSourceFactory.BuildResult.Built  -> result.source
        }
        val handle = MonitorHandle(src, sessionId, System.currentTimeMillis())
        try {
            pool.register(sessionId, handle)
        } catch (e: MonitorPool.MaxConcurrentReached) {
            src.stop()
            return err(e.message ?: "Too many monitors")
        }
        project.service<AgentService>().ensureMonitorManager(sessionId)
        monitorPersistenceProvider(project).add(sessionId, spec)
        src.start { event -> handle.appendLine(event.formatLine()); MonitorBridge.emit(project, sessionId, event) }
        return ok("Started monitor $id (pull_request #$prId aspects=${aspects.map { it.name.lowercase() }.sorted().joinToString(",")}). Notifications delivered on state changes.")
    }

    private suspend fun startJiraTicket(
        params: JsonObject,
        project: Project,
        sessionId: String,
        pool: MonitorPool,
    ): ToolResult {
        val ticketKey = params["ticket_key"]?.jsonPrimitive?.content
        validateJiraTicketStart(ticketKey)?.let { return err(it) }

        val id = "jira-ticket-" + java.util.UUID.randomUUID().toString().take(8)
        val desc = params["description"]?.jsonPrimitive?.content ?: "jira ${ticketKey!!}"

        val specParams = mapOf("ticket_key" to ticketKey!!)
        val spec = MonitorSpec(id, "jira_ticket", desc, specParams)
        val result = MonitorSourceFactory.build(spec, project, cs, bambooProvider, bitbucketProvider, jiraProvider, sonarProvider, eventBusProvider)
        val src = when (result) {
            is MonitorSourceFactory.BuildResult.Failed -> return err(result.error)
            is MonitorSourceFactory.BuildResult.Built  -> result.source
        }
        val handle = MonitorHandle(src, sessionId, System.currentTimeMillis())
        try {
            pool.register(sessionId, handle)
        } catch (e: MonitorPool.MaxConcurrentReached) {
            // Invariant: register BEFORE start; on register failure stop the source so future
            // EventBus/polling sources (Phase 3+) copy the correct cleanup template. Harmless today
            // (no SmartPoller running before start), but prevents a leaked subscription/poller.
            src.stop()
            return err(e.message ?: "Too many monitors")
        }
        project.service<AgentService>().ensureMonitorManager(sessionId)
        monitorPersistenceProvider(project).add(sessionId, spec)
        src.start { event -> handle.appendLine(event.formatLine()); MonitorBridge.emit(project, sessionId, event) }
        return ok("Started monitor $id (jira_ticket $ticketKey). Notifications delivered on status/assignee changes.")
    }

    private suspend fun startJiraSprint(
        params: JsonObject,
        project: Project,
        sessionId: String,
        pool: MonitorPool,
    ): ToolResult {
        val boardIdRaw  = params["board_id"]?.jsonPrimitive?.content
        val sprintIdRaw = params["sprint_id"]?.jsonPrimitive?.content
        validateJiraSprintStart(boardIdRaw, sprintIdRaw)?.let { return err(it) }

        val boardId  = boardIdRaw?.toIntOrNull()
        val sprintId = sprintIdRaw?.toIntOrNull()

        val id = "jira-sprint-" + java.util.UUID.randomUUID().toString().take(8)
        val desc = params["description"]?.jsonPrimitive?.content
            ?: "jira sprint ${sprintId ?: "board:$boardId"}"

        val specParams = buildMap {
            if (boardIdRaw != null) put("board_id", boardIdRaw)
            if (sprintIdRaw != null) put("sprint_id", sprintIdRaw)
        }
        val spec = MonitorSpec(id, "jira_sprint", desc, specParams)
        val result = MonitorSourceFactory.build(spec, project, cs, bambooProvider, bitbucketProvider, jiraProvider, sonarProvider, eventBusProvider)
        val src = when (result) {
            is MonitorSourceFactory.BuildResult.Failed -> return err(result.error)
            is MonitorSourceFactory.BuildResult.Built  -> result.source
        }
        val handle = MonitorHandle(src, sessionId, System.currentTimeMillis())
        try {
            pool.register(sessionId, handle)
        } catch (e: MonitorPool.MaxConcurrentReached) {
            // Invariant: register BEFORE start; on register failure stop the source so future
            // EventBus/polling sources (Phase 3+) copy the correct cleanup template. Harmless today
            // (no SmartPoller running before start), but prevents a leaked subscription/poller.
            src.stop()
            return err(e.message ?: "Too many monitors")
        }
        project.service<AgentService>().ensureMonitorManager(sessionId)
        monitorPersistenceProvider(project).add(sessionId, spec)
        src.start { event -> handle.appendLine(event.formatLine()); MonitorBridge.emit(project, sessionId, event) }
        return ok("Started monitor $id (jira_sprint ${sprintId ?: "board:$boardId"}). Notifications delivered on issue status/membership changes.")
    }

    private suspend fun startSonarGate(
        params: JsonObject,
        project: Project,
        sessionId: String,
        pool: MonitorPool,
    ): ToolResult {
        val projectKey = params["project_key"]?.jsonPrimitive?.content
        validateSonarGateStart(projectKey)?.let { return err(it) }

        val branch = params["branch"]?.jsonPrimitive?.content
        val id = "sonar-gate-" + java.util.UUID.randomUUID().toString().take(8)
        val defaultDesc = "sonar gate ${projectKey!!} ${branch ?: ""}".trim()
        val desc = params["description"]?.jsonPrimitive?.content ?: defaultDesc

        val specParams = buildMap {
            put("project_key", projectKey)
            if (branch != null) put("branch", branch)
        }
        val spec = MonitorSpec(id, "sonar_gate", desc, specParams)
        val result = MonitorSourceFactory.build(spec, project, cs, bambooProvider, bitbucketProvider, jiraProvider, sonarProvider, eventBusProvider)
        val src = when (result) {
            is MonitorSourceFactory.BuildResult.Failed -> return err(result.error)
            is MonitorSourceFactory.BuildResult.Built  -> result.source
        }
        val handle = MonitorHandle(src, sessionId, System.currentTimeMillis())
        try {
            pool.register(sessionId, handle)
        } catch (e: MonitorPool.MaxConcurrentReached) {
            src.stop()
            return err(e.message ?: "Too many monitors")
        }
        project.service<AgentService>().ensureMonitorManager(sessionId)
        monitorPersistenceProvider(project).add(sessionId, spec)
        src.start { event -> handle.appendLine(event.formatLine()); MonitorBridge.emit(project, sessionId, event) }
        return ok("Started monitor $id (sonar_gate $projectKey). Notifications delivered on quality gate changes.")
    }

    private suspend fun startSonarIssues(
        params: JsonObject,
        project: Project,
        sessionId: String,
        pool: MonitorPool,
    ): ToolResult {
        val projectKey  = params["project_key"]?.jsonPrimitive?.content
        val minSeverity = params["min_severity"]?.jsonPrimitive?.content
        validateSonarIssuesStart(projectKey, minSeverity)?.let { return err(it) }

        val branch = params["branch"]?.jsonPrimitive?.content
        val id = "sonar-issues-" + java.util.UUID.randomUUID().toString().take(8)
        val defaultDesc = "sonar issues ${projectKey!!} ${branch ?: ""} ${minSeverity ?: ""}".trim()
        val desc = params["description"]?.jsonPrimitive?.content ?: defaultDesc

        val specParams = buildMap {
            put("project_key", projectKey)
            if (branch != null) put("branch", branch)
            if (minSeverity != null) put("min_severity", minSeverity)
        }
        val spec = MonitorSpec(id, "sonar_issues", desc, specParams)
        val result = MonitorSourceFactory.build(spec, project, cs, bambooProvider, bitbucketProvider, jiraProvider, sonarProvider, eventBusProvider)
        val src = when (result) {
            is MonitorSourceFactory.BuildResult.Failed -> return err(result.error)
            is MonitorSourceFactory.BuildResult.Built  -> result.source
        }
        val handle = MonitorHandle(src, sessionId, System.currentTimeMillis())
        try {
            pool.register(sessionId, handle)
        } catch (e: MonitorPool.MaxConcurrentReached) {
            src.stop()
            return err(e.message ?: "Too many monitors")
        }
        project.service<AgentService>().ensureMonitorManager(sessionId)
        monitorPersistenceProvider(project).add(sessionId, spec)
        src.start { event -> handle.appendLine(event.formatLine()); MonitorBridge.emit(project, sessionId, event) }
        return ok("Started monitor $id (sonar_issues $projectKey${if (minSeverity != null) " min=$minSeverity" else ""}). Notifications delivered on new/resolved issues.")
    }

    private fun ok(text: String) = ToolResult(content = text, summary = text.take(80), tokenEstimate = estimateTokens(text))
    private fun err(text: String) = ToolResult(content = text, summary = text.take(80), tokenEstimate = estimateTokens(text), isError = true)

    companion object {
        private val ALLOWED_SOURCES = setOf("shell", "bamboo", "pull_request", "jira_ticket", "jira_sprint", "sonar_gate", "sonar_issues")

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
            if (s !in ALLOWED_SOURCES) return "source '$s' is not supported (use 'shell', 'bamboo', 'pull_request', 'jira_ticket', 'jira_sprint', 'sonar_gate', or 'sonar_issues')."
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

        /**
         * Pure validation for `action=start source=pull_request`.
         * Returns an error string, or null when valid.
         *
         * Rules:
         * - [prIdRaw] is required and must be a numeric string (parseable as [Int]).
         * - [aspects] (if non-blank) may only contain tokens from {state, reviews, comments}
         *   (case-insensitive, comma-separated, trimmed); any unknown token is an error.
         *
         * **Intentional asymmetry with [PullRequestMonitorSource.parseAspects]:** this function is
         * STRICTER — unknown aspect tokens are rejected (fail-closed at the LLM tool boundary), whereas
         * [PullRequestMonitorSource.parseAspects] silently ignores unknown tokens (post-validation default path).
         */
        fun validatePullRequestStart(prIdRaw: String?, aspects: String?): String? {
            if (prIdRaw.isNullOrBlank()) return "pull_request monitor requires numeric 'pr_id'."
            if (prIdRaw.toIntOrNull() == null) return "pull_request monitor requires numeric 'pr_id' (got '$prIdRaw')."
            if (!aspects.isNullOrBlank()) {
                val validTokens = setOf("state", "reviews", "comments")
                val tokens = aspects.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                val bad = tokens.firstOrNull { it !in validTokens }
                if (bad != null) return "unknown aspect '$bad'; valid values are: state, reviews, comments."
            }
            return null
        }

        /**
         * Pure validation for `action=start source=jira_ticket`.
         * Returns an error string, or null when valid.
         *
         * Rules:
         * - [ticketKey] is required and must be non-blank.
         */
        fun validateJiraTicketStart(ticketKey: String?): String? {
            if (ticketKey.isNullOrBlank()) return "jira_ticket monitor requires 'ticket_key'."
            return null
        }

        /**
         * Pure validation for `action=start source=jira_sprint`.
         * Returns an error string, or null when valid.
         *
         * Rules:
         * - At least one of [boardIdRaw] or [sprintIdRaw] must be present and non-blank.
         * - Each provided value must be parseable as a numeric ([toIntOrNull] != null).
         */
        fun validateJiraSprintStart(boardIdRaw: String?, sprintIdRaw: String?): String? {
            val hasBoardId  = !boardIdRaw.isNullOrBlank()
            val hasSprintId = !sprintIdRaw.isNullOrBlank()
            if (!hasBoardId && !hasSprintId) return "jira_sprint monitor requires at least one of 'board_id' or 'sprint_id'."
            if (hasBoardId  && boardIdRaw!!.toIntOrNull() == null)  return "jira_sprint monitor requires numeric 'board_id' (got '$boardIdRaw')."
            if (hasSprintId && sprintIdRaw!!.toIntOrNull() == null) return "jira_sprint monitor requires numeric 'sprint_id' (got '$sprintIdRaw')."
            return null
        }

        /**
         * Pure validation for `action=start source=sonar_gate`.
         * Returns an error string, or null when valid.
         *
         * Rules:
         * - [projectKey] is required and must be non-blank.
         */
        fun validateSonarGateStart(projectKey: String?): String? {
            if (projectKey.isNullOrBlank()) return "sonar_gate monitor requires 'project_key'."
            return null
        }

        /**
         * Pure validation for `action=start source=sonar_issues`.
         * Returns an error string, or null when valid.
         *
         * Rules:
         * - [projectKey] is required and must be non-blank.
         * - [minSeverity] if present must be a valid [IssueSeverity] level
         *   (INFO | MINOR | MAJOR | CRITICAL | BLOCKER).
         */
        fun validateSonarIssuesStart(projectKey: String?, minSeverity: String?): String? {
            if (projectKey.isNullOrBlank()) return "sonar_issues monitor requires 'project_key'."
            if (!minSeverity.isNullOrBlank() && !IssueSeverity.isValid(minSeverity))
                return "min_severity '$minSeverity' is not valid; use one of: INFO, MINOR, MAJOR, CRITICAL, BLOCKER."
            return null
        }
    }
}
