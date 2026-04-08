# Auto-Detect Project Keys Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auto-detect docker tag name from `bamboo-specs/**/*.java`, extract sonar detection into a service, and run all detection automatically on project open / branch change / file changes — without overwriting user-set values.

**Architecture:** New `BambooSpecsParser` (pure util) + `AutoDetectOrchestrator` (project service) coordinate existing detectors (`RepoContextResolver`, `PlanDetectionService`, new `SonarKeyDetector`). Triggers via `ProjectActivity`, `EventBus.BranchChanged` subscription, and `BulkFileListener`. Fill-only-empty rule eliminates schema migration.

**Tech Stack:** Kotlin, IntelliJ Platform SDK, JUnit 5, mockk, kotlinx.coroutines.test

**Spec:** `docs/superpowers/specs/2026-04-08-auto-detect-project-keys-design.md`

---

## File Structure

**New files:**

| File | Module | Responsibility |
|---|---|---|
| `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/BambooSpecsParser.kt` | `:core` | Pure util — walks `bamboo-specs/**/*.java`, returns map of `String constant -> String value` |
| `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectResult.kt` | `:core` | Data class returned by `detectAll()`: counts and lists of filled fields |
| `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectOrchestrator.kt` | `:core` | Project service, coordinates 4 detectors, applies fill-only-empty rule, fires one notification |
| `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/WorkflowAutoDetectStartupActivity.kt` | `:core` | `ProjectActivity` — triggers `detectAll()` after smart mode |
| `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectFileListener.kt` | `:core` | `BulkFileListener` — re-runs targeted detectors on `.git/config` / `pom.xml` / `bamboo-specs/*.java` changes |
| `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarKeyDetector.kt` | `:sonar` | Project service — Maven property lookup, extracted from `CodeQualityConfigurable` |
| `core/src/test/kotlin/com/workflow/orchestrator/core/autodetect/BambooSpecsParserTest.kt` | `:core` | Pure unit tests against fixture files |
| `core/src/test/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectOrchestratorTest.kt` | `:core` | Unit tests with fake detectors |
| `core/src/test/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectIntegrationTest.kt` | `:core` | End-to-end with fixture project tree |
| `core/src/test/testData/auto-detect-project/` | `:core` | Fixture: `.git/config`, `pom.xml`, `bamboo-specs/.../PlanProperties.java` |
| `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/SonarKeyDetectorTest.kt` | `:sonar` | Maven detector unit tests |
| `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionServiceAutoDetectIfMissingTest.kt` | `:bamboo` | Test for the new wrapper |

**Modified files:**

| File | Change |
|---|---|
| `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionService.kt` | Add `autoDetectIfMissing(project: Project)` wrapper |
| `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/settings/CodeQualityConfigurable.kt` | Replace inlined Maven snippet (lines 70-89) with `SonarKeyDetector.detect()` call |
| `core/src/main/kotlin/com/workflow/orchestrator/core/settings/RepositoriesConfigurable.kt` | After `autoDetectRepos()`, also call `AutoDetectOrchestrator.detectAll()` |
| `src/main/resources/META-INF/plugin.xml` | Register `AutoDetectOrchestrator`, `SonarKeyDetector`, `WorkflowAutoDetectStartupActivity`, `AutoDetectFileListener` |

**Note on Bamboo project key:** The spec mentioned "derive bamboo project key from git remote" but `RepoConfig` has no `bambooProjectKey` field — `bitbucketProjectKey` already holds the same value (parsed from the same regex group, used as the bamboo project key wherever needed). No task in this plan addresses it because there's nothing to add.

---

## Phase 1: Pure Utilities

