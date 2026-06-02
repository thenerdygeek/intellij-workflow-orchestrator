package com.workflow.orchestrator.web.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A redirect `Location` header may be relative per RFC 7231 §7.1.2. The redirect walker
 * must resolve it against the current URL BEFORE re-screening it as an absolute URL —
 * otherwise a relative `Location: /guides/` was screened bare and rejected as
 * `MALFORMED_URL` (the `https://spring.io/guides` field report).
 */
class RedirectLocationResolveTest {
    private fun resolve(base: String, loc: String) = WebFetchEngine.resolveLocation(base, loc)

    @Test fun `absolute location passes through unchanged`() =
        assertEquals(
            "https://other.example.com/x",
            resolve("https://spring.io/guides", "https://other.example.com/x"),
        )

    @Test fun `root-relative location resolves against base host`() =
        assertEquals(
            "https://spring.io/guides/",
            resolve("https://spring.io/guides", "/guides/"),
        )

    @Test fun `path-relative location resolves against base path`() =
        assertEquals(
            "https://spring.io/guides/gs/rest-service",
            resolve("https://spring.io/guides/", "gs/rest-service"),
        )

    @Test fun `protocol-relative location inherits base scheme`() =
        assertEquals(
            "https://cdn.example.com/a",
            resolve("https://spring.io/guides", "//cdn.example.com/a"),
        )
}
