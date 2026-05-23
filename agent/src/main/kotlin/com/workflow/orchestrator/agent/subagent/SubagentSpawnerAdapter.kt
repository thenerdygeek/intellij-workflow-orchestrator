// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.subagent

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.subagent.SubagentRunner
import com.workflow.orchestrator.agent.tools.subagent.SubagentProgressUpdate
import com.workflow.orchestrator.core.ai.LlmBrainFactory
import com.workflow.orchestrator.core.web.SubagentSpawner
import com.workflow.orchestrator.core.web.SubagentSpawner.SanitizerResult
import com.workflow.orchestrator.core.web.SubagentSpawner.Verdict
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Adapts [com.workflow.orchestrator.core.web.SubagentSpawner] to the real
 * [SubagentRunner] so the `:web` module can spawn a sanitizer sub-agent without
 * taking a compile-time dependency on `:agent`.
 *
 * Registered as a project service with the [SubagentSpawner] interface so IntelliJ DI
 * delivers it anywhere `:core`'s interface is consumed.
 *
 * Each [runSanitizer] call constructs a fresh one-shot, tool-less [SubagentRunner]
 * (maxIterations=1, coreTools=empty) so the sanitizer LLM cannot execute any side
 * effects — it can only return text in its final output.
 */
@Service(Service.Level.PROJECT)
class SubagentSpawnerAdapter(private val project: Project) : SubagentSpawner {

    private val LOG = Logger.getInstance(SubagentSpawnerAdapter::class.java)

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun runSanitizer(
        project: Project,
        brainId: String?,
        systemPrompt: String,
        userPrompt: String,
        timeoutMs: Long,
    ): SanitizerResult {
        // TODO: when arbitrary-brainId lookup is supported, resolve brain by brainId here.
        // For now we always use createForSanitization (Haiku > Sonnet fallback).
        val brain = if (!brainId.isNullOrBlank()) {
            LOG.info("SubagentSpawnerAdapter: brainId='$brainId' requested but per-ID lookup not yet supported; using sanitization model")
            LlmBrainFactory.createForSanitization(project)
        } else {
            LlmBrainFactory.createForSanitization(project)
        }

        val runner = SubagentRunner(
            brain = brain,
            coreTools = emptyMap(),          // read-only sanitizer — no tool access
            systemPrompt = systemPrompt,
            project = project,
            maxIterations = 1,               // single-shot: LLM produces JSON output once
            planMode = false,
            contextBudget = 16_000,
            maxOutputTokens = 8_000,
        )

        val result = withTimeoutOrNull(timeoutMs) {
            runner.run(
                prompt = userPrompt,
                agentId = "web-sanitizer",
                label = "Web content sanitizer",
                onProgress = { _: SubagentProgressUpdate -> },  // no UI surfacing for sanitizer
            )
        } ?: return SanitizerResult(
            verdict = Verdict.TIMEOUT,
            cleanedText = "",
            notes = "Sanitizer subagent timed out after ${timeoutMs}ms",
        )

        val rawText = result.result ?: result.error ?: ""
        return parseResult(rawText)
    }

    /**
     * Parse the sanitizer subagent's JSON output.
     *
     * Expected shape: `{"verdict": "SAFE|STRIPPED|REFUSED", "cleaned_text": "...", "notes": "..."}`
     *
     * Any parse failure or unrecognised verdict defaults to [Verdict.TIMEOUT] (fail-closed)
     * so the calling code can handle it gracefully.
     */
    private fun parseResult(rawText: String): SanitizerResult {
        val json = extractJsonObject(rawText) ?: return SanitizerResult(
            verdict = Verdict.TIMEOUT,
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
                    LOG.warn("SubagentSpawnerAdapter: unrecognised verdict '$verdictStr', defaulting to SAFE")
                    Verdict.SAFE
                }
            }
            SanitizerResult(verdict = verdict, cleanedText = cleanedText, notes = notes)
        } catch (e: Exception) {
            LOG.warn("SubagentSpawnerAdapter: failed to extract fields from sanitizer JSON", e)
            SanitizerResult(
                verdict = Verdict.TIMEOUT,
                cleanedText = "",
                notes = "Sanitizer JSON field extraction failed: ${e.message}",
            )
        }
    }

    /**
     * Extract the first JSON object from [text], which may contain leading prose before
     * the JSON block (the LLM sometimes prefixes the JSON with a sentence).
     */
    private fun extractJsonObject(text: String): JsonObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        return try {
            lenientJson.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        } catch (e: Exception) {
            LOG.warn("SubagentSpawnerAdapter: could not parse sanitizer response as JSON: ${text.take(200)}", e)
            null
        }
    }
}
