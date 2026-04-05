package com.workflow.orchestrator.agent.observability

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.ai.ModelCache
import com.workflow.orchestrator.core.ai.OpenAiCompatBrain
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings

/**
 * Generates humorous, context-aware working indicator phrases using a cheap LLM (Haiku).
 *
 * Called every ~30s while the agent is working. Fire-and-forget — returns null on any failure.
 * Uses the cheapest available model (Haiku preferred) with a 10s timeout.
 */
object HaikuPhraseGenerator {

    private val LOG = Logger.getInstance(HaikuPhraseGenerator::class.java)

    private const val SYSTEM_PROMPT = """You write short, humorous loading messages for an AI coding assistant that appear while the AI is working. Think witty dev humor — like commit messages written by a comedian. Keep it relatable to what's actually happening. No emoji. Always end with "..." """

    /**
     * Generate a humorous working phrase based on the current task context.
     *
     * @param task the user's original request (truncated to 150 chars internally)
     * @param recentTools last 2-3 tool calls as (toolName, filePath/arg) pairs
     * @return a funny one-liner (under 80 chars), or null if generation fails
     */
    suspend fun generate(task: String, recentTools: List<Pair<String, String>>): String? {
        return try {
            val connections = ConnectionSettings.getInstance()
            val sgUrl = connections.state.sourcegraphUrl.trimEnd('/')
            if (sgUrl.isBlank()) return null

            val tokenProvider = { CredentialStore().getToken(ServiceType.SOURCEGRAPH) }

            // Use cached models only — never fetch just for jokes
            val models = ModelCache.getCached()
            val cheapModel = ModelCache.pickCheapest(models)?.id ?: return null

            val brain = OpenAiCompatBrain(
                sourcegraphUrl = sgUrl,
                tokenProvider = tokenProvider,
                model = cheapModel,
                readTimeoutSeconds = 10
            )

            val toolContext = if (recentTools.isNotEmpty()) {
                recentTools.joinToString(", ") { (name, arg) -> "$name(${arg.take(40)})" }
            } else "just started"

            val userPrompt = "The user asked: \"${task.take(150)}\"\nRecent activity: $toolContext\n\nWrite ONE short funny loading message (under 60 chars):"

            val result = brain.chat(
                messages = listOf(
                    ChatMessage(role = "system", content = SYSTEM_PROMPT),
                    ChatMessage(role = "user", content = userPrompt)
                ),
                maxTokens = 60
            )

            if (result is ApiResult.Success) {
                val text = result.data.choices.firstOrNull()?.message?.content?.trim()
                    ?: return null
                // Clean up: remove surrounding quotes, cap length
                val cleaned = text
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
                    .take(80)
                if (cleaned.isBlank()) null else cleaned
            } else {
                LOG.debug("HaikuPhraseGenerator: API call failed: $result")
                null
            }
        } catch (e: Exception) {
            LOG.debug("HaikuPhraseGenerator: ${e.message}")
            null
        }
    }
}
