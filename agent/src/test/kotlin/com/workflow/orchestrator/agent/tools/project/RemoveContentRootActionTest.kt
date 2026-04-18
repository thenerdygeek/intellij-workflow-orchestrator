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
 * Unit tests for [executeRemoveContentRoot].
 *
 * Strategy: all IntelliJ APIs (ModuleManager, ReadAction, WriteCommandAction,
 * ModuleRootManager, ModuleRootModificationUtil) are static and mocked via mockkStatic.
 * [AgentTool] is mocked via MockK coEvery so requestApproval returns a controlled value.
 * [moduleExternalSystemId] is mocked via mockkStatic on the helpers file.
 * No real IntelliJ services are used.
 *
 * Test 1 — testMissingModuleParamReturnsError: no "module" param → isError=true
 * Test 2 — testMissingPathParamReturnsError: no "path" param → isError=true
 * Test 3 — testModuleNotFoundReturnsError: findModuleByName returns null → isError=true, content has module name
 * Test 4 — testExternalSystemModuleReturnsError: moduleExternalSystemId returns "GRADLE" → isError=true, content contains "external"
 * Test 5 — testContentRootNotFoundReturnsError: ReadAction returns preCheck with targetEntry=null → isError=true, content contains "not found" and lists known roots
 * Test 6 — testApprovalDeniedReturnsError: full chain reaches approval gate → DENIED → isError=true, content contains "denied"
 * Test 7 — testHappyPathRemovesRoot: approval APPROVED → write runs → remainingCount=1 → isError=false, content contains "Removed"
 * Test 8 — testRemovingLastRootProducesWarning: approval APPROVED → write runs → remainingCount=0 → content contains "0 content roots"
 */
