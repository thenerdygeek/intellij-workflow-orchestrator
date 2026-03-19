package com.workflow.orchestrator.jira.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.model.jira.JiraCommentData
import com.workflow.orchestrator.core.model.jira.JiraTicketData
import com.workflow.orchestrator.core.model.jira.JiraTransitionData
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.api.JiraApiClient

/**
 * Unified Jira service implementation used by both UI panels and AI agent.
 *
 * Wraps the existing [JiraApiClient] and maps its responses to shared
 * domain models ([JiraTicketData]) with LLM-optimized text summaries.
 */
@Service(Service.Level.PROJECT)
class JiraServiceImpl(private val project: Project) : JiraService {

    private val log = Logger.getInstance(JiraServiceImpl::class.java)
    private val credentialStore = CredentialStore()
    private val settings get() = PluginSettings.getInstance(project)

    @Volatile private var cachedClient: JiraApiClient? = null
    @Volatile private var cachedBaseUrl: String? = null

    private val client: JiraApiClient?
        get() {
            val url = settings.connections.jiraUrl.orEmpty().trimEnd('/')
            if (url.isBlank()) return null
            if (url != cachedBaseUrl || cachedClient == null) {
                cachedBaseUrl = url
                cachedClient = JiraApiClient(
                    baseUrl = url,
                    tokenProvider = { credentialStore.getToken(ServiceType.JIRA) }
                )
            }
            return cachedClient
        }

