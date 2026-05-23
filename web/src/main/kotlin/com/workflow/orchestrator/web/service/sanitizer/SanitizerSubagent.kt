package com.workflow.orchestrator.web.service.sanitizer

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.web.SubagentSpawner

class SanitizerSubagent(private val spawner: SubagentSpawner) {

    suspend fun sanitize(
        project: Project,
        extractedText: String,
        brainId: String?,
        timeoutMs: Long,
    ): SubagentSpawner.SanitizerResult {
        val system = loadSystemPrompt()
        val user = "Source-of-truth text follows between <input> tags.\n" +
                   "Return only the JSON object specified in your instructions.\n" +
                   "<input>\n$extractedText\n</input>"
        return spawner.runSanitizer(
            project = project,
            brainId = brainId,
            systemPrompt = system,
            userPrompt = user,
            timeoutMs = timeoutMs,
        )
    }

    /**
     * Batch sanitization for a list of snippets. Sends all texts in a single LLM call and
     * returns one [SubagentSpawner.SanitizerResult] per input in the same order.
     *
     * On empty input returns an empty list immediately (no LLM call).
     */
    suspend fun sanitizeBatch(
        project: Project,
        texts: List<String>,
        brainId: String?,
        timeoutMs: Long,
    ): List<SubagentSpawner.SanitizerResult> {
        if (texts.isEmpty()) return emptyList()
        val combined = buildString {
            appendLine("Sanitize the following ${texts.size} snippets.")
            appendLine("""Return JSON: {"results":[{"verdict":..., "cleaned_text":..., "notes":...}, ...]} in the SAME order.""")
            texts.forEachIndexed { i, t -> appendLine("<snippet i='$i'>$t</snippet>") }
        }
        return spawner.runSanitizerBatch(
            project = project,
            brainId = brainId,
            systemPrompt = loadSystemPrompt(),
            userPrompt = combined,
            timeoutMs = timeoutMs,
            expectedCount = texts.size,
        )
    }

    private fun loadSystemPrompt(): String =
        javaClass.getResourceAsStream("/personas/sanitizer-system-prompt.txt")
            ?.bufferedReader()?.readText()
            ?: error("sanitizer-system-prompt.txt resource missing")
}
