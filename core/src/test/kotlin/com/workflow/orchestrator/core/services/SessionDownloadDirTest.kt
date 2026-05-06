package com.workflow.orchestrator.core.services

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SessionDownloadDirTest {

    @Test
    fun `current returns null when no element installed`() = runTest {
        assertNull(SessionDownloadDir.current())
    }

    @Test
    fun `current returns the installed downloads dir inside the scope`() = runTest {
        val dir = Path.of("/tmp/wo-test-session/downloads")
        withContext(SessionDownloadDir(dir)) {
            assertEquals(dir, SessionDownloadDir.current())
        }
    }

    @Test
    fun `current returns null again after the scope exits`() = runTest {
        val dir = Path.of("/tmp/wo-test-session/downloads")
        withContext(SessionDownloadDir(dir)) {
            assertEquals(dir, SessionDownloadDir.current())
        }
        assertNull(SessionDownloadDir.current())
    }
}
