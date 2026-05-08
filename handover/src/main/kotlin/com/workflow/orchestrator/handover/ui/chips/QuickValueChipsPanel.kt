package com.workflow.orchestrator.handover.ui.chips

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.ui.ClipboardUtil
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.handover.model.HandoverPlaceholderValue
import com.workflow.orchestrator.handover.model.HandoverTemplateAction
import com.workflow.orchestrator.handover.service.HandoverPlaceholderResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * A panel that shows a grid of quick-copy chips for common handover placeholder values.
 *
 * Each chip displays the placeholder key (uppercase) and its resolved value. Clicking a chip
 * copies the value to the clipboard and emits [WorkflowEvent.HandoverChipCopied].
 *
 * Position: bottom of the Share tab, below the Jira and Email editor cards.
 *
 * @param onEdt   function that schedules [Runnable] on the EDT; defaults to [SwingUtilities.invokeLater];
 *                inject [Runnable::run] in tests to make it synchronous.
 */
class QuickValueChipsPanel private constructor(
    private val project: Project?,
    private val cs: CoroutineScope,
    private val resolver: HandoverPlaceholderResolver,
    private val eventBus: EventBus,
    private val testChipKeys: (() -> List<String>)?,
    private val onEdt: (Runnable) -> Unit,
    private val ioDispatcher: CoroutineDispatcher,
) : JPanel(BorderLayout()), Disposable {

    /**
     * Primary constructor for production use via IntelliJ DI.
     */
    constructor(project: Project, cs: CoroutineScope) : this(
        project = project,
        cs = cs,
        resolver = project.getService(HandoverPlaceholderResolver::class.java),
        eventBus = project.getService(EventBus::class.java),
        testChipKeys = null,
        onEdt = SwingUtilities::invokeLater,
        ioDispatcher = Dispatchers.IO,
    )

    /** Track current chip views for @TestOnly accessors. */
    internal val chips: MutableList<ChipView> = mutableListOf()

    private val chipGrid = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))

    init {
        background = null
        isOpaque = false
        chipGrid.background = null
        chipGrid.isOpaque = false

        add(buildHeaderRow(), BorderLayout.NORTH)
        add(chipGrid, BorderLayout.CENTER)

        refresh()
    }

    private fun buildHeaderRow(): JPanel {
        val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            background = null
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)
        }
        val title = JBLabel("QUICK VALUES").apply {
            foreground = StatusColors.SECONDARY_TEXT
            font = font.deriveFont(font.size2D - 1f)
            border = JBUI.Borders.emptyRight(8)
        }
        val settingsLink = JBLabel("<html><a style='color:#1A73E8'>customise in Settings</a></html>").apply {
            toolTipText = "Configure which placeholders show as chips (Settings → Handover)"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (project != null) {
                        try {
                            ShowSettingsUtil.getInstance().showSettingsDialog(
                                project,
                                "com.workflow.orchestrator.plugin.handover.settings",
                            )
                        } catch (_: Exception) {
                            // Settings page not yet registered — T25 wires this up.
                        }
                    }
                }
            })
        }
        row.add(title)
        row.add(Box.createHorizontalGlue())
        row.add(settingsLink)
        return row
    }

    /** Re-resolve and re-render chips. Call from the parent on state changes. */
    fun refresh() {
        val keys = chipKeys()
        cs.launch(ioDispatcher) {
            val resolved: List<Pair<String, HandoverPlaceholderValue>> = keys.map { key ->
                key to try {
                    resolver.resolve(key, HandoverTemplateAction.JIRA)
                } catch (_: Exception) {
                    HandoverPlaceholderValue.unavailable("resolution error")
                }
            }
            onEdt(Runnable { rebuildChips(resolved) })
        }
    }

    private fun rebuildChips(resolved: List<Pair<String, HandoverPlaceholderValue>>) {
        chipGrid.removeAll()
        chips.clear()
        for ((key, value) in resolved) {
            val chip = ChipView(key = key, placeholderValue = value, eventBus = eventBus, cs = cs, onEdt = onEdt)
            chips.add(chip)
            chipGrid.add(chip)
        }
        chipGrid.revalidate()
        chipGrid.repaint()
    }

    private fun chipKeys(): List<String> =
        if (testChipKeys != null) testChipKeys.invoke()
        else project?.let { PluginSettings.getInstance(it).state.quickClipboardChips.toList() }
            ?: emptyList()

    override fun dispose() {
        // CoroutineScope is owned by the parent; nothing to dispose here.
    }

    // region TestOnly accessors

    @TestOnly
    fun testGetChipKeys(): List<String> = chips.map { it.key }

    @TestOnly
    fun testGetChipValueLabels(): List<String> = chips.map { it.valueLabel.text }

    @TestOnly
    fun testClickChip(key: String) {
        chips.first { it.key == key }.simulateClick()
    }

    // endregion

    companion object {
        @TestOnly
        fun forTest(
            resolver: HandoverPlaceholderResolver,
            eventBus: EventBus,
            chipKeys: () -> List<String>,
            cs: CoroutineScope,
        ): QuickValueChipsPanel = QuickValueChipsPanel(
            project = null,
            cs = cs,
            resolver = resolver,
            eventBus = eventBus,
            testChipKeys = chipKeys,
            onEdt = Runnable::run,      // synchronous in tests — no Swing event queue needed
            ioDispatcher = Dispatchers.Unconfined, // runs eagerly on calling thread, no real IO threads
        )
    }
}

