package com.workflow.orchestrator.handover.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class JiraCommentResponse(
    val id: String,
    val body: String,
    val created: String? = null
)

@Serializable
data class JiraTransitionsResponse(
    val transitions: List<JiraTransition>
)

@Serializable
data class JiraTransition(
    val id: String,
    val name: String,
    val to: JiraTransitionTarget? = null
)

@Serializable
data class JiraTransitionTarget(
    val id: String,
    val name: String
)
