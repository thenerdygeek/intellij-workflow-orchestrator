package com.workflow.orchestrator.agent.monitor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShellCommandSourceProcessIntegrationTest {
    @Test
    fun `real shell command emits NOTABLE and ALERT events for matching stdout lines`() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val events = ConcurrentLinkedQueue<MonitorEvent>()
        val latch = CountDownLatch(2)   // expect 2 matching lines
        // printf emits 3 lines; filter matches 2 of them. "Exception: boom" must escalate to ALERT.
        val src = ShellCommandSource(
            monitorId = "it-shell",
            description = "integration shell",
            command = "printf 'progress line\\nbuild done\\nException: boom\\n'",
            filter = Regex("done|Exception"),
            workingDir = null,
            cs = scope,
            project = null,
        )
        try {
            src.start { e -> events.add(e); latch.countDown() }
            assertTrue(latch.await(10, TimeUnit.SECONDS), "did not receive 2 events from the real process in time")
        } finally {
            src.stop()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
        val list = events.toList()
        assertEquals(2, list.size, "expected exactly 2 matching events, got: ${list.map { it.line }}")
        val byLine = list.associateBy { it.line }
        assertEquals(Severity.NOTABLE, byLine["build done"]?.severity, "build done should be NOTABLE")
        assertEquals(Severity.ALERT, byLine["Exception: boom"]?.severity, "Exception line should escalate to ALERT")
        assertTrue(list.all { it.monitorId == "it-shell" })
    }

    @Test
    fun `non-matching command output produces no events`() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val events = ConcurrentLinkedQueue<MonitorEvent>()
        val src = ShellCommandSource(
            monitorId = "it-quiet",
            description = "quiet",
            command = "printf 'nothing interesting here\\nstill nothing\\n'",
            filter = Regex("ERROR|FAILED"),
            workingDir = null, cs = scope, project = null,
        )
        try {
            src.start { e -> events.add(e) }
            Thread.sleep(1500)   // give the (short-lived) process time to run to completion
        } finally {
            src.stop(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
        assertEquals(0, events.size, "no lines match the filter; got: ${events.toList().map { it.line }}")
    }
}
