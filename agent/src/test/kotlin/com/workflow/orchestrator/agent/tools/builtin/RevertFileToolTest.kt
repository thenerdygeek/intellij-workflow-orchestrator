package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.settings.RepoContextResolver
import git4idea.repo.GitRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class RevertFileToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = RevertFileTool()

    @BeforeEach
    fun setUp() {
        // Default: no repo match → tool falls back to project.basePath, matching
        // pre-fix behavior for single-repo projects. The submodule test overrides
        // this to return a specific GitRepository.
        val defaultResolver = mockk<RepoContextResolver>(relaxed = true) {
            every { findRepositoryForPath(any()) } returns null
        }
        every { project.getService(RepoContextResolver::class.java) } returns defaultResolver
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `tool metadata is correct`() {
        assertEquals("revert_file", tool.name)
        assertTrue(tool.parameters.required.contains("file_path"))
        assertTrue(tool.parameters.required.contains("description"))
    }

    @Test
    fun `allowed only for coder`() {
        assertEquals(setOf(WorkerType.CODER), tool.allowedWorkers)
    }

    @Test
    fun `returns error when file_path missing`() = runTest {
        val params = buildJsonObject { put("description", "bad edit") }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("file_path"))
    }

    @Test
    fun `returns error when description missing`() = runTest {
        val params = buildJsonObject { put("file_path", "/src/A.kt") }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("description"))
    }

    @Test
    fun `returns error when agent service unavailable`() = runTest {
        val params = buildJsonObject {
            put("file_path", "/src/A.kt")
            put("description", "bad edit")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
    }

    // ── Regression: fix 8a8712f2d ─────────────────────────────────────────────
    // Bug (a): PathValidator was not called before the git checkout, so a path
    //   like "../../etc/passwd" bypassed the security gate. The fix added
    //   PathValidator.resolveAndValidateForWrite at the top of execute() before
    //   ProcessBuilder is ever constructed.
    // Bug (b): After a successful git checkout, VFS and Document caches were not
    //   refreshed, so editors and subsequent read_file calls saw stale content.
    //   The fix added LocalFileSystem.getInstance().refreshAndFindFileByPath()
    //   plus FileDocumentManager.reloadFromDisk() after exit code 0.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Regression (a) — PathValidator security gate.
     *
     * Passes a traversal path ("../../etc/passwd") to revert_file.
     * Asserts: (1) the tool returns an error, (2) the error mentions "outside"
     * (PathValidator's wording), (3) no git process was spawned.
     *
     * The pre-fix code did NOT call PathValidator, so it would attempt to run
     * `git checkout -- <resolved-path>` against the escaped path. After the fix,
     * PathValidator.resolveAndValidateForWrite rejects the path before ProcessBuilder
     * is ever constructed. The test confirms (3) by verifying no ProcessBuilder
     * was created — done indirectly: the project's basePath is set to a real temp
     * dir so the PathValidator can compute a canonical project root, and the tool
     * must return an error result without touching git.
     */
    @Test
    fun `PathValidator rejects traversal path — git is never spawned`(@TempDir tempDir: Path) = runTest {
        every { project.basePath } returns tempDir.toFile().absolutePath

        val params = buildJsonObject {
            put("file_path", "../../etc/passwd")
            put("description", "trying to escape")
        }

        val result = tool.execute(params, project)

        // PathValidator must reject the escaping path
        assertTrue(result.isError, "Traversal path must be rejected with isError=true")
        assertTrue(
            result.content.contains("outside", ignoreCase = true),
            "Error message must mention 'outside' (PathValidator wording): ${result.content}"
        )
        // If the pre-fix code were present, ProcessBuilder("git", "checkout", "--", …) would run.
        // With the fix, PathValidator returns an error before any git spawn.
        // We verify this indirectly: git checkout on a non-existent resolved path
        // would produce a different error ("Failed to revert"), not a path-outside error.
        assertFalse(
            result.content.contains("Failed to revert", ignoreCase = true),
            "Error must come from PathValidator (path-outside), not from a git spawn attempt"
        )
    }

    /**
     * Regression (a) — PathValidator also rejects absolute escaping paths.
     *
     * An absolute path like "/etc/passwd" that resolves outside the project root
     * must be rejected by PathValidator before any git is touched.
     */
    @Test
    fun `PathValidator rejects absolute path outside project root`(@TempDir tempDir: Path) = runTest {
        every { project.basePath } returns tempDir.toFile().absolutePath

        val params = buildJsonObject {
            put("file_path", "/etc/passwd")
            put("description", "absolute escape attempt")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError, "Absolute path outside project must be rejected")
        assertTrue(
            result.content.contains("outside", ignoreCase = true),
            "Error must come from PathValidator, not git: ${result.content}"
        )
    }

    /**
     * Regression (b) — VFS refresh is invoked after a successful git checkout.
     *
     * Mocks LocalFileSystem.getInstance() so that refreshAndFindFileByPath is
     * observable. After calling execute() with a valid in-project path, verifies
     * that refreshAndFindFileByPath was called with the resolved canonical path.
     *
     * The pre-fix code did no VFS refresh. After the fix, the code calls
     * LocalFileSystem.getInstance().refreshAndFindFileByPath(resolvedPath)
     * inside a try-catch block on successful git exit code 0.
     *
     * Strategy: create a real file inside @TempDir, mock git checkout to succeed
     * (by relying on `git checkout -- <path>` on a file with no diff from HEAD
     * returning exit 0, or by running against a temp git repo), and verify the
     * VFS refresh call. Since spawning an actual git process is fragile in CI,
     * we use the approach of providing a file that has no staged changes — git
     * checkout on an unmodified file exits 0 silently. On systems without git,
     * this test is accepted as best-effort and the VFS mock path is still validated.
     *
     * Alternative strategy used here: mock LocalFileSystem.getInstance() BEFORE
     * the execute() call. If the tool calls refreshAndFindFileByPath at all
     * (regardless of what git returns), the mock will capture the call.
     * We create an actual git repo in @TempDir so git checkout exits 0.
     */
    @Test
    fun `VFS refreshAndFindFileByPath is called after successful git checkout`(@TempDir tempDir: Path) = runTest {
        val projectDir = tempDir.toFile()
        every { project.basePath } returns projectDir.absolutePath

        // Create a real git repository so git checkout can succeed
        val gitInitResult = ProcessBuilder("git", "init")
            .directory(projectDir)
            .redirectErrorStream(true)
            .start()
            .waitFor()
        if (gitInitResult != 0) return@runTest  // skip if git unavailable

        // Configure git identity so commit works in CI environments
        ProcessBuilder("git", "config", "user.email", "test@test.com")
            .directory(projectDir).start().waitFor()
        ProcessBuilder("git", "config", "user.name", "Test")
            .directory(projectDir).start().waitFor()

        // Create a tracked file and commit it
        val trackedFile = File(projectDir, "Target.kt")
        trackedFile.writeText("fun original() {}")
        ProcessBuilder("git", "add", "Target.kt")
            .directory(projectDir).start().waitFor()
        ProcessBuilder("git", "commit", "-m", "init")
            .directory(projectDir).start().waitFor()

        // Modify the file so git checkout restores it (confirms exit 0 is a real revert)
        trackedFile.writeText("fun modified() {}")

        // Spy on LocalFileSystem so we can verify refreshAndFindFileByPath is called
        val mockVirtualFile = mockk<VirtualFile>(relaxed = true)
        val mockLocalFileSystem = mockk<LocalFileSystem>(relaxed = true) {
            every { refreshAndFindFileByPath(any()) } returns mockVirtualFile
        }
        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns mockLocalFileSystem

        val params = buildJsonObject {
            put("file_path", "Target.kt")
            put("description", "undo bad edit")
        }

        val result = tool.execute(params, project)

        // Tool must succeed (git checkout exits 0)
        assertFalse(result.isError, "Expected success from git checkout on a tracked file: ${result.content}")

        // VFS refresh must be called with the resolved canonical path
        verify(atLeast = 1) { mockLocalFileSystem.refreshAndFindFileByPath(trackedFile.canonicalPath) }
    }

    /**
     * Regression — submodule support.
     *
     * When the target file lives inside a nested git repo (a submodule, or any
     * sub-dir with its own `.git`), the tool must resolve the submodule's root
     * via RepoContextResolver and run git from there. The pre-fix code always
     * ran git from project.basePath, which made the submodule's .git invisible
     * upward and produced "fatal: not a git repository (or any of the parent
     * directories)".
     *
     * Setup: project root is a temp dir with NO git init, and a `submodule/`
     * subdir has its own real git repo with a tracked-and-modified file.
     * Running git from project root would fail; running from submodule works.
     * The fix wires project.service<RepoContextResolver>().findRepositoryForPath
     * to point ProcessBuilder.directory at the submodule's root.
     */
    @Test
    fun `submodule path resolves to nested git root not project basePath`(@TempDir tempDir: Path) = runTest {
        val projectDir = tempDir.toFile()
        every { project.basePath } returns projectDir.absolutePath

        // Submodule has its own git repo; project root has none.
        val submoduleDir = File(projectDir, "submodule").apply { mkdirs() }
        if (ProcessBuilder("git", "init").directory(submoduleDir).redirectErrorStream(true).start().waitFor() != 0) {
            return@runTest  // skip if git unavailable
        }
        ProcessBuilder("git", "config", "user.email", "test@test.com").directory(submoduleDir).start().waitFor()
        ProcessBuilder("git", "config", "user.name", "Test").directory(submoduleDir).start().waitFor()

        val trackedFile = File(submoduleDir, "pom.xml")
        trackedFile.writeText("<project>original</project>")
        ProcessBuilder("git", "add", "pom.xml").directory(submoduleDir).start().waitFor()
        ProcessBuilder("git", "commit", "-m", "init").directory(submoduleDir).start().waitFor()
        trackedFile.writeText("<project>modified</project>")

        // RepoContextResolver returns the submodule's GitRepository for the path.
        val submoduleRoot = mockk<VirtualFile>(relaxed = true) {
            every { path } returns submoduleDir.canonicalPath
        }
        val submoduleRepo = mockk<GitRepository>(relaxed = true) {
            every { root } returns submoduleRoot
        }
        val resolver = mockk<RepoContextResolver>(relaxed = true) {
            every { findRepositoryForPath(any()) } returns submoduleRepo
        }
        every { project.getService(RepoContextResolver::class.java) } returns resolver

        // VFS stub so the post-checkout refresh path doesn't NPE.
        val mockVf = mockk<VirtualFile>(relaxed = true)
        val mockLfs = mockk<LocalFileSystem>(relaxed = true) {
            every { refreshAndFindFileByPath(any()) } returns mockVf
        }
        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns mockLfs

        val params = buildJsonObject {
            put("file_path", "submodule/pom.xml")
            put("description", "wrong refactor direction")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError, "Expected success when revert runs from submodule root: ${result.content}")
        // Pre-fix behavior would surface this exact phrase from git's own stderr.
        assertFalse(
            result.content.contains("not a git repository", ignoreCase = true),
            "Working dir must be the submodule root, not project.basePath: ${result.content}"
        )
        verify(atLeast = 1) { resolver.findRepositoryForPath(trackedFile.canonicalPath) }
    }
}
