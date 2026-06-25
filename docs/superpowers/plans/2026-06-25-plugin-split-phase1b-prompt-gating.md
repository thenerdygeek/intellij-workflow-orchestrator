# Plugin Split — Phase 1b: Gate the agent system prompt on configured integrations

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The agent's system prompt must only mention an Atlassian/Sonar integration (Jira / Bamboo / SonarQube / Bitbucket) when that integration is actually configured — so Plugin A's open-source prompt doesn't unconditionally leak the company stack.

**Architecture:** Introduce a small `IntegrationFlags(jira, bamboo, sonar, bitbucket)` value type resolved from `ConnectionSettings` (`*Url.isNotBlank()` — the exact predicate `AgentService.reregisterConditionalTools` already uses for tool gating). Thread it into `SystemPrompt.build()` (default `IntegrationFlags.NONE`), which passes it to the four section builders (`agentRole`, `outputFormatting`, `capabilities`, `rules`) that gate their six integration-specific fragments. The flags are PASSED IN by the three real callers (orchestrator execute + resume, sub-agent builder) — never read from `ConnectionSettings` inside `build()`, because the golden-snapshot tests are pure JUnit 5 with no IntelliJ Application. With all four flags TRUE the rendered prompt is **byte-identical to today** (parity), proven by regenerating the existing 7 orchestrator + 7 sub-agent golden snapshots with `IntegrationFlags.ALL` and getting an empty git diff.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform Plugin v2, JUnit 5 (pure — golden snapshot tests), Gradle. Module: `:agent` (depends on `:core` for `ConnectionSettings`).

## Global Constraints

- **No `Co-Authored-By` trailer in ANY commit.** Plain messages. (User standing rule.)
- **All-ON == today, byte-exact.** Every gating site, when its flag is TRUE, must render the *exact current text* (same words, order, whitespace). The snapshot-regenerate-with-ALL diff MUST be empty for the existing 7 orchestrator + 7 sub-agent snapshots. This is the parity contract.
- **Flags are PASSED IN, never resolved inside `build()`.** Snapshot tests can't call `ConnectionSettings.getInstance()` (pure JUnit 5, no Application). `build()`'s new param defaults to `IntegrationFlags.NONE`.
- **`:agent` ONE-`BasePlatformTestCase` invariant:** all tests here are pure JUnit 5 — do NOT add a `BasePlatformTestCase`.
- **Snapshot workflow (agent/CLAUDE.md):** after changing `SystemPrompt.kt`, run the `*SNAPSHOT*`/validate tests (expect failures on changed variants), review the diff, regenerate via the `*generate all golden*` test, re-validate, commit the `.txt` alongside the code.
- **Build-cache:** `SystemPrompt.build()` gains a new param (not an interface default-method / suspend change), so plain `:agent:test` is fine; if a stale-bytecode `NoSuchMethodError` appears, use `--no-build-cache --rerun-tasks` (and note: a full `:agent:test` already needs `--rerun-tasks` per the 1a finding when the detekt cache is involved — irrelevant here, tests only).
- **Docs in the same commit** as the code (`agent/CLAUDE.md` IDE-Aware-System-Prompt section, spec §20).
- **`docs/superpowers/plans/` is gitignored** — force-add this plan.

## Scope

**IN:** the 6 gating sites in `SystemPrompt.kt`, the `IntegrationFlags` type, the 3 caller wirings (orchestrator execute + resume, sub-agent), the snapshot strategy (preserve 7+7 as ALL + add 3 gated variants: no-integrations, jira-only, no-jira), docs.

**OUT (leave unconditioned — generic, not company-specific):** the Docker mention in the `run_command` tip + the `devops-engineer` delegation line; "sprint" in delegation text. No Sourcegraph/Confluence appears in `SystemPrompt.kt`. **Persona role-text and the `supportsSpring` gate are Phase 3, NOT 1b.** **Tool *registration* is already gated** (`reregisterConditionalTools`) — 1b only aligns the PROSE with it.

## The 6 gating sites (exact, in `agent/.../prompt/SystemPrompt.kt`)

| # | Fn | Line(s) | Current text → gated behavior |
|---|---|---|---|
| 1 | `agentRole()` | 337 | `"…refactoring engine, and enterprise integrations (Jira, Bamboo, SonarQube, Bitbucket). You help…"` → list only configured (order Jira, Bamboo, SonarQube, Bitbucket); if none, drop `", and enterprise integrations (…)"`. ORCHESTRATOR-only (sub-agents use `agentRoleOverride`). |
| 2 | `outputFormatting()` | 403, 406 | the `- Jira tickets: [PROJ-1234](jira:PROJ-1234)` scheme line + the `, a regression from [WORK-1234](jira:WORK-1234)` example tail → include both only if `jira`. |
| 3 | `capabilities()` | 521 | `"- **Project integrations** → jira, bamboo_builds, bamboo_plans, sonar, bitbucket_pr, bitbucket_repo, bitbucket_review"` → build the tool list from configured set; omit the line if none. |
| 4 | `capabilities()` | 537 | the `project_context` comprehensive-state list → per-clause: `active Jira ticket`(jira), `PR status`(bitbucket), `build results`(bamboo), `Sonar quality gate`(sonar); keep branch/uncommitted/service keys/project type. |
| 5 | `capabilities()` | 540 | the `sonar(action="local_analysis"…)` tip line → only if `sonar`. |
| 6 | `rules()` | 856–868 | the `# Jira Transition — Field Collection Pattern` block → only if `jira`. |

