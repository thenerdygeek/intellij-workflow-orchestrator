# Agent Runtime & Persistence — Security/Correctness Audit

**Date:** 2026-05-24
**Reviewer:** Claude Opus 4.7 (1M ctx) — max-effort, read-only
**Scope:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/{AgentService.kt, SharedCatalogHolder.kt, loop/, session/, memory/, observability/, hooks/, checkpoint/, security/, util/}` (~9.7K LoC)

---

## Findings

### F-1 [P0] [Security] Hook system spawns arbitrary shell from project-root JSON

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/hooks/HookRunner.kt:128-133`, `HookConfig.kt:83-111`

**Category**: Security

**Description**: `.agent-hooks.json` at the project root is auto-loaded at `AgentService.init` and every `command` is spawned through `/bin/sh -c …` (or `cmd /c` on Windows) on hook events such as `TaskStart`, `PreToolUse`, `PostToolUse`, `UserPromptSubmit`. Anyone who can land a file in the project root (`git clone` of a malicious repo, dependency snippet, `npm install` post-install script that drops a file, supply-chain attacker, a coworker on a shared SMB share) gains arbitrary shell execution the moment the user opens the project in IntelliJ. There is no signature, no allowlist, no per-project "trust" prompt, no UI surface to disable, and no log line summarising what was loaded beyond a hook count.

**Evidence**:
```kotlin
// hooks/HookRunner.kt:128-133
val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true
val command = if (isWindows) {
    listOf("cmd", "/c", hook.command)
} else {
    listOf("/bin/sh", "-c", hook.command)
}

// hooks/HookConfig.kt:83-111  (HookConfigLoader.load)
val configFile = File(projectPath, CONFIG_FILE_NAME)
if (!configFile.exists()) return emptyList()
…
HookConfig(type = hookType, command = entry.command, timeout = entry.timeout.coerceIn(1000, 120_000))

// AgentService.kt:294-296  (auto-load on service init)
val hookRunner = HookRunner(workingDir = basePath)
hookManager = HookManager(hookRunner)
hookManager.loadFromConfigFile(basePath)
```

**Impact**: Repository-driven RCE. Untrusted code execution when opening a project. Worse than the JetBrains `.idea/workspace.xml` family of trust prompts because there is no prompt at all.

**Fix sketch**: Gate auto-load on JetBrains' Trusted Projects API (`TrustedProjects.isTrusted(project)`), or store hooks per-IDE in `~/.workflow-orchestrator/hooks/{projectId}/` instead of the project root, or gate behind a user-visible "Enable agent hooks for this project" toggle whose default is OFF.

---

### F-2 [P0] [Security] Hook environment variables built by Map<String,Any?>.toString — injection-friendly

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/hooks/HookRunner.kt:140-147`

**Category**: Security

**Description**: Hook env vars (`HOOK_TASK`, `HOOK_MESSAGE`, `SESSION_ID`, etc.) are populated with `event.data[k]?.toString()`. `event.data` for `TASK_START` contains the user-typed `task` string (AgentService.kt:1485 — `"task" to task`). The hook command is interpreted by `/bin/sh -c hook.command`. If a hook command contains `$HOOK_TASK`, the user's prompt (which the LLM can be tricked into emitting) is interpolated into the shell at hook-invocation time. Worse, several entries are passed through `toString()` without any quoting — newline, backtick, or `$()` inside the task string lands directly in the env value.

**Evidence**:
```kotlin
// hooks/HookRunner.kt:140-147
environment().apply {
    put("HOOK_TYPE", event.type.hookName)
    event.data["sessionId"]?.toString()?.let { put("SESSION_ID", it) }
    event.data["toolName"]?.toString()?.let { put("TOOL_NAME", it) }
    event.data["taskId"]?.toString()?.let { put("TASK_ID", it) }
    event.data["message"]?.toString()?.let { put("HOOK_MESSAGE", it) }
    event.data["task"]?.toString()?.let { put("HOOK_TASK", it) }
}

// AgentService.kt:1485 — task is the user-typed prompt
"task" to task,
```

**Impact**: When combined with F-1, a malicious hook only needs `echo "$HOOK_TASK" | bash` to turn every user prompt into shell — prompt-injection into the user's own shell. Even with trusted hooks, the hook author often uses these env vars naïvely in subshell expansions.

**Fix sketch**: Document the env var convention as "untrusted user input — single-quote in shell hooks." Strip control characters (`\n`, NUL, `\r`) before `put()`. Cap each env value at e.g. 4 KB so a 10 MB attachment payload doesn't end up in `environ`. Consider passing the same data only via the stdin JSON.

---

### F-3 [P0] [Security] Path traversal in `SessionCheckpointStore.snapshotRelative` — Windows drive-letter handling is incomplete

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/checkpoint/SessionCheckpointStore.kt:218-223`, used at 55, 96, 154, 192

**Category**: Security

**Description**: `snapshotRelative()` strips POSIX leading `/` and the colon after a Windows drive letter (`C:` → `C`), but it does NOT reject paths containing `..` segments. The agent's write tools normalise paths through `PathValidator` for the *real* target file, but the checkpoint store is fed the same path verbatim and uses it to build a destination under `sessions/{sid}/checkpoints/msg-{ts}/files/<snapshot-rel>`. A malicious or even merely sloppy path like `../../etc/passwd` becomes `etc/passwd` after `trimStart('/')`, but a path like `/Users/x/../../../../../tmp/foo` stays as `Users/x/../../../../../tmp/foo` and `File(filesRoot, …)` happily walks out of `filesRoot`. Equally, `revertFileToBaseline(absolutePath)` writes `snap.copyTo(File(absolutePath), overwrite = true)` with **no canonicalisation or allowlist** — if a stale `meta.json` from another session is replayed (or a malicious one is dropped in `checkpoints/msg-…/meta.json`), the agent will happily restore bytes into any absolute path.

