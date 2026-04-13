package com.workflow.orchestrator.agent.session

import kotlinx.serialization.Serializable

@Serializable
data class HistoryItem(
    val id: String,
    val ts: Long,
    val task: String,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val cacheWrites: Long? = null,
    val cacheReads: Long? = null,
    val totalCost: Double = 0.0,
    val size: Long? = null,
    val cwdOnTaskInitialization: String? = null,
    val conversationHistoryDeletedRange: List<Int>? = null,
    val isFavorited: Boolean = false,
    val checkpointManagerErrorMessage: String? = null,
    val modelId: String? = null,
)
