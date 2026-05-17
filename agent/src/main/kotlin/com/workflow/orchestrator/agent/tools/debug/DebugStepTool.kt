package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerUtil
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
import com.workflow.orchestrator.agent.tools.platform.DebugState
import com.workflow.orchestrator.agent.tools.platform.IdeStateProbe
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Debug session navigation — stepping, state, and lifecycle control.
 *
 * 10 actions with only 4 parameters (action, session_id, file, line).
 * Includes force_step_into (bypasses step filters for framework code)
 * and force_step_over (ignores breakpoints in called methods).
 */
class DebugStepTool(private val controller: AgentDebugController) : AgentTool {

    override val name = "debug_step"

    override val description = """
Debug session navigation — stepping, state, and lifecycle control.

IMPORTANT: Call get_state first to confirm session exists and whether it is paused.
Pause-required actions fail loudly when the session is running.

State tags: [SUSPENDED] = session must be paused. [ANY] = runs regardless.

Actions:
- get_state(session_id?) [ANY] → Current session state (paused/running), breakpoint, thread, line. CALL THIS FIRST.
- step_over(session_id?) [SUSPENDED] → Step over current line
- step_into(session_id?) [SUSPENDED] → Step into method call
- step_out(session_id?) [SUSPENDED] → Step out of current method
- force_step_into(session_id?) [SUSPENDED] → Step into even library/framework code (bypasses step filters — use to enter Spring proxies, CGLIB, reflection, or Kotlin inlined bodies)
- force_step_over(session_id?) [SUSPENDED] → Step over, ignoring any breakpoints in called methods
- run_to_cursor(file, line, session_id?) [SUSPENDED] → Run to specific line (despite the name, requires current suspension)
- resume(session_id?) [ANY] → Resume execution
- pause(session_id?) [ANY] → Best-effort pause. May take several seconds or fail if the JVM is in a non-suspendable state (native code, GC). Follow with get_state to confirm.
- stop(session_id?) [ANY] → Stop debug session

All actions accept optional session_id (defaults to active session).
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "get_state", "step_over", "step_into", "step_out",
                    "force_step_into", "force_step_over",
                    "resume", "pause", "run_to_cursor", "stop"
                )
            ),
            "session_id" to ParameterProperty(
                type = "string",
                description = "Debug session ID (optional — uses active session if omitted)"
            ),
            "file" to ParameterProperty(
                type = "string",
                description = "File path relative to project or absolute — for run_to_cursor"
            ),
            "line" to ParameterProperty(
                type = "integer",
                description = "1-based line number — for run_to_cursor"
            ),
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override fun documentation(): ToolDocumentation = toolDoc("debug_step") {
        summary {
            technical("Single-tool dispatcher for IntelliJ debug-session lifecycle: stepping (step_over/into/out + force variants), state inspection, run-to-cursor, resume, pause, and stop. Resolves the target XDebugSession through IdeStateProbe so agent-started and user-started sessions are both visible.")
            plain("The agent's debugger remote control. Once a debug session is paused at a breakpoint, the agent uses this tool to step through code line-by-line, walk into method calls, jump to a specific line, or stop the session — exactly like the buttons in IntelliJ's Debug toolbar, but driven by the LLM.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.IDE_MUTATION)
        counterfactual(
            "Without debug_step, no programmatic debugging — the LLM cannot drive a paused session. The fallback is asking the user to manually step in IntelliJ's Debug toolbar and report what they see, which fragments the agent loop into 5-10 chat-like exchanges per debug interaction (vs 1-3 tool calls today). Whole 'debug this failing test' workflow degrades to copy-paste."
        )
        llmMistake("Skips `get_state` and steps blindly — calls step_over on a running session, gets NOT_PAUSED, then has to call pause + get_state + step_over (3 tool calls instead of 1). Mitigation: 'CALL THIS FIRST' nudge in the description. Still the most common failure pattern.")
        llmMistake("Confuses `force_step_into` (bypasses step filters) with `force_step_over` (ignores breakpoints in callees) — picks the wrong one and gets unexpected pause locations.")
        llmMistake("Calls `step_out` from `main()` — session terminates with SESSION_ENDED, then LLM tries to step_out again on a dead session and loops on NO_SESSION.")
        llmMistake("Calls `run_to_cursor` on a running session expecting it to pause+go-to-line; instead gets NOT_PAUSED. The action name is misleading (it requires a paused session).")
        llmMistake("Calls `pause` and assumes it took effect immediately — proceeds to `step_over` before pause has actually landed, gets NOT_PAUSED. Pause needs `get_state` polling because it's best-effort.")
        flowchart("""
            flowchart TD
                A[LLM decides to debug] --> B[get_state — ALWAYS FIRST]
                B --> C{Session paused?}
                C -- no, running --> D{Want to pause?}
                D -- yes --> E[pause then get_state again]
                D -- no --> F[resume / stop / wait]
                C -- yes --> G{What to do?}
                G -- inspect --> H[debug_inspect get_variables]
                G -- step line --> I[step_over]
                G -- enter method --> J[step_into / force_step_into]
                G -- leave method --> K[step_out]
                G -- jump ahead --> L[run_to_cursor]
                G -- continue --> M[resume]
                G -- abort --> N[stop]
                I --> B
                J --> B
                K --> B
                L --> B
        """)
        actions {
            action("get_state") {
                description {
                    technical("Returns paused/running, current breakpoint id, thread name, file, line. Works on any session state.")
                    plain("Asks the debugger 'where are we?' — like checking which step of a recipe you're on. Always call this first; the LLM can't act on the debugger without knowing whether it's paused.")
                }
                whenLLMUses("At the start of every debugging interaction, and after every step/run-to-cursor to learn where the program now is.")
                params {
                    optional("session_id", "string") {
                        llmSeesIt("Debug session ID (optional — uses active session if omitted)")
                        humanReadable("Which debug session to query if there are multiple. Most projects only ever have one running at a time.")
                        whenPresent("That specific session is queried; an error is returned if it doesn't exist.")
                        whenAbsent("The currently active session is used (the one the user last interacted with in the Debug tool window).")
                    }
                }
                rejectsParam("file", "Only `run_to_cursor` reads `file` — `get_state` ignores it.")
                rejectsParam("line", "Only `run_to_cursor` reads `line` — `get_state` ignores it.")
                onSuccess("Returns a structured snapshot: state (paused|running), session id, thread, current frame's file + line + class + method, hit breakpoint (if any).")
                onFailure("session not found", "Returns a NO_SESSION error with a hint to start one via the Debug button or `runtime_exec(action=run_config, mode=debug)`.")
                onFailure("multiple sessions and no session_id", "Returns AMBIGUOUS_SESSION listing the candidates so the LLM can re-call with `session_id=...`.")
                example("first call in a debug interaction") {
                    param("action", "get_state")
                    outcome("Tool returns `Paused at MyClass.kt:42 in thread main`. Now LLM knows it can step or inspect.")
                }
                verdict {
                    keep("Without this, the LLM has to guess whether the session is paused before every step. That guess is wrong often enough to corrupt the debugging interaction.", VerdictSeverity.STRONG)
                }
            }
            action("step_over") {
                description {
                    technical("Advances execution one source-line, treating method calls as atomic.")
                    plain("Like reading the next line of a recipe, even if that line says 'now make a sauce' — you don't dive into the sauce sub-recipe.")
                }
                whenLLMUses("When tracing through a method to see what value each line produces, without descending into helper methods.")
                params {
                    optional("session_id", "string") {
                        llmSeesIt("Debug session ID (optional — uses active session if omitted)")
                        humanReadable("Same as get_state: which session to step in.")
                        whenPresent("That session steps; error if not paused.")
                        whenAbsent("Active session steps.")
                    }
                }
                rejectsParam("file", "Step actions don't take a target.")
                rejectsParam("line", "Step actions don't take a target.")
                precondition("session must be PAUSED — running or stopped sessions return an error")
                onSuccess("The debugger advances; tool returns `Stepped over to MyClass.kt:43`. Follow up with get_state to see the new state if you need more detail.")
                onFailure("session is running", "Returns NOT_PAUSED with a suggestion to call `pause` or wait for a breakpoint.")
                onFailure("session is stopped", "Returns NO_SESSION (the session was disposed between calls).")
                verdict {
                    keep("Most-used debugging primitive. No reasonable alternative.", VerdictSeverity.STRONG)
                }
            }
            action("step_into") {
                description {
                    technical("Advances one source-line, descending into the first method call on that line. Stops at the first executable line of the called method.")
                    plain("Like the recipe saying 'now make the sauce' — and you flip to the sauce page and start reading.")
                }
                whenLLMUses("When the LLM wants to see what a helper method actually does, especially when its return value is the suspect.")
                params {
                    optional("session_id", "string") {
                        llmSeesIt("Debug session ID (optional — uses active session if omitted)")
                        humanReadable("Same as step_over.")
                        whenPresent("That session steps in.")
                        whenAbsent("Active session steps in.")
                    }
                }
                rejectsParam("file", "Step actions don't take a target.")
                rejectsParam("line", "Step actions don't take a target.")
                precondition("session must be PAUSED")
                onSuccess("The debugger descends one frame; tool returns `Stepped into MyHelper.compute() at MyHelper.kt:18`.")
                onFailure("no method call on current line", "Behaviour depends on the JVM — usually equivalent to step_over. Tool reports the resulting line.")
                onFailure("session not paused", "NOT_PAUSED error.")
                verdict {
                    keep("Foundational. Without it the LLM can't enter helper methods at all.", VerdictSeverity.STRONG)
                }
            }
            action("step_out") {
                description {
                    technical("Resumes execution until the current method returns, then pauses at the call site in the caller.")
                    plain("'Finish the sauce and pop back to the main recipe.' Useful when you've descended too far and want to get back to the bigger picture.")
                }
                whenLLMUses("When the LLM has stepped into a method whose body it now understands and wants to see how the caller uses the return value.")
                params {
                    optional("session_id", "string") {
                        llmSeesIt("Debug session ID (optional — uses active session if omitted)")
                        humanReadable("Same as step_over.")
                        whenPresent("That session steps out.")
                        whenAbsent("Active session steps out.")
                    }
                }
                rejectsParam("file", "Step actions don't take a target.")
                rejectsParam("line", "Step actions don't take a target.")
                precondition("session must be PAUSED")
                precondition("there must be a caller frame — calling step_out at top of main() ends the session")
                onSuccess("Returns once the method has returned; tool reports the new pause line in the caller.")
                onFailure("session not paused", "NOT_PAUSED error.")
                onFailure("no caller frame", "Session ends and tool returns SESSION_ENDED.")
                verdict {
                    keep("Standard debugger primitive. Hard to debug without it.", VerdictSeverity.STRONG)
                }
            }
            action("force_step_into") {
                description {
                    technical("Like step_into, but ignores the IDE's step filters — descends into JDK, reflection, Spring proxies, CGLIB, Kotlin inlined bodies.")
                    plain("Same as step_into but ignores the 'don't bother me with library code' setting. Use when you suspect the bug is in framework code.")
                }
                whenLLMUses("When step_into appears to skip a method (because step filters hide it) and the LLM needs to see inside.")
                params {
                    optional("session_id", "string") {
                        llmSeesIt("Debug session ID (optional — uses active session if omitted)")
                        humanReadable("Same as step_into.")
                        whenPresent("That session force-steps in.")
                        whenAbsent("Active session force-steps in.")
                    }
                }
                rejectsParam("file", "Step actions don't take a target.")
                rejectsParam("line", "Step actions don't take a target.")
                precondition("session must be PAUSED")
                onSuccess("Descends into the next method even if it's library/framework code; tool reports the new file/line which may be inside the JDK or a 3rd-party JAR.")
                onFailure("session not paused", "NOT_PAUSED error.")
                verdict {
                    keep("Necessary for debugging Spring/Kotlin proxies and any case where step_into appears to skip frames. Cannot be merged with step_into via a `force` boolean cleanly because the JVM API exposes a distinct method.", VerdictSeverity.NORMAL)
                    drop("Mainly used by power users — the LLM picks step_into ~10x more often. Could plausibly be hidden behind a deferred 'advanced debug' tool.", VerdictSeverity.WEAK)
                }
            }
            action("force_step_over") {
                description {
                    technical("Like step_over, but ignores breakpoints inside any method called on the current line. Implemented as `stepOver(true)` — the boolean is whether breakpoints in callees should pause execution.")
                    plain("Step over, and ignore any breakpoints inside the methods called on this line. 'Don't stop me even if there's a trap inside the sub-recipe.'")
                }
                whenLLMUses("When the LLM is trying to advance past a line that calls deeply-instrumented code (e.g. with breakpoints set elsewhere) and doesn't want to detour.")
                params {
                    optional("session_id", "string") {
                        llmSeesIt("Debug session ID (optional — uses active session if omitted)")
                        humanReadable("Same as step_over.")
                        whenPresent("That session force-steps over.")
                        whenAbsent("Active session force-steps over.")
                    }
                }
                rejectsParam("file", "Step actions don't take a target.")
                rejectsParam("line", "Step actions don't take a target.")
                precondition("session must be PAUSED")
                onSuccess("Advances one source line; any breakpoints inside called methods do NOT pause; tool reports the new line.")
                onFailure("session not paused", "NOT_PAUSED error.")
                verdict {
                    keep("Distinct from `force_step_into` — different JVM API, different semantics. Hides breakpoints rather than reveals filtered frames.", VerdictSeverity.NORMAL)
                    drop("Could realistically be expressed as `step_over(force=true)` — would shrink the schema by one action. But that adds a param to the more-common step_over and changes its description, costing tokens on every iteration.", VerdictSeverity.WEAK)
                }
            }
            action("run_to_cursor") {
                description {
                    technical("Resumes execution until reaching the specified file:line, then pauses. Roughly equivalent to setting a temporary breakpoint there and resuming.")
                    plain("'Skip ahead to step 7 and pause there.' Useful when there's a loop you want to iterate through, or when you want to jump past setup code.")
                }
                whenLLMUses("When the LLM has read the code and knows the line where the bug surfaces — saves stepping through 50 unrelated lines.")
                params {
                    required("file", "string") {
                        llmSeesIt("File path relative to project or absolute — for run_to_cursor")
                        humanReadable("The file you want to pause inside.")
                        whenPresent("That file is resolved against project basePath and used as the target.")
                        constraint("must resolve via PathValidator (no traversal, must be inside an allow-listed root)")
                        example("src/main/kotlin/Service.kt")
                    }
                    required("line", "integer") {
                        llmSeesIt("1-based line number — for run_to_cursor")
                        humanReadable("Which line in that file to pause at.")
                        whenPresent("Execution resumes; the debugger pauses when it reaches `file:line`.")
                        constraint("must be ≥ 1")
                        example("87")
                    }
                    optional("session_id", "string") {
                        llmSeesIt("Debug session ID (optional — uses active session if omitted)")
                        humanReadable("Same as the other actions.")
                        whenPresent("That session runs to cursor.")
                        whenAbsent("Active session runs to cursor.")
                    }
                }
                precondition("session must be PAUSED")
                precondition("the target line must be reachable — if execution finishes without hitting it, the session ends and the tool returns SESSION_ENDED")
                onSuccess("Pauses at file:line; tool returns the actual pause location (which may differ from the request if the line is blank or in a comment).")
                onFailure("file not found", "Returns FILE_NOT_FOUND.")
                onFailure("session is running", "NOT_PAUSED — but the action description used to say 'run to cursor' which sounds like it should work on a running session. The IntelliJ API still requires a paused session.")
                onFailure("execution exits before reaching line", "SESSION_ENDED — common cause: line is in an unreachable branch.")
                verdict {
                    keep("Saves dozens of step calls when the LLM knows the target. Foundational for breakpoint-free targeted debugging.", VerdictSeverity.NORMAL)
                }
            }
            action("resume") {
                description {
                    technical("Continues execution from the current pause until the next breakpoint or session end.")
                    plain("'Press play.' Lets the program run normally again.")
                }
                whenLLMUses("After inspecting state at a breakpoint, when the LLM has gathered enough info and wants to let execution continue (often to hit a later breakpoint).")
                params {
                    optional("session_id", "string") {
                        llmSeesIt("Debug session ID (optional — uses active session if omitted)")
                        humanReadable("Same as the others.")
                        whenPresent("That session resumes.")
                        whenAbsent("Active session resumes.")
                    }
                }
                rejectsParam("file", "Resume has no target.")
                rejectsParam("line", "Resume has no target.")
                onSuccess("Tool returns immediately; the session continues. Subsequent get_state may show paused (next breakpoint hit) or running (no more breakpoints).")
                onFailure("session not running or paused", "NO_SESSION error.")
                verdict {
                    keep("Cannot be omitted — without it the agent can never let execution continue.", VerdictSeverity.STRONG)
                }
            }
            action("pause") {
                description {
                    technical("Best-effort suspension of a running session. Calls XDebugSession.pause() which may take seconds and may fail if the JVM is in a non-suspendable state (native code, GC, etc.).")
                    plain("'Press pause.' May not work instantly — the JVM has to be in a state where it can be safely interrupted.")
                }
                whenLLMUses("When the LLM wants to inspect a long-running operation that has no breakpoints but whose state mid-flight is interesting.")
                params {
                    optional("session_id", "string") {
                        llmSeesIt("Debug session ID (optional — uses active session if omitted)")
                        humanReadable("Same as the others.")
                        whenPresent("That session is asked to pause.")
                        whenAbsent("Active session is asked to pause.")
                    }
                }
                rejectsParam("file", "Pause has no target.")
                rejectsParam("line", "Pause has no target.")
                onSuccess("Returns immediately; the JVM will pause when it reaches a safe point. Always follow with get_state — the pause may take 1-5 seconds, or may not happen at all.")
                onFailure("session is in native code", "Pause may never trigger; subsequent get_state will still show running.")
                onFailure("session already paused", "No-op — returns success.")
                verdict {
                    keep("Useful enough for long-running ops. Required for any 'pause and inspect' workflow.", VerdictSeverity.NORMAL)
                    drop("Best-effort semantics make it unreliable. The LLM has to call get_state in a poll-like pattern — could be replaced by a 'pause and wait up to N seconds' helper.", VerdictSeverity.WEAK)
                }
            }
            action("stop") {
                description {
                    technical("Terminates the debug session. Wraps XDebugSession.stop().")
                    plain("'Stop the debugger.' Closes the debug session entirely.")
                }
                whenLLMUses("When debugging is done, before starting a new debug session, or when the LLM needs to free up resources.")
                params {
                    optional("session_id", "string") {
                        llmSeesIt("Debug session ID (optional — uses active session if omitted)")
                        humanReadable("Same as the others.")
                        whenPresent("That session is stopped.")
                        whenAbsent("Active session is stopped.")
                    }
                }
                rejectsParam("file", "Stop has no target.")
                rejectsParam("line", "Stop has no target.")
                onSuccess("Session is destroyed; tool returns confirmation.")
                onFailure("no session", "NO_SESSION — generally a no-op error since stopping nothing is harmless.")
                verdict {
                    keep("Required to end a session cleanly. Cannot be omitted.", VerdictSeverity.STRONG)
                }
            }
        }
        verdict {
            keep("10 actions consolidated into one tool keeps the schema lean — full debugging capability for the cost of a single tool definition. Action enum + state-tagged descriptions ([SUSPENDED]/[ANY]) gives the LLM enough context to choose correctly.", VerdictSeverity.STRONG)
        }
        mergeOpportunity("`force_step_into` and `force_step_over` could in theory be expressed as `step_into(force=true)` / `step_over(force=true)`. Net effect: -2 actions but +1 boolean param on the two most-used actions. Schema cost roughly breaks even; LLM clarity slightly worse. Not recommended.")
        observation("All 10 actions accept session_id but the LLM almost always uses the active session. The optional default is the right design — explicit session_id is essential when there are multiple sessions, but mandating it on every call would bloat every invocation.")
        observation("`run_to_cursor` is the only action requiring `file` + `line`. The current schema exposes those params at tool level, marked as 'for run_to_cursor'. Per-action filtering would require splitting the tool — not worth the schema cost.")
        related("debug_inspect", Relationship.COMPLEMENT, "Use after pause/step to inspect variables, evaluate expressions, get stack frames.")
        related("debug_breakpoints", Relationship.COMPLEMENT, "Set breakpoints first, then resume — debug_step navigates between them.")
        related("runtime_exec", Relationship.COMPOSE_WITH, "Use `runtime_exec(action=run_config, mode=debug)` to launch a debug session — debug_step then drives it.")
        downside("`pause` is best-effort and can take 1-5 seconds or fail silently if the JVM is in native code. The LLM has to call get_state to confirm.")
        downside("`run_to_cursor` requires the session to be paused, despite its name suggesting it should work on a running session. This is an IntelliJ Platform constraint, not ours.")
        downside("`step_out` from the top-level frame ends the session — the LLM occasionally trips on this when debugging short scripts.")
        narrative("debug_step")
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
            "get_state" -> executeGetState(params, project)
            "step_over" -> executeStepAction(params, project, "step_over") { it.stepOver(false) }
            "step_into" -> executeStepAction(params, project, "step_into") { it.stepInto() }
            "step_out" -> executeStepAction(params, project, "step_out") { it.stepOut() }
            "force_step_into" -> executeStepAction(params, project, "force_step_into") { it.forceStepInto() }
            "force_step_over" -> executeStepAction(params, project, "force_step_over") { it.stepOver(true) }
            "resume" -> executeResume(params, project)
            "pause" -> executePause(params, project)
            "run_to_cursor" -> executeRunToCursor(params, project)
            "stop" -> executeStop(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions: get_state, step_over, step_into, step_out, force_step_into, force_step_over, resume, pause, run_to_cursor, stop",
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
     * (resume, pause, stop, get_state).
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
     * Use this for actions that need the session to be suspended
     * (step_over, step_into, step_out, force_step_into, force_step_over, run_to_cursor).
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

    // ── get_state ───────────────────────────────────────────────────────────

    private suspend fun executeGetState(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content

        val session = when (val r = requireSession(project, sessionId)) {
            is SessionResolution.Found -> r.session
            is SessionResolution.Failed -> return r.toolResult
        }

        return try {
            val sb = StringBuilder()
            sb.append("Session: ${session.sessionName}\n")

            val isStopped = session.isStopped
            val isSuspended = session.isSuspended
            val pos = session.currentPosition

            val status = when {
                isStopped -> "STOPPED"
                isSuspended && pos != null -> {
                    val file = pos.file.name
                    val line = pos.line + 1
                    "PAUSED at $file:$line"
                }
                isSuspended -> "PAUSED"
                else -> "RUNNING"
            }
            sb.append("Status: $status\n")

            if (isSuspended) {
                sb.append("Reason: breakpoint\n")
            }

            val suspendContext = session.suspendContext
            if (suspendContext != null && isSuspended) {
                val activeStack = suspendContext.activeExecutionStack
                val allStacks = suspendContext.executionStacks

                val totalThreads = allStacks.size
                val suspendedCount = allStacks.size.coerceAtLeast(1)
                sb.append("Suspended threads: $suspendedCount of $totalThreads\n")

                if (activeStack != null) {
                    val threadName = activeStack.displayName
                    val frameDesc = if (pos != null) {
                        val currentFrame = session.currentStackFrame
                        val file = pos.file.name
                        val line = pos.line + 1
                        "$currentFrame".takeIf { it != "null" } ?: "$file:$line"
                    } else {
                        "unknown position"
                    }
                    sb.append("  $threadName (SUSPENDED) at $frameDesc\n")
                }

                allStacks.filter { it != activeStack }.take(5).forEach { stack ->
                    sb.append("  ${stack.displayName}\n")
                }
            }

            val content = sb.toString().trimEnd()
            ToolResult(content, "Debug state: $status", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error getting debug state: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── step_over / step_into / step_out ────────────────────────────────────

    private suspend fun executeStepAction(
        params: JsonObject,
        project: Project,
        actionName: String,
        action: (XDebugSession) -> Unit
    ): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        return executeStep(controller, project, sessionId, actionName, action)
    }

    // ── resume ──────────────────────────────────────────────────────────────

    private suspend fun executeResume(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content

        val session = when (val r = requireSession(project, sessionId)) {
            is SessionResolution.Found -> r.session
            is SessionResolution.Failed -> return r.toolResult
        }

        return try {
            withContext(Dispatchers.EDT) { session.resume() }
            val content = "Session resumed. Session: ${session.sessionName}"
            ToolResult(content, "Session resumed", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error resuming debug session: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── pause ───────────────────────────────────────────────────────────────

    private suspend fun executePause(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content

        val session = when (val r = requireSession(project, sessionId)) {
            is SessionResolution.Found -> r.session
            is SessionResolution.Failed -> return r.toolResult
        }

        return try {
            withContext(Dispatchers.EDT) { session.pause() }

            val name = session.sessionName
            // Try to get a registered ID for waitForPause; fall back to session name
            val registeredId = sessionId ?: controller.getActiveSessionId() ?: name
            val pauseEvent = controller.waitForPause(registeredId, 5000)

            val content = if (pauseEvent != null) {
                "Session paused at ${pauseEvent.file ?: "unknown"}:${pauseEvent.line ?: "?"}\nSession: $name"
            } else {
                "Pause requested. Session: $name"
            }

            ToolResult(content, "Pause requested", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error pausing debug session: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── run_to_cursor ───────────────────────────────────────────────────────

    private suspend fun executeRunToCursor(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("file")
        val line = params["line"]?.jsonPrimitive?.intOrNull ?: return ToolValidation.missingParam("line")
        val sessionId = params["session_id"]?.jsonPrimitive?.content

        if (line < 1) {
            return ToolResult("Line number must be >= 1, got: $line", "Invalid line", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val session = when (val r = requireSuspendedSession(project, sessionId)) {
            is SessionResolution.Found -> r.session
            is SessionResolution.Failed -> return r.toolResult
        }

        return try {
            val (absolutePath, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
            if (pathError != null) return pathError

            val position = withContext(Dispatchers.EDT) {
                val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath!!)
                    ?: return@withContext null
                XDebuggerUtil.getInstance().createPosition(vFile, line - 1)
            } ?: return ToolResult("File not found: $absolutePath", "File not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            withContext(Dispatchers.EDT) { session.runToPosition(position, false) }

            val name = session.sessionName
            // Try to get a registered ID for waitForPause; fall back to session name
            val registeredId = sessionId ?: controller.getActiveSessionId() ?: name
            val pauseEvent = controller.waitForPause(registeredId, 30000)

            val content = if (pauseEvent != null) {
                "Reached ${pauseEvent.file ?: "unknown"}:${pauseEvent.line ?: "?"}\nSession: $name"
            } else {
                // Pause event didn't arrive within the wait window, but the session may
                // actually be suspended already — the event flow can race against the IsSuspended
                // flag (audit finding C5). Re-check the current session state so the LLM gets
                // an accurate message and doesn't conclude "the action failed" when it didn't.
                val currentlySuspended = withContext(Dispatchers.EDT) {
                    session.isSuspended && session.currentStackFrame != null
                }
                val pos = withContext(Dispatchers.EDT) { session.currentPosition }
                val posStr = pos?.let { "${it.file.path}:${(it.line + 1)}" } ?: "unknown location"
                if (currentlySuspended) {
                    "Run to cursor requested ($filePath:$line). Pause event not observed within 30s, " +
                        "but the session is currently SUSPENDED at $posStr — the action likely succeeded; " +
                        "the wait simply didn't see the event in time.\nSession: $name"
                } else {
                    "Run to cursor requested ($filePath:$line). No pause within 30s and the session is " +
                        "not currently suspended — the target line was probably skipped (early return, " +
                        "exception, or a different code path). Consider `get_state` to confirm, then a " +
                        "fresh breakpoint at the desired line.\nSession: $name"
                }
            }

            ToolResult(content, "Run to cursor", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error running to cursor: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── stop ────────────────────────────────────────────────────────────────

    private suspend fun executeStop(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content

        val session = when (val r = requireSession(project, sessionId)) {
            is SessionResolution.Found -> r.session
            is SessionResolution.Failed -> return r.toolResult
        }

        return try {
            withContext(Dispatchers.EDT) { session.stop() }
            val content = "Debug session stopped. Session: ${session.sessionName}"
            ToolResult(content, "Debug session stopped", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error stopping debug session: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

}
