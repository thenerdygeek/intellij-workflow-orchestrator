# Plugin Split — Phase 0a (Skeleton + Mechanism) Implementation Plan — **rev 2**

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up a second, private plugin **B** that `<depends>` on the existing plugin **A**, prove B can override an A extension point and contribute an agent tool at runtime, and lay the safety rails (a `@State` settings-migration *framework* + a self-declared write-tool safety property + a konsist public-API contract) that later phases depend on — without changing any user-visible behavior.

**Architecture:** A stays the current composed plugin. B is a NEW Gradle subproject (`:plugin-b`) applying the full IntelliJ Platform Gradle plugin, with its own `plugin.xml` (hard `<depends>`), consuming A via `localPlugin(...)`. New EPs are added in A; B implements them. A's EP interfaces B touches are `public`; **new** ones are marked `@InternalApi` (public-but-unfrozen); **pre-existing** ones already marked `@StableApi(since=…)` are left as-is. Never `internal` (Kotlin `internal` is module-scoped; B can't compile against it).

**Tech Stack:** Kotlin 2.1.10, Gradle + IntelliJ Platform Gradle plugin **2.12.0**, target IntelliJ IDEA Ultimate **2025.1.7**, JUnit 5 + JUnit-Vintage (for `BasePlatformTestCase`), Konsist 0.17.3.

> **rev-2 changelog (plan review, 2 bytecode-verified reviewers):** **BLOCKER fixes** — Task 2 `@State` self-assignment is a *proven serialization no-op* → redesigned to *stamp the sentinel only*, default-preservation deferred to Phase 1 (keyed off the sentinel), with a real XML round-trip test; Task 5 `NoopTool` *didn't compile* → corrected DTO shapes/packages. **HIGH fixes** — Task 1 `localPlugin(rootProject)` is a type error → `localPlugin(project(rootProject.path))` + `:plugin-a` fallback; Task 6 `FormatCodeTool`/`OptimizeImportsTool`/`RefactorRenameTool` are in `tools/ide/` not `tools/builtin/`; added the two-plugin **runIde** smoke for the B-contributed tool (spec §12 gate 3). **Other** — `ToolRegistrationService` explicitly deferred to Phase-0b; all-modules green gate; honest TDD framing for Task 3; `@StableApi` vs `@InternalApi` note; A→B konsist direction test; Konsist count-guard. **Kept** (review pushback) — Task 4 `order` default `0` on the interface + `Int.MAX_VALUE` on the shipped impl is correct (mirrors `WorkflowConfig`; a B override defaulting to `0` beats the `MAX` fallback — the reviewer's "interface = MAX" would *create* a tie).

## Global Constraints
- IntelliJ Platform Gradle plugin **2.12.0** (`libs.versions.toml:8`); aliases `libs.plugins.intelliJPlatform` (full) / `intelliJPlatformModule` (module). `localPlugin` overloads in 2.12.0: `(File)`, `(String)`, `(Directory)`, `(Provider<?>)`, `(ProjectDependency)` — **no `(Project)` overload**.
- `platformVersion = 2025.1.7`; plugin A `<id>` = `com.workflow.orchestrator.plugin`; `pluginSinceBuild = 251`, `pluginUntilBuild = 253.*`; `javaVersion = 21`.
- **Never make a B-implemented EP interface `internal`.** New EP interfaces → `public` + `@InternalApi`; do not downgrade pre-existing `@StableApi(since)` interfaces. (`core/.../core/api/ApiStability.kt` defines `@StableApi(val since: String)` and `@InternalApi`; there is no `CANDIDATE` level.)
- **`--no-build-cache --rerun-tasks`** on any commit changing a lambda/function type to/from `suspend`.
- No `runBlocking` in `main/` (pre-commit hook; use `runBlockingCancellable`). Threading: IO for API, EDT for UI, `WriteCommandAction` for files.
- `BasePlatformTestCase` extends JUnit-3 `TestCase`: methods **must start with `test`** (backtick names like `` `test foo` `` work; no `@Test`). **One test method per class** (a 2nd hangs on a headless "Indexing timeout").
- Module test command: `./gradlew :<module>:test`. Full check: `./gradlew verifyPlugin`.

---

### Task 1: Stand up the `:plugin-b` skeleton (depends on A)

**Files:**
- Create: `plugin-b/build.gradle.kts`, `plugin-b/src/main/resources/META-INF/plugin.xml`, `plugin-b/src/main/kotlin/com/workflow/orchestrator/companyb/.gitkeep`
- Modify: `settings.gradle.kts` (add `":plugin-b"` to `include(...)`, lines 9–22)

**Interfaces — Produces:** a buildable plugin `com.workflow.orchestrator.companyb.plugin` that hard-`<depends>` on `com.workflow.orchestrator.plugin`.

- [ ] **Step 1: Add `:plugin-b` to `settings.gradle.kts`** — append `":plugin-b",` as the last entry of the `include(...)` block.

