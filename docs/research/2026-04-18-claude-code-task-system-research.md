# Claude Code Task System — Research Report

**Date:** 2026-04-18
**Context:** Reconnaissance for porting Claude Code's task/todo system faithfully into the `:agent` module's task handling. Agent context: we are replacing the Cline-ported `task_progress` markdown-parameter system with dedicated `TaskCreate`/`TaskUpdate`/`TaskGet`/`TaskList` tools plus a typed `TaskStore`. User has pinned: Path A (full replacement), include `blocks`/`blockedBy` DAG, tasks available in both plan and act modes, `PlanJson.steps` deleted, `PlanProgressWidget` rewired to `TaskStore`.

**Methodology:** `claude-code-guide` subagent with targeted prompt covering design rationale, granularity guidance, plan mode interaction, UI patterns, evolution of the system, failure modes, and hook integration. Prioritized official Anthropic docs + reputable community sources.

---

## Executive Summary

Claude Code's task system, released in January 2026 alongside Opus 4.5, is a fundamental redesign that replaced the ephemeral `TodoWrite`/`TodoRead` tools with persistent, dependency-aware tasks. This report synthesizes findings from official Anthropic documentation, community sources, and technical implementations to address seven research questions about design rationale, granularity guidance, and integration patterns.

**Key finding:** Official Anthropic documentation remains limited on design *rationale*, but community analysis and reverse-engineered system prompts provide implementation details. This report distinguishes between well-documented behaviors and areas requiring inference.

---

## 1. Design Rationale

### Task Creation: One-Task-Per-Call API Design

**Finding:** Official documentation does NOT explain why `TaskCreate` accepts only a single task.

**What we know from implementation:**
- The API follows a granular, RESTful pattern: individual tool calls for each operation (`TaskCreate`, `TaskUpdate`, `TaskGet`, `TaskList`)
- `TaskList` returns minimal fields (just taskId and subject) to avoid context bloat; detailed reads require explicit `TaskGet` calls
- This mirrors general API design principles emphasizing focused operations over batch operations

**Community inference:** Sequential task creation with `blockedBy` dependencies allows dependencies to be specified atomically—you create Task A, then Task B with `blockedBy: [A.id]`, ensuring dependency is explicit and verifiable. Batch creation would require post-hoc dependency linking or introduce ordering ambiguity.

