# Google Antigravity: Task Boundary & Planning System Deep Dive

**Date:** 2026-03-20
**Sources:** Google Developers Codelab (codelabs.developers.google.com), Google Developers Blog, antigravityai.io, awesome-antigravity.com, gizmodo.com, Bing search results, Zhihu discussions

---

## 1. What Is Google Antigravity?

Google Antigravity is an **agentic development platform** (not just an IDE) launched alongside Gemini 3 on November 18, 2025. It splits the interface into two primary surfaces:

- **Editor View**: A VS Code-style AI-powered IDE with tab completions and inline commands (synchronous, hands-on coding).
- **Manager Surface (Agent Manager)**: A "Mission Control" dashboard where developers spawn, orchestrate, and observe multiple agents working asynchronously across different workspaces.

The key architectural distinction: Antigravity treats AI as an **autonomous actor**, not a coding assistant. Each user request spawns a **dedicated agent instance** that can plan, code, run terminal commands, and browse the web independently. Multiple agent instances run in parallel.

---

## 2. Task Boundaries

### What Is a Task Boundary?

The term "task boundary" is **not explicitly used** in Antigravity's public documentation. However, the concept maps to how Antigravity scopes agent work:

- Each user prompt to the Agent Manager creates a **discrete agent instance** with defined scope boundaries (workspace-level, file-level, or feature-level).
- The agent instance operates within that scope boundary for its entire lifecycle.
- Multiple agent instances run in parallel on different tasks, each with its own boundary.

### How Task Scope Is Determined

Task scope is determined by:
1. **The user's prompt** - What the user asks for defines the boundary
2. **The workspace context** - Which project/workspace the agent operates in
3. **The agent's planning phase** - When in Planning Mode, the agent's generated Task List and Implementation Plan implicitly define what's in and out of scope

There is no automatic "task boundary detection" heuristic. The boundary is a function of what the user asks + what the agent plans to do.

---

## 3. The Two Agent Modes: Planning Mode vs Fast Mode

### Planning Mode

**Purpose**: "An Agent can plan before executing tasks. Use for deep research, complex tasks, or collaborative work."

**Behavior**:
- Agent organizes work into **task groups**
- Produces **Artifacts** (Task Lists, Implementation Plans) before writing code
- Thoroughly researches the problem space before acting
- Higher "thinking budget" consumption (more tokens, more deliberation)
- Generates extensive output

**When to use**: Complex multi-step features, deep research tasks, collaborative work where review is needed before execution.

### Fast Mode

**Purpose**: "An Agent will execute tasks directly. Use for simple tasks that can be completed faster, such as renaming variables, kicking off bash commands, or other smaller, localized tasks."

**Behavior**:
- Agent skips the planning phase entirely
- Executes tasks directly with minimal deliberation
- Lower latency and resource usage
- No Task List or Implementation Plan artifacts generated

**When to use**: Variable renaming, running commands, small localized changes.

### Critical Finding: The Mode Is USER-SELECTED, Not Agent-Decided

The documentation makes it clear that **the user selects the mode** via a dropdown in the Agent Manager before initiating a task. This is NOT an automatic classification by the model or a heuristic.

There is no complexity classifier or automatic task boundary detection. The system relies on the **developer's judgment** to choose the appropriate mode for each task.

