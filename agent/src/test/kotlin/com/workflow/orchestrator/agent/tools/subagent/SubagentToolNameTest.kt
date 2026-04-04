package com.workflow.orchestrator.agent.tools.subagent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SubagentToolNameTest {

    @Nested
    inner class Sanitize {

        @Test
        fun `lowercases and replaces special chars`() {
            assertEquals("code_analyzer", SubagentToolName.sanitize("Code Analyzer"))
        }

        @Test
        fun `collapses multiple underscores`() {
            assertEquals("foo_bar", SubagentToolName.sanitize("foo___bar"))
        }

        @Test
        fun `replaces mixed special characters`() {
            assertEquals("my_agent_v2", SubagentToolName.sanitize("My Agent (v2)"))
        }

        @Test
        fun `returns empty for all-special chars`() {
            assertEquals("", SubagentToolName.sanitize("!@#\$%^&*()"))
        }

        @Test
        fun `trims leading and trailing underscores`() {
            assertEquals("hello", SubagentToolName.sanitize("__hello__"))
        }

        @Test
        fun `handles already-clean names`() {
            assertEquals("simple", SubagentToolName.sanitize("simple"))
        }
    }

    @Nested
    inner class Build {

        @Test
        fun `creates prefixed tool name`() {
            assertEquals("use_subagent_code_analyzer", SubagentToolName.build("Code Analyzer"))
        }

        @Test
        fun `uses agent fallback for empty sanitized name`() {
            assertEquals("use_subagent_agent", SubagentToolName.build("!@#"))
        }

        @Test
        fun `truncates long names with hash suffix and stays within 64 chars`() {
            val longName = "a".repeat(100)
            val result = SubagentToolName.build(longName)
            assertTrue(result.length <= 64, "Expected <= 64 chars, got ${result.length}: $result")
            assertTrue(result.startsWith("use_subagent_"), "Expected prefix, got: $result")
        }

        @Test
        fun `short names are not truncated`() {
            val result = SubagentToolName.build("lint")
            assertEquals("use_subagent_lint", result)
        }

        @Test
        fun `names exactly at limit are not truncated`() {
            // PREFIX is 13 chars, so we need a sanitized name of exactly 51 chars to hit 64
            val name = "a".repeat(51)
            val result = SubagentToolName.build(name)
            assertEquals(64, result.length)
            assertFalse(result.contains(SubagentToolName.hashString(name).take(6)),
                "Should not contain hash suffix when exactly at limit")
        }
    }

    @Nested
    inner class IsSubagentToolName {

        @Test
        fun `detects dynamic subagent names`() {
            assertTrue(SubagentToolName.isSubagentToolName("use_subagent_code_analyzer"))
        }

        @Test
        fun `rejects non-subagent names`() {
            assertFalse(SubagentToolName.isSubagentToolName("read_file"))
        }

        @Test
        fun `rejects empty string`() {
            assertFalse(SubagentToolName.isSubagentToolName(""))
        }
    }

    @Nested
    inner class HashString {

        @Test
        fun `is deterministic`() {
            val a = SubagentToolName.hashString("hello")
            val b = SubagentToolName.hashString("hello")
            assertEquals(a, b)
        }

        @Test
        fun `differs for different inputs`() {
            val a = SubagentToolName.hashString("hello")
            val b = SubagentToolName.hashString("world")
            assertNotEquals(a, b)
        }

        @Test
        fun `returns non-empty string`() {
            assertTrue(SubagentToolName.hashString("test").isNotEmpty())
        }
    }
}
