package com.workflow.orchestrator.core.http

import okhttp3.Request
import java.security.MessageDigest

/**
 * Composite key for [HttpResponseCache]. Wraps a single canonicalised string
 * so Caffeine can hash + compare cheaply.
 *
 * What goes into the key — and why:
 * - **Method** — `GET /foo` and `HEAD /foo` are different responses.
 * - **URL** including query — `?since=2024-01-01` is a different view than `?since=2024-02-01`.
 * - **Authorization hash** — two different users hitting the same URL get different
 *   personalised responses (Jira assignee filters, Bitbucket per-user visibility).
 *   Stored as SHA-256 of the header value (not the value itself) so credentials
 *   never live in the cache key string.
 * - **Accept header** — `application/json` vs `application/xml` are different bodies.
 *
 * Equality is by the `value` string. Thread-safe by construction (immutable).
 */
data class CacheKey(val value: String) {

    override fun toString(): String = value

    companion object {
        /**
         * Build a key from an OkHttp [Request]. Empty/missing headers normalise to
         * a stable token so requests without an Authorization header don't leak
         * their unauthenticated identity into keys for authenticated requests.
         */
        fun of(request: Request): CacheKey {
            val method = request.method.uppercase()
            val url = request.url.toString()
            val authHash = request.header("Authorization")?.let(::sha256Short) ?: "anon"
            val accept = request.header("Accept") ?: "*/*"
            return CacheKey("$method|$url|auth=$authHash|accept=$accept")
        }

        private fun sha256Short(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return buildString(16) {
                for (i in 0 until 8) {
                    append(((bytes[i].toInt() and 0xFF) + 0x100).toString(16).substring(1))
                }
            }
        }
    }
}
