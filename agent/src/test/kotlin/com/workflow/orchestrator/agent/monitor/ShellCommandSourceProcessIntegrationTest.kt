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
    fun `stop terminates the entire process subtree`() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val started = java.util.concurrent.CountDownLatch(1)
        // `& wait` makes the bash wrapper a compound command (no exec-collapse): process=bash with a
        // backgrounded `sh` child (+ `sleep` grandchild). At stop() the bash parent is alive, so
        // process.descendants() includes the `sh`/`sleep` subtree. Killing only the parent (old
        // behavior) leaves orphans alive past the poll on Linux/CI; killing the tree (the fix) reaps them.
        val src = ShellCommandSource(
            monitorId = "tree-test", description = "tree",
            command = "sh -c 'while true; do echo tick-match; sleep 0.2; done' & wait",
            filter = Regex("match"), workingDir = null, cs = scope, project = null,
        )
        try {
            src.start { started.countDown() }
            org.junit.jupiter.api.Assertions.assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS), "process never emitted")
            Thread.sleep(300) // let the sh/sleep descendants spawn
            val handle = src.processHandleForTest()
            org.junit.jupiter.api.Assertions.assertTrue(handle != null && handle.isAlive, "expected a live tracked process")
            val descendants = handle!!.descendants().toList()  // snapshot before stop
            org.junit.jupiter.api.Assertions.assertTrue(descendants.isNotEmpty(), "expected a child subtree (& wait should not exec-collapse)")
            src.stop()
            // Poll up to 3s for the tracked process AND every snapshotted descendant to die.
            val deadline = System.currentTimeMillis() + 3000
            fun allDead() = !handle.isAlive && descendants.none { it.isAlive }
            while (!allDead() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            org.junit.jupiter.api.Assertions.assertTrue(allDead(),
                "stop must terminate the whole subtree; still alive: " +
                    (listOf(handle).filter { it.isAlive }.map { it.pid() } + descendants.filter { it.isAlive }.map { it.pid() }))
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
