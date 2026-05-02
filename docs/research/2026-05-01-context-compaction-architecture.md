# Context Compaction — Complete Architecture & Comparative Analysis

> Branch: `feature/context-compaction` · Date: 2026-05-01
> Author research: synthesized from full read of `ContextManager.kt` (891 lines) + `AgentLoop.kt` compaction sites + 8 open-source agent codebases (Cline, Roo Code, OpenCode, Claude Code, Aider, Codex CLI, Continue, Plandex).
>
> **Goal of this document:** end-to-end visual map of how this plugin's agent compacts context, every trigger surface, every state mutation, every gap relative to the field, and where the design decisions are load-bearing.

---

## 0. TL;DR — One-Diagram Mental Model

```
┌──────────────────────────────────────────────────────────────────────────┐
│                     CONTEXT COMPACTION OVERVIEW                          │
│                                                                          │
│  TRIGGERS (3 sites in AgentLoop)                                         │
│  ──────────────────────────────                                          │
│   ① shouldCompact() at iteration top      preventive   (>85% util)       │
│   ② isContextOverflowError after API call reactive     (API rejected)    │
│   ③ timeout exhaustion + opt-in flag      desperate    (network dead)    │
│                                                                          │
│                              │                                           │
│                              ▼                                           │
│   ┌────────────────────────────────────────────────────────┐             │
│   │  ContextManager.compact(brain, hookManager, force)     │             │
│   └────────────────────────────────────────────────────────┘             │
│                              │                                           │
│      PRE_COMPACT hook ──────►│ ◄──── may cancel                          │
│                              │                                           │
│   ┌──────────────────────────┴───────────────────────────┐               │
│   │                                                      │               │
│   ▼ STAGE 1                                              │               │
│   deduplicateFileReads()                                 │               │
│   • Replace older `read_file` results w/ pointer notice  │               │
│   • Keep most recent read intact                         │               │
│   • If saved ≥ 30% → STOP (cheapest stage wins)          │               │
│                              │                                           │
│   ▼ STAGE 2 (only if 30% gate failed OR utilization >85%)│               │
│   truncateConversation(HALF | QUARTER)                   │               │
│   • Always preserve first user-assistant pair            │               │
│   • Drop middle in even-count blocks                     │               │
│   • Insert "[Context truncated]" notice at index 1       │               │
│   • Strip orphaned tool results at boundary              │               │
│   • Persist via onHistoryOverwrite                       │               │
│                              │                                           │
│   ▼ STAGE 3 (only if util STILL >95% — even under force) │               │
│   llmSummarize(brain)                                    │               │
│   • Safe split at 70% (avoids tool_call/result pairs)    │               │
│   • Structured prompt: TASK/FILES/DONE/ERRORS/PENDING    │               │
│   • Summary chaining (lastSummary fed forward)           │               │
│   • Insert as ASSISTANT msg (not user) at index 0        │               │
│                              │                                           │
│   ▼ POST                                                 │               │
│   reInjectActiveSkill() · reInjectActivePlan()           │               │
│   invalidateTokens()  →  forces re-estimate              │               │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 1. Architecture Overview

### 1.1 Module layout

```
agent/src/main/kotlin/com/workflow/orchestrator/agent/
├── loop/
│   ├── ContextManager.kt          ← 891 lines — owns compaction pipeline
│   ├── AgentLoop.kt               ← 3 trigger sites
│   └── ModelFallbackManager.kt    ← orthogonal (model swap, not compaction)
├── session/
│   ├── MessageStateHandler.kt     ← persistence target of onHistoryOverwrite
│   └── TaskStore.kt               ← attached for live task rendering
├── tools/
│   ├── ToolOutputSpiller.kt       ← upstream defense (per-tool >30K spills)
│   └── ToolOutputConfig.kt        ← per-tool caps (50K default, 100K command)
└── settings/
    └── AgentSettings.kt           ← maxInputTokens (190K default)
