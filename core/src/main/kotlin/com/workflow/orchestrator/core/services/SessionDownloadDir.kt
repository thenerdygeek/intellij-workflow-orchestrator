package com.workflow.orchestrator.core.services

import java.nio.file.Path
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Per-session "where should I download files to" directory, propagated via
 * the coroutine context.
 *
 * Feature modules (`:jira`, future `:bamboo`, …) that download artifacts on
 * the agent's behalf use [current] to land bytes inside the active session's
 * directory tree. That keeps downloads under `~/.workflow-orchestrator/` —
 * the read-allowlisted root — so the same agent can later `read_file` /
 * `read_document` / `search_code` over them. Without this routing, downloads
 * land in `java.io.tmpdir` and the agent's read tools reject the path as
 * "outside project".
 *
 * Lives in `:core` (not `:agent`) on purpose: feature modules consume this
 * via `coroutineContext[Key]` without taking a compile-time dependency on
 * `:agent`. The contract IS the coroutine context element — there is no
 * separate interface, mirroring the layering rationale in the project
 * CLAUDE.md "Storage tiers" section.
 *
 * Installation: `:agent` wraps every tool invocation in a withContext
 * carrying this element (alongside the SessionAttachmentAccess for image
 * uploads). Outside the agent loop (UI handlers, tests, sub-agents that
 * skip the wrap) [current] returns null and callers fall back to their
 * pre-existing behavior (system temp).
 */
class SessionDownloadDir(val downloadsDir: Path) :
    AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<SessionDownloadDir> {
        /**
         * Returns the active session's downloads directory, or null when the
         * caller is not running inside an agent tool invocation. Callers that
         * receive null should fall back to their existing default (typically
         * a `Files.createTempDirectory(...)` — fine for UI-driven downloads,
         * but the file is unreadable to the agent's read tools).
         */
        suspend fun current(): Path? = coroutineContext[Key]?.downloadsDir
    }
}
