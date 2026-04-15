package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModuleRootManager
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [executeAddSourceRoot].
 *
 * Strategy: all IntelliJ APIs (ModuleManager, ReadAction) are static and mocked via mockkStatic.
 * [AgentTool] is mocked via MockK coEvery so requestApproval returns a controlled value.
 * [moduleExternalSystemId] is mocked via mockkStatic on the helpers file.
 * No real IntelliJ services are used.
 *
 * Test 1 — testMissingModuleParamReturnsError: no "module" param → isError=true
 * Test 2 — testMissingPathParamReturnsError: no "path" param → isError=true
 * Test 3 — testInvalidKindReturnsError: kind="bogus" → isError=true with "bogus" in content
 * Test 4 — testExternalSystemModuleBlocksAddition: moduleExternalSystemId returns "GRADLE" → isError=true with "external system"
 * Test 5 — testApprovalDeniedReturnsError: full mock chain reaches approval gate → approval=DENIED → isError=true with "denied"
 * Test 6 — testModuleNotFoundReturnsError: findModuleByName returns null → isError=true with module name in content
 */
class AddSourceRootActionTest {

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

    // ────────────────────────────────────────────────────────────────────────
    // Test 1 — Missing "module" param → isError=true
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testMissingModuleParamReturnsError() = runTest {
        val params = buildJsonObject {
            put("path", "src/main/kotlin")
            put("kind", "source")
        }
        val result = executeAddSourceRoot(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when 'module' param is missing, got: ${result.content}")
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 2 — Missing "path" param → isError=true
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testMissingPathParamReturnsError() = runTest {
        val params = buildJsonObject {
            put("module", "my-module")
            put("kind", "source")
        }
        val result = executeAddSourceRoot(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when 'path' param is missing, got: ${result.content}")
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 3 — Invalid kind → isError=true with "bogus" in content
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testInvalidKindReturnsError() = runTest {
        val params = buildJsonObject {
            put("module", "my-module")
            put("path", "src/main/kotlin")
            put("kind", "bogus")
        }
        val result = executeAddSourceRoot(params, project, tool)
        assertTrue(result.isError, "Expected isError=true for invalid kind, got: ${result.content}")
        assertTrue(
            result.content.contains("bogus"),
            "Expected 'bogus' in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 4 — External system module → isError=true with "external system" in content
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testExternalSystemModuleBlocksAddition() = runTest {
        val fakeModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)

        mockkStatic(ModuleManager::class)
        every { ModuleManager.getInstance(project) } returns fakeModuleManager
        every { fakeModuleManager.findModuleByName("my-module") } returns fakeModule

        // Mock ReadAction to execute the lambda directly
        mockkStatic(ReadAction::class)
        val computeSlot = slot<ThrowableComputable<Any, RuntimeException>>()
        every { ReadAction.compute(capture(computeSlot)) } answers {
            computeSlot.captured.compute()
        }

        mockkStatic("com.workflow.orchestrator.agent.tools.project.ProjectStructureHelpersKt")
        every { moduleExternalSystemId(fakeModule) } returns "GRADLE"

        val params = buildJsonObject {
            put("module", "my-module")
            put("path", "src/main/kotlin")
            put("kind", "source")
        }
        val result = executeAddSourceRoot(params, project, tool)
        assertTrue(result.isError, "Expected isError=true for external system module, got: ${result.content}")
        assertTrue(
            result.content.lowercase().contains("external system"),
            "Expected 'external system' in error content (case-insensitive), got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 5 — Approval denied → isError=true with "denied" in content
    //
    // Mocks the full call chain through to the approval gate:
    //   module found → not external → VFS returns vFile → content entry found
    //   → approval=DENIED → isError=true with "denied" in content
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testApprovalDeniedReturnsError() = runTest {
        val fakeModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)
        val fakeLocalFileSystem = mockk<LocalFileSystem>(relaxed = true)
        val fakeVirtualFile = mockk<VirtualFile>(relaxed = true)
        val fakeContentEntry = mockk<ContentEntry>(relaxed = true)
        val fakeModuleRootManager = mockk<ModuleRootManager>(relaxed = true)

        mockkStatic(ModuleManager::class)
        every { ModuleManager.getInstance(project) } returns fakeModuleManager
        every { fakeModuleManager.findModuleByName("my-module") } returns fakeModule

        // Mock ReadAction to execute the lambda directly
        mockkStatic(ReadAction::class)
        val computeSlot = slot<ThrowableComputable<Any, RuntimeException>>()
        every { ReadAction.compute(capture(computeSlot)) } answers {
            computeSlot.captured.compute()
        }

        mockkStatic("com.workflow.orchestrator.agent.tools.project.ProjectStructureHelpersKt")
        every { moduleExternalSystemId(fakeModule) } returns null

        // VFS resolves successfully to a mock VirtualFile
        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns fakeLocalFileSystem
        every { fakeLocalFileSystem.refreshAndFindFileByIoFile(any()) } returns fakeVirtualFile

        // Content entry is found — file is under a content root
        mockkStatic(ModuleRootManager::class)
        every { ModuleRootManager.getInstance(fakeModule) } returns fakeModuleRootManager
        every { fakeContentEntry.file } returns fakeVirtualFile
        every { fakeModuleRootManager.contentEntries } returns arrayOf(fakeContentEntry)

        mockkStatic(VfsUtilCore::class)
        every {
            VfsUtilCore.isAncestor(any<VirtualFile>(), any<VirtualFile>(), any<Boolean>())
        } returns true

        // Approval denied
        coEvery {
            tool.requestApproval(any(), any(), any(), any())
        } returns ApprovalResult.DENIED

        val params = buildJsonObject {
            put("module", "my-module")
            put("path", "/some/path/for/test")
            put("kind", "source")
        }
        val result = executeAddSourceRoot(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when approval is denied, got: ${result.content}")
        assertTrue(
            result.content.contains("denied", ignoreCase = true),
            "Expected 'denied' in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 6 — Module not found → isError=true with module name in content
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testModuleNotFoundReturnsError() = runTest {
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)

        mockkStatic(ModuleManager::class)
        every { ModuleManager.getInstance(project) } returns fakeModuleManager
        every { fakeModuleManager.findModuleByName("no-such-module") } returns null

        // Mock ReadAction to execute the lambda directly
        mockkStatic(ReadAction::class)
        val computeSlot = slot<ThrowableComputable<Any, RuntimeException>>()
        every { ReadAction.compute(capture(computeSlot)) } answers {
            computeSlot.captured.compute()
        }

        val params = buildJsonObject {
            put("action", "add_source_root")
            put("module", "no-such-module")
            put("path", "/any/path")
            put("kind", "source")
        }
        val res = executeAddSourceRoot(params, project, tool)
        assertTrue(res.isError, "Expected isError=true when module is not found, got: ${res.content}")
        assertTrue(
            res.content.contains("no-such-module"),
            "Expected module name 'no-such-module' in error content, got: ${res.content}"
        )
    }
}
