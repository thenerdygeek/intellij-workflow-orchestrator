package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModel
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
 * Unit tests for [executeSetModuleDependency].
 *
 * Strategy: all IntelliJ APIs (ModuleManager, ReadAction, WriteCommandAction,
 * ModuleRootManager, ModuleRootModificationUtil) are static and mocked via mockkStatic.
 * [AgentTool] is mocked via MockK coEvery so requestApproval returns a controlled value.
 * [moduleExternalSystemId] is mocked via mockkStatic on the helpers file.
 * No real IntelliJ services are used.
 *
 * Test 1 — testMissingModuleParamReturnsError: no "module" param → isError=true
 * Test 2 — testMissingDependsOnParamReturnsError: no "dependsOn" param → isError=true
 * Test 3 — testSelfDependencyReturnsError: module==dependsOn → isError=true, content has "cannot depend on itself"
 * Test 4 — testInvalidScopeReturnsError: scope="bogus" → isError=true, content has the bad scope value
 * Test 5 — testModuleNotFoundReturnsError: findModuleByName returns null → isError=true, content has "not found"
 * Test 6 — testExternalSystemModuleBlocksWrite: moduleExternalSystemId returns "GRADLE" → isError=true, content contains "external" (case-insensitive)
 * Test 7 — testIdenticalDependencyAlreadyExistsIsNoOp: existingScope == requested scope → isError=false, no approval gate reached
 * Test 8 — testApprovalDeniedReturnsError: full chain → approval=DENIED → isError=true, content contains "denied"
 */
