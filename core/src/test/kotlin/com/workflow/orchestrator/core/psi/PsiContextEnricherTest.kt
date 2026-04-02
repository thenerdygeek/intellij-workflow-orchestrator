package com.workflow.orchestrator.core.psi

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PsiContextEnricherTest {

    @Test
    fun `PsiContext data class has correct defaults in emptyContext`() {
        val ctx = PsiContextEnricher.PsiContext(
            fileType = "unknown",
            packageName = null,
            className = null,
            classAnnotations = emptyList(),
            methodAnnotations = emptyMap(),
            testFilePath = null,
            imports = emptyList(),
            mavenModule = null,
            relatedFiles = emptyList(),
            isTestFile = false
        )
        assertNull(ctx.packageName)
        assertNull(ctx.className)
        assertNull(ctx.testFilePath)
        assertNull(ctx.mavenModule)
        assertFalse(ctx.isTestFile)
        assertEquals("unknown", ctx.fileType)
        assertTrue(ctx.classAnnotations.isEmpty())
        assertTrue(ctx.methodAnnotations.isEmpty())
        assertTrue(ctx.imports.isEmpty())
        assertTrue(ctx.relatedFiles.isEmpty())
    }

    @Test
    fun `PsiContext correctly stores annotation data`() {
        val ctx = PsiContextEnricher.PsiContext(
            fileType = "JAVA",
            packageName = "com.example",
            className = "com.example.UserService",
            classAnnotations = listOf("Service", "Transactional"),
            methodAnnotations = mapOf("getUser" to listOf("Transactional", "Override")),
            testFilePath = "src/test/java/com/example/UserServiceTest.java",
            imports = listOf("org.springframework.stereotype.Service"),
            mavenModule = "user-service",
            relatedFiles = listOf("src/main/java/com/example/UserController.java"),
            isTestFile = false
        )
        assertEquals("com.example", ctx.packageName)
        assertEquals("com.example.UserService", ctx.className)
        assertEquals(2, ctx.classAnnotations.size)
        assertTrue(ctx.classAnnotations.contains("Service"))
        assertEquals(listOf("Transactional", "Override"), ctx.methodAnnotations["getUser"])
        assertEquals("user-service", ctx.mavenModule)
    }
}
