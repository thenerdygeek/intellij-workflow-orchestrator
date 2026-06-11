package com.workflow.orchestrator.agent.walkthrough.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.util.HtmlEscape
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The draggable callout. Pure view: every button delegates to the callbacks; all
 * state decisions live in WalkthroughService/StateMachine. EDT-only.
 *
 * Drag: JBPopup.setMovable(true) only works via a caption component — captionless
 * component popups have no grip — so the header bar hand-rolls drag (screen-coord
 * delta -> popup.setLocation). v1 deliberately does NOT track editor scrolling
 * (spec §4): position is computed at show-time; the user can drag it.
 */
internal class WalkthroughCalloutPopup(
    private val onNext: () -> Unit,
    private val onBack: () -> Unit,
    private val onClose: () -> Unit,
    private val onAsk: () -> Unit,
) {
    private val counterLabel = JBLabel("", SwingConstants.LEFT)
    private val titleLabel = JBLabel("").apply { font = JBUI.Fonts.label().asBold() }
    private val bodyPane = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder.simple()
        isEditable = false
        isOpaque = false
    }
    private val statusLabel = JBLabel("")
    private val backButton = JButton("← Back").apply { addActionListener { onBack() } }
    private val nextButton = JButton("Next →").apply { addActionListener { onNext() } }
    private val askButton = JButton("Ask…").apply { addActionListener { onAsk() } }
    private val root: JPanel = buildRoot()
    private var popup: JBPopup? = null

    private fun buildRoot(): JPanel {
        val header = JPanel(BorderLayout(GAP, 0)).apply {
            border = JBUI.Borders.empty(PAD_V, PAD_H)
            add(counterLabel, BorderLayout.WEST)
            add(
                JButton("✕").apply {
                    isBorderPainted = false
                    isContentAreaFilled = false
                    addActionListener { onClose() }
                },
                BorderLayout.EAST,
            )
        }
        installDrag(header)
        val footer = JPanel(BorderLayout(GAP, 0)).apply {
            border = JBUI.Borders.empty(PAD_V, PAD_H)
            add(backButton, BorderLayout.WEST)
            add(askButton, BorderLayout.CENTER)
            add(nextButton, BorderLayout.EAST)
        }
        val center = JPanel(BorderLayout(0, GAP_SMALL)).apply {
            border = JBUI.Borders.empty(0, PAD_H)
            add(titleLabel, BorderLayout.NORTH)
            add(JBScrollPane(bodyPane).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(POPUP_WIDTH), JBUI.scale(POPUP_HEIGHT))
            add(header, BorderLayout.NORTH)
            add(center, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }
    }

    private fun installDrag(grip: JPanel) {
        val listener = object : MouseAdapter() {
            private var dragStart: Point? = null
            private var popupStart: Point? = null
            override fun mousePressed(e: MouseEvent) {
                dragStart = e.locationOnScreen
                popupStart = popup?.locationOnScreen
            }
            override fun mouseDragged(e: MouseEvent) {
                val ds = dragStart ?: return
                val ps = popupStart ?: return
                popup?.setLocation(
                    Point(ps.x + e.locationOnScreen.x - ds.x, ps.y + e.locationOnScreen.y - ds.y)
                )
            }
        }
        grip.addMouseListener(listener)
        grip.addMouseMotionListener(listener)
    }

    /** Show (or move) the popup anchored under [anchorLine] of [editor]; flips above when cramped. */
    fun showAt(editor: Editor, anchorLine: Int) {
        ensurePopup()
        val doc = editor.document
        val line = (anchorLine - 1).coerceIn(0, doc.lineCount - 1)
        val xy = editor.offsetToXY(doc.getLineEndOffset(line))
        val visible = editor.scrollingModel.visibleArea
        val height = root.preferredSize.height
        val below = Point(
            xy.x.coerceAtMost(visible.x + visible.width - root.preferredSize.width),
            xy.y + editor.lineHeight,
        )
        val flipsAbove = below.y + height > visible.y + visible.height &&
            xy.y - height - editor.lineHeight > visible.y
        val point = if (flipsAbove) {
            Point(below.x, xy.y - height - editor.lineHeight) // flip above
        } else {
            below
        }
        val currentPopup = this.popup ?: return
        if (currentPopup.isVisible) {
            currentPopup.setLocation(RelativePoint(editor.contentComponent, point).screenPoint)
        } else {
            currentPopup.show(RelativePoint(editor.contentComponent, point))
        }
    }

    private fun ensurePopup() {
        if (popup != null && popup?.isDisposed != true) return
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(root, null)
            .setFocusable(true)
            .setRequestFocus(false)
            .setCancelOnClickOutside(false)
            .setCancelOnWindowDeactivation(false)
            .setCancelKeyEnabled(false)
            .setMovable(true)
            .createPopup()
    }

    fun renderStep(counter: String, title: String?, bodyHtml: String, nextIsDone: Boolean, backEnabled: Boolean) {
        counterLabel.text = counter
        titleLabel.text = title ?: ""
        titleLabel.isVisible = title != null
        bodyPane.text = "<html><body>$bodyHtml</body></html>"
        bodyPane.caretPosition = 0
        clearStatus()
        nextButton.text = if (nextIsDone) "Done ✓" else "Next →"
        nextButton.isEnabled = true
        backButton.isEnabled = backEnabled
    }

    fun renderMissingFile(counter: String, filePath: String, backEnabled: Boolean) {
        renderStep(
            counter,
            null,
            "<i>File no longer exists — step skipped:</i> <code>${HtmlEscape.escapeHtml(filePath)}</code>",
            nextIsDone = false,
            backEnabled = backEnabled,
        )
    }

    /** Fallback show when the step's editor can't open (e.g. step 1's file is missing). */
    fun showCenteredFallback(project: Project) {
        ensurePopup()
        val currentPopup = this.popup ?: return
        if (!currentPopup.isVisible) currentPopup.showCenteredInCurrentWindow(project)
    }

    fun renderLoading(counter: String) {
        counterLabel.text = counter
        statusLabel.text = "Writing next step…"
        statusLabel.icon = AnimatedIcon.Default.INSTANCE
        nextButton.isEnabled = false
    }

    fun renderPaused(counter: String) {
        counterLabel.text = counter
        statusLabel.text = "Agent is waiting for your input in chat ↗"
        statusLabel.icon = null
        nextButton.isEnabled = false
    }

    /** Ask was clicked: the question is routed to the main chat input below. */
    fun showDiscussingInChat() {
        statusLabel.text = "Discussing this step in the chat below ↗"
        statusLabel.icon = null
    }

    fun updateCounter(counter: String) { counterLabel.text = counter }

    private fun clearStatus() {
        statusLabel.text = ""
        statusLabel.icon = null
    }

    fun dispose() {
        popup?.cancel()
        popup = null
    }

    private companion object {
        const val POPUP_WIDTH = 380
        const val POPUP_HEIGHT = 220
        val GAP = JBUI.scale(8)
        val GAP_SMALL = JBUI.scale(4)
        const val PAD_V = 6
        const val PAD_H = 10
    }
}
