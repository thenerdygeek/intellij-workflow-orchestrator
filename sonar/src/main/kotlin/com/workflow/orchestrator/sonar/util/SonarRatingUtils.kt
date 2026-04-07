package com.workflow.orchestrator.sonar.util

/**
 * SonarQube returns its A-E ratings as numeric strings ("1.0".."5.0"), where
 * 1.0 = A, 2.0 = B, 3.0 = C, 4.0 = D, 5.0 = E.
 *
 * Both the data layer ([com.workflow.orchestrator.sonar.service.SonarDataService])
 * and the agent service implementation
 * ([com.workflow.orchestrator.sonar.service.SonarServiceImpl]) used to inline
 * this conversion. Centralised here so the mapping rule is defined exactly once.
 */
object SonarRatingUtils {

    /**
     * Maps a SonarQube numeric rating string to its A-E letter.
     *
     * Returns [unknown] when the input is null, blank, or not in the 1..5 range.
     * Defaults to an empty string, but callers can pass `value` to fall back to
     * the original input (matches the [SonarServiceImpl] behaviour).
     */
    fun ratingLetter(value: String?, unknown: String = ""): String {
        val num = value?.toDoubleOrNull()?.toInt() ?: return unknown
        return when (num) {
            1 -> "A"
            2 -> "B"
            3 -> "C"
            4 -> "D"
            5 -> "E"
            else -> unknown
        }
    }
}
