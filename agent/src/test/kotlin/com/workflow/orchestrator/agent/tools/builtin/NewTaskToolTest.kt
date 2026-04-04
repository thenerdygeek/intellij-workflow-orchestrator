package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NewTaskToolTest {

    private val project = mockk<Project>(relaxed = true)
    private val tool = NewTaskTool()

    @Test
    fun `tool name is new_task`() {
        assertEquals("new_task", tool.name)
    }

    @Test
    fun `context is required parameter`() {
        assertTrue(tool.parameters.required.contains("context"))
    }

    @Test
    fun `allowedWorkers contains ORCHESTRATOR`() {
        assertTrue(tool.allowedWorkers.contains(WorkerType.ORCHESTRATOR))
    }

    @Test
    fun `description faithfully ported from Cline`() {
        // Key phrases from Cline's new_task description
        assertTrue(tool.description.contains("preloaded context"))
        assertTrue(tool.description.contains("detailed summary of the conversation"))
        assertTrue(tool.description.contains("technical details"))
        assertTrue(tool.description.contains("architectural decisions"))
    }

    @Test
    fun `parameter description includes all 5 context sections from Cline`() {
        val contextParam = tool.parameters.properties["context"]!!
        assertTrue(contextParam.description!!.contains("Current Work"))
        assertTrue(contextParam.description!!.contains("Key Technical Concepts"))
        assertTrue(contextParam.description!!.contains("Relevant Files and Code"))
        assertTrue(contextParam.description!!.contains("Problem Solving"))
        assertTrue(contextParam.description!!.contains("Pending Tasks and Next Steps"))
    }

    @Test
    fun `returns session handoff with isSessionHandoff=true`() = runTest {
        val context = """
            1. Current Work: Implementing user authentication
            2. Key Technical Concepts: JWT tokens, Spring Security
            3. Relevant Files: UserService.kt, AuthController.kt
            4. Problem Solving: Fixed token expiration bug
            5. Pending Tasks: Add refresh token support
        """.trimIndent()

        val result = tool.execute(buildJsonObject {
            put("context", context)
        }, project)

        assertTrue(result.isSessionHandoff)
        assertFalse(result.isError)
        assertFalse(result.isCompletion)
        assertEquals(context, result.content)
        assertEquals(context, result.handoffContext)
    }

    @Test
    fun `missing context parameter returns error`() = runTest {
        val result = tool.execute(buildJsonObject {}, project)

        assertTrue(result.isError)
        assertFalse(result.isSessionHandoff)
        assertTrue(result.content.contains("Missing required parameter"))
    }

    @Test
    fun `empty context parameter returns error`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("context", "   ")
        }, project)

        assertTrue(result.isError)
        assertFalse(result.isSessionHandoff)
        assertTrue(result.content.contains("must not be empty"))
    }

    @Test
    fun `summary includes context size`() = runTest {
        val context = "Test context with 50 characters of important info."
        val result = tool.execute(buildJsonObject {
            put("context", context)
        }, project)

        assertTrue(result.summary.contains("${context.length} chars"))
        assertTrue(result.summary.contains("Session handoff"))
    }

    @Test
    fun `token estimate is proportional to context length`() = runTest {
        val context = "A".repeat(400)
        val result = tool.execute(buildJsonObject {
            put("context", context)
        }, project)

        assertEquals(100, result.tokenEstimate) // 400 chars / 4
    }
}
