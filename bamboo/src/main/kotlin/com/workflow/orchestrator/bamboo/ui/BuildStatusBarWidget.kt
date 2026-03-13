package com.workflow.orchestrator.bamboo.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
import com.workflow.orchestrator.bamboo.model.BuildStatus
import com.workflow.orchestrator.bamboo.service.BuildMonitorService
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.event.MouseEvent

class BuildStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = "WorkflowBuildStatusBar"
    override fun getDisplayName(): String = "Workflow Build Status"
    override fun isAvailable(project: Project): Boolean {
        return !PluginSettings.getInstance(project).connections.bambooUrl.isNullOrBlank()
    }

    override fun createWidget(project: Project): StatusBarWidget {
        return BuildStatusBarWidgetImpl(project)
    }
}

private class BuildStatusBarWidgetImpl(project: Project) :
    EditorBasedWidget(project), StatusBarWidget.TextPresentation {

    private var displayText: String = "Build: --"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun ID(): String = "WorkflowBuildStatusBar"

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String = displayText

    override fun getAlignment(): Float = 0f

    override fun getTooltipText(): String = "Build monitoring active"

    override fun getClickConsumer(): Consumer<MouseEvent>? = Consumer {
        val toolWindow = ToolWindowManager
            .getInstance(project).getToolWindow("Workflow")
        toolWindow?.show {
            val content = toolWindow.contentManager.findContent("Build")
            if (content != null) {
                toolWindow.contentManager.setSelectedContent(content)
            }
        }
    }

    override fun install(statusBar: StatusBar) {
        super.install(statusBar)

        // Collect real-time build state (in-progress, pending, etc.)
        val monitorService = BuildMonitorService.getInstance(project)
        scope.launch {
            monitorService.stateFlow.collect { state ->
                if (state != null) {
                    val indicator = when (state.overallStatus) {
                        BuildStatus.SUCCESS -> "\u2713"
                        BuildStatus.FAILED -> "\u2717"
                        BuildStatus.IN_PROGRESS -> "\u25B6"
                        BuildStatus.PENDING -> "\u25CB"
                        BuildStatus.UNKNOWN -> "?"
                    }
                    displayText = "${state.planKey}: $indicator #${state.buildNumber}"
                    statusBar.updateWidget(ID())
                }
            }
        }

        // Also listen for terminal build events (ensures widget updates even if stateFlow lags)
        val eventBus = project.getService(EventBus::class.java)
        scope.launch {
            eventBus.events.collect { event ->
                if (event is WorkflowEvent.BuildFinished) {
                    val indicator = when (event.status) {
                        WorkflowEvent.BuildEventStatus.SUCCESS -> "\u2713"
                        WorkflowEvent.BuildEventStatus.FAILED -> "\u2717"
                    }
                    displayText = "${event.planKey}: $indicator #${event.buildNumber}"
                    statusBar.updateWidget(ID())
                }
            }
        }
    }

    override fun dispose() {
        scope.cancel()
        super.dispose()
    }
}
