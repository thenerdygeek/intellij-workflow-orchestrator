package com.workflow.orchestrator.core.ui

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * Canonical width buckets for [bindBoundedWidth]. Widths are unscaled —
 * the helper passes them through `JBUI.scale` so HiDPI displays render
 * proportionally. Pick the bucket that matches your content's natural
 * length; the goal is "long enough that 80% of values fit, short enough
 * that the header doesn't overflow on a narrow tool window".
 */
object ComboBoxWidth {
    /** Short identifiers — severity, role, key prefix. */
    const val SHORT = 160

    /** Branch names, repo labels, Jira fields. */
    const val DEFAULT = 220

    /** Plan keys, board names, qualified file paths. */
    const val WIDE = 280
}

/**
 * Caps the on-screen width of a [JComboBox] so long values (branch names,
 * repo URLs, Jira field labels) don't push neighbouring components off
 * screen — a recurring layout bug when a wide combo lives in a header row
 * with `BorderLayout.WEST` / `BorderLayout.EAST` siblings.
 *
 * Behaviour:
 * - Closed combo and popup items truncate with Swing's built-in ellipsis
 *   when their content overflows the cell width.
 * - Each cell exposes the full value via [JComponent.setToolTipText], so
 *   the user can recover truncated text on hover.
 * - Any pre-existing custom renderer is preserved — this helper wraps it
 *   and only augments the tooltip.
 *
 * Call this AFTER any custom `setRenderer(...)`, otherwise the wrapping
 * is lost when the caller's renderer overwrites it.
 *
 * @param maxWidthPx unscaled width; passes through [JBUI.scale]. See
 *   [ComboBoxWidth] for canonical buckets.
 * @param textOf extractor for tooltip text. Defaults to [Any.toString];
 *   override when the item's `toString` is technical (e.g. data-class
 *   `toString` with all fields).
 */
fun <T> JComboBox<T>.bindBoundedWidth(
    maxWidthPx: Int = ComboBoxWidth.DEFAULT,
    textOf: (T?) -> String = { it?.toString().orEmpty() }
) {
    val w = JBUI.scale(maxWidthPx)
    val h = preferredSize.height
    preferredSize = Dimension(w, h)
    maximumSize = Dimension(w, h)

    @Suppress("UNCHECKED_CAST")
    val base = (renderer ?: DefaultListCellRenderer()) as ListCellRenderer<Any?>
    renderer = object : ListCellRenderer<Any?> {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val c = base.getListCellRendererComponent(
                list, value, index, isSelected, cellHasFocus
            )
            @Suppress("UNCHECKED_CAST")
            val full = textOf(value as T?)
            if (c is JComponent) c.toolTipText = full.takeIf { it.isNotBlank() }
            return c
        }
    }
}
