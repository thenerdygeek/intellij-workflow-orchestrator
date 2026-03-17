package com.workflow.orchestrator.bamboo.api

import com.workflow.orchestrator.bamboo.api.dto.*
import com.workflow.orchestrator.bamboo.api.dto.BambooBuildStatusResponse
import com.workflow.orchestrator.bamboo.api.dto.BambooBuildVariablesResponse
import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.http.RetryInterceptor
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class BambooApiClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val connectTimeoutSeconds: Long = 10,
    private val readTimeoutSeconds: Long = 30
) {
    private val log = Logger.getInstance(BambooApiClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenProvider, AuthScheme.BEARER))
            .addInterceptor(RetryInterceptor())
            .build()
    }

    suspend fun getPlans(): ApiResult<List<BambooPlanDto>> {
        log.info("[Bamboo:API] Fetching all plans")
        return get<BambooPlanListResponse>("/rest/api/latest/plan?expand=plans.plan&max-results=100")
            .map { it.plans.plan }
    }

    suspend fun getProjects(): ApiResult<List<BambooProjectDto>> {
        log.info("[Bamboo:API] Fetching all projects")
        return get<BambooProjectListResponse>("/rest/api/latest/project?max-results=100")
            .map { it.projects.project }
    }

    suspend fun getProjectPlans(projectKey: String): ApiResult<List<BambooPlanDto>> {
        log.info("[Bamboo:API] Fetching plans for project $projectKey")
        return get<BambooProjectDetailResponse>("/rest/api/latest/project/$projectKey?expand=plans.plan")
            .map { it.plans.plan }
    }

    suspend fun searchPlans(query: String): ApiResult<List<BambooSearchEntity>> {
        log.info("[Bamboo:API] Searching plans with query='$query'")
        val encoded = URLEncoder.encode(query, "UTF-8")
        return get<BambooSearchResponse>("/rest/api/latest/search/plans?searchTerm=$encoded&fuzzy=true&max-results=25")
            .map { it.searchResults.map { r -> r.searchEntity } }
    }

    suspend fun getPlanSpecs(planKey: String): ApiResult<String> {
        log.debug("[Bamboo:API] Fetching plan specs for planKey=$planKey")
        return getRaw("/rest/api/latest/plan/$planKey/specs?format=YAML")
    }

    suspend fun getBranches(planKey: String): ApiResult<List<BambooBranchDto>> =
        get<BambooBranchListResponse>("/rest/api/latest/plan/$planKey/branch?max-results=100")
            .map { it.branches.branch }

    suspend fun getLatestResult(planKey: String, branch: String? = null): ApiResult<BambooResultDto> {
        // If planKey already includes the branch number (e.g., PROJ-PLAN123), use it directly
        // Otherwise, use the branch path format
        val path = if (branch != null && !planKey.last().isDigit()) {
            val encodedBranch = URLEncoder.encode(branch, "UTF-8")
            "/rest/api/latest/result/$planKey/branch/$encodedBranch/latest?expand=stages.stage.results.result"
        } else {
            "/rest/api/latest/result/$planKey/latest?expand=stages.stage.results.result"
        }
        log.info("[Bamboo:API] Fetching latest result: $path")
        return get(path)
    }

    suspend fun getBuildLog(resultKey: String): ApiResult<String> {
        log.info("[Bamboo:API] Fetching build log for resultKey=$resultKey")
        // Use the download endpoint for plain text logs (not the REST API logEntries which returns XML)
        return getRaw("/download/$resultKey/build_logs/$resultKey.log")
    }

    /**
     * Fetches test results for a job, including failed test details.
     * GET /rest/api/latest/result/{jobResultKey}?expand=testResults.failedTests.testResult,testResults.successfulTests.testResult
     */
    suspend fun getTestResults(jobResultKey: String): ApiResult<BambooJobTestResultDto> {
        log.info("[Bamboo:API] Fetching test results for jobResultKey=$jobResultKey")
        return get("/rest/api/latest/result/$jobResultKey?expand=testResults.failedTests.testResult,testResults.successfulTests.testResult")
    }

    suspend fun getVariables(planKey: String): ApiResult<List<BambooPlanVariableDto>> =
        get<BambooVariableListResponse>("/rest/api/latest/plan/$planKey/variable")
            .map { it.variables.variable }

    suspend fun triggerBuild(
        planKey: String,
        variables: Map<String, String> = emptyMap(),
        stageName: String? = null
    ): ApiResult<BambooQueueResponse> {
        log.info("[Bamboo:API] Triggering build for planKey=$planKey, stage=$stageName, variables=${variables.keys}")
        val params = buildString {
            if (stageName != null) {
                append("?stage=${URLEncoder.encode(stageName, "UTF-8")}&executeAllStages=false")
            }
        }
        val bodyJson = if (variables.isNotEmpty()) {
            val varArray = JsonArray(variables.entries.map { (k, v) ->
                JsonObject(mapOf("name" to JsonPrimitive(k), "value" to JsonPrimitive(v)))
            })
            JsonObject(mapOf("variables" to varArray)).toString()
        } else {
            "{}"
        }
        return post("/rest/api/latest/queue/$planKey$params", bodyJson)
    }

    suspend fun getRunningAndQueuedBuilds(planKey: String): ApiResult<List<BambooResultDto>> {
        return get<BambooBuildStatusResponse>(
            "/rest/api/latest/result/$planKey?includeAllStates=true&max-results=5"
        ).map { response ->
            response.results.result.filter { dto ->
                dto.lifeCycleState in listOf("InProgress", "Queued", "Pending")
            }
        }
    }

    suspend fun getBuildVariables(resultKey: String): ApiResult<Map<String, String>> {
        return get<BambooBuildVariablesResponse>(
            "/rest/api/latest/result/$resultKey?expand=variables"
        ).map { response ->
            response.variables.variable.associate { it.name to it.value }
        }
    }

    suspend fun getRecentResults(
        planKey: String,
        maxResults: Int = 10
    ): ApiResult<List<BambooResultDto>> {
        return get<BambooBuildStatusResponse>(
            "/rest/api/latest/result/$planKey?max-results=$maxResults&expand=stages.stage,variables"
        ).map { it.results.result }
    }

    /** Rerun failed/incomplete jobs for a build via PUT to queue endpoint. */
    suspend fun rerunFailedJobs(planKey: String, buildNumber: Int): ApiResult<Unit> {
        log.info("[Bamboo:API] Rerunning failed jobs for $planKey-$buildNumber")
        return put("/rest/api/latest/queue/$planKey-$buildNumber")
    }

    /** Cancel a queued (not yet running) build. */
    suspend fun cancelBuild(resultKey: String): ApiResult<Unit> {
        log.info("[Bamboo:API] Cancelling queued build resultKey=$resultKey")
        return delete("/rest/api/latest/queue/$resultKey")
    }

    /** Stop a running build. */
    suspend fun stopBuild(resultKey: String): ApiResult<Unit> {
        log.info("[Bamboo:API] Stopping running build resultKey=$resultKey")
        return put("/rest/api/latest/result/$resultKey/stop")
    }

    /** Cancel or stop a build based on its state. */
    suspend fun cancelOrStopBuild(resultKey: String, isRunning: Boolean): ApiResult<Unit> {
        return if (isRunning) stopBuild(resultKey) else cancelBuild(resultKey)
    }

    private suspend inline fun <reified T> get(path: String): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path").get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    log.info("[Bamboo:API] GET $path -> ${it.code}")
                    when (it.code) {
                        in 200..299 -> {
                            val bodyStr = it.body?.string() ?: ""
                            try {
                                ApiResult.Success(json.decodeFromString<T>(bodyStr))
                            } catch (e: Exception) {
                                log.warn("[Bamboo:API] Parse failed for GET $path: ${e.message}")
                                log.info("[Bamboo:API] Response body (first 500): ${bodyStr.take(500)}")
                                ApiResult.Error(ErrorType.PARSE_ERROR, "Failed to parse response: ${e.message}")
                            }
                        }
                        401 -> { log.error("[Bamboo:API] Authentication failed for GET $path"); ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bamboo token") }
                        403 -> { log.error("[Bamboo:API] Forbidden for GET $path"); ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bamboo permissions") }
                        404 -> { log.warn("[Bamboo:API] Not found for GET $path (plan key or branch may be incorrect)"); ApiResult.Error(ErrorType.NOT_FOUND, "Bamboo resource not found") }
                        429 -> { log.error("[Bamboo:API] Rate limited for GET $path"); ApiResult.Error(ErrorType.RATE_LIMITED, "Bamboo rate limit exceeded") }
                        else -> { log.error("[Bamboo:API] Server error ${it.code} for GET $path"); ApiResult.Error(ErrorType.SERVER_ERROR, "Bamboo returned ${it.code}") }
                    }
                }
            } catch (e: IOException) {
                log.error("[Bamboo:API] Network error for GET $path: ${e.message}", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bamboo: ${e.message}", e)
            }
        }

    private suspend fun getRaw(path: String): ApiResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path").get().build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    log.debug("[Bamboo:API] GET (raw) $path responded with status=${it.code}")
                    when (it.code) {
                        in 200..299 -> ApiResult.Success(it.body?.string() ?: "")
                        401 -> { log.error("[Bamboo:API] Authentication failed for GET $path"); ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bamboo token") }
                        403 -> { log.error("[Bamboo:API] Forbidden for GET $path"); ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bamboo permissions") }
                        404 -> { log.warn("[Bamboo:API] Not found for GET $path (plan key or branch may be incorrect)"); ApiResult.Error(ErrorType.NOT_FOUND, "Bamboo resource not found") }
                        else -> { log.error("[Bamboo:API] Server error ${it.code} for GET $path"); ApiResult.Error(ErrorType.SERVER_ERROR, "Bamboo returned ${it.code}") }
                    }
                }
            } catch (e: IOException) {
                log.error("[Bamboo:API] Network error for GET (raw) $path: ${e.message}", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bamboo: ${e.message}", e)
            }
        }

    private suspend inline fun <reified T> post(path: String, jsonBody: String): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val body = jsonBody.toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("$baseUrl$path").post(body)
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    log.debug("[Bamboo:API] POST $path responded with status=${it.code}")
                    when (it.code) {
                        in 200..299 -> {
                            val bodyStr = it.body?.string() ?: ""
                            ApiResult.Success(json.decodeFromString<T>(bodyStr))
                        }
                        401 -> { log.error("[Bamboo:API] Authentication failed for POST $path"); ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bamboo token") }
                        403 -> { log.error("[Bamboo:API] Forbidden for POST $path"); ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bamboo permissions") }
                        else -> { log.error("[Bamboo:API] Server error ${it.code} for POST $path"); ApiResult.Error(ErrorType.SERVER_ERROR, "Bamboo returned ${it.code}") }
                    }
                }
            } catch (e: IOException) {
                log.error("[Bamboo:API] Network error for POST $path: ${e.message}", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bamboo: ${e.message}", e)
            }
        }

    suspend fun getBuildResult(resultKey: String): ApiResult<BambooResultDto> {
        return get("/rest/api/latest/result/$resultKey?expand=stages.stage")
    }

    private suspend fun put(path: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path")
                    .put("".toRequestBody("application/json".toMediaType()))
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> ApiResult.Success(Unit)
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bamboo token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bamboo permissions")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Bamboo resource not found")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bamboo returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bamboo: ${e.message}", e)
            }
        }

    private suspend fun delete(path: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path").delete()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    log.debug("[Bamboo:API] DELETE $path responded with status=${it.code}")
                    when (it.code) {
                        in 200..299 -> ApiResult.Success(Unit)
                        401 -> { log.error("[Bamboo:API] Authentication failed for DELETE $path"); ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bamboo token") }
                        403 -> { log.error("[Bamboo:API] Forbidden for DELETE $path"); ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bamboo permissions") }
                        404 -> { log.error("[Bamboo:API] Not found for DELETE $path"); ApiResult.Error(ErrorType.NOT_FOUND, "Bamboo resource not found") }
                        else -> { log.error("[Bamboo:API] Server error ${it.code} for DELETE $path"); ApiResult.Error(ErrorType.SERVER_ERROR, "Bamboo returned ${it.code}") }
                    }
                }
            } catch (e: IOException) {
                log.error("[Bamboo:API] Network error for DELETE $path: ${e.message}", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bamboo: ${e.message}", e)
            }
        }
}
