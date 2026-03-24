# Enterprise-Grade Agent Gap Analysis — 2026-03-24

**Context:** Re-analysis of the agent module after recent changes, compared against enterprise best practices from Anthropic, OpenAI, LangChain, and production agent research.

---

## Where We Stand vs Enterprise Grade

### Scorecard

| Dimension | Our Status | Enterprise Bar | Gap |
|-----------|-----------|---------------|-----|
| **Agent Loop** | Single ReAct loop, 50 iterations, budget escalation | Claude Code: single-threaded `while(tool_call)`, flat history | Aligned |
| **Context Compression** | 3-phase (smart prune → tiered → LLM summary), anchors | Anthropic recommends: trigger at 70-92%, 3:1 to 5:1 ratio | Aligned |
| **Sub-Agents** | 5 types, depth=1, rollback, transcripts | Industry standard: depth=1, fresh context, no recursive | Aligned |
| **Tool Selection** | Keyword-triggered dynamic, 86 tools | Claude Code: deferred loading (85% token savings). "Start with single agent, add tools before agents" | **Gap: need deferred loading** |
| **Approval Flow** | Static risk classification, callback to UI | Enterprise: context-aware risk, timeout + auto-deny, audit trail | **Gap: no enforcement, no timeout** |
| **Plan System** | Basic (goal, steps, status), approval UI | Enterprise: step dependencies, conflict detection, deviation warning | **Gap: no enforcement** |
| **Security** | InputSanitizer + OutputValidator + CredentialRedactor | "Lethal Trifecta": data access + untrusted input + exfiltration | **Gap: validator doesn't block** |
| **Observability** | SessionTrace + EventLog + token tracking | 94% of production agents have observability. Track: latency, error rates, tool frequency | **Gap: no structured metrics export** |
| **Checkpointing** | LocalHistory snapshots + JSONL transcripts | LangGraph: O(1) channel-based state. "Checkpoint before destructive ops" | Partially aligned |
| **Evaluation** | 86 test files, unit tests | Anthropic: pass^k consistency, 20-50 tasks from real failures | **Gap: no agent-level evals** |
| **Error Recovery** | LoopGuard nudges, doom loop detection | Claude Code: "don't re-attempt, adjust approach", 3-tier recovery | **Gap: no concrete recovery guidance** |
| **Session Recovery** | ConversationStore JSONL persistence | Anthropic: "progress files at session boundaries, git-based state" | **Gap: loading from JSONL not complete** |

---

## The 8 Things That Separate Us From Enterprise Grade

### 1. APPROVAL GATE NEEDS ENFORCEMENT + CONTEXT-AWARENESS

**Current:** `ApprovalGate` classifies risk statically (edit_file = MEDIUM regardless of target). Returns `ApprovalResult` but doesn't block execution. No timeout — hangs forever if user doesn't respond.

**Enterprise standard (Anthropic Safety):** Three-layer validation: rule-based (<10ms) + ML classifier (50-200ms) + LLM judge (300-2000ms). Run in parallel. Risk-based routing. "87% of jailbreak prompts still work on advanced models" — rule-based fast-path is essential.

**What to do:**
```
1. Make ApprovalGate BLOCK execution (not just signal)
2. Add 60-second approval timeout with auto-deny for HIGH/DESTRUCTIVE
3. Context-aware risk scoring:
   - edit_file on test files → LOW
   - edit_file on core business logic → HIGH
   - run_command with rm/drop/delete → DESTRUCTIVE
   - run_command with ls/cat/grep → NONE
4. Add command safety analyzer for run_command arguments
5. Audit log every approval decision (who, what, when, result)
```

**Priority:** P0 — blocking for enterprise use

---

### 2. SECURITY VALIDATORS MUST BLOCK, NOT JUST REPORT

**Current:** `OutputValidator` returns list of issues but doesn't prevent execution. `CredentialRedactor` applied post-LLM (too late). No command injection detection.

**Enterprise standard (Lethal Trifecta):** If your agent has data access + untrusted input + ability to exfiltrate, you're vulnerable. Defense: validate BEFORE execution, not after.

**What to do:**
```
1. OutputValidator.validate() must be called BEFORE applying edits/running commands
2. If validation fails → block execution, log violation, notify user
3. CredentialRedactor: apply to tool results BEFORE injecting into LLM context
   (currently applied to LLM output — too late, LLM already "saw" the credential)
4. Add command injection detection for run_command:
   - Block: $(subshell), backticks, pipes to curl/wget, && chains with sensitive commands
   - Allow: standard build/test/lint commands
5. Add path traversal detection: reject ../../ patterns in file operations
```

**Priority:** P0 — security vulnerability

---

### 3. DIFF HUNK HANDLING — IMPLEMENT OR REMOVE

