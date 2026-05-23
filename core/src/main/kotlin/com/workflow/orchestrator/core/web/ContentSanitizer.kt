package com.workflow.orchestrator.core.web

interface ContentSanitizer {
    /**
     * Strip executable / dangerous structures from raw bytes and produce safe text.
     * Pure / deterministic — no LLM here.
     */
    fun sanitize(
        rawBytes: ByteArray,
        contentType: String,
        sourceUrl: String,
        maxExtractedChars: Int,
    ): SanitizeResult

    data class SanitizeResult(
        val extractedText: String,
        val truncated: Boolean,
        val originalChars: Int,
    )
}
