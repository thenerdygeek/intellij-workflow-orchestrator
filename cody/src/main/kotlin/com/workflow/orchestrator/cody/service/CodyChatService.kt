package com.workflow.orchestrator.cody.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.agent.CodyAgentProviderService
import com.workflow.orchestrator.cody.protocol.*
import kotlinx.coroutines.future.await
import java.io.File

class CodyChatService(private val project: Project) {

    private val log = Logger.getInstance(CodyChatService::class.java)

    /**
     * Send a chat message with optional context items.
     *
     * Before submitting, notifies the agent about each context file via
     * textDocument/didOpen so the agent has full file awareness.
     * Context items use a separate, higher token budget than prompt text.
     */
    suspend fun generateCommitMessage(
        prompt: String,
        contextItems: List<ContextFile> = emptyList()
    ): String? {
        val server = CodyAgentProviderService.getInstance(project).ensureRunning()

        // Notify agent about context files before submitting the chat message.
        // This matches the reference implementation (cody_agentic_tool) which calls
        // open_file() for every context file to improve agent awareness.
        for (item in contextItems) {
            val filePath = item.uri.fsPath
            try {
                val file = File(filePath)
                if (file.exists() && file.isFile) {
                    val content = file.readText()
                    server.textDocumentDidOpen(
                        ProtocolTextDocument(
                            uri = "file://$filePath",
                            content = content
                        )
                    )
                    log.info("[Cody:Chat] didOpen: $filePath (${content.length} chars)")
                } else {
                    log.warn("[Cody:Chat] Context file not found: $filePath")
                }
            } catch (e: Exception) {
                log.warn("[Cody:Chat] Failed to open context file $filePath: ${e.message}")
            }
        }

        log.info("[Cody:Chat] Submitting chat with ${contextItems.size} context items, prompt ${prompt.length} chars")
        if (contextItems.isNotEmpty()) {
            log.info("[Cody:Chat] Context items: ${contextItems.map { "${it.uri.fsPath}${it.range?.let { r -> " [${r.start.line}-${r.end.line}]" } ?: ""}" }}")
        }

        val chatId = server.chatNew().await()
        log.info("[Cody:Chat] Chat session created: $chatId")

        val response = server.chatSubmitMessage(
            ChatSubmitParams(
                id = chatId,
                message = ChatMessage(
                    text = prompt,
                    contextItems = contextItems
                )
            )
        ).await()

        val assistantMessage = response.messages.lastOrNull { it.speaker == "assistant" }?.text
        log.info("[Cody:Chat] Response: ${assistantMessage?.take(200) ?: "(empty)"}")
        return assistantMessage
    }
}
