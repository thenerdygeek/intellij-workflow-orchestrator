package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.CompletionKind
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AttemptCompletionToolTest {

    private val project = mockk<Project>(relaxed = true)
    private val tool = AttemptCompletionTool()

    @Test
    fun `tool name is attempt_completion`() {
        assertEquals("attempt_completion", tool.name)
    }

    @Test
    fun `kind and result are required parameters`() {
        assertTrue(tool.parameters.required.contains("kind"))
        assertTrue(tool.parameters.required.contains("result"))
        assertFalse(tool.parameters.required.contains("command"))
        assertFalse(tool.parameters.required.contains("verify_how"))
        assertFalse(tool.parameters.required.contains("discovery"))
    }

    @Test
    fun `allowedWorkers contains only ORCHESTRATOR`() {
        assertEquals(setOf(WorkerType.ORCHESTRATOR), tool.allowedWorkers)
    }

    @Test
    fun `kind=done returns completion result`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("kind", "done")
            put("result", "Done")
        }, project)
        assertTrue(result.isCompletion)
        assertFalse(result.isError)
        assertEquals(CompletionKind.DONE, result.completionData?.kind)
        assertEquals("Done", result.completionData?.result)
    }

    @Test
    fun `kind=review returns completion result`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("kind", "review")
            put("result", "Please check")
        }, project)
        assertTrue(result.isCompletion)
        assertFalse(result.isError)
        assertEquals(CompletionKind.REVIEW, result.completionData?.kind)
    }

    @Test
    fun `kind=heads_up with discovery returns completion result`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("kind", "heads_up")
            put("result", "Done")
            put("discovery", "3 orphaned tables found")
        }, project)
        assertTrue(result.isCompletion)
        assertFalse(result.isError)
        assertEquals(CompletionKind.HEADS_UP, result.completionData?.kind)
        assertEquals("3 orphaned tables found", result.completionData?.discovery)
    }

    @Test
    fun `kind=heads_up without discovery returns error`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("kind", "heads_up")
            put("result", "Done")
        }, project)
        assertTrue(result.isError)
        assertFalse(result.isCompletion)
    }

    @Test
    fun `missing result returns error`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("kind", "done")
        }, project)
        assertTrue(result.isError)
        assertFalse(result.isCompletion)
    }

    @Test
    fun `missing kind returns error`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("result", "Done")
        }, project)
        assertTrue(result.isError)
        assertFalse(result.isCompletion)
    }

    @Test
    fun `invalid kind returns error`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("kind", "invalid")
            put("result", "Done")
        }, project)
        assertTrue(result.isError)
        assertFalse(result.isCompletion)
    }

    @Test
    fun `verify_how is carried through when provided`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("kind", "done")
            put("result", "Done")
            put("verify_how", "./gradlew test")
        }, project)
        assertTrue(result.isCompletion)
        assertEquals("./gradlew test", result.completionData?.verifyHow)
    }

    @Test
    fun `verify_how is null when omitted`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("kind", "done")
            put("result", "Done")
        }, project)
        assertTrue(result.isCompletion)
        assertNull(result.completionData?.verifyHow)
    }
}
