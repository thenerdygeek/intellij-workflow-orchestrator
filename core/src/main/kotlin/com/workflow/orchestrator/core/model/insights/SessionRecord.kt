package com.workflow.orchestrator.core.model.insights

import kotlinx.serialization.Serializable

@Serializable
data class SessionRecord(
    val id: String,
    val ts: Long,
    val task: String,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val totalCost: Double = 0.0,
    val modelId: String? = null,
    val isFavorited: Boolean = false,
)
