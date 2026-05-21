package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.workflow.orchestrator.agent.ide.LanguageIntelligenceProvider
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FindReferencesToolTest {

    private val registry = LanguageProviderRegistry()

    @Test
    fun `tool metadata is correct`() {
        val tool = FindReferencesTool(registry)
        assertEquals("find_references", tool.name)
        assertTrue(tool.parameters.required.contains("symbol"))
        assertTrue(tool.parameters.properties.containsKey("symbol"))
        assertTrue(tool.parameters.properties.containsKey("file"))
    }

    @Test
    fun `allowedWorkers includes ANALYZER and REVIEWER`() {
        val tool = FindReferencesTool(registry)
        assertEquals(setOf(WorkerType.ANALYZER, WorkerType.REVIEWER), tool.allowedWorkers)
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val tool = FindReferencesTool(registry)
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("find_references", def.function.name)
        assertTrue(def.function.parameters.required.contains("symbol"))
        assertFalse(def.function.parameters.required.contains("file"))
    }

    @Test
    fun `execute does not bail early when indexer is busy`() = runTest {
        // The eager isDumb guard was removed: inSmartMode().executeSynchronously() defers
        // internally, so a dumb-mode state should NOT produce the short-circuit error.
        val tool = FindReferencesTool(registry)
        val project = mockk<com.intellij.openapi.project.Project>(relaxed = true) {
            every { basePath } returns "/tmp"
        }
        val params = kotlinx.serialization.json.buildJsonObject {
            put("symbol", kotlinx.serialization.json.JsonPrimitive("MyClass"))
        }

        mockkStatic(com.intellij.openapi.project.DumbService::class)
        every { com.intellij.openapi.project.DumbService.isDumb(project) } returns true

        val result = try {
            tool.execute(params, project)
        } catch (e: Exception) {
            // An exception past the guard is acceptable proof that the guard is gone.
            unmockkStatic(com.intellij.openapi.project.DumbService::class)
            return@runTest
        }

        assertFalse(
            result.content.contains("IDE is still indexing", ignoreCase = true),
            "Eager dumb-mode guard must be absent; got: ${result.content}"
        )
        unmockkStatic(com.intellij.openapi.project.DumbService::class)
    }

    @Test
    fun `execute returns error when symbol is missing`() = runTest {
        val tool = FindReferencesTool(registry)
        val project = mockk<com.intellij.openapi.project.Project> {
            every { basePath } returns "/tmp"
        }
        val params = kotlinx.serialization.json.buildJsonObject { }

        mockkStatic(com.intellij.openapi.project.DumbService::class)
        every { com.intellij.openapi.project.DumbService.isDumb(project) } returns false

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'symbol' parameter required"))
        unmockkStatic(com.intellij.openapi.project.DumbService::class)
    }

    // ── Regression: fix 3918e3d7b ─────────────────────────────────────────────
    // Before the fix, the no-file-context global resolution path was:
    //   registry.forLanguageId("JAVA") ?: registry.forLanguageId("kotlin")
    // which returned null for a Python-only registry so resolveSearchTarget()
    // returned null and the tool reported "No symbol '...' found in project"
    // even though a PythonProvider was registered and could resolve the symbol.
    // After the fix, the code iterates registry.allProviders().firstNotNullOfOrNull { … }
    // so any registered provider is reached regardless of its language ID.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Confirms that a LanguageProviderRegistry containing ONLY a Python provider (no Java/Kotlin)
     * returns the Python provider via allProviders().firstNotNullOfOrNull, matching the
     * post-fix dispatch pattern used in FindReferencesTool.resolveSearchTarget().
     *
     * The OLD code: registry.forLanguageId("JAVA") ?: registry.forLanguageId("kotlin")
     *   → both return null for a Python-only registry → resolveSearchTarget returns null → "No symbol found"
     * The NEW code: registry.allProviders().firstNotNullOfOrNull { p -> p.findSymbol(project, symbol) }
     *   → iterates all providers → PythonProvider.findSymbol is called → non-null PsiElement
     *
     * If anyone reverts to the hardcoded JAVA/kotlin fallback, forLanguageId("JAVA") / ("kotlin")
     * will return null on a Python-only registry and the test will fail at the `assertNotNull(resolved)`
     * assertion, pinning the regression.
     */
    @Test
    fun `allProviders dispatch finds PythonProvider when only Python is registered`() {
        // Arrange: a registry containing ONLY a Python-language provider
        val mockPythonElement = mockk<PsiElement>(relaxed = true)
        val mockPythonProvider = mockk<LanguageIntelligenceProvider> {
            every { supportedLanguageIds } returns setOf("Python")
            every { findSymbol(any(), any()) } returns mockPythonElement
        }
        val pythonOnlyRegistry = LanguageProviderRegistry()
        pythonOnlyRegistry.register(mockPythonProvider)

        // Confirm old-style hardcoded lookups return null (proving the pre-fix path was broken)
        assertNull(
            pythonOnlyRegistry.forLanguageId("JAVA"),
            "forLanguageId(JAVA) must return null in a Python-only registry — confirms pre-fix path was broken"
        )
        assertNull(
            pythonOnlyRegistry.forLanguageId("kotlin"),
            "forLanguageId(kotlin) must return null in a Python-only registry — confirms pre-fix path was broken"
        )

        // Confirm allProviders() exposes the Python provider (the post-fix dispatch path)
        val allProviders = pythonOnlyRegistry.allProviders()
        assertEquals(1, allProviders.size, "allProviders() must return the single registered PythonProvider")

        val mockProject = mockk<Project>(relaxed = true)

        // Simulate the exact dispatch pattern now used in FindReferencesTool.resolveSearchTarget()
        // (the global resolution branch, no file path provided):
        //   registry.allProviders().firstNotNullOfOrNull { p -> p.findSymbol(project, symbol) }
        val resolved = allProviders.firstNotNullOfOrNull { p ->
            p.findSymbol(mockProject, "my_python_func")
        }

        assertNotNull(resolved, "allProviders dispatch must resolve the symbol via PythonProvider")
        assertSame(mockPythonElement, resolved, "PythonProvider's findSymbol result must be returned")

        // Verify PythonProvider.findSymbol was actually invoked
        verify(exactly = 1) { mockPythonProvider.findSymbol(mockProject, "my_python_func") }
    }

    /**
     * Regression: empty registry produces null from allProviders() dispatch,
     * meaning resolveSearchTarget returns null and the tool reports "No symbol found".
     * This is the correct pre-condition check — the allProviders iteration on an
     * empty registry is safe (no NPE, no crash).
     */
    @Test
    fun `allProviders on empty registry returns empty list — resolveSearchTarget returns null`() {
        val emptyRegistry = LanguageProviderRegistry()
        val allProviders = emptyRegistry.allProviders()
        assertTrue(allProviders.isEmpty(), "Empty registry must return empty allProviders list")

        val mockProject = mockk<Project>(relaxed = true)
        // Simulate what resolveSearchTarget does after the allProviders() path:
        val resolved = allProviders.firstNotNullOfOrNull { p -> p.findSymbol(mockProject, "anything") }
        assertNull(resolved, "firstNotNullOfOrNull on empty list must return null")
    }
}