class SetModuleDependencyActionTest {

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
        val result = executeSetModuleDependency(params, project, tool)
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
        val result = executeSetModuleDependency(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when 'dependsOn' param is missing, got: ${result.content}")
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 3 — Self-dependency (module == dependsOn) → isError=true, "cannot depend on itself" in content
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testSelfDependencyReturnsError() = runTest {
        val params = buildJsonObject {
            put("module", "my-module")
            put("dependsOn", "my-module")
        }
        val result = executeSetModuleDependency(params, project, tool)
        assertTrue(result.isError, "Expected isError=true for self-dependency, got: ${result.content}")
        assertTrue(
            result.content.contains("cannot depend on itself"),
            "Expected 'cannot depend on itself' in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 4 — Invalid scope → isError=true with bad scope value in content
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testInvalidScopeReturnsError() = runTest {
        val params = buildJsonObject {
            put("module", "my-module")
            put("dependsOn", "other-module")
            put("scope", "bogus")
        }
        val result = executeSetModuleDependency(params, project, tool)
        assertTrue(result.isError, "Expected isError=true for invalid scope, got: ${result.content}")
        assertTrue(
            result.content.contains("bogus"),
            "Expected 'bogus' in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 5 — Module or dependsOn not found → isError=true with "not found" in content
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testModuleNotFoundReturnsError() = runTest {
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)

        mockkStatic(ModuleManager::class)
        every { ModuleManager.getInstance(project) } returns fakeModuleManager
        every { fakeModuleManager.findModuleByName("no-such-module") } returns null
        every { fakeModuleManager.findModuleByName("other-module") } returns null
        every { fakeModuleManager.modules } returns emptyArray()

        // Mock ReadAction to execute the lambda directly
        mockkStatic(ReadAction::class)
        val computeSlot = slot<ThrowableComputable<Any, RuntimeException>>()
        every { ReadAction.compute(capture(computeSlot)) } answers {
            computeSlot.captured.compute()
        }

        val params = buildJsonObject {
            put("module", "no-such-module")
            put("dependsOn", "other-module")
        }
        val result = executeSetModuleDependency(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when module is not found, got: ${result.content}")
        assertTrue(
            result.content.contains("not found"),
            "Expected 'not found' in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 6 — External-system module → isError=true with "external" (case-insensitive) in content
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testExternalSystemModuleBlocksWrite() = runTest {
        val fakeOwnerModule = mockk<Module>(relaxed = true)
        val fakeTargetModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)

        mockkStatic(ModuleManager::class)
        every { ModuleManager.getInstance(project) } returns fakeModuleManager
        every { fakeModuleManager.findModuleByName("my-module") } returns fakeOwnerModule
        every { fakeModuleManager.findModuleByName("other-module") } returns fakeTargetModule

        // Mock ReadAction to execute the lambda directly
        mockkStatic(ReadAction::class)
        val computeSlot = slot<ThrowableComputable<Any, RuntimeException>>()
        every { ReadAction.compute(capture(computeSlot)) } answers {
            computeSlot.captured.compute()
        }

        mockkStatic("com.workflow.orchestrator.agent.tools.project.ProjectStructureHelpersKt")
        every { moduleExternalSystemId(fakeOwnerModule) } returns "GRADLE"

        val params = buildJsonObject {
            put("module", "my-module")
            put("dependsOn", "other-module")
        }
        val result = executeSetModuleDependency(params, project, tool)
        assertTrue(result.isError, "Expected isError=true for external-system module, got: ${result.content}")
        assertTrue(
            result.content.lowercase().contains("external"),
            "Expected 'external' (case-insensitive) in error content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 7 — Identical dependency already exists (same scope) → isError=false, no approval gate
    //
    // Full mock chain: both modules found → not external → existingScope == COMPILE → no-op result
    // Approval should NOT be called.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testIdenticalDependencyAlreadyExistsIsNoOp() = runTest {
        val fakeOwnerModule = mockk<Module>(relaxed = true)
        val fakeTargetModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)
        val fakeModuleRootManager = mockk<ModuleRootManager>(relaxed = true)
        val fakeOrderEntry = mockk<ModuleOrderEntry>(relaxed = true)

        mockkStatic(ModuleManager::class)
        every { ModuleManager.getInstance(project) } returns fakeModuleManager
        every { fakeModuleManager.findModuleByName("my-module") } returns fakeOwnerModule
        every { fakeModuleManager.findModuleByName("other-module") } returns fakeTargetModule

        mockkStatic(ReadAction::class)
        val computeSlot = slot<ThrowableComputable<Any, RuntimeException>>()
        every { ReadAction.compute(capture(computeSlot)) } answers {
            computeSlot.captured.compute()
        }

        mockkStatic("com.workflow.orchestrator.agent.tools.project.ProjectStructureHelpersKt")
        every { moduleExternalSystemId(fakeOwnerModule) } returns null

        // Mock existing dependency with same scope (COMPILE)
        mockkStatic(ModuleRootManager::class)
        every { ModuleRootManager.getInstance(fakeOwnerModule) } returns fakeModuleRootManager
        every { fakeOrderEntry.moduleName } returns "other-module"
        every { fakeOrderEntry.scope } returns DependencyScope.COMPILE
        every { fakeModuleRootManager.orderEntries } returns arrayOf(fakeOrderEntry)

        val params = buildJsonObject {
            put("module", "my-module")
            put("dependsOn", "other-module")
            put("scope", "compile")
        }
        val result = executeSetModuleDependency(params, project, tool)
        assertFalse(result.isError, "Expected isError=false for no-op (identical dep), got: ${result.content}")
        assertTrue(
            result.content.contains("already exists"),
            "Expected 'already exists' in content, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 8 — Approval denied → isError=true with "denied" in content
    //
    // Full mock chain: both modules found → not external → existingScope=null (no existing dep)
    // → reaches approval gate → DENIED → isError=true with "denied"
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testApprovalDeniedReturnsError() = runTest {
        val fakeOwnerModule = mockk<Module>(relaxed = true)
        val fakeTargetModule = mockk<Module>(relaxed = true)
        val fakeModuleManager = mockk<ModuleManager>(relaxed = true)
        val fakeModuleRootManager = mockk<ModuleRootManager>(relaxed = true)

        mockkStatic(ModuleManager::class)
        every { ModuleManager.getInstance(project) } returns fakeModuleManager
        every { fakeModuleManager.findModuleByName("my-module") } returns fakeOwnerModule
        every { fakeModuleManager.findModuleByName("other-module") } returns fakeTargetModule

        mockkStatic(ReadAction::class)
        val computeSlot = slot<ThrowableComputable<Any, RuntimeException>>()
        every { ReadAction.compute(capture(computeSlot)) } answers {
            computeSlot.captured.compute()
        }

        mockkStatic("com.workflow.orchestrator.agent.tools.project.ProjectStructureHelpersKt")
        every { moduleExternalSystemId(fakeOwnerModule) } returns null

        // No existing dependency — empty order entries
        mockkStatic(ModuleRootManager::class)
        every { ModuleRootManager.getInstance(fakeOwnerModule) } returns fakeModuleRootManager
        every { fakeModuleRootManager.orderEntries } returns emptyArray()

        // Approval denied
        coEvery {
            tool.requestApproval(any(), any(), any(), any())
        } returns ApprovalResult.DENIED

        val params = buildJsonObject {
            put("module", "my-module")
            put("dependsOn", "other-module")
            put("scope", "compile")
        }
        val result = executeSetModuleDependency(params, project, tool)
        assertTrue(result.isError, "Expected isError=true when approval is denied, got: ${result.content}")
        assertTrue(
            result.content.contains("denied", ignoreCase = true),
            "Expected 'denied' in error content, got: ${result.content}"
        )
    }
}
