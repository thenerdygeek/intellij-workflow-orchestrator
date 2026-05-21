package com.workflow.orchestrator.core.services.link

import com.intellij.ide.BrowserUtil
import com.workflow.orchestrator.core.model.ChatLink
import com.workflow.orchestrator.core.model.LinkResolution

class JiraLinkResolver(
    private val getJiraBaseUrl: () -> String?,
    private val notifyMissingUrl: () -> Unit,
) {
    fun resolve(link: ChatLink.JiraLink): LinkResolution =
        LinkResolution(
            kind = LinkResolution.Kind.JIRA,
            raw = link.raw,
            displayLabel = link.ticketId,
            targetDescription = "Opens Jira ticket ${link.ticketId} in browser",
        )

    fun open(link: ChatLink.JiraLink) {
        val base = getJiraBaseUrl()?.trim()?.trimEnd('/')
        if (base.isNullOrEmpty()) {
            notifyMissingUrl()
            return
        }
        BrowserUtil.browse("$base/browse/${link.ticketId}")
    }
}
