package com.workflow.orchestrator.handover.ui.panels

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.ClipboardUtil
import com.workflow.orchestrator.core.ui.StatusColors
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

class QaClipboardPanel(private val project: Project) : JPanel(BorderLayout()) {

    val textArea = JBTextArea(8, 40).apply {
        isEditable = false
        font = JBUI.Fonts.create(EditorColorsManager.getInstance().globalScheme.editorFontName, 12)
    }
    val copyAllButton = JButton("Copy All")
    val addServiceButton = JButton("Add Service").apply {
        isEnabled = false
        toolTipText = "Coming soon"
    }
    val statusLabel = JBLabel("")
    private val tagListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)
    private val emptyLabel = JBLabel("No services configured.").apply {
        foreground = StatusColors.SECONDARY_TEXT
        horizontalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.emptyTop(40)
    }

    init {
        border = JBUI.Borders.empty(8)

        copyAllButton.addActionListener {
            val text = textArea.text
            if (text.isNotBlank()) {
                copyToClipboard(text)
                statusLabel.text = "Copied!"
            }
        }

        val buttonPanel = JPanel().apply {
            add(copyAllButton)
        }

        val southPanel = JPanel(BorderLayout()).apply {
            add(buttonPanel, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
        }

        val contentPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(tagListPanel), BorderLayout.NORTH)
            add(JBScrollPane(textArea), BorderLayout.CENTER)
        }

        cardPanel.add(contentPanel, "content")
        cardPanel.add(emptyLabel, "empty")
        cardLayout.show(cardPanel, "empty")

        add(handoverPanelHeader("QA HANDOVER", addServiceButton), BorderLayout.NORTH)
        add(cardPanel, BorderLayout.CENTER)
        add(southPanel, BorderLayout.SOUTH)
    }

    fun setDockerTags(tags: Map<String, String>) {
        tagListPanel.removeAll()
        if (tags.isEmpty()) {
            cardLayout.show(cardPanel, "empty")
            tagListPanel.revalidate()
            tagListPanel.repaint()
            return
        }
        cardLayout.show(cardPanel, "content")
        tags.forEach { (service, tag) ->
            val row = JPanel(BorderLayout()).apply {
                add(JBLabel("  $service: $tag"), BorderLayout.CENTER)
                val copyBtn = JButton("Copy").apply {
                    addActionListener { copyToClipboard("$service:$tag") }
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

    private fun copyToClipboard(text: String) {
        ClipboardUtil.copyToClipboard(text)
    }
}
