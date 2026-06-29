# Plugin Split — Phase 3: Persona / Skill Hardening

**Date:** 2026-06-29
**Branch:** `feature/plugin-split`
**Extends:** `docs/superpowers/specs/2026-06-22-plugin-split-design.md` (§11 roadmap row "3 — Persona/skill/prompt hardening", §136). A `§24` back-reference is added to the main design doc in the same commit as implementation.
**Predecessors:** Phase 1 (de-convention), Phase 2a/2b/2c (carve `:automation` / `:handover` → B + config preset) — both complete and pushed (`f4a16444f`).

---

## 1. Goal

Make Plugin A's bundled **personas** and the **`git-workflow` skill** behave correctly and read neutrally in environments where the company's assumed stack (Spring Boot; Atlassian PR/CI) is absent — without carving any new module or adding any new extension point. This is the third "de-convention" pass: Phase 1 neutralized *settings defaults*, Phase 1b neutralized the *system-prompt integration prose*, and Phase 3 neutralizes the *persona availability* and the *skill body*.

This phase ships **A + B privately** (same as all phases 0–4); it is NOT the OSS publish (that is Phase 5).

---

## 2. Scope

### In scope (two workstreams)

1. **`supportsSpring` persona availability gate** — `security-auditor` and `performance-engineer` are offered only when `IdeContext.supportsSpring` is true, mirroring how `spring-boot-engineer` is already gated (but on `supportsSpring`, not `supportsJava` — see §4.2 for why the predicate differs).
2. **`git-workflow` skill split** — trim A's `agent/src/main/resources/skills/git-workflow/SKILL.md` to generic git, replacing its two Atlassian-coupled sections ("PR-Related Tasks", "CI Context") with a single neutral tool-pointer.

### Out of scope (explicitly deferred / not built)

- **`devops-engineer` → B** (design §189 listed it under Phase 2; never moved). **Deferred to Phase 5.** Moving a bundled persona to B needs a persona-contribution mechanism (a new EP or a B startup seeder) that does not exist today and is OSS-source-hygiene shaped — it belongs with Phase 5's repo extraction + scrub. `devops-engineer.md` stays bundled in A for Phase 3. *(USER-confirmed 2026-06-29.)*
- **Generic (non-Spring) variants of the two personas.** The design says "gate," not "rewrite." Authoring parallel language-generic security/performance bodies is scope creep (YAGNI). Non-Spring projects fall back to `general-purpose` (and the user may still author a custom persona). *(USER-confirmed 2026-06-29.)*
- **A B-side `atlassian-git` skill + a skill-contribution EP.** Considered for the git-workflow split; rejected for Phase 3 as the largest lift (new EP + cross-plugin classpath skill scanning). If ever wanted, it is a Phase-5 item. *(USER-confirmed 2026-06-29.)*
- **Fine-grained role-text stripping** (keeping a persona available but removing only its Spring paragraphs). Deliberately not built — the two target personas are Spring-shaped end-to-end, so availability gating is the honest mechanism (§4.1).
- **Phase 4** (native Anthropic LLM provider + persisted-format migration) and **Phase 5** (OSS hardening) — separate later efforts.

---

## 3. Current state (what exists today)

### Persona availability filtering

`AgentConfigLoader.filterByIdeContext(configs, ideContext)` is the single gate. Today it filters exactly two personas:

```kotlin
fun filterByIdeContext(configs, ideContext): List<AgentConfig> {
    if (ideContext == null) return configs          // null → ALL (tests / pre-detection)
    return configs.filter { config ->
        when (config.name.lowercase()) {
            "spring-boot-engineer" -> ideContext.supportsJava
            "python-engineer"      -> ideContext.supportsPython
            else                   -> true           // universal: always available
        }
    }
}
```

`getFilteredConfigs(ideContext)` = `filterByIdeContext(getAllCachedConfigs(), ideContext)`. It is consumed by **`SpawnAgentTool`** only (lines 144, 801) — it builds the runtime `agent`-tool available-list and the "Unknown agent type 'X'. Available: …" error, both post-IdeContext-filter. (Persona names elsewhere in `SpawnAgentTool` — the `humanReadable`/`example` strings ~313-381 — are illustrative schema docs, not the live list.)

