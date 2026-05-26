package com.workflow.orchestrator.jira.api

import com.workflow.orchestrator.core.http.HttpClientFactory
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.model.jira.TransitionInput
import com.workflow.orchestrator.core.model.jira.TransitionMeta
import com.workflow.orchestrator.jira.api.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
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
import okhttp3.Response
import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

class JiraApiClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?
) : DevStatusFetcher {
    private val log = Logger.getInstance(JiraApiClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient by lazy {
        HttpClientFactory(tokenProvider = { _ -> tokenProvider() })
            .clientFor(ServiceType.JIRA)
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
        // Defense-in-depth before building JQL into a GET query string (same shape as
        // jira:F-12 on searchTickets): cap length to bound URL size and reject control
        // characters. escapeJql already handles reserved JQL punctuation.
        if (text.length > MAX_SEARCH_TEXT_LENGTH) {
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Search text exceeds $MAX_SEARCH_TEXT_LENGTH characters")
        }
        if (text.any { it.code < 32 }) {
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Search text contains control characters")
        }
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

    /**
     * Paged JQL search variant used by the IntelliJ Tasks integration, which calls
     * [JiraTaskRepository.getIssues] with `(offset, limit)` and expects server-side
     * pagination. Mirrors [searchByJql] but threads `startAt` into the query string.
     */
    suspend fun searchByJqlPaged(jql: String, startAt: Int, maxResults: Int): ApiResult<List<JiraIssue>> {
        val encodedJql = URLEncoder.encode(jql, "UTF-8")
        log.debug("[Jira:API] GET /rest/api/2/search (jql=$jql, startAt=$startAt, maxResults=$maxResults)")
        return get<JiraIssueSearchResult>(
            "/rest/api/2/search?jql=$encodedJql&startAt=$startAt&maxResults=$maxResults&fields=summary,status,issuetype,priority,assignee"
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

    /**
     * Posts a worklog. `started` (if non-null) is sent as the `started` field in
     * Jira's expected format (`yyyy-MM-dd'T'HH:mm:ss.SSSZ`); when null, the server
     * defaults to "now". When [adjustEstimate] is set to anything other than `auto`
     * it is appended as a `?adjustEstimate=` query param.
     */
    suspend fun postWorklog(
        issueKey: String,
        timeSpent: String,
        comment: String? = null,
        started: String? = null,
        adjustEstimateParam: String? = null
    ): ApiResult<Unit> {
        log.debug(
            "[Jira:API] POST /rest/api/2/issue/$issueKey/worklog " +
                "(timeSpent=$timeSpent, comment=${comment != null}, started=$started, " +
                "adjustEstimate=$adjustEstimateParam)"
        )
        val body = buildJsonObject {
            put("timeSpent", timeSpent)
            if (comment != null) put("comment", comment)
            if (started != null) put("started", started)
        }.toString()
        val path = if (adjustEstimateParam != null) {
            "/rest/api/2/issue/$issueKey/worklog?adjustEstimate=$adjustEstimateParam"
        } else {
            "/rest/api/2/issue/$issueKey/worklog"
        }
        return post(path, body)
    }

    /**
     * Adds a standalone comment to a Jira issue.
     * POST /rest/api/2/issue/{key}/comment
     *
     * When [visibilityType] + [visibilityValue] are both non-null, a
     * `visibility: { type, value }` block is included in the body so the comment
     * is restricted to the named role / group. The visibility field is **omitted
     * entirely** when null — Jira rejects `visibility: null`.
     */
    suspend fun addComment(
        issueKey: String,
        body: String,
        visibilityType: String? = null,
        visibilityValue: String? = null
    ): ApiResult<Unit> {
        log.debug(
            "[Jira:API] POST /rest/api/2/issue/$issueKey/comment " +
                "(visibility=${if (visibilityType != null) "$visibilityType:$visibilityValue" else "none"})"
        )
        val payload = buildJsonObject {
            put("body", body)
            if (visibilityType != null && visibilityValue != null) {
                putJsonObject("visibility") {
                    put("type", visibilityType)
                    put("value", visibilityValue)
                }
            }
        }.toString()
        return post("/rest/api/2/issue/$issueKey/comment", payload)
    }

    /**
     * `GET /rest/api/2/project/{projectKey}/role`
     *
     * Returns a name-keyed object (not an array): `{"Developers":"<roleSelfUrl>", …}`.
     * Caller flattens to a list of role names. The role-id can be parsed from the
     * URL tail when needed (e.g., `/rest/api/2/project/PROJ/role/10001`).
     */
    suspend fun getProjectRoles(projectKey: String): ApiResult<String> {
        val encoded = URLEncoder.encode(projectKey, "UTF-8")
        log.debug("[Jira:API] GET /rest/api/2/project/$encoded/role")
        return getRawString("/rest/api/2/project/$encoded/role")
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
                // Same-origin guard: reject absolute URLs whose host or scheme differs from the
                // configured Jira base URL to prevent token exfiltration via server-supplied
                // autoCompleteUrl / other absolute-URL fields (audit finding jira:F-1).
                if (path.startsWith("http://") || path.startsWith("https://")) {
                    val targetUri = runCatching { URI(url) }.getOrNull()
                    val baseUri = runCatching { URI(baseUrl) }.getOrNull()
                    if (targetUri == null || baseUri == null ||
                        !targetUri.host.equals(baseUri.host, ignoreCase = true) ||
                        targetUri.scheme != baseUri.scheme
                    ) {
                        log.warn("[Jira:API] getRawString rejected cross-origin URL: $url (base: $baseUrl)")
                        return@withContext ApiResult.Error(
                            ErrorType.FORBIDDEN,
                            "getRawString rejected: target host '${targetUri?.host}' does not match Jira base host"
                        )
                    }
                }
                val request = Request.Builder().url(url).get().build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    log.debug("[Jira:API] GET $path -> ${it.code}")
                    statusCode?.set(0, it.code)
                    when (it.code) {
                        in 200..299 -> {
                            checkJsonContentType(it, path)?.let { err -> return@withContext err }
                            ApiResult.Success(it.body?.string() ?: "")
                        }
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
                            checkJsonContentType(it, path)?.let { err -> return@withContext err }
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
    override suspend fun getDevStatusBranches(issueId: String): ApiResult<List<DevStatusBranch>> {
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
     *
     * Uses POST /rest/api/2/search so the JQL goes in the body and the URL-length cap
     * (which forced an earlier `chunked(100)` GET workaround) no longer applies.
     */
    suspend fun validateTicketKeys(keys: List<String>): ApiResult<Map<String, TicketKeyInfo>> {
        if (keys.isEmpty()) return ApiResult.Success(emptyMap())

        // Validate every key against the anchored Jira key pattern before interpolating
        // into JQL. An unvalidated key like `ABC-1, summary~"x"` would inject extra JQL
        // clauses via the joinToString — fail-fast on the whole batch.
        val jiraKeyPattern = Regex("^[A-Z][A-Z0-9_]+-\\d+$")
        keys.forEach { k ->
            if (!jiraKeyPattern.matches(k)) {
                throw IllegalArgumentException("Invalid Jira key: $k")
            }
        }
        val jql = "key in (${keys.joinToString(",")})"
        val body = buildJsonObject {
            put("jql", jql)
            putJsonArray("fields") {
                add("summary")
                add("status")
            }
            put("maxResults", keys.size)
        }.toString()
        log.debug("[Jira:API] POST /rest/api/2/search (validate ${keys.size} ticket keys)")
        return when (val result = postJson<JiraIssueSearchResult>("/rest/api/2/search", body)) {
            is ApiResult.Success -> {
                val map = result.data.issues.associate { issue ->
                    issue.key to TicketKeyInfo(
                        key = issue.key,
                        summary = issue.fields.summary,
                        status = issue.fields.status.name
                    )
                }
                ApiResult.Success(map)
            }
            is ApiResult.Error -> {
                log.warn("[Jira:API] Ticket key validation failed: ${result.message}")
                result
            }
        }
    }

    /**
     * Fetches ALL worklogs for an issue by paging (`startAt`/`maxResults`). The previous fixed
     * `maxResults=20` silently truncated busy tickets, so any date/author filter applied
     * downstream operated on an arbitrary first page and missed entries.
     */
    suspend fun getWorklogs(issueKey: String, pageSize: Int = 100): ApiResult<JiraWorklogResponse> {
        val encoded = URLEncoder.encode(issueKey, "UTF-8")
        val all = mutableListOf<JiraWorklog>()
        var startAt = 0
        var total = 0
        var pages = 0
        while (pages < MAX_WORKLOG_PAGES) {
            log.debug("[Jira:API] GET /rest/api/2/issue/$encoded/worklog?startAt=$startAt")
            val page = when (val r = get<JiraWorklogResponse>(
                "/rest/api/2/issue/$encoded/worklog?startAt=$startAt&maxResults=$pageSize"
            )) {
                is ApiResult.Error -> return r
                is ApiResult.Success -> r.data
            }
            all += page.worklogs
            total = page.total
            startAt += page.worklogs.size
            pages++
            if (page.worklogs.isEmpty() || startAt >= total) break
        }
        return ApiResult.Success(JiraWorklogResponse(worklogs = all, total = total))
    }

    suspend fun getClosedSprints(boardId: Int, startAt: Int = 0, maxResults: Int = 50): ApiResult<JiraSprintSearchResult> {
        log.debug("[Jira:API] GET /rest/agile/1.0/board/$boardId/sprint?state=closed&startAt=$startAt&maxResults=$maxResults")
        return get<JiraSprintSearchResult>("/rest/agile/1.0/board/$boardId/sprint?state=closed&startAt=$startAt&maxResults=$maxResults")
    }

    override suspend fun getDevStatusPullRequests(issueId: String): ApiResult<List<DevStatusPullRequest>> =
        fetchDevStatus(issueId, "pullrequest") { it.pullRequests }

    override suspend fun getDevStatusCommits(issueId: String): ApiResult<List<DevStatusCommit>> =
        fetchDevStatus(issueId, "repository") { d -> d.repositories.flatMap { it.commits } }

    override suspend fun getDevStatusBuilds(issueId: String): ApiResult<List<DevStatusBuild>> =
        fetchDevStatus(issueId, "build") { it.builds }

    override suspend fun getDevStatusDeployments(issueId: String): ApiResult<List<DevStatusDeployment>> =
        fetchDevStatus(issueId, "deployment") { it.deployments }

    override suspend fun getDevStatusReviews(issueId: String): ApiResult<List<DevStatusReview>> =
        fetchDevStatus(issueId, "review") { it.reviews }

    private suspend inline fun <reified R> fetchDevStatus(
        issueId: String,
        dataType: String,
        crossinline picker: (DevStatusDetail) -> List<R>
    ): ApiResult<List<R>> {
        log.debug("[Jira:API] GET /rest/dev-status/1.0/issue/detail (issueId=$issueId, type=stash, data=$dataType)")
        return try {
            val response = get<DevStatusResponse>(
                "/rest/dev-status/1.0/issue/detail?issueId=$issueId&applicationType=stash&dataType=$dataType"
            )
            response.map { it.detail.flatMap { d -> picker(d) } }
        } catch (e: Exception) {
            log.warn("[Jira:API] Dev-status $dataType API failed: ${e.message}")
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
                            checkJsonContentType(it, path)?.let { err -> return@withContext err }
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

    /**
     * Shared write helper for every Jira POST that doesn't need a typed response body.
     *
     * Used by [postWorklog], [addComment], [addWatcher], and any future write that
     * returns 200/201/204 with no caller-relevant body. Uses [parseJiraErrorMessage]
     * on every 4xx/5xx response so the user sees the actionable Jira error
     * (e.g. `Worklog timeSpent is required` rather than `HTTP 400`). This was
     * previously only wired into [transitionIssue]; the 2026-05-07 audit lifted
     * it here so all writes get the same error UX.
     */
    private suspend fun post(path: String, jsonBody: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val body = jsonBody.toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("$baseUrl$path").post(body).build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    log.debug("[Jira:API] POST $path -> ${it.code}")
                    when (it.code) {
                        in 200..299 -> {
                            // 204 No Content has no body, no Content-Type — skip the guard for empty bodies.
                            val len = it.body?.contentLength() ?: 0
                            if (len > 0) {
                                checkJsonContentType(it, path)?.let { err -> return@withContext err }
                            }
                            ApiResult.Success(Unit)
                        }
                        400 -> {
                            val raw = it.body?.string().orEmpty()
                            val msg = parseJiraErrorMessage(raw) ?: "Bad request (400)"
                            log.warn("[Jira:API] POST $path rejected (400): $msg")
                            ApiResult.Error(ErrorType.VALIDATION_ERROR, msg)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token").also {
                            log.warn("[Jira:API] Authentication failed (401)")
                        }
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions").also {
                            log.warn("[Jira:API] Forbidden (403)")
                        }
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Resource not found at $path").also {
                            log.warn("[Jira:API] Not found (404) at $path")
                        }
                        429 -> ApiResult.Error(ErrorType.RATE_LIMITED, "Jira rate limit exceeded").also {
                            log.warn("[Jira:API] Rate limited (429)")
                        }
                        else -> {
                            val raw = it.body?.string().orEmpty()
                            val msg = parseJiraErrorMessage(raw) ?: "Jira returned ${it.code}"
                            log.warn("[Jira:API] POST $path server error (${it.code}): $msg")
                            ApiResult.Error(ErrorType.SERVER_ERROR, msg)
                        }
                    }
                }
            } catch (e: IOException) {
                log.warn("[Jira:API] Network error: ${e.message}")
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira: ${e.message}", e)
            }
        }

    /**
     * POST helper that returns the raw response body as a String.
     * Used by endpoints (e.g. `/rest/api/1.0/render`) that return non-JSON content
     * (plain HTML) so the JSON content-type guard is deliberately skipped.
     * Auth handling, retry policy, and timeout configuration are inherited from
     * the shared [httpClient] exactly as in [post].
     */
    suspend fun postRawString(path: String, jsonBody: String): ApiResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val body = jsonBody.toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("$baseUrl$path").post(body).build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    log.debug("[Jira:API] POST $path -> ${it.code}")
                    when (it.code) {
                        in 200..299 -> ApiResult.Success(it.body?.string() ?: "")
                        400 -> {
                            val raw = it.body?.string().orEmpty()
                            val msg = parseJiraErrorMessage(raw) ?: "Bad request (400)"
                            log.warn("[Jira:API] POST $path rejected (400): $msg")
                            ApiResult.Error(ErrorType.VALIDATION_ERROR, msg)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token").also { _ ->
                            log.warn("[Jira:API] Authentication failed (401)")
                        }
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions").also { _ ->
                            log.warn("[Jira:API] Forbidden (403)")
                        }
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Resource not found at $path").also { _ ->
                            log.warn("[Jira:API] Not found (404) at $path")
                        }
                        429 -> ApiResult.Error(ErrorType.RATE_LIMITED, "Jira rate limit exceeded").also { _ ->
                            log.warn("[Jira:API] Rate limited (429)")
                        }
                        else -> {
                            val raw = it.body?.string().orEmpty()
                            val msg = parseJiraErrorMessage(raw) ?: "Jira returned ${it.code}"
                            log.warn("[Jira:API] POST $path server error (${it.code}): $msg")
                            ApiResult.Error(ErrorType.SERVER_ERROR, msg)
                        }
                    }
                }
            } catch (e: IOException) {
                log.warn("[Jira:API] Network error at $path: ${e.message}")
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira: ${e.message}", e)
            }
        }

    /**
     * Typed POST helper: serializes a JSON-body request and decodes the response into [T].
     * Mirrors [get] for response-code mapping, content-type guard, and error semantics.
     */
    private suspend inline fun <reified T> postJson(path: String, jsonBody: String): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val body = jsonBody.toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("$baseUrl$path").post(body).build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    log.debug("[Jira:API] POST $path -> ${it.code}")
                    when (it.code) {
                        in 200..299 -> {
                            checkJsonContentType(it, path)?.let { err -> return@withContext err }
                            val bodyStr = it.body?.string() ?: ""
                            ApiResult.Success(json.decodeFromString<T>(bodyStr))
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

    private suspend fun delete(path: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path").delete().build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    log.debug("[Jira:API] DELETE $path -> ${it.code}")
                    when (it.code) {
                        in 200..299 -> ApiResult.Success(Unit)
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Jira token").also {
                            log.warn("[Jira:API] Authentication failed (401) at $path")
                        }
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Jira permissions").also {
                            log.warn("[Jira:API] Forbidden (403) at $path")
                        }
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Resource not found at $path").also {
                            log.warn("[Jira:API] Not found (404) at $path")
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
     * Returns null when the response is OK to parse as JSON; an [ApiResult.Error] otherwise.
     *
     * Two failure modes covered:
     *  - **HTML (auth-expired session redirect):** when auth expires, Jira responds 302 →
     *    `/login.jsp?permissionViolation=true`. With `followRedirects=true` the client receives
     *    HTML on a 200 status, and JSON parsing yields confusing errors. We map this to
     *    [ErrorType.AUTH_FAILED] so the UI surface ("Check your Jira token in Settings") stays
     *    correct regardless of the underlying status code.
     *  - **Other non-JSON content types:** mapped to [ErrorType.PARSE_ERROR] like before.
     *
     * Empty/blank Content-Type is treated as OK because some endpoints return 204 No Content
     * with no Content-Type at all.
     */
    private fun checkJsonContentType(response: Response, path: String): ApiResult.Error? {
        val contentType = response.header("Content-Type").orEmpty()
        if (contentType.isBlank()) return null
        if (contentType.contains("text/html", ignoreCase = true)) {
            log.warn("[Jira:API] Got text/html on $path — likely auth-expired login redirect.")
            return ApiResult.Error(
                ErrorType.AUTH_FAILED,
                "Jira returned HTML — likely not authenticated"
            )
        }
        if (!contentType.contains("application/json", ignoreCase = true) &&
            !contentType.contains("text/json", ignoreCase = true)) {
            return ApiResult.Error(
                ErrorType.PARSE_ERROR,
                "Unexpected response Content-Type: $contentType (expected JSON)"
            )
        }
        return null
    }

    // ── New endpoint methods (R-PROJ extensions) ───────────────────────────────

    /**
     * `GET /rest/api/2/mypermissions[?projectKey=…]`
     *
     * Returns global permission flags when [projectKey] is null, project-scoped when set.
     * Per-key shape is `{id, key, name, type, havePermission, deprecatedKey}`.
     */
    suspend fun getMyPermissions(projectKey: String? = null): ApiResult<JiraPermissions> {
        val path = if (projectKey != null) {
            "/rest/api/2/mypermissions?projectKey=${URLEncoder.encode(projectKey, "UTF-8")}"
        } else {
            "/rest/api/2/mypermissions"
        }
        log.debug("[Jira:API] GET $path")
        return get(path)
    }

    /**
     * `GET /rest/api/2/field` — flat array of every field (system + custom).
     * The `custom` boolean tells the consumer whether the field id is a `customfield_*`.
     */
    suspend fun getFields(): ApiResult<List<JiraField>> {
        log.debug("[Jira:API] GET /rest/api/2/field")
        return get("/rest/api/2/field")
    }

    /**
     * `GET /rest/api/2/issue/{key}/remotelink` — Confluence pages, web links, etc.
     */
    suspend fun getRemoteLinks(key: String): ApiResult<List<JiraRemoteLink>> {
        log.debug("[Jira:API] GET /rest/api/2/issue/$key/remotelink")
        return get("/rest/api/2/issue/$key/remotelink")
    }

    /**
     * `GET /rest/api/2/issue/{key}/watchers`
     */
    suspend fun getWatchers(key: String): ApiResult<JiraWatchers> {
        log.debug("[Jira:API] GET /rest/api/2/issue/$key/watchers")
        return get("/rest/api/2/issue/$key/watchers")
    }

    /**
     * `POST /rest/api/2/issue/{key}/watchers` with a JSON-string body (the username,
     * literally a JSON string like `"jdoe"`). That's the documented Jira API shape.
     */
    suspend fun addWatcher(key: String, username: String): ApiResult<Unit> {
        log.debug("[Jira:API] POST /rest/api/2/issue/$key/watchers (add $username)")
        val body = JsonPrimitive(username).toString()
        return post("/rest/api/2/issue/$key/watchers", body)
    }

    /**
     * `DELETE /rest/api/2/issue/{key}/watchers?username=…`
     */
    suspend fun removeWatcher(key: String, username: String): ApiResult<Unit> {
        val encoded = URLEncoder.encode(username, "UTF-8")
        log.debug("[Jira:API] DELETE /rest/api/2/issue/$key/watchers (remove $username)")
        return delete("/rest/api/2/issue/$key/watchers?username=$encoded")
    }

    /**
     * `GET /rest/api/2/myself?expand=groups,applicationRoles`
     */
    suspend fun getMyselfExpanded(): ApiResult<JiraMyself> {
        log.debug("[Jira:API] GET /rest/api/2/myself?expand=groups,applicationRoles")
        return get("/rest/api/2/myself?expand=groups,applicationRoles")
    }

    /**
     * `GET /rest/api/2/issue/picker?query=…&showSubTasks=true&showSubTaskParent=true`
     *
     * Used by the # ticket-mention search — flat list of every issue across every section.
     * Sections vary by query type (key-prefix → only `History Search` returns; free-text →
     * `History Search` + `Current Search`), so we don't index on section id.
     */
    suspend fun getIssueSuggestions(query: String): ApiResult<JiraIssuePickerResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        log.debug("[Jira:API] GET /rest/api/2/issue/picker?query=$query")
        return get("/rest/api/2/issue/picker?query=$encoded&showSubTasks=true&showSubTaskParent=true")
    }

    /**
     * `GET /rest/api/2/filter/favourite` — saved JQL filters the user marked as favourite.
     */
    suspend fun getFavouriteFilters(): ApiResult<List<JiraFilter>> {
        log.debug("[Jira:API] GET /rest/api/2/filter/favourite")
        return get("/rest/api/2/filter/favourite")
    }

    /**
     * `GET /rest/api/2/filter/{id}` — single saved filter (always carries `jql`).
     */
    suspend fun getFilter(id: Long): ApiResult<JiraFilter> {
        log.debug("[Jira:API] GET /rest/api/2/filter/$id")
        return get("/rest/api/2/filter/$id")
    }

    /**
     * Like [getIssueWithContext] but additionally requests `expand=…,changelog`,
     * so the detail panel gets the full activity log in one round-trip.
     */
    suspend fun getIssueWithContextAndChangelog(key: String): ApiResult<JiraIssueWithChangelog> {
        val fields = "summary,description,status,priority,issuetype,assignee,reporter," +
                "labels,components,fixVersions,comment"
        log.debug("[Jira:API] GET /rest/api/2/issue/$key (rich + changelog)")
        return get("/rest/api/2/issue/$key?fields=$fields&expand=renderedFields,changelog")
    }
}

/** Max length of free-text search input accepted by [JiraApiClient.searchIssues] (bounds GET URL size). */
private const val MAX_SEARCH_TEXT_LENGTH = 500

/** Safety cap on worklog pagination — at pageSize=100 this covers 50k worklogs per issue. */
private const val MAX_WORKLOG_PAGES = 500

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
