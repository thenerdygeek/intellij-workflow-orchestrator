# Deep Source Code Analysis: Agentic Shell Execution Implementations

Research date: 2026-03-27
Sources: Direct source code analysis of cloned repositories + web research

---

## 1. CLAUDE CODE — Bash Tool

### Open Source Status
- **Repo:** https://github.com/anthropics/claude-code
- **Source availability:** The repository does NOT contain the core agent source code. It only has: `plugins/`, `examples/`, `scripts/` (GitHub automation), and documentation files.
- **The actual BashTool is closed-source** — shipped as a compiled npm package.

### Architecture (from docs + system prompt analysis)
- **Shell session model:** Persistent bash/zsh session where cwd persists between calls, but shell state (env vars, aliases) resets
- **Shell init:** Loaded from user's profile (bash or zsh)
- **Sandboxing:**
  - macOS: `sandbox-exec` (Seatbelt framework) — generates SBPL policy files
  - Linux: Bubblewrap (`bwrap`) — filesystem namespace isolation
- **BashTool optimization:** Skips login shell (`-l` flag) when shell snapshot is available, improving performance
- **No reusable library extracted** — the Bash tool is tightly coupled to the Claude Code agent loop

### Key Design Decisions
- Background execution via `run_in_background` flag — returns a shell_id for later `BashOutput`/`KillBash`
- Description parameter is REQUIRED — forces the LLM to explain what it's doing
- Output truncation: ~30K chars, no begin+end split
- Default timeout: 120s, max 10 minutes

---

## 2. CURSOR — Terminal Tool

### Open Source Status
- **Closed source.** Cursor is a fork of VS Code but proprietary.

### Architecture (from docs + community analysis)
- Uses VS Code's integrated terminal with shell integration signals for command completion detection
- Isolated agent-created terminals separate from normal workspace
- `CURSOR_TRACE_ID` environment variable used to identify Cursor sessions
- Shell integration issues with Powerlevel10k/Oh-My-Zsh (agent expects specific completion signals)
- sandbox.json for network/filesystem policies
- No OS-level sandbox (policy-file based only)

### Key Design Decisions
- Heavily relies on VS Code shell integration API for output capture
- Terminal completion detection via shell integration hooks (can break with custom prompts)
- No standalone component extractable

---

## 3. CLINE — execute_command Tool

### Open Source Status
- **Fully open source:** https://github.com/cline/cline (TypeScript, VS Code extension)

### Architecture (from source code)

**Two terminal backends:**

1. **VscodeTerminalProcess** (`src/hosts/vscode/terminal/VscodeTerminalProcess.ts`)
   - Uses VS Code Shell Integration API (`vscode.window.onDidEndTerminalShellExecution`)
   - Falls back to reading terminal buffer directly when shell integration unavailable
   - Emits `line`, `completed`, `continue`, `error`, `no_shell_integration` events

2. **StandaloneTerminalProcess** (`src/integrations/terminal/standalone/StandaloneTerminalProcess.ts`)
   - Uses Node.js `child_process.spawn()` with detached process groups
   - stdin disabled (`"ignore"`) to prevent interactivity
   - Environment: sets `PAGER=cat`, `GIT_PAGER=cat`, `SYSTEMD_PAGER=""`, `MANPAGER=cat`, `EDITOR=cat`
   - Output streaming via stdout/stderr `data` events
   - Process tree termination via `terminateProcessTree()` utility
   - EventEmitter pattern: `line`, `completed`, `continue`, `error`

**CommandExecutor** (`src/integrations/terminal/CommandExecutor.ts`)
   - Unified executor that delegates to either VscodeTerminalManager or StandaloneTerminalManager
   - Two modes: `vscodeTerminal` (visible in IDE) or `backgroundExec` (hidden child_process)
   - Tracks current process for cancellation support

**ExecuteCommandToolHandler** (`src/core/task/tools/handlers/ExecuteCommandToolHandler.ts`)
   - Recognizes long-running commands (npm install, cargo build, pytest, etc.) via regex patterns
   - Default timeout: 30s for normal commands, 300s for long-running patterns
   - Command permission validation via `CommandPermissionController`
   - Uses `shell-quote` npm package for command parsing and safety analysis
   - Multi-workspace support with `@workspace:command` prefix syntax
   - LLM provides `requires_approval` boolean parameter

