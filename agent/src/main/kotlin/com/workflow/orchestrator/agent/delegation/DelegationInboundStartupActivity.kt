package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.startup.ProjectActivity
import com.workflow.orchestrator.core.settings.PluginSettings

/**
 * Starts [DelegationInboundService] when the project opens (if the inbound
 * setting is enabled) and notifies the service when the project closes so
 * any active inbound channels can be terminated with `project_closed`
 * before the IPC socket is reaped.
 *
 * Note: `kotlinx.coroutines.runBlocking` is banned in main/ sources by the
 * project's pre-commit hook. The platform's `projectClosing` callback is
 * non-suspend, so the channel-close coroutine bridges via `runBlockingCancellable`.
 */
class DelegationInboundStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val settings = project.getService(PluginSettings::class.java).state
        val inbound = project.getService(DelegationInboundService::class.java)
        if (settings.enableInboundCrossIdeDelegation) {
            inbound.start()
        }
        project.messageBus.connect().subscribe(
            com.intellij.openapi.project.ProjectManager.TOPIC,
            object : ProjectManagerListener {
                override fun projectClosing(closingProject: Project) {
                    if (closingProject !== project) return
                    runBlockingCancellable { inbound.closeAllForProjectClose() }
                }
            },
        )
    }
}
