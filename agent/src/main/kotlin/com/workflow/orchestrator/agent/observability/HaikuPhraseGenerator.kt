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
    //
    // Bug 9 — Haiku self-gates updates. The previous prompt forced a funny
    // message every 30s regardless of whether anything had changed, so the
    // indicator churned with phrases that "tried too hard to be funny."
    // The new contract:
    //  - Compare the current state (recent tools + what the agent is thinking)
    //    against the LAST phrase shown.
    //  - Return EMPTY if the situation hasn't meaningfully changed — the
    //    timer fires every 30s but only writes a new phrase when there's
    //    something honest to say.
    //  - Be honest first, witty only if it lands. Brevity > cleverness.

    private const val EMPTY_RESPONSE = "(no change)"

    private const val SYSTEM_PROMPT = """You write a short status line for a developer IDE's working indicator.

YOU SEE: the user's task, the last 2-3 tools the agent ran, what the agent is thinking, AND the phrase currently displayed (if any).

YOUR JOB: decide whether the displayed phrase is still good enough OR a new phrase will be more honest about what the agent is doing right now.

OUTPUT one of:
  (no change)
  — return this LITERAL STRING when the situation has not meaningfully shifted since the last phrase. Same kind of work, same area of code, same idea. No update needed.

  A new phrase under 12 words ending with "..."
  — return this when the agent's focus has clearly moved (different file area, different intent, different blocker, different reasoning).

PRIORITIES, in order:
1. Honesty about what is happening — the user reads this to know progress.
2. Specificity — "Walking the AST for /agent/loop" beats "Reading code". Use file paths or symbol names from the recent tools when you have them.
3. Brevity — short hits harder.
4. Wry observation — only if it's TRUE for what's actually happening. A pithy, deadpan line is fine. A forced joke is worse than (no change).

NEVER:
- Repeat or rephrase the current phrase. If the situation hasn't changed, output (no change).
- Generic filler that could apply to any task ("Working on it...", "Almost there...").
- Puns, dad jokes, "wizard/magic/journey", or anything a LinkedIn post would say.
- Third person narration.
- Output anything other than (no change) or one phrase ending with "..." — no preamble, no quotes, no explanation."""

    private const val TITLE_EVAL_SYSTEM_PROMPT = """You evaluate whether a conversation title still fits after a task completed.

You see three things:
- The current title (may be a provisional placeholder that reads like raw user input)
- The latest user message
- The assistant's final response summarizing what was done

Respond with ONLY one of:
- KEEP — the current title still accurately describes the conversation
- A new title (under 50 chars) — the title needs replacement or refinement

Replace when:
- The title looks like a raw user message or placeholder ("fix this please", "can you help...", truncated text with "…")
- The title is vague while the actual work was specific
- The conversation scope has clearly shifted from what the title suggests

Keep when:
- The title is specific, action-oriented, and accurately describes the work
- The new turn is a minor follow-up or refinement of the same task

Format for new titles:
- Under 50 characters
- Verb + specific thing (e.g. "Fix auth token expiry in LoginService")
- No quotes, no punctuation at end"""

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
        val data = result.data as? com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
        val text = data?.choices?.firstOrNull()?.message?.content?.trim()
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
     * @param recentTools last 2-3 tool calls as (toolName, contextHint) pairs
     * @param agentThinking last ~100 chars of the LLM's stream output (what it's reasoning about)
     * @return a funny one-liner (under 80 chars), or null if generation fails
     */
    suspend fun generate(
        task: String,
        recentTools: List<Pair<String, String>>,
        agentThinking: String = "",
        currentPhrase: String? = null,
    ): String? {
        return try {
            val brain = createBrain("HaikuPhrase") ?: return null

            val toolContext = if (recentTools.isNotEmpty()) {
                recentTools.joinToString(", ") { (name, arg) ->
                    if (arg.isNotBlank()) "$name($arg)" else name
                }
            } else "just started"

            val thinkingContext = if (agentThinking.isNotBlank()) {
                "\nThe AI is currently thinking about: ${agentThinking.take(100)}"
            } else ""

            val currentLine = if (!currentPhrase.isNullOrBlank()) {
                "\nCurrent phrase displayed: \"${currentPhrase.take(80)}\""
            } else "\nCurrent phrase displayed: (none — first phrase of this task)"

            val userPrompt =
                "Task: \"${task.take(150)}\"\nRecent tools: $toolContext$thinkingContext$currentLine\n\n" +
                    "Output (no change) OR a new phrase under 12 words ending with \"...\":"

            val result = brain.chat(
                messages = listOf(
                    ChatMessage(role = "system", content = SYSTEM_PROMPT),
                    ChatMessage(role = "user", content = userPrompt)
                ),
                maxTokens = 60
            )

            val text = extractText(result, "HaikuPhrase") ?: return null
            val cleaned = text.removeSurrounding("\"").removeSurrounding("'").trim().take(80)
            // Bug 9 — empty / "no change" → suppress the update so the displayed
            // phrase stays put. This is the whole point of Haiku self-gating.
            if (cleaned.isBlank() || cleaned.equals(EMPTY_RESPONSE, ignoreCase = true)) {
                LOG.info("[HaikuPhrase] Haiku decided no change is needed — suppressing update")
                return null
            }
            LOG.info("[HaikuPhrase] Generated: $cleaned")
            cleaned
        } catch (e: Exception) {
            LOG.info("[HaikuPhrase] Exception: ${e.message}")
            null
        }
    }

    /**
     * Evaluate the conversation title at task completion. Takes the current title
     * plus the user message and the assistant's final response, and returns:
     *   - null if the title should stay the same (either Haiku said KEEP or a failure)
     *   - a new title string if Haiku decided a replacement is warranted
     *
     * Called at loop-exit (LoopResult.Completed). Gives Haiku far richer context
     * than the old task-start-only path had: it sees what was actually done, not
     * just what the user asked for. This is what lets a provisional
     * first-50-chars-of-user-message title get replaced with a crisp
     * "Fix null check in LoginService" once the task finishes.
     */
    suspend fun evaluateTitleFromCompletion(
        currentTitle: String,
        userMessage: String,
        assistantResponse: String,
    ): String? {
        return try {
            val brain = createBrain("HaikuTitleEval") ?: return null

            val result = brain.chat(
                messages = listOf(
                    ChatMessage(role = "system", content = TITLE_EVAL_SYSTEM_PROMPT),
                    ChatMessage(
                        role = "user",
                        content = """Current title: "$currentTitle"
User message: "${userMessage.take(300)}"
Assistant response: "${assistantResponse.take(500)}""""
                    )
                ),
                maxTokens = 40
            )

            val text = extractText(result, "HaikuTitleEval") ?: return null
            val cleaned = text.removeSurrounding("\"").removeSurrounding("'").trim()

            if (cleaned.equals("KEEP", ignoreCase = true) || cleaned.startsWith("KEEP")) {
                LOG.info("[HaikuTitleEval] Keeping: '$currentTitle'")
                null
            } else {
                val newTitle = cleaned.take(50)
                if (newTitle.isBlank() || newTitle.equals(currentTitle, ignoreCase = true)) {
                    null
                } else {
                    LOG.info("[HaikuTitleEval] '$currentTitle' → '$newTitle'")
                    newTitle
                }
            }
        } catch (e: Exception) {
            LOG.info("[HaikuTitleEval] Exception: ${e.message}")
            null
        }
    }
}
