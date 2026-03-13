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
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
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

    /**
     * Resolves the Cody Agent binary using a 3-tier fallback:
     *
     * 1. User-configured path (Settings > Advanced > codyAgentPath)
     * 2. Environment variable CODY_AGENT_BINARY, or "cody" on system PATH
     * 3. Auto-downloaded binary at ~/.cody-agent/cody-agent-{VERSION}
     *
     * If tier 3's binary doesn't exist yet, [downloadAgentBinary] is called
     * to fetch it from the npm registry.
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

        val pathBinary = findOnPath("cody")
        if (pathBinary != null) {
            log.info("[CodyAgent] Using 'cody' from PATH: $pathBinary")
            return pathBinary
        }

        // Tier 3: Auto-downloaded binary at ~/.cody-agent/
        val autoPath = getAutoDownloadedBinaryPath()
        if (autoPath.exists()) {
            log.info("[CodyAgent] Using auto-downloaded binary: $autoPath")
            return autoPath.absolutePath
        }

        // Attempt auto-download
        log.info("[CodyAgent] No binary found, attempting auto-download of @sourcegraph/cody v$CODY_AGENT_VERSION")
        return try {
            val downloaded = downloadAgentBinary()
            if (downloaded != null) {
                log.info("[CodyAgent] Auto-download complete: $downloaded")
                downloaded
            } else {
                log.warn("[CodyAgent] Auto-download failed")
                null
            }
        } catch (e: Exception) {
            log.warn("[CodyAgent] Auto-download failed: ${e.message}", e)
            null
        }
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

    private fun getAutoDownloadedBinaryPath(): File {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val suffix = if (isWindows) ".cmd" else ""
        val dir = File(System.getProperty("user.home"), ".cody-agent")
        return File(dir, "cody-agent-$CODY_AGENT_VERSION$suffix")
    }

    /**
     * Downloads @sourcegraph/cody from the npm registry, extracts it,
     * and creates a wrapper script — matching cody_agentic_tool's approach.
     *
     * Requires Node.js to be installed (the agent is a Node.js application).
     */
    private fun downloadAgentBinary(): String? {
        // Check Node.js prerequisite
        val nodePath = findOnPath("node")
        if (nodePath == null) {
            log.warn("[CodyAgent] Node.js not found on PATH — required for Cody Agent. " +
                "Install Node.js from https://nodejs.org/ or install Cody globally: npm install -g @sourcegraph/cody")
            notifyError("Cody Agent requires Node.js. Install Node.js or run: npm install -g @sourcegraph/cody")
            return null
        }
        log.info("[CodyAgent] Node.js found at: $nodePath")

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val agentDir = File(System.getProperty("user.home"), ".cody-agent")
        agentDir.mkdirs()

        val tarballUrl = "https://registry.npmjs.org/@sourcegraph/cody/-/cody-$CODY_AGENT_VERSION.tgz"
        val tarballFile = File(agentDir, "cody-$CODY_AGENT_VERSION.tgz")

        // Download tarball
        log.info("[CodyAgent] Downloading $tarballUrl")
        try {
            URI(tarballUrl).toURL().openStream().use { input ->
                tarballFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            log.error("[CodyAgent] Failed to download tarball: ${e.message}", e)
            tarballFile.delete()
            return null
        }
        log.info("[CodyAgent] Downloaded ${tarballFile.length()} bytes")

        // Extract tarball using tar command
        val packageDir = File(agentDir, "package")
        if (packageDir.exists()) packageDir.deleteRecursively()

        try {
            val extractProc = ProcessBuilder("tar", "xzf", tarballFile.absolutePath, "-C", agentDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            val extractOutput = extractProc.inputStream.bufferedReader().readText()
            val exitCode = extractProc.waitFor()
            if (exitCode != 0) {
                log.error("[CodyAgent] tar extraction failed (exit=$exitCode): $extractOutput")
                return null
            }
        } finally {
            tarballFile.delete()
        }

        val indexJs = File(packageDir, "dist/index.js")
        if (!indexJs.exists()) {
            log.error("[CodyAgent] Expected index.js not found at: ${indexJs.absolutePath}")
            return null
        }

        // Create wrapper script
        val wrapperFile = getAutoDownloadedBinaryPath()
        if (isWindows) {
            wrapperFile.writeText("@echo off\r\n\"$nodePath\" \"${indexJs.absolutePath}\" %*\r\n")
        } else {
            wrapperFile.writeText("#!/bin/sh\nexec \"$nodePath\" \"${indexJs.absolutePath}\" \"$@\"\n")
            try {
                Files.setPosixFilePermissions(
                    wrapperFile.toPath(),
                    PosixFilePermissions.fromString("rwxr-xr-x")
                )
            } catch (e: Exception) {
                // Fallback for systems where setPosixFilePermissions isn't supported
                wrapperFile.setExecutable(true)
            }
        }

        log.info("[CodyAgent] Created wrapper script: ${wrapperFile.absolutePath}")
        return wrapperFile.absolutePath
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
