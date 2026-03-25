package com.workflow.orchestrator.cody.vcs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.VcsCheckinHandlerFactory
import com.intellij.openapi.vfs.VfsUtilCore
import com.workflow.orchestrator.cody.service.PsiContextEnricher
import com.workflow.orchestrator.core.ai.LlmBrainFactory
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.prompts.CommitMessagePromptBuilder
import com.workflow.orchestrator.core.model.ApiResult
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
     * Generates a commit message using LlmBrain via Sourcegraph API.
     *
     * Builds a prompt with diff, file metadata, and code context, then sends
     * it through LlmBrainFactory for commit message generation.
     */
    suspend fun generateMessage(): String? {
        return try {
            val project = panel.project
            val diff = buildDiffFromChanges()
            if (diff.isBlank()) {
                log.warn("No diff content available for commit message generation")
                return null
            }

            val truncatedDiff = if (diff.length > 8000) {
                diff.take(8000) + "\n... (diff truncated, ${diff.length - 8000} chars omitted)"
            } else diff

            val filesSummary = panel.selectedChanges.mapNotNull { change ->
                val path = (change.afterRevision ?: change.beforeRevision)?.file?.path ?: return@mapNotNull null
                val fileName = path.substringAfterLast('/')
                val changeType = when {
                    change.beforeRevision == null -> "new"
                    change.afterRevision == null -> "deleted"
                    else -> "modified"
                }
                "$fileName ($changeType)"
            }.joinToString(", ")

            val codeContext = buildCodeContext()

            val brain = LlmBrainFactory.create(project)
            val prompt = CommitMessagePromptBuilder.build(
                diff = truncatedDiff,
                filesSummary = filesSummary,
                codeContext = codeContext
            )
            val messages = listOf(ChatMessage(role = "user", content = prompt))
            val result = brain.chat(messages, tools = null)
            when (result) {
                is ApiResult.Success ->
                    result.data.choices.firstOrNull()?.message?.content
                        ?.replace(Regex("^```[a-z]*\\n?"), "")
                        ?.replace(Regex("\\n?```$"), "")
                        ?.trim()
                is ApiResult.Error -> {
                    log.warn("LLM commit message generation failed: ${result.message}")
                    null
                }
            }
        } catch (e: Exception) {
            log.warn("Commit message generation failed: ${e.message}")
            null
        }
    }

    /**
     * Build a unified diff from selected changes by comparing before/after content.
     */
    private fun buildDiffFromChanges(): String {
        return panel.selectedChanges.mapNotNull { change ->
            try {
                val filePath = (change.afterRevision ?: change.beforeRevision)?.file?.path ?: return@mapNotNull null
                val fileName = filePath.substringAfterLast('/')
                val beforeContent = try { change.beforeRevision?.content } catch (_: Exception) { null }
                val afterContent = try { change.afterRevision?.content } catch (_: Exception) { null }

                when {
                    beforeContent == null && afterContent != null -> {
                        // New file
                        val lines = afterContent.lines().mapIndexed { i, line -> "+$line" }
                        "--- /dev/null\n+++ b/$fileName\n@@ -0,0 +1,${lines.size} @@\n${lines.joinToString("\n")}"
                    }
                    beforeContent != null && afterContent == null -> {
                        // Deleted file
                        val lines = beforeContent.lines().mapIndexed { i, line -> "-$line" }
                        "--- a/$fileName\n+++ /dev/null\n@@ -1,${lines.size} +0,0 @@\n${lines.joinToString("\n")}"
                    }
                    beforeContent != null && afterContent != null && beforeContent != afterContent -> {
                        // Modified file — simplified diff showing changed lines
                        val beforeLines = beforeContent.lines()
                        val afterLines = afterContent.lines()
                        buildString {
                            appendLine("--- a/$fileName")
                            appendLine("+++ b/$fileName")
                            // Simple line-by-line comparison
                            val maxLines = maxOf(beforeLines.size, afterLines.size)
                            for (i in 0 until maxLines) {
                                val bLine = beforeLines.getOrNull(i)
                                val aLine = afterLines.getOrNull(i)
                                when {
                                    bLine == aLine -> appendLine(" ${bLine.orEmpty()}")
                                    bLine != null && aLine != null -> {
                                        appendLine("-$bLine")
                                        appendLine("+$aLine")
                                    }
                                    bLine != null -> appendLine("-$bLine")
                                    aLine != null -> appendLine("+$aLine")
                                }
                            }
                        }
                    }
                    else -> null
                }
            } catch (_: Exception) { null }
        }.joinToString("\n")
    }

    /**
     * Build code intelligence context from PSI and Maven project structure.
     */
    private suspend fun buildCodeContext(): String {
        val project = panel.project
        val changedFiles = panel.virtualFiles.toList()

        val psiEnricher = PsiContextEnricher(project)
        val fileContexts = changedFiles.take(5).mapNotNull { file ->
            try {
                val ctx = psiEnricher.enrich(file.path)
                if (ctx.className == null && ctx.classAnnotations.isEmpty()) return@mapNotNull null
                buildString {
                    append(file.name)
                    if (ctx.isTestFile) append(" [TEST]")
                    if (ctx.mavenModule != null) append(" (module: ${ctx.mavenModule})")
                    if (ctx.className != null) append(" — ${ctx.className}")
                    if (ctx.classAnnotations.isNotEmpty()) {
                        append(" @${ctx.classAnnotations.joinToString(", @")}")
                    }
                }
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
            if (modules.isNotEmpty()) {
                appendLine("Maven modules: ${modules.joinToString(", ")}")
            }
            if (fileContexts.isNotEmpty()) {
                fileContexts.forEach { appendLine(it) }
            }
        }.trim()
    }
}
