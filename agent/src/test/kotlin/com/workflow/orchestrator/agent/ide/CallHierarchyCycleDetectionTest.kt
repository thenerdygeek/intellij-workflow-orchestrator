package com.workflow.orchestrator.agent.ide

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Query
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Tests that [JavaKotlinProvider.findCallers] and [PythonProvider.findCallers]
 * handle recursive call chains (A calls B, B calls A) without stack overflow.
 */
class CallHierarchyCycleDetectionTest {

    private val project = mockk<Project>(relaxed = true)
    private val scope = mockk<GlobalSearchScope>(relaxed = true)

    @BeforeEach
    fun setup() {
        mockkStatic(ReferencesSearch::class)
        mockkStatic(PsiTreeUtil::class)
        mockkStatic(PsiDocumentManager::class)

        val docManager = mockk<PsiDocumentManager>(relaxed = true)
        every { PsiDocumentManager.getInstance(any()) } returns docManager
        every { docManager.getDocument(any()) } returns null
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ---- JavaKotlinProvider ----

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `JavaKotlinProvider findCallers handles A-calls-B-calls-A cycle without stack overflow`() {
        val provider = JavaKotlinProvider(project)

        // Create two mock PsiMethods that reference each other
        val methodA = mockPsiMethod("ClassA", "methodA")
        val methodB = mockPsiMethod("ClassB", "methodB")

        // methodA is called by methodB (reference found inside methodB)
        val refToA = mockJavaReference(methodB)
        // methodB is called by methodA (reference found inside methodA — creates cycle)
        val refToB = mockJavaReference(methodA)

        // When searching for references to methodA, find one inside methodB
        every { ReferencesSearch.search(eq(methodA), any<SearchScope>()) } returns mockQuery(listOf(refToA))
        // When searching for references to methodB, find one inside methodA (creating cycle)
        every { ReferencesSearch.search(eq(methodB), any<SearchScope>()) } returns mockQuery(listOf(refToB))

        // PsiTreeUtil should find the containing method for each reference
        every { PsiTreeUtil.getParentOfType(refToA.element, PsiMethod::class.java) } returns methodB
        every { PsiTreeUtil.getParentOfType(refToB.element, PsiMethod::class.java) } returns methodA

        // Execute with depth 3 — without cycle detection this would infinite-recurse
        val callers = provider.findCallers(methodA, 3, scope)

        // Should return results without stack overflow
        assertNotNull(callers)
        // Should find methodB as a caller of methodA at depth 1
        assertTrue(callers.any { it.name.contains("methodB") && it.depth == 1 })
        // methodA should NOT be re-processed (visited set prevents it),
        // so we should not see an infinite chain
        assertTrue(callers.size <= 2, "Expected at most 2 callers but got ${callers.size}")
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `JavaKotlinProvider findCallers handles self-recursive method without stack overflow`() {
        val provider = JavaKotlinProvider(project)

        val method = mockPsiMethod("MyClass", "recursive")
        val refToSelf = mockJavaReference(method)

        every { ReferencesSearch.search(eq(method), any<SearchScope>()) } returns mockQuery(listOf(refToSelf))
        every { PsiTreeUtil.getParentOfType(refToSelf.element, PsiMethod::class.java) } returns method

        val callers = provider.findCallers(method, 3, scope)

        assertNotNull(callers)
        // The self-reference is found at depth 1, but recursion stops because method is already visited
        assertEquals(1, callers.size, "Self-reference should be found exactly once at depth 1")
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `JavaKotlinProvider findCallers handles 3-node cycle without stack overflow`() {
        val provider = JavaKotlinProvider(project)

        val methodA = mockPsiMethod("A", "a")
        val methodB = mockPsiMethod("B", "b")
        val methodC = mockPsiMethod("C", "c")

        // A is called by B, B is called by C, C is called by A (triangle cycle)
        val refAinB = mockJavaReference(methodB)
        val refBinC = mockJavaReference(methodC)
        val refCinA = mockJavaReference(methodA)

        every { ReferencesSearch.search(eq(methodA), any<SearchScope>()) } returns mockQuery(listOf(refAinB))
        every { ReferencesSearch.search(eq(methodB), any<SearchScope>()) } returns mockQuery(listOf(refBinC))
        every { ReferencesSearch.search(eq(methodC), any<SearchScope>()) } returns mockQuery(listOf(refCinA))

        every { PsiTreeUtil.getParentOfType(refAinB.element, PsiMethod::class.java) } returns methodB
        every { PsiTreeUtil.getParentOfType(refBinC.element, PsiMethod::class.java) } returns methodC
        every { PsiTreeUtil.getParentOfType(refCinA.element, PsiMethod::class.java) } returns methodA

        val callers = provider.findCallers(methodA, 3, scope)

        assertNotNull(callers)
        // B at depth 1, C at depth 2, A at depth 3 (added as caller of C before recursion blocked)
        assertEquals(3, callers.size, "Should find B(1), C(2), A(3); recursion on A blocked by visited set")
    }

    // ---- PythonProvider ----

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `PythonProvider findCallers handles A-calls-B-calls-A cycle without stack overflow`() {
        val helper = mockk<PythonPsiHelper>(relaxed = true)
        val provider = PythonProvider(helper)

        val funcA = mockPyFunction(helper, "func_a")
        val funcB = mockPyFunction(helper, "func_b")

        every { funcA.project } returns project
        every { funcB.project } returns project

        // funcA is called by funcB
        val refToA = mockPyReference(helper, funcB)
        // funcB is called by funcA (cycle)
        val refToB = mockPyReference(helper, funcA)

        every { ReferencesSearch.search(eq(funcA), any<SearchScope>()) } returns mockQuery(listOf(refToA))
        every { ReferencesSearch.search(eq(funcB), any<SearchScope>()) } returns mockQuery(listOf(refToB))

        val callers = provider.findCallers(funcA, 3, scope)

        assertNotNull(callers)
        assertTrue(callers.any { it.name == "func_b" && it.depth == 1 })
        assertTrue(callers.size <= 2, "Expected at most 2 callers but got ${callers.size}")
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `PythonProvider findCallers handles self-recursive function without stack overflow`() {
        val helper = mockk<PythonPsiHelper>(relaxed = true)
        val provider = PythonProvider(helper)

        val func = mockPyFunction(helper, "recursive")
        every { func.project } returns project

        val refToSelf = mockPyReference(helper, func)
        every { ReferencesSearch.search(eq(func), any<SearchScope>()) } returns mockQuery(listOf(refToSelf))

        val callers = provider.findCallers(func, 3, scope)

        assertNotNull(callers)
        assertEquals(1, callers.size, "Self-reference should be found exactly once at depth 1")
    }

    // ---- Helpers ----

    private fun mockPsiMethod(className: String, methodName: String): PsiMethod {
        val clazz = mockk<PsiClass>(relaxed = true) {
            every { name } returns className
        }
        return mockk<PsiMethod>(relaxed = true) {
            every { name } returns methodName
            every { containingClass } returns clazz
            every { containingFile } returns null
        }
    }

    private fun mockJavaReference(containingMethod: PsiMethod): PsiReference {
        val element = mockk<PsiElement>(relaxed = true) {
            every { containingFile } returns null
            every { textOffset } returns 0
        }
        return mockk<PsiReference>(relaxed = true) {
            every { this@mockk.element } returns element
        }
    }

    private fun mockPyFunction(helper: PythonPsiHelper, name: String): PsiElement {
        val func = mockk<PsiElement>(relaxed = true) {
            every { containingFile } returns null
            every { parent } returns mockk<PsiFile>(relaxed = true)
        }
        every { helper.isPyFunction(func) } returns true
        every { helper.getName(func) } returns name
        return func
    }

    private fun mockPyReference(helper: PythonPsiHelper, containingFunc: PsiElement): PsiReference {
        // The reference element's parent chain leads to containingFunc
        val element = mockk<PsiElement>(relaxed = true) {
            every { containingFile } returns null
            every { textOffset } returns 0
            every { parent } returns containingFunc
        }
        // helper.isPyFunction should recognize containingFunc during the parent walk
        every { helper.isPyFunction(containingFunc) } returns true
        return mockk<PsiReference>(relaxed = true) {
            every { this@mockk.element } returns element
        }
    }

    private fun mockQuery(refs: List<PsiReference>): Query<PsiReference> {
        val query = mockk<Query<PsiReference>>(relaxed = true)
        every { query.findAll() } returns refs
        return query
    }
}
