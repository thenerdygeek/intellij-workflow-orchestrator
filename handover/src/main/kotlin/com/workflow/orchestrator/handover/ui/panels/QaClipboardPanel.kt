package com.workflow.orchestrator.handover.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

class QaClipboardPanel(private val project: Project) : JPanel(BorderLayout()) {

    val textArea = JBTextArea(8, 40).apply {
        isEditable = false
        font = JBUI.Fonts.create("Monospaced", 12)
    }
    val copyAllButton = JButton("Copy All")
    val addServiceButton = JButton("Add Service")
    val statusLabel = JBLabel("")
    private val tagListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    init {
        border = JBUI.Borders.empty(8)

        val header = JPanel(BorderLayout()).apply {
            add(JBLabel("QA Handover").apply {
                font = font.deriveFont(java.awt.Font.BOLD, 14f)
            }, BorderLayout.WEST)
            add(addServiceButton, BorderLayout.EAST)
        }

        val buttonPanel = JPanel().apply {
            add(copyAllButton)
        }

        val southPanel = JPanel(BorderLayout()).apply {
            add(buttonPanel, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
        }

        val centerPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(tagListPanel), BorderLayout.NORTH)
            add(JBScrollPane(textArea), BorderLayout.CENTER)
        }

        add(header, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)
        add(southPanel, BorderLayout.SOUTH)
    }

    fun setDockerTags(tags: Map<String, String>) {
        tagListPanel.removeAll()
        for ((service, tag) in tags) {
            val row = JPanel(BorderLayout()).apply {
                add(JBLabel("  $service: $tag"), BorderLayout.CENTER)
                val copyBtn = JButton("Copy")
                copyBtn.addActionListener {
                    val content = java.awt.datatransfer.StringSelection("$service:$tag")
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(content, null)
                }
                add(copyBtn, BorderLayout.EAST)
            }
            tagListPanel.add(row)
        }
        tagListPanel.revalidate()
        tagListPanel.repaint()
    }

    fun setFormattedText(text: String) {
        textArea.text = text
    }
}
