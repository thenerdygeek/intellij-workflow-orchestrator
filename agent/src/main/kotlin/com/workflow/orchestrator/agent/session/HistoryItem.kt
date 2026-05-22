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
    val modelId: String? = null,
    /**
     * Persisted plan-mode toggle for this session. Default false keeps
     * backward-compat: old entries without this field deserialize to false
     * via kotlinx.serialization's missing-key → default semantics.
     */
    val planModeEnabled: Boolean = false,
    /**
     * Non-null when this session was started by an incoming cross-IDE delegation.
     * Nullable default = backward-compatible with existing serialized indexes; old
     * entries without this field deserialize to null.
     *
     * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §9.1.
     * F2: replaces the delegation.json sidecar as the canonical delegation marker in
     * sessions.json so the session-list UI can show a badge without a secondary read.
     */
    val delegated: DelegationMetadata? = null,
)
