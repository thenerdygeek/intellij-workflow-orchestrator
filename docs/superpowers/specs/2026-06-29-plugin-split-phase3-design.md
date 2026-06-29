# Plugin Split — Phase 3: Persona / Skill Hardening

**Date:** 2026-06-29
**Branch:** `feature/plugin-split`
**Extends:** `docs/superpowers/specs/2026-06-22-plugin-split-design.md` (§11 roadmap row "3 — Persona/skill/prompt hardening", §136). A `§24` back-reference is added to the main design doc in the same commit as implementation.
**Predecessors:** Phase 1 (de-convention), Phase 2a/2b/2c (carve `:automation` / `:handover` → B + config preset) — both complete and pushed (`f4a16444f`).
**Spec version:** v2 — revised after three independent opus reviews (accuracy / completeness / skeptic) and two USER decisions (predicate = `supportsSpring` for both; gate = advisory + fix routing pointers). Review provenance in §11.

---

## 1. Goal

Make Plugin A's bundled **personas** and the **`git-workflow` skill** behave correctly and read neutrally in environments where the company's assumed stack (Spring Boot; Atlassian PR/CI) is absent — without carving any new module or adding any new extension point. This is the third "de-convention" pass: Phase 1 neutralized *settings defaults*, Phase 1b neutralized the *system-prompt integration prose*, and Phase 3 neutralizes *persona advertisement* and the *git-workflow skill body*.

This phase ships **A + B privately** (same as all phases 0–4); it is NOT the OSS publish (that is Phase 5).

**What "gate" means here (important framing, corrected in v2):** the persona gate is an **advertisement filter**, not a hard runtime block. `SpawnAgentTool.execute()` resolves a persona by name via `getCachedConfig`, which reads the *unfiltered* cache (`AgentConfigLoader.kt:193`) — so an explicitly-named persona still resolves and runs even when filtered out of the advertised list. Per the USER decision, we keep that by-name escape hatch (the persona runs degraded-but-functional, its `spring(...)` tool simply absent) and instead make the **advertisement** consistent across all surfaces. See §4.3.

---

## 2. Scope

### In scope (two workstreams)

1. **`supportsSpring` persona advertisement gate** — `security-auditor` and `performance-engineer` are advertised only when `IdeContext.supportsSpring` is true. This requires neutralizing **three advertisement surfaces** consistently (§4.1): Site A `AgentConfigLoader.filterByIdeContext` (the live `agent`-tool list), Site B the hardcoded prose list in `SystemPrompt.kt` `rules()`, and Site C the three bundled files that *actively steer* the LLM to these personas by name (`code-reviewer.md`, `architect-reviewer.md`, `subagent-driven/SKILL.md`). The by-name spawn path is intentionally left working (advisory gate).
2. **`git-workflow` skill split** — trim A's `agent/src/main/resources/skills/git-workflow/SKILL.md`: replace the two Atlassian-coupled section *headers/framing* ("PR-Related Tasks", "CI Context") with one neutral, conditional PR/CI section, **retaining the concrete `bitbucket_pr` / `bamboo_builds` action-name bullets** (they are OSS-generic by the §3 standard and load-bearing as LLM hints).

### Out of scope (explicitly deferred / not built)

- **`devops-engineer` → B** (design §189 listed it under Phase 2; never moved). **Deferred to Phase 5** — needs a persona-contribution mechanism (a new EP or a B startup seeder) that does not exist today and is OSS-source-hygiene shaped. `devops-engineer.md` stays bundled in A. *(USER-confirmed 2026-06-29.)*
- **Generic (non-Spring) variants of the two personas / fine-grained Spring-paragraph stripping.** The design says "gate," not "rewrite." Non-Spring projects fall back to `general-purpose` for proactive use, and the by-name escape hatch still serves the two personas' generic value (CVE/secrets scanning, JVM/GC/threading). *(USER-confirmed 2026-06-29.)*
- **A B-side `atlassian-git` skill + a skill-contribution EP** — largest lift; Phase-5 item if ever wanted. *(USER-confirmed 2026-06-29.)*
- **True hard runtime gate** (rejecting a by-name spawn of a filtered-out persona via `getFilteredConfigs` in `execute()`). Considered; **rejected** in favor of the advisory gate so a user on a non-Spring Java project can still explicitly invoke the personas' generic capabilities. *(USER-confirmed 2026-06-29.)*
- **Neutralizing Atlassian/`master` references in *sibling* skills** (`systematic-debugging/SKILL.md:79-80`; `create-skill/SKILL.md` `bamboo_builds`/`bitbucket_pr` + a "Create PR targeting master" default-branch echo). Out of this phase's declared scope; flagged for a Phase-5 awareness sweep (§8).
- **Phase 4** (native Anthropic LLM provider + persisted-format migration) and **Phase 5** (OSS hardening) — separate later efforts.