---

## Task 1: `IntegrationFlags` value type

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/IntegrationFlags.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/IntegrationFlagsTest.kt`

**Interfaces:**
- Produces: `data class IntegrationFlags(jira, bamboo, sonar, bitbucket: Boolean)`; `val any: Boolean`; `companion { NONE; ALL; fun from(state: ConnectionSettings.State): IntegrationFlags }`.

- [ ] **Step 1: Write the failing test**

Create `IntegrationFlagsTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.prompt

import com.workflow.orchestrator.core.settings.ConnectionSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntegrationFlagsTest {
    @Test fun `NONE has nothing configured`() {
        assertFalse(IntegrationFlags.NONE.any)
        assertFalse(IntegrationFlags.NONE.jira)
    }

    @Test fun `ALL has everything configured`() {
        val a = IntegrationFlags.ALL
        assertTrue(a.jira && a.bamboo && a.sonar && a.bitbucket && a.any)
    }

    @Test fun `from resolves each flag off a blank vs non-blank service URL`() {
        val state = ConnectionSettings.State(
            jiraUrl = "https://jira.example.com",
            bambooUrl = "",
            sonarUrl = "https://sonar.example.com",
            bitbucketUrl = "   ",
        )
        val f = IntegrationFlags.from(state)
        assertTrue(f.jira)
        assertFalse(f.bamboo)
        assertTrue(f.sonar)
        assertFalse(f.bitbucket) // blank-but-whitespace counts as not configured
        assertEquals(IntegrationFlags(jira = true, sonar = true), f)
    }
}
```

> NOTE for the implementer: verify `ConnectionSettings.State` is a plain `data class` with named-arg constructible URL fields (it is — `:core` `ConnectionSettings` is a plain `PersistentStateComponent` whose `State` is a data class). If the field names differ, match them; the four are `jiraUrl`, `bambooUrl`, `sonarUrl`, `bitbucketUrl`.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.prompt.IntegrationFlagsTest"`
Expected: FAIL — `IntegrationFlags` unresolved.

- [ ] **Step 3: Create `IntegrationFlags.kt`**

```kotlin
package com.workflow.orchestrator.agent.prompt

import com.workflow.orchestrator.core.settings.ConnectionSettings

/**
 * Which Atlassian/Sonar integrations are configured, derived from [ConnectionSettings].
 * Drives system-prompt gating (Phase 1b de-convention): the prompt mentions an integration
 * only when its flag is true. Resolved by the prompt's callers and PASSED IN to
 * [SystemPrompt.build] — never read from ConnectionSettings inside build(), which must stay
 * pure for the golden-snapshot tests.
 */
data class IntegrationFlags(
    val jira: Boolean = false,
    val bamboo: Boolean = false,
    val sonar: Boolean = false,
    val bitbucket: Boolean = false,
) {
    /** True if at least one integration is configured. */
    val any: Boolean get() = jira || bamboo || sonar || bitbucket

    companion object {
        val NONE = IntegrationFlags()
        val ALL = IntegrationFlags(jira = true, bamboo = true, sonar = true, bitbucket = true)

        /** Mirror of [com.workflow.orchestrator.agent.AgentService] tool-gating: `*Url.isNotBlank()`. */
        fun from(state: ConnectionSettings.State) = IntegrationFlags(
            jira = state.jiraUrl.isNotBlank(),
            bamboo = state.bambooUrl.isNotBlank(),
            sonar = state.sonarUrl.isNotBlank(),
            bitbucket = state.bitbucketUrl.isNotBlank(),
        )
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.prompt.IntegrationFlagsTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/IntegrationFlags.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/IntegrationFlagsTest.kt
git commit -m "feat(agent): IntegrationFlags value type for system-prompt gating (1b)"
```

---

## Task 2: Gate the 6 sites in `SystemPrompt.kt` + orchestrator snapshots

**Files:**
- Modify: `agent/.../prompt/SystemPrompt.kt` (`build()` signature + the 4 section builders + 4 invocation sites)
- Modify: `agent/src/test/kotlin/.../prompt/SystemPromptIdeContextTest.kt` (pass `IntegrationFlags.ALL` to existing variants; add gated variants)
- Add: `agent/src/test/resources/prompt-snapshots/no-integrations.txt`, `jira-only.txt` (generated)
- Create: `agent/src/test/kotlin/.../prompt/SystemPromptIntegrationGatingTest.kt` (characterization)

