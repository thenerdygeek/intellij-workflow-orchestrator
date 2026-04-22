package com.workflow.orchestrator.pullrequest.service

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.workflow.orchestrator.core.ai.TextGenerationService
import com.workflow.orchestrator.core.ai.prompts.PrDescriptionPromptBuilder
import com.workflow.orchestrator.core.bitbucket.PrService
import com.workflow.orchestrator.core.workflow.TicketContext
import git4idea.commands.Git
import git4idea.repo.GitRepository

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
     * @param repo The [GitRepository] for the selected module. Must be non-null; callers are
     *   responsible for resolving the correct repo before invoking this method.
     * @param tickets Ordered list of [TicketContext]; first element is the primary ticket.
     */
    suspend fun generate(
        project: Project,
        repo: GitRepository,
        tickets: List<TicketContext>,
        sourceBranch: String,
        targetBranch: String
    ): String {
        val commitMessages = getCommitMessages(project, repo, sourceBranch, targetBranch)
        val changedFilePaths = getChangedFilePaths(project)

        val textGen = TextGenerationService.getInstance()
        if (textGen != null) {
            log.info("[Build:PrDesc] Using AI for PR description generation (chained)")

            // Tier 1: AI with diff
            val diff = getDiffBetweenBranches(project, repo, sourceBranch, targetBranch)
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
     * No LLM involved — renders only the data we already have.
     *
     * Section headings mirror the Tier 1/2 LLM prompt structure (from
     * [com.workflow.orchestrator.core.ai.prompts.PrDescriptionPromptBuilder]) so users
     * editing a fallback description see the same shape a successful AI generation
     * would have produced. Sections the fallback cannot populate from available data
     * show italic placeholders — obvious to the author, unambiguous to fill in.
     */
    internal fun buildFallbackDescription(
        tickets: List<TicketContext>,
        commits: List<String>,
        branch: String
    ): String = buildString {
        val primary = tickets.firstOrNull()
        val additional = tickets.drop(1)

        // ## Summary — populate from primary ticket if available, else placeholder.
        appendLine("## Summary")
        if (primary != null) {
            appendLine("${primary.key}: ${primary.summary}")
            val desc = primary.description
            if (!desc.isNullOrBlank()) {
                appendLine()
                appendLine(desc.take(500))
            }
        } else {
            appendLine("_(AI generation unavailable — please summarize what this PR delivers and why.)_")
        }
        appendLine()

        // ## Context — we don't have enough signal without the LLM; leave a placeholder
        // so the author knows the shape a full description would have.
        appendLine("## Context")
        appendLine("_(AI generation unavailable — please fill in the motivation behind this PR: problem being solved, why this approach, relevant prior work.)_")
        appendLine()

        // ## Changes — best-effort: commit subjects as a starting point for the author.
        appendLine("## Changes")
        if (commits.isNotEmpty()) {
            appendLine("_(Derived from commit messages — please rewrite as behavior-level bullets:)_")
            commits.forEach { appendLine("- $it") }
        } else {
            appendLine("_(AI generation unavailable — please list the behavior-level changes.)_")
        }
        appendLine()

        // ## Testing — must be author-supplied.
        appendLine("## Testing")
        appendLine("_(AI generation unavailable — please describe scenarios reviewers should verify.)_")
        appendLine()

        // ## Risks & Rollback — left blank by default; author adds only if applicable.
        appendLine("## Risks & Rollback")
        appendLine("_(Omit this section if none of breaking changes / migrations / feature flags / performance / rollback apply.)_")
        appendLine()

        // ## Feedback Requested — research-backed strongest merge-rate predictor.
        appendLine("## Feedback Requested")
        appendLine("_(Please state what kind of feedback would be most valuable — e.g. correctness of retry logic, API naming, security review.)_")
        appendLine()

        // ## Jira — structured ticket list.
        if (primary != null || additional.isNotEmpty()) {
            appendLine("## Jira")
            if (primary != null) appendLine("- ${primary.key}: ${primary.summary}")
            additional.forEach { appendLine("- ${it.key}: ${it.summary}") }
            appendLine()
        }

        append("**Branch:** $branch")
    }

    private fun getCommitMessages(project: Project, repo: GitRepository, source: String, target: String): List<String> {
        return try {
            ReadAction.compute<List<String>, Throwable> {
                val handler = git4idea.commands.GitLineHandler(project, repo.root, git4idea.commands.GitCommand.LOG)
                handler.addParameters("--oneline", "$target..$source")
                val result = Git.getInstance().runCommand(handler)
                if (result.success()) {
                    result.getOutputOrThrow().lines().filter { it.isNotBlank() }.take(50)
                } else emptyList()
            }
        } catch (e: Exception) {
            log.warn("[Build:PrDesc] Failed to get commit messages: ${e.message}")
            emptyList()
        }
    }

    private fun getDiffBetweenBranches(project: Project, repo: GitRepository, source: String, target: String): String? {
        return try {
            ReadAction.compute<String?, Throwable> {
                val handler = git4idea.commands.GitLineHandler(project, repo.root, git4idea.commands.GitCommand.DIFF)
                handler.addParameters("$target...$source", "--no-color")
                val result = Git.getInstance().runCommand(handler)
                if (result.success() && result.output.isNotEmpty()) {
                    result.output.joinToString("\n")
                } else null
            }
        } catch (e: Exception) {
            log.warn("[Build:PrDesc] Failed to get branch diff: ${e.message}")
            null
        }
    }

    private fun getChangedFilePaths(project: Project): List<String> {
        return try {
            ReadAction.compute<List<String>, Throwable> {
                ChangeListManager.getInstance(project).allChanges
                    .mapNotNull { it.virtualFile?.path }
                    .take(MAX_CONTEXT_FILES)
            }
        } catch (e: Exception) {
            log.warn("[Build:PrDesc] Failed to get changed files: ${e.message}")
            emptyList()
        }
    }
}
