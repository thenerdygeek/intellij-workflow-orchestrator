package com.workflow.orchestrator.core.ui

import java.awt.Graphics2D
import java.awt.RenderingHints

/**
 * Shared rendering utilities for cell renderers and custom paint components.
 * Caches expensive system calls (desktop font hints) to avoid per-render JNI overhead.
 */
object RenderingUtils {

    /**
     * Cached desktop font rendering hints. Fetched once from the OS via JNI.
     * On Windows this avoids repeated GDI calls (1-5ms each) during cell rendering.
     */
    @Suppress("UNCHECKED_CAST")
    private val DESKTOP_HINTS: Map<RenderingHints.Key, Any> by lazy {
        val raw = java.awt.Toolkit.getDefaultToolkit()
            .getDesktopProperty("awt.font.desktophints") as? Map<*, *>
        if (raw != null) {
            raw.entries
                .filter { it.key is RenderingHints.Key && it.value != null }
                .associate { (it.key as RenderingHints.Key) to it.value!! }
        } else {
            mapOf(RenderingHints.KEY_TEXT_ANTIALIASING to RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        }
    }

    /**
     * Apply cached desktop font hints and antialiasing to a Graphics2D context.
     * Call this once at the start of [paintComponent] instead of fetching hints per render.
     */
    fun applyDesktopHints(g2: Graphics2D) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        DESKTOP_HINTS.forEach { (k, v) -> g2.setRenderingHint(k, v) }
    }
}
