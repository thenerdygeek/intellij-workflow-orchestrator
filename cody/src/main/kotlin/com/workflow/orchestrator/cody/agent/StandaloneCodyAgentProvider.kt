package com.workflow.orchestrator.cody.agent

import com.intellij.openapi.project.Project

/**
 * Provider that spawns our own Cody Agent subprocess.
 * This is the default fallback when the official Sourcegraph Cody plugin is not installed.
 */
class StandaloneCodyAgentProvider : CodyAgentProvider {

    override val displayName = "Standalone Agent"
    override val priority = 0

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