---

## 3. Current state (what exists today)

### Persona availability filtering (Site A)

`AgentConfigLoader.filterByIdeContext(configs, ideContext)` (`AgentConfigLoader.kt:227-236`) is the live-list gate. Today it filters exactly two personas:

```kotlin
fun filterByIdeContext(configs, ideContext): List<AgentConfig> {
    if (ideContext == null) return configs          // null → ALL (tests / pre-detection)
    return configs.filter { config ->
        when (config.name.lowercase()) {
            "spring-boot-engineer" -> ideContext.supportsJava
            "python-engineer"      -> ideContext.supportsPython
            else                   -> true           // universal: always advertised
        }
    }
}
```

`getFilteredConfigs(ideContext)` = `filterByIdeContext(getAllCachedConfigs(), ideContext)`, consumed **only** by `SpawnAgentTool` (`SpawnAgentTool.kt:144` for the `agent`-tool description, `:801` for the "Unknown agent type 'X'. Available: …" error). The persona names elsewhere in `SpawnAgentTool` (`:244`, `:313`, `:320`, `:348`, `:380-381`) are illustrative schema-doc strings, not the live list.

### The advisory nature of the gate (the spawn-by-name path)

`SpawnAgentTool.execute()` resolves the requested persona via `configLoader.getCachedConfig(agentType)` (`SpawnAgentTool.kt:662`), and `getCachedConfig` returns straight from the **unfiltered** `configCache` (`AgentConfigLoader.kt:193-196`) — it never calls `filterByIdeContext`. The "Unknown agent type … Available:" error fires only when `getCachedConfig` returns `null` (a genuine typo/nonexistent type, `SpawnAgentTool.kt:663`). So filtering hides a persona from the *advertised* list but does not stop a by-name spawn; the persona runs with its `spring(...)` tool dropped (gracefully omitted by `resolveConfigToolsTiered`, `SpawnAgentTool.kt:762-764`, because `shouldRegisterSpringTools` is false). This is the behavior we keep (USER decision); §4.3 documents it.

### Site B — the prose listing in `SystemPrompt.kt`

A **separate, hardcoded** persona listing lives in `SystemPrompt.kt` `rules()` → "# Subagent Delegation" (lines ~949-972), gated `includeSubagentDelegationInRules`. It does **not** call `filterByIdeContext`; `SystemPrompt.build` is passed only `ideContext`, and each line has its own conditional:

```kotlin
appendLine("- \"code-reviewer\" — …")          // always
appendLine("- \"architect-reviewer\" — …")     // always
appendLine("- \"test-automator\" — …")         // always
if (ideContext == null || ideContext.supportsJava) {
    appendLine("- \"spring-boot-engineer\" — …")
}
if (ideContext?.supportsPython == true) {
    appendLine("- \"python-engineer\" — …")
}
appendLine("- \"refactoring-specialist\" — …") // always
appendLine("- \"devops-engineer\" — …")        // always (untouched this phase)
appendLine("- \"security-auditor\" — …")       // ← currently UNCONDITIONAL (lines 970-971,
appendLine("- \"performance-engineer\" — …")   //    consecutive + trailing)
```

