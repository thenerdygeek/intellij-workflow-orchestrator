package com.workflow.orchestrator.sonar.model

import com.workflow.orchestrator.core.model.sonar.SecurityHotspotData
import java.time.Instant

data class SonarState(
    val projectKey: String,
    val branch: String,
    val qualityGate: QualityGateState,
    val issues: List<MappedIssue>,
    val fileCoverage: Map<String, FileCoverageData>,
    val overallCoverage: CoverageMetrics,
    val lastUpdated: Instant,

    // New code data (cached alongside overall data)
    val newCodeMode: Boolean = true,
    val newCodeIssues: List<MappedIssue> = emptyList(),
    val newCodeFileCoverage: Map<String, FileCoverageData> = emptyMap(),
    val newCodeOverallCoverage: CoverageMetrics = CoverageMetrics(0.0, 0.0),
    val newCodeIssueCounts: IssueCounts = IssueCounts(),
    val overallIssueCounts: IssueCounts = IssueCounts(),
    val branches: List<SonarBranch> = emptyList(),
    val currentBranchAnalyzed: Boolean = false,
    val currentBranchAnalysisDate: String? = null,
    val recentAnalyses: List<SonarAnalysisTask> = emptyList(),
    val newCodePeriod: NewCodePeriod? = null,
    val lastAnalysisForBranch: SonarAnalysisTask? = null,
    val totalIssueCount: Int? = null,
    val totalNewCodeIssueCount: Int? = null,
    val totalCoverageFileCount: Int? = null,
    val projectHealth: ProjectHealthMetrics = ProjectHealthMetrics(),
    val securityHotspots: List<SecurityHotspotData> = emptyList()
) {
    /** Returns the active issues based on the current mode. */
    val activeIssues: List<MappedIssue>
        get() = if (newCodeMode) newCodeIssues else issues

    /** Returns the active file coverage based on the current mode. */
    val activeFileCoverage: Map<String, FileCoverageData>
        get() = if (newCodeMode) newCodeFileCoverage else fileCoverage

    /** Returns the active overall coverage based on the current mode. */
    val activeOverallCoverage: CoverageMetrics
        get() = if (newCodeMode) newCodeOverallCoverage else overallCoverage

    /** Returns the active issue counts based on the current mode. */
    val activeIssueCounts: IssueCounts
        get() = if (newCodeMode) newCodeIssueCounts else overallIssueCounts

    /** Returns the total issue count from the server (null if unknown). */
    val activeTotalIssueCount: Int?
        get() = if (newCodeMode) totalNewCodeIssueCount else totalIssueCount

    /** Whether the issue list is truncated (more issues exist than returned). */
    val issuesTruncated: Boolean
        get() {
            val total = activeTotalIssueCount ?: return false
            return total > activeIssues.size
        }

    companion object {
        val EMPTY = SonarState(
            projectKey = "",
            branch = "",
            qualityGate = QualityGateState(QualityGateStatus.NONE, emptyList()),
            issues = emptyList(),
            fileCoverage = emptyMap(),
            overallCoverage = CoverageMetrics(0.0, 0.0),
            lastUpdated = Instant.EPOCH
        )
    }
}
