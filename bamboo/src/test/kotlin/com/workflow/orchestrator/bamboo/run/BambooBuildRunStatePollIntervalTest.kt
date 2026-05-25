package com.workflow.orchestrator.bamboo.run

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text pin for audit finding bamboo:F-8.
 *
 * The previous implementation used a hardcoded `delay(15_000)` that ignored
 * PluginSettings.buildPollIntervalSeconds and the SmartPoller convention.
 *
 * The fix reads `PluginSettings.getInstance(project).state.buildPollIntervalSeconds`
 * with a floor of MIN_POLL_INTERVAL_SECONDS (10s) instead of hard-coding 15_000.
 *
 * These tests assert that the source file:
 * 1. No longer contains the literal 15_000 delay constant.
 * 2. References PluginSettings for the interval.
 * 3. Applies the configured value with a minimum floor of 10 seconds.
 */
class BambooBuildRunStatePollIntervalTest {

    private val sourceFile = File(
        "src/main/kotlin/com/workflow/orchestrator/bamboo/run/BambooBuildRunState.kt"
    )

    @Test
    fun `source no longer contains hardcoded 15000 ms delay constant`() {
        val source = sourceFile.readText()
        assertTrue(
            !source.contains("delay(15_000)") && !source.contains("delay(15000)"),
            "BambooBuildRunState must not use a hardcoded 15-second delay"
        )
    }

    @Test
    fun `source reads buildPollIntervalSeconds from PluginSettings`() {
        val source = sourceFile.readText()
        assertTrue(
            source.contains("buildPollIntervalSeconds"),
            "BambooBuildRunState must read buildPollIntervalSeconds from PluginSettings"
        )
    }

    @Test
    fun `source applies a minimum floor for the poll interval`() {
        val source = sourceFile.readText()
        assertTrue(
            source.contains("maxOf") || source.contains("MIN_POLL_INTERVAL_SECONDS"),
            "BambooBuildRunState must apply a minimum floor to the configured poll interval"
        )
    }

    @Test
    fun `min poll interval constant is 10 seconds`() {
        val source = sourceFile.readText()
        assertTrue(
            source.contains("MIN_POLL_INTERVAL_SECONDS = 10"),
            "MIN_POLL_INTERVAL_SECONDS must be 10"
        )
    }
}
