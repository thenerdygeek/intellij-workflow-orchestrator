# Phase 1F: Pre-Push & Health Check — Design Specification

> **Date:** 2026-03-11
> **Status:** Draft
> **Gate 6 Milestone:** Quality enforcement — Phase 1 complete
> **Prerequisites:** Phase 1C (Bamboo build monitoring), Phase 1D (SonarQube integration)

---

## 1. Scope

Quality enforcement layer that prevents broken code from leaving the developer's machine. Five features:

| # | Feature | Surface |
|---|---------|---------|
| 38 | Health check gate (configurable, pre-commit) | VCS commit dialog checkbox |
| 39 | Incremental Maven build (changed modules only) | Background service |
| 40 | Maven console integration (RunConfiguration + ConsoleView) | Run tool window |
| 41 | CVE auto-bumper (IntentionAction on pom.xml) | Editor Alt+Enter quick-fix |
| 42 | Copyright enforcer (header validation) | VCS commit dialog checkbox |

**Configurability principle:** Every check is individually toggleable. The health check gate supports three blocking modes: hard block (prevents commit), soft block (warns but allows), and off. Developers choose which checks matter to them.

**Out of scope:** Remote CI integration (that's Bamboo in Phase 1C), SonarQube analysis triggering (that's Phase 1D), code review workflows (Phase 2B).

---

## 2. Module Architecture

**No new Gradle modules.** Phase 1F adds to existing modules — this is an enforcement layer on top of capabilities built in Phases 1C-1E.

| Feature | Module | Rationale |
|---------|--------|-----------|
| #38 Health check gate | `:core` | Cross-cutting VCS hook that orchestrates checks from multiple modules |
| #39 Incremental Maven build | `:core` | General build capability reused by health check and future phases |
| #40 Maven console integration | `:core` | Run configuration infrastructure, not module-specific |
| #41 CVE auto-bumper | `:bamboo` | CVE data originates from Bamboo build log parsing |
| #42 Copyright enforcer | `:core` | Simple file scan, no external service dependency |

**Note on master spec divergences:**

1. **Module placement:** The master spec (Section 7.3) places `HealthCheckService` in `:sonar`. This design moves it to `:core` because Maven builds and copyright checks have no relationship to SonarQube — only the quality gate check reads Sonar data, and it does so via EventBus events (no direct `:sonar` dependency). The CVE auto-bumper stays in `:bamboo` per the master spec (Section 7.2) since CVE data comes from build logs. **Recommendation:** Update master spec Section 7.3 to remove `HealthCheckService` and `PrePushHandler` from the `:sonar` module listing.

2. **Pre-commit vs pre-push:** The master spec (Section 11) names Feature #38 as "PrePushHandler — health check gate" and references `com.intellij.prePushHandler` in Section 5.4. IntelliJ Community Edition 2025.1 does not expose a public `PrePushHandler` extension point — push interception is internal to the Git plugin (`git4idea.push`). This design uses `VcsCheckinHandlerFactory` instead, which intercepts at **commit time** (earlier than push, which is strictly better — issues are caught before they enter the local commit history). The `CheckinHandler.beforeCheckin()` API is stable, well-documented, and the standard IntelliJ mechanism for quality gates.

3. **Maven execution:** The master spec (Section 5.1) references `MavenRunner` + `MavenRunnerParameters` from the `org.jetbrains.idea.maven` bundled plugin. This design uses `GeneralCommandLine` instead because: (a) it works even if the Maven plugin is disabled, (b) it supports `mvnw` (Maven wrapper) which is standard in enterprise projects, (c) it is simpler to test without IntelliJ Maven API mocking, and (d) `MavenRunner` internals are not part of the stable Platform API.

4. **Copyright enforcement:** The master spec (Section 5.7) references `com.intellij.copyright.updater` for copyright header management. This design implements a custom regex-based scanner instead. The IntelliJ Copyright plugin handles automatic header insertion/update — a different concern. This feature only **validates** that headers exist on changed files before commit. The regex approach is simpler, more configurable, and does not require the Copyright plugin to be installed.

