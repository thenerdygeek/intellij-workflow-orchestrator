package com.workflow.orchestrator.companyb

import com.workflow.orchestrator.core.config.ConfigPreset

/** Company B's config preset (order = 0, beats A's DefaultConfigPreset). Supplies company default
 * VALUES; A's ConfigPresetSeeder applies them one-shot + guarded. */
class CompanyBConfigPreset : ConfigPreset {
    override val order: Int get() = 0
    override fun bambooBuildVariableName(): String = "DockerTagsAsJSON"
    override fun quickClipboardChips(): List<String> = listOf(
        "docker.tag",
        "docker.tagsJson",
        "pr.url",
        "build.url",
        "automation.url",
        "ticket.id",
        "ai.changeSummary",
        "ai.ticketSummary",
    )
    override fun defaultTargetBranch(): String = "develop"
    // copyrightTemplate: NOT seeded — the company supplies its real header in B's settings (the EP
    // supports it; returning null avoids inserting placeholder copyright text). See DECISION 2.
}
