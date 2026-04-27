package com.workflow.orchestrator.core.settings

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.bitbucket.RemoteUrlParseResult
import com.workflow.orchestrator.core.bitbucket.RemoteUrlParser
import com.workflow.orchestrator.core.model.ErrorType
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

@Service(Service.Level.PROJECT)
class RepoContextResolver(private val project: Project) : Disposable {

    private val log = logger<RepoContextResolver>()

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
        val gitRepo = findRepositoryForPath(file.path) ?: return getPrimary()
        return resolveFromGitRepo(gitRepo)
    }

    /**
     * Resolves a [GitRepository] from a file system path. Prefers the deepest matching
     * repository root, so a file in a nested submodule resolves to the submodule, not
     * the parent. Returns null only when no repository contains the path AND the project
     * has multiple repos (single-repo projects always return that repo regardless).
     *
     * Use this from any caller that already knows which file the user is acting on
     * (checked changes, selected PR's source branch tip, build's plan VCS root, etc.)
     * — it is strictly more accurate than [resolveCurrentEditorRepoOrPrimary] for those
     * call sites because the user's chosen object names the right repo, while the editor
     * may be in a different submodule.
     */
    fun findRepositoryForPath(path: String): GitRepository? {
        val repos = GitRepositoryManager.getInstance(project).repositories
        if (repos.isEmpty()) return null
        if (repos.size == 1) return repos.first()
        // Find the repo whose root is an ancestor of the path, preferring the deepest match
        return repos
            .filter { path == it.root.path || path.startsWith(it.root.path + "/") }
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
            // Action 2: iterate remotes in priority order
            val remote = pickBestRemote(repo) ?: return@mapNotNull null
            // Try all fetch URLs, then push URLs as fallback
            val urls = remote.urls.takeIf { it.isNotEmpty() }
                ?: remote.pushUrls.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null

            var result: RepoConfig? = null
            for (url in urls) {
                when (val parsed = RemoteUrlParser.parse(url)) {
                    is RemoteUrlParseResult.CloudNotSupported -> {
                        // Action 3: explicit user-facing warning for Cloud remotes
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("workflow.autodetect")
                            .createNotification(
                                "Repository '${repo.root.name}' uses Bitbucket Cloud — " +
                                    "auto-detection skipped (plugin targets Bitbucket Server).",
                                NotificationType.WARNING
                            )
                            .notify(project)
                        break
                    }
                    is RemoteUrlParseResult.Unparseable -> {
                        log.warn("[AutoDetect] Could not parse remote URL '$url': ${parsed.reason}")
                        // try next URL
                    }
                    is RemoteUrlParseResult.Success -> {
                        result = RepoConfig().apply {
                            name = parsed.parsed.repoSlug
                            bitbucketProjectKey = parsed.parsed.projectKey
                            bitbucketRepoSlug = parsed.parsed.repoSlug
                            localVcsRootPath = repo.root.path
                            isPrimary = repo == gitRepos.first()
                            // canonicalCloneUrl left blank; populated by validateAndPersistCanonicalUrl
                        }
                        break
                    }
                }
            }
            result
        }
    }

    /**
     * Validates a parsed repo against the Bitbucket Server REST API and persists
     * the server-canonical HTTPS clone URL on [repo] if available.
     *
     * On NOT_FOUND: logs a warning (parse may have produced wrong key/slug).
     * On AUTH/NETWORK: logs info and leaves [repo.canonicalCloneUrl] blank for
     * retry on the next sweep.
     */
    suspend fun validateAndPersistCanonicalUrl(repo: RepoConfig, client: BitbucketBranchClient) {
        val projectKey = repo.bitbucketProjectKey ?: return
        val repoSlug = repo.bitbucketRepoSlug ?: return
        if (projectKey.isBlank() || repoSlug.isBlank()) return

        when (val apiResult = client.getRepository(projectKey, repoSlug)) {
            is com.workflow.orchestrator.core.model.ApiResult.Success -> {
                // Some Bitbucket DC versions emit the HTTPS clone link as name="http",
                // others as name="https". Accept either; prefer http if both present
                // (matches the Atlassian default branding).
                val cloneLinks = apiResult.data.links.clone
                val httpClone = (cloneLinks.firstOrNull { it.name == "http" }
                    ?: cloneLinks.firstOrNull { it.name == "https" })?.href
                if (!httpClone.isNullOrBlank()) {
                    repo.canonicalCloneUrl = httpClone
                    log.info("[AutoDetect] Persisted canonical URL for $projectKey/$repoSlug: $httpClone")
                }
            }
            is com.workflow.orchestrator.core.model.ApiResult.Error -> {
                when (apiResult.type) {
                    ErrorType.NOT_FOUND ->
                        log.warn("[AutoDetect] $projectKey/$repoSlug not found on server — auto-detected key may be wrong")
                    else ->
                        log.info("[AutoDetect] Cannot validate $projectKey/$repoSlug (${apiResult.type}): ${apiResult.message}")
                }
            }
        }
    }

    /**
     * Selects the best remote from [repo] using priority order:
     * `origin` → `upstream` → `bitbucket` → first available.
     */
    private fun pickBestRemote(repo: GitRepository): git4idea.repo.GitRemote? {
        val remotes = repo.remotes
        if (remotes.isEmpty()) return null
        return remotes.find { it.name == "origin" }
            ?: remotes.find { it.name == "upstream" }
            ?: remotes.find { it.name == "bitbucket" }
            ?: remotes.first()
    }

    /**
     * Retained for backwards compatibility with [resolveFromGitRepo] Tier 2.
     * Delegates to [RemoteUrlParser] so both paths share the same parsing logic.
     */
    private fun parseRemoteUrl(url: String): Pair<String, String>? {
        return when (val r = RemoteUrlParser.parse(url)) {
            is RemoteUrlParseResult.Success -> r.parsed.projectKey to r.parsed.repoSlug
            else -> null
        }
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
