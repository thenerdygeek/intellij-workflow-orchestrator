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

    @Volatile private var process: Process? = null
    @Volatile private var server: CodyAgentServer? = null
    @Volatile private var _client: CodyAgentClient? = null

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
        // On Windows, .cmd files must be run via cmd.exe /c
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val command = if (isWindows && binaryPath.endsWith(".cmd", ignoreCase = true)) {
            listOf("cmd.exe", "/c", binaryPath, "api", "jsonrpc-stdio")
        } else {
            listOf(binaryPath, "api", "jsonrpc-stdio")
        }
        log.info("[CodyAgent] Starting process: ${command.joinToString(" ")}")

        val pb = ProcessBuilder(command)
            .redirectErrorStream(false)

        val env = pb.environment()
        env["CODY_DEBUG"] = "true"
        env["CODY_AGENT_DEBUG_REMOTE"] = "false"

        val proc = pb.start()
        process = proc

        // Log stderr in a daemon thread for debugging agent startup issues
        Thread({
            try {
                proc.errorStream.bufferedReader().forEachLine { line ->
                    log.warn("[CodyAgent:stderr] $line")
                }
            } catch (_: Exception) {}
        }, "cody-agent-stderr").apply { isDaemon = true }.start()

        val agentClient = CodyAgentClient(project)
        // Pre-populate secrets so the agent can retrieve the token via secrets/get
        agentClient.storeSecret("cody.access-token", token)
        agentClient.storeSecret("token", token)
        _client = agentClient

        // Log all JSON-RPC messages for debugging protocol issues
        val traceWriter = object : java.io.PrintWriter(java.io.Writer.nullWriter(), true) {
            override fun println(x: String?) {
                if (x != null) log.info("[CodyAgent:jsonrpc] $x")
            }
        }

        val launcher = Launcher.Builder<CodyAgentServer>()
            .setInput(proc.inputStream)
            .setOutput(proc.outputStream)
            .setLocalService(agentClient)
            .setRemoteInterface(CodyAgentServer::class.java)
            .traceMessages(traceWriter)
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

    /**
     * Resolves the Cody Agent binary using a 3-tier fallback:
     *
     * 1. User-configured path (Settings > Advanced > codyAgentPath)
     * 2. Environment variable CODY_AGENT_BINARY, or "cody" on system PATH
     * 3. Previously installed binary at ~/.cody-agent/cody-agent-{VERSION}
     */
    internal fun resolveAgentBinary(): String? {
        // Tier 1: User-configured path in settings
        val settings = PluginSettings.getInstance(project)
        val configured = settings.state.codyAgentPath
        if (!configured.isNullOrBlank() && File(configured).exists()) {
            log.info("[CodyAgent] Using configured binary: $configured")
            return configured
        }

        // Tier 2: CODY_AGENT_BINARY env var or "cody" on PATH
        val envBinary = System.getenv("CODY_AGENT_BINARY")
        if (!envBinary.isNullOrBlank() && File(envBinary).exists()) {
            log.info("[CodyAgent] Using CODY_AGENT_BINARY env: $envBinary")
            return envBinary
        }

        // On Windows, npm creates "cody.cmd" wrapper — the plain "cody" is a shell script
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val pathCommands = if (isWindows) listOf("cody.cmd", "cody") else listOf("cody")
        for (cmd in pathCommands) {
            val pathBinary = findOnPath(cmd)
            if (pathBinary != null) {
                log.info("[CodyAgent] Using '$cmd' from PATH: $pathBinary")
                return pathBinary
            }
        }

        // Tier 3: Previously installed binary at ~/.cody-agent/
        val suffix = if (isWindows) ".cmd" else ""
        val autoPath = File(
            File(System.getProperty("user.home"), ".cody-agent"),
            "cody-agent-$CODY_AGENT_VERSION$suffix"
        )
        if (autoPath.exists()) {
            log.info("[CodyAgent] Using binary at: $autoPath")
            return autoPath.absolutePath
        }

        log.warn("[CodyAgent] No Cody binary found. Install via: npm install -g @sourcegraph/cody")
        return null
    }

    private fun findOnPath(command: String): String? {
        return try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val whichCmd = if (isWindows) "where" else "which"
            val proc = ProcessBuilder(whichCmd, command).start()
            val result = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(5, TimeUnit.SECONDS)
            val firstLine = result.lines().firstOrNull()?.trim()
            if (!firstLine.isNullOrBlank() && File(firstLine).exists()) firstLine else null
        } catch (e: Exception) {
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
            workspaceRootUri = java.io.File(project.basePath ?: ".").toURI().toString().trimEnd('/'),
            extensionConfiguration = ExtensionConfiguration(
                serverEndpoint = settings.connections.sourcegraphUrl ?: "",
                accessToken = token
            ),
            capabilities = ClientCapabilities()
        )
    }

    override fun dispose() {
        try {
            server?.shutdown()?.get(10, TimeUnit.SECONDS)
            server?.exit()
        } catch (e: Exception) {
            log.debug("Shutdown error (expected if agent already exited)", e)
        }

        // Kill the process and all child processes to prevent orphaned agent subprocesses
        process?.let { proc ->
            proc.destroyForcibly()
            try {
                proc.toHandle().descendants().forEach { it.destroyForcibly() }
            } catch (e: Exception) {
                log.debug("Failed to kill child processes (expected if already exited)", e)
            }
        }

        // Clear secrets from the client to avoid retaining tokens in memory
        _client?.clearSecrets()

        process = null
        server = null
        _client = null
        _state.value = AgentState.Stopped
    }

    private fun notifyError(message: String) {
        try {
            WorkflowNotificationService.getInstance(project).notifyError(
                WorkflowNotificationService.GROUP_CODY,
                "Workflow Cody",
                message
            )
        } catch (e: Exception) {
            log.error("Failed to show notification", e)
        }
    }

    companion object {
        /** Version of @sourcegraph/cody to auto-download from npm. */
        const val CODY_AGENT_VERSION = "5.5.14"

        fun getInstance(project: Project): CodyAgentManager =
            project.service<CodyAgentManager>()
    }
}
