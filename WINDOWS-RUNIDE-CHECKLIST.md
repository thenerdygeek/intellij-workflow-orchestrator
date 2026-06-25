# Windows `runIde` Smoke-Test Checklist — Workflow Orchestrator Plugin

> **STRICTLY READ-ONLY against all backends.** This run validates load, wiring, persistence, the agent loop, and that connector tabs *render data*. The tester must perform **NO write operation** against Jira, Bamboo, Bitbucket, Sonar, or Sourcegraph. Every write-capable control is flagged **⛔ SKIP (write op)** below — do not click those.
>
> Backends: Jira, Bamboo, Bitbucket, Sonar (REST) + Sourcegraph (LLM). Run on Windows with activated IntelliJ **Ultimate** + real tokens.

---

## 1. HOW TO CAPTURE & SHARE LOGS (read first)

The plugin writes a small, plugin-only diagnostic log. **Share that one file for any failing scenario** — not the giant platform `idea.log`.

### PRIMARY log (share this)
- **`%USERPROFILE%\.workflow-orchestrator\diagnostics\plugin-0.log`**
  - Plugin-only, rotating, 5 MB × 3 generations. `plugin-0.log` is current; `plugin-1.log` / `plugin-2.log` are older rolls.
  - Captures **every** `[Tag]` marker referenced in this checklist (all loggers under the `com.workflow.orchestrator` namespace propagate into it). Compact one-line format: `yyyy-MM-dd HH:mm:ss.SSS  LEVEL  [<LoggerName>]  <message>`.
  - **For any failing scenario, attach the whole `plugin-0.log`** (it is small) plus paste the grep lines for the scenario's marker.
  - First line on a fresh file is the header: `Plugin diagnostic log enabled: <...>\plugin-0.log` — its presence confirms the diagnostic log is active.

### AGENT log (additionally share for agent failures)
- **`%USERPROFILE%\.workflow-orchestrator\{proj}-{sha6}\logs\agent-YYYY-MM-DD.jsonl`**
  - Structured per-event agent trail (`session_start`, `iteration`, `api_call`, `tool_call`, `retry`, `compaction`, `session_end`). Agent runs only; always-on, 7-day rolling.
  - `{sha6}` = first 6 hex of SHA-256 of the absolute project path; dated file.
  - For any **agent** (P1-A / P2-1 / P2-2) failure, attach the dated `.jsonl` too.

### Per-marker grep (Windows `findstr`)
```bat
:: from the diagnostics dir (%USERPROFILE%\.workflow-orchestrator\diagnostics)
findstr /C:"[PluginSplit]" /C:"[Core:Credentials]" plugin-0.log
```
For agent JSONL (PowerShell, since Windows has no `jq` by default):
```powershell
Get-Content "$env:USERPROFILE\.workflow-orchestrator\<proj>-<sha6>\logs\agent-2026-06-25.jsonl" |
  Select-String '"event":"tool_call".*"status":"error"'
```

### FALLBACK — only if `plugin-0.log` is somehow absent
The diagnostic log is on by default (Settings → Tools → Workflow Orchestrator → Telemetry → "Plugin diagnostic log"). If it never appeared, grab plugin lines from the platform `idea.log` instead. Open the sandbox log folder via **Help → Show Log in Explorer**, then:
```bat
findstr /C:"#com.workflow.orchestrator" idea.log > workflow-share.log
```
Sandbox `idea.log` path: `build\idea-sandbox\IU-2025.1.7\log\idea.log` (or the printed `.intellijPlatform\sandbox\IU-2025.1.7\log\idea.log`).

---

## 0. PREREQUISITES

