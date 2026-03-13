# Phase 3B: Native IntelliJ Integrations — Tier 2 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate Surefire test results, SonarQube inspections, SonarQube coverage, and Bamboo builds into IntelliJ's native Test Runner, Inspection, Coverage, and Run Configuration frameworks.

**Architecture:** Each integration adapts existing service data into IntelliJ Platform interfaces. No new API endpoints needed — everything reads from `SurefireReportParser`, `SonarDataService`, or `BambooApiClient`.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (2025.1+), SMTRunnerConsoleView (TeamCity service messages), IntelliJ Coverage API

**Design Spec:** `docs/superpowers/specs/2026-03-13-intellij-native-integrations-design.md` (Sections 3.1–3.5)

---

## Chunk 1: Surefire Test Runner UI

### Task 1: TeamCity Message Converter

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/maven/TeamCityMessageConverter.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/maven/TeamCityMessageConverterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.core.maven

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TeamCityMessageConverterTest {

    @Test
    fun `converts successful test to TeamCity messages`() {
        val summary = TestResultSummary(
            totalTests = 1, passed = 1, failures = 0, errors = 0, skipped = 0,
            failedTests = emptyList()
        )
        val messages = TeamCityMessageConverter.convert(summary, mapOf(
            "com.example.FooTest" to listOf(
                TestCaseInfo("testAdd", "com.example.FooTest", 100, TestCaseStatus.PASSED)
            )
        ))

        assertTrue(messages.any { it.contains("testSuiteStarted") && it.contains("com.example.FooTest") })
        assertTrue(messages.any { it.contains("testStarted") && it.contains("testAdd") })
        assertTrue(messages.any { it.contains("testFinished") && it.contains("testAdd") })
        assertTrue(messages.any { it.contains("testSuiteFinished") })
        assertFalse(messages.any { it.contains("testFailed") })
    }

    @Test
    fun `converts failed test to TeamCity messages`() {
        val summary = TestResultSummary(
            totalTests = 1, passed = 0, failures = 1, errors = 0, skipped = 0,
            failedTests = listOf(TestFailure("com.example.FooTest", "testFail", "expected 5 but was 3", "AssertionError", "stack trace"))
        )
        val messages = TeamCityMessageConverter.convert(summary, mapOf(
            "com.example.FooTest" to listOf(
                TestCaseInfo("testFail", "com.example.FooTest", 200, TestCaseStatus.FAILED, "expected 5 but was 3", "stack trace")
            )
        ))

        assertTrue(messages.any { it.contains("testFailed") && it.contains("expected 5 but was 3") })
    }

    @Test
    fun `escapes special characters in TeamCity messages`() {
        val escaped = TeamCityMessageConverter.escapeValue("test|with'quotes\nnewline[brackets]")
        assertTrue(escaped.contains("||"))
        assertTrue(escaped.contains("|'"))
        assertTrue(escaped.contains("|n"))
        assertTrue(escaped.contains("|["))
        assertTrue(escaped.contains("|]"))
    }

    @Test
    fun `converts skipped test`() {
        val messages = TeamCityMessageConverter.convert(
            TestResultSummary(1, 0, 0, 0, 1, emptyList()),
            mapOf("com.example.FooTest" to listOf(
                TestCaseInfo("testSkipped", "com.example.FooTest", 0, TestCaseStatus.SKIPPED)
            ))
        )
        assertTrue(messages.any { it.contains("testIgnored") && it.contains("testSkipped") })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests '*TeamCityMessageConverterTest*'`
Expected: FAIL — classes not found

- [ ] **Step 3: Implement the converter**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/maven/TeamCityMessageConverter.kt`:

```kotlin
package com.workflow.orchestrator.core.maven

enum class TestCaseStatus { PASSED, FAILED, ERROR, SKIPPED }

data class TestCaseInfo(
    val name: String,
    val className: String,
    val durationMs: Long,
    val status: TestCaseStatus,
    val failureMessage: String = "",
    val stackTrace: String = ""
)

/**
 * Converts Surefire test results into TeamCity service message format.
 * These messages are understood by IntelliJ's SMTRunnerConsoleView to render
 * the native test tree (green/red checkmarks, timing, stack traces).
 *
 * Format: ##teamcity[messageName key='value' key2='value2']
 */
object TeamCityMessageConverter {

    fun convert(
        summary: TestResultSummary,
        testCasesBySuite: Map<String, List<TestCaseInfo>>
    ): List<String> {
        val messages = mutableListOf<String>()

        // Overall test count
        messages.add("##teamcity[testCount count='${summary.totalTests}']")

        for ((suiteName, testCases) in testCasesBySuite) {
            messages.add("##teamcity[testSuiteStarted name='${escapeValue(suiteName)}']")

            for (tc in testCases) {
                val testId = "${tc.className}.${tc.name}"
                messages.add("##teamcity[testStarted name='${escapeValue(tc.name)}' locationHint='java:test://${escapeValue(testId)}']")

                when (tc.status) {
                    TestCaseStatus.PASSED -> {
                        messages.add("##teamcity[testFinished name='${escapeValue(tc.name)}' duration='${tc.durationMs}']")
                    }
                    TestCaseStatus.FAILED, TestCaseStatus.ERROR -> {
                        messages.add("##teamcity[testFailed name='${escapeValue(tc.name)}' message='${escapeValue(tc.failureMessage)}' details='${escapeValue(tc.stackTrace)}']")
                        messages.add("##teamcity[testFinished name='${escapeValue(tc.name)}' duration='${tc.durationMs}']")
                    }
                    TestCaseStatus.SKIPPED -> {
                        messages.add("##teamcity[testIgnored name='${escapeValue(tc.name)}' message='Skipped']")
                    }
                }
            }

            messages.add("##teamcity[testSuiteFinished name='${escapeValue(suiteName)}']")
        }

        return messages
    }

    /** Escape special characters per TeamCity service message protocol. */
    fun escapeValue(value: String): String {
        return value
            .replace("|", "||")
            .replace("'", "|'")
            .replace("\n", "|n")
            .replace("\r", "|r")
            .replace("[", "|[")
            .replace("]", "|]")
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:test --tests '*TeamCityMessageConverterTest*'`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/maven/TeamCityMessageConverter.kt
git add core/src/test/kotlin/com/workflow/orchestrator/core/maven/TeamCityMessageConverterTest.kt
git commit -m "feat(core): add TeamCity service message converter for Surefire results"
```

---

### Task 2: Enhanced SurefireReportParser to Produce TestCaseInfo

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/maven/SurefireReportParser.kt`
- Modify: `core/src/test/kotlin/com/workflow/orchestrator/core/maven/SurefireReportParserTest.kt`

- [ ] **Step 1: Add test for detailed test case parsing**

Add to `SurefireReportParserTest.kt`:

```kotlin
@Test
fun `parseDetailedResults returns test case info grouped by suite`() {
    val reportDir = File(tempDir, "target/surefire-reports").apply { mkdirs() }
    File(reportDir, "TEST-com.example.FooTest.xml").writeText("""
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="com.example.FooTest" tests="2" failures="1" errors="0" skipped="0" time="0.3">
          <testcase name="testPass" classname="com.example.FooTest" time="0.1"/>
          <testcase name="testFail" classname="com.example.FooTest" time="0.2">
            <failure message="oops" type="AssertionError">stack</failure>
          </testcase>
        </testsuite>
    """.trimIndent())

    val (summary, casesBySuite) = SurefireReportParser.parseDetailedReports(tempDir.absolutePath)

    assertEquals(2, summary.totalTests)
    val cases = casesBySuite["com.example.FooTest"]
    assertNotNull(cases)
    assertEquals(2, cases!!.size)
    assertEquals(TestCaseStatus.PASSED, cases[0].status)
    assertEquals(TestCaseStatus.FAILED, cases[1].status)
    assertEquals("oops", cases[1].failureMessage)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests '*SurefireReportParserTest*parseDetailedResults*'`
Expected: FAIL — `parseDetailedReports` not found

- [ ] **Step 3: Add `parseDetailedReports` method to SurefireReportParser**

Add to `SurefireReportParser.kt`:

```kotlin
/**
 * Parse reports and return both the summary and detailed test case info
 * grouped by suite name. Used by the Test Runner UI integration.
 */
fun parseDetailedReports(projectBasePath: String): Pair<TestResultSummary, Map<String, List<TestCaseInfo>>> {
    val baseDir = File(projectBasePath)
    val reportDirs = mutableListOf<File>()

    val singleModuleDir = File(baseDir, "target/surefire-reports")
    if (singleModuleDir.isDirectory) reportDirs.add(singleModuleDir)

    baseDir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
        val surefireDir = File(subDir, "target/surefire-reports")
        if (surefireDir.isDirectory) reportDirs.add(surefireDir)
    }

    if (reportDirs.isEmpty()) {
        return Pair(TestResultSummary(0, 0, 0, 0, 0, emptyList()), emptyMap())
    }

    var totalTests = 0; var totalFailures = 0; var totalErrors = 0; var totalSkipped = 0
    val failedTests = mutableListOf<TestFailure>()
    val casesBySuite = mutableMapOf<String, MutableList<TestCaseInfo>>()

    for (dir in reportDirs) {
        val xmlFiles = dir.listFiles { _, name -> name.startsWith("TEST-") && name.endsWith(".xml") } ?: continue
        for (xmlFile in xmlFiles) {
            try {
                val result = parseReportFile(xmlFile)
                totalTests += result.tests; totalFailures += result.failures
                totalErrors += result.errors; totalSkipped += result.skipped
                failedTests.addAll(result.failedTests)

                // Parse individual test cases for detailed reporting
                val detailedCases = parseTestCases(xmlFile)
                for (tc in detailedCases) {
                    casesBySuite.getOrPut(tc.className) { mutableListOf() }.add(tc)
                }
            } catch (e: Exception) {
                log.warn("[Maven:Surefire] Failed to parse ${xmlFile.name}: ${e.message}")
            }
        }
    }

    val passed = totalTests - totalFailures - totalErrors - totalSkipped
    val summary = TestResultSummary(totalTests, passed, totalFailures, totalErrors, totalSkipped, failedTests)
    return Pair(summary, casesBySuite)
}

private fun parseTestCases(file: File): List<TestCaseInfo> {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    val builder = factory.newDocumentBuilder()
    val doc = builder.parse(file)
    val root = doc.documentElement
    val cases = mutableListOf<TestCaseInfo>()

    val testcases = root.getElementsByTagName("testcase")
    for (i in 0 until testcases.length) {
        val tc = testcases.item(i)
        val name = tc.attributes.getNamedItem("name")?.nodeValue ?: "unknown"
        val className = tc.attributes.getNamedItem("classname")?.nodeValue ?: "unknown"
        val timeStr = tc.attributes.getNamedItem("time")?.nodeValue ?: "0"
        val durationMs = ((timeStr.toDoubleOrNull() ?: 0.0) * 1000).toLong()

        var status = TestCaseStatus.PASSED
        var failureMessage = ""
        var stackTrace = ""

        val children = tc.childNodes
        for (j in 0 until children.length) {
            val child = children.item(j)
            when (child.nodeName) {
                "failure" -> {
                    status = TestCaseStatus.FAILED
                    failureMessage = child.attributes?.getNamedItem("message")?.nodeValue ?: ""
                    stackTrace = child.textContent ?: ""
                }
                "error" -> {
                    status = TestCaseStatus.ERROR
                    failureMessage = child.attributes?.getNamedItem("message")?.nodeValue ?: ""
                    stackTrace = child.textContent ?: ""
                }
                "skipped" -> {
                    status = TestCaseStatus.SKIPPED
                }
            }
        }

        cases.add(TestCaseInfo(name, className, durationMs, status, failureMessage, stackTrace))
    }

    return cases
}
```

Add the `TestCaseInfo` and `TestCaseStatus` imports at the top of the file.

- [ ] **Step 4: Run all surefire parser tests**

Run: `./gradlew :core:test --tests '*SurefireReportParserTest*'`
Expected: 7 tests PASS (6 existing + 1 new)

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/maven/SurefireReportParser.kt
git add core/src/test/kotlin/com/workflow/orchestrator/core/maven/SurefireReportParserTest.kt
git commit -m "feat(core): add detailed test case parsing for Test Runner UI integration"
```

---

### Task 3: Wire Test Runner UI into BuildDashboardPanel

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt`

- [ ] **Step 1: Update runLocalMavenBuild to use detailed reports**

In `BuildDashboardPanel.kt`, modify the `runLocalMavenBuild` method to use `parseDetailedReports` and generate TeamCity messages when test goals are involved. Update the `testResults` variable to use the detailed parser:

```kotlin
// In runLocalMavenBuild, replace:
//   val testResults = if (goals.contains("test")) {
//       SurefireReportParser.parseProjectReports(basePath)
//   } else null
// With:
val testResults = if (goals.contains("test") && basePath != null) {
    val (summary, casesBySuite) = SurefireReportParser.parseDetailedReports(basePath)
    Triple(summary, casesBySuite, TeamCityMessageConverter.convert(summary, casesBySuite))
} else null
```

Update the `invokeLater` block to use `testResults?.first` instead of `testResults` for the summary, and log the TeamCity messages for debug purposes.

Note: Full SMTRunnerConsoleView integration would require a RunContentManager + ProcessHandler setup which is complex. For the initial implementation, display the test tree in the existing `stageDetailPanel` using formatted text. A follow-up task can wire up a proper SMTRunnerConsoleView.

- [ ] **Step 2: Add TeamCityMessageConverter import**

Add to BuildDashboardPanel.kt:

```kotlin
import com.workflow.orchestrator.core.maven.TeamCityMessageConverter
```

- [ ] **Step 3: Verify in runIde**

Run: `./gradlew runIde`
Trigger a "Test" build from the Build Dashboard toolbar.
Expected: Test results show in the detail panel with failure details.

- [ ] **Step 4: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt
git commit -m "feat(bamboo): wire Surefire detailed test results into build dashboard"
```

---

## Chunk 2: SonarQube Global Inspection + Run Configuration

### Task 4: SonarQube Global Inspection Tool

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/inspection/SonarGlobalInspectionTool.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Implement the global inspection**

Create `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/inspection/SonarGlobalInspectionTool.kt`:

```kotlin
package com.workflow.orchestrator.sonar.inspection

import com.intellij.codeInspection.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.workflow.orchestrator.sonar.service.SonarDataService

/**
 * Global inspection that surfaces SonarQube issues in IntelliJ's
 * Analyze > Inspect Code results. Issues appear alongside built-in
 * inspections with severity mapped from SonarQube.
 */
class SonarGlobalInspectionTool : GlobalSimpleInspectionTool() {

    private val log = Logger.getInstance(SonarGlobalInspectionTool::class.java)

    override fun getGroupDisplayName(): String = "Workflow Orchestrator"

    override fun getDisplayName(): String = "SonarQube Issues"

    override fun getShortName(): String = "SonarQubeIssues"

    override fun isEnabledByDefault(): Boolean = true

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        problemsHolder: ProblemsHolder,
        globalContext: GlobalInspectionContext,
        problemDescriptionsProcessor: ProblemDescriptionsProcessor
    ) {
        val project = file.project
        val sonarService = SonarDataService.getInstance(project)
        val filePath = file.virtualFile?.path ?: return

        val issues = sonarService.getIssuesForFile(filePath)
        if (issues.isEmpty()) return

        for (issue in issues) {
            val line = issue.line ?: continue
            val element = findElementAtLine(file, line) ?: continue

            val severity = mapSeverity(issue.severity)
            val description = "${issue.message} [${issue.rule}]"

            problemsHolder.registerProblem(
                element,
                description,
                severity
            )
        }
    }

    private fun findElementAtLine(file: PsiFile, line: Int): com.intellij.psi.PsiElement? {
        val document = com.intellij.psi.PsiDocumentManager.getInstance(file.project)
            .getDocument(file) ?: return null
        if (line < 1 || line > document.lineCount) return null
        val offset = document.getLineStartOffset(line - 1)
        return file.findElementAt(offset)
    }

    private fun mapSeverity(sonarSeverity: String): ProblemHighlightType {
        return when (sonarSeverity.uppercase()) {
            "BLOCKER", "CRITICAL" -> ProblemHighlightType.ERROR
            "MAJOR" -> ProblemHighlightType.WARNING
            "MINOR" -> ProblemHighlightType.WEAK_WARNING
            "INFO" -> ProblemHighlightType.INFORMATION
            else -> ProblemHighlightType.WARNING
        }
    }
}
```

Note: This requires `SonarDataService.getIssuesForFile(filePath)` to exist. If it doesn't, add a method that filters cached issues by file path. The SonarQube issue model needs `line`, `severity`, `message`, and `rule` fields.

- [ ] **Step 2: Register in plugin.xml**

Add to `<extensions defaultExtensionNs="com.intellij">`:

```xml
<!-- SonarQube Global Inspection -->
<globalInspection
    implementationClass="com.workflow.orchestrator.sonar.inspection.SonarGlobalInspectionTool"
    groupName="Workflow Orchestrator"
    displayName="SonarQube Issues"
    enabledByDefault="true"
    level="WARNING"/>
```

- [ ] **Step 3: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/inspection/SonarGlobalInspectionTool.kt
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat(sonar): add SonarQube issues as IntelliJ global inspection"
```

---

### Task 5: Bamboo Run Configuration

**Files:**
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/run/BambooBuildConfigurationType.kt`
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/run/BambooBuildConfigurationFactory.kt`
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/run/BambooBuildRunConfiguration.kt`
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/run/BambooBuildRunState.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Implement ConfigurationType**

Create `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/run/BambooBuildConfigurationType.kt`:

```kotlin
package com.workflow.orchestrator.bamboo.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.icons.AllIcons
import javax.swing.Icon

class BambooBuildConfigurationType : ConfigurationType {
    override fun getDisplayName(): String = "Bamboo Build"
    override fun getConfigurationTypeDescription(): String = "Trigger and monitor a Bamboo build"
    override fun getIcon(): Icon = AllIcons.Actions.Execute
    override fun getId(): String = "BambooBuildConfiguration"
    override fun getConfigurationFactories(): Array<ConfigurationFactory> =
        arrayOf(BambooBuildConfigurationFactory(this))
}
```

- [ ] **Step 2: Implement ConfigurationFactory**

Create `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/run/BambooBuildConfigurationFactory.kt`:

```kotlin
package com.workflow.orchestrator.bamboo.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project

class BambooBuildConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = "BambooBuildConfigurationFactory"
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        BambooBuildRunConfiguration(project, this, "Bamboo Build")
    override fun getOptionsClass(): Class<out BaseState> = BambooBuildRunConfigurationOptions::class.java
}
```

- [ ] **Step 3: Implement RunConfiguration**

Create `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/run/BambooBuildRunConfiguration.kt`:

```kotlin
package com.workflow.orchestrator.bamboo.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.bindText
import com.workflow.orchestrator.core.settings.PluginSettings
import git4idea.repo.GitRepositoryManager
import javax.swing.JComponent

