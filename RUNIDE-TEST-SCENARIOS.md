# runIde Manual Test-Scenario Catalog (Prioritized, with Log Oracles)

> A skim-while-you-runIde catalog. For every scenario: the steps, the **expected success log signature**, the **failure log signature(s)**, and **exactly which log to capture & share**.
> When a run fails, grep the named log for the scenario's marker tag and paste those lines — they map to a known failure here (or surface a new one).
>
> All log strings below were grepped verbatim from source. Where source has **no logger**, this doc says so — report the UI symptom instead.
> Plugin-split A/B smoke STEPS live in **`PHASE-0A-SMOKE-TESTS.md`** (not repeated here); this doc carries only their log oracles (P0-2 / P0-3).

---

## Preamble — How to capture & share logs (Windows-first)

### The two log sinks

| Sink | What | Where (Windows runIde sandbox) | How to open |
|---|---|---|---|
| **A — `idea.log`** | Everything from `Logger.getInstance` (all `[Tag]` markers below). The default sink for almost every scenario. | `build\idea-sandbox\IU-2025.1.7\log\idea.log` (or the printed `.intellijPlatform\sandbox\IU-2025.1.7\log\idea.log`) | **Help → Show Log in Explorer** (Windows) / **Show Log in Files** (macOS) |
| **B — agent JSONL** | Structured per-event agent trail (`session_start`, `tool_call`, `retry`, `compaction`, `session_end`…). **Agent runs only.** Always-on, 7-day rolling. | `%USERPROFILE%\.workflow-orchestrator\{projDir}-{sha6}\agent\logs\agent-YYYY-MM-DD.jsonl` | open the dated file; `{sha6}` = first 6 hex of SHA-256(absolute project path) |

Other agent artifacts (only when debugging a specific session):
- `~/.workflow-orchestrator/{proj}-{sha6}/agent/sessions/{id}/api_conversation_history.json` — full LLM message log (dialect / trailing-assistant issues)
- `~/.workflow-orchestrator/{proj}-{sha6}/agent/sessions/{id}/ui_messages.json` — what the webview rendered
- `~/.workflow-orchestrator/{proj}-{sha6}/agent/sessions.json` — global session index (startup recovery source)

### How to share a failing log (the recipe)

Each scenario lists a **📋 On failure, share** line naming the file + the grep marker(s). The generic recipe:

```bat
:: Windows — from the sandbox log dir
findstr /C:"[Jira:API]" idea.log
:: multiple markers in one pass:
findstr /C:"[PluginSplit]" /C:"[Core:Credentials]" idea.log
```

```bash
# macOS/Linux equivalent
grep -F "[Jira:API]" idea.log
# agent JSONL (sink B), e.g. all tool errors:
jq 'select(.event=="tool_call" and .status=="error")' agent-*.jsonl
```

Paste the matched lines (plus any stacktrace in the same block). The marker tag is what maps the paste to a known failure here.

### Which scenarios need tokens

| Needs | Scenarios |
|---|---|
| **NONE** (run these first — no network/token) | P0-1 plugins load · P0-2/P0-3 plugin-split A/B · P0-4 settings token persist · P0-5 tool window opens |
| **Sourcegraph URL + token (+ a model)** | all **P1 agent** (send msg, ReAct, plan mode, compaction, errors, resume) and **P2 sub-agents / monitors** |
| **Jira URL + token** | P1 Jira (Sprint tab) |
| **Bamboo URL + token** | P1 Bamboo (Build tab) + P2 Automation |
| **Sonar URL + token** | P1 Sonar (Quality tab) |
| **Bitbucket URL + token + ≥1 repo** | P1 PR (PR tab) + parts of P2 Handover |

### Tier map

- **P0 — no tokens, highest value:** does the plugin even load & persist? Run these every build.
- **P1 — core agent loop + connector tabs:** the daily-driver paths (need tokens).
- **P2 — extended:** sub-agents, monitors, automation, handover (terser).

---

# P0 — No tokens, highest value

> Run all five with **zero** credentials configured. They prove load, the plugin-split wiring, settings persistence, and the tool window — the things that break a build before any feature is reachable.

### [P0-1] Core / Startup — Both plugins load (Settings → Plugins)
- **Prereqs:** none. Plugin A (and, for the two-plugin variant, plugin B) installed in the sandbox.
- **Steps:**
  1. `./gradlew runIde`, open any project.
  2. **Settings → Plugins → Installed** → confirm "Workflow Orchestrator" is present and **enabled** (no red "incompatible" / "failed to load" badge).
  3. (Two-plugin variant) confirm the plugin-B descriptor is listed and enabled too.
- **✅ Success log (sink A):** absence of any plugin-load error block, AND on project open within ~5 s:
  - `[PluginSplit] active WorkflowConfig impl: DefaultWorkflowConfig` (A solo) — `SettingsMigrationStartupActivity.kt:17`
  - `[SettingsMigration] stamped schema v<N>` (first open after a schema bump only) — `SettingsMigrationStartupActivity.kt:12`
- **❌ Failure logs (sink A):**
  - Any `com.intellij.diagnostic.PluginException` / "Plugin … failed to load" block → descriptor / dependency error.
  - **No `[PluginSplit]` line at all** → `SettingsMigrationStartupActivity` didn't register or crashed — look for an exception in the same log block.
