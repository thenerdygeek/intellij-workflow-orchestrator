package com.workflow.orchestrator.pullrequest.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.util.TicketKeyExtractor
import com.workflow.orchestrator.core.workflow.JiraTicketProvider
import com.workflow.orchestrator.core.workflow.TicketContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.DataFlavor
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.Timer
import javax.swing.border.Border
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter
import javax.swing.text.PlainDocument

/**
 * A chip-based ticket input widget for the PR creation flow.
 *
 * Lets the user associate up to 5 Jira ticket keys with a pull request. Keys are
 * committed on Enter / Tab / comma / whitespace and validated asynchronously via
 * [JiraTicketProvider.getTicketContext]. Each chip renders its validation status
 * (pending / valid / not-found / network-error) with an icon + tooltip.
 *
 * The first chip (index 0) is the "primary" ticket — it is drawn bold and tagged
 * with a small PRIMARY label. A right-click menu on a non-primary chip lets the
 * user promote it. If the primary chip resolves to NOT_FOUND and any other chip
 * is VALID, the primary is auto-promoted to the first valid chip.
 *
 * Phase 4 of the PR Creation Redesign. Intentionally self-contained: not wired
 * into [com.workflow.orchestrator.bamboo.ui.CreatePrDialog] until Phase 5.
 */