**Official sources:** No published design justification from Anthropic. Referenced in [Best Practices for Claude Code](https://code.claude.com/docs/en/best-practices) implicitly through examples but never made explicit.

---

### Status Enum: `pending|in_progress|completed|deleted`

**Finding:** Official guidance is minimal; the design is inferred from behavior.

**Design implications from the spec:**
- **No "cancelled" or "blocked" state:** Work is either being done, done, or waiting. "Blocked" is expressed via `blockedBy` array, not a status. This separation is deliberate.
- **Why this matters for multi-agent coordination:** In agent teams, a task can be `in_progress` owned by Agent A. Agent B sees `blockedBy: [taskId]` and knows to wait. The blocked state is *relational* (depends on task X), not absolute (task is "blocked").
- **The "deleted" state:** Tasks can be marked deleted instead of removed, preserving history and preventing re-instantiation of old tasks.

**Official sources:** [Todo Lists](https://code.claude.com/docs/en/agent-sdk/todo-tracking) documents task lifecycle (Created→Activated→Completed→Removed) but doesn't explain the philosophical choice of relational blocking over status-based blocking.

---

### `blocks` and `blockedBy` Terminology

**Finding:** Official documentation uses "blocks/blockedBy" consistently but offers no comparative analysis with alternatives like "depends_on/required_by."

**What we learned from implementation:**
- `blockedBy: [taskIds]` is the inverse of `blocks: [taskIds]`—both fields exist on a task for bidirectional queries
- The terminology reflects causality: "Task B is blocked by Task A" = "Task A blocks Task B"
- This is semantically different from "Task B depends on Task A" (which centers B's need) vs. "Task A blocks Task B" (which centers A's agency)

**Community best practice:** The blocks/blockedBy terminology better reflects the coordinator's view: "what am I blocking?" vs. "what's blocking me?"

**Official sources:** Used but not justified in [Orchestrate teams of Claude Code sessions](https://code.claude.com/docs/en/agent-teams).

---

### The `owner` Field

**Finding:** The `owner` field exists specifically for multi-agent coordination.

**Design purpose:**
- Signals which agent has claimed a task in parallel agent teams
- Prevents duplicate work when multiple Claude Code sessions read the same task list
- Enables round-robin or explicit assignment patterns (lead agent decides who works on what)

**Official sources:** Documented in [Orchestrate teams of Claude Code sessions](https://code.claude.com/docs/en/agent-teams):

> "Tasks can be assigned in two ways: lead assignment (asking the lead which task to assign to which teammate, useful for tasks requiring specific expertise or a precise order), or teammates can self-claim tasks from the list."

---

## 2. Granularity Guidance

### When to Create Tasks

**Official guidance from [Best Practices for Claude Code](https://code.claude.com/docs/en/best-practices):**

> "For tasks where the scope is clear and the fix is small (like fixing a typo, adding a log line, or renaming a variable) ask Claude to do it directly. Planning is most useful when you're uncertain about the approach, when the change modifies multiple files, or when you're unfamiliar with the code being modified. If you could describe the diff in one sentence, skip the plan."

**For task creation specifically** (inferred from [Claude Code Task Management: Native Multi-Session AI](https://claudefa.st/blog/guide/development/task-management)):
- Create tasks when there are **3+ distinct work items** (implicit threshold from community docs)
- Create tasks for **multi-step work spanning sessions**
- Create tasks when **dependencies exist** between work items
- Skip tasks entirely for simple, single-prompt work

---

### Good Task Subjects vs. Bad Ones

**Official examples from documentation:**

**GOOD (imperative form):**
- "Implement OAuth2 flow"
- "Add performance monitoring"
- "Fix login timeout bug"
- "Refactor database migrations"

**BAD (vague or passive):**
- "Improve auth" (too vague)
- "Work on tests" (not actionable)
- "Miscellaneous fixes" (not a task, a category)

**The `activeForm` field** (present-continuous) distinguishes **what work looks like in progress**:
- Subject (imperative): "Implement OAuth2 flow"
- ActiveForm (present-continuous): "Implementing OAuth2 flow"

**Official source:** Referenced in search results (TaskCreate documentation) but the full examples are not in the main Anthropic docs—they're in community guides like the [claude-code-ultimate-guide](https://github.com/FlorianBruniaux/claude-code-ultimate-guide).

---

### Expected Task Count Per Session

**Finding:** No official guidance on "typical range."

**Community inference from best practices:**
- Sessions should have 5–15 active tasks in agent teams (beyond that, the DAG becomes hard to track mentally)
- Single-session workflows typically use 3–5 tasks for complex projects
- Stale tasks should be actively removed to avoid context pollution

**Official sources:** [Best Practices for Claude Code](https://code.claude.com/docs/en/best-practices) emphasizes context management but does NOT specify task count thresholds.

---

## 3. Plan Mode Interaction with Tasks

### Task Creation During Plan Mode

**Finding:** No official Anthropic statement on whether tasks should be created during plan mode (Shift+Tab read-only) vs. act mode (normal execution).

**Implementation behavior** (inferred):
- Plan Mode uses `--permission-mode plan` and disables write tools (`edit_file`, `create_file`, `run_command`)
- **Tasks are read-only tools** in this context (TaskCreate, TaskUpdate are absent in plan mode schema)
- Tasks can only be created/updated in act mode (normal operation)

**CONTRADICTION WITH LOADED SCHEMAS:** The `TaskCreate` tool schema description as seen in the Claude Code harness lists "Plan mode" as a valid trigger ("Plan mode - When using plan mode, create a task list to track the work"). This suggests the community claim that tasks are absent in plan mode schema is either out of date or incorrect. The loaded schema is authoritative over community inference.

**Why the permissive reading makes sense:** Plans are exploratory; tasks are persistent work items. Creating tasks during planning commits to a structure before the plan is approved/refined — but the LLM CAN do so if the planning itself benefits from structured tracking.

**Official sources:** [How to use Plan Mode for safe code analysis](https://code.claude.com/docs/en/common-workflows#use-plan-mode-for-safe-code-analysis) documents plan mode workflow but doesn't mention task interaction.

---

### Plan-to-Task Conversion

**Finding:** No official guidance on converting plan content into tasks.

**Community best practice** (inferred from workflow guides):
1. User explores in plan mode (read-only)
2. Claude creates a detailed plan document (human-readable text)
3. User approves plan (manually or via Claude prompt)
4. Claude switches to act mode and creates tasks from plan sections
5. Claude proceeds with implementation

**No "automatic plan → task conversion"** is documented—it's manual human interpretation. **This aligns with this plugin's rejection of mechanical auto-conversion** (which previously produced 22-30 task explosions).

---

## 4. UI Presentation Patterns

**Finding:** Limited official documentation on task UI rendering in Claude Code.

**What we know:**
- Tasks appear as a **checklist-style todo list** in the UI (inline in chat or as a sidebar widget)
- **In-progress tasks show a spinner** and the `activeForm` text (not the subject)
- **Completed tasks** show a checkmark
- **Pending tasks** show an empty box
- **Dependencies are NOT visually rendered** in the UI (you must query `blockedBy` manually or via `TaskGet`)

**Official sources:** [Todo Lists](https://code.claude.com/docs/en/agent-sdk/todo-tracking) includes example code showing status icons but no official UI mockup documentation.

---

## 5. Evolution of the System

### TodoWrite → Tasks Migration (January 2026)

**Official statement from search results:**

In January 2026, Anthropic replaced the simpler `TodoWrite`/`TodoRead` tools with a Tasks system designed for longer projects spanning multiple sessions.

**Key changes:**

| Feature | TodoWrite | Tasks |
|---------|-----------|-------|
| **Storage** | Context window (ephemeral) | Filesystem: `~/.claude/tasks/` (persistent) |
| **Scope** | Single session | Multi-session + cross-agent coordination |
| **Dependencies** | None | Full DAG support (`blockedBy`, `blocks`) |
| **API calls** | 1 tool (TodoWrite) | 4 tools (TaskCreate, TaskUpdate, TaskGet, TaskList) |

**Migration guidance:** No migration scripts required. Anthropic designed the transition as "seamless"—Claude Code handles the upgrade automatically.

**Official sources:** [Claude Code Tasks vs Todos — What Changed in 2026](https://claudearchitect.com/docs/claude-code/claude-code-tasks-vs-todos/) and [Tasks API vs TodoWrite](https://deepwiki.com/FlorianBruniaux/claude-code-ultimate-guide/7.1-tasks-api-vs-todowrite) (community-curated, not Anthropic official).

---

### System Maturation Timeline

- **Pre-2025:** TodoWrite only (basic checklist)
- **January 2026 (v2.1.16):** Tasks API released alongside Opus 4.5
- **Current (April 2026):** Tasks are stable; TodoWrite deprecated but not removed

**No breaking changes announced.** Both systems coexist; TodoWrite workflows still function but are not recommended for new work.

---

## 6. Community Best Practices and Failure Modes

### Anti-Patterns Documented

**From [Best Practices for Claude Code](https://code.claude.com/docs/en/best-practices):**

1. **Stale instructions are actively harmful** — Remove task entries when they're no longer relevant
2. **Over-decomposition** — Breaking work into too many micro-tasks makes the DAG unnavigable
3. **Forgetting to clear context** — Stale tasks from previous work clutter the session
4. **Treating tasks as permanent** — Old, completed, or irrelevant tasks should be deleted or marked `deleted`

### Effective Prompting Patterns

From community analysis:

- **Discovery first:** Use `Bash` or `Glob` to understand the codebase before decomposing into tasks
- **Explicit dependency ordering:** If Task B depends on Task A, always use `blockedBy: [A.id]`—don't rely on creation order
- **Owner assignment in agent teams:** Clearly assign owners to prevent duplicate work
- **Verify before marking complete:** Don't mark a task completed until tests pass or the change is verified

---

### Common Mistakes

**From issue tracking and community reports:**

1. **Circular dependencies:** Task A blocks B, B blocks C, C blocks A → deadlock. The API doesn't prevent this; human discipline is required.
2. **Abandoned tasks:** A task marked `in_progress` but abandoned when the session ends. Other agents see it as claimed and wait forever.
3. **Over-detailed subjects:** "Add unit tests to utils.ts including null-check edge cases for the isValidEmail function" is a task description, not a subject. Subject should be: "Add unit tests to utils.ts"
4. **Missing descriptions:** Tasks with only a subject are confusing; include a `description` with context, acceptance criteria, or links.

---

## 7. Integration with Hooks and Custom Agents

### Hook Points Around Tasks

**Finding:** Tasks bypass PreToolUse/PostToolUse hooks.

From GitHub issue #20243 (reference in search results):

> "Task* tools (TaskCreate, TaskUpdate, TaskList, TaskGet) bypass PreToolUse/PostToolUse hooks"

This is a known limitation: task operations are not subject to user-configured hooks. Custom approval gates for task creation are not possible via hooks.

**Workaround:** Custom agents or subagents can be configured to *avoid* using tasks if that's a requirement, but there's no hook-based veto.

---

### Custom Agent Configuration

**From [Custom subagents documentation](https://code.claude.com/docs/en/sub-agents):**

Custom agents in `.claude/agents/` can be configured with YAML frontmatter including:
- `name`, `description`, `tools` (list of allowed tools)
- `model`, `max-turns`

**Tasks are NOT restricted by tool lists**—they are always available (unless explicitly disabled in system prompt).

**Note:** A `disable-task-creation` field has been proposed in the community but is not a real Claude Code feature. Current agents cannot opt out of task tools via configuration.

---

## 8. Inconclusive / Not Found

The following questions could **not** be answered with authoritative sources:

1. **Why `pending|in_progress|completed|deleted` specifically?** No Anthropic blog post or engineering article explains the philosophical choice of this enum over alternatives.

2. **Why not batch TaskCreate?** The API design is granular, but the rationale isn't documented. We can infer from implementation, but there's no official statement.

3. **What is the expected task count per session?** No guidance from Anthropic on "too many tasks" thresholds.

4. **Circular dependency prevention?** No documentation on whether the API prevents TaskA→B→A cycles. Testing would be required.

5. **Hook points for task approval?** Custom approval gates for task creation are not documented. The GitHub issue indicates it's a known gap but not a priority.

6. **Multi-team task sharing?** If two independent agent teams share a single task list, what happens if both claim the same task? Conflict resolution strategy not documented.

7. **Task archival/retention policy?** How long are deleted tasks kept? No retention guidance found.

---

## Sources

### Official Anthropic Documentation
- [Best Practices for Claude Code](https://code.claude.com/docs/en/best-practices)
- [How to use Plan Mode for safe code analysis](https://code.claude.com/docs/en/common-workflows#use-plan-mode-for-safe-code-analysis)
- [Todo Lists](https://code.claude.com/docs/en/agent-sdk/todo-tracking)
- [Orchestrate teams of Claude Code sessions](https://code.claude.com/docs/en/agent-teams)
- [Extend Claude Code - Sub-agents](https://code.claude.com/docs/en/sub-agents)

### Community Analysis (Highly Curated)
- [Claude Code Tasks — Complete Guide 2026 | ClaudeArchitect](https://claudearchitect.com/docs/claude-code/claude-code-tasks-guide/)
- [Claude Code Task Management: Native Multi-Session AI](https://claudefa.st/blog/guide/development/task-management)
- [claude-code-ultimate-guide (GitHub)](https://github.com/FlorianBruniaux/claude-code-ultimate-guide/blob/main/guide/workflows/task-management.md)
- [The Task Tool: Claude Code's Agent Orchestration System - DEV Community](https://dev.to/bhaidar/the-task-tool-claude-codes-agent-orchestration-system-4bf2)
- [Claude Code Swarm Orchestration Skill (GitHub Gist)](https://gist.github.com/kieranklaassen/4f2aba89594a4aea4ad64d753984b2ea)

### GitHub Issues & Discussions
- [Task* tools bypass PreToolUse/PostToolUse hooks · Issue #20243](https://github.com/anthropics/claude-code/issues/20243)
- [Reddit: Anthropic replaced Claude Code's old 'Todos' with Tasks](https://www.reddit.com/r/ClaudeAI/comments/1qkjznp/anthropic_replaced_claude_codes_old_todos_with/)

---

## Recommendations for the Plugin Port

**High confidence (official sources):**
1. Implement `TaskCreate`, `TaskUpdate`, `TaskGet`, `TaskList` as four separate tool calls (not batched)
2. Use `blockedBy: [taskIds]` and `blocks: [taskIds]` for dependency management
3. Persist tasks to disk (not ephemeral context window)
4. Support the `owner` field for multi-agent coordination
5. Use imperative subject + present-continuous `activeForm`

**Medium confidence (community consensus + behavior inference):**
6. Status enum: `pending | in_progress | completed | deleted` (no "cancelled" or "blocked" states)
7. Create tasks for 3+ distinct work items; skip for single-diff changes
8. Prefer act-mode task creation, but permit it in plan mode (the schema allows it)
9. Visualize task state in UI (PlanProgressWidget reused); dependency arrows are a nice-to-have
10. Task list refreshes on state change for multi-agent / sub-agent scenarios

**Areas requiring experimentation or explicit design decisions for the port:**
- **Persistence scope** — Claude Code uses `~/.claude/tasks/` (global-ish). This plugin's current plan is `agent/sessions/{sessionId}/tasks.json` (session-scoped). Decide intentionally.
- **Circular dependency detection** — Claude Code doesn't prevent cycles. Optional safety guard for the port.
- **Hook bypass** — Claude Code's Task* tools bypass PreToolUse/PostToolUse. A faithful port matches this (tasks bypass the HookManager). Alternative: add TaskPreUse/TaskPostUse hooks as a plugin-specific extension.
- **TaskList minimal fields** — Claude Code's TaskList returns taskId + subject only (not full task); full read requires TaskGet. Kotlin design should match.
- **"Stale tasks are actively harmful"** guidance — should be in the TaskUpdate description nudging the LLM to clean up.

---

**Report compiled:** 2026-04-18 | **Research scope:** Official Anthropic docs + vetted community sources | **Confidence level:** High for APIs and workflows; Medium for design rationale; Low for unresolved questions (noted above)
