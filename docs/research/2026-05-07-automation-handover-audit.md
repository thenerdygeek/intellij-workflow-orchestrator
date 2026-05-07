# `:automation` and `:handover` audit — what's actually broken

**Date:** 2026-05-07
**Branch:** `fix/automation-handover-quality-tabs`
**Scope:** every Kotlin file under `automation/src/main/` and `handover/src/main/`. Cross-checked against Bamboo 10.2.14 probe bundles (`tools/atlassian-probe/Result_Bamboo/bundle-{automation,repo}.unpacked/`) and Nexus 3.90.1-01 probe (`tools/atlassian-probe/Result_Nexus/`).
**Prior commit on branch:** `59c9ea8d` (Bamboo audit fixes — DTO `key`-shape, branch fallback, log size cap). This audit picks up where that left off, in modules `:automation` and `:handover`, and **does not re-list bugs already fixed there**.
**Reference doc:** `docs/research/2026-05-07-bamboo-audit-recommendations.md`.

---

## 1. TL;DR

**Total bugs found: 21** (5 P0, 9 P1, 7 P2). The user's "totally broken" framing is fully accurate for `:handover` and partially accurate for `:automation`.

### `:handover` — almost the entire tab is dead UI

The Handover tab renders six sub-panels (Copyright, AI Review, Jira Comment, Time Log, QA Clipboard, Macro). **None of them are wired.** Every one of the panels' setter methods (`setEntries`, `setFindings`, `setCommentText`, `setTicket`, `setDockerTags`, `setFormattedText`, `setSteps`) is defined in the panel class but **never called from production**. Every primary action button on every sub-panel is hard-coded `isEnabled = false` with `toolTipText = "Coming soon"`. The tab is a static IKEA-flat-pack — assembled, never used. See bugs **H-P0-1** through **H-P0-3** and **H-P1-1** through **H-P1-5**.

### `:automation` — one P0 silent-success bug, several latent

The Automation tab loads tags, lets the user trigger, and shows a Monitor view. The **Trigger Now** button works in the happy path. But:

- **Tag validation breaks Trigger when registry URL points at Nexus's path-based Docker repo** (the documented shape per `:automation/CLAUDE.md`): `/v2/...` returns 404 at the registry root on this Nexus install, so every `tagExists()` call silently maps to `Success(false)` ("tag missing"), which **blocks every trigger** with the misleading "One or more Docker tags no longer exist". See **A-P0-1**.
- **Saved per-suite variables get destroyed every time the user clicks Apply on Settings** because `AutomationConfigurable.apply()` calls `saveSuiteConfig` with a freshly-constructed `SuiteConfig` (variables = empty map). Any extra-variable values typed into the Configure tab are lost on next Apply. See **A-P0-2**.
- **Default `bambooBuildVariableName` is `DockerTagsAsJson` (capital D) but two services hard-default to `dockerTagsAsJson` (lowercase) and `TagValidationLogic.extractDockerTagsJson` hardcodes the lowercase name** — so on a fresh install with the persisted default, the validation pre-run hook silently does nothing. See **A-P0-3**.
- **`MonitorPanel` polling never terminates** because `pollAllRuns` mutates the `DefaultListModel` from a non-EDT thread (silent corruption) AND `TERMINAL_STATUSES` doesn't include `"Unknown"` so a NotBuilt build polls forever. See **A-P0-4**.
- **`QueueService` polling never terminates** because completed entries are never removed from `_stateFlow`, so `_stateFlow.value.isEmpty()` is never true. See **A-P0-5**.
- **`QueueStatusPanel` has a Cancel button that's permanently disabled and a status label that's permanently "Suite Idle"** — the panel has no observer of `queueService.stateFlow`. See **A-P1-2**.

### Top 5 by user-visible impact

1. **A-P0-1** — Trigger Now blocked by silent tag-validation failure when Nexus is path-based (which the org uses, per `reference_nexus3_url_conventions.md`).
2. **H-P0-1** — Entire Handover tab is dead UI.
3. **A-P0-2** — Per-suite extra variables wiped on every Settings → Apply.
4. **A-P0-4** — Monitor tab leaks polling and corrupts UI under EDT contention.
5. **A-P0-3** — Default config name mismatch silently disables tag pre-validation hook.

### What's in this doc

- §2: P0 bugs — must-fix before shipping
- §3: P1 bugs — silent failures, dead-code wiring, UX broken
- §4: P2 bugs — cleanup, latent, minor UX
- §5: What was checked but is fine
- §6: Open questions
- §7: Cross-module reference table

---

## 2. P0 bugs — `:automation`

### A-P0-1 — Tag validation always returns "missing" on Nexus path-based Docker registries (silent block)

**Files:**
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/api/DockerRegistryClient.kt:51` (HEAD URL)
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagValidationService.kt:103` (HEAD URL)
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/run/TagValidationBeforeRunProvider.kt:104` (HEAD URL, third copy of same builder)
- Caller: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt:339-353` (`validateTags`) blocks Trigger when any tag returns `Success(false)`.

**Excerpt:**

```kotlin
// DockerRegistryClient.kt:50-54
val request = Request.Builder()
    .url("$registryUrl/v2/$serviceName/manifests/$tag")  // ← assumes port-based registry root
    .head()
    .header("Accept", "application/vnd.docker.distribution.manifest.v2+json")
    .build()
```

**What's wrong:** Nexus 3 path-based Docker repos are reachable at `/repository/{repo-name}/v2/{image}/manifests/{tag}`, NOT at `/v2/{image}/manifests/{tag}`. The Nexus probe (`Result_Nexus/bundle-discover-compressed.unpacked/raw/docker_v2_root.json`) confirms `GET /v2/` on this server returns **404 HTML** ("404 - Sonatype Nexus Repository"). On a path-based deployment, the registry root URL the user types into Settings (e.g. `https://nexus.example.com/repository/docker-hosted`) MUST end in the repo path, and the plugin must concat `/v2/...` to it. With three different builders all assuming `<base>/v2/...`, only port-based deployments work.

The HEAD endpoint sees:
- `DockerRegistryClient.tagExists` line 59 maps **404 → `ApiResult.Success(false)`** ("tag does not exist") — silently masking the URL-shape error.
- `QueueService.validateTags` line 349: `if (result is ApiResult.Success && !result.data) return false` — every tag short-circuits to false, every Trigger is blocked with summary `"One or more Docker tags no longer exist in the registry"` (line 301).

**Hypothesised user-visible symptom:** the Trigger Now and Queue Run flows fail with a misleading "tags missing" message even though the tags exist in Nexus. The registry tag list visible in the user's Nexus admin UI looks correct; the plugin's pre-validation refuses to use it. This matches the user's "totally broken" framing — the action button visibly hangs and errors with a wrong reason.

**Suggested fix sketch:** add a `dockerBasePath` field to `PluginSettings.State` (default `""`) and concat it ahead of `/v2/...` in all three builders. Provide a settings-time probe that does `GET <registryUrl>/<dockerBasePath>/v2/` and shows green if 200/401/empty body, red+suggested-path otherwise. The Nexus probe driver already supports `--docker-base-path` per commit `5dd1814c` — mirror that flag in the plugin.

**Cross-reference:** memory entry `reference_nexus3_url_conventions.md`. The fix needs to apply to all three duplicate URL builders (`DockerRegistryClient`, `TagValidationService`, `TagValidationLogic`) — see **A-P2-3** for the broader consolidation.

