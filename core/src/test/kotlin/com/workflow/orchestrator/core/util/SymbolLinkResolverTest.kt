package com.workflow.orchestrator.core.util

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.core.testutil.installReadActionInlineShim
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SymbolLinkResolverTest {

    private lateinit var project: Project
    private lateinit var scope: GlobalSearchScope
    private lateinit var facade: JavaPsiFacade
    private lateinit var psiClass: PsiClass
    private lateinit var psiFile: PsiFile
    private lateinit var virtualFile: VirtualFile
    private lateinit var document: Document
    private lateinit var psiDocManager: PsiDocumentManager

    @BeforeEach
    fun setUp() {
        installReadActionInlineShim()

        project = mockk()
        scope = mockk()
        facade = mockk()
        psiClass = mockk()
        psiFile = mockk()
        virtualFile = mockk()
        document = mockk()
        psiDocManager = mockk()

        mockkStatic(GlobalSearchScope::class)
        mockkStatic(JavaPsiFacade::class)
        mockkStatic(PsiDocumentManager::class)

        every { GlobalSearchScope.allScope(project) } returns scope
        every { JavaPsiFacade.getInstance(project) } returns facade
        every { PsiDocumentManager.getInstance(project) } returns psiDocManager

        // default stubs for psiClass (class-level resolution)
        every { psiClass.containingFile } returns psiFile
        every { psiFile.virtualFile } returns virtualFile
        every { virtualFile.path } returns "/project/src/MyClass.kt"
        every { psiClass.textOffset } returns 100
        every { psiDocManager.getDocument(psiFile) } returns document
        every { document.getLineNumber(100) } returns 5
        every { document.getLineStartOffset(5) } returns 90
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `resolves class FQN to ValidatedPath`() = runTest {
        every { facade.findClass("com.example.MyClass", scope) } returns psiClass

        val result = SymbolLinkResolver(project).resolve("symbol:com.example.MyClass")

        assertNotNull(result)
        assertEquals("symbol:com.example.MyClass", result!!.input)
        assertEquals("/project/src/MyClass.kt", result.canonicalPath)
        assertEquals(5, result.line)
        assertEquals(10, result.column) // 100 - 90
    }

    @Test
    fun `resolves method via hash separator`() = runTest {
        val method = mockk<PsiMethod>()
        every { facade.findClass("com.example.MyClass", scope) } returns psiClass
        every { psiClass.findMethodsByName("doThing", true) } returns arrayOf(method)
        every { method.containingFile } returns psiFile
        every { method.textOffset } returns 200
        every { document.getLineNumber(200) } returns 8
        every { document.getLineStartOffset(8) } returns 180

        val result = SymbolLinkResolver(project).resolve("symbol:com.example.MyClass#doThing")

        assertNotNull(result)
        assertEquals("symbol:com.example.MyClass#doThing", result!!.input)
        assertEquals(8, result.line)
        assertEquals(20, result.column) // 200 - 180
    }

    @Test
    fun `resolves field when no method matches`() = runTest {
        val field = mockk<PsiField>()
        every { facade.findClass("com.example.MyClass", scope) } returns psiClass
        every { psiClass.findMethodsByName("TOKEN", true) } returns emptyArray()
        every { psiClass.findFieldByName("TOKEN", true) } returns field
        every { field.containingFile } returns psiFile
        every { field.textOffset } returns 50
        every { document.getLineNumber(50) } returns 2
        every { document.getLineStartOffset(2) } returns 40

        val result = SymbolLinkResolver(project).resolve("symbol:com.example.MyClass#TOKEN")

        assertNotNull(result)
        assertEquals(2, result!!.line)
        assertEquals(10, result.column) // 50 - 40
    }

    @Test
    fun `returns null when class not found`() = runTest {
        every { facade.findClass("com.example.Missing", scope) } returns null

        assertNull(SymbolLinkResolver(project).resolve("symbol:com.example.Missing"))
    }

    @Test
    fun `returns null when member not found`() = runTest {
        every { facade.findClass("com.example.MyClass", scope) } returns psiClass
        every { psiClass.findMethodsByName("ghost", true) } returns emptyArray()
        every { psiClass.findFieldByName("ghost", true) } returns null

        assertNull(SymbolLinkResolver(project).resolve("symbol:com.example.MyClass#ghost"))
    }

    @Test
    fun `returns null for blank FQN`() = runTest {
        assertNull(SymbolLinkResolver(project).resolve("symbol:"))
    }

    @Test
    fun `resolveAll returns only non-null results`() = runTest {
        every { facade.findClass("com.example.MyClass", scope) } returns psiClass
        every { facade.findClass("com.example.Missing", scope) } returns null

        val results = SymbolLinkResolver(project).resolveAll(
            listOf("symbol:com.example.MyClass", "symbol:com.example.Missing"),
        )

        assertEquals(1, results.size)
        assertEquals("symbol:com.example.MyClass", results[0].input)
    }
}
