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

@Serializable
data class SonarHotspot(
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
    val author: String? = null,
)

@Serializable
data class SonarDuplication(
    val blocks: List<SonarDuplicationBlock> = emptyList(),
)

@Serializable
data class SonarDuplicationBlock(
    val ref: String = "",
    val from: Int = 0,
    val size: Int = 0,
)

@Serializable
data class SonarDuplicationFile(
    val key: String = "",
    val name: String = "",
    val projectName: String = "",
)

@Serializable
data class SonarBranch(
    val name: String,
    val isMain: Boolean = false,
    val type: String = "BRANCH",
    val qualityGateStatus: String = "",
    val bugs: Int? = null,
    val vulnerabilities: Int? = null,
    val codeSmells: Int? = null,
    val analysisDate: String? = null,
)

@Serializable
data class SonarCeTask(
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
    val hasErrorStacktrace: Boolean = false,
)

@Serializable
data class SonarNewCodePeriod(
    val projectKey: String = "",
    val branchKey: String = "",
    val type: String = "",             // PREVIOUS_VERSION, NUMBER_OF_DAYS, REFERENCE_BRANCH, SPECIFIC_ANALYSIS
    val value: String = "",
    val effectiveValue: String = "",
    val inherited: Boolean = false,
)

@Serializable
data class SonarProjectMeasure(
    val metric: String,
    val value: String = "",
)

class SonarState {
    var authValid: Boolean = true
    var projects: MutableList<SonarProject> = mutableListOf()
    var issues: MutableList<SonarIssue> = mutableListOf()
    var qualityGate: SonarQualityGate? = null
    var measures: MutableList<SonarMeasure> = mutableListOf()
    var sourceLines: Map<String, List<SonarSourceLine>> = emptyMap()
    var hotspots: MutableList<SonarHotspot> = mutableListOf()
    var duplications: Map<String, List<SonarDuplication>> = emptyMap()
    var duplicationFiles: Map<String, SonarDuplicationFile> = emptyMap()
    var branches: MutableList<SonarBranch> = mutableListOf()
    var ceTasks: MutableList<SonarCeTask> = mutableListOf()
    var newCodePeriod: SonarNewCodePeriod? = null
    var projectMeasures: MutableList<SonarProjectMeasure> = mutableListOf()
}
