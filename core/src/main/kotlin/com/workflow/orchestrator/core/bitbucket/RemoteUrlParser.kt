package com.workflow.orchestrator.core.bitbucket

import com.intellij.openapi.diagnostic.logger
import java.net.URI

data class ParsedBitbucketRemote(
    val projectKey: String,
    val repoSlug: String,
    val host: String,
    val scheme: Scheme
) {
    enum class Scheme { HTTPS, SSH, SCP }
}

sealed class RemoteUrlParseResult {
    data class Success(val parsed: ParsedBitbucketRemote) : RemoteUrlParseResult()
    object CloudNotSupported : RemoteUrlParseResult()
    data class Unparseable(val reason: String) : RemoteUrlParseResult()
}

object RemoteUrlParser {
    private val log = logger<RemoteUrlParser>()

    /**
     * Parses [url] into a Bitbucket Server project/slug pair, or returns the reason
     * it couldn't be parsed.
     *
     * Handles HTTPS (with or without PAT-in-URL userinfo), SSH `ssh://` form, and
     * SSH scp-style `git@host:project/repo.git`. Strips trailing `.git`. Preserves
     * `~userslug` literally for personal-repo URLs (this IS the project key).
     *
     * Bitbucket Cloud (`bitbucket.org`) is detected and rejected — Cloud uses a
     * different REST API surface that this plugin does not target.
     */
    fun parse(url: String): RemoteUrlParseResult {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return RemoteUrlParseResult.Unparseable("empty URL")

        // Detect scp-style first (no scheme, contains `:` not after `//`).
        val scpMatch = Regex("""^([^@\s]+@)?([^:/\s]+):([^/\s]+/[^/\s]+?)(?:\.git)?/?\s*$""")
            .matchEntire(trimmed)
        if (scpMatch != null && !trimmed.startsWith("ssh://") && !trimmed.startsWith("http")) {
            val host = scpMatch.groupValues[2]
            val pathPart = scpMatch.groupValues[3]
            if (isCloudHost(host)) return RemoteUrlParseResult.CloudNotSupported
            val (key, slug) = splitProjectSlug(pathPart)
                ?: return RemoteUrlParseResult.Unparseable("scp form: bad path '$pathPart'")
            return RemoteUrlParseResult.Success(
                ParsedBitbucketRemote(key, slug, host, ParsedBitbucketRemote.Scheme.SCP)
            )
        }

        return try {
            val uri = URI.create(trimmed)
            val host = uri.host
                ?: return RemoteUrlParseResult.Unparseable("URI missing host: $trimmed")
            if (isCloudHost(host)) return RemoteUrlParseResult.CloudNotSupported

            // Strip leading slash, trailing .git, optional /scm prefix
            var path = uri.rawPath
                ?: return RemoteUrlParseResult.Unparseable("URI missing path: $trimmed")
            path = path.removePrefix("/").removeSuffix("/")
            if (path.endsWith(".git")) path = path.removeSuffix(".git")
            if (path.startsWith("scm/")) path = path.removePrefix("scm/")

            val (key, slug) = splitProjectSlug(path)
                ?: return RemoteUrlParseResult.Unparseable("URI: bad path '$path'")
            val scheme = when (uri.scheme?.lowercase()) {
                "https", "http" -> ParsedBitbucketRemote.Scheme.HTTPS
                "ssh" -> ParsedBitbucketRemote.Scheme.SSH
                else -> return RemoteUrlParseResult.Unparseable("unsupported scheme: ${uri.scheme}")
            }
            RemoteUrlParseResult.Success(ParsedBitbucketRemote(key, slug, host, scheme))
        } catch (e: Exception) {
            log.warn("[RemoteUrlParser] Failed to parse $trimmed", e)
            RemoteUrlParseResult.Unparseable("URI parse error: ${e.message ?: "unknown"}")
        }
    }

    internal fun isCloudHost(host: String): Boolean =
        host.equals("bitbucket.org", ignoreCase = true) ||
            host.endsWith(".bitbucket.org", ignoreCase = true)

    /**
     * Splits "PROJ/repo" or "~userslug/repo" into (projectKey, repoSlug).
     * Personal repos preserve the leading `~`.
     * Uses the LAST two segments — handles paths with extra prefixes defensively.
     */
    internal fun splitProjectSlug(path: String): Pair<String, String>? {
        val parts = path.split("/")
        if (parts.size < 2) return null
        val slug = parts[parts.size - 1].takeIf { it.isNotBlank() } ?: return null
        val key = parts[parts.size - 2].takeIf { it.isNotBlank() } ?: return null
        return key to slug
    }
}
