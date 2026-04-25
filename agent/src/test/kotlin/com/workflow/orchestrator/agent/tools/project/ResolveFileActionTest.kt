package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
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

/**
 * Tests for [executeResolveFile].
 *
 * executeResolveFile uses IntelliJ platform services (LocalFileSystem, ProjectFileIndex,
 * DumbService, smartReadAction, ModuleUtilCore). These tests use MockK to stub the static
 * service lookups and verify the three key contracts:
 *
 *  1. testResolvesFileUnderModuleSourceRoot  — happy path: no error, content has module + "source"
 *  2. testReturnsErrorForNonexistentPath     — LocalFileSystem returns null → isError + "not found"
 *  3. testReturnsErrorWhenProjectIsIndexing  — DumbService.isDumb=true → isError + "indexing"
 *  4. testReturnsErrorForMissingPath         — no "path" param → isError before any platform call
 */
class ResolveFileActionTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        every { project.basePath } returns tempDir.toFile().absolutePath
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 1 — Happy path: file resolves to module + source root
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testResolvesFileUnderModuleSourceRoot() = runTest {
        val sourceFile = File(tempDir.toFile(), "src/main/kotlin/Foo.kt").also {
            it.parentFile.mkdirs()
            it.writeText("class Foo")
        }
        val absolutePath = sourceFile.absolutePath

        val mockModule = mockk<Module>(relaxed = true)
        every { mockModule.name } returns "my-module"

        val mockVFile = mockk<VirtualFile>(relaxed = true)
        every { mockVFile.path } returns absolutePath.replace("\\", "/")

        val mockIndex = mockk<ProjectFileIndex>(relaxed = true)
        every { mockIndex.getModuleForFile(mockVFile) } returns null   // null avoids ExternalSystemModulePropertyManager
        every { mockIndex.isInTestSourceContent(mockVFile) } returns false
        every { mockIndex.isInSourceContent(mockVFile) } returns true
        every { mockIndex.isInLibraryClasses(mockVFile) } returns false
        every { mockIndex.getContentRootForFile(mockVFile) } returns null
        every { mockIndex.getSourceRootForFile(mockVFile) } returns null

        mockkStatic(LocalFileSystem::class)
        val mockLfs = mockk<LocalFileSystem>(relaxed = true)
        every { LocalFileSystem.getInstance() } returns mockLfs
        every { mockLfs.findFileByPath(any()) } returns mockVFile

        mockkStatic(DumbService::class)
        every { DumbService.isDumb(project) } returns false

        mockkStatic(ProjectFileIndex::class)
        every { ProjectFileIndex.getInstance(project) } returns mockIndex

        mockkStatic(ModuleUtilCore::class)
        every { ModuleUtilCore.findModuleForFile(mockVFile, project) } returns mockModule

        // D7b: readAction { … } / smartReadAction(project) { … } are top-level suspending
        // functions in com.intellij.openapi.application.CoroutinesKt. Stub them to invoke
        // the lambda in-place so tests don't need an initialized IntelliJ Application.
        // Pattern from D7a's RuntimeExecRunConfigTest.
        mockkStatic("com.intellij.openapi.application.CoroutinesKt")
        coEvery { readAction<Any?>(any()) } coAnswers { firstArg<() -> Any?>().invoke() }
        coEvery { smartReadAction<Any?>(any(), any()) } coAnswers { secondArg<() -> Any?>().invoke() }

        val params = buildJsonObject { put("path", absolutePath) }
        val result = executeResolveFile(params, project)

        assertFalse(result.isError, "Expected no error but got: ${result.content}")
        assertTrue(
            result.content.contains("my-module"),
            "Expected module name 'my-module' in content: ${result.content}"
        )
        assertTrue(
            result.content.contains("source"),
            "Expected 'source' classification in content: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 2 — Nonexistent path → isError + "not found" message
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testReturnsErrorForNonexistentPath() = runTest {
        val fakePath = "/nonexistent/path/does-not-exist-xyz.kt"

        mockkStatic(LocalFileSystem::class)
        val mockLfs = mockk<LocalFileSystem>(relaxed = true)
        every { LocalFileSystem.getInstance() } returns mockLfs
        every { mockLfs.findFileByPath(any()) } returns null

        mockkStatic(DumbService::class)
        every { DumbService.isDumb(project) } returns false

        val params = buildJsonObject { put("path", fakePath) }
        val result = executeResolveFile(params, project)

        assertTrue(result.isError, "Expected isError=true for nonexistent path")
        assertTrue(
            result.content.lowercase().contains("not found"),
            "Expected 'not found' in error message but got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 3 — Dumb mode (indexing) → isError + "indexing" message
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testReturnsErrorWhenProjectIsIndexing() = runTest {
        val fakePath = "/some/valid/path/Foo.kt"

        mockkStatic(DumbService::class)
        every { DumbService.isDumb(project) } returns true

        val params = buildJsonObject { put("path", fakePath) }
        val result = executeResolveFile(params, project)

        assertTrue(result.isError, "Expected isError=true during indexing")
        assertTrue(
            result.content.lowercase().contains("indexing"),
            "Expected 'indexing' in error message but got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 4 — Missing "path" param → isError before any platform call
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testReturnsErrorForMissingPath() = runTest {
        val params = buildJsonObject { /* no "path" key */ }
        val result = executeResolveFile(params, project)

        assertTrue(result.isError, "Expected isError=true when 'path' param is missing")
        assertTrue(
            result.content.contains("path", ignoreCase = true),
            "Expected error message to mention 'path' but got: ${result.content}"
        )
    }
}
