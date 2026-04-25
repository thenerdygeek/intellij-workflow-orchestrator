package com.workflow.orchestrator.agent.tools.project

import com.intellij.facet.Facet
import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
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
 * Unit tests for [executeListFacets].
 *
 *  1. testListFacetsReturnsContent     — mock ModuleManager + FacetManager + readAction → no error
 *  2. testModuleNotFoundReturnsError   — give a module name, mock ModuleManager returning null → isError
 */
class ListFacetsActionTest {

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)

        installReadActionInlineShim()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 1 — Happy path: facets listed for all modules
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testListFacetsReturnsContent() = runTest {
        // -- Mock facet type --
        val mockFacetType = mockk<FacetType<*, *>>(relaxed = true)
        every { mockFacetType.presentableName } returns "Spring"
        every { mockFacetType.stringId } returns "spring"

        // -- Mock facet --
        @Suppress("UNCHECKED_CAST")
        val mockFacet = mockk<Facet<*>>(relaxed = true)
        every { mockFacet.name } returns "Spring"
        every { mockFacet.type } returns mockFacetType as FacetType<Facet<*>, *>

        // -- Mock module --
        val mockModule = mockk<Module>(relaxed = true)
        every { mockModule.name } returns "app-module"

        // -- ModuleManager: returns one module --
        val mockModuleManager = mockk<ModuleManager>(relaxed = true)
        mockkStatic(ModuleManager::class)
        every { ModuleManager.getInstance(project) } returns mockModuleManager
        every { mockModuleManager.modules } returns arrayOf(mockModule)

        // -- FacetManager: returns one facet for the module --
        val mockFacetManager = mockk<FacetManager>(relaxed = true)
        every { mockFacetManager.allFacets } returns arrayOf(mockFacet)
        mockkStatic(FacetManager::class)
        every { FacetManager.getInstance(mockModule) } returns mockFacetManager

        val params = buildJsonObject {}
        val result = executeListFacets(params, project)

        assertFalse(result.isError, "Expected no error but got: ${result.content}")
        assertTrue(
            result.content.contains("app-module"),
            "Expected module name in content: ${result.content}"
        )
        assertTrue(
            result.content.contains("Spring"),
            "Expected facet name 'Spring' in content: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 2 — Module name given but not found → isError
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testModuleNotFoundReturnsError() = runTest {
        val unknownModuleName = "non-existent-module"

        // -- ModuleManager: findModuleByName returns null --
        val mockModuleManager = mockk<ModuleManager>(relaxed = true)
        mockkStatic(ModuleManager::class)
        every { ModuleManager.getInstance(project) } returns mockModuleManager
        every { mockModuleManager.findModuleByName(unknownModuleName) } returns null

        val params = buildJsonObject { put("module", unknownModuleName) }
        val result = executeListFacets(params, project)

        assertTrue(result.isError, "Expected isError=true for unknown module")
        assertTrue(
            result.content.contains(unknownModuleName),
            "Expected module name in error message: ${result.content}"
        )
    }
}
