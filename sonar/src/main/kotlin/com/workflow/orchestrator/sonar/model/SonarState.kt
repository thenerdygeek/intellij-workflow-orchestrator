package com.workflow.orchestrator.sonar.model

import java.time.Instant

data class SonarState(
    val projectKey: String,
    val branch: String,
    val qualityGate: QualityGateState,
    val issues: List<MappedIssue>,
    val fileCoverage: Map<String, FileCoverageData>,
    val overallCoverage: CoverageMetrics,
    val lastUpdated: Instant
) {
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
