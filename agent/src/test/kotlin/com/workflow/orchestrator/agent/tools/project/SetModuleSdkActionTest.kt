package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.ThrowableComputable
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
 * Unit tests for [executeSetModuleSdk].
 *
 * Strategy: all IntelliJ APIs (ModuleManager, ReadAction, WriteCommandAction,
 * ProjectJdkTable, ModuleRootModificationUtil) are static and mocked via mockkStatic.
 * [AgentTool] is mocked via MockK coEvery so requestApproval returns a controlled value.
 * [moduleExternalSystemId] is mocked via mockkStatic on the helpers file.
 * No real IntelliJ services are used.
 *
 * Test 1 — testMissingModuleParamReturnsError: no "module" param → isError=true
 * Test 2 — testModuleNotFoundReturnsError: findModuleByName returns null → isError=true, content has module name
 * Test 3 — testExternalSystemModuleBlocksWrite: moduleExternalSystemId returns "GRADLE" → isError=true, content contains "external" (case-insensitive)
 * Test 4 — testSdkNotFoundReturnsError: SDK name unknown → isError=true, content contains bad SDK name
 * Test 5 — testInheritPathApprovalApprovedReturnsSuccess: no sdkName → inherit → approval=APPROVED → isError=false, content contains "inherit"
 * Test 6 — testApprovalDeniedReturnsError: inherit path → approval=DENIED → isError=true, content contains "denied"
 * Test 7 — testHappyPathWithNamedSdk: corretto-17 found → approval=APPROVED → isError=false, content contains "corretto-17"
 */
class SetModuleSdkActionTest {

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
            put("sdkName", "corretto-17")
        }
        val result = executeSetModuleSdk(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when 'module' param is missing, got: ${result.content}")
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 2 — Module not found → isError=true with module name in content
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
            put("module", "no-such-module")
        }
        val result = executeSetModuleSdk(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when module is not found, got: ${result.content}")
        assertTrue(
            result.content.contains("no-such-module"),
            "Expected module name in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 3 — External-system module → isError=true with "external" (case-insensitive) in content
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testExternalSystemModuleBlocksWrite() = runTest {
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
        }
        val result = executeSetModuleSdk(params, project, tool)
        assertTrue(result.isError, "Expected isError=true for external-system module, got: ${result.content}")
        assertTrue(
            result.content.lowercase().contains("external"),
            "Expected 'external' (case-insensitive) in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 4 — SDK not found by name → isError=true, content contains the bad SDK name
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testSdkNotFoundReturnsError() = runTest {
        val fakeModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)
        val fakeTable = mockk<ProjectJdkTable>(relaxed = true)

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

        mockkStatic(ProjectJdkTable::class)
        every { ProjectJdkTable.getInstance() } returns fakeTable
        every { fakeTable.findJdk("bad-sdk") } returns null
        every { fakeTable.allJdks } returns emptyArray()

        val params = buildJsonObject {
            put("module", "my-module")
            put("sdkName", "bad-sdk")
        }
        val result = executeSetModuleSdk(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when SDK is not found, got: ${result.content}")
        assertTrue(
            result.content.contains("bad-sdk"),
            "Expected bad SDK name in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 5 — Inherit path (no sdkName) → approval=APPROVED → isError=false, content contains "inherit"
    //
    // Full mock chain: module found → not external → no sdkName → inherit path
    // → approval=APPROVED → WriteCommandAction runs Runnable → success
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testInheritPathApprovalApprovedReturnsSuccess() = runTest {
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
        every { moduleExternalSystemId(fakeModule) } returns null

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
        val consumerSlot = slot<com.intellij.util.Consumer<ModifiableRootModel>>()
        every {
            ModuleRootModificationUtil.updateModel(any(), capture(consumerSlot))
        } answers {
            consumerSlot.captured.consume(mockk(relaxed = true))
        }

        // No sdkName key → inherit path
        val params = buildJsonObject {
            put("module", "my-module")
        }
        val result = executeSetModuleSdk(params, project, tool)
        assertFalse(result.isError, "Expected isError=false for inherit path with approval, got: ${result.content}")
        assertTrue(
            result.content.contains("inherit", ignoreCase = true),
            "Expected 'inherit' in content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 6 — Approval denied → isError=true with "denied" in content
    //
    // Inherit path → approval=DENIED → error before write
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testApprovalDeniedReturnsError() = runTest {
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
        every { moduleExternalSystemId(fakeModule) } returns null

        // Approval denied
        coEvery {
            tool.requestApproval(any(), any(), any(), any())
        } returns ApprovalResult.DENIED

        // No sdkName → inherit path
        val params = buildJsonObject {
            put("module", "my-module")
        }
        val result = executeSetModuleSdk(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when approval is denied, got: ${result.content}")
        assertTrue(
            result.content.contains("denied", ignoreCase = true),
            "Expected 'denied' in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 7 — Happy path with named SDK → isError=false, content contains SDK name
    //
    // Module found → not external → SDK found → approval=APPROVED → write runs → success
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testHappyPathWithNamedSdk() = runTest {
        val fakeModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)
        val fakeTable = mockk<ProjectJdkTable>(relaxed = true)
        val fakeSdk = mockk<Sdk>(relaxed = true)

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

        mockkStatic(ProjectJdkTable::class)
        every { ProjectJdkTable.getInstance() } returns fakeTable
        every { fakeTable.findJdk("corretto-17") } returns fakeSdk

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
        val consumerSlot = slot<com.intellij.util.Consumer<ModifiableRootModel>>()
        every {
            ModuleRootModificationUtil.updateModel(any(), capture(consumerSlot))
        } answers {
            consumerSlot.captured.consume(mockk(relaxed = true))
        }

        val params = buildJsonObject {
            put("module", "my-module")
            put("sdkName", "corretto-17")
        }
        val result = executeSetModuleSdk(params, project, tool)
        assertFalse(result.isError, "Expected isError=false for successful SDK set, got: ${result.content}")
        assertTrue(
            result.content.contains("corretto-17"),
            "Expected SDK name 'corretto-17' in content, got: ${result.content}"
        )
    }
}
