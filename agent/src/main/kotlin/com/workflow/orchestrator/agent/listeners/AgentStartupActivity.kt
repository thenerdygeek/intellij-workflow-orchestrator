package com.workflow.orchestrator.agent.listeners

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.workflow.orchestrator.agent.service.GlobalSessionIndex
import com.workflow.orchestrator.agent.settings.AgentSettings

/**
 * Checks for interrupted agent sessions on IDE startup.
 *
 * Uses GlobalSessionIndex to detect sessions that were "active" when the IDE
 * shut down (or crashed). Marks them as "interrupted" and notifies the user
 * so they can resume from the History tab.
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

        // Check for interrupted sessions in global index
        try {
            val index = GlobalSessionIndex.getInstance()
            val interrupted = index.getSessions().filter {
                it.status == "active" || it.status == "interrupted"
            }

            if (interrupted.isNotEmpty()) {
                // Mark them as interrupted
                interrupted.forEach { entry ->
                    index.updateSession(entry.sessionId) { it.copy(status = "interrupted") }
                }

                // Notify user about the most recent one
                val latest = interrupted.first()
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("workflow.agent")
                    .createNotification(
                        "Agent Session Interrupted",
                        "\"${latest.title.take(80)}\" was interrupted. You can resume it from the History tab.",
                        NotificationType.INFORMATION
                    )
                    .notify(project)
            }

            // Also run periodic cleanup of stale sessions
            index.cleanup()
        } catch (_: Exception) { /* service not available */ }
    }
}
