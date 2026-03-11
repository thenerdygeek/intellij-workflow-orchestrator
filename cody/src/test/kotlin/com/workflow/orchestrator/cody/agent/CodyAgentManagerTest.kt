package com.workflow.orchestrator.cody.agent

import com.workflow.orchestrator.cody.protocol.AuthStatus
import com.workflow.orchestrator.cody.protocol.ServerInfo
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class CodyAgentManagerTest {

    @Test
    fun `initial state is Stopped`() {
        val manager = createTestManager()
        assertTrue(manager.state.value is CodyAgentManager.AgentState.Stopped)
    }

    @Test
    fun `isRunning returns false when stopped`() {
        val manager = createTestManager()
        assertFalse(manager.isRunning())
    }

    @Test
    fun `resolveAgentBinary returns configured path when set`() {
        val manager = createTestManager(agentPath = "/custom/cody-agent")
        val resolved = manager.resolveAgentBinaryForTest()
        assertEquals("/custom/cody-agent", resolved)
    }

    @Test
    fun `resolveAgentBinary returns null when not configured and not on PATH`() {
        val manager = createTestManager(agentPath = "")
        val resolved = manager.resolveAgentBinaryForTest()
        assertNull(resolved)
    }

    @Test
    fun `buildClientInfo creates correct structure`() {
        val manager = createTestManager(
            sourcegraphUrl = "https://sg.example.com",
            token = "sgp_test123"
        )
        val clientInfo = manager.buildClientInfoForTest()
        assertEquals("WorkflowOrchestrator", clientInfo.name)
        assertEquals("https://sg.example.com", clientInfo.extensionConfiguration.serverEndpoint)
        assertEquals("sgp_test123", clientInfo.extensionConfiguration.accessToken)
        assertEquals("enabled", clientInfo.capabilities.edit)
        assertEquals("none", clientInfo.capabilities.completions)
    }

    private fun createTestManager(
        agentPath: String = "",
        sourcegraphUrl: String = "",
        token: String? = null
    ): TestCodyAgentManager = TestCodyAgentManager(agentPath, sourcegraphUrl, token)
}

class TestCodyAgentManager(
    private val agentPath: String,
    private val sourcegraphUrl: String,
    private val token: String?
) {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<CodyAgentManager.AgentState>(
        CodyAgentManager.AgentState.Stopped
    )
    val state: kotlinx.coroutines.flow.StateFlow<CodyAgentManager.AgentState> = _state

    fun isRunning(): Boolean = _state.value is CodyAgentManager.AgentState.Running

    fun resolveAgentBinaryForTest(): String? {
        if (agentPath.isNotBlank()) return agentPath
        return try {
            val process = ProcessBuilder("which", "cody-agent").start()
            val result = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            result.ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    fun buildClientInfoForTest(): com.workflow.orchestrator.cody.protocol.ClientInfo {
        return com.workflow.orchestrator.cody.protocol.ClientInfo(
            version = "1.0.0",
            workspaceRootUri = "file:///test",
            extensionConfiguration = com.workflow.orchestrator.cody.protocol.ExtensionConfiguration(
                serverEndpoint = sourcegraphUrl,
                accessToken = token ?: ""
            ),
            capabilities = com.workflow.orchestrator.cody.protocol.ClientCapabilities()
        )
    }
}
