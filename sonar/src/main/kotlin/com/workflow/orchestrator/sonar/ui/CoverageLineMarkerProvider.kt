package com.workflow.orchestrator.sonar.ui

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.JBColor
import com.workflow.orchestrator.sonar.model.LineCoverageStatus
import com.workflow.orchestrator.sonar.model.SonarState
import com.workflow.orchestrator.sonar.service.SonarDataService
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.ImageIcon

class CoverageLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Only process first element on each line (the PsiFile's direct children or first token)
        if (element.parent !is PsiFile && element != element.parent?.firstChild) return null

        val project = element.project
        val file = element.containingFile?.virtualFile ?: return null
        val basePath = project.basePath ?: return null

        val relativePath = file.path.removePrefix("$basePath/")
        val state = getSonarState(project)
        val fileCoverage = state.fileCoverage[relativePath] ?: return null

        val doc = element.containingFile?.viewProvider?.document ?: return null
        val lineNumber = doc.getLineNumber(element.textRange.startOffset) + 1
        val lineStatus = fileCoverage.lineStatuses[lineNumber] ?: return null

        val (icon, tooltip) = when (lineStatus) {
            LineCoverageStatus.COVERED -> coverageIcon(COVERED_COLOR) to "Line covered"
            LineCoverageStatus.UNCOVERED -> coverageIcon(UNCOVERED_COLOR) to "Line not covered"
            LineCoverageStatus.PARTIAL -> coverageIcon(PARTIAL_COLOR) to "Partially covered (some branches uncovered)"
        }

        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { tooltip },
            null,
            GutterIconRenderer.Alignment.LEFT,
            { tooltip }
        )
    }

    private fun getSonarState(project: Project): SonarState {
        return try {
            SonarDataService.getInstance(project).stateFlow.value
        } catch (_: Exception) {
            SonarState.EMPTY
        }
    }

    companion object {
        val COVERED_COLOR = JBColor(Color(46, 160, 67), Color(46, 160, 67))
        val UNCOVERED_COLOR = JBColor(Color(136, 136, 136), Color(136, 136, 136))
        val PARTIAL_COLOR = JBColor(Color(212, 160, 32), Color(212, 160, 32))

        fun coverageIcon(color: Color): Icon {
            val img = BufferedImage(6, 14, BufferedImage.TYPE_INT_ARGB)
            val g2 = img.createGraphics()
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillRoundRect(1, 0, 4, 14, 2, 2)
            g2.dispose()
            return ImageIcon(img)
        }
    }
}