- [ ] **Step 2: Create `plugin-b/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.intelliJPlatform)
}

kotlin { jvmToolchain(providers.gradleProperty("javaVersion").get().toInt()) }

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate(providers.gradleProperty("platformVersion"))
        // B depends on plugin A. 2.12.0 has NO localPlugin(Project) overload — pass a
        // ProjectDependency via project(...). The root project IS plugin A.
        // VERIFY: localPlugin(ProjectDependency) requires the target to expose the platform
        // plugin's consumable plugin artifact. If resolution fails against the root,
        // FALLBACK: extract A's plugin composition into a `:plugin-a` subproject and use
        // localPlugin(project(":plugin-a")). Pick one and record which in the commit message.
        localPlugin(project(rootProject.path))
        testFramework(TestFrameworkType.Platform)
    }
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.serialization.json)
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.vintage.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(kotlin("test"))
}

intellijPlatform {
    pluginConfiguration {
        id = "com.workflow.orchestrator.companyb.plugin"
        name = "Workflow Orchestrator — Company B"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
}
```

- [ ] **Step 3: Create `plugin-b/src/main/resources/META-INF/plugin.xml`**

```xml
<idea-plugin>
    <id>com.workflow.orchestrator.companyb.plugin</id>
    <name>Workflow Orchestrator — Company B</name>
    <vendor>Workflow Team</vendor>
    <description><![CDATA[Company-specific configuration preset and modules for Workflow Orchestrator.]]></description>

    <depends>com.intellij.modules.platform</depends>
    <!-- HARD depends (NOT optional): B's classloader becomes a child of A's. B is useless without A. -->
    <depends>com.workflow.orchestrator.plugin</depends>

    <!-- extensions added in Task 3 (workflowConfig override) and Task 5 (agentToolContributor) -->
</idea-plugin>
```

- [ ] **Step 4: Create the empty source root**

```bash
mkdir -p plugin-b/src/main/kotlin/com/workflow/orchestrator/companyb
touch plugin-b/src/main/kotlin/com/workflow/orchestrator/companyb/.gitkeep
```

- [ ] **Step 5: Verify B builds + descriptor valid**

Run: `./gradlew :plugin-b:buildPlugin`
Expected: BUILD SUCCESSFUL; `plugin-b/build/distributions/*.zip` produced. If `localPlugin(project(rootProject.path))` fails to resolve, apply the Step-2 FALLBACK and re-run.
Run: `./gradlew verifyPlugin`
Expected: passes.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts plugin-b/
git commit -m "feat(plugin-split): add :plugin-b skeleton depending on plugin A"
```

---

### Task 2: `@State` settings-migration **framework** (stamp sentinel; behavior unchanged)

Establishes the migration rail. **It stamps a `settingsSchemaVersion` sentinel only — it does NOT materialize defaults.** (Verified: `BaseState`'s `ObjectStoredProperty.setValue` short-circuits value-equal assignments, and serialization is gated on `isEqualToDefault()`, so `state.x = state.x` cannot force a value-equals-default field into XML.) The sentinel lets a **later phase** that changes a default (e.g. `defaultTargetBranch` `"develop"`→`"main"`) distinguish upgraders (`settingsSchemaVersion >= 1`) from brand-new installs (`== 0`) and preserve the old value for upgraders only — there, the old default is a literal *unequal* to the new default, so it serializes.

**Files:**
- Modify: `core/.../core/settings/PluginSettings.kt` (add the sentinel to `State`)
- Create: `core/.../core/settings/SettingsMigration.kt`, `core/.../core/settings/SettingsMigrationStartupActivity.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (register the activity after the onboarding entry at lines 275–276)
- Test: `core/src/test/kotlin/com/workflow/orchestrator/core/settings/SettingsMigrationTest.kt`, `core/src/test/kotlin/com/workflow/orchestrator/core/settings/SettingsMigrationSerializationTest.kt`

**Interfaces — Produces:** `SettingsMigration.CURRENT_VERSION: Int = 1`, `fun migrate(state: PluginSettings.State): Boolean`; `State.settingsSchemaVersion` (`by property(0)`).

- [ ] **Step 1: Write the failing unit test (version-bump logic)**

`core/src/test/kotlin/.../settings/SettingsMigrationTest.kt`:

```kotlin
package com.workflow.orchestrator.core.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsMigrationTest {
    @Test fun `v0 state is stamped to current version`() {
        val state = PluginSettings.State()
        assertEquals(0, state.settingsSchemaVersion)
        assertTrue(SettingsMigration.migrate(state))
        assertEquals(SettingsMigration.CURRENT_VERSION, state.settingsSchemaVersion)
    }
    @Test fun `already-current state is a no-op`() {
        val state = PluginSettings.State()
        state.settingsSchemaVersion = SettingsMigration.CURRENT_VERSION
        assertFalse(SettingsMigration.migrate(state))
    }
}
```

> Plain JUnit5 (no `BasePlatformTestCase`); two methods OK. `PluginSettings.State()` constructs without the IDE.

- [ ] **Step 2: Run → fails** — `./gradlew :core:test --tests "*SettingsMigrationTest"` → FAIL (`SettingsMigration`/`settingsSchemaVersion` unresolved).

- [ ] **Step 3: Add the sentinel field** — in `PluginSettings.kt`, as the first field of `class State : BaseState()`:

```kotlin
        /** Schema version for one-shot settings migrations (see SettingsMigration). 0 = pre-migration. */
        var settingsSchemaVersion by property(0)
```

