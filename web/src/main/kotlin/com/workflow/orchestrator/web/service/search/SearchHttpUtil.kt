// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.web.service.search

import okhttp3.Response

/** I8 — default cap on search-provider response bodies: 1 MiB. */
internal const val PROVIDER_BODY_MAX_BYTES: Long = 1L * 1024L * 1024L

/**
 * Reads a search-provider response body as UTF-8, but only up to [maxBytes]. Throws
 * [IllegalStateException] with message `PROVIDER_RESPONSE_TOO_LARGE: ...` if the body
 * is longer. Returns null when the response carries no body.
 *
 * I8 — replaces `response.body?.string()` in all four providers. A malicious or
 * misconfigured provider can otherwise return a 100 MB body and OOM the IDE.
 *
 * Implementation note: `source.request(maxBytes + 1)` peeks one byte over the limit
 * so we can detect overflow without buffering an unbounded amount. The cap is enforced
 * before [okio.BufferedSource.readUtf8] is called.
 */
internal fun readBodyCapped(response: Response, maxBytes: Long = PROVIDER_BODY_MAX_BYTES): String? {
    val source = response.body?.source() ?: return null
    source.request(maxBytes + 1)  // peek one byte over to detect overflow
    val buf = source.buffer
    if (buf.size > maxBytes) {
        response.close()
        throw IllegalStateException("PROVIDER_RESPONSE_TOO_LARGE: body exceeds $maxBytes bytes")
    }
    return source.readUtf8()
}
