package com.workflow.orchestrator.core.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RightGap

/**
 * Reusable banner that goes at the top of feature settings pages to show
 * whether the underlying connections are configured. Click the inline
 * "Set up in Connections" link to jump straight to the Connections page.
 *
 * Usage from a [Panel] DSL:
 *
 * ```
 * panel {
 *     ConnectionStatusBanner.render(this, project,
 *         requirements = listOf(
 *             ConnectionStatusBanner.Requirement.JIRA,
 *             ConnectionStatusBanner.Requirement.BITBUCKET,
 *         )
 *     )
 *     // ... rest of the page
 * }
 * ```
 *
 * Behaviour:
 *  - **All required connections present** → green check + "Connected" label
 *  - **One or more missing** → red error + "X needs to be set up in Connections" link
 *  - **Multiple missing** → "X, Y need to be set up in Connections" link
 *
 * The link uses [ShowSettingsUtil.showSettingsDialog] to navigate to the
 * Connections sub-page so the user can fix the issue without leaving Settings.
 */
object ConnectionStatusBanner {

    /**
     * A connection requirement. Each value knows how to check itself
     * against the current [ConnectionSettings] state.
     */
    enum class Requirement(val displayName: String) {
        JIRA("Jira") {
            override fun isConfigured(state: ConnectionSettings.State): Boolean = !state.jiraUrl.isNullOrBlank()
        },
        BAMBOO("Bamboo") {
            override fun isConfigured(state: ConnectionSettings.State): Boolean = !state.bambooUrl.isNullOrBlank()
        },
        BITBUCKET("Bitbucket") {
            override fun isConfigured(state: ConnectionSettings.State): Boolean = !state.bitbucketUrl.isNullOrBlank()
        },
        SONARQUBE("SonarQube") {
            override fun isConfigured(state: ConnectionSettings.State): Boolean = !state.sonarUrl.isNullOrBlank()
        },
        SOURCEGRAPH("Sourcegraph") {
            override fun isConfigured(state: ConnectionSettings.State): Boolean = !state.sourcegraphUrl.isNullOrBlank()
        },
        NEXUS("Nexus Docker Registry") {
            override fun isConfigured(state: ConnectionSettings.State): Boolean = !state.nexusUrl.isNullOrBlank()
        };

        abstract fun isConfigured(state: ConnectionSettings.State): Boolean
    }

    /**
     * The Settings sub-page id for Connections (used by [ShowSettingsUtil]
     * to navigate from a banner click).
     */
    const val CONNECTIONS_PAGE_ID = "workflow.orchestrator.connections"

    /**
     * Render a banner row at the top of [panel] that summarises the state
     * of the supplied [requirements] for [project].
     *
     * @param panel    the parent [Panel] DSL builder
     * @param project  the current project, used for navigation on click
     * @param requirements connections this page depends on
     */
    fun render(panel: Panel, project: Project, requirements: List<Requirement>) {
        if (requirements.isEmpty()) return
        val state = ConnectionSettings.getInstance().state
        val missing = requirements.filter { !it.isConfigured(state) }

        with(panel) {
            row {
                if (missing.isEmpty()) {
                    icon(AllIcons.General.InspectionsOK).gap(RightGap.SMALL)
                    val labelText = if (requirements.size == 1) {
                        "${requirements.first().displayName} connected"
                    } else {
                        requirements.joinToString(", ") { it.displayName } + " connected"
                    }
                    label(labelText)
                } else {
                    icon(AllIcons.General.Error).gap(RightGap.SMALL)
                    val missingText = missing.joinToString(", ") { it.displayName }
                    val verb = if (missing.size == 1) "needs" else "need"
                    label("$missingText $verb to be set up in").gap(RightGap.SMALL)
                    cell(
                        ActionLink("Connections") {
                            ShowSettingsUtil.getInstance().showSettingsDialog(project, CONNECTIONS_PAGE_ID)
                        }
                    )
                }
            }
            row { /* empty spacer row to separate from the page content */ }
        }
    }
}
