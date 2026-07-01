# Phase 2 runIde Smoke — Results

**Run date:** 2026-06-29 · mock on all 5 ports.

> ⚠️ **Hard blocker:** Phase 2 requires launching **two** gradle sandboxes (`./gradlew runIde` for A-alone, `./gradlew :plugin-b:runIde` for A+B). I have **no terminal/gradle access** (the only terminal on screen, kitty, is click-tier — I can't type into it; my own shell is an isolated Linux VM that can't launch the Mac IDE). I can only observe a sandbox the operator launches.
>
> ⚠️ The one interactive sandbox that was running today was the **A+B** build, and it **closed mid-run**. So Part 2 is only partially covered and Part 1 is not started.

## Part 1 — Plugin A ALONE (`./gradlew runIde`) — ✅ ALL PASS (2026-06-29)

Verified live on a **fresh A-alone sandbox** (`[PluginSplit] active WorkflowConfig impl: **DefaultWorkflowConfig**`, log line 15825+, launch 13:59). Note: the sandbox's *Gradle import* of the opened project failed on the unrelated `verification-metadata.xml` checksum issue — cosmetic; the plugin itself loaded and ran fine.

| # | Check | Status | Evidence |
|---|---|---|---|
| 1.1 | no Automation/Handover **tabs** | ✅ PASS | Workflow tool-window tabs = **Sprint · PR · Build · Quality · Agent · Insights** (zoom-confirmed). No Automation, no Handover. No error popup from the tabs. |
| 1.2 | no Automation/Handover **settings pages** | ✅ PASS | Settings → Workflow Orchestrator tree = Connections · Repositories · Jira & Workflow · Code Quality · Telemetry & Logs · Builds & Health Checks · AI Agent. No Automation/Handover pages. |
| 1.3 | log grep: no automation/handover `NoClassDefFoundError`/`ClassNotFoundException`/`LinkageError` | ✅ PASS | **Zero** matches in the current launch (lines 15825→end). The carved-class `ClassNotFoundException`s seen in an *older* log were from a prior build and do **not** recur in this fresh A-alone launch. |
| 1.4 | neutral defaults | ✅ PASS | **Default target branch = "main"** (Create-PR dialog Target field, zoom-confirmed); **Bamboo plan key blank** (auto-detect); **no Docker build-variable** field at all (the Automation page that hosts `DockerTagsAsJSON` is carved out); Repositories table empty. |

> Note (not a carve issue): the one SEVERE in this launch is the pre-existing Jira sprint-load crash — `MissingFieldException` on `JiraLinkedIssue.fields` under `issuelinks[].outwardIssue` (a second face of the REQ-6 issue-link brittleness). It reproduces on A-alone too, so it's a Jira-DTO/mock problem, **not** a split regression. Drives the "IDE error occurred" balloon on the Sprint tab.

## Part 2 — Plugin A + B (`./gradlew :plugin-b:runIde`)

