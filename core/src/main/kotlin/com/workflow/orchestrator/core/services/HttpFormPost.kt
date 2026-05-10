package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Cross-cutting helper for `application/x-www-form-urlencoded` POSTs.
 *
 * **Why this exists.** Two Atlassian endpoints in this plugin require form-encoded
 * bodies — Bamboo's `/rest/api/latest/queue/{planKey}` (which silently drops
 * variables when sent JSON) and Bamboo's Struts admin `restartBuild.action`. The
 * 2026-05-07 audit found `triggerBuild` had been silently dropping every
 * `dockerTagsAsJson` override for months because the JSON body was being ignored
 * by the server. PR 2 of the fix plan switches `queueBuild` onto this helper;
 * PR 7 switches `rerunFailedJobs` onto it.
 *
 * **The XSRF header.** Every form-encoded write to Atlassian's REST APIs needs
 * `X-Atlassian-Token: no-check`. The probe empirically confirmed this — without
 * the header, Bamboo's queue endpoint rejects the request with 403. Bundling the
 * header into this helper means every form-encoded mutation gets it for free, so
 * future callers can't accidentally omit it.
 *
 * **Return type.** The helper returns `ApiResult<String>` (raw response body) so
 * callers can decide how to deserialize. Bamboo's queue endpoint returns JSON,
 * the Struts admin endpoint returns HTML — one helper, two parse paths.
 *
 * **Threading.** Runs on [Dispatchers.IO] per `:core` threading conventions.
 *
 * @param client the OkHttp client to use (pass the per-service client from
 *   [com.workflow.orchestrator.core.http.HttpClientFactory.clientFor] so auth +
 *   retry interceptors apply).
 * @param url the absolute target URL.
 * @param formFields key/value pairs to URL-encode into the request body. Use
 *   [emptyMap] for an empty body (e.g., Bamboo's `executeAllStages=true` query
 *   param mode where the body holds no variables).
 * @param extraHeaders optional extra headers (rare — `X-Atlassian-Token: no-check`
 *   is set automatically; callers don't need to pass it).
 */
suspend fun postForm(
    client: OkHttpClient,
    url: String,
    formFields: Map<String, String>,
    extraHeaders: Map<String, String> = emptyMap()
): ApiResult<String> = withContext(Dispatchers.IO) {
    try {
        val bodyBuilder = FormBody.Builder()
        for ((k, v) in formFields) bodyBuilder.add(k, v)
        val body = bodyBuilder.build()

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body)
            // Atlassian XSRF bypass — empirically required on Bamboo's queue + Struts
            // admin endpoints; per Atlassian docs, any form-encoded write needs it.
            .header("X-Atlassian-Token", "no-check")
            .header("Accept", "application/json")
        for ((k, v) in extraHeaders) requestBuilder.header(k, v)

        val response = client.newCall(requestBuilder.build()).execute()
        response.use {
            val bodyStr = it.body?.string().orEmpty()
            when {
                isSuccess(it.code) -> {
                    if (looksLikeAuthRedirect(it.headers)) {
                        ApiResult.Error(
                            ErrorType.AUTH_REDIRECT,
                            "Server returned HTML — your session may have expired. Re-authenticate in Settings."
                        )
                    } else {
                        ApiResult.Success(bodyStr)
                    }
                }
                it.code == 401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid token")
                it.code == 403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient permissions")
                it.code == 404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Resource not found")
                it.code == 429 -> ApiResult.Error(ErrorType.RATE_LIMITED, "Rate limit exceeded")
                else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Server returned ${it.code}: ${bodyStr.take(200)}")
            }
        }
    } catch (e: IOException) {
        ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach server: ${e.message}", e)
    }
}

