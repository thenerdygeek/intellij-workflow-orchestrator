package com.workflow.orchestrator.jira.ui.widgets

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Generic debounced search component.
 *
 * Renders a [JBTextField] for query input and a [JBList] popup showing results.
 *
 * - Keyboard input is debounced by [debounceMs] milliseconds; rapid keystrokes
 *   collapse into a single search call.
 * - The [search] suspend function runs on [Dispatchers.IO]; each new keystroke
 *   cancels any previous in-flight search.
 * - Results are displayed via the configurable [display] function.
 * - In single-select mode the most-recent clicked item is remembered.
 * - In multi-select mode selected items accumulate and are shown in a separate
 *   label row above the text field.
 * - The internal [CoroutineScope] is tied to [disposable]: it is cancelled
 *   automatically when the parent is disposed.
 *
 * @param T             Item type returned by [search].
 * @param disposable    Parent disposable that controls the coroutine scope lifetime.
 * @param debounceMs    Debounce window in milliseconds (default 250).
 * @param search        Suspend function called with the current query; must be safe
 *                      to call on [Dispatchers.IO].
 * @param display       Maps an item to the string shown in the list.
 * @param multi         When true, allows accumulating multiple selections.
 * @param uiRunner      Runs a block on the UI thread.  Defaults to
 *                      [SwingUtilities.invokeLater]; override in tests to avoid
 *                      requiring a running IntelliJ Application instance.
 */
class SearchableChooser<T>(
    disposable: Disposable,
    internal val debounceMs: Long = 250L,
    private val search: suspend (String) -> List<T>,
    val display: (T) -> String,
    val multi: Boolean = false,
    private val uiRunner: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) }
) : JComponent() {

    // ── Coroutine scope ───────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        Disposer.register(disposable) { scope.coroutineContext[Job]?.cancel() }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    /** Most recently selected item (single-select mode). */
    var singleSelection: T? = null
        private set

    /** Accumulated selections (multi-select mode). */
    val multiSelection: MutableList<T> = mutableListOf()

    // ── UI components ─────────────────────────────────────────────────────────

    private val queryField = JBTextField()

    private val resultModel = DefaultListModel<String>()
    private val resultList = JBList(resultModel).apply {
        selectionMode = if (multi) ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        else ListSelectionModel.SINGLE_SELECTION
        border = JBUI.Borders.empty(2)
    }
    private val resultScroll = JBScrollPane(resultList).apply {
        border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1)
        preferredSize = java.awt.Dimension(JBUI.scale(240), JBUI.scale(120))
    }

    /** Parallel list of domain items corresponding to [resultModel] entries. */
    private val resultItems = mutableListOf<T>()

    // ── Layout ────────────────────────────────────────────────────────────────

    init {
        layout = BorderLayout(0, JBUI.scale(4))
        add(queryField, BorderLayout.NORTH)
        add(resultScroll, BorderLayout.CENTER)

        // Commit selection on click
        resultList.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            val idx = resultList.selectedIndex
            if (idx < 0 || idx >= resultItems.size) return@addListSelectionListener
            val item = resultItems[idx]
            if (multi) {
                if (!multiSelection.contains(item)) multiSelection.add(item)
            } else {
                singleSelection = item
            }
        }

        // Wire document listener to debounce logic
        queryField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = scheduleSearch(queryField.text)
            override fun removeUpdate(e: DocumentEvent) = scheduleSearch(queryField.text)
            override fun changedUpdate(e: DocumentEvent) = scheduleSearch(queryField.text)
        })
    }

    // ── Debounce logic ────────────────────────────────────────────────────────

    private var pendingSearch: Job? = null

    /**
     * Schedules a debounced search for [query].
     * Cancels any previously pending search job before queuing a new one.
     */
    private fun scheduleSearch(query: String) {
        pendingSearch?.cancel()
        pendingSearch = scope.launch {
            delay(debounceMs)
            val items = search(query)
            uiRunner { applyResults(items) }
        }
    }

    /**
     * Internal test entry-point.  Triggers the same debounce+search code path
     * as a DocumentListener event without touching the Swing text field.
     * Exposed as `internal` so tests in the same module can call it directly.
     */
    internal fun queryForTest(query: String) {
        scheduleSearch(query)
    }

    // ── Result rendering ──────────────────────────────────────────────────────

    private fun applyResults(items: List<T>) {
        resultModel.clear()
        resultItems.clear()
        for (item in items) {
            resultModel.addElement(display(item))
            resultItems.add(item)
        }
    }

    // ── Public accessors ──────────────────────────────────────────────────────

    /** Returns the query currently shown in the text field. */
    fun queryText(): String = queryField.text

    /** Clears results and resets selections. */
    fun reset() {
        queryField.text = ""
        resultModel.clear()
        resultItems.clear()
        singleSelection = null
        multiSelection.clear()
    }
}
