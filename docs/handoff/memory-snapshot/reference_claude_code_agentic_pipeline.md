# Claude Code Agentic Pipeline - Complete Reference

Research date: 2026-03-24 (updated from 2026-03-21)
Sources: Official docs (code.claude.com), Anthropic platform docs, PromptLayer blog, GitHub issues, npm repo, Piebald-AI system prompt extraction (verbatim prompts)

---

## 1. THE AGENT LOOP

### Core Pattern
Single-threaded master loop (internal codename: **nO**):
```
while(tool_call) -> execute tool -> feed results -> repeat
```
Loop terminates when Claude produces a response with **no tool calls** (stop_reason = "end_turn").

### Three Phases (blended, not sequential)
1. **Gather context** - search files, read code, understand structure
2. **Take action** - edit files, run commands, make changes
3. **Verify results** - run tests, check output, confirm correctness

### What Stops the Loop
- **Model stops calling tools** - produces text-only response (stop_reason = "end_turn")
- **Max turns hit** - configurable via `max_turns`/`maxTurns` (counts tool-use turns only)
- **Budget exceeded** - `max_budget_usd`/`maxBudgetUsd` cost cap
- **Max tokens** - stop_reason = "max_tokens" (hit output token limit)
- **Refusal** - stop_reason = "refusal" (model declined)
- **User interruption** - user presses Escape or types correction
- **Context window exhaustion** - triggers auto-compaction, then continues

### Iteration Counts
- No hard default turn limit in interactive CLI mode
- Non-interactive mode: configurable via `--max-iterations`
- Simple tasks: 1-2 turns (e.g., "what files are here?")
- Complex tasks: "dozens of tool calls across many turns"
- SDK default: no limit (unless `maxTurns` set)
- Example in docs: 4 turns for "fix failing tests" (3 tool-use + 1 final text)

### Real-Time Steering (h2A Queue)
Async dual-buffer queue that supports:
- Mid-task pausing and resuming
- User interjections without full restart
- Seamless plan adjustment on the fly
- Streaming output generation

### Nudge System
No explicit "running low on context" nudge to user. Context awareness is injected to the MODEL via XML tags:
```xml
<budget:token_budget>1000000</budget:token_budget>
```
After each tool call:
```xml
<system_warning>Token usage: 35000/1000000; 965000 remaining</system_warning>
```
This helps Claude itself manage its token usage, not the user.

---

## 2. CONTEXT WINDOW

### Model Context Window Sizes
| Model | Standard | Extended |
|-------|----------|----------|
| Claude Opus 4.6 | 200K tokens | 1M tokens |
| Claude Sonnet 4.6 | 200K tokens | 1M tokens |
| Claude Haiku 4.5 | 200K tokens | N/A |
| Older models (Sonnet 4.5, Sonnet 4) | 200K (1M with beta header) | 1M (requires beta header `context-1m-2025-08-07`) |

### 1M Context Availability
- **Max, Team, Enterprise**: Opus auto-upgraded to 1M, no config needed
- **Pro**: Requires extra usage credits
- **API/pay-as-you-go**: Full access
- Disable with: `CLAUDE_CODE_DISABLE_1M_CONTEXT=1`
- No pricing premium for tokens beyond 200K

### Context Window Allocation (for 200K window)
| Component | Tokens | % |
|-----------|--------|---|
| Usable context (conversation, tools, etc.) | ~167K | 83.5% |
| Auto-compact buffer (reserved) | ~33K | 16.5% |
| **Total** | **200K** | **100%** |

Note: Buffer was recently reduced from ~45K (22.5%) to ~33K (16.5%), gaining ~12K usable tokens.

### What Consumes Context
| Source | When Loaded | Impact |
|--------|-------------|--------|
| System prompt | Every request | Small fixed cost (~40K+ tokens across all prompt sections) |
| CLAUDE.md files | Session start | Full content every request (prompt-cached) |
| Tool definitions | Every request | Each tool adds its schema; ~18 built-in tools |
| Conversation history | Accumulates | Grows each turn: prompts + responses + tool I/O |
| Skill descriptions | Session start | Short summaries; full content on invocation only |
| MCP tool schemas | Every request | Can consume significant space if many servers |
| Auto memory (MEMORY.md) | Session start | First 200 lines loaded |