**Interfaces:**
- Consumes: `IntegrationFlags` (Task 1).
- Produces: `SystemPrompt.build(..., integrations: IntegrationFlags = IntegrationFlags.NONE)`.

- [ ] **Step 1: Write the failing characterization test**

Create `SystemPromptIntegrationGatingTest.kt` — pins both directions + parity:

```kotlin
package com.workflow.orchestrator.agent.prompt

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SystemPromptIntegrationGatingTest {
    private fun prompt(flags: IntegrationFlags) = SystemPrompt.build(
        projectName = "P", projectPath = "/p", osName = "Linux", shell = "/bin/bash",
        homeDir = "/home", integrations = flags,
    )

    @Test fun `all-on prompt mentions every integration`() {
        val p = prompt(IntegrationFlags.ALL)
        assertTrue(p.contains("enterprise integrations (Jira, Bamboo, SonarQube, Bitbucket)"))
        assertTrue(p.contains("- Jira tickets: [PROJ-1234](jira:PROJ-1234)"))
        assertTrue(p.contains("jira, bamboo_builds, bamboo_plans, sonar, bitbucket_pr, bitbucket_repo, bitbucket_review"))
        assertTrue(p.contains("Sonar quality gate"))
        assertTrue(p.contains("sonar(action=\"local_analysis\""))
        assertTrue(p.contains("# Jira Transition — Field Collection Pattern"))
    }

    @Test fun `no-integration prompt omits all stack mentions`() {
        val p = prompt(IntegrationFlags.NONE)
        assertFalse(p.contains("enterprise integrations"))
        assertFalse(p.contains("(jira:"))                 // no jira: URL scheme
        assertFalse(p.contains("or Jira ticket"))         // outputFormatting lead-line softened
        assertFalse(p.contains("bamboo_builds"))
        assertFalse(p.contains("SonarQube"))
        assertFalse(p.contains("Sonar quality gate"))
        assertFalse(p.contains("# Jira Transition"))
        assertFalse(p.contains("active Jira ticket"))
    }

    @Test fun `jira-only prompt has Jira fragments but not bamboo or sonar`() {
        val p = prompt(IntegrationFlags(jira = true))
        assertTrue(p.contains("enterprise integrations (Jira)"))
        assertTrue(p.contains("- Jira tickets: [PROJ-1234](jira:PROJ-1234)"))
        assertTrue(p.contains("# Jira Transition — Field Collection Pattern"))
        assertTrue(p.contains("active Jira ticket"))
        assertFalse(p.contains("bamboo_builds"))
        assertFalse(p.contains("SonarQube"))
        assertFalse(p.contains("- **Project integrations** → jira, bamboo")) // list trimmed to "jira"
        assertTrue(p.contains("- **Project integrations** → jira\n"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :agent:test --tests "*SystemPromptIntegrationGatingTest*"`
Expected: FAIL — `integrations` param doesn't exist; the no/partial assertions fail (prompt is currently unconditional).

- [ ] **Step 3: Add the `integrations` param to `build()` + thread it to the 4 builders**

In `SystemPrompt.build(...)`, add as the LAST parameter (after `delegationTargets`):

```kotlin
    /**
     * Which Atlassian/Sonar integrations are configured. Gates the prompt's integration-specific
     * fragments (Phase 1b de-convention). Default NONE = open-source-neutral (no stack mentioned).
     * Resolved by the caller from ConnectionSettings and passed in — build() stays pure so the
     * golden-snapshot tests need no Application. ALL reproduces the pre-1b prompt byte-for-byte.
     */
    integrations: IntegrationFlags = IntegrationFlags.NONE,
```

Update the four invocation sites in the `build()` body:
- Line 201: `append(agentRoleOverride ?: agentRole(ideContext, integrations))`
- Line 218: `append(outputFormatting(integrations.jira))`
- Line 240: `append(capabilities(projectPath, ideContext, delegationOutboundEnabled, delegationTargets, hasWebTools, integrations))`
- Line 297: `append(rules(projectPath, ideContext, availableModels, includeSubagentDelegationInRules, hasWebTools, integrations.jira))`

- [ ] **Step 4: Gate `agentRole()` (site 1)**

Replace the body of `agentRole`:

```kotlin
    private fun agentRole(ideContext: IdeContext?, integrations: IntegrationFlags): String {
        val ideName = ideContext?.productName ?: "IntelliJ IDEA"
        val names = buildList {
            if (integrations.jira) add("Jira")
            if (integrations.bamboo) add("Bamboo")
            if (integrations.sonar) add("SonarQube")
            if (integrations.bitbucket) add("Bitbucket")
        }
        val integrationsClause =
            if (names.isEmpty()) "" else ", and enterprise integrations (${names.joinToString(", ")})"
        return "You are an AI coding agent running inside $ideName. You have programmatic access to the IDE's debugger, test runner, code analysis, build system, refactoring engine$integrationsClause. You help users with software engineering tasks by using IDE-native tools that are faster and more accurate than shell equivalents. You are highly skilled with extensive knowledge of programming languages, frameworks, design patterns, and best practices."
    }
```

