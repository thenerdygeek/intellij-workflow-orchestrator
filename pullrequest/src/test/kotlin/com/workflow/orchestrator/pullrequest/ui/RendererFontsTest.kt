package com.workflow.orchestrator.pullrequest.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.awt.Font

/**
 * Unit tests for [RendererFonts] (P1-20 / P2-20).
 *
 * Cache-correctness story: same derivation inputs must hit the cache (no per-paint Font
 * allocation), while a changed base font (LAF/theme switch) or changed size (UI scale change)
 * must map to a different entry — never a stale single static snapshot (B20-class bug).
 */
class RendererFontsTest {

    @Test
    fun `derive returns the identical cached instance for the same base, style and size`() {
        val base = Font("Dialog", Font.PLAIN, 12)

        val first = RendererFonts.derive(base, Font.BOLD)
        val second = RendererFonts.derive(base, Font.BOLD)

        assertSame(first, second, "per-paint derive must be a cache hit, not an allocation")
        assertEquals(Font.BOLD, first.style)
        assertEquals(base.size2D, first.size2D)
    }

    @Test
    fun `a different base font (LAF switch) yields a different derived font`() {
        val lightLaf = Font("Dialog", Font.PLAIN, 12)
        val darkLaf = Font("SansSerif", Font.PLAIN, 12)

        val fromLight = RendererFonts.derive(lightLaf, Font.BOLD)
        val fromDark = RendererFonts.derive(darkLaf, Font.BOLD)

        assertNotSame(fromLight, fromDark, "LAF switch must not serve the old LAF's font")
        assertEquals("SansSerif", fromDark.family)
    }

    @Test
    fun `a different size (UI scale change) yields a different derived font at that size`() {
        val base = Font("Dialog", Font.PLAIN, 12)

        val at10 = RendererFonts.derive(base, Font.ITALIC, 10f)
        val at20 = RendererFonts.derive(base, Font.ITALIC, 20f)

        assertNotSame(at10, at20)
        assertEquals(10f, at10.size2D)
        assertEquals(20f, at20.size2D)
    }

    @Test
    fun `monospaced fonts are cached per style and size`() {
        val first = RendererFonts.monospaced(Font.BOLD, 11)
        val second = RendererFonts.monospaced(Font.BOLD, 11)
        val scaled = RendererFonts.monospaced(Font.BOLD, 22)

        assertSame(first, second, "same (style, size) must be a cache hit")
        assertNotSame(first, scaled, "a scale change must produce a new entry, not a stale font")
        assertEquals(Font.MONOSPACED, first.name)
        assertEquals(Font.BOLD, first.style)
        assertEquals(11, first.size)
        assertEquals(22, scaled.size)
    }
}
