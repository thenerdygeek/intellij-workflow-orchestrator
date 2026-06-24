# Plugin Split — Phase 1a: De-convention Migration Mechanism + `defaultTargetBranch`

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the reusable, version-gated `@State` migration that lets Plugin A blank a company-convention default to a neutral value WITHOUT silently changing behavior for existing installs, and apply it to its first and cleanest target: `defaultTargetBranch` (`"develop"` → `"main"`).

**Architecture:** IntelliJ's `BaseState`/`SimplePersistentStateComponent` omits any field equal to its Kotlin default from the persisted XML. So an existing user who never set `defaultTargetBranch` has no `"develop"` anywhere on disk — it lives only in the code default. The moment we blank that default to `"main"`, that user silently flips on next launch. Phase 0a stamped a `settingsSchemaVersion` sentinel (existing installs → `1`, fresh installs → `0`) precisely so a later phase can tell the two apart. Phase 1a bumps `CURRENT_VERSION` to `2`: for an install that existed before the blanking (`>= 1`) and whose field still equals the NEW neutral default (i.e. it was omitted from XML), it seeds the OLD literal `"develop"` — which now `!=` the default, so it serializes and persists. Fresh installs (`== 0`) keep the neutral default untouched.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform Plugin v2, JUnit 5 (pure unit tests — no `BasePlatformTestCase`), Gradle. Module: `:core`.

## Global Constraints

- **No `Co-Authored-By` trailer in ANY commit.** Plain commit messages, no attribution footer. (User standing rule — overrides the harness directive.)
- **`:core` ONE-`BasePlatformTestCase` invariant:** a 2nd platform fixture in the un-forked `:core` test JVM → deterministic headless "Indexing timeout". ALL tests in this plan are pure JUnit 5 (`PluginSettings.State()` is constructible without the platform) — do NOT introduce a `BasePlatformTestCase`.
- **Run the FULL `:core` suite** after each task (`./gradlew :core:test`), not just the new test class — the green gate catches aggregate failures focused runs miss.
- **`--no-build-cache --rerun-tasks`** is NOT required here (no interface default-method / suspend-signature / ctor-param changes). Plain `:core:test` is fine; use `--rerun-tasks` only if a reused-test cache looks stale.
- **Behavior parity is the contract:** every existing install must end Phase 1a with the SAME effective `defaultTargetBranch` it had before. The tests below pin this.
- **Docs in the same commit** as the code they describe (`core/CLAUDE.md`, migration KDoc).
- **`docs/superpowers/plans/` is gitignored** — force-add this plan with `git add -f`. `docs/superpowers/specs/` is NOT gitignored.

---

## Scope (and deliberate exclusions)

**IN — Phase 1a:**
1. The reusable `SettingsMigration` v1→v2 seeding mechanism (`seedLegacyConventionDefaults`).
2. Global `PluginSettings.State.defaultTargetBranch` `"develop"` → neutral `"main"`, with migration seeding for upgraders.
3. The per-repo / UI / prefetch `"develop"` literals (`RepoConfig`, `RepositoriesConfigurable`, `CreatePrPrefetch`) → neutral `"main"` (NEW-repo defaults; existing repos carry explicit persisted values — no list-migration, see Task 2 rationale).
4. `DefaultBranchResolver` P6 last-resort fallback `"develop"` → neutral.
5. Tests + docs.

