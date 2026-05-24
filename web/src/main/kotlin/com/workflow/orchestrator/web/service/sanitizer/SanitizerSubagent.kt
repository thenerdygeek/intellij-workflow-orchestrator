package com.workflow.orchestrator.web.service.sanitizer

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.web.SubagentSpawner
import java.util.UUID

class SanitizerSubagent(private val spawner: SubagentSpawner) {

    suspend fun sanitize(
        project: Project,
        extractedText: String,
        brainId: String?,
        timeoutMs: Long,
    ): SubagentSpawner.SanitizerResult {
        val system = loadSystemPrompt()
        // I9 — random per-call delimiter so attacker text containing a literal
        // `</input>` cannot terminate the sanitizer prompt boundary. The sanitizer
        // subagent is an LLM, not an XML parser, so it understands `<input-XXXX>`
        // just fine — and forging an 8-hex-char nonce is computationally infeasible
        // for content the attacker doesn't see in advance.
        val delim = randomDelim()
        val user = "Source-of-truth text follows between <input-$delim> tags.\n" +
                   "Return only the JSON object specified in your instructions.\n" +
                   "<input-$delim>\n$extractedText\n</input-$delim>"
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
        // I9 — random per-call snippet delimiter (same rationale as `sanitize` above).
        val delim = randomDelim()
        val combined = buildString {
            appendLine("Sanitize the following ${texts.size} snippets.")
            appendLine("""Return JSON: {"results":[{"verdict":..., "cleaned_text":..., "notes":...}, ...]} in the SAME order.""")
            texts.forEachIndexed { i, t -> appendLine("<snippet-$delim i='$i'>$t</snippet-$delim>") }
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

    /** 8-char hex delim from a fresh UUID. Per-call nonce — computationally infeasible to forge. */
    private fun randomDelim(): String = UUID.randomUUID().toString().take(8).filter { it.isLetterOrDigit() }
}
