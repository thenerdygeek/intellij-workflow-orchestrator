package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Service(Service.Level.PROJECT)
class TimeTrackingService {

    private var project: Project? = null

    constructor(project: Project) {
        this.project = project
    }

    constructor()

    private val log = Logger.getInstance(TimeTrackingService::class.java)

    fun getMaxHours(): Double {
        val proj = project ?: return DEFAULT_MAX_HOURS
        return PluginSettings.getInstance(proj).state.maxWorklogHours.toDouble()
    }

    fun validateHours(hours: Double): Boolean {
        val maxHours = getMaxHours()
        val valid = hours > 0.0 && hours <= maxHours
        if (!valid) {
            log.warn("[Handover:Time] Invalid hours: $hours (max: $maxHours)")
        }
        return valid
    }

    fun hoursToSeconds(hours: Double): Int = (hours * 3600).toInt()

    fun formatStartedDate(year: Int, month: Int, day: Int, hour: Int, minute: Int): String {
        val formatted = LocalDateTime.of(year, month, day, hour, minute, 0)
            .atOffset(ZoneOffset.UTC)
            .format(ISO_FORMAT)
        log.debug("[Handover:Time] Formatted started date: $formatted")
        return formatted
    }

    fun clampHours(hours: Double): Double = hours.coerceAtMost(getMaxHours())

    fun isFutureDate(year: Int, month: Int, day: Int): Boolean =
        LocalDate.of(year, month, day).isAfter(LocalDate.now())

    fun computeElapsedHours(startTimestamp: Long, currentTimestamp: Long): Double {
        if (startTimestamp == 0L) return 0.0
        val diffMs = currentTimestamp - startTimestamp
        val hours = diffMs.toDouble() / (3600.0 * 1000.0)
        log.debug("[Handover:Time] Computed elapsed hours: $hours (${diffMs}ms)")
        return hours
    }

    companion object {
        private const val DEFAULT_MAX_HOURS = 7.0
        private val ISO_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

        fun getInstance(project: Project): TimeTrackingService =
            project.getService(TimeTrackingService::class.java)
    }
}
