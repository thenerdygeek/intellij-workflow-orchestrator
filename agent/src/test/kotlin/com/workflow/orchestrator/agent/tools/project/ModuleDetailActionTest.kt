package com.workflow.orchestrator.agent.tools.project

import com.intellij.facet.FacetManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.SourceFolder
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.VirtualFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.module.JpsModuleSourceRoot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [executeModuleDetail].
 *
 * All IntelliJ platform services are mocked via MockK static mocking
 * (same pattern used by [ResolveFileActionTest]).
 *
 *  1. testMissingModuleParamReturnsError — no "module" param → isError, message mentions 'module'
 *  2. testModuleNotFoundReturnsError     — ModuleManager returns null → isError, message contains module name
 *  3. testDumbModeReturnsError           — DumbService.isDumb=true → isError, message contains "indexing"
 *  4. testModuleDetailReturnsContent     — happy path: 1 content root, 1 source folder → no error,
 *                                          content contains module name and "source"
 */
class ModuleDetailActionTest {

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        every { project.basePath } returns "/project/root"
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 1 — Missing "module" param → isError + message mentions 'module'
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testMissingModuleParamReturnsError() {
        val params = buildJsonObject { /* no "module" key */ }
        val result = executeModuleDetail(params, project)

        assertTrue(result.isError, "Expected isError=true when 'module' param is missing")
        assertTrue(
            result.content.contains("module", ignoreCase = true),
            "Expected error message to mention 'module' but got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 2 — ModuleManager returns null → isError + content contains module name
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testModuleNotFoundReturnsError() {
        val unknownName = "non-existent-module"

        mockkStatic(DumbService::class)
        every { DumbService.isDumb(project) } returns false

        val mockModuleManager = mockk<ModuleManager>(relaxed = true)
        mockkStatic(ModuleManager::class)
        every { ModuleManager.getInstance(project) } returns mockModuleManager
        every { mockModuleManager.findModuleByName(unknownName) } returns null

        val params = buildJsonObject { put("module", unknownName) }
        val result = executeModuleDetail(params, project)

        assertTrue(result.isError, "Expected isError=true for unknown module")
        assertTrue(
            result.content.contains(unknownName),
            "Expected module name '$unknownName' in error content but got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 3 — DumbService.isDumb=true → isError + "indexing" in content
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testDumbModeReturnsError() {
        mockkStatic(DumbService::class)
        every { DumbService.isDumb(project) } returns true

        val params = buildJsonObject { put("module", "any-module") }
        val result = executeModuleDetail(params, project)

        assertTrue(result.isError, "Expected isError=true while project is indexing")
        assertTrue(
            result.content.lowercase().contains("indexing"),
            "Expected 'indexing' in error message but got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 4 — Happy path: module found, 1 content root, 1 source folder
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testModuleDetailReturnsContent() {
        val moduleName = "my-core-module"

        // -- DumbService: not indexing --
        mockkStatic(DumbService::class)
        every { DumbService.isDumb(project) } returns false

        // -- ModuleManager: returns a mock module --
        val mockModule = mockk<Module>(relaxed = true)
        every { mockModule.name } returns moduleName

        val mockModuleManager = mockk<ModuleManager>(relaxed = true)
        mockkStatic(ModuleManager::class)
        every { ModuleManager.getInstance(project) } returns mockModuleManager
        every { mockModuleManager.findModuleByName(moduleName) } returns mockModule

        // -- Content root VirtualFile --
        val mockContentRootVFile = mockk<VirtualFile>(relaxed = true)
        every { mockContentRootVFile.path } returns "/project/root/my-core-module/src/main/java"

        // -- Source folder: jpsElement.rootType = JavaSourceRootType.SOURCE --
        val mockJpsRoot = mockk<JpsModuleSourceRoot>(relaxed = true)
        every { mockJpsRoot.rootType } returns JavaSourceRootType.SOURCE

        val mockSourceFolder = mockk<SourceFolder>(relaxed = true)
        every { mockSourceFolder.jpsElement } returns mockJpsRoot
        every { mockSourceFolder.file } returns mockContentRootVFile

        // -- ContentEntry --
        val mockContentEntry = mockk<ContentEntry>(relaxed = true)
        every { mockContentEntry.file } returns mockContentRootVFile
        every { mockContentEntry.sourceFolders } returns arrayOf(mockSourceFolder)
        every { mockContentEntry.excludeFolderFiles } returns emptyArray()

        // -- ModuleRootManager --
        val mockRm = mockk<ModuleRootManager>(relaxed = true)
        mockkStatic(ModuleRootManager::class)
        every { ModuleRootManager.getInstance(mockModule) } returns mockRm
        every { mockRm.sdk } returns null
        every { mockRm.contentRoots } returns arrayOf(mockContentRootVFile)
        every { mockRm.contentEntries } returns arrayOf(mockContentEntry)
        every { mockRm.orderEntries } returns emptyArray()

        // -- CompilerModuleExtension --
        val mockCompilerExt = mockk<CompilerModuleExtension>(relaxed = true)
        every { mockCompilerExt.compilerOutputUrl } returns null
        every { mockCompilerExt.compilerOutputUrlForTests } returns null
        every { mockCompilerExt.isCompilerOutputPathInherited } returns false
        mockkStatic(CompilerModuleExtension::class)
        every { CompilerModuleExtension.getInstance(mockModule) } returns mockCompilerExt

        // -- FacetManager --
        val mockFacetManager = mockk<FacetManager>(relaxed = true)
        every { mockFacetManager.allFacets } returns emptyArray()
        mockkStatic(FacetManager::class)
        every { FacetManager.getInstance(mockModule) } returns mockFacetManager

        // -- ReadAction: intercept and execute the lambda directly --
        mockkStatic(ReadAction::class)
        val computeSlot = slot<ThrowableComputable<Any, RuntimeException>>()
        every { ReadAction.compute(capture(computeSlot)) } answers {
            computeSlot.captured.compute()
        }

        val params = buildJsonObject { put("module", moduleName) }
        val result = executeModuleDetail(params, project)

        assertFalse(result.isError, "Expected no error but got: ${result.content}")
        assertTrue(
            result.content.contains(moduleName),
            "Expected module name '$moduleName' in content: ${result.content}"
        )
        assertTrue(
            result.content.contains("source"),
            "Expected 'source' root kind in content: ${result.content}"
        )
    }
}
