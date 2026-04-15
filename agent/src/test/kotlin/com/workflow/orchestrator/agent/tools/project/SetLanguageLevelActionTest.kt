package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
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
 * Unit tests for [executeSetLanguageLevel].
 *
 * Strategy: all IntelliJ APIs (ModuleManager, ReadAction, WriteCommandAction,
 * ModuleRootModificationUtil) are static and mocked via mockkStatic.
 * [AgentTool] is mocked via MockK coEvery so requestApproval returns a controlled value.
 * [moduleExternalSystemId] is mocked via mockkStatic on the helpers file.
 * [applyLanguageLevelToModule] is mocked via mockkStatic on SetLanguageLevelActionKt so
 * the reflection path (which cannot succeed against MockK mock objects) returns null (success).
 * No real IntelliJ services are used.
 *
 * Test 1 — testMissingModuleParamReturnsError: no "module" param → isError=true
 * Test 2 — testInvalidLanguageLevelReturnsError: levelRaw="Java99" → parseLanguageLevel returns null → isError=true, content contains "Java99"
 * Test 3 — testModuleNotFoundReturnsError: findModuleByName returns null → isError=true, content contains module name
 * Test 4 — testExternalSystemModuleBlocksWrite: moduleExternalSystemId returns "GRADLE" → isError=true, content contains "external" (case-insensitive)
 * Test 5 — testValidLevelApprovalApprovedReturnsSuccess: level="17" → approval=APPROVED → isError=false, content contains "JDK_17"
 * Test 6 — testInheritLevelApprovalApprovedReturnsSuccess: no languageLevel → inherit → approval=APPROVED → isError=false, content contains "inherit"
 * Test 7 — testApprovalDeniedReturnsError: inherit path → approval=DENIED → isError=true, content contains "denied"
 */
class SetLanguageLevelActionTest {

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
            put("languageLevel", "17")
        }
        val result = executeSetLanguageLevel(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when 'module' param is missing, got: ${result.content}")
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 2 — Invalid language level string → isError=true, content contains "Java99"
    //
    // parseLanguageLevel is a pure function — let it run for real since "Java99" will
    // genuinely return null without needing any IntelliJ APIs.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testInvalidLanguageLevelReturnsError() = runTest {
        val params = buildJsonObject {
            put("module", "my-module")
            put("languageLevel", "Java99")
        }
        val result = executeSetLanguageLevel(params, project, tool)
        assertTrue(result.isError, "Expected isError=true for invalid language level, got: ${result.content}")
        assertTrue(
            result.content.contains("Java99"),
            "Expected 'Java99' in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 3 — Module not found → isError=true with module name in content
    //
    // We omit languageLevel to take the inherit path (no parse needed) so only the
    // module lookup matters.
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
        val result = executeSetLanguageLevel(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when module is not found, got: ${result.content}")
        assertTrue(
            result.content.contains("no-such-module"),
            "Expected module name in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 4 — External-system module → isError=true, content contains "external" (case-insensitive)
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
        val result = executeSetLanguageLevel(params, project, tool)
        assertTrue(result.isError, "Expected isError=true for external-system module, got: ${result.content}")
        assertTrue(
            result.content.lowercase().contains("external"),
            "Expected 'external' (case-insensitive) in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 5 — Valid level "17" → approval=APPROVED → isError=false, content contains "JDK_17"
    //
    // Full mock chain: module found → not external → level parsed →
    // approval=APPROVED → WriteCommandAction runs Runnable → updateModel runs consumer
    // → applyLanguageLevelToModule mocked to return null (success) → success result
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testValidLevelApprovalApprovedReturnsSuccess() = runTest {
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

        // Mock ModuleRootModificationUtil.updateModel to run the consumer directly
        mockkStatic(ModuleRootModificationUtil::class)
        val consumerSlot = slot<com.intellij.util.Consumer<ModifiableRootModel>>()
        every {
            ModuleRootModificationUtil.updateModel(any(), capture(consumerSlot))
        } answers {
            consumerSlot.captured.consume(mockk(relaxed = true))
        }

        // Mock the reflection helper to return null (success) — real reflection cannot
        // succeed against MockK mock objects (the mock class has no getModuleExtension method).
        mockkStatic("com.workflow.orchestrator.agent.tools.project.SetLanguageLevelActionKt")
        every { applyLanguageLevelToModule(any(), any(), any()) } returns null

        val params = buildJsonObject {
            put("module", "my-module")
            put("languageLevel", "17")
        }
        val result = executeSetLanguageLevel(params, project, tool)
        assertFalse(result.isError, "Expected isError=false for valid level with approval, got: ${result.content}")
        assertTrue(
            result.content.contains("JDK_17"),
            "Expected 'JDK_17' in content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 6 — Empty languageLevel (inherit) → approval=APPROVED → isError=false, content contains "inherit"
    //
    // No languageLevel param → inherit path → level=null → descr="inherit from project"
    // → approval=APPROVED → write runs → applyLanguageLevelToModule mocked null → success
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testInheritLevelApprovalApprovedReturnsSuccess() = runTest {
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

        // Mock ModuleRootModificationUtil.updateModel to run the consumer directly
        mockkStatic(ModuleRootModificationUtil::class)
        val consumerSlot = slot<com.intellij.util.Consumer<ModifiableRootModel>>()
        every {
            ModuleRootModificationUtil.updateModel(any(), capture(consumerSlot))
        } answers {
            consumerSlot.captured.consume(mockk(relaxed = true))
        }

        // Mock the reflection helper to return null (success) — real reflection cannot
        // succeed against MockK mock objects (the mock class has no getModuleExtension method).
        mockkStatic("com.workflow.orchestrator.agent.tools.project.SetLanguageLevelActionKt")
        every { applyLanguageLevelToModule(any(), any(), any()) } returns null

        // No languageLevel param → inherit
        val params = buildJsonObject {
            put("module", "my-module")
        }
        val result = executeSetLanguageLevel(params, project, tool)
        assertFalse(result.isError, "Expected isError=false for inherit path with approval, got: ${result.content}")
        assertTrue(
            result.content.contains("inherit", ignoreCase = true),
            "Expected 'inherit' in content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 7 — Approval denied → isError=true, content contains "denied"
    //
    // Module found → not external → inherit path → approval=DENIED → error before write
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

        // No languageLevel → inherit path
        val params = buildJsonObject {
            put("module", "my-module")
        }
        val result = executeSetLanguageLevel(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when approval is denied, got: ${result.content}")
        assertTrue(
            result.content.contains("denied", ignoreCase = true),
            "Expected 'denied' in error content, got: ${result.content}"
        )
    }
}
