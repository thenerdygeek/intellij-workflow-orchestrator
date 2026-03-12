package com.workflow.orchestrator.jira.api

import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.http.RetryInterceptor
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.jira.api.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class JiraApiClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenProvider, AuthScheme.BEARER))
            .addInterceptor(RetryInterceptor())
            .build()
    }

    suspend fun getBoards(boardType: String = ""): ApiResult<List<JiraBoard>> {
        val typeFilter = if (boardType.isNotBlank()) "?type=$boardType" else ""
        return get<JiraBoardSearchResult>("/rest/agile/1.0/board$typeFilter").map { it.values }
    }

    suspend fun getActiveSprints(boardId: Int): ApiResult<List<JiraSprint>> =
        get<JiraSprintSearchResult>("/rest/agile/1.0/board/$boardId/sprint?state=active")
            .map { it.values }

    suspend fun getSprintIssues(sprintId: Int): ApiResult<List<JiraIssue>> {
        val jql = URLEncoder.encode("assignee=currentUser()", "UTF-8")
        return get<JiraIssueSearchResult>("/rest/agile/1.0/sprint/$sprintId/issue?jql=$jql")
            .map { it.issues }
    }

    suspend fun getIssue(key: String): ApiResult<JiraIssue> =
        get("/rest/api/2/issue/$key?expand=issuelinks")

    suspend fun getTransitions(
        issueKey: String,
        expandFields: Boolean = false
    ): ApiResult<List<JiraTransition>> {
        val expand = if (expandFields) "?expand=transitions.fields" else ""
        return get<JiraTransitionList>("/rest/api/2/issue/$issueKey/transitions$expand")
            .map { it.transitions }
    }

    suspend fun transitionIssue(
        issueKey: String,
        transitionId: String,
        fields: Map<String, Any>? = null,
        comment: String? = null
    ): ApiResult<Unit> {
        val body = buildTransitionPayload(transitionId, fields, comment)
        return post("/rest/api/2/issue/$issueKey/transitions", body)
    }

    private fun buildTransitionPayload(
        transitionId: String,
        fields: Map<String, Any>?,
        comment: String?
    ): String {
        val sb = StringBuilder()
        sb.append("""{"transition":{"id":"$transitionId"}""")
        if (!fields.isNullOrEmpty()) {
            sb.append(""","fields":{""")
            sb.append(fields.entries.joinToString(",") { (k, v) ->
                val valueJson = when (v) {
                    is Map<*, *> -> {
                        v.entries.joinToString(",", "{", "}") { (mk, mv) ->
                            """"$mk":"$mv""""
                        }
                    }
                    else -> """{"name":"${v.toString().replace("\"", "\\\"")}"}"""
                }
                """"$k":$valueJson"""
            })
            sb.append("}")
        }
        if (comment != null) {
            val escaped = comment.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            sb.append(""","update":{"comment":[{"add":{"body":"$escaped"}}]}""")
        }
        sb.append("}")
        return sb.toString()
    }

    private suspend inline fun <reified T> get(path: String): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path").get().build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val bodyStr = it.body?.string() ?: ""
                            ApiResult.Success(json.decodeFromString<T>(bodyStr))
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Resource not found")
                        429 -> ApiResult.Error(ErrorType.RATE_LIMITED, "Jira rate limit exceeded")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Jira returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira: ${e.message}", e)
            }
        }

    private suspend fun post(path: String, jsonBody: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val body = jsonBody.toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("$baseUrl$path").post(body).build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299, 204 -> ApiResult.Success(Unit)
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Jira returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira: ${e.message}", e)
            }
        }
}
