package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.agent.tools.integration.ServiceLookup
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.util.DefaultBranchResolver
import com.workflow.orchestrator.core.workflow.ChainKeyResolver
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only tool that returns comprehensive project context: git state, service keys,
 * active ticket, open files, build/quality status, current PR, and project type.
 *
 * Network sections (PR, Bamboo, Sonar) use stale-while-revalidate caching:
 * - If cached data exists, return it immediately
 * - Fire async refresh in background
 * - If fresh data arrives within 3s, use it instead
 * - Either way, cache is updated for next call
 */
class ProjectContextTool : AgentTool {

    override val name = "project_context"

    override val description =
        "Get comprehensive project context: git branch, recent commits, uncommitted changes, " +
        "open editor files, configured service keys, active Jira ticket, current PR with commits, " +
        "Bamboo build status, Sonar quality gate, project type (Maven/Gradle, Spring, etc.), " +
        "and resolved default branch. Use this to understand the current project state " +
        "before calling integration tools."

    override val parameters = FunctionParameters(
        properties = emptyMap(),
        required = emptyList()
    )

    override val allowedWorkers = setOf(
        WorkerType.CODER, WorkerType.REVIEWER,
        WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER
    )

    override fun documentation(): ToolDocumentation = toolDoc("project_context") {
        summary {
            technical(
                "Zero-parameter read-only snapshot of the entire project's state: git branch + recent commits + " +
                "uncommitted changes, open editor files, configured service endpoints and keys (Jira/Bamboo/" +
                "Bitbucket/Sonar), active Jira ticket, current PR (with commits), latest Bamboo build, " +
                "Sonar quality gate, and build-system / framework detection (Maven/Gradle, Spring Boot, " +
                "Quarkus, Kotlin, IntelliJ Plugin, …). Network sections use stale-while-revalidate caching " +
                "with a 3-second fresh-data window. Uses `runBlockingCancellable` so cancellation propagates."
            )
            plain(
                "Like asking the IDE 'give me the full briefing on this project' — one call and you learn " +
                "what branch you're on, what's changed, what's open in the editor, which services are wired up, " +
                "whether the latest build passed, and what tech stack the project uses. " +
                "Think of it as the morning stand-up report the agent reads before touching anything."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without `project_context`, the LLM must manually glob build files (`glob_files('**/pom.xml')`, " +
            "`glob_files('**/build.gradle.kts')`), read each one, run `git status` and `git log` via " +
            "`run_command`, inspect PluginSettings via other tools, and separately call Bamboo + Sonar + " +
            "Bitbucket integration tools. That manual route takes 6-12 tool calls, produces inconsistent " +
            "formatting, misses unsaved editor changes and in-memory VCS state, and is fragile if any single " +
            "call times out. `project_context` collapses this to one call with stale-while-revalidate " +
            "fallbacks so partial failures degrade gracefully rather than blocking."
        )
        llmMistake(
            "Expects per-file context (e.g. 'show me the contents of build.gradle') — " +
            "`project_context` returns a project-level summary, not file contents. " +
            "Use `read_file` afterwards to get the raw text of a specific file."
        )
        llmMistake(
            "Calls `project_context` repeatedly inside a tight loop to poll build status, " +
            "triggering repeated network fetches to Bamboo/Sonar. The tool has an in-process " +
            "`sectionCache` keyed by project path, but repeated calls inside the same session still " +
            "consume context tokens. For build polling, use `bamboo_builds` directly."
        )
        llmMistake(
            "Assumes all sections are always populated. If Bamboo/Sonar keys are not configured, " +
            "those sections are omitted entirely — the LLM must not treat an absent section as " +
            "'build passed' or 'quality gate OK'."
        )
        llmMistake(
            "Treats 'Recent Commits (last 10)' as the full commit history. " +
            "The tool only fetches the last 10 commits on the current branch via `git log --oneline -10`. " +
            "For deeper history or cross-branch comparisons, use `run_command git log ...`."
        )
        llmMistake(
            "Uses `project_context` to decide which files to edit without checking whether the reported " +
            "'Open Files' list is what the user currently has open. The list is a VFS snapshot — it " +
            "reflects what was open when the tool was called, which may already be stale by the next iteration."
        )
        llmMistake(
            "Interprets the 'Default Target Branch' field as the currently checked-out branch. " +
            "`Default Target Branch` is the merge target (typically `develop` or `main`) resolved by " +
            "`DefaultBranchResolver` — it is distinct from `Current Branch`. Confusing them causes " +
            "the LLM to target the wrong branch when constructing a PR."
        )
        verdict {
            keep(
                "Acts as a 'mission briefing' that prevents the LLM from making blind assumptions about " +
                "git state, configured integrations, and build status. A single call replaces 6-12 " +
                "fragile one-off tool calls and is the canonical preamble before any integration workflow " +
                "(PR creation, Bamboo trigger, Sonar check, Jira transition). Removing it would force " +
                "every integration workflow to re-invent the same information-gathering sequence — " +
                "inconsistently and with no fallback caching.",
                VerdictSeverity.STRONG,
            )
        }
        related(
            "build", Relationship.COMPLEMENT,
            "Use `build` (Gradle/Maven actions) when you need precise dependency graphs or task lists " +
            "that go beyond the build-system type detection `project_context` provides."
        )
        related(
            "project_structure", Relationship.COMPLEMENT,
            "Use `project_structure` when you need SDK details, content roots, facets, or module " +
            "dependency topology that `project_context` only summarises."
        )
        related(
            "glob_files", Relationship.FALLBACK,
            "Use `glob_files` as a fallback when `project_context` is unavailable (no IDE state) " +
            "or when you need exact paths to build files beyond what the summary exposes."
        )
        related(
            "bamboo_builds", Relationship.COMPLEMENT,
            "Use `bamboo_builds` for detailed per-job build log access, test results, or triggering " +
            "a new build — `project_context` only returns the latest build summary."
        )
        related(
            "sonar", Relationship.COMPLEMENT,
            "Use `sonar` for per-issue listings, coverage details, or branch quality reports — " +
            "`project_context` only reports the overall quality gate pass/fail."
        )
        related(
            "bitbucket_pr", Relationship.COMPLEMENT,
            "Use `bitbucket_pr` for PR mutations (approve, merge, comment) and detailed participant info — " +
            "`project_context` only shows the current open PR's summary and commits."
        )
        downside(
            "Network sections (PR, Bamboo, Sonar) have a hard 3-second timeout per section. " +
            "On a slow network or a large Bamboo queue, these sections may return stale cached data " +
            "or be omitted entirely on the first call (cold cache). The LLM cannot distinguish " +
            "'build is passing' from 'build section timed out and was omitted'."
        )
        downside(
            "Framework detection is heuristic: it scans build file text for string markers " +
            "(`spring-boot`, `io.quarkus`, etc.) and optionally reflects into the Maven plugin. " +
            "A project that puts Spring Boot in a profile or a custom BOM may not be detected. " +
            "The LLM should treat the framework list as a hint, not a guarantee."
        )
        downside(
            "The tool depends on IntelliJ IDE state: `ChangeListManager`, `FileEditorManager`, " +
            "`GitRepositoryManager`, `PluginSettings`. If any service is unavailable (e.g. during " +
            "IDE startup or indexing), that section is silently skipped rather than returning an error. " +
            "The LLM receives a partial snapshot with no indication of which sections were dropped."
        )
        downside(
            "Uncommitted changes are capped at 30 files; untracked files at 15. In a large " +
            "working tree, the LLM may miss modified files beyond those caps. " +
            "The output does include a '... and N more' line, but the LLM cannot see which files were cut."
        )
        downside(
            "Multi-repo output can be verbose. In a project with 4+ repositories, each emitting " +
            "its own git log, PR, Bamboo, and Sonar sections, the tool output can easily exceed " +
            "5K tokens. Context budget should be monitored when `project_context` is called in a " +
            "multi-repo workspace."
        )
        observation(
            "The 3-second network timeout and stale-while-revalidate cache are purely in-process " +
            "(ConcurrentHashMap). The cache is never invalidated between calls — a build that " +
            "completes mid-session will only be visible once its 3-second window succeeds. " +
            "This is intentional for latency, but worth surfacing for debugging unexpected stale data."
        )
        observation(
            "`project_context` is deferred (not in the core tool set sent on every iteration). " +
            "The LLM must call `tool_search` with keyword 'project_context' or 'context' to activate it. " +
            "The system prompt's Capabilities section has a one-liner hint pointing here."
        )
        mergeOpportunity(
            "The Bamboo and Sonar summary sections in `project_context` overlap with the single-section " +
            "outputs of `bamboo_builds(action=build_status)` and `sonar(action=quality_gate)`. " +
            "They are retained here because the colocation of all signals in one call is the primary " +
            "value proposition, but if context-token cost becomes a concern, the network sections " +
            "could be made opt-in via a future `sections` parameter."
        )
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val sb = StringBuilder()
        val gitRepos = try {
            GitRepositoryManager.getInstance(project).repositories
        } catch (_: Exception) {
            emptyList()
        }
        val primaryRepo = gitRepos.firstOrNull()
        val currentBranch = primaryRepo?.currentBranch?.name ?: "unknown"

        // ── Global sections (instant) ──
        appendProjectInfo(sb, project, gitRepos, currentBranch)
        appendResolvedDefaultBranch(sb, project, primaryRepo)
        appendUncommittedChanges(sb, project)
        appendOpenFiles(sb, project)
        appendServiceConfig(sb, project, gitRepos)
        appendProjectType(sb, project)

        // ── Per-repo sections (recent commits + network status) ──
        val settings = try { PluginSettings.getInstance(project) } catch (_: Exception) { null }
        val state = settings?.state
        val repoConfigs = settings?.getRepos()?.filter { it.isConfigured } ?: emptyList()

        if (repoConfigs.size > 1) {
            // Multi-repo: iterate each repo
            for (config in repoConfigs) {
                val gitRepo = gitRepos.find { it.root.path == config.localVcsRootPath } ?: gitRepos.firstOrNull()
                val branch = gitRepo?.currentBranch?.name ?: "unknown"
                val primary = if (config.isPrimary) " [PRIMARY]" else ""
                val sonarKey = config.sonarProjectKey?.takeIf { it.isNotBlank() } ?: ""
                val bambooPlanKey = config.bambooPlanKey?.takeIf { it.isNotBlank() } ?: ""

                sb.appendLine()
                sb.appendLine("── ${config.displayLabel}$primary ──")
                sb.appendLine("  Branch: $branch")

                // Recent commits for this repo
                appendRecentCommits(sb, project, gitRepo, branch)

                // Network: PR, build, quality — fetch details in parallel.
                appendRepoStatus(sb, project, config.displayLabel, branch, bambooPlanKey, sonarKey)
            }
        } else {
            // Single repo: original behavior
            val sonarKey = repoConfigs.firstOrNull()?.sonarProjectKey?.takeIf { it.isNotBlank() }
                ?: state?.sonarProjectKey ?: ""
            val bambooPlanKey = repoConfigs.firstOrNull()?.bambooPlanKey?.takeIf { it.isNotBlank() }
                ?: state?.bambooPlanKey ?: ""
            val repoName = repoConfigs.firstOrNull()?.displayLabel ?: ""

            appendRecentCommits(sb, project, primaryRepo, currentBranch)
            appendRepoStatus(sb, project, repoName, currentBranch, bambooPlanKey, sonarKey)
        }

        val content = sb.toString().trimEnd()
        return ToolResult(
            content = content,
            summary = "Project context retrieved",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    /**
     * Appends PR, build, and quality status for a single repo. Fetches details in parallel.
     *
     * Phase 5 T16 removed the quick build/quality status line that previously read
     * `EventBus.prContextMap`: T12 deleted the only writer for those fields (the dashboard's
     * `fetchAndUpdateStatuses`), and the focused PR snapshot in `WorkflowContextService` only
     * carries keys (`bambooPlanKey`, `sonarProjectKey`), not statuses. The Bamboo and Sonar
     * detail sections fetched below already provide richer, authoritative status.
     */
    private suspend fun appendRepoStatus(
        sb: StringBuilder,
        project: Project,
        repoName: String,
        branch: String,
        bambooPlanKey: String,
        sonarKey: String,
    ) {
        // Fetch detailed sections in parallel
        val networkSections = coroutineScope {
            val cachePrefix = if (repoName.isNotBlank()) "${repoName}:" else ""
            val prDeferred = async { fetchCurrentPr(project, branch, repoName) }
            val bambooDeferred = async { fetchBambooStatus(project, bambooPlanKey, branch) }
            val sonarDeferred = async { fetchSonarStatus(project, sonarKey, branch) }

            val prResult = withTimeoutOrFallback(prDeferred, "${cachePrefix}current_pr", project)
            val bambooResult = withTimeoutOrFallback(bambooDeferred, "${cachePrefix}bamboo_build", project)
            val sonarResult = withTimeoutOrFallback(sonarDeferred, "${cachePrefix}sonar_quality", project)

            Triple(prResult, bambooResult, sonarResult)
        }

        val (prSection, bambooSection, sonarSection) = networkSections

        if (prSection.isNotBlank()) {
            sb.appendLine()
            sb.append(prSection)

            val prId = extractPrId(prSection)
            if (prId != null) {
                val cachePrefix = if (repoName.isNotBlank()) "${repoName}:" else ""
                val commitsSection = coroutineScope {
                    val d = async { fetchPrCommits(project, prId, repoName) }
                    withTimeoutOrFallback(d, "${cachePrefix}pr_commits", project)
                }
                if (commitsSection.isNotBlank()) {
                    sb.appendLine()
                    sb.append(commitsSection)
                }
            }
        }

        if (bambooSection.isNotBlank()) {
            sb.appendLine()
            sb.append(bambooSection)
        }
        if (sonarSection.isNotBlank()) {
            sb.appendLine()
            sb.append(sonarSection)
        }
    }

    // ═══════════════════════════════════════════════════
    //  Local sections (no network, instant)
    // ═══════════════════════════════════════════════════

    private fun appendProjectInfo(
        sb: StringBuilder,
        project: Project,
        repos: List<git4idea.repo.GitRepository>,
        currentBranch: String
    ) {
        sb.appendLine("Project: ${project.name}")
        sb.appendLine("Path: ${project.basePath ?: "unknown"}")

        if (repos.isEmpty()) {
            sb.appendLine("Git: No git repositories detected.")
        } else {
            sb.appendLine("Current Branch: $currentBranch")
            if (repos.size > 1) {
                sb.appendLine("Git Roots (${repos.size}):")
                repos.forEach { repo ->
                    val branch = repo.currentBranch?.name ?: "DETACHED HEAD"
                    sb.appendLine("  ${repo.root.name}: $branch")
                }
            }
        }
    }

    private fun appendResolvedDefaultBranch(
        sb: StringBuilder,
        project: Project,
        primaryRepo: git4idea.repo.GitRepository?
    ) {
        if (primaryRepo == null) return
        try {
            val resolver = DefaultBranchResolver.getInstance(project)
            val resolved = runBlockingCancellable {
                withTimeoutOrNull(2000) { resolver.resolve(primaryRepo) }
            } ?: return
            sb.appendLine("Default Target Branch: $resolved")
        } catch (_: Exception) { /* resolver not available */ }
    }

    private fun appendRecentCommits(
        sb: StringBuilder,
        project: Project,
        primaryRepo: git4idea.repo.GitRepository?,
        currentBranch: String
    ) {
        if (primaryRepo == null) return
        try {
            val handler = GitLineHandler(project, primaryRepo.root, GitCommand.LOG)
            handler.addParameters("--oneline", "-10", currentBranch)
            val result = Git.getInstance().runCommand(handler)
            val output = result.getOutputOrThrow()
            if (output.isNotBlank()) {
                sb.appendLine()
                sb.appendLine("Recent Commits (last 10):")
                output.lines().filter { it.isNotBlank() }.forEach { sb.appendLine("  $it") }
            }
        } catch (_: Exception) { /* git log failed, skip */ }
    }

    private suspend fun appendUncommittedChanges(sb: StringBuilder, project: Project) {
        try {
            val content = readAction {
                val clm = ChangeListManager.getInstance(project)
                val changes = clm.allChanges
                val untracked = clm.modifiedWithoutEditing

                if (changes.isEmpty() && untracked.isEmpty()) return@readAction null

                buildString {
                    if (changes.isNotEmpty()) {
                        appendLine("Uncommitted Changes (${changes.size}):")
                        changes.take(30).forEach { change ->
                            val type = change.type.name.lowercase()
                            val filePath = change.virtualFile?.path
                                ?: change.afterRevision?.file?.path
                                ?: change.beforeRevision?.file?.path
                                ?: "unknown"
                            val rel = relativize(project, filePath)
                            appendLine("  [$type] $rel")
                        }
                        if (changes.size > 30) appendLine("  ... and ${changes.size - 30} more")
                    }
                    if (untracked.isNotEmpty()) {
                        appendLine("Untracked (${untracked.size}):")
                        untracked.take(15).forEach { vf ->
                            appendLine("  ${relativize(project, vf.path)}")
                        }
                        if (untracked.size > 15) appendLine("  ... and ${untracked.size - 15} more")
                    }
                }
            }
            if (content != null) {
                sb.appendLine()
                sb.append(content)
            }
        } catch (_: Exception) { /* CLM not available */ }
    }

    private fun appendOpenFiles(sb: StringBuilder, project: Project) {
        try {
            val fem = FileEditorManager.getInstance(project)
            val openFiles = fem.openFiles
            if (openFiles.isEmpty()) return

            val selectedFile = fem.selectedTextEditor?.virtualFile
            sb.appendLine()
            sb.appendLine("Open Files (${openFiles.size}):")
            openFiles.take(20).forEach { vf ->
                val rel = relativize(project, vf.path)
                val marker = if (vf == selectedFile) " [ACTIVE]" else ""
                sb.appendLine("  $rel$marker")
            }
            if (openFiles.size > 20) sb.appendLine("  ... and ${openFiles.size - 20} more")
        } catch (_: Exception) { /* FEM not available */ }
    }

    private fun appendServiceConfig(
        sb: StringBuilder,
        project: Project,
        repos: List<git4idea.repo.GitRepository>
    ) {
        val settings = try { PluginSettings.getInstance(project) } catch (_: Exception) { null }
        val connections = try { ConnectionSettings.getInstance().state } catch (_: Exception) { null }

        if (settings == null) {
            sb.appendLine()
            sb.appendLine("Settings: could not read plugin settings.")
            return
        }
        val state = settings.state

        // Active ticket
        if (!state.activeTicketId.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine("Active Ticket: ${state.activeTicketId} — ${state.activeTicketSummary ?: ""}")
        }

        // Service endpoints
        sb.appendLine()
        sb.appendLine("Service Endpoints:")
        if (connections != null) {
            appendEndpoint(sb, "Jira", connections.jiraUrl)
            appendEndpoint(sb, "Bamboo", connections.bambooUrl)
            appendEndpoint(sb, "Bitbucket", connections.bitbucketUrl)
            appendEndpoint(sb, "SonarQube", connections.sonarUrl)
            appendEndpoint(sb, "Sourcegraph", connections.sourcegraphUrl)
        } else {
            sb.appendLine("  (could not read connection settings)")
        }

        // Scalar service keys
        val hasScalarKeys = listOf(
            state.sonarProjectKey, state.bambooPlanKey,
            state.bitbucketProjectKey, state.bitbucketRepoSlug
        ).any { !it.isNullOrBlank() }

        if (hasScalarKeys) {
            sb.appendLine()
            sb.appendLine("Configured Keys:")
            appendKey(sb, "Sonar Project Key", state.sonarProjectKey)
            appendKey(sb, "Bamboo Plan Key", state.bambooPlanKey)
            appendKey(sb, "Bitbucket Project", state.bitbucketProjectKey)
            appendKey(sb, "Bitbucket Repo Slug", state.bitbucketRepoSlug)
        }

        // Multi-repo configuration
        val repoConfigs = settings.getRepos()
        if (repoConfigs.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Repositories (${repoConfigs.size}):")
            for (config in repoConfigs) {
                val primary = if (config.isPrimary) " [PRIMARY]" else ""
                sb.appendLine("  ${config.displayLabel}$primary")
                val gitRepo = repos.find { it.root.path == config.localVcsRootPath }
                if (gitRepo != null) {
                    sb.appendLine("    Branch: ${gitRepo.currentBranch?.name ?: "DETACHED HEAD"}")
                }
                appendKey(sb, "    Sonar", config.sonarProjectKey, indent = false)
                appendKey(sb, "    Bamboo", config.bambooPlanKey, indent = false)
                if (!config.bitbucketProjectKey.isNullOrBlank()) {
                    sb.appendLine("    Bitbucket: ${config.bitbucketProjectKey}/${config.bitbucketRepoSlug ?: ""}")
                }
                if (!config.defaultTargetBranch.isNullOrBlank() && config.defaultTargetBranch != "develop") {
                    sb.appendLine("    Target Branch: ${config.defaultTargetBranch}")
                }
            }
        }
    }

    private fun appendProjectType(sb: StringBuilder, project: Project) {
        try {
            val basePath = project.basePath ?: return
            val indicators = mutableListOf<String>()

            // Build system detection via file existence
            val root = java.io.File(basePath)
            val hasPom = root.resolve("pom.xml").exists()
            val hasGradleKts = root.resolve("build.gradle.kts").exists()
            val hasGradle = root.resolve("build.gradle").exists()
            val hasGradleWrapper = root.resolve("gradlew").exists()

            if (hasPom) indicators.add("Maven")
            if (hasGradleKts || hasGradle || hasGradleWrapper) indicators.add("Gradle")

            // Framework detection via Maven/Gradle reflection or file scanning
            try {
                val mavenManagerClass = Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager")
                val manager = mavenManagerClass.getMethod("getInstance", Project::class.java).invoke(null, project)
                val isMavenized = mavenManagerClass.getMethod("isMavenizedProject").invoke(manager) as Boolean
                if (isMavenized) {
                    @Suppress("UNCHECKED_CAST")
                    val projects = mavenManagerClass.getMethod("getProjects").invoke(manager) as List<Any>
                    val allDeps = mutableSetOf<String>()
                    for (mavenProject in projects) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val deps = mavenProject.javaClass.getMethod("getDependencies").invoke(mavenProject) as? List<Any> ?: continue
                            for (dep in deps) {
                                val groupId = dep.javaClass.getMethod("getGroupId").invoke(dep) as? String ?: continue
                                val artifactId = dep.javaClass.getMethod("getArtifactId").invoke(dep) as? String ?: continue
                                allDeps.add("$groupId:$artifactId")
                            }
                        } catch (_: Exception) { /* skip this project */ }
                    }
                    detectFrameworks(allDeps, indicators)
                }
            } catch (_: ClassNotFoundException) { /* Maven plugin not loaded */ }
            catch (_: Exception) { /* reflection failed */ }

            // Fallback: scan build files for common framework markers
            if (indicators.none { it.startsWith("Spring") || it == "Quarkus" || it == "Micronaut" }) {
                val buildFileContent = listOf("pom.xml", "build.gradle.kts", "build.gradle")
                    .mapNotNull { root.resolve(it).takeIf { f -> f.exists() }?.readText(Charsets.UTF_8) }
                    .joinToString("\n")
                if (buildFileContent.contains("spring-boot")) indicators.add("Spring Boot")
                else if (buildFileContent.contains("org.springframework")) indicators.add("Spring")
                if (buildFileContent.contains("io.quarkus")) indicators.add("Quarkus")
                if (buildFileContent.contains("io.micronaut")) indicators.add("Micronaut")
                if (buildFileContent.contains("org.jetbrains.intellij")) indicators.add("IntelliJ Plugin")
                if (buildFileContent.contains("org.jetbrains.kotlin")) indicators.add("Kotlin")
                if (buildFileContent.contains("android")) indicators.add("Android")
            }

            // Module count
            val modules = try { ModuleManager.getInstance(project).modules } catch (_: Exception) { emptyArray() }

            if (indicators.isNotEmpty() || modules.size > 1) {
                sb.appendLine()
                sb.appendLine("Project Type: ${indicators.distinct().joinToString(", ").ifEmpty { "Unknown" }}")
                if (modules.size > 1) {
                    sb.appendLine("Modules (${modules.size}): ${modules.take(15).joinToString(", ") { it.name }}")
                    if (modules.size > 15) sb.append("  ... and ${modules.size - 15} more")
                }
            }
        } catch (_: Exception) { /* detection failed, skip */ }
    }

    private fun detectFrameworks(deps: Set<String>, indicators: MutableList<String>) {
        if (deps.any { it.contains("spring-boot") }) indicators.add("Spring Boot")
        else if (deps.any { it.contains("org.springframework") }) indicators.add("Spring")
        if (deps.any { it.contains("io.quarkus") }) indicators.add("Quarkus")
        if (deps.any { it.contains("io.micronaut") }) indicators.add("Micronaut")
        if (deps.any { it.contains("jakarta.ee") || it.contains("javax.servlet") }) indicators.add("Jakarta EE")
        if (deps.any { it.contains("org.jetbrains.kotlin") }) indicators.add("Kotlin")
        if (deps.any { it.contains("junit") || it.contains("testng") }) indicators.add("JUnit/TestNG")
    }

    // ═══════════════════════════════════════════════════
    //  Network sections (stale-while-revalidate)
    // ═══════════════════════════════════════════════════

    private suspend fun fetchCurrentPr(project: Project, currentBranch: String, repoName: String = ""): String {
        val bitbucket = ServiceLookup.bitbucket(project) ?: return ""
        return try {
            val result = bitbucket.getPullRequestsForBranch(currentBranch, repoName.takeIf { it.isNotBlank() })
            if (result.isError || result.data.isNullOrEmpty()) return ""
            val pr = result.data!!.first() // Most recent open PR for this branch
            buildString {
                appendLine("Current PR: #${pr.id} — ${pr.title}")
                appendLine("  State: ${pr.state}")
                appendLine("  Source: ${pr.fromBranch} → Target: ${pr.toBranch}")
                appendLine("  Author: ${pr.authorName ?: "unknown"}")
                appendLine("  Link: ${pr.link}")
            }
        } catch (_: Exception) { "" }
    }

    private suspend fun fetchPrCommits(project: Project, prId: Int, repoName: String = ""): String {
        val bitbucket = ServiceLookup.bitbucket(project) ?: return ""
        return try {
            val result = bitbucket.getPullRequestCommits(prId, repoName.takeIf { it.isNotBlank() })
            if (result.isError || result.data.isNullOrEmpty()) return ""
            val commits = result.data!!
            buildString {
                appendLine("PR Commits (${commits.size}):")
                commits.take(20).forEach { c ->
                    val msg = c.message.lines().firstOrNull()?.take(80) ?: ""
                    appendLine("  ${c.displayId} ${c.author ?: ""} — $msg")
                }
                if (commits.size > 20) appendLine("  ... and ${commits.size - 20} more")
            }
        } catch (_: Exception) { "" }
    }

    private suspend fun fetchBambooStatus(project: Project, planKey: String, branch: String): String {
        if (planKey.isBlank()) return ""
        val bamboo = ServiceLookup.bamboo(project) ?: return ""
        return try {
            val chainKey = when {
                branch.isBlank() -> planKey
                else -> ChainKeyResolver.getInstance()?.resolveChainKey(project, planKey, branch)
                    ?: return ""
            }
            val result = bamboo.getLatestBuild(chainKey)
            if (result.isError) return ""
            val build = result.data!!
            buildString {
                appendLine("Bamboo Build: ${build.buildResultKey.ifBlank { "${build.planKey}-${build.buildNumber}" }}")
                appendLine("  State: ${build.state}")
                appendLine("  Duration: ${build.durationSeconds}s")
                appendLine("  Tests: ${build.testsPassed} passed, ${build.testsFailed} failed, ${build.testsSkipped} skipped")
                if (build.buildRelativeTime.isNotBlank()) appendLine("  When: ${build.buildRelativeTime}")
            }
        } catch (_: Exception) { "" }
    }

    private suspend fun fetchSonarStatus(project: Project, sonarKey: String, branch: String): String {
        if (sonarKey.isBlank()) return ""
        val sonar = ServiceLookup.sonar(project) ?: return ""
        return try {
            val result = sonar.getQualityGateStatus(sonarKey, branch)
            if (result.isError) return ""
            val qg = result.data!!
            buildString {
                appendLine("Sonar Quality Gate: ${qg.status}")
                if (qg.conditions.isNotEmpty()) {
                    appendLine("  Conditions:")
                    qg.conditions.forEach { c ->
                        appendLine("    ${c.metric}: ${c.value} (${c.status})")
                    }
                }
            }
        } catch (_: Exception) { "" }
    }

    // ═══════════════════════════════════════════════════
    //  Cache + timeout helpers
    // ═══════════════════════════════════════════════════

    private suspend fun withTimeoutOrFallback(
        deferred: Deferred<String>,
        cacheKey: String,
        project: Project
    ): String {
        val projectKey = project.basePath ?: project.name
        val fullKey = "$projectKey::$cacheKey"

        return try {
            val fresh = withTimeout(NETWORK_TIMEOUT_MS) { deferred.await() }
            // Update cache with fresh data
            if (fresh.isNotBlank()) sectionCache[fullKey] = fresh
            fresh
        } catch (_: TimeoutCancellationException) {
            // Return cached data, let the coroutine continue updating cache in background
            val cached = sectionCache[fullKey] ?: ""
            // Fire-and-forget: update cache when deferred completes
            @Suppress("DeferredResultUnused")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = deferred.await()
                    if (result.isNotBlank()) sectionCache[fullKey] = result
                } catch (_: Exception) { /* background update failed, keep stale cache */ }
            }
            cached
        }
    }

