package com.workflow.orchestrator.agent.listeners

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.session.SessionStatus
import com.workflow.orchestrator.agent.settings.AgentSettings

/**
 * Checks for interrupted agent sessions on IDE startup.
 * Gap 10: Shows a notification balloon if an active session exists from a previous IDE run.
 */
class AgentStartupActivity : ProjectActivity {

    companion object {
        private val LOG = Logger.getInstance(AgentStartupActivity::class.java)
    }

    override suspend fun execute(project: Project) {
        // Only check if agent is enabled
        val settings = try {
            AgentSettings.getInstance(project)
        } catch (_: Exception) {
            return
        }
        if (!settings.state.agentEnabled) return

        // Check for interrupted sessions
        try {
            val service = AgentService.getInstance(project)
            val activeSessions = service.sessionStore.list().filter {
                it.status == SessionStatus.ACTIVE && it.messageCount > 0
            }

            if (activeSessions.isNotEmpty()) {
                val session = activeSessions.first()
                val title = session.title.ifBlank { "Untitled session" }
                LOG.info("AgentStartupActivity: found interrupted session '${session.id}' — '${title}'")

                // Show notification balloon
                try {
                    val notificationGroup = NotificationGroupManager.getInstance()
                        .getNotificationGroup("Workflow Orchestrator")
                    notificationGroup.createNotification(
                        "Interrupted Agent Session",
                        "Agent session \"${title.take(60)}\" was interrupted. " +
                            "Open the Agent tab to resume it.",
                        NotificationType.INFORMATION
                    ).notify(project)
                } catch (e: Exception) {
                    // Notification group may not be registered — log and continue
                    LOG.info("AgentStartupActivity: interrupted session found but notification failed: ${e.message}")
                }
            } else {
                LOG.info("AgentStartupActivity: no interrupted sessions found")
            }
        } catch (e: Exception) {
            LOG.warn("AgentStartupActivity: failed to check for interrupted sessions", e)
        }
    }
}
