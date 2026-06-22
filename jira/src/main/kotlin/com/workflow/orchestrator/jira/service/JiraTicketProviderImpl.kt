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
import com.workflow.orchestrator.jira.api.JiraApiClient

/**
 * Implementation of [JiraTicketProvider] that delegates to [JiraApiClient].
 * Registered as an extension point so :bamboo can access Jira data without importing :jira.
 *
 * The zero-arg constructor is used by the IntelliJ extension-point mechanism.
 * The internal constructor accepting a pre-built [JiraApiClient] is used in unit tests.
 */
class JiraTicketProviderImpl : JiraTicketProvider {

    /** Shipped fallback sits at the lowest priority so any B override (default order=0) wins. */
    override val order: Int get() = Int.MAX_VALUE

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
        // Use the rich endpoint so we get renderedFields.description (Jira-Server-rendered,
        // free of wiki markup) and the structured type/labels/components needed for accurate
        // commit-type and scope inference.
        return when (val result = client.getIssueWithContext(ticketId)) {
            is ApiResult.Success -> {
                val issue = result.data
                val description = (issue.renderedFields?.description?.ifBlank { null }
                    ?: issue.fields.description)?.ifBlank { null }
                TicketDetails(
                    key = issue.key,
                    summary = issue.fields.summary,
                    description = description,
                    type = issue.fields.issuetype?.name,
                    labels = issue.fields.labels,
                    components = issue.fields.components.map { it.name }
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

}
