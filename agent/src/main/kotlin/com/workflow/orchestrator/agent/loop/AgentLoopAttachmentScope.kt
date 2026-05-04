package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.session.AttachmentStore
import com.workflow.orchestrator.agent.tool.SessionAttachmentAccess
import kotlinx.coroutines.withContext

/**
 * Wraps a tool invocation in a coroutine context that exposes the active
 * session's [AttachmentStore]. AgentLoop calls this around `tool.execute(...)`
 * so feature-module tools (e.g. JiraTool) can use [SessionAttachmentAccess]
 * without taking a direct dependency on the agent service.
 */
object AgentLoopAttachmentScope {
    suspend fun <T> runWithStore(store: AttachmentStore, block: suspend () -> T): T =
        withContext(SessionAttachmentAccess(store)) { block() }
}
