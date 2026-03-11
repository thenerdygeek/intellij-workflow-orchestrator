package com.workflow.orchestrator.sonar.api.dto

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SonarDtoSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @Test
    fun `deserialize auth validation`() {
        val result = json.decodeFromString<SonarValidationDto>(fixture("auth-validate.json"))
        assertTrue(result.valid)
    }

    @Test
    fun `deserialize projects search`() {
        val result = json.decodeFromString<SonarProjectSearchResult>(fixture("projects-search.json"))
        assertEquals(2, result.components.size)
        assertEquals("com.myapp:my-app", result.components[0].key)
        assertEquals("My App", result.components[0].name)
        assertEquals(2, result.paging.total)
    }

    @Test
    fun `deserialize quality gate passed`() {
        val result = json.decodeFromString<SonarQualityGateResponse>(fixture("qualitygate-status-passed.json"))
        assertEquals("OK", result.projectStatus.status)
        assertEquals(3, result.projectStatus.conditions.size)
        assertEquals("new_coverage", result.projectStatus.conditions[0].metricKey)
        assertEquals("87.3", result.projectStatus.conditions[0].actualValue)
    }

    @Test
    fun `deserialize quality gate failed`() {
        val result = json.decodeFromString<SonarQualityGateResponse>(fixture("qualitygate-status-failed.json"))
        assertEquals("ERROR", result.projectStatus.status)
        assertEquals("ERROR", result.projectStatus.conditions[0].status)
        assertEquals("42.1", result.projectStatus.conditions[0].actualValue)
    }

    @Test
    fun `deserialize issues search`() {
        val result = json.decodeFromString<SonarIssueSearchResult>(fixture("issues-search.json"))
        assertEquals(3, result.issues.size)

        val bug = result.issues[0]
        assertEquals("AYz1", bug.key)
        assertEquals("CRITICAL", bug.severity)
        assertEquals("BUG", bug.type)
        assertEquals(42, bug.textRange?.startLine)

        val vuln = result.issues[1]
        assertEquals("BLOCKER", vuln.severity)
        assertEquals("VULNERABILITY", vuln.type)

        val smell = result.issues[2]
        assertNull(smell.textRange)
        assertEquals("2h", smell.effort)
    }

    @Test
    fun `deserialize measures component tree`() {
        val result = json.decodeFromString<SonarMeasureSearchResult>(fixture("measures-component-tree.json"))
        assertEquals(2, result.components.size)
        assertEquals("com.myapp:my-app", result.baseComponent?.key)

        val first = result.components[0]
        assertEquals("src/main/kotlin/com/myapp/service/UserService.kt", first.path)
        assertEquals(5, first.measures.size)
        assertEquals("72.1", first.measures.first { it.metric == "coverage" }.value)
    }

    @Test
    fun `deserialize source lines`() {
        val lines = json.decodeFromString<List<SonarSourceLineDto>>(fixture("source-lines.json"))
        assertEquals(8, lines.size)

        val coveredLine = lines[0]
        assertEquals(38, coveredLine.line)
        assertEquals(5, coveredLine.lineHits)
        assertNull(coveredLine.conditions)

        val partialLine = lines[2]
        assertEquals(40, partialLine.line)
        assertEquals(2, partialLine.conditions)
        assertEquals(1, partialLine.coveredConditions)

        val uncoveredLine = lines[6]
        assertEquals(44, uncoveredLine.line)
        assertEquals(0, uncoveredLine.lineHits)
    }
}
