package com.workflow.orchestrator.core.bitbucket

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.http.HttpClientFactory
import com.workflow.orchestrator.core.http.HttpTimeouts
import com.workflow.orchestrator.core.http.RetryInterceptor
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Bitbucket DC's structured error response envelope. Used to disambiguate
 * 409s on the merge endpoint (PullRequestOutOfDateException vs.
 * PullRequestMergeVetoedException) and to extract user-actionable error
 * messages without lossy string-matching on a hardcoded copy.
 */
@Serializable
internal data class BitbucketErrorEnvelope(
    val errors: List<BitbucketErrorEntry> = emptyList(),
)

@Serializable
internal data class BitbucketErrorEntry(
    val message: String? = null,
    val exceptionName: String? = null,
)

@Serializable
data class BitbucketProject(
    val key: String,
    val name: String,
    val description: String? = null
)

@Serializable
data class BitbucketCloneLink(
    val name: String,
    val href: String
)

@Serializable
data class BitbucketRepoLinks(
    val clone: List<BitbucketCloneLink> = emptyList(),
    val self: List<BitbucketCloneLink> = emptyList()
)

@Serializable
data class BitbucketRepoDetail(
    val slug: String,
    val name: String? = null,
    val project: BitbucketProject,
    val links: BitbucketRepoLinks = BitbucketRepoLinks()
)

@Serializable
data class BitbucketBranch(
    val id: String,
    val displayId: String,
    val latestCommit: String? = null,
    val isDefault: Boolean = false,
    /**
     * Populated by Bitbucket DC 9.4 when the request includes `details=true`.
     * Carries `aheadBehind`, `latestCommit` author info, and resolved Jira issues
     * inline; eliminates the N follow-up calls the plugin used to make per branch
     * for `aheadBehind` / `lastModified`.
     *
     * Source: docs/research/2026-05-07-bitbucket-recommendations.md §3 R-SWAP-3.
     */
    val metadata: BranchMetadata? = null,
)

/**
 * Optional `metadata` payload Bitbucket DC adds when a branches listing is
 * requested with `details=true`. All fields use `JsonElement` so we don't lock
 * the schema down — Bitbucket has multiple metadata providers (build-status,
 * jira-link, default-reviewers, ahead-behind, latest-commit) and the keys/shapes
 * differ per provider. Callers that need a specific provider's payload should
 * decode it from the JsonElement on demand.
 */
