package com.workflow.orchestrator.agent.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Shows a side-by-side diff of proposed file edits with Accept/Reject buttons.
 * Used when approvalRequiredForEdits is true and the agent wants to modify a file.
 */
class EditApprovalDialog(
    private val project: Project,
    private val filePath: String,
    private val originalContent: String,
    private val proposedContent: String,
    private val editDescription: String
) : DialogWrapper(project) {

    var approved = false
        private set

    init {
        title = "Agent Edit Approval"
        setOKButtonText("Accept Edit")
        setCancelButtonText("Reject")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(JBUI.scale(800), JBUI.scale(600))
        panel.border = JBUI.Borders.empty(8)

        // Header with file path and description
        val header = JBLabel("<html><b>File:</b> $filePath<br><b>Action:</b> $editDescription</html>")
        header.border = JBUI.Borders.emptyBottom(8)
        panel.add(header, BorderLayout.NORTH)

        // Diff panel using IntelliJ's DiffManager
        try {
            val contentFactory = DiffContentFactory.getInstance()
            val content1 = contentFactory.create(project, originalContent)
            val content2 = contentFactory.create(project, proposedContent)
            val request = SimpleDiffRequest(
                "Agent Edit: $filePath",
                content1, content2,
                "Original", "Proposed"
            )
            val diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
            diffPanel.setRequest(request)
            panel.add(diffPanel.component, BorderLayout.CENTER)
        } catch (e: Exception) {
            // Fallback: show text comparison if DiffManager fails
            val fallback = JBTextArea().apply {
                text = "=== Original ===\n${originalContent.take(2000)}\n\n=== Proposed ===\n${proposedContent.take(2000)}"
                isEditable = false
                font = JBUI.Fonts.create("Monospaced", 12)
                border = JBUI.Borders.empty(4)
            }
            panel.add(JBScrollPane(fallback), BorderLayout.CENTER)
        }

        return panel
    }

    override fun doOKAction() {
        approved = true
        super.doOKAction()
    }
}
