package com.workflow.orchestrator.sonar.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.core.settings.RepoContextResolver

/**
 * Resolves the repo-relative path SonarQube uses to identify a file, plus
 * the owning Sonar project key. Sonar issues + coverage are addressed by
 * paths relative to the repo root, not the IntelliJ project base — the two
 * diverge in multi-repo projects (see `core/CLAUDE.md` "Repo resolution").
 */
object SonarPathResolver {

    data class Context(val relativePath: String, val sonarProjectKey: String?)

    fun resolveContext(project: Project, virtualFile: VirtualFile): Context {
        val repoConfig = RepoContextResolver.getInstance(project).resolveFromFile(virtualFile)
        val vcsRootPath = repoConfig?.localVcsRootPath?.takeIf { it.isNotBlank() }
        val projectBasePath = project.basePath
        val relative = computeRelativePath(virtualFile.path, vcsRootPath, projectBasePath)
        val sonarProjectKey = repoConfig?.sonarProjectKey?.takeIf { it.isNotBlank() }
        return Context(relative, sonarProjectKey)
    }

    /**
     * Pure path computation. Strips [vcsRootPath] when it's a prefix of
     * [filePath]; otherwise tries [projectBasePath]; otherwise returns
     * [filePath] unchanged. Public for tests; production callers should use
     * [resolveContext].
     */
    fun computeRelativePath(
        filePath: String,
        vcsRootPath: String?,
        projectBasePath: String?
    ): String {
        vcsRootPath?.takeIf { it.isNotBlank() }?.let { strip(filePath, it)?.let { r -> return r } }
        projectBasePath?.takeIf { it.isNotBlank() }?.let { strip(filePath, it)?.let { r -> return r } }
        return filePath
    }

    private fun strip(filePath: String, base: String): String? {
        val normalized = base.removeSuffix("/")
        if (filePath == normalized) return ""
        if (filePath.startsWith("$normalized/")) return filePath.removePrefix("$normalized/")
        return null
    }
}