(All-ON → `…refactoring engine, and enterprise integrations (Jira, Bamboo, SonarQube, Bitbucket). You help…` — byte-exact. None → `…refactoring engine. You help…`.)

- [ ] **Step 5: Gate `outputFormatting()` (site 2)**

Change the signature + interpolate two conditional fragments so the Jira scheme line sits between "Code symbols" and "External URLs", and the example tail is appended:

```kotlin
    private fun outputFormatting(jiraConfigured: Boolean): String {
        val jiraScheme = if (jiraConfigured) "\n- Jira tickets: [PROJ-1234](jira:PROJ-1234)" else ""
        val jiraExampleTail = if (jiraConfigured) ", a regression from [WORK-1234](jira:WORK-1234)" else ""
        return """OUTPUT FORMATTING

In prose, ALWAYS format a mention of a file, code symbol, or Jira ticket as a markdown link with one of the custom URL schemes below — NEVER as plain text (the chat UI renders these as clickable navigation; plain text is dead). Unresolvable symbols fall back to plain text automatically.

Schemes:
- Files: [path/to/Foo.kt](file:path/to/Foo.kt) — with a line [Foo.kt:42](file:path/to/Foo.kt:42) or range [Foo.kt:42-58](file:path/to/Foo.kt:42-58)
- Code symbols (any language): [Foo](symbol:com.example.Foo) for a type, [Foo#run](symbol:com.example.Foo#run) for a member. Always use the fully qualified name; bare names may not resolve.$jiraScheme
- External URLs: standard markdown link

EXAMPLE: I traced the bug to [AgentService#run](symbol:com.workflow.orchestrator.agent.service.AgentService#run) at [AgentService.kt:142-156](file:agent/src/main/kotlin/AgentService.kt:142-156)$jiraExampleTail.

CARVE-OUT: inside fenced code blocks and inline code spans, do NOT linkify — code must stay verbatim so it can be copied. Hyperlink formatting applies to prose only."""
    }
```

⚠ Note the "In prose, ALWAYS format a mention of a file, code symbol, **or Jira ticket**…" lead line still says "Jira ticket" — when jira is off, that's a stray mention. Soften the lead to `"a mention of a file or code symbol"` and append `", or Jira ticket"` conditionally too:
- Replace the lead with: `In prose, ALWAYS format a mention of a file, code symbol${if (jiraConfigured) ", or Jira ticket" else ""} as a markdown link…`
(Add a `val jiraLead = if (jiraConfigured) ", or Jira ticket" else ""` and interpolate. All-ON byte-exact: "a file, code symbol, or Jira ticket as".)

- [ ] **Step 6: Gate `capabilities()` (sites 3, 4, 5)**

Change the `capabilities(...)` signature to add `integrations: IntegrationFlags = IntegrationFlags.NONE` as the last param. Then:

**Site 3 (replace line 521):**
```kotlin
        val integrationTools = buildList {
            if (integrations.jira) add("jira")
            if (integrations.bamboo) { add("bamboo_builds"); add("bamboo_plans") }
            if (integrations.sonar) add("sonar")
            if (integrations.bitbucket) { add("bitbucket_pr"); add("bitbucket_repo"); add("bitbucket_review") }
        }
        if (integrationTools.isNotEmpty()) {
            appendLine("- **Project integrations** → ${integrationTools.joinToString(", ")}")
        }
```

**Site 4 (replace line 537):**
```kotlin
        val contextState = buildList {
            add("branch"); add("uncommitted changes")
            if (integrations.jira) add("active Jira ticket")
            add("service keys")
            if (integrations.bitbucket) add("PR status")
            if (integrations.bamboo) add("build results")
            if (integrations.sonar) add("Sonar quality gate")
            add("project type")
        }
        appendLine("- Load project_context via tool_search early to get comprehensive state: ${contextState.joinToString(", ")}.")
```

**Site 5 (wrap line 540):**
```kotlin
        if (integrations.sonar) {
            appendLine("- After refactoring code, use sonar(action=\"local_analysis\", files=...) to get immediate SonarQube feedback on the changed files without waiting for the CI pipeline to complete a full scan. This runs the Sonar scanner locally and fetches fresh issues, hotspots, coverage, and duplications for exactly the files you changed.")
        }
```

(All-ON: site 3 → the exact `jira, bamboo_builds, bamboo_plans, sonar, bitbucket_pr, bitbucket_repo, bitbucket_review`; site 4 → the exact comma list; site 5 → present. Byte-exact.)

