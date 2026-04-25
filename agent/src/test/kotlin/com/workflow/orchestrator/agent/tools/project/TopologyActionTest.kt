package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
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
 * Unit tests for [executeTopology].
 *
 *  1. testDumbModeReturnsError      — DumbService.isDumb=true → isError, message contains "indexing"
 *  2. testTopologyReturnsModuleList — mock ModuleManager.sortedModules with 2 modules →
 *                                     no error, content contains both module names and "module"
 */
class TopologyActionTest {

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        every { project.basePath } returns "/project/root"

        installReadActionInlineShim()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 1 — DumbService.isDumb=true → isError + "indexing" in content
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testDumbModeReturnsError() = runTest {
        mockkStatic(DumbService::class)
        every { DumbService.isDumb(project) } returns true

        val params = buildJsonObject {}
        val result = executeTopology(params, project)

        assertTrue(result.isError, "Expected isError=true while project is indexing")
        assertTrue(
            result.content.lowercase().contains("indexing"),
            "Expected 'indexing' in error message but got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 2 — Happy path: 2 modules returned in topological order
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testTopologyReturnsModuleList() = runTest {
        // -- DumbService: not indexing --
        mockkStatic(DumbService::class)
        every { DumbService.isDumb(project) } returns false

        // -- Two mock modules --
        val mockModuleA = mockk<Module>(relaxed = true)
        every { mockModuleA.name } returns "module-alpha"

        val mockModuleB = mockk<Module>(relaxed = true)
        every { mockModuleB.name } returns "module-beta"

        // -- ModuleManager: returns both modules in sorted order --
        val mockModuleManager = mockk<ModuleManager>(relaxed = true)
        mockkStatic(ModuleManager::class)
        every { ModuleManager.getInstance(project) } returns mockModuleManager
        every { mockModuleManager.sortedModules } returns arrayOf(mockModuleA, mockModuleB)
        // moduleGraph needed for cycle detection — relax it to avoid NPE
        every { mockModuleManager.moduleGraph() } returns mockk(relaxed = true)

        val params = buildJsonObject { put("detect_cycles", false) }
        val result = executeTopology(params, project)

        assertFalse(result.isError, "Expected no error but got: ${result.content}")
        assertTrue(
            result.content.contains("module-alpha"),
            "Expected 'module-alpha' in content: ${result.content}"
        )
        assertTrue(
            result.content.contains("module-beta"),
            "Expected 'module-beta' in content: ${result.content}"
        )
        assertTrue(
            result.content.lowercase().contains("module"),
            "Expected 'module' in content: ${result.content}"
        )
    }
}