- [ ] **IntelliJ Ultimate license.** `runIde` launches **Ultimate** (license-gated). Use an activated Ultimate account, **or** at the sandbox license dialog start the free **30-day Ultimate trial**. (Coverage/PSI agent tools depend on Ultimate/Professional edition; on Community they're skipped — informational, not a failure.)
- [ ] **Pull the `feature/plugin-split` branch on the Windows machine.** This branch carries both required pieces: the **Gradle-9.4 `buildPlugin` fix** AND the **plugin-only diagnostic-log feature**. Confirm a `plugin-b\` directory exists at the repo root after checkout (Plugin B does not exist on `main`; if it's missing you're on the wrong branch).
- [ ] **Run everything via the Gradle dev sandbox — there is NO ZIP to install.** The two launch commands spin up a throwaway sandbox IDE with the plugin(s) already loaded from this branch's sources:
  - `gradlew.bat runIde` — **Plugin A only**. Use for all P0-1/2/4/5, all P1, all P2.
  - `gradlew.bat :plugin-b:runIde` — **A + B** (B pulls in A via `localPlugin`). **Required for the split scenario P0-3** (and the A+B half of P0-2). Depends on the Gradle-9.4 `buildPlugin` fix already applied on this branch.
- [ ] **Do NOT install the marketplace ZIP.** The marketplace build lacks both fixes and cannot load Plugin B, so the split test (P0-3) would **falsely fail**. `build/distributions/*.zip` is only an output artifact of `buildPlugin` — it is **NOT** the test vehicle. Always launch through the Gradle dev sandbox commands above.
- [ ] **JDK 21** (Gradle toolchain auto-provisions via foojay if absent). First `runIde` downloads IntelliJ Ultimate 2025.1.7 (~1.5 GB) — expect several minutes on first launch; subsequent launches are fast.
- [ ] Git installed.

> **READ-ONLY POSTURE for the whole run:** loading tabs, viewing/rendering data, and agent prompts that strictly **read / search / analyze** are allowed. Anything that mutates a backend (or, for the agent, edits files / triggers a connector write) is forbidden. See every **⛔ SKIP** line.

---

## 2. P0 — No tokens, highest value

> Run all five with **zero** credentials configured. Use a **DUMMY** token only in P0-4 (stored locally; never sent to a backend). These prove load, plugin-split wiring, settings persistence, and tool-window render.

### [P0-1] Both plugins load (Settings → Plugins)
- Launch: `gradlew.bat runIde` (A solo); for the two-plugin variant use `gradlew.bat :plugin-b:runIde`. Open any project.
- Steps: Settings → Plugins → Installed → confirm **"Workflow Orchestrator"** present + **enabled**, no red "incompatible"/"failed to load" badge. (Two-plugin variant: also confirm **"Workflow Orchestrator - Company B"** listed + enabled.)
- ✅ **Success oracle (plugin-0.log):** no plugin-load error block, and on project open within ~5 s:
  - `[PluginSplit] active WorkflowConfig impl: DefaultWorkflowConfig` (A solo)
  - `[SettingsMigration] stamped schema v<N>` (first open after a schema bump only)
- ❌ **Failure oracle:**
  - `com.intellij.diagnostic.PluginException` / "Plugin … failed to load" block → descriptor/dependency error.
  - **No `[PluginSplit]` line at all** → `SettingsMigrationStartupActivity` didn't register/crashed (look for an exception in the same block).
- 📋 On failure share: `plugin-0.log` → `findstr /C:"[PluginSplit]" /C:"[SettingsMigration]" /C:"PluginException" plugin-0.log` + the first startup error block.
- ⛔ No write actions in this scenario.

### [P0-2] Plugin-split — A-solo oracle: `DefaultWorkflowConfig`
- Launch: `gradlew.bat runIde` (**Plugin B NOT on classpath**). Open any project. (Steps: PHASE-0A-SMOKE-TESTS.md S-1.)
- ✅ **Success oracle:** `[PluginSplit] active WorkflowConfig impl: DefaultWorkflowConfig`
- ❌ **Failure oracle:**
  - `[PluginSplit] active WorkflowConfig impl: CompanyBWorkflowConfig` → Plugin B loaded when it shouldn't be.
  - no `[PluginSplit]` line → startup activity failed to register/crashed.
- 📋 On failure share: `findstr /C:"[PluginSplit]" /C:"[SettingsMigration]" plugin-0.log`.
- ⛔ No write actions.

### [P0-3] Plugin-split — A + B oracles: `CompanyBWorkflowConfig` + `companyb_noop`
- Launch: `gradlew.bat :plugin-b:runIde` (A+B). **Open a project** (fires the WorkflowConfig oracle), then **open the Workflow tool window / start an agent session** (fires the tool-contributor oracle — it only fires once an `AgentService` is constructed). (Steps: PHASE-0A-SMOKE-TESTS.md S-2.) `companyb_noop` is a **no-op tool that returns `"ok"` and never executes against any service** — registering it is safe and read-only.
- ✅ **Success oracles (both):**
  - `[PluginSplit] active WorkflowConfig impl: CompanyBWorkflowConfig` (B's order=0 override won)
  - `[agentToolContributor] 1 contributor(s) [CompanyBToolContributor] contributed tools: [companyb_noop]`
- ❌ **Failure oracle:**
  - `[Tools] agentToolContributor EP iteration failed` → EP class not found / B not loaded.
  - `[PluginSplit] active WorkflowConfig impl: DefaultWorkflowConfig` → B's EP impl not resolving (check `order`/`depends`).
  - oracle line absent → tool contributor didn't run.
- 📋 On failure share: `findstr /C:"[agentToolContributor]" /C:"[PluginSplit]" plugin-0.log`.
- ⛔ No write actions. (Do **not** prompt the agent to *invoke* `companyb_noop` against anything — just confirm it's *registered* via the oracle.)

### [P0-4] Settings — token persist (local store only, DUMMY token)
- Prereqs: PasswordSafe unlocked (KeePass password entered / Windows credential store access granted). **No service URL needed.**
- Steps: Settings → Tools → Workflow Orchestrator → Connections. Enter a **DUMMY** token (any throwaway string) for any service, click **Apply**.
  - ⚠ This writes only to the **local Windows credential store / Keychain** — it does **NOT** contact any backend. Use a fake value; do not paste a real production token you'd then leave behind in the sandbox store.
- ✅ **Success oracle:** `[Core:Credentials] Stored and verified credential for <SERVICE_NAME>`
- ❌ **Failure oracle:**
  - `[Core:Credentials] Write verification FAILED for <SERVICE_NAME> — password storage did not persist the token` → PasswordSafe silently dropped the write (KeePass DB locked / permission denied).
  - `[Core:Credentials] Read-back after store threw for <SERVICE_NAME>` → PasswordSafe threw on immediate re-read.
- 📋 On failure share: `findstr /C:"[Core:Credentials]" plugin-0.log`.
- ⛔ No backend write — this is a **local** store test only.

### [P0-5] Workflow tool window + all tabs + Agent JCEF UI
- Prereqs: none. Connector tabs render **empty-states** without config — that is **expected** here, not a failure.
- Steps:
  1. Open the **Workflow** bottom-docked tool window.
  2. Confirm all **6 tabs** render: Sprint, PR, Build, Quality, Automation, Handover (each may show its "not configured" empty state).
  3. Click the **Agent** tab → JCEF webview loads the history list (`viewMode: 'history'`).
- ✅ **Success oracles:**
  - `AgentStartupActivity: no interrupted sessions found`
  - `IDE context detected: <product> (<edition>), ...`
  - `[AgentService] Available shells: <...>`
  - `AgentService: registered N core + M deferred tools ...`
  - `[Context] System prompt set (N chars, ~M tokens)`
- ❌ **Failure oracles:**
  - `AgentStartupActivity: failed to check for interrupted sessions`
  - `AgentService: N tools failed to register: [...]`
  - `AgentService: failed to register core tool: ...`
  - `[Tools] agentToolContributor EP iteration failed`
  - **JCEF blank/white webview with no error** → no reliable oracle; report the UI symptom + any JCEF/CEF lines.
  - Informational (NOT failures): `Skipping PSI tools — neither Java nor Python plugin available`; `Skipping debug tools …`; `Skipping coverage tools — requires Ultimate or Professional edition`.
- 📋 On failure share: `findstr /C:"AgentStartupActivity" /C:"AgentService" /C:"[Tools]" plugin-0.log`.
- ⛔ No write actions. **Expected empty-states per connector tab (do NOT configure backends to "fix" them here):** Sprint "Jira not configured."; Build "No builds found. Connect to Bamboo…"; Quality project-key/no-PR hint; PR "No pull request services configured…"; Automation/Handover configure prompts.

---

## 3. P1 — Core agent loop + connector tabs

> Needs tokens. Launch `gradlew.bat runIde`. Agent scenarios write to **both** `plugin-0.log` and the agent `.jsonl`; connector tabs write to `plugin-0.log` only.
>
> **EXPLICIT AGENT SAFETY INSTRUCTION:** Send only **strictly read-only** prompts. Do **NOT** ask the agent to edit/create/delete files in a real repo you care about (use a throwaway scratch project if you want to watch `edit_file`, or simply avoid write prompts). Do **NOT** ask the agent to perform any Jira / Bamboo / Bitbucket / Sonar **write** action. Safe prompts: `"summarize <file>"`, `"search the repo for X"`, `"explain this function"`, `"list the modules and their purpose"`.

### P1-A — Agent core (Sourcegraph URL + token + ≥1 model)

#### [P1-A1] Send message (task bootstrap)
- Steps: type a **read-only** prompt in the Agent chat (e.g. `summarize core/CLAUDE.md`), press Send. Watch spinner → first assistant response.
- ✅ **Success oracles:**
  - `[Agent] Resolved model: <modelId>`
  - `[Agent] Creating brain with model: <modelId> at <sgUrl> (tools=N, params=M)`
  - `[Agent] Task started: sessionId=<sid>, model=<modelId>`
  - `[Loop] Starting task (maxIterations=N, planMode=false)`
  - JSONL: `"event":"session_start"`
- ❌ **Failure oracles:**
  - `[Agent] Failed to fetch models from Sourcegraph: ...`
  - `[Agent] No models available and no model configured. Trying factory auto-resolution.`
  - `Cannot start agent: failed to fetch models from Sourcegraph (<sgUrl>)`
  - `AgentService: TASK_START hook cancelled task: <reason>`
  - `AgentController: USER_PROMPT_SUBMIT hook cancelled: <reason>`
- 📋 On failure share: `findstr /C:"[Agent]" /C:"BrainFactory" plugin-0.log` + JSONL `session_start`.

#### [P1-A2] ReAct loop / tool execution
- Steps: from the active read-only session, watch iterations (each calls ≥1 read/search tool → result → LLM again → `attempt_completion`). Confirm a tool card expands; if you want to see `run_command`, use a harmless read command like `git status`.
- ✅ **Success oracles:**
  - `[Loop] Iteration N -- M messages, X.X% context`
  - `[Loop] Executing tool: <toolName> (...)`
  - `run_command[<id>]: process started (streamCallback=true)` / `run_command[<id>]: first flush to UI (N chars)`
  - `[Loop] Tool <toolName> completed in <ms>ms (OK)`
  - `[Loop] Task completed in N iterations (<in> input, <out> output tokens)`
  - `[Agent] Task ended: status=COMPLETED, ...`
  - JSONL: `"event":"iteration"`, `"api_call"`, `"tool_call"` (`status:"ok"`), `"session_end"`
- ❌ **Failure oracles:**
  - `[Loop] Tool <toolName> failed: <...>`
  - `[Agent:RunCommand] BLOCKED command: <...>` / `[Agent:RunCommand] Rejected env vars: ...`
  - `[Loop] Task failed after N iterations: <...>`
  - `[Loop] Max consecutive mistakes (N) — waiting for user feedback`
  - `[Loop] Task failed after N iterations: exceeded maximum iterations`
  - `[Loop] Hard loop limit reached: '<toolName>' called N times consecutively`
  - `[HookRunner] PRE_TOOL_USE hook timed out after Nms, proceeding`
  - JSONL: `"event":"tool_call","status":"error"`, `"event":"loop_hard"` / `"loop_soft"`
- 📋 On failure share: `findstr /C:"[Loop]" /C:"[Agent:RunCommand]" plugin-0.log` + JSONL tool-call errors.
- ⛔ **SKIP (write op):** do not prompt the agent to run `edit_file` / `create_file` / `revert_file` against a repo you care about, or any shell command that mutates state. Keep `run_command` to read-only commands (`git status`, `git log`, `ls`).

#### [P1-A3] Plan mode block → switch to Act
- Steps: enable **Plan Mode** in the chat toolbar (or let the LLM call `enable_plan_mode`). Confirm the LLM presents a plan via `plan_mode_respond`, and that a **write tool is refused while in plan mode**. Click **Approve** → write tools then become available.
  - ⚠ The point of this test is to confirm the **block fires**, not to execute a write. After Approve, you may stop — do NOT then drive a real backend/file write. If you want to confirm Act mode at all, restrict it to a read tool or a throwaway scratch file.
- ✅ **Success oracles:**
  - `[Loop] Starting task (maxIterations=N, planMode=true)`
  - `[Loop] Plan mode enabled by LLM via enable_plan_mode tool`
  - `[Loop] Plan presented (needsMoreExploration=false)`
  - `[Agent] Model change requested by user: <modelId> — will apply on next iteration` (after Approve)
- ❌ **Failure oracles:**
  - tool result text `Error: '<toolName>' is blocked in plan mode. You can only read, search, and analyze code.` — this is the **expected** block; its **absence when a write was attempted in plan mode = guard leaked → REPORT**.
  - JSONL `"event":"tool_call","status":"error","error":"blocked in plan mode"`
  - `[Loop] Plan presented (needsMoreExploration=true)` → loop keeps exploring (noteworthy, not fatal).
- 📋 On failure share: `findstr /C:"[Loop]" /C:"[Agent]" plugin-0.log` + JSONL `blocked in plan mode`.

#### [P1-A4] Context window / compaction (~88%)
- Steps: run a read-only session long enough to reach ~88% of the window (default 132K) — many large file reads (e.g. "read and summarize these 10 files") will do it. Observe automatic compaction (the context bar resets).
- ✅ **Success oracles:**
  - `[Loop] Iteration N -- M messages, 87.X% context`
  - `[Loop] Context compaction triggered at X.X%`
  - `[Context] Compacting at X.X% utilization (N messages, iters=M)`
  - `[Context] Compacted: X.X% → Y.Y% (T1 → T2 tokens, ..., case=A)`
  - JSONL `"event":"compaction","tokensBefore":N,"tokensAfter":M`
- ❌ **Failure oracles:**
  - `[Context] auto-compaction failed: <reason>; falling back to slidingWindow(0.3)`
  - `[Context] Summarization LLM call failed: ...`
  - `[Loop] Context overflow detected, compacting and retrying (N/M)`
  - `[Loop] Compaction failed N consecutive times at overflow site — aborting loop`
  - `[AgentService] compactContext failed: ...; applying slidingWindow(0.3) as safety net`
- 📋 On failure share: `findstr /C:"[Context]" /C:"[Loop]" plugin-0.log` + JSONL `compaction`. (The slidingWindow fallback appears only in `plugin-0.log`.)

#### [P1-A5] Errors: offline / retry-backoff / upstream_timeout / empty response *(optional, read-only)*
- Steps: with an active read-only session: (a) drop network/VPN mid-task → Offline banner + Retry; (b) provoke 429/5xx → auto-retry with delay; (c) `upstream_timeout` → recovery up to 5×; (d) 200-OK-no-choices → temperature escalation to 1.0; (e) `finish_reason:"length"` → recovery to max retries. (All driven by network conditions, not backend writes.)
- ✅ **Success oracles (recovery):** `[Loop] API retry N/M (<reason>, delay=Xms)`; `[Loop] Timeout retries exhausted, compacting context and retrying (N/M)`; `[Loop] L2 tier escalation: <old> → <new>`; `[Loop] Recycling brain (N/M) on <type> ...`; JSONL `"event":"retry"`.
- ❌ **Failure oracles:** `[Loop] Confirmed offline (<netState>) ... — failing fast`; `[Loop] Empty response from LLM — provider error (... temperature escalated to 1.0)`; `[Loop] API success but response contained no choices at iteration N ...`; `[Loop] Upstream gateway-timeout recovery exhausted (N/M) — aborting`; `[Loop] Output-length truncation recovery exhausted (N/M) — aborting`; `[Context] timeout-recovery compaction failed: ...`; JSONL `"event":"session_end","failureType":"OFFLINE"` (or `"API_ERROR"`).
- 📋 On failure share: `findstr /C:"[Loop]" /C:"[Context]" plugin-0.log` + JSONL retry / failed `session_end`.

#### [P1-A6] Session resume (incl. locked-session)
- Steps: start a read-only task, let it run a few iterations, close/reopen the IDE (or switch sessions in history and back). On restart, observe the "Interrupted session" banner → click **Resume**.
- ✅ **Success oracles:** `AgentStartupActivity: found interrupted session '<id>' — '<title>'`; `AgentStartupActivity: cleaned up N orphan session director(ies)`; `[Agent] Resuming session: <id> (N api messages, interrupted Xs ago)`; `[AgentService] resume cleanup: dropped N trailing empty-assistant turn(s) ...`; `[AgentService] resume pickup: delivered N queued item(s) ...`; `[Context] Restored N messages, totalUserMessageCount=M`; `AgentService.resumeSession: session <id> was already completed, displaying without re-execution`.
- ❌ **Failure oracles:** `AgentService.resumeSession: session dir not found for <id>`; `AgentService.resumeSession: session <id> is locked by another instance` (locked-session path / stale lock); `AgentService.resumeSession: no api history for <id>`; `AgentService: TASK_RESUME hook cancelled resume: <reason>`; `AgentService.resumeSession: loadPersistedHandles failed for <id>`.
- 📋 On failure share: `findstr /C:"resumeSession" /C:"AgentStartupActivity" /C:"[Agent]" plugin-0.log` + (if dialect/trailing-turn suspected) `~/.workflow-orchestrator/{proj}-{sha6}/agent/sessions/{id}/api_conversation_history.json`.

### P1-B — Connector tabs: **LOAD-ONLY** (sink: `plugin-0.log`)

> For each: **open the tab, confirm data renders, read the oracle. That is the entire test.** Tab-provider gates are silent (empty-state UI, no marker); load failures DO log. Enumerated write-capable controls are marked **⛔ SKIP — do not click**.

#### [P1-B1] Jira (Sprint tab) — load only
- Prereqs: Jira URL + token; board auto-detected or `jiraBoardId` set.
- Steps: open Workflow → **Sprint** tab. Confirm sprint name in header, `(N tickets)` badge, status bar `"N tickets loaded"`. **Stop there.**
- ✅ **Success oracles:** `[Jira:UI] Sprint dashboard loaded <n> tickets`; `[Jira:API] GET /rest/agile/1.0/board?… -> 200` (debug).
- ❌ **Failure oracles:** `[Jira:API] Authentication failed (401)` (UI: "Error: Invalid Jira token"); `[Jira:API] Got text/html on <path> — likely auth-expired login redirect.`; `[Jira:API] Forbidden (403)`; `[Jira:API] Not found (404)`; `[Jira:API] Rate limited (429)`; `[Jira:API] Network error: <message>`; `[Jira:UI] Favourite filters load failed: <message>`.
  - No-oracle (report UI symptom): URL not configured → silent gate, UI "Jira not configured. Check URL and token in Settings."; Dev-status API failure → DevStatus section silently empty.
- 📋 On failure share: `findstr /C:"[Jira:API]" /C:"[Jira:UI]" plugin-0.log`.
- **Write-capable controls — DO NOT CLICK:**
  - ⛔ SKIP (write op): **"Start Work"** button/action (creates a Bitbucket branch AND transitions the ticket to In Progress).
  - ⛔ SKIP (write op): **status-transition dropdown** / any "Transition" / move-to-status control (`TRANSITION_ISSUES`).
  - ⛔ SKIP (write op): **time-tracking / log-work on commit** (`logWork`).
  - ⛔ SKIP (write op): **add/remove watcher**, **add comment**, or any field edit on a ticket.
  - ⛔ SKIP (write op): the **StartWorkDialog** "Create Branch" / "Start Work" OK button (do not even open-and-confirm it).

#### [P1-B2] Bamboo (Build tab) — load only
- Prereqs: Bamboo URL + token; plan key (or per-repo, or auto-detected from PR branch).
- Steps: open Workflow → **Build** tab (focus a PR to exercise plan auto-detect). Confirm latest/recent results render. **Stop there** — do NOT trigger the auth-redirect case (it requires a write op).
- ✅ **Success oracles:** `[Build:Dashboard] Resolved branch plan '<key>' via BambooService.autoDetectPlan (...)`; `[Bamboo:API] getLatestResult: GET /rest/api/latest/result/<key>/latest?...`; `[Bamboo:API] getRecentResults: N result(s) for <key> (...)`.
- ❌ **Failure oracles:** `[Bamboo:API] Authentication failed (401)`; `[Bamboo:API] Forbidden (403)`; `[Bamboo:API] Not found (404)` (likely wrong plan key); `[Bamboo:API] Network error: <message>`; `[Bamboo:API] Parse failed: <message>` (+ `Response body (first 200): <body>`); `[Build:Dashboard] No plan resolved for branch '<x>' and no configured planKey`; `[Build:Dashboard] Failed to check divergence: <message>`.
  - The `[Bamboo:API] DELETE got HTML body — auth redirect` signature only occurs on a **write** (stop/cancel) — **not exercised** here (that's a write op). 401/403 on poll has **no UI surface** (stage list silently clears) → rely on the `[Bamboo:API]` log, not the UI.
- 📋 On failure share: `findstr /C:"[Bamboo:API]" /C:"[Build:Dashboard]" plugin-0.log`.
- **Write-capable controls — DO NOT CLICK:**
  - ⛔ SKIP (write op): **"Stop Build"** (stops a running build).
  - ⛔ SKIP (write op): **"Cancel Build"** (cancels a queued build).
  - ⛔ SKIP (write op): **"Rerun Failed Jobs"** (re-runs jobs on Bamboo).
  - ⛔ SKIP (write op): **"Trigger Build"** (triggers a new remote build) and the **ManualStageDialog** "Trigger" OK button.
  - ✅ Allowed (no backend write): **"Refresh"** (force poll), **"Compile"**, **"Test"** (these run **local Maven** only, not Bamboo) — optional, not required for load-test.

#### [P1-B3] Sonar (Quality tab) — load only
- Prereqs: Sonar URL + token; project key (or auto-detected from Maven/Gradle).
- Steps: open Workflow → **Quality** tab with a PR focused. Confirm coverage/branch data renders. **Stop there.** (CE-403 with a non-admin token is normal/non-fatal.)
- ✅ **Success oracles:** `[Sonar:LineCoverage] Cached N line statuses for '<path>' (projectKey='<key>')`; `[Sonar:Branches] N branches analyzed:`; `[Sonar:Detect] Found explicit sonar.projectKey: <key>`.
- ❌ **Failure oracles:** `[Sonar:API] <path> -> 401 Auth failed`; `[Sonar:API] <path> -> 403 (insufficient permissions ...)`; `[Sonar:CE] /api/ce/activity 403 — token lacks Administer Project permission` (**non-fatal/normal** for non-admin tokens); `[Sonar:API] <path> -> 404 Not found`; `[Sonar:API] <path> -> 429 Rate limited`; `[Sonar:API] <path> -> <code> Server error`; `[Sonar:API] <path> -> Network error: <message>`; `[SonarService] componentExists preflight failed (...)`; `[Sonar:Detect] No tier matched project basePath: <path>`.
  - ⚠ **NO-ORACLE:** `QualityDashboardPanel` has **no logger** — project-key-not-configured / no-PR-focused / no-analysis / branch-warning are **UI-only** (status/hint label). For these, report the exact status-bar / hint text — nothing reaches the log.
- 📋 On failure share: `findstr /C:"[Sonar:API]" /C:"[Sonar:CE]" /C:"[SonarService]" /C:"[Sonar:Detect]" plugin-0.log`. If the tab is silent, quote the UI hint/status text.
- **Write-capable controls — DO NOT CLICK:**
  - ⛔ SKIP (write op): the **Sonar "fix" intention action** (`SonarFixIntentionAction`) in the editor gutter / Alt-Enter menu — do not invoke it (it can write to the file).
  - ⛔ SKIP (write op): any **"Resolve" / "Confirm" / "Mark False-Positive"** issue-status control if present.

#### [P1-B4] PR (Bitbucket PR tab) — load only
- Prereqs: Bitbucket URL + token; ≥1 repo with `bitbucketProjectKey` + `bitbucketRepoSlug`.
- Steps: open Workflow → **PR** tab. Confirm the PR list renders + status bar `"N PRs loaded [updated Xs ago]"`. You may open a PR's **detail** panel to confirm it renders — but **touch none of the action buttons**.
- ✅ **Success oracles:** `[PR:List] Found N authored PRs (dashboard, scoped to M configured repos, state=OPEN)`; `[PR:List] Found N total repo PRs across M repos (state=OPEN)`; `[PR:List] Auto-detected Bitbucket username: <username>`.
- ❌ **Failure oracles:** `[PR:List] Bitbucket URL not configured, skipping refresh`; `[PR:List] Bitbucket project/repo not configured, skipping refresh`; `[BitbucketService] Failed to fetch my PRs: Invalid Bitbucket token`; `[BitbucketService] Failed to fetch my PRs: Cannot reach Bitbucket: <message>`; `[PR:List] Could not detect username — PR list may not filter correctly`; `[PR:Dashboard] CreatePrLauncher EP not registered — cannot open dialog`.
  - No-oracle: tab-provider gate (URL blank) is silent → empty-state "No pull request services configured…". An auth-fail path can also end at "No pull requests open." — distinguish via the `[PR:List]` / `[BitbucketService]` log.
- 📋 On failure share: `findstr /C:"[PR:List]" /C:"[BitbucketService]" /C:"[PR:Dashboard]" plugin-0.log`.
- **Write-capable controls — DO NOT CLICK (PrDashboard / PrDetailPanel):**
  - ⛔ SKIP (write op): **"Create PR"** action and the **CreatePrDialog** "Create PR" OK button.
  - ⛔ SKIP (write op): **"Merge"** button (and the merge confirm dialog "Merge" OK button).
  - ⛔ SKIP (write op): **"Decline"** button.
  - ⛔ SKIP (write op): **"Approve"** button (and any "Unapprove"/"Needs Work" toggle — these are participant-status writes).
  - ⛔ SKIP (write op): **add comment** / reply field ("Comment" / "Add Comment" → `PrActionService.addComment`), incl. inline diff comments.
  - ⛔ SKIP (write op): **add/remove reviewer**, **edit title/description**, or any PR field edit.
  - ✅ Allowed (no backend write): **"Refresh"** (re-poll), expanding/collapsing the detail panel and diff view.

---

## 4. P2 — Extended (sub-agents, monitors, automation, handover)

> Launch `gradlew.bat runIde`. Keep everything read-only.

### [P2-1] Agent — Sub-agents (`spawn_agent`) — read-only tasks only
- Prereqs: active agent session (Sourcegraph). Up to 5 parallel.
- Steps: prompt that triggers **read-only** parallel dispatch, e.g. `"in parallel, summarize each of these 3 files: A, B, C"`. Optionally Stop one sub-agent.
  - ⚠ Every sub-agent task must be **read/search/analyze** only. Do NOT phrase the prompt so a sub-agent edits files or hits a connector write.
- ✅ **Success oracles:** `[Loop] Executing tool: spawn_agent (...)`; per-branch `[Agent] Task started: sessionId=<subSid> ...` + `[Loop] Task completed ...`; JSONL `session_start`/`session_end` per sub-agent. All sub-agents write the SAME JSONL — demux by `session` field (first 11 chars).
- ❌ **Failure oracles:** `[SpawnAgent] Abort requested for subagent <agentId>`; `AgentController: subagent <agentId> cancelled`; `[SubagentRunner] Failed: <errorMsg>`; `[SpawnAgent] Config '<name>' references unknown core/deferred tool: <name>`.
- 📋 On failure share: `findstr /C:"[SpawnAgent]" /C:"[SubagentRunner]" /C:"[Loop]" plugin-0.log` + JSONL demuxed by `session`.

### [P2-2] Agent — Monitors / idle-wake — READ-ONLY shell monitor
- Prereqs: session with a monitor registered (persists across restart, re-armed on resume). **No JSONL** — monitors use the EventBus path; `plugin-0.log` only.
- Steps: register a **read-only** shell-command monitor via the `monitor` tool — e.g. `git status` (or `git log -1`). Let the session idle; observe the idle-wake card when it fires.
  - ⚠ Use only a **read-only** shell command in the monitor. Do NOT register a command that mutates anything (no `git commit`, `git push`, build triggers, etc.).
- ✅ **Success oracles:** `reArmMonitors: re-arming N monitor(s) for <sessionId>`; `reArmMonitors: re-armed <id> (<sourceType>) ...`; `[MonitorPool] emitSnapshot session=<id> count=N ids=[...]`; `[Monitor] forwarding to webview — session=<active> count=N ...`; `[AgentService] resume pickup: delivered N persisted monitor notification(s) for <id>`.
- ❌ **Failure oracles:** `reArmMonitors: build failed for <id> (<sourceType>): <error>`; `reArmMonitors failed for <id> — resume continues unaffected: ...`; `[MonitorPool] EventBus service is null — event not emitted (session=<id>)`; `[ShellCommandSource:<id>] failed: ...`; `[AgentController] auto-wake resume failed for session <id>: ...`.
- 📋 On failure share: `findstr /C:"reArmMonitors" /C:"[MonitorPool]" /C:"[Monitor]" plugin-0.log`.

### [P2-3] Automation tab — suite LOAD/VIEW only
- Prereqs: Bamboo URL + token; a plan/suite.
- Steps: Workflow → **Automation** tab → **select a suite** from the dropdown; confirm the baseline/Docker-tag data renders. **Stop there.**
- ✅ **Success oracles (load/view):** `[Automation:UI] Suite selected: <PLAN_KEY> (gen=<N>) — sticky baseline, no scan`; `[Automation:Tags] Found <N> recent builds for '<PLAN_KEY>'`; `[Automation:Tags] Selected baseline: build #<N>, <M>/<T> release tags, score=<S>`; `[Automation:Tags] Built JSON payload with <N> services, length=<L>`; on restart `[Automation:Cache] Loaded <N> cached suite baseline(s) from disk.`.
- ❌ **Failure oracles:** `[Automation:Tags] getRecentBuilds failed for '<PLAN_KEY>': ...` (Bamboo URL/auth); `[Automation:Tags] No baseline runs found`; `[Automation] Bamboo URL is blank — showing configure message`; `[Automation:Cache] Failed to persist cache`; `[Automation:UI] onTriggerDefault: stage fetch failed`; `[Automation:Monitor] Poll failed for <resultKey>`.
- 📋 On failure share: `findstr /C:"[Automation:Tags]" /C:"[Automation:UI]" /C:"[Automation:Cache]" plugin-0.log`.
- **Write-capable controls — DO NOT CLICK:**
  - ⛔ SKIP (write op): **"Run" / "Trigger"** the suite (the success oracle `[Automation:UI] Run queued for suite <PLAN_KEY>` is a **write** — do NOT cause it; selecting a suite is enough).
  - ⛔ SKIP (write op): any **re-trigger / queue / monitor-default-trigger** action.

### [P2-4] Handover tab — LOAD/VIEW only
- Prereqs: Jira + (for the closure view) Automation suite results loaded.
- Steps: Workflow → **Handover** tab → open the **Checks** / **Actions** / **Share** subtabs and confirm they render (closure-comment preview, copyright scan list, AI pre-review summary). **View only.**
- ✅ **Success oracles (build/preview, read side):** `[Handover:Jira] Building closure comment from <N> suite results`; `[Handover:Jira] Closure comment built with <N> docker tags`; copyright scan `[Handover:Copyright] Missing copyright header: <filePath>` (the scan itself is read-only). (AI pre-review **success is silent** — summary appears in the card, no log.)
- ❌ **Failure oracles:** `[Handover:Jira] No suite results provided for closure comment` (Automation not loaded); `[Handover:Resolver] Failed to parse dockerTagsJson`; `[Handover:Copyright] Fix-all aborted: copyrightTemplate is blank`; `[Handover:Copyright] Failed to fix <filePath>`; AI pre-review `[Handover:AiCache] Generation failed for <key>: <message>`.
- 📋 On failure share: `findstr /C:"[Handover:Jira]" /C:"[Handover:State]" /C:"[Handover:Copyright]" /C:"[Handover:AiCache]" plugin-0.log`.
- **Write-capable controls — DO NOT CLICK:**
  - ⛔ SKIP (write op): **Jira closure** "Post Comment" action (posts a comment to Jira → `[Handover:State] jiraCommentPosted=true`).
  - ⛔ SKIP (write op): **Jira status transition** from Handover (→ `[Handover:State] Marked Jira as transitioned to <statusName>`).
  - ⛔ SKIP (write op): Copyright **"Fix All"** button (writes copyright headers into files → `[Handover:Copyright] Failed to fix ...` on error path; the **scan/rescan** is read-only and fine, but **Fix All writes files**).
  - ⛔ SKIP (write op): **time-log / "log work"** card actions, and any "Mark done"/state-write control.
  - ✅ Allowed (no backend write): viewing the closure-comment **preview**, running the copyright **scan/rescan** (read-only), reading the AI pre-review summary, copying text to clipboard.

---

## Appendix — quick reference

| Log | Path (Windows) | When |
|---|---|---|
| **PRIMARY — plugin-0.log** | `%USERPROFILE%\.workflow-orchestrator\diagnostics\plugin-0.log` | Every scenario. Share whole file on failure. |
| **Agent JSONL** | `%USERPROFILE%\.workflow-orchestrator\{proj}-{sha6}\logs\agent-YYYY-MM-DD.jsonl` | Agent failures (P1-A, P2-1, P2-2 — note P2-2 monitors have NO JSONL). |
| Session API history | `…\{proj}-{sha6}\agent\sessions\{id}\api_conversation_history.json` | Resume / dialect issues only. |
| FALLBACK — idea.log | `build\idea-sandbox\IU-2025.1.7\log\idea.log` (Help → Show Log in Explorer) | Only if `plugin-0.log` is absent: `findstr /C:"#com.workflow.orchestrator" idea.log > workflow-share.log`. |

**No-log-oracle scenarios (report the UI symptom, not a marker):** all connector tab-provider gates (URL blank); the entire Sonar Quality panel (no logger); Bamboo poll 401/403 (stage list silently clears); Jira "Start Work" Bitbucket-not-configured checks; Jira Dev-status API failure (DevStatus silently empty); Agent JCEF blank/white webview; Handover AI pre-review success (silent); monitors (no JSONL).

**⛔ MASTER SKIP LIST (never click — accidental backend write):** Jira Start Work · Jira status transition · Jira time-log/log-work · Jira add comment/watcher/field edit · Bamboo Stop Build · Bamboo Cancel Build · Bamboo Rerun Failed Jobs · Bamboo Trigger Build / ManualStageDialog Trigger · PR Create · PR Merge · PR Decline · PR Approve/Unapprove · PR add comment (incl. inline) · PR add/remove reviewer · PR edit title/description · Sonar fix-intention · Sonar resolve/confirm/false-positive · Automation Run/Trigger suite · Handover Jira closure Post Comment · Handover Jira transition · Handover Copyright "Fix All" · Handover log-work/mark-done · agent `edit_file`/`create_file`/`revert_file` against a real repo · agent mutating shell commands · agent prompts that drive any connector write.
