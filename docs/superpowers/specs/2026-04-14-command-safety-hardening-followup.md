# Command Safety Hardening — Follow-up Spec

**Date:** 2026-04-14
**Status:** Proposed (not scheduled)
**Context:** Follow-up to `2026-04-14-run-command-tool-redesign.md`

## Background

During the RunCommandTool redesign discussion, we evaluated OS-level process sandboxing (Seatbelt, bubblewrap, AppContainer) for network access restriction. Research across Claude Code, Codex CLI, Anthropic's sandbox-runtime, and JVM libraries concluded:

- **macOS/Linux sandboxing is viable** via `sandbox-exec` / `bwrap` wrappers
- **Windows sandboxing is not viable** as a simple `ProcessBuilder` wrapper — requires either:
  - Native Win32 helper binary (Codex approach: weeks of Rust/JNA work, admin-setup flow, firewall rules)
  - WSL2 + bubblewrap (degrades UX, requires WSL2 installed + configured)
- **Claude Code explicitly does not support Windows sandboxing** — errors with "Sandboxing is currently only supported on macOS, Linux, and WSL2"
- **No JVM library exists** for cross-platform process sandboxing
- **IntelliJ Platform has no sandboxing API**

Since our primary development/deployment target is **Windows**, OS-level sandboxing fails the effort-to-value test: the platforms where it's easy aren't the primary target, and the primary target requires significant native development.

## Decision

**Do NOT implement OS-level process sandboxing.** Instead, strengthen the existing approval-gate model which works cross-platform with zero additional infrastructure.

## Goals

1. Catch more network-touching commands in risk classification (Python/Node/PowerShell equivalents of curl)
2. Give users better context at the approval gate ("network access: yes/no", "writes outside project: yes/no")
3. Scrub credentials from command output before LLM sees it (close the exfiltration-via-output loop)
4. Add a pluggable `SandboxWrapper` hook point so OS-level sandboxing can be added later without touching `RunCommandTool`

## Non-Goals

- OS-level sandboxing (Seatbelt, bwrap, AppContainer) — deferred indefinitely
- Network proxy / egress filter — deferred indefinitely
- WSL2 integration — not planned

## Proposed Work

### 1. Network-Access Detection in CommandSafetyAnalyzer

Add a `detectsNetworkAccess(command)` classification path. Currently catches:
- `curl` / `wget` with non-local URLs → RISKY (POST/PUT/DELETE methods)

Missed today:
- `python -c "import urllib.request; urllib.request.urlopen(...)"`
- `python -m http.server` (opens listening port)
- `node -e "require('http').get(...)"`
- `nc`, `netcat`, `ncat` (any invocation)
- `ssh`, `scp`, `sftp`, `rsync` to non-local hosts
- `telnet`, `ftp`
- PowerShell: `Invoke-WebRequest`, `iwr`, `Invoke-RestMethod`, `irm`, `New-Object Net.WebClient`
- Ruby `net/http`, Perl `LWP`, PHP `file_get_contents` on URLs
- `docker pull`, `docker push` (registry I/O)
- `git push` / `git fetch` / `git clone` to non-local remotes

Treatment:
- Classify network-touching commands as RISKY (requires approval)
- Keep localhost/127.0.0.1 exemption
- Expose `hasNetworkAccess: Boolean` in risk result for UI use

### 2. Enriched Approval Dialog Context

Current approval dialog shows: command, description, risk level.

Add context fields:
- **Network access:** Yes/No (from `hasNetworkAccess`)
- **Writes outside project:** Yes/No (from new `writesOutsideProject` detection)
- **Uses credentials:** Yes/No (detects `git`, `ssh`, `curl -u`, `gh auth`, etc.)

Users make informed decisions without needing to parse bash syntax.

### 3. Output Credential Scrubbing

Wire existing `CredentialRedactor` (`agent/security/CredentialRedactor.kt`) into `OutputCollector.processOutput()`:

- Before truncation: scan output for credential patterns (API keys, private keys, tokens)
- Redact matches before content reaches the LLM
- Applies to both in-memory output and disk-spilled file

Closes the attack vector where a command prints secrets that the LLM then sees and could leak in subsequent actions.

### 4. SandboxWrapper Hook Point

Introduce a no-op interface in `agent/tools/process/`:

```kotlin
interface SandboxWrapper {
    /**
     * Wrap a command for sandboxed execution. May transform argv, env,
     * or ProcessBuilder attributes. NoopSandboxWrapper returns unchanged.
     */
    fun wrap(commandLine: GeneralCommandLine, policy: SandboxPolicy): GeneralCommandLine
}

data class SandboxPolicy(
    val denyNetwork: Boolean = false,
    val denyFileSystemOutsideProject: Boolean = false,
)

class NoopSandboxWrapper : SandboxWrapper {
    override fun wrap(cmd: GeneralCommandLine, policy: SandboxPolicy) = cmd
}
```

`RunCommandTool` receives a `SandboxWrapper` (injected, defaults to `NoopSandboxWrapper`) and calls it after `ShellResolver.resolve()` but before spawning.

Future implementations (not built):
- `SeatbeltSandboxWrapper` (macOS, via `sandbox-exec` prepending)
- `BubblewrapSandboxWrapper` (Linux, via `bwrap` prepending)
- `WindowsNativeSandboxWrapper` (Windows, via helper binary — only if we ever commit to building it)

**Important:** This is ONLY the interface and no-op. We don't implement any actual sandboxing now. The hook prevents `RunCommandTool` from needing changes later.

## Files Affected (when implemented)

| File | Change |
|------|--------|
| `agent/security/CommandSafetyAnalyzer.kt` | Add network-detection rules for Python/Node/PS/nc/ssh/scp/etc. |
| `agent/tools/process/OutputCollector.kt` | Call `CredentialRedactor` before truncation |
| `agent/tools/process/SandboxWrapper.kt` (new) | Interface + NoopSandboxWrapper |
| `agent/tools/process/SandboxPolicy.kt` (new) | Policy data class |
| `agent/tools/builtin/RunCommandTool.kt` | Inject SandboxWrapper, call wrap() before spawn |
| `agent/loop/AgentLoop.kt` | Pass `hasNetworkAccess` and related flags to approval gate |
| `agent/ui/` (approval dialog component) | Display new context fields |

## Priority

**Low-to-medium.** The current approval-gate model is the load-bearing security layer and works well. These improvements are quality-of-life and defense-in-depth, not gap-closers for the primary threat model (an LLM going off the rails).

Recommended trigger for implementing this:
- A user reports a near-miss where an obfuscated network command was approved
- Or we decide to harden before a broader release
- Or someone volunteers to write the native Windows sandbox (at which point the hook point is needed)

## What We're Accepting

- Python/Node/Ruby scripts can still make network calls if user approves
- There's no protection if the user reflexively approves every command
- Commands that legitimately need network (npm install, gradle build, docker pull) go through the same gate as exfiltration attempts — the user has to distinguish

These are acceptable tradeoffs for a developer tool where the user is the security boundary.
