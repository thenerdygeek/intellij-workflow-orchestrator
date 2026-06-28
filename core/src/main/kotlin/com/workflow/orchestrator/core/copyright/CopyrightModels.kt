package com.workflow.orchestrator.core.copyright

// Copyright models
data class CopyrightFileEntry(
    val filePath: String,
    val status: CopyrightStatus,
    val oldYear: String? = null,
    val newYear: String? = null
)

enum class CopyrightStatus {
    OK,
    YEAR_OUTDATED,
    MISSING_HEADER
}