```
core/src/main/kotlin/com/workflow/orchestrator/core/
├── healthcheck/
│   ├── HealthCheckService.kt                    # Orchestrates all registered checks
│   ├── HealthCheckCheckinHandlerFactory.kt      # VcsCheckinHandlerFactory integration
│   ├── HealthCheckResult.kt                     # Result data classes
│   └── checks/
│       ├── HealthCheck.kt                       # Interface for pluggable checks
│       ├── MavenCompileCheck.kt                 # Delegates to MavenBuildService
│       ├── MavenTestCheck.kt                    # Delegates to MavenBuildService
│       ├── CopyrightCheck.kt                    # Delegates to CopyrightCheckService
│       └── SonarGateCheck.kt                    # Reads cached quality gate from EventBus
│
├── maven/
│   ├── MavenBuildService.kt                     # Runs Maven goals programmatically
│   ├── MavenModuleDetector.kt                   # Git diff → affected Maven modules
│   └── MavenConsoleManager.kt                   # Creates RunConfiguration + ConsoleView
│
├── copyright/
│   └── CopyrightCheckService.kt                 # Scans changed files for copyright headers

core/src/test/kotlin/com/workflow/orchestrator/core/
├── healthcheck/
│   ├── HealthCheckServiceTest.kt
│   ├── HealthCheckCheckinHandlerFactoryTest.kt
│   └── checks/
│       ├── MavenCompileCheckTest.kt
│       ├── MavenTestCheckTest.kt
│       ├── CopyrightCheckTest.kt
│       └── SonarGateCheckTest.kt
│
├── maven/
│   ├── MavenBuildServiceTest.kt
│   ├── MavenModuleDetectorTest.kt
│   └── MavenConsoleManagerTest.kt
│
├── copyright/
│   └── CopyrightCheckServiceTest.kt

bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/
├── service/
│   └── CveRemediationService.kt                 # Maps CVEs to pom.xml dependencies
└── editor/
    ├── CveIntentionAction.kt                    # Alt+Enter quick-fix on pom.xml
    └── CveAnnotator.kt                          # ExternalAnnotator for pom.xml CVE warnings

bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/
├── service/
│   └── CveRemediationServiceTest.kt
└── editor/
    ├── CveIntentionActionTest.kt
    └── CveAnnotatorTest.kt
```

**Dependency graph:**
```
:core (healthcheck/, maven/, copyright/) → IntelliJ Platform APIs only
:bamboo (CveRemediationService, CveIntentionAction) → :core (EventBus, PluginSettings)
```

No new cross-module dependencies introduced. The health check orchestrator in `:core` reads Sonar quality gate status from cached `WorkflowEvent.QualityGateResult` events on the EventBus — no direct `:sonar` dependency.

---

## 3. Health Check Gate (Feature #38)

### 3.1 Configuration Model

All health check settings are stored in `PluginSettings.State` (`:core` module):

```kotlin
// Added to core/settings/PluginSettings.State
var healthCheckEnabled by property(true)
var healthCheckBlockingMode by string("soft")       // "hard" | "soft" | "off"
var healthCheckCompileEnabled by property(true)
var healthCheckTestEnabled by property(true)
var healthCheckCopyrightEnabled by property(true)
var healthCheckSonarGateEnabled by property(true)
var healthCheckCveEnabled by property(true)
var healthCheckMavenGoals by string("clean compile test")
var healthCheckSkipBranchPattern by string("")      // regex, empty = no skip
var healthCheckTimeoutSeconds by property(300)
var copyrightHeaderPattern by string("")            // regex for expected header
```

**Blocking modes:**
| Mode | Behavior |
|------|----------|
| **Hard block** | `ReturnResult.CANCEL` — commit is rejected, user must fix issues first |
| **Soft block** | `ReturnResult.COMMIT` with a balloon notification listing failures — commit proceeds with a "heads up" |
| **Off** | Checks do not run, handler is a no-op |

**Branch skip:** If the current branch matches `healthCheckSkipBranchPattern` (e.g., `hotfix/.*`), all health checks are skipped regardless of blocking mode. This lets developers bypass checks for urgent hotfixes.

### 3.2 HealthCheck Interface

Pluggable check system — each check implements a common interface:

```kotlin
interface HealthCheck {
    val id: String                    // unique identifier, matches settings toggle key
    val displayName: String           // shown in commit dialog and results
    val order: Int                    // execution order (lower = first)

    fun isEnabled(settings: PluginSettings.State): Boolean
    suspend fun execute(context: HealthCheckContext): CheckResult

    data class CheckResult(
        val passed: Boolean,
        val message: String,          // human-readable summary
        val details: List<String> = emptyList()  // individual failure lines
    )
}

data class HealthCheckContext(
    val project: Project,
    val changedFiles: List<VirtualFile>,
    val commitMessage: String,
    val branch: String
)
```

**Registered checks and their order:**

| Order | Check ID | Class | Toggle Setting |
|-------|----------|-------|---------------|
| 10 | `maven-compile` | `MavenCompileCheck` | `healthCheckCompileEnabled` |
| 20 | `maven-test` | `MavenTestCheck` | `healthCheckTestEnabled` |
| 30 | `copyright` | `CopyrightCheck` | `healthCheckCopyrightEnabled` |
| 40 | `sonar-gate` | `SonarGateCheck` | `healthCheckSonarGateEnabled` |

