package com.workflow.orchestrator.core.toolwindow.insights

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.util.ProjectIdentifier
import java.awt.*
import java.io.File
import javax.swing.*

internal class ReliabilityPanel(private val project: Project) : JPanel(BorderLayout()) {

    init {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(16)
        }

        content.add(buildSectionHeader("HTTP Metrics"))
        content.add(Box.createVerticalStrut(6))
        content.add(JBLabel("HTTP client metrics will appear here after your first service request.").apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            alignmentX = Component.LEFT_ALIGNMENT
        })

        content.add(Box.createVerticalStrut(20))
        content.add(buildSectionHeader("Traces"))
        content.add(Box.createVerticalStrut(6))
        content.add(JBLabel("Raw LLM API traces (raw-api/) will appear here when tracing is enabled.").apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            alignmentX = Component.LEFT_ALIGNMENT
        })
        content.add(Box.createVerticalStrut(10))

        val openFolderBtn = JButton("Open Trace Folder").apply {
            isFocusable = false
            alignmentX = Component.LEFT_ALIGNMENT
            addActionListener { openTraceFolder() }
        }
        content.add(openFolderBtn)

        content.add(Box.createVerticalStrut(20))
        content.add(buildSectionHeader("Diagnostics"))
        content.add(Box.createVerticalStrut(6))
        content.add(JBLabel("Bundle logs and settings into a zip for bug reports.").apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            alignmentX = Component.LEFT_ALIGNMENT
        })
        content.add(Box.createVerticalStrut(10))

        val reportIssueBtn = JButton("Report Issue…").apply {
            isFocusable = false
            alignmentX = Component.LEFT_ALIGNMENT
            addActionListener { buildDiagnosticBundle() }
        }
        content.add(reportIssueBtn)

        add(content, BorderLayout.NORTH)
    }

    private fun buildDiagnosticBundle() {
        com.intellij.openapi.progress.runBackgroundableTask("Building diagnostic bundle", project, false) {
            try {
                val zipFile = com.workflow.orchestrator.core.diagnostics.DiagnosticBundleBuilder.build(project)
                com.intellij.openapi.application.invokeLater {
                    com.workflow.orchestrator.core.diagnostics.DiagnosticDialog(project, zipFile).show()
                }
            } catch (e: Exception) {
                com.intellij.openapi.diagnostic.Logger.getInstance(ReliabilityPanel::class.java)
                    .warn("DiagnosticBundleBuilder failed: ${e.message}", e)
            }
        }
    }

    private fun openTraceFolder() {
        val basePath = project.basePath ?: return
        val traceDir = File(ProjectIdentifier.agentDir(basePath), "logs/raw-api")
        traceDir.mkdirs()
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(traceDir)
            }
        } catch (_: Exception) {
        }
    }

    private fun buildSectionHeader(title: String): JBLabel = JBLabel(title.uppercase()).apply {
        foreground = StatusColors.SECONDARY_TEXT
        font = font.deriveFont(Font.BOLD, 10f)
        border = JBUI.Borders.emptyBottom(2)
        alignmentX = Component.LEFT_ALIGNMENT
    }
}
