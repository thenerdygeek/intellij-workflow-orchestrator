package com.workflow.orchestrator.web.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Regression tests for [WebFetchEngine.matchesDomain] — the function that decides whether a
 * host matches an allowlist entry pattern.
 *
 * The S2 bug: `host.endsWith(pattern.removePrefix("*."))` matches any host whose name simply
 * ends with the pattern bytes, so `attacker-example.com` would match `*.example.com`. The fix
 * requires a leading dot (proper subdomain) OR exact bare-suffix match, and is case-insensitive.
 */
class AllowlistMatchTest {

    @ParameterizedTest(name = "matchesDomain({0}, {1}) == {2}")
    @CsvSource(
        // Proper subdomain — true
        "docs.example.com,            *.example.com,  true",
        // Bare host equals the suffix — true (covers *.example.com / example.com itself)
        "example.com,                 *.example.com,  true",
        // ── S2 bug: attacker prefixes that share suffix bytes must NOT match ──
        "attacker-example.com,        *.example.com,  false",
        "evilexample.com,             *.example.com,  false",
        // ── Suffix appears mid-host, not at the end ──
        "docs.example.com.attacker.com, *.example.com, false",
        // Exact match on a non-glob entry, case-insensitive
        "EXAMPLE.com,                 example.com,    true",
        "example.com,                 example.com,    true",
        // Deeper subdomain still matches a 2-label glob suffix
        "a.b.c.example.com,           *.example.com,  true",
        // Mismatch on non-glob: subdomain must NOT match exact pattern
        "docs.example.com,            example.com,    false",
    )
    fun `matchesDomain enforces leading-dot or exact match (case-insensitive)`(
        host: String,
        pattern: String,
        expected: Boolean,
    ) {
        val actual = WebFetchEngine.matchesDomainForTesting(host, pattern)
        assertEquals(expected, actual, "matchesDomain('$host', '$pattern') expected $expected but got $actual")
    }
}
