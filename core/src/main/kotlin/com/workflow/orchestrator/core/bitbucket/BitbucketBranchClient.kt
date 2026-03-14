package com.workflow.orchestrator.core.bitbucket

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.http.RetryInterceptor
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class BitbucketProject(
    val key: String,
    val name: String,
    val description: String? = null
)

@Serializable
data class BitbucketRepo(
    val slug: String,
    val name: String,
    val project: BitbucketProject? = null
)

@Serializable
data class BitbucketBranch(
    val id: String,
    val displayId: String,
    val isDefault: Boolean = false
)

@Serializable
private data class ProjectListResponse(
    val values: List<BitbucketProject>,
    val isLastPage: Boolean = true
)

@Serializable
private data class RepoListResponse(
    val values: List<BitbucketRepo>,
    val isLastPage: Boolean = true
)

@Serializable
private data class BranchListResponse(
    val values: List<BitbucketBranch>,
    val isLastPage: Boolean = true
)

@Serializable
private data class CreateBranchRequest(
    val name: String,
    val startPoint: String
)

/**
 * Lightweight Bitbucket Server REST client for branch operations only.
 * Lives in :core so both :jira (Start Work) and :handover (PR creation)
 * can access it without cross-module dependencies.
 */
class BitbucketBranchClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?
) {
    private val log = Logger.getInstance(BitbucketBranchClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenProvider, AuthScheme.BEARER))
            .addInterceptor(RetryInterceptor())
            .build()
    }

    /**
     * Lists projects in Bitbucket Server.
     * GET /rest/api/1.0/projects
     */
    suspend fun getProjects(): ApiResult<List<BitbucketProject>> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching projects")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects?limit=100")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<ProjectListResponse>(body)
                            log.info("[Core:Bitbucket] Found ${parsed.values.size} projects")
                            ApiResult.Success(parsed.values)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching projects", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Lists repositories in a Bitbucket Server project.
     * GET /rest/api/1.0/projects/{projectKey}/repos
     */
    suspend fun getRepos(projectKey: String): ApiResult<List<BitbucketRepo>> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching repos for project $projectKey")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos?limit=100")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<RepoListResponse>(body)
                            log.info("[Core:Bitbucket] Found ${parsed.values.size} repos in $projectKey")
                            ApiResult.Success(parsed.values)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Project $projectKey not found")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching repos", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Lists branches in a Bitbucket Server repository.
     * GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/branches
     */
    suspend fun getBranches(
        projectKey: String,
        repoSlug: String,
        filterText: String = ""
    ): ApiResult<List<BitbucketBranch>> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching branches for $projectKey/$repoSlug")
            try {
                val filterParam = if (filterText.isNotBlank()) "&filterText=$filterText" else ""
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/branches?limit=100&orderBy=MODIFICATION$filterParam")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<BranchListResponse>(body)
                            log.info("[Core:Bitbucket] Found ${parsed.values.size} branches")
                            ApiResult.Success(parsed.values)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Repository $projectKey/$repoSlug not found")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching branches", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Creates a branch in a Bitbucket Server repository.
     * POST /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/branches
     */
    suspend fun createBranch(
        projectKey: String,
        repoSlug: String,
        branchName: String,
        startPoint: String
    ): ApiResult<BitbucketBranch> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Creating branch '$branchName' from '$startPoint' in $projectKey/$repoSlug")
            try {
                val payload = json.encodeToString(CreateBranchRequest(branchName, startPoint))
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/branches")
                    .post(payload)
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val branch = json.decodeFromString<BitbucketBranch>(body)
                            log.info("[Core:Bitbucket] Branch '${branch.displayId}' created successfully")
                            ApiResult.Success(branch)
                        }
                        401 -> ApiResult.Error(
                            ErrorType.AUTH_FAILED,
                            "Bitbucket token lacks write permission. " +
                            "Ensure your HTTP access token has Repository Write (or Admin) permission."
                        )
                        403 -> ApiResult.Error(
                            ErrorType.AUTH_FAILED,
                            "Bitbucket token lacks permission to create branches in $projectKey/$repoSlug. " +
                            "Check Repository Write permission."
                        )
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Repository $projectKey/$repoSlug not found")
                        409 -> ApiResult.Error(ErrorType.VALIDATION_ERROR, "Branch '$branchName' already exists")
                        else -> {
                            val errorBody = it.body?.string() ?: ""
                            ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}: $errorBody")
                        }
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error creating branch", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }
}
