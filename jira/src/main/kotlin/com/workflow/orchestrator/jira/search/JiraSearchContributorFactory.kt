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
import com.workflow.orchestrator.core.model.jira.JiraTicketData
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.jira.service.ActiveTicketService
import com.workflow.orchestrator.jira.service.JiraServiceImpl
import kotlinx.coroutines.runBlocking
import com.intellij.ui.components.JBLabel
import javax.swing.JList
import javax.swing.ListCellRenderer

class JiraSearchContributorFactory : SearchEverywhereContributorFactory<JiraTicketData> {
    override fun createContributor(initEvent: AnActionEvent): SearchEverywhereContributor<JiraTicketData> {
        return JiraSearchContributor(initEvent)
    }
}

private class JiraSearchContributor(
    private val initEvent: AnActionEvent
) : WeightedSearchEverywhereContributor<JiraTicketData> {

    private val log = Logger.getInstance(JiraSearchContributor::class.java)
    private val project: Project? = initEvent.project
    private val jiraService: JiraService? by lazy {
        project?.let { JiraServiceImpl.getInstance(it) }
    }

    override fun getSearchProviderId(): String = "workflow.jira.search"
    override fun getGroupName(): String = "Jira Tickets"
    override fun getSortWeight(): Int = 500
    override fun showInFindResults(): Boolean = false

    override fun getElementsRenderer(): ListCellRenderer<in JiraTicketData> {
        return ListCellRenderer<JiraTicketData> { _: JList<out JiraTicketData>?, value: JiraTicketData?, _, isSelected, _ ->
            val label = JBLabel()
            if (value != null) {
                val typeName = value.type.ifBlank { "Issue" }
                val statusName = value.status
                label.text = "[${value.key}] ${value.summary} ($statusName)"
                label.toolTipText = "$typeName - ${value.summary}"
            }
            if (isSelected) {
                label.isOpaque = true
            }
            label
        }
    }

    override fun processSelectedItem(selected: JiraTicketData, modifiers: Int, searchText: String): Boolean {
        val proj = project ?: return false
        val activeTicketService = ActiveTicketService.getInstance(proj)
        activeTicketService.setActiveTicket(selected.key, selected.summary)
        log.info("[Jira:Search] Selected ticket ${selected.key} from Search Everywhere")
        return true
    }

    override fun getDataForItem(element: JiraTicketData, dataId: String): Any? = null

    override fun fetchWeightedElements(
        pattern: String,
        progressIndicator: ProgressIndicator,
        consumer: Processor<in FoundItemDescriptor<JiraTicketData>>
    ) {
        if (pattern.length < 3) return
        val service = jiraService ?: return

        try {
            // Search Everywhere runs fetchWeightedElements on a background thread,
            // so runBlocking is acceptable here (not on EDT).
            val result = runBlocking {
                service.searchIssues(pattern, maxResults = 20)
            }

            if (progressIndicator.isCanceled) return

            if (result.isError) {
                log.warn("[Jira:Search] Search failed: ${result.summary}")
            } else {
                for (ticket in result.data) {
                    if (progressIndicator.isCanceled) return
                    consumer.process(FoundItemDescriptor(ticket, getSortWeight()))
                }
            }
        } catch (e: Exception) {
            if (progressIndicator.isCanceled) return
            log.warn("[Jira:Search] Exception during search: ${e.message}", e)
        }
    }

    override fun dispose() {}
}
