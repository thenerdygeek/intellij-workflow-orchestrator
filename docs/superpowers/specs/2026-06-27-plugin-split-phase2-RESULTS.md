# Plugin Split — Phase 2 RESULTS (carve company-only → B)

**Status: COMPLETE on `feature/plugin-split` — NOT pushed.** 22 commits, `dad8d4c7bf89712b05a57bcbd4edae4aecf2c465..834e46c37` (Phase-2 BASE → HEAD). Executed autonomously, subagent-driven, with the split's mandated multi-round review at every step (3-round opus plan review per sub-phase + per-task two-stage review + whole-sub-phase opus review + a final whole-branch opus review). Green gate verified after each sub-phase. Push + GUI smoke left for the user.

Plans: `docs/superpowers/plans/2026-06-27-plugin-split-phase2{a,b,c}-*.md`. Spec notes: design §21 (2a) / §22 (2b) / §23 (2c). Exploration maps: `.superpowers/phase2/explore-{automation,handover,ep-preset-konsist}.md`. SDD ledger: `.superpowers/sdd/progress.md`.

---

## What shipped, per sub-phase

### 2a — carve `:automation` → B (9 commits `b82432abe..2347e9426`)
- `:automation` stays a **top-level Gradle module** but ships with `:plugin-b` (bundled `implementation(project(":automation")){ isTransitive = false }`), no longer with A. Root keeps `kover(project(":automation"))` (coverage aggregation, independent of bundling).
- A's `WorkflowToolWindowFactory.defaultTabs` dropped the hard-coded **"Automation"** tab → it now surfaces only as B's extension-provided `AutomationTabProvider`.
- A's `plugin.xml` removed all automation registrations (services, app service, `AutomationConfigurable`, status-bar widget, queue-recovery startup, 2 notification groups, tabProvider). B re-registers them from its own descriptor.
- The trimmed **sqlite-jdbc** runtime jar relocated from automation's build into `plugin-b/build.gradle.kts` (A never shipped sqlite standalone; it shipped only transitively via `:automation`). `TrimmedSqliteJarInvariantsTest` moved to `:plugin-b`; `BundledPluginLibInvariantsTest` (tests **A's** dist) moved to `:document`.
- **Dropped the pre-existing dangling `ConflictDetectorService` registration** (class never existed — latent bug).
- Headless duplicate-class proof: B's jar = 173 automation classes + sqlite, **0** of `:core`/`:bamboo`/okhttp.

### 2b — carve `:handover` → B (8 commits `dec558b52..5332b8af7`)
- `:handover` → B (same top-level-module-bundled-by-B pattern, kover retained); "Handover" default tab dropped from A.
- **CopyrightFix tease-apart:** the **generic** `CopyrightFixService` + `CopyrightFileEntry`/`CopyrightStatus` model types moved `:handover` → **`:core/copyright`** (charter: the year-arithmetic is generic and STAYS in A). `CopyrightFixCard` (UI) stays in `:handover`→B, imports them from `:core`. Behaviour-preserving move (verified byte-equivalent via git rename similarity).
- **`HandoverConfigurable` moved `:core` → `:handover`** (it is handover-only; OSS-A should not ship it). B registers it via `projectConfigurable parentId="workflow.orchestrator"`.
- **Dropped the dead `PreReviewService` registration** (no `.kt` in the tree).
- Generic `core.bitbucket.PrService` **stays in A** (consumed by `:pullrequest`) — its `<projectService>` line relocated out of the handover block; its tests moved `:handover` → `:core/bitbucket`.
- Detekt baselines migrated **surgically** between `:core`/`:handover` for the moved files (per-module baselines key on bare filename).
- Headless proof: B's jar = 169 handover + 173 automation classes, 0 `:core` duplicates.

