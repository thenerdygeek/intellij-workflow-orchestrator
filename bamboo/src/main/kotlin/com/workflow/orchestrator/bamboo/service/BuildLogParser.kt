package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.bamboo.model.BuildError
import com.workflow.orchestrator.bamboo.model.ErrorSeverity

object BuildLogParser {

    private val log = Logger.getInstance(BuildLogParser::class.java)

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

    fun parse(buildLog: String): List<BuildError> {
        log.info("[Bamboo:Parser] Starting build log parsing (${buildLog.lines().size} lines)")
        val errors = mutableListOf<BuildError>()

        for (line in buildLog.lines()) {
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

        val errorCount = errors.count { it.severity == ErrorSeverity.ERROR }
        val warningCount = errors.count { it.severity == ErrorSeverity.WARNING }
        log.info("[Bamboo:Parser] Parsing complete: $errorCount errors, $warningCount warnings found")
        if (errorCount > 0) {
            log.debug("[Bamboo:Parser] Errors: ${errors.filter { it.severity == ErrorSeverity.ERROR }.joinToString { "${it.filePath}:${it.lineNumber} - ${it.message}" }}")
        }
        return errors
    }
}
