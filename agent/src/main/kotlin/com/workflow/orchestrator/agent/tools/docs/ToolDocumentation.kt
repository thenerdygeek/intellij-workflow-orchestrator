package com.workflow.orchestrator.agent.tools.docs

import kotlinx.serialization.Serializable

/**
 * Rich, source-embedded documentation for an [com.workflow.orchestrator.agent.tools.AgentTool].
 *
 * The plugin's tool testing surface (`ToolTestingPanel`) reads this on demand to render
 * a per-tool documentation tab. The data is hand-authored on each tool via the [toolDoc]
 * DSL — kept separate from the tool's `description` field (which is LLM-facing and
 * must stay terse). Optional long-form prose lives next to the tool as a Markdown
 * resource at `/tool-docs/<name>.md`, loaded lazily by [NarrativeLoader].
 *
 * Documentation is intentionally not serialized to the LLM. It exists only for users
 * (you, deciding which tools to keep) and the tool-docs UI.
 */
@Serializable
data class ToolDocumentation(
    /** Tool's registered name — must match [com.workflow.orchestrator.agent.tools.AgentTool.name]. */
    val toolName: String,
    /** Single high-level summary in two registers: technical and plain-English. */
    val summary: ToolSummary,
    /**
     * The exact `description` string sent to the LLM. The DSL captures it so the
     * UI can show the audit-the-prompt view side-by-side with the human docs.
     */
    val whatLLMSees: String,
    /**
     * Blast-radius classification. Drives a chip in the UI header and powers
     * "show me all NETWORK tools" / "show me all FILE_WRITE tools" filters in
     * the Compare Tools view. Required — every tool has a side effect class.
     */
    val sideEffect: SideEffectKind,
    /**
     * Per-action documentation. Non-null for meta-tools that dispatch on an `action`
     * enum parameter (e.g. `debug_step`, `jira`, `bamboo_builds`). Null for single-action
     * tools where parameters are captured in [singleActionParams] instead.
     */
    val actions: List<ActionDoc>? = null,
    /**
     * Parameters for single-action tools. Mutually exclusive with [actions] in
     * authoring; both may be present at runtime if a multi-action tool also has
     * tool-level params shared across actions.
     */
    val singleActionParams: ParamGroup? = null,
    /** Tool-level keep/drop verdict. Each side is optional — see [Verdict]. */
    val toolVerdict: Verdict = Verdict(),
    /**
     * Short answer to "what would the LLM do instead if this tool were dropped?".
     * The single most valuable field for drop-decisions: forces the author to
     * articulate the alternative cost, instead of leaving it implicit in the verdict
     * prose. 1-3 sentences. Surfaced as a callout next to the tool-level verdict.
     */
    val counterfactual: String? = null,
    /**
     * Patterns the LLM consistently gets wrong with this tool — distinct from
     * [downsides] (gotchas of the tool itself). E.g. "LLM forgets to call
     * `get_state` first" or "LLM includes the line-number prefix when crafting
     * edit_file SEARCH blocks". Strong signal that the *tool description* needs
     * tightening (=fix) vs the tool itself is mis-designed (=drop).
     */
    val commonLLMMistakes: List<String> = emptyList(),
    /**
     * Free-form audit notes — places where this tool could be merged with another,
     * actions that overlap, redundant params, etc. Surfaced in the Compare Tools view.
     */
    val auditNotes: List<AuditNote> = emptyList(),
    /** Tools related by alternative, complement, fallback, or compose-with relationship. */
    val relatedTools: List<RelatedTool> = emptyList(),
    /** Mermaid source for an overall tool flow diagram. Per-action diagrams live on [ActionDoc]. */
    val flowchart: String? = null,
    /** Known downsides, gotchas, and edge cases. Surfaced prominently in the UI. */
    val downsides: List<String> = emptyList(),
    /**
     * Resource path under `agent/src/main/resources/tool-docs/` (no leading slash, no
     * `.md` extension) for additional long-form narrative loaded lazily by [NarrativeLoader].
     * Null if the DSL fields are sufficient.
     */
    val narrativeResource: String? = null,
)

/**
 * Blast-radius classification — what category of side effect the tool can produce.
 *
 * Surfaced as a coloured chip in the UI header. Authors assign one tag per tool;
 * meta-tools whose actions span multiple categories pick the broadest (e.g. a tool
 * that can either read OR write picks `FILE_WRITE`).
 */
