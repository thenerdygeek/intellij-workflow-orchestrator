package com.workflow.orchestrator.core.ui

import java.io.File
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
    private fun absolute(epochMillis: Long): String {
        if (epochMillis <= 0) return ""
        return ABSOLUTE.format(Instant.ofEpochMilli(epochMillis))
    }

    /** Relative text with absolute tooltip */
    fun relativeWithTooltip(epochMillis: Long): Pair<String, String> {
        return relative(epochMillis) to absolute(epochMillis)
    }

    /**
     * Formats a duration in seconds as "1h 30m 15s" / "5m 2s" / "12s" style.
     * Returns [zero] for non-positive input (defaults to "0s").
     * Used for build durations, plan run times, etc.
     */
    fun formatDurationSeconds(seconds: Long, zero: String = "0s"): String {
        if (seconds <= 0) return zero
        val hours = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return buildString {
            if (hours > 0) append("${hours}h ")
            if (mins > 0) append("${mins}m ")
            if (secs > 0 || isEmpty()) append("${secs}s")
        }.trim()
    }

    /**
     * Formats a duration in milliseconds as "12m 34s" / "15s" style (no hours).
     * Returns [zero] for non-positive input (defaults to "--").
     * Used for stage durations, shorter operations.
     */
    fun formatDurationMillis(ms: Long, zero: String = "--"): String {
        if (ms <= 0) return zero
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    /**
     * Formats effort/debt minutes as work-day duration (e.g., "4h 30min", "2d 3h").
     * Returns "0min" for non-positive input.
     * Uses 8-hour work days. Minutes are omitted once days are present.
     * Used for SonarQube technical debt, issue effort estimates.
     */
    /**
     * Formats an ISO-8601 timestamp as a relative age suitable for Jira-style
     * comment lists. Tolerates the Jira `+0000` offset form (converts to `Z` before
     * parsing). Thresholds: <60m → "Xm ago"; <24h → "Xh ago"; <[maxDaysAsRelative]
     * days → "Xd ago". Beyond that threshold, returns the date portion of the
     * input (`isoDate.take(10)`) when [fallbackDateOnly] is true, else an absolute
     * "yyyy-MM-dd HH:mm" timestamp. Parse failures also fall back to `isoDate.take(10)`.
     */
    fun relativeFromIso(
        isoDate: String,
        maxDaysAsRelative: Int = 30,
        fallbackDateOnly: Boolean = true,
    ): String {
        return try {
            val instant = Instant.parse(isoDate.replace("+0000", "Z"))
            val duration = java.time.Duration.between(instant, Instant.now())
            when {
                duration.toMinutes() < 60 -> "${duration.toMinutes()}m ago"
                duration.toHours() < 24 -> "${duration.toHours()}h ago"
                duration.toDays() < maxDaysAsRelative -> "${duration.toDays()}d ago"
                else -> if (fallbackDateOnly) isoDate.take(10) else ABSOLUTE.format(instant)
            }
        } catch (_: Exception) {
            isoDate.take(10)
        }
    }

    /**
     * Formats a file's modification age in compact form: "5s ago", "2m ago",
     * "3h ago", "1d ago". Used for build-lockfile freshness hints
     * (poetry.lock / uv.lock / etc.). Returns negative strings for
     * future-dated files (no guard — caller's responsibility if that matters).
     */
    fun formatFileAge(file: File): String = formatFileAge(file.lastModified())

    /**
     * Formats age relative to `System.currentTimeMillis()` in compact form.
     * See [formatFileAge] above for usage context.
     */
    fun formatFileAge(lastModifiedMillis: Long): String {
        val ageMs = System.currentTimeMillis() - lastModifiedMillis
        val ageSec = ageMs / 1000
        return when {
            ageSec < 60 -> "${ageSec}s ago"
            ageSec < 3600 -> "${ageSec / 60}m ago"
            ageSec < 86400 -> "${ageSec / 3600}h ago"
            else -> "${ageSec / 86400}d ago"
        }
    }

    fun formatEffortMinutes(totalMinutes: Int): String {
        if (totalMinutes <= 0) return "0min"
        val days = totalMinutes / (8 * 60) // 8h work day
        val hours = (totalMinutes % (8 * 60)) / 60
        val mins = totalMinutes % 60
        return buildString {
            if (days > 0) append("${days}d ")
            if (hours > 0) append("${hours}h ")
            if (mins > 0 && days == 0) append("${mins}min") // skip mins when days present
        }.trim().ifEmpty { "0min" }
    }
}
