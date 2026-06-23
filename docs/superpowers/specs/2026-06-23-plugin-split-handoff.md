# Plugin Split — Context Handoff (resume in a new session)

**Date:** 2026-06-23 · **Branch:** `feature/plugin-split` @ `5ca43277b` (**pushed to origin**) · supersedes the 2026-06-22 Phase-0a handoff.

---

## ▶ TL;DR — where we are

Splitting the "Workflow Orchestrator" IntelliJ plugin into **two installable plugins**:
- **Plugin A** (root project, eventually open-source): the configurable engine — AI agent + pluggable LLM backend + de-conventioned Atlassian/Sonar connectors.
- **Plugin B** (`:plugin-b`, private): A pre-configured for this company + company-only modules. `<depends>` on A via extension points — **never a code fork.**

**Done & merged & pushed:** **Phase 0a** (skeleton + mechanism) and **Phase 0b-1** (the LLM-provider seam). **In progress (user):** runIde manual testing. **Next:** triage runIde results → Phase 0b-2/0b-3/0b-4 → Phase 1 de-convention → … → Phase 4 native LLM (critical path) → Phase 5 OSS publish (deferred).

## Git state / how to resume
```bash
git clone git@github.com:thenerdygeek/intellij-workflow-orchestrator.git   # or: cd existing clone
git checkout feature/plugin-split && git pull        # @ 5ca43277b — has EVERYTHING below
```
`main` does NOT have any of this. Everything lives on `feature/plugin-split`. The worktrees used during execution are cleaned up.

---

## ✅ What's complete

### Phase 0a — skeleton + mechanism (8 tasks, merged)
`:plugin-b` Gradle subproject (hard `<depends>` on A) · `@State` settings-migration framework (stamps a `settingsSchemaVersion` sentinel; no default materialization — deferred to Phase 1) · B overrides A's `workflowConfig` EP (lowest-order-wins) · `JiraTicketProvider` resolver `firstOrNull()`→`minByOrNull{order}` · `agentToolContributor` EP (narrow factory) + B's `companyb_noop` stub tool · self-declared `AgentTool.isMutating` for plan-mode safety · konsist contracts (public-EP-surface + A↔B dep-direction).

