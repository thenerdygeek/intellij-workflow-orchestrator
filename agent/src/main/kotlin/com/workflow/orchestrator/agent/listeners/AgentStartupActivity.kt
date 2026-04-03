package com.workflow.orchestrator.agent.listeners

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.workflow.orchestrator.agent.runtime.ConversationStore
import com.workflow.orchestrator.agent.runtime.SessionCheckpoint
import com.workflow.orchestrator.agent.service.GlobalSessionIndex
import com.workflow.orchestrator.agent.settings.AgentSettings
import java.io.File

/**
 * Checks for interrupted agent sessions on IDE startup.
 *
 * Uses GlobalSessionIndex to detect sessions that were "active" when the IDE
 * shut down (or crashed). Marks them as "interrupted" and notifies the user
 * with a direct "Resume" action that opens the agent tab and resumes the session.
 *
 * Also loads checkpoint data to show the user what the agent was doing when interrupted.
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

        // Check for interrupted sessions in global index
        try {
            val index = GlobalSessionIndex.getInstance()
            val projectPath = project.basePath ?: return
            val interrupted = index.getSessionsForProject(projectPath).filter {
                it.status == "active"
            }

            if (interrupted.isNotEmpty()) {
                // Mark them as interrupted
                interrupted.forEach { entry ->
                    index.updateSession(entry.sessionId) { it.copy(status = "interrupted") }
                }

                // Load checkpoint for the most recent session to show context
                val latest = interrupted.first()
                val sessionDir = File(ConversationStore.getSessionsDir(projectBasePath = projectPath), latest.sessionId)
                val checkpoint = SessionCheckpoint.load(sessionDir)

                val detail = buildString {
                    append("\"${latest.title.take(80)}\" was interrupted")
                    if (checkpoint != null) {
                        append(" at iteration ${checkpoint.iteration}")
                        if (checkpoint.editedFiles.isNotEmpty()) {
                            append(" (${checkpoint.editedFiles.size} files edited)")
                        }
                        checkpoint.lastActivity?.let { append(" — $it") }
                    }
                    append(".")
                }

                NotificationGroupManager.getInstance()
                    .getNotificationGroup("workflow.agent")
                    .createNotification(
                        "Agent Session Interrupted",
                        detail,
                        NotificationType.INFORMATION
                    )
                    .addAction(NotificationAction.createSimple("Resume Session") {
                        // Resume via AgentController — dispatch to the agent tab
                        try {
                            val agentService = com.workflow.orchestrator.agent.AgentService.getInstance(project)
                            agentService.resumeSession(latest.sessionId)
                        } catch (e: Exception) { LOG.debug("Failed to resume session", e) }
                    })
                    .notify(project)
            }

            // Also run periodic cleanup of stale sessions
            index.cleanup()
        } catch (e: Exception) { LOG.debug("Failed to check interrupted sessions", e) }

        // Check for interrupted Ralph loops
        try {
            val basePath = project.basePath ?: return
            val ralphDir = File(com.workflow.orchestrator.core.util.ProjectIdentifier.agentDir(basePath), "ralph")
            if (ralphDir.exists()) {
                val terminalPhases = setOf(
                    com.workflow.orchestrator.agent.ralph.RalphPhase.COMPLETED,
                    com.workflow.orchestrator.agent.ralph.RalphPhase.FORCE_COMPLETED,
                    com.workflow.orchestrator.agent.ralph.RalphPhase.CANCELLED
                )
                val activeLoops = ralphDir.listFiles()
                    ?.filter { it.isDirectory }
                    ?.mapNotNull { dir ->
                        com.workflow.orchestrator.agent.ralph.RalphLoopState.load(dir)
                            ?.takeIf { it.phase !in terminalPhases }
                    }
                    ?: emptyList()

                for (loop in activeLoops) {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("workflow.agent")
                        .createNotification(
                            "Ralph Loop Interrupted",
                            "Ralph Loop was interrupted at iteration ${loop.iteration}/${loop.maxIterations}. " +
                                "Cost so far: $${String.format("%.2f", loop.totalCostUsd)}",
                            NotificationType.INFORMATION
                        )
                        .addAction(NotificationAction.createSimple("Resume") {
                            try {
                                val agentService = com.workflow.orchestrator.agent.AgentService.getInstance(project)
                                agentService.ralphOrchestrator?.resumeInterrupted(loop)
                                // Trigger actual execution via the agent tab
                                agentService.resumeRalphLoop(loop.originalPrompt)
                            } catch (e: Exception) { LOG.debug("Failed to resume Ralph loop", e) }
                        })
                        .addAction(NotificationAction.createSimple("Cancel") {
                            val dir = File(ralphDir, loop.loopId)
                            com.workflow.orchestrator.agent.ralph.RalphLoopState.delete(dir)
                        })
                        .notify(project)
                }
            }
        } catch (e: Exception) { LOG.debug("Failed to check interrupted Ralph loops", e) }
    }
}
