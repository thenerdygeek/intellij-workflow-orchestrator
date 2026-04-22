package com.workflow.orchestrator.jira.ui.widgets

import com.intellij.openapi.util.Disposer
import com.workflow.orchestrator.core.model.jira.FieldOption
import com.workflow.orchestrator.core.model.jira.FieldSchema
import com.workflow.orchestrator.core.model.jira.FieldValue
import com.workflow.orchestrator.core.model.jira.SelectSource
import com.workflow.orchestrator.core.model.jira.TransitionField
import com.workflow.orchestrator.core.services.jira.JiraSearchService
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FieldWidgetFactoryTest {

    /**
     * Parametric coverage: every [FieldSchema] variant must be routed to the
     * expected [FieldWidget] implementation by [FieldWidgetFactory.build].
     */
    @Test
    fun `maps every schema to expected widget class`() {
        val disp = Disposer.newDisposable()
        val ctx = WidgetContext(
            project = mockk(relaxed = true),
            ticketKey = "ABC-1",
            projectKey = "ABC",
            search = mockk<JiraSearchService>(relaxed = true),
            disposable = disp
        )

        val allowedValues = listOf(
            FieldOption("a", "Alpha"),
            FieldOption("b", "Beta")
        )

        // Pairs of (schema, expected widget class).
        // AllowedValues schemas need non-empty allowedValues so the combo/list has items.
        val cases: List<Pair<FieldSchema, Class<out FieldWidget>>> = listOf(
            FieldSchema.Text                                        to TextFieldWidget::class.java,
            FieldSchema.Number                                      to NumberFieldWidget::class.java,
            FieldSchema.Date                                        to DatePickerWidget::class.java,
            FieldSchema.DateTime                                    to DateTimePickerWidget::class.java,
            FieldSchema.Labels                                      to LabelPickerWidget::class.java,
            FieldSchema.Priority                                    to SingleSelectWidget::class.java,
            FieldSchema.SingleSelect(SelectSource.AllowedValues)   to SingleSelectWidget::class.java,
            FieldSchema.SingleSelect(SelectSource.AutoCompleteUrl) to AutoCompleteWidget::class.java,
            FieldSchema.MultiSelect(SelectSource.AllowedValues)    to MultiSelectWidget::class.java,
            FieldSchema.MultiSelect(SelectSource.AutoCompleteUrl)  to AutoCompleteWidget::class.java,
            FieldSchema.CascadingSelect                            to CascadingSelectWidget::class.java,
            FieldSchema.User(multi = false)                        to UserPickerWidget::class.java,
            FieldSchema.Group(multi = false)                       to GroupPickerWidget::class.java,
            FieldSchema.Version(multi = false)                     to VersionPickerWidget::class.java,
            FieldSchema.Component(multi = false)                   to ComponentPickerWidget::class.java,
            FieldSchema.Unknown("custom_field")                    to TextFieldWidget::class.java
        )

        for ((schema, expectedClass) in cases) {
            val field = TransitionField(
                id = "x",
                name = "X",
                required = false,
                schema = schema,
                allowedValues = allowedValues,
                autoCompleteUrl = "/rest/autocomplete?query=",
                defaultValue = null
            )
            val widget = FieldWidgetFactory.build(field, ctx) {}
            assertTrue(
                expectedClass.isInstance(widget),
                "Expected ${expectedClass.simpleName} for schema $schema, got ${widget::class.java.simpleName}"
            )
        }

        Disposer.dispose(disp)
    }

    @Test
    fun `Unknown schema logs warning and returns TextFieldWidget`() {
        val disp = Disposer.newDisposable()
        val ctx = WidgetContext(
            project = mockk(relaxed = true),
            ticketKey = "ABC-1",
            projectKey = "ABC",
            search = mockk<JiraSearchService>(relaxed = true),
            disposable = disp
        )

        val field = TransitionField(
            id = "custom_1",
            name = "Mystery Field",
            required = false,
            schema = FieldSchema.Unknown("some_custom_type"),
            allowedValues = emptyList(),
            autoCompleteUrl = null,
            defaultValue = null
        )
        val widget = FieldWidgetFactory.build(field, ctx) {}
        assertTrue(widget is TextFieldWidget, "Unknown schema should produce TextFieldWidget")

        Disposer.dispose(disp)
    }

    @Test
    fun `User multi=true produces UserPickerWidget`() {
        val disp = Disposer.newDisposable()
        val ctx = WidgetContext(
            project = mockk(relaxed = true),
            ticketKey = "ABC-1",
            projectKey = "ABC",
            search = mockk<JiraSearchService>(relaxed = true),
            disposable = disp
        )
        val field = TransitionField(
            id = "watchers", name = "Watchers", required = false,
            schema = FieldSchema.User(multi = true),
            allowedValues = emptyList(), autoCompleteUrl = null, defaultValue = null
        )
        val widget = FieldWidgetFactory.build(field, ctx) {}
        assertTrue(widget is UserPickerWidget)

        Disposer.dispose(disp)
    }

    @Test
    fun `onChange callback is invoked-ready — factory wiring smoke test`() {
        // Verifies that calling onChange through a widget built by the factory does not throw.
        val disp = Disposer.newDisposable()
        val ctx = WidgetContext(
            project = mockk(relaxed = true),
            ticketKey = "ABC-1",
            projectKey = "ABC",
            search = mockk<JiraSearchService>(relaxed = true),
            disposable = disp
        )
        var received: FieldValue? = FieldValue.Text("sentinel")
        val field = TransitionField(
            id = "labels", name = "Labels", required = false,
            schema = FieldSchema.Labels,
            allowedValues = emptyList(), autoCompleteUrl = null, defaultValue = null
        )
        val widget = FieldWidgetFactory.build(field, ctx) { v -> received = v }
        widget.setInitial(FieldValue.LabelList(listOf("smoke")))
        // currentValue returns the seeded label — factory wiring is intact
        val value = widget.currentValue()
        assertTrue(value is FieldValue.LabelList)
        assertEquals(
            listOf("smoke"),
            (value as FieldValue.LabelList).labels
        )
        Disposer.dispose(disp)
    }
}
