package com.workflow.orchestrator.core.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValuesManager
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Unit tests for [RepoContextResolver.findRepositoryForPath].
 *
 * Pins the contract for the helper introduced as part of the repo-resolution sweep
 * (`docs/architecture/repo-resolution-sweep-plan.md`). Callers that already know which
 * file the user is acting on (checked changes, focused PR's source branch tip, build's
 * plan VCS root, etc.) must use this helper rather than the editor-or-primary fallback.
 *
 * Cases:
 * - zero repos returns null
 * - single repo returns that repo regardless of path (single-repo projects always win)
 * - multiple repos return the deepest matching root (nested submodule wins over parent)
 * - path outside all repos returns null in multi-repo projects
 */
class RepoContextResolverTest {

    @AfterEach
    fun teardown() = unmockkAll()

    private fun stubRepo(rootPath: String): GitRepository {
        val vf = mockk<VirtualFile>(relaxed = true)
        every { vf.path } returns rootPath
        val repo = mockk<GitRepository>(relaxed = true)
        every { repo.root } returns vf
        return repo
    }

    /**
     * Builds a [RepoContextResolver] without invoking any platform-bound init paths.
     * The resolver's `init` block subscribes to message buses and `currentRepoCache`
     * is created via [CachedValuesManager] at construction time — both require a
     * running IDE, so we stub them. The resolver's `project` field is then used only
     * to look up [GitRepositoryManager] inside [findRepositoryForPath].
     */
    private fun buildResolver(repos: List<GitRepository>): RepoContextResolver {
        val project = mockk<Project>(relaxed = true)

        val mgr = mockk<GitRepositoryManager>(relaxed = true)
        every { mgr.repositories } returns repos
        mockkStatic(GitRepositoryManager::class)
        every { GitRepositoryManager.getInstance(project) } returns mgr

        // CachedValuesManager.getManager(project).createCachedValue(...) is called at
        // construction time (property initialiser); stub it so construction succeeds
        // off-platform.
        mockkStatic(CachedValuesManager::class)
        val cvm = mockk<CachedValuesManager>(relaxed = true)
        every { CachedValuesManager.getManager(project) } returns cvm

        return RepoContextResolver(project)
    }

    @Test
    fun `findRepositoryForPath returns null when project has zero repos`() {
        val resolver = buildResolver(emptyList())

        assertNull(resolver.findRepositoryForPath("/work/anything/Foo.kt"))
    }

    @Test
    fun `findRepositoryForPath returns the single repo regardless of path`() {
        val repo = stubRepo("/work/single")
        val resolver = buildResolver(listOf(repo))

        // Inside the repo
        assertSame(repo, resolver.findRepositoryForPath("/work/single/src/Foo.kt"))
        // Path outside the repo — single-repo projects always return their one repo,
        // because callers using this helper need *some* repo and there is no ambiguity.
        assertSame(repo, resolver.findRepositoryForPath("/elsewhere/Bar.kt"))
    }

    @Test
    fun `findRepositoryForPath returns deepest matching repo in multi-repo project`() {
        val parent = stubRepo("/work/multi")
        val nested = stubRepo("/work/multi/serviceA")
        val sibling = stubRepo("/work/multi/serviceB")
        val resolver = buildResolver(listOf(parent, nested, sibling))

        // File inside the nested submodule — must resolve to the nested repo, not the parent.
        assertSame(
            nested,
            resolver.findRepositoryForPath("/work/multi/serviceA/src/Foo.kt"),
            "deepest matching root should win"
        )
        // File inside sibling submodule.
        assertSame(
            sibling,
            resolver.findRepositoryForPath("/work/multi/serviceB/src/Bar.kt")
        )
        // File directly under the parent repo (not in any submodule) resolves to the parent.
        assertSame(
            parent,
            resolver.findRepositoryForPath("/work/multi/README.md")
        )
        // The repo root path itself resolves to that repo.
        assertSame(nested, resolver.findRepositoryForPath("/work/multi/serviceA"))
    }

    @Test
    fun `findRepositoryForPath returns null when path outside all repos in multi-repo project`() {
        val a = stubRepo("/work/multi/serviceA")
        val b = stubRepo("/work/multi/serviceB")
        val resolver = buildResolver(listOf(a, b))

        assertNull(resolver.findRepositoryForPath("/elsewhere/Foo.kt"))
        // Path that is a *prefix* of a repo root must not match — startsWith uses "/" boundary.
        assertNull(resolver.findRepositoryForPath("/work/multi"))
    }

    @Test
    fun `findRepositoryForPath does not falsely match repo with shared prefix`() {
        // "/work/multi/serviceA" must NOT match a path under "/work/multi/serviceA-extras"
        // (the boundary check is `path.startsWith(root + "/")`, not bare startsWith).
        val a = stubRepo("/work/multi/serviceA")
        val resolver = buildResolver(listOf(a, stubRepo("/work/other")))

        assertNull(resolver.findRepositoryForPath("/work/multi/serviceA-extras/src/Foo.kt"))
        // Plain prefix as a non-path string also must not match.
        assertNull(resolver.findRepositoryForPath("/work/multi/serviceAB"))
    }

    @Test
    fun `findRepositoryForPath supports the multi-module commit scenario from the bug`() {
        // Reproduces the multi-module bug from commit 4be45839: editor in repo A, but the
        // user has checked a file in repo B. Resolving by the *checked* path must yield B.
        val repoA = stubRepo("/work/multi/serviceA")
        val repoB = stubRepo("/work/multi/serviceB")
        val resolver = buildResolver(listOf(repoA, repoB))

        val checkedFileInB = "/work/multi/serviceB/src/main/kotlin/Service.kt"
        assertEquals(repoB, resolver.findRepositoryForPath(checkedFileInB))
    }
}
