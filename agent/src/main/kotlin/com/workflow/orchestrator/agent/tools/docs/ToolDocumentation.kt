package com.workflow.orchestrator.agent.tools.docs

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

/** A single description in both technical and plain-English registers. */
data class ToolSummary(
    /** Engineer-facing — concise, jargon ok. */
    val technical: String,
    /** Plain-English — analogies welcome, no jargon. Targets non-technical users. */
    val plain: String,
)

/** Documentation for one action of a multi-action tool. */
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
data class ParamGroup(
    val required: List<ParamDoc> = emptyList(),
    val optional: List<ParamDoc> = emptyList(),
)

/** Documentation for a single parameter. */
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
data class RejectedParam(
    val name: String,
    /** Why this param is irrelevant for this action. */
    val reason: String,
)

/** A documented failure mode — used for "things that go wrong" in the UI. */
data class FailureMode(
    /** The condition that triggers this failure ("file is binary", "session is running"). */
    val condition: String,
    /** What the tool returns and how the LLM is likely to recover. */
    val response: String,
)

/** A concrete invocation example. */
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
data class Verdict(
    val keep: VerdictReason? = null,
    val drop: VerdictReason? = null,
)

/** Reasoning for one side of a verdict. */
data class VerdictReason(
    val reasoning: String,
    val severity: VerdictSeverity = VerdictSeverity.NORMAL,
)

/**
 * Strength of a verdict claim. STRONG verdicts colour the UI urgently — useful
 * when scanning for drop candidates across 79 tools.
 */
enum class VerdictSeverity { STRONG, NORMAL, WEAK }

/**
 * A non-blocking audit note — opportunities for tool/action consolidation,
 * removable params, or other refactors that don't fit neatly into a verdict.
 */
data class AuditNote(
    val kind: AuditKind,
    val text: String,
)

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
data class RelatedTool(
    val name: String,
    val relationship: Relationship,
    /** Plain-English note: "use instead when X", "compose with this for Y". */
    val note: String,
)

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