@Serializable
data class BranchMetadata(
    @kotlinx.serialization.SerialName("com.atlassian.bitbucket.server.bitbucket-branch:ahead-behind-metadata-provider")
    val aheadBehind: AheadBehindMetadata? = null,
    @kotlinx.serialization.SerialName("com.atlassian.bitbucket.server.bitbucket-ref-metadata:latest-commit-metadata")
    val latestCommitMetadata: kotlinx.serialization.json.JsonElement? = null,
    @kotlinx.serialization.SerialName("com.atlassian.bitbucket.server.bitbucket-jira:branch-list-jira-issues")
    val jiraIssues: kotlinx.serialization.json.JsonElement? = null,
    @kotlinx.serialization.SerialName("com.atlassian.bitbucket.server.bitbucket-build:build-metadata")
    val build: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
data class AheadBehindMetadata(
    val ahead: Int = 0,
    val behind: Int = 0,
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

/**
 * One condition in Bitbucket Server's default-reviewers plugin. A repo can have many
 * conditions; each is scoped to a (sourceRefMatcher, targetRefMatcher) pair so that
 * different default-reviewer sets can apply for different branch flows. The audit
 * (P1 finding #6, 2026-05-07) showed the previous DTO discarded the matchers and
 * unioned every reviewer across every condition — so a `feature/x → develop` PR
 * would suggest reviewers configured for `release/{any}` → `master` too.
 *
 * Real shape (from probe `default_reviewers_conditions.json`): the `type` on a
 * matcher is itself a `{id, name}` object — the `id` field is the matcher-type
 * discriminator (`BRANCH`, `MODEL_BRANCH`, `MODEL_CATEGORY`, `ANY_REF`, `PATTERN`).
 *
 * Matching rules per [RefMatcherType]:
 *  - `BRANCH` / `MODEL_BRANCH`: the matcher's [RefMatcher.id] is the full branch ref
 *    (e.g. `refs/heads/develop`); we match exact-equal against either the branch ref
 *    or the displayId-style "develop" form passed by callers.
 *  - `MODEL_CATEGORY`: the matcher's `id` is the category (`feature`, `release`, …);
 *    we match the branch as a category prefix (`feature/x` matches `feature`).
 *  - `ANY_REF`: always matches.
 *  - `PATTERN`: glob with `*` and `?`; small regex transform escapes the rest.
 */
@Serializable
internal data class DefaultReviewerCondition(
    val id: Int = 0,
    val sourceRefMatcher: RefMatcher = RefMatcher.ANY,
    val targetRefMatcher: RefMatcher = RefMatcher.ANY,
    val reviewers: List<BitbucketUser> = emptyList(),
    val requiredApprovals: Int = 0,
)

@Serializable
internal data class RefMatcher(
    val id: String = "",
    val displayId: String = "",
    val type: RefMatcherTypeDescriptor = RefMatcherTypeDescriptor(),
) {
    /** Discriminator pulled out of the nested `type` object for branching. */
    val matcherType: RefMatcherType
        get() = when (type.id) {
            "BRANCH" -> RefMatcherType.BRANCH
            "MODEL_BRANCH" -> RefMatcherType.MODEL_BRANCH
            "MODEL_CATEGORY" -> RefMatcherType.MODEL_CATEGORY
            "ANY_REF" -> RefMatcherType.ANY_REF
            "PATTERN" -> RefMatcherType.PATTERN
            else -> RefMatcherType.ANY_REF
        }

    /**
     * True iff this matcher accepts [branch]. [branch] may be either the full
     * `refs/heads/<name>` form or just the displayId `<name>` — we normalise
     * before comparing.
     */
    fun matches(branch: String): Boolean {
        val displayBranch = branch.removePrefix("refs/heads/")
        return when (matcherType) {
            RefMatcherType.BRANCH, RefMatcherType.MODEL_BRANCH -> {
                val normalisedMatcher = id.removePrefix("refs/heads/")
                normalisedMatcher == displayBranch || id == branch || id == "refs/heads/$displayBranch"
            }
            RefMatcherType.MODEL_CATEGORY -> {
                // Category names typically map to a "<category>/" prefix on the branch
                // (e.g. category=feature matches feature/x). Be permissive: also
                // accept exact-equal because Bitbucket sometimes uses the displayId.
                displayBranch == id || displayBranch.startsWith("$id/")
            }
            RefMatcherType.ANY_REF -> true
            RefMatcherType.PATTERN -> globToRegex(id).matches(displayBranch) ||
                globToRegex(id).matches(branch)
        }
    }

    companion object {
        val ANY = RefMatcher(
            id = "ANY_REF_MATCHER_ID",
            displayId = "Any branch",
            type = RefMatcherTypeDescriptor(id = "ANY_REF", name = "Any branch"),
        )

        /** Translate Bitbucket-style globs (`*`, `?`) into a Kotlin [Regex]. */
        internal fun globToRegex(pattern: String): Regex {
            val sb = StringBuilder("^")
            for (c in pattern) {
                when (c) {
                    '*' -> sb.append(".*")
                    '?' -> sb.append('.')
                    '.', '+', '(', ')', '{', '}', '[', ']', '^', '$', '|', '\\' -> {
                        sb.append('\\').append(c)
                    }
                    else -> sb.append(c)
                }
            }
            sb.append('$')
            return Regex(sb.toString())
        }
    }
}

@Serializable
internal data class RefMatcherTypeDescriptor(
    val id: String = "ANY_REF",
    val name: String = "",
)

internal enum class RefMatcherType { BRANCH, MODEL_BRANCH, MODEL_CATEGORY, ANY_REF, PATTERN }

@Serializable
private data class ProjectListResponse(
    val values: List<BitbucketProject>,
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
    val latestCommit: String = "",
    /**
     * Populated by the dashboard PR endpoint (and any cross-repo listing) so callers
     * can identify the source/target repo without re-resolving from the PR id.
     * Kept optional because per-repo endpoints don't always echo it back.
     *
     * Source: docs/research/2026-05-07-bitbucket-recommendations.md §3 R-SWAP-1.
     */
    val repository: BitbucketPrRefRepository? = null,
)

@Serializable
data class BitbucketPrRefRepository(
    val slug: String = "",
    val name: String = "",
    val project: BitbucketPrRefProject = BitbucketPrRefProject(),
)

@Serializable
data class BitbucketPrRefProject(
    val key: String = "",
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
) {
    /** Transient repo name — set after fetch to identify source repo in multi-repo setups */
    @kotlinx.serialization.Transient
    var repoName: String = ""
}

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
    val srcPath: BitbucketPath? = null,
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

/**
 * Project + repo coordinates for a Bitbucket repository. Wraps the two-string
 * `(projectKey, repoSlug)` pair that every PR write path needs to thread through
 * fetch-modify-write helpers without expanding their argument list.
 *
 * Introduced for [BitbucketBranchClient.modifyPullRequest] (PR 1 of the 2026-05-07
 * write-ops fix plan); PRs 3 + 6 will adopt it for `addReviewer`, `removeReviewer`,
 * `updateTitle`, and `merge`.
 */
data class RepoCoords(val projectKey: String, val repoSlug: String)

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
data class BitbucketPrActivityResponse(
    val values: List<BitbucketPrActivity> = emptyList(),
    val isLastPage: Boolean = true,
    val nextPageStart: Int? = null,
)

@Serializable
data class BitbucketPrChangesResponse(
    val values: List<BitbucketPrChange> = emptyList(),
    val isLastPage: Boolean = true,
    val nextPageStart: Int? = null,
)

@Serializable
data class BitbucketPrCommentResponse(
    val id: Long,
    val version: Int = 0,
    val text: String = "",
    val author: BitbucketPrCommentAuthor = BitbucketPrCommentAuthor(),
    val createdDate: Long = 0,
    val updatedDate: Long = 0,
    val anchor: BitbucketPrCommentAnchor? = null,
    val state: String = "OPEN",
    val severity: String = "NORMAL",
    val comments: List<BitbucketPrCommentResponse> = emptyList(),
    val permittedOperations: BitbucketPrCommentPermittedOps? = null,
    val threadResolvedDate: Long? = null,
)

@Serializable
data class BitbucketPrCommentAuthor(
    val name: String = "",
    val displayName: String = "",
    val emailAddress: String? = null,
    val avatarUrl: String? = null,
)

@Serializable
data class BitbucketPrCommentAnchor(
    val path: String = "",
    val srcPath: String? = null,
    val line: Int? = null,
    val lineType: String? = null,
    val fileType: String? = null,
    val fromHash: String? = null,
    val toHash: String? = null,
)

@Serializable
data class BitbucketPrCommentPermittedOps(
    val editable: Boolean = false,
    val deletable: Boolean = false,
    val transitionable: Boolean = false,
)

@Serializable
data class BitbucketPrCommentList(
    val values: List<BitbucketPrCommentResponse> = emptyList(),
    val isLastPage: Boolean = true,
    val nextPageStart: Int? = null,
)

// Request bodies for editing / state changes (private — only used inside the client)

@Serializable
private data class EditCommentRequest(val text: String, val version: Int)

@Serializable
private data class ResolveCommentRequest(val state: String)

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
internal data class InlineCommentRequest(
    val text: String,
    val anchor: InlineCommentAnchor,
)

/**
 * Bitbucket DC inline-comment anchor. The anchor identifies which file/line the
 * comment attaches to.
 *
 * The five pinning fields ([diffType], [fromHash], [toHash]) were added in PR 6
 * of the 2026-05-07 write-ops fix plan to fix audit finding #7: when [diffType]
 * is left at the server default of `EFFECTIVE`, comments float to whichever line
 * the diff hunk maps to as new commits land on the PR. Sending `diffType=COMMIT`
 * with the explicit commit pair pins the comment to the exact commit the
 * reviewer saw, matching what Bitbucket's web UI does on review submission.
 *
 * `srcPath` carries the old path on a renamed file. The three pinning fields
 * are nullable; null is the legacy "let the server pick the diff" behaviour
 * (callers that don't yet know the commit hash retain the old behaviour).
 */
@Serializable
internal data class InlineCommentAnchor(
    val path: String,
    val line: Int,
    val lineType: String, // ADDED | REMOVED | CONTEXT
    val fileType: String, // FROM | TO
    val srcPath: String? = null,
    val diffType: String? = null, // COMMIT | EFFECTIVE | RANGE
    val fromHash: String? = null,
    val toHash: String? = null,
) {
    companion object {
        fun deriveFileType(lineType: String): String =
            if (lineType == "REMOVED") "FROM" else "TO"
    }
}

/** Discriminator strings for [InlineCommentAnchor.diffType]. */
internal object InlineCommentDiffType {
    const val COMMIT = "COMMIT"
    const val EFFECTIVE = "EFFECTIVE"
    const val RANGE = "RANGE"
}

@Serializable
private data class ReplyCommentRequest(
    val text: String,
    val parent: CommentParentRef
)

@Serializable
private data class CommentParentRef(val id: Int)

@Serializable
private data class ReviewerStatusRequest(val status: String, val approved: Boolean)

// --- Audit-driven additions (2026-05-07) ---

/**
 * Response shape from `GET /pull-requests/{id}/blocker-comments?count=true`.
 * The `count=true` form returns just the size; the `values=` form (without `count=true`)
 * returns the full list. We surface both fields so callers can use the same DTO.
 *
 * Source: docs/research/2026-05-07-bitbucket-recommendations.md §3 R-SWAP-4.
 */
@Serializable
data class BitbucketBlockerCommentsResponse(
    val size: Int = 0,
    val count: Int? = null,
    val values: List<BitbucketPrCommentResponse> = emptyList(),
) {
    /**
     * Blocker count regardless of response shape. DC's `?count=true` returns a count-only body
     * `{"count": N}` (so `size`/`values` stay at their defaults); the full listing returns
     * `{"size": N, "values": [...]}`. Reading `size` alone returned 0 for the count-only path.
     */
    val effectiveCount: Int get() = count ?: values.size.takeIf { it > 0 } ?: size
}

/**
 * Response shape from `GET /pull-requests/{id}/participants` — explicit endpoint
 * with `state` (UNAPPROVED / APPROVED / NEEDS_WORK) and `lastReviewedCommit` per
 * participant, richer than the embedded `reviewers` array on `getPullRequest`.
 *
 * Source: docs/research/2026-05-07-bitbucket-recommendations.md §3 R-SWAP-5.
 */
@Serializable
data class BitbucketParticipantsResponse(
    val values: List<BitbucketPrParticipantDetail> = emptyList(),
    val size: Int = 0,
    val isLastPage: Boolean = true,
    val nextPageStart: Int? = null,
)

@Serializable
data class BitbucketPrParticipantDetail(
    val user: BitbucketUser,
    val role: String = "REVIEWER",
    val approved: Boolean = false,
    val status: String = "UNAPPROVED",
    val lastReviewedCommit: String? = null,
)

/**
 * Response shape from `GET /rest/build-status/1.0/commits/stats/{sha}`. Used for
 * cheap aggregate counters on dashboards / commit-list badges (vs paginating the
 * full build list per commit).
 *
 * Source: docs/research/2026-05-07-bitbucket-recommendations.md §2 B6, §4 R-ADD-12.
 */
@Serializable
data class BitbucketBuildStatsResponse(
    val successful: Int = 0,
    val failed: Int = 0,
    val inProgress: Int = 0,
)

/**
 * Single Jira-issue reference returned by Bitbucket's Jira-link plugin from
 * `GET /rest/jira/1.0/.../pull-requests/{id}/issues`. Replaces three regex-based
 * extraction sites where the plugin used to scan PR titles, branch names, and
 * commit messages for `[A-Z]+-[0-9]+` patterns.
 *
 * Source: docs/research/2026-05-07-bitbucket-recommendations.md §4 R-ADD-11.
 */
@Serializable
data class BitbucketJiraIssueRef(
    val key: String,
    val url: String = "",
)

/**
 * Response shape from `GET /commits/{sha}/pull-requests` — reverse lookup from
 * commit SHA to PRs containing the commit. Wired into the Bamboo bridge so a
 * failed build can identify which PR authors to notify.
 *
 * Source: docs/research/2026-05-07-bitbucket-recommendations.md §2 B1, §4 R-ADD-5.
 */
@Serializable
data class BitbucketCommitPrsResponse(
    val values: List<BitbucketPrDetail> = emptyList(),
    val size: Int = 0,
    val isLastPage: Boolean = true,
    val nextPageStart: Int? = null,
)

/**
 * Response shape from `GET /rest/required-builds/latest/.../conditions` (the
 * canonical path; the v0 path under `/rest/api/1.0/` 404s on DC 9.4 per the
 * audit).
 *
 * Source: docs/research/2026-05-07-bitbucket-recommendations.md §4 R-ADD-15.
 */
@Serializable
data class BitbucketRequiredBuildsResponse(
    val values: List<BitbucketRequiredBuildCondition> = emptyList(),
    val size: Int = 0,
    val isLastPage: Boolean = true,
)

@Serializable
data class BitbucketRequiredBuildCondition(
    val id: Long = 0,
    val buildParentKeys: List<String> = emptyList(),
    val refMatcher: kotlinx.serialization.json.JsonElement? = null,
    val exemptRefMatcher: kotlinx.serialization.json.JsonElement? = null,
)

/**
 * Lightweight Bitbucket Server REST client for branch operations only.
 * Lives in :core so both :jira (Start Work) and :handover (PR creation)
 * can access it without cross-module dependencies.
 */
/**
 * @param timeouts connect/read timeout pair. Defaults to [HttpTimeouts.DEFAULT] (10s/30s)
 *   so existing call sites that don't have a [Project] context are unaffected.
 *   Pass [HttpClientFactory.timeoutsFromSettings] at construction to pick up the
 *   user-configured values from Advanced Network settings.
 *   Audit finding core:F-13.
 */
class BitbucketBranchClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val timeouts: HttpTimeouts = HttpTimeouts.DEFAULT
) {
    companion object {
        /**
         * Factory method for the common "create a client from the configured Bitbucket URL
         * and the stored Bitbucket credential" pattern.
         *
         * Returns `null` if Bitbucket is not configured (blank base URL). Callers that
         * need to differentiate between missing config and an HTTP error should branch on
         * the `null` return before invoking any method.
         */
        fun fromConfiguredSettings(): BitbucketBranchClient? {
            val url = com.workflow.orchestrator.core.settings.ConnectionSettings.getInstance()
                .state.bitbucketUrl.trimEnd('/')
            if (url.isBlank()) return null
            val credentialStore = com.workflow.orchestrator.core.auth.CredentialStore()
            return BitbucketBranchClient(
                baseUrl = url,
                tokenProvider = { credentialStore.getToken(com.workflow.orchestrator.core.model.ServiceType.BITBUCKET) }
            )
        }

        /**
         * Factory method that constructs a client using the project/slug from a [RepoConfig]
         * instead of reading the single-value `PluginSettings.state.bitbucket*` fields.
         *
         * Auth token and base URL are resolved identically to [fromConfiguredSettings]:
         * - base URL from [com.workflow.orchestrator.core.settings.ConnectionSettings]
         * - token from [com.workflow.orchestrator.core.auth.CredentialStore] with [com.workflow.orchestrator.core.model.ServiceType.BITBUCKET]
         *
         * Returns null if the base URL is blank (Bitbucket not configured) or if
         * [config] does not have both [com.workflow.orchestrator.core.settings.RepoConfig.bitbucketProjectKey]
         * and [com.workflow.orchestrator.core.settings.RepoConfig.bitbucketRepoSlug] set.
         */
        fun forRepo(config: com.workflow.orchestrator.core.settings.RepoConfig): BitbucketBranchClient? {
            if (!config.isConfigured) return null
            val url = com.workflow.orchestrator.core.settings.ConnectionSettings.getInstance()
                .state.bitbucketUrl.trimEnd('/')
            if (url.isBlank()) return null
            val credentialStore = com.workflow.orchestrator.core.auth.CredentialStore()
            return BitbucketBranchClient(
                baseUrl = url,
                tokenProvider = { credentialStore.getToken(com.workflow.orchestrator.core.model.ServiceType.BITBUCKET) }
            )
        }

        /**
         * Maximum number of characters returned from a PR diff.
         *
         * Rationale: the agent session has ~190K input-token budget (see agent/CLAUDE.md).
         * 327,680 chars ≈ 80K tokens at 4 chars/token, leaving headroom for the system
         * prompt, Jira ticket context, and tool definitions. This is intentionally larger
         * than the 10K cap in PrDescriptionGenerator (which is a single-LLM-call flow with
         * a much tighter budget). May be tuned in Phase 4.
         */
        const val MAX_DIFF_CHARS = 327_680

        /**
         * Extract the Bamboo plan key from a Bitbucket build status.
         *
         * Bitbucket build statuses report the *build* key (e.g. `PROJ-PLAN-42`), but
         * triggering a new build requires the plan key without the build-number suffix
         * (`PROJ-PLAN`). This helper prefers parsing the browse URL
         * (`.../browse/PROJ-PLAN-42` → `PROJ-PLAN`) and falls back to stripping the
         * trailing `-42` digits from the key.
         */
        fun extractPlanKey(buildStatus: BitbucketBuildStatus): String {
            // Prefer the browse URL: https://bamboo.example.com/browse/PROJ-PLAN-42
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

            // Fallback: strip the trailing "-<digits>" build number from the key.
            // e.g. PROJ-BUILD-42 → PROJ-BUILD
            val key = buildStatus.key
            val lastDash = key.lastIndexOf('-')
            if (lastDash > 0) {
                val suffix = key.substring(lastDash + 1)
                if (suffix.all { it.isDigit() }) {
                    return key.substring(0, lastDash)
                }
            }

            // Last resort: strip trailing digits without a dash boundary.
            // e.g. PROJ-SERVICE514 → PROJ-SERVICE
            return key.trimEnd { it.isDigit() }.ifEmpty { key }
        }
    }

    private val log = Logger.getInstance(BitbucketBranchClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * OkHttpClient built ONCE per [BitbucketBranchClient] instance, sharing the
     * plugin-wide [okhttp3.ConnectionPool] via [HttpClientFactory.sharedPool].
     *
     * Previously this constructed a new [HttpClientFactory] per instance, which created
     * an independent [okhttp3.ConnectionPool] per action. Each call to `fromConfiguredSettings()`
     * or `forRepo()` produced a fresh client — leaking connection pools and threads.
     *
     * Now all instances share the same base client ([HttpClientFactory.sharedPool]) and only
     * add an instance-scoped [AuthInterceptor] on top for BEARER auth. Auth scheme stays BEARER
     * (same as Jira, Bamboo, SonarQube per core/CLAUDE.md). Token resolution is still dynamic:
     * the [tokenProvider] lambda is captured per-instance and called at request time.
     *
     * Audit finding core:F-6.
     */
    internal val httpClient: OkHttpClient by lazy {
        HttpClientFactory.sharedPool
            .newBuilder()
            .connectTimeout(timeouts.connectSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(timeouts.readSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor())
            .addInterceptor(AuthInterceptor({ tokenProvider() }, AuthScheme.BEARER))
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
     * Fetches authoritative repository metadata including server-canonical clone URLs.
     * GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}
     *
     * Returns 200 → Success(detail with links.clone[]), 404 → Error(NOT_FOUND), 401/403 → Error(AUTH_FAILED).
     * Used by auto-detection to validate parsed remote URLs and persist a canonical URL
     * that survives URL drift (PAT changes, mirror swap, server-side renames).
     */
    suspend fun getRepository(projectKey: String, repoSlug: String): ApiResult<BitbucketRepoDetail> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching repo detail $projectKey/$repoSlug")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> {
                            val body = response.body?.string()
                                ?: return@use ApiResult.Error(ErrorType.NETWORK_ERROR, "empty body")
                            ApiResult.Success(json.decodeFromString<BitbucketRepoDetail>(body))
                        }
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "repo not found: $projectKey/$repoSlug")
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "auth failure (401)")
                        403 -> ApiResult.Error(ErrorType.AUTH_FAILED, "auth failure (403)")
                        else -> ApiResult.Error(ErrorType.NETWORK_ERROR, "http ${response.code}")
                    }
                }
            } catch (e: IOException) {
                log.warn("[Core:Bitbucket] getRepository failed", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, e.message ?: "io error")
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
                // `details=true` (R-SWAP-3) inlines `metadata` (aheadBehind, latestCommit,
                // build, jira-link) per branch — eliminates the per-branch follow-up calls
                // the plugin used to make for that information.
                // Source: docs/research/2026-05-07-bitbucket-recommendations.md §3 R-SWAP-3.
                //
                // filterText is added via addQueryParameter (C5 fix: prevents parameter
                // injection when the caller supplies a value containing '&', '=', or '#').
                val urlBuilder = "$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/branches"
                    .toHttpUrl().newBuilder()
                    .addQueryParameter("limit", "100")
                    .addQueryParameter("orderBy", "MODIFICATION")
                    .addQueryParameter("details", "true")
                if (filterText.isNotBlank()) urlBuilder.addQueryParameter("filterText", filterText)
                val request = Request.Builder()
                    .url(urlBuilder.build())
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
     * Returns the default branch of a Bitbucket Server repository.
     * GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/default-branch
     */
    suspend fun getDefaultBranch(
        projectKey: String,
        repoSlug: String
    ): ApiResult<BitbucketBranch> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching default branch for $projectKey/$repoSlug")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/default-branch")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val branch = json.decodeFromString<BitbucketBranch>(body)
                            log.info("[Core:Bitbucket] Default branch for $projectKey/$repoSlug is '${branch.displayId}'")
                            ApiResult.Success(branch)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Repository $projectKey/$repoSlug not found")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching default branch", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Returns all pull requests for a Bitbucket Server repository.
     * GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests?state={state}&limit={limit}
     */
    suspend fun getAllPullRequests(
        projectKey: String,
        repoSlug: String,
        state: String = "OPEN",
        limit: Int = 100
    ): ApiResult<List<BitbucketPrResponse>> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching all $state pull requests for $projectKey/$repoSlug")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests?state=$state&limit=$limit")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<BitbucketPrListResponse>(body)
                            log.info("[Core:Bitbucket] Found ${parsed.values.size} $state PRs in $projectKey/$repoSlug")
                            ApiResult.Success(parsed.values)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Repository $projectKey/$repoSlug not found")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching pull requests", e)
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
                // branchName is added via addQueryParameter (C5 fix: prevents injection
                // when the caller supplies a branch name containing '&', '=', or spaces).
                val branchRef = "refs/heads/$branchName"
                val prUrl = "$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests"
                    .toHttpUrl().newBuilder()
                    .addQueryParameter("direction", "OUTGOING")
                    .addQueryParameter("at", branchRef)
                    .addQueryParameter("state", "OPEN")
                    .build()
                val request = Request.Builder()
                    .url(prUrl)
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
     * Fetches every default-reviewer condition configured for the repo, returning
     * the union of reviewers across all conditions (de-duplicated by username).
     *
     * GET /rest/default-reviewers/1.0/projects/{projectKey}/repos/{repoSlug}/conditions
     *
     * **Prefer [getDefaultReviewersForBranch]** when both source and target branches
     * are known — this method's union-all behaviour is the legacy shape that the
     * 2026-05-07 audit (P1 finding #6) called out. It's retained for callers that
     * genuinely want every repo-level reviewer (e.g. settings/admin previews); the
     * PR-creation path now goes through the branch-aware variant so the dialog only
     * suggests reviewers whose conditions actually apply.
     *
     * Returns empty list (wrapped in Success) when the default-reviewers plugin is
     * not installed (404) or no conditions are configured — both are legitimate
     * repo states.
     */
    suspend fun getDefaultReviewers(projectKey: String, repoSlug: String): ApiResult<List<BitbucketUser>> {
        log.info("[Core:Bitbucket] Fetching default-reviewer conditions for $projectKey/$repoSlug (union-all)")
        return when (val r = getDefaultReviewerConditions(projectKey, repoSlug)) {
            is ApiResult.Success -> {
                val unique = r.data.flatMap { c -> c.reviewers }.distinctBy { u -> u.name }
                log.info("[Core:Bitbucket] Default reviewers for $projectKey/$repoSlug: ${unique.size}")
                ApiResult.Success(unique)
            }
            is ApiResult.Error -> r
        }
    }

    /**
     * Branch-aware variant of [getDefaultReviewers]. Filters the repo's
     * default-reviewer conditions down to those whose `sourceRefMatcher` AND
     * `targetRefMatcher` both accept the requested branch pair, then returns the
     * union of reviewers across the surviving conditions.
     *
     * Source: 2026-05-07 audit P1 finding #6 (PR 6 of the write-ops fix plan).
     */
    suspend fun getDefaultReviewersForBranch(
        repo: RepoCoords,
        sourceBranch: String,
        targetBranch: String,
    ): ApiResult<List<BitbucketUser>> {
        log.info(
            "[Core:Bitbucket] Resolving default reviewers for ${repo.projectKey}/${repo.repoSlug} " +
                "source='$sourceBranch' target='$targetBranch'"
        )
        return when (val r = getDefaultReviewerConditions(repo.projectKey, repo.repoSlug)) {
            is ApiResult.Success -> {
                val matched = r.data.filter { c ->
                    c.sourceRefMatcher.matches(sourceBranch) && c.targetRefMatcher.matches(targetBranch)
                }
                val unique = matched.flatMap { c -> c.reviewers }.distinctBy { u -> u.name }
                log.info(
                    "[Core:Bitbucket] Matched ${matched.size}/${r.data.size} conditions → ${unique.size} reviewers"
                )
                ApiResult.Success(unique)
            }
            is ApiResult.Error -> r
        }
    }

    /**
     * Raw fetch of the conditions list. Internal helper shared by
     * [getDefaultReviewers] and [getDefaultReviewersForBranch] so the matcher-aware
     * filtering happens in one place.
     */
    internal suspend fun getDefaultReviewerConditions(
        projectKey: String,
        repoSlug: String,
    ): ApiResult<List<DefaultReviewerCondition>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/rest/default-reviewers/1.0/projects/$projectKey/repos/$repoSlug/conditions")
                .get()
                .header("Accept", "application/json")
                .build()
            val response = httpClient.newCall(request).execute()
            response.use {
                when (it.code) {
                    in 200..299 -> {
                        val body = it.body?.string() ?: "[]"
                        ApiResult.Success(json.decodeFromString<List<DefaultReviewerCondition>>(body))
                    }
                    401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                    // 404 → default-reviewers plugin not installed, treat as "no defaults"
                    404 -> ApiResult.Success(emptyList())
                    else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                }
            }
        } catch (e: IOException) {
            log.error("[Core:Bitbucket] Network error fetching default-reviewer conditions", e)
            ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
        }
    }

    /**
     * Searches Bitbucket Server users by filter text.
     *
     * `GET /rest/api/1.0/users?filter={filter}` — Bitbucket's `UserService.findUsersByName`
     * matches the filter **case-insensitively across `name` (username), `displayName`, and
     * `emailAddress`**, so typing "jane" finds Jane Citizen by real name. (Source: Atlassian
     * `UserService` javadoc / DC REST docs.)
     *
     * When [projectKey] and [repoSlug] are provided, the request adds
     * `permission.1=REPO_READ&permission.1.projectKey=P&permission.1.repositorySlug=R` so
     * the server filters results to users who have effective `REPO_READ` on the target repo
     * — matching the Bitbucket web UI's reviewer-picker behaviour (and honouring
     * group-expanded permissions, unlike `/repos/{r}/permissions/users` which is both
     * admin-gated and direct-grants-only).
     */
    suspend fun getUsers(
        filter: String,
        projectKey: String? = null,
        repoSlug: String? = null
    ): ApiResult<List<BitbucketUser>> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Searching users: filter='$filter' repo=$projectKey/$repoSlug")
            try {
                val encodedFilter = java.net.URLEncoder.encode(filter, "UTF-8")
                val permissionParams = if (!projectKey.isNullOrBlank() && !repoSlug.isNullOrBlank()) {
                    "&permission.1=REPO_READ" +
                        "&permission.1.projectKey=${java.net.URLEncoder.encode(projectKey, "UTF-8")}" +
                        "&permission.1.repositorySlug=${java.net.URLEncoder.encode(repoSlug, "UTF-8")}"
                } else ""
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/users?filter=$encodedFilter&limit=10$permissionParams")
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
     * Gets all pull requests in a repository (no role/user filter).
     * GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests?state={state}
     */
    suspend fun getRepoPullRequests(
        projectKey: String,
        repoSlug: String,
        state: String = "OPEN",
        start: Int = 0,
        limit: Int = 25
    ): ApiResult<BitbucketPrDetailListResponse> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching all repo PRs (state=$state, start=$start, limit=$limit) in $projectKey/$repoSlug")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests?state=$state&start=$start&limit=$limit")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<BitbucketPrDetailListResponse>(body)
                            log.info("[Core:Bitbucket] Found ${parsed.values.size} repo PRs (isLastPage=${parsed.isLastPage})")
                            ApiResult.Success(parsed)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Repository $projectKey/$repoSlug not found")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching repo PRs", e)
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
     * Encapsulated fetch-modify-write helper for Bitbucket PR mutations.
     *
     * Bitbucket DC requires every PUT on a pull request to carry the latest
     * `version` for optimistic locking. Without a fetch-and-retry guard, every
     * long-lived `PrDetailPanel` is a 409 trap: the user clicks "Add reviewer"
     * 30 seconds after the panel opens; if anyone else touched the PR in those
     * 30s, the version is stale and the write silently fails (or returns 409
     * with no retry). Four mutation paths in `:pullrequest` had this race
     * (`addReviewer`, `removeReviewer`, `updateTitle`, `merge`) — the audit
     * found them in 2026-05-07 and PR 3 of the fix plan consolidates them onto
     * this helper.
     *
     * **Behaviour:**
     *  1. GET `/pull-requests/{id}` → fresh [BitbucketPrDetail] with `version`.
     *  2. Apply [mutate] to derive the [BitbucketPrUpdateRequest]. The mutator
     *     should set `version = current.version` from the GET. (The helper does
     *     not enforce this — it would defeat the retry's "re-apply with the
     *     refetched version" loop, since a hard-coded version would survive the
     *     retry.)
     *  3. PUT the mutated request.
     *  4. On 409 Conflict, refetch and re-apply [mutate] once. On second 409,
     *     return [ErrorType.STALE_VERSION] so callers can surface
     *     "the PR was updated by someone else, refresh and try again".
     *
     * @param repo project + repo coordinates.
     * @param prId Bitbucket PR id.
     * @param mutate transform from current PR state to the update request.
     *   Suspending so callers can do further IO inside (e.g., looking up a user
     *   slug from a search endpoint when adding a reviewer).
     * @return the updated PR detail on success, or a typed error on failure.
     *   Returns [ErrorType.STALE_VERSION] only after both attempts collide.
     */
    suspend fun modifyPullRequest(
        repo: RepoCoords,
        prId: Int,
        mutate: suspend (BitbucketPrDetail) -> BitbucketPrUpdateRequest,
    ): ApiResult<BitbucketPrDetail> {
        // Attempt 1: fetch fresh, apply mutator, PUT.
        val first = fetchAndPut(repo, prId, mutate)
        if (first !is ApiResult.Error || first.type != ErrorType.STALE_VERSION) return first

        log.info("[Core:Bitbucket] modifyPullRequest #$prId hit 409 — retrying once with refetched version")
        // The cached GET that fed the first attempt is now known stale. The
        // 409 PUT does not invalidate the cache (MutationInvalidationInterceptor
        // only fires on 2xx mutations) so the second GET would otherwise serve
        // the same version-3 entry and the retry would deadlock on the same
        // 409. Force-evict the PR cache key here so the refetch hits the
        // network.
        com.workflow.orchestrator.core.http.HttpResponseCache.invalidateByPrefix(
            "/rest/api/1.0/projects/${repo.projectKey}/repos/${repo.repoSlug}/pull-requests/$prId"
        )

        // Attempt 2: refetch, re-apply mutator with the now-fresher PR state, PUT again.
        return fetchAndPut(repo, prId, mutate)
    }

    /**
     * One round-trip of the [modifyPullRequest] cycle: GET the current PR, run
     * the mutator, PUT the result. Maps 409 to [ErrorType.STALE_VERSION] so the
     * caller can decide whether to retry. All other errors propagate through
     * unchanged.
     */
    private suspend fun fetchAndPut(
        repo: RepoCoords,
        prId: Int,
        mutate: suspend (BitbucketPrDetail) -> BitbucketPrUpdateRequest,
    ): ApiResult<BitbucketPrDetail> {
        val current = when (val r = getPullRequestDetail(repo.projectKey, repo.repoSlug, prId)) {
            is ApiResult.Success -> r.data
            is ApiResult.Error -> return r
        }

        val updateRequest = mutate(current)
        val updated = updatePullRequest(repo.projectKey, repo.repoSlug, prId, updateRequest)
        // updatePullRequest currently maps 409 → ErrorType.VALIDATION_ERROR with the legacy
        // "version conflict — refresh and retry" copy. Translate that into the typed
        // STALE_VERSION result so callers get an unambiguous signal.
        if (updated is ApiResult.Error &&
            updated.type == ErrorType.VALIDATION_ERROR &&
            updated.message.contains("version conflict", ignoreCase = true)
        ) {
            return ApiResult.Error(
                ErrorType.STALE_VERSION,
                "PR #$prId was updated by someone else — refresh and try again."
            )
        }
        return updated
    }

    /**
     * Fetches a single page of PR activities.
     * GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/activities?limit=50&start={start}
     */
    private suspend fun fetchActivitiesPage(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        start: Int,
    ): ApiResult<BitbucketPrActivityResponse> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/activities?limit=50&start=$start")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<BitbucketPrActivityResponse>(body)
                            ApiResult.Success(parsed)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "PR #$prId not found in $projectKey/$repoSlug")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching PR #$prId activities (start=$start)", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Gets activity (comments, approvals, merges) for a pull request, aggregating across all pages.
     * GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/activities?limit=50&start={start}
     */
    suspend fun getPullRequestActivities(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        start: Int = 0,
    ): ApiResult<BitbucketPrActivityResponse> {
        log.info("[Core:Bitbucket] Fetching activities for PR #$prId in $projectKey/$repoSlug")
        val maxPages = 20  // safety cap: ~1000 activities at limit=50
        val aggregated = mutableListOf<BitbucketPrActivity>()
        var cursor = start
        var pages = 0
        while (pages < maxPages) {
            when (val single = fetchActivitiesPage(projectKey, repoSlug, prId, cursor)) {
                is ApiResult.Error -> return single
                is ApiResult.Success -> {
                    aggregated += single.data.values
                    if (single.data.isLastPage || single.data.nextPageStart == null) {
                        log.info("[Core:Bitbucket] Found ${aggregated.size} activities in PR #$prId")
                        return ApiResult.Success(
                            BitbucketPrActivityResponse(values = aggregated, isLastPage = true, nextPageStart = null)
                        )
                    }
                    cursor = single.data.nextPageStart
                    pages++
                }
            }
        }
        // Cap hit — return aggregated with isLastPage=false indicating truncation
        log.warn("[Core:Bitbucket] PR #$prId activities truncated at $maxPages pages (${aggregated.size} activities)")
        return ApiResult.Success(
            BitbucketPrActivityResponse(values = aggregated, isLastPage = false, nextPageStart = cursor)
        )
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
                        409 -> {
                            // Bitbucket DC distinguishes the two 409 causes via `exceptionName`
                            // in the error body. PullRequestOutOfDateException = stale version
                            // (caller should retry with refreshed version); everything else
                            // (PullRequestMergeVetoedException, etc.) is a real merge gate
                            // that retrying won't fix. Surfacing typed STALE_VERSION lets
                            // mergePullRequestWithRetry skip the string-match heuristic and
                            // gives end-users an accurate error message for veto failures.
                            val errorBody = it.body?.string() ?: ""
                            val isOutOfDate = errorBody.contains(
                                "PullRequestOutOfDateException", ignoreCase = false
                            ) || errorBody.contains("out-of-date", ignoreCase = true)
                            if (isOutOfDate) {
                                ApiResult.Error(
                                    ErrorType.STALE_VERSION,
                                    "PR #$prId version is stale — refresh and try again"
                                )
                            } else {
                                val parsedMessage = parseBitbucketErrorMessage(errorBody)
                                    ?: "Merge preconditions not met for PR #$prId"
                                ApiResult.Error(ErrorType.VALIDATION_ERROR, parsedMessage)
                            }
                        }
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
     * Stale-version-safe merge for Bitbucket DC pull requests.
     *
     * Counterpart to [modifyPullRequest] for the merge POST (the merge endpoint
     * carries `version` in the query string, not a `BitbucketPrUpdateRequest`
     * body — the same retry shape applies, so it gets its own helper rather
     * than shoe-horning a non-PUT path through the PUT-shaped lambda).
     *
     *  1. GET `/pull-requests/{id}` to read fresh `version`.
     *  2. POST `/pull-requests/{id}/merge?version={fresh}` with strategy /
     *     deleteSourceRef / commitMessage payload.
     *  3. On 409, force-evict the cached PR detail, refetch, and retry the
     *     merge POST once with the new version.
     *  4. On second 409, return [ErrorType.STALE_VERSION] so callers can
     *     surface "the PR was updated by someone else, refresh and try again".
     *
     * Audit cross-ref: `addReviewer` / `removeReviewer` / `updateTitle` use
     * [modifyPullRequest]; this is the matching helper for the merge case
     * called out in the 2026-05-07 write-ops fix plan PR 3.
     */
    suspend fun mergePullRequestWithRetry(
        repo: RepoCoords,
        prId: Int,
        strategyId: String? = null,
        deleteSourceBranch: Boolean = false,
        commitMessage: String? = null,
    ): ApiResult<BitbucketPrDetail> {
        val first = fetchAndMerge(repo, prId, strategyId, deleteSourceBranch, commitMessage)
        if (first !is ApiResult.Error || first.type != ErrorType.STALE_VERSION) return first

        log.info("[Core:Bitbucket] mergePullRequestWithRetry #$prId hit 409 — retrying once with refetched version")
        com.workflow.orchestrator.core.http.HttpResponseCache.invalidateByPrefix(
            "/rest/api/1.0/projects/${repo.projectKey}/repos/${repo.repoSlug}/pull-requests/$prId"
        )
        return fetchAndMerge(repo, prId, strategyId, deleteSourceBranch, commitMessage)
    }

    /**
     * One round-trip of the [mergePullRequestWithRetry] cycle: GET the PR for
     * its current version, POST the merge with that version, map 409 to
     * [ErrorType.STALE_VERSION].
     */
    private suspend fun fetchAndMerge(
        repo: RepoCoords,
        prId: Int,
        strategyId: String?,
        deleteSourceBranch: Boolean,
        commitMessage: String?,
    ): ApiResult<BitbucketPrDetail> {
        val current = when (val r = getPullRequestDetail(repo.projectKey, repo.repoSlug, prId)) {
            is ApiResult.Success -> r.data
            is ApiResult.Error -> return r
        }
        val merged = mergePullRequest(
            projectKey = repo.projectKey,
            repoSlug = repo.repoSlug,
            prId = prId,
            version = current.version,
            strategyId = strategyId,
            deleteSourceBranch = deleteSourceBranch,
            commitMessage = commitMessage,
        )
        // mergePullRequest now disambiguates the two 409 causes via the response body's
        // `exceptionName` and surfaces STALE_VERSION as a typed error directly. We no
        // longer string-match — a real veto returns VALIDATION_ERROR with the actual
        // Bitbucket message, and only true stale-version 409s flow back as STALE_VERSION.
        return merged
    }

    /**
     * Parses Bitbucket DC's structured error response body to extract the first
     * actionable error message. Body shape:
     * `{"errors": [{"message": "...", "exceptionName": "...", "vetoes": [...]}]}`
     *
     * Returns the joined messages of all errors, or null if the body isn't
     * parseable (caller falls back to a generic message).
     */
    private fun parseBitbucketErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val parsed = json.decodeFromString<BitbucketErrorEnvelope>(body)
            parsed.errors
                .mapNotNull { it.message?.takeIf { m -> m.isNotBlank() } }
                .takeIf { it.isNotEmpty() }
                ?.joinToString("; ")
        } catch (e: Exception) {
            null
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
     * Per-process cache of the resolved merge-strategy URL. DC 9.4 returns 404 from
     * `/repos/{r}/settings/pull-requests/git` whenever the repo doesn't have its own
     * override of the project default; we then fall back to the project URL and
     * remember which one worked so subsequent fetches skip the 404.
     *
     * Key: `"$projectKey/$repoSlug"`. Value: `true` if the repo URL responded 200 last
     * time (use repo URL); `false` if we had to fall back to the project URL.
     *
     * Source: docs/research/2026-05-07-bitbucket-recommendations.md §1.2.
     */
    private val repoSettingsResolution = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Gets available merge strategies for a repository.
     *
     * Tries `GET /rest/api/1.0/projects/{p}/repos/{r}/settings/pull-requests/git` first.
     * On 404 (repo has no per-repo override of the project default) falls back to
     * `GET /rest/api/1.0/projects/{p}/settings/pull-requests/git`. Both URLs return the
     * same `BitbucketRepoSettingsResponse` shape. The resolution is cached in-memory per
     * (projectKey, repoSlug) so subsequent calls skip the 404.
     *
     * Source: docs/research/2026-05-07-bitbucket-recommendations.md §1.2.
     */
    suspend fun getMergeStrategies(
        projectKey: String,
        repoSlug: String
    ): ApiResult<BitbucketMergeConfig> = withContext(Dispatchers.IO) {
        log.info("[Core:Bitbucket] Fetching merge strategies for $projectKey/$repoSlug")
        val cacheKey = "$projectKey/$repoSlug"
        val preferRepoUrl = repoSettingsResolution[cacheKey] ?: true
        if (preferRepoUrl) {
            val repoResult = fetchMergeStrategiesAt(
                "$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/settings/pull-requests/git",
                "$projectKey/$repoSlug repo",
            )
            if (repoResult is ApiResult.Success) {
                repoSettingsResolution[cacheKey] = true
                return@withContext repoResult
            }
            if (repoResult is ApiResult.Error && repoResult.type != ErrorType.NOT_FOUND) {
                return@withContext repoResult
            }
            log.info("[Core:Bitbucket] Repo merge-strategy URL 404'd for $projectKey/$repoSlug; falling back to project URL")
        }
        val projectResult = fetchMergeStrategiesAt(
            "$baseUrl/rest/api/1.0/projects/$projectKey/settings/pull-requests/git",
            "$projectKey project",
        )
        if (projectResult is ApiResult.Success) {
            repoSettingsResolution[cacheKey] = false
        }
        projectResult
    }

    private suspend fun fetchMergeStrategiesAt(
        url: String,
        scopeForLog: String,
    ): ApiResult<BitbucketMergeConfig> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .build()
            val response = httpClient.newCall(request).execute()
            response.use {
                when (it.code) {
                    in 200..299 -> {
                        val body = it.body?.string() ?: ""
                        val settings = json.decodeFromString<BitbucketRepoSettingsResponse>(body)
                        log.info("[Core:Bitbucket] Found ${settings.mergeConfig.strategies.size} merge strategies for $scopeForLog")
                        ApiResult.Success(settings.mergeConfig)
                    }
                    401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                    404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Merge-strategy settings not found at $scopeForLog")
                    else -> {
                        val errorBody = it.body?.string() ?: ""
                        ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}: $errorBody")
                    }
                }
            }
        } catch (e: IOException) {
            log.error("[Core:Bitbucket] Network error fetching merge strategies at $scopeForLog", e)
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
                            val raw = it.body?.string() ?: ""
                            val capped = if (raw.length > MAX_DIFF_CHARS) {
                                raw.take(MAX_DIFF_CHARS) + "\n[... diff truncated at $MAX_DIFF_CHARS chars ...]"
                            } else {
                                raw
                            }
                            log.info("[Core:Bitbucket] PR #$prId diff fetched (${raw.length} chars raw, ${capped.length} chars after cap)")
                            ApiResult.Success(capped)
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
     * Fetches a single page of PR changes.
     * GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/changes?limit=100&start={start}
     */
    private suspend fun fetchChangesPage(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        start: Int,
    ): ApiResult<BitbucketPrChangesResponse> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/changes?limit=100&start=$start")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<BitbucketPrChangesResponse>(body)
                            ApiResult.Success(parsed)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "PR #$prId not found in $projectKey/$repoSlug")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching PR #$prId changes (start=$start)", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Gets the list of changed files for a pull request, aggregating across all pages.
     * GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/changes?limit=100&start={start}
     */
    suspend fun getPullRequestChanges(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        start: Int = 0,
    ): ApiResult<BitbucketPrChangesResponse> {
        log.info("[Core:Bitbucket] Fetching changes for PR #$prId in $projectKey/$repoSlug")
        val maxPages = 20  // safety cap = 2000 files
        val aggregated = mutableListOf<BitbucketPrChange>()
        var cursor = start
        var pages = 0
        while (pages < maxPages) {
            when (val single = fetchChangesPage(projectKey, repoSlug, prId, cursor)) {
                is ApiResult.Error -> return single
                is ApiResult.Success -> {
                    aggregated += single.data.values
                    if (single.data.isLastPage || single.data.nextPageStart == null) {
                        log.info("[Core:Bitbucket] Found ${aggregated.size} changed files in PR #$prId")
                        return ApiResult.Success(
                            BitbucketPrChangesResponse(values = aggregated, isLastPage = true, nextPageStart = null)
                        )
                    }
                    cursor = single.data.nextPageStart
                    pages++
                }
            }
        }
        // Cap hit — return aggregated with isLastPage=false indicating truncation
        log.warn("[Core:Bitbucket] PR #$prId changes truncated at $maxPages pages (${aggregated.size} files)")
        return ApiResult.Success(
            BitbucketPrChangesResponse(values = aggregated, isLastPage = false, nextPageStart = cursor)
        )
    }

    /**
     * Gets comments for a pull request, derived from the activities timeline.
     *
     * On Bitbucket DC 9.4 the direct `GET /pull-requests/{id}/comments` listing rejects
     * requests that don't pass `path` or `count=true` (returns 400). The audit confirmed
     * `/activities` keeps returning 200 and already includes every COMMENTED action with
     * the full comment body, so we filter the activities timeline for `action=COMMENTED`
     * and surface the embedded comment objects.
     *
     * Source: docs/research/2026-05-07-bitbucket-recommendations.md §1.1.
     */
    suspend fun listPrComments(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        start: Int = 0,
    ): ApiResult<BitbucketPrCommentList> {
        log.info("[Core:Bitbucket] Fetching comments for PR #$prId in $projectKey/$repoSlug (via activities)")
        return when (val activities = getPullRequestActivities(projectKey, repoSlug, prId, start)) {
            is ApiResult.Error -> activities
            is ApiResult.Success -> {
                val comments = activities.data.values
                    .filter { it.action == "COMMENTED" }
                    .mapNotNull { it.comment?.toCommentResponse() }
                log.info("[Core:Bitbucket] Found ${comments.size} comments in PR #$prId (from activities)")
                ApiResult.Success(
                    BitbucketPrCommentList(
                        values = comments,
                        isLastPage = activities.data.isLastPage,
                        nextPageStart = activities.data.nextPageStart,
                    )
                )
            }
        }
    }

    /**
     * Maps the lightweight comment payload that lives on `BitbucketPrActivity.comment`
     * onto the richer [BitbucketPrCommentResponse] DTO that downstream UI/services consume.
     *
     * The activities timeline omits a few fields (`severity`, `state`, `comments` thread
     * replies, `permittedOperations`, `version`) that the comments listing previously
     * carried. Defaults match the field defaults on `BitbucketPrCommentResponse` so the
     * UI degrades gracefully — anything that needs the missing fields can re-fetch the
     * comment by id via [getPrComment].
     */
    private fun BitbucketPrComment.toCommentResponse(): BitbucketPrCommentResponse =
        BitbucketPrCommentResponse(
            id = id,
            text = text,
            author = BitbucketPrCommentAuthor(
                name = author.name,
                displayName = author.displayName,
                emailAddress = author.emailAddress,
            ),
            createdDate = createdDate,
            updatedDate = updatedDate,
            anchor = anchor?.let { a ->
                BitbucketPrCommentAnchor(
                    path = a.path,
                    srcPath = a.srcPath,
                    line = a.line.takeIf { it != 0 },
                    lineType = a.lineType.takeIf { it.isNotBlank() },
                    fileType = a.fileType.takeIf { it.isNotBlank() },
                )
            },
        )

    /**
     * Gets a single comment by ID for a pull request.
     * GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/comments/{commentId}
     */
    suspend fun getPrComment(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        commentId: Long,
    ): ApiResult<BitbucketPrCommentResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/comments/$commentId")
                .get()
                .header("Accept", "application/json")
                .build()
            val response = httpClient.newCall(request).execute()
            response.use {
                when (it.code) {
                    in 200..299 -> {
                        val body = it.body?.string() ?: ""
                        ApiResult.Success(json.decodeFromString<BitbucketPrCommentResponse>(body))
                    }
                    401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                    404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Comment $commentId not found on PR #$prId in $projectKey/$repoSlug")
                    else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                }
            }
        } catch (e: IOException) {
            log.error("[Core:Bitbucket] Network error fetching comment $commentId on PR #$prId", e)
            ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
        }
    }

    /**
     * Edits an existing comment on a pull request.
     * PUT /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/comments/{commentId}
     * Requires the current comment version in the request body; returns 409 if the comment was
     * modified since the caller last fetched it (optimistic concurrency / stale version).
     */
    suspend fun editPrComment(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        commentId: Long,
        text: String,
        expectedVersion: Int,
    ): ApiResult<BitbucketPrCommentResponse> = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(EditCommentRequest(text = text, version = expectedVersion))
        val request = Request.Builder()
            .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/comments/$commentId")
            .put(payload.toRequestBody("application/json".toMediaType()))
            .addHeader("Accept", "application/json")
            .build()
        runCatching {
            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    409 -> ApiResult.Error(
                        ErrorType.VALIDATION_ERROR,
                        "STALE_VERSION: comment $commentId was modified by another user; re-fetch and retry"
                    )
                    in 200..299 -> {
                        val body = response.body?.string()
                            ?: return@use ApiResult.Error(ErrorType.PARSE_ERROR, "empty response body")
                        ApiResult.Success(json.decodeFromString<BitbucketPrCommentResponse>(body))
                    }
                    401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                    else -> ApiResult.Error(
                        ErrorType.SERVER_ERROR,
                        "HTTP ${response.code}: ${response.message}"
                    )
                }
            }
        }.getOrElse { e ->
            log.error("[Core:Bitbucket] Network error editing comment $commentId on PR #$prId", e)
            ApiResult.Error(ErrorType.NETWORK_ERROR, "editPrComment failed: ${e.message}", e)
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
        limit: Int = 50,
        start: Int = 0
    ): ApiResult<BitbucketCommitListResponse> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching commits for PR #$prId in $projectKey/$repoSlug (start=$start)")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/commits?limit=$limit&start=$start")
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
     *
     * The optional [diffType] / [fromHash] / [toHash] arguments pin the comment to
     * a specific diff range so it doesn't float when the PR receives new commits
     * after the comment is anchored. The recommended use for AI / batch reviews
     * is `diffType="COMMIT"` with `toHash` set to the PR's `toRef.latestCommit`
     * at review time (audit finding #7, PR 6 of the 2026-05-07 write-ops fix
     * plan). Callers that don't pass any of the pinning fields keep the legacy
     * server-default `EFFECTIVE` behaviour.
     */
    suspend fun addInlineComment(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        filePath: String,
        lineNumber: Int,
        lineType: String,
        text: String,
        srcPath: String? = null,
        diffType: String? = null,
        fromHash: String? = null,
        toHash: String? = null,
    ): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            log.info(
                "[Core:Bitbucket] Adding inline comment to PR #$prId at $filePath:$lineNumber " +
                    "($lineType) diffType=$diffType toHash=${toHash?.take(8)}"
            )
            try {
                val payload = json.encodeToString(
                    InlineCommentRequest(
                        text = text,
                        anchor = InlineCommentAnchor(
                            path = filePath,
                            line = lineNumber,
                            lineType = lineType,
                            fileType = InlineCommentAnchor.deriveFileType(lineType),
                            srcPath = srcPath,
                            diffType = diffType,
                            fromHash = fromHash,
                            toHash = toHash,
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
                val payload = json.encodeToString(ReviewerStatusRequest(status = status, approved = (status == "APPROVED")))
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

    /**
     * Deletes a comment from a pull request.
     * DELETE /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/comments/{commentId}?version={expectedVersion}
     * Returns 409 if the comment was modified since the caller last fetched it (stale version).
     */
    suspend fun deletePrComment(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        commentId: Long,
        expectedVersion: Int,
    ): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/comments/$commentId?version=$expectedVersion")
            .delete()
            .build()
        runCatching {
            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    409 -> ApiResult.Error(ErrorType.VALIDATION_ERROR, "STALE_VERSION: comment was modified by another user; re-fetch and retry")
                    in 200..299 -> ApiResult.Success(Unit)
                    else -> ApiResult.Error(ErrorType.SERVER_ERROR, "HTTP ${response.code}: ${response.message}")
                }
            }
        }.getOrElse { e ->
            log.error("[Core:Bitbucket] Network error deleting comment $commentId on PR #$prId", e)
            ApiResult.Error(ErrorType.NETWORK_ERROR, "deletePrComment failed: ${e.message}", e)
        }
    }

    /**
     * Resolves a comment thread on a pull request (sets state to RESOLVED).
     * PUT /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/comments/{commentId}
     */
    suspend fun resolvePrComment(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        commentId: Long,
    ): ApiResult<BitbucketPrCommentResponse> = setCommentState(projectKey, repoSlug, prId, commentId, "RESOLVED")

    /**
     * Reopens a resolved comment thread on a pull request (sets state back to OPEN).
     * PUT /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/comments/{commentId}
     */
    suspend fun reopenPrComment(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        commentId: Long,
    ): ApiResult<BitbucketPrCommentResponse> = setCommentState(projectKey, repoSlug, prId, commentId, "OPEN")

    private suspend fun setCommentState(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        commentId: Long,
        state: String,
    ): ApiResult<BitbucketPrCommentResponse> = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(ResolveCommentRequest(state = state))
        val request = Request.Builder()
            .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/comments/$commentId")
            .put(payload.toRequestBody("application/json".toMediaType()))
            .addHeader("Accept", "application/json")
            .build()
        runCatching {
            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    409 -> ApiResult.Error(ErrorType.VALIDATION_ERROR, "STALE_VERSION: comment was modified by another user; re-fetch and retry")
                    in 200..299 -> {
                        val body = response.body?.string()
                            ?: return@use ApiResult.Error(ErrorType.PARSE_ERROR, "empty response body")
                        ApiResult.Success(json.decodeFromString<BitbucketPrCommentResponse>(body))
                    }
                    else -> ApiResult.Error(ErrorType.SERVER_ERROR, "HTTP ${response.code}: ${response.message}")
                }
            }
        }.getOrElse { e ->
            log.error("[Core:Bitbucket] Network error setting state='$state' on comment $commentId of PR #$prId", e)
            ApiResult.Error(ErrorType.NETWORK_ERROR, "setCommentState failed: ${e.message}", e)
        }
    }

    // ============================================================================
    // Audit-driven additions (2026-05-07) — see
    // docs/research/2026-05-07-bitbucket-recommendations.md.
    // ============================================================================

    /**
     * Fetches PRs across every repo the current user has access to in a single call.
     *
     * Replaces the per-repo iteration in `PrListService.refresh()` (R-SWAP-1 / R-SWAP-2):
     * for users with N repos, the dashboard endpoint collapses N round-trips into 1.
     * The returned list carries `toRef.repository.{slug,project.key}` so callers can
     * still bucket PRs per repo if needed.
     *
     * @param role  AUTHOR, REVIEWER, or PARTICIPANT
     * @param state OPEN, MERGED, or DECLINED
     * @param limit max results to fetch (DC defaults to 25, 100 hard max)
     *
     * Source: docs/research/2026-05-07-bitbucket-recommendations.md §3 R-SWAP-1, R-SWAP-2.
     */
    suspend fun getDashboardPullRequests(
        role: String,
        state: String = "OPEN",
        limit: Int = 25,
        start: Int = 0,
    ): ApiResult<BitbucketPrDetailListResponse> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching dashboard PRs (role=$role, state=$state, start=$start, limit=$limit)")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/dashboard/pull-requests?role=$role&state=$state&start=$start&limit=$limit")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(request).execute().use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<BitbucketPrDetailListResponse>(body)
                            log.info("[Core:Bitbucket] Dashboard returned ${parsed.values.size} $role PRs (isLastPage=${parsed.isLastPage})")
                            ApiResult.Success(parsed)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching dashboard PRs", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Lists blocker-severity comments for a PR.
     *
     * `GET /pull-requests/{prId}/blocker-comments` — when `count=true` only the size is
     * populated (cheap badge counter); without `count=true` the `values` list is filled.
     * Replaces the client-side `comments.filter { severity == "BLOCKER" }` loop the
     * plugin used to do over the full comments listing.
     *
     * Source: docs/research/2026-05-07-bitbucket-recommendations.md §3 R-SWAP-4.
     */
    suspend fun getBlockerComments(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        countOnly: Boolean = false,
    ): ApiResult<BitbucketBlockerCommentsResponse> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching blocker comments for PR #$prId (countOnly=$countOnly)")
            try {
                val countParam = if (countOnly) "?count=true" else ""
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/blocker-comments$countParam")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(request).execute().use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<BitbucketBlockerCommentsResponse>(body)
                            log.info("[Core:Bitbucket] PR #$prId blocker comments: size=${parsed.size}, values=${parsed.values.size}")
                            ApiResult.Success(parsed)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "PR #$prId not found in $projectKey/$repoSlug")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching blocker comments for PR #$prId", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Fetches the full list of participants on a PR with `state` and `lastReviewedCommit`
     * per reviewer. The dedicated endpoint is richer than parsing the embedded
     * `reviewers` array on `getPullRequestDetail()`.
     *
     * Source: docs/research/2026-05-07-bitbucket-recommendations.md §3 R-SWAP-5.
     */
    suspend fun getPullRequestParticipants(
        projectKey: String,
        repoSlug: String,
        prId: Int,
    ): ApiResult<BitbucketParticipantsResponse> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching participants for PR #$prId in $projectKey/$repoSlug")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/participants?limit=100")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(request).execute().use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<BitbucketParticipantsResponse>(body)
                            log.info("[Core:Bitbucket] PR #$prId has ${parsed.values.size} participants")
                            ApiResult.Success(parsed)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "PR #$prId not found in $projectKey/$repoSlug")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching participants for PR #$prId", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Reverse lookup: given a commit SHA, return the PRs containing that commit.
     *
     * Powers the Bamboo bridge — when a build fails for commit X, the listener calls
     * this to find the affected PRs and notify the authors.
     *
     * Source: docs/research/2026-05-07-bitbucket-recommendations.md §2 B1, §4 R-ADD-5.
     */
    suspend fun getCommitPullRequests(
        projectKey: String,
        repoSlug: String,
        sha: String,
        limit: Int = 25,
    ): ApiResult<BitbucketCommitPrsResponse> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Reverse lookup PRs for commit $sha in $projectKey/$repoSlug")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/commits/$sha/pull-requests?limit=$limit")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(request).execute().use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<BitbucketCommitPrsResponse>(body)
                            log.info("[Core:Bitbucket] Commit $sha is in ${parsed.values.size} PR(s)")
                            ApiResult.Success(parsed)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Commit $sha not found in $projectKey/$repoSlug")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error reverse-looking-up PRs for commit $sha", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Cheap aggregate build counter for a commit: `{successful, failed, inProgress}`.
     *
     * Source: docs/research/2026-05-07-bitbucket-recommendations.md §2 B6, §4 R-ADD-12.
     */
    suspend fun getCommitBuildStats(sha: String): ApiResult<BitbucketBuildStatsResponse> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching build stats for commit $sha")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/build-status/1.0/commits/stats/$sha")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(request).execute().use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<BitbucketBuildStatsResponse>(body)
                            log.info("[Core:Bitbucket] Commit $sha build stats: success=${parsed.successful} fail=${parsed.failed} inProgress=${parsed.inProgress}")
                            ApiResult.Success(parsed)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        404 -> ApiResult.Error(ErrorType.NOT_FOUND, "No build stats for commit $sha")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching build stats for $sha", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Fetches Jira issues linked to a PR via Bitbucket's Jira-link plugin.
     *
     * Replaces the three regex-based extraction sites in the plugin (PR-title regex,
     * branch-name regex, commit-message regex) where keys were scanned for
     * `[A-Z]+-[0-9]+`. The Atlassian Jira link plugin already extracts AND validates
     * keys against Jira before returning them, so this is more accurate than regex.
     *
     * Returns an empty list (wrapped in Success) if the Jira-link plugin is not
     * installed or no keys were detected — both are legitimate states.
     *
     * Source: docs/research/2026-05-07-bitbucket-recommendations.md §4 R-ADD-11.
     */
    suspend fun getLinkedJiraIssues(
        projectKey: String,
        repoSlug: String,
        prId: Int,
    ): ApiResult<List<BitbucketJiraIssueRef>> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching linked Jira issues for PR #$prId in $projectKey/$repoSlug")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/jira/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/issues")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(request).execute().use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<List<BitbucketJiraIssueRef>>(body)
                            log.info("[Core:Bitbucket] PR #$prId has ${parsed.size} linked Jira issue(s)")
                            ApiResult.Success(parsed)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        // 404 = Jira-link plugin not installed; treat as empty list, not a failure.
                        404 -> ApiResult.Success(emptyList())
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching linked Jira issues for PR #$prId", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

    /**
     * Fetches required-builds conditions for a repo (per-branch merge gating rules).
     *
     * Uses the canonical `/rest/required-builds/latest/` base — DC 9.4 returns 404 from
     * the v0 path under `/rest/api/1.0/`, per the audit. Callers cross-reference these
     * `buildParentKeys` against the build statuses on the PR's source-tip commit to
     * decide which required builds have passed.
     *
     * Source: docs/research/2026-05-07-bitbucket-recommendations.md §4 R-ADD-15.
     */
    suspend fun getRequiredBuilds(
        projectKey: String,
        repoSlug: String,
    ): ApiResult<BitbucketRequiredBuildsResponse> =
        withContext(Dispatchers.IO) {
            log.info("[Core:Bitbucket] Fetching required-builds conditions for $projectKey/$repoSlug")
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/required-builds/latest/projects/$projectKey/repos/$repoSlug/conditions")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(request).execute().use {
                    when (it.code) {
                        in 200..299 -> {
                            val body = it.body?.string() ?: ""
                            val parsed = json.decodeFromString<BitbucketRequiredBuildsResponse>(body)
                            log.info("[Core:Bitbucket] $projectKey/$repoSlug has ${parsed.values.size} required-builds condition(s)")
                            ApiResult.Success(parsed)
                        }
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bitbucket token")
                        // 404 = required-builds plugin not installed; treat as empty list.
                        404 -> ApiResult.Success(BitbucketRequiredBuildsResponse(values = emptyList(), size = 0, isLastPage = true))
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.error("[Core:Bitbucket] Network error fetching required-builds for $projectKey/$repoSlug", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bitbucket: ${e.message}", e)
            }
        }

}
