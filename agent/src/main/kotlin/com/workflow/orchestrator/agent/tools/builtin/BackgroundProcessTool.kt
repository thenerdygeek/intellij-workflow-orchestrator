package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.background.BackgroundHandle
import com.workflow.orchestrator.agent.tools.background.BackgroundPool
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.core.util.StringUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class BackgroundProcessTool : AgentTool {
    override val name = "background_process"
    override val isMutating: Boolean get() = true
    override val description = "Manage background processes spawned in this session via run_command(background: true). " +
        "With no args, lists all background processes in this session. " +
        "With an id and no action, returns status. " +
        "Actions: status, output, attach, send_stdin, kill. " +
        "Background processes are session-scoped — killed on session transitions."
    override val parameters: FunctionParameters = FunctionParameters(
        properties = linkedMapOf(
            "id" to ParameterProperty(type = "string",
                description = "Background process ID (e.g., bg_a1b2c3d4). Omit to list all processes."),
            "action" to ParameterProperty(type = "string",
                description = "Operation. If id is given without action, returns status. Omit both id and action to list all processes (equivalent to action=\"list\").",
                enumValues = listOf("list", "status", "output", "attach", "send_stdin", "kill")),
            "tail_lines" to ParameterProperty(type = "integer",
                description = "[output] Return last N lines."),
            "since_offset" to ParameterProperty(type = "integer",
                description = "[output] Return bytes after this offset."),
            "grep_pattern" to ParameterProperty(type = "string",
                description = "[output] Filter output lines matching this regex."),
            "output_file" to ParameterProperty(type = "boolean",
                description = "[output] When true, writes full output to disk and returns a preview + path."),
            "input" to ParameterProperty(type = "string",
                description = "[send_stdin] Text to send. Include \\n for Enter."),
            "timeout_seconds" to ParameterProperty(type = "integer",
                description = "[attach] Max seconds to wait in monitor loop. Default: 600, Max: 600."),
        ),
        required = emptyList(),
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    companion object {
        var currentSessionId: ThreadLocal<String?> = ThreadLocal.withInitial { null }
        val WRITE_ACTIONS = setOf("kill", "send_stdin", "attach")
    }

    override fun documentation(): ToolDocumentation = toolDoc("background_process") {
        summary {
            technical(
                "Single-tool dispatcher for managing background processes spawned via " +
                    "run_command(background=true). Backed by BackgroundPool keyed on the agent " +
                    "session id; handles are session-scoped and reaped on session transitions. " +
                    "Six actions: list (default when id is omitted), status (default when id is " +
                    "supplied without action), output (with offset/tail/grep/spill), attach " +
                    "(re-enter the blocking monitor loop with a timeout), send_stdin (write text " +
                    "to the process's stdin), kill (graceful SIGTERM → SIGKILL, idempotent)."
            )
            plain(
                "The agent's process manager — like tmux+ps+kill rolled into one tool. After " +
                    "you launch a long-running process in the background (a dev server, a file " +
                    "watcher, a `tail -f`), this tool lets you check what's still running, peek " +
                    "at its output, type input into it, wait for it to do something, or kill it. " +
                    "Without it, a background command would just keep running forever with no " +
                    "way to inspect or stop it."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.PROCESS_SPAWN)
        counterfactual(
            "Without background_process, the LLM has no programmatic handle on processes started " +
                "via run_command(background=true) — they would run unattended until the JVM " +
                "exits, with no way to tail logs, send input, or kill them. The fallback would be " +
                "platform-divergent shell tricks (`nohup`, `&`, `disown`, `kill <pid>` discovered " +
                "via `ps`/`pgrep`) which fail on Windows, leak processes across new chats, and " +
                "force every workflow with a server to either block the agent loop on a " +
                "synchronous run_command (loop dies waiting) or give up on long-running processes " +
                "entirely."
        )
        llmMistake(
            "Calls `output` (or any action) on a bgId from a previous chat — background processes " +
                "are session-scoped and reaped on new chat. Returns NO_SUCH_ID_IN_SESSION; the LLM " +
                "should re-launch via run_command(background=true) instead of retrying the stale id."
        )
        llmMistake(
            "Forgets to `kill` a background process before launching a replacement (e.g. a dev " +
                "server) — port stays bound, the new launch fails with 'address in use'. Always " +
                "list+kill before respawning a server-shaped process."
        )
        llmMistake(
            "Uses `send_stdin` here when the process was spawned via the legacy run_command idle " +
                "path that returned a `process_id` (handled by the separate `send_stdin` tool, " +
                "which uses ProcessRegistry, not BackgroundPool). The two id namespaces look " +
                "alike but are not interchangeable: bgId starts with `bg_`, the legacy id does " +
                "not. Pick the tool by the id prefix."
        )
        llmMistake(
            "Calls `attach` with no timeout expecting it to block forever. The default is 600s " +
                "and the param caps at 600s; longer waits require re-attaching in a loop and " +
                "checking state between calls."
        )
        llmMistake(
            "Uses `output` repeatedly without passing `since_offset` from the previous call's " +
                "`offset=` header — re-reads the entire output buffer each time and burns context " +
                "tokens. The LLM should thread `nextOffset` through to incremental polls."
        )
        llmMistake(
            "Calls `send_stdin` for password/token prompts — there is no password-detection " +
                "guard on this code path (unlike the standalone `send_stdin` tool). Use " +
                "ask_user_input for credentials."
        )
        flowchart(
            """
            stateDiagram-v2
                [*] --> Spawned: run_command(background=true)
                Spawned --> Running: process up
                Running --> Running: list / status / output (read-only)
                Running --> Running: send_stdin (writes input)
                Running --> Idle: attach hits idle threshold
                Idle --> Running: more output arrives
                Running --> Exited: process completes naturally
                Running --> Killed: kill action (SIGTERM→SIGKILL)
                Idle --> Exited: process completes naturally
                Idle --> Killed: kill action
                Exited --> [*]: removed from pool
                Killed --> [*]: removed from pool
            """
        )
        actions {
            action("list") {
                description {
                    technical(
                        "Returns one row per background handle in the current session: bgId, " +
                            "kind, truncated label, state, runtime, output bytes, exit code (or — " +
                            "if still running). This is the implicit default when neither id nor " +
                            "action is provided."
                    )
                    plain(
                        "Asks 'what's running in the background right now?' — like `ps` filtered " +
                            "to just the agent's own children. Use it to find the bgId before " +
                            "doing anything else."
                    )
                }
                whenLLMUses(
                    "At the start of any background-process interaction, after a 'New Chat' to " +
                        "confirm the session is empty, or when the LLM has lost track of which " +
                        "bgIds it spawned earlier in the conversation."
                )
                rejectsParam("id", "List ignores id — supply no id to list, supply an id to address a single process.")
                rejectsParam("action", "When action is omitted and id is omitted, list is the implicit default; passing action=list explicitly is also valid.")
                rejectsParam("tail_lines", "Output-shaping params apply only to `output`.")
                rejectsParam("since_offset", "Output-shaping params apply only to `output`.")
                rejectsParam("grep_pattern", "Output-shaping params apply only to `output`.")
                rejectsParam("output_file", "Output-shaping params apply only to `output`.")
                rejectsParam("input", "Stdin only applies to `send_stdin`.")
                rejectsParam("timeout_seconds", "Timeout only applies to `attach`.")
                onSuccess(
                    "Returns `Background processes (N):` followed by one tabular row per handle. " +
                        "Returns `No background processes in this session.` (token-cheap) when the " +
                        "pool for this session is empty."
                )
                onFailure(
                    "session id missing",
                    "NO_SESSION_CONTEXT — AgentLoop forgot to populate the ThreadLocal. Indicates " +
                        "a wiring bug, not an LLM-recoverable error. Surface to the user."
                )
                example("first call after spawning a server") {
                    outcome(
                        "Tool returns a single row: `bg_a1b2c3d4  run_command  ./gradlew bootRun  " +
                            "RUNNING  12s  out=4321B  exit=—`. LLM now knows the bgId for follow-ups."
                    )
                }
                verdict {
                    keep(
                        "Without list there's no way to discover bgIds across iterations — the " +
                            "LLM would have to remember every id it ever spawned. Cheap (~10 " +
                            "tokens for the empty case) and load-bearing.",
                        VerdictSeverity.STRONG
                    )
                }
            }
            action("status") {
                description {
                    technical(
                        "Per-process snapshot: bgId, kind, label, state, exit code (or —), " +
                            "runtime, output byte count, last 5 lines. Implicit default when id " +
                            "is supplied without an explicit action."
                    )
                    plain(
                        "Like asking 'how's process X doing?' — returns its state plus a tiny " +
                            "tail of its recent output, just enough to tell whether it's stuck or " +
                            "making progress."
                    )
                }
                whenLLMUses(
                    "When the LLM has a known bgId and wants a quick liveness + last-5-lines " +
                        "snapshot, without paying for the full output buffer."
                )
                params {
                    required("id", "string") {
                        llmSeesIt("Background process ID (e.g., bg_a1b2c3d4). Omit to list all processes.")
                        humanReadable("The handle id returned when the background process was spawned (or surfaced by `list`).")
                        whenPresent("That handle is fetched from BackgroundPool; status returned if found.")
                        constraint("must be a bgId belonging to the current session")
                        example("bg_a1b2c3d4")
                    }
                }
                rejectsParam("tail_lines", "Status hard-codes the last-5-lines preview; use `output` for arbitrary tails.")
                rejectsParam("since_offset", "Status doesn't accept offsets; use `output`.")
                rejectsParam("grep_pattern", "Status doesn't filter; use `output`.")
                rejectsParam("output_file", "Status doesn't spill; use `output(output_file=true)`.")
                rejectsParam("input", "Stdin only applies to `send_stdin`.")
                rejectsParam("timeout_seconds", "Timeout only applies to `attach`.")
                onSuccess(
                    "Multi-line snapshot ending in a tail of the last 5 lines, indented two spaces."
                )
                onFailure(
                    "id not in this session",
                    "Returns NO_SUCH_ID_IN_SESSION with both the requested id and the session id. " +
                        "Most common cause: stale bgId from a prior chat. Recover by calling `list`."
                )
                example("polling a running build") {
                    param("id", "bg_a1b2c3d4")
                    outcome(
                        "Returns the bgId, kind=run_command, label, State=RUNNING, Exit=—, " +
                            "Runtime=23s, Output=18432 bytes, plus the last 5 lines of stdout."
                    )
                }
                verdict {
                    keep(
                        "Token-efficient liveness check — much cheaper than dumping the whole " +
                            "output buffer when the LLM only needs to know 'is it still alive?'.",
                        VerdictSeverity.NORMAL
                    )
                }
            }
            action("output") {
                description {
                    technical(
                        "Returns the captured stdout/stderr buffer with optional shaping: " +
                            "since_offset (byte cursor for incremental polling), tail_lines " +
                            "(return last N lines from the available window), grep_pattern " +
                            "(regex line-filter applied after slicing), output_file (write full " +
                            "content to disk and return preview + path). Header line carries " +
                            "`offset=<nextOffset>, bytes=<totalBytes>` so the LLM can thread the " +
                            "cursor on subsequent calls."
                    )
                    plain(
                        "Reads what the process has printed — like opening its log file. You can " +
                            "ask for just the last few lines, only lines matching a pattern, " +
                            "everything since you last checked, or dump the whole thing to disk " +
                            "for later searching."
                    )
                }
                whenLLMUses(
                    "After spawning a long-running process, when polling for progress, when a " +
                        "build is hanging and the LLM needs the recent output, or when grepping " +
                            "for an error pattern."
                )
                params {
                    required("id", "string") {
                        llmSeesIt("Background process ID (e.g., bg_a1b2c3d4). Omit to list all processes.")
                        humanReadable("The handle id to read from.")
                        whenPresent("That handle's buffer is read.")
                        constraint("must be a bgId belonging to the current session")
                        example("bg_a1b2c3d4")
                    }
                    optional("tail_lines", "integer") {
                        llmSeesIt("[output] Return last N lines.")
                        humanReadable("Cap the response to just the last N lines of available output — like `tail -n N`.")
                        whenPresent("Only the last N lines (after offset slicing) are returned.")
                        whenAbsent("Full window from since_offset to end is returned.")
                        constraint("non-negative integer")
                        example("50")
                    }
                    optional("since_offset", "integer") {
                        llmSeesIt("[output] Return bytes after this offset.")
                        humanReadable("Byte cursor for incremental polling — pass back the previous call's `offset=` header value to get only new bytes.")
                        whenPresent("Only bytes at offset ≥ since_offset are returned.")
                        whenAbsent("Defaults to 0 — full buffer is read.")
                        constraint("must be ≥ 0; must be ≤ current output bytes (else returns empty content)")
                        example("4321")
                    }
                    optional("grep_pattern", "string") {
                        llmSeesIt("[output] Filter output lines matching this regex.")
                        humanReadable("Java regex applied per line after offset/tail slicing — only matching lines are returned.")
                        whenPresent("Only lines containing a regex match are kept; non-matching lines drop.")
                        whenAbsent("All lines in the window are returned unfiltered.")
                        constraint("must be a valid Java Regex; INVALID_GREP_PATTERN if it fails to compile")
                        example("(?i)error|exception")
                    }
                    optional("output_file", "boolean") {
                        llmSeesIt("[output] When true, writes full output to disk and returns a preview + path.")
                        humanReadable("When true, the full content is spilled to disk under the session's tool-output dir and the response carries a head/tail preview plus the file path.")
                        whenPresent("Full content written to disk; tool returns a small preview + the spill path.")
                        whenAbsent("Inline content returned (subject to AgentLoop's 30K auto-spill safety net).")
                        example("true")
                    }
                }
                rejectsParam("input", "Stdin only applies to `send_stdin`.")
                rejectsParam("timeout_seconds", "Timeout only applies to `attach`.")
                onSuccess(
                    "Header line `[bgId=<id>] offset=<next>, bytes=<total>` followed by the " +
                        "shaped content (sliced/filtered as requested). isError=false even when " +
                        "the body is empty — empty output is a valid signal."
                )
                onFailure(
                    "invalid grep regex",
                    "Returns INVALID_GREP_PATTERN with the offending pattern echoed back so the LLM can fix it."
                )
                onFailure(
                    "id not in this session",
                    "NO_SUCH_ID_IN_SESSION — recover via `list`."
                )
                example("incremental poll after a known offset") {
                    param("id", "bg_a1b2c3d4")
                    param("since_offset", "4321")
                    outcome("Returns only the new bytes printed since the previous call. Updated `offset=` header threads forward to the next poll.")
                }
                example("grep for errors only") {
                    param("id", "bg_a1b2c3d4")
                    param("grep_pattern", "(?i)error|exception")
                    outcome("Returns only lines matching the regex; useful for triaging a noisy build log.")
                }
                example("spill the full log to disk") {
                    param("id", "bg_a1b2c3d4")
                    param("output_file", "true")
                    outcome("Full output written to {sessionDir}/tool-output/<file>; tool returns a preview + path. LLM can follow up with read_file/search_code on the path.")
                }
                verdict {
                    keep(
                        "The only way to read what a background process has emitted. The four " +
                            "shaping params (tail_lines / since_offset / grep_pattern / " +
                            "output_file) collapse what would otherwise be 4 separate actions or " +
                            "4 separate read_file-style follow-ups into one call.",
                        VerdictSeverity.STRONG
                    )
                }
            }
            action("attach") {
                description {
                    technical(
                        "Suspends the agent loop and re-enters BackgroundHandle.attach()'s " +
                            "monitor loop with the supplied timeout (capped at 600s). Returns " +
                            "Exited (process completed), Idle (idle-stdout threshold elapsed " +
                            "with classification reason like LIKELY_STDIN_PROMPT), or " +
                            "AttachTimeout (timeout elapsed with process still RUNNING). " +
                            "Equivalent to running run_command synchronously after the fact."
                    )
                    plain(
                        "'Wait for this process to do something interesting, with a timeout.' " +
                            "Like reattaching to a tmux session and watching it. Useful when the " +
                            "LLM spawned a process eagerly but now wants to wait for a specific " +
                            "milestone (server ready, command finished, prompt appeared)."
                    )
                }
                whenLLMUses(
                    "When the LLM needs to wait for a background process to finish or hit an " +
                        "idle/prompt state, but doesn't want to busy-poll with `output` calls."
                )
                params {
                    required("id", "string") {
                        llmSeesIt("Background process ID (e.g., bg_a1b2c3d4). Omit to list all processes.")
                        humanReadable("The handle id to attach to.")
                        whenPresent("That handle's monitor loop is re-entered.")
                        constraint("must be a bgId belonging to the current session")
                        example("bg_a1b2c3d4")
                    }
                    optional("timeout_seconds", "integer") {
                        llmSeesIt("[attach] Max seconds to wait in monitor loop. Default: 600, Max: 600.")
                        humanReadable("How long to wait before returning AttachTimeout — capped at 10 minutes to keep the agent loop responsive.")
                        whenPresent("Wait at most this many seconds; coerced into [1, 600].")
                        whenAbsent("Defaults to 600s.")
                        constraint("coerced to [1, 600]; values outside are silently clamped")
                        example("60")
                    }
                }
                rejectsParam("tail_lines", "Output-shaping params apply only to `output`.")
                rejectsParam("since_offset", "Output-shaping params apply only to `output`.")
                rejectsParam("grep_pattern", "Output-shaping params apply only to `output`.")
                rejectsParam("output_file", "Output-shaping params apply only to `output`.")
                rejectsParam("input", "Stdin only applies to `send_stdin`.")
                precondition("the handle must still exist in the session pool — Exited/Killed handles return NO_SUCH_ID_IN_SESSION")
                onSuccess(
                    "Returns one of three outcomes: `Exit code: <code>\\n<output>` on natural " +
                        "completion (isError=false); `[IDLE] <reason>\\n<lastOutput>` when the " +
                        "idle classifier fires (isError=false — typically a stdin prompt); " +
                        "`[ATTACH_TIMEOUT] <bgId> still RUNNING after <N>s.\\nLast output:\\n…` " +
                        "(isError=true) when the deadline hits with the process still going."
                )
                onFailure(
                    "process not found",
                    "NO_SUCH_ID_IN_SESSION — same recovery as the other actions."
                )
                onFailure(
                    "process disappears mid-attach",
                    "Returns Exited with the recovered exit code if the underlying handle reports it."
                )
                example("wait for a build to finish, with a 5-minute cap") {
                    param("id", "bg_a1b2c3d4")
                    param("timeout_seconds", "300")
                    outcome("Either returns Exit + output (build done) or ATTACH_TIMEOUT (still building after 5min — let the LLM decide whether to keep waiting or kill).")
                }
                verdict {
                    keep(
                        "Cleanly converts a background spawn back into a synchronous wait — " +
                            "essential for 'launch then wait for ready' workflows. Far cheaper " +
                            "in tokens and wall time than polling output every few seconds.",
                        VerdictSeverity.STRONG
                    )
                }
            }
            action("send_stdin") {
                description {
                    technical(
                        "Writes the supplied `input` to the underlying process's stdin via " +
                            "BackgroundHandle.sendStdin. Returns SEND_STDIN_FAILED if the write " +
                            "returns false (process exited before bytes flushed) or " +
                            "UNSUPPORTED_FOR_KIND if the handle's kind doesn't support stdin " +
                            "(non-process handles like XDebugSession). Does NOT enter a monitor " +
                            "loop — to wait for the process to react, follow with `attach` or " +
                            "`output`."
                    )
                    plain(
                        "Types something into the process — like clicking on its window and " +
                            "pressing keys. Useful for confirmation prompts ('y/n'), menu " +
                            "selections, or REPLs. Remember to include `\\n` for Enter."
                    )
                }
                whenLLMUses(
                    "When `attach` returned [IDLE] with a stdin-shaped reason and the LLM can " +
                        "infer the right answer (yes/no, a menu number, a known config value)."
                )
                params {
                    required("id", "string") {
                        llmSeesIt("Background process ID (e.g., bg_a1b2c3d4). Omit to list all processes.")
                        humanReadable("The handle id to write to.")
                        whenPresent("Stdin is written to that handle's process.")
                        constraint("must be a bgId belonging to the current session and a kind that supports stdin")
                        example("bg_a1b2c3d4")
                    }
                    required("input", "string") {
                        llmSeesIt("[send_stdin] Text to send. Include \\n for Enter.")
                        humanReadable("Exact bytes (after escape processing) to write. The LLM controls newlines explicitly — `y` does not press Enter, `y\\n` does.")
                        whenPresent("Those bytes are written to the process's stdin pipe.")
                        constraint("non-empty; the LLM is responsible for trailing newlines")
                        example("y\\n")
                        example("2\\n")
                    }
                }
                rejectsParam("tail_lines", "Output-shaping params apply only to `output`.")
                rejectsParam("since_offset", "Output-shaping params apply only to `output`.")
                rejectsParam("grep_pattern", "Output-shaping params apply only to `output`.")
                rejectsParam("output_file", "Output-shaping params apply only to `output`.")
                rejectsParam("timeout_seconds", "send_stdin doesn't wait — chain with `attach` for a follow-up wait.")
                precondition("process must be alive — exited handles cannot accept stdin")
                precondition("handle kind must support stdin — currently only run_command-spawned process handles do")
                precondition(
                    "no password-prompt detection on this code path — unlike the standalone " +
                        "send_stdin tool. Caller must not send credentials. Use ask_user_input " +
                        "for any password/token/secret prompt."
                )
                onSuccess("Returns `Wrote <N> chars to <bgId> stdin.` immediately; does not wait for process reaction.")
                onFailure(
                    "kind doesn't support stdin",
                    "UNSUPPORTED_FOR_KIND — non-process background handles (future XDebugSession, HTTP, etc.) reject stdin."
                )
                onFailure(
                    "process exited mid-write",
                    "SEND_STDIN_FAILED with hint that the process may have exited. Recover by calling `status` to confirm exit code."
                )
                onFailure(
                    "input param missing",
                    "MISSING_INPUT — schema-level guard."
                )
                example("answer a y/n prompt") {
                    param("id", "bg_a1b2c3d4")
                    param("input", "y\\n")
                    outcome("Process receives `y\\n` on stdin. Follow up with `attach` or `output(since_offset=…)` to see the reaction.")
                }
                verdict {
                    keep(
                        "Necessary for any background process that hits an interactive prompt. " +
                            "Without it, an idle stdin-prompt is a dead-end and the LLM can " +
                            "only kill+respawn with a non-interactive flag.",
                        VerdictSeverity.NORMAL
                    )
                    drop(
                        "There is a separate top-level `send_stdin` tool for run_command's idle " +
                            "path (uses ProcessRegistry, not BackgroundPool). The two id " +
                            "namespaces are distinct, but having two tools that both 'send " +
                            "stdin' is confusing for the LLM. The right consolidation is " +
                            "probably to delete the standalone tool and route all stdin through " +
                            "this action — see auditNotes.",
                        VerdictSeverity.WEAK
                    )
                }
            }
            action("kill") {
                description {
                    technical(
                        "Two-phase graceful kill via BackgroundHandle.kill (SIGTERM → wait → " +
                            "SIGKILL, idempotent at the handle level). On success, also removes " +
                            "the bgId from the session's pool so subsequent list/status calls " +
                            "don't show it. medium-risk approval gate — surfaces a per-invocation " +
                            "approval to the user."
                    )
                    plain(
                        "'Stop this process — politely first, then forcefully.' Like clicking " +
                            "the red square in the IDE's Run window. Use when a process is hung, " +
                            "a server needs restarting, or you're done with it."
                    )
                }
                whenLLMUses(
                    "When a server needs replacing on the same port, when a hung build needs " +
                        "interrupting, or as cleanup before completing the task."
                )
                params {
                    required("id", "string") {
                        llmSeesIt("Background process ID (e.g., bg_a1b2c3d4). Omit to list all processes.")
                        humanReadable("The handle id to kill.")
                        whenPresent("Graceful-then-forceful kill is sent; pool entry is removed on success.")
                        constraint("must be a bgId belonging to the current session")
                        example("bg_a1b2c3d4")
                    }
                }
                rejectsParam("tail_lines", "Output-shaping params apply only to `output`.")
                rejectsParam("since_offset", "Output-shaping params apply only to `output`.")
                rejectsParam("grep_pattern", "Output-shaping params apply only to `output`.")
                rejectsParam("output_file", "Output-shaping params apply only to `output`.")
                rejectsParam("input", "Stdin only applies to `send_stdin`.")
                rejectsParam("timeout_seconds", "Kill uses BackgroundHandle's internal timeouts; no per-call override exposed.")
                precondition("approval gate — kill is medium-risk and must be approved (or session-allowed)")
                onSuccess(
                    "Returns `Killed <bgId>` (isError=false) and removes the handle from the " +
                        "session pool. Subsequent `list` no longer shows the bgId."
                )
                onFailure(
                    "kill returns false",
                    "Returns `Kill failed for <bgId>` with isError=true. Most common cause: the " +
                        "underlying process was already terminal at the time of the call (race). " +
                        "LLM should call `status` to confirm before retrying."
                )
                onFailure(
                    "user denies approval",
                    "Returns `Tool execution denied by user.` — the LLM should respect the deny and try a different approach (e.g., ask the user how to proceed)."
                )
                onFailure(
                    "id not in this session",
                    "NO_SUCH_ID_IN_SESSION — almost always a stale id."
                )
                example("kill a hung dev server before respawning") {
                    param("id", "bg_a1b2c3d4")
                    outcome("Approval prompt fires; on approval, process is SIGTERM'd, waited briefly, then SIGKILL'd if needed; pool entry removed.")
                }
                verdict {
                    keep(
                        "Without kill, every long-running spawn leaks until session end. The " +
                            "approval gate prevents accidental termination of user-facing " +
                            "processes (e.g., a server the user is also using).",
                        VerdictSeverity.STRONG
                    )
                }
            }
        }
        verdict {
            keep(
                "Six actions, one schema slot, ~250 tokens of description — covers the entire " +
                    "background-process lifecycle (discover, observe, interact, wait, terminate). " +
                    "Splitting into 6 sibling tools would cost ~1200 tokens every iteration even " +
                    "when no processes are running. The action-enum keeps the cost flat.",
                VerdictSeverity.STRONG
            )
        }
        mergeOpportunity(
            "The standalone `send_stdin` tool overlaps significantly with this tool's `send_stdin` " +
                "action. They use different id namespaces (ProcessRegistry vs BackgroundPool) " +
                "but both 'write text to a process's stdin'. Worth investigating whether " +
                "BackgroundPool can subsume ProcessRegistry's idle-prompt path so all stdin " +
                "writes flow through `background_process(action=send_stdin)`. " +
                "Trade-off: the standalone tool has password-prompt detection and a max-stdin " +
                "rate limit that this action does not — those guards would need to migrate. " +
                "See `relatedTools.send_stdin` for context."
        )
        observation(
            "All seven params are exposed at tool level even though each action consumes a " +
                "narrow subset (e.g., `input` only applies to send_stdin, `timeout_seconds` only " +
                "to attach). The rejectsParam table on each action documents this — splitting " +
                "the schema per action would require splitting the tool, which costs more in " +
                "schema tokens than the rejection table costs in description tokens."
        )
        observation(
            "When `id` is supplied without `action`, the tool falls through to `status` (default " +
                "behavior). When neither is supplied, it falls through to `list`. This is " +
                "documented in the description but the LLM occasionally calls action=status " +
                "explicitly anyway — harmless, just slightly more tokens."
        )
        observation(
            "Approval gating is action-scoped: only `kill`, `send_stdin`, and `attach` go " +
                "through requestApproval (kill=medium, others=low). list/status/output are " +
                "treated as read-only and skip the gate."
        )
        related(
            "run_command",
            Relationship.COMPOSE_WITH,
            "Spawns the background process via run_command(background=true), which returns the " +
                "bgId that this tool addresses. The two are paired: run_command starts, " +
                "background_process manages."
        )
        related(
            "send_stdin",
            Relationship.ALTERNATIVE,
            "A separate top-level tool that writes stdin to processes spawned via run_command's " +
                "synchronous-then-idle path (different id namespace — ProcessRegistry, not " +
                "BackgroundPool). Has password-prompt detection and stdin rate limiting that this " +
                "tool's send_stdin action lacks. Pick by id prefix: `bg_…` → use this tool."
        )
        related(
            "runtime_exec",
            Relationship.ALTERNATIVE,
            "For IDE-managed processes (run configurations) the canonical path is " +
                "runtime_exec(action=run_config) + stop_run_config — those processes live in " +
                "ExecutionManagerImpl, not BackgroundPool, and survive across sessions. Use " +
                "this tool only for shell-spawned long-running processes."
        )
        downside(
            "Process state is per-session and lost on new chat (`AgentController.newChat()` " +
                "cascades through SessionDisposableHolder and reaps BackgroundPool entries). " +
                "Background spawns do NOT survive a session reset."
        )
        downside(
            "Output buffer has limits — `OutputCollector` in run_command applies head/tail " +
                "truncation when the buffer exceeds its cap. Long-running processes effectively " +
                "lose their middle history; LLM must `output` periodically to keep up if it " +
                "needs all bytes."
        )
        downside(
            "No signal API beyond kill — there's no SIGHUP / SIGUSR1 / SIGINT helper. If a " +
                "process needs a config reload signal or a graceful-only stop, the LLM must " +
                "shell out via run_command(`kill -HUP <pid>`) — but the pid isn't directly " +
                "exposed by this tool. Workaround: `runtime_exec(action=get_running_processes)`."
        )
        downside(
            "`send_stdin` here has no password-prompt guardrail (unlike the standalone " +
                "send_stdin tool). The LLM is solely responsible for not sending credentials."
        )
        downside(
            "Kill is two-phase but the per-phase timeouts are baked into BackgroundHandle and " +
                "not exposed at the tool layer — no way to ask for SIGKILL-only or to extend " +
                "the SIGTERM grace period."
        )
        downside(
            "`attach`'s 600-second cap is hard-clamped — for genuinely long waits, the LLM " +
                "must loop attach calls and re-check state between iterations, which costs " +
                "extra context tokens."
        )
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val sessionId = currentSessionId.get()
            ?: return toolError("NO_SESSION_CONTEXT: session id not set by AgentLoop")
        val pool = BackgroundPool.getInstance(project)
        val id = params["id"]?.jsonPrimitive?.content
        val action = params["action"]?.jsonPrimitive?.content ?: if (id != null) "status" else "list"

        // Gate write actions through the approval machinery. READ actions (list, status,
        // output) are observational and skip the gate entirely. When AgentLoop wraps this
        // tool in ApprovalGatedTool the requestApproval call is routed to the UI gate;
        // in tests (no gate wired) the default implementation returns APPROVED.
        if (action in WRITE_ACTIONS) {
            val approval = requestApproval(
                toolName = "$name.$action",
                args = params.toString(),
                riskLevel = if (action == "kill") "medium" else "low",
                allowSessionApproval = true,
            )
            if (approval == ApprovalResult.DENIED) {
                return toolError("Tool execution denied by user.")
            }
        }

        return when (action) {
            "list" -> doList(pool, sessionId)
            "status" -> {
                val h = pool.get(sessionId, id!!)
                    ?: return toolError("NO_SUCH_ID_IN_SESSION: '$id' not in session '$sessionId'")
                doStatus(h)
            }
            "output" -> {
                val h = pool.get(sessionId, id!!)
                    ?: return toolError("NO_SUCH_ID_IN_SESSION: '$id' not in session '$sessionId'")
                doOutput(h, params)
            }
            "attach" -> {
                val h = pool.get(sessionId, id!!)
                    ?: return toolError("NO_SUCH_ID_IN_SESSION: '$id' not in session '$sessionId'")
                doAttach(h, params)
            }
            "send_stdin" -> {
                val h = pool.get(sessionId, id!!)
                    ?: return toolError("NO_SUCH_ID_IN_SESSION: '$id' not in session '$sessionId'")
                doSendStdin(h, params)
            }
            "kill" -> {
                val h = pool.get(sessionId, id!!)
                    ?: return toolError("NO_SUCH_ID_IN_SESSION: '$id' not in session '$sessionId'")
                doKill(pool, sessionId, h)
            }
            else -> toolError("UNSUPPORTED_ACTION: '$action' — expected one of: status, output, attach, send_stdin, kill")
        }
    }

    private fun doList(pool: BackgroundPool, sessionId: String): ToolResult {
        val handles = pool.list(sessionId)
        if (handles.isEmpty()) {
            return ToolResult(
                content = "No background processes in this session.",
                summary = "No bg processes",
                tokenEstimate = 10,
            )
        }
        val rows = handles.joinToString("\n") { h ->
            val exit = h.exitCode()?.toString() ?: "—"
            "${h.bgId}  ${h.kind}  ${StringUtils.truncate(h.label, 60)}  ${h.state()}  " +
                "${formatRuntime(h.runtimeMs())}  out=${h.outputBytes()}B  exit=$exit"
        }
        val content = "Background processes (${handles.size}):\n$rows"
        return ToolResult(
            content = content,
            summary = "${handles.size} bg processes",
            tokenEstimate = TokenEstimator.estimate(content),
        )
    }

    private fun doStatus(h: BackgroundHandle): ToolResult {
        val exit = h.exitCode()?.toString() ?: "—"
        val tail = h.readOutput(tailLines = 5).content.lines().joinToString("\n") { "  $it" }
        val content = buildString {
            appendLine("Background process: ${h.bgId}")
            appendLine("Kind: ${h.kind}")
            appendLine("Label: ${h.label}")
            appendLine("State: ${h.state()}")
            appendLine("Exit code: $exit")
            appendLine("Runtime: ${formatRuntime(h.runtimeMs())}")
            appendLine("Output: ${h.outputBytes()} bytes")
            appendLine("Last 5 lines:")
            append(tail)
        }
        return ToolResult(
            content = content,
            summary = "${h.bgId}: ${h.state()}",
            tokenEstimate = TokenEstimator.estimate(content),
        )
    }

    private fun doOutput(h: BackgroundHandle, params: JsonObject): ToolResult {
        val tailLines = params["tail_lines"]?.jsonPrimitive?.content?.toIntOrNull()
        val sinceOffset = params["since_offset"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val grepPattern = params["grep_pattern"]?.jsonPrimitive?.content

        val chunk = h.readOutput(sinceOffset = sinceOffset, tailLines = tailLines)
        var content = chunk.content
        if (grepPattern != null) {
            val re = runCatching { Regex(grepPattern) }.getOrNull()
                ?: return toolError("INVALID_GREP_PATTERN: $grepPattern")
            content = content.lines().filter { re.containsMatchIn(it) }.joinToString("\n")
        }
        val header = "[bgId=${h.bgId}] offset=${chunk.nextOffset}, bytes=${h.outputBytes()}\n"
        val body = header + content
        return ToolResult(
            content = body,
            summary = "${h.bgId}: ${content.lineSequence().count()} lines",
            tokenEstimate = TokenEstimator.estimate(body),
            isError = false,
        )
    }

    private suspend fun doAttach(h: BackgroundHandle, params: JsonObject): ToolResult {
        val seconds = (params["timeout_seconds"]?.jsonPrimitive?.content?.toLongOrNull() ?: 600L)
            .coerceIn(1, 600)
        val result = h.attach(seconds * 1000)
        val content = when (result) {
            is com.workflow.orchestrator.agent.tools.background.AttachResult.Exited ->
                "Exit code: ${result.exitCode}\n${result.output}"
            is com.workflow.orchestrator.agent.tools.background.AttachResult.Idle ->
                "[IDLE] ${result.reason}\n${result.lastOutput}"
            is com.workflow.orchestrator.agent.tools.background.AttachResult.AttachTimeout ->
                "[ATTACH_TIMEOUT] ${h.bgId} still RUNNING after ${seconds}s.\n" +
                    "Last output:\n${result.lastOutput}"
        }
        return ToolResult(
            content = content,
            summary = "attach ${h.bgId}",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = result is com.workflow.orchestrator.agent.tools.background.AttachResult.AttachTimeout,
        )
    }

    /**
     * Performs the stdin write on [Dispatchers.IO] so the blocking [OutputStream.write]
     * never runs on EDT or a UI-affine dispatcher (F-16 fix).
     * Cancellation is checked via coroutine suspension inside [withContext].
     */
    private suspend fun doSendStdin(h: BackgroundHandle, params: JsonObject): ToolResult {
        val input = params["input"]?.jsonPrimitive?.content
            ?: return toolError("MISSING_INPUT: send_stdin requires 'input'")
        val written = withContext(Dispatchers.IO) {
            runCatching { h.sendStdin(input) }.getOrElse { e ->
                if (e is UnsupportedOperationException)
                    return@withContext null  // propagate as "unsupported" below
                throw e
            }
        }
        if (written == null) return toolError("UNSUPPORTED_FOR_KIND: ${h.kind} does not accept stdin")
        if (!written) return toolError("SEND_STDIN_FAILED: process may have exited")
        val content = "Wrote ${input.length} chars to ${h.bgId} stdin."
        return ToolResult(
            content = content,
            summary = "stdin→${h.bgId}",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = false,
        )
    }

    private fun doKill(pool: BackgroundPool, sessionId: String, h: BackgroundHandle): ToolResult {
        val ok = h.kill()
        if (ok) {
            pool.forSession(sessionId).remove(h.bgId)
        }
        val content = if (ok) "Killed ${h.bgId}" else "Kill failed for ${h.bgId}"
        return ToolResult(
            content = content,
            summary = content,
            tokenEstimate = TokenEstimator.estimate(content),
            isError = !ok,
        )
    }

    private fun formatRuntime(ms: Long): String {
        val s = ms / 1000
        return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
    }

    private fun toolError(msg: String) = ToolResult(msg, "Error: $msg", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
}
