package com.workflow.orchestrator.jira.api

import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.http.RetryInterceptor
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.jira.TransitionInput
import com.workflow.orchestrator.core.model.jira.TransitionMeta
import com.workflow.orchestrator.jira.api.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        com.workflow.orchestrator.core.http.HttpClientFactory.sharedPool.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenProvider, AuthScheme.BEARER))
            .addInterceptor(RetryInterceptor())
            .build()
    }

    suspend fun getBoards(boardType: String = "", nameFilter: String = ""): ApiResult<List<JiraBoard>> {
        log.debug("[Jira:API] GET /rest/agile/1.0/board (type=$boardType, name=$nameFilter)")
        val params = mutableListOf("maxResults=200")
        if (boardType.isNotBlank()) params.add("type=$boardType")
        if (nameFilter.isNotBlank()) params.add("name=${URLEncoder.encode(nameFilter, "UTF-8")}")
        return get<JiraBoardSearchResult>("/rest/agile/1.0/board?${params.joinToString("&")}").map { it.values }
    }

    suspend fun getActiveSprints(boardId: Int): ApiResult<List<JiraSprint>> {
        log.debug("[Jira:API] GET /rest/agile/1.0/board/$boardId/sprint?state=active")
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
        log.debug("[Jira:API] GET sprint issues for sprintId=$sprintId (allUsers=$allUsers)")
        return get<JiraIssueSearchResult>("/rest/agile/1.0/sprint/$sprintId/issue?$query")
            .map { it.issues }
    }

    suspend fun getBoardIssues(boardId: Int, allUsers: Boolean = false): ApiResult<List<JiraIssue>> {
        val jqlParts = mutableListOf("resolution=Unresolved")
        if (!allUsers) jqlParts.add("assignee=currentUser()")
        val jql = URLEncoder.encode(jqlParts.joinToString(" AND "), "UTF-8")
        log.debug("[Jira:API] GET board issues for boardId=$boardId (allUsers=$allUsers)")
        return get<JiraIssueSearchResult>("/rest/agile/1.0/board/$boardId/issue?jql=$jql&maxResults=200")
            .map { it.issues }
    }

    suspend fun getIssue(key: String): ApiResult<JiraIssue> {
        log.debug("[Jira:API] GET /rest/api/2/issue/$key")
        return get("/rest/api/2/issue/$key?expand=issuelinks")
    }

    /**
     * Fetches a Jira issue with the full set of fields needed for PR context generation.
     * Requests `renderedFields` so that the description is available as rendered HTML
     * (which is easier to strip to plain text than wiki markup).
     *
     * GET /rest/api/2/issue/{key}
     *   ?fields=summary,description,status,priority,issuetype,assignee,reporter,
     *           labels,components,fixVersions,comment
     *   &expand=renderedFields
     */
    suspend fun getIssueWithContext(key: String): ApiResult<JiraIssueWithRendered> {
        val fields = "summary,description,status,priority,issuetype,assignee,reporter," +
                "labels,components,fixVersions,comment"
        log.debug("[Jira:API] GET /rest/api/2/issue/$key (context fetch, expand=renderedFields)")
        return get("/rest/api/2/issue/$key?fields=$fields&expand=renderedFields")
    }

    suspend fun searchIssues(text: String, maxResults: Int = 20, currentUserOnly: Boolean = true): ApiResult<List<JiraIssue>> {
        val escaped = escapeJql(text)
        val looksLikeKey = text.matches(Regex("[A-Z][A-Z0-9]+-\\d+"))
        val assigneeClause = if (currentUserOnly) " AND assignee = currentUser()" else ""
        val jql = if (looksLikeKey) {
            "(text ~ \"$escaped\" OR key = \"$escaped\")${assigneeClause} ORDER BY updated DESC"
        } else {
            "text ~ \"$escaped\"${assigneeClause} ORDER BY updated DESC"
        }
        val encodedJql = URLEncoder.encode(jql, "UTF-8")
        log.debug("[Jira:API] GET /rest/api/2/search (text=$text, maxResults=$maxResults)")
        return get<JiraIssueSearchResult>(
            "/rest/api/2/search?jql=$encodedJql&maxResults=$maxResults&fields=summary,status,issuetype,priority,assignee"
        ).map { it.issues }
    }

    suspend fun searchByJql(jql: String, maxResults: Int = 8): ApiResult<List<JiraIssue>> {
        val encodedJql = URLEncoder.encode(jql, "UTF-8")
        log.debug("[Jira:API] GET /rest/api/2/search (jql=$jql, maxResults=$maxResults)")
        return get<JiraIssueSearchResult>(
            "/rest/api/2/search?jql=$encodedJql&maxResults=$maxResults&fields=summary,status,issuetype,priority,assignee,attachment"
        ).map { it.issues }
    }

    /** Uses expand=transitions.fields by default to populate TransitionField schema. */
    suspend fun getTransitions(
        issueKey: String,
        expandFields: Boolean = true
    ): ApiResult<List<TransitionMeta>> {
        log.debug("[Jira:API] Fetching transitions for $issueKey (expandFields=$expandFields)")
        val path = if (expandFields) {
            "/rest/api/2/issue/$issueKey/transitions?expand=transitions.fields"
        } else {
            "/rest/api/2/issue/$issueKey/transitions"
        }
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path").get().build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    log.debug("[Jira:API] GET $path -> ${it.code}")
                    when (it.code) {
                        in 200..299 -> {
                            val bodyStr = it.body?.string() ?: ""
                            ApiResult.Success(JiraTransitionResponseParser(json).parse(bodyStr))
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token").also { _ ->
                            log.warn("[Jira:API] Authentication failed (401)")
                        }
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions").also { _ ->
                            log.warn("[Jira:API] Forbidden (403)")
                        }
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Resource not found").also { _ ->
                            log.warn("[Jira:API] Resource not found (404)")
                        }
                        429 -> ApiResult.Error(ErrorType.RATE_LIMITED, "Jira rate limit exceeded").also { _ ->
                            log.warn("[Jira:API] Rate limited (429)")
                        }
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Jira returned ${it.code}").also { _ ->
                            log.warn("[Jira:API] Server error (${response.code})")
                        }
                    }
                }
            } catch (e: IOException) {
                log.warn("[Jira:API] Network error: ${e.message}")
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira: ${e.message}", e)
            }
        }
    }

    suspend fun postWorklog(issueKey: String, timeSpent: String, comment: String? = null): ApiResult<Unit> {
        log.debug("[Jira:API] POST /rest/api/2/issue/$issueKey/worklog (timeSpent=$timeSpent, comment=${comment != null})")
        val body = buildJsonObject {
            put("timeSpent", timeSpent)
            if (comment != null) put("comment", comment)
        }.toString()
        return post("/rest/api/2/issue/$issueKey/worklog", body)
    }

    /**
     * Adds a standalone comment to a Jira issue.
     * POST /rest/api/2/issue/{key}/comment
     */
    suspend fun addComment(issueKey: String, body: String): ApiResult<Unit> {
        log.debug("[Jira:API] POST /rest/api/2/issue/$issueKey/comment")
        val payload = buildJsonObject {
            put("body", body)
        }.toString()
        return post("/rest/api/2/issue/$issueKey/comment", payload)
    }

    /**
     * Fetches the raw string value of a single custom field for an issue.
     * Returns the field value as a String if it is a JSON primitive (string),
     * or the serialized JSON for objects (caller can decide how to present it).
     * Returns null (wrapped in Success) when the field is absent or null in the response.
     *
     * Delegates to [getRaw] so that any future cross-cutting concerns added there
     * (retry, rate-limit handling, TLS config) are automatically picked up here.
     *
     * GET /rest/api/2/issue/{key}?fields={fieldId}
     */
    suspend fun getCustomFieldValue(issueKey: String, fieldId: String): ApiResult<String?> {
        log.debug("[Jira:API] GET /rest/api/2/issue/$issueKey?fields=$fieldId (custom field)")
        return when (val raw = getRaw("/rest/api/2/issue/$issueKey?fields=$fieldId")) {
            is ApiResult.Error -> raw
            is ApiResult.Success -> {
                val fieldsObj = raw.data["fields"] as? JsonObject
                val fieldVal = fieldsObj?.get(fieldId)
                val strVal = when {
                    fieldVal == null || fieldVal is JsonNull -> null
                    fieldVal is JsonPrimitive -> fieldVal.content
                    else -> fieldVal.toString()
                }
                ApiResult.Success(strVal)
            }
        }
    }

    /**
     * Returns the raw response body string for an arbitrary Jira REST path.
     * Useful for endpoints that return JSON arrays (not objects) or whose shapes
     * are traversed manually rather than decoded into a typed DTO.
     *
     * @param path   A relative Jira REST path, e.g. `/rest/api/2/user/search?query=jd`.
     *               The configured [baseUrl] is prepended automatically.
     * @param statusCode  When non-null, the caller receives the HTTP status code via this
     *                    single-element int array (index 0). Used by callers that need to
     *                    distinguish 404 from other errors before calling this method.
     */
    suspend fun getRawString(path: String, statusCode: IntArray? = null): ApiResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val url = if (path.startsWith("http://") || path.startsWith("https://")) path
                          else "$baseUrl$path"
                val request = Request.Builder().url(url).get().build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    log.debug("[Jira:API] GET $path -> ${it.code}")
                    statusCode?.set(0, it.code)
                    when (it.code) {
                        in 200..299 -> ApiResult.Success(it.body?.string() ?: "")
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token").also { _ ->
                            log.warn("[Jira:API] Authentication failed (401) at $path")
                        }
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions").also { _ ->
                            log.warn("[Jira:API] Forbidden (403) at $path")
                        }
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Resource not found at $path").also { _ ->
                            log.warn("[Jira:API] Not found (404) at $path")
                        }
                        429 -> ApiResult.Error(ErrorType.RATE_LIMITED, "Jira rate limit exceeded").also { _ ->
                            log.warn("[Jira:API] Rate limited (429) at $path")
                        }
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Jira returned ${it.code}").also { _ ->
                            log.warn("[Jira:API] Server error (${it.code}) at $path")
                        }
                    }
                }
            } catch (e: IOException) {
                log.warn("[Jira:API] Network error at $path: ${e.message}")
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira: ${e.message}", e)
            }
        }

    /**
     * Shared raw-JSON GET helper used by methods that need access to the raw [JsonObject]
     * rather than a deserialized DTO. Uses the same OkHttp client, auth interceptor, retry
     * logic, and response-code mapping as the typed [get] helper, so all cross-cutting
     * concerns (TLS, rate-limiting, retries) are inherited automatically.
     *
     * @param path A relative Jira REST path, e.g. `/rest/api/2/issue/PROJ-1`. Do **not**
     *   pass a full URL — the configured [baseUrl] is prepended automatically.
     */
    private suspend fun getRaw(path: String): ApiResult<JsonObject> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path").get().build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    log.debug("[Jira:API] GET $path -> ${it.code}")
                    when (it.code) {
                        in 200..299 -> {
                            val contentType = it.header("Content-Type") ?: ""
                            if (contentType.isNotBlank() &&
                                !contentType.contains("application/json", ignoreCase = true) &&
                                !contentType.contains("text/json", ignoreCase = true)) {
                                return@withContext ApiResult.Error(
                                    ErrorType.PARSE_ERROR,
                                    "Unexpected response Content-Type: $contentType (expected JSON)"
                                )
                            }
                            val bodyStr = it.body?.string() ?: ""
                            val parsed = json.parseToJsonElement(bodyStr)
                            if (parsed is JsonObject) {
                                ApiResult.Success(parsed)
                            } else {
                                ApiResult.Error(ErrorType.PARSE_ERROR, "Expected JSON object at $path")
                            }
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token").also {
                            log.warn("[Jira:API] Authentication failed (401) at $path")
                        }
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions").also {
                            log.warn("[Jira:API] Forbidden (403) at $path")
                        }
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Resource not found at $path").also {
                            log.warn("[Jira:API] Not found (404) at $path")
                        }
                        429 -> ApiResult.Error(ErrorType.RATE_LIMITED, "Jira rate limit exceeded").also {
                            log.warn("[Jira:API] Rate limited (429) at $path")
                        }
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Jira returned ${it.code}").also { _ ->
                            log.warn("[Jira:API] Server error (${response.code}) at $path")
                        }
                    }
                }
            } catch (e: IOException) {
                log.warn("[Jira:API] Network error at $path: ${e.message}")
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira: ${e.message}", e)
            }
        }

    /**
     * Fetches the current user to validate the connection.
     * GET /rest/api/2/myself
     */
    suspend fun getCurrentUser(): ApiResult<JsonObject> {
        log.debug("[Jira:API] GET /rest/api/2/myself")
        return get("/rest/api/2/myself")
    }

    suspend fun transitionIssue(issueKey: String, input: TransitionInput): ApiResult<Unit> {
        log.debug("[Jira:API] POST /rest/api/2/issue/$issueKey/transitions (transitionId=${input.transitionId})")
        val bodyString = TransitionInputSerializer().buildBody(input).toString()
        return withContext(Dispatchers.IO) {
            try {
                val body = bodyString.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/2/issue/$issueKey/transitions")
                    .post(body)
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    log.debug("[Jira:API] POST /rest/api/2/issue/$issueKey/transitions -> ${it.code}")
                    when (it.code) {
                        in 200..299 -> ApiResult.Success(Unit)
                        400 -> {
                            val raw = it.body?.string().orEmpty()
                            val msg = parseJiraErrorMessage(raw) ?: "Bad request (400)"
                            log.warn("[Jira:API] Transition rejected (400) for $issueKey: $msg")
                            ApiResult.Error(ErrorType.VALIDATION_ERROR, msg)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token").also { _ ->
                            log.warn("[Jira:API] Authentication failed (401)")
                        }
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions").also { _ ->
                            log.warn("[Jira:API] Forbidden (403)")
                        }
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Issue or transition not found").also { _ ->
                            log.warn("[Jira:API] Not found (404)")
                        }
                        else -> {
                            val raw = it.body?.string().orEmpty()
                            val msg = parseJiraErrorMessage(raw) ?: "HTTP ${it.code}"
                            log.warn("[Jira:API] Server error (${it.code}) for transitionIssue $issueKey: $msg")
                            ApiResult.Error(ErrorType.SERVER_ERROR, msg)
                        }
                    }
                }
            } catch (e: IOException) {
                log.warn("[Jira:API] Network error transitioning $issueKey: ${e.message}")
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira: ${e.message}", e)
            }
        }
    }

    private fun parseJiraErrorMessage(raw: String): String? = try {
        val obj = Json.parseToJsonElement(raw).jsonObject
        val messages = mutableListOf<String>()
        obj["errorMessages"]?.jsonArray?.forEach { messages += it.jsonPrimitive.content }
        obj["errors"]?.jsonObject?.entries?.forEach { (k, v) -> messages += "$k: ${v.jsonPrimitive.content}" }
        messages.joinToString("; ").ifBlank { null }
    } catch (_: Exception) { null }

    /**
     * Fetches branches linked to a Jira issue via the dev-status API.
     * This is the same data shown in the Development Panel on the issue.
     *
     * GET /rest/dev-status/1.0/issue/detail?issueId={numericId}&applicationType=stash&dataType=branch
     *
     * Note: This is an internal Jira API (not officially supported) but has been
     * stable since Jira 7.x and powers Jira's own Development Panel.
     */
    suspend fun getDevStatusBranches(issueId: String): ApiResult<List<DevStatusBranch>> {
        log.debug("[Jira:API] GET /rest/dev-status/1.0/issue/detail (issueId=$issueId, type=stash, data=branch)")
        return try {
            val result = get<DevStatusResponse>(
                "/rest/dev-status/1.0/issue/detail?issueId=$issueId&applicationType=stash&dataType=branch"
            )
            result.map { response ->
                response.detail.flatMap { it.branches }
            }
        } catch (e: Exception) {
            log.warn("[Jira:API] Dev-status API failed (may not be available): ${e.message}")
            ApiResult.Error(ErrorType.SERVER_ERROR, "Dev-status API unavailable: ${e.message}", e)
        }
    }

    /**
     * Fetches comments for a Jira issue.
     * GET /rest/api/2/issue/{key}/comment
     */
    suspend fun getComments(
        issueKey: String,
        maxResults: Int = 50
    ): ApiResult<List<JiraComment>> {
        log.debug("[Jira:API] GET /rest/api/2/issue/$issueKey/comment")
        return get<JiraCommentSearchResult>(
            "/rest/api/2/issue/$issueKey/comment?maxResults=$maxResults&orderBy=-created"
        ).map { it.comments }
    }

    /**
     * Validates ticket keys by batch search.
     * Returns a map of valid key → TicketKeyInfo. Missing keys are not in the map.
     */
    suspend fun validateTicketKeys(keys: List<String>): ApiResult<Map<String, TicketKeyInfo>> {
        if (keys.isEmpty()) return ApiResult.Success(emptyMap())

        // Batch in groups of 100 (JQL IN clause limit)
        val allResults = mutableMapOf<String, TicketKeyInfo>()
        for (batch in keys.chunked(100)) {
            val jql = "key in (${batch.joinToString(",")})"
            val encodedJql = URLEncoder.encode(jql, "UTF-8")
            log.debug("[Jira:API] Validating ${batch.size} ticket keys")
            val result = get<JiraIssueSearchResult>(
                "/rest/api/2/search?jql=$encodedJql&maxResults=${batch.size}&fields=summary,status"
            )
            when (result) {
                is ApiResult.Success -> {
                    for (issue in result.data.issues) {
                        allResults[issue.key] = TicketKeyInfo(
                            key = issue.key,
                            summary = issue.fields.summary,
                            status = issue.fields.status.name
                        )
                    }
                }
                is ApiResult.Error -> {
                    log.warn("[Jira:API] Ticket key validation failed: ${result.message}")
                }
            }
        }
        return ApiResult.Success(allResults)
    }

    suspend fun getWorklogs(issueKey: String, maxResults: Int = 20): ApiResult<JiraWorklogResponse> {
        val encoded = URLEncoder.encode(issueKey, "UTF-8")
        log.debug("[Jira:API] GET /rest/api/2/issue/$encoded/worklog")
        return get<JiraWorklogResponse>("/rest/api/2/issue/$encoded/worklog?maxResults=$maxResults")
    }

    suspend fun getClosedSprints(boardId: Int, startAt: Int = 0, maxResults: Int = 50): ApiResult<JiraSprintSearchResult> {
        log.debug("[Jira:API] GET /rest/agile/1.0/board/$boardId/sprint?state=closed&startAt=$startAt&maxResults=$maxResults")
        return get<JiraSprintSearchResult>("/rest/agile/1.0/board/$boardId/sprint?state=closed&startAt=$startAt&maxResults=$maxResults")
    }

    suspend fun getDevStatusPullRequests(issueId: String): ApiResult<List<DevStatusPullRequest>> {
        log.debug("[Jira:API] GET /rest/dev-status/1.0/issue/detail (issueId=$issueId, type=stash, data=pullrequest)")
        return try {
            val response = get<DevStatusResponse>(
                "/rest/dev-status/1.0/issue/detail?issueId=$issueId&applicationType=stash&dataType=pullrequest"
            )
            response.map { it.detail.flatMap { d -> d.pullRequests } }
        } catch (e: Exception) {
            log.warn("[Jira:API] Dev-status PR API failed: ${e.message}")
            ApiResult.Success(emptyList())
        }
    }

    private suspend inline fun <reified T> get(path: String): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path").get().build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    log.debug("[Jira:API] GET $path -> ${it.code}")
                    when (it.code) {
                        in 200..299 -> {
                            val contentType = it.header("Content-Type") ?: ""
                            if (contentType.isNotBlank() &&
                                !contentType.contains("application/json", ignoreCase = true) &&
                                !contentType.contains("text/json", ignoreCase = true)) {
                                return@withContext ApiResult.Error(
                                    ErrorType.PARSE_ERROR,
                                    "Unexpected response Content-Type: $contentType (expected JSON)"
                                )
                            }
                            val bodyStr = it.body?.string() ?: ""
                            ApiResult.Success(json.decodeFromString<T>(bodyStr))
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token").also {
                            log.warn("[Jira:API] Authentication failed (401)")
                        }
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions").also {
                            log.warn("[Jira:API] Forbidden (403)")
                        }
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Resource not found").also {
                            log.warn("[Jira:API] Resource not found (404)")
                        }
                        429 -> ApiResult.Error(ErrorType.RATE_LIMITED, "Jira rate limit exceeded").also {
                            log.warn("[Jira:API] Rate limited (429)")
                        }
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Jira returned ${it.code}").also { _ ->
                            log.warn("[Jira:API] Server error (${response.code})")
                        }
                    }
                }
            } catch (e: IOException) {
                log.warn("[Jira:API] Network error: ${e.message}")
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
                    log.debug("[Jira:API] POST $path -> ${it.code}")
                    when (it.code) {
                        in 200..299 -> ApiResult.Success(Unit)
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token").also {
                            log.warn("[Jira:API] Authentication failed (401)")
                        }
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions").also {
                            log.warn("[Jira:API] Forbidden (403)")
                        }
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Jira returned ${it.code}").also { _ ->
                            log.warn("[Jira:API] Server error (${response.code})")
                        }
                    }
                }
            } catch (e: IOException) {
                log.warn("[Jira:API] Network error: ${e.message}")
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira: ${e.message}", e)
            }
        }
}

/** Escapes JQL reserved characters in user-supplied text. Shared by [JiraApiClient] and Tasks integration. */
internal fun escapeJql(text: String): String {
    val reserved = setOf('+', '-', '&', '|', '!', '(', ')', '{', '}', '[', ']', '^', '"', '~', '*', '?', '\\', '/')
    return buildString {
        for (c in text) {
            if (c in reserved) append('\\')
            append(c)
        }
    }
}
