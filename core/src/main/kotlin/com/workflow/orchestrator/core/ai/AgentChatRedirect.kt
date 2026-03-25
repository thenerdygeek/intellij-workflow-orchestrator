package com.workflow.orchestrator.core.ai

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

interface AgentChatRedirect {
    fun sendToAgent(
        project: Project,
        prompt: String,
        filePaths: List<String> = emptyList()
    )

    companion object {
        val EP_NAME = ExtensionPointName.create<AgentChatRedirect>(
            "com.workflow.orchestrator.agentChatRedirect"
        )

        fun getInstance(): AgentChatRedirect? =
            EP_NAME.extensionList.firstOrNull()
    }
}
