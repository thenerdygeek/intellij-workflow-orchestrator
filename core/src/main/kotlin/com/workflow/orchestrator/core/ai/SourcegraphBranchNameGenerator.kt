package com.workflow.orchestrator.core.ai

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.model.ApiResult

class SourcegraphBranchNameGenerator : BranchNameAiGenerator {

    private val log = Logger.getInstance(SourcegraphBranchNameGenerator::class.java)

    override suspend fun generateBranchSlug(
        project: Project,
        ticketKey: String,
        title: String,
        description: String?
    ): String? {
        if (!LlmBrainFactory.isAvailable()) return null
        val brain = LlmBrainFactory.create(project)

        val prompt = buildString {
            appendLine("Generate a short git branch slug for this Jira ticket.")
            appendLine("Ticket: $ticketKey")
            appendLine("Title: $title")
            if (!description.isNullOrBlank()) {
                appendLine("Description: ${description.take(300)}")
            }
            appendLine()
            appendLine("Output ONLY the slug (lowercase, hyphens, max 50 chars, no ticket key prefix).")
            appendLine("Example: fix-null-pointer-order-service")
        }

        val messages = listOf(ChatMessage(role = "user", content = prompt))
        // Thinking-capable models require max_tokens > thinking.budget_tokens or Sourcegraph
        // rejects the request. Use the user-configured ceiling (16K default).
        val maxTokens = AiSettings.getInstance(project).state.maxOutputTokens
        return when (val result = brain.chat(messages, tools = null, maxTokens = maxTokens)) {
            is ApiResult.Success -> {
                val raw = result.data.choices.firstOrNull()?.message?.content?.trim()
                raw?.removeSurrounding("\"")
                    ?.removeSurrounding("`")
                    ?.lowercase()
                    ?.replace(Regex("[^a-z0-9-]"), "-")
                    ?.replace(Regex("-+"), "-")
                    ?.trim('-')
                    ?.take(50)
                    .also { log.info("[AI:BranchName] Generated slug: $it") }
            }
            is ApiResult.Error -> {
                log.warn("[AI:BranchName] Failed: ${result.message}")
                null
            }
        }
    }
}