```

### 1.2 What ContextManager owns vs. what it delegates

| Concern | Owner | Notes |
|---|---|---|
| Message list (mutable) | ContextManager | `messages: MutableList<ChatMessage>` |
| System prompt | ContextManager | Field `systemPrompt`, included in `getMessages()` |
| File-read tracking | ContextManager | `fileReadIndices: Map<String, MutableList<Int>>` |
| Token utilization | ContextManager | `lastPromptTokens` (API-reported) ∨ `tokenEstimate()` |
| Active skill content | ContextManager | Re-injected post-compaction |
| Active plan path | ContextManager | Re-injected as pointer message |
| Task progress markdown | TaskStore (attached) | Rendered live into system prompt §2 |
| Tool output truncation | ToolOutputSpiller | Upstream — full content already trimmed before reaching ContextManager |
| Disk persistence | MessageStateHandler | Via `onHistoryOverwrite` callback |
| Compaction trigger decisions | AgentLoop | ContextManager only exposes `shouldCompact()` predicate |

`★ Insight ─────────────────────────────────────`
- **Two layers of defense, not one.** ToolOutputSpiller intercepts >30K tool outputs *before* they hit the message list — full content goes to disk, only a head-20 + tail-10 preview enters context. ContextManager's compaction is the *second* layer that handles accumulated message bloat. The two layers are designed independently and don't share state.
- **TaskStore is the only "live" data source.** Every other field (skill, plan, summary) is a snapshot. Tasks are rendered fresh every prompt rebuild because the LLM commonly mutates them mid-iteration.
- **The system prompt is rebuilt every iteration**, not stored once. That means MEMORY.md, IDE context, available tools, active skill — anything system-prompt-shaped — survives compaction trivially because it's regenerated, not preserved.
`─────────────────────────────────────────────────`

---

## 2. Trigger Surfaces — Three Paths Into Compaction

```
                    AgentLoop iteration N
                            │
                            ▼
               ┌─────────────────────────┐
               │ Stage 0: drain steering │
               └─────────────────────────┘
                            │
                            ▼
        ┌───────────────────────────────────────┐
        │ ① PREVENTIVE  (line 583)              │
        │   if (contextManager.shouldCompact()) │
        │     contextManager.compact(brain)     │  ◄── runs before LLM call
        │   utilization > 85%                   │
        └───────────────────────────────────────┘
                            │
                            ▼
              ┌─────────────────────────┐
              │ build messages + tools  │
              │ call brain.chat(...)    │
              └─────────────────────────┘
                            │
              ┌─────────────┴─────────────┐
              │                           │
              ▼ ApiResult.Success         ▼ ApiResult.Error
        process tool calls         ┌──────────────────────────────┐
              │                    │ ② OVERFLOW REPLAY (line 753) │
              │                    │   isContextOverflowError &&  │
              ▼                    │   retries < 2                │
        next iteration             │     compact(brain)           │
                                   │     iteration--              │
                                   │     continue                 │
                                   └──────────────────────────────┘
                                              │
                                   ┌──────────┴───────────┐
                                   │                      │
                                   ▼ matched              ▼ unmatched
                              compact + replay     fall through to
                                                   timeout/retry logic
                                                          │
                                                          ▼
                              ┌────────────────────────────────────────┐
                              │ ③ TIMEOUT EXHAUSTION  (line 897)       │
                              │   compactOnTimeoutExhaustion=true   && │
                              │   apiResult.type in TIMEOUT_ERRORS   &&│
                              │   compactionRetries < 2                │
                              │     compact(brain)                     │
                              │     apiRetryCount = 0                  │
                              │     iteration--                        │
                              │     continue                           │
                              └────────────────────────────────────────┘
```

### 2.1 Trigger ① — Preventive (`AgentLoop.kt:583`)

| Property | Value |
|---|---|
| Predicate | `utilizationPercent() > 85.0` |
| Source of truth for tokens | `lastPromptTokens` from prior API response, falls back to `tokenEstimate()` |
| When in iteration | Top of loop, **before** the LLM is called |
| Iteration cost | Counts as the iteration (no decrement) |
| `force` flag | `false` (normal pipeline gates apply) |

This is the **only path that runs without an error condition**. The other two are reactive.

### 2.2 Trigger ② — Context-Overflow Replay (`AgentLoop.kt:753`)

```
┌──────────────────────────────────────────────────────────┐
│ isContextOverflowError(apiResult): Boolean               │
│ ─────────────────────────────────────────                │
│ Matches when:                                            │
│   apiResult.type == ErrorType.CONTEXT_LENGTH_EXCEEDED    │
│ OR (type ∈ {VALIDATION_ERROR, SERVER_ERROR} AND          │
│     message matches any of:                              │
│       /context.{0,20}(length|window|limit)/i             │
│       /(maximum|max).{0,20}(token|context)/i             │
│       /token.{0,20}(limit|exceeded|overflow)/i           │
│       /(input|prompt).{0,10}too.{0,10}(long|large)/i)    │
└──────────────────────────────────────────────────────────┘
```

| Property | Value |
|---|---|
| Predicate | `isContextOverflowError(apiResult) && contextOverflowRetries < 2` |
| Constant | `MAX_CONTEXT_OVERFLOW_RETRIES = 2` (`AgentLoop.kt:407`) |
| Iteration accounting | `iteration--` so retries don't burn budget |
| Counter reset | `contextOverflowRetries = 0` after any successful API call |

Ported from **OpenCode**'s pattern: catch overflow, compact, replay the same iteration. The pattern-match list is broader than OpenCode's because Sourcegraph's API can return overflow as `VALIDATION_ERROR` instead of a dedicated error type.

### 2.3 Trigger ③ — Timeout Exhaustion (`AgentLoop.kt:897`)

| Property | Value |
|---|---|
| Predicate | `compactOnTimeoutExhaustion && apiResult.type ∈ TIMEOUT_ERRORS && compactionRetries < 2` |
| Activation | `AgentSettings.networkErrorStrategy == "context_compaction"` |
| Constant | `MAX_COMPACTION_RETRIES = 2` |
| Iteration accounting | `iteration--`, also resets `apiRetryCount = 0` |
| Rationale | Last-ditch when network retries are exhausted; sometimes the *real* cause is an over-large prompt timing out the proxy |

This is **opt-in** — the default `networkErrorStrategy = "none"` means trigger ③ is dormant for most users.

---

## 3. The 3-Stage Pipeline — Anatomy

### 3.1 Stage 1 — Duplicate file-read deduplication

```
fileReadIndices : Map<filePath, [msgIdx, msgIdx, msgIdx, ...]>
                                  ▲           ▲          ▲
                                  oldest      middle     latest

   For each path with size > 1:
     keep messages[latest] unchanged
     for each older idx:
       messages[idx].content = "[File content for '{path}' — see latest read below]"
       savedChars += oldLen - notice.length

   return savedChars / totalChars   ── if ≥ 0.30, STOP (skip Stage 2/3)
