package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FileGuardTest {

    private lateinit var guard: FileGuard

    @BeforeEach
    fun setup() {
        guard = FileGuard()
    }

    @Test
    fun `lockFile acquires lock on new path`() {
        assertTrue(guard.lockFile("/src/Main.kt"))
    }

    @Test
    fun `lockFile returns false for already locked path`() {
        guard.lockFile("/src/Main.kt")
        assertFalse(guard.lockFile("/src/Main.kt"))
    }

    @Test
    fun `unlockFile releases a locked path`() {
        guard.lockFile("/src/Main.kt")
        assertTrue(guard.unlockFile("/src/Main.kt"))
    }

    @Test
    fun `unlockFile returns false for unlocked path`() {
        assertFalse(guard.unlockFile("/src/Main.kt"))
    }

    @Test
    fun `isLocked returns true for locked file`() {
        guard.lockFile("/src/Main.kt")
        assertTrue(guard.isLocked("/src/Main.kt"))
    }

    @Test
    fun `isLocked returns false for unlocked file`() {
        assertFalse(guard.isLocked("/src/Main.kt"))
    }

    @Test
    fun `isLocked returns false after unlock`() {
        guard.lockFile("/src/Main.kt")
        guard.unlockFile("/src/Main.kt")
        assertFalse(guard.isLocked("/src/Main.kt"))
    }

    @Test
    fun `getLockedFiles returns all locked paths`() {
        guard.lockFile("/src/Main.kt")
        guard.lockFile("/src/Utils.kt")

        val locked = guard.getLockedFiles()
        assertEquals(2, locked.size)
    }

    @Test
    fun `getLockedFiles returns empty set when nothing locked`() {
        assertTrue(guard.getLockedFiles().isEmpty())
    }

    @Test
    fun `clearAll removes all locks`() {
        guard.lockFile("/src/Main.kt")
        guard.lockFile("/src/Utils.kt")
        guard.clearAll()

        assertTrue(guard.getLockedFiles().isEmpty())
        assertFalse(guard.isLocked("/src/Main.kt"))
    }

    @Test
    fun `can re-lock after unlock`() {
        guard.lockFile("/src/Main.kt")
        guard.unlockFile("/src/Main.kt")
        assertTrue(guard.lockFile("/src/Main.kt"))
    }

    @Test
    fun `multiple files can be locked independently`() {
        guard.lockFile("/src/A.kt")
        guard.lockFile("/src/B.kt")

        assertTrue(guard.isLocked("/src/A.kt"))
        assertTrue(guard.isLocked("/src/B.kt"))

        guard.unlockFile("/src/A.kt")
        assertFalse(guard.isLocked("/src/A.kt"))
        assertTrue(guard.isLocked("/src/B.kt"))
    }
}