- [ ] **Step 7: Gate `rules()` (site 6)**

Change `rules(...)` signature to add `jiraConfigured: Boolean = false` as the last param. Wrap the Jira Transition block (the `// Jira Transition Retry Pattern` comment through its trailing `appendLine()` — current lines 856–868) in:

```kotlin
        // Jira Transition Retry Pattern
        if (jiraConfigured) {
            appendLine("# Jira Transition — Field Collection Pattern")
            appendLine("When calling jira(action=transition, ...):")
            appendLine("- If the response payload_type is `missing_required_fields`, do NOT hallucinate field values.")
            appendLine("  For each listed field, call ask_followup_question asking the user for the field name and any provided hint (e.g. \"Enter reviewer username\").")
            appendLine("  After collecting all values, retry jira(action=transition, key=..., transition_id=..., fields={<fieldId>: <value>, ...}).")
            appendLine("- If the response payload_type is `requires_interaction` (RequiresInteraction), surface the")
            appendLine("  transition name to the user via attempt_completion and stop — dialog opening is not a loop concern.")
            appendLine("- Never re-ask the same field in the same session if the user already provided a value; reuse the previously collected value.")
            appendLine("- fields format: user/assignee/reviewer: {\"name\": \"username\"} | labels: [\"label1\", \"label2\"] |")
            appendLine("  priority/select/option: {\"id\": \"option-id\"} | multi select: [{\"id\": \"a\"}, {\"id\": \"b\"}] |")
            appendLine("  cascading: {\"value\": \"parent\", \"child\": {\"value\": \"child\"}} | version/component: {\"id\": \"id\"} or [{\"id\": \"id\"}, ...]")
            appendLine()
        }
```

