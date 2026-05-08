package com.workflow.orchestrator.handover.ui.editor

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.handover.model.HandoverPlaceholderValue
import com.workflow.orchestrator.handover.model.HandoverTemplate
import com.workflow.orchestrator.handover.model.HandoverTemplateAction
import com.workflow.orchestrator.handover.model.HandoverTemplateOrigin
import com.workflow.orchestrator.handover.service.HandoverPlaceholderResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import javax.swing.JComponent

class TemplateEditorCardTest {

    private val resolver: HandoverPlaceholderResolver = mockk()
    private val previewPane = object : PreviewPane {
        var lastRender: String? = null
        override fun setRenderedMarkup(html: String) { lastRender = html }
        override fun asComponent(): JComponent = javax.swing.JPanel()
    }

    private fun mkTemplate(name: String, source: String) = HandoverTemplate(
        id = "jira/$name",
        name = name,
        action = HandoverTemplateAction.JIRA,
        source = source,
        origin = HandoverTemplateOrigin.GLOBAL,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `binding to flow populates picker, selecting first loads source into pane`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { resolver.resolve(any(), any()) } returns HandoverPlaceholderValue.available("X")
            val flow = MutableStateFlow(listOf(mkTemplate("A", "h2. {ticket.id}"), mkTemplate("B", "x")))
            val card = newCard(scope = backgroundScope)
            card.bind(flow)
            // With UnconfinedTestDispatcher, the collect block runs eagerly; no advance needed.

            val sourceText = card.testGetSourceText()
            assertEquals("h2. {ticket.id}", sourceText)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `editing source debounces preview resolution`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { resolver.resolve("ticket.id", any()) } returns HandoverPlaceholderValue.available("AFTER8TE-912")
            val flow = MutableStateFlow(listOf(mkTemplate("A", "h2. {ticket.id}")))
            val card = newCard(scope = backgroundScope)
            card.bind(flow)
            // With UnconfinedTestDispatcher, coroutines run eagerly; the initial triggerPreview
            // completes (including the mockk suspend call) before we get here.

            // Reset the tracker now that initial selection preview has settled.
            previewPane.lastRender = null

            // Modify source — triggers debounced preview job (100ms delay)
            card.testSetSourceText("h2. {ticket.id}!")
            advanceTimeBy(50)        // before 100ms debounce fires
            assertNull(previewPane.lastRender, "preview shouldn't render yet")

            advanceTimeBy(100)
            advanceUntilIdle()
            assertEquals("h2. AFTER8TE-912!", previewPane.lastRender)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `editing source eventually triggers auto-save with 300ms debounce`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { resolver.resolve(any(), any()) } returns HandoverPlaceholderValue.available("v")
            val flow = MutableStateFlow(listOf(mkTemplate("A", "x")))
            val onUpdate = mockk<suspend (String, String) -> Unit>(relaxed = true)
            val card = newCard(onUpdate = onUpdate, scope = backgroundScope)
            card.bind(flow)
            // With UnconfinedTestDispatcher, initial selection + triggerPreview run eagerly.

            card.testSetSourceText("y")
            advanceTimeBy(200)
            coVerify(exactly = 0) { onUpdate(any(), any()) }    // not yet

            advanceTimeBy(150)
            advanceUntilIdle()
            coVerify(atLeast = 1) { onUpdate("jira/A", "y") }
        }

    private fun newCard(
        onUpdate: suspend (String, String) -> Unit = mockk(relaxed = true),
        scope: CoroutineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob()),
    ): TemplateEditorCard {
        val project = mockk<Project>(relaxed = true)
        val actions = TemplateActions(
            onUpdate = onUpdate,
            onCreate = mockk(relaxed = true),
            onDuplicate = mockk(relaxed = true),
            onDelete = mockk(relaxed = true),
        )
        return TemplateEditorCard.forTest(
            project = project,
            title = "Jira Comment",
            formatPill = null,
            action = HandoverTemplateAction.JIRA,
            previewPane = previewPane,
            primaryActionLabel = "Post Comment",
            secondaryActionLabel = "Copy as wiki",
            onPrimaryAction = { _, _ -> },
            onSecondaryAction = { _, _ -> },
            templateActions = actions,
            placeholderResolver = resolver,
            scope = scope,
        )
    }
}
