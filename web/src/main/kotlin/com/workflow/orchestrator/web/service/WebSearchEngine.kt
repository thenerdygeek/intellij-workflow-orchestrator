// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.web.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.web.SearchHit
import com.workflow.orchestrator.core.security.UrlSafetyGuard
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.resolveSanitizerBrainId
import com.workflow.orchestrator.core.web.QueryScreenResult
import com.workflow.orchestrator.core.web.SearchProvider
import com.workflow.orchestrator.core.web.UrlScreener
import com.workflow.orchestrator.core.web.WebError
import com.workflow.orchestrator.core.web.WebSearchService
import com.workflow.orchestrator.core.web.WebSearchService.WebSearchRequest
import com.workflow.orchestrator.web.audit.WebAuditLog
import com.workflow.orchestrator.web.audit.WebAuditRecord
import com.workflow.orchestrator.web.service.sanitizer.JsoupReadability
import com.workflow.orchestrator.web.service.sanitizer.SanitizerSubagent
import com.workflow.orchestrator.web.service.search.SearchProviderRegistry
import java.time.Instant
import java.util.UUID

/**
 * Core 5-stage web search pipeline. Constructor-injected (no [@Service] annotation) so
 * that [WebSearchPipelineE2ETest] can instantiate it directly with mocked deps.
 *
 * Stages:
 *  0. Plan-mode gate + provider-configured check.
 *  1. Query screening — [UrlScreener.screenQuery]; Bearer/JWT/AWS tokens are redacted.
 *  2. Provider resolution + provider base-URL safety screening ([UrlSafetyGuard]) + validation.
 *  3. Provider search — maps result to List<RawHit>; maps failure to typed [WebError].
 *  4. Per-result normalization — URL screening for flags, structural snippet sanitization.
 *  5. Batch semantic sanitization — [SanitizerSubagent.sanitizeBatch]; snippets truncated at
 *     [PluginSettings.State.webSearchSnippetMaxChars]. Result list truncated at
 *     [PluginSettings.State.webSearchMaxResults]. Audit log appended.
 */