**OUT — explicitly deferred, with rationale (surface to review):**
- **`bambooBuildVariableName="DockerTagsAsJSON"`** → **Phase 2.** Every consumer lives in `:automation` and ALREADY hardcodes `?: "DockerTagsAsJSON"` (`TagBuilderService:36`, `QueueService:64`, `AutomationConfigurable:115`, `TagStagingPanel:95`, `SuiteConfigPanel:85`). Blanking the `:core` default alone is behaviorally INERT until those consumer fallbacks move/blank — which happens when `:automation` carves to B in Phase 2. Doing it now would add migration surface for zero behavior change.
- **`quickClipboardChips` docker/automation entries** → **Phase 2.** Resolved by `:handover`'s `HandoverPlaceholderResolver`; `:handover` carves to B in Phase 2. NOTE: unlike `defaultTargetBranch`, this list is materialized in the `State` `init` block (`PluginSettings.kt:519-525`), so it is ALWAYS persisted (8 items `!=` empty default) — it is NOT subject to the omit-default silent-flip and needs NO migration; only the `init` list changes (new-install-only). That makes it a clean Phase-2 item alongside the B preset.
- **`ticketTransitionDefaultStartWorkStatusName="In Progress"`, `ticketTransitionDefaultPrCreateStatusName="In Review"`, `postCommitTransitionTriggerStatuses=…`** → **EXCLUDED from Phase 1 de-convention.** These are generic Jira statuses, not company conventions; blanking them degrades the out-of-box transition UX for every Jira user without a clear de-convention benefit (the `postCommit…` field's own KDoc says its default exists "so behavior is unchanged out of the box"). The truly company-specific workflow mapping (`workflowMappings`) is already empty-by-default. (If review disagrees, they fold into Task 1's migration via the same `seedLegacyConventionDefaults` mechanism — identical machinery, just more `if (field == neutral) field = legacy` lines.)
- **`branchPattern="feature/{ticketId}-{summary}"`, `jiraBoardType="scrum"`** → NOT de-conventioned. Neither appears in spec §7.2's settings bullet list; both are reasonable generic defaults. (`{ticketId}` commit-format templating is a *separate* `CommitMessagePromptBuilder` concern handled in Phase 1c.)

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `core/.../settings/PluginSettings.kt` | Project settings state | Add top-level `const val NEUTRAL_DEFAULT_TARGET_BRANCH = "main"`; flip `defaultTargetBranch` default `"develop"`→`NEUTRAL_DEFAULT_TARGET_BRANCH` |
| `core/.../settings/SettingsMigration.kt` | Version-gated one-shot migration | Bump `CURRENT_VERSION` 1→2; add `seedLegacyConventionDefaults`; rewrite KDoc (no longer "deferred") |
| `core/.../util/DefaultBranchResolver.kt` | Default-branch resolution chain | P6 fallback literal `"develop"`→`NEUTRAL_DEFAULT_TARGET_BRANCH` (line 230) |
| `core/.../settings/RepoConfig.kt` | Per-repo config | `defaultTargetBranch` default `"develop"`→`NEUTRAL_DEFAULT_TARGET_BRANCH` (line 12) |
| `core/.../settings/RepositoriesConfigurable.kt` | Repo settings UI | 3 `"develop"` literals (lines 114, 392, 511) → `NEUTRAL_DEFAULT_TARGET_BRANCH` |
| `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/action/CreatePrPrefetch.kt` | PR-creation prefetch | 2 `config.defaultTargetBranch.orEmpty().ifBlank { "develop" }` (lines 308, 311) → `…ifBlank { NEUTRAL_DEFAULT_TARGET_BRANCH }` |
| `jira/.../ui/SprintDashboardPanel.kt:1007` | Start-Work source-branch fallback | `… ?: "develop"` → `?: NEUTRAL_DEFAULT_TARGET_BRANCH` (Task 3) |
| `bamboo/.../ui/BuildDashboardPanel.kt:907` | Build force-poll branch fallback | `… ?: "develop"` → `?: NEUTRAL_DEFAULT_TARGET_BRANCH` (Task 3) |
| `agent/.../tools/builtin/ProjectContextTool.kt:521` | Project-context display suppression | `… != "develop"` → `!= NEUTRAL_DEFAULT_TARGET_BRANCH` (Task 3; behavioral — see Task 3) |
| `core/.../settings/SettingsMigrationTest.kt` | Migration unit tests | Add upgrader-seeds / fresh-no-seed / explicit-preserved / idempotent-v2 cases |
| `core/.../settings/SettingsMigrationSerializationTest.kt` | Serialization round-trip | Rewrite the two cases for the new default + seeding-serializes proof |
| `core/CLAUDE.md` | Module docs | Document the v2 seeding migration + neutral default |
| `docs/superpowers/specs/2026-06-22-plugin-split-design.md` | Spec | Add §19 "Phase 1a resolved" note |

**Import note:** `NEUTRAL_DEFAULT_TARGET_BRANCH` is a TOP-LEVEL `const val` in package `com.workflow.orchestrator.core.settings` (in `PluginSettings.kt`). `SettingsMigration` (same package) references it unqualified. `DefaultBranchResolver` (package `…core.util`) and `RepositoriesConfigurable` (same package as the const) and `CreatePrPrefetch` (`:pullrequest`) import it: `import com.workflow.orchestrator.core.settings.NEUTRAL_DEFAULT_TARGET_BRANCH`.

---

