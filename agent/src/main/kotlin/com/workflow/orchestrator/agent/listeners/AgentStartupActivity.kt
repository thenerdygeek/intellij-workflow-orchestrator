package com.workflow.orchestrator.agent.listeners

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.workflow.orchestrator.agent.session.MessageStateHandler
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
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

            // Sweep orphan top-level session dirs that weren't recorded in the index —
            // typically crashes between per-session writes and the index update, or
            // residue from older plugin versions. Bounded to dirs older than 30 days
            // so we never touch in-flight sessions.
            try {
                val orphans = MessageStateHandler.cleanupOrphanSessions(baseDir)
                if (orphans > 0) LOG.info("AgentStartupActivity: cleaned up $orphans orphan session director(ies)")
            } catch (e: Exception) {
                LOG.warn("AgentStartupActivity: orphan cleanup failed (non-fatal)", e)
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
                WorkflowNotificationService.getInstance(project).notifyInfo(
                    WorkflowNotificationService.GROUP_AGENT,
                    "Interrupted Agent Session",
                    "Agent session \"${title.take(60)}\" was interrupted. " +
                        "Open the Agent tab to resume it."
                )
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
