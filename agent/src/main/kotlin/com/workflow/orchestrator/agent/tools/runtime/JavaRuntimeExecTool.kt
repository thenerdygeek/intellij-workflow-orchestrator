package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.compiler.CompilationStatusListener
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.compiler.CompilerTopics
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolOutputConfig
import com.workflow.orchestrator.agent.tools.TestConsoleUtils
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.builtin.RunCommandTool
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.core.util.BuildToolExecutableResolver
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import com.intellij.task.ProjectTaskManager

/**
 * Matches a real Gradle `> Task :...:test` progress line, ignoring `compileTestJava`,
 * `testClasses`, and arbitrary log lines that happen to contain the substring `:test `.
 *
 * - `^> Task :` — anchored (MULTILINE) so we only match the canonical Gradle progress
 *   prefix at line start, not `:test ` appearing in random log noise.
 * - `(\S*:)*` — allows any number of nested project path segments
 *   (e.g. `> Task :services:auth:test`); the trailing `:` before `test` ensures the
 *   task name is actually `test` and not `testClasses`.
 * - `\btest\b` — whole-word match so `testIntegration` / `testClasses` don't match.
 */
internal val GRADLE_TEST_TASK_REGEX: Regex = Regex("""^> Task :(\S*:)*test\b""", RegexOption.MULTILINE)

/**
 * Upper bound on methods accepted in a single `run_tests` invocation. Beyond this, the
 * LLM is asked to split — the Maven `-Dtest=Class#a+b+...` command line grows linearly,
 * and a few dozen failures are the natural human-readable batch anyway.
 */
internal const val MAX_METHODS_PER_RUN = 50

/**
 * Java/Kotlin method identifiers only — rejects `#`, `.`, whitespace, and any
 * separator the LLM might emit by mistake (e.g. `;`, space-delimited). Ensures a
 * `methods=["Other#foo"]`-style smuggled class never slips past the regex.
 */
internal val METHOD_NAME_REGEX: Regex = Regex("""^[A-Za-z_$][A-Za-z0-9_$]*$""")

/**
 * Java/Kotlin-specific runtime execution — runs JUnit/TestNG tests and compiles modules
 * via IntelliJ's CompilerManager. Registered only when the Java plugin is present
 * (see ToolRegistrationFilter.shouldRegisterJavaBuildTools).
 *
 * Split from the universal RuntimeExecTool so the LLM on PyCharm never sees these
 * actions (which cannot work without JavaPsiFacade + CompilerManager).
 */
class JavaRuntimeExecTool : AgentTool {

    /** Carries both the run settings and the resolved module out of [createJUnitRunSettings]. */
    private data class JUnitLaunchSpec(
        val settings: RunnerAndConfigurationSettings,
        val module: com.intellij.openapi.module.Module
    )

    override val name = "java_runtime_exec"
    // run_tests manages its own process + test-tree timeout (up to RUN_TESTS_MAX_TIMEOUT = 900 s).
    override val timeoutMs: Long get() = Long.MAX_VALUE

