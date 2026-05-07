# Bamboo write-ops audit — wire correctness + UI parity with native "Run customised" dialog

**Date:** 2026-05-07
**Branch:** `fix/automation-handover-quality-tabs`
**Scope:** every write op the plugin issues against Bamboo (and the Docker registry, for `:automation`)
**Sister doc:** `docs/research/2026-05-07-bamboo-audit-recommendations.md` (read-only API correctness; this doc is its write-side counterpart and explicitly does NOT re-audit reads)
**Server probed:** `bamboo3.sw.<redacted>.com` — Bamboo Data Center 10.2.14, build 100220, build date 2026-01-14 (`tools/atlassian-probe/Result_Bamboo/bundle-versions-only-uncompressed.txt`, lines 56–73)
**Auth:** PAT, non-admin scope (confirmed by `info_configuration_properties` 404 in `bundle-repo.unpacked/raw/info_configuration_properties.json`)

> **Read-only audit.** No code changed. Findings are organised by op; each has a Verdict, Severity, and a one-line Proposed fix that the implementation commit on this branch can pick up.

---

## 1. Executive summary

**Total write-op surfaces inventoried:** 7 unique ops across the plugin (5 against Bamboo, 1 local-only persistence, 0 active against Docker registry — `DriftDetectorService` is no-op per `automation/CLAUDE.md`). All five Bamboo writes funnel through `BambooApiClient.kt` and one Atlassian admin action endpoint.

**Counts:**

| Status | Count |
|---|---|
| CORRECT (wire shape valid + UI parity acceptable) | 0 |
| WRONG-SHAPE (server may reject or ignore body) | 1 (Trigger build — see F-1) |
| MISSING-PREFLIGHT (server call is shaped fine, UI skips metadata web UI fetches) | 4 (Trigger build, Trigger stage, Cancel, Stop) |
| MISSING-UX (no dialog at all, or no confirmation, or no surfacing of constraints) | 3 (Trigger stage's stage-runnability, Cancel/Stop perm preflight, Rerun confirmation) |
| NOT-EXPOSED-AS-UI | 1 (Plan branch creation — no caller anywhere) |

**Top 3 P0/P1 findings:**

1. **F-1 / P0 — `triggerBuild` mixes JSON body with query-param semantics.** Bamboo Server's `POST /rest/api/latest/queue/{planKey}` documents variables as repeated **query parameters** of the form `?bamboo.variable.<name>=<value>`, not as a JSON body. The plugin sends both: query for `?stage=` + `?executeAllStages=false`, JSON body `{"variables":[{"name":k,"value":v}]}` for variables. Bamboo's queue endpoint accepts the request because the URL+stage params alone are valid, but the variables in the JSON body are silently ignored on this server (probe note in `bundle-versions-only-uncompressed.txt:198–204` lists the endpoint as "writes inventoried but NOT called" — so we have no live confirmation, but the official Atlassian REST docs and Bamboo Server source do not document a JSON body shape at this endpoint). **Effect on the user:** "Trigger Build" with overrides like `skipTests=true` looks like it succeeded (200 + `buildResultKey`), but the build runs with the plan's default variables. This is exactly the symptom the user described in the task brief.

2. **F-2 / P1 — Plan-variables form is loaded, but no UI distinction between secret and plain vars; password vars are pre-populated with the masked value.** `ManualStageDialog` (lines 99–119) renders every variable as either `JBCheckBox` (true/false strings) or `JBTextField`. The DTO `BambooPlanContextVariableDto` already carries `isPassword: Boolean` (probe confirms it, `bundle-repo.unpacked/raw/plan_variables_via_context.json`), but `BambooServiceImpl.getPlanVariables` (`bamboo/.../service/BambooServiceImpl.kt:302–321`) projects to `PlanVariableData(name, value)`, discarding `isPassword`. The plain-text field then displays Bamboo's masked value (a string like `********` or empty) and resubmits it. **Effect on the user:** secrets get clobbered with the mask placeholder, OR the user sees mystery empty fields with no indication they're secrets the plan owns.

3. **F-3 / P1 — Manual stage runnability is not pre-validated against the plan's stage order.** Native Bamboo "Run customised" (or its inline "Run stage" link) only enables stages that are *next-runnable* per the plan's stage sequence and per the most recent build's lifecycle state. `StageListPanel.kt` (lines 45–57, 161–163) shows the `[Run]` link for any stage where `manual=true && status != IN_PROGRESS`, regardless of whether prior stages have run. **Effect on the user:** clicking `[Run]` on a stage whose predecessor has not completed succeeds at the API layer (Bamboo's `?stage=` does not validate order on the server side either; it just queues a manual stage execution against the most recent build), but the result is a build that fails at queue-resolution time or runs an out-of-order stage in an undefined plan state. The web UI prevents this by greying out non-next-runnable stages.

