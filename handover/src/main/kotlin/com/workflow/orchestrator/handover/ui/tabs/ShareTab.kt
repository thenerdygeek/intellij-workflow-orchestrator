package com.workflow.orchestrator.handover.ui.tabs

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.ui.ClipboardUtil
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import com.workflow.orchestrator.handover.model.HandoverState
import com.workflow.orchestrator.handover.model.HandoverTemplate
import com.workflow.orchestrator.handover.model.HandoverTemplateAction
import com.workflow.orchestrator.handover.service.HandoverPlaceholderResolver
import com.workflow.orchestrator.handover.service.HandoverStateService
import com.workflow.orchestrator.handover.service.HandoverTemplateStore
import com.workflow.orchestrator.handover.service.HandoverWikiPreviewRendererService
import com.workflow.orchestrator.handover.ui.chips.QuickValueChipsPanel
import com.workflow.orchestrator.handover.ui.editor.EmailPreviewPane
import com.workflow.orchestrator.handover.ui.editor.JiraPreviewPane
import com.workflow.orchestrator.handover.ui.editor.PreviewPane
import com.workflow.orchestrator.handover.ui.editor.TemplateActions
import com.workflow.orchestrator.handover.ui.editor.TemplateEditorCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.time.Instant
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

// ---------------------------------------------------------------------------
// Clipboard abstraction — injectable for tests
// ---------------------------------------------------------------------------

/**
 * Thin wrapper around the system clipboard placement so tests can capture the
 * [Transferable] without touching the real OS clipboard.
 */
data class ClipboardSink(
    val place: (Transferable) -> Unit,
) {
    companion object {
        /** Default production sink — writes to the system clipboard. */
        val system: ClipboardSink = ClipboardSink { transferable ->
            Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
        }
    }
}

// ---------------------------------------------------------------------------
// ShareTab
// ---------------------------------------------------------------------------

/**
 * Share tab — composes the Jira template editor, the Email template editor,
 * and the quick-value chips panel.
 *
 * Layout (top → bottom inside a JBScrollPane):
 *   Jira TemplateEditorCard
 *   strut 12px
 *   Email TemplateEditorCard
 *   strut 12px
 *   QuickValueChipsPanel
 *
 * Use [ShareTab.create] for production; [ShareTab.forTest] for unit tests.
 */
