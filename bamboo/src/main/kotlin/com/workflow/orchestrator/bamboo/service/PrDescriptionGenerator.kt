package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.workflow.orchestrator.core.ai.TextGenerationService
import com.workflow.orchestrator.core.ai.prompts.PrDescriptionPromptBuilder
import com.workflow.orchestrator.core.bitbucket.PrService
import com.workflow.orchestrator.core.settings.RepoContextResolver
import com.workflow.orchestrator.core.workflow.TicketContext
import git4idea.commands.Git

/**
 * Generates PR descriptions using AI (with fallback to commit messages).
 * Uses [TextGenerationService] interface from :core for Sourcegraph-backed generation.
 *
 * Three-tier cascade:
 *   1. AI with diff — delegates to [TextGenerationService.generatePrDescription] which uses [PrDescriptionPromptBuilder]
 *   2. AI without diff — builds prompt via [PrDescriptionPromptBuilder] with a "(diff unavailable)" sentinel
 *   3. Commit-message fallback — pure markdown from commits + tickets, no AI
 */
object PrDescriptionGenerator {

    private val log = Logger.getInstance(PrDescriptionGenerator::class.java)

    private const val MAX_CONTEXT_FILES = 20

    /**
     * Generate a PR description using a 3-tier cascade.
     * Tries AI with diff first, then AI without diff, then commit-message fallback.
     *
     * @param tickets Ordered list of [TicketContext]; first element is the primary ticket.
     */
    suspend fun generate(
        project: Project,
        tickets: List<TicketContext>,
        sourceBranch: String,
        targetBranch: String
    ): String {
        val commitMessages = getCommitMessages(project, sourceBranch, targetBranch)
        val changedFilePaths = getChangedFilePaths(project)

        val textGen = TextGenerationService.getInstance()
        if (textGen != null) {
            log.info("[Build:PrDesc] Using AI for PR description generation (chained)")

            // Tier 1: AI with diff
            val diff = getDiffBetweenBranches(project, sourceBranch, targetBranch)
            val truncatedDiff = if (diff != null && diff.length > 10000) {
                diff.take(10000) + "\n... (diff truncated)"
            } else diff

            if (truncatedDiff != null) {
                val chainedResult = try {
                    textGen.generatePrDescription(
                        project = project,
                        diff = truncatedDiff,
                        commitMessages = commitMessages,
                        contextFilePaths = changedFilePaths.take(MAX_CONTEXT_FILES),
                        tickets = tickets,
                        sourceBranch = sourceBranch,
                        targetBranch = targetBranch
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

            // Tier 2: AI without diff — delegate to shared prompt builder to avoid duplicating cap logic
            log.info("[Build:PrDesc] Falling back to single prompt (no diff)")
            val prompt = PrDescriptionPromptBuilder.build(
                diff = "(diff unavailable)",
                commitMessages = commitMessages,
                tickets = tickets,
                sourceBranch = sourceBranch,
                targetBranch = targetBranch
            )
            val aiResult = try {
                textGen.generateText(project, prompt, changedFilePaths.take(MAX_CONTEXT_FILES))
            } catch (e: Exception) {
                log.warn("[Build:PrDesc] Single prompt failed: ${e.message}")
                null
            }
            if (!aiResult.isNullOrBlank()) {
                log.info("[Build:PrDesc] Single prompt generated ${aiResult.length} chars")
                return aiResult
            }
        }

        // Tier 3: commit-message fallback (no AI)
        log.info("[Build:PrDesc] Using fallback description (commit messages)")
        return buildFallbackDescription(tickets, commitMessages, sourceBranch)
    }

    /**
     * Generate a PR title. Tries a pattern-based title via [PrService]; AI title generation
     * is deferred to a later phase.
     *
     * @param primary The primary [TicketContext] for the PR, or null if no ticket is linked.
     */
    suspend fun generateTitle(
        project: Project,
        primary: TicketContext?,
        branchName: String
    ): String {
        val prService = PrService.getInstance(project)
        val ticketId = primary?.key ?: ""
        val summary = primary?.summary ?: branchName.replace("-", " ")
        return prService.buildPrTitle(ticketId, summary, branchName)
    }

    /**
     * Tier 3 fallback: markdown description built from ticket list and commit messages.
     *
     * Layout:
     * ```
     * ## {primary.key}: {primary.summary}
     * {primary.description truncated to 500 chars — only if non-blank}
     *
     * ## Related tickets
     * - {key}: {summary}
     * ...
     *
     * ## Commits
     * - {commit}
     * ...
     *
     * **Branch:** {branch}
     * ```
     *
     * Sections are omitted when empty or not applicable.
     */
    internal fun buildFallbackDescription(
        tickets: List<TicketContext>,
        commits: List<String>,
        branch: String
    ): String = buildString {
        val primary = tickets.firstOrNull()
        val additional = tickets.drop(1)

        if (primary != null) {
            appendLine("## ${primary.key}: ${primary.summary}")
            val desc = primary.description
            if (!desc.isNullOrBlank()) {
                appendLine()
                appendLine(desc.take(500))
            }
            appendLine()
        }

        if (additional.isNotEmpty()) {
            appendLine("## Related tickets")
            additional.forEach { appendLine("- ${it.key}: ${it.summary}") }
            appendLine()
        }

        if (commits.isNotEmpty()) {
            appendLine("## Commits")
            commits.forEach { appendLine("- $it") }
            appendLine()
        }

        append("**Branch:** $branch")
    }

    private fun resolveTargetRepo(project: Project): git4idea.repo.GitRepository? =
        RepoContextResolver.getInstance(project).resolveCurrentEditorRepoOrPrimary()

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
