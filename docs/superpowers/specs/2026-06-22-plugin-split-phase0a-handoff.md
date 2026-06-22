# Plugin Split — Phase 0a Execution Handoff

**Date:** 2026-06-22 · **For:** a fresh session picking up implementation · **Branch:** `feature/plugin-split`

---

## ▶ IMMEDIATE NEXT ACTION

Execute **Phase 0a** of the plugin split, **subagent-driven**, in an **isolated git worktree** off `feature/plugin-split`.

1. Invoke skill **`superpowers:using-git-worktrees`** → create a worktree off `feature/plugin-split`.
2. Invoke skill **`superpowers:subagent-driven-development`** with the plan below. Run Task 1 → review → Task 2 → review → … (8 tasks). Surface each task at a checkpoint for the user.
3. **The plan file:** `docs/superpowers/plans/2026-06-22-plugin-split-phase0a.md` — **rev 2**, 8 bite-sized TDD tasks, exact code/paths/commands. (Now force-tracked in git so it appears in the worktree.) **Read it fully before starting.**

**Standing rule from the user (hard requirement):** *every step gets multiple independent review rounds.* Subagent-driven development's per-task two-stage review satisfies this for execution; honor it. Also: **never use haiku for dispatched subagents** (sonnet = floor, opus = hardest).

---

## What this project is

Split the IntelliJ "Workflow Orchestrator" plugin into **two installable plugins**:
- **Plugin A (open-source eventually):** a **configurable engine** — the AI agent + a pluggable LLM backend + de-conventioned Atlassian/Sonar connectors. Any team on the supported stack configures it.
- **Plugin B (private):** plugin A **configured for this company** (a settings preset) **+ company-only code** (`:automation`, `:handover`, etc.). `<depends>` on A via extension points; **never a code fork**.

Mental model: A is a configurable engine; B is "A pre-configured for us + our bespoke modules."

## Key decisions (full detail in the spec)

