package com.workflow.orchestrator.cody.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Project-level service providing a single entry point for acquiring a [CodyAgentServer].
 * Delegates to [CodyAgentManager] which spawns and manages the Cody CLI agent process.
 */
@Service(Service.Level.PROJECT)
class CodyAgentProviderService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(CodyAgentProviderService::class.java)

    private val manager get() = CodyAgentManager.getInstance(project)

    /**
     * Acquire a [CodyAgentServer], starting the agent if necessary.
     */
    suspend fun ensureRunning(): CodyAgentServer = manager.ensureRunning()

    fun getServerOrNull(): CodyAgentServer? = manager.getServerOrNull()

    /**
     * Returns the [CodyAgentClient] for setting state (e.g., pending edit instructions)
     * before server calls.
     */
    fun getClient(): CodyAgentClient? = manager.client

    fun isRunning(): Boolean = manager.isRunning()

    /** Standalone agent always manages its own document sync. */
    val isIntegratedMode: Boolean = false

    val activeProviderName: String?
        get() = if (isRunning()) "Standalone Agent" else null

    override fun dispose() {
        manager.dispose()
    }

    companion object {
        fun getInstance(project: Project): CodyAgentProviderService =
            project.service<CodyAgentProviderService>()
    }
}
