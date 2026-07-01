# Plugin Split — Phase 3 (Persona / Skill Hardening) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gate the `security-auditor` and `performance-engineer` personas on `supportsSpring` across all three advertisement surfaces, and neutralize the Atlassian framing in the `git-workflow` skill — so Plugin A reads neutrally on non-Spring / non-Atlassian projects.

**Architecture:** The persona gate is an **advertisement filter, not a hard runtime block** — `SpawnAgentTool.execute()` resolves by name via `getCachedConfig` (unfiltered), so an explicitly-named persona still runs (degraded). We therefore neutralize *every advertising surface* consistently — Site A (`AgentConfigLoader.filterByIdeContext`, the live list), Site B (`SystemPrompt.kt` prose list), Site C (three bundled files that actively steer to the personas) — while deliberately leaving the by-name escape hatch working. The `git-workflow` edit is a resource-only framing change that keeps the OSS-generic action bullets.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform Plugin v2 (target IDEA 2025.1+), Gradle, JUnit 5 + MockK. Module: `:agent` (depends only on `:core`). Golden-snapshot tests via `SystemPromptIdeContextTest` (orchestrator) + `SubagentSystemPromptSnapshotTest` (sub-agent).

**Spec:** `docs/superpowers/specs/2026-06-29-plugin-split-phase3-design.md` (v2, review-hardened).

**Plan version:** v2 — revised after two independent opus plan reviews (executability/coverage + correctness-skeptic). Fixes folded: snapshot run-command must be comparison-only (`*SNAPSHOT*`, not class-level — else the generator masks failures); new test call-sites must be one-arg-per-line (else `ArgumentListWrapping`/`MaxLineLength` detekt failure); Site C persona-body pointers reworded to not reference a "Subagent Delegation list" a sub-agent prompt lacks; misc line-number/wording fixes. Review provenance in the trailer.

## Global Constraints

- Work on branch `feature/plugin-split`. Do NOT branch off `main`.
- **NO `Co-Authored-By` / "Generated with" trailer** on any commit (hard user rule).
- `supportsSpring` = `hasSpringPlugin && (Language.JAVA in languages)` (`IdeContext.kt:44-45` / `:34-35`). The gate uses `ideContext.supportsSpring`; `null` ideContext returns all configs (back-compat — do not change the early-return).
- **Advisory gate (do NOT "fix"):** `AgentConfigLoader.getCachedConfig` reads the unfiltered cache by design (`AgentConfigLoader.kt:193-196`); a by-name `agent(agent_type="security-auditor")` still resolves and runs degraded (its `spring(...)` tool simply absent — `BUNDLED_AGENT_FILES` still lists both at `:639-640`). This is intended — document it, never "harden" it into a block.
- **Detekt is strict (`formatting.active: true`, `autoCorrect: false`, `config/detekt/detekt.yml:6-9`).** Any NEW `ArgumentListWrapping` / `MaxLineLength` / `ImportOrdering` issue fails the gate. Run `./gradlew :agent:detekt --rerun-tasks` after Kotlin edits (build cache masks per-module debt). NEVER wholesale-regenerate the detekt baseline; migrate `<ID>` lines surgically if ever needed. (No files move across modules in this phase, so the per-module-baseline-by-filename trap does not apply.)
- macOS: if Gradle config-cache trips, run `./gradlew --stop`. Do NOT pass `--no-build-cache` (corrupts config-cache on macOS).
- **Snapshot workflow (critical — comparison-only filter):** run the COMPARISON tests via `--tests "*SNAPSHOT*"` (this excludes the `generate all golden snapshots` generator, which would otherwise rewrite the fixtures and mask both expected failures and unintended drift) → expect the listed failures → review each diff equals the expected delta ONLY → regenerate via the relevant `*generate all golden snapshots*` task → re-run `--tests "*SNAPSHOT*"` green → commit the regenerated `.txt` alongside the code.
- Sub-agent model floor for any delegated work: sonnet (opus for hardest); never haiku.

---

