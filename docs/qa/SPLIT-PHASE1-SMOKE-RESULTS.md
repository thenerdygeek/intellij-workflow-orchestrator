# Plugin-Split Phase 1 — runIde Interactive Smoke Results

**Run date:** 2026-06-29 · **Build under test:** the live sandbox was the **A+B** config (`:plugin-b:runIde`) — see note below · mock on all 5 ports, connectors configured.

> ⚠️ **Run was cut short.** The interactive sandbox ("Main" / `com.jetbrains.jbr.java`) closed mid-run (after Item 2), so Items 1/3/4 and the runtime half of Item 2 could not be finished. They need the sandbox relaunched. I cannot launch gradle myself (no terminal/gradle access — the sandbox must be started by the operator).
>
> ⚠️ **Build mismatch for the de-convention items.** Phase 1's de-convention checks (Item 1 "main" default, neutral commit/clipboard defaults) are written for **Plugin A ALONE**. The sandbox that was running is **A+B** (Automation + Handover tabs *and* settings pages are present; `:plugin-b:runIde` sandbox was launched today). On A+B the company **ConfigPreset** is expected to seed company values (`develop` target branch, docker chips), i.e. the *opposite* of the neutral A-alone defaults. So Item 1 (and the neutral-defaults spirit of Item 2) are only meaningful on an **A-alone** sandbox (`./gradlew runIde`), which was not available.

