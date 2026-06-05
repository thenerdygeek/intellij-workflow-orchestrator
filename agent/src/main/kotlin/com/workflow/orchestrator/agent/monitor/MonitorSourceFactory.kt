package com.workflow.orchestrator.agent.monitor

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.WorkflowEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

/**
 * Shared factory that builds any [MonitorSource] from a [MonitorSpec].
 *
 * Used by both the live [MonitorTool.startXxx] path and the future resume re-arm path
 * so source construction happens in exactly one place.
 *
 * The factory is **pure/headless-testable**: it only constructs objects and resolves
 * providers — no pool registration, no persistence writes, and no AgentService calls.
 *
 * ### Provider conventions
 * Every service provider is `(Project) -> XxxService?`.  A null return means the
 * service is not configured; the factory returns [BuildResult.Failed] with an
 * appropriate message (reusing the same error strings the original `startXxx` methods
 * return, so no behaviour change surfaces to the LLM).
 *
 * ### onShellExit
 * The shell monitor's exit callback is injected separately (it closes over the live
 * [MonitorPool] and [MonitorBridge] context, which would be circular if embedded here).
 * For the live path, [MonitorTool] passes its own lambda; for the re-arm path a
 * compatible lambda can be supplied.  Pass `null` to omit the exit side-effect entirely
 * (only useful in tests that don't exercise the exit path).
 */
object MonitorSourceFactory {

    /** Result type returned by [build]. */
    sealed class BuildResult {
        /** Successfully built source, ready for [MonitorSource.start]. */
        data class Built(val source: MonitorSource) : BuildResult()
        /** Construction failed; surface [error] to the caller as a tool error. */
        data class Failed(val error: String) : BuildResult()
    }

    /**
     * Build a [MonitorSource] from [spec].
     *
     * @param spec               Serializable description of the monitor to construct.
     * @param project            IntelliJ [Project]; passed to source constructors that need it.
     * @param cs                 Lifecycle-bound [CoroutineScope].
     * @param bambooProvider     Resolves [com.workflow.orchestrator.core.services.BambooService].
     * @param bitbucketProvider  Resolves [com.workflow.orchestrator.core.services.BitbucketService].
     * @param jiraProvider       Resolves [com.workflow.orchestrator.core.services.JiraService].
     * @param sonarProvider      Resolves [com.workflow.orchestrator.core.services.SonarService].
     * @param eventBusProvider   Resolves the project-scoped [SharedFlow] of [WorkflowEvent]s.
     * @param onShellExit        Exit callback for `shell` sources (called on natural process exit only).
     *                           May be null when the exit side-effect is not needed.
     */
    fun build(
        spec: MonitorSpec,
        project: Project,
        cs: CoroutineScope,
        bambooProvider: (Project) -> com.workflow.orchestrator.core.services.BambooService?,
        bitbucketProvider: (Project) -> com.workflow.orchestrator.core.services.BitbucketService?,
        jiraProvider: (Project) -> com.workflow.orchestrator.core.services.JiraService?,
        sonarProvider: (Project) -> com.workflow.orchestrator.core.services.SonarService?,
        eventBusProvider: (Project) -> SharedFlow<WorkflowEvent>?,
        onShellExit: ((Int?) -> Unit)? = null,
    ): BuildResult = when (spec.sourceType) {

        "shell" -> buildShell(spec, project, cs, onShellExit)

        "bamboo" -> buildBamboo(spec, project, cs, bambooProvider)

        "pull_request" -> buildPullRequest(spec, project, cs, bitbucketProvider, eventBusProvider)

        "jira_ticket" -> buildJiraTicket(spec, project, cs, jiraProvider)

        "jira_sprint" -> buildJiraSprint(spec, project, cs, jiraProvider)

        "sonar_gate" -> buildSonarGate(spec, project, cs, sonarProvider, eventBusProvider)

        "sonar_issues" -> buildSonarIssues(spec, project, cs, sonarProvider)

        else -> BuildResult.Failed(
            "source '${spec.sourceType}' is not supported " +
                "(use 'shell', 'bamboo', 'pull_request', 'jira_ticket', 'jira_sprint', 'sonar_gate', or 'sonar_issues')."
        )
    }

