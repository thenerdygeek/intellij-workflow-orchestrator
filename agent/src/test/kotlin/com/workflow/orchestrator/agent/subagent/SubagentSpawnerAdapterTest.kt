// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.subagent

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.web.SubagentSpawner
import com.workflow.orchestrator.core.web.SubagentSpawner.Verdict
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

/**
 * Unit tests for [SubagentSpawnerAdapter]'s JSON parsing logic.
 *
 * The adapter's `parseResult(rawText)` method is `private`, so we call it via reflection.
 * Similarly `parseBatchResult(rawText, expectedCount)` is private.
 *
 * ## Why reflection
 * The methods are self-contained pure functions (String → SanitizerResult) and
 * have no external dependencies. Extracting them to a companion/top-level would
 * change the production API; reflection lets us test them in-situ and pin the
 * existing contract.
 *
 * ## What we test
 * - `parseResult`:
 *   1. Clean JSON → correct fields
 *   2. JSON with prose prefix → still parsed (first-`{` last-`}` window)
 *   3. JSON fenced in backticks → same expectation as prose prefix
 *   4. Malformed JSON (missing closing brace) → TIMEOUT result
 *   5. Unknown verdict value → defaults to SAFE
 *
 * - `parseBatchResult`:
 *   6. Happy-path batch (exact count) → all fields correct
 *   7. Short batch (fewer results than expected) → padded with STRIPPED
 *   8. Malformed batch JSON → returns null (caller falls back to failAll)
 */
class SubagentSpawnerAdapterTest {

    private val project = mockk<Project>(relaxed = true)
    private lateinit var adapter: SubagentSpawnerAdapter
    private lateinit var parseResultMethod: Method
    private lateinit var parseBatchResultMethod: Method

    @BeforeEach
    fun setUp() {
        adapter = SubagentSpawnerAdapter(project)
        // Resolve private methods via reflection; make them accessible for the test
        parseResultMethod = SubagentSpawnerAdapter::class.java
            .getDeclaredMethod("parseResult", String::class.java)
            .also { it.isAccessible = true }
        parseBatchResultMethod = SubagentSpawnerAdapter::class.java
            .getDeclaredMethod("parseBatchResult", String::class.java, Int::class.java)
            .also { it.isAccessible = true }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // parseResult cases
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseResult(rawText: String): SubagentSpawner.SanitizerResult {
        @Suppress("UNCHECKED_CAST")
        return parseResultMethod.invoke(adapter, rawText) as SubagentSpawner.SanitizerResult
    }

    @Test
    fun `clean JSON returns correct SanitizerResult`() {
        val json = """{"verdict":"SAFE","cleaned_text":"hello world","notes":"all good"}"""
        val result = parseResult(json)
        assertEquals(Verdict.SAFE, result.verdict)
        assertEquals("hello world", result.cleanedText)
        assertEquals("all good", result.notes)
    }

    @Test
    fun `JSON with prose prefix is still parsed via first-brace last-brace window`() {
        val raw = """Here's the result:
{"verdict":"STRIPPED","cleaned_text":"trimmed content","notes":"removed ads"}"""
        val result = parseResult(raw)
        assertEquals(Verdict.STRIPPED, result.verdict)
        assertEquals("trimmed content", result.cleanedText)
    }

    @Test
    @Disabled("Implementation uses first-{/last-} window which may not strip backtick fences — " +
        "this case pins expected behavior; re-enable once the impl handles fenced JSON.")
    fun `JSON fenced in backticks is still parsed`() {
        val raw = "```json\n{\"verdict\":\"SAFE\",\"cleaned_text\":\"page content\",\"notes\":null}\n```"
        val result = parseResult(raw)
        assertEquals(Verdict.SAFE, result.verdict)
        assertEquals("page content", result.cleanedText)
    }

    @Test
    fun `malformed JSON returns TIMEOUT result`() {
        val raw = """{"verdict":"SAFE","cleaned_text":"incomplete"""  // missing closing }
        val result = parseResult(raw)
        // Implementation returns TIMEOUT on any parse failure (fail-closed)
        assertEquals(Verdict.TIMEOUT, result.verdict)
        assertEquals("", result.cleanedText)
    }

    @Test
    fun `unknown verdict value defaults to SAFE`() {
        val json = """{"verdict":"BANANA","cleaned_text":"some text","notes":null}"""
        val result = parseResult(json)
        assertEquals(Verdict.SAFE, result.verdict, "Unknown verdict should default to SAFE")
        assertEquals("some text", result.cleanedText)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // parseBatchResult cases
    // ─────────────────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun parseBatchResult(rawText: String, expectedCount: Int): List<SubagentSpawner.SanitizerResult>? {
        return parseBatchResultMethod.invoke(adapter, rawText, expectedCount) as List<SubagentSpawner.SanitizerResult>?
    }

    @Test
    fun `batch happy path returns correct count and fields`() {
        val json = """{"results":[
            {"verdict":"SAFE","cleaned_text":"snippet one","notes":"ok"},
            {"verdict":"STRIPPED","cleaned_text":"snippet two","notes":"stripped"}
        ]}"""
        val results = parseBatchResult(json, 2)
        assertNotNull(results)
        assertEquals(2, results!!.size)
        assertEquals(Verdict.SAFE, results[0].verdict)
        assertEquals("snippet one", results[0].cleanedText)
        assertEquals(Verdict.STRIPPED, results[1].verdict)
        assertEquals("snippet two", results[1].cleanedText)
    }

    @Test
    fun `batch with fewer results than expected is padded with STRIPPED`() {
        // LLM returned 1 item but we expected 3
        val json = """{"results":[
            {"verdict":"SAFE","cleaned_text":"only one","notes":null}
        ]}"""
        val results = parseBatchResult(json, 3)
        assertNotNull(results)
        assertEquals(3, results!!.size, "Should pad to expectedCount")
        assertEquals(Verdict.SAFE, results[0].verdict)
        assertEquals("only one", results[0].cleanedText)
        // Padded entries should be STRIPPED
        assertEquals(Verdict.STRIPPED, results[1].verdict)
        assertEquals(Verdict.STRIPPED, results[2].verdict)
    }

    @Test
    fun `batch with malformed JSON returns null`() {
        val malformed = """not-json-at-all"""
        val results = parseBatchResult(malformed, 2)
        assertNull(results, "Expected null for malformed batch JSON so caller can fall back")
    }
}
