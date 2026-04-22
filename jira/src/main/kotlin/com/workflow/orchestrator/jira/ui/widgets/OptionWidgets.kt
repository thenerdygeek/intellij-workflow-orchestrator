package com.workflow.orchestrator.jira.ui.widgets

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.jira.FieldOption
import com.workflow.orchestrator.core.model.jira.FieldValue
import com.workflow.orchestrator.core.model.jira.TransitionField
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.ListSelectionModel

// ── SingleSelectWidget ────────────────────────────────────────────────────────

/**
 * Single-choice picker backed by an IntelliJ [ComboBox].
 * The combo items are built from [TransitionField.allowedValues].
 * Maps to [FieldValue.Option].
 */
class SingleSelectWidget(override val field: TransitionField) : FieldWidget {

    /** Parallel list of option IDs matching the combo model order. */
    private val optionIds: List<String> = field.allowedValues.map { it.id }
    private val optionNames: Array<String> = field.allowedValues.map { it.value }.toTypedArray()

    private val combo = ComboBox(optionNames)
    override val component: JComponent = combo

    override fun currentValue(): FieldValue? {
        val selectedIdx = combo.selectedIndex
        if (selectedIdx < 0 || selectedIdx >= optionIds.size) return null
        return FieldValue.Option(optionIds[selectedIdx])
    }

    override fun validate(): String? {
        if (field.required && combo.selectedIndex < 0) return "${field.name} is required"
        return null
    }

    override fun setInitial(value: FieldValue?) {
        val targetId = when (value) {
            is FieldValue.Option -> value.id
            else -> null
        } ?: return
        val idx = optionIds.indexOf(targetId)
        if (idx >= 0) combo.selectedIndex = idx
    }

    /** Registers an [java.awt.event.ActionListener] notified on selection change. */
    fun addChangeListener(listener: java.awt.event.ActionListener) {
        combo.addActionListener(listener)
    }
}

// ── MultiSelectWidget ─────────────────────────────────────────────────────────

/**
 * Multi-choice picker backed by a [JBList] with [ListSelectionModel.MULTIPLE_INTERVAL_SELECTION]
 * wrapped in a [JBScrollPane].
 * Maps to [FieldValue.Options].
 */
class MultiSelectWidget(override val field: TransitionField) : FieldWidget {

    private val optionIds: List<String> = field.allowedValues.map { it.id }
    private val listModel = DefaultListModel<String>().apply {
        field.allowedValues.forEach { addElement(it.value) }
    }
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        border = JBUI.Borders.empty(2)
    }
    private val scroll = JBScrollPane(list).apply {
        preferredSize = java.awt.Dimension(JBUI.scale(240), JBUI.scale(100))
    }

    override val component: JComponent = scroll

    override fun currentValue(): FieldValue? {
        val selectedIndices = list.selectedIndices
        if (selectedIndices.isEmpty()) return null
        val ids = selectedIndices
            .filter { it in optionIds.indices }
            .map { optionIds[it] }
        return if (ids.isEmpty()) null else FieldValue.Options(ids)
    }

    override fun validate(): String? {
        if (field.required && list.selectedIndices.isEmpty()) return "${field.name} is required"
        return null
    }

    override fun setInitial(value: FieldValue?) {
        val targetIds = when (value) {
            is FieldValue.Options -> value.ids
            is FieldValue.Option -> listOf(value.id)
            else -> emptyList()
        }
        val indices = targetIds.mapNotNull { id ->
            val idx = optionIds.indexOf(id)
            if (idx >= 0) idx else null
        }.toIntArray()
        list.selectedIndices = indices
    }

    /** Registers a [javax.swing.event.ListSelectionListener] notified on selection change. */
    fun addSelectionListener(listener: javax.swing.event.ListSelectionListener) {
        list.addListSelectionListener(listener)
    }
}

// ── CascadingSelectWidget ─────────────────────────────────────────────────────

/**
 * Cascading (parent + optional child) select widget.
 *
 * **v1 implementation: parent combo only.**
 * The Jira cascading-select schema carries both parent and child options, but the child
 * options depend on which parent is selected and the parent options are themselves dynamic
 * in some projects. Until T25 entity-picker machinery is in place (which can resolve child
 * options at runtime via [JiraSearchService.followAutoCompleteUrl]), we only expose the
 * parent combo. The child is always set to `null` in the emitted [FieldValue.Cascade].
 *
 * Maps to [FieldValue.Cascade] with `childId = null`.
 */
class CascadingSelectWidget(override val field: TransitionField) : FieldWidget {

    private val parentIds: List<String> = field.allowedValues.map { it.id }
    private val parentNames: Array<String> = field.allowedValues.map { it.value }.toTypedArray()

    private val parentCombo = ComboBox(parentNames)
    override val component: JComponent = parentCombo

    override fun currentValue(): FieldValue? {
        val idx = parentCombo.selectedIndex
        if (idx < 0 || idx >= parentIds.size) return null
        return FieldValue.Cascade(parentId = parentIds[idx], childId = null)
    }

    override fun validate(): String? {
        if (field.required && parentCombo.selectedIndex < 0) return "${field.name} is required"
        return null
    }

    override fun setInitial(value: FieldValue?) {
        val parentId = when (value) {
            is FieldValue.Cascade -> value.parentId
            is FieldValue.Option -> value.id
            else -> null
        } ?: return
        val idx = parentIds.indexOf(parentId)
        if (idx >= 0) parentCombo.selectedIndex = idx
    }

    /** Registers an [java.awt.event.ActionListener] notified on parent selection change. */
    fun addChangeListener(listener: java.awt.event.ActionListener) {
        parentCombo.addActionListener(listener)
    }
}

// ── Factory helper ────────────────────────────────────────────────────────────

/**
 * Convenience factory.  Returns the appropriate option-widget for [field].
 * Delegates to [SingleSelectWidget], [MultiSelectWidget], or [CascadingSelectWidget]
 * based on the field schema.
 */
fun optionWidgetFor(field: TransitionField): FieldWidget {
    return when (field.schema) {
        is com.workflow.orchestrator.core.model.jira.FieldSchema.MultiSelect -> MultiSelectWidget(field)
        is com.workflow.orchestrator.core.model.jira.FieldSchema.CascadingSelect -> CascadingSelectWidget(field)
        else -> SingleSelectWidget(field)
    }
}