class ShareTab private constructor(
    private val project: Project?,
    private val handoverStateFlow: StateFlow<HandoverState>,
    private val workflowContextFlow: StateFlow<WorkflowContext>,
    private val eventBus: EventBus,
    private val jiraService: JiraService?,
    private val clipboardSink: ClipboardSink,
    private val jiraCard: TemplateEditorCard,
    private val emailCard: TemplateEditorCard,
    private val chipsPanel: QuickValueChipsPanel?,
    private val templateStore: StateFlow<List<HandoverTemplate>>,
    private val scope: CoroutineScope,
) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(ShareTab::class.java)

    init {
        buildLayout()
        // Binding the template store launches an infinite collect coroutine on the
        // card's scope. In test mode (project == null) this is skipped to avoid
        // UncompletedCoroutinesError; tests trigger actions via testTriggerXxx directly.
        if (project != null) {
            jiraCard.bind(templateStore)
            emailCard.bind(templateStore)
        }
        Disposer.register(this, jiraCard)
        Disposer.register(this, emailCard)
    }

    private fun buildLayout() {
        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8)
        }
        column.add(jiraCard)
        column.add(Box.createVerticalStrut(JBUI.scale(12)))
        column.add(emailCard)
        if (chipsPanel != null) {
            column.add(Box.createVerticalStrut(JBUI.scale(12)))
            column.add(chipsPanel)
        }
        column.add(Box.createVerticalGlue())

        add(JBScrollPane(column).apply {
            border = JBUI.Borders.empty()
            viewport.isOpaque = false
            isOpaque = false
        }, BorderLayout.CENTER)
    }

    override fun dispose() {
        scope.cancel()
    }

    // ── Action handlers ──────────────────────────────────────────────────────

    internal suspend fun handlePostComment(resolvedMarkup: String, @Suppress("UNUSED_PARAMETER") template: HandoverTemplate) {
        emitOverrideIfNeeded(WorkflowEvent.HandoverAction.POST_JIRA)
        val ticketId = resolveTicketId()
        if (ticketId.isBlank()) {
            log.warn("[ShareTab] Post Comment: no active ticket")
            notifyWarning("No active ticket", "Start Work on a Jira ticket before posting a comment.")
            return
        }
        val result = jiraService?.addComment(ticketId, resolvedMarkup)
        if (result == null || result.isError) {
            val msg = result?.summary ?: "JiraService unavailable"
            log.warn("[ShareTab] Post Comment failed: $msg")
            notifyWarning("Post Comment failed", msg)
        } else {
            log.info("[ShareTab] Comment posted to $ticketId")
        }
    }

    internal suspend fun handleCopyWiki(resolvedMarkup: String) {
        emitOverrideIfNeeded(WorkflowEvent.HandoverAction.COPY_EMAIL)
        try {
            ClipboardUtil.copyToClipboard(resolvedMarkup)
        } catch (e: Exception) {
            log.warn("[ShareTab] Copy wiki failed", e)
        }
    }

    internal suspend fun handleCopyFormatted(resolvedMarkup: String, @Suppress("UNUSED_PARAMETER") template: HandoverTemplate) {
        emitOverrideIfNeeded(WorkflowEvent.HandoverAction.COPY_EMAIL)
        try {
            clipboardSink.place(OutlookHtmlTransferable(resolvedMarkup))
        } catch (e: Exception) {
            log.warn("[ShareTab] Copy formatted failed", e)
        }
    }

    // ── Override emission ────────────────────────────────────────────────────

    private suspend fun emitOverrideIfNeeded(action: WorkflowEvent.HandoverAction) {
        val state = handoverStateFlow.value
        val ctx = workflowContextFlow.value
        val failed = currentFailedChecks(state)
        if (failed.isNotEmpty()) {
            // Identity always comes from WorkflowContext (canonical source of truth).
            // HandoverState.ticketId is a stale mirror — do not fall back to it.
            val ticketId = ctx.activeTicket?.key?.takeIf { it.isNotBlank() }.orEmpty()
            eventBus.emit(WorkflowEvent.HandoverOverride(ticketId, action, failed, Instant.now()))
        }
    }

    private fun currentFailedChecks(state: HandoverState): List<String> {
        val out = mutableListOf<String>()
        if (state.qualityGatePassed == false) out += "quality.gate"
        state.suiteResults.forEach { s ->
            when {
                s.passed == false -> out += "suite.${s.suitePlanKey.lowercase()}"
                s.passed == null -> out += "suite.${s.suitePlanKey.lowercase()}.running"
            }
        }
        return out
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun resolveTicketId(): String {
        // Identity always comes from WorkflowContext (canonical source of truth).
        // HandoverState.ticketId is a stale mirror — do not fall back to it.
        return workflowContextFlow.value.activeTicket?.key?.takeIf { it.isNotBlank() }.orEmpty()
    }

    private fun notifyWarning(title: String, content: String) {
        project?.let { p ->
            WorkflowNotificationService.getInstance(p).notifyWarning(
                "com.workflow.orchestrator.handover",
                title,
                content,
            )
        }
    }

    // ── Test-only hooks ──────────────────────────────────────────────────────

    @TestOnly
    fun testTriggerPostComment(template: HandoverTemplate) {
        scope.launch { handlePostComment(template.source, template) }
    }

    @TestOnly
    fun testTriggerCopyFormatted(template: HandoverTemplate) {
        scope.launch { handleCopyFormatted(template.source, template) }
    }

    @TestOnly
    fun testTriggerCopyWiki(template: HandoverTemplate) {
        scope.launch { handleCopyWiki(template.source) }
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    companion object {

        /**
         * Production factory — wires all real services and builds child widgets.
         */
        fun create(project: Project): ShareTab {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val handoverStateFlow = HandoverStateService.getInstance(project).stateFlow
            val workflowContextFlow = WorkflowContextService.getInstance(project).state
            val eventBus = project.getService(EventBus::class.java)
            val jiraService = project.getService(JiraService::class.java)
            val templateStore = HandoverTemplateStore.getInstance(project).templates
            val resolver = project.getService(HandoverPlaceholderResolver::class.java)

            val templateActions = TemplateActions(
                onUpdate = { id, source -> HandoverTemplateStore.getInstance(project).update(id, source) },
                onCreate = { name, action, source -> HandoverTemplateStore.getInstance(project).create(name, action, source) },
                onDuplicate = { sourceId, newName -> HandoverTemplateStore.getInstance(project).duplicate(sourceId, newName) },
                onDelete = { id -> HandoverTemplateStore.getInstance(project).delete(id) },
            )

            // Build placeholder cards with stub callbacks; real callbacks require 'tab' which
            // doesn't exist yet. We use a shared holder so the lambdas can reference 'tab'
            // once it is constructed.
            val holder = arrayOfNulls<ShareTab>(1)

            val jiraPreview = JiraPreviewPane(
                project = project,
                renderer = project.getService(HandoverWikiPreviewRendererService::class.java),
                ticketKeyProvider = { workflowContextFlow.value.activeTicket?.key.orEmpty() },
                cs = scope,
            )

            val jiraCard = TemplateEditorCard(
                project = project,
                title = "Jira Comment",
                formatPill = "WIKI",
                action = HandoverTemplateAction.JIRA,
                previewPane = jiraPreview,
                primaryActionLabel = "Post Comment",
                onPrimaryAction = { markup, tmpl -> holder[0]?.handlePostComment(markup, tmpl) },
                secondaryActionLabel = "Copy as wiki",
                onSecondaryAction = { markup, _ -> holder[0]?.handleCopyWiki(markup) },
                templateActions = templateActions,
                placeholderResolver = resolver,
            )

            val emailCard = TemplateEditorCard(
                project = project,
                title = "Email",
                formatPill = "HTML",
                action = HandoverTemplateAction.EMAIL,
                previewPane = EmailPreviewPane(),
                primaryActionLabel = "Copy formatted",
                onPrimaryAction = { markup, tmpl -> holder[0]?.handleCopyFormatted(markup, tmpl) },
                templateActions = templateActions,
                placeholderResolver = resolver,
            )

            val chipsPanel = QuickValueChipsPanel(project, scope)

            val tab = ShareTab(
                project = project,
                handoverStateFlow = handoverStateFlow,
                workflowContextFlow = workflowContextFlow,
                eventBus = eventBus,
                jiraService = jiraService,
                clipboardSink = ClipboardSink.system,
                jiraCard = jiraCard,
                emailCard = emailCard,
                chipsPanel = chipsPanel,
                templateStore = templateStore,
                scope = scope,
            )
            holder[0] = tab
            return tab
        }

        /**
         * Test-only factory. Accepts pre-built [TemplateEditorCard] instances so tests can
         * inject mockk-based [Project] stubs without polluting production code with a test dep.
         */
        @TestOnly
        fun forTest(
            handoverStateFlow: StateFlow<HandoverState>,
            workflowContextFlow: StateFlow<WorkflowContext>,
            eventBus: EventBus,
            jiraService: JiraService?,
            clipboardSink: ClipboardSink,
            scope: CoroutineScope,
            jiraCard: TemplateEditorCard,
            emailCard: TemplateEditorCard,
        ): ShareTab {
            val emptyTemplates = MutableStateFlow<List<HandoverTemplate>>(emptyList())
            return ShareTab(
                project = null,
                handoverStateFlow = handoverStateFlow,
                workflowContextFlow = workflowContextFlow,
                eventBus = eventBus,
                jiraService = jiraService,
                clipboardSink = clipboardSink,
                jiraCard = jiraCard,
                emailCard = emailCard,
                chipsPanel = null,
                templateStore = emptyTemplates,
                scope = scope,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// NoOpPreviewPane — lightweight preview pane for test-only TemplateEditorCards
// ---------------------------------------------------------------------------

internal class NoOpPreviewPane : PreviewPane {
    private val panel = JPanel()
    override fun setRenderedMarkup(html: String) { /* no-op */ }
    override fun asComponent(): JComponent = panel
}

// ---------------------------------------------------------------------------
// OutlookHtmlTransferable — multi-flavor clipboard payload for Outlook compat
// ---------------------------------------------------------------------------

internal class OutlookHtmlTransferable(private val html: String) : Transferable {
    private val htmlFlavor: DataFlavor = DataFlavor("text/html;class=java.lang.String")
    private val plainFlavor: DataFlavor = DataFlavor.stringFlavor

    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(htmlFlavor, plainFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        flavor == htmlFlavor || flavor == plainFlavor

    override fun getTransferData(flavor: DataFlavor): Any = when {
        flavor == htmlFlavor -> html
        flavor == plainFlavor -> stripHtml(html)
        else -> throw UnsupportedFlavorException(flavor)
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()
}