    override suspend fun getTicket(key: String): ToolResult<JiraTicketData> {
        val api = client ?: return ToolResult(
            data = JiraTicketData(
                key = key, summary = "", status = "ERROR",
                assignee = null, type = "", priority = null,
                description = null
            ),
            summary = "Jira not configured. Cannot fetch $key.",
            isError = true,
            hint = "Set up Jira connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getIssue(key)) {
            is ApiResult.Success -> {
                val issue = result.data
                val fields = issue.fields
                val data = JiraTicketData(
                    key = issue.key,
                    summary = fields.summary,
                    status = fields.status.name,
                    assignee = fields.assignee?.displayName,
                    type = fields.issuetype?.name ?: "Unknown",
                    priority = fields.priority?.name,
                    description = fields.description?.take(500),
                    labels = fields.labels
                )
                ToolResult.success(
                    data = data,
                    summary = buildString {
                        append("${data.key}: ${data.summary}")
                        append("\nStatus: ${data.status} | Type: ${data.type} | Assignee: ${data.assignee ?: "Unassigned"}")
                        if (data.priority != null) append(" | Priority: ${data.priority}")
                        if (data.labels.isNotEmpty()) append("\nLabels: ${data.labels.joinToString(", ")}")
                    }
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch $key: ${result.message}")
                ToolResult(
                    data = JiraTicketData(
                        key = key, summary = "", status = "ERROR",
                        assignee = null, type = "", priority = null,
                        description = null
                    ),
                    summary = "Error fetching $key: ${result.message}",
                    isError = true,
                    hint = when (result.type) {
                        com.workflow.orchestrator.core.model.ErrorType.AUTH_FAILED ->
                            "Check your Jira token in Settings."
                        com.workflow.orchestrator.core.model.ErrorType.NOT_FOUND ->
                            "Verify the ticket key is correct."
                        else -> "Check Jira connection in Settings."
                    }
                )
            }
        }
    }

    override suspend fun getTransitions(key: String): ToolResult<List<JiraTransitionData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured. Cannot fetch transitions for $key.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.getTransitions(key)) {
            is ApiResult.Success -> {
                val transitions = result.data.map { t ->
                    JiraTransitionData(
                        id = t.id,
                        name = t.name,
                        toStatus = t.to.name
                    )
                }
                val listing = transitions.joinToString(", ") { "${it.name} (id=${it.id})" }
                ToolResult.success(
                    data = transitions,
                    summary = "Transitions for $key: $listing"
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to get transitions for $key: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching transitions for $key: ${result.message}",
                    isError = true,
                    hint = "Use getTicket first to verify the ticket exists."
                )
            }
        }
    }

    override suspend fun transition(
        key: String,
        transitionId: String,
        fields: Map<String, Any>?,
        comment: String?
    ): ToolResult<Unit> {
        val api = client ?: return ToolResult(
            data = Unit,
            summary = "Jira not configured. Cannot transition $key.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.transitionIssue(key, transitionId, fields = fields, comment = comment)) {
            is ApiResult.Success -> {
                ToolResult.success(
                    data = Unit,
                    summary = "Transitioned $key with transition ID $transitionId."
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to transition $key: ${result.message}")
                ToolResult(
                    data = Unit,
                    summary = "Error transitioning $key: ${result.message}",
                    isError = true,
                    hint = "Use getTransitions to see available transitions for this ticket."
                )
            }
        }
    }

    override suspend fun addComment(key: String, body: String): ToolResult<Unit> {
        val api = client ?: return ToolResult(
            data = Unit,
            summary = "Jira not configured. Cannot add comment to $key.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.addComment(key, body)) {
            is ApiResult.Success -> {
                ToolResult.success(
                    data = Unit,
                    summary = "Comment added to $key."
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to add comment to $key: ${result.message}")
                ToolResult(
                    data = Unit,
                    summary = "Error adding comment to $key: ${result.message}",
                    isError = true,
                    hint = when (result.type) {
                        com.workflow.orchestrator.core.model.ErrorType.AUTH_FAILED ->
                            "Check your Jira token in Settings."
                        com.workflow.orchestrator.core.model.ErrorType.NOT_FOUND ->
                            "Verify the ticket key is correct."
                        else -> "Check Jira connection in Settings."
                    }
                )
            }
        }
    }

    override suspend fun logWork(key: String, timeSpent: String, comment: String?): ToolResult<Unit> {
        val api = client ?: return ToolResult(
            data = Unit,
            summary = "Jira not configured. Cannot log work on $key.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.postWorklog(key, timeSpent, comment)) {
            is ApiResult.Success -> {
                ToolResult.success(
                    data = Unit,
                    summary = "Logged $timeSpent on $key.${if (comment != null) " Comment: $comment" else ""}"
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to log work on $key: ${result.message}")
                ToolResult(
                    data = Unit,
                    summary = "Error logging work on $key: ${result.message}",
                    isError = true,
                    hint = "Verify the time format (e.g., '2h 30m', '1d')."
                )
            }
        }
    }

    override suspend fun getComments(key: String): ToolResult<List<JiraCommentData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured. Cannot fetch comments for $key.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.getComments(key)) {
            is ApiResult.Success -> {
                val comments = result.data.map { c ->
                    JiraCommentData(
                        id = c.id,
                        author = c.author?.displayName ?: "Unknown",
                        body = c.body,
                        created = c.created
                    )
                }
                ToolResult.success(
                    data = comments,
                    summary = "${comments.size} comment(s) on $key"
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch comments for $key: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching comments for $key: ${result.message}",
                    isError = true,
                    hint = "Check Jira connection in Settings."
                )
            }
        }
    }

    override suspend fun testConnection(): ToolResult<Unit> {
        val api = client ?: return ToolResult(
            data = Unit,
            summary = "Jira not configured.",
            isError = true,
            hint = "Set Jira URL and token in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getCurrentUser()) {
            is ApiResult.Success -> {
                ToolResult.success(Unit, "Jira connection successful.")
            }
            is ApiResult.Error -> {
                ToolResult(
                    data = Unit,
                    summary = "Jira connection failed: ${result.message}",
                    isError = true,
                    hint = "Check URL and token in Settings."
                )
            }
        }
    }

    /**
     * Provides the underlying [JiraApiClient] for modules that need direct API access
     * (e.g., SprintService, BranchingService). Returns null if Jira is not configured.
     *
     * Prefer using the service-level methods (getTicket, addComment, etc.) when possible.
     * This avoids duplicate client construction and ensures consistent auth handling.
     */
    fun getApiClient(): JiraApiClient? = client

    companion object {
        @JvmStatic
        fun getInstance(project: Project): JiraServiceImpl =
            project.getService(JiraService::class.java) as JiraServiceImpl
    }
}