| Item | Verdict | Notes |
|---|---|---|
| 0 Load sanity | ✅ PASS (with note) | Tool window + all tabs present; all settings pages present; plugin **load** is clean. Runtime SEVEREs exist but are *not* load-time (see below). |
| 1 Default target branch = "main" (1a) | ✅ PASS | On the fresh **A-alone** sandbox the Create-PR dialog defaults **Target: "main"** (zoom-confirmed). |
| 2 Commit-message format combo (1c) | 🟡 PARTIAL | Combo + default + options **PASS**; runtime format diff **BLOCKED** (see below). |
| 3 Sprint-tab visibility (1c) | 🟡 PARTIAL PASS | "Shown when agile" direction confirmed; "hidden when non-Software" not toggleable on the mock → mock REQ filed. |
| 4 Agent integration-gating (1b) | 🟡 SOFT / CI-covered | Not meaningfully checkable via the mock (scripted replies don't reflect the system prompt); gating is CI snapshot-tested. See note. |

---

## Item 0 — Load sanity → ✅ PASS (with note)

- **Workflow tool window present**, bottom-docked, tabs: **Sprint · PR · Build · Quality · Automation · Handover · Agent · Insights**. ✅
- **Settings → Tools → Workflow Orchestrator** present with all pages: Connections, Repositories, Jira & Workflow, Code Quality, Telemetry & Logs, Builds & Health Checks, Automation, Handover, AI Agent (→ Multimodal, Cross-IDE Delegation, Web, Database Profiles, Process Tools, Advanced). Root page shows all five connectors **configured**. ✅
- **Log "no stack traces on load":** the *plugin load itself* is clean (no classloading/registration failure for the A+B build). However the sandbox log contains runtime SEVEREs that are **not** load-time:
  - `SprintService` — **"Board discovery failed: Invalid Jira token"** (early, before the board resolved); later resolves to `Active sprint: Sprint 2026.11 (id=7)`.
  - **NEW BUG — `JiraIssueLinkType` MissingFieldException** crashes the sprint-issues load (details below).
  - Known/pre-filed: `SlowOperations` (BUG-AGENT-1), `SourcegraphChatClient` SSE-parse (BUG-AGENT-2 / MOCK REQ-2).
  - Note: the mounted copy of `idea.log` lags the live session, so live-session lines could not be confirmed in real time.

## Item 1 — Default target branch "main" → ✅ PASS (A-alone, 2026-06-29)
On the fresh **A-alone** sandbox (`DefaultWorkflowConfig`), the **Create Pull Request** dialog (PR tab → "+") prefills **Source: feature/plugin-split → Target: `main`** (zoom-confirmed). So A's neutral default target branch is **"main"**, not the old company "develop". (Also corroborated headless by `SettingsMigrationTest`.)

## Item 2 — Configurable commit-message format → 🟡 PARTIAL
- **Combo exists** under **Jira & Workflow → Commit Messages → "AI commit message format"** with **conventional (default)** / **plain**. ✅ (verified in UI and at `jira/.../JiraWorkflowConfigurable.kt:292`; setting `PluginSettings.commitMessageFormat` defaults to `"conventional"`.)
- **Runtime format difference → ⛔ BLOCKED.** The conventional-vs-plain difference is **prompt-only**: `CommitMessagePromptBuilder` swaps the system message (`PLAIN_SYSTEM_MESSAGE` vs `SYSTEM_MESSAGE`) and the user message (`buildPlainUserMessage` vs conventional) by format, but does **not** post-process the model output. The mock returns **fixed scripted content regardless of the prompt**, so both modes would render an identical message — the FORMAT shape can't be observed via the mock. (Also, reaching the generate entry point needs a real staged diff.) To verify live, the mock would need to **echo/transform per-prompt** (so plain vs conventional prompts yield visibly different output), or test against a real Sourcegraph.

## Item 3 — Sprint-tab visibility → 🟡 PARTIAL PASS
- **Sprint tab is SHOWN** in the running sandbox, and the mock Jira **is agile-capable** (log: board discovery + `Active sprint: Sprint 2026.11 (id=7)`). So "tab shown when Jira is Software/agile" holds. ✅
- **Hidden-when-non-Software** could not be exercised: the mock Jira has no non-Software/non-agile scenario to toggle. → **mock REQ filed** (`non-software-jira` scenario) in MOCK-SERVER-REQUESTS.md. Mark that direction Blocked.

## Item 4 — Agent integration-gating → 🟡 SOFT / CI-covered
The sheet frames this as a *soft* live signal. It isn't meaningfully verifiable against the **mock**: the mock returns **scripted** turns and does not read the plugin-built system prompt, so the agent's chat answer reflects the active scenario, not its real integration set. The actual gating (`IntegrationFlags` deciding which integration sections go into the system prompt) is **snapshot-tested in CI** per the sheet. A true live check needs the **built system prompt** inspected (the request body / the agent session's history) against a real Sourcegraph — not reachable cleanly here. No anomaly observed; left as soft/not-a-blocker.

---

## NEW BUG — Jira `JiraIssueLinkType` required-field crash breaks Sprint board load

**Severity:** P2 (functional — Sprint dashboard data load fails). **Where:** `jira/api/dto/JiraDtos.kt:82` (`JiraIssueLinkType`), surfaced via `JiraApiClient.getSprintIssues` (`JiraApiClient.kt:1006/1017`) → `SprintService.loadScrumBoardIssues` → `SprintDashboardPanel.loadData`.

**Symptom (live log):**
```
SEVERE - CoroutineExceptionHandlerImpl - Unhandled exception in Dispatchers.IO
kotlinx.serialization.MissingFieldException: Fields [inward, outward] are required for type with
serial name 'com.workflow.orchestrator.jira.api.dto.JiraIssueLinkType', but they were missing
at path: $.issues[0].fields.issuelinks[0].type
```
**Root cause (two layers):**
1. **Mock gap:** the mock Jira's issue JSON returns `fields.issuelinks[].type` **without** the `inward`/`outward` strings that real Jira always includes. → file/extend a mock fix so issue-link `type` objects carry `inward`/`outward`.
2. **Plugin brittleness:** `JiraIssueLinkType` declares `inward`/`outward` as **required, non-nullable, no default**, so a single issue with a link **throws and aborts the entire sprint-issues deserialization** (unhandled in `Dispatchers.IO`). Hardening: make those fields nullable/defaulted (or decode leniently with `ignoreUnknownKeys`/`coerceInputValues`) so one odd link can't crash the whole board.

---

## What still needs the operator (cannot be done from here)
- **Relaunch the sandbox** — it closed mid-run; I can't start gradle.
- **A-alone build** (`./gradlew runIde`) for Item 1 (and the de-convention spirit of the sheet).
- Once relaunched: finish **Item 3 hidden-direction** (needs `non-software-jira` mock scenario) and **Item 4** (agent integration question).
