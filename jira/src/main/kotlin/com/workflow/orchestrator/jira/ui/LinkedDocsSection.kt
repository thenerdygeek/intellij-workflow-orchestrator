package com.workflow.orchestrator.jira.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.jira.RemoteLinkData
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.ui.StatusColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Lazy-loaded list of remote links (Confluence pages, web links) attached to
 * an issue. The whole section hides itself when there are no links — empty
 * state is suppressed by design (R-ADD-4 spec).
 */
class LinkedDocsSection(private val project: Project) : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(LinkedDocsSection::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Notified after each load so [TicketDetailPanel] can show/hide the section header. */
    var onContent: ((hasContent: Boolean) -> Unit)? = null

    init {
        isOpaque = false
        isVisible = false
    }

    fun loadLinks(issueKey: String) {
        removeAll()
        isVisible = true
        val loadingPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(JBLabel(AnimatedIcon.Default()).apply { border = JBUI.Borders.empty(8) })
            add(JBLabel("Loading linked docs...").apply { foreground = StatusColors.SECONDARY_TEXT })
        }
        add(loadingPanel, BorderLayout.CENTER)
        revalidate()
        repaint()

        scope.launch {
            val service = project.getService(JiraService::class.java)
            val result = service.getRemoteLinks(issueKey)

            withContext(Dispatchers.EDT) {
                if (result.isError) {
                    log.warn("[Jira:UI] Failed to load remote links for $issueKey: ${result.summary}")
                    hideSection()
                } else if (result.data!!.isEmpty()) {
                    hideSection()
                } else {
                    renderLinks(result.data!!)
                }
            }
        }
    }

    private fun hideSection() {
        removeAll()
        isVisible = false
        onContent?.invoke(false)
        revalidate()
        repaint()
    }

    private fun renderLinks(links: List<RemoteLinkData>) {
        removeAll()
        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(2, 8)
        }
        links.forEach { container.add(buildRow(it)) }
        add(container, BorderLayout.CENTER)
        isVisible = true
        onContent?.invoke(true)
        revalidate()
        repaint()
    }

    private fun buildRow(link: RemoteLinkData): JPanel {
        val icon = if (link.applicationType == "com.atlassian.confluence") {
            AllIcons.FileTypes.Html
        } else {
            AllIcons.General.Web
        }
        val title = link.title?.takeIf { it.isNotBlank() } ?: link.url
        val app = link.applicationName?.takeIf { it.isNotBlank() }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(3, 0)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(22))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
            }
            left.add(JBLabel(icon))
            left.add(JBLabel(title).apply {
                font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
                foreground = StatusColors.LINK
                if (link.url.isNotBlank()) toolTipText = link.url
            })
            if (app != null) {
                left.add(JBLabel("· $app").apply {
                    font = font.deriveFont(JBUI.scale(10).toFloat())
                    foreground = StatusColors.SECONDARY_TEXT
                })
            }
            add(left, BorderLayout.WEST)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    if (link.url.isNotBlank()) BrowserUtil.browse(link.url)
                }
            })
        }
    }

    fun dispose() {
        scope.cancel()
    }

    companion object {
        /** Pure helper: should the section be hidden for the given link list? */
        fun shouldHide(links: List<RemoteLinkData>): Boolean = links.isEmpty()
    }
}
