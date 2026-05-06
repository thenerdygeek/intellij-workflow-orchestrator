package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.sonar.api.dto.SonarImpactDto
import com.workflow.orchestrator.sonar.api.dto.SonarIssueDto
import com.workflow.orchestrator.sonar.api.dto.SonarTextRangeDto
import com.workflow.orchestrator.sonar.model.ImpactSeverity
import com.workflow.orchestrator.sonar.model.IssueSeverity
import com.workflow.orchestrator.sonar.model.IssueType
import com.workflow.orchestrator.sonar.model.SoftwareQuality
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
    fun `maps Clean Code taxonomy fields and impacts`() {
        val dtos = listOf(
            SonarIssueDto(
                key = "k1", rule = "java:S2259", severity = "MAJOR",
                message = "m", component = "p:k:src/F.kt", type = "BUG",
                cleanCodeAttribute = "LOGICAL",
                cleanCodeAttributeCategory = "INTENTIONAL",
                impacts = listOf(
                    SonarImpactDto(softwareQuality = "RELIABILITY", severity = "HIGH"),
                    SonarImpactDto(softwareQuality = "MAINTAINABILITY", severity = "LOW"),
                ),
            )
        )

        val result = IssueMapper.mapIssues(dtos, "p:k")

        val mapped = result[0]
        assertEquals("LOGICAL", mapped.cleanCodeAttribute)
        assertEquals("INTENTIONAL", mapped.cleanCodeAttributeCategory)
        assertEquals(2, mapped.impacts.size)
        assertEquals(SoftwareQuality.RELIABILITY, mapped.impacts[0].softwareQuality)
        assertEquals(ImpactSeverity.HIGH, mapped.impacts[0].severity)
        assertEquals(SoftwareQuality.MAINTAINABILITY, mapped.impacts[1].softwareQuality)
        assertEquals(ImpactSeverity.LOW, mapped.impacts[1].severity)
    }

    @Test
    fun `unrecognized impact values fall back to UNKNOWN instead of crashing`() {
        // Future-proofing: if SonarQube introduces a new softwareQuality or severity value,
        // the mapper must downgrade gracefully rather than throw IllegalArgumentException.
        val dtos = listOf(
            SonarIssueDto(
                key = "k1", rule = "r", severity = "MAJOR", message = "m",
                component = "p:k:src/F.kt", type = "BUG",
                impacts = listOf(
                    SonarImpactDto(softwareQuality = "AVAILABILITY", severity = "EXTREME"),
                ),
            )
        )

        val result = IssueMapper.mapIssues(dtos, "p:k")

        val impact = result[0].impacts.single()
        assertEquals(SoftwareQuality.UNKNOWN, impact.softwareQuality)
        assertEquals(ImpactSeverity.UNKNOWN, impact.severity)
    }

    @Test
    fun `older Sonar without taxonomy fields produces empty impacts`() {
        // Pre-9.6 Sonar omits cleanCodeAttribute / impacts entirely. The DTO defaults keep
        // parsing alive; the mapper must surface that as null/empty in the domain model
        // rather than fabricating UNKNOWN entries.
        val dtos = listOf(
            SonarIssueDto(
                key = "k1", rule = "r", severity = "MAJOR", message = "m",
                component = "p:k:src/F.kt", type = "BUG"
            )
        )

        val result = IssueMapper.mapIssues(dtos, "p:k")

        assertNull(result[0].cleanCodeAttribute)
        assertNull(result[0].cleanCodeAttributeCategory)
        assertTrue(result[0].impacts.isEmpty())
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

}
