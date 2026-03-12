package com.workflow.orchestrator.mockserver.sonar

import kotlinx.serialization.Serializable

@Serializable
data class SonarProject(
    val key: String,
    val name: String,
)

@Serializable
data class SonarIssue(
    val key: String,
    val rule: String,
    val severity: String,
    val type: String,
    val message: String,
    val component: String,
    val line: Int?,
    val status: String = "OPEN",
)

@Serializable
data class SonarQualityGate(
    val status: String,
    val conditions: List<SonarCondition>,
)

@Serializable
data class SonarCondition(
    val status: String,
    val metricKey: String,
    val comparator: String,
    val errorThreshold: String? = null,
    val warningThreshold: String? = null,
    val actualValue: String,
)

@Serializable
data class SonarMeasure(
    val component: String,
    val metricKey: String,
    val value: String,
)

@Serializable
data class SonarSourceLine(
    val line: Int,
    val code: String,
    val covered: Boolean? = null,
)

class SonarState {
    var authValid: Boolean = true
    var projects: MutableList<SonarProject> = mutableListOf()
    var issues: MutableList<SonarIssue> = mutableListOf()
    var qualityGate: SonarQualityGate? = null
    var measures: MutableList<SonarMeasure> = mutableListOf()
    var sourceLines: Map<String, List<SonarSourceLine>> = emptyMap()
}
