package com.workflow.orchestrator.cody.agent

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

class CodyAgentServerTest {

    @Test
    fun `initialize method has correct JsonRequest annotation`() {
        val method = findMethod("initialize")
        val annotation = method.getAnnotation(JsonRequest::class.java)
        assertNotNull(annotation, "initialize must have @JsonRequest")
        assertEquals("initialize", annotation.value)
    }

    @Test
    fun `initialized method has correct JsonNotification annotation`() {
        val method = findMethod("initialized")
        val annotation = method.getAnnotation(JsonNotification::class.java)
        assertNotNull(annotation, "initialized must have @JsonNotification")
        assertEquals("initialized", annotation.value)
    }

    @Test
    fun `shutdown method has correct JsonRequest annotation`() {
        val method = findMethod("shutdown")
        val annotation = method.getAnnotation(JsonRequest::class.java)
        assertNotNull(annotation, "shutdown must have @JsonRequest")
        assertEquals("shutdown", annotation.value)
    }

    @Test
    fun `editCommandsCode method has correct annotation`() {
        val method = findMethod("editCommandsCode")
        val annotation = method.getAnnotation(JsonRequest::class.java)
        assertNotNull(annotation)
        assertEquals("editCommands/code", annotation.value)
    }

    @Test
    fun `chatNew method has correct annotation`() {
        val method = findMethod("chatNew")
        val annotation = method.getAnnotation(JsonRequest::class.java)
        assertNotNull(annotation)
        assertEquals("chat/new", annotation.value)
    }

    @Test
    fun `textDocumentDidOpen is a notification not a request`() {
        val method = findMethod("textDocumentDidOpen")
        assertNull(method.getAnnotation(JsonRequest::class.java))
        val annotation = method.getAnnotation(JsonNotification::class.java)
        assertNotNull(annotation)
        assertEquals("textDocument/didOpen", annotation.value)
    }

    @Test
    fun `all server methods are defined`() {
        val expectedMethods = listOf(
            "initialize", "initialized", "shutdown", "exit",
            "editCommandsCode", "editTaskAccept", "editTaskUndo", "editTaskCancel",
            "chatNew", "chatSubmitMessage",
            "textDocumentDidOpen", "textDocumentDidChange", "textDocumentDidFocus", "textDocumentDidClose",
            "codeActionsProvide", "codeActionsTrigger",
            "commandsTest", "commandExecute"
        )
        val actual = CodyAgentServer::class.java.declaredMethods.map { it.name }.toSet()
        for (name in expectedMethods) {
            assertTrue(name in actual, "Missing method: $name")
        }
    }

    private fun findMethod(name: String): Method =
        CodyAgentServer::class.java.declaredMethods.first { it.name == name }
}