### Task 1: Site A — `filterByIdeContext` gate + unit tests

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/AgentConfigLoader.kt:230-234` (the `when` block inside `filterByIdeContext`) + its KDoc (`:219-226`)
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/subagent/AgentConfigFilterTest.kt`

**Interfaces:**
- Consumes: `AgentConfigLoader.filterByIdeContext(configs: List<AgentConfig>, ideContext: IdeContext?): List<AgentConfig>`; `IdeContext.supportsSpring: Boolean`.
- Produces: nothing new for later tasks (behavioral change only).

- [ ] **Step 1: Extend the test helper to allow a Spring context**

In `AgentConfigFilterTest.kt`, replace the `makeIdeContext` helper (currently lines 125-139) with one that takes a `hasSpring` flag (default `false`, so all six existing call sites — `:41,52,62,73,94,108` — compile unchanged):

```kotlin
    private fun makeIdeContext(
        product: IdeProduct,
        supportsJava: Boolean,
        supportsPython: Boolean,
        hasSpring: Boolean = false,
    ) = IdeContext(
        product = product,
        productName = product.name,
        edition = Edition.COMMUNITY,
        languages = buildSet {
            if (supportsJava) { add(Language.JAVA); add(Language.KOTLIN) }
            if (supportsPython) add(Language.PYTHON)
        },
        hasJavaPlugin = supportsJava,
        hasPythonPlugin = supportsPython,
        hasPythonCorePlugin = supportsPython,
        hasSpringPlugin = hasSpring,
        detectedFrameworks = emptySet(),
        detectedBuildTools = emptySet(),
    )
```

- [ ] **Step 2: Write the failing tests**

Add a dedicated config list and three tests to `AgentConfigFilterTest.kt` (do NOT add the personas to the shared `sampleConfigs` — the `null ideContext returns all configs` test asserts `assertEquals(4, …)` and would break). Insert after the existing tests, before the `makeConfig` helper. **Note (detekt):** each `makeIdeContext(...)` call uses one-argument-per-line — a wrapped paren with all args on one line trips `ArgumentListWrapping`, and a single line would breach `MaxLineLength` (≈129 chars):

```kotlin
    private val springGatedConfigs = listOf(
        makeConfig("code-reviewer", "Code review"),
        makeConfig("security-auditor", "Security audit"),
        makeConfig("performance-engineer", "Performance optimization"),
    )

    @Test
    fun `security-auditor and performance-engineer present when supportsSpring`() {
        val context = makeIdeContext(
            product = IdeProduct.INTELLIJ_ULTIMATE,
            supportsJava = true,
            supportsPython = false,
            hasSpring = true,
        )
        val names = loader.filterByIdeContext(springGatedConfigs, context).map { it.name }
        assertTrue("security-auditor" in names)
        assertTrue("performance-engineer" in names)
        assertTrue("code-reviewer" in names)
    }

    @Test
    fun `security-auditor and performance-engineer absent on Java without Spring plugin`() {
        val context = makeIdeContext(
            product = IdeProduct.INTELLIJ_COMMUNITY,
            supportsJava = true,
            supportsPython = false,
            hasSpring = false,
        )
        val names = loader.filterByIdeContext(springGatedConfigs, context).map { it.name }
        assertFalse("security-auditor" in names)
        assertFalse("performance-engineer" in names)
        assertTrue("code-reviewer" in names)
    }

    @Test
    fun `security-auditor and performance-engineer absent in non-Java context`() {
        val context = makeIdeContext(
            product = IdeProduct.PYCHARM_COMMUNITY,
            supportsJava = false,
            supportsPython = true,
            hasSpring = false,
        )
        val names = loader.filterByIdeContext(springGatedConfigs, context).map { it.name }
        assertFalse("security-auditor" in names)
        assertFalse("performance-engineer" in names)
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :agent:test --tests "*AgentConfigFilterTest*"`
Expected: the two `absent …` tests FAIL (before the gate, `filterByIdeContext` returns both personas via `else -> true`, so `assertFalse` fails). The `present when supportsSpring` test passes already (regression guard).