CVE check is not in the commit-time pipeline — it runs on-demand via the IntentionAction (Feature #41). The CVE setting (`healthCheckCveEnabled`) controls whether CVE annotations appear in the editor.

### 3.3 HealthCheckService

Project-level service that orchestrates check execution:

```kotlin
@Service(Service.Level.PROJECT)
class HealthCheckService(private val project: Project) {

    private val checks = listOf(
        MavenCompileCheck(),
        MavenTestCheck(),
        CopyrightCheck(),
        SonarGateCheck()
    )

    suspend fun runChecks(context: HealthCheckContext): HealthCheckResult {
        val settings = PluginSettings.getInstance(project).state
        if (!settings.healthCheckEnabled) return HealthCheckResult.skipped()

        val branch = context.branch
        val skipPattern = settings.healthCheckSkipBranchPattern
        if (skipPattern.isNotBlank() && Regex(skipPattern).matches(branch)) {
            return HealthCheckResult.skipped("Branch matches skip pattern")
        }

        val enabledChecks = checks.filter { it.isEnabled(settings) }

        // Emit start event so UI can show "health check running" status
        EventBus.getInstance(project).emit(
            WorkflowEvent.HealthCheckStarted(checks = enabledChecks.map { it.id })
        )

        val startTime = System.currentTimeMillis()
        val results = mutableMapOf<String, HealthCheck.CheckResult>()

        for (check in enabledChecks) {
            val result = withTimeoutOrNull(
                settings.healthCheckTimeoutSeconds * 1000L
            ) {
                check.execute(context)
            } ?: HealthCheck.CheckResult(
                passed = false,
                message = "${check.displayName} timed out after ${settings.healthCheckTimeoutSeconds}s"
            )
            results[check.id] = result
        }

        val passed = results.values.all { it.passed }
        val durationMs = System.currentTimeMillis() - startTime

        // Emit finish event with timing
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
            project.service<HealthCheckService>()
    }
}

data class HealthCheckResult(
    val passed: Boolean,
    val checkResults: Map<String, HealthCheck.CheckResult>,
    val skipped: Boolean = false,
    val skipReason: String? = null,
    val durationMs: Long = 0
) {
    companion object {
        fun skipped(reason: String = "Health check disabled") =
            HealthCheckResult(passed = true, checkResults = emptyMap(), skipped = true, skipReason = reason)
    }
}
```

### 3.4 HealthCheckCheckinHandlerFactory

Integration with IntelliJ's VCS commit dialog:

```kotlin
class HealthCheckCheckinHandlerFactory : VcsCheckinHandlerFactory(GitVcs.getKey()) {

    override fun createVcsHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler {
        return HealthCheckCheckinHandler(panel, commitContext)
    }
}

class HealthCheckCheckinHandler(
    private val panel: CheckinProjectPanel,
    private val commitContext: CommitContext
) : CheckinHandler() {

    override fun getBeforeCheckinConfigurationPanel(): RefreshableOnComponent? {
        val settings = PluginSettings.getInstance(panel.project).state
        if (!settings.healthCheckEnabled) return null

        return HealthCheckConfigPanel(panel.project)
        // Returns a JPanel with:
        // - Master checkbox: "Run health checks"
        // - Expandable detail showing which checks are enabled
        // - Link to settings for full configuration
    }

    override fun beforeCheckin(
        executor: CommitExecutor?,
        additionalDataConsumer: PairConsumer<Any, Any>?
    ): ReturnResult {
        val settings = PluginSettings.getInstance(panel.project).state
        val mode = settings.healthCheckBlockingMode

        if (mode == "off" || !settings.healthCheckEnabled) return ReturnResult.COMMIT

        val context = buildHealthCheckContext()

        // Run health checks with a cancellable progress dialog to avoid blocking EDT.
        // ProgressManager shows "Running health checks..." with a cancel button.
        var result: HealthCheckResult? = null
        ProgressManager.getInstance().run(object : Task.Modal(
            panel.project, "Running Health Checks...", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                result = runBlocking {
                    HealthCheckService.getInstance(panel.project).runChecks(context)
                }
            }
        })

        val healthResult = result ?: return ReturnResult.COMMIT
        if (healthResult.skipped || healthResult.passed) return ReturnResult.COMMIT

        // HealthCheckService already emits HealthCheckStarted/Finished events

        return when (mode) {
            "hard" -> {
                showHealthCheckFailureDialog(healthResult)
                ReturnResult.CANCEL
            }
            "soft" -> {
                showHealthCheckWarningNotification(healthResult)
                ReturnResult.COMMIT
            }
            else -> ReturnResult.COMMIT
        }
    }
}
```

**UI in commit dialog:** A collapsible panel showing:
```
[x] Run health checks
    ✓ Maven compile    ✓ Maven test    ✓ Copyright    ○ Sonar gate (cached)
    Mode: Soft block (warn only)  [Configure...]
```

The "Configure..." link opens the Workflow Orchestrator settings page.

---

## 4. Incremental Maven Build (Feature #39)

### 4.1 MavenModuleDetector

Determines which Maven modules are affected by the current changes, enabling incremental builds:

```kotlin
class MavenModuleDetector(private val project: Project) {

    fun detectChangedModules(changedFiles: List<VirtualFile>): List<String> {
        // 1. For each changed file, walk up directories to find nearest pom.xml
        // 2. Parse the pom.xml's <artifactId>
        // 3. Deduplicate and return list of module artifact IDs
        // Returns empty list if no pom.xml found (non-Maven project)
    }

    fun buildMavenArgs(modules: List<String>, goals: String): List<String> {
        // If all modules changed or single-module project: just return goals
        // If subset: return "-pl module1,module2 -am" + goals
        // -am (also-make) ensures dependencies of changed modules are built too
    }
}
```

**Changed file detection:** Uses `ChangeListManager.getInstance(project).allChanges` to get uncommitted changes. For the commit-time health check, uses the files selected in the commit dialog (`CheckinProjectPanel.getSelectedChanges()`).

### 4.2 MavenBuildService

Programmatic Maven execution using IntelliJ's built-in Maven support:

```kotlin
@Service(Service.Level.PROJECT)
class MavenBuildService(private val project: Project) {

    suspend fun runBuild(
        goals: String,
        modules: List<String> = emptyList(),
        offline: Boolean = false
    ): MavenBuildResult {
        val args = buildArgs(goals, modules, offline)

        // Use GeneralCommandLine to run Maven
        val executable = detectMavenExecutable()
        val commandLine = GeneralCommandLine(
            executable,
            *args.toTypedArray()
        ).withWorkDirectory(project.basePath)
            .withEnvironment(System.getenv())

        val handler = OSProcessHandler(commandLine)
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
        val completed = handler.waitFor(
            PluginSettings.getInstance(project).state.healthCheckTimeoutSeconds * 1000L
        )

        return MavenBuildResult(
            success = completed && handler.exitCode == 0,
            exitCode = handler.exitCode ?: -1,
            output = output.toString(),
            errors = errors.toString(),
            timedOut = !completed
        )
    }

    /**
     * Builds a GeneralCommandLine for Maven execution without running it.
     * Used by MavenConsoleManager to create RunConfiguration console output.
     */
    fun buildCommandLine(goals: String, modules: List<String> = emptyList()): GeneralCommandLine {
        val args = buildArgs(goals, modules, offline = false)
        val executable = detectMavenExecutable()
        return GeneralCommandLine(executable, *args.toTypedArray())
            .withWorkDirectory(project.basePath)
            .withEnvironment(System.getenv())
    }

    private fun detectMavenExecutable(): String {
        // Resolution order — returns the path to the Maven executable (not the home dir):
        // 1. Project-level Maven wrapper: <project-root>/mvnw (or mvnw.cmd on Windows)
        // 2. MAVEN_HOME/bin/mvn or M2_HOME/bin/mvn from environment variables
        // 3. "mvn" bare command (relies on system PATH resolution)
    }

    companion object {
        fun getInstance(project: Project): MavenBuildService =
            project.service<MavenBuildService>()
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

**Maven wrapper support:** Prefers `mvnw` / `mvnw.cmd` in the project root. This is the standard for enterprise projects — no global Maven installation required.

**Incremental build flow:**
1. `MavenModuleDetector.detectChangedModules()` identifies affected modules from changed files
2. `MavenBuildService.runBuild()` adds `-pl module1,module2 -am` for incremental builds
3. For single-module projects, runs goals without `-pl` flag

---

## 5. Maven Console Integration (Feature #40)

### 5.1 MavenConsoleManager

Creates IntelliJ RunConfiguration entries for Maven builds, showing output in the standard Run tool window with full syntax highlighting and clickable file links:

```kotlin
class MavenConsoleManager(private val project: Project) {

    fun createAndRunConfiguration(
        name: String,
        goals: String,
        modules: List<String> = emptyList()
    ): RunContentDescriptor? {
        // 1. Create a GeneralCommandLine for Maven
        val commandLine = MavenBuildService.getInstance(project)
            .buildCommandLine(goals, modules)

        // 2. Create a ConsoleView with Maven output filters
        val consoleView = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .apply {
                addFilter(MavenErrorFilter(project))     // clickable [ERROR] file:line
                addFilter(MavenWarningFilter(project))   // clickable [WARNING] file:line
                addFilter(MavenTestFailureFilter(project))  // clickable test failure links
            }
            .console

        // 3. Create ProcessHandler and attach to console
        val processHandler = OSProcessHandler(commandLine)
        consoleView.attachToProcess(processHandler)

        // 4. Show in Run tool window
        val descriptor = RunContentDescriptor(
            consoleView, processHandler, consoleView.component, name
        )

        ExecutionManager.getInstance(project)
            .contentManager
            .showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)

        processHandler.startNotify()
        return descriptor
    }
}
```

**Output filters:** Custom `Filter` implementations that parse Maven output and create `HyperlinkInfo` entries:
- `MavenErrorFilter` — matches `[ERROR] /path/File.java:[line,col]` → clickable link that opens the file at that line
- `MavenWarningFilter` — same for `[WARNING]` lines
- `MavenTestFailureFilter` — matches test failure output → clickable link to test class

These filters reuse the regex patterns from `BuildLogParser` in `:bamboo` — but since `:core` cannot depend on `:bamboo`, the patterns are duplicated as simple regex constants. This is acceptable because the patterns are Maven standard output format (unlikely to change).

### 5.2 Integration with Health Check

When the health check runs Maven goals, the output is shown in the Run tool window:
1. Health check starts → `MavenConsoleManager.createAndRunConfiguration("Health Check: compile test")`
2. Maven output streams to the console in real-time
3. If build fails, clickable error links let the developer jump to the problem instantly
4. Health check reads the exit code to determine pass/fail

This gives developers full visibility into what the health check is doing — no "black box" experience.

---

## 6. CVE Auto-Bumper (Feature #41)

### 6.1 CveRemediationService

Extends the existing `:bamboo` module's build log analysis to extract CVE vulnerability data and map it to `pom.xml` dependencies:

```kotlin
@Service(Service.Level.PROJECT)
class CveRemediationService(private val project: Project) {

