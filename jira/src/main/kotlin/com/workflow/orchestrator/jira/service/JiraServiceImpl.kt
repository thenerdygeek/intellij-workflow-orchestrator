package com.workflow.orchestrator.jira.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.model.jira.AttachmentContentData
import com.workflow.orchestrator.core.model.jira.BoardData
import com.workflow.orchestrator.core.model.jira.CommentVisibility
import com.workflow.orchestrator.core.model.jira.DevStatusBranchData
import com.workflow.orchestrator.core.model.jira.DevStatusBuildData
import com.workflow.orchestrator.core.model.jira.DevStatusBundle
import com.workflow.orchestrator.core.model.jira.DevStatusCommitData
import com.workflow.orchestrator.core.model.jira.DevStatusDeploymentData
import com.workflow.orchestrator.core.model.jira.FilterData
import com.workflow.orchestrator.core.model.jira.GroupOption
import com.workflow.orchestrator.core.model.jira.IssueSuggestion
import com.workflow.orchestrator.core.model.jira.JiraBoardSummary
import com.workflow.orchestrator.core.model.jira.DevStatusPrData
import com.workflow.orchestrator.core.model.jira.DevStatusReviewData
import com.workflow.orchestrator.core.model.jira.JiraCommentData
import com.workflow.orchestrator.core.model.jira.JiraAttachmentData
import com.workflow.orchestrator.core.model.jira.JiraFieldData
import com.workflow.orchestrator.core.model.jira.JiraLinkedIssueRef
import com.workflow.orchestrator.core.model.jira.JiraSubtaskRef
import com.workflow.orchestrator.core.model.jira.JiraTicketData
import com.workflow.orchestrator.core.model.jira.FieldValue
import com.workflow.orchestrator.core.model.jira.MyPermissionsData
import com.workflow.orchestrator.core.model.jira.MyselfData
import com.workflow.orchestrator.core.model.jira.PermissionFlag
import com.workflow.orchestrator.core.model.jira.RemoteLinkData
import com.workflow.orchestrator.core.model.jira.RoleOption
import com.workflow.orchestrator.core.model.jira.TicketHistoryEntry
import com.workflow.orchestrator.core.model.jira.TransitionError
import com.workflow.orchestrator.core.model.jira.TransitionInput
import com.workflow.orchestrator.core.model.jira.TransitionMeta
import com.workflow.orchestrator.core.model.jira.TransitionOutcome
import com.workflow.orchestrator.core.model.jira.SprintData
import com.workflow.orchestrator.core.model.jira.StartWorkResultData
import com.workflow.orchestrator.core.model.jira.VisibilityOptions
import com.workflow.orchestrator.core.model.jira.VisibilityType
import com.workflow.orchestrator.core.model.jira.WatcherUser
import com.workflow.orchestrator.core.model.jira.WatchersData
import com.workflow.orchestrator.core.model.jira.WorklogData
import com.workflow.orchestrator.core.model.jira.WorklogEstimateAdjustment
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.services.SessionDownloadDir
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.services.jira.TicketTransitionService
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.api.JiraApiClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * Test seam: when non-null, [client] returns this instance instead of building one
     * from [PluginSettings] + [CredentialStore]. Mirrors [JiraSearchServiceImpl.testClient].
     */
    internal var testClient: JiraApiClient? = null

    /**
     * Test seam: when non-null, [ticketTransitionService] returns this instance instead
     * of looking it up from the project's service container. Used by [transition] and
     * [startWork] which delegate to [TicketTransitionService] for required-field preflight.
     */
    internal var testTicketTransitionService: TicketTransitionService? = null

    private val ticketTransitionService: TicketTransitionService?
        get() = testTicketTransitionService
            ?: project.getService(TicketTransitionService::class.java)

    @Volatile private var cachedClient: JiraApiClient? = null
    @Volatile private var cachedBaseUrl: String? = null

    private val client: JiraApiClient?
        get() {
            testClient?.let { return it }
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
                assignee = null, reporter = null, type = "", priority = null,
                description = null
            ),
            summary = "Jira not configured. Cannot fetch $key.",
            isError = true,
            hint = "Set up Jira connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return coroutineScope {
            val issueDeferred = async { api.getIssue(key) }
            val transitionsDeferred = async { api.getTransitions(key) }

            val issueResult = issueDeferred.await()
            val transitionsResult = transitionsDeferred.await()

            when (issueResult) {
                is ApiResult.Success -> {
                    val issue = issueResult.data
                    val fields = issue.fields
                    val attachments = fields.attachment.map { att ->
                        JiraAttachmentData(
                            id = att.id,
                            filename = att.filename,
                            mimeType = att.mimeType,
                            sizeBytes = att.size
                        )
                    }
                    val subtasks = fields.subtasks.map { st ->
                        JiraSubtaskRef(
                            key = st.key,
                            summary = st.fields.summary,
                            status = st.fields.status.name
                        )
                    }
                    val linkedIssues = fields.issuelinks.mapNotNull { link ->
                        val linked = link.inwardIssue ?: link.outwardIssue ?: return@mapNotNull null
                        val relationship = if (link.inwardIssue != null) link.type.inward else link.type.outward
                        JiraLinkedIssueRef(
                            key = linked.key,
                            summary = linked.fields.summary,
                            status = linked.fields.status.name,
                            relationship = relationship
                        )
                    }
                    val transitions = when (transitionsResult) {
                        is ApiResult.Success -> transitionsResult.data
                        else -> emptyList()
                    }

                    // Extract mentioned ticket keys from summary, description, and comments
                    val mentionedTickets = extractMentionedTickets(
                        selfKey = issue.key,
                        summary = fields.summary,
                        description = fields.description,
                        comments = fields.comment?.comments?.map { it.body } ?: emptyList(),
                        subtaskKeys = subtasks.map { it.key }.toSet(),
                        linkedKeys = linkedIssues.map { it.key }.toSet()
                    )

                    val data = JiraTicketData(
                        key = issue.key,
                        summary = fields.summary,
                        status = fields.status.name,
                        assignee = fields.assignee?.displayName,
                        reporter = fields.reporter?.displayName,
                        type = fields.issuetype?.name ?: "Unknown",
                        priority = fields.priority?.name,
                        description = fields.description,
                        labels = fields.labels,
                        created = fields.created,
                        updated = fields.updated,
                        sprintName = fields.sprint?.name,
                        sprintState = fields.sprint?.state,
                        closedSprints = fields.closedSprints.map { "${it.name} (${it.state})" },
                        epicKey = fields.parent?.key,
                        epicSummary = fields.parent?.fields?.summary,
                        originalEstimate = fields.timetracking?.originalEstimate,
                        remainingEstimate = fields.timetracking?.remainingEstimate,
                        timeSpent = fields.timetracking?.timeSpent,
                        commentCount = fields.comment?.total ?: 0,
                        attachmentCount = fields.attachment.size,
                        transitions = transitions,
                        attachments = attachments,
                        subtasks = subtasks,
                        linkedIssues = linkedIssues,
                        mentionedTickets = mentionedTickets
                    )
                    ToolResult.success(
                        data = data,
                        summary = "${data.key} [${data.status}] ${data.summary}"
                    )
                }
                is ApiResult.Error -> {
                    log.warn("[JiraService] Failed to fetch $key: ${issueResult.message}")
                    ToolResult(
                        data = JiraTicketData(
                            key = key, summary = "", status = "ERROR",
                            assignee = null, reporter = null, type = "", priority = null,
                            description = null
                        ),
                        summary = "Failed to fetch $key: ${issueResult.message}",
                        isError = true,
                        hint = when (issueResult.type) {
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
    }

    override suspend fun getTransitions(key: String): ToolResult<List<TransitionMeta>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured. Cannot fetch transitions for $key.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.getTransitions(key)) {
            is ApiResult.Success -> {
                val transitions = result.data
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
        if (client == null) {
            return ToolResult(
                data = Unit,
                summary = "Jira not configured. Cannot transition $key.",
                isError = true,
                hint = "Set up Jira connection in Settings."
            )
        }

        // Delegate to TicketTransitionService — the canonical write path. It runs the
        // required-field preflight (expand=transitions.fields), invalidates the cache,
        // and emits TicketTransitioned. Bypassing it here was a P1 audit finding.
        val transitionSvc = ticketTransitionService ?: return ToolResult(
            data = Unit,
            summary = "Internal error transitioning $key: TicketTransitionService unavailable.",
            isError = true,
            hint = "This is a configuration bug — restart the IDE and report it."
        )

        val fieldValues = fields?.mapValues { (_, v) -> anyToFieldValue(v) } ?: emptyMap()
        val input = TransitionInput(transitionId, fieldValues, comment)
        val result = transitionSvc.executeTransition(key, input)
        return if (result.isError) {
            val payload = result.payload
            val hint = when (payload) {
                is TransitionError.MissingFields ->
                    "Required fields not supplied: ${payload.payload.fields.joinToString(", ") { it.name }}. " +
                        "Use the Sprint-tab transition dialog or pass the fields parameter."
                is TransitionError.InvalidTransition ->
                    "Use getTransitions to see available transitions for this ticket."
                else -> "Use getTransitions to see available transitions for this ticket."
            }
            ToolResult(
                data = Unit,
                summary = result.summary,
                isError = true,
                hint = hint,
                payload = payload
            )
        } else {
            ToolResult.success(
                data = Unit,
                summary = "Transitioned $key with transition ID $transitionId."
            )
        }
    }

    override suspend fun addComment(
        key: String,
        body: String,
        visibility: CommentVisibility?
    ): ToolResult<Unit> {
        val api = client ?: return ToolResult(
            data = Unit,
            summary = "Jira not configured. Cannot add comment to $key.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        // Jira's `visibility.type` is lowercase ("role" / "group"); our enum is uppercase.
        val typeWire = visibility?.type?.let {
            when (it) {
                VisibilityType.ROLE -> "role"
                VisibilityType.GROUP -> "group"
            }
        }
        val result = api.addComment(
            issueKey = key,
            body = body,
            visibilityType = typeWire,
            visibilityValue = visibility?.value
        )
        return when (result) {
            is ApiResult.Success -> {
                val suffix = visibility?.let { " (visible to ${it.type.name.lowercase()} '${it.value}')" } ?: ""
                ToolResult.success(
                    data = Unit,
                    summary = "Comment added to $key$suffix."
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

    override suspend fun logWork(
        key: String,
        timeSpent: String,
        comment: String?,
        started: OffsetDateTime?,
        adjustEstimate: WorklogEstimateAdjustment
    ): ToolResult<Unit> {
        val api = client ?: return ToolResult(
            data = Unit,
            summary = "Jira not configured. Cannot log work on $key.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        val startedWire = started?.format(JIRA_STARTED_FORMAT)
        // AUTO is the default — only forward the param when the caller picked something else.
        val adjustWire = if (adjustEstimate == WorklogEstimateAdjustment.AUTO) null
                        else adjustEstimate.name.lowercase()

        val result = api.postWorklog(
            issueKey = key,
            timeSpent = timeSpent,
            comment = comment,
            started = startedWire,
            adjustEstimateParam = adjustWire
        )
        return when (result) {
            is ApiResult.Success -> {
                ToolResult.success(
                    data = Unit,
                    summary = buildString {
                        append("Logged $timeSpent on $key.")
                        if (comment != null) append(" Comment: $comment")
                        if (startedWire != null) append(" Started: $startedWire.")
                    }
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

    override suspend fun getWorklogs(issueKey: String): ToolResult<List<WorklogData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured. Cannot fetch worklogs for $issueKey.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.getWorklogs(issueKey)) {
            is ApiResult.Success -> {
                val worklogs = result.data.worklogs.map { w ->
                    WorklogData(
                        author = w.author?.displayName ?: "Unknown",
                        timeSpent = w.timeSpent,
                        timeSpentSeconds = w.timeSpentSeconds,
                        comment = w.comment,
                        started = w.started
                    )
                }
                val totalSeconds = worklogs.sumOf { it.timeSpentSeconds }
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                ToolResult.success(
                    data = worklogs,
                    summary = "Found ${worklogs.size} worklog(s) totaling ${hours}h ${minutes}m for $issueKey"
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch worklogs for $issueKey: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching worklogs for $issueKey: ${result.message}",
                    isError = true,
                    hint = "Check Jira connection in Settings."
                )
            }
        }
    }

    override suspend fun getAvailableSprints(boardId: Int): ToolResult<List<SprintData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured. Cannot fetch sprints for board $boardId.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        // Fetch both active and closed sprints
        val activeSprints = when (val result = api.getActiveSprints(boardId)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch active sprints: ${result.message}")
                emptyList()
            }
        }
        val closedSprints = when (val result = api.getClosedSprints(boardId)) {
            is ApiResult.Success -> result.data.values
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch closed sprints: ${result.message}")
                emptyList()
            }
        }

        val allSprints = (activeSprints + closedSprints).map { s ->
            SprintData(
                id = s.id,
                name = s.name,
                state = s.state,
                startDate = s.startDate,
                endDate = s.endDate
            )
        }

        val activeCount = allSprints.count { it.state.equals("active", ignoreCase = true) }
        val closedCount = allSprints.count { it.state.equals("closed", ignoreCase = true) }
        return ToolResult.success(
            data = allSprints,
            summary = "Board $boardId: $activeCount active, $closedCount closed sprint(s)"
        )
    }

    override suspend fun getLinkedPullRequests(issueId: String): ToolResult<List<DevStatusPrData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured. Cannot fetch linked PRs for issue $issueId.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.getDevStatusPullRequests(issueId)) {
            is ApiResult.Success -> {
                val prs = result.data.map { pr ->
                    DevStatusPrData(
                        name = pr.name,
                        url = pr.url,
                        status = pr.status,
                        lastUpdate = pr.lastUpdate
                    )
                }
                val listing = if (prs.isEmpty()) "none" else prs.joinToString(", ") { "${it.name} (${it.status})" }
                ToolResult.success(
                    data = prs,
                    summary = "Found ${prs.size} linked PR(s) for issue $issueId: $listing"
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch linked PRs for issue $issueId: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching linked PRs for issue $issueId: ${result.message}",
                    isError = true,
                    hint = "The dev-status API may not be available on your Jira instance."
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

    // ── New agent-exposable methods ──────────────────────────────────────

    override suspend fun getBoards(type: String?, nameFilter: String?): ToolResult<List<BoardData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured. Cannot fetch boards.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.getBoards(type ?: "", nameFilter ?: "")) {
            is ApiResult.Success -> {
                val boards = result.data.map { b ->
                    BoardData(id = b.id, name = b.name, type = b.type)
                }
                ToolResult.success(
                    data = boards,
                    summary = "Found ${boards.size} board(s)${type?.let { " (type=$it)" } ?: ""}"
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch boards: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching boards: ${result.message}",
                    isError = true,
                    hint = "Check Jira connection in Settings."
                )
            }
        }
    }

    override suspend fun getSprintIssues(sprintId: Int): ToolResult<List<JiraTicketData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured. Cannot fetch sprint issues.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.getSprintIssues(sprintId, true)) {
            is ApiResult.Success -> {
                val tickets = result.data.map { it.toTicketData() }
                ToolResult.success(
                    data = tickets,
                    summary = "Sprint $sprintId: ${tickets.size} issue(s)"
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch sprint $sprintId issues: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching sprint $sprintId issues: ${result.message}",
                    isError = true,
                    hint = "Check Jira connection in Settings."
                )
            }
        }
    }

    override suspend fun getBoardIssues(boardId: Int): ToolResult<List<JiraTicketData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured. Cannot fetch board issues.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.getBoardIssues(boardId, true)) {
            is ApiResult.Success -> {
                val tickets = result.data.map { it.toTicketData() }
                ToolResult.success(
                    data = tickets,
                    summary = "Board $boardId: ${tickets.size} unresolved issue(s)"
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch board $boardId issues: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching board $boardId issues: ${result.message}",
                    isError = true,
                    hint = "Check Jira connection in Settings."
                )
            }
        }
    }

    override suspend fun searchIssues(text: String, maxResults: Int, currentUserOnly: Boolean): ToolResult<List<JiraTicketData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured. Cannot search issues.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.searchIssues(text, maxResults, currentUserOnly)) {
            is ApiResult.Success -> {
                val tickets = result.data.map { it.toTicketData() }
                ToolResult.success(
                    data = tickets,
                    summary = "Search \"$text\": ${tickets.size} result(s)"
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Search failed for \"$text\": ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error searching issues: ${result.message}",
                    isError = true,
                    hint = "Check Jira connection in Settings."
                )
            }
        }
    }

    override suspend fun getDevStatusBranches(issueId: String): ToolResult<List<DevStatusBranchData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured. Cannot fetch dev-status branches for issue $issueId.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.getDevStatusBranches(issueId)) {
            is ApiResult.Success -> {
                val branches = result.data.map { b ->
                    DevStatusBranchData(name = b.name, url = b.url)
                }
                val listing = if (branches.isEmpty()) "none" else branches.joinToString(", ") { it.name }
                ToolResult.success(
                    data = branches,
                    summary = "Found ${branches.size} branch(es) for issue $issueId: $listing"
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch dev-status branches for issue $issueId: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching branches for issue $issueId: ${result.message}",
                    isError = true,
                    hint = "The dev-status API may not be available on your Jira instance."
                )
            }
        }
    }

    override suspend fun startWork(
        issueKey: String,
        branchName: String,
        sourceBranch: String
    ): ToolResult<StartWorkResultData> {
        if (client == null) {
            return ToolResult(
                data = StartWorkResultData(branchName = branchName, ticketKey = issueKey, transitioned = false),
                summary = "Jira not configured. Cannot start work on $issueKey.",
                isError = true,
                hint = "Set up Jira connection in Settings."
            )
        }

        // Delegate the transition to TicketTransitionService — the canonical write path.
        // It fetches `expand=transitions.fields` and runs a required-field preflight, so
        // when a Jira admin marks any field required on the In Progress transition we get
        // a structured MissingFields error instead of a silent 400. Pre-2026-05-08 this
        // method posted directly to /transitions with fields={} and bypassed the preflight.
        val transitionSvc = ticketTransitionService
        var transitioned = false
        var missingFieldsBlocker: String? = null
        var transitionErrorMessage: String? = null

        if (transitionSvc == null) {
            log.warn("[JiraService] TicketTransitionService unavailable; skipping transition for $issueKey")
        } else {
            val avail = transitionSvc.getAvailableTransitions(issueKey)
            if (avail.isError) {
                log.warn("[JiraService] Could not fetch transitions for $issueKey: ${avail.summary}")
            } else {
                val inProgress = avail.data!!.firstOrNull { t ->
                    t.toStatus.name.equals("In Progress", ignoreCase = true)
                }
                if (inProgress == null) {
                    log.info("[JiraService] No 'In Progress' transition available for $issueKey")
                } else {
                    val outcome: ToolResult<TransitionOutcome> =
                        transitionSvc.executeTransition(
                            issueKey,
                            TransitionInput(inProgress.id, emptyMap(), null)
                        )
                    if (outcome.isError) {
                        when (val payload = outcome.payload) {
                            is TransitionError.MissingFields -> {
                                val fieldNames = payload.payload.fields.joinToString(", ") { it.name }
                                missingFieldsBlocker =
                                    "Cannot auto-start: '${payload.payload.transitionName}' " +
                                        "requires $fieldNames. Use the Sprint-tab transition dialog."
                                log.info("[JiraService] startWork blocked by required fields on $issueKey: $fieldNames")
                            }
                            else -> {
                                transitionErrorMessage = outcome.summary
                                log.warn("[JiraService] Failed to transition $issueKey to In Progress: ${outcome.summary}")
                            }
                        }
                    } else {
                        transitioned = true
                        log.info("[JiraService] Transitioned $issueKey to In Progress")
                    }
                }
            }
        }

        val data = StartWorkResultData(
            branchName = branchName,
            ticketKey = issueKey,
            transitioned = transitioned
        )

        // A required-field block is reportable as an error so the agent surfaces the
        // workflow rule. Branch creation already succeeded; only the transition was
        // skipped. Other transition errors (forbidden, network, etc.) keep the previous
        // best-effort behaviour: log + report unchanged status.
        return if (missingFieldsBlocker != null) {
            ToolResult(
                data = data,
                summary = "Started work on $issueKey. Branch: $branchName (from $sourceBranch). $missingFieldsBlocker",
                isError = true,
                hint = missingFieldsBlocker
            )
        } else {
            ToolResult.success(
                data = data,
                summary = buildString {
                    append("Started work on $issueKey. Branch: $branchName (from $sourceBranch).")
                    when {
                        transitioned -> append(" Ticket transitioned to In Progress.")
                        transitionErrorMessage != null ->
                            append(" Ticket status unchanged (transition failed: $transitionErrorMessage).")
                        else -> append(" Ticket status unchanged (no In Progress transition available).")
                    }
                }
            )
        }
    }

    override suspend fun downloadAttachment(
        issueKey: String,
        attachmentId: String
    ): ToolResult<AttachmentContentData> {
        val api = client ?: return ToolResult(
            data = AttachmentContentData(
                filename = "", mimeType = null, sizeBytes = 0,
                content = null, filePath = "", attachmentId = attachmentId
            ),
            summary = "Jira not configured. Cannot download attachment.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        // Fetch issue to find the attachment metadata
        val issueResult = api.getIssue(issueKey)
        if (issueResult is ApiResult.Error) {
            return ToolResult(
                data = AttachmentContentData(
                    filename = "", mimeType = null, sizeBytes = 0,
                    content = null, filePath = "", attachmentId = attachmentId
                ),
                summary = "Error fetching issue $issueKey: ${issueResult.message}",
                isError = true,
                hint = "Verify the issue key is correct."
            )
        }

        val issue = (issueResult as ApiResult.Success).data
        val attachment = issue.fields.attachment.firstOrNull { it.id == attachmentId }
            ?: return ToolResult(
                data = AttachmentContentData(
                    filename = "", mimeType = null, sizeBytes = 0,
                    content = null, filePath = "", attachmentId = attachmentId
                ),
                summary = "Attachment $attachmentId not found on $issueKey.",
                isError = true,
                hint = "Use getTicket to list available attachments."
            )

        val downloadService = AttachmentDownloadService(project)
        // Land the file inside the active agent session's `{sessionDir}/downloads/`
        // when called from an agent tool — that directory is under the
        // `~/.workflow-orchestrator/` tree, which `PathValidator.resolveAndValidateForRead`
        // allowlists, so the agent can `read_file` / `read_document` it afterwards.
        // Outside the agent (UI handler, tests), `SessionDownloadDir.current()`
        // returns null and we fall through to the service's system-temp default.
        // See `agent/CLAUDE.md` "Storage tiers".
        val sessionDownloads = SessionDownloadDir.current()
        val targetDir = sessionDownloads?.resolve("jira-$attachmentId")?.toFile()
        val downloadResult = downloadService.downloadAttachment(attachment, targetDir)
            ?: return ToolResult(
                data = AttachmentContentData(
                    filename = attachment.filename, mimeType = attachment.mimeType,
                    sizeBytes = attachment.size, content = null, filePath = "",
                    attachmentId = attachmentId
                ),
                summary = "Failed to download attachment ${attachment.filename} from $issueKey.",
                isError = true,
                hint = "The attachment content URL may be inaccessible."
            )

        // For text MIME types, read file content
        val textContent = if (downloadResult.isText) {
            try {
                downloadResult.file.readText().take(10_000)
            } catch (e: Exception) {
                log.warn("[JiraService] Could not read text content of ${attachment.filename}", e)
                null
            }
        } else null

        val data = AttachmentContentData(
            filename = downloadResult.filename,
            mimeType = downloadResult.mimeType,
            sizeBytes = downloadResult.sizeBytes,
            content = textContent,
            filePath = downloadResult.file.absolutePath,
            attachmentId = attachmentId
        )
        return ToolResult.success(
            data = data,
            summary = "Downloaded ${data.filename} (${data.sizeBytes} bytes) to ${data.filePath}"
        )
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private fun com.workflow.orchestrator.jira.api.dto.JiraIssue.toTicketData(): JiraTicketData {
        return JiraTicketData(
            key = key,
            summary = fields.summary,
            status = fields.status.name,
            assignee = fields.assignee?.displayName,
            reporter = fields.reporter?.displayName,
            type = fields.issuetype?.name ?: "Unknown",
            priority = fields.priority?.name,
            description = fields.description,
            labels = fields.labels,
            created = fields.created,
            updated = fields.updated,
            sprintName = fields.sprint?.name,
            sprintState = fields.sprint?.state,
            closedSprints = fields.closedSprints.map { "${it.name} (${it.state})" },
            epicKey = fields.parent?.key,
            epicSummary = fields.parent?.fields?.summary,
            originalEstimate = fields.timetracking?.originalEstimate,
            remainingEstimate = fields.timetracking?.remainingEstimate,
            timeSpent = fields.timetracking?.timeSpent,
            commentCount = fields.comment?.total ?: 0,
            attachmentCount = fields.attachment.size,
            attachments = fields.attachment.map { att ->
                JiraAttachmentData(id = att.id, filename = att.filename, mimeType = att.mimeType, sizeBytes = att.size)
            },
            subtasks = fields.subtasks.map { st ->
                JiraSubtaskRef(
                    key = st.key,
                    summary = st.fields.summary,
                    status = st.fields.status.name
                )
            },
            linkedIssues = fields.issuelinks.mapNotNull { link ->
                val linked = link.inwardIssue ?: link.outwardIssue ?: return@mapNotNull null
                val relationship = if (link.inwardIssue != null) link.type.inward else link.type.outward
                JiraLinkedIssueRef(
                    key = linked.key,
                    summary = linked.fields.summary,
                    status = linked.fields.status.name,
                    relationship = relationship
                )
            }
        )
    }

    /**
     * Provides the underlying [JiraApiClient] for modules that need direct API access
     * (e.g., SprintService, BranchingService). Returns null if Jira is not configured.
     *
     * Prefer using the service-level methods (getTicket, addComment, etc.) when possible.
     * This avoids duplicate client construction and ensures consistent auth handling.
     */
    fun getApiClient(): JiraApiClient? = client

    override suspend fun getLinkedCommits(issueId: String): ToolResult<List<DevStatusCommitData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured. Cannot fetch linked commits for issue $issueId.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )
        return when (val result = api.getDevStatusCommits(issueId)) {
            is ApiResult.Success -> {
                val commits = result.data.map { c ->
                    DevStatusCommitData(
                        sha = c.id,
                        displayId = c.displayId,
                        message = c.message,
                        url = c.url,
                        authorName = c.author?.name,
                        authorTimestamp = c.authorTimestamp,
                        merge = c.merge
                    )
                }
                ToolResult.success(data = commits, summary = "Found ${commits.size} commit(s) for issue $issueId")
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch commits for issue $issueId: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching commits for issue $issueId: ${result.message}",
                    isError = true,
                    hint = "The dev-status API may not be available on your Jira instance."
                )
            }
        }
    }

    override suspend fun getLinkedBuilds(issueId: String): ToolResult<List<DevStatusBuildData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured. Cannot fetch linked builds for issue $issueId.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )
        return when (val result = api.getDevStatusBuilds(issueId)) {
            is ApiResult.Success -> {
                val builds = result.data.map { b ->
                    DevStatusBuildData(
                        name = b.name,
                        url = b.url,
                        state = b.state,
                        lastUpdated = b.lastUpdated,
                        description = b.description
                    )
                }
                ToolResult.success(data = builds, summary = "Found ${builds.size} build(s) for issue $issueId")
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch builds for issue $issueId: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching builds for issue $issueId: ${result.message}",
                    isError = true,
                    hint = "The dev-status API may not be available on your Jira instance."
                )
            }
        }
    }

    override suspend fun getLinkedDeployments(issueId: String): ToolResult<List<DevStatusDeploymentData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured. Cannot fetch linked deployments for issue $issueId.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )
        return when (val result = api.getDevStatusDeployments(issueId)) {
            is ApiResult.Success -> {
                val deployments = result.data.map { d ->
                    DevStatusDeploymentData(
                        displayName = d.displayName,
                        url = d.url,
                        state = d.state,
                        environmentName = d.environment?.displayName,
                        environmentType = d.environment?.type,
                        lastUpdated = d.lastUpdated
                    )
                }
                ToolResult.success(data = deployments, summary = "Found ${deployments.size} deployment(s) for issue $issueId")
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch deployments for issue $issueId: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching deployments for issue $issueId: ${result.message}",
                    isError = true,
                    hint = "The dev-status API may not be available on your Jira instance."
                )
            }
        }
    }

    override suspend fun getLinkedReviews(issueId: String): ToolResult<List<DevStatusReviewData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured. Cannot fetch linked reviews for issue $issueId.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )
        return when (val result = api.getDevStatusReviews(issueId)) {
            is ApiResult.Success -> {
                val reviews = result.data.map { r ->
                    DevStatusReviewData(
                        name = r.name,
                        url = r.url,
                        state = r.state,
                        reviewerNames = r.reviewers.map { it.name },
                        lastUpdated = r.lastUpdated
                    )
                }
                ToolResult.success(data = reviews, summary = "Found ${reviews.size} review(s) for issue $issueId")
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch reviews for issue $issueId: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching reviews for issue $issueId: ${result.message}",
                    isError = true,
                    hint = "The dev-status API may not be available on your Jira instance."
                )
            }
        }
    }

    override suspend fun getFullDevStatus(issueId: String): ToolResult<DevStatusBundle> {
        val api = client ?: return ToolResult(
            data = DevStatusBundle(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), fetchedAt = System.currentTimeMillis()),
            summary = "Jira not configured. Cannot fetch dev status for issue $issueId.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )
        return fetchFullDevStatus(issueId, api)
    }

    internal suspend fun fetchFullDevStatus(
        issueId: String,
        fetcher: com.workflow.orchestrator.jira.api.DevStatusFetcher
    ): ToolResult<DevStatusBundle> = coroutineScope {
        val branchesDeferred = async { fetcher.getDevStatusBranches(issueId) }
        val prsDeferred = async { fetcher.getDevStatusPullRequests(issueId) }
        val commitsDeferred = async { fetcher.getDevStatusCommits(issueId) }
        val buildsDeferred = async { fetcher.getDevStatusBuilds(issueId) }
        val deploymentsDeferred = async { fetcher.getDevStatusDeployments(issueId) }
        val reviewsDeferred = async { fetcher.getDevStatusReviews(issueId) }

        val branchesResult = branchesDeferred.await()
        val prsResult = prsDeferred.await()
        val commitsResult = commitsDeferred.await()
        val buildsResult = buildsDeferred.await()
        val deploymentsResult = deploymentsDeferred.await()
        val reviewsResult = reviewsDeferred.await()

        var fetchErrors = 0

        val branches = when (branchesResult) {
            is ApiResult.Success -> branchesResult.data.map { b -> DevStatusBranchData(name = b.name, url = b.url) }
            is ApiResult.Error -> { log.warn("[JiraService] branches fetch failed: ${branchesResult.message}"); fetchErrors++; emptyList() }
        }
        val prs = when (prsResult) {
            is ApiResult.Success -> prsResult.data.map { pr -> DevStatusPrData(name = pr.name, url = pr.url, status = pr.status, lastUpdate = pr.lastUpdate) }
            is ApiResult.Error -> { log.warn("[JiraService] PRs fetch failed: ${prsResult.message}"); fetchErrors++; emptyList() }
        }
        val commits = when (commitsResult) {
            is ApiResult.Success -> commitsResult.data.map { c -> DevStatusCommitData(sha = c.id, displayId = c.displayId, message = c.message, url = c.url, authorName = c.author?.name, authorTimestamp = c.authorTimestamp, merge = c.merge) }
            is ApiResult.Error -> { log.warn("[JiraService] commits fetch failed: ${commitsResult.message}"); fetchErrors++; emptyList() }
        }
        val builds = when (buildsResult) {
            is ApiResult.Success -> buildsResult.data.map { b -> DevStatusBuildData(name = b.name, url = b.url, state = b.state, lastUpdated = b.lastUpdated, description = b.description) }
            is ApiResult.Error -> { log.warn("[JiraService] builds fetch failed: ${buildsResult.message}"); fetchErrors++; emptyList() }
        }
        val deployments = when (deploymentsResult) {
            is ApiResult.Success -> deploymentsResult.data.map { d -> DevStatusDeploymentData(displayName = d.displayName, url = d.url, state = d.state, environmentName = d.environment?.displayName, environmentType = d.environment?.type, lastUpdated = d.lastUpdated) }
            is ApiResult.Error -> { log.warn("[JiraService] deployments fetch failed: ${deploymentsResult.message}"); fetchErrors++; emptyList() }
        }
        val reviews = when (reviewsResult) {
            is ApiResult.Success -> reviewsResult.data.map { rv -> DevStatusReviewData(name = rv.name, url = rv.url, state = rv.state, reviewerNames = rv.reviewers.map { it.name }, lastUpdated = rv.lastUpdated) }
            is ApiResult.Error -> { log.warn("[JiraService] reviews fetch failed: ${reviewsResult.message}"); fetchErrors++; emptyList() }
        }

        val bundle = DevStatusBundle(
            branches = branches,
            pullRequests = prs,
            commits = commits,
            builds = builds,
            deployments = deployments,
            reviews = reviews,
            fetchErrors = fetchErrors,
            fetchedAt = System.currentTimeMillis()
        )
        ToolResult.success(data = bundle, summary = bundle.summaryLine())
    }

    override suspend fun searchBoards(query: String): ToolResult<List<JiraBoardSummary>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured. Cannot search boards.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.getBoards(nameFilter = query)) {
            is ApiResult.Success -> {
                val boards = result.data.map { b ->
                    JiraBoardSummary(id = b.id.toLong(), name = b.name, type = b.type)
                }
                ToolResult.success(
                    data = boards,
                    summary = "Found ${boards.size} board(s) matching \"$query\""
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to search boards for \"$query\": ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error searching boards for \"$query\": ${result.message}",
                    isError = true,
                    hint = "Check Jira connection in Settings."
                )
            }
        }
    }

    override suspend fun searchTickets(jql: String, maxResults: Int): ToolResult<List<JiraTicketData>> {
        // Lightweight sanity-validation of the agent-supplied JQL string.
        // Goal: reject obviously malformed input while preserving the agent's
        // full JQL expressiveness. This is NOT a full JQL parser — Jira's server
        // enforces query authorization via the user's own PAT (no privilege escalation).
        // Closes audit finding jira:F-12.
        val trimmed = jql.trim()
        if (trimmed.isBlank()) {
            return ToolResult(
                data = emptyList(),
                summary = "JQL must not be blank.",
                isError = true,
                hint = "Provide a non-empty JQL query, e.g. \"project = PROJ AND status = Open\"."
            )
        }
        if (trimmed.length > MAX_JQL_LENGTH) {
            return ToolResult(
                data = emptyList(),
                summary = "JQL exceeds maximum allowed length ($MAX_JQL_LENGTH chars).",
                isError = true,
                hint = "Shorten the JQL query."
            )
        }
        if (trimmed.any { it.code < 32 }) {
            return ToolResult(
                data = emptyList(),
                summary = "JQL contains control characters and was rejected.",
                isError = true,
                hint = "Remove non-printable or control characters from the JQL query."
            )
        }

        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        // Note: search runs under the user's own Jira PAT — no privilege escalation is possible.
        return when (val result = api.searchByJql(trimmed, maxResults)) {
            is ApiResult.Success -> {
                val tickets = result.data.map { it.toTicketData() }
                ToolResult.success(
                    data = tickets,
                    summary = "${tickets.size} ticket(s) found"
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Ticket search failed: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error searching tickets: ${result.message}",
                    isError = true
                )
            }
        }
    }

    /**
     * Scans summary, description, and comment bodies for Jira ticket key patterns.
     * Returns unique keys excluding the ticket itself, its subtasks, and already-linked issues.
     */
    private fun extractMentionedTickets(
        selfKey: String,
        summary: String,
        description: String?,
        comments: List<String>,
        subtaskKeys: Set<String>,
        linkedKeys: Set<String>
    ): List<String> {
        val pattern = Regex("\\b([A-Z][A-Z0-9]+-\\d+)\\b")
        val excludeKeys = subtaskKeys + linkedKeys + selfKey
        val allText = buildString {
            appendLine(summary)
            if (!description.isNullOrBlank()) appendLine(description)
            comments.forEach { appendLine(it) }
        }
        return pattern.findAll(allText)
            .map { it.groupValues[1] }
            .distinct()
            .filter { it !in excludeKeys }
            .toList()
    }

    /**
     * Converts a loosely-typed field value into a typed [FieldValue] for [TransitionInput].
     *
     * - [Map] → [FieldValue.Text] with the first value (e.g. `{"name":"jdoe"}` → kept as-is via UserRef path
     *   below by checking for "name" key; otherwise Text of the map's string representation).
     * - Anything else → [FieldValue.Text] of `.toString()`.
     *
     * Note: callers that need richer field types should construct [TransitionInput] directly.
     */
    private fun anyToFieldValue(v: Any): FieldValue = when (v) {
        is Map<*, *> -> {
            val name = v["name"]
            if (name != null) FieldValue.UserRef(name.toString())
            else FieldValue.Text(v.toString())
        }
        else -> FieldValue.Text(v.toString())
    }

    // ── Caches for new endpoints ─────────────────────────────────────────

    private data class CacheEntry<T>(val value: T, val expiresAt: Long)

    private val permissionsCache = ConcurrentHashMap<String, CacheEntry<MyPermissionsData>>()

    @Volatile private var fieldsCache: CacheEntry<List<JiraFieldData>>? = null

    /**
     * Comment-visibility options cache, keyed by `projectKey`. Roles are project-scoped;
     * groups are global but we still bucket per project to keep the dropdown's "groups"
     * call lazy and aligned with the panel's project context.
     */
    private val visibilityCache = ConcurrentHashMap<String, CacheEntry<VisibilityOptions>>()

    private val cacheTtlMs = 5 * 60_000L

    // ── New endpoint methods ─────────────────────────────────────────────

    override suspend fun getMyPermissions(projectKey: String?): ToolResult<MyPermissionsData> {
        val cacheKey = projectKey ?: "_global"
        val now = System.currentTimeMillis()
        permissionsCache[cacheKey]?.let { entry ->
            if (entry.expiresAt > now) {
                return ToolResult.success(
                    data = entry.value,
                    summary = "Found ${entry.value.permissions.size} permission(s) " +
                        (if (projectKey != null) "for project $projectKey" else "globally") + " (cached)."
                )
            }
        }

        val api = client ?: return ToolResult(
            data = MyPermissionsData(emptyMap()),
            summary = "Jira not configured. Cannot fetch permissions.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.getMyPermissions(projectKey)) {
            is ApiResult.Success -> {
                val filtered = result.data.permissions.entries
                    .filter { (_, p) -> !p.deprecatedKey }
                    .associate { (k, p) ->
                        k to PermissionFlag(
                            key = p.key.ifBlank { k },
                            name = p.name,
                            havePermission = p.havePermission,
                            deprecated = p.deprecatedKey
                        )
                    }
                val data = MyPermissionsData(filtered)
                permissionsCache[cacheKey] = CacheEntry(data, now + cacheTtlMs)
                ToolResult.success(
                    data = data,
                    summary = "Found ${data.permissions.size} permission(s) " +
                        (if (projectKey != null) "for project $projectKey" else "globally") + "."
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch permissions: ${result.message}")
                ToolResult(
                    data = MyPermissionsData(emptyMap()),
                    summary = "Error fetching permissions: ${result.message}",
                    isError = true,
                    hint = "Check Jira connection in Settings."
                )
            }
        }
    }

    /**
     * Force the next [getFields] call to bypass the 5-min cache.
     *
     * Used by the settings UI's "Refresh fields" button so a freshly-added custom
     * field shows up in the picker without waiting for cache expiry.
     */
    fun invalidateFieldsCache() {
        fieldsCache = null
    }

    override suspend fun getFields(): ToolResult<List<JiraFieldData>> {
        val now = System.currentTimeMillis()
        fieldsCache?.let { entry ->
            if (entry.expiresAt > now) {
                return ToolResult.success(
                    data = entry.value,
                    summary = "Found ${entry.value.size} field(s) (cached)."
                )
            }
        }

        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured. Cannot fetch fields.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.getFields()) {
            is ApiResult.Success -> {
                val fields = result.data.map { f ->
                    JiraFieldData(
                        id = f.id,
                        name = f.name,
                        isCustom = f.custom,
                        schemaType = f.schema?.type
                    )
                }
                fieldsCache = CacheEntry(fields, now + cacheTtlMs)
                ToolResult.success(
                    data = fields,
                    summary = "Found ${fields.size} field(s)."
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch fields: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching fields: ${result.message}",
                    isError = true,
                    hint = "Check Jira connection in Settings."
                )
            }
        }
    }

    override suspend fun getRemoteLinks(key: String): ToolResult<List<RemoteLinkData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured. Cannot fetch remote links for $key.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.getRemoteLinks(key)) {
            is ApiResult.Success -> {
                // Drop links without a usable URL — Jira occasionally returns entries
                // whose `object.url` is null (orphaned links from removed apps); they
                // would render as un-clickable rows in LinkedDocsSection.
                val links = result.data.mapNotNull { l ->
                    val url = l.`object`?.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    RemoteLinkData(
                        id = l.id,
                        applicationType = l.application?.type,
                        applicationName = l.application?.name,
                        relationship = l.relationship,
                        url = url,
                        title = l.`object`?.title
                    )
                }
                ToolResult.success(
                    data = links,
                    summary = "Found ${links.size} remote link(s) for $key."
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch remote links for $key: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching remote links for $key: ${result.message}",
                    isError = true,
                    hint = "Verify the ticket key is correct."
                )
            }
        }
    }

    override suspend fun getWatchers(key: String): ToolResult<WatchersData> {
        val api = client ?: return ToolResult(
            data = WatchersData(0, false, emptyList()),
            summary = "Jira not configured. Cannot fetch watchers for $key.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.getWatchers(key)) {
            is ApiResult.Success -> {
                val data = WatchersData(
                    watchCount = result.data.watchCount,
                    isWatching = result.data.isWatching,
                    watchers = result.data.watchers.map { w ->
                        WatcherUser(name = w.name, displayName = w.displayName, emailAddress = w.emailAddress)
                    }
                )
                ToolResult.success(
                    data = data,
                    summary = "$key has ${data.watchCount} watcher(s)."
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch watchers for $key: ${result.message}")
                ToolResult(
                    data = WatchersData(0, false, emptyList()),
                    summary = "Error fetching watchers for $key: ${result.message}",
                    isError = true,
                    hint = "Verify the ticket key is correct."
                )
            }
        }
    }

    override suspend fun addWatcher(key: String, username: String): ToolResult<Unit> {
        val api = client ?: return ToolResult(
            data = Unit,
            summary = "Jira not configured. Cannot add watcher to $key.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.addWatcher(key, username)) {
            is ApiResult.Success -> ToolResult.success(
                data = Unit,
                summary = "Added $username as a watcher on $key."
            )
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to add watcher $username to $key: ${result.message}")
                ToolResult(
                    data = Unit,
                    summary = "Error adding watcher $username to $key: ${result.message}",
                    isError = true,
                    hint = "Verify the username and ticket key are correct."
                )
            }
        }
    }

    override suspend fun removeWatcher(key: String, username: String): ToolResult<Unit> {
        val api = client ?: return ToolResult(
            data = Unit,
            summary = "Jira not configured. Cannot remove watcher from $key.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.removeWatcher(key, username)) {
            is ApiResult.Success -> ToolResult.success(
                data = Unit,
                summary = "Removed $username from watchers on $key."
            )
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to remove watcher $username from $key: ${result.message}")
                ToolResult(
                    data = Unit,
                    summary = "Error removing watcher $username from $key: ${result.message}",
                    isError = true,
                    hint = "Verify the username and ticket key are correct."
                )
            }
        }
    }

    override suspend fun getMyselfExpanded(): ToolResult<MyselfData> {
        val api = client ?: return ToolResult(
            data = MyselfData(name = "", displayName = ""),
            summary = "Jira not configured.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.getMyselfExpanded()) {
            is ApiResult.Success -> {
                val m = result.data
                val data = MyselfData(
                    name = m.name,
                    displayName = m.displayName,
                    emailAddress = m.emailAddress,
                    groups = m.groups?.items?.map { it.name }.orEmpty(),
                    applicationRoles = m.applicationRoles?.items?.map { it.name }.orEmpty()
                )
                ToolResult.success(
                    data = data,
                    summary = "Current user: ${data.displayName} (${data.name}); " +
                        "${data.groups.size} group(s), ${data.applicationRoles.size} role(s)."
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch myself: ${result.message}")
                ToolResult(
                    data = MyselfData(name = "", displayName = ""),
                    summary = "Error fetching current user: ${result.message}",
                    isError = true,
                    hint = "Check Jira connection in Settings."
                )
            }
        }
    }

    override suspend fun getIssueSuggestions(query: String): ToolResult<List<IssueSuggestion>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.getIssueSuggestions(query)) {
            is ApiResult.Success -> {
                // Flatten across every section — key-prefix queries return only History Search,
                // free-text queries return both History Search and Current Search.
                val suggestions = result.data.sections.flatMap { section ->
                    section.issues.map { e ->
                        IssueSuggestion(
                            key = e.key,
                            summary = e.summary,
                            summaryText = e.summaryText,
                            iconUrl = e.img
                        )
                    }
                }
                ToolResult.success(
                    data = suggestions,
                    summary = "Found ${suggestions.size} issue suggestion(s) for '$query'."
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch issue suggestions for '$query': ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching issue suggestions: ${result.message}",
                    isError = true,
                    hint = "Check Jira connection in Settings."
                )
            }
        }
    }

    override suspend fun getFavouriteFilters(): ToolResult<List<FilterData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.getFavouriteFilters()) {
            is ApiResult.Success -> {
                val filters = result.data.mapNotNull { f -> f.toFilterData() }
                ToolResult.success(
                    data = filters,
                    summary = "Found ${filters.size} favourite filter(s)."
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch favourite filters: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching favourite filters: ${result.message}",
                    isError = true,
                    hint = "Check Jira connection in Settings."
                )
            }
        }
    }

    override suspend fun getFilter(id: Long): ToolResult<FilterData> {
        val api = client ?: return ToolResult(
            data = FilterData(id = id, name = ""),
            summary = "Jira not configured.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.getFilter(id)) {
            is ApiResult.Success -> {
                val data = result.data.toFilterData()
                if (data == null) {
                    log.warn("[JiraService] Filter $id returned a malformed id (${result.data.id}); cannot map.")
                    ToolResult(
                        data = FilterData(id = id, name = ""),
                        summary = "Filter $id returned a malformed id and could not be parsed.",
                        isError = true,
                        hint = "The Jira filter response did not contain a numeric id."
                    )
                } else {
                    ToolResult.success(
                        data = data,
                        summary = "Filter $id: ${data.name}."
                    )
                }
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch filter $id: ${result.message}")
                ToolResult(
                    data = FilterData(id = id, name = ""),
                    summary = "Error fetching filter $id: ${result.message}",
                    isError = true,
                    hint = "Verify the filter id is correct."
                )
            }
        }
    }

    override suspend fun getTicketHistory(key: String): ToolResult<List<TicketHistoryEntry>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured. Cannot fetch history for $key.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.getIssueWithContextAndChangelog(key)) {
            is ApiResult.Success -> {
                val histories = result.data.changelog?.histories.orEmpty()
                val entries = histories.flatMap { h ->
                    h.items.map { item ->
                        TicketHistoryEntry(
                            actorDisplayName = h.author?.displayName ?: h.author?.name ?: "Unknown",
                            createdAt = h.created,
                            field = item.field,
                            oldValue = item.fromString,
                            newValue = item.toString
                        )
                    }
                }
                ToolResult.success(
                    data = entries,
                    summary = "Found ${entries.size} history entry(ies) for $key."
                )
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch history for $key: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching history for $key: ${result.message}",
                    isError = true,
                    hint = "Verify the ticket key is correct."
                )
            }
        }
    }

    override suspend fun renderWikiMarkup(text: String, issueKey: String): ToolResult<String> {
        val api = client ?: return ToolResult(
            data = "",
            summary = "Jira not configured. Cannot render wiki markup.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        val payload = buildJsonObject {
            put("rendererType", "atlassian-wiki-renderer")
            put("unrenderedMarkup", text)
            put("issueKey", issueKey)
        }.toString()

        return when (val result = api.postRawString(path = "/rest/api/1.0/render", jsonBody = payload)) {
            is ApiResult.Success -> ToolResult.success(
                data = result.data,
                summary = "Rendered wiki markup for $issueKey."
            )
            is ApiResult.Error -> {
                log.warn("[JiraService] renderWikiMarkup failed for $issueKey: ${result.message}")
                ToolResult(
                    data = "",
                    summary = "Jira render failed: ${result.message}",
                    isError = true,
                    hint = when (result.type) {
                        com.workflow.orchestrator.core.model.ErrorType.AUTH_FAILED ->
                            "Check your Jira token in Settings."
                        else -> "Check Jira connection in Settings."
                    }
                )
            }
        }
    }

    private fun com.workflow.orchestrator.jira.api.dto.JiraFilter.toFilterData(): FilterData? {
        val parsedId = id.toLongOrNull() ?: return null
        return FilterData(
            id = parsedId,
            name = name,
            description = description?.takeIf { it.isNotBlank() },
            jql = jql,
            viewUrl = viewUrl,
            owner = owner?.displayName?.takeIf { it.isNotBlank() } ?: owner?.name?.takeIf { it.isNotBlank() }
        )
    }

    override suspend fun getCommentVisibilityOptions(projectKey: String): ToolResult<VisibilityOptions> {
        val now = System.currentTimeMillis()
        visibilityCache[projectKey]?.let { entry ->
            if (entry.expiresAt > now) {
                return ToolResult.success(
                    data = entry.value,
                    summary = "Found ${entry.value.roles.size} role(s) and ${entry.value.groups.size} group(s) " +
                        "for $projectKey (cached)."
                )
            }
        }

        val api = client ?: return ToolResult(
            data = VisibilityOptions(),
            summary = "Jira not configured. Cannot fetch comment visibility options.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        // Roles: `/project/{key}/role` returns a name-keyed object — flatten to RoleOption(name).
        // Groups: `/groups/picker?query=` returns `{groups:[{name:…}]}` — empty query lists everything
        //         the user can see (Jira DC v2 caps at 200 by default, fine for a dropdown).
        // Both fetches share ONE coroutineScope so the awaits run truly concurrently — wrapping
        // each `async` in its own `coroutineScope` (the original PR 5 shape) made them serial,
        // because `coroutineScope` suspends until its child coroutines complete.
        val (rolesResult, groupsResult) = coroutineScope {
            val rolesDeferred = async { api.getProjectRoles(projectKey) }
            val groupsDeferred = async { api.getRawString("/rest/api/2/groups/picker?query=") }
            rolesDeferred.await() to groupsDeferred.await()
        }

        val roles: List<RoleOption> = when (rolesResult) {
            is ApiResult.Success -> parseProjectRoles(rolesResult.data)
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch project roles for $projectKey: ${rolesResult.message}")
                emptyList()
            }
        }
        val groups: List<GroupOption> = when (groupsResult) {
            is ApiResult.Success -> parseGroupsPicker(groupsResult.data)
            is ApiResult.Error -> {
                log.warn("[JiraService] Failed to fetch groups picker: ${groupsResult.message}")
                emptyList()
            }
        }

        // If both calls failed, surface an error rather than caching an empty payload.
        if (rolesResult is ApiResult.Error && groupsResult is ApiResult.Error) {
            return ToolResult(
                data = VisibilityOptions(),
                summary = "Error fetching comment visibility options for $projectKey: ${rolesResult.message}",
                isError = true,
                hint = "Check Jira connection in Settings."
            )
        }

        val data = VisibilityOptions(roles = roles, groups = groups)
        visibilityCache[projectKey] = CacheEntry(data, now + cacheTtlMs)
        return ToolResult.success(
            data = data,
            summary = "Found ${roles.size} role(s) and ${groups.size} group(s) for $projectKey."
        )
    }

    private fun parseProjectRoles(body: String): List<RoleOption> = try {
        val obj = Json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        // Response shape: `{"Developers":"https://…/role/10001","Administrators":"https://…/role/10002"}`
        // Parse the role-id off the URL tail when present so the UI can bind to a stable id.
        obj.entries.map { (name, urlEl) ->
            val urlText = (urlEl as? kotlinx.serialization.json.JsonPrimitive)?.content
            val id = urlText?.substringAfterLast('/')?.toLongOrNull()
            RoleOption(id = id, name = name)
        }.sortedBy { it.name }
    } catch (e: Exception) {
        log.warn("[JiraService] Failed to parse project roles: ${e.message}")
        emptyList()
    }

    private fun parseGroupsPicker(body: String): List<GroupOption> = try {
        val obj = Json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val arr = obj["groups"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        arr.mapNotNull { el ->
            val name = (el as? JsonObject)?.get("name")?.jsonPrimitive?.content
            name?.let { GroupOption(it) }
        }
    } catch (e: Exception) {
        log.warn("[JiraService] Failed to parse groups picker: ${e.message}")
        emptyList()
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): JiraServiceImpl =
            project.getService(JiraService::class.java) as JiraServiceImpl

        /**
         * Jira's expected `started` format on `POST /rest/api/2/issue/{key}/worklog`.
         * Pinned by `JiraServiceImplWorklogStartedTest`; matches `TimeTrackingService.ISO_FORMAT`.
         */
        internal val JIRA_STARTED_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

        /**
         * Maximum length for agent-supplied JQL in [searchTickets].
         * Jira DC URL path limit is ~8000 chars; JQL of 2000 chars leaves
         * room for the URL prefix, encoding overhead, and other parameters.
         * Closes audit finding jira:F-12.
         */
        internal const val MAX_JQL_LENGTH = 2000
    }
}

/** Convert a Jira API issue DTO to the shared [JiraTicketData] domain model.
 *  Used by Sprint tab to emit [WorkflowEvent.SprintDataLoaded] for the # ticket autocomplete cache. */
internal fun com.workflow.orchestrator.jira.api.dto.JiraIssue.toJiraTicketData(): JiraTicketData {
    return JiraTicketData(
        key = key,
        summary = fields.summary,
        status = fields.status.name,
        assignee = fields.assignee?.displayName,
        reporter = fields.reporter?.displayName,
        type = fields.issuetype?.name ?: "Unknown",
        priority = fields.priority?.name,
        description = fields.description,
        labels = fields.labels,
        created = fields.created,
        updated = fields.updated,
    )
}
