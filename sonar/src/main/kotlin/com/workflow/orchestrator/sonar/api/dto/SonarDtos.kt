package com.workflow.orchestrator.sonar.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Authentication ---

@Serializable
data class SonarValidationDto(
    val valid: Boolean
)

// --- Project Search ---

@Serializable
data class SonarProjectSearchResult(
    val paging: SonarPagingDto,
    val components: List<SonarProjectDto> = emptyList()
)

@Serializable
data class SonarProjectDto(
    val key: String,
    val name: String,
    val qualifier: String = "TRK"
)

@Serializable
data class SonarPagingDto(
    val pageIndex: Int = 1,
    val pageSize: Int = 100,
    val total: Int = 0
)

// --- Quality Gate ---

@Serializable
data class SonarQualityGateResponse(
    val projectStatus: SonarQualityGateDto
)

@Serializable
data class SonarQualityGateDto(
    val status: String,
    val conditions: List<SonarConditionDto> = emptyList()
)

@Serializable
data class SonarConditionDto(
    val status: String,
    val metricKey: String,
    val comparator: String,
    val errorThreshold: String = "",
    val actualValue: String = ""
)

// --- Issues ---

@Serializable
data class SonarIssueSearchResult(
    val paging: SonarPagingDto = SonarPagingDto(),
    val issues: List<SonarIssueDto> = emptyList()
)

@Serializable
data class SonarIssueDto(
    val key: String,
    val rule: String,
    val severity: String,
    val message: String,
    val component: String,
    val type: String,
    val effort: String? = null,
    val textRange: SonarTextRangeDto? = null
)

@Serializable
data class SonarTextRangeDto(
    val startLine: Int,
    val endLine: Int,
    val startOffset: Int = 0,
    val endOffset: Int = 0
)

// --- Measures (file-level coverage) ---

@Serializable
data class SonarMeasureSearchResult(
    val paging: SonarPagingDto = SonarPagingDto(),
    val baseComponent: SonarComponentDto? = null,
    val components: List<SonarMeasureComponentDto> = emptyList()
)

@Serializable
data class SonarComponentDto(
    val key: String,
    val name: String,
    val qualifier: String = "",
    val path: String? = null
)

@Serializable
data class SonarMeasureComponentDto(
    val key: String,
    val name: String = "",
    val qualifier: String = "",
    val path: String? = null,
    val measures: List<SonarMeasureDto> = emptyList()
)

@Serializable
data class SonarMeasureDto(
    val metric: String,
    val value: String = ""
)

// --- Source Lines (per-line coverage) ---

@Serializable
data class SonarSourceLineDto(
    val line: Int,
    val code: String = "",
    val lineHits: Int? = null,
    val conditions: Int? = null,
    val coveredConditions: Int? = null
)
