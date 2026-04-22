package com.workflow.orchestrator.jira.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.model.jira.AttachmentContentData
import com.workflow.orchestrator.core.model.jira.BoardData
import com.workflow.orchestrator.core.model.jira.DevStatusBranchData
import com.workflow.orchestrator.core.model.jira.DevStatusPrData
import com.workflow.orchestrator.core.model.jira.JiraCommentData
import com.workflow.orchestrator.core.model.jira.JiraAttachmentData
import com.workflow.orchestrator.core.model.jira.JiraLinkedIssueRef
import com.workflow.orchestrator.core.model.jira.JiraSubtaskRef
import com.workflow.orchestrator.core.model.jira.JiraTicketData
import com.workflow.orchestrator.core.model.jira.TransitionMeta
import com.workflow.orchestrator.core.model.jira.SprintData
import com.workflow.orchestrator.core.model.jira.StartWorkResultData
import com.workflow.orchestrator.core.model.jira.WorklogData
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.api.JiraApiClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
        val api = client ?: return ToolResult(
            data = StartWorkResultData(branchName = branchName, ticketKey = issueKey, transitioned = false),
            summary = "Jira not configured. Cannot start work on $issueKey.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        // Attempt to transition the ticket to "In Progress"
        var transitioned = false
        when (val transResult = api.getTransitions(issueKey)) {
            is ApiResult.Success -> {
                val inProgressTransition = transResult.data.firstOrNull { t ->
                    t.toStatus.name.equals("In Progress", ignoreCase = true)
                }
                if (inProgressTransition != null) {
                    when (api.transitionIssue(issueKey, inProgressTransition.id)) {
                        is ApiResult.Success -> {
                            transitioned = true
                            log.info("[JiraService] Transitioned $issueKey to In Progress")
                        }
                        is ApiResult.Error -> {
                            log.warn("[JiraService] Failed to transition $issueKey to In Progress")
                        }
                    }
                } else {
                    log.info("[JiraService] No 'In Progress' transition available for $issueKey")
                }
            }
            is ApiResult.Error -> {
                log.warn("[JiraService] Could not fetch transitions for $issueKey: ${transResult.message}")
            }
        }

        val data = StartWorkResultData(
            branchName = branchName,
            ticketKey = issueKey,
            transitioned = transitioned
        )
        return ToolResult.success(
            data = data,
            summary = buildString {
                append("Started work on $issueKey. Branch: $branchName (from $sourceBranch).")
                if (transitioned) append(" Ticket transitioned to In Progress.")
                else append(" Ticket status unchanged (no In Progress transition available).")
            }
        )
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
        val downloadResult = downloadService.downloadAttachment(attachment)
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

    override suspend fun searchTickets(jql: String, maxResults: Int): ToolResult<List<JiraTicketData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Jira not configured.",
            isError = true,
            hint = "Set up Jira connection in Settings."
        )

        return when (val result = api.searchByJql(jql, maxResults)) {
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

    companion object {
        @JvmStatic
        fun getInstance(project: Project): JiraServiceImpl =
            project.getService(JiraService::class.java) as JiraServiceImpl
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
