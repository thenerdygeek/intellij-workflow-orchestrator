package com.workflow.orchestrator.web.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.web.AllowlistDecision
import com.workflow.orchestrator.core.model.web.DomainAllowlistEntry
import com.workflow.orchestrator.core.model.web.SanitizerVerdict
import com.workflow.orchestrator.core.model.web.WebPage
import com.workflow.orchestrator.core.security.UrlSafetyGuard
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.getWebAllowlist
import com.workflow.orchestrator.core.settings.resolveSanitizerBrainId
import com.workflow.orchestrator.core.settings.setWebAllowlist
import com.workflow.orchestrator.core.web.SubagentSpawner
import com.workflow.orchestrator.core.web.WebError
import com.workflow.orchestrator.core.web.WebFetchService
import com.workflow.orchestrator.web.audit.WebAuditLog
import com.workflow.orchestrator.web.audit.WebAuditRecord
import com.workflow.orchestrator.web.service.sanitizer.JsoupReadability
import com.workflow.orchestrator.web.service.sanitizer.SanitizerSubagent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.time.Instant
import java.util.UUID

/**
 * Core 8-stage web fetch pipeline. Constructor-injected (no [Service] annotation) so
 * that [WebFetchPipelineE2ETest] can instantiate it directly with mocked deps.
 *
 * Stages:
 *  0. Plan-mode gate — short-circuit when request.planMode=true.
 *  1. URL screening — UrlScreener (scheme, credentials, IP literal, IDN, shortener, TLD).
 *  2. Shortener resolution — single-hop HEAD/GET + re-screen destination.
 *  3. SSRF guard — UrlSafetyGuard (DNS-resolved literal + address check).
 *  4. Allowlist check — fast-path when domain is already approved.
 *  5. Approval gate — shows [ApprovalDialog] (or fake in tests) for unlisted domains.
 *  6. HTTP GET with safe-redirect walking (re-screens every Location header) + size cap.
 *  7. Structural sanitization — [JsoupReadability] strips scripts/styles/iframes/comments.
 *  8. Semantic sanitization — [SanitizerSubagent] rewrites fetched content into neutral form.
 *
 * The [WebFetchServiceImpl] @Service thin facade owns production wiring; this class owns logic.
 */
