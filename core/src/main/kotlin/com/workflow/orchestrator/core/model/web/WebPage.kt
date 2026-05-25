package com.workflow.orchestrator.core.model.web

import com.workflow.orchestrator.core.web.UrlScreener
import java.time.Instant

data class WebPage(
    val originalUrl: String,
    val finalUrl: String,
    val contentType: String,
    val responseBytes: Long,
    val extractedText: String,
    val extractedChars: Int,
    val screenerFlags: Set<UrlScreener.Flag>,
    val allowlistDecision: AllowlistDecision,
    val sanitizerVerdict: SanitizerVerdict,
    val sanitizerNotes: String?,
    val contentHash: String = "",
    val fetchedAt: Instant,
    val elapsedMs: Long,
)

enum class AllowlistDecision {
    APPROVED_AUTO,           // host was already on allowlist
    APPROVED_PROMPT,         // user clicked Allow once or Add to allowlist
    UNLISTED_HARD_REJECT,    // settings.webUnlistedPolicy = REJECT, never reached HTTP
    DENIED,                  // user clicked Deny
    TIMED_OUT,               // approval dialog timed out
}

enum class SanitizerVerdict {
    SAFE,
    STRIPPED,                // sanitizer removed some content but returned clean text
    REFUSED,                 // sanitizer refused — no text returned
    STRUCTURAL_ONLY,         // sanitizer subagent timed out; fail-open path
}
