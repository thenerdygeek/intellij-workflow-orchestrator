package com.workflow.orchestrator.core.web

import com.workflow.orchestrator.core.security.UrlSafetyGuard

sealed class WebError(
    val code: String,
    val message: String,
    val recoverable: Boolean,
) {
    // URL screening ---------------------------------------------------------
    class MalformedUrl(url: String) : WebError("MALFORMED_URL", "Malformed URL: $url", false)
    class HttpDisallowed(url: String) : WebError("HTTPS_REQUIRED", "HTTP scheme not permitted for $url", false)
    class CredentialsInUrl(url: String) : WebError("CREDENTIALS_IN_URL", "URL contains userinfo: $url", false)
    class RawIpLiteral(url: String) : WebError("IP_LITERAL_DISALLOWED", "Raw IP literal not permitted: $url", false)
    class ShortenerUnresolved(url: String) : WebError("SHORTENER_UNRESOLVED", "Could not resolve shortener: $url", true)

    // SSRF ------------------------------------------------------------------
    class UrlBlocked(reason: UrlSafetyGuard.Reason, host: String)
        : WebError("URL_BLOCKED_$reason", "Host $host blocked by safety guard: $reason", false)

    // Allowlist / approval --------------------------------------------------
    class UnlistedHardReject(host: String) : WebError("UNLISTED_DOMAIN", "Domain not on allowlist: $host", true)
    class ApprovalDenied(host: String) : WebError("APPROVAL_DENIED", "User denied approval for: $host", true)
    class ApprovalTimeout(host: String) : WebError("APPROVAL_TIMEOUT", "Approval prompt timed out for: $host", true)

    // HTTP ------------------------------------------------------------------
    class HttpStatus(status: Int, url: String)
        : WebError("HTTP_$status", "HTTP $status for $url", status in 500..599)
    class HttpTimeout(stage: String) : WebError("HTTP_TIMEOUT_$stage", "HTTP timeout in stage $stage", true)
    class ResponseTooLarge(bytes: Long, capBytes: Long)
        : WebError("RESPONSE_TOO_LARGE", "Response $bytes bytes exceeds cap $capBytes", false)
    class UnsupportedContentType(ct: String)
        : WebError("UNSUPPORTED_CONTENT_TYPE", "Content-Type not allowed: $ct", false)

    // Sanitizer -------------------------------------------------------------
    object SanitizerTimeout : WebError("SANITIZER_TIMEOUT", "Sanitizer subagent timed out", true)
    class SanitizerRefused(notes: String)
        : WebError("SANITIZER_REFUSED", "Sanitizer refused content: $notes", false)

    // Search ----------------------------------------------------------------
    object NoProviderConfigured : WebError("NO_PROVIDER_CONFIGURED", "No web_search provider configured in settings", true)
    class ProviderAuthFailed(provider: String) : WebError("PROVIDER_AUTH_FAILED", "Auth failed for provider: $provider", true)
    class ProviderMalformedResponse(provider: String) : WebError("PROVIDER_MALFORMED_RESPONSE", "Malformed response from provider: $provider", true)
    /** The provider's configured base URL failed the UrlSafetyGuard / UrlPipeline screen. */
    class ProviderUrlUnsafe(provider: String, reason: String) : WebError("PROVIDER_URL_UNSAFE", "Provider $provider base URL rejected by safety guard: $reason", false)

    // Disabled via settings -------------------------------------------------
    class WebFetchDisabled : WebError("WEB_FETCH_DISABLED", "web_fetch is disabled in Workflow Orchestrator settings", false)
    class WebSearchDisabled : WebError("WEB_SEARCH_DISABLED", "web_search is disabled in Workflow Orchestrator settings", false)

    // Plan mode -------------------------------------------------------------
    object PlanModeBlocked : WebError("PLAN_MODE_BLOCKED", "Web tools disabled in plan mode", false)
}
