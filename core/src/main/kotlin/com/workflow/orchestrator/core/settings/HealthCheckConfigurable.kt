package com.workflow.orchestrator.core.settings

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.*

class HealthCheckConfigurable(
    private val project: Project
) : BoundSearchableConfigurable("Health Check", "workflow.orchestrator.healthcheck") {

    private val settings = PluginSettings.getInstance(project)

    override fun createPanel() = panel {
        group("Health Check Gate") {
            row {
                checkBox("Enable health checks on commit")
                    .bindSelected(settings.state::healthCheckEnabled)
            }
            row("Blocking mode:") {
                comboBox(listOf("hard", "soft", "off"))
                    .bindItem(
                        getter = { settings.state.healthCheckBlockingMode },
                        setter = { settings.state.healthCheckBlockingMode = it ?: "soft" }
                    )
                    .comment("hard = block commit, soft = warn only, off = disabled")
            }
            group("Checks") {
                row {
                    checkBox("Maven compile")
                        .bindSelected(settings.state::healthCheckCompileEnabled)
                }
                row {
                    checkBox("Maven test")
                        .bindSelected(settings.state::healthCheckTestEnabled)
                }
                row {
                    checkBox("Copyright headers")
                        .bindSelected(settings.state::healthCheckCopyrightEnabled)
                }
                row {
                    checkBox("Sonar quality gate (uses cached status)")
                        .bindSelected(settings.state::healthCheckSonarGateEnabled)
                }
                row {
                    checkBox("CVE annotations in pom.xml")
                        .bindSelected(settings.state::healthCheckCveEnabled)
                }
            }
            row("Maven goals:") {
                textField()
                    .columns(30)
                    .bindText(
                        { settings.state.healthCheckMavenGoals ?: "" },
                        { settings.state.healthCheckMavenGoals = it }
                    )
            }
            row("Skip for branches (regex):") {
                textField()
                    .columns(30)
                    .bindText(
                        { settings.state.healthCheckSkipBranchPattern ?: "" },
                        { settings.state.healthCheckSkipBranchPattern = it }
                    )
                    .comment("e.g., hotfix/.* — leave blank to run on all branches")
            }
            row("Timeout (seconds):") {
                intTextField(range = 10..3600)
                    .bindIntText(settings.state::healthCheckTimeoutSeconds)
            }
        }
        group("Copyright") {
            row("Header pattern (regex):") {
                textField()
                    .columns(40)
                    .bindText(
                        { settings.state.copyrightHeaderPattern ?: "" },
                        { settings.state.copyrightHeaderPattern = it }
                    )
                    .comment("e.g., Copyright \\(c\\) \\d{4} MyCompany")
            }
        }
    }
}
