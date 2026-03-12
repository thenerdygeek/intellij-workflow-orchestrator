package com.workflow.orchestrator.cody.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpringContextEnricherTest {

    @Test
    fun `EMPTY enricher returns null for any file`() {
        val enricher = SpringContextEnricher.EMPTY
        val result = kotlinx.coroutines.runBlocking { enricher.enrich("/any/path.kt") }
        assertNull(result)
    }

    @Test
    fun `SpringContext data class stores bean information correctly`() {
        val ctx = SpringContextEnricher.SpringContext(
            isBean = true,
            beanType = "Service",
            injectedDependencies = listOf(
                SpringContextEnricher.BeanDependency(
                    beanName = "userRepo",
                    beanType = "com.example.UserRepository",
                    qualifier = null
                )
            ),
            transactionalMethods = listOf("createUser", "deleteUser"),
            requestMappings = emptyList(),
            beanConsumers = listOf("com.example.UserController")
        )
        assertTrue(ctx.isBean)
        assertEquals("Service", ctx.beanType)
        assertEquals(1, ctx.injectedDependencies.size)
        assertEquals("userRepo", ctx.injectedDependencies[0].beanName)
        assertEquals(2, ctx.transactionalMethods.size)
        assertEquals(1, ctx.beanConsumers.size)
    }

    @Test
    fun `RequestMappingInfo stores endpoint data`() {
        val info = SpringContextEnricher.RequestMappingInfo(
            method = "GET",
            path = "/api/users/{id}",
            handlerMethod = "getUserById"
        )
        assertEquals("GET", info.method)
        assertEquals("/api/users/{id}", info.path)
        assertEquals("getUserById", info.handlerMethod)
    }

    @Test
    fun `non-bean SpringContext has empty collections`() {
        val ctx = SpringContextEnricher.SpringContext(
            isBean = false,
            beanType = null,
            injectedDependencies = emptyList(),
            transactionalMethods = emptyList(),
            requestMappings = emptyList(),
            beanConsumers = emptyList()
        )
        assertFalse(ctx.isBean)
        assertNull(ctx.beanType)
    }
}
