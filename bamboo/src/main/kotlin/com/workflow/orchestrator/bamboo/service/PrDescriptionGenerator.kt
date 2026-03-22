package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.workflow.orchestrator.core.ai.TextGenerationService
import com.workflow.orchestrator.core.bitbucket.PrService
import com.workflow.orchestrator.core.settings.RepoContextResolver
import com.workflow.orchestrator.core.workflow.TicketDetails
import git4idea.commands.Git
import git4idea.repo.GitRepositoryManager

/**
 * Generates PR descriptions using Cody (with fallback to commit messages).
 * Uses [TextGenerationService] interface from :core — no compile-time :cody dependency.
 */
object PrDescriptionGenerator {

    private val log = Logger.getInstance(PrDescriptionGenerator::class.java)

    private const val MAX_CONTEXT_FILES = 20

    /**
     * Generate a PR description using 3-step prompt chain.
     * Tries chained approach first, falls back to single prompt, then commit messages.
     */
    suspend fun generate(
        project: Project,
        ticketDetails: TicketDetails?,
        sourceBranch: String,
        targetBranch: String
    ): String {
        val commitMessages = getCommitMessages(project, sourceBranch, targetBranch)
        val changedFilePaths = getChangedFilePaths(project)

        // Try Cody with 3-step chain
        val textGen = TextGenerationService.getInstance()
        if (textGen != null) {
            log.info("[Build:PrDesc] Using Cody for PR description generation (chained)")

            // Get the actual diff between branches for the chain
            val diff = getDiffBetweenBranches(project, sourceBranch, targetBranch)
            val truncatedDiff = if (diff != null && diff.length > 10000) {
                diff.take(10000) + "\n... (diff truncated)"
            } else diff

            if (truncatedDiff != null) {
                val chainedResult = try {
                    textGen.generatePrDescription(
                        project, truncatedDiff, commitMessages, changedFilePaths.take(MAX_CONTEXT_FILES),
                        ticketDetails?.key ?: "", ticketDetails?.summary ?: "",
                        ticketDetails?.description ?: "", sourceBranch, targetBranch
                    )
                } catch (e: Exception) {
                    log.warn("[Build:PrDesc] Chained generation failed: ${e.message}")
                    null
                }
                if (!chainedResult.isNullOrBlank()) {
                    log.info("[Build:PrDesc] Chained generation produced ${chainedResult.length} chars")
                    return chainedResult
                }
            }

            // Fallback to single prompt
            log.info("[Build:PrDesc] Falling back to single prompt")
            val prompt = buildPrompt(ticketDetails, commitMessages, sourceBranch)
            val codyResult = try {
                textGen.generateText(project, prompt, changedFilePaths.take(MAX_CONTEXT_FILES))
            } catch (e: Exception) {
                log.warn("[Build:PrDesc] Single prompt failed: ${e.message}")
                null
            }
            if (!codyResult.isNullOrBlank()) {
                log.info("[Build:PrDesc] Single prompt generated ${codyResult.length} chars")
                return codyResult
            }
        }

        // Fallback: commit messages
        log.info("[Build:PrDesc] Using fallback description (commit messages)")
        return buildFallbackDescription(ticketDetails, commitMessages, sourceBranch)
    }

    /**
     * Generate a PR title. Tries Cody for a concise title, falls back to pattern.
     */
    suspend fun generateTitle(
        project: Project,
        ticketDetails: TicketDetails?,
        branchName: String
    ): String {
        val prService = PrService.getInstance(project)
        val ticketId = ticketDetails?.key ?: ""
        val summary = ticketDetails?.summary ?: branchName.replace("-", " ")
        return prService.buildPrTitle(ticketId, summary, branchName)
    }

