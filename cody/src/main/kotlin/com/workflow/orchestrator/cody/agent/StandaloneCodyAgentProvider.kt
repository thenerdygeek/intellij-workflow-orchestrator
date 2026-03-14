package com.workflow.orchestrator.cody.agent

import com.intellij.openapi.project.Project

/**
 * Provider that spawns a Cody CLI agent subprocess via JSON-RPC over stdio.
 * Supports the full protocol: chat/new, chat/submitMessage, editCommands/code, etc.
 */
class StandaloneCodyAgentProvider : CodyAgentProvider {

    override val displayName = "Standalone Agent"
    override val priority = 200

    override suspend fun isAvailable(project: Project): Boolean {
        val manager = CodyAgentManager.getInstance(project)
        return manager.resolveAgentBinary() != null
    }

    override suspend fun acquireServer(project: Project): CodyAgentServer =
        CodyAgentManager.getInstance(project).ensureRunning()

    override fun isRunning(project: Project): Boolean =
        CodyAgentManager.getInstance(project).isRunning()

    override fun getServerOrNull(project: Project): CodyAgentServer? =
        CodyAgentManager.getInstance(project).getServerOrNull()

    override fun handlesDocumentSync(): Boolean = false

    override fun dispose(project: Project) {
        CodyAgentManager.getInstance(project).dispose()
    }
}