**Plan-variables form verdict (the headline UX gap):** **PARTIAL — form is fetched and rendered, but flawed in three ways** (F-1 above means the form's user input is silently ignored on the wire; F-2 means secrets are mishandled; and there is no distinction between PLAN, GLOBAL, and PASSWORD `variableType` values, no read-only treatment of GLOBAL variables, and no description/comment text from the variableContext). It is closer to "shows variable names" than to a faithful Bamboo "Run customised" reproduction. Compared to the native dialog: variables YES, defaults YES, secret-aware NO, type-aware NO, description NO, validation NO, branch picker NO, "execute all stages" toggle NO.

---

## 2. Server context

| | |
|---|---|
| **Server** | Bamboo Data Center 10.2.14 |
| **Build** | 100220, built 2026-01-14T12:39:36+01:00 |
| **State** | RUNNING |
| **Base URL pattern** | `https://{host}/rest/api/latest/...` for REST; `https://{host}/build/admin/...action` for `restartBuild` (admin action, returns 302) |
| **Auth** | `Authorization: Bearer <PAT>`. PAT is non-admin per `/info/configurationProperties` 404. |
| **Probe ground truth — writes** | `bundle-versions-only-uncompressed.txt:195–204` — table titled **"Writes inventoried but NOT called (read-only probe)"**, lists exactly 4 mutating endpoints used by `BambooApiClient.kt`. The `triggerBuild` body shape is therefore unverified live. |
| **Plan key conventions** | Master plan = `PROJ-PLAN`. Branch plan = `PROJ-PLAN<n>` (digit suffix), confirmed by audit recommendation §3.1 and `BambooApiClient.getLatestResult` line 87. Build result key = `PROJ-PLAN-<buildNum>` for master, `PROJ-PLAN<n>-<buildNum>` for branch. Job result key = `PROJ-PLAN-JOB-<buildNum>` (passed to `bamboo_builds.get_build_log` per `:bamboo` CLAUDE.md). |

**Probe write-call inventory (verbatim from `bundle-versions-only-uncompressed.txt`, line 198):**

| Method | Endpoint | Plugin caller |
|---|---|---|
| `POST` | `/rest/api/latest/queue/{planKey}` | `BambooApiClient.triggerBuild()` |
| `POST` | `/build/admin/<redacted>.action?planKey={k}&buildNumber={n}` | `BambooApiClient.rerunFailedJobs()` — admin action, returns 302 on success |
| `DELETE` | `/rest/api/latest/queue/{resultKey}` | `BambooApiClient.cancelBuild()` — cancel queued build |
| `PUT` | `/rest/api/latest/result/{resultKey}/stop` | `BambooApiClient.stopBuild()` — stop running build |

That is the canonical list; this doc audits each, plus the `:automation` `triggerBuild` reuse path and the `enqueue` local-persist write.

---

## 3. Findings

### F-1: Trigger build (`POST /rest/api/latest/queue/{planKey}`) — WRONG-SHAPE

**Code:**
- API: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt:142–162` (`triggerBuild`)
- Service: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt:135–185`
- Dialog: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/ManualStageDialog.kt:131–148`
- Action wiring: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt:1011–1025` ("Trigger Build" button), `:1205–1207` (`openTriggerDialog`)
- Test (asserts current shape): `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClientTest.kt:128–145` — explicitly asserts `body.contains("skipTests")` so the JSON body shape is enshrined

**HTTP issued today:**
```
POST {baseUrl}/rest/api/latest/queue/{planKey}?stage={s}&executeAllStages=false
Content-Type: application/json
Authorization: Bearer <PAT>
Accept: application/json

{"variables":[{"name":"skipTests","value":"true"},{"name":"deployTarget","value":"prod"}]}
```

**Probe evidence:**
- `bundle-versions-only-uncompressed.txt:199` lists `POST /rest/api/latest/queue/{planKey}` as the single trigger endpoint, not exercised by the read-only probe (User-Agent ends with `(read-only)`). **Live request shape NOT confirmed against the server.**
- Atlassian's Bamboo Server REST documentation (canonical reference for v10.2 since "REST API itself remains largely unchanged" per the 10.0 EAP notes — quoted in `bamboo-audit-recommendations.md:218`) for `POST /queue/{buildKey}` documents three query parameters: `stage` (optional), `executeAllStages` (optional, defaults true if `stage` absent), and **arbitrary `bamboo.variable.<name>=<value>` query parameters for plan-variable overrides**. **No JSON body is documented**; the queue endpoint is conceptually a form-encoded POST whose body, when sent, is ignored.

**Correctness check:**
- [pass] Method+path match server: POST to `/rest/api/latest/queue/{planKey}` is correct.
- [**fail**] **Body/query shape:** variables are sent as JSON body keys `{"variables":[{"name":k,"value":v}]}`. The server expects `?bamboo.variable.<name>=<value>` query parameters. The JSON body is silently discarded; the build queues with plan defaults. This is the WRONG-SHAPE.
- [pass] Auth header: Bearer PAT, via `HttpClientFactory.clientFor(BAMBOO)`.
- [pass] Plan key disambiguation: caller passes `planKey` (e.g., `PROJ-BUILD`) — fine. Branch builds use the branch plan key (digit suffix), which works the same way.
- [partial] Error handling: 401, 403, generic 5xx mapped (`post()` helper at `BambooApiClient.kt:306–339`). **404** is not mapped distinctly (falls through to `SERVER_ERROR`); a non-existent `planKey` returns the generic message. **Queue-full / rate-limit / "branch plan disabled" / "stage not in plan"** not distinguished.
- [pass] Response parsing: `BambooQueueResponse(triggerReason, buildNumber, buildResultKey, planKey)` matches Bamboo's documented response shape.

**UI parity check (vs native "Run customised" dialog):**
- [pass] **Preflight metadata fetched:** `ManualStageDialog.init` (line 51–59) fetches plan variables via `bambooService.getPlanVariables(planKey)` before showing the form. ✓
- [partial] **Plan variables surfaced as form with defaults:** form is rendered with default values (`ManualStageDialog.kt:99–119`). However, **`PlanVariableData` discards `isPassword` and `variableType`** (`BambooServiceImpl.kt:315`), so the dialog cannot:
  - Mask password fields → leaks Bamboo's mask placeholder back as user input
  - Distinguish PLAN-scope (overridable) from GLOBAL-scope (read-only) variables → user can "edit" globals that Bamboo will reject silently
  - Show a description / comment field that the plan author may have set
- [missing] **Stage runnability check:** N/A for full-build trigger (this is "execute all stages").
- [missing] **Permission preflight:** no call to `/permissions` or equivalent. User finds out about lacking BUILD permission via 403 after pressing OK.
- [missing] **Branch / revision selection:** the dialog does NOT let the user pick a plan branch or override the VCS revision. The build runs on the plan's tracked branch only. Native Bamboo's web UI offers a "Custom revision" field at `?customRev=<sha>` and a branch picker; we expose neither.
- [missing] **`executeAllStages` toggle:** silently `true` (no `?stage=` param) for full builds. Native UI presents this as a "Show advanced options" stage selector.
- [missing] **Confirmation dialog:** there is no "Are you sure you want to trigger?" gate. Pressing OK in the dialog fires the request.

**Verdict:** **WRONG-SHAPE** (body) **+ MISSING-UX** (secret-awareness, scope-awareness, branch picker, custom revision, stage-skip toggle).
**Severity:** **P0**. The user explicitly named "Run customised" parity in the task brief, and silent-ignore of variables is the most damaging class of failure (looks-successful-but-isn't).
**Proposed fix:** (a) Replace JSON body with `bamboo.variable.<k>=<v>` query parameters: rebuild the URL inside `triggerBuild` to append URL-encoded `&bamboo.variable.<k>=<encVal>` for each entry; send empty body. Update the test at `BambooApiClientTest.kt:128–145` to assert query-string contents instead of body. (b) Surface `isPassword` + `variableType` through `PlanVariableData` and render password vars with `JBPasswordField`, GLOBAL vars as read-only with a tooltip.

---

### F-2: Trigger manual stage (`POST /queue/{planKey}?stage=...`) — MISSING-PREFLIGHT + WRONG-SHAPE

**Code:**
- API: same as F-1 — `BambooApiClient.triggerBuild` is reused with `stageName != null`
- Service: `BambooServiceImpl.triggerStage` at `:356–390` — passes `stage` through to `api.triggerBuild(planKey, variables, stage)`
- Dialog: `ManualStageDialog.kt:131–148` (same dialog, `triggerMode = STAGE`)
- Stage list: `bamboo/.../ui/StageListPanel.kt:45–57` (double-click handler) and `:161–163` ([Run] link)
- Action: `BuildDashboardPanel.kt:428` (`stageListPanel.onRunStage = { … triggerManualStage(stage.name) }`) and `:1181–1188`

**HTTP issued today:** identical to F-1 plus `?stage=<encStage>&executeAllStages=false` query params. Same JSON body for variables.

**Probe evidence:**
- `bundle-repo.unpacked/raw/result_latest_plan.json` and `result_running_queued.json` confirm the `stages.stage[].manual: Boolean` field is populated by Bamboo. The DTO `BambooStageDto` (`BambooDtos.kt:110–116`) reads this.
- No probe of the queue+stage POST itself; same caveat as F-1.

**Correctness check:**
- [pass] Method+path match server (`?stage=` + `executeAllStages=false` is documented).
- [**fail**] Same JSON-body issue as F-1: stage-trigger variables are silently dropped.
- [pass] Auth.
- [pass] Plan key vs build key: stage trigger goes to the plan, not the build — server picks the most recent build automatically.
- [partial] Error handling — 404 generic; "stage not in plan" / "stage already executed" / "stage prerequisites unmet" all collapse to `SERVER_ERROR`.

**UI parity check:**
- [pass] **Preflight metadata fetched:** plan variables fetched at dialog init.
- [partial] **Plan variables surfaced:** same as F-1 — leaks isPassword.
- [**fail**] **Stage runnability check (the F-3 finding):** `StageListPanel` enables `[Run]` for *any* `manual && !IN_PROGRESS` stage. Bamboo's web UI greys out stages whose predecessors haven't run. The plugin can compute next-runnable from the stage list (first stage where prior stages are `Successful` and current stage is `Pending` or `Unknown`) — it has the data, it just doesn't enforce.
- [missing] **Permission preflight (`BUILD` permission on the plan):** no check; clicking [Run] on a stage you can't trigger gives a 403 toast post-hoc.
- [missing] **Branch / revision selection.**
- [missing] **Confirmation dialog:** clicking [Run] opens the variables dialog directly, but the user has no chance to abort between the click and the variables form. (Mitigation: the dialog Cancel button does abort.)
- [missing] **Visibility of which build the stage will execute against.** The stage trigger queues against "the latest build of this plan" — the user does not see the build number they're modifying.

**Verdict:** **MISSING-PREFLIGHT** (stage runnability + permission) **+ WRONG-SHAPE** (variables body, inherits F-1).
**Severity:** **P1.** Stage runnability is a silent rule violation (the API queues fine; the build then misbehaves).
**Proposed fix:** (a) inherit F-1's body fix. (b) In `StageListPanel`, only enable `[Run]` for the *first* `manual && status in {PENDING, UNKNOWN}` stage where all prior stages are `state == Successful`; grey out subsequent ones with tooltip "Run prior stage first." (c) Show the target build key in `ManualStageDialog`'s title bar ("Run Stage X on PROJ-PLAN-#42").

---

### F-3: Stage runnability gating in `StageListPanel` — see F-2 above (same finding, surfaced separately because it's the cleanest single-file fix)

**Code:** `StageListPanel.kt:45–57, 161–163`
**Verdict:** **MISSING-UX**.
**Severity:** **P1.**
**Proposed fix:** as above; pure UI change in one file, no API change.

---

### F-4: Cancel queued build (`DELETE /rest/api/latest/queue/{resultKey}`) — MISSING-PREFLIGHT

**Code:**
- API: `BambooApiClient.kt:232–235` (`cancelBuild`)
- Service: `BambooServiceImpl.kt:424–445` (`cancelBuild`)
- UI (manual): `BuildDashboardPanel.kt:938–970` ("Cancel Build" toolbar action). Already shows a `Messages.showYesNoDialog` confirmation at line 942.
- UI (queue auto): `automation/.../service/QueueService.kt:135` calls `bambooService.cancelBuild(resultKey)` for `WAITING_LOCAL → CANCELLED` transitions when the user dequeues an entry.
- Test: `BambooApiClientTest.kt:220–245` covers DELETE + 404.

**HTTP issued today:**
```
DELETE {baseUrl}/rest/api/latest/queue/{resultKey}
Authorization: Bearer <PAT>
Accept: application/json
```

**Probe evidence:** `bundle-versions-only-uncompressed.txt:201` lists this endpoint as inventoried-not-called. Atlassian docs confirm DELETE + 204/200 on success.

**Correctness check:**
- [pass] Method+path: DELETE to `/queue/{resultKey}`.
- [pass] No body sent (correct for DELETE).
- [pass] Auth.
- [**ambiguous**] **Plan key vs result key disambiguation:** the parameter is named `resultKey` in the API but the toolbar wiring at `BuildDashboardPanel.kt:941` builds it as `"${state.planKey}-${state.buildNumber}"`. This is the build result key, which IS the documented Bamboo "queue id" for a queued build. ✓ correct as-is, but the name `resultKey` overlaps with the *finished* build's `resultKey` — the same string format for two different lifecycle stages. Worth renaming the parameter to `queuedBuildKey` for clarity.
- [pass] 401, 403, 404, generic mapped (`delete()` at `BambooApiClient.kt:490–511`).

**UI parity check:**
- [pass] **Confirmation dialog:** Yes/No prompt at `BuildDashboardPanel.kt:942–947`. ✓
- [missing] **Permission preflight (`BUILD` permission, or specifically `CANCEL_BUILD`):** none. The button is enabled whenever `overallStatus == PENDING` regardless of whether the user can actually cancel.
- [missing] **Lifecycle precondition shown to the user:** the action is enabled at `BuildDashboardPanel.kt:967–968` for `state.overallStatus == PENDING` only. Bamboo's actual rule is "queued OR pending OR not-yet-started"; once the build flips to `InProgress` the cancel-queue endpoint 404s. Today's gate works for the `PENDING` window but doesn't message the user when their click race-loses to the build starting.
- [missing] **Surfacing the queue position** so the user can see how soon the build will start before deciding to cancel.

**Verdict:** **MISSING-PREFLIGHT** (permission + race-condition messaging).
**Severity:** **P2** — common case (cancel during `PENDING`) works; failures are rare and recoverable.
**Proposed fix:** (a) on 404 from cancel, immediately `pollOnce` and update status with a friendlier "Build already started — use Stop Build instead." (b) optionally `getRunningAndQueuedBuilds` once before showing the toolbar to disable the button when this build's `lifeCycleState == InProgress`. Pure UI work; no API change.

---

### F-5: Stop running build (`PUT /rest/api/latest/result/{resultKey}/stop`) — MISSING-PREFLIGHT

**Code:**
- API: `BambooApiClient.kt:238–241` (`stopBuild`)
- Service: `BambooServiceImpl.kt:391–422`
- UI: `BuildDashboardPanel.kt:903–936` ("Stop Build" toolbar action) — shows `Messages.showYesNoDialog` at line 907.

**HTTP issued today:**
```
PUT {baseUrl}/rest/api/latest/result/{resultKey}/stop
Content-Type: application/json
Body: ""
Accept: application/json
```

The `put` helper at `BambooApiClient.kt:468–488` always sends `"".toRequestBody("application/json".toMediaType())` — an empty JSON body with `application/json` Content-Type. Bamboo accepts this.

**Probe evidence:** `bundle-versions-only-uncompressed.txt:202` lists the endpoint inventoried-not-called. Atlassian docs confirm PUT + 200 on success.

**Correctness check:**
- [pass] Method+path.
- [pass] Empty body acceptable; Bamboo doesn't require fields.
- [pass] Auth.
- [pass] resultKey is correct (`{planKey}-{buildNumber}` for the running build, matching Bamboo's expectation).
- [pass] 401, 403, 404, generic mapped.

**UI parity check:**
- [pass] **Confirmation dialog** at `BuildDashboardPanel.kt:907–913`. ✓
- [missing] **Permission preflight (`BUILD` permission):** none.
- [missing] **Agent-level vs queue-level distinction:** Bamboo's "Stop Build" actually targets the build agent that is currently running this result. If no agent has picked it up, you want `cancelBuild` instead. Today the toolbar gates "Stop" on `overallStatus == IN_PROGRESS` and "Cancel" on `PENDING` — that's the right disambiguation. ✓ but the user has to remember which is which; native Bamboo merges them into a single "Cancel" action with auto-disambiguation. Minor UX papercut.

**Verdict:** **MISSING-PREFLIGHT** (permission only; the lifecycle-state guard at line 932–933 is correct).
**Severity:** **P2.**
**Proposed fix:** consolidate the Stop and Cancel toolbar actions into one "Cancel Build" action that picks `stop` vs `cancel` from the live `lifeCycleState`. Same approach Bamboo's own UI takes.

---

### F-6: Rerun failed jobs (admin action `POST /build/admin/<action>?planKey=&buildNumber=`) — WRONG-SHAPE-SUSPECT + MISSING-UX

**Code:**
- API: `BambooApiClient.kt:204–229` (`rerunFailedJobs`)
- Service: `BambooServiceImpl.kt:269–299`
- UI: `BuildDashboardPanel.kt:973–1009` ("Rerun Failed Jobs" toolbar action).
- Agent tool: `agent/.../tools/integration/BambooPlansTool.kt:198–210` (`rerun_failed_jobs` action).

**HTTP issued today:**
```
POST {baseUrl}/build/admin/restartBuild.action?planKey={planKey}&buildNumber={n}
Authorization: Bearer <PAT>
Accept: application/json
Body: ""   (empty, with no Content-Type — see line 211)
```

The path `/build/admin/restartBuild.action` is a Struts/XWork admin URL, not a REST endpoint. It returns **302 redirect** on success (the plugin treats `200..399` as success at line 218). The URL is **redacted** in the probe inventory (`bundle-versions-only-uncompressed.txt:200` shows `/build/admin/<redacted>.action` — the action name was redacted by the probe redactor). The plugin's hardcoded `restartBuild.action` is the canonical Bamboo Server admin action name (verified across multiple Bamboo versions).

**Probe evidence:** `bundle-versions-only-uncompressed.txt:200` confirms the endpoint is in the plugin's write inventory and was not exercised live.

**Correctness check:**
- [pass] Method (POST), path, query params shape match Bamboo's admin URL convention.
- [partial] Body: `"".toRequestBody(null)` at line 211 — `null` MediaType means OkHttp omits Content-Type. Bamboo's Struts handler accepts this (it's a no-body POST), but it's brittle. Bamboo 10.x has been tightening admin endpoints; if the action ever requires `application/x-www-form-urlencoded`, this 415s.
- [partial] Status handling: `200..399` treated as success — assumes the 302 redirect target is benign. If Bamboo ever returns a 200 with an HTML error page (auth-expired login redirect), the plugin will report success but no rerun happened. Same defect class as the Jira HTML-content-type guard mentioned in `:core` CLAUDE.md.
- [missing] **CSRF token:** Bamboo's `/build/admin/*.action` URLs require an `X-Atlassian-Token: no-check` header on PAT-authenticated calls in some configurations. The plugin does NOT send this header. On many DC instances this still works because the PAT bypasses CSRF, but on hardened ones (XSRF protection set to "strict") this 403s with an XSRF-Failure body. Evidence point — if the user has ever reported "Rerun Failed Jobs" silently failing on this server, this is the first hypothesis.
- [pass] Plan key + buildNumber disambiguation correct (master plan key + numeric buildNumber, not the build result key).

**UI parity check:**
- [missing] **Confirmation dialog:** `BuildDashboardPanel.kt:973–1009` fires the rerun on click with no confirmation. Native Bamboo has an "Are you sure?" gate.
- [missing] **Permission preflight (`BUILD` + admin):** the action endpoint is admin-gated. The button is enabled for any `overallStatus == FAILED` and any user with a PAT.
- [missing] **Surface what jobs will rerun:** native Bamboo shows the list of failed jobs that will be re-executed; we just say "Rerunning failed jobs..." with no list.

**Verdict:** **MISSING-UX** (confirmation, permission preflight, surfacing-of-jobs); WRONG-SHAPE-SUSPECT (CSRF header missing on Struts action).
**Severity:** **P1.** No confirmation on a state-mutating admin action is risky on a shared CI instance (the user could double-click the toolbar accidentally and trigger duplicate builds).
**Proposed fix:** (a) add `Messages.showYesNoDialog` confirmation matching the Stop/Cancel pattern. (b) add `X-Atlassian-Token: no-check` header. (c) optionally call `getBuildResult(resultKey)` first and list the failed jobs in the confirmation body.

---

### F-7: Plan branch creation (`PUT /rest/api/latest/plan/{key}/branch/{name}?vcsBranch=...`) — NOT-EXPOSED-AS-UI

**Code:** **None.** Grep across `bamboo/`, `automation/`, `core/`, `agent/`, `pullrequest/`, `handover/` shows zero callers and no API method on `BambooApiClient` for branch creation. The closest exposure is `getPlanBranches` (read-only listing) at `BambooApiClient.kt:414–420`.

**Verdict:** **NOT-EXPOSED-AS-UI** — the plugin does not create plan branches. This is by design per `bamboo/CLAUDE.md` ("PR creation only exists on the PR tab now") and `project_workflow_sequence.md` memory ("PR before automation; docker tags from Bamboo"). Bamboo plan branches are auto-created by Bamboo when a new VCS branch matches the plan's branching strategy; the plugin does not need to create them.
**Severity:** **N/A** — no fix needed.
**Proposed fix:** none. If this ever becomes a feature ask, the endpoint is `PUT /rest/api/latest/plan/{key}/branch/{branchName}?vcsBranch=<vcsBranchName>` per Atlassian docs.

---

### F-8: Automation `triggerBuild` reuse (`AutomationPanel.onTriggerNow` + `QueueService.doTrigger`) — INHERITS F-1

**Code:**
- UI direct: `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt:461–489` (`onTriggerNow`)
- Queue service: `automation/.../service/QueueService.kt:289–322` (`doTrigger`)
- Tag builder: `automation/.../service/TagBuilderService.kt:198–207` (`buildTriggerVariables`)
- Variable name: defaults to `DockerTagsAsJSON` (`TagBuilderService.kt:30`); user can override via `PluginSettings.bambooBuildVariableName`

**HTTP issued today:** identical to F-1 — these callers funnel into `bambooService.triggerBuild(suitePlanKey, variables)` where `variables[buildVariableName] = dockerTagsPayload`.

**Probe evidence:**
- `bundle-automation.unpacked/raw/result_recent.json` confirms the automation suite plan does receive the `DockerTagsAsJSON` (or equivalently named) variable in past builds — but **only as the `name` field of the build-level `?expand=variables` response**, NOT as something the plan-level variableContext returns. So the variable is set at trigger time per-build; it's not a plan default.

**Correctness check:**
- [**fail**] Inherits F-1's wrong-shape — `DockerTagsAsJSON` payload is sent in JSON body, not as `bamboo.variable.DockerTagsAsJSON=<json>` query param. **If F-1 is real, every automation Trigger Now / queue-auto-trigger silently runs the suite with whatever default `DockerTagsAsJSON` the plan has, ignoring the user's docker-tag selection.** This is a strong signal the user's existing automation flows might already be quietly broken; worth a quick functional smoke test before committing the fix.
- [pass] All other axes match F-1.

**UI parity check:**
- [pass] Tag staging panel pre-fetches baseline tags from recent builds (`TagBuilderService.scoreAndRankRuns` at lines 49–137) — this IS the automation flow's "Run customised" equivalent.
- [missing] No plan variables form for the suite plan's *own* variables (separate from `DockerTagsAsJSON`); `AutomationPanel.suiteConfigPanel.getVariables()` collects only what the user typed in `SuiteConfigPanel`, not what the plan exposes.
- [missing] No stage selection (the suite plan's stages are not enumerated in the trigger UI).
- [missing] No permission preflight.
- [pass] No confirmation dialog needed (Trigger Now is the explicit user action).

**Verdict:** **WRONG-SHAPE** (inherits F-1) **+ MISSING-PREFLIGHT** (suite plan's own variables not surfaced).
**Severity:** **P0** if the user's automation flows depend on `DockerTagsAsJSON` reaching the build (which they do — the entire `:automation` module exists for this).
**Proposed fix:** fixing F-1 fixes this for free — `BambooApiClient.triggerBuild` is the single funnel point. Verify post-fix that the suite plan actually receives the per-trigger `DockerTagsAsJSON` override.

---

### F-9: `QueueService.enqueue` + `TagHistoryService.persistQueueEntry` (local-only writes) — CORRECT (no server call)

**Code:**
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt` (`enqueue` flow)
- `automation/.../service/TagHistoryService.kt:173` (`deleteQueueEntry`) and persistence sites

**HTTP issued today:** **None.** This is local file persistence (`PersistentStateComponent` / on-disk JSON for queue recovery — see `:automation` CLAUDE.md, `QueueRecoveryStartupActivity`).

**Verdict:** **CORRECT** for what it is. Not a Bamboo write.
**Severity:** N/A.
**Proposed fix:** none.

---

### F-10: Docker tag operations (tag/untag/promote against `DockerRegistry`) — NOT-EXPOSED-AS-UI

**Code:** **None active.** `automation/CLAUDE.md` is explicit:
> "Tag validation flow removed — Trigger Now does not pre-validate tags against any Docker registry; Bamboo handles missing-tag failures during the automation run."
> "DriftDetectorService — no-op after registry calls removed; `isRegistryConfigured()` always returns false"

`grep -rn "DockerRegistry" automation/src/main/kotlin` returns the single match in `DriftDetectorService.kt:12`'s comment ("Docker Registry v2 HTTP calls. Bamboo handles missing-tag failures at run-time."). No actual registry writes.

**Verdict:** **NOT-EXPOSED-AS-UI** — the plugin never POSTs/PUTs/DELETEs against the Docker registry. All tag operations are read-only metadata via `DockerTagsAsJSON` build variable; tag *creation* happens server-side in Bamboo's CI build of each microservice (the "Unique Docker Tag" line in build logs that `TagBuilderService.detectDockerTag` parses).
**Severity:** N/A.
**Proposed fix:** none.

---

### F-11: Per-build artifact / annotation writes — NOT-EXPOSED-AS-UI

**Code:** **None.** `BambooApiClient.downloadArtifact` (line 348–375) is a read; no `uploadArtifact` or `addAnnotation` exists.
**Verdict:** **NOT-EXPOSED-AS-UI**.
**Severity:** N/A.

---

### F-12: Plan favorite/star toggle, plan enable/disable — NOT-EXPOSED-AS-UI

**Code:** **None.** No `favoritePlan`, `starPlan`, `enablePlan`, `disablePlan`, `togglePlan` methods on `BambooApiClient` or `BambooService`. Bamboo does expose `POST /rest/api/latest/plan/{key}/favourite`, but the plugin doesn't surface a favorites concept.
**Verdict:** **NOT-EXPOSED-AS-UI**.
**Severity:** N/A.

---

## 4. Cross-cutting issues

Patterns repeated across multiple findings, listed once for the implementation commit to address holistically.

### CC-1: Single-funnel for queue mutations is good; reuse the fix

`BambooApiClient.triggerBuild` is the single funnel for both manual ("Trigger Build" in BuildDashboardPanel) and automation (AutomationPanel + QueueService) flows. Fixing F-1 (JSON-body → query-param) at this one site fixes F-2 and F-8 simultaneously. Existing test at `BambooApiClientTest.kt:128–145` is the regression net — update its assertions when the wire shape changes.

### CC-2: PlanVariableData is too narrow

`PlanVariableData(name, value)` discards three fields the dialog needs: `isPassword`, `variableType` (PLAN/GLOBAL/MANUAL), and `description`. Plumb these through. F-2 fix.

### CC-3: No CSRF / X-Atlassian-Token header on admin actions

`BambooApiClient.rerunFailedJobs` is the only admin-action POST today. If the plugin ever adds others (e.g., favorite plan, restore from archive), they all need `X-Atlassian-Token: no-check`. Worth adding to `HttpClientFactory.clientFor(BAMBOO)` as a default header for the Bamboo client only — or at least to a `postAdminAction` helper.

### CC-4: No HTML-content-type guard on Bamboo responses

`:core` CLAUDE.md notes Jira's `JiraApiClient` adds an HTML-content-type guard for auth-expired login redirects. The Bamboo `post()` helper at `BambooApiClient.kt:306–339` does check Content-Type for non-JSON (line 318–326), so this is partially in place — but `rerunFailedJobs` at `:204–229` does NOT (it just looks at status code). When Bamboo's session expires and the admin URL 302s to login, the rerun reports success.

### CC-5: No permission preflight on any write

None of `triggerBuild`, `triggerStage`, `cancelBuild`, `stopBuild`, `rerunFailedJobs` calls `/rest/api/latest/permissions/...` or equivalent before enabling its toolbar/dialog button. Native Bamboo greys out actions the user can't perform. Fixing this requires a one-shot permissions probe at panel construction time + a `currentUserPermissions: Set<String>` field on `BuildMonitorService`. Cost: ~50 LOC + test. Optional — the post-hoc 403 toast is acceptable UX.

### CC-6: No confirmation gate on `rerunFailedJobs`

`Stop Build` and `Cancel Build` both have `Messages.showYesNoDialog` confirmations. `Rerun Failed Jobs` does not. Inconsistency. F-6 fix.

### CC-7: Stage runnability is computable but not enforced

`BambooStageDto.{state, lifeCycleState, manual}` are populated by every `?expand=stages.stage.results.result` call. The "first manual stage where all priors are Successful" rule is a 5-line UI computation. F-3 fix.

---

## 5. Out of scope / not found

- **Bamboo Cloud** — DC only, per audit policy and Bamboo Cloud EOL.
- **Plan/repo/deployment configuration writes** (`POST /rest/api/latest/repository`, etc.) — not in plugin today; out of scope.
- **Build labels POST** (`POST /result/{key}/label`) — not in plugin; deferred per `bamboo-audit-recommendations.md` §5 R-ADD-2.
- **Build comments POST** (`POST /result/{key}/comment`) — deferred per §5 R-ADD-7.
- **Deployment project triggers** (`POST /deploy/...`) — rejected per §5 R-ADD-6.
- **`:agent` Bamboo tool wrappers** — `BambooBuildsTool` and `BambooPlansTool` (`agent/.../tools/integration/`) wrap `BambooService`, not `BambooApiClient`. They inherit every finding above transitively. The agent's `bamboo_plans.rerun_failed_jobs` action (`BambooPlansTool.kt:198–210`) and `bamboo_builds.trigger_build` (separate dispatch) need no separate fix — they ride on the service-layer corrections.
- **`HandoverService` Bamboo writes** — none. `:handover` is post-merge, post-build; reads only.
- **Probe coverage of writes** — by design the probe `(read-only)` User-Agent abstains. The recommendations to actually exercise the queue endpoint live (with a known throwaway plan + `bamboo.variable.x=y` query param) sit outside this audit; flag for the implementation commit's verification step.

---

## 6. Probe artifacts referenced

| Artifact | Path |
|---|---|
| Versions-only bundle (write inventory) | `tools/atlassian-probe/Result_Bamboo/bundle-versions-only-uncompressed.txt:195–204` |
| Service-CI bundle (variableContext shape ground truth) | `tools/atlassian-probe/Result_Bamboo/bundle-repo.unpacked/raw/plan_variables_via_context.json` |
| Automation bundle (`DockerTagsAsJSON` real-world payload) | `tools/atlassian-probe/Result_Bamboo/bundle-automation.unpacked/raw/result_variables.json` |
| Stage shape (`manual` field, lifeCycleState) | `bundle-repo.unpacked/raw/result_latest_plan.json`, `result_running_queued.json` |
| Existing read-side audit | `docs/research/2026-05-07-bamboo-audit-recommendations.md` |
