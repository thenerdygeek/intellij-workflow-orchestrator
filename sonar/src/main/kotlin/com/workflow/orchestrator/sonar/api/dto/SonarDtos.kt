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
    val conditions: List<SonarConditionDto> = emptyList(),
    val period: SonarGatePeriodDto? = null,
    /**
     * Sonar 25.x adds this; "Clean as You Code" gate compliance rating —
     * `compliant` / `over-compliant` (≥ minimum new-code conditions enforced)
     * or `non-compliant` (gate is still legacy overall-only thresholds).
     * Empty default for older Sonar that doesn't ship the field.
     */
    val caycStatus: String = ""
)

/**
 * New-code period metadata embedded in the quality gate response. Carries
 * what defines the new-code period (REFERENCE_BRANCH, NUMBER_OF_DAYS,
 * PREVIOUS_VERSION, …) and the parameter (branch name, day count, version).
 *
 * The dedicated `/api/new_code_periods/show` endpoint returns the same
 * fields but requires Administer Project permission, so the plugin reads
 * the period from the gate response only — every token with Browse
 * permission can fetch this.
 */
@Serializable
data class SonarGatePeriodDto(
    val mode: String = "",
    val parameter: String = ""
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
    val issues: List<SonarIssueDto> = emptyList(),
    /**
     * Populated only when the request URL specifies `&facets=...`. Default
     * empty list keeps existing callers (which don't pass facets) unchanged.
     */
    val facets: List<SonarFacetDto> = emptyList()
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
    val author: String? = null,
    // Clean Code taxonomy (SonarQube 9.6+). All optional / defaulted so older
    // servers without these fields still parse cleanly.
    val cleanCodeAttribute: String? = null,
    val cleanCodeAttributeCategory: String? = null,
    val impacts: List<SonarImpactDto> = emptyList(),
    val issueStatus: String? = null    // Separate from legacy `status`; OPEN, FIXED, ACCEPTED, FALSE_POSITIVE
)

/**
 * Per-software-quality impact carried on every Sonar 9.6+ issue.
 * The agent uses this for prioritization (e.g. RELIABILITY/HIGH ranks higher
 * than MAINTAINABILITY/LOW even when both have legacy `severity=MAJOR`).
 */
@Serializable
data class SonarImpactDto(
    val softwareQuality: String = "",   // RELIABILITY | SECURITY | MAINTAINABILITY
    val severity: String = ""           // INFO | LOW | MEDIUM | HIGH | BLOCKER
)

