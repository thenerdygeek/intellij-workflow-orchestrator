package com.workflow.orchestrator.core.insights

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.services.SessionHistoryReader
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.util.ProjectIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

class WeeklyDigestStartupActivity : ProjectActivity {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override suspend fun execute(project: Project) {
        val settings = PluginSettings.getInstance(project)
        if (!settings.state.weeklyDigestEnabled) return

        val today = LocalDate.now()
        if (today.dayOfWeek != DayOfWeek.MONDAY) return

        if (reportExistsForThisWeek(project, today)) return

        scope.launch {
            runCatching {
                val reader = ExtensionPointName
                    .create<SessionHistoryReader>("com.workflow.orchestrator.sessionHistoryReader")
                    .extensionList.firstOrNull() ?: return@launch

                val basePath = project.basePath ?: ""
                val agentDir = ProjectIdentifier.agentDir(basePath)

                val windowEndMs = System.currentTimeMillis()
                val windowStartMs = windowEndMs - 7L * 24 * 60 * 60 * 1000

                val mechanical = ReportDataCollector(reader, agentDir).collect(windowStartMs, windowEndMs)
                val narrative = InsightsNarrativeService().generate(
                    project = project,
                    mechanical = mechanical,
                    includeAI = true,
                ) { /* silent — no progress UI */ }

                val html = HtmlReportRenderer.render(mechanical, narrative)

                val reportsDir = ProjectIdentifier.rootDir(basePath).resolve("reports")
                reportsDir.mkdirs()
                val outputFile = reportsDir.resolve("insights-weekly-${System.currentTimeMillis()}.html")
                outputFile.writeText(html)

                WorkflowNotificationService.getInstance(project).notifyInfo(
                    groupId = GenerateReportAction.GROUP_INSIGHTS,
                    title = "Weekly Insights Report Ready",
                    content = "Your Monday digest has been generated: ${outputFile.name}",
                )
            }
        }
    }

    private fun reportExistsForThisWeek(project: Project, today: LocalDate): Boolean {
        val basePath = project.basePath ?: return false
        val reportsDir = ProjectIdentifier.rootDir(basePath).resolve("reports")
        if (!reportsDir.exists()) return false

        val weekFields = WeekFields.of(Locale.getDefault())
        val currentWeek = today.get(weekFields.weekOfWeekBasedYear())
        val currentYear = today.get(weekFields.weekBasedYear())

        return reportsDir.listFiles()
            ?.filter { it.name.startsWith("insights-weekly-") && it.name.endsWith(".html") }
            ?.any { file ->
                runCatching {
                    // Extract timestamp from filename: insights-weekly-<epoch>.html
                    val epochMs = file.nameWithoutExtension.removePrefix("insights-weekly-").toLong()
                    val fileDate = java.time.Instant.ofEpochMilli(epochMs)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    fileDate.get(weekFields.weekOfWeekBasedYear()) == currentWeek &&
                            fileDate.get(weekFields.weekBasedYear()) == currentYear
                }.getOrElse { false }
            } ?: false
    }
}