- [ ] **Step 4: Implement the gate**

In `AgentConfigLoader.kt`, add the two cases to the `when` inside `filterByIdeContext` (currently lines 230-234):

```kotlin
        return configs.filter { config ->
            when (config.name.lowercase()) {
                "spring-boot-engineer" -> ideContext.supportsJava
                "python-engineer" -> ideContext.supportsPython  // ships with Plan C
                "security-auditor" -> ideContext.supportsSpring
                "performance-engineer" -> ideContext.supportsSpring
                else -> true // universal agents always available
            }
        }
```

Also update the KDoc above `filterByIdeContext` (lines ~219-226) to mention the two `supportsSpring`-gated personas.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :agent:test --tests "*AgentConfigFilterTest*"`
Expected: PASS (all cases, including the existing 8).

- [ ] **Step 6: Detekt the touched files**

Run: `./gradlew :agent:detekt --rerun-tasks`
Expected: no NEW issues in `AgentConfigLoader.kt` / `AgentConfigFilterTest.kt` (the one-arg-per-line call form keeps `ArgumentListWrapping`/`MaxLineLength` clean). Pre-existing baselined `:agent` debt is acceptable; do not regenerate the baseline.

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/AgentConfigLoader.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/subagent/AgentConfigFilterTest.kt
git commit -m "feat(agent): gate security-auditor + performance-engineer on supportsSpring (Site A)"
```

---

### Task 2: Sites B + C — neutralize the prose advertisement + regenerate snapshots + doc

> Note on task split: Site A (Task 1) and Sites B+C (this task) ship in separate commits, so the advertisement is transiently incoherent between the two commits (A gated; B+C still steering). This is acceptable — both commits land on `feature/plugin-split` before the whole-phase gate (which re-checks all three surfaces), and the file sets are disjoint. The spec §8 "all three in the same task" intent is satisfied by both landing in the same phase.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt:970-971` (Site B)
- Modify: `agent/src/main/resources/agents/code-reviewer.md:104` (Site C)
- Modify: `agent/src/main/resources/agents/architect-reviewer.md:123` (Site C)
- Modify: `agent/src/main/resources/skills/subagent-driven/SKILL.md:61-62` (Site C)
- Regenerate: `agent/src/test/resources/prompt-snapshots/{intellij-community,pycharm-professional,pycharm-community,webstorm}.txt`
- Regenerate: `agent/src/test/resources/subagent-prompt-snapshots/{code-reviewer-intellij-ultimate,architect-reviewer-intellij-community}.txt`
- Modify: `agent/CLAUDE.md` ("Agent Persona Filtering" + the "IDE-Aware System Prompt (Plan D)" note)

**Interfaces:**
- Consumes: `SystemPrompt.build(..., ideContext)`; `IdeContext.supportsSpring`.
- Produces: nothing for later tasks.

- [ ] **Step 1: Site B — gate the prompt prose**

In `SystemPrompt.kt`, wrap the two consecutive trailing `appendLine`s (currently 970-971, right after the `devops-engineer` line at 969 and before the blank `appendLine()` at 972) in the `supportsSpring` predicate, mirroring the `spring-boot-engineer` block above (961-963):

```kotlin
            appendLine("- \"devops-engineer\" — CI/CD, Docker, Maven build config, AWS deployment configs.")
            if (ideContext == null || ideContext.supportsSpring) {
                appendLine("- \"security-auditor\" — security audit: OWASP Top 10, Spring Security, secrets, dependency CVEs.")
                appendLine("- \"performance-engineer\" — performance: database, caching, HTTP clients, JVM tuning.")
            }