---

### A-P0-2 — Settings → Apply destroys per-suite variables (silent data loss)

**File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/settings/AutomationConfigurable.kt:405-424`

**Excerpt:**

```kotlin
override fun apply() {
    dialogPanel?.apply()

    val automationSettings = AutomationSettingsService.getInstance()
    automationSettings.state.suites.clear()                  // line 411 — wipes EVERYTHING
    for (row in suiteRows) {
        val key = row.planKeyField.text.trim()
        val name = row.displayNameField.text.trim()
        if (key.isNotBlank()) {
            automationSettings.saveSuiteConfig(
                AutomationSettingsService.SuiteConfig(       // line 417 — fresh empty SuiteConfig
                    planKey = key, displayName = name.ifBlank { key },
                    lastModified = System.currentTimeMillis()
                    // variables = mutableMapOf() (default), enabledStages = mutableListOf() (default)
                )
            )
        }
    }
}
```

**What's wrong:** `SuiteConfig.variables` is the source of truth for the user's per-suite variable selections (filled by `SuiteConfigPanel.persistVariables()` at `SuiteConfigPanel.kt:240-245`). On every Settings → Apply, the entire `state.suites` map is cleared and rebuilt with default-constructed `SuiteConfig` instances that have empty `variables`. Every variable name + value the user selected on the Configure tab vanishes.

**Hypothesised user-visible symptom:** user adds three custom variables (`branchName`, `dryRun`, `featureFlag`) on the Configure tab, sets values, clicks "Trigger Now" — works. User opens Settings to e.g. add a new suite, clicks Apply, switches back to Automation tab. All three variables are gone. Trigger now sends only `dockerTagsAsJson`. The user perceives Automation as "broken" because their workflow inputs disappeared on what looks like an unrelated action.

**Suggested fix sketch:** in `apply()`, do not `clear()`. Instead, compute the diff: existing keys minus edited keys → remove; edited keys with no existing config → insert; existing keys with edited displayName → update only displayName + lastModified, preserving `variables` and `enabledStages`. Or: read the existing `SuiteConfig` per planKey first, mutate only displayName + lastModified, then save.

---

### A-P0-3 — Default `bambooBuildVariableName` mismatch silently disables tag pre-validation

**Files:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt:112` — `var bambooBuildVariableName by string("DockerTagsAsJson")` (capital D)
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/run/TagValidationBeforeRunProvider.kt:97` — `obj["dockerTagsAsJson"]?.jsonPrimitive?.content ?: ""` (lowercase d, **hardcoded, ignores the setting entirely**)
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagBuilderService.kt:30` — fallback `?: "dockerTagsAsJson"` (used at construction time only — see below)
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/ConflictDetectorService.kt:22` — fallback `?: "dockerTagsAsJson"`
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt:83` — fallback `?: "dockerTagsAsJson"`
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/settings/AutomationConfigurable.kt:109-110` — DSL bindText fallback `?: "dockerTagsAsJson"` (dead — the field is non-null and defaulted in PluginSettings)

**What's wrong:** The plugin defaults `bambooBuildVariableName` to `"DockerTagsAsJson"` in `PluginSettings`. The `TagValidationLogic.extractDockerTagsJson` (the Run-Configuration Before-Run hook) reads the raw JSON string and looks up the key `"dockerTagsAsJson"` (lowercase) directly without any case-insensitive lookup or settings indirection. Result: on a fresh install where `bambooBuildVariableName == "DockerTagsAsJson"`, the Before-Run validator never finds the key, returns `""`, and short-circuits with "no tags to validate" — silently no-op'ing the entire validation. The reader paths that ARE case-insensitive (`TagBuilderService.scoreAndRankRuns:87`, `ConflictDetectorService:47`) work fine, but the Run-Configuration validator does not.

The settings DSL fallback `?: "dockerTagsAsJson"` (line 109-110 in `AutomationConfigurable.kt`) at first looks like it patches this — but the field is **already** defaulted in PluginSettings.State.<init>, so `state.bambooBuildVariableName` is never null. The `?:` is dead.

**Hypothesised user-visible symptom:** Bamboo plan-level variable is named `DockerTagsAsJson` (Bamboo's UI default for the user's existing setup). Plugin reads it case-insensitively (works in baseline-load and conflict-check), but the Trigger pre-run validation hook never sees it and silently skips. User believes validation is running ("I have it on") but every trigger goes through with no actual check. Real tags-don't-exist case slips through to a Bamboo build that fails 30s later with a Docker pull error.

**Suggested fix sketch:** rewrite `TagValidationLogic.extractDockerTagsJson(buildVariables, configuredVarName)` to take the configured name as a parameter, and look up case-insensitively via `obj.entries.firstOrNull { it.key.equals(configuredName, ignoreCase = true) }?.value?.jsonPrimitive?.content`. Pass through from `TagValidationBeforeRunProvider.executeTask`. Bonus: pick ONE canonical default casing across the codebase (recommendation: `"dockerTagsAsJson"` to match Bamboo's `inject.GIT_COMMIT_SHA`-style camelCase in the probe at `result_variables.json:175-180` — but verify with the user's actual Bamboo plan variable name first).

---

### A-P0-4 — `MonitorPanel.pollAllRuns` corrupts `DefaultListModel` off-EDT and never terminates polling on terminal "Unknown" builds

**File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/MonitorPanel.kt:127-192, 371`

**Excerpt:**

```kotlin
// MonitorPanel.kt:114-125
private fun startPolling() {
    poller?.stop()
    poller = SmartPoller(
        name = "AutomationMonitor",
        baseIntervalMs = 15_000,
        scope = scope                    // Dispatchers.IO
    ) {
        val hadActive = hasActiveRuns()  // ← reads runListModel from IO thread
        pollAllRuns()
        hadActive || hasActiveRuns()
    }.also { it.start() }
}

// MonitorPanel.kt:127-132
private fun hasActiveRuns(): Boolean {
    for (i in 0 until runListModel.size()) {                       // ← off-EDT read of Swing model
        if (runListModel.getElementAt(i).status !in TERMINAL_STATUSES) return true
    }
    return false
}

// MonitorPanel.kt:134-146
private suspend fun pollAllRuns() {
    val bambooService = project.getService(BambooService::class.java) ?: return
    for (i in 0 until runListModel.size()) {                       // ← off-EDT read of Swing model
        val entry = runListModel.getElementAt(i)
        if (entry.status in TERMINAL_STATUSES) continue
        // ... bambooService.getBuild(...) ...
    }
}

// MonitorPanel.kt:371
private val TERMINAL_STATUSES = setOf("Successful", "Failed")
```

**What's wrong, two issues:**

1. **EDT violation.** `DefaultListModel.size()` and `getElementAt()` are called from `Dispatchers.IO` (the `scope` passed to `SmartPoller`). Swing models are not thread-safe; concurrent mutations from the EDT in `addRun` (line 108: `runListModel.add(0, entry)`) and from this background loop produce silent corruption (UI stale rows, intermittent IndexOutOfBoundsException on cell renderer paint). Per `:core` Phase 4 conventions, all Swing reads/writes go on EDT.

