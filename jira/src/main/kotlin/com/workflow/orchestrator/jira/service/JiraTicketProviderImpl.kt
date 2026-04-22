package com.workflow.orchestrator.jira.service

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.workflow.JiraTicketProvider
import com.workflow.orchestrator.core.workflow.TicketComment
import com.workflow.orchestrator.core.workflow.TicketContext
import com.workflow.orchestrator.core.workflow.TicketDetails
import com.workflow.orchestrator.core.workflow.TicketTransition
import com.intellij.openapi.progress.runBackgroundableTask
import com.workflow.orchestrator.jira.api.JiraApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Implementation of [JiraTicketProvider] that delegates to [JiraApiClient].
 * Registered as an extension point so :bamboo can access Jira data without importing :jira.
 *
 * The zero-arg constructor is used by the IntelliJ extension-point mechanism.
 * The internal constructor accepting a pre-built [JiraApiClient] is used in unit tests.
 */
class JiraTicketProviderImpl : JiraTicketProvider {

    private val log = Logger.getInstance(JiraTicketProviderImpl::class.java)

    /** Fixed client injected in tests; null means [createClient] will build one lazily. */
    @Suppress("CanBePrivate")
    internal var testClient: JiraApiClient? = null

    /** Cache: pair of (jiraUrl, client) so we never build more than one OkHttpClient per URL. */
    @Volatile private var cachedClient: Pair<String, JiraApiClient>? = null

    private fun createClient(): JiraApiClient? {
        testClient?.let { return it }
        val url = ConnectionSettings.getInstance().state.jiraUrl.orEmpty().trimEnd('/')
        if (url.isBlank()) return null
        cachedClient?.let { (cachedUrl, c) -> if (cachedUrl == url) return c }
        val credentialStore = CredentialStore()
        val c = JiraApiClient(
            baseUrl = url,
            tokenProvider = { credentialStore.getToken(ServiceType.JIRA) }
        )
        cachedClient = url to c
        return c
    }

    override suspend fun getTicketDetails(ticketId: String): TicketDetails? {
        val client = createClient() ?: return null
        return when (val result = client.getIssue(ticketId)) {
            is ApiResult.Success -> {
                val issue = result.data
                TicketDetails(
                    key = issue.key,
                    summary = issue.fields.summary,
                    description = issue.fields.description,
                    type = issue.fields.issuetype?.name
                )
            }
            is ApiResult.Error -> {
                log.warn("[Jira:TicketProvider] Failed to get ticket $ticketId: ${result.message}")
                null
            }
        }
    }

    override suspend fun getTicketContext(key: String): TicketContext? {
        val client = createClient() ?: return null
        val acFieldId = resolveAcceptanceCriteriaFieldId()
        return when (val result = client.getIssueWithContext(key)) {
            is ApiResult.Success -> {
                val issue = result.data
                val fields = issue.fields

                // Prefer rendered description (strips Jira wiki markup server-side)
                val description = (issue.renderedFields?.description?.ifBlank { null }
                    ?: fields.description)?.ifBlank { null }

                // Acceptance criteria from configurable custom field (best-effort)
                val acceptanceCriteria = if (acFieldId != null) {
                    resolveCustomField(client, key, acFieldId)
                } else {
                    null
                }

                TicketContext(
                    key = issue.key,
                    summary = fields.summary,
                    description = description,
                    status = fields.status.name,
                    priority = fields.priority?.name,
                    issueType = fields.issuetype?.name,
                    assignee = fields.assignee?.displayName,
                    reporter = fields.reporter?.displayName,
                    labels = fields.labels,
                    components = fields.components.map { it.name },
                    fixVersions = fields.fixVersions.map { it.name },
                    comments = fields.comment?.comments.orEmpty().map { c ->
                        TicketComment(
                            author = c.author?.displayName ?: "",
                            created = c.created,
                            body = c.body
                        )
                    },
                    acceptanceCriteria = acceptanceCriteria
                )
            }
            is ApiResult.Error -> {
                log.warn("[Jira:TicketProvider] Failed to get ticket context for $key: ${result.message}")
                null
            }
        }
    }

    /**
     * Reads the acceptance-criteria custom field ID from [PluginSettings].
     * Returns null if unset or blank.
     *
     * Overrideable in tests via [testAcceptanceCriteriaFieldId].
     */
    @Suppress("CanBePrivate")
    internal var testAcceptanceCriteriaFieldId: String? = null
    /** Set to true in tests to skip the ConnectionSettings lookup. */
    internal var useTestAcceptanceCriteriaFieldId: Boolean = false