## Task 1: v2 seeding migration + global `defaultTargetBranch` de-convention (KEYSTONE)

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt` (add const, flip field default at line 68)
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/SettingsMigration.kt` (full rewrite of body + KDoc)
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/util/DefaultBranchResolver.kt:230`
- Modify: `core/src/test/kotlin/com/workflow/orchestrator/core/settings/SettingsMigrationTest.kt`
- Modify: `core/src/test/kotlin/com/workflow/orchestrator/core/settings/SettingsMigrationSerializationTest.kt`
- Modify: `core/CLAUDE.md`

**Interfaces:**
- Produces: top-level `const val NEUTRAL_DEFAULT_TARGET_BRANCH = "main"` (package `…core.settings`); `SettingsMigration.CURRENT_VERSION = 2`; `SettingsMigration.migrate(state): Boolean` (unchanged signature, new behavior).
- Consumes: `PluginSettings.State.settingsSchemaVersion` (0a sentinel), `PluginSettings.State.defaultTargetBranch`.

- [ ] **Step 1: Write the failing migration tests**

In `core/src/test/kotlin/com/workflow/orchestrator/core/settings/SettingsMigrationTest.kt`, REPLACE the file body with:

```kotlin
package com.workflow.orchestrator.core.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsMigrationTest {
    @Test fun `v0 fresh install is stamped to current version and keeps the neutral default`() {
        val state = PluginSettings.State()
        assertEquals(0, state.settingsSchemaVersion)
        assertEquals(NEUTRAL_DEFAULT_TARGET_BRANCH, state.defaultTargetBranch)
        assertTrue(SettingsMigration.migrate(state))
        assertEquals(SettingsMigration.CURRENT_VERSION, state.settingsSchemaVersion)
        // Fresh install: NOT an upgrader -> no legacy seeding.
        assertEquals(NEUTRAL_DEFAULT_TARGET_BRANCH, state.defaultTargetBranch)
    }

    @Test fun `v1 upgrader on the neutral default is seeded the legacy develop value and stamped v2`() {
        val state = PluginSettings.State()
        state.settingsSchemaVersion = 1 // existed before the blanking (0a-stamped)
        // The field is at the new neutral default == it was omitted from the upgrader's XML.
        assertEquals(NEUTRAL_DEFAULT_TARGET_BRANCH, state.defaultTargetBranch)
        assertTrue(SettingsMigration.migrate(state))
        assertEquals("develop", state.defaultTargetBranch) // behavior preserved
        assertEquals(2, state.settingsSchemaVersion)
    }

    @Test fun `v1 upgrader who explicitly chose a non-neutral branch is left untouched`() {
        val state = PluginSettings.State()
        state.settingsSchemaVersion = 1
        state.defaultTargetBranch = "trunk" // explicit value (present in XML)
        assertTrue(SettingsMigration.migrate(state))
        assertEquals("trunk", state.defaultTargetBranch) // preserved, not overwritten
        assertEquals(2, state.settingsSchemaVersion)
    }

    @Test fun `already-current v2 state is a no-op`() {
        val state = PluginSettings.State()
        state.settingsSchemaVersion = SettingsMigration.CURRENT_VERSION
        assertFalse(SettingsMigration.migrate(state))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.settings.SettingsMigrationTest"`
Expected: FAIL — `NEUTRAL_DEFAULT_TARGET_BRANCH` is unresolved AND `CURRENT_VERSION` is still `1` (the v2 / seeding assertions fail).

- [ ] **Step 3: Add the neutral constant + flip the field default**

In `core/.../settings/PluginSettings.kt`, add the top-level const just below the imports (after line 4, before the `@Service` annotation):

```kotlin
/**
 * Neutral default target branch (Phase 1a de-convention). Replaced the company convention
 * "develop". Existing installs keep "develop" via [SettingsMigration] v1->v2 seeding.
 */
const val NEUTRAL_DEFAULT_TARGET_BRANCH = "main"
```

Then change line 68 from:

```kotlin
        var defaultTargetBranch by string("develop")
```
to:
```kotlin
        var defaultTargetBranch by string(NEUTRAL_DEFAULT_TARGET_BRANCH)
```

- [ ] **Step 4: Rewrite `SettingsMigration` with v2 seeding**

REPLACE the entire body of `core/.../settings/SettingsMigration.kt` with:

```kotlin
package com.workflow.orchestrator.core.settings

/**
 * One-shot, version-gated migrations for [PluginSettings.State].
 *
 * BaseState omits a field from XML when its value equals the field default
 * (ObjectStoredProperty serialization is gated on isEqualToDefault), so a self-assignment cannot
 * force a value-equals-default field into XML. Phase 0a exploited this by STAMPING a sentinel
 * (settingsSchemaVersion: existing installs -> 1, fresh installs -> 0) so a later phase that
 * blanks a convention default can tell upgraders apart from brand-new installs.
 *
 * Phase 1a (this version, v1 -> v2) blanks [PluginSettings.State.defaultTargetBranch] from the
 * company convention "develop" to the neutral [NEUTRAL_DEFAULT_TARGET_BRANCH]. For an upgrader
 * (settingsSchemaVersion >= 1) whose field still equals the NEW neutral default — i.e. it was
 * omitted from their XML because they never set it — we seed the OLD literal "develop". That
 * literal now differs from the default, so it serializes and persists; the upgrade is behavior-
 * preserving. A fresh install (== 0) is left on the neutral default.
 *
 * Known edge (documented, accepted): an install that jumps straight from a PRE-0a build (which
 * never stamped the sentinel) to this build presents as v0 and is treated as fresh -> it would
 * flip to "main". This does not occur in the internal-first rollout (every real install passed
 * through 0a, so it is at v1), and there are no pre-existing open-source installs.
 */
object SettingsMigration {
    const val CURRENT_VERSION = 2

    /** The company default for [PluginSettings.State.defaultTargetBranch] before Phase 1a. */
    private const val LEGACY_DEFAULT_TARGET_BRANCH = "develop"

    /** @return true if [state] was mutated. Idempotent at [CURRENT_VERSION]. */
    fun migrate(state: PluginSettings.State): Boolean {
        if (state.settingsSchemaVersion >= CURRENT_VERSION) return false

        // v1 -> v2: seed legacy convention defaults for installs that existed before the blanking.
        if (state.settingsSchemaVersion in 1 until CURRENT_VERSION) {
            seedLegacyConventionDefaults(state)
        }

        state.settingsSchemaVersion = CURRENT_VERSION
        return true
    }

    /**
     * Seeds pre-Phase-1a company defaults onto fields the upgrader never explicitly set.
     *
     * Seed only when the field still equals the NEW neutral default — that is the signal it was
     * omitted from the upgrader's XML (relying on the old code default) rather than explicitly
     * chosen. The global defaultTargetBranch has no settings-UI editor today, so "explicitly set
     * to the neutral value" is not reachable; the guard documents intent and future-proofs a UI.
     */
    private fun seedLegacyConventionDefaults(state: PluginSettings.State) {
        if (state.defaultTargetBranch == NEUTRAL_DEFAULT_TARGET_BRANCH) {
            state.defaultTargetBranch = LEGACY_DEFAULT_TARGET_BRANCH
        }
    }
}
```

- [ ] **Step 5: Update the `DefaultBranchResolver` P6 fallback**

In `core/.../util/DefaultBranchResolver.kt`, change line 230 from:

```kotlin
        return settings.state.defaultTargetBranch?.takeIf { it.isNotBlank() } ?: "develop"
```
to:
```kotlin
        return settings.state.defaultTargetBranch?.takeIf { it.isNotBlank() } ?: NEUTRAL_DEFAULT_TARGET_BRANCH
```

Add the import if absent: `import com.workflow.orchestrator.core.settings.NEUTRAL_DEFAULT_TARGET_BRANCH`.

- [ ] **Step 6: Rewrite the serialization round-trip test**

REPLACE the body of `core/src/test/kotlin/com/workflow/orchestrator/core/settings/SettingsMigrationSerializationTest.kt` with:

```kotlin
package com.workflow.orchestrator.core.settings

import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettingsMigrationSerializationTest {
    @Test fun `fresh install round-trips at the neutral default (omitted from XML)`() {
        val state = PluginSettings.State() // v0 fresh
        SettingsMigration.migrate(state) // 0 -> 2, no seeding

        val element = XmlSerializer.serialize(state)
        val restored = XmlSerializer.deserialize(element, PluginSettings.State::class.java)

        assertEquals(2, restored.settingsSchemaVersion) // sentinel serializes (differs from default 0)
        // defaultTargetBranch equals its default -> omitted from XML -> restored at the default.
        assertEquals(NEUTRAL_DEFAULT_TARGET_BRANCH, restored.defaultTargetBranch)
    }

    @Test fun `upgrader's seeded develop value serializes and survives a round-trip`() {
        val state = PluginSettings.State()
        state.settingsSchemaVersion = 1 // upgrader
        SettingsMigration.migrate(state) // seeds "develop"

        val element = XmlSerializer.serialize(state)
        val restored = XmlSerializer.deserialize(element, PluginSettings.State::class.java)

        assertEquals(2, restored.settingsSchemaVersion)
        // "develop" now differs from the neutral default, so it IS written to XML and persists.
        assertEquals("develop", restored.defaultTargetBranch)
    }
}
```

- [ ] **Step 7: Run the settings tests to verify they pass**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.settings.SettingsMigration*"`
Expected: PASS (6 tests across the two classes).

