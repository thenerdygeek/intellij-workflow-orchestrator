# RunCommandTool Redesign — Execution Engine Extraction

**Date:** 2026-04-14
**Status:** Draft
**Branch:** feature/tooling-architecture-enhancements

## Problem Statement

`RunCommandTool.kt` is a 600-line monolith mixing 5 concerns: shell resolution, command safety, process spawning, output collection, and result building. It has:

- Static mutable state (`var streamCallback`, `ThreadLocal<String?>`) that breaks under concurrent sessions
- Unbounded output accumulation in memory (`ConcurrentLinkedQueue<String>` with no cap)
- `sh -c` on macOS/Linux (actually runs zsh on macOS, dash on Linux — not bash)
- Merged stderr with no opt-out
- No environment variable passthrough for the LLM
- Git allowlist checks tangled inside the execution engine (separate concern)
- `CommandSafetyAnalyzer.classify()` called redundantly inside `execute()` (already called in AgentLoop approval gate)
- Other tools (`RuntimeExecTool`, `SonarTool`) duplicating streaming callback patterns

## Goals

1. Split the monolith into focused, independently testable components
2. Fix HIGH-priority execution bugs (shell, output, state management)
3. Remove git-specific checks from the tool (deferred to future pluggable policy layer)
4. Remove redundant `CommandSafetyAnalyzer.classify()` call from `execute()`
5. Keep hard-block safety net (fork bombs, `rm -rf /`, `sudo`, etc.) via `CommandFilter`
6. Add `env` parameter with comprehensive security blocklist
7. Add `separate_stderr` opt-in parameter
8. Prepare architecture for future command policy system

## Non-Goals

- Implementing a full command policy/rejection system (deferred)
- Adding PTY support via pty4j (deferred)
- Session-scoping ProcessRegistry (deferred)
- Persistent shell sessions (one-shot is correct for IDE context)
- Sandbox/Seatbelt integration (out of scope)

## Research Basis

Design informed by source-code analysis of 9 agent tools:

| Tool | Key Pattern Adopted |
|------|-------------------|
| **Claude Code** | File-fd output capture, 30K default result size, safety/execution separation (7 validator files) |
| **Codex CLI** | HeadTailBuffer (50/50 split), process group isolation, env_clear + explicit set, IO drain timeout |
| **Cline** | 1MB memory cap, compilation-aware idle, "Proceed While Running" concept, tree-kill |
| **Goose** | Structured ShellOutput (separate stdout/stderr), temp file spill (rotating slots), Unicode sanitization, LOGIN_PATH recovery |
| **OpenHands** | 30K char middle truncation, no-change timeout |
| **SWE-agent** | bashlex validation (not adopted — too heavy for JVM plugin) |
| **Amazon Q** | Unicode TAG character stripping for prompt injection prevention |
| **Cursor** | `is_background` parameter, `command_status` tool |
| **Windsurf** | `WaitMsBeforeAsync` (not adopted — marginal benefit over idle detection) |

**Industry consensus patterns adopted:**
- Head+tail truncation is universal (50/50 by line count)
- Safety always separated from execution
- Disk spill is standard for large output
- stdin must be open-but-empty (not closed — breaks heredocs)
- Process group isolation prevents orphans
- Pager suppression is universal (`PAGER=cat`, `GIT_PAGER=cat`)
- One-shot process model is correct for IDE context
- `run_command` should always require per-invocation approval

## Architecture

### Component Decomposition

