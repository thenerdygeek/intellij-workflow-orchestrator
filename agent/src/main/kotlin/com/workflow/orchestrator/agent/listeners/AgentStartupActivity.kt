package com.workflow.orchestrator.agent.listeners

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import java.io.File

/**
 * Checks for interrupted agent tasks on IDE startup.
 * If a checkpoint exists, notifies the user to resume or discard.
 */
class AgentStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        // Only check if agent is enabled
        val settings = try {
            AgentSettings.getInstance(project)
        } catch (_: Exception) {
            return
        }
        if (!settings.state.agentEnabled) return

        val basePath = project.basePath ?: return
        val checkpointDir = File(basePath, ".workflow/agent")
        if (!checkpointDir.exists()) return

        val checkpointFiles = checkpointDir.listFiles { f ->
            f.name.startsWith("checkpoint-") && f.extension == "json"
        }
        if (checkpointFiles.isNullOrEmpty()) return

        // Found interrupted task(s) - notify user
        val notification = WorkflowNotificationService.getInstance(project)
        notification.notifyInfo(
            "workflow.agent",
            "Agent Task Interrupted",
            "A previous agent task was interrupted. Would you like to resume?"
        )
    }
}