(`property(Int)` is already used in this file, e.g. `jiraBoardId by property(0)` at `:35`.)

- [ ] **Step 4: Write `SettingsMigration.kt`**

```kotlin
package com.workflow.orchestrator.core.settings

/**
 * One-shot, version-gated migrations for [PluginSettings.State].
 *
 * Phase 0a only STAMPS the sentinel. It does NOT materialize current defaults: BaseState omits a
 * field from XML when its value equals the field default (ObjectStoredProperty.setValue
 * short-circuits equal assignments; serialization is gated on isEqualToDefault), so a self-
 * assignment cannot force a value-equals-default field into XML. Instead, the sentinel records
 * "this install existed at/before schema v1", which lets a LATER phase that changes a convention
 * default tell upgraders (settingsSchemaVersion >= 1) apart from brand-new installs (== 0) and
 * write the OLD default literal (now != the NEW default, so it serializes) for upgraders only.
 */
object SettingsMigration {
    const val CURRENT_VERSION = 1

    /** @return true if [state] was mutated. Idempotent at [CURRENT_VERSION]. */
    fun migrate(state: PluginSettings.State): Boolean {
        if (state.settingsSchemaVersion >= CURRENT_VERSION) return false
        // (No default materialization here — see the class KDoc; deferred to Phase 1.)
        state.settingsSchemaVersion = CURRENT_VERSION
        return true
    }
}
```

- [ ] **Step 5: Run → passes** — `./gradlew :core:test --tests "*SettingsMigrationTest"` → PASS.

- [ ] **Step 6: Write the XML round-trip test (proves the sentinel actually persists)**

`core/src/test/kotlin/.../settings/SettingsMigrationSerializationTest.kt`:

```kotlin
package com.workflow.orchestrator.core.settings

import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettingsMigrationSerializationTest {
    @Test fun `stamped sentinel survives serialize-deserialize, default field stays at default`() {
        val state = PluginSettings.State()
        SettingsMigration.migrate(state) // settingsSchemaVersion 0 -> 1 (a real change -> serializes)

        val element = XmlSerializer.serialize(state)
        val restored = XmlSerializer.deserialize(element, PluginSettings.State::class.java)

        assertEquals(1, restored.settingsSchemaVersion) // the sentinel round-trips
        // defaultTargetBranch was NOT touched and equals its default, so it was omitted from XML
        // and the restored state shows the (unchanged) default — documenting why materialization
        // is impossible here and is deferred to Phase 1:
        assertEquals("develop", restored.defaultTargetBranch)
    }
}
```

> VERIFY: `com.intellij.util.xmlb.XmlSerializer.serialize/deserialize` honors the `BaseState` filter in 2025.1 (it is the mechanism the component store uses). If a `BaseState` instance doesn't round-trip cleanly via `XmlSerializer` here, use `com.intellij.configurationStore.serialize(state)` / the component-store deserialize util instead — but the assertions stand. Plain JUnit5; one assertion-cluster, single method.

- [ ] **Step 7: Run the round-trip test → passes** — `./gradlew :core:test --tests "*SettingsMigrationSerializationTest"` → PASS. **If `settingsSchemaVersion` does NOT round-trip as 1, STOP** — the sentinel itself isn't persisting and the whole migration approach is invalid; investigate before continuing.

- [ ] **Step 8: Wire the startup activity** — `SettingsMigrationStartupActivity.kt`:

```kotlin
package com.workflow.orchestrator.core.settings

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class SettingsMigrationStartupActivity : ProjectActivity {
    private val log = Logger.getInstance(SettingsMigrationStartupActivity::class.java)
    override suspend fun execute(project: Project) {
        if (SettingsMigration.migrate(PluginSettings.getInstance(project).state)) {
            log.info("[SettingsMigration] stamped schema v${SettingsMigration.CURRENT_VERSION}")
        }
    }
}
```

Register in `src/main/resources/META-INF/plugin.xml` immediately after the `OnboardingStartupListener` entry (line 275–276):

```xml
        <postStartupActivity
            implementation="com.workflow.orchestrator.core.settings.SettingsMigrationStartupActivity"/>
```

- [ ] **Step 9: `./gradlew :core:test` → PASS; commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/SettingsMigration.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/settings/SettingsMigrationStartupActivity.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/settings/ \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat(core): @State settings-migration framework — stamp schema sentinel (behavior unchanged)"
```

---

### Task 3: Prove B overrides an A extension point (WorkflowConfig)

**Files:** Create `plugin-b/.../companyb/CompanyBWorkflowConfig.kt`; modify `plugin-b/.../META-INF/plugin.xml`; Test `core/src/test/kotlin/.../config/WorkflowConfigOverrideTest.kt`.

**Interfaces — Consumes:** `WorkflowConfig` (`order get()=0`, `baseUrl(ServiceType)`, `EP_NAME`, `resolve()=minByOrNull{order}` in `runCatching`); `ServiceType.JIRA`. (`DefaultWorkflowConfig.order == Int.MAX_VALUE`.)

- [ ] **Step 1: Write the resolver-pinning test (`:core`)**

`core/src/test/kotlin/.../config/WorkflowConfigOverrideTest.kt`:

```kotlin
package com.workflow.orchestrator.core.config

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.workflow.orchestrator.core.model.ServiceType

