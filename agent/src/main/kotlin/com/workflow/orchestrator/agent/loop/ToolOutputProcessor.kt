package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.tools.ToolOutputConfig
import com.workflow.orchestrator.agent.tools.ToolOutputSpiller

/** Result of post-processing a tool's raw output. */
data class ProcessedToolResult(val content: String, val tokenEstimate: Int, val wasProcessed: Boolean)

/**
 * Pure post-processing of a tool's raw output: optional grep filter, disk spill, middle-truncation, and
 * token re-estimation. Extracted verbatim from AgentLoop.executeToolCalls (was inline ~2111-2137) so the
 * inline path and the background-completion path produce IDENTICAL content. `truncate`/`estimateTokens`
 * are injected (they are AgentLoop helpers) so this stays unit-testable without constructing AgentLoop.
 */
object ToolOutputProcessor {
    suspend fun process(
        toolName: String,
        rawContent: String,
        rawTokenEstimate: Int,
        grepPattern: String?,
        requestedOutputFile: Boolean,
        maxChars: Int,
        spiller: ToolOutputSpiller?,
        truncate: (String, Int) -> String,
        estimateTokens: (String) -> Int,
    ): ProcessedToolResult {
        var processedContent = rawContent
        if (!grepPattern.isNullOrBlank() && processedContent.isNotBlank()) {
            processedContent = ToolOutputConfig.applyGrep(processedContent, grepPattern)
        }
        if (spiller != null && (requestedOutputFile || processedContent.length > ToolOutputConfig.SPILL_THRESHOLD_CHARS)) {
            processedContent = spiller.spill(toolName, processedContent).preview
        }
        val truncatedContent = truncate(processedContent, maxChars)
        val tokenEstimate = when {
            truncatedContent.length < processedContent.length -> estimateTokens(truncatedContent)
            processedContent.length < rawContent.length -> estimateTokens(processedContent)
            else -> rawTokenEstimate
        }
        return ProcessedToolResult(
            content = truncatedContent,
            tokenEstimate = tokenEstimate,
            wasProcessed = truncatedContent != rawContent,
        )
    }
}