2. **`"Unknown"` is missing from `TERMINAL_STATUSES`.** Per Bamboo probe `bundle-automation.unpacked/raw/result_recent.json:685-686`: `"lifeCycleState": "NotBuilt", "state": "Unknown"`. After `BambooServiceImpl.toBuildResultData` does `state.ifBlank { lifeCycleState }`, this maps to `state="Unknown"` (because state is non-blank). `TERMINAL_STATUSES` only contains `"Successful", "Failed"` — so a NotBuilt build (e.g. user manually skipped a stage, or all stages were already-up-to-date) **is never recognized as terminal**. `pollAllRuns` keeps fetching `getBuild()` for that resultKey forever, every 15 seconds, until project close.

**Hypothesised user-visible symptom:** Monitor tab list flickers or shows out-of-order rows (Item 1) on busy poll cycles. Rare IndexOutOfBoundsException toasts from the cell renderer (Item 1). For NotBuilt builds (Item 2), the Bamboo log file in `idea.log` accumulates 4 polls/minute forever per stuck run — quietly burning bamboo API quota.

**Suggested fix sketch:**
- Item 1: snapshot `runListModel.elements().toList()` on the EDT at the start of each poll, work off the snapshot, marshal updates back via `invokeLater`. (This is partially done — line 175, 183 `invokeLater { runListModel.set(i, withTests) }` — but the index `i` may be stale by then, and the read of the model still happens off-EDT.)
- Item 2: change `TERMINAL_STATUSES = setOf("Successful", "Failed", "Unknown")`. Document that any non-{Successful,Failed,Unknown,InProgress,Pending,Queued} state is unknown-and-treated-as-terminal-to-avoid-stuck-poll. Or better: thread `BuildResultData.lifeCycleState` through the model so we can check `lifeCycleState == "Finished" || == "NotBuilt"` directly. Currently `BuildResultData` does NOT expose `lifeCycleState`, only the merged `state` — so the polling loop can't disambiguate. See **A-P1-1**.

---

### A-P0-5 — `QueueService` polling never terminates; completed entries never leave `_stateFlow`

**File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt:192-216, 218-239, 265-290`

**Excerpt:**

```kotlin
// QueueService.kt:195-215
pollingJob = cs.launch(Dispatchers.IO) {
    while (true) {
        if (pollInProgress.compareAndSet(false, true)) {
            try { pollOnce() } finally { pollInProgress.set(false) }
        }
        val hasActive = _stateFlow.value.any { it.status in ACTIVE_STATUSES }
        val interval = if (hasActive) 15_000L else 60_000L
        // ...
        delay(interval + jitter)
        if (_stateFlow.value.isEmpty()) {                    // ← never true
            log.info("[Automation:Queue] Queue empty, stopping polling")
            break
        }
    }
    pollingJob = null
}

// QueueService.kt:265-290 — handleRunningOrQueued
private suspend fun handleRunningOrQueued(entry: QueueEntry): QueueEntry {
    // ...
    return when {
        buildData.state == "Successful" || buildData.state == "Failed" -> {
            // ...
            entry.copy(status = QueueEntryStatus.COMPLETED)   // ← KEEPS the entry, just flips status
        }
        buildData.state == "InProgress" || buildData.state == "Unknown" -> entry.copy(status = QueueEntryStatus.RUNNING)
        else -> entry
    }
}
```

**What's wrong:** completed entries are never removed from `_stateFlow.value`. The only removal site is `cancel()` at line 166. So once an entry reaches COMPLETED, it stays in the state forever, `_stateFlow.value.isEmpty()` is never true, the poll loop never exits. Every 60s the loop wakes up, calls `pollOnce()`, iterates all completed entries (no-op `else -> entry`), sleeps. Forever. Until `cs` is canceled (project close). Memory grows linearly with cumulative-runs-this-session. UI cost low (few entries), but the polling work is wasted.

Same `state == "Unknown"` issue as **A-P0-4**: a Bamboo NotBuilt build is misclassified as RUNNING (because `state == "Unknown"` matches the RUNNING branch). The queue entry is stuck in RUNNING forever; `handleRunningOrQueued` keeps returning the same RUNNING entry.

**Hypothesised user-visible symptom:** Bamboo API call rate stays elevated forever. Status-bar widget never reverts to "Suite Idle" after a NotBuilt outcome — sticks at "Running". Per Bamboo audit (§7), the `/result/{key}` endpoint is one of the heavier ones (~100ms+ per call); 4 polls/min × N stuck entries × hours-of-IDE = real noise.

**Suggested fix sketch:**
- After a build hits a terminal status (Successful, Failed, **and Unknown=NotBuilt**), emit `_stateFlow.value = _stateFlow.value.filter { it.id != entry.id }` (remove from active state) AND keep the SQLite history. The poll loop then naturally exits when the last active entry resolves.
- Use `lifeCycleState in {"Finished", "NotBuilt"}` for terminal check, not state-string equality. Plumbs through **A-P1-1**.
- Defensively: bound the loop with `maxIterations = 10_000` (or compute a wallclock budget) — even with the fix, a misconfigured Bamboo could spin the loop forever.

---

## 2.5. P0 bugs — `:handover`

### H-P0-1 — Every panel setter is dead code; the entire tab is static UI

**Files:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/panels/*.kt` (all six sub-panels)

**Inventory:**
- `CopyrightPanel.setEntries` (`CopyrightPanel.kt:54`) — never called from production
- `PreReviewPanel.setFindings` (`PreReviewPanel.kt:35`) — never called from production
- `JiraCommentPanel.setCommentText` (`JiraCommentPanel.kt:49`) — never called from production
- `TimeLogPanel.setTicket` (`TimeLogPanel.kt:101`) — never called from production
- `QaClipboardPanel.setDockerTags` (`QaClipboardPanel.kt:75`) — never called from production
- `QaClipboardPanel.setFormattedText` (`QaClipboardPanel.kt:98`) — never called from production
- `CompletionMacroPanel.setSteps` (`CompletionMacroPanel.kt:51`) — never called from production

(verified by `grep -rn "<setter-name>" handover/src/main` — only the definition site appears, no callers).

**What's wrong:** `HandoverPanel` (`HandoverPanel.kt`) instantiates all six sub-panels and adds them to a `CardLayout`, but never invokes any of their data setters. The only data plumbing in the tab is the `HandoverContextPanel.updateState(state)` collector at `HandoverPanel.kt:63-67`, which only updates the **left-side context sidebar**, not the right-side detail panels. So the user sees:

- Copyright tab: "No files to check." forever
- AI Review tab: empty findings list, "Click Analyze to run AI pre-review"
- Jira Comment tab: empty preview, "Coming soon" Post button
- Time Log tab: "Select a ticket to log time." even when a ticket is active
- QA Clipboard tab: "No services configured." even after suites have triggered
- Macro tab: "No steps configured."

**Hypothesised user-visible symptom:** the user clicks every Handover sub-tab and gets an empty pane. None of the documented features in `:handover/CLAUDE.md` ("Jira closure, copyright enforcement, pre-review, QA handover") actually work. Matches "totally broken" exactly.

**Suggested fix sketch:** wire each panel by collecting `HandoverStateService.stateFlow` in `HandoverPanel.init` and calling the appropriate setter on each transition. For `TimeLogPanel.setTicket` use `state.ticketId`. For `QaClipboardPanel.setDockerTags` and `setFormattedText` use `QaClipboardService.buildPayloadFromSuiteResults(state.suiteResults, listOf(state.ticketId)).run { dockerTags + formatForClipboard(this) }`. For `JiraCommentPanel.setCommentText` use `JiraClosureService.buildClosureComment(state.suiteResults)`. For `CopyrightPanel.setEntries` and `PreReviewPanel.setFindings` — these need scan-on-demand actions (not state-driven) and they're behind the `"Coming soon"` button gate (see **H-P0-2**). For `CompletionMacroPanel.setSteps` use `CompletionMacroService.getDefaultSteps()` once at panel init.