- **📋 On failure, share:** `idea.log` → `findstr /C:"[PluginSplit]" /C:"[SettingsMigration]" /C:"PluginException" idea.log` plus the first error block at startup.

### [P0-2] Plugin-Split — Plugin-A solo: DefaultWorkflowConfig
- **Prereqs:** none. **Plugin B NOT on classpath.** (Full steps: `PHASE-0A-SMOKE-TESTS.md` S-1.)
- **✅ Success log (sink A):** `[PluginSplit] active WorkflowConfig impl: DefaultWorkflowConfig` — `SettingsMigrationStartupActivity.kt:17`
- **❌ Failure logs (sink A):**
  - `[PluginSplit] active WorkflowConfig impl: CompanyBWorkflowConfig` → plugin B was loaded when it shouldn't be.
  - no `[PluginSplit]` line → startup activity failed to register/crashed.
- **📋 On failure, share:** `idea.log` → `findstr /C:"[PluginSplit]" /C:"[SettingsMigration]" idea.log`.

### [P0-3] Plugin-Split — Plugin-A + Plugin-B: WorkflowConfig override + tool contribution
- **Prereqs:** plugin B built + in sandbox. **An agent session must be started** to trigger tool registration (the second oracle fires only then). (Full steps: `PHASE-0A-SMOKE-TESTS.md` S-2.)
- **✅ Success logs (sink A):**
  - `[PluginSplit] active WorkflowConfig impl: CompanyBWorkflowConfig` — `SettingsMigrationStartupActivity.kt:17`
  - `[agentToolContributor] 1 contributor(s) [CompanyBToolContributor] contributed tools: [companyb_noop]` — `AgentService.kt:1335-1337`
- **❌ Failure logs (sink A):**
  - `[Tools] agentToolContributor EP iteration failed` (`AgentService.kt:1340`) → EP class not found / plugin-B not loaded.
  - `[PluginSplit] active WorkflowConfig impl: DefaultWorkflowConfig` → plugin-B's EP impl not resolving (check `order` / `depends`).
- **📋 On failure, share:** `idea.log` → `findstr /C:"[agentToolContributor]" /C:"[PluginSplit]" idea.log`.

### [P0-4] Settings / Auth — Token persist (write-back verification)
- **Prereqs:** none beyond PasswordSafe unlocked (KeePass: password entered; Windows credential store / macOS Keychain: access granted). No service URL needed to test the store itself.
- **Steps:**
  1. **Settings → Tools → Workflow Orchestrator → Connections.**
  2. Enter any token for any service (Jira/Bamboo/Bitbucket/Sonar/Sourcegraph), click **Apply**.
- **✅ Success log (sink A):** `[Core:Credentials] Stored and verified credential for <SERVICE_NAME>` — `CredentialStore.kt:117`
- **❌ Failure logs (sink A):**
  - `[Core:Credentials] Write verification FAILED for <SERVICE_NAME> — password storage did not persist the token` (`CredentialStore.kt:111-114`) → PasswordSafe silently dropped the write (KeePass DB locked, permission denied). The token will NOT be used. (This is the latent "token didn't persist" trap.)
  - `[Core:Credentials] Read-back after store threw for <SERVICE_NAME>` (`CredentialStore.kt:104`) → PasswordSafe threw on the immediate re-read.
- **📋 On failure, share:** `idea.log` → `findstr /C:"[Core:Credentials]" idea.log`.