```

- [ ] **Step 2: Site C — neutralize the `code-reviewer.md` pointer**

In `agent/src/main/resources/agents/code-reviewer.md`, replace line 104. (This persona runs as a sub-agent with no "# Subagent Delegation" section and no `agent` tool, so the new text simply scopes the work out rather than steering to a named/possibly-unavailable persona):

```markdown
> **Note:** Deep security auditing and performance profiling are separate specialist concerns — out of scope for this code review.
```

- [ ] **Step 3: Site C — neutralize the `architect-reviewer.md` pointer (keep the existing universal `code-reviewer` reference)**

In `agent/src/main/resources/agents/architect-reviewer.md`, replace line 123:

```markdown
> **Note:** For line-level code quality (correctness, naming, test coverage) use `code-reviewer`. Security auditing is a separate specialist concern, out of scope for this architecture review.
```

- [ ] **Step 4: Site C — neutralize the `subagent-driven/SKILL.md` table rows**

In `agent/src/main/resources/skills/subagent-driven/SKILL.md`, delete the two table rows (currently 61-62) for `security-auditor` and `performance-engineer`, leaving a blank line after the last remaining row (`devops-engineer`, :60), then add this conditional note. (This skill is activated by the ORCHESTRATOR, which DOES have the gated Subagent Delegation list, so the deferral clause is valid here):

```markdown

> Spring/Java-stack projects additionally offer `security-auditor` (OWASP, dependency CVEs, auth review) and `performance-engineer` (profiling, bottlenecks, query optimization) — check the Subagent Delegation list in your system prompt for what is actually available in this project.
```

(Leave the `spring-boot-engineer` and `devops-engineer` rows unchanged — out of scope this phase.)

- [ ] **Step 5: Run the comparison snapshot tests to see the expected failures**

Run: `./gradlew :agent:test --tests "*SNAPSHOT*"`
(Comparison-only — this filter excludes the `generate all golden snapshots` generators in both classes, which would otherwise rewrite the fixtures and hide the failures.)
Expected failures (6 total):
- Orchestrator (Site B): `intellij-community`, `pycharm-professional`, `pycharm-community`, `webstorm` — each diff is the **deletion of exactly two lines** (`- "security-auditor" …` and `- "performance-engineer" …`).
- Sub-agent (Site C): `code-reviewer-intellij-ultimate` and `architect-reviewer-intellij-community` — each diff is the **reworded `> **Note:** …` sentence** only.
- Unchanged (must NOT fail): `intellij-ultimate`, `intellij-ultimate-mixed`, `null-context`, `no-integrations`, `jira-only`, `no-jira`, and the other 5 sub-agent snapshots. If any of these fail, STOP — the predicate or an edit is wrong.

- [ ] **Step 6: Review each diff, then regenerate**

Confirm every failing diff matches Step 5 exactly (no stray deltas). Then regenerate both snapshot sets:

```bash
./gradlew :agent:test --tests "*SystemPromptIdeContextTest*generate all golden snapshots*"
./gradlew :agent:test --tests "*SubagentSystemPromptSnapshotTest*generate all golden*"
```

- [ ] **Step 7: Re-run the comparison snapshot tests to verify they pass**

Run: `./gradlew :agent:test --tests "*SNAPSHOT*"`
Expected: PASS.

- [ ] **Step 8: Document the gate in `agent/CLAUDE.md`**

(a) In the "Agent Persona Filtering" section, add `security-auditor` and `performance-engineer` to the gated list (on `supportsSpring`) and record the advisory semantics:

```markdown
- `security-auditor` / `performance-engineer` → only when `IdeContext.supportsSpring` (Site A `filterByIdeContext` + Site B `SystemPrompt` prose + the Site C routing pointers in code-reviewer/architect-reviewer/subagent-driven). **Advisory gate:** `SpawnAgentTool.execute()` resolves a persona by name via the *unfiltered* `getCachedConfig`, so an explicit `agent(agent_type="security-auditor")` still runs (degraded — its `spring` tool absent) even when un-advertised. This is intentional; do not "fix" it into a hard block.
```

(b) In the "### IDE-Aware System Prompt (Plan D)" section, append one line to the `rules()` row note recording that the subagent listing now also gates `security-auditor`/`performance-engineer` on `supportsSpring` (the `spring-boot-engineer` line is `supportsJava`-gated; these two are `supportsSpring`-gated).

- [ ] **Step 9: Detekt + full module test**

Run: `./gradlew :agent:detekt --rerun-tasks` (no new issues in `SystemPrompt.kt`) and `./gradlew :agent:test` (full module green).

- [ ] **Step 10: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt \
        agent/src/main/resources/agents/code-reviewer.md \
        agent/src/main/resources/agents/architect-reviewer.md \
        agent/src/main/resources/skills/subagent-driven/SKILL.md \
        agent/src/test/resources/prompt-snapshots/ \
        agent/src/test/resources/subagent-prompt-snapshots/ \
        agent/CLAUDE.md
git commit -m "feat(agent): de-advertise Spring personas in prompt prose + routing pointers (Sites B+C)"
```