    data class CveVulnerability(
        val cveId: String,                     // e.g. "CVE-2023-44487"
        val groupId: String,                   // e.g. "io.netty"
        val artifactId: String,                // e.g. "netty-codec-http2"
        val currentVersion: String,            // e.g. "4.1.93.Final"
        val fixedVersion: String?,             // e.g. "4.1.100.Final" (null if unknown)
        val severity: CveSeverity,
        val description: String
    )

    enum class CveSeverity { CRITICAL, HIGH, MEDIUM, LOW }

    private val _vulnerabilities = MutableStateFlow<List<CveVulnerability>>(emptyList())
    val vulnerabilities: StateFlow<List<CveVulnerability>> = _vulnerabilities.asStateFlow()

    fun parseFromBuildLog(log: String): List<CveVulnerability> {
        // Extracts CVE entries from OWASP Dependency-Check Maven plugin output
        // Pattern: "[WARNING] CVE-XXXX-XXXXX: groupId:artifactId:version - description"
        // Also handles NVD format from dependency-check-report
    }

    fun findVulnerableDependency(
        cve: CveVulnerability
    ): PomDependencyLocation? {
        // Uses IntelliJ PSI to find the <dependency> element in pom.xml
        // that matches the CVE's groupId + artifactId
        // Returns the PsiElement location for annotation
    }

