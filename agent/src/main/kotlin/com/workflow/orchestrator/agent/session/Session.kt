package com.workflow.orchestrator.agent.session

import kotlinx.serialization.Serializable

@Serializable
data class Session(
    val id: String,
    val title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    var lastMessageAt: Long = createdAt,
    var messageCount: Int = 0,
    var status: SessionStatus = SessionStatus.ACTIVE,
    var totalTokens: Int = 0
)

@Serializable
enum class SessionStatus { ACTIVE, COMPLETED, FAILED, CANCELLED }
