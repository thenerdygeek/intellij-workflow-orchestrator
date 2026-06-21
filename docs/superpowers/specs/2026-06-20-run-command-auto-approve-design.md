# run_command Auto-Approval — Design Specification

> **Date:** 2026-06-20
> **Status:** Draft (pending user review) — incorporates two adversarial reviews (design + spec)
> **Module:** `:agent` (depends only on `:core`)
> **Related:** memory `project_run_command_auto_approve_research.md`; `agent/CLAUDE.md` → "Tool Approval", "Security"

---

## 1. Overview

Reduce approval friction for the `run_command` agent tool with two opt-in, fail-closed mechanisms:

- **Part A — global "auto-approve safe commands" toggle.** An opt-in setting (default OFF). When ON, a `run_command` the analyzer classifies `SAFE` *and* that is structurally simple auto-approves (no prompt). `RISKY`/unknown still prompt; `DANGEROUS` never auto-approves.
- **Part B — session prefix allowlist ("approve all of this type").** The approval card gains a button **"Approve all `<prefix>` this session"** (e.g. `git add`, `git pull`). Approved prefixes live in an in-memory, per-session store cleared on new chat. A later `run_command` whose every sub-command matches a session-approved prefix (and is structurally simple) auto-approves. Offered for `SAFE` and `RISKY` commands; never `DANGEROUS`.

Both paths converge on **one pure decision function** so the security-critical logic (command splitting, prefix matching, structural-simplicity guards) lives in exactly one hardened place, and so the future user-editable allow/deny-list feature has a single insertion point.

**Prior art:** Every major agent ships this (Cline, Cursor, Claude Code, Roo Code, Factory Droid). The matcher is a Kotlin re-port of Roo Code's Apache-2.0 `getCommandDecision` / `findLongestPrefixMatch` decision logic, hardened per review. We do **not** port Roo's `shell-quote` dependency.

**Key design tension (drives everything below):** `CommandSafetyAnalyzer` was tuned to avoid *false `DANGEROUS` positives* for a **human-reviewed** approval card. Auto-approval **inverts the cost function** — a false "SAFE/simple" verdict now executes code **unattended**. Therefore the auto-approve gate is deliberately conservative: **a false prompt costs nothing; a false skip executes code.** When in doubt, prompt.

---

## 2. Product decisions (locked)