**Evidence**:
```kotlin
// checkpoint/SessionCheckpointStore.kt:218-223
private fun snapshotRelative(absolutePath: String): String =
    absolutePath
        .replace('\\', '/')
        .replace(Regex("^([A-Za-z]):"), "$1") // strip the ":" off "C:" → "C"
        .trimStart('/')

// checkpoint/SessionCheckpointStore.kt:152-160 (revert path)
for ((path, cp) in earliestTouchedByPath) {
    if (path in createdInRange) continue
    val snapFile = File(File(checkpointsDir, "msg-${cp.messageTs}/files"), snapshotRelative(path))
    if (!snapFile.exists()) continue
    val dst = File(path)
    dst.parentFile?.mkdirs()
    snapFile.copyTo(dst, overwrite = true)   // writes anywhere `path` points
    restored.add(path)
}
```

**Impact**: (a) Checkpoint snapshot written outside the session dir tree; (b) on revert, attacker-controlled `meta.json` rewrites arbitrary files (e.g. `/Users/$USER/.ssh/authorized_keys`) using bytes the attacker pre-staged under `files/`. The attack surface is narrow (requires write to `~/.workflow-orchestrator/{proj}/agent/sessions/{sid}/checkpoints/`) but the home dir is world-traversable on shared machines.

**Fix sketch**: After `snapshotRelative`, reject any component equal to `..` and verify `snapFile.canonicalPath.startsWith(filesRoot.canonicalPath + File.separator)`. On revert, only write to paths that originated from the agent's own write-tool capture in this session (cross-check against the session's persisted UI/api history's `touchedPaths`).

---

### F-4 [P0] [Security] Atomic file writer follows symlinks — TOCTOU on `~/.workflow-orchestrator/`

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/AtomicFileWriter.kt:22-58`, `memory/MemoryIndex.kt:220-228`

**Category**: Security

**Description**: `AtomicFileWriter.write(target, content)` writes to `{target.parent}/{target.name}.tmp.{ms}.{rand}` and then `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`. There is no `LinkOption.NOFOLLOW_LINKS`, no `O_NOFOLLOW`, no symlink check, and no permission set on the resulting file. If `target` (e.g. `sessions.json`, `ui_messages.json`, `api_conversation_history.json`, `MEMORY.md`) is replaced by a symlink to a victim file (`~/.ssh/authorized_keys`, `~/.zshrc`, `/etc/hosts` when run as root), the next write overwrites the victim with JSON content. The `.tmp` filename is partly predictable (`Math.random() * 100000` is not cryptographically random; on macOS `/tmp` is a shared symlink farm but the writer uses a sibling — the attack moves to `~/.workflow-orchestrator/`).

**Evidence**:
```kotlin
// session/AtomicFileWriter.kt:22-32
fun write(target: File, content: String) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parent, "${target.name}.tmp.${System.currentTimeMillis()}.${(Math.random() * 100000).toInt()}")
    try {
        tmp.writeText(content, Charsets.UTF_8)
        moveWithRetry(tmp.toPath(), target.toPath())
    } catch (e: Exception) {
        tmp.delete()
        throw e
    }
}
// no LinkOption.NOFOLLOW_LINKS; no PosixFilePermissions
```

**Impact**: Local user can elevate their access via a planted symlink in the user's `~/.workflow-orchestrator/` directory.

**Fix sketch**: Use `Files.write(tmp, …, StandardOpenOption.CREATE_NEW)` (refuses to follow), set `PosixFilePermissions.fromString("rw-------")` on creation, and check `Files.isSymbolicLink(target)` before move. On Windows, use `Files.setAttribute(tmp, "dos:hidden", true)` plus an ACL deny for non-owner users. **NEEDS HUMAN VERIFICATION** of the threat model — single-user dev laptop or shared CI agent?

---

### F-5 [P0] [Security] Persisted JSON files are world-readable by default (no permission set)

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/AtomicFileWriter.kt`, `session/AttachmentStore.kt:65-100`, `memory/MemoryIndex.kt:220-228`

**Category**: Security

**Description**: Every persisted artefact — `api_conversation_history.json` (contains user prompts and LLM output that often quotes credentials), `ui_messages.json`, `MEMORY.md`, attachment image bytes — is written with default umask. On macOS / Linux this is typically `0644` (world-readable). Conversation history routinely contains source code, tokens accidentally pasted by the user, and `run_command` output that may leak secrets (`AgentFileLogger.args = call.function.arguments.take(500)` — 500 chars of JSON that may include `Authorization` headers). On a multi-user box (CI runner, shared dev VM), every user can read every other user's plugin history.

**Evidence**: Same as F-4 — no `PosixFilePermissions` is set anywhere in the audited scope. `find` over the source confirms `setReadable`, `setWritable`, `setExecutable`, `PosixFilePermissions` are unused.

**Impact**: Secrets exfiltration on multi-user hosts; PII leakage; corporate data exposure on shared CI runners.

**Fix sketch**: Wrap `AtomicFileWriter.write` with `PosixFilePermissions.fromString("rw-------")` (Unix) / equivalent ACL (Windows) at creation time. Same for `AttachmentStore`'s image bytes, `AgentFileLogger`'s `agent-YYYY-MM-DD.jsonl`, and `MemoryIndex.atomicWrite`.

---

### F-6 [P0] [Security] HookRunner does NOT redact credentials from hook command output, stderr lands in `HookResult.Cancel.reason`

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/hooks/HookRunner.kt:230-233`, returning into `loop/ContextManager.kt:1492+` via callers

**Category**: Security

**Description**: When a non-cancellable hook exits non-zero, the stderr (trimmed, no size cap on `.trim()` then truncated implicitly by serialization but the in-memory string is unbounded) is propagated as `HookResult.Cancel.reason` and then surfaced into the conversation history via `[Hook] cancelled` messages. If a hook command leaks a token in its stderr (e.g. `curl -v` printing the Bearer header), the token lands in the api_conversation_history.json that gets sent to the LLM and persisted. `CredentialRedactor` exists in `agent/security/CredentialRedactor.kt` but is never called from this path.

**Evidence**:
```kotlin
// hooks/HookRunner.kt:230-233
if (result.exitCode != 0 && event.cancellable) {
    val reason = result.stderr.trim().takeIf { it.isNotEmpty() }
        ?: "Hook exited with code ${result.exitCode}"
    return HookResult.Cancel(reason = reason)
}
```

`CredentialRedactor.redact()` is defined (security/CredentialRedactor.kt:42) but `grep -r CredentialRedactor` shows zero call sites under `agent/loop`, `agent/session`, `agent/hooks`, `agent/observability`.

**Impact**: Credentials in hook stderr become permanent context-poisoning content, get sent to the LLM provider, and persist to disk in `api_conversation_history.json`.

**Fix sketch**: Pipe every hook stderr/stdout through `CredentialRedactor.redact()` before constructing `HookResult`. Apply the same redaction to `AgentFileLogger.write` for `args`, `error`, and other free-text fields.

---

### F-7 [P0] [Leak] `AgentFileLogger` never closed — file handle leaked per AgentService instance

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/observability/AgentFileLogger.kt:206-210`, `AgentService.kt:2750-2754`

