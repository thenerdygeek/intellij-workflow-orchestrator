package com.workflow.orchestrator.agent.tools.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ComponentManager
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.model.build.BuildProblem
import com.workflow.orchestrator.core.model.build.BuildSource
import com.workflow.orchestrator.core.model.build.ProblemType
import com.workflow.orchestrator.core.model.build.Severity
import com.workflow.orchestrator.core.services.BuildProblemsService
import com.workflow.orchestrator.core.services.ToolResult as CoreToolResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildProblemsToolTest {

    private val tool = BuildProblemsTool()

    @Test
    fun `tool metadata has correct name and is not Bamboo-confusable`() {
        assertEquals("get_build_problems", tool.name)
        assertTrue(tool.description.contains("LOCAL IDE ONLY"), "description must lead with LOCAL IDE ONLY for tool selection")
        assertTrue(tool.description.contains("bamboo_builds"), "description must reference bamboo_builds for disambiguation")
        assertEquals(setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER), tool.allowedWorkers)
    }

    @Test
    fun `parameter schema declares source and severity enums`() {
        val params = tool.parameters
        assertTrue(params.properties.containsKey("source"))
        assertTrue(params.properties.containsKey("severity"))
        assertEquals(listOf("maven", "gradle", "compile", "all"), params.properties["source"]?.enumValues)
        assertEquals(listOf("error", "warning", "all"), params.properties["severity"]?.enumValues)
    }

    @Test
    fun `invalid source value is rejected without calling service`() = runTest {
        val project = projectMockReturning(emptyList())
        val result = tool.execute(buildJsonObject { put("source", "invalid") }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Invalid 'source' value"))
    }

    @Test
    fun `invalid severity value is rejected without calling service`() = runTest {
        val project = projectMockReturning(emptyList())
        val result = tool.execute(buildJsonObject { put("severity", "fatal") }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Invalid 'severity' value"))
    }

    @Test
    fun `empty result formats clean summary`() = runTest {
        val project = projectMockReturning(emptyList())
        val result = tool.execute(JsonObject(emptyMap()), project)
        assertFalse(result.isError)
        assertTrue(result.content.contains("No build/import problems"))
    }

    @Test
    fun `dependency problem is rendered with artifact coordinates and source label`() = runTest {
        val project = projectMockReturning(
            listOf(
                BuildProblem(
                    source = BuildSource.MAVEN_IMPORT,
                    projectPath = "/proj/pom.xml",
                    description = "Could not transfer artifact org.foo:bar:jar:1.2.3 (401)",
                    type = ProblemType.DEPENDENCY,
                    severity = Severity.ERROR,
                    artifactCoords = "org.foo:bar:jar:1.2.3",
                ),
            )
        )
        val result = tool.execute(JsonObject(emptyMap()), project)
        assertFalse(result.isError)
        assertTrue(result.content.contains("/proj/pom.xml"), "expected pom path in content, got: ${result.content}")
        assertTrue(result.content.contains("org.foo:bar:jar:1.2.3"))
        assertTrue(result.content.contains("MAVEN_IMPORT"))
        assertTrue(result.content.contains("DEPENDENCY"))
    }

    @Test
    fun `source filter excludes mismatched problems`() = runTest {
        val project = projectMockReturning(
            listOf(
                buildProblem(BuildSource.MAVEN_IMPORT, ProblemType.DEPENDENCY, "/a/pom.xml"),
                buildProblem(BuildSource.GRADLE_IMPORT, ProblemType.DEPENDENCY, "/a/build.gradle"),
            )
        )
        val result = tool.execute(buildJsonObject { put("source", "maven") }, project)
        assertFalse(result.isError)
        assertTrue(result.content.contains("/a/pom.xml"))
        assertFalse(result.content.contains("/a/build.gradle"))
    }

    @Test
    fun `severity filter excludes warnings when error requested`() = runTest {
        val project = projectMockReturning(
            listOf(
                buildProblem(BuildSource.MAVEN_IMPORT, ProblemType.DEPENDENCY, "/a/pom.xml", Severity.ERROR),
                buildProblem(BuildSource.MAVEN_IMPORT, ProblemType.STRUCTURE, "/b/pom.xml", Severity.WARNING),
            )
        )
        val result = tool.execute(buildJsonObject { put("severity", "error") }, project)
        assertFalse(result.isError)
        assertTrue(result.content.contains("/a/pom.xml"))
        assertFalse(result.content.contains("/b/pom.xml"))
    }

    @Test
    fun `service-level error propagates as tool error`() = runTest {
        val project = projectMockReturningError(
            CoreToolResult(
                data = emptyList(),
                summary = "Maven plugin not present.",
                isError = true,
                hint = "Install the Maven plugin.",
            )
        )
        val result = tool.execute(JsonObject(emptyMap()), project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Maven plugin not present"))
        assertTrue(result.content.contains("Hint: Install the Maven plugin"))
    }

    private fun buildProblem(
        source: BuildSource,
        type: ProblemType,
        path: String,
        severity: Severity = Severity.ERROR,
    ) = BuildProblem(
        source = source,
        projectPath = path,
        description = "test problem",
        type = type,
        severity = severity,
    )

    private fun projectMockReturning(data: List<BuildProblem>): Project =
        projectMockReturningResult(CoreToolResult.success(data, summary = "${data.size} build/import problem(s)"))

    private fun projectMockReturningError(result: CoreToolResult<List<BuildProblem>>): Project =
        projectMockReturningResult(result)

    private fun projectMockReturningResult(result: CoreToolResult<List<BuildProblem>>): Project {
        val service = mockk<BuildProblemsService>()
        coEvery { service.getRecentBuildProblems() } returns result
        val project = mockk<Project>(relaxed = true)
        every { project.getService(BuildProblemsService::class.java) } returns service
        // BuildProblemsService.getInstance(project) calls project.service<BuildProblemsService>()
        // which delegates to ApplicationManager.getApplication() in some platform paths.
        // Stub the application getService too as a defense-in-depth.
        val app = mockk<ComponentManager>(relaxed = true)
        every { app.getService(BuildProblemsService::class.java) } returns service
        return project
    }
}
