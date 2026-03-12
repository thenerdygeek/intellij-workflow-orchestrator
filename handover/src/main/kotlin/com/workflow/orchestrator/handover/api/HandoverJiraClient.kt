package com.workflow.orchestrator.handover.api

import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.http.RetryInterceptor
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.handover.api.dto.JiraCommentResponse
import com.workflow.orchestrator.handover.api.dto.JiraTransition
import com.workflow.orchestrator.handover.api.dto.JiraTransitionsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.util.concurrent.TimeUnit

class HandoverJiraClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?
) {
    private val log = Logger.getInstance(HandoverJiraClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenProvider, AuthScheme.BEARER))
            .addInterceptor(RetryInterceptor())
            .build()
    }

    suspend fun addComment(issueKey: String, wikiMarkupBody: String): ApiResult<JiraCommentResponse> =
        withContext(Dispatchers.IO) {
            log.info("[Handover:Jira] Posting comment to issue $issueKey")
            log.debug("[Handover:Jira] Comment body length: ${wikiMarkupBody.length} chars")
            try {
                val payload = buildJsonObject { put("body", wikiMarkupBody) }.toString()
                val body = payload.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/2/issue/$issueKey/comment")
                    .post(body)
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val bodyStr = it.body?.string() ?: ""
                            log.info("[Handover:Jira] Comment posted successfully to $issueKey")
                            ApiResult.Success(json.decodeFromString<JiraCommentResponse>(bodyStr))
                        }
                        401 -> { log.error("[Handover:Jira] Auth failed posting comment to $issueKey"); ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token") }
                        403 -> { log.error("[Handover:Jira] Forbidden posting comment to $issueKey"); ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions") }
                        404 -> { log.error("[Handover:Jira] Issue not found: $issueKey"); ApiResult.Error(ErrorType.NOT_FOUND, "Jira issue not found: $issueKey") }
                        else -> { log.error("[Handover:Jira] Unexpected response ${it.code} posting comment to $issueKey"); ApiResult.Error(ErrorType.SERVER_ERROR, "Jira returned ${it.code}") }
                    }
                }
            } catch (e: IOException) {
                log.error("[Handover:Jira] Network error posting comment to $issueKey", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira: ${e.message}", e)
            }
        }

    suspend fun logWork(
        issueKey: String,
        timeSpentSeconds: Int,
        comment: String?,
        started: String
    ): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            log.info("[Handover:Jira] Logging work to issue $issueKey: ${timeSpentSeconds}s, started=$started")
            try {
                val payload = buildJsonObject {
                    put("timeSpentSeconds", timeSpentSeconds)
                    put("started", started)
                    if (comment != null) {
                        put("comment", comment)
                    }
                }.toString()
                val body = payload.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/2/issue/$issueKey/worklog")
                    .post(body)
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> { log.info("[Handover:Jira] Worklog posted successfully to $issueKey"); ApiResult.Success(Unit) }
                        401 -> { log.error("[Handover:Jira] Auth failed logging work to $issueKey"); ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token") }
                        403 -> { log.error("[Handover:Jira] Forbidden logging work to $issueKey"); ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions") }
                        404 -> { log.error("[Handover:Jira] Issue not found for worklog: $issueKey"); ApiResult.Error(ErrorType.NOT_FOUND, "Jira issue not found: $issueKey") }
                        else -> { log.error("[Handover:Jira] Unexpected response ${it.code} logging work to $issueKey"); ApiResult.Error(ErrorType.SERVER_ERROR, "Jira returned ${it.code}") }
                    }
                }
            } catch (e: IOException) {
                log.error("[Handover:Jira] Network error logging work to $issueKey", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira: ${e.message}", e)
            }
        }

    suspend fun getTransitions(issueKey: String): ApiResult<List<JiraTransition>> =
        withContext(Dispatchers.IO) {
            log.info("[Handover:Jira] Fetching transitions for issue $issueKey")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/2/issue/$issueKey/transitions")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val bodyStr = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<JiraTransitionsResponse>(bodyStr)
                            log.info("[Handover:Jira] Found ${parsed.transitions.size} transitions for $issueKey")
                            ApiResult.Success(parsed.transitions)
                        }
                        401 -> { log.error("[Handover:Jira] Auth failed fetching transitions for $issueKey"); ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token") }
                        404 -> { log.error("[Handover:Jira] Issue not found for transitions: $issueKey"); ApiResult.Error(ErrorType.NOT_FOUND, "Jira issue not found: $issueKey") }
                        else -> { log.error("[Handover:Jira] Unexpected response ${it.code} fetching transitions for $issueKey"); ApiResult.Error(ErrorType.SERVER_ERROR, "Jira returned ${it.code}") }
                    }
                }
            } catch (e: IOException) {
                log.error("[Handover:Jira] Network error fetching transitions for $issueKey", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira: ${e.message}", e)
            }
        }

    suspend fun transitionIssue(issueKey: String, transitionId: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            log.info("[Handover:Jira] Transitioning issue $issueKey with transitionId=$transitionId")
            try {
                val payload = buildJsonObject {
                    putJsonObject("transition") {
                        put("id", transitionId)
                    }
                }.toString()
                val body = payload.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/2/issue/$issueKey/transitions")
                    .post(body)
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> { log.info("[Handover:Jira] Transition executed successfully for $issueKey"); ApiResult.Success(Unit) }
                        401 -> { log.error("[Handover:Jira] Auth failed transitioning $issueKey"); ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token") }
                        403 -> { log.error("[Handover:Jira] Forbidden transitioning $issueKey"); ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions") }
                        404 -> { log.error("[Handover:Jira] Issue not found for transition: $issueKey"); ApiResult.Error(ErrorType.NOT_FOUND, "Jira issue not found: $issueKey") }
                        else -> { log.error("[Handover:Jira] Unexpected response ${it.code} transitioning $issueKey"); ApiResult.Error(ErrorType.SERVER_ERROR, "Jira returned ${it.code}") }
                    }
                }
            } catch (e: IOException) {
                log.error("[Handover:Jira] Network error transitioning $issueKey", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira: ${e.message}", e)
            }
        }
}