This list (shorter, hand-written descriptions) is what the orchestrator golden snapshots capture — proven by `webstorm.txt` omitting `spring-boot-engineer` (its `supportsJava` conditional). `capabilities()` carries **no** persona names — Site B is the only prompt-prose listing.

### Site C — active routing pointers that steer to the two personas

Three bundled files *actively tell* the LLM to delegate to these personas by name (live prompt/skill text, not passive docs). These are what make a de-advertisement incoherent unless also addressed:

- `agents/code-reviewer.md:104` — "For deep security audits use `security-auditor`. For performance profiling use `performance-engineer`."
- `agents/architect-reviewer.md:123` — "For security audits use `security-auditor`."
- `agents/subagent-driven/SKILL.md:61-62` — `agent_type` table rows naming both personas.

`code-reviewer.md` and `architect-reviewer.md` are **persona bodies captured by `SubagentSystemPromptSnapshotTest`** (`code-reviewer-intellij-ultimate.txt:97`, `architect-reviewer-intellij-community.txt:114`) — editing them regenerates those 2 subagent snapshots. `subagent-driven/SKILL.md` is a skill body (not snapshotted); its `SKILL.java.md`/`SKILL.python.md` variants name only `spring-boot-engineer`/`python-engineer` → no edit needed there.

### `IdeContext` predicates

```kotlin
val supportsJava   get() = Language.JAVA in languages
val supportsPython get() = Language.PYTHON in languages
val supportsSpring get() = hasSpringPlugin && supportsJava
```

`ToolRegistrationFilter.shouldRegisterSpringTools(ctx) = ctx.hasSpringPlugin && ctx.hasJavaPlugin`. Note `supportsSpring` keys on `supportsJava` (`Language.JAVA in languages`) while the spring-tool gate keys on `hasJavaPlugin` (the plugin flag); `IdeContextDetector` sets both together, so they coincide in practice and in every test factory — but they are not literally the same expression.

### The two target personas

- `security-auditor.md` — OWASP-via-Spring-Security throughout (5 `spring(...)` calls, `SecurityFilterChain`, `@PreAuthorize`, Keycloak/OAuth2), plus generic CVE (`build`), secrets (`search_code`), `sonar`, `dataflow_analysis`. `description:` says "Kotlin/Java Spring Boot projects."
- `performance-engineer.md` — only 2 `spring(...)` calls; body dominated by framework-agnostic JVM work (GC/heap, virtual threads/concurrency, Lambda cold-start) with a JPA/Hibernate/HikariCP layer. `description:` says "Kotlin/Java Spring Boot projects."

(The asymmetry in Spring-coupling is acknowledged; per the USER decision both gate on `supportsSpring` — §4.2.)

### The `git-workflow` skill

`SKILL.md` (80 lines, single file — no variants). Generic git in lines 1-54. The two Atlassian-coupled sections are **mid-file**, not the tail: `## PR-Related Tasks` (56-62, uses `bitbucket_pr`) and `## CI Context` (64-68, uses `bamboo_builds`/`bitbucket_repo`), followed by generic `## Destructive Operations` (70) and `## Common Mistakes to Avoid` (74).

> Note: `:pullrequest` and `:bamboo` are **A** modules (only `:automation`/`:handover` carved to B — confirmed in `plugin-b/build.gradle.kts:64,68`), so `bitbucket_pr` / `bamboo_builds` / `bitbucket_repo` are A's own tools — deferred and URL-gated. Referencing them (and their action names) in A's skill is OSS-appropriate; the Phase-5 scrub targets company-internal strings, not generic Atlassian product/tool/action names.

---

## 4. Workstream 1 — `supportsSpring` persona advertisement gate

### 4.1 Mechanism — THREE advertisement surfaces, gated/neutralized consistently

**Site A — runtime live list (`AgentConfigLoader.filterByIdeContext`):**

```kotlin
when (config.name.lowercase()) {
    "spring-boot-engineer"  -> ideContext.supportsJava
    "python-engineer"       -> ideContext.supportsPython
    "security-auditor"      -> ideContext.supportsSpring   // NEW
    "performance-engineer"  -> ideContext.supportsSpring   // NEW
    else                    -> true
}
```

