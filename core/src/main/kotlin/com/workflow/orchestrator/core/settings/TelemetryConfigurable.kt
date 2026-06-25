package com.workflow.orchestrator.core.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.*
import com.workflow.orchestrator.core.http.HttpCacheMetrics
import com.workflow.orchestrator.core.http.HttpResponseCache
import com.workflow.orchestrator.core.util.ProjectIdentifier
import javax.swing.JComponent

class TelemetryConfigurable(private val project: Project) : SearchableConfigurable {

    private val settings = PluginSettings.getInstance(project)
    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null
    private val cacheStatsLabel = JBLabel()

    override fun getId(): String = "workflow.orchestrator.telemetry"
    override fun getDisplayName(): String = "Telemetry & Logs"

    override fun createComponent(): JComponent {
        val innerPanel = panel {
            group("Logging") {
                row("Log Level:") {
                    comboBox(listOf("INFO", "DEBUG", "TRACE"))
                        .bindItem(
                            { settings.state.logLevel },
                            { settings.state.logLevel = it ?: "INFO" }
                        )
                }
                row {
                    checkBox("Enable diagnostic JSONL logging")
                        .bindSelected(
                            { settings.state.diagnosticJsonlEnabled },
                            { settings.state.diagnosticJsonlEnabled = it }
                        )
                }
                row {
                    // Written to ~/.workflow-orchestrator/diagnostics/plugin-{0,1,2}.log
                    // by PluginDiagnosticLogService — installed at startup by PluginDiagnosticLogActivity.
                    // Install is one-shot per IDE session, so the toggle only takes effect after restart.
                    checkBox("Write a separate shareable plugin diagnostic log (for support; applies after restart)")
                        .bindSelected(
                            { settings.state.pluginDiagnosticLogEnabled },
                            { settings.state.pluginDiagnosticLogEnabled = it }
                        )
                }
                row("Log retention (days):") {
                    intTextField(range = 1..365)
                        .bindIntText(settings.state::retentionDays)
                }
                row {
                    button("Open Log Folder") { openLogsFolder() }
                }
            }
            group("Privacy") {
                row {
                    checkBox("Include run_command output in logs")
                        .bindSelected(
                            { settings.state.includeCommandOutputInLogs },
                            { settings.state.includeCommandOutputInLogs = it }
                        )
                }
            }
            group("Display") {
                row {
                    checkBox("Show estimated cost in UI")
                        .bindSelected(
                            { settings.state.costDisplayEnabled },
                            { settings.state.costDisplayEnabled = it }
                        )
                }
            }
            group("HTTP Cache") {
                row {
                    comment(
                        "In-memory HTTP response cache (Phase 3 Prong A). " +
                            "Entries auto-expire per URL-pattern TTL, are evicted at a " +
                            "5 MB cap, and are invalidated when the plugin issues a " +
                            "successful POST/PUT/PATCH/DELETE on the same resource. " +
                            "Counters reset on IDE restart."
                    )
                }
                row {
                    cell(cacheStatsLabel)
                }
                row {
                    button("Refresh stats") { refreshCacheStats() }
                    button("Purge HTTP cache") { purgeCache() }
                }
            }
        }
        refreshCacheStats()
        dialogPanel = innerPanel
        return JBScrollPane(innerPanel).apply {
            border = null
        }
    }

    override fun isModified(): Boolean = dialogPanel?.isModified() ?: false

    override fun apply() {
        dialogPanel?.apply()
    }

    override fun reset() {
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }

    private fun openLogsFolder() {
        val basePath = project.basePath ?: return
        val logsDir = ProjectIdentifier.logsDir(basePath)
        logsDir.mkdirs()
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(logsDir)
            }
        } catch (_: Exception) {}
    }

    private fun refreshCacheStats() {
        val all = HttpCacheMetrics.getAllStats()
        cacheStatsLabel.text = if (all.isEmpty()) {
            "<html><i>No cache activity recorded yet — counters populate after the plugin makes HTTP calls.</i></html>"
        } else {
            buildStatsHtml(all)
        }
    }

    private fun buildStatsHtml(stats: Map<String, HttpCacheMetrics.CacheStats>): String {
        val sb = StringBuilder(512)
        sb.append("<html><body style='font-family:monospace'>")
        sb.append("<table cellspacing='0' cellpadding='4' border='0'>")
        sb.append("<tr style='background-color:rgba(128,128,128,0.15)'>")
        listOf("Service", "Fresh hits", "Stale-match", "Stale-differ", "Miss", "Hit %", "Entries", "Bytes", "Mut. inv.", "Evicted")
            .forEach { sb.append("<th align='left'>").append(it).append("</th>") }
        sb.append("</tr>")
        stats.entries.sortedBy { it.key }.forEach { (tag, s) ->
            sb.append("<tr>")
            sb.append("<td>").append(tag).append("</td>")
            sb.append("<td align='right'>").append(s.hitFresh).append("</td>")
            sb.append("<td align='right'>").append(s.hitStaleMatch).append("</td>")
            sb.append("<td align='right'>").append(s.hitStaleDiffer).append("</td>")
            sb.append("<td align='right'>").append(s.miss).append("</td>")
            sb.append("<td align='right'>").append("%.1f%%".format(s.hitRatePct)).append("</td>")
            sb.append("<td align='right'>").append(s.entriesInCache).append("</td>")
            sb.append("<td align='right'>").append(formatBytes(s.bytesInCache)).append("</td>")
            sb.append("<td align='right'>").append(s.invalidatedByMutation).append("</td>")
            sb.append("<td align='right'>").append(s.evicted).append("</td>")
            sb.append("</tr>")
        }
        sb.append("</table></body></html>")
        return sb.toString()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
    }

    private fun purgeCache() {
        val before = HttpCacheMetrics.getAllStats().values.sumOf { it.bytesInCache }
        val entriesBefore = HttpResponseCache.estimatedSize()
        HttpResponseCache.invalidateAll()
        refreshCacheStats()
        Messages.showInfoMessage(
            project,
            "Cleared $entriesBefore cached response(s), freeing ${formatBytes(before)}. " +
                "Hit/miss counters remain — only cached entries were discarded.",
            "HTTP Cache Purged"
        )
    }
}
