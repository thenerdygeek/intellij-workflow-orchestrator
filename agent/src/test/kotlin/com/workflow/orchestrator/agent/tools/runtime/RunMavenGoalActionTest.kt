package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.configurations.ConfigurationType
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.framework.MavenUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RunMavenGoalActionTest {

    private val project = mockk<Project>(relaxed = true)
    private val tool = mockk<AgentTool>(relaxed = true)

    @BeforeEach
    fun setup() {
        mockkObject(MavenUtils)
        // Default: pretend project IS Maven. Tests override per case.
        every { MavenUtils.getMavenManager(any()) } returns Any()
        // Default: approval granted. Tests override per case.
        coEvery {
            tool.requestApproval(any(), any(), any(), any())
        } returns ApprovalResult.APPROVED
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `blank goals returns INVALID_ARGS`() = runTest {
        val params = buildJsonObject {
            put("action", "run_maven_goal")
            put("goals", "   ")
        }
        val result = executeRunMavenGoal(params, project, tool)
        assertTrue(result.isError, "expected isError=true for blank goals")
        assertTrue(
            result.content.startsWith("INVALID_ARGS:"),
            "expected content to begin with INVALID_ARGS: prefix, was: ${result.content}"
        )
    }

    @Test
    fun `missing goals param returns INVALID_ARGS`() = runTest {
        val params = buildJsonObject {
            put("action", "run_maven_goal")
            // no "goals" key
        }
        val result = executeRunMavenGoal(params, project, tool)
        assertTrue(result.isError)
        assertTrue(result.content.startsWith("INVALID_ARGS:"))
    }

    @Test
    fun `non-blank goals does not return INVALID_ARGS for blank-goals reason`() = runTest {
        val params = buildJsonObject {
            put("action", "run_maven_goal")
            put("goals", "clean install")
        }
        val result = executeRunMavenGoal(params, project, tool)
        // Result will still error somewhere downstream (no real Maven runner in tests),
        // but the failure reason must not be "goals is blank".
        assertFalse(
            result.content.contains("goals is blank"),
            "expected NOT to fail with blank-goals reason, was: ${result.content}"
        )
    }

    @Test
    fun `non-Maven project returns NOT_A_MAVEN_PROJECT`() = runTest {
        every { MavenUtils.getMavenManager(any()) } returns null
        val params = buildJsonObject {
            put("action", "run_maven_goal")
            put("goals", "clean install")
        }
        val result = executeRunMavenGoal(params, project, tool)
        assertTrue(result.isError)
        assertTrue(
            result.content.startsWith("NOT_A_MAVEN_PROJECT:"),
            "expected content to begin with NOT_A_MAVEN_PROJECT: prefix, was: ${result.content}"
        )
    }

    @Test
    fun `tokenizeExtraArgs splits whitespace`() {
        val tokens = tokenizeExtraArgs("-DskipTests -Pdev -T 4 -U")
        assertEquals(listOf("-DskipTests", "-Pdev", "-T", "4", "-U"), tokens)
    }

    @Test
    fun `tokenizeExtraArgs preserves quoted values`() {
        val tokens = tokenizeExtraArgs("""-Dmessage="hello world" -Pdev""")
        assertEquals(listOf("-Dmessage=hello world", "-Pdev"), tokens)
    }

    @Test
    fun `tokenizeExtraArgs returns empty list for blank input`() {
        assertEquals(emptyList<String>(), tokenizeExtraArgs(""))
        assertEquals(emptyList<String>(), tokenizeExtraArgs("   "))
    }

    @Test
    fun `assembleGoalTokens with goals only`() {
        val tokens = assembleGoalTokens(
            goals = "clean install",
            modules = emptyList(),
            extraTokens = emptyList(),
            offline = false
        )
        assertEquals(listOf("clean", "install"), tokens)
    }

    @Test
    fun `assembleGoalTokens with modules prepends -pl X -am`() {
        val tokens = assembleGoalTokens(
            goals = "clean install",
            modules = listOf("jira", "agent"),
            extraTokens = emptyList(),
            offline = false
        )
        assertEquals(listOf("-pl", "jira,agent", "-am", "clean", "install"), tokens)
    }

    @Test
    fun `assembleGoalTokens with offline appends -o`() {
        val tokens = assembleGoalTokens(
            goals = "verify",
            modules = emptyList(),
            extraTokens = emptyList(),
            offline = true
        )
        assertEquals(listOf("verify", "-o"), tokens)
    }

    @Test
    fun `assembleGoalTokens full combo`() {
        val tokens = assembleGoalTokens(
            goals = "clean install",
            modules = listOf("jira", "agent"),
            extraTokens = listOf("-DskipTests", "-Pdev"),
            offline = true
        )
        assertEquals(
            listOf("-pl", "jira,agent", "-am", "clean", "install", "-DskipTests", "-Pdev", "-o"),
            tokens
        )
    }

    @Test
    fun `approval denial returns APPROVAL_DENIED`() = runTest {
        coEvery {
            tool.requestApproval(any(), any(), any(), any())
        } returns ApprovalResult.DENIED
        val params = buildJsonObject {
            put("action", "run_maven_goal")
            put("goals", "clean install")
        }
        val result = executeRunMavenGoal(params, project, tool)
        assertTrue(result.isError)
        assertTrue(
            result.content.startsWith("APPROVAL_DENIED:"),
            "expected APPROVAL_DENIED: prefix, was: ${result.content}"
        )
    }

    @Test
    fun `formatHeader contains goals modules and exit code`() {
        val header = formatHeader(
            goals = "clean install",
            modules = listOf("jira", "agent"),
            workingDir = "/proj",
            mavenHome = "/usr/local/maven",
            exitCode = 0,
            durationSec = 12.5
        )
        assertTrue(header.contains("Maven goal: clean install"))
        assertTrue(header.contains("Modules: jira, agent"))
        assertTrue(header.contains("Working directory: /proj"))
        assertTrue(header.contains("Exit code: 0"))
        assertTrue(header.contains("Duration: 12.5s"))
    }

    @Test
    fun `formatHeader Modules shows 'all' when modules empty`() {
        val header = formatHeader(
            goals = "test",
            modules = emptyList(),
            workingDir = "/proj",
            mavenHome = "(IDE default)",
            exitCode = 0,
            durationSec = 1.0
        )
        assertTrue(header.contains("Modules: all"))
    }

    @Test
    fun `buildSuccessResult on exit 0 has isError=false and check-mark summary`() {
        val result = buildSuccessResult(
            goals = "clean install",
            modules = emptyList(),
            workingDir = "/proj",
            mavenHome = "(IDE default)",
            exitCode = 0,
            durationSec = 45.3,
            output = "[INFO] BUILD SUCCESS\n",
            project = project
        )
        assertFalse(result.isError, "exit=0 must produce isError=false")
        assertTrue(
            result.summary.contains("✓"),
            "summary should contain check-mark for success, was: ${result.summary}"
        )
        assertTrue(result.summary.contains("45"))
        assertTrue(result.content.contains("[INFO] BUILD SUCCESS"))
    }

    @Test
    fun `buildSuccessResult on non-zero exit has isError=true and BUILD_FAILURE prefix`() {
        val result = buildSuccessResult(
            goals = "clean install",
            modules = emptyList(),
            workingDir = "/proj",
            mavenHome = "(IDE default)",
            exitCode = 1,
            durationSec = 8.2,
            output = "[ERROR] Failed to execute goal\n",
            project = project
        )
        assertTrue(result.isError, "exit=1 must produce isError=true")
        assertTrue(
            result.content.startsWith("BUILD_FAILURE:"),
            "expected BUILD_FAILURE: prefix on non-zero exit, was first 60 chars: ${result.content.take(60)}"
        )
        assertTrue(result.summary.contains("✗"))
        assertTrue(result.summary.contains("exit=1"))
    }

    @Test
    fun `buildTimeoutResult has TIMEOUT prefix and isError=true`() {
        val result = buildTimeoutResult(
            goals = "install",
            modules = emptyList(),
            workingDir = "/proj",
            mavenHome = "(IDE default)",
            timeoutSec = 1200.0,
            output = "[INFO] partial output\n",
            project = project
        )
        assertTrue(result.isError)
        assertTrue(result.content.startsWith("TIMEOUT:"))
        assertTrue(result.content.contains("1200"))
    }

    @Test
    fun `findMavenConfigurationType finds type with id MavenRunConfiguration`() {
        val maven = mockk<ConfigurationType>(relaxed = true).also {
            every { it.id } returns "MavenRunConfiguration"
        }
        val other = mockk<ConfigurationType>(relaxed = true).also {
            every { it.id } returns "JUnit"
        }
        val found = findMavenConfigurationType(listOf(other, maven))
        assertEquals(maven, found)
    }

    @Test
    fun `findMavenConfigurationType returns null when absent`() {
        val other = mockk<ConfigurationType>(relaxed = true).also {
            every { it.id } returns "JUnit"
        }
        assertEquals(null, findMavenConfigurationType(listOf(other)))
    }

    @Test
    fun `findMavenConfigurationType is locale-independent (does not match displayName)`() {
        val mavenLikeDisplayName = mockk<ConfigurationType>(relaxed = true).also {
            every { it.id } returns "SomeOtherId"
            every { it.displayName } returns "Maven"
        }
        assertEquals(null, findMavenConfigurationType(listOf(mavenLikeDisplayName)))
    }
}