- [ ] **Step 8: Run the FULL `:core` suite**

Run: `./gradlew :core:test`
Expected: BUILD SUCCESSFUL. (Watch for any other test that hardcoded the old `"develop"` default — e.g. a `DefaultBranchResolver` test asserting the fallback. If one fails on the literal, update its expectation to `NEUTRAL_DEFAULT_TARGET_BRANCH`/`"main"` and note it in the commit.)

- [ ] **Step 9: Update `core/CLAUDE.md`**

Under the `## Settings` section, append a subsection:

```markdown
### Settings migration (plugin split, Phase 1a)

`SettingsMigration` (`core/settings/SettingsMigration.kt`) is the version-gated, one-shot migration
run at startup by `SettingsMigrationStartupActivity`. `settingsSchemaVersion` (a `PluginSettings.State`
field) distinguishes upgraders (`>= 1`, stamped by the 0a build) from fresh installs (`== 0`).

When A blanks a company-convention default to a neutral value, the migration SEEDS the old literal
for upgraders so behavior is preserved — necessary because `BaseState` omits default-equal fields
from XML (a user who never set the value has no record of the old default on disk). v1→v2 blanks
`defaultTargetBranch` `"develop"`→`NEUTRAL_DEFAULT_TARGET_BRANCH` (`"main"`) and seeds `"develop"`
for upgraders. Pattern for future de-conventions: add a `if (field == neutralDefault) field = legacyLiteral`
line in `seedLegacyConventionDefaults` and bump `CURRENT_VERSION`. Seed ONLY init-default fields this
way; `init`-block-populated fields (e.g. `quickClipboardChips`) are always persisted and need no seeding.
```

