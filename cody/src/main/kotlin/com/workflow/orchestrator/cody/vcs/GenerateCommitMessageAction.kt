package com.workflow.orchestrator.cody.vcs

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.ui.CommitMessage
import com.workflow.orchestrator.cody.protocol.ContextFile
import com.workflow.orchestrator.cody.protocol.Range
import com.workflow.orchestrator.cody.protocol.Position
import com.workflow.orchestrator.cody.service.CodyChatService
import com.workflow.orchestrator.core.settings.PluginSettings
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Action that appears in the commit dialog's message toolbar (Vcs.MessageActionGroup).
 * Generates a commit message using Cody AI from the staged/unstaged changes.
 */
class GenerateCommitMessageAction : AnAction(
    "Generate with Workflow",
    "Generate commit message using Cody AI",
    null
) {

    private val log = Logger.getInstance(GenerateCommitMessageAction::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val commitMessage = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) as? CommitMessage ?: return

        log.info("[Cody:CommitMsg] Generate commit message triggered")
        commitMessage.setCommitMessage("Generating commit message with Cody...")

        scope.launch {
            val message = generateMessage(project)
            invokeLater {
                if (message != null) {
                    commitMessage.setCommitMessage(message)
                    log.info("[Cody:CommitMsg] Commit message generated (${message.length} chars)")
                } else {
                    commitMessage.setCommitMessage("")
                    log.warn("[Cody:CommitMsg] Failed to generate commit message")
                }
            }
        }
    }

    private suspend fun generateMessage(project: Project): String? {
        return try {
            val settings = PluginSettings.getInstance(project)
            val ticketId = settings.state.activeTicketId.orEmpty()

            // Get the actual git diff
            val diff = getGitDiff(project)
            if (diff.isNullOrBlank()) {
                log.warn("[Cody:CommitMsg] No diff found")
                return null
            }

            // Truncate diff if too large (keep under ~8K chars for the analysis prompt)
            val truncatedDiff = if (diff.length > 8000) {
                diff.take(8000) + "\n... (diff truncated, ${diff.length - 8000} chars omitted)"
            } else diff

            // Build context items with changed line ranges for file-level understanding
            val contextItems = try {
                val changeListManager = ChangeListManager.getInstance(project)
                changeListManager.allChanges.mapNotNull { change ->
                    val afterRevision = change.afterRevision ?: return@mapNotNull null
                    val filePath = afterRevision.file.path
                    val afterContent = try { afterRevision.content } catch (_: Exception) { null }
                        ?: return@mapNotNull ContextFile.fromPath(filePath)
                    val beforeContent = try { change.beforeRevision?.content } catch (_: Exception) { null }

                    val range = if (beforeContent != null) {
                        computeChangedRange(beforeContent, afterContent)
                    } else {
                        val lineCount = minOf(afterContent.count { it == '\n' } + 1, 100)
                        Range(Position(0, 0), Position(lineCount - 1, 0))
                    }
                    ContextFile.fromPath(filePath, range)
                }.take(15)
            } catch (_: Exception) { emptyList() }

            // Build a short summary of changed files for the analysis step
            val filesSummary = try {
                val changeListManager = ChangeListManager.getInstance(project)
                changeListManager.allChanges.mapNotNull { change ->
                    val path = (change.afterRevision ?: change.beforeRevision)?.file?.path ?: return@mapNotNull null
                    val fileName = path.substringAfterLast('/')
                    val changeType = when {
                        change.beforeRevision == null -> "new"
                        change.afterRevision == null -> "deleted"
                        else -> "modified"
                    }
                    "$fileName ($changeType)"
                }.joinToString(", ")
            } catch (_: Exception) { "" }

            log.info("[Cody:CommitMsg] Starting chained generation: ${truncatedDiff.length} char diff, ${contextItems.size} context items, files: $filesSummary")

            CodyChatService(project).generateCommitMessageChained(
                diff = truncatedDiff,
                contextItems = contextItems,
                ticketId = ticketId,
                filesSummary = filesSummary
            )
        } catch (ex: Exception) {
            log.warn("[Cody:CommitMsg] Generation failed: ${ex.message}")
            null
        }
    }

    /**
     * Get the git diff for uncommitted changes.
     * Tries staged diff first, falls back to unstaged diff.
     */
    private fun getGitDiff(project: Project): String? {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return null
        val root = repo.root

        // Try staged changes first (what would be committed)
        val stagedHandler = GitLineHandler(project, root, GitCommand.DIFF).apply {
            addParameters("--cached", "--no-color")
        }
        val stagedResult = Git.getInstance().runCommand(stagedHandler)
        if (stagedResult.success() && stagedResult.output.isNotEmpty()) {
            return stagedResult.output.joinToString("\n")
        }

        // Fall back to unstaged changes
        val unstagedHandler = GitLineHandler(project, root, GitCommand.DIFF).apply {
            addParameters("--no-color")
        }
        val unstagedResult = Git.getInstance().runCommand(unstagedHandler)
        if (unstagedResult.success() && unstagedResult.output.isNotEmpty()) {
            return unstagedResult.output.joinToString("\n")
        }

        return null
    }

    /**
     * Compute the line range covering all changes between before and after content.
     * Adds 5-line padding for surrounding context.
     */
    private fun computeChangedRange(before: String, after: String): Range {
        val beforeLines = before.lines()
        val afterLines = after.lines()

        var firstChanged = 0
        val minLen = minOf(beforeLines.size, afterLines.size)
        while (firstChanged < minLen && beforeLines[firstChanged] == afterLines[firstChanged]) {
            firstChanged++
        }

        var lastChangedBefore = beforeLines.size - 1
        var lastChangedAfter = afterLines.size - 1
        while (lastChangedBefore > firstChanged && lastChangedAfter > firstChanged &&
            beforeLines[lastChangedBefore] == afterLines[lastChangedAfter]) {
            lastChangedBefore--
            lastChangedAfter--
        }

        val contextPadding = 5
        val startLine = maxOf(0, firstChanged - contextPadding)
        val endLine = minOf(afterLines.size - 1, lastChangedAfter + contextPadding)

        return Range(
            start = Position(line = startLine, character = 0),
            end = Position(line = endLine, character = 0)
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null &&
            e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
    }
}
