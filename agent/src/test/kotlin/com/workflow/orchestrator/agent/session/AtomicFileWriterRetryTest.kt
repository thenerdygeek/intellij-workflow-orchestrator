package com.workflow.orchestrator.agent.session

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Regression coverage for the Windows ATOMIC_MOVE flakiness that surfaced as
 * `AccessDeniedException: ...ui_messages.json.tmp.<ts>.<rand> -> ...ui_messages.json`
 * during streaming persistence. On Windows, AV scanners and the IDE's VFS
 * refresher can briefly hold a handle on the destination file; ATOMIC_MOVE
 * then fails outright rather than waiting.
 *
 * Tests use the `moverOverride` seam — production code uses the default mover
 * that calls `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`; tests inject a
 * mover that throws on N calls before delegating to the real one.
 */
class AtomicFileWriterRetryTest {

    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun cleanup() {
        AtomicFileWriter.moverOverride = null
    }

    @Test
    fun `retries when mover throws AccessDeniedException and eventually succeeds`() {
        val target = File(tempDir.toFile(), "ui_messages.json")
        var callCount = 0
        AtomicFileWriter.moverOverride = { src, dst ->
            callCount++
            if (callCount <= 2) {
                throw AccessDeniedException(src.toString())
            }
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        AtomicFileWriter.write(target, "content")

        assertEquals(3, callCount, "Should retry twice then succeed on third call")
        assertEquals("content", target.readText())
    }

    @Test
    fun `propagates AccessDeniedException after exhausting retries`() {
        val target = File(tempDir.toFile(), "ui_messages.json")
        var callCount = 0
        AtomicFileWriter.moverOverride = { _, _ ->
            callCount++
            throw AccessDeniedException("locked")
        }

        val ex = assertThrows(IOException::class.java) {
            AtomicFileWriter.write(target, "content")
        }
        assertTrue(ex is AccessDeniedException, "Expected AccessDeniedException, got ${ex.javaClass.name}")
        assertTrue(callCount > 1, "Should attempt more than once before giving up (was $callCount)")
    }

    @Test
    fun `does not retry non-AccessDeniedException errors`() {
        val target = File(tempDir.toFile(), "ui_messages.json")
        var callCount = 0
        AtomicFileWriter.moverOverride = { _, _ ->
            callCount++
            throw IOException("different IO error")
        }

        assertThrows(IOException::class.java) {
            AtomicFileWriter.write(target, "content")
        }
        assertEquals(1, callCount, "Non-AccessDeniedException must fail fast (no retry)")
    }
}
