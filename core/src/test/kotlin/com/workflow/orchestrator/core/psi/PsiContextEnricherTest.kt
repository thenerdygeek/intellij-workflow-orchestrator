package com.workflow.orchestrator.core.psi

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PsiContextEnricherTest {

    @Test
    fun `PsiContext data class has correct defaults in emptyContext`() {
        val ctx = PsiContextEnricher.PsiContext(
            className = null,
            classAnnotations = emptyList(),
            methodAnnotations = emptyMap(),
            mavenModule = null,
            isTestFile = false
        )
        assertNull(ctx.className)
        assertNull(ctx.mavenModule)
        assertFalse(ctx.isTestFile)
        assertTrue(ctx.classAnnotations.isEmpty())
        assertTrue(ctx.methodAnnotations.isEmpty())
    }

    @Test
    fun `PsiContext correctly stores annotation data`() {
        val ctx = PsiContextEnricher.PsiContext(
            className = "com.example.UserService",
            classAnnotations = listOf("Service", "Transactional"),
            methodAnnotations = mapOf("getUser" to listOf("Transactional", "Override")),
            mavenModule = "user-service",
            isTestFile = false
        )
        assertEquals("com.example.UserService", ctx.className)
        assertEquals(2, ctx.classAnnotations.size)
        assertTrue(ctx.classAnnotations.contains("Service"))
        assertEquals(listOf("Transactional", "Override"), ctx.methodAnnotations["getUser"])
        assertEquals("user-service", ctx.mavenModule)
    }
}
