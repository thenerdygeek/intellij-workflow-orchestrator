package com.workflow.orchestrator.agent.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.workflow.orchestrator.agent.runtime.ConversationStore

/**
 * Application-level index of all agent sessions across all projects.
 *
 * Enables the History tab to show sessions from any project without
 * scanning the filesystem. Updated best-effort from ConversationSession
 * on create, message, and completion.
 *
 * Storage: workflowAgentSessions.xml in IDE config (non-roaming).
 * Cleanup policy: max 100 sessions, max 30 days old.
 */
@Service(Service.Level.APP)
@State(
    name = "WorkflowAgentSessions",
    storages = [Storage("workflowAgentSessions.xml", roamingType = RoamingType.DISABLED)]
)
class GlobalSessionIndex : PersistentStateComponent<GlobalSessionIndex.State> {

    /**
     * A single session entry in the index.
     *
     * Uses var fields with defaults for IntelliJ XML serialization compatibility.
     * Do NOT add @Serializable — IntelliJ uses its own XML serializer.
     */
    data class SessionEntry(
        var sessionId: String = "",
        var projectName: String = "",
        var projectPath: String = "",
        var title: String = "",
        var createdAt: Long = 0,
        var lastMessageAt: Long = 0,
        var messageCount: Int = 0,
        var status: String = "active" // active, completed, interrupted, failed
    )

    class State : BaseState() {
        var sessions by list<SessionEntry>()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    /**
     * Register a new session (inserted at position 0 = newest first).
     */
    fun addSession(entry: SessionEntry) {
        val sessions = myState.sessions.toMutableList()
        sessions.add(0, entry)
        myState.sessions = sessions
    }

    /**
     * Update an existing session by ID. The updater receives a copy and returns the new value.
     */
    fun updateSession(sessionId: String, updater: (SessionEntry) -> SessionEntry) {
        val sessions = myState.sessions.toMutableList()
        val index = sessions.indexOfFirst { it.sessionId == sessionId }
        if (index >= 0) {
            sessions[index] = updater(sessions[index])
            myState.sessions = sessions
        }
    }

    /**
     * Get the most recent sessions, across all projects.
     */
    fun getSessions(limit: Int = 50): List<SessionEntry> {
        return myState.sessions.take(limit)
    }

    /**
     * Get sessions for a specific project (by base path).
     */
    fun getSessionsForProject(projectPath: String): List<SessionEntry> {
        return myState.sessions.filter { it.projectPath == projectPath }
    }

    /**
     * Delete a session from the index and its files on disk.
     */
    fun deleteSession(sessionId: String) {
        val sessions = myState.sessions.toMutableList()
        sessions.removeAll { it.sessionId == sessionId }
        myState.sessions = sessions
        // Also delete JSONL files
        ConversationStore.deleteSession(sessionId)
    }

    /**
     * Remove stale sessions that exceed age or count limits.
     * Called periodically or on IDE startup.
     *
     * @param maxAge Maximum age in milliseconds (default: 30 days)
     * @param maxCount Maximum number of sessions to keep (default: 100)
     */
    fun cleanup(maxAge: Long = 30L * 24 * 60 * 60 * 1000, maxCount: Int = 100) {
        val now = System.currentTimeMillis()
        val sessions = myState.sessions.toMutableList()

        // Remove sessions older than maxAge
        val expired = sessions.filter { now - it.lastMessageAt > maxAge }
        expired.forEach { ConversationStore.deleteSession(it.sessionId) }
        sessions.removeAll(expired.toSet())

        // Trim to maxCount, removing oldest (they're sorted newest-first)
        if (sessions.size > maxCount) {
            val toRemove = sessions.subList(maxCount, sessions.size).toList()
            toRemove.forEach { ConversationStore.deleteSession(it.sessionId) }
            sessions.retainAll(sessions.take(maxCount).toSet())
        }

        myState.sessions = sessions
    }

    companion object {
        fun getInstance(): GlobalSessionIndex =
            ApplicationManager.getApplication().getService(GlobalSessionIndex::class.java)
    }
}
