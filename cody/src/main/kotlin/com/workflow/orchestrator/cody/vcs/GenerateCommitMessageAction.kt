package com.workflow.orchestrator.cody.vcs

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.ui.CommitMessage
import com.workflow.orchestrator.cody.protocol.ContextFile
import com.workflow.orchestrator.cody.protocol.Range
import com.workflow.orchestrator.cody.protocol.Position
import com.workflow.orchestrator.cody.service.CodyChatService
import com.workflow.orchestrator.cody.service.PsiContextEnricher
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Action that appears in the commit dialog's message toolbar (Vcs.MessageActionGroup).
 * Generates a commit message using Cody AI from the staged changes.
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
            val message = generateMessage(project, e)
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

    private suspend fun generateMessage(project: Project, e: AnActionEvent): String? {
        return try {
            val settings = PluginSettings.getInstance(project)
            val ticketId = settings.state.activeTicketId.orEmpty()

            // Get changed files for context
            val changedFiles = try {
                val changeListManager = com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
                changeListManager.allChanges.mapNotNull { change ->
                    val afterRevision = change.afterRevision ?: return@mapNotNull null
                    val filePath = afterRevision.file.path
                    val content = try { afterRevision.content } catch (_: Exception) { null }

                    if (content != null) {
                        val lineCount = content.count { it == '\n' } + 1
                        ContextFile.fromPath(
                            filePath,
                            Range(Position(0, 0), Position(lineCount - 1, 0))
                        )
                    } else {
                        ContextFile.fromPath(filePath)
                    }
                }.take(20) // Cap at 20 files
            } catch (_: Exception) { emptyList() }

            // Build prompt
            val prompt = buildString {
                appendLine("Generate a concise git commit message for the changed files provided as context.")
                appendLine("Use conventional commits format (feat/fix/refactor/etc).")
                appendLine("One line summary, optional body. No quotes or backticks.")
                if (ticketId.isNotBlank()) {
                    appendLine("Prefix with ticket ID: $ticketId")
                }
                appendLine()
                appendLine("Focus on WHAT changed and WHY, not implementation details.")
            }

            CodyChatService(project).generateCommitMessage(prompt, changedFiles)
        } catch (ex: Exception) {
            log.warn("[Cody:CommitMsg] Generation failed: ${ex.message}")
            null
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null &&
            e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
    }
}
