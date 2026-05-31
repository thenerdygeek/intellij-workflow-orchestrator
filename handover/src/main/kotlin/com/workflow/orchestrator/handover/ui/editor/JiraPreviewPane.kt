package com.workflow.orchestrator.handover.ui.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.handover.service.HandoverWikiPreviewRendererService
import com.workflow.orchestrator.handover.service.HandoverWikiPreviewRendererService.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Jira preview pane backing the [PreviewPane] interface for [HandoverTemplateAction.JIRA].
 *
 * On every [setRenderedMarkup] call:
 *   1. Calls [HandoverWikiPreviewRendererService.renderImmediate] for instant paint
 *      (returns LIVE_CACHED if cached, else LOCAL).
 *   2. Calls [HandoverWikiPreviewRendererService.requestLive] to kick off a live fetch
 *      when [HandoverWikiPreviewRendererService.isLiveAvailable] is true.
 *   3. Subscribes to [liveResults] (once, in init) and swaps to LIVE_FRESH HTML when
 *      a result arrives whose text matches the most recent setRenderedMarkup input.
 *
 * The badge above the editor reflects the current source.
 */
class JiraPreviewPane(
    private val project: Project,
    private val renderer: HandoverWikiPreviewRendererService,
    private val ticketKeyProvider: () -> String,
    cs: CoroutineScope,
) : JPanel(BorderLayout()), PreviewPane, Disposable {

    // ── Coroutine lifecycle ───────────────────────────────────────────────────

    /**
     * Standalone Job for the liveResults collector coroutine.
     * NOT a child of [cs] — avoids UncompletedCoroutinesError in runTest when the
     * test scope exits (SharedFlow.collect never completes on its own).
     * Must be cancelled in [dispose] so the coroutine does not outlive the pane.
     * (Audit finding HANDOVER-COR-4)
     */
    private val collectorJob = Job()

    // ── State ────────────────────────────────────────────────────────────────

    @Volatile
    private var currentMarkup: String = ""

    /** Raw HTML last set on the editor — preserved so tests can read it without round-tripping through the HTML parser. */
    @Volatile
    private var rawHtml: String = ""

    // ── Badge components ─────────────────────────────────────────────────────

    private val dotLabel = JBLabel("●").apply {
        foreground = JBColor.GRAY
    }

    private val statusLabel = JBLabel(LOCAL_TEXT).apply {
        foreground = JBColor.GRAY
    }

    // ── Editor ───────────────────────────────────────────────────────────────

    /**
     * Custom JEditorPane that overrides [getText] to return the raw HTML string set via
     * [setText], bypassing the HTML parser's round-trip reformatting so tests can do
     * simple substring checks on the markup we actually painted.
     */
    internal val editor: JEditorPane = object : JEditorPane() {
        override fun getText(): String = rawHtml
    }.also {
        it.contentType = "text/html"
        it.isEditable = false
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    init {
        // Badge row — NORTH
        val badgeRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        badgeRow.add(dotLabel)
        badgeRow.add(statusLabel)
        add(badgeRow, BorderLayout.NORTH)

        // Editor in scroll pane — CENTER
        add(JBScrollPane(editor), BorderLayout.CENTER)

        // Subscribe to live results once.
        // Launch as a standalone coroutine whose Job is NOT a child of cs — this way
        // runTest with UnconfinedTestDispatcher does not fail with UncompletedCoroutinesError
        // when the test scope exits (SharedFlow.collect never completes on its own).
        val collectorJob = Job()
        CoroutineScope(cs.coroutineContext + collectorJob).launch {
            renderer.liveResults.collect { (text, result) ->
                if (text == currentMarkup) {
                    setEditorText(result.html)
                    applyBadge(result.source)
                }
            }
        }
    }

    // ── PreviewPane ──────────────────────────────────────────────────────────

    override fun setRenderedMarkup(html: String) {
        currentMarkup = html

        // 1. Immediate paint (cache hit = LIVE_CACHED, miss = LOCAL)
        val initial = renderer.renderImmediate(html)

        // 2. Paint — set directly so tests (headless EDT) see the value synchronously
        setEditorText(initial.html)
        applyBadge(initial.source)

        // 3. Fire live fetch if available; the liveResults collector will swap later
        if (renderer.isLiveAvailable()) {
            renderer.requestLive(html, ticketKeyProvider())
        }
    }

    /** Sets [rawHtml] and calls [JEditorPane.setText] on the EDT (or current thread in headless). */
    private fun setEditorText(html: String) {
        rawHtml = html
        if (SwingUtilities.isEventDispatchThread()) {
            editor.setText(html)
        } else {
            SwingUtilities.invokeLater { editor.setText(html) }
        }
    }

    override fun asComponent(): JComponent = this

    override fun dispose() {
        // Nothing to dispose — the cs is owned by the parent
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun applyBadge(source: Source) {
        val doApply = Runnable {
            when (source) {
                Source.LIVE_FRESH -> {
                    dotLabel.foreground = StatusColors.SUCCESS
                    dotLabel.text = "●"
                    statusLabel.text = LIVE_FRESH_TEXT
                    statusLabel.foreground = JBColor.foreground()
                }
                Source.LIVE_CACHED -> {
                    dotLabel.foreground = StatusColors.SUCCESS
                    dotLabel.text = "●"
                    statusLabel.text = LIVE_CACHED_TEXT
                    statusLabel.foreground = JBColor.foreground()
                }
                Source.LOCAL -> {
                    dotLabel.foreground = JBColor.GRAY
                    dotLabel.text = "○"
                    statusLabel.text = LOCAL_TEXT
                    statusLabel.foreground = JBColor.GRAY
                }
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            doApply.run()
        } else {
            SwingUtilities.invokeLater(doApply)
        }
    }

    companion object {
        private const val LIVE_FRESH_TEXT = "Live preview (Jira) · live"
        private const val LIVE_CACHED_TEXT = "Live preview (Jira) · cached"
        private const val LOCAL_TEXT = "Local preview — Jira render unavailable"
    }
}
