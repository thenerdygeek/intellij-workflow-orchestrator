// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ModelIdNormalizer].
 *
 * Covers:
 * - Full Sourcegraph-style `provider::apiVersion::modelName-YYYYMMDD` prefixes
 * - -latest suffix stripping
 * - -thinking suffix preservation
 * - Bare (already-normalized) IDs pass through unchanged
 * - Edge cases: blank input, single-:: id
 */
class ModelIdNormalizerTest {

    private fun assertNormalized(raw: String, expected: String) {
        assertEquals(expected, ModelIdNormalizer.normalize(raw),
            "normalize(\"$raw\") should equal \"$expected\"")
    }

    // ----- Spec examples -----

    @Test
    fun `strips prefix and preserves thinking suffix`() {
        assertNormalized(
            "anthropic::v1::claude-opus-4-20250514-thinking",
            "claude-opus-4-thinking"
        )
    }

    @Test
    fun `strips prefix and date suffix from sonnet`() {
        assertNormalized(
            "anthropic::v1::claude-sonnet-4-20250514",
            "claude-sonnet-4"
        )
    }

    @Test
    fun `strips prefix with date-segment apiVersion and preserves thinking`() {
        assertNormalized(
            "anthropic::2024-10-22::claude-opus-4-5-thinking-20250101",
            "claude-opus-4-5-thinking"
        )
    }

    @Test
    fun `strips prefix and latest suffix`() {
        assertNormalized(
            "anthropic::2024-10-22::claude-sonnet-4-latest",
            "claude-sonnet-4"
        )
    }

    @Test
    fun `strips openai prefix and date suffix`() {
        assertNormalized(
            "openai::v1::gpt-4o-2024-08-06",
            "gpt-4o"
        )
    }

    @Test
    fun `bare id without prefix passes through unchanged`() {
        assertNormalized("claude-sonnet-4", "claude-sonnet-4")
    }

    @Test
    fun `bare id with thinking passes through unchanged`() {
        assertNormalized("claude-opus-4-5-thinking", "claude-opus-4-5-thinking")
    }

    // ----- Additional cases -----

    @Test
    fun `bare haiku passes through unchanged`() {
        assertNormalized("claude-haiku-4", "claude-haiku-4")
    }

    @Test
    fun `bare latest suffix stripped`() {
        assertNormalized("claude-sonnet-4-latest", "claude-sonnet-4")
    }

    @Test
    fun `bare date suffix stripped`() {
        assertNormalized("claude-sonnet-4-20250514", "claude-sonnet-4")
    }

    @Test
    fun `bare thinking with date suffix strips date preserves thinking`() {
        assertNormalized("claude-sonnet-4-5-thinking-20250514", "claude-sonnet-4-5-thinking")
    }

    @Test
    fun `gpt-4o bare passes through unchanged`() {
        assertNormalized("gpt-4o", "gpt-4o")
    }

    @Test
    fun `openai prefix with openai date-style apiVersion`() {
        assertNormalized("openai::2024-08-06::gpt-4o-2024-08-06", "gpt-4o")
    }

    @Test
    fun `o3-mini passes through unchanged`() {
        assertNormalized("o3-mini", "o3-mini")
    }

    @Test
    fun `gemini with prefix and date suffix`() {
        assertNormalized("google::v1::gemini-2-0-flash-20240101", "gemini-2-0-flash")
    }

    // ----- Edge cases -----

    @Test
    fun `blank string is returned as-is`() {
        assertEquals("", ModelIdNormalizer.normalize(""))
    }

    @Test
    fun `whitespace-only string is returned as-is`() {
        assertEquals("   ", ModelIdNormalizer.normalize("   "))
    }

    @Test
    fun `string with only one double-colon is returned unchanged`() {
        // Only one "::" — does not match the provider::apiVersion::model format, returned as-is
        assertNormalized("anthropic::claude-sonnet-4", "anthropic::claude-sonnet-4")
    }

    @Test
    fun `thinking suffix preserved after full prefix plus date stripping`() {
        assertNormalized(
            "anthropic::v1::claude-opus-4-20250514-thinking",
            "claude-opus-4-thinking"
        )
    }

    @Test
    fun `date segment in apiVersion does not bleed into model name`() {
        // The apiVersion `2024-10-22` contains hyphens+digits but is not 8 contiguous digits
        assertNormalized(
            "anthropic::2024-10-22::claude-sonnet-4-5-thinking-20250101",
            "claude-sonnet-4-5-thinking"
        )
    }
}
