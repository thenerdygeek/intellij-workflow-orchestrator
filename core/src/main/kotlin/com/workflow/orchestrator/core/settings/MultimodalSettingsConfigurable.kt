package com.workflow.orchestrator.core.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selected
import javax.swing.JCheckBox
import javax.swing.JComponent

/**
 * Settings page rendered under
 *   File ▸ Settings ▸ Tools ▸ Workflow Orchestrator ▸ Multimodal
 *
 * Exposes the Phase 5 image-input controls persisted on
 * [PluginSettings.State]. UI idiom matches the project's other configurables
 * (Kotlin UI DSL `panel { ... }` + `bind*` accessors), so isModified/apply/reset
 * are wired by the framework.
 */
class MultimodalSettingsConfigurable(private val project: Project) : SearchableConfigurable {

    private val settings = PluginSettings.getInstance(project)
    private var dialogPanel: DialogPanel? = null

    override fun getId(): String = "workflow.orchestrator.multimodal"
    override fun getDisplayName(): String = "Multimodal"

    override fun createComponent(): JComponent {
        // Single source of truth for the master checkbox. Shared between the
        // "Image Input" group (where the visible control lives) and the
        // "Limits" group (whose sub-rows grey out via `enabledIf`) so we don't
        // need a hidden phantom checkbox duplicating the binding.
        var masterCell: Cell<JCheckBox>? = null
        val innerPanel = panel {
            group("Image Input") {
                row {
                    masterCell = checkBox("Enable visual support (images, view_image tool, multimodal /stream)")
                        .bindSelected(
                            { settings.state.enableImageInput },
                            { settings.state.enableImageInput = it }
                        )
                    cell()
                }
                row {
                    comment(
                        "When disabled, the agent runs text-only: view_image is removed from the LLM's " +
                            "tool list, image uploads in chat are blocked, and images already in session " +
                            "history are stripped from requests. Disable this if image handling is misbehaving."
                    )
                }
                row {
                    checkBox("Auto-load images from tool results")
                        .bindSelected(
                            { settings.state.enableToolImageAutoload },
                            { settings.state.enableToolImageAutoload = it }
                        )
                        .also { cell -> masterCell?.let { cell.enabledIf(it.component.selected) } }
                    cell()
                }
                row {
                    comment(
                        "When a tool (e.g. Jira download_attachment) returns an image, " +
                            "send it to the model as vision input — same path as paste/upload. " +
                            "Disable to keep tool images as opaque file references."
                    )
                }
            }

            group("Limits") {
                row("Maximum image size (bytes):") {
                    intTextField(range = 1..104_857_600)  // up to 100 MB sanity cap
                        .bindIntText(
                            { settings.state.imageMaxBytes.toInt().coerceAtLeast(1) },
                            { settings.state.imageMaxBytes = it.toLong() }
                        )
                        .also { cell -> masterCell?.let { cell.enabledIf(it.component.selected) } }
                    comment("Default 5_242_880 (5 MB).")
                }
                row("Maximum images per turn:") {
                    intTextField(range = 1..10)
                        .bindIntText(settings.state::imagesPerTurnCap)
                        .also { cell -> masterCell?.let { cell.enabledIf(it.component.selected) } }
                    comment("Mirrors Cody's per-turn cap. Default 2.")
                }
                row("Allowed MIME types:") {
                    textField()
                        .bindText(
                            { settings.state.imageMimeWhitelist.joinToString(",") },
                            { value ->
                                val parsed = value.split(",")
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                settings.state.imageMimeWhitelist.clear()
                                settings.state.imageMimeWhitelist.addAll(parsed)
                            }
                        )
                        .also { cell -> masterCell?.let { cell.enabledIf(it.component.selected) } }
                    comment(
                        "Comma-separated. Default: image/png, image/jpeg, image/webp. " +
                            "These are the formats verified to round-trip through Sourcegraph's " +
                            "vision endpoint; other formats (HEIC, HEIF, GIF, BMP, TIFF, AVIF, SVG) " +
                            "are rejected by the gateway."
                    )
                }
            }

            group("Token budget") {
                row("Token estimate per image:") {
                    intTextField(range = 1..50_000)
                        .bindIntText(settings.state::imageTokenEstimateDefault)
                    comment(
                        "Used for pre-send budget warnings only. The authoritative cost " +
                            "is reported by the model after the call returns."
                    )
                }
            }
        }
        dialogPanel = innerPanel
        return JBScrollPane(innerPanel).apply { border = null }
    }

    override fun isModified(): Boolean = dialogPanel?.isModified() ?: false

    override fun apply() {
        dialogPanel?.apply()
        // Multimodal-agent Phase 7 followup F-P5-2 / F-P6-1 — push fresh
        // settings into the running JCEF webview so the React `<InputBar>`
        // sees the new limits without a plugin restart. Reflective lookup
        // keeps `:core` from depending on `:agent` (cycle-free).
        notifyAgentControllerOfChange()
    }

    /**
     * Reflective notification: looks up `AgentControllerRegistry` (a project
     * service) and calls the controller's `pushImageSettingsToWebview()`.
     * Reflective so `:core` doesn't import `:agent` (`:agent → :core` is the
     * canonical direction; reversing would create a module cycle).
     *
     * Best-effort: silent failure when the agent tab isn't loaded or the
     * controller isn't yet wired (no-op is correct in that case — the user's
     * setting persists, and the next session pulls it via the page-ready
     * `_getImageSettings` query).
     */
    private fun notifyAgentControllerOfChange() {
        try {
            val registryClass = Class.forName("com.workflow.orchestrator.agent.ui.AgentControllerRegistry")
            val companion = registryClass.getField("Companion").get(null)
            val getInstance = companion.javaClass.getMethod("getInstance", com.intellij.openapi.project.Project::class.java)
            val registry = getInstance.invoke(companion, project) ?: return
            val getController = registry.javaClass.getMethod("getController")
            val controller = getController.invoke(registry) ?: return
            val pushMethod = controller.javaClass.getMethod("pushImageSettingsToWebview")
            pushMethod.invoke(controller)
        } catch (_: Throwable) {
            // Silent fallback — agent tab may not be loaded; the next page
            // load will pull fresh settings via `_getImageSettings`.
        }
    }

    override fun reset() {
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }
}
