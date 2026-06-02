// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.subagent

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.ai.LlmBrain
// SubagentRunner is intentionally NOT used: the sanitizer is a single brain.chat()
// completion, not an agentic loop (see class KDoc).
import com.workflow.orchestrator.core.ai.LlmBrainFactory
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.web.SubagentSpawner
import com.workflow.orchestrator.core.web.SubagentSpawner.SanitizerResult
import com.workflow.orchestrator.core.web.SubagentSpawner.Verdict
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Implements [com.workflow.orchestrator.core.web.SubagentSpawner] for the `:web` module
 * (so it can sanitize fetched content without a compile-time dependency on `:agent`).
 *
 * Registered as a project service with the [SubagentSpawner] interface so IntelliJ DI
 * delivers it anywhere `:core`'s interface is consumed.
 *
 * The sanitizer is a **single-shot, text-in/text-out** generator: its persona returns a
 * raw JSON object and never calls a tool. It therefore runs as ONE [LlmBrain.chat]
 * completion, NOT through the agentic [com.workflow.orchestrator.agent.tools.subagent.SubagentRunner]
 * / `AgentLoop`. The loop only emits a completed result when the LLM calls a terminal tool
 * (`task_report`); a text-only response with `maxIterations=1` failed with
 * "Exceeded maximum iterations (1)", surfacing as `SANITIZER_UNREADABLE` on even trivial
 * pages. A direct completion has no loop, no tools, and no iteration cap — exactly the
 * pattern `HaikuPhraseGenerator` uses for one-shot text generation.
 */
@Service(Service.Level.PROJECT)
class SubagentSpawnerAdapter(private val project: Project) : SubagentSpawner {

    private val LOG = Logger.getInstance(SubagentSpawnerAdapter::class.java)

    /**
     * Brain factory seam — `(project, brainId) -> LlmBrain`. Production resolves the cheap
     * sanitization model via [LlmBrainFactory.createForSanitization]; tests inject a fake
     * brain. `brainId` is reserved for future per-id lookup (not yet supported).
     */
    internal var brainFactory: suspend (Project, String?) -> LlmBrain = { p, _ ->
        LlmBrainFactory.createForSanitization(p)
    }

    /** Outcome of one sanitizer completion: either the raw assistant text or a failure. */
    private sealed interface ChatOutcome {
        data class Text(val raw: String) : ChatOutcome
        data class Fail(val verdict: Verdict, val note: String) : ChatOutcome
    }

    /** One sanitizer LLM completion (no loop, no tools, no iteration cap). */
    private suspend fun runOnce(
        brainId: String?,
        systemPrompt: String,
        userPrompt: String,
        timeoutMs: Long,
    ): ChatOutcome {
        val brain = brainFactory(project, brainId)
        val messages = listOf(
            ChatMessage(role = "system", content = systemPrompt),
            ChatMessage(role = "user", content = userPrompt),
        )
        val resp = withTimeoutOrNull(timeoutMs) {
            brain.chat(messages, maxTokens = outputBudgetForInput(userPrompt.length))
        } ?: return ChatOutcome.Fail(Verdict.TIMEOUT, "Sanitizer timed out after ${timeoutMs}ms")
        return when (resp) {
            is ApiResult.Success -> {
                val text = resp.data.choices.firstOrNull()?.message?.content
                if (text.isNullOrBlank()) {
                    ChatOutcome.Fail(Verdict.UNRECOGNISED, "Sanitizer returned an empty response")
                } else {
                    ChatOutcome.Text(text)
                }
            }
            is ApiResult.Error ->
                ChatOutcome.Fail(Verdict.UNRECOGNISED, "Sanitizer LLM error: ${resp.type} ${resp.message}")
        }
    }