    data class PomDependencyLocation(
        val pomFile: VirtualFile,
        val dependencyElement: XmlTag,         // PSI reference to <dependency> tag
        val versionElement: XmlTag?,           // PSI reference to <version> tag (null if managed)
        val lineNumber: Int
    )

    companion object {
        fun getInstance(project: Project): CveRemediationService =
            project.service<CveRemediationService>()
    }
}
```

**CVE data flow:**
1. Bamboo build log arrives → `BuildLogParser.parse()` extracts errors/warnings (existing)
2. `CveRemediationService.parseFromBuildLog()` extracts CVE entries from the same log
3. `findVulnerableDependency()` maps CVEs to `pom.xml` `<dependency>` elements via PSI
4. `CveIntentionAction` reads from `_vulnerabilities` StateFlow to show quick-fixes

**Build log CVE patterns** (OWASP Dependency-Check plugin output):
```
[WARNING] One or more dependencies were identified with known vulnerabilities:
[WARNING]   io.netty:netty-codec-http2:4.1.93.Final  CVE-2023-44487 (CRITICAL)
```

### 6.2 CveIntentionAction

PSI-based `IntentionAction` on `pom.xml` files that offers to bump vulnerable dependency versions:

```kotlin
class CveIntentionAction : IntentionAction, PriorityAction {

