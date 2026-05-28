package com.workflow.orchestrator.agent.delegation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DelegatedExecutionWiringTest {
    private fun agentRoot(): java.io.File {
        val d = System.getProperty("user.dir")
        return if (java.io.File("$d/src/main/kotlin").isDirectory) java.io.File("$d/src/main/kotlin")
               else java.io.File("$d/agent/src/main/kotlin")
    }
    private fun startDelegatedBody(): String {
        val s = java.io.File(agentRoot(), "com/workflow/orchestrator/agent/AgentService.kt").readText()
        return s.substringAfter("fun startDelegatedSession(").substringBefore("\n    fun ")
    }
    @Test fun `delegated executeTask call wires approval gate and streaming callbacks`() {
        val body = startDelegatedBody()
        val call = body.substringAfter("executeTask(").substringBefore("\n        )")
        assertTrue(call.contains("approvalGate"), "startDelegatedSession's executeTask call must forward approvalGate")
        assertTrue(call.contains("onStreamChunk") && call.contains("onToolCall"), "must forward streaming callbacks")
    }
}