**Current:** 26 JCEF bridges wired. `onAcceptDiffHunk` and `onRejectDiffHunk` callbacks exist but just log. No actual file editing after acceptance. No conflict resolution.

**Enterprise standard:** Claude Code applies patches atomically. Codex CLI uses `apply_patch` with full grammar validation. Aider auto-commits after every edit for rollback.

**What to do:**
```
1. Implement diff hunk application:
   - Parse the diff/patch from the hunk data
   - Apply to file using WriteCommandAction
   - Handle conflicts (fuzzy matching if context lines shifted)
   - Show result in editor
2. If hunk doesn't apply cleanly:
   - Show conflict UI (ours vs theirs)
   - Allow manual edit
   - Report back to agent
3. After acceptance: update agent context with applied changes
4. After rejection: inform agent so it can try alternative approach
```

**Priority:** P1 — feature completeness

---

### 4. PLAN EXECUTION ENFORCEMENT

**Current:** `PlanManager` stores plan with step status. Agent can deviate without warning. No step dependencies. Revised comments not persisted. No plan history.

**Enterprise standard (Anthropic long-running agents):** "One feature at a time — multiple features simultaneously is a core failure mode." Progress files at session boundaries. Git-based state with descriptive commits.

**What to do:**
```
1. Step dependency graph: steps declare prerequisites
   - Before executing step N, verify steps N-1 are complete
   - Warn (don't block) on out-of-sequence execution
2. Deviation detection: compare tool calls against plan scope
   - If agent calls tools on files NOT listed in current step → warn
   - "You're editing UserService.kt but current step targets AuthService.kt"
3. Progress persistence: save step status + comments to disk after each update
4. Plan history: append-only log of plan versions (not just latest)
5. Post-step verification: after marking step complete, run diagnostics on affected files
```

**Priority:** P1 — reliability

---

### 5. OBSERVABILITY / METRICS EXPORT

**Current:** `SessionTrace` tracks events. `AgentEventLog` records tool calls. `TokenUsageTracker` counts tokens. But no structured metrics export, no dashboards, no alerting.

**Enterprise standard:** "94% of production agents have observability vs ~70% of prototypes." Track: latency, token usage, error rates, turn count, tool call frequency, guardrail triggers, context utilization. Circuit breakers for anomalous behavior.

**What to do:**
```
1. Structured session metrics (emitted at session end):
   {
     sessionId, duration, turnCount, totalTokens, costEstimate,
     toolCallCount, toolErrorCount, compressionCount,
     approvalCount (approved/denied/timeout),
     loopGuardTriggerCount, budgetStatus,
     subagentCount, subagentTokens
   }
2. Per-tool metrics: call count, avg latency, error rate, token cost
3. Circuit breaker: if error rate > 50% over 5 consecutive calls → pause and ask user
4. Export to IDE's diagnostic log in structured JSON format
5. Optional: OTEL trace emission for enterprise monitoring integration
```

**Priority:** P1 — production readiness

---

### 6. TOOL SELECTION REFACTORING

**Current:** 175 keywords in a flat `TOOL_TRIGGERS` map. Exact substring matching. No grouping. No fuzzy matching. Tools only expand, never shrink.

**Enterprise standard (Claude Code):** Deferred loading via `ToolSearch` — 85% token reduction. Only 9 core tools pre-loaded, rest loaded on demand. "Accuracy went from 49% to 74% with ToolSearch."

**What to do:**
```
Option A (recommended): Deferred tool loading
- Pre-load 14 ALWAYS_INCLUDE tools (~5K tokens)
- Register remaining 72 tools as name+description only (~2K tokens)
- Add request_tools tool that activates tool groups by category
- Total: ~7K tokens instead of ~15K (53% reduction)

Option B (incremental): Semantic tool groups
- Replace 175 flat keywords with 12 semantic groups
- Each group has: name, description, trigger keywords, tools
- Better maintainability, same keyword-matching logic

Both options:
- Add tool shrinking: if no relevant keywords in last 5 messages, mark tools as dormant
- Dormant tools stay in name-only mode (re-activated on next keyword match)
```

**Priority:** P2 — token efficiency

---

### 7. AGENT-LEVEL EVALUATION FRAMEWORK

**Current:** 86 unit test files covering individual components. No integration tests for multi-turn conversations. No evaluation of agent quality.

**Enterprise standard (Anthropic evals):** "pass^k for consistency, pass@k for capability. 75% per-trial = ~42% pass^3. Grade outcomes, not paths. Start with 20-50 tasks from real failures."

