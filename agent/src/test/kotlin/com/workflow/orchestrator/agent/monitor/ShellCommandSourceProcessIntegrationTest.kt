package com.workflow.orchestrator.agent.monitor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    @org.junit.jupiter.api.Test
    fun `stop halts delivery promptly even when a child process holds the pipe`() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val events = java.util.concurrent.ConcurrentLinkedQueue<MonitorEvent>()
        // Nested shell: the bash wrapper spawns `sh -c` which runs the emitting loop in a CHILD.
        // Killing only the direct process orphans the child, which keeps emitting — this reproduces the bug.
        val src = ShellCommandSource(
            monitorId = "stop-test", description = "stop",
            command = "sh -c 'while true; do echo tick-match; sleep 0.2; done'",
            filter = Regex("match"), workingDir = null, cs = scope, project = null,
        )
        try {
            src.start { events.add(it) }
            val deadline = System.currentTimeMillis() + 5000
            while (events.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            org.junit.jupiter.api.Assertions.assertTrue(events.isNotEmpty(), "expected at least one event before stop")
            src.stop()
            val countAtStop = events.size
            Thread.sleep(2000)   // ~10 more ticks would arrive if the tree weren't killed
            val leaked = events.toList().drop(countAtStop).map { it.line }
            org.junit.jupiter.api.Assertions.assertEquals(countAtStop, events.size,
                "no events should arrive after stop; leaked: $leaked")
        } finally { src.stop(); scope.cancel() }
    }

    @org.junit.jupiter.api.Test
    fun `natural process exit fires onExit with the exit code`() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val exitLatch = java.util.concurrent.CountDownLatch(1)
        val exitCode = java.util.concurrent.atomic.AtomicReference<Int?>(null)
        val src = ShellCommandSource(
            monitorId = "exit-test", description = "exit",
            command = "echo go-match; exit 3",
            filter = Regex("match"), workingDir = null, cs = scope, project = null,
            onExit = { code -> exitCode.set(code); exitLatch.countDown() },
        )
        try {
            src.start { }
            org.junit.jupiter.api.Assertions.assertTrue(exitLatch.await(10, java.util.concurrent.TimeUnit.SECONDS), "onExit was not called on natural exit")
            org.junit.jupiter.api.Assertions.assertEquals(3, exitCode.get())
        } finally { src.stop(); scope.cancel() }
    }
}
