package com.workflow.orchestrator.core.settings

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.workflow.orchestrator.core.config.ConfigPreset
import com.workflow.orchestrator.core.config.WorkflowConfig

class SettingsMigrationStartupActivity : ProjectActivity {
    private val log = Logger.getInstance(SettingsMigrationStartupActivity::class.java)
    override suspend fun execute(project: Project) {
        // Mutate the LIVE persisted singleton (not a copy) so BaseState setters bump modCount and
        // the changes are saved on the normal persistence cycle — same as the migration below.
        val state = PluginSettings.getInstance(project).state
        if (SettingsMigration.migrate(state)) {
            log.info("[SettingsMigration] stamped schema v${SettingsMigration.CURRENT_VERSION}")
        }
        if (ConfigPresetSeeder.seed(state, ConfigPreset.resolve())) {
            log.info("[PluginSplit] applied ConfigPreset company defaults (one-shot)")
        }
        // Plugin-split diagnostic: which WorkflowConfig EP impl is active — DefaultWorkflowConfig
        // for plugin A alone, or a depending plugin's lower-order override (e.g. CompanyBWorkflowConfig)
        // when installed. Used by the Phase-0a two-plugin runIde smoke.
        log.info("[PluginSplit] active WorkflowConfig impl: ${WorkflowConfig.resolve()::class.simpleName}")
    }
}
