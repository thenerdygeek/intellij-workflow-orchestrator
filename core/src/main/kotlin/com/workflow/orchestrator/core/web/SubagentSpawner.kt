package com.workflow.orchestrator.core.web

import com.intellij.openapi.project.Project

/**
 * Bridge for :web to spawn a subagent without depending on :agent. The :agent module
 * registers a project service implementing this interface; it wraps the existing
 * `agent/tools/subagent/SubagentRunner`.
 */
interface SubagentSpawner {
    suspend fun runSanitizer(
        project: Project,
        brainId: String?,
        systemPrompt: String,
        userPrompt: String,
        timeoutMs: Long,
    ): SanitizerResult

    /**
     * Batch variant of [runSanitizer]. Sends all [expectedCount] texts in a single LLM call
     * and parses the result as `{"results":[{"verdict","cleaned_text","notes"}, ...]}`.
     *
     * On parse failure the caller receives a list of length [expectedCount] where every entry
     * has verdict=STRIPPED, cleanedText="" and notes="batch parse failed", so downstream
     * code can always assume the list length equals [expectedCount].
     */
    suspend fun runSanitizerBatch(
        project: Project,
        brainId: String?,
        systemPrompt: String,
        userPrompt: String,
        timeoutMs: Long,
        expectedCount: Int,
    ): List<SanitizerResult>

    data class SanitizerResult(
        val verdict: Verdict,
        val cleanedText: String,
        val notes: String?,
    )

    enum class Verdict {
        SAFE,
        STRIPPED,
        REFUSED,
        TIMEOUT,
        /**
         * The sanitizer subagent returned a verdict string that is not one of the recognised
         * values (SAFE / STRIPPED / REFUSED / TIMEOUT). Treat as fail-closed: do not pass
         * the sanitizer's cleaned_text to the main agent.
         */
        UNRECOGNISED,
    }
}