### [P0-5] Core / UX — Workflow tool window opens
- **Prereqs:** none. (Agent tab renders its JCEF webview without a token; connector tabs show empty-states without config — that's expected here.)
- **Steps:**
  1. Open the **Workflow** bottom-docked tool window.
  2. Confirm the 6 tabs render (Sprint, PR, Build, Quality, Automation, Handover).
  3. Click the **Agent** tab → JCEF webview loads the history list (`viewMode: 'history'`).
- **✅ Success logs (sink A):**
  - `AgentStartupActivity: no interrupted sessions found` — `AgentStartupActivity.kt:73`
  - `IDE context detected: ${ideContext.product} (${ideContext.edition}), ...` — `AgentService.kt:1015`
  - `[AgentService] Available shells: $allowedShells` — `AgentService.kt:1019`
  - `AgentService: registered N core + M deferred tools ...` — `AgentService.kt:1345`
  - `[Context] System prompt set (N chars, ~M tokens)` — `ContextManager.kt:161`
- **❌ Failure logs (sink A):**
  - `AgentStartupActivity: failed to check for interrupted sessions` — `AgentStartupActivity.kt:76`
  - `AgentService: N tools failed to register: [...]` — `AgentService.kt:1343`
  - `AgentService: failed to register core tool: ...` — `AgentService.kt:1543`
  - `[Tools] agentToolContributor EP iteration failed` — `AgentService.kt:1340`
  - (Informational, not failures: `Skipping PSI tools — neither Java nor Python plugin available` `:1086`; `Skipping debug tools …` `:1316`; `Skipping coverage tools — requires Ultimate or Professional edition` `:1244`.)
  - **JCEF blank/white webview with no error** → no reliable log oracle; report the UI symptom + JCEF/CEF lines from idea.log.
- **📋 On failure, share:** `idea.log` → `findstr /C:"AgentStartupActivity" /C:"AgentService" /C:"[Tools]" idea.log`.

---

# P1 — Core agent loop + connector tabs

> Need tokens (see Preamble). Agent scenarios write to **both** sinks A and B; connector tabs are sink A only.

## P1-A — Agent core

### [P1-A1] Agent — Send message (task bootstrap)
- **Prereqs:** Sourcegraph URL + token; ≥1 model available.
- **Steps:** type a prompt in the Agent chat, press Enter / Send. Watch spinner → first assistant response.
- **✅ Success logs:**
  - `[Agent] Resolved model: ${resolution.modelId}` (A) — `BrainFactory.kt:68`
  - `[Agent] Creating brain with model: $modelId at $sgUrl (tools=N, params=M)` (A) — `BrainFactory.kt:91-93`
  - `[Agent] Task started: sessionId=$sid, model=${brain.modelId}` (A) — `AgentService.kt:1892`
  - `[Loop] Starting task (maxIterations=N, planMode=false)` (A) — `AgentLoop.kt:852`
  - JSONL `"event":"session_start"` (B) — `AgentFileLogger.kt:152-163`
- **❌ Failure logs:**
  - `[Agent] Failed to fetch models from Sourcegraph: ...` (A) — `BrainFactory.kt:59`
  - `[Agent] No models available and no model configured. Trying factory auto-resolution.` (A) — `BrainFactory.kt:73`
  - exception text `Cannot start agent: failed to fetch models from Sourcegraph ($sgUrl)` (A) — `BrainFactory.kt:78-83`
  - `AgentService: TASK_START hook cancelled task: ${hookResult.reason}` (A) — `AgentService.kt:1825`
  - `AgentController: USER_PROMPT_SUBMIT hook cancelled: ${hookResult.reason}` (A) — `AgentController.kt:2378`
- **📋 On failure, share:** `idea.log` → `findstr /C:"[Agent]" /C:"BrainFactory" idea.log`; AND sink B → `jq 'select(.event=="session_start")' agent-*.jsonl`.

### [P1-A2] Agent — ReAct loop / tool execution
- **Prereqs:** active session from P1-A1.
- **Steps:** watch iterations: each calls ≥1 tool, gets a result, calls the LLM again until `attempt_completion`. Confirm a tool card expands; for `run_command` watch streaming stdout; for `edit_file` confirm the file on disk changed.
- **✅ Success logs:**
  - `[Loop] Iteration $iteration -- N messages, X.X% context` (A) — `AgentLoop.kt:935`
  - `[Loop] Executing tool: $toolName (${call.function.arguments.take(200)})` (A) — `AgentLoop.kt:2066`
  - `run_command[$toolCallId]: process started (streamCallback=true)` (A) — `RunCommandTool.kt:747`
  - `run_command[$id]: first flush to UI (N chars)` (A) — `AgentController.kt:538`
  - `[Loop] Tool $toolName completed in ${durationMs}ms (OK)` (A) — `AgentLoop.kt:2223`
  - `[Loop] Task completed in $iteration iterations ($totalInputTokens input, $totalOutputTokens output tokens)` (A) — `AgentLoop.kt:2413`
  - `[Agent] Task ended: status=COMPLETED, ...` (A) — `AgentService.kt:2644`
  - JSONL `"event":"iteration"`, `"api_call"` (latencyMs/promptTokens/completionTokens), `"tool_call"` (status:"ok", durationMs), `"session_end"` (B) — `AgentFileLogger.kt:52-185`
- **❌ Failure logs:**
  - `[Loop] Tool $toolName failed: ${errorMsg.take(200)}` (A) — `AgentLoop.kt:2191`
  - `[Loop] Tool $toolName failed: ${toolResult.content.take(200)}` (A) — `AgentLoop.kt:2221`
  - `[Agent:RunCommand] BLOCKED command: ${command.take(100)}` (A) — `RunCommandTool.kt:640`
  - `[Agent:RunCommand] Rejected env vars: ...` (A) — `RunCommandTool.kt:652`
  - `[Loop] Task failed after $iteration iterations: ${apiResult.message.take(200)}` (A) — `AgentLoop.kt:1398`
  - `[Loop] Max consecutive mistakes (N) — waiting for user feedback` (A) — `AgentLoop.kt:1751`
  - `[Loop] Task failed after $iteration iterations: exceeded maximum iterations` (A) — `AgentLoop.kt:1821`
  - `[Loop] Hard loop limit reached: '$toolName' called N times consecutively` (A) — `AgentLoop.kt:1890`
  - `[Loop] checkpoint capture failed for $path (non-fatal): ...` (A) — `AgentLoop.kt:2087`
  - `[HookRunner] PRE_TOOL_USE hook timed out after Nms, proceeding` (A) — `HookRunner.kt:85`
  - JSONL `"event":"tool_call"`,`"status":"error"`,`"error":"…"` and `"event":"loop_hard"`/`"loop_soft"` (B) — `AgentFileLogger.kt:52-201`
- **📋 On failure, share:** `idea.log` → `findstr /C:"[Loop]" /C:"[Agent:RunCommand]" idea.log`; AND sink B → `jq 'select(.event=="tool_call" and .status=="error")' agent-*.jsonl` (per-tool timing: `jq 'select(.event=="tool_call")|{tool,status,durationMs}' agent-*.jsonl`).

### [P1-A3] Agent — Plan mode block → switch to Act
- **Prereqs:** active session; Plan-mode toggle visible in chat toolbar.
- **Steps:** enable Plan Mode (or let LLM call `enable_plan_mode`). Confirm the LLM presents a plan via `plan_mode_respond` and that a write tool is **refused** while in plan mode. Click **Approve** → write tools then execute.
- **✅ Success logs (sink A):**
  - `[Loop] Starting task (maxIterations=N, planMode=true)` — `AgentLoop.kt:852`
  - `[Loop] Plan mode enabled by LLM via enable_plan_mode tool` — `AgentLoop.kt:2511`
  - `[Loop] Plan presented (needsMoreExploration=false)` — `AgentLoop.kt:2494`
  - `[Agent] Model change requested by user: $modelId — will apply on next iteration` (after Approve) — `AgentService.kt:733`
- **❌ Failure logs:**
  - tool result text `Error: '$toolName' is blocked in plan mode. You can only read, search, and analyze code.` (A) — `AgentLoop.kt:1946` (this is the **expected** block; absence of it when a write was attempted = the guard leaked → report).
  - JSONL `"event":"tool_call"`,`"status":"error"`,`"error":"blocked in plan mode"` (B) — `AgentFileLogger.kt:52-74`
  - `[Loop] Plan presented (needsMoreExploration=true)` (A) — loop keeps exploring (noteworthy, not fatal) — `AgentLoop.kt:2494`
- **📋 On failure, share:** `idea.log` → `findstr /C:"[Loop]" /C:"[Agent]" idea.log`; sink B → `jq 'select(.error|test("blocked in plan mode"))' agent-*.jsonl`.

### [P1-A4] Agent — Context window / compaction (~88%)
- **Prereqs:** active session long enough to reach ~88% of the window (default 132K). Many large file reads will do it.
- **Steps:** run until the context bar nears 88%+; observe automatic compaction (bar resets).
- **✅ Success logs:**
  - `[Loop] Iteration $iteration -- N messages, 87.X% context` (A) — `AgentLoop.kt:935`
  - `[Loop] Context compaction triggered at X.X%` (A) — `AgentLoop.kt:957`
  - `[Context] Compacting at X.X% utilization (N messages, iters=M)` (A) — `ContextManager.kt:575`
  - `[Context] Compacted: X.X% → Y.Y% (T1 → T2 tokens, L1=N chars, L3=M chars, case=A)` (A) — `ContextManager.kt:686`
  - JSONL `"event":"compaction"`,`"tokensBefore":N`,`"tokensAfter":M` (B) — `AgentFileLogger.kt:114-130`
- **❌ Failure logs:**
  - `[Context] auto-compaction failed: ${compactResult.reason}; falling back to slidingWindow(0.3)` (A) — `AgentLoop.kt:962`
  - `[Context] Summarization LLM call failed: ...` (A) — `ContextManager.kt:806`
  - `[Loop] Context overflow detected, compacting and retrying (N/M)` (A) — `AgentLoop.kt:1200`
  - `[Loop] Compaction failed N consecutive times at overflow site — aborting loop` (A) — `AgentLoop.kt:1211`
  - `[AgentService] compactContext failed: ...; applying slidingWindow(0.3) as safety net` (A) — `AgentService.kt:989`
- **📋 On failure, share:** `idea.log` → `findstr /C:"[Context]" /C:"[Loop]" idea.log`; sink B → `jq 'select(.event=="compaction")' agent-*.jsonl`. (The slidingWindow fallback only appears in sink A.)

### [P1-A5] Agent — Errors: offline, retry/backoff, upstream_timeout, empty response
- **Prereqs:** active session. Disconnect network / VPN for offline; a loaded Sourcegraph for 429/5xx/timeout.
- **Steps:** (a) drop network mid-task → Offline banner + Retry; (b) provoke 429/5xx → auto-retry with delay; (c) `finish_reason:"upstream_timeout"` → recovery up to 5×; (d) 200-OK-no-choices → temperature escalation to 1.0; (e) `finish_reason:"length"` → recovery to max retries.
- **✅ Success logs (recovery, sink A unless noted):**
  - `[Loop] API retry N/M ($reason, delay=Xms)` — `AgentLoop.kt:1367`
  - `[Loop] Timeout retries exhausted, compacting context and retrying (N/M)` — `AgentLoop.kt:1378`
  - `[Loop] L2 tier escalation: $oldModel → $newTierModel` — `AgentLoop.kt:1341`
  - `[Loop] Recycling brain (N/M) on $type — model unchanged: $model` — `AgentLoop.kt:1270`
  - JSONL `"event":"retry"`,`"error":"reason"`,`"failureType":"..."` (B) — `AgentFileLogger.kt:132-148`
- **❌ Failure logs (sink A unless noted):**
  - `[Loop] Confirmed offline ($netState) on $type — failing fast for manual retry` — `AgentLoop.kt:1238`
  - `[Loop] Empty response from LLM — provider error (attempt N/M, temperature escalated to 1.0)` — `AgentLoop.kt:1791`
  - `[Loop] API success but response contained no choices at iteration N — backing off and retrying` — `AgentLoop.kt:1476`
  - `[Loop] Upstream gateway-timeout recovery exhausted (N/M) — aborting` — `AgentLoop.kt:1575`
  - `[Loop] Output-length truncation recovery exhausted (N/M) — aborting` — `AgentLoop.kt:1512`
  - `[Loop] Task failed after N iterations: ${apiResult.message.take(200)}` — `AgentLoop.kt:1398`
  - `[Context] timeout-recovery compaction failed: ...; falling back to slidingWindow(0.3)` — `AgentLoop.kt:1384`
  - JSONL `"event":"session_end"`,`"failureType":"OFFLINE"` (or `"API_ERROR"`) (B) — `AgentFileLogger.kt:165-185`
- **📋 On failure, share:** `idea.log` → `findstr /C:"[Loop]" /C:"[Context]" idea.log`; sink B → `jq 'select(.event=="retry" or (.event=="session_end" and .failureType))' agent-*.jsonl`.

### [P1-A6] Agent — Session resume (incl. locked-session)
- **Prereqs:** a previously-running, not-cleanly-completed session; `sessions.json` intact.
- **Steps:** start a task, let it run a few iterations, close/reopen the IDE (or switch sessions in the history view and back). On restart, observe the "Interrupted session" banner → click **Resume**.
- **✅ Success logs (sink A):**
  - `AgentStartupActivity: found interrupted session '${interrupted.id}' — '$title'` — `AgentStartupActivity.kt:63`
  - `AgentStartupActivity: cleaned up N orphan session director(ies)` — `AgentStartupActivity.kt:47`
  - `[Agent] Resuming session: $sessionId (N api messages, interrupted Xs ago)` — `AgentService.kt:3152`
  - `[AgentService] resume cleanup: dropped N trailing empty-assistant turn(s) from history` — `AgentService.kt:2879`
  - `[AgentService] resume pickup: delivered N queued item(s) from unified queue for $sessionId` — `AgentService.kt:2926`
  - `[Context] Restored N messages, totalUserMessageCount=M` — `ContextManager.kt:1128`
  - `AgentService.resumeSession: session $sessionId was already completed, displaying without re-execution` — `AgentService.kt:2859`
- **❌ Failure logs (sink A):**
  - `AgentService.resumeSession: session dir not found for $sessionId` — `AgentService.kt:2813`
  - `AgentService.resumeSession: session $sessionId is locked by another instance` — `AgentService.kt:2820` (the **locked-session** path — second IDE instance / stale lock)
  - `AgentService.resumeSession: no api history for $sessionId` — `AgentService.kt:2829`
  - `AgentService: TASK_RESUME hook cancelled resume: ${hookResult.reason}` — `AgentService.kt:3088`
  - `AgentService.resumeSession: loadPersistedHandles failed for $sessionId` — `AgentService.kt:3035`
- **📋 On failure, share:** `idea.log` → `findstr /C:"resumeSession" /C:"AgentStartupActivity" /C:"[Agent]" idea.log`; plus `~/.workflow-orchestrator/{proj}-{sha6}/agent/sessions/{id}/api_conversation_history.json` if a dialect/trailing-turn issue is suspected.

## P1-B — Connector tabs (sink A only)

> Tab-provider gates are **silent** (no log) — you see the empty-state UI, not a marker. Connector load failures DO log. Quote the exact tag below.

### [P1-B1] Jira (Sprint tab) — load + auth failures
- **Prereqs:** Jira URL + token; board auto-detected or `jiraBoardId` set.
- **Steps:** open Workflow → **Sprint** tab. (For 401/403: use a wrong/expired token.)
- **✅ Success logs:**
  - `[Jira:UI] Sprint dashboard loaded ${n} tickets` — `SprintDashboardPanel.kt:592`
  - `[Jira:API] GET /rest/agile/1.0/board?… -> 200` (debug) — `JiraApiClient.kt:635`
  - UI: sprint name in header, `(N tickets)` badge, status bar `"N tickets loaded"`.
- **❌ Failure logs:**
  - `[Jira:API] Authentication failed (401)` — `JiraApiClient.kt:173,343,393,446` (UI status: `"Error: Invalid Jira token"`)
  - `[Jira:API] Got text/html on <path> — likely auth-expired login redirect.` — `JiraApiClient.kt:862` (session cookie expired → token wrong)
  - `[Jira:API] Forbidden (403)` — `JiraApiClient.kt:176,345,395,449` (`"Error: Insufficient Jira permissions"`)
  - `[Jira:API] Not found (404)` — `JiraApiClient.kt:180,349,399,452`
  - `[Jira:API] Rate limited (429)` — `JiraApiClient.kt:182,351,401,452`
  - `[Jira:API] Network error: <message>` — `JiraApiClient.kt:660,720,810` (DNS/firewall/VPN)
  - `[Jira:API] Transition rejected (400) for ABC-123: <msg>` — `JiraApiClient.kt:442`
  - `[Jira:UI] Favourite filters load failed: <message>` — `SprintDashboardPanel.kt:671`
  - **No-oracle gates (report UI symptom):** Jira URL not configured → silent gate `SprintTabProvider.kt:28`, UI `"Jira not configured.\nCheck URL and token in Settings."`; "Start Work" Bitbucket-not-configured checks are silent (status bar only, `SprintDashboardPanel.kt:951,991,999`); `[Jira:API] Dev-status API failed (...)` returns empty success → DevStatus section silently empty (`JiraApiClient.kt:498`).
- **📋 On failure, share:** `idea.log` → `findstr /C:"[Jira:API]" /C:"[Jira:UI]" idea.log`.

### [P1-B2] Bamboo (Build tab) — load + DELETE-HTML auth redirect
- **Prereqs:** Bamboo URL + token; plan key (or per-repo, or auto-detected from PR branch).
- **Steps:** open Workflow → **Build** tab (focus a PR to exercise plan auto-detect). For the auth-redirect case, attempt a write op (stop/cancel) with an expired session.
- **✅ Success logs:**
  - `[Build:Dashboard] Resolved branch plan 'PROJ-42' via BambooService.autoDetectPlan (...)` — `BuildDashboardPanel.kt:263`
  - `[Bamboo:API] getLatestResult: GET /rest/api/latest/result/PROJ-42/latest?...` — `BambooApiClient.kt:104`
  - `[Bamboo:API] getRecentResults: N result(s) for PROJ-42 (...)` — `BambooApiClient.kt:327`
- **❌ Failure logs:**
  - `[Bamboo:API] DELETE got HTML body — auth redirect` — `BambooApiClient.kt:677` (→ `ErrorType.AUTH_REDIRECT`, "session may have expired" — the Bamboo write-path auth signature)
  - `[Bamboo:API] Authentication failed (401)` — `BambooApiClient.kt:419,441`
  - `[Bamboo:API] Forbidden (403)` — `BambooApiClient.kt:420,442`
  - `[Bamboo:API] Not found (404)` — `BambooApiClient.kt:421,443` (likely wrong plan key)
  - `[Bamboo:API] Network error: <message>` — `BambooApiClient.kt:427,448`
  - `[Bamboo:API] Parse failed: <message>` + `[Bamboo:API] Response body (first 200): <body>` — `BambooApiClient.kt:414,415`
  - `[Build:Dashboard] No plan resolved for branch 'feature/x' and no configured planKey` — `BuildDashboardPanel.kt:283`
  - `[Build:Dashboard] Failed to check divergence: <message>` — `BuildDashboardPanel.kt:332`
  - **No-oracle:** Bamboo URL not configured → silent gate `BuildTabProvider.kt:16`, UI `"No builds found.\nConnect to Bamboo in Settings to get started."`; **401/403 on poll has no direct UI surface** — stage list just clears (so rely on the `[Bamboo:API]` log, not the UI).
- **📋 On failure, share:** `idea.log` → `findstr /C:"[Bamboo:API]" /C:"[Build:Dashboard]" idea.log`.

### [P1-B3] Sonar (Quality tab) — load + CE 403
- **Prereqs:** Sonar URL + token; project key (or auto-detected from Maven/Gradle).
- **Steps:** open Workflow → **Quality** tab with a PR focused. For CE-403 use a non-admin token.
- **✅ Success logs:**
  - `[Sonar:LineCoverage] Cached N line statuses for '<relativePath>' (projectKey='<key>')` — `SonarDataService.kt:268`
  - `[Sonar:Branches] N branches analyzed:` — `SonarDataService.kt:445`
  - `[Sonar:Detect] Found explicit sonar.projectKey: <key>` — `SonarKeyDetector.kt:129`
- **❌ Failure logs:**
  - `[Sonar:API] <path> -> 401 Auth failed` — `SonarApiClient.kt:542`
  - `[Sonar:API] <path> -> 403 (insufficient permissions — some endpoints require admin)` — `SonarApiClient.kt:546`
  - `[Sonar:CE] /api/ce/activity 403 — token lacks Administer Project permission` — `SonarDataService.kt:484` (**non-fatal / normal** for non-admin tokens; surfaces the admin-permission hint label)
  - `[Sonar:API] <path> -> 404 Not found` — `SonarApiClient.kt:550`
  - `[Sonar:API] <path> -> 429 Rate limited` — `SonarApiClient.kt:554`
  - `[Sonar:API] <path> -> <code> Server error` — `SonarApiClient.kt:558`
  - `[Sonar:API] <path> -> Network error: <message>` — `SonarApiClient.kt:567`
  - `[SonarService] componentExists preflight failed (...); proceeding to issues search` — `SonarServiceImpl.kt:121` (project key wrong / not found)
  - `[Sonar:Detect] No tier matched project basePath: <path>` — `SonarKeyDetector.kt:63` (auto-detect failed; set key manually)
  - **⚠ NO-ORACLE (important):** `QualityDashboardPanel` has **no logger field at all** — project-key-not-configured, no-PR-focused, no-analysis-for-branch, branch-warning banner are **UI-only** (`statusLabel`/`qualityHintLabel`, e.g. `:412,:292,:483,:530`). For these, report the exact status-bar / hint text — there is nothing in idea.log.
- **📋 On failure, share:** `idea.log` → `findstr /C:"[Sonar:API]" /C:"[Sonar:CE]" /C:"[SonarService]" /C:"[Sonar:Detect]" idea.log`. If Quality tab is silent (no `[Sonar:*]` line), quote the UI hint/status text instead.

### [P1-B4] PR (PR tab) — load + not-configured
- **Prereqs:** Bitbucket URL + token; ≥1 repo with `bitbucketProjectKey` + `bitbucketRepoSlug`.
- **Steps:** open Workflow → **PR** tab.
- **✅ Success logs:**
  - `[PR:List] Found N authored PRs (dashboard, scoped to M configured repos, state=OPEN)` — `PrListService.kt:183`
  - `[PR:List] Found N total repo PRs across M repos (state=OPEN)` — `PrListService.kt:189`
  - `[PR:List] Auto-detected Bitbucket username: <username>` — `PrListService.kt:122`
  - UI status bar `"N PRs loaded [updated Xs ago]"` — `PrDashboardPanel.kt:464`
- **❌ Failure logs:**
  - `[PR:List] Bitbucket URL not configured, skipping refresh` — `PrListService.kt:101` (the runtime not-configured signal; this one DOES log)
  - `[PR:List] Bitbucket project/repo not configured, skipping refresh` — `PrListService.kt:139` (per-repo settings missing)
  - `[BitbucketService] Failed to fetch my PRs: Invalid Bitbucket token` — `BitbucketServiceImpl.kt:498`
  - `[BitbucketService] Failed to fetch my PRs: Cannot reach Bitbucket: <message>` — `BitbucketServiceImpl.kt:498` (network/offline)
  - `[PR:List] Could not detect username — PR list may not filter correctly` — `PrListService.kt:125`
  - `[PR:Dashboard] CreatePrLauncher EP not registered — cannot open dialog` — `PrDashboardPanel.kt:514`
  - `[BitbucketService] Failed to merge PR #N: <message>` — `BitbucketServiceImpl.kt:767`
  - **No-oracle:** the **tab-provider gate** (Bitbucket URL blank at construction time) is silent → empty-state UI `"No pull request services configured.\nConnect Bitbucket in Settings to get started."` (`PrTabProvider.kt:16,22-23`). Note the auth-fail path can ALSO end at empty-state `"No pull requests open."` (`PrListPanel.kt:137`) — distinguish via the `[PR:List]` / `[BitbucketService]` log.
- **📋 On failure, share:** `idea.log` → `findstr /C:"[PR:List]" /C:"[BitbucketService]" /C:"[PR:Dashboard]" idea.log`.

---

# P2 — Extended (terser)

### [P2-1] Agent — Sub-agents (spawn_agent)
- **Prereqs:** active agent session (Sourcegraph). Up to 5 parallel.
- **Steps:** prompt that triggers parallel dispatch (e.g. "run 3 independent tasks"); optionally Stop one sub-agent.
- **✅ Success:** `[Loop] Executing tool: spawn_agent (...)` (A, `AgentLoop.kt:2066`); per-branch `[Agent] Task started: sessionId=$subSid ...` (A, `AgentService.kt:1892`) + `[Loop] Task completed ...` (A); JSONL `session_start`/`session_end` per sub-agent (B). All sub-agents write the SAME JSONL — demux by `session` field: `jq 'select(.session=="<first11>")' agent-*.jsonl`.
- **❌ Failure:** `[SpawnAgent] Abort requested for subagent $agentId` (`SpawnAgentTool.kt:718`); `AgentController: subagent $agentId cancelled` (`AgentController.kt:1284`); `[SubagentRunner] Failed: $errorMsg` (`SubagentRunner.kt:597`); `[SpawnAgent] Config '$name' references unknown core/deferred tool: $name` (`SpawnAgentTool.kt:765,782`).
- **📋 Share:** `idea.log` → `findstr /C:"[SpawnAgent]" /C:"[SubagentRunner]" /C:"[Loop]" idea.log`; sink B demuxed by `session`.

### [P2-2] Agent — Monitors / idle-wake
- **Prereqs:** session with a monitor registered (persists across restart, re-armed on resume). **No JSONL** — monitors use the EventBus path, not AgentFileLogger; sink A only.
- **Steps:** register a shell-command monitor via the `monitor` tool; let the session idle; observe the idle-wake card when it fires.
- **✅ Success:** `reArmMonitors: re-arming N monitor(s) for $sessionId` (`AgentMonitorCoordinator.kt:271`); `reArmMonitors: re-armed $id ($sourceType) ...` (`:328`); `[MonitorPool] emitSnapshot session=$sessionId count=N ids=[...]` (`MonitorPool.kt:126`); `[Monitor] forwarding to webview — session=$active count=N ...` (`AgentController.kt:816`); `[AgentService] resume pickup: delivered N persisted monitor notification(s) for $sessionId` (`AgentService.kt:3005`).
- **❌ Failure:** `reArmMonitors: build failed for ${spec.id} ($sourceType): ${result.error}` (`:300`); `reArmMonitors failed for $sessionId — resume continues unaffected: ...` (`:333`); `[MonitorPool] EventBus service is null — event not emitted (session=$sessionId)` (`MonitorPool.kt:131`); `[ShellCommandSource:$monitorId] failed: ...` (`ShellCommandSource.kt:57`); `[AgentController] auto-wake resume failed for session $sessionId: ...` (`AgentController.kt:878`).
- **📋 Share:** `idea.log` → `findstr /C:"reArmMonitors" /C:"[MonitorPool]" /C:"[Monitor]" idea.log`.

### [P2-3] Automation tab — suite load + Docker tag baseline
- **Prereqs:** Bamboo URL + token; a plan/suite.
- **Steps:** Workflow → **Automation** tab → select a suite from the dropdown; then click Run/Trigger.
- **✅ Success (sink A, in order):** `[Automation:UI] Suite selected: <PLAN_KEY> (gen=<N>) — sticky baseline, no scan` (`AutomationPanel.kt:468`); `[Automation:Tags] Found <N> recent builds for '<PLAN_KEY>'` (`TagBuilderService.kt:117`); `[Automation:Tags] Selected baseline: build #<N>, <M>/<T> release tags, score=<S>` (`:217`); `[Automation:Tags] Built JSON payload with <N> services, length=<L>` (`:278`); on restart `[Automation:Cache] Loaded <N> cached suite baseline(s) from disk.` (`BaselineCacheService.kt:110`); on run `[Automation:UI] Run queued for suite <PLAN_KEY> (...)` (`AutomationPanel.kt:1022`).
- **❌ Failure:** `[Automation:Tags] getRecentBuilds failed for '<PLAN_KEY>': ...` (`TagBuilderService.kt:106`, Bamboo URL/auth); `[Automation:Tags] No baseline runs found` (`:212`); `[Automation] Bamboo URL is blank — showing configure message` (`AutomationConfigurable.kt:218`); `[Automation:Cache] Failed to persist cache` (`BaselineCacheService.kt:155`); `[Automation:UI] onTriggerDefault: stage fetch failed` (`AutomationPanel.kt:937`); `[Automation:Monitor] Poll failed for <resultKey>` (`MonitorPanel.kt:451`).
- **📋 Share:** `idea.log` → `findstr /C:"[Automation:Tags]" /C:"[Automation:UI]" /C:"[Automation:Cache]" idea.log`.

### [P2-4] Handover tab — Jira closure / state / copyright
- **Prereqs:** Jira + (for closure) Automation suite results loaded.
- **Steps:** Workflow → **Handover** tab → trigger Jira closure; transition Jira status; run the Copyright scan.
- **✅ Success:** `[Handover:Jira] Building closure comment from <N> suite results` (`JiraClosureService.kt:26`); `[Handover:Jira] Closure comment built with <N> docker tags` (`:57`); `[Handover:State] jiraCommentPosted=true (commentId=<ID>)` (`HandoverStateService.kt:324`); `[Handover:State] Marked Jira as transitioned to <statusName>` (`:344`); copyright `[Handover:Copyright] Missing copyright header: <filePath>` (`CopyrightFixService.kt:134`).
- **❌ Failure:** `[Handover:Jira] No suite results provided for closure comment` (`JiraClosureService.kt:28`, Automation not loaded); `[Handover:Resolver] Failed to parse dockerTagsJson` (`HandoverPlaceholderResolver.kt:162`); `[Handover:Copyright] Fix-all aborted: copyrightTemplate is blank` (`CopyrightFixCard.kt:145`); `[Handover:Copyright] Failed to fix <filePath>` (`:245`); AI pre-review `[Handover:AiCache] Generation failed for <key>: <message>` (`HandoverAiSummaryCache.kt:194`).
- **Note:** AI pre-review **success is silent** (summary appears in the card, no log) — only failures log under `[Handover:AiCache]`.
- **📋 Share:** `idea.log` → `findstr /C:"[Handover:Jira]" /C:"[Handover:State]" /C:"[Handover:Copyright]" /C:"[Handover:AiCache]" idea.log`.

---

## Appendix — log-file locations (recap)

| Sink | Path |
|---|---|
| **A — idea.log** | Windows runIde: `build\idea-sandbox\IU-2025.1.7\log\idea.log` (Help → Show Log in Explorer). macOS: `~/Library/Logs/JetBrains/IntelliJIdea<ver>/idea.log`. |
| **B — agent JSONL** | `~/.workflow-orchestrator/{projDir}-{sha6}/agent/logs/agent-YYYY-MM-DD.jsonl` (`{sha6}` = first 6 hex of SHA-256(absolute project path); 7-day rolling) |
| Session API history | `~/.workflow-orchestrator/{proj}-{sha6}/agent/sessions/{id}/api_conversation_history.json` |
| Session UI messages | `~/.workflow-orchestrator/{proj}-{sha6}/agent/sessions/{id}/ui_messages.json` |
| Global session index | `~/.workflow-orchestrator/{proj}-{sha6}/agent/sessions.json` |

### No-log-oracle scenarios (report the UI symptom, not a marker)
- **All connector tab-provider gates** (URL blank at construction): Jira `SprintTabProvider.kt:28`, Bamboo `BuildTabProvider.kt:16`, Sonar `QualityTabProvider.kt:16`, PR `PrTabProvider.kt:16` — silent; empty-state UI only.
- **Sonar Quality tab (whole panel):** `QualityDashboardPanel` has no logger — project-key/no-PR/no-analysis/branch-warning are status-bar / hint-label only.
- **Bamboo poll 401/403:** logs `[Bamboo:API]` but has **no direct UI surface** (stage list silently clears).
- **Jira "Start Work" Bitbucket-not-configured** checks: status-bar only, no log.
- **Jira Dev-status API failure:** logs `[Jira:API] Dev-status API failed` but returns empty success → DevStatus section silently empty.
- **Agent JCEF webview blank/white:** no reliable oracle — report symptom + any CEF lines.
- **Handover AI pre-review success:** silent (only failures log).
- **Monitors:** no JSONL (sink A only).
