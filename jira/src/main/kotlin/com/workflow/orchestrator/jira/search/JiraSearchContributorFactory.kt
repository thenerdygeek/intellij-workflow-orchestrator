package com.workflow.orchestrator.jira.search

import com.intellij.ide.actions.searcheverywhere.FoundItemDescriptor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.ide.actions.searcheverywhere.WeightedSearchEverywhereContributor
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.util.Processor
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import com.workflow.orchestrator.jira.service.ActiveTicketService
import kotlinx.coroutines.runBlocking
import com.intellij.ui.components.JBLabel
import javax.swing.JList
import javax.swing.ListCellRenderer

class JiraSearchContributorFactory : SearchEverywhereContributorFactory<JiraIssue> {
    override fun createContributor(initEvent: AnActionEvent): SearchEverywhereContributor<JiraIssue> {
        return JiraSearchContributor(initEvent)
    }
}

private class JiraSearchContributor(
    private val initEvent: AnActionEvent
) : WeightedSearchEverywhereContributor<JiraIssue> {

    private val log = Logger.getInstance(JiraSearchContributor::class.java)
    private val project: Project? = initEvent.project
    private val credentialStore = CredentialStore()
    private val apiClient: JiraApiClient? by lazy {
        val proj = project ?: return@lazy null
        val jiraUrl = PluginSettings.getInstance(proj).state.jiraUrl
        if (jiraUrl.isNullOrBlank()) null
        else JiraApiClient(
            baseUrl = jiraUrl.trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.JIRA) }
        )
    }

    override fun getSearchProviderId(): String = "workflow.jira.search"
    override fun getGroupName(): String = "Jira Tickets"
    override fun getSortWeight(): Int = 500
    override fun showInFindResults(): Boolean = false

    override fun getElementsRenderer(): ListCellRenderer<in JiraIssue> {
        return ListCellRenderer<JiraIssue> { _: JList<out JiraIssue>?, value: JiraIssue?, _, isSelected, _ ->
            val label = JBLabel()
            if (value != null) {
                val typeName = value.fields.issuetype?.name ?: "Issue"
                val statusName = value.fields.status.name
                label.text = "[${value.key}] ${value.fields.summary} ($statusName)"
                label.toolTipText = "$typeName - ${value.fields.summary}"
            }
            if (isSelected) {
                label.isOpaque = true
            }
            label
        }
    }

    override fun processSelectedItem(selected: JiraIssue, modifiers: Int, searchText: String): Boolean {
        val proj = project ?: return false
        val activeTicketService = ActiveTicketService.getInstance(proj)
        activeTicketService.setActiveTicket(selected.key, selected.fields.summary)
        log.info("[Jira:Search] Selected ticket ${selected.key} from Search Everywhere")
        return true
    }

    override fun getDataForItem(element: JiraIssue, dataId: String): Any? = null

    override fun fetchWeightedElements(
        pattern: String,
        progressIndicator: ProgressIndicator,
        consumer: Processor<in FoundItemDescriptor<JiraIssue>>
    ) {
        if (pattern.length < 3) return
        val client = apiClient ?: return

        try {
            // Search Everywhere runs fetchWeightedElements on a background thread,
            // so runBlocking is acceptable here (not on EDT).
            val result = runBlocking {
                client.searchIssues(pattern, maxResults = 20)
            }

            if (progressIndicator.isCanceled) return

            when (result) {
                is ApiResult.Success -> {
                    for (issue in result.data) {
                        if (progressIndicator.isCanceled) return
                        consumer.process(FoundItemDescriptor(issue, getSortWeight()))
                    }
                }
                is ApiResult.Error -> {
                    log.warn("[Jira:Search] Search failed: ${result.message}")
                }
            }
        } catch (e: Exception) {
            if (progressIndicator.isCanceled) return
            log.warn("[Jira:Search] Exception during search: ${e.message}", e)
        }
    }

    override fun dispose() {}
}
