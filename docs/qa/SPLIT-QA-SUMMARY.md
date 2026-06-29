# Plugin Split — QA Campaign Summary (Phase 1 + Phase 2)

**Date:** 2026-06-30 · **Branch:** `feature/plugin-split` · **Sandbox:** live runIde on macOS (A-alone `:runIde`, A+B `:plugin-b:runIde`) against the `:mock-server` backends.

> **Headline:** the plugin split is **verified structurally sound**. Both plugins load, every tab / settings page / service reconnects under A+B, `CompanyBWorkflowConfig` + ConfigPreset are active, and **zero cross-plugin classloader errors** appeared anywhere. No carve defect was found. The two genuine plugin bugs surfaced during testing are **fixed**; everything still open is **mock-data completeness** or **UX backlog**, not a split defect.

This rolls up the detail docs: [SPLIT-PHASE1-SMOKE-RESULTS.md](SPLIT-PHASE1-SMOKE-RESULTS.md), [PHASE-2-RUNIDE-SMOKE-RESULTS.md](PHASE-2-RUNIDE-SMOKE-RESULTS.md), [UI-UX-REVIEW.md](UI-UX-REVIEW.md), [MOCK-SERVER-REQUESTS.md](MOCK-SERVER-REQUESTS.md) (REQ tracker).

---

## Phase 1 — split smoke (Items 0–4): ✅ COMPLETE

| Item | Check | Verdict |
|---|---|---|
| 0 | Plugin load sanity (both plugins, no classloader errors) | ✅ PASS |
| 1 | Target branch = `main` on A-alone (neutral default, no company preset) | ✅ PASS |
| 2 | Commit-format combo (prefix/body) | ✅ PASS |
| 3 | Sprint-tab agile gating | ✅ PASS |
| 4 | Integration (cross-module EP) gating | ✅ PASS |

## Phase 2 — A-alone (Part 1): ✅ ALL PASS · A+B (Part 2)

| Check | What | Verdict |
|---|---|---|
| 2.1 | Automation + Handover **tabs** present under A+B | ✅ PASS |
| 2.2 | Automation + Handover **settings pages** present | ✅ PASS — *GAP-1 (cowork) was a misread; B registers all 3 configurables (Company B + Automation + Handover). **Withdrawn.*** |
| 2.3 | ⭐ `ManualStageDialog` (cross-classloader) | 🟡 **DEFERRED** — mock Bamboo project/plan listing 404s → no suite/stage to open the dialog. Path **partially de-risked**: Automation panel + "Trigger Customized…" menu loaded with **no `NoClassDefFoundError`**. Blocked on **REQ-8**. |
| 2.4 | Handover copyright status reads changelist | ✅ PASS — "No files to check", graceful, no Git4Idea, no crash (Fix-All not clicked) |
| 2.5 | Handover Jira closure comment builds | ✅ PASS — suite table + PR link render on Share tab |
| 2.6 | Company preset applied | 🟢 confirmed — `DockerTagsAsJSON` build variable + `docker.tag`/`tagsJson`/`automation.url` chips |
| 2.8 | `CompanyBWorkflowConfig` + ConfigPreset one-shot | ✅ PASS — live log, no automation/handover `LinkageError` |

---

## Plugin bugs found + FIXED (local commits, not pushed)

| ID | Bug | Fix | Commit |
|---|---|---|---|
| REQ-6 | Jira issue-link deser brittleness — a single restricted/partial linked issue (missing `inward`/`outward`, then `fields`/`summary`/`status`) threw `MissingFieldException` and **blanked the whole Sprint board** | Defaulted the full issue-link DTO chain (non-nullable, no consumer churn, shared `JiraStatus` untouched) | `59bc1dfe8` + `dd3d3992d` |
| — | **Handover notification NPE** (the "ambient IDE-error balloon" on Sprint/Handover — *not* REQ-6) — `HandoverWikiPreviewRenderer` notifies on groups `workflow.handover.wiki[.transient]` registered in no `plugin.xml`, and `WorkflowNotificationService.notify` chained `getNotificationGroup(id).createNotification` with no null-check → NPE killed the coroutine | Register the 2 groups in B's `plugin.xml` + null-safe `notify` (WARN + return) | `e123c78f4` |

**REQ-6 is RESOLVED and live-verified** — the fix is baked into the running jar and the live log shows **zero** issue-link deser crashes.

## Reclassified — NOT a plugin defect

- **Insights tab "Failed to load … `SessionHistoryReader`"** → traced to a **corrupted sandbox jar** (IntelliJ mmap `ZipException: invalid distance too far back`), an artifact of jars rewritten under a running sandbox. The on-disk jar passes `unzip -t`; a fresh build is clean. **Insights loads normally on a clean relaunch.** Reclassified P1 → environmental; re-test on a clean build. (A friendly tab-load error + Retry in `WorkflowToolWindowFactory.materializeByTitle` remains a valid defense-in-depth ask — see UI-UX-REVIEW.)

## Build-config fixes (this session)

- **`allprojects` scope fix** — the runIde JVM-arg block was root-scoped, so `:plugin-b:runIde` (A+B) never received `-Dworkflow.orchestrator.allowPrivateUrls=true` → the SSRF guard rejected the localhost mock URLs on A+B (worked on A-alone). This is the **complete** resolution of REQ-1 for the split sandbox.
- **`-Didea.auto.reload.plugins=false`** — the A+B plugin is not dynamically unload-safe; a background build rewriting bundled jars under a running sandbox strips B's UI via a failed hot-reload (it does **not**, however, prevent jar **mmap corruption** like the Insights case — the standing rule remains: **don't run any gradle build while a QA sandbox is open**).

---

## Open — mock-data gaps (NOT plugin defects)

| REQ | Gap | Impact |
|---|---|---|
| REQ-2 | Mock returns SSE for all chat (breaks non-streaming title-gen) | agent title-gen path |
| REQ-3 | `plan-mode` scenario doesn't drive Approve→Act | plan-mode E2E |
| REQ-4 | `multi-tool` `run_command` unscoped → repo-wide flood | agent multi-tool E2E |
| REQ-5 | Thinking emitted as one instant SSE frame | BUG-3 timing window unreachable |
| REQ-7 | No non-Software (non-agile) Jira scenario | Sprint-tab **hide** path unverified |
| REQ-8 | Mock Bamboo project/plan listing 404s | **blocks Phase-2 check 2.3** (`ManualStageDialog`) + the Bamboo wiki `/render` 404 |

## Open — UX backlog (UI-UX-REVIEW.md)

Spec-grounded review of all 8 tabs' empty/loading/populated/error states. Real, small fixes: auto-refresh tabs on connection change; disable un-runnable actions (Automation "Trigger Customized…" silent no-op) with a reason; deep-link "Connect" buttons to the specific settings page; standardize empty-state copy via `EmptyStatePanel`. **One design proposal pending product direction:** a unified cross-tab **Setup/Connections checklist** (replace N "Connect … in Settings" dead-ends with one guided first-run surface).

---

## Bottom line

The split itself is **done and sound**. Remaining work splits cleanly into: (a) mock-server completeness (REQ-2/3/4/5/7/8) to unlock the few deferred E2E checks, and (b) a UX-polish backlog (post-merge). The only check still gated on the split specifically is **2.3 (`ManualStageDialog`)**, blocked solely by REQ-8 (mock data), with its classloader path already partially de-risked.