class TicketChipInput(
    private val project: Project,
    private val scope: CoroutineScope,
    private val initialKeys: List<String> = emptyList(),
    private val initialContexts: Map<String, TicketContext> = emptyMap(),
    private val onChange: (snapshot: Snapshot) -> Unit = {},
    /**
     * Optional test seam. When non-null the widget uses this provider instead of
     * the `JiraTicketProvider.getInstance()` extension point lookup. Mirrors the
     * `testClient` pattern in [com.workflow.orchestrator.jira.service.JiraTicketProviderImpl].
     */
    private val testProvider: JiraTicketProvider? = null,
    /**
     * Test seam: UI re-render dispatcher. Defaults to Swing EDT via `invokeLater`.
     * Tests pass `Runnable::run` to drive the widget synchronously.
     */
    private val uiDispatcher: (Runnable) -> Unit = { invokeLater { it.run() } },
    /**
     * Test seam: coroutine context for the network fetch. Defaults to
     * [Dispatchers.IO]; tests pass [kotlin.coroutines.EmptyCoroutineContext] so
     * the resolve runs in-line on the test scope.
     */
    private val ioContext: kotlin.coroutines.CoroutineContext = Dispatchers.IO
) : JPanel(), Disposable {

    data class Snapshot(
        val chips: List<Chip>,
        val validChips: List<Chip>,
        val primary: Chip?
    )

    data class Chip(
        val key: String,
        val status: Status,
        val context: TicketContext? = null
    ) {
        enum class Status { PENDING, VALID, NOT_FOUND, NETWORK_ERROR }
    }

    // ─── State ──────────────────────────────────────────────────────────────────

    private val chips = mutableListOf<Chip>()

    /** Tracks live [AnimatedIcon.Default] instances for chips in PENDING state.
     *  Keyed by ticket key. Entries are removed (and thus eligible for GC) when the
     *  chip transitions out of PENDING or when the widget is disposed, preventing
     *  accumulated dead timer-backed icon instances across render cycles. */
    private val pendingIcons: MutableMap<String, AnimatedIcon.Default> = mutableMapOf()

    /** Guards onChange firing during the init-time addKey loop. */
    private var initialized = false

    // ─── UI components ──────────────────────────────────────────────────────────

    private val chipsPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply {
        isOpaque = false
    }

    private val inputField = JBTextField(15).apply {
        toolTipText = INPUT_PLACEHOLDER
        // Simple placeholder via putClientProperty (IntelliJ platform recognises this)
        putClientProperty("StatusVisibleFunction", java.util.function.BooleanSupplier { text.isEmpty() })
        putClientProperty("JTextField.placeholderText", INPUT_PLACEHOLDER)
    }

    private val defaultInputBorder: Border = inputField.border

    // ─── Init ───────────────────────────────────────────────────────────────────

    init {
        layout = BorderLayout()
        isOpaque = false

        installDocumentFilter()
        installKeyHandler()
        installPasteHandler()
        installFocusReset()

        chipsPanel.add(inputField)
        add(chipsPanel, BorderLayout.CENTER)

        // Pre-seed from constructor args. onChange is suppressed during this loop
        // via the `initialized` guard; a single synthetic call fires afterward so
        // callers receive one stable snapshot rather than per-key intermediate states.
        initialKeys.forEach { addKey(it) }
        initialized = true
        fireChange()
    }

    override fun dispose() {
        // AnimatedIcon.Default does not implement Disposable in the platform version
        // this plugin targets; clearing the map releases all references so the
        // timer-backed instances can be GC'd and their animations stop naturally.
        pendingIcons.clear()
    }

    // ─── Public API ─────────────────────────────────────────────────────────────

    fun validPrimary(): TicketContext? {
        val primary = chips.firstOrNull() ?: return null
        return if (primary.status == Chip.Status.VALID) primary.context else null
    }

    fun allValid(): List<TicketContext> =
        chips.filter { it.status == Chip.Status.VALID }.mapNotNull { it.context }

    fun allChips(): List<Chip> = chips.toList()

    /**
     * Adds a ticket key programmatically. Dedupes against existing chips. If
     * [initialContexts] contains a pre-resolved entry for the key, the chip is
     * created directly in the VALID state without firing an async fetch.
     */
    fun addKey(key: String) {
        val normalized = key.trim().uppercase()
        if (normalized.isEmpty()) return
        if (!TicketKeyExtractor.isValidKey(normalized)) return
        if (chips.any { it.key == normalized }) return
        if (chips.size >= MAX_CHIPS) return

        val cached = initialContexts[normalized]
        if (cached != null) {
            chips.add(Chip(normalized, Chip.Status.VALID, cached))
            renderChips()
            fireChange()
            return
        }

        chips.add(Chip(normalized, Chip.Status.PENDING))
        renderChips()
        fireChange()
        resolveChipAsync(normalized)
    }

    // ─── Internal: commit / remove / promote ────────────────────────────────────

    private fun commitInput(): Boolean {
        val raw = inputField.text.trim()
        if (raw.isEmpty()) return false

        val normalized = raw.uppercase()
        if (!TicketKeyExtractor.isValidKey(normalized)) {
            flashInvalid()
            return false
        }
        if (chips.any { it.key == normalized }) {
            inputField.text = ""
            return false
        }
        if (chips.size >= MAX_CHIPS) {
            return false
        }

        inputField.text = ""
        chips.add(Chip(normalized, Chip.Status.PENDING))
        renderChips()
        fireChange()
        resolveChipAsync(normalized)
        return true
    }

    private fun removeChip(key: String) {
        val wasPrimary = chips.firstOrNull()?.key == key
        val removed = chips.removeAll { it.key == key }
        if (!removed) return
        pendingIcons.remove(key) // release reference so the icon can be GC'd
        if (wasPrimary) maybeAutoPromote()
        renderChips()
        fireChange()
    }

    private fun setAsPrimary(key: String) {
        val idx = chips.indexOfFirst { it.key == key }
        if (idx <= 0) return
        val chip = chips.removeAt(idx)
        chips.add(0, chip)
        renderChips()
        fireChange()
    }

    private fun maybeAutoPromote() {
        val current = chips.firstOrNull() ?: return
        if (current.status != Chip.Status.NOT_FOUND) return
        val firstValidIdx = chips.indexOfFirst { it.status == Chip.Status.VALID }
        if (firstValidIdx <= 0) return
        // Simple swap: valid chip takes position 0, NOT_FOUND slides into valid's old slot.
        val valid = chips[firstValidIdx]
        chips[0] = valid
        chips[firstValidIdx] = current
    }

    // ─── Internal: async resolve ───────────────────────────────────────────────

    private fun resolveChipAsync(key: String) {
        scope.launch {
            val result = runCatching {
                withContext(ioContext) {
                    val provider = testProvider ?: JiraTicketProvider.getInstance()
                    provider?.getTicketContext(key)
                }
            }
            uiDispatcher {
                val idx = chips.indexOfFirst { it.key == key }
                if (idx < 0) return@uiDispatcher // removed while resolving

                // Chip is transitioning out of PENDING — release the animated icon reference.
                pendingIcons.remove(key)

                val context = result.getOrNull()
                val newChip = when {
                    result.isFailure -> Chip(key, Chip.Status.NETWORK_ERROR)
                    context == null -> Chip(key, Chip.Status.NOT_FOUND)
                    else -> Chip(key, Chip.Status.VALID, context)
                }
                chips[idx] = newChip

                // Auto-promote if:
                //   (a) the primary itself just resolved to NOT_FOUND, OR
                //   (b) a later chip just resolved to VALID while the primary is NOT_FOUND.
                // Both reduce to "primary is NOT_FOUND ∧ some later chip is VALID".
                maybeAutoPromote()
                renderChips()
                fireChange()
            }
        }
    }

    // ─── Internal: rendering ────────────────────────────────────────────────────

    private fun renderChips() {
        chipsPanel.removeAll()
        chips.forEachIndexed { idx, chip ->
            chipsPanel.add(buildChipComponent(chip, isPrimary = idx == 0))
        }
        chipsPanel.add(inputField)
        updateInputEnabled()
        chipsPanel.revalidate()
        chipsPanel.repaint()
    }

    private fun updateInputEnabled() {
        if (chips.size >= MAX_CHIPS) {
            inputField.isEnabled = false
            inputField.toolTipText = MAX_TOOLTIP
        } else {
            inputField.isEnabled = true
            inputField.toolTipText = INPUT_PLACEHOLDER
        }
    }

    private fun buildChipComponent(chip: Chip, isPrimary: Boolean): JPanel {
        // Ticket chip intentionally diverges from the reviewer-chip pattern in PrDetailPanel:
        // uses CARD_BG background + compound border + grey ✕ for richer visual weight (status
        // icon + bold-for-primary + PRIMARY tag need more affordance than a flat user chip).
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            background = StatusColors.CARD_BG
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(StatusColors.BORDER),
                JBUI.Borders.empty(1, 6)
            )
            toolTipText = tooltipFor(chip)
        }

        // Status icon (leftmost).
        panel.add(buildStatusLabel(chip))

        // Ticket key label — bold for primary.
        panel.add(JBLabel(chip.key).apply {
            font = if (isPrimary) font.deriveFont(Font.BOLD, 11f) else font.deriveFont(11f)
        })

        if (isPrimary) {
            panel.add(JBLabel("PRIMARY").apply {
                font = font.deriveFont(font.size2D - 2f)
                foreground = StatusColors.SECONDARY_TEXT
            })
        }

        // Remove button.
        val removeBtn = JBLabel("✕").apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            foreground = StatusColors.SECONDARY_TEXT
            toolTipText = "Remove"
        }
        removeBtn.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                removeChip(chip.key)
            }
        })
        panel.add(removeBtn)

        // Right-click → Set as primary (only for non-primary chips).
        panel.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)

            private fun maybeShowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val popup = JPopupMenu()
                if (isPrimary) {
                    popup.add(JMenuItem("Already primary").apply { isEnabled = false })
                } else {
                    val item = JMenuItem("Set as primary")
                    item.addActionListener { setAsPrimary(chip.key) }
                    popup.add(item)
                }
                popup.show(e.component, e.x, e.y)
            }
        })

        return panel
    }

    /** Returns the icon for [chip]'s current status.
     *  For PENDING, reuses an existing [AnimatedIcon.Default] from [pendingIcons]
     *  (or creates and stores one) so every render cycle holds the same timer instance
     *  rather than creating a new one on every [renderChips] call. */
    private fun statusIconFor(chip: Chip): javax.swing.Icon = when (chip.status) {
        Chip.Status.PENDING -> pendingIcons.getOrPut(chip.key) { AnimatedIcon.Default() }
        Chip.Status.VALID -> AllIcons.General.InspectionsOK
        Chip.Status.NOT_FOUND -> AllIcons.General.Error
        Chip.Status.NETWORK_ERROR -> AllIcons.General.Warning
    }

    private fun buildStatusLabel(chip: Chip): JBLabel {
        val label = JBLabel()
        label.icon = statusIconFor(chip)
        label.text = ""
        return label
    }

    private fun tooltipFor(chip: Chip): String = when (chip.status) {
        Chip.Status.PENDING -> "Checking Jira..."
        Chip.Status.VALID -> chip.context?.summary ?: chip.key
        Chip.Status.NOT_FOUND -> "Ticket not found in Jira"
        Chip.Status.NETWORK_ERROR -> "Could not reach Jira"
    }

    // ─── Input wiring ───────────────────────────────────────────────────────────

    private fun installDocumentFilter() {
        val doc = inputField.document as? PlainDocument ?: return
        doc.setDocumentFilter(object : DocumentFilter() {
            override fun insertString(fb: FilterBypass, offset: Int, string: String?, attr: AttributeSet?) {
                super.insertString(fb, offset, string?.uppercase(), attr)
            }

            override fun replace(
                fb: FilterBypass, offset: Int, length: Int, text: String?, attrs: AttributeSet?
            ) {
                super.replace(fb, offset, length, text?.uppercase(), attrs)
            }
        })
    }

    private fun installKeyHandler() {
        inputField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                        if (commitInput()) e.consume()
                    }
                }
            }

            override fun keyTyped(e: KeyEvent) {
                // Comma or space also commit. Enter/Tab are handled in keyPressed.
                val ch = e.keyChar
                if (ch == ',' || ch == ' ') {
                    // Don't let the separator itself land in the field.
                    e.consume()
                    commitInput()
                }
            }
        })
    }

    private fun installPasteHandler() {
        // Override paste via a custom TransferHandler so we can split.
        inputField.transferHandler = object : javax.swing.TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean =
                support.isDataFlavorSupported(DataFlavor.stringFlavor)

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                val pasted = try {
                    support.transferable.getTransferData(DataFlavor.stringFlavor) as? String ?: return false
                } catch (_: Exception) {
                    return false
                }
                handlePastedText(pasted)
                return true
            }
        }
    }

    private fun handlePastedText(text: String) {
        if (text.isBlank()) return
        // If the pasted chunk contains ANY separator, split; otherwise let it land in the field.
        val hasSeparator = text.any { it == ',' || it.isWhitespace() }
        if (!hasSeparator) {
            inputField.text = (inputField.text + text).uppercase()
            return
        }
        val tokens = text.split(',', ' ', '\t', '\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        tokens.forEach { token ->
            val normalized = token.uppercase()
            if (!TicketKeyExtractor.isValidKey(normalized)) return@forEach
            if (chips.any { it.key == normalized }) return@forEach
            if (chips.size >= MAX_CHIPS) return
            chips.add(Chip(normalized, Chip.Status.PENDING))
            resolveChipAsync(normalized)
        }
        renderChips()
        fireChange()
    }

    private fun installFocusReset() {
        inputField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) {
                // On blur with pending text, try to commit silently; ignore failure.
                if (inputField.text.isNotBlank()) commitInput()
            }
        })
    }

    private fun flashInvalid() {
        inputField.border = BorderFactory.createLineBorder(JBColor.RED, 1)
        val timer = Timer(INVALID_FLASH_MS) { inputField.border = defaultInputBorder }
        timer.isRepeats = false
        timer.start()
    }

    // ─── Snapshot / change broadcast ───────────────────────────────────────────

    private fun snapshot(): Snapshot = Snapshot(
        chips = chips.toList(),
        validChips = chips.filter { it.status == Chip.Status.VALID },
        primary = chips.firstOrNull()
    )

    private fun fireChange() {
        if (!initialized) return
        onChange(snapshot())
    }

    // Test seams — keep internal visibility so tests in the same module can drive the widget.
    internal fun alignmentComponent(): Component = chipsPanel
    internal fun inputFieldForTest(): JBTextField = inputField
    internal fun commitCurrentInputForTest(): Boolean = commitInput()
    internal fun handlePastedTextForTest(text: String) = handlePastedText(text)
    internal fun setAsPrimaryForTest(key: String) = setAsPrimary(key)
    internal fun removeChipForTest(key: String) = removeChip(key)
    internal fun pendingIconsForTest(): Map<String, AnimatedIcon.Default> = pendingIcons

    companion object {
        const val MAX_CHIPS = 5
        private const val INPUT_PLACEHOLDER = "Type key and press Enter…"
        private const val MAX_TOOLTIP = "Max 5 tickets — remove one to add another"
        private const val INVALID_FLASH_MS = 800
    }
}