**Category**: Leak

**Description**: `AgentFileLogger` opens a `PrintWriter(FileWriter(file, true), true)` and caches it in a field, rotating on date change. There is a `close()` method that closes the writer and nulls the field — but `AgentService.dispose()` (the lone candidate consumer) does NOT call it. Every project close leaks one PrintWriter + FileChannel until the JVM exits. On long IDE sessions with many project-open/close cycles, file descriptors accumulate; on Windows the underlying file becomes unmovable.

**Evidence**:
```kotlin
// AgentService.kt:2750-2754
override fun dispose() {
    cancelCurrentTask()
    ProcessRegistry.killAll()
    debugController?.dispose()
}

// AgentFileLogger.kt:206-210  — close() exists but is unused
fun close() {
    writer?.close()
    writer = null
    currentDate = null
}
```

`grep "fileLogger\\.close\\|AgentFileLogger.*close" agent/src/main/` returns no production call site.

**Impact**: FD leak. On Windows, the day-rotation file is held even when a new IDE instance writes the same `.jsonl` — causing `AccessDeniedException` in `AtomicFileWriter` (the very condition that motivated the retry loop).

**Fix sketch**: `AgentService.dispose()` should call `fileLogger.close()` (or register the lazy holder as a `Disposable` and `Disposer.register(this, fileLogger)`).

---

### F-8 [P0] [Threading] `pendingModelChange.compareAndSet(applied, null)` race: pending change is silently dropped if the user submits two changes back-to-back

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt:2080-2083`, `loop/AgentLoop.kt:801-814`

**Category**: Threading

**Description**: `onModelChangeApplied = { applied -> pendingModelChange.compareAndSet(applied, null) }` only clears the pending state when the still-pending value equals `applied`. The intent is "don't clear a newer pending change," but there's no follow-up: if the user clicked twice quickly (`A`, then `B` while loop is between iterations), the loop applies `A`, fires `onModelChangeApplied(A)`, the CAS fails because pending is now `B` — and the next iteration applies `B`. That is the correct behavior. BUT — the loop reads `pendingModelChangeProvider()` at iteration boundary, applies the change, and only THEN calls `onModelChangeApplied`. Between the read and the callback, the user can click again. If the third click was identical to `applied`, it's now silently lost (CAS clears it). NEEDS HUMAN VERIFICATION — the failure window is tiny and the practical impact is "user clicks the same model twice in 200ms, second click is no-op." Probably benign but worth flagging.

**Evidence**:
```kotlin
// AgentService.kt:2080-2083
pendingModelChangeProvider = { pendingModelChange.get() },
onModelChangeApplied = { applied ->
    pendingModelChange.compareAndSet(applied, null)
},

// AgentLoop.kt:801
pendingModelChangeProvider?.invoke()?.takeIf { it.isNotBlank() && it != brain.modelId }?.let { newModel ->
    …
    brain = factory.invoke(newModel, "User-requested model change")
    …
    onModelChangeApplied?.invoke(newModel)   // CAS races vs. fresh user click
}
```

**Impact**: Rare lost user input. NEEDS HUMAN VERIFICATION on whether the dropdown debounces clicks.

**Fix sketch**: Use a `getAndSet(null)` if the value matches `newModel` BEFORE calling factory; or move clear into the same critical section. Better: keep a generation counter alongside the model id.

---

### F-9 [P0] [Threading] `MessageStateHandler.dialectDriftFlag.set(true)` outside mutex — TOCTOU with `consumeDialectDriftFlag()`

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/MessageStateHandler.kt:111, 177, 191`

**Category**: Threading

