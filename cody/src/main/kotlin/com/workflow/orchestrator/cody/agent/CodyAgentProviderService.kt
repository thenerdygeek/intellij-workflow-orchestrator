package com.workflow.orchestrator.cody.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Project-level service that resolves the best available [CodyAgentProvider]
 * and provides a single entry point for acquiring a [CodyAgentServer].
 *
 * Resolution order: providers sorted by [CodyAgentProvider.priority] descending.
 * If the highest-priority provider fails, falls back to lower-priority ones.
 */
@Service(Service.Level.PROJECT)
class CodyAgentProviderService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(CodyAgentProviderService::class.java)
    private val resolveMutex = Mutex()

    @Volatile
    private var activeProvider: CodyAgentProvider? = null

    /**
     * Acquire a [CodyAgentServer], starting an agent if necessary.
     * Tries the active provider first (fast path), then resolves from all registered providers.
     * Protected by a mutex to prevent concurrent resolution races.
     */
    suspend fun ensureRunning(): CodyAgentServer = resolveMutex.withLock {
        // Fast path: reuse active provider
        activeProvider?.let { provider ->
            try {
                if (provider.isAvailable(project)) {
                    return provider.acquireServer(project)
                }
            } catch (e: Exception) {
                log.warn("Active provider '${provider.displayName}' failed, resolving fallback", e)
                activeProvider = null
            }
        }

        // Full resolution: try all providers in priority order
        val providers = CodyAgentProvider.EP_NAME.extensionList
            .sortedByDescending { it.priority }

        for (provider in providers) {
            try {
                if (provider.isAvailable(project)) {
                    val server = provider.acquireServer(project)
                    activeProvider = provider
                    log.info("Using Cody agent provider: ${provider.displayName}")
                    return server
                }
            } catch (e: Exception) {
                log.warn("Provider '${provider.displayName}' failed, trying next", e)
            }
        }

        throw IllegalStateException(
            "No Cody agent provider available. " +
            "Install the Sourcegraph Cody plugin or configure the agent binary path in Settings."
        )
    }

    fun getServerOrNull(): CodyAgentServer? =
        activeProvider?.getServerOrNull(project)

    /**
     * Returns the [CodyAgentClient] for the standalone agent, or null if using integrated mode.
     * Used to set state (e.g., pending edit instructions) on the client before server calls.
     */
    fun getClient(): CodyAgentClient? =
        CodyAgentManager.getInstance(project).client

    fun isRunning(): Boolean =
        activeProvider?.isRunning(project) == true

    /**
     * True when using the official Cody plugin's agent (document sync handled externally).
     * When the active provider hasn't been resolved yet, checks the extension list directly
     * to determine if the highest-priority provider handles document sync.
     */
    val isIntegratedMode: Boolean
        get() = activeProvider?.handlesDocumentSync()
            ?: CodyAgentProvider.EP_NAME.extensionList
                .maxByOrNull { it.priority }
                ?.handlesDocumentSync()
            ?: false

    val activeProviderName: String?
        get() = activeProvider?.displayName

    override fun dispose() {
        activeProvider?.dispose(project)
    }

    companion object {
        fun getInstance(project: Project): CodyAgentProviderService =
            project.service<CodyAgentProviderService>()
    }
}
