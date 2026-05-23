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

    data class SanitizerResult(
        val verdict: Verdict,
        val cleanedText: String,
        val notes: String?,
    )

    enum class Verdict { SAFE, STRIPPED, REFUSED, TIMEOUT }
}
