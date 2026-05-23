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
    /**
     * Most-recently-observed remote state (RUNNING / AWAITING_ANSWER / etc.).
     * Updated on every received `Result` / `Question` / `Heartbeat`. Sent in
     * `ChannelResume` requests as a diagnostic; never authoritative.
     *
     * Default "unknown" so existing serialized handles deserialize cleanly.
     *
     * Plan 4 spec §4.2.
     */
    val lastSeenState: String = "unknown",
)
