package com.workflow.orchestrator.core.settings

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*

/**
 * Settings page for Cody AI configuration, network timeouts, and Jira custom fields.
 * Extracted from AdvancedConfigurable as part of the settings UI refactor (7 pages -> 4).
 */
class AiAdvancedConfigurable(private val project: Project) :
    BoundSearchableConfigurable("AI & Advanced", "workflow.orchestrator.ai_advanced") {

    override fun createPanel(): DialogPanel {
        val settings = PluginSettings.getInstance(project)

        return panel {
            group("Cody AI") {
                row("Max diff lines for review:") {
                    intTextField(range = 100..100000)
                        .bindIntText(settings.state::maxDiffLinesForReview)
                }
            }

            group("Network") {
                row("Connect timeout (seconds):") {
                    intTextField(range = 1..300)
                        .bindIntText(settings.state::httpConnectTimeoutSeconds)
                }
                row("Read timeout (seconds):") {
                    intTextField(range = 1..600)
                        .bindIntText(settings.state::httpReadTimeoutSeconds)
                }
            }

            group("Jira Custom Fields") {
                row("Epic link field ID:") {
                    textField()
                        .bindText(
                            { settings.state.epicLinkFieldId ?: "" },
                            { settings.state.epicLinkFieldId = it }
                        )
                        .comment("e.g., customfield_10014")
                }
                row("Reviewer field ID:") {
                    textField()
                        .bindText(
                            { settings.state.reviewerFieldId ?: "" },
                            { settings.state.reviewerFieldId = it }
                        )
                        .comment("e.g., customfield_10050 (leave blank if not used)")
                }
                row("Tester field ID:") {
                    textField()
                        .bindText(
                            { settings.state.testerFieldId ?: "" },
                            { settings.state.testerFieldId = it }
                        )
                        .comment("e.g., customfield_10051 (leave blank if not used)")
                }
            }
        }
    }
}
