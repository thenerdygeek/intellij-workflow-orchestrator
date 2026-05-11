package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.search.SearchScope
import com.workflow.orchestrator.agent.ide.LanguageIntelligenceProvider
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.agent.ide.StructuralMatchInfo
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StructuralSearchToolTest {

    private val registry = LanguageProviderRegistry()
    private val tool = StructuralSearchTool(registry)

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // -------------------------------------------------------------------------
    // Bug 12 regression — Python misroute
    // -------------------------------------------------------------------------

    /**
     * Before the fix: when PythonProvider is registered and file_type="python" is
     * requested, the dispatch uses listOf(PythonProvider) as providersToTry.
     * PythonProvider.structuralSearch() always returns null, so the tool emits
     * "Error: structural search failed — provider returned null" — which looks like
     * a tool bug instead of a clear capability gap.
     *
     * After the fix: the dispatch checks supportsStructuralSearch() before
     * including a provider. PythonProvider.supportsStructuralSearch() = false (default),
     * so the tool emits "SSR not supported for language 'Python'" immediately.
     *
     * This test FAILS before the fix and PASSES after.
     */
    @Test
    fun `structural search with file_type=python returns explicit not-supported error`() = runTest {
        val registryWithPython = LanguageProviderRegistry()

        // A fake Python provider: supportsStructuralSearch() uses the interface
        // default (false). structuralSearch() would return null if ever called.
        val pythonProvider = object : LanguageIntelligenceProvider {
            override val supportedLanguageIds = setOf("Python")
            // supportsStructuralSearch() intentionally NOT overridden — inherits default false
            override fun structuralSearch(project: Project, pattern: String, scope: SearchScope):
                List<StructuralMatchInfo>? = null
            // Minimal stubs for the remaining interface methods — never called in this test
            override fun findSymbol(project: Project, name: String): com.intellij.psi.PsiElement? = null
            override fun findSymbolAt(file: com.intellij.psi.PsiFile, offset: Int): com.intellij.psi.PsiElement? = null
            override fun getDefinitionInfo(element: com.intellij.psi.PsiElement): com.workflow.orchestrator.agent.ide.DefinitionInfo? = null
            override fun getFileStructure(file: com.intellij.psi.PsiFile, detail: com.workflow.orchestrator.agent.ide.DetailLevel) =
                com.workflow.orchestrator.agent.ide.FileStructureResult(null, emptyList(), emptyList(), "")
            override fun getTypeHierarchy(element: com.intellij.psi.PsiElement): com.workflow.orchestrator.agent.ide.TypeHierarchyResult? = null
            override fun findImplementations(element: com.intellij.psi.PsiElement, scope: SearchScope): List<com.workflow.orchestrator.agent.ide.ImplementationInfo> = emptyList()
            override fun inferType(element: com.intellij.psi.PsiElement): com.workflow.orchestrator.agent.ide.TypeInferenceResult? = null
            override fun analyzeDataflow(element: com.intellij.psi.PsiElement): com.workflow.orchestrator.agent.ide.DataflowResult? = null
            override fun findCallers(element: com.intellij.psi.PsiElement, depth: Int, scope: SearchScope): List<com.workflow.orchestrator.agent.ide.CallerInfo> = emptyList()
            override fun findCallees(element: com.intellij.psi.PsiElement): List<com.workflow.orchestrator.agent.ide.CalleeInfo> = emptyList()
            override fun getMetadata(element: com.intellij.psi.PsiElement, includeInherited: Boolean): List<com.workflow.orchestrator.agent.ide.MetadataInfo> = emptyList()
            override fun getBody(element: com.intellij.psi.PsiElement, contextLines: Int): com.workflow.orchestrator.agent.ide.BodyResult? = null
            override fun classifyAccesses(element: com.intellij.psi.PsiElement, scope: SearchScope) =
                com.workflow.orchestrator.agent.ide.AccessClassification(emptyList(), emptyList(), emptyList())
            override fun findRelatedTests(element: com.intellij.psi.PsiElement) =
                com.workflow.orchestrator.agent.ide.TestRelationResult(false, emptyList())
            override fun getDiagnostics(file: com.intellij.psi.PsiFile, lineRange: IntRange?): List<com.workflow.orchestrator.agent.ide.DiagnosticInfo> = emptyList()
        }
        registryWithPython.register(pythonProvider)

        val toolWithPython = StructuralSearchTool(registryWithPython)
        val project = mockk<Project>(relaxed = true)

        // Prevent the dumb-mode check from blocking execution
        mockkStatic(com.intellij.openapi.project.DumbService::class)
        every { com.intellij.openapi.project.DumbService.isDumb(project) } returns false

        val params = buildJsonObject {
            put("pattern", "\$x\$.equals(\$y\$)")
            put("file_type", "python")
        }

        val result = toolWithPython.execute(params, project)

        assertTrue(result.isError,
            "Expected an error result for Python SSR, got: ${result.content}")
        assertTrue(
            result.content.contains("not supported", ignoreCase = true),
            "Expected 'not supported' in error, but got: '${result.content}'\n" +
            "This is the Bug 12 regression — fix StructuralSearchTool dispatch to " +
            "check supportsStructuralSearch() before routing to a provider."
        )
        // Confirm the misleading "provider returned null" phrasing is gone
        assertFalse(
            result.content.contains("provider returned null"),
            "Error must NOT say 'provider returned null' — that's the misleading pre-fix message. Got: '${result.content}'"
        )
    }

    // -------------------------------------------------------------------------
    // Bug 13 regression — JavaKotlinProvider.supportsStructuralSearch contract
    // -------------------------------------------------------------------------

    /**
     * JavaKotlinProvider must advertise SSR support via supportsStructuralSearch() = true.
     * Before the fix: the method does not exist (interface default = false), so the provider
     * is excluded from providersToTry even for Java/Kotlin requests.
     * After the fix: JavaKotlinProvider overrides supportsStructuralSearch() to return true.
     *
     * This test FAILS before the fix (supportsStructuralSearch() returns false / method not
     * overridden) and PASSES after.
     */
    @Test
    fun `JavaKotlinProvider supportsStructuralSearch returns true`() {
        // Construct a JavaKotlinProvider via the registry pattern used in AgentService.
        // JavaKotlinProvider requires a Project in its constructor.
        val mockProject = mockk<Project>(relaxed = true)
        val provider = com.workflow.orchestrator.agent.ide.JavaKotlinProvider(mockProject)

        assertTrue(
            provider.supportsStructuralSearch(),
            "JavaKotlinProvider.supportsStructuralSearch() must return true. " +
            "This is the Bug 13 regression — override supportsStructuralSearch() in JavaKotlinProvider."
        )
    }

    @Test
    fun `tool metadata is correct`() {
        assertEquals("structural_search", tool.name)
        assertTrue(tool.description.contains("structural search"))

        val props = tool.parameters.properties
        assertTrue(props.containsKey("pattern"))
        assertTrue(props.containsKey("file_type"))
        assertTrue(props.containsKey("scope"))
        assertTrue(props.containsKey("max_results"))

        assertEquals(listOf("pattern"), tool.parameters.required)
    }

    @Test
    fun `allowed workers include ANALYZER and REVIEWER`() {
        assertEquals(
            setOf(WorkerType.ANALYZER, WorkerType.REVIEWER),
            tool.allowedWorkers
        )
    }

    @Test
    fun `error when pattern parameter is missing`() = runTest {
        val params = buildJsonObject {
            put("file_type", "java")
        }
        val mockProject = io.mockk.mockk<com.intellij.openapi.project.Project>()
        val result = tool.execute(params, mockProject)
        assertTrue(result.isError)
        assertTrue(result.content.contains("'pattern' parameter is required"))
    }

    @Test
    fun `parameters have correct types`() {
        val props = tool.parameters.properties
        assertEquals("string", props["pattern"]?.type)
        assertEquals("string", props["file_type"]?.type)
        assertEquals("string", props["scope"]?.type)
        assertEquals("integer", props["max_results"]?.type)
    }

    @Test
    fun `tool definition can be generated`() {
        val definition = tool.toToolDefinition()
        assertEquals("structural_search", definition.function.name)
        assertEquals("function", definition.type)
        assertFalse(definition.function.description.isBlank())
        assertTrue(definition.function.parameters.properties.isNotEmpty())
    }

    @Test
    fun `tool can be instantiated`() {
        val instance = StructuralSearchTool(registry)
        assertNotNull(instance)
        assertEquals("structural_search", instance.name)
    }
}
