package com.workflow.orchestrator.companyb

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Demonstrates plugin B contributing its own Settings page nested under plugin A's
 * "Workflow Orchestrator" group, using only the platform `projectConfigurable` EP with
 * `parentId="workflow.orchestrator"` (A's stable anchor) — no custom A-side seam required.
 *
 * Placeholder content + NO persisted state for now (Phase 0b-4 = mechanism proof only). B's
 * real company-preset settings land in Phase 2. Sibling of `CompanyBToolContributor` (which
 * proved the agentToolContributor EP in Phase 0a).
 */
class CompanyBSettingsConfigurable(
    @Suppress("UnusedParameter", "UnusedPrivateProperty") project: Project,
) : Configurable {
    override fun getDisplayName(): String = "Company B"

    override fun createComponent(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(
            JLabel("Company B preset settings — placeholder (populated in Phase 2)."),
            BorderLayout.NORTH,
        )
        return panel
    }

    override fun isModified(): Boolean = false

    override fun apply() { /* no-op: B contributes no persisted settings yet (Phase 2) */ }
}
