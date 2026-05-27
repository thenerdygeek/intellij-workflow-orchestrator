package com.workflow.orchestrator.automation.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Tests that [TagBuilderService.extractDockerTagFromLog] (a) extracts docker tags
 * correctly regardless of where the marker sits in a concatenated multi-job log,
 * and (b) stays bounded/quick on pathological large inputs.
 *
 * The scan reaches up to [TagBuilderService.MAX_SCAN_CHARS] (not just a head window),
 * because job logs are concatenated in Bamboo's unstable REST order — the marker's job
 * may be ordered last. `find()` short-circuits at the first match, preserving the perf
 * intent of audit finding automation:F-5 without its correctness bug (dropping
 * later-ordered tags).
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
    fun `extracts tag when marker is within the first few lines`() {
        val prefix = (1..10).joinToString("\n") { "line $it" }
        val suffix = (1..10).joinToString("\n") { "tail $it" }
        val log = "$prefix\nUnique Docker Tag : 1.2.3\n$suffix"
        assertEquals("1.2.3", service.extractDockerTagFromLog(log))
    }

    // ── Negative cases: marker beyond scan window is not found ────────────────

    @Test
    fun `marker far past the first 500 lines is still found (multi-job concat)`() {
        // Regression for the "build has no Unique Docker Tag" reports: when job logs are
        // concatenated in Bamboo's unstable order, the tag's job may sit well past the old
        // 500-line head window. The marker must still be found.
        val leadingJobLog = (1..2_000).joinToString("\n") { "earlier-job noise line $it" }
        val log = "$leadingJobLog\nUnique Docker Tag : feature-abc123\ntrailing output"
        assertEquals("feature-abc123", service.extractDockerTagFromLog(log))
    }

    @Test
    fun `marker beyond MAX_SCAN_CHARS is not found (scan stays bounded)`() {
        val noise = "X".repeat(TagBuilderService.MAX_SCAN_CHARS + 1_000)
        val log = "$noise\nUnique Docker Tag : 9.9.9"
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
    fun `large single-line log with no marker returns null quickly`() {
        // Single-line log (no newlines), larger than the old 64 KB head cap — the linear,
        // short-circuiting find() must still return quickly without exception.
        val bigLine = "Z".repeat(200_000)
        val result = service.extractDockerTagFromLog(bigLine)
        assertNull(result)
    }
}