    override fun getText() = "Bump to fix CVE vulnerability"
    override fun getFamilyName() = "Workflow Orchestrator CVE"
    override fun getPriority() = PriorityAction.Priority.HIGH

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        // 1. File must be pom.xml (check file name)
        // 2. Caret must be inside a <dependency> tag
        // 3. That dependency must have a known CVE in CveRemediationService
        if (file !is XmlFile || file.name != "pom.xml") return false

        val element = file.findElementAt(editor.caretModel.offset) ?: return false
        val dependencyTag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java)
            ?.takeIf { it.name == "dependency" || it.parentTag?.name == "dependency" }
            ?: return false

        val groupId = dependencyTag.findFirstSubTag("groupId")?.value?.text ?: return false
        val artifactId = dependencyTag.findFirstSubTag("artifactId")?.value?.text ?: return false

        return CveRemediationService.getInstance(project)
            .vulnerabilities.value
            .any { it.groupId == groupId && it.artifactId == artifactId && it.fixedVersion != null }
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        // 1. Find the CVE for this dependency
        // 2. Navigate PSI to the <version> tag
        // 3. Replace version text with fixedVersion
        // 4. Use WriteCommandAction for undo support
        // 5. Show notification: "Bumped artifact:version → fixedVersion (fixes CVE-XXXX)"
    }
}
```

**PSI navigation:** The action uses `XmlFile` PSI to navigate `<project>` → `<dependencies>` → `<dependency>` → `<version>`. For dependencies using `<dependencyManagement>`, it navigates to the managed version instead. For properties-based versions (e.g., `<version>${netty.version}</version>`), it finds and updates the `<properties>` entry.

### 6.3 CveAnnotator

`ExternalAnnotator` that shows inline warnings on vulnerable `<dependency>` tags in `pom.xml`:

```kotlin
class CveAnnotator : ExternalAnnotator<CveAnnotator.CollectionInfo, CveAnnotator.AnnotationResult>() {

    data class CollectionInfo(
        val file: PsiFile,
        val vulnerabilities: List<CveRemediationService.CveVulnerability>
    )

    data class AnnotationResult(
        val annotations: List<CveAnnotationEntry>
    )

    data class CveAnnotationEntry(
        val element: XmlTag,
        val cve: CveRemediationService.CveVulnerability
    )

    override fun collectInformation(file: PsiFile): CollectionInfo? {
        // Phase 1: Runs on EDT — must be fast
        if (file !is XmlFile || file.name != "pom.xml") return null
        val project = file.project
        val settings = PluginSettings.getInstance(project).state
        if (!settings.healthCheckCveEnabled) return null

        val vulns = CveRemediationService.getInstance(project).vulnerabilities.value
        if (vulns.isEmpty()) return null

        return CollectionInfo(file, vulns)
    }

    override fun doAnnotate(collectionInfo: CollectionInfo): AnnotationResult {
        // Phase 2: Runs on background thread — can be slow
        val xmlFile = collectionInfo.file as XmlFile
        val entries = mutableListOf<CveAnnotationEntry>()

        for (vuln in collectionInfo.vulnerabilities) {
            val location = CveRemediationService.getInstance(collectionInfo.file.project)
                .findVulnerableDependency(vuln)
            if (location != null) {
                entries.add(CveAnnotationEntry(location.dependencyElement, vuln))
            }
        }

        return AnnotationResult(entries)
    }

    override fun apply(file: PsiFile, result: AnnotationResult, holder: AnnotationHolder) {
        // Phase 3: Runs on EDT — applies annotations to the editor
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
}
```

The three-phase `ExternalAnnotator` pattern ensures:
- `collectInformation()` runs fast on EDT (just reads cached StateFlow)
- `doAnnotate()` does PSI traversal on a background thread
- `apply()` creates annotations on EDT

---

## 7. Copyright Enforcer (Feature #42)

### 7.1 CopyrightCheckService

Scans source files for required copyright headers:

```kotlin
class CopyrightCheckService(private val project: Project) {

    fun checkFiles(files: List<VirtualFile>): CopyrightCheckResult {
        val settings = PluginSettings.getInstance(project).state
        val pattern = settings.copyrightHeaderPattern
        if (pattern.isBlank()) return CopyrightCheckResult(emptyList())

        val regex = Regex(pattern)
        val violations = mutableListOf<CopyrightViolation>()

        for (file in files) {
            if (!isSourceFile(file)) continue

            val content = file.contentsToByteArray().decodeToString()
            val headerLines = content.lines().take(10).joinToString("\n")

            if (!regex.containsMatchIn(headerLines)) {
                violations.add(CopyrightViolation(file, "Missing copyright header"))
            }
        }

        return CopyrightCheckResult(violations)
    }

