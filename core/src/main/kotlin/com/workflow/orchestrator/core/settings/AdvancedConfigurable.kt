package com.workflow.orchestrator.core.settings

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*

class AdvancedConfigurable(private val project: Project) :
    BoundSearchableConfigurable("Advanced", "workflow.orchestrator.advanced") {

    override fun createPanel(): DialogPanel {
        val settings = PluginSettings.getInstance(project)

        return panel {
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

            group("Quality Thresholds") {
                row("High coverage — green (%):") {
                    textField()
                        .bindText(
                            { settings.state.coverageHighThreshold.toString() },
                            { settings.state.coverageHighThreshold = it.toFloatOrNull() ?: 80.0f }
                        )
                }
                row("Medium coverage — yellow (%):") {
                    textField()
                        .bindText(
                            { settings.state.coverageMediumThreshold.toString() },
                            { settings.state.coverageMediumThreshold = it.toFloatOrNull() ?: 50.0f }
                        )
                }
                row("SonarQube metrics:") {
                    textField()
                        .bindText(
                            { settings.state.sonarMetricKeys ?: "" },
                            { settings.state.sonarMetricKeys = it }
                        )
                        .comment("Comma-separated metric keys for API queries")
                }
            }

            group("Time Tracking") {
                row("Max hours per worklog:") {
                    textField()
                        .bindText(
                            { settings.state.maxWorklogHours.toString() },
                            { settings.state.maxWorklogHours = it.toFloatOrNull() ?: 7.0f }
                        )
                }
                row("Time increment (hours):") {
                    textField()
                        .bindText(
                            { settings.state.worklogIncrementHours.toString() },
                            { settings.state.worklogIncrementHours = it.toFloatOrNull() ?: 0.5f }
                        )
                }
            }

            group("Branching & PRs") {
                row("Max branch name length:") {
                    intTextField(range = 10..200)
                        .bindIntText(settings.state::branchMaxSummaryLength)
                }
                row("PR title format:") {
                    textField()
                        .bindText(
                            { settings.state.prTitleFormat ?: "" },
                            { settings.state.prTitleFormat = it }
                        )
                        .comment("Variables: {ticketId}, {summary}, {branch}")
                }
                row("Max PR title length:") {
                    intTextField(range = 20..300)
                        .bindIntText(settings.state::maxPrTitleLength)
                }
                row("Default reviewers:") {
                    textField()
                        .bindText(
                            { settings.state.prDefaultReviewers ?: "" },
                            { settings.state.prDefaultReviewers = it }
                        )
                        .comment("Comma-separated Bitbucket usernames")
                }
            }

            group("Cody AI") {
                row("Max diff lines for review:") {
                    intTextField(range = 100..100000)
                        .bindIntText(settings.state::maxDiffLinesForReview)
                }
            }

            group("Automation") {
                row("Tag history entries:") {
                    intTextField(range = 1..50)
                        .bindIntText(settings.state::tagHistoryMaxEntries)
                }
                row("Build variable name:") {
                    textField()
                        .bindText(
                            { settings.state.bambooBuildVariableName ?: "" },
                            { settings.state.bambooBuildVariableName = it }
                        )
                        .comment("Bamboo build variable containing Docker tag JSON")
                }
            }
        }
    }
}
