// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.preview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.agent.tools.builtin.EditFileTool
import com.workflow.orchestrator.agent.util.DiffUtil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-AgentLoop-call state machine that streams partial `edit_file` diffs into the chat
 * panel while the LLM is still emitting `<new_string>`.
 *
 * Wired from `AgentLoop.onChunk` — for every `ToolUseContent` block in the parser output
 * whose name is `edit_file`, the loop calls [observe] with the current best-known params
 * and the block's `partial` flag.
 *
 * Behaviour:
 *  - **First sighting (no state yet)**: parameter completeness is checked (both `old_string`
 *    and `new_string` keys must be present in the param map; the parser only adds the
 *    `new_string` key once `</old_string>` has closed). [EditFileTool.preview] re-validates
 *    everything Commit 1 already validates — missing path, file not found, no match,
 *    ambiguous match without `replace_all`. On validation failure the tracker silently
 *    drops the preview (no card shown to user); when the tool call eventually completes,
 *    `execute()` will surface the precise error to the LLM exactly as it does today.
 *    On success the file's full content is snapshotted and [onOpen] fires.
 *  - **Mid-stream update**: throttled to [throttleMs] (default 100ms). Identical
 *    `new_string` across re-parses is suppressed. Each accepted update rebuilds the
 *    unified diff against the snapshotted original content + current partial `new_string`
 *    and fires [onUpdate].
 *  - **Tool close (`isPartial=false`)**: the final diff is pushed via [onUpdate] and
 *    [onFinalize] is invoked. The tracker marks the state as finalized; stray re-parses
 *    after that are ignored. AgentLoop's normal write-tool dispatch path takes over
 *    from here (approval gate or session-approved fast path).
 *  - **Cancellation** (via [cancel] or [cancelAll]): drops the open preview and fires
 *    [onCancel]. Used by AgentLoop on stream-interrupt / coroutine cancel — real file
 *    is never touched during streaming, so cancellation is purely a UI affordance.
 *
 * Thread safety: the per-call state is held in a [ConcurrentHashMap]. [observe] is
 * `suspend` because [EditFileTool.preview] is suspend (it uses `readAction { }` to read
 * VFS content). Callbacks run inline on the calling coroutine — the AgentLoop's
 * `onChunk` lambda invokes us from `Dispatchers.IO`, so the bridge push happens off-EDT
 * (the bridge layer routes onto the JCEF dispatcher itself).
 */
