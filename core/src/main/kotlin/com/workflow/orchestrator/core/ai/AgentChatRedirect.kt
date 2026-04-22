package com.workflow.orchestrator.core.ai

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

interface AgentChatRedirect {
    fun sendToAgent(
        project: Project,
        prompt: String,
        filePaths: List<String> = emptyList()
    )

    /**
     * Start a fresh agent session pre-seeded with [initialMessage].
     *
     * The implementation resets the current chat, then submits the initial message.
     * [persona] and [sessionTag] are passed through to the controller for observability;
     * persona guidance is expected to be embedded in [initialMessage] by the caller.
     *
     * Default implementation delegates to [sendToAgent] for backward compatibility
     * with existing implementations that haven't overridden this method.
     */
    fun startPrReviewSession(
        project: Project,
        persona: String,
        initialMessage: String,
        sessionTag: String,
    ) {
        // Default: delegate to sendToAgent — implementations should override for new-chat semantics
        sendToAgent(project, initialMessage)
    }

    companion object {
        val EP_NAME = ExtensionPointName.create<AgentChatRedirect>(
            "com.workflow.orchestrator.agentChatRedirect"
        )

        fun getInstance(): AgentChatRedirect? =
            EP_NAME.extensionList.firstOrNull()
    }
}
