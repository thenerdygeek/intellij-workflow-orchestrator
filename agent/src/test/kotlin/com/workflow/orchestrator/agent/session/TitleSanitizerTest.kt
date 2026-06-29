package com.workflow.orchestrator.agent.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TitleSanitizerTest {

    @Test
    fun `strips a closed thinking block`() {
        val raw = "<thinking>I should look at the auth flow</thinking> Fix auth token expiry"
        assertEquals("Fix auth token expiry", TitleSanitizer.sanitize(raw))
    }

    @Test
    fun `QA leak — thinking block plus dangling fenced code collapses to clean prose`() {
        // The exact shape the owner/QA saw leak into a History card title.
        val raw =
            "<thinking>\nLet me analyze the request carefully.\n</thinking> " +
                "Here is the first analysis block: ```kotlin\nfun foo() {\n  bar()\n}\n```"
        val clean = TitleSanitizer.sanitize(raw)
        assertEquals("Here is the first analysis block:", clean)
        assertFalse(clean.contains("<thinking>"), "thinking tag must be stripped")
        assertFalse(clean.contains("```"), "code fence must be stripped")
        assertFalse(clean.contains("fun foo"), "code body must be stripped")
    }

    @Test
    fun `strips an unclosed thinking block truncated mid-stream`() {
        val raw = "Investigate flaky test <thinking>the cause might be the socket timeout and"
        assertEquals("Investigate flaky test", TitleSanitizer.sanitize(raw))
    }

    @Test
    fun `strips an unclosed fenced code block`() {
        val raw = "Refactor the parser ```kotlin\nval x = parse(input)"
        assertEquals("Refactor the parser", TitleSanitizer.sanitize(raw))
    }

    @Test
    fun `keeps inline code text and drops backticks`() {
        assertEquals("Rename foo to bar", TitleSanitizer.sanitize("Rename `foo` to `bar`"))
    }

    @Test
    fun `strips markdown heading and emphasis markers`() {
        assertEquals("Important fix", TitleSanitizer.sanitize("## **Important** _fix_"))
    }

    @Test
    fun `collapses internal whitespace and trims`() {
        assertEquals("a b c", TitleSanitizer.sanitize("  a   b\n\tc  "))
    }

    @Test
    fun `preserves angle-bracket generics — only literal thinking tag is recognised`() {
        val raw = "Add List<String> overload to Cache<K,V>"
        assertEquals("Add List<String> overload to Cache<K,V>", TitleSanitizer.sanitize(raw))
    }

    @Test
    fun `blank input returns empty string`() {
        assertEquals("", TitleSanitizer.sanitize("   "))
        assertEquals("", TitleSanitizer.sanitize(""))
    }

    @Test
    fun `a clean title passes through unchanged`() {
        val clean = "Fix null check in LoginService"
        assertEquals(clean, TitleSanitizer.sanitize(clean))
    }

    @Test
    fun `multiple thinking blocks are all removed`() {
        val raw = "<thinking>a</thinking> keep one <thinking>b</thinking> keep two"
        val clean = TitleSanitizer.sanitize(raw)
        assertEquals("keep one keep two", clean)
        assertTrue(clean.startsWith("keep one"))
    }
}
