package com.workflow.orchestrator.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * AgentService is a project-level IntelliJ service that requires a real Project instance.
 * These tests verify the class structure and contract without instantiating the service.
 */
class AgentServiceTest {

    @Test
    fun `AgentService class is loadable`() {
        val clazz = AgentService::class.java
        assertNotNull(clazz)
        assertEquals("AgentService", clazz.simpleName)
    }

    @Test
    fun `AgentService implements Disposable`() {
        assertTrue(Disposable::class.java.isAssignableFrom(AgentService::class.java))
    }

    @Test
    fun `companion object has getInstance method that accepts Project`() {
        val companion = AgentService::class.java.declaredClasses
            .find { it.simpleName == "Companion" }
        assertNotNull(companion, "AgentService should have a Companion object")

        val getInstanceMethod = companion!!.methods.find { it.name == "getInstance" }
        assertNotNull(getInstanceMethod, "Companion should have getInstance method")
        assertEquals(1, getInstanceMethod!!.parameterCount)
        assertEquals(Project::class.java, getInstanceMethod.parameterTypes[0])
        assertEquals(AgentService::class.java, getInstanceMethod.returnType)
    }

    @Test
    fun `constructor takes Project parameter`() {
        val constructors = AgentService::class.java.declaredConstructors
        assertTrue(constructors.isNotEmpty())
        val primaryConstructor = constructors.first()
        assertTrue(primaryConstructor.parameterTypes.any { it == Project::class.java })
    }

    @Test
    fun `toolRegistry property exists`() {
        val field = AgentService::class.java.methods.find { it.name == "getToolRegistry" }
        assertNotNull(field, "AgentService should expose toolRegistry as a getter")
    }

    @Test
    fun `brain property exists`() {
        val field = AgentService::class.java.methods.find { it.name == "getBrain" }
        assertNotNull(field, "AgentService should expose brain as a getter")
    }

    @Test
    fun `isConfigured method exists and returns Boolean`() {
        val method = AgentService::class.java.methods.find { it.name == "isConfigured" }
        assertNotNull(method, "AgentService should have isConfigured method")
        assertEquals(Boolean::class.java, method!!.returnType)
    }
}
