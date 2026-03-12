package com.workflow.orchestrator.cody.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.agent.CodyAgentProviderService
import com.workflow.orchestrator.cody.protocol.*
import kotlinx.coroutines.future.await

class CodyChatService(private val project: Project) {

    suspend fun generateCommitMessage(
        diff: String,
        contextFiles: List<ContextFile> = emptyList()
    ): String? {
        val server = CodyAgentProviderService.getInstance(project).ensureRunning()
        val chatId = server.chatNew().await()
        val prompt = buildCommitMessagePrompt(diff)
        val response = server.chatSubmitMessage(
            ChatSubmitParams(
                id = chatId,
                message = ChatMessage(
                    text = prompt,
                    contextFiles = contextFiles
                )
            )
        ).await()
        return response.messages.lastOrNull { it.speaker == "assistant" }?.text
    }

    internal fun buildCommitMessagePrompt(diff: String): String =
        """Generate a concise git commit message for this diff.
           |Use conventional commits format (feat/fix/refactor/etc).
           |One line summary, optional body.
           |
           |```diff
           |$diff
           |```""".trimMargin()
}
