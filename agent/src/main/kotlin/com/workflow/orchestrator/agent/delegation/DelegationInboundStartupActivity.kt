package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.workflow.orchestrator.core.settings.PluginSettings

/**
 * Starts [DelegationInboundService] when the project opens (if the inbound
 * setting is enabled) and notifies the service when the project closes so
 * any active inbound channels can be terminated with `project_closed`
 * before the IPC socket is reaped.
 *
 * Threading note: the platform's `projectClosing` callback is non-suspend and
 * fires on EITHER thread — on the EDT when the user closes the project window
 * (`CloseProjectWindowHelper.windowClosing`), or on a BGT for programmatic
 * closes. `runBlockingCancellable` is FORBIDDEN on the EDT (it does not pump
 * the event queue and the platform asserts with IllegalStateException — live
 * crash reported 2026-06-10). The EDT path must bridge via
 * [runWithModalProgressBlocking], which pumps events while the channel-close
 * coroutine completes; the BGT path keeps the cancellable bridge.
 * (`kotlinx.coroutines.runBlocking` is banned in main/ sources by the
 * project's pre-commit hook, so neither path may use it.)
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
                    if (ApplicationManager.getApplication().isDispatchThread) {
                        // EDT (window-close path): blocks while pumping the event queue.
                        // The socket writes are local and sub-second, so the modal is
                        // effectively a flash; what matters is the terminal
                        // `project_closed` Result reaching IDE-A before the socket dies.
                        runWithModalProgressBlocking(
                            ModalTaskOwner.project(project),
                            "Closing delegated sessions",
                        ) {
                            inbound.closeAllForProjectClose()
                        }
                    } else {
                        // BGT close path (programmatic close): cancellable bridge is correct here.
                        runBlockingCancellable { inbound.closeAllForProjectClose() }
                    }
                }
            },
        )
    }
}