    private fun resolveAcceptanceCriteriaFieldId(): String? {
        if (useTestAcceptanceCriteriaFieldId) return testAcceptanceCriteriaFieldId
        // TODO: multi-project ambiguity — same as pre-Fix-1 ProjectManager usage.
        // Fix 3 is a scope/consistency fix (move field to PluginSettings); resolving
        // which project's settings to read is a separate concern.
        val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
            ?: return null
        val id = PluginSettings.getInstance(project).state.jiraAcceptanceCriteriaFieldId
        return if (id.isNullOrBlank()) null else id
    }

    /**
     * Fetches the value of a custom field for [issueKey] by doing a targeted single-field
     * issue fetch. Returns null if the field is absent or the request fails.
     */
    private suspend fun resolveCustomField(
        client: com.workflow.orchestrator.jira.api.JiraApiClient,
        issueKey: String,
        fieldId: String
    ): String? {
        return try {
            val result = client.getCustomFieldValue(issueKey, fieldId)
            when (result) {
                is ApiResult.Success -> result.data
                is ApiResult.Error -> {
                    log.warn("[Jira:TicketProvider] Could not read custom field $fieldId for $issueKey: ${result.message}")
                    null
                }
            }
        } catch (e: Exception) {
            log.warn("[Jira:TicketProvider] Exception reading custom field $fieldId for $issueKey: ${e.message}")
            null
        }
    }

    override suspend fun getAvailableTransitions(ticketId: String): List<TicketTransition> {
        val client = createClient() ?: return emptyList()
        return when (val result = client.getTransitions(ticketId)) {
            is ApiResult.Success -> result.data.map { t ->
                TicketTransition(
                    id = t.id,
                    name = t.name,
                    targetStatus = t.toStatus.name
                )
            }
            is ApiResult.Error -> {
                log.warn("[Jira:TicketProvider] Failed to get transitions for $ticketId: ${result.message}")
                emptyList()
            }
        }
    }

    override suspend fun transitionTicket(ticketId: String, transitionId: String): Boolean {
        val client = createClient() ?: return false
        return when (val result = client.transitionIssue(ticketId, transitionId)) {
            is ApiResult.Success -> true
            is ApiResult.Error -> {
                log.warn("[Jira:TicketProvider] Failed to transition $ticketId: ${result.message}")
                false
            }
        }
    }

    override fun showTransitionDialog(
        project: com.intellij.openapi.project.Project,
        ticketId: String,
        onTransitioned: () -> Unit
    ) {
        val client = createClient() ?: return

        runBackgroundableTask("Loading transitions for $ticketId", project, false) {
            val result = runBlocking(Dispatchers.IO) {
                client.getTransitions(ticketId)
            }
            val transitions = when (result) {
                is ApiResult.Success -> result.data
                is ApiResult.Error -> {
                    log.warn("[Jira:TicketProvider] Failed to get transitions for $ticketId")
                    return@runBackgroundableTask
                }
            }

            com.intellij.openapi.application.invokeLater {
                if (transitions.isEmpty()) {
                    log.warn("[Jira:TicketProvider] No transitions available for $ticketId")
                    return@invokeLater
                }

                if (transitions.size == 1 && transitions[0].fields.isEmpty()) {
                    // Single transition, no required fields — execute directly
                    runBackgroundableTask("Transitioning $ticketId", project, false) {
                        runBlocking(Dispatchers.IO) {
                            transitionTicket(ticketId, transitions[0].id)
                        }
                        com.intellij.openapi.application.invokeLater { onTransitioned() }
                    }
                } else {
                    // Show popup to pick transition, then show dialog if fields required
                    val popup = javax.swing.JPopupMenu()
                    for (transition in transitions) {
                        val item = javax.swing.JMenuItem(transition.name)
                        item.addActionListener {
                            val hasRequiredFields = transition.fields.any { it.required }
                            if (hasRequiredFields) {
                                com.workflow.orchestrator.jira.ui.TransitionDialog(
                                    project, ticketId, transition, onTransitioned
                                ).show()
                            } else {
                                runBackgroundableTask("Transitioning $ticketId", project, false) {
                                    runBlocking(Dispatchers.IO) {
                                        transitionTicket(ticketId, transition.id)
                                    }
                                    com.intellij.openapi.application.invokeLater { onTransitioned() }
                                }
                            }
                        }
                        popup.add(item)
                    }
                    // Show popup relative to the focused component
                    val focusOwner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                    if (focusOwner != null) {
                        popup.show(focusOwner, 0, focusOwner.height)
                    } else {
                        // Fallback: show relative to the IDE frame
                        val frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)
                        if (frame != null) {
                            val mousePos = java.awt.MouseInfo.getPointerInfo().location
                            javax.swing.SwingUtilities.convertPointFromScreen(mousePos, frame)
                            popup.show(frame, mousePos.x, mousePos.y)
                        }
                    }
                }
            }
        }
    }
}
