package com.workflow.orchestrator.core.model.sonar

import kotlinx.serialization.Serializable

/**
 * Simplified SonarQube issue domain model shared between UI panels and AI agent.
 */
@Serializable
data class SonarIssueData(
    val key: String,
    val rule: String,
    val severity: String,       // "BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO"
    val message: String,
    val component: String,
    val line: Int?,
    val status: String,
    val type: String,           // "BUG", "VULNERABILITY", "CODE_SMELL"
    // SonarQube 9.6+ Clean Code taxonomy. Empty/null on older servers.
    val cleanCodeAttribute: String? = null,        // CLEAR | CONVENTIONAL | FORMATTED | EFFICIENT | LAWFUL | RESPECTFUL | TRUSTWORTHY | DISTINCT | LOGICAL | COMPLETE | IDENTIFIABLE
    val cleanCodeAttributeCategory: String? = null, // CONSISTENT | INTENTIONAL | ADAPTABLE | RESPONSIBLE
    val impacts: List<SonarImpact> = emptyList(),
    val issueStatus: String? = null                // OPEN | FIXED | ACCEPTED | FALSE_POSITIVE — distinct from legacy `status`
) {
    override fun toString(): String {
        val file = component.substringAfterLast(':').substringAfterLast('/')
        val loc = if (line != null) "$file:$line" else file
        val impactStr = if (impacts.isEmpty()) "" else
            " [impacts: ${impacts.joinToString(",") { "${shortQuality(it.softwareQuality)}/${it.severity}" }}]"
        val taxonomy = if (cleanCodeAttributeCategory != null && cleanCodeAttribute != null)
            " [$cleanCodeAttributeCategory/$cleanCodeAttribute]" else ""
        return "[$severity/$type]$impactStr$taxonomy $loc — ${message.take(120)}"
    }

    private fun shortQuality(q: String): String = when (q) {
        "RELIABILITY" -> "REL"
        "SECURITY" -> "SEC"
        "MAINTAINABILITY" -> "MNT"
        else -> q.take(3)
    }
}

/**
 * Per-software-quality severity carried on every Sonar 9.6+ issue. The agent
 * uses this for prioritization (e.g. RELIABILITY/HIGH outranks MAINTAINABILITY/LOW
 * even when both legacy `severity` values are MAJOR).
 */
@Serializable
data class SonarImpact(
    val softwareQuality: String,   // RELIABILITY | SECURITY | MAINTAINABILITY
    val severity: String           // INFO | LOW | MEDIUM | HIGH | BLOCKER
)

/**
 * Quality gate status with individual metric conditions.
 */
@Serializable
data class QualityGateData(
    val status: String,         // "OK", "ERROR"
    val conditions: List<QualityCondition> = emptyList()
) {
    override fun toString(): String = buildString {
        append("Quality Gate: $status")
        if (conditions.isNotEmpty()) {
            append("\n")
            conditions.forEach { c -> append("  ${c.metric}: ${c.value} (${c.status})\n") }
        }
    }.trimEnd()
}

/**
 * A single quality gate condition (metric threshold check).
 */
@Serializable
data class QualityCondition(
    val metric: String,
    val operator: String,
    val value: String,
    val status: String
)

/**
 * Code coverage metrics.
 */
@Serializable
data class CoverageData(
    val lineCoverage: Double,
    val branchCoverage: Double,
    val totalLines: Int,
    val coveredLines: Int
)

/**
 * Simplified SonarQube project domain model for project search/picker.
 */
@Serializable
data class SonarProjectData(
    val key: String,
    val name: String
)

/**
 * SonarQube Compute Engine analysis task data for build correlation.
 */
@Serializable
data class SonarAnalysisTaskData(
    val id: String,
    val status: String,        // SUCCESS, FAILED, PENDING, IN_PROGRESS, CANCELED
    val branch: String?,
    val errorMessage: String?,
    val executionTimeMs: Long?
)

/**
 * SonarQube branch info for a project.
 */
@Serializable
data class SonarBranchData(
    val name: String,
    val isMain: Boolean,
    val type: String,
    val qualityGateStatus: String?
)

/**
 * Project-level aggregate measures (ratings, coverage, debt).
 */
@Serializable
data class ProjectMeasuresData(
    val reliability: String?,
    val security: String?,
    val maintainability: String?,
    val coverage: Double?,
    val duplications: Double?,
    val technicalDebt: String?,
    val linesOfCode: Long?
)

/**
 * A single source line with coverage status. `coverageStatus` ∈ null /
 * "covered" / "uncovered" / "partially-covered". The "partially-covered"
 * value indicates the line itself ran (lineHits > 0) but at least one of
 * its branches was never taken (coveredConditions < conditions) — the
 * agent's "write a test for the missing branch" signal.
 */
