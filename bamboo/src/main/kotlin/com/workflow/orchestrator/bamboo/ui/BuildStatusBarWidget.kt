package com.workflow.orchestrator.bamboo.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
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
        return !PluginSettings.getInstance(project).state.bambooUrl.isNullOrBlank()
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
        val eventBus = project.getService(EventBus::class.java)
        scope.launch {
            eventBus.events.collect { event ->
                if (event is WorkflowEvent.BuildFinished) {
                    val indicator = when (event.status) {
                        WorkflowEvent.BuildEventStatus.SUCCESS -> "✓"
                        WorkflowEvent.BuildEventStatus.FAILED -> "✗"
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
