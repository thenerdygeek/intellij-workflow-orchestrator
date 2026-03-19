package com.workflow.orchestrator.pullrequest.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.model.bitbucket.PullRequestData
import com.workflow.orchestrator.core.services.BitbucketService
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.PluginSettings

/**
 * Unified Bitbucket service implementation used by both UI panels and AI agent.
 *
 * Wraps the existing [BitbucketBranchClient] (in :core) and maps its responses
 * to shared domain models ([PullRequestData]) with LLM-optimized text summaries.
 */
@Service(Service.Level.PROJECT)
class BitbucketServiceImpl(private val project: Project) : BitbucketService {

    private val log = Logger.getInstance(BitbucketServiceImpl::class.java)
    private val credentialStore = CredentialStore()
    private val settings get() = PluginSettings.getInstance(project)

    @Volatile private var cachedClient: BitbucketBranchClient? = null
    @Volatile private var cachedBaseUrl: String? = null

    private val client: BitbucketBranchClient?
        get() {
            val url = ConnectionSettings.getInstance().state.bitbucketUrl.trimEnd('/')
            if (url.isBlank()) return null
            if (url != cachedBaseUrl || cachedClient == null) {
                cachedBaseUrl = url
                cachedClient = BitbucketBranchClient(
                    baseUrl = url,
                    tokenProvider = { credentialStore.getToken(ServiceType.BITBUCKET) }
                )
            }
            return cachedClient
        }

    override suspend fun createPullRequest(
        title: String,
        description: String,
        fromBranch: String,
        toBranch: String
    ): ToolResult<PullRequestData> {
        val api = client ?: return ToolResult(
            data = PullRequestData(
                id = 0, title = "", state = "ERROR",
                fromBranch = fromBranch, toBranch = toBranch,
                link = "", authorName = null
            ),
            summary = "Bitbucket not configured. Cannot create pull request.",
            isError = true,
            hint = "Set up Bitbucket connection in Settings > Tools > Workflow Orchestrator > General."
        )

        val projectKey = settings.state.bitbucketProjectKey.orEmpty()
        val repoSlug = settings.state.bitbucketRepoSlug.orEmpty()
        if (projectKey.isBlank() || repoSlug.isBlank()) {
            return ToolResult(
                data = PullRequestData(
                    id = 0, title = title, state = "ERROR",
                    fromBranch = fromBranch, toBranch = toBranch,
                    link = "", authorName = null
                ),
                summary = "Bitbucket project/repo not configured. Cannot create PR.",
                isError = true,
                hint = "Set Bitbucket project key and repo slug in Settings."
            )
        }

        return when (val result = api.createPullRequest(
            projectKey = projectKey,
            repoSlug = repoSlug,
            title = title,
            description = description,
            fromBranch = fromBranch,
            toBranch = toBranch
        )) {
            is ApiResult.Success -> {
                val pr = result.data
                val link = pr.links.self.firstOrNull()?.href ?: "${cachedBaseUrl}/projects/$projectKey/repos/$repoSlug/pull-requests/${pr.id}"
                val data = PullRequestData(
                    id = pr.id,
                    title = pr.title,
                    state = pr.state,
                    fromBranch = pr.fromRef?.displayId ?: fromBranch,
                    toBranch = pr.toRef?.displayId ?: toBranch,
                    link = link,
                    authorName = null
                )

                ToolResult.success(
                    data = data,
                    summary = buildString {
                        append("PR #${data.id} created: ${data.title}")
                        append("\n${data.fromBranch} -> ${data.toBranch}")
                        append("\nLink: ${data.link}")
                    }
                )
            }
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to create PR: ${result.message}")
                ToolResult(
                    data = PullRequestData(
                        id = 0, title = title, state = "ERROR",
                        fromBranch = fromBranch, toBranch = toBranch,
                        link = "", authorName = null
                    ),
                    summary = "Error creating pull request: ${result.message}",
                    isError = true,
                    hint = when (result.type) {
                        com.workflow.orchestrator.core.model.ErrorType.AUTH_FAILED ->
                            "Check your Bitbucket token in Settings."
                        com.workflow.orchestrator.core.model.ErrorType.FORBIDDEN ->
                            "You may not have permission to create PRs in this repo."
                        com.workflow.orchestrator.core.model.ErrorType.NOT_FOUND ->
                            "Verify project key and repo slug are correct."
                        else -> "Check Bitbucket connection in Settings."
                    }
                )
            }
        }
    }

    override suspend fun testConnection(): ToolResult<Unit> {
        val api = client ?: return ToolResult(
            data = Unit,
            summary = "Bitbucket not configured.",
            isError = true,
            hint = "Set Bitbucket URL and token in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getProjects()) {
            is ApiResult.Success -> {
                ToolResult.success(Unit, "Bitbucket connection successful. Found ${result.data.size} projects.")
            }
            is ApiResult.Error -> {
                ToolResult(
                    data = Unit,
                    summary = "Bitbucket connection failed: ${result.message}",
                    isError = true,
                    hint = "Check URL and token in Settings."
                )
            }
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): BitbucketServiceImpl =
            project.getService(BitbucketService::class.java) as BitbucketServiceImpl
    }
}
