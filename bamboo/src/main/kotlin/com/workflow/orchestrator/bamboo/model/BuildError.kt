package com.workflow.orchestrator.bamboo.model

enum class ErrorSeverity { ERROR, WARNING }

data class BuildError(
    val severity: ErrorSeverity,
    val message: String,
    val filePath: String?,
    val lineNumber: Int?
)
