package com.workflow.orchestrator.core.settings

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.workflow.orchestrator.core.config.WorkflowConfig

class SettingsMigrationStartupActivity : ProjectActivity {
    private val log = Logger.getInstance(SettingsMigrationStartupActivity::class.java)
    override suspend fun execute(project: Project) {
        if (SettingsMigration.migrate(PluginSettings.getInstance(project).state)) {
            log.info("[SettingsMigration] stamped schema v${SettingsMigration.CURRENT_VERSION}")
        }
        // Plugin-split diagnostic: which WorkflowConfig EP impl is active — DefaultWorkflowConfig
        // for plugin A alone, or a depending plugin's lower-order override (e.g. CompanyBWorkflowConfig)
        // when installed. Used by the Phase-0a two-plugin runIde smoke.
        log.info("[PluginSplit] active WorkflowConfig impl: ${WorkflowConfig.resolve()::class.simpleName}")
    }
}
