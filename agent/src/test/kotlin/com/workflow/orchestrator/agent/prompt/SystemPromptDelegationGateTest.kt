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
        skills: List<SkillMetadata>? = null,
        delegationTargets: List<SystemPrompt.DelegationTarget> = emptyList(),
    ) = SystemPrompt.build(
        projectName = "GateTestProject",
        projectPath = "/gate/test/project",
        osName = "Linux",
        shell = "/bin/bash",
        availableSkills = skills,
        delegationOutboundEnabled = delegationOutboundEnabled,
        delegationTargets = delegationTargets,
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

    // ---- Plan 5.1: delegation target snapshot rendering ----

    @Test
    fun `delegation targets section absent when gate is off even with non-empty list`() {
        // Defensive: AgentService always returns empty list when gate off, but the
        // prompt builder itself must not render the section when the gate is off.
        val targets = listOf(
            SystemPrompt.DelegationTarget("frontend-app", "running"),
            SystemPrompt.DelegationTarget("mobile-app", "closed"),
        )
        val prompt = buildWith(delegationOutboundEnabled = false, delegationTargets = targets)
        assertFalse(
            prompt.contains("Available cross-IDE delegation targets"),
            "Targets section must not render when gate is off, even with a non-empty list"
        )
        assertFalse(prompt.contains("frontend-app"), "Repo names must not leak when gate is off")
    }

    @Test
    fun `delegation targets section absent when gate is on but list is empty`() {
        val prompt = buildWith(delegationOutboundEnabled = true, delegationTargets = emptyList())
        assertFalse(
            prompt.contains("Available cross-IDE delegation targets"),
            "Targets section must be omitted when the list is empty (no IDE found)"
        )
        // But the gate-on hints / UX note must still appear.
        assertTrue(prompt.contains("Cross-IDE delegation UX"))
    }

    @Test
    fun `delegation targets section lists every repo with its status when gate on and list non-empty`() {
        val targets = listOf(
            SystemPrompt.DelegationTarget("frontend-app", "running"),
            SystemPrompt.DelegationTarget("mobile-app", "closed"),
            SystemPrompt.DelegationTarget("infra-terraform", "discovered"),
        )
        val prompt = buildWith(delegationOutboundEnabled = true, delegationTargets = targets)
        assertTrue(
            prompt.contains("Available cross-IDE delegation targets"),
            "Targets section header must be present"
        )
        assertTrue(prompt.contains("- frontend-app (running)"))
        assertTrue(prompt.contains("- mobile-app (closed)"))
        assertTrue(prompt.contains("- infra-terraform (discovered)"))
        assertTrue(
            prompt.contains("Status meanings:"),
            "Status legend must accompany the list so the LLM interprets the values correctly"
        )
    }

    @Test
    fun `delegation targets list is capped at MAX_DELEGATION_TARGETS_IN_PROMPT with overflow line`() {
        // Heavy users may have 100+ recents — keep the prompt bounded.
        val targets = (1..60).map { SystemPrompt.DelegationTarget("repo-$it", "running") }
        val prompt = buildWith(delegationOutboundEnabled = true, delegationTargets = targets)
        assertTrue(prompt.contains("- repo-1 (running)"))
        assertTrue(prompt.contains("- repo-50 (running)"), "First 50 entries must render")
        assertFalse(prompt.contains("- repo-51 (running)"), "Beyond cap must NOT render verbatim")
        assertTrue(
            prompt.contains("and 10 more"),
            "Overflow line must point the LLM at list_targets for the remainder"
        )
    }

    @Test
    fun `sub-agent builder never renders targets even when caller would pass them`() {
        // Mirrors the existing sub-agent invariant — sub-agents always get gate off,
        // so any targets caller might forward are dropped at the gate.
        val prompt = com.workflow.orchestrator.agent.tools.subagent.SubagentSystemPromptBuilder.build(
            personaRole = "You are a test sub-agent.",
            agentConfig = null,
            ideContext = null,
            projectName = "SubagentGateProject",
            projectPath = "/subagent/gate",
            completingYourTaskSection = "COMPLETING YOUR TASK\n\nCall task_report when done.",
        )
        assertFalse(
            prompt.contains("Available cross-IDE delegation targets"),
            "Sub-agent prompts must never contain the targets section"
        )
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
