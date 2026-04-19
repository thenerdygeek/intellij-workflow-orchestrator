package com.workflow.orchestrator.core.insights

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.services.SessionHistoryReader
import com.workflow.orchestrator.core.util.ProjectIdentifier
import kotlinx.coroutines.runBlocking

class GenerateReportAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val dialog = ReportRangeDialog()
        if (!dialog.showAndGet()) return

        val windowStartMs = dialog.windowStartMs
        val windowEndMs = dialog.windowEndMs
        val includeAI = dialog.includeAI

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Generating Insights Report…",
            true,
        ) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                runBlocking {
                    try {
                        indicator.text = "Collecting session data…"
                        indicator.isIndeterminate = true

                        val reader = ExtensionPointName
                            .create<SessionHistoryReader>("com.workflow.orchestrator.sessionHistoryReader")
                            .extensionList.firstOrNull() ?: return@runBlocking

                        val basePath = project.basePath ?: ""
                        val agentDir = ProjectIdentifier.agentDir(basePath)

                        val collector = ReportDataCollector(reader, agentDir)
                        val mechanical = collector.collect(windowStartMs, windowEndMs)

                        indicator.text = "Generating AI narrative…"
                        val narrative = InsightsNarrativeService().generate(
                            project = project,
                            mechanical = mechanical,
                            includeAI = includeAI,
                        ) { msg ->
                            indicator.text2 = msg
                        }

                        indicator.text = "Rendering HTML report…"
                        val html = HtmlReportRenderer.render(mechanical, narrative)

                        val reportsDir = ProjectIdentifier.rootDir(basePath).resolve("reports")
                        reportsDir.mkdirs()
                        val outputFile = reportsDir.resolve("insights-${System.currentTimeMillis()}.html")
                        outputFile.writeText(html)

                        BrowserUtil.browse(outputFile.toURI())

                        WorkflowNotificationService.getInstance(project).notifyInfo(
                            groupId = GROUP_INSIGHTS,
                            title = "Insights Report Ready",
                            content = "Report saved to ${outputFile.name} and opened in browser.",
                        )
                    } catch (ex: Exception) {
                        WorkflowNotificationService.getInstance(project).notifyError(
                            groupId = GROUP_INSIGHTS,
                            title = "Insights Report Failed",
                            content = ex.message ?: "Unknown error generating report.",
                        )
                    }
                }
            }
        })
    }

    companion object {
        const val GROUP_INSIGHTS = "workflow.insights"
    }
}
