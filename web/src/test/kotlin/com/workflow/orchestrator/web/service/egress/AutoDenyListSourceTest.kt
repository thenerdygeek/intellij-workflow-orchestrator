package com.workflow.orchestrator.web.service.egress

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AutoDenyListSourceTest {

    @Test
    fun `extractHostsFromUrls returns hostnames from configured service URLs`() {
        val terms = AutoDenyListSource.extractHostsFromUrls(listOf(
            "https://jenkins.acme.corp/dashboard",
            "https://jira.internal.example.com",
            "http://bamboo.acme.corp:8085/",
        ))
        // Both hostname and second-level domain are recorded so a query mentioning either
        // form is caught.
        assertTrue("jenkins.acme.corp" in terms)
        assertTrue("acme.corp" in terms)
        assertTrue("jira.internal.example.com" in terms)
        assertTrue("internal.example.com" in terms)
        assertTrue("bamboo.acme.corp" in terms)
    }

    @Test
    fun `extractHostsFromUrls handles blank and malformed URLs without throwing`() {
        val terms = AutoDenyListSource.extractHostsFromUrls(listOf("", "  ", "not a url", "https://"))
        assertEquals(emptySet<String>(), terms)
    }

    @Test
    fun `extractHostsFromUrls skips well-known public hosts that would cause false positives`() {
        // api.search.brave.com is the default Brave provider URL — auto-deriving "brave.com"
        // would block any query containing "brave" which is too aggressive.
        val terms = AutoDenyListSource.extractHostsFromUrls(listOf(
            "https://api.search.brave.com/res/v1/web/search",
            "https://api.tavily.com",
            "https://www.googleapis.com/search",
        ))
        assertFalse("brave.com" in terms, "public search provider hosts must not be auto-derived")
        assertFalse("tavily.com" in terms)
        assertFalse("googleapis.com" in terms)
    }

    @Test
    fun `extractHostsFromUrls drops very short tokens to avoid spurious matches`() {
        // A two-letter SLD like "x.y" would match nearly every query — skip it.
        val terms = AutoDenyListSource.extractHostsFromUrls(listOf("https://a.b/"))
        assertEquals(emptySet<String>(), terms)
    }

    @Test
    fun `filterTerms drops entries shorter than 6 chars`() {
        val filtered = AutoDenyListSource.filterTerms(setOf("acme.corp", "aws", "MyCompiler", "v1"))
        // "aws" and "v1" are too short — they would false-positive on "always", "aviator", etc.
        assertEquals(setOf("acme.corp", "MyCompiler"), filtered)
    }
}
