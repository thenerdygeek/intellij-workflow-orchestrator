package com.workflow.orchestrator.web.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.workflow.orchestrator.core.util.HtmlEscape
import com.workflow.orchestrator.core.web.UrlScreener
import java.net.URI
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

    // S3 — Checkboxes for the "Add to allowlist" options. Wired into createCenterPanel
    // and read by the Add-to-allowlist action. Previously these fields existed but no
    // widget in createCenterPanel ever flipped them, so the entire subdomainGlob /
    // allowHttp code path was dead in production.
    private val globCheckbox = JBCheckBox(
        "Include all subdomains (*.${eTldPlus1(finalUrl)})",
        false,
    ).also {
        it.toolTipText = "If checked, this adds *.<domain> to the allowlist instead of just the bare host. " +
            "Use carefully — covers all current and future subdomains of this site."
    }
    private val httpCheckbox = JBCheckBox("Allow HTTP for this domain", false).also {
        it.toolTipText = "If checked, future requests to this domain may use plain HTTP. " +
            "Most production sites require HTTPS — leave unchecked unless you know why."
    }

    init {
        title = "Allow this web fetch?"
        init()
        // The ok/cancel actions are replaced by custom buttons in createActions()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        panel.add(JBLabel("URL: ${safeLabel(finalUrl)}"))
        if (originalUrl != null && originalUrl != finalUrl) {
            panel.add(JBLabel("Original: ${safeLabel(originalUrl)}"))
        }
        if (resolvedIp != null) panel.add(JBLabel("Resolves to: ${safeLabel(resolvedIp)}"))
        // Always show the Content-Length field; when null make the absence explicit.
        val contentLengthText = if (contentLength != null)
            "Content-Length: $contentLength bytes"
        else
            "Content-Length: unknown (no pre-probe)"
        panel.add(JBLabel(contentLengthText))
        if (screenerFlags.isNotEmpty()) {
            panel.add(JBLabel("Flags: ${safeLabel(screenerFlags.joinToString(", "))}"))
        }
        panel.add(JBLabel("Agent context: ${safeLabel(agentContext)}"))
        // S3 — Options for "Add to allowlist". Always enabled — they're only read when
        // the user clicks the Add-to-allowlist button, ignored for Allow-once / Deny.
        panel.add(JBLabel(" "))
        panel.add(JBLabel("If \"Add to allowlist\":"))
        panel.add(globCheckbox)
        panel.add(httpCheckbox)
        return panel
    }

    override fun createActions() = arrayOf(
        action("Allow once") { decision = Decision.ALLOW_ONCE; close(OK_EXIT_CODE) },
        action("Add to allowlist") {
            decision = Decision.ADD_TO_ALLOWLIST
            addSubdomainGlob = globCheckbox.isSelected
            addAllowHttp = httpCheckbox.isSelected
            close(OK_EXIT_CODE)
        },
        action("Deny") { decision = Decision.DENY; close(CANCEL_EXIT_CODE) },
    )

    private fun action(name: String, onClick: () -> Unit) =
        object : javax.swing.AbstractAction(name) {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = onClick()
        }

    companion object {
        /**
         * Extract a coarse eTLD+1 from a URL for display in the subdomain-glob checkbox tooltip.
         * This is a best-effort label — the real glob computation lives in
         * [com.workflow.orchestrator.web.service.WebFetchEngine.computeAllowlistDomain]. PSL nuance
         * (e.g. co.uk) is intentionally not required here because this is purely a tooltip hint.
         */
        internal fun eTldPlus1(url: String): String = try {
            val host = URI(url).host ?: return ""
            val labels = host.split(".")
            if (labels.size <= 2) host else labels.takeLast(2).joinToString(".")
        } catch (_: Exception) {
            ""
        }

        /**
         * Normalise + escape a value for display in a [JBLabel]. Collapses runs of whitespace
         * (so `\n` / `\r` in `originalUrl` or `agentContext` can't break dialog layout),
         * truncates to 200 chars, then HTML-escapes. The HTML-escape is defence-in-depth:
         * `BasicLabelUI.isHTMLString` is a position-0 check, so today's `"URL: $finalUrl"`
         * pattern wouldn't trigger HTML rendering even with a `<html>...` value, but a future
         * refactor that drops the prefix would silently re-expose the vector (BasicHTML
         * follows `<img src=...>` URLs from the EDT, info-leak).
         */
        internal fun safeLabel(value: String?, max: Int = 200): String {
            if (value.isNullOrEmpty()) return ""
            val collapsed = value.replace(Regex("\\s+"), " ").take(max)
            return HtmlEscape.escapeHtml(collapsed)
        }
    }
}
