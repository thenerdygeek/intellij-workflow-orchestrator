package com.workflow.orchestrator.core.settings

import com.workflow.orchestrator.core.config.ConfigPreset
import com.workflow.orchestrator.core.config.DefaultConfigPreset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigPresetSeederTest {
    private fun preset(
        bambooVar: String? = null,
        chips: List<String>? = null,
        branch: String? = null,
        copyright: String? = null,
    ) = object : ConfigPreset {
        override fun bambooBuildVariableName() = bambooVar
        override fun quickClipboardChips() = chips
        override fun defaultTargetBranch() = branch
        override fun copyrightTemplate() = copyright
    }

    @Test fun `seeds neutral fields from a value-providing preset and sets the sentinel`() {
        val s = PluginSettings.State()
        val company = listOf("docker.tag", "docker.tagsJson", "automation.url")
        val mutated = ConfigPresetSeeder.seed(s, preset("DockerTagsAsJSON", company, "develop"))
        assertTrue(mutated)
        assertEquals("DockerTagsAsJSON", s.bambooBuildVariableName)
        assertEquals(company, s.quickClipboardChips)
        assertEquals("develop", s.defaultTargetBranch)
        assertTrue(s.configPresetApplied)
    }

    @Test fun `does NOT clobber user-set fields (guard)`() {
        val s = PluginSettings.State().apply {
            bambooBuildVariableName = "MyVar"
            defaultTargetBranch = "trunk"
            quickClipboardChips = mutableListOf("only.mine")
        }
        ConfigPresetSeeder.seed(s, preset("DockerTagsAsJSON", listOf("docker.tag"), "develop"))
        assertEquals("MyVar", s.bambooBuildVariableName)
        assertEquals("trunk", s.defaultTargetBranch)
        assertEquals(listOf("only.mine"), s.quickClipboardChips)
    }

    @Test fun `is one-shot — does not re-seed once applied (no chip resurrection)`() {
        val s = PluginSettings.State()
        // First seed applies and stamps the sentinel.
        ConfigPresetSeeder.seed(s, preset(chips = listOf("docker.tag", "pr.url")))
        // User removes a seeded chip.
        s.quickClipboardChips = mutableListOf("pr.url")
        val mutated = ConfigPresetSeeder.seed(s, preset(chips = listOf("docker.tag", "pr.url")))
        assertFalse(mutated)
        // NOT resurrected.
        assertEquals(listOf("pr.url"), s.quickClipboardChips)
    }

    @Test fun `DefaultConfigPreset provides nothing — does not set the sentinel (B-after-A still seeds)`() {
        val s = PluginSettings.State()
        val mutated = ConfigPresetSeeder.seed(s, DefaultConfigPreset())
        assertFalse(mutated)
        // Sentinel stays false so a later B install still seeds.
        assertFalse(s.configPresetApplied)
    }
}