**CommandPermissionController** (`src/core/permissions/CommandPermissionController.ts`)
   - Parses commands using `shell-quote` npm package
   - Detects redirect operators (`>`, `>>`, `<`, `>&`, etc.)
   - Detects command separators (`&&`, `||`, `|`, `;`)
   - Validates each segment against allow/deny glob patterns
   - Recursively validates subshell contents `(...)` and `$(...)`
   - Config via `CLINE_COMMAND_PERMISSIONS` env var

### Key Libraries Used
- `shell-quote` (npm) — command parsing for permission validation
- `child_process.spawn` (Node.js built-in) — process execution
- VS Code Shell Integration API — terminal output capture
- EventEmitter pattern for output streaming

### Reusability Assessment
The `StandaloneTerminalProcess` class is relatively self-contained and could be adapted. The `CommandPermissionController` with `shell-quote` is a good pattern. However, everything is deeply coupled to VS Code extension APIs.

---

## 4. AIDER — Shell Command Execution

### Open Source Status
- **Fully open source:** https://github.com/Aider-AI/aider (Python)

### Architecture (from source code: `aider/run_cmd.py`)

**Two execution backends:**

1. **pexpect backend** (preferred on Unix when TTY available)
   - Uses `pexpect.spawn(shell, args=["-i", "-c", command])` with `-i` (interactive) flag
   - Captures output via `child.interact(output_filter=output_callback)` — interactive mode with output filter
   - Detects user's shell from `$SHELL` env var, falls back to `/bin/sh`
   - Exit status via `child.exitstatus`
   - Library: `pexpect` (PyPI)

2. **subprocess backend** (fallback, used on Windows or non-TTY)
   - Uses `subprocess.Popen` with `shell=True`, `bufsize=0` (unbuffered)
   - Character-by-character reading: `process.stdout.read(1)` in a loop
   - Real-time output printing: `print(chunk, end="", flush=True)`
   - Windows: detects parent process (PowerShell vs cmd.exe) via `psutil`
   - Encoding: uses `sys.stdout.encoding` with `errors="replace"`