### Phase 0b-1 — LLM seam `LlmProvider` + `ToolProtocol` (12 tasks, merged)
Behavior-UNCHANGED extraction: `ToolProtocol` (paradigm: presentTools/parseToolCalls/UI-splitter/wire-prefix/drift-flag/classifyStreamLine) + `XmlToolProtocol` (delegates to today's `ToolPromptBuilder`/`AssistantMessageParser`/`MessageSanitizer`/`GatewayErrorDetector`, characterization-pinned) · routed 2 tool-presentation + 3 `AgentLoop` streaming sites through the protocol · `NativeProtocol` interface-only (impl = Phase 4) · drift machinery gated on `requiresDialectGuard` at the `consumeDialectDriftFlag` chokepoint (6 sites; XML always-true ⇒ identical) · `LlmProvider extends LlmBrain` (catalog/capability/context-window + `classifyStreamLine`/`classifyHttpError`/`getStatus`/`buildFallbackChain` + `toolProtocol`) · `XmlLlmProvider` absorbs `BrainRouter` image routing (additive/unused in 0b-1; same-instance cancel) · `ApiMessage.protocol` discriminator reserved (no migration) · `@InternalApi` retention BINARY→RUNTIME.

**Quality:** every task got implementer + independent reviewer + controller build/test verification; a final opus whole-branch review (READY TO MERGE); full green gates (160 tasks); detekt-green (ktlint autocorrected, not baselined).

### Key committed artifacts
- **Spec:** `docs/superpowers/specs/2026-06-22-plugin-split-design.md` (rev 3, + 2 corrections folded 2026-06-23).
- **Plans (force-tracked):** `docs/superpowers/plans/2026-06-22-plugin-split-phase0a.md`, `docs/superpowers/plans/2026-06-23-plugin-split-phase0b-1-llm-seam.md` (rev 2).
- **runIde test docs:** `PHASE-0A-SMOKE-TESTS.md` (plugin-split smokes, no tokens), `RUNIDE-TEST-SCENARIOS.md` (19 prioritized whole-plugin scenarios with real success/failure log oracles).

---

## ⏳ What's next (the queue)

1. **runIde testing (user is doing this).** Command: `gradlew.bat :plugin-b:runIde` (loads BOTH plugins — root `runIde` loads only A). Docs: `PHASE-0A-SMOKE-TESTS.md` then `RUNIDE-TEST-SCENARIOS.md`. The two-plugin GUI smokes (B loads / override wins / `companyb_noop` registers) are still **PENDING-USER** (can't run headless). When the user shares an `idea.log`, match it to the catalog's failure signatures (or diagnose new). runIde may surface **pre-existing** plugin issues too — triage whether pre-existing vs split-introduced.
2. **Phase 0b-2** — `VcsHostClient` + `CiService` connector seams (neutral interfaces over Bitbucket DC / Bamboo; Atlassian impls in A). Plan + multi-round review, then subagent-driven execution.
3. **Phase 0b-3** — project-scoped `ToolRegistrationService` (narrow host for *production* B tools that need `spillOrFormat`) + remaining `AgentTool` safety props (`requiresApproval`/`HOOK_EXEMPT`).
4. **Phase 0b-4** — settings-section contribution EP + B's config preset.
5. **Phase 1** — de-convention → settings (uses the Phase-0a `settingsSchemaVersion` sentinel before blanking any default). **Phase 2** carve company-only → B. **Phase 3** persona/prompt. **Phase 4** native LLM (Anthropic-direct) — the **critical path**. **Phase 5** OSS hardening + publish (DEFERRED; new repo + gitleaks + scrub api-docs JSON + Apache NOTICE).

### ⚠ Phase-4 carry-forward checklist (from the 0b-1 review — do these when the native provider lands)
- (a) `XmlLlmProvider` MUST delegate `toolNameSet`/`paramNameSet` when the loop is rewired to consume the provider (else tool decode breaks — they feed `SourcegraphChatClient`/`OpenAiCompatBrain`/`BrainRouter`).
- (b) wire `classifyStreamLine` in the transport (`SourcegraphChatClient` still calls `GatewayErrorDetector` directly).
- (c) relocate `toolNameSet`/`paramNameSet` onto the protocol (transport-coupled — deferred from 0b-1).
- (d) widen `parseToolCalls` streaming surface (`CharSequence` → structured `content_block_delta`/`input_json_delta`) for native.
- (e) `AnthropicDirectProvider` impl + persisted-message-format migration + populate the `ApiMessage.protocol` discriminator + proxy-aware native HTTP client.

---

## ⚠ Reusable learnings / traps (cost real green-gate failures — honor these)
- **`localPlugin(project(rootProject.path))` is runtime/sandbox-only** — it does NOT put A's modules on B's COMPILE classpath. B needs explicit `compileOnly(project(":core"))` / `compileOnly(project(":agent"))` (compile-against, NOT bundled — A ships them at runtime).
- **Private B can't pass the Marketplace Plugin Verifier** (it hard-depends on local, unpublished A) → B's `verifyPlugin` task is disabled; `verifyPlugin` verifies plugin A only. `:plugin-a` extraction would NOT fix this (deferred; user chose to keep `localPlugin(root)`).
- **`:core` ONE-`BasePlatformTestCase` invariant** — its test JVM is un-forked; a 2nd platform fixture → deterministic headless "Indexing timeout" (#51). **Pure-test seam/EP logic** (extract a pure helper, e.g. `lowestOrderOf`/`XmlToolProtocol`), never add a 2nd `BasePlatformTestCase` to `:core`.
- **detekt/ktlint: AUTOCORRECT, do NOT baseline** (`./gradlew :<m>:detekt --auto-correct`; the project's `detekt.yml` has `autoCorrect:false`, so apply formatting fixes then verify). `verifyPlugin`/tests do NOT run detekt — CI `check` does, so make detekt green before declaring done.
- **`--no-build-cache --rerun-tasks`** on any commit changing a lambda/function type to/from `suspend` OR adding a ctor param (Gradle compile-avoidance → stale `Function`-bytecode `NoSuchMethodError`).
- **No `runBlocking` in `main/`** (pre-commit hook; use `runBlockingCancellable`).
- New EP/seam interfaces B implements are **`public` + `@InternalApi`** (NEVER `internal` — module-scoped, B can't compile against it).
- **Webview build noise:** gradle regenerates `agent/src/main/resources/webview/dist/` (content-hash churn) on builds — never `git add` it; `git checkout -- agent/src/main/resources/webview/dist/` to drop it before committing.
- **Don't trust the implementer's report** — the controller's independent clean build caught a Task-1 "verifyPlugin is fine" misdiagnosis (it was actually broken) and the full-suite green gate caught a `:core` indexing-timeout that all focused tests passed. Run the authoritative build yourself.

## Standing rules (from the user — keep)
- **Multiple INDEPENDENT review rounds at EVERY step** ([[multi-round-review-plugin-split]]) — not one pass. Reaffirmed 2026-06-23. The per-task two-stage review (implementer self-review + independent task reviewer) + a final whole-branch review satisfies it for execution; plans get multi-lens review (accuracy/bytecode + completeness + skeptic).
- **Subagent models:** sonnet = floor, opus = hardest/critical (final reviews, keystone tasks); **NEVER haiku** ([[subagent-model-no-haiku]]).
- **Process:** plan → multi-round review → `superpowers:subagent-driven-development` in a worktree off `feature/plugin-split` → per-task review → final whole-branch review → `finishing-a-development-branch` (ff-merge + push). Track progress in `.superpowers/sdd/progress.md` (gitignored ledger; the recovery map across compaction).

## Memory (auto-loaded each session)
- `project-plugin-split-open-source-backbone` — the full decision log + current status (updated through 0b-1 merged/pushed).
- `multi-round-review-plugin-split` — the review rule. · `subagent-model-no-haiku` — model floor.

## To resume in a new session, say e.g.
- "Continue the plugin split — start Phase 0b-2 (VcsHostClient/CiService): write the plan, multi-round review, then execute subagent-driven." OR
- "I ran runIde — here's the log: …" (paste) — and I'll diagnose against `RUNIDE-TEST-SCENARIOS.md`.
