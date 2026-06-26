package com.workflow.orchestrator.mockserver.sourcegraph.scenario

import java.security.MessageDigest

/** A normalized, dialect-agnostic message: the route maps both OpenAI and Cody bodies into this. */
data class EngineMessage(val role: String, val text: String)

/**
 * Dialect-agnostic scenario driver. Given the normalized conversation so far, picks the active
 * scenario (tag → admin default → fallback) and returns the next [Turn] to play, advancing the
 * per-conversation index in [ScenarioState]. The OpenAI and Cody serializers then render the same
 * [Turn] to their respective wire formats.
 */
class ScenarioEngine(
    private val library: ScenarioLibrary,
    private val state: ScenarioState,
) {

    /** Pick + advance the next [Turn] for the conversation described by [messages]. */
    fun nextTurn(messages: List<EngineMessage>): Turn {
        val convoKey = conversationKey(messages)
        val latestUserText = messages.lastOrNull { it.role == ROLE_USER }?.text.orEmpty()
        val tagged = extractScenarioTag(latestUserText)

        val view = state.advance(convoKey, tagged)
        val scenario = library.byName(view.scenarioName) ?: library.default()
        val index = view.turnIndex.coerceIn(0, scenario.turns.lastIndex)
        return scenario.turns[index]
    }

    /** Stable per-conversation key: a short SHA-256 of the first user message text. */
    private fun conversationKey(messages: List<EngineMessage>): String {
        val seed = messages.firstOrNull { it.role == ROLE_USER }?.text
            ?: messages.firstOrNull()?.text
            ?: ""
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** First `[name]` tag in [text] that names a known scenario, or null. */
    private fun extractScenarioTag(text: String): String? =
        TAG_REGEX.findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull { library.exists(it) }

    companion object {
        const val ROLE_USER = "user"

        // Matches a bracketed lowercase-kebab token, e.g. [multi-tool].
        private val TAG_REGEX = Regex("""\[([a-z0-9][a-z0-9-]*)]""")
    }
}