@Serializable
enum class SideEffectKind {
    /** Pure read — no filesystem, process, or IDE state mutation. (`read_file`, `find_definition`.) */
    READ_ONLY,
    /**
     * Mutates only the agent's own state: skill activation, plan-mode toggle, task store,
     * completion signal, no-op reasoning. Zero blast radius outside the agent loop.
     * (`use_skill`, `task_create`, `task_update`, `plan_mode_respond`, `enable_plan_mode`,
     * `attempt_completion`, `think`, `new_task`, `tool_search`.)
     */
    AGENT_CONTROL,
    /** Mutates files on disk via VFS / IO / IntelliJ Document API. (`edit_file`, `create_file`.) */
    FILE_WRITE,
    /** Spawns or signals OS processes. (`run_command`, `runtime_exec(action=run_config)`.) */
    PROCESS_SPAWN,
    /** Hits external network — HTTP, integrations, MCP servers. (`jira`, `bitbucket_pr`, `sonar`.) */
    NETWORK,
    /** Mutates IDE/JVM runtime state without writing files: debugger control, breakpoints, run configs. (`debug_step`, `debug_breakpoints`.) */
    IDE_MUTATION,
}

/**
 * Bridge-populated metadata derived from the tool's source — never hand-authored.
 *
 * The `AgentController.buildToolDocsJson` endpoint computes one of these per tool by
 * inspecting the registered `AgentTool` instance, the `ToolRegistry` tier, the
 * `WRITE_TOOLS` constant in `AgentLoop`, the tool's self-declared `requiresApproval`
 * property, and the registration gate in `ToolRegistrationFilter`. Authors don't see this — it's appended to the
 * JSON response alongside [ToolDocumentation].
 *
 * Intentionally separate from [ToolDocumentation] so author-only fields don't
 * accidentally drift from runtime reality.
 */
@Serializable
data class AutoDerivedMetadata(
    /** "Core" / "Deferred" / "Active-deferred". */
    val tier: String,
    /** Plain-English registration condition: "Always" / "Requires Java plugin" / "Requires Bitbucket URL configured". */
    val registrationCondition: String,
    /** Estimated tokens this tool's schema adds to every system prompt (Core) or every active session (Deferred). */
    val schemaTokenCost: Int,
    /** "ALWAYS_APPROVE" / "ALLOW_FOR_SESSION" / "ALWAYS_PER_INVOCATION" / "N/A". */
    val approvalPolicy: String,
    /** True if this tool is in the `WRITE_TOOLS` set in `AgentLoop` and therefore blocked in plan mode. */
    val planModeBlocked: Boolean,
    /** Worker types allowed to call this tool — from `AgentTool.allowedWorkers`. */
    val allowedWorkers: List<String>,
    /** Timeout class — "Default (120s)" / "Long (600s)" / "Unlimited". */
    val timeoutClass: String,
    /** Output cap in characters — "Default (50K)" / "Command (100K)". */
    val outputCap: String,
    /** True if this tool is in `WRITE_TOOLS` (sequential execution, plan-mode block). */
    val isWriteTool: Boolean,
)

/** A single description in both technical and plain-English registers. */
@Serializable
data class ToolSummary(
    /** Engineer-facing — concise, jargon ok. */
    val technical: String,
    /** Plain-English — analogies welcome, no jargon. Targets non-technical users. */
    val plain: String,
)

/** Documentation for one action of a multi-action tool. */
@Serializable
data class ActionDoc(
    /** Action name as it appears in the `action` enum parameter. */
    val name: String,
    /** Two-register description of the action. */
    val description: ToolSummary,
    /**
     * Plain-English statement of when the LLM is likely to pick this action.
     * Helps the user judge whether the action earns its slot in the schema budget.
     */
    val whenLLMUses: String,
    /** Required parameters specific to this action. */
    val requiredParams: List<ParamDoc> = emptyList(),
    /** Optional parameters this action accepts. */
    val optionalParams: List<ParamDoc> = emptyList(),
    /**
     * Parameters the tool exposes (because other actions need them) but THIS action
     * silently ignores or rejects. Documenting these surfaces hidden contracts.
     */
    val rejectedParams: List<RejectedParam> = emptyList(),
    /**
     * Preconditions that must hold for this action to succeed (e.g. "debug session
     * must be paused"). Surfaced as warnings in the UI.
     */
    val preconditions: List<String> = emptyList(),
    /** Plain-English description of what success looks like — what the LLM (and user) gets back. */
    val onSuccess: String,
    /** Known failure modes with their conditions and responses. */
    val onFailure: List<FailureMode> = emptyList(),
    /** Concrete examples — at least one per non-trivial action. */
    val examples: List<ToolExample> = emptyList(),
    /** Per-action mermaid flowchart source. */
    val flowchart: String? = null,
    /** Per-action keep/drop verdict. */
    val verdict: Verdict = Verdict(),
)

/** A grouping of params shared across actions (or for single-action tools). */
@Serializable
data class ParamGroup(
    val required: List<ParamDoc> = emptyList(),
    val optional: List<ParamDoc> = emptyList(),
)

