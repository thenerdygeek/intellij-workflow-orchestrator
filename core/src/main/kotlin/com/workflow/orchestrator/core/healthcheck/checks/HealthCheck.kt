package com.workflow.orchestrator.core.healthcheck.checks

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

interface HealthCheck {
    val id: String
    val displayName: String
    val order: Int

    fun isEnabled(settings: com.workflow.orchestrator.core.settings.PluginSettings.State): Boolean
    suspend fun execute(context: HealthCheckContext): CheckResult

    data class CheckResult(
        val passed: Boolean,
        val message: String,
        val details: List<String> = emptyList()
    )
}

data class HealthCheckContext(
    val project: Project,
    val changedFiles: List<VirtualFile>,
    val commitMessage: String,
    val branch: String
)
