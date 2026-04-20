// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.tools.subagent

import com.workflow.orchestrator.agent.ide.IdeContext
import com.workflow.orchestrator.agent.prompt.SkillMetadata
import com.workflow.orchestrator.agent.prompt.SystemPrompt

/**
 * Stateless façade that composes a sub-agent-appropriate system prompt by:
 *
 * 1. Delegating to [SystemPrompt.build] with scoped opt-in flags:
 *    - [includeTaskManagement] = false — sub-agents don't own task trees
 *    - [includePlanModeSection] = false — sub-agents are act-only
 *    - [planModeEnabled] = false — same reason
 *    - [includeSubagentDelegationInRules] = false — sub-agents can't spawn further
 *    - All other flags = true (sub-agents inherit all shared sections)
 *
 * 2. Injecting [personaRole] as the agent-role override (section 1).
 *
 * 3. Appending the [completingYourTaskSection] footer so every persona knows
 *    to call `task_report` instead of `attempt_completion`.
 *
 * Task 2 of Track C. Not yet integrated into SubagentRunner — Task 3 does that.
 */
object SubagentSystemPromptBuilder {

    // Section separator literal — matches SystemPrompt.SECTION_SEP (private there).
    private const val SECTION_SEP = "\n\n====\n\n"

    /**
     * Build a sub-agent system prompt.
     *
     * @param personaRole     The agent config's systemPrompt body (persona definition).
     *                        Replaces the default agent-role section.
     * @param agentConfig     The resolved [AgentConfig] for this sub-agent, or null in
     *                        tests / legacy paths. Accepted but not yet consumed.
     * @param ideContext      Optional IDE context for adapting prompt to the running IDE.
     * @param projectName     Project name injected into the User Instructions section.
     * @param projectPath     Absolute project path for Capabilities section.
     * @param osName          Operating system name (defaults to `os.name` property).
     * @param shell           Default shell; defaults to the platform's default shell via
     *                        [defaultShellFallback] so callers can cleanly omit it.
     * @param repoMap         Optional repository map string.
     * @param additionalContext Optional extra context appended to User Instructions.
     * @param availableSkills Optional list of available skill metadata.
     * @param activeSkillContent Optional content of the currently active skill.
     * @param coreMemoryXml   Optional compiled core memory XML block.
     * @param recalledMemoryXml Optional auto-retrieved archival memory XML.
     * @param toolDefinitionsMarkdown Optional Cline-style XML tool schemas.
     * @param deferredToolCatalog Optional categorised deferred tool one-liners.
     * @param availableShells Optional shell list (shows "Available Shells: …" instead of
     *                        "Default Shell: …" in the System Information section).
     * @param completingYourTaskSection The "COMPLETING YOUR TASK" footer injected verbatim
     *                        after the main prompt. Passed in so this builder stays
     *                        stateless and the constant lives only in SubagentRunner.
     */
    fun build(
        personaRole: String,
        agentConfig: AgentConfig?,
        ideContext: IdeContext?,
        projectName: String,
        projectPath: String,
        osName: String = System.getProperty("os.name") ?: "Unknown",
        shell: String = defaultShellFallback(),
        repoMap: String? = null,
        additionalContext: String? = null,
        availableSkills: List<SkillMetadata>? = null,
        activeSkillContent: String? = null,
        coreMemoryXml: String? = null,
        recalledMemoryXml: String? = null,
        toolDefinitionsMarkdown: String? = null,
        deferredToolCatalog: Map<String, List<Pair<String, String>>>? = null,
        availableShells: List<String>? = null,
        completingYourTaskSection: String,
    ): String {
        // TODO(task-4): consume agentConfig.promptSections when YAML schema extension lands
        @Suppress("UNUSED_VARIABLE")
        val _agentConfigUnused = agentConfig

        val base = SystemPrompt.build(
            projectName = projectName,
            projectPath = projectPath,
            osName = osName,
            shell = shell,
            repoMap = repoMap,
            additionalContext = additionalContext,
            availableSkills = availableSkills,
            activeSkillContent = activeSkillContent,
            coreMemoryXml = coreMemoryXml,
            recalledMemoryXml = recalledMemoryXml,
            toolDefinitionsMarkdown = toolDefinitionsMarkdown,
            deferredToolCatalog = deferredToolCatalog,
            availableShells = availableShells,
            ideContext = ideContext,
            // ---- sub-agent scoping ----
            agentRoleOverride = personaRole,
            includeTaskManagement = false,
            includePlanModeSection = false,
            planModeEnabled = false,
            includeSubagentDelegationInRules = false,
            // all other include-flags remain true (inherited)
        )

        return base + SECTION_SEP + completingYourTaskSection
    }

    // Mirrors SystemPrompt.defaultShell() (private there) — duplicated rather than exposing it.
    private fun defaultShellFallback(): String {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        return if (os.contains("win")) {
            System.getenv("COMSPEC") ?: "cmd.exe"
        } else {
            System.getenv("SHELL") ?: "/bin/bash"
        }
    }
}
