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
import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.util.concurrent.TimeUnit

class BitbucketApiClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?
) {
    private val log = Logger.getInstance(BitbucketApiClient::class.java)
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
            log.info("[Handover:PR] Creating PR in $projectKey/$repoSlug: $fromBranch -> $toBranch")
            log.debug("[Handover:PR] PR title: $title, description length: ${description.length} chars")
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
                            log.info("[Handover:PR] PR created successfully in $projectKey/$repoSlug")
                            ApiResult.Success(json.decodeFromString<BitbucketPrResponse>(bodyStr))
                        }
                        401 -> { log.error("[Handover:PR] Auth failed creating PR in $projectKey/$repoSlug"); ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token") }
                        403 -> { log.error("[Handover:PR] Forbidden creating PR in $projectKey/$repoSlug"); ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bitbucket permissions") }
                        409 -> { log.warn("[Handover:PR] PR already exists for branch $fromBranch in $projectKey/$repoSlug"); ApiResult.Error(ErrorType.VALIDATION_ERROR, "PR already exists for this branch") }
                        else -> { log.error("[Handover:PR] Unexpected response ${it.code} creating PR in $projectKey/$repoSlug"); ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}") }
                    }
                }
            } catch (e: IOException) {
                log.error("[Handover:PR] Network error creating PR in $projectKey/$repoSlug", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    suspend fun getPullRequestsForBranch(
        projectKey: String,
        repoSlug: String,
        branchName: String
    ): ApiResult<List<BitbucketPrResponse>> =
        withContext(Dispatchers.IO) {
            log.info("[Handover:PR] Fetching PRs for branch $branchName in $projectKey/$repoSlug")
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
                            log.info("[Handover:PR] Found ${parsed.values.size} PRs for branch $branchName")
                            ApiResult.Success(parsed.values)
                        }
                        401 -> { log.error("[Handover:PR] Auth failed fetching PRs for branch $branchName"); ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token") }
                        403 -> { log.error("[Handover:PR] Forbidden fetching PRs for branch $branchName"); ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bitbucket permissions") }
                        404 -> { log.error("[Handover:PR] Repository not found: $projectKey/$repoSlug"); ApiResult.Error(ErrorType.NOT_FOUND, "Repository not found") }
                        else -> { log.error("[Handover:PR] Unexpected response ${it.code} fetching PRs for branch $branchName"); ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}") }
                    }
                }
            } catch (e: IOException) {
                log.error("[Handover:PR] Network error fetching PRs for branch $branchName", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }
}
