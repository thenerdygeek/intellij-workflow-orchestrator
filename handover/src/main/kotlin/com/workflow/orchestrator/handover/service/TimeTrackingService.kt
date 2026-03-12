package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
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

    companion object {
        @Deprecated("Read maxWorklogHours from PluginSettings via getMaxHours() instead")
        const val MAX_HOURS = 7.0
        private val ISO_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

        fun getInstance(project: Project): TimeTrackingService {
            return project.getService(TimeTrackingService::class.java)
        }
    }

    fun getMaxHours(): Double {
        val proj = project ?: return 7.0
        return com.workflow.orchestrator.core.settings.PluginSettings.getInstance(proj).state.maxWorklogHours.toDouble()
    }

    fun validateHours(hours: Double): Boolean {
        return hours > 0.0 && hours <= getMaxHours()
    }

    fun hoursToSeconds(hours: Double): Int {
        return (hours * 3600).toInt()
    }

    fun formatStartedDate(year: Int, month: Int, day: Int, hour: Int, minute: Int): String {
        val dt = LocalDateTime.of(year, month, day, hour, minute, 0)
        return dt.atOffset(ZoneOffset.UTC).format(ISO_FORMAT)
    }

    fun clampHours(hours: Double): Double {
        return hours.coerceAtMost(getMaxHours())
    }

    fun isFutureDate(year: Int, month: Int, day: Int): Boolean {
        val date = java.time.LocalDate.of(year, month, day)
        return date.isAfter(java.time.LocalDate.now())
    }

    fun computeElapsedHours(startTimestamp: Long, currentTimestamp: Long): Double {
        if (startTimestamp == 0L) return 0.0
        val diffMs = currentTimestamp - startTimestamp
        return diffMs.toDouble() / (3600.0 * 1000.0)
    }
}