```

**Tracking discipline.** `fileReadIndices` is populated in `addToolResult()` by:
1. Looking at the prior assistant message's `tool_calls` to find the matching call's `function.name`.
2. If the tool name is in `FILE_READ_TOOLS = {read_file, create_file, edit_file}`, parse the path.
3. Path extraction handles both header-style (`[read_file for '/path']`) and tag-style (`<file_content path="/path">`).

After any compaction step that removes/replaces messages, indices are stale → `rebuildFileReadIndices()` is called.

**Why this stage exists.** Long agent sessions re-read the same files repeatedly (it's how the LLM "remembers" what's there). Each re-read pushes a multi-KB blob into context. Replacing all but the latest with a 60-char pointer is the cheapest possible win — no LLM call, no information lost (the latest content is still there).

### 3.2 Stage 2 — Conversation truncation (Cline's `getNextTruncationRange`)

```
Before truncation:
  [user₀][asst₀] [tool₁] [user₂][asst₂] [tool₃] [user₄][asst₄] [user₅][asst₅]
  ^^^^^^^^^^^^^^                                                ^^^^^^^^^^^^^
  PROTECTED                                                     RECENT

  ↓ HALF strategy: removes ~50% of middle in even-count blocks

After truncation:
  [user₀][asst-NOTICE]                                          [user₅][asst₅]
                                                                ^^^^^^^^^^^^^

  asst-NOTICE = "[Context truncated — N messages removed to free context space.
                 The conversation continues below with the most recent exchanges.]"
                 (toolCalls cleared so no orphan refs)
```

**Algorithm details:**

| Strategy | Removed | When chosen |
|---|---|---|
| `HALF` | `floorDiv(messages.size - 2, 4) * 2` | Default — `estimatedTokens / 2 ≤ maxInputTokens` |
| `QUARTER` | `floorDiv((messages.size - 2) * 3, 4) / 2 * 2` | Aggressive — `estimatedTokens / 2 > maxInputTokens` |
| `LAST_TWO` | `messages.size - 4` | Manual / last-resort |
| `NONE` | `messages.size - 2` | Effectively delete-all-but-task |

**Boundary-correctness rules:**
1. **Always end on assistant.** If `messages[rangeEnd].role != "assistant"`, decrement `rangeEnd` to keep alternation valid post-cut.
2. **Absorb orphan tool results.** If `messages[rangeEnd+1].role == "tool"`, walk forward absorbing all consecutive tool messages — otherwise the surviving tool result has no preceding `assistant.tool_calls` to pair with, breaking OpenAI-format validation.
3. **Notice replaces index 1 (not inserted).** Avoids shifting all later indices and preserves the "first user-assistant pair" anchor as exactly indices 0+1.

**Persistence.** After mutation, `onHistoryOverwrite(messages, removedRange)` fires → MessageStateHandler atomically rewrites `api_conversation_history.json`.

### 3.3 Stage 3 — LLM summarization (our addition, not in Cline)

```
splitPoint = findSafeSplitPoint(messages.size * 0.7)
                ▲
                └── Walks forward/backward to land on a clean
                    user→assistant boundary, never mid-tool-pair

       ┌─ messages[0 .. splitPoint-1] ─┐         ┌─ messages[splitPoint .. end] ─┐
       │                                │         │                              │
       │  TO BE SUMMARIZED              │         │  KEPT AS-IS                  │
       └────────────────┬───────────────┘         └──────────────────────────────┘
                        │
                        ▼
       brain.chat(summarizationPrompt, maxTokens=1024)
                        │
       ┌────────────────┴───────────────────────┐
       │                                        │
       ▼ ApiResult.Success                      ▼ ApiResult.Error
   replace [0..splitPoint-1] with:           return without modifying
   ┌─────────────────────────────────┐       (failure is silent + safe)
   │ role: "assistant"               │
   │ content: "[Context Summary]\n   │
   │   TASK: ...                     │
   │   FILES: ...                    │
   │   DONE: ...                     │
   │   ERRORS: ...                   │
   │   PENDING: ..."                 │
   └─────────────────────────────────┘
   lastSummary = summaryContent       ◄── chained into NEXT Stage 3 prompt