class WebFetchEngine(
    private val project: Project,
    private val settings: PluginSettings,
    private val clientFactory: (okhttp3.Dns) -> OkHttpClient,
    private val sanitizer: JsoupReadability,
    private val sanitizerSubagent: SanitizerSubagent,
    private val approvalGate: ApprovalGate,
    private val auditLog: WebAuditLog,
    private val resolver: UrlSafetyGuard.Resolver = UrlSafetyGuard.SystemResolver,
    private val shortenerResolver: ShortenerResolver,
    /**
     * When true, the SSRF guard allows loopback addresses (localhost, 127.x.x.x) and
     * private LAN ranges to pass. Default false (production posture — all internal addresses
     * are rejected). Set to true in tests that use MockWebServer on loopback.
     */
    private val allowLoopback: Boolean = false,
) {

    /**
     * Legacy constructor used by tests that pass a pre-built [OkHttpClient] without a
     * DNS pinning hook. The DNS pinning is still applied per-call via [PinnedDns] —
     * the supplied client is rebuilt via `newBuilder().dns(pinnedDns)` at fetch time so
     * connection pool / interceptors / timeouts are preserved while DNS resolution is
     * routed through the per-call [PinnedDns].
     */
    constructor(
        project: Project,
        settings: PluginSettings,
        client: OkHttpClient,
        sanitizer: JsoupReadability,
        sanitizerSubagent: SanitizerSubagent,
        approvalGate: ApprovalGate,
        auditLog: WebAuditLog,
        resolver: UrlSafetyGuard.Resolver = UrlSafetyGuard.SystemResolver,
        shortenerResolver: ShortenerResolver,
        allowLoopback: Boolean = false,
    ) : this(
        project = project,
        settings = settings,
        clientFactory = { pinnedDns -> client.newBuilder().dns(pinnedDns).build() },
        sanitizer = sanitizer,
        sanitizerSubagent = sanitizerSubagent,
        approvalGate = approvalGate,
        auditLog = auditLog,
        resolver = resolver,
        shortenerResolver = shortenerResolver,
        allowLoopback = allowLoopback,
    )

    companion object {
        private const val USER_AGENT =
            "WorkflowOrchestratorPlugin/1.0 (+https://github.com/workflow-orchestrator)"
        private const val ACCEPT =
            "text/html,text/plain,application/json,text/markdown;q=0.9,application/xml;q=0.8"
        private val ALLOWED_CONTENT_TYPES = listOf(
            "text/html",
            "text/plain",
            "application/json",
            "text/markdown",
            "application/xml",
        )

        /**
         * Converts a bare [host] into the allowlist entry domain, applying subdomain-glob expansion
         * when [subdomainGlob] is true.
         *
         * Rules:
         * - glob=false → [host] unchanged.
         * - glob=true, 2-label host (e.g. `example.com`) → `*.example.com` (covers its own subdomains).
         * - glob=true, 3+ label host (e.g. `docs.example.com`) → drop leftmost label → `*.example.com`.
         * - Defense: if the resulting tail after `*.` has no dot (i.e. it would be a bare TLD like
         *   `*.com`), fall back to [host] unchanged rather than write a wildcard that matches the
         *   entire TLD. B2 bug root cause: `host.substringAfter('.')` on `example.com` returned
         *   `"com"` → entry was `*.com` → every `.com` host was matched.
         *
         * Exposed as `internal` so [AllowlistGlobTest] can invoke it directly.
         */
        internal fun computeAllowlistDomain(host: String, subdomainGlob: Boolean): String {
            if (!subdomainGlob) return host
            val labels = host.split(".")
            if (labels.size < 2) return host  // single-label host — can't glob safely

            // For a 2-label host (example.com), the glob covers subdomains of the host itself.
            // For 3+ label hosts (docs.example.com), drop the leftmost label only.
            val globTail = if (labels.size == 2) host else labels.drop(1).joinToString(".")

            // Defense: never produce a glob whose tail is a bare TLD (no dot → *.com, *.org).
            if (!globTail.contains('.')) return host

            return "*.$globTail"
        }

        /**
         * Tests whether [host] matches an allowlist [pattern]. S2 fix: a glob pattern
         * `*.example.com` matches the bare suffix `example.com` AND any host that ends
         * with `.example.com`. Crucially it does NOT match `attacker-example.com` (the
         * old `endsWith(suffix)` bug). Case-insensitive on both sides.
         *
         * @see com.workflow.orchestrator.web.service.AllowlistMatchTest
         */
        internal fun matchesDomainImpl(host: String, pattern: String): Boolean {
            val h = host.lowercase()
            return if (pattern.startsWith("*.")) {
                val suffix = pattern.removePrefix("*.").lowercase()
                // Bare-suffix exact match (e.g. example.com matches *.example.com)
                // OR proper subdomain (e.g. docs.example.com matches *.example.com).
                // Crucially NOT "attacker-example.com" — the bug was a bare endsWith
                // with no leading-dot requirement.
                h == suffix || h.endsWith(".$suffix")
            } else {
                h == pattern.lowercase()
            }
        }

        /** Test-only accessor — see [com.workflow.orchestrator.web.service.AllowlistMatchTest]. */
        internal fun matchesDomainForTesting(host: String, pattern: String): Boolean =
            matchesDomainImpl(host, pattern)
    }

    private val pipeline: UrlPipeline by lazy { UrlPipeline(shortenerResolver, resolver) }

    /**
     * I7 — Serializes read-modify-write on the allowlist. Two parallel fetches that both
     * resolve "Add to allowlist" would otherwise lost-update each other. One mutex per
     * engine instance is sufficient because there is only one engine per project.
     */
    private val allowlistMutex = Mutex()

    /** Internal exception types caught at the top of [fetchWithSafeRedirects]. */
    private class RedirectBlockedException(val webError: WebError) : RuntimeException()
    private class TooManyRedirectsException : RuntimeException()

    suspend fun fetch(request: WebFetchService.WebFetchRequest): ToolResult<WebPage> {
        val start = System.currentTimeMillis()
        val correlationId = UUID.randomUUID().toString()
        val state = settings.state

        // S1 — Per-call DNS pinning. The SSRF safety check above runs against a fresh
        // PinnedDns that caches lookups for the lifetime of this fetch. We build an
        // OkHttpClient that resolves via the SAME PinnedDns so OkHttp cannot pick a
        // different (potentially malicious) address than the one the guard cleared.
        val pinnedDns = PinnedDns(resolver)
        val callClient: OkHttpClient = clientFactory(pinnedDns)

        // Stage 0: plan-mode gate
        if (request.planMode) {
            return failure(WebError.PlanModeBlocked, start, request.url)
        }

        // Stages 1 + 2 + 3: URL screening, shortener resolution, SSRF guard
        val piped = pipeline.run(
            url = request.url,
            httpsRequired = state.webRequireHttps,
            allowIpLiteral = state.webAllowIpLiteral,
            resolveShorteners = state.webResolveShorteners,
            allowLoopback = allowLoopback,
        )
        if (piped is UrlPipeline.Result.Reject) {
            return failure(piped.error, start, request.url)
        }
        val pass = piped as UrlPipeline.Result.Pass

        // Stage 4: allowlist check
        val allowlist = settings.getWebAllowlist()
        val isAllowlisted = allowlist.any { matchesDomain(pass.host, it.domain) }

        val decision: AllowlistDecision
        if (isAllowlisted) {
            decision = AllowlistDecision.APPROVED_AUTO
        } else {
            when (state.webUnlistedPolicy) {
                "REJECT" -> return failure(WebError.UnlistedHardReject(pass.host), start, pass.finalUrl)
                else -> {
                    // Stage 5: show approval gate (PROMPT path)
                    val outcome = withContext(Dispatchers.Default) {
                        approvalGate.ask(
                            ApprovalGate.ApprovalPrompt(
                                finalUrl = pass.finalUrl,
                                originalUrl = pass.originalUrl,
                                screenerFlags = pass.flags,
                                resolvedIp = pass.resolvedIp,
                                contentLength = null,   // No HEAD probe by design; dialog shows "unknown"
                                agentContext = request.agentContext?.take(200) ?: "",
                                timeoutMs = state.webApprovalTimeoutSec * 1000L,
                            )
                        )
                    }
                    decision = when (outcome) {
                        ApprovalGate.Decision.AllowOnce -> AllowlistDecision.APPROVED_PROMPT
                        is ApprovalGate.Decision.AddToAllowlist -> {
                            persistAllowlistEntry(pass.host, outcome)
                            AllowlistDecision.APPROVED_PROMPT
                        }
                        ApprovalGate.Decision.Denied ->
                            return failure(WebError.ApprovalDenied(pass.host), start, pass.finalUrl)
                        ApprovalGate.Decision.TimedOut ->
                            return failure(WebError.ApprovalTimeout(pass.host), start, pass.finalUrl)
                    }
                }
            }
        }

        // Stage 6: HTTP GET with manual redirect walking + streaming size cap
        val maxBytes = (request.maxBytes ?: state.webMaxBytes).toLong()
        val resp: Response = try {
            fetchWithSafeRedirects(pass.finalUrl, correlationId, state, callClient)
        } catch (e: RedirectBlockedException) {
            return failure(e.webError, start, pass.finalUrl)
        } catch (_: TooManyRedirectsException) {
            return failure(
                WebError.HttpTimeout("too_many_redirects"),
                start,
                pass.finalUrl,
            )
        } catch (_: Exception) {
            return failure(WebError.HttpTimeout("connect"), start, pass.finalUrl)
        }

        resp.use {
            if (!resp.isSuccessful) {
                return failure(WebError.HttpStatus(resp.code, pass.finalUrl), start, pass.finalUrl)
            }
            val ct = resp.header("Content-Type") ?: "application/octet-stream"
            if (!isAllowedContentType(ct)) {
                return failure(WebError.UnsupportedContentType(ct), start, pass.finalUrl)
            }
            val source = resp.body?.source()
                ?: return failure(WebError.HttpStatus(204, pass.finalUrl), start, pass.finalUrl)

            // Streaming read with size cap
            val buf = okio.Buffer()
            var read = 0L
            while (!source.exhausted()) {
                val chunk = source.read(buf, 8 * 1024L)
                if (chunk == -1L) break
                read += chunk
                if (read > maxBytes) {
                    return failure(WebError.ResponseTooLarge(read, maxBytes), start, pass.finalUrl)
                }
            }
            val rawBytes = buf.readByteArray()

            // Stage 6 (prefix-sniff): defeat lying Content-Type headers — spec §3 Stage 6.
            // A server that sends Content-Type: text/html but a PNG body will be caught here.
            val prefix = rawBytes.take(512).toByteArray()
            if (sniffIsBinary(prefix)) {
                return failure(
                    WebError.UnsupportedContentType("$ct (binary content detected by prefix sniff)"),
                    start,
                    pass.finalUrl,
                )
            }

            // Stage 7: structural sanitization
            val struct = sanitizer.sanitize(
                rawBytes = rawBytes,
                contentType = ct,
                sourceUrl = pass.finalUrl,
                maxExtractedChars = state.webMaxExtractedChars,
            )

            // Stage 8: semantic sanitization via subagent
            val san = sanitizerSubagent.sanitize(
                project = project,
                extractedText = struct.extractedText,
                brainId = settings.resolveSanitizerBrainId(),
                timeoutMs = 60_000L,
            )

            val (verdict, finalText) = when (san.verdict) {
                SubagentSpawner.Verdict.SAFE    -> SanitizerVerdict.SAFE     to san.cleanedText
                SubagentSpawner.Verdict.STRIPPED -> SanitizerVerdict.STRIPPED to san.cleanedText
                SubagentSpawner.Verdict.REFUSED  ->
                    return failure(
                        WebError.SanitizerRefused(san.notes ?: ""),
                        start,
                        pass.finalUrl,
                    )
                SubagentSpawner.Verdict.TIMEOUT ->
                    if (state.webSanitizerFailClosed)
                        return failure(WebError.SanitizerTimeout, start, pass.finalUrl)
                    else
                        SanitizerVerdict.STRUCTURAL_ONLY to struct.extractedText
                SubagentSpawner.Verdict.UNRECOGNISED ->
                    return failure(
                        WebError.SanitizerRefused("sanitizer returned unrecognised verdict"),
                        start,
                        pass.finalUrl,
                    )
            }

            val page = WebPage(
                originalUrl = pass.originalUrl ?: request.url,
                finalUrl = pass.finalUrl,
                contentType = ct,
                responseBytes = read,
                extractedText = finalText,
                extractedChars = finalText.length,
                screenerFlags = pass.flags,
                allowlistDecision = decision,
                sanitizerVerdict = verdict,
                sanitizerNotes = san.notes,
                fetchedAt = Instant.now(),
                elapsedMs = System.currentTimeMillis() - start,
            )

            auditSuccess(page, correlationId)
            return ToolResult.success(
                data = page,
                summary = "Fetched ${page.finalUrl} (${page.extractedChars} chars, ${page.sanitizerVerdict})",
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // R3: Safe redirect walking — re-screens every Location header via pipeline
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun fetchWithSafeRedirects(
        initialUrl: String,
        correlationId: String,
        state: PluginSettings.State,
        callClient: OkHttpClient,
    ): Response {
        var url = initialUrl
        repeat(3) { _ ->
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", ACCEPT)
                .header("X-Web-Tool-CorrelationId", correlationId)
                .build()
            val resp = withContext(Dispatchers.IO) { callClient.newCall(req).execute() }
            val loc = resp.header("Location")
            if (resp.code !in 300..399 || loc == null) {
                // Not a redirect — return this response to caller
                return resp
            }
            resp.close()
            // Re-screen redirect destination (no shortener resolution on hop)
            // Note: allowLoopback is NOT propagated here — redirect destinations are
            // always screened with the production-safe allowLoopback=false regardless
            // of the test setting, because redirect injection attacks must always be blocked.
            val rescreen = pipeline.run(
                url = loc,
                httpsRequired = state.webRequireHttps,
                allowIpLiteral = state.webAllowIpLiteral,
                resolveShorteners = false,
                allowLoopback = false,
            )
            if (rescreen is UrlPipeline.Result.Reject) {
                throw RedirectBlockedException(rescreen.error)
            }
            url = (rescreen as UrlPipeline.Result.Pass).finalUrl
        }
        throw TooManyRedirectsException()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun matchesDomain(host: String, pattern: String): Boolean =
        matchesDomainImpl(host, pattern)

    private fun isAllowedContentType(ct: String): Boolean {
        val lower = ct.lowercase()
        return ALLOWED_CONTENT_TYPES.any { lower.startsWith(it) }
    }

    /**
     * Returns true if [prefixBytes] look like binary (non-text) content.
     *
     * Two heuristics:
     *  1. NUL byte in the first 512 bytes — text files virtually never contain 0x00.
     *  2. Known binary magic numbers at offset 0:
     *       0x89 50 4E 47 — PNG
     *       FF D8 FF      — JPEG
     *       47 49 46 38   — GIF
     *       25 50 44 46   — PDF (%PDF)
     *       50 4B 03 04   — ZIP / JAR / DOCX
     *       7F 45 4C 46   — ELF
     *       4D 5A         — Windows PE (MZ)
     *       CA FE BA BE   — Mach-O fat binary
     *       FE ED FA CE   — Mach-O 32-bit
     *       FE ED FA CF   — Mach-O 64-bit
     */
    internal fun sniffIsBinary(prefixBytes: ByteArray): Boolean {
        val limit = minOf(512, prefixBytes.size)
        // Heuristic 1: NUL byte
        for (i in 0 until limit) {
            if (prefixBytes[i] == 0x00.toByte()) return true
        }
        // Heuristic 2: magic bytes
        if (prefixBytes.size < 2) return false
        val b0 = prefixBytes[0].toInt() and 0xFF
        val b1 = prefixBytes[1].toInt() and 0xFF
        val b2 = if (prefixBytes.size > 2) prefixBytes[2].toInt() and 0xFF else -1
        val b3 = if (prefixBytes.size > 3) prefixBytes[3].toInt() and 0xFF else -1
        return when {
            // PNG: 89 50 4E 47
            b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47 -> true
            // JPEG: FF D8 FF
            b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF -> true
            // GIF: 47 49 46 38
            b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38 -> true
            // PDF: 25 50 44 46
            b0 == 0x25 && b1 == 0x50 && b2 == 0x44 && b3 == 0x46 -> true
            // ZIP/JAR/DOCX: 50 4B 03 04
            b0 == 0x50 && b1 == 0x4B && b2 == 0x03 && b3 == 0x04 -> true
            // ELF: 7F 45 4C 46
            b0 == 0x7F && b1 == 0x45 && b2 == 0x4C && b3 == 0x46 -> true
            // Windows PE (MZ): 4D 5A
            b0 == 0x4D && b1 == 0x5A -> true
            // Mach-O fat: CA FE BA BE
            b0 == 0xCA && b1 == 0xFE && b2 == 0xBA && b3 == 0xBE -> true
            // Mach-O 32-bit: FE ED FA CE
            b0 == 0xFE && b1 == 0xED && b2 == 0xFA && b3 == 0xCE -> true
            // Mach-O 64-bit: FE ED FA CF
            b0 == 0xFE && b1 == 0xED && b2 == 0xFA && b3 == 0xCF -> true
            else -> false
        }
    }

    private suspend fun persistAllowlistEntry(host: String, outcome: ApprovalGate.Decision.AddToAllowlist) {
        // I7 — Serialize read-modify-write so two parallel fetches both Add-to-allowlisting
        // can't lose-update each other. Mutex is per-engine (one engine per project).
        allowlistMutex.withLock {
            val current = settings.getWebAllowlist().toMutableList()
            val domain = computeAllowlistDomain(host, outcome.subdomainGlob)
            current += DomainAllowlistEntry(
                domain = domain,
                httpOk = outcome.allowHttp,
                addedAt = Instant.now(),
            )
            settings.setWebAllowlist(current)
        }
    }

    private fun failure(err: WebError, startMs: Long, url: String): ToolResult<WebPage> {
        auditError(err, url)
        return ToolResult.error(
            summary = "${err.code}: ${err.message}",
            hint = if (err.recoverable) "RECOVERABLE" else "FATAL",
        )
    }

    private fun auditSuccess(page: WebPage, correlationId: String) {
        auditLog.append(
            WebAuditRecord(
                ts = Instant.now(),
                op = "fetch",
                agentSessionId = correlationId,
                url = page.originalUrl,
                finalUrl = page.finalUrl,
                query = null,
                provider = null,
                allowlistDecision = page.allowlistDecision,
                screenerFlags = page.screenerFlags.map { it.name },
                ssrfPass = true,
                httpStatus = null,
                contentType = page.contentType,
                responseBytes = page.responseBytes,
                extractedChars = page.extractedChars,
                resultCount = null,
                sanitizerVerdict = page.sanitizerVerdict,
                sanitizerNotes = page.sanitizerNotes,
                elapsedMs = page.elapsedMs,
                error = null,
            )
        )
    }

    private fun auditError(err: WebError, url: String) {
        auditLog.append(
            WebAuditRecord(
                ts = Instant.now(),
                op = "fetch",
                agentSessionId = null,
                url = url,
                finalUrl = null,
                query = null,
                provider = null,
                allowlistDecision = null,
                screenerFlags = emptyList(),
                ssrfPass = !err.code.startsWith("URL_BLOCKED_"),
                httpStatus = null,
                contentType = null,
                responseBytes = null,
                extractedChars = null,
                resultCount = null,
                sanitizerVerdict = null,
                sanitizerNotes = null,
                elapsedMs = 0,
                error = err.code,
            )
        )
    }
}