**Shell command integration (NOT a tool — suggestion only):**
Aider does NOT have an "execute_command" tool. Instead:
- `aider/coders/shell.py` contains prompt templates for SUGGESTING shell commands
- LLM suggests commands in ```bash blocks
- User manually executes or approves
- No automatic execution, no sandbox, no timeout

### Key Libraries Used
- `pexpect` (PyPI) — PTY-based interactive process control
- `psutil` (PyPI) — process tree inspection (Windows parent detection)
- `subprocess` (Python built-in) — fallback process execution

### Reusability Assessment
The `run_cmd.py` is a clean, reusable 133-line module. The pexpect/subprocess dual-backend pattern is practical. However, it's Python-only and has no sandboxing.

---

## 5. OPENHANDS (formerly OpenDevin) — CmdRunAction

### Open Source Status
- **Fully open source:** https://github.com/All-Hands-AI/OpenHands (Python)
- **V1 SDK:** https://github.com/OpenHands/software-agent-sdk

### Architecture (from source code)

**V0 (Legacy) — `openhands/runtime/utils/bash.py`:**
- **Uses `libtmux`** (Python tmux wrapper) as the terminal multiplexer
- `BashSession` class creates a tmux server, session, and window
- Commands sent by writing to tmux pane, output read by polling pane content
- Custom PS1 prompt (`CmdOutputMetadata.to_ps1_prompt()`) for detecting command completion
- Command parsing via `bashlex` (Python bash AST parser) for splitting compound commands
- Escape handling for bash special chars within heredocs, quotes, command substitutions
- `BashCommandStatus` enum: `CONTINUE`, `COMPLETED`, `NO_CHANGE_TIMEOUT`, `HARD_TIMEOUT`
- Polling interval: 0.5 seconds
- History limit: 10,000 lines per tmux session
- Session naming: `openhands-{username}-{uuid4}`
- User switching via `su {username} -` for non-login shell

**Docker Runtime** (`openhands/runtime/impl/docker/docker_runtime.py`):
- Spins up a Docker container with an HTTP action execution server inside
- Commands sent as HTTP requests to the container's server
- Full filesystem isolation via Docker
- Supports Kubernetes deployment for scaling

**V1 SDK Architecture:**
- Event-sourced state model with deterministic replay
- Typed tool system with `TerminalTool` component
- Uses `libtmux` for session management + `bashlex` for command parsing
- Workspace abstraction: same agent code runs locally or in containers

### Key Libraries Used
- `libtmux` (PyPI) — tmux session management (the core terminal bridge)
- `bashlex` (PyPI) — bash command AST parsing
- Docker SDK — container lifecycle management
- HTTP client (httpx) — communication with action execution server in container

### Reusability Assessment
The `libtmux`-based `BashSession` is a solid pattern for persistent shell sessions. The tmux approach solves the "persistent state" problem elegantly. However, it requires tmux to be installed and is Python-only. The Docker runtime is the most production-ready sandbox approach.

---

## 6. SWE-AGENT — SWE-ReX Runtime

### Open Source Status
- **Fully open source:** https://github.com/SWE-agent/SWE-agent (Python)
- **SWE-ReX (standalone library):** https://github.com/SWE-agent/SWE-ReX

### Architecture (from source code: `sweagent/environment/swe_env.py`)

**SWE-ReX is the KEY STANDALONE LIBRARY here.**

`SWEEnv` class delegates ALL execution to SWE-ReX:
- `swerex.deployment.abstract.AbstractDeployment` — deployment abstraction (Docker, local, AWS, Modal)
- `swerex.runtime.abstract.BashAction` — execute bash command
- `swerex.runtime.abstract.BashInterruptAction` — interrupt running command
- `swerex.runtime.abstract.CreateBashSessionRequest` — create persistent bash session
- `swerex.runtime.abstract.ReadFileRequest/WriteFileRequest` — file operations

**Communicate method:**
```python
def communicate(self, input, timeout=25, check="ignore"):
    r = asyncio.run(
        self.deployment.runtime.run_in_session(
            BashAction(command=input, timeout=timeout, check=check)
        )
    )
    return r.output  # includes r.exit_code
```

**SWE-ReX Features:**
- Deployment-agnostic: same code for local, Docker, AWS, Modal
- Massively parallel execution
- Interactive shell support (ipython, gdb) via multiple sessions
- Automatic command completion detection
- Output + exit code extraction
- Interrupt support
- Default deployment: `DockerDeploymentConfig(image="python:3.11")`

### Key Libraries Used
- `swerex` (PyPI) — **THE standalone terminal execution library for agents**
- Docker SDK (via SWE-ReX) — container deployment
- `asyncio` — async runtime

### Reusability Assessment
**SWE-ReX is the most reusable standalone library found in this research.** It provides:
- Clean abstraction over deployment targets
- Persistent bash sessions with completion detection
- Parallel session support
- Interrupt capability
- Proper timeout handling
However, it is Python-only with no JVM/Kotlin bindings.

---

## 7. CODEX CLI (OpenAI) — Shell Tool

### Open Source Status
- **Fully open source:** https://github.com/openai/codex (Rust)

### Architecture (from source code)

**This is the most sophisticated implementation found.**

**Crate structure (within `codex-rs/`):**
- `core/src/exec.rs` — Core process execution engine
- `core/src/tools/handlers/shell.rs` — Shell tool handler
- `core/src/tools/runtimes/shell.rs` — Shell runtime with approval flow
- `core/src/tools/runtimes/shell/zsh_fork_backend.rs` — Optimized zsh-fork backend
- `core/src/tools/runtimes/shell/unix_escalation.rs` — Privilege escalation
- `core/src/tools/sandboxing.rs` — Sandbox orchestration with approval caching
- `sandboxing/src/` — Platform sandbox implementations
- `utils/pty/src/` — PTY and pipe process spawning utilities
- `shell-escalation/` — Shell escalation via Unix domain sockets

**Process Spawning (`codex-rs/utils/pty/src/`):**
```
lib.rs exports:
  - spawn_pipe_process — Non-interactive via regular pipes
  - spawn_pipe_process_no_stdin — Pipes with stdin closed
  - spawn_pty_process — Interactive with PTY
  - ProcessHandle — Handle for interacting with spawned process
  - SpawnedProcess — Bundle of handles + output receivers
  - TerminalSize — For PTY resize operations
  - combine_output_receivers — Merge stdout/stderr into single broadcast
