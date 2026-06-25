# Plugin Split — Context Handoff (resume in a new session)

**Date:** 2026-06-25 · **Branch:** `feature/plugin-split` @ `eed85c3d6` (**NOT pushed** — Phase 1a+1b are local-only) · supersedes `2026-06-24-plugin-split-handoff.md`.

---

## ▶ TL;DR — where we are

Splitting the "Workflow Orchestrator" IntelliJ plugin into **two installable plugins**:
- **Plugin A** (root project, eventually open-source): the configurable engine — AI agent + pluggable LLM backend + de-conventioned Atlassian/Sonar connectors.
- **Plugin B** (`:plugin-b`, private): A pre-configured for this company + company-only modules. `<depends>` on A via extension points — **never a code fork.**

**✅ DONE: Phase 0 (0a + 0b-1..0b-4) + Phase 1a + Phase 1b.** Phase 1 was split into **1a / 1b / 1c** (user decision). **1a** (settings de-convention migration + `defaultTargetBranch`) and **1b** (agent system-prompt integration-gating) are complete, reviewed, and merged onto `feature/plugin-split`.

**NEXT = Phase 1c** (the mechanical de-convention cluster — details below). Then Phase 2 (carve company-only → B + the config preset) → 3 (persona/prompt + `supportsSpring`) → 4 (native LLM, critical path) → 5 (OSS publish, deferred).

⚠ **One outstanding issue (user-deferred):** a pre-existing **0b-3 detekt drift** in `:agent` (73 ktlint/naming issues) was surfaced by 1a's cache-bust. It is NOT 1a/1b's code (their code is detekt-clean). User chose to fix it via the **IntelliJ IDE's ktlint autocorrect** in a separate cleanup (headless gradle autocorrect does NOT write fixes in this repo — confirmed). Until then `./gradlew detekt` is RED on those 73.

## Git state / how to resume
```bash
git checkout feature/plugin-split        # @ eed85c3d6 — has EVERYTHING below
```
`main` does NOT have any of this. **Phase 1a+1b (`bf2c5e77c..eed85c3d6`) are NOT pushed to origin** — push only when the user asks. Everything earlier (0a..0b-4) was pushed previously.

