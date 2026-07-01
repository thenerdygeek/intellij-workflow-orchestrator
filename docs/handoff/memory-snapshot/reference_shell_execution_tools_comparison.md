# Enterprise AI Agent Shell/Command Execution Tools — Comprehensive Comparison

Research date: 2026-03-26
Sources: Official docs, GitHub source code, DeepWiki, community forums, blog posts, security advisories

---

## 1. CLAUDE CODE — Bash Tool

### Tool Definition
- **Tool name:** `Bash`
- **Parameters:** `command` (required string), `timeout` (optional ms, max 600000), `description` (required string), `run_in_background` (optional bool), `dangerouslyDisableSandbox` (optional bool)
- **Persistent session:** Yes — working directory persists between calls, but shell state (env vars set in one call) does NOT persist
- **Shell init:** Initialized from user's profile (bash or zsh)

### Command Safety / Blocking
- **Permission system:** Every Bash invocation requires user approval unless auto-approved via settings
- **Auto-approval:** Configurable allowlist in `~/.claude/settings.json` (regex patterns for safe commands)
- **Dangerous command detection:** System prompt instructs Claude to avoid destructive commands (rm -rf, format, fork bombs)
- **Hooks:** `PreToolUse` hooks can intercept and block commands before execution (e.g., `claude-code-safety-net` blocks destructive git/fs commands)
- **`dangerouslyDisableSandbox`:** When true AND Bash is auto-approved, bypasses sandbox without user prompt (security concern raised in issue #14268)
- **Prompt-level safety:** "Carefully consider the reversibility and blast radius of actions" — freely take local reversible actions, check with user for hard-to-reverse/shared/risky actions

### Timeout Handling
- **Default:** 120,000ms (2 minutes)
- **Max:** 600,000ms (10 minutes) — per-invocation override via `timeout` parameter
- **Global config:** `BASH_DEFAULT_TIMEOUT_MS` and `BASH_MAX_TIMEOUT_MS` in `~/.claude/settings.json` env section
- **Behavior on timeout:** Command killed, error returned to model
- **Known issue:** Commands that complete successfully can still timeout if output handling stalls (issue #3505)

### Output Streaming
- **Real-time to UI:** Yes — terminal output streams live to the CLI interface
- **To LLM:** Output collected and returned as tool result after command completes (not streamed incrementally to model)

### Output Truncation / Capping
- **Character limit:** ~30,000 characters for stdout
- **Bash stdout:** Various thresholds reported (~4k, 6k, 8k, 16k chars in some versions)
- **No automatic summarization:** Large outputs consume context directly (known issue)
- **MCP tool responses:** Truncated separately to ~700 characters

### Working Directory Handling
- **Persists between calls:** Working directory carries over across Bash invocations within a session
- **Shell state does NOT persist:** Environment variables, aliases set in one call don't carry to next
- **System prompt guidance:** Instructs Claude to use absolute paths and avoid `cd`

### Environment Variable Handling
- **Shell profile loaded:** Yes (bash or zsh profile on init)
- **Per-call isolation:** Shell state resets between calls (only cwd persists)
- **Secret protection:** Instructs model not to echo sensitive env vars

### Process Lifecycle
- **Kill support:** `KillBash` tool kills background processes by shell_id
- **Cancel:** User can press Escape to interrupt current operation
- **Background execution:** `run_in_background: true` starts process in separate shell with unique ID
- **Background monitoring:** `BashOutput` tool reads output from background process
- **Known issue:** Background processes can trigger infinite system-reminder loops causing token exhaustion (issue #11716)
- **Known issue:** After long sessions, Claude forgets background processes and can't use KillBash (reported by badlogicgames)

### Sandbox / Isolation
- **macOS:** Seatbelt framework (sandbox-exec) — built-in, works out of the box
- **Linux/WSL2:** Bubblewrap (bwrap) — filesystem namespace isolation
- **Inheritance:** All child processes inherit sandbox boundaries
- **Disable:** `dangerouslyDisableSandbox: true` parameter
- **Docker:** Not used natively (OS-level sandboxing preferred)

### How LLM Gets Result
- **Full output** returned as tool_result content (JSON text block)
- **No summarization** — raw stdout/stderr up to truncation limit
- **Exit code** included in result

### Unique Features
- **Background execution** with `run_in_background` + `BashOutput` + `KillBash` lifecycle
- **Description parameter** required — Claude must explain what the command does
- **Sub-agent Bash:** Dedicated `bash` agent type runs commands in isolated context
- **Hooks system:** PreToolUse/PostToolUse hooks for custom safety gates
- **Parallel execution:** Read-only Bash commands can run concurrently; state-modifying commands sequential

---

## 2. CURSOR — Terminal Tool

### Tool Definition
- **Tool name:** `run_terminal_command` (Agent mode)
- **Parameters:** Command string, working directory
- **Integration:** VS Code integrated terminal

### Command Safety / Blocking
- **Three modes:** Ask Every Time, Auto-Run in Sandbox, Run Everything (Unsandboxed)
- **Command allowlist/denylist:** Configurable, but reported as silently ignored when "Auto-Run in Sandbox" enabled (bug)
- **sandbox.json:** Network and filesystem policies — controls domains, paths, and access levels
- **YOLO mode:** Auto-executes everything without approval
- **Security vulnerabilities:** Critical CVEs disclosed (workspace trust bypass in 2025, command execution via crafted repos)

### Timeout Handling
- **Default:** ~200,000ms (3.3 minutes) based on community reports
- **Behavior:** Tool call times out after 200,000ms, terminal gets stuck
- **Not user-configurable:** Users have requested configurable timeouts (unfulfilled as of 2026)
- **Known issue:** Agent arbitrarily times out processes it started, losing tool call tokens

### Output Streaming
- **Real-time to UI:** Yes — visible in VS Code terminal panel
- **To LLM:** Output captured after completion; known issues with empty output or missing last line

### Output Truncation / Capping
- **Tail truncation:** Agent prefers `tail 20` — showing only last 20 lines
- **Known issue:** Composer output tail truncation loses important information
- **Known issue:** Agent sometimes receives empty output despite command producing visible output

### Working Directory Handling
- **Project-scoped:** Defaults to workspace root
- **Configurable per-call:** Can specify working directory

### Environment Variable Handling
- **Inherits VS Code terminal environment:** User's shell profile loaded
- **Sandbox restrictions:** Network access controlled via sandbox.json

### Process Lifecycle
- **Cancel:** User can interrupt via VS Code terminal
- **Background:** Subagents can run asynchronously (as of 2026)
- **Known issue:** Terminal commands often hang, requiring user click to pop out

### Sandbox / Isolation
- **sandbox.json:** Three levels of network access (restrict to config, allowlist + defaults, allow all)
- **Filesystem policies:** Path-based restrictions
- **macOS/Linux:** Sandboxing enabled for terminal commands from chat window — read/write to working directory, network blocked by default
- **No OS-level sandbox:** Relies on VS Code extension sandbox + policy files (not Seatbelt/bubblewrap)

### How LLM Gets Result
- **Truncated output** — tail 20 lines typical
- **Known reliability issues** with output capture

### Unique Features
- **Sandbox network controls** (Cursor 2.5) — granular domain allowlisting
- **Linter integration** — agent notices linter errors and auto-fixes
- **Sub-agent parallel execution** — background subagents for concurrent work

---

## 3. CLINE — execute_command Tool

### Tool Definition
- **Tool name:** `execute_command`
- **Parameters:** `command` (required), `requires_approval` (bool)
- **Integration:** VS Code Shell Integration API (with fallback)

### Command Safety / Blocking
- **Human-in-the-loop:** Every command requires user approval by default
- **Auto-approve modes:** Granular per-type (read files, write files, commands, browser, MCP)
- **YOLO mode:** `yoloModeToggled` bypasses all approvals
- **Prompt-level rules:** ~40 rules including: cannot `cd` to different directory, must not use `~` or `$HOME`, must use `--` before positional args to prevent option injection
- **Subagent restrictions:** Subagents limited to read-only commands (ls, grep, git log, git diff, gh)
- **`.clinerules/`:** Project-specific behavioral constraints

### Timeout Handling
- **Hard timeout:** 30 seconds on all execute_command operations (regression in v3.34)
- **Not overridable:** Cannot be configured through standard methods
- **Behavior:** Commands continue running in background but Cline stops listening for output
- **Shell integration timeout:** 4 seconds for shell integration activation (configurable via PR #2478)
- **Known issue:** Cline "timeouts" after ~30s and continues thinking command failed

### Output Streaming
- **Real-time to UI:** Yes — via VS Code Shell Integration API
- **"Proceed While Running" button:** For long-running processes (dev servers), lets Cline continue while command runs
- **Notification of new output:** As Cline works, it's notified of new terminal output from background commands
- **Fallback:** When shell integration fails, reads terminal content directly

### Output Truncation / Capping
- **Not well-documented:** Output captured via shell integration events
- **Context window management:** Aggressive — auto-condensation at 75% threshold

### Working Directory Handling
- **Fixed CWD:** Cline is told it cannot `cd` to a different directory
- **Commands execute in project root** by default

### Environment Variable Handling
- **VS Code terminal environment:** Inherits user shell config
- **Restrictions:** Must not use `~` or `$HOME` (explicit rule)

### Process Lifecycle
- **Cancel:** Abort via VS Code UI — cancels active API stream + running commands
- **Long-running processes:** "Proceed While Running" button lets agent continue while process runs
- **No background execution API:** All commands are foreground (subagents are separate concept)

### Sandbox / Isolation
- **No OS-level sandbox:** Relies on VS Code extension sandbox + user approval flow
- **Subagent isolation:** Separate context window per subagent, read-only tools only

### How LLM Gets Result
- **Full output** via shell integration events
- **Command output verification:** Prompt instructs "don't assume success — verify output"

### Unique Features
- **Shell Integration API primary, direct terminal read fallback** — robust output capture
- **"Proceed While Running" UX** — unique long-running process handling
- **Explanation requirement:** Model must explain why it's running each command
- **`<thinking>` tags required** before tool calls for reasoning

---

## 4. OPENAI CODEX CLI — Shell Tool

### Tool Definition
- **Tool name:** `shell` (internally `shell_call`)
- **Parameters:** `command` (string array), `timeout_ms` (number), `workdir` (optional)
- **Runtime:** Rust-based (codex-rs/core)

### Command Safety / Blocking
- **Five approval modes:**
  - `Never` — auto-approve everything
  - `OnFailure` — auto-approve, ask only on failure
  - `OnRequest` — ask for approval on each action
  - `UnlessTrusted` — ask unless in trusted list
  - `Granular(config)` — fine-grained per-action-type
- **Guardian system:** `GuardianApprovalRequest` types: Shell, ExecCommand, ApplyPatch, NetworkAccess, McpToolCall
- **Network approval:** Host/port/protocol tracking
- **MCP tool annotations:** `destructive_hint`, `open_world_hint`, `read_only_hint`
- **Shell environment policy:** Configurable env var includes/excludes/overrides to avoid leaking secrets

### Timeout Handling
- **Per-command:** `timeout_ms` parameter on shell tool
- **Worker timeout:** `job_max_runtime_seconds` defaults to 1800s (30 minutes) per worker
- **Background polling:** `max_poll_window_ms` defaults to 300,000ms (5 minutes) for terminal polling
- **Known issue:** Shell init scripts (.bashrc) can cause hangs when using `bash -lc`

### Output Streaming
- **Real-time to UI:** Yes — terminal output streams live
- **To LLM:** Collected after completion with truncation

### Output Truncation / Capping
- **Recommended:** 10,000 tokens (~40,000 bytes at 4 bytes/token estimate)
- **Split strategy:** Half budget for beginning, half for end, truncation indicator in middle
- **TruncationPolicy:** `Bytes(usize)` or `Tokens(usize)` modes
- **Preserves UTF-8 boundaries** on truncation
- **Prepends "Total output lines: N"** when truncating

### Working Directory Handling
- **Configurable per-call:** `workdir` parameter
- **Workspace-scoped:** Sandbox constrains to workspace root + configured writable roots
- **Worker ownership:** Workers get explicit file/module ownership with writable paths

### Environment Variable Handling
- **Shell environment policy:** `inherits_from: none|core`, `excludes`, `includes`, `overrides`
- **Secret protection:** Explicit env var filtering to prevent leaking secrets
- **Login shell:** Configurable `login_shell` (defaults true) — `bash -lc` semantics

### Process Lifecycle
- **Timeout-based kill:** Commands killed after timeout_ms
- **Worker timeouts:** `job_max_runtime_seconds` per sub-agent worker
- **Sub-agent threads:** Each gets own thread, config, and agent loop
- **`agent_max_threads`:** Configurable concurrent agent limit (default 6)

### Sandbox / Isolation
- **macOS:** Seatbelt (sandbox-exec) with dynamically generated SBPL profiles based on permissions
- **Linux:** Bubblewrap (bwrap) for filesystem namespacing + Seccomp for syscall filtering
  - Outer stage: bwrap constructs filesystem view
  - Inner stage: PR_SET_NO_NEW_PRIVS + Seccomp filters
  - Final exec: execvp into target command
  - Falls back to vendored bubblewrap if system bwrap missing
- **Windows:** Restricted process tokens
- **Sandbox modes:** `read-only`, `workspace-write`, `danger-full-access`
- **Network access:** Configurable per sandbox mode (defaults to blocked in workspace-write)
- **Writable roots:** Additional writable paths beyond workspace configurable as array

### How LLM Gets Result
- **Truncated output** with beginning+end strategy
- **"Total output lines: N"** prepended on truncation
- **Tool result as function output** with error text on failure

### Unique Features
- **OS-level sandboxing on all platforms** (macOS/Linux/Windows) — most comprehensive
- **Dynamically generated sandbox profiles** based on agent config
- **Shell environment policy** — fine-grained env var control
- **Sub-agent file ownership** — workers told which files they own
- **CSV fan-out pattern** — `spawn_agents_on_csv` for batch parallel execution
- **Managed proxy mode** — egress through proxy-only bridge

---

## 5. WINDSURF (CODEIUM/COGNITION) — Cascade Terminal

### Tool Definition
- **Tool name:** Terminal command execution via Cascade agent
- **Integration:** VS Code-based IDE with custom terminal integration

### Command Safety / Blocking
- **Four auto-execution levels:**
  1. Always ask
  2. Auto-execute if on allow list
  3. Turbo mode (auto-execute unless on deny list)
  4. Full auto
- **Allow list:** Commands that always auto-execute (e.g., `git` → `git add -A` always runs)
- **Deny list:** Commands that always prompt (e.g., `rm` → `rm index.py` always asks)
- **Turbo mode:** Executes everything except deny-listed commands

### Timeout Handling
- **MCP init timeout:** 60 seconds
- **Command timeout:** Not well-documented; known issues with Cascade getting stuck in command execution loops (issue #153)

### Output Streaming
- **Real-time to UI:** Yes — visible in IDE terminal
- **Background terminal issue:** Commands sometimes only run in background terminal (issue #169)

### Output Truncation / Capping
- **Not publicly documented** in detail

### Working Directory Handling
- **Project-scoped:** Workspace root as default

### Environment Variable Handling
- **Inherits IDE terminal environment**

### Process Lifecycle
- **Cascade-managed:** Agent controls lifecycle
- **Known issue:** Gets stuck in command execution loops

### Sandbox / Isolation
- **No documented OS-level sandbox** — relies on allow/deny lists and user approval
- **IDE extension sandbox:** VS Code-based restrictions

### How LLM Gets Result
- **Terminal output** captured and fed back to Cascade agent

### Unique Features
- **Turbo mode** — unique "deny-list only" approach (whitelist-by-default)
- **Cascade multi-step reasoning** — plans, executes, and iterates on terminal output
- **Now owned by Cognition (Devin)** — likely to gain autonomous agent capabilities

---

## 6. AMAZON Q DEVELOPER CLI — execute_bash Tool

### Tool Definition
- **Tool name:** `execute_bash`
- **Parameters:** Command string
- **Other built-in tools:** `fs_read`, `fs_write`, `use_aws`
- **Runtime:** Open-source at github.com/aws/amazon-q-developer-cli

### Command Safety / Blocking
- **Permission prompt by default** for execute_bash
- **Configuration per-tool:**
  - `allowedCommands` — regex patterns for auto-approved commands
  - `deniedCommands` — regex patterns for blocked commands (evaluated FIRST, before allow)
  - `autoAllowReadonly` — auto-approve read-only commands
- **Trust system:** Tool-level trust with session-level guardrails
- **Audit logging:** All executed commands logged to CloudWatch Logs (always enabled, can't be disabled)
- **Security incident:** In July 2026, VS Code extension v1.84.0 was compromised via prompt injection

### Timeout Handling
- **Not publicly documented** with specific values
- **Known issue:** Commands run in separate bash shell without user's shell configuration

### Output Streaming
- **Real-time to UI:** Yes — terminal output visible during execution

### Output Truncation / Capping
- **Not publicly documented** in detail

### Working Directory Handling
- **Separate bash shell:** Commands execute in a separate bash shell that doesn't access user's shell config
- **Known issue (issue #1030):** Should execute in user's actual shell environment but doesn't — missing aliases, functions, env vars

### Environment Variable Handling
- **Isolated shell:** Does NOT inherit user's shell customizations (bash/zsh)
- **Missing:** User-defined aliases, functions, environment variables, shell customizations
- **AWS credentials:** Accessible for `use_aws` tool

### Process Lifecycle
- **Not well-documented** for cancel/kill

### Sandbox / Isolation
- **No OS-level sandbox** currently
- **Feature request (issue #2336):** Users requesting autonomous sandbox mode
- **Permission-based:** Relies on allow/deny command lists

### How LLM Gets Result
- **Command output** returned to model

### Unique Features
- **AWS-native integration** — `use_aws` tool for direct AWS API calls
- **CloudWatch audit logging** — mandatory, immutable audit trail
- **Custom agents:** Configurable via `.amazonq/agents/` with tool settings
- **Natural language to bash** — converts prompts to executable bash code

---

## 7. GITHUB COPILOT CLI — Shell Tool (runInTerminal)

### Tool Definition
- **Tool name:** `runInTerminal` (in VS Code) / shell execution (in CLI)
- **GA:** February 2026 for all Copilot subscribers
- **Built-in MCP server** ships with CLI

### Command Safety / Blocking
- **Explicit approval required** for every file change and command execution
- **Autopilot mode:** Executes tools and commands without stopping for approval
- **Configuration:** `allowed_urls` and `denied_urls` patterns for web access
- **Sandboxing (VS Code):** macOS/Linux — read/write to working directory, network blocked by default
- **Docker sandbox:** Copilot sandbox available via Docker (workspaces mounted at /workspace)
- **Security advisory:** CVE-2026-29783 — dangerous shell expansion patterns enable arbitrary code execution (fixed in v0.0.423)

### Timeout Handling
- **Default lowered** in recent versions (exact value not publicly documented)
- **Prompt updated:** "Timeout does not imply failure" — explicit language change
- **Hook timeout:** `timeoutSec` parameter for command hooks
- **Known issue:** Agent sometimes doesn't detect command completion, waits for hours

### Output Streaming
- **Real-time to UI:** Yes — terminal output visible
- **Known issue:** Agent sometimes doesn't receive terminal output despite visible output

### Output Truncation / Capping
- **Not publicly documented** in detail
- **Background agent progress** surfaces in task timeout responses

### Working Directory Handling
- **Per-session CWD:** `/cd` keeps separate working directory per session, restored when switching
- **Monorepo support:** Custom instructions, MCP servers, skills, and agents discovered at every directory level from CWD to git root

### Environment Variable Handling
- **Shell environment** inherited from terminal
- **Configuration discovery:** Per-directory configs from CWD to git root

### Process Lifecycle
- **Cancel:** Standard terminal interrupt
- **Specialized agents:** Explore, Task, Plan, Code Review — run in parallel
- **Background agents:** Progress surfaced in status responses

### Sandbox / Isolation
- **macOS/Linux sandboxing:** Terminal commands sandboxed — read/write to working directory, network blocked by default
- **Docker sandbox:** Official Docker sandbox integration
- **Config:** `~/.copilot/config.json` for trusted folders

### How LLM Gets Result
- **Terminal output** captured and returned
- **Known reliability issues** with output capture

### Unique Features
- **Four specialized agents** (Explore, Task, Plan, Code Review) with parallel execution
- **Monorepo-aware CWD** — per-session working directory with directory-level config discovery
- **Docker sandbox integration** — official Docker-based isolation
- **MCP server built-in** — extensible tool system

---

## COMPARISON MATRIX

| Feature | Claude Code | Cursor | Cline | Codex CLI | Windsurf | Amazon Q | Copilot CLI |
|---------|------------|--------|-------|-----------|----------|----------|-------------|
| **Default Timeout** | 2 min | ~3.3 min | 30 sec | Configurable | Unknown | Unknown | Lowered (unspecified) |
| **Max Timeout** | 10 min | Not configurable | Not configurable | 30 min (workers) | Unknown | Unknown | Unknown |
| **Timeout Configurable** | Yes (env vars) | No | No (regression) | Yes (per-call) | No | No | Yes (hooks) |
| **OS-Level Sandbox** | Seatbelt/Bubblewrap | No | No | Seatbelt/Bubblewrap/RestrictedToken | No | No | macOS/Linux (partial) |
| **Docker Sandbox** | No | No | No | No | No | No | Yes (official) |
| **Output Truncation** | ~30K chars | Tail 20 lines | Not documented | 10K tokens (begin+end) | Not documented | Not documented | Not documented |
| **Output Streaming** | Yes (UI only) | Yes (UI only) | Yes (UI + agent notify) | Yes (UI only) | Yes (UI only) | Yes (UI only) | Yes (UI only) |
| **Background Exec** | Yes (run_in_background) | Yes (subagents) | Yes (Proceed While Running) | Yes (sub-agent threads) | No | No | Yes (agents) |
| **Kill/Cancel** | KillBash tool | UI interrupt | UI abort | Timeout + /agent | No | No | Standard interrupt |
| **Command Allowlist** | Settings regex | sandbox.json (buggy) | Auto-approve settings | Approval modes | Allow/deny lists | allowedCommands regex | allowed_urls |
| **Command Denylist** | Hooks + prompt | sandbox.json | .clinerules | Guardian system | Deny list | deniedCommands regex | denied_urls |
| **CWD Persists** | Yes | Yes (project) | No (fixed) | Yes (configurable) | Yes (project) | Separate shell | Yes (per-session) |
| **Shell State Persists** | No (resets) | Yes (terminal) | Yes (terminal) | Configurable | Yes (terminal) | No (separate shell) | Yes (terminal) |
| **Env Var Control** | Profile loaded | IDE inherited | IDE inherited | Full policy (include/exclude/override) | IDE inherited | Isolated (missing user config) | Inherited |
| **Audit Log** | No | No | No | No | No | Yes (CloudWatch, mandatory) | No |
| **LLM Gets** | Full output (truncated) | Tail 20 lines | Full output | Begin+end (truncated) | Full output | Full output | Full output |
| **Safety Hooks** | PreToolUse/PostToolUse | sandbox.json | .clinerules | Guardian + annotations | Allow/deny lists | Allow/deny regex | Config-based |

---

## KEY ARCHITECTURAL INSIGHTS FOR BUILDING AN ENTERPRISE-GRADE TOOL

### 1. Sandbox Strategy (Most Important for Enterprise)
- **Best-in-class:** Codex CLI — OS-level sandbox on all 3 platforms with dynamic profile generation
- **Runner-up:** Claude Code — Seatbelt/Bubblewrap on macOS/Linux
- **Enterprise need:** Docker/container isolation for maximum security (only Copilot CLI offers this)
- **Recommendation:** Layer approach: OS-level sandbox (Seatbelt/bubblewrap) + optional Docker for high-security environments

### 2. Timeout Strategy
- **Best approach:** Codex CLI — per-call timeout_ms parameter with worker-level max
- **Anti-pattern:** Cline's hard 30-second non-configurable timeout (causes widespread issues)
- **Recommendation:** Default 2 min, per-call override up to 10 min, global config for defaults, worker timeout for sub-agents

### 3. Output Handling
- **Best approach:** Codex CLI — begin+end truncation preserving UTF-8 boundaries with line count header
- **Anti-pattern:** Cursor's tail-20-lines approach (loses important early output)
- **Recommendation:** Configurable truncation with begin+end split, total line count, and optional streaming to agent for long outputs

### 4. Command Safety
- **Best approach:** Codex CLI's Guardian system with typed approval requests + Claude Code's hooks
- **Enterprise must-have:** Audit logging (Amazon Q is only one with mandatory CloudWatch logging)
- **Recommendation:** Multi-layer: allowlist/denylist regex + hooks for custom gates + audit logging + approval modes

### 5. Environment Isolation
- **Best approach:** Codex CLI's shell environment policy (inherits_from, excludes, includes, overrides)
- **Anti-pattern:** Amazon Q's isolated shell missing user config
- **Recommendation:** Inherit user shell config but filter/override sensitive env vars

### 6. Background Execution
- **Best approach:** Claude Code's `run_in_background` + `BashOutput` + `KillBash` lifecycle
- **Known pitfalls:** Background process tracking can be lost after long sessions, infinite system-reminder loops
- **Recommendation:** Explicit process registry with IDs, timeout enforcement, output buffering, clean kill

### 7. Working Directory
- **Best approach:** Copilot CLI's per-session CWD with monorepo directory-level config discovery
- **Recommendation:** Persist CWD across calls, support monorepo-aware config inheritance

---

## SOURCES

### Claude Code
- [Bash tool API docs](https://platform.claude.com/docs/en/agents-and-tools/tool-use/bash-tool)
- [Sandboxing docs](https://code.claude.com/docs/en/sandboxing)
- [Timeout configuration guide (issue #5615)](https://github.com/anthropics/claude-code/issues/5615)
- [Background process token exhaustion (issue #11716)](https://github.com/anthropics/claude-code/issues/11716)
- [dangerouslyDisableSandbox security (issue #14268)](https://github.com/anthropics/claude-code/issues/14268)
- [System prompts (Piebald-AI)](https://github.com/Piebald-AI/claude-code-system-prompts)
- [Under the hood (pierce.dev)](https://pierce.dev/notes/under-the-hood-of-claude-code)

### Cursor
- [Terminal tool docs](https://cursor.com/docs/agent/tools/terminal)
- [sandbox.json reference](https://cursor.com/docs/reference/sandbox)
- [Sandbox analysis report](https://agent-safehouse.dev/docs/agent-investigations/cursor-agent)
- [Sandbox explained (adwaitx)](https://www.adwaitx.com/cursor-ai-agent-sandboxing-explained/)
- [Output truncation bug](https://forum.cursor.com/t/composer-output-tail-truncation/142084)
- [Tool call timeout 200000ms](https://forum.cursor.com/t/tool-call-timed-out-after-200000ms-and-terminal-stuck/71776)
- [Allowlist ignored with sandbox](https://forum.cursor.com/t/command-allowlist-is-silently-ignored-when-auto-run-in-sandbox-is-enabled/152136)

### Cline
- [Terminal Integration (DeepWiki)](https://deepwiki.com/cline/cline/5.2-terminal-integration)
- [Shell Integration docs](https://docs.cline.bot/features/commands-and-shortcuts/terminal-integration)
- [30-second timeout issue (#8154)](https://github.com/cline/cline/issues/8154)
- [Shell integration timeout PR (#2478)](https://github.com/cline/cline/pull/2478/files)
- [Terminal fix blog post](https://cline.bot/blog/cline-v3-18-1-4-we-fixed-the-terminal)

### Codex CLI
- [Configuration reference](https://developers.openai.com/codex/config-reference)
- [Advanced configuration](https://developers.openai.com/codex/config-advanced)
- [Security docs](https://developers.openai.com/codex/security)
- [Agent approvals & security](https://developers.openai.com/codex/agent-approvals-security)
- [Sandboxing implementation (DeepWiki)](https://deepwiki.com/openai/codex/5.6-sandboxing-implementation)
- [Linux sandbox README](https://github.com/openai/codex/blob/main/codex-rs/linux-sandbox/README.md)
- [Definitive technical reference](https://blakecrosley.com/guides/codex)

### Windsurf
- [Terminal docs](https://docs.windsurf.com/windsurf/terminal)
- [Cascade docs](https://docs.windsurf.com/windsurf/cascade/cascade)
- [Turbo mode announcement](https://x.com/windsurf_ai/status/1891981446698656142)
- [Command loop issue (#153)](https://github.com/Exafunction/codeium/issues/153)

### Amazon Q Developer CLI
- [Built-in tools docs](https://docs.aws.amazon.com/amazonq/latest/qdeveloper-ug/command-line-built-in-tools.html)
- [GitHub source](https://github.com/aws/amazon-q-developer-cli)
- [Built-in tools.md](https://github.com/aws/amazon-q-developer-cli/blob/main/docs/built-in-tools.md)
- [Shell environment issue (#1030)](https://github.com/aws/amazon-q-developer-cli/issues/1030)
- [Sandbox feature request (#2336)](https://github.com/aws/amazon-q-developer-cli/issues/2336)

### GitHub Copilot CLI
- [GA announcement](https://github.blog/changelog/2026-02-25-github-copilot-cli-is-now-generally-available/)
- [Enhanced agents changelog](https://github.blog/changelog/2026-01-14-github-copilot-cli-enhanced-agents-context-management-and-new-ways-to-install/)
- [Copilot CLI source](https://github.com/github/copilot-cli)
- [Docker sandbox docs](https://docs.docker.com/ai/sandboxes/agents/copilot/)
- [VS Code sandboxing issue (#290620)](https://github.com/microsoft/vscode/issues/290620)
- [CVE-2026-29783 advisory](https://advisories.gitlab.com/pkg/npm/@github/copilot/CVE-2026-29783/)
- [Secure Docker sandbox guide](https://gordonbeeming.com/blog/2025-10-03/taming-the-ai-my-paranoid-guide-to-running-copilot-cli-in-a-secure-docker-sandbox)
- [Agent sandbox deep dive](https://pierce.dev/notes/a-deep-dive-on-agent-sandboxes)