---

### H-P0-2 — Five primary action buttons are permanently disabled with "Coming soon"

**Files:**
- `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/panels/JiraCommentPanel.kt:20-23` — `postButton.isEnabled = false`
- `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/panels/PreReviewPanel.kt:19-22` — `analyzeButton.isEnabled = false`
- `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/panels/CopyrightPanel.kt:21-28` — `fixAllButton.isEnabled = false`, `rescanButton.isEnabled = false`
- `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/panels/TimeLogPanel.kt:38-41` — `logButton.isEnabled = false`
- `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/panels/QaClipboardPanel.kt:25-28` — `addServiceButton.isEnabled = false`
- `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/HandoverContextPanel.kt:42-45` — `transitionButton.isEnabled = false`

**What's wrong:** seven buttons across six panels are hard-coded `isEnabled = false`, with `toolTipText = "Coming soon"`. The only buttons in `:handover` that ARE wired and enabled are `QaClipboardPanel.copyAllButton` (line 24, 44) and the `Edit` toggle on `JiraCommentPanel.editButton` (line 19, 29-32). No primary action works.

**Hypothesised user-visible symptom:** every button-click does nothing. Tooltip "Coming soon". Matches "totally broken".

**Suggested fix sketch:** these correspond to features documented in `:handover/CLAUDE.md`:
- Post Jira comment → `HandoverJiraClient.addComment` (mentioned in CLAUDE.md but the client class doesn't exist — see **H-P1-3**).
- Analyze with AI → `PreReviewService.buildEnrichedReviewPrompt` + `TextGenerationService` (the EP exists in `:core`).
- Fix copyright → there's a fully-implemented `CopyrightFixService` (147 LOC) but no Fix-All command.
- Log work → wire to a yet-to-exist worklog API call.

Each is a 30-200 LOC implementation. The audit can't fix this; flag for the implementer.

---

### H-P0-3 — `JiraClosureService.buildClosureComment` and `CompletionMacroService.executeMacro` are dead

**Files:**
- `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/JiraClosureService.kt:26` — `buildClosureComment` only used by `JiraClosureServiceTest`. `JiraCommentPanel` doesn't reference it.
- `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/CompletionMacroService.kt:69` — `executeMacro` only used by `CompletionMacroServiceTest`. `CompletionMacroPanel` doesn't reference it; the run button has no action listener (see `CompletionMacroPanel.kt:23` — declared but no `addActionListener` anywhere).

(verified by grep across `handover/src/main` and `core/src/main`).

**What's wrong:** ~150 LOC of service code (closure-comment formatter + macro orchestrator) exists, has tests, but is never invoked. Both should be wired to the corresponding panels per **H-P0-1**.

**Suggested fix sketch:** part of the **H-P0-1** fix. Same panel-data-binding pass.

---

## 3. P1 bugs

### A-P1-1 — `BuildResultData` model loses `lifeCycleState`, blocking correct terminal-vs-running detection

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/model/bamboo/BambooModels.kt:9-20`

**What's wrong:** `BuildResultData.state` collapses Bamboo's `state` (Successful/Failed/Unknown) and `lifeCycleState` (Finished/NotBuilt/InProgress/Queued/Pending) into a single string via `state.ifBlank { lifeCycleState }` in `BambooServiceImpl`. This is **lossy**: `state="Unknown"` could mean *NotBuilt* (terminal — manual skip) OR *InProgress* (running). The Automation tab's `MonitorPanel`, `QueueService`, and `ConflictDetectorService` all need to know the difference. Per Bamboo probe `bundle-automation.unpacked/raw/result_recent.json:685-686`, NotBuilt + Unknown is the genuine terminal-but-no-result case. The plugin currently misclassifies it as still-running (see **A-P0-4**, **A-P0-5**) or as still-running-via-conflict (see **A-P2-1**).

**Hypothesised user-visible symptom:** stuck "Running" status on completed-but-not-built suites; conflict-detector false positives.

**Suggested fix sketch:** add `lifeCycleState: String = ""` field to `BuildResultData`. Populate from `dto.lifeCycleState` in `BambooServiceImpl.toBuildResultData()` (lines 552, 741, 957, 1012). Update consumers to check `lifeCycleState in {"Finished", "NotBuilt"}` for terminal, `lifeCycleState in {"InProgress", "Queued", "Pending"}` for active. This change touches `:core/model` and `:bamboo/service` but is tagged as "shared :core types" so within audit scope (per the user's "Do NOT touch any module outside ..." rule).

---

### A-P1-2 — `QueueStatusPanel` is decorative — no observer, no enable logic — Closed in 3079bf2c

**File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/QueueStatusPanel.kt`

**Excerpt:**

```kotlin
// QueueStatusPanel.kt:18-24
private val cancelButton = JButton("Cancel").apply {
    isEnabled = false                       // ← never re-enabled
    isFocusPainted = false
}
private val queueButton = JButton("Queue Run").apply { ... }
private val triggerButton = JButton("Trigger Now ▶").apply { ... }

// QueueStatusPanel.kt:43-46 — status bar always says "Suite Idle"
val statusBar = JPanel(FlowLayout(...)).apply {
    add(JBLabel("●").apply { foreground = JBColor.GRAY })
    add(JBLabel("Suite Idle").apply { foreground = StatusColors.SECONDARY_TEXT })
}
```

**What's wrong:** `QueueStatusPanel` declares a Cancel button + status bar but never observes `QueueService.stateFlow`. The Cancel button is permanently disabled (line 19). The status bar always says "Suite Idle". The Queue Run / Trigger Now buttons are duplicated from the parent `AutomationPanel` header (lines 110-117 of `AutomationPanel.kt`) — same actions, two places.

**Hypothesised user-visible symptom:** user queues a build (uses the parent header button), switches to Monitor tab, sees the QueueStatusPanel's "Suite Idle" label that never changes. Cancel button never enables. The Queue Run button in QueueStatusPanel works (callback wired at `AutomationPanel.kt:166-168`) but doesn't change anything visually because the status label is hardcoded.

**Suggested fix sketch:** subscribe `QueueStatusPanel` to `queueService.stateFlow` (passed in via constructor or fetched from `project.getService`); update the status label and cancel-button enable state on each emission. The status bar widget at `AutomationStatusBarWidgetFactory.kt:64-72` already does this correctly — copy that pattern. Remove the duplicate Queue Run / Trigger Now buttons (keep them in the parent header).

---

### A-P1-3 — `ConflictDetectorService` is dead code (no UI consumer) — Closed in 3079bf2c (deleted)

**File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/ConflictDetectorService.kt`

**What's wrong:** `ConflictDetectorService.checkConflicts` (the only public method) is **never called from production**. Tests reference it. `automation/CLAUDE.md` describes it as "detects conflicting tag selections" but no panel surfaces conflicts.

**Hypothesised user-visible symptom:** user trigger conflicts when other devs are running the same suite with different tags — there's no warning before sending.

**Suggested fix sketch:** wire `ConflictDetectorService.checkConflicts(currentSuitePlanKey, stagedTags)` into `AutomationPanel.onTriggerNow` and `onQueueRun` (lines 444, 474). If conflicts, show a Messages.YesNoCancelDialog with the conflict list before triggering. Or as a passive banner in the `tagStagingPanel`'s diagnostic strip.

---

### A-P1-4 — `TagHistoryService.saveHistory` / `getHistory` / `loadAsBaseline` are dead code — Closed in 3079bf2c (deleted; `automation_history` table + `HistoryEntry` model + `tagHistoryMaxEntries` setting removed)

**File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagHistoryService.kt:111, 131, 163`

**What's wrong:** the entire `automation_history` SQLite table (created at `initSchema` line 92-104) is never populated. `saveHistory` is unreferenced from production. The `tagHistoryMaxEntries` setting (`PluginSettings.kt:111`, surfaced in the Automation Configurable Advanced group at line 124-128) is dead — it controls a `LIMIT` on a table that's always empty.

**Hypothesised user-visible symptom:** the "Advanced" section in Automation Settings shows a `Tag history entries: [5]` field that does nothing.

**Suggested fix sketch:** EITHER: delete `saveHistory`, `getHistory`, `loadAsBaseline`, the `automation_history` table, the SQLite schema for it, the setting, the SettingsUI binding, the `HistoryEntry` model. Net deletion ~80 LOC. OR: wire `HandoverStateService.handleEvent(WorkflowEvent.AutomationFinished)` (line 113-120) to also call `tagHistoryService.saveHistory(...)` so a "previous runs" picker UI can be added later. Recommendation: delete (per Bamboo audit's dead-code policy).

---

### A-P1-5 — `TagHistoryService` SQLite connection leaks across IDE life — Closed in 3079bf2c

**File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagHistoryService.kt:39-58, 278-281`

**What's wrong:** `TagHistoryService` is a `@Service(Service.Level.PROJECT)` that opens a SQLite JDBC connection lazily (`connection by lazy`). It defines a `close()` method but is NOT `Disposable` — close is never invoked at project close. The `connectionInitialized` flag suggests intent to handle close, but there's no Disposer registration.

**Hypothesised user-visible symptom:** closing a project leaves the SQLite WAL file (`automation.db-wal`, `automation.db-shm`) on disk locked until JVM exit. Re-opening the same project in a new IDE window fails with `database is locked`. Also: long-running IDE sessions accumulate file handles.

**Suggested fix sketch:** make `TagHistoryService` implement `Disposable` and register itself with the project (`Disposer.register(project, this)` or use `@Service` lifecycle). In `dispose()`, call `close()`. (IntelliJ services with `Service.Level.PROJECT` already get a project-scoped Disposable lifecycle — just override `dispose()`.)

---

### A-P1-6 — `AutomationPanel.onSuiteSelected` race: stale invokeLater handler can fire after another suite is selected — Closed in 3079bf2c

**File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt:196-228`

**Excerpt:**

```kotlin
private fun onSuiteSelected(planKey: String) {
    currentSuitePlanKey = planKey
    currentBranchPlanKey = ""
    statusLabel.text = "Loading branches..."
    // ...
    scope.launch {
        val branchesResult = bambooService.getPlanBranches(planKey)   // ← can take seconds
        // ...
        invokeLater {
            // ← when this lands, currentSuitePlanKey may already be a NEWER suite
            branchCombo.removeAllItems()
            branchCombo.addItem(BranchComboItem(planKey, "default"))  // ← uses captured planKey, OK
            // ...
            onBranchSelected(branchCombo.getItemAt(0))                 // ← reads currentBranchPlanKey, OK
        }
    }
}
```

**What's wrong:** if the user selects suite A → fast-clicks to suite B before `getPlanBranches(A)` returns, the `invokeLater` block for A populates the branch combo with A's branches AND fires `onBranchSelected` for A's first branch. The user sees A's branches and tags despite having clicked B. Then B's `getPlanBranches` returns and overwrites correctly. The brief window of A-state shown for B is misleading; if the user starts modifying tags during that window, edits go to A's tag table that gets clobbered when B's data lands.

**Hypothesised user-visible symptom:** rapid suite-toggling shows incorrect intermediate state. With the user's "totally broken" framing, the fix isn't just about race conditions — but if the user clicks suites quickly while testing, this race contributes to perceived flakiness.

**Suggested fix sketch:** capture a per-launch token (`val token = ++loadGeneration`), and in the `invokeLater` block, early-return if `token != loadGeneration`. Or use `cancelAndJoin` on a Job-per-suite-selection field. Standard "latest-wins" pattern — analogous to `kotlinx.coroutines.flow.collectLatest`.

---

### A-P1-7 — `Automation` Trigger flow doesn't write its variables in Bamboo's documented form

**File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt:142-162`

**What's wrong (UNVERIFIED — flagged for re-probe):** Bamboo `/queue/{planKey}` endpoint per Atlassian's published docs accepts URL-form params named `bamboo.variable.NAME=VALUE`. The plugin sends a JSON body `{"variables": [{"name": ..., "value": ...}]}`. Some Bamboo versions accept both (community reports), but this isn't documented. The Bamboo probe (`docs/research/2026-05-07-bamboo-audit-recommendations.md` §2 — line 84) confirms the read-only probe never exercised this. **If the JSON form is silently ignored**, every plugin trigger defaults to plan-level variables only — not the staged docker tags.

**Hypothesised user-visible symptom:** Trigger Now appears to succeed (Bamboo returns 200 + a buildKey), but the queued build runs with default `dockerTagsAsJson` instead of the staged tags. Users see "wrong tags ran" in their automation results — without obvious error path.

**Suggested fix sketch:** capture `tools/atlassian-probe/probe_bamboo.py` to support `--probe-trigger` mode (POST a no-op trigger to a sandbox plan and inspect the resulting build's `variables` to verify which form Bamboo accepts). If the form-encoded form is required, change `triggerBuild` to send `"application/x-www-form-urlencoded"` with `bamboo.variable.${k}=${URLEncoder.encode(v, UTF_8)}` joined by `&`. Cross-reference: the Bamboo audit doc (§3 line 84) inventories `POST /queue/{planKey}` as "not exercised by the read-only probe" — so the right answer is to verify, not to assume. **This is in `:bamboo`, flagged here because it's the trigger path Automation uses.**

---

### A-P1-8 — `SuiteConfigPanel.persistVariables` silently no-ops when `getSuiteConfig` returns null — Closed in 3079bf2c

**File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/SuiteConfigPanel.kt:240-247`

**Excerpt:**

```kotlin
private fun persistVariables() {
    if (currentSuitePlanKey.isBlank()) return
    val vars = getVariables().toMutableMap()
    // ...
    val config = automationSettings.getSuiteConfig(currentSuitePlanKey)
    if (config != null) {                            // ← silent skip when null
        config.variables = vars.toMutableMap()
        config.lastModified = System.currentTimeMillis()
        automationSettings.saveSuiteConfig(config)
    }
}
```

**What's wrong:** if the user adds a suite via the Settings page (`AutomationConfigurable.addSuiteRow`) but never clicks Apply, the suite is in `suiteRows` but NOT in `automationSettings.state.suites`. Then on the Automation tab the user picks that suite (... but actually they can't, because `loadSuites()` reads from `automationSettings.getAllSuites()` — so this can't happen via that path). However: **suites whose `SuiteConfig` was wiped by A-P0-2** then re-saved without variables — the SuiteConfig DOES exist (line 416-422), so this null branch is not hit. Real risk is lower than initially flagged. **Downgrade to P1 because A-P0-2 already wipes the variables anyway.**

**Suggested fix sketch:** as part of fixing A-P0-2, ensure `persistVariables()` creates a fresh `SuiteConfig` if missing rather than silently skipping. (Defensive; no observed user impact today because A-P0-2 dominates.)

---

### H-P1-1 — `HandoverContextPanel.transitionButton` permanently disabled, transitionComboBox never populated

**File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/HandoverContextPanel.kt:41-45`

**What's wrong:** `transitionComboBox` is declared (line 41) and added to the layout (line 501), but no listener loads the Jira transitions for the active ticket. `transitionButton.isEnabled = false` (line 43-44 — "Coming soon"). Even if a user could click it, there's nothing to transition to.

**Suggested fix sketch:** subscribe to `WorkflowContextService.activeTicketFlow`; on each new ticket, fetch transitions via `JiraService.getTransitions(ticketKey)` (existing in `:core`/jira), populate combo box. Wire the button to call `JiraService.performTransition(...)`.

---

### H-P1-2 — `HandoverStateService` constructor is fine but `JiraClosureService` is a no-arg/`Project` mongrel — never registers as `@Service`

**File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/JiraClosureService.kt:13-22`

**What's wrong:** `JiraClosureService` declares `@Service(Service.Level.PROJECT)` AND has both a `constructor(project: Project)` AND a `constructor()` (no-arg). Per IntelliJ Platform docs, project-level services must have a `(Project)` or `(Project, CoroutineScope)` constructor — the no-arg constructor is a test-only artifact. Having both causes IntelliJ DI to pick one via signature scan, but the no-arg shouldn't be `@Service`-visible. Same anti-pattern in `CompletionMacroService.kt:19-23`, `CopyrightFixService.kt:23-28`, `PreReviewService.kt:18-22`, `QaClipboardService.kt:18-22`, `TimeTrackingService.kt:17-21`.

**Hypothesised user-visible symptom:** in production, the `(Project)` constructor wins. No observable user-visible bug — but tests construct via no-arg, which means they don't exercise the project-aware paths (e.g., `TimeTrackingService.getMaxHours` returns `DEFAULT_MAX_HOURS` instead of reading from settings — meaning the test never validates the settings-respecting path).

**Suggested fix sketch:** introduce a separate non-`@Service` test fixture class (or use mockk's `mockk<JiraClosureService>(relaxed=true)` against the service interface). Remove the no-arg constructor from the service classes. Also: `:handover` services don't take the platform-injected `cs: CoroutineScope` per Phase 4 conventions in `:core/CLAUDE.md` — but they don't launch coroutines either, so this is a stylistic concern not a bug.

---

### H-P1-3 — `:handover/CLAUDE.md` references `HandoverJiraClient` that doesn't exist

**File:** `handover/CLAUDE.md:8-9`

**Excerpt:**

```
- `JiraClosureService` — posts rich-text closure comment to Jira (docker tags + test results + links)
- `HandoverJiraClient` — Jira REST API client for comments and worklogs
```

**What's wrong:** `HandoverJiraClient` doesn't exist anywhere in the source tree (verified by `grep -rn HandoverJiraClient handover/src core/src jira/src`). The doc claim is aspirational. This is the missing puzzle piece for **H-P0-2** Post-Comment and TimeLog Log-Work buttons.

**Suggested fix sketch:** EITHER write the class (delegating to `:core`'s `JiraService.addComment` and `JiraService.addWorklog` if those exist — verify) OR remove the line from CLAUDE.md and consolidate Jira mutations through the existing `:core` `JiraService`.

---

### H-P1-4 — `:handover` services flag a workflow that doesn't exist

**File:** `handover/CLAUDE.md:25-27`

**Excerpt:**

```
No single "Complete Task" button. The real workflow is sequential:
PR creation -> Bamboo builds -> docker tags -> automation suites -> QA handover.
Each step depends on the previous one completing.
```

**What's wrong:** `CompletionMacroService` and `CompletionMacroPanel` exist, complete with macro-step orchestration logic (`executeMacro`, sequential failure handling, etc.). The `runButton` even says "Run Macro". But the doc says there's no "Complete Task" button. Either the doc is stale (the macro is the Complete Task button) OR the macro is dead code that should be deleted. Currently both are true: macro exists AND is dead code (see **H-P0-3**).

**Suggested fix sketch:** decide. Either wire the macro (preferred — the implementation is there, ~150 LOC, well-tested) and update the doc to "the Complete Task Macro panel orchestrates copyright → comment → transition → log work in sequence", OR delete the macro service and panel (~120 LOC).

---

### H-P1-5 — `JiraClosureService.escapeWikiMarkup` doesn't escape brackets and `[` will produce broken Jira link syntax

**File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/JiraClosureService.kt:74-75`

**Excerpt:**

```kotlin
private fun escapeWikiMarkup(text: String): String =
    text.replace("|", "\\|").replace("{", "\\{").replace("}", "\\}")
```

**What's wrong:** if `suite.suitePlanKey` ever contains `[` or `]`, Jira wiki interprets it as a link delimiter and the cell content becomes corrupted. The plugin only escapes `|`, `{`, `}`. Bamboo plan keys are typically `PROJ-AUTO` or `PROJ-AUTO0` (digits + letters + dash), which won't trigger this — but suite display names from `automation_history` could. Not exploitable today (since this service is dead code anyway), but the fix is one line.

**Suggested fix sketch:** add `.replace("[", "\\[").replace("]", "\\]")`. Also escape `*`, `_`, `+` for completeness. Or use a known Jira-wiki-escape helper from `:core` if one exists.

---

## 4. P2 bugs

### A-P2-1 — `ConflictDetectorService` `state == "InProgress"` is dead-but-correct-by-accident

**File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/ConflictDetectorService.kt:60`

**Excerpt:**

```kotlin
isRunning = build.state == "InProgress" || build.state == "Unknown"
```

**What's wrong:** `getRunningBuilds(planKey)` already filters by `lifeCycleState in {"InProgress", "Queued", "Pending"}` (`BambooApiClient.kt:168-170`). The `BambooServiceImpl.toBuildResultData` collapses to `state.ifBlank { lifeCycleState }`. For a real in-progress build: `state="Unknown"` (non-blank), so the merged state stays `"Unknown"`. The plugin's check on line 60 catches this via the `|| state == "Unknown"` half. The `state == "InProgress"` half is dead — there's no shape that produces `state="InProgress"` from a 200 response per the probe. Cosmetic.

**Suggested fix sketch:** remove `|| build.state == "InProgress"`, OR (better) when **A-P1-1** lands and `lifeCycleState` is exposed, switch to `lifeCycleState in ACTIVE_LIFECYCLE_STATES`.

---

### A-P2-2 — `TagBuilderService.semverPattern` matches pre-release tags as releases (scoring inflation) — Closed in 3079bf2c

**File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagBuilderService.kt:40, 104`

**Excerpt:**

```kotlin
private val semverPattern = Regex("""^\d+\.\d+\.\d+.*$""")
// ...
val releaseCount = tags.values.count { semverPattern.matches(it) }
```

**What's wrong:** the user said the goal is "fetch baseline from previous run where all/most docker tags are **release tags**". The regex matches `1.2.3-rc1`, `1.2.3-SNAPSHOT`, `1.2.3+build123` — all of which are *not* releases by typical convention. A baseline run where every tag is a `-rc1` looks identical to a run where every tag is a clean `1.2.3`. Score is inflated.

**Hypothesised user-visible symptom:** in environments using semver pre-release tags as a regular practice, the baseline picker selects the wrong build (the one with `-rc` tags wins ties over the clean release).

**Suggested fix sketch:** use a stricter regex `^\d+\.\d+\.\d+$` for "release" definition; treat pre-release with `.*$` suffix as a separate `prereleaseCount` and weight it lower (e.g., score = releaseCount*10 + prereleaseCount*3). Verify with the user's real release-tag conventions first — they may want pre-releases to count or not.

---

### A-P2-3 — Three duplicate `<base>/v2/{name}/manifests/{tag}` URL builders

**Files:**
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/api/DockerRegistryClient.kt:51`
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagValidationService.kt:103`
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/run/TagValidationBeforeRunProvider.kt:104` (`TagValidationLogic.buildManifestUrl`)

**What's wrong:** three independent callers build the same Docker manifest URL with copy-paste code. Per `:core/CLAUDE.md` "Reuse code" / `feedback_reuse_code.md`, this is a deletion candidate. Worse: a fix to **A-P0-1** has to touch all three — easy to miss one.

**Suggested fix sketch:** consolidate to a single helper in `core/util` (e.g., `DockerRegistryUrls.manifestUrl(base, basePath, name, tag)`). Replace all three call sites.

---

### A-P2-4 — `TagValidationBeforeRunProvider` reads `nexusUrl` directly instead of `dockerRegistryUrl`

**File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/run/TagValidationBeforeRunProvider.kt:47`

**Excerpt:**

```kotlin
val registryUrl = settings.connections.nexusUrl.orEmpty().trimEnd('/')
```

**What's wrong:** `QueueService.kt:54-55` and `DriftDetectorService.kt:26-28` use a fallback chain `dockerRegistryUrl → nexusUrl`. `TagValidationBeforeRunProvider` (the IntelliJ run-config Before-Run hook) only reads `nexusUrl`. If the user has separated their Docker registry URL from the Nexus API URL (which is what `dockerRegistryUrl` exists for), the run-config validation hook uses the wrong base URL.

**Suggested fix sketch:** add the same fallback chain. Pull into a shared helper as part of **A-P2-3** consolidation.

---

### A-P2-5 — `AutomationPanel.scope = CoroutineScope(Dispatchers.IO + SupervisorJob())` violates Phase 4 service-injected scope convention — Closed in 3079bf2c (verify-only: panel is `Disposable` and `dispose()` cancels the scope; non-`@Service` exception applies, no code change)

**File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt:37`

**What's wrong:** per `:core/CLAUDE.md` "Service & threading conventions", project-scoped services must use platform-injected `cs: CoroutineScope`. `AutomationPanel` is a `Disposable` panel (not a `@Service`), so the constraint is softer — but the platform offers `Disposer.register(panel, scope)`-aware scopes via `kotlinx.coroutines.toCoroutineScope` from a Disposable parent (or `service<*>().coroutineScope` if there's a service to anchor on). The current pattern allocates a fresh scope; cancellation works (`dispose() { scope.cancel() }`), so this is technically fine but stylistically non-conforming to the rest of the codebase.

Same in `MonitorPanel.kt:34`, `AutomationConfigurable.kt:65, 91`, `AutomationStatusBarWidgetFactory.kt:33`.

**Suggested fix sketch:** non-blocking; flag for the next platform-conformance sweep.

---

### A-P2-6 — Default `bambooBuildVariableName` casing is "DockerTagsAsJson" but probe shows live Bamboo plan-level variable as `DockerTagsAsJson` (capital) — verify — Closed in 3079bf2c (verify-only: every production read uses `equals(buildVariableName, ignoreCase = true)`; defaults are uniformly `DockerTagsAsJSON` matching the probe; no code change)

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt:112`

**What's wrong:** value is "DockerTagsAsJson" (capital D + capital T). Cross-reference with the Bamboo probe's `bundle-automation.unpacked/raw/result_variables.json` and `plan_variables_via_context.json` to see what the live plan variable name actually is. If it's lowercase-camelCase, the default is wrong and case-sensitive write paths (the trigger variable map → Bamboo) would silently fail to override the plan default.

Looking at the probe at `result_variables.json:217` (build-level variables with `name`/`value` shape), the visible variable names are `inject.GIT_COMMIT_SHA` etc — `DockerTagsAsJson` was not present in the probed automation suite (which makes sense; it's set by the trigger, not a stored plan variable). **Cannot confirm the canonical name from the probe**. Open question (§6).

---

### H-P2-1 — `HandoverContextPanel.dockerTagLabel` truncates at 50 chars with no overflow indicator

**File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/HandoverContextPanel.kt:421`

**Excerpt:**

```kotlin
dockerTagLabel.text = state.suiteResults.lastOrNull()?.dockerTagsJson?.take(50) ?: "No docker tags"
```

**What's wrong:** silently truncates at 50 chars. A typical `dockerTagsAsJson` is `{"auth":"2.4.0","payments":"2.3.1","user":"1.9.0"}` — 50+ chars trivially. The user sees an arbitrarily-cut JSON fragment with no `…` indicator and no full-text-on-hover. (Plus this is fed by `state.suiteResults.lastOrNull()` — only the last triggered suite's tags are shown, even though `HandoverState.suiteResults` aggregates all suites.)

**Suggested fix sketch:** parse the JSON, render as `"3 services" + tooltip with full text`, or use `<html>` with `…` overflow. Better: render N tag chips with key + truncated tag.

---

## 5. What was checked but is fine

- `DockerRegistryClient.parseWwwAuthenticate` — Bearer token challenge realm parsing + SSRF guard (`UrlSafetyGuard`, `sharesParentDomain`) — correct.
- `DockerRegistryClient.SemverComparator` and `getLatestReleaseTag` — sort logic correct, handles non-numeric segments via `takeWhile { it.isDigit() }.toIntOrNull()`.
- `DockerRegistryClient.listTags` pagination via Link header — correct.
- `DockerRegistryClient` token caching with `expiresIn * 800L` (80% TTL margin) — correct.
- `TagBuilderService.scoreAndRankRuns` baseline scoring math (line 107: `releaseCount*10 + successStages*5 - failedStages*20`) — correct, deterministic, and consistent with the user's "all release tags wins" intent (modulo **A-P2-2** pre-release matching).
- `TagBuilderService.extractDockerTagFromLog` — the `Unique Docker Tag :` regex + ANSI escape strip works on the test fixtures (`TagBuilderServiceTest.kt:296-308`); the post-`59c9ea8d` branch fallback in `BambooServiceImpl.getLatestBuild` makes this work for master-tracked branches.
- `TagBuilderService.replaceCurrentRepoTag` — correctly swaps tag for matching service, marks `isCurrentRepo = true`. Test-covered.
- `QueueService.enqueue` mutex + sequence counter + max-depth check — correct.
- `QueueService.cancel` Bamboo cancellation path (line 162: `bambooService.cancelBuild(resultKey)`) — correct.
- `AutomationStatusBarWidget.install` flow — observes `queueService.stateFlow` correctly, marshals updates to EDT via `Dispatchers.EDT`.
- `HandoverStateService` event handling — `BuildFinished`, `QualityGateResult`, `HealthCheckFinished`, `AutomationTriggered`, `AutomationFinished`, `PullRequestCreated`, `JiraCommentPosted` all handled correctly with proper state transitions. Tests cover this.
- `CopyrightFixService` year parsing/consolidation logic — well-tested, handles ranges (`2018-2023`) and lists (`2018, 2020, 2025`) correctly.
- `PreReviewService.parseFindings` regex + severity mapping — correct.
- `QaClipboardService.formatForClipboard` and `buildPayloadFromSuiteResults` — correct, handles malformed JSON gracefully.
- `TimeTrackingService` validation + ISO format math — correct.
- `CompletionMacroService.executeMacro` — sequential failure-skipping logic correct (just dead code per **H-P0-3**).
- The Bamboo audit's 16 new tests do NOT touch `:automation` or `:handover` — confirmed by `grep -rn ":automation\|:handover" automation/src/test handover/src/test`. No regressions from the recent commit.

## 6. Open questions

1. **Is the org's Nexus Docker registry path-based or port-based?** The Nexus probe at `Result_Nexus/bundle-discover-compressed.unpacked/raw/docker_v2_root.json` shows `/v2/` returns 404 HTML on the current Nexus host (`https://zo-zqau.sw.rfb.com/`) — strongly suggesting path-based. Confirms **A-P0-1**. Resolves with: user confirmation, or a probe that does `GET <nexus>/repository/<docker-repo>/v2/` and checks for 200 + empty body.

2. **What is the canonical `bambooBuildVariableName` on the user's Bamboo automation plans?** The probe didn't capture it (it's a runtime trigger-time variable, not a plan-level one). Resolves with: 1 line — user inspects their Bamboo plan UI and reports the variable name. Affects **A-P0-3** and **A-P2-6**.

3. **Does Bamboo 10.2 accept `triggerBuild`'s JSON variable shape `{"variables":[{"name":k,"value":v}]}`, or does it require `bamboo.variable.NAME=VALUE` form-encoded?** Affects **A-P1-7**. Resolves with: a one-shot live test against a no-op sandbox plan (probe driver extension).

4. **Is the documented `HandoverJiraClient` supposed to exist?** If yes, where; if no, the `:handover/CLAUDE.md` line should be removed. Affects **H-P1-3**. Resolves with: user intent.

5. **What's the user's expected behavior for the disabled `transitionButton`, `analyzeButton`, `fixAllButton`, `logButton`, `postButton`?** These are the seven "Coming soon" buttons. Each is a 1-3 day implementation. Resolves with: user prioritization for the implementation ticket.

6. **Should `automation_history` table + `tagHistoryMaxEntries` setting be deleted (no observed traffic) or wired to populate?** Affects **A-P1-4**. The Bamboo audit's policy was "delete dead fallbacks"; same applies here unless the user wants a "previous runs" picker UI. Resolves with: user intent on UI scope.

---

## 7. Cross-module reference table

For each bug, which modules might exhibit the same pattern.

| Bug | Pattern | Other modules at risk | Notes |
|---|---|---|---|
| A-P0-1, A-P2-3, A-P2-4 | Hard-coded `/v2/{...}` URL assuming port-based registry | `:bamboo` (no — it doesn't talk to Docker), `:agent` `DockerRegistryTool` if any (none found), `:handover` (no) | The Nexus probe shows path-based 404 — only `:automation` is exposed |
| A-P0-3, A-P2-6 | Default casing mismatch between PluginSettings default and hardcoded lookups | All readers of `bambooBuildVariableName` (4 files in `:automation`); `:agent` if a tool reads it (none found) | Single setting, multiple readers |
| A-P0-4 (Item 1) | Off-EDT read/write of Swing models | Search across all panels with `SmartPoller` or background `scope.launch` updating model | `:bamboo BuildDashboardPanel` polling, `:pullrequest PrDashboardPanel`, `:sonar` panels |
| A-P0-4 (Item 2), A-P0-5, A-P1-1, A-P2-1 | Bamboo `state` vs `lifeCycleState` confusion (lossy `state.ifBlank{lifeCycleState}` mapping) | `:bamboo` (`BuildDashboardPanel`, `BuildMonitorService`), `:handover HandoverContextPanel.buildStatusLabel` (line 403 — uses `state.buildStatus.status` which is an enum, not the raw string, so safer) | Address by extending `BuildResultData` in `:core` |
| A-P0-2, A-P1-8 | `state.suites.clear() + saveSuiteConfig` blast pattern in IntelliJ Configurable.apply() | Other Configurables that mutate `PersistentStateComponent` collections — audit `JiraConfigurable`, `BambooConfigurable`, `PullRequestConfigurable`, etc. | Fixed once in `AutomationConfigurable`, the pattern bug-pattern check should sweep all Configurables |
| A-P1-3, A-P1-4, H-P0-3, H-P1-4 | Service implemented + tested, never wired to UI | All recent feature modules — sweep `grep` for `class .*Service` + cross-check call sites | High frequency in this codebase per audit findings |
| A-P1-5 | `@Service(PROJECT)` opens JDBC connection, never `Disposable` | `:agent SessionStorageService` opens JSON files (closed via Disposer), `:agent CheckpointService` (similar) — verify | Generic resource-leak audit candidate |
| A-P1-6 | "Latest-wins" race in invokeLater after coroutine | `:bamboo BuildDashboardPanel.onPlanSelected`, `:pullrequest PrDashboardPanel.onPrSelected`, `:jira CurrentWorkSection.onTicketSelected` | Apply `++loadGeneration` token pattern across UIs |
| A-P1-7 | Mutating Bamboo endpoint with unverified body shape | `BambooApiClient.cancelBuild`, `stopBuild`, `rerunFailedJobs` — none verified by the read-only probe | Single audit pass, multi-method fix |
| H-P1-2 | `(Project)` + `()` mongrel constructors on `@Service` classes | All 7 `:handover` services, also some `:automation` services — confirm: `TagBuilderService(bambooService, ...)`, `ConflictDetectorService(bambooService, ...)` etc. — these are tagged "Test constructor" | Doc says it's intentional for test injection; arguably accepted pattern |
| H-P0-1 | UI panel setters defined but never called | All 6 sub-panels in `:handover`. `:pullrequest` and others may have similar dead UI surfaces — not audited here | Largest single category in this audit |

---

## Probe artifacts referenced

- `tools/atlassian-probe/Result_Bamboo/bundle-{automation,repo}.unpacked/raw/result_recent.json` — for `state="Unknown"` / `lifeCycleState="NotBuilt"` evidence (lines 685-686)
- `tools/atlassian-probe/Result_Bamboo/bundle-automation.unpacked/raw/result_variables.json` — confirms build-level `variables.variable[]` uses `name`/`value` shape (canonical reader path for `dockerTagsAsJson`)
- `tools/atlassian-probe/Result_Nexus/bundle-discover-compressed.unpacked/raw/docker_v2_root.json` — confirms `/v2/` → 404 HTML on this Nexus install (path-based deployment evidence)
- `tools/atlassian-probe/Result_Nexus/bundle-discover-compressed.unpacked/summary.md` — Nexus 3.90.1-01 PRO; `/v2/` 404; full repo path required
- `docs/research/2026-05-07-bamboo-audit-recommendations.md` — Bamboo 10.2.14 endpoint inventory; trigger endpoint inventoried but not exercised (§3 line 84)
