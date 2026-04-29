package com.workflow.orchestrator.core.workflow.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import git4idea.branch.GitBrancher
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase D — wires the [ReadOnlyBanner] "Switch branch" link.
 *
 * Single-repo semantics by design: checks out `pr.fromBranch` only on the repo
 * named by the focused PR's `RepoRef.localVcsRootPath`. Multi-repo "checkout
 * same branch in all matching" is intentionally NOT supported — that's a
 * workspace-wide decision the user should make via the IDE's VCS popup, not a
 * side effect of clicking a banner link.
 *
 * Repo source: `WorkflowContext.activeRepo.localVcsRootPath` is the user-action
 * signal (the focused PR's repo) and is REQUIRED. There is no editor-context
 * fallback — if the focus chain doesn't pin a repo, the action aborts with
 * `NoRepoResolved`. Falling back to the editor's repo would silently switch
 * the wrong submodule's branch in multi-module projects.
 *
 * Dirty-tree guard: aborts with a warning notification if the resolved repo has
 * any tracked-but-uncommitted changes OR any untracked files. Saved unsaved
 * editor buffers count as tracked changes via `ChangeListManager`. Checking
 * `FileDocumentManager.unsavedDocuments` would also catch in-memory edits, but
 * `GitBrancher.checkout` itself prompts about smart-checkout / stash, so we
 * leave that to the platform.
 *
 * Threading: all checks + the actual checkout run off-EDT on the supplied
 * dispatcher; the caller (banner) launches on `WorkflowContextService.serviceCs`.
 */
object BranchSwitchAction {

    private val log = Logger.getInstance(BranchSwitchAction::class.java)

    sealed class Result {
        data class Switched(val branch: String, val repoRoot: String) : Result()
        data class Dirty(val branch: String, val repoRoot: String, val dirtyCount: Int) : Result()
        object NoFocusPr : Result()
        object NoRepoResolved : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun trySwitch(project: Project): Result = withContext(Dispatchers.IO) {
        val service = WorkflowContextService.getInstance(project)
        val state = service.state.value
        val pr: PrRef = state.focusPr ?: return@withContext Result.NoFocusPr

        val preferredRoot = state.activeRepo?.localVcsRootPath
        if (preferredRoot == null) {
            log.warn("[Workflow:Banner] Switch branch aborted: no activeRepo pinned in WorkflowContext")
            return@withContext Result.NoRepoResolved
        }
        val repo = resolveTargetRepo(project, preferredRoot)
            ?: return@withContext Result.NoRepoResolved

        val dirtyCount = countDirtyEntries(project, repo)
        if (dirtyCount > 0) {
            notifyDirty(project, pr.fromBranch, repo.root.path, dirtyCount)
            return@withContext Result.Dirty(pr.fromBranch, repo.root.path, dirtyCount)
        }

        return@withContext try {
            GitBrancher.getInstance(project).checkout(
                pr.fromBranch,
                /* detach = */ false,
                listOf(repo),
                /* callInAwtLater = */ null,
            )
            log.info("[Workflow:Banner] Switched repo '${repo.root.path}' to '${pr.fromBranch}'")
            Result.Switched(pr.fromBranch, repo.root.path)
        } catch (t: Throwable) {
            log.warn("[Workflow:Banner] Switch branch failed: ${t.message}", t)
            Result.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Multi-repo entry point used by the active-ticket chip's per-row
     * "Switch branch" affordance. Unlike [trySwitch] this does NOT depend on
     * `WorkflowContextService.activeRepo` — the row's repo is the user-action
     * signal. The dirty-tree guard, threading, and notification semantics are
     * identical to [trySwitch].
     *
     * Returns [Result.NoRepoResolved] when [repoRoot] does not match any
     * registered `GitRepository` (stale settings or repo unmounted).
     */
    suspend fun trySwitchTo(project: Project, repoRoot: String, branch: String): Result =
        withContext(Dispatchers.IO) {
            val repo = resolveTargetRepo(project, repoRoot)
                ?: return@withContext Result.NoRepoResolved

            val dirtyCount = countDirtyEntries(project, repo)
            if (dirtyCount > 0) {
                notifyDirty(project, branch, repo.root.path, dirtyCount)
                return@withContext Result.Dirty(branch, repo.root.path, dirtyCount)
            }

            return@withContext try {
                GitBrancher.getInstance(project).checkout(
                    branch,
                    /* detach = */ false,
                    listOf(repo),
                    /* callInAwtLater = */ null,
                )
                log.info("[Workflow:CurrentWork] Switched repo '${repo.root.path}' to '$branch'")
                Result.Switched(branch, repo.root.path)
            } catch (t: Throwable) {
                log.warn("[Workflow:CurrentWork] Switch branch failed: ${t.message}", t)
                Result.Failed(t.message ?: t.javaClass.simpleName)
            }
        }

    private fun resolveTargetRepo(project: Project, preferredRoot: String): GitRepository? {
        // `preferredRoot` is the contract: it comes from the focused PR's
        // `RepoRef.localVcsRootPath`. If the manager doesn't know about it
        // (project re-opened with stale context, repo unmounted, etc.) we
        // return null so the caller surfaces `NoRepoResolved` — silently
        // switching the editor's repo's branch would be the wrong submodule
        // in multi-module projects.
        val mgr = GitRepositoryManager.getInstance(project)
        return mgr.repositories.firstOrNull { it.root.path == preferredRoot }
    }

    private fun countDirtyEntries(project: Project, repo: GitRepository): Int {
        val rootPath = repo.root.path
        val rootPrefix = "$rootPath/"
        // Tracked changes scoped to this repo only — multi-repo projects share one
        // ChangeListManager.
        val trackedDirty = try {
            ChangeListManager.getInstance(project).allChanges.count { change ->
                val p = change.virtualFile?.path
                    ?: change.afterRevision?.file?.path
                    ?: change.beforeRevision?.file?.path
                    ?: return@count false
                p == rootPath || p.startsWith(rootPrefix)
            }
        } catch (_: Throwable) { 0 }
        // Untracked files reported by git4idea — already scoped to this repo.
        val untracked = try {
            repo.untrackedFilesHolder.retrieveUntrackedFilePaths().size
        } catch (_: Throwable) { 0 }
        return trackedDirty + untracked
    }

    private fun notifyDirty(project: Project, branch: String, repoRoot: String, dirtyCount: Int) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("workflow.autodetect")
            .createNotification(
                "Cannot switch to '$branch'",
                "Repository '${repoRoot.substringAfterLast('/')}' has $dirtyCount uncommitted/untracked file(s). " +
                    "Commit, stash, or revert before switching.",
                NotificationType.WARNING,
            )
            .notify(project)
    }
}