`null` IdeContext keeps the existing `if (ideContext == null) return configs` early-return → all configs (back-compat; tests + the null-context snapshots depend on it; not a runtime path — §4.3).

**Site B — prompt prose (`SystemPrompt.kt` ~970-971):** wrap the two consecutive trailing `appendLine`s:

```kotlin
if (ideContext == null || ideContext.supportsSpring) {
    appendLine("- \"security-auditor\" — security audit: OWASP Top 10, Spring Security, secrets, dependency CVEs.")
    appendLine("- \"performance-engineer\" — performance: database, caching, HTTP clients, JVM tuning.")
}
```

**Site C — routing pointers (3 files):** reword so they no longer *unconditionally* steer the LLM to a possibly-unadvertised persona. The intent is to drop the hard, unconditional steer while preserving the guidance; exact wording finalized in implementation. Recommended approach — make the pointer conditional/deferential rather than deleting the guidance, e.g. "for a deep security audit or performance pass, prefer the dedicated specialist agent when one is offered for this project's stack (see the Subagent Delegation list)." Apply to `code-reviewer.md:104`, `architect-reviewer.md:123`, and the `subagent-driven/SKILL.md:61-62` table rows.

All three surfaces gate-on/condition-on `supportsSpring` (or defer to the gated list). `SpawnAgentTool` needs **no** code change (it already routes the live list through `getFilteredConfigs` → Site A).

### 4.2 Why `supportsSpring` (USER-confirmed), and why the `spring-boot-engineer` asymmetry is acceptable

- **`supportsSpring` (both personas):** the goal of the phase is neutral *advertisement* — A should not proactively offer a "Spring Security OWASP auditor" / "Spring-Boot performance engineer" (both `description`s say "Spring Boot projects") on a project with no Spring plugin. The generic value of each persona is **not lost** — the by-name escape hatch (§4.3) still serves it on demand.
- **Asymmetry with `spring-boot-engineer` (kept on `supportsJava`) is intentional, not an oversight:** `spring-boot-engineer` is a *writer* (you may add Spring to any Java project), so advertising it on any Java project is reasonable; `security-auditor`/`performance-engineer` are *auditors of existing Spring* whose headline introspection (`spring(...)`) needs the plugin. The skeptic review correctly notes `performance-engineer`'s body is mostly generic; the USER nonetheless chose `supportsSpring` for both (symmetry + neutral advertisement + escape hatch). The split-predicate alternative (`performance-engineer → supportsJava`) was considered and declined.

### 4.3 Advisory gate semantics (rewritten in v2 — the original "benign unknown-persona fallback" claim was wrong)

After this change, in a non-Spring environment:

- The two personas are **not advertised** anywhere (Sites A + B + C all neutralized).
- An explicit `agent(agent_type="security-auditor")` **still resolves and runs** — `getCachedConfig` reads the unfiltered cache (`AgentConfigLoader.kt:193`). It does **not** hit the "Unknown agent type" error (that needs a `null` lookup). The persona runs **degraded-but-functional**: its `spring(...)` tool was never registered, so `resolveConfigToolsTiered` silently omits it (`SpawnAgentTool.kt:762-764`); the generic tools (`search_code`, `build`, `sonar`, `dataflow_analysis`, `read_file`, …) all work.

This is the intended behavior (USER decision: keep the escape hatch). It must be **documented in `agent/CLAUDE.md`** ("Agent Persona Filtering") so a future reader doesn't "fix" the unfiltered `getCachedConfig` as a bug. With Site C neutralized, nothing *actively steers* the LLM to a hidden persona; a by-name spawn is a deliberate user/LLM act.

### 4.4 Test impact

