package com.workflow.orchestrator.pullrequest.service

import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.ai.TextGenerationOutcome
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
     * Diff-cap fallback ladder used by [generate] on context-overflow retry. Starts at the
     * default (full Sonnet window), halves on each retry. After the ladder is exhausted the
     * pipeline falls through to Tier 2 (no-diff prompt).
     */
    private val DIFF_CAP_LADDER = intArrayOf(
        PrDescriptionPromptBuilder.DEFAULT_DIFF_CAP,        // 150K
        PrDescriptionPromptBuilder.DEFAULT_DIFF_CAP / 2,    // 75K
        PrDescriptionPromptBuilder.DEFAULT_DIFF_CAP / 4,    // 37.5K
        PrDescriptionPromptBuilder.DEFAULT_DIFF_CAP / 8     // ~18K
    )

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
        targetBranch: String,
        onPartial: (suspend (String) -> Unit)? = null
    ): String {
        val commitMessages = getCommitMessages(project, repo, sourceBranch, targetBranch)
        // Use files actually changed between source and target — NOT ChangeListManager.allChanges,
        // which reflects uncommitted local edits and is empty for a clean working tree.
        val changedFilePaths = getChangedFilePathsBetweenBranches(project, repo, sourceBranch, targetBranch)

        val textGen = TextGenerationService.getInstance()
        if (textGen != null) {
            log.info("[Build:PrDesc] Using AI for PR description generation (chained)")

            // Tier 1: AI with diff. The prompt builder now owns the cap and applies smart
            // selection (component/label-matching files first), so we send the FULL diff
            // and let the builder decide which files survive.
            val diff = getDiffBetweenBranches(project, repo, sourceBranch, targetBranch)
            val diffStat = getDiffStatBetweenBranches(project, repo, sourceBranch, targetBranch)

            if (diff != null) {
                // Tier 1 with retry ladder: send the full diff first; on CONTEXT_LENGTH_EXCEEDED
                // halve the diff cap and retry, until the ladder is exhausted or any non-overflow
                // failure ends the loop. The prompt builder owns the smart-selection step within
                // each cap so component/label-matching files always survive.
                for (cap in DIFF_CAP_LADDER) {
                    val outcome = try {
                        textGen.generatePrDescriptionTyped(
                            project = project,
                            diff = diff,
                            commitMessages = commitMessages,
                            contextFilePaths = changedFilePaths.take(MAX_CONTEXT_FILES),
                            tickets = tickets,
                            sourceBranch = sourceBranch,
                            targetBranch = targetBranch,
                            diffStat = diffStat,
                            diffCap = cap,
                            onPartial = onPartial
                        )
                    } catch (e: Exception) {
                        log.warn("[Build:PrDesc] Chained generation threw at cap=$cap: ${e.message}")
                        TextGenerationOutcome.Other(null, e.message ?: "exception")
                    }
                    when (outcome) {
                        is TextGenerationOutcome.Success -> {
                            log.info("[Build:PrDesc] Chained generation produced ${outcome.text.length} chars at cap=$cap")
                            return outcome.text
                        }
                        TextGenerationOutcome.ContextOverflow -> {
                            log.warn("[Build:PrDesc] Context overflow at cap=$cap; halving and retrying")
                            // continue loop with next (smaller) cap
                        }
                        is TextGenerationOutcome.Other -> {
                            log.warn("[Build:PrDesc] Non-overflow failure at cap=$cap: ${outcome.message}")
                            break // any other failure is not retry-able by shrinking the diff
                        }
                    }
                }
            }

            // Tier 2: AI without diff — delegate to shared prompt builder to avoid duplicating cap logic
            log.info("[Build:PrDesc] Falling back to single prompt (no diff)")
            val prompt = PrDescriptionPromptBuilder.build(
                diff = "(diff unavailable)",
                commitMessages = commitMessages,
                tickets = tickets,
                sourceBranch = sourceBranch,
                targetBranch = targetBranch,
                diffStat = diffStat
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

    private suspend fun getCommitMessages(project: Project, repo: GitRepository, source: String, target: String): List<String> {
        return try {
            readAction {
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

    /**
     * Returns `git diff --stat target...source` so the LLM sees the full file list and
     * insertion/deletion counts even when the body diff is truncated by the prompt builder's
     * smart-selection budget.
     */
    private suspend fun getDiffStatBetweenBranches(project: Project, repo: GitRepository, source: String, target: String): String {
        return try {
            readAction {
                val handler = git4idea.commands.GitLineHandler(project, repo.root, git4idea.commands.GitCommand.DIFF)
                handler.addParameters("--stat", "$target...$source", "--no-color")
                val result = Git.getInstance().runCommand(handler)
                if (result.success() && result.output.isNotEmpty()) {
                    result.output.joinToString("\n")
                } else ""
            }
        } catch (e: Exception) {
            log.warn("[Build:PrDesc] Failed to get diff stat: ${e.message}")
            ""
        }
    }

    private suspend fun getDiffBetweenBranches(project: Project, repo: GitRepository, source: String, target: String): String? {
        return try {
            readAction {
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

    /**
     * Files actually changed between [target] and [source] (`git diff --name-only target...source`),
     * resolved to absolute paths under the repo root. This is the correct list to send as
     * [TextGenerationService] context: it reflects what's in the PR, not what the user
     * happens to have uncommitted on disk. Capped at [MAX_CONTEXT_FILES].
     */
    private suspend fun getChangedFilePathsBetweenBranches(
        project: Project,
        repo: GitRepository,
        source: String,
        target: String
    ): List<String> = try {
        readAction {
            val handler = git4idea.commands.GitLineHandler(project, repo.root, git4idea.commands.GitCommand.DIFF)
            handler.addParameters("--name-only", "$target...$source")
            val result = Git.getInstance().runCommand(handler)
            if (!result.success()) {
                log.warn("[Build:PrDesc] git diff --name-only failed exit=${result.exitCode}")
                emptyList()
            } else {
                val rootPath = repo.root.path
                result.output
                    .filter { it.isNotBlank() }
                    .map { rel -> "$rootPath/$rel" }
                    .take(MAX_CONTEXT_FILES)
            }
        }
    } catch (e: Exception) {
        log.warn("[Build:PrDesc] Failed to compute branch-diff file list: ${e.message}")
        emptyList()
    }
}