class WorkflowConfigOverrideTest : BasePlatformTestCase() {
    fun `test lower-order WorkflowConfig override wins over the default`() {
        val override = object : WorkflowConfig {
            override val order: Int get() = 0
            override fun baseUrl(service: ServiceType): String =
                if (service == ServiceType.JIRA) "https://jira.company-b.example/" else ""
        }
        WorkflowConfig.EP_NAME.point.registerExtension(override, testRootDisposable)
        assertEquals("https://jira.company-b.example/", WorkflowConfig.resolve().baseUrl(ServiceType.JIRA))
    }
}
```

> One method; name starts with `test` (required — `BasePlatformTestCase` is JUnit-3). `registerExtension(_, testRootDisposable)` auto-unregisters at teardown.

- [ ] **Step 2: Run it → PASSES (this is a behavior-pin, not a TDD red step)**

Run: `./gradlew :core:test --tests "*WorkflowConfigOverrideTest"`
Expected: **PASS** — the EP mechanism is already live in `:core`, so registering a lower-order extension already wins. This test's purpose is to *pin* "lowest-order wins" so a later change can't regress it. (If it FAILS because `EP_NAME.point` is unavailable in the fixture, the EP isn't loaded in the `:core` sandbox — investigate; do not stub around it.)

- [ ] **Step 3: Implement B's override** — `plugin-b/.../companyb/CompanyBWorkflowConfig.kt`:

```kotlin
package com.workflow.orchestrator.companyb

import com.workflow.orchestrator.core.config.WorkflowConfig
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings

/** order=0 beats DefaultWorkflowConfig (Int.MAX_VALUE) via WorkflowConfig.resolve(). For 0a it
 *  delegates to configured URLs (identical behavior); its presence is the override-mechanism proof. */
class CompanyBWorkflowConfig : WorkflowConfig {
    override val order: Int get() = 0
    override fun baseUrl(service: ServiceType): String {
        val state = ConnectionSettings.getInstance().state
        return when (service) {
            ServiceType.JIRA -> state.jiraUrl
            ServiceType.BAMBOO -> state.bambooUrl
            ServiceType.BITBUCKET -> state.bitbucketUrl
            ServiceType.SONARQUBE -> state.sonarUrl
            ServiceType.SOURCEGRAPH -> state.sourcegraphUrl
            ServiceType.WEB_SEARCH -> ""
        }
    }
}
```

- [ ] **Step 4: Register it in B's descriptor** — add to `plugin-b/.../META-INF/plugin.xml` inside `<idea-plugin>`:

```xml
    <extensions defaultExtensionNs="com.workflow.orchestrator">
        <workflowConfig implementation="com.workflow.orchestrator.companyb.CompanyBWorkflowConfig"/>
    </extensions>
```

- [ ] **Step 5: `./gradlew :core:test --tests "*WorkflowConfigOverrideTest"` → PASS; `./gradlew :plugin-b:buildPlugin` → SUCCESS.**

- [ ] **Step 6: Two-plugin runIde smoke (HARD GATE — spec §12 "observed at runtime with both plugins loaded")**

Run the sandbox with B loaded: `./gradlew runIde`. **VERIFY** the run task loads `:plugin-b` — with `localPlugin(project(...))` B should be on the sandbox; if it isn't auto-installed, configure the `runIde`/`prepareSandbox` task to include the second local plugin (check the 2.12.0 docs). In the sandbox IDE: Settings → Plugins shows **"Workflow Orchestrator — Company B"** enabled with no load error. Temporarily add `LOG.info("WorkflowConfig impl = " + WorkflowConfig.resolve()::class.java.name)` to `CredentialStore.serverUrlFor` (`core/.../auth/CredentialStore.kt:79`), confirm the log shows `CompanyBWorkflowConfig`, then remove the log line. **Record PASS/FAIL in the commit message.** This is the gate, not optional evidence.

- [ ] **Step 7: Commit**

```bash
git add plugin-b/src/main/kotlin/com/workflow/orchestrator/companyb/CompanyBWorkflowConfig.kt \
        plugin-b/src/main/resources/META-INF/plugin.xml \
        core/src/test/kotlin/com/workflow/orchestrator/core/config/WorkflowConfigOverrideTest.kt
