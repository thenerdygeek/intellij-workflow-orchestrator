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
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class BambooApiClient(
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

    suspend fun getPlans(): ApiResult<List<BambooPlanDto>> =
        get<BambooPlanListResponse>("/rest/api/latest/plan?expand=plans.plan&max-results=100")
            .map { it.plans.plan }

    suspend fun searchPlans(query: String): ApiResult<List<BambooSearchEntity>> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return get<BambooSearchResponse>("/rest/api/latest/search/plans?searchTerm=$encoded&fuzzy=true&max-results=25")
            .map { it.searchResults.map { r -> r.searchEntity } }
    }

    suspend fun getPlanSpecs(planKey: String): ApiResult<String> =
        getRaw("/rest/api/latest/plan/$planKey/specs?format=YAML")

    suspend fun getBranches(planKey: String): ApiResult<List<BambooBranchDto>> =
        get<BambooBranchListResponse>("/rest/api/latest/plan/$planKey/branch?max-results=100")
            .map { it.branches.branch }

    suspend fun getLatestResult(planKey: String, branch: String): ApiResult<BambooResultDto> {
        val encodedBranch = URLEncoder.encode(branch, "UTF-8")
        return get("/rest/api/latest/result/$planKey/branch/$encodedBranch/latest?expand=stages.stage")
    }

    suspend fun getBuildLog(resultKey: String): ApiResult<String> =
        getRaw("/rest/api/latest/result/$resultKey?expand=logEntries&max-results=2000")

    suspend fun getVariables(planKey: String): ApiResult<List<BambooPlanVariableDto>> =
        get<BambooVariableListResponse>("/rest/api/latest/plan/$planKey/variable")
            .map { it.variables.variable }

    suspend fun triggerBuild(
        planKey: String,
        variables: Map<String, String> = emptyMap(),
        stageName: String? = null
    ): ApiResult<BambooQueueResponse> {
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

    suspend fun cancelBuild(resultKey: String): ApiResult<Unit> {
        return delete("/rest/api/latest/queue/$resultKey")
    }

    private suspend inline fun <reified T> get(path: String): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path").get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val bodyStr = it.body?.string() ?: ""
                            ApiResult.Success(json.decodeFromString<T>(bodyStr))
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bamboo token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bamboo permissions")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Bamboo resource not found")
                        429 -> ApiResult.Error(ErrorType.RATE_LIMITED, "Bamboo rate limit exceeded")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bamboo returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bamboo: ${e.message}", e)
            }
        }

    private suspend fun getRaw(path: String): ApiResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path").get().build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> ApiResult.Success(it.body?.string() ?: "")
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

    private suspend inline fun <reified T> post(path: String, jsonBody: String): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val body = jsonBody.toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("$baseUrl$path").post(body)
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val bodyStr = it.body?.string() ?: ""
                            ApiResult.Success(json.decodeFromString<T>(bodyStr))
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bamboo token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bamboo permissions")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bamboo returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bamboo: ${e.message}", e)
            }
        }

    suspend fun getBuildResult(resultKey: String): ApiResult<BambooResultDto> {
        return get("/rest/api/latest/result/$resultKey?expand=stages.stage")
    }

    private suspend fun delete(path: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path").delete()
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
}