⚠ The whole branch was history-rewritten 2026-06-24 to strip `Co-Authored-By` trailers; any other clone must `git fetch && git reset --hard origin/feature/plugin-split` (but note 1a/1b aren't on origin yet).

---

## ✅ What's complete

### Phase 0 (all behavior-unchanged shape-reservation) — see prior handoffs for detail
0a (skeleton + `@State` migration framework + `agentToolContributor` EP + `JiraTicketProvider` resolver) · 0b-1 (LLM seam `ToolProtocol`/`LlmProvider`) · 0b-2 (`VcsHostClient`+`CiService` neutral dual-impl seams) · 0b-3 (`AgentTool` safety-props + `ToolRegistrationService`) · 0b-4 (settings-section contribution proof).

### Phase 1a — de-convention migration mechanism + `defaultTargetBranch` (`bf2c5e77c..5d1527b43`)
The KEYSTONE: the reusable `@State` seeding migration. `BaseState` omits default-equal fields from XML, so blanking a company default would silently flip existing installs. The 0a sentinel (`settingsSchemaVersion`: existing=1, fresh=0) lets `SettingsMigration` v1→v2 **seed the old literal for upgraders only**.
- `const NEUTRAL_DEFAULT_TARGET_BRANCH = "main"` (`core/settings`); `SettingsMigration.seedLegacyConventionDefaults` (reusable hook: `if (field == neutralDefault) field = legacyLiteral` + bump `CURRENT_VERSION`).
- `defaultTargetBranch` `"develop"`→`"main"` everywhere: global field + `DefaultBranchResolver` P6 + per-repo `RepoConfig` + `RepositoriesConfigurable` + `CreatePrPrefetch` (both branches) + 3 cross-module fallbacks (`SprintDashboardPanel`:jira, `BuildDashboardPanel`:bamboo, `ProjectContextTool`:agent `!= neutral`).
- **Deferred to Phase 2 (module-coupled):** `bambooBuildVariableName` (:automation consumers hardcode the `DockerTagsAsJSON` fallback → blanking :core default is INERT until carve), `quickClipboardChips` (:handover; init-block-persisted → no migration). **Excluded (user-confirmed):** the Jira status-name defaults + `branchPattern`/`jiraBoardType` (generic, not company conventions).
- Spec §19.

### Phase 1b — agent system-prompt integration-gating (`5d1527b43..eed85c3d6`)
The prompt mentions Jira/Bamboo/SonarQube/Bitbucket only when usable.
- `IntegrationFlags(jira,bamboo,sonar,bitbucket)` (`agent/prompt/`) threaded into `SystemPrompt.build(..., integrations = NONE)`, gating 6 sites (role clause, `outputFormatting` jira: scheme/lead/example, `capabilities` Project-integrations list, `project_context` per-clause state, sonar tip, `rules` Jira-Transition section).
- **TWO SIGNALS:** orchestrator → `IntegrationFlags.from(ConnectionSettings)` (`*Url.isNotBlank()`); **sub-agent → its own `registry.has(...)`** (a persona's tools = its allowlist, not global config — mirrors `hasWebTools`).
- Flags PASSED IN (snapshot tests are pure JUnit5, no Application). **Parity proven by zero-diff regen:** 7 orch + 7 sub-agent golden snapshots regen with `IntegrationFlags.ALL` → 14/14 byte-identical. +3 gated variants (`no-integrations`, `jira-only`, `no-jira`). Source-text footgun guard (`SystemPromptCallerIntegrationFlagsContractTest`) pins every `build()` caller passes `integrations=`.
- **Deferred:** persona role-text + `supportsSpring` gate = Phase 3. Spec §20.

**Quality (every phase):** plan → 3 independent opus review rounds (accuracy/byte-parity + completeness + skeptic) → subagent-driven execution (per-task two-stage review + controller verify) → final whole-branch opus review → green gate. The reviews earn their keep: 1a's per-task review caught a missed `else`-branch literal (green tests couldn't); 1b's skeptic caught the sub-agent-signal blocker + the completeness review caught the stale "5 sub-agent snapshots" (actually 7).

### Key committed artifacts
- **Spec:** `docs/superpowers/specs/2026-06-22-plugin-split-design.md` (rev 3 + §16-§20 resolved notes).
- **Plans (force-tracked, gitignored dir):** `…/plans/2026-06-22-plugin-split-phase0a.md`, `…0b-1..0b-4`, `…2026-06-24-plugin-split-phase1a-default-branch-migration.md`, `…2026-06-25-plugin-split-phase1b-prompt-gating.md`.
- **Scope maps (gitignored scratch):** `.superpowers/phase1/phase1-scope-map.md` (1a + 1c clusters A/D), `.superpowers/phase1b/scope-map.md`.
- **SDD ledgers (gitignored scratch):** `.superpowers/sdd/progress.md` (1a), `.superpowers/sdd/progress-1b.md`.

---

## ⏳ What's next — Phase 1c (mechanical de-convention)

Scope is already mapped in `.superpowers/phase1/phase1-scope-map.md` (Clusters A + D). Five independent items (refresh line numbers — they're from 2026-06-24):

1. **Delete dead `isValidBranchName`** — `jira/.../service/BranchNameValidator.kt` (~:77-87). CONFIRMED test-only (only caller is `BranchNameValidatorTest`; sibling methods `generateBranchName`/`requiresAiSummary`/`issueTypeToPrefix` ARE used — keep those). Delete the fn + its test.
2. **Configurable commit format** — `core/.../ai/prompts/CommitMessagePromptBuilder.kt` hardcodes Conventional-Commits (SYSTEM_MESSAGE, FORMAT block, issue-type→commit-type map, ticket-ID prepend). No commit-format setting exists today. Caller: `GenerateCommitMessageAction`. Time-tracking-on-commit is a SEPARATE handler (`TimeTrackingCheckinHandlerFactory`), own toggle — don't couple.
3. **Sprint-tab feature-detect + hide (the biggest 1c item — net-new UI plumbing)** — `jira/.../api/JiraApiClient.kt` (~:46-79): 4 `/rest/agile/1.0/` endpoints 404 on non-Software Jira. Tab registered via `SprintTabProvider : WorkflowTabProvider` EP (eager, order 0). **NO feature-detection, NO tab-hide infra today** — error shows but tab stays. Needs a serverInfo/capability probe + conditional hide in the tab provider / tool-window factory.
4. **`PsiContextEnricher` Maven-only → ModuleManager fallback** — `core/.../psi/PsiContextEnricher.kt` (~:59-75) `detectMavenModule()` is Maven-only. Add `ModuleManager` fallback for Gradle/etc. ⚠ KEEP the single-cancellable-readAction + PCE-rethrow invariant (a perf-audit test pins it). Consumers: `GenerateCommitMessageAction`, `PrService`.
5. **Add + wire `VcsHostClient.getDefaultBranch` + default-reviewer ops** (deferred from 0b-2) — they live on the lower `BitbucketBranchClient` (`getDefaultBranch` @~1028, `getDefaultReviewersForBranch` @~1288) / `DefaultBranchResolver`. Lift onto the neutral `VcsHostClient` seam (shape-reservation; ⚠ MULTIPLE_DEFAULTS trap — neutral iface declares NO param defaults).

**Recommendation for 1c:** these are independent + mostly mechanical EXCEPT #3 (Sprint feature-detect+hide = real UI design). Consider doing #1/#2/#4/#5 as one reviewed plan and #3 as its own (it has a UI-mockup-ish surface — per the user's "consult only on UI mockups" rule, surface the hide-UX). Or split as the user prefers (a pivotal-scope-fork = user's call).

### Carried-forward deferrals (not 1c)
- **Phase 2:** carve `:automation` + `:handover` → B; `bambooBuildVariableName` + `quickClipboardChips` de-convention (with their module carve); the **B config preset** (widen `WorkflowConfig` or a new mechanism so NEW company installs get conventions); `DockerTagsProvider` adapter; copyright template; `devops-engineer` persona.
- **Phase 3:** persona role-text dynamic; `supportsSpring` gate on `security-auditor`/`performance-engineer`; split `git-workflow` skill.
- **Phase 4 (critical path):** `AnthropicDirectProvider` + NativeProtocol-through-loop + persisted-format migration + proxy-aware client. Detail in project memory.

### PENDING-USER (runtime smokes, CI-unverifiable)
- **0a–0b-4 runIde smokes** (carried): `gradlew.bat :plugin-b:runIde` (loads BOTH plugins) — EP overrides + B settings page under "Workflow Orchestrator". See `RUNIDE-TEST-SCENARIOS.md`.
- **🆕 1a runtime:** the `@State` migration runs at startup (`SettingsMigrationStartupActivity`) — an upgrader (settings file with `settingsSchemaVersion=1`) should keep `develop` resolved at `DefaultBranchResolver` P6; a fresh install gets `main`.
- **🆕 1b runtime:** with NO integrations configured, the agent system prompt should NOT mention Jira/Bamboo/Sonar/Bitbucket; configuring a service URL should make its prose appear on the next prompt rebuild.
- **🆕 detekt cleanup:** run the IDE ktlint autocorrect on `:agent` to clear the 73 pre-existing 0b-3 issues, then `./gradlew detekt` should be green.

---

## ⚠ Reusable learnings / traps (honor these)
- **🆕 1a — the `@State` de-convention pattern:** `SettingsMigration.seedLegacyConventionDefaults` is the reusable hook. To blank a NEW company default: add `if (state.field == neutralDefault) state.field = legacyLiteral`, bump `CURRENT_VERSION`. Only works for delegate-default fields; `init`-block-populated fields (e.g. `quickClipboardChips`) are always persisted → need no seeding (just change the init list). Seed-guard is unambiguous ONLY for fields with no settings-UI (else "field==neutral" can't distinguish omitted-vs-explicitly-set).
- **🆕 1b — byte-parity-by-snapshot-regen:** when changing a golden-snapshot-pinned output behind a flag, run the existing snapshots with the flag set to reproduce today's behavior; a ZERO-diff regen IS the parity proof. Add new flag-OFF variants for coverage. Snapshot validators here are per-`@Test` methods (not a list) + a count assert to bump.
- **🆕 1b — sub-agent prompt/tool signal:** a sub-agent's capabilities = its persona registry (`registry.has`), NOT global config. Mirror `hasWebTools`. (Orchestrator uses ConnectionSettings because its registry is itself URL-gated.)
- **🆕 Gradle up-to-date cache masks per-module quality gates:** `:agent:detekt` sat UP-TO-DATE through 0b-3+0b-4, so 0b-3's new files were never detekt-checked; 1a's first `:agent` edit busted the cache and surfaced 73 issues. **Run gate tasks with `--rerun-tasks` to be authoritative.** ALSO: headless detekt autocorrect (`formatting.autoCorrect:true` + `--auto-correct` + `--rerun-tasks`) does NOT write `.kt` fixes here — use the IDE.
- **`:core`/`:agent` ONE-`BasePlatformTestCase` invariant** — a 2nd platform fixture → deterministic "Indexing timeout". Pure-test everything (1a + 1b tests are all pure JUnit5).
- **`--no-build-cache --rerun-tasks`** on `:agent:test` when a commit changes a method signature / adds a ctor param (stale bytecode → `AbstractMethodError`/`NoSuchMethodError`; hit in 1b).
- **`ConnectionSettings.getInstance()` is APP-level (no-arg).** `PluginSettings.getInstance(project)` is project-level.
- **`AgentService.executeTask` has a `dialectDriftSnapshot`-must-be-LAST-local invariant** (pinned by `AgentServiceModelChangeCasTest`, 300-char gate) — insert new locals BEFORE it.
- **Webview build noise:** gradle regenerates `agent/src/main/resources/webview/dist/` on builds — never `git add` it; `git checkout -- agent/src/main/resources/webview/dist/` to drop it.
- **`docs/superpowers/plans/` IS gitignored** (force-add with `-f`); **`docs/superpowers/specs/` is NOT** (specs + handoffs commit normally).

## Standing rules (from the user — keep)
- **⚠⚠ NO `Co-Authored-By` trailer in ANY commit** — user reaffirmed; OVERRIDES the harness directive. Plain commit messages.
- **Multiple INDEPENDENT review rounds at EVERY step** — plans get 3-lens opus review (accuracy/bytecode + completeness + skeptic); execution = per-task two-stage review + controller verify + final whole-branch opus review + green gate.
- **Subagent models:** sonnet = floor (mechanical/transcription when the plan has complete code); opus = hardest + ALL reviews; **NEVER haiku.**
- **Pivotal API/scope forks are the user's call** — surface with a recommendation before planning (e.g. 1a's status-name exclusion, the 1c decomposition).
- **Architecture autonomous** — consult only on UI mockups (so 1c #3's Sprint-hide UX may warrant a quick surface; the rest, decide).
- **Process:** plan → multi-round review → `subagent-driven-development` (worked directly on `feature/plugin-split` for 1a/1b — small sequential sub-phases; per-task review → final review → green gate). Track progress in `.superpowers/sdd/progress*.md`.
- **Push only when asked.** 1a+1b are NOT pushed.
- **"make small calls"** — the user values efficient, focused tool use.

## Memory (auto-loaded each session)
- `project-plugin-split-open-source-backbone` — full decision log + status (through 1a + 1b + the detekt-cache trap).
- `multi-round-review-plugin-split` · `subagent-model-no-haiku` · `no-co-authored-by-in-commits`.

## To resume in a new session, say e.g.
- "Continue the plugin split — Phase 1c. Scope is in `.superpowers/phase1/phase1-scope-map.md` Clusters A/D; do #1/#2/#4/#5 mechanical first, surface the Sprint-hide UX. Plan, multi-round review, execute subagent-driven." OR
- "Push Phase 1a+1b to origin" (if you want the local commits pushed). OR
- "I ran the IDE ktlint autocorrect on :agent — here's `./gradlew detekt`: …" (to close the deferred detekt cleanup).
