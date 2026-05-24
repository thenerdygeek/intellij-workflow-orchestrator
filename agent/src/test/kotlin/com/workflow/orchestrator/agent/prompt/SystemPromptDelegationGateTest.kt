package com.workflow.orchestrator.agent.prompt

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Verifies that cross-IDE delegation prompt hints and the `cross-ide-delegation`
 * skill listing are gated on [SystemPrompt.build]'s `delegationOutboundEnabled` flag.
 *
 * When the flag is false (default), neither the Section-5 task-to-tool hint rows
 * nor the skill description must appear — preventing "Unknown tool" failures when
 * the delegation tools are not registered.
 *
 * When the flag is true, both must appear so the LLM knows the tools exist.
 */
class SystemPromptDelegationGateTest {

    // ---- helpers ----

    private fun buildWith(
        delegationOutboundEnabled: Boolean,
        skills: List<SkillMetadata>? = null
    ) = SystemPrompt.build(
        projectName = "GateTestProject",
        projectPath = "/gate/test/project",
        osName = "Linux",
        shell = "/bin/bash",
        availableSkills = skills,
        delegationOutboundEnabled = delegationOutboundEnabled
    )

    private fun crossIdeDelegationSkill() = SkillMetadata(
        name = "cross-ide-delegation",
        description = "Delegate tasks to an agent in a different IDE window",
        path = "/skills/cross-ide-delegation/SKILL.md",
        source = SkillSource.BUNDLED,
        userInvocable = false
    )

    private fun otherSkill() = SkillMetadata(
        name = "some-other-skill",
        description = "Does something unrelated to delegation",
        path = "/skills/some-other-skill/SKILL.md",
        source = SkillSource.BUNDLED,
        userInvocable = true
    )

    // ---- Section-5 capability hint tests ----

    @Test
    fun `delegation hints absent when outbound disabled (default)`() {
        val prompt = buildWith(delegationOutboundEnabled = false)
        assertFalse(
            prompt.contains("delegation with action=\"send\""),
            "Section-5 delegation send hint must be absent when outbound off"
        )
        assertFalse(
            prompt.contains("Cross-IDE delegation UX") || prompt.contains("cross-IDE delegation UX"),
            "Picker UX note must be absent when outbound off"
        )
        assertFalse(
            prompt.contains("delegation with action=\"fetch_transcript\""),
            "delegation fetch_transcript hint must be absent when outbound off"
        )
    }

    @Test
    fun `delegation hints absent with default parameter (delegationOutboundEnabled omitted)`() {
        // Calling build() without delegationOutboundEnabled should behave identically to false.
        val prompt = SystemPrompt.build(
            projectName = "GateTestProject",
            projectPath = "/gate/test/project",
            osName = "Linux",
            shell = "/bin/bash",
        )
        assertFalse(
            prompt.contains("delegation with action=\"send\""),
            "delegation send hint must be absent by default (safe default posture)"
        )
    }

    @Test
    fun `delegation hints present when outbound enabled`() {
        val prompt = buildWith(delegationOutboundEnabled = true)
        assertTrue(
            prompt.contains("delegation with action=\"send\""),
            "Section-5 delegation send hint must be present when outbound on"
        )
        assertTrue(
            prompt.contains("Cross-IDE delegation UX"),
            "Picker UX note must be present when outbound on"
        )
        assertTrue(
            prompt.contains("delegation with action=\"fetch_transcript\""),
            "delegation fetch_transcript hint must be present when outbound on"
        )
    }

    // ---- Section-6 skill listing tests ----

    @Test
    fun `cross-ide-delegation skill listing absent when outbound disabled`() {
        val skills = listOf(crossIdeDelegationSkill(), otherSkill())
        val prompt = buildWith(delegationOutboundEnabled = false, skills = skills)
        assertFalse(
            prompt.contains("cross-ide-delegation"),
            "Skill listing must be filtered out when outbound off"
        )
        assertTrue(
            prompt.contains("some-other-skill"),
            "Other skills must still appear in the listing"
        )
    }

    @Test
    fun `cross-ide-delegation skill listing present when outbound enabled`() {
        val skills = listOf(crossIdeDelegationSkill(), otherSkill())
        val prompt = buildWith(delegationOutboundEnabled = true, skills = skills)
        assertTrue(
            prompt.contains("cross-ide-delegation"),
            "Skill listing must appear when outbound on"
        )
        assertTrue(
            prompt.contains("some-other-skill"),
            "Other skills must also appear when outbound on"
        )
    }

    @Test
    fun `no skills list — cross-ide-delegation hint still absent when outbound disabled`() {
        // No availableSkills passed at all — skill section must simply not appear,
        // not throw or leak anything.
        val prompt = buildWith(delegationOutboundEnabled = false, skills = null)
        assertFalse(prompt.contains("cross-ide-delegation"))
        assertFalse(prompt.contains("delegation with action=\"send\""))
    }

    // ---- Sub-agent invariant ----

    @Test
    fun `sub-agent builder always produces prompt without delegation hints`() {
        // SubagentSystemPromptBuilder hard-codes delegationOutboundEnabled=false.
        // We smoke-test its output here by building directly through the builder.
        val prompt = com.workflow.orchestrator.agent.tools.subagent.SubagentSystemPromptBuilder.build(
            personaRole = "You are a test sub-agent.",
            agentConfig = null,
            ideContext = null,
            projectName = "SubagentGateProject",
            projectPath = "/subagent/gate",
            completingYourTaskSection = "COMPLETING YOUR TASK\n\nCall task_report when done.",
        )
        assertFalse(
            prompt.contains("delegation with action=\"send\""),
            "Sub-agent prompt must never contain delegation send hints"
        )
        assertFalse(
            prompt.contains("Cross-IDE delegation UX"),
            "Sub-agent prompt must never contain the Cross-IDE delegation UX note"
        )
    }
}
