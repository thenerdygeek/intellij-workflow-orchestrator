package com.workflow.orchestrator.core.ai

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.prompts.PrDescriptionPromptBuilder
import com.workflow.orchestrator.core.ai.prompts.PrTitlePromptBuilder
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
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
        targetBranch: String,
        diffStat: String,
        onPartial: (suspend (String) -> Unit)?
    ): String? = when (val outcome = generatePrDescriptionTyped(
        project, diff, commitMessages, contextFilePaths, tickets,
        sourceBranch, targetBranch, diffStat, diffCap = null, onPartial = onPartial
    )) {
        is TextGenerationOutcome.Success -> outcome.text
        else -> null
    }

    override suspend fun generatePrDescriptionTyped(
        project: Project,
        diff: String,
        commitMessages: List<String>,
        contextFilePaths: List<String>,
        tickets: List<TicketContext>,
        sourceBranch: String,
        targetBranch: String,
        diffStat: String,
        diffCap: Int?,
        onPartial: (suspend (String) -> Unit)?
    ): TextGenerationOutcome {
        if (!LlmBrainFactory.isAvailable()) return TextGenerationOutcome.Other(null, "LLM not configured")
        val brain = LlmBrainFactory.create(project)
        val maxTokens = AiSettings.getInstance(project).state.maxOutputTokens

        val prompt = PrDescriptionPromptBuilder.build(
            diff = diff,
            commitMessages = commitMessages,
            tickets = tickets,
            sourceBranch = sourceBranch,
            targetBranch = targetBranch,
            diffStat = diffStat,
            diffCap = diffCap ?: PrDescriptionPromptBuilder.DEFAULT_DIFF_CAP
        )
        val messages = listOf(ChatMessage(role = "user", content = prompt))

        val accumulated = StringBuilder()
        val result = brain.chatStream(messages, tools = null, maxTokens = maxTokens) { chunk ->
            val delta = chunk.choices.firstOrNull()?.delta?.content
            if (delta != null) {
                accumulated.append(delta)
                onPartial?.invoke(accumulated.toString())
            }
        }
        return when (result) {
            is ApiResult.Success -> {
                val text = accumulated.toString()
                    .replace(Regex("^```[a-z]*\\n?"), "")
                    .replace(Regex("\\n?```$"), "")
                    .trim()
                if (text.isBlank()) TextGenerationOutcome.Other(null, "empty response")
                else TextGenerationOutcome.Success(text)
            }
            is ApiResult.Error -> {
                log.warn("[AI:PrDesc] Failed: type=${result.type} ${result.message}")
                if (result.type == ErrorType.CONTEXT_LENGTH_EXCEEDED) {
                    TextGenerationOutcome.ContextOverflow
                } else {
                    TextGenerationOutcome.Other(result.type, result.message)
                }
            }
        }
    }

    override suspend fun generatePrTitle(
        project: Project,
        ticket: TicketContext,
        commitMessages: List<String>,
        onPartial: (suspend (String) -> Unit)?
    ): String? {
        if (!LlmBrainFactory.isAvailable()) return null
        val brain = LlmBrainFactory.create(project)
        val maxTokens = AiSettings.getInstance(project).state.maxOutputTokens

        val prompt = PrTitlePromptBuilder.build(ticket, commitMessages)
        val messages = listOf(ChatMessage(role = "user", content = prompt))

        val accumulated = StringBuilder()
        val result = brain.chatStream(messages, tools = null, maxTokens = maxTokens) { chunk ->
            val delta = chunk.choices.firstOrNull()?.delta?.content
            if (delta != null) {
                accumulated.append(delta)
                // Title must be a single line — surface the first non-blank line of the
                // accumulated text so the partial UI never shows mid-stream junk.
                val partial = accumulated.toString()
                    .lines()
                    .firstOrNull { it.isNotBlank() }
                    ?.trim()
                    .orEmpty()
                if (partial.isNotEmpty()) onPartial?.invoke(partial)
            }
        }
        return when (result) {
            is ApiResult.Success -> accumulated.toString()
                .replace(Regex("^```[a-z]*\\n?"), "")
                .replace(Regex("\\n?```$"), "")
                .trim()
                .lines()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
            is ApiResult.Error -> {
                log.warn("[AI:PrTitle] Failed: ${result.message}")
                null
            }
        }
    }
}