**Description**: `addToApiConversationHistory` rejects a drift-laden message and calls `dialectDriftFlag.set(true)` inside `mutex.withLock`. `redactDialectXmlInHistory` ALSO sets the flag inside the mutex. But `consumeDialectDriftFlag()` is **not** suspending and is read from outside the mutex (it's invoked synchronously from the `systemPromptBuilder` lambda at `AgentService.kt:1780`). Concurrency timeline: drift detected → flag=true → system prompt rebuild happens BEFORE drift fully persists (the next assistant turn) → flag.getAndSet(false) flushes early → next turn's drift gets the flag re-raised but the corrective system-reminder was already sent in a previous turn for a different message. Not a security bug but a subtle correctness one for the rare race.

**Evidence**: `MessageStateHandler.kt:191` is `fun consumeDialectDriftFlag(): Boolean = dialectDriftFlag.getAndSet(false)` — no mutex. `systemPromptBuilder` at `AgentService.kt:1780` calls it on every prompt rebuild.

**Impact**: Edge-case loss of the corrective system reminder. Cosmetic — drift detection still rejects subsequent turns.

**Fix sketch**: Either pair the flag set with the prompt-rebuild trigger via a single atomic transition, or accept the looseness and document it. AtomicBoolean is fine; the issue is only the read/write ordering vs. when the prompt is built.

---

### F-10 [P0] [Leak] `MemoryIndex.indexLocks` ConcurrentHashMap grows unbounded across projects

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/memory/MemoryIndex.kt:79-81`

**Category**: Leak

**Description**: `indexLocks` is a `ConcurrentHashMap<String, Any>` keyed by the absolute path of every memory directory ever touched in this JVM. Entries are never removed. In a 24-hour IntelliJ session that opens and closes 50 projects, 50 entries accumulate; this is small but the pattern is wrong for a module that wants to be project-scoped (its callers are project-scoped services). The `Any` lock objects are tiny but the strings are long.

**Evidence**:
```kotlin
// memory/MemoryIndex.kt:79-81
private val indexLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
private fun lockFor(memoryDir: Path): Any =
    indexLocks.computeIfAbsent(memoryDir.toAbsolutePath().toString()) { Object() }
```

**Impact**: Slow leak (~256 bytes per project). Confused threading model — the locks survive `dispose`.

**Fix sketch**: Cap with a `LinkedHashMap` bounded LRU, OR move into a project-scoped `@Service` and let IntelliJ dispose it.

---

### F-11 [P0] [Threading] `AgentService.cancelCurrentTask()` only cancels one task — concurrent `executeTask` calls race the AtomicReference

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt:2103, 2696-2719`

**Category**: Threading

**Description**: `activeTask.set(ActiveTask(...))` is called inside the launched coroutine at line 2103 — but the `Job` returned from `executeTask` (line 2232) is already in the caller's hands by then. If a second `executeTask` lands in the brief window between `cs.launch` (line 1475) and `activeTask.set(...)` (line 2103), it overwrites the AtomicReference with the second task's data, then the FIRST task's coroutine writes back at line 2103, clobbering the second task. `cancelCurrentTask()` then cancels whichever happens to be currently stored — possibly not what the user wanted. Same race on `activeMessageStateHandler` and `taskStore`. The `private val activeTask = AtomicReference<ActiveTask?>(null)` is treated like a serial slot, but nothing enforces serial execution.

**Evidence**:
```kotlin
// AgentService.kt:1475-2103
val job = cs.launch(Dispatchers.IO) {
    …  // many lines later
    activeTask.set(ActiveTask(sessionId = sid, loop = loop, job = coroutineContext.job))
    val result = loop.run(task, attachments)
    …
} finally {
    activeTask.set(null)        // racey: clears wrong task if a new one started
    activeMessageStateHandler = null
    taskStore = null
    …
}
return job
```

**Impact**: Cross-task state corruption when the UI dispatches two tasks in rapid succession. In practice the controller serialises this, but the API surface doesn't enforce it.

**Fix sketch**: Either reject re-entrant `executeTask` calls (return null/throw if `activeTask.get() != null`), or move `activeTask.set` to the top of the launched block AND CAS-protected so a second task aborts/queues. Document the single-task contract.

---

### F-12 [P1] [Correctness] `MemoryIndex.load()` keeps last N lines but documentation says new entries belong at the top — semantic mismatch

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/memory/MemoryIndex.kt:42-49`

**Category**: Correctness

**Description**: `load()` truncates at `MAX_LINES=200` by keeping `lines.takeLast(MAX_LINES)`. The docstring (lines 14-19) says "the prompt instructs the LLM to insert new entries at the top of their section" — but `takeLast` drops the **top** lines (most-recently-written per the docstring's stated convention), keeping the **bottom** (oldest). The injected truncation banner contradicts itself: it tells the LLM to insert at top to stay in-prompt, but the truncator keeps the bottom. NEEDS HUMAN VERIFICATION: I think the user's MEMORY.md actually inserts new entries at top of each section (per the user's own MEMORY.md format), in which case this truncation drops the newest entries every time the file grows past 200 lines.

**Evidence**:
```kotlin
// memory/MemoryIndex.kt:42-49
return if (lines.size <= MAX_LINES) {
    raw
} else {
    buildString {
        append("<!-- MEMORY.md truncated at $MAX_LINES lines (file has ${lines.size}) — older entries above this line were omitted; insert new entries near the top of their section to keep them in-prompt. -->\n")
        lines.takeLast(MAX_LINES).joinToString(this, "\n")   // keeps BOTTOM = oldest if top-insert convention holds
    }
}
```

**Impact**: Newest memory entries are silently dropped from prompt injection. The user's instructions say "insert at top to keep in-prompt" but `takeLast` keeps the bottom.

**Fix sketch**: Change to `lines.take(MAX_LINES)` if top-insert convention is correct, OR change the banner text to "insert new entries at the BOTTOM…" if `takeLast` is intentional. Adding a test that pins the truncation direction would help.

---

### F-13 [P1] [Correctness] `hookManager` field is initialised AFTER `registerAllTools()` reads `ideContext` — race risk on hot-reload paths

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt:288-298`

**Category**: Correctness

**Description**: `AgentService.init` calls `registerAllTools()` (which sets `ideContext`), then `loadFromConfigFile`. But `hookManager` is declared as `val` and assigned BEFORE `registerAllTools()`:

```
val hookRunner = HookRunner(workingDir = basePath)
hookManager = HookManager(hookRunner)        // line 295
hookManager.loadFromConfigFile(basePath)     // line 296
registerAllTools()                            // line 298
```

This is correct ordering. The concern: `hookManager` is `lateinit` initialised via constructor of class, but tools registered in `registerAllTools()` read `hookManager.hasAnyHooks()` indirectly. Looking again — the read happens inside `executeTask`, well after init, so this is fine. Documentation: this is FALSE POSITIVE under review. Marking as resolved.

**Evidence**: Re-read confirms hook init precedes `registerAllTools`. NO ISSUE — removing from final summary. *(left in report for transparency of audit process)*

---

### F-14 [P1] [Correctness] `ResumeHelper.popTrailingUserMessage` doesn't pop tool-result-only user messages — resume loop may double-fire

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/ResumeHelper.kt:53-64`

**Category**: Correctness

**Description**: `popTrailingUserMessage` pops only `ApiRole.USER` content if the trailing message is `USER`. After the XML-in-content migration, tool-result blocks are persisted as `USER` role with a `ContentBlock.ToolResult`. If a session was interrupted **after** the assistant emitted a tool call but **before** the tool result was persisted, the trailing user message is a real user prompt — pop is fine. But if it was interrupted DURING tool execution, the trailing entry could be the (assistant w/ tool_use) and there's no tool_result yet — pop does nothing on the assistant turn, and the LLM will see the prior assistant message and try to re-issue the tool call. NEEDS HUMAN VERIFICATION: the comment in `MessageStateHandler.collapseLastCompletionToolPair` and the resume path's `pruneTrailingEmptyAssistants` suggest this is handled elsewhere, but the interaction is subtle.

**Evidence**:
```kotlin
// session/ResumeHelper.kt:53-64
fun popTrailingUserMessage(apiHistory: List<ApiMessage>): PopResult {
    if (apiHistory.isEmpty()) return PopResult(apiHistory, emptyList())
    val last = apiHistory.last()
    return if (last.role == ApiRole.USER) {
        PopResult(trimmedHistory = apiHistory.dropLast(1), poppedContent = last.content)
    } else {
        PopResult(trimmedHistory = apiHistory, poppedContent = emptyList())
    }
}
```

**Impact**: Possible duplicated tool calls on resume. Mitigated by `collapseLastCompletionToolPair`.

**Fix sketch**: Add a comment clarifying the invariant; consider unifying the resume-time cleanup paths.

---

### F-15 [P1] [Threading] `Files.list(attachmentsDir).use { … findFirst() }` opens a directory stream every `read` call — O(N) per read on growing sessions

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/AttachmentStore.kt:114-123`

**Category**: Perf

**Description**: `readBlocking(sha256)` opens a `Files.list(dir)` stream and filters by filename-prefix. For a session with K image attachments, every read is O(K) plus a directory-entry scan. `BrainRouter` hydrates all attachments per call, so per-LLM-turn cost grows quadratically (M images per turn × K images on disk).

**Evidence**:
```kotlin
// session/AttachmentStore.kt:114-123
fun readBlocking(sha256: String): ByteArray? {
    if (!Files.exists(attachmentsDir)) return null
    val match = Files.list(attachmentsDir).use { stream ->
        stream.filter { it.fileName.toString().startsWith("$sha256.") }.findFirst().orElse(null)
    }
    return match?.let { Files.readAllBytes(it) }
}
```

**Impact**: Multi-MB attachment sessions show user-visible latency before the LLM call.

**Fix sketch**: Maintain an in-memory `sha256 → extension` map populated at first `store`/`load`. Or use `pathFor(sha256, knownExt)` and try a fixed extension list (`png|jpg|webp|gif|heic`) before scanning.

---

### F-16 [P1] [Correctness] `compactJson.decodeFromString<List<HistoryItem>>(globalIndexFile.readText())` loads entire file into memory; no upper bound

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/MessageStateHandler.kt:496, 587, 632, 644, 670, 726`

**Category**: Perf / Correctness

**Description**: Every load of `sessions.json` / `ui_messages.json` / `api_conversation_history.json` calls `file.readText()` followed by `decodeFromString`. For long-running sessions the api_conversation_history can grow to 10-50 MB. No streaming parser, no upper-byte gate, no warning at threshold. The blocking `FileLock` in `withGlobalIndexFileLock` holds the lock for the entire duration of read+decode+write.

**Evidence**: Counts shown by `grep` above — 6 sites.

**Impact**: UI freeze on session-list load for users with very long history. Cross-window contention (two IDE instances) blocks for the duration of the slow read.

**Fix sketch**: Cap input size at e.g. 64 MB and warn; use `Json.decodeFromStream(Files.newInputStream(file))` to stream; release the FileLock as soon as the bytes are read.

---

### F-17 [P1] [Threading] `MessageStateHandler.uiMessages` / `apiHistory` are `MutableList<...>` — `getClineMessages()` returns a defensive `.toList()` but mutation happens outside the lock during init

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/MessageStateHandler.kt:419-430`

**Category**: Threading

**Description**: `setClineMessages` / `setApiConversationHistory` mutate the underlying lists with `check(!mutex.isLocked) { … }` as the only guard. The check is racy (the mutex could be acquired the instant after the check returns true) and `MutableList<...>` is not safe for concurrent reader (a coroutine in `addToClineMessages` could still hold a reference even though `mutex.isLocked` flickered to false). This is documented as "Call ONLY during initialization" — but the call sites (AgentService.kt:1885, 1886, 2437, 2438, 2611, 2612) include resume paths and revert paths that happen after the handler is exposed to other coroutines via `activeMessageStateHandler` assignment.

**Evidence**:
```kotlin
// session/MessageStateHandler.kt:419-423
fun setClineMessages(messages: List<UiMessage>) {
    check(!mutex.isLocked) { "setClineMessages must only be called during init, before concurrent access" }
    uiMessages.clear()
    uiMessages.addAll(messages)
}

// AgentService.kt:2610-2612 — handler created fresh inside revertToUserMessage,
// then setClineMessages called after that handler is exposed to the read path
val handler = MessageStateHandler(baseDir = sessionBaseDir, sessionId = sessionId, taskText = "")
if (existingUi.isNotEmpty()) handler.setClineMessages(existingUi)
```

In `revertToUserMessage`, the handler IS fresh (not exposed yet), so this case is OK. In `executeTask` line 1885-1886, the handler is built and `setClineMessages` runs BEFORE `activeMessageStateHandler = messageState` (line 1929). Same in resume (2437-2438 before innerJob.join). So the production paths are OK, but the `check(!mutex.isLocked)` guard provides false safety — `Mutex.isLocked` can change in the next instruction.

**Impact**: Defensive check is misleading; future refactors that add a `setClineMessages` call after the handler is shared will fail intermittently.

**Fix sketch**: Either fold the setters into the constructor (init-only contract enforced by the type system), or replace the racy check with `kotlinx.coroutines.sync.Mutex.tryLock` + immediate release before mutation.

---

### F-18 [P1] [Correctness] `HookRunner` `process.destroyForcibly()` in finally without grace period — leaves hooks no chance to clean up

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/hooks/HookRunner.kt:160-180`

**Category**: Correctness

**Description**: `runProcess` always calls `process.destroyForcibly()` in finally, even after the process exited normally. On Unix this is a no-op for an already-exited process, but on Windows `Process.destroyForcibly` is documented to have implementation-defined behavior post-exit. More importantly, on timeout `withTimeoutOrNull` cancels the coroutine; the `finally { destroyForcibly() }` runs **after** the wait completes successfully (because the cancellation unblocks `process.waitFor()` via InterruptedException). The hook gets a SIGKILL with no chance to flush stdout, losing the JSON response that might have been written.

**Evidence**:
```kotlin
// hooks/HookRunner.kt:160-180
val process = processBuilder.start()
try {
    process.outputStream.use { stdin -> … }
    val stdout = readLimited(process.inputStream, MAX_OUTPUT_SIZE)
    val stderr = readLimited(process.errorStream, MAX_OUTPUT_SIZE)
    val exitCode = process.waitFor()
    return ProcessResult(exitCode, stdout, stderr)
} finally {
    process.destroyForcibly()   // always — even on clean exit
}
```

**Impact**: Hooks lose their last buffered output bytes on timeout; on Windows, post-exit `destroyForcibly` can throw.

**Fix sketch**: Only call `destroyForcibly` when `!process.waitFor(0, TimeUnit.MILLISECONDS)` (still alive). On timeout, prefer `destroy()` first, wait 2 seconds, then `destroyForcibly()`. Wrap in `try { destroy() } catch (_: Throwable) {}`.

---

### F-19 [P1] [Correctness] `TaskStore.getTask` and `listTasks` are unsynchronised reads with deliberate dirty-read — documented but fragile

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/TaskStore.kt:53-65`

**Category**: Correctness

**Description**: `getTask` and `listTasks` are documented to bypass the mutex because `updateTask` calls them re-entrantly. This means a coroutine writing a task can observe a half-mutated snapshot (the comment is honest about it). The fragility: any new method that takes the mutex and then calls `listTasks` will produce inconsistent reads. The `cycle-detection` snapshot is built from `tasks.associateBy`, which iterates the live mutable list — if another coroutine adds a task during cycle detection, the snapshot is inconsistent.

**Evidence**:
```kotlin
// session/TaskStore.kt:82-86
private fun checkNoCycles(candidate: Task) {
    val snapshot = tasks.associateBy { it.id } + (candidate.id to candidate)
    …
}
```

The current call sites are all under `mutex.withLock`, so cycle detection is safe. Listed for vigilance.

**Impact**: Low today; brittle to refactors.

**Fix sketch**: Either snapshot inside the lock and pass to cycle detector, or document the invariant more loudly with a comment marker.

---

### F-20 [P1] [Quality] `ModelFallbackManager` documented as user-may-remove, still present and wired into the loop

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ModelFallbackManager.kt:1-97`, `loop/AgentLoop.kt:275-279`, `AgentService.kt:1614-1617`

**Category**: Quality

**Description**: Per user MEMORY.md (`feedback_no_model_fallback_for_empties.md`): "User plans to remove or gate ModelFallbackManager. Fix at SSE/connection layer instead; same-model brain recycle is fine." `ModelFallbackManager` is still 97 lines and is wired into AgentLoop's L1 fallback path (lines 1097-1178). The user's plan to remove it has not yet been executed.

**Evidence**: File exists at full length; `AgentService.kt:1614` instantiates it when `strategy == "model_fallback"`.

**Impact**: Code debt; the feature is deprecated per user intent.

**Fix sketch**: Either gate behind a setting and document deprecation, or remove the L1 path entirely and rely on L2 tier escalation + same-tier recycle. Stayed flagged per the user's documented preference.

---

### F-21 [P1] [Correctness] `ContextManager.collapseLastCompletionToolPair` uses `tail.toolCallId` which is null after the XML-in-content migration

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt:287-314`

**Category**: Correctness

**Description**: After the 2026-05-13 XML-in-content migration, assistant turns no longer carry `toolCalls` (per `agent/CLAUDE.md`). Yet `collapseLastCompletionToolPair` here looks for `penult.toolCalls` and matches by `it.id == toolCallId`. The trailing `tool` role message DOES carry `toolCallId` (it's used by the message sanitizer), so the matching logic still works for one direction — but the `penult.toolCalls.isNullOrEmpty()` check will be TRUE for the new in-memory shape, so the early return fires and collapse never happens for new-shape sessions. `MessageStateHandler.collapseLastCompletionToolPair` (the disk side) was updated to handle the new shape with `matchingXmlToolName` lookup — but the in-memory `ContextManager` version was not. Result: the failure modes the disk-side fix prevents (tool-result→user merge, resume auto-iterates) still happen in memory.

**Evidence**:
```kotlin
// loop/ContextManager.kt:287-314
fun collapseLastCompletionToolPair(): Boolean {
    if (messages.size < 2) return false
    val tail = messages.last()
    if (tail.role != "tool") return false
    val toolCallId = tail.toolCallId ?: return false

    val penult = messages[messages.size - 2]
    if (penult.role != "assistant") return false
    val penultToolCalls = penult.toolCalls
    if (penultToolCalls.isNullOrEmpty()) return false      // ← always true post-migration
    val matchingCall = penultToolCalls.firstOrNull {
        it.id == toolCallId && it.function.name in COMPLETION_TOOL_NAMES
    } ?: return false
    …
}
```

Contrast `MessageStateHandler.kt:239-294` which DOES handle the new shape.

**Impact**: In-memory pair collapse is a no-op for new sessions; only the disk-side cleanup runs (called from `AgentLoop.run` at line 733-734). The in-memory equivalent failed silently — meaning at every follow-up user message within the SAME loop instance (multi-turn before exit), the assistant turn's XML still references the prior `attempt_completion`, increasing the LLM's chance to re-issue completion.

**Fix sketch**: Mirror `MessageStateHandler.collapseLastCompletionToolPair`'s new-shape detection in `ContextManager`. Scan `penult.content` for `<attempt_completion>` / `<task_report>` XML and treat as a match.

---

### F-22 [P1] [Correctness] `compactDegenerate` returns `Failed` if summarization fails — but no `slidingWindow(0.3)` fallback at the caller

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt:559-581`, callers at `AgentLoop.kt:823-830` and `loop/AgentLoop.kt:1061-1076`

**Category**: Correctness

**Description**: `compactDegenerate` returns `CompactResult.Failed("Degenerate-path summarization failed")`. The callers (AgentLoop's three compaction sites) handle `Failed` by calling `slidingWindow(0.3)` — good. But `slidingWindow` requires a `user` role message to split safely (see `findSafeSplitPoint`); the degenerate path is *defined* as "no user message at all", so `slidingWindow` returns without modifying messages. Result: the loop sees compaction "failed" + sliding fell through, and on the next iteration the context is still over 88% and we loop forever (until max_iterations) or context overflow → context_overflow_retries exhausted → makeFailed.

**Evidence**: `ContextManager.compactDegenerate` returns Failed on LLM error; `ContextManager.slidingWindow` returns silently when `findSafeSplitPoint` returns `messages.size`.

**Impact**: Degenerate sessions (a session resumed in an odd state, or a fresh session whose user message was rejected by `addToApiConversationHistory`) can soft-loop until max-iterations.

**Fix sketch**: In `slidingWindow`, when no user message exists, fall back to keeping the last `keepCount` messages regardless of role. Add a test for "all-assistant history at >88% utilization."

---

### F-23 [P1] [Correctness] `cleanupOrphanSessions` uses `child.lastModified()` — vulnerable to clock skew + filesystem-level mtime updates

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/MessageStateHandler.kt:700-718`

**Category**: Correctness

**Description**: Orphan-session cleanup compares `child.lastModified()` against `now - 30 days`. If the user's clock was wrong, or the session dir got `touch`-ed by a backup tool (Time Machine, rsync), or the FS doesn't preserve mtime accurately, the cleanup either silently does nothing OR aggressively deletes a "fresh" session whose mtime is far in the past. No log line about what gets deleted, no dry-run, no audit trail.

**Evidence**:
```kotlin
// session/MessageStateHandler.kt:700-718
fun cleanupOrphanSessions(baseDir: File, olderThanMs: Long = 30L * 24 * 60 * 60 * 1000): Int {
    …
    val cutoff = System.currentTimeMillis() - olderThanMs
    var removed = 0
    val children = sessionsRoot.listFiles() ?: return 0
    for (child in children) {
        if (!child.isDirectory) continue
        if (child.name in knownIds) continue
        if (child.lastModified() >= cutoff) continue
        try {
            if (child.deleteRecursively()) removed++   // silent destruction
        } catch (_: Exception) { /* skip, try next */ }
    }
    return removed
}
```

**Impact**: Silent data loss on backup-restored / clock-skewed systems.

**Fix sketch**: Log the names of deleted dirs (at INFO level); accept an `dryRun` flag for visibility; prefer mtime of a known file inside (e.g. `meta.json`) over the dir mtime.

---

### F-24 [P1] [Perf] `DefaultBranchResolver.resolve` launched inside `executeTask` body fires twice on every user message (no cache reuse)

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt:1960-1970`

**Category**: Perf

**Description**: Each `executeTask` call (each user message) launches a coroutine to call `DefaultBranchResolver.getInstance(project).resolve(primary)` with a 3s timeout, then stores the result in a local `AtomicReference`. The reference is captured by the `environmentDetailsProvider` lambda — but `DefaultBranchResolver` is a project-level service with its own cache, so it's redundant. More important: the 3s timeout fires on every turn even when the underlying resolver is cached.

**Evidence**: Lines 1960-1970 — `launch(Dispatchers.IO) { … withTimeoutOrNull(3000L) { … resolve(primary) } }` inside `executeTask`.

**Impact**: 0-3s delay per turn before the first environment-details injection sees the branch. Battery on long IDE sessions.

**Fix sketch**: Move the resolution to AgentService init / cache at session level.

---

### F-25 [P1] [Correctness] `_outputSpiller` is mutated by every `executeTask` call but never cleared on failure paths inside the launched coroutine

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt:1568, 2739`

**Category**: Correctness

**Description**: `_outputSpiller = ToolOutputSpiller(...)` is set inside the launched coroutine at line 1568. On task failure / cancellation / exception, the finally block (lines 2207-2229) does NOT clear `_outputSpiller`. It's only cleared on the next `resetForNewChat`. Between failed task end and next chat start, any tool that reads `outputSpiller` writes into the previous session's `tool-output/` directory.

**Evidence**:
```kotlin
// AgentService.kt:1568
_outputSpiller = ToolOutputSpiller(
    java.io.File(sessionDebugDir, "tool-output").toPath()
)
// finally block at 2207-2229 does NOT touch _outputSpiller
```

**Impact**: Stale spiller pointing at the previous session's directory; future tool output cross-contaminates session disks.

**Fix sketch**: Clear `_outputSpiller = null` in the finally block when the task ended. Same for `activeAttachmentStore`.

---

### F-26 [P2] [Quality] Magic numbers throughout: 200 (memory lines), 88 (compaction %), 30K (output spill), 5/3 (loop thresholds), 30s (hook timeout), 30 days (orphan cleanup), 5000 (LCS lines)

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/memory/MemoryIndex.kt:24`, `loop/ContextManager.kt:42`, `loop/AgentLoop.kt:546-567`, `hooks/HookConfig.kt:28`, `session/MessageStateHandler.kt:700`, `checkpoint/DiffCalculator.kt:6`

**Category**: Quality

**Description**: Hardcoded thresholds scattered. None of them are user-tunable through a settings UI, and none have justification in code comments.

**Impact**: Tuning requires source changes; ops cannot adjust without redeploy.

**Fix sketch**: Lift into `PluginSettings` with sensible defaults, document the rationale next to each.

---

### F-27 [P2] [Quality] Five JSON instances in `MessageStateHandler.companion` — duplication

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/MessageStateHandler.kt:549-574`

**Category**: Quality

**Description**: Five `Json { … }` configurations: `configuredJson`, `configuredPrettyJson`, `compactJson`, `prettyJsonStatic`, and member-level `json`/`prettyJson`. They differ in `prettyPrint` and `encodeDefaults`. Three of them share the contentBlockModule, two don't (compactJson doesn't enable `encodeDefaults`). Lots of subtle config drift potential.

**Impact**: Future field additions might encode differently across read/write paths.

**Fix sketch**: Two named Json instances (PRETTY, COMPACT), both sharing the same serializersModule and `encodeDefaults=true`.

---

### F-28 [P2] [Quality] `BackgroundProcessTool.currentSessionId` ThreadLocal; same for `RunCommandTool` — explicit TODO acknowledges the smell

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt:1948-1950, 2013-2015, 671-680`

**Category**: Quality

**Description**: ThreadLocal-based session id and tool-call id for streaming tools, with a TODO to migrate to CoroutineContext element. Since these are read inside coroutine code that may dispatch across threads, the ThreadLocal can be empty or refer to the wrong session if the streaming tool migrates to a different worker thread mid-execution. Currently safe because of `Dispatchers.IO`'s pinning, but Dispatchers don't guarantee thread affinity.

**Impact**: Latent bug — if a future tool implementation hops dispatchers, output is mis-routed.

**Fix sketch**: Replace with `CoroutineContext.Element` (already a pattern in `AgentLoopAttachmentScope`).

---

### F-29 [P0] [Threading] `HookManager` mutable map `hooks` is not synchronized; `register` / `dispatch` race

**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/hooks/HookManager.kt:28, 39-42, 105-152`

**Category**: Threading

**Description**: `private val hooks = mutableMapOf<HookType, MutableList<HookConfig>>()`. `register` mutates it; `dispatch` reads it. `clearAll` removes everything. There is no mutex, no @Synchronized, no `ConcurrentHashMap`. AgentService.init populates the map (lines 295-296) before any dispatch, so today this is single-threaded — but `register` and `unregister` are public API and `dispatch` is suspend, so anything that calls `register` after the manager has been used will race.

**Evidence**:
```kotlin
// hooks/HookManager.kt:28-42
private val hooks = mutableMapOf<HookType, MutableList<HookConfig>>()
…
fun register(config: HookConfig) {
    hooks.getOrPut(config.type) { mutableListOf() }.add(config)
    …
}
```

**Impact**: `ConcurrentModificationException` if hot-reload or runtime hook registration is added. Today it's effectively init-only, but the API contract doesn't say so.

**Fix sketch**: Use `ConcurrentHashMap` + `CopyOnWriteArrayList` for the inner list, or document init-only and add `check(!started)` guard.

---

## Summary Table

| Severity | Security | Leak | Threading | Correctness | Perf | Quality | TOTAL |
|----------|----------|------|-----------|-------------|------|---------|-------|
| **P0**   | 6        | 2    | 3         | 0           | 0    | 0       | **11** |
| **P1**   | 0        | 0    | 1         | 7           | 3    | 1       | **12** |
| **P2**   | 0        | 0    | 0         | 0           | 0    | 4       | **4**  |
| **TOTAL**| 6        | 2    | 4         | 7           | 3    | 5       | **27** |

(F-13 was a false positive surfaced during the audit and is left in for transparency but excluded from the count.)

## Top 5 Must-Fix-First

1. **F-1 (P0 Security)** — `agent/hooks/HookConfig.kt:83-111` + `AgentService.kt:294-296`: Repository-rooted `.agent-hooks.json` is auto-loaded and runs arbitrary shell. **Untrusted-repo RCE.**
2. **F-3 (P0 Security)** — `agent/checkpoint/SessionCheckpointStore.kt:218-223, 152-160`: Path traversal in `snapshotRelative` + unchecked absolute paths on revert. **Arbitrary file write via planted `meta.json`.**
3. **F-4 (P0 Security)** — `agent/session/AtomicFileWriter.kt:22-58`: No `NOFOLLOW_LINKS`, no permission set. **Symlink TOCTOU under `~/.workflow-orchestrator/`.**
4. **F-5 (P0 Security)** — `AtomicFileWriter` + `AttachmentStore` + `MemoryIndex` + `AgentFileLogger`: All persisted files are world-readable by default. **Secrets / PII exposure on multi-user hosts.**
5. **F-11 (P0 Threading)** — `AgentService.kt:2103-2229`: Concurrent `executeTask` races corrupt `activeTask` / `activeMessageStateHandler`. **Lost cancellation, cross-task state.**

## P0 — Immediate Attention

- **F-1** (hook RCE), **F-2** (hook env injection), **F-3** (checkpoint traversal), **F-4** (symlink TOCTOU), **F-5** (world-readable files), **F-6** (token leak in hook stderr) — security-critical, fix before next enterprise rollout.
- **F-7** (AgentFileLogger never closed), **F-10** (MemoryIndex lock map leak) — process-stability.
- **F-8** (model-change CAS race), **F-9** (drift flag TOCTOU), **F-11** (executeTask race), **F-29** (HookManager unsynchronized map) — threading.

## Files Audited

- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/SharedCatalogHolder.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoopAttachmentScope.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoopTestSupport.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ApprovalPolicy.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/BrainRouter.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/LoopDetector.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/LoopResult.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ModelFallbackManager.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/PlanData.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/SessionApprovalStore.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/Task.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/session/ApiMessage.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/session/AtomicFileWriter.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/session/AttachmentStore.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/session/DialectDriftDetector.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/session/HistoryItem.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/session/MessageStateHandler.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/session/ResumeHelper.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/session/Session.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/session/SessionLock.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/session/TaskStore.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/session/ToolUseXmlRenderer.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/session/UiMessage.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/session/UnsupportedContentBlock.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/memory/MemoryIndex.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/observability/AgentFileLogger.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/observability/HaikuPhraseGenerator.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/observability/SessionMetrics.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/hooks/HookConfig.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/hooks/HookEvent.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/hooks/HookManager.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/hooks/HookRunner.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/checkpoint/CheckpointModels.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/checkpoint/DiffCalculator.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/checkpoint/SessionCheckpointStore.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/security/CommandFilter.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/security/CommandSafetyAnalyzer.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/security/CredentialRedactor.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/security/DefaultCommandFilter.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/util/AgentStringUtils.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/util/DiffUtil.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/util/JsEscape.kt`
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/util/ReflectionUtils.kt`
