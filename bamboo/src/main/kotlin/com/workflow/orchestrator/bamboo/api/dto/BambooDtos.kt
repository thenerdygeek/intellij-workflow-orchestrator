package com.workflow.orchestrator.bamboo.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Plan DTOs ---

@Serializable
data class BambooPlanListResponse(
    val plans: BambooPlanCollection = BambooPlanCollection()
)

@Serializable
data class BambooPlanCollection(
    val size: Int = 0,
    @SerialName("max-result") val maxResult: Int = 25,
    @SerialName("start-index") val startIndex: Int = 0,
    val plan: List<BambooPlanDto> = emptyList()
)

@Serializable
data class BambooPlanDto(
    val key: String,
    val shortKey: String = "",
    val name: String,
    val shortName: String = "",
    val enabled: Boolean = true,
    val type: String = "chain"
)

// --- Branch DTOs ---

@Serializable
data class BambooBranchListResponse(
    val branches: BambooBranchCollection = BambooBranchCollection()
)

@Serializable
data class BambooBranchCollection(
    val size: Int = 0,
    @SerialName("max-result") val maxResult: Int = 25,
    @SerialName("start-index") val startIndex: Int = 0,
    val branch: List<BambooBranchDto> = emptyList()
)

@Serializable
data class BambooBranchDto(
    val key: String,
    val name: String = "",
    val shortName: String = "",
    val enabled: Boolean = true
)

// --- Build Result DTOs ---

@Serializable
data class BambooResultResponse(
    val results: BambooResultCollection = BambooResultCollection()
)

@Serializable
data class BambooResultCollection(
    val size: Int = 0,
    val result: List<BambooResultDto> = emptyList()
)

@Serializable
data class BambooResultDto(
    val key: String = "",
    val buildNumber: Int = 0,
    val state: String = "",               // Successful, Failed, Unknown
    val lifeCycleState: String = "",      // Queued, Pending, InProgress, Finished
    val buildDurationInSeconds: Long = 0,
    val buildRelativeTime: String = "",
    val plan: BambooPlanDto? = null,
    val stages: BambooStageCollection = BambooStageCollection()
)

@Serializable
data class BambooStageCollection(
    val size: Int = 0,
    val stage: List<BambooStageDto> = emptyList()
)

@Serializable
data class BambooStageDto(
    val name: String,
    val state: String = "",               // Successful, Failed, Unknown
    val lifeCycleState: String = "",      // Queued, Pending, InProgress, Finished
    val manual: Boolean = false,
    val buildDurationInSeconds: Long = 0
)

// --- Plan Variables DTOs ---

@Serializable
data class BambooVariableListResponse(
    val variables: BambooVariableCollection = BambooVariableCollection()
)

@Serializable
data class BambooVariableCollection(
    val size: Int = 0,
    val variable: List<BambooPlanVariableDto> = emptyList()
)

@Serializable
data class BambooPlanVariableDto(
    val name: String,
    val value: String = ""
)

// --- Search DTOs ---

@Serializable
data class BambooSearchResponse(
    val size: Int = 0,
    @SerialName("max-result") val maxResult: Int = 25,
    @SerialName("start-index") val startIndex: Int = 0,
    val searchResults: List<BambooSearchResultItem> = emptyList()
)

@Serializable
data class BambooSearchResultItem(
    val searchEntity: BambooSearchEntity
)

@Serializable
data class BambooSearchEntity(
    val key: String,
    val planName: String = "",
    val projectName: String = "",
    val type: String = ""
)

// --- Queue (Trigger) DTOs ---

@Serializable
data class BambooQueueResponse(
    val triggerReason: String = "",
    val buildNumber: Int = 0,
    val buildResultKey: String = "",
    val planKey: String = ""
)

// --- Build Status DTOs (Phase 2A: Automation) ---

/** Wraps result list for running/queued builds query */
@Serializable
data class BambooBuildStatusResponse(
    val results: BambooResultCollection = BambooResultCollection()
)

/** Wraps variables attached to a specific build result */
@Serializable
data class BambooBuildVariablesResponse(
    val key: String = "",
    val buildNumber: Int = 0,
    val variables: BambooVariableCollection = BambooVariableCollection()
)
