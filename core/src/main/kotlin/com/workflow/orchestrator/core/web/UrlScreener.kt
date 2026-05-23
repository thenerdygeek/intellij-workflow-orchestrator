package com.workflow.orchestrator.core.web

import java.net.IDN
import java.net.URI

/**
 * Pure URL screening — no DNS, no network. Composes with [UrlSafetyGuard] for the DNS pass.
 */
object UrlScreener {

    enum class Flag {
        SHORTENER,
        IDN_HOMOGRAPH,
        IDN_NON_ASCII,
        SUSPICIOUS_TLD,
    }

    sealed class Result {
        data class Pass(val finalUrl: String, val flags: Set<Flag>, val host: String) : Result()
        data class Reject(val error: WebError) : Result()
    }

    private val KNOWN_SHORTENERS = setOf(
        "bit.ly", "t.co", "tinyurl.com", "ow.ly", "goo.gl",
        "is.gd", "buff.ly", "lnkd.in", "rebrand.ly", "cutt.ly",
        "shorturl.at", "tr.im", "tiny.cc", "soo.gd",
    )

    private val SUSPICIOUS_TLDS = setOf(
        "tk", "ml", "ga", "cf", "gq",
        "zip", "mov", "click", "loan", "download",
    )

    fun screen(
        url: String,
        httpsRequired: Boolean,
        allowIpLiteral: Boolean,
    ): Result {
        val uri = try { URI(url) } catch (_: Exception) { null }
        if (uri == null) return reject(WebError.MalformedUrl(url))

        val scheme = uri.scheme?.lowercase()
        // No scheme → not a valid absolute URL (e.g. "" or "example.com")
        if (scheme == null) return reject(WebError.MalformedUrl(url))
        if (scheme != "https" && scheme != "http") return reject(WebError.HttpDisallowed(url))
        if (scheme == "http" && httpsRequired) return reject(WebError.HttpDisallowed(url))

        // Extract host: java.net.URI.host is null for non-ASCII (IDN) hostnames;
        // fall back to rawAuthority, stripping userinfo and port.
        val host = extractHost(uri) ?: return reject(WebError.MalformedUrl(url))

        // Credential check: for ASCII hosts uri.userInfo works; for non-ASCII
        // check rawAuthority for '@' (URI spec: userinfo precedes the last '@').
        val hasCredentials = !uri.userInfo.isNullOrBlank() ||
            (uri.userInfo == null && uri.rawAuthority?.contains('@') == true)
        if (hasCredentials) return reject(WebError.CredentialsInUrl(url))
        if (!allowIpLiteral && isIpLiteral(host)) return reject(WebError.RawIpLiteral(url))

        val flags = mutableSetOf<Flag>()
        if (isShortener(host)) flags += Flag.SHORTENER
        if (isSuspiciousTld(host)) flags += Flag.SUSPICIOUS_TLD
        when (idnClassification(host)) {
            IdnKind.HOMOGRAPH -> flags += Flag.IDN_HOMOGRAPH
            IdnKind.NON_ASCII -> flags += Flag.IDN_NON_ASCII
            IdnKind.ASCII -> Unit
        }

        return Result.Pass(finalUrl = url, flags = flags, host = host)
    }

    fun toPunycode(host: String): String = try { IDN.toASCII(host) } catch (_: Exception) { host }

    private fun reject(error: WebError) = Result.Reject(error)

    /**
     * Extract the host from a URI, handling both ASCII and non-ASCII (IDN) hosts.
     * [URI.host] returns null for non-ASCII hostnames; in that case we parse [URI.rawAuthority].
     */
    private fun extractHost(uri: URI): String? {
        // Fast path: ASCII hostname
        val asciiHost = uri.host
        if (asciiHost != null && asciiHost.isNotBlank()) return asciiHost.lowercase()

        // Fallback for IDN / non-ASCII hostnames
        val rawAuth = uri.rawAuthority ?: return null
        // Strip userinfo (everything up to and including the last '@')
        val afterUserInfo = rawAuth.substringAfterLast('@').ifBlank { rawAuth }
        // Strip port (trailing :digits)
        val withoutPort = afterUserInfo.replace(Regex(":\\d+$"), "")
        return withoutPort.lowercase().ifBlank { null }
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) return true
        if (host.startsWith("[") && host.endsWith("]")) return true
        if (host.contains(":")) return true
        return false
    }

    private fun isShortener(host: String): Boolean {
        if (host in KNOWN_SHORTENERS) return true
        val parts = host.split(".")
        if (parts.size >= 2) {
            val twoLevel = parts.takeLast(2).joinToString(".")
            if (twoLevel in KNOWN_SHORTENERS) return true
        }
        return false
    }

    private fun isSuspiciousTld(host: String): Boolean {
        val tld = host.substringAfterLast('.', "")
        return tld in SUSPICIOUS_TLDS
    }

    private enum class IdnKind { ASCII, NON_ASCII, HOMOGRAPH }

    private fun idnClassification(host: String): IdnKind {
        if (host.all { it.code < 128 }) return IdnKind.ASCII
        // Homograph heuristic: mixed scripts within one label
        for (label in host.split(".")) {
            if (label.any { it.code < 128 } && label.any { it.code >= 128 }) {
                return IdnKind.HOMOGRAPH
            }
            // Cyrillic-in-Latin: contains Cyrillic codepoints but the label "looks Latin"
            val hasCyr = label.any { it in 'Ѐ'..'ӿ' }
            val hasLatin = label.any { it in 'a'..'z' || it in 'A'..'Z' }
            if (hasCyr && hasLatin) return IdnKind.HOMOGRAPH
        }
        return IdnKind.NON_ASCII
    }
}
