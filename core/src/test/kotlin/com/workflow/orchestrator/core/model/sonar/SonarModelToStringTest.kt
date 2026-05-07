package com.workflow.orchestrator.core.model.sonar

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Pins the hand-written `toString()` overrides on Sonar domain models that
 * flow through `ServiceLookup.toAgentToolResult`. The default data-class
 * `toString()` would dump multi-KB HTML fields verbatim into LLM context;
 * these overrides keep the agent-facing rendering compact while leaving
 * the structured `data` field intact for callers that need the full body.
 */
class SonarModelToStringTest {

    @Test
    fun `SonarRuleData toString elides description body`() {
        val longHtml = "<p>".repeat(500) + "<pre>code</pre>" + "</p>".repeat(500)
        val rule = SonarRuleData(
            ruleKey = "java:S2245",
            name = "Using PRNGs is security-sensitive",
            description = longHtml,
            remediation = "0min",
            tags = listOf("cwe", "security")
        )
        val s = rule.toString()
        assertTrue(s.startsWith("Rule java:S2245"), "toString: $s")
        assertTrue(s.contains("Effort 0min"))
        assertTrue(s.contains("tags=[cwe, security]"))
        // Body must NOT be inlined
        assertFalse(s.contains("<pre>"), "description HTML must not be in toString: $s")
        assertTrue(s.contains("description=") && s.contains("chars (in data)"))
        // Compact: stays well under 1K even with a 1K+ description input
        assertTrue(s.length < 200, "toString grew unexpectedly: ${s.length}")
    }

    @Test
    fun `SonarRuleData toString empty optionals omitted`() {
        val rule = SonarRuleData(
            ruleKey = "java:S1135",
            name = "Track TODOs",
            description = "",
            remediation = null,
            tags = emptyList()
        )
        val s = rule.toString()
        assertEquals("Rule java:S1135 — \"Track TODOs\"", s)
    }

    @Test
    fun `HotspotDetailData toString elides three HTML bodies`() {
        val data = HotspotDetailData(
            key = "abc-123",
            componentKey = "proj:src/Foo.java",
            componentPath = "src/Foo.java",
            projectKey = "proj",
            ruleKey = "java:S2245",
            ruleName = "PRNG security-sensitive",
            securityCategory = "weak-cryptography",
            vulnerabilityProbability = "MEDIUM",
            riskDescription = "X".repeat(2000),
            vulnerabilityDescription = "Y".repeat(1500),
            fixRecommendations = "Z".repeat(1800),
            status = "TO_REVIEW",
            resolution = null,
            line = 278,
            message = "Use SecureRandom",
            assignee = null,
            author = null,
            canChangeStatus = false
        )
        val s = data.toString()
        // Body must NOT be inlined
        assertFalse(s.contains("X".repeat(50)), "riskDescription must not be inlined")
        assertFalse(s.contains("Y".repeat(50)), "vulnerabilityDescription must not be inlined")
        assertFalse(s.contains("Z".repeat(50)), "fixRecommendations must not be inlined")
        // Actionable signal IS inlined
        assertTrue(s.contains("[MEDIUM/TO_REVIEW]"), "severity/status must show: $s")
        assertTrue(s.contains("Foo.java:278"), "location must show: $s")
        assertTrue(s.contains("canChangeStatus=false"), "agent constraint must show: $s")
        // Char counts for the body sit in data
        assertTrue(s.contains("risk=2000"))
        assertTrue(s.contains("vulnerability=1500"))
        assertTrue(s.contains("fix=1800"))
        // Compact: well under 1K despite 5K of HTML input
        assertTrue(s.length < 600, "toString grew unexpectedly: ${s.length}")
    }

    @Test
    fun `HotspotDetailData toString omits canChangeStatus warning when admin`() {
        val data = HotspotDetailData(
            key = "abc", componentKey = "proj:F.java", componentPath = "F.java",
            projectKey = "proj", ruleKey = "java:S1", ruleName = "n",
            securityCategory = "", vulnerabilityProbability = "LOW",
            riskDescription = "", vulnerabilityDescription = "", fixRecommendations = "",
            status = "REVIEWED", resolution = "FIXED", line = null, message = "",
            assignee = null, author = null, canChangeStatus = true
        )
        val s = data.toString()
        assertFalse(s.contains("canChangeStatus"), "no warning for admin tokens: $s")
        assertTrue(s.contains("[LOW/REVIEWED/FIXED]"))
    }

    @Test
    fun `SonarQualityGateListData toString surfaces default and non-compliant gates`() {
        val data = SonarQualityGateListData(listOf(
            SonarQualityGateEntry("Sonar way", isDefault = false, isBuiltIn = true,
                caycStatus = "compliant", hasStandardConditions = false,
                hasMQRConditions = false, isAiCodeSupported = false),
            SonarQualityGateEntry("Project Default", isDefault = true, isBuiltIn = false,
                caycStatus = "over-compliant", hasStandardConditions = true,
                hasMQRConditions = false, isAiCodeSupported = false),
            SonarQualityGateEntry("legacy-gate", isDefault = false, isBuiltIn = false,
                caycStatus = "non-compliant", hasStandardConditions = true,
                hasMQRConditions = false, isAiCodeSupported = false),
            SonarQualityGateEntry("ai-pilot", isDefault = false, isBuiltIn = false,
                caycStatus = "compliant", hasStandardConditions = false,
                hasMQRConditions = true, isAiCodeSupported = true)
        ))
        val s = data.toString()
        assertTrue(s.startsWith("4 quality gate(s)"), s)
        assertTrue(s.contains("default: \"Project Default\" (over-compliant)"))
        assertTrue(s.contains("Non-compliant gates: legacy-gate"))
        assertTrue(s.contains("AI Code Fix supported: 1 of 4"))
    }

    @Test
    fun `SourceLineData toString surfaces partial-branch state`() {
        val partial = SourceLineData(
            line = 42, code = "if (x && y)",
            coverageStatus = "partially-covered",
            conditions = 2, coveredConditions = 1
        )
        assertEquals("  42 [partial: 1/2 branches] if (x && y)", partial.toString())

        val covered = SourceLineData(
            line = 7, code = "return x", coverageStatus = "covered",
            conditions = null, coveredConditions = null
        )
        assertEquals("   7 [covered] return x", covered.toString())

        val uncovered = SourceLineData(
            line = 9, code = "throw e", coverageStatus = "uncovered",
            conditions = null, coveredConditions = null
        )
        assertEquals("   9 [uncovered] throw e", uncovered.toString())

        val noCoverageInfo = SourceLineData(
            line = 1, code = "// header",
            coverageStatus = null, conditions = null, coveredConditions = null
        )
        assertEquals("   1 // header", noCoverageInfo.toString())
    }
}
