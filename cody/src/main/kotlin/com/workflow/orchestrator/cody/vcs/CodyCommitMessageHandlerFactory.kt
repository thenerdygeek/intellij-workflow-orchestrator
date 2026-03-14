package com.workflow.orchestrator.cody.vcs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.VcsCheckinHandlerFactory
import com.intellij.openapi.vfs.VfsUtilCore
import com.workflow.orchestrator.cody.protocol.ContextFile
import com.workflow.orchestrator.cody.protocol.Position
import com.workflow.orchestrator.cody.protocol.Range
import com.workflow.orchestrator.cody.service.PsiContextEnricher
import git4idea.GitVcs

class CodyCommitMessageHandlerFactory : VcsCheckinHandlerFactory(GitVcs.getKey()) {

    override fun createVcsHandler(
        panel: CheckinProjectPanel,
        commitContext: CommitContext
    ): CheckinHandler {
        return CodyCommitMessageHandler(panel)
    }
}

class CodyCommitMessageHandler(
    private val panel: CheckinProjectPanel
) : CheckinHandler() {

    private val log = Logger.getInstance(CodyCommitMessageHandler::class.java)

    override fun getBeforeCheckinConfigurationPanel() = null

    /**
     * Generates a commit message using Cody.
     *
     * Strategy: keep the prompt text small (instructions + metadata only) and send
     * the actual changed files as contextItems with line ranges. This uses Cody's
     * higher context-file token budget instead of the input token limit.
     */
    suspend fun generateMessage(): String? {
        return try {
            val prompt = buildPrompt()
            val contextItems = buildContextItems()

            com.workflow.orchestrator.cody.service.CodyChatService(panel.project)
                .generateCommitMessage(prompt, contextItems)
        } catch (e: Exception) {
            log.warn("Cody commit message generation failed: ${e.message}")
            null
        }
    }

    /**
     * Build a lightweight prompt with instructions and metadata only.
     * No diff or file content — those go as contextItems.
     */
    internal suspend fun buildPrompt(): String {
        val project = panel.project
        val changedFiles = panel.virtualFiles.toList()

        val psiEnricher = PsiContextEnricher(project)
        val fileContexts = changedFiles.mapNotNull { file ->
            try {
                val ctx = psiEnricher.enrich(file.path)
                if (ctx.className != null) {
                    "${ctx.className} (${ctx.classAnnotations.joinToString(", ") { "@$it" }})"
                } else null
            } catch (e: Exception) {
                log.debug("Failed to enrich ${file.name}: ${e.message}")
                null
            }
        }

        val modules = try {
            val mavenManager = org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project)
            if (mavenManager.isMavenizedProject) {
                mavenManager.projects
                    .filter { mp -> changedFiles.any { VfsUtilCore.isAncestor(mp.directoryFile, it, false) } }
                    .map { it.mavenId.artifactId }
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        return buildString {
            append("Generate a concise git commit message for the changed files provided as context.\n")
            append("Use conventional commits format (feat/fix/refactor/etc).\n")
            append("One line summary, optional body. No quotes or backticks.\n\n")
            if (modules.isNotEmpty()) {
                append("Affected Maven modules: ${modules.joinToString(", ")}\n")
            }
            if (fileContexts.isNotEmpty()) {
                append("Changed classes:\n")
                fileContexts.forEach { append("- $it\n") }
            }
            append("\nFiles with changes are attached as context. Focus on WHAT changed and WHY.")
        }
    }

    /**
     * Build contextItems from the selected changes.
     * Each changed file is sent with the line range of the modification,
     * so Cody indexes only the relevant portions with its higher token budget.
     */
    internal fun buildContextItems(): List<ContextFile> {
        val changes = panel.selectedChanges.toList()
        return changes.mapNotNull { change -> changeToContextFile(change) }
    }

    /**
     * Convert a VCS Change to a ContextFile with the changed line range.
     * Uses afterRevision (the new state) since that's what we're committing.
     */
    private fun changeToContextFile(change: Change): ContextFile? {
        val afterRevision = change.afterRevision ?: return null
        val filePath = afterRevision.file.path

        // Try to get line-level change info from the content revision
        val content = try {
            afterRevision.content
        } catch (_: Exception) {
            null
        }

        if (content != null) {
            val lineCount = content.count { it == '\n' } + 1

            // If we have the before-revision, compute the changed range
            val beforeContent = try {
                change.beforeRevision?.content
            } catch (_: Exception) {
                null
            }

            val range = if (beforeContent != null) {
                computeChangedRange(beforeContent, content)
            } else {
                // New file — entire file is the change
                Range(
                    start = Position(line = 0, character = 0),
                    end = Position(line = lineCount - 1, character = 0)
                )
            }

            return ContextFile.fromPath(filePath, range)
        }

        // Fallback: no content available, send without range
        return ContextFile.fromPath(filePath)
    }

    /**
     * Compute the line range that contains all changes between before and after content.
     * Returns a Range covering from the first changed line to the last changed line.
     */
    private fun computeChangedRange(before: String, after: String): Range {
        val beforeLines = before.lines()
        val afterLines = after.lines()

        // Find first differing line
        var firstChanged = 0
        val minLen = minOf(beforeLines.size, afterLines.size)
        while (firstChanged < minLen && beforeLines[firstChanged] == afterLines[firstChanged]) {
            firstChanged++
        }

        // Find last differing line (from the end)
        var lastChangedBefore = beforeLines.size - 1
        var lastChangedAfter = afterLines.size - 1
        while (lastChangedBefore > firstChanged && lastChangedAfter > firstChanged &&
            beforeLines[lastChangedBefore] == afterLines[lastChangedAfter]) {
            lastChangedBefore--
            lastChangedAfter--
        }

        // Expand range by a few lines for context (capped at file boundaries)
        val contextPadding = 5
        val startLine = maxOf(0, firstChanged - contextPadding)
        val endLine = minOf(afterLines.size - 1, lastChangedAfter + contextPadding)

        return Range(
            start = Position(line = startLine, character = 0),
            end = Position(line = endLine, character = 0)
        )
    }
}