git commit -m "feat(plugin-split): B overrides A's WorkflowConfig EP (runIde smoke: PASS/FAIL)"
```

---

### Task 4: `JiraTicketProvider` resolver → lowest-order-wins

`getInstance()` uses `firstOrNull()` (non-deterministic). Upgrade to mirror `WorkflowConfig` so a future B override deterministically wins. (Full rename to `IssueTrackerProvider` is deferred — Phase 1+.)

**Files:** Modify `core/.../core/workflow/JiraTicketProvider.kt`, `jira/.../service/JiraTicketProviderImpl.kt`; Test `core/src/test/kotlin/.../workflow/JiraTicketProviderResolverTest.kt`.

**Interfaces — Produces:** `JiraTicketProvider.order: Int get() = 0` (interface default); `JiraTicketProviderImpl.order = Int.MAX_VALUE` (shipped fallback sits lowest priority); `getInstance()` returns `minByOrNull{order}`.

> **Design note (review pushback, kept):** interface default `0` + shipped impl `Int.MAX_VALUE` is correct and mirrors `WorkflowConfig`. A B override that doesn't set `order` defaults to `0`, beating the `MAX_VALUE` fallback. (Making the interface default `MAX_VALUE` would make B's override *also* default to `MAX_VALUE` → a tie.)

- [ ] **Step 1: Write the failing test**

`core/src/test/kotlin/.../workflow/JiraTicketProviderResolverTest.kt`:

```kotlin
package com.workflow.orchestrator.core.workflow

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JiraTicketProviderResolverTest : BasePlatformTestCase() {
    fun `test lowest-order JiraTicketProvider wins`() {
        val high = object : JiraTicketProvider {
            override val order: Int get() = 100
            override suspend fun getTicketDetails(ticketId: String): TicketDetails? = null
            override suspend fun getTicketContext(key: String): TicketContext? = null
        }
        val low = object : JiraTicketProvider {
            override val order: Int get() = 5
            override suspend fun getTicketDetails(ticketId: String): TicketDetails? = null
            override suspend fun getTicketContext(key: String): TicketContext? = null
        }
        JiraTicketProvider.EP_NAME.point.registerExtension(high, testRootDisposable)
        JiraTicketProvider.EP_NAME.point.registerExtension(low, testRootDisposable)
        assertSame(low, JiraTicketProvider.getInstance())
    }
}
```

- [ ] **Step 2: Run → fails** — `./gradlew :core:test --tests "*JiraTicketProviderResolverTest"` → FAIL (`order` unresolved; `firstOrNull()`).

- [ ] **Step 3: Add `order` + upgrade resolver** — in `JiraTicketProvider.kt`:

```kotlin
    /** Lower runs first; the shipped impl sits at the lowest priority (Int.MAX_VALUE). */
    val order: Int get() = 0
```

and change the companion:

```kotlin
        fun getInstance(): JiraTicketProvider? =
            EP_NAME.extensionList.minByOrNull { it.order }
```

- [ ] **Step 4: Shipped impl → lowest priority** — in `jira/.../service/JiraTicketProviderImpl.kt` add:

```kotlin
    override val order: Int get() = Int.MAX_VALUE
```

- [ ] **Step 5: `./gradlew :core:test --tests "*JiraTicketProviderResolverTest"` → PASS; `./gradlew :jira:test` → PASS.**

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/workflow/JiraTicketProvider.kt \
        jira/src/main/kotlin/com/workflow/orchestrator/jira/service/JiraTicketProviderImpl.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/workflow/JiraTicketProviderResolverTest.kt
git commit -m "feat(core): JiraTicketProvider resolver -> lowest-order-wins (deterministic override)"
```

---

### Task 5: Agent-tool contribution extension point (+ runtime proof)

A narrow factory EP so B can register an agent tool; A keeps owning `AgentTool`/`ToolRegistry`.

**Files:** Create `agent/.../tools/contribution/AgentToolContributor.kt`, `agent/.../tools/contribution/ToolRegistrationContext.kt`; modify `src/main/resources/META-INF/plugin.xml` (declare EP), `agent/.../AgentService.kt` (iterate EP), `plugin-b/.../META-INF/plugin.xml` + a B stub contributor (runtime proof); Test `agent/src/test/kotlin/.../tools/contribution/ToolRegistrationContextTest.kt`.

**Interfaces — Consumes:** `ToolRegistry.registerCore(tool)`/`registerDeferred(tool, category)`/`get(name)`; `AgentTool`. **Produces:** `AgentToolContributor { fun registerTools(context: ToolRegistrationContext) }` + `EP_NAME`; `ToolRegistrationContext(project, registry)`.

> **Coupling note (spec §8.2):** `AgentTool.spillOrFormat()` looks up `AgentService` via `project.getServiceIfCreated(...)`, so a B tool that *calls* `spillOrFormat` transitively needs `AgentService`. Phase-0a's stub does NOT call it, so a direct `AgentTool` impl is fine here. Production B tools that need spill go through the **`ToolRegistrationService`** narrow surface — **deferred to Phase-0b** (see Out of scope).

- [ ] **Step 1: Write the failing test (registration mechanism)**

`agent/src/test/kotlin/.../tools/contribution/ToolRegistrationContextTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.contribution

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class ToolRegistrationContextTest {
    private class NoopTool : AgentTool {
        override val name = "companyb_noop"
        override val description = "no-op contributed tool"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "ok", summary = "ok", tokenEstimate = 1)
    }

    @Test fun `contributor registers a tool into the registry via the context`() {
        val registry = ToolRegistry()
        val context = ToolRegistrationContext(mockk(relaxed = true), registry)
        object : AgentToolContributor {
            override fun registerTools(ctx: ToolRegistrationContext) = ctx.registerCore(NoopTool())
        }.registerTools(context)
        assertNotNull(registry.get("companyb_noop"))
    }
}
```

> Verified shapes: `WorkerType` + `ToolResult` are in `com.workflow.orchestrator.agent.tools` (NOT `agent.loop`); `FunctionParameters` (typealias at `agent/.../api/dto/`) requires `properties`; `ToolResult` primary ctor is `(content, summary, tokenEstimate, ...)` — no `.text(...)` factory. Plain JUnit5 + MockK (`ToolRegistry` is a plain class).