    companion object {
        /** Floor — never give the sanitizer less output room than this. */
        private const val MIN_OUTPUT_TOKENS = 8_000
        /** Cap — guard against an absurd allocation on a pathologically large page. */
        private const val MAX_OUTPUT_TOKENS = 32_000
        /** Conservative chars→tokens divisor (real ratio ~3.5–4); /3 over-allocates = safe. */
        private const val CHARS_PER_TOKEN = 3
        /** Headroom for the JSON wrapper + notes around the verbatim echo. */
        private const val WRAPPER_TOKENS = 2_000

        /**
         * Output-token budget for a verbatim-echo sanitize of [inputChars] characters.
         *
         * The sanitizer reproduces its input character-for-character, so the response must
         * be able to hold the whole input plus the JSON wrapper. A fixed 8000-token cap
         * truncated large pages (`webMaxExtractedChars` defaults to 32768 ≈ 10k+ tokens) →
         * the JSON never closed → parse failure surfaced as `SANITIZER_TIMEOUT`. Sizing the
         * budget to the input removes that structural truncation.
         */
        fun outputBudgetForInput(inputChars: Int): Int =
            (inputChars / CHARS_PER_TOKEN + WRAPPER_TOKENS)
                .coerceIn(MIN_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS)
    }

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun runSanitizer(
        project: Project,
        brainId: String?,
        systemPrompt: String,
        userPrompt: String,
        timeoutMs: Long,
    ): SanitizerResult {
        return when (val outcome = runOnce(brainId, systemPrompt, userPrompt, timeoutMs)) {
            is ChatOutcome.Text -> parseResult(outcome.raw)
            is ChatOutcome.Fail -> SanitizerResult(outcome.verdict, "", outcome.note)
        }
    }

    override suspend fun runSanitizerBatch(
        project: Project,
        brainId: String?,
        systemPrompt: String,
        userPrompt: String,
        timeoutMs: Long,
        expectedCount: Int,
    ): List<SanitizerResult> {
        val failAll = List(expectedCount) {
            SanitizerResult(verdict = Verdict.STRIPPED, cleanedText = "", notes = "batch parse failed")
        }

        return when (val outcome = runOnce(brainId, systemPrompt, userPrompt, timeoutMs)) {
            is ChatOutcome.Text -> parseBatchResult(outcome.raw, expectedCount) ?: failAll
            is ChatOutcome.Fail -> List(expectedCount) {
                SanitizerResult(outcome.verdict, "", outcome.note)
            }
        }
    }

    /**
     * Parse the batch sanitizer output.
     *
     * Expected shape: `{"results": [{"verdict": "...", "cleaned_text": "...", "notes": "..."}, ...]}`
     * of length [expectedCount]. Returns null on any parse failure so the caller can fall back.
     */
    private fun parseBatchResult(rawText: String, expectedCount: Int): List<SanitizerResult>? {
        val candidate = extractBraceBalanced(stripMarkdownFences(rawText)) ?: return null
        return try {
            val root = lenientJson.parseToJsonElement(candidate).jsonObject
            val resultsArray: JsonArray = root["results"]?.jsonArray ?: return null
            if (resultsArray.size != expectedCount) {
                LOG.warn("SubagentSpawnerAdapter: batch result count ${resultsArray.size} != expected $expectedCount")
                // Return what we have, padded/trimmed to expectedCount
            }
            val parsed = resultsArray.mapIndexed { idx, elem ->
                try {
                    val obj = elem.jsonObject
                    val verdictStr = obj["verdict"]?.jsonPrimitive?.content?.uppercase()
                    val cleanedText = obj["cleaned_text"]?.jsonPrimitive?.content ?: ""
                    val notes = obj["notes"]?.jsonPrimitive?.content
                    val verdict = when (verdictStr) {
                        "SAFE" -> Verdict.SAFE
                        "STRIPPED" -> Verdict.STRIPPED
                        "REFUSED" -> Verdict.REFUSED
                        "TIMEOUT" -> Verdict.TIMEOUT
                        else -> {
                            LOG.warn("SubagentSpawnerAdapter: batch[$idx] unrecognised verdict '$verdictStr', treating as UNRECOGNISED (fail-closed)")
                            Verdict.UNRECOGNISED
                        }
                    }
                    SanitizerResult(verdict = verdict, cleanedText = cleanedText, notes = notes)
                } catch (e: Exception) {
                    LOG.warn("SubagentSpawnerAdapter: batch[$idx] field extraction failed: ${e.message}")
                    SanitizerResult(verdict = Verdict.STRIPPED, cleanedText = "", notes = "batch item parse failed")
                }
            }
            // Pad with STRIPPED entries if the LLM returned fewer results than expected
            if (parsed.size < expectedCount) {
                parsed + List(expectedCount - parsed.size) {
                    SanitizerResult(verdict = Verdict.STRIPPED, cleanedText = "", notes = "batch parse failed")
                }
            } else {
                parsed.take(expectedCount)
            }
        } catch (e: Exception) {
            LOG.warn("SubagentSpawnerAdapter: batch parse failed: ${e.message}")
            null
        }
    }