    private fun buildPrompt(
        ticket: TicketDetails?,
        commits: List<String>,
        branch: String
    ): String = buildString {
        appendLine("Generate a pull request description in markdown for a Spring Boot project.")
        appendLine()
        appendLine("Use this structure:")
        appendLine("## Summary")
        appendLine("2-3 sentences: what changed and why.")
        appendLine()
        appendLine("## Changes")
        appendLine("Bullet list of specific changes.")
        appendLine()
        appendLine("## Affected Modules")
        appendLine("List affected Maven modules if detectable from file paths.")
        appendLine()
        appendLine("## Testing")
        appendLine("What tests were added/modified. Use &#10004; checkmarks.")
        appendLine()
        if (ticket != null) {
            appendLine("## Jira")
            appendLine("Link to the ticket.")
            appendLine()
        }
        appendLine("Rules:")
        appendLine("- Be concise and professional")
        appendLine("- Focus on business impact, not just implementation details")
        appendLine("- Highlight breaking changes if any")
        appendLine("- Use `code formatting` for class/method names")
        appendLine("- Output ONLY the markdown, no preamble")
        appendLine()

        if (ticket != null) {
            appendLine("Jira ticket: ${ticket.key} — ${ticket.summary}")
            val desc = ticket.description
            if (!desc.isNullOrBlank()) {
                appendLine("Ticket description: ${desc.take(1000)}")
            }
            appendLine()
        }

        if (commits.isNotEmpty()) {
            appendLine("Commits on this branch:")
            commits.take(30).forEach { appendLine("- $it") }
            appendLine()
        }

        appendLine("Branch: $branch")
    }

    private fun buildFallbackDescription(
        ticket: TicketDetails?,
        commits: List<String>,
        branch: String
    ): String = buildString {
        if (ticket != null) {
            appendLine("## ${ticket.key}: ${ticket.summary}")
            appendLine()
        }

        appendLine("## Commits")
        if (commits.isEmpty()) {
            appendLine("No commits on this branch.")
        } else {
            commits.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("**Branch:** $branch")

        if (ticket != null) {
            appendLine()
            appendLine("## Jira")
            appendLine(ticket.key)
        }
    }

    private fun resolveTargetRepo(project: Project): git4idea.repo.GitRepository? {
        val resolver = RepoContextResolver.getInstance(project)
        val repoConfig = resolver.resolveFromCurrentEditor() ?: resolver.getPrimary()
        val repos = GitRepositoryManager.getInstance(project).repositories
        return if (repoConfig?.localVcsRootPath != null) {
            repos.find { it.root.path == repoConfig.localVcsRootPath }
        } else {
            repos.firstOrNull()
        } ?: repos.firstOrNull()
    }

    private fun getCommitMessages(project: Project, source: String, target: String): List<String> {
        return try {
            val repo = resolveTargetRepo(project) ?: return emptyList()
            val handler = git4idea.commands.GitLineHandler(project, repo.root, git4idea.commands.GitCommand.LOG)
            handler.addParameters("--oneline", "$target..$source")
            val result = Git.getInstance().runCommand(handler)
            if (result.success()) {
                result.getOutputOrThrow().lines().filter { it.isNotBlank() }.take(50)
            } else emptyList()
        } catch (e: Exception) {
            log.warn("[Build:PrDesc] Failed to get commit messages: ${e.message}")
            emptyList()
        }
    }

    private fun getDiffBetweenBranches(project: Project, source: String, target: String): String? {
        return try {
            val repo = resolveTargetRepo(project) ?: return null
            val handler = git4idea.commands.GitLineHandler(project, repo.root, git4idea.commands.GitCommand.DIFF)
            handler.addParameters("$target...$source", "--no-color")
            val result = Git.getInstance().runCommand(handler)
            if (result.success() && result.output.isNotEmpty()) {
                result.output.joinToString("\n")
            } else null
        } catch (e: Exception) {
            log.warn("[Build:PrDesc] Failed to get branch diff: ${e.message}")
            null
        }
    }

    private fun getChangedFilePaths(project: Project): List<String> {
        return try {
            ChangeListManager.getInstance(project).allChanges
                .mapNotNull { it.virtualFile?.path }
                .take(MAX_CONTEXT_FILES)
        } catch (e: Exception) {
            log.warn("[Build:PrDesc] Failed to get changed files: ${e.message}")
            emptyList()
        }
    }
}