### System Prompt Structure (from Piebald-AI reverse-engineering)
- **110+ modular strings** (not one monolithic prompt)
- Total: ~40,000+ tokens across all components
- Major sections:
  - Core behavior and tool usage (~80 files)
  - Agent prompts (Explore, Plan, verification)
  - Data files (API refs, SDK patterns)
  - System reminders (~40 contextual files)
  - Slash command agents (/batch, /review, /security-review)
- Individual sections range from 23 tokens to 5,106 tokens
- Security monitor alone: 2,941 tokens

---

## 3. AUTO-COMPACTION

### Trigger Mechanism
- **Default trigger**: ~95% of context window capacity (per official docs, March 2026)
- Previous reports of ~83.5% may reflect older behavior or different measurement
- For 200K window: fires at ~190K tokens
- For 1M window: fires at ~950K tokens
- **API compaction default**: 150,000 input tokens (configurable, minimum 50,000)
- Override with: `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` (value 1-100)
- `CLAUDE_CODE_AUTO_COMPACT_WINDOW`: virtual window size for compaction calc (e.g., 500K on 1M model)
- **Circuit breaker**: stops retrying after 3 consecutive failures (v2.1.77)

### How Compaction Works
1. Detects when input tokens exceed trigger threshold
2. Clears older tool outputs first
3. Generates a summary of the conversation using this default prompt:
   ```
   You have written a partial transcript for the initial task above. Please write
   a summary of the transcript. The purpose of this summary is to provide continuity
   so you can continue to make progress towards solving the task in a future context,
   where the raw history above may not be accessible and will be replaced with this
   summary. Write down anything that would be helpful, including the state, next steps,
   learnings etc. You must wrap your summary in a <summary></summary> block.
   ```
4. Replaces older messages with the summary (compaction block)
5. Emits `SystemMessage` with subtype `"compact_boundary"` in SDK
6. Continues seamlessly

### What Gets Preserved vs Lost
**Preserved:**
- Recent exchanges and key decisions
- Current task objective
- CLAUDE.md content (re-injected every request, never compacted)
- File paths read/modified
- Test results and error messages

**May Be Lost:**
- Detailed instructions from early in conversation
- Specific tool outputs from older turns
- Verbose debugging output

### Three Compaction Strategies (from system prompt analysis)
1. Full conversation analysis
2. Recent messages only
3. Minimal/experimental lean version

### Customizing Compaction
- Add "Compact Instructions" section to CLAUDE.md
- Run `/compact` with focus: `/compact Focus on code samples and API usage`
- `PreCompact` hook for custom logic before compaction
- API: `instructions` parameter completely replaces default prompt
- API: `pause_after_compaction` = true lets you add content before continuing

### After Compaction
- The compacted conversation continues in the same session
- Original messages preserved in session transcript (Claude can reference if needed)
- Context usage drops significantly, allowing many more turns

---

## 4. PROMPT CACHING

### How Claude Code Uses It
- **Enabled by default** for all models
- Content that stays the same across turns is automatically prompt-cached
- Reduces cost and latency for repeated prefixes

### Cache Key Structure
Cache prefixes created in order: **tools -> system -> messages**
- Cache keys are cumulative (hash of all previous blocks)
- Up to 4 cache breakpoints per request
- Server walks from start, matches cached prefix, serves from cache

