package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.tools.AgentTool
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [executeAddContentRoot].
 *
 * Strategy: all IntelliJ APIs (ModuleManager, ReadAction, WriteCommandAction,
 * LocalFileSystem, ModuleRootManager, VfsUtilCore, ModuleRootModificationUtil)
 * are static and mocked via mockkStatic.
 * [AgentTool] is mocked via MockK coEvery so requestApproval returns a controlled value.
 * [moduleExternalSystemId] is mocked via mockkStatic on the helpers file.
 * No real IntelliJ services are used.
 *
 * Test 1  — testMissingModuleParamReturnsError: no "module" param → isError=true
 * Test 2  — testMissingPathParamReturnsError: no "path" param → isError=true
 * Test 3  — testModuleNotFoundReturnsError: findModuleByName returns null → isError=true, content has module name
 * Test 4  — testExternalSystemModuleBlocksAddition: moduleExternalSystemId returns "GRADLE" → isError=true, content contains "external"
 * Test 5  — testPathNotFoundReturnsError: VFS returns null → isError=true, content contains "not found"
 * Test 6  — testPathNotADirectoryReturnsError: vFile.isDirectory=false → isError=true, content contains "not a directory" (case-insensitive)
 * Test 7  — testAlreadyContentRootIsNoOp: exact-match check fires → isError=false, content contains "already registered" or "No change"
 * Test 8  — testOverlappingContentRootReturnsError: ancestor check returns true → isError=true, content contains "overlap" (case-insensitive)
 * Test 9  — testApprovalDeniedReturnsError: full chain → approval=DENIED → isError=true, content contains "denied"
 * Test 10 — testHappyPath: full chain → approval=APPROVED → isError=false, content contains relative path
 */
class AddContentRootActionTest {

    private lateinit var project: Project
    private lateinit var tool: AgentTool

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        tool = mockk(relaxed = true)
        every { project.basePath } returns "/fake/project"
        // Default: approve (overridden per-test where needed)
        coEvery {
            tool.requestApproval(any(), any(), any(), any())
        } returns ApprovalResult.APPROVED
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Wires ReadAction.compute to execute the captured lambda inline. */
    private fun mockReadAction() {
        mockkStatic(ReadAction::class)
        val computeSlot = slot<ThrowableComputable<Any, RuntimeException>>()
        every { ReadAction.compute(capture(computeSlot)) } answers {
            computeSlot.captured.compute()
        }
    }

