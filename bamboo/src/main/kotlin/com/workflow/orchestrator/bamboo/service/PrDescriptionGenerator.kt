package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.workflow.orchestrator.core.ai.TextGenerationService
import com.workflow.orchestrator.core.bitbucket.PrService
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
     * Generate a PR description. Tries Cody first, falls back to commit messages.
     */
    suspend fun generate(
        project: Project,
        ticketDetails: TicketDetails?,
        sourceBranch: String,
        targetBranch: String
    ): String {
        val commitMessages = getCommitMessages(project, sourceBranch, targetBranch)
        val changedFilePaths = getChangedFilePaths(project)

        // Try Cody
        val textGen = TextGenerationService.getInstance()
        if (textGen != null) {
            log.info("[Build:PrDesc] Using Cody for PR description generation")
            val prompt = buildPrompt(ticketDetails, commitMessages, sourceBranch)
            val codyResult = try {
                textGen.generateText(project, prompt, changedFilePaths.take(MAX_CONTEXT_FILES))
            } catch (e: Exception) {
                log.warn("[Build:PrDesc] Cody generation failed: ${e.message}")
                null
            }
            if (!codyResult.isNullOrBlank()) {
                log.info("[Build:PrDesc] Cody generated ${codyResult.length} chars")
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

    private fun getCommitMessages(project: Project, source: String, target: String): List<String> {
        return try {
            val repos = GitRepositoryManager.getInstance(project).repositories
            val repo = repos.firstOrNull() ?: return emptyList()
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
