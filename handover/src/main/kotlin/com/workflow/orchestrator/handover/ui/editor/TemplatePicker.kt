package com.workflow.orchestrator.handover.ui.editor

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.workflow.orchestrator.handover.model.HandoverTemplate
import com.workflow.orchestrator.handover.model.HandoverTemplateOrigin
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * A picker bar for selecting, duplicating, and deleting handover templates.
 *
 * Layout (left-to-right):
 *   [Combo] [Duplicate button] [Delete button] [● dirty dot]
 *
 * The combo always has real templates first, then the "+ New template…" sentinel.
 * PROJECT-origin templates are rendered with a ★ badge; BUNDLED items with a 🔒 prefix.
 * The dirty dot (●) is visible only when [setDirty] has been called with `true`.
 */
class TemplatePicker : JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)) {

    // ── Sealed item type ────────────────────────────────────────────────────

    private sealed class Item {
        data class Template(val template: HandoverTemplate) : Item()
        object Sentinel : Item()
    }

    // ── State ────────────────────────────────────────────────────────────────

    private var templates: List<HandoverTemplate> = emptyList()
    private var previousTemplateIndex: Int = -1
    private var ignoreEvents: Boolean = false

    // ── Callbacks ────────────────────────────────────────────────────────────

    var onSelectionChanged: ((HandoverTemplate) -> Unit)? = null
    var onCreateRequested: (() -> Unit)? = null
    var onDuplicateRequested: ((HandoverTemplate) -> Unit)? = null
    var onDeleteRequested: ((HandoverTemplate) -> Unit)? = null

    // ── Components ───────────────────────────────────────────────────────────

    private val comboModel = DefaultComboBoxModel<Item>()
    private val combo = JComboBox(comboModel).apply {
        renderer = TemplateRenderer()
    }

    private val duplicateBtn = JButton(AllIcons.Actions.Copy).apply {
        toolTipText = "Duplicate"
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
    }

    private val deleteBtn = JButton(AllIcons.General.Remove).apply {
        toolTipText = "Delete"
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
    }

    private val dirtyDot = JLabel("●").apply {
        foreground = JBColor(Color(216, 162, 60), Color(216, 162, 60))
        isVisible = false
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    init {
        add(combo)
        add(duplicateBtn)
        add(deleteBtn)
        add(dirtyDot)

        combo.addItemListener { e ->
            if (ignoreEvents) return@addItemListener
            if (e.stateChange != ItemEvent.SELECTED) return@addItemListener

            when (val selected = e.item as? Item) {
                is Item.Sentinel -> {
                    // Revert to the previous real template selection
                    ignoreEvents = true
                    try {
                        if (previousTemplateIndex >= 0 && previousTemplateIndex < comboModel.size) {
                            combo.selectedIndex = previousTemplateIndex
                        } else if (templates.isNotEmpty()) {
                            combo.selectedIndex = 0
                        }
                    } finally {
                        ignoreEvents = false
                    }
                    onCreateRequested?.invoke()
                }
                is Item.Template -> {
                    previousTemplateIndex = combo.selectedIndex
                    updateButtonStates()
                    onSelectionChanged?.invoke(selected.template)
                }
                null -> { /* no-op */ }
            }
        }

        duplicateBtn.addActionListener {
            currentSelection?.let { onDuplicateRequested?.invoke(it) }
        }

        deleteBtn.addActionListener {
            val sel = currentSelection ?: return@addActionListener
            if (!sel.isBundled) onDeleteRequested?.invoke(sel)
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Replace the dropdown items. The "+ New template…" sentinel is added internally.
     * Caller passes only real templates.
     */
    fun setTemplates(list: List<HandoverTemplate>) {
        ignoreEvents = true
        try {
            templates = list
            comboModel.removeAllElements()
            list.forEach { comboModel.addElement(Item.Template(it)) }
            comboModel.addElement(Item.Sentinel)

            if (list.isNotEmpty()) {
                combo.selectedIndex = 0
                previousTemplateIndex = 0
            } else {
                previousTemplateIndex = -1
            }
        } finally {
            ignoreEvents = false
        }
        updateButtonStates()
    }

    /** Current non-sentinel selection, or null if no real templates. */
    val currentSelection: HandoverTemplate?
        get() = (combo.selectedItem as? Item.Template)?.template

    /** Programmatic selection by id — useful for re-selecting after a list refresh. */
    fun select(id: String) {
        ignoreEvents = true
        try {
            for (i in 0 until comboModel.size) {
                val item = comboModel.getElementAt(i)
                if (item is Item.Template && item.template.id == id) {
                    combo.selectedIndex = i
                    previousTemplateIndex = i
                    break
                }
            }
        } finally {
            ignoreEvents = false
        }
        updateButtonStates()
    }

    /** Toggle the dirty ● indicator next to the picker. */
    fun setDirty(dirty: Boolean) {
        dirtyDot.isVisible = dirty
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun updateButtonStates() {
        val sel = currentSelection
        val hasSel = sel != null
        duplicateBtn.isEnabled = hasSel
        deleteBtn.isEnabled = hasSel && !(sel?.isBundled ?: true)
    }

    // ── Renderer ─────────────────────────────────────────────────────────────

    private inner class TemplateRenderer : ListCellRenderer<Item> {
        private val label = JLabel()

        override fun getListCellRendererComponent(
            list: JList<out Item>?,
            value: Item?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            label.text = when (value) {
                is Item.Template -> {
                    val t = value.template
                    val prefix = if (t.origin == HandoverTemplateOrigin.BUNDLED) "🔒 " else ""
                    val suffix = if (t.origin == HandoverTemplateOrigin.PROJECT) " ★" else ""
                    "$prefix${t.name}$suffix"
                }
                is Item.Sentinel -> "+ New template…"
                null -> ""
            }
            if (isSelected) {
                label.background = list?.selectionBackground
                label.foreground = list?.selectionForeground
                label.isOpaque = true
            } else {
                label.background = list?.background
                label.foreground = when (value) {
                    is Item.Sentinel -> JBColor.GRAY
                    else -> list?.foreground
                }
                label.isOpaque = false
            }
            return label
        }
    }
}
