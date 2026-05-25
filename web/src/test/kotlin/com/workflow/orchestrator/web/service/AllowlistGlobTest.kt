// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.web.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Regression tests for [WebFetchEngine.computeAllowlistDomain] — the function that converts a
 * bare host into a subdomain-glob allowlist entry.
 *
 * The critical invariant: a 2-label host like `example.com` must glob to `*.example.com` (covers
 * its own subdomains), NOT `*.com` (which would allowlist the entire TLD). This was the B2 bug:
 *   host.substringAfter('.') → "com" → domain = "*.com"
 *
 * Correct rules (implemented as [WebFetchEngine.computeAllowlistDomain]):
 *   - glob=false → host unchanged.
 *   - glob=true, 1 label  → host unchanged (can't glob safely, no dot in tail).
 *   - glob=true, 2 labels → "*.${host}" (covers subdomains of the host itself).
 *   - glob=true, 3 labels → drop leftmost label → "*.{tail}".
 *   - glob=true, 4+ labels → drop leftmost label → "*.{tail}".
 *   - defense: if the resulting tail after "*." has no dot → fall back to host (TLD guard).
 */
class AllowlistGlobTest {

    @ParameterizedTest(name = "computeAllowlistDomain({0}, glob={1}) == {2}")
    @CsvSource(
        // 2-label host: glob = "*.host" (covers www.example.com, etc.)
        "example.com,      true,  *.example.com",
        // 3-label host: drop leftmost label
        "docs.example.com, true,  *.example.com",
        // 4-label host: drop leftmost label only (conservative — one label at a time)
        "a.b.c.example.com, true, *.b.c.example.com",
        // 3-label, different shape
        "foo.bar.example.com, true, *.bar.example.com",
        // glob=false: host unchanged
        "example.com,      false, example.com",
        // 1-label host: no dot in tail → fall back to host, never produce "*.com" etc.
        "standalone,       true,  standalone",
    )
    fun `computeAllowlistDomain produces correct glob or falls back to host`(
        host: String,
        glob: Boolean,
        expected: String,
    ) {
        val actual = WebFetchEngine.computeAllowlistDomain(host, glob)
        assertEquals(expected, actual, "For host='$host' glob=$glob expected '$expected' but got '$actual'")
    }
}
