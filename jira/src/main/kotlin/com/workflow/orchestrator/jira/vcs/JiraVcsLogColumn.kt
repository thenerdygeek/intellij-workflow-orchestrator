package com.workflow.orchestrator.jira.vcs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.JBColor
import com.intellij.vcs.log.ui.table.GraphTableModel
import com.intellij.vcs.log.ui.table.VcsLogGraphTable
import com.intellij.vcs.log.ui.table.column.VcsLogCustomColumn
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.api.JiraApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.Component
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * Adds a "Jira Ticket" column to the Git Log view.
 * Extracts ticket IDs from commit messages and displays the summary + status.
 * Ticket metadata is fetched lazily and cached with a 10-minute TTL.
 */
class JiraVcsLogColumn : VcsLogCustomColumn<String> {

    private val log = Logger.getInstance(JiraVcsLogColumn::class.java)
    private val cache = TicketCache(maxSize = 500, ttlMs = 600_000)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingFetches = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val credentialStore by lazy { CredentialStore() }
    private val clientCache = java.util.concurrent.ConcurrentHashMap<String, JiraApiClient>()

    override val id: String = "jira.ticket"

    override val localizedName: String = "Jira Ticket"

    override val isDynamic: Boolean = true

    override fun isEnabledByDefault(): Boolean = true

    override fun getStubValue(model: GraphTableModel): String = ""

    override fun getValue(model: GraphTableModel, row: Int): String {
        val commitMetadata = model.getCommitMetadata(row) ?: return ""
        val ticketId = TicketIdExtractor.extract(commitMetadata.fullMessage) ?: return ""

        val cached = cache.get(ticketId)
        if (cached != null) {
            return "$ticketId | ${cached.summary} (${cached.statusName})"
        }

        // Schedule async fetch if not already pending
        if (ticketId !in pendingFetches) {
            pendingFetches.add(ticketId)
            val project = model.logData.project
            val settings = PluginSettings.getInstance(project)
            val baseUrl = settings.state.jiraUrl.orEmpty().trimEnd('/')
            if (baseUrl.isNotBlank()) {
                scope.launch {
                    try {
                        val client = clientCache.getOrPut(baseUrl) {
                            JiraApiClient(baseUrl) { credentialStore.getToken(ServiceType.JIRA) }
                        }
                        when (val result = client.getIssue(ticketId)) {
                            is ApiResult.Success -> {
                                cache.put(ticketId, TicketCacheEntry(
                                    ticketId,
                                    result.data.fields.summary,
                                    result.data.fields.status.name
                                ))
                            }
                            is ApiResult.Error -> {
                                log.debug("Could not fetch $ticketId: ${result.message}")
                            }
                        }
                    } finally {
                        pendingFetches.remove(ticketId)
                    }
                }
            }
        }

        return ticketId
    }

    override fun createTableCellRenderer(table: VcsLogGraphTable): TableCellRenderer {
        return object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean,
                hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                val text = value?.toString() ?: ""
                if (text.contains("(") && text.contains(")") && !isSelected) {
                    val status = text.substringAfterLast("(").substringBefore(")")
                    foreground = when {
                        status.contains("Done", ignoreCase = true) ||
                        status.contains("Closed", ignoreCase = true) ||
                        status.contains("Resolved", ignoreCase = true) -> JBColor(java.awt.Color(0x59, 0xA6, 0x0F), java.awt.Color(0x6C, 0xC6, 0x44))
                        status.contains("Progress", ignoreCase = true) -> JBColor(java.awt.Color(0x40, 0x7E, 0xC9), java.awt.Color(0x58, 0x9D, 0xF6))
                        else -> table.foreground
                    }
                }
                return component
            }
        }
    }
}
