package com.workflow.orchestrator.core.settings

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class SettingsMigrationStartupActivity : ProjectActivity {
    private val log = Logger.getInstance(SettingsMigrationStartupActivity::class.java)
    override suspend fun execute(project: Project) {
        if (SettingsMigration.migrate(PluginSettings.getInstance(project).state)) {
            log.info("[SettingsMigration] stamped schema v${SettingsMigration.CURRENT_VERSION}")
        }
    }
}
