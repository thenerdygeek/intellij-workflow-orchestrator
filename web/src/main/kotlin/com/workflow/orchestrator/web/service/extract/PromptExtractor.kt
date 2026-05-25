package com.workflow.orchestrator.web.service.extract

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.web.SubagentSpawner
import java.util.UUID

/**
 * Page-prompt fusion: a 2nd LLM call after the inbound sanitizer that pre-extracts a
 * targeted answer to a user question from the cleaned source text.
 *
 * Architecture: a SECOND call, not a fusion of sanitize+extract. Conflating the two jobs
 * was the failure mode the Phase A persona rewrite fixed (the sanitizer would paraphrase
 * "AWS Lambda" into "a cloud function service"). Two calls cost ~2x but keep the contract:
 * sanitizer = filter, extractor = editor.
 *
 * Verdict mapping:
 *   SAFE         -> Result.Complete(answer)
 *   STRIPPED     -> Result.Partial(answer, note)
 *   REFUSED      -> Result.NoAnswer(reason)
 *   TIMEOUT      -> Result.NoAnswer("extractor timed out")
 *   UNRECOGNISED -> Result.NoAnswer("extractor returned unrecognised verdict")  // fail-closed
 *
 * `open` so PromptExtractorTest / WebFetchPipelineE2ETest can subclass with a canned Result.
 */
open class PromptExtractor(
    private val spawner: SubagentSpawner,
    private val brainId: String?,
    private val timeoutMs: Long,
) {

    sealed class Result {
        data class Complete(val answer: String) : Result()
        data class Partial(val answer: String, val note: String) : Result()
        data class NoAnswer(val reason: String) : Result()
    }

    open suspend fun extract(project: Project, sourceText: String, question: String): Result {
        val system = loadSystemPrompt()
        val qDelim = randomDelim()
        val sDelim = randomDelim()
        val user = "Answer the question using only the supplied source text.\n" +
                   "Return only the JSON object specified in your instructions.\n" +
                   "<question-$qDelim>\n$question\n</question-$qDelim>\n" +
                   "<source-$sDelim>\n$sourceText\n</source-$sDelim>"
        val result = spawner.runSanitizer(
            project = project,
            brainId = brainId,
            systemPrompt = system,
            userPrompt = user,
            timeoutMs = timeoutMs,
        )
        return when (result.verdict) {
            SubagentSpawner.Verdict.SAFE -> Result.Complete(result.cleanedText)
            SubagentSpawner.Verdict.STRIPPED -> Result.Partial(
                answer = result.cleanedText,
                note = result.notes.orEmpty().ifBlank { "partial answer" },
            )
            SubagentSpawner.Verdict.REFUSED -> Result.NoAnswer(
                result.notes.orEmpty().ifBlank { "source did not answer the question" }
            )
            SubagentSpawner.Verdict.TIMEOUT -> Result.NoAnswer("extractor timed out")
            SubagentSpawner.Verdict.UNRECOGNISED -> Result.NoAnswer("extractor returned unrecognised verdict")
        }
    }

    private fun loadSystemPrompt(): String =
        javaClass.getResourceAsStream("/personas/extractor-system-prompt.txt")
            ?.bufferedReader()?.readText()
            ?: error("extractor-system-prompt.txt resource missing")

    private fun randomDelim(): String =
        UUID.randomUUID().toString().take(8).filter { it.isLetterOrDigit() }
}
