package com.workflow.orchestrator.agent.tools.runtime

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
}
