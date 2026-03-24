# Unified Agent TODO — Everything Left to Do

**Sources merged:** Prompt quality review, industry comparison (Claude Code, Codex, Cline, Aider, SWE-agent), codebase re-analysis, enterprise best practices (Anthropic, OpenAI, LangChain)

**Status:** Architecture is 70% enterprise-grade. 19 items remain across 4 phases.

---

## Phase 1 — Security & Safety (P0)

*These block enterprise deployment. Fix before any production use.*

### 1.1 ApprovalGate Enforcement + Context-Aware Risk
**File:** `agent/runtime/ApprovalGate.kt`
**Problem:** Returns `ApprovalResult` but doesn't block execution. Static risk (edit_file on tests = same as core code). No timeout — hangs forever.
**Evidence:** Anthropic recommends rule-based fast-path (<10ms). "87% of jailbreak prompts still work on advanced models."
**Fix:**
- Make `check()` block the calling coroutine until user responds
- Add 60-second timeout with auto-deny for HIGH/DESTRUCTIVE
- Context-aware risk scoring: analyze file path (test → LOW, core → HIGH), command arguments (ls → NONE, rm → DESTRUCTIVE)
- Audit log every decision (who, what, when, result) to SessionTrace

### 1.2 OutputValidator Must Block Execution
**File:** `agent/security/OutputValidator.kt`
**Problem:** `validate()` returns list of issues but nothing enforces them. Agent can write credentials to files.
**Evidence:** "Lethal Trifecta: data access + untrusted input + exfiltration = vulnerable."
**Fix:**
- Call `validate()` BEFORE applying edits or running commands
- If issues found → block execution, log violation, inform agent with specific error
- Add command injection detection for `run_command`: block `$(subshell)`, backticks, pipes to `curl/wget`
- Add path traversal detection: reject `../../` patterns in file operations

### 1.3 CredentialRedactor Applied Pre-LLM
**File:** `agent/security/CredentialRedactor.kt`
**Problem:** Applied to LLM output (post-generation). Credentials are already in context — LLM has "seen" them.
**Evidence:** Claude Code's Security Monitor evaluates every action. Credentials should never enter context.
**Fix:**
- Apply redactor to tool results BEFORE injecting into `ContextManager`
- Add patterns: JWTs, OAuth tokens, Azure keys, environment variable assignments
- Log what was redacted (audit trail)

### 1.4 Command Safety Analyzer for run_command
**File:** New — `agent/security/CommandSafetyAnalyzer.kt`
**Problem:** `run_command` executes arbitrary shell commands with no content analysis.
**Evidence:** Codex CLI uses OS-level sandbox (Seatbelt/seccomp). We don't have sandbox — need analysis.
**Fix:**
- Classify commands: SAFE (ls, cat, grep, git status, mvn test), RISKY (git push, docker, npm publish), DANGEROUS (rm -rf, drop table, kill -9)
- SAFE → auto-approve if user preference allows
- RISKY → always require approval
- DANGEROUS → block with warning, require explicit user confirmation

---

## Phase 2 — Reliability & Completeness (P1)

*These affect agent quality and user trust.*

### 2.1 Diff Hunk Implementation
**Files:** `agent/ui/AgentCefPanel.kt`, `agent/ui/AgentController.kt`
**Problem:** 26 JCEF bridges wired. `onAcceptDiffHunk`/`onRejectDiffHunk` just log — no file editing.
**Evidence:** Claude Code applies patches atomically. Codex uses `apply_patch` with grammar. Aider auto-commits.
**Fix:**
- Parse diff/patch from hunk data
- Apply to file via `WriteCommandAction` with fuzzy matching for shifted context
- Handle conflicts: show conflict UI if patch doesn't apply
- After acceptance: update agent context
- After rejection: inform agent to try alternative

### 2.2 Plan Execution Enforcement
**File:** `agent/runtime/PlanManager.kt`
**Problem:** Agent can deviate from plan without warning. No step dependencies. No conflict detection.
**Evidence:** Anthropic: "One feature at a time — multiple simultaneously is core failure mode."
**Fix:**
- Step dependency graph: steps declare prerequisites
- Deviation detection: warn if agent edits files not in current step's scope
- Progress persistence: save step status + comments to disk after each update
- Post-step verification: run diagnostics on affected files after marking complete
- Plan history: append-only log of plan versions

