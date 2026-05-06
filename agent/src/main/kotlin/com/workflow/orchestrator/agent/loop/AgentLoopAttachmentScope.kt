package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.session.AttachmentStore
import com.workflow.orchestrator.agent.tool.SessionAttachmentAccess
import com.workflow.orchestrator.core.services.SessionDownloadDir
import kotlinx.coroutines.withContext

/**
 * Wraps a tool invocation in a coroutine context that exposes the active
 * session's [AttachmentStore] and downloads directory. AgentLoop calls this
 * around `tool.execute(...)` so feature-module tools (e.g. JiraTool) can use
 * [SessionAttachmentAccess] (image uploads — `:agent`-scoped) and
 * [SessionDownloadDir] (artifact downloads — `:core`-scoped, readable by
 * `:jira`) without taking a direct dependency on the agent service.
 *
 * The downloads dir is anchored at `{sessionDir}/downloads/` so files land
 * inside the read-allowlisted `~/.workflow-orchestrator/` tree and the
 * agent's `read_file` / `read_document` / `search_code` tools can reach
 * them. See `agent/CLAUDE.md` "Storage tiers".
 */
object AgentLoopAttachmentScope {
    suspend fun <T> runWithStore(store: AttachmentStore, block: suspend () -> T): T {
        val downloadsDir = store.sessionDir.resolve("downloads")
        return withContext(
            SessionAttachmentAccess(store) + SessionDownloadDir(downloadsDir)
        ) { block() }
    }
}