- [ ] **Step 2: Run → fails** — `./gradlew :agent:test --tests "*ToolRegistrationContextTest"` → FAIL (`AgentToolContributor`/`ToolRegistrationContext` unresolved).

- [ ] **Step 3: Implement the EP interface + context**

`agent/.../tools/contribution/AgentToolContributor.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.contribution

import com.intellij.openapi.extensions.ExtensionPointName
import com.workflow.orchestrator.core.api.InternalApi

/** EP letting a depending plugin (B) contribute agent tools. @InternalApi: public so B can
 *  implement it, but NOT frozen — we may change it; B recompiles in lockstep. */
@InternalApi
interface AgentToolContributor {
    fun registerTools(context: ToolRegistrationContext)
    companion object {
        val EP_NAME: ExtensionPointName<AgentToolContributor> =
            ExtensionPointName.create("com.workflow.orchestrator.agentToolContributor")
    }
}
```

`agent/.../tools/contribution/ToolRegistrationContext.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.contribution

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.core.api.InternalApi

@InternalApi
class ToolRegistrationContext(
    val project: Project,
    private val registry: ToolRegistry,
) {
    fun registerCore(tool: AgentTool) = registry.registerCore(tool)
    fun registerDeferred(tool: AgentTool, category: String = "Other") =
        registry.registerDeferred(tool, category)
}
```

- [ ] **Step 4: Run → passes** — `./gradlew :agent:test --tests "*ToolRegistrationContextTest"` → PASS.

- [ ] **Step 5: Declare the EP + iterate it in `registerAllTools()`**

In `src/main/resources/META-INF/plugin.xml`, add to the `<extensionPoints>` block (after line 74):

```xml
        <extensionPoint
            qualifiedName="com.workflow.orchestrator.agentToolContributor"
            interface="com.workflow.orchestrator.agent.tools.contribution.AgentToolContributor"
            dynamic="true"/>
```

In `agent/.../AgentService.kt`, insert at `~:1318` — **after** the `registerConditionalIntegrationTools()` call (`:1317`) and **before** the `if (failedRegistrations.isNotEmpty())` check (`:1319`); fields in scope are `registry` (`:106`), `project` (`:100`), `log` (`:104`):

```kotlin
        // Tools contributed by depending plugins (e.g. plugin B) via the agentToolContributor EP.
        runCatching {
            val ctx = com.workflow.orchestrator.agent.tools.contribution
                .ToolRegistrationContext(project, registry)
            com.workflow.orchestrator.agent.tools.contribution.AgentToolContributor
                .EP_NAME.extensionList.forEach { it.registerTools(ctx) }
        }.onFailure { log.warn("[Tools] agentToolContributor EP iteration failed", it) }
```

- [ ] **Step 6: `./gradlew :agent:test` → PASS; `./gradlew verifyPlugin` → PASS.**

- [ ] **Step 7: B contributes a stub tool + two-plugin runIde smoke (HARD GATE — spec §12 gate 3)**

Create `plugin-b/src/main/kotlin/com/workflow/orchestrator/companyb/CompanyBToolContributor.kt`:

```kotlin
package com.workflow.orchestrator.companyb

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.contribution.AgentToolContributor
import com.workflow.orchestrator.agent.tools.contribution.ToolRegistrationContext
import kotlinx.serialization.json.JsonObject

class CompanyBToolContributor : AgentToolContributor {
    override fun registerTools(context: ToolRegistrationContext) =
        context.registerCore(object : AgentTool {
            override val name = "companyb_noop"
            override val description = "Company B no-op demo tool"
            override val parameters = FunctionParameters(properties = emptyMap())
            override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)
            override suspend fun execute(params: JsonObject, project: Project) =
                ToolResult(content = "ok", summary = "ok", tokenEstimate = 1)
        })
}
```

> This is the **cross-module compile proof**: B must `import com.workflow.orchestrator.agent.tools.*` and construct an `AgentTool`. **VERIFY** `:plugin-b:compileKotlin` resolves `:agent`'s classes through `localPlugin(A)`. If B's compile classpath does NOT expose `:agent` (only `:core`), that's a real finding — add an explicit `compileOnly(project(":agent"))` to `plugin-b/build.gradle.kts` (compile-against-but-don't-bundle, since A ships `:agent` at runtime) and record it.

Register in `plugin-b/.../META-INF/plugin.xml` (inside the existing `<extensions defaultExtensionNs="com.workflow.orchestrator">`):

```xml
        <agentToolContributor implementation="com.workflow.orchestrator.companyb.CompanyBToolContributor"/>
```

