# Phase 1F: Pre-Push & Health Check — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a configurable health check gate to the VCS commit dialog that runs Maven builds, copyright checks, and Sonar quality gate verification before code is committed — plus a CVE auto-bumper for pom.xml vulnerabilities.

**Architecture:** No new Gradle modules. Health check orchestration goes in `:core` (packages: `healthcheck/`, `maven/`, `copyright/`). CVE remediation goes in `:bamboo` (packages: `service/`, `editor/`). A pluggable `HealthCheck` interface lets each check be independently toggled. `VcsCheckinHandlerFactory` integrates with IntelliJ's commit dialog. Maven execution uses `GeneralCommandLine` with `mvnw` support. CVE data flows from Bamboo build logs through `CveRemediationService` to a PSI-based `IntentionAction` and `ExternalAnnotator` on pom.xml.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (2025.1+), kotlinx.coroutines 1.8, JUnit 5, MockK, Turbine

**Spec:** `docs/superpowers/specs/2026-03-11-phase-1f-prepush-health-design.md`

---

## Chunk 1: Foundation — Events, Settings, Data Models

### Task 1: Add Health Check Events to WorkflowEvent

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/events/EventBus.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt`
- Modify: `core/src/test/kotlin/com/workflow/orchestrator/core/events/EventBusTest.kt`

- [ ] **Step 1: Write failing test for HealthCheckStarted event**

Add to `EventBusTest.kt`:

```kotlin
@Test
fun `emit delivers HealthCheckStarted event`() = runTest {
    val bus = EventBus()
    val event = WorkflowEvent.HealthCheckStarted(checks = listOf("maven-compile", "copyright"))

    bus.events.test {
        bus.emit(event)
        val received = awaitItem()
        assertTrue(received is WorkflowEvent.HealthCheckStarted)
        assertEquals(listOf("maven-compile", "copyright"), (received as WorkflowEvent.HealthCheckStarted).checks)
        cancelAndIgnoreRemainingEvents()
    }
}

