package com.workflow.orchestrator.core.diagnostics

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.*

class DiagnosticDialog(project: Project, private val zipFile: File) : DialogWrapper(project, false) {

    init {
        title = "Diagnostic Bundle Created"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 12))
        panel.border = JBUI.Borders.empty(8)

        panel.add(JBLabel("<html>Diagnostic bundle created.<br>Attach <b>${zipFile.name}</b> to your bug report.</html>"), BorderLayout.NORTH)

        val pathLabel = JBLabel(zipFile.absolutePath).apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }
        panel.add(pathLabel, BorderLayout.CENTER)

        return panel
    }

    override fun createActions(): Array<Action> {
        val revealAction = object : DialogWrapperAction("Reveal in Finder") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                try {
                    if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(zipFile.parentFile)
                } catch (_: Exception) {}
            }
        }
        val copyAction = object : DialogWrapperAction("Copy Path") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                val sel = StringSelection(zipFile.absolutePath)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            }
        }
        return arrayOf(revealAction, copyAction, okAction)
    }
}