/** Documentation for a single parameter. */
@Serializable
data class ParamDoc(
    val name: String,
    /** JSON schema type — `string`, `integer`, `boolean`, `number`, `array`, `object`. */
    val type: String,
    /** The exact `description` field sent to the LLM via the function-calling schema. */
    val descriptionLLM: String,
    /** Human-friendly explanation, possibly with an analogy. */
    val descriptionHuman: String,
    /** What happens when this param IS provided. */
    val whenPresent: String,
    /** What happens when this param IS NOT provided (defaults, derivation, error). Required for optional params. */
    val whenAbsent: String? = null,
    /** Validation constraints — "must be ≥ 1", "must be a regex", "max length 200", etc. */
    val constraints: List<String> = emptyList(),
    /** Concrete example values. */
    val examples: List<String> = emptyList(),
    /** Allowed enum values, mirrored from the JSON schema if present. */
    val enumValues: List<String> = emptyList(),
)

/**
 * A param that another action uses but this action does not. Surfaces hidden
 * action-specific contracts without forcing the LLM to read 10 action descriptions
 * just to know which params apply.
 */
@Serializable
data class RejectedParam(
    val name: String,
    /** Why this param is irrelevant for this action. */
    val reason: String,
)

/** A documented failure mode — used for "things that go wrong" in the UI. */
@Serializable
data class FailureMode(
    /** The condition that triggers this failure ("file is binary", "session is running"). */
    val condition: String,
    /** What the tool returns and how the LLM is likely to recover. */
    val response: String,
)

/** A concrete invocation example. */
@Serializable
data class ToolExample(
    val title: String,
    /** Param name → string-form value, exactly as the LLM would emit. */
    val params: Map<String, String>,
    /** Plain-English description of the resulting outcome. */
    val outcome: String,
    /** Optional clarifying note. */
    val notes: String? = null,
)

/**
 * Bidirectional verdict — both sides are optional.
 *
 * - If [keep] is set, the author is making a positive case for retaining.
 * - If [drop] is set, the author is making a negative case for removing.
 * - If both are set, the UI presents them side-by-side as a balanced trade-off.
 * - If neither is set, the verdict is "no opinion" — a flag to the user that
 *   this tool/action needs more thought.
 */
@Serializable
data class Verdict(
    val keep: VerdictReason? = null,
    val drop: VerdictReason? = null,
)

/** Reasoning for one side of a verdict. */
@Serializable
data class VerdictReason(
    val reasoning: String,
    val severity: VerdictSeverity = VerdictSeverity.NORMAL,
)

/**
 * Strength of a verdict claim. STRONG verdicts colour the UI urgently — useful
 * when scanning for drop candidates across 79 tools.
 */
@Serializable
enum class VerdictSeverity { STRONG, NORMAL, WEAK }

/**
 * A non-blocking audit note — opportunities for tool/action consolidation,
 * removable params, or other refactors that don't fit neatly into a verdict.
 */
@Serializable
data class AuditNote(
    val kind: AuditKind,
    val text: String,
)

@Serializable
enum class AuditKind {
    /** Two actions could plausibly merge. */
    MERGE_OPPORTUNITY,
    /** A param appears removable. */
    REMOVABLE_PARAM,
    /** Generic observation worth surfacing. */
    OBSERVATION,
    /** Explicit deprecation warning. */
    DEPRECATION,
}

/** Pointer to a related tool with semantic relationship. */
@Serializable
data class RelatedTool(
    val name: String,
    val relationship: Relationship,
    /** Plain-English note: "use instead when X", "compose with this for Y". */
    val note: String,
)

@Serializable
enum class Relationship {
    /** Use instead — does the same job differently. */
    ALTERNATIVE,
    /** Use together — completes the workflow. */
    COMPLEMENT,
    /** Use as a fallback when this one can't run (env constraint, etc.). */
    FALLBACK,
    /** Composes with — pipes output to / from. */
    COMPOSE_WITH,
    /** Conceptually related, no specific workflow link. */
    SEE_ALSO,
}

/**
 * Wire-format payload sent to the JCEF tool-docs editor. Flattens hand-authored
 * [ToolDocumentation] with runtime [AutoDerivedMetadata] and the optionally-loaded
 * narrative markdown into a single JSON object whose shape matches the static
 * preview at `/tmp/tool-docs-preview/data.js`.
 *
 * The duplication between top-level `tier` and `metadata.tier` is intentional —
 * the React header reads the top-level field for the badge, while `metadata.tier`
 * lives inside the capability strip. Same value, two binding sites.
 */
@Serializable
data class ToolDocPayload(
    val toolName: String,
    val tier: String,
    val sideEffect: SideEffectKind,
    val counterfactual: String? = null,
    val commonLLMMistakes: List<String> = emptyList(),
    val metadata: AutoDerivedMetadata,
    val summary: ToolSummary,
    val whatLLMSees: String,
    val actions: List<ActionDoc>? = null,
    val singleActionParams: ParamGroup? = null,
    val toolVerdict: Verdict = Verdict(),
    val auditNotes: List<AuditNote> = emptyList(),
    val relatedTools: List<RelatedTool> = emptyList(),
    val flowchart: String? = null,
    val downsides: List<String> = emptyList(),
    val narrative: String? = null,
)