    // ── per-source builders ────────────────────────────────────────────────────

    private fun buildShell(
        spec: MonitorSpec,
        project: Project,
        cs: CoroutineScope,
        onShellExit: ((Int?) -> Unit)?,
    ): BuildResult {
        val command = spec.params["command"]
        val filter  = spec.params["filter"]
        if (command.isNullOrBlank()) return BuildResult.Failed("shell monitor requires 'command'.")
        if (filter.isNullOrBlank())  return BuildResult.Failed("shell monitor requires 'filter' (a regex).")
        val regex = try { Regex(filter) } catch (e: Exception) {
            return BuildResult.Failed("filter is not a valid regex: ${e.message}")
        }
        val workingDir = project.basePath?.let { java.io.File(it) }
        val src = ShellCommandSource(
            monitorId  = spec.id,
            description = spec.description,
            command     = command,
            filter      = regex,
            workingDir  = workingDir,
            cs          = cs,
            project     = project,
            onExit      = onShellExit ?: {},
        )
        return BuildResult.Built(src)
    }

    private fun buildBamboo(
        spec: MonitorSpec,
        project: Project,
        cs: CoroutineScope,
        bambooProvider: (Project) -> com.workflow.orchestrator.core.services.BambooService?,
    ): BuildResult {
        val bamboo = bambooProvider(project) ?: return BuildResult.Failed("Bamboo is not configured.")
        val planKey   = spec.params["plan_key"]
        if (planKey.isNullOrBlank()) return BuildResult.Failed("bamboo monitor requires 'plan_key'.")
        val levelStr  = spec.params["level"] ?: "build"
        val level = when (levelStr.lowercase()) {
            "stage" -> BambooDiff.Level.STAGE
            "job"   -> BambooDiff.Level.JOB
            else    -> BambooDiff.Level.BUILD
        }
        val branch    = spec.params["branch"]?.takeIf { it.isNotBlank() }
        val stageName = spec.params["stage_name"]?.takeIf { it.isNotBlank() }
        val jobName   = spec.params["job_name"]?.takeIf { it.isNotBlank() }
        val src = BambooMonitorSource(
            monitorId   = spec.id,
            description = spec.description,
            bamboo      = bamboo,
            planKey     = planKey,
            branch      = branch,
            level       = level,
            stageName   = stageName,
            jobName     = jobName,
            cs          = cs,
        )
        return BuildResult.Built(src)
    }

    private fun buildPullRequest(
        spec: MonitorSpec,
        project: Project,
        cs: CoroutineScope,
        bitbucketProvider: (Project) -> com.workflow.orchestrator.core.services.BitbucketService?,
        eventBusProvider: (Project) -> SharedFlow<WorkflowEvent>?,
    ): BuildResult {
        val bitbucket = bitbucketProvider(project) ?: return BuildResult.Failed("Bitbucket is not configured.")
        val flow      = eventBusProvider(project)  ?: return BuildResult.Failed("EventBus is not available.")
        val prIdRaw = spec.params["pr_id"]
        if (prIdRaw.isNullOrBlank()) return BuildResult.Failed("pull_request monitor requires numeric 'pr_id'.")
        val prId = prIdRaw.toIntOrNull()
            ?: return BuildResult.Failed("pull_request monitor requires numeric 'pr_id' (got '$prIdRaw').")
        val aspects  = PullRequestMonitorSource.parseAspects(spec.params["aspects"])
        val repoName = spec.params["repo_name"]?.takeIf { it.isNotBlank() }
        val src = PullRequestMonitorSource(
            monitorId   = spec.id,
            description = spec.description,
            aspects     = aspects,
            bitbucket   = bitbucket,
            flow        = flow,
            prId        = prId,
            repoName    = repoName,
            cs          = cs,
        )
        return BuildResult.Built(src)
    }

