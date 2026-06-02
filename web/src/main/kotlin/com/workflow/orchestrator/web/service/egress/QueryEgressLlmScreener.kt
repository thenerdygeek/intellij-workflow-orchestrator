package com.workflow.orchestrator.web.service.egress

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.web.QueryEgressFilter
import com.workflow.orchestrator.core.web.SubagentSpawner
import java.util.UUID

/**
 * Stage 1 of the egress filter: an LLM judges whether the query contains proprietary
 * identifiers the deny-list missed. Uses the same [SubagentSpawner.runSanitizer]
 * machinery as the inbound sanitizer (it's an LLM-call abstraction, not sanitizer-
 * specific) with the [egress-screener-system-prompt.txt] persona.
 *
 * Maps SubagentSpawner verdicts onto egress decisions:
 *   SAFE         → Decision.Safe(originalQuery)
 *   STRIPPED     → Decision.Rewritten(newQuery, originalQuery, note)
 *   REFUSED      → Decision.Blocked("EGRESS_SCREENER_UNAVAILABLE", maskFirstToken(originalQuery))
 *   TIMEOUT      → Decision.Blocked("EGRESS_SCREENER_UNAVAILABLE", maskFirstToken(originalQuery))  // fail-closed
 *   UNRECOGNISED → Decision.Blocked("EGRESS_SCREENER_UNAVAILABLE", ...)                            // fail-closed
 *
 * The persona uses verdict labels SAFE/STRIPPED/REFUSED (matching SubagentSpawner.Verdict)
 * even though the egress concept-words are "safe/rewritten/blocked" — keeps the parser path
 * unchanged. The semantic mapping happens here in the wrapper.
 */
class QueryEgressLlmScreener(
    private val spawner: SubagentSpawner,
    private val brainId: String?,
    private val timeoutMs: Long,
) {

    suspend fun screen(project: Project, query: String): QueryEgressFilter.Decision {
        val system = loadSystemPrompt()
        val delim = randomDelim()
        val user = "Source-of-truth query follows between <query-$delim> tags.\n" +
                   "Return only the JSON object specified in your instructions.\n" +
                   "<query-$delim>\n$query\n</query-$delim>"
        val result = spawner.runSanitizer(
            project = project,
            brainId = brainId,
            systemPrompt = system,
            userPrompt = user,
            timeoutMs = timeoutMs,
        )
        return when (result.verdict) {
            SubagentSpawner.Verdict.SAFE -> QueryEgressFilter.Decision.Safe(query)
            SubagentSpawner.Verdict.STRIPPED -> QueryEgressFilter.Decision.Rewritten(
                query = result.cleanedText.ifBlank { query },
                original = query,
                note = result.notes.orEmpty().ifBlank { "rewritten by egress screener" },
            )
            SubagentSpawner.Verdict.REFUSED,
            SubagentSpawner.Verdict.TIMEOUT,
            SubagentSpawner.Verdict.UNRECOGNISED ->
                QueryEgressFilter.Decision.Blocked("EGRESS_SCREENER_UNAVAILABLE", maskFirstToken(query))
        }
    }

    private fun loadSystemPrompt(): String =
        javaClass.getResourceAsStream("/personas/egress-screener-system-prompt.txt")
            ?.bufferedReader()?.readText()
            ?: error("egress-screener-system-prompt.txt resource missing")

    private fun randomDelim(): String =
        UUID.randomUUID().toString().take(8).filter { it.isLetterOrDigit() }

    private fun maskFirstToken(query: String): String {
        val first = query.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        return if (first.length >= 6) first.take(3) + "***" else "***"
    }
}