@Test
fun `emit delivers HealthCheckFinished event`() = runTest {
    val bus = EventBus()
    val event = WorkflowEvent.HealthCheckFinished(
        passed = false,
        results = mapOf("maven-compile" to true, "copyright" to false),
        durationMs = 1234
    )

    bus.events.test {
        bus.emit(event)
        val received = awaitItem()
        assertTrue(received is WorkflowEvent.HealthCheckFinished)
        val finished = received as WorkflowEvent.HealthCheckFinished
        assertFalse(finished.passed)
        assertEquals(1234, finished.durationMs)
        assertEquals(false, finished.results["copyright"])
        cancelAndIgnoreRemainingEvents()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.events.EventBusTest" -x verifyPluginStructure`
Expected: FAIL — `HealthCheckStarted` and `HealthCheckFinished` are unresolved references.

- [ ] **Step 3: Add `getInstance()` companion to EventBus**

The existing `EventBus` class has no `getInstance()` helper. Add one to match the pattern used by `WorkflowNotificationService`, `PluginSettings`, etc. In `EventBus.kt`, add before the closing `}`:

```kotlin
companion object {
    fun getInstance(project: com.intellij.openapi.project.Project): EventBus =
        project.getService(EventBus::class.java)
}
```

- [ ] **Step 4: Add events to WorkflowEvent sealed class**

In `WorkflowEvent.kt`, add before the closing `}`:

```kotlin
/** Emitted by :core when health checks begin running. */
data class HealthCheckStarted(
    val checks: List<String>
) : WorkflowEvent()

/** Emitted by :core when all health checks complete. */
data class HealthCheckFinished(
    val passed: Boolean,
    val results: Map<String, Boolean>,
    val durationMs: Long
) : WorkflowEvent()
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.events.EventBusTest" -x verifyPluginStructure`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/events/EventBus.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/events/EventBusTest.kt
git commit -m "feat(core): add EventBus.getInstance(), HealthCheckStarted and HealthCheckFinished events"
```

---

### Task 2: Add Health Check Settings to PluginSettings

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`

- [ ] **Step 1: Add health check settings fields**

Add these fields to the `State` class, after the existing `jiraBoardId` field:

```kotlin
// Health check configuration
var healthCheckEnabled by property(true)
var healthCheckBlockingMode by string("soft")
var healthCheckCompileEnabled by property(true)
var healthCheckTestEnabled by property(true)
var healthCheckCopyrightEnabled by property(true)
var healthCheckSonarGateEnabled by property(true)
var healthCheckCveEnabled by property(true)
var healthCheckMavenGoals by string("clean compile test")
var healthCheckSkipBranchPattern by string("")
var healthCheckTimeoutSeconds by property(300)
var copyrightHeaderPattern by string("")
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew :core:compileKotlin -x verifyPluginStructure`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt
git commit -m "feat(core): add health check configuration settings"
```

---

### Task 3: Create HealthCheck Interface and Result Data Classes

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/healthcheck/checks/HealthCheck.kt`
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/healthcheck/HealthCheckResult.kt`

- [ ] **Step 1: Create HealthCheck interface**

```kotlin
package com.workflow.orchestrator.core.healthcheck.checks

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.core.settings.PluginSettings

interface HealthCheck {
    val id: String
    val displayName: String
    val order: Int

    fun isEnabled(settings: PluginSettings.State): Boolean
    suspend fun execute(context: HealthCheckContext): CheckResult

    data class CheckResult(
        val passed: Boolean,
        val message: String,
        val details: List<String> = emptyList()
    )
}

data class HealthCheckContext(
    val project: Project,
    val changedFiles: List<VirtualFile>,
    val commitMessage: String,
    val branch: String
)
```

- [ ] **Step 2: Create HealthCheckResult data class**

```kotlin
package com.workflow.orchestrator.core.healthcheck

import com.workflow.orchestrator.core.healthcheck.checks.HealthCheck

data class HealthCheckResult(
    val passed: Boolean,
    val checkResults: Map<String, HealthCheck.CheckResult>,
    val skipped: Boolean = false,
    val skipReason: String? = null,
    val durationMs: Long = 0
) {
    companion object {
        fun skipped(reason: String = "Health check disabled") =
            HealthCheckResult(
                passed = true,
                checkResults = emptyMap(),
                skipped = true,
                skipReason = reason
            )
    }
}
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew :core:compileKotlin -x verifyPluginStructure`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/healthcheck/checks/HealthCheck.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/healthcheck/HealthCheckResult.kt
git commit -m "feat(core): add HealthCheck interface and HealthCheckResult data class"
```

---

### Task 4: Create CopyrightCheckService

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/copyright/CopyrightCheckService.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/copyright/CopyrightCheckServiceTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.core.copyright

import com.intellij.openapi.vfs.VirtualFile
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CopyrightCheckServiceTest {

    private fun mockFile(name: String, extension: String, content: String): VirtualFile {
        val file = mockk<VirtualFile>()
        every { file.name } returns name
        every { file.extension } returns extension
        every { file.contentsToByteArray() } returns content.toByteArray()
        return file
    }

    @Test
    fun `returns no violations when all files have copyright header`() {
        val file = mockFile("Foo.kt", "kt", "// Copyright (c) 2026 MyCompany\npackage com.example\n")
        val pattern = """Copyright \(c\) \d{4} MyCompany"""

        val result = CopyrightCheckService.checkFiles(listOf(file), pattern)

        assertTrue(result.passed)
        assertTrue(result.violations.isEmpty())
    }

    @Test
    fun `detects missing copyright header`() {
        val file = mockFile("Foo.kt", "kt", "package com.example\nclass Foo {}\n")
        val pattern = """Copyright \(c\) \d{4} MyCompany"""

        val result = CopyrightCheckService.checkFiles(listOf(file), pattern)

        assertFalse(result.passed)
        assertEquals(1, result.violations.size)
        assertEquals("Foo.kt", result.violations[0].fileName)
    }

    @Test
    fun `skips non-source files`() {
        val file = mockFile("image.png", "png", "binary data")
        val pattern = """Copyright"""

        val result = CopyrightCheckService.checkFiles(listOf(file), pattern)

        assertTrue(result.passed)
        assertTrue(result.violations.isEmpty())
    }

    @Test
    fun `returns no violations when pattern is blank`() {
        val file = mockFile("Foo.kt", "kt", "package com.example\n")
        val pattern = ""

        val result = CopyrightCheckService.checkFiles(listOf(file), pattern)

        assertTrue(result.passed)
    }

    @Test
    fun `only scans first 10 lines`() {
        val lines = (1..20).joinToString("\n") { "// line $it" }
        val content = "$lines\n// Copyright (c) 2026 MyCompany"
        val file = mockFile("Foo.kt", "kt", content)
        val pattern = """Copyright \(c\) \d{4} MyCompany"""

        val result = CopyrightCheckService.checkFiles(listOf(file), pattern)

        // Copyright is on line 21, beyond the 10-line scan window
        assertFalse(result.passed)
    }

    @Test
    fun `checks multiple file types`() {
        val javaFile = mockFile("Foo.java", "java", "package com.example;\n")
        val xmlFile = mockFile("pom.xml", "xml", "<project>\n</project>\n")
        val pattern = """Copyright"""

        val result = CopyrightCheckService.checkFiles(listOf(javaFile, xmlFile), pattern)

        assertFalse(result.passed)
        assertEquals(2, result.violations.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.copyright.CopyrightCheckServiceTest" -x verifyPluginStructure`
Expected: FAIL — class not found

- [ ] **Step 3: Implement CopyrightCheckService**

```kotlin
package com.workflow.orchestrator.core.copyright

import com.intellij.openapi.vfs.VirtualFile

object CopyrightCheckService {

    private val SOURCE_EXTENSIONS = setOf("java", "kt", "kts", "xml", "yaml", "yml", "properties")

    fun checkFiles(files: List<VirtualFile>, pattern: String): CopyrightCheckResult {
        if (pattern.isBlank()) return CopyrightCheckResult(emptyList())

        val regex = Regex(pattern)
        val violations = mutableListOf<CopyrightViolation>()

        for (file in files) {
            val ext = file.extension ?: continue
            if (ext !in SOURCE_EXTENSIONS) continue

            val content = file.contentsToByteArray().decodeToString()
            val headerLines = content.lines().take(10).joinToString("\n")

            if (!regex.containsMatchIn(headerLines)) {
                violations.add(CopyrightViolation(file.name, "Missing copyright header"))
            }
        }

        return CopyrightCheckResult(violations)
    }
}

data class CopyrightCheckResult(val violations: List<CopyrightViolation>) {
    val passed: Boolean get() = violations.isEmpty()
}

data class CopyrightViolation(
    val fileName: String,
    val reason: String
)
```

> **Design note:** `CopyrightCheckService` is an `object` (stateless singleton) rather than a project service because it needs no project-level state — it takes files and a pattern as parameters. This makes it trivially testable without mocking IntelliJ services.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.copyright.CopyrightCheckServiceTest" -x verifyPluginStructure`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/copyright/CopyrightCheckService.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/copyright/CopyrightCheckServiceTest.kt
git commit -m "feat(core): add CopyrightCheckService with regex-based header validation"
```

---

### Task 5: Create MavenModuleDetector

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/maven/MavenModuleDetector.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/maven/MavenModuleDetectorTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.core.maven

import com.intellij.openapi.vfs.VirtualFile
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MavenModuleDetectorTest {

    private fun mockFile(path: String): VirtualFile {
        val file = mockk<VirtualFile>()
        every { file.path } returns path
        // Build parent chain from path
        val segments = path.split("/").filter { it.isNotEmpty() }
        var current: VirtualFile = file
        for (i in segments.size - 2 downTo 0) {
            val parent = mockk<VirtualFile>()
            val parentPath = "/" + segments.subList(0, i + 1).joinToString("/")
            every { parent.path } returns parentPath
            every { parent.findChild("pom.xml") } returns null
            every { current.parent } returns parent
            current = parent
        }
        every { current.parent } returns null
        return file
    }

    private fun mockFileWithPom(filePath: String, pomDir: String, artifactId: String): VirtualFile {
        val file = mockk<VirtualFile>(relaxed = true)
        every { file.path } returns filePath

        val pomFile = mockk<VirtualFile>()
        every { pomFile.contentsToByteArray() } returns
            "<project><artifactId>$artifactId</artifactId></project>".toByteArray()

        // Walk up from file to pomDir
        val segments = filePath.split("/").filter { it.isNotEmpty() }
        val pomSegments = pomDir.split("/").filter { it.isNotEmpty() }

        var current: VirtualFile = file
        for (i in segments.size - 2 downTo 0) {
            val parent = mockk<VirtualFile>()
            val parentPath = "/" + segments.subList(0, i + 1).joinToString("/")
            every { parent.path } returns parentPath
            if (parentPath == pomDir) {
                every { parent.findChild("pom.xml") } returns pomFile
            } else {
                every { parent.findChild("pom.xml") } returns null
            }
            every { current.parent } returns parent
            current = parent
        }
        every { current.parent } returns null
        return file
    }

    @Test
    fun `detects single module from changed file`() {
        val file = mockFileWithPom(
            "/project/module-a/src/main/java/Foo.java",
            "/project/module-a",
            "module-a"
        )

        val modules = MavenModuleDetector.detectChangedModules(listOf(file))

        assertEquals(listOf("module-a"), modules)
    }

    @Test
    fun `deduplicates modules from multiple files in same module`() {
        val file1 = mockFileWithPom(
            "/project/module-a/src/main/java/Foo.java",
            "/project/module-a",
            "module-a"
        )
        val file2 = mockFileWithPom(
            "/project/module-a/src/main/java/Bar.java",
            "/project/module-a",
            "module-a"
        )

        val modules = MavenModuleDetector.detectChangedModules(listOf(file1, file2))

        assertEquals(listOf("module-a"), modules)
    }

    @Test
    fun `detects multiple modules from different changed files`() {
        val file1 = mockFileWithPom(
            "/project/module-a/src/Foo.java",
            "/project/module-a",
            "module-a"
        )
        val file2 = mockFileWithPom(
            "/project/module-b/src/Bar.java",
            "/project/module-b",
            "module-b"
        )

        val modules = MavenModuleDetector.detectChangedModules(listOf(file1, file2))

        assertEquals(listOf("module-a", "module-b"), modules.sorted())
    }

    @Test
    fun `returns empty list when no pom found`() {
        val file = mockFile("/tmp/random/file.txt")

        val modules = MavenModuleDetector.detectChangedModules(listOf(file))

        assertTrue(modules.isEmpty())
    }

    @Test
    fun `buildMavenArgs returns goals only for empty module list`() {
        val args = MavenModuleDetector.buildMavenArgs(emptyList(), "clean compile")

        assertEquals(listOf("clean", "compile"), args)
    }

    @Test
    fun `buildMavenArgs adds -pl and -am for module list`() {
        val args = MavenModuleDetector.buildMavenArgs(listOf("module-a", "module-b"), "clean test")

        assertTrue(args.contains("-pl"))
        assertTrue(args.contains("module-a,module-b"))
        assertTrue(args.contains("-am"))
        assertTrue(args.contains("clean"))
        assertTrue(args.contains("test"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.maven.MavenModuleDetectorTest" -x verifyPluginStructure`
Expected: FAIL — class not found

- [ ] **Step 3: Implement MavenModuleDetector**

```kotlin
package com.workflow.orchestrator.core.maven

import com.intellij.openapi.vfs.VirtualFile

object MavenModuleDetector {

    private val ARTIFACT_ID_PATTERN = Regex("""<artifactId>\s*(.+?)\s*</artifactId>""")

    fun detectChangedModules(changedFiles: List<VirtualFile>): List<String> {
        val modules = mutableSetOf<String>()

        for (file in changedFiles) {
            val pomFile = findNearestPom(file) ?: continue
            val artifactId = extractArtifactId(pomFile) ?: continue
            modules.add(artifactId)
        }

        return modules.toList()
    }

    fun buildMavenArgs(modules: List<String>, goals: String): List<String> {
        val goalList = goals.trim().split("\\s+".toRegex())
        if (modules.isEmpty()) return goalList

        return listOf("-pl", modules.joinToString(","), "-am") + goalList
    }

    private fun findNearestPom(file: VirtualFile): VirtualFile? {
        var dir = file.parent
        while (dir != null) {
            val pom = dir.findChild("pom.xml")
            if (pom != null) return pom
            dir = dir.parent
        }
        return null
    }

    private fun extractArtifactId(pomFile: VirtualFile): String? {
        val content = pomFile.contentsToByteArray().decodeToString()
        return ARTIFACT_ID_PATTERN.find(content)?.groupValues?.get(1)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.maven.MavenModuleDetectorTest" -x verifyPluginStructure`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/maven/MavenModuleDetector.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/maven/MavenModuleDetectorTest.kt
git commit -m "feat(core): add MavenModuleDetector for incremental build module detection"
```

---

## Chunk 2: Maven Build Service & Console Integration

### Task 6: Create MavenBuildService

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/maven/MavenBuildService.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/maven/MavenBuildServiceTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.core.maven

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MavenBuildServiceTest {

    @Test
    fun `detectMavenExecutable finds mvnw in project root`(@TempDir tempDir: Path) {
        val mvnw = tempDir.resolve("mvnw")
        Files.createFile(mvnw)
        mvnw.toFile().setExecutable(true)

        val executable = MavenBuildService.detectMavenExecutable(tempDir.toString())

        assertEquals(mvnw.toString(), executable)
    }

    @Test
    fun `detectMavenExecutable falls back to MAVEN_HOME`(@TempDir tempDir: Path) {
        val mavenHome = tempDir.resolve("maven")
        val binDir = mavenHome.resolve("bin")
        Files.createDirectories(binDir)
        Files.createFile(binDir.resolve("mvn"))

        val executable = MavenBuildService.detectMavenExecutable(
            projectBasePath = tempDir.toString(),
            mavenHome = mavenHome.toString()
        )

        assertEquals(binDir.resolve("mvn").toString(), executable)
    }

    @Test
    fun `detectMavenExecutable falls back to bare mvn`(@TempDir tempDir: Path) {
        val executable = MavenBuildService.detectMavenExecutable(
            projectBasePath = tempDir.toString(),
            mavenHome = null
        )

        assertEquals("mvn", executable)
    }

    @Test
    fun `buildCommandLine constructs correct args for single module`(@TempDir tempDir: Path) {
        val cmd = MavenBuildService.buildCommandLine(
            executable = "mvn",
            goals = "clean compile",
            modules = emptyList(),
            projectBasePath = tempDir.toString()
        )

        assertTrue(cmd.commandLineString.contains("mvn"))
        assertTrue(cmd.commandLineString.contains("clean"))
        assertTrue(cmd.commandLineString.contains("compile"))
        assertFalse(cmd.commandLineString.contains("-pl"))
        assertEquals(tempDir.toString(), cmd.workDirectory?.path)
    }

    @Test
    fun `buildCommandLine adds -pl -am for multi-module`(@TempDir tempDir: Path) {
        val cmd = MavenBuildService.buildCommandLine(
            executable = "mvn",
            goals = "test",
            modules = listOf("core", "bamboo"),
            projectBasePath = tempDir.toString()
        )

        assertTrue(cmd.commandLineString.contains("-pl"))
        assertTrue(cmd.commandLineString.contains("core,bamboo"))
        assertTrue(cmd.commandLineString.contains("-am"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.maven.MavenBuildServiceTest" -x verifyPluginStructure`
Expected: FAIL — class not found

- [ ] **Step 3: Implement MavenBuildService**

```kotlin
package com.workflow.orchestrator.core.maven

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Service(Service.Level.PROJECT)
class MavenBuildService(private val project: Project) {

    suspend fun runBuild(
        goals: String,
        modules: List<String> = emptyList(),
        offline: Boolean = false
    ): MavenBuildResult = withContext(Dispatchers.IO) {
        val basePath = project.basePath ?: return@withContext MavenBuildResult(
            success = false, exitCode = -1,
            output = "", errors = "Project base path is null", timedOut = false
        )
        val executable = detectMavenExecutable(basePath, System.getenv("MAVEN_HOME") ?: System.getenv("M2_HOME"))
        val cmd = buildCommandLine(executable, goals, modules, basePath, offline)

        val handler = OSProcessHandler(cmd)
        val output = StringBuilder()
        val errors = StringBuilder()

        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                when (outputType) {
                    ProcessOutputTypes.STDOUT -> output.append(event.text)
                    ProcessOutputTypes.STDERR -> errors.append(event.text)
                }
            }
        })

        handler.startNotify()
        val timeoutMs = PluginSettings.getInstance(project).state.healthCheckTimeoutSeconds * 1000L
        val completed = handler.waitFor(timeoutMs)

        if (!completed) {
            handler.destroyProcess()
        }

        MavenBuildResult(
            success = completed && handler.exitCode == 0,
            exitCode = handler.exitCode ?: -1,
            output = output.toString(),
            errors = errors.toString(),
            timedOut = !completed
        )
    }

    companion object {
        fun getInstance(project: Project): MavenBuildService =
            project.getService(MavenBuildService::class.java)

        fun detectMavenExecutable(
            projectBasePath: String,
            mavenHome: String? = null
        ): String {
            // 1. Maven wrapper in project root
            val mvnw = File(projectBasePath, "mvnw")
            if (mvnw.exists() && mvnw.canExecute()) return mvnw.absolutePath

            val mvnwCmd = File(projectBasePath, "mvnw.cmd")
            if (mvnwCmd.exists()) return mvnwCmd.absolutePath

            // 2. MAVEN_HOME or M2_HOME
            if (!mavenHome.isNullOrBlank()) {
                val mvnBin = File(mavenHome, "bin/mvn")
                if (mvnBin.exists()) return mvnBin.absolutePath
            }

            // 3. Bare command on PATH
            return "mvn"
        }

        fun buildCommandLine(
            executable: String,
            goals: String,
            modules: List<String> = emptyList(),
            projectBasePath: String,
            offline: Boolean = false
        ): GeneralCommandLine {
            val args = MavenModuleDetector.buildMavenArgs(modules, goals).toMutableList()
            if (offline) args.add(0, "-o")

            return GeneralCommandLine(listOf(executable) + args)
                .withWorkDirectory(projectBasePath)
                .withEnvironment(System.getenv())
        }
    }
}

data class MavenBuildResult(
    val success: Boolean,
    val exitCode: Int,
    val output: String,
    val errors: String,
    val timedOut: Boolean = false
)
```

> **Design note:** Static helper methods (`detectMavenExecutable`, `buildCommandLine`) are in the `companion object` so tests can exercise them without mocking IntelliJ project services. The `runBuild()` instance method handles the process lifecycle and needs a real project.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.maven.MavenBuildServiceTest" -x verifyPluginStructure`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/maven/MavenBuildService.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/maven/MavenBuildServiceTest.kt
git commit -m "feat(core): add MavenBuildService with mvnw support and incremental builds"
```

---

### Task 7: Create Maven Console Output Filters

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/maven/MavenOutputFilters.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/maven/MavenOutputFiltersTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.core.maven

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MavenOutputFiltersTest {

    @Test
    fun `MavenErrorPattern matches ERROR with file path and line`() {
        val line = "[ERROR] /src/main/java/com/example/UserService.java:[45,12] cannot find symbol"
        val match = MavenOutputPatterns.FILE_ERROR_PATTERN.find(line)

        assertNotNull(match)
        assertEquals("/src/main/java/com/example/UserService.java", match!!.groupValues[1])
        assertEquals("45", match.groupValues[2])
    }

    @Test
    fun `MavenErrorPattern matches Kotlin file errors`() {
        val line = "[ERROR] /src/main/kotlin/Foo.kt:[10,5] unresolved reference: bar"
        val match = MavenOutputPatterns.FILE_ERROR_PATTERN.find(line)

        assertNotNull(match)
        assertEquals("/src/main/kotlin/Foo.kt", match!!.groupValues[1])
        assertEquals("10", match.groupValues[2])
    }

    @Test
    fun `MavenWarningPattern matches WARNING with file path`() {
        val line = "[WARNING] /src/main/java/Config.java:[12,8] unchecked conversion"
        val match = MavenOutputPatterns.FILE_WARNING_PATTERN.find(line)

        assertNotNull(match)
        assertEquals("/src/main/java/Config.java", match!!.groupValues[1])
        assertEquals("12", match.groupValues[2])
    }

    @Test
    fun `TestFailurePattern matches test class reference`() {
        val line = "  UserServiceTest.testCreateUser:42 expected: <true> but was: <false>"
        val match = MavenOutputPatterns.TEST_FAILURE_PATTERN.find(line)

        assertNotNull(match)
        assertEquals("UserServiceTest", match!!.groupValues[1])
        assertEquals("42", match.groupValues[2])
    }

    @Test
    fun `patterns do not match clean build output`() {
        val line = "[INFO] BUILD SUCCESS"

        assertNull(MavenOutputPatterns.FILE_ERROR_PATTERN.find(line))
        assertNull(MavenOutputPatterns.FILE_WARNING_PATTERN.find(line))
        assertNull(MavenOutputPatterns.TEST_FAILURE_PATTERN.find(line))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.maven.MavenOutputFiltersTest" -x verifyPluginStructure`
Expected: FAIL — class not found

- [ ] **Step 3: Implement MavenOutputPatterns and filter classes**

```kotlin
package com.workflow.orchestrator.core.maven

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * Regex patterns for parsing Maven console output.
 * Duplicated from BuildLogParser in :bamboo (core cannot depend on bamboo).
 * These match standard Maven compiler and Surefire plugin output formats.
 */
object MavenOutputPatterns {
    val FILE_ERROR_PATTERN = Regex(
        """\[ERROR]\s+(/\S+\.(?:java|kt|xml)):\[(\d+),\d+]\s+(.+)"""
    )
    val FILE_WARNING_PATTERN = Regex(
        """\[WARNING]\s+(/\S+\.(?:java|kt|xml)):\[(\d+),\d+]\s+(.+)"""
    )
    val TEST_FAILURE_PATTERN = Regex(
        """\s+(\w+Test\w*)\.\w+:(\d+)\s+(.+)"""
    )
}

class MavenErrorFilter(private val project: Project) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        return matchFilePattern(MavenOutputPatterns.FILE_ERROR_PATTERN, line, entireLength, project)
    }
}

class MavenWarningFilter(private val project: Project) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        return matchFilePattern(MavenOutputPatterns.FILE_WARNING_PATTERN, line, entireLength, project)
    }
}

class MavenTestFailureFilter(private val project: Project) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val match = MavenOutputPatterns.TEST_FAILURE_PATTERN.find(line) ?: return null
        val className = match.groupValues[1]
        val lineNum = match.groupValues[2].toIntOrNull() ?: return null

        // Search for test file by class name convention
        val basePath = project.basePath ?: return null
        val testFile = LocalFileSystem.getInstance().findFileByPath(basePath)
            ?.let { findTestFile(it, className) }
            ?: return null

        val startOffset = entireLength - line.length + match.range.first
        val endOffset = startOffset + match.value.length
        return Filter.Result(startOffset, endOffset, OpenFileHyperlinkInfo(project, testFile, lineNum - 1))
    }

    private fun findTestFile(root: com.intellij.openapi.vfs.VirtualFile, className: String): com.intellij.openapi.vfs.VirtualFile? {
        // Simple recursive search for ClassName.java or ClassName.kt in test dirs
        val queue = ArrayDeque<com.intellij.openapi.vfs.VirtualFile>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            for (child in dir.children ?: emptyArray()) {
                if (child.isDirectory) {
                    if (child.name != "build" && child.name != ".gradle") queue.add(child)
                } else if (child.nameWithoutExtension == className) {
                    return child
                }
            }
        }
        return null
    }
}

private fun matchFilePattern(
    pattern: Regex,
    line: String,
    entireLength: Int,
    project: Project
): Filter.Result? {
    val match = pattern.find(line) ?: return null
    val filePath = match.groupValues[1]
    val lineNum = match.groupValues[2].toIntOrNull() ?: return null
    val file = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return null

    val startOffset = entireLength - line.length + match.range.first
    val endOffset = startOffset + match.value.length
    return Filter.Result(startOffset, endOffset, OpenFileHyperlinkInfo(project, file, lineNum - 1))
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.maven.MavenOutputFiltersTest" -x verifyPluginStructure`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/maven/MavenOutputFilters.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/maven/MavenOutputFiltersTest.kt
git commit -m "feat(core): add Maven console output filters with clickable error links"
```

---

### Task 8: Create MavenConsoleManager

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/maven/MavenConsoleManager.kt`

> **Note:** MavenConsoleManager creates UI components (ConsoleView, RunContentDescriptor) that require full IDE infrastructure. Testing is limited to verifying the patterns in Task 7. The console manager itself is verified via manual testing.

- [ ] **Step 1: Implement MavenConsoleManager**

```kotlin
package com.workflow.orchestrator.core.maven

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.project.Project

class MavenConsoleManager(private val project: Project) {

    fun createAndRunConfiguration(
        name: String,
        goals: String,
        modules: List<String> = emptyList()
    ): RunContentDescriptor? {
        val commandLine = MavenBuildService.buildCommandLine(
            executable = MavenBuildService.detectMavenExecutable(
                project.basePath ?: return null
            ),
            goals = goals,
            modules = modules,
            projectBasePath = project.basePath ?: return null
        )

        val consoleBuilder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
        consoleBuilder.addFilter(MavenErrorFilter(project))
        consoleBuilder.addFilter(MavenWarningFilter(project))
        consoleBuilder.addFilter(MavenTestFailureFilter(project))
        val consoleView = consoleBuilder.console

        val processHandler = OSProcessHandler(commandLine)
        consoleView.attachToProcess(processHandler)

        val descriptor = RunContentDescriptor(
            consoleView, processHandler, consoleView.component, name
        )

        RunContentManager.getInstance(project).showRunContent(
            DefaultRunExecutor.getRunExecutorInstance(), descriptor
        )

        processHandler.startNotify()
        return descriptor
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew :core:compileKotlin -x verifyPluginStructure`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/maven/MavenConsoleManager.kt
git commit -m "feat(core): add MavenConsoleManager for Run tool window integration"
```

---

## Chunk 3: Health Check Implementations

### Task 9: Create MavenCompileCheck

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/healthcheck/checks/MavenCompileCheck.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/healthcheck/checks/MavenCompileCheckTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.core.healthcheck.checks

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.maven.MavenBuildResult
import com.workflow.orchestrator.core.maven.MavenBuildService
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MavenCompileCheckTest {

    @Test
    fun `id and order are correct`() {
        val check = MavenCompileCheck()
        assertEquals("maven-compile", check.id)
        assertEquals("Maven Compile", check.displayName)
        assertEquals(10, check.order)
    }

    @Test
    fun `isEnabled reads healthCheckCompileEnabled setting`() {
        val state = mockk<PluginSettings.State>()
        every { state.healthCheckCompileEnabled } returns true
        assertTrue(MavenCompileCheck().isEnabled(state))

        every { state.healthCheckCompileEnabled } returns false
        assertFalse(MavenCompileCheck().isEnabled(state))
    }

    @Test
    fun `execute returns passed when build succeeds`() = runTest {
        val project = mockk<Project>()
        val buildService = mockk<MavenBuildService>()
        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()

        every { state.healthCheckMavenGoals } returns "clean compile"

        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(project) } returns settings
        every { settings.state } returns state

        mockkObject(MavenBuildService)
        every { MavenBuildService.getInstance(project) } returns buildService
        coEvery { buildService.runBuild("compile", any()) } returns MavenBuildResult(
            success = true, exitCode = 0, output = "BUILD SUCCESS", errors = "", timedOut = false
        )

        val context = HealthCheckContext(project, emptyList(), "msg", "main")
        val result = MavenCompileCheck().execute(context)

        assertTrue(result.passed)
        assertTrue(result.message.contains("passed"))

        unmockkAll()
    }

    @Test
    fun `execute returns failed when build fails`() = runTest {
        val project = mockk<Project>()
        val buildService = mockk<MavenBuildService>()
        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()

        every { state.healthCheckMavenGoals } returns "clean compile"

        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(project) } returns settings
        every { settings.state } returns state

        mockkObject(MavenBuildService)
        every { MavenBuildService.getInstance(project) } returns buildService
        coEvery { buildService.runBuild("compile", any()) } returns MavenBuildResult(
            success = false, exitCode = 1, output = "[ERROR] compilation failed", errors = "", timedOut = false
        )

        val context = HealthCheckContext(project, emptyList(), "msg", "main")
        val result = MavenCompileCheck().execute(context)

        assertFalse(result.passed)
        assertTrue(result.message.contains("failed"))

        unmockkAll()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.healthcheck.checks.MavenCompileCheckTest" -x verifyPluginStructure`
Expected: FAIL — class not found

- [ ] **Step 3: Implement MavenCompileCheck**

```kotlin
package com.workflow.orchestrator.core.healthcheck.checks

import com.workflow.orchestrator.core.maven.MavenBuildService
import com.workflow.orchestrator.core.maven.MavenModuleDetector
import com.workflow.orchestrator.core.settings.PluginSettings

class MavenCompileCheck : HealthCheck {
    override val id = "maven-compile"
    override val displayName = "Maven Compile"
    override val order = 10

    override fun isEnabled(settings: PluginSettings.State): Boolean =
        settings.healthCheckCompileEnabled

    override suspend fun execute(context: HealthCheckContext): HealthCheck.CheckResult {
        val buildService = MavenBuildService.getInstance(context.project)
        val modules = MavenModuleDetector.detectChangedModules(context.changedFiles)
        val result = buildService.runBuild("compile", modules)

        return if (result.success) {
            HealthCheck.CheckResult(passed = true, message = "Maven compile passed")
        } else {
            HealthCheck.CheckResult(
                passed = false,
                message = "Maven compile failed (exit code ${result.exitCode})",
                details = result.output.lines().filter { it.contains("[ERROR]") }.take(10)
            )
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.healthcheck.checks.MavenCompileCheckTest" -x verifyPluginStructure`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/healthcheck/checks/MavenCompileCheck.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/healthcheck/checks/MavenCompileCheckTest.kt
git commit -m "feat(core): add MavenCompileCheck health check implementation"
```

---

### Task 10: Create MavenTestCheck

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/healthcheck/checks/MavenTestCheck.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/healthcheck/checks/MavenTestCheckTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.core.healthcheck.checks

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.maven.MavenBuildResult
import com.workflow.orchestrator.core.maven.MavenBuildService
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MavenTestCheckTest {

    @Test
    fun `id and order are correct`() {
        val check = MavenTestCheck()
        assertEquals("maven-test", check.id)
        assertEquals("Maven Test", check.displayName)
        assertEquals(20, check.order)
    }

    @Test
    fun `isEnabled reads healthCheckTestEnabled setting`() {
        val state = mockk<PluginSettings.State>()
        every { state.healthCheckTestEnabled } returns false
        assertFalse(MavenTestCheck().isEnabled(state))
    }

    @Test
    fun `execute returns passed when tests pass`() = runTest {
        val project = mockk<Project>()
        val buildService = mockk<MavenBuildService>()
        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()

        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(project) } returns settings
        every { settings.state } returns state

        mockkObject(MavenBuildService)
        every { MavenBuildService.getInstance(project) } returns buildService
        coEvery { buildService.runBuild("test", any()) } returns MavenBuildResult(
            success = true, exitCode = 0, output = "Tests run: 42, Failures: 0", errors = "", timedOut = false
        )

        val context = HealthCheckContext(project, emptyList(), "msg", "main")
        val result = MavenTestCheck().execute(context)

        assertTrue(result.passed)
        unmockkAll()
    }

    @Test
    fun `execute returns failed with test failure details`() = runTest {
        val project = mockk<Project>()
        val buildService = mockk<MavenBuildService>()
        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()

        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(project) } returns settings
        every { settings.state } returns state

        mockkObject(MavenBuildService)
        every { MavenBuildService.getInstance(project) } returns buildService
        coEvery { buildService.runBuild("test", any()) } returns MavenBuildResult(
            success = false, exitCode = 1,
            output = "[ERROR] Tests run: 10, Failures: 2\n[ERROR] FooTest.testBar FAILED",
            errors = "", timedOut = false
        )

        val context = HealthCheckContext(project, emptyList(), "msg", "main")
        val result = MavenTestCheck().execute(context)

        assertFalse(result.passed)
        assertTrue(result.details.isNotEmpty())
        unmockkAll()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.healthcheck.checks.MavenTestCheckTest" -x verifyPluginStructure`
Expected: FAIL — class not found

- [ ] **Step 3: Implement MavenTestCheck**

```kotlin
package com.workflow.orchestrator.core.healthcheck.checks

import com.workflow.orchestrator.core.maven.MavenBuildService
import com.workflow.orchestrator.core.maven.MavenModuleDetector
import com.workflow.orchestrator.core.settings.PluginSettings

class MavenTestCheck : HealthCheck {
    override val id = "maven-test"
    override val displayName = "Maven Test"
    override val order = 20

    override fun isEnabled(settings: PluginSettings.State): Boolean =
        settings.healthCheckTestEnabled

    override suspend fun execute(context: HealthCheckContext): HealthCheck.CheckResult {
        val buildService = MavenBuildService.getInstance(context.project)
        val modules = MavenModuleDetector.detectChangedModules(context.changedFiles)
        val result = buildService.runBuild("test", modules)

        return if (result.success) {
            HealthCheck.CheckResult(passed = true, message = "Maven tests passed")
        } else {
            HealthCheck.CheckResult(
                passed = false,
                message = "Maven tests failed (exit code ${result.exitCode})",
                details = result.output.lines().filter { it.contains("[ERROR]") }.take(10)
            )
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.healthcheck.checks.MavenTestCheckTest" -x verifyPluginStructure`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/healthcheck/checks/MavenTestCheck.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/healthcheck/checks/MavenTestCheckTest.kt
git commit -m "feat(core): add MavenTestCheck health check implementation"
```

---

### Task 11: Create CopyrightCheck and SonarGateCheck

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/healthcheck/checks/CopyrightCheck.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/healthcheck/checks/CopyrightCheckTest.kt`
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/healthcheck/checks/SonarGateCheck.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/healthcheck/checks/SonarGateCheckTest.kt`

- [ ] **Step 1: Write failing tests for CopyrightCheck**

```kotlin
package com.workflow.orchestrator.core.healthcheck.checks

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CopyrightCheckTest {

    @Test
    fun `isEnabled returns false when copyright disabled`() {
        val state = mockk<PluginSettings.State>()
        every { state.healthCheckCopyrightEnabled } returns false
        every { state.copyrightHeaderPattern } returns "Copyright"
        assertFalse(CopyrightCheck().isEnabled(state))
    }

    @Test
    fun `isEnabled returns false when pattern is blank`() {
        val state = mockk<PluginSettings.State>()
        every { state.healthCheckCopyrightEnabled } returns true
        every { state.copyrightHeaderPattern } returns ""
        assertFalse(CopyrightCheck().isEnabled(state))
    }

    @Test
    fun `execute passes when all files have copyright`() = runTest {
        val project = mockk<Project>()
        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()
        every { state.copyrightHeaderPattern } returns """Copyright"""

        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(project) } returns settings
        every { settings.state } returns state

        val file = mockk<VirtualFile>()
        every { file.name } returns "Foo.kt"
        every { file.extension } returns "kt"
        every { file.contentsToByteArray() } returns "// Copyright 2026\npackage foo\n".toByteArray()

        val context = HealthCheckContext(project, listOf(file), "msg", "main")
        val result = CopyrightCheck().execute(context)

        assertTrue(result.passed)
        unmockkAll()
    }

    @Test
    fun `execute fails when files missing copyright`() = runTest {
        val project = mockk<Project>()
        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()
        every { state.copyrightHeaderPattern } returns """Copyright"""

        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(project) } returns settings
        every { settings.state } returns state

        val file = mockk<VirtualFile>()
        every { file.name } returns "Bar.kt"
        every { file.extension } returns "kt"
        every { file.contentsToByteArray() } returns "package bar\nclass Bar {}\n".toByteArray()

        val context = HealthCheckContext(project, listOf(file), "msg", "main")
        val result = CopyrightCheck().execute(context)

        assertFalse(result.passed)
        assertEquals(1, result.details.size)
        assertTrue(result.details[0].contains("Bar.kt"))
        unmockkAll()
    }
}
```

- [ ] **Step 2: Write failing tests for SonarGateCheck**

```kotlin
package com.workflow.orchestrator.core.healthcheck.checks

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SonarGateCheckTest {

    @Test
    fun `isEnabled reads healthCheckSonarGateEnabled setting`() {
        val state = mockk<PluginSettings.State>()
        every { state.healthCheckSonarGateEnabled } returns true
        assertTrue(SonarGateCheck().isEnabled(state))

        every { state.healthCheckSonarGateEnabled } returns false
        assertFalse(SonarGateCheck().isEnabled(state))
    }

    @Test
    fun `execute returns passed when quality gate passed`() = runTest {
        val project = mockk<Project>()
        val eventBus = EventBus()

        mockkObject(EventBus)
        every { EventBus.getInstance(project) } returns eventBus

        // Simulate a cached quality gate result by emitting before check runs
        // SonarGateCheck should read the last known state
        val check = SonarGateCheck()
        check.setLastKnownGateStatus(true)

        val context = HealthCheckContext(project, emptyList(), "msg", "main")
        val result = check.execute(context)

        assertTrue(result.passed)
        unmockkAll()
    }

    @Test
    fun `execute returns failed when quality gate failed`() = runTest {
        val project = mockk<Project>()

        val check = SonarGateCheck()
        check.setLastKnownGateStatus(false)

        val context = HealthCheckContext(project, emptyList(), "msg", "main")
        val result = check.execute(context)

        assertFalse(result.passed)
        assertTrue(result.message.contains("failed"))
        unmockkAll()
    }

    @Test
    fun `execute skips when no quality gate data available`() = runTest {
        val project = mockk<Project>()

        val check = SonarGateCheck()
        // No setLastKnownGateStatus called — null state

        val context = HealthCheckContext(project, emptyList(), "msg", "main")
        val result = check.execute(context)

        assertTrue(result.passed)
        assertTrue(result.message.contains("no cached"))
        unmockkAll()
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.healthcheck.checks.*" -x verifyPluginStructure`
Expected: FAIL — classes not found

- [ ] **Step 4: Implement CopyrightCheck**

```kotlin
package com.workflow.orchestrator.core.healthcheck.checks

import com.workflow.orchestrator.core.copyright.CopyrightCheckService
import com.workflow.orchestrator.core.settings.PluginSettings

class CopyrightCheck : HealthCheck {
    override val id = "copyright"
    override val displayName = "Copyright Headers"
    override val order = 30

    override fun isEnabled(settings: PluginSettings.State): Boolean =
        settings.healthCheckCopyrightEnabled && settings.copyrightHeaderPattern.isNotBlank()

    override suspend fun execute(context: HealthCheckContext): HealthCheck.CheckResult {
        val settings = PluginSettings.getInstance(context.project).state
        val result = CopyrightCheckService.checkFiles(context.changedFiles, settings.copyrightHeaderPattern)

        return HealthCheck.CheckResult(
            passed = result.passed,
            message = if (result.passed) "All files have copyright headers"
                     else "${result.violations.size} file(s) missing copyright header",
            details = result.violations.map { "${it.fileName}: ${it.reason}" }
        )
    }
}
```

- [ ] **Step 5: Implement SonarGateCheck**

```kotlin
package com.workflow.orchestrator.core.healthcheck.checks

import com.workflow.orchestrator.core.settings.PluginSettings
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads the last known Sonar quality gate status.
 * Status is updated by Phase 1D's SonarDataService via EventBus — specifically,
 * when a QualityGateResult event is emitted, HealthCheckService subscribes
 * to EventBus and calls setLastKnownGateStatus() on this check.
 * This check does NOT trigger a new Sonar analysis — it reads the cached result.
 *
 * Note: The EventBus subscription wiring is done in HealthCheckService's init block.
 * Phase 1D must emit WorkflowEvent.QualityGateResult for this to work.
 * If Phase 1D is not yet implemented, this check gracefully returns "no cached data".
 */
class SonarGateCheck : HealthCheck {
    override val id = "sonar-gate"
    override val displayName = "Sonar Quality Gate"
    override val order = 40

    // null = no data, true = passed, false = failed
    private val lastKnownStatus = AtomicReference<Boolean?>(null)

    fun setLastKnownGateStatus(passed: Boolean?) {
        lastKnownStatus.set(passed)
    }

    override fun isEnabled(settings: PluginSettings.State): Boolean =
        settings.healthCheckSonarGateEnabled

    override suspend fun execute(context: HealthCheckContext): HealthCheck.CheckResult {
        val status = lastKnownStatus.get()

        return when (status) {
            null -> HealthCheck.CheckResult(
                passed = true,
                message = "Sonar quality gate: no cached data (skipped)"
            )
            true -> HealthCheck.CheckResult(
                passed = true,
                message = "Sonar quality gate passed"
            )
            false -> HealthCheck.CheckResult(
                passed = false,
                message = "Sonar quality gate failed"
            )
        }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.healthcheck.checks.*" -x verifyPluginStructure`
Expected: PASS (7 tests across CopyrightCheckTest + SonarGateCheckTest)

- [ ] **Step 7: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/healthcheck/checks/CopyrightCheck.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/healthcheck/checks/CopyrightCheckTest.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/healthcheck/checks/SonarGateCheck.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/healthcheck/checks/SonarGateCheckTest.kt
git commit -m "feat(core): add CopyrightCheck and SonarGateCheck health check implementations"
```

---

### Task 12: Create HealthCheckService

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/healthcheck/HealthCheckService.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/healthcheck/HealthCheckServiceTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.core.healthcheck

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.healthcheck.checks.HealthCheck
import com.workflow.orchestrator.core.healthcheck.checks.HealthCheckContext
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class HealthCheckServiceTest {

    @AfterEach
    fun cleanup() {
        unmockkAll()
    }

    private fun mockSettingsAndBus(
        enabled: Boolean = true,
        blockingMode: String = "hard",
        skipPattern: String = ""
    ): Pair<Project, EventBus> {
        val project = mockk<Project>()
        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()
        val eventBus = mockk<EventBus>(relaxed = true)

        every { state.healthCheckEnabled } returns enabled
        every { state.healthCheckBlockingMode } returns blockingMode
        every { state.healthCheckSkipBranchPattern } returns skipPattern
        every { state.healthCheckTimeoutSeconds } returns 10

        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(project) } returns settings
        every { settings.state } returns state

        mockkObject(EventBus)
        every { EventBus.getInstance(project) } returns eventBus

        return Pair(project, eventBus)
    }

    private fun passingCheck(id: String, order: Int): HealthCheck {
        val check = mockk<HealthCheck>()
        every { check.id } returns id
        every { check.displayName } returns id
        every { check.order } returns order
        every { check.isEnabled(any()) } returns true
        coEvery { check.execute(any()) } returns HealthCheck.CheckResult(true, "passed")
        return check
    }

    private fun failingCheck(id: String, order: Int): HealthCheck {
        val check = mockk<HealthCheck>()
        every { check.id } returns id
        every { check.displayName } returns id
        every { check.order } returns order
        every { check.isEnabled(any()) } returns true
        coEvery { check.execute(any()) } returns HealthCheck.CheckResult(false, "failed", listOf("detail"))
        return check
    }

    @Test
    fun `returns skipped when health check disabled`() = runTest {
        val (project, _) = mockSettingsAndBus(enabled = false)
        val service = HealthCheckService(project).withChecks(listOf(passingCheck("a", 1)))
        val context = HealthCheckContext(project, emptyList(), "msg", "main")

        val result = service.runChecks(context)

        assertTrue(result.skipped)
        assertTrue(result.passed)
    }

    @Test
    fun `returns skipped when branch matches skip pattern`() = runTest {
        val (project, _) = mockSettingsAndBus(skipPattern = "hotfix/.*")
        val service = HealthCheckService(project).withChecks(listOf(passingCheck("a", 1)))
        val context = HealthCheckContext(project, emptyList(), "msg", "hotfix/urgent-fix")

        val result = service.runChecks(context)

        assertTrue(result.skipped)
        assertTrue(result.message.contains("skip pattern") ?: false || result.skipReason?.contains("skip") == true)
    }

    @Test
    fun `runs all enabled checks and returns passed`() = runTest {
        val (project, eventBus) = mockSettingsAndBus()
        val checks = listOf(passingCheck("a", 1), passingCheck("b", 2))
        val service = HealthCheckService(project).withChecks(checks)
        val context = HealthCheckContext(project, emptyList(), "msg", "main")

        val result = service.runChecks(context)

        assertTrue(result.passed)
        assertEquals(2, result.checkResults.size)
        assertTrue(result.checkResults.values.all { it.passed })
    }

    @Test
    fun `returns failed when any check fails`() = runTest {
        val (project, eventBus) = mockSettingsAndBus()
        val checks = listOf(passingCheck("a", 1), failingCheck("b", 2))
        val service = HealthCheckService(project).withChecks(checks)
        val context = HealthCheckContext(project, emptyList(), "msg", "main")

        val result = service.runChecks(context)

        assertFalse(result.passed)
        assertTrue(result.checkResults["a"]!!.passed)
        assertFalse(result.checkResults["b"]!!.passed)
    }

    @Test
    fun `skips disabled checks`() = runTest {
        val (project, _) = mockSettingsAndBus()
        val disabledCheck = mockk<HealthCheck>()
        every { disabledCheck.id } returns "disabled"
        every { disabledCheck.isEnabled(any()) } returns false

        val service = HealthCheckService(project).withChecks(listOf(passingCheck("a", 1), disabledCheck))
        val context = HealthCheckContext(project, emptyList(), "msg", "main")

        val result = service.runChecks(context)

        assertTrue(result.passed)
        assertEquals(1, result.checkResults.size)
        assertFalse(result.checkResults.containsKey("disabled"))
    }

    @Test
    fun `emits HealthCheckStarted and HealthCheckFinished events`() = runTest {
        val (project, eventBus) = mockSettingsAndBus()
        val service = HealthCheckService(project).withChecks(listOf(passingCheck("a", 1)))
        val context = HealthCheckContext(project, emptyList(), "msg", "main")

        service.runChecks(context)

        coVerify(exactly = 1) { eventBus.emit(match { it is com.workflow.orchestrator.core.events.WorkflowEvent.HealthCheckStarted }) }
        coVerify(exactly = 1) { eventBus.emit(match { it is com.workflow.orchestrator.core.events.WorkflowEvent.HealthCheckFinished }) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.healthcheck.HealthCheckServiceTest" -x verifyPluginStructure`
Expected: FAIL — class not found

- [ ] **Step 3: Implement HealthCheckService**

```kotlin
package com.workflow.orchestrator.core.healthcheck

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.healthcheck.checks.HealthCheck
import com.workflow.orchestrator.core.healthcheck.checks.HealthCheckContext
import com.workflow.orchestrator.core.healthcheck.checks.MavenCompileCheck
import com.workflow.orchestrator.core.healthcheck.checks.MavenTestCheck
import com.workflow.orchestrator.core.healthcheck.checks.CopyrightCheck
import com.workflow.orchestrator.core.healthcheck.checks.SonarGateCheck
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.withTimeoutOrNull

@Service(Service.Level.PROJECT)
class HealthCheckService(private val project: Project) {

    // IntelliJ @Service requires a single-arg (Project) constructor.
    // The checks list is internal; tests override via the @VisibleForTesting constructor below.
    private var checks: List<HealthCheck> = listOf(
        MavenCompileCheck(),
        MavenTestCheck(),
        CopyrightCheck(),
        SonarGateCheck()
    )

    // Wire SonarGateCheck to EventBus so it receives quality gate updates from Phase 1D.
    // If Phase 1D is not deployed yet, the check gracefully returns "no cached data".
    val sonarGateCheck: SonarGateCheck?
        get() = checks.filterIsInstance<SonarGateCheck>().firstOrNull()

    /** @VisibleForTesting — allows injecting mock checks in tests. */
    internal fun withChecks(checks: List<HealthCheck>): HealthCheckService {
        this.checks = checks
        return this
    }

    suspend fun runChecks(context: HealthCheckContext): HealthCheckResult {
        val settings = PluginSettings.getInstance(project).state
        if (!settings.healthCheckEnabled) return HealthCheckResult.skipped()

        val branch = context.branch
        val skipPattern = settings.healthCheckSkipBranchPattern
        if (!skipPattern.isNullOrBlank() && Regex(skipPattern).matches(branch)) {
            return HealthCheckResult.skipped("Branch matches skip pattern")
        }

        val enabledChecks = checks.filter { it.isEnabled(settings) }

        EventBus.getInstance(project).emit(
            WorkflowEvent.HealthCheckStarted(checks = enabledChecks.map { it.id })
        )

        val startTime = System.currentTimeMillis()
        val results = mutableMapOf<String, HealthCheck.CheckResult>()

        for (check in enabledChecks) {
            val result = withTimeoutOrNull(settings.healthCheckTimeoutSeconds * 1000L) {
                check.execute(context)
            } ?: HealthCheck.CheckResult(
                passed = false,
                message = "${check.displayName} timed out after ${settings.healthCheckTimeoutSeconds}s"
            )
            results[check.id] = result
        }

        val passed = results.values.all { it.passed }
        val durationMs = System.currentTimeMillis() - startTime

        EventBus.getInstance(project).emit(
            WorkflowEvent.HealthCheckFinished(
                passed = passed,
                results = results.mapValues { it.value.passed },
                durationMs = durationMs
            )
        )

        return HealthCheckResult(passed, results, durationMs = durationMs)
    }

    companion object {
        fun getInstance(project: Project): HealthCheckService =
            project.getService(HealthCheckService::class.java)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.healthcheck.HealthCheckServiceTest" -x verifyPluginStructure`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/healthcheck/HealthCheckService.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/healthcheck/HealthCheckServiceTest.kt
git commit -m "feat(core): add HealthCheckService orchestrating all pluggable checks"
```

---

## Chunk 4: VCS Commit Dialog Integration

### Task 13: Create HealthCheckCheckinHandlerFactory

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/healthcheck/HealthCheckCheckinHandlerFactory.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/healthcheck/HealthCheckCheckinHandlerFactoryTest.kt`

- [ ] **Step 1: Write failing tests**

Tests focus on the `beforeCheckin()` logic for each blocking mode. The CheckinProjectPanel is mocked.

```kotlin
package com.workflow.orchestrator.core.healthcheck

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class HealthCheckCheckinHandlerFactoryTest {

    @AfterEach
    fun cleanup() = unmockkAll()

    private fun setup(
        enabled: Boolean = true,
        mode: String = "hard"
    ): Triple<Project, CheckinProjectPanel, CommitContext> {
        val project = mockk<Project>(relaxed = true)
        val panel = mockk<CheckinProjectPanel>(relaxed = true)
        val commitContext = mockk<CommitContext>(relaxed = true)
        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()

        every { panel.project } returns project
        every { panel.commitMessage } returns "test commit"
        every { panel.selectedChanges } returns emptyList()

        every { state.healthCheckEnabled } returns enabled
        every { state.healthCheckBlockingMode } returns mode

        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(project) } returns settings
        every { settings.state } returns state

        return Triple(project, panel, commitContext)
    }

    @Test
    fun `returns COMMIT when health check disabled`() {
        val (_, panel, commitContext) = setup(enabled = false)
        val handler = HealthCheckCheckinHandler(panel, commitContext)

        val result = handler.beforeCheckin()

        assertEquals(CheckinHandler.ReturnResult.COMMIT, result)
    }

    @Test
    fun `returns COMMIT when blocking mode is off`() {
        val (_, panel, commitContext) = setup(mode = "off")
        val handler = HealthCheckCheckinHandler(panel, commitContext)

        val result = handler.beforeCheckin()

        assertEquals(CheckinHandler.ReturnResult.COMMIT, result)
    }
}
```

> **Note:** Full integration tests (with ProgressManager and HealthCheckService) require IntelliJ platform test infrastructure. The tests above verify the early-exit paths. Integration with the actual health check flow is verified via manual testing.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.healthcheck.HealthCheckCheckinHandlerFactoryTest" -x verifyPluginStructure`
Expected: FAIL — class not found

- [ ] **Step 3: Implement HealthCheckCheckinHandlerFactory**

```kotlin
package com.workflow.orchestrator.core.healthcheck

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.VcsCheckinHandlerFactory
import com.workflow.orchestrator.core.healthcheck.checks.HealthCheckContext
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.settings.PluginSettings
import git4idea.GitVcs
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.runBlocking

class HealthCheckCheckinHandlerFactory : VcsCheckinHandlerFactory(GitVcs.getKey()) {

    override fun createVcsHandler(
        panel: CheckinProjectPanel,
        commitContext: CommitContext
    ): CheckinHandler {
        return HealthCheckCheckinHandler(panel, commitContext)
    }
}

class HealthCheckCheckinHandler(
    private val panel: CheckinProjectPanel,
    private val commitContext: CommitContext
) : CheckinHandler() {

    // Uses no-arg beforeCheckin() — the parameterized overload is deprecated in IntelliJ 2025.1.
    // Matches the existing CommitMessagePrefixHandler pattern in :jira.
    override fun beforeCheckin(): ReturnResult {
        val project = panel.project
        val settings = PluginSettings.getInstance(project).state
        val mode = settings.healthCheckBlockingMode

        if (!settings.healthCheckEnabled || mode == "off") return ReturnResult.COMMIT

        val currentBranch = GitRepositoryManager.getInstance(project)
            .repositories.firstOrNull()?.currentBranchName ?: ""

        val changedFiles = panel.selectedChanges
            .mapNotNull { it.virtualFile }

        val context = HealthCheckContext(
            project = project,
            changedFiles = changedFiles,
            commitMessage = panel.commitMessage ?: "",
            branch = currentBranch
        )

        var healthResult: HealthCheckResult? = null
        ProgressManager.getInstance().run(object : Task.Modal(
            project, "Running Health Checks...", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                healthResult = runBlocking {
                    HealthCheckService.getInstance(project).runChecks(context)
                }
            }
        })

        val result = healthResult ?: return ReturnResult.COMMIT
        if (result.skipped || result.passed) return ReturnResult.COMMIT

        val failedChecks = result.checkResults
            .filter { !it.value.passed }
            .map { "${it.key}: ${it.value.message}" }
        val summary = failedChecks.joinToString("\n")

        return when (mode) {
            "hard" -> {
                WorkflowNotificationService.getInstance(project).notifyError(
                    "workflow.healthcheck",
                    "Health Check Failed",
                    summary
                )
                ReturnResult.CANCEL
            }
            "soft" -> {
                WorkflowNotificationService.getInstance(project).notifyWarning(
                    "workflow.healthcheck",
                    "Health Check Warning",
                    "$summary\n\nCommit proceeding with warnings."
                )
                ReturnResult.COMMIT
            }
            else -> ReturnResult.COMMIT
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.healthcheck.HealthCheckCheckinHandlerFactoryTest" -x verifyPluginStructure`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/healthcheck/HealthCheckCheckinHandlerFactory.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/healthcheck/HealthCheckCheckinHandlerFactoryTest.kt
git commit -m "feat(core): add HealthCheckCheckinHandlerFactory for VCS commit dialog"
```

---

### Task 14: Create Health Check Settings UI

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/HealthCheckConfigurable.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/WorkflowSettingsConfigurable.kt`

- [ ] **Step 1: Create HealthCheckConfigurable**

```kotlin
package com.workflow.orchestrator.core.settings

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.*

class HealthCheckConfigurable(
    private val project: Project
) : BoundSearchableConfigurable("Health Check", "workflow.orchestrator.healthcheck") {

    private val settings = PluginSettings.getInstance(project)

    override fun createPanel() = panel {
        group("Health Check Gate") {
            row {
                checkBox("Enable health checks on commit")
                    .bindSelected(settings.state::healthCheckEnabled)
            }
            row("Blocking mode:") {
                comboBox(listOf("hard", "soft", "off"))
                    .bindItem(
                        getter = { settings.state.healthCheckBlockingMode },
                        setter = { settings.state.healthCheckBlockingMode = it ?: "soft" }
                    )
                    .comment("hard = block commit, soft = warn only, off = disabled")
            }
            group("Checks") {
                row {
                    checkBox("Maven compile")
                        .bindSelected(settings.state::healthCheckCompileEnabled)
                }
                row {
                    checkBox("Maven test")
                        .bindSelected(settings.state::healthCheckTestEnabled)
                }
                row {
                    checkBox("Copyright headers")
                        .bindSelected(settings.state::healthCheckCopyrightEnabled)
                }
                row {
                    checkBox("Sonar quality gate (uses cached status)")
                        .bindSelected(settings.state::healthCheckSonarGateEnabled)
                }
                row {
                    checkBox("CVE annotations in pom.xml")
                        .bindSelected(settings.state::healthCheckCveEnabled)
                }
            }
            row("Maven goals:") {
                textField()
                    .columns(30)
                    .bindText(settings.state::healthCheckMavenGoals)
            }
            row("Skip for branches (regex):") {
                textField()
                    .columns(30)
                    .bindText(settings.state::healthCheckSkipBranchPattern)
                    .comment("e.g., hotfix/.* — leave blank to run on all branches")
            }
            row("Timeout (seconds):") {
                intTextField(range = 10..3600)
                    .bindIntText(settings.state::healthCheckTimeoutSeconds)
            }
        }
        group("Copyright") {
            row("Header pattern (regex):") {
                textField()
                    .columns(40)
                    .bindText(settings.state::copyrightHeaderPattern)
                    .comment("e.g., Copyright \\(c\\) \\d{4} MyCompany")
            }
        }
    }
}
```

- [ ] **Step 2: Add HealthCheckConfigurable to WorkflowSettingsConfigurable**

In `WorkflowSettingsConfigurable.kt`, update `getConfigurables()`:

```kotlin
override fun getConfigurables(): Array<Configurable> {
    return arrayOf(
        ConnectionsConfigurable(project),
        HealthCheckConfigurable(project)
    )
}
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew :core:compileKotlin -x verifyPluginStructure`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/HealthCheckConfigurable.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/settings/WorkflowSettingsConfigurable.kt
git commit -m "feat(core): add Health Check settings UI under Workflow Orchestrator"
```

---

### Task 15: Register Core Health Check Components in plugin.xml

**Files:**
- Modify: `core/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Add health check registrations**

Add inside the `<extensions defaultExtensionNs="com.intellij">` block, after the existing status bar widgets:

```xml
<!-- Health Check -->
<vcsCheckinHandlerFactory
    implementation="com.workflow.orchestrator.core.healthcheck.HealthCheckCheckinHandlerFactory"/>
<projectService
    serviceImplementation="com.workflow.orchestrator.core.healthcheck.HealthCheckService"/>
<projectService
    serviceImplementation="com.workflow.orchestrator.core.maven.MavenBuildService"/>
<notificationGroup id="workflow.healthcheck" displayType="BALLOON"/>
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew :core:compileKotlin -x verifyPluginStructure`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/resources/META-INF/plugin.xml
git commit -m "feat(core): register health check services and checkin handler in plugin.xml"
```

---

## Chunk 5: CVE Auto-Bumper (Bamboo Module)

### Task 16: Create CveRemediationService

**Files:**
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/CveRemediationService.kt`
- Create: `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/CveRemediationServiceTest.kt`
- Create: `bamboo/src/test/resources/fixtures/cve-build-log.txt`

- [ ] **Step 1: Create test fixture file**

Create `bamboo/src/test/resources/fixtures/cve-build-log.txt`:

```
[INFO] --- dependency-check-maven:8.4.3:check (default) @ my-app ---
[WARNING] One or more dependencies were identified with known vulnerabilities:
[WARNING]   io.netty:netty-codec-http2:4.1.93.Final  CVE-2023-44487 (CRITICAL) - HTTP/2 Rapid Reset Attack
[WARNING]   com.fasterxml.jackson.core:jackson-databind:2.14.0  CVE-2022-42003 (HIGH) - Deeply nested JSON DoS
[WARNING]   org.apache.commons:commons-text:1.9  CVE-2022-42889 (CRITICAL) - Apache Commons Text RCE
[INFO] BUILD SUCCESS
```

- [ ] **Step 2: Write failing tests**

```kotlin
package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.service.CveRemediationService.CveSeverity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CveRemediationServiceTest {

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @Test
    fun `parses CVE entries from OWASP dependency-check output`() {
        val log = fixture("cve-build-log.txt")

        val vulns = CveRemediationService.parseFromBuildLog(log)

        assertEquals(3, vulns.size)
    }

    @Test
    fun `extracts CVE ID, groupId, artifactId, version, severity`() {
        val log = fixture("cve-build-log.txt")

        val vulns = CveRemediationService.parseFromBuildLog(log)
        val nettyVuln = vulns.find { it.cveId == "CVE-2023-44487" }

        assertNotNull(nettyVuln)
        assertEquals("io.netty", nettyVuln!!.groupId)
        assertEquals("netty-codec-http2", nettyVuln.artifactId)
        assertEquals("4.1.93.Final", nettyVuln.currentVersion)
        assertEquals(CveSeverity.CRITICAL, nettyVuln.severity)
        assertTrue(nettyVuln.description.contains("HTTP/2"))
    }

    @Test
    fun `parses HIGH severity correctly`() {
        val log = fixture("cve-build-log.txt")

        val vulns = CveRemediationService.parseFromBuildLog(log)
        val jacksonVuln = vulns.find { it.artifactId == "jackson-databind" }

        assertNotNull(jacksonVuln)
        assertEquals(CveSeverity.HIGH, jacksonVuln!!.severity)
    }

    @Test
    fun `returns empty list for clean build log`() {
        val log = """
            [INFO] BUILD SUCCESS
            [INFO] Total time: 5 s
        """.trimIndent()

        val vulns = CveRemediationService.parseFromBuildLog(log)

        assertTrue(vulns.isEmpty())
    }

    @Test
    fun `handles build log with no dependency-check output`() {
        val log = """
            [ERROR] Failed to execute goal
            [WARNING] Some other warning
        """.trimIndent()

        val vulns = CveRemediationService.parseFromBuildLog(log)

        assertTrue(vulns.isEmpty())
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.service.CveRemediationServiceTest" -x verifyPluginStructure`
Expected: FAIL — class not found

- [ ] **Step 4: Implement CveRemediationService**

```kotlin
package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Service(Service.Level.PROJECT)
class CveRemediationService(private val project: Project) {

    data class CveVulnerability(
        val cveId: String,
        val groupId: String,
        val artifactId: String,
        val currentVersion: String,
        val fixedVersion: String? = null,
        val severity: CveSeverity,
        val description: String
    )

    enum class CveSeverity { CRITICAL, HIGH, MEDIUM, LOW }

    private val _vulnerabilities = MutableStateFlow<List<CveVulnerability>>(emptyList())
    val vulnerabilities: StateFlow<List<CveVulnerability>> = _vulnerabilities.asStateFlow()

    fun updateFromBuildLog(log: String) {
        _vulnerabilities.value = parseFromBuildLog(log)
    }

    companion object {
        // Pattern: "[WARNING]   groupId:artifactId:version  CVE-XXXX-XXXXX (SEVERITY) - description"
        private val CVE_PATTERN = Regex(
            """\[WARNING]\s+([\w.]+):([\w\-]+):([\w.\-]+)\s+(CVE-\d{4}-\d+)\s+\((\w+)\)\s*-?\s*(.*)"""
        )

        fun parseFromBuildLog(log: String): List<CveVulnerability> {
            val vulnerabilities = mutableListOf<CveVulnerability>()

            for (line in log.lines()) {
                val match = CVE_PATTERN.find(line) ?: continue
                val (groupId, artifactId, version, cveId, severityStr, description) = match.destructured

                val severity = try {
                    CveSeverity.valueOf(severityStr.uppercase())
                } catch (_: IllegalArgumentException) {
                    CveSeverity.MEDIUM
                }

                vulnerabilities.add(
                    CveVulnerability(
                        cveId = cveId,
                        groupId = groupId,
                        artifactId = artifactId,
                        currentVersion = version,
                        severity = severity,
                        description = description.trim()
                    )
                )
            }

            return vulnerabilities
        }

        fun getInstance(project: Project): CveRemediationService =
            project.getService(CveRemediationService::class.java)
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.service.CveRemediationServiceTest" -x verifyPluginStructure`
Expected: PASS (5 tests)

- [ ] **Step 6: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/CveRemediationService.kt \
        bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/CveRemediationServiceTest.kt \
        bamboo/src/test/resources/fixtures/cve-build-log.txt
git commit -m "feat(bamboo): add CveRemediationService for CVE build log parsing"
```

---

### Task 17: Create CveIntentionAction

**Files:**
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/editor/CveIntentionAction.kt`
- Create: `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/editor/CveIntentionActionTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.bamboo.editor

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import com.workflow.orchestrator.bamboo.service.CveRemediationService
import com.workflow.orchestrator.bamboo.service.CveRemediationService.CveSeverity
import com.workflow.orchestrator.bamboo.service.CveRemediationService.CveVulnerability
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class CveIntentionActionTest {

    @AfterEach
    fun cleanup() = unmockkAll()

    @Test
    fun `getText returns bump message`() {
        assertEquals("Bump to fix CVE vulnerability", CveIntentionAction().text)
    }

    @Test
    fun `getFamilyName returns CVE category`() {
        assertEquals("Workflow Orchestrator CVE", CveIntentionAction().familyName)
    }

    @Test
    fun `isAvailable returns false for non-XML files`() {
        val project = mockk<Project>()
        val editor = mockk<Editor>()
        val file = mockk<PsiFile>()
        every { file.name } returns "Foo.kt"

        assertFalse(CveIntentionAction().isAvailable(project, editor, file))
    }

    @Test
    fun `isAvailable returns false for non-pom XML files`() {
        val project = mockk<Project>()
        val editor = mockk<Editor>()
        val file = mockk<XmlFile>()
        every { file.name } returns "web.xml"

        assertFalse(CveIntentionAction().isAvailable(project, editor, file))
    }
}
```

> **Note:** Full `isAvailable()` tests with PSI navigation require `BasePlatformTestCase`. The tests above verify the early-exit paths. PSI-based tests are deferred to integration testing.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.editor.CveIntentionActionTest" -x verifyPluginStructure`
Expected: FAIL — class not found

- [ ] **Step 3: Implement CveIntentionAction**

```kotlin
package com.workflow.orchestrator.bamboo.editor

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.workflow.orchestrator.bamboo.service.CveRemediationService
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService

class CveIntentionAction : IntentionAction, PriorityAction {

    override fun getText() = "Bump to fix CVE vulnerability"
    override fun getFamilyName() = "Workflow Orchestrator CVE"
    override fun getPriority() = PriorityAction.Priority.HIGH
    override fun startInWriteAction() = false

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        if (file !is XmlFile || file.name != "pom.xml") return false

        val element = file.findElementAt(editor.caretModel.offset) ?: return false
        val dependencyTag = findDependencyTag(element) ?: return false

        val groupId = dependencyTag.findFirstSubTag("groupId")?.value?.text ?: return false
        val artifactId = dependencyTag.findFirstSubTag("artifactId")?.value?.text ?: return false

        return CveRemediationService.getInstance(project)
            .vulnerabilities.value
            .any { it.groupId == groupId && it.artifactId == artifactId && it.fixedVersion != null }
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val element = file.findElementAt(editor.caretModel.offset) ?: return
        val dependencyTag = findDependencyTag(element) ?: return
        val groupId = dependencyTag.findFirstSubTag("groupId")?.value?.text ?: return
        val artifactId = dependencyTag.findFirstSubTag("artifactId")?.value?.text ?: return

        val cve = CveRemediationService.getInstance(project)
            .vulnerabilities.value
            .find { it.groupId == groupId && it.artifactId == artifactId && it.fixedVersion != null }
            ?: return

        val versionTag = findVersionTag(dependencyTag, file as XmlFile) ?: return

        WriteCommandAction.runWriteCommandAction(project, "Bump CVE Dependency", null, {
            versionTag.value.text = cve.fixedVersion!!
        })

        WorkflowNotificationService.getInstance(project).notifyInfo(
            "workflow.quality",
            "CVE Fixed",
            "Bumped $artifactId ${cve.currentVersion} → ${cve.fixedVersion} (fixes ${cve.cveId})"
        )
    }

    private fun findDependencyTag(element: com.intellij.psi.PsiElement): XmlTag? {
        var tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java)
        while (tag != null) {
            if (tag.name == "dependency") return tag
            tag = tag.parentTag
        }
        return null
    }

    private fun findVersionTag(dependencyTag: XmlTag, xmlFile: XmlFile): XmlTag? {
        // Direct <version> under <dependency>
        val directVersion = dependencyTag.findFirstSubTag("version")
        if (directVersion != null) {
            val text = directVersion.value.text
            // Check for property reference like ${foo.version}
            if (text.startsWith("\${") && text.endsWith("}")) {
                val propertyName = text.substring(2, text.length - 1)
                return findPropertyTag(xmlFile, propertyName)
            }
            return directVersion
        }

        // Look in <dependencyManagement> section
        val rootTag = xmlFile.rootTag ?: return null
        val depMgmt = rootTag.findFirstSubTag("dependencyManagement") ?: return null
        val deps = depMgmt.findFirstSubTag("dependencies") ?: return null
        val groupId = dependencyTag.findFirstSubTag("groupId")?.value?.text
        val artifactId = dependencyTag.findFirstSubTag("artifactId")?.value?.text

        for (dep in deps.findSubTags("dependency")) {
            if (dep.findFirstSubTag("groupId")?.value?.text == groupId &&
                dep.findFirstSubTag("artifactId")?.value?.text == artifactId) {
                return dep.findFirstSubTag("version")
            }
        }

        return null
    }

    private fun findPropertyTag(xmlFile: XmlFile, propertyName: String): XmlTag? {
        val properties = xmlFile.rootTag?.findFirstSubTag("properties") ?: return null
        return properties.findFirstSubTag(propertyName)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.editor.CveIntentionActionTest" -x verifyPluginStructure`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/editor/CveIntentionAction.kt \
        bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/editor/CveIntentionActionTest.kt
git commit -m "feat(bamboo): add CveIntentionAction for pom.xml dependency version bumping"
```

---

### Task 18: Create CveAnnotator

**Files:**
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/editor/CveAnnotator.kt`
- Create: `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/editor/CveAnnotatorTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.bamboo.editor

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import com.workflow.orchestrator.bamboo.service.CveRemediationService
import com.workflow.orchestrator.bamboo.service.CveRemediationService.CveSeverity
import com.workflow.orchestrator.bamboo.service.CveRemediationService.CveVulnerability
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class CveAnnotatorTest {

    @AfterEach
    fun cleanup() = unmockkAll()

    @Test
    fun `collectInformation returns null for non-pom files`() {
        val file = mockk<PsiFile>()
        every { file.name } returns "Foo.kt"

        assertNull(CveAnnotator().collectInformation(file))
    }

    @Test
    fun `collectInformation returns null for pom with no CVEs`() {
        val project = mockk<Project>()
        val file = mockk<XmlFile>()
        every { file.name } returns "pom.xml"
        every { file.project } returns project

        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()
        every { state.healthCheckCveEnabled } returns true
        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(project) } returns settings
        every { settings.state } returns state

        val cveService = mockk<CveRemediationService>()
        every { cveService.vulnerabilities } returns MutableStateFlow(emptyList())
        mockkObject(CveRemediationService)
        every { CveRemediationService.getInstance(project) } returns cveService

        assertNull(CveAnnotator().collectInformation(file))
    }

    @Test
    fun `collectInformation returns null when CVE annotations disabled`() {
        val project = mockk<Project>()
        val file = mockk<XmlFile>()
        every { file.name } returns "pom.xml"
        every { file.project } returns project

        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()
        every { state.healthCheckCveEnabled } returns false
        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(project) } returns settings
        every { settings.state } returns state

        assertNull(CveAnnotator().collectInformation(file))
    }

    @Test
    fun `collectInformation returns info when CVEs present`() {
        val project = mockk<Project>()
        val file = mockk<XmlFile>()
        every { file.name } returns "pom.xml"
        every { file.project } returns project

        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()
        every { state.healthCheckCveEnabled } returns true
        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(project) } returns settings
        every { settings.state } returns state

        val vuln = CveVulnerability(
            cveId = "CVE-2023-44487", groupId = "io.netty",
            artifactId = "netty-codec-http2", currentVersion = "4.1.93.Final",
            severity = CveSeverity.CRITICAL, description = "test"
        )
        val cveService = mockk<CveRemediationService>()
        every { cveService.vulnerabilities } returns MutableStateFlow(listOf(vuln))
        mockkObject(CveRemediationService)
        every { CveRemediationService.getInstance(project) } returns cveService

        val result = CveAnnotator().collectInformation(file)

        assertNotNull(result)
        assertEquals(1, result!!.vulnerabilities.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.editor.CveAnnotatorTest" -x verifyPluginStructure`
Expected: FAIL — class not found

- [ ] **Step 3: Implement CveAnnotator**

```kotlin
package com.workflow.orchestrator.bamboo.editor

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.workflow.orchestrator.bamboo.service.CveRemediationService
import com.workflow.orchestrator.bamboo.service.CveRemediationService.CveSeverity
import com.workflow.orchestrator.bamboo.service.CveRemediationService.CveVulnerability
import com.workflow.orchestrator.core.settings.PluginSettings

class CveAnnotator : ExternalAnnotator<CveAnnotator.CollectionInfo, CveAnnotator.AnnotationResult>() {

    data class CollectionInfo(
        val file: PsiFile,
        val vulnerabilities: List<CveVulnerability>
    )

    data class AnnotationResult(
        val annotations: List<CveAnnotationEntry>
    )

    data class CveAnnotationEntry(
        val element: XmlTag,
        val cve: CveVulnerability
    )

    override fun collectInformation(file: PsiFile): CollectionInfo? {
        if (file !is XmlFile || file.name != "pom.xml") return null
        val project = file.project
        val settings = PluginSettings.getInstance(project).state
        if (!settings.healthCheckCveEnabled) return null

        val vulns = CveRemediationService.getInstance(project).vulnerabilities.value
        if (vulns.isEmpty()) return null

        return CollectionInfo(file, vulns)
    }

    override fun doAnnotate(collectionInfo: CollectionInfo): AnnotationResult {
        val xmlFile = collectionInfo.file as XmlFile
        val entries = mutableListOf<CveAnnotationEntry>()
        val rootTag = xmlFile.rootTag ?: return AnnotationResult(emptyList())

        for (vuln in collectionInfo.vulnerabilities) {
            val depTag = findDependencyTag(rootTag, vuln.groupId, vuln.artifactId)
            if (depTag != null) {
                entries.add(CveAnnotationEntry(depTag, vuln))
            }
        }

        return AnnotationResult(entries)
    }

    override fun apply(file: PsiFile, result: AnnotationResult, holder: AnnotationHolder) {
        for (entry in result.annotations) {
            val severity = when (entry.cve.severity) {
                CveSeverity.CRITICAL, CveSeverity.HIGH -> HighlightSeverity.WARNING
                CveSeverity.MEDIUM -> HighlightSeverity.WEAK_WARNING
                CveSeverity.LOW -> HighlightSeverity.INFORMATION
            }

            val fixText = entry.cve.fixedVersion?.let { " — Fix: bump to $it" } ?: ""
            holder.newAnnotation(
                severity,
                "${entry.cve.cveId} (${entry.cve.severity})$fixText"
            )
                .range(entry.element.textRange)
                .create()
        }
    }

    private fun findDependencyTag(rootTag: XmlTag, groupId: String, artifactId: String): XmlTag? {
        val dependenciesSections = mutableListOf<XmlTag>()

        rootTag.findFirstSubTag("dependencies")?.let { dependenciesSections.add(it) }
        rootTag.findFirstSubTag("dependencyManagement")
            ?.findFirstSubTag("dependencies")
            ?.let { dependenciesSections.add(it) }

        for (depsTag in dependenciesSections) {
            for (dep in depsTag.findSubTags("dependency")) {
                if (dep.findFirstSubTag("groupId")?.value?.text == groupId &&
                    dep.findFirstSubTag("artifactId")?.value?.text == artifactId) {
                    return dep
                }
            }
        }

        return null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.editor.CveAnnotatorTest" -x verifyPluginStructure`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/editor/CveAnnotator.kt \
        bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/editor/CveAnnotatorTest.kt
git commit -m "feat(bamboo): add CveAnnotator for pom.xml CVE warning annotations"
```

---

## Chunk 6: Plugin.xml Wiring & Final Verification

### Task 19: Register Bamboo CVE Components in plugin.xml

**Files:**
- Modify: `core/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Add CVE registrations to plugin.xml**

Add inside the `<extensions defaultExtensionNs="com.intellij">` block:

```xml
<!-- CVE Auto-Bumper -->
<intentionAction>
    <language>XML</language>
    <className>com.workflow.orchestrator.bamboo.editor.CveIntentionAction</className>
    <category>Workflow Orchestrator CVE</category>
</intentionAction>
<projectService
    serviceImplementation="com.workflow.orchestrator.bamboo.service.CveRemediationService"/>
<externalAnnotator
    language="XML"
    implementationClass="com.workflow.orchestrator.bamboo.editor.CveAnnotator"/>
```

- [ ] **Step 2: Verify full build compiles**

Run: `./gradlew compileKotlin -x verifyPluginStructure`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/resources/META-INF/plugin.xml
git commit -m "feat: register CVE intention action, annotator, and service in plugin.xml"
```

---

### Task 20: Run Full Test Suite and Final Verification

**Files:** None (verification only)

- [ ] **Step 1: Run all core tests**

Run: `./gradlew :core:test -x verifyPluginStructure`
Expected: All tests PASS

- [ ] **Step 2: Run all bamboo tests**

Run: `./gradlew :bamboo:test -x verifyPluginStructure`
Expected: All tests PASS

- [ ] **Step 3: Run full project build**

Run: `./gradlew build -x verifyPluginStructure`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Verify no compilation warnings**

Run: `./gradlew compileKotlin -x verifyPluginStructure 2>&1 | grep -i "warning"`
Expected: No warnings related to Phase 1F code

- [ ] **Step 5: Final commit if any cleanup needed**

```bash
git add -A
git commit -m "chore: Phase 1F final verification and cleanup"
```

---

## Summary

| Chunk | Tasks | Tests | Key Deliverables |
|-------|-------|-------|-----------------|
| 1: Foundation | 1-5 | ~18 | Events, settings, HealthCheck interface, CopyrightCheckService, MavenModuleDetector |
| 2: Maven Build | 6-8 | ~10 | MavenBuildService, output filters, MavenConsoleManager |
| 3: Health Checks | 9-12 | ~21 | MavenCompileCheck, MavenTestCheck, CopyrightCheck, SonarGateCheck, HealthCheckService |
| 4: VCS Integration | 13-15 | ~2 | HealthCheckCheckinHandlerFactory, settings UI, plugin.xml wiring |
| 5: CVE Bumper | 16-18 | ~13 | CveRemediationService, CveIntentionAction, CveAnnotator |
| 6: Final Wiring | 19-20 | 0 | plugin.xml registrations, full build verification |

**Total:** 20 tasks, ~64 tests, 15 new files across `:core` and `:bamboo` modules.
