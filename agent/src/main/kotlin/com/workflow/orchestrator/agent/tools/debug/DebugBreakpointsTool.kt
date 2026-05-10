package com.workflow.orchestrator.agent.tools.debug

import com.intellij.debugger.ui.breakpoints.JavaExceptionBreakpointType
import com.intellij.debugger.ui.breakpoints.JavaFieldBreakpointType
import com.intellij.debugger.ui.breakpoints.JavaMethodBreakpointType
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.remote.RemoteConfiguration
import com.intellij.execution.remote.RemoteConfigurationType
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.agent.tools.integration.ToolValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.java.debugger.breakpoints.properties.JavaExceptionBreakpointProperties
import org.jetbrains.java.debugger.breakpoints.properties.JavaFieldBreakpointProperties
import org.jetbrains.java.debugger.breakpoints.properties.JavaMethodBreakpointProperties
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Breakpoint management + debug session start/attach.
 *
 * 8 actions covering breakpoint CRUD and session lifecycle initiation.
 */
class DebugBreakpointsTool(private val controller: AgentDebugController) : AgentTool {

    override val name = "debug_breakpoints"

    override val description = """
Breakpoint management — add, remove, list breakpoints, and attach the debugger to a remote JVM.

Breakpoints are project-scoped and do NOT require a running debug session. attach_to_process
creates a session. To launch a run configuration in debug mode, use runtime_exec(action=run_config, mode=debug).

Actions:
- add_breakpoint(file, line, condition?, log_expression?, temporary?, suspend_policy?, pass_count?) → Add line breakpoint. Fails if the line is not breakpointable (comment, blank line, import).
- method_breakpoint(class_name, method_name, watch_entry?, watch_exit?) → Add method breakpoint
- exception_breakpoint(exception_class, caught?, uncaught?, condition?) → Break on exception
- field_watchpoint(class_name, field_name, file?, watch_read?, watch_write?) → Watch field access/modification
- remove_breakpoint(file, line) → Remove breakpoint at file:line
- list_breakpoints(file?) → List all breakpoints (line, method, exception, field), optionally filtered by file
- attach_to_process(port, host?, name?) → Attach debugger to remote JVM. Target must be listening on the JDWP port.

All breakpoint actions modify IDE state. attach_to_process creates a debug session.
To launch a run configuration in debug mode, use runtime_exec(action=run_config, mode=debug).
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "add_breakpoint", "method_breakpoint", "exception_breakpoint", "field_watchpoint",
                    "remove_breakpoint", "list_breakpoints", "attach_to_process"
                )
            ),
            "file" to ParameterProperty(
                type = "string",
                description = "File path relative to project or absolute — for add_breakpoint, remove_breakpoint, list_breakpoints, field_watchpoint"
            ),
            "line" to ParameterProperty(
                type = "integer",
                description = "1-based line number — for add_breakpoint, remove_breakpoint"
            ),
            "condition" to ParameterProperty(
                type = "string",
                description = "Optional conditional expression — for add_breakpoint, exception_breakpoint"
            ),
            "log_expression" to ParameterProperty(
                type = "string",
                description = "Optional expression to log when hit without stopping (log breakpoint) — for add_breakpoint"
            ),
            "temporary" to ParameterProperty(
                type = "boolean",
                description = "If true, breakpoint removed after first hit — for add_breakpoint"
            ),
            "suspend_policy" to ParameterProperty(
                type = "string",
                description = "Thread suspension policy: 'all' (pause all threads, default) or 'thread' (pause only the hitting thread — use for concurrent debugging) — for add_breakpoint",
                enumValues = listOf("all", "thread", "none")
            ),
            "pass_count" to ParameterProperty(
                type = "integer",
                description = "Break only on every Nth hit (e.g., pass_count=100 breaks on the 100th hit). Useful for loops and high-traffic code — for add_breakpoint"
            ),
            "class_name" to ParameterProperty(
                type = "string",
                description = "Fully qualified class name — for method_breakpoint, exception_breakpoint, field_watchpoint"
            ),
            "method_name" to ParameterProperty(
                type = "string",
                description = "Method name — for method_breakpoint"
            ),
            "field_name" to ParameterProperty(
                type = "string",
                description = "Field name to watch — for field_watchpoint"
            ),
            "watch_entry" to ParameterProperty(
                type = "boolean",
                description = "Break on method entry (default: true) — for method_breakpoint"
            ),
            "watch_exit" to ParameterProperty(
                type = "boolean",
                description = "Break on method exit (default: false) — for method_breakpoint"
            ),
            "watch_read" to ParameterProperty(
                type = "boolean",
                description = "Break on field read (default: false) — for field_watchpoint"
            ),
            "watch_write" to ParameterProperty(
                type = "boolean",
                description = "Break on field write (default: true) — for field_watchpoint"
            ),
            "exception_class" to ParameterProperty(
                type = "string",
                description = "Fully qualified exception class name — for exception_breakpoint"
            ),
            "caught" to ParameterProperty(
                type = "boolean",
                description = "Break on caught exceptions (default: true) — for exception_breakpoint"
            ),
            "uncaught" to ParameterProperty(
                type = "boolean",
                description = "Break on uncaught exceptions (default: true) — for exception_breakpoint"
            ),
            "host" to ParameterProperty(
                type = "string",
                description = "Host to connect to (default: localhost) — for attach_to_process"
            ),
            "port" to ParameterProperty(
                type = "integer",
                description = "Debug port to connect to (e.g., 5005) — for attach_to_process"
            ),
            "name" to ParameterProperty(
                type = "string",
                description = "Display name for the debug configuration — for attach_to_process"
            ),
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override fun documentation(): ToolDocumentation = toolDoc("debug_breakpoints") {
        summary {
            technical(
                "IDE-mutation meta-tool that manages all IntelliJ breakpoint types (line, method, exception, " +
                "field watchpoint) and attaches to remote JVMs via JDWP. Dispatches on 7 actions; breakpoint " +
                "mutations run on EDT inside a WriteAction; attach_to_process suspends until the XDebugSession " +
                "handshake completes or times out at 30 s."
            )
            plain(
                "The agent's equivalent of the Breakpoints dialog — lets it plant, remove, list, and " +
                "inspect every kind of IntelliJ breakpoint without touching the keyboard, and lets it " +
                "connect the debugger to a JVM that's already running on a remote machine or Docker container."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.IDE_MUTATION)

        counterfactual(
            "Without debug_breakpoints, no programmatic breakpoint management. The LLM must ask the user " +
            "to manually open the Breakpoints dialog or click the gutter — then the user must report back " +
            "what's there. Setting a conditional breakpoint that only fires when `userId == 42` requires " +
            "at least 5 back-and-forth messages instead of one add_breakpoint call. attach_to_process has " +
            "no fallback at all: connecting to a remote JVM can only be done by the user through Run > Attach " +
            "to Process, which is not something the agent can trigger any other way."
        )

        llmMistake(
            "Calls add_breakpoint on a comment, blank line, or import statement. The tool pre-flights " +
            "via XDebuggerUtil.canPutBreakpointAt() and returns a structured 'Line not breakpointable' " +
            "error — but the LLM sometimes retries the same line instead of using read_file to identify " +
            "the nearest executable statement."
        )
        llmMistake(
            "Uses remove_breakpoint to clear a method breakpoint or exception breakpoint. " +
            "remove_breakpoint only matches XLineBreakpoint entries by file:line. Method and exception " +
            "breakpoints have no file:line association in the IDE; remove_breakpoint will always return " +
            "'No breakpoint found' for them. list_breakpoints first to understand what's there."
        )
        llmMistake(
            "Passes exception_class as a simple name ('NullPointerException') instead of fully " +
            "qualified ('java.lang.NullPointerException'). The tool does not validate that the class " +
            "exists in the classpath; the breakpoint is created but never fires."
        )
        llmMistake(
            "Sets both watch_entry=false and watch_exit=false for method_breakpoint, or both " +
            "watch_read=false and watch_write=false for field_watchpoint. The tool explicitly rejects " +
            "these as 'will never trigger', but the LLM occasionally omits both flags thinking the " +
            "defaults will cover it."
        )
        llmMistake(
            "Calls attach_to_process before the target JVM has started its JDWP agent. The tool " +
            "waits 30 s then returns a timeout error. The LLM should verify the remote process is " +
            "listening (e.g. via runtime_exec or a prior run_command checking the port) before attaching."
        )
        llmMistake(
            "Assumes list_breakpoints(file=...) will also show exception breakpoints for that file. " +
            "Exception breakpoints have no file association — file filter only applies to line/method/" +
            "watchpoint entries. list_breakpoints() without a file filter is required to see all exception breakpoints."
        )

        actions {
            action("add_breakpoint") {
                description {
                    technical(
                        "Adds a line breakpoint at file:line. Pre-flights with canPutBreakpointAt(); " +
                        "runs the actual add in a WriteAction on EDT. Supports conditional expressions, " +
                        "log expressions (non-suspending), temporary flag (auto-remove after first hit), " +
                        "suspend policy (all/thread/none), and pass_count (break on every Nth hit via " +
                        "JavaBreakpointProperties reflection for cross-version API compatibility)."
                    )
                    plain(
                        "Plant a breakpoint on a specific source line, with optional extra smarts: " +
                        "only stop when a condition is true, log a value without pausing, auto-remove " +
                        "after the first hit, or only trigger every 100th time through a loop."
                    )
                }
                whenLLMUses(
                    "When the LLM needs execution to pause at a known location so it can inspect state " +
                    "via debug_inspect, or when it wants to log a value mid-execution without stopping."
                )
                params {
                    required("file", "string") {
                        llmSeesIt("File path relative to project or absolute — for add_breakpoint, remove_breakpoint, list_breakpoints, field_watchpoint")
                        humanReadable("Which source file to add the breakpoint in. Relative paths are resolved against the project base directory.")
                        whenPresent("Resolved via PathValidator; a VirtualFile is located and used to create the breakpoint.")
                        constraint("must resolve inside the project or an allow-listed root (no traversal)")
                        example("src/main/kotlin/com/example/UserService.kt")
                    }
                    required("line", "integer") {
                        llmSeesIt("1-based line number — for add_breakpoint, remove_breakpoint")
                        humanReadable("Which line in the file to break on. Use 1-based numbering as shown in the editor gutter.")
                        whenPresent("Pre-flight checks whether this line is breakpointable; if not, returns a structured error before the WriteAction.")
                        constraint("must be ≥ 1")
                        example("42")
                    }
                    optional("condition", "string") {
                        llmSeesIt("Optional conditional expression — for add_breakpoint, exception_breakpoint")
                        humanReadable("A Java/Kotlin expression evaluated in the breakpoint's scope. The debugger only pauses when it evaluates to true.")
                        whenPresent("Set as an XExpressionImpl on the breakpoint. Evaluated by the JVM at each hit; invalid expressions cause the breakpoint to disable itself silently.")
                        whenAbsent("No condition — the breakpoint fires unconditionally.")
                        example("userId == 42")
                        example("list.size() > 100")
                    }
                    optional("log_expression", "string") {
                        llmSeesIt("Optional expression to log when hit without stopping (log breakpoint) — for add_breakpoint")
                        humanReadable("Turns the breakpoint into a tracepoint: logs the expression value to the Debug console without suspending execution.")
                        whenPresent("Sets logExpressionObject and forces suspend_policy=NONE — the thread keeps running. If suspend_policy is also provided explicitly, the explicit param overrides this default.")
                        whenAbsent("No log expression — ordinary stopping breakpoint.")
                        example("\"userId=\" + userId")
                    }
                    optional("temporary", "boolean") {
                        llmSeesIt("If true, breakpoint removed after first hit — for add_breakpoint")
                        humanReadable("One-shot breakpoint. Useful for 'pause here once' scenarios like initialization code that only runs at startup.")
                        whenPresent("Breakpoint auto-removes itself the first time it fires.")
                        whenAbsent("Defaults to false — persistent breakpoint.")
                    }
                    optional("suspend_policy", "string") {
                        llmSeesIt("Thread suspension policy: 'all' (pause all threads, default) or 'thread' (pause only the hitting thread — use for concurrent debugging) — for add_breakpoint")
                        humanReadable("Controls whether the whole JVM pauses or just the thread that hit the breakpoint. Use 'thread' when debugging concurrent code so other threads keep running.")
                        whenPresent("Applied to the breakpoint's SuspendPolicy. 'none' combined with a log_expression creates a tracepoint.")
                        whenAbsent("Defaults to 'all' (suspend all threads) unless log_expression is provided, in which case defaults to 'none'.")
                        enumValue("all", "thread", "none")
                    }
                    optional("pass_count", "integer") {
                        llmSeesIt("Break only on every Nth hit (e.g., pass_count=100 breaks on the 100th hit). Useful for loops and high-traffic code — for add_breakpoint")
                        humanReadable("Skip the first N-1 hits and only pause on the Nth. Handy when a bug manifests after a loop runs many times.")
                        whenPresent("Applied via JavaBreakpointProperties reflection (COUNT_FILTER / COUNT_FILTER_ENABLED). Silently ignored if the Java debugger plugin is unavailable.")
                        whenAbsent("No pass count — fires on every hit.")
                        constraint("must be > 1 to have effect (pass_count=1 is treated as no filter)")
                        example("100")
                    }
                }
                precondition("file must exist and be a valid source file in the project")
                precondition("line must be an executable statement — comments, blank lines, import declarations fail the pre-flight check")
                onSuccess("Returns 'Breakpoint added at {file}:{line}' with a summary of applied traits (conditional, log, temporary, pass_count, suspend type).")
                onFailure("line is not breakpointable (comment, blank, import)", "Returns 'Line {n} in {file} is not breakpointable' with a suggestion to pick a line with a statement. Pre-flight fires before the WriteAction so no partial state is created.")
                onFailure("file not found", "Returns 'File not found: {path}'.")
                onFailure("WriteAction fails", "Caught as Exception; returns 'Error adding breakpoint: {message}'.")
                example("conditional breakpoint on user 42") {
                    param("action", "add_breakpoint")
                    param("file", "src/main/kotlin/com/example/UserService.kt")
                    param("line", "87")
                    param("condition", "userId == 42")
                    outcome("Breakpoint set at UserService.kt:87 with condition 'userId == 42'. Fires only when that user's request hits the line.")
                }
                example("high-traffic loop — break on 100th hit") {
                    param("action", "add_breakpoint")
                    param("file", "src/main/java/com/example/BatchProcessor.java")
                    param("line", "234")
                    param("pass_count", "100")
                    outcome("Breakpoint set at BatchProcessor.java:234, fires on every 100th hit — skips the first 99 iterations of the loop.")
                }
                example("tracepoint — log without pausing") {
                    param("action", "add_breakpoint")
                    param("file", "src/main/kotlin/com/example/CacheService.kt")
                    param("line", "55")
                    param("log_expression", "\"cache hit: \" + key")
                    outcome("Non-suspending log breakpoint at CacheService.kt:55 — prints 'cache hit: <key>' to the Debug console on each pass without pausing execution.")
                }
                verdict {
                    keep("Core breakpoint primitive. Without it the LLM cannot programmatically set any stopping condition — all debugging would require manual user intervention.", VerdictSeverity.STRONG)
                }
            }

            action("method_breakpoint") {
                description {
                    technical(
                        "Adds a JavaMethodBreakpointType breakpoint anchored at the first line of the " +
                        "named method inside the named class. Uses smartReadAction to resolve the PSI " +
                        "class and method before entering the WriteAction. Warns when the method is " +
                        "overloaded (breaks on the first match) and always appends a performance warning " +
                        "because method breakpoints are 5-10× slower than line breakpoints."
                    )
                    plain(
                        "Set a breakpoint that fires on entry or exit of a named method — no need " +
                        "to find the exact line number. The debugger can pause the moment execution " +
                        "enters the method, when it's about to return, or both."
                    )
                }
                whenLLMUses(
                    "When the LLM knows which method to investigate but doesn't know or care which specific " +
                    "line to break on — especially useful when a method has multiple overloads or when the " +
                    "source is hard to navigate."
                )
                params {
                    required("class_name", "string") {
                        llmSeesIt("Fully qualified class name — for method_breakpoint, exception_breakpoint, field_watchpoint")
                        humanReadable("The class containing the method. Must be fully qualified, including the package path.")
                        whenPresent("PSI lookup via JavaPsiFacade.findClass() in GlobalSearchScope.allScope(). Returns MethodNotFound with available method names if the class is found but the method isn't.")
                        constraint("must be a fully qualified name, e.g. 'com.example.UserService' not 'UserService'")
                        example("com.example.service.UserService")
                    }
                    required("method_name", "string") {
                        llmSeesIt("Method name — for method_breakpoint")
                        humanReadable("The simple method name (no parentheses, no signature). For overloaded methods the breakpoint is set on the first PSI match.")
                        whenPresent("Resolved to the first matching method in the class via psiClass.methods.filter { it.name == methodName }.")
                        constraint("simple name only — no parameter types, no return type, no parentheses")
                        example("processPayment")
                    }
                    optional("watch_entry", "boolean") {
                        llmSeesIt("Break on method entry (default: true) — for method_breakpoint")
                        humanReadable("Pause as soon as the method is entered, before its first statement runs.")
                        whenPresent("Applied to JavaMethodBreakpointProperties.WATCH_ENTRY.")
                        whenAbsent("Defaults to true.")
                    }
                    optional("watch_exit", "boolean") {
                        llmSeesIt("Break on method exit (default: false) — for method_breakpoint")
                        humanReadable("Pause just before the method returns, useful for inspecting the return value.")
                        whenPresent("Applied to JavaMethodBreakpointProperties.WATCH_EXIT.")
                        whenAbsent("Defaults to false.")
                    }
                }
                rejectsParam("file", "method_breakpoint locates the file via PSI; the 'file' param is for line-based actions.")
                rejectsParam("line", "method_breakpoint resolves the line from PSI; the 'line' param is for add_breakpoint and remove_breakpoint.")
                rejectsParam("condition", "Not wired for method_breakpoint; only add_breakpoint and exception_breakpoint support conditions.")
                precondition("class must be indexed and reachable in GlobalSearchScope.allScope()")
                precondition("at least one of watch_entry or watch_exit must be true — the tool rejects both-false explicitly")
                onSuccess("Returns confirmation with class.method, watch_entry, watch_exit values. Appends an overload note if the method is overloaded. Always appends a PERFORMANCE WARNING about the 5-10× overhead.")
                onFailure("class not found", "Returns 'Class not found: {className}. Verify the fully qualified class name is correct.'")
                onFailure("method not found", "Returns 'Method not found in {className}. Available methods: {list}' — the LLM can pick the right name from the list.")
                onFailure("both watch_entry and watch_exit are false", "Returns 'breakpoint would never trigger' error before attempting the WriteAction.")
                example("break on payment processing entry") {
                    param("action", "method_breakpoint")
                    param("class_name", "com.example.service.PaymentService")
                    param("method_name", "processPayment")
                    param("watch_entry", "true")
                    param("watch_exit", "true")
                    outcome("Method breakpoint on PaymentService.processPayment, fires on entry and exit. Performance warning included in response.")
                }
                verdict {
                    keep("Useful when the LLM knows the method but not the exact line. The PSI lookup and overload note reduce follow-up errors.", VerdictSeverity.NORMAL)
                    drop("Performance overhead (5-10× slowdown) makes it unsuitable for hot code. In most cases add_breakpoint on the method's opening line achieves the same effect with no overhead. Drop candidates if token budget tightens.", VerdictSeverity.WEAK)
                }
            }

            action("exception_breakpoint") {
                description {
                    technical(
                        "Adds a JavaExceptionBreakpointType breakpoint using addBreakpoint() (not " +
                        "addLineBreakpoint() — exception breakpoints have no file:line anchor). " +
                        "Accepted for caught, uncaught, or both. Supports conditional expressions. " +
                        "Does NOT validate that the exception class exists in the classpath."
                    )
                    plain(
                        "Tell the debugger to pause whenever a specific exception is thrown — " +
                        "even before it's caught. Like a smoke alarm for a named exception class: " +
                        "the debugger stops the moment that exception happens, regardless of where in the code."
                    )
                }
                whenLLMUses(
                    "When the LLM needs to catch an exception at the throw site rather than the catch site — " +
                    "e.g. when an exception is swallowed somewhere and normal breakpoints can't locate it."
                )
                params {
                    required("exception_class", "string") {
                        llmSeesIt("Fully qualified exception class name — for exception_breakpoint")
                        humanReadable("The exception to watch for. Must be fully qualified. The tool does not verify the class exists in the classpath — a typo creates a silent non-firing breakpoint.")
                        whenPresent("Passed to JavaExceptionBreakpointProperties(exceptionClass) as the qualified name.")
                        constraint("must be fully qualified, e.g. 'java.lang.NullPointerException' not 'NullPointerException'")
                        example("java.lang.NullPointerException")
                        example("com.example.exception.PaymentDeclinedException")
                    }
                    optional("caught", "boolean") {
                        llmSeesIt("Break on caught exceptions (default: true) — for exception_breakpoint")
                        humanReadable("Pause when the exception is thrown inside a try block that will catch it.")
                        whenPresent("Applied to JavaExceptionBreakpointProperties.NOTIFY_CAUGHT.")
                        whenAbsent("Defaults to true.")
                    }
                    optional("uncaught", "boolean") {
                        llmSeesIt("Break on uncaught exceptions (default: true) — for exception_breakpoint")
                        humanReadable("Pause when the exception propagates up without being caught — typically causing the thread to die.")
                        whenPresent("Applied to JavaExceptionBreakpointProperties.NOTIFY_UNCAUGHT.")
                        whenAbsent("Defaults to true.")
                    }
                    optional("condition", "string") {
                        llmSeesIt("Optional conditional expression — for add_breakpoint, exception_breakpoint")
                        humanReadable("A Java expression evaluated at the throw site. Only pause when the condition is true.")
                        whenPresent("Set via bp.conditionExpression = XExpressionImpl.fromText(condition).")
                        whenAbsent("No condition — fires on every throw of the named exception.")
                        example("message.contains(\"timeout\")")
                    }
                }
                rejectsParam("file", "Exception breakpoints are not anchored to a file. list_breakpoints without a file filter shows them; file filter skips them.")
                rejectsParam("line", "Exception breakpoints have no line anchor — they fire at the throw site, wherever that is.")
                precondition("Java debugger plugin must be available (JavaExceptionBreakpointType class must be loadable)")
                onSuccess("Returns 'Exception breakpoint set for {exceptionClass}' with caught/uncaught flags and a note that the class is not validated against the classpath.")
                onFailure("Java exception breakpoint type not available", "Returns structured error if the Java plugin is not loaded (e.g. running in a non-Java IDE).")
                onFailure("blank exception_class", "Validated before the WriteAction; returns 'exception_class cannot be blank'.")
                example("catch NullPointerException at throw site") {
                    param("action", "exception_breakpoint")
                    param("exception_class", "java.lang.NullPointerException")
                    param("caught", "false")
                    param("uncaught", "true")
                    outcome("Exception breakpoint set for java.lang.NullPointerException, fires only on uncaught throws. Debugger will pause at the throw site the next time an uncaught NPE escapes.")
                    notes("Setting caught=false avoids noisy pauses for NPEs that are caught and handled normally — only uncaught ones (usually bugs) trigger the stop.")
                }
                verdict {
                    keep("No equivalent mechanism exists — line breakpoints cannot intercept exceptions before they're caught. This is the only way to trace a swallowed exception back to its origin.", VerdictSeverity.STRONG)
                }
            }

            action("field_watchpoint") {
                description {
                    technical(
                        "Adds a JavaFieldBreakpointType breakpoint anchored to the field's declaration " +
                        "line via PSI resolution (smartReadAction → JavaPsiFacade → PsiDocumentManager). " +
                        "Falls back to text-scan in the provided file if PSI lookup fails. Watches read " +
                        "access, write (modification), or both — configurable via WATCH_ACCESS and " +
                        "WATCH_MODIFICATION on JavaFieldBreakpointProperties."
                    )
                    plain(
                        "Watch a field like a spy — the debugger pauses every time the field is read " +
                        "or written. Useful when a field gets corrupted and you need to catch the exact " +
                        "moment it happens."
                    )
                }
                whenLLMUses(
                    "When the LLM suspects a field is being modified or read unexpectedly — e.g. a cached " +
                    "value goes stale, a counter gets corrupted — and wants to trace every access without " +
                    "adding logging statements."
                )
                params {
                    required("class_name", "string") {
                        llmSeesIt("Fully qualified class name — for method_breakpoint, exception_breakpoint, field_watchpoint")
                        humanReadable("The class declaring the field. Must be fully qualified.")
                        whenPresent("Used in JavaPsiFacade.findClass() scoped to projectScope(); then falls back to text-scan in the provided file if PSI fails.")
                        constraint("fully qualified, e.g. 'com.example.CacheService' not 'CacheService'")
                        example("com.example.service.CacheService")
                    }
                    required("field_name", "string") {
                        llmSeesIt("Field name to watch — for field_watchpoint")
                        humanReadable("The field to watch. For Kotlin properties, use the property name — the backing field name is usually the same.")
                        whenPresent("Resolved via psiClass.fields.firstOrNull { it.name == fieldName }. If PSI fails, falls back to regex text-scan looking for modifier keywords + fieldName on the same line.")
                        example("cachedValue")
                        example("userCount")
                    }
                    optional("file", "string") {
                        llmSeesIt("File path relative to project or absolute — for add_breakpoint, remove_breakpoint, list_breakpoints, field_watchpoint")
                        humanReadable("Source file hint — used as fallback if PSI cannot resolve the class (e.g. if the class is not indexed yet).")
                        whenPresent("Passed to the fallback text-scan path in findFieldInClass().")
                        whenAbsent("PSI lookup only; returns 'Field not found' if PSI cannot resolve.")
                    }
                    optional("watch_read", "boolean") {
                        llmSeesIt("Break on field read (default: false) — for field_watchpoint")
                        humanReadable("Pause whenever the field is read. High-traffic fields make this noisy — prefer watch_write unless you specifically need reads.")
                        whenPresent("Applied to JavaFieldBreakpointProperties.WATCH_ACCESS.")
                        whenAbsent("Defaults to false.")
                    }
                    optional("watch_write", "boolean") {
                        llmSeesIt("Break on field write (default: true) — for field_watchpoint")
                        humanReadable("Pause whenever the field is assigned. Default on — the most common use case is catching unexpected writes.")
                        whenPresent("Applied to JavaFieldBreakpointProperties.WATCH_MODIFICATION.")
                        whenAbsent("Defaults to true.")
                    }
                }
                rejectsParam("line", "field_watchpoint resolves its own line from PSI; the 'line' param is for add_breakpoint and remove_breakpoint.")
                precondition("class must be reachable in project scope via PSI, or 'file' must be provided for the text-scan fallback")
                precondition("at least one of watch_read or watch_write must be true — both-false is rejected explicitly")
                onSuccess("Returns 'Field watchpoint set on {class}.{field}' with file:line (from PSI), and watching flags (read + write).")
                onFailure("field not found", "Returns descriptive error with suggestion to verify class name and field name. Notes Kotlin property backing-field naming.")
                onFailure("both watch_read and watch_write are false", "Rejected before WriteAction with 'will never trigger' error.")
                example("catch the moment a cache field is overwritten") {
                    param("action", "field_watchpoint")
                    param("class_name", "com.example.service.CacheService")
                    param("field_name", "cachedUser")
                    param("watch_read", "false")
                    param("watch_write", "true")
                    outcome("Watchpoint set on CacheService.cachedUser — fires on every write to that field. The debugger will pause the next time anything assigns to cachedUser.")
                }
                verdict {
                    keep("No other tool can watch field access at the JVM level without modifying the source. Invaluable for tracking down cache corruption and unexpected state mutation.", VerdictSeverity.NORMAL)
                }
            }

            action("remove_breakpoint") {
                description {
                    technical(
                        "Finds and removes an XLineBreakpoint at the given file:line by scanning " +
                        "bpManager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>() and matching " +
                        "on fileUrl + zero-based line. Only removes line-type breakpoints (including " +
                        "method breakpoints created via addLineBreakpoint). Cannot remove exception " +
                        "breakpoints (no file:line anchor). Runs in a WriteAction on EDT."
                    )
                    plain(
                        "Remove a specific breakpoint by saying which file and line it's on. " +
                        "Works for line breakpoints, method breakpoints, and field watchpoints. " +
                        "Exception breakpoints cannot be removed this way because they have no line."
                    )
                }
                whenLLMUses(
                    "After a debugging session is done and the LLM wants to clean up breakpoints it set, " +
                    "or when a breakpoint is in the wrong place and needs to be repositioned."
                )
                params {
                    required("file", "string") {
                        llmSeesIt("File path relative to project or absolute — for add_breakpoint, remove_breakpoint, list_breakpoints, field_watchpoint")
                        humanReadable("Which file the breakpoint is in. Must match the file used when adding the breakpoint.")
                        whenPresent("Resolved to a VirtualFile; its URL is used to match against bp.fileUrl.")
                        example("src/main/kotlin/com/example/UserService.kt")
                    }
                    required("line", "integer") {
                        llmSeesIt("1-based line number — for add_breakpoint, remove_breakpoint")
                        humanReadable("Which line the breakpoint is on. Must be the exact 1-based line as it was added.")
                        whenPresent("Converted to zero-based for matching against XLineBreakpoint.line.")
                        constraint("must be ≥ 1")
                        example("87")
                    }
                }
                rejectsParam("condition", "Not relevant to removal — identifies by position only.")
                rejectsParam("class_name", "Not used by remove_breakpoint — method/exception/watchpoint removal is not supported; only XLineBreakpoints can be removed by file:line.")
                precondition("a line-type breakpoint must exist at exactly file:line — removal of method/exception breakpoints requires list_breakpoints to identify them and currently has no remove path")
                onSuccess("Returns 'Breakpoint removed from {file}:{line}'.")
                onFailure("no breakpoint at file:line", "Returns 'No breakpoint found at {file}:{line}'. Call list_breakpoints first to see what's actually there.")
                onFailure("file not found", "Returns 'File not found: {path}'.")
                example("clean up after debugging") {
                    param("action", "remove_breakpoint")
                    param("file", "src/main/kotlin/com/example/UserService.kt")
                    param("line", "87")
                    outcome("Breakpoint at UserService.kt:87 removed. The debugger will no longer pause there.")
                }
                verdict {
                    keep("Needed for breakpoint cleanup. Without it, the user has to manually clear agent-set breakpoints after a debug session.", VerdictSeverity.NORMAL)
                    drop("Cannot remove exception breakpoints or non-line-anchored breakpoints — the tool is incomplete for full breakpoint lifecycle management. A future 'remove by id' action (using breakpoint IDs from list_breakpoints) would be strictly superior.", VerdictSeverity.WEAK)
                }
            }

            action("list_breakpoints") {
                description {
                    technical(
                        "Reads bpManager.allBreakpoints and classifies each entry into three buckets: " +
                        "XLineBreakpoint (covers line, method, and field watchpoint types — all created " +
                        "via addLineBreakpoint()), JavaExceptionBreakpointType instances (checked via " +
                        "isJavaExceptionBreakpoint() with NoClassDefFoundError guard), and other. " +
                        "Per-type formatting via formatJavaBreakpointProperties() which reads " +
                        "JavaMethodBreakpointProperties and JavaFieldBreakpointProperties. File filter " +
                        "applies to line-bucket only — exception breakpoints always appear when no filter is active."
                    )
                    plain(
                        "List every breakpoint the IDE knows about — line breakpoints, method breakpoints, " +
                        "exception breakpoints, and field watchpoints — with their current status, " +
                        "conditions, and traits. Optionally filter to a specific file."
                    )
                }
                whenLLMUses(
                    "At the start of a debugging session to understand the existing breakpoint landscape, " +
                    "after adding several breakpoints to confirm they were registered, or before calling " +
                    "remove_breakpoint to find the exact file:line."
                )
                params {
                    optional("file", "string") {
                        llmSeesIt("File path relative to project or absolute — for add_breakpoint, remove_breakpoint, list_breakpoints, field_watchpoint")
                        humanReadable("Restrict output to breakpoints in a specific file. Exception breakpoints are always excluded from file-filtered results because they have no file anchor.")
                        whenPresent("Resolved to a VirtualFile URL; only XLineBreakpoints with matching fileUrl are included. Exception breakpoints are excluded when a file filter is active.")
                        whenAbsent("All breakpoints of all types are listed.")
                        example("src/main/kotlin/com/example/UserService.kt")
                    }
                }
                precondition("none — works regardless of session state, even when no debug session is active")
                onSuccess("Returns a formatted list: 'Breakpoints (N):' followed by one entry per breakpoint. Line breakpoints show 'file:line [enabled, conditional: expr, ...]'. Method breakpoints show 'Method: class.method [entry, exit, enabled]'. Field watchpoints show 'Field: class.field [access, modification, enabled]'. Exception breakpoints show 'Exception: {class} [caught, uncaught, enabled]'.")
                onFailure("no breakpoints found", "Returns 'No breakpoints found' (or 'No breakpoints found in {file}' if filtered) — not an error, just an empty result.")
                onFailure("file filter specified but file not found on disk", "The fileUrl will simply not match any breakpoint — returns empty result without an explicit error.")
                example("list all breakpoints across the project") {
                    param("action", "list_breakpoints")
                    outcome("Returns all breakpoints: line breakpoints with conditions/traits, method breakpoints with entry/exit flags, exception breakpoints with caught/uncaught flags, field watchpoints with access/modification flags.")
                    notes("Crucial before calling remove_breakpoint — verifies the exact file:line of each breakpoint and whether it's a line type (removable by file:line) or an exception type (not removable by file:line).")
                }
                example("check breakpoints in one file") {
                    param("action", "list_breakpoints")
                    param("file", "src/main/kotlin/com/example/PaymentService.kt")
                    outcome("Returns only the line, method, and watchpoint breakpoints anchored in PaymentService.kt. Exception breakpoints are excluded from this view even if they would fire in this file.")
                }
                verdict {
                    keep("Essential for breakpoint awareness. The LLM has no other way to discover what breakpoints exist in the IDE before deciding whether to add or remove one.", VerdictSeverity.STRONG)
                }
            }

            action("attach_to_process") {
                description {
                    technical(
                        "Creates a RemoteConfiguration in RunManager with the given host/port, then " +
                        "launches it via ProgramRunnerUtil.executeConfiguration() with DefaultDebugExecutor. " +
                        "Suspends in a coroutine using suspendCancellableCoroutine, subscribing to both " +
                        "XDebuggerManagerListener.processStarted (success path) and " +
                        "ExecutionListener.processNotStarted (abort path). Times out at 30 s. On success, " +
                        "registers the session in AgentDebugController and returns the session ID."
                    )
                    plain(
                        "Connect the IntelliJ debugger to a JVM that's already running on a specific " +
                        "host and port — like attaching a remote control to a JVM running inside a " +
                        "Docker container, a Kubernetes pod, or a separate process on another machine. " +
                        "The target JVM must have been started with JDWP enabled."
                    )
                }
                whenLLMUses(
                    "When the target JVM cannot be launched by the IDE (it's inside a container, on a " +
                    "remote host, or started by a custom bootstrap script) and the LLM needs to attach " +
                    "the debugger to an already-running process."
                )
                params {
                    required("port", "integer") {
                        llmSeesIt("Debug port to connect to (e.g., 5005) — for attach_to_process")
                        humanReadable("The JDWP port the target JVM is listening on. Standard port is 5005 for most Spring/JVM debug configs.")
                        whenPresent("Used as RemoteConfiguration.PORT (stored as string internally).")
                        constraint("must be between 1 and 65535")
                        example("5005")
                        example("5050")
                    }
                    optional("host", "string") {
                        llmSeesIt("Host to connect to (default: localhost) — for attach_to_process")
                        humanReadable("The hostname or IP of the machine running the target JVM. For local Docker containers 'localhost' is usually correct.")
                        whenPresent("Used as RemoteConfiguration.HOST.")
                        whenAbsent("Defaults to 'localhost'.")
                        example("localhost")
                        example("192.168.1.100")
                    }
                    optional("name", "string") {
                        llmSeesIt("Display name for the debug configuration — for attach_to_process")
                        humanReadable("Label shown in IntelliJ's Debug tool window for this session.")
                        whenPresent("Used as the run configuration name.")
                        whenAbsent("Defaults to '[Agent] Remote Debug {host}:{port}'.")
                        example("[Agent] Remote Debug localhost:5005")
                    }
                }
                rejectsParam("file", "attach_to_process does not need a file — it connects to a process by network address.")
                rejectsParam("line", "Not relevant to network-level attachment.")
                rejectsParam("class_name", "Not used by attach_to_process.")
                precondition("target JVM must be running and listening on JDWP with socket transport enabled (not shared memory)")
                precondition("target JVM must have been started with: -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:{port}")
                onSuccess("Returns 'Attached to remote JVM: {host}:{port}\\nSession: {sessionId}\\nConfiguration: {name}\\nStatus: connected'. The sessionId can be passed to debug_step and debug_inspect for subsequent operations.")
                onFailure("JVM not listening on port", "Times out after 30 s; returns 'Failed to attach to {host}:{port} within 30 seconds. Verify the target JVM is running with JDWP agent enabled on port {port}.'")
                onFailure("port out of range", "Validated before launch; returns 'Port must be between 1 and 65535, got {port}'.")
                onFailure("IntelliJ rejects the run config", "processNotStarted callback fires; cont.resume(\"\") causes the 30 s window to return an empty sessionId, which the tool treats as failure.")
                example("attach to Spring Boot in Docker") {
                    param("action", "attach_to_process")
                    param("host", "localhost")
                    param("port", "5005")
                    param("name", "[Agent] Spring Boot Debug")
                    outcome("Debugger attached to JVM at localhost:5005. Returns session ID that can be used with debug_step and debug_inspect. Breakpoints set before attaching will activate once the session is connected.")
                    notes("The Docker container must expose port 5005 and the JVM must be started with -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005")
                }
                verdict {
                    keep("No alternative path exists to programmatically attach the debugger to a remote JVM. This is the only way to debug containerised or externally-launched processes without asking the user to manually use Run > Attach to Process.", VerdictSeverity.STRONG)
                }
            }
        }

        verdict {
            keep(
                "7 distinct breakpoint management operations consolidated into one tool. The action-enum " +
                "pattern keeps schema cost flat regardless of how many breakpoint types are supported. " +
                "All 4 IntelliJ breakpoint types are covered (line, method, exception, field watchpoint) " +
                "plus remote attach. The conditional registration on hasJavaPlugin means the tool only " +
                "appears in Java/Kotlin projects, preventing schema waste in Python-only IDEs.",
                VerdictSeverity.STRONG
            )
        }

        observation(
            "The KDoc comment on the class says '8 actions covering breakpoint CRUD and session lifecycle " +
            "initiation' but there are only 7 actions. The start_session action was removed — its role is " +
            "now runtime_exec(action=run_config, mode=debug). The KDoc is stale and should be updated."
        )
        observation(
            "remove_breakpoint cannot remove exception breakpoints or non-line-anchored breakpoints. " +
            "A future 'remove_breakpoint(id=...)' action reading IDs from list_breakpoints would close this gap."
        )
        mergeOpportunity(
            "field_watchpoint could be folded into add_breakpoint with a 'type' enum param " +
            "(line/method/exception/watchpoint). This would reduce 7 actions to 4 but make add_breakpoint's " +
            "param list significantly longer. Not recommended — the current split makes per-action param " +
            "requirements clear without requiring rejected-param documentation on every call."
        )

        related("debug_step", Relationship.COMPLEMENT, "Navigate a paused debug session after breakpoints fire — debug_step drives stepping, resume, and state inspection.")
        related("debug_inspect", Relationship.COMPLEMENT, "Inspect variables, evaluate expressions, and examine stack frames after a breakpoint pauses execution.")
        related("runtime_exec", Relationship.COMPOSE_WITH, "Use runtime_exec(action=run_config, mode=debug) to launch a run configuration in debug mode — this is the canonical way to start a debug session (replaces the removed start_session action).")

        downside(
            "remove_breakpoint only works on XLineBreakpoints (file:line). Exception breakpoints and " +
            "non-file-anchored method breakpoints cannot be removed programmatically — the user must " +
            "clear them via the IDE's Breakpoints dialog."
        )
        downside(
            "exception_breakpoint does not validate the exception class against the classpath. A typo " +
            "(e.g. 'NullPointerException' instead of 'java.lang.NullPointerException') creates a silent " +
            "non-firing breakpoint with no error feedback."
        )
        downside(
            "method_breakpoint carries a 5-10× JVM performance overhead because IntelliJ implements it " +
            "via method entry/exit bytecode events. Long-running apps under method breakpoints can become " +
            "unusably slow. The tool always appends a performance warning but the LLM may proceed anyway."
        )
        downside(
            "attach_to_process has a hard 30 s timeout with no configurable override. Slow Docker starts " +
            "or high-latency network targets may need manual retry after the JVM is confirmed ready."
        )
        downside(
            "pass_count is applied via JavaBreakpointProperties reflection and is silently ignored when " +
            "the Java debugger API is unavailable. The LLM has no way to confirm it was applied."
        )
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
            "add_breakpoint" -> executeAddBreakpoint(params, project)
            "method_breakpoint" -> executeMethodBreakpoint(params, project)
            "exception_breakpoint" -> executeExceptionBreakpoint(params, project)
            "field_watchpoint" -> executeFieldWatchpoint(params, project)
            "remove_breakpoint" -> executeRemoveBreakpoint(params, project)
            "list_breakpoints" -> executeListBreakpoints(params, project)
            "attach_to_process" -> executeAttachToProcess(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions: add_breakpoint, method_breakpoint, exception_breakpoint, field_watchpoint, remove_breakpoint, list_breakpoints, attach_to_process",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    // ── add_breakpoint ──────────────────────────────────────────────────────

    private suspend fun executeAddBreakpoint(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("file")
        val line = params["line"]?.jsonPrimitive?.intOrNull ?: return ToolValidation.missingParam("line")
        val condition = params["condition"]?.jsonPrimitive?.content
        val logExpression = params["log_expression"]?.jsonPrimitive?.content
        val temporary = params["temporary"]?.jsonPrimitive?.booleanOrNull ?: false
        val suspendPolicyStr = params["suspend_policy"]?.jsonPrimitive?.content
        val passCount = params["pass_count"]?.jsonPrimitive?.intOrNull

        if (line < 1) {
            return ToolResult("Line number must be >= 1, got: $line", "Invalid line", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val (absolutePath, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
        if (pathError != null) return pathError

        // C7: pre-flight check — resolve file and verify the target line is actually
        // breakpointable before entering the write action. Without this, a breakpoint on
        // a comment/blank/import line is silently created as a disabled "unresolvable"
        // entry — the gutter stays empty and the user can't tell why it never hits.
        val preCheckFile = LocalFileSystem.getInstance().findFileByPath(absolutePath!!)
        if (preCheckFile != null) {
            val zeroBasedPreCheck = line - 1
            // KotlinLineBreakpointType.canPutAt → PsiManager.findFile, which requires a read action.
            val breakpointable = readAction {
                XDebuggerUtil.getInstance().canPutBreakpointAt(project, preCheckFile, zeroBasedPreCheck)
            }
            if (!breakpointable) {
                return ToolResult(
                    "Line $line in ${preCheckFile.name} is not breakpointable " +
                        "(comment, blank line, or outside executable code). " +
                        "Pick a line with a statement or expression.",
                    "Line not breakpointable",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true,
                )
            }
        }

        return try {
            withContext(Dispatchers.EDT) {
                WriteAction.compute<ToolResult, Exception> {
                    val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath!!)
                        ?: return@compute ToolResult(
                            "File not found: $absolutePath",
                            "File not found",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )

                    val bpManager = XDebuggerManager.getInstance(project).breakpointManager
                    val bpType = resolveBreakpointType(vFile.name)
                    val zeroBasedLine = line - 1
                    val bp: XLineBreakpoint<*> = addLineBreakpointSafe(
                        bpManager as com.intellij.xdebugger.impl.breakpoints.XBreakpointManagerImpl,
                        bpType, vFile.url, zeroBasedLine, vFile, temporary
                    ) ?: return@compute ToolResult(
                        "Failed to add breakpoint at ${vFile.name}:$line — line may not be breakpointable",
                        "Add failed",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )

                    if (condition != null) {
                        bp.conditionExpression = XExpressionImpl.fromText(condition)
                    }
                    if (logExpression != null) {
                        bp.logExpressionObject = XExpressionImpl.fromText(logExpression)
                        bp.suspendPolicy = SuspendPolicy.NONE
                    }

                    // Apply suspend policy (explicit param overrides log_expression default)
                    if (suspendPolicyStr != null) {
                        bp.suspendPolicy = when (suspendPolicyStr.lowercase()) {
                            "thread" -> SuspendPolicy.THREAD
                            "none" -> SuspendPolicy.NONE
                            else -> SuspendPolicy.ALL
                        }
                    }

                    // Apply pass count via Java-specific breakpoint properties.
                    // XBreakpoint doesn't expose pass count; it's on JavaBreakpointProperties
                    // (COUNT_FILTER / COUNT_FILTER_ENABLED fields).
                    if (passCount != null && passCount > 1) {
                        try {
                            val javaDebugger = com.intellij.debugger.DebuggerManagerEx.getInstanceEx(project)
                            val javaBp = javaDebugger.breakpointManager.breakpoints
                                .filterIsInstance<com.intellij.debugger.ui.breakpoints.Breakpoint<*>>()
                                .find { jBp -> jBp.xBreakpoint === bp }
                            if (javaBp != null) {
                                // Use reflection for API compatibility across IDE versions
                                try {
                                    javaBp.javaClass.getMethod("setCountFilterEnabled", Boolean::class.javaPrimitiveType).invoke(javaBp, true)
                                    javaBp.javaClass.getMethod("setCountFilter", Int::class.javaPrimitiveType).invoke(javaBp, passCount)
                                } catch (_: Exception) { /* API not available */ }
                            }
                        } catch (_: Exception) {
                            // Java-specific API not available (non-Java debugger); ignore silently
                        }
                    }

                    controller.trackBreakpoint(bp)

                    val fileName = vFile.name
                    val sb = StringBuilder("Breakpoint added at $fileName:$line")
                    if (condition != null) sb.append("\n  Condition: $condition")
                    if (logExpression != null) sb.append("\n  Log expression: $logExpression")
                    if (passCount != null) sb.append("\n  Pass count: every ${passCount}th hit")
                    val traits = mutableListOf<String>()
                    if (condition != null) traits.add("conditional")
                    if (logExpression != null) traits.add("log")
                    if (temporary) traits.add("temporary")
                    if (passCount != null) traits.add("pass_count=$passCount")
                    val suspendType = when (bp.suspendPolicy) {
                        SuspendPolicy.NONE -> "non-suspend"
                        SuspendPolicy.THREAD -> "suspend-thread"
                        else -> "suspend-all"
                    }
                    traits.add(suspendType)
                    sb.append("\n  Type: ${traits.joinToString(", ")}")

                    val content = sb.toString()
                    ToolResult(content, "Breakpoint at $fileName:$line", TokenEstimator.estimate(content))
                }
            }
        } catch (e: BreakpointNotAllowedException) {
            // Thrown from addLineBreakpointSafe when the platform rejects the line.
            // Caught before the generic handler so the LLM gets a structured message.
            ToolResult(
                e.message ?: "Line not breakpointable",
                "Line not breakpointable",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )
        } catch (e: Exception) {
            ToolResult("Error adding breakpoint: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── method_breakpoint ───────────────────────────────────────────────────

    private suspend fun executeMethodBreakpoint(params: JsonObject, project: Project): ToolResult {
        val className = params["class_name"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("class_name")
        val methodName = params["method_name"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("method_name")
        val watchEntry = params["watch_entry"]?.jsonPrimitive?.booleanOrNull ?: true
        val watchExit = params["watch_exit"]?.jsonPrimitive?.booleanOrNull ?: false

        if (!watchEntry && !watchExit) {
            return ToolResult(
                "Both watch_entry and watch_exit are false — the breakpoint would never trigger. Set at least one to true.",
                "Invalid config",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            val psiResult = smartReadAction(project) {
                val facade = JavaPsiFacade.getInstance(project)
                val psiClass = facade.findClass(className, GlobalSearchScope.allScope(project))
                    ?: return@smartReadAction PsiLookupResult.ClassNotFound

                val methods = psiClass.methods.filter { it.name == methodName }
                if (methods.isEmpty()) {
                    val availableMethods = psiClass.methods.map { it.name }.distinct().sorted()
                    return@smartReadAction PsiLookupResult.MethodNotFound(availableMethods)
                }

                val targetMethod = methods.first()
                val containingFile = targetMethod.containingFile?.virtualFile
                val lineNumber = if (containingFile != null) {
                    val document = PsiDocumentManager.getInstance(project)
                        .getDocument(targetMethod.containingFile)
                    document?.getLineNumber(targetMethod.textOffset) ?: -1
                } else {
                    -1
                }

                PsiLookupResult.Found(
                    fileUrl = containingFile?.url,
                    lineNumber = lineNumber,
                    isOverloaded = methods.size > 1,
                    overloadCount = methods.size
                )
            }

            when (psiResult) {
                is PsiLookupResult.ClassNotFound -> ToolResult(
                    "Class not found: $className. Verify the fully qualified class name is correct.",
                    "Class not found",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
                is PsiLookupResult.MethodNotFound -> {
                    val methodList = if (psiResult.availableMethods.isEmpty()) {
                        "No methods found in class."
                    } else {
                        "Available methods: ${psiResult.availableMethods.joinToString(", ")}"
                    }
                    ToolResult(
                        "Method '$methodName' not found in $className. $methodList",
                        "Method not found",
                        TokenEstimator.estimate(methodList),
                        isError = true
                    )
                }
                is PsiLookupResult.Found -> {
                    withContext(Dispatchers.EDT) {
                        WriteAction.compute<ToolResult, Exception> {
                            val bpManager = XDebuggerManager.getInstance(project).breakpointManager
                            val bpType = XDebuggerUtil.getInstance()
                                .findBreakpointType(JavaMethodBreakpointType::class.java)

                            val props = JavaMethodBreakpointProperties()
                            props.myClassPattern = className
                            props.myMethodName = methodName
                            props.WATCH_ENTRY = watchEntry
                            props.WATCH_EXIT = watchExit

                            val fileUrl = psiResult.fileUrl ?: ""
                            val lineNumber = if (psiResult.lineNumber >= 0) psiResult.lineNumber else 0

                            val bp = bpManager.addLineBreakpoint(
                                bpType, fileUrl, lineNumber, props, false
                            ) ?: return@compute ToolResult(
                                "Failed to add method breakpoint on $className.$methodName — breakpoint manager rejected it.",
                                "Add failed",
                                ToolResult.ERROR_TOKEN_ESTIMATE,
                                isError = true
                            )

                            controller.trackBreakpoint(bp)

                            val sb = StringBuilder()
                            sb.appendLine("Method breakpoint set on $className.$methodName")
                            sb.appendLine("  Watch entry: $watchEntry")
                            sb.appendLine("  Watch exit: $watchExit")
                            if (psiResult.isOverloaded) {
                                sb.appendLine("  NOTE: Method is overloaded (${psiResult.overloadCount} variants). Breakpoint set on first match.")
                            }
                            sb.appendLine("  PERFORMANCE WARNING: Method breakpoints are 5-10x slower than line breakpoints. Use sparingly.")

                            val content = sb.toString().trimEnd()
                            ToolResult(content, "Method breakpoint on $className.$methodName", TokenEstimator.estimate(content))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ToolResult("Error setting method breakpoint: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── exception_breakpoint ────────────────────────────────────────────────

    private suspend fun executeExceptionBreakpoint(params: JsonObject, project: Project): ToolResult {
        val exceptionClass = params["exception_class"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("exception_class")
        val caught = params["caught"]?.jsonPrimitive?.booleanOrNull ?: true
        val uncaught = params["uncaught"]?.jsonPrimitive?.booleanOrNull ?: true
        val condition = params["condition"]?.jsonPrimitive?.content

        if (exceptionClass.isBlank()) {
            return ToolResult("exception_class cannot be blank", "Invalid param", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        return try {
            withContext(Dispatchers.EDT) {
                WriteAction.compute<ToolResult, Exception> {
                    val bpManager = XDebuggerManager.getInstance(project).breakpointManager
                    val bpType = XDebuggerUtil.getInstance()
                        .findBreakpointType(JavaExceptionBreakpointType::class.java)
                        ?: return@compute ToolResult(
                            "Java exception breakpoint type not available — Java debugger plugin may not be installed",
                            "Type not found",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )

                    val props = JavaExceptionBreakpointProperties(exceptionClass)
                    props.NOTIFY_CAUGHT = caught
                    props.NOTIFY_UNCAUGHT = uncaught

                    val bp = bpManager.addBreakpoint(bpType, props)
                        ?: return@compute ToolResult(
                            "Failed to create exception breakpoint for $exceptionClass",
                            "Creation failed",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )

                    if (condition != null) {
                        bp.conditionExpression = XExpressionImpl.fromText(condition)
                    }

                    controller.trackGeneralBreakpoint(bp)

                    val simpleName = exceptionClass.substringAfterLast('.')
                    val sb = StringBuilder("Exception breakpoint set for $exceptionClass")
                    sb.append("\n  Caught: $caught")
                    sb.append("\n  Uncaught: $uncaught")
                    if (condition != null) sb.append("\n  Condition: $condition")
                    sb.append("\n  Note: No validation that '$exceptionClass' exists in the classpath — verify the class name is correct")

                    val content = sb.toString()
                    ToolResult(content, "Exception breakpoint on $simpleName", TokenEstimator.estimate(content))
                }
            }
        } catch (e: Exception) {
            ToolResult("Error setting exception breakpoint: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── field_watchpoint ────────────────────────────────────────────────────

    private suspend fun executeFieldWatchpoint(params: JsonObject, project: Project): ToolResult {
        val className = params["class_name"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("class_name")
        val fieldName = params["field_name"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("field_name")
        val filePath = params["file"]?.jsonPrimitive?.content
        val watchRead = params["watch_read"]?.jsonPrimitive?.booleanOrNull ?: false
        val watchWrite = params["watch_write"]?.jsonPrimitive?.booleanOrNull ?: true

        if (!watchRead && !watchWrite) {
            return ToolResult(
                "Warning: both watch_read and watch_write are false — watchpoint will never trigger. Set at least one to true.",
                "Will never trigger",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            val fieldInfo = smartReadAction(project) {
                findFieldInClass(project, className, fieldName, filePath)
            }

            if (fieldInfo == null) {
                return ToolResult(
                    "Could not find field '$fieldName' in class '$className'. " +
                        "Ensure the class exists in the project scope and the field name is correct. " +
                        "For Kotlin properties, use the property name (backing field name is usually the same).",
                    "Field not found",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            withContext(Dispatchers.EDT) {
                WriteAction.compute<ToolResult, Exception> {
                    val bpManager = XDebuggerManager.getInstance(project).breakpointManager
                    val bpType = XDebuggerUtil.getInstance()
                        .findBreakpointType(JavaFieldBreakpointType::class.java)

                    val bp = bpManager.addLineBreakpoint(
                        bpType, fieldInfo.fileUrl, fieldInfo.lineNumber,
                        JavaFieldBreakpointProperties(fieldName, className), false
                    ) ?: return@compute ToolResult(
                        "Failed to add field watchpoint for $className.$fieldName at line ${fieldInfo.lineNumber + 1}",
                        "Add failed",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )

                    val props = bp.properties
                    if (props is JavaFieldBreakpointProperties) {
                        props.WATCH_ACCESS = watchRead
                        props.WATCH_MODIFICATION = watchWrite
                    }

                    controller.trackBreakpoint(bp)

                    val watchTypes = mutableListOf<String>()
                    if (watchRead) watchTypes.add("read")
                    if (watchWrite) watchTypes.add("write")
                    val watchDesc = watchTypes.joinToString(" + ")
                    val displayLine = fieldInfo.lineNumber + 1

                    val sb = StringBuilder("Field watchpoint set on $className.$fieldName")
                    sb.append("\n  File: ${fieldInfo.fileName}:$displayLine")
                    sb.append("\n  Watching: $watchDesc")

                    val content = sb.toString()
                    ToolResult(content, "Watchpoint on $className.$fieldName ($watchDesc)", TokenEstimator.estimate(content))
                }
            }
        } catch (e: Exception) {
            ToolResult("Error setting field watchpoint: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── remove_breakpoint ───────────────────────────────────────────────────

    private suspend fun executeRemoveBreakpoint(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("file")
        val line = params["line"]?.jsonPrimitive?.intOrNull ?: return ToolValidation.missingParam("line")

        if (line < 1) {
            return ToolResult("Line number must be >= 1, got: $line", "Invalid line", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val (absolutePath, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
        if (pathError != null) return pathError

        return try {
            withContext(Dispatchers.EDT) {
                WriteAction.compute<ToolResult, Exception> {
                    val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath!!)
                        ?: return@compute ToolResult(
                            "File not found: $absolutePath",
                            "File not found",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )

                    val bpManager = XDebuggerManager.getInstance(project).breakpointManager
                    val fileUrl = vFile.url
                    val zeroBasedLine = line - 1

                    val matchingBp = bpManager.allBreakpoints
                        .filterIsInstance<XLineBreakpoint<*>>()
                        .find { bp -> bp.fileUrl == fileUrl && bp.line == zeroBasedLine }

                    if (matchingBp == null) {
                        return@compute ToolResult(
                            "No breakpoint found at ${vFile.name}:$line",
                            "Not found",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )
                    }

                    bpManager.removeBreakpoint(matchingBp)
                    ToolResult(
                        "Breakpoint removed from ${vFile.name}:$line",
                        "Removed ${vFile.name}:$line",
                        ToolResult.ERROR_TOKEN_ESTIMATE
                    )
                }
            }
        } catch (e: Exception) {
            ToolResult("Error removing breakpoint: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── list_breakpoints ────────────────────────────────────────────────────

    private suspend fun executeListBreakpoints(params: JsonObject, project: Project): ToolResult {
        val filterFile = params["file"]?.jsonPrimitive?.content

        return try {
            val bpManager = XDebuggerManager.getInstance(project).breakpointManager

            val filterFileUrl = if (filterFile != null) {
                val absolutePath = if (File(filterFile).isAbsolute) {
                    filterFile
                } else {
                    val basePath = project.basePath
                        ?: return ToolResult("Cannot resolve relative path: project basePath is null", "Path error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
                    File(basePath, filterFile).canonicalPath
                }
                LocalFileSystem.getInstance().findFileByPath(absolutePath)?.url
            } else {
                null
            }

            val allBreakpoints = bpManager.allBreakpoints.toList()

            // Separate breakpoints by type for type-specific formatting
            val lineBreakpoints = mutableListOf<XLineBreakpoint<*>>()
            val exceptionBreakpoints = mutableListOf<com.intellij.xdebugger.breakpoints.XBreakpoint<*>>()
            val otherBreakpoints = mutableListOf<com.intellij.xdebugger.breakpoints.XBreakpoint<*>>()

            for (bp in allBreakpoints) {
                when {
                    bp is XLineBreakpoint<*> -> {
                        // Line breakpoints include method breakpoints and field watchpoints
                        // (both created via addLineBreakpoint)
                        if (filterFileUrl == null || bp.fileUrl == filterFileUrl) {
                            lineBreakpoints.add(bp)
                        }
                    }
                    isJavaExceptionBreakpoint(bp) -> {
                        // Exception breakpoints have no file association — skip file filter
                        if (filterFileUrl == null) {
                            exceptionBreakpoints.add(bp)
                        }
                    }
                    else -> {
                        // Any other breakpoint type not yet categorized
                        if (filterFileUrl == null) {
                            otherBreakpoints.add(bp)
                        }
                    }
                }
            }

            val totalCount = lineBreakpoints.size + exceptionBreakpoints.size + otherBreakpoints.size
            if (totalCount == 0) {
                val qualifier = if (filterFile != null) " in $filterFile" else ""
                return ToolResult("No breakpoints found$qualifier.", "No breakpoints", ToolResult.ERROR_TOKEN_ESTIMATE)
            }

            val sb = StringBuilder()
            sb.appendLine("Breakpoints ($totalCount):")
            sb.appendLine()

            // Format line breakpoints (includes method breakpoints and field watchpoints)
            for (bp in lineBreakpoints) {
                val fileName = bp.fileUrl.substringAfterLast('/')
                val oneBased = bp.line + 1

                val props = bp.properties
                val javaFormatted = formatJavaBreakpointProperties(bp, props)
                when {
                    javaFormatted != null -> sb.appendLine(javaFormatted)
                    else -> {
                        // Standard line breakpoint
                        val traits = mutableListOf<String>()
                        traits.add(if (bp.isEnabled) "enabled" else "disabled")
                        val bpCondition = bp.conditionExpression?.expression
                        if (!bpCondition.isNullOrBlank()) traits.add("conditional: $bpCondition")
                        val logExpr = bp.logExpressionObject?.expression
                        if (!logExpr.isNullOrBlank()) traits.add("log: $logExpr")
                        if (bp.isTemporary) traits.add("temporary")
                        if (bp.suspendPolicy == SuspendPolicy.NONE) traits.add("non-suspend")
                        sb.appendLine("$fileName:$oneBased [${traits.joinToString(", ")}]")
                    }
                }
            }

            // Format exception breakpoints
            for (bp in exceptionBreakpoints) {
                val props = bp.properties
                val traits = mutableListOf<String>()
                val exceptionClass: String
                if (props is JavaExceptionBreakpointProperties) {
                    exceptionClass = props.myQualifiedName?.ifBlank { "Any Exception" } ?: "Any Exception"
                    if (props.NOTIFY_CAUGHT) traits.add("caught")
                    if (props.NOTIFY_UNCAUGHT) traits.add("uncaught")
                } else {
                    exceptionClass = bp.type.id
                }
                traits.add(if (bp.isEnabled) "enabled" else "disabled")
                val bpCondition = bp.conditionExpression?.expression
                if (!bpCondition.isNullOrBlank()) traits.add("conditional: $bpCondition")
                sb.appendLine("Exception: $exceptionClass [${traits.joinToString(", ")}]")
            }

            // Format any other breakpoint types generically
            for (bp in otherBreakpoints) {
                val traits = mutableListOf<String>()
                traits.add(if (bp.isEnabled) "enabled" else "disabled")
                val bpCondition = bp.conditionExpression?.expression
                if (!bpCondition.isNullOrBlank()) traits.add("conditional: $bpCondition")
                sb.appendLine("${bp.type.title ?: bp.type.id}: [${traits.joinToString(", ")}]")
            }

            val content = sb.toString().trimEnd()
            ToolResult(content, "$totalCount breakpoints", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error listing breakpoints: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── attach_to_process ───────────────────────────────────────────────────

    private suspend fun executeAttachToProcess(params: JsonObject, project: Project): ToolResult {
        val host = params["host"]?.jsonPrimitive?.content ?: "localhost"
        val port = params["port"]?.jsonPrimitive?.intOrNull ?: return ToolValidation.missingParam("port")
        val displayName = params["name"]?.jsonPrimitive?.content ?: "[Agent] Remote Debug $host:$port"

        if (port < 1 || port > 65535) {
            return ToolResult("Port must be between 1 and 65535, got $port", "Invalid port", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        return try {
            val sessionId = withContext(Dispatchers.EDT) {
                val runManager = RunManager.getInstance(project)
                val remoteConfigType = RemoteConfigurationType.getInstance()
                val settings = runManager.createConfiguration(displayName, remoteConfigType.factory)
                val remoteConfig = settings.configuration as RemoteConfiguration
                remoteConfig.HOST = host
                remoteConfig.PORT = port.toString()
                remoteConfig.SERVER_MODE = false
                remoteConfig.USE_SOCKET_TRANSPORT = true

                val executor = DefaultDebugExecutor.getDebugExecutorInstance()
                val env = ExecutionEnvironmentBuilder.create(project, executor, remoteConfig).build()

                withTimeoutOrNull(30_000L) {
                    suspendCancellableCoroutine<String> { cont ->
                        val connection = project.messageBus.connect()
                        val buildConn = project.messageBus.connect()
                        cont.invokeOnCancellation { connection.disconnect(); buildConn.disconnect() }
                        connection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
                            override fun processStarted(debugProcess: XDebugProcess) {
                                val session = debugProcess.session
                                val id = controller.registerSession(session)
                                connection.disconnect()
                                cont.resume(id)
                            }
                        })

                        ProgramRunnerUtil.executeConfiguration(env, true, true)

                        // Detect execution abort before debug attaches
                        buildConn.subscribe(com.intellij.execution.ExecutionManager.EXECUTION_TOPIC,
                            object : com.intellij.execution.ExecutionListener {
                                override fun processNotStarted(executorId: String, e: com.intellij.execution.runners.ExecutionEnvironment) {
                                    if (e == env) {
                                        buildConn.disconnect()
                                        connection.disconnect()
                                        if (cont.isActive) cont.resume("")
                                    }
                                }
                                override fun processStarted(executorId: String, e: com.intellij.execution.runners.ExecutionEnvironment, handler: com.intellij.execution.process.ProcessHandler) {
                                    if (e == env) buildConn.disconnect()
                                }
                            }
                        )
                    }
                }
            }

            if (sessionId == null) {
                return ToolResult(
                    "Failed to attach to $host:$port within 30 seconds. Verify the target JVM is running with JDWP agent enabled on port $port.",
                    "Attach timeout",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            val content = buildString {
                append("Attached to remote JVM: $host:$port\n")
                append("Session: $sessionId\n")
                append("Configuration: $displayName\n")
                append("Status: connected")
            }
            ToolResult(content, "Attached to $host:$port as $sessionId", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error attaching to process at $host:$port: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun resolveBreakpointType(fileName: String): com.intellij.xdebugger.breakpoints.XLineBreakpointType<*> {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return if (ext == "kt" || ext == "kts") {
            try {
                val kotlinType = Class.forName("org.jetbrains.kotlin.idea.debugger.breakpoints.KotlinLineBreakpointType")
                XDebuggerUtil.getInstance().findBreakpointType(
                    @Suppress("UNCHECKED_CAST")
                    (kotlinType as Class<out com.intellij.xdebugger.breakpoints.XLineBreakpointType<*>>)
                )
            } catch (_: ClassNotFoundException) {
                XDebuggerUtil.getInstance().findBreakpointType(
                    com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType::class.java
                )
            }
        } else {
            XDebuggerUtil.getInstance().findBreakpointType(
                com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType::class.java
            )
        }
    }

    private fun addLineBreakpointSafe(
        bpManager: com.intellij.xdebugger.impl.breakpoints.XBreakpointManagerImpl,
        bpType: com.intellij.xdebugger.breakpoints.XLineBreakpointType<*>,
        fileUrl: String,
        line: Int,
        vFile: com.intellij.openapi.vfs.VirtualFile,
        temporary: Boolean
    ): XLineBreakpoint<*>? {
        return addTyped<com.intellij.xdebugger.breakpoints.XBreakpointProperties<*>>(bpManager, bpType, fileUrl, line, vFile, temporary)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <P : com.intellij.xdebugger.breakpoints.XBreakpointProperties<*>> addTyped(
        bpManager: com.intellij.xdebugger.impl.breakpoints.XBreakpointManagerImpl,
        bpType: com.intellij.xdebugger.breakpoints.XLineBreakpointType<*>,
        fileUrl: String,
        line: Int,
        vFile: com.intellij.openapi.vfs.VirtualFile,
        temporary: Boolean
    ): XLineBreakpoint<P>? {
        val typed = bpType as com.intellij.xdebugger.breakpoints.XLineBreakpointType<P>
        val properties = typed.createBreakpointProperties(vFile, line)
        return bpManager.addLineBreakpoint(typed, fileUrl, line, properties, temporary)
    }

    private sealed class PsiLookupResult {
        data object ClassNotFound : PsiLookupResult()
        data class MethodNotFound(val availableMethods: List<String>) : PsiLookupResult()
        data class Found(
            val fileUrl: String?,
            val lineNumber: Int,
            val isOverloaded: Boolean,
            val overloadCount: Int
        ) : PsiLookupResult()
    }

    private data class FieldInfo(
        val fileUrl: String,
        val fileName: String,
        val lineNumber: Int
    )

    private fun findFieldInClass(
        project: Project,
        className: String,
        fieldName: String,
        filePath: String?
    ): FieldInfo? {
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(className, GlobalSearchScope.projectScope(project))

        if (psiClass != null) {
            val field = psiClass.fields.firstOrNull { it.name == fieldName } ?: return null
            val containingFile = field.containingFile ?: return null
            val virtualFile = containingFile.virtualFile ?: return null
            val document = PsiDocumentManager.getInstance(project).getDocument(containingFile) ?: return null
            val lineNumber = document.getLineNumber(field.textOffset)
            return FieldInfo(fileUrl = virtualFile.url, fileName = virtualFile.name, lineNumber = lineNumber)
        }

        if (filePath != null) {
            val (absolutePath, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
            if (pathError != null || absolutePath == null) return null
            val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return null
            val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return null
            val lineNumber = findFieldLineInDocument(document, fieldName)
            return FieldInfo(fileUrl = vFile.url, fileName = vFile.name, lineNumber = lineNumber)
        }

        return null
    }

    private fun findFieldLineInDocument(document: Document, fieldName: String): Int {
        val text = document.text
        val lines = text.lines()
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.contains(fieldName) &&
                (trimmed.contains("private ") || trimmed.contains("protected ") ||
                    trimmed.contains("public ") || trimmed.contains("val ") ||
                    trimmed.contains("var ") || trimmed.contains("static "))
            ) {
                return index
            }
        }
        return 0
    }

    /**
     * Safely checks if a breakpoint is a Java exception breakpoint.
     * Returns false if Java debugger classes aren't available (e.g., in PyCharm).
     */
    private fun isJavaExceptionBreakpoint(bp: com.intellij.xdebugger.breakpoints.XBreakpoint<*>): Boolean =
        try {
            bp.type is JavaExceptionBreakpointType
        } catch (_: NoClassDefFoundError) {
            false
        }

    /**
     * Formats Java-specific breakpoint properties (method breakpoints, field watchpoints).
     * Returns null if the properties aren't Java-specific or if Java debugger classes
     * aren't available (e.g., in PyCharm).
     */
    private fun formatJavaBreakpointProperties(
        bp: XLineBreakpoint<*>,
        props: Any?
    ): String? = try {
        when (props) {
            is JavaMethodBreakpointProperties -> {
                val className = props.myClassPattern ?: ""
                val methodName = props.myMethodName ?: ""
                val display = if (className.isNotBlank()) "$className.$methodName" else methodName
                val traits = mutableListOf<String>()
                if (props.WATCH_ENTRY) traits.add("entry")
                if (props.WATCH_EXIT) traits.add("exit")
                traits.add(if (bp.isEnabled) "enabled" else "disabled")
                val bpCondition = bp.conditionExpression?.expression
                if (!bpCondition.isNullOrBlank()) traits.add("conditional: $bpCondition")
                "Method: $display [${traits.joinToString(", ")}]"
            }
            is JavaFieldBreakpointProperties -> {
                val className = props.myClassName ?: ""
                val fieldName = props.myFieldName ?: ""
                val display = if (className.isNotBlank()) "$className.$fieldName" else fieldName
                val traits = mutableListOf<String>()
                if (props.WATCH_ACCESS) traits.add("access")
                if (props.WATCH_MODIFICATION) traits.add("modification")
                traits.add(if (bp.isEnabled) "enabled" else "disabled")
                val bpCondition = bp.conditionExpression?.expression
                if (!bpCondition.isNullOrBlank()) traits.add("conditional: $bpCondition")
                "Field: $display [${traits.joinToString(", ")}]"
            }
            else -> null
        }
    } catch (_: NoClassDefFoundError) {
        null
    }

    /**
     * Thrown when the LLM requests a breakpoint on a line that the IDE platform
     * cannot break on (comment, blank line, import, or other non-executable code).
     *
     * Propagates out of [addLineBreakpointSafe] through the WriteAction boundary
     * and is caught in [executeAddBreakpoint] before the generic Exception handler
     * so the user sees a structured, actionable error instead of the platform's
     * silent disabled-breakpoint behaviour.
     */
    private class BreakpointNotAllowedException(message: String) : RuntimeException(message)
}
