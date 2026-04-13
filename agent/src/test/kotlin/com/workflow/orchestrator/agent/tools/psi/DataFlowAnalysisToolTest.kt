package com.workflow.orchestrator.agent.tools.psi

import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DataFlowAnalysisToolTest {

    private val registry = LanguageProviderRegistry()
    private val tool = DataFlowAnalysisTool(registry)

    // ---- Metadata ----

    @Test
    fun `tool name is dataflow_analysis`() {
        assertEquals("dataflow_analysis", tool.name)
    }

    @Test
    fun `description mentions nullability and Java only`() {
        assertTrue(tool.description.contains("nullability", ignoreCase = true))
        assertTrue(tool.description.contains("Java only", ignoreCase = true))
    }

    @Test
    fun `file parameter is required`() {
        assertTrue(tool.parameters.required.contains("file"))
    }

    @Test
    fun `has offset line and column parameters`() {
        val props = tool.parameters.properties
        assertNotNull(props["file"])
        assertNotNull(props["offset"])
        assertNotNull(props["line"])
        assertNotNull(props["column"])
    }

    @Test
    fun `allowed workers include analyzer coder reviewer`() {
        assertEquals(
            setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER),
            tool.allowedWorkers
        )
    }

    // ---- Parameter validation (no project needed) ----

    @Test
    fun `missing file parameter returns error`() = runTest {
        val params = buildJsonObject {
            put("offset", 10)
        }
        val project = io.mockk.mockk<com.intellij.openapi.project.Project>()
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("'file' parameter is required"))
    }

    @Test
    fun `kotlin file returns clear error`() = runTest {
        val params = buildJsonObject {
            put("file", "src/main/kotlin/MyService.kt")
            put("line", 10)
        }
        val project = io.mockk.mockk<com.intellij.openapi.project.Project>()
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("DataFlow analysis is only available for Java files"))
        assertTrue(result.content.contains("Kotlin"))
    }

    @Test
    fun `kts file returns clear error`() = runTest {
        val params = buildJsonObject {
            put("file", "build.gradle.kts")
            put("line", 5)
        }
        val project = io.mockk.mockk<com.intellij.openapi.project.Project>()
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("DataFlow analysis is only available for Java files"))
    }

    @Test
    fun `missing both offset and line returns error`() = runTest {
        val params = buildJsonObject {
            put("file", "MyService.java")
        }
        val project = io.mockk.mockk<com.intellij.openapi.project.Project>()
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("at least one of 'offset' or 'line'"))
    }

    // ---- Tool definition ----

    @Test
    fun `toToolDefinition produces valid definition`() {
        val def = tool.toToolDefinition()
        assertEquals("dataflow_analysis", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertTrue(def.function.parameters.properties.containsKey("file"))
    }
}
