package com.workflow.orchestrator.handover.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel

class JiraCommentPanel(private val project: Project) : JPanel(BorderLayout()) {

    val commentPreview = JBTextArea(12, 40).apply {
        isEditable = false
        font = JBUI.Fonts.create(com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme.editorFontName, 12)
    }
    val editButton = JButton("Edit")
    val postButton = JButton("Post Comment")
    val statusLabel = JBLabel("")

    init {
        border = JBUI.Borders.empty(8)

        val headerLabel = JBLabel("JIRA CLOSURE COMMENT").apply {
            font = font.deriveFont(java.awt.Font.BOLD, JBUI.scale(12).toFloat())
            foreground = StatusColors.SECONDARY_TEXT
            border = JBUI.Borders.emptyLeft(8)
        }
        val header = JPanel(BorderLayout()).apply {
            add(headerLabel, BorderLayout.CENTER)
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(StatusColors.BORDER, 0, 0, 1, 0),
                BorderFactory.createCompoundBorder(
                    JBUI.Borders.customLine(StatusColors.LINK, 0, 2, 0, 0),
                    JBUI.Borders.empty(6, 0, 6, 0)
                )
            )
            isOpaque = false
        }

        editButton.addActionListener {
            commentPreview.isEditable = !commentPreview.isEditable
            editButton.text = if (commentPreview.isEditable) "Done" else "Edit"
        }
        postButton.isEnabled = false
        postButton.toolTipText = "Coming soon"

        val buttonPanel = JPanel().apply {
            add(editButton)
            add(postButton)
        }

        val southPanel = JPanel(BorderLayout()).apply {
            add(buttonPanel, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
        }

        add(header, BorderLayout.NORTH)
        add(JBScrollPane(commentPreview), BorderLayout.CENTER)
        add(southPanel, BorderLayout.SOUTH)
    }

    fun setCommentText(text: String) {
        commentPreview.text = text
    }
}
