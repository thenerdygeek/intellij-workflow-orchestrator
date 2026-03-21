package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.runtime.AgentDefinitionRegistry
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SpawnAgentToolTest {

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
    fun `tool name is agent`() {
        val tool = SpawnAgentTool()
        assertEquals("agent", tool.name)
    }

    @Test
    fun `only ORCHESTRATOR can call this tool`() {
        val tool = SpawnAgentTool()
        assertEquals(setOf(WorkerType.ORCHESTRATOR), tool.allowedWorkers)
    }

    @Test
    fun `description and prompt are the only required parameters`() {
        val tool = SpawnAgentTool()
        assertEquals(listOf("description", "prompt"), tool.parameters.required)
    }

    @Test
    fun `has subagent_type and model as optional parameters`() {
        val tool = SpawnAgentTool()
        assertTrue(tool.parameters.properties.containsKey("subagent_type"))
        assertTrue(tool.parameters.properties.containsKey("model"))
        assertFalse(tool.parameters.required.contains("subagent_type"))
        assertFalse(tool.parameters.required.contains("model"))
    }

    @Test
    fun `missing description returns error`() = runTest {
        val tool = SpawnAgentTool()
        val params = buildJsonObject {
            put("prompt", "Do something complex")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("'description' parameter required"))
    }

    @Test
    fun `missing prompt returns error`() = runTest {
        val tool = SpawnAgentTool()
        val params = buildJsonObject {
            put("description", "Fix the bug")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("'prompt' parameter required"))
    }

    @Test
    fun `unknown subagent_type returns error with available types`() = runTest {
        val tool = SpawnAgentTool()

        // Mock registry with no custom agents
        val registry = mockk<AgentDefinitionRegistry> {
            every { getAgent("nonexistent") } returns null
            every { getAllAgents() } returns emptyList()
        }
        every { agentService.agentDefinitionRegistry } returns registry

        val params = buildJsonObject {
            put("description", "Test task")
            put("prompt", "Do something")
            put("subagent_type", "nonexistent")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Unknown subagent_type 'nonexistent'"))
        assertTrue(result.content.contains("general-purpose"))
        assertTrue(result.content.contains("explorer"))
        assertTrue(result.content.contains("coder"))
        assertTrue(result.content.contains("reviewer"))
        assertTrue(result.content.contains("tooler"))
    }

    @Test
    fun `custom agent name resolves to agent definition`() = runTest {
        val tool = SpawnAgentTool()

        val customDef = AgentDefinitionRegistry.AgentDefinition(
            name = "my-reviewer",
            description = "Custom code reviewer",
            systemPrompt = "You are a custom reviewer.",
            tools = listOf("read_file", "search_code"),
            maxTurns = 5,
            filePath = "/tmp/.workflow/agents/my-reviewer.md",
            scope = AgentDefinitionRegistry.AgentScope.PROJECT
        )

        val registry = mockk<AgentDefinitionRegistry> {
            every { getAgent("my-reviewer") } returns customDef
            every { getMemoryDirectory(customDef, project) } returns null
        }
        every { agentService.agentDefinitionRegistry } returns registry

        // We can't easily test the full execution without mocking WorkerSession,
        // but we can verify it doesn't return an "unknown" error
        val params = buildJsonObject {
            put("description", "Review code")
            put("prompt", "Review the changes in src/main/kotlin/Foo.kt")
            put("subagent_type", "my-reviewer")
        }

        // This will fail at the WorkerSession level (no brain mock), but should NOT
        // return the "Unknown subagent_type" error
        val result = tool.execute(params, project)
        assertFalse(result.content.contains("Unknown subagent_type"))
    }

    @Test
    fun `default subagent_type is general-purpose when omitted`() = runTest {
        val tool = SpawnAgentTool()

        // Mock registry returns null for "general-purpose" custom agent
        val registry = mockk<AgentDefinitionRegistry> {
            every { getAgent("general-purpose") } returns null
        }
        every { agentService.agentDefinitionRegistry } returns registry

        val params = buildJsonObject {
            put("description", "Complex task")
            put("prompt", "Do many things across the codebase")
        }

        // This will proceed past agent resolution (general-purpose is built-in)
        // and fail at WorkerSession level — but should NOT fail at resolution
        val result = tool.execute(params, project)
        assertFalse(result.content.contains("Unknown subagent_type"))
    }

    @Test
    fun `max concurrent workers returns error`() = runTest {
        val tool = SpawnAgentTool()
        every { agentService.activeWorkerCount } returns AtomicInteger(5)

        val params = buildJsonObject {
            put("description", "Test task")
            put("prompt", "Do something")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Maximum concurrent workers"))
    }

    @Test
    fun `session token budget exceeded returns error`() = runTest {
        val tool = SpawnAgentTool()
        every { agentService.totalSessionTokens } returns AtomicLong(600_000)

        val params = buildJsonObject {
            put("description", "Test task")
            put("prompt", "Do something")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Session token budget exceeded"))
    }

    @Test
    fun `built-in agent types are all recognized`() {
        val builtInTypes = SpawnAgentTool.BUILT_IN_AGENTS.keys
        assertTrue(builtInTypes.contains("general-purpose"))
        assertTrue(builtInTypes.contains("explorer"))
        assertTrue(builtInTypes.contains("coder"))
        assertTrue(builtInTypes.contains("reviewer"))
        assertTrue(builtInTypes.contains("tooler"))
        assertEquals(5, builtInTypes.size)
    }

    @Test
    fun `built-in types map to correct worker types`() {
        val mapping = SpawnAgentTool.BUILT_IN_AGENTS
        assertEquals(WorkerType.ORCHESTRATOR, mapping["general-purpose"]!!.workerType)
        assertEquals(WorkerType.ANALYZER, mapping["explorer"]!!.workerType)
        assertEquals(WorkerType.CODER, mapping["coder"]!!.workerType)
        assertEquals(WorkerType.REVIEWER, mapping["reviewer"]!!.workerType)
        assertEquals(WorkerType.TOOLER, mapping["tooler"]!!.workerType)
    }
}
