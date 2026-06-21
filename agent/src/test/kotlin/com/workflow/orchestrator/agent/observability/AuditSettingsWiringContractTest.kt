package com.workflow.orchestrator.agent.observability

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Wave-1 audit wiring (D1/D2/D4). The Telemetry & Logs settings page exposes `retentionDays`,
 * `diagnosticJsonlEnabled`, and `includeCommandOutputInLogs`, but all three were dead — never read
 * by the logger. AgentService/AgentLoop are not unit-instantiable (their init loads tools/memory/
 * hooks), so these source-contract pins guarantee the construction + call sites actually READ the
 * settings.
 */
class AuditSettingsWiringContractTest {

    private fun src(rel: String): String {
        val cwd = File(System.getProperty("user.dir"))
        return listOf(File(cwd, rel), File(cwd, "agent/$rel"), File(cwd.parentFile ?: cwd, "agent/$rel"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate $rel from ${cwd.absolutePath}")
    }

    @Test
    fun `AgentService constructs AgentFileLogger from retentionDays and diagnosticJsonlEnabled (D1, D2)`() {
        val s = src("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt")
        val window = s.substringAfter("AgentFileLogger(").take(260)
        assertTrue(
            window.contains("retentionDays"),
            "AgentFileLogger must be constructed with retentionDays wired from settings (D1): $window",
        )
        assertTrue(
            window.contains("enabled"),
            "AgentFileLogger must be constructed with enabled wired from diagnosticJsonlEnabled (D2): $window",
        )
    }

    @Test
    fun `AgentLoop gates command-output logging on includeCommandOutputInLogs (D4)`() {
        val s = src("src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt")
        assertTrue(
            s.contains("includeCommandOutputInLogs"),
            "AgentLoop must read includeCommandOutputInLogs to gate run_command output in the audit log (D4)",
        )
    }
}