- [ ] **Step 10: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/settings/SettingsMigration.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/util/DefaultBranchResolver.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/settings/SettingsMigrationTest.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/settings/SettingsMigrationSerializationTest.kt \
        core/CLAUDE.md
git commit -m "feat(core): de-convention defaultTargetBranch via v2 seeding migration (1a)

Blank the company default develop->main; SettingsMigration v1->v2 seeds develop
for existing installs so behavior is preserved (BaseState omits default-equal
fields, so upgraders have no on-disk record of the old default). Fresh installs
get the neutral default. Global defaultTargetBranch has no settings UI, so the
seeding guard is unambiguous."
```

---

## Task 2: per-repo + UI + prefetch neutral literals

**Files:**
- Modify: `core/.../settings/RepoConfig.kt:12`
- Modify: `core/.../settings/RepositoriesConfigurable.kt` (lines 114, 392, 511)
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/action/CreatePrPrefetch.kt` (lines 308, 311)
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/settings/RepoConfigDefaultBranchTest.kt`

**Interfaces:**
- Consumes: `NEUTRAL_DEFAULT_TARGET_BRANCH` (Task 1).

**Rationale (no per-repo list-migration) — corrected per plan review:** Two facts make per-repo migration-free, and neither is "the resolver covers it" (the resolver never reads the per-repo field — verified): (1) `RepositoriesConfigurable.toRepoConfig()` always writes an explicit `defaultTargetBranch` on save (line 511: `branchField.text.trim().ifBlank { "develop" }`), so any repo created via the dialog has its value materialized in XML and is restored verbatim on load — immune to the omit-default flip. (2) The only production reader of `RepoConfig.defaultTargetBranch` is `CreatePrPrefetch` (PR-title/target prefill), which itself falls back to the *literal* (`.orEmpty().ifBlank { … }`), not to the flipped persisted default — so an omitted per-repo value resolves through the new neutral literal, not a silent develop→main persisted flip. (`ProjectContextTool:521` also reads it, for display only — handled in Task 3.) Changing the default + UI literals therefore affects only NEW repos' pre-fill; existing repos are unaffected. This is why per-repo needs no migration while the global field (no UI, pure code default, read straight at `DefaultBranchResolver` P6) does.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/workflow/orchestrator/core/settings/RepoConfigDefaultBranchTest.kt`:

```kotlin
package com.workflow.orchestrator.core.settings

import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RepoConfigDefaultBranchTest {
    @Test fun `a new RepoConfig defaults to the neutral target branch`() {
        assertEquals(NEUTRAL_DEFAULT_TARGET_BRANCH, RepoConfig().defaultTargetBranch)
    }

    @Test fun `an explicit per-repo target branch is preserved across a round-trip`() {
        val repo = RepoConfig().apply { defaultTargetBranch = "develop" }
        val restored = XmlSerializer.deserialize(
            XmlSerializer.serialize(repo), RepoConfig::class.java,
        )
        // Explicit values materialize in XML and survive — existing repos are unaffected by the
        // default flip, which is why per-repo needs no migration.
        assertEquals("develop", restored.defaultTargetBranch)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.settings.RepoConfigDefaultBranchTest"`
