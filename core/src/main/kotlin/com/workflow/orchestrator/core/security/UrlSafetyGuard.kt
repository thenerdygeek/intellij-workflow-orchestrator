package com.workflow.orchestrator.core.security

import java.net.InetAddress
import java.net.URI

/**
 * Validates a URL is safe to fetch — i.e. its host (after DNS resolution) does not point at
 * an internal address that an outbound HTTP call from this plugin could be tricked into
 * probing (SSRF). The classic example is the AWS instance metadata service at
 * `169.254.169.254`, but the same family of attacks applies to any link-local, loopback,
 * private-LAN, or any-local address.
 *
 * Two call modes:
 * - `allowLoopback = false` (Docker Registry / outbound auth-realm follow): rejects loopback,
 *   link-local, AWS metadata, all RFC 1918 private LAN ranges, IPv6 link-local + loopback, and
 *   any-local. This is the "we are talking to a remote registry, anything pointing at our own
 *   network is suspicious" mode.
 * - `allowLoopback = true` (`HttpReadinessProbe` / local Spring Boot dev server): same blocklist
 *   minus the loopback + private-LAN ranges, since the legitimate use case here is the developer
 *   probing `http://localhost:PORT/health` for a local app. Link-local / AWS metadata / any-local
 *   are still blocked — there's no legitimate reason for a probe to ever hit those.
 *
 * Lifted from `DockerRegistryClient.isRealmSafe` (kept byte-equivalent for the `allowLoopback=false`
 * case so the existing realm-safety tests pass unchanged) and extended with explicit reason
 * categories so callers can produce informative "URL_BLOCKED: AWS_METADATA …" style errors.
 *
 * **DNS-resolution semantics.** The literal host AND every resolved address are checked. A
 * malicious DNS record like `evil.example.com → 127.0.0.1` is caught because the resolved
 * address fails the loopback check. Unresolvable hosts fail closed (rejected) — better to error
 * a legitimate request than to silently let a malformed host through.
 *
 * The guard does its DNS lookup inside the caller's existing IO context — it is a pure-Kotlin
 * blocking utility, no coroutine machinery. Callers are expected to be on `Dispatchers.IO`.
 */
object UrlSafetyGuard {

    /**
     * Hookable DNS resolver. Tests inject a deterministic resolver so a "DNS-resolves-to-loopback"
     * scenario does not depend on actual DNS. Production uses [SystemResolver].
     */
    fun interface Resolver {
        /** Returns all addresses for [host], or throws on resolution failure. */
        fun resolve(host: String): Array<InetAddress>
    }

    /** Production resolver — delegates to `InetAddress.getAllByName`. */
    object SystemResolver : Resolver {
        override fun resolve(host: String): Array<InetAddress> = InetAddress.getAllByName(host)
    }

    /** Why a URL was rejected. Callers use this to format `URL_BLOCKED: <reason>` errors. */
    enum class Reason {
        /** URL string was malformed or had no host component. */
        MALFORMED_URL,
        /** Host could not be resolved by DNS — fail-closed. */
        UNRESOLVABLE_HOST,
        /** `127.0.0.0/8` or hostname `localhost`. */
        IPV4_LOOPBACK,
        /** `::1`. */
        IPV6_LOOPBACK,
        /** `0.0.0.0`. */
        IPV4_ANY_LOCAL,
        /** `::`. */
        IPV6_ANY_LOCAL,
        /** `169.254.0.0/16` — includes the AWS metadata address `169.254.169.254`. */
        IPV4_LINK_LOCAL,
        /** `fe80::/10`. */
        IPV6_LINK_LOCAL,
        /** RFC 1918: `10/8`, `192.168/16`, `172.16/12` (and IPv6 site-local `fec0::/10`). */
        PRIVATE_LAN,
    }

    /**
     * Exception payload describing which check rejected the URL. Stored as the failure cause in
     * the returned `Result.failure(...)`.
     */
    class UrlBlockedException(
        val reason: Reason,
        val host: String,
        message: String,
    ) : RuntimeException(message)