    private fun buildJiraTicket(
        spec: MonitorSpec,
        project: Project,
        cs: CoroutineScope,
        jiraProvider: (Project) -> com.workflow.orchestrator.core.services.JiraService?,
    ): BuildResult {
        val jira      = jiraProvider(project) ?: return BuildResult.Failed("Jira is not configured.")
        val ticketKey = spec.params["ticket_key"]
        if (ticketKey.isNullOrBlank()) return BuildResult.Failed("jira_ticket monitor requires 'ticket_key'.")
        val src = JiraTicketMonitorSource(
            monitorId   = spec.id,
            description = spec.description,
            cs          = cs,
            jira        = jira,
            ticketKey   = ticketKey,
        )
        return BuildResult.Built(src)
    }

    private fun buildJiraSprint(
        spec: MonitorSpec,
        project: Project,
        cs: CoroutineScope,
        jiraProvider: (Project) -> com.workflow.orchestrator.core.services.JiraService?,
    ): BuildResult {
        val jira = jiraProvider(project) ?: return BuildResult.Failed("Jira is not configured.")
        val boardId  = spec.params["board_id"]?.toIntOrNull()
        val sprintId = spec.params["sprint_id"]?.toIntOrNull()
        if (boardId == null && sprintId == null) {
            return BuildResult.Failed("jira_sprint monitor requires at least one of 'board_id' or 'sprint_id'.")
        }
        val src = JiraSprintMonitorSource(
            monitorId   = spec.id,
            description = spec.description,
            cs          = cs,
            jira        = jira,
            boardId     = boardId,
            sprintId    = sprintId,
        )
        return BuildResult.Built(src)
    }

    private fun buildSonarGate(
        spec: MonitorSpec,
        project: Project,
        cs: CoroutineScope,
        sonarProvider: (Project) -> com.workflow.orchestrator.core.services.SonarService?,
        eventBusProvider: (Project) -> SharedFlow<WorkflowEvent>?,
    ): BuildResult {
        val sonar      = sonarProvider(project)   ?: return BuildResult.Failed("Sonar is not configured.")
        val flow       = eventBusProvider(project) ?: return BuildResult.Failed("EventBus is not available.")
        val projectKey = spec.params["project_key"]
        if (projectKey.isNullOrBlank()) return BuildResult.Failed("sonar_gate monitor requires 'project_key'.")
        val branch = spec.params["branch"]?.takeIf { it.isNotBlank() }
        val src = SonarGateSource(
            monitorId   = spec.id,
            description = spec.description,
            cs          = cs,
            flow        = flow,
            sonar       = sonar,
            projectKey  = projectKey,
            branch      = branch,
        )
        return BuildResult.Built(src)
    }

    private fun buildSonarIssues(
        spec: MonitorSpec,
        project: Project,
        cs: CoroutineScope,
        sonarProvider: (Project) -> com.workflow.orchestrator.core.services.SonarService?,
    ): BuildResult {
        val sonar      = sonarProvider(project) ?: return BuildResult.Failed("Sonar is not configured.")
        val projectKey = spec.params["project_key"]
        if (projectKey.isNullOrBlank()) return BuildResult.Failed("sonar_issues monitor requires 'project_key'.")
        val branch      = spec.params["branch"]?.takeIf { it.isNotBlank() }
        val minSeverity = spec.params["min_severity"]?.takeIf { it.isNotBlank() }
        val src = SonarIssuesSource(
            monitorId   = spec.id,
            description = spec.description,
            cs          = cs,
            sonar       = sonar,
            projectKey  = projectKey,
            branch      = branch,
            minSeverity = minSeverity,
        )
        return BuildResult.Built(src)
    }
}
