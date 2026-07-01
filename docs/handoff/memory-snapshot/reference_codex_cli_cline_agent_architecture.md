# Codex CLI & Cline Agent Architecture Research

Research date: 2026-03-24
Sources: github.com/openai/codex (Rust core), github.com/cline/cline (TypeScript)

---

## 1. CODEX CLI (OpenAI)

### 1.1 System Prompt Structure

Located in `codex-rs/core/prompt.md` (base) with model-specific variants (`gpt-5.1-codex-max_prompt.md`, `gpt-5.2-codex_prompt.md`).

**Sections in order:**
1. **Identity & Capabilities** - "You are a coding agent running in the Codex CLI"
2. **AGENTS.md spec** - How to read/obey repo-level agent instructions (scoping, precedence, nesting)
3. **Responsiveness / Preamble messages** - Brief messages before tool calls (8-12 words)
4. **Planning** - `update_plan` tool usage, high/low quality plan examples
5. **Task execution** - Core behavioral rules (fix root cause, minimal changes, no copyright headers, no git commit unless asked)
6. **Validating your work** - Test philosophy (specific to broad), formatting, approval-mode awareness
7. **Ambition vs. precision** - New project = ambitious; existing codebase = surgical
8. **Sharing progress updates** - Concise updates at intervals
9. **Presenting your work / Final answer** - Detailed formatting spec (headers, bullets, monospace, file references, tone)
10. **Tool Guidelines** - Shell (prefer `rg`), `update_plan` semantics, `apply_patch` grammar

**Key design pattern:** The prompt is modular. Model-specific variants override sections. The GPT-5.1/5.2 prompts have additional frontend design rules and review guidelines.

### 1.2 Context Management

**File:** `codex-rs/core/src/context_manager/history.rs`

- `ContextManager` struct holds `Vec<ResponseItem>` ordered oldest-to-newest
- Tracks `TokenUsageInfo` with `last_token_usage.total_tokens`
- **Token estimation:** Uses byte-based heuristic (`APPROX_BYTES_PER_TOKEN = 4`), not a tokenizer
- **Truncation strategies:**
  - `remove_first_item()` - drops oldest item + its corresponding call/output pair
  - `remove_last_item()` - drops newest item
  - `drop_last_n_user_turns(n)` - thread rollback semantics
  - `replace_last_turn_images(placeholder)` - replaces images with text to save tokens
  - Tool output truncation via `TruncationPolicy` (Bytes or Tokens mode) applied at recording time
- **Normalization:** Before sending to model, ensures every call has output and vice versa, strips images when unsupported
- **Image estimation:** Fixed `RESIZED_IMAGE_BYTES_ESTIMATE = 7373` bytes (~1844 tokens), or actual patch-based calculation for `detail: "original"`
- **Reasoning tokens:** Estimates encrypted reasoning content length as `(encoded_len * 3/4) - 650`

**File:** `codex-rs/core/src/truncate.rs`
- `TruncationPolicy::Bytes(usize)` or `TruncationPolicy::Tokens(usize)`
- Preserves prefix and suffix on UTF-8 boundaries
- Prepends "Total output lines: N" when truncating

### 1.3 Tool Presentation

Codex uses **two primary tools** presented to the model:
1. **`shell`** - Execute terminal commands
2. **`apply_patch`** - Edit files using a custom patch format

The `apply_patch` tool has its own instruction doc (`apply_patch_tool_instructions.md`) with a full grammar:
```
Patch := Begin { FileOp } End
FileOp := AddFile | DeleteFile | UpdateFile
Hunk := "@@" [ header ] NEWLINE { HunkLine }
```

Additional tools:
- `update_plan` - Step tracking with status (pending/in_progress/completed)
- MCP tools via external servers
- `spawn_agent` - Sub-agent delegation

### 1.4 Error Handling

