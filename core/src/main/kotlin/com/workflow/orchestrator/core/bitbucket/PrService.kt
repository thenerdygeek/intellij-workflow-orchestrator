package com.workflow.orchestrator.core.bitbucket

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.core.psi.PsiContextEnricher
import com.workflow.orchestrator.core.settings.PluginSettings

object PrTitleRenderer {
    fun render(format: String, ticketId: String, summary: String, branch: String, maxLength: Int): String {
        val rendered = format
            .replace("{ticketId}", ticketId)
            .replace("{branch}", branch)
            .replace("{summary}", summary)
        return if (rendered.length > maxLength) {
            val withoutSummary = format
                .replace("{ticketId}", ticketId)
                .replace("{branch}", branch)
                .replace("{summary}", "")
            val availableForSummary = maxLength - withoutSummary.length
            if (availableForSummary > 3) {
                format.replace("{ticketId}", ticketId)
                    .replace("{branch}", branch)
                    .replace("{summary}", summary.take(availableForSummary - 3) + "...")
            } else {
                rendered.take(maxLength)
            }
        } else {
            rendered
        }
    }
}

@Service(Service.Level.PROJECT)
class PrService {

    private var project: Project? = null

    constructor(project: Project) {
        this.project = project
    }

    constructor()

    companion object {
        private const val DEFAULT_MAX_TITLE_LENGTH = 120
        private const val DEFAULT_PR_TITLE_FORMAT = "{ticketId}: {summary}"

        // SSH: ssh://git@host(:port)/PROJECT/repo.git
        private val SSH_URL_PATTERN = Regex("""ssh://[^/]+(?::\d+)?/([^/]+)/([^/]+?)(?:\.git)?$""")
        // HTTPS: https://host/scm/PROJECT/repo.git
        private val HTTPS_URL_PATTERN = Regex("""https?://[^/]+/scm/([^/]+)/([^/]+?)(?:\.git)?$""")
        // SCP-style: git@host:PROJECT/repo.git
        private val SCP_URL_PATTERN = Regex("""[^@]+@[^:]+:([^/]+)/([^/]+?)(?:\.git)?$""")

        fun getInstance(project: Project): PrService {
            return project.getService(PrService::class.java)
        }
    }

    fun parseGitRemote(remoteUrl: String): Pair<String, String>? {
        SSH_URL_PATTERN.find(remoteUrl)?.let { return Pair(it.groupValues[1], it.groupValues[2]) }
        HTTPS_URL_PATTERN.find(remoteUrl)?.let { return Pair(it.groupValues[1], it.groupValues[2]) }
        SCP_URL_PATTERN.find(remoteUrl)?.let { return Pair(it.groupValues[1], it.groupValues[2]) }
        return null
    }

    fun buildPrTitle(ticketId: String, ticketSummary: String, branchName: String = ""): String {
        val settings = project?.let { PluginSettings.getInstance(it).state }
        val format = settings?.prTitleFormat?.takeIf { it.isNotBlank() } ?: DEFAULT_PR_TITLE_FORMAT
        val maxLength = settings?.maxPrTitleLength?.takeIf { it > 0 } ?: DEFAULT_MAX_TITLE_LENGTH
        return PrTitleRenderer.render(format, ticketId, ticketSummary, branchName, maxLength)
    }

    fun buildDefaultReviewers(): List<BitbucketReviewer> {
        val raw = project?.let { PluginSettings.getInstance(it).state.prDefaultReviewers } ?: return emptyList()
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { BitbucketReviewer(BitbucketReviewerUser(it)) }
    }

    fun buildFallbackDescription(ticketId: String, ticketSummary: String, branchName: String): String {
        return "$ticketId: $ticketSummary\n\nBranch: $branchName"
    }

    suspend fun buildEnrichedDescription(
        ticketId: String,
        ticketSummary: String,
        branchName: String,
        changedFiles: List<VirtualFile>
    ): String {
        val proj = project ?: return buildFallbackDescription(ticketId, ticketSummary, branchName)
        if (changedFiles.isEmpty()) return buildFallbackDescription(ticketId, ticketSummary, branchName)

        val modules = detectAffectedModules(proj, changedFiles)
        val endpoints = detectControllerEndpoints(proj, changedFiles)

        return buildString {
            append("## $ticketId: $ticketSummary\n\n")
            if (modules.isNotEmpty()) {
                append("**Affected modules:** ${modules.joinToString(", ")}\n\n")
            }
            if (endpoints.isNotEmpty()) {
                append("**Affected controllers:**\n")
                endpoints.forEach { append("- $it\n") }
                append("\n")
            }
            append("**Files changed:** ${changedFiles.size}\n")
            append("**Branch:** $branchName\n")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun detectAffectedModules(proj: Project, changedFiles: List<VirtualFile>): List<String> {
        return try {
            val mavenManagerClass = Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager")
            val getInstanceMethod = mavenManagerClass.getMethod("getInstance", Project::class.java)
            val mavenManager = getInstanceMethod.invoke(null, proj)
            val isMavenized = mavenManagerClass.getMethod("isMavenizedProject").invoke(mavenManager) as Boolean
            if (!isMavenized) return emptyList()
            val projects = mavenManagerClass.getMethod("getProjects").invoke(mavenManager) as List<Any>
            projects.filter { mp ->
                val dirFile = mp.javaClass.getMethod("getDirectoryFile").invoke(mp) as VirtualFile
                changedFiles.any { file ->
                    com.intellij.openapi.vfs.VfsUtilCore.isAncestor(dirFile, file, false)
                }
            }.mapNotNull { mp ->
                val mavenId = mp.javaClass.getMethod("getMavenId").invoke(mp)
                mavenId.javaClass.getMethod("getArtifactId").invoke(mavenId) as? String
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun detectControllerEndpoints(proj: Project, changedFiles: List<VirtualFile>): List<String> {
        val endpoints = mutableListOf<String>()
        try {
            val enricher = PsiContextEnricher(proj)
            for (file in changedFiles) {
                try {
                    val psi = enricher.enrich(file.path)
                    if (psi.classAnnotations.any { it in listOf("RestController", "Controller") }) {
                        endpoints.add("${file.nameWithoutExtension} (${psi.classAnnotations.joinToString(", ") { "@$it" }})")
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return endpoints
    }
}
