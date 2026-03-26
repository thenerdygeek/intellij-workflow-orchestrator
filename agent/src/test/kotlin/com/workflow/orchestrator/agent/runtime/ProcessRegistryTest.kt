package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

class ProcessRegistryTest {

    @AfterEach
    fun cleanup() {
        ProcessRegistry.killAll()
    }

    // -----------------------------------------------------------------------
    // register / get
    // -----------------------------------------------------------------------

    @Test
    fun `register and get managed process`() {
        val process = ProcessBuilder("sleep", "10").start()
        val managed = ProcessRegistry.register("tc-1", process, "sleep 10")

        assertEquals("tc-1", managed.toolCallId)
        assertEquals("sleep 10", managed.command)
        assertEquals(process, managed.process)
        assertEquals(process.outputStream, managed.stdin)
        assertTrue(managed.startedAt > 0)
        assertEquals(0L, managed.lastOutputAt.get())
        assertEquals(0L, managed.idleSignaledAt.get())
        assertEquals(0, managed.stdinCount.get())
        assertTrue(managed.outputLines.isEmpty())

        val fetched = ProcessRegistry.get("tc-1")
        assertNotNull(fetched)
        assertEquals(managed, fetched)
    }

    @Test
    fun `get returns null for unknown ID`() {
        assertNull(ProcessRegistry.get("no-such-id"))
    }

    // -----------------------------------------------------------------------
    // writeStdin
    // -----------------------------------------------------------------------

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `writeStdin sends data to process`() {
        // 'cat' echoes whatever is written to its stdin
        val process = ProcessBuilder("cat").start()
        ProcessRegistry.register("tc-cat", process, "cat")

        val result = ProcessRegistry.writeStdin("tc-cat", "hello\n")
        assertTrue(result)

        // Give cat a moment to echo, then read a line back
        process.inputStream.bufferedReader().use { reader ->
            val line = reader.readLine()
            assertEquals("hello", line)
        }
    }

    @Test
    fun `writeStdin returns false for dead process`() {
        val process = ProcessBuilder("sleep", "0").start()
        process.waitFor() // let it die
        ProcessRegistry.register("tc-dead", process, "sleep 0")

        assertFalse(process.isAlive, "Process should be dead before test assertion")
        val result = ProcessRegistry.writeStdin("tc-dead", "data\n")
        assertFalse(result)
    }

    @Test
    fun `writeStdin returns false for unknown ID`() {
        assertFalse(ProcessRegistry.writeStdin("no-such-id", "data\n"))
    }

    // -----------------------------------------------------------------------
    // stdinCount
    // -----------------------------------------------------------------------

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `stdinCount tracks writes per process`() {
        val process = ProcessBuilder("cat").start()
        ProcessRegistry.register("tc-count", process, "cat")

        ProcessRegistry.writeStdin("tc-count", "line1\n")
        ProcessRegistry.writeStdin("tc-count", "line2\n")
        ProcessRegistry.writeStdin("tc-count", "line3\n")

        val managed = ProcessRegistry.get("tc-count")
        assertNotNull(managed)
        assertEquals(3, managed!!.stdinCount.get())
    }

    // -----------------------------------------------------------------------
    // kill
    // -----------------------------------------------------------------------

    @Test
    fun `kill removes and destroys process`() {
        val process = ProcessBuilder("sleep", "60").start()
        ProcessRegistry.register("tc-kill", process, "sleep 60")
        assertTrue(ProcessRegistry.isRunning("tc-kill"))

        val killed = ProcessRegistry.kill("tc-kill")
        assertTrue(killed)
        assertNull(ProcessRegistry.get("tc-kill"))
        process.waitFor()
        assertFalse(process.isAlive)
    }

    // -----------------------------------------------------------------------
    // reapIdleProcesses
    // -----------------------------------------------------------------------

    @Test
    fun `reapIdleProcesses kills processes idle past threshold`() {
        val process = ProcessBuilder("sleep", "60").start()
        val managed = ProcessRegistry.register("tc-reap", process, "sleep 60")

        // Simulate idle signal set 70 seconds ago
        val seventySecondsAgo = System.currentTimeMillis() - 70_000
        managed.idleSignaledAt.set(seventySecondsAgo)

        ProcessRegistry.reapIdleProcesses(maxIdleSinceSignalMs = 60_000)

        assertNull(ProcessRegistry.get("tc-reap"))
        process.waitFor()
        assertFalse(process.isAlive)
    }

    @Test
    fun `reapIdleProcesses does not kill non-idle processes`() {
        val process = ProcessBuilder("sleep", "60").start()
        val managed = ProcessRegistry.register("tc-keep", process, "sleep 60")

        // idleSignaledAt is 0 — not idle
        assertEquals(0L, managed.idleSignaledAt.get())

        ProcessRegistry.reapIdleProcesses(maxIdleSinceSignalMs = 60_000)

        assertNotNull(ProcessRegistry.get("tc-keep"))
        assertTrue(process.isAlive)
    }

    // -----------------------------------------------------------------------
    // getIdleProcesses
    // -----------------------------------------------------------------------

    @Test
    fun `getIdleProcesses returns processes idle past threshold`() {
        val process = ProcessBuilder("sleep", "60").start()
        val managed = ProcessRegistry.register("idle-get-test", process, "sleep 60")
        managed.lastOutputAt.set(System.currentTimeMillis() - 20_000) // 20s ago

        val idle = ProcessRegistry.getIdleProcesses(15_000)
        assertEquals(1, idle.size)
        assertEquals("idle-get-test", idle.first().toolCallId)

        val notIdle = ProcessRegistry.getIdleProcesses(30_000)
        assertTrue(notIdle.isEmpty())

        process.destroyForcibly()
    }

    @Test
    fun `reapIdleProcesses does not kill processes idle for less than threshold`() {
        val process = ProcessBuilder("sleep", "60").start()
        val managed = ProcessRegistry.register("tc-recent", process, "sleep 60")

        // Signaled only 10 seconds ago, threshold is 60s
        val tenSecondsAgo = System.currentTimeMillis() - 10_000
        managed.idleSignaledAt.set(tenSecondsAgo)

        ProcessRegistry.reapIdleProcesses(maxIdleSinceSignalMs = 60_000)

        assertNotNull(ProcessRegistry.get("tc-recent"))
        assertTrue(process.isAlive)
    }
}
