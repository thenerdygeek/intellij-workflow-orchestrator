package com.workflow.orchestrator.agent.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.workflow.orchestrator.agent.memory.ArchivalMemory
import com.workflow.orchestrator.agent.memory.CoreMemory
import com.workflow.orchestrator.core.util.ProjectIdentifier
import javax.swing.JComponent

/**
 * "AI Agent → Memory" sub-page.
 *
 * Shows current memory contents, provides clear/edit operations, and toggles
 * auto-memory extraction. Auto-memory runs a cheap LLM extraction after
 * completed sessions to populate these stores; users may want to review or
 * clear what the system has saved.
 */
class AgentMemoryConfigurable(
    private val project: Project
) : SearchableConfigurable {

    private val settings = AgentSettings.getInstance(project)
    private var dialogPanel: DialogPanel? = null

    // Lazy-loaded memory instances for display and mutation
    private val agentDir by lazy {
        ProjectIdentifier.agentDir(project.basePath ?: System.getProperty("user.home"))
    }
    private val coreMemory by lazy { CoreMemory.forProject(agentDir) }
    private val archivalMemory by lazy { ArchivalMemory.forProject(agentDir) }

    // Mutable UI copies
    private var autoMemoryEnabled = settings.state.autoMemoryEnabled
    private var userBlock = ""
    private var projectBlock = ""
    private var patternsBlock = ""

    override fun getId(): String = "workflow.orchestrator.agent.memory"
    override fun getDisplayName(): String = "Memory"

    override fun createComponent(): JComponent {
        loadCurrentMemory()

        val innerPanel = panel {
            group("Auto-Memory") {
                row {
                    checkBox("Enable automatic memory extraction")
                        .bindSelected(::autoMemoryEnabled)
                        .comment(
                            "When enabled, the system automatically extracts insights from completed " +
                                "sessions using a cheap LLM (Haiku) and retrieves relevant memories at " +
                                "session start. Default: on."
                        )
                }
            }

            group("Core Memory") {
                row {
                    comment(
                        "Always-in-prompt working memory. Each block costs tokens on every LLM call, " +
                            "so keep them concise. Edited here and saved on Apply."
                    )
                }
                row("User:") {
                    textArea()
                        .rows(3)
                        .columns(60)
                        .bindText(::userBlock)
                        .comment("Role, expertise, preferences")
                }
                row("Project:") {
                    textArea()
                        .rows(3)
                        .columns(60)
                        .bindText(::projectBlock)
                        .comment("Active goals, decisions, blockers")
                }
                row("Patterns:") {
                    textArea()
                        .rows(3)
                        .columns(60)
                        .bindText(::patternsBlock)
                        .comment("Conventions, behavioral rules")
                }
                row {
                    button("Clear Core Memory") {
                        if (confirmClear("core memory")) {
                            clearCoreMemory()
                            loadCurrentMemory()
                            dialogPanel?.reset()
                        }
                    }
                }
            }

            group("Archival Memory") {
                row {
                    comment("Long-term searchable knowledge store (${archivalMemory.size()} entries)")
                }
                row {
                    button("Clear Archival Memory") {
                        if (confirmClear("archival memory (${archivalMemory.size()} entries)")) {
                            archivalMemory.clear()
                        }
                    }
                }
            }

            group("Danger Zone") {
                row {
                    button("Clear All Memory") {
                        if (confirmClear("ALL memory (core + archival)")) {
                            clearCoreMemory()
                            archivalMemory.clear()
                            loadCurrentMemory()
                            dialogPanel?.reset()
                        }
                    }
                }
                row {
                    comment("Removes all core and archival memory. Cannot be undone.")
                }
            }
        }
        dialogPanel = innerPanel
        return innerPanel
    }

    override fun isModified(): Boolean {
        dialogPanel?.apply()
        return autoMemoryEnabled != settings.state.autoMemoryEnabled ||
            userBlock != (coreMemory.read("user") ?: "") ||
            projectBlock != (coreMemory.read("project") ?: "") ||
            patternsBlock != (coreMemory.read("patterns") ?: "")
    }

    override fun apply() {
        dialogPanel?.apply()
        settings.state.autoMemoryEnabled = autoMemoryEnabled

        // Apply core memory edits — setBlock replaces the whole block
        applyCoreBlock("user", userBlock)
        applyCoreBlock("project", projectBlock)
        applyCoreBlock("patterns", patternsBlock)
    }

    override fun reset() {
        autoMemoryEnabled = settings.state.autoMemoryEnabled
        loadCurrentMemory()
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }

    private fun loadCurrentMemory() {
        userBlock = coreMemory.read("user") ?: ""
        projectBlock = coreMemory.read("project") ?: ""
        patternsBlock = coreMemory.read("patterns") ?: ""
    }

    private fun applyCoreBlock(label: String, newValue: String) {
        val current = coreMemory.read(label) ?: ""
        if (newValue != current) {
            coreMemory.setBlock(label, newValue)
        }
    }

    private fun clearCoreMemory() {
        coreMemory.setBlock("user", "")
        coreMemory.setBlock("project", "")
        coreMemory.setBlock("patterns", "")
    }

    private fun confirmClear(what: String): Boolean {
        return Messages.showYesNoDialog(
            project,
            "Are you sure you want to clear $what? This cannot be undone.",
            "Clear Memory",
            Messages.getWarningIcon()
        ) == Messages.YES
    }
}