---

### Task 3: Workstream 2 — trim the `git-workflow` skill

**Files:**
- Modify: `agent/src/main/resources/skills/git-workflow/SKILL.md:56-68`

**Interfaces:** none (resource-only; behavior-neutral).

- [ ] **Step 1: Replace the two Atlassian sections with one neutral, conditional section**

In `agent/src/main/resources/skills/git-workflow/SKILL.md`, replace lines 56-68 (the `## PR-Related Tasks` section through the `bitbucket_repo(...)` bullet of `## CI Context`; line 69 is the blank line before `## Destructive Operations` at 70 — leave it) with:

```markdown
## PR & CI tasks (when integrations are configured)

These tools are deferred and appear only when their service URL is set in settings — load them via `tool_search` first.

- Pull requests: `bitbucket_pr(action="get_pr_diff")` / `get_pr_changes` / `get_pr_commits` / `create_pr`
- Build/CI status: `bamboo_builds(action="build_status")`, `bitbucket_repo(action="get_build_statuses")`
```

(Leave the surrounding generic sections — `## Understanding Branch Divergence`, `## Shelving Changes`, `## Destructive Operations`, `## Common Mistakes to Avoid` — unchanged. Keep the `description:` frontmatter as is.)

- [ ] **Step 2: Verify no test regression**