    /** Wires ModuleManager.getInstance to return [fakeManager]. */
    private fun mockModuleManager(fakeManager: ModuleManager) {
        mockkStatic(ModuleManager::class)
        every { ModuleManager.getInstance(project) } returns fakeManager
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 1 — Missing "module" param → isError=true
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testMissingModuleParamReturnsError() = runTest {
        val params = buildJsonObject {
            put("path", "/some/dir")
        }
        val result = executeAddContentRoot(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when 'module' param is missing, got: ${result.content}")
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 2 — Missing "path" param → isError=true
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testMissingPathParamReturnsError() = runTest {
        val params = buildJsonObject {
            put("module", "my-module")
        }
        val result = executeAddContentRoot(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when 'path' param is missing, got: ${result.content}")
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 3 — Module not found → isError=true with module name in content
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testModuleNotFoundReturnsError() = runTest {
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)
        mockModuleManager(fakeModuleManager)
        every { fakeModuleManager.findModuleByName("no-such-module") } returns null
        mockReadAction()

        val params = buildJsonObject {
            put("module", "no-such-module")
            put("path", "/any/path")
        }
        val result = executeAddContentRoot(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when module is not found, got: ${result.content}")
        assertTrue(
            result.content.contains("no-such-module"),
            "Expected module name 'no-such-module' in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 4 — External-system module → isError=true, content contains "external" (case-insensitive)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testExternalSystemModuleBlocksAddition() = runTest {
        val fakeModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)
        mockModuleManager(fakeModuleManager)
        every { fakeModuleManager.findModuleByName("my-module") } returns fakeModule
        mockReadAction()

        mockkStatic("com.workflow.orchestrator.agent.tools.project.ProjectStructureHelpersKt")
        every { moduleExternalSystemId(fakeModule) } returns "GRADLE"

        val params = buildJsonObject {
            put("module", "my-module")
            put("path", "/some/dir")
        }
        val result = executeAddContentRoot(params, project, tool)
        assertTrue(result.isError, "Expected isError=true for external system module, got: ${result.content}")
        assertTrue(
            result.content.lowercase().contains("external"),
            "Expected 'external' (case-insensitive) in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 5 — Path not found (VFS returns null) → isError=true, content contains "not found"
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testPathNotFoundReturnsError() = runTest {
        val fakeModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)
        val fakeLocalFileSystem = mockk<LocalFileSystem>(relaxed = true)
        mockModuleManager(fakeModuleManager)
        every { fakeModuleManager.findModuleByName("my-module") } returns fakeModule
        mockReadAction()

        mockkStatic("com.workflow.orchestrator.agent.tools.project.ProjectStructureHelpersKt")
        every { moduleExternalSystemId(fakeModule) } returns null

        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns fakeLocalFileSystem
        every { fakeLocalFileSystem.refreshAndFindFileByIoFile(any()) } returns null

        val params = buildJsonObject {
            put("module", "my-module")
            put("path", "/nonexistent/dir")
        }
        val result = executeAddContentRoot(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when path is not found, got: ${result.content}")
        assertTrue(
            result.content.lowercase().contains("not found"),
            "Expected 'not found' in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 6 — Path is not a directory → isError=true, content contains "not a directory" (case-insensitive)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testPathNotADirectoryReturnsError() = runTest {
        val fakeModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)
        val fakeLocalFileSystem = mockk<LocalFileSystem>(relaxed = true)
        val fakeVFile = mockk<VirtualFile>(relaxed = true)
        mockModuleManager(fakeModuleManager)
        every { fakeModuleManager.findModuleByName("my-module") } returns fakeModule
        mockReadAction()

        mockkStatic("com.workflow.orchestrator.agent.tools.project.ProjectStructureHelpersKt")
        every { moduleExternalSystemId(fakeModule) } returns null

        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns fakeLocalFileSystem
        every { fakeLocalFileSystem.refreshAndFindFileByIoFile(any()) } returns fakeVFile
        every { fakeVFile.isDirectory } returns false

        val params = buildJsonObject {
            put("module", "my-module")
            put("path", "/some/file.txt")
        }
        val result = executeAddContentRoot(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when path is not a directory, got: ${result.content}")
        assertTrue(
            result.content.lowercase().contains("not a directory"),
            "Expected 'not a directory' in error content (case-insensitive), got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 7 — Already a content root → isError=false, content contains "already registered" or "No change"
    //
    // Exact-match check fires because fakeContentEntry.file.path == fakeVFile.path.
    // VfsUtilCore.isAncestor is never reached.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testAlreadyContentRootIsNoOp() = runTest {
        val fakeModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)
        val fakeLocalFileSystem = mockk<LocalFileSystem>(relaxed = true)
        val fakeVFile = mockk<VirtualFile>(relaxed = true)
        val fakeContentEntry = mockk<ContentEntry>(relaxed = true)
        val fakeRootManager = mockk<ModuleRootManager>(relaxed = true)
        mockModuleManager(fakeModuleManager)
        every { fakeModuleManager.findModuleByName("my-module") } returns fakeModule
        mockReadAction()

        mockkStatic("com.workflow.orchestrator.agent.tools.project.ProjectStructureHelpersKt")
        every { moduleExternalSystemId(fakeModule) } returns null

        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns fakeLocalFileSystem
        every { fakeLocalFileSystem.refreshAndFindFileByIoFile(any()) } returns fakeVFile
        every { fakeVFile.isDirectory } returns true
        every { fakeVFile.path } returns "/fake/project/extra"

        // Exact match: content entry's file path == vFile.path
        every { fakeContentEntry.file } returns fakeVFile
        mockkStatic(ModuleRootManager::class)
        every { ModuleRootManager.getInstance(fakeModule) } returns fakeRootManager
        every { fakeRootManager.contentEntries } returns arrayOf(fakeContentEntry)

        val params = buildJsonObject {
            put("module", "my-module")
            put("path", "/fake/project/extra")
        }
        val result = executeAddContentRoot(params, project, tool)
        assertFalse(result.isError, "Expected isError=false when content root already registered, got: ${result.content}")
        assertTrue(
            result.content.contains("already registered", ignoreCase = true) ||
                result.content.contains("No change", ignoreCase = true),
            "Expected 'already registered' or 'No change' in content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 8 — Overlapping content root → isError=true, content contains "overlap" (case-insensitive)
    //
    // Exact match is false; VfsUtilCore.isAncestor(existing, vFile, false) returns true.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testOverlappingContentRootReturnsError() = runTest {
        val fakeModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)
        val fakeLocalFileSystem = mockk<LocalFileSystem>(relaxed = true)
        val fakeVFile = mockk<VirtualFile>(relaxed = true)
        val fakeExistingVFile = mockk<VirtualFile>(relaxed = true)
        val fakeContentEntry = mockk<ContentEntry>(relaxed = true)
        val fakeRootManager = mockk<ModuleRootManager>(relaxed = true)
        mockModuleManager(fakeModuleManager)
        every { fakeModuleManager.findModuleByName("my-module") } returns fakeModule
        mockReadAction()

        mockkStatic("com.workflow.orchestrator.agent.tools.project.ProjectStructureHelpersKt")
        every { moduleExternalSystemId(fakeModule) } returns null

        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns fakeLocalFileSystem
        every { fakeLocalFileSystem.refreshAndFindFileByIoFile(any()) } returns fakeVFile
        every { fakeVFile.isDirectory } returns true
        every { fakeVFile.path } returns "/fake/project/new-root"

        // Existing content entry has a different path (no exact match)
        every { fakeExistingVFile.path } returns "/fake/project/parent"
        every { fakeContentEntry.file } returns fakeExistingVFile
        mockkStatic(ModuleRootManager::class)
        every { ModuleRootManager.getInstance(fakeModule) } returns fakeRootManager
        every { fakeRootManager.contentEntries } returns arrayOf(fakeContentEntry)

        // Ancestor check returns true → overlap
        mockkStatic(VfsUtilCore::class)
        every {
            VfsUtilCore.isAncestor(any<VirtualFile>(), any<VirtualFile>(), any<Boolean>())
        } returns true

        val params = buildJsonObject {
            put("module", "my-module")
            put("path", "/fake/project/new-root")
        }
        val result = executeAddContentRoot(params, project, tool)
        assertTrue(result.isError, "Expected isError=true for overlapping content root, got: ${result.content}")
        assertTrue(
            result.content.lowercase().contains("overlap"),
            "Expected 'overlap' in error content (case-insensitive), got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 9 — Approval denied → isError=true, content contains "denied"
    //
    // Full mock chain passes all checks → approval gate → DENIED → error
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testApprovalDeniedReturnsError() = runTest {
        val fakeModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)
        val fakeLocalFileSystem = mockk<LocalFileSystem>(relaxed = true)
        val fakeVFile = mockk<VirtualFile>(relaxed = true)
        val fakeRootManager = mockk<ModuleRootManager>(relaxed = true)
        mockModuleManager(fakeModuleManager)
        every { fakeModuleManager.findModuleByName("my-module") } returns fakeModule
        mockReadAction()

        mockkStatic("com.workflow.orchestrator.agent.tools.project.ProjectStructureHelpersKt")
        every { moduleExternalSystemId(fakeModule) } returns null

        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns fakeLocalFileSystem
        every { fakeLocalFileSystem.refreshAndFindFileByIoFile(any()) } returns fakeVFile
        every { fakeVFile.isDirectory } returns true
        every { fakeVFile.path } returns "/fake/project/brand-new"

        // No existing content entries → no exact match, no overlap
        mockkStatic(ModuleRootManager::class)
        every { ModuleRootManager.getInstance(fakeModule) } returns fakeRootManager
        every { fakeRootManager.contentEntries } returns emptyArray()

        mockkStatic(VfsUtilCore::class)
        every {
            VfsUtilCore.isAncestor(any<VirtualFile>(), any<VirtualFile>(), any<Boolean>())
        } returns false

        // Approval denied
        coEvery {
            tool.requestApproval(any(), any(), any(), any())
        } returns ApprovalResult.DENIED

        val params = buildJsonObject {
            put("module", "my-module")
            put("path", "/fake/project/brand-new")
        }
        val result = executeAddContentRoot(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when approval is denied, got: ${result.content}")
        assertTrue(
            result.content.contains("denied", ignoreCase = true),
            "Expected 'denied' in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 10 — Happy path → isError=false, content contains relative path
    //
    // Full chain passes: module found → not external → VFS resolves → isDirectory
    // → no exact match → no overlap → approval=APPROVED → write runs → success
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testHappyPath() = runTest {
        val fakeModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)
        val fakeLocalFileSystem = mockk<LocalFileSystem>(relaxed = true)
        val fakeVFile = mockk<VirtualFile>(relaxed = true)
        val fakeRootManager = mockk<ModuleRootManager>(relaxed = true)
        mockModuleManager(fakeModuleManager)
        every { fakeModuleManager.findModuleByName("my-module") } returns fakeModule
        mockReadAction()

        mockkStatic("com.workflow.orchestrator.agent.tools.project.ProjectStructureHelpersKt")
        every { moduleExternalSystemId(fakeModule) } returns null

        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns fakeLocalFileSystem
        every { fakeLocalFileSystem.refreshAndFindFileByIoFile(any()) } returns fakeVFile
        every { fakeVFile.isDirectory } returns true
        every { fakeVFile.path } returns "/fake/project/generated"

        // No existing content entries → no conflict
        mockkStatic(ModuleRootManager::class)
        every { ModuleRootManager.getInstance(fakeModule) } returns fakeRootManager
        every { fakeRootManager.contentEntries } returns emptyArray()

        mockkStatic(VfsUtilCore::class)
        every {
            VfsUtilCore.isAncestor(any<VirtualFile>(), any<VirtualFile>(), any<Boolean>())
        } returns false

        // Approval granted
        coEvery {
            tool.requestApproval(any(), any(), any(), any())
        } returns ApprovalResult.APPROVED

        // Mock WriteCommandAction to execute the Runnable directly
        mockkStatic(WriteCommandAction::class)
        every {
            WriteCommandAction.runWriteCommandAction(any(), any(), any(), any<Runnable>())
        } answers {
            (it.invocation.args[3] as Runnable).run()
        }

        // Mock ModuleRootModificationUtil.updateModel to run the consumer directly
        mockkStatic(ModuleRootModificationUtil::class)
        val consumerSlot = slot<com.intellij.util.Consumer<ModifiableRootModel>>()
        every {
            ModuleRootModificationUtil.updateModel(any(), capture(consumerSlot))
        } answers {
            consumerSlot.captured.consume(mockk(relaxed = true))
        }

        val params = buildJsonObject {
            put("module", "my-module")
            put("path", "/fake/project/generated")
        }
        val result = executeAddContentRoot(params, project, tool)
        assertFalse(result.isError, "Expected isError=false for happy path, got: ${result.content}")
        // relativizeToProject("/fake/project/generated", "/fake/project") → "generated"
        assertTrue(
            result.content.contains("generated"),
            "Expected relative path 'generated' in content, got: ${result.content}"
        )
    }
}
