package com.workflow.orchestrator.core.diagnostics

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.workflow.orchestrator.core.settings.PluginSettings

/**
 * On project open, installs the shareable plugin diagnostic log handler when the setting is on.
 * [PluginDiagnosticLogService.ensureInstalled] is idempotent, so the second and later project
 * opens are no-ops (the handler is process-wide / app-level).
 */
class PluginDiagnosticLogActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val enabled = PluginSettings.getInstance(project).state.pluginDiagnosticLogEnabled
        ApplicationManager.getApplication()
            .getService(PluginDiagnosticLogService::class.java)
            .ensureInstalled(enabled)
    }
}
