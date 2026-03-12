package com.workflow.orchestrator.cody.vcs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.VcsCheckinHandlerFactory
import com.intellij.openapi.vfs.VfsUtilCore
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
     * Generates a commit message using Cody with PSI-enriched context.
     * Called from the commit dialog when user clicks "Generate with Cody".
     */
    suspend fun generateMessage(): String? {
        val prompt = buildEnrichedPrompt()
        val diff = getDiff()
        val enrichedPrompt = "$prompt\n```diff\n$diff\n```"
        val changedFiles = panel.virtualFiles.toList()
        return try {
            com.workflow.orchestrator.cody.service.CodyChatService(panel.project)
                .generateCommitMessage(
                    diff = enrichedPrompt,
                    contextFiles = changedFiles.map {
                        com.workflow.orchestrator.cody.protocol.ContextFile.fromPath(it.path)
                    }
                )
        } catch (e: Exception) {
            log.warn("Cody commit message generation failed: ${e.message}")
            null
        }
    }

    private suspend fun getDiff(): String {
        return try {
            val changes = panel.selectedChanges.toList()
            changes.joinToString("\n") { it.toString() }
        } catch (_: Exception) { "" }
    }

    internal suspend fun buildEnrichedPrompt(): String {
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
            append("Generate a concise git commit message for this diff.\n")
            append("Use conventional commits format (feat/fix/refactor/etc).\n")
            append("One line summary, optional body.\n\n")
            if (modules.isNotEmpty()) {
                append("Affected Maven modules: ${modules.joinToString(", ")}\n")
            }
            if (fileContexts.isNotEmpty()) {
                append("Changed classes:\n")
                fileContexts.forEach { append("- $it\n") }
            }
        }
    }
}
