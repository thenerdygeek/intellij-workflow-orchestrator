package com.workflow.orchestrator.handover.ui.panels

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

class JiraCommentPanel(private val project: Project) : JPanel(BorderLayout()) {

    val commentPreview = JBTextArea(12, 40).apply {
        isEditable = false
        font = JBUI.Fonts.create(EditorColorsManager.getInstance().globalScheme.editorFontName, 12)
    }
    val editButton = JButton("Edit")
    val postButton = JButton("Post Comment").apply {
        isEnabled = false
        toolTipText = "Coming soon"
    }
    val statusLabel = JBLabel("")

    init {
        border = JBUI.Borders.empty(8)

        editButton.addActionListener {
            commentPreview.isEditable = !commentPreview.isEditable
            editButton.text = if (commentPreview.isEditable) "Done" else "Edit"
        }

        val buttonPanel = JPanel().apply {
            add(editButton)
            add(postButton)
        }

        val southPanel = JPanel(BorderLayout()).apply {
            add(buttonPanel, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
        }

        add(handoverPanelHeader("JIRA CLOSURE COMMENT"), BorderLayout.NORTH)
        add(JBScrollPane(commentPreview), BorderLayout.CENTER)
        add(southPanel, BorderLayout.SOUTH)
    }

    fun setCommentText(text: String) {
        commentPreview.text = text
    }
}
