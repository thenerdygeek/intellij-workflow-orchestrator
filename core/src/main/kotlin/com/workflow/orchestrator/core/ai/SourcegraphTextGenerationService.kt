package com.workflow.orchestrator.core.ai

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.prompts.PrDescriptionPromptBuilder
import com.workflow.orchestrator.core.ai.prompts.PrTitlePromptBuilder
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.workflow.TicketContext
import java.io.File

class SourcegraphTextGenerationService : TextGenerationService {

    private val log = Logger.getInstance(SourcegraphTextGenerationService::class.java)

    override suspend fun generateText(
        project: Project,
        prompt: String,
        contextFilePaths: List<String>
    ): String? {
        if (!LlmBrainFactory.isAvailable()) return null
        val brain = LlmBrainFactory.create(project)
        val maxTokens = AiSettings.getInstance(project).state.maxOutputTokens

        val contextBlock = contextFilePaths.mapNotNull { path ->
            val file = File(path)
            if (file.exists() && file.isFile) {
                val content = file.bufferedReader().use { r ->
                    val buf = CharArray(50_000)
                    val len = r.read(buf)
                    if (len > 0) String(buf, 0, len) else ""
                }
                "File: ${file.name}\n```\n$content\n```"
            } else null
        }.joinToString("\n\n")

        val fullPrompt = if (contextBlock.isNotBlank()) "$contextBlock\n\n$prompt" else prompt
        val messages = listOf(ChatMessage(role = "user", content = fullPrompt))

        return when (val result = brain.chat(messages, tools = null, maxTokens = maxTokens)) {
            is ApiResult.Success -> {
                val text = result.data.choices.firstOrNull()?.message?.content
                log.info("[AI:TextGen] Generated ${text?.length ?: 0} chars")
                text
            }
            is ApiResult.Error -> {
                log.warn("[AI:TextGen] Failed: ${result.message}")
                null
            }
        }
    }

    override suspend fun generatePrDescription(
        project: Project,
        diff: String,
        commitMessages: List<String>,
        contextFilePaths: List<String>,
        tickets: List<TicketContext>,
        sourceBranch: String,
        targetBranch: String
    ): String? {
        if (!LlmBrainFactory.isAvailable()) return null
        val brain = LlmBrainFactory.create(project)
        val maxTokens = AiSettings.getInstance(project).state.maxOutputTokens

        val prompt = PrDescriptionPromptBuilder.build(
            diff = diff,
            commitMessages = commitMessages,
            tickets = tickets,
            sourceBranch = sourceBranch,
            targetBranch = targetBranch
        )
        val messages = listOf(ChatMessage(role = "user", content = prompt))

        return when (val result = brain.chat(messages, tools = null, maxTokens = maxTokens)) {
            is ApiResult.Success -> {
                result.data.choices.firstOrNull()?.message?.content
                    ?.replace(Regex("^```[a-z]*\\n?"), "")
                    ?.replace(Regex("\\n?```$"), "")
                    ?.trim()
            }
            is ApiResult.Error -> {
                log.warn("[AI:PrDesc] Failed: ${result.message}")
                null
            }
        }
    }

    override suspend fun generatePrTitle(
        project: Project,
        ticket: TicketContext,
        commitMessages: List<String>
    ): String? {
        if (!LlmBrainFactory.isAvailable()) return null
        val brain = LlmBrainFactory.create(project)
        val maxTokens = AiSettings.getInstance(project).state.maxOutputTokens

        val prompt = PrTitlePromptBuilder.build(ticket, commitMessages)
        val messages = listOf(ChatMessage(role = "user", content = prompt))

        return when (val result = brain.chat(messages, tools = null, maxTokens = maxTokens)) {
            is ApiResult.Success -> {
                result.data.choices.firstOrNull()?.message?.content
                    ?.replace(Regex("^```[a-z]*\\n?"), "")
                    ?.replace(Regex("\\n?```$"), "")
                    ?.trim()
                    // Title must be a single line — take the first non-blank line in case the model
                    // returns a preamble or trailing explanation despite the prompt's "output only" rule.
                    ?.lines()
                    ?.firstOrNull { it.isNotBlank() }
                    ?.trim()
            }
            is ApiResult.Error -> {
                log.warn("[AI:PrTitle] Failed: ${result.message}")
                null
            }
        }
    }
}
