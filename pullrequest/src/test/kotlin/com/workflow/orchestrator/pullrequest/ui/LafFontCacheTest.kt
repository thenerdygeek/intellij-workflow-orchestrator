package com.workflow.orchestrator.pullrequest.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.awt.Font
import java.awt.FontMetrics

/**
 * Unit tests for [LafFontCache] (B20).
 *
 * The cache must serve a stable Font/FontMetrics while the LAF base font and UI scale are
 * unchanged, and re-derive as soon as either changes — the bug being fixed was a static
 * snapshot that survived LAF/theme switches and scale changes forever.
 */
class LafFontCacheTest {

    private class Fixture {
        var baseFont: Font = Font("Dialog", Font.PLAIN, 12)

        var size: Float = 9f

        var deriveCount: Int = 0

        var metricsCount: Int = 0

        val cache = LafFontCache(
            currentBaseFont = { baseFont },
            currentSize = { size },
            deriveFont = { s ->
                deriveCount++
                baseFont.deriveFont(Font.BOLD, s)
            },
        )

        fun metrics(): FontMetrics = cache.metrics { font ->
            metricsCount++
            object : FontMetrics(font) {}
        }
    }

    @Test
    fun `steady state serves the same cached font without re-deriving`() {
        val f = Fixture()

        val first = f.cache.font()
        val second = f.cache.font()

        assertSame(first, second)
        assertEquals(1, f.deriveCount, "derive must run exactly once while base font + size are stable")
    }

    @Test
    fun `LAF switch (new base font instance) re-derives the font`() {
        val f = Fixture()
        val before = f.cache.font()

        f.baseFont = Font("Dialog", Font.PLAIN, 13) // theme switch replaces the LAF font instance
        val after = f.cache.font()

        assertNotSame(before, after, "a stale pre-switch font must not be served")
        assertEquals(2, f.deriveCount)
    }

    @Test
    fun `UI scale change (new size) re-derives the font at the new size`() {
        val f = Fixture()
        f.cache.font()

        f.size = 18f // e.g. 200% scale
        val after = f.cache.font()

        assertEquals(18f, after.size2D)
        assertEquals(2, f.deriveCount)
    }

    @Test
    fun `metrics are cached while the font is stable`() {
        val f = Fixture()

        val first = f.metrics()
        val second = f.metrics()

        assertSame(first, second)
        assertEquals(1, f.metricsCount)
    }

    @Test
    fun `metrics are re-derived after the font goes stale`() {
        val f = Fixture()
        val before = f.metrics()

        f.size = 18f
        val after = f.metrics()

        assertNotSame(before, after, "metrics for the old font must not be reused for the new font")
        assertEquals(after.font, f.cache.font())
        assertEquals(2, f.metricsCount)
    }
}
