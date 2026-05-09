package com.workflow.orchestrator.agent.ui.tooldocs

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.tools.AgentTool
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList

/**
 * Top-level entry to the tool documentation system.
 *
 * Tools → View Tool Documentation opens a quick picker listing every registered
 * tool that has a `documentation()` block, ordered Core-first then alphabetical.
 * On select, opens [ToolDocsEditor] for the chosen tool. Two-click access from
 * the menu bar; complements the per-row (i) / double-click affordances in the
 * Agent Tool Testing panel.
 *
 * Tools without `documentation()` are excluded from the picker so the user
 * cannot select a "no docs yet" target — the testing panel's ⓘ glyph remains
 * the source of truth for "is this documented?".
 */
class OpenToolDocsAction :
    AnAction("View Tool Documentation", "Browse rich per-tool documentation", null),
    DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.getService(AgentService::class.java) ?: run {
            // Agent disabled — surface a no-op popup instead of silently doing nothing.
            JBPopupFactory.getInstance()
                .createMessage("Agent service unavailable — is the agent enabled in settings?")
                .showCenteredInCurrentWindow(project)
            return
        }

        val entries = collectDocumentedTools(service)
        if (entries.isEmpty()) {
            JBPopupFactory.getInstance()
                .createMessage("No tools have documentation() blocks yet.")
                .showCenteredInCurrentWindow(project)
            return
        }

        val step = object : BaseListPopupStep<Entry>("Tool Documentation", entries) {
            override fun getTextFor(value: Entry): String = value.tool.name
            override fun isSpeedSearchEnabled(): Boolean = true

            override fun onChosen(selectedValue: Entry?, finalChoice: Boolean): PopupStep<*>? {
                if (selectedValue != null) ToolDocsEditor.open(project, selectedValue.tool.name)
                return PopupStep.FINAL_CHOICE
            }
        }

        val popup = JBPopupFactory.getInstance().createListPopup(step)
        // Replace the default cell renderer so we get the tier badge inline.
        @Suppress("UNCHECKED_CAST")
        (popup as? com.intellij.ui.popup.list.ListPopupImpl)?.list?.cellRenderer = EntryRenderer()
        popup.showCenteredInCurrentWindow(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private data class Entry(val tool: AgentTool, val tier: String)

    private fun collectDocumentedTools(service: AgentService): List<Entry> {
        val activeNames = service.registry.getActiveTools().keys
        val deferredNames = service.registry.getDeferredCatalog().map { it.first }.toSet()
        return service.registry.allTools()
            .filter { it.documentation() != null }
            .map { tool ->
                val tier = when {
                    tool.name in activeNames && tool.name !in deferredNames -> "Core"
                    tool.name in activeNames && tool.name in deferredNames -> "Active-deferred"
                    else -> "Deferred"
                }
                Entry(tool, tier)
            }
            .sortedWith(compareBy({ if (it.tier == "Core") 0 else 1 }, { it.tool.name }))
    }

    private class EntryRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val entry = value as? Entry ?: return this
            border = JBUI.Borders.empty(2, 6)
            val tierColor = when (entry.tier) {
                "Core" -> "#6cb0e0"
                "Active-deferred" -> "#d2a8ff"
                else -> "#9ba8b4"
            }
            text = "<html><b>${entry.tool.name}</b> <font color='$tierColor'>[${entry.tier}]</font></html>"
            font = UIUtil.getLabelFont()
            return this
        }
    }
}