```

**Execution Engine (`core/src/exec.rs`):**
- Uses `tokio::process::Child` for async process management
- `ExecParams`: command (Vec<String>), cwd, expiration, capture_policy, env, network, sandbox_permissions
- Default timeout: 10,000ms (`DEFAULT_EXEC_COMMAND_TIMEOUT_MS`)
- Output cap: `DEFAULT_OUTPUT_BYTES_CAP` = 1MB
- Streaming: `ExecCommandOutputDeltaEvent` events, max 10,000 deltas per call
- Read chunk size: 8,192 bytes
- IO drain timeout: 2,000ms for pipe cleanup after process exit
- Smart handling of grandchild processes inheriting stdout/stderr FDs
- Exit code handling: SIGKILL=9, TIMEOUT=64, signal base=128, exec timeout=124
- UTF-8 boundary preservation on truncation (`bytes_to_string_smart`)

**Sandbox Manager (`sandboxing/src/manager.rs`):**
- `SandboxType` enum: `None`, `MacosSeatbelt`, `LinuxSeccomp`, `WindowsRestrictedToken`
- `SandboxCommand`: program + args + cwd + env + permissions
- Platform detection: macOS -> Seatbelt, Linux -> Seccomp/Landlock, Windows -> RestrictedToken
- macOS: Generates SBPL policy files dynamically from `seatbelt_base_policy.sbpl`
- Linux: Bubblewrap + Landlock LSM for filesystem, Seccomp for syscalls
- Windows: Restricted process tokens
- Network proxy integration for controlled egress

**Shell Runtime (`core/src/tools/runtimes/shell.rs`):**
- Three backend modes: `Generic`, `ShellCommandClassic`, `ShellCommandZshFork`
- `ShellRequest` struct: command, cwd, timeout_ms, env, network, sandbox_permissions, justification
- Approval caching: `ApprovalKey` hashed by (command, cwd, sandbox_permissions, additional_permissions)
- Guardian approval system for interactive permission requests
- Network approval integration
- PowerShell UTF-8 prefix support

**Zsh-Fork Backend (`shell/zsh_fork_backend.rs`):**
- Uses `codex-shell-escalation` crate with Unix domain socket communication
- `EscalationSession` for privilege management
- File descriptor inheritance for socket passing
- Post-spawn cleanup of client sockets

### Key Libraries/Crates Used
- `tokio` — Async runtime, process management
- `codex-sandboxing` (internal crate) — Platform sandbox abstractions
- `codex-utils-pty` (internal crate) — PTY/pipe process spawning
- `codex-shell-escalation` (internal crate) — Unix escalation via UDS
- `codex-network-proxy` (internal crate) — Network egress control
- `async-channel` — Output streaming channels
- `tokio-util` — CancellationToken for process lifecycle

### Reusability Assessment
Codex has the most modular architecture with separate internal crates. However:
- All crates are internal to the Codex monorepo, not published to crates.io
- Deeply coupled to Codex's approval/guardian system
- Rust-only, no JVM bindings
- The `codex-utils-pty` and `codex-sandboxing` crates could theoretically be extracted but would need significant decoupling

---

## 8. CONTINUE.DEV — runTerminalCommand Tool

### Open Source Status
- **Fully open source:** https://github.com/continuedev/continue (TypeScript)

### Architecture (from source code: `core/tools/implementations/runTerminalCommand.ts`)

**Single implementation using Node.js child_process:**
- Uses `childProcess.spawn(shell, args)` with login shell
- Shell detection: Unix uses `$SHELL || /bin/bash` with `-l -c` flags; Windows uses `powershell.exe`
- Default timeout: 120,000ms (2 minutes)
- Graceful termination: SIGTERM first, then SIGKILL after 5 seconds
- Encoding: `iconv-lite` for cross-platform decoding (UTF-8 with GBK fallback on Windows)
- Color support: Forces `FORCE_COLOR=1`, `COLORTERM=truecolor`, `TERM=xterm-256color`
- Background execution: `waitForCompletion: false` resolves immediately, detaches with `childProc.unref()`
- Process tracking: `markProcessAsRunning`/`removeRunningProcess`/`isProcessBackgrounded` for lifecycle management
- Remote environments: Delegates to `ide.runCommand()` which routes through VS Code terminal

**Output Streaming:**
- Partial output via `extras.onPartialOutput()` callback
- Both stdout and stderr streamed as `contextItems` with status messages
- Background process check via `isProcessBackgrounded(toolCallId)` — skips output if backgrounded

**Process Lifecycle:**
- `toolCallId`-based tracking for foreground/background processes
- Cancellation via process tracking system
- Status messages: "Command completed", "Command failed with exit code X", "Command timed out", "Command is running in the background..."

### Key Libraries Used
- `child_process` (Node.js built-in) — process spawning
- `iconv-lite` (npm) — encoding handling (especially for CJK on Windows)
- VS Code IDE API — for remote terminal delegation

### Reusability Assessment
The implementation is clean and straightforward (~570 lines). The pattern is simple: spawn + stream + timeout + background support. However, it's Node.js-specific and lacks sandboxing entirely.

---

## 9. AMAZON Q DEVELOPER CLI — execute_bash Tool

### Open Source Status
- **Open source (archived):** https://github.com/aws/amazon-q-developer-cli (Rust)
- **Note:** No longer actively maintained; succeeded by Kiro CLI (closed-source)

### Architecture (from source code: `crates/chat-cli/src/cli/chat/tools/execute/`)

**Platform-specific implementations:**

**Unix (`execute/unix.rs`):**
- Uses `tokio::process::Command::new(shell).arg("-c").arg(command)`
- Shell from `get_chat_shell()` (configurable)
- Stdin: `Stdio::inherit()` (allows interactive commands)
- Stdout/stderr: `Stdio::piped()` for capture
- Streaming: `tokio::io::BufReader::new(stdout).lines()` with `tokio::select!` biased for stdout
- Output buffer: `VecDeque` with 1,024 line ring buffer (circular, drops oldest)
- Real-time output: writes to `updates` writer while buffering
- Env vars include user-agent metadata for CloudTrail tracking

**Windows (`execute/windows.rs`):**
- Similar tokio-based approach
- PowerShell as default shell

**Command Safety (`execute/mod.rs`):**
- Read-only command allowlist: `ls`, `cat`, `echo`, `pwd`, `which`, `head`, `tail`, `find`, `grep`, `dir`, `type`
- Dangerous pattern detection: `<(`, `$(`, backtick, `>`, `&&`, `||`, `&`, `;`, `$`, newlines, `IFS`
- Multi-command awareness: splits by pipe `|` and validates each segment
- Command parsing via `shlex::split()` (Rust shlex crate)
- Configurable `allowedCommands`/`deniedCommands` regex
- Tool permission checker: `is_tool_in_allowlist()`
- Max tool response size: `MAX_TOOL_RESPONSE_SIZE` (truncation)

### Key Libraries/Crates Used
- `tokio` — Async process management
- `shlex` (Rust crate) — shell command parsing
- `crossterm` — terminal styling
- `regex` — command pattern matching
- `eyre` — error handling

### Reusability Assessment
The execute module is reasonably clean but tightly coupled to the Amazon Q CLI framework. The tokio-based streaming pattern and shlex-based safety checks are good patterns. Rust-only, no JVM bindings.

---

## STANDALONE REUSABLE LIBRARIES FOUND

### 1. SWE-ReX (Python) — BEST STANDALONE OPTION
- **Repo:** https://github.com/SWE-agent/SWE-ReX
- **PyPI:** `swerex`
- **Purpose:** Sandboxed code execution for AI agents
- **Features:** Deployment-agnostic (local/Docker/AWS/Modal), persistent bash sessions, parallel sessions, command completion detection, interrupt support, file operations
- **Architecture:** Client-server model — server runs in deployment, client communicates via API
- **Limitation:** Python-only

### 2. pty4j (JVM) — BEST JVM OPTION FOR RAW PTY
- **Repo:** https://github.com/JetBrains/pty4j
- **Maven:** `org.jetbrains.pty4j:pty4j`
- **Purpose:** Pseudo-terminal implementation for Java
- **Platforms:** Linux, macOS, Windows (ConPTY)
- **Used by:** All JetBrains IDEs for terminal integration
- **Limitation:** Low-level PTY only — no session management, no sandboxing, no agent-aware features

### 3. Koog (JVM) — JetBrains AI Agent Framework
- **Repo:** https://github.com/JetBrains/koog
- **Purpose:** JVM framework for building AI agents
- **Features:** Includes `ExecuteShellCommandTool` component
- **Limitation:** Full framework, not a standalone terminal library

### 4. node-pty (Node.js) — Raw PTY for Node
- **npm:** `node-pty`
- **Purpose:** Fork pseudoterminals in Node.js
- **Used by:** VS Code terminal, many tools indirectly
- **Limitation:** Low-level PTY only

### 5. pexpect (Python) — Interactive Process Control
- **PyPI:** `pexpect`
- **Purpose:** Spawn and control interactive processes
- **Used by:** Aider for its preferred Unix backend
- **Limitation:** No sandboxing, Python-only

### 6. libtmux (Python) — tmux Session Management
- **PyPI:** `libtmux`
- **Purpose:** Programmatic tmux control
- **Used by:** OpenHands for persistent shell sessions
- **Limitation:** Requires tmux installed, Python-only

### 7. bashlex (Python) — Bash AST Parser
- **PyPI:** `bashlex`
- **Purpose:** Parse bash commands into AST for safety analysis
- **Used by:** OpenHands for command splitting

### 8. shell-quote (npm) — Shell Command Parser
- **npm:** `shell-quote`
- **Purpose:** Parse and quote shell commands
- **Used by:** Cline for permission validation

---

## WHAT DOES NOT EXIST (Gap Analysis)

**No standalone "agentic terminal bridge" library exists that provides:**
1. Cross-language support (JVM + Node.js + Python)
2. Agent-aware features (timeout, output capping, streaming, approval hooks)
3. Built-in sandboxing (OS-level or container)
4. Persistent session management
5. Process lifecycle management (background, kill, cancel)

Every tool builds its own from scratch using platform primitives:
- Node.js: `child_process.spawn` + EventEmitter
- Python: `subprocess.Popen` or `pexpect.spawn` or `libtmux`
- Rust: `tokio::process::Command` + async channels
- JVM: `ProcessBuilder` or `pty4j`

---

## RECOMMENDATIONS FOR INTELLIJ PLUGIN IMPLEMENTATION

### Architecture Pattern (synthesized from all 9 tools)

```
┌─────────────────────────────────────────┐
│         Agent Tool Interface            │
│  (ToolResult<ShellOutput>)              │
├─────────────────────────────────────────┤
│         Command Safety Layer            │
│  - Allow/deny patterns (like Codex)     │
│  - Dangerous pattern detection          │
│  - Approval hooks (like Claude Code)    │
│  - Command AST parsing (bashlex-style)  │
├─────────────────────────────────────────┤
│        Execution Engine                 │
│  - pty4j for PTY (like JetBrains IDE)   │
│  - ProcessBuilder for non-interactive   │
│  - Timeout: SIGTERM then SIGKILL        │
│  - Output streaming via channels        │
│  - Output capping (begin+end, Codex)    │
├─────────────────────────────────────────┤
│        Process Lifecycle                │
│  - Foreground/background tracking       │
│  - toolCallId-based (like Continue)     │
│  - Kill/cancel support                  │
│  - Coroutine-based (Dispatchers.IO)     │
├─────────────────────────────────────────┤
│     Optional: Sandbox Layer             │
│  - IntelliJ ProcessHandler integration  │
│  - Docker container option              │
│  - Network policy enforcement           │
└─────────────────────────────────────────┘
```

### Recommended JVM Libraries
1. **pty4j** (`org.jetbrains.pty4j:pty4j`) — PTY support, already a JetBrains dependency
2. **IntelliJ Platform `ProcessHandler`** — IDE-native process management with console integration
3. **IntelliJ `GeneralCommandLine`** — Command line construction with proper escaping
4. **Kotlin Coroutines** — Async execution with `Dispatchers.IO`, cancellation via `Job.cancel()`
5. **Koog** — If building a full agent, JetBrains' own framework has shell tool support

### Key Implementation Details (from source analysis)

**Timeout strategy (Codex pattern):**
- Default: 120s (like Claude Code and Continue)
- Per-call configurable timeout
- Two-phase kill: SIGTERM, wait 5s, then SIGKILL (Continue pattern)
- IO drain timeout: 2s after process exit for pipe cleanup (Codex pattern)

**Output handling (Codex pattern):**
- Cap at 1MB total
- Streaming via async channels with max 10K delta events
- Read chunks of 8KB
- Begin+end truncation preserving UTF-8 boundaries
- Prepend "Total output lines: N" on truncation

**Environment setup (Cline pattern):**
- Set `PAGER=cat`, `GIT_PAGER=cat`, `MANPAGER=cat` to prevent interactive pagers
- Force color: `FORCE_COLOR=1`, `TERM=xterm-256color`
- Inherit user shell from `$SHELL`

**Safety (Amazon Q + Codex pattern):**
- Read-only allowlist: ls, cat, echo, pwd, which, head, tail, find, grep
- Dangerous pattern blocklist: $(), backticks, redirects, IFS, command separators
- Command parsing via AST (not string matching)
- Per-segment validation for compound commands

---

## SOURCES

### Repositories Analyzed (source code)
- Claude Code: https://github.com/anthropics/claude-code (plugins/docs only)
- Cline: https://github.com/cline/cline (full source)
- Codex CLI: https://github.com/openai/codex (full source, Rust)
- Aider: https://github.com/Aider-AI/aider (full source, Python)
- OpenHands: https://github.com/All-Hands-AI/OpenHands (full source, Python)
- SWE-agent: https://github.com/SWE-agent/SWE-agent (full source, Python)
- Continue.dev: https://github.com/continuedev/continue (full source, TypeScript)
- Amazon Q CLI: https://github.com/aws/amazon-q-developer-cli (full source, Rust)

### Standalone Libraries
- [SWE-ReX](https://github.com/SWE-agent/SWE-ReX) — Sandboxed code execution for agents
- [pty4j](https://github.com/JetBrains/pty4j) — JVM pseudo-terminal
- [Koog](https://github.com/JetBrains/koog) — JetBrains AI agent framework
- [node-pty](https://www.npmjs.com/package/node-pty) — Node.js PTY
- [pexpect](https://pypi.org/project/pexpect/) — Python interactive process control
- [libtmux](https://pypi.org/project/libtmux/) — Python tmux wrapper
- [bashlex](https://pypi.org/project/bashlex/) — Python bash parser
- [shell-quote](https://www.npmjs.com/package/shell-quote) — Node.js shell command parser

### Web Research
- [Cursor Terminal Docs](https://cursor.com/docs/agent/tools/terminal)
- [JetBrains Terminal Architecture Blog](https://blog.jetbrains.com/idea/2025/04/jetbrains-terminal-a-new-architecture/)
- [Koog Framework](https://www.jetbrains.com/koog/)
- [SWE-ReX Documentation](https://swe-rex.com/latest/usage/)
- [OpenHands Software Agent SDK](https://github.com/OpenHands/software-agent-sdk)
- [Amazon Q CLI Built-in Tools](https://aws.github.io/amazon-q-developer-cli/built-in-tools.html)
- [Claude Code Bash Tool API](https://platform.claude.com/docs/en/agents-and-tools/tool-use/bash-tool)
- [Agentic Terminal — InfoQ](https://www.infoq.com/articles/agentic-terminal-cli-agents/)
- [Pi Agent Toolkit](https://github.com/agentic-dev-io/pi-agent)
