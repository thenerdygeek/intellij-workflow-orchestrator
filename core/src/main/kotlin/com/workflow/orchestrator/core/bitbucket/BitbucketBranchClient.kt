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
    val latestCommit: String? = null,
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
    val values: List<BitbucketPrResponse>,
    val isLastPage: Boolean = true
)

// --- Full PR Management DTOs ---

@Serializable
data class BitbucketPrDetail(
    val id: Int,
    val title: String,
    val description: String? = null,
    val state: String,
    val version: Int = 0,
    val author: BitbucketPrParticipant? = null,
    val reviewers: List<BitbucketPrReviewer> = emptyList(),
    val createdDate: Long = 0,
    val updatedDate: Long = 0,
    val fromRef: BitbucketPrRef? = null,
    val toRef: BitbucketPrRef? = null,
    val links: BitbucketLinks? = null
)

@Serializable
data class BitbucketPrParticipant(
    val user: BitbucketUser
)

@Serializable
data class BitbucketPrReviewer(
    val user: BitbucketUser,
    val role: String = "REVIEWER",
    val approved: Boolean = false,
    val status: String = "UNAPPROVED"
)

@Serializable
data class BitbucketPrActivity(
    val id: Long,
    val action: String,
    val comment: BitbucketPrComment? = null,
    val commentAnchor: BitbucketCommentAnchor? = null,
    val user: BitbucketUser,
    val createdDate: Long = 0
)

@Serializable
data class BitbucketPrComment(
    val id: Long,
    val text: String,
    val author: BitbucketUser,
    val createdDate: Long = 0,
    val updatedDate: Long = 0,
    val anchor: BitbucketCommentAnchor? = null
)

/** Anchor data for inline code comments — identifies the file and line. */
@Serializable
data class BitbucketCommentAnchor(
    val path: String = "",
    val line: Int = 0,
    val lineType: String = "",    // ADDED, REMOVED, CONTEXT
    val fileType: String = "",    // FROM, TO
    val srcPath: String? = null
)

@Serializable
data class BitbucketPrChange(
    val path: BitbucketPath,
    val type: String,
    val nodeType: String = "FILE"
)

@Serializable
data class BitbucketPath(
    val toString: String,
    val name: String = ""
)

@Serializable
data class BitbucketPrUpdateRequest(
    val title: String,
    val description: String,
    val version: Int,
    val reviewers: List<BitbucketPrReviewerRef> = emptyList()
)

@Serializable
data class BitbucketPrReviewerRef(
    val user: BitbucketReviewerUser
)

@Serializable
data class BitbucketPrDetailListResponse(
    val values: List<BitbucketPrDetail> = emptyList(),
    val size: Int = 0,
    val isLastPage: Boolean = true,
    val start: Int = 0,
    val nextPageStart: Int? = null
)

@Serializable
private data class BitbucketPrActivityResponse(
    val values: List<BitbucketPrActivity> = emptyList(),
    val isLastPage: Boolean = true
)

@Serializable
private data class BitbucketPrChangesResponse(
    val values: List<BitbucketPrChange> = emptyList(),
    val isLastPage: Boolean = true
)

