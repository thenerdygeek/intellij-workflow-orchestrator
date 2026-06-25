package com.workflow.orchestrator.core.config

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text pin for the plugin-split startup diagnostic the runIde smoke greps. The smoke read the
 * IDE log for a `[PluginSplit] active WorkflowConfig impl: …` line to confirm which provider won
 * (DefaultWorkflowConfig for A alone, CompanyBWorkflowConfig when B is installed). This pins the two
 * load-bearing literals so a refactor that drops the log or stops calling the resolver fails here
 * instead of silently removing the only signal the smoke depended on.
 */
class PluginSplitStartupOracleContractTest {
    private val src =
        File("src/main/kotlin/com/workflow/orchestrator/core/settings/SettingsMigrationStartupActivity.kt")
            .readText()

    @Test
    fun `startup activity emits the PluginSplit oracle log`() {
        assertTrue(
            src.contains("[PluginSplit]"),
            "SettingsMigrationStartupActivity must log a [PluginSplit] line — it is the oracle the " +
                "two-plugin runIde smoke greps for the active WorkflowConfig impl.",
        )
    }

    @Test
    fun `startup activity resolves the active WorkflowConfig impl`() {
        assertTrue(
            src.contains("WorkflowConfig.resolve()"),
            "SettingsMigrationStartupActivity must call WorkflowConfig.resolve() — that call produces " +
                "the active-impl name printed in the [PluginSplit] oracle line.",
        )
    }
}
