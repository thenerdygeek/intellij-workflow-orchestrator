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
 * Lightweight Haiku-powered text generation for UI embellishments:
 * - Humorous working indicator phrases (every ~30s while agent works)
 * - Conversation title generation and scope-change detection
 *
 * Fire-and-forget — returns null on any failure.
 * Uses the cheapest available model (Haiku preferred) with a 10s timeout.
 */
object HaikuPhraseGenerator {

    private val LOG = Logger.getInstance(HaikuPhraseGenerator::class.java)

    // ── Working phrase prompt ────────────────────────────────────────────

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

    // ── Title generation prompts ──────────────────────────────────────

    private const val TITLE_SYSTEM_PROMPT = """You generate short, descriptive titles for coding conversations. No preamble, no quotes, no explanation — just the title text.

RULES:
- Under 50 characters
- Descriptive and scannable — a developer should know what this conversation is about at a glance
- Use the format: "Verb + specific thing" (e.g. "Fix auth token expiry in LoginService")
- Be specific: "Add retry logic to API client" not "Work on code"
- No punctuation at the end
- No quotes around the title"""

    private const val TITLE_CHECK_SYSTEM_PROMPT = """You decide whether a conversation's title still fits after a new message. Respond with ONLY one of:
- KEEP — if the new message is still about the same topic
- A new title (under 50 chars) — if the scope has clearly shifted

Be conservative: minor follow-ups, refinements, or related questions are NOT a scope change. Only generate a new title when the user has moved to a genuinely different task."""

    // ── Shared brain creation ───────────────────────────────────────

    private suspend fun createBrain(tag: String): OpenAiCompatBrain? {
        val connections = ConnectionSettings.getInstance()
        val sgUrl = connections.state.sourcegraphUrl.trimEnd('/')
        if (sgUrl.isBlank()) return null

        val tokenProvider = { CredentialStore().getToken(ServiceType.SOURCEGRAPH) }
        if (tokenProvider() == null) {
            LOG.info("[$tag] No Sourcegraph token available")
            return null
        }

        var models = ModelCache.getCached()
        if (models.isEmpty()) {
            LOG.info("[$tag] Model cache empty, fetching models...")
            val client = SourcegraphChatClient(
                baseUrl = sgUrl,
                tokenProvider = tokenProvider,
                model = ""
            )
            models = ModelCache.getModels(client)
        }
        val cheapModel = ModelCache.pickCheapest(models)?.id
        if (cheapModel == null) {
            LOG.info("[$tag] No cheap model found (${models.size} models cached)")
            return null
        }
        LOG.info("[$tag] Using model: $cheapModel")

        return OpenAiCompatBrain(
            sourcegraphUrl = sgUrl,
            tokenProvider = tokenProvider,
            model = cheapModel,
            readTimeoutSeconds = 10
        )
    }

    private fun extractText(result: ApiResult<*>, tag: String): String? {
        if (result !is ApiResult.Success) {
            LOG.info("[$tag] API call failed: $result")
            return null
        }
        @Suppress("UNCHECKED_CAST")
        val response = result as ApiResult.Success<com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse>
        val text = response.data.choices.firstOrNull()?.message?.content?.trim()
        if (text.isNullOrBlank()) {
            LOG.info("[$tag] Empty response from API")
            return null
        }
        return text
    }

    // ── Working phrase generation ───────────────────────────────────

    /**
     * Generate a humorous working phrase based on the current task context.
     *
     * @param task the user's original request (truncated to 150 chars internally)
     * @param recentTools last 2-3 tool calls as (toolName, filePath/arg) pairs
     * @return a funny one-liner (under 80 chars), or null if generation fails
     */
    suspend fun generate(task: String, recentTools: List<Pair<String, String>>): String? {
        return try {
            val brain = createBrain("HaikuPhrase") ?: return null

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

            val text = extractText(result, "HaikuPhrase") ?: return null
            val cleaned = text.removeSurrounding("\"").removeSurrounding("'").take(80)
            LOG.info("[HaikuPhrase] Generated: $cleaned")
            if (cleaned.isBlank()) null else cleaned
        } catch (e: Exception) {
            LOG.info("[HaikuPhrase] Exception: ${e.message}")
            null
        }
    }

    // ── Conversation title generation ───────────────────────────────

    /**
     * Generate a concise, descriptive title for a conversation from the user's first message.
     *
     * @param task the user's message
     * @return a title under 50 chars, or null if generation fails
     */
    suspend fun generateTitle(task: String): String? {
        return try {
            val brain = createBrain("HaikuTitle") ?: return null

            val result = brain.chat(
                messages = listOf(
                    ChatMessage(role = "system", content = TITLE_SYSTEM_PROMPT),
                    ChatMessage(role = "user", content = "Generate a title for this task: ${task.take(200)}")
                ),
                maxTokens = 40
            )

            val text = extractText(result, "HaikuTitle") ?: return null
            val cleaned = text.removeSurrounding("\"").removeSurrounding("'").take(50)
            LOG.info("[HaikuTitle] Generated: $cleaned")
            if (cleaned.isBlank()) null else cleaned
        } catch (e: Exception) {
            LOG.info("[HaikuTitle] Exception: ${e.message}")
            null
        }
    }

    /**
     * Check if a new message has shifted the conversation scope enough to warrant a new title.
     *
     * @param currentTitle the current conversation title
     * @param newMessage the new user message
     * @return a new title if scope changed, null if the current title still fits
     */
    suspend fun checkTitleUpdate(currentTitle: String, newMessage: String): String? {
        return try {
            val brain = createBrain("HaikuTitleCheck") ?: return null

            val result = brain.chat(
                messages = listOf(
                    ChatMessage(role = "system", content = TITLE_CHECK_SYSTEM_PROMPT),
                    ChatMessage(role = "user", content = "Current title: \"$currentTitle\"\nNew message: \"${newMessage.take(200)}\"")
                ),
                maxTokens = 40
            )

            val text = extractText(result, "HaikuTitleCheck") ?: return null
            val cleaned = text.removeSurrounding("\"").removeSurrounding("'").trim()

            if (cleaned.equals("KEEP", ignoreCase = true) || cleaned.startsWith("KEEP")) {
                LOG.info("[HaikuTitleCheck] Scope unchanged, keeping: $currentTitle")
                null
            } else {
                val newTitle = cleaned.take(50)
                LOG.info("[HaikuTitleCheck] Scope changed: '$currentTitle' → '$newTitle'")
                newTitle
            }
        } catch (e: Exception) {
            LOG.info("[HaikuTitleCheck] Exception: ${e.message}")
            null
        }
    }
}
