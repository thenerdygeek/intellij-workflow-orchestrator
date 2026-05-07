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
        // Period metadata embedded in the gate response — used as a fallback
        // for /api/new_code_periods/show on tokens without admin permission.
        assertEquals("REFERENCE_BRANCH", result.projectStatus.period?.mode)
        assertEquals("release", result.projectStatus.period?.parameter)
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
        // Sonar 9.6+ Clean Code taxonomy
        assertEquals("LOGICAL", bug.cleanCodeAttribute)
        assertEquals("INTENTIONAL", bug.cleanCodeAttributeCategory)
        assertEquals(1, bug.impacts.size)
        assertEquals("RELIABILITY", bug.impacts[0].softwareQuality)
        assertEquals("HIGH", bug.impacts[0].severity)
        assertEquals("OPEN", bug.issueStatus)

        val vuln = result.issues[1]
        assertEquals("BLOCKER", vuln.severity)
        assertEquals("VULNERABILITY", vuln.type)
        // Multi-impact issue: SECURITY/BLOCKER + RELIABILITY/MEDIUM
        assertEquals(2, vuln.impacts.size)
        assertEquals("SECURITY", vuln.impacts[0].softwareQuality)
        assertEquals("BLOCKER", vuln.impacts[0].severity)
        assertEquals("MEDIUM", vuln.impacts[1].severity)

        // Older Sonar (< 9.6) has no taxonomy fields — defaults preserve compatibility.
        val smell = result.issues[2]
        assertNull(smell.textRange)
        assertEquals("2h", smell.effort)
        assertNull(smell.cleanCodeAttribute)
        assertNull(smell.cleanCodeAttributeCategory)
        assertTrue(smell.impacts.isEmpty())
        assertNull(smell.issueStatus)
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
        val response = json.decodeFromString<SonarSourceLinesResponse>(fixture("source-lines.json"))
        val lines = response.sources
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

    /**
     * R-FIX-1 — round-trips Sonar 25.x's `/api/rules/show` payload (probe-validated).
     * Pre-25.x ships `htmlDesc`/`mdDesc`; 25.x ships `descriptionSections` + the
     * Clean Code taxonomy fields. Plugin must parse both shapes without error.
     */
    @Test
    fun `deserialize 25x rule with descriptionSections`() {
        val response = json.decodeFromString<SonarRuleShowResponseDto>(fixture("rules-show-25x.json"))
        val rule = response.rule
        assertEquals("java:S1135", rule.key)
        assertEquals("Track uses of \"TODO\" tags", rule.name)
        // 25.x: legacy htmlDesc/mdDesc are absent
        assertNull(rule.mdDesc)
        assertNull(rule.htmlDesc)
        // 25.x: structured replacement
        assertTrue(rule.descriptionSections.isNotEmpty(), "descriptionSections must be populated for Sonar 25.x")
        assertTrue(rule.descriptionSections.any { it.key == "root_cause" }, "expected at least one root_cause section")
        assertTrue(rule.descriptionSections.first { it.key == "root_cause" }.content.isNotBlank())
        // 25.x: Clean Code taxonomy
        assertEquals("COMPLETE", rule.cleanCodeAttribute)
        assertEquals("INTENTIONAL", rule.cleanCodeAttributeCategory)
        assertEquals(1, rule.impacts.size)
        assertEquals("MAINTAINABILITY", rule.impacts[0].softwareQuality)
    }

    /**
     * R-EVOLVE-1 + R-FIX-3 — round-trips Sonar 25.x's `/api/qualitygates/project_status`.
     * Verifies caycStatus is parsed and the `period` block carries mode + parameter
     * (the data we use as a fallback for the admin-gated `/api/new_code_periods/show`).
     */
    @Test
    fun `deserialize 25x quality gate with caycStatus + period`() {
        val response = json.decodeFromString<SonarQualityGateResponse>(fixture("qualitygate-status-25x.json"))
        val gate = response.projectStatus
        assertEquals("ERROR", gate.status)
        assertEquals(21, gate.conditions.size)
        // R-EVOLVE-1: caycStatus is now in the DTO
        assertEquals("over-compliant", gate.caycStatus)
        // R-FIX-3: period is the admin-free new-code-period source
        assertNotNull(gate.period)
        assertTrue(gate.period!!.mode.isNotBlank(), "period.mode should be populated")
        assertTrue(gate.period!!.parameter.isNotBlank(), "period.parameter should be populated")
    }

    /**
     * R-ADD-AGENT-1 — round-trips `/api/hotspots/show` for an admin-gated hotspot.
     * The fix recommendations HTML must contain the literal "Compliant Solution"
     * code example the agent feeds the LLM, and `canChangeStatus` must be `false`
     * (the constraint the agent system prompt warns about).
     */
    @Test
    fun `deserialize hotspot detail with fix recommendations`() {
        val dto = json.decodeFromString<SonarHotspotDetailDto>(fixture("hotspots-show-25x.json"))
        assertEquals("java:S2245", dto.rule.key)
        assertEquals("MEDIUM", dto.rule.vulnerabilityProbability)
        assertTrue(dto.rule.riskDescription.isNotBlank())
        assertTrue(dto.rule.fixRecommendations.contains("SecureRandom"),
            "fixRecommendations must contain the SecureRandom Compliant Solution example")
        // CRITICAL: non-admin tokens cannot mark hotspots fixed/safe
        assertFalse(dto.canChangeStatus, "agent system prompt warns about this — must be false on the probe sample")
    }

    /**
     * R-ADD-AGENT-3 — round-trips `/api/issues/search?facets=...&inNewCodePeriod=true`.
     * Verifies the new `facets` field on SonarIssueSearchResult parses the probe-validated
     * facet shape with `@SerialName("val")` mapped onto Kotlin's reserved keyword.
     */
    @Test
    fun `deserialize issues search with facets`() {
        val result = json.decodeFromString<SonarIssueSearchResult>(fixture("issues-search-facets-25x.json"))
        assertTrue(result.facets.isNotEmpty(), "facets must be populated when ?facets= is in the URL")
        assertTrue(result.facets.any { it.property == "impactSoftwareQualities" },
            "expected impactSoftwareQualities facet on the probe sample")
        val severities = result.facets.firstOrNull { it.property == "severities" }
        assertNotNull(severities)
        // Each severity bucket has a `val` field (mapped via @SerialName) and a `count`
        assertTrue(severities!!.values.any { it.value == "BLOCKER" },
            "@SerialName(\"val\") must map to .value")
    }

    /**
     * R-ADD-FEATURE-1 — round-trips `/api/users/current`.
     */
    @Test
    fun `deserialize current user`() {
        val dto = json.decodeFromString<SonarCurrentUserDto>(fixture("users-current.json"))
        assertTrue(dto.login.isNotBlank())
        assertTrue(dto.name.isNotBlank())
        assertTrue(dto.isLoggedIn)
        // Non-admin token has empty global permissions on the probe sample
        assertNotNull(dto.permissions)
        assertTrue(dto.permissions!!.global.isEmpty(),
            "probe was run with non-admin token; permissions.global should be empty")
    }

    /**
     * R-ADD-FEATURE-2 — round-trips `/api/qualitygates/list`.
     */
    @Test
    fun `deserialize quality gates list`() {
        val response = json.decodeFromString<SonarQualityGateListResponse>(fixture("qualitygates-list.json"))
        assertTrue(response.qualitygates.isNotEmpty())
        // At least one gate must be the default
        assertTrue(response.qualitygates.any { it.isDefault })
        // Sonar 25.x ships caycStatus + isAiCodeSupported on every gate
        assertTrue(response.qualitygates.all { it.caycStatus.isNotBlank() })
        // AI Code Fix is unavailable on Community Build — flag must be false everywhere
        assertTrue(response.qualitygates.all { !it.isAiCodeSupported },
            "Community Build returns isAiCodeSupported=false on every gate (R-ADD-AGENT-4)")
    }
}
