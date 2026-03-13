package com.workflow.orchestrator.cody.agent

import com.intellij.openapi.project.Project

/**
 * Provider that spawns our own Cody Agent subprocess.
 *
 * Priority 200 (higher than Sourcegraph plugin's 100) because the standalone
 * agent supports the full protocol including chat/new and chat/submitMessage,
 * whereas the Sourcegraph IDE plugin's internal server has moved to webview-based
 * chat and no longer exposes those methods.
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
