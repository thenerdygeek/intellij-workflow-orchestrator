package com.workflow.orchestrator.jira.ui.widgets

import com.workflow.orchestrator.core.model.jira.FieldOption
import com.workflow.orchestrator.core.model.jira.FieldSchema
import com.workflow.orchestrator.core.model.jira.FieldValue
import com.workflow.orchestrator.core.model.jira.TransitionField
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// ── Helpers ───────────────────────────────────────────────────────────────────

private val OPTIONS = listOf(
    FieldOption(id = "a", value = "Alpha"),
    FieldOption(id = "b", value = "Beta"),
    FieldOption(id = "c", value = "Gamma")
)

private fun singleField(required: Boolean = false) = TransitionField(
    id = "priority",
    name = "Priority",
    required = required,
    schema = FieldSchema.SingleSelect(com.workflow.orchestrator.core.model.jira.SelectSource.AllowedValues),
    allowedValues = OPTIONS,
    autoCompleteUrl = null,
    defaultValue = null
)

private fun multiField(required: Boolean = false) = TransitionField(
    id = "labels",
    name = "Labels",
    required = required,
    schema = FieldSchema.MultiSelect(com.workflow.orchestrator.core.model.jira.SelectSource.AllowedValues),
    allowedValues = OPTIONS,
    autoCompleteUrl = null,
    defaultValue = null
)

private fun cascadeField(required: Boolean = false) = TransitionField(
    id = "region",
    name = "Region",
    required = required,
    schema = FieldSchema.CascadingSelect,
    allowedValues = listOf(
        FieldOption(id = "p1", value = "APAC"),
        FieldOption(id = "p2", value = "EMEA")
    ),
    autoCompleteUrl = null,
    defaultValue = null
)

// ── SingleSelectWidget tests ──────────────────────────────────────────────────

class OptionWidgetsTest {

    @Test
    fun `SingleSelect setInitial Option(id) round-trips currentValue`() {
        val widget = SingleSelectWidget(singleField())
        widget.setInitial(FieldValue.Option("a"))
        assertEquals(FieldValue.Option("a"), widget.currentValue())
    }

    @Test
    fun `SingleSelect validate returns null when not required and nothing selected`() {
        val widget = SingleSelectWidget(singleField(required = false))
        // Default combo index is 0 (first item), so value is non-null
        assertNull(widget.validate())
    }

    @Test
    fun `SingleSelect currentValue returns null when no items`() {
        val emptyField = singleField().copy(allowedValues = emptyList())
        val widget = SingleSelectWidget(emptyField)
        assertNull(widget.currentValue())
    }

    @Test
    fun `SingleSelect setInitial unknown id leaves selection unchanged`() {
        val widget = SingleSelectWidget(singleField())
        widget.setInitial(FieldValue.Option("b"))   // selects "Beta"
        widget.setInitial(FieldValue.Option("zzz")) // unknown — ignored
        // Should still be "b"
        assertEquals(FieldValue.Option("b"), widget.currentValue())
    }

    // ── MultiSelectWidget tests ───────────────────────────────────────────────

    @Test
    fun `MultiSelect setInitial Options round-trips currentValue`() {
        val widget = MultiSelectWidget(multiField())
        widget.setInitial(FieldValue.Options(listOf("a", "c")))
        val result = widget.currentValue()
        assertNotNull(result)
        assertTrue(result is FieldValue.Options)
        val ids = (result as FieldValue.Options).ids.toSet()
        assertEquals(setOf("a", "c"), ids)
    }

    @Test
    fun `MultiSelect currentValue returns null when nothing is selected`() {
        val widget = MultiSelectWidget(multiField())
        // Nothing pre-selected
        assertNull(widget.currentValue())
    }

    @Test
    fun `MultiSelect validate returns null when not required`() {
        val widget = MultiSelectWidget(multiField(required = false))
        assertNull(widget.validate())
    }

    @Test
    fun `MultiSelect validate returns error when required and empty`() {
        val widget = MultiSelectWidget(multiField(required = true))
        val msg = widget.validate()
        assertNotNull(msg)
        assertTrue(msg!!.contains("Labels"))
    }

    // ── CascadingSelectWidget tests ───────────────────────────────────────────

    @Test
    fun `CascadingSelect setInitial Cascade round-trips parentId`() {
        val widget = CascadingSelectWidget(cascadeField())
        widget.setInitial(FieldValue.Cascade(parentId = "p1", childId = null))
        val result = widget.currentValue()
        assertNotNull(result)
        assertTrue(result is FieldValue.Cascade)
        assertEquals("p1", (result as FieldValue.Cascade).parentId)
        assertNull(result.childId)  // v1: child always null
    }

    @Test
    fun `CascadingSelect setInitial with childId still sets parent correctly`() {
        val widget = CascadingSelectWidget(cascadeField())
        widget.setInitial(FieldValue.Cascade(parentId = "p2", childId = "child-x"))
        val result = widget.currentValue() as? FieldValue.Cascade
        assertNotNull(result)
        assertEquals("p2", result!!.parentId)
        // v1 does not persist childId
        assertNull(result.childId)
    }

    @Test
    fun `CascadingSelect validate returns null when not required`() {
        val widget = CascadingSelectWidget(cascadeField(required = false))
        assertNull(widget.validate())
    }

    @Test
    fun `CascadingSelect setInitial Option(id) maps to parent`() {
        val widget = CascadingSelectWidget(cascadeField())
        widget.setInitial(FieldValue.Option("p2"))
        val result = widget.currentValue() as? FieldValue.Cascade
        assertNotNull(result)
        assertEquals("p2", result!!.parentId)
    }
}
