package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.workflow.orchestrator.core.ai.AgentChatRedirect

class AgentChatRedirectImpl : AgentChatRedirect {

    private val log = Logger.getInstance(AgentChatRedirectImpl::class.java)

    override fun sendToAgent(
        project: Project,
        prompt: String,
        filePaths: List<String>
    ) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Workflow")
        toolWindow?.activate {
            val contentManager = toolWindow.contentManager
            val agentContent = contentManager.contents.find { it.displayName == "Agent" }
            agentContent?.let { contentManager.setSelectedContent(it) }
        }

        val controller = AgentControllerRegistry.getInstance(project).controller
        if (controller == null) {
            log.warn("[AgentRedirect] AgentController not initialized — open the Agent tab first")
            return
        }
        controller.executeTaskWithMentions(prompt, filePaths)
    }
}
