package com.workflow.orchestrator.agent.tool

import com.workflow.orchestrator.core.services.AttachmentSink
import com.workflow.orchestrator.core.services.ToolResult

/**
 * `:agent`-side [AttachmentSink] adapter that resolves the active session's
 * [com.workflow.orchestrator.agent.session.AttachmentStore] from the
 * surrounding coroutine context (installed by [SessionAttachmentAccess])
 * and delegates the suspend-write to it.
 *
 * Feature-module callers from non-suspend tool entry points should wrap
 * their `sink.store(...)` call in `runBlockingCancellable { ... }` at the
 * call site (the project-canonical replacement for `runBlocking` on
 * background threads). Keeping the bridge at the caller side avoids
 * sneaking blocking primitives into shared infrastructure.
 */
class CoroutineAttachmentSink : AttachmentSink {
    override suspend fun store(
        bytes: ByteArray,
        mime: String,
        originalFilename: String?
    ): ToolResult.ImageRefData {
        val store = SessionAttachmentAccess.requireStore()
        val ref = store.store(bytes, mime, originalFilename)
        return ToolResult.ImageRefData(
            sha256 = ref.sha256,
            mime = ref.mime,
            size = ref.size,
            originalFilename = ref.originalFilename,
        )
    }
}