> ⚠️ **Read this first — the running A+B sandbox got corrupted by a failed hot-reload, so 2.1/2.2/2.3 can't be trusted in its current state.** Root cause from the live log (`plugin-b/build/idea-sandbox/.../idea.log`):
> - JVM args include **`-Didea.auto.reload.plugins=true`** + a Compose hot-reload agent + `-XX:+AllowEnhancedClassRedefinition` — i.e. the sandbox **watches the plugin jars and live-reloads** on change.
> - `15:02:40` both plugins loaded; `16:14:48` ConfigPreset applied, **`CompanyBWorkflowConfig` active**, **`QueueService [Automation:Queue] Restored 0 entries`** (B's automation services were fully live).
> - `16:16:07` **"Detected plugin .jar file change … handover.jar / automation.jar, reloading plugin"** — the automation/handover jars were **rebuilt under the running sandbox** (a parallel `./gradlew` build).
> - `16:16:19` **"Plugin …companyb.plugin is not unload-safe because class loader cannot be unloaded"** — the dynamic reload **failed**.
>
> A failed dynamic reload **unregisters** the plugin's UI extensions (tool-window tabs + Settings configurables) but can't re-register them, so afterward the **Automation/Handover (and Agent) tabs and settings pages disappear** even though `CompanyBWorkflowConfig` (a core EP value) survives. **This is a dev-sandbox artifact, not a carve regression.**
>
> **To get a valid Part 2 reading:** launch `:plugin-b:runIde` and **do not run any other `./gradlew` build while that sandbox is open** (or disable `idea.auto.reload.plugins`). Don't rebuild the jars underneath it.

| # | Check | Status | Evidence |
|---|---|---|---|
**Re-run on a clean A+B sandbox (2026-06-29 17:47 launch, log-verified clean — no reload/unload failures):**

| # | Check | Status | Evidence |
|---|---|---|---|
| 2.1 | Automation **and** Handover tabs present | ✅ **PASS** | Clean sandbox tool-window tabs (zoom-confirmed): **Sprint · PR · Build · Quality · Automation · Agent · Handover · Insights**. Both Automation and Handover tabs present. (Confirms the earlier vanished-UI was purely the failed-hot-reload artifact.) |
| 2.2 | Automation + Handover **settings pages** nested under the group | ✅ **PASS** | Under *Tools → Workflow Orchestrator* the full group (scrolled) is: Connections · Repositories · Jira & Workflow · Code Quality · Telemetry & Logs · Builds & Health Checks · AI Agent · **Company B** · **Automation** · **Handover**. Both **Automation** and **Handover** pages are present (siblings below "Company B") and **instantiate to real pages** — Automation shows the Docker Tags + Automation Suites UI; Handover shows the Quick-clipboard-chips + Templates UI. The "Company B" page is a deliberate minimal preset stub ("populated in Phase 2") — the ConfigPreset applies programmatically (log confirms), so that page being minimal is intended. |

> **GAP-1 — WITHDRAWN (was my error).** I initially reported the Automation/Handover settings pages as missing — that was wrong: I had stopped at the "Company B" node without scrolling. On scrolling the full group, **Automation** and **Handover** are present below it and open to real, functional pages. B fully reconnects all settings pages; the only minimal page is the intentional "Company B" preset stub.
| 2.3 | **⭐ Automation → "Trigger Customized…" opens `ManualStageDialog`** | 🟡 **DEFERRED — mock gap (not a plugin defect)** | After the owner entered Bamboo creds (`:8280`), the Automation tab populated (SUITE/BRANCH selectors, DOCKER TAGS "Sent to Bamboo as build variable: `DockerTagsAsJSON`"), and the **Trigger ▾** menu surfaced **"Trigger Customized…"** + "Trigger All Stages". **Clicking "Trigger Customized…" produced no `ManualStageDialog`** — because there are **no stages**: the SUITE dropdown is empty and the Settings → Automation **Project dropdown shows "Failed: …resource not found"** (the mock's Bamboo project/plan-listing endpoint 404s), so no suite/plan/stage data exists. Per the sheet's own caveat ("if the mock Bamboo doesn't return suite-shaped data … 2.3 needs a custom mock plan or stays deferred — don't force it"). **Cross-classloader partial de-risk:** B's Automation panel and the "Trigger Customized…" action loaded and ran with **no `NoClassDefFoundError` popup**; only the terminal `ManualStageDialog` instantiation is unverified (no stage to trigger). Needs a mock Bamboo plan-with-stages scenario → **MOCK REQ-8 filed**. |
| 2.4 | Handover → copyright Rescan runs (read-only) | ✅ **PASS** | After Jira/Bitbucket creds, the Handover tab populated. **Actions → "COPYRIGHT HEADER STATUS"** reads the changelist via `ChangeListManager` and shows **"No files to check"** — i.e. the scan **runs gracefully** (empty changelist for copyright-relevant files), no crash, and **no Git4Idea dependency needed** in B (the check's purpose). Did **not** click Fix-All (read-only constraint, HND-05). |
| 2.5 | A handover action (Jira closure builds) | ✅ **PASS** | Handover **Share** sub-tab builds a **Jira closure comment**: renders `h2. Handover — {ticket.id}`, the `‖ Suite ‖ Result ‖ Docker tag ‖ {automation.suiteTable}` table, and `PR: [#{pr.id}]{pr.url}` (with a copy-to-clipboard control). The handover closure-comment action works. |

**Bottom line for 2.3–2.5 (after owner entered mock creds):** **2.4 and 2.5 PASS** — the Handover copyright-status (changelist read, no Git4Idea) and Jira closure-comment builder both work. **2.3 is deferred** purely because the **mock Bamboo doesn't return project/plan/stage data** (`resource not found`), so there's no stage for "Trigger Customized…" to open the `ManualStageDialog` — the B→A cross-classloader path is partially de-risked (panel + menu load cleanly) but the dialog open itself needs a mock plan (REQ-8). One side-note: an **"IDE error occurred"** balloon shows on the Sprint/Handover tabs from the Jira active-ticket fetch — consistent with the REQ-6 issue-link deserialization brittleness (mock issue data), not a split defect.

---

## 2026-06-30 — clean sandbox re-run (REQ-8 live): 2.3 advances but hits a NEW blocker

**Insights sanity (operator Check 1): ✅ PASS.** On the clean rebuilt sandbox the **Insights** tab now loads normally — sub-tabs Today / This Week / Sessions / Reliability + an empty state *"No sessions today. Start a conversation with the agent."* No raw `SessionHistoryReader` error. Confirms the earlier raw-error was the **corrupted-jar / mmap** artifact (jars rewritten under the running IDE), not a code defect.

**⭐ 2.3 (ManualStageDialog) — 🟡 still BLOCKED, but now one step further (NEW finding):**
- **REQ-8 is live ✅** — Settings → Automation → "Add suite by browsing Bamboo projects" → **Project** picker lists **"Mock Project (PROJ)"** (no more "resource not found"), and the **Plan** dropdown lists **Build (PROJ-BUILD)**, Sonar (PROJ-SONAR), Test (PROJ-TEST).
- Selecting PROJ → PROJ-BUILD → **Add** creates the suite; it **persists** under "Configured Suites: Build → PROJ-BUILD" (Apply greyed = saved).
- **NEW BLOCKER:** the **Automation tab never loads the saved suite** — its **SUITE dropdown stays empty** ("No suites configured — go to Settings") after (a) the tab's own refresh button, (b) the tool-window "Refresh All Tabs", and (c) a full tool-window close+reopen. So **"Trigger Customized…" still no-ops** (no stage context) and `ManualStageDialog` can't be reached. This is a **suite-reload gap** (the tab doesn't pick up suites saved in Settings — likely a project-scope key mismatch or a missing config-change listener), adjacent to the auto-refresh theme in UI-UX-REVIEW. **For the owner to grep:** the suite-load path on Automation-tab init / refresh — why does it return an empty list when `Configured Suites` is non-empty in settings? (Avoided a sandbox restart per the no-build rule.)
- Cross-classloader status unchanged: the B Automation panel + the "Trigger Customized…" action load with **no `NoClassDefFoundError` popup**; only the terminal `ManualStageDialog` is still unverified, now blocked by the suite-reload gap rather than the mock.
| 2.6 | Config preset (docker chips, `DockerTagsAsJSON`, target branch `develop`) | 🟢 **MOSTLY PASS (no creds needed)** | Verified from B's settings pages on this A+B sandbox: **Automation page → Build variable name = `DockerTagsAsJSON`** ✅; **Handover page → Quick-clipboard chips include `docker.tag`, `docker.tagsJson`, `automation.url` (all checked)** ✅ — the company preset set. Log confirms `[PluginSplit] applied ConfigPreset company defaults (one-shot)`. The remaining value — **default target branch = `develop`** — couldn't be read here (the PR/Create-PR surface is Bitbucket-config-gated; A-alone showed `main`), but it's corroborated by the one-shot preset log + the two confirmed company values. |
| 2.7 | One-shot seed survives chip removal + restart | ⛔ BLOCKED | Needs a clean sandbox restart not rebuilt underneath. |

### Positive signals captured for A+B (log-level, trustworthy)
- Both plugins load: **"Loaded custom plugins: Workflow Orchestrator (0.87.2), Workflow Orchestrator - Company B (0.87.2)"**; launch is `:plugin-b:runIde` (`-Didea.required.plugins.id=com.workflow.orchestrator.companyb.plugin`).
- **`CompanyBWorkflowConfig`** is the active `WorkflowConfig` impl, and the company **ConfigPreset applied one-shot** (2.8 markers ✅).
- B's automation backend is live: `QueueRecoveryStartupActivity` + `QueueService [Automation:Queue] Restored 0 entries`.
- No automation/handover `NoClassDefFoundError`/`LinkageError` (the only CNFEs are `:agent` UI `*EditorProvider`, all from the same failed-reload).
| 2.8 | log markers `CompanyBWorkflowConfig` + `applied ConfigPreset company defaults (one-shot)`; no Linkage/NoClassDef | ✅ PASS (markers) / ⚠️ caveat | **Both markers present** in the live A+B launch (`plugin-b/build/idea-sandbox/.../idea.log`, lines 5168/5184): `[PluginSplit] applied ConfigPreset company defaults (one-shot)` and `[PluginSplit] active WorkflowConfig impl: **CompanyBWorkflowConfig**`. **No** automation/handover/ManualStage `NoClassDefFoundError`/`LinkageError`. ⚠️ But the launch **does** have `ClassNotFoundException`s for `:agent` UI `*EditorProvider` classes (AgentChat/AgentVisualization/ApiDocs/AgentPlan/ToolDocs/ToolTesting EditorProvider) — these coincide with the IDE balloon **"Failed to unload modified plugins: Workflow Orchestrator - Company B → Restart"**, i.e. a **failed plugin hot-reload / stale classloader**, not a carve defect. |

### ⚠️ This A+B sandbox is in a degraded (hot-reload) state — recommend a clean Restart before trusting the GUI checks
On launch the IDE showed **"Failed to unload modified plugins: Workflow Orchestrator - Company B"** with a **Restart** prompt, plus a **"Low memory"** warning. The agent-UI `ClassNotFoundException`s above are consistent with that stale classloader. Because **2.3** specifically probes a cross-plugin class load, a failure there right now could be a **false positive** from the stale state. **Action:** click the balloon's **Restart** (or relaunch `:plugin-b:runIde` clean) so Company B loads fresh, then 2.1–2.5 can be trusted. (Also: Chrome kept stealing window focus during this attempt, blocking GUI driving — the sandbox needs to be frontmost.)

## Most important outstanding checks (per the sheet)
1. **2.3 `ManualStageDialog`** — the ⭐ cross-plugin classloader path; only observable live.
2. **2.8 / 1.3 log greps** — need a live (non-stale) `idea.log` read on each sandbox.

## What I need from the operator to finish Phase 2
1. Launch **A-alone**: `./gradlew runIde` → I'll do Part 1 (1.1–1.4) + grep its log.
2. Launch **A+B**: `./gradlew :plugin-b:runIde` → I'll do 2.3 (ManualStageDialog), 2.4/2.5 (read-only), and read its live log for 2.8.
3. For 2.6/2.7: a **fresh project** (never had the plugin configured) opened in the A+B sandbox, and a willingness to **restart** that sandbox once.