(All-ON: identical block + its single leading/trailing blank. Off: the build-file tip's trailing blank flows straight into `# Safety & Reversibility` — single-blank separator preserved, verified by the snapshot diff.)

- [ ] **Step 8: Run the characterization test**

Run: `./gradlew :agent:test --tests "*SystemPromptIntegrationGatingTest*"`
Expected: PASS (3 tests). If `jira-only` list assertion fails on a trailing-newline nuance, inspect the actual capabilities output and adjust the assertion to the real byte form (the gating logic is the source of truth; the test asserts against it).

- [ ] **Step 9: Update the orchestrator snapshot test to pass `ALL` + add gated variants**

In `SystemPromptIdeContextTest.kt`, change `buildPrompt` to take integration flags (default `ALL` so the 7 existing variants stay byte-identical):

```kotlin
        private fun buildPrompt(
            ideContext: IdeContext? = null,
            integrations: IntegrationFlags = IntegrationFlags.ALL,
        ) = SystemPrompt.build(
            projectName = "SnapshotProject",
            projectPath = "/snapshot/project",
            osName = "Linux",
            shell = "/bin/bash",
            homeDir = "/home/snapshot",
            ideContext = ideContext,
            integrations = integrations,
        )
```

In the "generate all golden snapshots" method, AFTER the 7 existing `saveSnapshot(...)` lines add THREE gated variants (the third, `no-jira`, pins jira-OFF against a *non-empty* stack — the separator-flow boundary where a middle clause vanishes):

```kotlin
        saveSnapshot("no-integrations", buildPrompt(null, IntegrationFlags.NONE))
        saveSnapshot("jira-only", buildPrompt(null, IntegrationFlags(jira = true)))
        saveSnapshot("no-jira", buildPrompt(null, IntegrationFlags(bamboo = true, sonar = true, bitbucket = true)))
```

⚠ The generate method ALSO has a count assertion `assertEquals(7, ... "Should have created 7 snapshot files")` (~line 141) — bump it to **10**.

⚠ The VALIDATION is NOT a variant-name list — it is SEVEN separate `@Test` methods (`SNAPSHOT null context matches golden file`, `…IntelliJ Ultimate…`, etc., ~lines 448-515), each hard-coding one `loadSnapshot("name")` + `buildPrompt(factory())` + `assertEquals`. Author THREE new `@Test` methods mirroring that shape, e.g.:

```kotlin
    @Test fun `SNAPSHOT no-integrations matches golden file`() {
        assertEquals(loadSnapshot("no-integrations"), buildPrompt(null, IntegrationFlags.NONE))
    }
    @Test fun `SNAPSHOT jira-only matches golden file`() {
        assertEquals(loadSnapshot("jira-only"), buildPrompt(null, IntegrationFlags(jira = true)))
    }
    @Test fun `SNAPSHOT no-jira matches golden file`() {
        assertEquals(loadSnapshot("no-jira"), buildPrompt(null, IntegrationFlags(bamboo = true, sonar = true, bitbucket = true)))
    }
```
(Match the exact `loadSnapshot`/`assertEquals` shape the existing 7 methods use.)

⚠ The same file also has ~14 OTHER `@Test`s that call `SystemPrompt.build(...)` directly WITHOUT an `integrations` arg (isolation/size/ordering tests). The new `build()` default is `NONE`, so they now build gated-OFF prompts — none of them assert a gated string, so they stay green; confirm this at Step 12 rather than discovering it.

- [ ] **Step 10: Validate (expect the 7 existing to be UNCHANGED), then generate, then re-validate**

Run: `./gradlew :agent:test --tests "*SystemPromptIdeContextTest*"`
Expected: the 7 existing variants PASS UNCHANGED (parity — `buildPrompt` now passes `ALL`, which reproduces today's text); the 2 new variants FAIL (no `.txt` yet).
Then generate: `./gradlew :agent:test --tests "*SystemPromptIdeContextTest*generate all golden*"`
Then: `git diff --stat agent/src/test/resources/prompt-snapshots/` — **the 7 existing `.txt` must show ZERO changes** (this is the parity proof); only `no-integrations.txt`, `jira-only.txt`, `no-jira.txt` are new. If any existing `.txt` changed, a gating site is not byte-exact at ALL — fix it before proceeding.
Re-validate: `./gradlew :agent:test --tests "*SystemPromptIdeContextTest*"` → all PASS.

- [ ] **Step 11: Inspect the new gated snapshots**

`read_file` `no-integrations.txt`: confirm NO `Jira`, `Bamboo`, `SonarQube`, `Bitbucket`, `bamboo_builds`, `jira:`, `# Jira Transition`, `Sonar quality gate`, `or Jira ticket`. Confirm the role line ends `…refactoring engine. You help…`. `jira-only.txt`: confirm Jira fragments present, bamboo/sonar/bitbucket absent. `no-jira.txt`: confirm bamboo/sonar/bitbucket fragments present BUT no `jira:`, no `# Jira Transition`, no `active Jira ticket`, no `or Jira ticket`, and the role line reads `…enterprise integrations (Bamboo, SonarQube, Bitbucket).` — this is the key separator-flow check (a middle clause removed cleanly).

- [ ] **Step 12: Full `:agent` suite**

Run: `./gradlew :agent:test`
Expected: BUILD SUCCESSFUL. (Watch `SubagentRunnerTest` + any prompt-content test — the `bamboo_builds` assertions found are tool-name/tool-def assertions, expected to stay green; if one breaks, it asserted gated prose and needs a flag-aware fix — report it.)

- [ ] **Step 13: Update `agent/CLAUDE.md`**

In the "IDE-Aware System Prompt (Plan D)" section, add a row/note: integration-specific prose (role integrations clause, `jira:` link scheme, Project-integrations tool list, project_context state list, Sonar tip, Jira Transition section) is gated on `IntegrationFlags` (from `ConnectionSettings.*Url.isNotBlank()`, passed into `build()`). All-ON reproduces the pre-1b prompt (snapshot parity). Snapshots: the 7 orchestrator IDE variants + 7 sub-agent variants run with `IntegrationFlags.ALL`; `no-integrations` + `jira-only` + `no-jira` pin the gating. Sub-agents gate on their own persona registry (`registry.has`), the orchestrator on `ConnectionSettings`. ALSO correct the stale "5 subagent snapshot variants" → 7 in the "Unified Sub-agent Prompt Pipeline" table.

- [ ] **Step 14: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/SystemPromptIdeContextTest.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/SystemPromptIntegrationGatingTest.kt \
        agent/src/test/resources/prompt-snapshots/no-integrations.txt \
        agent/src/test/resources/prompt-snapshots/jira-only.txt \
        agent/src/test/resources/prompt-snapshots/no-jira.txt \
        agent/CLAUDE.md
git commit -m "feat(agent): gate system-prompt integration mentions on IntegrationFlags (1b)

The 6 stack-leaking prompt fragments (role integrations clause, jira: link scheme,
Project-integrations tool list, project_context state list, Sonar local_analysis tip,
Jira Transition section) render only when the integration is configured. All-ON is
byte-identical to today (7 IDE snapshots regenerate with no diff); no-integrations +
jira-only + no-jira snapshots pin the gating."
```

---

## Task 3: Wire the 3 callers + sub-agent snapshots

**Files:**
- Modify: `agent/.../AgentService.kt` (`systemPromptBuilder` lambda in `executeTask` ~2134-2164; `resumeSession` build ~3118-3135)
- Modify: `agent/.../tools/subagent/SubagentSystemPromptBuilder.kt` (`build()` param + pass-through)
- Modify: `agent/.../tools/subagent/SubagentRunner.kt` (resolve flags, pass to the builder ~643)
- Modify: `agent/src/test/kotlin/.../tools/subagent/SubagentSystemPromptSnapshotTest.kt` (pass `ALL` to keep 5 snapshots identical)

**Interfaces:**
- Consumes: `IntegrationFlags.from(ConnectionSettings.getInstance().state)` (Task 1); `SystemPrompt.build(..., integrations)` (Task 2).

- [ ] **Step 1: Orchestrator — `executeTask` lambda**

In the `systemPromptBuilder` lambda, alongside the existing `hasWebTools` re-evaluation, add (re-resolved each rebuild so a mid-session settings change is reflected, mirroring `hasWebTools`):

```kotlin
        val integrations = com.workflow.orchestrator.agent.prompt.IntegrationFlags.from(
            com.workflow.orchestrator.core.settings.ConnectionSettings.getInstance().state,
        )
```
and pass `integrations = integrations,` into the `SystemPrompt.build(...)` call.

- [ ] **Step 2: Orchestrator — `resumeSession`**

In the `resumeSession` `SystemPrompt.build(...)` call, add the same resolution + `integrations = IntegrationFlags.from(ConnectionSettings.getInstance().state)` argument.

- [ ] **Step 3: Sub-agent — thread the flags, gated on the sub-agent's REGISTRY (not ConnectionSettings)**

`SubagentSystemPromptBuilder.build(...)`: add `integrations: IntegrationFlags = IntegrationFlags.NONE` param; pass it through to the inner `SystemPrompt.build(..., integrations = integrations)`.

`SubagentRunner.buildUnifiedSystemPrompt()` (~line 643): a sub-agent's capabilities are its **persona tool allowlist**, not the global config — so resolve the flags from the sub-agent's OWN registry, mirroring the sibling `hasWebTools = registry.has("web_fetch") || registry.has("web_search")` already at this call site. Do NOT use `ConnectionSettings` here (a persona without the jira tool, in a jira-configured install, must not get jira prose for a tool it can't call). This is a strict refinement of the orchestrator's ConnectionSettings flags — integration tools are only in any registry when their URL is configured, so registry-derived is never broader. Add:

```kotlin
        val integrations = IntegrationFlags(
            jira = registry.has("jira"),
            bamboo = registry.has("bamboo_builds") || registry.has("bamboo_plans"),
            sonar = registry.has("sonar"),
            bitbucket = registry.has("bitbucket_pr") || registry.has("bitbucket_repo") ||
                registry.has("bitbucket_review"),
        )
```
and pass `integrations = integrations` into `SubagentSystemPromptBuilder.build(...)`. (`IntegrationFlags` import: `com.workflow.orchestrator.agent.prompt.IntegrationFlags`.)

- [ ] **Step 4: Sub-agent snapshots — pass `ALL` to preserve them (there are SEVEN, not five)**

⚠ The sub-agent snapshot set is **7** variants, not 5 (the agent/CLAUDE.md table is stale): `code-reviewer-intellij-ultimate`, `spring-boot-engineer-intellij-ultimate`, `python-engineer-pycharm-professional`, `test-automator-null-context`, `architect-reviewer-intellij-community`, `research-null-context`, `research-intellij-ultimate`. The generate test asserts `assertEquals(7, snapshots.size)` (~line 177) — leave it at 7 (we add no new sub-agent variants).

In `SubagentSystemPromptSnapshotTest.kt`, find the `buildPrompt`/`SubagentSystemPromptBuilder.build(...)` helper used by ALL 7 variants and add `integrations = IntegrationFlags.ALL` so every one stays byte-identical (their purpose is persona/IDE composition, orthogonal to integration gating). NOTE: site-1 `agentRole` is overridden for sub-agents, so only sites 2-6 (outputFormatting/capabilities/rules) are affected — all 7 current `.txt` DO contain the gated fragments (`jira:PROJ-1234`, `# Jira Transition`, etc.), so `ALL` is required to reproduce them.
Validate: `./gradlew :agent:test --tests "*SubagentSystemPromptSnapshotTest*"` (expect all 7 unchanged-pass once `ALL` is passed). Then regenerate via the `*generate all golden*` variant and confirm `git diff agent/src/test/resources/subagent-prompt-snapshots/` is **empty** (parity proof for all 7).

- [ ] **Step 5: Footgun guard — every `build()` caller must pass `integrations =`**

The `integrations` param defaults to `NONE`, so a future caller that forgets it would silently ship a stack-less prompt to a configured user (a behavior regression in the safe direction, but invisible). Add a pure source-text contract test mirroring the repo's existing source-text contract tests (e.g. `WalkthroughRegistrationContractTest`).

Create `agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/SystemPromptCallerIntegrationFlagsContractTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.prompt

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Every production call to SystemPrompt.build(...) must pass `integrations =` — else a configured
 *  user silently gets a stack-less prompt (the param defaults to NONE for the pure snapshot tests). */
class SystemPromptCallerIntegrationFlagsContractTest {
    @Test fun `every SystemPrompt_build call site in agent main passes integrations`() {
        val mainSrc = File("src/main/kotlin")
        val offenders = mainSrc.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                // crude but sufficient: find each `SystemPrompt.build(` and check the call (up to its
                // matching close on the same logical block) contains `integrations =`. The 3 known
                // callers use multi-line named args, so scan a generous window.
                Regex("SystemPrompt\\.build\\(").findAll(text).mapNotNull { m ->
                    val window = text.substring(m.range.first, minOf(text.length, m.range.first + 4000))
                    if (window.contains("integrations =")) null else "${file.name} @ ${m.range.first}"
                }
            }.toList()
        assertTrue(offenders.isEmpty(), "SystemPrompt.build() callers missing `integrations =`: $offenders")
    }
}
```

> NOTE: this test is `:agent`-module-relative (`File("src/main/kotlin")` resolves against the module dir when `:agent:test` runs). If the working dir differs, the existing source-text contract tests show the right base-path idiom — match it. The `SubagentSystemPromptBuilder` call is in `tools/subagent/` and IS a production caller — confirm it's covered (it passes `integrations` after Step 3).

Run: `./gradlew :agent:test --tests "*SystemPromptCallerIntegrationFlagsContractTest*"`
Expected: PASS (all 3 callers pass `integrations =`).

- [ ] **Step 6: Full `:agent` suite**

Run: `./gradlew :agent:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentSystemPromptBuilder.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunner.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentSystemPromptSnapshotTest.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/SystemPromptCallerIntegrationFlagsContractTest.kt
git commit -m "feat(agent): resolve IntegrationFlags at all 3 prompt callers; orchestrator=ConnectionSettings, sub-agent=registry (1b)

Orchestrator (execute + resume) gates on ConnectionSettings; sub-agent gates on its own
persona registry (registry.has) so a tool-restricted persona never gets prose for a tool
it lacks. Source-text guard pins that every build() caller passes integrations. Sub-agent
snapshots (7) regenerate with ALL (no diff)."
```

---

## Task 4: spec note + scope-map decision record

**Files:**
- Modify: `docs/superpowers/specs/2026-06-22-plugin-split-design.md` (add §20)
- Modify: `.superpowers/phase1b/scope-map.md` (append "resolved")

- [ ] **Step 1: Add spec §20** summarizing: 6 gating sites, `IntegrationFlags` from `ConnectionSettings`, passed-in (pure snapshots), all-ON==today parity proven by zero-diff snapshot regen, gated variants added, both orchestrator + sub-agent paths, persona/`supportsSpring` deferred to Phase 3.

- [ ] **Step 2: Append "Phase 1b resolved"** to the scope map.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-06-22-plugin-split-design.md
git add -f .superpowers/phase1b/scope-map.md
git commit -m "docs(plugin-split): Phase 1b resolved — system-prompt integration-gating (spec §20)"
```

---

## Final verification (before declaring 1b done)

- [ ] `./gradlew :agent:test` — green.
- [ ] `git diff` on `agent/src/test/resources/prompt-snapshots/` and `subagent-prompt-snapshots/` after a clean regenerate: the 7 orchestrator + 7 sub-agent EXISTING `.txt` show ZERO change (parity); only `no-integrations.txt`, `jira-only.txt`, `no-jira.txt` are added (orchestrator). Count asserts: orchestrator generate test → 10; sub-agent generate test → 7 (unchanged).
- [ ] `./gradlew verifyPlugin` — green (A).
- [ ] `:konsist:test` — green (no new public-API surface; `IntegrationFlags` is `:agent`-internal, not an EP).
- [ ] detekt: KNOWN pre-existing `:agent` drift (1a finding) — out of scope; if the gate is run, the NEW 1b code must add ZERO new detekt issues (verify the 1b files aren't in the report). Do NOT autocorrect the pre-existing drift here.
- [ ] Grep: `rg -n 'enterprise integrations|# Jira Transition|Project integrations|jira:PROJ' agent/src/main` returns ONLY `SystemPrompt.kt` (and only inside the gated branches).

## Self-Review (against the spec before execution)

**1. Spec coverage (§7.2 system-prompt bullet):** "System prompt leaks the stack unconditionally → gate each on configured integrations" → ✅ all 6 sites (Tasks 2+3, orchestrator + sub-agent). Persona/`supportsSpring` → correctly deferred (Phase 3).

**2. Placeholder scan:** none — every gating site has exact code; the only "find the list/call site" directives are for well-identified test methods whose structure is quoted.

**3. Type consistency:** `IntegrationFlags` (`.jira/.bamboo/.sonar/.bitbucket/.any`, `NONE/ALL/from`) used identically across `SystemPrompt`, the 4 builders, the 3 callers, and the tests. `build(..., integrations: IntegrationFlags = IntegrationFlags.NONE)`; snapshot harness passes `ALL`.

**Parity is the load-bearing risk:** the byte-exact all-ON requirement is enforced by Step 10's zero-diff check. The implementer must treat ANY existing-snapshot change as a gating bug, not regenerate-and-accept.
