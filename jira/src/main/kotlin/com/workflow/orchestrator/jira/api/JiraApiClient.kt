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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class JiraApiClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?
) {
    private val log = Logger.getInstance(JiraApiClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenProvider, AuthScheme.BEARER))
            .addInterceptor(RetryInterceptor())
            .build()
    }

    suspend fun getBoards(boardType: String = "", nameFilter: String = ""): ApiResult<List<JiraBoard>> {
        log.info("[Jira:API] GET /rest/agile/1.0/board (type=$boardType, name=$nameFilter)")
        val params = mutableListOf<String>()
        if (boardType.isNotBlank()) params.add("type=$boardType")
        if (nameFilter.isNotBlank()) params.add("name=${URLEncoder.encode(nameFilter, "UTF-8")}")
        params.add("maxResults=50")
        val query = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
        return get<JiraBoardSearchResult>("/rest/agile/1.0/board$query").map { it.values }
    }

    suspend fun getActiveSprints(boardId: Int): ApiResult<List<JiraSprint>> {
        log.info("[Jira:API] GET /rest/agile/1.0/board/$boardId/sprint?state=active")
        return get<JiraSprintSearchResult>("/rest/agile/1.0/board/$boardId/sprint?state=active")
            .map { it.values }
    }

    suspend fun getSprintIssues(sprintId: Int, allUsers: Boolean = false): ApiResult<List<JiraIssue>> {
        val jqlParts = mutableListOf<String>()
        if (!allUsers) jqlParts.add("assignee=currentUser()")
        val query = if (jqlParts.isNotEmpty()) {
            "jql=${URLEncoder.encode(jqlParts.joinToString(" AND "), "UTF-8")}&maxResults=200"
        } else {
            "maxResults=200"
        }
        log.info("[Jira:API] GET sprint issues for sprintId=$sprintId (allUsers=$allUsers)")
        return get<JiraIssueSearchResult>("/rest/agile/1.0/sprint/$sprintId/issue?$query")
            .map { it.issues }
    }

    suspend fun getBoardIssues(boardId: Int, allUsers: Boolean = false): ApiResult<List<JiraIssue>> {
        val jqlParts = mutableListOf("resolution=Unresolved")
        if (!allUsers) jqlParts.add("assignee=currentUser()")
        val jql = URLEncoder.encode(jqlParts.joinToString(" AND "), "UTF-8")
        log.info("[Jira:API] GET board issues for boardId=$boardId (allUsers=$allUsers)")
        return get<JiraIssueSearchResult>("/rest/agile/1.0/board/$boardId/issue?jql=$jql&maxResults=200")
            .map { it.issues }
    }

    suspend fun getIssue(key: String): ApiResult<JiraIssue> {
        log.info("[Jira:API] GET /rest/api/2/issue/$key")
        return get("/rest/api/2/issue/$key?expand=issuelinks")
    }

    suspend fun getTransitions(
        issueKey: String,
        expandFields: Boolean = false
    ): ApiResult<List<JiraTransition>> {
        log.info("[Jira:API] Fetching transitions for $issueKey (expandFields=$expandFields)")
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
        log.info("[Jira:API] POST /rest/api/2/issue/$issueKey/transitions (transitionId=$transitionId)")
        val body = buildTransitionPayload(transitionId, fields, comment)
        return post("/rest/api/2/issue/$issueKey/transitions", body)
    }

    private fun buildTransitionPayload(
        transitionId: String,
        fields: Map<String, Any>?,
        comment: String?
    ): String {
        val payload = buildJsonObject {
            putJsonObject("transition") {
                put("id", transitionId)
            }
            if (!fields.isNullOrEmpty()) {
                putJsonObject("fields") {
                    for ((key, value) in fields) {
                        when (value) {
                            is Map<*, *> -> putJsonObject(key) {
                                for ((mk, mv) in value) {
                                    put(mk.toString(), mv.toString())
                                }
                            }
                            else -> putJsonObject(key) {
                                put("name", value.toString())
                            }
                        }
                    }
                }
            }
            if (comment != null) {
                putJsonObject("update") {
                    putJsonArray("comment") {
                        add(buildJsonObject {
                            putJsonObject("add") {
                                put("body", comment)
                            }
                        })
                    }
                }
            }
        }
        return payload.toString()
    }

    private suspend inline fun <reified T> get(path: String): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path").get().build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    log.info("[Jira:API] GET $path -> ${it.code}")
                    when (it.code) {
                        in 200..299 -> {
                            val bodyStr = it.body?.string() ?: ""
                            ApiResult.Success(json.decodeFromString<T>(bodyStr))
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token").also {
                            log.error("[Jira:API] Authentication failed for GET $path")
                        }
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions").also {
                            log.error("[Jira:API] Forbidden for GET $path")
                        }
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Resource not found").also {
                            log.warn("[Jira:API] Resource not found: GET $path")
                        }
                        429 -> ApiResult.Error(ErrorType.RATE_LIMITED, "Jira rate limit exceeded").also {
                            log.warn("[Jira:API] Rate limited on GET $path")
                        }
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Jira returned ${it.code}").also { _ ->
                            log.error("[Jira:API] Server error ${response.code} for GET $path")
                        }
                    }
                }
            } catch (e: IOException) {
                log.error("[Jira:API] Network error for GET $path: ${e.message}", e)
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
                    log.info("[Jira:API] POST $path -> ${it.code}")
                    when (it.code) {
                        in 200..299, 204 -> ApiResult.Success(Unit)
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token").also {
                            log.error("[Jira:API] Authentication failed for POST $path")
                        }
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions").also {
                            log.error("[Jira:API] Forbidden for POST $path")
                        }
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Jira returned ${it.code}").also { _ ->
                            log.error("[Jira:API] Server error ${response.code} for POST $path")
                        }
                    }
                }
            } catch (e: IOException) {
                log.error("[Jira:API] Network error for POST $path: ${e.message}", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira: ${e.message}", e)
            }
        }
}
