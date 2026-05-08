package com.workflow.orchestrator.handover.ui.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.workflow.orchestrator.handover.model.HandoverTemplate
import com.workflow.orchestrator.handover.model.HandoverTemplateAction
import com.workflow.orchestrator.handover.service.HandoverPlaceholderResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import kotlin.coroutines.CoroutineContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

// ---------------------------------------------------------------------------
// PreviewPane marker interface
// ---------------------------------------------------------------------------

/**
 * Marker interface implemented by JiraPreviewPane (T19) and EmailPreviewPane (T20).
 * The card mounts [asComponent] in the layout and drives it via [setRenderedMarkup].
 */
interface PreviewPane {
    fun setRenderedMarkup(html: String)
    fun asComponent(): JComponent
}

// ---------------------------------------------------------------------------
// TemplateActions bag — injected by parent (T22 ShareTab)
// ---------------------------------------------------------------------------

/**
 * Thin callback bag wrapping the HandoverTemplateStore operations.
 * The card never depends directly on the store — the parent wires these lambdas.
 */
data class TemplateActions(
    val onUpdate: suspend (templateId: String, newSource: String) -> Unit,
    val onCreate: suspend (name: String, action: HandoverTemplateAction, source: String) -> Unit,
    val onDuplicate: suspend (sourceId: String, newName: String) -> Unit,
    val onDelete: suspend (templateId: String) -> Unit,
)

// ---------------------------------------------------------------------------
// TemplateEditorCard
// ---------------------------------------------------------------------------

/**
 * Reusable editor card for one action type (JIRA or EMAIL).
 *
 * Layout (top → bottom):
 *   - Title row: JBLabel(title) + optional format-pill label
 *   - Pane label ("WIKI MARKUP" or "HTML SOURCE")
 *   - Picker bar (TemplatePicker)
 *   - Source pane (JBTextArea in JBScrollPane)
 *   - "PREVIEW" label
 *   - Injected preview pane
 *   - Actions row: secondary button (left) + primary button (right, bold/accent)
 *
 * @param previewPane an injected panel implementing [PreviewPane]
 * @param onPrimaryAction invoked when the primary button is clicked with resolved markup
 * @param onSecondaryAction invoked when the secondary button is clicked; null hides the button
 * @param templateActions callbacks bridging to the HandoverTemplateStore
 * @param placeholderResolver resolves `{key}` tokens in the template source
 * @param scope externally-provided coroutine scope (injectable for tests)
 */