class BambooBuildRunConfigurationOptions : BaseState() {
    var planKey by string("")
    var branch by string("")
    var buildVariables by string("")
}

class BambooBuildRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<BambooBuildRunConfigurationOptions>(project, factory, name) {

    override fun getOptions(): BambooBuildRunConfigurationOptions =
        super.getOptions() as BambooBuildRunConfigurationOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        BambooBuildSettingsEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val planKey = options.planKey.takeUnless { it.isNullOrBlank() }
            ?: PluginSettings.getInstance(project).state.bambooPlanKey.orEmpty()
        val branch = options.branch.takeUnless { it.isNullOrBlank() }
            ?: GitRepositoryManager.getInstance(project).repositories.firstOrNull()?.currentBranchName
            ?: "develop"
        return BambooBuildRunState(project, planKey, branch, options.buildVariables ?: "")
    }
}

class BambooBuildSettingsEditor(private val project: Project) : SettingsEditor<BambooBuildRunConfiguration>() {
    private var planKeyField = ""
    private var branchField = ""
    private var variablesField = ""

    override fun resetEditorFrom(config: BambooBuildRunConfiguration) {
        planKeyField = config.options.planKey ?: PluginSettings.getInstance(project).state.bambooPlanKey.orEmpty()
        branchField = config.options.branch ?: ""
        variablesField = config.options.buildVariables ?: ""
    }

