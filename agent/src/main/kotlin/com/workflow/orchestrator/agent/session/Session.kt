package com.workflow.orchestrator.agent.session

import kotlinx.serialization.Serializable

@Serializable
data class Session(
    val id: String,
    val title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastMessageAt: Long = createdAt,
    val messageCount: Int = 0,
    val status: SessionStatus = SessionStatus.ACTIVE,
    val totalTokens: Int = 0
)

@Serializable
enum class SessionStatus { ACTIVE, COMPLETED, FAILED, CANCELLED }
