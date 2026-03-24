# Agent System Prompt — Industry Comparison & Gap Analysis

**Date:** 2026-03-24
**Compared:** Our Workflow Orchestrator Agent vs Claude Code, Codex CLI, Cline, Aider, SWE-agent
**Purpose:** Validate our prompt review findings against production-grade implementations

---

## Executive Summary

Our 10 prompt review findings were evaluated against 5 production tools. **6 are genuine issues confirmed by industry practice, 2 are partially valid, and 2 are non-issues that the LLM handles naturally.**

| Finding | Verdict | Industry Evidence |
|---------|---------|-------------------|
| `request_tools` phantom tool | **GENUINE BUG** | Claude Code actually implements ToolSearch; we reference it but don't have it |
| No compression explanation | **GENUINE GAP** | Claude Code appends post-compaction continuation message; Cline generates 10-section summary |
| No error recovery guidance | **GENUINE GAP** | Claude Code has 3-tier error recovery; Codex says "iterate up to 3 times" |
| ORCHESTRATOR prompt too short | **GENUINE GAP** | Codex has distinct worker/explorer prompts; Cline has agent system suffix |
| Duplicate rules (5x "read before edit") | **GENUINE — but intentional** | Claude Code also repeats critical rules; Aider puts reminder at END of every message |
| RENDERING_RULES too long | **PARTIALLY VALID** | No other tool has this — but they also don't have rich JCEF rendering. Keep but consider deferred loading for syntax details |
| No budget nudge explanation | **PARTIALLY VALID** | Claude Code injects `USD budget: $used/$total` but doesn't explain what to do either |
| No multi-turn awareness | **PARTIALLY VALID** | Most tools don't explicitly mention it; context persistence is implicit |
| Worker constraints not stated | **NON-ISSUE** | Workers naturally stop at their tool boundary; Claude Code subagents also aren't told limits |
| Conflicting guidance precedence | **NON-ISSUE** | All tools have overlapping rules; LLMs handle context-dependent precedence well |

---

## Detailed Comparison by Dimension

### 1. System Prompt Structure

| Tool | Approach | Size | Key Insight |
|------|----------|------|-------------|
| **Claude Code** | 250+ conditional components, dynamically assembled | Variable (~15-25K tokens) | Modular: only relevant sections loaded. 78 system prompt files, 47 tool description files |
| **Codex CLI** | Single `prompt.md` + model-specific variants | ~3K words | Flat structure with clear sections: identity, planning, execution, validation, formatting |
| **Cline** | Component-based `PromptBuilder` with template vars | ~4K words | Model-family variants (Claude, GPT, Devstral). XML tool definitions inline |
| **Aider** | 8-section ChatChunks assembly | ~2K words | Minimal system prompt; edit format dominates. System reminder re-appended at END |
| **SWE-agent** | Intentionally minimal system + rich instance template | ~500 words system | Guidance lives in tool docstrings, not system prompt. 5-step workflow in instance |
| **Ours** | 17 sections assembled by PromptAssembler | ~8-10K tokens | Too many sections. RENDERING_RULES alone is 1,100 tokens |

**Takeaway:** Claude Code and Cline use modular/conditional assembly — only include sections relevant to current context. We include everything always. SWE-agent proves that minimal system prompts work when tool descriptions are self-documenting.

**Action:** Make sections conditional (e.g., skip RENDERING_RULES unless chat UI supports it, skip integration tool rules unless those tools are active).

---

### 2. Context Compression

| Tool | Trigger | Mechanism | What's Preserved |
|------|---------|-----------|-----------------|
| **Claude Code** | ~95% capacity (configurable) | 3-layer: microcompaction → auto-compaction → manual. Structured 5-section summary | Last 5 files, user intent, key decisions, errors, pending tasks, todo state |
| **Codex CLI** | Context overflow | Drop oldest item + orphan pairs. Truncation policies per tool output | Recent conversation, truncated tool outputs |
| **Cline** | 75% of context window | `summarize_task` generates 10-section summary with direct user quotes + code snippets | Intent, files with code, problem solving, pending tasks, progress checklist |
| **Aider** | Threshold-based | Recursive LLM summarization of `done_messages`. Repo map regenerated | Active files, conversation summary |
| **SWE-agent** | Never (deterministic only) | `LastNObservations` elides old outputs. `ClosedWindowHistoryProcessor` for stale files. On overflow: submit and terminate | Recent observations only. No LLM summarization |
| **Ours** | 93% (tMaxRatio) | 3-phase: smart pruning → tiered tool result pruning → LLM summarization. Structured 5-section summary | Plan anchor, skill anchor, mention anchor, facts anchor, first system message |

