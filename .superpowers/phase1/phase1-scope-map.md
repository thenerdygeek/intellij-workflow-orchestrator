# Phase 1 De-convention — Current-State Scope Map (2026-06-24)

Branch: `feature/plugin-split`. Refreshed against real code (spec line numbers from 2026-06-22 were ~off-by-a-few but all targets located). Source: 4 parallel Explore agents.

---

## Cluster A — Branch default + VcsHostClient ops

**`defaultTargetBranch = "develop"` — 8 sites:**
- `core/.../settings/PluginSettings.kt:68` — `var defaultTargetBranch by string("develop")` (the root global default; `State : BaseState`)
- `core/.../settings/RepoConfig.kt:12` — per-repo `by string("develop")`
- `core/.../util/DefaultBranchResolver.kt:230` — **P6 last-resort fallback** `?: "develop"`
- `.../CreatePrPrefetch.kt:308,311` — `.ifBlank { "develop" }`
- `.../RepositoriesConfigurable.kt:114,392,511` — UI field defaults

**DefaultBranchResolver** already does a 6-tier chain: P1 per-branch override → P2 existing-PR target → P3 merge-base → P4 `BitbucketBranchClient.getDefaultBranch()` → P5 `origin/HEAD` detection → **P6 settings fallback (hardcoded `"develop"`, line 230)**. So neutral-default work is concentrated at P6 + the settings field default.

**`BitbucketBranchClient`** (core/.../bitbucket/): `getDefaultBranch(projectKey, repoSlug)` @1028 exists; `getDefaultReviewersForBranch(repo, source, target)` @1288 (branch-aware) + `getDefaultReviewers` @1268 exist. `fromConfiguredSettings()` @783.

**`VcsHostClient`** (core/.../services/VcsHostClient.kt): 35 methods, `@InternalApi`, dual-impl by `BitbucketServiceImpl`. **NO `getDefaultBranch` / reviewer ops yet** — Phase-1 adds them as shape-reservation (no consumer needed). ⚠ MULTIPLE_DEFAULTS trap: neutral iface declares NO param defaults.

---

## Cluster B — System-prompt + persona gating (:agent)

**`agent/.../prompt/SystemPrompt.kt`** — unconditional stack leaks:
- :337 `agentRole()` — "...enterprise integrations (Jira, Bamboo, SonarQube, Bitbucket)."
- :398,:403 `outputFormatting()` — Jira ticket markdown-link scheme
- :521 `capabilities()` — integration tool list row
- :537 `capabilities()` — "active Jira ticket, ... Sonar quality gate"
- :540 `capabilities()` — sonar local_analysis hint
- :856-867 `rules()` — "Jira Transition — Field Collection Pattern" section

11-section prompt built by `SystemPrompt.build()`. **Tools are ALREADY gated** via `ConnectionSettings` URL checks in `AgentService.reregisterConditionalTools()` (jiraUrl/bambooUrl/sonarUrl/bitbucketUrl `.isNotBlank()`). Phase 1 extends that exact pattern to the prompt: add `jira/bamboo/sonar/bitbucketConfigured` booleans to `build()`, resolved from ConnectionSettings, thread through `SubagentSystemPromptBuilder` too. Snapshot tests (~7 variants) will need updating.

**Persona role text:** bundled `agent/src/main/resources/agents/*.md`; `AgentConfigLoader.filterByIdeContext` already gates spring-boot/python by IdeContext. ⚠ Spec's `supportsSpring` gate on security-auditor/performance-engineer is **Phase 3**, not Phase 1 — Phase 1 persona work = none / dynamic-role-text only.

---

## Cluster C — Settings + migration (HIGHEST RISK, keystone)

**Migration framework (built 0a) is a NO-OP sentinel stamp:**
- `PluginSettings.kt:15` — `var settingsSchemaVersion by property(0)`
- `core/.../settings/SettingsMigration.kt` — `migrate(state)`: if `>= CURRENT_VERSION(1)` return false; else stamp `= 1`, return true. **No seeding yet.**
- `SettingsMigrationStartupActivity` runs it (registered `plugin.xml:282` postStartupActivity).
- Tests (`SettingsMigrationSerializationTest`) PROVE `defaultTargetBranch` stays `"develop"` (omitted from XML; not materialized) — Phase 1 must add seeding.

**Company-default fields to blank in `PluginSettings.State` (`SimplePersistentStateComponent`, omits default-equal fields):**
- `defaultTargetBranch = "develop"` (:68)
- `bambooBuildVariableName = "DockerTagsAsJSON"` (:126)
- `quickClipboardChips` (:331 + init block :519-525) — 8 entries incl `docker.tag, docker.tagsJson, automation.url`
- `branchPattern = "feature/{ticketId}-{summary}"` (:33)
- `jiraBoardType = "scrum"` (:80)
- `ticketTransitionDefaultStartWorkStatusName = "In Progress"` (:162)
- `ticketTransitionDefaultPrCreateStatusName = "In Review"` (:170)
- `postCommitTransitionTriggerStatuses = "to do,open,new,backlog,selected for development"` (:181)

