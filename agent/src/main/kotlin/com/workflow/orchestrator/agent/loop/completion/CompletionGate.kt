// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.loop.completion

/**
 * One step in the post-`attempt_completion` chain. When armed, the loop injects
 * [nudge] and continues; the gate is cleared either by a satisfying tool call
 * ([isSatisfiedByTool]) or by the agent re-issuing `attempt_completion` (handled
 * universally by [CompletionGateChain], not here).
 *
 * Orchestrator-only — sub-agents complete via `task_report`, never the Completion branch.
 */
interface CompletionGate {
    /** Stable identifier, e.g. "memory", "feedback". */
    val id: String

    /** Message injected as a nudge when this gate is armed. */
    fun nudge(): String

    /** True if invoking [toolName] satisfies this gate. Default: only re-completion satisfies it. */
    fun isSatisfiedByTool(toolName: String): Boolean = false
}

/**
 * Asks the agent — before the task is marked complete — whether anything it learned
 * this session is worth persisting to file-based memory. Satisfied only by re-issuing
 * `attempt_completion` (memory uses generic create_file/edit_file, so there is no
 * dedicated tool to wait for). Gated by `AgentSettings.proactiveMemoryUpdatesEnabled`.
 */
class MemoryReviewGate(private val memoryDirPath: String) : CompletionGate {
    override val id: String = "memory"

    override fun nudge(): String =
        """Before this task is marked complete, review whether anything you learned this session is worth saving to your file-based memory at $memoryDirPath. The frontmatter format and save/delete contracts are in the MEMORY section of your system prompt; the fuller per-type guidance below helps you decide WHAT is worth keeping and HOW to file it. If nothing is worth saving, just call `attempt_completion` again. (This is an automated message — do not respond to it conversationally.)

## Types of memory

<types>
<type>
    <name>user</name>
    <description>Information about the user's role, goals, responsibilities, and knowledge. Great user memories help you tailor future behavior to the user's preferences and perspective — collaborate with a senior engineer differently than a first-time coder. Avoid memories that read as negative judgement or that aren't relevant to the work.</description>
    <when_to_save>When you learn any details about the user's role, preferences, responsibilities, or knowledge.</when_to_save>
    <examples>
    user: I've been writing Go for ten years but this is my first time touching the React side of this repo
    assistant: [saves user memory: deep Go expertise, new to React and this project's frontend — frame frontend explanations in terms of backend analogues]
    </examples>
</type>
<type>
    <name>feedback</name>
    <description>Guidance the user has given about how to approach work — both what to avoid and what to keep doing. Record from failure AND success: if you only save corrections you drift away from approaches the user has already validated and grow overly cautious. Include *why* so you can judge edge cases later.</description>
    <when_to_save>Any time the user corrects your approach ("no, not that", "stop doing X") OR confirms a non-obvious approach worked ("yes exactly", "keep doing that", accepting an unusual choice without pushback). Corrections are easy to notice; confirmations are quieter — watch for them.</when_to_save>
    <body_structure>Lead with the rule, then a **Why:** line (the reason — often a past incident or strong preference) and a **How to apply:** line (when/where it kicks in).</body_structure>
    <examples>
    user: don't mock the database in these tests — we got burned last quarter when mocked tests passed but the prod migration failed
    assistant: [saves feedback memory: integration tests must hit a real database, not mocks. Why: prior incident where mock/prod divergence masked a broken migration]

    user: yeah the single bundled PR was the right call here, splitting this one would've just been churn
    assistant: [saves feedback memory: for refactors in this area, user prefers one bundled PR over many small ones — confirmed after I chose it, a validated judgment call not a correction]
    </examples>
</type>
<type>
    <name>project</name>
    <description>Ongoing work, goals, initiatives, bugs, or incidents within the project that aren't derivable from the code or git history — the broader context and motivation behind the user's work.</description>
    <when_to_save>When you learn who is doing what, why, or by when. These states change quickly. Always convert relative dates to absolute ("Thursday" → "2026-03-05") so the memory stays interpretable later.</when_to_save>
    <body_structure>Lead with the fact or decision, then a **Why:** line (the motivation — a constraint, deadline, or stakeholder ask) and a **How to apply:** line (how it should shape your suggestions).</body_structure>
    <examples>
    user: we're freezing all non-critical merges after Thursday — mobile team is cutting a release branch
    assistant: [saves project memory: merge freeze begins 2026-03-05 for mobile release cut — flag any non-critical PR work scheduled after that date]
    </examples>
</type>
<type>
    <name>reference</name>
    <description>Pointers to where information lives in external systems, so you remember where to look for up-to-date information outside the project directory.</description>
    <when_to_save>When you learn about an external resource and its purpose (e.g. bugs tracked in a specific Linear project, feedback in a specific Slack channel).</when_to_save>
    <examples>
    user: the Grafana board at grafana.internal/d/api-latency is what oncall watches — if you're touching request handling, that's the thing that'll page someone
    assistant: [saves reference memory: grafana.internal/d/api-latency is the oncall latency dashboard — check it when editing request-path code]
    </examples>
</type>
</types>

## What NOT to save
Code patterns, conventions, architecture, file paths, project structure (derivable by reading the project); git history or who-changed-what (`git log`/`git blame` are authoritative); debugging/fix recipes (the fix is in the code); anything already in CLAUDE.md; ephemeral task state. These exclusions apply even when the user asks you to save — if they ask you to save a PR list or activity summary, ask what was *surprising* or *non-obvious* about it; that is the part worth keeping.

## How to organize MEMORY.md
The index uses a fixed structure so it scans quickly and truncates safely:
- Line 1: `# Memory Index`. One `##` section per type — `## Project`, `## User`, `## Feedback`, `## Reference` — created the first time you add an entry of that type.
- Entries within a section are sorted **newest-first**: insert a new entry as the line immediately after its `##` heading, pushing older entries down. (MEMORY.md loads with a 200-line cap; oldest lines drop first, so newest-first keeps fresh memories in-prompt.)
- Do not write duplicate memories: first `read_file MEMORY.md`, scan for an existing entry on the same topic, and update it instead of adding a parallel one. Keep name/description/type fields current; remove memories that turn out wrong.

When you're done saving (or if nothing was worth saving), call `attempt_completion` again to finish."""
}

/**
 * Asks the agent to report tool problems via the `feedback` tool after completing a task.
 * Satisfied by the `feedback` tool OR by re-issuing `attempt_completion` (the universal
 * bypass). Gated by `AgentSettings.agentFeedbackEnabled`. Behaviorally identical to the
 * pre-refactor inline feedback nudge.
 */
class FeedbackGate : CompletionGate {
    override val id: String = "feedback"

    override fun isSatisfiedByTool(toolName: String): Boolean = toolName == "feedback"

    override fun nudge(): String = FEEDBACK_NUDGE

    companion object {
        /** Verbatim copy of the pre-refactor nudge (was inline at AgentLoop.kt:2251-2256). */
        const val FEEDBACK_NUDGE: String =
            "Use the `feedback` tool to share any feedback about the tools you used during this task. " +
            "Report tools that did not work as expected, had confusing or contradictory parameters, " +
            "returned incorrect results, or failed unexpectedly. " +
            "If you have no feedback, call it with an empty string."
    }
}
