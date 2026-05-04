package com.workflow.orchestrator.core.services

/**
 * Minimal sink contract feature modules use to deposit tool-produced
 * image bytes into the active agent session's content-addressed store.
 *
 * `:agent` provides the implementation (a thin adapter over
 * `AttachmentStore`); `:jira` and other feature modules depend ONLY on
 * this interface — preserves the `feature module → :core` layering rule
 * documented in the project CLAUDE.md.
 *
 * The method is `suspend` because the underlying `AttachmentStore.store`
 * performs `Dispatchers.IO` disk writes. Callers from non-suspend tool
 * entry points should bridge via `runBlockingCancellable { sink.store(...) }`
 * at their own call site (the project canonical replacement for
 * `runBlocking` on background threads).
 */
interface AttachmentSink {
    /**
     * Idempotent: identical bytes produce identical [ToolResult.ImageRefData.sha256]
     * and the underlying store skips the second write.
     */
    suspend fun store(bytes: ByteArray, mime: String, originalFilename: String?): ToolResult.ImageRefData
}
