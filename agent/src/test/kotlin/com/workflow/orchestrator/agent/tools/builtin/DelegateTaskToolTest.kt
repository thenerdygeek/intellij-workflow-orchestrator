package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.settings.AgentSettings
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DelegateTaskToolTest {

    private lateinit var project: Project
    private lateinit var agentService: AgentService
    private lateinit var agentSettings: AgentSettings
    private lateinit var agentSettingsState: AgentSettings.State

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true) {
            every { basePath } returns "/tmp/test-project"
        }

        agentSettingsState = AgentSettings.State().apply {
            maxSessionTokens = 500_000
            maxInputTokens = 150_000
        }

        agentSettings = mockk(relaxed = true) {
            every { state } returns agentSettingsState
        }

        agentService = mockk(relaxed = true) {
            every { activeWorkerCount } returns AtomicInteger(0)
            every { totalSessionTokens } returns AtomicLong(0)
            every { delegationAttempts } returns ConcurrentHashMap()
        }

        mockkStatic(AgentService::class)
        every { AgentService.getInstance(project) } returns agentService

        mockkStatic(AgentSettings::class)
        every { AgentSettings.getInstance(project) } returns agentSettings
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(AgentService::class)
        unmockkStatic(AgentSettings::class)
    }

    @Test
    fun `tool metadata is correct`() {
        val tool = DelegateTaskTool()
        assertEquals("delegate_task", tool.name)
        assertTrue(tool.parameters.required.containsAll(listOf("task", "worker_type", "context")))
        assertEquals(setOf(WorkerType.ORCHESTRATOR), tool.allowedWorkers)
    }

    @Test
    fun `returns error when task is too short`() = runTest {
        val tool = DelegateTaskTool()
        val params = buildJsonObject {
            put("task", "Do something short")
            put("worker_type", "coder")
            put("context", "Relevant context for src/main/kotlin/UserService.kt")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("50 characters"))
    }

    @Test
    fun `returns error when context has no file path`() = runTest {
        val tool = DelegateTaskTool()
        val params = buildJsonObject {
            put("task", "A" .repeat(50) + " analyze the code structure and dependencies carefully")
            put("worker_type", "analyzer")
            put("context", "Some context without any file path reference whatsoever")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("file path"))
    }

    @Test
    fun `returns error for invalid worker type`() = runTest {
        val tool = DelegateTaskTool()
        val params = buildJsonObject {
            put("task", "Analyze the user service layer for potential null pointer exceptions in all methods")
            put("worker_type", "invalid_type")
            put("context", "See src/main/kotlin/UserService.kt for the service implementation")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Invalid worker_type"))
    }

    @Test
    fun `returns error when worker limit reached`() = runTest {
        every { agentService.activeWorkerCount } returns AtomicInteger(5)

        val tool = DelegateTaskTool()
        val params = buildJsonObject {
            put("task", "Analyze the user service layer for potential null pointer exceptions in all methods")
            put("worker_type", "coder")
            put("context", "See src/main/kotlin/UserService.kt for the service implementation")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Maximum concurrent workers"))
    }

    @Test
    fun `returns error when session token limit exceeded`() = runTest {
        every { agentService.totalSessionTokens } returns AtomicLong(600_000)

        val tool = DelegateTaskTool()
        val params = buildJsonObject {
            put("task", "Analyze the user service layer for potential null pointer exceptions in all methods")
            put("worker_type", "analyzer")
            put("context", "See src/main/kotlin/UserService.kt for the service implementation")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Session token budget"))
    }

    @Test
    fun `returns error when required params missing`() = runTest {
        val tool = DelegateTaskTool()
        val params = buildJsonObject {
            put("task", "Analyze the user service layer for potential null pointer exceptions in all methods")
            put("context", "See src/main/kotlin/UserService.kt for the service implementation")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("worker_type"))
    }

    @Test
    fun `description mentions worker types`() {
        val tool = DelegateTaskTool()
        assertTrue(tool.description.contains("coder"), "Description should mention coder")
        assertTrue(tool.description.contains("analyzer"), "Description should mention analyzer")
    }
}
