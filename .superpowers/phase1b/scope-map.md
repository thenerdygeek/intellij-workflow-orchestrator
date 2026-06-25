# Phase 1b — System-prompt integration-gating — Scope Map (2026-06-25)

Branch `feature/plugin-split`. Refreshed against real code (post-1a). Source: focused Explore agent.

## Gating sites in `agent/.../prompt/SystemPrompt.kt` (6 MUST-GATE)
1. `agentRole()` ~337 — "...refactoring engine, and enterprise integrations (Jira, Bamboo, SonarQube, Bitbucket)." → gate: list only configured; if none, drop the clause.
2. `outputFormatting()` ~398/403/406 — Jira ticket markdown-link scheme (`[PROJ-1234](jira:PROJ-1234)`) + example → gate on jira.
3. `capabilities()` ~521 — "- **Project integrations** → jira, bamboo_builds, bamboo_plans, sonar, bitbucket_pr, bitbucket_repo, bitbucket_review" → build list from configured set; omit line if none.
4. `capabilities()` ~537 — "...active Jira ticket, ..., PR status, build results, Sonar quality gate, ..." (project_context state list) → per-clause gate (jira→"active Jira ticket"; bitbucket→"PR status"; bamboo→"build results"; sonar→"Sonar quality gate"; keep branch/uncommitted/service keys/project type).
5. `capabilities()` ~540 — "After refactoring code, use sonar(action=local_analysis,...) ... SonarQube feedback..." → gate on sonar.
6. `rules()` ~857-867 — "# Jira Transition — Field Collection Pattern" full subsection → gate on jira.

LEAVE (generic, not company-specific): Docker in run_command tip + devops-engineer delegation line; "sprint" in delegation text. NO Sourcegraph/Confluence in SystemPrompt.kt.

## `SystemPrompt.build()` signature
Long; already has `ideContext: IdeContext?` + many `include*` boolean flags + `hasWebTools`/`delegationOutboundEnabled` (all default-valued, backward-compatible). ADD 4 new trailing params: `jiraConfigured/bambooConfigured/sonarConfigured/bitbucketConfigured: Boolean = false`. **Flags PASSED IN — never read ConnectionSettings inside build()** (snapshot tests are pure JUnit5, no Application).

## Callers (3 — all thread the new flags)
1. `AgentService.executeTask()` `systemPromptBuilder` lambda (~2134-2164) — resolves from `ConnectionSettings.getInstance().state.*Url.isNotBlank()` (re-evaluated every rebuild, like `hasWebTools`).
2. `AgentService.resumeSession()` (~3118-3135) — same resolution.
3. `SubagentSystemPromptBuilder.build()` (~107-146) — receives flags as params (like `hasWebTools`), threaded from `SubagentRunner` (which has `project`→ConnectionSettings). Sub-agent prompt gated for parity with orchestrator.

## Config source (mirror tool-gating)
`ConnectionSettings.getInstance().state.{jiraUrl,bambooUrl,sonarUrl,bitbucketUrl}.isNotBlank()` — exact predicate from `AgentService.reregisterConditionalTools()` (~1379-1441). NO existing `isConfigured` helper. (Consider a tiny pure helper `IntegrationFlags.from(ConnectionSettings.State)` to DRY the 3 call sites + make resolution unit-testable.)

## Snapshot harness (CRITICAL)
- Orchestrator: `SystemPromptIdeContextTest` (`buildPrompt(ideContext)` passes NO integration flags) → 7 `.txt` in `agent/src/test/resources/prompt-snapshots/`.
- Sub-agent: `SubagentSystemPromptSnapshotTest` → 5 `.txt` in `agent/src/test/resources/subagent-prompt-snapshots/`.
- Regen: `./gradlew :agent:test --tests "*<TestName>*generate all golden*"`; validate: `--tests "*<TestName>*"`.

## DECISIONS (my call — architecture-autonomous; review to vet)
- **Per-integration flags** (jira/bamboo/sonar/bitbucket), matching tool-gating granularity. NOT a single hasIntegrations flag.
- **Snapshot strategy = preserve baseline + add gated variants (Option C):** the existing 7 orchestrator + 5 sub-agent snapshots are regenerated with all-4-flags-TRUE so they stay byte-identical to today (their purpose = IDE-context adaptation, orthogonal to gating; all-ON == today PROVES parity). Then ADD a small set of NEW gated snapshots: at minimum `no-integrations` (all false) + `jira-only` (jira true, rest false) to pin the OFF/partial rendering. (Explorer recommended Option A = regenerate-as-no-integration, which LOSES the configured-path coverage and silently rewrites every snapshot — rejected.)
- **Degraded form = drop, not generalize:** when an integration is off, its name/tools/tips simply don't appear; when on, identical to today. agentRole/capabilities-integration-line build the list dynamically from the configured set.
- **Both orchestrator + sub-agent gated** (shared build() sites; thread flags to the sub-agent caller too).

## OPEN for the plan (verify during planning)
- Grep for NON-snapshot tests asserting prompt content (e.g. a test checking "Jira"/"SonarQube" appears) — they may break and need flag-aware updates.
- Confirm `SubagentRunner` has `project`/ConnectionSettings access to resolve flags, and the exact threading param names.
- The all-ON==today parity must be EXACT (the gated rendering with all flags true must reproduce the current unconditional text verbatim — whitespace/ordering identical).

---
## Resolved by plan review (2026-06-25)
- ⭐ BLOCKER fixed: **sub-agent prompt gates on its OWN registry (`registry.has(...)`), NOT ConnectionSettings** — a persona's tools = its allowlist, not global config (mirrors existing `hasWebTools = registry.has(...)`). Orchestrator keeps ConnectionSettings (its registry is itself URL-gated → registry-derived is a strict refinement). Decided autonomously (correct + matches precedent + strengthens de-convention); documented in spec §20.
- Sub-agent snapshot set is **7** (not 5 — CLAUDE.md stale): +research-null-context, +research-intellij-ultimate.
- Snapshots: keep 7 orch + 7 subagent as ALL (zero-diff parity proof) + add **3** gated orchestrator variants: no-integrations, jira-only, **no-jira** (pins jira-OFF against a non-empty stack = separator-flow boundary). Orchestrator count assert 7→10; subagent stays 7. Validators are per-@Test methods, not a list.
- Added a source-text footgun guard (every build() caller passes `integrations =`).
- Parity verified byte-exact at all 6 sites by the accuracy review (decompile-level); getInstance() is app-level no-arg (correct).