@Serializable
data class SourceLineData(
    val line: Int,
    val code: String,
    val coverageStatus: String?,
    val conditions: Int?,
    val coveredConditions: Int?
) {
    override fun toString(): String {
        val cov = when {
            coverageStatus == "partially-covered" && conditions != null ->
                " [partial: ${coveredConditions ?: 0}/$conditions branches]"
            coverageStatus != null -> " [$coverageStatus]"
            else -> ""
        }
        return "${line.toString().padStart(4)}$cov ${code.take(120)}"
    }
}

/**
 * Security hotspot from SonarQube's dedicated hotspot API.
 */
@Serializable
data class SecurityHotspotData(
    val key: String,
    val message: String,
    val component: String,
    val line: Int?,
    val securityCategory: String,
    val probability: String,     // HIGH, MEDIUM, LOW
    val status: String,          // TO_REVIEW, REVIEWED
    val resolution: String?      // FIXED, SAFE, ACKNOWLEDGED
) {
    override fun toString(): String {
        val file = component.substringAfterLast(':').substringAfterLast('/')
        val loc = if (line != null) "$file:$line" else file
        return "[$probability] $loc — ${message.take(120)} ($status)"
    }
}

/**
 * Code duplication details showing which blocks are duplicated across files.
 */
@Serializable
data class DuplicationData(
    val blocks: List<DuplicationBlock>
) {
    override fun toString(): String = buildString {
        append("${blocks.size} duplication group(s)")
        blocks.forEachIndexed { i, block ->
            append("\n  Group ${i + 1}: ${block.fragments.joinToString(" ↔ ") { "${it.file}:${it.startLine}-${it.endLine}" }}")
        }
    }.trimEnd()
}

@Serializable
data class DuplicationBlock(
    val fragments: List<DuplicationFragment>
)

@Serializable
data class DuplicationFragment(
    val file: String,
    val startLine: Int,
    val endLine: Int
)

/**
 * Paged issue results with total count for pagination.
 */
@Serializable
data class PagedIssuesData(
    val issues: List<SonarIssueData>,
    val total: Int,
    val page: Int,
    val pageSize: Int
)

// ── Branch Quality Report (consolidated new-code report) ──────────────────

/**
 * A line range (inclusive) within a file.
 */
@Serializable
data class LineRange(val startLine: Int, val endLine: Int) {
    override fun toString(): String =
        if (startLine == endLine) "$startLine" else "$startLine-$endLine"
}

/**
 * Per-file quality details: exact uncovered lines, uncovered branches, and duplicated blocks.
 */
@Serializable
data class FileQualityReport(
    val filePath: String,
    val lineCoverage: Double?,
    val branchCoverage: Double?,
    val uncoveredLineNumbers: List<Int>,
    val uncoveredBranchLineNumbers: List<Int>,
    val duplicatedLineRanges: List<LineRange>
)

/**
 * Issue count breakdown by type.
 */
@Serializable
data class IssueSummary(
    val bugs: Int,
    val vulnerabilities: Int,
    val codeSmells: Int,
    val total: Int
)

/**
 * New-code coverage summary with uncovered counts.
 */
@Serializable
data class NewCodeCoverageSummary(
    val lineCoverage: Double?,
    val branchCoverage: Double?,
    val newUncoveredLines: Int,
    val newUncoveredConditions: Int,
    val duplicatedLinesDensity: Double?
)

/**
 * Consolidated branch quality report — one tool call gives the LLM everything
 * about new-code quality: quality gate, issues, hotspots, coverage gaps,
 * duplications, with exact line numbers per file.
 */
@Serializable
data class BranchQualityReportData(
    val branch: String,
    val qualityGate: QualityGateData,
    val issueSummary: IssueSummary,
    val issues: List<SonarIssueData>,
    val securityHotspots: List<SecurityHotspotData>,
    val coverageSummary: NewCodeCoverageSummary,
    val fileReports: List<FileQualityReport>,
    val truncatedFiles: Boolean = false
)

/**
 * A file-level component discovered via SonarQube's component_tree API.
 *
 * For multi-module Maven/Gradle projects the [key] is *not* predictable from the file path
 * alone — SonarQube stores it as `projectKey:moduleName:pathWithinModule` (or similar), so
 * callers that need to query per-file endpoints (source_lines, duplications) must resolve
 * the key via this model rather than constructing `"$projectKey:$relativePath"`.
 */
data class SonarFileComponent(
    val key: String,
    val path: String,
    val name: String
)

/**
 * Full security hotspot detail — adds rule risk + fix recommendation HTML
 * to the search-list shape (`SecurityHotspotData`). The three description
 * fields are HTML strings the LLM consumes verbatim.
 *
 * `canChangeStatus` reflects the active token's permissions. When `false`,
 * the agent CANNOT directly mark the hotspot fixed/safe via the API —
 * remediation flow is: edit code → push → wait for re-analysis. The agent
 * system prompt documents this constraint so the LLM doesn't promise the
 * user it can close hotspots autonomously.
 */