- Tool call failures are returned as function output with error text
- `apply_patch` returns failure if patch doesn't apply (model told "don't re-read after success")
- Validation philosophy: "iterate up to 3 times to get formatting right"
- Context window overflow: `remove_first_item()` drops oldest, normalization removes orphan pairs

### 1.5 Sub-Agent Delegation

**File:** `codex-rs/core/src/agent/role.rs`, `control.rs`

Codex has a full hierarchical multi-agent system:

**Built-in roles:**
- `default` - Standard agent
- `explorer` - Fast, read-only codebase questions. "Must be used for specific, well-scoped questions." Can spawn multiple in parallel. "Trust explorer results without additional verification."
- `worker` - Execution/production work. Gets explicit file/module ownership. Told "you are not alone in the codebase, don't revert others' edits."
- `awaiter` (temp removed) - Long-running commands (tests, monitoring)

**Architecture:**
- `AgentControl` - Control plane for multi-agent ops, held by each session
- `AgentRegistry` - Tracks all spawned agents
- Each sub-agent gets its own thread, config, and agent loop
- Roles can override model, reasoning_effort, and have custom config.toml
- Name generation from `agent_names.txt` list
- Fork support: parent conversation history forked to child with message "Treat the next user message as your new task"
- `agent_max_threads` config limits concurrent agents

**Inter-agent communication:**
- `InterAgentCommunication` protocol for structured messages between agents
- Parent can send instructions that appear as "assistant" role but are inter-agent boundaries
- `format_subagent_notification_message` for progress updates

### 1.6 Safety & Sandboxing

**File:** `codex-rs/core/src/safety.rs`, `sandboxing/mod.rs`

**Approval modes (AskForApproval enum):**
- `Never` - Auto-approve everything (no user confirmation)
- `OnFailure` - Auto-approve, ask only on failure
- `OnRequest` - Ask for approval on each action
- `UnlessTrusted` - Ask unless action is in trusted list
- `Granular(config)` - Fine-grained per-action-type approval

**Sandbox types:**
- `MacosSeatbelt` - macOS sandbox-exec profiles
- `LinuxSeccomp` - Linux seccomp-bpf filtering
- `WindowsRestrictedToken` - Windows restricted process tokens
- `None` / `ExternalSandbox` / `DangerFullAccess`

**Safety assessment for patches:**
- Checks if writes are constrained to writable paths
- Even "safe" patches run in sandbox (could be hard links to outside paths)
- Platform-specific sandbox selection based on OS

**Guardian system:**
- `GuardianApprovalRequest` types: Shell, ExecCommand, ApplyPatch, NetworkAccess, McpToolCall
- Network approval with host/port/protocol tracking
- MCP tool annotations: `destructive_hint`, `open_world_hint`, `read_only_hint`

### 1.7 Budget/Limits

- Token estimation via byte heuristic (4 bytes/token)
- No explicit iteration limit in prompt (told to "keep going until resolved")
- `agent_max_threads` for sub-agent concurrency
- `ExecExpiration` for command timeouts
- Tool output truncation policies (bytes or tokens)
- Context managed by dropping oldest items when approaching limit

---

## 2. CLINE (VS Code Agent)

### 2.1 System Prompt Structure

Located in `src/core/prompts/system-prompt/components/` with modular sections:

**Sections (assembled by PromptBuilder):**
1. **Agent Role** - "You are Cline, a highly skilled software engineer with extensive knowledge..."
2. **Capabilities** - File access, search, list_code_definition_names, execute_command, browser_action, MCP servers, web_search/web_fetch
3. **Rules** - ~40 rules covering: CWD constraints, command safety, search patterns, project creation, file editing, auto-approve behavior, replace_in_file formatting
4. **Objective** - 6-step iterative methodology with `<thinking>` tags for tool selection
5. **Tool Use** - Individual tool definitions with parameters
6. **System Info** - OS, shell, CWD, etc.
7. **User Instructions** - Custom instructions from settings + .clinerules files
8. **Feedback** - Response formatting rules
9. **MCP** - Connected MCP server tool/resource descriptions
10. **Skills** - Available skill definitions
11. **Task Progress** - Focus chain checklist (optional)