@Serializable
private data class AddCommentRequest(
    val text: String
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

// --- Merge Precondition & Strategy DTOs ---

@Serializable
data class BitbucketMergeStatus(
    val canMerge: Boolean = false,
    val conflicted: Boolean = false,
    val outcome: String = "",
    val vetoes: List<BitbucketMergeVeto> = emptyList()
)

@Serializable
data class BitbucketMergeVeto(
    val summaryMessage: String = "",
    val detailedMessage: String = ""
)

@Serializable
data class BitbucketMergeRequest(
    val message: String? = null,
    val strategyId: String? = null,
    val deleteSourceRef: Boolean = false
)

@Serializable
data class BitbucketMergeConfig(
    val defaultStrategy: BitbucketMergeStrategy? = null,
    val strategies: List<BitbucketMergeStrategy> = emptyList()
)

@Serializable
data class BitbucketMergeStrategy(
    val id: String,
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true
)

@Serializable
private data class BitbucketRepoSettingsResponse(
    val mergeConfig: BitbucketMergeConfig = BitbucketMergeConfig()
)

// --- Commit DTOs ---

@Serializable
data class BitbucketCommitListResponse(
    val values: List<BitbucketCommit> = emptyList(),
    val size: Int = 0,
    val isLastPage: Boolean = true,
    val start: Int = 0,
    val nextPageStart: Int? = null
)

@Serializable
data class BitbucketCommit(
    val id: String,
    val displayId: String,
    val message: String,
    val author: BitbucketUser? = null,
    val authorTimestamp: Long = 0,
    val parents: List<BitbucketCommitRef> = emptyList()
)

@Serializable
data class BitbucketCommitRef(val id: String, val displayId: String)

// --- Inline Comment & Reply Request DTOs ---

@Serializable
private data class InlineCommentRequest(
    val text: String,
    val anchor: InlineCommentAnchor
)

@Serializable
private data class InlineCommentAnchor(
    val path: String,
    val line: Int,
    val lineType: String,
    val fileType: String = "TO"
)

@Serializable
private data class ReplyCommentRequest(
    val text: String,
    val parent: CommentParentRef
)

@Serializable
private data class CommentParentRef(val id: Int)

@Serializable
private data class ReviewerStatusRequest(val status: String)

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
        com.workflow.orchestrator.core.http.HttpClientFactory.sharedPool.newBuilder()
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

    /**
     * Get the current authenticated user's username.
     * Uses /plugins/servlet/applinks/whoami which returns the plain username string.
     */
    suspend fun getCurrentUsername(): ApiResult<String> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching current username")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/plugins/servlet/applinks/whoami")
                    .get()
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val username = it.body?.string()?.trim() ?: ""
                            log.info("[Core:Bitbucket] Current user: $username")
                            ApiResult.Success(username)
                        }
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Failed to get username: HTTP ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    // --- Full PR Management Methods ---

    /**
     * Gets pull requests authored by the current user.
     * GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests?state={state}&role.1=AUTHOR
     */
    suspend fun getMyPullRequests(
        projectKey: String,
        repoSlug: String,
        state: String = "OPEN",
        username: String? = null,
        start: Int = 0,
        limit: Int = 25
    ): ApiResult<BitbucketPrDetailListResponse> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching my PRs (state=$state, username=$username, start=$start, limit=$limit) in $projectKey/$repoSlug")
            try {
                val usernameParam = if (!username.isNullOrBlank()) "&username.1=$username" else ""
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests?state=$state&role.1=AUTHOR$usernameParam&start=$start&limit=$limit")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<BitbucketPrDetailListResponse>(body)
                            log.info("[Core:Bitbucket] Found ${parsed.values.size} authored PRs (isLastPage=${parsed.isLastPage})")
                            ApiResult.Success(parsed)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Repository $projectKey/$repoSlug not found")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching my PRs", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Gets pull requests where the current user is a reviewer.
     * GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests?state={state}&role.1=REVIEWER
     */
    suspend fun getReviewingPullRequests(
        projectKey: String,
        repoSlug: String,
        state: String = "OPEN",
        username: String? = null,
        start: Int = 0,
        limit: Int = 25
    ): ApiResult<BitbucketPrDetailListResponse> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching reviewing PRs (state=$state, username=$username, start=$start, limit=$limit) in $projectKey/$repoSlug")
            try {
                val usernameParam = if (!username.isNullOrBlank()) "&username.1=$username" else ""
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests?state=$state&role.1=REVIEWER$usernameParam&start=$start&limit=$limit")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<BitbucketPrDetailListResponse>(body)
                            log.info("[Core:Bitbucket] Found ${parsed.values.size} reviewing PRs (isLastPage=${parsed.isLastPage})")
                            ApiResult.Success(parsed)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Repository $projectKey/$repoSlug not found")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching reviewing PRs", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Gets full details of a specific pull request.
     * GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}
     */
    suspend fun getPullRequestDetail(
        projectKey: String,
        repoSlug: String,
        prId: Int
    ): ApiResult<BitbucketPrDetail> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching PR #$prId detail in $projectKey/$repoSlug")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val pr = json.decodeFromString<BitbucketPrDetail>(body)
                            log.info("[Core:Bitbucket] PR #$prId: state=${pr.state}, version=${pr.version}")
                            ApiResult.Success(pr)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "PR #$prId not found in $projectKey/$repoSlug")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching PR #$prId", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Updates a pull request (title, description, reviewers).
     * PUT /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}
     * Requires version for optimistic locking.
     */
    suspend fun updatePullRequest(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        updateRequest: BitbucketPrUpdateRequest
    ): ApiResult<BitbucketPrDetail> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Updating PR #$prId in $projectKey/$repoSlug (version=${updateRequest.version})")
            try {
                val payload = json.encodeToString(updateRequest)
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId")
                    .put(payload)
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val pr = json.decodeFromString<BitbucketPrDetail>(body)
                            log.info("[Core:Bitbucket] PR #$prId updated successfully (new version=${pr.version})")
                            ApiResult.Success(pr)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "PR #$prId not found in $projectKey/$repoSlug")
                        409 -> ApiResult.Error(ErrorType.VALIDATION_ERROR, "PR #$prId version conflict — refresh and retry")
                        else -> {
                            val errorBody = it.body?.string() ?: ""
                            ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}: $errorBody")
                        }
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error updating PR #$prId", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Gets activity (comments, approvals, merges) for a pull request.
     * GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/activities?limit=50
     */
    suspend fun getPullRequestActivities(
        projectKey: String,
        repoSlug: String,
        prId: Int
    ): ApiResult<List<BitbucketPrActivity>> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching activities for PR #$prId in $projectKey/$repoSlug")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/activities?limit=50")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<BitbucketPrActivityResponse>(body)
                            log.info("[Core:Bitbucket] Found ${parsed.values.size} activities for PR #$prId")
                            ApiResult.Success(parsed.values)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "PR #$prId not found in $projectKey/$repoSlug")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching PR #$prId activities", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Adds a comment to a pull request.
     * POST /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/comments
     */
    suspend fun addPullRequestComment(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        text: String
    ): ApiResult<BitbucketPrComment> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Adding comment to PR #$prId in $projectKey/$repoSlug")
            try {
                val payload = json.encodeToString(AddCommentRequest(text))
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/comments")
                    .post(payload)
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val comment = json.decodeFromString<BitbucketPrComment>(body)
                            log.info("[Core:Bitbucket] Comment added to PR #$prId (id=${comment.id})")
                            ApiResult.Success(comment)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "PR #$prId not found in $projectKey/$repoSlug")
                        else -> {
                            val errorBody = it.body?.string() ?: ""
                            ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}: $errorBody")
                        }
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error adding comment to PR #$prId", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Approves a pull request.
     * POST /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/approve
     */
    suspend fun approvePullRequest(
        projectKey: String,
        repoSlug: String,
        prId: Int
    ): ApiResult<BitbucketPrReviewer> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Approving PR #$prId in $projectKey/$repoSlug")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/approve")
                    .post("".toRequestBody("application/json".toMediaType()))
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val reviewer = json.decodeFromString<BitbucketPrReviewer>(body)
                            log.info("[Core:Bitbucket] PR #$prId approved")
                            ApiResult.Success(reviewer)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "PR #$prId not found in $projectKey/$repoSlug")
                        409 -> ApiResult.Error(ErrorType.VALIDATION_ERROR, "Cannot approve PR #$prId — already approved or not a reviewer")
                        else -> {
                            val errorBody = it.body?.string() ?: ""
                            ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}: $errorBody")
                        }
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error approving PR #$prId", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Removes approval from a pull request.
     * DELETE /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/approve
     */
    suspend fun unapprovePullRequest(
        projectKey: String,
        repoSlug: String,
        prId: Int
    ): ApiResult<BitbucketPrReviewer> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Removing approval from PR #$prId in $projectKey/$repoSlug")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/approve")
                    .delete()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val reviewer = json.decodeFromString<BitbucketPrReviewer>(body)
                            log.info("[Core:Bitbucket] PR #$prId approval removed")
                            ApiResult.Success(reviewer)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "PR #$prId not found in $projectKey/$repoSlug")
                        else -> {
                            val errorBody = it.body?.string() ?: ""
                            ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}: $errorBody")
                        }
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error unapproving PR #$prId", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Merges a pull request.
     * POST /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/merge?version={version}
     * Requires version for optimistic locking.
     * Optionally accepts merge strategy, delete-source-branch flag, and commit message.
     */
    suspend fun mergePullRequest(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        version: Int,
        strategyId: String? = null,
        deleteSourceBranch: Boolean = false,
        commitMessage: String? = null
    ): ApiResult<BitbucketPrDetail> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Merging PR #$prId in $projectKey/$repoSlug (version=$version, strategy=$strategyId, deleteBranch=$deleteSourceBranch)")
            try {
                val mergeRequest = BitbucketMergeRequest(
                    message = commitMessage,
                    strategyId = strategyId,
                    deleteSourceRef = deleteSourceBranch
                )
                val jsonBody = json.encodeToString(mergeRequest)
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/merge?version=$version")
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val pr = json.decodeFromString<BitbucketPrDetail>(body)
                            log.info("[Core:Bitbucket] PR #$prId merged successfully")
                            ApiResult.Success(pr)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "PR #$prId not found in $projectKey/$repoSlug")
                        409 -> ApiResult.Error(ErrorType.VALIDATION_ERROR, "PR #$prId version conflict or merge preconditions not met — refresh and retry")
                        else -> {
                            val errorBody = it.body?.string() ?: ""
                            ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}: $errorBody")
                        }
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error merging PR #$prId", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Checks merge preconditions for a pull request.
     * GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/merge
     * Returns merge status including whether the PR can be merged and any vetoes.
     */
    suspend fun getMergeStatus(
        projectKey: String,
        repoSlug: String,
        prId: Int
    ): ApiResult<BitbucketMergeStatus> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Checking merge status for PR #$prId in $projectKey/$repoSlug")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/merge")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val status = json.decodeFromString<BitbucketMergeStatus>(body)
                            log.info("[Core:Bitbucket] Merge status for PR #$prId: canMerge=${status.canMerge}, conflicted=${status.conflicted}, vetoes=${status.vetoes.size}")
                            ApiResult.Success(status)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "PR #$prId not found in $projectKey/$repoSlug")
                        else -> {
                            val errorBody = it.body?.string() ?: ""
                            ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}: $errorBody")
                        }
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error checking merge status for PR #$prId", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Gets available merge strategies for a repository.
     * GET /rest/api/1.0/projects/{proj}/repos/{repo}/settings/pull-requests/git
     * Returns the merge configuration including default strategy and available strategies.
     */
    suspend fun getMergeStrategies(
        projectKey: String,
        repoSlug: String
    ): ApiResult<BitbucketMergeConfig> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching merge strategies for $projectKey/$repoSlug")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/settings/pull-requests/git")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val settings = json.decodeFromString<BitbucketRepoSettingsResponse>(body)
                            log.info("[Core:Bitbucket] Found ${settings.mergeConfig.strategies.size} merge strategies for $projectKey/$repoSlug")
                            ApiResult.Success(settings.mergeConfig)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Repository $projectKey/$repoSlug not found")
                        else -> {
                            val errorBody = it.body?.string() ?: ""
                            ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}: $errorBody")
                        }
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching merge strategies", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Declines a pull request.
     * POST /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/decline?version={version}
     * Requires version for optimistic locking.
     */
    suspend fun declinePullRequest(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        version: Int
    ): ApiResult<BitbucketPrDetail> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Declining PR #$prId in $projectKey/$repoSlug (version=$version)")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/decline?version=$version")
                    .post("".toRequestBody("application/json".toMediaType()))
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val pr = json.decodeFromString<BitbucketPrDetail>(body)
                            log.info("[Core:Bitbucket] PR #$prId declined")
                            ApiResult.Success(pr)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "PR #$prId not found in $projectKey/$repoSlug")
                        409 -> ApiResult.Error(ErrorType.VALIDATION_ERROR, "PR #$prId version conflict — refresh and retry")
                        else -> {
                            val errorBody = it.body?.string() ?: ""
                            ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}: $errorBody")
                        }
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error declining PR #$prId", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Gets the raw diff for a pull request.
     * GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/diff
     * Returns the diff as plain text.
     */
    suspend fun getPullRequestDiff(
        projectKey: String,
        repoSlug: String,
        prId: Int
    ): ApiResult<String> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching diff for PR #$prId in $projectKey/$repoSlug")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/diff")
                    .get()
                    .header("Accept", "text/plain")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            log.info("[Core:Bitbucket] PR #$prId diff fetched (${body.length} chars)")
                            ApiResult.Success(body)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "PR #$prId not found in $projectKey/$repoSlug")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching PR #$prId diff", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Gets the list of changed files for a pull request.
     * GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/changes?limit=100
     */
    suspend fun getPullRequestChanges(
        projectKey: String,
        repoSlug: String,
        prId: Int
    ): ApiResult<List<BitbucketPrChange>> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching changes for PR #$prId in $projectKey/$repoSlug")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/changes?limit=100")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<BitbucketPrChangesResponse>(body)
                            log.info("[Core:Bitbucket] Found ${parsed.values.size} changed files in PR #$prId")
                            ApiResult.Success(parsed.values)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "PR #$prId not found in $projectKey/$repoSlug")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching PR #$prId changes", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    // --- Commit, Inline Comment, Reply, Reviewer Status, File Browse Methods ---

    /**
     * Gets commits for a pull request.
     * GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/commits?limit={limit}
     */
    suspend fun getPullRequestCommits(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        limit: Int = 50
    ): ApiResult<BitbucketCommitListResponse> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching commits for PR #$prId in $projectKey/$repoSlug")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/commits?limit=$limit")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<BitbucketCommitListResponse>(body)
                            log.info("[Core:Bitbucket] Found ${parsed.values.size} commits for PR #$prId")
                            ApiResult.Success(parsed)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "PR #$prId not found in $projectKey/$repoSlug")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching commits for PR #$prId", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Adds an inline comment to a specific file/line in a pull request.
     * POST /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/comments
     */
    suspend fun addInlineComment(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        filePath: String,
        lineNumber: Int,
        lineType: String,
        text: String
    ): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Adding inline comment to PR #$prId at $filePath:$lineNumber ($lineType)")
            try {
                val payload = json.encodeToString(
                    InlineCommentRequest(
                        text = text,
                        anchor = InlineCommentAnchor(
                            path = filePath,
                            line = lineNumber,
                            lineType = lineType
                        )
                    )
                ).toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/comments")
                    .post(payload)
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            log.info("[Core:Bitbucket] Inline comment added to PR #$prId at $filePath:$lineNumber")
                            ApiResult.Success(Unit)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "PR #$prId not found in $projectKey/$repoSlug")
                        else -> {
                            val errorBody = it.body?.string() ?: ""
                            ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}: $errorBody")
                        }
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error adding inline comment to PR #$prId", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Replies to an existing comment on a pull request.
     * POST /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/comments
     */
    suspend fun replyToComment(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        parentCommentId: Int,
        text: String
    ): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Replying to comment #$parentCommentId on PR #$prId")
            try {
                val payload = json.encodeToString(
                    ReplyCommentRequest(
                        text = text,
                        parent = CommentParentRef(id = parentCommentId)
                    )
                ).toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/comments")
                    .post(payload)
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            log.info("[Core:Bitbucket] Reply added to comment #$parentCommentId on PR #$prId")
                            ApiResult.Success(Unit)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "PR #$prId or comment #$parentCommentId not found")
                        else -> {
                            val errorBody = it.body?.string() ?: ""
                            ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}: $errorBody")
                        }
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error replying to comment on PR #$prId", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Sets reviewer status on a pull request (APPROVED, NEEDS_WORK, UNAPPROVED).
     * PUT /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/participants/{username}
     */
    suspend fun setReviewerStatus(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        username: String,
        status: String
    ): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Setting reviewer status for $username on PR #$prId to $status")
            try {
                val payload = json.encodeToString(ReviewerStatusRequest(status = status))
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/participants/$username")
                    .put(payload)
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            log.info("[Core:Bitbucket] Reviewer status set: $username=$status on PR #$prId")
                            ApiResult.Success(Unit)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "PR #$prId or participant $username not found")
                        409 -> ApiResult.Error(ErrorType.VALIDATION_ERROR, "Cannot set status $status for $username on PR #$prId")
                        else -> {
                            val errorBody = it.body?.string() ?: ""
                            ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}: $errorBody")
                        }
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error setting reviewer status on PR #$prId", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Gets raw file content from a Bitbucket Server repository at a specific ref.
     * GET /rest/api/1.0/projects/{proj}/repos/{repo}/browse/{filePath}?at={ref}&raw
     * Note: filePath is NOT URL-encoded — Bitbucket expects literal path separators.
     */
    suspend fun getFileContent(
        projectKey: String,
        repoSlug: String,
        filePath: String,
        atRef: String
    ): ApiResult<String> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching file content: $filePath at $atRef in $projectKey/$repoSlug")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/browse/$filePath?at=$atRef&raw")
                    .get()
                    .header("Accept", "text/plain")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            log.info("[Core:Bitbucket] File content fetched: $filePath (${body.length} chars)")
                            ApiResult.Success(body)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "File $filePath not found at ref $atRef in $projectKey/$repoSlug")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching file content: $filePath", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    companion object {
        /**
         * Extract Bamboo plan key from a build status.
         * Prefers extracting from the URL (e.g., ".../browse/PROJ-PLAN-42" → "PROJ-PLAN")
         * Falls back to stripping trailing digits from the key.
         */
        fun extractPlanKey(buildStatus: BitbucketBuildStatus): String {
            // Try URL first: https://bamboo.example.com/browse/PROJ-PLAN-42
            val url = buildStatus.url
            if (url.contains("/browse/")) {
                val browseKey = url.substringAfter("/browse/").substringBefore("?").trim('/')
                val lastDash = browseKey.lastIndexOf('-')
                if (lastDash > 0) {
                    val suffix = browseKey.substring(lastDash + 1)
                    if (suffix.all { it.isDigit() }) {
                        return browseKey.substring(0, lastDash)
                    }
                }
            }

            // Fallback: strip trailing digits from key
            val key = buildStatus.key
            // Try last-dash approach: PROJ-BUILD-42 → PROJ-BUILD
            val lastDash = key.lastIndexOf('-')
            if (lastDash > 0) {
                val suffix = key.substring(lastDash + 1)
                if (suffix.all { it.isDigit() }) {
                    return key.substring(0, lastDash)
                }
            }

            // Last resort: strip trailing digits from end of key
            // e.g., PROJ-SERVICE514 → PROJ-SERVICE
            return key.trimEnd { it.isDigit() }.ifEmpty { key }
        }
    }
}