**Takeaway:** Our compression is comparable to Claude Code and Cline. **BUT** our system prompt doesn't explain the compression boundary to the LLM — Claude Code appends "Please continue from where we left off" after compaction; Cline wraps the summary in a continuation prompt. We should do similar.

**Action:** Add post-compression guidance in the system prompt explaining `[CONTEXT COMPRESSED]` marker and what to do.

---

### 3. Tool Selection / Presentation

| Tool | Strategy | Always-On Tools | Dynamic Loading |
|------|----------|----------------|-----------------|
| **Claude Code** | Deferred loading via ToolSearch tool | 9 core tools (~8.1K tokens) | ToolSearch queries deferred tools by name/keyword. 85% token reduction |
| **Codex CLI** | Static (all tools always present) | 3 primary: shell, apply_patch, update_plan | MCP tools added dynamically |
| **Cline** | Static with model-family variants | 14+ tools always in prompt | MCP tools added |
| **Aider** | No function calling | N/A — text-based edit format | N/A |
| **SWE-agent** | Static per config bundle | ~10 tools from YAML config | No dynamic selection |
| **Ours** | Keyword-triggered dynamic selection | ~20 core tools | TOOL_TRIGGERS map with 100+ patterns. Tools only expand, never shrink |

**Takeaway:** Our approach is MORE sophisticated than most. Claude Code's ToolSearch is the gold standard — we could adopt something similar. But our keyword-based selection is a pragmatic middle ground that works well.

**Issue confirmed:** The `request_tools` reference in our prompt is a genuine bug. Claude Code actually implements this as `ToolSearch`. We reference the concept but don't have the tool. Either implement it or remove the mention.

---

### 4. Sub-Agent / Worker Delegation

| Tool | Sub-Agents? | Types | Depth | Context | Key Constraint |
|------|-------------|-------|-------|---------|----------------|
| **Claude Code** | Yes | Explorer (Haiku, read-only), Plan (read-only), General-purpose (all tools) | **Depth=1** (no recursive spawning) | Fresh — prompt is ONLY context | Custom agents via `.claude/agents/` YAML |
| **Codex CLI** | Yes | Explorer (read-only, parallel), Worker (edit, file ownership), Awaiter (long commands) | Depth=1 | Fork copies parent conversation | `agent_max_threads` concurrency limit |
| **Cline** | Yes (v3+) | Read-only research agents | Depth=1 | Independent context + API handler | Max 5 parallel, configurable via `.agents/` |
| **Aider** | No | Architect+Editor mode (2-call split) | N/A | Shared | Not sub-agents, just split reasoning |
| **SWE-agent** | No | RetryAgent (reset + retry on low score) | N/A | Reset | Not delegation, quality retry |
| **Ours** | Yes | ORCHESTRATOR, ANALYZER, CODER, REVIEWER, TOOLER | Depth=1 | Fresh per worker | Max 5 concurrent, 10 iterations, 5-min timeout, rollback on failure |

**Takeaway:** Our sub-agent system is **on par with Claude Code and more capable than Cline/Codex**. Key validations:
- Depth=1 is universal — nobody allows recursive sub-agent spawning
- Read-only explorer is standard (Claude Code, Codex, Cline all have it)
- Fresh context per worker is standard (only Codex forks parent context)
- Custom agent definitions match Claude Code's `.claude/agents/` pattern exactly

**Our unique advantages:**
- TOOLER worker type (enterprise integration) — nobody else has this
- Rollback on worker failure via LocalHistory — unique safety feature
- Transcript persistence for resume — only Codex has similar (fork context)

**Our gap:** Claude Code explicitly tells the LLM "Subagents CANNOT spawn other subagents." Our prompt doesn't state this constraint — the tool just isn't available to workers, but the LLM doesn't know that.

---

### 5. Error Recovery

| Tool | Strategy | Retry Limit | Guidance to LLM |
|------|----------|-------------|-----------------|
| **Claude Code** | 3-tier: tool denial → blocked approach → API retry | 0 API retries (429/529 only) | "Do not re-attempt exact same tool call. Think about why denied and adjust" |
| **Codex CLI** | Format retry + context reduction | 3 formatting retries | "Iterate up to 3 times on formatting" |
| **Cline** | Provider-specific detection + backoff + compaction | 3 empty response retries, 3 stream retries | Nudge: "no tools used" on empty response |
| **Aider** | Reflection loop + lint/test feedback | 3 reflections per message | Error output sent as `reflected_message` |
| **SWE-agent** | Requery on format error + cost-based termination | 3 requery attempts | Auto-submit on fatal error |
| **Ours** | LoopGuard error nudge + doom loop detection | No explicit retry limit | "Address this error before proceeding" (no concrete steps) |

