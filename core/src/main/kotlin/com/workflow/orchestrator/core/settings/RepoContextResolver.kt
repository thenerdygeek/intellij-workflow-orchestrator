package com.workflow.orchestrator.core.settings

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

@Service(Service.Level.PROJECT)
class RepoContextResolver(private val project: Project) : Disposable {

    /**
     * Bumped when any input to [resolveCurrentEditorRepoOrPrimary] may have changed:
     * active editor file, VCS repository mapping, or plugin repos config (via
     * [invalidateCache]). The [currentRepoCache] re-runs its provider only when this
     * tracker's count differs from the stored value — in the steady state, repeated
     * calls with unchanged inputs cost one `AtomicLong.get()` each instead of an
     * O(n) repo + settings scan.
     */
    private val cacheTracker = SimpleModificationTracker()

    init {
        val bus = project.messageBus.connect(this)
        bus.subscribe(
            VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED,
            VcsRepositoryMappingListener { cacheTracker.incModificationCount() }
        )
        bus.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    cacheTracker.incModificationCount()
                }
            }
        )
    }

    /**
     * Hand-bumps the cache tracker. Called from [RepositoriesConfigurable] after the
     * user edits the repos list in Settings — VCS mapping listener and editor listener
     * don't fire on that, so it's the one invalidation source we can't auto-wire.
     */
    fun invalidateCache() {
        cacheTracker.incModificationCount()
    }

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
     *
     * Result is memoised via [CachedValuesManager] keyed on [cacheTracker]; callers
     * invoking this in a tight loop (panel refreshes, poller ticks) get O(1) reads
     * as long as the editor selection, VCS mapping, and repos config are stable.
     */
    fun resolveCurrentEditorRepoOrPrimary(): GitRepository? = currentRepoCache.value

    private val currentRepoCache: CachedValue<GitRepository?> =
        CachedValuesManager.getManager(project).createCachedValue({
            CachedValueProvider.Result.create(
                doResolveCurrentEditorRepoOrPrimary(),
                cacheTracker
            )
        }, false)

    private fun doResolveCurrentEditorRepoOrPrimary(): GitRepository? {
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

    override fun dispose() {
        // MessageBusConnection was parented on this service (see init); platform
        // disposes it automatically when the project closes. Nothing to do here.
    }

    companion object {
        fun getInstance(project: Project): RepoContextResolver =
            project.getService(RepoContextResolver::class.java)
    }
}