```

**Three load-bearing decisions:**

1. **Role is `assistant`, not `user`.** Inserting as `user` would create consecutive user messages (the next surviving message is also `user`), and many providers reject that. From an expert review fix.
2. **Summary chaining.** Each successive summarization includes the prior summary in its prompt: `"Previous summary (incorporate and update this): ..."` — so memory accumulates rather than each summarization standing alone.
3. **`findSafeSplitPoint` is non-trivial.** A naive split at `0.7 * size` will sometimes land between an assistant message with `tool_calls` and the corresponding `tool` result — corrupting the format. The helper walks until it finds a boundary where the surviving prefix is structurally complete.

### 3.4 Stage gating logic

```
                   ENTRY (compact called)
                          │
                          ▼
                   PRE_COMPACT hook ── cancel? ──► return
                          │
                          ▼ no cancel
                   force OR util > 70% ── no ──► return
                          │
                          ▼ yes
                   STAGE 1: dedup
                          │
                          ▼
                   percentSaved >= 0.30 AND !force? ── yes ──► return
                          │
                          ▼ no
                   force OR util > 85%? ── no ──► return
                          │
                          ▼ yes
                   STAGE 2: truncate
                          │
                          ▼
                   util > 95%? ── no ──► return
                          │
                          ▼ yes
                   STAGE 3: LLM summarize
                          │
                          ▼
                   reInjectActiveSkill()
                   reInjectActivePlan()
                          │
                          ▼
                   invalidateTokens()
                          │
                          ▼
                          EXIT
```

**Force semantics** (manual user cleanup, not yet wired in production):

| Stage | `force=false` gate | `force=true` gate |
|---|---|---|
| Entry | `util > 70%` | always |
| Stage 1 | always | always |
| Stage 1→2 | `percentSaved < 30%` | always (skip dedup gate) |
| Stage 2 | `util > 85%` | always |
| Stage 3 | `util > 95%` | `util > 95%` (still gated — LLM call is expensive) |

---

## 4. Token Math — How Utilization Is Computed

```
┌────────────────────────────────────────────────────────────────────┐
│  utilizationPercent()                                              │
│  ────────────────────                                              │
│                                                                    │
│  numerator = lastPromptTokens                                      │
│              ?: tokenEstimate()                                    │
│                                                                    │
│  return numerator / maxInputTokens * 100                           │
└────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────┐
│  tokenEstimate()                                                   │
│  ───────────────                                                   │
│                                                                    │
│  charCount = systemPrompt.length                                   │
│            + Σ(messages[i].content.length)                         │
│            + Σ(messages[i].toolCalls.[name + args].length)         │
│                                                                    │
│  messageTokens = charCount / 3.5             ◄── chars-per-token   │
│  overheadTokens = (messages.size + 1) * 4    ◄── role/delim cost   │
│  return messageTokens + overheadTokens + toolDefinitionTokens      │
└────────────────────────────────────────────────────────────────────┘
```

| Constant | Value | Source |
|---|---|---|
| `compactionThreshold` | 0.85 | Hardcoded in `ContextManager` constructor |
| `maxInputTokens` | 190 000 | `AgentSettings.maxInputTokens` (configurable) |
| chars-per-token | 3.5 | Tuned for code-heavy content (vs. OpenAI's 4.0 for prose) |
| Per-message overhead | 4 tokens | Role + delimiter cost |

**`lastPromptTokens` lifecycle:**

```
        API success ──► updateTokens(promptTokens) ──► lastPromptTokens = X
                                                              │
                                                              ▼
                                          utilizationPercent() reads X
                                                              │
                              ┌───────────────────────────────┘
                              │
                              ▼ if shouldCompact
                          compact() ── invalidateTokens() ──► lastPromptTokens = null
                                                              │
                                                              ▼
                                          utilizationPercent() falls back to tokenEstimate()
                                                              │
                                                              ▼
                                          on next API success, lastPromptTokens reset
