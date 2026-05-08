package com.workflow.orchestrator.handover.ui.editor

import com.workflow.orchestrator.handover.model.HandoverTemplate
import com.workflow.orchestrator.handover.model.HandoverTemplateAction
import com.workflow.orchestrator.handover.model.HandoverTemplateOrigin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import javax.swing.JComboBox

class TemplatePickerTest {

    private fun mk(name: String, origin: HandoverTemplateOrigin) =
        HandoverTemplate(
            id = "jira/${name.lowercase().replace(' ', '-')}",
            name = name,
            action = HandoverTemplateAction.JIRA,
            source = "h2. $name",
            origin = origin,
        )

    @Test
    fun `dropdown shows all real templates plus a +New sentinel`() {
        val picker = TemplatePicker()
        picker.setTemplates(listOf(
            mk("Standard closure", HandoverTemplateOrigin.BUNDLED),
            mk("Hotfix", HandoverTemplateOrigin.GLOBAL),
        ))
        // Walk the combo's items
        val combo = picker.components.filterIsInstance<JComboBox<*>>().first()
        // Real templates: 2; sentinel: 1; total: 3
        assertEquals(3, combo.itemCount)
    }

    @Test
    fun `★ badge appears in render of project-origin item`() {
        val picker = TemplatePicker()
        picker.setTemplates(listOf(mk("Standard closure", HandoverTemplateOrigin.PROJECT)))
        val combo = picker.components.filterIsInstance<JComboBox<*>>().first()
        // Use the renderer to render the first item as a JLabel/JComponent
        @Suppress("UNCHECKED_CAST")
        val renderer = combo.renderer as javax.swing.ListCellRenderer<Any?>
        val rendered = renderer.getListCellRendererComponent(
            javax.swing.JList<Any?>(), combo.getItemAt(0), 0, false, false
        ) as javax.swing.JLabel
        assertTrue(rendered.text.contains("★"), "expected ★ in: ${rendered.text}")
    }

    @Test
    fun `selecting an item fires onSelectionChanged`() {
        val picker = TemplatePicker()
        picker.setTemplates(listOf(
            mk("Standard closure", HandoverTemplateOrigin.BUNDLED),
            mk("Hotfix", HandoverTemplateOrigin.GLOBAL),
        ))
        var fired: HandoverTemplate? = null
        picker.onSelectionChanged = { fired = it }

        val combo = picker.components.filterIsInstance<JComboBox<*>>().first()
        combo.selectedIndex = 1   // pick "Hotfix"
        assertEquals("Hotfix", fired?.name)
    }

    @Test
    fun `delete button disabled for bundled selection`() {
        val picker = TemplatePicker()
        picker.setTemplates(listOf(mk("Bundled", HandoverTemplateOrigin.BUNDLED)))
        // Find the delete button — locate by tooltip "Delete" (set deterministic tooltips)
        val deleteBtn = walk(picker).filterIsInstance<javax.swing.JButton>()
            .firstOrNull { it.toolTipText == "Delete" }
        assertNotNull(deleteBtn, "delete button should be present")
        assertFalse(deleteBtn!!.isEnabled, "delete should be disabled for bundled")
    }

    @Test
    fun `dirty dot toggles visibility`() {
        val picker = TemplatePicker()
        picker.setTemplates(listOf(mk("Bundled", HandoverTemplateOrigin.BUNDLED)))
        val dot = walk(picker).filterIsInstance<javax.swing.JLabel>()
            .firstOrNull { it.text?.contains("●") == true }
        assertNotNull(dot)
        // Default not dirty
        assertFalse(dot!!.isVisible)

        picker.setDirty(true)
        assertTrue(walk(picker).filterIsInstance<javax.swing.JLabel>()
            .first { it.text?.contains("●") == true }.isVisible)

        picker.setDirty(false)
        assertFalse(walk(picker).filterIsInstance<javax.swing.JLabel>()
            .first { it.text?.contains("●") == true }.isVisible)
    }

    @Test
    fun `selecting +New sentinel fires onCreateRequested only and not onSelectionChanged`() {
        val picker = TemplatePicker()
        picker.setTemplates(listOf(
            mk("Standard closure", HandoverTemplateOrigin.BUNDLED),
        ))
        var selectionCount = 0
        var createCount = 0
        picker.onSelectionChanged = { selectionCount++ }
        picker.onCreateRequested = { createCount++ }

        val combo = picker.components.filterIsInstance<JComboBox<*>>().first()
        // The sentinel is always the last item in the combo.
        combo.selectedIndex = combo.itemCount - 1

        assertEquals(1, createCount, "onCreateRequested should have fired exactly once")
        assertEquals(0, selectionCount, "onSelectionChanged must NOT fire when sentinel is selected")
    }

    private fun walk(c: java.awt.Container): List<java.awt.Component> = buildList {
        for (i in 0 until c.componentCount) {
            val child = c.getComponent(i)
            add(child)
            if (child is java.awt.Container) addAll(walk(child))
        }
    }
}

private fun TemplatePicker.isDirtyVisible(): Boolean = false  // optional helper if you need it
