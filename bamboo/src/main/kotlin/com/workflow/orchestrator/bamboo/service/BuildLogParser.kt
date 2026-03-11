package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.model.BuildError
import com.workflow.orchestrator.bamboo.model.ErrorSeverity

object BuildLogParser {

    // Matches: [ERROR] /path/to/File.java:[lineNum,col] message
    private val FILE_ERROR_PATTERN = Regex(
        """\[ERROR]\s+(/\S+\.(?:java|kt|xml)):\[(\d+),\d+]\s+(?:error:\s+)?(.+)"""
    )

    // Matches: [WARNING] /path/to/File.java:[lineNum,col] warning: message
    private val FILE_WARNING_PATTERN = Regex(
        """\[WARNING]\s+(/\S+\.(?:java|kt|xml)):\[(\d+),\d+]\s+(?:warning:\s+)?(.+)"""
    )

    // Matches: [ERROR] generic message (no file path)
    private val GENERIC_ERROR_PATTERN = Regex(
        """\[ERROR]\s+(?!/\S+\.(?:java|kt|xml))(.+)"""
    )

    fun parse(log: String): List<BuildError> {
        val errors = mutableListOf<BuildError>()

        for (line in log.lines()) {
            FILE_ERROR_PATTERN.find(line)?.let { match ->
                errors.add(
                    BuildError(
                        severity = ErrorSeverity.ERROR,
                        message = match.groupValues[3].trim(),
                        filePath = match.groupValues[1],
                        lineNumber = match.groupValues[2].toIntOrNull()
                    )
                )
                return@let
            } ?: FILE_WARNING_PATTERN.find(line)?.let { match ->
                errors.add(
                    BuildError(
                        severity = ErrorSeverity.WARNING,
                        message = match.groupValues[3].trim(),
                        filePath = match.groupValues[1],
                        lineNumber = match.groupValues[2].toIntOrNull()
                    )
                )
                return@let
            } ?: run {
                GENERIC_ERROR_PATTERN.find(line)?.let { match ->
                    val msg = match.groupValues[1].trim()
                    // Skip continuation lines (e.g., "  symbol:", "  location:")
                    if (!msg.startsWith("symbol:") && !msg.startsWith("location:") && msg.isNotBlank()) {
                        errors.add(
                            BuildError(
                                severity = ErrorSeverity.ERROR,
                                message = msg,
                                filePath = null,
                                lineNumber = null
                            )
                        )
                    }
                }
            }
        }

        return errors
    }
}
