package com.workflow.orchestrator.web.audit

import com.workflow.orchestrator.core.model.web.AllowlistDecision
import com.workflow.orchestrator.core.model.web.SanitizerVerdict
import java.time.Instant

data class WebAuditRecord(
    val ts: Instant,
    val op: String,                   // "fetch" | "search"
    val agentSessionId: String?,
    val url: String,
    val finalUrl: String?,
    val query: String?,
    /**
     * The user-typed (or LLM-generated) query BEFORE the egress filter ran. Null for fetch
     * operations and for search audit entries written before the egress filter shipped.
     * Useful for post-hoc "what did we almost send?" investigations — pair with
     * [egressDecision] to see what the filter caught.
     */
    val queryBeforeFilter: String? = null,
    /**
     * The egress-filter outcome string. One of: "SAFE" / "DENYLIST_BLOCKED" /
     * "LLM_REWRITTEN" / "LLM_SCREENER_UNAVAILABLE". Null when the egress filter did
     * not run (fetch operations, search before egress filter shipped).
     */
    val egressDecision: String? = null,
    val provider: String?,
    val allowlistDecision: AllowlistDecision?,
    val screenerFlags: List<String>,
    val ssrfPass: Boolean,
    val httpStatus: Int?,
    val contentType: String?,
    val responseBytes: Long?,
    val extractedChars: Int?,
    val resultCount: Int?,
    val sanitizerVerdict: SanitizerVerdict?,
    val sanitizerNotes: String?,
    val elapsedMs: Long,
    val error: String?,
    /**
     * True when this fetch was served from the in-memory cache (no network call,
     * no sanitizer subagent). Defaults to false so existing entries deserialize unchanged.
     */
    val cacheHit: Boolean = false,
)
