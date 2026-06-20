package com.workflow.orchestrator.agent.ui

import com.workflow.orchestrator.agent.loop.queue.QueuedMessage
import com.workflow.orchestrator.agent.session.AsyncEventCardData
import kotlinx.serialization.json.Json

/**
 * Pure stateless helper: rebuilds [AsyncEventCardData] from drained queue items whose
 * [QueuedMessage.meta] carries a `"card"` entry (JSON-encoded [AsyncEventCardData]).
 * Deduplicates against [existingIds] so a card already shown live on a focused session
 * is never double-rendered on resume.
 *
 * Called from [com.workflow.orchestrator.agent.AgentService.resumeSession] inside the
 * `cs.launch` job AFTER the resume-ask [com.workflow.orchestrator.agent.session.MessageStateHandler.addToClineMessages]
 * call — at that point the session is NOT yet active, so this path appends onto the
 * resume-local handler directly (not via appendAsyncEventCardToSession).
 */
object AsyncEventResumeSynthesis {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Rebuild cards from drained items' meta["card"], skipping ids already present in [existingIds].
     *
     * @param items  all [QueuedMessage] items from the BACKGROUND and MONITOR drain groups
     * @param existingIds  stable card ids already present in the loaded ui_messages
     * @return ordered list of cards to append (may be empty)
     */
    fun cardsToAppend(
        items: List<QueuedMessage>,
        existingIds: Set<String>,
    ): List<AsyncEventCardData> =
        items
            .mapNotNull { it.meta["card"] }
            .mapNotNull { raw ->
                runCatching { json.decodeFromString(AsyncEventCardData.serializer(), raw) }.getOrNull()
            }
            .filter { it.id !in existingIds }
}