⚠ **Surprise:** `WorkflowIntent` enum (`core/.../workflow/WorkflowIntent.kt`) hardcodes status *aliases* ("In Progress"/"In Review"/"Done"/…) as class members, NOT settings. These are state-classification fallbacks — arguably generic Jira statuses, not company-specific. Decide in plan whether to touch.

**B preset hook:** `WorkflowConfig` EP (`core/.../config/WorkflowConfig.kt`, `@StableApi(since="0.86")`) only exposes `baseUrl(service): String` (URLs). It does NOT carry non-URL company values (defaultTargetBranch, bambooBuildVariableName, …). `DefaultWorkflowConfig` (order=MAX) reads ConnectionSettings URLs; `CompanyBWorkflowConfig` (order=0) currently identical. `CompanyBSettingsConfigurable` is a no-state placeholder. **To let B supply non-URL defaults, WorkflowConfig must be widened OR a new preset mechanism added.** ConnectionSettings (app-level, plain PersistentStateComponent) holds only URLs, all empty by default — no company defaults there.

---

## Cluster D — Validator / commit / sprint / PSI

**D1 Dead validator:** `jira/.../service/BranchNameValidator.kt:77-87` `isValidBranchName` — **confirmed dead** (only caller is `BranchNameValidatorTest`; other BranchNameValidator methods `generateBranchName`/`requiresAiSummary`/`issueTypeToPrefix` ARE used). Delete fn + its test.

**D2 Commit format:** `core/.../ai/prompts/CommitMessagePromptBuilder.kt` hardcodes Conventional-Commits (SYSTEM_MESSAGE :28-30; FORMAT block :62-87; issue-type→commit-type map :89-98; ticket-ID prepend). No commit-format setting exists. Callers: `GenerateCommitMessageAction`. Time-tracking-on-commit is a SEPARATE handler (`TimeTrackingCheckinHandlerFactory`), not coupled to the prompt — own toggle.

**D3 Sprint tab:** `jira/.../api/JiraApiClient.kt:46-79` — 4 `/rest/agile/1.0/` endpoints (getBoards/getActiveSprints/getSprintIssues/getBoardIssues) 404 on non-Software Jira. Tab registered via `SprintTabProvider : WorkflowTabProvider` EP (eager, order 0). **NO feature-detection, NO tab-hide infra today** — error shows but tab stays. Needs serverInfo/capability probe + conditional hide in tab provider / tool-window factory.

**D4 PsiContextEnricher:** `core/.../psi/PsiContextEnricher.kt:59-75` `detectMavenModule()` is Maven-only (MavenProjectsManager). Needs `ModuleManager` fallback for Gradle/etc. Consumers: `GenerateCommitMessageAction`, `PrService` (PR context). ⚠ keep the single-cancellable-readAction + PCE-rethrow invariant (perf audit test pins it).

---

## Decomposition note
4 heterogeneous clusters across :core/:agent/:jira/:pullrequest. Cluster C (settings+migration+preset) carries the silent-behavior-loss risk + an architectural fork (WorkflowConfig widening / preset timing). Recommend splitting Phase 1 like 0b was: keystone settings/migration first, then prompt-gating, then mechanical de-convention.


---

## Phase 1a resolved (2026-06-24) — DONE, on feature/plugin-split

Scope SHIPPED (commits bf2c5e77c..b09b3f93a): the v1→v2 `SettingsMigration` seeding mechanism + `defaultTargetBranch` "develop"→"main" (global field + seeding for upgraders + `DefaultBranchResolver` P6), per-repo/UI/prefetch literals (`RepoConfig`, `RepositoriesConfigurable`, `CreatePrPrefetch` both branches), and the 3 cross-module fallbacks (`SprintDashboardPanel`:jira, `BuildDashboardPanel`:bamboo, `ProjectContextTool`:agent `!= neutral`). `NEUTRAL_DEFAULT_TARGET_BRANCH` = top-level const in `core/settings`.

DEFERRED to Phase 2 (module-coupled): `bambooBuildVariableName` (:automation consumers hardcode the `DockerTagsAsJSON` fallback — inert until carve), `quickClipboardChips` (:handover; init-block-persisted, no migration). B config preset stays Phase 2.

EXCLUDED (user-confirmed): status-name defaults (`ticketTransitionDefault*StatusName`, `postCommitTransitionTriggerStatuses`) + `branchPattern`/`jiraBoardType` — generic, not company conventions.

NEXT sub-phases of Phase 1: 1b (agent system-prompt gating — see Cluster B), 1c (dead `isValidBranchName` delete, configurable commit format, Sprint feature-detect+hide, PsiContextEnricher ModuleManager fallback, VcsHostClient.getDefaultBranch/reviewer ops — see Clusters A/D).
