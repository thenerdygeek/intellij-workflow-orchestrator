package com.workflow.orchestrator.core.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.test.assertIs

/**
 * Pins the plugin-split resolution contract [ConfigPreset.resolve] relies on:
 *  - the lowest-[order] provider wins regardless of its position in the EP list, so a B-registered
 *    order=0 override beats [DefaultConfigPreset] (order=Int.MAX_VALUE);
 *  - with an empty list, [ConfigPreset.lowestOrderOf] returns null;
 *  - [DefaultConfigPreset] returns null for every field (A keeps its neutral defaults);
 *  - with no platform extension-point system present (plain unit test), [ConfigPreset.resolve]
 *    degrades to a fresh [DefaultConfigPreset] via its runCatching fallback.
 * Mirrors WorkflowConfigResolutionTest's anonymous-provider style.
 */
class ConfigPresetResolutionTest {

    /** order=0 override returning distinct non-null values so we can tell which provider won. */
    private val override = object : ConfigPreset {
        override val order: Int get() = 0
        override fun bambooBuildVariableName(): String = "COMPANY_BUILD_VAR"
        override fun quickClipboardChips(): List<String> = listOf("chip1", "chip2")
        override fun defaultTargetBranch(): String = "develop"
        override fun copyrightTemplate(): String = "© Company"
    }

    /** A's no-op fallback at the lowest priority (order=Int.MAX_VALUE). */
    private val default = DefaultConfigPreset()

    @Test
    fun `order-0 override beats DefaultConfigPreset when override is listed last`() {
        val winner = ConfigPreset.lowestOrderOf(listOf(default, override))
        assertEquals("COMPANY_BUILD_VAR", winner!!.bambooBuildVariableName())
    }

    @Test
    fun `order-0 override beats DefaultConfigPreset when override is listed first`() {
        // Same providers, reversed order — position must not change the winner.
        val winner = ConfigPreset.lowestOrderOf(listOf(override, default))
        assertEquals("COMPANY_BUILD_VAR", winner!!.bambooBuildVariableName())
    }

    @Test
    fun `lowestOrderOf returns null for empty list`() {
        assertNull(ConfigPreset.lowestOrderOf(emptyList()))
    }

    @Test
    fun `DefaultConfigPreset returns null for bambooBuildVariableName`() {
        assertNull(DefaultConfigPreset().bambooBuildVariableName())
    }

    @Test
    fun `DefaultConfigPreset returns null for quickClipboardChips`() {
        assertNull(DefaultConfigPreset().quickClipboardChips())
    }

    @Test
    fun `DefaultConfigPreset returns null for defaultTargetBranch`() {
        assertNull(DefaultConfigPreset().defaultTargetBranch())
    }

    @Test
    fun `DefaultConfigPreset returns null for copyrightTemplate`() {
        assertNull(DefaultConfigPreset().copyrightTemplate())
    }

    @Test
    fun `resolve falls back to DefaultConfigPreset with no platform EP system`() {
        // No Application / extension-point registry in a plain unit test — the runCatching guard in
        // resolve() must degrade to a fresh DefaultConfigPreset instead of throwing.
        assertIs<DefaultConfigPreset>(ConfigPreset.resolve())
    }
}