This is fundamentally different from systems where the agent itself decides whether to plan (like Claude Code's autonomous TodoWrite usage).

---

## 4. The Artifact Pipeline: Implementation Plan -> Task List -> Code -> Walkthrough

When in **Planning Mode**, the agent produces artifacts in a defined sequence:

### Step 1: Implementation Plan
- A high-level document describing:
  - The goal
  - Proposed tech stack
  - "High-level description of the proposed changes"
- This is the **architectural blueprint** - it describes WHAT will change and WHY
- Users can review and comment before the agent proceeds

### Step 2: Task List
- "A concrete list of steps Antigravity will follow to create and verify the app"
- This is the **execution blueprint** - it describes HOW, in sequential steps
- The documentation says users "don't typically need to edit this plan but you can review it"
- Users can add Google Docs-style comments to refine steps

### Step 3: Code Changes
- Generated files with diffs
- Users can "Accept all" or "Reject all" or review individual changes

### Step 4: Walkthrough
- "Summary of changes and how to test them"
- Includes "a screenshot or a verification flow with a browser recording"
- The agent actually launches the app, opens a browser, and visually verifies the result

### Key Detail: Sometimes the Agent Skips User Confirmation

The documentation explicitly states: "sometimes Antigravity goes straight to coding after creating an implementation plan and task list without waiting for you to confirm."

In these cases, users can **still comment on the plans/tasks and submit again**. This will cause the agent to **update the code and walkthrough afterwards**. The plan is retroactively editable even after execution begins.

This behavior depends on the **Review Policy** setting (see below).

---

## 5. Review Policies: The Three-Tier System

Antigravity has three independent review policy dimensions:

### A. Plan/Code Review Policy
Controls when the agent asks for human approval on plans and code:

| Policy | Behavior |
|---|---|
| **Always Proceed** | Agent never asks for review. Goes straight from plan to code to walkthrough. |
| **Agent Decides** | Agent determines when review is necessary (see below for details). |
| **Request Review** | Agent always stops and asks for review at each artifact stage. |

### B. Terminal Execution Policy
Controls terminal command execution:

| Policy | Behavior |
|---|---|
| **Always Proceed** | Auto-executes commands except those on a configurable deny list |
| **Request Review** | Requires user approval before every terminal command |

### C. JavaScript Execution Policy
Controls browser JavaScript execution:

| Policy | Behavior |
|---|---|
| **Always Proceed** | Agent runs JS freely in the browser |
| **Request Review** | Agent stops to ask permission for each JS execution |
| **Disabled** | Agent never runs JS in the browser |

### Pre-Configured Autonomy Levels

During setup, users choose from four presets that combine these policies:

1. **Secure Mode**: Maximum restrictions on external resources and sensitive operations
2. **Review-Driven Development** (recommended): "Agent balances autonomy with frequent user approvals" - the agent makes decisions and comes back for approval
3. **Agent-Driven Development**: Agent operates without requesting reviews
4. **Custom Configuration**: User picks individual policy combinations

---

## 6. How "Agent Decides" Works

This is the most opaque part of the system. The documentation states only that the agent "will decide when to ask for review" but does NOT document:

- The exact decision criteria
- Whether there's a complexity threshold
- Whether it's based on file count, task type, or risk assessment
- Whether it's a heuristic or purely prompt-based

### What We Can Infer

Based on the overall architecture and the codelab's description of "Review-driven development" as "letting the agent make a decision and come back to the user for approval":

1. **It is likely prompt-based, not a separate classifier.** The decision to request review is made by Gemini 3 itself during task execution, not by a separate complexity-scoring model.

2. **The agent probably considers risk factors**: operations that modify many files, delete data, or execute destructive commands likely trigger review requests. The deny-list/allow-list infrastructure suggests a similar risk-assessment pattern.

3. **"Agent Decides" sits between "Always Proceed" and "Request Review"** - it's the middle ground where the LLM uses its judgment about when human oversight is needed.

4. **No public documentation exists** on the exact mechanism. This contrasts with Devin, which also lacks public planning docs, and Claude Code, which uses an explicit TodoWrite tool the model calls when it decides a task needs a plan.

---

## 7. Task Lists: Structure and Nesting

### How Task Lists Are Structured

Task Lists are generated as **Artifacts** - rich markdown documents. Based on the documentation:

- Tasks are organized as **sequential steps**
- The documentation mentions "task groups" as a concept: "organize work in task groups, produce Artifacts"
- Task groups appear to be a way to cluster related steps under high-level objectives

### Can Users Add Subtasks?

Yes. Users provide feedback via **Google Docs-style comments** on the Task List artifact:
- "Select a specific action or task, provide a command the way you would like it to be and then submit that to the agent"
- "Whenever you add a comment to the plans or tasks, make sure you remember to submit the comment. This is what triggers Antigravity to update its plans."

Users can request additional steps, modify existing ones, or restructure the plan through comments.

### How Deep Can Nesting Go?

**Not documented.** The public documentation does not describe nested subtasks or multi-level task hierarchies. Task Lists appear to be **flat sequential lists** with optional grouping into "task groups" rather than deeply nested trees.

The antigravityai.io site mentions the ability to "Group related steps into high-level missions" - suggesting grouping is mission-level (top) -> task group -> individual step, but not deeper nesting.

### Relationship Between Implementation Plan and Task List

- **Implementation Plan** = WHAT and WHY (architectural decisions, tech stack, high-level approach)
- **Task List** = HOW (concrete sequential steps the agent will follow)
- They are **separate artifacts** but generated as a pair
- One task (user prompt) generates exactly one Implementation Plan + one Task List
- No evidence of multiple implementation plans per task

---

## 8. Integration with the Agent Loop

### After Plan Approval

When the user approves the plan (or when the agent proceeds automatically):

1. The agent executes each step in the Task List sequentially
2. It generates code diffs as it goes
3. It can run terminal commands and interact with browsers
4. Progress is visible through the Agent Manager dashboard

### Does Each Plan Step Become a Separate Agent?

**No.** A single agent instance handles the entire task from plan to execution to verification. There is no evidence of spawning sub-agents for individual plan steps. The "multiple agents" capability is for **multiple user-initiated tasks**, not for subtask decomposition within a single task.

However, the **browser subagent** is an exception: "a browser subagent handles the task at hand" when web interaction is needed. This is a specialized sub-model, not a general task decomposition pattern.

### Can the Agent Deviate from the Plan?

**Yes, implicitly.** The documentation shows that:
- Users can comment on plans at any point, triggering plan updates
- The agent incorporates feedback "without stopping its execution flow"
- The undo capability ("Undo changes up to this point") allows reverting if the agent takes a wrong turn

The agent appears to treat the plan as a **living document** that can be modified during execution, not a rigid contract.

### Progress Tracking

Progress is tracked through **artifact production**:
- Task List shows the steps
- Code diffs appear as each step completes
- Screenshots and browser recordings provide visual verification
- The Agent Manager dashboard shows active tasks, plans, and pending approvals

There is no explicit "step 3/7 completed" progress bar in the documentation. Progress is inferred from which artifacts have been produced.

---

## 9. Security Boundaries for Task Execution

Antigravity implements granular security controls:

### Allow Lists (Positive Model)
- Everything forbidden unless expressly permitted
- Paired with "Request Review" policy
- Used for: terminal commands, browser URLs

### Deny Lists (Negative Model)
- Everything allowed unless specifically forbidden
- Paired with "Always Proceed" policy
- Default deny entries: `rm`, `sudo`, `curl`, `wget`

### Browser URL Allowlist
- Restricts which domains the agent can navigate to
- Prevents prompt injection attacks through web browsing

---

## 10. Additional Planning-Related Features

### Skills (Progressive Disclosure)
Skills are specialized knowledge packages that load conditionally:
- "Only loaded into the agent's context when your specific request matches the skill's description"
- Structure: `SKILL.md` + scripts + references + assets
- Scopes: Global (`~/.gemini/antigravity/skills/`) or Workspace (`.agents/skills/`)
- Skills function as **context-efficient expertise injection** - they avoid bloating every prompt with specialized knowledge

### Rules vs Workflows
- **Rules**: System-level guidelines applied globally (code style, documentation standards). Stored in `.agents/rules/` or `~/.gemini/GEMINI.md`.
- **Workflows**: User-triggered saved prompts invoked with `/` prefix. Stored in `.agents/workflows/`.

### Agent Personas
Specialized execution contexts (not separate models):
- Code Reviewer, Debugger, Performance Expert, Security Auditor, Documentation Writer
- These appear to be prompt-level personas, not separate planning strategies

### Mission Templates
Pre-structured workflows for recurring patterns:
- Full-stack features, complex debugging, performance audits
- These provide a planning scaffold without the agent needing to generate one from scratch

---

## 11. Comparison with Other Planning Systems

### vs. Devin (Cognition)

| Dimension | Antigravity | Devin |
|---|---|---|
| **Planning approach** | Artifact pipeline (Implementation Plan -> Task List -> Code -> Walkthrough) | "Ask Devin" planning layer -> Agent session execution |
| **Plan visibility** | Rich artifacts with inline commenting | Session-based, progress visible in chat |
| **Mode selection** | User-selected (Planning/Fast) | No explicit modes; always plans then executes |
| **Multi-agent** | Multiple parallel agent instances on separate tasks | Single agent per session |
| **Planner model** | Same model (Gemini 3) handles planning + execution | "Ask Devin" is a separate interaction mode for scoping/planning before agent sessions |
| **Plan-to-code transition** | Automatic after artifacts generated (or after review) | User manually launches agent session from Ask Devin context |
| **Browser testing** | Built-in browser subagent with video recording | Built-in browser in sandbox |

**Key difference**: Devin's planning is a **two-phase interaction** (Ask Devin for planning, then Agent session for execution). Antigravity's planning is **integrated into a single agent lifecycle** with artifacts as checkpoints.

### vs. Claude Code (Anthropic)

| Dimension | Antigravity | Claude Code |
|---|---|---|
| **Planning mechanism** | Explicit Planning Mode with artifact pipeline | TodoWrite tool - the model decides when to create a todo list |
| **Plan format** | Rich artifacts (Implementation Plan + Task List as separate documents with commenting) | Simple todo list with status tracking (pending/in-progress/completed) |
| **Mode selection** | User selects Planning vs Fast mode | No explicit modes; model autonomously decides whether to plan |
| **Plan approval** | Configurable (Always Proceed / Agent Decides / Request Review) | No explicit approval step; user sees plan inline |
| **Subtasks** | Task groups with sequential steps | Flat todo items (no nesting) |
| **Multi-agent** | Multiple parallel agent instances | Single agent, sequential execution |
| **Feedback on plan** | Google Docs-style comments on artifact | User can verbally redirect in conversation |
| **Plan persistence** | Artifacts persist as reviewable documents | Todo list exists only in conversation context |

**Key difference**: Claude Code's planning is **implicit and model-driven** - the model uses TodoWrite when it judges a task is complex enough. Antigravity's planning is **explicit and user-driven** - the user chooses Planning Mode, and the system always produces artifacts in that mode.

### vs. OpenAI Codex

| Dimension | Antigravity | Codex |
|---|---|---|
| **Planning** | Artifact pipeline with user review | Reads AGENTS.md/PLANS.md for project context, then executes |
| **Environment** | Desktop IDE + browser | Cloud sandbox with shell |
| **Mode selection** | User-selected | No explicit modes |
| **Multi-agent** | Yes, parallel instances | Parallel tasks, each in isolated sandbox |
| **Plan visibility** | Rich artifacts | Minimal - mostly code output |

**Key difference**: Codex relies on **static project context files** (AGENTS.md) for planning guidance rather than generating dynamic implementation plans. Its planning is more implicit, embedded in the model's reasoning rather than surfaced as reviewable artifacts.

---

## 12. Summary: The Exact Planning Decision Mechanism

Here is the precise flow:

```
User sends prompt to Agent Manager
        |
        v
[User has selected Planning Mode or Fast Mode?]
        |                          |
   Planning Mode              Fast Mode
        |                          |
        v                          v
Agent researches &           Agent executes
generates artifacts:         directly, no
  1. Implementation Plan     planning artifacts
  2. Task List               generated
        |
        v
[Review Policy?]
        |              |              |
  Always Proceed   Agent Decides   Request Review
        |              |              |
        v              v              v
  Skip review    Agent uses LLM    Always stop
  Go to code     judgment to        and wait for
                 decide whether     user approval
                 to pause for
                 review (criteria
                 NOT documented)
        |
        v
Agent generates code
(diffs, file changes)
        |
        v
Agent runs app,
opens browser,
verifies result
        |
        v
Agent generates
Walkthrough artifact
(screenshots, video)
        |
        v
User can Accept/Reject
or comment at any stage
(triggers plan update
and re-execution)
```

### The Key Takeaways

1. **Task boundary is user-defined, not system-detected.** The user's prompt + mode selection defines what the agent will work on.

2. **Planning Mode ALWAYS creates a plan.** There is no conditional planning in Planning Mode - it always generates Implementation Plan + Task List.

3. **Fast Mode NEVER creates a plan.** It goes straight to execution.

4. **"Agent Decides" review policy is opaque.** Google has not documented the criteria by which the agent decides whether to request review. It is likely prompt-based (the LLM's own judgment) rather than a separate heuristic or classifier.

5. **The plan is a living document.** Users can comment on plans even after the agent has started coding, and the agent will incorporate changes.

6. **No deep subtask nesting.** Task Lists are flat sequential steps with optional grouping into "task groups." No evidence of multi-level nesting.

7. **Single agent per task, not subtask decomposition.** Each user prompt creates one agent instance that handles the full lifecycle. The only sub-agent is the browser subagent for web interaction.

---

## 13. Raw Source Data

### Source 1: Google Developers Codelab
URL: https://codelabs.developers.google.com/getting-started-google-antigravity

Key quotes:
- Planning Mode: "An Agent can plan before executing tasks. Use for deep research, complex tasks, or collaborative work."
- Fast Mode: "An Agent will execute tasks directly. Use for simple tasks that can be completed faster, such as renaming variables, kicking off a few bash commands, or other smaller, localized tasks."
- Review policies: "Always Proceed: Agent never asks for review" / "Agent Decides: Agent will decide when to ask for review" / "Request Review: Agent always asks for review"
- Artifact sequence: Task Lists -> Implementation Plans -> Code Changes -> Walkthrough
- "Review-driven development (recommended)" balances autonomy with user approvals
- "sometimes Antigravity goes straight to coding after creating an implementation plan and task list without waiting for you to confirm"
- Skills: "specialized package of knowledge that sits dormant until needed"
- Skills scope: Global ~/.gemini/antigravity/skills/ or Workspace .agents/skills/
- Rules stored in .agents/rules/ or ~/.gemini/GEMINI.md
- Browser subagent: "a browser subagent to handle the task at hand"

### Source 2: Google Developers Blog
URL: https://developers.googleblog.com/build-with-google-antigravity-our-new-agentic-development-platform/

Key quotes:
- "Editor View: a state-of-the-art, AI-powered IDE equipped with tab completions and inline commands"
- "Manager Surface: a dedicated interface where you can spawn, orchestrate, and observe multiple agents"
- "autonomously plan, execute, and verify complex tasks across your editor, terminal, and browser"
- Artifacts: "tangible deliverables like task lists, implementation plans, screenshots, and browser recordings"
- "leave feedback directly on the Artifact—similar to commenting on a doc—and the agent will incorporate your input"

### Source 3: antigravityai.io
Key quotes:
- "Artifacts Timeline: Every mission produces clear artifacts—plans, diffs, screenshots, recordings"
- "Task Groups: Group related steps into high-level missions"
- "Feedback Loop: Comment directly on artifacts to correct or refine the agents' work"

### Source 4: awesome-antigravity.com
Key quotes:
- "Plan: What it's going to do"
- "Files affected: Which files will change"
- "Preview: See the changes before applying"
- "Actions: Apply, Modify, or Cancel"
- Agent Personas: Code Reviewer, Debugger, Performance Expert, Security Auditor, Documentation Writer
- Mission Templates for recurring patterns

### Source 5: gizmodo.com
Key quotes:
- "tends to create a to-do list and then proceeds to take action, letting the users be aware of what will occur before clicking a button"
- "plans what to build, creates the files, and even tests the output"
- Agent Manager shows: active tasks, development plans, artifacts, test results and recordings

### Source 6: Devin Documentation (docs.devin.ai)
- Ask Devin: "Plan and scope projects, break down tasks, and generate context-aware prompts"
- Planning -> Agent session transition is manual
- No explicit "planning mode" vs "fast mode" distinction

---

## 14. Open Questions (Not Answered by Public Documentation)

1. **What exact criteria does "Agent Decides" use?** Is it token-count-based, file-count-based, operation-risk-based, or purely LLM judgment?
2. **What is the "thinking budget" mechanism?** The codelab mentions Planning Mode has higher thinking budget consumption, but doesn't explain the allocation mechanism.
3. **Can Task Lists have true nested subtasks?** Documentation shows flat sequential steps with grouping, but doesn't confirm or deny deeper nesting.
4. **How does the agent track which Task List step it's currently executing?** No progress indicator mechanism is documented.
5. **Does Fast Mode use a different model or just different system prompts?** The documentation suggests it's the same model with different instruction, but this isn't confirmed.
6. **Can a single task spawn multiple Implementation Plans?** Documentation shows one-to-one relationship, but edge cases aren't covered.
7. **How does the browser subagent communicate back to the main agent?** The handoff mechanism isn't documented beyond "a browser subagent handles the task."

---

## 15. Implications for Our Plugin's Agent Architecture

Based on this research, key architectural decisions for our Phase 3 agent system:

1. **User-selected mode is simpler but less magical.** Antigravity puts the planning decision on the user. For our IntelliJ plugin, we should consider whether our agent should auto-detect complexity (like Claude Code) or let the user choose (like Antigravity). The auto-detect approach is more seamless but harder to implement correctly.

2. **The artifact pipeline is excellent for transparency.** The Implementation Plan -> Task List -> Code -> Walkthrough sequence gives users clear checkpoints. We should adopt a similar artifact-based approach where each phase produces a reviewable output.

3. **Flat task lists with grouping are sufficient.** Deep nesting adds complexity without proportional value. A two-level hierarchy (task group -> steps) covers most real-world cases.

4. **The feedback-on-artifacts pattern is powerful.** Allowing users to comment on plans and having the agent incorporate feedback without restarting is a much better UX than "regenerate from scratch."

5. **Review policies should be configurable.** The three-tier system (Always Proceed / Agent Decides / Request Review) gives users appropriate control over autonomy level. We should implement similar configurability.

6. **Single agent per task, not subtask decomposition.** Antigravity's model of one agent handling the full lifecycle (plan -> code -> verify) is simpler and more reliable than spawning sub-agents for each step.