/**
 * A single chip view showing one placeholder key → resolved value pair.
 */
internal class ChipView(
    val key: String,
    private val placeholderValue: HandoverPlaceholderValue,
    private val eventBus: EventBus,
    private val cs: CoroutineScope,
    private val onEdt: (Runnable) -> Unit = SwingUtilities::invokeLater,
) : JPanel(BorderLayout(4, 0)) {

    val valueLabel: JBLabel

    private val displayValue: String = if (placeholderValue.isAvailable) {
        val v = placeholderValue.value
        if (v.length > 60) v.take(57) + "…" else v
    } else {
        "—" // em-dash
    }

    private var hovered = false
    private var flashActive = false
    private var flashColor: Color? = null

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(2, 2, 2, 2),
            JBUI.Borders.empty(4, 10),
        )

        val keyLabel = JBLabel(key.uppercase()).apply {
            foreground = StatusColors.SECONDARY_TEXT
            font = font.deriveFont(Font.BOLD, font.size2D - 1f)
        }

        valueLabel = JBLabel(displayValue).apply {
            font = Font(Font.MONOSPACED, font.style, font.size)
            toolTipText = if (!placeholderValue.isAvailable) {
                placeholderValue.unavailableReason
            } else {
                placeholderValue.value
            }
        }

        val copyIcon = JBLabel(AllIcons.Actions.Copy).apply {
            border = JBUI.Borders.emptyLeft(4)
        }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(keyLabel)
            add(Box.createHorizontalStrut(6))
            add(valueLabel)
            add(Box.createHorizontalStrut(4))
            add(copyIcon)
        }
        add(content, BorderLayout.CENTER)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true; repaint()
            }
            override fun mouseExited(e: MouseEvent) {
                hovered = false; repaint()
            }
            override fun mouseClicked(e: MouseEvent) {
                simulateClick()
            }
        })
    }

    fun simulateClick() {
        val value = if (placeholderValue.isAvailable) placeholderValue.value else ""
        try {
            ClipboardUtil.copyToClipboard(value)
        } catch (_: Exception) {
            // Clipboard not available in headless/test environments — safe to ignore.
        }
        cs.launch {
            // Flash the chip background, then emit the event.
            onEdt(Runnable {
                flashColor = StatusColors.HIGHLIGHT_BG
                flashActive = true
                repaint()
            })
            delay(200)
            onEdt(Runnable {
                flashActive = false
                flashColor = null
                repaint()
            })
            eventBus.emit(WorkflowEvent.HandoverChipCopied(key))
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val bg: Color = when {
            flashActive && flashColor != null -> flashColor!!
            hovered -> StatusColors.HIGHLIGHT_BG
            else -> StatusColors.CARD_BG
        }
        g2.color = bg
        g2.fillRoundRect(0, 0, width, height, 8, 8)
        g2.color = if (hovered) StatusColors.LINK else StatusColors.BORDER
        g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)
        g2.dispose()
        super.paintComponent(g)
    }
}