### The SECOND, independent gating site (the prose listing)

There is a **separate, hardcoded** persona listing inside `SystemPrompt.kt` `rules()` → "# Subagent Delegation" (lines ~955-972), gated `includeSubagentDelegationInRules`. It does **not** call `filterByIdeContext` / `getFilteredConfigs`; `SystemPrompt.build` is passed only `ideContext`, and each line has its own conditional:

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
appendLine("- \"security-auditor\" — …")       // ← currently UNCONDITIONAL
appendLine("- \"performance-engineer\" — …")   // ← currently UNCONDITIONAL
```

This list (with its shorter, hand-written descriptions) is what the golden snapshots capture — proven two ways: `webstorm.txt` omits `spring-boot-engineer` (its `supportsJava` conditional), and `security-auditor`/`performance-engineer` currently appear in *every* context snapshot (they are unconditional here). **This is the site that drives the snapshot regen.** It must be kept consistent with `filterByIdeContext` (the Phase-1b "tool-gating vs prose-gating must align" lesson, now applied to personas).

### `IdeContext` predicates

```kotlin
val supportsJava   get() = Language.JAVA in languages
val supportsPython get() = Language.PYTHON in languages
val supportsSpring get() = hasSpringPlugin && supportsJava
```

`ToolRegistrationFilter.shouldRegisterSpringTools(ctx) = ctx.hasSpringPlugin && ctx.hasJavaPlugin` — i.e. the `spring(...)` meta-tool is registered under the **same** `supportsSpring` predicate.

### The two target personas

- `security-auditor.md` — OWASP-via-Spring-Security end to end (`spring(action="security_config")`, `SecurityFilterChain`, `@PreAuthorize`, Keycloak/OAuth2). `description:` says "Kotlin/Java Spring Boot projects."
- `performance-engineer.md` — ~95% JPA/Hibernate/HikariCP/Spring Boot tuning, with a thin generic JVM/GC/Lambda slice. `description:` says "Kotlin/Java Spring Boot projects."

Both lean on the `spring(...)` meta-tool, which is only registered when `supportsSpring`.

### The `git-workflow` skill

`SKILL.md` (81 lines). Generic git through line ~56 (status / branch-compare / blame / history / view-at-ref / shelve). The two Atlassian-coupled tail sections:
- **"PR-Related Tasks"** → `bitbucket_pr(action=…)`
- **"CI Context"** → `bamboo_builds(action=…)` / `bitbucket_repo(action=…)`

`description:` and trigger keywords are already generic git. `preferred-tools: [run_command, changelist_shelve]` are generic.

> Note: `:pullrequest` and `:bamboo` are **A** modules (only `:automation`/`:handover` carved to B), so `bitbucket_pr` / `bamboo_builds` / `bitbucket_repo` are A's own tools — deferred and URL-gated. Referencing them by name in A's skill is OSS-appropriate (Bitbucket/Bamboo are generic Atlassian products); the Phase-5 scrub targets company-internal strings, not product/tool names.

---

## 4. Workstream 1 — `supportsSpring` persona gate

### 4.1 Mechanism — TWO sites, gated consistently

**Site A — runtime availability (`AgentConfigLoader.filterByIdeContext`):** drives the `agent`-tool list + unknown-agent error.

```kotlin
when (config.name.lowercase()) {
    "spring-boot-engineer"  -> ideContext.supportsJava
    "python-engineer"       -> ideContext.supportsPython
    "security-auditor"      -> ideContext.supportsSpring   // NEW
    "performance-engineer"  -> ideContext.supportsSpring   // NEW
    else                    -> true
}
```

`null` IdeContext is handled by the existing `if (ideContext == null) return configs` early-return → all configs (backward-compatible).

**Site B — prompt prose (`SystemPrompt.kt` rules, ~line 970):** wrap the two `appendLine`s in the same predicate the prose uses for `spring-boot-engineer`, but on `supportsSpring`:

```kotlin
if (ideContext == null || ideContext.supportsSpring) {
    appendLine("- \"security-auditor\" — security audit: OWASP Top 10, Spring Security, secrets, dependency CVEs.")
    appendLine("- \"performance-engineer\" — performance: database, caching, HTTP clients, JVM tuning.")
}
```

Both sites list-when-`null` and gate-on-`supportsSpring` otherwise → consistent with each other and with `SpawnAgentTool`'s `getFilteredConfigs`. `SpawnAgentTool` itself needs **no** change (it already routes through `getFilteredConfigs` → Site A). These are the only two production edits for this workstream.

### 4.2 Why availability gating, and why `supportsSpring` (not `supportsJava`)

- **Availability, not paragraph-stripping:** both personas are Spring-shaped end to end (§3). A "strip the Spring parts" mechanism would leave an incoherent skeleton and requires role-text-stripping machinery we are not building. Gating availability is the honest mechanism and matches the design verb ("gate") and the `spring-boot-engineer` precedent.
- **`supportsSpring`, because the headline tool is `supportsSpring`-gated:** both personas' primary instrument is `spring(...)`, registered only when `hasSpringPlugin && hasJavaPlugin`. In a non-Spring environment the persona would invoke tools that were never registered. So `supportsSpring` is not merely a description-match — it is the predicate under which the persona can actually function. (We do **not** change `spring-boot-engineer`'s existing `supportsJava` gate — out of scope, and it has its own rationale: a Java project may use Spring without the IntelliJ Spring plugin.)

### 4.3 Accepted edge cases

- **IntelliJ Community + real Spring code** → `hasSpringPlugin=false` → `supportsSpring=false` → the two personas are gated out. Accepted: the `spring(...)` tool is unavailable there anyway, so the personas could not perform their Spring-specific audits. The `general-purpose` agent remains.
- **Dangling cross-references (documented, NOT fixed):** `code-reviewer.md` / `architect-reviewer.md` bodies hardcode prose like "for deep security audits use `security-auditor`"; `SpawnAgentTool`'s schema `example("security-auditor")` / `humanReadable("… security-auditor …")` strings (~313-381) likewise name the gated personas illustratively. In a non-Spring environment those personas are no longer offered, so the pointers dangle. All are static prose inside *other* personas / tool docs; fixing them needs the role-text-stripping mechanism we chose not to build. Impact is benign (the LLM may attempt `agent(agent_type="security-auditor")` and receive the standard unknown-persona fallback that lists the *actually* available, post-filter set). Left as accepted; a future fine-grained role-text pass (or Phase 5) may address it.

### 4.4 Test impact

**Unit:** `AgentConfigFilterTest.kt` — add cases asserting `security-auditor` + `performance-engineer` are **present** under a `supportsSpring=true` context and **absent** under non-Spring contexts (PyCharm / WebStorm / Java-no-Spring). The test builds `IdeContext` directly, so it can set `hasSpringPlugin` + `Language.JAVA` to flip `supportsSpring`.

**Unaffected:** `AgentConfigLoaderTest.kt:434-437` asserts `security-auditor` is in the *unfiltered* bundled load (`getAllCachedConfigs`, no context) — still true (the persona file is still bundled). `PersonaToolsTest.kt` — verify; it tests tool resolution, not context filtering (expected no change — confirm in plan).

**Golden snapshots (orchestrator system prompt) — intentional regen:** driven by **Site B** (`SystemPrompt.kt`); `filterByIdeContext` (Site A) does NOT feed these snapshots (the builder never calls it). The two personas disappear from exactly the non-Spring *context* snapshots:

| Snapshot | IdeContext | `supportsSpring` | security-auditor / performance-engineer |
|---|---|---|---|
| `intellij-ultimate` | IU + Spring + Gradle | **true** | unchanged (kept) |
| `intellij-ultimate-mixed` | IU + Java+Python + Spring | **true** | unchanged (kept) |
| `intellij-community` | IC + Java + Maven, **no Spring** | **false** | **REMOVED** |
| `pycharm-professional` | Python + Django | false | **REMOVED** |
| `pycharm-community` | Python + FastAPI | false | **REMOVED** |
| `webstorm` | base, no language | false | **REMOVED** |
| `null-context` | null | n/a → all | unchanged (kept) |
| `no-integrations` / `jira-only` / `no-jira` | **null** IdeContext | n/a → all | unchanged (kept) |

The 4 REMOVED-row snapshots are regenerated via the documented workflow (`*SNAPSHOT*` fail → review diff → `*generate all golden snapshots*` → `*SNAPSHOT*` pass). The expected diff per file is the deletion of exactly the two persona lines (`security-auditor …` and `performance-engineer …`) — any other delta is a red flag. The two `SubagentSystemPromptSnapshotTest` cross-reference notes (`architect-reviewer-intellij-community`, `code-reviewer-intellij-ultimate`) are persona *bodies* we do not edit → those snapshots are unchanged (this is the §4.3 dangling-pointer, surfaced but not fixed).

---

## 5. Workstream 2 — `git-workflow` skill split

### 5.1 Change

Edit `agent/src/main/resources/skills/git-workflow/SKILL.md`:

- **Keep** all generic-git sections (Tool Availability, Before Any Git Operation, Comparing Branches, Checking a Ticket's Changes, Reviewing File History, Viewing a File at a Different Branch, Understanding Branch Divergence, Shelving Changes, Destructive Operations, Common Mistakes).
- **Replace** the two Atlassian-coupled sections ("PR-Related Tasks", "CI Context") with one neutral pointer, e.g.:

  > ## PR & CI tasks
  > For pull-request and CI work, load the relevant integration tools via `tool_search` when those integrations are configured — e.g. `bitbucket_pr` / `bitbucket_repo` for PR diffs, changes, and creation; `bamboo_builds` / `bitbucket_repo` for build status. These tools are deferred and appear only when their service URL is set in settings.

- **`description:`** — keep generic; the current "Enterprise git workflow …" wording is acceptable (generic). No trigger-keyword change required.

The intent: A's skill teaches git neutrally and points at the (already URL-gated, deferred) integration tools without embedding deep Atlassian procedure. No behavior regression — the bitbucket/bamboo tools remain discoverable via `tool_search` exactly as before; only the skill's *prose* is trimmed.

### 5.2 Test impact

None today: no test references the git-workflow skill text (verified by grep — no hits for `git-workflow` / `PR-Related` / `CI Context` / `Comparing Branches` under `agent/src/test/`). `InstructionLoaderTest` / `SkillVariantTest` load skills generically and do not assert this skill's content.

**Optional guard (decide in plan):** a tiny content-assertion test pinning that A's `git-workflow/SKILL.md` no longer contains the carved deep-Atlassian procedure (e.g. asserts absence of the old `get_pr_diff`-style step list, presence of the neutral pointer). Low value vs. maintenance; default = omit unless the plan reviewer wants the OSS-neutrality pinned.

---

## 6. Files touched (anticipated)

| File | Change |
|---|---|
| `agent/.../tools/subagent/AgentConfigLoader.kt` | **Site A** — `filterByIdeContext`: +2 `when` cases (`supportsSpring`) |
| `agent/.../prompt/SystemPrompt.kt` | **Site B** — wrap the `security-auditor` + `performance-engineer` `appendLine`s (~970) in `if (ideContext == null \|\| ideContext.supportsSpring)` |
| `agent/.../resources/skills/git-workflow/SKILL.md` | trim 2 Atlassian sections → 1 neutral pointer |
| `agent/src/test/.../AgentConfigFilterTest.kt` | +cases for the two personas × Spring/non-Spring (Site A) |
| `agent/src/test/resources/prompt-snapshots/{intellij-community,pycharm-professional,pycharm-community,webstorm}.txt` | regen (remove 2 persona lines each) — Site B |
| `docs/.../2026-06-22-plugin-split-design.md` | add `§24 Phase 3 resolved` note (same commit as impl) |
| `agent/CLAUDE.md` | update "Agent Persona Filtering" + the IDE-aware "Subagent Delegation" notes to record both `supportsSpring`-gated sites |

`SpawnAgentTool` — **no change** (already routes the live list through `getFilteredConfigs` → Site A). No `:core` change. No new EP. No settings/migration change. No `:plugin-b` change.

---

## 7. Testing strategy

1. `./gradlew :agent:test --tests "*AgentConfigFilter*"` — new persona-gate cases (TDD: write first, watch fail).
2. `./gradlew :agent:test --tests "*SNAPSHOT*"` — expect the 4 context variants to fail; review each diff is exactly the two-line persona deletion; regen via `*generate all golden snapshots*`; re-run `*SNAPSHOT*` green.
3. `./gradlew :agent:test` — full module green (watch for `PersonaToolsTest`, `AgentConfigLoaderTest`).
4. Detekt per touched module with `--rerun-tasks` (cache masks per-module debt). `git-workflow` is a resource edit — no Kotlin detekt impact.
5. Gate: `./gradlew verifyPlugin` + `koverVerify -Pcoverage -x uiTest` (retry the known #51 timing/socket flakes under instrumentation).

The skill edit (workstream 2) is resource-only and behavior-neutral; its "test" is the unchanged green suite + manual confirmation the rendered skill reads neutrally.

---

## 8. Risks & mitigations

- **Snapshot over-regeneration** (the recurring trap): regenerating wholesale could mask an unintended prompt change. Mitigation: the §4.4 table fixes the *exact* expected diff (two lines per file, 4 files); the implementer reviews each diff before committing; the task-reviewer re-checks.
- **Wrong predicate** (`supportsJava` vs `supportsSpring`): pinned by §4.2 rationale + the new `AgentConfigFilterTest` Java-no-Spring case (must gate OUT under Java-without-Spring).
- **Two-site drift** (the headline Phase-3 trap): Site A (`filterByIdeContext`) and Site B (`SystemPrompt.kt` prose) are independent and must gate identically. Editing only one would let the prompt advertise a persona the runtime won't spawn (or vice-versa). Mitigation: both edits in the same task; `AgentConfigFilterTest` pins Site A; the 4-snapshot regen pins Site B; the task-reviewer checks both predicates read `supportsSpring` with list-when-`null`.
- **Dangling cross-reference** (§4.3): accepted and documented; benign fallback behavior.
- **Whole-branch orphan check** (Phase-2 lesson): grep all A modules for any code that *assumes* `security-auditor`/`performance-engineer` is always present (beyond the documented prose pointers) before the gate — none expected (personas are LLM-selected, not code-referenced), but verify.
- **Detekt baseline trap:** no files move across modules in Phase 3, so the per-module-baseline-by-filename trap does not apply. Still run per-module detekt `--rerun-tasks`.

---

## 9. Definition of Done

- Both sites gate `security-auditor` + `performance-engineer` on `supportsSpring` (Site A `filterByIdeContext`; Site B `SystemPrompt.kt` prose), consistently and list-when-`null`. `AgentConfigFilterTest` proves Site A present-when-Spring / absent-when-not (incl. Java-no-Spring).
- The 4 non-Spring context snapshots (Site B) no longer list the two personas; `null`/ultimate/mixed/integration-gating snapshots unchanged; `*SNAPSHOT*` green.
- A's `git-workflow/SKILL.md` is generic git + a neutral PR/CI tool-pointer; no deep Atlassian procedure remains; full suite green.
- `agent/CLAUDE.md` "Agent Persona Filtering" + main design-doc `§24` updated in the implementing commit(s).
- Full gate GREEN (`:agent:test`, `verifyPlugin`, `koverVerify` floor holds); 0 Critical / 0 Important from the whole-phase review.

---

## 10. Process (standing rule for this program)

Multi-round independent review at every step. Sequence: this spec → user review → `writing-plans` → 2–3 independent opus plan reviews + a confirming re-review → SDD execution (implementer + independent task-reviewer per task; controller verifies) → whole-phase opus review → full gate. opus has a weekly limit (sonnet is the floor, never haiku for subagents). Commit on `feature/plugin-split`; **no `Co-Authored-By` / "Generated with" trailer**.
