package com.workflow.orchestrator.bamboo.ui

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.workflow.orchestrator.bamboo.model.BuildStatus
import com.workflow.orchestrator.bamboo.service.BuildMonitorService
import java.awt.Color

/**
 * Decorates the project tree root with the latest Bamboo build status.
 * Shows a colored suffix like " ✓ #123" or " ✗ #123" on the project root node.
 */
class BuildStatusNodeDecorator : ProjectViewNodeDecorator {

    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val project = node.project ?: return
        val projectDir = project.basePath ?: return
        val nodeFile = node.virtualFile ?: return

        // Only decorate the project root directory
        if (nodeFile.path != projectDir) return

        val state = try {
            BuildMonitorService.getInstance(project).stateFlow.value
        } catch (e: Exception) {
            null
        } ?: return

        val (symbol, color) = when (state.overallStatus) {
            BuildStatus.SUCCESS -> "✓" to JBColor(Color(0x59, 0xA6, 0x0F), Color(0x6C, 0xC6, 0x44))
            BuildStatus.FAILED -> "✗" to JBColor(Color(0xE0, 0x40, 0x40), Color(0xF0, 0x60, 0x60))
            BuildStatus.IN_PROGRESS -> "⟳" to JBColor(Color(0x40, 0x7E, 0xC9), Color(0x58, 0x9D, 0xF6))
            BuildStatus.PENDING -> "◷" to JBColor(Color(0x99, 0x99, 0x99), Color(0xAA, 0xAA, 0xAA))
            BuildStatus.UNKNOWN -> return
        }

        data.addText(
            " $symbol #${state.buildNumber}",
            SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, color)
        )
    }
}
