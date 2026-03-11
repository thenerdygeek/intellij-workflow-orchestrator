package com.workflow.orchestrator.cody.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.protocol.*
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.lsp4j.jsonrpc.Launcher
import java.io.File
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class CodyAgentManager(private val project: Project) : Disposable {

    private val log = Logger.getInstance(CodyAgentManager::class.java)

    private var process: Process? = null
    private var server: CodyAgentServer? = null
    private var _client: CodyAgentClient? = null

    val client: CodyAgentClient? get() = _client

    private val _state = MutableStateFlow<AgentState>(AgentState.Stopped)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    sealed class AgentState {
        object Stopped : AgentState()
        object Starting : AgentState()
        data class Running(val serverInfo: ServerInfo) : AgentState()
        data class Error(val message: String) : AgentState()
    }

    private val startMutex = Mutex()

    suspend fun ensureRunning(): CodyAgentServer {
        startMutex.withLock {
            val currentServer = server
            if (currentServer != null && isRunning()) return currentServer

            _state.value = AgentState.Starting

            val binaryPath = resolveAgentBinary()
            if (binaryPath == null) {
                val msg = "Cody Agent binary not found. Configure path in Settings > Workflow Orchestrator."
                _state.value = AgentState.Error(msg)
                notifyError(msg)
                throw IllegalStateException(msg)
            }

            val settings = PluginSettings.getInstance(project)
            val token = CredentialStore().getToken(ServiceType.SOURCEGRAPH)
            if (token.isNullOrBlank()) {
                val msg = "Sourcegraph access token not configured."
                _state.value = AgentState.Error(msg)
                notifyError(msg)
                throw IllegalStateException(msg)
            }

            return startAgent(binaryPath, settings, token)
        }
    }

    fun getServerOrNull(): CodyAgentServer? {
        if (_state.value !is AgentState.Running) return null
        return server
    }

    private fun startAgent(binaryPath: String, settings: PluginSettings, token: String): CodyAgentServer {
        val pb = ProcessBuilder(binaryPath, "api", "jsonrpc-stdio")
            .redirectErrorStream(false)

        val env = pb.environment()
        env["CODY_DEBUG"] = if (log.isDebugEnabled) "true" else "false"

        val proc = pb.start()
        process = proc

        val agentClient = CodyAgentClient(project)
        _client = agentClient

        val launcher = Launcher.Builder<CodyAgentServer>()
            .setInput(proc.inputStream)
            .setOutput(proc.outputStream)
            .setLocalService(agentClient)
            .setRemoteInterface(CodyAgentServer::class.java)
            .create()

        val agentServer = launcher.remoteProxy
        server = agentServer

        launcher.startListening()

        Thread({
            proc.waitFor()
            log.warn("Cody Agent process exited with code ${proc.exitValue()}")
            if (_state.value is AgentState.Running) {
                _state.value = AgentState.Error("Agent process exited unexpectedly")
            }
            server = null
        }, "cody-agent-monitor").apply { isDaemon = true }.start()

        val clientInfo = buildClientInfo(settings, token)
        val serverInfo = agentServer.initialize(clientInfo).get(30, TimeUnit.SECONDS)
        agentServer.initialized()

        if (serverInfo.authenticated != true) {
            val msg = "Sourcegraph authentication failed. Check your access token."
            _state.value = AgentState.Error(msg)
            notifyError(msg)
            dispose()
            throw IllegalStateException(msg)
        }

        _state.value = AgentState.Running(serverInfo)
        log.info("Cody Agent started: ${serverInfo.name}, user: ${serverInfo.authStatus?.username}")

        return agentServer
    }

    fun isRunning(): Boolean {
        val proc = process ?: return false
        return proc.isAlive && _state.value is AgentState.Running
    }

    suspend fun restart() {
        dispose()
        ensureRunning()
    }

    internal fun resolveAgentBinary(): String? {
        val settings = PluginSettings.getInstance(project)
        val configured = settings.state.codyAgentPath
        if (!configured.isNullOrBlank() && File(configured).exists()) {
            return configured
        }

        return try {
            val whichCmd = if (System.getProperty("os.name").lowercase().contains("win")) "where" else "which"
            val proc = ProcessBuilder(whichCmd, "cody-agent").start()
            val result = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(5, TimeUnit.SECONDS)
            if (result.isNotBlank() && File(result).exists()) result else null
        } catch (e: Exception) {
            log.debug("cody-agent not found on PATH", e)
            null
        }
    }

    internal fun buildClientInfo(settings: PluginSettings, token: String): ClientInfo {
        val ideVersion = try {
            ApplicationInfo.getInstance().build.toString()
        } catch (e: Exception) { null }

        return ClientInfo(
            version = "1.0.0",
            ideVersion = ideVersion,
            workspaceRootUri = "file://${project.basePath}",
            extensionConfiguration = ExtensionConfiguration(
                serverEndpoint = settings.state.sourcegraphUrl ?: "",
                accessToken = token
            ),
            capabilities = ClientCapabilities()
        )
    }

    override fun dispose() {
        try {
            server?.shutdown()?.get(5, TimeUnit.SECONDS)
            server?.exit()
        } catch (e: Exception) {
            log.debug("Shutdown error (expected if agent already exited)", e)
        }
        process?.destroyForcibly()
        process = null
        server = null
        _client = null
        _state.value = AgentState.Stopped
    }

    private fun notifyError(message: String) {
        try {
            WorkflowNotificationService.getInstance(project).notifyError(
                WorkflowNotificationService.GROUP_CODY,
                "Cody AI",
                message
            )
        } catch (e: Exception) {
            log.error("Failed to show notification", e)
        }
    }

    companion object {
        fun getInstance(project: Project): CodyAgentManager =
            project.service<CodyAgentManager>()
    }
}