**Key design pattern:** Component-based with `PromptVariant` overrides per model family. Template engine resolves placeholders like `{{CWD}}`, `{{BROWSER_SUPPORT}}`.

### 2.2 Context Management

**File:** `src/core/context/context-management/ContextManager.ts`

Sophisticated multi-layer context management:

- **Nested map structure:** `{ messageIndex => [EditType, { blockIndex => [[timestamp, updateType, update, metadata], ...] }] }`
- **EditTypes:** UNDEFINED, NO_FILE_READ, READ_FILE_TOOL, ALTER_FILE_TOOL, FILE_MENTION
- **Timestamp-based tracking** for checkpoint support (binary search on truncation)

**Context window management:**
```typescript
// context-window-utils.ts
switch (contextWindow) {
    case 64_000:  maxAllowedSize = contextWindow - 27_000  // deepseek
    case 128_000: maxAllowedSize = contextWindow - 30_000  // most models
    case 200_000: maxAllowedSize = contextWindow - 40_000  // claude
    default:      maxAllowedSize = max(contextWindow - 40_000, contextWindow * 0.8)
}
```

**Auto-condensation (summarize_task):**
- Triggered when context reaches 75% threshold (for next-gen models with auto-condense enabled)
- `summarize_task` tool generates comprehensive 10-section summary:
  1. Primary Request and Intent
  2. Key Technical Concepts
  3. Files and Code Sections (with full code snippets)
  4. Problem Solving
  5. Pending Tasks
  6. Task Evolution (original vs modified task with direct user quotes)
  7. Current Work (precise description with file names)
  8. Next Step (with verbatim conversation quotes)
  9. Required Files (for continuing work)
  10. Task Progress checklist
- After summarization, continuation prompt wraps the summary

**Truncation strategies:**
- `getNextTruncationRange()` with "quarter" mode
- `attemptFileReadOptimizationInMemory()` - optimizes file reads before truncating
- Context window exceeded error detection for 6+ providers (OpenAI, Anthropic, OpenRouter, Cerebras, Bedrock, Vercel)
- Retry with compaction on context overflow

### 2.3 Tool Presentation

Tools defined as `ClineToolSpec` objects with model-family variants:

**Default tools (XML-based in prompt):**
- `execute_command` - CLI commands with explanation requirement
- `read_file` - File reading
- `write_to_file` - Create/overwrite files
- `replace_in_file` - SEARCH/REPLACE blocks for editing
- `list_files` - Directory listing (recursive or top-level)
- `search_files` - Regex search with context
- `list_code_definition_names` - Code structure overview
- `ask_followup_question` - Ask user for clarification
- `attempt_completion` - Present final result
- `browser_action` - Puppeteer browser automation
- `use_subagents` - Parallel research agents
- `summarize_task` - Context condensation
- `use_skill` - Skill activation
- `web_search` / `web_fetch` - Web research

**Native tool calling:** Supported for OpenAI Responses API format. `ClineToolSet.getNativeConverter()` converts specs to provider-specific format.

**Tool validation:** `<thinking>` tag required before tool calls. Model must verify all required params are present.

### 2.4 Error Handling

**File:** `src/core/context/context-management/context-error-handling.ts`

- Provider-specific context window error detection (6 providers with regex patterns)
- On context overflow: compact conversation + retry (up to `MAX_INITIAL_STREAM_ATTEMPTS = 3`)
- Empty assistant response: retry up to `MAX_EMPTY_ASSISTANT_RETRIES = 3` with "no tools used" nudge
- Auth/balance errors: no auto-retry
- Retry with exponential backoff: `INITIAL_STREAM_RETRY_BASE_DELAY_MS = 2000 * 2^(attempt-1)`

