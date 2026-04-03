package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.EventSourcedContextBridge
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SingleAgentSessionSteeringTest {

    private lateinit var session: SingleAgentSession
    private lateinit var brain: LlmBrain
    private lateinit var bridge: EventSourcedContextBridge
    private lateinit var project: Project
    private lateinit var steeringChannel: SteeringChannel

    @BeforeEach
    fun setup() {
        steeringChannel = SteeringChannel()
        session = SingleAgentSession(maxIterations = 5)
        brain = mockk()
        bridge = mockk(relaxed = true)
        project = mockk()

        every { bridge.getMessages() } returns listOf(
            ChatMessage(role = "system", content = "You are an AI coding assistant"),
            ChatMessage(role = "user", content = "Do something")
        )
        every { bridge.currentTokens } returns 1000
        every { bridge.remainingBudget() } returns 149_000
        every { bridge.tokenUtilization } returns 0.01

        coEvery { brain.chatStream(any(), any(), any(), any()) } throws NotImplementedError("test fallback")
    }

    @Test
    fun `steering message injected into bridge at iteration boundary`() = runTest {
        // Enqueue a steering message before execution starts
        steeringChannel.enqueue("Actually, focus on the tests instead")

        // LLM returns a simple completion (no tools)
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
            ChatCompletionResponse(
                id = "test-steering",
                choices = listOf(
                    Choice(
                        index = 0,
                        message = ChatMessage(role = "assistant", content = "OK, focusing on tests now."),
                        finishReason = "stop"
                    )
                ),
                usage = UsageInfo(promptTokens = 500, completionTokens = 50, totalTokens = 550)
            )
        )

        session.execute(
            task = "Fix the bug",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            bridge = bridge,
            project = project,
            steeringChannel = steeringChannel
        )

        // Verify steering message was injected into the bridge
        verify(atLeast = 1) { bridge.addSteeringMessage("Actually, focus on the tests instead") }
    }

    @Test
    fun `no steering injection when channel is empty`() = runTest {
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
            ChatCompletionResponse(
                id = "test-no-steering",
                choices = listOf(
                    Choice(
                        index = 0,
                        message = ChatMessage(role = "assistant", content = "Done."),
                        finishReason = "stop"
                    )
                ),
                usage = UsageInfo(promptTokens = 500, completionTokens = 50, totalTokens = 550)
            )
        )

        session.execute(
            task = "Fix the bug",
            tools = emptyMap(),
            toolDefinitions = emptyList(),
            brain = brain,
            bridge = bridge,
            project = project,
            steeringChannel = steeringChannel
        )

        verify(exactly = 0) { bridge.addSteeringMessage(any()) }
    }
}
