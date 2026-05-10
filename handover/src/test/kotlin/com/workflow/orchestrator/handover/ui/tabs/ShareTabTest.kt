package com.workflow.orchestrator.handover.ui.tabs

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.handover.model.HandoverState
import com.workflow.orchestrator.handover.model.HandoverTemplate
import com.workflow.orchestrator.handover.model.HandoverTemplateAction
import com.workflow.orchestrator.handover.model.HandoverTemplateOrigin
import com.workflow.orchestrator.handover.model.SuiteResult
import com.workflow.orchestrator.handover.service.HandoverPlaceholderResolver
import com.workflow.orchestrator.handover.ui.editor.TemplateActions
import com.workflow.orchestrator.handover.ui.editor.TemplateEditorCard
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.time.Instant

class ShareTabTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun jiraTemplate(source: String = "Closing ticket") = HandoverTemplate(
        id = "jira/test", name = "Test Jira", action = HandoverTemplateAction.JIRA,
        source = source, origin = HandoverTemplateOrigin.BUNDLED,
    )

    private fun emailTemplate(source: String = "<b>Done</b>") = HandoverTemplate(
        id = "email/test", name = "Test Email", action = HandoverTemplateAction.EMAIL,
        source = source, origin = HandoverTemplateOrigin.BUNDLED,
    )

    private fun stateWithRedChecks() = HandoverState(
        ticketId = "AFTER8TE-912",
        qualityGatePassed = false,
        suiteResults = listOf(
            SuiteResult(
                suitePlanKey = "API-SMOKE",
                buildResultKey = "API-SMOKE-1",
                dockerTagsJson = "{}",
                passed = false,
                durationMs = null,
                triggeredAt = Instant.now(),
                bambooLink = "http://bamboo/browse/API-SMOKE-1",
            )
        ),
    )

    private fun stateAllGreen() = HandoverState(
        ticketId = "AFTER8TE-912",
        qualityGatePassed = true,
        suiteResults = listOf(
            SuiteResult(
                suitePlanKey = "API-SMOKE",
                buildResultKey = "API-SMOKE-1",
                dockerTagsJson = "{}",
                passed = true,
                durationMs = 1000,
                triggeredAt = Instant.now(),
                bambooLink = "http://bamboo/browse/API-SMOKE-1",
            )
        ),
    )

    /**
     * Build a ShareTab with injected collaborators.
     * TemplateEditorCard.forTest needs a non-null Project; we use mockk in test scope here
     * (acceptable — test source only, no production code involved).
     */
    private fun buildTab(
        handoverState: HandoverState,
        workflowContext: WorkflowContext = WorkflowContext(),
        eventBus: EventBus,
        jiraService: JiraService? = null,
        clipboardSink: ClipboardSink,
        scope: kotlinx.coroutines.CoroutineScope,
    ): ShareTab {
        val resolver = mockk<HandoverPlaceholderResolver>(relaxed = true)
        val project = mockk<Project>(relaxed = true)
        val noopActions = TemplateActions(
            onUpdate = { _, _ -> },
            onCreate = { _, _, _ -> },
            onDuplicate = { _, _ -> },
            onDelete = { _ -> },
        )
        // We can't reference 'tab' before it exists, so we use a holder.
        val holder = arrayOfNulls<ShareTab>(1)

        val jiraCard = TemplateEditorCard.forTest(
            project = project,
            title = "Jira Comment",
            formatPill = "WIKI",
            action = HandoverTemplateAction.JIRA,
            previewPane = NoOpPreviewPane(),
            primaryActionLabel = "Post Comment",
            onPrimaryAction = { markup, tmpl -> holder[0]?.handlePostComment(markup, tmpl) },
            secondaryActionLabel = "Copy as wiki",
            onSecondaryAction = { markup, _ -> holder[0]?.handleCopyWiki(markup) },
            templateActions = noopActions,
            placeholderResolver = resolver,
            scope = scope,
        )

        val emailCard = TemplateEditorCard.forTest(
            project = project,
            title = "Email",
            formatPill = "HTML",
            action = HandoverTemplateAction.EMAIL,
            previewPane = NoOpPreviewPane(),
            primaryActionLabel = "Copy formatted",
            onPrimaryAction = { markup, tmpl -> holder[0]?.handleCopyFormatted(markup, tmpl) },
            templateActions = noopActions,
            placeholderResolver = resolver,
            scope = scope,
        )

        val tab = ShareTab.forTest(
            handoverStateFlow = MutableStateFlow(handoverState),
            workflowContextFlow = MutableStateFlow(workflowContext),
            eventBus = eventBus,
            jiraService = jiraService,
            clipboardSink = clipboardSink,
            scope = scope,
            jiraCard = jiraCard,
            emailCard = emailCard,
        )
        holder[0] = tab
        return tab
    }

    // ── Test 1: Email "Copy formatted" places a multi-flavor Transferable ────

    @Test
    fun `email copy formatted places Transferable with html and plain flavors`() = runTest(UnconfinedTestDispatcher()) {
        val capturedTransferable = slot<Transferable>()
        val clipboardSink = ClipboardSink { transferable ->
            capturedTransferable.captured = transferable
        }

        val eventBus = mockk<EventBus>(relaxed = true)

        val tab = buildTab(
            handoverState = stateAllGreen(),
            eventBus = eventBus,
            clipboardSink = clipboardSink,
            scope = this,
        )

        val htmlSource = "<b>Release complete</b>"
        tab.testTriggerCopyFormatted(emailTemplate(htmlSource))
        advanceUntilIdle()

        assertTrue(capturedTransferable.isCaptured, "expected transferable to be placed on clipboard")
        val t = capturedTransferable.captured
        val flavors = t.transferDataFlavors
        assertTrue(
            flavors.any { it.mimeType.startsWith("text/html") },
            "must advertise text/html flavor; got ${flavors.map { it.mimeType }}",
        )
        assertTrue(
            flavors.any { it == DataFlavor.stringFlavor },
            "must advertise text/plain flavor",
        )

        val htmlFlavor = flavors.first { it.mimeType.startsWith("text/html") }
        val htmlData = t.getTransferData(htmlFlavor) as String
        assertEquals(htmlSource, htmlData)

        val plainData = t.getTransferData(DataFlavor.stringFlavor) as String
        assertFalse(plainData.contains("<"), "plain flavor should strip HTML tags; got: $plainData")
    }

    // ── Test 2: Post Comment with red checks → emits HandoverOverride first ──

    @Test
    fun `post comment with red checks emits HandoverOverride before calling addComment`() = runTest(UnconfinedTestDispatcher()) {
        val eventBus = mockk<EventBus>(relaxed = true)
        val jiraService = mockk<JiraService>(relaxed = true)

        coEvery {
            jiraService.addComment(any(), any(), any())
        } returns ToolResult.success(Unit, "comment posted")

        // Identity comes from WorkflowContext (canonical). Supply the matching ticket so
        // resolveTicketId() doesn't early-return on blank ticket key.
        val tab = buildTab(
            handoverState = stateWithRedChecks(),
            workflowContext = WorkflowContext(activeTicket = TicketRef("AFTER8TE-912", "Test ticket")),
            eventBus = eventBus,
            jiraService = jiraService,
            clipboardSink = ClipboardSink { },
            scope = this,
        )

        tab.testTriggerPostComment(jiraTemplate())
        advanceUntilIdle()

        // HandoverOverride must have been emitted
        coVerify {
            eventBus.emit(match<WorkflowEvent.HandoverOverride> { ev ->
                ev.action == WorkflowEvent.HandoverAction.POST_JIRA &&
                    ev.failedChecks.isNotEmpty()
            })
        }

        // addComment must also have been called
        coVerify { jiraService.addComment(any(), any(), any()) }
    }

    // ── Test 3: Post Comment with all green → NO HandoverOverride emitted ────

    @Test
    fun `post comment with all green does NOT emit HandoverOverride`() = runTest(UnconfinedTestDispatcher()) {
        val eventBus = mockk<EventBus>(relaxed = true)
        val jiraService = mockk<JiraService>(relaxed = true)

        coEvery {
            jiraService.addComment(any(), any(), any())
        } returns ToolResult.success(Unit, "comment posted")

        // Identity comes from WorkflowContext (canonical). Supply the matching ticket so
        // resolveTicketId() doesn't early-return on blank ticket key.
        val tab = buildTab(
            handoverState = stateAllGreen(),
            workflowContext = WorkflowContext(activeTicket = TicketRef("AFTER8TE-912", "Test ticket")),
            eventBus = eventBus,
            jiraService = jiraService,
            clipboardSink = ClipboardSink { },
            scope = this,
        )

        tab.testTriggerPostComment(jiraTemplate())
        advanceUntilIdle()

        // HandoverOverride must NOT have been emitted
        coVerify(exactly = 0) {
            eventBus.emit(ofType<WorkflowEvent.HandoverOverride>())
        }

        // But addComment must still have been called
        coVerify { jiraService.addComment(any(), any(), any()) }
    }
}