### 2c — B config-preset + deferred default blanking (6 commits `d3934fe69..834e46c37`)
- New **`ConfigPreset` EP** in `:core` — application-level, `public` + `@InternalApi` (unfrozen), order-resolved like `WorkflowConfig` (`DefaultConfigPreset` at `order=MAX` all-null; B's `CompanyBConfigPreset` at `order=0`). 4 fields: `bambooBuildVariableName`, `quickClipboardChips`, `defaultTargetBranch`, `copyrightTemplate`.
- **`ConfigPresetSeeder`** (A-owned logic) runs at startup in `SettingsMigrationStartupActivity` AFTER the migration. **One-shot** (`configPresetApplied` sentinel, stamped only when a value-providing preset is present) + **per-field guarded** (writes a value only if the field still equals A's neutral default). This: seeds fresh A+B once; never clobbers user edits or upgrader values; never resurrects a curated-away chip; and still seeds a B-installed-**after**-A case (A-alone never stamps the sentinel).
- **Blanked the deferred company defaults in `:core`:** `bambooBuildVariableName "DockerTagsAsJSON"→""` (covered by B's automation blank-safe fallback + the preset); `quickClipboardChips` init → `NEUTRAL_QUICK_CLIPBOARD_CHIPS` (5 generic keys; the `docker.*`/`automation.url` company chips move to B's preset). **No `SettingsMigration` bump** (justified: blank-safe automation readers + always-persisted chips preserve upgraders).
- `CompanyBConfigPreset` supplies the company values (`DockerTagsAsJSON`, the 8-chip company list, `defaultTargetBranch="develop"`).

---

## Simplifications / improvements made (charter §8 latitude) + why

1. **Separate `@InternalApi ConfigPreset` EP instead of widening the frozen `@StableApi WorkflowConfig`** (the charter/spec literally said "a `WorkflowConfig` impl / WorkflowConfig widening"). `WorkflowConfig` is `@StableApi(since="0.86")` and returns only `baseUrl(service)` — widening it would pollute/break a frozen, fork-facing contract and conflate *live config reads* with *seed-once mutable defaults*. The new EP is the cleaner home (validated by both final reviewers as a correct §8 improvement). Recorded so the user sees the deliberate, sound deviation from the charter's wording.
2. **Dropped two dead registrations** (`ConflictDetectorService` in 2a, `PreReviewService` in 2b) — A's `plugin.xml` registered service classes that don't exist anywhere. Latent-bug cleanup.
3. **Kept `:automation`/`:handover` as top-level modules** (bundled by B) rather than physically relocating under `plugin-b/`. Minimal/reversible diff; the carve is pure *wiring* (module source physically unchanged); and konsist `ModuleBoundaryTest`/`LayeringTest` keep checking `:handover` (it's a featureModule; moving it under `plugin-b/` would silently drop that coverage via the `filterNot{/plugin-b/}` filter). **Trade-off:** the source repo is therefore NOT yet OSS-clean — see "Phase-5 readiness" below.
4. **`:plugin-b` sandbox isolation** (`sandboxContainer = layout.buildDirectory.dir("idea-sandbox")`) — fixed a latent Gradle-9.4 undeclared-cross-task-dependency that surfaced under the aggregate `koverVerify` once B bundled `:automation`. Matches the convention already used by `:automation`/`:bamboo`/`:sonar`/`:pullrequest`/`:handover`.

---

## Green-gate results

After each sub-phase, run with `--rerun-tasks` (not cache-masked):
- **Module tests:** `:core`, `:automation`, `:handover`, `:plugin-b`, `:konsist` (+ `:document` for the relocated bundled-lib test) — all GREEN.
- **`verifyPlugin`** (Plugin A, JetBrains verifier) — BUILD SUCCESSFUL each sub-phase. (`:plugin-b:verifyPlugin` is disabled by design — Marketplace verifier can't resolve the local unpublished A.)
- **`koverVerify -Pcoverage -x uiTest`** — floor `minBound(48)` holds each sub-phase (both `:automation` and `:handover` kept in the kover aggregation). ⚠ The 2c run flaked once on the known **#51** timing/socket tests (`TemplateEditorCardTest` debounce, `Delegation*` sockets) under coverage instrumentation, then passed on retry; 2c touched no `:agent` and `TemplateEditorCardTest` passes standalone, so these are not regressions.
- **Distribution jar checks (headless duplicate-class proxy):** A's dist contains NO `com/workflow/orchestrator/{automation,handover}/` classes and no trimmed sqlite; B's dist contains both modules' classes + sqlite, with **0** `:core`/`:bamboo`/okhttp duplicates.
- **detekt:** every touched file is new-issue-clean; per-module baselines migrated surgically (never wholesale-regenerated). Pre-existing cache-masked debt remains user-deferred: `:core` `PluginDiagnosticLogService.kt` + `RepoConfigDefaultBranchTest.kt`, `:plugin-b` `CompanyBSplitBehaviorTest.kt` (Phase 0a), `:agent` 73 (Phase 0b-3).
- **`-x buildSearchableOptions`** was used on macOS when a running IDEA instance blocked that step (env quirk; it's a settings-index build, not a descriptor/verifier gate).

---

## runIde smoke checklist (PENDING-USER — cannot GUI-verify headless; Mac has no runIde)

Run `gradlew.bat :plugin-b:runIde` (loads A+B) and the root `runIde` (loads A alone). The headless gate proved the classes are partitioned correctly; these confirm the runtime/cross-classloader behavior the gate can't see.

**A alone (root `runIde`):**
1. Tool window "Workflow" shows **no Automation tab and no Handover tab**, no error in idea.log.
2. Settings → Workflow Orchestrator: **no Automation page, no Handover page** (the copyright-fix capability `CopyrightFixService` is present in `:core` but has no UI in A alone — that's expected).
3. Defaults neutral: `quickClipboardChips` has no `docker.*`/`automation.url`; `bambooBuildVariableName` blank; default target branch "main".
4. No `LinkageError`/`NoClassDefFoundError` in idea.log.

**A + B (`:plugin-b:runIde`):**
5. Automation tab + Handover tab appear (ordered after the default tabs); Automation/Handover settings pages nest under "Workflow Orchestrator".
6. **Automation "Trigger Customized…" opens `bamboo.ui.ManualStageDialog`** — this is the one cross-plugin path (B's `AutomationPanel` → A's `:bamboo` UI via parent classloader) the headless gate cannot verify; a `NoClassDefFoundError` would surface only here.
7. **Handover copyright "Fix All" / rescan** works (B's `CopyrightFixCard` → platform `ChangeListManager`; confirms no Git4Idea `<depends>` is needed in B).
8. A handover action runs (Jira closure comment, clipboard chip copy).
9. **Config preset seeding:** on a FRESH A+B project, `quickClipboardChips` shows the company set (incl. `docker.*`), `bambooBuildVariableName`="DockerTagsAsJSON", default target branch "develop". Remove a company chip → restart → it stays removed (one-shot sentinel). A user-customized value is not clobbered.
10. idea.log shows `[PluginSplit] active WorkflowConfig impl: CompanyBWorkflowConfig` and `[PluginSplit] applied ConfigPreset company defaults (one-shot)`.

---

## DECISIONS NEEDING USER CONFIRMATION

1. **ConfigPreset seed is one-shot** (`configPresetApplied` sentinel). Once a value-providing preset has been applied, the seeder never re-runs — so if the company later **changes** a preset value (e.g. adds a chip), existing installs that already seeded won't pick it up. Reversible: introduce a preset-version int and re-apply when it bumps (described, not built — YAGNI). Confirm acceptable.
2. **`copyrightTemplate` is NOT seeded by B's preset** — the EP supports it, but `CompanyBConfigPreset.copyrightTemplate()` returns null. Seeding a placeholder would insert wrong copyright text; the real header is out-of-band (the company sets it in B's settings UI). Flip it to return the real header when available. Confirm.
3. **`defaultTargetBranch="develop"` IS now seeded for fresh A+B installs** (spec §93 listed it as a B-preset value; Phase 1a deferred fresh-company-install restoration to the preset). Upgraders already have "develop" from the 1a migration (the guard skips them). **Behavioral edge** (surfaced by the final OSS-skeptic review): an A-**alone** user who deliberately kept the neutral "main" and *then installs B* will have it flipped to "develop" — the `==neutral` guard cannot distinguish "never touched" from "deliberately wants main". Acceptable for a company-internal B, but worth your explicit nod.
4. **Top-level-module layout (Phase-5 readiness, NOT a Phase-2 defect):** `:automation`, `:handover`, and `:plugin-b` still live at the repo top level. **A's distribution JAR correctly excludes the company modules** (verified), but **publishing this repo as-is would still expose their source.** The physical repo extraction (and the `agent/resources/api-docs/*.json` + comment scrub: `DockerTagsAsJSON` in KDoc, the `[Handover:Copyright]` log tag in `:core`, two stale `automation`-package doc/example refs in `:sonar`/`:agent`) are the explicit **Phase-5** deliverables. Do not open-source A before Phase 5.

---

## Not done by design (out of Phase-2 scope)
- **Push / PR** — left for the user (charter §0.6).
- **runIde GUI smoke** — PENDING-USER (above).
- **Phase 3** (persona/prompt role-text, `supportsSpring`), **Phase 4** (native LLM + persisted-format migration), **Phase 5** (OSS hardening: repo extraction, api-docs/comment scrub, `@StableApi` freeze, Marketplace publish) — future efforts.
