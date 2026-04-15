package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.ThrowableComputable
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.tools.AgentTool
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
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
 * Unit tests for [executeRemoveModuleDependency].
 *
 * Strategy: all IntelliJ APIs (ModuleManager, ReadAction, WriteCommandAction,
 * ModuleRootManager, ModuleRootModificationUtil) are static and mocked via mockkStatic.
 * [AgentTool] is mocked via MockK coEvery so requestApproval returns a controlled value.
 * [moduleExternalSystemId] is mocked via mockkStatic on the helpers file.
 * No real IntelliJ services are used.
 *
 * Test 1 — testMissingModuleParamReturnsError: no "module" param → isError=true
 * Test 2 — testMissingDependsOnParamReturnsError: no "dependsOn" param → isError=true
 * Test 3 — testModuleNotFoundReturnsError: findModuleByName returns null → isError=true, content has module name
 * Test 4 — testExternalSystemModuleBlocksWrite: moduleExternalSystemId returns "GRADLE" → isError=true, content contains "external" (case-insensitive)
 * Test 5 — testNoDependencyIsNoOp: existingCount=0 → isError=false, content contains "Nothing to remove"
 * Test 6 — testApprovalDeniedReturnsError: existingCount=1 → approval=DENIED → isError=true, content contains "denied"
 * Test 7 — testHappyPathRemovesDependency: existingCount=1 → approval=APPROVED → isError=false, content contains "Removed"
 */
class RemoveModuleDependencyActionTest {

    private lateinit var project: Project
    private lateinit var tool: AgentTool

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        tool = mockk(relaxed = true)
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
            put("dependsOn", "other-module")
        }
        val result = executeRemoveModuleDependency(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when 'module' param is missing, got: ${result.content}")
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 2 — Missing "dependsOn" param → isError=true
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testMissingDependsOnParamReturnsError() = runTest {
        val params = buildJsonObject {
            put("module", "my-module")
        }
        val result = executeRemoveModuleDependency(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when 'dependsOn' param is missing, got: ${result.content}")
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 3 — Module not found → isError=true with module name in content
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
            put("dependsOn", "other-module")
        }
        val result = executeRemoveModuleDependency(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when module is not found, got: ${result.content}")
        assertTrue(
            result.content.contains("no-such-module"),
            "Expected module name in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 4 — External-system module → isError=true with "external" (case-insensitive) in content
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testExternalSystemModuleBlocksWrite() = runTest {
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
            put("dependsOn", "other-module")
        }
        val result = executeRemoveModuleDependency(params, project, tool)
        assertTrue(result.isError, "Expected isError=true for external-system module, got: ${result.content}")
        assertTrue(
            result.content.lowercase().contains("external"),
            "Expected 'external' (case-insensitive) in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 5 — Dependency does not exist (idempotent no-op) → isError=false
    //
    // Module found + not external + existingCount=0 → "Nothing to remove"
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testNoDependencyIsNoOp() = runTest {
        val fakeModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)
        val fakeModuleRootManager = mockk<ModuleRootManager>(relaxed = true)

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

        // No existing dependency entries
        mockkStatic(ModuleRootManager::class)
        every { ModuleRootManager.getInstance(fakeModule) } returns fakeModuleRootManager
        every { fakeModuleRootManager.orderEntries } returns emptyArray()

        val params = buildJsonObject {
            put("module", "my-module")
            put("dependsOn", "other-module")
        }
        val result = executeRemoveModuleDependency(params, project, tool)
        assertFalse(result.isError, "Expected isError=false for no-op (no dependency), got: ${result.content}")
        assertTrue(
            result.content.contains("Nothing to remove", ignoreCase = true) ||
                result.content.contains("no dependency", ignoreCase = true),
            "Expected 'Nothing to remove' or 'no dependency' in content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 6 — Approval denied → isError=true with "denied" in content
    //
    // Module found + not external + existingCount=1 → approval=DENIED → error
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testApprovalDeniedReturnsError() = runTest {
        val fakeModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)
        val fakeModuleRootManager = mockk<ModuleRootManager>(relaxed = true)
        val fakeOrderEntry = mockk<ModuleOrderEntry>(relaxed = true)

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

        // One existing dependency entry
        mockkStatic(ModuleRootManager::class)
        every { ModuleRootManager.getInstance(fakeModule) } returns fakeModuleRootManager
        every { fakeOrderEntry.moduleName } returns "other-module"
        every { fakeModuleRootManager.orderEntries } returns arrayOf(fakeOrderEntry)

        // Approval denied
        coEvery {
            tool.requestApproval(any(), any(), any(), any())
        } returns ApprovalResult.DENIED

        val params = buildJsonObject {
            put("module", "my-module")
            put("dependsOn", "other-module")
        }
        val result = executeRemoveModuleDependency(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when approval is denied, got: ${result.content}")
        assertTrue(
            result.content.contains("denied", ignoreCase = true),
            "Expected 'denied' in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 7 — Happy path: dependency removed → isError=false, content contains "Removed"
    //
    // Module found + not external + existingCount=1 → approval=APPROVED
    // → WriteCommandAction runs Runnable → ModuleRootModificationUtil.updateModel invoked
    // → result says "Removed"
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testHappyPathRemovesDependency() = runTest {
        val fakeModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)
        val fakeModuleRootManager = mockk<ModuleRootManager>(relaxed = true)
        val fakeOrderEntry = mockk<ModuleOrderEntry>(relaxed = true)

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

        // One existing dependency entry matching the target
        mockkStatic(ModuleRootManager::class)
        every { ModuleRootManager.getInstance(fakeModule) } returns fakeModuleRootManager
        every { fakeOrderEntry.moduleName } returns "other-module"
        every { fakeModuleRootManager.orderEntries } returns arrayOf(fakeOrderEntry)

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

        // Mock ModuleRootModificationUtil.updateModel to run the lambda directly
        mockkStatic(ModuleRootModificationUtil::class)
        val modelConsumerSlot = slot<com.intellij.util.Consumer<com.intellij.openapi.roots.ModifiableRootModel>>()
        val fakeModifiableModel = mockk<com.intellij.openapi.roots.ModifiableRootModel>(relaxed = true)
        every { fakeModifiableModel.orderEntries } returns arrayOf(fakeOrderEntry)
        justRun { fakeModifiableModel.removeOrderEntry(any()) }
        every {
            ModuleRootModificationUtil.updateModel(eq(fakeModule), capture(modelConsumerSlot))
        } answers {
            modelConsumerSlot.captured.consume(fakeModifiableModel)
        }

        val params = buildJsonObject {
            put("module", "my-module")
            put("dependsOn", "other-module")
        }
        val result = executeRemoveModuleDependency(params, project, tool)
        assertFalse(result.isError, "Expected isError=false for successful removal, got: ${result.content}")
        assertTrue(
            result.content.contains("Removed", ignoreCase = true),
            "Expected 'Removed' in content, got: ${result.content}"
        )
    }
}
