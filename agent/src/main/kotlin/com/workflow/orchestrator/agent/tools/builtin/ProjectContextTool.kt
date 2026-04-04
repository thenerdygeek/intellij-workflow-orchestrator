package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.PluginSettings
import git4idea.repo.GitRepositoryManager
import kotlinx.serialization.json.JsonObject

/**
 * Read-only tool that returns the current project context: git branch, configured
 * service keys (Sonar, Bamboo, Bitbucket), active ticket, and multi-repo mappings.
 *
 * Always active so the agent can discover correct project_key, plan_key, and branch
 * before calling integration tools — the same context the UI tabs have.
 */
class ProjectContextTool : AgentTool {

    override val name = "project_context"

    override val description =
        "Get the current project context: git branch, configured service keys " +
        "(Sonar, Bamboo, Bitbucket), active Jira ticket, and multi-repo mappings. " +
        "Use this to discover the correct project_key, plan_key, repo slug, and branch " +
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

        // --- Git repositories ---
        val repos = try {
            GitRepositoryManager.getInstance(project).repositories
        } catch (_: Exception) {
            emptyList()
        }

        sb.appendLine("Project: ${project.name}")
        sb.appendLine("Path: ${project.basePath ?: "unknown"}")

        if (repos.isEmpty()) {
            sb.appendLine("Git: No git repositories detected.")
        } else {
            val primaryRepo = repos.first()
            val currentBranch = primaryRepo.currentBranch?.name ?: "DETACHED HEAD"
            sb.appendLine("Current Branch: $currentBranch")
            if (repos.size > 1) {
                sb.appendLine("Git Roots (${repos.size}):")
                repos.forEach { repo ->
                    val branch = repo.currentBranch?.name ?: "DETACHED HEAD"
                    sb.appendLine("  ${repo.root.name}: $branch")
                }
            }
        }

        // --- Configured service keys (scalar / legacy) ---
        val settings = try {
            PluginSettings.getInstance(project)
        } catch (_: Exception) {
            null
        }

        val connections = try {
            ConnectionSettings.getInstance().state
        } catch (_: Exception) {
            null
        }

        if (settings != null) {
            val state = settings.state

            // Active ticket
            if (!state.activeTicketId.isNullOrBlank()) {
                sb.appendLine()
                sb.appendLine("Active Ticket: ${state.activeTicketId} — ${state.activeTicketSummary ?: ""}")
            }

            // Configured service endpoints
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

            // Scalar service keys (used when no multi-repo or as fallback)
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

            // Default target branch
            if (!state.defaultTargetBranch.isNullOrBlank()) {
                sb.appendLine()
                sb.appendLine("Default Target Branch: ${state.defaultTargetBranch}")
            }

            // --- Multi-repo configuration ---
            val repoConfigs = settings.getRepos()
            if (repoConfigs.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Repositories (${repoConfigs.size}):")
                for (config in repoConfigs) {
                    val primary = if (config.isPrimary) " [PRIMARY]" else ""
                    val label = config.displayLabel
                    sb.appendLine("  $label$primary")

                    // Match git repo to get branch
                    val gitRepo = repos.find { it.root.path == config.localVcsRootPath }
                    if (gitRepo != null) {
                        val branch = gitRepo.currentBranch?.name ?: "DETACHED HEAD"
                        sb.appendLine("    Branch: $branch")
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
        } else {
            sb.appendLine()
            sb.appendLine("Settings: could not read plugin settings.")
        }

        val content = sb.toString().trimEnd()
        return ToolResult(
            content = content,
            summary = "Project context retrieved",
            tokenEstimate = TokenEstimator.estimate(content)
        )
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
}
