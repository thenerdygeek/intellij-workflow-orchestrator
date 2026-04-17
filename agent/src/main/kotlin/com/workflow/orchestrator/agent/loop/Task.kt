package com.workflow.orchestrator.agent.loop

import kotlinx.serialization.Serializable

@Serializable
enum class TaskStatus { PENDING, IN_PROGRESS, COMPLETED, DELETED }

@Serializable
data class Task(
    val id: String,
    val subject: String,
    val description: String,
    val activeForm: String? = null,
    val status: TaskStatus = TaskStatus.PENDING,
    val owner: String? = null,
    val blocks: List<String> = emptyList(),
    val blockedBy: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
