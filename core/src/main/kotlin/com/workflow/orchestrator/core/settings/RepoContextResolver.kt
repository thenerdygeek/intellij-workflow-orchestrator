package com.workflow.orchestrator.core.settings

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

@Service(Service.Level.PROJECT)
class RepoContextResolver(private val project: Project) {

    fun resolveFromFile(file: VirtualFile): RepoConfig? {
        // Use cached repositories list instead of getRepositoryForFile() which triggers
        // a synchronous repository update that is forbidden on EDT (IntelliJ 2025.1+)
        val gitRepo = findRepositoryForFile(file) ?: return getPrimary()
        return resolveFromGitRepo(gitRepo)
    }

    private fun findRepositoryForFile(file: VirtualFile): GitRepository? {
        val repos = GitRepositoryManager.getInstance(project).repositories
        if (repos.isEmpty()) return null
        if (repos.size == 1) return repos.first()
        // Find the repo whose root is an ancestor of the file, preferring the deepest match
        return repos
            .filter { file.path.startsWith(it.root.path + "/") || file.path == it.root.path }
            .maxByOrNull { it.root.path.length }
    }

    fun resolveFromGitRepo(gitRepo: GitRepository): RepoConfig? {
        val rootPath = gitRepo.root.path
        val settings = PluginSettings.getInstance(project)

        // Tier 1: exact path match
        settings.getRepoForPath(rootPath)?.let { return it }

        // Tier 2: match by parsing git remote URL
        val remoteUrl = gitRepo.remotes.firstOrNull()?.firstUrl ?: return getPrimary()
        val parsed = parseRemoteUrl(remoteUrl) ?: return getPrimary()
        settings.getRepos().find {
            it.bitbucketProjectKey.equals(parsed.first, ignoreCase = true) &&
                it.bitbucketRepoSlug.equals(parsed.second, ignoreCase = true)
        }?.let { return it }

        // Tier 3: fall back to primary
        return getPrimary()
    }

    fun resolveFromCurrentEditor(): RepoConfig? {
        val file = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedEditor?.file
            ?: return getPrimary()
        return resolveFromFile(file)
    }

    /**
     * Convenience resolver used by panels/services that need a concrete [GitRepository]:
     * resolve the repo config from the current editor (or primary), then look up the
     * matching [GitRepository] by local VCS root path. Falls back to the first known
     * repository if no match is found.
     *
     * This centralises the "editor repo or primary, materialised as GitRepository"
     * pattern that was previously duplicated in bamboo / sonar / pullrequest / jira.
     */
    fun resolveCurrentEditorRepoOrPrimary(): GitRepository? {
        val repoConfig = resolveFromCurrentEditor() ?: getPrimary()
        return materialize(repoConfig)
    }

    /**
     * Same as [resolveCurrentEditorRepoOrPrimary] but skips the "current editor" lookup —
     * returns the configured primary repository materialised as [GitRepository], falling
     * back to the first known repository if no match is found.
     */
    fun resolvePrimaryGitRepo(): GitRepository? = materialize(getPrimary())

    private fun materialize(repoConfig: RepoConfig?): GitRepository? {
        val repos = GitRepositoryManager.getInstance(project).repositories
        return repos.find { it.root.path == repoConfig?.localVcsRootPath } ?: repos.firstOrNull()
    }

    fun getPrimary(): RepoConfig? = PluginSettings.getInstance(project).getPrimaryRepo()

    fun autoDetectRepos(): List<RepoConfig> {
        val gitRepos = GitRepositoryManager.getInstance(project).repositories
        return gitRepos.mapNotNull { repo ->
            val remoteUrl = repo.remotes.firstOrNull()?.firstUrl ?: return@mapNotNull null
            val (projectKey, repoSlug) = parseRemoteUrl(remoteUrl) ?: return@mapNotNull null
            RepoConfig().apply {
                name = repoSlug
                bitbucketProjectKey = projectKey
                bitbucketRepoSlug = repoSlug
                localVcsRootPath = repo.root.path
                isPrimary = repo == gitRepos.first()
            }
        }
    }

    private fun parseRemoteUrl(url: String): Pair<String, String>? {
        // SSH: ssh://git@server/PROJECT/repo.git or git@server:PROJECT/repo.git
        // HTTPS: https://server/scm/PROJECT/repo.git
        val pattern = Regex(".*/([^/]+)/([^/]+?)(?:\\.git)?$")
        val match = pattern.find(url) ?: return null
        return Pair(match.groupValues[1], match.groupValues[2])
    }

    companion object {
        fun getInstance(project: Project): RepoContextResolver =
            project.getService(RepoContextResolver::class.java)
    }
}
