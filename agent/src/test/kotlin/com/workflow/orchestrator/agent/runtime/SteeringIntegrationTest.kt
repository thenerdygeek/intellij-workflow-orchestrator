package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.Choice
import com.workflow.orchestrator.agent.api.dto.FunctionCall
import com.workflow.orchestrator.agent.api.dto.FunctionDefinition
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ToolCall
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.api.dto.UsageInfo
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.EventSourcedContextBridge
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SteeringIntegrationTest {

    private lateinit var brain: LlmBrain
    private lateinit var bridge: EventSourcedContextBridge
    private lateinit var project: Project
    private lateinit var steeringChannel: SteeringChannel

    @BeforeEach
    fun setup() {
        brain = mockk()
        bridge = mockk(relaxed = true)
        project = mockk()
        steeringChannel = SteeringChannel()

        every { bridge.getMessages() } returns listOf(
            ChatMessage(role = "system", content = "system"),
            ChatMessage(role = "user", content = "task")
        )
        every { bridge.currentTokens } returns 1000
        every { bridge.remainingBudget() } returns 149_000
        every { bridge.tokenUtilization } returns 0.01

        coEvery { brain.chatStream(any(), any(), any(), any()) } throws NotImplementedError("test")
    }

    private fun toolCallResponse(id: String, toolName: String, arguments: String): ChatCompletionResponse =
        ChatCompletionResponse(
            id = id,
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = id,
                                function = FunctionCall(name = toolName, arguments = arguments)
                            )
                        )
                    ),
                    finishReason = "tool_calls"
                )
            ),
            usage = UsageInfo(promptTokens = 500, completionTokens = 100, totalTokens = 600)
        )

    private fun textResponse(id: String, content: String): ChatCompletionResponse =
        ChatCompletionResponse(
            id = id,
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(role = "assistant", content = content),
                    finishReason = "stop"
                )
            ),
            usage = UsageInfo(promptTokens = 600, completionTokens = 50, totalTokens = 650)
        )

    private fun buildReadTool(): AgentTool {
        val readTool = mockk<AgentTool>()
        every { readTool.name } returns "read_file"
        every { readTool.description } returns "Read a file"
        every { readTool.parameters } returns FunctionParameters(properties = emptyMap())
        every { readTool.allowedWorkers } returns emptySet()
        every { readTool.toToolDefinition() } returns ToolDefinition(
            function = FunctionDefinition(
                name = "read_file",
                description = "Read a file",
                parameters = FunctionParameters(properties = emptyMap())
            )
        )
        coEvery { readTool.execute(any(), any()) } returns ToolResult(
            content = "file contents here",
            summary = "Read file",
            tokenEstimate = 10
        )
        return readTool
    }

    @Test
    fun `steering message appears in bridge before second LLM call`() = runTest {
        var llmCallCount = 0

        coEvery { brain.chat(any(), any(), any(), any()) } answers {
            llmCallCount++
            if (llmCallCount == 1) {
                // After first LLM call, simulate user sending a steering message
                steeringChannel.enqueue("Skip the database migration, focus on API")
                ApiResult.Success(toolCallResponse("tc1", "read_file", """{"path":"src/Main.kt"}"""))
            } else {
                ApiResult.Success(textResponse("done", "OK, focusing on API layer as requested."))
            }
        }

        val readTool = buildReadTool()

        val session = SingleAgentSession(maxIterations = 5)
        session.execute(
            task = "Fix the migration",
            tools = mapOf("read_file" to readTool),
            toolDefinitions = listOf(readTool.toToolDefinition()),
            brain = brain,
            bridge = bridge,
            project = project,
            steeringChannel = steeringChannel
        )

        // Verify steering message was injected into the bridge
        verify { bridge.addSteeringMessage("Skip the database migration, focus on API") }
        // Verify at least 2 LLM calls happened
        assertTrue(llmCallCount >= 2)
    }

    @Test
    fun `multiple steering messages drained together`() = runTest {
        steeringChannel.enqueue("First redirection")
        steeringChannel.enqueue("Second redirection")

        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
            textResponse("done", "Adjusting approach.")
        )

        val session = SingleAgentSession(maxIterations = 3)
        session.execute(
            task = "Do work",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            bridge = bridge,
            project = project,
            steeringChannel = steeringChannel
        )

        verify { bridge.addSteeringMessage("First redirection") }
        verify { bridge.addSteeringMessage("Second redirection") }
    }

    @Test
    fun `steering channel cleared after drain`() = runTest {
        steeringChannel.enqueue("Only once")

        var llmCallCount = 0
        coEvery { brain.chat(any(), any(), any(), any()) } answers {
            llmCallCount++
            if (llmCallCount <= 2) {
                ApiResult.Success(
                    toolCallResponse(
                        "tc$llmCallCount",
                        "read_file",
                        """{"path":"file$llmCallCount.kt"}"""
                    )
                )
            } else {
                ApiResult.Success(textResponse("done", "Done."))
            }
        }

        val readTool = buildReadTool()

        val session = SingleAgentSession(maxIterations = 5)
        session.execute(
            task = "Do work",
            tools = mapOf("read_file" to readTool),
            toolDefinitions = listOf(readTool.toToolDefinition()),
            brain = brain,
            bridge = bridge,
            project = project,
            steeringChannel = steeringChannel
        )

        // Should be called exactly once — drained in first iteration, not re-injected in subsequent iterations
        verify(exactly = 1) { bridge.addSteeringMessage("Only once") }
    }
}