Run: `./gradlew :agent:test --tests "*InstructionLoader*" --tests "*SkillVariant*"`
Expected: PASS (these load skills generically; `SkillVariantTest` asserts only `variant.length > base.length` for subagent-driven, unaffected; none asserts this skill's text).

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/resources/skills/git-workflow/SKILL.md
git commit -m "docs(agent): neutralize git-workflow skill PR/CI framing (keep OSS-generic action bullets)"
```

---

### Task 4: Phase docs — design-doc §24 + release note

**Files:**
- Modify: `docs/superpowers/specs/2026-06-22-plugin-split-design.md` (append `## 24. Phase 3 resolved`; the doc currently ends at `## 23. Phase 2c`)
- Modify: `CHANGELOG.md` (repo root; add a line under the existing `## [Unreleased]` section)

**Interfaces:** none.

- [ ] **Step 1: Add §24 to the main design doc**

Append to `docs/superpowers/specs/2026-06-22-plugin-split-design.md`:

```markdown
## 24. Phase 3 resolved (2026-06-29)

**Persona / skill hardening.** `security-auditor` + `performance-engineer` are now gated on `supportsSpring` across all three advertisement surfaces — `AgentConfigLoader.filterByIdeContext` (Site A, live list), the `SystemPrompt.kt` "# Subagent Delegation" prose (Site B), and the three routing pointers in `code-reviewer.md` / `architect-reviewer.md` / `subagent-driven/SKILL.md` (Site C). The gate is **advisory**: `SpawnAgentTool.execute()` resolves a named persona via the unfiltered `getCachedConfig`, so an explicit by-name spawn still runs (degraded, its `spring` tool absent) — intended escape hatch, kept by user decision. `git-workflow/SKILL.md` neutralized to generic git + a conditional PR/CI section that retains the OSS-generic `bitbucket_pr`/`bamboo_builds` action bullets. 6 golden snapshots regenerated (4 orchestrator two-line deletions + 2 sub-agent pointer rewordings). **Deferred to Phase 5:** `devops-engineer` → B; physical repo extraction; the sibling-skill Atlassian/`master` coupling in `systematic-debugging`/`create-skill`. Predicate = `supportsSpring` for both and gate = advisory + fixed routing pointers were USER decisions (2026-06-29). Spec: `docs/superpowers/specs/2026-06-29-plugin-split-phase3-design.md`.
```

- [ ] **Step 2: Add the release note**

Add a one-line entry under the `## [Unreleased]` section of `CHANGELOG.md`:

```markdown
- The `security-auditor` and `performance-engineer` specialist agents are now offered only on Spring projects (Java + the IntelliJ Spring plugin); they remain spawnable explicitly by name on any project.
```

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-06-22-plugin-split-design.md CHANGELOG.md
git commit -m "docs(plugin-split): record Phase 3 (§24) + release note"
```

---

## Phase Gate (after all tasks — whole-phase review + full gate)

- [ ] Whole-phase review (independent, opus): verify all three surfaces (A/B/C) gate consistently on `supportsSpring` with list-when-`null`; the advisory semantics are documented and not accidentally turned into a hard block; the 6 snapshot deltas are exactly as specified and no other snapshot drifted; the git-workflow edit keeps the action bullets. Grep all A modules' `src/main` for any code (beyond the documented prose/schema strings) that assumes these personas are present — expect none.
- [ ] `./gradlew :agent:test` green.
- [ ] `./gradlew verifyPlugin` green.
- [ ] `./gradlew koverVerify -Pcoverage -x uiTest` (retry the known #51 timing/socket flakes under instrumentation).
- [ ] 0 Critical / 0 Important from the whole-phase review; DoD (spec §9) met.

---

## Self-Review (completed against spec v2)

- **Spec coverage:** Site A → Task 1; Site B + Site C + 6 snapshots + CLAUDE.md → Task 2; git-workflow → Task 3; design §24 + CHANGELOG → Task 4. Advisory-gate documentation → Task 2 Step 8 + Task 4 Step 1. All §6 files-touched are covered.
- **Placeholder scan:** no TBD/TODO; every code/markdown step shows exact content. `CHANGELOG.md` confirmed present at repo root with an `## [Unreleased]` section.
- **Type/name consistency:** `filterByIdeContext`, `supportsSpring`, `makeIdeContext(hasSpring=…)`, `springGatedConfigs`, snapshot task names, and the persona names are consistent across tasks. No interface is referenced before it exists (behavioral change only; no new public symbols).

---

## Review provenance (v2)

Revised after two independent opus plan reviews of v1:
- **Executability + coverage** — VERDICT READY-WITH-FIXES. [Important] the snapshot run-command must be comparison-only (`*SNAPSHOT*`), else the class-level filter runs the generator and masks failures (fixed in Global Constraints + Task 2 Steps 5/7). Minors: Task 2 Step 8 second CLAUDE.md edit now spelled out; A/B-C task-split deviation noted. Confirmed all exact edits, line numbers, command validity, the 4-fail/6-unchanged snapshot split, spec coverage, TDD soundness, ordering; `CHANGELOG.md` exists; design doc ends at §23.
- **Correctness skeptic** — VERDICT SOUND-WITH-FIXES. [Important] new test call-sites must be one-arg-per-line (else `ArgumentListWrapping`/`MaxLineLength` detekt failure) — fixed in Task 1 Step 2. Minors: Site C persona-body pointers reworded to not cite a "Subagent Delegation list" a sub-agent prompt lacks (Steps 2-3); blank line before the subagent-driven note (Step 4); Task 3 bullets aligned to `action="…"`. Verified: 2-file snapshot ripple, subagent-driven affects no snapshot, red→green logic, defaulted-param safety, Site B → exactly 4 snapshots, git-workflow range, advisory-gate integrity, `SkillVariantTest` stays green.