**Unit — `AgentConfigFilterTest.kt` (concrete edits, not a no-op add):**
- The shared `makeIdeContext` helper hardcodes `hasSpringPlugin = false` (so `supportsSpring` is always false) — **add a `hasSpring: Boolean = false` parameter** (or build `IdeContext` inline) to construct a `supportsSpring=true` context.
- The shared `sampleConfigs` lacks the two personas, and the `null → returns all` test asserts `assertEquals(4, filtered.size)` — **do not append to `sampleConfigs`** (it breaks that count); use a **separate local config list** containing `security-auditor` + `performance-engineer`.
- Add cases: both present under `supportsSpring=true`; both absent under non-Spring (PyCharm/WebStorm **and** Java-without-Spring — the predicate's whole point).

**Unaffected (verified by review):** `AgentConfigLoaderTest.kt` (asserts unfiltered `getAllCachedConfigs` + `assertEquals(12, bundled.size)` — still 12 persona files), `PersonaToolsTest.kt` (reads unfiltered `getCachedConfig`, never the filter), konsist/contract tests, `plugin-b`, webview (generic `agentType: string`, no hardcoded names).

**Golden snapshots — intentional regen, SIX files:**

*Orchestrator (Site B), 4 files* — the two persona lines deleted from each non-Spring *context* snapshot:

| Snapshot | `hasSpringPlugin` / context | security-auditor / performance-engineer |
|---|---|---|
| `intellij-ultimate`, `intellij-ultimate-mixed` | true | kept |
| `null-context`, `no-integrations`, `jira-only`, `no-jira` | null IdeContext → all | kept |
| `intellij-community` | false (Java+Maven, no Spring) | **REMOVED** |
| `pycharm-professional`, `pycharm-community`, `webstorm` | false | **REMOVED** |

*Sub-agent (Site C body edits), 2 files* — `code-reviewer-intellij-ultimate.txt` and `architect-reviewer-intellij-community.txt` regen because the edited pointer sentences live in those persona bodies. The other 5 `SubagentSystemPromptSnapshotTest` snapshots (spring-boot-engineer, python-engineer, test-automator, research ×2) are untouched.

Regen via the documented workflow (`*SNAPSHOT*` fail → review each diff is exactly the expected delta → `*generate all golden snapshots*` → `*SNAPSHOT*` pass). Expected deltas: orchestrator = two-line deletion; sub-agent = the reworded pointer sentence only. Any other delta is a red flag.

---

## 5. Workstream 2 — `git-workflow` skill split

### 5.1 Change

Edit `agent/src/main/resources/skills/git-workflow/SKILL.md`:

- **Keep** all generic-git sections (Tool Availability, Before Any Git Operation, Comparing Branches, Checking a Ticket's Changes, Reviewing File History, Viewing a File at a Different Branch, Understanding Branch Divergence, Shelving Changes, Destructive Operations, Common Mistakes).
- **Replace** the two Atlassian-coupled sections (56-68) with **one neutral, conditional section** that keeps the concrete action bullets but frames them as deferred/optional, e.g.:

  > ## PR & CI tasks (when integrations are configured)
  > These tools are deferred and appear only when their service URL is set in settings — load them via `tool_search` first.
  > - Pull requests: `bitbucket_pr(action="get_pr_diff" / "get_pr_changes" / "get_pr_commits" / "create_pr")`
  > - Build/CI status: `bamboo_builds(action="build_status")`, `bitbucket_repo(action="get_build_statuses")`

  Rationale: the action names are OSS-generic by the §3 standard and are useful LLM hints; the only thing being neutralized is the "Enterprise/always-on" framing — they become explicitly conditional on the integration being configured. (Skeptic review: deleting the action bullets would be a capability downgrade for no neutrality gain.)

- **`description:`** — keep generic; current "Enterprise git workflow …" wording is acceptable. No trigger-keyword change.

No behavior regression: the bitbucket/bamboo tools remain deferred + `tool_search`-discoverable exactly as before; only the skill's *framing* is neutralized.

### 5.2 Test impact

None: no test references the git-workflow skill text (verified — no hits for `git-workflow` / `PR-Related` / `CI Context` / `Comparing Branches` under `agent/src/test/`); `InstructionLoaderTest` / `SkillVariantTest` load skills generically. No konsist/contract test pins skill content. **Optional guard (default = omit):** a content-assertion test pinning the neutral framing — low value vs. maintenance.

---

## 6. Files touched (anticipated)

| File | Change |
|---|---|
| `agent/.../tools/subagent/AgentConfigLoader.kt` | **Site A** — `filterByIdeContext`: +2 `when` cases (`supportsSpring`) |
| `agent/.../prompt/SystemPrompt.kt` | **Site B** — wrap the two trailing `appendLine`s (~970-971) in `if (ideContext == null \|\| ideContext.supportsSpring)` |
| `agent/.../resources/agents/code-reviewer.md` | **Site C** — neutralize the `security-auditor`/`performance-engineer` steer (~:104) |
| `agent/.../resources/agents/architect-reviewer.md` | **Site C** — neutralize the `security-auditor` steer (~:123) |
| `agent/.../resources/skills/subagent-driven/SKILL.md` | **Site C** — neutralize the two persona `agent_type` table rows (~:61-62) |
| `agent/.../resources/skills/git-workflow/SKILL.md` | trim 2 Atlassian sections → 1 neutral conditional PR/CI section (keep action bullets) |
| `agent/src/test/.../AgentConfigFilterTest.kt` | `makeIdeContext` +`hasSpring` param; separate local persona list; +present/absent cases |
| `agent/src/test/resources/prompt-snapshots/{intellij-community,pycharm-professional,pycharm-community,webstorm}.txt` | regen — Site B (remove 2 lines each) |
| `agent/src/test/resources/subagent-prompt-snapshots/{code-reviewer-intellij-ultimate,architect-reviewer-intellij-community}.txt` | regen — Site C (reworded pointer sentence) |
| `docs/.../2026-06-22-plugin-split-design.md` | add `§24 Phase 3 resolved` note (same commit as impl) |
| `agent/CLAUDE.md` | update "Agent Persona Filtering" (both Spring-gated personas; the advisory-gate / by-name behavior) + the IDE-aware "Subagent Delegation" note |

`SpawnAgentTool` — **no change**. No `:core` / new EP / settings / migration / `:plugin-b` change. A one-line release-note/CHANGELOG entry records the advertisement change (see DoD).

---

## 7. Testing strategy

1. `./gradlew :agent:test --tests "*AgentConfigFilter*"` — new persona-gate cases (TDD: write first, watch fail) — incl. the helper `hasSpring` param + separate local list.
2. `./gradlew :agent:test --tests "*SNAPSHOT*"` — expect 4 orchestrator + 2 sub-agent variants to fail; review each diff equals the expected delta only; regen via the two `*generate all golden snapshots*` tasks (`SystemPromptIdeContextTest` + `SubagentSystemPromptSnapshotTest`); re-run `*SNAPSHOT*` green.
3. `./gradlew :agent:test` — full module green (watch `PersonaToolsTest`, `AgentConfigLoaderTest`, `SubagentSystemPromptBuilderTest`).
4. Detekt per touched module `--rerun-tasks` (cache masks per-module debt). Resource/markdown edits → no Kotlin detekt impact.
5. Gate: `./gradlew verifyPlugin` + `koverVerify -Pcoverage -x uiTest` (retry the known #51 timing/socket flakes).

The Site C + git-workflow edits are markdown/resource; behavior-neutral apart from the snapshot deltas above.

---

## 8. Risks & mitigations

- **Snapshot over-regeneration** (recurring trap): the §4.4 table fixes the *exact* expected delta for all 6 files; the implementer reviews each diff before committing; the task-reviewer re-checks. Two regen tasks now run (orchestrator + sub-agent) — don't forget the sub-agent one.
- **Three-surface drift** (the headline Phase-3 trap): Sites A, B, C must all gate/defer on `supportsSpring`. Editing a subset leaves an incoherent state (advertised in one place, steered-to in another, hidden in a third). Mitigation: all three in the same task; `AgentConfigFilterTest` pins A; the 4-snapshot regen pins B; the 2-snapshot regen pins C.
- **Advisory-gate misread as a hard gate** (the v1 error): documented in §4.3 + `agent/CLAUDE.md`. The by-name spawn deliberately still works; do not "fix" `getCachedConfig`.
- **Silent advertisement regression:** internal users on non-Spring Java projects lose the proactive listing (still spawnable by name). Mitigation: one-line CHANGELOG/release note ("`security-auditor`/`performance-engineer` now offered only in Spring/Java-with-Spring contexts; still spawnable explicitly by name"). No settings override (gold-plating, given the escape hatch).
- **Wrong predicate** (`supportsJava` vs `supportsSpring`): pinned by §4.2 + the `AgentConfigFilterTest` Java-no-Spring case (must gate OUT under Java-without-Spring).
- **Whole-branch orphan check** (Phase-2 lesson): the only main-source references to the two personas are the documented prose/schema/Site-C strings + the `BUNDLED_AGENT_FILES` list — no Kotlin code branches on these personas being present (verified by review). Re-grep before the gate to confirm none added.
- **Sibling-skill Atlassian/`master` coupling out of scope** (`systematic-debugging/SKILL.md:79-80`; `create-skill/SKILL.md` incl. "Create PR targeting master"): not edited in Phase 3; logged here for the Phase-5 OSS sweep.
- **Detekt baseline trap:** no files move across modules → the per-module-baseline-by-filename trap doesn't apply. Still run per-module detekt `--rerun-tasks`.

---

## 9. Definition of Done

- All three advertisement surfaces gate/defer on `supportsSpring`, consistently and list-when-`null`: Site A (`filterByIdeContext`), Site B (`SystemPrompt.kt` prose), Site C (3 routing-pointer files). `AgentConfigFilterTest` proves Site A present-when-Spring / absent-when-not (incl. Java-no-Spring).
- 6 golden snapshots regenerated with the exact expected deltas (4 orchestrator two-line deletions + 2 sub-agent pointer rewordings); all other snapshots byte-stable; `*SNAPSHOT*` green.
- A's `git-workflow/SKILL.md` is generic git + a neutral, conditional PR/CI section that retains the action bullets; full suite green.
- `agent/CLAUDE.md` documents both Spring-gated personas **and** the advisory-gate / by-name behavior; main design-doc `§24` updated in the implementing commit(s); one-line CHANGELOG/release note added.
- Full gate GREEN (`:agent:test`, `verifyPlugin`, `koverVerify` floor holds); 0 Critical / 0 Important from the whole-phase review.

---

## 10. Process (standing rule for this program)

Multi-round independent review at every step. Sequence: this spec → user review → `writing-plans` → 2–3 independent opus plan reviews + a confirming re-review → SDD execution (implementer + independent task-reviewer per task; controller verifies) → whole-phase opus review → full gate. opus has a weekly limit (sonnet is the floor, never haiku for subagents). Commit on `feature/plugin-split`; **no `Co-Authored-By` / "Generated with" trailer**.

---

## 11. Review provenance (v2)

This spec was revised after three independent opus reviews of v1 (each verifying claims against source, read-only):

- **Accuracy** — VERDICT MINOR-CORRECTIONS. All load-bearing claims confirmed; fixed: "same predicate" wording, "tail sections" → mid-file, "81 → 80 lines."
- **Completeness** — VERDICT MINOR-GAPS. Surfaced the third listing site (`subagent-driven/SKILL.md`), the concrete `AgentConfigFilterTest` helper/`sampleConfigs` edits, the extra `SpawnAgentTool` illustrative strings, and the out-of-scope sibling-skill coupling. Confirmed no third *production* gating site, no konsist/`plugin-b`/webview/doc drift, no git-workflow variants.
- **Skeptic** — VERDICT SOUND-WITH-CAVEATS. Caught the v1 "benign unknown-persona fallback" error (gate is advisory; `getCachedConfig` is unfiltered) and the predicate-bluntness/consistency concern. Confirmed list-when-null is a non-issue (`ideContext` set synchronously in init, never null at runtime).

Two USER decisions (2026-06-29) resolved the Important findings: predicate = **`supportsSpring` for both**; gate = **advisory + fix routing pointers** (keep by-name escape hatch; no hard gate).
