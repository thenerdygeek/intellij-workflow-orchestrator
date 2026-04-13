package com.workflow.orchestrator.agent.session

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SessionLockTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `acquire succeeds on first attempt`() {
        val sessionDir = File(tempDir.toFile(), "session-1").also { it.mkdirs() }
        val lock = SessionLock.tryAcquire(sessionDir)
        assertNotNull(lock)
        lock!!.release()
    }

    @Test
    fun `second acquire on same dir fails`() {
        val sessionDir = File(tempDir.toFile(), "session-1").also { it.mkdirs() }
        val lock1 = SessionLock.tryAcquire(sessionDir)
        assertNotNull(lock1)
        val lock2 = SessionLock.tryAcquire(sessionDir)
        assertNull(lock2)
        lock1!!.release()
    }

    @Test
    fun `after release, can re-acquire`() {
        val sessionDir = File(tempDir.toFile(), "session-1").also { it.mkdirs() }
        val lock1 = SessionLock.tryAcquire(sessionDir)!!
        lock1.release()
        val lock2 = SessionLock.tryAcquire(sessionDir)
        assertNotNull(lock2)
        lock2!!.release()
    }
}