```

**The "stale token" bug guard.** Without `invalidateTokens()`, compaction would mutate the message list but `utilizationPercent()` would keep returning the pre-compaction API number — masking the freed space and either preventing later compaction or, worse, causing it to fire on already-compacted state.

---

## 5. State That Survives Compaction

```
                    BEFORE COMPACTION                   AFTER COMPACTION
   ┌─────────────────────────────────────────┐    ┌─────────────────────────┐
   │ messages:        [task, ...,  recent]   │    │ messages:                │
   │                  ^^^^^                  │    │   [task,                 │
   │                  preserved              │    │    notice/summary,       │
   │                                         │    │    recent_tail]          │
   │ activeSkillContent: <skill body>        │    │                          │
   │ activePlanPath:     "plan.md"           │    │ Re-injected as new       │
   │ taskStore:          live tasks          │    │ trailing assistant msgs: │
   │ lastSummary:        prior summary       │    │   [Active Skill] ...     │
   │ fileReadIndices:    {path: [...]}       │    │   [Active Plan] ...      │
   │ systemPrompt:       <full prompt>       │    │                          │
   └─────────────────────────────────────────┘    │ Indexes rebuilt          │
                                                  │ Tokens invalidated       │
                                                  └──────────────────────────┘

   Always survives                              Re-injection mechanism
   ────────────────                             ──────────────────────
   • System prompt (rebuilt next iter)          • Skill: append assistant msg if
   • First user-assistant pair (anchor)           skill tag not in last 10 msgs
   • Last N messages (tail)                     • Plan: append "[Active Plan]
   • TaskStore (rendered into prompt §2)          Use read_file at: {path}"
   • MEMORY.md index (in prompt §10)
   • IDE context (in prompt §1, §5, §7)
```

---

## 6. Persistence — How Compaction Hits Disk

```
ContextManager.compact()
        │
        ▼
   mutates messages[]
        │
        ▼
   onHistoryOverwrite(messages, Pair(removedStart, removedEnd))
        │
        ▼
   AgentService wires this to:
   ┌──────────────────────────────────────────────────────┐
   │  MessageStateHandler.overwriteApiConversationHistory │
   │  ───────────────────────────────────────             │
   │  acquire Mutex (per-session)                         │
   │  write to {sessionId}/api_conversation_history.json  │
   │    via temp + atomic rename                          │
   │  release Mutex                                       │
   └──────────────────────────────────────────────────────┘
        │
        ▼
   ui_messages.json — UNCHANGED
   (the chat UI keeps showing pre-compaction history;
    only the LLM-facing `api_conversation_history.json` changes)
