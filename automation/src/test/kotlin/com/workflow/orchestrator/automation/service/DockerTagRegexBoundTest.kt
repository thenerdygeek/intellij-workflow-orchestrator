package com.workflow.orchestrator.automation.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Tests that [TagBuilderService.extractDockerTagFromLog] (a) still extracts
 * docker tags correctly from normal logs, and (b) completes quickly on
 * pathological large inputs.
 *
 * The second case verifies the fix for audit finding automation:F-5: the
 * method now scans only the first [TagBuilderService.SCAN_LINE_LIMIT] lines
 * (capped at [TagBuilderService.SCAN_CHAR_LIMIT] chars) instead of the entire
 * multi-MB string.
 */
class DockerTagRegexBoundTest {

    private val service = TagBuilderService(
        bambooService = io.mockk.mockk(),
        buildLogCache = null,
    )

    // ── Positive cases: normal extraction still works ─────────────────────────

    @Test
    fun `extracts docker tag from a simple log line`() {
        val log = """
            [INFO] Building image...
            Unique Docker Tag : 2.4.1
            [INFO] Build complete.
        """.trimIndent()
        val result = service.extractDockerTagFromLog(log)
        assertEquals("2.4.1", result)
    }

    @Test
    fun `extracts docker tag with extra whitespace around colon`() {
        val log = "Unique Docker Tag  :  release-20260524\n[INFO] Done."
        assertEquals("release-20260524", service.extractDockerTagFromLog(log))
    }

    @Test
    fun `strips ANSI escape sequences from extracted tag`() {
        val log = "Unique Docker Tag : [32m2.4.1[0m"
        assertEquals("2.4.1", service.extractDockerTagFromLog(log))
    }

    @Test
    fun `returns null when marker is absent`() {
        val log = "Nothing to see here\n[INFO] Build finished."
        assertNull(service.extractDockerTagFromLog(log))
    }

    @Test
    fun `returns null for blank value after marker`() {
        val log = "Unique Docker Tag : "
        assertNull(service.extractDockerTagFromLog(log))
    }

    @Test
    fun `extracts tag when marker is within first SCAN_LINE_LIMIT lines`() {
        val prefix = (1..10).joinToString("\n") { "line $it" }
        val suffix = (1..10).joinToString("\n") { "tail $it" }
        val log = "$prefix\nUnique Docker Tag : 1.2.3\n$suffix"
        assertEquals("1.2.3", service.extractDockerTagFromLog(log))
    }

    // ── Negative cases: marker beyond scan window is not found ────────────────

    @Test
    fun `marker beyond SCAN_LINE_LIMIT is not found`() {
        // Build a log where the marker appears on line SCAN_LINE_LIMIT + 1
        val noise = (1..(TagBuilderService.SCAN_LINE_LIMIT + 5))
            .joinToString("\n") { "noise line $it" }
        val log = "$noise\nUnique Docker Tag : 9.9.9"
        // Should return null because the marker is beyond the scan window
        assertNull(service.extractDockerTagFromLog(log))
    }

    // ── Performance: pathological large input completes within 2 seconds ──────

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `pathological large log is processed within 2 seconds`() {
        // Create a 3 MB log with no docker-tag marker — pure noise
        val line = "X".repeat(200)  // 200-char lines
        val sb = StringBuilder()
        repeat(15_000) { sb.append(line).append('\n') }
        val hugeLog = sb.toString()  // ~3 MB

        // Must complete quickly — the slice cap prevents full-string scan
        val result = service.extractDockerTagFromLog(hugeLog)
        assertNull(result)  // no tag in this noise log
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `pathological log with tag in bounded window is extracted quickly`() {
        val header = (1..50).joinToString("\n") { "build step $it output" }
        val tagLine = "Unique Docker Tag : fast-build-123"
        val tail = "X".repeat(200).let { line ->
            (1..15_000).joinToString("\n") { line }
        }
        val hugeLog = "$header\n$tagLine\n$tail"  // tag is in the first 52 lines

        val result = service.extractDockerTagFromLog(hugeLog)
        assertNotNull(result)
        assertEquals("fast-build-123", result)
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `log exceeding SCAN_CHAR_LIMIT is capped before line-split`() {
        // Single-line log (no newlines) larger than SCAN_CHAR_LIMIT — should not scan it all
        val bigLine = "Z".repeat(TagBuilderService.SCAN_CHAR_LIMIT * 3)
        // No marker — just ensure it returns quickly without exception
        val result = service.extractDockerTagFromLog(bigLine)
        assertNull(result)
    }
}