class StreamingEditTracker(
    private val project: Project,
    private val onOpen: (callId: String, path: String, initialDiff: String) -> Unit,
    private val onUpdate: (callId: String, diff: String) -> Unit,
    private val onFinalize: (callId: String) -> Unit,
    private val onCancel: (callId: String) -> Unit,
    private val throttleMs: Long = 100L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private data class StreamingEditState(
        val callId: String,
        val path: String,
        val resolvedPath: String,
        val originalContent: String,
        val oldString: String,
        val replaceAll: Boolean,
        var lastTickMs: Long,
        var lastEmittedNewString: String?,
        var finalized: Boolean = false,
    )

    private val states = ConcurrentHashMap<String, StreamingEditState>()

    /**
     * Observe the current state of a partial `edit_file` tool call.
     *
     * @param callId stable per-tool-call ID derived from the parser's block index +
     *   tool name. The same logical tool call at the same list position must produce
     *   the same id across re-parses (the parser is deterministic over the accumulated
     *   buffer, so AgentLoop derives this from the block index).
     * @param params current best-known param values, extracted from
     *   `ToolUseContent.params` by the caller.
     * @param isPartial true while `</edit_file>` has not arrived yet; false on close.
     * @return true if the tracker owns a live preview for [callId] after this call;
     *   false if validation failed (preview dropped) or the params are not yet complete.
     */
    suspend fun observe(
        callId: String,
        params: Map<String, String>,
        isPartial: Boolean,
    ): Boolean {
        val existing = states[callId]
        if (existing == null) {
            return tryOpen(callId, params, isPartial)
        }
        if (existing.finalized) return true  // stray re-parse after finalize
        return updateOrFinalize(existing, params, isPartial)
    }

    /** Cancel a single open preview. Fires [onCancel] iff the id was tracked. */
    fun cancel(callId: String) {
        val removed = states.remove(callId) ?: return
        if (!removed.finalized) onCancel(callId)
    }

    /** Cancel every open preview. Fires [onCancel] once per id. Used on stream-interrupt
     *  / AgentLoop coroutine cancel / new session. Order is iteration-order of the
     *  underlying map; no guarantees beyond that. */
    fun cancelAll() {
        val ids = states.keys.toList()
        for (id in ids) cancel(id)
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private suspend fun tryOpen(
        callId: String,
        params: Map<String, String>,
        isPartial: Boolean,
    ): Boolean {
        val path = params["path"] ?: return false
        val oldString = params["old_string"]
        if (oldString.isNullOrEmpty()) return false

        // Heuristic for "old_string is complete": the parser only opens the
        // `<new_string>` slot once `</old_string>` has closed. So presence of the
        // `new_string` key in the param map is the load-bearing signal — its value
        // can still be empty/streaming, but the key being present means
        // `<old_string>` has closed and we have its final bytes.
        // The tool fully closed case (`!isPartial`) is also accepted, in case the
        // parser delivered both close tags in the same chunk.
        val oldStringIsComplete = params.containsKey("new_string") || !isPartial
        if (!oldStringIsComplete) return false

        val replaceAll = params["replace_all"]?.toBooleanStrictOrNull() ?: false
        val newString = params["new_string"] ?: ""

        val previewParams: JsonObject = buildJsonObject {
            put("path", JsonPrimitive(path))
            put("old_string", JsonPrimitive(oldString))
            put("new_string", JsonPrimitive(newString))
            params["replace_all"]?.toBooleanStrictOrNull()?.let { put("replace_all", JsonPrimitive(it)) }
        }

        val preview = EditFileTool.preview(previewParams, project)
        if (preview !is EditFileTool.EditPreview.Ready) {
            // Validation failed — drop. execute() will surface the precise error later.
            return false
        }

        // Snapshot the original content so subsequent updates don't have to re-read
        // the file on each tick. Mid-stream the file should be stable; if it isn't,
        // execute() will catch the drift via its own re-validation.
        val originalContent = snapshotOriginalContent(path) ?: return false

        val state = StreamingEditState(
            callId = callId,
            path = path,
            resolvedPath = path,
            originalContent = originalContent,
            oldString = oldString,
            replaceAll = replaceAll,
            lastTickMs = clock(),
            lastEmittedNewString = newString,
            finalized = false,
        )
        states[callId] = state

        // Initial diff uses the current partial new_string (often empty when the LLM
        // has just opened the slot). The user sees the deleted lines appear immediately;
        // additions stream in as the LLM types.
        onOpen(callId, path, preview.realDiff)
        return true
    }

    private fun updateOrFinalize(
        state: StreamingEditState,
        params: Map<String, String>,
        isPartial: Boolean,
    ): Boolean {
        val currentNewString = params["new_string"] ?: ""

        if (!isPartial) {
            // Tool close — push final diff + finalize.
            state.finalized = true
            val finalDiff = buildDiff(state, currentNewString)
            onUpdate(state.callId, finalDiff)
            onFinalize(state.callId)
            return true
        }

        val now = clock()
        if (now - state.lastTickMs < throttleMs) return true
        if (currentNewString == state.lastEmittedNewString) return true

        state.lastTickMs = now
        state.lastEmittedNewString = currentNewString
        onUpdate(state.callId, buildDiff(state, currentNewString))
        return true
    }

    private fun buildDiff(state: StreamingEditState, currentNewString: String): String {
        val newContent = if (state.replaceAll) {
            state.originalContent.replace(state.oldString, currentNewString)
        } else {
            state.originalContent.replaceFirst(state.oldString, currentNewString)
        }
        return DiffUtil.unifiedDiff(state.originalContent, newContent, state.path)
    }

    /**
     * Read the full file content for the original snapshot. Mirrors
     * [EditFileTool] Document → VFS → java.io.File fallback chain. Returns
     * null on read failure (the caller treats null as a validation failure
     * and drops the preview).
     */
    private suspend fun snapshotOriginalContent(rawPath: String): String? {
        return try {
            val vFile = findVirtualFile(rawPath)
            if (vFile != null) {
                try {
                    return readAction {
                        val doc = FileDocumentManager.getInstance().getDocument(vFile)
                        doc?.text ?: String(vFile.contentsToByteArray(), vFile.charset)
                    }
                } catch (_: Exception) {
                    // readAction unavailable (test env without Application) — fall through.
                }
            }
            // The path that preview() accepted may be relative; we have to resolve the
            // same way EditFileTool.preview did. preview() already validated the path,
            // so the raw path either is absolute already or the project basePath was
            // available. In the test path (no Application), the param is the absolute
            // temp-file path — read it directly. In the runtime path, the VFS lookup
            // above hits first.
            val file = java.io.File(rawPath)
            if (file.exists() && file.isFile) file.readText(Charsets.UTF_8) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun findVirtualFile(rawPath: String): VirtualFile? {
        return try {
            if (ApplicationManager.getApplication() == null) return null
            LocalFileSystem.getInstance().findFileByPath(rawPath)
                ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(rawPath)
        } catch (_: Exception) {
            null
        }
    }
}
