package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.workflow.orchestrator.sonar.service.SonarDataService
import java.util.function.Function
import javax.swing.JComponent

class CoverageBannerProvider : EditorNotificationProvider {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<in FileEditor, out JComponent?> {
        return Function { _ ->
            val basePath = project.basePath ?: return@Function null
            val relativePath = file.path.removePrefix("$basePath/")

            val state = try {
                SonarDataService.getInstance(project).stateFlow.value
            } catch (_: Exception) { return@Function null }

            val fileCoverage = state.fileCoverage[relativePath] ?: return@Function null
            if (fileCoverage.uncoveredConditions <= 0) return@Function null

            EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
                text = "${fileCoverage.uncoveredConditions} uncovered branch(es) in this file — Branch coverage: ${"%.1f".format(fileCoverage.branchCoverage)}%"
                createActionLabel("View in Quality Tab") {
                    // TODO: Phase 1D+ — focus Quality tab on this file
                }
            }
        }
    }
}