```

`★ Insight ─────────────────────────────────────`
- **Two-file persistence.** `api_conversation_history.json` is what the LLM sees; `ui_messages.json` is what the user sees. Compaction only touches the LLM-facing file. The user's chat scrollback stays intact — they see the full original conversation, even though the LLM has been given a compacted version.
- **No "audit trail" of what was compacted.** Once persistence runs, the original LLM-facing message bodies are gone. `ui_messages.json` retains them, but reconstructing what the LLM-context looked like at iteration 5 vs. iteration 50 isn't possible. This is a gap if you want to debug "why did the agent forget X" — the answer might be in a long-overwritten `api_conversation_history.json`.
- **Mutex granularity is per-session, not per-tool.** Two compactions can't race; but a compaction *can* race a tool result append (handled because both use the same mutex inside MessageStateHandler).
`─────────────────────────────────────────────────`

---

## 7. Comparison Matrix — How 9 Agents Handle Context Compaction

| Agent | Trigger | Reduction strategy | Stages | LLM cost | Survives compaction | Persistence | Configurable | Notable bug/issue |
|---|---|---|---|---|---|---|---|---|
| **This plugin** | 85% util · API overflow · timeout exhaustion (opt-in) | Heuristic dedup → truncate → LLM summary | 3 | Stage 3 only (≥95% util) | Skill, plan, tasks, MEMORY, system prompt | Atomic JSON rewrite via callback | `maxInputTokens` only (threshold hardcoded 0.85) | Stage 3 prompt is unparsed string template |
| **Cline** | Per-tier reserves (27K/30K/40K) · 3/4 escalation when `tokens/2 > maxAllowed` | Heuristic dedup → truncate | 2 | **Zero** | First user-asst pair · system prompt · last pair if `lastTwo` | `contextHistoryUpdates` serialized & replayed on reload | None (model-tier-driven) | `findAndPotentiallySaveFileReadContextHistoryUpdates` metadata is fragile |
| **Roo Code** (Cline fork) | % threshold (`MIN=5`/`MAX=100`) · auto + manual | LLM summary (only) — tagged `condenseParent` non-destructive | 1 + folded file context + command-block extraction | Every condense | Original task command blocks · folded files · env details (auto only) · system prompt | Non-destructive — old msgs tagged, hidden from API view | Threshold %, separate summarization model | Models call tools during summary despite "DO NOT call tools" directive |
| **OpenCode** | Tool-output: tokens > `PRUNE_MINIMUM=20K` while protecting `PRUNE_PROTECT=40K` · Session: `tokens ≈ context_limit - output_limit` · manual `/compact` | Heuristic prune + LLM summary w/ structured template | 2+ | Summary call only | `DEFAULT_TAIL_TURNS=2` · `MIN/MAX_PRESERVE_RECENT_TOKENS=2K-8K` · `["skill"]` protected | Persisted as new `summary` message type | `OPENCODE_DISABLE_AUTOCOMPACT` env, protected-tools list | #4416: cached tokens double-counted, premature compaction |
| **Claude Code** (Anthropic CLI) | ~83.5% default · 95% on 1M ctx · `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` 1-100 | Client-side LLM summary OR server-side `compact_20260112` | 1 | Summary call (server-side integrates with prompt cache) | Summary block · system prompt · CLAUDE.md memory | Local conversation kept for `--resume` | Override env, `/compact <focus>`, CLAUDE.md "Compact instructions" block | #11819: compact mid-task "goes off the rails" — summary loses active reasoning |
| **Aider** | `max_chat_history_tokens` per model | Recursive chunked summarization (depth ≤ 3) | 1 (recursive) | Each summary call (`weak_model` allowed) | Tail messages under cap · separate repo map · system prompts | `.aider.chat.history.md` overwritten | `--max-chat-history-tokens`, `--weak-model` | #3607: long-standing request for finer chat-history control |
| **Codex CLI** (OpenAI) | `min(user_config, ctx * 90%)` · reactive on `prompt_too_long` · pause after 3 consecutive failures | LLM summary → head-trim fallback | 2 | Each summary | ~20K tokens recent user msgs · system prompt | `ThreadManager` / `CodexThread` / `Session` | `model_auto_compact_token_limit` (clamped 90%), `/compact` | #11805: 90% clamp silently overrides user threshold; #19842: at exact boundary shows "ran out of room" instead of compacting; #13946: race drops items recorded during compaction window, no max-attempt counter |
| **Continue** (IDE assistant) | Implicit — every request via `compileChatMessages` | Pure truncation top-down (`pruneRawPromptFromTop`) | 1 | **Zero** | Last user/tool message sequence · system message · orphan tool removal | Pruning is request-time, not saved | `contextLength`, `maxTokens` per model | #9231: Anthropic context length miscalc shows 130K@70% when actually 200K |
| **Plandex** (Go) | `max-convo-tokens` cap | LLM summary by dedicated **Summarizer role** (1 of 5 roles) | 1+ (context loader stage separate from convo summary) | Summarizer role call (cheaper model OK) | Sticky **Notes** (user-controlled primacy) · loaded files context (separate from convo) | Plans + per-plan convos persisted | `max-convo-tokens`, per-role model, sticky Notes | #171: role overrides not respected; #53: high token usage 429s |

### 7.1 Field synthesis

**Common patterns (table stakes):**
- System prompt always survives (9/9).
- Some recency preservation (9/9 — last pair, last 2 turns, last user/tool sequence, etc.).
- Token-based triggers (9/9 — never message-count).
- Manual override / `/compact` available (8/9 — Continue is the exception, since it does pure truncation).

**Outliers:**
- **Cline + Continue**: only agents with **zero LLM-summarization**. Cheapest, most deterministic, can't loop.
- **OpenCode**: structured `SUMMARY_TEMPLATE` (Goal/Constraints/Progress/Decisions/Next Steps/Critical Context) is the most opinionated.
- **Claude Code**: server-side `compact_20260112` is unique — the API itself emits a `compaction` block that auto-drops prior messages on next request, integrating with prompt caching.
- **Codex**: reactive-on-error compaction with 3-failure circuit breaker is unique.
- **Plandex**: Summarizer-as-role is unique — every other agent does inline calls.
- **Aider**: depth-bounded recursive chunked summarization is unique algorithmically.

**Where this plugin fits:**

```
              CHEAP/DETERMINISTIC ◄──────────────────────► EXPENSIVE/COHERENT
              ◄────────────────────────────────────────────────────────────►
              │                                                            │
   Continue   Cline                        ★ THIS PLUGIN ★          Roo Code
   (truncate (dedup +                      (dedup +                  (LLM only,
    only)     truncate)                     truncate +                non-destructive)
                                            LLM summary fallback)
                                                            │
                                                            ▼
                                         Aider           Plandex      Codex      Claude
                                       (recursive)    (role-based)  (sum→trim)   (server)
```

We sit closest to **OpenCode** in design space, but with two notable differences:
1. Our Stage 3 summary prompt is a string template (no schema validation); OpenCode uses a structured Markdown template.
2. We don't have OpenCode's `DEFAULT_TAIL_TURNS=2` explicit recency-preservation block — Stage 2 truncation handles it implicitly via the "always keep last N" math, but it's not as crisp.

---

## 8. Gaps — Where We Could Improve (Ranked by Impact)

### 8.1 High-impact gaps

**[G1] No semantic dedup beyond `read_file`.**
Stage 1 only catches the 3 file-read tools (`read_file`, `create_file`, `edit_file`). Identical results from `search_code`, `find_definition`, `find_references`, `db_schema`, `glob_files`, etc. accumulate as duplicates. A hash-based dedup keyed on `(toolName, normalizedArgs)` would extend dedup to ~30 tools. **OpenCode does this** for any deterministic tool.

**[G2] Stage 3 prompt produces unparsed string output.**
The structured-format request (`TASK / FILES / DONE / ERRORS / PENDING`) is "trust the LLM" — no validation, no fail-loud if a section is missing. Compare to OpenCode's `SUMMARY_TEMPLATE` with explicit `## Critical Context` instructions and "every section, even when empty" rule. A schema-validated tool-call would let us reject malformed summaries and re-prompt.

