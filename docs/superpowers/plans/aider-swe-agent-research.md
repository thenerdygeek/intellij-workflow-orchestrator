# Aider & SWE-agent: Agentic System Research

**Date:** 2026-03-24
**Purpose:** Extract implementation patterns from two leading open-source AI coding agents for our agent module design.

---

## Table of Contents

1. [Aider — System Prompt Structure](#1-aider--system-prompt-structure)
2. [Aider — Context Management](#2-aider--context-management)
3. [Aider — Tool/Action Presentation](#3-aider--toolaction-presentation)
4. [Aider — Error Handling](#4-aider--error-handling)
5. [Aider — Multi-Step Planning](#5-aider--multi-step-planning)
6. [Aider — Safety](#6-aider--safety)
7. [Aider — Getting Stuck](#7-aider--getting-stuck)
8. [SWE-agent — System Prompt Structure](#8-swe-agent--system-prompt-structure)
9. [SWE-agent — Context Management](#9-swe-agent--context-management)
10. [SWE-agent — Tool/Action Presentation (ACI)](#10-swe-agent--toolaction-presentation-aci)
11. [SWE-agent — Error Handling](#11-swe-agent--error-handling)
12. [SWE-agent — Multi-Step Planning](#12-swe-agent--multi-step-planning)
13. [SWE-agent — Safety](#13-swe-agent--safety)
14. [SWE-agent — Getting Stuck](#14-swe-agent--getting-stuck)
15. [Comparative Analysis](#15-comparative-analysis)
16. [Lessons for Our Agent Module](#16-lessons-for-our-agent-module)

---

## 1. Aider — System Prompt Structure

**Source:** `aider/coders/editblock_prompts.py`, `base_prompts.py`, `udiff_prompts.py`, `ask_prompts.py`

### Prompt Assembly Order (in `format_chat_chunks()`)

The system prompt is assembled from multiple layers in `ChatChunks`:

```
1. system       — Core system instructions (role + edit format)
2. examples     — Few-shot demonstrations of the edit format
3. done         — Summarized prior conversation history
4. repo         — Repository map (tree-sitter based)
5. readonly_files — Reference files (read-only context)
6. chat_files   — Files added to chat (editable)
7. cur          — Current user message
8. reminder     — System reminder (rules reinforcement)
```

### Core System Prompt Text

The base prompt across all edit modes begins with:

> "Act as an expert software developer. Always use best practices when coding. Respect and use existing conventions, libraries, etc that are already present in the code base."

Then adds mode-specific instructions:
- **EditBlock mode:** "Describe each change with a SEARCH/REPLACE block"
- **WholeFile mode:** "Return a file listing that contains the entire content of the file"
- **UnifiedDiff mode:** "Write out the changes similar to a unified diff like `diff -U0` would produce"
- **Ask/Architect mode:** "Act as an expert code analyst. Do not return fully detailed code. Describe the needed changes or give a plan."

### System Reminder (appended at end of every message)

Rules reinforcement sent with every turn. For EditBlock mode:
- Exact SEARCH/REPLACE block format with 8 required elements
- "Every SEARCH section must EXACTLY MATCH the existing file content, character for character"
- "Only create SEARCH/REPLACE blocks for files that the user has added to the chat"
- "Keep SEARCH/REPLACE blocks concise"

### Dynamic Substitutions in `fmt_system_prompt()`

- `{fence}` — Code fence delimiters
- Platform info (shell, language, date)
- Lint/test command preferences
- Shell command toggle
- `{final_reminders}` — Model-specific additions

### Key Pattern: Role + Format + Rules + Examples

Aider's prompt is structured as:
1. **Role** — "Act as an expert software developer"
2. **Format** — Exact edit format instructions (SEARCH/REPLACE, diff, etc.)
3. **Rules** — Constraints and requirements
4. **Examples** — Few-shot demonstrations embedded in conversation

### Additional Behavioral Prompts

- **Lazy prompt:** "You are diligent and tireless! You NEVER leave comments describing code without implementing it! You always COMPLETELY IMPLEMENT the needed code!"
- **Overeager prompt:** "Pay careful attention to the scope of the user's request. Do what they ask, but no more. Do not improve, comment, fix or modify unrelated parts of the code in any way!"

---

## 2. Aider — Context Management

**Source:** `aider/repomap.py`, `aider/history.py`, `aider/coders/base_coder.py`, `aider/coders/chat_chunks.py`

### Repository Map (Tree-Sitter + PageRank)

The repo map is Aider's primary context management innovation.

**How it works:**

1. **Parse:** Tree-sitter parses every source file into AST, extracting definitions and references via language-specific `.scm` query files (130+ languages supported)
2. **Graph:** Builds a directed multigraph (NetworkX) where nodes = files, edges = identifier references
3. **Rank:** Personalized PageRank with custom weights:
   - Files in active chat: **50x boost**
   - Mentioned identifiers: **10x boost**
   - CamelCase/snake_case naming matches: **10x boost**
   - Private symbols (`_` prefix): **0.1x penalty**
   - Overused symbols (5+ definitions): **0.1x penalty**
4. **Budget:** Binary search to find the maximum set of ranked tags that fit within the token budget (default 1024 tokens, configurable with `--map-tokens`)
   - Convergence threshold: +/-15% error acceptable
   - Target: `min(max_map_tokens * multiplier, context_window - 4096 padding)`
5. **Cache:** SQLite disk cache with modification-time validation; falls back to in-memory dict

**Output format:** File paths with indented class/function signatures (no implementation bodies).

### Conversation History Summarization

**Class:** `ChatSummary`

- **Trigger:** `too_big()` checks if `done_messages` exceed `max_tokens`
- **Strategy:** Recursive split — divide into head/tail, summarize head, combine, recurse (max depth 3)
- **Summarize prompt:** "Briefly summarize this partial conversation about programming. Include less detail about older parts and more detail about the most recent messages... The summaries MUST include the function names, libraries, packages being discussed and filenames referenced in code blocks."
- **Summary prefix:** "I spoke to you previously about a number of things."
- **Output:** Single user-role message replacing the summarized history
- **Fallback:** Tries multiple models sequentially; raises error if all fail

### Token Budget Allocation

The `ChatChunks` dataclass organizes messages into 8 priority sections. Key behaviors:
- System prompt and current message: always included
- Repo map: dynamically sized to fill remaining budget
- Done messages (history): summarized when too large
- Context window exceeded: `ContextWindowExceededError` shows token breakdown and mitigation suggestions

### Prompt Caching

`add_cache_control_headers()` strategically applies `{"cache_control": {"type": "ephemeral"}}` to:
- Examples (over system prompt)
- Repo data (over readonly files)
- Chat files (always cached)

---

## 3. Aider — Tool/Action Presentation

Aider does NOT use function calling or structured tool APIs. Instead, it uses **text-based edit formats** embedded in the system prompt:

### EditBlock Format (default, most popular)
```
path/to/file.py
<<<<<<< SEARCH
exact existing code
=======
replacement code
>>>>>>> REPLACE
```

### UnifiedDiff Format
```
--- path/to/file.py
+++ path/to/file.py
@@ ... @@
-old line
+new line
```

### WholeFile Format
Outputs the entire file content within code fences.

### Shell Commands
Optionally enabled — model can suggest shell commands for the user to run.

### Key Design Choice: No Function Calling

Aider deliberately uses text-based formats rather than function calling because:
- Works across all LLM providers uniformly
- The model can explain changes naturally alongside the edit blocks
- Edit format is the "tool" — the model's output IS the action

---

## 4. Aider — Error Handling

**Source:** `aider/coders/base_coder.py`

### Retry Logic in `send_message()`

```
Catches litellm exceptions → exponential backoff (retry_delay *= 2)
→ up to RETRY_TIMEOUT
```

### Exception Categories

| Error | Handling |
|---|---|
| `ContextWindowExceededError` | Set exhausted flag, show token breakdown, break loop |
| `FinishReasonLength` | Attempt prefill continuation if model supports `assistant_prefill` |
| `KeyboardInterrupt` | Set interrupted flag, break gracefully |
| Generic exceptions | Log traceback, set `reflected_message` for retry |

### Reflection Loop (Auto-Fix)

After each LLM response:
1. Apply edits via `apply_updates()`
2. Auto-commit changes
3. Run linter on edited files → if errors, set `reflected_message` with lint output
4. Run test suite (if configured) → if failures, set `reflected_message` with test output
5. If `reflected_message` is set AND `num_reflections < max_reflections` (default 3): loop back and send the error to the LLM

### URL Detection
`check_and_open_urls()` extracts URLs from exception messages via regex, offers to open in browser.

---

## 5. Aider — Multi-Step Planning

### Architect/Editor Mode (Two-Phase)

Aider's primary planning mechanism splits inference into two steps:

1. **Architect phase:** A "reasoning" model (e.g., o1, Claude) describes how to solve the problem — no code edits, just a plan
2. **Editor phase:** A separate model (can be different LLM) takes the Architect's plan and produces specific SEARCH/REPLACE blocks

**Key insight:** The Architect focuses on WHAT to change without worrying about edit format. The Editor focuses on HOW to format edits without worrying about the solution.

### Within a Single Turn

- Step-by-step reasoning is requested: "Think step-by-step and explain the needed changes in a few short sentences"
- Then produce edit blocks

### No Explicit Plan-Then-Execute Loop

Aider does NOT have a built-in plan → approve → execute loop. The Architect mode is the closest equivalent, but it's a two-LLM pipeline rather than an interactive planning phase.

---

## 6. Aider — Safety

### Git-Based Safety (Primary Mechanism)

- **Auto-commit:** Every AI edit is committed with a descriptive message
- **Dirty file protection:** Pre-existing uncommitted changes are committed separately before AI edits
- **Undo:** `/undo` command reverses the last AI commit atomically
- **Audit trail:** Full git history of every AI change

### File Access Controls

- `.aiderignore` — files excluded from chat
- `.gitignore` — respected when adding files
- Read-only files clearly marked: "Here are some READ ONLY files, provided for your reference. Do not edit these files!"

### No Sandbox/Container

Aider runs directly on the host filesystem. Git is the safety net — no Docker isolation, no filesystem sandboxing.

---

## 7. Aider — Getting Stuck

### Reflection Limit

- Maximum **3 reflections** per message (`max_reflections = 3`)
- If exceeded: "Only 3 reflections allowed, stopping."

### Known Issue: Lint Loop

- Documented bug: if the LLM cannot fix a lint error, it can loop (up to the reflection limit)
- Code formatters returning non-zero exit codes (even on success) can trigger false loops

### State Reset

`init_before_message()` clears per-message state on each new user message:
- `aider_edited_files`, `lint_outcome`, `test_outcome`, `num_reflections`

### File Mention De-duplication

`ignore_mentions` set prevents re-prompting for the same files, avoiding repetitive suggestion loops.

### Double Ctrl+C Exit

Two Ctrl+C within 2 seconds triggers `sys.exit()` — hard exit for stuck states.

---

## 8. SWE-agent — System Prompt Structure

**Source:** `config/default.yaml`

### System Template (Minimal)

```
You are a helpful assistant that can interact with a computer to solve tasks.
```

This is intentionally minimal — the tools themselves carry the documentation.

### Instance Template (Task-Specific)

```
<uploaded_files>
{{working_dir}}
</uploaded_files>
I've uploaded a python code repository in the directory {{working_dir}}.
Consider the following PR description:

<pr_description>
{{problem_statement}}
</pr_description>

Can you help me implement the necessary changes to the repository
so that the requirements specified in the <pr_description> are met?

Follow these steps to resolve the issue:
1. Find and read code relevant to the <pr_description>
2. Create a script to reproduce the error and execute it
3. Edit the sourcecode of the repo to resolve the issue
4. Rerun your reproduce script and confirm that the error is fixed!
5. Think about edgecases and make sure your fix handles them as well

Your thinking should be thorough and so it's fine if it's very long.
```

### Observation Template

```
OBSERVATION:
{{observation}}
```

### Key Design Choice: Tool Docs ARE the Prompt

SWE-agent keeps the system prompt minimal and puts all capability documentation into tool docstrings. The tools are self-documenting — the model learns what it can do from the tool definitions, not from verbose system instructions.

### Submit Review Messages (Post-Submission Guidance)

Before accepting a submission, SWE-agent shows:
1. Run reproduction script again if changes were made after
2. Remove reproduction script
3. Revert any test file changes with `git checkout`
4. Run submit command again to confirm

---

## 9. SWE-agent — Context Management

**Source:** `sweagent/agent/history_processors.py`, `sweagent/agent/agents.py`

### History Processor Pipeline

Seven processor classes, composable in sequence:

| Processor | Strategy |
|---|---|
| `DefaultHistoryProcessor` | Pass-through (no modification) |
| `LastNObservations` | Keep only last N observation outputs; replace older ones with "Old environment output: (X lines omitted)" |
| `ClosedWindowHistoryProcessor` | Track open file windows; replace outdated windows with "Outdated window with X lines omitted..." |
| `TagToolCallObservations` | Add tags to specific tool call outputs |
| `CacheControlHistoryProcessor` | Add prompt caching markers to last N messages |
| `RemoveRegex` | Strip content matching regex patterns |
| `ImageParsingHistoryProcessor` | Convert base64 images to multimodal format |

### Default Configuration

```yaml
history_processors:
  - type: cache_control
    last_n_messages: 2
```

### Key Design Choice: Structural Elision, Not Summarization

SWE-agent NEVER summarizes history with an LLM. Instead:
- Replace old outputs with line counts
- Track file windows and remove stale views
- Strip content via regex
- This is deterministic and costs zero tokens

### Context Window Exceeded

When hit, the agent:
1. Catches `ContextWindowExceededError` in `forward_with_handling()`
2. Calls `handle_error_with_autosubmission()` with status "exit_context"
3. Attempts to extract and submit current patch via `git add -A && git diff --cached`
4. Terminates — no recovery, no retry

### Token Counting

Uses `litellm.utils.token_counter()` with model-specific tokenizers. Pre-validates input tokens against `model_max_input_tokens` before every API call.

---

## 10. SWE-agent — Tool/Action Presentation (ACI)

**Source:** `tools/` directory, `config/default.yaml`

### Tool Bundles

SWE-agent organizes tools into composable YAML bundles:

**Default bundle set:**
- `tools/registry` — Tool registry (empty by default, extensible)
- `tools/edit_anthropic` — Anthropic-style `str_replace_editor`
- `tools/review_on_submit_m` — Submit with review
- Plus: `enable_bash_tool: true` — raw bash access

### Core Tools (ACI)

**str_replace_editor** (5 commands):
| Command | Description |
|---|---|
| `view` | Display file with `cat -n`, or list directory (2 levels) |
| `create` | Create new file (fails if exists) |
| `str_replace` | Replace exact string match (must be unique in file) |
| `insert` | Insert text after a specific line number |
| `undo_edit` | Revert last edit to a file |

**Search tools:**
| Tool | Description |
|---|---|
| `find_file` | Find files by name/pattern (shell wildcards) |
| `search_dir` | Search for term in all files in directory |
| `search_file` | Search for term in a specific file |

**Navigation tools (windowed mode):**
| Tool | Description |
|---|---|
| `open` | Open file at optional line number |
| `goto` | Jump to line number |
| `scroll_up/scroll_down` | Move window by N lines |
| `create` | Create and open new file |

**Submission:**
| Tool | Description |
|---|---|
| `submit` | Submit the current solution |

**Other:**
| Tool | Description |
|---|---|
| `filemap` | Print Python file with function bodies elided |

### Tool Format: Function Calling

```yaml
parse_function:
  type: function_calling
```

SWE-agent uses the LLM's native function calling interface (not text-based tool use).

### Key ACI Design Principles

1. **Simple, composable actions** — small set of tools, each doing one thing
2. **Guardrails built in** — `str_replace` requires unique match, `create` fails on existing files
3. **Specific feedback** — every action returns a clear observation about what happened
4. **State tracking** — `_state` command shows current file window after every action

---

## 11. SWE-agent — Error Handling

**Source:** `sweagent/agent/agents.py`

### Error Categories and Handlers

| Error Type | Handler | Behavior |
|---|---|---|
| `FormatError` | Requery loop (max 3) | Increment `n_format_fails`, resubmit with correction |
| `_BlockedActionError` | Requery loop (max 3) | Same as FormatError |
| `BashIncorrectSyntaxError` | Requery with shell template | Correct bash syntax |
| `CommandTimeoutError` | Track consecutive count | Auto-submit after threshold |
| `ContextWindowExceededError` | Auto-submit | Extract patch and exit |
| `CostLimitExceededError` | Auto-submit | Terminate on budget exhaustion |
| `TotalCostLimitExceededError` | Re-raise immediately | Halt all retry attempts |

### Requery Logic

Up to `max_requeries` (default 3) attempts per step for parsing/format errors. If exceeded, triggers auto-submission.

### Auto-Submission on Fatal Errors

`handle_error_with_autosubmission()`:
1. Run `git add -A && git diff --cached`
2. Submit whatever patch exists
3. Set `done = True`

### API Retry Logic

Exponential backoff via tenacity:
- 20 retries max
- 10-120 second random exponential waits
- Non-retryable: context window errors, cost limit violations

---

## 12. SWE-agent — Multi-Step Planning

### Instance Template Provides the Plan

The 5-step workflow is embedded in the instance template:
1. Find and read relevant code
2. Create reproduction script and run it
3. Edit source code
4. Rerun reproduction script
5. Consider edge cases

### No Explicit Plan-Approve-Execute Loop

SWE-agent does NOT have a planning phase. The model follows the template steps implicitly. The thinking happens within each turn: "Your thinking should be thorough and so it's fine if it's very long."

### RetryAgent (Self-Review and Retry)

The `RetryAgent` adds multi-attempt planning:
1. Agent produces a solution
2. `Reviewer` scores it via LLM (numerical score, multiple samples averaged)
3. If score < `accept_score`: reset environment, try again
4. Continue until `max_attempts` or `cost_limit` reached
5. Select best attempt by highest review score

### AskColleagues (Action Sampling)

Alternative planning strategy:
1. Query model N times in parallel for different approaches
2. Concatenate all "colleague ideas" into a discussion prompt
3. Ask model to "summarize and compare the ideas and propose an action"
4. This is per-step, not per-task

---

## 13. SWE-agent — Safety

### Docker Container Isolation (Primary)

- All execution happens inside Docker containers
- SWE-ReX manages the container lifecycle
- Deployment options: local Docker, AWS Fargate, Modal
- Custom container images supported (default: Python 3.11)

### Environment Variables Lockdown

```yaml
env_variables:
  PAGER: cat          # No interactive pagers
  MANPAGER: cat
  LESS: -R
  PIP_PROGRESS_BAR: 'off'
  TQDM_DISABLE: '1'
  GIT_PAGER: cat
```

### Command Timeouts

- Per-command timeout tracked
- `_n_consecutive_timeouts` counter
- Total execution time limit: `total_execution_timeout`
- Exceeding triggers auto-submission

### Cost Limits

- Per-instance cost limit
- Per-retry-loop cost limit
- Total cost limit (across all instances)
- Review costs tracked separately

### Submit Review

Before accepting, review messages prompt:
1. Rerun reproduction script
2. Remove reproduction script
3. Revert test file changes
4. Submit again to confirm

---

## 14. SWE-agent — Getting Stuck

### No Hard Step Limit

The agent loop runs indefinitely until `step_output.done == True`. There is NO maximum step counter.

### Timeout-Based Detection

- Consecutive command timeouts tracked in `_n_consecutive_timeouts`
- Reset to 0 on successful execution
- Threshold triggers auto-submission

### Total Execution Time

- `_total_execution_time` accumulates across steps
- Compared against `total_execution_timeout`
- Exceeding raises `_TotalExecutionTimeExceeded` → auto-submission

### Cost-Based Termination

- Per-instance and total cost limits provide a financial ceiling
- When budget exhausted, agent auto-submits whatever it has

### Special Tokens for Agent Self-Exit

- `RETRY_WITH_OUTPUT_TOKEN` → retry with current output
- `RETRY_WITHOUT_OUTPUT_TOKEN` → retry from scratch
- `EXIT_FORFEIT_TOKEN` → give up and auto-submit

---

## 15. Comparative Analysis

| Dimension | Aider | SWE-agent |
|---|---|---|
| **System prompt length** | Long (role + format + rules + examples) | Short system + long instance template |
| **Edit mechanism** | Text-based (SEARCH/REPLACE, diff) | Function calling (str_replace_editor) |
| **Context management** | Tree-sitter repo map + LLM summarization | History processors (structural elision) |
| **Repo understanding** | PageRank-based repo map (graph ranking) | filemap tool (on-demand, per-file) |
| **Error recovery** | Reflection loop (max 3), lint/test auto-fix | Requery loop (max 3), auto-submission |
| **Planning** | Architect/Editor two-phase | Implicit in instance template |
| **Safety** | Git auto-commit + undo | Docker container isolation |
| **Stuck detection** | Reflection limit (3) | Timeout + cost limits |
| **Sandbox** | None (runs on host) | Docker container |
| **Token optimization** | Prompt caching + summarization | Prompt caching + structural elision |
| **Multi-attempt** | None | RetryAgent with LLM reviewer scoring |
| **Cost control** | None built-in | Per-instance + total cost limits |

### What Aider Does Better

1. **Repo-wide context** — PageRank-based repo map gives the model awareness of the entire codebase structure, not just the current file
2. **History summarization** — LLM-based summarization preserves semantic meaning when compressing history
3. **Git safety** — Auto-commit + undo is simpler and works without containers
4. **Edit format flexibility** — Multiple edit formats optimized for different models

### What SWE-agent Does Better

1. **Structured tool use** — Function calling is more reliable than text-based edit parsing
2. **Cost control** — Built-in cost limits prevent runaway spending
3. **Self-review** — RetryAgent with scoring produces higher quality solutions
4. **Isolation** — Docker containers prevent filesystem damage
5. **Composable tools** — YAML-based tool bundles are easy to customize
6. **Deterministic history compression** — No LLM calls needed for context management

---

## 16. Lessons for Our Agent Module

### Patterns to Adopt

1. **Structured tool calling over text parsing** — Use Cody's tool_use support rather than text-based edit formats. SWE-agent proves function calling works well for code editing.

2. **Deterministic history compression** — SWE-agent's `LastNObservations` (replace old outputs with line counts) is cheaper and more predictable than LLM summarization. Consider this as a first pass before expensive summarization.

3. **Cost limits** — Essential for any agent loop. Per-task and total cost limits prevent runaway spending.

4. **Auto-submission on fatal errors** — When context window is exhausted or cost limit hit, save whatever progress exists rather than losing everything.

5. **Requery on format errors (max 3)** — Both tools limit format error retries to 3. This is a good default.

6. **Step-by-step workflow in instance template** — SWE-agent's 5-step workflow template is effective: find code → reproduce → fix → verify → edge cases. We should embed similar workflow guidance in our tool descriptions.

7. **Prompt caching** — Both tools use it. Cache control headers on stable context (system prompt, file contents) saves significant cost.

8. **Self-review before submission** — SWE-agent's RetryAgent pattern (score solution, retry if low) produces meaningfully better results.

### Patterns to Avoid

1. **No step limit** — SWE-agent has no hard step counter. We MUST have one to prevent infinite loops in an IDE plugin context.

2. **Text-based edit formats** — Aider's SEARCH/REPLACE works but requires exact string matching. Function calling is more robust.

3. **Running on host without sandbox** — Aider's approach. For an IDE plugin, we should at minimum use git-based safety (auto-commit + undo).

4. **LLM summarization as sole compression** — Expensive and can lose important details. Use structural elision first, summarization as fallback.

### Architecture Recommendations

```
Our Agent Loop Design:

1. System prompt: Short role + tool descriptions (SWE-agent style)
2. Task prompt: Structured workflow steps (SWE-agent instance template style)
3. Context: PageRank-based file relevance (Aider style, but via PSI not tree-sitter)
4. History: Structural elision first (SWE-agent), LLM summarization when needed (Aider)
5. Tools: Function calling via Cody API (SWE-agent style)
6. Error recovery: Requery max 3 + reflection max 3 (both tools)
7. Safety: Git auto-commit + undo (Aider) + cost limits (SWE-agent)
8. Stuck detection: Step limit (hard) + cost limit + timeout (SWE-agent)
9. Quality: Self-review scoring before accepting (SWE-agent RetryAgent)
```

---

## Source Files Referenced

### Aider (https://github.com/Aider-AI/aider)
- `aider/coders/base_coder.py` — Main agent loop, `run()`, `send_message()`, `format_chat_chunks()`
- `aider/coders/base_prompts.py` — Base prompt templates (lazy, overeager, file prefixes)
- `aider/coders/editblock_prompts.py` — SEARCH/REPLACE format system prompt
- `aider/coders/udiff_prompts.py` — Unified diff format system prompt
- `aider/coders/wholefile_prompts.py` — Whole file format system prompt
- `aider/coders/ask_prompts.py` — Architect/ask mode prompt
- `aider/coders/chat_chunks.py` — `ChatChunks` dataclass (8 message sections + cache control)
- `aider/repomap.py` — Tree-sitter parsing, PageRank ranking, token-budgeted repo map
- `aider/history.py` — `ChatSummary` class (recursive LLM-based summarization)
- `aider/prompts.py` — Summarization prompt, commit prompt, misc prompt templates

### SWE-agent (https://github.com/SWE-agent/SWE-agent)
- `config/default.yaml` — Default agent configuration (templates, tools, history processors)
- `sweagent/agent/agents.py` — `DefaultAgent`, `RetryAgent`, main loop, `step()`, `forward()`
- `sweagent/agent/history_processors.py` — 7 history processor classes
- `sweagent/agent/models.py` — Model config, token counting, cost tracking, API retry
- `sweagent/agent/action_sampler.py` — `AskColleagues`, `BinaryTrajectoryComparison`
- `sweagent/agent/reviewer.py` — `Reviewer` class, `ScoreRetryLoop`
- `tools/edit_anthropic/config.yaml` — `str_replace_editor` tool definition
- `tools/search/config.yaml` — `find_file`, `search_dir`, `search_file` tools
- `tools/windowed/config.yaml` — `open`, `goto`, `scroll_up/down`, `create` tools
- `tools/filemap/config.yaml` — Python file structure viewer

### Mini-SWE-agent (https://github.com/SWE-agent/mini-swe-agent)
- `src/minisweagent/agents/default.py` — Minimal ~170 line agent (bash-only, linear history, subprocess execution)