    private fun isSourceFile(file: VirtualFile): Boolean {
        val ext = file.extension ?: return false
        return ext in setOf("java", "kt", "kts", "xml", "yaml", "yml", "properties")
    }
}

data class CopyrightCheckResult(val violations: List<CopyrightViolation>) {
    val passed: Boolean get() = violations.isEmpty()
}

data class CopyrightViolation(
    val file: VirtualFile,
    val reason: String
)
```

**Header pattern:** Configured via `copyrightHeaderPattern` setting. Example:
```regex
Copyright \(c\) \d{4} MyCompany
```

The check only scans the first 10 lines of each file — copyright headers always appear at the top. This keeps the check fast even for large changesets.

### 7.2 Integration with Health Check

`CopyrightCheck` wraps `CopyrightCheckService` as a `HealthCheck` implementation:

```kotlin
class CopyrightCheck : HealthCheck {
    override val id = "copyright"
    override val displayName = "Copyright Headers"
    override val order = 30

    override fun isEnabled(settings: PluginSettings.State): Boolean =
        settings.healthCheckCopyrightEnabled && settings.copyrightHeaderPattern.isNotBlank()

    override suspend fun execute(context: HealthCheckContext): HealthCheck.CheckResult {
        val service = CopyrightCheckService(context.project)
        val result = service.checkFiles(context.changedFiles)

        return HealthCheck.CheckResult(
            passed = result.passed,
            message = if (result.passed) "All files have copyright headers"
                     else "${result.violations.size} file(s) missing copyright header",
            details = result.violations.map { "${it.file.name}: ${it.reason}" }
        )
    }
}
```

---

## 8. WorkflowEvent Extensions

New events added to the sealed hierarchy in `:core`:

```kotlin
// Health check lifecycle events
data class HealthCheckStarted(
    val checks: List<String>           // IDs of checks being run
) : WorkflowEvent()

data class HealthCheckFinished(
    val passed: Boolean,
    val results: Map<String, Boolean>, // check ID → pass/fail
    val durationMs: Long
) : WorkflowEvent()
```

These events allow the Build tab (`:bamboo`) and Quality tab (`:sonar`) to show health check status. The EventBus carries these events without creating module dependencies.

---

## 9. Settings UI

The Workflow Orchestrator settings page (`WorkflowSettingsConfigurable`) gains a new "Health Check" section:

```
── Health Check ─────────────────────────────────────
[x] Enable health checks on commit
    Blocking mode: [ Soft block (warn only) ▼ ]

    Checks:
    [x] Maven compile
    [x] Maven test
    [x] Copyright headers
    [x] Sonar quality gate (uses cached status)