@Serializable
data class HotspotDetailData(
    val key: String,
    val componentKey: String,
    val componentPath: String,
    val projectKey: String,
    val ruleKey: String,
    val ruleName: String,
    val securityCategory: String,
    val vulnerabilityProbability: String,
    val riskDescription: String,
    val vulnerabilityDescription: String,
    val fixRecommendations: String,
    val status: String,
    val resolution: String?,
    val line: Int?,
    val message: String,
    val assignee: String?,
    val author: String?,
    val canChangeStatus: Boolean
) {
    /**
     * Compact one-line + structured-meta rendering. The three HTML description
     * fields (`riskDescription`, `vulnerabilityDescription`, `fixRecommendations`)
     * are kept ONLY in the structured data field — they each run 1-3 KB on the
     * probed Sonar 25.x sample, and dumping all three into LLM context per call
     * would blow the budget. The LLM sees what's actionable: rule, severity,
     * canChangeStatus warning, location, char-counts that confirm the body is
     * available to read out of `data` if needed.
     */
    override fun toString(): String = buildString {
        val resStr = resolution?.let { "/$it" } ?: ""
        append("Hotspot $key — $ruleKey [$vulnerabilityProbability/$status$resStr]")
        val file = componentPath.ifBlank { componentKey.substringAfterLast(':') }.substringAfterLast('/')
        val loc = if (line != null) "$file:$line" else file
        if (loc.isNotBlank()) append(" at $loc")
        if (ruleName.isNotBlank()) append("\n  Rule: $ruleName")
        if (securityCategory.isNotBlank()) append("\n  Category: $securityCategory")
        if (message.isNotBlank()) append("\n  Message: ${message.take(200)}")
        if (!canChangeStatus) {
            append("\n  ⚠ canChangeStatus=false: token cannot mark fixed/safe via API; remediation = edit code → push → wait for re-analysis")
        }
        val descBytes = riskDescription.length + vulnerabilityDescription.length + fixRecommendations.length
        if (descBytes > 0) {
            append("\n  (rule HTML in data: risk=${riskDescription.length}, vulnerability=${vulnerabilityDescription.length}, fix=${fixRecommendations.length} chars)")
        }
    }
}

/**
 * Issue facet counts — one round trip yields the breakdown by severity,
 * type, software quality, file, etc. for a project's open issues. Empty
 * `values` list = facet was requested but no matching issues exist.
 */
@Serializable
data class IssueFacetsData(
    val total: Int,
    val facets: List<IssueFacet>
)

@Serializable
data class IssueFacet(
    val property: String,
    val values: List<IssueFacetValue>
)

@Serializable
data class IssueFacetValue(
    val value: String,
    val count: Int
)

/**
 * SonarQube authenticated user identity + permissions. Used by the
 * settings-page identity badge and to conditionally gate the
 * "Administer Project required" hint (admins don't need to be told).
 */
@Serializable
data class SonarCurrentUserData(
    val login: String,
    val name: String,
    val email: String?,
    val groups: List<String>,
    val globalPermissions: List<String>,
    val externalProvider: String?,
    val isLoggedIn: Boolean
) {
    /**
     * True only when the token holds SonarQube's global `admin` permission. Previously this
     * returned true for ANY global permission (e.g. `scan`/`provisioning`), wrongly reporting
     * a non-admin token as admin and contradicting the "403 on admin-only actions ⇒ not admin"
     * guidance the tool gives the LLM.
     */
    val isAdmin: Boolean get() = globalPermissions.any { it.equals("admin", ignoreCase = true) }
}

/**
 * Quality gate registry entry from `/api/qualitygates/list`. The plugin's
 * `getQualityGateStatus` returns project-status; this is the catalog of
 * available gates with their CaYC compliance and AI-Code-Fix support flag.
 */
@Serializable
data class SonarQualityGateListData(
    val gates: List<SonarQualityGateEntry>
) {
    /**
     * Default-class toString would dump every gate inline (17 entries on the
     * probed Sonar). Hand-written rendering surfaces what's actionable for the
     * agent: which gate is the project's default, which gates fail CaYC
     * compliance, AI Code Fix availability counter.
     */
    override fun toString(): String = buildString {
        append("${gates.size} quality gate(s)")
        gates.firstOrNull { it.isDefault }?.let { append(" — default: \"${it.name}\" (${it.caycStatus.ifBlank { "?" }})") }
        val nonCompliant = gates.filter { it.caycStatus == "non-compliant" }
        if (nonCompliant.isNotEmpty()) {
            append("\n  Non-compliant gates: ${nonCompliant.joinToString(", ") { it.name }}")
        }
        val aiCount = gates.count { it.isAiCodeSupported }
        append("\n  AI Code Fix supported: $aiCount of ${gates.size}")
    }
}

@Serializable
data class SonarQualityGateEntry(
    val name: String,
    val isDefault: Boolean,
    val isBuiltIn: Boolean,
    val caycStatus: String,
    val hasStandardConditions: Boolean,
    val hasMQRConditions: Boolean,
    val isAiCodeSupported: Boolean
)
