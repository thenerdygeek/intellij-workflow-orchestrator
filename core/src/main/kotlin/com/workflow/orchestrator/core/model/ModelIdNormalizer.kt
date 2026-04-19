// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.core.model

/**
 * Normalizes raw LLM model IDs (as returned by Sourcegraph's OpenAI-compatible API)
 * to the canonical form used for pricing lookup and display.
 *
 * Raw format: `provider::apiVersion::modelName-YYYYMMDD`
 * Examples:
 *   `anthropic::v1::claude-sonnet-4-20250514`        → `claude-sonnet-4`
 *   `anthropic::v1::claude-opus-4-20250514-thinking`  → `claude-opus-4-thinking`
 *   `anthropic::2024-10-22::claude-sonnet-4-latest`   → `claude-sonnet-4`
 *   `openai::v1::gpt-4o-2024-08-06`                  → `gpt-4o`
 *   `claude-sonnet-4` (bare)                          → `claude-sonnet-4` (unchanged)
 */
object ModelIdNormalizer {

    // Matches an 8-digit compact date (Anthropic style): -20250514, -20241022, etc.
    // Also matches a YYYY-MM-DD hyphenated date (OpenAI style): -2024-08-06, etc.
    // The date may appear at end-of-string OR just before a trailing "-thinking" suffix.
    // Lookahead `(?=-thinking$|$)` ensures we only strip dates in these two positions.
    private val DATE_SUFFIX_RE = Regex("""-(?:\d{8}|\d{4}-\d{2}-\d{2})(?=-thinking$|$)""")

    // Matches a trailing -latest suffix
    private val LATEST_SUFFIX_RE = Regex("""-latest$""")

    /**
     * Normalize [rawModelId] to its canonical pricing-lookup form.
     *
     * Steps (applied in order):
     * 1. Return blank strings as-is.
     * 2. Strip `provider::apiVersion::` prefix (two "::" separated leading segments).
     * 3. Strip trailing `-YYYYMMDD` date suffix.
     * 4. Strip trailing `-latest` suffix.
     *
     * The `-thinking` suffix is intentionally preserved (needed for separate pricing entries).
     */
    fun normalize(rawModelId: String): String {
        if (rawModelId.isBlank()) return rawModelId

        // Strip provider::apiVersion:: prefix — exactly two leading "::" segments
        val stripped = stripProviderPrefix(rawModelId)

        // Strip trailing date suffix (-20250514, -20241022, etc.)
        val withoutDate = DATE_SUFFIX_RE.replace(stripped, "")

        // Strip trailing -latest
        return LATEST_SUFFIX_RE.replace(withoutDate, "")
    }

    /**
     * Strip `provider::apiVersion::` prefix if present.
     *
     * The raw model ID may contain a date inside the apiVersion segment
     * (e.g. `anthropic::2024-10-22::claude-sonnet-4-latest`), so we cannot
     * simply split on `::` and take the last part via `substringAfterLast("::") ` —
     * that would leave the date in. Instead we count exactly two occurrences of
     * `::` from the start and strip everything up to and including the second one.
     */
    private fun stripProviderPrefix(id: String): String {
        val first = id.indexOf("::")
        if (first < 0) return id              // no "::" at all — bare id
        val second = id.indexOf("::", first + 2)
        if (second < 0) return id             // only one "::" — not the expected prefix format
        return id.substring(second + 2)
    }
}
