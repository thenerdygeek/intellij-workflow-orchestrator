package com.workflow.orchestrator.agent.delegation

import kotlinx.serialization.Serializable

/**
 * Opaque handle that the LLM holds to reference an open delegation channel.
 * Returned by [DelegationOutboundService.send] and consumed by
 * [DelegationOutboundService.close].
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §3.3, §4.1.
 */
@Serializable
data class DelegationHandle(
    val id: String,
    val targetProjectPath: String,
    val targetRepoName: String,
    val createdAt: Long = System.currentTimeMillis(),
)