**Spec:** `docs/superpowers/specs/2026-06-22-plugin-split-design.md` — **rev 3** (committed). Read it for the full rationale. Highlights:
- **Sequencing = internal-first.** Build the two-plugin architecture + de-convention + the LLM seam, ship A+B **privately**. A's EP surface is **`public` + `@InternalApi` (unfrozen-by-policy)** — *never `internal`* (B can't compile against `internal` across a plugin boundary), and **not** `@StableApi(CANDIDATE)` (that level doesn't exist — the real annotations are `@StableApi(since: String)` and `@InternalApi` in `core/.../core/api/ApiStability.kt`). Freezing the API for external consumers + Marketplace publish = a **deferred Phase 5**.
- **Configurable engine, 3 tiers:** Tier-1 plain settings (config A; no code) · Tier-2 pluggable impl via EP (still not a fork) · Tier-3 genuinely new code (plugin B only). Most company conventions become Tier-1 settings.
- **LLM pluggability = first-class workstream + the critical path.** Two-layer seam `LlmProvider` (transport) + `ToolProtocol` (XML-in-content for Sourcegraph vs native function-calling for Anthropic). It's WIDER than a 3-method seam (system-prompt assembly + streaming segmentation + persisted-message-format migration). **Tier-1 native = Anthropic-direct only**; Bedrock/Vertex = Tier-2 (SigV4/OAuth2 ≠ static-header; their SDKs bypass IdeProxy/IdeTrust).
- **Scope:** IntelliJ IDEA only (hard `com.intellij.modules.java` dep); Java/Kotlin + Python; Jira/Bitbucket/Bamboo **Server/DC** (Cloud not supported); SonarQube.
- **Effort:** ~4–6 months whole-program; **Phase 4 (native LLM) is the critical path**, not Phase 0.
- **Security (for the deferred public release):** scrub `agent/.../resources/api-docs/*.json` (real server versions, org metrics, `DockerTagsAsJSON`, plan keys) + `:mock-server` Kotlin data factories; A ships from a **NEW repo** (the current GitHub repo is already public); add an Apache-2.0 **NOTICE** (the agent is a Cline port).

**Phases:** **0a** (this — skeleton + mechanism) → **0b** (wide LlmProvider/ToolProtocol + VcsHostClient/CiService seams) → **1** de-convention→settings → **2** carve company-only→B → **3** persona/prompt hardening → **4** native LLM (critical path) → **5** OSS hardening+publish (deferred).

## Phase-0a tasks (the plan has full TDD detail for each)

1. `:plugin-b` skeleton — new Gradle subproject, full platform plugin, hard `<depends>`, `localPlugin(project(rootProject.path))` (verify; `:plugin-a` fallback).
2. `@State` settings-migration **framework** — stamp a `settingsSchemaVersion` sentinel (do **NOT** materialize defaults — proven serialization no-op); real XML round-trip test; default-preservation deferred to Phase 1.
3. Prove B overrides A's `WorkflowConfig` EP (order-wins) + **runIde two-plugin smoke (hard gate)**.
4. `JiraTicketProvider` resolver → lowest-order-wins (interface default `0`, shipped impl `Int.MAX_VALUE`).
5. `agentToolContributor` EP (narrow factory in `agent/.../tools/contribution/`) + B contributes a stub tool + **runIde smoke** (also proves B compiles against `:agent`).
6. Self-declared `isMutating` on `AgentTool` for plan-mode write-safety (the guard at `AgentLoop.kt:1942-1944`).
7. konsist contracts — public EP surface (with a count self-guard) + A↔B dep-direction + exclude `:plugin-b` from A's scans.
8. Green gate — all modules + `verifyPlugin`.

## ⚠ Critical gotchas the executor MUST respect (all verified against the real code)

- **`@State` (Task 2):** `BaseState` omits value-equals-default fields from XML; `state.x = state.x` is a no-op. Phase-0a only stamps the sentinel; the unit test alone would pass green while broken — the **XML round-trip test is mandatory**.
- **`BasePlatformTestCase`:** extends JUnit-3 → methods must **start with `test`** (no `@Test`); **one test method per class** (a 2nd hangs on "Indexing timeout").
- **Verify-flags in the plan (resolve while implementing, don't skip):** the `localPlugin` form for 2.12.0 (no `(Project)` overload — use `project(rootProject.path)`, fallback `:plugin-a`); whether B's `localPlugin(A)` exposes `:agent` classes to B's compiler (if not, add `compileOnly(project(":agent"))`); whether `runIde` loads the second local plugin (may need run-config); whether `XmlSerializer` round-trips `BaseState` in 2025.1.
- **`--no-build-cache --rerun-tasks`** on any commit changing a lambda type to/from `suspend`.
- **No `runBlocking` in `main/`** (pre-commit hook) — use `runBlockingCancellable`.
- Tool packages: 7 write-tools in `tools/builtin/`, 3 (`FormatCodeTool`/`OptimizeImportsTool`/`RefactorRenameTool`) in `tools/ide/`.

## Review history (why the artifacts are trustworthy)

- **Spec:** Round 1 (4 reviewers) + Round 2 (3 reviewers) — caught the `internal` contradiction, the native-LLM transport/persistence underestimate, the security api-docs leak, the inverted `@State` migration risk, `BuildFailureBridge`→A. All folded into rev 3.
- **Plan:** reviewed by `plan-accuracy` (bytecode-verified — caught the 2 BLOCKERs: the `@State` no-op + a non-compiling test) and `plan-completeness` (caught the missing two-plugin runtime smoke, the `ToolRegistrationService` gap, etc.). `plan-skeptic` got stuck; its angle (cross-module compile, runIde) is covered as verify-flags. All folded into rev 2. One reviewer item rejected after verification (the Task-4 `order` default is correct as written).

## Git state

- Branch: **`feature/plugin-split`** (off `main` @ `38e7ee9c2`).
- Commits: `0212b7bec` (spec) · `b90040e2d` (@InternalApi correction) · + this handoff & the plan (this commit).
- The plan was force-tracked (the `docs/superpowers/plans/` dir is gitignored) so it travels into the worktree.

## Memory (auto-loaded each session)

- `project-plugin-split-open-source-backbone` — the full decision log.
- `multi-round-review-plugin-split` — the "multiple review rounds at every step" rule.
- `subagent-model-no-haiku` — sonnet floor / opus hardest for dispatched agents.
