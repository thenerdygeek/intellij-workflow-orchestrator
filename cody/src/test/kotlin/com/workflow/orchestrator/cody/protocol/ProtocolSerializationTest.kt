package com.workflow.orchestrator.cody.protocol

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProtocolSerializationTest {

    private val gson: Gson = GsonBuilder().create()

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @Test
    fun `deserialize initialize response`() {
        val result = gson.fromJson(fixture("initialize-response.json"), ServerInfo::class.java)
        assertEquals("cody-agent", result.name)
        assertTrue(result.authenticated!!)
        assertNotNull(result.authStatus)
        assertEquals("developer", result.authStatus!!.username)
        assertEquals("https://sourcegraph.example.com", result.authStatus!!.endpoint)
        assertEquals("5.5.0", result.authStatus!!.siteVersion)
    }

    @Test
    fun `deserialize edit task`() {
        val result = gson.fromJson(fixture("edit-task.json"), EditTask::class.java)
        assertEquals("edit-task-001", result.id)
        assertEquals("Applied", result.state)
        assertNull(result.error)
        assertNotNull(result.selectionRange)
        assertEquals(10, result.selectionRange!!.start.line)
        assertEquals(42, result.selectionRange!!.end.character)
        assertEquals("Fix the null pointer exception", result.instruction)
    }

    @Test
    fun `deserialize workspace edit with multiple operations`() {
        val result = gson.fromJson(fixture("workspace-edit.json"), WorkspaceEditParams::class.java)
        assertEquals(2, result.operations.size)

        val editOp = result.operations[0]
        assertEquals("edit-file", editOp.type)
        assertEquals("file:///src/main/kotlin/UserService.kt", editOp.uri)
        assertEquals(1, editOp.edits!!.size)
        assertEquals("replace", editOp.edits!![0].type)
        assertEquals(42, editOp.edits!![0].range!!.start.line)
        assertEquals("name?.toUpperCase() ?: \"\"", editOp.edits!![0].value)

        val createOp = result.operations[1]
        assertEquals("create-file", createOp.type)
        assertEquals("file:///src/test/kotlin/UserServiceTest.kt", createOp.uri)
        assertTrue(createOp.textContents!!.contains("UserServiceTest"))
    }

    @Test
    fun `deserialize chat response`() {
        val result = gson.fromJson(fixture("chat-response.json"), ChatResponse::class.java)
        assertEquals("transcript", result.type)
        assertEquals(2, result.messages.size)
        assertEquals("human", result.messages[0].speaker)
        assertEquals("assistant", result.messages[1].speaker)
        assertTrue(result.messages[1].text!!.startsWith("fix:"))
    }

    @Test
    fun `serialize ClientInfo roundtrip`() {
        val clientInfo = ClientInfo(
            version = "1.0.0",
            ideVersion = "IC-2025.1",
            workspaceRootUri = "file:///project",
            extensionConfiguration = ExtensionConfiguration(
                serverEndpoint = "https://sg.example.com",
                accessToken = "sgp_test"
            ),
            capabilities = ClientCapabilities()
        )
        val json = gson.toJson(clientInfo)
        val parsed = gson.fromJson(json, ClientInfo::class.java)
        assertEquals("WorkflowOrchestrator", parsed.name)
        assertEquals("sgp_test", parsed.extensionConfiguration.accessToken)
        assertEquals("streaming", parsed.capabilities.chat)
        assertEquals("none", parsed.capabilities.completions)
    }

    @Test
    fun `serialize EditCommandsCodeParams`() {
        val params = EditCommandsCodeParams(
            instruction = "Fix the NPE on line 42",
            mode = "edit",
            range = Range(
                start = Position(line = 40, character = 0),
                end = Position(line = 45, character = 0)
            )
        )
        val json = gson.toJson(params)
        assertTrue(json.contains("Fix the NPE"))
        assertTrue(json.contains("\"mode\":\"edit\""))
        assertTrue(json.contains("\"line\":40"))
    }

    @Test
    fun `serialize ChatSubmitParams`() {
        val params = ChatSubmitParams(
            id = "chat-123",
            message = ChatMessage(
                text = "Generate a commit message",
                addEnhancedContext = false
            )
        )
        val json = gson.toJson(params)
        assertTrue(json.contains("chat-123"))
        assertTrue(json.contains("Generate a commit message"))
        assertTrue(json.contains("\"addEnhancedContext\":false"))
    }
}