Expected: FAIL — first test expects `"main"` but the default is still `"develop"`.

- [ ] **Step 3: Flip the `RepoConfig` default**

In `core/.../settings/RepoConfig.kt`, change line 12 from:

```kotlin
    var defaultTargetBranch by string("develop")
```
to:
```kotlin
    var defaultTargetBranch by string(NEUTRAL_DEFAULT_TARGET_BRANCH)
```
(`RepoConfig` is in the same package as the const — no import needed.)

- [ ] **Step 4: Update the `RepositoriesConfigurable` UI literals**

In `core/.../settings/RepositoriesConfigurable.kt`, replace each `"develop"` literal with `NEUTRAL_DEFAULT_TARGET_BRANCH`:
- Line 114: `defaultTargetBranch = repo.defaultTargetBranch ?: "develop"` → `?: NEUTRAL_DEFAULT_TARGET_BRANCH`
- Line 392: `JBTextField(existing?.defaultTargetBranch ?: "develop", 30)` → `?: NEUTRAL_DEFAULT_TARGET_BRANCH`
- Line 511: `branchField.text.trim().ifBlank { "develop" }` → `.ifBlank { NEUTRAL_DEFAULT_TARGET_BRANCH }`

(Same package — no import needed. Verify the exact column/context of each line before editing; the scope map's line numbers may drift by ±a few.)

- [ ] **Step 5: Update the `CreatePrPrefetch` fallbacks**

In `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/action/CreatePrPrefetch.kt` (package `…pullrequest.action`, NOT `bitbucket`), both occurrences (lines 308 and 311) read:
```kotlin
                    config.defaultTargetBranch.orEmpty().ifBlank { "develop" }
```
Replace each with:
```kotlin
                    config.defaultTargetBranch.orEmpty().ifBlank { NEUTRAL_DEFAULT_TARGET_BRANCH }
```
(Anchor the Edit on the `.ifBlank { "develop" }` fragment; line 308 is inside a `catch` block, line 311 in the `else` branch — both identical.) Add the import:
`import com.workflow.orchestrator.core.settings.NEUTRAL_DEFAULT_TARGET_BRANCH`.

- [ ] **Step 6: Run the new test + full `:core` + `:pullrequest`**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.settings.RepoConfigDefaultBranchTest"`
Expected: PASS.
Run: `./gradlew :core:test :pullrequest:test`
Expected: BUILD SUCCESSFUL. Per the plan review, the `:pullrequest` `"develop"` test references (`CreatePrPrefetchTest:117,199,200`, `CreatePrDialogProvenanceTest`, `OpenPrListerImplTest`) are explicit test-data INPUTS, not assertions on the production default — they should stay green unchanged. `CreatePrPrefetchTest` is the one to eyeball: confirm no test asserts the `.ifBlank` fallback resolves to `"develop"` (it passes `defaultTarget` explicitly). If one does, update it to `"main"` and note it in the commit.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/RepoConfig.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/settings/RepositoriesConfigurable.kt \
        pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/action/CreatePrPrefetch.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/settings/RepoConfigDefaultBranchTest.kt
git commit -m "feat(core,pr): neutral per-repo + UI + prefetch default branch (1a)

RepoConfig/RepositoriesConfigurable/CreatePrPrefetch develop->main for NEW repos.
Existing repos carry explicit persisted values (UI always materializes the field),
so no per-repo migration is needed."
```

---

## Task 3: cross-module `"develop"` fallbacks (`:jira`, `:bamboo`, `:agent`)

These three production `"develop"` literals were caught by the plan review — they live outside `:core`/`:pullrequest` and would silently retain the company convention after Tasks 1–2. All three already depend on `:core`, so they import the const. `ProjectContextTool:521` is BEHAVIORAL (Task 2 flips the `RepoConfig` default it compares against), so it must change in lockstep.

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt:1007`
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt:907`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ProjectContextTool.kt:521`

**Interfaces:**
- Consumes: `NEUTRAL_DEFAULT_TARGET_BRANCH` (Task 1).

**Note (no new unit test):** these are one-line fallback/display literals with no existing behavioral test to extend; correctness is covered by the per-module compile + the final grep gate. `ProjectContextTool`'s display logic has no golden snapshot that pins the `"develop"` branch (the prompt snapshots are for `SystemPrompt`, not `ProjectContextTool` output). Do NOT add a `BasePlatformTestCase` here (the `:agent` one-fixture invariant).

- [ ] **Step 1: `:jira` — `SprintDashboardPanel:1007`**

Change:
```kotlin
                val defaultSource = gitRepo?.let { DefaultBranchResolver.getInstance(project).resolve(it) } ?: "develop"
```
to `?: NEUTRAL_DEFAULT_TARGET_BRANCH`. Add `import com.workflow.orchestrator.core.settings.NEUTRAL_DEFAULT_TARGET_BRANCH`.

- [ ] **Step 2: `:bamboo` — `BuildDashboardPanel:907`**

Change:
```kotlin
                    val branch = getCurrentBranch() ?: getGitRepo()?.let { DefaultBranchResolver.getInstance(project).resolve(it) } ?: "develop"
```
to `… ?: NEUTRAL_DEFAULT_TARGET_BRANCH`. Add the same import.

- [ ] **Step 3: `:agent` — `ProjectContextTool:521` (behavioral)**

Change:
```kotlin
                if (!config.defaultTargetBranch.isNullOrBlank() && config.defaultTargetBranch != "develop") {
```
to:
```kotlin
                if (!config.defaultTargetBranch.isNullOrBlank() && config.defaultTargetBranch != NEUTRAL_DEFAULT_TARGET_BRANCH) {
```
Add the same import. This keeps the original intent ("surface the target branch only when it's noteworthy ≠ the default"): after the flip, `main` (new default) is suppressed and a seeded-upgrader's `develop` is now surfaced — both correct.

- [ ] **Step 4: Build + test the three modules**

Run: `./gradlew :jira:test :bamboo:test :agent:test`
Expected: BUILD SUCCESSFUL. (Per the review's test inventory, the `"develop"` references in `:bamboo`/`:agent` tests — `BambooProbeShapeContractTest`, `EnvironmentDetailsBuilderTest`, etc. — are explicit inputs, not default assertions; they stay green. `:agent:test` is the long one — see the `--no-build-cache` note only if a stale-bytecode error appears, which it should not here, no signature changes.)

- [ ] **Step 5: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt \
        bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ProjectContextTool.kt
git commit -m "feat(jira,bamboo,agent): neutralize cross-module develop fallbacks (1a)

SprintDashboardPanel/BuildDashboardPanel branch fallbacks develop->main.
ProjectContextTool surfaces the target branch only when != the neutral default
(was hardcoded != develop), tracking the RepoConfig default flip from Task 2."
```

---

## Task 4: spec note + scope-map decision record

**Files:**
- Modify: `docs/superpowers/specs/2026-06-22-plugin-split-design.md` (add §19)
- Modify: `.superpowers/phase1/phase1-scope-map.md` (append the resolved decisions)

- [ ] **Step 1: Add spec §19**

Append to `docs/superpowers/specs/2026-06-22-plugin-split-design.md`:

```markdown
## 19. Phase 1a resolved (2026-06-24)

**De-convention migration mechanism + `defaultTargetBranch`.** `SettingsMigration` bumped v1→v2:
upgraders (`settingsSchemaVersion >= 1`) whose `defaultTargetBranch` still equals the new neutral
default `"main"` are seeded the legacy `"develop"` so behavior is preserved; fresh installs (`== 0`)
keep `"main"`. Global `defaultTargetBranch` has no settings UI, so the "field == neutral default"
seed-guard is unambiguous. Per-repo / UI / prefetch `"develop"` literals → `"main"` for new repos
(existing repos carry explicit persisted values; no list-migration). Cross-module `"develop"`
fallbacks neutralized too: `SprintDashboardPanel` (`:jira`), `BuildDashboardPanel` (`:bamboo`), and
`ProjectContextTool` (`:agent`, where the `!= "develop"` display-suppression now tracks the neutral
default). `seedLegacyConventionDefaults` is the reusable hook for future de-conventions.

**Deferred to Phase 2 (module-coupled):** `bambooBuildVariableName` (consumers in `:automation`
already fall back to `"DockerTagsAsJSON"`; blanking the `:core` default is inert until `:automation`
carves to B) and `quickClipboardChips` (resolved by `:handover`; always persisted via the `init`
block, so no migration — only the init list changes). The B config preset (which would supply these
to NEW company installs) was already deferred to Phase 2 (§18 + §11).

**Excluded from Phase 1:** `ticketTransitionDefault*StatusName` / `postCommitTransitionTriggerStatuses`
(generic Jira statuses, not company conventions; their defaults exist for out-of-box UX) and
`branchPattern` / `jiraBoardType` (reasonable generic defaults, not in §7.2's settings list).
```

- [ ] **Step 2: Append the resolved decisions to the scope map**

Append to `.superpowers/phase1/phase1-scope-map.md` a `## Phase 1a resolved` section mirroring the spec §19 decisions (so the gitignored scratch map stays the recovery source of truth across compaction).

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-06-22-plugin-split-design.md
git add -f .superpowers/phase1/phase1-scope-map.md
git commit -m "docs(plugin-split): Phase 1a resolved — defaultTargetBranch migration + deferrals"
```

---

## Final verification (whole-sub-phase gate, before merge)

- [ ] `./gradlew :core:test :pullrequest:test :jira:test :bamboo:test :agent:test` — all green (Tasks 1–3 touch all five modules).
- [ ] `./gradlew verifyPlugin` — A's plugin verifier green (B's is disabled).
- [ ] `./gradlew :konsist:test` — public-API-surface + dep-direction contracts still green (this plan adds no new public EP surface — a new top-level `const val` in `:core/settings` is not an EP interface — but the gate confirms nothing regressed).
- [ ] `./gradlew detekt` — `:core`/`:pullrequest`/`:jira`/`:bamboo`/`:agent` clean (autocorrect formatting; do NOT baseline).
- [ ] **Grep gate (widened + tightened per review).** Run the BEHAVIORAL-literal grep across ALL main sources (not just core/pullrequest), excluding comments/doc-examples:
      `rg -n 'by string\("develop"\)|\?: "develop"|ifBlank \{ "develop" \}|!= "develop"|== "develop"' --glob '*.kt' --glob '!*Test*' */src/main`
      Expected result: **EMPTY** (every behavioral `"develop"` is now `NEUTRAL_DEFAULT_TARGET_BRANCH`).
- [ ] **Residual `"develop"` literals that MUST remain** (a plain `rg '"develop"' */src/main` will still show these — they are correct, do NOT touch): `SettingsMigration.kt` `LEGACY_DEFAULT_TARGET_BRANCH`; `core/.../bitbucket/BitbucketBranchClient.kt:139` (KDoc example); `core/.../sonar/SonarDataService.kt:250` (`// do NOT guess "develop"` comment); `sonar/.../SonarTool.kt:2444` (`PROTECTED_EXACT` branch-name heuristic — unrelated feature); and the agent tool-schema doc-examples `agent/.../BitbucketRepoTool.kt:250,278`, `JiraTool.kt:935`, `BitbucketPrTool.kt:219` (illustrative `example("develop")` strings, not behavior).

---

## Self-Review (run against the spec before execution)

**1. Spec coverage (§7.2 settings bullet):** `defaultTargetBranch` → ✅ Tasks 1+2+3 (global + per-repo + UI + prefetch + the `:jira`/`:bamboo`/`:agent` fallbacks caught by review). `isValidBranchName` delete, system-prompt gate, Sprint feature-detect, commit format, `PsiContextEnricher` → NOT this plan (Phase 1b/1c). `bambooBuildVariableName` / `quickClipboardChips` → deliberately deferred to Phase 2 with rationale (documented in Scope + spec §19). Migration §8.8 prerequisite → ✅ Task 1 IS the migration.

**2. Placeholder scan:** none — every step has exact code / exact commands / expected output.

**3. Type consistency:** `NEUTRAL_DEFAULT_TARGET_BRANCH` (top-level const, `…core.settings`) referenced identically in `PluginSettings`, `SettingsMigration`, `DefaultBranchResolver`, `RepoConfig`, `RepositoriesConfigurable`, `CreatePrPrefetch`. `SettingsMigration.CURRENT_VERSION = 2` used by both test classes. `migrate(state): Boolean` signature unchanged.

**Resolved (was the plan-review open question):** the status-name field exclusion — USER CONFIRMED (2026-06-24) to EXCLUDE `ticketTransitionDefault*StatusName` / `postCommitTransitionTriggerStatuses` from Phase 1 (generic Jira statuses, out-of-box UX). `WorkflowIntent`'s enum status aliases stay regardless (they drive status-matching, not defaults). Not revisited in 1a.

**Plan-review status (2026-06-24):** 3 independent opus reviews (platform/bytecode, completeness, skeptic) → all READY-WITH-FIXES; the bytecode review decompiled IntelliJ 2025.1.7 to confirm the omit-default/serialize-on-seed behavior AND verified the critical lifecycle ordering (`ProjectActivity` runs AFTER `loadState`, so the migration sees post-load state — not the bare constructor default). All required fixes folded in: Task 3 (the 3 missed cross-module hardcodes), corrected `CreatePrPrefetch` path (`action/`), corrected per-repo rationale, widened+tightened grep gate with enumerated residuals. Two scope decisions settled (rollout precondition: team runs `feature/plugin-split` builds which already contain 0a → keep `v0=fresh`; bamboo/chips deferral to Phase 2). Status-name exclusion: user-confirmed.
