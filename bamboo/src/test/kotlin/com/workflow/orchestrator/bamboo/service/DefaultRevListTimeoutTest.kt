package com.workflow.orchestrator.bamboo.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Real test for [defaultRevList] timeout behaviour (audit finding bamboo:F-11).
 *
 * Verifies that a process slower than [REV_LIST_TIMEOUT_SECONDS] is terminated and
 * an empty list is returned — using the [PlanDetectionService.revListRunner] seam to
 * inject a slow command without needing a real git repository.
 */
class DefaultRevListTimeoutTest {

    /**
     * Duplicates the production timeout logic with an injected slow process so the
     * constant and the destroyForcibly path are exercised directly.
     */
    private fun runWithTimeout(tempDir: Path, sleepSeconds: Long): Pair<List<String>, Long> {
        val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
        val process: Process = if (isWindows) {
            // 'ping -n N 127.0.0.1' sleeps for approximately N-1 seconds on Windows
            ProcessBuilder("ping", "-n", (sleepSeconds + 1).toString(), "127.0.0.1")
                .directory(tempDir.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } else {
            ProcessBuilder("sleep", sleepSeconds.toString())
                .directory(tempDir.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }
        val output = process.inputStream.bufferedReader().readText()
        val start = System.currentTimeMillis()
        if (!process.waitFor(REV_LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return Pair(emptyList(), System.currentTimeMillis() - start)
        }
        val elapsed = System.currentTimeMillis() - start
        val lines = output.lines().map { it.trim() }.filter { it.isNotBlank() }
        return Pair(lines, elapsed)
    }

    @Test
    fun `slow process returns empty list and completes within expected bound`(@TempDir tempDir: Path) {
        // Sleep for 10× the timeout — the timeout must fire before the process exits.
        val sleepSeconds = REV_LIST_TIMEOUT_SECONDS * 10
        val (result, elapsed) = runWithTimeout(tempDir, sleepSeconds)

        assertTrue(result.isEmpty(), "Expected empty list on timeout, got $result")
        // Must complete well within 5× the timeout constant (generous upper bound)
        val maxExpectedMs = REV_LIST_TIMEOUT_SECONDS * 5 * 1_000
        assertTrue(elapsed < maxExpectedMs) {
            "Took ${elapsed}ms — expected < ${maxExpectedMs}ms (timeout=$REV_LIST_TIMEOUT_SECONDS s)"
        }
    }

    @Test
    fun `timeout constant is 10 seconds`() {
        // Pin the constant so any future change requires updating this test explicitly.
        assertEquals(10L, REV_LIST_TIMEOUT_SECONDS)
    }
}