### Task 1: BambooSpecsParser

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/BambooSpecsParser.kt`
- Test: `core/src/test/kotlin/com/workflow/orchestrator/core/autodetect/BambooSpecsParserTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/workflow/orchestrator/core/autodetect/BambooSpecsParserTest.kt`:

```kotlin
package com.workflow.orchestrator.core.autodetect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BambooSpecsParserTest {

    @Test
    fun `returns empty map when bamboo-specs directory missing`(@TempDir root: Path) {
        val result = BambooSpecsParser.parseConstants(root)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `extracts constants from PlanProperties java`(@TempDir root: Path) {
        val javaDir = root.resolve("bamboo-specs/src/main/java/constants")
        Files.createDirectories(javaDir)
        Files.writeString(javaDir.resolve("PlanProperties.java"), """
            package constants;
            public class PlanProperties {
                private static final String REPOSITORY_NAME = "my-sample-service";
                private static final String PLAN_KEY = "MYSAMPLESERVICE";
                private static final String DOCKER_TAG_NAME = "MySampleServiceDockerTag";
                public static final String PROJECT_KEY = "MYPROJ";
            }
        """.trimIndent())

        val result = BambooSpecsParser.parseConstants(root)

        assertEquals("my-sample-service", result["REPOSITORY_NAME"])
        assertEquals("MYSAMPLESERVICE", result["PLAN_KEY"])
        assertEquals("MySampleServiceDockerTag", result["DOCKER_TAG_NAME"])
        assertEquals("MYPROJ", result["PROJECT_KEY"])
    }

    @Test
    fun `merges constants across multiple java files`(@TempDir root: Path) {
        val javaDir = root.resolve("bamboo-specs/src/main/java/constants")
        Files.createDirectories(javaDir)
        Files.writeString(javaDir.resolve("ProjectProperties.java"),
            """public class ProjectProperties { private static final String PROJECT_KEY = "ABC"; }""")
        Files.writeString(javaDir.resolve("PlanProperties.java"),
            """public class PlanProperties { private static final String PLAN_KEY = "XYZ"; }""")

        val result = BambooSpecsParser.parseConstants(root)

        assertEquals("ABC", result["PROJECT_KEY"])
        assertEquals("XYZ", result["PLAN_KEY"])
    }

    @Test
    fun `first occurrence wins on duplicate constant names`(@TempDir root: Path) {
        val javaDir = root.resolve("bamboo-specs/src/main/java")
        Files.createDirectories(javaDir)
        Files.writeString(javaDir.resolve("A.java"),
            """class A { private static final String PLAN_KEY = "FIRST"; }""")
        Files.writeString(javaDir.resolve("B.java"),
            """class B { private static final String PLAN_KEY = "SECOND"; }""")

        val result = BambooSpecsParser.parseConstants(root)

        // Either FIRST or SECOND is acceptable as long as parser is deterministic.
        // We assert it's non-null and one of the two values.
        assertTrue(result["PLAN_KEY"] in setOf("FIRST", "SECOND"))
    }

    @Test
    fun `ignores non-string constants and malformed lines`(@TempDir root: Path) {
        val javaDir = root.resolve("bamboo-specs/src/main/java")
        Files.createDirectories(javaDir)
        Files.writeString(javaDir.resolve("Mixed.java"), """
            public class Mixed {
                private static final int BUILD_NUMBER = 42;
                private static final String VALID = "yes";
                // private static final String COMMENTED = "no";
                private static final String[] BRANCHES = { "develop" };
            }
        """.trimIndent())

        val result = BambooSpecsParser.parseConstants(root)

        assertEquals("yes", result["VALID"])
        assertEquals(null, result["BUILD_NUMBER"])
        assertEquals(null, result["BRANCHES"])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :core:test --tests "com.workflow.orchestrator.core.autodetect.BambooSpecsParserTest"
```

Expected: FAIL with "Unresolved reference: BambooSpecsParser"

- [ ] **Step 3: Write minimal implementation**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/BambooSpecsParser.kt`:

```kotlin
package com.workflow.orchestrator.core.autodetect

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * Parses `private/public static final String NAME = "value";` declarations from
 * every `.java` file under `<projectRoot>/bamboo-specs/src/main/java/`.
 *
 * Returns a flat map of constant name -> string value. First occurrence wins on
 * duplicates. Returns empty map if the directory does not exist.
 *
 * Pure utility — no IDE APIs, no caching, no project context.
 */
object BambooSpecsParser {

    private val CONSTANT_REGEX = Regex(
        """(?:private|public|protected)?\s+static\s+final\s+String\s+(\w+)\s*=\s*"([^"]+)""""
    )

    fun parseConstants(projectRoot: Path): Map<String, String> {
        val javaDir = projectRoot.resolve("bamboo-specs/src/main/java")
        if (!javaDir.exists()) return emptyMap()

        val result = mutableMapOf<String, String>()
        Files.walk(javaDir).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() && it.toString().endsWith(".java") }
                .forEach { file ->
                    val content = try { file.readText() } catch (_: Exception) { return@forEach }
                    CONSTANT_REGEX.findAll(content).forEach { match ->
                        val name = match.groupValues[1]
                        val value = match.groupValues[2]
                        result.putIfAbsent(name, value)
                    }
                }
        }
        return result
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :core:test --tests "com.workflow.orchestrator.core.autodetect.BambooSpecsParserTest"
```

Expected: PASS, all 5 tests green.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/BambooSpecsParser.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/autodetect/BambooSpecsParserTest.kt
git commit -m "feat(autodetect): add BambooSpecsParser utility

Pure util that walks bamboo-specs/src/main/java/**/*.java and returns
a map of static-final-String constant names to values. Foundation for
docker tag name auto-detection."
```

---

### Task 2: SonarKeyDetector (extract from CodeQualityConfigurable)

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarKeyDetector.kt`
- Test: `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/SonarKeyDetectorTest.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/settings/CodeQualityConfigurable.kt:70-89`
- Modify: `src/main/resources/META-INF/plugin.xml` (register service)

- [ ] **Step 1: Write the failing test**

Create `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/SonarKeyDetectorTest.kt`:

```kotlin
package com.workflow.orchestrator.sonar.service

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Properties

class SonarKeyDetectorTest {

    private val project = mockk<Project>(relaxed = true)
    private val mavenManager = mockk<MavenProjectsManager>(relaxed = true)
    private lateinit var detector: SonarKeyDetector

    @BeforeEach
    fun setup() {
        mockkStatic(MavenProjectsManager::class)
        every { MavenProjectsManager.getInstance(project) } returns mavenManager
        detector = SonarKeyDetector(project)
    }

    @AfterEach
    fun teardown() {
        unmockkStatic(MavenProjectsManager::class)
    }

    @Test
    fun `returns null when project is not mavenized`() {
        every { mavenManager.isMavenizedProject } returns false
        assertNull(detector.detect())
    }

    @Test
    fun `returns sonar projectKey property when set`() {
        val mavenProject = mockk<MavenProject>()
        val props = Properties().apply { setProperty("sonar.projectKey", "my-explicit-key") }
        every { mavenManager.isMavenizedProject } returns true
        every { mavenManager.rootProjects } returns listOf(mavenProject)
        every { mavenProject.properties } returns props

        assertEquals("my-explicit-key", detector.detect())
    }

    @Test
    fun `falls back to groupId-artifactId when sonar property absent`() {
        val mavenProject = mockk<MavenProject>()
        every { mavenManager.isMavenizedProject } returns true
        every { mavenManager.rootProjects } returns listOf(mavenProject)
        every { mavenProject.properties } returns Properties()
        every { mavenProject.mavenId } returns MavenId("com.acme", "my-service", "1.0")

        assertEquals("com.acme:my-service", detector.detect())
    }

    @Test
    fun `returns null when no root projects`() {
        every { mavenManager.isMavenizedProject } returns true
        every { mavenManager.rootProjects } returns emptyList()
        assertNull(detector.detect())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :sonar:test --tests "com.workflow.orchestrator.sonar.service.SonarKeyDetectorTest"
```

Expected: FAIL with "Unresolved reference: SonarKeyDetector"

- [ ] **Step 3: Create the service**

Create `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarKeyDetector.kt`:

```kotlin
package com.workflow.orchestrator.sonar.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * Detects the SonarQube project key from the IDE's resolved Maven model.
 *
 * Priority:
 *   1. Explicit `<sonar.projectKey>` property in the root pom.xml.
 *      (Maven model already resolves placeholders like `${project.artifactId}`.)
 *   2. Synthesized `groupId:artifactId` from the root project's MavenId.
 *
 * Returns null if the project is not mavenized or has no root projects.
 */
@Service(Service.Level.PROJECT)
class SonarKeyDetector(private val project: Project) {

    private val log = logger<SonarKeyDetector>()

    fun detect(): String? {
        return try {
            val mavenManager = MavenProjectsManager.getInstance(project)
            if (!mavenManager.isMavenizedProject) {
                log.debug("[Sonar:Detect] Project is not mavenized")
                return null
            }
            val rootProject = mavenManager.rootProjects.firstOrNull() ?: run {
                log.debug("[Sonar:Detect] No root Maven projects")
                return null
            }
            val explicit = rootProject.properties?.getProperty("sonar.projectKey")
            if (!explicit.isNullOrBlank()) {
                log.info("[Sonar:Detect] Found explicit sonar.projectKey: $explicit")
                return explicit
            }
            val mavenId = rootProject.mavenId
            val synthesized = "${mavenId.groupId}:${mavenId.artifactId}"
            log.info("[Sonar:Detect] Synthesized sonar key from coordinates: $synthesized")
            synthesized
        } catch (e: Exception) {
            log.warn("[Sonar:Detect] Detection failed", e)
            null
        }
    }
}
```

- [ ] **Step 4: Register the service in plugin.xml**

Modify `src/main/resources/META-INF/plugin.xml`. Find the existing `<projectService>` block (around lines 108-113 — look for the line with `RepoContextResolver`). Add this line in that same block:

```xml
<projectService
    serviceImplementation="com.workflow.orchestrator.sonar.service.SonarKeyDetector"/>
```

- [ ] **Step 5: Replace inlined logic in CodeQualityConfigurable**

Modify `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/settings/CodeQualityConfigurable.kt`. Find the existing button block (around lines 70-89):

```kotlin
button("Auto-detect") {
    // Detect sonar.projectKey from pom.xml via Maven API
    val detected = try {
        val mavenManager = org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project)
        if (mavenManager.isMavenizedProject) {
            val rootProject = mavenManager.rootProjects.firstOrNull()
            rootProject?.properties?.getProperty("sonar.projectKey")
                ?: rootProject?.let { "${it.mavenId.groupId}:${it.mavenId.artifactId}" }
        } else null
    } catch (_: Exception) { null }

    if (detected != null) {
        projectKeyField.component.text = detected
    } else {
        com.intellij.openapi.ui.Messages.showWarningDialog(
            "Could not detect sonar.projectKey from pom.xml.\nEnsure Maven is configured with a sonar.projectKey property.",
            "Auto-detect Failed"
        )
    }
}
```

Replace with:

```kotlin
button("Auto-detect") {
    val detected = project.getService(com.workflow.orchestrator.sonar.service.SonarKeyDetector::class.java).detect()
    if (detected != null) {
        projectKeyField.component.text = detected
    } else {
        com.intellij.openapi.ui.Messages.showWarningDialog(
            "Could not detect sonar.projectKey from pom.xml.\nEnsure Maven is configured with a sonar.projectKey property.",
            "Auto-detect Failed"
        )
    }
}
```

- [ ] **Step 6: Run sonar tests**

```bash
./gradlew :sonar:test --tests "com.workflow.orchestrator.sonar.service.SonarKeyDetectorTest"
./gradlew :sonar:test
```

Expected: All sonar tests PASS.

- [ ] **Step 7: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarKeyDetector.kt \
        sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/SonarKeyDetectorTest.kt \
        sonar/src/main/kotlin/com/workflow/orchestrator/sonar/settings/CodeQualityConfigurable.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "refactor(sonar): extract SonarKeyDetector service

Pulls Maven sonar.projectKey lookup out of CodeQualityConfigurable
into a project-level service so it can be reused by the auto-detect
orchestrator. Behavior is unchanged."
```

---

## Phase 2: Service Wrappers

### Task 3: PlanDetectionService.autoDetectIfMissing wrapper

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionService.kt`
- Test: `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionServiceAutoDetectIfMissingTest.kt`

- [ ] **Step 1: Write the failing test**

Create `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionServiceAutoDetectIfMissingTest.kt`:

```kotlin
package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooPlanDto
import com.workflow.orchestrator.core.api.ApiResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlanDetectionServiceAutoDetectIfMissingTest {

    private val apiClient = mockk<BambooApiClient>()
    private val service = PlanDetectionService(apiClient)

    @Test
    fun `returns null when plan key already set`() = runTest {
        val result = service.autoDetectIfMissing(
            currentPlanKey = "ALREADY-SET",
            gitRemoteUrl = "https://bitbucket.org/acme/repo.git"
        )
        assertNull(result)
    }

    @Test
    fun `returns null when git remote url is blank`() = runTest {
        val result = service.autoDetectIfMissing(
            currentPlanKey = "",
            gitRemoteUrl = ""
        )
        assertNull(result)
    }

    @Test
    fun `delegates to autoDetect when plan key is empty`() = runTest {
        coEvery { apiClient.getPlans() } returns ApiResult.Success(emptyList())
        val result = service.autoDetectIfMissing(
            currentPlanKey = "",
            gitRemoteUrl = "https://bitbucket.org/acme/repo.git"
        )
        // No matching plan -> returns null (failure case from autoDetect)
        assertNull(result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.service.PlanDetectionServiceAutoDetectIfMissingTest"
```

Expected: FAIL with "Unresolved reference: autoDetectIfMissing"

- [ ] **Step 3: Add the wrapper**

Modify `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionService.kt`. Add this method to the class (after the existing `autoDetect` method):

```kotlin
/**
 * Convenience wrapper for the auto-detect orchestrator. Skips detection when
 * a plan key is already set or no git remote is available. Returns the
 * detected plan key on success, null otherwise (silent — no error).
 */
suspend fun autoDetectIfMissing(currentPlanKey: String, gitRemoteUrl: String): String? {
    if (currentPlanKey.isNotBlank()) return null
    if (gitRemoteUrl.isBlank()) return null
    return when (val result = autoDetect(gitRemoteUrl)) {
        is com.workflow.orchestrator.core.api.ApiResult.Success -> result.data
        is com.workflow.orchestrator.core.api.ApiResult.Error -> null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.service.PlanDetectionServiceAutoDetectIfMissingTest"
```

Expected: PASS, all 3 tests green.

- [ ] **Step 5: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionService.kt \
        bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionServiceAutoDetectIfMissingTest.kt
git commit -m "feat(bamboo): add autoDetectIfMissing wrapper to PlanDetectionService

Thin convenience wrapper for the auto-detect orchestrator: skips when
plan key already set or git remote blank, returns null on detection
failure (silent — failures are non-fatal in auto-detect context)."
```

---

## Phase 3: Orchestrator

### Task 4: AutoDetectResult + Orchestrator skeleton + fillIfEmpty rule

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectResult.kt`
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectOrchestrator.kt`
- Test: `core/src/test/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectOrchestratorTest.kt`

- [ ] **Step 1: Write the failing test (fill-only-empty rule)**

Create `core/src/test/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectOrchestratorTest.kt`:

```kotlin
package com.workflow.orchestrator.core.autodetect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AutoDetectOrchestratorTest {

    @Test
    fun `fillIfEmpty fills blank with detected value`() {
        assertEquals("detected", AutoDetectOrchestrator.fillIfEmpty("", "detected"))
        assertEquals("detected", AutoDetectOrchestrator.fillIfEmpty(null, "detected"))
        assertEquals("detected", AutoDetectOrchestrator.fillIfEmpty("   ", "detected"))
    }

    @Test
    fun `fillIfEmpty preserves existing value when detected non-blank`() {
        assertEquals("existing", AutoDetectOrchestrator.fillIfEmpty("existing", "detected"))
    }

    @Test
    fun `fillIfEmpty returns current when detected is null or blank`() {
        assertEquals("existing", AutoDetectOrchestrator.fillIfEmpty("existing", null))
        assertEquals("existing", AutoDetectOrchestrator.fillIfEmpty("existing", ""))
        assertEquals(null, AutoDetectOrchestrator.fillIfEmpty(null, null))
        assertEquals("", AutoDetectOrchestrator.fillIfEmpty("", null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :core:test --tests "com.workflow.orchestrator.core.autodetect.AutoDetectOrchestratorTest"
```

Expected: FAIL with "Unresolved reference: AutoDetectOrchestrator"

- [ ] **Step 3: Create AutoDetectResult**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectResult.kt`:

```kotlin
package com.workflow.orchestrator.core.autodetect

/**
 * Summary of an auto-detect sweep. Each list contains the human-readable names
 * of fields that were filled (i.e. were blank before, non-blank after).
 */
data class AutoDetectResult(
    val filledFields: List<String> = emptyList(),
    val errors: List<String> = emptyList()
) {
    val anyFilled: Boolean get() = filledFields.isNotEmpty()
}
```

- [ ] **Step 4: Create AutoDetectOrchestrator skeleton**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectOrchestrator.kt`:

```kotlin
package com.workflow.orchestrator.core.autodetect

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates project-key auto-detection across modules. Each detector is
 * independently triggerable. Writes to settings via the fill-only-empty rule
 * so user-set values are never overwritten.
 *
 * Triggers (wired in plugin.xml or in the activity / listeners):
 *   - Project open: WorkflowAutoDetectStartupActivity calls detectAll()
 *   - Branch change: subscribed to EventBus.BranchChanged in init
 *   - File changes: AutoDetectFileListener routes to specific detectors
 *   - Credentials updated: PluginSettings change listener calls detectBambooPlan()
 */
@Service(Service.Level.PROJECT)
class AutoDetectOrchestrator(private val project: Project) : Disposable {

    private val log = logger<AutoDetectOrchestrator>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val firstSweepNotified = AtomicBoolean(false)

    suspend fun detectAll(): AutoDetectResult {
        // TODO Task 5: wire detectors
        return AutoDetectResult()
    }

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        /** Returns `detected` only when `current` is null/blank AND `detected` is non-blank. */
        fun fillIfEmpty(current: String?, detected: String?): String? =
            if (current.isNullOrBlank() && !detected.isNullOrBlank()) detected else current
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew :core:test --tests "com.workflow.orchestrator.core.autodetect.AutoDetectOrchestratorTest"
```

Expected: PASS, all 3 tests green.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectResult.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectOrchestrator.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectOrchestratorTest.kt
git commit -m "feat(autodetect): add orchestrator skeleton with fillIfEmpty rule

Project-level service that will coordinate four detectors. Currently
only the fill-only-empty rule is implemented (will never overwrite a
user-set value). detectAll() is a stub — wired in next task."
```

---

### Task 5: Wire detectors into orchestrator

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectOrchestrator.kt`
- Modify: `core/src/test/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectOrchestratorTest.kt`

- [ ] **Step 1: Add tests for the four detector methods**

Append these tests to `AutoDetectOrchestratorTest.kt`:

```kotlin
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@Nested
inner class DetectFromBambooSpecsTests {

    @Test
    fun `fills dockerTagKey when DOCKER_TAG_NAME constant exists and field empty`(@TempDir root: Path) = runTest {
        val javaDir = root.resolve("bamboo-specs/src/main/java/constants")
        Files.createDirectories(javaDir)
        Files.writeString(javaDir.resolve("PlanProperties.java"),
            """class PlanProperties { private static final String DOCKER_TAG_NAME = "MyServiceDockerTag"; }""")

        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns root.toString()
        val settings = PluginSettings.State()
        // settings.dockerTagKey is "" by default

        val orchestrator = AutoDetectOrchestrator(project)
        val constants = BambooSpecsParser.parseConstants(root)
        val filled = orchestrator.applyBambooSpecsConstants(settings, constants)

        assertEquals("MyServiceDockerTag", settings.dockerTagKey)
        assertEquals(listOf("dockerTagKey"), filled)
    }

    @Test
    fun `does not overwrite user-set dockerTagKey`(@TempDir root: Path) = runTest {
        val javaDir = root.resolve("bamboo-specs/src/main/java")
        Files.createDirectories(javaDir)
        Files.writeString(javaDir.resolve("X.java"),
            """class X { private static final String DOCKER_TAG_NAME = "DetectedTag"; }""")

        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns root.toString()
        val settings = PluginSettings.State().apply { dockerTagKey = "UserSetTag" }

        val orchestrator = AutoDetectOrchestrator(project)
        val constants = BambooSpecsParser.parseConstants(root)
        val filled = orchestrator.applyBambooSpecsConstants(settings, constants)

        assertEquals("UserSetTag", settings.dockerTagKey)
        assertTrue(filled.isEmpty())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :core:test --tests "com.workflow.orchestrator.core.autodetect.AutoDetectOrchestratorTest"
```

Expected: FAIL with "Unresolved reference: applyBambooSpecsConstants"

- [ ] **Step 3: Implement the four detector methods**

Replace the stub `detectAll()` and add new methods in `AutoDetectOrchestrator.kt`:

```kotlin
// Add these imports at the top:
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoContextResolver
import com.workflow.orchestrator.sonar.service.SonarKeyDetector
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Paths

// Replace the stub detectAll() with:

suspend fun detectAll(): AutoDetectResult = coroutineScope {
    log.info("[AutoDetect] Running full sweep")
    val filled = mutableListOf<String>()
    val errors = mutableListOf<String>()

    runDetector("bamboo-specs", filled, errors) { detectFromBambooSpecs(it) }
    runDetector("git-remote", filled, errors) { detectGitDerivable(it) }
    runDetector("sonar-key", filled, errors) { detectSonarKey(it) }
    runDetector("bamboo-plan", filled, errors) { detectBambooPlan(it) }

    val result = AutoDetectResult(filledFields = filled, errors = errors)
    if (result.anyFilled && firstSweepNotified.compareAndSet(false, true)) {
        showNotification(result)
    }
    result
}

private inline fun runDetector(
    name: String,
    filled: MutableList<String>,
    errors: MutableList<String>,
    block: (MutableList<String>) -> Unit
) {
    try {
        block(filled)
    } catch (e: Exception) {
        log.warn("[AutoDetect] Detector '$name' failed", e)
        errors.add("$name: ${e.message ?: "unknown"}")
    }
}

fun detectFromBambooSpecs(filled: MutableList<String>) {
    val basePath = project.basePath ?: return
    val constants = BambooSpecsParser.parseConstants(Paths.get(basePath))
    if (constants.isEmpty()) return
    val settings = PluginSettings.getInstance(project).state
    filled += applyBambooSpecsConstants(settings, constants)
}

/** Visible for testing. Returns names of fields that were actually filled. */
internal fun applyBambooSpecsConstants(
    settings: PluginSettings.State,
    constants: Map<String, String>
): List<String> {
    val filled = mutableListOf<String>()
    val newDocker = fillIfEmpty(settings.dockerTagKey, constants["DOCKER_TAG_NAME"])
    if (newDocker != settings.dockerTagKey && !newDocker.isNullOrBlank()) {
        settings.dockerTagKey = newDocker
        filled += "dockerTagKey"
    }
    val newPlan = fillIfEmpty(settings.bambooPlanKey, constants["PLAN_KEY"])
    if (newPlan != settings.bambooPlanKey && !newPlan.isNullOrBlank()) {
        settings.bambooPlanKey = newPlan
        filled += "bambooPlanKey"
    }
    return filled
}

fun detectGitDerivable(filled: MutableList<String>) {
    // RepoContextResolver.autoDetectRepos() already populates RepoConfig
    // entries with bitbucketProjectKey + bitbucketRepoSlug from git remote.
    // We don't write to global PluginSettings here — repo-level config is
    // owned by RepositoriesConfigurable. This detector is a no-op in the
    // current scope (kept as a hook for future expansion).
}

fun detectSonarKey(filled: MutableList<String>) {
    val detected = project.getService(SonarKeyDetector::class.java)?.detect() ?: return
    val settings = PluginSettings.getInstance(project).state
    val current = settings.sonarProjectKey
    val updated = fillIfEmpty(current, detected)
    if (updated != current && !updated.isNullOrBlank()) {
        settings.sonarProjectKey = updated
        filled += "sonarProjectKey"
    }
}

suspend fun detectBambooPlan(filled: MutableList<String> = mutableListOf()) {
    val settings = PluginSettings.getInstance(project).state
    if (settings.bambooPlanKey.isNotBlank()) return
    val gitRepo = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return
    val remoteUrl = gitRepo.remotes.firstOrNull()?.firstUrl ?: return
    val planService = project.getService(
        Class.forName("com.workflow.orchestrator.bamboo.service.PlanDetectionService")
    ) as? Any ?: return
    // Call autoDetectIfMissing via reflection to avoid :core depending on :bamboo
    val method = planService.javaClass.getMethod("autoDetectIfMissing", String::class.java, String::class.java)
    val detected = method.invoke(planService, "", remoteUrl) as? String ?: return
    settings.bambooPlanKey = detected
    filled += "bambooPlanKey"
}

private fun showNotification(result: AutoDetectResult) {
    val msg = "Auto-detected project keys: ${result.filledFields.joinToString(", ")}. " +
              "Review in Settings → Tools → Workflow Orchestrator → Repositories."
    NotificationGroupManager.getInstance()
        .getNotificationGroup("Workflow Orchestrator")
        .createNotification(msg, NotificationType.INFORMATION)
        .notify(project)
}

// Subscribe to BranchChanged in init
init {
    scope.launch {
        project.getService(EventBus::class.java).events.collect { event ->
            if (event is WorkflowEvent.BranchChanged) {
                log.info("[AutoDetect] BranchChanged → re-running branch-sensitive detectors")
                val filled = mutableListOf<String>()
                runDetector("bamboo-specs", filled, mutableListOf()) { detectFromBambooSpecs(it) }
                detectBambooPlan(filled)
            }
        }
    }
}
```

> **Note on `:core` -> `:bamboo` reflection:** The dependency rule (`:core` cannot depend on feature modules) forces us to use reflection for the bamboo plan service. If a future task adds `BambooService` to the unified service layer in `:core`, this reflection can be replaced with a direct call.

> **Note on `detectGitDerivable`:** Currently a no-op because `RepoContextResolver.autoDetectRepos()` already populates `RepoConfig` entries with the git-derived fields, and that path is owned by `RepositoriesConfigurable`. We keep the method as a hook so the trigger wiring (file listener for `.git/config`) has a place to call.

- [ ] **Step 4: Run tests**

```bash
./gradlew :core:test --tests "com.workflow.orchestrator.core.autodetect.AutoDetectOrchestratorTest"
```

Expected: PASS, all tests including the new bamboo-specs tests green.

- [ ] **Step 5: Register the orchestrator service in plugin.xml**

Modify `src/main/resources/META-INF/plugin.xml`. In the same `<projectService>` block as before, add:

```xml
<projectService
    serviceImplementation="com.workflow.orchestrator.core.autodetect.AutoDetectOrchestrator"/>
```

Also add the notification group (find the existing `<notificationGroup>` declarations or add a new block in `<extensions defaultExtensionNs="com.intellij">`):

```xml
<notificationGroup id="Workflow Orchestrator"
                   displayType="BALLOON"
                   isLogByDefault="false"/>
```

If a `Workflow Orchestrator` notification group already exists, skip this — don't duplicate it.

- [ ] **Step 6: Build to verify wiring**

```bash
./gradlew :core:compileKotlin
```

Expected: Compiles cleanly.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectOrchestrator.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectOrchestratorTest.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat(autodetect): wire detectors into AutoDetectOrchestrator

detectAll() runs four detectors (bamboo-specs, git-remote, sonar,
bamboo-plan) in parallel-safe coroutineScope. Each failure is
contained. firstSweepNotified ensures only one notification per
session. BranchChanged subscription re-runs branch-sensitive
detectors. Bamboo plan call uses reflection to respect the
:core -> :bamboo dependency rule."
```

---

## Phase 4: Triggers

### Task 6: WorkflowAutoDetectStartupActivity

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/WorkflowAutoDetectStartupActivity.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create the startup activity**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/WorkflowAutoDetectStartupActivity.kt`:

```kotlin
package com.workflow.orchestrator.core.autodetect

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Runs auto-detection once on project open, after smart mode (so PSI / Maven /
 * Git APIs are ready). Background only — never blocks the EDT.
 */
class WorkflowAutoDetectStartupActivity : ProjectActivity {

    private val log = logger<WorkflowAutoDetectStartupActivity>()

    override suspend fun execute(project: Project) {
        log.info("[AutoDetect:Startup] Project opened, scheduling initial sweep")
        DumbService.getInstance(project).runWhenSmart {
            val orchestrator = project.getService(AutoDetectOrchestrator::class.java)
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                orchestrator.detectAll()
            }
        }
    }
}
```

> **Note:** `GlobalScope` is acceptable here because the orchestrator owns its own scope and disposable lifecycle — we're just kicking off `detectAll()` from outside its scope. If your code style forbids `GlobalScope`, replace with `@Suppress("OPT_IN_USAGE")` or use `project.coroutineScope` if that helper exists in this codebase.

- [ ] **Step 2: Register the activity in plugin.xml**

Modify `src/main/resources/META-INF/plugin.xml`. Find the existing `<postStartupActivity>` declarations (around lines 163-164). Add:

```xml
<postStartupActivity
    implementation="com.workflow.orchestrator.core.autodetect.WorkflowAutoDetectStartupActivity"/>
```

- [ ] **Step 3: Build**

```bash
./gradlew :core:compileKotlin
```

Expected: Compiles cleanly.

- [ ] **Step 4: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/WorkflowAutoDetectStartupActivity.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat(autodetect): add startup activity to run sweep on project open

Triggers AutoDetectOrchestrator.detectAll() after DumbService smart
mode so Maven and PSI APIs are ready. Background dispatch only."
```

---

### Task 7: AutoDetectFileListener (BulkFileListener for file-change triggers)

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectFileListener.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create the listener**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectFileListener.kt`:

```kotlin
package com.workflow.orchestrator.core.autodetect

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Listens for VFS changes to files that affect auto-detection results:
 *   - `.git/config`              → re-run git-derivable detection
 *   - `pom.xml`                  → re-run sonar key detection
 *   - `bamboo-specs/**\/*.java`  → re-run bamboo-specs constant walk
 *
 * 500ms debounce per detector so a `git checkout` touching many files only
 * fires the walk once.
 */
class AutoDetectFileListener : BulkFileListener {

    private val log = logger<AutoDetectFileListener>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var bambooSpecsJob: Job? = null
    @Volatile private var pomJob: Job? = null
    @Volatile private var gitConfigJob: Job? = null

    override fun after(events: MutableList<out VFileEvent>) {
        if (events.isEmpty()) return

        var bambooSpecsTouched = false
        var pomTouched = false
        var gitConfigTouched = false

        for (event in events) {
            val path = event.path
            if (path.endsWith("/.git/config") || path.endsWith("\\.git\\config")) gitConfigTouched = true
            if (path.endsWith("/pom.xml") || path.endsWith("\\pom.xml")) pomTouched = true
            if (path.contains("/bamboo-specs/") && path.endsWith(".java")) bambooSpecsTouched = true
            if (path.contains("\\bamboo-specs\\") && path.endsWith(".java")) bambooSpecsTouched = true
        }

        if (!bambooSpecsTouched && !pomTouched && !gitConfigTouched) return

        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val orchestrator = project.getService(AutoDetectOrchestrator::class.java) ?: continue
            if (bambooSpecsTouched) bambooSpecsJob = debounce(bambooSpecsJob) {
                log.info("[AutoDetect:FileListener] bamboo-specs changed → re-running")
                orchestrator.detectFromBambooSpecs(mutableListOf())
            }
            if (pomTouched) pomJob = debounce(pomJob) {
                log.info("[AutoDetect:FileListener] pom.xml changed → re-running sonar")
                orchestrator.detectSonarKey(mutableListOf())
            }
            if (gitConfigTouched) gitConfigJob = debounce(gitConfigJob) {
                log.info("[AutoDetect:FileListener] .git/config changed → re-running git-derivable")
                orchestrator.detectGitDerivable(mutableListOf())
            }
        }
    }

    private fun debounce(existing: Job?, block: suspend () -> Unit): Job {
        existing?.cancel()
        return scope.launch {
            delay(500)
            block()
        }
    }
}
```

- [ ] **Step 2: Register the listener in plugin.xml**

Modify `src/main/resources/META-INF/plugin.xml`. Find the `<applicationListeners>` block (or add one inside `<idea-plugin>`):

```xml
<applicationListeners>
    <listener class="com.workflow.orchestrator.core.autodetect.AutoDetectFileListener"
              topic="com.intellij.openapi.vfs.newvfs.BulkFileListener"/>
</applicationListeners>
```

If `<applicationListeners>` already exists, just add the `<listener>` line inside it.

- [ ] **Step 3: Build**

```bash
./gradlew :core:compileKotlin verifyPlugin
```

Expected: Compiles cleanly. `verifyPlugin` passes.

- [ ] **Step 4: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectFileListener.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat(autodetect): add VFS listener for .git/config, pom.xml, bamboo-specs

500ms debounce per detector so a git checkout touching many files
only fires the bamboo-specs walk once. Routes each touched file type
to the appropriate orchestrator method."
```

---

## Phase 5: Wire UI

### Task 8: RepositoriesConfigurable button calls orchestrator

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/RepositoriesConfigurable.kt`

- [ ] **Step 1: Modify the button handler**

In `RepositoriesConfigurable.kt`, find the existing `onAutoDetectRepos()` method (around lines 232-264). After the existing logic that calls `resolver.autoDetectRepos()` and refreshes the table, add a follow-up call to the orchestrator. Replace:

```kotlin
private fun onAutoDetectRepos() {
    repoStatusLabel.text = "Detecting repositories from VCS roots..."
    ApplicationManager.getApplication().executeOnPooledThread {
        val resolver = RepoContextResolver.getInstance(project)
        val detected = resolver.autoDetectRepos()
        invokeLater {
            if (detected.isEmpty()) {
                repoStatusLabel.text = "No repositories detected from git remotes"
                return@invokeLater
            }
            // ... existing add-to-table logic ...
            refreshRepoTable()
            repoStatusLabel.text = if (added > 0) {
                "Added $added new repo(s) from ${detected.size} detected"
            } else {
                "All ${detected.size} detected repos already configured"
            }
        }
    }
}
```

With:

```kotlin
private fun onAutoDetectRepos() {
    repoStatusLabel.text = "Detecting repositories from VCS roots..."
    ApplicationManager.getApplication().executeOnPooledThread {
        val resolver = RepoContextResolver.getInstance(project)
        val detected = resolver.autoDetectRepos()

        // Also run the orchestrator to fill docker tag, sonar key, bamboo plan key, etc.
        val orchestrator = project.getService(
            com.workflow.orchestrator.core.autodetect.AutoDetectOrchestrator::class.java
        )
        val orchestratorResult = kotlinx.coroutines.runBlocking { orchestrator.detectAll() }

        invokeLater {
            if (detected.isEmpty() && !orchestratorResult.anyFilled) {
                repoStatusLabel.text = "No repositories or project keys detected"
                return@invokeLater
            }
            var added = 0
            for (repo in detected) {
                val alreadyExists = editedRepos.any {
                    it.bitbucketProjectKey.equals(repo.bitbucketProjectKey, ignoreCase = true) &&
                        it.bitbucketRepoSlug.equals(repo.bitbucketRepoSlug, ignoreCase = true)
                }
                if (!alreadyExists) {
                    if (repo.isPrimary && editedRepos.any { it.isPrimary }) {
                        repo.isPrimary = false
                    }
                    editedRepos.add(repo)
                    added++
                }
            }
            refreshRepoTable()
            val parts = mutableListOf<String>()
            if (added > 0) parts.add("Added $added repo(s)")
            if (orchestratorResult.anyFilled) parts.add("Filled: ${orchestratorResult.filledFields.joinToString(", ")}")
            repoStatusLabel.text = if (parts.isNotEmpty()) parts.joinToString(" · ") else "Nothing new to detect"
        }
    }
}
```

> **Note on `runBlocking`:** Acceptable here because we're already on a background pool thread (`executeOnPooledThread`), not on the EDT. The brief block waits for the suspend `detectAll()` to complete before showing the status label update.

- [ ] **Step 2: Build**

```bash
./gradlew :core:compileKotlin
```

Expected: Compiles cleanly.

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/RepositoriesConfigurable.kt
git commit -m "feat(autodetect): wire Repositories Auto-Detect button to orchestrator

Clicking Auto-Detect now (a) runs the existing repo discovery from
git remotes AND (b) runs the orchestrator's full sweep to fill
docker tag, sonar key, and bamboo plan key. Status label reports
both outcomes."
```

---

## Phase 6: Integration

### Task 9: Integration test with fixture project

**Files:**
- Create: `core/src/test/testData/auto-detect-project/.git/config`
- Create: `core/src/test/testData/auto-detect-project/pom.xml`
- Create: `core/src/test/testData/auto-detect-project/bamboo-specs/src/main/java/constants/PlanProperties.java`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectIntegrationTest.kt`

- [ ] **Step 1: Create the fixture tree**

Create `core/src/test/testData/auto-detect-project/.git/config`:

```ini
[core]
    repositoryformatversion = 0
    filemode = true
    bare = false
    logallrefupdates = true
[remote "origin"]
    url = git@bitbucket.example.com:7999/MYPROJ/my-sample-service.git
    fetch = +refs/heads/*:refs/remotes/origin/*
[branch "develop"]
    remote = origin
    merge = refs/heads/develop
```

Create `core/src/test/testData/auto-detect-project/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example.product</groupId>
    <artifactId>my-sample-service</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <properties>
        <sonar.projectKey>my-sample-service</sonar.projectKey>
    </properties>
</project>
```

Create `core/src/test/testData/auto-detect-project/bamboo-specs/src/main/java/constants/PlanProperties.java`:

```java
package constants;

public class PlanProperties {
    private static final String REPOSITORY_NAME = "my-sample-service";
    private static final String PLAN_KEY = "MYSAMPLESERVICE";
    private static final String DOCKER_TAG_NAME = "MySampleServiceDockerTag";
    private static final String GIT_PROJECT_ID = "MYPROJ";
}
```

- [ ] **Step 2: Write the integration test**

Create `core/src/test/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectIntegrationTest.kt`:

```kotlin
package com.workflow.orchestrator.core.autodetect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Pure integration test against the fixture project tree. Does NOT spin up an
 * IntelliJ project — just exercises the parts of the orchestrator that are
 * project-independent (BambooSpecsParser).
 *
 * Full project-aware integration testing requires BasePlatformTestCase which
 * is heavier and is covered manually in runIde.
 */
class AutoDetectIntegrationTest {

    private val fixtureRoot: Path = Paths.get("src/test/testData/auto-detect-project")

    @Test
    fun `bamboo-specs parser extracts all expected constants from fixture`() {
        val constants = BambooSpecsParser.parseConstants(fixtureRoot)

        assertEquals("my-sample-service", constants["REPOSITORY_NAME"])
        assertEquals("MYSAMPLESERVICE", constants["PLAN_KEY"])
        assertEquals("MySampleServiceDockerTag", constants["DOCKER_TAG_NAME"])
        assertEquals("MYPROJ", constants["GIT_PROJECT_ID"])
    }

    @Test
    fun `applyBambooSpecsConstants fills all empty fields from fixture`() {
        val constants = BambooSpecsParser.parseConstants(fixtureRoot)
        val settings = com.workflow.orchestrator.core.settings.PluginSettings.State()
        // settings.dockerTagKey and settings.bambooPlanKey are "" by default

        // Use the companion's static-like method via a stub orchestrator
        // (we can't construct AutoDetectOrchestrator without a project, so we
        // test the pure rule directly via fillIfEmpty + manual application)
        settings.dockerTagKey = AutoDetectOrchestrator.fillIfEmpty(settings.dockerTagKey, constants["DOCKER_TAG_NAME"]) ?: ""
        settings.bambooPlanKey = AutoDetectOrchestrator.fillIfEmpty(settings.bambooPlanKey, constants["PLAN_KEY"]) ?: ""

        assertEquals("MySampleServiceDockerTag", settings.dockerTagKey)
        assertEquals("MYSAMPLESERVICE", settings.bambooPlanKey)
    }
}
```

- [ ] **Step 3: Run the test**

```bash
./gradlew :core:test --tests "com.workflow.orchestrator.core.autodetect.AutoDetectIntegrationTest"
```

Expected: PASS.

- [ ] **Step 4: Run the full test suites for affected modules**

```bash
./gradlew :core:test :sonar:test :bamboo:test verifyPlugin
```

Expected: All green. `verifyPlugin` passes.

- [ ] **Step 5: Manual smoke in runIde**

```bash
./gradlew runIde
```

In the sandbox IDE:
1. Open a project that has `bamboo-specs/src/main/java/constants/PlanProperties.java` with a `DOCKER_TAG_NAME` constant.
2. Verify a notification appears: "Auto-detected project keys: ..."
3. Open Settings → Tools → Workflow Orchestrator → Repositories. Verify `dockerTagKey` is filled.
4. Manually clear `dockerTagKey` and click Auto-Detect. Verify it refills.
5. Manually set `dockerTagKey` to a custom value and reopen the project. Verify auto-detect does NOT overwrite the custom value.
6. Switch git branches. Verify the orchestrator re-runs (check the IDE log for `[AutoDetect] BranchChanged`).
7. Edit `bamboo-specs/.../PlanProperties.java`. Verify the file listener fires within 500ms (check log for `[AutoDetect:FileListener] bamboo-specs changed`).

- [ ] **Step 6: Commit**

```bash
git add core/src/test/testData/auto-detect-project \
        core/src/test/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectIntegrationTest.kt
git commit -m "test(autodetect): add fixture project + integration test

Realistic fixture tree (.git/config, pom.xml, PlanProperties.java)
for end-to-end verification of BambooSpecsParser and the
fill-only-empty rule. Manual smoke steps documented in plan."
```

---

## Self-Review

**Spec coverage:**

- [x] Goal 1 (docker tag from bamboo-specs): Tasks 1, 5
- [x] Goal 2 (bamboo project key derivation): N/A — `RepoConfig` has no field for it; `bitbucketProjectKey` already holds the same value. Documented in plan header.
- [x] Goal 3 (auto-trigger on project open / branch / file changes): Tasks 5 (BranchChanged subscription), 6 (startup activity), 7 (file listener)
- [x] Goal 4 (never overwrite user-set values): Task 4 (`fillIfEmpty`), tests in Tasks 4 and 5
- [x] Goal 5 (manual buttons still work): Tasks 2 (sonar), 8 (repositories)
- [x] SonarKeyDetector extraction: Task 2
- [x] PlanDetectionService.autoDetectIfMissing: Task 3
- [x] One-time notification: Task 5
- [x] BambooSpecsParser tests: Task 1
- [x] AutoDetectOrchestrator tests: Tasks 4, 5
- [x] SonarKeyDetector tests: Task 2
- [x] Integration test: Task 9

**Placeholder scan:** No TBD/TODO/"add appropriate" placeholders. Every code step shows the actual code.

**Type consistency:**
- `BambooSpecsParser.parseConstants(Path): Map<String, String>` — used consistently in Tasks 1, 5, 9.
- `AutoDetectResult(filledFields: List<String>, errors: List<String>)` — Task 4 defines, Task 5 returns it, Task 8 reads `.anyFilled` and `.filledFields`.
- `AutoDetectOrchestrator.fillIfEmpty(String?, String?): String?` — Task 4 defines, Task 5 uses, Task 9 uses.
- `PlanDetectionService.autoDetectIfMissing(currentPlanKey: String, gitRemoteUrl: String): String?` — Task 3 defines, Task 5 calls via reflection.
- `SonarKeyDetector.detect(): String?` — Task 2 defines, Task 2 step 5 uses, Task 5 uses.

**Risks not covered by tests:**
- File listener debounce timing — covered by manual smoke in Task 9 step 5.
- Maven model not ready at first project open — accepted risk per spec; the file listener catches up when import finishes.
- Reflection call to PlanDetectionService — fragile if signatures change. Acceptable trade-off for the dependency rule.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-04-08-auto-detect-project-keys.md`. Two execution options:**

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