class TemplateEditorCard private constructor(
    private val project: Project,
    private val title: String,
    private val formatPill: String?,
    private val action: HandoverTemplateAction,
    private val previewPane: PreviewPane,
    private val primaryActionLabel: String,
    private val onPrimaryAction: suspend (resolvedMarkup: String, template: HandoverTemplate) -> Unit,
    private val secondaryActionLabel: String?,
    private val onSecondaryAction: (suspend (resolvedMarkup: String, template: HandoverTemplate) -> Unit)?,
    private val templateActions: TemplateActions,
    private val placeholderResolver: HandoverPlaceholderResolver,
    private val scope: CoroutineScope,
    private val edtDispatcher: CoroutineContext = Dispatchers.EDT,
) : JPanel(BorderLayout()), Disposable {

    // ── Production constructor ───────────────────────────────────────────────

    constructor(
        project: Project,
        title: String,
        formatPill: String?,
        action: HandoverTemplateAction,
        previewPane: PreviewPane,
        primaryActionLabel: String,
        onPrimaryAction: suspend (resolvedMarkup: String, template: HandoverTemplate) -> Unit,
        secondaryActionLabel: String? = null,
        onSecondaryAction: (suspend (resolvedMarkup: String, template: HandoverTemplate) -> Unit)? = null,
        templateActions: TemplateActions,
        placeholderResolver: HandoverPlaceholderResolver,
    ) : this(
        project = project,
        title = title,
        formatPill = formatPill,
        action = action,
        previewPane = previewPane,
        primaryActionLabel = primaryActionLabel,
        onPrimaryAction = onPrimaryAction,
        secondaryActionLabel = secondaryActionLabel,
        onSecondaryAction = onSecondaryAction,
        templateActions = templateActions,
        placeholderResolver = placeholderResolver,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        edtDispatcher = Dispatchers.EDT,
    )

    // ── State ────────────────────────────────────────────────────────────────

    private var currentTemplate: HandoverTemplate? = null
    private var pendingSelectId: String? = null  // id to re-select after next flow refresh
    private var previewJob: Job? = null
    private var saveJob: Job? = null
    /** Suppresses the document listener while loading a template into the source area. */
    private var loadingTemplate: Boolean = false

    // ── Components ───────────────────────────────────────────────────────────

    private val picker = TemplatePicker()

    internal val sourceArea = JBTextArea(10, 60).also {
        it.lineWrap = true
        it.wrapStyleWord = true
        it.font = com.intellij.util.ui.UIUtil.getLabelFont()
    }

    private val primaryBtn = JButton(primaryActionLabel).apply {
        font = font.deriveFont(Font.BOLD)
        isEnabled = false
    }

    private val secondaryBtn: JButton? = secondaryActionLabel?.let { label ->
        JButton(label).apply { isEnabled = false }
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    init {
        val content = JPanel(BorderLayout(0, 4))

        // Title row
        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        titleRow.add(JBLabel(title).apply { font = font.deriveFont(Font.BOLD) })
        if (formatPill != null) {
            titleRow.add(JBLabel(formatPill).apply {
                font = font.deriveFont(Font.PLAIN, font.size - 1f)
                foreground = JBColor.GRAY
            })
        }
        content.add(titleRow, BorderLayout.NORTH)

        // Center — source label, picker, source area, preview label, preview pane
        val centerPanel = JPanel(BorderLayout(0, 4))

        val sourceLabel = JBLabel(if (action == HandoverTemplateAction.JIRA) "WIKI MARKUP" else "HTML SOURCE").apply {
            font = font.deriveFont(Font.PLAIN, font.size - 1f)
            foreground = JBColor.GRAY
        }

        val pickerAndLabel = JPanel(BorderLayout(0, 2))
        pickerAndLabel.add(sourceLabel, BorderLayout.NORTH)
        pickerAndLabel.add(picker, BorderLayout.CENTER)

        val previewLabel = JBLabel("PREVIEW").apply {
            font = font.deriveFont(Font.PLAIN, font.size - 1f)
            foreground = JBColor.GRAY
        }

        val bodyPanel = JPanel(BorderLayout(0, 4))
        bodyPanel.add(pickerAndLabel, BorderLayout.NORTH)
        bodyPanel.add(JBScrollPane(sourceArea), BorderLayout.CENTER)

        val previewSection = JPanel(BorderLayout(0, 2))
        previewSection.add(previewLabel, BorderLayout.NORTH)
        previewSection.add(previewPane.asComponent(), BorderLayout.CENTER)

        centerPanel.add(bodyPanel, BorderLayout.NORTH)
        centerPanel.add(previewSection, BorderLayout.CENTER)

        content.add(centerPanel, BorderLayout.CENTER)

        // Actions row
        val actionsRow = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        if (secondaryBtn != null) actionsRow.add(secondaryBtn)
        actionsRow.add(primaryBtn)
        content.add(actionsRow, BorderLayout.SOUTH)

        add(content, BorderLayout.CENTER)

        // Wire picker callbacks
        picker.onSelectionChanged = { template ->
            onTemplateSelected(template)
        }
        picker.onCreateRequested = {
            handleCreate()
        }
        picker.onDuplicateRequested = { template ->
            handleDuplicate(template)
        }
        picker.onDeleteRequested = { template ->
            handleDelete(template)
        }

        // Source pane document listener — dirty + debounced preview + debounced save
        sourceArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onSourceChanged()
            override fun removeUpdate(e: DocumentEvent) = onSourceChanged()
            override fun changedUpdate(e: DocumentEvent) = onSourceChanged()
        })

        // Button actions
        primaryBtn.addActionListener {
            val tmpl = currentTemplate ?: return@addActionListener
            scope.launch {
                val resolved = resolveMarkup(sourceArea.text)
                onPrimaryAction(resolved, tmpl)
            }
        }
        secondaryBtn?.addActionListener {
            val tmpl = currentTemplate ?: return@addActionListener
            scope.launch {
                val resolved = resolveMarkup(sourceArea.text)
                onSecondaryAction?.invoke(resolved, tmpl)
            }
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Subscribe to the merged template list. The card filters by [action] and refreshes
     * the picker. Call once from the parent (T22 ShareTab).
     */
    fun bind(templates: StateFlow<List<HandoverTemplate>>) {
        scope.launch {
            templates.collect { all ->
                val filtered = all.filter { it.action == action }
                val prevId = currentTemplate?.id ?: pendingSelectId
                picker.setTemplates(filtered)

                // Determine which template to select after the refresh.
                val toSelect: HandoverTemplate? = when {
                    prevId != null -> filtered.find { it.id == prevId } ?: filtered.firstOrNull()
                    else -> filtered.firstOrNull()
                }
                pendingSelectId = null

                if (toSelect != null) {
                    // Sync the picker's visual selection (ignoring event suppression)
                    if (picker.currentSelection?.id != toSelect.id) {
                        picker.select(toSelect.id)
                    }
                    // Always call onTemplateSelected so source + preview are loaded
                    if (toSelect != currentTemplate) {
                        onTemplateSelected(toSelect)
                    }
                }
            }
        }
    }

    override fun dispose() {
        scope.cancel()
    }

    // ── TestOnly accessors ───────────────────────────────────────────────────

    @TestOnly
    fun testGetSourceText(): String = sourceArea.text

    @TestOnly
    fun testSetSourceText(value: String) {
        // Replace the entire document content to ensure the DocumentListener fires
        // consistently in test headless mode.
        val doc = sourceArea.document
        doc.remove(0, doc.length)
        if (value.isNotEmpty()) {
            doc.insertString(0, value, null)
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun onTemplateSelected(template: HandoverTemplate) {
        currentTemplate = template
        loadingTemplate = true
        try {
            sourceArea.text = template.source
        } finally {
            loadingTemplate = false
        }
        // picker.setDirty mutates Swing — ensure EDT even when called from the IO collector.
        if (SwingUtilities.isEventDispatchThread()) {
            picker.setDirty(false)
        } else {
            SwingUtilities.invokeLater { picker.setDirty(false) }
        }
        updateButtonStates()
        triggerPreview(template.source)
    }

    /** Resolves and renders [source] immediately (no debounce) — used on selection change. */
    private fun triggerPreview(source: String) {
        previewJob?.cancel()
        previewJob = scope.launch {
            val resolved = resolveMarkup(source)
            withContext(edtDispatcher) { previewPane.setRenderedMarkup(resolved) }
        }
    }

    private fun onSourceChanged() {
        if (loadingTemplate) return
        picker.setDirty(true)

        val text = sourceArea.text

        // Debounced preview
        previewJob?.cancel()
        previewJob = scope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            val resolved = resolveMarkup(text)
            withContext(edtDispatcher) { previewPane.setRenderedMarkup(resolved) }
        }

        // Debounced auto-save — capture text at call time; if the user types more, this job
        // is cancelled and a newer onSourceChanged call creates a fresh job with the latest text.
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            val tmpl = currentTemplate ?: return@launch
            templateActions.onUpdate(tmpl.id, text)
            // Clear dirty only if the source pane still matches what we saved
            if (sourceArea.text == text) {
                withContext(edtDispatcher) { picker.setDirty(false) }
            }
        }
    }

    private fun updateButtonStates() {
        val hasSel = currentTemplate != null
        primaryBtn.isEnabled = hasSel
        secondaryBtn?.isEnabled = hasSel
    }

    private fun handleCreate() {
        scope.launch(Dispatchers.IO) {
            // Show dialog on EDT is handled by Messages — but since we're in headless test mode
            // we guard with a try/catch so tests that don't exercise this path don't fail.
            val name = try {
                com.intellij.openapi.application.ApplicationManager.getApplication().let { app ->
                    var result: String? = null
                    app.invokeAndWait {
                        result = com.intellij.openapi.ui.Messages.showInputDialog(
                            project,
                            "Template name:",
                            "New Template",
                            null,
                        )
                    }
                    result
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { null }
            if (!name.isNullOrBlank()) {
                pendingSelectId = "${action.name.lowercase()}/$name"
                templateActions.onCreate(name, action, "")
            }
        }
    }

    private fun handleDuplicate(template: HandoverTemplate) {
        scope.launch(Dispatchers.IO) {
            val name = try {
                com.intellij.openapi.application.ApplicationManager.getApplication().let { app ->
                    var result: String? = null
                    app.invokeAndWait {
                        result = com.intellij.openapi.ui.Messages.showInputDialog(
                            project,
                            "New name for the duplicate:",
                            "Duplicate Template",
                            null,
                        )
                    }
                    result
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { null }
            if (!name.isNullOrBlank()) {
                pendingSelectId = "${action.name.lowercase()}/$name"
                templateActions.onDuplicate(template.id, name)
            }
        }
    }

    private fun handleDelete(template: HandoverTemplate) {
        scope.launch(Dispatchers.IO) {
            val confirmed = try {
                com.intellij.openapi.application.ApplicationManager.getApplication().let { app ->
                    var result = false
                    app.invokeAndWait {
                        result = com.intellij.openapi.ui.Messages.showOkCancelDialog(
                            project,
                            "Delete template \"${template.name}\"?",
                            "Delete Template",
                            "Delete",
                            "Cancel",
                            com.intellij.icons.AllIcons.General.QuestionDialog,
                        ) == com.intellij.openapi.ui.Messages.OK
                    }
                    result
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { false }
            if (confirmed) {
                templateActions.onDelete(template.id)
            }
        }
    }

    private suspend fun resolveMarkup(source: String): String {
        val placeholderRe = Regex("""\{([a-zA-Z][a-zA-Z._]*)\}""")
        val matches = placeholderRe.findAll(source).toList()
        if (matches.isEmpty()) return source

        var result = source
        for (match in matches) {
            val key = match.groupValues[1]
            val value = placeholderResolver.resolve(key, action)
            val substitution = when {
                value.isAvailable -> value.value
                action == HandoverTemplateAction.JIRA -> value.renderForJira()
                else -> value.renderForEmail()
            }
            result = result.replace(match.value, substitution)
        }
        return result
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    companion object {
        /** Debounce delay in milliseconds before re-rendering the preview after a keystroke. */
        const val PREVIEW_DEBOUNCE_MS = 100L

        /** Debounce delay in milliseconds before auto-saving edited template source. */
        const val AUTOSAVE_DEBOUNCE_MS = 300L

        /**
         * Test-only factory that accepts an external coroutine scope for deterministic timing
         * and an injectable [edtDispatcher] so headless tests avoid a real EDT dependency.
         * Pass [kotlinx.coroutines.Dispatchers.Unconfined] for [edtDispatcher] in tests.
         */
        @TestOnly
        fun forTest(
            project: Project,
            title: String,
            formatPill: String?,
            action: HandoverTemplateAction,
            previewPane: PreviewPane,
            primaryActionLabel: String,
            onPrimaryAction: suspend (resolvedMarkup: String, template: HandoverTemplate) -> Unit,
            secondaryActionLabel: String? = null,
            onSecondaryAction: (suspend (resolvedMarkup: String, template: HandoverTemplate) -> Unit)? = null,
            templateActions: TemplateActions,
            placeholderResolver: HandoverPlaceholderResolver,
            scope: CoroutineScope = CoroutineScope(SupervisorJob()),
            edtDispatcher: CoroutineContext = Dispatchers.Unconfined,
        ): TemplateEditorCard = TemplateEditorCard(
            project = project,
            title = title,
            formatPill = formatPill,
            action = action,
            previewPane = previewPane,
            primaryActionLabel = primaryActionLabel,
            onPrimaryAction = onPrimaryAction,
            secondaryActionLabel = secondaryActionLabel,
            onSecondaryAction = onSecondaryAction,
            templateActions = templateActions,
            placeholderResolver = placeholderResolver,
            scope = scope,
            edtDispatcher = edtDispatcher,
        )
    }
}