**[G3] No "summaries-of-summaries" guard.**
Long sessions accumulate summary chaining (each Stage 3 includes `lastSummary` in its prompt). Codex's Rust rewrite explicitly fixed this by using a fresh template each pass. Our chaining is intentional (continuity) — but if the chain grows pathologically (10+ chain depth), the early summary information dilutes. No depth cap.

**[G4] No reactive `prompt_too_long` retry counter.**
Codex pauses auto-compaction after 3 consecutive failures (#13946 root cause: no max-attempt counter → infinite loop on poorly-shrinking summary). We have `MAX_CONTEXT_OVERFLOW_RETRIES = 2` which is a counter, but it's per-iteration not per-session. A session-wide circuit breaker that disables compaction after N consecutive overflow retries that didn't shrink the prompt would be safer.

### 8.2 Medium-impact gaps

**[G5] `compactionThreshold` is hardcoded.**
Roo lets the user pick 5–100%. Claude Code has `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE`. We hardcode 0.85 in the constructor. Adding `agentSettings.compactionThreshold` would make this user-tunable for advanced workflows (e.g., users on fast Sonnet can compact later; users on Opus might want earlier).

**[G6] No "preserve protected tools" concept.**
OpenCode has `PRUNE_PROTECT = 40K` and a `["skill"]` allowlist that's exempt from output pruning. Our active-skill content survives via re-injection but the original tool-result message containing `use_skill` output isn't tagged-as-protected anywhere — if the active skill is on a skill-load message that's mid-conversation, truncation can drop it. (We re-inject it at the tail, so functionality survives — but it's belt-and-suspenders, not robust by design.)

**[G7] No cost-aware Stage 3 trigger.**
Stage 3 fires whenever `util > 95%` regardless of whether the conversation is mostly tool noise (where summarization wastes a call) or substantive turns (where it preserves value). A heuristic check (`if tool_results_chars / total_chars > 0.7, prefer aggressive truncation over summarization`) would be a cheap win.

**[G8] No audit trail of pre-compaction history.**
After `onHistoryOverwrite`, the old message bodies are gone from the LLM-facing file. `ui_messages.json` keeps them, but reconstructing "what the LLM saw at iteration N" isn't possible. Roo's `condenseParent` tag pattern (non-destructive) would solve this — tag instead of delete, hide from API view but keep on disk.

### 8.3 Low-impact polish

**[G9] No model-tier-aware reserve buffer.**
Cline's `context-window-utils.ts` hardcodes per-tier reserves (64K→27K, 128K→30K, 200K→40K). We use a single `maxInputTokens = 190K`. On Sonnet's 200K we're fine, but on a hypothetical 1M-context model the same 85% threshold means compacting at 850K — possibly too late if the next response wants 32K output. Reserve should scale with output tokens, not input.

**[G10] Stage 2 `LAST_TWO` and `NONE` strategies are coded but unreachable.**
The strategy enum has 4 values but only `HALF` and `QUARTER` are selected by the algorithm. The other two are presumably for the not-yet-wired manual "compact aggressively" UI button.

**[G11] No PreCompact hook telemetry.**
The PRE_COMPACT hook fires with `{utilizationPercent, messageCount}` data. Adding `lastPromptTokens, fileReadIndices.size, toolDefinitionTokens` would let users write hooks that decide based on richer context (e.g., "skip compaction if we just re-read 5 files" — a different signal than just %).

---

## 9. Visual Comparison — Algorithmic Behavior

```
──────────────────────────────────────────────────────────────────────────
                       BEHAVIOR ON LONG SESSIONS
──────────────────────────────────────────────────────────────────────────

  Continue       Cline          THIS PLUGIN       OpenCode      Claude Code
  ────────       ─────          ───────────       ────────      ───────────
   ▲              ▲              ▲                 ▲             ▲
   │              │              │                 │             │
context        context         context           context       context
   │              │              │                 │             │
   ╳ truncate     ╳ dedup        ╳ dedup           ╳ prune        ╳ summarize
   │              │              │                 │             ╳ (server-side
   │              ╳ truncate     ╳ truncate        ╳ summarize       cache-aware)
   │              │              │                 ╳ replay       │
   │              │              ╳ summarize       │              │
   │              │              │                 │             │
   ▼              ▼              ▼                 ▼             ▼
NO LLM        NO LLM        LLM (95%+ only)    LLM (every)    LLM (every)
  cost           cost            cost              cost           cost
```