Run `./gradlew :plugin-b:buildPlugin` → SUCCESS. Then `./gradlew runIde` with both plugins; open the agent and confirm `companyb_noop` is in the tool registry (it appears in the agent's tool list / system prompt, or add a temporary `log.info(registry.allToolNames())` after the EP iteration and confirm `companyb_noop` is present, then remove). **Record PASS/FAIL in the commit message.**

- [ ] **Step 8: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/contribution/ \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/contribution/ToolRegistrationContextTest.kt \
        src/main/resources/META-INF/plugin.xml \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
        plugin-b/src/main/kotlin/com/workflow/orchestrator/companyb/CompanyBToolContributor.kt \
        plugin-b/src/main/resources/META-INF/plugin.xml \
        plugin-b/build.gradle.kts
git commit -m "feat(agent): agentToolContributor EP + B contributes a tool (runIde smoke: PASS/FAIL)"
```

---

### Task 6: Self-declared plan-mode write-safety (`isMutating`)

So a B-contributed write tool is plan-mode-blocked without editing A's hardcoded `WRITE_TOOLS`.

**Files:** Modify `agent/.../tools/AgentTool.kt` (add `isMutating`), `agent/.../loop/AgentLoop.kt` (guard at lines 1942–1944), and the 10 write-tool classes; Test `agent/src/test/kotlin/.../tools/AgentToolMutatingTest.kt`.

**Interfaces — Produces:** `AgentTool.isMutating: Boolean get() = false`. Guard: `tool.isMutating || toolName in WRITE_TOOLS || tool.isWriteAction(planModeAction)`.

The 10 `WRITE_TOOLS` tool classes and their packages (verified):
- `agent/.../tools/builtin/`: `EditFileTool` (`edit_file`), `CreateFileTool` (`create_file`), `DeleteFileTool` (`delete_file`), `RunCommandTool` (`run_command`), `RevertFileTool` (`revert_file`), `SendStdinTool` (`send_stdin`), `BackgroundProcessTool` (`background_process`).
- `agent/.../tools/ide/`: `FormatCodeTool` (`format_code`), `OptimizeImportsTool` (`optimize_imports`), `RefactorRenameTool` (`refactor_rename` — note this one takes a ctor arg `RefactorRenameTool(providerRegistry)`; the `isMutating` override is still a plain property).

- [ ] **Step 1: Write the failing test**

`agent/src/test/kotlin/.../tools/AgentToolMutatingTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools

import com.workflow.orchestrator.agent.tools.builtin.EditFileTool
import com.workflow.orchestrator.agent.tools.builtin.ReadFileTool
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentToolMutatingTest {
    @Test fun `read-only tool defaults to non-mutating`() { assertFalse(ReadFileTool().isMutating) }
    @Test fun `edit_file declares itself mutating`() { assertTrue(EditFileTool().isMutating) }
}
```

> `ReadFileTool`/`EditFileTool` are in `tools/builtin/`, both no-arg ctors (verified). Plain JUnit5.

- [ ] **Step 2: Run → fails** — `./gradlew :agent:test --tests "*AgentToolMutatingTest"` → FAIL (`isMutating` unresolved).

- [ ] **Step 3: Add `isMutating` to the interface** — in `AgentTool.kt`, next to `isWriteAction`:

```kotlin
    /** True if this tool mutates state and must be blocked in plan mode. The guard in AgentLoop
     *  uses `isMutating || isWriteAction(action)`, so contributed tools declare their own safety
     *  without editing the hardcoded WRITE_TOOLS set. */
    val isMutating: Boolean get() = false
```

- [ ] **Step 4: Override on the 10 write-tool classes** — add `override val isMutating: Boolean get() = true` to each: `EditFileTool`, `CreateFileTool`, `DeleteFileTool`, `RunCommandTool`, `RevertFileTool`, `SendStdinTool`, `BackgroundProcessTool` (in `tools/builtin/`) **and** `FormatCodeTool`, `OptimizeImportsTool`, `RefactorRenameTool` (in `tools/ide/`).

- [ ] **Step 5: Update the plan-mode guard** — in `AgentLoop.kt` at lines 1942–1944, change:

```kotlin
if ((planModeProvider?.invoke() ?: planMode) &&
    (toolName in WRITE_TOOLS || tool.isWriteAction(planModeAction))
) {
```

to:

```kotlin
if ((planModeProvider?.invoke() ?: planMode) &&
    (tool.isMutating || toolName in WRITE_TOOLS || tool.isWriteAction(planModeAction))
) {
```

(`WRITE_TOOLS` stays as a backstop; a later phase deletes it once all built-ins self-declare.)

- [ ] **Step 6: `./gradlew :agent:test` → PASS (plan-mode behavior unchanged); commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/ \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/AgentToolMutatingTest.kt
git commit -m "feat(agent): self-declared isMutating for plan-mode safety (enables contributed write tools)"
```

---

### Task 7: konsist contracts — public EP surface, both-way dep direction

**Files:** Create `konsist/src/test/kotlin/.../PublicApiSurfaceTest.kt`, `konsist/src/test/kotlin/.../PluginBDependencyDirectionTest.kt`; modify `ModuleBoundaryTest.kt`, `LayeringTest.kt` (exclude plugin-b paths).

**Interfaces — Consumes:** Konsist 0.17.3 `Konsist.scopeFromProject().files`, `file.interfaces()`, `iface.hasInternalModifier` (all verified against the 0.17.3 jar).

- [ ] **Step 1: Write the public-EP-surface test (with a scope self-guard)**

`konsist/src/test/kotlin/.../PublicApiSurfaceTest.kt`:

```kotlin
package com.workflow.orchestrator.konsist

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicApiSurfaceTest {
    private val bFacingEpInterfaces = listOf(
        "AgentToolContributor", "WorkflowConfig", "AuthProvider", "FeatureRegistry", "JiraTicketProvider",
    )

    @Test fun `B-facing EP interfaces are public`() {
        val targets = Konsist.scopeFromProject().files
            .flatMap { it.interfaces() }
            .filter { it.name in bFacingEpInterfaces }

        // Self-guard: if the scope/API is wrong, fail loudly instead of silently passing on empty.
        assertTrue(targets.isNotEmpty(), "Konsist found none of the target EP interfaces — check API/scope")

        val violations = targets.filter { it.hasInternalModifier }.map { "${it.name} is `internal`" }
        assertTrue(violations.isEmpty(),
            "EP interfaces must be public for plugin B:\n" + violations.joinToString("\n"))
    }
}
```

> Note: `WorkflowConfig`/`AuthProvider`/`FeatureRegistry` are already `@StableApi(since="0.86")` — this test does NOT change that and only asserts the absence of the `internal` modifier (applies to all five, `@StableApi` or `@InternalApi`).

- [ ] **Step 2: Run** — `./gradlew :konsist:test --tests "*PublicApiSurfaceTest"` → PASS. To prove it bites: temporarily mark one EP interface `internal`, re-run → FAIL, revert. Record that you verified it fails on an `internal` interface.

- [ ] **Step 3: Write the A→B direction test**

`konsist/src/test/kotlin/.../PluginBDependencyDirectionTest.kt`:

```kotlin
package com.workflow.orchestrator.konsist

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginBDependencyDirectionTest {
    @Test fun `A modules do not import plugin-b`() {
        val violations = Konsist.scopeFromProject().files
            .filterNot { it.path.contains("/plugin-b/") }
            .filter { it.path.contains("/src/main/") }
            .flatMap { f -> f.imports.filter { it.name.startsWith("com.workflow.orchestrator.companyb.") }
                .map { "${f.path}: imports ${it.name}" } }
        assertTrue(violations.isEmpty(), "A must not depend on plugin B:\n" + violations.joinToString("\n"))
    }
}
```

- [ ] **Step 4: Exclude `:plugin-b` from A's boundary scans** — in `ModuleBoundaryTest.kt` and `LayeringTest.kt`, change `val files = Konsist.scopeFromProject().files` to:

```kotlin
        val files = Konsist.scopeFromProject().files
            .filterNot { it.path.contains("/plugin-b/") }
```

- [ ] **Step 5: `./gradlew :konsist:test` → PASS (all four classes); commit**

```bash
git add konsist/src/test/kotlin/com/workflow/orchestrator/konsist/
git commit -m "test(konsist): public EP surface + A<->B dep direction + exclude :plugin-b from A scans"
```

---

### Task 8: Phase-0a green gate

- [ ] **Step 1: Full check (all touched + dependent modules)**

Run: `./gradlew :core:test :jira:test :bamboo:test :sonar:test :pullrequest:test :handover:test :agent:test :konsist:test :plugin-b:buildPlugin verifyPlugin`
Expected: all PASS / BUILD SUCCESSFUL. (Tasks 2 & 5 edit the shared root `plugin.xml` + `PluginSettings.kt`, so the broader module set must be exercised.)

- [ ] **Step 2: Confirm spec §12 done-gates** — A builds + tests green standalone ✔; B builds + installs on A ✔ (`:plugin-b:buildPlugin` + Task-3/5 runIde smokes); one override (Task 3) + one B-contributed agent-tool (Task 5) work end-to-end ✔ (both with recorded runIde PASS); contract test green ✔ (Task 7). Note evidence for each.

- [ ] **Step 3: Build-cache note** — if any commit changed a lambda type to/from `suspend`, re-run the affected module with `--no-build-cache --rerun-tasks` before declaring green.

---

## Out of scope (this plan)

**Deferred to the Phase-0b plan (the wide provider seams):**
- The `LlmProvider` + `ToolProtocol` seam — wide shape: system-prompt-assembly injection, per-SSE-chunk streaming segmentation, history-header convention, model-catalog/capability/context-window + provider error classification, and absorbing `BrainRouter`'s per-message routing (spec §6).
- The net-new `VcsHostClient` / `CiService` connector seams (Atlassian impls).
- The settings-section contribution EP.
- The **project-scoped `ToolRegistrationService`** (spec §8.2) — the narrow host that lets a *production* B tool register without B implementing the fat `AgentTool` (which transitively reaches `AgentService` via `spillOrFormat`). Phase-0a's stub tool doesn't call `spillOrFormat`, so it's a direct impl; the narrow surface is needed only for real B tools, so it ships with the seam work in 0b.

**Deferred to Phase 1+:** actual blanking of convention defaults (uses the Task-2 sentinel to preserve upgraders); the `IssueTrackerProvider` *rename* (this plan does the resolver upgrade only); the monitor-source contribution EP.

**Deferred to Phase 4 / Phase 5:** the native `AnthropicDirectProvider` + persisted-message-format migration (Phase 4); freezing `@StableApi` for external consumers + Marketplace publish (deferred Phase 5).
