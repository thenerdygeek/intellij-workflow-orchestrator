package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AgentServiceBackgroundWiringTest {

    private val src = File(
        "src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt",
    ).readText()

    @Test
    fun `owns executor and registry and passes them to the loop`() {
        assertTrue(src.contains("BackgroundToolExecutor("), "BackgroundToolExecutor field must be declared")
        assertTrue(
            src.contains("backgroundExecutor = backgroundToolExecutor"),
            "must pass backgroundExecutor to AgentLoop",
        )
        assertTrue(
            src.contains(
                "backgroundEnabled = { AgentSettings.getInstance(project).state.allowToolsRunInBackground }",
            ),
            "must pass backgroundEnabled to AgentLoop",
        )
        assertTrue(
            src.contains("backgroundCap = agentSettings.state.maxBackgroundedToolsPerSession"),
            "must pass backgroundCap to AgentLoop",
        )
        assertTrue(
            src.contains("backgroundInFlightCount = { backgroundToolRegistry.countForSession(sid) }"),
            "must pass backgroundInFlightCount to AgentLoop",
        )
    }

    @Test
    fun `delivers via enqueueToSession with BACKGROUND kind and coalesceKey`() {
        assertTrue(src.contains("deliverBackgroundResult"), "deliverBackgroundResult method must be present")
        assertTrue(src.contains("QueueSourceKind.BACKGROUND"), "must enqueue with BACKGROUND kind")
        assertTrue(src.contains("coalesceKey = handle.toolCallId"), "must coalesce by toolCallId")
    }

    @Test
    fun `passes backgroundTasks from registry into environmentDetailsProvider`() {
        assertTrue(
            src.contains("backgroundTasks = backgroundToolRegistry.list(sid)"),
            "must pass backgroundTasks list from registry into EnvironmentDetailsBuilder",
        )
    }

    @Test
    fun `cancels background jobs on new chat task cancel and dispose`() {
        assertTrue(
            src.contains("backgroundToolExecutor.cancelAllForSession"),
            "must call cancelAllForSession in resetForNewChat and/or cancelCurrentTask",
        )
        assertTrue(
            src.contains("backgroundToolExecutor.dispose()"),
            "must dispose backgroundToolExecutor in dispose()",
        )
    }
}
