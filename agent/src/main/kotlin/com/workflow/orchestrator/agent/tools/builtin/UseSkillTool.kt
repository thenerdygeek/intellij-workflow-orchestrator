package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.prompt.InstructionLoader
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Skill activation tool — faithful port of Cline's use_skill.
 *
 * Cline source:
 *   Tool spec: src/core/prompts/system-prompt/tools/use_skill.ts
 *   Handler:   src/core/task/tools/handlers/UseSkillToolHandler.ts
 *
 * On invocation:
 *   1. Re-discovers all skills (lazy loading, matches Cline's handler)
 *   2. Resolves available skills with override precedence
 *   3. Loads full skill content on demand
 *   4. Returns Cline's response format with instructions + skill directory path
 *
 * The isSkillActivation flag on ToolResult is our addition for compaction
 * survival — Cline does not have this.
 */
class UseSkillTool : AgentTool {

    override val name = "use_skill"

    // Port of Cline's use_skill tool description (src/core/prompts/system-prompt/tools/use_skill.ts)
    override val description = "Load and activate a skill by name. Skills provide specialized instructions " +
        "for specific tasks. Use this tool ONCE when a user's request matches one of the available skill " +
        "descriptions shown in the SKILLS section of your system prompt. After activation, follow the " +
        "skill's instructions directly - do not call use_skill again."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "skill_name" to ParameterProperty(
                type = "string",
                description = "The name of the skill to activate (must match exactly one of the available skill names)"
            )
        ),
        required = listOf("skill_name")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override fun documentation(): ToolDocumentation = toolDoc("use_skill") {
        summary {
            technical(
                "Activates a named skill by name — discovers all skills (bundled classpath + ~/.workflow-orchestrator/skills/ + {project}/.workflow/skills/), resolves override precedence (project > user > bundled), loads SKILL.md (plus optional SKILL.java.md / SKILL.python.md variant based on IdeContext) with YAML frontmatter parsing and substitution expansion, and returns the full body wrapped in an activation marker. Uses ToolResult.skillActivation so ContextManager pins the skill content into the rebuilt system prompt — survives compaction. Orchestrator-only; sub-agents do not load skills via this tool. Auto-removed from the schema when no skills are available; meta-skill `using-skills` is rejected because it is auto-injected."
            )
            plain(
                "Like loading a chapter of a manual that's relevant to what you're about to do. The agent has a shelf of named procedures (debugging, TDD, git workflow, brainstorming, etc.); this tool says 'open the TDD chapter and follow it from now on'. The chapter stays open even after the agent compresses old chat history — so a long debugging session won't lose its instructions halfway through."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.AGENT_CONTROL)
        counterfactual(
            "Without use_skill, the LLM either (a) re-reads the SKILL.md via read_file each time, which costs an iteration and disappears the first time ContextManager truncates, or (b) reinvents the procedure from priors — typically skipping the deliberate steps (write the test first, run the failing test, then implement) that the skill exists to enforce. The user can still invoke skills manually via `/skill-name args` in chat, but auto-discovery from the LLM (matching a user request to an available skill description) goes away. Net cost: ~3-5 wasted iterations on long debugging/TDD tasks plus loss of compaction-survival for the procedure."
        )
        llmMistake("Activates a skill before scanning the SKILLS section of the system prompt — picks a name that doesn't exist (e.g. `use_skill(skill_name=\"debug\")` instead of `systematic-debugging`) and gets a 'skill not found' error with the available list. The error recovers cleanly but burns an iteration.")
        llmMistake("Calls use_skill more than once per task — the description literally says 'Use this tool ONCE'. The second call replaces the first active skill silently (only one is active at a time), which can lose mid-procedure context.")
        llmMistake("Invokes a skill for ad-hoc reasoning that doesn't need a procedure — e.g. activates `brainstorm` to write a one-line bug fix. The skill content is several KB and pollutes the budget for a small task. Prefer no skill or `think` for short reasoning.")
        llmMistake("Forgets that `using-skills` (the meta-skill) is already injected — tries to activate it explicitly and gets the 'already active' nudge. Harmless but a token-cost on a no-op.")
        llmMistake("Mismatches `\$ARGUMENTS` / `\$1`-`\$N` substitutions in custom skills — the tool doesn't currently accept an `arguments` parameter, so the substitutions are not expanded by use_skill (they fire when the user types `/skill-name args` in chat). Custom-skill authors who rely on positional args from LLM-driven activation will silently get unsubstituted templates.")
        params {
            required("skill_name", "string") {
                llmSeesIt("The name of the skill to activate (must match exactly one of the available skill names)")
                humanReadable("The skill's filesystem name (the directory name, lower-kebab-case). The agent sees the available list under SKILLS in its system prompt — this parameter must match one of those names exactly.")
                whenPresent("Skills are re-discovered on every call (lazy, matches Cline). The named skill's SKILL.md is read; if an IdeContext-specific variant exists (`SKILL.java.md` for IntelliJ/IDEA, `SKILL.python.md` for PyCharm), it is appended. Result is wrapped with an activation banner and returned via ToolResult.skillActivation, which ContextManager re-injects into Section 6 of the system prompt on every rebuild.")
                constraint("must match a name from the SKILLS section of the system prompt — case-sensitive exact match")
                constraint("`using-skills` is rejected (already auto-injected as the meta-skill); the tool returns a non-error nudge")
                example("systematic-debugging")
                example("tdd")
                example("git-workflow")
                example("brainstorm")
                example("writing-plans")
            }
        }
        verdict {
            keep(
                "STRONG keep. Skills are the agent's compaction-survivable procedure store — the only mechanism by which a multi-page procedure (TDD red-green-refactor, systematic-debugging escalation, interactive-debugging tactics) stays loaded across a long session even after the conversation truncates. `use_skill` is the single LLM-callable entry point into that store; without it, skills become user-invocation-only (`/skill-name`), which means the LLM can never auto-pick a procedure based on a request like 'help me debug this flaky test' → activate systematic-debugging. Also: skill content is user-extensible (project + user dirs), so this tool is the only way third-party procedures reach the LLM at all.",
                VerdictSeverity.STRONG,
            )
        }
        related("attempt_completion", Relationship.SEE_ALSO, "Both are AGENT_CONTROL — but attempt_completion ends the loop, while use_skill modifies how the LLM proceeds inside it.")
        related("agent", Relationship.COMPOSE_WITH, "Skills with `context: fork` in their YAML frontmatter run inside a subagent — the orchestrator activates the skill, agent tool spawns the worker carrying the activation forward. Used for sandboxed multi-step procedures.")
        related("task_create", Relationship.SEE_ALSO, "Many skills (writing-plans, systematic-debugging, tdd) instruct the LLM to break work into typed tasks via task_create. Skills define the methodology; tasks track the execution.")
        related("read_file", Relationship.ALTERNATIVE, "If the skill's content is needed for inspection (not activation), read_file on the SKILL.md path works. But that read does NOT survive compaction — only use_skill keeps the content pinned across rebuilds.")
        related("ask_followup_question", Relationship.COMPLEMENT, "Some skills (brainstorm, interactive-debugging) explicitly instruct the LLM to ask the user clarifying questions; use_skill activates the procedure that then drives ask_followup_question calls.")
        downside("Activated skill content takes a chunk of the context budget — typical skill is 2-8KB; a verbose project-level custom skill can hit the per-skill cap (~16K chars / 2% of context window). Re-injected on every prompt rebuild, so it re-pays the cost after every compaction.")
        downside("Only one skill is active at a time. Activating a second skill mid-task replaces the first silently — there is no skill stack. A 'TDD then refactor' workflow has to re-activate the second skill manually.")
        downside("Skill substitutions (`\$ARGUMENTS`, `\$1`-`\$N`, `\${CLAUDE_SKILL_DIR}`) are designed for the chat slash-command path — when use_skill is called by the LLM, no `arguments` parameter is currently exposed in the schema, so those substitutions fire with empty values. Custom-skill authors must design skills that work without LLM-supplied positional args.")
        downside("Skill discovery re-reads disk on every call (intentional — matches Cline's lazy loading). For a project with dozens of custom skills, that is dozens of file reads per use_skill invocation. Not a current bottleneck but worth knowing for skill-heavy power users.")
        downside("Sub-agents cannot call use_skill (orchestrator-only via `allowedWorkers`). Skills the sub-agent should follow are wired through the persona's `skills:` YAML field at config-load time, not at runtime.")
        downside("The tool is removed from the schema entirely when no skills are available — bundled skills are always present, so this only triggers in highly stripped-down deployments. Edge-case worth flagging.")
        flowchart("""
            flowchart TD
                A[LLM calls use_skill] --> B{skill_name provided?}
                B -- no --> X1[Error: missing skill_name]
                B -- yes --> C[InstructionLoader.discoverSkills]
                C --> D{Any skills found?}
                D -- no --> X2[Error: no skills available]
                D -- yes --> E{name == 'using-skills'?}
                E -- yes --> X3[Nudge: meta-skill already active]
                E -- no --> F[getSkillContent + IdeContext variant]
                F --> G{Skill found?}
                G -- no --> X4[Error: skill not found + list available]
                G -- yes --> H[Wrap with activation banner]
                H --> I[ToolResult.skillActivation]
                I --> J[ContextManager pins skill]
                J --> K[Re-injected on every prompt rebuild]
        """)
        narrative("use_skill")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val skillName = params["skill_name"]?.jsonPrimitive?.content

        // Port of Cline's handler: missing param increments consecutiveMistakeCount
        if (skillName.isNullOrBlank()) {
            return ToolResult(
                content = "Error: Missing required parameter 'skill_name'. Please provide the name of the skill to activate.",
                summary = "use_skill failed: missing skill_name",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Discover skills on-demand (lazy loading, matches Cline's UseSkillToolHandler.execute)
        val projectPath = project.basePath ?: ""
        val allSkills = InstructionLoader.discoverSkills(projectPath)
        val availableSkills = InstructionLoader.getAvailableSkills(allSkills)

        if (availableSkills.isEmpty()) {
            return ToolResult(
                content = "Error: No skills are available. Skills may be disabled or not configured.",
                summary = "use_skill failed: no skills available",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Block loading the meta-skill via use_skill — it's auto-injected into the system prompt
        if (skillName == InstructionLoader.META_SKILL_NAME) {
            return ToolResult(
                content = "The '${InstructionLoader.META_SKILL_NAME}' skill is already active — it's auto-injected into your system prompt. You don't need to load it.",
                summary = "Meta-skill already active",
                tokenEstimate = 10
            )
        }

        // Get IdeContext for skill variant selection
        val ideContext = try {
            project.getService(com.workflow.orchestrator.agent.AgentService::class.java)?.ideContext
        } catch (_: Exception) { null }
        val skillContent = InstructionLoader.getSkillContent(skillName, availableSkills, ideContext)

        if (skillContent == null) {
            // Port of Cline: list available skill names in error message
            val availableNames = availableSkills.filter { it.name != InstructionLoader.META_SKILL_NAME }.joinToString(", ") { it.name }
            return ToolResult(
                content = "Error: Skill \"$skillName\" not found. Available skills: $availableNames",
                summary = "use_skill failed: skill '$skillName' not found",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Port of Cline's response format (UseSkillToolHandler.ts lines 92-97)
        val skillDirPath = skillContent.path.replace(Regex("SKILL\\.md$"), "")
        val response = """# Skill "${skillContent.name}" is now active

${skillContent.instructions}

---
IMPORTANT: The skill is now loaded. Do NOT call use_skill again for this task. Simply follow the instructions above to complete the user's request. You may access other files in the skill directory at: $skillDirPath"""

        return ToolResult.skillActivation(
            content = response,
            summary = "Activated skill: $skillName",
            tokenEstimate = response.length / 4,
            skillName = skillName,
            skillContent = skillContent.instructions
        )
    }
}