**What to do:**
```
1. Create 20 evaluation scenarios from real usage:
   - "Fix this compilation error" (single file, should be fast)
   - "Add a new API endpoint with tests" (multi-file, should plan)
   - "Why is this test failing?" (exploration, should delegate to explorer)
   - "Review this PR" (read-only, should use reviewer worker)
   - "Deploy this build" (enterprise tools, should use tooler worker)

2. For each scenario, define:
   - Expected outcome (files changed, tests passing)
   - Expected behavior (used appropriate worker type, didn't over-iterate)
   - Max acceptable cost (tokens, time)

3. Run each scenario 3 times (pass^3 for consistency)
4. Track pass rate over time (regression detection)
5. Use as gatekeeping before releases
```

**Priority:** P2 — quality assurance

---

### 8. SESSION RECOVERY COMPLETION

**Current:** `ConversationStore` persists messages to JSONL incrementally. Metadata saved. But loading sessions from JSONL for recovery is not shown/incomplete. Checkpoint recovery path unclear.

**Enterprise standard (Anthropic):** "Progress files at session boundaries. Git-based state with descriptive commits. Session recovery: read progress + git log before acting."

**What to do:**
```
1. Implement session loading from JSONL:
   - Reconstruct ContextManager from saved messages
   - Restore PlanManager state from plan.json
   - Restore WorkingSet from saved files list
   - Re-inject system prompt with current project state

2. Recovery heuristic:
   - On session resume, inject: "Previous session ended at [status].
     Last completed step: [step]. Resume from where we left off."
   - Don't replay all messages — load compressed summary + recent messages

3. Git-based recovery:
   - After each successful step, descriptive commit: "Agent: [step description]"
   - On failure recovery, git log shows what was accomplished
   - User can cherry-pick or revert individual agent commits
```

**Priority:** P2 — reliability

---

## What We Already Do Better Than Industry

| Advantage | Detail | Who Lacks This |
|-----------|--------|---------------|
| **TOOLER worker type** | Enterprise integration agent (Jira/Bamboo/SonarQube/Bitbucket) with 66 tools | Everyone — no other tool has enterprise service integration |
| **PSI code intelligence** | 13 IDE-native tools (find_definition, type_hierarchy, call_hierarchy) | SWE-agent has text search. Aider has tree-sitter. We have full IDE semantic model |
| **Rich JCEF visualization** | 12 animated visualization formats (flow diagrams, charts, timelines, diff hunks) | Claude Code: plain markdown. Codex: plain text. Nobody has animated diagrams |
| **5-threshold budget escalation** | OK → COMPRESS → NUDGE → STRONG_NUDGE → TERMINATE | Claude Code: 1 threshold. Cline: 1 threshold. Most have binary (ok/overflow) |
| **LocalHistory rollback** | Checkpoint before worker spawn, auto-rollback on failure | Aider: git commit/undo. Others: no rollback. We have IDE-integrated snapshot |
| **Transcript persistence + resume** | JSONL recording of all messages, worker resume capability | Only Codex has fork context. Nobody else has full resume |
| **Custom agent definitions** | User-extensible agents via markdown + YAML frontmatter | Matches Claude Code's `.claude/agents/` exactly. Better than Cline's `.agents/` |
| **Plan approval UI** | Interactive plan card in chat with approve/revise + per-step comments | Claude Code: plan mode exists but no interactive card. Nobody has per-step comments |

---

## Recommended Execution Order

```
Phase 1 — Security & Safety (P0, 1-2 weeks)
├── ApprovalGate enforcement + timeout + context-aware risk
├── OutputValidator blocking + pre-execution validation
├── Command injection detection in run_command
└── CredentialRedactor applied pre-LLM (on tool results)

Phase 2 — Reliability (P1, 2-3 weeks)
├── Diff hunk implementation (apply, conflict, reject flow)
├── Plan execution enforcement (dependencies, deviation detection)
├── Observability metrics export (session + per-tool metrics)
└── Error recovery guidance in system prompt

Phase 3 — Efficiency & Quality (P2, 2-3 weeks)
├── Tool selection refactoring (deferred loading or semantic groups)
├── Agent-level evaluation framework (20 scenarios, pass^3)
├── Session recovery from JSONL
└── System prompt optimization (remove request_tools phantom, expand ORCHESTRATOR prompt)
```

---

## Key Quotes From Research

> "Start with a single agent. Add tools before adding agents." — LangChain

> "Agents melt down rather than degrade gradually." — Vending-Bench research

> "75% per-trial reliability = only 42% consistency across 3 runs." — Anthropic evals

> "94% of production agents have observability vs ~70% of prototypes." — State of Agent Engineering

> "The Lethal Trifecta: data access + untrusted input + exfiltration capability = vulnerable." — AI Security Research

> "One feature at a time. Multiple features simultaneously is a core failure mode." — Anthropic long-running agents

> "Long-running tasks fail 30% of the time; checkpointing saves 60%+ of wasted processing." — Checkpoint/Restore Research
