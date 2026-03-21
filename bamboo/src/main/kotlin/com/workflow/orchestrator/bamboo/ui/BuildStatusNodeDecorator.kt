package com.workflow.orchestrator.bamboo.ui

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.project.guessProjectDir
import com.intellij.ui.SimpleTextAttributes
import com.workflow.orchestrator.bamboo.model.BuildStatus
import com.workflow.orchestrator.bamboo.service.BuildMonitorService
import com.workflow.orchestrator.core.ui.StatusColors

/**
 * Decorates the project tree root with the latest Bamboo build status.
 * Shows a colored suffix like " ✓ #123" or " ✗ #123" on the project root node.
 */
class BuildStatusNodeDecorator : ProjectViewNodeDecorator {

    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val project = node.project ?: return
        val projectDir = project.guessProjectDir() ?: return
        val nodeFile = node.virtualFile ?: return

        // Only decorate the project root directory
        if (nodeFile != projectDir) return

        val state = try {
            BuildMonitorService.getInstance(project).stateFlow.value
        } catch (e: Exception) {
            null
        } ?: return

        val (symbol, color) = when (state.overallStatus) {
            BuildStatus.SUCCESS -> "✓" to StatusColors.SUCCESS
            BuildStatus.FAILED -> "✗" to StatusColors.ERROR
            BuildStatus.IN_PROGRESS -> "⟳" to StatusColors.LINK
            BuildStatus.PENDING -> "◷" to StatusColors.INFO
            BuildStatus.UNKNOWN -> return
        }

        data.addText(
            " $symbol #${state.buildNumber}",
            SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, color)
        )
    }
}
