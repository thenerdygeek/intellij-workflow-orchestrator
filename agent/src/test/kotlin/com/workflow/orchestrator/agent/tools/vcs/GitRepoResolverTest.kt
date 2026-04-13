package com.workflow.orchestrator.agent.tools.vcs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.branch.GitBranchesCollection
import git4idea.repo.GitBranchTrackInfo
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class GitRepoResolverTest {

    private val project = mockk<Project>(relaxed = true)

    @BeforeEach
    fun setup() {
        mockkStatic(GitRepositoryManager::class)
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    private fun mockRepo(rootPath: String, rootName: String, branch: String = "main"): GitRepository {
        val vf = mockk<VirtualFile>(relaxed = true)
        every { vf.path } returns rootPath
        every { vf.name } returns rootName

        val gitBranch = mockk<git4idea.GitLocalBranch>(relaxed = true)
        every { gitBranch.name } returns branch

        val repo = mockk<GitRepository>(relaxed = true)
        every { repo.root } returns vf
        every { repo.currentBranch } returns gitBranch
        return repo
    }

    private fun setupRepoManager(repos: List<GitRepository>) {
        val manager = mockk<GitRepositoryManager>(relaxed = true)
        every { manager.repositories } returns repos
        every { GitRepositoryManager.getInstance(project) } returns manager
    }

    // ─── Single repo (trivial cases) ───

    @Nested
    inner class SingleRepo {

        @Test
        fun `returns the only repo when single repo exists`() {
            val repo = mockRepo("/project/root", "root")
            setupRepoManager(listOf(repo))

            val result = GitRepoResolver.resolve(project)

            assertSame(repo, result)
        }

        @Test
        fun `returns the only repo regardless of repo param`() {
            val repo = mockRepo("/project/root", "root")
            setupRepoManager(listOf(repo))

            val result = GitRepoResolver.resolve(project, repo = "nonexistent")

            assertSame(repo, result, "Single repo should be returned even if repo param doesn't match")
        }

        @Test
        fun `returns null when no repos exist`() {
            setupRepoManager(emptyList())

            val result = GitRepoResolver.resolve(project)

            assertNull(result)
        }
    }

    // ─── Multi-repo: explicit repo param ───

    @Nested
    inner class MultiRepoExplicit {

        private val repoA = mockRepo("/project/module-a", "module-a", "feature/x")
        private val repoB = mockRepo("/project/module-b", "module-b", "main")
        private val repoC = mockRepo("/project/shared/lib", "lib", "develop")

        @BeforeEach
        fun setup() {
            every { project.basePath } returns "/project"
            setupRepoManager(listOf(repoA, repoB, repoC))
        }

        @Test
        fun `resolves by absolute path`() {
            val result = GitRepoResolver.resolve(project, repo = "/project/module-b")
            assertSame(repoB, result)
        }

        @Test
        fun `resolves by relative path`() {
            val result = GitRepoResolver.resolve(project, repo = "module-a")
            assertSame(repoA, result)
        }

        @Test
        fun `resolves by directory name`() {
            val result = GitRepoResolver.resolve(project, repo = "module-b")
            assertSame(repoB, result)
        }

        @Test
        fun `resolves nested relative path`() {
            val result = GitRepoResolver.resolve(project, repo = "shared/lib")
            assertSame(repoC, result)
        }

        @Test
        fun `directory name match is case-insensitive`() {
            val result = GitRepoResolver.resolve(project, repo = "Module-A")
            assertSame(repoA, result)
        }

        @Test
        fun `falls back to first repo when repo param doesn't match`() {
            val result = GitRepoResolver.resolve(project, repo = "nonexistent")
            assertSame(repoA, result, "Should fall back to first repo")
        }
    }

    // ─── Multi-repo: auto-resolve from path ───

    @Nested
    inner class MultiRepoAutoResolve {

        private val repoA = mockRepo("/project/module-a", "module-a")
        private val repoB = mockRepo("/project/module-b", "module-b")

        @BeforeEach
        fun setup() {
            every { project.basePath } returns "/project"
            setupRepoManager(listOf(repoA, repoB))
        }

        @Test
        fun `resolves repo from relative file path`() {
            val result = GitRepoResolver.resolve(project, path = "module-b/src/Main.kt")
            assertSame(repoB, result)
        }

        @Test
        fun `resolves repo from absolute file path`() {
            val result = GitRepoResolver.resolve(project, path = "/project/module-a/src/Foo.kt")
            assertSame(repoA, result)
        }

        @Test
        fun `falls back to first repo when path doesn't match any root`() {
            val result = GitRepoResolver.resolve(project, path = "other-module/src/Bar.kt")
            assertSame(repoA, result)
        }

        @Test
        fun `explicit repo param takes precedence over path`() {
            val result = GitRepoResolver.resolve(project, repo = "module-a", path = "module-b/src/Main.kt")
            assertSame(repoA, result, "Explicit repo should win over path-based resolution")
        }
    }

    // ─── Multi-repo: nested repos ───

    @Nested
    inner class NestedRepos {

        @Test
        fun `prefers most specific (longest) root for nested repos`() {
            val outer = mockRepo("/project", "project")
            val inner = mockRepo("/project/submodule", "submodule")
            every { project.basePath } returns "/project"
            setupRepoManager(listOf(outer, inner))

            val result = GitRepoResolver.resolve(project, path = "submodule/src/Foo.kt")

            assertSame(inner, result, "Should pick the inner/nested repo, not the outer")
        }
    }

    // ─── availableReposHint ───

    @Nested
    inner class AvailableReposHint {

        @Test
        fun `returns empty for single repo`() {
            val repo = mockRepo("/project/root", "root")
            setupRepoManager(listOf(repo))
            every { project.basePath } returns "/project"

            val hint = GitRepoResolver.availableReposHint(project)

            assertEquals("", hint)
        }

        @Test
        fun `returns hint for multi repo`() {
            val repoA = mockRepo("/project/a", "a", "main")
            val repoB = mockRepo("/project/b", "b", "develop")
            setupRepoManager(listOf(repoA, repoB))
            every { project.basePath } returns "/project"

            val hint = GitRepoResolver.availableReposHint(project)

            assertTrue(hint.contains("2 git roots"))
            assertTrue(hint.contains("a"))
            assertTrue(hint.contains("b"))
            assertTrue(hint.contains("main"))
            assertTrue(hint.contains("develop"))
        }

        @Test
        fun `returns empty when no repos`() {
            setupRepoManager(emptyList())

            val hint = GitRepoResolver.availableReposHint(project)

            assertEquals("", hint)
        }
    }
}
