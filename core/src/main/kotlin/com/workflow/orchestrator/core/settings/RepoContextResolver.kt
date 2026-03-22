package com.workflow.orchestrator.core.settings

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

@Service(Service.Level.PROJECT)
class RepoContextResolver(private val project: Project) {

    fun resolveFromFile(file: VirtualFile): RepoConfig? {
        val gitRepo = GitRepositoryManager.getInstance(project).getRepositoryForFile(file)
            ?: return getPrimary()
        return resolveFromGitRepo(gitRepo)
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
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedEditor
        val file = editor?.file ?: return getPrimary()
        return resolveFromFile(file)
    }

    fun getAllRepos(): List<RepoConfig> = PluginSettings.getInstance(project).getRepos()

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