```
──────────────────────────────────────────────────────────────────────────
              PROTECTION OF "OLDEST IMPORTANT MESSAGE"
──────────────────────────────────────────────────────────────────────────

Continue   :  any message can be pruned (oldest first)
              ↓ no anchor
              "long-tail context loss"

Cline      :  first user-asst pair PROTECTED
This plugin:  first user-asst pair PROTECTED  ◄── matches Cline
OpenCode   :  first turn + 2 tail turns PROTECTED
Codex      :  ~20K recent user msgs PROTECTED
Claude Code:  CLAUDE.md + summary always PROTECTED
Aider      :  tail-fits-in-cap PROTECTED
Roo Code   :  full original task command-blocks PROTECTED (tagged)
Plandex    :  sticky NOTES (user-controlled) PROTECTED
```

```
──────────────────────────────────────────────────────────────────────────
                    COST AT 100 ITERATIONS
                (assumes 1 compaction every ~10 iter)
──────────────────────────────────────────────────────────────────────────

Continue   : 0 LLM calls for compaction
Cline      : 0 LLM calls for compaction
THIS PLUGIN: 0-2 LLM calls for compaction (Stage 3 rare in practice)
OpenCode   : ~10 LLM calls (every compaction summarizes)
Aider      : ~10-30 LLM calls (recursion can multiply)
Roo Code   : ~10 LLM calls
Codex      : ~10 LLM calls
Claude Code: ~5 LLM calls (cache hit reduces summary cost)
Plandex    : ~10 LLM calls (cheaper summarizer model)
```

---

## 10. Recommended Reading Order Before Touching Compaction

1. **`ContextManager.kt`** lines 614-685 — the `compact()` orchestration.
2. **`ContextManager.kt`** lines 384-424 — Stage 1 dedup.
3. **`ContextManager.kt`** lines 440-520 — Stage 2 truncation (Cline's algorithm).
4. **`ContextManager.kt`** lines 534-586 — Stage 3 LLM summary (our addition).
5. **`AgentLoop.kt`** lines 575-770 + 880-910 — the 3 trigger sites.
6. **`MessageStateHandler.overwriteApiConversationHistory`** — the persistence target.
7. **Cline upstream**: `src/core/context/context-management/ContextManager.ts` — verify our dedup algorithm matches current Cline (we may have drifted from `main`).
8. **OpenCode**: `packages/opencode/src/session/compaction.ts` — for the summary template + replay pattern.
9. **Codex bug #13946** — required reading for race conditions.
10. **Claude Code**: `compact_20260112` API beta docs — for understanding where the field is going.

---

## 11. Tests That Pin Current Behavior

| Test | LOC | What it pins |
|---|---|---|
| `ContextManagerTest.kt` | 1151 | Message ordering, token tracking, all 3 stages, hook dispatch, re-injection, nudge pruning, checkpoint restore |
| `ContextManagerTaskStoreTest.kt` | 2.3K | TaskStore wiring, markdown rendering, mutation propagation |
| `AgentLoopTest.kt` | — | Trigger-site integration (line 583, 753, 897 paths) |
| `AgentLoopEmptyResponsePersistenceTest.kt` | — | Empty assistant pruning |

Any meaningful design change should grow these test files, not replace them — Cline-port snapshot tests assume the algorithm shape.

---

## Sources

**Internal:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/MessageStateHandler.kt`
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/ContextManagerTest.kt`

**External:**
- Cline: https://github.com/cline/cline/blob/main/src/core/context/context-management/ContextManager.ts
- Cline: https://github.com/cline/cline/blob/main/src/core/context/context-management/context-window-utils.ts
- Roo Code: https://github.com/RooCodeInc/Roo-Code/tree/main/src/core/condense
- OpenCode: https://github.com/sst/opencode/blob/dev/packages/opencode/src/session/compaction.ts
- OpenCode bug #4416: https://github.com/sst/opencode/issues/4416
- Claude Code compaction docs: https://platform.claude.com/docs/en/build-with-claude/compaction
- Claude Code costs: https://code.claude.com/docs/en/costs
- Claude Code issue #11819 (compact mid-task derails): https://github.com/anthropics/claude-code/issues/11819
- Aider: https://github.com/Aider-AI/aider/blob/main/aider/history.py
- Aider feature request #3607: https://github.com/Aider-AI/aider/issues/3607
- Codex: https://github.com/openai/codex/blob/main/codex-rs/core/src/codex/compact.rs
- Codex #11805 (90% clamp regression): https://github.com/openai/codex/issues/11805
- Codex #19842 (boundary "ran out of room"): https://github.com/openai/codex/issues/19842
- Codex #13946 (compaction race condition): https://github.com/openai/codex/issues/13946
- Continue: https://github.com/continuedev/continue/blob/main/core/llm/countTokens.ts
- Continue #9231 (Anthropic context miscalc): https://github.com/continuedev/continue/issues/9231
- Plandex docs: https://docs.plandex.ai/core-concepts/context-management/
- Comparative gist: https://gist.github.com/badlogic/cd2ef65b0697c4dbe2d13fbecb0a0a5f
- Justin3go analysis: https://justin3go.com/en/posts/2026/04/09-context-compaction-in-codex-claude-code-and-opencode
