package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.integration.ServiceLookup
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.util.DefaultBranchResolver
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

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val sb = StringBuilder()
        val repos = try {
            GitRepositoryManager.getInstance(project).repositories
        } catch (_: Exception) {
            emptyList()
        }
        val primaryRepo = repos.firstOrNull()
        val currentBranch = primaryRepo?.currentBranch?.name ?: "unknown"

        // ── Local sections (instant) ──
        appendProjectInfo(sb, project, repos, currentBranch)
        appendResolvedDefaultBranch(sb, project, primaryRepo)
        appendRecentCommits(sb, project, primaryRepo, currentBranch)
        appendUncommittedChanges(sb, project)
        appendOpenFiles(sb, project)
        appendServiceConfig(sb, project, repos)
        appendProjectType(sb, project)

        // ── Network sections (stale-while-revalidate) ──
        val settings = try { PluginSettings.getInstance(project) } catch (_: Exception) { null }
        val state = settings?.state

        // Resolve keys for network calls
        val repoConfig = settings?.getPrimaryRepo()
        val sonarKey = repoConfig?.sonarProjectKey ?: state?.sonarProjectKey ?: ""
        val bambooPlanKey = repoConfig?.bambooPlanKey ?: state?.bambooPlanKey ?: ""

        // Fire all network calls in parallel
        val networkSections = coroutineScope {
            val prDeferred = async { fetchCurrentPr(project, currentBranch) }
            val bambooDeferred = async { fetchBambooStatus(project, bambooPlanKey, currentBranch) }
            val sonarDeferred = async { fetchSonarStatus(project, sonarKey, currentBranch) }

            // Wait up to 3s — use cached fallback for any that don't complete in time
            val prResult = withTimeoutOrFallback(prDeferred, "current_pr", project)
            val bambooResult = withTimeoutOrFallback(bambooDeferred, "bamboo_build", project)
            val sonarResult = withTimeoutOrFallback(sonarDeferred, "sonar_quality", project)

            Triple(prResult, bambooResult, sonarResult)
        }

        val (prSection, bambooSection, sonarSection) = networkSections

        // Append PR section and fetch PR commits if we have a PR ID
        if (prSection.isNotBlank()) {
            sb.appendLine()
            sb.append(prSection)

            // Extract PR ID from cached data to fetch commits
            val prId = extractPrId(prSection)
            if (prId != null) {
                val commitsSection = coroutineScope {
                    val d = async { fetchPrCommits(project, prId) }
                    withTimeoutOrFallback(d, "pr_commits", project)
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

        val content = sb.toString().trimEnd()
        return ToolResult(
            content = content,
            summary = "Project context retrieved",
            tokenEstimate = TokenEstimator.estimate(content)
        )
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
            val resolved = kotlinx.coroutines.runBlocking {
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

    private fun appendUncommittedChanges(sb: StringBuilder, project: Project) {
        try {
            val content = ReadAction.compute<String?, Exception> {
                val clm = ChangeListManager.getInstance(project)
                val changes = clm.allChanges
                val untracked = clm.modifiedWithoutEditing

                if (changes.isEmpty() && untracked.isEmpty()) return@compute null

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

    private suspend fun fetchCurrentPr(project: Project, currentBranch: String): String {
        val bitbucket = ServiceLookup.bitbucket(project) ?: return ""
        return try {
            val result = bitbucket.getPullRequestsForBranch(currentBranch)
            if (result.isError || result.data.isEmpty()) return ""
            val pr = result.data.first() // Most recent open PR for this branch
            buildString {
                appendLine("Current PR: #${pr.id} — ${pr.title}")
                appendLine("  State: ${pr.state}")
                appendLine("  Source: ${pr.fromBranch} → Target: ${pr.toBranch}")
                appendLine("  Author: ${pr.authorName ?: "unknown"}")
                appendLine("  Link: ${pr.link}")
            }
        } catch (_: Exception) { "" }
    }

    private suspend fun fetchPrCommits(project: Project, prId: Int): String {
        val bitbucket = ServiceLookup.bitbucket(project) ?: return ""
        return try {
            val result = bitbucket.getPullRequestCommits(prId)
            if (result.isError || result.data.isEmpty()) return ""
            val commits = result.data
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
            val result = bamboo.getLatestBuild(planKey, branch)
            if (result.isError) return ""
            val build = result.data
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
            val qg = result.data
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
