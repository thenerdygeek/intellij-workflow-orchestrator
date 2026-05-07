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
