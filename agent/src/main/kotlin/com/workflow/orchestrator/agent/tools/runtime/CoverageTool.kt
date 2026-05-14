package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolOutputConfig
import com.workflow.orchestrator.agent.tools.TestConsoleUtils
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.agent.util.ReflectionUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Coverage tool — runs tests with IntelliJ's native coverage executor (or JaCoCo) and returns
 * rich per-file coverage data: line hits, branch coverage (jumps + switches), method coverage,
 * and partial-coverage indicators.
 *
 * Works with both the IntelliJ native runner (.ic files) and JaCoCo (.exec files). IntelliJ
 * converts JaCoCo data to its own ProjectData format internally, so the same reflection-based
 * extraction path handles both runners.
 *
 * Actions:
 * - run_with_coverage: Launch tests via CoverageExecutor, return test results + full coverage detail
 * - get_file_coverage: Re-read coverage for a specific file from cached data (no re-run)
 */
class CoverageTool : AgentTool {

    override val name = "coverage"
    // run_with_coverage manages its own process timeout (up to MAX_TIMEOUT = 900 s).
    override val timeoutMs: Long get() = Long.MAX_VALUE

    override val description = """
Run tests with coverage analysis and retrieve rich per-file coverage data.

Actions and their parameters:
- run_with_coverage(test_class, method?, timeout?) → Run tests via IntelliJ Coverage executor, return test results + line/branch/method coverage
- get_file_coverage(file_path) → Re-read full coverage detail for a specific file from the last coverage run (no re-run)
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf("run_with_coverage", "get_file_coverage")
            ),
            "test_class" to ParameterProperty(
                type = "string",
                description = "Fully qualified test class name — for run_with_coverage"
            ),
            "method" to ParameterProperty(
                type = "string",
                description = "Test method name(s) — for run_with_coverage. Single: 'testFoo'. " +
                    "Multiple methods from the same class in one coverage run: 'testFoo,testBar,testBaz' " +
                    "(comma-separated, whitespace around commas trimmed). Requires JUnit 5 — TestNG multi-method " +
                    "returns a hard error (coverage aggregates only within one run; splitting would lose the merged snapshot)."
            ),
            "timeout" to ParameterProperty(
                type = "integer",
                description = "Seconds before test process is killed (default: 300, max: 900) — for run_with_coverage"
            ),
            "file_path" to ParameterProperty(
                type = "string",
                description = "File path (e.g. 'src/main/java/com/example/ServiceImpl.java') or fully qualified class name (e.g. 'com.example.ServiceImpl') — for get_file_coverage"
            ),
            "on_existing_suite" to ParameterProperty(
                type = "string",
                enumValues = listOf("replace", "append", "ignore", "ask"),
                description = "What to do when a previous coverage suite is already active — for run_with_coverage. " +
                    "'replace' (default): silently swap in the new run's data — fresh measurement. " +
                    "'append' / 'add' / 'merge': merge into existing suite — use for incremental coverage across many classes. " +
                    "'ignore': silently discard the new run's data. " +
                    "'ask': surface IntelliJ's native merge dialog (will block the agent until the user clicks). " +
                    "Default 'replace' prevents the merge dialog from freezing the agent loop."
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(
        WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER
    )
    override val outputConfig = ToolOutputConfig.COMMAND

    /** Cached coverage snapshot from the last run_with_coverage execution. */
    @Volatile
    internal var lastSnapshot: CoverageSnapshot? = null

    /** Diagnostic info from the last extraction attempt — included in error responses. */
    @Volatile
    internal var lastExtractionDiag: String? = null

    override fun documentation(): ToolDocumentation = toolDoc("coverage") {
        summary {
            technical("Two-action wrapper around IntelliJ's Coverage executor: `run_with_coverage` launches a JUnit/TestNG test (via reflective JUnit 5 PATTERNS for multi-method runs) under the Coverage runner and harvests the resulting ProjectData via `CoverageDataManager` reflection — line hits, jump branches, switch cases, and per-method rollups; `get_file_coverage` re-reads that cached snapshot for one class without rerunning. All run paths route through `RunInvocation` for leak-free disposal and reflectively flip `CoverageOptionsProvider.setOptionsToReplace(int)` to suppress the IDE's replace-or-append dialog before launch (restored in `finally`). REGISTRATION: gated by `ToolRegistrationFilter.shouldRegisterCoverageTool(ideContext)` which requires `edition == ULTIMATE || edition == PROFESSIONAL` — NOT just Java plugin presence. On IntelliJ IDEA Community or PyCharm Community, this tool is NOT registered even with the Java plugin installed; the coverage runner ships only with Ultimate/Professional editions.")
            plain("Like clicking IntelliJ's 'Run with Coverage' button and then reading the green/red gutter — but as data the agent can reason about. Tells the LLM exactly which lines, branches, and methods were exercised by a test, so it can write tests that actually cover the code instead of guessing.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.PROCESS_SPAWN)
        counterfactual(
            "Without `coverage`, the LLM runs tests via `java_runtime_exec(action=run_tests)` and never sees coverage data — at best it knows the tests passed. To get coverage the user has to manually run `mvn jacoco:report` (or the Gradle equivalent), open `build/reports/jacoco/test/html/index.html`, and copy-paste the missing lines back into chat. That round-trip turns a 1-tool-call workflow into a 5-message back-and-forth, and the LLM still ends up reasoning over rendered HTML instead of structured per-line data."
        )
        llmMistake("Runs `run_with_coverage` with `method=testFoo` (a single method) and concludes 'low coverage' from the sparse snapshot — single-method runs only cover the lines reachable from that one test, not the whole class. Mitigation: omit `method` to cover the class, or list multiple methods comma-separated.")
        llmMistake("Calls `run_with_coverage` on a TestNG class with `method='testA,testB'` — returns a hard 'config creation failed' error because TestNG has no JUnit 5 PATTERNS equivalent and the tool refuses to split (splitting would lose the merged snapshot). LLM must either pick a single method or switch to JUnit.")
        llmMistake("Skips `on_existing_suite` and the IDE pops the 'replace or append coverage suite?' modal mid-run — the agent loop hangs waiting for a click that will never come. The default policy is now `replace`, but LLMs that explicitly pass `on_existing_suite='ask'` re-introduce the freeze.")
        llmMistake("Calls `get_file_coverage` before `run_with_coverage` and gets 'No coverage data available' — the snapshot is in-memory only, populated by the most recent run. There is no persistence across plugin restarts.")
        llmMistake("Passes a file path with extension (`MyService.java`) to `get_file_coverage` and assumes the tool re-reads disk; in fact it matches against fully-qualified class names harvested from the snapshot — falls back to suffix match on simple class name, but the LLM sometimes tries paths like `src/main/java/...` that don't resolve.")
        flowchart("""
            flowchart TD
                A[LLM wants coverage] --> B{First call?}
                B -- yes --> C[run_with_coverage]
                B -- already ran --> D[get_file_coverage]
                C --> E[applyCoverageOptionProviderPolicy<br/>flip dialog policy via reflection]
                E --> F[RunInvocation.attachListener<br/>build env + Coverage executor]
                F --> G[Test process runs]
                G --> H[CoverageSuiteListener.coverageDataCalculated<br/>or fallback poll]
                H --> I[extractCoverageSnapshot<br/>reflective ProjectData walk]
                I --> J[Cache as lastSnapshot]
                J --> K[Format summary + return]
                K --> Z[finally:<br/>restore policy + Disposer.dispose]
                D --> M{lastSnapshot present?}
                M -- yes --> N[Look up by FQ class name]
                M -- no --> O[Try extractCoverageSnapshot one more time]
                N --> P[formatFileCoverageDetail]
                O -- still empty --> Q[Return 'No coverage data' error]
                O -- found --> P
        """)
        actions {
            action("run_with_coverage") {
                description {
                    technical("Builds a transient JUnit/TestNG run config (class mode for 0 methods, method mode for 1, JUnit 5 PATTERNS mode for 2+), launches it via `ProgramRunnerUtil.executeConfigurationAsync` with the Coverage executor, suspends until `TestResultsViewer.onTestingFinished` (or `processTerminated` fallback), then extracts a `CoverageSnapshot` via reflection over `CoverageDataManager` → suite bundle → `ProjectData.getClasses`. Per-line hits, FULL/PARTIAL/NONE status, jump branches, switch cases, and per-method rollups all returned. Suppresses the replace-or-append modal via `CoverageOptionsProvider.setOptionsToReplace(int)` and restores the user's saved policy in `finally`.")
                    plain("'Run this test (or this whole class) and tell me what got covered.' The agent gets back both the test result (passed/failed) and a coverage report keyed by class — line %, branch %, plus a list of every uncovered or partially-covered line with method names. Multi-method runs are merged into one snapshot — useful for 'cover this class with these three tests'.")
                }
                whenLLMUses("After writing or modifying tests, when the LLM wants to verify it actually exercises the new code paths — or when asked 'what's the coverage of class X?'")
                params {
                    required("test_class", "string") {
                        llmSeesIt("Fully qualified test class name — for run_with_coverage")
                        humanReadable("Which test class to run, by full name (`com.example.MyServiceTest`).")
                        whenPresent("The class is resolved via `JavaPsiFacade`; if found, the run config targets it.")
                        constraint("must be resolvable in the project's `GlobalSearchScope`")
                        example("com.example.OrderServiceTest")
                        example("com.acme.payment.PaymentProcessorTest")
                    }
                    optional("method", "string") {
                        llmSeesIt("Test method name(s) — for run_with_coverage. Single: 'testFoo'. Multiple methods from the same class in one coverage run: 'testFoo,testBar,testBaz' (comma-separated, whitespace around commas trimmed). Requires JUnit 5 — TestNG multi-method returns a hard error (coverage aggregates only within one run; splitting would lose the merged snapshot).")
                        humanReadable("Run only specific test method(s) instead of the whole class — comma-separated for batch. Whitespace around commas is fine.")
                        whenPresent("Methods are validated against `METHOD_NAME_REGEX`; 1 method → method-mode config, 2+ methods → JUnit 5 `bePatternConfiguration` setPatterns reflection (TestNG rejected with 'config creation failed').")
                        whenAbsent("Whole class is run.")
                        constraint("each name must match the Java identifier regex — no spaces, no '#', no '.', no ';'")
                        constraint("max 50 methods per run (MAX_METHODS_PER_RUN)")
                        constraint("TestNG + 2+ methods is rejected (no shell fallback — splitting loses snapshot aggregation)")
                        example("testCreateOrder")
                        example("testCreateOrder,testCancelOrder,testRefund")
                    }
                    optional("timeout", "integer") {
                        llmSeesIt("Seconds before test process is killed (default: 300, max: 900) — for run_with_coverage")
                        humanReadable("How long to wait for the test process before giving up. Coverage runs are slower than plain test runs.")
                        whenPresent("`timeoutSeconds` is coerced to [1, 900]; the suspendable launch is wrapped in `withTimeoutOrNull(timeoutSeconds * 1000)`.")
                        whenAbsent("Defaults to 300s (5 minutes).")
                        constraint("clamped to 1..900")
                        example("60")
                        example("600")
                    }
                    optional("on_existing_suite", "string") {
                        llmSeesIt("What to do when a previous coverage suite is already active — for run_with_coverage. 'replace' (default): silently swap in the new run's data — fresh measurement. 'append' / 'add' / 'merge': merge into existing suite — use for incremental coverage across many classes. 'ignore': silently discard the new run's data. 'ask': surface IntelliJ's native merge dialog (will block the agent until the user clicks). Default 'replace' prevents the merge dialog from freezing the agent loop.")
                        humanReadable("Which IntelliJ policy to use when there's already coverage data from a prior run. The IDE normally pops a 'replace or append' modal — this param suppresses it.")
                        whenPresent("Maps to REPLACE_SUITE=0 / ADD_SUITE=1 / IGNORE_SUITE=2 — applied via `CoverageOptionsProvider.setOptionsToReplace(int)` reflection. Prior value snapshotted and restored in `finally` (success / exception / timeout / cancel — all paths).")
                        whenAbsent("Defaults to 'replace' — the safest default for an LLM-driven loop. NOT 'ask', because that re-introduces the modal freeze.")
                        constraint("'ask' will block the agent loop on the IDE modal — only use when a human is actively watching")
                        constraint("unknown values silently fall back to 'replace' (defense against LLM typos)")
                        enumValue("replace", "append", "ignore", "ask")
                        example("replace")
                        example("append")
                    }
                }
                rejectsParam("file_path", "Only `get_file_coverage` reads `file_path` — `run_with_coverage` ignores it.")
                precondition("Coverage plugin must be enabled — `ExecutorRegistry.getExecutorById(\"Coverage\")` must resolve")
                precondition("project must be out of dumb mode (`waitForSmartModeOrTimeout` 60s ceiling) — a recent `run_command` may have triggered reindexing")
                precondition("`test_class` must resolve via JavaPsiFacade in `GlobalSearchScope.projectScope`")
                onSuccess("Returns a 2-section text result: first the test outcome (PASSED/FAILED/NO_TESTS_FOUND with stack traces if any — same classifier as `java_runtime_exec`), then a coverage block listing every covered file with line % and branch %, sorted by line coverage ascending. The summary line includes file count: `5 tests passed | 3 files covered`. The full snapshot is also cached in `lastSnapshot` for subsequent `get_file_coverage` calls.")
                onFailure("DUMB_MODE — indexing did not complete within 60s", "Returns DUMB_MODE error; the LLM should wait or re-trigger after VFS settles.")
                onFailure("missing test_class param", "Returns 'test_class parameter is required' validation error.")
                onFailure("invalid method name (spaces, '#', '.', ';')", "Returns 'invalid method name' error pointing at the offending name.")
                onFailure("too many methods (>50)", "Returns 'too many methods' error suggesting the LLM split into multiple calls.")
                onFailure("TestNG with 2+ methods", "createJUnitRunSettings returns null → tool returns 'config creation failed' with a hint that multi-method coverage requires JUnit 5. NO shell fallback (would lose snapshot aggregation).")
                onFailure("Coverage executor not registered (plugin disabled)", "Returns 'Coverage executor not available. Ensure the Coverage plugin is enabled.'")
                onFailure("test process build failure (BUILD FAILED before run)", "ExecutionListener.processNotStarted fires → tool returns 'BUILD FAILED — coverage run did not start' with `isError=true`.")
                onFailure("timeout", "Returns `[TIMEOUT] Coverage run timed out after Ns`; the outer `finally` disposes the invocation (kills handler, removes descriptor, restores policy).")
                onFailure("snapshot extraction returned no data", "Test result still returned, but coverage section reads 'No coverage data available' with extraction diagnostics. The IDE's coverage tab may still show data — `get_file_coverage` can re-read.")
                example("cover whole class") {
                    param("action", "run_with_coverage")
                    param("test_class", "com.example.OrderServiceTest")
                    outcome("Whole class runs under coverage; tool returns test outcome + line/branch % for every class touched. `lastSnapshot` is populated for follow-up `get_file_coverage` calls.")
                }
                example("multi-method JUnit 5 batch") {
                    param("action", "run_with_coverage")
                    param("test_class", "com.example.OrderServiceTest")
                    param("method", "testCreateOrder, testCancelOrder, testRefund")
                    outcome("All three methods run in one coverage session via JUnit 5 PATTERNS; one merged snapshot returned. Useful when the LLM wants 'these three tests, together, cover what?'.")
                    notes("Whitespace around commas is trimmed. TestNG multi-method would return a hard error here.")
                }
                example("incremental coverage across multiple classes") {
                    param("action", "run_with_coverage")
                    param("test_class", "com.example.PaymentServiceTest")
                    param("on_existing_suite", "append")
                    outcome("New run merges into the prior suite — useful for building a cumulative picture across many test classes. The IDE's coverage tab now shows the union.")
                }
                verdict {
                    keep("Single-call replacement for: build run config + launch under Coverage + parse JaCoCo XML + diff against source. Nothing else exposes branch coverage and per-method rollups in a single tool result.", VerdictSeverity.STRONG)
                }
            }
            action("get_file_coverage") {
                description {
                    technical("Re-reads the in-memory `lastSnapshot` (or one-shot `extractCoverageSnapshot` retry) for a single class. Matches input by exact FQ class name, then PSI-resolved class name (resolves a relative source path → FQ name via `LocalFileSystem` + `PsiJavaFile`), then suffix match on simple class name. Returns the same `formatFileCoverageDetail` block as `run_with_coverage` but for one class only — with FULL/PARTIAL/NONE per-line lists, jump/switch branch detail, and per-method rollups. No process spawn.")
                    plain("'I already ran coverage — show me just the missing lines for this one file.' Avoids paying for a full test rerun when the agent only needs to see uncovered branches in one class. Cheap.")
                }
                whenLLMUses("After a `run_with_coverage` call, when the LLM wants to drill into a specific class to find uncovered branches and methods to write follow-up tests for.")
                params {
                    required("file_path", "string") {
                        llmSeesIt("File path (e.g. 'src/main/java/com/example/ServiceImpl.java') or fully qualified class name (e.g. 'com.example.ServiceImpl') — for get_file_coverage")
                        humanReadable("Either a relative source path or a class FQN. The tool tries both.")
                        whenPresent("Resolved via three-step lookup: exact match on snapshot keys → PSI-resolved FQ name from path → suffix match on simple class name.")
                        constraint("must reference a class that was instrumented during the most recent `run_with_coverage` call")
                        example("com.example.OrderService")
                        example("src/main/java/com/example/OrderService.java")
                        example("OrderService.kt")
                    }
                }
                rejectsParam("test_class", "Only `run_with_coverage` reads `test_class` — `get_file_coverage` operates on the cached snapshot.")
                rejectsParam("method", "Only `run_with_coverage` reads `method`.")
                rejectsParam("timeout", "`get_file_coverage` does not spawn a process — no timeout needed.")
                rejectsParam("on_existing_suite", "Only `run_with_coverage` flips the IDE's suite policy.")
                precondition("a `run_with_coverage` call must have populated `lastSnapshot`, OR the IDE's CoverageDataManager must still hold a current suites bundle from a prior IDE-side run")
                onSuccess("Returns `=== ClassName ===` block with line %, branch %, uncovered/partial methods (sorted by lowest coverage first), and individual line entries for every PARTIAL or NONE line. Consecutive NONE lines with no branches and the same method are range-collapsed (`lines 43–45 [NONE]`). Capped at 50 line entries with overflow note.")
                onFailure("missing file_path param", "Returns 'file_path parameter is required' validation error.")
                onFailure("no snapshot cached and CoverageDataManager has no current suite", "Returns 'No coverage data available. Run run_with_coverage first.' with extraction diagnostics.")
                onFailure("class not found in snapshot", "Returns 'No coverage data found for X' along with the resolved FQ class name (if any) and the full list of available class keys, so the LLM can pick the right name.")
                example("drill into a specific class after a class-wide run") {
                    param("action", "get_file_coverage")
                    param("file_path", "com.example.OrderService")
                    outcome("Returns full per-line detail for `OrderService` — every uncovered line with its method, every partial branch (`if[0] → true=3, false=0`), every uncovered method. No new test process spawned.")
                }
                example("path-based lookup") {
                    param("action", "get_file_coverage")
                    param("file_path", "src/main/java/com/example/OrderService.java")
                    outcome("PSI resolves the path to `com.example.OrderService` and looks up the snapshot — returns the same detail as the FQN form.")
                }
                verdict {
                    keep("Cheap follow-up to `run_with_coverage` — no process spawn, no compile, just a Map lookup + format. Required to avoid expensive reruns when the LLM iterates on test gaps.", VerdictSeverity.STRONG)
                }
            }
        }
        verdict {
            keep("Two actions consolidate launch+harvest and re-read of the IDE's coverage data into a single tool. The LLM gets structured per-line/per-branch/per-method coverage that no shell command can replicate (mvn jacoco:report → HTML → manual parse) in one round trip. Multi-method JUnit 5 PATTERNS support is a real productivity win for 'cover this class with these tests' workflows.", VerdictSeverity.STRONG)
        }
        observation("`run_with_coverage` and `get_file_coverage` are bound by a single-tool cache (`lastSnapshot`, `@Volatile`). Tool instances are session-scoped; cross-session coverage is invisible to `get_file_coverage` unless the IDE's `CoverageDataManager` still holds a current suite. Worth flagging in the description.")
        observation("Reflection into `com.intellij.coverage.CoverageOptionsProvider.setOptionsToReplace(int)` is fragile — IntelliJ Platform refactors the Coverage plugin every few releases. The apply-then-restore pattern is pinned by `apply and restore calls are balanced and restore lives in finally` source-contract test, but a method rename or signature change will silently make the modal-suppression a no-op (the rest of the run still works — the loop just hangs on the first dialog). Re-validate on every Platform bump.")
        observation("`run_with_coverage` returns the test outcome AND the coverage block in one `ToolResult`. There is no way to suppress the test stdout — high-output tests will exercise the spill path (`ToolOutputConfig.COMMAND` cap=100K) and the LLM will read the spill file via `read_file`.")
        related("java_runtime_exec", Relationship.ALTERNATIVE, "Use `java_runtime_exec(action=run_tests)` when the LLM only cares about pass/fail and does NOT need coverage data — same multi-method JUnit 5 PATTERNS support, but no instrumentation overhead, no IDE Coverage plugin requirement.")
        related("python_runtime_exec", Relationship.ALTERNATIVE, "Same role for Python projects — `coverage.py` integration goes through pytest, not this tool. CoverageTool is Java/Kotlin/Groovy only (PSI lookup is JavaPsiFacade).")
        related("runtime_exec", Relationship.COMPLEMENT, "Use `runtime_exec(action=get_test_results)` to re-read structured test outcomes from a previous coverage run without a fresh process — pairs with `get_file_coverage` for low-cost iteration.")
        related("read_file", Relationship.COMPOSE_WITH, "After `get_file_coverage` reports uncovered lines `42–45`, the LLM typically follows with `read_file` on the source to see what those lines actually do — then writes a test.")
        downside("IntelliJ-IDEA-only — the Coverage executor is a JetBrains platform plugin, and the reflective `CoverageDataManager` / `ProjectData` extraction path is JVM-coverage specific. PyCharm's pytest-cov is a different runner; this tool will return 'no coverage data' for Python projects.")
        downside("Reflection into `CoverageOptionsProvider.setOptionsToReplace(int)` is fragile to platform-version changes. If the API renames/disappears, the modal-suppression silently no-ops and the agent loop will hang on the dialog. Source-contract test pins the call site shape but cannot validate the platform's contract.")
        downside("Snapshot aggregation only works within a single `run_with_coverage` call — multi-method runs aggregate, but two separate runs do not (unless `on_existing_suite=append` is used, which relies on IntelliJ's internal merge logic).")
        downside("TestNG with 2+ methods is a hard error with no shell fallback. The LLM occasionally hits this and has to either pick a single method or migrate the test class to JUnit 5.")
        downside("`lastSnapshot` is in-memory only — restarting the plugin/IDE wipes it. `get_file_coverage` will retry one extraction from the live `CoverageDataManager` but cannot recover snapshots from killed sessions.")
        downside("Heavy output — full coverage detail for a large class can run hundreds of lines (uncovered lines + branch detail). Mitigated by `MAX_UNCOVERED_ENTRIES=50` cap and `ToolOutputConfig.COMMAND` (100K) spill, but still costly to context.")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return when (action) {
            "run_with_coverage" -> executeRunWithCoverage(params, project)
            "get_file_coverage" -> executeGetFileCoverage(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions: run_with_coverage, get_file_coverage",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: run_with_coverage
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeRunWithCoverage(params: JsonObject, project: Project): ToolResult {
        // Bug 4 — Layer C: indexing barrier. A recent run_command may have triggered a wide
        // VFS refresh that fanned out into reindexing; wait for smart mode before launching.
        if (!com.workflow.orchestrator.core.vfs.waitForSmartModeOrTimeout(project)) {
            return ToolResult(
                content = "DUMB_MODE: indexing did not complete within 60s. " +
                    "A recent file mutation triggered reindexing. Retry shortly.",
                summary = "DUMB_MODE: timeout waiting for indexing",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )
        }
        val testClass = params["test_class"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Error: 'test_class' parameter is required for run_with_coverage",
                "Error: missing test_class",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val methodRaw = params["method"]?.jsonPrimitive?.contentOrNull
        // Same comma-split as java_runtime_exec: single name stays a single-entry list,
        // comma-separated values become a multi-method list, empty/separator-only fails.
        val methods: List<String> = methodRaw
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?: emptyList()

        if (methodRaw != null && methodRaw.isNotBlank() && methods.isEmpty()) {
            return ToolResult(
                content = "Error: 'method' parameter contains only separators/whitespace ('$methodRaw'). " +
                    "Pass a method name or omit the parameter to run the whole class.",
                summary = "Invalid method value",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        if (methods.size > MAX_METHODS_PER_RUN) {
            return ToolResult(
                content = "Error: too many methods requested (${methods.size}, max $MAX_METHODS_PER_RUN). " +
                    "Split into multiple run_with_coverage calls, or omit 'method' to cover the whole class.",
                summary = "Too many methods (${methods.size})",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        methods.firstOrNull { !it.matches(METHOD_NAME_REGEX) }?.let { bad ->
            return ToolResult(
                content = "Error: invalid method name '$bad'. Expected a Java identifier " +
                    "(letters/digits/underscore/\$, starting with a non-digit) — no spaces, no '#', " +
                    "no '.', no ';'.",
                summary = "Invalid method name '$bad'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val timeoutSeconds = (params["timeout"]?.jsonPrimitive?.intOrNull?.toLong() ?: DEFAULT_TIMEOUT)
            .coerceIn(1, MAX_TIMEOUT)

        val testTarget = when (methods.size) {
            0 -> testClass
            1 -> "$testClass#${methods.first()}"
            else -> "$testClass#${methods.joinToString(",")}"
        }

        val settings = createJUnitRunSettings(project, testClass, methods)
            ?: return ToolResult(
                "Error: Could not create run configuration for '$testTarget'. " +
                    "Ensure the class exists and JUnit plugin is available. " +
                    (if (methods.size >= 2) "Multi-method coverage requires JUnit 5; TestNG is not supported." else ""),
                "Error: config creation failed",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val coverageExecutor = com.intellij.execution.ExecutorRegistry.getInstance()
            .getExecutorById("Coverage")
            ?: return ToolResult(
                "Error: Coverage executor not available. Ensure the Coverage plugin is enabled.",
                "Error: no coverage executor",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        // Suppress IntelliJ's "replace-or-append coverage suite" dialog before launch.
        // IntelliJ reads CoverageOptionsProvider.getOptionToReplace() inside
        // coverageGathered() to decide between silent apply and popping the dialog.
        // Default ASK_ON_NEW_SUITE (=3) blocks the agent loop waiting for user input;
        // we temporarily flip it to the caller's requested policy (default REPLACE_SUITE=0)
        // and restore the user's saved value in the `finally` below.
        //
        // on_existing_suite param: "replace" (default) | "append" | "ignore" | "ask"
        //   - "ask" → don't touch the user setting; accepts that the dialog will appear.
        //   - unknown string → safer default = REPLACE_SUITE, not ASK, so an LLM typo
        //     doesn't re-introduce the freeze.
        val suitePolicyInt: Int? = when (params["on_existing_suite"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            null, "replace" -> 0             // REPLACE_SUITE
            "append", "add", "merge" -> 1    // ADD_SUITE
            "ignore" -> 2                    // IGNORE_SUITE
            "ask" -> null                    // leave user setting alone — dialog WILL appear
            else -> 0                        // unknown → replace (safer default than ask)
        }
        val priorSuiteOption: SuitePolicyPrior? = applyCoverageOptionProviderPolicy(project, suitePolicyInt)

        // Phase 3 / Task 2.5: route all listener/connection/descriptor tracking through
        // a single RunInvocation, mirroring the Task 2.3+2.4 refactor in JavaRuntimeExecTool.
        // The try/finally block below disposes the invocation on every exit path (success /
        // processNotStarted / timeout / exception / coroutine cancel), which in turn:
        //   - destroys the captured ProcessHandler if it hasn't terminated,
        //   - disconnects the build-phase MessageBusConnection (registered via
        //     invocation.subscribeTopic below),
        //   - runs the `removeRunContent` onDispose callback installed after the
        //     descriptor is captured (removes the descriptor from RunContentManager,
        //     which in turn disposes the TestResultsViewer and its EventsListener),
        //   - auto-cleans the 2-arg process listener attached via attachProcessListener,
        //   - disposes the CoverageSuiteListener (folded into invocation.onDispose),
        //   - cancels any poll scope launched inside processTerminated.
        //
        // The same finally also restores the user's CoverageOptionsProvider setting so
        // their next manual coverage run prompts as before.
        val invocation = project.service<AgentService>().newRunInvocation(
            "run-with-coverage-${System.currentTimeMillis()}"
        )
        try {
            val coverageDeferred = CompletableDeferred<CoverageSnapshot?>()
            val listenerDisposable = registerCoverageListener(project, coverageDeferred)
            listenerDisposable?.let {
                invocation.onDispose { Disposer.dispose(it) }
            }

            val result = withTimeoutOrNull<CoverageRunResult>(timeoutSeconds * 1000) {
                suspendCancellableCoroutine { continuation ->
                    // Single cleanup path: dispose the invocation. This destroys the
                    // process handler, disconnects the MessageBusConnection, removes
                    // the run content descriptor, and runs all auto-cleaning 2-arg
                    // process listeners — replacing the old manual destroy/disconnect
                    // dance.
                    continuation.invokeOnCancellation {
                        Disposer.dispose(invocation)
                    }
                    invokeLater {
                        try {
                            val env = com.intellij.execution.runners.ExecutionEnvironmentBuilder
                                .createOrNull(coverageExecutor, settings)
                                ?.build()

                            if (env == null) {
                                if (continuation.isActive) continuation.resume(
                                    CoverageRunResult("Error: could not build execution environment", "Error", testIsError = true)
                                )
                                return@invokeLater
                            }

                            val callback = object : com.intellij.execution.runners.ProgramRunner.Callback {
                                override fun processStarted(descriptor: RunContentDescriptor?) {
                                    if (descriptor == null) {
                                        if (continuation.isActive) continuation.resume(
                                            CoverageRunResult("Error: no descriptor from coverage run", "Error", testIsError = true)
                                        )
                                        return
                                    }
                                    invocation.descriptorRef.set(descriptor)
                                    val handler = descriptor.processHandler
                                    invocation.processHandlerRef.set(handler)

                                    // Register an onDispose callback that removes the descriptor
                                    // from RunContentManager — this is the release mechanism for
                                    // the TestResultsViewer (and its EventsListener) because
                                    // TestResultsViewer is Disposable with NO removeEventsListener
                                    // API. Per design, the literal `removeRunContent` call lives
                                    // here in the tool file (source-text test anchor).
                                    invocation.onDispose {
                                        val currentDesc = invocation.descriptorRef.get() ?: return@onDispose
                                        ApplicationManager.getApplication().invokeLater {
                                            com.intellij.execution.ui.RunContentManager.getInstance(project)
                                                .removeRunContent(coverageExecutor, currentDesc)
                                        }
                                    }

                                    val testConsole = TestConsoleUtils.unwrapToTestConsole(descriptor.executionConsole)
                                    if (testConsole != null) {
                                        // Phase 3 / Task 2.5: replace the raw
                                        // `resultsViewer.addEventsListener(...)` leak with
                                        // invocation.attachListener — which internally wraps the
                                        // listener in a defense-in-depth proxy that gates on the
                                        // invocation's disposed flag. Prevents stale resume() on an
                                        // already-consumed continuation after timeout/cancel. The
                                        // TestResultsViewer has no removeEventsListener API, so the
                                        // listener is released when the RunContentDescriptor is
                                        // disposed via removeRunContent (registered below).
                                        val eventsListener = object : TestResultsViewer.EventsListener {
                                            override fun onTestingFinished(sender: TestResultsViewer) {
                                                val root = sender.testsRootNode as? SMTestProxy.SMRootTestProxy
                                                if (root != null && continuation.isActive) {
                                                    val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                                                    invocation.onDispose { pollScope.cancel() }
                                                    pollScope.launch {
                                                        if (continuation.isActive) {
                                                            continuation.resume(interpretTestRoot(root, testTarget, this@CoverageTool, project).toCoverageRunResult())
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        invocation.attachListener(eventsListener, testConsole.resultsViewer)
                                    } else if (handler != null) {
                                        // Phase 3 / Task 2.5: the old raw `handler.addProcessListener(1-arg)`
                                        // leak is replaced with invocation.attachProcessListener, which
                                        // internally uses the 2-arg form addProcessListener(listener, disposable)
                                        // so the listener is auto-removed when the invocation disposes —
                                        // no manual removeProcessListener needed. The raw java.util.Timer
                                        // that previously fired a 2s delayed callback has been replaced
                                        // with a cancellable coroutine delay tied to invocation lifecycle
                                        // via invocation.onDispose.
                                        val terminateListener = object : ProcessAdapter() {
                                            override fun processTerminated(event: ProcessEvent) {
                                                val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                                                invocation.onDispose { pollScope.cancel() }
                                                pollScope.launch {
                                                    delay(2000)
                                                    if (continuation.isActive) {
                                                        val root = TestConsoleUtils.findTestRoot(descriptor)
                                                        if (root != null) {
                                                            continuation.resume(interpretTestRoot(root, testTarget, this@CoverageTool, project).toCoverageRunResult())
                                                        } else {
                                                            continuation.resume(
                                                                CoverageRunResult(
                                                                    "Tests completed for $testTarget but no structured results available.",
                                                                    "Tests completed"
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        invocation.attachProcessListener(handler, terminateListener)
                                    } else {
                                        if (continuation.isActive) continuation.resume(
                                            CoverageRunResult("Error: no process handler", "Error", testIsError = true)
                                        )
                                    }
                                }
                            }

                            try {
                                ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)
                            } catch (_: NoSuchMethodError) {
                                env.callback = callback
                                ProgramRunnerUtil.executeConfiguration(env, false, true)
                            }

                            // Build watchdog: detect before-run task failure via
                            // ExecutionListener.processNotStarted. Registered through
                            // invocation.subscribeTopic so disposal (on timeout / cancel /
                            // success) disconnects it automatically. No manual disconnect
                            // needed inside the listener.
                            val buildConn = project.messageBus.connect()
                            invocation.subscribeTopic(buildConn)
                            buildConn.subscribe(com.intellij.execution.ExecutionManager.EXECUTION_TOPIC,
                                object : com.intellij.execution.ExecutionListener {
                                    override fun processNotStarted(executorId: String, e: com.intellij.execution.runners.ExecutionEnvironment) {
                                        if (e == env) {
                                            if (continuation.isActive) {
                                                continuation.resume(CoverageRunResult(
                                                    "BUILD FAILED — coverage run did not start.\n\n" +
                                                        "Compilation failed before test execution. " +
                                                        "Fix the errors and try again.",
                                                    "Build failed before coverage",
                                                    testIsError = true,
                                                ))
                                            }
                                        }
                                    }
                                    override fun processStarted(executorId: String, e: com.intellij.execution.runners.ExecutionEnvironment, handler: ProcessHandler) {
                                        // Observation-only; teardown happens through invocation.dispose().
                                    }
                                }
                            )
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resume(
                                CoverageRunResult(
                                    "Error launching coverage run: ${e.message}",
                                    "Error: ${e.javaClass.simpleName}",
                                    testIsError = true,
                                )
                            )
                        }
                    }
                }
            }

            if (result == null) {
                // Timeout path: outer `finally { Disposer.dispose(invocation) }` handles all
                // cleanup (destroy handler, disconnect bus, remove descriptor, dispose
                // coverage listener). No manual cleanup needed here.
                return ToolResult(
                    "[TIMEOUT] Coverage run timed out after ${timeoutSeconds}s for $testTarget.",
                    "Coverage timeout",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            val snapshot = if (listenerDisposable != null) {
                withTimeoutOrNull(COVERAGE_LISTENER_TIMEOUT_MS) { coverageDeferred.await() }
                    ?: extractCoverageSnapshot(project)
            } else {
                delay(COVERAGE_FALLBACK_DELAY_MS)
                extractCoverageSnapshot(project)
            }

            lastSnapshot = snapshot

            val coverageSummary = if (snapshot != null && snapshot.files.isNotEmpty()) {
                formatCoverageSummary(snapshot)
            } else {
                val diag = lastExtractionDiag?.let { "\n\nExtraction diagnostics:\n$it" } ?: ""
                "\nCoverage: No coverage data available. " +
                    "The coverage tab in the IDE may still show results — use get_file_coverage to re-read.$diag"
            }

            val content = "${result.testResult}\n$coverageSummary"
            return ToolResult(
                content = content,
                summary = result.testSummary + if (snapshot != null) " | ${snapshot.files.size} files covered" else "",
                tokenEstimate = content.length / 4,
                isError = result.testIsError,
            )
        } finally {
            // Restore the user's CoverageOptionsProvider setting BEFORE disposing the
            // invocation, so the restore always runs even if the Disposer.dispose call
            // throws (defensive — Disposer rarely throws, but the ordering is free).
            restoreCoverageOptionProviderPolicy(project, priorSuiteOption)
            Disposer.dispose(invocation)
        }
    }

    /**
     * Snapshot of the user's prior CoverageOptionsProvider.getOptionToReplace() value,
     * used to restore the setting after an agent-initiated coverage run so the user's
     * next manual coverage run prompts (or auto-replaces) exactly as before.
     */
    private data class SuitePolicyPrior(val priorValue: Int)

    /**
     * Flip [CoverageOptionsProvider.setOptionsToReplace] to [policy] so IntelliJ's
     * `coverageGathered()` applies the merge decision silently instead of showing the
     * "replace or append" dialog that freezes the agent loop. Returns a snapshot of the
     * prior value for later restoration, or null when:
     *   - policy is null (caller requested "ask" — do not touch the user setting)
     *   - the Coverage plugin isn't loaded (Class.forName throws)
     *   - the reflection API signature has shifted in a newer IDE
     *
     * Tolerant of every failure mode: if we can't set the option, we can't suppress the
     * dialog, but the rest of the coverage run still works (just blocks on user click).
     */
    private fun applyCoverageOptionProviderPolicy(project: Project, policy: Int?): SuitePolicyPrior? {
        if (policy == null) return null
        return try {
            val clazz = Class.forName("com.intellij.coverage.CoverageOptionsProvider")
            val provider = clazz.getMethod("getInstance", Project::class.java).invoke(null, project)
                ?: return null
            val prior = clazz.getMethod("getOptionToReplace").invoke(provider) as Int
            clazz.getMethod("setOptionsToReplace", Int::class.javaPrimitiveType)
                .invoke(provider, policy)
            SuitePolicyPrior(prior)
        } catch (_: Throwable) { null }
    }

    /**
     * Restore the user's prior CoverageOptionsProvider.getOptionToReplace() value.
     * No-op when [prior] is null (either the caller opted into "ask" mode or the
     * apply step failed — nothing to restore). Silent on reflection failure; the
     * user can reset manually via Settings > Build > Coverage if this ever drifts.
     */
    private fun restoreCoverageOptionProviderPolicy(project: Project, prior: SuitePolicyPrior?) {
        if (prior == null) return
        try {
            val clazz = Class.forName("com.intellij.coverage.CoverageOptionsProvider")
            val provider = clazz.getMethod("getInstance", Project::class.java).invoke(null, project)
                ?: return
            clazz.getMethod("setOptionsToReplace", Int::class.javaPrimitiveType)
                .invoke(provider, prior.priorValue)
        } catch (_: Throwable) { /* best-effort — user can reset in Settings > Build > Coverage */ }
    }

    /**
     * Register a CoverageSuiteListener via reflection to be notified when coverage data
     * is ready. Returns a Disposable to unregister the listener, or null if registration failed.
     */
    private fun registerCoverageListener(
        project: Project,
        deferred: CompletableDeferred<CoverageSnapshot?>
    ): com.intellij.openapi.Disposable? {
        return try {
            val dataManagerClass = Class.forName("com.intellij.coverage.CoverageDataManager")
            val getInstanceMethod = dataManagerClass.getMethod("getInstance", Project::class.java)
            val dataManager = getInstanceMethod.invoke(null, project) ?: return null

            val listenerClass = Class.forName("com.intellij.coverage.CoverageSuiteListener")
            val listener = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, _ ->
                if (method.name == "coverageDataCalculated" && !deferred.isCompleted) {
                    val snapshot = extractCoverageSnapshot(project)
                    deferred.complete(snapshot)
                }
                null
            }

            val disposable = com.intellij.openapi.util.Disposer.newDisposable("CoverageTool-listener")
            val addListenerMethod = dataManagerClass.getMethod(
                "addSuiteListener",
                listenerClass,
                com.intellij.openapi.Disposable::class.java
            )
            addListenerMethod.invoke(dataManager, listener, disposable)
            disposable
        } catch (_: Exception) {
            null
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: get_file_coverage
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeGetFileCoverage(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file_path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Error: 'file_path' parameter is required for get_file_coverage",
                "Error: missing file_path",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        var snapshot = lastSnapshot
        if (snapshot == null || snapshot.files.isEmpty()) {
            snapshot = withContext(Dispatchers.IO) { extractCoverageSnapshot(project) }
            if (snapshot != null && snapshot.files.isNotEmpty()) lastSnapshot = snapshot
        }
        if (snapshot == null || snapshot.files.isEmpty()) {
            val diag = lastExtractionDiag?.let { "\n\nExtraction diagnostics:\n$it" } ?: ""
            return ToolResult(
                "No coverage data available. Run 'run_with_coverage' first to collect coverage data, " +
                    "or check that the Coverage plugin is enabled.$diag",
                "No coverage data",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val resolvedClassName = resolveToClassName(filePath, project)
        val simpleClassName = filePath.substringAfterLast('/').removeSuffix(".java").removeSuffix(".kt").removeSuffix(".groovy")

        val matchedEntry = snapshot.files.entries.find { it.key == filePath }
            ?: resolvedClassName?.let { cn -> snapshot.files.entries.find { it.key == cn } }
            ?: snapshot.files.entries.find { it.key.endsWith(".$simpleClassName") }
            ?: return ToolResult(
                "No coverage data found for '$filePath'." +
                    (resolvedClassName?.let { " (resolved to class: $it)" } ?: "") +
                    "\nAvailable classes: ${snapshot.files.keys.joinToString(", ")}",
                "File not in coverage",
                20,
                isError = true
            )

        val content = formatFileCoverageDetail(matchedEntry.key, matchedEntry.value)
        return ToolResult(
            content = content,
            summary = "${matchedEntry.key.substringAfterLast(".")} — ${String.format("%.1f", matchedEntry.value.lineCoveragePercent)}% lines, " +
                if (matchedEntry.value.totalBranches > 0) "${String.format("%.1f", matchedEntry.value.branchCoveragePercent)}% branches"
                else "no branch data",
            tokenEstimate = content.length / 4
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Coverage data extraction
    // Works for both IntelliJ native runner and JaCoCo — IntelliJ converts
    // JaCoCo exec data to its own ProjectData/ClassData/LineData format
    // internally, so the same reflection path handles both runners.
    // ══════════════════════════════════════════════════════════════════════

    private fun extractCoverageSnapshot(project: Project): CoverageSnapshot? {
        val diag = StringBuilder()
        return try {
            val dataManagerClass = Class.forName("com.intellij.coverage.CoverageDataManager")
            val dataManager = dataManagerClass.getMethod("getInstance", Project::class.java)
                .invoke(null, project)
            if (dataManager == null) {
                lastExtractionDiag = "CoverageDataManager.getInstance() returned null"
                return null
            }
            diag.appendLine("CoverageDataManager: ${dataManager.javaClass.name}")

            // Path A: getCurrentSuitesBundle() → bundle.getCoverageData()
            var projectData: Any? = null
            val bundle = ReflectionUtils.tryReflective {
                dataManager.javaClass.getMethod("getCurrentSuitesBundle").invoke(dataManager)
            }
            if (bundle != null) {
                diag.appendLine("getCurrentSuitesBundle(): ${bundle.javaClass.simpleName}")
                projectData = ReflectionUtils.tryReflective {
                    bundle.javaClass.getMethod("getCoverageData").invoke(bundle)
                }
                if (projectData != null) diag.appendLine("Path A: bundle.getCoverageData() OK")
            } else {
                diag.appendLine("getCurrentSuitesBundle() returned null")
            }

            // Path B: getSuites() → most recent suite → getCoverageData(manager)
            if (projectData == null) {
                try {
                    val suites = dataManager.javaClass.getMethod("getSuites").invoke(dataManager) as? Array<*>
                    diag.appendLine("getSuites() returned ${suites?.size ?: "null"} suites")
                    val latestSuite = suites?.lastOrNull()
                    if (latestSuite != null) {
                        val getCoverageData = latestSuite.javaClass.getMethod("getCoverageData", dataManagerClass)
                        projectData = getCoverageData.invoke(latestSuite, dataManager)
                        if (projectData != null) diag.appendLine("Path B: suite.getCoverageData(manager) OK")
                        else diag.appendLine("Path B: suite.getCoverageData(manager) returned null")
                    }
                } catch (e: Exception) {
                    diag.appendLine("Path B failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }

            if (projectData == null) {
                diag.appendLine("No ProjectData from either path")
                lastExtractionDiag = diag.toString()
                return null
            }

            @Suppress("UNCHECKED_CAST")
            val classes = projectData.javaClass.getMethod("getClasses").invoke(projectData) as? Map<String, Any>
            if (classes == null || classes.isEmpty()) {
                diag.appendLine("getClasses() returned null or empty")
                lastExtractionDiag = diag.toString()
                return null
            }
            diag.appendLine("Classes: ${classes.size}")

            val files = mutableMapOf<String, FileCoverageDetail>()
            var processed = 0; var skipped = 0
            for ((className, classData) in classes) {
                val detail = extractFileCoverageDetail(classData)
                if (detail != null) { files[className] = detail; processed++ } else skipped++
            }
            diag.appendLine("Processed: $processed, Skipped: $skipped, Files: ${files.size}")
            lastExtractionDiag = diag.toString()
            CoverageSnapshot(files)
        } catch (e: Exception) {
            diag.appendLine("EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            lastExtractionDiag = diag.toString()
            null
        }
    }

    /**
     * Extract full coverage detail for one class from its ClassData object.
     *
     * Extracts per-line hit counts, FULL/PARTIAL/NONE status, jump (if/else/ternary/&&/||)
     * branch counts, switch case counts, and per-method coverage rollups.
     * Only PARTIAL and NONE lines are stored in [FileCoverageDetail.lines] to keep output
     * concise — FULL lines are counted in totals but not stored individually.
     */
    private fun extractFileCoverageDetail(classData: Any): FileCoverageDetail? {
        val linesArray = ReflectionUtils.tryReflective {
            classData.javaClass.getMethod("getLines").invoke(classData) as? Array<*>
        } ?: return null

        var coveredLines = 0; var totalLines = 0
        var coveredBranches = 0; var totalBranches = 0
        val lineDetails = mutableListOf<LineCoverageDetail>()
        val methodStats = mutableMapOf<String, MethodStats>()

        for (lineObj in linesArray) {
            if (lineObj == null) continue
            val lineClass = lineObj.javaClass

            val lineNumber = ReflectionUtils.tryReflective { lineClass.getMethod("getLineNumber").invoke(lineObj) as? Int }
                ?: continue
            val hits = ReflectionUtils.tryReflective { lineClass.getMethod("getHits").invoke(lineObj) as? Int } ?: 0
            val methodSig = ReflectionUtils.tryReflective { lineClass.getMethod("getMethodSignature").invoke(lineObj) as? String }

            // getStatus() returns a byte (FULL=2, PARTIAL=1, NONE=0) — handle both Byte and Int boxing
            val statusInt = when (val raw = ReflectionUtils.tryReflective { lineClass.getMethod("getStatus").invoke(lineObj) }) {
                is Byte -> raw.toInt()
                is Int -> raw
                else -> if (hits > 0) 2 else 0
            }
            val status = when (statusInt) {
                2 -> LineCoverageStatus.FULL
                1 -> LineCoverageStatus.PARTIAL
                else -> LineCoverageStatus.NONE
            }

            totalLines++
            if (hits > 0) coveredLines++

            // Extract jump (if/else/ternary/&&/||) branch data
            val jumps = mutableListOf<JumpCoverageDetail>()
            val jumpsArray = ReflectionUtils.tryReflective { lineClass.getMethod("getJumps").invoke(lineObj) as? Array<*> }
            jumpsArray?.forEachIndexed { idx, jumpObj ->
                if (jumpObj == null) return@forEachIndexed
                val trueHits = ReflectionUtils.tryReflective { jumpObj.javaClass.getMethod("getTrueHits").invoke(jumpObj) as? Int } ?: 0
                val falseHits = ReflectionUtils.tryReflective { jumpObj.javaClass.getMethod("getFalseHits").invoke(jumpObj) as? Int } ?: 0
                jumps.add(JumpCoverageDetail(idx, trueHits, falseHits))
                totalBranches += 2
                if (trueHits > 0) coveredBranches++
                if (falseHits > 0) coveredBranches++
            }

            // Extract switch statement branch data
            val switches = mutableListOf<SwitchCoverageDetail>()
            val switchesArray = ReflectionUtils.tryReflective { lineClass.getMethod("getSwitches").invoke(lineObj) as? Array<*> }
            switchesArray?.forEachIndexed { idx, switchObj ->
                if (switchObj == null) return@forEachIndexed
                val keys = ReflectionUtils.tryReflective { switchObj.javaClass.getMethod("getKeys").invoke(switchObj) as? IntArray } ?: IntArray(0)
                // SwitchData.getHits() is the no-arg form returning int[] (parallel to getKeys()).
                // Not to be confused with a hypothetical parameterised variant — the IntelliJ
                // coverage API defines it as: public int[] getHits()
                val switchHits = ReflectionUtils.tryReflective { switchObj.javaClass.getMethod("getHits").invoke(switchObj) as? IntArray } ?: IntArray(0)
                val defaultHits = ReflectionUtils.tryReflective { switchObj.javaClass.getMethod("getDefaultHits").invoke(switchObj) as? Int } ?: 0

                val cases = mutableListOf<Pair<Int?, Int>>()
                keys.forEachIndexed { i, key ->
                    val h = if (i < switchHits.size) switchHits[i] else 0
                    cases.add(key to h)
                    totalBranches++
                    if (h > 0) coveredBranches++
                }
                // Default case
                cases.add(null to defaultHits)
                totalBranches++
                if (defaultHits > 0) coveredBranches++

                switches.add(SwitchCoverageDetail(idx, cases))
            }

            // Accumulate per-method stats
            if (methodSig != null) {
                val ms = methodStats.getOrPut(methodSig) { MethodStats() }
                ms.totalLines++
                if (hits > 0) ms.coveredLines++
                ms.totalBranches += jumps.size * 2 + switches.sumOf { it.cases.size }
                ms.coveredBranches += jumps.count { it.trueHits > 0 } + jumps.count { it.falseHits > 0 } +
                    switches.sumOf { sw -> sw.cases.count { (_, h) -> h > 0 } }
            }

            // Only store PARTIAL and NONE lines — FULL lines counted in totals only
            if (status != LineCoverageStatus.FULL) {
                lineDetails.add(LineCoverageDetail(lineNumber, hits, status, methodSig, jumps, switches))
            }
        }

        if (totalLines == 0) return null

        val methods = methodStats.map { (sig, ms) ->
            MethodCoverageDetail(sig, ms.coveredLines, ms.totalLines, ms.coveredBranches, ms.totalBranches)
        }.sortedWith(compareBy({ it.coveredLines > 0 }, { it.coveredLines.toDouble() / it.totalLines.coerceAtLeast(1) }))

        return FileCoverageDetail(
            coveredLines = coveredLines,
            totalLines = totalLines,
            coveredBranches = coveredBranches,
            totalBranches = totalBranches,
            lineCoveragePercent = coveredLines.toDouble() / totalLines * 100,
            branchCoveragePercent = if (totalBranches == 0) 0.0 else coveredBranches.toDouble() / totalBranches * 100,
            methods = methods,
            lines = lineDetails.sortedBy { it.lineNumber }
        )
    }

    /** Temporary accumulator for per-method stats during extraction. */
    private data class MethodStats(
        var coveredLines: Int = 0,
        var totalLines: Int = 0,
        var coveredBranches: Int = 0,
        var totalBranches: Int = 0
    )

    // ══════════════════════════════════════════════════════════════════════
    // Formatting
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Multi-file summary for run_with_coverage output.
     * Shows line % and branch % per file, sorted by line coverage ascending.
     */
    internal fun formatCoverageSummary(snapshot: CoverageSnapshot): String {
        val sb = StringBuilder()
        sb.appendLine("\nCoverage (${snapshot.files.size} files):")

        val sorted = snapshot.files.entries.sortedBy { it.value.lineCoveragePercent }
        for ((name, detail) in sorted) {
            val shortName = name.substringAfterLast('.')
            val linePct = String.format("%.1f", detail.lineCoveragePercent)
            val branchPart = if (detail.totalBranches > 0)
                ", ${String.format("%.1f", detail.branchCoveragePercent)}% branches"
            else ""
            sb.appendLine("  $shortName — ${linePct}% lines$branchPart")
        }

        val totalCovered = snapshot.files.values.sumOf { it.coveredLines }
        val totalLines = snapshot.files.values.sumOf { it.totalLines }
        val overallLinePct = if (totalLines == 0) 0.0 else totalCovered.toDouble() / totalLines * 100

        val totalCovBranches = snapshot.files.values.sumOf { it.coveredBranches }
        val totalBranches = snapshot.files.values.sumOf { it.totalBranches }

        sb.appendLine()
        if (totalBranches > 0) {
            val overallBranchPct = totalCovBranches.toDouble() / totalBranches * 100
            sb.appendLine("Overall: ${String.format("%.1f", overallLinePct)}% line coverage, ${String.format("%.1f", overallBranchPct)}% branch coverage")
        } else {
            sb.appendLine("Overall: ${String.format("%.1f", overallLinePct)}% line coverage")
        }

        return sb.toString().trimEnd()
    }

    /**
     * Full per-file coverage detail — used by both run_with_coverage (per file) and get_file_coverage.
     *
     * Format:
     *   === ClassName ===
     *   Lines   : 74.3%  (89/120 covered)
     *   Branches: 61.5%  (32/52 covered)
     *
     *   Methods (N uncovered / M total):
     *     ✗ methodName  — 0/12 lines, 0/4 branches
     *     ~ methodName  — 4/8 lines, 1/4 branches
     *
     *   Uncovered / Partial Lines:
     *     line 42  [NONE]    hits=0   method=methodName
     *       if[0] → true=0, false=3
     *     lines 43–45 [NONE] hits=0
     *     line 67  [PARTIAL] hits=5   method=methodName
     *       if[0] → true=5, false=0
     *       switch[0]: case(1)=3, case(2)=0, default=0
     */
    internal fun formatFileCoverageDetail(className: String, detail: FileCoverageDetail): String {
        val sb = StringBuilder()
        sb.appendLine("=== ${className.substringAfterLast('.')} ===")

        val linePct = String.format("%.1f", detail.lineCoveragePercent)
        sb.appendLine("Lines   : $linePct%  (${detail.coveredLines}/${detail.totalLines} covered)")

        if (detail.totalBranches > 0) {
            val branchPct = String.format("%.1f", detail.branchCoveragePercent)
            sb.appendLine("Branches: $branchPct%  (${detail.coveredBranches}/${detail.totalBranches} covered)")
        } else {
            sb.appendLine("Branches: N/A  (no branch data)")
        }

        // Methods section — only show uncovered (✗) and partial (~) methods
        val uncovered = detail.methods.filter { it.coveredLines == 0 }
        val partial = detail.methods.filter { it.coveredLines > 0 && it.coveredLines < it.totalLines }
        if (uncovered.isNotEmpty() || partial.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Methods (${uncovered.size} uncovered / ${detail.methods.size} total):")
            for (m in uncovered) {
                val branchStr = if (m.totalBranches > 0) ", ${m.coveredBranches}/${m.totalBranches} branches" else ""
                sb.appendLine("  ✗ ${m.signature.substringBefore("(")}  — ${m.coveredLines}/${m.totalLines} lines$branchStr")
            }
            for (m in partial) {
                val branchStr = if (m.totalBranches > 0) ", ${m.coveredBranches}/${m.totalBranches} branches" else ""
                sb.appendLine("  ~ ${m.signature.substringBefore("(")}  — ${m.coveredLines}/${m.totalLines} lines$branchStr")
            }
        } else if (detail.methods.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Methods: all covered (${detail.methods.size} total)")
        }

        // Uncovered / partial lines — with range collapsing for plain NONE lines
        if (detail.lines.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Uncovered / Partial Lines:")
            val rendered = buildUncoveredLineOutput(detail.lines)
            val toShow = rendered.take(MAX_UNCOVERED_ENTRIES)
            toShow.forEach { sb.appendLine(it) }
            if (rendered.size > MAX_UNCOVERED_ENTRIES) {
                sb.appendLine("  ... and ${rendered.size - MAX_UNCOVERED_ENTRIES} more uncovered/partial lines")
            }
        } else if (detail.coveredLines == detail.totalLines) {
            sb.appendLine()
            sb.appendLine("All lines covered.")
        }

        return sb.toString().trimEnd()
    }

    /**
     * Build the rendered list of uncovered/partial line strings.
     * Consecutive NONE lines with no branches and the same method are range-collapsed.
     */
    private fun buildUncoveredLineOutput(lines: List<LineCoverageDetail>): List<String> {
        val out = mutableListOf<String>()
        var rangeStart: LineCoverageDetail? = null
        var rangeEnd: LineCoverageDetail? = null

        fun flushRange() {
            val s = rangeStart ?: return
            val e = rangeEnd ?: return
            val methodStr = s.methodSignature?.let { "  method=${it.substringBefore("(")}" } ?: ""
            out.add(if (s.lineNumber == e.lineNumber)
                "  line ${s.lineNumber} [NONE]  hits=0$methodStr"
            else
                "  lines ${s.lineNumber}–${e.lineNumber} [NONE]  hits=0$methodStr"
            )
            rangeStart = null; rangeEnd = null
        }

        for (line in lines) {
            val collapsible = line.status == LineCoverageStatus.NONE && line.jumps.isEmpty() && line.switches.isEmpty()
            val consecutive = rangeEnd != null && line.lineNumber == rangeEnd!!.lineNumber + 1
            val sameMethod = rangeStart?.methodSignature == line.methodSignature

            if (collapsible && consecutive && sameMethod) {
                rangeEnd = line
            } else {
                flushRange()
                if (collapsible) {
                    rangeStart = line; rangeEnd = line
                } else {
                    val methodStr = line.methodSignature?.let { "  method=${it.substringBefore("(")}" } ?: ""
                    out.add("  line ${line.lineNumber} [${line.status}]  hits=${line.hits}$methodStr")
                    for (jump in line.jumps) {
                        out.add("    if[${jump.index}] → true=${jump.trueHits}, false=${jump.falseHits}")
                    }
                    for (sw in line.switches) {
                        val cases = sw.cases.joinToString(", ") { (key, h) ->
                            if (key == null) "default=$h" else "case($key)=$h"
                        }
                        out.add("    switch[${sw.index}]: $cases")
                    }
                }
            }
        }
        flushRange()
        return out
    }

    // ══════════════════════════════════════════════════════════════════════
    // Test results formatting
    //
    // CoverageRunResult is the data-class carrier we pass across the
    // withTimeoutOrNull boundary. Populate it from the canonical
    // [interpretTestRoot] ToolResult — same classifier as RuntimeExecTool and
    // JavaRuntimeExecTool, so a coverage-backed run can't report PASSED for
    // an empty suite or a runner crash.
    // ══════════════════════════════════════════════════════════════════════

    private fun ToolResult.toCoverageRunResult(): CoverageRunResult =
        CoverageRunResult(this.content, this.summary, this.isError)

    // ══════════════════════════════════════════════════════════════════════
    // Run configuration creation
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun createJUnitRunSettings(
        project: Project, className: String, methods: List<String>
    ): com.intellij.execution.RunnerAndConfigurationSettings? {
        return try {
            val runManager = RunManager.getInstance(project)
            val testFramework = detectTestFramework(project, className)
            val configTypeId = if (testFramework == "TestNG") "TestNG" else "JUnit"
            val testConfigType = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.find { type ->
                type.id == configTypeId || type.displayName == configTypeId
            } ?: return null
            val factory = testConfigType.configurationFactories.firstOrNull() ?: return null
            val isTestNG = testFramework == "TestNG"

            // Coverage has no shell fallback: multi-method on TestNG is a hard null.
            // Splitting into N runs defeats the point of run_with_coverage because each
            // run would emit its own snapshot that we can't merge back.
            if (isTestNG && methods.size >= 2) return null

            val simpleClass = className.substringAfterLast('.')
            val configName = when (methods.size) {
                0 -> "[Coverage] $simpleClass"
                1 -> "[Coverage] $simpleClass.${methods.first()}"
                else -> "[Coverage] $simpleClass.${methods.first()}+${methods.size - 1}more"
            }
            val settings = runManager.createConfiguration(configName, factory)
            val config = settings.configuration

            try {
                val dataMethodName = if (isTestNG) "getPersistantData" else "getPersistentData"
                val data = config.javaClass.methods.find { it.name == dataMethodName }?.invoke(config)
                    ?: return null
                // Three-way switch mirroring JavaRuntimeExecTool:
                //   0 methods → class mode
                //   1 method  → method mode
                //   2+ methods → JUnit 5 pattern mode (TestNG guarded above)
                val testType = when {
                    methods.isEmpty() -> if (isTestNG) "CLASS" else "class"
                    methods.size == 1 -> if (isTestNG) "METHOD" else "method"
                    else -> "pattern"
                }
                data.javaClass.getField("TEST_OBJECT").set(data, testType)
                data.javaClass.getField("MAIN_CLASS_NAME").set(data, className)
                ReflectionUtils.tryReflective {
                    data.javaClass.getField("PACKAGE_NAME").set(data, className.substringBeforeLast('.', ""))
                }
                when {
                    methods.size == 1 -> data.javaClass.getField("METHOD_NAME").set(data, methods.first())
                    methods.size >= 2 -> {
                        // Per JUnitConfiguration.bePatternConfiguration: each LinkedHashSet
                        // entry is one "fully.qualified.Class,methodName" pair. The backing
                        // field myPattern is private; setPatterns is the public API.
                        val patterns = java.util.LinkedHashSet<String>(methods.size).apply {
                            methods.forEach { add("$className,$it") }
                        }
                        data.javaClass.getMethod("setPatterns", java.util.LinkedHashSet::class.java)
                            .invoke(data, patterns)
                    }
                }
            } catch (_: Exception) { return null }

            val testModule = findModuleForClass(project, className) ?: return null
            try {
                config.javaClass.getMethod("setModule", com.intellij.openapi.module.Module::class.java)
                    .invoke(config, testModule)
            } catch (_: Exception) {
                try {
                    val configModule = config.javaClass.getMethod("getConfigurationModule").invoke(config)
                    configModule.javaClass.getMethod("setModule", com.intellij.openapi.module.Module::class.java)
                        .invoke(configModule, testModule)
                } catch (_: Exception) {}
            }

            settings.isTemporary = true
            settings
        } catch (_: Exception) { null }
    }

    private suspend fun detectTestFramework(project: Project, className: String): String {
        return try {
            smartReadAction(project) {
                val psiClass = JavaPsiFacade.getInstance(project)
                    .findClass(className, GlobalSearchScope.projectScope(project))
                    ?: return@smartReadAction "Unknown"
                val annotations = psiClass.annotations.map { it.qualifiedName.orEmpty() } +
                    psiClass.methods.flatMap { m -> m.annotations.map { it.qualifiedName.orEmpty() } }
                when {
                    annotations.any { it.startsWith("org.testng.") } -> "TestNG"
                    annotations.any { it.startsWith("org.junit.") } -> "JUnit"
                    else -> "Unknown"
                }
            }
        } catch (_: Exception) { "Unknown" }
    }

    private suspend fun findModuleForClass(project: Project, className: String): com.intellij.openapi.module.Module? {
        return try {
            smartReadAction(project) {
                val psiClass = JavaPsiFacade.getInstance(project)
                    .findClass(className, GlobalSearchScope.projectScope(project))
                    ?: return@smartReadAction null
                ModuleUtilCore.findModuleForPsiElement(psiClass)
            }
        } catch (_: Exception) { null }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Utilities
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun resolveToClassName(filePath: String, project: Project): String? {
        return try {
            readAction {
                val basePath = project.basePath ?: return@readAction null
                val absolutePath = if (filePath.startsWith("/") || filePath.contains(":\\")) filePath
                    else "$basePath/$filePath"
                val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .findFileByIoFile(java.io.File(absolutePath)) ?: return@readAction null
                val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vf) ?: return@readAction null
                if (psiFile is com.intellij.psi.PsiJavaFile) {
                    val pkg = psiFile.packageName
                    val cls = psiFile.classes.firstOrNull()?.name ?: return@readAction null
                    return@readAction if (pkg.isNotBlank()) "$pkg.$cls" else cls
                }
                try {
                    val ktFileClass = Class.forName("org.jetbrains.kotlin.psi.KtFile")
                    if (ktFileClass.isInstance(psiFile)) {
                        val pkg = ktFileClass.getMethod("getPackageFqName").invoke(psiFile)?.toString() ?: ""
                        val cls = vf.nameWithoutExtension
                        return@readAction if (pkg.isNotBlank()) "$pkg.$cls" else cls
                    }
                } catch (_: Exception) {}
                null
            }
        } catch (_: Exception) { null }
    }

    companion object {
        private const val DEFAULT_TIMEOUT = 300L
        private const val MAX_TIMEOUT = 900L
        private const val COVERAGE_LISTENER_TIMEOUT_MS = 30_000L
        private const val COVERAGE_FALLBACK_DELAY_MS = 5_000L
        private const val MAX_UNCOVERED_ENTRIES = 50

        /**
         * Collapse a sorted list of line numbers into contiguous ranges.
         * E.g., [3, 4, 5, 10, 12, 13] → [3..5, 10..10, 12..13]
         */
        internal fun collapseToRanges(lines: List<Int>): List<IntRange> {
            if (lines.isEmpty()) return emptyList()
            val sorted = lines.sorted()
            val ranges = mutableListOf<IntRange>()
            var start = sorted[0]; var end = sorted[0]
            for (i in 1 until sorted.size) {
                if (sorted[i] == end + 1) end = sorted[i]
                else { ranges.add(start..end); start = sorted[i]; end = sorted[i] }
            }
            ranges.add(start..end)
            return ranges
        }

        /** Test helper — exposes formatCoverageSummary for unit tests. */
        fun formatCoverageSnapshotPublic(snapshot: CoverageSnapshot): String =
            CoverageTool().formatCoverageSummary(snapshot)

        /** Test helper — exposes formatFileCoverageDetail for unit tests. */
        fun formatFileCoverageDetailPublic(className: String, detail: FileCoverageDetail): String =
            CoverageTool().formatFileCoverageDetail(className, detail)
    }
}

// ══════════════════════════════════════════════════════════════════════════
// Data classes
// ══════════════════════════════════════════════════════════════════════════

/** Line coverage status matching IntelliJ's FULL=2 / PARTIAL=1 / NONE=0 constants. */
enum class LineCoverageStatus { FULL, PARTIAL, NONE }

/** Hit counts for one conditional jump (if/else, ternary, &&, ||) on a line. */
data class JumpCoverageDetail(
    val index: Int,       // jump index within the line (0-based)
    val trueHits: Int,    // times the true branch was taken
    val falseHits: Int    // times the false branch was taken
)

/**
 * Hit counts for one switch statement on a line.
 * [cases] pairs: key (null = default) → hit count.
 */
data class SwitchCoverageDetail(
    val index: Int,
    val cases: List<Pair<Int?, Int>>
)

/** Full coverage detail for one executable line. */
data class LineCoverageDetail(
    val lineNumber: Int,
    val hits: Int,                          // execution count (0 = not covered)
    val status: LineCoverageStatus,         // FULL / PARTIAL / NONE
    val methodSignature: String?,           // JVM method descriptor this line belongs to
    val jumps: List<JumpCoverageDetail>,    // empty if no conditional branches on this line
    val switches: List<SwitchCoverageDetail>
)

/** Per-method coverage rollup derived from per-line method signatures. */
data class MethodCoverageDetail(
    val signature: String,      // JVM method descriptor (e.g. "processOrder(Lcom/example/Order;)V")
    val coveredLines: Int,
    val totalLines: Int,
    val coveredBranches: Int,
    val totalBranches: Int
)

/**
 * Full coverage detail for one class — returned by both run_with_coverage and get_file_coverage.
 *
 * [lines] contains only PARTIAL and NONE lines to keep output concise.
 * FULL lines are counted in [coveredLines] / [totalLines] but not stored individually.
 */
data class FileCoverageDetail(
    val coveredLines: Int,
    val totalLines: Int,
    val coveredBranches: Int,
    val totalBranches: Int,
    val lineCoveragePercent: Double,
    val branchCoveragePercent: Double,   // 0.0 when totalBranches == 0
    val methods: List<MethodCoverageDetail>,
    val lines: List<LineCoverageDetail>  // only PARTIAL and NONE
)

/** Top-level snapshot from a coverage run — keyed by fully qualified class name. */
data class CoverageSnapshot(val files: Map<String, FileCoverageDetail>)

internal data class CoverageRunResult(
    val testResult: String,
    val testSummary: String,
    val testIsError: Boolean = false,
)
