package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RenderArtifactToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = RenderArtifactTool()

    @Test
    fun `tool name is render_artifact`() {
        assertEquals("render_artifact", tool.name)
    }

    @Test
    fun `allowedWorkers includes ORCHESTRATOR and ANALYZER`() {
        assertTrue(tool.allowedWorkers.contains(WorkerType.ORCHESTRATOR))
        assertTrue(tool.allowedWorkers.contains(WorkerType.ANALYZER))
    }

    @Test
    fun `returns artifact payload on valid input`() = runTest {
        val params = buildJsonObject {
            put("title", "Auth Flow")
            put("source", "export default function App({ bridge }) { return <div>Hello</div>; }")
        }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertNotNull(result.artifact)
        assertEquals("Auth Flow", result.artifact!!.title)
        assertEquals(
            "export default function App({ bridge }) { return <div>Hello</div>; }",
            result.artifact!!.source
        )
        assertTrue(result.content.contains("Auth Flow"))
        assertTrue(result.summary.contains("Auth Flow"))
    }

    @Test
    fun `error on missing title`() = runTest {
        val params = buildJsonObject {
            put("source", "export default function App() { return <div/>; }")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("title"))
    }

    @Test
    fun `error on missing source`() = runTest {
        val params = buildJsonObject {
            put("title", "Test Artifact")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("source"))
    }

    @Test
    fun `error when source missing default export`() = runTest {
        val params = buildJsonObject {
            put("title", "Bad Component")
            put("source", "function Foo() { return <div/>; }")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("default"))
    }
}
