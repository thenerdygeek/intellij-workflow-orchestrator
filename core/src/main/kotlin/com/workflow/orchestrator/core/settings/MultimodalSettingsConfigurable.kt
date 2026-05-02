package com.workflow.orchestrator.core.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
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
        val innerPanel = panel {
            group("Image Input") {
                row {
                    checkBox("Enable image input")
                        .bindSelected(
                            { settings.state.enableImageInput },
                            { settings.state.enableImageInput = it }
                        )
                    cell()
                }
                row {
                    comment(
                        "When disabled, the paperclip menu hides the image action and " +
                            "paste/drag-drop reject image content."
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
                    comment("Default 5_242_880 (5 MB).")
                }
                row("Maximum images per turn:") {
                    intTextField(range = 1..10)
                        .bindIntText(settings.state::imagesPerTurnCap)
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
                    comment(
                        "Comma-separated. Default: image/png, image/jpeg, image/webp, " +
                            "image/heic, image/heif."
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
    }

    override fun reset() {
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }
}
