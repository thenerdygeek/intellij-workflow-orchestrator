package com.workflow.orchestrator.pullrequest.ui

import java.awt.Component
import java.awt.Font
import java.awt.FontMetrics

/**
 * LAF-safe single-slot cache for a derived font and its [FontMetrics] (B20).
 *
 * The previous implementation cached a static `Font`/`FontMetrics` snapshot forever, which went
 * stale after a LAF/theme switch or a UI-scale change. This cache re-derives whenever the
 * *current* base font instance (LAF-owned, replaced on theme/font-size change) or the requested
 * size (scale-dependent) differs from the cached key, so theme/scale changes produce a fresh
 * font while steady-state renders hit the cache.
 *
 * Suppliers are injected so the staleness/derivation logic is unit-testable without a LAF.
 * Not thread-safe; intended for EDT-only renderer use.
 */
internal class LafFontCache(
    private val currentBaseFont: () -> Font?,
    private val currentSize: () -> Float,
    private val deriveFont: (size: Float) -> Font,
) {
    private var cachedBase: Font? = null

    private var cachedSize: Float = Float.NaN

    private var cachedFont: Font? = null

    private var cachedMetrics: FontMetrics? = null

    /** The derived font; re-derived iff the LAF base font instance or the scaled size changed. */
    fun font(): Font {
        val base = currentBaseFont()
        val size = currentSize()
        val cached = cachedFont
        if (cached != null && cachedBase === base && cachedSize == size) {
            return cached
        }
        cachedBase = base
        cachedSize = size
        cachedMetrics = null
        return deriveFont(size).also { cachedFont = it }
    }

    /** Metrics for [font], re-derived only when the cached metrics no longer match the font. */
    fun metrics(metricsFor: (Font) -> FontMetrics): FontMetrics {
        val font = font()
        val cached = cachedMetrics
        if (cached != null && cached.font == font) {
            return cached
        }
        return metricsFor(font).also { cachedMetrics = it }
    }

    /** Convenience overload for Swing call sites. */
    fun metrics(component: Component): FontMetrics = metrics { component.getFontMetrics(it) }
}
