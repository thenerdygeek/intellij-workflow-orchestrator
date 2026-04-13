package com.workflow.orchestrator.agent.listeners

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.workflow.orchestrator.agent.session.MessageStateHandler
import com.workflow.orchestrator.agent.session.SessionMigrator
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.core.util.ProjectIdentifier
import java.io.File

/**
 * Checks for interrupted agent sessions on IDE startup.
 * Gap 10: Shows a notification balloon if an active session exists from a previous IDE run.
 *
 * Reads sessions.json (global index maintained by MessageStateHandler) and looks for
 * sessions without a lock file that have recent timestamps — indicating an interrupted session.
 */
class AgentStartupActivity : ProjectActivity {

    companion object {
        private val LOG = Logger.getInstance(AgentStartupActivity::class.java)
        /** Sessions within this window are considered "recent" (10 minutes). */
        private const val RECENT_THRESHOLD_MS = 10 * 60 * 1000L
    }

    override suspend fun execute(project: Project) {
        // Only check if agent is enabled
        val settings = try {
            AgentSettings.getInstance(project)
        } catch (_: Exception) {
            return
        }
        if (!settings.state.agentEnabled) return

        // Check for interrupted sessions via sessions.json global index
        try {
            val basePath = project.basePath ?: return
            val baseDir = ProjectIdentifier.agentDir(basePath)

            // Migrate old JSONL sessions to new two-file format before loading index
            try {
                SessionMigrator.migrate(baseDir)
            } catch (e: Exception) {
                LOG.warn("AgentStartupActivity: session migration failed", e)
            }

            val history = MessageStateHandler.loadGlobalIndex(baseDir)

            // Find sessions without a lock file that have recent timestamps
            val interrupted = history.firstOrNull { item ->
                val sessionDir = File(baseDir, "sessions/${item.id}")
                val lockFile = File(sessionDir, ".lock")
                !lockFile.exists() && isRecent(item.ts)
            }

            if (interrupted != null) {
                val title = interrupted.task.ifBlank { "Untitled session" }
                LOG.info("AgentStartupActivity: found interrupted session '${interrupted.id}' — '${title}'")

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

    private fun isRecent(ts: Long): Boolean {
        return (System.currentTimeMillis() - ts) < RECENT_THRESHOLD_MS
    }
}