    override fun applyEditorTo(config: BambooBuildRunConfiguration) {
        config.options.planKey = planKeyField
        config.options.branch = branchField
        config.options.buildVariables = variablesField
    }

    override fun createEditor(): JComponent {
        return panel {
            row("Plan Key:") {
                textField().bindText(::planKeyField)
            }
            row("Branch:") {
                textField().bindText(::branchField)
                    .comment("Leave empty to use current Git branch")
            }
            row("Build Variables (JSON):") {
                textField().bindText(::variablesField)
                    .comment("e.g., {\"dockerTagsAsJson\": \"{...}\"}")
            }
        }
    }
}
```

- [ ] **Step 4: Implement RunProfileState**

Create `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/run/BambooBuildRunState.kt`:

```kotlin
package com.workflow.orchestrator.bamboo.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.*
import java.io.OutputStream

class BambooBuildRunState(
    private val project: Project,
    private val planKey: String,
    private val branch: String,
    private val buildVariables: String
) : RunProfileState {

    private val log = Logger.getInstance(BambooBuildRunState::class.java)

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        val handler = BambooBuildProcessHandler(project, planKey, branch, buildVariables)
        handler.startNotify()
        return DefaultExecutionResult(null, handler)
    }
}

class BambooBuildProcessHandler(
    private val project: Project,
    private val planKey: String,
    private val branch: String,
    private val buildVariables: String
) : ProcessHandler() {

    private val log = Logger.getInstance(BambooBuildProcessHandler::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun startNotify() {
        super.startNotify()
        scope.launch {
            try {
                printOutput("Triggering Bamboo build: $planKey (branch: $branch)\n")

                val settings = PluginSettings.getInstance(project)
                val credentialStore = CredentialStore()
                val client = BambooApiClient(
                    baseUrl = settings.state.bambooUrl.orEmpty().trimEnd('/'),
                    tokenProvider = { credentialStore.getToken(ServiceType.BAMBOO) },
                    connectTimeoutSeconds = settings.state.httpConnectTimeoutSeconds.toLong(),
                    readTimeoutSeconds = settings.state.httpReadTimeoutSeconds.toLong()
                )

                when (val result = client.triggerBuild(planKey, branch)) {
                    is ApiResult.Success -> {
                        val buildKey = result.data
                        printOutput("Build triggered: $buildKey\n")
                        printOutput("Polling for status...\n")

                        // Poll until complete
                        var completed = false
                        while (!completed && !isProcessTerminating && !isProcessTerminated) {
                            delay(15_000) // 15 second intervals
                            when (val statusResult = client.getBuildResult(buildKey)) {
                                is ApiResult.Success -> {
                                    val state = statusResult.data
                                    printOutput("Status: ${state.lifeCycleState} (${state.state})\n")
                                    if (state.lifeCycleState.equals("Finished", ignoreCase = true)) {
                                        completed = true
                                        val success = state.state.equals("Successful", ignoreCase = true)
                                        printOutput(if (success) "\nBuild PASSED\n" else "\nBuild FAILED\n")
                                    }
                                }
                                is ApiResult.Error -> {
                                    printOutput("Error polling status: ${statusResult.message}\n")
                                    completed = true
                                }
                            }
                        }
                    }
                    is ApiResult.Error -> {
                        printOutput("Failed to trigger build: ${result.message}\n")
                    }
                }
            } catch (e: CancellationException) {
                printOutput("Build monitoring cancelled\n")
            } catch (e: Exception) {
                printOutput("Error: ${e.message}\n")
                log.warn("[Bamboo:RunConfig] Error during build", e)
            } finally {
                destroyProcess()
            }
        }
    }

    private fun printOutput(text: String) {
        notifyTextAvailable(text, ProcessOutputType.STDOUT)
    }

    override fun destroyProcessImpl() {
        scope.cancel()
        notifyProcessTerminated(0)
    }

    override fun detachProcessImpl() {
        scope.cancel()
        notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false
    override fun getProcessInput(): OutputStream? = null
}
```

- [ ] **Step 5: Register in plugin.xml**

Add to `<extensions defaultExtensionNs="com.intellij">`:

```xml
<!-- Bamboo Run Configuration -->
<configurationType implementation="com.workflow.orchestrator.bamboo.run.BambooBuildConfigurationType"/>
```

- [ ] **Step 6: Verify in runIde**

Run: `./gradlew runIde`
Go to Run > Edit Configurations > Add New > "Bamboo Build"
Expected: Configuration editor shows plan key, branch, and variables fields.

- [ ] **Step 7: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/run/
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat(bamboo): add Bamboo Build run configuration type"
```

---

## Chunk 3: Coverage Engine Bridge

### Task 6: SonarQube Coverage Engine

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/coverage/SonarCoverageEngine.kt`
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/coverage/SonarCoverageRunner.kt`
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/coverage/SonarCoverageSuite.kt`
- Create: `src/main/resources/META-INF/plugin-withCoverage.xml`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Implement SonarCoverageRunner**

Create `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/coverage/SonarCoverageRunner.kt`:

```kotlin
package com.workflow.orchestrator.sonar.coverage

import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.CoverageSuite
import com.intellij.rt.coverage.data.ProjectData
import java.io.File

/**
 * Coverage runner that reads from SonarQube instead of running tests locally.
 * This bridges SonarQube coverage data into IntelliJ's native coverage view.
 */
class SonarCoverageRunner : CoverageRunner() {

    override fun getId(): String = "SonarQubeCoverage"

    override fun getPresentableName(): String = "SonarQube"

    override fun getDataFileExtension(): String = "sonar"

    override fun acceptsCoverageEngine(engine: com.intellij.coverage.CoverageEngine): Boolean {
        return engine is SonarCoverageEngine
    }

    override fun loadCoverageData(sessionDataFile: File, baseCoverageSuite: CoverageSuite?): ProjectData? {
        if (baseCoverageSuite !is SonarCoverageSuite) return null
        return baseCoverageSuite.loadProjectData()
    }
}
```

- [ ] **Step 2: Implement SonarCoverageSuite**

Create `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/coverage/SonarCoverageSuite.kt`:

```kotlin
package com.workflow.orchestrator.sonar.coverage

import com.intellij.coverage.BaseCoverageSuite
import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageRunner
import com.intellij.openapi.project.Project
import com.intellij.rt.coverage.data.ClassData
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.ProjectData
import com.workflow.orchestrator.sonar.service.SonarDataService

class SonarCoverageSuite(
    name: String?,
    project: Project?,
    runner: CoverageRunner?,
    private val coverageEngine: SonarCoverageEngine?
) : BaseCoverageSuite(name, project, runner) {

    // No-arg constructor required for serialization
    constructor() : this(null, null, null, null)

    fun loadProjectData(): ProjectData? {
        val project = project ?: return null
        val sonarService = SonarDataService.getInstance(project)
        val coverageData = sonarService.getCoverageData() ?: return null

        val projectData = ProjectData()

        for ((filePath, lineData) in coverageData) {
            val className = filePathToClassName(filePath, project)
            val classData = projectData.getOrCreateClassData(className)

            val maxLine = lineData.maxOfOrNull { it.line } ?: continue
            val lines = arrayOfNulls<LineData>(maxLine + 1)

            for (ld in lineData) {
                val lineDatum = LineData(ld.line, null)
                lineDatum.hits = if (ld.covered) 1 else 0
                lines[ld.line] = lineDatum
            }

            classData.setLines(lines)
        }

        return projectData
    }

    private fun filePathToClassName(filePath: String, project: Project): String {
        // Convert file path to class name (com/example/Foo.java -> com.example.Foo)
        val basePath = project.basePath ?: ""
        val relative = filePath.removePrefix(basePath).removePrefix("/")
            .removePrefix("src/main/java/").removePrefix("src/main/kotlin/")
        return relative
            .replace("/", ".")
            .removeSuffix(".java")
            .removeSuffix(".kt")
    }

    override fun getCoverageEngine(): CoverageEngine = coverageEngine ?: SonarCoverageEngine()
}
```

- [ ] **Step 3: Implement SonarCoverageEngine**

Create `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/coverage/SonarCoverageEngine.kt`:

```kotlin
package com.workflow.orchestrator.sonar.coverage

import com.intellij.coverage.*
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import java.io.File
import java.util.*

/**
 * Coverage engine that bridges SonarQube coverage data into IntelliJ's
 * native coverage view. Does not run tests — reads coverage from SonarQube API.
 */
class SonarCoverageEngine : CoverageEngine() {

    override fun isApplicableTo(conf: RunConfigurationBase<*>?): Boolean = false

    override fun canHavePerTestCoverage(conf: RunConfigurationBase<*>?): Boolean = false

    override fun createCoverageEnabledConfiguration(conf: RunConfigurationBase<*>?): CoverageEnabledConfiguration? = null

    override fun createCoverageSuite(
        covRunner: CoverageRunner,
        name: String,
        coverageDataFileProvider: CoverageFileProvider,
        filters: Array<out String>?,
        lastCoverageTimeStamp: Long,
        suiteToMerge: String?,
        coverageByTestEnabled: Boolean,
        branchCoverage: Boolean,
        trackTestFolders: Boolean,
        project: Project?
    ): CoverageSuite? {
        return SonarCoverageSuite(name, project, covRunner, this)
    }

    override fun createCoverageSuite(
        covRunner: CoverageRunner,
        name: String,
        coverageDataFileProvider: CoverageFileProvider,
        config: CoverageEnabledConfiguration
    ): CoverageSuite? {
        return SonarCoverageSuite(name, config.configuration.project, covRunner, this)
    }

    override fun createEmptyCoverageSuite(coverageRunner: CoverageRunner): CoverageSuite {
        return SonarCoverageSuite("SonarQube", null, coverageRunner, this)
    }

    override fun coverageEditorHighlightingApplicableTo(psiFile: PsiFile): Boolean {
        val name = psiFile.virtualFile?.name ?: return false
        return name.endsWith(".java") || name.endsWith(".kt")
    }

    override fun acceptedByFilters(psiFile: PsiFile, suite: CoverageSuite): Boolean = true

    override fun recompileProjectAndRerunAction(
        module: Module, suite: CoverageSuitesBundle, chooseSuiteAction: Runnable
    ): Boolean = false

    override fun getPresentableText(): String = "SonarQube Coverage"

    override fun getId(): String = "SonarQubeCoverageEngine"
}
```

- [ ] **Step 4: Create plugin-withCoverage.xml**

Create `src/main/resources/META-INF/plugin-withCoverage.xml`:

```xml
<idea-plugin>
    <extensions defaultExtensionNs="com.intellij">
        <coverageRunner
            implementation="com.workflow.orchestrator.sonar.coverage.SonarCoverageRunner"/>
        <coverageEngine
            implementation="com.workflow.orchestrator.sonar.coverage.SonarCoverageEngine"/>
    </extensions>
</idea-plugin>
```

- [ ] **Step 5: Add optional dependency in plugin.xml**

Add to the `<depends>` section:

```xml
<depends optional="true" config-file="plugin-withCoverage.xml">com.intellij.java.coverage</depends>
```

- [ ] **Step 6: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/coverage/
git add src/main/resources/META-INF/plugin-withCoverage.xml
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat(sonar): bridge SonarQube coverage into IntelliJ native coverage view"
```

---

## Verification

After all tasks are complete:

- [ ] `./gradlew :core:test` — all core tests pass
- [ ] `./gradlew :jira:test` — all jira tests pass
- [ ] `./gradlew verifyPlugin` — no API compatibility issues
- [ ] `./gradlew runIde` — manual verification:
  - Test Runner: local Maven test shows detailed results
  - Analyze > Inspect Code: SonarQube Issues inspection available
  - Run > Edit Configurations: "Bamboo Build" type available
  - Run > Show Coverage Data: SonarQube option available (if coverage plugin installed)
