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

            // Get the actual git diff — this is what Cody needs to understand the changes
            val diff = getGitDiff(project)
            if (diff.isNullOrBlank()) {
                log.warn("[Cody:CommitMsg] No diff found")
                return null
            }

            // Truncate diff if too large (keep it under ~8K chars for prompt)
            val truncatedDiff = if (diff.length > 8000) {
                diff.take(8000) + "\n... (diff truncated, ${diff.length - 8000} chars omitted)"
            } else diff

            // Get changed files as context items for additional file-level understanding
            val contextItems = try {
                val changeListManager = ChangeListManager.getInstance(project)
                changeListManager.allChanges.mapNotNull { change ->
                    val afterRevision = change.afterRevision ?: return@mapNotNull null
                    val filePath = afterRevision.file.path
                    val content = try { afterRevision.content } catch (_: Exception) { null }
                    if (content != null) {
                        val lineCount = content.count { it == '\n' } + 1
                        ContextFile.fromPath(filePath, Range(Position(0, 0), Position(lineCount - 1, 0)))
                    } else {
                        ContextFile.fromPath(filePath)
                    }
                }.take(15)
            } catch (_: Exception) { emptyList() }

            // Build prompt with diff inline
            val prompt = buildString {
                appendLine("Generate a concise git commit message for the following changes.")
                appendLine("Use conventional commits format (feat/fix/refactor/etc).")
                appendLine("One line summary, optional body if needed. No quotes or backticks around the message.")
                if (ticketId.isNotBlank()) {
                    appendLine("Prefix with ticket ID: $ticketId")
                }
                appendLine()
                appendLine("Git diff:")
                appendLine("```")
                appendLine(truncatedDiff)
                appendLine("```")
                appendLine()
                appendLine("Focus on WHAT changed and WHY, not implementation details. Be concise.")
            }

            log.info("[Cody:CommitMsg] Sending prompt with ${truncatedDiff.length} char diff, ${contextItems.size} context items")
            CodyChatService(project).generateCommitMessage(prompt, contextItems)
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

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null &&
            e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
    }
}
