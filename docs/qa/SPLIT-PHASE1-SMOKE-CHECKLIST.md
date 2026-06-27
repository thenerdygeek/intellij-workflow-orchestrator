# Plugin-Split Phase 1 — runIde Interactive Smoke Checklist (for cowork)

## Context
Phase 1 of the open-source split is code-complete and the automated gate is green; only the **interactive runIde smoke** was deferred (Mac couldn't runIde — now resolved with Ultimate installed). The running sandbox is the `feature/plugin-split` build and includes all Phase-1 changes. This checklist verifies the Phase-1 *behavior* changes in the live IDE.

Phase 1 = **1a** de-convention (neutral defaults + settings migration) · **1b** agent system-prompt integration-gating · **1c** mechanical tail (configurable commit format, Sprint-tab hide, default-branch shape).

## Environment (already running)
- runIde sandbox (this Mac) + the 5-port mock: Jira 8180, Bamboo 8280, Sonar 8380, Bitbucket 8480, Sourcegraph 8088. All backends mocked; Connection settings already point at the mock.
- **Do NOT restart the sandbox or the mock** — another session is fixing bugs against the same build and will coordinate any rebuild. If something needs a rebuild, note it and flag it rather than restarting.

## Constraints
- **No backend writes** (no ticket create/transition, time-log, branch create, merge, build trigger, Jira closure, copyright-fix-all). Settings changes and AI text *previews* (commit-message generation) are fine — they don't write to a backend.
- If a check needs a mock capability that doesn't exist (e.g. a non-Software Jira scenario), **file it in `docs/qa/MOCK-SERVER-REQUESTS.md`** and mark the item Blocked — don't force it.

## Report
Write results to `docs/qa/SPLIT-PHASE1-SMOKE-RESULTS.md`: per item **Pass / Fail / Blocked** + what you observed + screenshot or `~/.workflow-orchestrator/diagnostics/plugin-0.log` line.

---

## 0. Load sanity
- [ ] Plugin loads; bottom-docked **"Workflow"** tool window present with its tabs.
- [ ] **Settings → Tools → Workflow Orchestrator** shows its settings pages (Connections + the workflow pages).
- [ ] `~/.workflow-orchestrator/diagnostics/plugin-0.log` has **no stack traces / errors** on load.

## 1. De-convention — default target branch is "main" (1a)
Phase-1 change: A's default `defaultTargetBranch` is now the neutral **"main"** (was the company convention "develop"); `SettingsMigration` v1→v2 seeds "develop" back for **upgraders only**.
- [ ] **Fresh-install default:** wherever the target branch is surfaced (the PR-create dialog's default target branch, or the settings field), the default reads **"main"**, not "develop".
  - PASS: default = "main".
- [ ] **Upgrade path (optional, harder to stage):** simulating an upgrader needs `settingsSchemaVersion ≥ 1` with no explicit `defaultTargetBranch` set — hard to stage interactively. If you can pre-seed the sandbox config to do it, PASS = old "develop" preserved. Otherwise mark **"covered by `SettingsMigrationTest`"** and move on.

## 2. Configurable commit-message format (1c)
Phase-1 change: `PluginSettings.commitMessageFormat ∈ {conventional (default), plain}`. UI combo lives at **Settings → Workflow Orchestrator → the Jira workflow page → "Commit Messages" group**.
- [ ] The combo exists with **Conventional** (default) / **Plain**.
- [ ] With **Conventional**, trigger an AI commit-message preview (the "generate commit message" entry point). Expect a Conventional-Commits-style message: a type prefix + ticket context.
- [ ] Switch to **Plain**, regenerate. Expect a **bare imperative summary** — no type prefix, no ticket context injected.
  - PASS: the two formats differ exactly as described. (Generation uses the Sourcegraph mock — the message *content* doesn't matter, only its FORMAT shape.)
  - If you can't reach the generation entry point without a real staged change, note how far you got and mark **Blocked**.

## 3. Sprint-tab hide on non-Software Jira (1c)
Phase-1 change: `WorkflowTabProvider.isAvailable` + `JiraAgileCapabilityService` (tri-state probe) → the **Sprint** tab is hidden when the configured Jira is **not** a Software (agile) project; shown when it is. (`TabAvailabilityChanged` triggers the tool-window rebuild.)
- [ ] Observe the Workflow tool window: is the **Sprint** tab present or hidden with the current mock Jira?
- [ ] If the mock Jira exposes agile/sprint capability → tab **shown**; if not → **hidden**.
- [ ] **Toggle if possible:** if the mock Jira (`/__admin` on 8180) has a Software-vs-non-Software scenario, switch it, re-trigger the probe (re-open the tool window), and confirm the tab appears/disappears.
  - PASS: tab visibility tracks the Jira agile capability.
  - If the mock can't toggle agile capability, record the observed default and **file a mock REQ** for a `non-software-jira` scenario.

## 4. Agent integration-gating (1b) — light check
Phase-1 change: the agent's system prompt gates integration sections by configured integrations (`IntegrationFlags`). This is snapshot-tested in CI; a soft live check:
- [ ] In the agent chat, ask (read-only) **"what integrations and tools do you have access to?"** The answer should reflect the mock-configured integration set and not reference integrations that aren't wired. Soft signal — flag anything that looks like a stale/ungated integration reference.

---

## Wrap-up
File per-item results in `SPLIT-PHASE1-SMOKE-RESULTS.md`. Anything that needed a missing mock capability → `MOCK-SERVER-REQUESTS.md`. When done, summarize: which Phase-1 behaviors are **verified in-IDE** vs **Blocked**, so we can decide whether the split base is solid enough to resume Phase 2.
