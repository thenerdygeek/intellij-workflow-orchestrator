package com.workflow.orchestrator.agent.link

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.workflow.orchestrator.agent.ide.LanguageIntelligenceProvider
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies that `symbol:` links resolve through [LanguageProviderRegistry] (so any
 * registered language provider — Java/Kotlin AND Python — can satisfy them) rather
 * than via `JavaPsiFacade` directly. Resolution is mocked at the provider boundary;
 * the test exercises the query-building and element→location logic.
 */
class SymbolLinkResolverTest {

    private lateinit var project: Project
    private lateinit var registry: LanguageProviderRegistry
    private lateinit var provider: LanguageIntelligenceProvider
    private lateinit var psiElement: PsiElement
    private lateinit var psiFile: PsiFile
    private lateinit var virtualFile: VirtualFile
    private lateinit var document: Document
    private lateinit var psiDocManager: PsiDocumentManager

    @BeforeEach
    fun setUp() {
        installReadActionInlineShim()

        project = mockk()
        registry = mockk()
        provider = mockk()
        psiElement = mockk()
        psiFile = mockk()
        virtualFile = mockk()
        document = mockk()
        psiDocManager = mockk()

        mockkStatic(PsiDocumentManager::class)
        every { PsiDocumentManager.getInstance(project) } returns psiDocManager

        // element → location chain (0-based line, column = offset - lineStart)
        every { registry.allProviders() } returns listOf(provider)
        every { psiElement.containingFile } returns psiFile
        every { psiFile.virtualFile } returns virtualFile
        every { virtualFile.path } returns "/project/src/MyClass.kt"
        every { psiElement.textOffset } returns 100
        every { psiDocManager.getDocument(psiFile) } returns document
        every { document.getLineNumber(100) } returns 5
        every { document.getLineStartOffset(5) } returns 90

        // by default a provider finds nothing — individual tests stub the matching query
        every { provider.findSymbol(any(), any()) } returns null
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `resolves class FQN via a registered provider`() = runTest {
        every { provider.findSymbol(project, "com.example.MyClass") } returns psiElement

        val result = SymbolLinkResolver(project, registry).resolve("symbol:com.example.MyClass")

        assertNotNull(result)
        assertEquals("symbol:com.example.MyClass", result!!.input)
        assertEquals("/project/src/MyClass.kt", result.canonicalPath)
        assertEquals(5, result.line)
        assertEquals(10, result.column) // 100 - 90
    }

    @Test
    fun `resolves a member using the simple-class query form`() = runTest {
        // Both providers honour "SimpleClass.member"; the resolver must query that,
        // not the full FQN+member (which no provider recognizes).
        every { provider.findSymbol(project, "MyClass.doThing") } returns psiElement

        val result = SymbolLinkResolver(project, registry).resolve("symbol:com.example.MyClass#doThing")

        assertNotNull(result)
        assertEquals(5, result!!.line)
    }

    @Test
    fun `falls back to the enclosing class when the member misses`() = runTest {
        // member query returns null; class FQN resolves → link still lands on the class
        every { provider.findSymbol(project, "MyClass.ghost") } returns null
        every { provider.findSymbol(project, "com.example.MyClass") } returns psiElement

        val result = SymbolLinkResolver(project, registry).resolve("symbol:com.example.MyClass#ghost")

        assertNotNull(result)
        assertEquals(5, result!!.line)
    }

    @Test
    fun `tries every provider until one resolves (language fan-out)`() = runTest {
        val first = mockk<LanguageIntelligenceProvider>()
        val second = mockk<LanguageIntelligenceProvider>()
        every { first.findSymbol(any(), any()) } returns null
        every { second.findSymbol(project, "app.models.User") } returns psiElement
        every { registry.allProviders() } returns listOf(first, second)

        val result = SymbolLinkResolver(project, registry).resolve("symbol:app.models.User")

        assertNotNull(result)
        assertEquals("/project/src/MyClass.kt", result!!.canonicalPath)
    }

    @Test
    fun `returns null when no provider resolves the symbol`() = runTest {
        assertNull(SymbolLinkResolver(project, registry).resolve("symbol:com.example.Missing"))
    }

    @Test
    fun `returns null for a blank fqn without calling any provider`() = runTest {
        assertNull(SymbolLinkResolver(project, registry).resolve("symbol:"))
    }

    @Test
    fun `resolveAll returns only the resolvable entries`() = runTest {
        every { provider.findSymbol(project, "com.example.MyClass") } returns psiElement

        val results = SymbolLinkResolver(project, registry).resolveAll(
            listOf("symbol:com.example.MyClass", "symbol:com.example.Missing"),
        )

        assertEquals(1, results.size)
        assertEquals("symbol:com.example.MyClass", results[0].input)
    }
}
