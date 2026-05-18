package com.workflow.orchestrator.agent.tools.debug

import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.ui.HotSwapUI
import com.intellij.debugger.ui.HotSwapStatusListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.agent.tools.integration.ToolValidation
import com.workflow.orchestrator.agent.tools.platform.DebugState
import com.workflow.orchestrator.agent.tools.platform.IdeStateProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.intellij.openapi.application.EDT
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Debug inspection and advanced operations — evaluate, variables, set value,
 * stack frames, thread dump, memory view, hotswap, force return, drop frame.
 *
 * 9 actions covering runtime inspection and advanced debugging.
 */
class DebugInspectTool(private val controller: AgentDebugController) : AgentTool {

    override val name = "debug_inspect"

    override val description = """
Debug inspection — evaluate expressions, inspect variables, and advanced operations.

IMPORTANT: Before calling any action below, run debug_step(action=get_state) first to
confirm the session is paused and (if multiple sessions are open) get the session_id.

State tags: [SUSPENDED] = session must be paused at a breakpoint or after a step.
            [ANY]       = works on a running or paused session.

Actions:
- evaluate(expression, session_id?) [SUSPENDED] → Evaluate Java/Kotlin expression in current frame
- get_stack_frames(session_id?, thread_name?, max_frames?) [SUSPENDED] → Get call stack
- get_variables(session_id?, variable_name?, max_depth?) [SUSPENDED] → Inspect local variables in current frame
- set_value(variable_name, new_value, session_id?) [SUSPENDED] → Assign a new value to a local variable at runtime. new_value must be a value-producing expression (42, "hello", null, true). Void method calls (e.g. obj.setX(y)) fail with "Incompatible types" — use evaluate for those instead.
- thread_dump(session_id?, max_frames?, include_stacks?, include_daemon?) [ANY] → Full thread dump. Per-thread frames require that thread to be suspended.
- memory_view(class_name, session_id?, max_instances?) [SUSPENDED, Java/Kotlin only, requires canGetInstanceInfo] → Count/inspect live instances
- hotswap(session_id?, compile_first?) [ANY, Java/Kotlin only] → Hot-reload changed classes
- force_return(session_id?, return_value?, return_type?) [SUSPENDED, Java/Kotlin only, requires canForceEarlyReturn] → Force method to return immediately
- drop_frame(session_id?, frame_index?) [SUSPENDED, Java/Kotlin only, requires canPopFrames] → Rewinds the program counter to the start of the parent frame. Local variables, fields, and any side effects already produced are NOT undone — only the instruction pointer moves back.

session_id defaults to the active/resolved session. If multiple sessions are open and none is uniquely paused, session_id is required.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "evaluate", "get_stack_frames", "get_variables", "set_value",
                    "thread_dump", "memory_view", "hotswap", "force_return", "drop_frame"
                )
            ),
            "session_id" to ParameterProperty(
                type = "string",
                description = "Debug session ID (optional — uses active session if omitted)"
            ),
            "expression" to ParameterProperty(
                type = "string",
                description = "Java/Kotlin expression to evaluate — for evaluate"
            ),
            "thread_name" to ParameterProperty(
                type = "string",
                description = "Thread name to get stack from — for get_stack_frames"
            ),
            "max_frames" to ParameterProperty(
                type = "integer",
                description = "Maximum stack frames to return (default 20, max 50) — for get_stack_frames, thread_dump"
            ),
            "max_depth" to ParameterProperty(
                type = "integer",
                description = "Maximum depth for variable expansion (default 2, max 4) — for get_variables"
            ),
            "variable_name" to ParameterProperty(
                type = "string",
                description = "Specific variable name to deep-inspect — for get_variables, set_value"
            ),
            "new_value" to ParameterProperty(
                type = "string",
                description = "Value expression to assign — must produce a result compatible with the variable's type. Primitives and boxed types: '42', 'true', '3.14'. Strings: '\"hello\"'. Null: 'null'. Void method calls like 'obj.setX(v)' are NOT supported here — use evaluate for those. — for set_value"
            ),
            "include_stacks" to ParameterProperty(
                type = "boolean",
                description = "Include stack traces per thread (default: true) — for thread_dump"
            ),
            "include_daemon" to ParameterProperty(
                type = "boolean",
                description = "Include daemon threads (default: false) — for thread_dump"
            ),
            "class_name" to ParameterProperty(
                type = "string",
                description = "Fully qualified class name — for memory_view"
            ),
            "max_instances" to ParameterProperty(
                type = "integer",
                description = "Max instances to list details for (0=count only). Default 0 — for memory_view"
            ),
            "compile_first" to ParameterProperty(
                type = "boolean",
                description = "Compile changed files before reloading (default: true) — for hotswap"
            ),
            "return_value" to ParameterProperty(
                type = "string",
                description = "Value to return: \"null\", \"42\", \"true\", etc. Omit for void — for force_return"
            ),
            "return_type" to ParameterProperty(
                type = "string",
                description = "Return type: void, null, int, long, boolean, string, double, float, char, byte, short, auto (default) — for force_return",
                enumValues = listOf("auto", "void", "null", "int", "long", "boolean", "string", "double", "float", "char", "byte", "short")
            ),
            "frame_index" to ParameterProperty(
                type = "integer",
                description = "Frame index to drop to (0=current, 1=caller). Default 0 — for drop_frame"
            ),
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override fun documentation(): ToolDocumentation = toolDoc("debug_inspect") {
        summary {
            technical(
                "Single-tool dispatcher for runtime inspection and advanced debugger operations: " +
                    "evaluate expressions, inspect and mutate variables, capture stack frames, " +
                    "dump all threads, count live heap instances, hot-swap changed classes, " +
                    "force-return from a method, and rewind execution via frame drop. " +
                    "All session resolution goes through IdeStateProbe so agent-started and " +
                    "user-started sessions are both visible."
            )
            plain(
                "The agent's debugger 'inspector and surgeon'. Once the debugger is paused, this " +
                    "tool lets the agent peer inside the running JVM (read variables, evaluate any " +
                    "expression, get a thread dump) and — when needed — operate on the live process " +
                    "(change a variable value, reload changed classes, force a method to return, or " +
                    "rewind the call stack). Think of it as the Variables, Evaluate Expression, " +
                    "Threads, and Hot Swap panels from IntelliJ's Debug UI, all callable by the LLM."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.IDE_MUTATION)
        counterfactual(
            "Without debug_inspect the LLM has no way to inspect live runtime state: it would have " +
                "to add `println` / logging statements, recompile, and re-run to observe variable " +
                "values — each cycle takes seconds and pollutes the codebase. For mutation actions " +
                "(set_value, hotswap, force_return, drop_frame) there is simply no text-based " +
                "equivalent; the LLM would have to ask the user to perform them manually in the IDE."
        )
        llmMistake(
            "Skips `debug_step(action=get_state)` and calls evaluate or get_variables on a running " +
                "session — gets NOT_SUSPENDED error, then has to call pause + get_state + the " +
                "inspect action (3 extra calls). The description's 'IMPORTANT: run get_state first' " +
                "nudge reduces this but doesn't eliminate it."
        )
        llmMistake(
            "Passes `session_id` to `get_state` (from debug_step) then forgets to pass the same " +
                "`session_id` to debug_inspect when there are multiple sessions — hits AMBIGUOUS_SESSION " +
                "error when the second session is also paused."
        )
        llmMistake(
            "Calls `memory_view` without checking the VM capability note — fails on remote VMs " +
                "(JMX/JDWP over network) that don't support `canGetInstanceInfo`. Should check the " +
                "error response and fall back to evaluate-based instance counting."
        )
        llmMistake(
            "Uses `drop_frame` expecting variable state to reset — variables retain their last-written " +
                "values. drop_frame rewinds the program counter; it does NOT undo side effects " +
                "or reset locals. The description says so explicitly but the name implies a full rewind."
        )
        llmMistake(
            "Expects `drop_frame` to undo side effects (println, log writes, HTTP calls, DB mutations) " +
                "produced by the frame. It only rewinds the program counter — the instruction pointer " +
                "moves back to the method entry but state stays mutated. Re-running the frame will " +
                "execute those side effects a second time, which can leave the application in an " +
                "inconsistent state."
        )
        llmMistake(
            "Passes a bare integer string to `evaluate` expecting it to call a method — e.g. " +
                "`evaluate(expression='myList.size')` works but `evaluate(expression='myList.size()')` " +
                "is needed for Java (size is a method, not a property). Kotlin-style property access " +
                "works in Kotlin frames; Java frames need the `()` suffix."
        )
        llmMistake(
            "Calls `hotswap` on a Python debug session — always fails with an explicit error. " +
                "Hotswap relies on JDWP `redefineClasses`, which is a Java/Kotlin-only protocol. " +
                "The tool detects PyDebugProcess via reflection and returns a clear message, but " +
                "the LLM sometimes retries anyway."
        )
        downside(
            "Most actions require [SUSPENDED] state; calling them on a running session returns an " +
                "error. The LLM must gate every inspect call on a prior debug_step(get_state) that " +
                "confirmed the session is paused."
        )
        downside(
            "`evaluate` is capped at 10 seconds — expressions that call blocking code (network I/O, " +
                "lock acquisition, infinite loops) will time out instead of returning. The LLM should " +
                "avoid evaluating side-effecting or blocking expressions."
        )
        downside(
            "`hotswap` only works for body changes — adding/removing methods, fields, or changing " +
                "class hierarchy causes the JDWP redefine to fail. Structural changes require a " +
                "full session restart."
        )
        downside(
            "`memory_view` requires `canGetInstanceInfo` — not available on remote VMs, some JVM " +
                "implementations, or when the VM is not HotSpot-compatible."
        )
        downside(
            "`get_variables` / `thread_dump` / `memory_view` auto-spill outputs >30K via " +
                "`spillOrFormat`. The LLM receives a preview and a file path; it must call " +
                "`read_file` to see the rest. `evaluate` output is always small (single value) " +
                "and never spills."
        )
        related("debug_step", Relationship.COMPLEMENT, "Always call debug_step(get_state) before any inspect action to confirm the session is paused. debug_step drives session lifecycle (step, resume, pause, stop); debug_inspect drives observation and mutation.")
        related("debug_breakpoints", Relationship.COMPLEMENT, "Use debug_breakpoints to set conditions for pausing; use debug_inspect to inspect state once paused. The two tools form the full breakpoint-inspect-mutate workflow.")
        flowchart("""
            flowchart TD
                A[Need to inspect paused session] --> B[debug_step get_state — confirm PAUSED]
                B --> C{What kind of inspection?}
                C -- read values --> D[get_variables / evaluate]
                C -- call tree --> E[get_stack_frames]
                C -- all threads --> F[thread_dump]
                C -- heap count --> G[memory_view]
                C -- test hypothesis --> H[set_value then evaluate]
                C -- reload code --> I[hotswap]
                C -- skip method --> J[force_return]
                C -- rerun method --> K[drop_frame]
                D --> L[debug_step step_over / resume]
                E --> L
                F --> L
                G --> L
                H --> L
                I --> L
                J --> L
                K --> L
        """)
        verdict {
            keep(
                "Provides the only programmatic path to JVM runtime state inspection and mutation. " +
                    "Without it, the LLM's entire debugging workflow degrades to println-and-recompile. " +
                    "All 9 actions map to distinct JVM/IntelliJ APIs with no reasonable textual " +
                    "equivalent. The 3 mutation actions (set_value, hotswap, force_return) are " +
                    "uniquely powerful: the LLM can test a hypothesis (change a value and step " +
                    "forward) without touching the source code.",
                VerdictSeverity.STRONG
            )
        }
        actions {
            // ── evaluate ──────────────────────────────────────────────────────────
            action("evaluate") {
                description {
                    technical(
                        "Evaluates a Java/Kotlin expression in the current stack frame using the " +
                            "IntelliJ debugger's expression evaluator. Wrapped in withTimeoutOrNull(10s) " +
                            "to prevent indefinite hangs on blocking or infinite-loop expressions. " +
                            "Returns the value and its runtime type. Output is bounded to a single " +
                            "value — no spill."
                    )
                    plain(
                        "Like IntelliJ's 'Evaluate Expression' dialog — type any expression and see " +
                            "its value without changing any code. Useful for 'what does this variable " +
                            "equal right now?' or 'what would this calculation return?'"
                    )
                }
                whenLLMUses(
                    "When the LLM wants to read a variable value, call a method on an object, or " +
                        "compute a sub-expression not already visible in the Variables pane — all without " +
                        "editing or recompiling the source."
                )
                params {
                    required("expression", "string") {
                        llmSeesIt("Java/Kotlin expression to evaluate — for evaluate")
                        humanReadable(
                            "Any valid Java or Kotlin expression. The language of the expression " +
                                "must match the language of the current frame. Kotlin property access " +
                                "(`myList.size`) works in Kotlin frames; Java frames need method " +
                                "syntax (`myList.size()`)."
                        )
                        whenPresent("The expression is evaluated in the context of the current stack frame and the result is returned.")
                        constraint("must not be blank")
                        constraint("evaluation is capped at 10 seconds — blocking or infinite-loop expressions will time out")
                        example("myService.getUser(userId)")
                        example("result.errorMessage")
                        example("items.stream().filter(i -> i.active).count()")
                    }
                    optional("session_id", "string") {
                        llmSeesIt("Debug session ID (optional — uses active session if omitted)")
                        humanReadable("Which debug session to evaluate in. Needed only when multiple sessions are paused simultaneously.")
                        whenPresent("That session's current frame is used for evaluation.")
                        whenAbsent("The active/resolved session is used.")
                    }
                }
                rejectsParam("thread_name", "Only get_stack_frames reads thread_name.")
                rejectsParam("max_frames", "Only get_stack_frames and thread_dump read max_frames.")
                rejectsParam("max_depth", "Only get_variables reads max_depth.")
                rejectsParam("variable_name", "evaluate uses expression, not variable_name.")
                rejectsParam("new_value", "Only set_value reads new_value.")
                rejectsParam("include_stacks", "Only thread_dump reads include_stacks.")
                rejectsParam("include_daemon", "Only thread_dump reads include_daemon.")
                rejectsParam("class_name", "Only memory_view reads class_name.")
                rejectsParam("max_instances", "Only memory_view reads max_instances.")
                rejectsParam("compile_first", "Only hotswap reads compile_first.")
                rejectsParam("return_value", "Only force_return reads return_value.")
                rejectsParam("return_type", "Only force_return reads return_type.")
                rejectsParam("frame_index", "Only drop_frame reads frame_index.")
                precondition("session must be PAUSED at a breakpoint or after a step (requireSuspendedSession)")
                onSuccess("Returns: expression text, result value string, runtime type. Output is always small — no spill.")
                onFailure("expression is blank", "Immediate error: 'Expression cannot be blank.'")
                onFailure("evaluation times out after 10s", "Error with message 'Expression evaluation timed out … may contain an infinite loop or be waiting for a lock.'")
                onFailure("expression has a syntax error or references an unknown symbol", "Error with the evaluator's error message (e.g., 'Cannot find symbol: myVar').")
                onFailure("session is running", "NOT_SUSPENDED error — call debug_step(get_state) first.")
                example("read a field value") {
                    param("action", "evaluate")
                    param("expression", "order.totalAmount")
                    outcome("Returns: Expression: order.totalAmount / Result: 150.00 / Type: java.math.BigDecimal")
                }
                example("call a method during pause") {
                    param("action", "evaluate")
                    param("expression", "userService.findById(userId).getUsername()")
                    outcome("Executes the method call in the current context and returns the String result.")
                    notes("Safe only if the method has no side effects — evaluate can modify state if the expression calls a mutating method.")
                }
                verdict {
                    keep(
                        "The most-used inspect action. Without it, the LLM can only read what " +
                            "get_variables exposes at depth=2. Evaluate unlocks arbitrary expression " +
                            "power: method calls, stream operations, chained property access.",
                        VerdictSeverity.STRONG
                    )
                }
            }

            // ── get_stack_frames ──────────────────────────────────────────────────
            action("get_stack_frames") {
                description {
                    technical(
                        "Returns the call stack for the current (or named) thread as an ordered list " +
                            "of frame records — index, method name, file name, line number. Defaults to " +
                            "20 frames; capped at 50."
                    )
                    plain(
                        "Like IntelliJ's 'Frames' pane in the Debug window — shows the chain of " +
                            "method calls that led to the current pause point. Useful for understanding " +
                            "how execution got here without manually walking up the call tree."
                    )
                }
                whenLLMUses(
                    "When the LLM needs to understand the call path that triggered the current pause — " +
                        "especially useful when the paused method is a utility called from many places " +
                        "and the LLM needs to know which caller to examine."
                )
                params {
                    optional("session_id", "string") {
                        llmSeesIt("Debug session ID (optional — uses active session if omitted)")
                        humanReadable("Which session's stack to retrieve.")
                        whenPresent("That session's frames are returned.")
                        whenAbsent("Active session's frames are returned.")
                    }
                    optional("thread_name", "string") {
                        llmSeesIt("Thread name to get stack from — for get_stack_frames")
                        humanReadable("Filter to a specific thread's stack. Most useful when multiple threads are suspended and you want one specifically.")
                        whenPresent("Stack frames for that thread are returned if it exists in the session; otherwise falls back to the default thread.")
                        whenAbsent("The active execution stack's thread name is used (from suspendContext.activeExecutionStack.displayName).")
                    }
                    optional("max_frames", "integer") {
                        llmSeesIt("Maximum stack frames to return (default 20, max 50) — for get_stack_frames, thread_dump")
                        humanReadable("How many frames deep to go. Default 20 covers most call chains; increase to 50 for deep recursive stacks.")
                        whenPresent("At most that many frames are returned.")
                        whenAbsent("Default 20 frames returned.")
                        constraint("clamped to [1, 50]")
                        example("50")
                    }
                }
                precondition("session must be PAUSED")
                onSuccess("Returns: thread name, frame count, and numbered list of frames each with index, method name, file name, and line number.")
                onFailure("no active stack frame", "Returns 'No stack frames available' (non-error result).")
                onFailure("session is running", "NOT_SUSPENDED error.")
                example("inspect call chain at pause") {
                    param("action", "get_stack_frames")
                    param("max_frames", "10")
                    outcome("Returns stack trace: main thread, 7 frames. Frame #0 is the current pause line; frame #1 is the caller, etc.")
                }
                verdict {
                    keep("Essential when 'how did I get here?' is the key question. Cannot be replaced by get_variables.", VerdictSeverity.STRONG)
                }
            }

            // ── get_variables ─────────────────────────────────────────────────────
            action("get_variables") {
                description {
                    technical(
                        "Retrieves local variables in the current stack frame, expanded recursively " +
                            "to `max_depth` (default 2, capped at 4). Optionally filters to a single " +
                            "named variable for deep inspection. Large outputs auto-spill to " +
                            "`{sessionDir}/tool-output/` via `spillOrFormat`."
                    )
                    plain(
                        "Like IntelliJ's 'Variables' pane — shows all local variables and their " +
                            "current values, including nested object fields up to a configurable depth. " +
                            "'What does this object look like right now?'"
                    )
                }
                whenLLMUses(
                    "Immediately after pausing or stepping, to see the full local-variable state of " +
                        "the current frame — especially for complex objects where evaluate would require " +
                        "knowing all the field names in advance."
                )
                params {
                    optional("session_id", "string") {
                        llmSeesIt("Debug session ID (optional — uses active session if omitted)")
                        humanReadable("Which session's variables to read.")
                        whenPresent("That session's current frame variables are returned.")
                        whenAbsent("Active session's current frame variables are returned.")
                    }
                    optional("variable_name", "string") {
                        llmSeesIt("Specific variable name to deep-inspect — for get_variables, set_value")
                        humanReadable("Name of a single variable to deep-inspect. When provided, max_depth is raised to at least 2 to give a meaningful expansion of that variable.")
                        whenPresent("Only that variable is returned, expanded to the effective depth. Error if not found — the error message lists all available variable names.")
                        whenAbsent("All variables in the current frame are returned.")
                        example("order")
                        example("serviceResult")
                    }
                    optional("max_depth", "integer") {
                        llmSeesIt("Maximum depth for variable expansion (default 2, max 4) — for get_variables")
                        humanReadable("How many levels of object nesting to expand. Depth 1 = immediate fields only; depth 4 = great-grandchild fields. Higher depths produce much larger output.")
                        whenPresent("Variable tree is expanded to that depth.")
                        whenAbsent("Default depth 2 is used.")
                        constraint("clamped to [1, 4]")
                        example("3")
                    }
                }
                rejectsParam("new_value", "set_value action reads new_value; get_variables is read-only.")
                precondition("session must be PAUSED")
                precondition("an active stack frame must exist (session.currentStackFrame must be non-null)")
                onSuccess("Returns: frame header (file:line), then formatted variable list with names, types, and values at the requested depth. Spills to disk if output exceeds 30K.")
                onFailure("no active stack frame", "Error: 'No active stack frame available.'")
                onFailure("variable_name not found in frame", "Error listing all available variable names so the LLM can correct its query.")
                onFailure("no variables in frame", "Non-error: 'No variables in the current frame.'")
                onFailure("session is running", "NOT_SUSPENDED error.")
                example("inspect all frame locals") {
                    param("action", "get_variables")
                    outcome("Returns all local variables in Frame #0 at depth 2. If an object has more fields than depth 2 reaches, shows '…' placeholders.")
                }
                example("deep-inspect one complex object") {
                    param("action", "get_variables")
                    param("variable_name", "requestContext")
                    param("max_depth", "4")
                    outcome("Returns only the `requestContext` variable, expanded 4 levels deep — useful when that object is the suspect and you need to see all its nested state.")
                }
                verdict {
                    keep(
                        "The primary way to see all local state at once. Complements evaluate (which is " +
                            "better for one specific calculation) and get_stack_frames (which shows location, " +
                            "not values).",
                        VerdictSeverity.STRONG
                    )
                }
            }

            // ── set_value ─────────────────────────────────────────────────────────
            action("set_value") {
                description {
                    technical(
                        "Modifies a local variable's value at runtime using `XValueModifier.setValue()` " +
                            "(EDT-dispatched, as required by the API), with a fallback to " +
                            "evaluate-with-assignment for variables that have no modifier. Verifies the " +
                            "change by reading back the variable after write."
                    )
                    plain(
                        "Like IntelliJ's 'Set Value' right-click in the Variables pane — changes a " +
                            "variable's value while the program is paused, without restarting or " +
                            "editing the source. Useful for testing a hypothesis: 'what happens if " +
                            "this flag is true?'"
                    )
                }
                whenLLMUses(
                    "When the LLM suspects a bug is in a specific variable's value and wants to verify " +
                        "by mutating it and continuing execution — a 'hypothesis test' without recompile. " +
                        "Also useful for forcing a known-good value to prove the bug is isolated to that variable."
                )
                params {
                    required("variable_name", "string") {
                        llmSeesIt("Specific variable name to deep-inspect — for get_variables, set_value")
                        humanReadable("The name of the local variable to change. Must exist in the current frame's variable list.")
                        whenPresent("That variable is located in the frame and its value is set to new_value.")
                        constraint("variable must exist in the current stack frame")
                        constraint("variable must not be final/val — attempting to set a final field returns an error")
                        example("isEnabled")
                        example("retryCount")
                    }
                    required("new_value", "string") {
                        llmSeesIt("Value expression to assign — must produce a result compatible with the variable's type. Primitives and boxed types: '42', 'true', '3.14'. Strings: '\"hello\"'. Null: 'null'. Void method calls like 'obj.setX(v)' are NOT supported here — use evaluate for those. — for set_value")
                        humanReadable("The new value as a string expression. Primitives as literals (42, true, 3.14); strings with quotes (\"hello\"); null. Must produce a value — void method calls like obj.setX(v) will fail with 'Incompatible types for = operation'. Use evaluate for calling setters.")
                        whenPresent("The variable is set to the result of evaluating this expression in the JVM context.")
                        constraint("expression must produce a non-void value assignable to the variable's declared type")
                        constraint("void method calls (setters, mutators) cause 'Incompatible types' — use evaluate action instead")
                        example("42")
                        example("\"admin\"")
                        example("null")
                        example("true")
                    }
                    optional("session_id", "string") {
                        llmSeesIt("Debug session ID (optional — uses active session if omitted)")
                        humanReadable("Which session's variable to set.")
                        whenPresent("That session's current frame variable is set.")
                        whenAbsent("Active session's current frame variable is set.")
                    }
                }
                precondition("session must be PAUSED")
                precondition("variable must exist in the current stack frame")
                onSuccess("Confirms the new value by reading it back. Returns: variable name, actual new value (post-write), type, and which method was used (XValueModifier direct or evaluate fallback).")
                onFailure("variable is final/val", "Error: 'Variable may not exist in the current frame, or may be final/val.'")
                onFailure("XValueModifier returns an error", "Error with the modifier's message (e.g., type mismatch).")
                onFailure("variable not in frame", "Error listing available variables.")
                onFailure("session is running", "NOT_SUSPENDED error.")
                example("force a flag to true to test a branch") {
                    param("action", "set_value")
                    param("variable_name", "featureEnabled")
                    param("new_value", "true")
                    outcome("Sets featureEnabled to true and confirms via read-back. The LLM can then resume and observe which branch executes.")
                }
                verdict {
                    keep(
                        "Uniquely valuable for hypothesis-testing without recompile. The LLM can run " +
                            "'what if this value was X' experiments at the cost of zero source edits.",
                        VerdictSeverity.STRONG
                    )
                }
            }

            // ── thread_dump ───────────────────────────────────────────────────────
            action("thread_dump") {
                description {
                    technical(
                        "Captures the state of all JVM threads via the JDWP VirtualMachine API " +
                            "(`vm.allThreads()`). For each thread: name, id, status (RUNNING/SLEEPING/" +
                            "BLOCKED/WAITING/NOT_STARTED/TERMINATED), daemon flag, suspended flag, " +
                            "and optionally per-thread stack frames. Daemon threads excluded by default. " +
                            "Large outputs auto-spill via `spillOrFormat`."
                    )
                    plain(
                        "Like IntelliJ's 'Threads' pane or Java's `jstack` command — shows every thread " +
                            "in the JVM and what it's doing. Useful for diagnosing deadlocks, thread leaks, " +
                            "and unexpected blocking."
                    )
                }
                whenLLMUses(
                    "When the LLM suspects a concurrency issue — deadlock, thread starvation, or " +
                        "an unexpected number of threads — and needs to see the global thread landscape " +
                        "rather than just the currently paused thread's stack."
                )
                params {
                    optional("session_id", "string") {
                        llmSeesIt("Debug session ID (optional — uses active session if omitted)")
                        humanReadable("Which session's JVM to query.")
                        whenPresent("That session's VM threads are returned.")
                        whenAbsent("Active session's VM threads are returned.")
                    }
                    optional("max_frames", "integer") {
                        llmSeesIt("Maximum stack frames to return (default 20, max 50) — for get_stack_frames, thread_dump")
                        humanReadable("How many frames per thread to include when include_stacks=true.")
                        whenPresent("At most that many frames per thread.")
                        whenAbsent("Default 20 frames per thread.")
                        constraint("only meaningful when include_stacks=true")
                        example("10")
                    }
                    optional("include_stacks", "boolean") {
                        llmSeesIt("Include stack traces per thread (default: true) — for thread_dump")
                        humanReadable("Whether to include per-thread stack frames. Set to false for a quick thread-count-only snapshot.")
                        whenPresent("Stack frames are included per thread if that thread is suspended; otherwise shows '(frames unavailable — thread not suspended)'.")
                        whenAbsent("Defaults to true — stack frames included.")
                    }
                    optional("include_daemon", "boolean") {
                        llmSeesIt("Include daemon threads (default: false) — for thread_dump")
                        humanReadable("Whether to include daemon threads (JVM housekeeping threads like GC, finalizer). Usually not interesting for application debugging.")
                        whenPresent("Daemon threads are included in the dump.")
                        whenAbsent("Defaults to false — daemon threads excluded.")
                    }
                }
                precondition("[ANY] — works on running or paused sessions. Per-thread stack frames require that thread to be suspended.")
                onSuccess("Returns: total thread count, suspended count, then per-thread block: name, id, status, daemon flag, and stack frames (if available and thread is suspended).")
                onFailure("VM is disconnected or session ended", "Returns 'Thread dump returned empty — VM may be disconnected.'")
                onFailure("include_stacks=true but threads are not suspended", "Per-thread frames show '(frames unavailable — thread not suspended)' — not an error, expected for running threads.")
                example("detect deadlock") {
                    param("action", "thread_dump")
                    param("include_stacks", "true")
                    outcome("Returns all non-daemon threads with their stack frames. Two threads BLOCKED on each other's monitor will show their stacks ending in synchronized blocks on each other's objects — classic deadlock signature.")
                }
                verdict {
                    keep(
                        "The only action that gives a system-wide thread view. `get_stack_frames` only " +
                            "shows one thread; `thread_dump` shows all threads, which is essential for " +
                            "diagnosing deadlocks and thread leaks.",
                        VerdictSeverity.STRONG
                    )
                }
            }

            // ── memory_view ───────────────────────────────────────────────────────
            action("memory_view") {
                description {
                    technical(
                        "Counts live instances of a fully-qualified class name in the JVM heap via " +
                            "`VirtualMachine.instanceCounts()`. Optionally lists per-instance details " +
                            "(reference type, unique ID) for the first N instances. Requires " +
                            "`vm.canGetInstanceInfo()`. Session must be paused (JDWP constraint). " +
                            "Output auto-spills via `spillOrFormat`."
                    )
                    plain(
                        "Like a JVM heap search: 'how many User objects are currently alive in " +
                            "memory?' Useful for finding memory leaks (the count keeps growing) or " +
                            "verifying that a cache is not unbounded."
                    )
                }
                whenLLMUses(
                    "When the LLM suspects a memory leak or wants to verify that object lifecycle " +
                        "management is correct — e.g., that a connection pool doesn't grow unboundedly, " +
                        "or that cached items are evicted properly."
                )
                params {
                    required("class_name", "string") {
                        llmSeesIt("Fully qualified class name — for memory_view")
                        humanReadable("The fully-qualified class name to count in the heap. Must match exactly how the class is known to the JVM (e.g., inner classes use `$` separator).")
                        whenPresent("All loaded reference types matching that name are counted; if multiple match (subclasses, inner classes), counts are listed per type.")
                        constraint("must be a fully-qualified class name — short names will not resolve")
                        constraint("class must be loaded in the JVM — not-yet-instantiated classes return a 'not loaded' error")
                        example("com.example.service.UserSession")
                        example("java.util.HashMap")
                    }
                    optional("session_id", "string") {
                        llmSeesIt("Debug session ID (optional — uses active session if omitted)")
                        humanReadable("Which session's heap to query.")
                        whenPresent("That session's VM heap is counted.")
                        whenAbsent("Active session's VM heap is counted.")
                    }
                    optional("max_instances", "integer") {
                        llmSeesIt("Max instances to list details for (0=count only). Default 0 — for memory_view")
                        humanReadable("How many individual instance records to include (unique ID, reference type). 0 = only the total count is shown, no per-instance detail.")
                        whenPresent("Up to that many instance records are listed (capped at 50 internally).")
                        whenAbsent("Default 0 — count only, no per-instance detail.")
                        constraint("capped at 50 instances regardless of requested value")
                        example("10")
                    }
                }
                precondition("session must be PAUSED (JDWP constraint — instanceCounts requires a suspended VM)")
                precondition("VM must support canGetInstanceInfo() — fails on remote / non-HotSpot VMs")
                onSuccess("Returns: class name, total live instance count, optional breakdown by type (when multiple reference types match), optional per-instance details (first N instances with unique IDs).")
                onFailure("VM does not support canGetInstanceInfo", "Error: 'VM does not support instance info … may be a remote or non-HotSpot JVM.'")
                onFailure("class not loaded", "Error: 'Class not loaded in the JVM. It may not have been instantiated yet, or the name may be incorrect.'")
                onFailure("session is running", "NOT_SUSPENDED error.")
                example("check for connection leak") {
                    param("action", "memory_view")
                    param("class_name", "com.zaxxer.hikari.pool.PoolEntry")
                    param("max_instances", "0")
                    outcome("Returns the total count of HikariCP pool entries. If the count grows across snapshots while connections should be evicted, it indicates a leak.")
                }
                verdict {
                    keep("Only way to count live heap instances programmatically. No evaluate-based equivalent exists without iterating all objects via ObjectReference, which has no LLM-callable surface.", VerdictSeverity.NORMAL)
                    drop("Narrow JVM capability requirement (canGetInstanceInfo=false on remote/non-HotSpot). Rarely used — most debugging doesn't require heap counts.", VerdictSeverity.WEAK)
                }
            }

            // ── hotswap ───────────────────────────────────────────────────────────
            action("hotswap") {
                description {
                    technical(
                        "Triggers IntelliJ's HotSwap mechanism via `HotSwapUI.reloadChangedClasses()`. " +
                            "Optionally compiles changed files first. Registers a `HotSwapStatusListener` " +
                            "to get success/failure/cancelled/nothing_to_reload status. Wrapped in a " +
                            "60-second timeout. Python sessions are rejected early via reflection-based " +
                            "PyDebugProcess detection."
                    )
                    plain(
                        "Like IntelliJ's 'Reload Changed Classes' button (Alt+Shift+F9 → HotSwap) — " +
                            "pushes your code changes into the running JVM without restarting the session. " +
                            "Works only for body changes (not structural changes like adding/removing methods)."
                    )
                }
                whenLLMUses(
                    "After editing a method body during a paused debug session to fix a suspected bug, " +
                        "the LLM calls hotswap to load the fix into the running JVM — then resumes " +
                        "and observes whether the behaviour changes without restarting."
                )
                params {
                    optional("session_id", "string") {
                        llmSeesIt("Debug session ID (optional — uses active session if omitted)")
                        humanReadable("Which session to hot-swap into.")
                        whenPresent("That session's debugger is used to correlate with the DebuggerManagerEx session.")
                        whenAbsent("Active session is used.")
                    }
                    optional("compile_first", "boolean") {
                        llmSeesIt("Compile changed files before reloading (default: true) — for hotswap")
                        humanReadable("Whether to compile changed files before asking the JVM to reload. Should almost always be true — reloading un-compiled changes is a no-op.")
                        whenPresent("Files are compiled (or not) according to this flag before reload.")
                        whenAbsent("Default true — files are compiled first.")
                    }
                }
                precondition("[ANY] — works on running or paused sessions")
                precondition("Java/Kotlin only — Python sessions return an error immediately")
                precondition("structural changes (new methods, fields, changed hierarchy) cause hotswap failure — body-only changes only")
                onSuccess("Returns: hot swap result (success/failure/cancelled/nothing_to_reload/timeout), session id, compile_first flag, and a human-readable explanation of the outcome.")
                onFailure("Python debug session", "Immediate error: 'Hot swap is not supported for Python.'")
                onFailure("structural changes", "Status 'failure' — 'Check for structural changes (new/removed methods, fields, or signature changes).'")
                onFailure("nothing changed", "Status 'nothing_to_reload' — 'No changed classes detected. Make code changes first.'")
                onFailure("timeout after 60s", "Status 'timeout' — 'Check compilation and IDE status.'")
                example("edit and hot-reload a bug fix") {
                    param("action", "hotswap")
                    param("compile_first", "true")
                    outcome("Returns 'hot swap result: success … Classes reloaded successfully. Execution continues with new code.' The LLM can then resume and verify the fix.")
                }
                verdict {
                    keep(
                        "Enables the only cycle shorter than recompile+restart — the LLM edits a method " +
                            "body (edit_file), reloads it (hotswap), and resumes, all without terminating " +
                            "the session. Crucial for debugging slow-to-start applications.",
                        VerdictSeverity.STRONG
                    )
                }
            }

            // ── force_return ──────────────────────────────────────────────────────
            action("force_return") {
                description {
                    technical(
                        "Forces the current method to return immediately via `ThreadReference.forceEarlyReturn()`. " +
                            "Requires `vmProxy.canForceEarlyReturn()`. Supports primitive types (int, long, " +
                            "boolean, string, double, float, char, byte, short), void, and null. " +
                            "Type is inferred from the value string when `return_type=auto`. " +
                            "Handles JDI-specific exceptions: IncompatibleThreadStateException, " +
                            "NativeMethodException, InvalidTypeException."
                    )
                    plain(
                        "Forces the current method to return immediately — like pressing an emergency " +
                            "eject button on the current method. The method returns the value you specify " +
                            "without executing any remaining code. Useful for testing 'what if this " +
                            "method returned X instead of what it normally computes?'"
                    )
                }
                whenLLMUses(
                    "When the LLM wants to skip the rest of a method's body and force a specific " +
                        "return value — usually to test whether the bug is inside the current method " +
                        "or in how the caller handles the return value."
                )
                params {
                    optional("session_id", "string") {
                        llmSeesIt("Debug session ID (optional — uses active session if omitted)")
                        humanReadable("Which session's current method to force-return from.")
                        whenPresent("That session's current method is force-returned.")
                        whenAbsent("Active session's current method is force-returned.")
                    }
                    optional("return_value", "string") {
                        llmSeesIt("Value to return: \"null\", \"42\", \"true\", etc. Omit for void — for force_return")
                        humanReadable("The value the method will return. Omit entirely for void methods. Must be compatible with the method's declared return type.")
                        whenPresent("The method returns this value.")
                        whenAbsent("Returns void (or null for reference-type returns when return_type=null).")
                        example("42")
                        example("true")
                        example("null")
                        example("\"success\"")
                    }
                    optional("return_type", "string") {
                        llmSeesIt("Return type: void, null, int, long, boolean, string, double, float, char, byte, short, auto (default) — for force_return")
                        humanReadable("Explicit return type to use when constructing the JDI mirror value. 'auto' infers the type from the value string (null→null, true/false→boolean, '42' in int range→int, etc.).")
                        whenPresent("The specified type is used to construct the JDI value — overrides auto-inference.")
                        whenAbsent("Default 'auto' — type is inferred from return_value.")
                        enumValue("auto", "void", "null", "int", "long", "boolean", "string", "double", "float", "char", "byte", "short")
                    }
                }
                precondition("session must be PAUSED")
                precondition("Java/Kotlin only — requires JDWP canForceEarlyReturn capability")
                precondition("thread must not be in native code (IncompatibleThreadStateException otherwise)")
                precondition("thread must not be at a native method (NativeMethodException otherwise)")
                onSuccess("Confirms: 'Forced early return from current method.' with the return type, value, and a note that remaining method execution is skipped.")
                onFailure("VM does not support canForceEarlyReturn", "Error: 'JVM does not support force early return … requires a JDWP-compliant JVM.'")
                onFailure("thread is in native code", "IncompatibleThreadStateException: 'The thread must be suspended at a non-native frame.'")
                onFailure("at a native method boundary", "NativeMethodException: 'Step out of the native method first.'")
                onFailure("type mismatch", "InvalidTypeException with descriptive message.")
                example("force a method to return true to isolate a caller bug") {
                    param("action", "force_return")
                    param("return_value", "true")
                    param("return_type", "boolean")
                    outcome("Current method returns true immediately. LLM resumes, observes caller behaviour when the method returns true, and determines whether the bug is in the caller's handling.")
                }
                verdict {
                    keep(
                        "Unique hypothesis-testing primitive. Lets the LLM short-circuit a method " +
                            "and inject a specific return value — equivalent to temporarily replacing " +
                            "the method body with `return X`, but without any source change.",
                        VerdictSeverity.STRONG
                    )
                }
            }

            // ── drop_frame ────────────────────────────────────────────────────────
            action("drop_frame") {
                description {
                    technical(
                        "Rewinds the program counter to the start of the method at `frame_index` " +
                            "via `ThreadReference.popFrames()`. Requires `vmProxy.canPopFrames()`. " +
                            "frame_index=0 rewinds the current method; frame_index=1 rewinds to the " +
                            "caller. IMPORTANT: only the program counter (instruction pointer) moves — " +
                            "local variables, fields, and any side effects already produced are NOT " +
                            "undone. Python sessions are rejected early."
                    )
                    plain(
                        "Rewinds the program counter to the start of the current (or parent) frame. " +
                            "Execution jumps back to the beginning of the method, but any side effects " +
                            "already produced (writes to files, DB inserts, println, HTTP calls, etc.) " +
                            "are NOT undone — only the instruction pointer moves back. Useful when you " +
                            "stepped past the interesting line and want to step through it again."
                    )
                }
                whenLLMUses(
                    "When the LLM has accidentally stepped past a critical line and wants to re-enter " +
                        "the method to step through it carefully from the top — without restarting the " +
                        "entire debug session."
                )
                params {
                    optional("session_id", "string") {
                        llmSeesIt("Debug session ID (optional — uses active session if omitted)")
                        humanReadable("Which session's frame to drop.")
                        whenPresent("That session's thread is rewound.")
                        whenAbsent("Active session's thread is rewound.")
                    }
                    optional("frame_index", "integer") {
                        llmSeesIt("Frame index to drop to (0=current, 1=caller). Default 0 — for drop_frame")
                        humanReadable("Which frame in the call stack to rewind to. 0 = rewind the current method (most common); 1 = rewind to the caller of the current method (pop two frames).")
                        whenPresent("Execution rewinds to the start of that frame's method.")
                        whenAbsent("Default 0 — rewinds the current method.")
                        constraint("must be ≥ 0")
                        constraint("must be less than the number of frames on the stack")
                        example("0")
                        example("1")
                    }
                }
                precondition("session must be PAUSED")
                precondition("Java/Kotlin only — Python sessions return a non-error message (not supported)")
                precondition("VM must support canPopFrames() — fails on remote/non-HotSpot VMs")
                precondition("variable state is NOT reset — only the program counter moves")
                onSuccess("Confirms: 'Dropped frame: rewound to beginning of ClassName.methodName', with frame index, thread name, session id, and the critical note that variable state and side effects are NOT undone.")
                onFailure("VM does not support canPopFrames", "Error: 'VM does not support frame popping.'")
                onFailure("Python debug session", "Non-error message: 'Drop frame is not supported in Python debug sessions.'")
                onFailure("frame_index out of range", "Error listing the valid range.")
                onFailure("no suspended thread found", "Error: 'No suspended thread found matching [thread].'")
                onFailure("session is running", "NOT_SUSPENDED error.")
                example("re-enter a method to step through it again") {
                    param("action", "drop_frame")
                    param("frame_index", "0")
                    outcome("Rewound to beginning of current method. LLM can now step through the method again from the top, this time more carefully at the interesting lines.")
                    notes("Variable state (locals) is NOT reset — any assignments that already happened are preserved. Only the program counter moves.")
                }
                verdict {
                    keep("Avoids full session restart when the LLM overstepped. The alternative is stop + relaunch + re-navigate to the same state, which can take minutes for slow-starting applications.", VerdictSeverity.NORMAL)
                    drop("Narrow use case: only useful when the LLM overstepped. The 'state not reset' caveat makes it confusing and a source of subtle mistakes when the LLM expects a clean re-run.", VerdictSeverity.WEAK)
                }
            }
        }
        narrative("debug_inspect")
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
            "evaluate" -> executeEvaluate(params, project)
            "get_stack_frames" -> executeGetStackFrames(params, project)
            "get_variables" -> executeGetVariables(params, project)
            "set_value" -> executeSetValue(params, project)
            "thread_dump" -> executeThreadDump(params, project)
            "memory_view" -> executeMemoryView(params, project)
            "hotswap" -> executeHotSwap(params, project)
            "force_return" -> executeForceReturn(params, project)
            "drop_frame" -> executeDropFrame(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions: evaluate, get_stack_frames, get_variables, set_value, thread_dump, memory_view, hotswap, force_return, drop_frame",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    // ── session resolution ──────────────────────────────────────────────────
    //
    // All actions resolve their target debug session through one of these two
    // helpers. Both delegate to IdeStateProbe so that sessions started outside
    // the agent (gutter Debug button, run config dropdown, etc.) are found via
    // the IntelliJ Platform — not just the agent's own session registry.
    //
    // The agent's controller registry is still consulted first via the
    // registryLookup callback, so sessions started by start_debug_session keep
    // their agent-assigned ids and `activeSessionId` semantics.

    private sealed class SessionResolution {
        data class Found(val session: XDebugSession) : SessionResolution()
        data class Failed(val toolResult: ToolResult) : SessionResolution()
    }

    /**
     * Resolves a debug session that exists (running or paused) for [sessionId].
     * Use this for actions that don't require the session to be paused
     * (thread_dump, hotswap).
     */
    private fun requireSession(project: Project, sessionId: String?): SessionResolution {
        val state = IdeStateProbe.debugState(project, sessionId, controller::getSession)
        return when (state) {
            is DebugState.Paused -> SessionResolution.Found(state.session)
            is DebugState.Running -> SessionResolution.Found(state.session)
            DebugState.NoSession -> SessionResolution.Failed(noSessionError(sessionId))
            is DebugState.AmbiguousSession -> SessionResolution.Failed(ambiguousError(state))
        }
    }

    /**
     * Resolves a debug session that exists AND is currently paused for [sessionId].
     * Use this for actions that need to inspect or modify suspended runtime state
     * (evaluate, get_variables, set_value, get_stack_frames, memory_view,
     * force_return, drop_frame).
     */
    private fun requireSuspendedSession(project: Project, sessionId: String?): SessionResolution {
        val state = IdeStateProbe.debugState(project, sessionId, controller::getSession)
        return when (state) {
            is DebugState.Paused -> SessionResolution.Found(state.session)
            is DebugState.Running -> SessionResolution.Failed(notSuspendedError())
            DebugState.NoSession -> SessionResolution.Failed(noSessionError(sessionId))
            is DebugState.AmbiguousSession -> SessionResolution.Failed(ambiguousError(state))
        }
    }

    private fun noSessionError(sessionId: String?) = ToolResult(
        buildString {
            append("No debug session found")
            if (sessionId != null) append(": $sessionId")
            append(". Start one with start_debug_session, or have the user start a debug session via the IDE (gutter Debug button or Run menu) and try again.")
        },
        "No session",
        ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true,
    )

    private fun notSuspendedError() = ToolResult(
        "No suspended session resolved. Common causes: " +
            "(1) multiple sessions are open and the one you targeted is running — " +
            "pass session_id explicitly, since `currentSession` resolves to the last-focused session (not necessarily the paused one); " +
            "(2) the program isn't at a breakpoint yet — set one and let execution reach it, or call debug_step(action=pause). " +
            "Run debug_step(action=get_state) first to list sessions and their paused/running state.",
        "Not suspended",
        ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true,
    )

    private fun ambiguousError(state: DebugState.AmbiguousSession) = ToolResult(
        "Multiple debug sessions are active (${state.count}: ${state.names.joinToString(", ")}). Pass session_id to disambiguate.",
        "Ambiguous session",
        ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true,
    )

    // ── evaluate ────────────────────────────────────────────────────────────

    private suspend fun executeEvaluate(params: JsonObject, project: Project): ToolResult {
        val expression = params["expression"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("expression")
        if (expression.isBlank()) {
            return ToolResult("Expression cannot be blank.", "Blank expression", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val sessionId = params["session_id"]?.jsonPrimitive?.content

        val session = when (val r = requireSuspendedSession(project, sessionId)) {
            is SessionResolution.Found -> r.session
            is SessionResolution.Failed -> return r.toolResult
        }

        return try {
            val evalResult = withTimeoutOrNull(EVALUATE_TIMEOUT_MS) {
                controller.evaluate(session, expression, 0)
            }

            if (evalResult == null) {
                return ToolResult(
                    "Expression evaluation timed out after ${EVALUATE_TIMEOUT_MS / 1000} seconds. " +
                        "The expression may contain an infinite loop or be waiting for a lock.",
                    "Evaluation timed out",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            if (evalResult.isError) {
                return ToolResult("Error: ${evalResult.result}", "Evaluation error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }

            val sb = StringBuilder()
            sb.append("Expression: $expression\n")
            sb.append("Result: ${evalResult.result}\n")
            sb.append("Type: ${evalResult.type}")

            // Evaluate output is bounded to a single value (< 1KB); no spill needed.
            val content = sb.toString()
            ToolResult(content, "Evaluated: $expression", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error evaluating expression: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── get_stack_frames ────────────────────────────────────────────────────

    private suspend fun executeGetStackFrames(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val maxFrames = (params["max_frames"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_FRAMES)
            .coerceIn(1, MAX_FRAMES_CAP)

        val session = when (val r = requireSuspendedSession(project, sessionId)) {
            is SessionResolution.Found -> r.session
            is SessionResolution.Failed -> return r.toolResult
        }

        return try {
            val frames = controller.getStackFrames(session, maxFrames)
                ?: return ToolResult(
                    "Stack frame retrieval timed out after ${AgentDebugController.GET_STACK_FRAMES_TIMEOUT_MS / 1000}s. " +
                        "The debugger may be in an inconsistent state. " +
                        "Try debug_step(action=get_state) to verify the session is still suspended.",
                    "Timed out",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )

            if (frames.isEmpty()) {
                return ToolResult("No stack frames available.", "No frames", ToolResult.ERROR_TOKEN_ESTIMATE)
            }

            val threadName = params["thread_name"]?.jsonPrimitive?.content
                ?: session.suspendContext?.activeExecutionStack?.displayName
                ?: "main"

            val sb = StringBuilder()
            sb.append("Stack trace ($threadName thread, ${frames.size} frames):\n")

            for (frame in frames) {
                val location = buildString {
                    append(frame.methodName)
                    if (frame.file != null && frame.line != null) {
                        val fileName = frame.file.substringAfterLast('/')
                        append("($fileName:${frame.line})")
                    }
                }
                sb.append("#${frame.index}  $location\n")
            }

            val content = sb.toString().trimEnd()
            ToolResult(content, "Stack trace: ${frames.size} frames", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error getting stack frames: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── get_variables ───────────────────────────────────────────────────────

    private suspend fun executeGetVariables(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val variableName = params["variable_name"]?.jsonPrimitive?.content
        val maxDepth = (params["max_depth"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_DEPTH)
            .coerceIn(1, MAX_DEPTH_CAP)
        // Default: hide CGLIB / Spring-proxy synthetic noise. LLM can pass include_internals=true
        // when it explicitly needs to inspect proxy internals (rare — usually debugging the
        // proxy infrastructure itself). Feedback.md §5 — Spring beans show 8-15 CGLIB lines
        // before the first real field, drowning out the actual state.
        val includeInternals = params["include_internals"]?.jsonPrimitive?.booleanOrNull ?: false

        val session = when (val r = requireSuspendedSession(project, sessionId)) {
            is SessionResolution.Found -> r.session
            is SessionResolution.Failed -> return r.toolResult
        }

        return try {
            val frame = session.currentStackFrame
                ?: return ToolResult("No active stack frame available.", "No frame", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            val effectiveDepth = if (variableName != null) maxDepth.coerceAtLeast(DEFAULT_MAX_DEPTH) else maxDepth
            val variables = controller.getVariables(frame, effectiveDepth)

            if (variables.isEmpty()) {
                return ToolResult("No variables in the current frame.", "No variables", ToolResult.ERROR_TOKEN_ESTIMATE)
            }

            val targetVars = if (variableName != null) {
                val match = variables.filter { it.name == variableName }
                if (match.isEmpty()) {
                    return ToolResult(
                        "Variable '$variableName' not found in frame. Available: ${variables.joinToString(", ") { it.name }}",
                        "Variable not found",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }
                match
            } else {
                variables
            }

            val pos = session.currentPosition
            val frameHeader = if (pos != null) {
                val file = pos.file.name
                val line = pos.line + 1
                "Frame #0: $file:$line"
            } else {
                "Frame #0"
            }

            val sb = StringBuilder()
            sb.append("$frameHeader\n\nVariables:\n")
            sb.append(formatVariables(targetVars, includeInternals = includeInternals))

            val spilled = spillOrFormat(sb.toString(), project)

            val varCount = targetVars.size
            val summary = if (variableName != null) {
                "Variable '$variableName' inspected"
            } else {
                "$varCount variables in frame #0"
            }
            ToolResult(
                content = spilled.preview,
                summary = summary,
                tokenEstimate = TokenEstimator.estimate(spilled.preview),
                spillPath = spilled.spilledToFile,
            )
        } catch (e: Exception) {
            ToolResult("Error getting variables: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── set_value ───────────────────────────────────────────────────────────

    /**
     * Modify a variable's value at runtime using XValueModifier.
     *
     * Finds the named variable in the current frame's children, gets its
     * XValueModifier, and calls setValue from EDT (as required by the API).
     * Falls back to evaluate-with-assignment if the variable has no modifier
     * (e.g., computed properties, watch expressions).
     */
    private suspend fun executeSetValue(params: JsonObject, project: Project): ToolResult {
        val variableName = params["variable_name"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("variable_name")
        val newValue = params["new_value"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("new_value")
        val sessionId = params["session_id"]?.jsonPrimitive?.content

        val session = when (val r = requireSuspendedSession(project, sessionId)) {
            is SessionResolution.Found -> r.session
            is SessionResolution.Failed -> return r.toolResult
        }

        // Pre-flight check: if the LLM passed something that looks like a method-call expression
        // ("x.foo()"), the JDI assignment fallback would emit a cryptic "Incompatible types for
        // '=' operation" error — feedback.md §2. Surface a clear hint pointing them at the
        // evaluate action (which IS the right tool for invoking side-effecting setters).
        val mutationHint = detectMutationExpression(newValue)
        if (mutationHint != null) {
            return ToolResult(
                content = "set_value rejected '$newValue': $mutationHint\n" +
                    "Use `evaluate(expression=\"$newValue\")` to invoke a setter / method call. " +
                    "`set_value` is only for primitive / string / null literal assignments " +
                    "(e.g. `42`, `\"hello\"`, `null`, `true`).",
                summary = "set_value: use `evaluate` for method calls",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )
        }

        return try {
            val frame = session.currentStackFrame
                ?: return ToolResult("No active stack frame.", "No frame", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            // Find the XValue for this variable name in the frame's children
            val xValue = controller.findXValueByName(frame, variableName)

            if (xValue != null) {
                val modifier = xValue.modifier
                if (modifier != null) {
                    // Use XValueModifier — the correct API for setting values.
                    // Documented: "this method is called from the Event Dispatch Thread"
                    val modifyResult: Result<Unit> = withContext(Dispatchers.EDT) {
                        suspendCancellableCoroutine { cont ->
                            modifier.setValue(
                                XDebuggerUtil.getInstance().createExpression(
                                    newValue,
                                    /* language */ null,
                                    /* customInfo */ null,
                                    EvaluationMode.EXPRESSION,
                                ),
                                object : com.intellij.xdebugger.frame.XValueModifier.XModificationCallback {
                                    override fun valueModified() {
                                        cont.resume(Result.success(Unit))
                                    }

                                    override fun errorOccurred(errorMessage: String) {
                                        cont.resume(Result.failure(RuntimeException(errorMessage)))
                                    }
                                }
                            )
                        }
                    }

                    modifyResult.getOrElse { error ->
                        return ToolResult(
                            "Failed to set '$variableName': ${error.message}",
                            "Set value failed",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )
                    }

                    // Verify by reading back
                    val verifyResult = controller.evaluate(session, variableName, 0)
                    val content = buildString {
                        append("Variable '$variableName' set to: ${verifyResult.result}\n")
                        append("Type: ${verifyResult.type}\n")
                        append("Method: XValueModifier (direct)")
                    }
                    return ToolResult(content, "Set $variableName = $newValue", TokenEstimator.estimate(content))
                }
            }

            // Fallback: variable not found in frame children or has no modifier.
            // Try evaluate-with-assignment (works for Java where assignment is an expression).
            val assignExpression = "$variableName = $newValue"
            val evalResult = controller.evaluate(session, assignExpression, 0)

            if (evalResult.isError) {
                return ToolResult(
                    "Failed to set '$variableName': ${evalResult.result}\n" +
                        "Variable may not exist in the current frame, or may be final/val.",
                    "Set value failed",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            // Verify the new value
            val verifyResult = controller.evaluate(session, variableName, 0)
            val content = buildString {
                append("Variable '$variableName' set to: ${verifyResult.result}\n")
                append("Type: ${verifyResult.type}\n")
                append("Method: evaluate fallback (assignment expression)")
            }
            ToolResult(content, "Set $variableName = $newValue", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error setting variable: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    /**
     * Cheap heuristic — returns a human-readable hint if [expr] looks like a method-call
     * expression (`foo.bar()`, `new Foo()`, `Foo.method(...)`). Returns null for plain
     * literals (`42`, `"hello"`, `null`, `true`, `[1,2,3]`, `new int[]{1,2}`).
     *
     * Used by [executeSetValue] to redirect the LLM to the `evaluate` action before the
     * JDI assignment fallback emits a cryptic "Incompatible types for '=' operation" error.
     */
    internal fun detectMutationExpression(expr: String): String? {
        val trimmed = expr.trim()
        if (trimmed.isEmpty()) return null
        // Plain literals — pass through to JDI which handles these natively.
        if (trimmed == "null" || trimmed == "true" || trimmed == "false") return null
        if (trimmed.toLongOrNull() != null || trimmed.toDoubleOrNull() != null) return null
        if (trimmed.startsWith('"') && trimmed.endsWith('"')) return null
        if (trimmed.startsWith('\'') && trimmed.endsWith('\'')) return null
        // Constructors like `new Foo()` or `new int[]{1,2}` — JDI handles array literals
        // but generally not new-expressions; flag them as mutation-shaped.
        if (trimmed.startsWith("new ")) {
            return "expression looks like a constructor / 'new' call"
        }
        // Detect a method-call shape: identifier followed by `(` somewhere. We're conservative —
        // only flag when there's a dot-or-bare identifier followed by `(`, not when the value is
        // something like a JSON literal `{key: value}` or an array `[1,2,3]`.
        val callRegex = Regex("""[a-zA-Z_$][a-zA-Z0-9_$]*\s*\(""")
        if (callRegex.containsMatchIn(trimmed)) {
            return "expression looks like a method or function call"
        }
        return null
    }

    // ── thread_dump ─────────────────────────────────────────────────────────

    private suspend fun executeThreadDump(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val includeStacks = params["include_stacks"]?.jsonPrimitive?.booleanOrNull ?: true
        val maxFrames = params["max_frames"]?.jsonPrimitive?.intOrNull ?: 20
        val includeDaemon = params["include_daemon"]?.jsonPrimitive?.booleanOrNull ?: false

        val session = when (val r = requireSession(project, sessionId)) {
            is SessionResolution.Found -> r.session
            is SessionResolution.Failed -> return r.toolResult
        }

        return try {
            val threadInfos = controller.executeOnManagerThread(session) { _, vmProxy ->
                val vm = vmProxy.virtualMachine
                val allThreads = vm.allThreads()
                allThreads.mapNotNull { thread ->
                    val isDaemon = inferDaemon(thread)
                    if (!includeDaemon && isDaemon) return@mapNotNull null

                    val threadName = try { thread.name() } catch (_: Exception) { "<unknown>" }
                    val status = try { thread.status() } catch (_: Exception) { THREAD_STATUS_UNKNOWN }
                    val threadId = try { thread.uniqueID() } catch (_: Exception) { -1L }
                    val isSuspended = try { thread.isSuspended } catch (_: Exception) { false }

                    val frames = if (includeStacks) {
                        try {
                            thread.frames().take(maxFrames).map { frame ->
                                val location = frame.location()
                                val clsName = try { location.declaringType().name() } catch (_: Exception) { "<unknown>" }
                                val methName = try { location.method().name() } catch (_: Exception) { "<unknown>" }
                                val sourceName = try { location.sourceName() } catch (_: Exception) { null }
                                val lineNumber = try { location.lineNumber() } catch (_: Exception) { -1 }
                                ThreadFrameInfo(clsName, methName, sourceName, lineNumber)
                            }
                        } catch (_: Exception) { null }
                    } else { null }

                    ThreadInfo(
                        name = threadName, id = threadId, status = status,
                        statusText = statusToString(status), isDaemon = isDaemon,
                        isSuspended = isSuspended, frames = frames
                    )
                }
            }

            if (threadInfos.isEmpty()) {
                return ToolResult("Thread dump returned empty — VM may be disconnected.", "Empty thread dump", ToolResult.ERROR_TOKEN_ESTIMATE)
            }

            val suspendedCount = threadInfos.count { it.isSuspended }
            val sb = StringBuilder()
            sb.append("Thread dump (${threadInfos.size} threads, $suspendedCount suspended):\n")

            for (thread in threadInfos) {
                sb.append("\n[${thread.statusText}] ${thread.name} (id=${thread.id}")
                if (thread.isDaemon) sb.append(", daemon")
                sb.append(")\n")

                if (thread.frames == null && includeStacks) {
                    sb.append("  (frames unavailable — thread not suspended)\n")
                } else if (thread.frames != null) {
                    for (frame in thread.frames) {
                        val sourceRef = if (frame.sourceName != null && frame.lineNumber > 0) {
                            "${frame.sourceName}:${frame.lineNumber}"
                        } else if (frame.sourceName != null) {
                            frame.sourceName
                        } else {
                            "Unknown Source"
                        }
                        sb.append("  ${frame.className}.${frame.methodName}($sourceRef)\n")
                    }
                }
            }

            val spilled = spillOrFormat(sb.toString().trimEnd(), project)
            ToolResult(
                content = spilled.preview,
                summary = "Thread dump: ${threadInfos.size} threads, $suspendedCount suspended",
                tokenEstimate = TokenEstimator.estimate(spilled.preview),
                spillPath = spilled.spilledToFile,
            )
        } catch (e: Exception) {
            ToolResult("Error getting thread dump: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── memory_view ─────────────────────────────────────────────────────────

    private suspend fun executeMemoryView(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val className = params["class_name"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("class_name")
        val maxInstances = params["max_instances"]?.jsonPrimitive?.intOrNull ?: 0

        val session = when (val r = requireSuspendedSession(project, sessionId)) {
            is SessionResolution.Found -> r.session
            is SessionResolution.Failed -> return r.toolResult
        }

        // Collect raw content on the manager thread, then spill outside (spillOrFormat is suspend).
        data class MemoryViewData(val content: String, val summary: String)

        return try {
            val dataOrError = controller.executeOnManagerThread(session) { _, vmProxy ->
                val vm = vmProxy.virtualMachine

                if (!vm.canGetInstanceInfo()) {
                    return@executeOnManagerThread Result.failure<MemoryViewData>(
                        IllegalStateException("VM does not support instance info (canGetInstanceInfo=false). This may be a remote or non-HotSpot JVM.")
                    )
                }

                val refTypes = vm.classesByName(className)
                if (refTypes.isEmpty()) {
                    return@executeOnManagerThread Result.failure<MemoryViewData>(
                        NoSuchElementException("Class '$className' is not loaded in the JVM. It may not have been instantiated yet, or the name may be incorrect.")
                    )
                }

                val counts = vm.instanceCounts(refTypes)
                val totalCount = counts.sum()

                val content = buildString {
                    append("Memory view for: $className\n")
                    append("Total live instances: $totalCount\n")

                    if (refTypes.size > 1) {
                        append("\nBreakdown by type:\n")
                        refTypes.forEachIndexed { i, refType ->
                            append("  ${refType.name()}: ${counts[i]}\n")
                        }
                    }

                    if (maxInstances > 0 && totalCount > 0) {
                        val cappedMax = maxInstances.coerceAtMost(MAX_INSTANCE_DETAILS)
                        append("\nInstance details (first $cappedMax):\n")
                        val instances = refTypes[0].instances(cappedMax.toLong())
                        instances.forEachIndexed { i, instance ->
                            append("  [$i] ${instance.referenceType().name()} @ ${instance.uniqueID()}\n")
                        }
                        if (totalCount > cappedMax) {
                            append("  ... and ${totalCount - cappedMax} more\n")
                        }
                    }

                    append("\nSession: ${sessionId ?: controller.getActiveSessionId() ?: "unknown"}")
                }

                Result.success(MemoryViewData(content, "$totalCount instances of $className"))
            }

            val data = dataOrError.getOrElse { err ->
                val msg = err.message ?: "Unknown error"
                val summary = when (err) {
                    is IllegalStateException -> "Not supported"
                    is NoSuchElementException -> "Class not loaded"
                    else -> "Error"
                }
                return ToolResult(msg, summary, ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }

            val spilled = spillOrFormat(data.content, project)
            ToolResult(
                content = spilled.preview,
                summary = data.summary,
                tokenEstimate = TokenEstimator.estimate(spilled.preview),
                spillPath = spilled.spilledToFile,
            )
        } catch (e: Exception) {
            ToolResult("Error viewing memory: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── hotswap ─────────────────────────────────────────────────────────────

    private suspend fun executeHotSwap(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val compileFirst = params["compile_first"]?.jsonPrimitive?.booleanOrNull ?: true

        val xSession = when (val r = requireSession(project, sessionId)) {
            is SessionResolution.Found -> r.session
            is SessionResolution.Failed -> return r.toolResult
        }

        // Hot swap relies on the JVM HotSwap protocol (JDWP redefineClasses) which is
        // Java/Kotlin-only. Python debug processes (PyDebugProcess) do not support it.
        if (isPythonDebugSession(xSession)) {
            return ToolResult(
                "Hot swap is not supported for Python. Restart the debug session to apply changes.",
                "Hot swap not supported for Python",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            val hotSwapUI = HotSwapUI.getInstance(project)
            val debuggerManager = DebuggerManagerEx.getInstanceEx(project)

            // Correlate the XDebugSession with the correct DebuggerSession.
            // When multiple sessions exist, sessions.firstOrNull() would pick the wrong one.
            val debuggerSession = debuggerManager.sessions.find { ds ->
                ds.xDebugSession === xSession
            } ?: debuggerManager.sessions.firstOrNull()
                ?: return ToolResult(
                    "No active debugger session found in DebuggerManagerEx.",
                    "No debugger session",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )

            val status = withTimeoutOrNull(60_000L) {
                suspendCancellableCoroutine { cont ->
                    // Use the abstract HotSwapUI API directly (not HotSwapUIImpl cast)
                    hotSwapUI.reloadChangedClasses(
                        debuggerSession, compileFirst,
                        object : HotSwapStatusListener {
                            override fun onSuccess(sessions: MutableList<DebuggerSession>) { cont.resume("success") }
                            override fun onFailure(sessions: MutableList<DebuggerSession>) { cont.resume("failure") }
                            override fun onCancel(sessions: MutableList<DebuggerSession>) { cont.resume("cancelled") }
                            override fun onNothingToReload(sessions: MutableList<DebuggerSession>) { cont.resume("nothing_to_reload") }
                        }
                    )
                }
            } ?: "timeout"

            val resolvedId = sessionId ?: controller.getActiveSessionId() ?: "unknown"
            val content = buildString {
                append("Hot swap result: $status\n")
                append("Session: $resolvedId\n")
                append("Compile first: $compileFirst\n")
                when (status) {
                    "success" -> append("Classes reloaded successfully. Execution continues with new code.")
                    "failure" -> append("Hot swap failed. Check for structural changes (new/removed methods, fields, or signature changes).")
                    "cancelled" -> append("Hot swap was cancelled by the user or IDE.")
                    "nothing_to_reload" -> append("No changed classes detected. Make code changes first.")
                    "timeout" -> append("Hot swap timed out after 60 seconds. Check compilation and IDE status.")
                }
            }

            val isError = status == "failure" || status == "timeout"
            ToolResult(content, "Hot swap: $status", TokenEstimator.estimate(content), isError = isError)
        } catch (e: Exception) {
            ToolResult("Error during hot swap: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── force_return ────────────────────────────────────────────────────────

    private suspend fun executeForceReturn(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val returnValue = params["return_value"]?.jsonPrimitive?.content
        val returnType = params["return_type"]?.jsonPrimitive?.content ?: "auto"

        val session = when (val r = requireSuspendedSession(project, sessionId)) {
            is SessionResolution.Found -> r.session
            is SessionResolution.Failed -> return r.toolResult
        }

        return try {
            controller.executeOnManagerThread(session) { debugProcess, vmProxy ->
                if (!vmProxy.canForceEarlyReturn()) {
                    throw IllegalStateException(
                        "JVM does not support force early return. This requires a JDWP-compliant JVM with canForceEarlyReturn capability."
                    )
                }

                val suspendContext = debugProcess.suspendManager.getPausedContext()
                    ?: throw IllegalStateException("No suspended context available. Ensure the session is paused.")

                val thread = (suspendContext as? SuspendContextImpl)?.thread
                    ?: throw IllegalStateException("Cannot access the suspended thread. The thread may not be available.")

                val effectiveType = if (returnType == "auto") inferReturnType(returnValue) else returnType

                val value: com.sun.jdi.Value? = when (effectiveType) {
                    "void" -> vmProxy.mirrorOfVoid()
                    "null" -> null
                    "int" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for int type")
                        vmProxy.mirrorOf(v.toInt())
                    }
                    "long" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for long type")
                        vmProxy.mirrorOf(v.toLong())
                    }
                    "boolean" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for boolean type")
                        vmProxy.mirrorOf(v.toBoolean())
                    }
                    "string" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for string type")
                        vmProxy.mirrorOf(v)
                    }
                    "double" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for double type")
                        vmProxy.mirrorOf(v.toDouble())
                    }
                    "float" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for float type")
                        vmProxy.mirrorOf(v.toFloat())
                    }
                    "char" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for char type")
                        if (v.isEmpty()) throw IllegalArgumentException("return_value for char type cannot be empty")
                        vmProxy.mirrorOf(v[0])
                    }
                    "byte" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for byte type")
                        vmProxy.mirrorOf(v.toByte())
                    }
                    "short" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for short type")
                        vmProxy.mirrorOf(v.toShort())
                    }
                    else -> throw IllegalArgumentException("Unknown return type: $effectiveType")
                }

                thread.forceEarlyReturn(value)
            }

            val sb = StringBuilder()
            sb.append("Forced early return from current method.\n")
            sb.append("Return type: $returnType\n")
            if (returnValue != null) {
                sb.append("Return value: $returnValue\n")
            } else {
                sb.append("Return value: (void/none)\n")
            }
            sb.append("The method will return immediately, skipping remaining execution.")

            val content = sb.toString()
            ToolResult(content, "Forced return with ${returnValue ?: "void"}", TokenEstimator.estimate(content))
        } catch (e: com.sun.jdi.IncompatibleThreadStateException) {
            ToolResult("Thread is not in a compatible state for force return. The thread must be suspended at a non-native frame.", "Incompatible thread state", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } catch (e: com.sun.jdi.NativeMethodException) {
            ToolResult("Cannot force return from a native method. Step out of the native method first.", "Native method", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } catch (e: com.sun.jdi.InvalidTypeException) {
            ToolResult("Type mismatch: the return value type does not match the method's return type. ${e.message}", "Type mismatch", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } catch (e: IllegalStateException) {
            ToolResult("Error: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } catch (e: IllegalArgumentException) {
            ToolResult("Invalid parameter: ${e.message}", "Invalid parameter", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } catch (e: NumberFormatException) {
            ToolResult("Invalid number format for return value: ${e.message}", "Number format error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } catch (e: Exception) {
            ToolResult("Error forcing return: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── drop_frame ──────────────────────────────────────────────────────────

    private suspend fun executeDropFrame(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val frameIndex = params["frame_index"]?.jsonPrimitive?.intOrNull ?: 0

        if (frameIndex < 0) {
            return ToolResult("frame_index must be >= 0, got $frameIndex", "Invalid frame index", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val session = when (val r = requireSuspendedSession(project, sessionId)) {
            is SessionResolution.Found -> r.session
            is SessionResolution.Failed -> return r.toolResult
        }

        if (isPythonDebugSession(session)) {
            return ToolResult(
                "Drop frame is not supported in Python debug sessions. Python's debugger does not support rewinding execution.",
                "Drop frame not supported for Python", 10
            )
        }

        return try {
            controller.executeOnManagerThread(session) { _, vmProxy ->
                if (!vmProxy.canPopFrames()) {
                    return@executeOnManagerThread ToolResult(
                        "VM does not support frame popping (canPopFrames=false). This may be a remote or non-HotSpot JVM.",
                        "Not supported",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }

                val suspendContext = session.suspendContext
                    ?: return@executeOnManagerThread ToolResult(
                        "No suspend context available. Session may not be properly paused.",
                        "No suspend context",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )

                val activeStack = suspendContext.activeExecutionStack
                    ?: return@executeOnManagerThread ToolResult(
                        "No active execution stack. Cannot determine current thread.",
                        "No active stack",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )

                val vm = vmProxy.virtualMachine
                val threads = vm.allThreads()
                val threadName = activeStack.displayName

                val thread = threads.firstOrNull { t ->
                    t.name() == threadName && t.isSuspended
                } ?: threads.firstOrNull { it.isSuspended }
                    ?: return@executeOnManagerThread ToolResult(
                        "No suspended thread found matching '$threadName'.",
                        "No suspended thread",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )

                val frames = thread.frames()
                if (frameIndex >= frames.size) {
                    return@executeOnManagerThread ToolResult(
                        "frame_index $frameIndex is out of range. Stack has ${frames.size} frames (0..${frames.size - 1}).",
                        "Frame out of range",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }

                val targetFrame = frames[frameIndex]
                val location = targetFrame.location()
                val methodName = "${location.declaringType().name()}.${location.method().name()}"

                thread.popFrames(targetFrame)

                val content = buildString {
                    append("Dropped frame: rewound to beginning of $methodName\n")
                    append("Frame index: $frameIndex\n")
                    append("Thread: ${thread.name()}\n")
                    append("Session: ${sessionId ?: controller.getActiveSessionId() ?: "unknown"}\n")
                    append("\nNote: Variable state is NOT reset. Side effects are NOT undone.")
                }
                ToolResult(content, "Dropped frame to $methodName", TokenEstimator.estimate(content))
            }
        } catch (e: Exception) {
            ToolResult("Error dropping frame: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Returns true when [session] is backed by a Python debug process.
     *
     * Uses reflection so the agent module has zero compile-time dependency on the
     * Python plugin JARs. Python debug processes are named PyDebugProcess (PyCharm)
     * or have "Py" in their class name — checking the simple name is sufficient
     * because there is no other XDebugProcess subclass with that prefix.
     */
    private fun isPythonDebugSession(session: XDebugSession): Boolean {
        return try {
            val processClass = session.debugProcess.javaClass
            processClass.simpleName.startsWith("Py") ||
                processClass.name.contains("pydevd", ignoreCase = true) ||
                processClass.name.startsWith("com.jetbrains.python") ||
                processClass.name.startsWith("com.intellij.python")
        } catch (_: Exception) {
            false
        }
    }

    private fun inferReturnType(returnValue: String?): String {
        if (returnValue == null) return "void"
        return when {
            returnValue == "null" -> "null"
            returnValue == "true" || returnValue == "false" -> "boolean"
            returnValue.startsWith("\"") && returnValue.endsWith("\"") -> "string"
            returnValue.contains(".") -> "double"
            returnValue.toLongOrNull() != null -> {
                val num = returnValue.toLong()
                if (num in Int.MIN_VALUE..Int.MAX_VALUE) "int" else "long"
            }
            else -> "string"
        }
    }

    // --- Thread dump helpers ---

    private data class ThreadFrameInfo(
        val className: String,
        val methodName: String,
        val sourceName: String?,
        val lineNumber: Int
    )

    private data class ThreadInfo(
        val name: String,
        val id: Long,
        val status: Int,
        val statusText: String,
        val isDaemon: Boolean,
        val isSuspended: Boolean,
        val frames: List<ThreadFrameInfo>?
    )

    private fun inferDaemon(thread: com.sun.jdi.ThreadReference): Boolean {
        return try {
            val method = thread.javaClass.getMethod("isDaemon")
            method.invoke(thread) as? Boolean ?: false
        } catch (_: Exception) {
            try {
                val groupName = thread.threadGroup()?.name() ?: ""
                groupName.contains("daemon", ignoreCase = true)
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun statusToString(status: Int): String = when (status) {
        THREAD_STATUS_RUNNING -> "RUNNING"
        THREAD_STATUS_SLEEPING -> "SLEEPING"
        THREAD_STATUS_MONITOR -> "BLOCKED"
        THREAD_STATUS_WAIT -> "WAITING"
        THREAD_STATUS_NOT_STARTED -> "NOT_STARTED"
        THREAD_STATUS_ZOMBIE -> "TERMINATED"
        else -> "UNKNOWN"
    }

    companion object {
        // EvaluateTool constants
        //
        // Outer ceiling for the whole evaluate pipeline. Must be ≥ the controller's
        // internal stacked timeouts (10s JDI dispatch + 20s PRESENTATION_TIMEOUT_MS),
        // otherwise the outer wraps cut the inner waits short and the LLM sees a
        // spurious timeout. 35s gives ~5s of headroom over the worst-case inner sum.
        private const val EVALUATE_TIMEOUT_MS = 35_000L

        // GetStackFramesTool constants
        private const val DEFAULT_MAX_FRAMES = 20
        private const val MAX_FRAMES_CAP = 50

        // GetVariablesTool constants
        private const val DEFAULT_MAX_DEPTH = 2
        private const val MAX_DEPTH_CAP = 4

        // MemoryViewTool constants
        private const val MAX_INSTANCE_DETAILS = 50

        // ThreadDumpTool constants
        private const val THREAD_STATUS_UNKNOWN = -1
        private const val THREAD_STATUS_ZOMBIE = 0
        private const val THREAD_STATUS_RUNNING = 1
        private const val THREAD_STATUS_SLEEPING = 2
        private const val THREAD_STATUS_MONITOR = 3
        private const val THREAD_STATUS_WAIT = 4
        private const val THREAD_STATUS_NOT_STARTED = 5
    }
}
