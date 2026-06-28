package com.workflow.orchestrator.companyb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pure JUnit5 unit pins for [CompanyBConfigPreset]. No platform deps — instantiate directly and
 * assert contract. These run RED until [CompanyBConfigPreset] exists (TDD red→green).
 */
class CompanyBConfigPresetTest {

    private val preset = CompanyBConfigPreset()

    @Test
    fun `order is 0 so it beats DefaultConfigPreset at MAX_VALUE`() {
        assertEquals(0, preset.order)
    }

    @Test
    fun `bambooBuildVariableName returns DockerTagsAsJSON`() {
        assertEquals("DockerTagsAsJSON", preset.bambooBuildVariableName())
    }

    @Test
    fun `quickClipboardChips returns the exact 8-entry company list`() {
        assertEquals(
            listOf(
                "docker.tag",
                "docker.tagsJson",
                "pr.url",
                "build.url",
                "automation.url",
                "ticket.id",
                "ai.changeSummary",
                "ai.ticketSummary",
            ),
            preset.quickClipboardChips(),
        )
    }

    @Test
    fun `defaultTargetBranch returns develop`() {
        assertEquals("develop", preset.defaultTargetBranch())
    }

    @Test
    fun `copyrightTemplate returns null — company supplies real header via B settings`() {
        assertNull(preset.copyrightTemplate())
    }
}
