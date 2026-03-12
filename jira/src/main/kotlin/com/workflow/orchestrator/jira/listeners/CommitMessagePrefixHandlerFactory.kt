package com.workflow.orchestrator.jira.listeners

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.VcsCheckinHandlerFactory
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.service.CommitPrefixService
import git4idea.GitVcs

class CommitMessagePrefixHandlerFactory : VcsCheckinHandlerFactory(GitVcs.getKey()) {

    override fun createVcsHandler(
        panel: CheckinProjectPanel,
        commitContext: CommitContext
    ): CheckinHandler {
        return CommitMessagePrefixHandler(panel)
    }
}

private class CommitMessagePrefixHandler(
    private val panel: CheckinProjectPanel
) : CheckinHandler() {

    private val log = Logger.getInstance(CommitMessagePrefixHandler::class.java)

    override fun beforeCheckin(): ReturnResult {
        val project = panel.project
        val settings = PluginSettings.getInstance(project)
        val ticketId = settings.state.activeTicketId

        if (ticketId.isNullOrBlank()) return ReturnResult.COMMIT

        val currentMessage = panel.commitMessage
        if (currentMessage.isNullOrBlank()) return ReturnResult.COMMIT

        val prefixedMessage = CommitPrefixService.addPrefix(
            message = currentMessage,
            ticketId = ticketId,
            useConventionalCommits = settings.state.useConventionalCommits
        )

        if (prefixedMessage != currentMessage) {
            panel.setCommitMessage(prefixedMessage)
            log.info("[Jira:Commit] Prefixed commit message with $ticketId")
        }

        return ReturnResult.COMMIT
    }
}
