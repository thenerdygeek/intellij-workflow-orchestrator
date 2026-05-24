package com.workflow.orchestrator.web.service.egress

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import java.net.URI

/**
 * Derives sensitive terms automatically — no user input required. Two sources today:
 *
 * 1. Hostnames from the user's configured service URLs (Jira/Bamboo/Bitbucket/Sonar/etc).
 *    Both the full hostname (`jenkins.acme.corp`) and the second-level domain
 *    (`acme.corp`) are recorded so a query mentioning either is caught.
 * 2. IntelliJ module names (`payments-service`, `MyComp`, etc).
 *
 * Filter: terms shorter than 6 chars OR matching the [PUBLIC_INFRA_HOSTS] suppress-list are
 * dropped to avoid false positives ("aws" matching "always", "brave.com" matching every Brave-
 * provider query). Future: scan top-level package declarations for richer auto-derivation —
 * left out of v1 because file-scan adds I/O the user pays for on every search.
 */
object AutoDenyListSource {

    /**
     * Well-known public infrastructure hosts that must not become deny-list entries even though
     * they appear in [ConnectionSettings]. These are the search-provider endpoints themselves —
     * deriving "brave.com" or "tavily.com" would block any query containing the provider's
     * brand name, which is over-broad.
     */
    private val PUBLIC_INFRA_HOSTS = setOf(
        "brave.com", "search.brave.com", "api.search.brave.com",
        "tavily.com", "api.tavily.com",
        "googleapis.com", "google.com",
        "github.com", "raw.githubusercontent.com",
    )

    private const val MIN_TERM_LENGTH = 6

    /**
     * Pulls hostnames from a list of service URLs. Returns both the full hostname and the
     * second-level domain (everything after the leftmost label, if at least two dots are
     * present). Filters via [filterTerms] before returning.
     */
    fun extractHostsFromUrls(urls: List<String>): Set<String> {
        val out = mutableSetOf<String>()
        for (raw in urls) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            val host = try {
                URI(trimmed).host?.lowercase()
            } catch (_: Exception) { null } ?: continue
            if (host.isBlank()) continue
            out += host
            // Second-level domain (drop leftmost label), only if the result still has a dot.
            val sld = host.substringAfter('.').takeIf { it.contains('.') }
            if (sld != null) out += sld
        }
        return filterTerms(out)
    }

    /** Module names from the IntelliJ ModuleManager, filtered via [filterTerms]. */
    fun extractModuleNames(project: Project): Set<String> =
        filterTerms(
            ModuleManager.getInstance(project).modules.map { it.name }.toSet()
        )

    /**
     * Drops terms < 6 chars (avoid false positives on common short words) and entries on the
     * [PUBLIC_INFRA_HOSTS] suppress-list. Case-insensitive comparison against the suppress-list.
     */
    fun filterTerms(terms: Set<String>): Set<String> =
        terms.filter { it.length >= MIN_TERM_LENGTH && it.lowercase() !in PUBLIC_INFRA_HOSTS }
            .toSet()
}