    Maven goals: [ clean compile test          ]
    Skip for branches: [ hotfix/.*               ]
    Timeout (seconds): [ 300                     ]

── Copyright ────────────────────────────────────────
    Header pattern (regex): [ Copyright \(c\) \d{4} MyCompany ]
```

This is added as a collapsible section to the existing `WorkflowSettingsConfigurable` panel. No new settings page needed.

---

## 10. plugin.xml Registrations

```xml
<!-- Health Check: VCS commit handler -->
<vcsCheckinHandlerFactory
    implementation="com.workflow.orchestrator.core.healthcheck.HealthCheckCheckinHandlerFactory"/>

<!-- Health Check: Project service -->
<projectService
    serviceImplementation="com.workflow.orchestrator.core.healthcheck.HealthCheckService"/>

<!-- Maven: Project services -->
<projectService
    serviceImplementation="com.workflow.orchestrator.core.maven.MavenBuildService"/>

<!-- Notification group for health check results -->
<notificationGroup id="workflow.healthcheck" displayType="BALLOON"/>

<!-- CVE: IntentionAction on pom.xml -->
<intentionAction>
    <language>XML</language>
    <className>com.workflow.orchestrator.bamboo.editor.CveIntentionAction</className>
    <category>Workflow Orchestrator CVE</category>
</intentionAction>

<!-- CVE: Project service -->
<projectService
    serviceImplementation="com.workflow.orchestrator.bamboo.service.CveRemediationService"/>

<!-- CVE: External annotator for pom.xml warnings -->
<externalAnnotator
    language="XML"
    implementationClass="com.workflow.orchestrator.bamboo.editor.CveAnnotator"/>
```

**Note:** `HealthCheckCheckinHandlerFactory` is registered alongside the existing `CommitMessagePrefixHandlerFactory` from `:jira`. IntelliJ supports multiple `VcsCheckinHandlerFactory` registrations — they all participate in the commit dialog.

---

## 11. Error Handling

| Scenario | Handling |
|----------|----------|
| Maven not found (no `mvnw`, no `MAVEN_HOME`, no `mvn` on PATH) | Health check skips Maven checks with warning notification. Other checks still run. |
| Maven build timeout | Check marked as failed with "timed out" message. Process killed via `ProcessHandler.destroyProcess()`. |
| No pom.xml in project | Maven-related checks skip silently (non-Maven project). |
| Copyright pattern is invalid regex | Settings validation shows error. Check disabled until pattern is valid. |
| Sonar quality gate not cached (Phase 1D not configured) | `SonarGateCheck` skips with "no cached data" message. |
| CVE build log has no OWASP output | `CveRemediationService` returns empty list. No annotations shown. |
| pom.xml uses `<dependencyManagement>` | `CveIntentionAction` navigates to managed `<version>` in parent pom. |
| pom.xml uses property-based versions | `CveIntentionAction` navigates to `<properties>` entry. |

---

## 12. Data Flow Summary

**Health check on commit:**
1. Developer clicks "Commit" in VCS dialog
2. `HealthCheckCheckinHandler.beforeCheckin()` fires
3. `HealthCheckService.runChecks()` runs enabled checks in order:
   - `MavenCompileCheck` → `MavenBuildService.runBuild("compile", changedModules)`
   - `MavenTestCheck` → `MavenBuildService.runBuild("test", changedModules)`
   - `CopyrightCheck` → `CopyrightCheckService.checkFiles(changedFiles)`
   - `SonarGateCheck` → reads cached `QualityGateResult` from EventBus
4. Maven output streams to Run tool window via `MavenConsoleManager`
5. Results aggregated → EventBus emits `HealthCheckFinished`
6. Hard block → dialog shows failures, commit cancelled
7. Soft block → notification shown, commit proceeds

**CVE remediation:**
1. Bamboo build finishes → `BuildLogReady` event fires
2. `CveRemediationService.parseFromBuildLog()` extracts CVE entries
3. `CveAnnotator` marks vulnerable `<dependency>` tags in pom.xml
4. Developer places caret on dependency → Alt+Enter → "Bump to fix CVE vulnerability"
5. `CveIntentionAction.invoke()` updates `<version>` tag via PSI + `WriteCommandAction`

**Incremental Maven build:**
1. `MavenModuleDetector.detectChangedModules()` walks changed files to nearest pom.xml
2. Deduplicates module list
3. `MavenBuildService.runBuild()` adds `-pl module1,module2 -am` for multi-module projects
4. Single-module projects: runs goals without `-pl`

---

## 13. Testing Strategy

| Layer | What | How |
|-------|------|-----|
| HealthCheckService | Check orchestration, timeout, skip branches | Mock individual checks, verify ordering and aggregation |
| HealthCheckCheckinHandler | beforeCheckin() return values per blocking mode | Mock HealthCheckService, verify ReturnResult per mode |
| MavenModuleDetector | File → module mapping | Test fixtures with multi-module pom.xml structures |
| MavenBuildService | Command line construction, Maven home detection | Mock ProcessHandler, verify command arguments |
| MavenConsoleManager | Output filter regex patterns | Unit test filter regex against Maven output fixtures. Console creation verified via manual testing (requires full IDE UI infrastructure). |
| CopyrightCheckService | Header pattern matching | Test files with/without headers, various patterns |
| CopyrightCheck | Integration with HealthCheck interface | Mock CopyrightCheckService, verify CheckResult |
| SonarGateCheck | Reads cached quality gate | Mock EventBus with cached QualityGateResult event |
| CveRemediationService | Build log parsing, PSI navigation | Log fixtures, mock XmlFile PSI tree |
| CveIntentionAction | isAvailable() and invoke() | Mock editor/PsiFile, verify version tag update |
| CveAnnotator | Three-phase annotation lifecycle | Mock PsiFile + CveRemediationService, verify annotation entries |

**No integration tests with real Maven.** All tests mock the process execution boundary. `MavenBuildService` is tested by verifying the constructed `GeneralCommandLine` arguments, not by running actual builds.

---

## 14. Build Configuration

No new `build.gradle.kts` files. Phase 1F modifies existing modules:

**`:core` additions:**
- New packages: `healthcheck/`, `healthcheck/checks/`, `maven/`, `copyright/`
- No new dependencies — all APIs come from IntelliJ Platform SDK

**`:bamboo` additions:**
- New package: `editor/`
- New service: `CveRemediationService`
- No new dependencies — PSI XML APIs come from IntelliJ Platform SDK