### 2.5 Sub-Agent Delegation

**Files:** `src/core/task/tools/subagent/SubagentRunner.ts`, `SubagentBuilder.ts`, `subagent.ts`

**Architecture:**
- `use_subagents` tool: up to 5 parallel subagents per call
- Each subagent gets its own `SubagentRunner` with independent context window + API handler
- Subagents are **read-only research agents** - no file writes, no browser, no MCP, no nested subagents

**Allowed tools for subagents:**
```typescript
SUBAGENT_DEFAULT_ALLOWED_TOOLS = [
    FILE_READ, LIST_FILES, SEARCH, LIST_CODE_DEF, BASH, USE_SKILL, ATTEMPT
]
```

**Subagent system suffix appended to prompt:**
"You are running as a research subagent. Your job is to explore the codebase and gather information..."
- Only readonly commands (ls, grep, git log, git diff, gh)
- Must call attempt_completion when done
- Result goes directly to main agent
- Include "Relevant file paths" section

**Subagent lifecycle:**
1. `SubagentBuilder` creates API handler, resolves allowed tools, builds system prompt
2. `SubagentRunner.run()` executes agent loop with conversation history
3. Tracks per-subagent stats (toolCalls, tokens, cost, contextUsagePercentage)
4. Proactive compaction at 75% context threshold
5. Context window overflow: compact + retry up to 3 attempts
6. Abort support: cancels active API stream + running commands
7. Progress callbacks report stats and latest tool call to parent

**Configurable via `.agents/` directory:**
- Custom model, system prompt, skills, tool allowlist per agent name
- `AgentConfigLoader` reads YAML/JSON configs

### 2.6 Safety

**Approval flow (human-in-the-loop):**
- Every file change requires user approval (unless auto-approve enabled)
- Every command execution requires approval
- `yoloModeToggled` bypasses all approvals
- Auto-approve granular settings: read files, write files, commands, browser, MCP
- Subagents follow "Read project files" auto-approve permission

**Prompt-level safety:**
- Forbidden conversation starters: "Great", "Certainly", "Okay", "Sure"
- Must wait for user response after each tool use
- Cannot `cd` to different directory
- Must not use `~` or `$HOME`
- Command output verification required (don't assume success)
- `--` before positional args to prevent option injection
- `.clinerules/` files for project-specific behavioral constraints

### 2.7 Budget/Limits

- Context window buffers: 27K-40K tokens reserved depending on model
- Auto-condense threshold: 75% of context window
- Subagent limits: max 5 per `use_subagents` call
- Empty response retries: 3
- Initial stream retries: 3 with exponential backoff
- No explicit iteration/turn limit in the agent loop (runs until `attempt_completion`)
- Token tracking per request and cumulative
- Cost calculation per-provider (Anthropic formula)

---

## Comparison Summary

| Aspect | Codex CLI | Cline |
|--------|-----------|-------|
| Language | Rust | TypeScript |
| Prompt size | ~3000 words base | ~4000 words assembled |
| Prompt modularity | Model-specific .md files | Component-based with TemplateEngine |
| Primary tools | 2 (shell + apply_patch) | 14+ (file, search, command, browser, etc.) |
| Tool format | Function calling | XML tags in prompt (or native) |
| Context strategy | Drop oldest items | Summarize + truncate quarters |
| Sub-agents | Full hierarchical (explorer/worker/awaiter) | Parallel read-only research (up to 5) |
| Sub-agent writes | Workers can write (owned files) | Cannot write |
| Sandbox | OS-level (Seatbelt/Seccomp/RestrictedToken) | VS Code extension sandbox + approval |
| Approval modes | 5 modes (Never to Granular) | Toggle per-action-type + YOLO mode |
| Error recovery | Retry + truncate oldest | Retry + compact + summarize |
| Iteration limit | None (keep going) | None (until attempt_completion) |
