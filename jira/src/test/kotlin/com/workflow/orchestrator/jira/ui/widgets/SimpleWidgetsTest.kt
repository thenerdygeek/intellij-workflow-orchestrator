package com.workflow.orchestrator.jira.ui.widgets

import com.workflow.orchestrator.core.model.jira.FieldOption
import com.workflow.orchestrator.core.model.jira.FieldSchema
import com.workflow.orchestrator.core.model.jira.FieldValue
import com.workflow.orchestrator.core.model.jira.TransitionField
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Shared helper — no IntelliJ Application needed for these pure logic tests. */
private fun textField(required: Boolean = false) = TransitionField(
    id = "summary",
    name = "Summary",
    required = required,
    schema = FieldSchema.Text,
    allowedValues = emptyList(),
    autoCompleteUrl = null,
    defaultValue = null
)

private fun numberField(required: Boolean = false) = TransitionField(
    id = "storyPoints",
    name = "Story Points",
    required = required,
    schema = FieldSchema.Number,
    allowedValues = emptyList(),
    autoCompleteUrl = null,
    defaultValue = null
)

private fun dateField(required: Boolean = false) = TransitionField(
    id = "dueDate",
    name = "Due Date",
    required = required,
    schema = FieldSchema.Date,
    allowedValues = emptyList(),
    autoCompleteUrl = null,
    defaultValue = null
)

class SimpleWidgetsTest {

    // ── TextFieldWidget ───────────────────────────────────────────────────────

    @Test
    fun `TextFieldWidget round-trips Text value`() {
        val widget = TextFieldWidget(textField())
        widget.setInitial(FieldValue.Text("hi"))
        val result = widget.currentValue()
        assertEquals(FieldValue.Text("hi"), result)
    }

    @Test
    fun `TextFieldWidget returns null for empty input`() {
        val widget = TextFieldWidget(textField())
        widget.setInitial(null)
        assertNull(widget.currentValue())
    }

    @Test
    fun `TextFieldWidget validate returns null when not required`() {
        val widget = TextFieldWidget(textField(required = false))
        assertNull(widget.validate())
    }

    @Test
    fun `TextFieldWidget validate returns error when required and empty`() {
        val widget = TextFieldWidget(textField(required = true))
        val msg = widget.validate()
        assertNotNull(msg)
        assertTrue(msg!!.contains("Summary"))
    }

    // ── NumberFieldWidget ─────────────────────────────────────────────────────

    @Test
    fun `NumberFieldWidget round-trips Number value`() {
        val widget = NumberFieldWidget(numberField())
        widget.setInitial(FieldValue.Number(5.0))
        val result = widget.currentValue()
        assertEquals(FieldValue.Number(5.0), result)
    }

    @Test
    fun `NumberFieldWidget validate returns error on non-numeric input`() {
        val widget = NumberFieldWidget(numberField())
        widget.setInitial(FieldValue.Text("not-a-number"))
        val msg = widget.validate()
        assertNotNull(msg, "Expected a validation error for non-numeric text")
    }

    @Test
    fun `NumberFieldWidget returns null for empty input`() {
        val widget = NumberFieldWidget(numberField())
        assertNull(widget.currentValue())
    }

    @Test
    fun `NumberFieldWidget validate returns null for valid integer string`() {
        val widget = NumberFieldWidget(numberField())
        widget.setInitial(FieldValue.Number(42.0))
        assertNull(widget.validate())
    }

    // ── DatePickerWidget ──────────────────────────────────────────────────────

    @Test
    fun `DatePickerWidget round-trips ISO date`() {
        val widget = DatePickerWidget(dateField())
        widget.setInitial(FieldValue.Date("2024-06-15"))
        val result = widget.currentValue()
        assertNotNull(result)
        assertTrue(result is FieldValue.Date)
        assertEquals("2024-06-15", (result as FieldValue.Date).iso)
    }

    @Test
    fun `DatePickerWidget has non-null currentValue after construction`() {
        val widget = DatePickerWidget(dateField())
        // SpinnerDateModel initialises to now — so currentValue() should never be null
        assertNotNull(widget.currentValue())
    }

    @Test
    fun `DatePickerWidget validate returns null always`() {
        val widget = DatePickerWidget(dateField())
        assertNull(widget.validate())
    }

    // ── DateTimePickerWidget ──────────────────────────────────────────────────

    @Test
    fun `DateTimePickerWidget has non-null currentValue after construction`() {
        val field = TransitionField(
            id = "startDate", name = "Start Date", required = false,
            schema = FieldSchema.DateTime,
            allowedValues = emptyList(), autoCompleteUrl = null, defaultValue = null
        )
        val widget = DateTimePickerWidget(field)
        assertNotNull(widget.currentValue())
        assertTrue(widget.currentValue() is FieldValue.DateTime)
    }

    @Test
    fun `DateTimePickerWidget validate returns null always`() {
        val field = TransitionField(
            id = "startDate", name = "Start Date", required = false,
            schema = FieldSchema.DateTime,
            allowedValues = emptyList(), autoCompleteUrl = null, defaultValue = null
        )
        assertNull(DateTimePickerWidget(field).validate())
    }
}