```
RunCommandTool (~150 lines, thin orchestrator)
│
├── ShellResolver (~120 lines)
│   Input: requestedShell, project
│   Output: ShellConfig(executable, args, shellType, displayName)
│   Stateless, pure function
│   Moved from: findGitBash(), findPowerShell(), detectAvailableShells(),
│               shell-matching when block (lines 341-407)
│
├── CommandFilter (interface + DefaultCommandFilter, in security/ package)
│   Input: command, shellType
│   Output: Allow / Reject(reason)
│   DefaultCommandFilter: hard-block regexes only (fork bombs, rm -rf /, sudo, etc.)
│   Moved from: HARD_BLOCKED list, isHardBlocked()
│   Future: CompositeCommandFilter with pluggable filters
│
├── ProcessExecutor (~200 lines)
│   Input: command, ShellConfig, env, timeout, workDir, toolCallId, streamCallback
│   Output: ExecutionResult (Exit/Timeout/Idle with ManagedProcess)
│   Manages: spawn via GeneralCommandLine, coroutine monitor loop, kill
│   Registers/unregisters from ProcessRegistry
│   Moved from: process spawning + monitor loop (lines 337-512)
│
├── OutputCollector (~150 lines)
│   Input: Process, maxMemoryBytes, separateStderr
│   Output: CollectedOutput(content, totalBytes, totalLines, fullPath, wasTruncated)
│   Reader thread with streaming callback
│   In-memory: 50% head + 50% tail by line count (1MB cap)
│   Disk spill to session temp directory
│   ANSI stripping + Unicode sanitization for LLM context
│   Moved from: reader thread (lines 444-467), collectOutput(), buildExitResult()
│
└── (CommandSafetyAnalyzer stays in security/ package, unchanged)
    Only called from AgentLoop.assessRisk() — NOT from RunCommandTool
```

### Execution Pipeline

```
RunCommandTool.execute(params, project, toolCallId, streamCallback)
  │
  ├─ 1. Parse parameters (command, shell, working_dir, env, timeout, etc.)
  │
  ├─ 2. ShellResolver.resolve(requestedShell, project) → ShellConfig
  │     Error if requested shell unavailable (lists available shells)
  │
  ├─ 3. CommandFilter.check(command, shellType) → Allow / Reject
  │     Reject → return ToolResult(isError=true, reason)
  │     Allow → continue
  │
  ├─ 4. ProcessExecutor.execute(command, shellConfig, env, timeout, workDir, ...)
  │     → ExecutionResult.Exit(managed, exitCode)
  │     → ExecutionResult.Timeout(managed, timeoutSeconds)
  │     → ExecutionResult.Idle(managed, idleSeconds)
  │
  └─ 5. Build ToolResult from ExecutionResult + OutputCollector
        Exit → content with exit code + truncated output
        Timeout → [TIMEOUT] marker + partial output
        Idle → [IDLE] marker + last 10 lines + process ID + interaction options
```

## Component Details

### 1. ShellResolver

```kotlin
// agent/tools/process/ShellResolver.kt

data class ShellConfig(
    val executable: String,       // e.g., "C:\\Program Files\\Git\\bin\\bash.exe"
    val args: List<String>,       // e.g., ["-c"] or ["/c"] or ["-NoProfile", "-NonInteractive", "-Command"]
    val shellType: ShellType,     // BASH, CMD, POWERSHELL
    val displayName: String       // "Git Bash", "PowerShell 7", "cmd.exe"
)

enum class ShellType { BASH, CMD, POWERSHELL }

object ShellResolver {
    fun resolve(requestedShell: String?, project: Project?): ShellConfig
    fun detectAvailableShells(project: Project?): List<ShellConfig>
    fun findGitBash(): String?       // moved from RunCommandTool
    fun findPowerShell(): String?    // moved from RunCommandTool
}
```

**Resolution order (Windows-first):**

