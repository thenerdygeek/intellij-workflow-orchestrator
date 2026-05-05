package com.workflow.orchestrator.core.vfs

import com.intellij.compiler.server.BuildManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Cache-coherency helper for "external mutator just exited; bring IntelliJ in sync with disk."
 *
 * Use cases:
 *  - After `run_command` exits, refresh the working directory so subsequent tool calls see
 *    fresh files (Layer A — always-on, working-dir scope).
 *  - After a git mutator (`git stash`, `git checkout`, …) refresh the whole project
 *    (Layer B — mutator-aware, broader scope).
 *  - After a build clean (`mvn clean`, `gradle clean`), drop JPS in-memory state so the next
 *    `ProjectTaskManager.build(module)` doesn't trust stale incremental stamps.
 *
 * Threading:
 *  - Safe to call from `Dispatchers.IO`. Must NOT be called while holding a read lock when
 *    [async] is false (documented platform deadlock for synchronous refresh).
 *  - When [async] is true (the default), the call returns immediately and refresh runs on
 *    the platform's internal scheduler.
 *
 * Why `markDirtyAndRefresh` instead of plain `refresh()`:
 *  - `refresh()` no-ops when the on-disk mtime matches the cached mtime. `git stash` /
 *    `git checkout <file>` can roll mtimes *backward*, leaving cached and on-disk mtimes
 *    different but the cached snapshot stale. `markDirtyAndRefresh` forces a re-stat
 *    regardless of timestamp direction.
 */
object PostMutationRefresh {

    private val LOG = Logger.getInstance(PostMutationRefresh::class.java)

    /** Directories to elide when recursing — match the exclusions used by build/index tooling. */
    private val EXCLUDED_DIR_NAMES = setOf(
        "node_modules", "target", "build", "out", "dist",
        ".gradle", ".idea", ".git",
        "__pycache__", ".venv", "venv",
        ".tox", ".mypy_cache", ".pytest_cache",
    )

    sealed interface Scope {
        /** Refresh the whole project (recursive from `guessProjectDir`). */
        data object WholeProject : Scope

        /** Refresh a specific directory recursively (typical for `run_command` working_dir). */
        data class WorkingDir(val dir: Path) : Scope

        /** Refresh exactly this set of paths (e.g. from `git diff --name-only`). Non-recursive. */
        data class KnownPaths(val paths: List<Path>) : Scope
    }

    /**
     * Bring the IntelliJ VFS in sync with disk for the given [scope].
     *
     * Called after a mutator subprocess exits. Always uses `markDirtyAndRefresh` so that
     * mtime rollbacks (git stash/checkout) don't fool the cache into thinking nothing changed.
     */
    fun refresh(project: Project, scope: Scope, async: Boolean = true) {
        val files: List<VirtualFile> = when (scope) {
            Scope.WholeProject -> listOfNotNull(project.guessProjectDir())
            is Scope.WorkingDir -> {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(scope.dir)
                listOfNotNull(vf).filter { !isExcludedDir(it) }
            }
            is Scope.KnownPaths -> scope.paths.mapNotNull {
                LocalFileSystem.getInstance().findFileByNioFile(it)
            }
        }
        if (files.isEmpty()) {
            LOG.debug("PostMutationRefresh: no resolvable VirtualFile for scope=$scope; skipping")
            return
        }
        val recursive = scope !is Scope.KnownPaths
        try {
            VfsUtil.markDirtyAndRefresh(async, recursive, /* reloadChildren = */ true, *files.toTypedArray())
            LOG.debug("PostMutationRefresh: scope=$scope, files=${files.size}, async=$async, recursive=$recursive")
        } catch (e: Exception) {
            // VFS refresh is best-effort — never let a refresh failure break tool execution.
            LOG.warn("PostMutationRefresh: refresh threw, continuing without refresh: ${e.message}")
        }
    }

    /**
     * Drop JPS's in-memory incremental-build state for [project].
     *
     * Call this after a build-tool clean (`mvn clean`, `gradle clean`, `npm ci` if it
     * destroys output dirs) so the next `ProjectTaskManager.build(module)` re-reads
     * source stamps from disk instead of trusting cached incremental state that no
     * longer corresponds to anything on disk.
     */
    fun clearJpsCache(project: Project) {
        try {
            BuildManager.getInstance().clearState(project)
            LOG.info("PostMutationRefresh: cleared JPS state for project=${project.name}")
        } catch (e: Exception) {
            LOG.warn("PostMutationRefresh: clearJpsCache failed (non-fatal): ${e.message}")
        }
    }

    private fun isExcludedDir(vf: VirtualFile): Boolean =
        vf.isDirectory && vf.name in EXCLUDED_DIR_NAMES
}

/**
 * Bug 4 — Layer C: pre-launch barrier for test/runtime tools.
 *
 * Suspends until the IDE leaves dumb mode (indexing finished). Intended to be called
 * from a `Dispatchers.IO` coroutine just before `ProjectTaskManager.build(module)` so
 * that the build sees a fully-indexed VFS — particularly when a recent `run_command`
 * triggered a wide refresh that fanned out into reindexing.
 *
 * Returns `true` if smart mode was reached within [timeoutMs] (or was already smart),
 * `false` if the wait timed out.
 */
suspend fun waitForSmartModeOrTimeout(
    project: com.intellij.openapi.project.Project,
    timeoutMs: Long = 60_000L,
): Boolean {
    val dumbService = com.intellij.openapi.project.DumbService.getInstance(project)
    if (!dumbService.isDumb) return true
    return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        // smartReadAction's lambda only runs once smart mode is reached. We don't need
        // any read access — we just want the wait. The platform handles WARA cancel/retry.
        com.intellij.openapi.application.smartReadAction(project) { /* no-op */ }
        true
    } ?: false
}
