package com.workflow.orchestrator.core.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimeFormatter {
    private val ABSOLUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())

    /** Formats a timestamp as relative time: "2m ago", "1h ago", "3d ago" */
    fun relative(epochMillis: Long): String {
        if (epochMillis <= 0) return ""
        val now = System.currentTimeMillis()
        val diff = now - epochMillis
        return when {
            diff < 60_000 -> "just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            diff < 604_800_000 -> "${diff / 86_400_000}d ago"
            else -> ABSOLUTE.format(Instant.ofEpochMilli(epochMillis))
        }
    }

    /** Formats a timestamp as absolute: "2024-03-15 14:32" */
    fun absolute(epochMillis: Long): String {
        if (epochMillis <= 0) return ""
        return ABSOLUTE.format(Instant.ofEpochMilli(epochMillis))
    }

    /** Relative text with absolute tooltip */
    fun relativeWithTooltip(epochMillis: Long): Pair<String, String> {
        return relative(epochMillis) to absolute(epochMillis)
    }
}