    /**
     * Validates that [url] is safe to fetch. Returns:
     * - `Result.success(Unit)` when the URL is allowed.
     * - `Result.failure(UrlBlockedException)` when the URL points at a blocked range or could not
     *   be parsed/resolved.
     *
     * @param url The full URL (must include scheme + host; port + path + userinfo are tolerated).
     * @param allowLoopback When true, loopback + RFC 1918 private LAN are permitted (intended for
     *   local-dev probes). When false, those ranges are rejected (intended for outbound calls to
     *   remote services).
     * @param resolver Inject a deterministic DNS resolver for testing. Production callers should
     *   leave the default [SystemResolver].
     */
    fun isUrlSafe(
        url: String,
        allowLoopback: Boolean,
        resolver: Resolver = SystemResolver,
    ): Result<Unit> {
        // ── Parse host ────────────────────────────────────────────────────────────────────
        val rawHost: String = try {
            URI(url).host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return reject(Reason.MALFORMED_URL, host = url, "URL is malformed or has no host")

        // URI.getHost returns IPv6 with surrounding brackets (e.g. "[::1]"); strip them so
        // InetAddress.getByName + literal regex matchers see the raw form.
        val host = rawHost.removePrefix("[").removeSuffix("]")

        // ── Literal-host rejection (skips DNS for textual matches like "localhost" or
        //    "169.254.169.254" — same first-pass logic as the original isRealmSafe). ─────────
        literalRejection(host, allowLoopback)?.let { reason ->
            return reject(reason, host, describe(reason, host))
        }

        // ── DNS-resolved address rejection ────────────────────────────────────────────────
        val addresses: Array<InetAddress> = try {
            resolver.resolve(host)
        } catch (_: Exception) {
            // Fail-closed: an unresolvable host is suspect, not benign. We log at debug from
            // the caller side if needed (the guard itself stays log-free to keep it pure).
            return reject(Reason.UNRESOLVABLE_HOST, host, "Host '$host' could not be resolved")
        }

        for (addr in addresses) {
            classify(addr, allowLoopback)?.let { reason ->
                return reject(reason, host, describe(reason, host, addr))
            }
        }

        return Result.success(Unit)
    }

    // ────────────────────────────────────────────────────────────────────────────────────────
    // Internals
    // ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * First-pass textual check on the literal host string. Catches the common cases without a DNS
     * round-trip. Returns the matching [Reason] or null if the literal does not look blocked.
     *
     * Mirrors the regex set in the original `DockerRegistryClient.isRealmSafe` so behavior is
     * byte-equivalent for `allowLoopback=false`.
     */
    private fun literalRejection(host: String, allowLoopback: Boolean): Reason? {
        // IPv4 any-local — always blocked
        if (host == "0.0.0.0") return Reason.IPV4_ANY_LOCAL
        // IPv6 any-local — "::" (URI.host returns "::" without brackets here since we stripped them)
        if (host == "::") return Reason.IPV6_ANY_LOCAL

        // IPv4 link-local (includes AWS metadata 169.254.169.254) — always blocked
        if (IPV4_LINK_LOCAL_REGEX.matches(host)) return Reason.IPV4_LINK_LOCAL

        // IPv6 link-local (fe80::/10) — always blocked. Match the textual prefix.
        if (host.startsWith("fe8") || host.startsWith("fe9") ||
            host.startsWith("fea") || host.startsWith("feb")
        ) {
            // Cheap textual prefix match for the fe80::/10 range. The DNS path will catch any
            // edge case via InetAddress.isLinkLocalAddress.
            if (host.contains(":")) return Reason.IPV6_LINK_LOCAL
        }

        if (!allowLoopback) {
            if (host == "localhost") return Reason.IPV4_LOOPBACK
            if (host == "::1") return Reason.IPV6_LOOPBACK
            if (IPV4_LOOPBACK_REGEX.matches(host)) return Reason.IPV4_LOOPBACK
            if (IPV4_PRIVATE_10_REGEX.matches(host)) return Reason.PRIVATE_LAN
            if (IPV4_PRIVATE_192_REGEX.matches(host)) return Reason.PRIVATE_LAN
            if (IPV4_PRIVATE_172_REGEX.matches(host)) return Reason.PRIVATE_LAN
        }
        return null
    }

    /**
     * Classifies an [InetAddress] against the active blocklist. Returns the matching [Reason] or
     * null if the address is allowed.
     *
     * The original `isRealmSafe` collapsed everything to a boolean via the standard
     * `InetAddress.is*Address()` helpers; this version preserves that semantics and adds the
     * branching needed to name the offending range.
     */
    private fun classify(addr: InetAddress, allowLoopback: Boolean): Reason? {
        // Any-local (`0.0.0.0` / `::`) — always blocked.
        if (addr.isAnyLocalAddress) {
            return if (addr is java.net.Inet6Address) Reason.IPV6_ANY_LOCAL else Reason.IPV4_ANY_LOCAL
        }
        // Link-local (`169.254.0.0/16` / `fe80::/10`) — always blocked. Includes AWS metadata.
        if (addr.isLinkLocalAddress) {
            return if (addr is java.net.Inet6Address) Reason.IPV6_LINK_LOCAL else Reason.IPV4_LINK_LOCAL
        }
        if (!allowLoopback) {
            if (addr.isLoopbackAddress) {
                return if (addr is java.net.Inet6Address) Reason.IPV6_LOOPBACK else Reason.IPV4_LOOPBACK
            }
            // Site-local covers RFC 1918 private LAN ranges (10/8, 192.168/16, 172.16/12) for
            // IPv4 and fec0::/10 for IPv6 (deprecated but still flagged).
            if (addr.isSiteLocalAddress) return Reason.PRIVATE_LAN
        }
        return null
    }

    private fun reject(reason: Reason, host: String, message: String): Result<Unit> =
        Result.failure(UrlBlockedException(reason, host, message))

    private fun describe(reason: Reason, host: String, addr: InetAddress? = null): String {
        val addrSuffix = if (addr != null) " (resolved to ${addr.hostAddress})" else ""
        return when (reason) {
            Reason.MALFORMED_URL -> "Malformed URL: '$host'"
            Reason.UNRESOLVABLE_HOST -> "Host '$host' could not be resolved"
            Reason.IPV4_LOOPBACK -> "Host '$host' resolves to IPv4 loopback$addrSuffix"
            Reason.IPV6_LOOPBACK -> "Host '$host' resolves to IPv6 loopback$addrSuffix"
            Reason.IPV4_ANY_LOCAL -> "Host '$host' is IPv4 any-local (0.0.0.0)$addrSuffix"
            Reason.IPV6_ANY_LOCAL -> "Host '$host' is IPv6 any-local (::)$addrSuffix"
            Reason.IPV4_LINK_LOCAL ->
                "Host '$host' is in IPv4 link-local 169.254.0.0/16 (includes AWS metadata)$addrSuffix"
            Reason.IPV6_LINK_LOCAL -> "Host '$host' is in IPv6 link-local fe80::/10$addrSuffix"
            Reason.PRIVATE_LAN -> "Host '$host' is in a private LAN range (RFC 1918)$addrSuffix"
        }
    }

    // Same regex set the original DockerRegistryClient.isRealmSafe used. Kept byte-equivalent so
    // the existing realm-safety tests remain meaningful.
    private val IPV4_LOOPBACK_REGEX = Regex("""^127\.\d+\.\d+\.\d+$""")
    private val IPV4_LINK_LOCAL_REGEX = Regex("""^169\.254\.\d+\.\d+$""")
    private val IPV4_PRIVATE_10_REGEX = Regex("""^10\.\d+\.\d+\.\d+$""")
    private val IPV4_PRIVATE_192_REGEX = Regex("""^192\.168\.\d+\.\d+$""")
    private val IPV4_PRIVATE_172_REGEX = Regex("""^172\.(1[6-9]|2\d|3[01])\.\d+\.\d+$""")
}
