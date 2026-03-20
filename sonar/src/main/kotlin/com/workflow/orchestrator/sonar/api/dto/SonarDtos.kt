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
    val actualValue: String = "",
    val warningThreshold: String? = null
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
    val textRange: SonarTextRangeDto? = null,
    val creationDate: String? = null,
    val updateDate: String? = null,
    val status: String = "OPEN",       // OPEN, CONFIRMED, REOPENED, RESOLVED, CLOSED
    val tags: List<String> = emptyList(),
    val author: String? = null
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

// --- Project-level Measures (aggregate, not file-level) ---

@Serializable
data class SonarComponentMeasureResponse(
    val component: SonarComponentWithMeasures = SonarComponentWithMeasures()
)

@Serializable
data class SonarComponentWithMeasures(
    val key: String = "",
    val name: String = "",
    val qualifier: String = "",
    val measures: List<SonarMeasureDto> = emptyList()
)

// --- Branch List ---

@Serializable
data class SonarBranchListResponse(
    val branches: List<SonarBranchDto> = emptyList()
)

@Serializable
data class SonarBranchDto(
    val name: String,
    val isMain: Boolean = false,
    val type: String = "BRANCH",
    val status: SonarBranchStatusDto? = null,
    val analysisDate: String? = null
)

@Serializable
data class SonarBranchStatusDto(
    val qualityGateStatus: String = "",
    val bugs: Int? = null,
    val vulnerabilities: Int? = null,
    val codeSmells: Int? = null
)

// --- Components Search (qualifiers=TRK for projects) ---

@Serializable
data class SonarComponentSearchResult(
    val paging: SonarPagingDto = SonarPagingDto(),
    val components: List<SonarProjectDto> = emptyList()
)

// --- Compute Engine (CE) Activity ---

@Serializable
data class SonarCeActivityResponse(
    val tasks: List<SonarCeTaskDto> = emptyList()
)

@Serializable
data class SonarCeTaskDto(
    val id: String = "",
    val type: String = "",
    val componentKey: String = "",
    val status: String = "",           // PENDING, IN_PROGRESS, SUCCESS, FAILED, CANCELED
    val branch: String? = null,
    val branchType: String? = null,
    val submittedAt: String? = null,
    val startedAt: String? = null,
    val executedAt: String? = null,
    val executionTimeMs: Long? = null,
    val errorMessage: String? = null,
    val hasScannerContext: Boolean = false,
    val hasErrorStacktrace: Boolean = false
)

// --- New Code Period ---

@Serializable
data class SonarNewCodePeriodDto(
    val projectKey: String = "",
    val branchKey: String = "",
    val type: String = "",             // PREVIOUS_VERSION, NUMBER_OF_DAYS, REFERENCE_BRANCH, SPECIFIC_ANALYSIS
    val value: String = "",
    val effectiveValue: String = "",
    val inherited: Boolean = false
)

// --- Source Lines (per-line coverage) ---

@Serializable
data class SonarSourceLinesResponse(val sources: List<SonarSourceLineDto> = emptyList())

@Serializable
data class SonarSourceLineDto(
    val line: Int,
    val code: String = "",
    val lineHits: Int? = null,
    val conditions: Int? = null,
    val coveredConditions: Int? = null
)