class WebSearchEngine(
    private val project: Project,
    private val settings: PluginSettings,
    private val sanitizerSubagent: SanitizerSubagent,
    private val jsoupReadability: JsoupReadability,
    private val registry: SearchProviderRegistry,
    private val auditLog: WebAuditLog,
    /**
     * DNS resolver used for provider-URL SSRF screening. Inject a deterministic stub in tests
     * so the guard does not attempt real DNS. Production callers leave the default [SystemResolver].
     */
    private val ssrfResolver: UrlSafetyGuard.Resolver = UrlSafetyGuard.SystemResolver,
) {

    suspend fun search(request: WebSearchRequest): ToolResult<List<SearchHit>> {
        val start = System.currentTimeMillis()
        val state = settings.state

        // Stage 0: plan-mode gate
        if (request.planMode) {
            auditError(WebError.PlanModeBlocked, request.query, null, start)
            return failure(WebError.PlanModeBlocked)
        }

        // Stage 0 (continued): provider configured check.
        // S1 — Per-call DNS pinning. The provider's OkHttpClient is built with a fresh
        // PinnedDns that caches lookups for the lifetime of this search call, defeating
        // the DNS-rebinding TOCTOU between the SSRF screen below and the provider's
        // outbound HTTP call.
        val pinnedDns = PinnedDns(ssrfResolver)
        val resolved = registry.resolve(pinnedDns)
            ?: run {
                auditError(WebError.NoProviderConfigured, request.query, null, start)
                return failure(WebError.NoProviderConfigured)
            }
        val provider = resolved.provider

        // Stage 1: query screening
        val screenResult = UrlScreener.screenQuery(request.query)
        if (screenResult is QueryScreenResult.Reject) {
            auditError(screenResult.error, request.query, null, start)
            return failure(screenResult.error)
        }
        val cleaned = (screenResult as QueryScreenResult.Pass).cleaned

        // Stage 2a: provider base-URL SSRF screening (spec §4 Stage 2).
        // Screens the configured endpoint against UrlSafetyGuard to block SSRF attacks via a
        // misconfigured/attacker-controlled SearXNG/Brave/CustomHttp URL field.
        // SearXNG gets allowLoopback=true because local instances are a first-class deployment;
        // all other providers are remote APIs and get allowLoopback=false.
        val ssrfCheck = UrlSafetyGuard.isUrlSafe(
            url = resolved.baseUrl,
            allowLoopback = resolved.allowLoopback,
            resolver = ssrfResolver,
        )
        if (ssrfCheck.isFailure) {
            val ex = ssrfCheck.exceptionOrNull() as? UrlSafetyGuard.UrlBlockedException
            val reason = ex?.reason?.name ?: "UNKNOWN"
            val err = WebError.ProviderUrlUnsafe(provider.id.name, reason)
            auditError(err, cleaned, provider.id.name, start)
            return failure(err)
        }

        // Stage 2b: optional provider credential validation
        val validationResult = provider.validate()
        if (validationResult.isFailure) {
            val err = WebError.ProviderAuthFailed(provider.id.name)
            auditError(err, cleaned, provider.id.name, start)
            return failure(err)
        }

        // Stage 3: provider search
        val maxResults = request.maxResults.coerceAtMost(state.webSearchMaxResults)
        val searchResult = provider.search(cleaned, maxResults)
        if (searchResult.isFailure) {
            val msg = searchResult.exceptionOrNull()?.message ?: ""
            val err: WebError = when {
                msg.contains("PROVIDER_AUTH_FAILED") -> WebError.ProviderAuthFailed(provider.id.name)
                msg.contains("PROVIDER_MALFORMED_RESPONSE") -> WebError.ProviderMalformedResponse(provider.id.name)
                else -> WebError.ProviderMalformedResponse(provider.id.name)
            }
            auditError(err, cleaned, provider.id.name, start)
            return failure(err)
        }
        val rawHits = searchResult.getOrThrow()

        // Stage 4: per-result normalization
        val normalizedHits = rawHits.map { hit ->
            val flags = when (val sr = UrlScreener.screen(hit.url, httpsRequired = false, allowIpLiteral = false)) {
                is UrlScreener.Result.Pass -> sr.flags
                is UrlScreener.Result.Reject -> emptySet()
            }
            // Structural snippet sanitization via JsoupReadability on plain text
            val structural = jsoupReadability.sanitize(
                rawBytes = hit.snippet.toByteArray(Charsets.UTF_8),
                contentType = "text/plain",
                sourceUrl = hit.url,
                maxExtractedChars = state.webSearchSnippetMaxChars,
            ).extractedText
            NormalizedHit(
                title = hit.title,
                url = hit.url,
                snippet = structural,
                rank = hit.rank,
                flags = flags,
                providerName = provider.id.name,
            )
        }

        // Stage 5: batch semantic sanitization
        val snippets = normalizedHits.map { it.snippet }
        val sanitized = sanitizerSubagent.sanitizeBatch(
            project = project,
            texts = snippets,
            brainId = settings.resolveSanitizerBrainId(),
            timeoutMs = 60_000L,
        )

        val hits = normalizedHits.mapIndexed { i, norm ->
            val sanResult = sanitized.getOrNull(i)
            // UNRECOGNISED verdict: treat as REFUSED — discard the sanitizer's cleaned_text
            // so a jailbroken sanitizer cannot forward its output to the main agent.
            val finalSnippet = when (sanResult?.verdict) {
                com.workflow.orchestrator.core.web.SubagentSpawner.Verdict.UNRECOGNISED,
                com.workflow.orchestrator.core.web.SubagentSpawner.Verdict.REFUSED -> ""
                else -> (sanResult?.cleanedText?.ifBlank { null } ?: norm.snippet)
            }.take(state.webSearchSnippetMaxChars)
            SearchHit(
                title = norm.title,
                url = norm.url,
                snippet = finalSnippet,
                provider = norm.providerName,
                rank = norm.rank,
                screenerFlags = norm.flags,
            )
        }.take(state.webSearchMaxResults)

        val elapsed = System.currentTimeMillis() - start
        auditSuccess(cleaned, provider.id.name, hits.size, elapsed)
        return ToolResult.success(
            data = hits,
            summary = "Found ${hits.size} results for: $cleaned",
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private data class NormalizedHit(
        val title: String,
        val url: String,
        val snippet: String,
        val rank: Int,
        val flags: Set<UrlScreener.Flag>,
        val providerName: String,
    )

    private fun failure(err: WebError): ToolResult<List<SearchHit>> =
        ToolResult.error(
            summary = "${err.code}: ${err.message}",
            hint = if (err.recoverable) "RECOVERABLE" else "FATAL",
        )

    private fun auditSuccess(query: String, provider: String, resultCount: Int, elapsedMs: Long) {
        auditLog.append(
            WebAuditRecord(
                ts = Instant.now(),
                op = "search",
                agentSessionId = UUID.randomUUID().toString(),
                url = query,
                finalUrl = null,
                query = query,
                provider = provider,
                allowlistDecision = null,
                screenerFlags = emptyList(),
                ssrfPass = true,
                httpStatus = null,
                contentType = null,
                responseBytes = null,
                extractedChars = null,
                resultCount = resultCount,
                sanitizerVerdict = null,
                sanitizerNotes = null,
                elapsedMs = elapsedMs,
                error = null,
            )
        )
    }

    private fun auditError(err: WebError, query: String, provider: String?, start: Long) {
        auditLog.append(
            WebAuditRecord(
                ts = Instant.now(),
                op = "search",
                agentSessionId = null,
                url = query,
                finalUrl = null,
                query = query,
                provider = provider,
                allowlistDecision = null,
                screenerFlags = emptyList(),
                ssrfPass = true,
                httpStatus = null,
                contentType = null,
                responseBytes = null,
                extractedChars = null,
                resultCount = null,
                sanitizerVerdict = null,
                sanitizerNotes = null,
                elapsedMs = System.currentTimeMillis() - start,
                error = err.code,
            )
        )
    }
}
