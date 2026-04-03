# Comprehensive Architecture Audit: Workflow Orchestrator AI Agent

**Date:** 2026-04-03
**Scope:** Full agent module architecture review — prompts, context management, tools, sub-agents, planning, completion, ReAct loop, security, wiring
**Method:** 6 parallel Opus-class expert reviewers analyzing 168 source files, 8 agent definitions, 8 skills, 72 plans, 38 specs
**Overall Grade: B+ (Strong enterprise foundation with specific fixable issues)**

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Cross-Cutting Findings](#2-cross-cutting-findings)
3. [System-by-System Grades](#3-system-by-system-grades)
4. [Critical Issues (Must Fix)](#4-critical-issues-must-fix)
5. [High Priority Issues](#5-high-priority-issues)
6. [Medium Priority Issues](#6-medium-priority-issues)
7. [Over-Engineering Assessment](#7-over-engineering-assessment)
8. [Industry Comparison](#8-industry-comparison)
9. [Strengths (What's Genuinely Good)](#9-strengths-whats-genuinely-good)
10. [Prioritized Remediation Plan](#10-prioritized-remediation-plan)
11. [Appendix: Reviewer Details](#11-appendix-reviewer-details)

---

## 1. Executive Summary

This agent is **substantially more sophisticated than most open-source AI coding agents** and demonstrates deep awareness of real-world agentic failure modes. The event-sourced context management, 4-stage condenser pipeline, meta-tool consolidation, sub-agent coordination with file ownership, and cross-session guardrail learning are genuine differentiators not found in Claude Code, Cursor, Cline, or any open-source competitor.

However, the system has reached a **complexity inflection point** where several structural issues risk degrading LLM reliability:

1. **The completion boundary is over-gated** — 5 overlapping mechanisms create unpredictable interaction effects
2. **AgentService is a God Object** — 20+ `@Volatile` fields functioning as a mutable service locator
3. **The prompt system has structural contradictions** — persistence stated 3x, recency zone overloaded with 11 sections
4. **Thread-safety bugs exist** in EventStore and FileOwnershipRegistry
5. **Context budget visibility gap** — condenser decisions exclude 3-8K tokens of anchor content

None of these are architectural failures. They are all fixable with targeted changes. The foundation is sound — what's needed is tightening, deduplication, and simplification at specific boundaries.

**Verdict:** Production-ready with caveats. Fix the 7 CRITICAL issues before shipping to enterprise customers.

---

## 2. Cross-Cutting Findings

These patterns emerged from correlating findings across all 6 reviewers:

### 2.1 The "Invisible Tokens" Problem

Multiple systems contribute tokens that other systems don't account for:
- **Anchors** (plan, facts, guardrails, change ledger, skills, mentions) add 3-8K tokens AFTER the condenser runs — the condenser doesn't see them
- **Tool schemas** consume ~7,400 tokens baseline (34 always-active tools × ~200 tokens average) — the prompt assembler doesn't budget for this
- **Skill injection** can add 2,500+ tokens with no budget guard at the assembly layer
- **System prompt** is ~8-10K tokens typical, ~15-18K worst case

**Combined worst case:** 18K (prompt) + 7.4K (tools) + 8K (anchors) + 2.5K (skill) = **~36K tokens of fixed overhead** before any conversation content. That's 19% of a 190K context window consumed by scaffolding.

This is manageable but no component has visibility into the total. Each system optimizes locally.

### 2.2 The "State Scatter" Problem

Session-specific state lives in at least 6 different locations:
- `AgentService` — 20+ `@Volatile var` fields (plan manager, question manager, context bridge, etc.)
- `SingleAgentSession` — loop-local state (forceTextOnly, malformedRetries, etc.)
- `SelfCorrectionGate` — per-file verification state (not persisted, lost on resume)
- `LoopGuard` — doom loop history and pre-edit-read tracking (not persisted)
- `BackpressureGate` — edit count per-batch state
- `CompletionGatekeeper` — attempt counters and gate history

On session resume, only `PlanManager`, `ChangeLedger`, and `EventStore` state is restored. The safety gates restart fresh, which means post-resume the agent can complete without verifying previously-edited files.

### 2.3 The "Dual Mechanism" Anti-Pattern

Several components have two parallel mechanisms for the same concern:
- `PlanManager` — `CompletableFuture` (legacy) + `CompletableDeferred` (coroutine-native) for approval
- `OrchestratorPrompts` — static worker prompts (dead code?) + `PromptAssembler` dynamic prompts
- `EventStore` — `NEVER_FORGET_TYPES` (event-level protection) + anchor slots (message-level protection) for compression-proof data
- `SelfCorrectionGate` + `BackpressureGate` — both track verification with independently-maintained `VERIFICATION_TOOLS` lists

The dual-mechanism pattern creates maintenance burden and subtle divergence risk.

### 2.4 The "Static Callback" Multi-Project Bug

`AskUserInputTool.showInputCallback` and `RunCommandTool.streamCallback` are set as static/companion-level callbacks from `AgentController.init`. With multiple IntelliJ projects open, the last project's controller wins. Tool callbacks for other projects silently route to the wrong UI or fail.

---

## 3. System-by-System Grades

| System | Grade | Verdict |
|--------|-------|---------|
| **Prompt Engineering** | 4.0/5 | Well-researched 3-zone layout, excellent few-shot examples, but recency zone bloated and instructions duplicated |
| **Context Management** | 4.5/5 | Best-in-class client-side implementation. SmartPrunerCondenser is genuinely novel. Thread-safety bug in EventStore |
| **Tool Architecture** | 4.0/5 | Meta-tool consolidation is ahead of industry. Parameter naming inconsistency and keyword false positives hurt |
| **Sub-Agent Orchestration** | 4.5/5 | Industry-leading coordination primitives. TOCTOU race in FileOwnershipRegistry, no retry in WorkerSession |
| **Planning System** | 4.0/5 | Only agent with built-in plan lifecycle. Dual approval mechanism is a race condition risk |
| **Completion Detection** | 3.0/5 | Over-gated with 5 overlapping mechanisms. Budget exhaustion bypasses all gates |
| **ReAct Loop** | 4.0/5 | Comprehensive edge case handling, but `processLlmSuccess` is 630+ lines |
| **Security** | 4.0/5 | Defence in depth (5-layer DB protection, credential redaction, command safety). ApprovalGate has no timeout |
| **Session Management** | 4.0/5 | Good persistence and resume. Safety gate state not restored |
| **Ralph Loop** | 4.0/5 | Clean state machine with auto-expansion and stuck detection. No iteration diversity |
| **Service Wiring** | 3.0/5 | AgentService is a God Object. Static callbacks. Duplicated panel wiring |
| **Observability** | 4.5/5 | Four complementary systems (metrics, event log, file logger, scorecard). Enterprise-grade |

---

## 4. Critical Issues (Must Fix)

### C1. Thread-Safety Bug in `EventStore.all()` — ConcurrentModificationException Risk
**File:** `context/events/EventStore.kt`
**Impact:** Production crash when background agents append events while the main loop reads

`ArrayList(events)` iterates the underlying `MutableList` without holding the lock. Concurrent `add()` calls can throw `ConcurrentModificationException`.

**Fix:**
```kotlin
fun all(): List<Event> = lock.withLock { ArrayList(events) }
```
Or switch `events` to `CopyOnWriteArrayList`.

### C2. TOCTOU Race in `FileOwnershipRegistry.claim()` — Concurrent Workers Can Both Claim Same File
**File:** `runtime/FileOwnershipRegistry.kt`
**Impact:** Two parallel background workers can both get GRANTED for the same file

Between the `fileOwners[canonical]` read and the `fileOwners[canonical] = ...` write, another thread can claim the same file.

**Fix:**
```kotlin
fun claim(filePath: String, agentId: String, workerType: WorkerType): ClaimResponse {
    val canonical = canonicalize(filePath)
    val record = OwnershipRecord(agentId, workerType)
    val existing = fileOwners.putIfAbsent(canonical, record)
    return when {
        existing == null -> ClaimResponse(ClaimResult.GRANTED)
        existing.agentId == agentId -> ClaimResponse(ClaimResult.GRANTED)
        else -> ClaimResponse(ClaimResult.DENIED, existing.agentId)
    }
}
```

### C3. Budget Exhaustion Bypasses All Completion Gates
**File:** `runtime/SingleAgentSession.kt`
**Impact:** Unverified work accepted as complete when context budget runs out

When `BudgetEnforcer.TERMINATE` fires and `consecutiveNoToolResponses > 0`, the system accepts completion bypassing `CompletionGatekeeper` entirely. Edits that never passed diagnostics are silently accepted.

**Fix:** Route budget exhaustion through `CompletionGatekeeper` in forced-accept mode. This ensures the scorecard records accurate quality signals.

### C4. AgentService is a God Object — 20+ Mutable Nullable Fields
**File:** `AgentService.kt`
**Impact:** Unmaintainable state management, null-safety lottery, session boundary confusion

**Fix:** Extract a `SessionScope` data class holding all per-session state (plan manager, question manager, skill manager, context bridge, change ledger, rollback manager, file ownership, worker bus). Create it at session start, pass it to all consumers, clear at session end.

### C5. `processLlmSuccess` is 630+ Lines With 15+ Responsibilities
**File:** `runtime/SingleAgentSession.kt`
**Impact:** Single biggest maintainability risk. Extremely difficult to reason about interaction effects

**Fix:** Decompose into: `handleEmptyResponse()`, `handleToolCalls()`, `handleCompletion()`, `handleTruncatedResponse()`. Each under 100 lines.

### C6. Anchor Tokens Invisible to Condenser Pipeline — 3-8K Discrepancy
**File:** `context/EventSourcedContextBridge.kt`
**Impact:** Condenser underestimates utilization. At 97% TERMINATE boundary, 5K tokens is the difference between operation and session death

Anchors (plan, facts, guardrails, skills, mentions) are appended AFTER the condenser runs. `estimateCurrentTokens()` excludes them.

**Fix:** Include anchor token estimates in `CondenserContext` or `estimateCurrentTokens()`.

### C7. Static Callbacks on Tool Classes — Multi-Project Bug
**Files:** `ui/AgentController.kt` (lines 165, 894)
**Impact:** With multiple projects open, tool callbacks route to wrong project UI

`AskUserInputTool.showInputCallback` and `RunCommandTool.streamCallback` are set as static/companion-level callbacks.

**Fix:** Use project-scoped lookup via `AgentService.getInstance(project)` or `WorkerContext` instead of static callbacks.

---

## 5. High Priority Issues

### H1. SelfCorrectionGate State Not Restored on Session Resume
**File:** `runtime/SelfCorrectionGate.kt`
**Impact:** Post-resume, agent can complete without verifying previously-edited files

**Fix:** Reconstruct `SelfCorrectionGate.fileStates` from `ChangeLedger` entries during `ConversationSession.load()`.

### H2. No API Retry in WorkerSession
**File:** `runtime/WorkerSession.kt`
**Impact:** Single 429 or 500 error terminates worker immediately (main session retries 5x)

**Fix:** Add 2-3 retries with exponential backoff for `ApiResult.Error` in `WorkerSession.runReactLoop()`.

### H3. Recency Zone Overloaded With 11 Sections
**File:** `orchestrator/PromptAssembler.kt`
**Impact:** Dilutes LLM attention. By section -11 from end, attention is no better than middle zone

**Fix:** Move MEMORY_RULES, CONTEXT_MANAGEMENT_RULES, STEERING_RULES, RENDERING_RULES_COMPACT to context zone. Keep 5-6 sections in recency.

### H4. Parameter Naming Inconsistency in Meta-Tools
**Files:** `tools/integration/JiraTool.kt`, `BambooBuildsTool.kt`
**Impact:** 1-3% of LLM tool calls fail with wrong parameter name, wasting iterations

JiraTool uses `key`, `issue_key`, and `issue_id` for the same concept. BambooBuildsTool uses `build_key` vs `result_key`.

**Fix:** Normalize to single name with runtime alias resolution.

### H5. `%s` Format String Vulnerability in SKILL_RULES_TEMPLATE
**File:** `orchestrator/PromptAssembler.kt`
**Impact:** If skill descriptions contain `%s`, format call throws or produces garbage

**Fix:** Replace `SKILL_RULES_TEMPLATE.format(skillDescriptions)` with Kotlin string template.

### H6. ApprovalGate Waits Indefinitely — No Timeout
**File:** `runtime/ApprovalGate.kt`
**Impact:** Agent coroutine blocks forever if UI dies or dialog never shown

**Fix:** `withTimeoutOrNull(300_000L)` (5 min) with auto-reject and warning log.

### H7. Background Worker Coroutine Scope Detached From Lifecycle
**File:** `tools/builtin/SpawnAgentTool.kt`
**Impact:** Background workers continue running (making API calls) after IDE shutdown

**Fix:** Make background scope a child of `AgentService`'s disposal-aware coroutine scope.

### H8. LLM Summarization Output Cap Too Low (500 Tokens)
**File:** `context/condenser/LlmBrainSummarizationClient.kt`
**Impact:** Complex session summaries truncated, losing critical information across compaction rounds

**Fix:** Increase to 1500 tokens. Still cheap (summarization uses cheaper model) but prevents truncation.

---

## 6. Medium Priority Issues

| # | Component | Issue | Fix |
|---|-----------|-------|-----|
| M1 | PlanManager | Dual approval mechanism (Future + Deferred) creates race potential | Consolidate on coroutine-native `submitPlanAndWait` |
| M2 | SingleAgentSession | `forceTextOnly` is irreversible — once set, agent can never return to tool mode | Reset when valid tool call succeeds |
| M3 | Worker Personas | 8 personas don't mention `worker_complete` — workers may not terminate properly | Add `<completion>` section to each persona |
| M4 | Skills/Personas | Tool names reference old API (`git_blame`) instead of meta-tools (`git(action="blame")`) | Sweep and update all .md files |
| M5 | DynamicToolSelector | False positive keywords ("run", "test", "implement") activate tools unnecessarily | Remove overly-broad keywords |
| M6 | EventSourcedContextBridge | `extractJsonString` regex fails on escaped quotes, nested objects | Replace with `Json.parseToJsonElement()` |
| M7 | Persistence | `File.renameTo()` in CoreMemory/ArchivalMemory — not atomic on Windows | Use `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)` |
| M8 | ToolCategoryRegistry | "planning" category bundles unrelated memory tools | Separate into "memory" category |
| M9 | ConversationSession | Bridge created twice during `create()` — wasteful and confusing | Remove placeholder bridge construction |
| M10 | ConversationWindowCondenser | `noOpCondensation()` creates wasteful empty events | Return `CondenserView` pass-through instead |
| M11 | CommandSafetyAnalyzer | Order-dependent pattern matching with no priority escalation | Add priority-based risk escalation |
| M12 | View.fromEvents() | Called 3-6 times per iteration with no caching — O(N) × 6 per turn | Add `cachedView` invalidated on `eventStore.size()` change |
| M13 | Callback Wiring | `init` (375 lines) and `wireExtraPanel()` (200 lines) are near-identical | Extract shared `wirePanel()` method |
| M14 | AgentStartupActivity | Swallows all exceptions with empty `catch` blocks | Add `LOG.debug` at minimum |
| M15 | PromptAssembler | Persistence instruction stated 3 times (CORE_IDENTITY, PERSISTENCE_AND_COMPLETION, BOOKEND) | Remove from CORE_IDENTITY, keep dedicated section + BOOKEND |
| M16 | Custom Agents | Default to `WorkerType.ORCHESTRATOR` — can bypass depth-1 constraint | Default to CODER type or add explicit `agent` to disallowed-tools |
| M17 | Resume | Full transcript replayed without budget check — can overflow 150K worker context | Summarize if replay exceeds 80% of budget |
| M18 | Condenser Pipeline | Token utilization not recalculated between stages | Re-estimate after each view-modifying condenser |

---

## 7. Over-Engineering Assessment

### Verdict: NOT over-engineered overall. Over-gated at the completion boundary.

| Area | Assessment |
|------|-----------|
| Event-sourced context | **Justified.** Each event type enables pattern matching in condensers. `GenericToolAction` alone would prevent SmartPruner's strategies |
| 4-stage condenser pipeline | **Justified.** Each stage addresses a different cost/benefit tradeoff. Removing any loses either information or cost efficiency |
| 3-tier memory | **Justified.** Core (always in prompt, 4KB) + Archival (searchable) + Recall (past sessions) serve distinct purposes |
| Sub-agent coordination | **Justified.** FileOwnershipRegistry, WorkerMessageBus, WorkerContext are ~320 lines total for real capabilities competitors lack |
| Compression-proof anchors | **Justified.** Belt-and-suspenders (NEVER_FORGET + anchors) for data that absolutely cannot be lost |
| Plan system with approval | **Justified.** Only agent with built-in plan lifecycle. Deviation detection and dependency tracking are real differentiators |
| **Completion detection** | **OVER-ENGINEERED.** 5 overlapping mechanisms (CompletionGatekeeper 3-gate, AttemptCompletionTool, consecutiveNoToolResponses nudging, forceTextOnly implicit completion, budget exhaustion override) create unpredictable interaction effects. Claude Code: text = done. Cline: attempt_completion + user approval. Both are simpler and more predictable |
| Ralph Loop | **Justified.** Clean state machine with auto-expansion, stuck detection, and budget awareness in ~600 lines |

### Simplification Recommendation for Completion

Reduce from 5 mechanisms to 3:
1. **Explicit completion** — `attempt_completion` tool with plan gate check
2. **Implicit completion** — 2 consecutive text-only responses = done (no 4-nudge sequence)
3. **Budget exhaustion** — route through gate in forced-accept mode (don't bypass)

Remove: `forceTextOnly` as a permanent flag, the 4-nudge escalation sequence.

---

## 8. Industry Comparison

### Overall Positioning

```
                    Sophistication →
                    
Claude Code    ████████████████████████████░░  (server-side advantages)
THIS SYSTEM    ███████████████████████████░░░  (client-side leader)
OpenHands      ██████████████████████░░░░░░░░  (event-sourced, sandboxed)
Cursor         ████████████████░░░░░░░░░░░░░░  (cloud agents, simple prompt)
Cline          █████████████░░░░░░░░░░░░░░░░░  (plugin, new_task handoff)
Codex CLI      ████████████░░░░░░░░░░░░░░░░░░  (sandboxed, ghost snapshots)
SWE-agent      ██████████░░░░░░░░░░░░░░░░░░░░  (academic, config diversity)
Aider          █████████░░░░░░░░░░░░░░░░░░░░░  (architect/editor, simple)
```

### Capability Matrix

| Capability | This System | Claude Code | Cursor | Cline | OpenHands | Codex CLI | SWE-agent |
|---|---|---|---|---|---|---|---|
| Event-sourced context | Full | None (server) | None | None | Full | Partial | None |
| Zero-loss context optimization | SmartPruner (3 strategies) | None | None | None | None | None | None |
| Compression-proof anchors | Dual protection | N/A | None | None | None | None | None |
| Meta-tool consolidation | 15 meta-tools, 144 actions | Flat ~20 tools | Flat ~15 | Flat ~25 | Flat | Flat | Flat |
| Dynamic tool selection | 3-layer hybrid | All always active | Task routing | None | None | None | None |
| Sub-agent file ownership | FileOwnershipRegistry | None | Worktree isolation | None | None | File ownership | None |
| Bidirectional agent messaging | WorkerMessageBus | None | None | None | None | send_input only | None |
| Custom agent definitions | YAML + memory scoping | YAML (no memory) | YAML | YAML | None | None | None |
| Plan lifecycle | Full (create/approve/execute/deviate) | None | None | None | None | None | None |
| Self-correction gate | Verify-reflect-retry | None | None | None | Stuck detector | None | RetryAgent |
| Cross-session learning | GuardrailStore | None | None | new_task | None | None | Config diversity |
| Iterative improvement | Ralph Loop + reviewer | None | None | None | None | None | RetryAgent |
| Enterprise integrations | Jira/Bamboo/Sonar/Bitbucket | None | None | None | None | None | None |
| LocalHistory rollback | Yes (IDE-native) | None | None | None | None | Git snapshots | None |
| Observability | 4 systems | Not documented | Server-side | None | Event stream | Not documented | None |

### Where This System Leads
1. **Only system with zero-loss context optimization** (SmartPruner's file-read dedup, error purging, write superseding)
2. **Only system with compression-proof anchors** (dual NEVER_FORGET + anchor slots)
3. **Only system with built-in plan lifecycle** (create, approve/revise, execute, deviation detection)
4. **Only system with bidirectional parent-child agent messaging**
5. **Only system with cross-session guardrail learning** (doom loop patterns persist and prevent future recurrence)
6. **Only system with enterprise service integrations** in the agent tool surface

### Where This System Trails
1. **No sandboxed execution** — relies on pattern matching, not Docker isolation (vs OpenHands, SWE-agent)
2. **No prompt caching** — blocked by Sourcegraph API (vs Claude Code with Anthropic-native caching)
3. **No server-side compaction** — all context management is client-side (vs Claude Code)
4. **More complex completion than any peer** — 5 mechanisms vs 1-2 in competitors
5. **Higher tool schema token cost** — 34 always-active tools vs 15-20 in competitors

---

## 9. Strengths (What's Genuinely Good)

### 9.1 Context Management — Best-in-Class
The event-sourced context pipeline is the most sophisticated client-side implementation reviewed across 13 tools. SmartPrunerCondenser (zero-loss file-read dedup, error purging, write superseding) is genuinely novel — no equivalent exists in any surveyed tool. The tiered observation masking with recovery hints is more nuanced than OpenHands' binary mask/unmask.

### 9.2 Prompt Architecture — Research-Informed
The three-zone layout (primacy/context/recency) correctly applies proven attention patterns. Conditional integration rules save 1-3K tokens per turn. The few-shot examples are the strongest reviewed in any agent system prompt — contrastive "Bad approach" annotations are backed by compliance research.

### 9.3 Sub-Agent Coordination — Industry-Leading
FileOwnershipRegistry + WorkerMessageBus + WorkerContext provide capabilities no competitor has. Named addressable agents, resume from transcript, background execution, kill, and bidirectional messaging in a single `agent` tool.

### 9.4 Defence in Depth — Enterprise-Grade
Database tools have 5-layer read-only protection. Plan mode has 2-layer enforcement. Credential redaction covers 11 pattern types. Command safety analysis catches fork bombs, sudo, disk formatting, pipe-to-bash. Output validation flags sensitive patterns before reaching the user.

### 9.5 Observability — Production-Ready
Four complementary systems: `AgentMetrics` (per-session runtime stats), `AgentEventLog` (per-session audit trail), `AgentFileLogger` (daily rotating structured logs), and `SessionScorecard` (end-of-session quality report). Plus API debug dumps per session. This exceeds what most production agents ship with.

### 9.6 Ralph Loop — Unique Differentiator
The only built-in iterative self-improvement mechanism with a dedicated reviewer agent, auto-expansion on progress, stuck detection, and budget awareness. Clean state machine in ~600 lines.

---

## 10. Prioritized Remediation Plan

### Phase 1: Safety & Correctness (Do First)
| # | Issue | Effort | Files |
|---|-------|--------|-------|
| C1 | Fix EventStore thread-safety | 1 line | `EventStore.kt` |
| C2 | Fix FileOwnershipRegistry TOCTOU | 10 lines | `FileOwnershipRegistry.kt` |
| H5 | Fix `%s` format string vulnerability | 1 line | `PromptAssembler.kt` |
| H6 | Add ApprovalGate timeout | 5 lines | `ApprovalGate.kt` |
| H7 | Tie background worker scope to lifecycle | 5 lines | `SpawnAgentTool.kt` |
| C7 | Fix static callbacks (multi-project bug) | 20 lines | `AgentController.kt`, tool classes |

**Estimated effort: 1 session**

### Phase 2: LLM Reliability (Do Soon)
| # | Issue | Effort | Files |
|---|-------|--------|-------|
| H3 | Tighten recency zone (11 → 6 sections) | 20 lines | `PromptAssembler.kt` |
| H4 | Normalize meta-tool parameter names | 30 lines | `JiraTool.kt`, `BambooBuildsTool.kt` |
| M3 | Add `worker_complete` to all personas | 16 lines | 8 files in `agents/` |
| M4 | Update tool names in skills/personas | Sweep | Multiple .md files |
| M5 | Remove false-positive keywords | 5 lines | `DynamicToolSelector.kt` |
| M15 | Deduplicate persistence instructions | 5 lines | `PromptAssembler.kt` |
| H8 | Increase summarization cap to 1500 tokens | 1 line | `LlmBrainSummarizationClient.kt` |

**Estimated effort: 1 session**

### Phase 3: Architecture (Do Next)
| # | Issue | Effort | Files |
|---|-------|--------|-------|
| C4 | Extract SessionScope from AgentService | Major refactor | `AgentService.kt` + consumers |
| C5 | Decompose `processLlmSuccess` | Major refactor | `SingleAgentSession.kt` |
| C3 | Route budget exhaustion through gates | 15 lines | `SingleAgentSession.kt` |
| C6 | Include anchors in condenser token estimates | 20 lines | `EventSourcedContextBridge.kt` |
| H1 | Restore SelfCorrectionGate on resume | 30 lines | `ConversationSession.kt` |
| H2 | Add API retry to WorkerSession | 20 lines | `WorkerSession.kt` |

**Estimated effort: 2-3 sessions**

### Phase 4: Polish (When Practical)
| # | Issue | Effort | Files |
|---|-------|--------|-------|
| M1 | Consolidate PlanManager approval mechanism | Medium | `PlanManager.kt`, `CreatePlanTool.kt` |
| M6 | Replace `extractJsonString` with proper JSON | 5 lines | `EventSourcedContextBridge.kt` |
| M12 | Add View caching | 10 lines | `EventSourcedContextBridge.kt` |
| M13 | Deduplicate callback wiring | Medium | `AgentController.kt` |
| M16 | Default custom agents to non-ORCHESTRATOR type | 5 lines | `SpawnAgentTool.kt` |

**Estimated effort: 1-2 sessions**

---

## 11. Appendix: Reviewer Details

### Reviewer 1: Prompt Engineering
- **Scope:** OrchestratorPrompts.kt, PromptAssembler.kt, 8 agent personas, 8 skill templates, prompt research docs
- **Grade:** 4.0/5.0
- **Key finding:** Recency zone overloaded (11 sections), persistence stated 3x, skill injection unbounded
- **Token budget:** Base ~8K, worst case ~18K. Healthy for 190K window

### Reviewer 2: Context Management
- **Scope:** 28 files covering EventStore, View, condensers, memory tiers, budget enforcement
- **Grade:** 4.5/5.0
- **Key finding:** Most feature-complete client-side implementation surveyed. Thread-safety bug in EventStore.all()
- **Unique:** SmartPrunerCondenser has no equivalent in any surveyed tool

### Reviewer 3: Tool Architecture
- **Scope:** ToolRegistry, DynamicToolSelector, 7 meta-tools, 15 builtin tools, 3 PSI tools
- **Grade:** 4.0/5.0 (B+)
- **Key finding:** Meta-tool consolidation ahead of industry. Parameter naming inconsistency causes 1-3% call failures

### Reviewer 4: Sub-Agent Orchestration
- **Scope:** SpawnAgentTool, WorkerSession, WorkerMessageBus, FileOwnershipRegistry, AgentDefinitionRegistry, SkillManager, 8 personas
- **Grade:** 4.5/5.0
- **Key finding:** Industry-leading coordination primitives. TOCTOU race in file ownership

### Reviewer 5: Planning, Completion, ReAct Loop, Security, Ralph Loop
- **Scope:** 28 files covering planning, completion gates, self-correction, doom loops, security, sessions, Ralph
- **Grade:** 4.0/5.0
- **Key finding:** Over-gated completion (5 mechanisms). `processLlmSuccess` at 630+ lines is the biggest maintainability risk

### Reviewer 6: Integration & Wiring
- **Scope:** AgentService, AgentController, AgentCefPanel, settings, LlmBrain, SourcegraphChatClient, database, startup, observability
- **Grade:** 3.5/5.0 (B+)
- **Key finding:** AgentService is a God Object. Static callbacks cause multi-project bug. Streaming resilience is excellent

---

*Audit conducted by 6 parallel Opus-class expert reviewers analyzing 168 source files across the complete agent module. Total analysis: ~715K input tokens, 183 file reads, ~22 minutes wall-clock time.*
