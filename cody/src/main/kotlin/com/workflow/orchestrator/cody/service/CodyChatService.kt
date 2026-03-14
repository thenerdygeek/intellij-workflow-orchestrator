package com.workflow.orchestrator.cody.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.agent.CodyAgentProviderService
import com.workflow.orchestrator.cody.protocol.*
import kotlinx.coroutines.future.await

class CodyChatService(private val project: Project) {

    /**
     * Generate a commit message. The prompt contains only instructions and metadata.
     * The actual file content and diff are sent as contextItems so they use
     * Cody's higher context-file token budget instead of the input token limit.
     */
    suspend fun generateCommitMessage(
        prompt: String,
        contextItems: List<ContextFile> = emptyList()
    ): String? {
        val server = CodyAgentProviderService.getInstance(project).ensureRunning()
        val chatId = server.chatNew().await()
        val response = server.chatSubmitMessage(
            ChatSubmitParams(
                id = chatId,
                message = ChatMessage(
                    text = prompt,
                    contextItems = contextItems
                )
            )
        ).await()
        return response.messages.lastOrNull { it.speaker == "assistant" }?.text
    }
}
