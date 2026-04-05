package com.workflow.orchestrator.agent.observability

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.ai.ModelCache
import com.workflow.orchestrator.core.ai.OpenAiCompatBrain
import com.workflow.orchestrator.core.ai.SourcegraphChatClient
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

    private const val SYSTEM_PROMPT = """You generate one short, funny loading message for a developer IDE. No preamble, no quotes, no explanation — just the message text ending with "..."

VOICE: You are a tired, self-aware senior dev with mass Slack energy. Deadpan, self-deprecating, observational. Funniest person in the standup channel, not a corporate chatbot.

RULES:
- Every message has a SETUP (familiar dev situation) then a TWIST (unexpected, specific, true)
- The funny word goes LAST — that is where the snap is
- Be SPECIFIC: "The PR with 47 files and the message 'small fix'" not "The PR"
- Reference REAL pain: merge conflicts, flaky tests, Jira estimates, TODO comments from 2019, the one file nobody dares touch, code reviews at 4:59pm
- Maximum 12 words. Shorter hits harder.
- First person ("I", "we") or second person ("your") only. Never narrate in third person.
- End with "..."

NEVER:
- "Verbing the noun..." format (e.g. "Compiling thoughts...", "Debugging reality...", "Optimizing vibes...")
- Puns or wordplay — they read as dad jokes
- The words "magic", "wizard", or "journey"
- Anything a LinkedIn post would say about coding
- Generic filler that could apply to any task

EXAMPLES OF THE EXACT TONE:
- "The tests pass. I don't know why. Don't ask..."
- "Whoever wrote this owes me an apology. Oh wait, it was me..."
- "Sprint planning: the fiction we agree to believe..."
- "Your TODO from 2019 says 'fix later'. It's later..."
- "git blame says it was me all along..."
- "Jira says this was a 2-point story. Jira lies..."
- "Rebasing. Praying. Same thing really..."
- "The deployment succeeded first try. I'm suspicious..."
- "Code review at 4:58pm on a Friday. Bold..."
- "The intern's code works. The architect's doesn't. Classic..." """

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
            if (tokenProvider() == null) return null

            // Use cached models, but populate cache if empty and credentials are available
            var models = ModelCache.getCached()
            if (models.isEmpty()) {
                val client = SourcegraphChatClient(
                    baseUrl = sgUrl,
                    tokenProvider = tokenProvider,
                    model = ""
                )
                models = ModelCache.getModels(client)
            }
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

            val userPrompt = "Task: \"${task.take(150)}\"\nI just did: $toolContext\n\nOne funny message about what I'm actually doing (under 12 words):"

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