| # | Decision | Source |
|---|---|---|
| 1 | `SAFE`-only auto-approve in v1; design leaves a clean seam for future user-editable allow/deny lists. | user |
| 2 | Inline badge on the tool card: `auto-approved · safe` or `auto-approved · session rule: <prefix>`. No separate audit-log surface. | user |
| 3 | Analyzer's existing `SAFE` set is **unchanged** — build/test/run commands (`mvn`, `gradle`, `./gradlew`, `npm test`, `npm run *`, `pytest`) stay `SAFE` → auto-approve. | user |
| 4 | Part B is offered for `RISKY` commands too (e.g. `git pull`, `git push`, `npm install`); `DANGEROUS`/redirection/subshell/dangerous-substitution are never prefix-approvable. | user |
| 5 | Part B is session-only, in-memory, **no `settings.json` persistence** (sidesteps Claude Code's documented persistence bugs #16735/#10956). | user |
| 6 | The session allowlist + the Part A toggle **also** apply to sub-agent `run_command`s (consistent UX). | default, stated |
| 7 | The auto-approved badge is **transient** (not persisted across session reload). | default, stated |

**Non-goals (v1):** user-editable allow/deny lists in settings; `settings.json`/cross-session persistence of prefixes; PowerShell-specific analysis (POSIX-shaped; PowerShell users get the prompt path); re-tuning `CommandSafetyAnalyzer`'s classification; a dedicated JSONL audit surface for the reason (the existing per-tool log entry already records that the command ran).

---

## 3. Architecture

### 3.1 The unified decision

All `run_command` approval flows through **one pure function**, evaluated in `AgentLoop`'s approval block *before* today's gate logic:

```kotlin
// agent/security/CommandApprovalDecision.kt  (new, pure, no IntelliJ deps)
sealed interface AutoApproveReason {
    data object Safe : AutoApproveReason
    data class SessionRule(val prefixes: List<String>) : AutoApproveReason
}

sealed interface ApprovalDecision {
    data class Skip(val reason: AutoApproveReason) : ApprovalDecision
    data object Prompt : ApprovalDecision
}

object CommandApprovalDecision {
    /**
     * @param command   the extracted run_command "command" string (already JSON-parsed)
     * @param risk      CommandRisk, computed once by the loop via the extracted
     *                  AgentLoop.classifyCommandRisk(command) — see §5.2. NOT re-derived per path.
     * @param autoApproveSafe          Part A toggle
     * @param sessionAllowedPrefixes   Part B per-session approved prefixes (already normalized)
     */
    fun evaluate(
        command: String,
        risk: CommandRisk,
        autoApproveSafe: Boolean,
        sessionAllowedPrefixes: Set<String>,
    ): ApprovalDecision {
        if (command.isBlank()) return ApprovalDecision.Prompt          // fail-closed
        if (risk == CommandRisk.DANGEROUS) return ApprovalDecision.Prompt
        if (!CommandShape.isAutoApprovable(command)) return ApprovalDecision.Prompt  // structural guard
        if (autoApproveSafe && risk == CommandRisk.SAFE) {
            return ApprovalDecision.Skip(AutoApproveReason.Safe)
        }
        val matched = CommandShape.coveringPrefixes(command, sessionAllowedPrefixes)
        if (matched != null) return ApprovalDecision.Skip(AutoApproveReason.SessionRule(matched))
        return ApprovalDecision.Prompt
    }
}
```

**Decision order (the contract):**

```
run_command reaches approval block
  │  (command extracted from JSON args; CommandRisk computed once via classifyCommandRisk)
  ▼
1. command blank ────────────────────────────────► Prompt   (fail-closed)
2. risk == DANGEROUS ────────────────────────────► Prompt   (also hard-blocked in RunCommandTool)
3. NOT structurally auto-approvable ─────────────► Prompt   (redirect / subshell / $-expansion /
   (CommandShape.isAutoApprovable == false)                  wrapper / assignment / inline-eval / & )
4. Part A: autoApproveSafe && risk == SAFE ──────► Skip(Safe)
5. Part B: every sub-command covered by a
   session-approved prefix ──────────────────────► Skip(SessionRule(prefixes))
6. else ─────────────────────────────────────────► Prompt
```

The structural guard (step 3) applies to **both** Part A and Part B — this closes the Roo #11095 redirection hole for the SAFE path too (e.g. `git status > ~/.ssh/authorized_keys` is `SAFE` to the analyzer but is **not** auto-approvable here).

### 3.2 Risk reuse — extract `classifyCommandRisk`

Today `AgentLoop.assessRisk(toolName, argsJson)` returns a display `String` (`"low"/"medium"/"high"`) and builds a throwaway `CommandRisk` internally. There is **no** `CommandRisk`-returning method. To feed `evaluate(...)` without re-parsing/re-classifying:

- Extract `internal fun AgentLoop.classifyCommandRisk(command: String): CommandRisk` (wraps `CommandSafetyAnalyzer.classify`).
- `assessRisk` calls it and maps to the String (single source of truth → badge risk and gate risk can't drift).
- The new auto-approve block extracts the command from the JSON args once, calls `classifyCommandRisk(command)` once, and passes the `CommandRisk` to `evaluate`.

### 3.3 Why the skip lives in the loop, not the gate

The approval gate (`AgentController.approvalGate`) is only invoked *after* the loop decides a prompt is needed. The auto-approve skip must therefore happen in `AgentLoop`, exactly where the existing `autoApproveMemoryOperations` bypass and `sessionApprovalStore.isApproved(...)` check already live (`AgentLoop.kt` ~1922–1929). On `Skip`, the loop bypasses **only** `approvalGate.invoke` — `fileLogger.logToolCall`, `sessionMetrics`, the `PreToolUse` hook, and checkpoint capture all still run.

---

## 4. Conservative command analysis (`CommandShape`)

A new pure object `agent/security/CommandShape.kt` owns all parsing for the gate. It does **not** reuse `CommandSafetyAnalyzer.splitSegments` (which misses newlines and lone `&`). It reuses only the public `CommandSafetyAnalyzer.tokenize` for quote-aware **token-value extraction** within an already-validated-simple sub-command (`Token` exposes `value`/`quoted`/`isOperator`).

### 4.1 Sub-command splitting

```
splitSubCommands(command): List<String>
```
1. Split the raw string on newlines first: `\r\n | \r | \n`.
2. Within each line, split on top-level operators `&&`, `||`, `;`, `|` (respecting quotes).
3. Return the non-empty trimmed sub-commands.

This is the newline-injection fix: `git status\nrm -rf /tmp/x` becomes two sub-commands, so a `git status` session rule cannot let the second line ride along. (A lone `&` is **not** split here; instead `isAutoApprovable` rejects any sub-command containing a `&` token — see §4.2 — and `coveringPrefixes` calls `isAutoApprovable` first, so backgrounding can never be covered.)

### 4.2 Structural simplicity — `isAutoApprovable(command)`

Returns `true` only if **every** sub-command passes all of:

- **No redirection:** none of `>`, `>>`, `<`, `<<`, `<<<`, `&>`, `2>`, `n>&m` (detected via the tokenizer's `>`/`<` operator tokens, not a raw-string `contains`).
- **No command/process substitution:** no `$(`, backtick, `<(`, `>(`.
- **No `$` expansion of any kind** (variables, `${...}`, `$((...))`, `$[...]`, special `$?`/`$!`/…) — conservative: a false prompt is free, and expansions are the primary injection surface. *(Documented limitation: `echo "$HOME"` prompts under auto-approve. Relax later with the allow/deny-list feature if needed.)*
- **No backgrounding:** no lone `&` token.
- **No leading variable assignment:** first token does not match `^[A-Za-z_][A-Za-z0-9_]*=` (blocks `FOO=bar git push`, `GIT_DIR=… git …`).
- **First token is not an exec wrapper:** not in `WRAPPER_DENYLIST` — `env, sudo, doas, su, timeout, nice, ionice, nohup, setsid, stdbuf, xargs, command, exec, time, watch, flock` (the real command would be an *argument*, defeating prefix logic).
- **Not inline-code execution:** not a shell interpreter (`sh, bash, zsh, fish, dash, ksh`) and not a language interpreter invoked with an inline-eval flag (`python -c`/`-m`, `node -e`/`--eval`, `ruby -e`, `perl -e`/`-E`, `php -r`, bare `-` stdin). *(`python script.py` / `node app.js` remain auto-approvable — same accepted risk class as `npm run`/gradle per decision #3; only inline-eval is rejected.)*

`isAutoApprovable` is an **independent predicate** — it does **not** trust `CommandSafetyAnalyzer.classify`. Pipes (`|`) and chains (`&&`/`||`/`;`) of otherwise-simple sub-commands ARE allowed (data flow / sequencing of approved commands is fine; I/O redirection and code substitution are not).

### 4.3 Prefix derivation — `derivePrefix(command)`

Offered only when prompting a **single, structurally-simple** sub-command (compound commands → no button in v1). Returns `null` to suppress the button otherwise.

```
tokens = tokenize(command).nonOperator.values     // quote-aware
if tokens.isEmpty || first token in WRAPPER_DENYLIST/assignment/interpreter-eval → null
if first token in MULTI_VERB_TOOLS and tokens[1] exists and not starting with '-':
    prefix = "${tokens[0]} ${tokens[1]}"          // e.g. "git add", "npm install", "docker build"
else:
    prefix = tokens[0]                            // e.g. "ls", "cat", "./gradlew"
```

`MULTI_VERB_TOOLS = { git, npm, yarn, pnpm, npx, docker, docker-compose, kubectl, helm, mvn, ./mvnw, mvnw, gradle, ./gradlew, gradlew, cargo, go, pip, pip3, poetry, uv, gh, terraform, make }`.

`derivePrefix` may return the **original-case** text — this is the human-readable **button label**. Normalization (trim → collapse spaces → lowercase) happens exactly once, in `SessionCommandAllowlist.approve(prefix)` (§5.1), so the stored key and the matcher agree regardless of the label's case.

### 4.4 Coverage matching — `coveringPrefixes(command, prefixes)`

Matching is **token-wise**, never raw `startsWith` (so `git` ≠ `git-secret push`, `ls` ≠ `lsof`). `prefixes` is assumed already-normalized (lowercased/single-spaced); only the live sub-command tokens are lowercased inline.

```
coveringPrefixes(command, prefixes): List<String>? =
  if (!isAutoApprovable(command)) null              // structural guard FIRST (closes lone-& etc.)
  else {
    val subs = splitSubCommands(command)
    val matched = subs.map { sub ->
        val subTokens = tokenize(sub).nonOperator.values.map { it.lowercase() }
        prefixes.firstOrNull { p ->
            val pT = p.split(' ')                    // p already normalized/lowercased
            subTokens.size >= pT.size && subTokens.subList(0, pT.size) == pT
        }
    }
    if (matched.all { it != null }) matched.filterNotNull().distinct() else null
  }
```

Every sub-command must be covered; a compound where one side is uncovered → prompt.

> **v1 simplification:** the session store is **allow-only** (a `Set<String>` of approved prefixes). Roo's deny-list + longest-prefix-wins conflict resolution is **not** needed in v1 but is the documented extension point — when user-editable allow/deny lists land, `coveringPrefixes` grows a deny check and longest-match tie-break here, and `CommandApprovalDecision.evaluate` gains the lists as parameters. No other call site changes.

### 4.5 Worked examples

| Command | `isAutoApprovable` | Part A (SAFE+toggle) | Part B given session `{git add, git status}` |
|---|---|---|---|
| `ls -la` | yes | Skip(Safe) | — |
| `git status` | yes | Skip(Safe) | Skip(SessionRule[git status]) |
| `git add Foo.kt && git status` | yes | Skip(Safe) | Skip(SessionRule[git add, git status]) |
| `git addendum` | yes | prompt (unknown→RISKY) | prompt (token `addendum` ≠ `add`) |
| `git status > ~/.ssh/x` | **no** (redirect) | Prompt | Prompt |
| `git status\nrm -rf /tmp/x` | **no** (2 subs; `rm -rf`→DANGEROUS) | Prompt | Prompt |
| `git status & rm -rf /tmp/x` | **no** (lone `&`) | Prompt | Prompt |
| `FOO=bar git push` | **no** (assignment) | Prompt | Prompt |
| `timeout 60 git push` | **no** (wrapper) | Prompt | Prompt |
| `python -c "import os…"` | **no** (inline-eval) | Prompt | Prompt |
| `echo $(rm -rf /)` | **no** (subshell; also DANGEROUS) | Prompt | Prompt |
| `git pull` (RISKY) | yes | Prompt (not SAFE) | Skip if `git pull` approved |

---

## 5. Component change surface

### 5.1 New (pure, unit-tested, no IntelliJ deps)

| File | Responsibility |
|---|---|
| `agent/security/CommandApprovalDecision.kt` | The unified `evaluate(...)` + `AutoApproveReason`/`ApprovalDecision` types. |
| `agent/security/CommandShape.kt` | `splitSubCommands`, `isAutoApprovable`, `derivePrefix`, `coveringPrefixes`, the denylists. |
| `agent/loop/SessionCommandAllowlist.kt` | Per-session approved prefixes. `approve(prefix)` is the **single normalization point** (trim → collapse-spaces → lowercase) writing into a `Collections.synchronizedSet`; `covers(command): List<String>?` delegates to `CommandShape.coveringPrefixes(command, snapshot)`; `clear()`. Mirrors `SessionApprovalStore` lifecycle/ownership. |

### 5.2 Changed

> **Construction reality (verified during implementation — corrects an earlier draft assumption):** `AgentController` does **not** build `AgentLoop`. `AgentService` builds it at exactly **one** site — inside `executeTask` (~`:2357`). `resumeSession` does **not** build its own loop; it rebuilds context then **delegates to `executeTask(...)`** (~`:3053`). The delegated/handoff entry points also route through those two functions. So there is no second build to forget: `autoApproveSafeCommands` is read from settings at the single build (always current on resume), and `resumeSession` must **thread its `sessionCommandAllowlist` param into the `executeTask(...)` call** so a controller-owned allowlist survives a resume. (An earlier draft of this spec assumed two build sites with a resume-side omission to fix; that was a misread — the line mistaken for a second build is the `resumeSession→executeTask` *call* passing the stores through.)

| File | Change |
|---|---|
| `agent/settings/AgentSettings.kt` | Add `var autoApproveSafeCommands by property(false)` to `State` (BaseState delegate, matching `autoApproveMemoryOperations`). |
| `agent/settings/AgentAdvancedConfigurable.kt` | New `group("Commands")` with `checkBox("Auto-approve safe shell commands without asking").bindSelected(agentSettings.state::autoApproveSafeCommands)` + a caption naming the risk (build/test/run scripts run unattended; DANGEROUS still blocked; redirections/subshells still prompt). Mirrors the existing `group("Memory")` (~`:218`). |
| `agent/loop/LoopResult.kt` (`ToolCallProgress`) | Add `autoApproved: Boolean = false` and `autoApproveReason: String? = null` (flattened label: `"safe"` / `"session rule: git add"`). Defaults keep all existing call sites compiling. |
| `agent/loop/AgentLoop.kt` | (a) Extract `internal fun classifyCommandRisk(command): CommandRisk`; `assessRisk` delegates to it (§3.2). (b) New ctor params `autoApproveSafeCommands: Boolean = false`, `sessionCommandAllowlist: SessionCommandAllowlist = SessionCommandAllowlist()`. (c) In the approval block (~1922): for `run_command`, extract the command, compute `CommandRisk` once, call `CommandApprovalDecision.evaluate(...)`; on `Skip` → bypass `approvalGate.invoke` **only** and stamp the upcoming `ToolCallProgress(autoApproved=true, autoApproveReason=…)`; on `Prompt` → existing gate path. |
| `agent/AgentService.kt` | At the single `executeTask` `AgentLoop` construction (~`:2357`), read `agentSettings.state.autoApproveSafeCommands` and pass it + the `SessionCommandAllowlist` (received via the callbacks bundle / param) into the loop. Add a `sessionCommandAllowlist` param to both `executeTask` and `resumeSession`, and have `resumeSession` thread it into its `executeTask(...)` delegation so prefix approvals survive a resume. Also set `spawnAgentTool.sessionCommandAllowlist` + `spawnAgentTool.autoApproveSafeCommands` next to the existing `spawnAgentTool.sessionApprovalStore = …` (~`:2173`) so sub-agents inherit them (decision #6). |
| `agent/ui/AgentController.kt` | Own a `SessionCommandAllowlist` field next to `sessionApprovalStore` (~`:268`); **clear it in `resetForNewChat`**. Thread it into `executeTask`/`resumeSession` via the `SessionUiCallbacks` bundle (see below). In `approvalGate` (~`:2743`, where `CommandApprovalPayload` is built ~`:2834`): for `run_command`, derive the prefix via `CommandShape.derivePrefix(command)` and include it in the card payload (null ⇒ no button). Add a **new** bridge callback `approveCommandPrefix(prefix)` — deliberately **separate** from the existing `_allowToolForSession(toolName)` bridge (`AgentCefPanel.kt:628`, whose String arg already means *tool name*); it calls `sessionCommandAllowlist.approve(prefix)` then completes the pending approval with `ApprovalResult.APPROVED`. In `onToolCall` (~`:3071`), forward `autoApproved`/`autoApproveReason` into `appendToolCall`. |
| `agent/ui/SessionUiCallbacks.kt` + `AgentController.buildSessionUiCallbacks` | Add `sessionCommandAllowlist` to the bundle (single source of truth) so both interactive and delegated `AgentService` entry points receive the same instance — required by the SessionUiCallbacks parity contract. |
| `agent/tools/builtin/SpawnAgentTool.kt` | Add `var sessionCommandAllowlist` + `var autoApproveSafeCommands` fields (mirroring the `sessionApprovalStore` field ~`:71`); forward both into **both** `SubagentRunner(...)` constructions (~`:835`, ~`:953`). |
| `agent/tools/subagent/SubagentRunner.kt` | Accept `sessionCommandAllowlist`/`autoApproveSafeCommands` ctor params (mirroring `sessionApprovalStore` ~`:62`); forward into its `AgentLoop(...)` (~`:354/:379`). |
| webview (`agent/webview/src/...`) | **Badge wire:** add `autoApproved`/`autoApproveReason` through `AgentDashboardPanel.appendToolCall` → `AgentCefPanel` → JCEF bridge → tool-call card render (`auto-approved · <reason>`). **Button:** approval card renders **"Approve all `<prefix>` this session"** when the payload carries a prefix; wire its click to the new `approveCommandPrefix` bridge. |

**No new `ApprovalResult` variant.** The prefix button resolves to the existing `APPROVED` after the controller mutates the allowlist. `ALLOWED_FOR_SESSION` is **not** reused — for `run_command` (`ApprovalPolicy` `allowSessionApproval=false`) it is a no-op today and carries no prefix.

---

## 6. Data flow / sequence

### 6.1 First time (prompt + "approve all")
```
LLM → run_command("git add .")
AgentLoop approval block:
  risk = classifyCommandRisk → RISKY ; evaluate(...) → Prompt (not SAFE, no session rule yet)
  approvalGate.invoke(...)  ── suspends on CompletableDeferred
AgentController.approvalGate:
  derivePrefix("git add .") → "git add"
  push approval card { command, riskLevel, prefix:"git add" }
User clicks [Approve all "git add" this session]:
  bridge approveCommandPrefix("git add")
    → sessionCommandAllowlist.approve("git add")     // normalizes here
    → pendingApproval.complete(APPROVED)
AgentLoop: APPROVED → run this one command once (logging/hook/checkpoint all run)
```

### 6.2 Next time (silent)
```
LLM → run_command("git add src/Foo.kt")
AgentLoop approval block:
  evaluate(...) → Skip(SessionRule["git add"])
  → no approvalGate call; ToolCallProgress(autoApproved=true, reason="session rule: git add")
  → logging + sessionMetrics + PreToolUse hook + checkpoint capture STILL run
Webview tool card shows "auto-approved · session rule: git add"
```

**Race-freedom:** approvals are serialized per session — `approvalGate` holds a single `pendingApproval` deferred with a reentry guard (`AgentController.kt` ~`:2789–2807`); sub-agents share the same gate. The triggering command runs exactly once via `APPROVED`; the *next* match short-circuits in the loop.

---

## 7. Security model

| Threat | Mitigation | Where |
|---|---|---|
| Newline-injected second command (`git status\nrm -rf …`) | `splitSubCommands` splits on newlines; every sub-command must be covered | §4.1/§4.4 |
| Prefix over-match (`git` ⇒ `git-secret push`, `ls` ⇒ `lsof`) | token-wise prefix comparison, never raw `startsWith` | §4.4 |
| Redirection writes files (`git show > ~/.ssh/id_rsa`, Roo #11095) | redirection ⇒ not auto-approvable, for **both** Part A & B | §4.2 |
| Backgrounding (`cmd & evil`) | lone `&` ⇒ not auto-approvable; `coveringPrefixes` runs the guard first | §4.2/§4.4 |
| Wrapper hides real command (`env`/`timeout`/`xargs … <cmd>`) | first-token wrapper denylist ⇒ not auto-approvable; no prefix derived | §4.2/§4.3 |
| Leading assignment (`FOO=bar git push`) | assignment-prefix reject | §4.2 |
| Inline code (`python -c`, `node -e`, `bash -c`) | interpreter-inline-eval reject (independent of analyzer SAFE set) | §4.2 |
| Command/parameter substitution (`$(…)`, `` `…` ``, `${x@P}`) | any `$`/backtick/substitution ⇒ not simple | §4.2 |
| DANGEROUS slips through | `risk==DANGEROUS ⇒ Prompt`; **and** `DefaultCommandFilter` hard-blocks pre-spawn regardless of approval (verified independent of the gate) | §3.1 + `RunCommandTool` |
| Hook bypass | auto-approve skips **only** `approvalGate.invoke`; `PreToolUse` hook still runs and can still block | §3.3/§5.2 |

**Conservative bias (restated):** every ambiguous case resolves to `Prompt`. Opt-in + default-OFF (Part A) and explicit per-prefix opt-in (Part B) mean the user always affirmatively chooses the reduced friction.

---

## 8. Observability

- Auto-approved executions are **not** silent to the logs: they flow through the existing `fileLogger.logToolCall` (status `ok`) and `sessionMetrics.recordToolCall` — these are **not** bypassed, so the JSONL trace shows the command ran.
- The auto-approval **reason** (`safe` / `session rule: <prefix>`) surfaces on the **UI badge**, not in JSONL — matching decision #2 (badge-only; the user declined a separate audit-log surface). No `AgentFileLogger`/`LogEntry` change is required.

---

## 9. Scope boundaries

- **In:** Parts A & B; interactive loop **and** sub-agent + delegated loops (shared store via the wiring in §5.2, decision #6).
- **Plan mode:** unaffected — `run_command` is in `WRITE_TOOLS`, fully blocked in plan mode; the gate is never reached.
- **Out:** user-editable allow/deny lists; cross-session/`settings.json` persistence; PowerShell-specific analysis; analyzer re-tuning; badge persistence across reload (transient, decision #7).
- **Future seam:** `CommandApprovalDecision.evaluate` + `CommandShape.coveringPrefixes` are the single insertion point for user-editable allow/deny lists (add deny check + longest-prefix tie-break + parameters; no other call site changes).

---

## 10. Testing plan

Pure unit tests (no IntelliJ runtime):
- **`CommandShapeTest`** — `splitSubCommands` (newlines, `&&`/`||`/`;`/`|`, quotes); `isAutoApprovable` (redirect incl. `2>`/`&>`/`<<<`/`<(`/`>(`; subshell; `$`-expansion; wrapper; assignment; inline-eval; lone `&`; pipes-allowed; chains-allowed); `derivePrefix` table (multi-verb vs bare; wrapper/assignment/interpreter → null; `./gradlew`); `coveringPrefixes` (token-boundary `git` ≠ `git-secret`; compound all-covered vs partial; not-simple → null; **lone-`&` case returns null** — locks the guard-first ordering).
- **`CommandApprovalDecisionTest`** — order/precedence: blank→Prompt; DANGEROUS→Prompt; not-simple→Prompt even when SAFE; SAFE+toggle→Skip(Safe); SAFE+toggle OFF→Prompt unless covered; RISKY+covered→Skip(SessionRule); RISKY+uncovered→Prompt.
- **`SessionCommandAllowlistTest`** — approve/covers/clear; **normalization is single-sourced** (case/spacing collapse in `approve`); covers delegates to `CommandShape`.

Loop-level tests (mirror `AgentLoopMemoryApprovalTest` / `ApprovalPolicyTest`; `AgentLoop` is unit-instantiable with a mock `Project` + sequence brain + gate recorder):
- **`AgentLoopAutoApproveTest`** — toggle ON: SAFE `run_command` does **not** invoke `approvalGate`, start callback carries `autoApproved=true,reason="safe"`; RISKY one **does** invoke the gate; session-covered command skips with `reason="session rule: …"`; a redirection variant of a covered prefix still invokes the gate; auto-approved path still fires `logToolCall` + `PreToolUse` hook + checkpoint capture.
- **`classifyCommandRisk` consistency** — pin that the extracted `classifyCommandRisk` and the String `assessRisk` agree for SAFE/RISKY/DANGEROUS (no drift between badge risk and gate risk).

Wiring pins (source-text/parity, guarding the two blockers):
- **Resume parity** — assert the `resumeSession` `AgentLoop` build receives `autoApproveSafeCommands` + the same `SessionCommandAllowlist` instance (guards the resume-omission regression).
- **Sub-agent parity** — extend `SessionUiCallbacksParityTest`-style coverage (or a new pin) asserting `sessionCommandAllowlist` + `autoApproveSafeCommands` are forwarded `SpawnAgentTool → SubagentRunner → AgentLoop`, and that `AgentService` sets them on `spawnAgentTool` (mirroring the `sessionApprovalStore` set site).

Webview:
- approval card renders the third button iff a prefix is present in the payload; click invokes `approveCommandPrefix`.
- tool-call card renders the badge per reason; absent when `autoApproved=false`.

Run: `./gradlew :agent:test`. **Use `--no-build-cache`** for the run that includes the `AgentLoop`/`SubagentRunner`/`SpawnAgentTool` constructor-signature changes (documented stale-cache `NoSuchMethodError` trap in root `CLAUDE.md` → "Rebase").

---

## 11. Review findings incorporated

### 11.1 Design review (Opus, 2026-06-20)
| Finding | Resolution |
|---|---|
| Newline split | `CommandShape.splitSubCommands` (§4.1). |
| Word/token boundary | token-wise matching + token-based derivation (§4.3/§4.4). |
| Hardened `isAutoApprovable` | independent predicate; wrapper+assignment+inline-eval+`$`+redirect+subshell+lone-`&` denylists (§4.2). |
| Skip-in-loop, runs-once | §3.3, §6 — serialized, race-free, first command runs once via APPROVED. |
| New bridge callback | `approveCommandPrefix(prefix)`; not `ALLOWED_FOR_SESSION` (§5.2). |
| Reuse risk | `evaluate` takes a `CommandRisk` from the extracted `classifyCommandRisk` (§3.2). |
| Logging + hook not bypassed | only `approvalGate.invoke` is skipped (§3.3/§8). |
| Reason shape | sealed `AutoApproveReason` internally; flattened `String?` on `ToolCallProgress` (§3.1/§5.2). |
| Sub-agent scope / badge persistence / PowerShell | decisions #6/#7; POSIX-only prompt path (§9). |
| Simpler alternative | adopted — one `CommandApprovalDecision` + `CommandShape`, single future-list seam. |
| Interpreter inline-eval; structural guard on Part A too | added (§4.2, §3.1). |

### 11.2 Spec review (Opus, 2026-06-20, 2nd pass) — wiring fixes
| Finding | Resolution |
|---|---|
| BLOCKER-1: sub-agents don't share the loop | added `SpawnAgentTool` + `SubagentRunner` rows + `AgentService` set-site (§5.2); sub-agent parity pin (§10). |
| BLOCKER-2 (resume must get the values): originally framed as "2 build sites, resume omits params" | **Corrected during impl:** there is ONE build (in `executeTask`); `resumeSession` delegates to `executeTask` and threads `sessionCommandAllowlist` through, so resume inherits the wiring. Resume-correctness verified by review + the source-text wiring pin (§5.2/§10). |
| `assessRisk` returns String not `CommandRisk` | extract `classifyCommandRisk` (§3.2). |
| JSONL audit over-claim | downgraded to "logged that it ran; reason on badge" — matches badge-only decision (§8). |
| Badge plumbing under-specified | named `appendToolCall → AgentDashboardPanel → AgentCefPanel → bridge` (§5.2). |
| Normalization location | single point in `SessionCommandAllowlist.approve` (§4.3/§4.4/§5.1). |
| `AgentSettings` uses `by property(false)` | corrected (§5.2). |
