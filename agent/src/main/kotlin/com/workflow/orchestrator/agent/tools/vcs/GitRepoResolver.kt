package com.workflow.orchestrator.agent.tools.vcs

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.io.File

/**
 * Resolves the correct [GitRepository] in multi-root projects.
 *
 * Resolution order:
 * 1. Explicit `repo` parameter — matches by absolute path, relative path, or directory name
 * 2. File `path` parameter — finds the repo whose root contains the given path
 * 3. Falls back to the first repository (backward-compatible default)
 *
 * For single-repo projects, all three paths converge on the same result.
 */
object GitRepoResolver {

    /**
     * Resolve the target git repository.
     *
     * @param project    the IntelliJ project
     * @param repo       optional repo root hint — absolute path, relative path, or directory name
     * @param path       optional file/directory path — used to auto-resolve when [repo] is null
     * @return the resolved [GitRepository], or null if no git repos exist
     */
    fun resolve(
        project: Project,
        repo: String? = null,
        path: String? = null
    ): GitRepository? {
        val repoManager = GitRepositoryManager.getInstance(project)
        val repositories = repoManager.repositories

        if (repositories.isEmpty()) return null
        if (repositories.size == 1) return repositories.first()

        // 1. Explicit repo selection
        if (!repo.isNullOrBlank()) {
            resolveByRepoHint(project, repositories, repo)?.let { return it }
        }

        // 2. Auto-resolve from file path
        if (!path.isNullOrBlank()) {
            resolveByFilePath(project, repositories, path)?.let { return it }
        }

        // 3. Fall back to first (backward compatible)
        return repositories.first()
    }

    /**
     * Returns a hint listing available repos — useful for error messages when resolution fails.
     * Returns empty string for single-repo projects.
     */
    fun availableReposHint(project: Project): String {
        val repos = try {
            GitRepositoryManager.getInstance(project).repositories
        } catch (_: Exception) {
            return ""
        }
        if (repos.size <= 1) return ""
        return buildString {
            appendLine("Hint: This project has ${repos.size} git roots. Use the 'repo' parameter to target a specific one:")
            repos.forEach { repo ->
                val branch = repo.currentBranch?.name ?: "DETACHED HEAD"
                val relPath = relativize(project, repo.root.path)
                appendLine("  repo=\"$relPath\"  ($branch)")
            }
        }
    }

    private fun resolveByRepoHint(
        project: Project,
        repositories: List<GitRepository>,
        repoHint: String
    ): GitRepository? {
        val basePath = project.basePath

        // Exact match on absolute path
        repositories.find { it.root.path == repoHint }?.let { return it }

        // Match on relative path resolved from project root
        if (basePath != null) {
            val absoluteFromRelative = File(basePath, repoHint).canonicalPath
            repositories.find { it.root.path == absoluteFromRelative }?.let { return it }
        }

        // Match on directory name (e.g., "backend", "frontend")
        repositories.find {
            it.root.name.equals(repoHint, ignoreCase = true)
        }?.let { return it }

        return null
    }

    private fun resolveByFilePath(
        project: Project,
        repositories: List<GitRepository>,
        filePath: String
    ): GitRepository? {
        val basePath = project.basePath
        val absolutePath = if (File(filePath).isAbsolute) {
            filePath
        } else if (basePath != null) {
            File(basePath, filePath).canonicalPath
        } else {
            return null
        }

        // Find the repo whose root contains this path.
        // If multiple match (nested repos), prefer the longest root (most specific).
        return repositories
            .filter { absolutePath.startsWith(it.root.path) }
            .maxByOrNull { it.root.path.length }
    }

    private fun relativize(project: Project, path: String): String {
        val base = project.basePath ?: return path
        return if (path.startsWith(base)) path.removePrefix("$base/") else path
    }
}
