package com.workflow.orchestrator.core.services.link

import com.intellij.ide.BrowserUtil
import com.workflow.orchestrator.core.model.ChatLink
import com.workflow.orchestrator.core.model.LinkResolution

class JiraLinkResolver(
    private val getJiraBaseUrl: () -> String?,
    private val notifyMissingUrl: () -> Unit,
) {
    fun resolve(link: ChatLink.JiraLink): LinkResolution {
        val url = browserUrlFor(link)
        return LinkResolution(
            kind = LinkResolution.Kind.JIRA,
            raw = link.raw,
            displayLabel = link.ticketId,
            targetDescription = if (url != null)
                "Opens Jira ticket ${link.ticketId} in browser"
            else
                "Jira base URL not configured — set it in Tools > Workflow Orchestrator settings",
            browserUrl = url,
        )
    }

    fun open(link: ChatLink.JiraLink) {
        val url = browserUrlFor(link)
        if (url == null) {
            notifyMissingUrl()
            return
        }
        BrowserUtil.browse(url)
    }

    private fun browserUrlFor(link: ChatLink.JiraLink): String? {
        val base = getJiraBaseUrl()?.trim()?.trimEnd('/')
        return if (base.isNullOrEmpty()) null else "$base/browse/${link.ticketId}"
    }
}
