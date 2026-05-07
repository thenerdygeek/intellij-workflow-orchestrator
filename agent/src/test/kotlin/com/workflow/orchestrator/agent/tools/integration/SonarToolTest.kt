package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.core.model.sonar.SonarBranchData
import com.workflow.orchestrator.core.model.sonar.SonarFileComponent
import com.workflow.orchestrator.core.model.sonar.SonarRuleData
import com.workflow.orchestrator.core.services.SonarService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SonarTool]:
 *  - rule action: returns rule details, errors on missing rule_key
 *  - branches.include_files=true appends file list
 *  - branches without include_files does NOT fetch file list
 *
 * Exercises [SonarTool.executeRuleForTest] and [SonarTool.executeGetBranchesForTest]
 * so tests do not require IntelliJ service infrastructure
 * (mirrors JiraToolGetTicketTest / BambooBuildsToolTest pattern).
 *
 * Run with: ./gradlew :agent:test --tests "*SonarToolTest*"
 */
class SonarToolTest {

    private val tool = SonarTool()

    private fun ruleResult() = ToolResult(
        data = SonarRuleData(
            ruleKey = "java:S1234",
            name = "Null pointers should not be dereferenced",
            description = "<p>Dereferencing null pointers...</p>",
            remediation = "5min",
            tags = listOf("suspicious", "cwe")
        ),
        summary = "Rule java:S1234: Null pointers should not be dereferenced",
        isError = false
    )

    private fun branchesResult() = ToolResult(
        data = listOf(
            SonarBranchData(
                name = "main",
                isMain = true,
                type = "LONG",
                qualityGateStatus = "OK"
            )
        ),
        summary = "1 branch(es) for PROJ-X",
        isError = false
    )

    private fun fileComponentsResult() = ToolResult(
        data = listOf(
            SonarFileComponent(key = "PROJ-X:src/Main.java", name = "Main.java", path = "src/Main.java"),
            SonarFileComponent(key = "PROJ-X:src/Util.java", name = "Util.java", path = "src/Util.java")
        ),
        summary = "2 file component(s) for PROJ-X",
        isError = false
    )

    // ── rule returns rule details for known rule key ──────────────────────────

    @Test
    fun `rule returns rule details for known rule key`() = runTest {
        val service = mockk<SonarService>()
        coEvery { service.getRule("java:S1234", null) } returns ruleResult()

        val result = tool.executeRuleForTest(
            ruleKey = "java:S1234",
            repoName = null,
            service = service
        )

        assertFalse(result.isError)
        assertTrue(
            result.content.contains("java:S1234"),
            "content must reference the rule key"
        )
        assertTrue(
            result.content.contains("Null pointers should not be dereferenced") ||
                result.content.contains("java:S1234"),
            "content must reference the rule name or key from stub"
        )
        coVerify(exactly = 1) { service.getRule("java:S1234", null) }
    }

    // ── rule missing rule_key errors ──────────────────────────────────────────

    @Test
    fun `rule missing rule_key errors`() = runTest {
        val service = mockk<SonarService>()

        val result = tool.executeRuleForTest(
            ruleKey = null,
            repoName = null,
            service = service
        )

        assertTrue(result.isError, "result must be an error when rule_key is missing")
        assertTrue(
            result.content.contains("rule_key"),
            "error content must reference 'rule_key'"
        )
        coVerify(exactly = 0) { service.getRule(any(), any()) }
    }

    // ── branches with include_files=true appends file list ───────────────────

    @Test
    fun `branches with include_files=true appends file list`() = runTest {
        val service = mockk<SonarService>()
        coEvery { service.getBranches("PROJ-X", repoName = null) } returns branchesResult()
        coEvery { service.listFileComponents("PROJ-X", null, null) } returns fileComponentsResult()

        val result = tool.executeGetBranchesForTest(
            projectKey = "PROJ-X",
            branch = null,
            repoName = null,
            includeFiles = true,
            service = service
        )

        assertFalse(result.isError)
        assertTrue(
            result.content.contains("Files"),
            "content must contain a 'Files' header"
        )
        assertTrue(
            result.content.contains("src/Main.java") || result.content.contains("Main.java"),
            "content must reference at least one file path from the stub"
        )
        coVerify(exactly = 1) { service.listFileComponents("PROJ-X", null, null) }
    }

    // ── branches without include_files does NOT fetch file list ──────────────

    @Test
    fun `branches without include_files does NOT fetch file list`() = runTest {
        val service = mockk<SonarService>()
        coEvery { service.getBranches("PROJ-X", repoName = null) } returns branchesResult()

        tool.executeGetBranchesForTest(
            projectKey = "PROJ-X",
            branch = null,
            repoName = null,
            includeFiles = false,
            service = service
        )

        coVerify(exactly = 0) { service.listFileComponents(any(), any(), any()) }
    }
}
