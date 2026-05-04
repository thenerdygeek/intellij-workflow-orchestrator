package com.workflow.orchestrator.agent.tool

import com.workflow.orchestrator.agent.session.AttachmentStore
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Carries the active session's [AttachmentStore] to tool implementations
 * via the coroutine context. AgentService installs this element when it
 * constructs the per-session [AttachmentStore] (the same point where
 * `wrapBrainWithRouter` is called) so every tool launched inside the
 * session inherits it.
 *
 * The `:jira` module (and any future feature module that produces images)
 * uses [current] to fetch the store WITHOUT taking a compile-time
 * dependency on `:agent` — `:jira → :core` is the canonical layering and
 * this element lives behind a small `:core` interface (see
 * [com.workflow.orchestrator.core.services.AttachmentSink]).
 */
class SessionAttachmentAccess(
    val store: AttachmentStore,
) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<SessionAttachmentAccess> {
        suspend fun current(): AttachmentStore? = coroutineContext[Key]?.store

        suspend fun requireStore(): AttachmentStore = current()
            ?: throw AttachmentStoreUnavailable(
                "No active session AttachmentStore in coroutine context. " +
                "Tool was invoked outside the agent session scope."
            )
    }
}

class AttachmentStoreUnavailable(message: String) : IllegalStateException(message)
