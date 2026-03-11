package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.sonar.api.dto.SonarIssueDto
import com.workflow.orchestrator.sonar.api.dto.SonarTextRangeDto
import com.workflow.orchestrator.sonar.model.IssueSeverity
import com.workflow.orchestrator.sonar.model.IssueType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IssueMapperTest {

    @Test
    fun `maps issue DTOs to MappedIssues`() {
        val dtos = listOf(
            SonarIssueDto(
                key = "AYz1",
                rule = "java:S2259",
                severity = "CRITICAL",
                message = "NullPointerException possible",
                component = "com.myapp:my-app:src/main/kotlin/com/myapp/UserService.kt",
                type = "BUG",
                effort = "15min",
                textRange = SonarTextRangeDto(startLine = 42, endLine = 42, startOffset = 8, endOffset = 32)
            )
        )

        val result = IssueMapper.mapIssues(dtos, "com.myapp:my-app")

        assertEquals(1, result.size)
        val issue = result[0]
        assertEquals("AYz1", issue.key)
        assertEquals(IssueType.BUG, issue.type)
        assertEquals(IssueSeverity.CRITICAL, issue.severity)
        assertEquals("src/main/kotlin/com/myapp/UserService.kt", issue.filePath)
        assertEquals(42, issue.startLine)
        assertEquals(8, issue.startOffset)
    }

    @Test
    fun `strips project key prefix from component path`() {
        val dtos = listOf(
            SonarIssueDto(
                key = "k1", rule = "r", severity = "MAJOR", message = "msg",
                component = "org.example:proj:src/main/java/Foo.java",
                type = "CODE_SMELL"
            )
        )

        val result = IssueMapper.mapIssues(dtos, "org.example:proj")

        assertEquals("src/main/java/Foo.java", result[0].filePath)
    }

    @Test
    fun `defaults textRange to line 1 when absent`() {
        val dtos = listOf(
            SonarIssueDto(
                key = "k1", rule = "r", severity = "INFO", message = "msg",
                component = "p:k:src/File.kt", type = "CODE_SMELL", textRange = null
            )
        )

        val result = IssueMapper.mapIssues(dtos, "p:k")

        assertEquals(1, result[0].startLine)
        assertEquals(1, result[0].endLine)
    }

    @Test
    fun `maps all severity levels`() {
        val severities = listOf("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO")
        val dtos = severities.mapIndexed { i, sev ->
            SonarIssueDto(
                key = "k$i", rule = "r", severity = sev, message = "m",
                component = "p:k:src/F.kt", type = "BUG"
            )
        }

        val result = IssueMapper.mapIssues(dtos, "p:k")

        assertEquals(IssueSeverity.BLOCKER, result[0].severity)
        assertEquals(IssueSeverity.CRITICAL, result[1].severity)
        assertEquals(IssueSeverity.MAJOR, result[2].severity)
        assertEquals(IssueSeverity.MINOR, result[3].severity)
        assertEquals(IssueSeverity.INFO, result[4].severity)
    }

    @Test
    fun `maps all issue types`() {
        val types = listOf("BUG", "VULNERABILITY", "CODE_SMELL", "SECURITY_HOTSPOT")
        val dtos = types.mapIndexed { i, t ->
            SonarIssueDto(
                key = "k$i", rule = "r", severity = "MAJOR", message = "m",
                component = "p:k:src/F.kt", type = t
            )
        }

        val result = IssueMapper.mapIssues(dtos, "p:k")

        assertEquals(IssueType.BUG, result[0].type)
        assertEquals(IssueType.VULNERABILITY, result[1].type)
        assertEquals(IssueType.CODE_SMELL, result[2].type)
        assertEquals(IssueType.SECURITY_HOTSPOT, result[3].type)
    }

    @Test
    fun `groups issues by file path`() {
        val dtos = listOf(
            SonarIssueDto(key = "k1", rule = "r", severity = "MAJOR", message = "m1",
                component = "p:k:src/A.kt", type = "BUG"),
            SonarIssueDto(key = "k2", rule = "r", severity = "MINOR", message = "m2",
                component = "p:k:src/A.kt", type = "BUG"),
            SonarIssueDto(key = "k3", rule = "r", severity = "INFO", message = "m3",
                component = "p:k:src/B.kt", type = "CODE_SMELL")
        )

        val result = IssueMapper.groupByFile(IssueMapper.mapIssues(dtos, "p:k"))

        assertEquals(2, result.size)
        assertEquals(2, result["src/A.kt"]?.size)
        assertEquals(1, result["src/B.kt"]?.size)
    }
}