**Finding confirmed: Our error recovery guidance is genuinely weak.** Claude Code gives specific behavioral instructions ("don't re-attempt", "think about why", "adjust approach"). Aider feeds error output back as a reflected message. We just say "address the error."

**Action:** Add Claude Code-style error recovery:
```
"If a tool call fails:
1. Do not retry with identical arguments — you'll get the same error
2. Read the error message and understand the cause
3. Try a different approach or tool
4. If stuck after 2 failed attempts, delegate to a specialized subagent or ask the user"
```

---

### 6. Budget / Token Management

| Tool | Financial Limit | Turn Limit | Context Limit | LLM Awareness |
|------|----------------|------------|---------------|---------------|
| **Claude Code** | `--max-budget-usd` | `maxTurns` | Auto-compaction at 95% | System reminder: "USD budget: $used/$total" |
| **Codex CLI** | Not documented | No explicit limit | Drop oldest on overflow | Not told about limits |
| **Cline** | Cost tracking (display only) | No explicit limit | Auto-condense at 75% | Not told about limits |
| **Aider** | Not applicable | 3 reflections per message | Summarize on threshold | Shown token breakdown |
| **SWE-agent** | `cost_limit` per instance + total | No step limit | Submit on overflow | Not told about limits |
| **Ours** | Token-based budget | 50 main, 10 worker iterations | Compress at 80%, terminate at 97% | NOT told about thresholds |

**Finding partially confirmed:** Most tools DON'T explicitly tell the LLM about budget thresholds. Claude Code shows the dollar amount but doesn't explain what to do at each level. So our lack of budget explanation is common — but adding it would be a competitive advantage.

**Action:** Inject a lightweight budget status (like Claude Code does) rather than documenting all 5 threshold levels in the system prompt.

---

### 7. Safety / Prompt Injection

| Tool | Defense | Sandboxing | Approval Flow |
|------|---------|------------|---------------|
| **Claude Code** | Security Monitor (5667 tokens, two-part classifier). `<external_data>` tag warning | None (trusts OS permissions) | Per-tool approval, auto mode with classifier |
| **Codex CLI** | OS-level sandbox (Seatbelt/seccomp/restricted tokens). MCP tool annotations | Full OS sandbox | Typed approval: Shell, Patch, Network, MCP |
| **Cline** | Forbidden starters, `--` before args, command verification | None | Per-action approval, YOLO mode |
| **Aider** | Git auto-commit + undo | None | No approval (trusts user) |
| **SWE-agent** | Docker container isolation | Full Docker | Submit review before accept |
| **Ours** | `<external_data>` tags, FileGuard, PathValidator, CredentialRedactor | None | ApprovalGate for commands/edits |

**Takeaway:** Our safety is comparable to Claude Code (external data tags + approval gate). We lack OS-level sandboxing (Codex CLI) or container isolation (SWE-agent), but those are deployment-level concerns, not prompt-level.

**Gap:** Our system prompt mentions `<external_data>` but doesn't mention PathValidator or CredentialRedactor. Claude Code's Security Monitor is far more sophisticated (5667-token classifier that evaluates every action). We should at minimum document our safety features in the prompt.

---

### 8. Rule Repetition

| Tool | Approach |
|------|----------|
| **Claude Code** | Repeats critical rules across system prompt, tool descriptions, and system reminders. Intentional redundancy |
| **Codex CLI** | Single statement per rule, no repetition |
| **Cline** | ~40 rules in one section, minimal repetition |
| **Aider** | System reminder re-appended at END of every message (deliberate repetition) |
| **SWE-agent** | Minimal rules, no repetition |

**Finding revised:** Our 5x repetition of "read before edit" is actually aligned with Claude Code and Aider's approach. **Deliberate repetition of safety-critical rules is industry practice**, not a bug. Aider specifically re-appends the entire system reminder at the end of every message to combat instruction fading.

**Action:** Keep the repetition but consolidate into fewer locations (system prompt + LoopGuard reminder = 2 locations, not 5).

---

### 9. Rendering / Output Format

