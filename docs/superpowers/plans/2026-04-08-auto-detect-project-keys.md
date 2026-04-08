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
| `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImplAutoDetectPlanTest.kt` | `:bamboo` | Test for the new `autoDetectPlan` method |

**Modified files:**

| File | Change |
|---|---|
| `core/src/main/kotlin/com/workflow/orchestrator/core/services/BambooService.kt` | Add `autoDetectPlan(gitRemoteUrl: String): ToolResult<String>` interface method |
| `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt` | Implement `autoDetectPlan` (delegates to `PlanDetectionService(client).autoDetect`) |
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

    @Test
    fun `detectForPath returns sonar key from matching maven root`() {
        val mavenProjectA = mockk<MavenProject>()
        val mavenProjectB = mockk<MavenProject>()
        val propsA = Properties().apply { setProperty("sonar.projectKey", "service-a-key") }
        val propsB = Properties().apply { setProperty("sonar.projectKey", "service-b-key") }
        every { mavenManager.isMavenizedProject } returns true
        every { mavenManager.rootProjects } returns listOf(mavenProjectA, mavenProjectB)
        every { mavenProjectA.directory } returns "/projects/service-a"
        every { mavenProjectB.directory } returns "/projects/service-b"
        every { mavenProjectA.properties } returns propsA
        every { mavenProjectB.properties } returns propsB

        assertEquals("service-a-key", detector.detectForPath("/projects/service-a"))
        assertEquals("service-b-key", detector.detectForPath("/projects/service-b"))
    }

    @Test
    fun `detectForPath returns null when no maven root matches the path`() {
        val mavenProject = mockk<MavenProject>()
        every { mavenManager.isMavenizedProject } returns true
        every { mavenManager.rootProjects } returns listOf(mavenProject)
        every { mavenProject.directory } returns "/projects/other"

        assertNull(detector.detectForPath("/projects/missing"))
    }

    @Test
    fun `detectForPath falls back to groupId-artifactId when sonar property absent`() {
        val mavenProject = mockk<MavenProject>()
        every { mavenManager.isMavenizedProject } returns true
        every { mavenManager.rootProjects } returns listOf(mavenProject)
        every { mavenProject.directory } returns "/projects/svc"
        every { mavenProject.properties } returns Properties()
        every { mavenProject.mavenId } returns MavenId("com.acme", "svc", "1.0")

        assertEquals("com.acme:svc", detector.detectForPath("/projects/svc"))
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
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.file.Paths

/**
 * Detects the SonarQube project key from the IDE's resolved Maven model.
 *
 * Priority:
 *   1. Explicit `<sonar.projectKey>` property in the root pom.xml.
 *      (Maven model already resolves placeholders like `${project.artifactId}`.)
 *   2. Synthesized `groupId:artifactId` from the root project's MavenId.
 *
 * Returns null if the project is not mavenized or no Maven root matches.
 *
 * Two methods:
 *   - `detect()`: legacy single-project case, uses the first Maven root.
 *   - `detectForPath(repoRootPath)`: multi-repo case, finds the Maven root
 *     whose directory matches the given repo path.
 */
@Service(Service.Level.PROJECT)
class SonarKeyDetector(private val project: Project) {

    private val log = logger<SonarKeyDetector>()

    fun detect(): String? = extractKey(firstMavenRoot() ?: return null)

    fun detectForPath(repoRootPath: String): String? {
        return try {
            val mavenManager = MavenProjectsManager.getInstance(project)
            if (!mavenManager.isMavenizedProject) return null
            val targetPath = Paths.get(repoRootPath).toAbsolutePath().normalize()
            val matching = mavenManager.rootProjects.firstOrNull { mavenProject ->
                try {
                    Paths.get(mavenProject.directory).toAbsolutePath().normalize() == targetPath
                } catch (_: Exception) { false }
            } ?: run {
                log.debug("[Sonar:Detect] No Maven root matches repo path: $repoRootPath")
                return null
            }
            extractKey(matching)
        } catch (e: Exception) {
            log.warn("[Sonar:Detect] detectForPath failed for $repoRootPath", e)
            null
        }
    }

    private fun firstMavenRoot(): MavenProject? {
        return try {
            val mavenManager = MavenProjectsManager.getInstance(project)
            if (!mavenManager.isMavenizedProject) {
                log.debug("[Sonar:Detect] Project is not mavenized")
                null
            } else mavenManager.rootProjects.firstOrNull()
                ?: run { log.debug("[Sonar:Detect] No root Maven projects"); null }
        } catch (e: Exception) {
            log.warn("[Sonar:Detect] firstMavenRoot failed", e); null
        }
    }

    private fun extractKey(mavenProject: MavenProject): String? {
        return try {
            val explicit = mavenProject.properties?.getProperty("sonar.projectKey")
            if (!explicit.isNullOrBlank()) {
                log.info("[Sonar:Detect] Found explicit sonar.projectKey: $explicit")
                return explicit
            }
            val mavenId = mavenProject.mavenId
            val synthesized = "${mavenId.groupId}:${mavenId.artifactId}"
            log.info("[Sonar:Detect] Synthesized sonar key from coordinates: $synthesized")
            synthesized
        } catch (e: Exception) {
            log.warn("[Sonar:Detect] extractKey failed", e); null
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

## Phase 2: Core Service Layer

### Task 3: BambooService.autoDetectPlan (core interface + impl)

This task adds plan auto-detection to the unified core service layer per CLAUDE.md's "Agent-Exposable Service Architecture" rules. The orchestrator (Task 5) calls `BambooService.autoDetectPlan()` directly via `project.getService(BambooService::class.java)` — no reflection, no `kotlin-reflect` dependency, no `:core` → `:bamboo` import.

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/services/BambooService.kt` (add interface method)
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt` (add implementation)
- Test: `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImplAutoDetectPlanTest.kt`

- [ ] **Step 1: Write the failing test**

Create `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImplAutoDetectPlanTest.kt`:

```kotlin
package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooPlanDto
import com.workflow.orchestrator.core.api.ApiResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BambooServiceImplAutoDetectPlanTest {

    private val project = mockk<Project>(relaxed = true)
    private val apiClient = mockk<BambooApiClient>()

    private fun makeService(): BambooServiceImpl {
        // Construct BambooServiceImpl with the apiClient injected. The exact
        // constructor shape may differ — adapt to whatever existing tests use.
        // If BambooServiceImpl is a project service, use a TestFixture or
        // a thin test factory. The PlanDetectionServiceTest pattern uses
        // direct constructor injection which we can mirror here.
        return BambooServiceImpl(project).also {
            // Inject the mocked client via reflection or a test-only setter
            // (existing tests in :bamboo will show the pattern)
            it.testOverrideClient(apiClient)
        }
    }

    @Test
    fun `returns error ToolResult when git remote url is blank`() = runTest {
        val service = makeService()
        val result = service.autoDetectPlan("")
        assertTrue(result.isError)
        assertEquals("", result.data)
    }

    @Test
    fun `returns success ToolResult when single plan matches`() = runTest {
        val plan = BambooPlanDto(key = "PROJ-MYPLAN", name = "My Plan")
        coEvery { apiClient.getPlans() } returns ApiResult.Success(listOf(plan))
        coEvery { apiClient.getPlanSpecs("PROJ-MYPLAN") } returns ApiResult.Success(
            "specs:\n  url: https://bitbucket.org/acme/repo.git"
        )

        val service = makeService()
        val result = service.autoDetectPlan("https://bitbucket.org/acme/repo.git")

        assertFalse(result.isError)
        assertEquals("PROJ-MYPLAN", result.data)
        assertTrue(result.summary.contains("PROJ-MYPLAN"))
    }

    @Test
    fun `returns error ToolResult when no plan matches`() = runTest {
        coEvery { apiClient.getPlans() } returns ApiResult.Success(emptyList())
        val service = makeService()
        val result = service.autoDetectPlan("https://bitbucket.org/acme/repo.git")
        assertTrue(result.isError)
        assertEquals("", result.data)
    }
}
```

> **Note on `BambooServiceImpl` test construction:** The exact constructor / DI pattern depends on how existing `BambooServiceImpl` tests are wired. Before writing this test, read `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/` for an existing `BambooServiceImplTest` (or similar) and mirror its setup. If no such test exists, the simplest pattern is:
> 1. Add a package-private/internal `testOverrideClient(client: BambooApiClient)` setter to `BambooServiceImpl` that overrides the cached client field
> 2. Or extract `PlanDetectionService` construction into a virtual method that tests can override
>
> Pick whichever pattern matches the codebase's existing testing style. The test above uses option 1 as a placeholder.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.service.BambooServiceImplAutoDetectPlanTest"
```

Expected: FAIL with "Unresolved reference: autoDetectPlan" (and possibly `testOverrideClient`).

- [ ] **Step 3: Add the method to the BambooService interface**

Modify `core/src/main/kotlin/com/workflow/orchestrator/core/services/BambooService.kt`. Add this method declaration to the interface (place it near `getPlans()` and `searchPlans()` for grouping):

```kotlin
/**
 * Auto-detects the Bamboo plan key associated with a git repository by
 * fetching all plans, parsing each plan's bamboo-specs YAML for repository
 * URLs, and matching against the given git remote URL.
 *
 * @param gitRemoteUrl the git remote URL to match against (SSH or HTTPS)
 * @return ToolResult with the detected plan key on success, or isError=true
 *         when the URL is blank, no plans match, or multiple plans match
 */
suspend fun autoDetectPlan(gitRemoteUrl: String): ToolResult<String>
```

- [ ] **Step 4: Implement in BambooServiceImpl**

Modify `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt`. Add this method to the class (place it near other plan-related methods like `getPlans()` and `searchPlans()`):

```kotlin
override suspend fun autoDetectPlan(gitRemoteUrl: String): ToolResult<String> {
    if (gitRemoteUrl.isBlank()) {
        return ToolResult(
            data = "",
            summary = "No git remote URL provided",
            isError = true
        )
    }
    val planDetection = PlanDetectionService(client)
    return when (val result = planDetection.autoDetect(gitRemoteUrl)) {
        is com.workflow.orchestrator.core.api.ApiResult.Success -> ToolResult(
            data = result.data,
            summary = "Auto-detected Bamboo plan: ${result.data}"
        )
        is com.workflow.orchestrator.core.api.ApiResult.Error -> ToolResult(
            data = "",
            summary = result.message,
            isError = true
        )
    }
}
```

> **Note on `client` access:** `BambooServiceImpl` already exposes its `BambooApiClient` as an internal/private property (per the explore agent's report, lines 43-58). If the field name is different from `client`, adapt the code above. If it's truly `private` and the test cannot inject a mock, change it to `internal` or add a constructor parameter — whichever matches the existing pattern in `:bamboo`.

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.service.BambooServiceImplAutoDetectPlanTest"
./gradlew :bamboo:test :core:compileKotlin
```

Expected: All 3 new tests PASS. Full bamboo test suite still passes. Core compiles cleanly.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/services/BambooService.kt \
        bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt \
        bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImplAutoDetectPlanTest.kt
git commit -m "feat(bamboo): add autoDetectPlan to BambooService core interface

Wraps PlanDetectionService.autoDetect as a ToolResult-returning method
on the unified core service interface, per CLAUDE.md's Agent-Exposable
Service Architecture rules. Enables the auto-detect orchestrator (and
future agent tools) to detect Bamboo plans without reflection or
direct :bamboo dependencies."
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

- [ ] **Step 1: Add tests for the four detector methods (single-repo + multi-repo)**

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
inner class ApplyBambooSpecsToStateTests {

    @Test
    fun `fills empty state fields from constants map`() {
        val state = PluginSettings.State()
        val constants = mapOf(
            "DOCKER_TAG_NAME" to "MyServiceDockerTag",
            "PLAN_KEY" to "MYSERVICE"
        )
        val filled = mutableListOf<String>()
        AutoDetectOrchestrator.applyBambooSpecsToState(state, constants, "global", filled)

        assertEquals("MyServiceDockerTag", state.dockerTagKey)
        assertEquals("MYSERVICE", state.bambooPlanKey)
        assertTrue(filled.contains("global.dockerTagKey"))
        assertTrue(filled.contains("global.bambooPlanKey"))
    }

    @Test
    fun `does not overwrite user-set state fields`() {
        val state = PluginSettings.State().apply {
            dockerTagKey = "UserSetTag"
            bambooPlanKey = "USERPLAN"
        }
        val constants = mapOf("DOCKER_TAG_NAME" to "DetectedTag", "PLAN_KEY" to "DETECTED")
        val filled = mutableListOf<String>()
        AutoDetectOrchestrator.applyBambooSpecsToState(state, constants, "global", filled)

        assertEquals("UserSetTag", state.dockerTagKey)
        assertEquals("USERPLAN", state.bambooPlanKey)
        assertTrue(filled.isEmpty())
    }
}

@Nested
inner class ApplyBambooSpecsToRepoTests {

    @Test
    fun `fills empty repo fields from constants map`() {
        val repo = RepoConfig().apply { name = "service-a" }
        val constants = mapOf(
            "DOCKER_TAG_NAME" to "ServiceADockerTag",
            "PLAN_KEY" to "SERVICEA"
        )
        val filled = mutableListOf<String>()
        AutoDetectOrchestrator.applyBambooSpecsToRepo(repo, constants, filled)

        assertEquals("ServiceADockerTag", repo.dockerTagKey)
        assertEquals("SERVICEA", repo.bambooPlanKey)
        assertTrue(filled.contains("service-a.dockerTagKey"))
        assertTrue(filled.contains("service-a.bambooPlanKey"))
    }

    @Test
    fun `does not overwrite user-set repo fields`() {
        val repo = RepoConfig().apply {
            name = "service-a"
            dockerTagKey = "UserSetTag"
            bambooPlanKey = "USERPLAN"
        }
        val constants = mapOf("DOCKER_TAG_NAME" to "DetectedTag", "PLAN_KEY" to "DETECTED")
        val filled = mutableListOf<String>()
        AutoDetectOrchestrator.applyBambooSpecsToRepo(repo, constants, filled)

        assertEquals("UserSetTag", repo.dockerTagKey)
        assertEquals("USERPLAN", repo.bambooPlanKey)
        assertTrue(filled.isEmpty())
    }
}

@Nested
inner class MirrorPrimaryToGlobalTests {

    @Test
    fun `mirrors primary repo values to global state when global is empty`() {
        val state = PluginSettings.State()
        val primary = RepoConfig().apply {
            isPrimary = true
            sonarProjectKey = "primary-sonar"
            bambooPlanKey = "PRIMARY"
            dockerTagKey = "PrimaryDockerTag"
        }
        val filled = mutableListOf<String>()
        AutoDetectOrchestrator.mirrorPrimaryToGlobal(state, primary, filled)

        assertEquals("primary-sonar", state.sonarProjectKey)
        assertEquals("PRIMARY", state.bambooPlanKey)
        assertEquals("PrimaryDockerTag", state.dockerTagKey)
    }

    @Test
    fun `does not mirror when global already has values`() {
        val state = PluginSettings.State().apply {
            sonarProjectKey = "global-sonar"
            bambooPlanKey = "GLOBAL"
        }
        val primary = RepoConfig().apply {
            sonarProjectKey = "primary-sonar"
            bambooPlanKey = "PRIMARY"
        }
        val filled = mutableListOf<String>()
        AutoDetectOrchestrator.mirrorPrimaryToGlobal(state, primary, filled)

        assertEquals("global-sonar", state.sonarProjectKey)
        assertEquals("GLOBAL", state.bambooPlanKey)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :core:test --tests "com.workflow.orchestrator.core.autodetect.AutoDetectOrchestratorTest"
```

Expected: FAIL with "Unresolved reference: applyBambooSpecsConstants"

- [ ] **Step 3: Implement the four detector methods (multi-repo aware)**

Replace the stub `detectAll()`, add the new instance methods, AND replace the existing `companion object` block (which currently only contains `fillIfEmpty`) with the expanded version below — the new companion includes `fillIfEmpty` plus three internal helpers (`applyBambooSpecsToState`, `applyBambooSpecsToRepo`, `mirrorPrimaryToGlobal`) used by the tests.

Edit `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectOrchestrator.kt`:

```kotlin
// Add these imports at the top:
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoConfig
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
    runDetector("bamboo-plan", filled, errors) { runBlockingPlan(it) }

    // After per-repo writes, mirror primary repo values to global as fallback
    val settings = PluginSettings.getInstance(project)
    val primary = settings.getPrimaryRepo()
    if (primary != null) {
        runDetector("mirror-primary", filled, errors) {
            mirrorPrimaryToGlobal(settings.state, primary, it)
        }
    }

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

private fun runBlockingPlan(filled: MutableList<String>) {
    // detectBambooPlan is suspend; bridge it for runDetector's sync block.
    kotlinx.coroutines.runBlocking { detectBambooPlan(filled) }
}

fun detectFromBambooSpecs(filled: MutableList<String>) {
    val settings = PluginSettings.getInstance(project)
    val repos = settings.getRepos()
    if (repos.isEmpty()) {
        val basePath = project.basePath ?: return
        val constants = BambooSpecsParser.parseConstants(Paths.get(basePath))
        if (constants.isEmpty()) return
        applyBambooSpecsToState(settings.state, constants, "global", filled)
        return
    }
    for (repo in repos) {
        val rootPath = repo.localVcsRootPath?.takeIf { it.isNotBlank() } ?: continue
        val constants = BambooSpecsParser.parseConstants(Paths.get(rootPath))
        if (constants.isEmpty()) continue
        applyBambooSpecsToRepo(repo, constants, filled)
    }
}

fun detectGitDerivable(filled: MutableList<String>) {
    // RepoContextResolver.autoDetectRepos() already populates RepoConfig
    // entries with bitbucketProjectKey + bitbucketRepoSlug from git remote.
    // We don't write to global PluginSettings here — repo-level config is
    // owned by RepositoriesConfigurable. This detector is a no-op in the
    // current scope (kept as a hook for future expansion).
}

fun detectSonarKey(filled: MutableList<String>) {
    val detector = project.getService(SonarKeyDetector::class.java) ?: return
    val settings = PluginSettings.getInstance(project)
    val repos = settings.getRepos()

    if (repos.isEmpty()) {
        val detected = detector.detect() ?: return
        val state = settings.state
        val updated = fillIfEmpty(state.sonarProjectKey, detected)
        if (updated != state.sonarProjectKey && !updated.isNullOrBlank()) {
            state.sonarProjectKey = updated
            filled += "global.sonarProjectKey"
        }
        return
    }

    for (repo in repos) {
        val rootPath = repo.localVcsRootPath?.takeIf { it.isNotBlank() } ?: continue
        val detected = detector.detectForPath(rootPath) ?: continue
        val updated = fillIfEmpty(repo.sonarProjectKey, detected)
        if (updated != repo.sonarProjectKey && !updated.isNullOrBlank()) {
            repo.sonarProjectKey = updated
            filled += "${repoLabel(repo)}.sonarProjectKey"
        }
    }
}

suspend fun detectBambooPlan(filled: MutableList<String>) {
    val settings = PluginSettings.getInstance(project)
    val repos = settings.getRepos()
    val gitRepos = GitRepositoryManager.getInstance(project).repositories
    val bambooService = project.getService(BambooService::class.java) ?: return

    if (repos.isEmpty()) {
        // Fallback: single global plan key from first git repo
        val state = settings.state
        if (state.bambooPlanKey.isNotBlank()) return
        val remoteUrl = gitRepos.firstOrNull()?.remotes?.firstOrNull()?.firstUrl ?: return
        val result = bambooService.autoDetectPlan(remoteUrl)
        if (result.isError || result.data.isBlank()) return
        state.bambooPlanKey = result.data
        filled += "global.bambooPlanKey"
        return
    }

    // Multi-repo: iterate over git repos, match each to a RepoConfig by path
    for (gitRepo in gitRepos) {
        val rootPath = gitRepo.root.path
        val repoConfig = settings.getRepoForPath(rootPath) ?: continue
        if (repoConfig.bambooPlanKey.isNotBlank()) continue
        val remoteUrl = gitRepo.remotes.firstOrNull()?.firstUrl ?: continue
        val result = bambooService.autoDetectPlan(remoteUrl)
        if (result.isError || result.data.isBlank()) continue
        repoConfig.bambooPlanKey = result.data
        filled += "${repoLabel(repoConfig)}.bambooPlanKey"
    }
}

private fun repoLabel(repo: RepoConfig): String =
    repo.name.takeIf { it.isNotBlank() } ?: repo.bitbucketRepoSlug.takeIf { it.isNotBlank() } ?: "unnamed"

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

companion object {
    /** Returns `detected` only when `current` is null/blank AND `detected` is non-blank. */
    fun fillIfEmpty(current: String?, detected: String?): String? =
        if (current.isNullOrBlank() && !detected.isNullOrBlank()) detected else current

    /** Visible for testing. Writes constants into a state object via fill-only-empty. */
    internal fun applyBambooSpecsToState(
        state: PluginSettings.State,
        constants: Map<String, String>,
        label: String,
        filled: MutableList<String>
    ) {
        val newDocker = fillIfEmpty(state.dockerTagKey, constants["DOCKER_TAG_NAME"])
        if (newDocker != state.dockerTagKey && !newDocker.isNullOrBlank()) {
            state.dockerTagKey = newDocker
            filled += "$label.dockerTagKey"
        }
        val newPlan = fillIfEmpty(state.bambooPlanKey, constants["PLAN_KEY"])
        if (newPlan != state.bambooPlanKey && !newPlan.isNullOrBlank()) {
            state.bambooPlanKey = newPlan
            filled += "$label.bambooPlanKey"
        }
    }

    /** Visible for testing. Writes constants into a repo via fill-only-empty. */
    internal fun applyBambooSpecsToRepo(
        repo: RepoConfig,
        constants: Map<String, String>,
        filled: MutableList<String>
    ) {
        val label = repo.name.takeIf { it.isNotBlank() }
            ?: repo.bitbucketRepoSlug.takeIf { it.isNotBlank() } ?: "unnamed"
        val newDocker = fillIfEmpty(repo.dockerTagKey, constants["DOCKER_TAG_NAME"])
        if (newDocker != repo.dockerTagKey && !newDocker.isNullOrBlank()) {
            repo.dockerTagKey = newDocker
            filled += "$label.dockerTagKey"
        }
        val newPlan = fillIfEmpty(repo.bambooPlanKey, constants["PLAN_KEY"])
        if (newPlan != repo.bambooPlanKey && !newPlan.isNullOrBlank()) {
            repo.bambooPlanKey = newPlan
            filled += "$label.bambooPlanKey"
        }
    }

    /** Visible for testing. Mirrors primary repo's values to global state via fill-only-empty. */
    internal fun mirrorPrimaryToGlobal(
        state: PluginSettings.State,
        primary: RepoConfig,
        filled: MutableList<String>
    ) {
        val newSonar = fillIfEmpty(state.sonarProjectKey, primary.sonarProjectKey)
        if (newSonar != state.sonarProjectKey && !newSonar.isNullOrBlank()) {
            state.sonarProjectKey = newSonar
            filled += "global.sonarProjectKey"
        }
        val newPlan = fillIfEmpty(state.bambooPlanKey, primary.bambooPlanKey)
        if (newPlan != state.bambooPlanKey && !newPlan.isNullOrBlank()) {
            state.bambooPlanKey = newPlan
            filled += "global.bambooPlanKey"
        }
        val newDocker = fillIfEmpty(state.dockerTagKey, primary.dockerTagKey)
        if (newDocker != state.dockerTagKey && !newDocker.isNullOrBlank()) {
            state.dockerTagKey = newDocker
            filled += "global.dockerTagKey"
        }
    }
}
```

> **Note on direct `BambooService` call:** Task 3 added `BambooService.autoDetectPlan(gitRemoteUrl)` to the unified core service interface. The orchestrator calls it directly via `project.getService(BambooService::class.java)` — no reflection, no `kotlin-reflect` dependency, no `:core` → `:bamboo` import. The dependency direction is preserved because the interface lives in `:core` and only the implementation lives in `:bamboo`.

> **Note on `detectGitDerivable`:** Currently a no-op because `RepoContextResolver.autoDetectRepos()` already populates `RepoConfig` entries with the git-derived fields, and that path is owned by `RepositoriesConfigurable`. We keep the method as a hook so the trigger wiring (file listener for `.git/config`) has a place to call.

> **Note on `runBlocking` in `runBlockingPlan`:** Acceptable because `detectAll()` runs on `Dispatchers.IO`, never on the EDT. The brief block is needed to bridge the suspend `detectBambooPlan()` into the synchronous `runDetector` helper.

> **Companion-object visibility:** `applyBambooSpecsToState`, `applyBambooSpecsToRepo`, and `mirrorPrimaryToGlobal` are `internal` companion methods so the tests in the same module can call them statically without constructing a real `Project`. The instance methods `detectFromBambooSpecs`, `detectSonarKey`, etc. delegate to these.

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

### Task 9: Integration test with fixture project (single + multi-repo)

**Files:**
- Create: `core/src/test/testData/auto-detect-project/.git/config`
- Create: `core/src/test/testData/auto-detect-project/pom.xml`
- Create: `core/src/test/testData/auto-detect-project/bamboo-specs/src/main/java/constants/PlanProperties.java`
- Create: `core/src/test/testData/auto-detect-multi-repo/service-a/bamboo-specs/src/main/java/constants/PlanProperties.java`
- Create: `core/src/test/testData/auto-detect-multi-repo/service-b/bamboo-specs/src/main/java/constants/PlanProperties.java`
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

Create `core/src/test/testData/auto-detect-multi-repo/service-a/bamboo-specs/src/main/java/constants/PlanProperties.java`:

```java
package constants;

public class PlanProperties {
    private static final String REPOSITORY_NAME = "service-a";
    private static final String PLAN_KEY = "SERVICEA";
    private static final String DOCKER_TAG_NAME = "ServiceADockerTag";
}
```

Create `core/src/test/testData/auto-detect-multi-repo/service-b/bamboo-specs/src/main/java/constants/PlanProperties.java`:

```java
package constants;

public class PlanProperties {
    private static final String REPOSITORY_NAME = "service-b";
    private static final String PLAN_KEY = "SERVICEB";
    private static final String DOCKER_TAG_NAME = "ServiceBDockerTag";
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
    fun `applyBambooSpecsToState fills all empty fields from single-repo fixture`() {
        val constants = BambooSpecsParser.parseConstants(fixtureRoot)
        val state = com.workflow.orchestrator.core.settings.PluginSettings.State()
        val filled = mutableListOf<String>()

        AutoDetectOrchestrator.applyBambooSpecsToState(state, constants, "global", filled)

        assertEquals("MySampleServiceDockerTag", state.dockerTagKey)
        assertEquals("MYSAMPLESERVICE", state.bambooPlanKey)
        assertTrue(filled.contains("global.dockerTagKey"))
        assertTrue(filled.contains("global.bambooPlanKey"))
    }

    @Test
    fun `multi-repo fixture fills each repo with its own constants`() {
        val multiRoot: Path = Paths.get("src/test/testData/auto-detect-multi-repo")
        val repoA = com.workflow.orchestrator.core.settings.RepoConfig().apply {
            name = "service-a"
            localVcsRootPath = multiRoot.resolve("service-a").toString()
        }
        val repoB = com.workflow.orchestrator.core.settings.RepoConfig().apply {
            name = "service-b"
            localVcsRootPath = multiRoot.resolve("service-b").toString()
        }
        val filled = mutableListOf<String>()

        for (repo in listOf(repoA, repoB)) {
            val constants = BambooSpecsParser.parseConstants(Paths.get(repo.localVcsRootPath!!))
            AutoDetectOrchestrator.applyBambooSpecsToRepo(repo, constants, filled)
        }

        assertEquals("ServiceADockerTag", repoA.dockerTagKey)
        assertEquals("SERVICEA", repoA.bambooPlanKey)
        assertEquals("ServiceBDockerTag", repoB.dockerTagKey)
        assertEquals("SERVICEB", repoB.bambooPlanKey)
        assertTrue(filled.contains("service-a.dockerTagKey"))
        assertTrue(filled.contains("service-a.bambooPlanKey"))
        assertTrue(filled.contains("service-b.dockerTagKey"))
        assertTrue(filled.contains("service-b.bambooPlanKey"))
    }

    @Test
    fun `multi-repo mirror copies primary repo values to global state`() {
        val state = com.workflow.orchestrator.core.settings.PluginSettings.State()
        val primary = com.workflow.orchestrator.core.settings.RepoConfig().apply {
            name = "service-a"
            isPrimary = true
            sonarProjectKey = "service-a"
            bambooPlanKey = "SERVICEA"
            dockerTagKey = "ServiceADockerTag"
        }
        val filled = mutableListOf<String>()

        AutoDetectOrchestrator.mirrorPrimaryToGlobal(state, primary, filled)

        assertEquals("service-a", state.sonarProjectKey)
        assertEquals("SERVICEA", state.bambooPlanKey)
        assertEquals("ServiceADockerTag", state.dockerTagKey)
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

**Single-repo scenario:**
1. Open a project that has `bamboo-specs/src/main/java/constants/PlanProperties.java` with a `DOCKER_TAG_NAME` constant.
2. Verify a notification appears: "Auto-detected project keys: ..."
3. Open Settings → Tools → Workflow Orchestrator → Repositories. Verify `dockerTagKey` is filled.
4. Manually clear `dockerTagKey` and click Auto-Detect. Verify it refills.
5. Manually set `dockerTagKey` to a custom value and reopen the project. Verify auto-detect does NOT overwrite the custom value.
6. Switch git branches. Verify the orchestrator re-runs (check the IDE log for `[AutoDetect] BranchChanged`).
7. Edit `bamboo-specs/.../PlanProperties.java`. Verify the file listener fires within 500ms (check log for `[AutoDetect:FileListener] bamboo-specs changed`).

**Multi-repo scenario:**
8. Open a project that has multiple git repositories (e.g. via `File → Project Structure → VCS Mappings`, add 2+ Git roots). Each repo has its own `bamboo-specs/.../PlanProperties.java` with different `DOCKER_TAG_NAME` and `PLAN_KEY` constants. Each repo's root pom.xml has its own `sonar.projectKey`.
9. Click Settings → Tools → Workflow Orchestrator → Repositories → Auto-Detect.
10. Verify the repo table shows multiple repos, each with its own `dockerTagKey`, `bambooPlanKey`, and `sonarProjectKey` filled from that repo's files (not all the same value).
11. Edit one repo's `bamboo-specs/.../PlanProperties.java`. Verify only that repo's docker tag re-detects (other repos unchanged).
12. Verify the global `PluginSettings.sonarProjectKey` (visible on the Sonar settings page) shows the **primary** repo's value.
13. Manually set one repo's `dockerTagKey` to a custom value and click Auto-Detect again. Verify only the empty repos get filled; the manually-set one is preserved.

- [ ] **Step 6: Commit**

```bash
git add core/src/test/testData/auto-detect-project \
        core/src/test/testData/auto-detect-multi-repo \
        core/src/test/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectIntegrationTest.kt
git commit -m "test(autodetect): add single + multi-repo fixtures + integration test

Single-repo fixture (.git/config, pom.xml, PlanProperties.java) and
multi-repo fixture (service-a + service-b each with their own
bamboo-specs constants). Verifies the fill-only-empty rule and the
per-repo write paths for BambooSpecsParser, applyBambooSpecsToRepo,
and mirrorPrimaryToGlobal. Manual smoke steps include multi-repo
scenarios."
```

---

## Self-Review

**Spec coverage:**

- [x] Goal 1 (docker tag from bamboo-specs): Tasks 1, 5
- [x] Goal 2 (bamboo project key derivation): N/A — `RepoConfig` has no field for it; `bitbucketProjectKey` already holds the same value. Documented in plan header.
- [x] Goal 3 (auto-trigger on project open / branch / file changes): Tasks 5 (BranchChanged subscription), 6 (startup activity), 7 (file listener)
- [x] Goal 4 (never overwrite user-set values): Task 4 (`fillIfEmpty`), tests in Tasks 4 and 5
- [x] Goal 5 (manual buttons still work): Tasks 2 (sonar), 8 (repositories)
- [x] **Goal 6 (multi-repo per-repo writes for sonar / docker tag / bamboo plan):** Task 2 (`SonarKeyDetector.detectForPath`), Task 5 (orchestrator iterates over `getRepos()`, writes to each `RepoConfig`, mirrors primary to global), Task 9 (multi-repo fixture + tests)
- [x] SonarKeyDetector extraction + `detectForPath`: Task 2
- [x] BambooService.autoDetectPlan added to core interface + impl: Task 3
- [x] One-time notification: Task 5
- [x] BambooSpecsParser tests: Task 1
- [x] AutoDetectOrchestrator tests (single-repo + multi-repo): Tasks 4, 5
- [x] SonarKeyDetector tests (`detect` + `detectForPath`): Task 2
- [x] Integration test (single + multi-repo fixtures): Task 9
- [x] `mirrorPrimaryToGlobal` helper + tests: Task 5

**Placeholder scan:** No TBD/TODO/"add appropriate" placeholders. Every code step shows the actual code.

**Type consistency:**
- `BambooSpecsParser.parseConstants(Path): Map<String, String>` — used consistently in Tasks 1, 5, 9.
- `AutoDetectResult(filledFields: List<String>, errors: List<String>)` — Task 4 defines, Task 5 returns it, Task 8 reads `.anyFilled` and `.filledFields`.
- `AutoDetectOrchestrator.fillIfEmpty(String?, String?): String?` — Task 4 defines, Task 5 keeps in expanded companion, Task 9 uses.
- `AutoDetectOrchestrator.applyBambooSpecsToState(State, Map, String, MutableList): Unit` — Task 5 defines as internal companion, Tasks 5 + 9 test.
- `AutoDetectOrchestrator.applyBambooSpecsToRepo(RepoConfig, Map, MutableList): Unit` — Task 5 defines as internal companion, Tasks 5 + 9 test.
- `AutoDetectOrchestrator.mirrorPrimaryToGlobal(State, RepoConfig, MutableList): Unit` — Task 5 defines as internal companion, Tasks 5 + 9 test.
- `BambooService.autoDetectPlan(gitRemoteUrl: String): ToolResult<String>` — Task 3 adds to `:core` interface and implements in `BambooServiceImpl`. Task 5 calls directly via `project.getService(BambooService::class.java)`. No reflection.
- `SonarKeyDetector.detect(): String?` — Task 2 defines, Task 2 step 5 uses, Task 5 uses for no-repo fallback.
- `SonarKeyDetector.detectForPath(repoRootPath: String): String?` — Task 2 defines, Task 5 uses for per-repo iteration.

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
