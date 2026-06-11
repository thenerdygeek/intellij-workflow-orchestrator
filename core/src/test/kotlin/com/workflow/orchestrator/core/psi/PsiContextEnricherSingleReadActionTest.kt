package com.workflow.orchestrator.core.psi

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.workflow.orchestrator.core.testutil.installReadActionInlineShim
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * P2-22 + B19 (2026-06-10 perf audit): `enrich()` must compute its result in ONE
 * read action that returns a plain-data snapshot. The old shape ran 5 sequential
 * read actions, resolved the file twice, and carried a [PsiClass] ACROSS read
 * actions — a PsiInvalidElementAccessException risk while the user types.
 */
class PsiContextEnricherSingleReadActionTest {

    private var readActionCount = 0

    @BeforeEach
    fun setUp() {
        installReadActionInlineShim()
        // Re-stub readAction with an invocation counter (still inline).
        readActionCount = 0
        coEvery { readAction<Any?>(any()) } coAnswers {
            readActionCount++
            firstArg<() -> Any?>().invoke()
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `enrich uses exactly one read action on the happy path`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val vFile = mockk<VirtualFile>(relaxed = true)
        val psiFile = mockk<PsiFile>(relaxed = true)
        val psiClass = mockk<PsiClass>(relaxed = true)

        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns mockk {
            every { findFileByPath(any()) } returns vFile
        }
        mockkStatic(PsiManager::class)
        every { PsiManager.getInstance(any()) } returns mockk {
            every { findFile(vFile) } returns psiFile
        }
        mockkStatic(ProjectFileIndex::class)
        every { ProjectFileIndex.getInstance(any()) } returns mockk {
            every { isInTestSourceContent(vFile) } returns true
        }
        mockkStatic(PsiTreeUtil::class)
        every { PsiTreeUtil.findChildOfType(psiFile, PsiClass::class.java) } returns psiClass
        every { psiClass.qualifiedName } returns "com.example.Foo"
        every { psiClass.annotations } returns emptyArray()
        every { psiClass.methods } returns emptyArray()

        val ctx = PsiContextEnricher(project).enrich("/some/Foo.kt")

        assertEquals("com.example.Foo", ctx.className)
        assertTrue(ctx.isTestFile)
        assertEquals(
            1,
            readActionCount,
            "enrich() must compute its full snapshot inside a SINGLE read action " +
                "(was 5 sequential read actions with a PsiClass crossing the boundaries — B19)"
        )
    }

    @Test
    fun `enrich resolves the file exactly once in source`() {
        val src = enricherSource()
        assertEquals(
            1,
            Regex(Regex.escape("findFileByPath")).findAll(src).count(),
            "PsiContextEnricher must resolve the VirtualFile exactly once (P2-22)"
        )
        assertEquals(
            1,
            Regex("""readAction\s*\{""").findAll(src).count(),
            "PsiContextEnricher must contain exactly one readAction block (P2-22)"
        )
    }

    @Test
    fun `PsiContext snapshot is pure data - no PSI types escape the read action`() {
        for (field in PsiContextEnricher.PsiContext::class.java.declaredFields) {
            assertFalse(
                field.type.name.startsWith("com.intellij."),
                "PsiContext.${field.name} leaks platform type ${field.type.name} — " +
                    "the snapshot must be plain data so no PSI element outlives the read action (B19)"
            )
        }
    }

    private fun enricherSource(): String {
        val root = findProjectRoot()
        return File(
            root,
            "core/src/main/kotlin/com/workflow/orchestrator/core/psi/PsiContextEnricher.kt"
        ).readText()
    }

    private fun findProjectRoot(): File {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null) {
            if (File(dir, "gradle.properties").exists() && File(dir, "settings.gradle.kts").exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        error("Could not locate project root from ${File(".").canonicalPath}")
    }
}
