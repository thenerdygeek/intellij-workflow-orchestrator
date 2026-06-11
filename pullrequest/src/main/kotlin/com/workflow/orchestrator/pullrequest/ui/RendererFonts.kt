package com.workflow.orchestrator.pullrequest.ui

import java.awt.Font
import java.util.concurrent.ConcurrentHashMap

/**
 * LAF-safe font caches for list-cell renderers (P1-20 / P2-20).
 *
 * `Font.deriveFont` / `Font(...)` per paint allocates a new font (and on Windows churns a GDI
 * handle) for every visible cell on every repaint. These caches make the per-render call a map
 * lookup instead, while staying LAF/scale-safe: entries are keyed by the *full* derivation
 * inputs — the current base font instance (which changes on LAF/theme/IDE-font-size switch)
 * plus the requested style/size (which changes with UI scale) — so a theme or scale change
 * produces a fresh entry instead of serving a stale snapshot. Never replace these with a single
 * static `Font`/`FontMetrics` field (B20).
 *
 * The key space is tiny in practice (one entry per LAF font x style x scale ever seen), so the
 * maps are unbounded by design.
 */
internal object RendererFonts {

    private val derivedCache = ConcurrentHashMap<DerivedKey, Font>()
    private val monospacedCache = ConcurrentHashMap<MonoKey, Font>()

    private data class DerivedKey(val base: Font, val style: Int, val size: Float)

    private data class MonoKey(val style: Int, val size: Int)

    /** [Font.deriveFont] with a new style, keeping the base size. Cached by (base, style, size). */
    fun derive(base: Font, style: Int): Font = derive(base, style, base.size2D)

    /** [Font.deriveFont] with a new style and size. Cached by (base, style, size). */
    fun derive(base: Font, style: Int, size: Float): Font =
        derivedCache.computeIfAbsent(DerivedKey(base, style, size)) { key ->
            key.base.deriveFont(key.style, key.size)
        }

    /**
     * Logical monospaced font cached by (style, size). Callers pass the already-scaled size
     * (e.g. `JBUI.scale(11)`), so a UI-scale change maps to a new cache entry.
     */
    fun monospaced(style: Int, size: Int): Font =
        monospacedCache.computeIfAbsent(MonoKey(style, size)) { key ->
            Font(Font.MONOSPACED, key.style, key.size)
        }
}
