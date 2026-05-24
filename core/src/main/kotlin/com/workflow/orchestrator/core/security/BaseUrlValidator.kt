package com.workflow.orchestrator.core.security

import java.net.InetAddress
import java.net.URI

/**
 * Validates user-supplied base URLs before they are persisted to [ConnectionSettings] and used
 * by HTTP clients.
 *
 * Prevents SSRF via committed `.idea/workflowOrchestratorConnections.xml` or social-engineering
 * a user into entering a crafted URL such as `http://169.254.169.254` (AWS IMDS),
 * `http://127.0.0.1`, or `file:///etc/passwd`.
 *
 * Validation rules:
 * 1. URL must be parseable as a [java.net.URI].
 * 2. Scheme must be `http` or `https` — no `file://`, `gopher://`, `ftp://`, etc.
 * 3. Host must be non-null and non-blank.
 * 4. Host (resolved via DNS) must NOT be a loopback, link-local, site-local, or any-local address.
 *    DNS resolution is offline-tolerant: literal IP addresses that fail the blocklist are rejected;
 *    hostnames that fail to resolve at validation time are accepted with a soft warning so users on
 *    offline networks can still save valid corporate URLs.
 *
 * Callers: [ConnectionsConfigurable.apply] for all four required service URLs.
 *
 * Closes audit finding core:F-12 (and chains: jira:F-4, bamboo:F-4, sonar:F-3).
 */
object BaseUrlValidator {

    sealed class ValidationResult {
        /** URL passed all checks — safe to persist. */
        object Valid : ValidationResult()

        /**
         * URL failed a hard check — must NOT be persisted.
         * @param reason Human-readable description suitable for display in the settings page.
         */
        data class Invalid(val reason: String) : ValidationResult()

        /**
         * URL passed structural/scheme checks but the hostname could not be resolved at
         * validation time. The URL may still be valid (e.g. on an air-gapped network).
         * Callers should surface a non-blocking warning but still allow save.
         * @param warning Human-readable description.
         */
        data class SoftWarning(val warning: String) : ValidationResult()
    }

    /**
     * Validate a base URL.
     *
     * @param url   The URL string to validate (e.g. `https://jira.company.com`).
     * @return      [ValidationResult.Valid], [ValidationResult.Invalid], or [ValidationResult.SoftWarning].
     */
    fun validate(url: String): ValidationResult {
        if (url.isBlank()) return ValidationResult.Invalid("URL must not be blank.")

        // ── 1. Parseable as URI ─────────────────────────────────────────────────
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return ValidationResult.Invalid("URL is malformed: ${e.message}")
        }

        // ── 2. Scheme ────────────────────────────────────────────────────────────
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in ALLOWED_SCHEMES) {
            return ValidationResult.Invalid(
                "URL scheme '$scheme' is not allowed. Use https:// (or http:// for internal servers)."
            )
        }

        // ── 3. Host ──────────────────────────────────────────────────────────────
        val rawHost = uri.host
        if (rawHost.isNullOrBlank()) {
            return ValidationResult.Invalid("URL has no host component.")
        }
        val host = rawHost.lowercase().removePrefix("[").removeSuffix("]")

        // ── 4. Literal host quick-reject (avoids DNS for known-bad patterns) ─────
        literalBlockedReason(host)?.let { reason ->
            return ValidationResult.Invalid(reason)
        }

        // ── 5. DNS-resolved address check ─────────────────────────────────────────
        val addresses: Array<InetAddress> = try {
            InetAddress.getAllByName(host)
        } catch (e: Exception) {
            // Hostname could not be resolved at validation time.
            // Fail-open for hostnames (might just be offline); fail-closed for IP literals.
            return if (looksLikeLiteralIp(host)) {
                ValidationResult.Invalid("IP address '$host' could not be resolved or is malformed.")
            } else {
                ValidationResult.SoftWarning(
                    "Hostname '$host' could not be resolved right now. " +
                    "Verify the URL is correct before saving — SSRF protection could not confirm the host is safe."
                )
            }
        }

        for (addr in addresses) {
            blockedAddressReason(addr)?.let { reason ->
                return ValidationResult.Invalid(reason)
            }
        }

        return ValidationResult.Valid
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    private val ALLOWED_SCHEMES = setOf("http", "https")

    /**
     * Quick textual rejection for well-known blocked host patterns — no DNS round-trip needed.
     * Returns a human-readable rejection reason or null if the literal passes.
     */
    private fun literalBlockedReason(host: String): String? {
        if (host.isBlank()) return "URL has no host component."

        // any-local
        if (host == "0.0.0.0") return "IP 0.0.0.0 (any-local) is not allowed."
        if (host == "::") return "IP :: (IPv6 any-local) is not allowed."

        // loopback
        if (host == "localhost") return "Loopback address '$host' is not allowed (SSRF risk)."
        if (host == "::1") return "IPv6 loopback [::1] is not allowed (SSRF risk)."
        if (IPV4_LOOPBACK.matches(host)) return "Loopback address '$host' is not allowed (SSRF risk)."

        // link-local — includes AWS IMDS 169.254.169.254
        if (IPV4_LINK_LOCAL.matches(host))
            return "Link-local address '$host' is not allowed (includes AWS IMDS 169.254.169.254)."
        if (host.length >= 3 && host.take(3).lowercase() in IPV6_LINK_LOCAL_PREFIXES && ':' in host)
            return "IPv6 link-local address '$host' (fe80::/10) is not allowed."

        // RFC 1918 private LAN
        if (IPV4_PRIVATE_10.matches(host)) return "Private LAN address '$host' (10/8) is not allowed (SSRF risk)."
        if (IPV4_PRIVATE_192.matches(host)) return "Private LAN address '$host' (192.168/16) is not allowed (SSRF risk)."
        if (IPV4_PRIVATE_172.matches(host)) return "Private LAN address '$host' (172.16/12) is not allowed (SSRF risk)."

        return null
    }

    /**
     * Classify a resolved [InetAddress] against the blocklist.
     * Returns a human-readable rejection reason or null if the address is safe.
     */
    private fun blockedAddressReason(addr: InetAddress): String? = when {
        addr.isAnyLocalAddress   -> "Resolved address ${addr.hostAddress} is any-local (0.0.0.0/::)."
        addr.isLinkLocalAddress  -> "Resolved address ${addr.hostAddress} is link-local (169.254/16 or fe80::/10)."
        addr.isLoopbackAddress   -> "Resolved address ${addr.hostAddress} is loopback (127/8 or ::1)."
        addr.isSiteLocalAddress  -> "Resolved address ${addr.hostAddress} is a private LAN address (RFC 1918)."
        else -> null
    }

    /** Returns true if the host string looks like a literal IPv4 or IPv6 address (not a hostname). */
    private fun looksLikeLiteralIp(host: String): Boolean =
        IPV4_LITERAL.matches(host) || host.contains(':')

    private val IPV4_LOOPBACK      = Regex("""^127\.\d+\.\d+\.\d+$""")
    private val IPV4_LINK_LOCAL    = Regex("""^169\.254\.\d+\.\d+$""")
    private val IPV4_PRIVATE_10    = Regex("""^10\.\d+\.\d+\.\d+$""")
    private val IPV4_PRIVATE_192   = Regex("""^192\.168\.\d+\.\d+$""")
    private val IPV4_PRIVATE_172   = Regex("""^172\.(1[6-9]|2\d|3[01])\.\d+\.\d+$""")
    private val IPV4_LITERAL       = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val IPV6_LINK_LOCAL_PREFIXES = setOf("fe8", "fe9", "fea", "feb")
}
