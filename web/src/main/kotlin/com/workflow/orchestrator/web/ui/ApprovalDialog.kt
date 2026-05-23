package com.workflow.orchestrator.web.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.workflow.orchestrator.core.web.UrlScreener
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

class ApprovalDialog(
    project: Project,
    private val finalUrl: String,
    private val originalUrl: String?,
    private val screenerFlags: Set<UrlScreener.Flag>,
    private val resolvedIp: String?,
    private val contentLength: Long?,
    private val agentContext: String,
) : DialogWrapper(project, /* canBeParent = */ true) {

    enum class Decision { ALLOW_ONCE, ADD_TO_ALLOWLIST, DENY }

    var decision: Decision = Decision.DENY
        private set
    var addSubdomainGlob: Boolean = false
        private set
    var addAllowHttp: Boolean = false
        private set

    init {
        title = "Allow this web fetch?"
        init()
        // The ok/cancel actions are replaced by custom buttons in createActions()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        panel.add(JBLabel("URL: $finalUrl"))
        if (originalUrl != null && originalUrl != finalUrl) {
            panel.add(JBLabel("Original: $originalUrl"))
        }
        if (resolvedIp != null) panel.add(JBLabel("Resolves to: $resolvedIp"))
        if (contentLength != null) panel.add(JBLabel("Content-Length: $contentLength bytes"))
        if (screenerFlags.isNotEmpty()) {
            panel.add(JBLabel("Flags: ${screenerFlags.joinToString(", ")}"))
        }
        panel.add(JBLabel("Agent context: $agentContext"))
        return panel
    }

    override fun createActions() = arrayOf(
        action("Allow once") { decision = Decision.ALLOW_ONCE; close(OK_EXIT_CODE) },
        action("Add to allowlist") { decision = Decision.ADD_TO_ALLOWLIST; close(OK_EXIT_CODE) },
        action("Deny") { decision = Decision.DENY; close(CANCEL_EXIT_CODE) },
    )

    private fun action(name: String, onClick: () -> Unit) =
        object : javax.swing.AbstractAction(name) {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = onClick()
        }
}