    /**
     * Parse the sanitizer subagent's JSON output.
     *
     * Expected shape: `{"verdict": "SAFE|STRIPPED|REFUSED", "cleaned_text": "...", "notes": "..."}`
     *
     * Any parse failure or unrecognised verdict returns [Verdict.UNRECOGNISED] (fail-closed,
     * carrying diagnostic notes). It is deliberately NOT [Verdict.TIMEOUT] — a parse failure
     * is not a timeout, and mislabeling it as one made truncated/garbled output surface to
     * the user as a misleading SANITIZER_TIMEOUT with no diagnostic. The true-timeout path
     * (the `withTimeoutOrNull` null branch in [runSanitizer]) is the only producer of
     * [Verdict.TIMEOUT].
     */
    private fun parseResult(rawText: String): SanitizerResult {
        val json = extractJsonObject(rawText) ?: return SanitizerResult(
            verdict = Verdict.UNRECOGNISED,
            cleanedText = "",
            notes = "Sanitizer returned unparseable output: ${rawText.take(200)}",
        )

        return try {
            val verdictStr = json["verdict"]?.jsonPrimitive?.content?.uppercase()
            val cleanedText = json["cleaned_text"]?.jsonPrimitive?.content ?: ""
            val notes = json["notes"]?.jsonPrimitive?.content

            val verdict = when (verdictStr) {
                "SAFE" -> Verdict.SAFE
                "STRIPPED" -> Verdict.STRIPPED
                "REFUSED" -> Verdict.REFUSED
                "TIMEOUT" -> Verdict.TIMEOUT
                else -> {
                    LOG.warn("SubagentSpawnerAdapter: unrecognised verdict '$verdictStr', treating as UNRECOGNISED (fail-closed)")
                    Verdict.UNRECOGNISED
                }
            }
            SanitizerResult(verdict = verdict, cleanedText = cleanedText, notes = notes)
        } catch (e: Exception) {
            LOG.warn("SubagentSpawnerAdapter: failed to extract fields from sanitizer JSON", e)
            SanitizerResult(
                verdict = Verdict.UNRECOGNISED,
                cleanedText = "",
                notes = "Sanitizer JSON field extraction failed: ${e.message}",
            )
        }
    }

    /**
     * Extract the first JSON object from [text], which may be:
     *   - Bare JSON: `{...}`
     *   - Markdown-fenced: ` ```json\n{...}\n``` ` or ` ```\n{...}\n``` `
     *   - Prose + fenced or prose + bare: `"Here is the result:\n```json\n{...}\n```"`
     *
     * Strategy: strip markdown fences first, then scan for the outermost `{...}` using
     * a brace-counting scanner (more correct than first-`{`/last-`}` when the JSON
     * contains nested objects or trailing prose after the closing `}`).
     */
    private fun extractJsonObject(text: String): JsonObject? {
        val stripped = stripMarkdownFences(text)
        val candidate = extractBraceBalanced(stripped) ?: return null
        return try {
            lenientJson.parseToJsonElement(candidate).jsonObject
        } catch (e: Exception) {
            LOG.warn("SubagentSpawnerAdapter: could not parse sanitizer response as JSON: ${text.take(200)}", e)
            null
        }
    }

    /**
     * Strip all ` ```[lang] ` / ` ``` ` fence markers from [text].
     * Handles optional language tag (e.g. `json`) and optional whitespace around newlines.
     */
    private fun stripMarkdownFences(text: String): String =
        text
            .replace(Regex("```(?:json)?\\s*\\n?"), "")
            .replace(Regex("\\n?```"), "")

    /**
     * Find the first top-level `{...}` block in [text] using a brace-counting scanner.
     * Returns the substring including the opening and closing braces, or null if not found.
     */
    private fun extractBraceBalanced(text: String): String? {
        val start = text.indexOf('{')
        if (start == -1) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val ch = text[i]
            if (escape) { escape = false; continue }
            if (ch == '\\' && inString) { escape = true; continue }
            if (ch == '"') { inString = !inString; continue }
            if (inString) continue
            when (ch) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
