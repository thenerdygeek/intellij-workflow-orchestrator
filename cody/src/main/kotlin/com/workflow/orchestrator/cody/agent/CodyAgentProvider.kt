package com.workflow.orchestrator.cody.agent

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Abstraction for acquiring a [CodyAgentServer] proxy.
 *
 * Default implementation: [StandaloneCodyAgentProvider] — spawns a Cody CLI agent process.
 */
interface CodyAgentProvider {

    /** Human-readable name for logging. */
    val displayName: String

    /** Higher priority wins. Integrated = 100, Standalone = 0. */
    val priority: Int

    /** Whether this provider can currently supply an agent for the given project. */
    suspend fun isAvailable(project: Project): Boolean

    /** Acquire (or start) the agent and return the server proxy. */
    suspend fun acquireServer(project: Project): CodyAgentServer

    /** Whether the provider currently has a running agent. */
    fun isRunning(project: Project): Boolean

    /** Get server without starting. Returns null if not running. */
    fun getServerOrNull(project: Project): CodyAgentServer?

    /** Whether this provider manages its own document sync (didOpen/didChange/didFocus). */
    fun handlesDocumentSync(): Boolean = false

    /** Clean shutdown. */
    fun dispose(project: Project)

    companion object {
        val EP_NAME = ExtensionPointName<CodyAgentProvider>(
            "com.workflow.orchestrator.codyAgentProvider"
        )
    }
}