    private fun extractPrId(prSection: String): Int? {
        // Parse "Current PR: #123 — ..." format
        val match = Regex("""#(\d+)""").find(prSection)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    // ═══════════════════════════════════════════════════
    //  Utility
    // ═══════════════════════════════════════════════════

    private fun relativize(project: Project, path: String): String {
        val base = project.basePath ?: return path
        return if (path.startsWith(base)) path.removePrefix("$base/") else path
    }

    private fun appendEndpoint(sb: StringBuilder, name: String, url: String?) {
        if (!url.isNullOrBlank()) {
            sb.appendLine("  $name: $url")
        } else {
            sb.appendLine("  $name: not configured")
        }
    }

    private fun appendKey(sb: StringBuilder, label: String, value: String?, indent: Boolean = true) {
        if (!value.isNullOrBlank()) {
            val prefix = if (indent) "  " else ""
            sb.appendLine("$prefix$label: $value")
        }
    }

    companion object {
        private const val NETWORK_TIMEOUT_MS = 3000L

        /**
         * Stale-while-revalidate cache for network sections.
         * Keyed by "projectPath::sectionName". Values are rendered section strings.
         * Shared across all invocations — stale data returned instantly while
         * background refresh updates the cache for the next call.
         */
        private val sectionCache = ConcurrentHashMap<String, String>()
    }
}
