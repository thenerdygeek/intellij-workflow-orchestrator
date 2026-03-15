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
data class BitbucketUser(
    val name: String,
    val displayName: String = "",
    val emailAddress: String? = null
)

@Serializable
private data class UserListResponse(
    val values: List<BitbucketUser>,
    val isLastPage: Boolean = true
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

// --- Pull Request DTOs ---

@Serializable
data class BitbucketPrResponse(
    val id: Int,
    val title: String,
    val state: String,
    val links: BitbucketLinks,
    val fromRef: BitbucketPrRef? = null,
    val toRef: BitbucketPrRef? = null
)

@Serializable
data class BitbucketPrRef(
    val id: String = "",
    val displayId: String = "",
    val latestCommit: String = ""
)

// --- Build Status DTOs ---

@Serializable
data class BitbucketBuildStatus(
    val state: String,
    val key: String,
    val name: String? = null,
    val url: String = "",
    val description: String? = null,
    val dateAdded: Long? = null
)

@Serializable
private data class BuildStatusListResponse(
    val values: List<BitbucketBuildStatus> = emptyList(),
    val size: Int = 0
)

@Serializable
data class BitbucketLinks(
    val self: List<BitbucketLink>
)

@Serializable
data class BitbucketLink(
    val href: String
)

@Serializable
private data class BitbucketPrListResponse(
    val size: Int,
    val values: List<BitbucketPrResponse>
)

@Serializable
data class BitbucketPrRequest(
    val title: String,
    val description: String,
    val fromRef: BitbucketRef,
    val toRef: BitbucketRef,
    val reviewers: List<BitbucketReviewer>? = null
)

@Serializable
data class BitbucketReviewer(val user: BitbucketReviewerUser)

@Serializable
data class BitbucketReviewerUser(val name: String)

@Serializable
data class BitbucketRef(
    val id: String
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

    /**
     * Creates a pull request in a Bitbucket Server repository.
     * POST /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests
     */
    suspend fun createPullRequest(
        projectKey: String,
        repoSlug: String,
        title: String,
        description: String,
        fromBranch: String,
        toBranch: String,
        reviewers: List<BitbucketReviewer>? = null
    ): ApiResult<BitbucketPrResponse> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Creating PR in $projectKey/$repoSlug: $fromBranch -> $toBranch")
            try {
                val payload = json.encodeToString(
                    BitbucketPrRequest(
                        title = title,
                        description = description,
                        fromRef = BitbucketRef("refs/heads/$fromBranch"),
                        toRef = BitbucketRef("refs/heads/$toBranch"),
                        reviewers = reviewers
                    )
                ).toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests")
                    .post(payload)
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val pr = json.decodeFromString<BitbucketPrResponse>(body)
                            log.info("[Core:Bitbucket] PR #${pr.id} created: ${pr.links.self.firstOrNull()?.href}")
                            ApiResult.Success(pr)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Bitbucket token lacks permission to create PRs")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient permissions to create PR in $projectKey/$repoSlug")
                        409 -> ApiResult.Error(ErrorType.VALIDATION_ERROR, "PR already exists for branch $fromBranch")
                        else -> {
                            val errorBody = it.body?.string() ?: ""
                            ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}: $errorBody")
                        }
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error creating PR", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Gets open pull requests for a branch in a Bitbucket Server repository.
     * GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests?direction=OUTGOING&at=refs/heads/{branch}&state=OPEN
     */
    suspend fun getPullRequestsForBranch(
        projectKey: String,
        repoSlug: String,
        branchName: String
    ): ApiResult<List<BitbucketPrResponse>> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching PRs for branch $branchName in $projectKey/$repoSlug")
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
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<BitbucketPrListResponse>(body)
                            log.info("[Core:Bitbucket] Found ${parsed.values.size} PRs for branch $branchName")
                            ApiResult.Success(parsed.values)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Repository $projectKey/$repoSlug not found")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching PRs", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Searches Bitbucket Server users by filter text.
     * GET /rest/api/1.0/users?filter={filter}
     * Used for reviewer autocomplete in PR creation dialog.
     */
    suspend fun getUsers(filter: String): ApiResult<List<BitbucketUser>> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Searching users: filter='$filter'")
            try {
                val encodedFilter = java.net.URLEncoder.encode(filter, "UTF-8")
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/users?filter=$encodedFilter&limit=10")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<UserListResponse>(body)
                            ApiResult.Success(parsed.values)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error searching users", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Gets build statuses for a commit from Bitbucket Server.
     * GET /rest/build-status/1.0/commits/{commitId}
     * Returns Bamboo build results linked to this commit.
     */
    suspend fun getBuildStatuses(commitId: String): ApiResult<List<BitbucketBuildStatus>> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching build statuses for commit ${commitId.take(8)}")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/build-status/1.0/commits/$commitId")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<BuildStatusListResponse>(body)
                            log.info("[Core:Bitbucket] Found ${parsed.values.size} build statuses for commit ${commitId.take(8)}")
                            ApiResult.Success(parsed.values)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Success(emptyList()) // No builds for this commit
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching build statuses", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    companion object {
        /**
         * Extract Bamboo plan key from a build status key.
         * e.g., "PROJ-BUILD-42" → "PROJ-BUILD"
         */
        fun extractPlanKey(buildKey: String): String {
            // Build key format: PLAN-KEY-BUILD_NUMBER (last segment after dash is the number)
            val lastDash = buildKey.lastIndexOf('-')
            if (lastDash <= 0) return buildKey
            val candidate = buildKey.substring(0, lastDash)
            // Verify the suffix was numeric
            val suffix = buildKey.substring(lastDash + 1)
            return if (suffix.all { it.isDigit() }) candidate else buildKey
        }
    }
}
