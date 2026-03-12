package com.workflow.orchestrator.sonar.ui

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.workflow.orchestrator.sonar.model.SonarState
import com.workflow.orchestrator.sonar.service.SonarDataService
import java.awt.Color

class CoverageTreeDecorator : ProjectViewNodeDecorator {

    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val project = node.project ?: return
        val file = node.virtualFile ?: return
        if (file.isDirectory) return

        val basePath = project.basePath ?: return
        val relativePath = file.path.removePrefix("$basePath/")

        // Skip test files and non-source files
        val normalizedPath = relativePath.replace('\\', '/')
        if (normalizedPath.contains("/test/") || normalizedPath.contains("/resources/")) return

        val state = getSonarState(project)
        val coverage = state.fileCoverage[relativePath] ?: return

        val pct = coverage.lineCoverage
        val color = when {
            pct >= 80.0 -> JBColor(Color(46, 160, 67), Color(46, 160, 67))
            pct >= 50.0 -> JBColor(Color(212, 160, 32), Color(212, 160, 32))
            else -> JBColor(Color(255, 68, 68), Color(255, 68, 68))
        }

        data.addText(
            " ${"%.0f".format(pct)}%",
            SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, color)
        )
    }

    private fun getSonarState(project: Project): SonarState {
        return try {
            project.getService(SonarDataService::class.java)
                ?.stateFlow?.value ?: SonarState.EMPTY
        } catch (_: Exception) {
            SonarState.EMPTY
        }
    }
}
