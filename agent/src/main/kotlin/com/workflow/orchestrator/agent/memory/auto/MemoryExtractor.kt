package com.workflow.orchestrator.agent.memory.auto

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.security.CredentialRedactor
import com.workflow.orchestrator.core.ai.SourcegraphChatClient
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Makes lightweight LLM calls to extract structured memory from conversations.
 *
 * Uses a [SourcegraphChatClient] pre-configured with a cheap model (e.g. Haiku).
 * Not part of the agent loop — one-shot extraction, no tools, no streaming.
 * Designed to be fire-and-forget: failure is non-fatal and returns null.
 *
 * @param client Sourcegraph chat client already configured with the extraction model
 */
class MemoryExtractor(
    private val client: SourcegraphChatClient
) {
    private val log = Logger.getInstance(MemoryExtractor::class.java)

    /**
     * Extract memory insights from a completed session's conversation.
     *
     * Converts messages to role-prefixed lines, builds the extraction prompt via
     * [ExtractionPrompts] (which handles truncation), then makes a single LLM call.
     * Parses the JSON response and returns an [ExtractionResult], or null on any
     * failure (empty input, API error, empty response, parse failure).
     */
    suspend fun extractFromSession(
        messages: List<ChatMessage>,
        currentCoreMemory: Map<String, String>
    ): ExtractionResult? {
        if (messages.isEmpty()) return null

        // M9 fix: log when truncation occurs so long-session debugging has a signal.
        // ExtractionPrompts.sessionEndPrompt preserves first 5 + last 35 lines of long conversations.
        if (messages.size > ExtractionPrompts.MAX_CONVERSATION_LINES) {
            log.debug("[AutoMemory] Truncating ${messages.size} messages for extraction")
        }

        val conversationLines = messages.mapNotNull { msg ->
            val content = msg.content ?: return@mapNotNull null
            // I3 fix: redact credentials before sending to extraction LLM. Defends against
            // secrets leaked in tool output being persisted to memory or re-injected in prompts.
            val redacted = CredentialRedactor.redact(content)
            "${msg.role}: $redacted"
        }
        if (conversationLines.isEmpty()) return null

        val prompt = ExtractionPrompts.sessionEndPrompt(
            conversationLines,
            currentCoreMemory,
            LocalDate.now().toString()
        )
        val fullPrompt = "${ExtractionPrompts.EXTRACTION_SYSTEM_MESSAGE}\n\n$prompt"

        val response = client.sendMessage(
            messages = listOf(ChatMessage(role = "user", content = fullPrompt)),
            tools = null,
            maxTokens = MAX_TOKENS,
            temperature = 0.0
        )

        return when (response) {
            is ApiResult.Success -> {
                val content = response.data.choices.firstOrNull()?.message?.content
                if (content != null) {
                    val result = parseExtractionResponse(content)
                    if (result == null) {
                        log.warn("[AutoMemory] Failed to parse extraction response: ${content.take(200)}")
                    }
                    result
                } else {
                    log.warn("[AutoMemory] Empty extraction response")
                    null
                }
            }
            is ApiResult.Error -> {
                log.warn("[AutoMemory] Extraction LLM call failed: ${response.message}")
                null
            }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Max output tokens for extraction — results are small JSON. */
        private const val MAX_TOKENS = 800

        /**
         * Parse an extraction response JSON, handling markdown code fences.
         * Returns null on parse failure — extraction is best-effort.
         */
        fun parseExtractionResponse(raw: String): ExtractionResult? {
            val cleaned = stripCodeFence(raw)
            return try {
                json.decodeFromString<ExtractionResult>(cleaned)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Strip markdown code fences if present.
         * LLMs sometimes wrap JSON in ```json ... ``` despite being told not to.
         */
        private fun stripCodeFence(raw: String): String {
            val trimmed = raw.trim()
            val fencePattern = Regex("""```(?:json)?\s*\n?(.*?)\n?\s*```""", RegexOption.DOT_MATCHES_ALL)
            val match = fencePattern.find(trimmed)
            return match?.groupValues?.get(1)?.trim() ?: trimmed
        }
    }
}
