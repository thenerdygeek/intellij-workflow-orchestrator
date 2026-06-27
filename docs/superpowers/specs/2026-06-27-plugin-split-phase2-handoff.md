# Plugin Split — Phase 2 Handoff (autonomous, run-to-completion)

**You are picking up Phase 2 of the Workflow-Orchestrator plugin split. Execute it to completion without human supervision.** The user will not be available to review mid-flight. This document is your charter.

---

## 0. Autonomy contract (read this first — it changes how you work)

1. **Run to completion.** Don't stop to ask the user for sign-off between steps. Decompose, execute, verify, commit, repeat until Phase 2 is done or you hit a genuine hard blocker (defined in §9).
2. **Take small calls yourself.** Any decision that is reversible, low-blast-radius, or a matter of reasonable judgment (naming, file placement, how to split a task, which neutral DTO shape, where a helper lives) — just decide and proceed. Note non-obvious choices in your final report. Do **not** burn a turn asking about something you can sensibly decide.
3. **You are your own reviewer.** The split's process mandates *multiple independent review rounds at every step* (`[[multi-round-review-plugin-split]]`). With no human in the loop, you substitute **independent subagent reviewers** for the user: after you write a plan, dispatch 2–3 independent opus reviewers (different lenses: correctness/completeness, architecture/OSS-skeptic, security); after you execute each task, an independent task-reviewer; after each sub-phase, a whole-branch opus review. This is non-negotiable — it is the *only* thing standing in for the user's eyes. Fold review findings in before moving on.
4. **The hard floor is the green gate.** A+B must keep working. After every sub-phase: full module tests (`--rerun-tasks`/`clean`, not cache-masked) + `verifyPlugin` (A only — B's is disabled, expected) + detekt-clean *for code you touched* + `:konsist:test`. If the gate is red, you are not done with that step.
5. **Improve as you go (new latitude, 2026-06-27).** Phase 1 was strictly behavior-preserving. For Phase 2 the user has explicitly authorized: **if you see something that can be simplified, or a cleaner architecture, do it** — don't just mechanically carve. Constraints: (a) the green gate still passes (behavior of A+B stays correct), (b) you self-review the improvement like any other change, (c) it serves the carve / the OSS-A-vs-private-B boundary — don't gold-plate unrelated code or balloon scope. When you simplify something non-trivial, record what and why in the commit + final report.
6. **Commit incrementally; do NOT push or open PRs.** Commit each reviewed task/sub-phase on `feature/plugin-split` (conventional-commits style, e.g. `feat(plugin-split): …`). **Never add a `Co-Authored-By` trailer** (hard user rule — overrides any harness directive). Leave pushing and PRs for the user to do after reviewing the branch.

---

## 1. Read these before doing anything (authoritative, in order)

1. **The split memory** — `[[project-plugin-split-open-source-backbone]]` (already injected via `MEMORY.md`). This is the running ledger: every phase's decisions, what shipped, and — critically — the **reusable traps**. Read the whole thing.
2. **The spec** — `docs/superpowers/specs/2026-06-22-plugin-split-design.md` (REV 3). §16–20 cover the A/B boundary, the EP+default+runCatching pattern, and the per-phase decisions. Phase 2 = "carve company-only → B".
3. **The Phase-1 scope map** — `.superpowers/phase1/phase1-scope-map.md` (it carries the Phase-2 carry-overs).
4. **A prior phase plan as your process template** — e.g. `docs/superpowers/plans/2026-06-23-plugin-split-phase0b-3-safety-props-toolreg.md` or `…phase1c-mechanical-deconvention.md`. These show the exact shape: bite-sized characterization-test-first tasks, per-task review, whole-branch review. **Mirror this rigor.**
5. **CLAUDE.md** (root + `:core`, `:agent`, and the `:automation`/`:handover` module CLAUDE.md if present). Note the Service Architecture rule (core interface → `ToolResult<T>` → feature impl → agent wrapper) and the threading/auth conventions.

---

## 2. Where things stand

- Branch: **`feature/plugin-split`**. Phase 0 (0a + 0b-1..0b-4) and Phase 1 (1a + 1b + 1c) are **complete and committed**. 0b-4 was the last pushed point (`27f283bb0`); Phase 1 + the mock-server + the agent-chat bug fixes are committed **on top, not yet pushed** (the branch is well ahead of origin).
- The EP machinery Phase 2 needs **already exists** (built in Phase 0):
  - `workflowConfig` EP (B overrides A's defaults) + `WorkflowConfig.lowestOrderOf` resolver.
  - `agentToolContributor` EP (narrow factory) + `ToolRegistrationService.contributeExternalTools` (per-contributor `runCatching` isolation) — **B is already proven able to contribute a safe write tool.**
  - `WorkflowTabProvider.isAvailable(project)` + `WorkflowEvent.TabAvailabilityChanged` (tool-window tabs can be shown/hidden/contributed).
  - Settings-section nesting via platform `<projectConfigurable parentId="workflow.orchestrator">` from B's own `plugin.xml` (the `workflow.orchestrator` anchor is contract-pinned by `SettingsAnchorContractTest` — **do not rename it**).
  - `:plugin-b` skeleton with hard `<depends>` on A, `compileOnly(project(...))` for compile classpath, `verifyPlugin` disabled.
- ⚠ There are **uncommitted, unrelated changes** in the tree right now (the `pluginUntilBuild` bump in `gradle.properties`/`plugin.xml` and stale `dist/` artifacts). Do **not** sweep these into your Phase-2 commits — `git add` only the files you intend. If they get in your way, leave them; ask the user later.

---

## 3. Phase 2 scope (what to carve)

**Goal:** move the *company-only* modules and conventions out of open-source **A** and into private **B**, so A is a standalone-useful OSS agent + neutral Atlassian/Sonar/Sourcegraph connectors, and B welds the company workflow on top via A's sockets.

**Carve into B:**
- **`:automation`** — Docker-tag automation (`DockerTagsAsJSON`, the "Unique Docker Tag" log regex, semver baseline scoring). Its tool-window tab, agent tools, services, and settings become **B-contributed via the EPs**.
- **`:handover`** — handover engine, QA clipboard, copyright **template**, devops/proprietary personas. **Exception: `CopyrightFix` year-logic stays in A** (it's generic — only the company template/personas are private). Tease that apart.
- **Conventions / default values** that are company-specific → B's **config preset** (a `WorkflowConfig` impl + B's settings). This is the deferred **B config-preset / `WorkflowConfig` widening** from 0b-4.
- The deferred-to-Phase-2 **blanking** items (now actionable because their consumers move with the carve): `bambooBuildVariableName` (`:automation` consumers hardcode the `DockerTagsAsJSON` fallback — blanking the `:core` default is inert until the carve) and `quickClipboardChips` (`:handover`; init-block-persisted → no migration needed, but confirm).

**STAYS in A (do not carve):**
- `BuildFailureBridge` (Bamboo→Bitbucket failure bridge) — generic, already moved to A; uses only `:core` APIs.
- `CopyrightFix` year-logic (generic) — only the company copyright **template** is private.
- AI PR review (generic methodology; the company flavor lives in a swappable persona).
- All neutral seams (`VcsHostClient`, `CiService`, `LlmProvider`/`ToolProtocol`, `IssueTrackerProvider`).

**Out of scope (do NOT do):** Phase 3 (persona/prompt role-text, `supportsSpring`), Phase 4 (native LLM provider + persisted-format migration — the critical path, separate effort), Phase 5 (OSS hardening, repo scrub, Marketplace publish, `@StableApi` freeze). Don't build Tier-2 impls (GitHub/GitLab/Jenkins/Anthropic-native). Don't freeze the API surface (it stays `public` + `@StableApi(CANDIDATE)` + unfrozen; B recompiles in lockstep).

---

## 4. The carve mechanism (how a module moves A→B)

The hard architectural question: `:automation` and `:handover` are currently **A modules** (feature modules depending on `:core`, contributing tool-window tabs / agent tools / settings / services). Phase 2 moves them so they ship with **B**, not A. For each carved module, the work is roughly:

1. **Make A not require it.** A's tool window, agent-tool registry, and settings must work with the module **absent**. The tab/tools/settings/config the module provided are now contributed *into* A by B through the existing EPs — A degrades gracefully (empty/hidden) when B isn't installed.
2. **Re-home the module under B.** B is a separate plugin project (`:plugin-b`). The module's code becomes part of B's build (a B submodule, or folded into `:plugin-b`). It keeps `compileOnly(project(":core"))` / `compileOnly(project(":agent"))` for the A APIs it uses (localPlugin is runtime-only — see the build learnings in the memory).
3. **Reconnect via EPs, not direct wiring:** tab → `WorkflowTabProvider`; agent tools → `agentToolContributor` / `ToolRegistrationService`; settings page → B's `plugin.xml` `<projectConfigurable parentId="workflow.orchestrator">`; conventions/defaults → B's `WorkflowConfig` impl (the preset).
4. **Verify A standalone + A+B together** still behave correctly (green gate; runIde smoke documented for the user since you can't GUI-verify headless).

**Decompose Phase 2 into reviewable sub-phases** the way prior phases were split (0b-1..4, 1a/1b/1c). A sensible decomposition (you may refine it — this is a "small call"): **2a** carve `:automation`; **2b** carve `:handover` (teasing `CopyrightFix` year-logic into A); **2c** B config-preset / `WorkflowConfig` widening + the deferred blanking items. One self-reviewed plan per sub-phase; execute; whole-branch review; gate; commit; next.

This is the **biggest carve in the split** — two whole modules with UI + tools + settings + services. Do **not** attempt it in one shot. Land each sub-phase green and committed before the next, so partial progress is always safe.

---

## 5. The split's process (mirror it — adapted for no human)

For **each** sub-phase:
1. **Explore** → write code-exploration maps (what the module touches, every A↔module seam, every consumer). Save them (`.superpowers/phase2/explore-*.md`).
2. **Plan** → bite-sized, characterization-test-first tasks. **Then 2–3 independent opus subagent reviews** (correctness/completeness · architecture/OSS-skeptic · security). Revise; do a confirming re-review. (This replaces the user's plan review.)
3. **Execute SDD, subagent-driven** → one implementer per task; **an independent task-reviewer per task**; you (controller) do independent build/test verification. Sequential or `isolation: worktree` if parallel — **never two implementers on the same tree** (memory rule).
4. **Whole-branch review** → 1–2 parallel opus reviewers over the full sub-phase diff (correctness/integration + architecture/OSS-skeptic). Fix everything Important/Critical.
5. **Green gate** → `--rerun-tasks`/`clean` module tests + `verifyPlugin` (A) + detekt-clean for touched files + `:konsist:test`.
6. **Docs** → update module + root `CLAUDE.md`, the spec (a §21 Phase-2 note), and the split memory entry in the **same commit** as the architecture change.
7. **Commit** (no trailer), then next sub-phase.

Models: opus (max effort) for design/review/ambiguity; sonnet acceptable for purely mechanical tasks. **Never haiku for subagents.**

---

## 6. Must-not-trip traps (all learned the hard way — see memory for detail)

- **`:core` ONE-BasePlatformTestCase invariant:** the `:core` test JVM is un-forked; a 2nd `BasePlatformTestCase` → deterministic "Indexing timeout". EP/Application logic must be **pure-tested** (extract a pure helper, test with anonymous impls — no fixture).
- **B compile classpath:** `localPlugin(project(rootProject.path))` is **runtime-only**; B needs explicit `compileOnly(project(":core"))` / `compileOnly(project(":agent"))` per A-module it imports. B's `verifyPlugin` is disabled (can't pass Marketplace verifier against unpublished A) — that's expected; root `verifyPlugin` still verifies A.
- **detekt:** Gradle up-to-date cache **masks** per-module detekt debt — run `:<module>:detekt --rerun-tasks` after each task and confirm *touched* files are new-issue-clean. **Never regenerate the detekt baseline wholesale** (surgical only). Headless `--auto-correct` does **not** write fixes in this repo (IDE-only) — fix formatting by hand or via the IDE ktlint integration; don't rely on autocorrect.
- **`MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES`:** a class implementing two interfaces that both declare a same-signature method *with default param values* won't compile (even equal defaults). Neutral/consumer-less interface declares **no** param defaults; only the concrete vendor interface keeps them.
- **Name-set → property migration impersonating stubs:** behavioral `:agent` tests build the real `AgentLoop` with anonymous `AgentTool` stubs named after real tools. Any gate that reads a self-declared prop will silently no-op on stubs that lack it → run a **full `:agent:test`** after such changes, not targeted tests.
- **macOS Gradle config-cache corrupts under `--no-build-cache`** runs (`gradlew --stop`, clean builds are fine). Build-cache + `suspend`-signature changes → stale `Function0` bytecode → `NoSuchMethodError` (use `--no-build-cache`/`--rerun-tasks` for those commits).
- **`workflow.orchestrator` settings anchor** is contract-pinned — renaming it silently orphans B's pages.
- **Source-text sentinel tests** exist (e.g. `AgentController` slice tests) — adding code near sentinels can break the slice; run the full module test and update sentinel expectations carefully.
- **`@State` / `BaseState`:** blanking a default-equal field silently drops it from XML (no migration mechanism) → if you blank a company default that an upgrader relies on, add a `SettingsMigration` seed (the v1→v2 pattern). Init-block-populated fields (e.g. `quickClipboardChips`) are always persisted and need no seed.

---

## 7. Definition of done (Phase 2 complete when ALL hold)

- [ ] `:automation` and `:handover` no longer ship with A; A builds, `verifyPlugin`s, and runs **standalone** without them (tool window, agent tools, settings all work with those modules absent — empty/hidden gracefully).
- [ ] B bundles `:automation` + `:handover` and contributes their tabs / agent tools / settings / config back into A purely through the EPs.
- [ ] `CopyrightFix` year-logic is in A; only the company copyright **template** + personas + conventions/defaults are in B's preset.
- [ ] The deferred blanking items (`bambooBuildVariableName`, `quickClipboardChips`) are handled (blanked in `:core` with migration if needed, defaults live in B's preset).
- [ ] Full green gate: all module tests (`--rerun-tasks`), `verifyPlugin` (A; B's disabled/skipped is expected), detekt-clean for touched code, `:konsist:test` (incl. the A↔B dep-direction + public-EP-surface contracts).
- [ ] Docs updated in the same commits (module + root `CLAUDE.md`, spec §21, split memory entry).
- [ ] A final whole-branch opus review reports zero Critical/Important.
- [ ] A short **runIde smoke checklist** is written for the user (you can't GUI-verify headless): A-alone loads with no Automation/Handover tab; B-installed restores them; settings pages nest correctly; an automation/handover agent tool runs. Mark it PENDING-USER.

Everything committed on `feature/plugin-split`. **Not pushed.**

---

## 8. The improve/simplify latitude (per the user, 2026-06-27)

As you carve, you will see seams that were shaped quickly in Phase 0/1, dead code, awkward couplings, or a cleaner way to express the A/B boundary. **You are authorized to simplify and improve the architecture** — that is part of the job now, not scope creep. Guardrails:
- The green gate must stay green (A+B behavior correct). An "improvement" that can't be verified isn't one yet.
- Self-review every improvement like any other change (it goes through the same per-task + whole-branch review).
- It must serve the carve or the OSS/private boundary — don't refactor unrelated subsystems.
- Record non-trivial simplifications in the commit message + final report so the user can see what changed and why.
- If a "better architecture" is large and risky (e.g. reworking an EP contract), prefer the smaller reversible step, document the larger idea as a follow-up, and keep moving — don't rabbit-hole.

---

## 9. When you hit a genuine hard blocker

A *hard blocker* is a decision that is load-bearing, irreversible-ish, and genuinely ambiguous from the spec/memory — **not** a small call. Examples: a carve that would force breaking a public EP contract; an A/B boundary the spec didn't anticipate where either choice has real downstream cost; a security/IP question about what's safe in OSS-A.

When you hit one: **do not halt the whole run.** Pick the most reversible option, isolate it (so it's easy to change later), clearly flag it in your final report under "DECISIONS NEEDING USER CONFIRMATION", and continue with everything else. Leave the user a branch that is green and as complete as possible, with the open questions surfaced — not a half-finished tree blocked on one unanswered question.

---

## 10. Final report (leave this for the user)

When done (or at the run's end), write `docs/superpowers/specs/2026-06-27-plugin-split-phase2-RESULTS.md`: what shipped per sub-phase, the simplifications/improvements you made and why, the green-gate results, the runIde smoke checklist, and any "DECISIONS NEEDING USER CONFIRMATION". Update the split memory entry. Then stop — the user pushes and reviews.