### 2.3 Error Recovery Guidance in System Prompt
**File:** `agent/orchestrator/OrchestratorPrompts.kt`
**Problem:** No concrete error recovery guidance. LoopGuard says "address the error" — no steps.
**Evidence:** Claude Code: "Don't re-attempt same call. Think about why denied. Adjust approach."
**Fix:** Add to RULES section:
```
If a tool call fails:
1. Do not retry with identical arguments — you'll get the same error
2. Read the error message and understand the root cause
3. Try a different approach or tool
4. If stuck after 2 failed attempts, delegate to a specialized subagent
5. If a tool is denied by the user, do not try to work around the denial
```

### 2.4 Observability Metrics Export
**Files:** New — `agent/runtime/AgentMetrics.kt`, update `agent/runtime/SessionTrace.kt`
**Problem:** Token tracking exists but no structured metrics export. No circuit breakers.
**Evidence:** "94% of production agents have observability vs ~70% of prototypes."
**Fix:**
- Structured session metrics emitted at session end (JSON):
  - sessionId, duration, turnCount, totalTokens, costEstimate
  - toolCallCount, toolErrorCount, compressionCount
  - approvalCount (approved/denied/timeout), loopGuardTriggers
  - subagentCount, subagentTokens
- Per-tool metrics: call count, avg latency, error rate
- Circuit breaker: if error rate > 50% over 5 consecutive calls → pause and ask user
- Export to IDE diagnostic log

### 2.5 Post-Compression Continuation Message
**File:** `agent/context/ContextManager.kt`
**Problem:** After compression, LLM doesn't know what happened. No `[CONTEXT COMPRESSED]` explanation.
**Evidence:** Claude Code appends "Continue from where we left off." Cline wraps in continuation prompt.
**Fix:**
- After compression, inject system message:
  ```
  Context was compressed to stay within limits. Messages above are a lossy summary.
  Your key findings are preserved in <agent_facts>. ALWAYS re-read files before editing.
  Continue from where you left off.
  ```

---

## Phase 3 — Efficiency & Prompt Quality (P2)

*These improve token efficiency and LLM decision quality.*

### 3.1 Remove `request_tools` Phantom Reference
**File:** `agent/orchestrator/OrchestratorPrompts.kt`
**Problem:** System prompt tells LLM to call `request_tools` — tool doesn't exist.
**Evidence:** Claude Code actually implements `ToolSearch`. We reference concept but never built it.
**Fix:** Either:
- **Option A:** Remove the mention entirely (quick)
- **Option B:** Implement as a real tool that activates tool groups by category (better, ~1 day)

### 3.2 Expand ORCHESTRATOR Worker Prompt
**File:** `agent/orchestrator/OrchestratorPrompts.kt`
**Problem:** ORCHESTRATOR prompt is 18 words: "You are an AI coding assistant." Custom agents inheriting this get no guidance.
**Evidence:** Claude Code: inherits full system prompt. Codex: "you are not alone in the codebase."
**Fix:** Expand to ~200 words:
```
You are an AI coding assistant with full tool access. You can read, edit, search,
run commands, and interact with enterprise services (Jira, Bamboo, SonarQube, Bitbucket).

Constraints:
- You have [N] iterations to complete your task
- You cannot spawn sub-agents — if the task is too complex, report back
- Always read files before editing
- Run diagnostics after edits
- Report status at the end: complete, partial, or failed

If you encounter errors, try a different approach rather than retrying the same action.
```

### 3.3 Tool Selection Refactoring
**File:** `agent/tools/DynamicToolSelector.kt`
**Problem:** 175 keywords in flat map. Exact substring matching. Unmaintainable.
**Evidence:** Claude Code's ToolSearch saves 85% tokens.
**Fix (incremental approach):**
- Group 175 keywords into 12 semantic categories
- Each category: name, description, trigger keywords, tool list
- Add fuzzy keyword matching (Levenshtein distance ≤ 2)
- Add tool dormancy: if no relevant keywords in last 5 messages, mark tools as name-only
- Future: implement full deferred loading like Claude Code's ToolSearch