class RemoveContentRootActionTest {

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
            put("path", "/some/path")
        }
        val result = executeRemoveContentRoot(params, project, tool)
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
        val result = executeRemoveContentRoot(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when 'path' param is missing, got: ${result.content}")
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 3 — Module not found → isError=true, content contains module name
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testModuleNotFoundReturnsError() = runTest {
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)

        mockkStatic(ModuleManager::class)
        every { ModuleManager.getInstance(project) } returns fakeModuleManager
        every { fakeModuleManager.findModuleByName("no-such-module") } returns null

        mockkStatic(ReadAction::class)
        val computeSlot = slot<ThrowableComputable<Any, RuntimeException>>()
        every { ReadAction.compute(capture(computeSlot)) } answers {
            computeSlot.captured.compute()
        }

        val params = buildJsonObject {
            put("module", "no-such-module")
            put("path", "/some/path")
        }
        val result = executeRemoveContentRoot(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when module is not found, got: ${result.content}")
        assertTrue(
            result.content.contains("no-such-module"),
            "Expected module name in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 4 — External-system module → isError=true, content contains "external"
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testExternalSystemModuleReturnsError() = runTest {
        val fakeModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)

        mockkStatic(ModuleManager::class)
        every { ModuleManager.getInstance(project) } returns fakeModuleManager
        every { fakeModuleManager.findModuleByName("my-module") } returns fakeModule

        mockkStatic(ReadAction::class)
        val computeSlot = slot<ThrowableComputable<Any, RuntimeException>>()
        every { ReadAction.compute(capture(computeSlot)) } answers {
            computeSlot.captured.compute()
        }

        mockkStatic("com.workflow.orchestrator.agent.tools.project.ProjectStructureHelpersKt")
        every { moduleExternalSystemId(fakeModule) } returns "GRADLE"

        val params = buildJsonObject {
            put("module", "my-module")
            put("path", "/some/path")
        }
        val result = executeRemoveContentRoot(params, project, tool)
        assertTrue(result.isError, "Expected isError=true for external-system module, got: ${result.content}")
        assertTrue(
            result.content.lowercase().contains("external"),
            "Expected 'external' (case-insensitive) in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 5 — Content root not found (no match) → isError=true, content contains "not found"
    //          and lists known roots
    //
    // Module found + not external + ReadAction returns preCheck with targetEntry=null
    // + knownPaths = ["/some/other/path"] → error listing known root
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testContentRootNotFoundReturnsError() = runTest {
        val fakeModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)
        val fakeModuleRootManager = mockk<ModuleRootManager>(relaxed = true)
        val fakeKnownVirtualFile = mockk<VirtualFile>(relaxed = true)
        val fakeKnownEntry = mockk<ContentEntry>(relaxed = true)

        mockkStatic(ModuleManager::class)
        every { ModuleManager.getInstance(project) } returns fakeModuleManager
        every { fakeModuleManager.findModuleByName("my-module") } returns fakeModule

        // Set up ReadAction to execute lambdas directly
        mockkStatic(ReadAction::class)
        val computeSlot = slot<ThrowableComputable<Any, RuntimeException>>()
        every { ReadAction.compute(capture(computeSlot)) } answers {
            computeSlot.captured.compute()
        }

        mockkStatic("com.workflow.orchestrator.agent.tools.project.ProjectStructureHelpersKt")
        every { moduleExternalSystemId(fakeModule) } returns null

        // Set up ModuleRootManager: known entry at a different path, so no match
        mockkStatic(ModuleRootManager::class)
        every { ModuleRootManager.getInstance(fakeModule) } returns fakeModuleRootManager
        every { fakeKnownVirtualFile.path } returns "/some/other/path"
        every { fakeKnownEntry.file } returns fakeKnownVirtualFile
        every { fakeModuleRootManager.contentEntries } returns arrayOf(fakeKnownEntry)

        val params = buildJsonObject {
            put("module", "my-module")
            put("path", "/some/missing/path")
        }
        val result = executeRemoveContentRoot(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when content root not found, got: ${result.content}")
        assertTrue(
            result.content.lowercase().contains("not found") || result.content.lowercase().contains("no content root"),
            "Expected 'not found' or 'no content root' in error content, got: ${result.content}"
        )
        assertTrue(
            result.content.contains("/some/other/path"),
            "Expected known path '/some/other/path' listed in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 6 — Approval denied → isError=true, content contains "denied"
    //
    // Module found + not external + ReadAction returns entry found → approval gate → DENIED
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testApprovalDeniedReturnsError() = runTest {
        val fakeModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)
        val fakeModuleRootManager = mockk<ModuleRootManager>(relaxed = true)
        val fakeVirtualFile = mockk<VirtualFile>(relaxed = true)
        val fakeContentEntry = mockk<ContentEntry>(relaxed = true)

        mockkStatic(ModuleManager::class)
        every { ModuleManager.getInstance(project) } returns fakeModuleManager
        every { fakeModuleManager.findModuleByName("my-module") } returns fakeModule

        mockkStatic(ReadAction::class)
        val computeSlot = slot<ThrowableComputable<Any, RuntimeException>>()
        every { ReadAction.compute(capture(computeSlot)) } answers {
            computeSlot.captured.compute()
        }

        mockkStatic("com.workflow.orchestrator.agent.tools.project.ProjectStructureHelpersKt")
        every { moduleExternalSystemId(fakeModule) } returns null

        // Content root found — path matches the requested path
        mockkStatic(ModuleRootManager::class)
        every { ModuleRootManager.getInstance(fakeModule) } returns fakeModuleRootManager
        every { fakeVirtualFile.path } returns "/fake/project/src"
        every { fakeContentEntry.file } returns fakeVirtualFile
        every { fakeModuleRootManager.contentEntries } returns arrayOf(fakeContentEntry)

        // Approval denied
        coEvery {
            tool.requestApproval(any(), any(), any(), any())
        } returns ApprovalResult.DENIED

        val params = buildJsonObject {
            put("module", "my-module")
            put("path", "/fake/project/src")
        }
        val result = executeRemoveContentRoot(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when approval is denied, got: ${result.content}")
        assertTrue(
            result.content.contains("denied", ignoreCase = true),
            "Expected 'denied' in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 7 — Happy path removes the root → isError=false, content contains "Removed"
    //
    // Module found + not external + content root found + approval APPROVED
    // → WriteCommandAction executes inline → updateModel consumer runs → remainingCount=1
    // → success result with "Removed"
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testHappyPathRemovesRoot() = runTest {
        val fakeModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)
        val fakeModuleRootManager = mockk<ModuleRootManager>(relaxed = true)
        val fakeVirtualFile = mockk<VirtualFile>(relaxed = true)
        val fakeContentEntry = mockk<ContentEntry>(relaxed = true)

        mockkStatic(ModuleManager::class)
        every { ModuleManager.getInstance(project) } returns fakeModuleManager
        every { fakeModuleManager.findModuleByName("my-module") } returns fakeModule

        mockkStatic(ReadAction::class)
        val computeSlot = slot<ThrowableComputable<Any, RuntimeException>>()
        every { ReadAction.compute(capture(computeSlot)) } answers {
            computeSlot.captured.compute()
        }

        mockkStatic("com.workflow.orchestrator.agent.tools.project.ProjectStructureHelpersKt")
        every { moduleExternalSystemId(fakeModule) } returns null

        // Content root found
        mockkStatic(ModuleRootManager::class)
        every { ModuleRootManager.getInstance(fakeModule) } returns fakeModuleRootManager
        every { fakeVirtualFile.path } returns "/fake/project/src"
        every { fakeContentEntry.file } returns fakeVirtualFile
        every { fakeModuleRootManager.contentEntries } returns arrayOf(fakeContentEntry)

        // Approval granted
        coEvery {
            tool.requestApproval(any(), any(), any(), any())
        } returns ApprovalResult.APPROVED

        // WriteCommandAction executes Runnable inline
        mockkStatic(WriteCommandAction::class)
        every {
            WriteCommandAction.runWriteCommandAction(any(), any(), any(), any<Runnable>())
        } answers {
            (it.invocation.args[3] as Runnable).run()
        }

        // updateModel runs consumer with a mock model that has 1 remaining entry after removal
        mockkStatic(ModuleRootModificationUtil::class)
        val consumerSlot = slot<com.intellij.util.Consumer<ModifiableRootModel>>()
        every {
            ModuleRootModificationUtil.updateModel(any(), capture(consumerSlot))
        } answers {
            val fakeModel = mockk<ModifiableRootModel>(relaxed = true)
            val remainingEntry = mockk<ContentEntry>(relaxed = true)
            every { fakeModel.contentEntries } returns arrayOf(remainingEntry)
            consumerSlot.captured.consume(fakeModel)
        }

        val params = buildJsonObject {
            put("module", "my-module")
            put("path", "/fake/project/src")
        }
        val result = executeRemoveContentRoot(params, project, tool)
        assertFalse(result.isError, "Expected isError=false for successful removal, got: ${result.content}")
        assertTrue(
            result.content.contains("Removed", ignoreCase = true),
            "Expected 'Removed' in content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 8 — Removing last content root → isError=false, content contains "0 content roots" warning
    //
    // Same as test 7 but updateModel consumer returns model with 0 remaining entries
    // → success result with warning "0 content roots"
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testRemovingLastRootProducesWarning() = runTest {
        val fakeModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)
        val fakeModuleRootManager = mockk<ModuleRootManager>(relaxed = true)
        val fakeVirtualFile = mockk<VirtualFile>(relaxed = true)
        val fakeContentEntry = mockk<ContentEntry>(relaxed = true)

        mockkStatic(ModuleManager::class)
        every { ModuleManager.getInstance(project) } returns fakeModuleManager
        every { fakeModuleManager.findModuleByName("my-module") } returns fakeModule

        mockkStatic(ReadAction::class)
        val computeSlot = slot<ThrowableComputable<Any, RuntimeException>>()
        every { ReadAction.compute(capture(computeSlot)) } answers {
            computeSlot.captured.compute()
        }

        mockkStatic("com.workflow.orchestrator.agent.tools.project.ProjectStructureHelpersKt")
        every { moduleExternalSystemId(fakeModule) } returns null

        // Content root found
        mockkStatic(ModuleRootManager::class)
        every { ModuleRootManager.getInstance(fakeModule) } returns fakeModuleRootManager
        every { fakeVirtualFile.path } returns "/fake/project/src"
        every { fakeContentEntry.file } returns fakeVirtualFile
        every { fakeModuleRootManager.contentEntries } returns arrayOf(fakeContentEntry)

        // Approval granted
        coEvery {
            tool.requestApproval(any(), any(), any(), any())
        } returns ApprovalResult.APPROVED

        // WriteCommandAction executes Runnable inline
        mockkStatic(WriteCommandAction::class)
        every {
            WriteCommandAction.runWriteCommandAction(any(), any(), any(), any<Runnable>())
        } answers {
            (it.invocation.args[3] as Runnable).run()
        }

        // updateModel runs consumer with a mock model that has 0 remaining entries after removal
        mockkStatic(ModuleRootModificationUtil::class)
        val consumerSlot = slot<com.intellij.util.Consumer<ModifiableRootModel>>()
        every {
            ModuleRootModificationUtil.updateModel(any(), capture(consumerSlot))
        } answers {
            val fakeModel = mockk<ModifiableRootModel>(relaxed = true)
            every { fakeModel.contentEntries } returns emptyArray()
            consumerSlot.captured.consume(fakeModel)
        }

        val params = buildJsonObject {
            put("module", "my-module")
            put("path", "/fake/project/src")
        }
        val result = executeRemoveContentRoot(params, project, tool)
        assertFalse(result.isError, "Expected isError=false even when last root removed, got: ${result.content}")
        assertTrue(
            result.content.contains("0 content roots"),
            "Expected '0 content roots' warning in content, got: ${result.content}"
        )
    }
}