    override val description = """
Java/Kotlin runtime execution — JUnit/TestNG test running, module compilation, and rerun of failed tests.

Actions and their parameters:
- run_tests(class_name, method?, timeout?, use_native_runner?) → Run tests for a specific Java/Kotlin class via IntelliJ's JUnit/TestNG runner, with Maven/Gradle shell fallback (timeout default 300s, max 900s). class_name is required and must be fully qualified — use test_finder to discover test classes first. `method` accepts a single name ('testFoo') or a comma-separated list ('testFoo,testBar,testBaz') to run several methods from the same class in one launch; output is aggregated into a single result.
- compile_module(module?, check_dependents?, refresh_maven_first?) → Compile a Java/Kotlin module via CompilerManager. If `module` is omitted, compiles the entire project. When `module` is given and `check_dependents=true`, also recompiles modules that depend on it (catches downstream ABI breakage after editing an upstream module). Default check_dependents is false. Set `refresh_maven_first=true` after a CLI `mvn install` to trigger MavenProjectsManager reimport (~30s) before compiling so newly-published artifacts are picked up.
- rerun_failed_tests(session_id?) → Re-run only the failed/errored tests from the last test session. Resolves the most-recent test run via RunContentManager (or the session matching session_id if provided). Returns NO_PRIOR_TEST_SESSION if no test session exists, or an informational message if all tests passed.

description optional: shown to user in approval dialog on run_tests, compile_module, rerun_failed_tests.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf("run_tests", "compile_module", "rerun_failed_tests")
            ),
            "class_name" to ParameterProperty(
                type = "string",
                description = "Fully qualified test class name (required for run_tests — use test_finder to discover classes)"
            ),
            "method" to ParameterProperty(
                type = "string",
                description = "Test method name(s) — for run_tests. Single: 'testFoo'. " +
                    "Multiple methods from the same class in one launch: 'testFoo,testBar,testBaz' " +
                    "(comma-separated, whitespace around commas is trimmed). JUnit 5 and " +
                    "Maven/Gradle shell support multi-method natively; TestNG auto-falls back to shell."
            ),
            "timeout" to ParameterProperty(
                type = "integer",
                description = "Seconds before test process is killed (default: 300, max: 900) — for run_tests"
            ),
            "use_native_runner" to ParameterProperty(
                type = "boolean",
                description = "Use IntelliJ native test runner (true) or Maven/Gradle shell (false). Default: true — for run_tests"
            ),
            "module" to ParameterProperty(
                type = "string",
                description = "Module name — for compile_module (compiles entire project if omitted)"
            ),
            "check_dependents" to ParameterProperty(
                type = "boolean",
                description = "When true (and `module` is set), also recompile modules that depend on the target — catches downstream ABI breakage after editing an upstream module. Default: false — for compile_module"
            ),
            "refresh_maven_first" to ParameterProperty(
                type = "boolean",
                description = "When true, trigger a Maven reimport (MavenProjectsManager.forceUpdateAllProjects or addManagedFilesOrUnignore for unimported pom.xml) and wait up to 30s for it to finish before compiling — picks up classpath changes from a prior `run_command mvn install`. Default: false. Set true after any `mvn install`/`mvn deploy` that wrote new SNAPSHOT versions to ~/.m2 — for compile_module"
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description of what this action does and why (shown to user in approval dialog) — for run_tests, compile_module, rerun_failed_tests"
            ),
            "session_id" to ParameterProperty(
                type = "string",
                description = "Name or partial name of the prior test session to rerun failures from — for rerun_failed_tests. Defaults to most-recent test session when omitted."
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(
        WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER
    )
    override val outputConfig = ToolOutputConfig.COMMAND

    override fun documentation(): ToolDocumentation = toolDoc("java_runtime_exec") {
        summary {
            technical("Java/Kotlin runtime execution meta-tool wrapping IntelliJ's JUnit/TestNG native runner, CompilerManager-driven module compilation, and a failed-tests rerun path. Registered only when the Java plugin is present (ToolRegistrationFilter.shouldRegisterJavaBuildTools). run_tests routes through a native-runner-first / Maven-Gradle-shell-fallback dispatcher with a BuildSystemValidator pre-flight and a per-launch RunInvocation for deterministic disposal.")
            plain("How the agent runs Java/Kotlin tests and compiles modules without shelling out. It clicks the green Run arrow on JUnit/TestNG tests, compiles a module the way the Build menu would, and reruns only the tests that just failed — using IntelliJ's own machinery so results show up in the Test Results tool window the user is used to seeing.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.PROCESS_SPAWN)
        counterfactual(
            "Without java_runtime_exec the LLM falls back to `run_command mvn test` or `./gradlew test`. " +
                "That works for whole-module runs but loses: IDE JUnit XML parsing and structured pass/fail counts, " +
                "native Test Results tool-window integration (user sees nothing appear), per-method targeting without " +
                "writing Maven Surefire `-Dtest=` or Gradle `--tests` flags by hand, BuildSystemValidator pre-flight " +
                "that catches main-source classes before the JVM is even started, the compile-before-run guard " +
                "(commit 9b164bf3) that prevents `initializationError`, and the RunInvocation disposal contract " +
                "that prevents Run-tab leaks across repeated agent runs."
        )
        verdict {
            keep(
                "Test running is the most irreplaceable action in the agentic TDD loop. The native runner path " +
                    "uniquely provides structured test results, IDE Run-tab integration, multi-method batching via " +
                    "JUnit 5 PATTERNS, and compile-before-run safety. No shell command reproduces all four at once. " +
                    "Registered only when the Java plugin is present so PyCharm users never see it.",
                VerdictSeverity.STRONG
            )
        }
        llmMistake(
            "Passes a simple class name instead of a fully qualified one — e.g. 'MyServiceTest' instead of " +
                "'com.example.MyServiceTest'. The validator rejects it immediately; the LLM should call test_finder " +
                "first to get the fully qualified name."
        )
        llmMistake(
            "Passes method names containing '#' or '.' — e.g. 'MyServiceTest#testFoo' instead of just 'testFoo'. " +
                "METHOD_NAME_REGEX rejects these; the full outer\$inner path belongs in class_name, not in method."
        )
        llmMistake(
            "Calls run_tests when compile_module has already returned errors, expecting it to succeed. " +
                "Fix the compile errors first — run_tests calls ProjectTaskManager.build() before launching and will " +
                "return per-file compile errors again instead of test results."
        )
        llmMistake(
            "Calls rerun_failed_tests immediately after calling run_tests in the same tool-call sequence, " +
                "before the first run has completed and populated RunContentManager. The descriptor won't be there yet; " +
                "await the run_tests result first."
        )
        llmMistake(
            "Passes more than 50 method names in a single 'method' string, hitting the MAX_METHODS_PER_RUN cap. " +
                "Split into multiple run_tests calls or omit the method parameter to run the whole class."
        )
        llmMistake(
            "Relies on run_tests to detect a TestNG class with 2+ methods via the native runner — TestNG has no " +
                "PATTERNS equivalent, so the tool silently routes to the Maven/Gradle shell fallback. JUnit 5 is " +
                "needed for native multi-method targeting."
        )
        downside(
            "PATTERNS reflection: multi-method JUnit 5 native routing reflects into the JUnit 5 PATTERNS field " +
                "at runtime. If the IntelliJ platform renames or removes this field in a future release, the reflection " +
                "will throw and the native runner will fall back to shell — which is safe but silently downgrades the UX."
        )
        downside(
            "compile_module has a hard 120s timeout enforced by withTimeoutOrNull. Very large projects (millions of " +
                "source lines) may time out even on a fast machine. No incremental-only option — CompilerManager.make " +
                "always runs whatever the compiler decides is dirty."
        )
        downside(
            "rerun_failed_tests resolves descriptors from RunContentManager.allDescriptors, which includes tabs the " +
                "user closed but didn't terminate. If a stale descriptor happens to sort as 'most recent', the rerun " +
                "target is wrong. Pass session_id to disambiguate."
        )
        downside(
            "BuildSystemValidator pre-flight adds a smartReadAction round-trip before every run_tests dispatch. " +
                "On cold IDE startup (before indexing completes) this adds latency and may block up to 60s on DUMB_MODE."
        )
        related("python_runtime_exec", Relationship.ALTERNATIVE, "Use instead for pytest classes on PyCharm — same concept, Python runner path. Parallel tool: both exist side by side, Java vs Python.")
        related("runtime_exec", Relationship.COMPLEMENT, "Use runtime_exec to observe the output of a JUnit run that is already live (get_run_output, get_test_results), or to launch any existing run configuration (run_config). java_runtime_exec creates and launches ephemeral configs; runtime_exec reads the results.")
        related("coverage", Relationship.COMPOSE_WITH, "Use coverage(action=run_with_coverage) after a successful run_tests to collect line/branch coverage for the same class. coverage uses the same JUnit 5 PATTERNS reflection and RunInvocation pattern as java_runtime_exec.")
        related("test_finder", Relationship.COMPLEMENT, "Use test_finder to discover the fully qualified class name before calling run_tests. java_runtime_exec requires a FQCN; test_finder is the lookup path.")
        observation("compile_module could plausibly merge with the build meta-tool's compile action, but CompilerManager.make semantics (per-module scope, includeDependents) are different from Gradle/Maven task dispatch — the overlap is shallow.")
        observation("rerun_failed_tests has no per-action timeout parameter; it inherits the run_tests default (300s). A dedicated timeout param for rerun would add clarity at low cost.")
        actions {
            action("run_tests") {
                description {
                    technical("Runs JUnit/TestNG tests for a specific class. Native runner path uses IntelliJ's JUnit configuration + ProgramRunnerUtil; falls back to Maven/Gradle shell when native setup fails or use_native_runner=false. Multi-method via JUnit 5 PATTERNS field; TestNG with 2+ methods auto-routes to shell. Pre-flight BuildSystemValidator catches main-source classes, zero @Test methods, missing settings.gradle entries. RunInvocation guarantees process+listener disposal on every exit path.")
                    plain("Runs the specified test class (and optionally specific methods inside it). Uses the IDE's real test runner so results appear in the Test Results panel; falls back to `mvn test` / `./gradlew test` if the native runner can't be set up.")
                }
                whenLLMUses("To execute a specific JUnit/TestNG test class after writing or fixing tests, to verify a change, or to drive a TDD red-green loop. Use test_finder first if the class name isn't known.")
                params {
                    required("class_name", "string") {
                        llmSeesIt("Fully qualified test class name (required for run_tests — use test_finder to discover classes)")
                        humanReadable("Fully qualified test class — the one the test runner should target.")
                        whenPresent("Resolved against the project; module is auto-detected from this class.")
                        constraint("must be a fully qualified Java/Kotlin class name")
                        example("com.example.MyServiceTest")
                    }
                    optional("method", "string") {
                        llmSeesIt("Test method name(s) — for run_tests. Single: 'testFoo'. " +
                            "Multiple methods from the same class in one launch: 'testFoo,testBar,testBaz' " +
                            "(comma-separated, whitespace around commas is trimmed). JUnit 5 and " +
                            "Maven/Gradle shell support multi-method natively; TestNG auto-falls back to shell.")
                        humanReadable("Optional method (or comma-separated methods) to scope the run to. Omit to run the whole class.")
                        whenPresent("Only the named methods run; multi-method uses JUnit 5 PATTERNS on the native runner, or shell fallback for TestNG.")
                        whenAbsent("All test methods in the class run.")
                        constraint("each name must be a bare Java identifier (no '#', '.', whitespace, or ';'); MAX_METHODS_PER_RUN=50")
                        example("testFoo")
                        example("testFoo,testBar,testBaz")
                    }
                    optional("timeout", "integer") {
                        llmSeesIt("Seconds before test process is killed (default: 300, max: 900) — for run_tests")
                        humanReadable("Wall-clock cap on the test run.")
                        whenPresent("Test process is killed after this many seconds.")
                        whenAbsent("Defaults to 300s.")
                        constraint("clamped to [1, 900]")
                        example("600")
                    }
                    optional("use_native_runner", "boolean") {
                        llmSeesIt("Use IntelliJ native test runner (true) or Maven/Gradle shell (false). Default: true — for run_tests")
                        humanReadable("Prefer the IDE's JUnit/TestNG runner, or go straight to Maven/Gradle shell.")
                        whenPresent("If true, native runner is tried first; if false, dispatch goes straight to shell.")
                        whenAbsent("Defaults to true.")
                        example("false")
                    }
                    optional("description", "string") {
                        llmSeesIt("Brief description of what this action does and why (shown to user in approval dialog) — for run_tests, compile_module, rerun_failed_tests")
                        humanReadable("Short reason shown in the user's approval dialog.")
                        whenPresent("Surfaced in the approval gate so the user sees why the agent wants to run.")
                        whenAbsent("Approval dialog shows a generic label.")
                        example("Verify MyServiceTest after refactor")
                    }
                }
                onSuccess("Returns aggregated test output prefixed with a breadcrumb ('Running tests in module: X (Build path: Y, N test methods detected in Z)'); shell fallback parses Surefire/Gradle reports; empty suites return NO_TESTS_FOUND.")
                onFailure("indexing didn't finish in 60s", "Returns DUMB_MODE — wait and retry shortly.")
                onFailure("build/compile fails before tests run", "Returns per-file compile errors (path:line:col — message) via formatCompileErrors instead of a bare count.")
                onFailure("native runner setup fails with use_native_runner=true", "Returns an error explaining the cause and the option to pass use_native_runner=false.")
                onFailure("invalid method name", "Returns 'Error: invalid method name' with the bad token; nested class methods require class_name to include the outer\$inner path.")
                example("run a single test method") {
                    param("action", "run_tests")
                    param("class_name", "com.example.MyServiceTest")
                    param("method", "testFoo")
                    outcome("Runs only testFoo on the IntelliJ JUnit runner; results appear in Test Results.")
                }
                example("run several methods in one launch") {
                    param("action", "run_tests")
                    param("class_name", "com.example.MyServiceTest")
                    param("method", "testFoo,testBar,testBaz")
                    outcome("All three methods run in one JUnit 5 PATTERNS launch; aggregated output returned as a single ToolResult.")
                }
                verdict {
                    keep(
                        "Core TDD action — the LLM calls this dozens of times per coding session. " +
                            "No practical substitute that also gives structured pass/fail results, " +
                            "multi-method batching, and native IDE integration.",
                        VerdictSeverity.STRONG
                    )
                }
            }
            action("compile_module") {
                description {
                    technical("Compiles a module via CompilerManager.make on either a module compile scope (with optional includeDependents) or the whole project compile scope when module is omitted. Wraps in a 120s timeout; errors return per-file messages via formatCompileErrors. Optional refresh_maven_first triggers MavenProjectsManager reimport (forceUpdateAllProjects, or addManagedFilesOrUnignore for unimported pom.xml) and awaits up to 30s before compile.")
                    plain("Triggers IntelliJ's Build for a module — same as Build > Build Module — and reports compile errors per file. Set check_dependents=true to also rebuild what depends on the module. Set refresh_maven_first=true after a CLI `mvn install` so the build picks up the newly-published JARs.")
                }
                whenLLMUses("After editing source in a module to verify it still compiles, or after an upstream module change to catch downstream ABI breakage in dependents. Also after `run_command mvn install` — set refresh_maven_first=true so the compile sees the freshly-published artifacts.")
                params {
                    optional("module", "string") {
                        llmSeesIt("Module name — for compile_module (compiles entire project if omitted)")
                        humanReadable("Which module to compile. Omit to compile the entire project.")
                        whenPresent("Compiles just that module's scope.")
                        whenAbsent("Compiles the entire project.")
                        example("myapp.core")
                    }
                    optional("check_dependents", "boolean") {
                        llmSeesIt("When true (and `module` is set), also recompile modules that depend on the target — catches downstream ABI breakage after editing an upstream module. Default: false — for compile_module")
                        humanReadable("Also recompile modules that depend on the target module.")
                        whenPresent("Compile scope includes the target plus its dependents.")
                        whenAbsent("Defaults to false — only the target module is compiled.")
                        example("true")
                    }
                    optional("refresh_maven_first", "boolean") {
                        llmSeesIt("When true, trigger a Maven reimport (MavenProjectsManager.forceUpdateAllProjects or addManagedFilesOrUnignore for unimported pom.xml) and wait up to 30s for it to finish before compiling — picks up classpath changes from a prior `run_command mvn install`. Default: false. Set true after any `mvn install`/`mvn deploy` that wrote new SNAPSHOT versions to ~/.m2 — for compile_module")
                        humanReadable("Reimport Maven before compiling so the build picks up newly-installed JARs from ~/.m2.")
                        whenPresent("MavenProjectsManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles fires (or addManagedFilesOrUnignore for fresh-clone projects); compile waits up to 30s for the reimport to finish.")
                        whenAbsent("Defaults to false — compiles against IntelliJ's current in-memory model, which can be stale after a CLI Maven build.")
                        example("true")
                    }
                    optional("description", "string") {
                        llmSeesIt("Brief description of what this action does and why (shown to user in approval dialog) — for run_tests, compile_module, rerun_failed_tests")
                        humanReadable("Short reason shown in the user's approval dialog.")
                        whenPresent("Surfaced in the approval gate so the user sees why the agent wants to compile.")
                        whenAbsent("Approval dialog shows a generic label.")
                        example("Compile core module after API change")
                    }
                }
                onSuccess("Returns 'Compilation of <target> successful' (with warning count when non-zero), or per-file compile errors via formatCompileErrors when errors > 0.")
                onFailure("module name does not match any module", "Returns 'Module \\'<name>\\' not found.' with the available module names.")
                onFailure("compilation aborted", "Returns 'Compilation of <target> was aborted.' with isError=true.")
                onFailure("compile takes longer than 120s", "Returns 'Compilation timed out after 120 seconds. The build may be stuck.' with isError=true.")
                example("compile a single module") {
                    param("action", "compile_module")
                    param("module", "myapp.core")
                    outcome("Returns 'Compilation of myapp.core successful: 0 errors.'")
                }
                example("compile module and its dependents") {
                    param("action", "compile_module")
                    param("module", "myapp.api")
                    param("check_dependents", "true")
                    outcome("Compiles myapp.api plus every module that depends on it — surfaces any downstream ABI breakage.")
                }
                verdict {
                    keep(
                        "Pre-run compile verification is critical when the LLM edits source and immediately wants to " +
                            "run tests. CompilerManager.make respects IntelliJ's module graph and facet settings; " +
                            "`run_command ./gradlew compileJava` doesn't see Kotlin sources and misses annotation processors. " +
                            "The check_dependents flag is uniquely valuable after upstream API changes.",
                        VerdictSeverity.NORMAL
                    )
                }
            }
            action("rerun_failed_tests") {
                description {
                    technical("Re-runs only the failed/errored tests from a prior test session. Resolves the most-recent test descriptor via RunContentManager.allDescriptors (or one matching session_id), extracts FAILED/ERROR tests via collectTestResults, then builds a filtered JUnit/TestNG run configuration via reflection (pattern mode for multi-class, class mode for single-class) and launches it through the same RunInvocation path as run_tests.")
                    plain("The 'Rerun Failed Tests' button — re-runs just the tests that just failed instead of the whole class. Returns a friendly informational message when there's nothing to rerun.")
                }
                whenLLMUses("After run_tests reports failures, to iterate quickly on just the failing subset without re-running the whole class.")
                params {
                    optional("session_id", "string") {
                        llmSeesIt("Name or partial name of the prior test session to rerun failures from — for rerun_failed_tests. Defaults to most-recent test session when omitted.")
                        humanReadable("Which prior test session to rerun. Omit to use the most recent one.")
                        whenPresent("Selects the test descriptor whose displayName contains this string (case-insensitive).")
                        whenAbsent("The most-recent test descriptor is used.")
                        example("MyServiceTest")
                    }
                    optional("description", "string") {
                        llmSeesIt("Brief description of what this action does and why (shown to user in approval dialog) — for run_tests, compile_module, rerun_failed_tests")
                        humanReadable("Short reason shown in the user's approval dialog.")
                        whenPresent("Surfaced in the approval gate so the user sees why the agent wants to rerun.")
                        whenAbsent("Approval dialog shows a generic label.")
                        example("Rerun failures after fix")
                    }
                }
                onSuccess("Returns aggregated rerun output for the filtered configuration covering only the FAILED/ERROR tests; returns an informational (non-error) result when the prior session had 0 failures.")
                onFailure("no prior test session", "Returns NO_PRIOR_TEST_SESSION — run run_tests first.")
                onFailure("session has no test data", "Returns NO_PRIOR_TEST_SESSION with a hint that the session may not be a test run.")
                onFailure("original run configuration cannot be resolved", "Returns CONFIGURATION_NOT_FOUND listing available configs.")
                onFailure("indexing didn't finish in 60s", "Returns DUMB_MODE — wait and retry shortly.")
                example("rerun the most recent failed tests") {
                    param("action", "rerun_failed_tests")
                    outcome("Reruns only the FAILED/ERROR tests from the last test session via a filtered [Rerun] configuration.")
                }
                example("rerun a specific named session") {
                    param("action", "rerun_failed_tests")
                    param("session_id", "MyServiceTest")
                    outcome("Reruns failures from the descriptor whose name contains 'MyServiceTest'.")
                }
                verdict {
                    keep(
                        "Saves significant token cost and wall-clock time on large test classes — reruns only " +
                            "the FAILED/ERROR subset rather than the full class. The alternative is manually listing " +
                            "failed methods and passing them as a comma-separated 'method' string to run_tests, " +
                            "which the LLM often gets wrong under long-context pressure.",
                        VerdictSeverity.NORMAL
                    )
                }
            }
        }
    }

    /** Resolve stream callback for live output. */
    private fun resolveStreamCallback(@Suppress("UNUSED_PARAMETER") project: Project): ((String, String) -> Unit)? {
        return RunCommandTool.streamCallback
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return when (action) {
            "run_tests" -> executeRunTests(params, project)
            "compile_module" -> executeCompileModule(params, project)
            "rerun_failed_tests" -> executeRerunFailedTests(params, project)
            else -> ToolResult(
                content = "Unknown action '$action' in java_runtime_exec. Valid actions: run_tests, compile_module, rerun_failed_tests",
                summary = "Unknown action '$action' in java_runtime_exec",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: run_tests
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeRunTests(params: JsonObject, project: Project): ToolResult {
        // Bug 4 — Layer C: a recent run_command may have triggered a wide VFS refresh that
        // fanned out into reindexing. Wait for smart mode before launching tests so JPS
        // sees a coherent VFS instead of mid-index garbage.
        if (!com.workflow.orchestrator.core.vfs.waitForSmartModeOrTimeout(project)) {
            return ToolResult(
                content = "DUMB_MODE: indexing did not complete within 60s. " +
                    "A recent file mutation triggered reindexing. Retry shortly.",
                summary = "DUMB_MODE: timeout waiting for indexing",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )
        }
        val className = params["class_name"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'class_name' is required — specify a fully qualified test class to run (e.g. com.example.MyServiceTest). " +
                    "Running all tests is not supported as it can take 30+ minutes. " +
                    "Use the 'test_finder' tool to discover test classes if you don't know the class name.",
                "Error: missing class_name",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val methodRaw = params["method"]?.jsonPrimitive?.content
        // Comma-separated list support: 'testFoo' or 'testFoo,testBar,testBaz'.
        // Whitespace around commas is trimmed; duplicates collapsed; empty segments dropped.
        val methods: List<String> = methodRaw
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?: emptyList()

        // Reject "contains only separators" (e.g. ","  "  ,  "  " ") distinctly from
        // the invalid-identifier path below — this particular confusion is the most
        // likely human-facing failure when debugging an LLM call.
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
                    "Split into multiple run_tests calls, or omit 'method' to run the whole class.",
                summary = "Too many methods (${methods.size})",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        methods.firstOrNull { !it.matches(METHOD_NAME_REGEX) }?.let { bad ->
            return ToolResult(
                content = "Error: invalid method name '$bad'. Expected a Java identifier " +
                    "(letters/digits/underscore/\$, starting with a non-digit) — no spaces, no '#', " +
                    "no '.', no ';'. To target a nested class method, set class_name to the " +
                    "fully-qualified nested class (e.g. com.example.Outer\$Inner) and pass the bare method.",
                summary = "Invalid method name '$bad'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val timeoutSeconds = (params["timeout"]?.jsonPrimitive?.intOrNull?.toLong() ?: RUN_TESTS_DEFAULT_TIMEOUT)
            .coerceIn(1, RUN_TESTS_MAX_TIMEOUT)
        val useNativeRunner = params["use_native_runner"]?.jsonPrimitive?.booleanOrNull ?: true

        val testTarget = when (methods.size) {
            0 -> className
            1 -> "$className#${methods.first()}"
            else -> "$className#${methods.joinToString(",")}"
        }

        // Resolve the module once and share it with both the native runner (for config
        // wiring) and the shell fallback (for multi-module Gradle/Maven subproject path).
        val module = findModuleForClass(project, className)

        // Pre-flight validation (Phase 2 / Tasks 2.7–2.9): when we have a resolved module,
        // run BuildSystemValidator to catch the "module exists in IntelliJ but unrunnable
        // in the build tool" failure family BEFORE dispatching. If module is null, skip the
        // validator and let the native/shell paths surface their own "module could not be
        // resolved" error — the validator requires a module to check against.
        var authoritativeBuildPath: String? = null
        var detectedTestCount: Int = 0
        var validatorWarning: String? = null
        if (module != null) {
            when (val validation = BuildSystemValidator(project).validateForTestRun(className, module)) {
                is BuildSystemValidator.ValidationResult.Blocked -> return ToolResult(
                    content = "${validation.reason}\n\n${validation.suggestion}",
                    summary = validation.reason.substringBefore('\n'),
                    tokenEstimate = 50,
                    isError = true
                )
                is BuildSystemValidator.ValidationResult.Ok -> {
                    authoritativeBuildPath = validation.authoritativeBuildPath
                    detectedTestCount = validation.detectedTestCount
                    // Populated by Task 2.5 filesystem fallback — surface it to the LLM so
                    // non-standard-layout dispatch failures aren't silently blamed on the
                    // build tool. Prepended to the breadcrumb below with the same
                    // `[WARNING]` prefix we use for the native-runner-fell-back-to-shell case.
                    validatorWarning = validation.warning
                }
            }
        }

        // Build the success breadcrumb (Task 2.9). Prepended to any happy-path result
        // returned by the native runner or shell fallback. Compose the parenthesized parts
        // as a list so the "Build path" segment is dropped entirely when no authoritative
        // path is available — otherwise we'd emit a dangling "(Build path, …)" label that
        // reads broken to humans and is a confusing LLM anchor.
        val breadcrumb = if (module != null) {
            val parts = buildList {
                authoritativeBuildPath?.let { path ->
                    val label = if (path.startsWith(":")) "Gradle path" else "Maven dir"
                    add("$label: $path")
                }
                val simpleClass = className.substringAfterLast('.')
                add("$detectedTestCount test methods detected in $simpleClass")
                if (methods.size >= 2) {
                    add("requested: ${methods.size} methods (${methods.joinToString(",")})")
                }
            }
            val line = "Running tests in module: ${module.name} (${parts.joinToString(", ")})"
            if (validatorWarning != null) "[WARNING] $validatorWarning\n$line" else line
        } else {
            null
        }

        fun prependBreadcrumb(result: ToolResult): ToolResult {
            if (breadcrumb == null || result.isError) return result
            val newContent = breadcrumb + "\n\n" + result.content
            return result.copy(
                content = newContent,
                tokenEstimate = result.tokenEstimate + TokenEstimator.estimate(breadcrumb)
            )
        }

        // Multi-method on TestNG or an unknown framework: the native JUnit 5 PATTERNS
        // trick (used below) is unavailable, and TestNG's METHOD_NAME is single-valued.
        // Route through the shell fallback, which does support comma/`+`-joined methods
        // on every build tool. We prepend a breadcrumb so the LLM sees why it happened.
        val framework = if (methods.size >= 2 && useNativeRunner) detectTestFramework(project, className) else null
        val forceShellForMultiMethod = framework != null && framework != "JUnit"
        val multiMethodShellWarning = if (forceShellForMultiMethod) {
            "[INFO] Multi-method native run is JUnit-5-only; '$framework' detected → routing through Maven/Gradle shell fallback, which supports multi-method natively."
        } else null

        if (useNativeRunner && !forceShellForMultiMethod) {
            val reasonOut = StringBuilder()
            try {
                val result = executeWithNativeRunner(project, className, methods, testTarget, timeoutSeconds, reasonOut)
                if (result != null) return prependBreadcrumb(result)
                // Explicit native opt-in but setup failed — do NOT silently use `mvn test`.
                // Previously this path silently fell through to executeWithShell, which is
                // why a multi-module project could land on Maven even with use_native_runner=true
                // (e.g. findModuleForClass returned null for a sibling module's class,
                // createJUnitRunSettings bailed, the dispatcher fell back without telling anyone).
                val reason = reasonOut.toString().ifBlank { "setup returned null without a specific reason" }

                // Exception: MULTI_METHOD_PATTERNS_UNAVAILABLE is a known capability
                // gap, not a real configuration error. The shell fallback supports
                // multi-method on every build tool, so auto-route there with a
                // breadcrumb (mirrors the TestNG handling at line 332-335).
                if (reason.startsWith("MULTI_METHOD_PATTERNS_UNAVAILABLE")) {
                    val shellResult = executeWithShell(project, className, methods, timeoutSeconds, module, authoritativeBuildPath)
                    val info = "[INFO] Multi-method native run requires the JUnit PATTERNS reflection trick, " +
                        "which is unavailable on this platform ($reason). Routed through Maven/Gradle shell, " +
                        "which supports multi-method on every JUnit version.\n\n"
                    return prependBreadcrumb(shellResult.copy(content = info + shellResult.content))
                }

                return ToolResult(
                    content = "Native IntelliJ test runner could not be set up for '$className': $reason.\n\n" +
                        "Not falling back to Maven/Gradle shell because use_native_runner=true.\n" +
                        "Options:\n" +
                        "- Fix the underlying cause (most common: class not in the project source roots, " +
                        "module not resolvable, or the JUnit/TestNG plugin is disabled).\n" +
                        "- Pass use_native_runner=false to run via `mvn test` / `./gradlew test`.",
                    summary = "Native runner unavailable: $reason",
                    tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            } catch (e: Exception) {
                // Catch branch: the validator may have produced an authoritative path we
                // should still prefer, but if anything about the native launch exploded we
                // pass `null` for the authoritative path so executeWithShell falls back to
                // filesystem derivation — the validator's Ok was only authoritative for a
                // happy-path launch. Defensive choice; can be relaxed later.
                val shellResult = executeWithShell(project, className, methods, timeoutSeconds, module, null)
                val warning = "[WARNING] Native test runner threw ${e.javaClass.simpleName}: ${e.message}, used shell fallback.\n\n"
                return prependBreadcrumb(shellResult.copy(content = warning + shellResult.content))
            }
        }

        val shellResult = executeWithShell(project, className, methods, timeoutSeconds, module, authoritativeBuildPath)
        val finalResult = if (multiMethodShellWarning != null && !shellResult.isError) {
            val newContent = multiMethodShellWarning + "\n\n" + shellResult.content
            shellResult.copy(content = newContent, tokenEstimate = shellResult.tokenEstimate + TokenEstimator.estimate(multiMethodShellWarning))
        } else shellResult
        return prependBreadcrumb(finalResult)
    }

    private suspend fun executeWithNativeRunner(
        project: Project, className: String, methods: List<String>,
        testTarget: String, timeoutSeconds: Long,
        reasonOut: StringBuilder
    ): ToolResult? {
        val launchSpec = createJUnitRunSettings(project, className, methods, reasonOut) ?: return null
        val settings = launchSpec.settings
        val testModule = launchSpec.module

        // Phase 3 / Task 2.3: route all listener/connection/descriptor tracking through
        // a single RunInvocation. The try/finally block below disposes the invocation on
        // every exit path (success / processNotStarted / timeout / exception / coroutine
        // cancel), which in turn:
        //   - destroys the captured ProcessHandler if it hasn't terminated,
        //   - disconnects both the build-phase and run-phase MessageBusConnection
        //     (registered via invocation.subscribeTopic below),
        //   - runs the `removeRunContent` onDispose callback installed after the
        //     descriptor is captured (removes the descriptor from RunContentManager,
        //     which in turn disposes the TestResultsViewer and its EventsListener),
        //   - auto-cleans any 2-arg process listeners attached inside handleDescriptorReady.
        //
        // The old raw `build-watchdog-timeout` Thread (5 min sleep that manually
        // disconnected the run connection) is gone — the outer `withTimeoutOrNull`
        // fires → this finally runs → everything is released.
        val invocation = project.service<AgentService>().newRunInvocation("run-tests-${System.currentTimeMillis()}")
        // Task 2.4 follow-up: handleDescriptorReady now consumes `invocation` directly
        // (its descriptorRef / processHandlerRef / attachListener / attachProcessListener),
        // so the local-var aliases that bridged Task 2.3 are gone.

        try {
            val result = withTimeoutOrNull(timeoutSeconds * 1000) {
                suspendCancellableCoroutine { continuation ->
                    // Single cleanup path: dispose the invocation. This destroys the
                    // process handler, disconnects both MessageBusConnections, removes
                    // the run content descriptor, and runs all auto-cleaning 2-arg
                    // process listeners — replacing the old manual disconnect/destroy
                    // dance.
                    continuation.invokeOnCancellation {
                        Disposer.dispose(invocation)
                    }
                    com.intellij.openapi.application.invokeLater {
                        try {
                            // Capture the full CompileContext so the build-failure branch can
                            // walk per-file error messages via formatCompileErrors(). Prior
                            // implementation stored only "N errors, M warnings" in a String,
                            // which the LLM misread as "TDD red phase" — missing that the
                            // real failure was a typo the user could see at file:line:col.
                            //
                            // AtomicReference for thread safety: compilationFinished() fires
                            // on a background thread (CompilerManager's build thread) while
                            // the outer scope may touch the ref from EDT.
                            val compileContextRef = AtomicReference<CompileContext?>(null)
                            val buildConnection = project.messageBus.connect()
                            invocation.subscribeTopic(buildConnection)

                            // Subscribe to CompilationStatusListener BEFORE starting the build so
                            // we capture the CompileContext from the build phase. Direct typed
                            // subscription — no reflection needed; the class and topic are
                            // part of the public intellij.java.compiler module.
                            buildConnection.subscribe(
                                CompilerTopics.COMPILATION_STATUS,
                                object : CompilationStatusListener {
                                    override fun compilationFinished(
                                        aborted: Boolean,
                                        errors: Int,
                                        warnings: Int,
                                        compileContext: CompileContext
                                    ) {
                                        if (aborted || errors > 0) {
                                            compileContextRef.set(compileContext)
                                        }
                                    }
                                }
                            )

                            // Explicit build phase: the transient RunnerAndConfigurationSettings is
                            // intentionally never registered in RunManager (see commit 9b164bf3), so
                            // IntelliJ's factory-default "Build" before-run task is NOT wired to it.
                            // We invoke ProjectTaskManager.build(module) ourselves to guarantee the
                            // test class is compiled before JUnit starts — preventing initializationError.
                            //
                            // Do NOT "fix" this by calling RunManager.setTemporaryConfiguration(settings):
                            // that API sets selectedConfiguration as a side-effect and re-triggers the
                            // "initialization error on next manual run" regression from commit 9b164bf3.
                            try {
                                ProjectTaskManager.getInstance(project)
                                    .build(testModule)
                                    .onSuccess { buildResult ->
                                        if (buildResult.hasErrors() || buildResult.isAborted) {
                                            if (continuation.isActive) {
                                                continuation.resume(
                                                    buildCompileFailureResult(
                                                        compileContextRef.get(),
                                                        testTarget,
                                                        buildResult.isAborted
                                                    )
                                                )
                                            }
                                            return@onSuccess
                                        }

                                        // Build succeeded — launch JUnit on EDT.
                                        com.intellij.openapi.application.invokeLater {
                                            try {
                                                val executor = DefaultRunExecutor.getRunExecutorInstance()
                                                val env = ExecutionEnvironmentBuilder
                                                    .createOrNull(executor, settings)
                                                    ?.build()

                                                if (env == null) {
                                                    reasonOut.append("ExecutionEnvironmentBuilder.createOrNull returned null (no runner registered for this configuration)")
                                                    if (continuation.isActive) continuation.resume(null)
                                                    return@invokeLater
                                                }

                                                val callback = object : ProgramRunner.Callback {
                                                    override fun processStarted(descriptor: RunContentDescriptor?) {
                                                        if (descriptor == null) {
                                                            reasonOut.append("ProgramRunner.Callback produced no RunContentDescriptor (the runner refused to start the process)")
                                                            if (continuation.isActive) continuation.resume(null)
                                                            return
                                                        }
                                                        handleDescriptorReady(descriptor, continuation, testTarget, invocation, project)
                                                        // Descriptor is populated into invocation.descriptorRef by
                                                        // handleDescriptorReady directly.
                                                        // Register an onDispose callback that removes it from
                                                        // RunContentManager — this is the release mechanism for the
                                                        // TestResultsViewer (and its EventsListener) because
                                                        // TestResultsViewer is Disposable with NO removeEventsListener
                                                        // API. Per design, the literal `removeRunContent` call
                                                        // lives here in the tool file (source-text test anchor).
                                                        invocation.onDispose {
                                                            val currentDesc = invocation.descriptorRef.get() ?: return@onDispose
                                                            val runExecutor = DefaultRunExecutor.getRunExecutorInstance()
                                                            ApplicationManager.getApplication().invokeLater {
                                                                com.intellij.execution.ui.RunContentManager.getInstance(project)
                                                                    .removeRunContent(runExecutor, currentDesc)
                                                            }
                                                        }
                                                    }
                                                }

                                                try {
                                                    ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)
                                                } catch (_: NoSuchMethodError) {
                                                    env.callback = callback
                                                    ProgramRunnerUtil.executeConfiguration(env, false, true)
                                                }

                                                // Defence-in-depth: ExecutionListener.processNotStarted()
                                                // fires when the execution framework aborts the run for non-build
                                                // reasons (no ProgramRunner registered, executor disabled, JDK
                                                // resolution failure, etc.). Build failures are caught above via
                                                // ProjectTaskManager.build — this handles everything else.
                                                //
                                                // Registered through invocation.subscribeTopic so disposal of the
                                                // RunInvocation (on timeout / cancel / success) disconnects it
                                                // automatically. No raw watchdog Thread needed.
                                                val runConnection = project.messageBus.connect()
                                                invocation.subscribeTopic(runConnection)

                                                runConnection.subscribe(
                                                    com.intellij.execution.ExecutionManager.EXECUTION_TOPIC,
                                                    object : com.intellij.execution.ExecutionListener {
                                                        override fun processNotStarted(
                                                            executorId: String,
                                                            e: com.intellij.execution.runners.ExecutionEnvironment
                                                        ) {
                                                            if (e == env) {
                                                                if (continuation.isActive) {
                                                                    continuation.resume(ToolResult(
                                                                        content = "Test execution did not start after a successful build.\n\n" +
                                                                            "Possible causes: no ProgramRunner registered for this configuration, " +
                                                                            "executor is disabled, or JDK resolution failed.",
                                                                        summary = "Run aborted after successful build",
                                                                        tokenEstimate = 30,
                                                                        isError = true
                                                                    ))
                                                                }
                                                            }
                                                        }

                                                        override fun processStarted(
                                                            executorId: String,
                                                            e: com.intellij.execution.runners.ExecutionEnvironment,
                                                            handler: com.intellij.execution.process.ProcessHandler
                                                        ) {
                                                            // Observation-only; teardown happens through invocation.dispose().
                                                        }
                                                    }
                                                )
                                            } catch (e: Exception) {
                                                reasonOut.append("run launch threw ${e.javaClass.simpleName}: ${e.message}")
                                                if (continuation.isActive) continuation.resume(null)
                                            }
                                        }
                                    }
                                    .onError { _ ->
                                        if (continuation.isActive) {
                                            continuation.resume(
                                                buildCompileFailureResult(
                                                    compileContextRef.get(),
                                                    testTarget,
                                                    aborted = false
                                                )
                                            )
                                        }
                                    }
                            } catch (e: Exception) {
                                reasonOut.append("ProjectTaskManager.build threw ${e.javaClass.simpleName}: ${e.message}")
                                if (continuation.isActive) continuation.resume(null)
                            }
                        } catch (e: Exception) {
                            reasonOut.append("setup threw ${e.javaClass.simpleName}: ${e.message}")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                }
            }

            if (result == null && invocation.processHandlerRef.get() != null) {
                invocation.processHandlerRef.get()?.destroyProcess()
                val descriptor = invocation.descriptorRef.get()
                val partialResult = descriptor?.let { extractNativeResults(it, testTarget, project) }
                return if (partialResult != null) {
                    partialResult.copy(
                        content = "[TIMEOUT] Test execution timed out after ${timeoutSeconds}s. Partial results:\n\n${partialResult.content}",
                        isError = true
                    )
                } else {
                    ToolResult(
                        "[TIMEOUT] Test execution timed out after ${timeoutSeconds}s for $testTarget. No results captured.",
                        "Test timeout", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                    )
                }
            }

            return result
        } finally {
            Disposer.dispose(invocation)
        }
    }

    /**
     * Build the [ToolResult] returned when `ProjectTaskManager.build(testModule)` fails
     * before tests can launch. Routes per-file error messages through the shared
     * [formatCompileErrors] helper when a [CompileContext] was captured by the
     * [CompilationStatusListener] — otherwise falls back to a generic message that
     * still tells the LLM "BUILD FAILED" rather than "Compilation result: N errors".
     *
     * Leading-line format: `BUILD FAILED — N compile error(s) prevented tests from starting:`
     * — matches the plan document so the LLM cannot skim-read this as a red test.
     */
    internal fun buildCompileFailureResult(
        context: CompileContext?,
        testTarget: String,
        aborted: Boolean
    ): ToolResult {
        if (context != null) {
            val errorCount = context.getMessages(CompilerMessageCategory.ERROR).size
            val leading = if (errorCount > 0) {
                "BUILD FAILED — $errorCount compile error(s) prevented tests from starting:"
            } else {
                // Build reported failure but no error messages were captured. Rare, but
                // possible when the build aborts for a non-compile reason (e.g. Gradle
                // before-task). Give a generic leading line.
                "BUILD FAILED — test execution did not start."
            }
            return formatCompileErrors(
                context = context,
                target = testTarget,
                leadingLine = leading
            )
        }
        // Fall back to a generic failure message when no CompileContext was captured —
        // typically means the listener callback didn't fire (reflection/early-abort path).
        val reason = if (aborted) "build was aborted" else "build failed with no compile context captured"
        return ToolResult(
            content = "BUILD FAILED — test execution did not start.\n\n" +
                "Reason: $reason.\n\n" +
                "Fix the compilation errors and try again. " +
                "Use diagnostics tool to check for errors in the test class.",
            summary = "Build failed before tests ($reason)",
            tokenEstimate = 30,
            isError = true
        )
    }

    internal fun handleDescriptorReady(
        descriptor: RunContentDescriptor,
        continuation: CancellableContinuation<ToolResult?>,
        testTarget: String,
        invocation: RunInvocation,
        project: Project? = null,
    ) {
        invocation.descriptorRef.set(descriptor)
        val handler = descriptor.processHandler
        invocation.processHandlerRef.set(handler)

        val toolCallId = RunCommandTool.currentToolCallId.get()
        val activeStreamCallback = if (project != null) resolveStreamCallback(project) else RunCommandTool.streamCallback

        if (handler != null && toolCallId != null) {
            // Phase 3 / Task 2.4: route through invocation.attachProcessListener so the
            // listener is auto-removed when invocation disposes (uses 2-arg
            // addProcessListener(listener, disposable) form internally — no manual
            // removeProcessListener needed on terminal notification).
            val streamingListener = object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                    val text = event.text ?: return
                    if (text.isNotBlank()) {
                        activeStreamCallback?.invoke(toolCallId, text)
                    }
                }
            }
            invocation.attachProcessListener(handler, streamingListener)
        }

        val testConsole = TestConsoleUtils.unwrapToTestConsole(descriptor.executionConsole)
        if (testConsole != null) {
            val resultsViewer = testConsole.resultsViewer
            // Phase 3 / Task 2.4: route through invocation.attachListener — wraps the
            // listener in a defense-in-depth proxy that gates on the invocation's
            // disposed flag. If the framework re-fires onTestingFinished after we've
            // already disposed (e.g. timeout fired and continuation was cancelled),
            // the proxy silently drops the call instead of resuming an already-consumed
            // continuation.
            val eventsListener = object : TestResultsViewer.EventsListener {
                override fun onTestingFinished(sender: TestResultsViewer) {
                    val root = sender.testsRootNode as? SMTestProxy.SMRootTestProxy
                    if (root != null && continuation.isActive) {
                        val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                        invocation.onDispose { pollScope.cancel() }
                        pollScope.launch {
                            if (continuation.isActive) {
                                continuation.resume(extractNativeResults(descriptor, testTarget, project))
                            }
                        }
                    }
                }
            }
            invocation.attachListener(eventsListener, resultsViewer)
        } else {
            // Fallback: no test console available — wait for process exit, then retry
            // until the test tree is populated. No TestResultsViewer.EventsListener is
            // available here, so we poll with short intervals instead of a blind 2s Timer.
            //
            // Phase 3 / Task 2.4: replaces the prior raw `test-tree-finalize` Thread.
            // We launch a coroutine on a per-invocation scope (Dispatchers.IO) and
            // tie its cancellation to invocation.onDispose so:
            //   - timeout/cancel disposes the invocation → scope.cancel() → poll loop
            //     stops promptly without leaking a daemon thread,
            //   - delay() is interruptible (unlike Thread.sleep) so cancellation is
            //     immediate rather than waiting up to TEST_TREE_RETRY_INTERVAL_MS.
            if (handler != null) {
                val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                invocation.onDispose { pollScope.cancel() }

                val doExtract = {
                    pollScope.launch {
                        for (attempt in 1..TEST_TREE_RETRY_ATTEMPTS) {
                            if (!continuation.isActive) return@launch
                            if (TestConsoleUtils.findTestRoot(descriptor)?.children?.isNotEmpty() == true) break
                            delay(TEST_TREE_RETRY_INTERVAL_MS)
                        }
                        if (continuation.isActive) {
                            continuation.resume(extractNativeResults(descriptor, testTarget, project))
                        }
                    }
                }

                if (handler.isProcessTerminated) {
                    doExtract()
                } else {
                    val terminationListener = object : ProcessAdapter() {
                        override fun processTerminated(event: ProcessEvent) {
                            doExtract()
                        }
                    }
                    invocation.attachProcessListener(handler, terminationListener)
                }
            } else {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private suspend fun createJUnitRunSettings(
        project: Project, className: String, methods: List<String>,
        reasonOut: StringBuilder
    ): JUnitLaunchSpec? {
        // reasonOut is appended (single reason) on every return-null branch so the
        // dispatcher can surface WHY the native runner could not be set up instead
        // of silently falling back to `mvn test`. Each branch writes its own reason
        // only if the builder is still empty, so the earliest failure wins.
        fun fail(why: String): JUnitLaunchSpec? {
            if (reasonOut.isEmpty()) reasonOut.append(why)
            return null
        }
        return try {
            val runManager = RunManager.getInstance(project)

            val testFramework = detectTestFramework(project, className)
            val configTypeId = when (testFramework) {
                "TestNG" -> "TestNG"
                else -> "JUnit"
            }
            val testConfigType = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.find { type ->
                type.id == configTypeId || type.displayName == configTypeId
            } ?: return fail("no '$configTypeId' ConfigurationType registered (is the JUnit/TestNG plugin enabled?)")

            val factory = testConfigType.configurationFactories.firstOrNull()
                ?: return fail("$configTypeId ConfigurationType has no configuration factories")
            val simpleClass = className.substringAfterLast('.')
            val configName = when (methods.size) {
                0 -> simpleClass
                1 -> "$simpleClass.${methods.first()}"
                else -> "$simpleClass.${methods.first()}+${methods.size - 1}more"
            }
            val settings = runManager.createConfiguration(configName, factory)

            val config = settings.configuration
            val isTestNG = testFramework == "TestNG"

            // TestNG has no PATTERNS field equivalent and METHOD_NAME is single-valued,
            // so multi-method on TestNG must go through the shell path. The dispatcher in
            // executeRunTests already routes this case around the native runner entirely;
            // this guard is defense-in-depth in case a caller bypasses that check.
            if (isTestNG && methods.size >= 2) {
                return fail("multi-method native run is not supported for TestNG; pass use_native_runner=false")
            }

            try {
                val dataMethodName = if (isTestNG) "getPersistantData" else "getPersistentData"
                val getDataMethod = config.javaClass.methods.find { it.name == dataMethodName }
                val data = getDataMethod?.invoke(config)
                if (data != null) {
                    val testObjectField = data.javaClass.getField("TEST_OBJECT")
                    val mainClassField = data.javaClass.getField("MAIN_CLASS_NAME")

                    // Three cases, mirroring rerun_failed_tests's multi-class PATTERNS trick
                    // (line ~1330) but applied to methods within a single class:
                    //   0 methods → class mode
                    //   1 method  → method mode (today's METHOD_NAME path)
                    //   2+ methods → JUnit 5 pattern mode with PATTERNS entry "Class,m1|m2|m3"
                    val testType = when {
                        methods.isEmpty() -> if (isTestNG) "CLASS" else "class"
                        methods.size == 1 -> if (isTestNG) "METHOD" else "method"
                        else -> "pattern"  // JUnit 5 only — TestNG guarded above
                    }
                    testObjectField.set(data, testType)
                    mainClassField.set(data, className)

                    try {
                        val packageField = data.javaClass.getField("PACKAGE_NAME")
                        val packageName = className.substringBeforeLast('.', "")
                        packageField.set(data, packageName)
                    } catch (_: Exception) { }

                    when {
                        methods.size == 1 -> {
                            val methodField = data.javaClass.getField("METHOD_NAME")
                            methodField.set(data, methods.first())
                        }
                        methods.size >= 2 -> {
                            // Storage format per JUnitConfiguration.bePatternConfiguration:
                            // one LinkedHashSet entry per "fully.qualified.Class,methodName"
                            // pair. The `||` separator from getPatternPresentation() is for
                            // display only — NOT the persistence format. The backing field
                            // `myPattern` is private; the public setter `setPatterns` is the
                            // stable API across platform versions.
                            try {
                                val patterns = java.util.LinkedHashSet<String>(methods.size).apply {
                                    methods.forEach { add("$className,$it") }
                                }
                                val setPatterns = data.javaClass.getMethod(
                                    "setPatterns", java.util.LinkedHashSet::class.java
                                )
                                setPatterns.invoke(data, patterns)
                            } catch (e: Exception) {
                                // Sentinel prefix MULTI_METHOD_PATTERNS_UNAVAILABLE lets the
                                // dispatcher (executeRunTests) recognize this as a known
                                // capability gap and auto-route to the shell fallback. Kept
                                // as defense-in-depth for forked or future plugin versions
                                // where the setter signature shifts. Class name is reported
                                // verbatim for debuggability.
                                return fail(
                                    "MULTI_METHOD_PATTERNS_UNAVAILABLE: setPatterns(LinkedHashSet) not callable on " +
                                        "${data.javaClass.name} (${e.javaClass.simpleName}: ${e.message})"
                                )
                            }
                        }
                    }
                } else {
                    return fail("$configTypeId config exposes neither getPersistentData nor getPersistantData (unexpected plugin version)")
                }
            } catch (e: Exception) {
                return fail("failed to populate $configTypeId persistent data via reflection: ${e.javaClass.simpleName}: ${e.message}")
            }

            val testModule = findModuleForClass(project, className)
                ?: return fail("could not resolve an IntelliJ module for '$className'. " +
                    "Most common cause in a multi-module project: the class is not under any module's source roots, " +
                    "or the module containing it hasn't been re-imported since it was added. " +
                    "Open the test class in the editor and verify it has a module badge on the file tab")
            run {
                try {
                    val setModuleMethod = config.javaClass.getMethod("setModule", com.intellij.openapi.module.Module::class.java)
                    setModuleMethod.invoke(config, testModule)
                } catch (_: Exception) {
                    try {
                        val getConfigModule = config.javaClass.getMethod("getConfigurationModule")
                        val configModule = getConfigModule.invoke(config)
                        val setModule = configModule.javaClass.getMethod("setModule", com.intellij.openapi.module.Module::class.java)
                        setModule.invoke(configModule, testModule)
                    } catch (_: Exception) { }
                }
            }

            settings.isTemporary = true
            // Do NOT add to RunManager and do NOT overwrite selectedConfiguration.
            // ExecutionEnvironmentBuilder doesn't require the config to be registered,
            // and stealing the user's selected config causes "initialization error" on
            // the next manual run after the agent is stopped.
            // See commit 9b164bf3 — do NOT "fix" this by calling
            // RunManager.setTemporaryConfiguration(settings): that sets selectedConfiguration
            // as a side-effect and re-triggers the original bug.
            JUnitLaunchSpec(settings, testModule)
        } catch (e: Exception) {
            fail("unexpected ${e.javaClass.simpleName} during native run config setup: ${e.message}")
        }
    }

    private suspend fun detectTestFramework(project: Project, className: String): String {
        return try {
            smartReadAction(project) {
                val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
                    .findClass(className, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
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
                val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
                    .findClass(className, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
                    ?: return@smartReadAction null
                com.intellij.openapi.module.ModuleUtilCore.findModuleForPsiElement(psiClass)
            }
        } catch (_: Exception) { null }
    }

    private suspend fun extractNativeResults(
        descriptor: RunContentDescriptor,
        testTarget: String,
        project: Project?,
    ): ToolResult? {
        val testRoot = TestConsoleUtils.findTestRoot(descriptor)
        if (testRoot == null) {
            return ToolResult(
                "Test run completed for $testTarget but no structured results available.\nRun session: ${descriptor.displayName}",
                "Tests completed, no structured data", 20
            )
        }

        // project is non-null in all production paths (executeWithNativeRunner always
        // provides it); null only in the legacy headless path via handleDescriptorReady(project=null).
        // interpretTestRoot accepts Project? and degrades gracefully when null.
        return interpretTestRoot(testRoot, descriptor.displayName ?: testTarget, this, project)
    }

    private suspend fun executeWithShell(
        project: Project,
        className: String,
        methods: List<String>,
        timeoutSeconds: Long,
        module: com.intellij.openapi.module.Module? = null,
        authoritativeBuildPath: String? = null
    ): ToolResult {
        // Display-only target string used in error messages and breadcrumbs.
        val testTarget = when (methods.size) {
            0 -> className
            1 -> "$className#${methods.first()}"
            else -> "$className#${methods.joinToString(",")}"
        }
        val basePath = project.basePath
            ?: return ToolResult("Error: no project base path available", "Error: no project", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val baseDir = File(basePath)
        val hasMaven = File(baseDir, "pom.xml").exists()
        val hasGradle = File(baseDir, "build.gradle").exists() || File(baseDir, "build.gradle.kts").exists()

        // Derive the Gradle subproject path from the module's content root directory.
        // For a module rooted at <projectRoot>/core/, the Gradle task is :core:test.
        // For nested modules (<projectRoot>/services/auth/), the task is :services:auth:test.
        // Falls back to a root-level `test` task if the module dir can't be determined or
        // if it equals the project root (single-module project).
        //
        // Fallback-only: this filesystem-derived path is used when the validator could not
        // supply an authoritative Gradle path (plugin unavailable, non-Gradle project, or
        // the validator was not run because `module` was null). When the authoritative path
        // IS available, it supersedes this value (see `effectiveGradlePath` below).
        val gradleSubprojectPath: String? = module?.let { m ->
            try {
                val contentRoot = com.intellij.openapi.roots.ModuleRootManager.getInstance(m)
                    .contentRoots.firstOrNull()?.path
                if (contentRoot != null) {
                    val rel = contentRoot.removePrefix(basePath).trimStart('/', File.separatorChar)
                    if (rel.isNotBlank()) ":" + rel.replace(File.separatorChar, ':') else null
                } else null
            } catch (_: Exception) { null }
        }

        // Maven: for multi-module projects, restrict the build to the module containing
        // the test class. Maven module directories contain their own pom.xml.
        //
        // Fallback-only for the same reason as `gradleSubprojectPath` above — the validator's
        // authoritative directory (when available) supersedes this via `effectiveMavenDir`.
        val mavenModuleDir: File? = module?.let { m ->
            try {
                val contentRoot = com.intellij.openapi.roots.ModuleRootManager.getInstance(m)
                    .contentRoots.firstOrNull()?.path
                if (contentRoot != null && File(contentRoot, "pom.xml").exists()) File(contentRoot)
                else null
            } catch (_: Exception) { null }
        }

        // Prefer the validator-supplied authoritative build path when available. Gradle
        // subproject paths start with `:` (e.g. `:services:auth`); Maven module directories
        // are absolute filesystem paths. Disambiguation is unambiguous because no legal
        // filesystem path begins with `:`.
        val effectiveGradlePath: String? =
            if (authoritativeBuildPath != null && authoritativeBuildPath.startsWith(":")) authoritativeBuildPath
            else gradleSubprojectPath

        val effectiveMavenDir: File? =
            if (authoritativeBuildPath != null && !authoritativeBuildPath.startsWith(":")) File(authoritativeBuildPath)
            else mavenModuleDir

        // For Maven multi-module: run from the submodule directory so Maven uses the
        // submodule's pom.xml and doesn't walk unrelated modules. For everything else
        // (Gradle, single-module Maven) always run from the project root.
        val workDir = if (hasMaven && effectiveMavenDir != null && effectiveMavenDir != baseDir) effectiveMavenDir else baseDir

        // Build the argv list directly — no shell interpreter is invoked.
        //
        // Security rationale (T4): passing a single command string to `sh -c` would allow
        // shell metacharacters in class names, method names, or module paths to be interpreted
        // by the shell even after input validation. argv-form ProcessBuilder passes each
        // argument as a discrete array element to execve(2); the OS never invokes a shell and
        // metacharacters are treated as literal characters.
        //
        // METHOD_NAME_REGEX still runs first (above) and rejects method names that contain
        // `;`, `$`, backticks, spaces, or other non-identifier characters. That validation
        // is the primary guard; argv-form is defence-in-depth.
        //
        // Wrapper resolution: prefer the project-local wrapper script (`mvnw` / `gradlew`)
        // over the system-installed binary so the project's pinned Maven/Gradle version is
        // used. The wrapper is resolved to an absolute path so `ProcessBuilder` does not
        // depend on the working directory's position in PATH.
        val argv: List<String> = when {
            hasMaven -> {
                // Surefire native multi-method syntax: `-Dtest=ClassName#method1+method2+method3`.
                // The `+` separator is Surefire-specific (NOT comma — comma separates
                // distinct classes). Single-method stays `-Dtest=Class#m`; whole-class
                // stays `-Dtest=Class`. The entire value is a single argv element — the OS
                // does not split on `+` or `#`, so no quoting is needed.
                val methodPart = when (methods.size) {
                    0 -> ""
                    1 -> "#${methods.first()}"
                    else -> "#${methods.joinToString("+")}"
                }
                val dTestArg = "-Dtest=${className}${methodPart}"
                // Resolve wrapper → absolute path; fall back to PATH executable
                // (`mvn.cmd` on Windows, `mvn` on Unix). See BuildToolExecutableResolver.
                val mvnExec = BuildToolExecutableResolver.resolveMaven(baseDir)
                if (effectiveMavenDir != null && effectiveMavenDir != baseDir) {
                    // Run only the submodule to avoid rebuilding unrelated modules.
                    // workDir is already set to effectiveMavenDir above so Maven's pom.xml
                    // is the submodule's pom — no `-pl` needed, but `--also-make` ensures
                    // upstream dependencies are up to date.
                    listOf(mvnExec, "test", dTestArg, "-Dsurefire.useFile=false", "-q", "--also-make")
                } else {
                    listOf(mvnExec, "test", dTestArg, "-Dsurefire.useFile=false", "-q")
                }
            }
            hasGradle -> {
                // Resolve wrapper → absolute path; fall back to PATH executable
                // (`gradle.bat` on Windows, `gradle` on Unix). See BuildToolExecutableResolver.
                val gradleExec = BuildToolExecutableResolver.resolveGradle(baseDir)
                val taskPrefix = if (effectiveGradlePath != null) "${effectiveGradlePath}:test" else "test"
                // Gradle multi-method: repeat the `--tests` flag once per selector.
                // Each selector is 'ClassName.methodName' (Gradle uses `.` not `#`).
                // Each `--tests` value is a discrete argv element — no shell quoting needed.
                // Whole-class: a single `--tests ClassName` element with no method suffix.
                val testsArgs: List<String> = when (methods.size) {
                    0 -> listOf("--tests", className)
                    else -> methods.flatMap { m -> listOf("--tests", "$className.$m") }
                }
                listOf(gradleExec, taskPrefix) + testsArgs + listOf("--no-daemon", "-q")
            }
            else -> return ToolResult(
                "No Maven (pom.xml) or Gradle (build.gradle) build file found in project root.",
                "No build tool found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        return try {
            val processBuilder = ProcessBuilder(argv)

            processBuilder.directory(workDir)
            processBuilder.redirectErrorStream(true)

            val process = processBuilder.start()
            val toolCallId = RunCommandTool.currentToolCallId.get()
            val activeStreamCallback = resolveStreamCallback(project)

            val outputBuilder = StringBuilder()
            val readerThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            outputBuilder.appendLine(line)
                            if (toolCallId != null) {
                                activeStreamCallback?.invoke(toolCallId, line + "\n")
                            }
                            line = reader.readLine()
                        }
                    }
                } catch (_: Exception) { }
            }.apply {
                isDaemon = true
                name = "RunTests-Output-${toolCallId ?: "shell"}"
                start()
            }

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                readerThread.join(1000)
                val rawOutput = outputBuilder.toString()
                val spilled = spillOrFormat(rawOutput, project)
                return ToolResult(
                    content = "[TIMEOUT] Test execution timed out after ${timeoutSeconds}s for $testTarget.\nPartial output:\n${spilled.preview}",
                    summary = "Test timeout",
                    tokenEstimate = TokenEstimator.estimate(spilled.preview),
                    isError = true,
                    spillPath = spilled.spilledToFile,
                )
            }

            readerThread.join(2000)
            val rawOutput = outputBuilder.toString()
            // Check build failure markers on full output before truncation
            val isBuildFailure = rawOutput.contains("BUILD FAILURE") ||       // Maven
                rawOutput.contains("COMPILATION ERROR") ||                    // Maven
                rawOutput.contains("compileTestJava FAILED") ||               // Gradle Java
                rawOutput.contains("compileTestKotlin FAILED")                // Gradle Kotlin
            // "Tests ran" markers from Maven (Surefire summary line) and Gradle
            // (task header). Used to distinguish "tests ran but reports missing"
            // from "nothing happened — not a test class".
            //
            // Gradle marker is matched per-line via [GRADLE_TEST_TASK_REGEX]: a
            // MULTILINE-anchored `^> Task :(...:)*test\b` pattern that correctly
            // handles nested project paths (e.g. `> Task :services:auth:test`)
            // and rejects compile phases like `compileTestJava`, `testClasses`,
            // or arbitrary log lines that happen to contain `":test "`.
            val hasTestRanMarker = rawOutput.contains("Tests run:") ||
                GRADLE_TEST_TASK_REGEX.containsMatchIn(rawOutput)
            val spilledOutput = spillOrFormat(rawOutput, project)

            val exitCode = process.exitValue()

            // Parse Surefire/Gradle XML reports. This is the authoritative signal —
            // exit code 0 with 0 tests means the target class isn't actually a test
            // class, and should surface as NO_TESTS_FOUND (not "Tests PASSED").
            val buildTool = if (hasMaven) "maven" else "gradle"
            val reportEntries = parseJUnitXmlReports(workDir, buildTool)

            // Branch ordering rationale: when we have parsed test entries, prefer
            // them over the `isBuildFailure` banner. With `-fae` / `--fail-at-end`,
            // or a compile abort in main sources that happens AFTER tests have
            // already run, `BUILD FAILURE` can appear alongside genuine surefire
            // reports. The parsed XML entries are more authoritative than a later
            // build-phase failure — the build-failure banner is still preserved
            // for the LLM via the appended `--- stdout ---` tail.
            when {
                reportEntries != null && reportEntries.isNotEmpty() -> {
                    val base = formatStructuredResults(reportEntries, testTarget, this, project)
                    // Diagnostic note: Surefire's default <useFile>true</useFile>
                    // writes per-suite XML that may drop individual testcases. If
                    // the "Tests run: N" summary count exceeds what we parsed,
                    // warn the LLM so it doesn't trust our count blindly.
                    val summaryCount = extractMavenTestsRunCount(rawOutput)
                    val diagnostic = if (summaryCount != null && summaryCount > reportEntries.size) {
                        "[WARN] Parsed ${reportEntries.size} test cases but Maven summary reports $summaryCount — " +
                            "reports may be missing individual testcases (Surefire <useFile>true> default).\n\n"
                    } else ""
                    val combined = diagnostic + base.content + "\n\n--- stdout ---\n" + spilledOutput.preview
                    base.copy(
                        content = combined,
                        tokenEstimate = TokenEstimator.estimate(combined),
                        spillPath = base.spillPath ?: spilledOutput.spilledToFile,
                    )
                }
                reportEntries != null && reportEntries.isEmpty() -> {
                    // XML reports exist but all had tests="0" → class had no @Test methods
                    noTestsFoundResult(testTarget, argv.joinToString(" "), exitCode, spilledOutput.preview, spillPath = spilledOutput.spilledToFile)
                }
                isBuildFailure -> {
                    // Preserve the existing BUILD FAILED message — no reports to parse
                    // because the build phase failed before tests could even start.
                    ToolResult(
                        content = "BUILD FAILED — test execution did not start (exit code $exitCode).\n\n" +
                            "Fix compilation errors and try again. Use diagnostics tool to check for errors.\n\n" +
                            spilledOutput.preview,
                        summary = "Build failed before tests",
                        tokenEstimate = TokenEstimator.estimate(spilledOutput.preview),
                        isError = true,
                        spillPath = spilledOutput.spilledToFile,
                    )
                }
                hasTestRanMarker -> {
                    // stdout claims tests ran but XML is absent — rare edge case
                    // (Surefire <useFile>false> plus no report dir, or a build-abort
                    // after tests started). Surface as an explicit warning so the
                    // agent knows the result is ambiguous.
                    ToolResult(
                        content = "Tests ran but no XML reports were found under ${workDir.path}. " +
                            "Possible causes: Surefire -Dmaven.test.skip, custom reports dir, " +
                            "or build aborted during write.\n\n" +
                            "Exit code: $exitCode\n\n--- stdout ---\n${spilledOutput.preview}",
                        summary = "Tests ran but XML missing",
                        tokenEstimate = TokenEstimator.estimate(spilledOutput.preview),
                        isError = true,
                        spillPath = spilledOutput.spilledToFile,
                    )
                }
                else -> {
                    // Neither XML reports nor "Tests run:" marker — nothing actually
                    // executed. This is the user-incident-#2 case: target wasn't a
                    // real test class.
                    noTestsFoundResult(testTarget, argv.joinToString(" "), exitCode, spilledOutput.preview, spillPath = spilledOutput.spilledToFile)
                }
            }
        } catch (e: Exception) {
            ToolResult("Error running tests: ${e.message}", "Test execution error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    /**
     * Build the standard NO_TESTS_FOUND result used by [executeWithShell] when
     * the Surefire/Gradle run succeeded but matched zero test methods. This is
     * the critical anti-"Tests PASSED" signal for incident #2 — the user asked
     * to run tests for a class that had no `@Test` annotations, Surefire exited 0
     * with 0 tests, and the agent previously reported the run as a success. The
     * `isError = true` flag guarantees the LLM treats this as a problem to fix.
     */
    private fun noTestsFoundResult(
        testTarget: String,
        command: String,
        exitCode: Int,
        truncatedOutput: String,
        spillPath: String? = null
    ): ToolResult {
        val content = "NO_TESTS_FOUND — Surefire/Gradle ran successfully but matched no test methods.\n" +
            "Verify the class has @Test methods and is in a test source root.\n\n" +
            "Command: $command\n" +
            "Exit code: $exitCode\n\n" +
            "--- stdout (last N lines) ---\n$truncatedOutput"
        return ToolResult(
            content = content,
            summary = "NO_TESTS_FOUND: $testTarget",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = true,
            spillPath = spillPath,
        )
    }

    /**
     * Extract the "Tests run: N" count from Maven Surefire's summary line.
     * Returns null if no summary line is present. Used to diagnose cases where
     * the XML reports undercount (Surefire <useFile>true</useFile> default).
     *
     * Surefire emits `Tests run: N, ...` per suite AND a final aggregate total
     * after all suites complete. We want the aggregate, so we match the LAST
     * occurrence rather than the first.
     */
    internal fun extractMavenTestsRunCount(rawOutput: String): Int? {
        val regex = Regex("""Tests run:\s*(\d+)""")
        return regex.findAll(rawOutput).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: rerun_failed_tests
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Re-run only the failed / errored tests from the most-recent (or named) test session.
     *
     * Strategy (research doc Section C, checklist D3):
     * 1. Resolve the last test session via [RunContentManager.allDescriptors], filtered by
     *    those whose console is an [SMTRunnerConsoleView]. Optional [session_id] narrows to a
     *    specific descriptor by display-name substring.
     * 2. Extract failed tests via [collectTestResults] + [TestStatus.FAILED] / [TestStatus.ERROR].
     * 3. Find the original run configuration via [RunManager.allSettings] by display-name match.
     * 4. Build a new [RunnerAndConfigurationSettings] with only the failed test classes wired in
     *    (via JUnit [PersistentData] reflection — same technique as [createJUnitRunSettings]).
     * 5. Launch via [ProgramRunnerUtil.executeConfigurationAsync] + full [RunInvocation] machinery
     *    (same as run_tests).
     *
     * Error categories specific to this action:
     * - [NO_PRIOR_TEST_SESSION]: no active descriptor with a test console found.
     * - [CONFIGURATION_NOT_FOUND]: descriptor exists but the original run config can't be resolved.
     *
     * Note: pytest rerun (--lf flag) follows a different path — see [PythonRuntimeExecTool].
     * TODO: wire python_runtime_exec for pytest --lf (defer per research Section C2).
     */
    private suspend fun executeRerunFailedTests(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()

        // Bug 4 — Layer C: same indexing barrier as run_tests.
        if (!com.workflow.orchestrator.core.vfs.waitForSmartModeOrTimeout(project)) {
            return ToolResult(
                content = "DUMB_MODE: indexing did not complete within 60s. " +
                    "A recent file mutation triggered reindexing. Retry shortly.",
                summary = "DUMB_MODE: timeout waiting for indexing",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )
        }

        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val timeoutSeconds = (params["timeout"]?.jsonPrimitive?.intOrNull?.toLong() ?: RUN_TESTS_DEFAULT_TIMEOUT)
            .coerceIn(1, RUN_TESTS_MAX_TIMEOUT)

        // Step 1: Resolve the last (or named) test session descriptor.
        val runContentManager = com.intellij.execution.ui.RunContentManager.getInstance(project)
        val allDescriptors = runContentManager.allDescriptors

        val descriptor = if (sessionId != null) {
            allDescriptors.firstOrNull { it.displayName?.contains(sessionId, ignoreCase = true) == true &&
                TestConsoleUtils.unwrapToTestConsole(it.executionConsole) != null }
        } else {
            // allDescriptors ordering is implementation-defined; callers needing a specific session should pass session_id.
            allDescriptors.lastOrNull { TestConsoleUtils.unwrapToTestConsole(it.executionConsole) != null }
        }

        if (descriptor == null) {
            val sessionHint = if (sessionId != null) " matching '$sessionId'" else ""
            return ToolResult(
                content = "NO_PRIOR_TEST_SESSION: No previous test session$sessionHint found.\n\n" +
                    "Run a test first with run_tests, then use rerun_failed_tests to re-execute failed tests.",
                summary = "NO_PRIOR_TEST_SESSION",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Step 2: Extract failed / errored tests from the descriptor's test tree.
        val testRoot = TestConsoleUtils.findTestRoot(descriptor)
        if (testRoot == null) {
            return ToolResult(
                content = "NO_PRIOR_TEST_SESSION: The test session '${descriptor.displayName}' has no test results available.\n\n" +
                    "Run a test first with run_tests, then use rerun_failed_tests.",
                summary = "NO_PRIOR_TEST_SESSION: no test results",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val allTests = collectTestResults(testRoot)
        val failedTests = allTests.filter { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR }

        if (failedTests.isEmpty()) {
            val passed = allTests.count { it.status == TestStatus.PASSED }
            return ToolResult(
                content = "No failed tests to rerun in session '${descriptor.displayName}'.\n\n" +
                    "$passed test(s) passed. All tests are green — nothing to rerun.",
                summary = "No failed tests to rerun",
                tokenEstimate = 20,
                isError = false
            )
        }

        // Step 3: Find the original run configuration by display name.
        val runManager = RunManager.getInstance(project)
        val originalSettings = runManager.allSettings.find { it.name == descriptor.displayName }
            ?: runManager.allSettings.firstOrNull { it.name?.contains(descriptor.displayName ?: "", ignoreCase = true) == true }
            ?: return ToolResult(
                content = "CONFIGURATION_NOT_FOUND: Could not find the original run configuration for session '${descriptor.displayName}'.\n\n" +
                    "Available configs: [${runManager.allSettings.joinToString(", ") { it.name }}]",
                summary = "CONFIGURATION_NOT_FOUND: ${descriptor.displayName}",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        // Step 4: Build a filtered configuration containing only the failed tests.
        // Extract class names from the test result entries (format: "com.example.FooTest.testMethod").
        val failedClassMethods = failedTests.map { entry ->
            val name = entry.name
            // Split on last '.' if the name looks fully qualified (contains a '.'), otherwise
            // treat the whole name as a class (method-level name is not always available).
            val dotIdx = name.lastIndexOf('.')
            if (dotIdx > 0 && dotIdx < name.length - 1) {
                val cls = name.substring(0, dotIdx)
                val method = name.substring(dotIdx + 1)
                cls to method
            } else {
                name to null
            }
        }
        val failedClasses = failedClassMethods.map { it.first }.toSet()
        val testTarget = failedClasses.first()   // Primary class for breadcrumb and config name

        // Create a new (temporary) run configuration mirroring the original but scoped to the
        // failed tests. Use reflection — same pattern as createJUnitRunSettings.
        val reasonOut = StringBuilder()
        val filteredSpec = try {
            val isTestNG = originalSettings.configuration.type.id == "TestNG"
            val configTypeId = if (isTestNG) "TestNG" else "JUnit"
            val testConfigType = com.intellij.execution.configurations.ConfigurationType.CONFIGURATION_TYPE_EP
                .extensionList.find { it.id == configTypeId }
                ?: run {
                    reasonOut.append("no '$configTypeId' ConfigurationType registered")
                    null
                }

            if (testConfigType != null) {
                val factory = testConfigType.configurationFactories.firstOrNull()
                val failedDesc = if (failedClasses.size == 1) {
                    failedClasses.first().substringAfterLast('.')
                } else {
                    "${failedClasses.first().substringAfterLast('.')}+${failedClasses.size - 1}more"
                }
                val newConfigName = "[Rerun] $failedDesc"
                val newSettings = factory?.let { runManager.createConfiguration(newConfigName, it) }

                if (newSettings != null) {
                    val config = newSettings.configuration
                    try {
                        val dataMethodName = if (isTestNG) "getPersistantData" else "getPersistentData"
                        val getDataMethod = config.javaClass.methods.find { it.name == dataMethodName }
                        val data = getDataMethod?.invoke(config)
                        if (data != null) {
                            val testObjectField = data.javaClass.getField("TEST_OBJECT")
                            val mainClassField = data.javaClass.getField("MAIN_CLASS_NAME")
                            // Use "class" mode for single class; for multiple classes use
                            // "pattern" mode with comma-separated class names.
                            if (failedClasses.size == 1) {
                                testObjectField.set(data, if (isTestNG) "CLASS" else "class")
                                mainClassField.set(data, failedClasses.first())
                            } else {
                                // Pattern mode: one entry per failed (Class[, method])
                                // pair so the rerun executes exactly the failures, not the
                                // whole containing class. Per JUnitConfiguration.bePatternConfiguration
                                // each entry is "fully.qualified.Class" (whole class) or
                                // "fully.qualified.Class,methodName" (single method).
                                // Backing field myPattern is private; setPatterns is the
                                // public API on JUnitConfiguration.Data.
                                testObjectField.set(data, if (isTestNG) "CLASS" else "pattern")
                                mainClassField.set(data, failedClasses.first())
                                try {
                                    val patterns = java.util.LinkedHashSet<String>(failedClassMethods.size).apply {
                                        failedClassMethods.forEach { (cls, m) ->
                                            add(if (m == null) cls else "$cls,$m")
                                        }
                                    }
                                    data.javaClass.getMethod(
                                        "setPatterns", java.util.LinkedHashSet::class.java
                                    ).invoke(data, patterns)
                                } catch (_: Exception) {
                                    // Setter not callable on this plugin variant — leave
                                    // myPattern empty; MAIN_CLASS_NAME above ensures the
                                    // first failed class still reruns instead of nothing.
                                }
                            }
                        }
                    } catch (_: Exception) { /* best-effort field population */ }

                    // Assign module from original config if possible
                    val testModule = findModuleForClass(project, testTarget)
                    if (testModule != null) {
                        try {
                            val setModuleMethod = newSettings.configuration.javaClass.getMethod(
                                "setModule", com.intellij.openapi.module.Module::class.java
                            )
                            setModuleMethod.invoke(newSettings.configuration, testModule)
                        } catch (_: Exception) { }
                    }

                    newSettings.isTemporary = true
                    newSettings to (testModule ?: findModuleForClass(project, testTarget))
                } else {
                    reasonOut.append("could not create run configuration")
                    null
                }
            } else null
        } catch (e: Exception) {
            reasonOut.append("exception during filtered config setup: ${e.message}")
            null
        }

        if (filteredSpec == null) {
            // Fall back to relaunching the original config with all tests — still useful
            // even if we couldn't narrow to just the failures.
            val reason = reasonOut.toString().ifBlank { "could not build filtered config" }
            return ToolResult(
                content = "CONFIGURATION_NOT_FOUND: Could not build a filtered run configuration for rerun: $reason.\n\n" +
                    "Hint: use run_tests(class_name='${testTarget}') to run that class again.",
                summary = "CONFIGURATION_NOT_FOUND: cannot build filtered config",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val (rerunSettings, rerunModule) = filteredSpec

        // Step 5: Launch via full RunInvocation machinery (same as executeWithNativeRunner).
        val invocation = project.service<AgentService>().newRunInvocation("rerun-failed-${System.currentTimeMillis()}")
        try {
            val result = withTimeoutOrNull(timeoutSeconds * 1000) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation {
                        Disposer.dispose(invocation)
                    }
                    com.intellij.openapi.application.invokeLater {
                        try {
                            val compileContextRef = AtomicReference<com.intellij.openapi.compiler.CompileContext?>(null)
                            val buildConnection = project.messageBus.connect()
                            invocation.subscribeTopic(buildConnection)
                            buildConnection.subscribe(
                                CompilerTopics.COMPILATION_STATUS,
                                object : CompilationStatusListener {
                                    override fun compilationFinished(
                                        aborted: Boolean,
                                        errors: Int,
                                        warnings: Int,
                                        compileContext: com.intellij.openapi.compiler.CompileContext,
                                    ) {
                                        if (aborted || errors > 0) compileContextRef.set(compileContext)
                                    }
                                }
                            )

                            val module = rerunModule
                            if (module != null) {
                                try {
                                    com.intellij.task.ProjectTaskManager.getInstance(project)
                                        .build(module)
                                        .onSuccess { buildResult ->
                                            if (buildResult.hasErrors() || buildResult.isAborted) {
                                                if (continuation.isActive) {
                                                    continuation.resume(
                                                        buildCompileFailureResult(
                                                            compileContextRef.get(), testTarget, buildResult.isAborted
                                                        )
                                                    )
                                                }
                                                return@onSuccess
                                            }
                                            launchRerun(rerunSettings, invocation, project, continuation, testTarget, failedTests.size)
                                        }
                                        .onError { _ ->
                                            if (continuation.isActive) {
                                                continuation.resume(
                                                    buildCompileFailureResult(compileContextRef.get(), testTarget, aborted = false)
                                                )
                                            }
                                        }
                                } catch (e: Exception) {
                                    if (continuation.isActive) continuation.resume(null)
                                }
                            } else {
                                // No module to build — launch directly
                                launchRerun(rerunSettings, invocation, project, continuation, testTarget, failedTests.size)
                            }
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                }
            }

            if (result == null) {
                return ToolResult(
                    "[TIMEOUT] Rerun of failed tests timed out after ${timeoutSeconds}s for $testTarget.",
                    "Rerun timeout", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )
            }
            return result
        } finally {
            Disposer.dispose(invocation)
        }
    }

    /** Launch the rerun environment and wire up result collection via RunInvocation. */
    private fun launchRerun(
        settings: RunnerAndConfigurationSettings,
        invocation: RunInvocation,
        project: Project,
        continuation: kotlinx.coroutines.CancellableContinuation<ToolResult?>,
        testTarget: String,
        failedCount: Int,
    ) {
        try {
            val executor = DefaultRunExecutor.getRunExecutorInstance()
            val env = ExecutionEnvironmentBuilder.createOrNull(executor, settings)?.build()
            if (env == null) {
                if (continuation.isActive) continuation.resume(ToolResult(
                    "PROCESS_START_FAILED: Could not create execution environment for rerun.",
                    "Rerun failed", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                ))
                return
            }

            val callback = object : com.intellij.execution.runners.ProgramRunner.Callback {
                override fun processStarted(descriptor: com.intellij.execution.ui.RunContentDescriptor?) {
                    if (descriptor == null) {
                        if (continuation.isActive) continuation.resume(ToolResult(
                            "PROCESS_START_FAILED: Rerun produced no descriptor.",
                            "Rerun no descriptor", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                        ))
                        return
                    }
                    invocation.onDispose {
                        val currentDesc = invocation.descriptorRef.get() ?: return@onDispose
                        val runExecutor = DefaultRunExecutor.getRunExecutorInstance()
                        ApplicationManager.getApplication().invokeLater {
                            com.intellij.execution.ui.RunContentManager.getInstance(project)
                                .removeRunContent(runExecutor, currentDesc)
                        }
                    }
                    handleDescriptorReady(descriptor, continuation, "$testTarget (rerun $failedCount failed)", invocation, project)
                }
            }

            try {
                ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)
            } catch (_: NoSuchMethodError) {
                env.callback = callback
                ProgramRunnerUtil.executeConfiguration(env, false, true)
            }

            val runConnection = project.messageBus.connect()
            invocation.subscribeTopic(runConnection)
            runConnection.subscribe(
                com.intellij.execution.ExecutionManager.EXECUTION_TOPIC,
                object : com.intellij.execution.ExecutionListener {
                    override fun processNotStarted(
                        executorId: String,
                        e: com.intellij.execution.runners.ExecutionEnvironment,
                    ) {
                        if (e === env && continuation.isActive) {
                            continuation.resume(ToolResult(
                                "PROCESS_START_FAILED: Rerun did not start (no runner registered or executor disabled).",
                                "Rerun not started", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                            ))
                        }
                    }
                }
            )
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resume(ToolResult(
                "UNEXPECTED_ERROR: ${e.javaClass.simpleName}: ${e.message}",
                "Rerun error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            ))
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: compile_module
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeCompileModule(params: JsonObject, project: Project): ToolResult {
        val moduleName = params["module"]?.jsonPrimitive?.content
        val checkDependents = params["check_dependents"]?.jsonPrimitive?.booleanOrNull ?: false
        val refreshMavenFirst = params["refresh_maven_first"]?.jsonPrimitive?.booleanOrNull ?: false

        // Feedback #7 (2026-05-17): after `run_command mvn install`, IntelliJ's in-memory
        // project model holds the previously-resolved JAR paths and versions. compile_module
        // then recompiles against the stale classpath. Opt-in flag asks Maven to reimport
        // first so the classpath is fresh before CompilerManager.compile.
        val mavenRefreshNote: String = if (refreshMavenFirst) {
            val detect = com.workflow.orchestrator.agent.tools.framework.maven.detectAndRegisterMaven(project)
            when (detect) {
                is com.workflow.orchestrator.agent.tools.framework.maven.MavenDetectResult.AlreadyImported -> {
                    val mgrClass = Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager")
                    val mgr = mgrClass.getMethod("getInstance", Project::class.java).invoke(null, project)
                    runCatching {
                        mgrClass.getMethod("forceUpdateAllProjectsOrFindAllAvailablePomFiles").invoke(mgr)
                    }
                    val completed = com.workflow.orchestrator.agent.tools.framework.maven.awaitMavenImport(project, timeoutMs = 30_000)
                    if (completed) "Maven reimport completed before compile. " else "Maven reimport timed out (30s) — compiling against the current classpath. "
                }
                is com.workflow.orchestrator.agent.tools.framework.maven.MavenDetectResult.NewlyRegistered -> {
                    val completed = com.workflow.orchestrator.agent.tools.framework.maven.awaitMavenImport(project, timeoutMs = 30_000)
                    val status = if (completed) "completed" else "timed out (30s)"
                    "Registered ${detect.pomPaths.size} pom.xml file(s) and waited for import ($status). "
                }
                com.workflow.orchestrator.agent.tools.framework.maven.MavenDetectResult.NoMavenPlugin ->
                    "refresh_maven_first ignored — Maven plugin not loaded. "
                com.workflow.orchestrator.agent.tools.framework.maven.MavenDetectResult.NoPomFound ->
                    "refresh_maven_first ignored — no pom.xml found at the project root. "
                is com.workflow.orchestrator.agent.tools.framework.maven.MavenDetectResult.Failed ->
                    "refresh_maven_first failed: ${detect.message}. Continuing with stale classpath. "
            }
        } else ""

        return try {
            val result = withTimeoutOrNull(120_000L) {
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { }
                    ApplicationManager.getApplication().invokeLater {
                        val compiler = CompilerManager.getInstance(project)

                        val scope = if (moduleName != null) {
                            val module = ModuleManager.getInstance(project).modules.find { it.name == moduleName }
                            if (module != null) {
                                compiler.createModuleCompileScope(module, checkDependents)
                            } else {
                                val available = ModuleManager.getInstance(project).modules
                                    .map { it.name }
                                    .joinToString(", ")
                                if (!cont.isCompleted) {
                                    cont.resume(
                                        ToolResult(
                                            "Module '$moduleName' not found. Available modules: $available",
                                            "Module not found", TokenEstimator.estimate(available), isError = true
                                        )
                                    )
                                }
                                return@invokeLater
                            }
                        } else {
                            compiler.createProjectCompileScope(project)
                        }

                        val target = moduleName ?: "project"

                        compiler.make(scope) { aborted, errors, warnings, context ->
                            val compileResult = when {
                                aborted -> ToolResult(
                                    "Compilation of $target was aborted.",
                                    "Compilation aborted", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                                )
                                errors > 0 -> {
                                    // Delegate to the shared formatter so run_tests and compile_module
                                    // produce identical per-file output. Leading line mirrors the old
                                    // format for caller-compatibility.
                                    formatCompileErrors(
                                        context = context,
                                        target = target,
                                        leadingLine = "Compilation of $target failed: $errors error(s), $warnings warning(s).",
                                        warnings = warnings
                                    )
                                }
                                else -> {
                                    val warningNote = if (warnings > 0) " with $warnings warning(s)" else ""
                                    ToolResult(
                                        "${mavenRefreshNote}Compilation of $target successful$warningNote: 0 errors.",
                                        "Build OK", ToolResult.ERROR_TOKEN_ESTIMATE
                                    )
                                }
                            }
                            if (!cont.isCompleted) cont.resume(compileResult)
                        }
                    }
                }
            }

            result ?: ToolResult(
                "Compilation timed out after 120 seconds. The build may be stuck.",
                "Compile timeout", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        } catch (e: Exception) {
            ToolResult("Compilation error: ${e.message}", "Compilation error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }
}
