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
)
