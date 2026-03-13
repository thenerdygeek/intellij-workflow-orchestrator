package com.workflow.orchestrator.sonar.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.FileContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.workflow.orchestrator.sonar.model.LineCoverageStatus
import com.workflow.orchestrator.sonar.service.SonarDataService
import java.awt.Color

/**
 * Shows SonarQube coverage data (green/red) in the diff gutter.
 * Covered lines in the right (new code) panel get a translucent green
 * background, uncovered lines get a translucent red background.
 *
 * Reads from [SonarDataService.stateFlow] cached data — no API calls.
 */
class CoverageDiffExtension : DiffExtension() {

    private val log = Logger.getInstance(CoverageDiffExtension::class.java)

    override fun onViewerCreated(
        viewer: FrameDiffTool.DiffViewer,
        context: DiffContext,
        request: DiffRequest
    ) {
        val project = context.project ?: return
        if (request !is ContentDiffRequest) return

        val sonarService = try {
            SonarDataService.getInstance(project)
        } catch (e: Exception) {
            return
        }

        val sonarState = sonarService.stateFlow.value
        if (sonarState.fileCoverage.isEmpty()) return

        // Get the right-side editor (new code)
        val editors = try {
            viewer.javaClass.getMethod("getEditors").invoke(viewer) as? List<*>
        } catch (e: Exception) {
            return
        }

        val rightEditor = (editors?.lastOrNull() as? Editor) ?: return
        val contents = request.contents
        if (contents.size < 2) return

        val rightContent = contents.last()
        val vFile = (rightContent as? FileContent)?.file ?: return

        // Match against Sonar coverage using the project-relative path
        val projectDir = project.basePath ?: return
        val relativePath = vFile.path.removePrefix(projectDir).removePrefix("/")

        // Try to find coverage data with several path variations
        val fileLines = sonarState.fileCoverage[vFile.path]?.lineStatuses
            ?: sonarState.fileCoverage[relativePath]?.lineStatuses
            ?: return

        val coveredColor = JBColor(
            Color(0x59, 0xA6, 0x0F, 0x30),
            Color(0x6C, 0xC6, 0x44, 0x30)
        )
        val uncoveredColor = JBColor(
            Color(0xE0, 0x40, 0x40, 0x30),
            Color(0xF0, 0x60, 0x60, 0x30)
        )

        val markupModel = rightEditor.markupModel
        val document = rightEditor.document

        for ((lineNumber, status) in fileLines) {
            val lineIndex = lineNumber - 1
            if (lineIndex < 0 || lineIndex >= document.lineCount) continue

            val startOffset = document.getLineStartOffset(lineIndex)
            val endOffset = document.getLineEndOffset(lineIndex)

            val color = when (status) {
                LineCoverageStatus.COVERED -> coveredColor
                LineCoverageStatus.PARTIAL -> coveredColor
                LineCoverageStatus.UNCOVERED -> uncoveredColor
            }

            val textAttributes = TextAttributes().apply { backgroundColor = color }

            markupModel.addRangeHighlighter(
                startOffset, endOffset,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                textAttributes,
                HighlighterTargetArea.LINES_IN_RANGE
            )
        }
    }
}
