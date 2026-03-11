package com.workflow.orchestrator.automation.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import com.workflow.orchestrator.automation.service.QueueService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.awt.event.MouseEvent

class AutomationStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = "WorkflowAutomationStatusBar"
    override fun getDisplayName(): String = "Workflow Automation Queue"
    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget {
        return AutomationStatusBarWidget(project)
    }
}

private class AutomationStatusBarWidget(
    project: Project
) : EditorBasedWidget(project), StatusBarWidget.TextPresentation {

    private var text = "\u2713 Suite Idle"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun ID(): String = "WorkflowAutomationStatusBar"

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String = text

    override fun getTooltipText(): String = "Automation Suite Queue Status"

    override fun getAlignment(): Float = 0f

    override fun getClickConsumer(): Consumer<MouseEvent>? = Consumer {
        val toolWindow = ToolWindowManager
            .getInstance(project).getToolWindow("Workflow")
        toolWindow?.show {
            val contentManager = toolWindow.contentManager
            val automationTab = contentManager.contents.find {
                it.displayName == AutomationTabProvider.TAB_TITLE
            }
            if (automationTab != null) {
                contentManager.setSelectedContent(automationTab)
            }
        }
    }

    override fun install(statusBar: StatusBar) {
        super.install(statusBar)
        scope.launch {
            try {
                val queueService = project.getService(QueueService::class.java) ?: return@launch
                queueService.stateFlow.collectLatest { entries ->
                    val hasRunning = entries.any { it.status == QueueEntryStatus.RUNNING }
                    val hasQueued = entries.any {
                        it.status in listOf(
                            QueueEntryStatus.WAITING_LOCAL,
                            QueueEntryStatus.QUEUED_ON_BAMBOO
                        )
                    }
                    val queueCount = entries.count {
                        it.status in listOf(
                            QueueEntryStatus.WAITING_LOCAL,
                            QueueEntryStatus.QUEUED_ON_BAMBOO
                        )
                    }

                    text = when {
                        hasRunning -> "\u25B6 Running"
                        hasQueued -> "Queue #$queueCount"
                        else -> "\u2713 Suite Idle"
                    }

                    withContext(Dispatchers.Main) {
                        myStatusBar?.updateWidget(ID())
                    }
                }
            } catch (_: CancellationException) {
                // Expected on dispose
            }
        }
    }

    override fun dispose() {
        scope.cancel()
        super.dispose()
    }
}
