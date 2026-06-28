package com.workflow.orchestrator.handover.settings

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.*
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.util.ProjectIdentifier
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.swing.JComponent

/**
 * Settings page: Tools > Workflow Orchestrator > Handover.
 *
 * Four sections:
 * 1. **Quick clipboard chips** — checkboxes for the full [PLACEHOLDER_CATALOG]; reads/writes
 *    [PluginSettings.State.quickClipboardChips].
 * 2. **Templates folders** — read-only paths for the global + project handover template dirs.
 * 3. **AI summaries** — toggle for [PluginSettings.State.aiSummariesEnabled].
 * 4. **Override audit** — live count of [com.workflow.orchestrator.core.events.WorkflowEvent.HandoverOverride]
 *    events in the last 30 days, read from [PluginSettings.State.handoverOverrideLog].
 *    Includes a Clear link.
 *
 * The override log is stored in [PluginSettings.State.handoverOverrideLog] (ISO-8601 timestamp
 * strings), written by `HandoverOverrideTracker` in `:handover`. This keeps `:core` free of
 * any cross-module dependency while allowing the configurable to display a live count.
 *
 * Registered in `plugin.xml` as a `projectConfigurable` with `parentId="workflow.orchestrator"`.
 */
class HandoverConfigurable(private val project: Project) : SearchableConfigurable {

    private val settings = PluginSettings.getInstance(project)
    private val state: PluginSettings.State get() = settings.state

    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    // Template directories (resolved once on construction from Project.basePath).
    private val globalDir: java.nio.file.Path = run {
        val home = java.nio.file.Path.of(System.getProperty("user.home"))
        home.resolve(".workflow-orchestrator/handover/templates")
    }
    private val projectDir: java.nio.file.Path = run {
        val basePath = project.basePath ?: System.getProperty("user.home")
        val home = java.nio.file.Path.of(System.getProperty("user.home"))
        home.resolve(".workflow-orchestrator/${ProjectIdentifier.compute(basePath)}/handover/templates")
    }

    // -----------------------------------------------------------------------
    // Configurable contract
    // -----------------------------------------------------------------------

    override fun getId(): String = "workflow.orchestrator.handover"
    override fun getDisplayName(): String = "Handover"

    override fun createComponent(): JComponent {
        val panel = panel {
            group("Quick clipboard chips") {
                row {
                    comment(
                        "Select which placeholder values appear as one-click chips in the " +
                            "Handover tab. Uncheck any chip you rarely use to keep the bar tidy."
                    )
                }
                for (key in PLACEHOLDER_CATALOG) {
                    row {
                        checkBox(key)
                            .bindSelected(
                                getter = { state.quickClipboardChips.contains(key) },
                                setter = { selected ->
                                    if (selected) {
                                        if (!state.quickClipboardChips.contains(key)) {
                                            state.quickClipboardChips.add(key)
                                        }
                                    } else {
                                        state.quickClipboardChips.remove(key)
                                    }
                                }
                            )
                    }
                }
            }

            group("Templates") {
                row("Global folder:") {
                    textField()
                        .text(globalDir.toString())
                        .applyToComponent { isEditable = false }
                        .columns(50)
                }
                row("Project folder:") {
                    textField()
                        .text(projectDir.toString())
                        .applyToComponent { isEditable = false }
                        .columns(50)
                }
                row {
                    link("Open global folder") {
                        globalDir.toFile().mkdirs()
                        RevealFileAction.openFile(globalDir.toFile())
                    }
                    link("Open project folder") {
                        projectDir.toFile().mkdirs()
                        RevealFileAction.openFile(projectDir.toFile())
                    }
                }
            }

            group("AI summaries") {
                row {
                    checkBox("Compute {ai.changeSummary} and {ai.ticketSummary}")
                        .bindSelected(
                            getter = { state.aiSummariesEnabled },
                            setter = { state.aiSummariesEnabled = it }
                        )
                }
                row {
                    comment(
                        "When enabled, the Handover tab sends a short LLM request to generate " +
                            "the change-summary and ticket-summary chip values. " +
                            "Disable to skip the LLM call and leave those chips blank."
                    )
                }
            }

            group("Override audit") {
                row {
                    val count = count30d()
                    label("$count handover override${if (count == 1) "" else "s"} in the last 30 days")
                    link("Clear") {
                        synchronized(state.handoverOverrideLog) {
                            state.handoverOverrideLog.clear()
                        }
                    }
                }
            }
        }

        dialogPanel = panel
        return panel
    }

    override fun isModified(): Boolean = dialogPanel?.isModified() ?: false

    override fun apply() {
        dialogPanel?.apply()
    }

    override fun reset() {
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Counts entries in [PluginSettings.State.handoverOverrideLog] that fall within the last
     * 30 days. Read-only — pruning is handled exclusively by `HandoverOverrideTracker.record`.
     * Access is guarded by `synchronized` on the log list to prevent
     * [java.util.ConcurrentModificationException] with the tracker's coroutine write path.
     */
    internal fun count30d(): Int {
        val log = state.handoverOverrideLog
        val cutoff = Instant.now().minus(30, ChronoUnit.DAYS)
        return synchronized(log) {
            log.count { entry ->
                runCatching { !Instant.parse(entry).isBefore(cutoff) }.getOrElse { false }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Companion
    // -----------------------------------------------------------------------

    companion object {
        /**
         * Canonical list of placeholder keys surfaced as Handover quick-clipboard chips.
         * This list is a superset of the 8-item default; users can trim it via settings.
         */
        val PLACEHOLDER_CATALOG = listOf(
            "ticket.id", "ticket.summary", "ticket.status",
            "pr.id", "pr.url",
            "build.url", "build.planKey", "build.number",
            "docker.tag", "docker.tagsJson",
            "automation.suiteTable", "automation.url",
            "ai.changeSummary", "ai.ticketSummary"
        )
    }
}
