package com.workflow.orchestrator.agent.session

import java.io.File

/**
 * A3 (path-traversal hardening): validates webview-supplied session ids before they are used to
 * build a session directory path (`sessions/{id}`).
 *
 * Read/resume/export entry points (`AgentController.showSession` / `formatSessionAsMarkdown`,
 * `AgentService.resumeSession`) build `File(agentDir, "sessions/$sessionId")` directly from the
 * id the webview sends. Without this guard a crafted id like `../../other-project/session`
 * resolves OUTSIDE the agent tree (cross-project session read / out-of-tree resume).
 *
 * The allow-list mirrors `MessageStateHandler.SAFE_SESSION_ID` (the write-path guard) so all
 * session-id-keyed paths share one shape: only letters, digits, `_` and `-` (covers UUIDs).
 */
object SessionIdValidator {

    private val SAFE_SESSION_ID = Regex("^[a-zA-Z0-9_-]+$")

    /** True iff [sessionId] is a safe, non-empty session id that cannot escape the sessions tree. */
    fun isValid(sessionId: String?): Boolean =
        sessionId != null && SAFE_SESSION_ID.matches(sessionId)

    /**
     * Returns `File(baseDir, "sessions/$sessionId")` only when [sessionId] is valid, else null.
     * The single safe path builder for session-id-keyed directories.
     */
    fun sessionDirOrNull(baseDir: File, sessionId: String?): File? =
        if (isValid(sessionId)) File(baseDir, "sessions/$sessionId") else null
}
