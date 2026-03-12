package com.workflow.orchestrator.handover.api

import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.http.RetryInterceptor
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.handover.api.dto.BitbucketPrListResponse
import com.workflow.orchestrator.handover.api.dto.BitbucketPrRequest
import com.workflow.orchestrator.handover.api.dto.BitbucketPrResponse
import com.workflow.orchestrator.handover.api.dto.BitbucketRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class BitbucketApiClient(
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

    suspend fun testConnection(): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/users")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> ApiResult.Success(Unit)
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bitbucket permissions")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    suspend fun createPullRequest(
        projectKey: String,
        repoSlug: String,
        title: String,
        description: String,
        fromBranch: String,
        toBranch: String
    ): ApiResult<BitbucketPrResponse> =
        withContext(Dispatchers.IO) {
            try {
                val payload = BitbucketPrRequest(
                    title = title,
                    description = description,
                    fromRef = BitbucketRef("refs/heads/$fromBranch"),
                    toRef = BitbucketRef("refs/heads/$toBranch")
                )
                val body = json.encodeToString(payload)
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests")
                    .post(body)
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val bodyStr = it.body?.string() ?: ""
                            ApiResult.Success(json.decodeFromString<BitbucketPrResponse>(bodyStr))
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bitbucket permissions")
                        409 -> ApiResult.Error(ErrorType.VALIDATION_ERROR, "PR already exists for this branch")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    suspend fun getPullRequestsForBranch(
        projectKey: String,
        repoSlug: String,
        branchName: String
    ): ApiResult<List<BitbucketPrResponse>> =
        withContext(Dispatchers.IO) {
            try {
                val branchRef = "refs/heads/$branchName"
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests?direction=OUTGOING&at=$branchRef&state=OPEN")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val bodyStr = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<BitbucketPrListResponse>(bodyStr)
                            ApiResult.Success(parsed.values)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bitbucket permissions")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Repository not found")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }
}