| Tool | Approach |
|------|----------|
| **Claude Code** | GitHub-flavored markdown. No rendering instructions in prompt |
| **Codex CLI** | Detailed output formatting spec (headers, bullets, monospace, file references) |
| **Cline** | No rendering instructions |
| **Aider** | Edit format instructions (SEARCH/REPLACE blocks) |
| **SWE-agent** | No rendering instructions |

**Finding REVISED — NOT a waste:** Other tools don't have this because they render plain markdown only. Our plugin has a rich JCEF-based chat UI that supports animated flow diagrams, interactive charts, and Mermaid with entrance animations. The LLM needs to know the syntax to produce these rich outputs when users ask explanatory questions. This is a **differentiating feature**, not bloat.

**However:** 1,100 tokens could still be optimized. Consider moving the detailed JSON syntax to a reference file loaded only when the LLM decides to produce a visualization, keeping ~200-300 tokens of format overview in the system prompt.

**Action:** Keep RENDERING_RULES but consider splitting into: brief overview in system prompt + detailed syntax in a deferred reference file.

---

### 10. Worker Prompt Quality

| Tool | Worker Prompt | Detail Level |
|------|--------------|--------------|
| **Claude Code** | Explorer: "Fast agent for exploring codebases." Plan: "Software architect agent for designing implementation plans." General: Full system prompt inherited | Minimal — role + tool list |
| **Codex CLI** | Explorer: told to be "fast, read-only". Worker: told "you are not alone in the codebase" (file ownership awareness) | Moderate — behavioral cues |
| **Cline** | Subagent suffix: "You are running as a research subagent... Only use execute_command for readonly operations" | Minimal — constraint statement |
| **Ours** | ANALYZER: 1100+ tokens (excellent). CODER: 80 lines (good). ORCHESTRATOR: 18 words (terrible) | Inconsistent |

**Finding confirmed:** Our ORCHESTRATOR worker prompt (18 words) is genuinely inadequate. Claude Code's general-purpose agent inherits the full system prompt. Codex's worker gets behavioral cues about file ownership. Even Cline's minimal suffix is more informative.

**Action:** Expand ORCHESTRATOR prompt to at least include: role, available tool categories, iteration limit, what to do on failure, and that it cannot spawn sub-agents.

---

## Summary: What To Fix vs What's Fine

### Fix These (confirmed by industry)

| Priority | Issue | Industry Evidence |
|----------|-------|-------------------|
| P0 | `request_tools` phantom tool | Claude Code actually has ToolSearch. Remove reference or implement |
| P0 | ORCHESTRATOR prompt too short | All tools give workers meaningful prompts |
| P1 | No error recovery guidance | Claude Code: "don't re-attempt, adjust approach". Aider: reflection loop |
| P1 | No compression boundary explanation | Claude Code: "continue from where we left off". Cline: 10-section summary wrap |
| P2 | RENDERING_RULES optimization | Keep (unique rich UI capability) but consider deferred loading for detailed syntax |
| P2 | Worker constraints not stated in prompt | Claude Code says "cannot spawn subagents". Workers should know their limits |

### Keep As-Is (validated by industry)

| What | Why |
|------|-----|
| Repeated "read before edit" rule | Aider re-appends entire reminder. Claude Code repeats across sections. Intentional |
| Dynamic tool selection | More sophisticated than Codex/Cline/SWE-agent. Only Claude Code's ToolSearch is better |
| Sub-agent architecture (5 types) | On par with Claude Code. TOOLER type is unique advantage |
| Compression mechanism (3-phase) | Comparable to Claude Code's 3-layer approach |
| Safety (`<external_data>` + guards) | Matches Claude Code. Better than Cline/Aider |
| Budget escalation (5 thresholds) | More granular than any competitor. Don't simplify |

### Consider Adopting

| From | Pattern | Why |
|------|---------|-----|
| **Claude Code** | ToolSearch for deferred tool loading | 85% token reduction. Our 86 tools are too many to send always |
| ~~**Aider**~~ | ~~Repo map via tree-sitter + PageRank~~ | **SKIP** — Already evaluated and rejected. Our 13 PSI tools (find_definition, type_hierarchy, call_hierarchy, etc.) provide real-time IDE-grade semantic intelligence far superior to a static tree-sitter parse |
| **Codex CLI** | File ownership in worker prompts | "You are not alone in the codebase" prevents edit conflicts |
| **SWE-agent** | Self-documenting tool descriptions | Shift guidance from system prompt to tool docstrings |
| **Cline** | 10-section compression summary | More structured than our 5-section. Includes direct user quotes |
| **Claude Code** | Post-compaction continuation message | "Continue from where we left off" — simple but effective |
