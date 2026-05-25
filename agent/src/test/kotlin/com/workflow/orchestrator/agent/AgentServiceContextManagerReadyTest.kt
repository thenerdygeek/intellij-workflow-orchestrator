package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentServiceContextManagerReadyTest {
    private val src = java.io.File(
        "src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt"
    ).readText()

    @Test
    fun `executeTask exposes onContextManagerReady and invokes it with the resolved ctx`() {
        assertTrue(src.contains("onContextManagerReady"), "param missing")
        // Invoked right after ctx is resolved so callers hold the live manager.
        assertTrue(
            src.contains("onContextManagerReady?.invoke(ctx)"),
            "ctx not reported back to caller"
        )
    }

    @Test
    fun `executeTask threads onHandoffProposed into the AgentLoop`() {
        assertTrue(src.contains("onHandoffProposed"), "onHandoffProposed not threaded")
    }

    @Test
    fun `startHandoffSession forwards onSessionStarted and onContextManagerReady to executeTask`() {
        val block = src.substringAfter("fun startHandoffSession(").substringBefore("\n    fun ")
        assertTrue(block.contains("onSessionStarted"), "handoff must forward onSessionStarted")
        assertTrue(block.contains("onContextManagerReady"), "handoff must forward onContextManagerReady")
        assertTrue(block.contains("onHandoffProposed"), "handoff must forward onHandoffProposed")
    }

    @Test
    fun `resumeSession forwards onContextManagerReady to executeTask`() {
        val block = src.substringAfter("fun resumeSession(").substringBefore("\n    fun ")
        assertTrue(block.contains("onContextManagerReady"), "resume must forward onContextManagerReady")
    }
}
