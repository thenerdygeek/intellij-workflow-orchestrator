package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AgentServiceQueueOwnershipContractTest {
    private val src = File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt").readText()

    @Test fun `enqueueToSession auto-wakes only when the policy allows and the loop is idle`() {
        val fn = src.substringAfter("fun enqueueToSession").substringBefore("\n    fun ")
        assertTrue(fn.contains("activeLoopForSession"), "must branch on live-vs-idle")
        assertTrue(fn.contains("autoWakesIdle"), "must consult the policy before waking")
        assertTrue(fn.contains("autoWakeIdleSession"), "idle path reuses the shared guarded waker")
    }

    @Test fun `queueForSession is backed by QueuePersistence`() {
        assertTrue(src.contains("QueuePersistence("), "per-session queues persist durable items")
    }
}
