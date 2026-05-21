package com.workflow.orchestrator.core.services.impl

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.ChatLink
import com.workflow.orchestrator.core.model.LinkResolution
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.services.LinkResolver
import com.workflow.orchestrator.core.services.link.ClassLinkResolver
import com.workflow.orchestrator.core.services.link.FileLinkResolver
import com.workflow.orchestrator.core.services.link.JiraLinkResolver
import com.workflow.orchestrator.core.services.link.WebLinkResolver
import com.workflow.orchestrator.core.settings.ConnectionSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class LinkResolverImpl(
    private val project: Project,
    private val cs: CoroutineScope,
) : LinkResolver {

    private val fileResolver = FileLinkResolver(project)
    private val classResolver = ClassLinkResolver(project)
    private val jiraResolver = JiraLinkResolver(
        getJiraBaseUrl = { ConnectionSettings.getInstance().state.jiraUrl.ifBlank { null } },
        notifyMissingUrl = {
            WorkflowNotificationService.getInstance(project).notifyWarning(
                WorkflowNotificationService.GROUP_JIRA,
                "Jira URL not configured",
                "Set the Jira base URL in Tools > Workflow Orchestrator settings.",
            )
        },
    )
    private val webResolver = WebLinkResolver()

    override fun resolve(link: ChatLink): LinkResolution = when (link) {
        is ChatLink.FileLink -> fileResolver.resolve(link)
        is ChatLink.ClassLink -> classResolver.resolve(link)
        is ChatLink.JiraLink -> jiraResolver.resolve(link)
        is ChatLink.WebLink -> webResolver.resolve(link)
    }

    override fun open(link: ChatLink) {
        cs.launch {
            when (link) {
                is ChatLink.FileLink -> fileResolver.open(link)
                is ChatLink.ClassLink -> classResolver.open(link)
                is ChatLink.JiraLink -> jiraResolver.open(link)
                is ChatLink.WebLink -> webResolver.open(link)
            }
        }
    }
}
