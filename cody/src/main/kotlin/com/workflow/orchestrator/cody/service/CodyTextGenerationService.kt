package com.workflow.orchestrator.cody.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.agent.CodyAgentProviderService
import com.workflow.orchestrator.cody.protocol.ChatMessage
import com.workflow.orchestrator.cody.protocol.ChatSubmitParams
import com.workflow.orchestrator.cody.protocol.ContextFile
import com.workflow.orchestrator.core.ai.TextGenerationService
import kotlinx.coroutines.future.await

/**
 * Implementation of [TextGenerationService] using the Cody CLI agent.
 * Registered as an extension point so :bamboo can generate text without importing :cody.
 */
class CodyTextGenerationService : TextGenerationService {

    private val log = Logger.getInstance(CodyTextGenerationService::class.java)

    override suspend fun generateText(
        project: Project,
        prompt: String,
        contextFilePaths: List<String>
    ): String? {
        return try {
            val server = CodyAgentProviderService.getInstance(project).ensureRunning()
            val chatId = server.chatNew().await()

            val contextItems = contextFilePaths.map { ContextFile.fromPath(it) }

            val response = server.chatSubmitMessage(
                ChatSubmitParams(
                    id = chatId,
                    message = ChatMessage(
                        text = prompt,
                        contextItems = contextItems
                    )
                )
            ).await()

            response.messages.lastOrNull { it.speaker == "assistant" }?.text
        } catch (e: Exception) {
            log.warn("[Cody:TextGen] Text generation failed: ${e.message}")
            null
        }
    }

    override suspend fun generatePrDescription(
        project: Project,
        diff: String,
        commitMessages: List<String>,
        contextFilePaths: List<String>,
        ticketId: String,
        ticketSummary: String,
        ticketDescription: String,
        sourceBranch: String,
        targetBranch: String
    ): String? {
        return try {
            val contextItems = contextFilePaths.map { ContextFile.fromPath(it) }
            CodyChatService(project).generatePrDescriptionChained(
                diff = diff,
                commitMessages = commitMessages,
                contextItems = contextItems,
                ticketId = ticketId,
                ticketSummary = ticketSummary,
                ticketDescription = ticketDescription,
                sourceBranch = sourceBranch,
                targetBranch = targetBranch
            )
        } catch (e: Exception) {
            log.warn("[Cody:TextGen] PR description chain failed: ${e.message}")
            null
        }
    }
}
