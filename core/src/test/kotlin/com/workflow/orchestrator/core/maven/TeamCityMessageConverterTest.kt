package com.workflow.orchestrator.core.maven

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TeamCityMessageConverterTest {

    @Test
    fun `converts successful test to TeamCity messages`() {
        val messages = TeamCityMessageConverter.convert(mapOf(
            "com.example.FooTest" to listOf(
                TestCaseInfo("testAdd", "com.example.FooTest", 100, TestCaseStatus.PASSED)
            )
        ))
        assertTrue(messages.any { it.contains("testSuiteStarted") && it.contains("com.example.FooTest") })
        assertTrue(messages.any { it.contains("testStarted") && it.contains("testAdd") })
        assertTrue(messages.any { it.contains("testFinished") && it.contains("testAdd") })
        assertTrue(messages.any { it.contains("testSuiteFinished") })
        assertFalse(messages.any { it.contains("testFailed") })
    }

    @Test
    fun `converts failed test to TeamCity messages`() {
        val messages = TeamCityMessageConverter.convert(mapOf(
            "com.example.FooTest" to listOf(
                TestCaseInfo("testFail", "com.example.FooTest", 200, TestCaseStatus.FAILED, "expected 5 but was 3", "stack trace")
            )
        ))
        assertTrue(messages.any { it.contains("testFailed") && it.contains("expected 5 but was 3") })
    }

    @Test
    fun `escapes special characters in TeamCity messages`() {
        val escaped = TeamCityMessageConverter.escapeValue("test|with'quotes\nnewline[brackets]")
        assertTrue(escaped.contains("||"))
        assertTrue(escaped.contains("|'"))
        assertTrue(escaped.contains("|n"))
        assertTrue(escaped.contains("|["))
        assertTrue(escaped.contains("|]"))
    }

    @Test
    fun `escapeValue round-trips all six specials exactly`() {
        assertEquals(
            "pipes || and brackets |[x|] |'quoted|' line|nreturn|r",
            TeamCityMessageConverter.escapeValue("pipes | and brackets [x] 'quoted' line\nreturn\r")
        )
    }

    @Test
    fun `escapeValue empty input returned unchanged`() {
        assertEquals("", TeamCityMessageConverter.escapeValue(""))
    }

    @Test
    fun `escapeValue plain ascii without specials returned unchanged`() {
        assertEquals("hello world", TeamCityMessageConverter.escapeValue("hello world"))
    }

    @Test
    fun `converts skipped test`() {
        val messages = TeamCityMessageConverter.convert(mapOf(
            "com.example.FooTest" to listOf(
                TestCaseInfo("testSkipped", "com.example.FooTest", 0, TestCaseStatus.SKIPPED)
            )
        ))
        assertTrue(messages.any { it.contains("testIgnored") && it.contains("testSkipped") })
    }
}