@Serializable
data class SonarTextRangeDto(
    val startLine: Int = 0,
    val endLine: Int = 0,
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
data class SonarMeasurePeriodDto(
    val value: String = "",
    val bestValue: Boolean = false
)

@Serializable
data class SonarMeasureDto(
    val metric: String,
    val value: String = "",
    val period: SonarMeasurePeriodDto? = null
) {
    /**
     * Returns the effective value for this measure.
     * SonarQube returns new code metrics (new_*) in the `period` field, not `value`.
     * Overall metrics are in `value` directly.
     */
    fun effectiveValue(): String =
        if (metric.startsWith("new_") && period != null) period.value else value
}

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

/** Single-task response from /api/ce/task?id={taskId} */
@Serializable
data class SonarCeTaskResponse(
    val task: SonarCeTaskDto = SonarCeTaskDto()
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

// --- Security Hotspots ---

@Serializable
data class SonarHotspotSearchResult(
    val paging: SonarPagingDto = SonarPagingDto(),
    val hotspots: List<SonarHotspotDto> = emptyList()
)

@Serializable
data class SonarHotspotDto(
    val key: String,
    val message: String,
    val component: String,
    val securityCategory: String = "",
    val vulnerabilityProbability: String = "",  // HIGH, MEDIUM, LOW
    val status: String = "TO_REVIEW",          // TO_REVIEW, REVIEWED
    val resolution: String? = null,            // FIXED, SAFE, ACKNOWLEDGED
    val line: Int? = null,
    val creationDate: String? = null,
    val updateDate: String? = null,
    val author: String? = null
)

// --- Security Hotspot Detail (/api/hotspots/show) ---

/**
 * Full hotspot detail returned by `/api/hotspots/show?hotspot={key}`. Adds
 * to the search-list shape (`SonarHotspotDto`) the rule's risk + fix
 * recommendation HTML — what the agent feeds the LLM for autonomous
 * remediation. `canChangeStatus` is `false` for non-admin tokens, which
 * gates whether the user can mark the hotspot fixed/safe via the API.
 */
@Serializable
data class SonarHotspotDetailDto(
    val key: String = "",
    val component: SonarHotspotComponentDto = SonarHotspotComponentDto(),
    val project: SonarHotspotComponentDto = SonarHotspotComponentDto(),
    val rule: SonarHotspotRuleDto = SonarHotspotRuleDto(),
    val status: String = "",
    val resolution: String? = null,
    val line: Int? = null,
    val message: String = "",
    val assignee: String? = null,
    val author: String? = null,
    val creationDate: String? = null,
    val updateDate: String? = null,
    val canChangeStatus: Boolean = false,
    val textRange: SonarTextRangeDto? = null,
    val changelog: List<SonarHotspotChangelogDto> = emptyList(),
    val comment: List<SonarHotspotCommentDto> = emptyList(),
    val users: List<SonarHotspotUserDto> = emptyList(),
    val codeVariants: List<String> = emptyList()
)

@Serializable
data class SonarHotspotComponentDto(
    val key: String = "",
    val qualifier: String = "",
    val name: String = "",
    val longName: String = "",
    val path: String = "",
    val branch: String = ""
)

@Serializable
data class SonarHotspotRuleDto(
    val key: String = "",
    val name: String = "",
    val securityCategory: String = "",
    val vulnerabilityProbability: String = "",
    /** HTML — "what's the risk this hotspot represents". */
    val riskDescription: String = "",
    /** HTML — "is this code instance vulnerable?". */
    val vulnerabilityDescription: String = "",
    /** HTML — Sonar's curated remediation guidance, contains a literal Compliant Solution code example. */
    val fixRecommendations: String = ""
)

@Serializable
data class SonarHotspotChangelogDto(
    val creationDate: String = "",
    val user: String = "",
    val userName: String = "",
    val diffs: List<SonarHotspotChangelogDiffDto> = emptyList()
)

@Serializable
data class SonarHotspotChangelogDiffDto(
    val key: String = "",
    val newValue: String = "",
    val oldValue: String = ""
)

@Serializable
data class SonarHotspotCommentDto(
    val key: String = "",
    val markdown: String = "",
    val htmlText: String = "",
    val user: String = "",
    val createdAt: String = ""
)

@Serializable
data class SonarHotspotUserDto(
    val login: String = "",
    val name: String = "",
    val active: Boolean = true
)

// --- Issue Search Facets (/api/issues/search?facets=...) ---

/**
 * Sonar field name `val` clashes with Kotlin's reserved `val` keyword,
 * hence `@SerialName("val") val value`. The actual field on the wire
 * is `val`.
 */
@Serializable
data class SonarFacetValueDto(
    @SerialName("val") val value: String = "",
    val count: Int = 0
)

@Serializable
data class SonarFacetDto(
    val property: String = "",
    val values: List<SonarFacetValueDto> = emptyList()
)

// --- Current User (/api/users/current) ---

@Serializable
data class SonarCurrentUserDto(
    val login: String = "",
    val name: String = "",
    val email: String? = null,
    val groups: List<String> = emptyList(),
    val permissions: SonarUserPermissionsDto? = null,
    val externalProvider: String? = null,
    val scmAccounts: List<String> = emptyList(),
    val isLoggedIn: Boolean = false,
    val local: Boolean = false
)

@Serializable
data class SonarUserPermissionsDto(
    val global: List<String> = emptyList()
)

// --- Quality Gates List (/api/qualitygates/list) ---

@Serializable
data class SonarQualityGateListResponse(
    val qualitygates: List<SonarQualityGateListEntryDto> = emptyList()
)

@Serializable
data class SonarQualityGateListEntryDto(
    val name: String = "",
    val isDefault: Boolean = false,
    val isBuiltIn: Boolean = false,
    val caycStatus: String = "",
    val hasStandardConditions: Boolean = false,
    val hasMQRConditions: Boolean = false,
    val isAiCodeSupported: Boolean = false
)

// --- Duplications ---

@Serializable
data class SonarDuplicationsResponse(
    val duplications: List<SonarDuplicationDto> = emptyList(),
    val files: Map<String, SonarDuplicationFileDto> = emptyMap()
)

@Serializable
data class SonarDuplicationDto(
    val blocks: List<SonarDuplicationBlockDto> = emptyList()
)

@Serializable
data class SonarDuplicationBlockDto(
    @SerialName("_ref") val ref: String = "",
    val from: Int = 0,
    val size: Int = 0
)

@Serializable
data class SonarDuplicationFileDto(
    val key: String = "",
    val name: String = "",
    val projectName: String = ""
)

// --- Rule Details ---

@Serializable
data class SonarRuleShowResponseDto(
    val rule: SonarRuleDto
)

/**
 * Sonar 25.x replaced the flat `htmlDesc`/`mdDesc` fields with structured
 * description sections keyed by purpose. Common keys: `root_cause` (the
 * "what / why"), `how_to_fix_it`, `resources`, `introduction`. Security
 * rules tend to ship all four; code-smell rules often ship only
 * `root_cause` + `resources`. Pre-25.x servers omit this list entirely.
 */
@Serializable
data class SonarRuleDescriptionSectionDto(
    val key: String = "",
    val content: String = ""
)

@Serializable
data class SonarRuleDto(
    val key: String,
    val name: String,
    // Pre-25.x: rule description shipped here; null on Sonar 25.x.
    val htmlDesc: String? = null,
    val mdDesc: String? = null,
    val remFnBaseEffort: String? = null,
    val tags: List<String> = emptyList(),
    // Sonar 25.x: structured replacement for htmlDesc/mdDesc.
    val descriptionSections: List<SonarRuleDescriptionSectionDto> = emptyList(),
    // Clean Code taxonomy (Sonar 9.6+ on issues, surfaced on rules in 25.x).
    val cleanCodeAttribute: String? = null,
    val cleanCodeAttributeCategory: String? = null,
    val impacts: List<SonarImpactDto> = emptyList(),
    val educationPrinciples: List<String> = emptyList()
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
    val coveredConditions: Int? = null,
    /** True when this line is part of the new code period (SonarQube 9.x+). */
    val isNew: Boolean? = null
)