| Priority | Windows | macOS/Linux |
|----------|---------|-------------|
| 1st | Git Bash (if installed) | `/bin/bash` (if exists) |
| 2nd | PowerShell 7+ (`pwsh.exe`) | `$SHELL` (user's login shell) |
| 3rd | Windows PowerShell 5.1 | `/bin/sh` (POSIX fallback) |
| 4th | `cmd.exe` (always available) | — |

**When LLM specifies a shell:** Honor the request, validate availability. If unavailable, return error listing available shells.

**Settings integration:** `AgentSettings.preferredShell` (new field) overrides detection when set. `AgentSettings.powershellEnabled` toggle respected (existing).

**Login shell on Unix:** Use `-l` flag for bash/zsh to recover full PATH (Goose pattern — prevents missing PATH entries when IDE launched from desktop).

### 2. CommandFilter

```kotlin
// agent/security/CommandFilter.kt

interface CommandFilter {
    fun check(command: String, shellType: ShellType): FilterResult
}

sealed class FilterResult {
    object Allow : FilterResult()
    data class Reject(val reason: String) : FilterResult()
}

// agent/security/DefaultCommandFilter.kt

class DefaultCommandFilter : CommandFilter {
    // Hard-block regexes moved from RunCommandTool.HARD_BLOCKED
    // These commands are NEVER allowed regardless of approval
}
```

**What moves here from RunCommandTool:**
- `HARD_BLOCKED` regex list (14 patterns: fork bombs, `rm -rf /`, `sudo`, `mkfs`, `dd if=`, `curl|sh`, etc.)
- `isHardBlocked()` / `isBlocked()` methods

**What is REMOVED entirely:**
- `checkGitCommand()` — git safety is handled by `CommandSafetyAnalyzer` in the approval gate
- `SAFE_GIT_SUBCOMMANDS`, `DANGEROUS_GIT_FLAGS` constants

**No `NeedsApproval` state** — approval is handled by `AgentLoop.assessRisk()` via `CommandSafetyAnalyzer.classify()`. The filter only does hard-blocks (never run, no override possible).

**Future extensibility (not built now):**
```kotlin
class CompositeCommandFilter(private val filters: List<CommandFilter>) : CommandFilter {
    override fun check(command: String, shellType: ShellType): FilterResult {
        for (filter in filters) {
            val result = filter.check(command, shellType)
            if (result is FilterResult.Reject) return result
        }
        return FilterResult.Allow
    }
}
```

### 3. ProcessExecutor

```kotlin
// agent/tools/process/ProcessExecutor.kt

sealed class ExecutionResult {
    data class Exit(val managed: ManagedProcess, val exitCode: Int) : ExecutionResult()
    data class Timeout(val managed: ManagedProcess, val timeoutSeconds: Long) : ExecutionResult()
    data class Idle(val managed: ManagedProcess, val idleSeconds: Long) : ExecutionResult()
}

object ProcessExecutor {
    suspend fun execute(
        command: String,
        shellConfig: ShellConfig,
        workDir: File,
        envOverrides: Map<String, String>,
        timeoutSeconds: Long,
        idleThresholdMs: Long,
        toolCallId: String,
        streamCallback: ((toolCallId: String, chunk: String) -> Unit)?,
        separateStderr: Boolean = false
    ): ExecutionResult
}
```

**Process spawning:**
- Uses `GeneralCommandLine` (IntelliJ-native)
- stdin: open but empty (default ProcessBuilder behavior — NOT closed, to support heredocs/pipes)
- stdout/stderr: merged by default; separate capture when `separateStderr=true`
- Process group isolation via `ProcessHandle.descendants()` for cleanup (current pattern, proven)

**Environment handling:**
1. Inherit user environment (ProcessBuilder default)
2. Strip sensitive variables (`SENSITIVE_ENV_VARS` — expanded list, see Security section)
3. Apply anti-interactive overrides (`ANTI_INTERACTIVE_ENV` — expanded list)
4. Apply LLM-provided `envOverrides` (filtered through `BLOCKED_ENV_VARS` blocklist)

**Monitor loop:**
- 500ms coroutine delay loop (current pattern)
- `coroutineContext.ensureActive()` for instant cancellation on Stop
- Priority: process exit > total timeout > idle detection
- IO drain timeout: 2s after exit/kill for pipe cleanup

**Two-phase kill:**
- SIGTERM (graceful, 5s window for cleanup — lets Gradle/Maven release locks)
- SIGKILL if still alive after 5s
- Child process tree killed via `ProcessHandle.descendants()` first

**Idle detection:**
- Only triggers after first output (grace period for slow startup)
- Default: 15s normal commands, 60s build commands
- Build command detection: prefix matching + keyword heuristics (`test`, `coverage`, `docker`)
- Configurable per-call via `idle_timeout` parameter

### 4. OutputCollector

```kotlin
// agent/tools/process/OutputCollector.kt

data class CollectedOutput(
    val content: String,         // Truncated content for ToolResult
    val stderrContent: String?,  // Separate stderr if requested
    val totalBytes: Long,        // Total raw output size
    val totalLines: Int,         // Total line count
    val fullPath: String?,       // Path to spilled full output (null if under cap)
    val wasTruncated: Boolean
)

class OutputCollector(
    private val spillDir: File,           // Session temp directory
    private val maxMemoryBytes: Long = 1_000_000,  // 1MB default
    private val maxResultChars: Int = 30_000        // ToolResult content cap
) {
    fun collect(
        process: Process,
        toolCallId: String,
        streamCallback: ((String, String) -> Unit)?,
        separateStderr: Boolean = false
    ): CollectedOutput
}
```

**Reader thread:**
- 4KB buffer (current pattern)
- Chunks appended to in-memory list + streamed via callback
- Daemon thread, named `RunCommand-Output-{toolCallId}`

**In-memory cap (1MB):**
- When total output exceeds 1MB, stop accumulating in memory
- Full output continues writing to disk spill file
- In-memory buffer holds first 50% of lines + last 50% of lines

**Disk spill:**
- File location: `{sessionDir}/tool-output/run-cmd-{toolCallId}-{epoch}.txt`
- Written continuously as output arrives (not buffered then flushed)
- Temp file registered with `spillFile.deleteOnExit()` for JVM cleanup
- Explicit cleanup when session ends (via Disposable lifecycle)

**Truncation for ToolResult (30K chars):**
- Applied AFTER ANSI stripping + Unicode sanitization
- 50/50 by line count: first half of lines + `[... N lines omitted ...]` + last half
- Total output size appended: `[Total output: N chars, N lines. Full output: {path}]`

**Unicode sanitization (Goose/Amazon Q pattern):**
```kotlin
private val UNSAFE_UNICODE = Regex("[\\u200B-\\u200D\\u202A-\\u202E\\uFEFF\\p{Cf}]")
fun sanitizeForLLM(text: String): String = text.replace(UNSAFE_UNICODE, "")
```

**ANSI stripping:**
- Current `stripAnsi()` regex preserved
- Applied to LLM context only — raw ANSI already streamed to UI via callback

### 5. Updated RunCommandTool Parameters

```kotlin
override val parameters = FunctionParameters(
    properties = mapOf(
        "command" to ParameterProperty(
            type = "string",
            description = "The CLI command to execute."
        ),
        "shell" to ParameterProperty(
            type = "string",
            description = "Shell to execute in. Use ONLY shells listed as available in your environment.",
            enumValues = listOf("bash", "cmd", "powershell")
        ),
        "working_dir" to ParameterProperty(
            type = "string",
            description = "Working directory (absolute or relative to project root). Defaults to project root."
        ),
        "description" to ParameterProperty(
            type = "string",
            description = "What the command does and why (shown to user in approval dialog)."
        ),
        "timeout" to ParameterProperty(
            type = "integer",
            description = "Timeout in seconds. Default: 120, max: 600."
        ),
        "idle_timeout" to ParameterProperty(
            type = "integer",
            description = "Idle detection threshold in seconds. Default: 15 (60 for build commands)."
        ),
        "env" to ParameterProperty(
            type = "object",
            description = "Additional environment variables as key-value pairs. System-critical vars (PATH, HOME, LD_PRELOAD) are blocked."
        ),
        "separate_stderr" to ParameterProperty(
            type = "boolean",
            description = "Capture stderr separately. Default: false (merged with stdout). When true, stderr appears in a labeled [STDERR] section."
        )
    ),
    required = listOf("command", "shell", "description")
)
```

## Security

### Sensitive Environment Variables (stripped before spawn)

Expanded from 17 → 35+ variables:

```kotlin
private val SENSITIVE_ENV_VARS = listOf(
    // API keys & tokens
    "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "SOURCEGRAPH_TOKEN",
    "GITHUB_TOKEN", "GH_TOKEN", "GITLAB_TOKEN", "BITBUCKET_TOKEN",
    "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_ACCESS_KEY_ID",
    "AZURE_CLIENT_SECRET", "AZURE_SUBSCRIPTION_ID",
    "GOOGLE_APPLICATION_CREDENTIALS",
    "NPM_TOKEN", "NUGET_API_KEY", "DOCKER_PASSWORD",
    "SONAR_TOKEN", "JIRA_TOKEN", "BAMBOO_TOKEN",
    "HEROKU_API_KEY", "TWILIO_AUTH_TOKEN", "SLACK_BOT_TOKEN",
    // SSH/crypto
    "SSH_AUTH_SOCK", "SSH_PRIVATE_KEY", "SSH_KEY_PATH",
    // Database
    "DATABASE_URL", "PGPASSWORD", "MYSQL_PWD",
    // Kubernetes/Cloud/Secrets
    "KUBECONFIG", "VAULT_TOKEN", "VAULT_ADDR", "AWS_PROFILE",
    // Docker
    "DOCKER_CONFIG",
    // GitHub Apps
    "GITHUB_APP_PRIVATE_KEY",
)
```

### Blocked Environment Variables (for `env` parameter)

LLM-provided env vars are filtered through this blocklist:

```kotlin
private val BLOCKED_ENV_VARS = setOf(
    // System-critical
    "PATH", "HOME", "SHELL", "TERM", "USER", "LOGNAME", "USERNAME",
    "SYSTEMROOT", "COMSPEC", "WINDIR", "APPDATA", "LOCALAPPDATA",
    // Dynamic linker injection
    "LD_PRELOAD", "LD_LIBRARY_PATH",
    "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH",
    // JVM injection
    "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH",
    // Language path injection
    "PYTHONPATH", "NODE_PATH", "GOPATH", "CARGO_HOME",
    "PERL5LIB", "RUBYLIB",
    // Build tool paths
    "MAVEN_HOME", "GRADLE_HOME", "GRADLE_USER_HOME",
)
```

### Anti-Interactive Environment Overrides (expanded)

```kotlin
private val ANTI_INTERACTIVE_ENV = mapOf(
    // Pager suppression (universal across all tools)
    "PAGER" to "cat",
    "GIT_PAGER" to "cat",
    "MANPAGER" to "cat",
    "SYSTEMD_PAGER" to "",
    // Editor suppression
    "EDITOR" to "cat",
    "VISUAL" to "cat",
    "GIT_EDITOR" to "cat",
    // Terminal behavior
    "LESS" to "-FRX",
    "TERM" to "dumb",
    "NO_COLOR" to "1",
    // Git credential/SSH prompt suppression (platform-adapted at runtime)
    "GIT_ASKPASS" to if (isWindows) "echo" else "/bin/false",
    "GIT_SSH_COMMAND" to "ssh -o BatchMode=yes",
    "GIT_TERMINAL_PROMPT" to "0",
    // npm non-interactive
    "NPM_CONFIG_INTERACTIVE" to "false",
    // Python output encoding
    "PYTHONIOENCODING" to "utf-8",
)
```

### Hard-Block Patterns (moved to DefaultCommandFilter)

Unchanged from current `HARD_BLOCKED` list — 14 patterns covering:
- `rm -rf /` and `rm -rf ~`
- `sudo` commands
- Fork bombs (`:(){`)
- `mkfs.*`, `dd if=`
- Redirect to device files (`> /dev/sd`)
- `chmod -R 777 /`, `chown -R ... /`
- Pipe to shell: `curl|sh`, `curl|bash`, `wget|sh`, `wget|bash`

### Safety Architecture (separation of concerns)

```
Command arrives
  │
  ├─ 1. CommandFilter.check()        ← Hard-block (NEVER run)
  │     DefaultCommandFilter             Fork bombs, rm -rf /, sudo, etc.
  │     Reject → error, no override      Located in: security/ package
  │                                      Called from: RunCommandTool.execute()
  │
  ├─ 2. CommandSafetyAnalyzer.classify() ← Risk assessment (user decides)
  │     SAFE → auto-approve                 git push → RISKY, curl|sh → DANGEROUS
  │     RISKY → approval dialog             Located in: security/ package
  │     DANGEROUS → block                   Called from: AgentLoop.assessRisk()
  │                                         NOT called from RunCommandTool
  │
  └─ 3. User approval gate           ← Per-invocation approval
        ALWAYS_PER_INVOCATION             Every run_command shows approval dialog
        User sees: command, risk level,   Located in: AgentLoop + UI
        description
```

## Files Changed

### New Files
| File | Lines | Purpose |
|------|-------|---------|
| `agent/tools/process/ShellResolver.kt` | ~120 | Shell detection and resolution |
| `agent/tools/process/ProcessExecutor.kt` | ~200 | Process spawning, monitoring, killing |
| `agent/tools/process/OutputCollector.kt` | ~150 | Streaming capture, disk spill, truncation |
| `agent/security/CommandFilter.kt` | ~15 | Interface: check() → Allow/Reject |
| `agent/security/DefaultCommandFilter.kt` | ~50 | Hard-block regex patterns |
| `agent/tools/process/ShellResolverTest.kt` | ~80 | Shell detection tests |
| `agent/tools/process/ProcessExecutorTest.kt` | ~100 | Spawn/monitor/kill tests |
| `agent/tools/process/OutputCollectorTest.kt` | ~100 | Truncation, spill, unicode tests |
| `agent/security/CommandFilterTest.kt` | ~60 | Hard-block pattern tests |

### Modified Files
| File | Change |
|------|--------|
| `agent/tools/builtin/RunCommandTool.kt` | Gutted to ~150 lines — thin orchestrator calling new components |
| `agent/tools/builtin/RunCommandToolTest.kt` | Updated for new pipeline, add parameter tests |
| `agent/security/CommandSafetyAnalyzer.kt` | Extract `ShellTokenizer` utility (optional, can defer) |
| `agent/AgentService.kt` | No change — registration unchanged |
| `agent/loop/AgentLoop.kt` | No change — assessRisk() unchanged |

### Deleted Code (from RunCommandTool)
| Code | Lines | Reason |
|------|-------|--------|
| `findGitBash()` | 126-134 | Moved to ShellResolver |
| `findPowerShell()` | 140-150 | Moved to ShellResolver |
| `detectAvailableShells()` | 157-168 | Moved to ShellResolver |
| `SAFE_GIT_SUBCOMMANDS` | 99-105 | Removed — git policy deferred |
| `DANGEROUS_GIT_FLAGS` | 108-111 | Removed — git policy deferred |
| `checkGitCommand()` | 221-257 | Removed — CommandSafetyAnalyzer handles git risk |
| `HARD_BLOCKED` | 81-96 | Moved to DefaultCommandFilter |
| `isHardBlocked()` / `isBlocked()` | 262-269 | Moved to DefaultCommandFilter |
| `CommandSafetyAnalyzer.classify()` call | 287-296 | Removed — already called in AgentLoop |
| `streamCallback` static var | 72 | Replaced by explicit parameter |
| `currentToolCallId` ThreadLocal | 78 | Replaced by explicit parameter |
| Shell-matching `when` block | 341-407 | Moved to ShellResolver |

## Execution State Threading

**Decision:** Explicit parameters, not coroutine context element.

The code reviewer identified that coroutine context elements are inherited by child coroutines. In `AgentLoop`, read-only tools execute in parallel via `async {}`. If tool A's context element propagated to tool B, streaming output would route to the wrong tool call ID.

**Approach:** `RunCommandTool.execute()` receives `toolCallId` and `streamCallback` as explicit parameters from the execution layer. These are passed through to `ProcessExecutor` and `OutputCollector` as function arguments.

```kotlin
// In RunCommandTool — internal execute method
internal suspend fun executeInternal(
    params: JsonObject,
    project: Project,
    toolCallId: String,
    streamCallback: ((toolCallId: String, chunk: String) -> Unit)?
): ToolResult

// The AgentTool.execute(params, project) override delegates:
override suspend fun execute(params: JsonObject, project: Project): ToolResult {
    val toolCallId = currentToolCallId.get() ?: "run-cmd-${processIdCounter.incrementAndGet()}"
    val callback = streamCallback
    return executeInternal(params, project, toolCallId, callback)
}
```

**Migration path:** The static `streamCallback` and `currentToolCallId` are kept temporarily for backward compatibility during the transition. `RuntimeExecTool` and `SonarTool` continue reading them until they're migrated to explicit parameters in a follow-up.

## Testing Strategy

### Unit Tests (new components)

**ShellResolverTest:**
- Detects Git Bash on Windows when installed
- Falls back to PowerShell when Git Bash missing
- Falls back to cmd when PowerShell disabled
- Uses `/bin/bash` on Unix when available
- Falls back to `$SHELL` when `/bin/bash` missing
- Falls back to `/bin/sh` as last resort
- Returns error when requested shell unavailable
- Lists available shells in error message

**CommandFilterTest:**
- Rejects fork bomb patterns
- Rejects `rm -rf /` and `rm -rf ~`
- Rejects `sudo` commands
- Rejects `curl|sh` and `wget|bash`
- Rejects `mkfs.*` and `dd if=`
- Allows normal commands (`ls`, `grep`, `echo`)
- Allows quoted dangerous patterns (`grep "rm -rf" file.txt`)

**OutputCollectorTest:**
- Under-cap output returned as-is
- Over-cap output truncated 50/50 by line count
- Spill file created for over-cap output
- Spill file contains full untruncated output
- ANSI codes stripped from LLM content
- Unicode zero-width characters stripped
- Separate stderr captured when flag set
- Empty output returns "(No output)" sentinel

**ProcessExecutorTest:**
- Process exit returns Exit result with exit code
- Timeout kills process and returns Timeout result
- Idle detection returns Idle result with process ID
- Environment variables applied correctly
- Sensitive vars stripped
- Blocked env vars rejected
- Anti-interactive overrides applied
- Cancellation kills process immediately

### Integration Tests

- Full pipeline: resolve → filter → spawn → collect → result
- Hard-blocked command returns error without spawning
- Build command uses 60s idle threshold
- Custom `idle_timeout` parameter overrides default
- `env` parameter applies to spawned process
- `separate_stderr` produces labeled sections
- Large output (>1MB) spills to disk with truncated result
- Windows shell resolution (if CI supports Windows)

## Migration Notes

### Backward Compatibility

- `AgentTool.execute(params, project)` signature unchanged — no change to 80 tools
- `RunCommandTool.streamCallback` and `currentToolCallId` kept temporarily
- `ProcessRegistry` API unchanged
- `CommandSafetyAnalyzer` API unchanged
- Approval gate flow unchanged

### Follow-Up Work (not in this spec)

1. **Migrate RuntimeExecTool + SonarTool** to use OutputCollector and explicit parameter passing
2. **Remove static `streamCallback` and `currentToolCallId`** once all consumers migrated
3. **Add `preferredShell` to AgentSettings UI** (settings page)
4. **Session-scope ProcessRegistry** (composite key: `sessionId:toolCallId`)
5. **Pluggable command policy system** (git policy, path restrictions, user rules)
6. **tree-sitter-bash integration** for AST-based command analysis (replaces regex hard-blocks)