/**
 * Form-encoded POST that does NOT follow redirects, intended for Bamboo Struts
 * action endpoints that return 302 on success.
 *
 * **Why a separate helper.** Bamboo's Struts action endpoints (e.g.
 * `/build/admin/ajax/runChainAction.action`) return a 302 redirect to the build
 * dashboard on success. When OkHttp follows that redirect (its default behaviour),
 * it lands on a 200 HTML page whose `Content-Type: text/html` body triggers
 * [looksLikeAuthRedirect] — correctly for genuine auth-expiry redirects to
 * `/login`, but incorrectly for this normal Struts success flow. Disabling
 * redirect-following for just this path breaks the false-positive chain without
 * touching [postForm]'s semantics for all other callers.
 *
 * **Auth-redirect detection.** With `followRedirects(false)`, a genuine auth
 * redirect surfaces as a 302 with a `Location` pointing to a login path. This
 * helper inspects the `Location` header for typical Atlassian login path tokens
 * (`/login`, `permissionViolation`, `usernotloggedin`) and maps those to
 * [ErrorType.AUTH_REDIRECT]. Any other 302/303 is treated as success.
 *
 * **Other status codes.** 200 is success; 401/403/404/429 map to the canonical
 * error types (same as [postForm]); anything else is [ErrorType.SERVER_ERROR].
 *
 * **Thread safety.** Runs on [Dispatchers.IO]. The no-redirect client variant is
 * constructed per-call via `client.newBuilder()` — cheap because OkHttp reuses
 * the underlying connection pool and dispatcher from the parent client.
 *
 * @param client the per-service client (auth/retry interceptors apply).
 * @param url the absolute action endpoint URL.
 * @param formFields key/value pairs for the form body.
 * @param extraHeaders optional additional headers.
 */
suspend fun postFormNoRedirect(
    client: OkHttpClient,
    url: String,
    formFields: Map<String, String>,
    extraHeaders: Map<String, String> = emptyMap()
): ApiResult<String> = withContext(Dispatchers.IO) {
    try {
        val bodyBuilder = FormBody.Builder()
        for ((k, v) in formFields) bodyBuilder.add(k, v)
        val body = bodyBuilder.build()

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body)
            .header("X-Atlassian-Token", "no-check")
            .header("Accept", "application/json")
        for ((k, v) in extraHeaders) requestBuilder.header(k, v)

        // Disable redirect-following so the caller sees the raw 302/303 from the
        // Struts action endpoint rather than the followed HTML dashboard page.
        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        val response = noRedirectClient.newCall(requestBuilder.build()).execute()
        response.use {
            val bodyStr = it.body?.string().orEmpty()
            when {
                // 200 success — still guard against HTML (genuine session expiry
                // can produce 200 with login HTML when the session cookie expired
                // between calls, even on endpoints that normally return JSON).
                isSuccess(it.code) -> {
                    if (looksLikeAuthRedirect(it.headers)) {
                        ApiResult.Error(
                            ErrorType.AUTH_REDIRECT,
                            "Server returned HTML — your session may have expired. Re-authenticate in Settings."
                        )
                    } else {
                        ApiResult.Success(bodyStr)
                    }
                }
                // 302/303 — normal Struts-action success. Distinguish by Location.
                it.code == 302 || it.code == 303 -> {
                    val location = it.header("Location").orEmpty()
                    if (isAuthRedirectLocation(location)) {
                        ApiResult.Error(
                            ErrorType.AUTH_REDIRECT,
                            "Redirected to login — your session may have expired. Re-authenticate in Settings."
                        )
                    } else {
                        // Non-auth redirect = Struts success; body is irrelevant.
                        ApiResult.Success(bodyStr)
                    }
                }
                it.code == 401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid token")
                it.code == 403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient permissions")
                it.code == 404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Resource not found")
                it.code == 429 -> ApiResult.Error(ErrorType.RATE_LIMITED, "Rate limit exceeded")
                else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Server returned ${it.code}: ${bodyStr.take(200)}")
            }
        }
    } catch (e: IOException) {
        ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach server: ${e.message}", e)
    }
}

/**
 * Returns true if [location] looks like an Atlassian login redirect.
 *
 * Typical Atlassian session-expiry redirect locations:
 *  - `https://bamboo.example.com/userlogin.action?...`
 *  - `https://bamboo.example.com/login.jsp?permissionViolation=true`
 *  - `...?usernotloggedin=true`
 *  - `...?os_destination=...`
 */
private fun isAuthRedirectLocation(location: String): Boolean {
    if (location.isBlank()) return false
    val lower = location.lowercase()
    return lower.contains("/login") ||
        lower.contains("userlogin") ||
        lower.contains("permissionviolation") ||
        lower.contains("usernotloggedin")
}