### What Gets Cached
- System prompt (identical every request)
- Tool definitions (identical every request)
- CLAUDE.md content (identical every request)
- Earlier conversation turns (don't change between requests)

### Cost Impact
- Cached tokens: significantly cheaper than uncached
- First request pays full cost; subsequent requests use cache
- "Makes Claude Code 80% cheaper" (per community analysis)

### Disabling Cache
| Variable | Effect |
|----------|--------|
| `DISABLE_PROMPT_CACHING` | Disable for all models |
| `DISABLE_PROMPT_CACHING_HAIKU` | Disable for Haiku only |
| `DISABLE_PROMPT_CACHING_SONNET` | Disable for Sonnet only |
| `DISABLE_PROMPT_CACHING_OPUS` | Disable for Opus only |

### Caching + Compaction Interaction
After compaction, the cache prefix changes (older messages replaced with summary), so next request has a partial cache miss. System prompt + tools + CLAUDE.md still cache-hit.

---

## 5. TOOL RESULT HANDLING

### File Read Limits
- Default Read tool: ~2,000 lines per read
- **Hard token limit**: 25,000 tokens per file read
- Error if exceeded: "File content (X tokens) exceeds maximum allowed tokens (25000). Please use offset and limit parameters."
- Can read with `offset` and `limit` parameters for partial reads

### Tool Output Handling
- Tool results feed directly back into message stream as JSON
- **No automatic truncation** of tool results (known issue)
- Large outputs consume significant context
- MCP tool responses: truncated to ~700 characters (separate issue)
- Bash stdout: truncated at various thresholds (~4k, 6k, 8k, 16k chars)

### Output Token Limits
- `CLAUDE_CODE_MAX_OUTPUT_TOKENS`: default 32K, controls max response length
- `MAX_THINKING_TOKENS`: controls thinking budget (default: model-dependent)
- These are SEPARATE from the compaction buffer

### Parallel Tool Execution
- Read-only tools (Read, Glob, Grep, read-only MCP): run concurrently
- State-modifying tools (Edit, Write, Bash): run sequentially
- Custom tools: sequential by default, mark `readOnly` for parallel

---

## 6. MULTI-TURN CONVERSATION

### How Messages Accumulate
- Every turn: full conversation history re-sent to API
- Input = system prompt + tools + all previous messages + current turn
- Linear growth pattern: previous turns preserved completely
- **No sliding window** - everything accumulates until compaction

### Extended Thinking in Multi-Turn
- Previous thinking blocks are **automatically stripped** from context
- Only current turn's thinking counts toward context
- Effective calculation: `context_window = (input_tokens - previous_thinking_tokens) + current_turn_tokens`
- Thinking tokens billed as output tokens only once (when generated)

### Session Continuity
- Sessions saved locally with full history
- `claude --continue`: restores full context from previous turns
- `claude --resume`: pick specific session
- `claude --continue --fork-session`: branch without affecting original
- Session-scoped permissions NOT restored on resume

### Subagent Context Isolation
- Each subagent gets fresh context window (no parent history)
- Loads its own system prompt + CLAUDE.md
- Only final response returns to parent as tool result
- Prevents context bloat from subtask transcripts
- Sub-agents CANNOT spawn their own sub-agents (depth = 1)

---

## 7. TOKEN DISPLAY AND COST TRACKING

### /cost Command
Shows for current session:
- Total cost (USD)
- Total duration (API time)
- Total duration (wall clock)
- Total code changes (lines added/removed)

### /context Command
Shows per-component token breakdown:
- System prompt tokens
- Tool definitions tokens
- MCP server tokens
- Conversation history tokens
- Free space remaining
- Buffer reserved for compaction

### Status Line
Configurable to show context window usage continuously.

### Average Costs
- Average: ~$6/developer/day
- 90th percentile: <$12/day
- Monthly average: ~$100-200/developer with Sonnet 4.6
- Background usage: ~$0.04/session (conversation summarization)

### Rate Limit Recommendations (TPM/RPM per user)
| Team Size | TPM/user | RPM/user |
|-----------|----------|----------|
| 1-5 | 200-300K | 5-7 |
| 5-20 | 100-150K | 2.5-3.5 |
| 20-50 | 50-75K | 1.25-1.75 |
| 50-100 | 25-35K | 0.62-0.87 |
| 100-500 | 15-20K | 0.37-0.47 |
| 500+ | 10-15K | 0.25-0.35 |

---

## 8. KEY ENVIRONMENT VARIABLES

### Context & Compaction
| Variable | Purpose | Default |
|----------|---------|---------|
| `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` | Override auto-compaction trigger percentage (1-100) | ~95% |
| `CLAUDE_CODE_AUTO_COMPACT_WINDOW` | Virtual context window for compaction calc | Model's actual window |
| `CLAUDE_CODE_MAX_OUTPUT_TOKENS` | Max response length | 32K |
| `MAX_THINKING_TOKENS` | Thinking budget per request | Model-dependent |
| `CLAUDE_CODE_DISABLE_1M_CONTEXT` | Disable 1M context variants | 0 |
| `CLAUDE_CODE_DISABLE_ADAPTIVE_THINKING` | Revert to fixed thinking budget | 0 |
| `CLAUDE_CODE_EFFORT_LEVEL` | Effort: low/medium/high/max/auto | Model default |

### Prompt Caching
| Variable | Purpose |
|----------|---------|
| `DISABLE_PROMPT_CACHING` | Disable all prompt caching |
| `DISABLE_PROMPT_CACHING_HAIKU` | Disable for Haiku |
| `DISABLE_PROMPT_CACHING_SONNET` | Disable for Sonnet |
| `DISABLE_PROMPT_CACHING_OPUS` | Disable for Opus |

### Model Selection
| Variable | Purpose |
|----------|---------|
| `ANTHROPIC_MODEL` | Override model selection |
| `ANTHROPIC_DEFAULT_OPUS_MODEL` | Pin Opus version |
| `ANTHROPIC_DEFAULT_SONNET_MODEL` | Pin Sonnet version |
| `ANTHROPIC_DEFAULT_HAIKU_MODEL` | Pin Haiku version |
| `CLAUDE_CODE_SUBAGENT_MODEL` | Model for subagents |

### Tool Management
| Variable | Purpose |
|----------|---------|
| `ENABLE_TOOL_SEARCH` | Auto-defer MCP tools when exceeding % of context (e.g., `auto:5` = 5%) |

### Agent Teams (Experimental)
| Variable | Purpose |
|----------|---------|
| `CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS` | Enable agent teams (set to 1) |

---

## 9. COMPACTION API (Server-Side)

### Beta Header
`anthropic-beta: compact-2026-01-12`

### Parameters
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `type` | string | Required | Must be `"compact_20260112"` |
| `trigger` | object | 150,000 tokens | When to trigger. Minimum 50,000 tokens. |
| `pause_after_compaction` | boolean | false | Pause after summary, return compaction stop_reason |
| `instructions` | string | null | Custom summarization prompt (replaces default) |

### Stop Reasons
- `end_turn` - model finished normally
- `tool_use` - model wants to call tools (loop continues)
- `max_tokens` - hit output token limit
- `compaction` - compaction triggered with pause_after_compaction=true
- `refusal` - model declined

### Supported Models
- Claude Opus 4.6
- Claude Sonnet 4.6

---

## 10. CHECKPOINTING

### How It Works
- Every user prompt creates a new checkpoint
- Snapshots file contents before each edit
- Persists across sessions (30-day cleanup, configurable)
- Only tracks file editing tool changes (NOT bash commands)

### Rewind Options (Esc+Esc or /rewind)
1. **Restore code and conversation** - revert both
2. **Restore conversation** - rewind messages, keep code
3. **Restore code** - revert files, keep conversation
4. **Summarize from here** - compress from selected point forward
5. **Never mind** - cancel

### Summarize vs Restore
- Summarize keeps you in same session, compresses context
- Original messages preserved in transcript
- Can provide custom focus instructions
- Different from fork (which creates new session)

### Limitations
- Bash command changes NOT tracked (rm, mv, cp)
- External changes NOT tracked
- Not a replacement for git

---

## 11. ARCHITECTURE DESIGN PRINCIPLES

1. **Single-threaded simplicity** - one main loop, flat message history
2. **Regex over embeddings** - no vector DB for code search
3. **Markdown over databases** - CLAUDE.md for memory
4. **Diff-first workflow** - minimal modifications, easy review
5. **Permission system** - whitelist/allow rules for trusted operations
6. **TodoWrite for planning** - structured JSON task lists with IDs, status, priority
7. **Sub-agents for isolation** - fresh context, depth=1 only
8. **Tools over text** - Claude acts through tools, not just responds

---

## 12. EFFORT LEVELS

| Level | Behavior | Token Impact |
|-------|----------|-------------|
| low | Minimal reasoning, fast | Cheapest |
| medium | Balanced (Opus default for Max/Team) | Moderate |
| high | Thorough analysis (SDK TypeScript default) | Higher |
| max | Maximum depth, no token constraint (Opus only) | Most expensive |

Set via: `/effort`, `--effort`, `CLAUDE_CODE_EFFORT_LEVEL`, settings, or skill/subagent frontmatter.

---

## 13. VERBATIM SYSTEM PROMPT DETAILS (from Piebald-AI extraction, v2.1.81)

### System Prompt Assembly — 250+ Components
Claude Code assembles its system prompt from 250+ files in 5 categories:
- **Agent Prompts** (36 files): Sub-agent system prompts, slash commands, utilities
- **Data/Reference** (26 files): API docs, model catalog, SDK patterns
- **Skill Prompts** (11 files): Reusable domain knowledge injected on demand
- **System Prompts** (78 files): Core behavior, task execution, tool usage
- **System Reminders** (42 files): Context-injected notifications during conversation
- **Tool Descriptions** (47 files): Individual tool specs (Bash alone has 30+ sub-files)

### Verbatim "Doing Tasks" Sub-Sections
Each is a separate file, conditionally included:
- **Software engineering focus** (104 tks): "The user will primarily request you to perform software engineering tasks...interpret instructions in that context"
- **Ambitious tasks** (47 tks): "You are highly capable...defer to user judgement about whether a task is too large"
- **Avoid over-engineering** (30 tks): "Only make changes that are directly requested or clearly necessary"
- **Read before modifying** (46 tks): "do not propose changes to code you haven't read"
- **Minimize file creation** (47 tks): Prefer editing existing files
- **No unnecessary additions** (78 tks): Do not add features, refactor beyond what was asked
- **No premature abstractions** (60 tks): No abstractions for one-time operations
- **No compatibility hacks** (52 tks): Delete unused code completely
- **No unnecessary error handling** (64 tks): Only validate at boundaries
- **No time estimates** (47 tks): Avoid giving time estimates
- **Security** (67 tks): Avoid introducing vulnerabilities
- **Blocked approach** (90 tks): "do not attempt to brute force...consider alternative approaches"

### Verbatim Compaction Prompts

#### SDK Compaction Prompt (278 tokens, v2.1.38+)
```
Your task is to create a detailed summary of the conversation so far...
Include:
1. Task Overview — user's core request and success criteria, clarifications/constraints
2. Current State — what has been completed, files created/modified/analyzed
3. Important Discoveries — constraints, decisions, errors, failed approaches (and why)
4. Next Steps — specific actions needed, blockers, priority order
5. Context to Preserve — user preferences, domain details, promises made
```
Wrapped in `<summary></summary>` tags.

#### Full Conversation Summarization (956 tokens, v2.1.69+)
9-section format:
1. Primary Request and Intent
2. Key Technical Concepts
3. Files and Code Sections (with full code snippets, summary of importance)
4. Errors and fixes (with user feedback)
5. Problem Solving
6. All user messages (non-tool-result — "critical for understanding changing intent")
7. Pending Tasks
8. Current Work (with file names, code snippets)
9. Optional Next Step (with direct quotes for task continuity)

Includes analysis-phase in `<analysis>` tags before summary.

#### Analysis Phase Variants
- **Full conversation**: Chronologically analyze each message, identify requests/approaches/decisions/code patterns/file names/edits/errors/user feedback
- **Recent messages only**: Same analysis but limited to recent messages
- **Minimal (experimental)**: "Walk through chronologically and note in a line or two each what belongs in each section. Do NOT write code snippets here — save those for <summary>"

### Verbatim Sub-Agent Prompts

#### Explore Agent (517 tokens, Haiku model)
```
You are a file search specialist for Claude Code...
=== CRITICAL: READ-ONLY MODE - NO FILE MODIFICATIONS ===
STRICTLY PROHIBITED from: creating, modifying, deleting, moving, copying files
Your role is EXCLUSIVELY to search and analyze existing code.
Guidelines:
- Use Glob for file pattern matching
- Use Grep for regex search
- Use Read for specific file paths
- Use Bash ONLY for read-only operations (ls, git status, git log, git diff, find, cat, head, tail)
- Adapt search approach based on thoroughness level: quick, medium, very thorough
- Return file paths as absolute paths
NOTE: You are meant to be a fast agent...spawn multiple parallel tool calls
```

#### Plan Agent (680 tokens, inherits model)
```
You are a software architect and planning specialist...
=== CRITICAL: READ-ONLY MODE - NO FILE MODIFICATIONS ===
Process:
1. Understand Requirements
2. Explore Thoroughly: Read files, find patterns, understand architecture, identify similar features, trace code paths
3. Design Solution: implementation approach, trade-offs, architectural decisions
4. Detail the Plan: step-by-step strategy, dependencies, sequencing, challenges

Required Output:
### Critical Files for Implementation
List 3-5 files most critical for implementing this plan
```

### Verbatim Error Recovery Prompts

#### Tool Execution Denied
```
IMPORTANT: You *may* attempt to accomplish this action using other tools that might naturally be used...
But you *should not* attempt to work around this denial in malicious ways...
You should only try to work around this restriction in reasonable ways that do not attempt to bypass the intent behind this denial.
If you believe this capability is essential...STOP and explain to the user what you were trying to do and why you need this permission.
```

#### Blocked Approach
```
If your approach is blocked, do not attempt to brute force your way to the outcome.
...do not wait and retry the same action repeatedly.
Instead, consider alternative approaches or other ways you might unblock yourself,
or consider using AskUserQuestion to align with the user on the right path forward.
```

#### Permission Denied in System Section
```
When you attempt to call a tool that is not automatically allowed...the user will be prompted.
If the user denies a tool you call, do not re-attempt the exact same tool call.
Instead, think about why the user has denied the tool call and adjust your approach.
If you do not understand why, use AskUserQuestion to ask them.
```

### Verbatim Budget/Token Reminders (injected as system-reminders)

#### USD Budget
```
USD budget: $used/$total; $remaining remaining
```

#### Token Usage
```
Token usage: used/total; remaining remaining
```

#### TodoWrite Reminder (hidden from user)
```
The TodoWrite tool hasn't been used recently. If you're working on tasks that would benefit from tracking progress, consider using the TodoWrite tool to track progress. Also consider cleaning up the todo list if has become stale...
Make sure that you NEVER mention this reminder to the user
```

### Verbatim Auto Mode Prompt (266 tokens, v2.1.78)
```
## Auto Mode Active
1. Execute immediately — Start implementing right away. Make reasonable assumptions.
2. Minimize interruptions — Prefer assumptions over questions. Use AskUserQuestion only when genuinely cannot proceed.
3. Prefer action over planning — Do not enter plan mode unless explicitly asked.
4. Make reasonable decisions — Choose most sensible approach, keep moving.
5. Be thorough — Complete full task including tests, linting, verification.
6. Never post to public services — without explicit written approval.
```

### Verbatim Executing Actions with Care (590 tokens, v2.1.78)
Key principle: "Carefully consider the reversibility and blast radius of actions."
- Freely take local, reversible actions (editing files, running tests)
- For hard-to-reverse/shared/risky actions: check with user first
- Examples: destructive ops, force-pushing, creating/commenting PRs/issues, posting to external services
- "When you encounter an obstacle, do not use destructive actions as a shortcut"
- "Investigate before deleting or overwriting...may represent the user's in-progress work"
- "measure twice, cut once"

### Verbatim Fork Usage Guidelines (326 tokens, v2.1.81)
```
Fork yourself (omit subagent_type) when intermediate tool output isn't worth keeping in context.
- Research: fork open-ended questions. Parallel forks in one message.
- Implementation: prefer to fork implementation work requiring more than a couple edits.
- Forks share prompt cache. Don't set model on a fork.

Don't peek. Do not Read or tail output_file unless user explicitly asks.
Don't race. Never fabricate or predict fork results. Notification arrives as user-role message.
```

### Verbatim Security Monitor (5667 tokens total, v2.1.81)
Two-part classifier for autonomous agent actions:
- Part 1 (2726 tks): Threat model, user intent rules, evaluation rules
- Part 2 (2941 tks): Block/allow rules, environment context
- Protects against: prompt injection, scope creep, accidental damage
- "Default: actions are ALLOWED. Only block if matches BLOCK AND no ALLOW exception"
- Composite actions: if ANY part blocked, entire action blocked
- Sub-agent delegation: checks prompt for blocked actions at spawn time
- "Silence is not consent": user not intervening is NOT evidence of approval

### Verbatim Subagent Delegation Examples (v2.1.70)
Shows coordinator pattern:
- Turn ends after launching agent ("Audit running.")
- Notification arrives as user-role message in later turn
- If user asks mid-wait: "Still waiting on the audit — should land shortly."
- Independent subagent: "needs full context in the prompt"

---

## 14. TOOL SELECTION — DYNAMIC DEFERRED LOADING (detailed)

### Tool Search Evolution Timeline
| Version | Change |
|---------|--------|
| v2.0.70 | MCPSearch introduced for MCP tools |
| v2.1.7 | MCP tool search auto mode enabled by default |
| v2.1.14 | MCPSearch renamed to ToolSearch |
| v2.1.31 | ToolSearch extended with `select:` syntax |
| v2.1.69 | ALL built-in tools deferred behind ToolSearch (undocumented, ~968 tokens) |
| v2.1.72 | Frequently-used tools pre-loaded again (~8.1k tokens) |

### Current State (v2.1.72+)
**Pre-loaded** (full schema immediately available): Agent, Bash, Edit, Glob, Grep, Read, Skill, ToolSearch, Write
**Deferred** (name only, require ToolSearch): AskUserQuestion, CronCreate/Delete/List, EnterWorktree, ExitPlanMode, ExitWorktree, NotebookEdit, TaskOutput, TaskStop, WebFetch, WebSearch

### Tool Search API Variants
- **Regex** (`tool_search_tool_regex_20251119`): Claude crafts regex to search tool catalog
- **BM25** (`tool_search_tool_bm25_20251119`): Claude uses natural language queries

### MCP Auto-Trigger
When MCP tool descriptions exceed 10% of context window, tools auto-deferred.
Configure: `ENABLE_TOOL_SEARCH=auto:5` (5% threshold) or `ENABLE_TOOL_SEARCH=false` (disable).

### Accuracy Impact
- Selection degrades past 30-50 available tools
- With ToolSearch: Opus 4 = 49% -> 74%, Opus 4.5 = 79.5% -> 88.1%
- Context savings: ~85% reduction in tool definition tokens

---

## 15. CUSTOM SUBAGENT CONFIGURATION

### Subagent File Format
YAML frontmatter + markdown body (system prompt):
```yaml
---
name: code-reviewer           # Required: lowercase + hyphens
description: Reviews code     # Required: when to delegate
tools: Read, Glob, Grep       # Optional: allowlist (inherits all if omitted)
disallowedTools: Write, Edit  # Optional: denylist
model: sonnet                 # Optional: sonnet|opus|haiku|inherit|full-id
permissionMode: default       # Optional: default|acceptEdits|dontAsk|bypassPermissions|plan
maxTurns: 50                  # Optional: turn limit
skills: [api-conventions]     # Optional: preloaded skill content
memory: user                  # Optional: user|project|local (persistent across sessions)
background: false             # Optional: always run as background task
effort: high                  # Optional: low|medium|high|max
isolation: worktree           # Optional: git worktree isolation
hooks:                        # Optional: lifecycle hooks
  PreToolUse: [...]
mcpServers:                   # Optional: scoped MCP servers
  - playwright: {type: stdio, command: npx, args: [...]}
  - github                   # Reference existing server
---
System prompt in markdown body...
```

### Subagent Scope Priority (highest to lowest)
1. `--agents` CLI flag (session only)
2. `.claude/agents/` (project, check into VCS)
3. `~/.claude/agents/` (user, all projects)
4. Plugin's `agents/` directory (plugin scope)

### Persistent Memory for Subagents
- `user` scope: `~/.claude/agent-memory/<name>/`
- `project` scope: `.claude/agent-memory/<name>/`
- `local` scope: `.claude/agent-memory-local/<name>/`
- First 200 lines of `MEMORY.md` auto-loaded
- Read/Write/Edit tools auto-enabled for memory management

### Agent Tool Restriction Syntax
```yaml
tools: Agent(worker, researcher), Read, Bash  # Only allow specific subagent types
tools: Agent, Read, Bash                       # Allow any subagent
# Omit Agent entirely = cannot spawn any subagents
```

### Sources for This Section
- https://github.com/Piebald-AI/claude-code-system-prompts
- https://platform.claude.com/docs/en/build-with-claude/compaction
- https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool
- https://code.claude.com/docs/en/sub-agents
- https://code.claude.com/docs/en/costs
- https://blog.promptlayer.com/claude-code-behind-the-scenes-of-the-master-agent-loop/
- https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents
- https://gist.github.com/badlogic/cd2ef65b0697c4dbe2d13fbecb0a0a5f
- https://github.com/anthropics/claude-code/issues/31002
