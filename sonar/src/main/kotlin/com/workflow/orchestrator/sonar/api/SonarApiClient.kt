package com.workflow.orchestrator.sonar.api

import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.http.RetryInterceptor
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.sonar.api.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SonarApiClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val connectTimeoutSeconds: Long = 10,
    private val readTimeoutSeconds: Long = 30
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenProvider, AuthScheme.BEARER))
            .addInterceptor(RetryInterceptor())
            .build()
    }

    suspend fun validateConnection(): ApiResult<Boolean> =
        get<SonarValidationDto>("/api/authentication/validate").map { it.valid }

    suspend fun searchProjects(query: String): ApiResult<List<SonarProjectDto>> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return get<SonarProjectSearchResult>("/api/projects/search?q=$encoded&ps=100")
            .map { it.components }
    }

    suspend fun getQualityGateStatus(projectKey: String, branch: String? = null): ApiResult<SonarQualityGateDto> {
        val branchParam = branch?.let { "&branch=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        return get<SonarQualityGateResponse>(
            "/api/qualitygates/project_status?projectKey=${URLEncoder.encode(projectKey, "UTF-8")}$branchParam"
        ).map { it.projectStatus }
    }

    suspend fun getIssues(
        projectKey: String,
        branch: String? = null,
        filePath: String? = null
    ): ApiResult<List<SonarIssueDto>> {
        val params = buildString {
            append("/api/issues/search?componentKeys=")
            append(URLEncoder.encode(projectKey, "UTF-8"))
            append("&resolved=false&ps=500")
            branch?.let { append("&branch=${URLEncoder.encode(it, "UTF-8")}") }
            filePath?.let { append("&components=${URLEncoder.encode(filePath, "UTF-8")}") }
        }
        return get<SonarIssueSearchResult>(params).map { it.issues }
    }

    suspend fun getMeasures(
        projectKey: String,
        branch: String? = null,
        metricKeys: String = "coverage,line_coverage,branch_coverage,uncovered_lines,uncovered_conditions"
    ): ApiResult<List<SonarMeasureComponentDto>> {
        val metrics = metricKeys.ifBlank { "coverage,line_coverage,branch_coverage,uncovered_lines,uncovered_conditions" }
        val branchParam = branch?.let { "&branch=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        return get<SonarMeasureSearchResult>(
            "/api/measures/component_tree?component=${URLEncoder.encode(projectKey, "UTF-8")}" +
                "&metricKeys=$metrics&qualifiers=FIL&ps=500$branchParam"
        ).map { it.components }
    }

    suspend fun getSourceLines(
        componentKey: String,
        from: Int? = null,
        to: Int? = null
    ): ApiResult<List<SonarSourceLineDto>> {
        val params = buildString {
            append("/api/sources/lines?key=")
            append(URLEncoder.encode(componentKey, "UTF-8"))
            from?.let { append("&from=$it") }
            to?.let { append("&to=$it") }
        }
        return get<List<SonarSourceLineDto>>(params)
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
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid SonarQube token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient SonarQube permissions")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "SonarQube resource not found")
                        429 -> ApiResult.Error(ErrorType.RATE_LIMITED, "SonarQube rate limit exceeded")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "SonarQube returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach SonarQube: ${e.message}", e)
            }
        }
}