### 3.4 Consolidate Duplicate Rules
**File:** `agent/orchestrator/OrchestratorPrompts.kt`, `agent/runtime/LoopGuard.kt`
**Problem:** "Read before edit" appears 5 times. Industry validates repetition as intentional BUT 5 is excessive.
**Evidence:** Aider repeats at END of every message (2 locations). Claude Code repeats across sections (2-3 locations).
**Fix:** Consolidate to 2 locations:
- System prompt RULES section (authoritative)
- LoopGuard instruction-fade reminder (reinforcement)
- Remove from: CODER_SYSTEM_PROMPT, CONTEXT_MANAGEMENT_RULES, and individual tool descriptions

### 3.5 RENDERING_RULES Deferred Loading
**File:** `agent/orchestrator/OrchestratorPrompts.kt`
**Problem:** 1,100 tokens always in system prompt. Rich rendering IS a differentiator but syntax details are rarely needed.
**Evidence:** No other tool has this (but no other tool has JCEF rendering either — it's our unique advantage).
**Fix:**
- Keep 200-300 token overview in system prompt (format names + when to use)
- Move detailed JSON syntax to a reference file loaded on demand
- Load full syntax only when LLM decides to produce a visualization

### 3.6 Conditional Prompt Sections
**File:** `agent/orchestrator/PromptAssembler.kt`
**Problem:** All 17 sections included always, even when irrelevant.
**Evidence:** Claude Code: 250+ conditional components, only relevant ones loaded.
**Fix:**
- Skip integration tool rules (Jira/Bamboo/Sonar/Bitbucket sections) unless those tools are active
- Skip RENDERING_RULES syntax unless chat UI is JCEF (not plain text)
- Skip skill/subagent descriptions if none registered
- Save ~2-4K tokens on sessions that don't need all sections

---

## Phase 4 — Quality Assurance (P2-P3)

*These ensure long-term reliability and regression prevention.*

### 4.1 Agent-Level Evaluation Framework
**Problem:** 86 unit tests exist but no agent-quality evaluation.
**Evidence:** Anthropic: "75% per-trial = 42% pass^3. Grade outcomes, not paths."
**Fix:**
- 20 evaluation scenarios from real usage patterns
- Each run 3 times for consistency (pass^3)
- Track: outcome correctness, worker type selection, iteration count, token cost
- Use as release gate

### 4.2 Session Recovery from JSONL
**File:** `agent/runtime/ConversationStore.kt`
**Problem:** Messages saved to JSONL but loading for recovery incomplete.
**Evidence:** Anthropic: "Progress files at session boundaries. Read progress before acting."
**Fix:**
- Implement `loadSession()`: reconstruct context from JSONL
- Recovery injection: "Previous session ended at [status]. Resume from [last step]."
- Don't replay all messages — load compressed summary + recent messages

### 4.3 Multi-Turn Integration Tests
**Problem:** No tests for multi-turn conversations, plan workflows, or sub-agent delegation chains.
**Fix:**
- Test: user message → plan → approve → execute → follow-up question
- Test: context compression mid-conversation → verify anchors preserved
- Test: worker failure → rollback → retry with different approach
- Test: budget escalation → compression → nudge → delegation

---

## Summary: 19 Items, 4 Phases

| Phase | Items | Effort | Impact |
|-------|-------|--------|--------|
| **1. Security & Safety** | 4 items (1.1-1.4) | 1-2 weeks | Blocks enterprise deployment |
| **2. Reliability** | 5 items (2.1-2.5) | 2-3 weeks | Agent quality and user trust |
| **3. Efficiency** | 6 items (3.1-3.6) | 2-3 weeks | Token savings and LLM decision quality |
| **4. Quality Assurance** | 3 items (4.1-4.3) | 1-2 weeks | Regression prevention |

**Total estimated effort:** 6-10 weeks for full enterprise-grade readiness.

**What's already enterprise-grade (keep as-is):**
- Agent loop (ReAct, 50 iterations, budget escalation)
- Context compression (3-phase, anchored content)
- Sub-agent system (5 types, depth=1, rollback, transcripts)
- PSI code intelligence (13 IDE-native tools)
- Rich JCEF visualizations (12 animated formats)
- Custom agent definitions (markdown + YAML)
- Plan approval UI (interactive card with per-step comments)
- TOOLER worker type (enterprise integration — unique advantage)
