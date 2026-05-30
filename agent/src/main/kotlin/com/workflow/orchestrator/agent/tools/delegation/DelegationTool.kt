package com.workflow.orchestrator.agent.tools.delegation

import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.delegation.DelegationException
import com.workflow.orchestrator.agent.delegation.DelegationOutboundService
import com.workflow.orchestrator.agent.delegation.DelegationStatusResult
import com.workflow.orchestrator.agent.delegation.DelegationWaitOutcome
import com.workflow.orchestrator.agent.delegation.HandleState
import com.workflow.orchestrator.agent.delegation.FetchTranscriptResult
import com.workflow.orchestrator.agent.delegation.ui.DelegationAnswerConfirmDialog
import com.workflow.orchestrator.agent.delegation.ui.SocketGlobDiscovery
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.agent.delegation.TargetStatusResolver
import com.workflow.orchestrator.core.delegation.DelegationClient
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.delegation.DelegationPaths
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * `delegation` — meta-tool consolidating the cross-IDE delegation surface into a
 * single tool with an `action` enum, mirroring the [com.workflow.orchestrator.agent.tools.runtime.RuntimeExecTool]
 * pattern. Seven actions:
 *
 * - `send` — request work from an agent in another running IntelliJ instance (fresh send
 *   opens a picker; continuation with `handle` skips it and resumes the SAME remote
 *   session — even after it COMPLETED, within the ~30 min retention window).
 * - `close` — close an active delegation channel by handle (idempotent).
 * - `answer` — reply to a clarifying question raised by a delegated session.
 * - `fetch_transcript` — retrieve the full message history of a delegated session
 *   (returns a local path plus a head preview). Works after completion too — the
 *   transcript is retained for a grace period after the channel closes.
 * - `status` — cheap liveness check of a handle (active / closed / unknown) without a
 *   transcript round-trip.
 * - `wait` — block until the delegation completes or raises a question (bounded by
 *   `timeout_seconds`); returns the result inline. The async result still auto-delivers,
 *   so a timeout is not a failure.
 * - `list_targets` — read-only enumeration of potential delegation targets.
 *
 * The settings gate ([PluginSettings.enableOutboundCrossIdeDelegation]) is checked once
 * at the top of [execute]; each handler validates only its own arguments.
 *
 * Spec: docs/superpowers/specs/2026-05-24-cross-ide-plan5-meta-tool-design.md (consolidation)
 * and docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md (per-action design).
 */
class DelegationTool(
    // Test-override hooks lifted from DelegationListTargetsTool — meta-tool-level so
    // every test for the list_targets action constructs with these fakes.
    private val recentsProvider: suspend (Project) -> List<RecentEntry> = ::defaultRecentsProvider,
    private val discoveredProvider: suspend (Project) -> List<RecentEntry> = ::defaultDiscoveredProvider,
) : AgentTool {

    /**
     * One potential delegation target (used by the `list_targets` action).
     *
     * @property projectPath  Absolute path to the project root.
     * @property repoName     Display name (from RecentProjectsManager or directory base name).
     * @property status       One of: "running" (delegation socket bound — accepting now),
     *                        "available" (IDE open but inbound off — a send rings the doorbell for
     *                        consent), "closed" (in recents, not running), "discovered" (socket-glob
     *                        only), "missing" (path doesn't exist on disk).
     * @property lastOpened   Epoch millis if known from recents; null otherwise.
     */
    data class RecentEntry(
        val projectPath: String,
        val repoName: String,
        val status: String,
        val lastOpened: Long?,
    )

    override val name = "delegation"

    override val description = """
        Cross-IDE delegation — coordinate work with agents running in other IntelliJ
        instances that hold different repositories open. Local-only, same-machine, same-user.

        Actions and their parameters:
        - send(request, suggested_repo?, handle?) → Delegate a task. Without `handle`,
          opens a picker so the user selects the target IDE/repo; with `handle`, sends
          a follow-up user turn on the SAME session — this CONTINUES the existing
          conversation even if the remote (IDE-B) session has already COMPLETED: within
          the ~30 min retention window IDE-B resurrects the persisted session and resumes
          it from where it left off. Returns a handle + "running" status; the actual
          result arrives later as a system nudge when the remote agent finishes. Do NOT poll.
        - close(handle) → Close an active delegation channel (idempotent — closing an
          already-closed handle returns {"closed":false} and is still a success).
        - answer(handle, question_id, answer) → Reply to a clarifying Question nudge from
          a delegated session (the reply param is `answer`, not `request`). When
          auto-approve is off, a confirmation dialog opens.
        - fetch_transcript(handle) → Retrieve the full message history of a delegated
          session. Returns a local transcript path plus a 2 KiB head preview; use
          read_file on the path for full content. Still works AFTER the delegation
          completes — the transcript is retained for ~30 min after the channel closes.
        - status(handle) → Cheap liveness check: returns active (with last-seen state),
          closed (with the TERMINAL last_state — COMPLETED/FAILED/CANCELED/REJECTED),
          or a not-found error once the ~30 min retention window elapses. Use this instead
          of fetch_transcript when you only need to know whether the delegation is still
          running. Do NOT poll in a tight loop. status, answer, and send-continuation now
          AGREE about a handle's existence (closed-but-retained = consistently "closed";
          pruned = consistently "not found").
        - wait(handle, timeout_seconds?) → Block the current turn until the delegation
          completes or raises a clarifying question, then return it inline (default 300s,
          5-1800). Use this to "attach" and get the answer in the same turn. On a question,
          answer it then wait again. A timeout returns "still running" — NOT a failure;
          the result auto-delivers (the session auto-resumes) when it finishes.
        - list_targets() → Read-only enumeration of potential delegation targets (same
          list the picker shows: running / available / closed / discovered / missing).
          "available" = IDE open but inbound delegation OFF — a send rings its doorbell
          for consent. No UI opens.

        Errors (across all actions):
          DelegationOutboundDisabled — outbound delegation off in settings.
          DelegationUserCanceledPicker / DelegationTargetNotReachable / DelegationLimitReached /
          DelegationRejected / DelegationDeclined — send-specific picker / connection / quota /
          consent failures (declined_timeout is now specific: "IDE-B agent tab busy; user did
          not click Start within 55s").
          DelegationExpired — handle/session gone. On a send-continuation it carries the
          specific reason: session_closed / session_not_found (remote session genuinely
          gone or pruned), ide_b_not_running (target IDE down), ide_b_busy (target tab busy
          with another task — try again), resume_failed (session locked/missing on disk).
          DelegationHandleClosed — answer on a closed-but-retained handle (the session
          completed; start a new send or fetch_transcript instead).
          DelegationHandleNotFound — handle is unknown or pruned (answer / status / wait).
          DelegationWriteFailed — IPC write failed (send continuation / answer).
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf("send", "close", "answer", "fetch_transcript", "status", "wait", "list_targets"),
            ),
            "request" to ParameterProperty(
                type = "string",
                description = "Full briefing for the remote agent: what to do, relevant context, " +
                    "expected deliverables — required for action=send",
            ),
            "suggested_repo" to ParameterProperty(
                type = "string",
                description = "Optional repo-name hint to pre-select in the picker (e.g. \"frontend\") " +
                    "— used by action=send only",
            ),
            "handle" to ParameterProperty(
                type = "string",
                description = "Channel handle returned by a prior delegation send — required for " +
                    "action=close/answer/fetch_transcript/status/wait; optional for action=send (continuation: " +
                    "skips the picker and Accept dialog, sends a new user turn on the existing session — works " +
                    "even after the remote session COMPLETED, within the ~30 min retention window: the remote IDE " +
                    "resurrects the persisted session and resumes it)",
            ),
            "timeout_seconds" to ParameterProperty(
                type = "integer",
                description = "For action=wait only: how long to block for the result before returning " +
                    "'still running' (default 300, range 5-1800). The result also arrives automatically " +
                    "when done, so a timeout is not a failure.",
            ),
            "question_id" to ParameterProperty(
                type = "string",
                description = "Question id from a Question nudge — required for action=answer",
            ),
            "answer" to ParameterProperty(
                type = "string",
                description = "Answer text to forward to the delegated session — required for action=answer",
            ),
        ),
        required = listOf("action"),
    )

    override val allowedWorkers = setOf(
        WorkerType.ORCHESTRATOR,
        WorkerType.CODER,
        WorkerType.ANALYZER,
    )

    // No wall-clock loop timeout: action=wait blocks for up to its own timeout_seconds cap
    // (≤30 min), and send/answer suspend on user dialogs. Each action is internally bounded;
    // the default 120s per-tool timeout would otherwise truncate a legitimate wait or picker.
    override val timeoutMs: Long get() = Long.MAX_VALUE

    override fun documentation(): ToolDocumentation = toolDoc("delegation") {
        summary {
            technical(
                "Single-tool dispatcher for the cross-IDE delegation surface: seven actions over a local " +
                    "Unix-domain-socket IPC channel to another running IntelliJ instance on the same machine — " +
                    "send (fresh delegation via picker, or handle-keyed continuation), close (idempotent), " +
                    "answer (forward a clarifying-question reply), fetch_transcript (path + 2 KiB head preview; " +
                    "retained ~30 min post-completion), status (cheap active/closed liveness check), " +
                    "wait (block until the delegation completes/asks, returned inline; async result still " +
                    "auto-delivers), and list_targets (read-only recents+discovery enumeration). Gated behind " +
                    "PluginSettings.enableOutboundCrossIdeDelegation; results return asynchronously as loop nudges, never inline."
            )
            plain(
                "Hand a piece of work to a teammate's Claude that's running in a different IntelliJ window — " +
                    "one that has a different repo open. Like passing a sticky note through a hatch to the next room: " +
                    "you send the request, the other window's user gets asked to accept, their agent does the work, and " +
                    "the answer slides back to you later as a notification. You don't sit and wait — you keep working and " +
                    "the result shows up when it's done. Only available if the user turned on outbound delegation in Settings."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.NETWORK)
        counterfactual(
            "Without `delegation`, an agent that needs work done in a repo it doesn't have open has no path at all: " +
                "it can't open another project, it can't reach the other IDE's services, and `run_command` only sees the " +
                "current working tree. The fallback is to give up and ask the human to switch windows and drive the other " +
                "agent by hand — losing the async hand-off, the consent gate, and the transcript trail this tool provides."
        )
        llmMistake(
            "Polls after a send — calls fetch_transcript or list_targets in a loop waiting for the remote agent to finish. " +
                "The result arrives on its own as a system nudge ([DELEGATION RESULT …]); the loop should yield, not spin. " +
                "fetch_transcript is for inspecting an in-flight or finished session on demand, not for completion polling."
        )
        llmMistake(
            "Treats DelegationTargetNotReachable as 'the other IDE isn't running' and gives up. The single most common " +
                "cause is that the target IDE has inbound delegation disabled — which looks identical at the socket layer. " +
                "The fix is to ask the user to enable 'Accept incoming delegations' on the target IDE, not to abandon the task."
        )
        llmMistake(
            "Re-opens a fresh delegation (send with no handle) to ask a follow-up, instead of continuing the existing session. " +
                "To send a follow-up turn, pass the original handle to send — that skips the picker and the Accept dialog and " +
                "reuses the same remote session, EVEN IF that session already COMPLETED (within the ~30 min retention window the " +
                "remote IDE resurrects and resumes it). A bare send spins up a brand-new picker and loses the prior context."
        )
        llmMistake(
            "Calls answer on a handle whose session already COMPLETED and then retries on DelegationHandleClosed. You cannot " +
                "answer a finished session — to ask it something more, use send with the same handle (continuation resumes it); " +
                "to read what it did, use fetch_transcript. Only DelegationHandleNotFound (pruned/unknown handle) needs a brand-new send."
        )
        flowchart(
            """
            flowchart TD
                A[Agent needs work in another repo] --> B{Already have a handle?}
                B -- no --> C[delegation send request=...]
                C --> D[User picks target IDE + accepts]
                D --> E[Returns handle, status=running]
                B -- yes, follow-up --> F[delegation send handle=... request=...]
                F --> E
                E --> G[Keep working — do NOT poll]
                G --> H{Remote raises a Question nudge?}
                H -- yes --> I[delegation answer handle question_id answer]
                I --> G
                H -- no --> J{[DELEGATION RESULT] nudge arrives}
                J --> K[Review result; optionally fetch_transcript handle]
                K --> L[delegation close handle]
                A2[Just exploring who's available] --> M[delegation list_targets]
            """
        )
        actions {
            action("send") {
                description {
                    technical(
                        "Two branches keyed on `handle`. Without handle: DelegationOutboundService.send opens the target " +
                            "picker, performs the inbound-consent handshake, and registers an onResult callback that enqueues a " +
                            "nudge on the delegator session when the remote terminates. With handle: sendContinuation skips the " +
                            "picker/Accept and writes a fresh UserTurn onto the existing channel (Plan 4 continuation)."
                    )
                    plain(
                        "Send a task to another IDE. The first time, a picker opens so the user chooses which window/repo to " +
                            "hand it to; after that you can keep the same conversation going by reusing the handle it gave you."
                    )
                }
                whenLLMUses(
                    "When the user asks for work in a repository this IDE doesn't have open (e.g. 'have the frontend agent " +
                        "wire up the API call'), or to send a follow-up to a delegation already in flight (reuse the handle)."
                )
                params {
                    required("request", "string") {
                        llmSeesIt("Full briefing for the remote agent: what to do, relevant context, expected deliverables — required for action=send")
                        humanReadable("The task description that the other window's agent will receive — write it as a self-contained brief; the remote agent has none of this conversation's context.")
                        whenPresent("Forwarded verbatim as the delegated task (fresh send) or as a new user turn on the channel (continuation).")
                        constraint("must be non-blank")
                        example("Add a loading spinner to the PR list while data is fetching; match the existing skeleton style.")
                    }
                    optional("suggested_repo", "string") {
                        llmSeesIt("Optional repo-name hint to pre-select in the picker (e.g. \"frontend\") — used by action=send only")
                        humanReadable("A repo-name hint so the picker lands on the right window by default — the user can still change it.")
                        whenPresent("The picker pre-selects the matching target if one is found.")
                        whenAbsent("The picker opens with no pre-selection; the user picks from the full list.")
                        example("frontend")
                    }
                    optional("handle", "string") {
                        llmSeesIt("Channel handle returned by a prior delegation send — required for action=close/answer/fetch_transcript/status/wait; optional for action=send (continuation: skips the picker and Accept dialog, sends a new user turn on the existing session — works even after the remote session COMPLETED, within the ~30 min retention window: the remote IDE resurrects the persisted session and resumes it)")
                        humanReadable("Reuse a previous delegation's handle to continue that same remote session instead of starting a new one.")
                        whenPresent("Continuation branch: no picker, no Accept dialog — a follow-up turn is written to the existing channel.")
                        whenAbsent("Fresh-send branch: the target picker opens and a new channel is created.")
                        example("d3f9a1b2-...")
                    }
                }
                precondition("Outbound delegation must be enabled in Settings (else DelegationOutboundDisabled, checked once at the top of execute).")
                precondition("There must be an active agent session — the delegator session ID is captured at call time to route the result nudge.")
                precondition("For a fresh send, the target IDE must be running with the project open AND have inbound delegation accepted.")
                onSuccess(
                    "Returns a one-line JSON header `{\"handle\":...,\"status\":\"running\",\"repo\":...}` plus a human note. " +
                        "The actual result is NOT here — it arrives later as a [DELEGATION RESULT — repo (id)] system nudge with status, summary, files changed, branch/commit."
                )
                onFailure("user dismisses the picker", "DelegationUserCanceledPicker — delegation not sent. Don't retry automatically; ask the user what they intended.")
                onFailure("cannot connect to the target IDE", "DelegationTargetNotReachable — most often the target has inbound delegation disabled (looks identical to 'not running'). Ask the user to enable 'Accept incoming delegations' on the target first.")
                onFailure("too many open channels", "DelegationLimitReached — DelegationOutboundService.MAX_CHANNELS concurrent delegations already open; close one before sending another.")
                onFailure("target user declines the consent prompt", "DelegationDeclined / DelegationRejected — the target IDE's user said no. Surface to the user; don't retry blindly.")
                onFailure("continuation handle expired or write failed", "DelegationExpired carries a specific reason — session_closed / session_not_found (remote session genuinely gone or pruned), ide_b_not_running (target IDE down), ide_b_busy (target tab busy — try again), resume_failed (session locked/missing on disk); or DelegationWriteFailed (IPC write failed). For ide_b_busy, retry; for session_not_found, start a new send.")
                example("fresh delegation") {
                    param("action", "send")
                    param("request", "Implement the /orders endpoint per the spec in docs/api.md and open a PR.")
                    param("suggested_repo", "backend")
                    outcome("Picker opens pre-selected on 'backend'; on accept, returns a handle with status=running. Result arrives later as a nudge.")
                }
                example("continue an existing delegation (even after it completed)") {
                    param("action", "send")
                    param("handle", "d3f9a1b2-...")
                    param("request", "Also add an integration test for the 404 case.")
                    outcome("No picker — the follow-up turn is delivered to the same remote session; if that session already COMPLETED, the remote IDE resurrects the persisted session (within ~30 min) and resumes it.")
                    notes("Reuse the handle from the original send rather than starting a new delegation for follow-ups; the remote agent resumes from where it left off.")
                }
                verdict {
                    keep("The core of the feature — the only way an agent can hand work to a repo it doesn't have open. Async + consent-gated by design.", VerdictSeverity.STRONG)
                }
            }
            action("close") {
                description {
                    technical("DelegationOutboundService.close(handle). Idempotent — closing an already-closed handle returns closed=false without error.")
                    plain("Hang up a delegation channel you're done with. Safe to call even if it's already closed.")
                }
                whenLLMUses("After a delegation's result has been received and there's no follow-up coming, to free a channel slot (the concurrent-channel limit is shared).")
                params {
                    required("handle", "string") {
                        llmSeesIt("Channel handle returned by a prior delegation send — required for action=close/answer/fetch_transcript/status/wait; optional for action=send (continuation: skips the picker and Accept dialog, sends a new user turn on the existing session — works even after the remote session COMPLETED, within the ~30 min retention window: the remote IDE resurrects the persisted session and resumes it)")
                        humanReadable("Which delegation channel to close.")
                        whenPresent("That channel is torn down; the result is reported regardless of whether it was still open.")
                        example("d3f9a1b2-...")
                    }
                }
                onSuccess("Returns `{\"closed\":true|false,\"handle\":...}` — true if a live channel was closed, false if it was already closed (still a success).")
                example("free a finished channel") {
                    param("action", "close")
                    param("handle", "d3f9a1b2-...")
                    outcome("Returns {\"closed\":true,...}; the slot is freed for a new delegation.")
                }
                verdict {
                    keep("Cheap channel hygiene; needed because the concurrent-channel count is capped. Idempotency makes it safe to call defensively.")
                }
            }
            action("answer") {
                description {
                    technical(
                        "Forwards a reply to a clarifying Question nudge. Honors PluginSettings.autoApproveDelegationAnswers: when " +
                            "off, opens DelegationAnswerConfirmDialog (human can edit/decline) before writing. Distinguishes a missing " +
                            "channel (DelegationHandleNotFound) from a write failure (DelegationWriteFailed) so recovery differs."
                    )
                    plain(
                        "The remote agent paused to ask you something; this sends your answer back. If the user hasn't turned on " +
                            "auto-approve, a dialog pops first so they can review or tweak the reply before it goes."
                    )
                }
                whenLLMUses("Only in response to a Question nudge from a delegated session — it carries the handle and question_id you must echo back.")
                params {
                    required("handle", "string") {
                        llmSeesIt("Channel handle returned by a prior delegation send — required for action=close/answer/fetch_transcript/status/wait; optional for action=send (continuation: skips the picker and Accept dialog, sends a new user turn on the existing session — works even after the remote session COMPLETED, within the ~30 min retention window: the remote IDE resurrects the persisted session and resumes it)")
                        humanReadable("Which delegation the question came from.")
                        whenPresent("Used to locate the open channel; if the channel is gone, returns DelegationHandleNotFound.")
                        example("d3f9a1b2-...")
                    }
                    required("question_id", "string") {
                        llmSeesIt("Question id from a Question nudge — required for action=answer")
                        humanReadable("The id of the specific question being answered — copy it from the Question nudge.")
                        whenPresent("Pairs the answer to the exact pending question on the remote side.")
                        example("q-7")
                    }
                    required("answer", "string") {
                        llmSeesIt("Answer text to forward to the delegated session — required for action=answer")
                        humanReadable("The reply to send back. When auto-approve is off, the user may edit this in the confirm dialog.")
                        whenPresent("Forwarded to the remote session (after the optional confirm dialog).")
                        example("Use the existing AuthInterceptor; don't add a new one.")
                    }
                }
                precondition("A Question nudge must have been received for this handle (the source of question_id).")
                onSuccess("Returns `{\"sent\":true,\"handle\":...,\"question_id\":...}`; the remote agent resumes with the answer.")
                onFailure("handle is closed-but-retained (session already completed)", "DelegationHandleClosed — you cannot answer a finished session; use action=send (same handle) to resume it with a new turn, or action=fetch_transcript to read what it did. Distinct from DelegationHandleNotFound so recovery differs.")
                onFailure("handle unknown or pruned", "DelegationHandleNotFound — the handle is gone (never existed or its retention window elapsed). Start a new send.")
                onFailure("user declines the confirm dialog", "Returns an error that the user declined to send the answer (only when auto-approve is off).")
                onFailure("channel rejected the write", "DelegationWriteFailed — the channel may be shutting down; try again or start a new send.")
                example("answer a clarifying question") {
                    param("action", "answer")
                    param("handle", "d3f9a1b2-...")
                    param("question_id", "q-7")
                    param("answer", "Target Java 21; the module already sets it.")
                    outcome("The remote session unblocks and continues with the provided answer.")
                }
                verdict {
                    keep("Required to keep a delegated session moving when it needs input; the confirm-dialog path keeps a human in the loop unless they opt out.")
                }
            }
            action("fetch_transcript") {
                description {
                    technical(
                        "DelegationOutboundService.fetchTranscript writes/locates transcript-export.json on the TARGET IDE's filesystem " +
                            "and returns its path, byte size, a token estimate, and the first 2 KiB. NotFound maps to DelegationExpired."
                    )
                    plain(
                        "Peek at the full conversation happening in the other window. Returns where the transcript file lives plus the " +
                            "first couple of kilobytes; read_file that path for the whole thing."
                    )
                }
                whenLLMUses("On demand when you need to see what the remote agent actually did or is doing — e.g. to debug an unexpected result, or to summarize the remote work. NOT for completion polling.")
                params {
                    required("handle", "string") {
                        llmSeesIt("Channel handle returned by a prior delegation send — required for action=close/answer/fetch_transcript/status/wait; optional for action=send (continuation: skips the picker and Accept dialog, sends a new user turn on the existing session — works even after the remote session COMPLETED, within the ~30 min retention window: the remote IDE resurrects the persisted session and resumes it)")
                        humanReadable("Which delegated session's transcript to retrieve.")
                        whenPresent("Resolves to the remote session and exports its message history.")
                        example("d3f9a1b2-...")
                    }
                }
                onSuccess("Returns `transcript_path`, `size_bytes`, `token_estimate`, and the first 2 KiB under `head`. If the file exceeds 2 KiB, a truncation marker tells the LLM to read_file the path for the rest.")
                onFailure("handle gone / session pruned", "DelegationExpired with a reason — the channel has timed out or the remote IDE closed; the transcript is no longer reachable.")
                example("inspect a finished delegation") {
                    param("action", "fetch_transcript")
                    param("handle", "d3f9a1b2-...")
                    outcome("Returns the transcript path on the other IDE plus a 2 KiB preview; follow with read_file for the full history.")
                }
                verdict {
                    keep("The only window into what the remote agent did; the path+preview shape keeps token cost bounded while still allowing a full read on demand.")
                }
            }
            action("status") {
                description {
                    technical(
                        "DelegationOutboundService.statusOf(handle) — a cheap liveness check that reads the single " +
                            "handle-state source of truth (the same lookup send-continuation and answer use, so the three " +
                            "agree). Returns active (with last-seen state), closed (with the TERMINAL last_state — " +
                            "COMPLETED/FAILED/CANCELED/REJECTED, no longer a stale 'RUNNING'), or DelegationHandleNotFound " +
                            "once the ~30 min retention window elapses. No transcript round-trip."
                    )
                    plain(
                        "Ask 'is the other window still working on it, or did it finish?' without pulling the whole " +
                            "conversation. Tells you running / finished (and how it finished) / gone."
                    )
                }
                whenLLMUses(
                    "When you only need to know whether a delegation is still running or has finished — e.g. before " +
                        "deciding whether to wait or to fetch the transcript. Cheaper than fetch_transcript. Do NOT call " +
                        "it in a tight loop to poll for completion — the result auto-delivers; use wait to block instead."
                )
                params {
                    required("handle", "string") {
                        llmSeesIt("Channel handle returned by a prior delegation send — required for action=close/answer/fetch_transcript/status/wait; optional for action=send (continuation: skips the picker and Accept dialog, sends a new user turn on the existing session — works even after the remote session COMPLETED, within the ~30 min retention window: the remote IDE resurrects the persisted session and resumes it)")
                        humanReadable("Which delegation channel to check the liveness of.")
                        whenPresent("Resolved through the retained-aware handle lookup; returns active / closed / not-found consistently with answer and send-continuation.")
                        example("d3f9a1b2-...")
                    }
                }
                onSuccess(
                    "Returns `{\"handle\":...,\"status\":\"active\",\"state\":...,\"repo\":...}` while running, or " +
                        "`{\"handle\":...,\"status\":\"closed\",\"last_state\":COMPLETED|FAILED|CANCELED|REJECTED,\"repo\":...}` " +
                        "once finished (still retained for transcript reads)."
                )
                onFailure("handle unknown or its retention window elapsed", "DelegationHandleNotFound — start a fresh send; a pruned handle is consistently 'not found' across status/answer/send-continuation.")
                example("check whether a delegation finished") {
                    param("action", "status")
                    param("handle", "d3f9a1b2-...")
                    outcome("Returns active+state while running, or closed+terminal last_state once done — no transcript fetched.")
                    notes("Use this for a one-off liveness check, not as a polling loop; the result auto-delivers when done.")
                }
                verdict {
                    keep("Cheap, retained-aware liveness check that lets the LLM branch (wait vs fetch_transcript vs move on) without a transcript round-trip.")
                }
            }
            action("wait") {
                description {
                    technical(
                        "DelegationOutboundService.awaitResult(handle, timeoutMs) — suspends the current turn on a one-shot " +
                            "deferred completed by the reader loop on the delegation's Result or Question (the async nudge is " +
                            "suppressed for that handle while a wait is pending). A TimedOut outcome is NOT a failure: the async " +
                            "auto-delivery still fires when the remote finishes. Bounded by timeout_seconds, not the per-tool timeout."
                    )
                    plain(
                        "Sit and wait for the other window to finish (or to ask you something) and get the answer right here in " +
                            "this turn, instead of continuing other work and being interrupted later. If it takes too long the wait " +
                            "ends with 'still running' — that's fine, the result still shows up on its own."
                    )
                }
                whenLLMUses(
                    "When the next step depends on the delegation's result and there's nothing useful to do meanwhile — " +
                        "'attach' and get the outcome inline. On a Question outcome, answer it (action=answer) then wait again."
                )
                params {
                    required("handle", "string") {
                        llmSeesIt("Channel handle returned by a prior delegation send — required for action=close/answer/fetch_transcript/status/wait; optional for action=send (continuation: skips the picker and Accept dialog, sends a new user turn on the existing session — works even after the remote session COMPLETED, within the ~30 min retention window: the remote IDE resurrects the persisted session and resumes it)")
                        humanReadable("Which delegation to block on until it finishes or asks a question.")
                        whenPresent("The turn suspends until the remote session delivers a Result or Question, or the timeout elapses.")
                        example("d3f9a1b2-...")
                    }
                    optional("timeout_seconds", "integer") {
                        llmSeesIt("For action=wait only: how long to block for the result before returning 'still running' (default 300, range 5-1800). The result also arrives automatically when done, so a timeout is not a failure.")
                        humanReadable("Maximum seconds to block before giving up the wait (the work keeps running regardless).")
                        whenPresent("Caps the blocking wait at this many seconds (clamped to 5–1800).")
                        whenAbsent("Defaults to 300 seconds.")
                        constraint("5–1800 (values outside are clamped)")
                        example("600")
                    }
                }
                onSuccess(
                    "On completion returns the same [DELEGATION RESULT …] block the async nudge would carry (status, summary, " +
                        "files changed, branch/commit). On a clarifying question returns the question text + question_id to answer. " +
                        "On timeout returns a 'still running' note (NOT an error) — the result will auto-deliver."
                )
                onFailure("handle unknown or never opened", "DelegationHandleNotFound — start a fresh send.")
                example("block until the delegation finishes") {
                    param("action", "wait")
                    param("handle", "d3f9a1b2-...")
                    param("timeout_seconds", "600")
                    outcome("Suspends up to 600s; returns the result inline on completion, the question on a clarifying-question, or 'still running' on timeout.")
                    notes("A timeout is not a failure — the async result still auto-delivers. On a question, answer then wait again.")
                }
                verdict {
                    keep("Lets the LLM synchronously attach to a delegation when the next step depends on its result, while preserving the no-failure-on-timeout + async-auto-delivery contract.")
                }
            }
            action("list_targets") {
                description {
                    technical(
                        "Read-only union of recents (RecentProjectsManagerBase.getRecentPaths, each UDS-socket-probed: delegation socket → running, " +
                            "else doorbell socket → available (open but inbound off), else closed; missing if the path is gone) and socket-glob discovery " +
                            "(reachable sockets not in recents). Never opens UI; never throws — provider failures degrade to an empty list."
                    )
                    plain(
                        "List the IDE windows you could delegate to and whether each is currently running — the same list the picker shows, " +
                            "but without opening any dialog."
                    )
                }
                whenLLMUses("To check what's reachable before a send, or to answer 'which repos/agents are available?' without popping the picker.")
                onSuccess("Returns `{\"targets\":[{repoName, projectPath, status, lastOpened}]}` where status ∈ running | available | closed | discovered | missing. " +
                    "`available` means the IDE is open with inbound delegation off — a send will ring its doorbell and prompt the user for consent.")
                example("enumerate available targets") {
                    param("action", "list_targets")
                    outcome("Returns the JSON target list; no UI opens. Pick a repoName to pass as suggested_repo on a subsequent send.")
                }
                verdict {
                    keep("Read-only situational awareness that avoids a blind send into a picker; cheap and side-effect-free.")
                }
            }
        }
        verdict {
            keep(
                "The entire cross-IDE delegation feature is exposed through this one meta-tool; consolidating the seven " +
                    "actions mirrors the runtime_exec / jira pattern and keeps the schema token cost low while the feature is gated off by default.",
                VerdictSeverity.STRONG,
            )
        }
        related("spawn_agent", Relationship.ALTERNATIVE, "spawn_agent runs a sub-agent inside THIS IDE/repo; delegation hands work to a DIFFERENT IDE that holds another repo open.")
        related("ask_followup_question", Relationship.COMPLEMENT, "Feed a remote Question nudge to the user via ask_followup_question, then return their reply with action=answer.")
        related("read_file", Relationship.COMPLEMENT, "After fetch_transcript, read_file the returned transcript_path for the full message history beyond the 2 KiB preview.")
        downside("Same-machine, same-user, local IPC only — no cross-machine, cross-user, or multi-hop delegation (explicit v1 non-goals).")
        downside("Results are asynchronous: a send returns 'running' immediately and the outcome arrives as a nudge. An agent that expects an inline answer will misread the running header as the result.")
        downside("Reachability is ambiguous at the socket layer — 'inbound disabled' and 'IDE not running' are indistinguishable until the consent handshake, which is why DelegationTargetNotReachable enumerates causes rather than asserting one.")
        narrative("delegation")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        // Single settings gate at the top — every action lifts the same check from
        // its old per-tool implementation.
        if (!PluginSettings.getInstance(project).state.enableOutboundCrossIdeDelegation) {
            return ToolResult.error(
                "DelegationOutboundDisabled: cross-IDE delegation is currently disabled in settings " +
                    "(Tools → Workflow Orchestrator → Agent → Enable outbound cross-IDE delegation)"
            )
        }

        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.error("delegation: 'action' is required")

        return when (action) {
            "send" -> handleSend(params, project)
            "close" -> handleClose(params, project)
            "answer" -> handleAnswer(params, project)
            "fetch_transcript" -> handleFetchTranscript(params, project)
            "status" -> handleStatus(params, project)
            "wait" -> handleWait(params, project)
            "list_targets" -> handleListTargets(params, project)
            else -> ToolResult.error(
                "delegation: unknown action '$action' — must be one of " +
                    "send|close|answer|fetch_transcript|status|wait|list_targets"
            )
        }
    }

    // ── Action: send ─────────────────────────────────────────────────────────

    private suspend fun handleSend(params: JsonObject, project: Project): ToolResult {
        val request = params["request"]?.jsonPrimitive?.content
            ?: return ToolResult.error("delegation: 'request' is required")

        val suggestedRepo = params["suggested_repo"]?.jsonPrimitive?.content

        val agentService = project.getService(AgentService::class.java)
            ?: return ToolResult.error("delegation: AgentService unavailable")

        val outboundService = project.getService(DelegationOutboundService::class.java)
            ?: return ToolResult.error("delegation: DelegationOutboundService unavailable")

        // Capture the delegator session ID at call time; the closure must close over a
        // non-null copy because the active session may change by the time onResult fires.
        val delegatorSessionId = agentService.currentSessionState()?.sessionId
            ?: return ToolResult.error("delegation: no active session — cannot determine delegator session ID")

        // Plan 4: continue_with branch. Skip picker + Accept; send UserTurn over existing channel.
        val handleId = params["handle"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (handleId != null) {
            return try {
                val handle = outboundService.sendContinuation(
                    handleId = handleId,
                    request = request,
                    delegatorSessionId = delegatorSessionId,
                )
                val shortId = handle.id.take(8)
                val content = buildString {
                    appendLine("""{"handle":"${handle.id}","status":"running","repo":"${handle.targetRepoName}"}""")
                    appendLine()
                    appendLine(
                        "Continuation sent to ${handle.targetRepoName} (handle $shortId). " +
                            "Agent-B will process the new user turn; results will arrive as a nudge."
                    )
                }.trimEnd()
                ToolResult(
                    content = content,
                    summary = "Continuation sent to ${handle.targetRepoName} ($shortId) — awaiting result",
                    tokenEstimate = 30,
                )
            } catch (e: DelegationException.Expired) {
                ToolResult.error("DelegationExpired: ${e.expireReason ?: "no_reason"}")
            } catch (e: DelegationException.WriteFailed) {
                ToolResult.error("DelegationWriteFailed: ${e.ioReason}")
            }
        }

        // Fresh-send branch (existing logic, unchanged).
        return try {
            val handle = outboundService.send(
                request = request,
                suggestedRepo = suggestedRepo,
                delegatorSessionId = delegatorSessionId,
                onResult = { h, result ->
                    val nudge = buildNudgeText(h.targetRepoName, h.id, result)
                    agentService.enqueueNudgeForSession(delegatorSessionId, nudge)
                },
            )

            val shortId = handle.id.take(8)
            val content = buildString {
                appendLine("""{"handle":"${handle.id}","status":"running","repo":"${handle.targetRepoName}"}""")
                appendLine()
                appendLine(
                    "Delegated to ${handle.targetRepoName} (handle $shortId). " +
                        "Async — result will arrive as a nudge when done."
                )
            }.trimEnd()

            ToolResult(
                content = content,
                summary = "Delegated to ${handle.targetRepoName} ($shortId) — awaiting result",
                tokenEstimate = 30,
            )
        } catch (e: DelegationException.UserCanceledPicker) {
            ToolResult.error("DelegationUserCanceledPicker: user dismissed the picker — delegation not sent")
        } catch (e: DelegationException.TargetNotReachable) {
            // Plan 5.4 — enumerate causes with inbound-off first; that's the
            // most common failure mode and the one users can't diagnose
            // without explicit hinting (looks identical to "IDE not running"
            // at the socket layer).
            ToolResult.error(
                "DelegationTargetNotReachable: could not connect to the target IDE. " +
                    "Likely causes (in order): " +
                    "(1) the target IDE does not have 'Accept incoming delegations from other IDEs' " +
                    "enabled in Settings → Tools → Workflow Orchestrator → Cross-IDE Delegation; " +
                    "(2) the target IDE is not running, or has a different project open; " +
                    "(3) the project at the picked path was just closed. " +
                    "Ask the user to verify the inbound setting on the target IDE first — that's " +
                    "the most common cause and looks identical to 'not running' from this side."
            )
        } catch (e: DelegationException.LimitReached) {
            ToolResult.error("DelegationLimitReached: max ${DelegationOutboundService.MAX_CHANNELS} concurrent delegations already open — close one before sending another")
        } catch (e: DelegationException.Rejected) {
            if (e.rejectReason == "inbound_consent_declined")
                ToolResult.error("DelegationDeclined: the target IDE's user declined the request to enable inbound delegation. Ask them to enable 'Accept incoming delegations' if they want to receive the task.")
            else
                ToolResult.error("DelegationRejected: the target IDE declined the request — reason: ${e.rejectReason ?: "none"}")
        }
    }

    /**
     * Builds the nudge text that is injected into the delegator's loop when the
     * delegated session terminates. Lifted verbatim from `DelegationSendTool.buildNudgeText`.
     */
    private fun buildNudgeText(
        repoName: String,
        handleId: String,
        result: DelegationMessage.Result,
    ): String = buildString {
        val shortId = handleId.take(8)
        appendLine("[DELEGATION RESULT — $repoName ($shortId)]")
        appendLine("Status: ${result.status}")
        if (result.summary.isNotBlank()) {
            appendLine("Summary: ${result.summary}")
        }
        if (result.filesChanged.isNotEmpty()) {
            appendLine("Files changed (${result.filesChanged.size}): ${result.filesChanged.joinToString(", ")}")
        }
        if (result.branch != null) {
            appendLine("Branch: ${result.branch}")
        }
        if (result.commit != null) {
            appendLine("Commit: ${result.commit}")
        }
        if (result.reason != null) {
            appendLine("Reason: ${result.reason}")
        }
        appendLine("Duration: ${result.durationSeconds}s")
        when (result.status) {
            DelegationMessage.ResultStatus.COMPLETED ->
                appendLine("The remote agent has finished. Review the result above and continue.")
            DelegationMessage.ResultStatus.CANCELED ->
                appendLine("The remote session was cancelled. You may retry delegation with action=send if needed.")
            DelegationMessage.ResultStatus.REJECTED ->
                appendLine("The remote agent rejected the task. Check the reason above.")
            DelegationMessage.ResultStatus.FAILED ->
                appendLine("The remote agent failed. Check the reason above; you may retry.")
        }
    }.trimEnd()

    // ── Action: close ────────────────────────────────────────────────────────

    private suspend fun handleClose(params: JsonObject, project: Project): ToolResult {
        val handle = params["handle"]?.jsonPrimitive?.content
            ?: return ToolResult.error("delegation: 'handle' is required")

        val outboundService = project.getService(DelegationOutboundService::class.java)
            ?: return ToolResult.error("delegation: DelegationOutboundService unavailable")

        val closed = outboundService.close(handle)

        val shortId = handle.take(8)
        val summary = if (closed) {
            "Closed delegation $shortId"
        } else {
            "Handle $shortId already closed"
        }

        val content = """{"closed":$closed,"handle":"$handle"}"""

        LOG.debug("[DelegationClose] handle=$shortId closed=$closed")

        return ToolResult(
            content = content,
            summary = summary,
            tokenEstimate = 15,
        )
    }

    // ── Action: answer ───────────────────────────────────────────────────────

    /**
     * Forward a clarifying-question reply to the delegated session. Returns a
     * distinct error when the handle is unknown or closed so the LLM can
     * recognise the channel has expired since the Question arrived.
     */
    private suspend fun handleAnswer(params: JsonObject, project: Project): ToolResult {
        val handleId = params["handle"]?.jsonPrimitive?.content
            ?: return ToolResult.error("delegation: 'handle' is required")
        val questionId = params["question_id"]?.jsonPrimitive?.content
            ?: return ToolResult.error("delegation: 'question_id' is required")
        val answerText = params["answer"]?.jsonPrimitive?.content
            ?: return ToolResult.error("delegation: 'answer' is required")

        val outboundService = project.getService(DelegationOutboundService::class.java)
            ?: return ToolResult.error("delegation: DelegationOutboundService unavailable")

        // F1 fix: honour the autoApproveDelegationAnswers setting. When off, show a
        // confirmation dialog so the human can review (and optionally edit) the answer
        // before it is forwarded. When on, forward directly without interrupting the loop.
        val settings = PluginSettings.getInstance(project).state
        val finalAnswer = if (settings.autoApproveDelegationAnswers) {
            answerText
        } else {
            val questionText = outboundService.lookupPendingQuestionText(handleId, questionId)
                ?: "(question text unavailable — channel may have closed)"
            val targetRepo = outboundService.targetRepoName(handleId) ?: "(unknown)"
            withContext(Dispatchers.EDT) {
                val dlg = DelegationAnswerConfirmDialog(project, questionText, answerText, targetRepo)
                if (dlg.showAndGet()) dlg.editedAnswer else null
            } ?: return ToolResult.error(
                "delegation: user declined to send the answer"
            )
        }

        // Fix A: route the existence check through the single source of truth (handleState)
        // that `status` and `send`-continuation use, so the three actions can't disagree about
        // whether the handle is known. Distinguish a CLOSED-but-retained handle (consistent with
        // `status`'s "closed" classification) from a truly unknown/pruned one, and from a write
        // failure (handled below) — so the LLM knows whether to retry or start a new delegation.
        val shortId = handleId.take(8)
        when (val state = outboundService.handleState(handleId)) {
            is HandleState.Active -> Unit // open — proceed to the write below
            is HandleState.ClosedRetained -> return ToolResult.error(
                "DelegationHandleClosed: $handleId — the delegated session has already completed " +
                    "(last state ${state.lastState}); the channel is closed (still retained for transcript reads). " +
                    "You cannot answer a closed session — use delegation with action=send to start a new one, or " +
                    "action=fetch_transcript to read what it did."
            )
            HandleState.Unknown -> return ToolResult.error(
                "DelegationHandleNotFound: $handleId — the handle is unknown or already closed. " +
                    "The delegated session may have already terminated; use delegation with action=send to start a new one."
            )
        }

        val sent = outboundService.sendAnswer(handleId, questionId, finalAnswer)

        return if (sent) {
            LOG.debug("[DelegationAnswer] handle=$shortId question=$questionId sent=true")
            ToolResult(
                content = """{"sent":true,"handle":"$handleId","question_id":"$questionId"}""",
                summary = "Sent answer to $shortId",
                tokenEstimate = 15,
            )
        } else {
            ToolResult.error(
                "DelegationWriteFailed: channel for $handleId rejected the write. " +
                    "The channel may be shutting down; try again or use delegation with action=send to start a new session."
            )
        }
    }

    // ── Action: status ───────────────────────────────────────────────────────

    private fun handleStatus(params: JsonObject, project: Project): ToolResult {
        val handleId = params["handle"]?.jsonPrimitive?.content
            ?: return ToolResult.error("delegation: 'handle' is required")
        val outbound = project.getService(DelegationOutboundService::class.java)
            ?: return ToolResult.error("delegation: DelegationOutboundService unavailable")
        val shortId = handleId.take(8)
        return when (val status = outbound.statusOf(handleId)) {
            is DelegationStatusResult.Active -> {
                val repo = status.repoName ?: "(unknown)"
                ToolResult(
                    content = """{"handle":"$handleId","status":"active","state":"${status.state}","repo":"$repo"}""",
                    summary = "Delegation $shortId ($repo): active — ${status.state}",
                    tokenEstimate = 20,
                )
            }
            is DelegationStatusResult.Closed -> {
                val repo = status.repoName ?: "(unknown)"
                ToolResult(
                    content = """{"handle":"$handleId","status":"closed","last_state":"${status.lastState}","repo":"$repo"}""" +
                        "\n\nThe delegated session has ended. Use delegation(action=\"fetch_transcript\", " +
                        "handle=\"$handleId\") to read its full conversation while it is still retained.",
                    summary = "Delegation $shortId ($repo): closed — last state ${status.lastState}",
                    tokenEstimate = 30,
                )
            }
            DelegationStatusResult.Unknown -> ToolResult.error(
                "DelegationHandleNotFound: $handleId — unknown handle, or its retention window has elapsed. " +
                    "Use delegation with action=send to start a new delegation."
            )
        }
    }

    // ── Action: wait ─────────────────────────────────────────────────────────

    private suspend fun handleWait(params: JsonObject, project: Project): ToolResult {
        val handleId = params["handle"]?.jsonPrimitive?.content
            ?: return ToolResult.error("delegation: 'handle' is required")
        val outbound = project.getService(DelegationOutboundService::class.java)
            ?: return ToolResult.error("delegation: DelegationOutboundService unavailable")
        val timeoutSeconds = (params["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: DEFAULT_WAIT_SECONDS)
            .coerceIn(MIN_WAIT_SECONDS, MAX_WAIT_SECONDS)
        val shortId = handleId.take(8)
        return when (val outcome = outbound.awaitResult(handleId, timeoutSeconds * 1000L)) {
            is DelegationWaitOutcome.Completed -> ToolResult(
                content = buildNudgeText(outcome.repoName, handleId, outcome.result),
                summary = "Delegation $shortId (${outcome.repoName}) finished: ${outcome.result.status}",
                tokenEstimate = 60,
            )
            is DelegationWaitOutcome.Question -> ToolResult(
                content = buildString {
                    appendLine("The delegated session ${outcome.repoName} ($shortId) is asking a clarifying question:")
                    appendLine()
                    appendLine(outcome.question.text)
                    if (outcome.question.options.isNotEmpty()) {
                        appendLine("Suggested options: ${outcome.question.options.joinToString(", ")}")
                    }
                    appendLine()
                    append(
                        "Answer it with delegation(action=\"answer\", handle=\"$handleId\", " +
                            "question_id=\"${outcome.question.questionId}\", answer=\"…\"), then wait again if needed."
                    )
                },
                summary = "Delegation $shortId asked a clarifying question",
                tokenEstimate = 40,
            )
            is DelegationWaitOutcome.TimedOut -> ToolResult(
                content = "Delegation ${outcome.repoName} ($shortId) is still running after ${timeoutSeconds}s. " +
                    "This is not a failure — it will deliver its result automatically when done (your session " +
                    "auto-resumes). You may continue with other work, call delegation(action=\"status\") to " +
                    "check, or delegation(action=\"wait\") again.",
                summary = "Delegation $shortId still running (waited ${timeoutSeconds}s)",
                tokenEstimate = 30,
            )
            is DelegationWaitOutcome.NotActive -> if (outcome.reason == "already_completed") {
                ToolResult(
                    content = "Delegation $shortId has already completed. " +
                        "Use delegation(action=\"fetch_transcript\", handle=\"$handleId\") to read its conversation.",
                    summary = "Delegation $shortId already completed",
                    tokenEstimate = 20,
                )
            } else {
                ToolResult.error(
                    "DelegationHandleNotFound: $handleId — unknown handle or never opened. " +
                        "Use delegation with action=send to start one."
                )
            }
        }
    }

    // ── Action: fetch_transcript ─────────────────────────────────────────────

    private suspend fun handleFetchTranscript(params: JsonObject, project: Project): ToolResult {
        val handleId = params["handle"]?.jsonPrimitive?.content
            ?: return ToolResult.error("delegation: 'handle' is required")
        return executeFetchTranscriptRaw(project, handleId)
    }

    /**
     * Public test entry point that takes pre-validated args, bypasses the settings gate,
     * and calls the outbound service directly. Lifted from `DelegationFetchTranscriptTool.executeRaw`.
     */
    suspend fun executeFetchTranscriptRaw(project: Project, handleId: String): ToolResult {
        val outbound = project.getService(DelegationOutboundService::class.java)
            ?: return ToolResult.error("delegation: DelegationOutboundService unavailable")
        return when (val outcome = outbound.fetchTranscript(handleId)) {
            is FetchTranscriptResult.Ok -> {
                val path = Path.of(outcome.transcriptPath)
                val bytes = try { Files.size(path) } catch (e: Exception) { -1L }
                val head = try {
                    Files.newInputStream(path).use { ins ->
                        ins.readNBytes(2048).toString(Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    "(head read failed: ${e.message})"
                }
                val tokenEstimate = if (bytes > 0) (bytes / 4).toInt() else 0
                val content = buildString {
                    append("transcript_path: ")
                    append(outcome.transcriptPath)
                    append("\nsize_bytes: ").append(bytes)
                    append("\ntoken_estimate: ").append(tokenEstimate)
                    append("\nhead (first 2 KiB):\n")
                    append(head)
                    if (bytes > 2048) {
                        append("\n[…truncated; use read_file on transcript_path for full content…]")
                    }
                }
                val shortId = handleId.take(8)
                LOG.debug("[DelegationFetchTranscript] handle=$shortId size=$bytes bytes")
                ToolResult(
                    content = content,
                    summary = content,
                    tokenEstimate = tokenEstimate.coerceAtLeast(ToolResult.ERROR_TOKEN_ESTIMATE),
                )
            }
            is FetchTranscriptResult.NotFound -> ToolResult.error(
                "DelegationExpired: ${outcome.reason}"
            )
        }
    }

    // ── Action: list_targets ─────────────────────────────────────────────────

    private suspend fun handleListTargets(@Suppress("UNUSED_PARAMETER") params: JsonObject, project: Project): ToolResult {
        val recents = try {
            recentsProvider(project)
        } catch (e: Exception) {
            LOG.warn("delegation list_targets: recents lookup failed", e)
            emptyList()
        }

        val recentPaths = recents.map { canon(it.projectPath) }.toSet()

        val discovered = try {
            discoveredProvider(project)
        } catch (e: Exception) {
            LOG.warn("delegation list_targets: discovery failed", e)
            emptyList()
        }.filter { canon(it.projectPath) !in recentPaths }

        val all = recents + discovered

        val json = buildString {
            append("""{"targets":[""")
            all.forEachIndexed { i, e ->
                if (i > 0) append(',')
                append("""{"repoName":""")
                append(quoteJson(e.repoName))
                append(""","projectPath":""")
                append(quoteJson(e.projectPath))
                append(""","status":""")
                append(quoteJson(e.status))
                append(""","lastOpened":""")
                append(e.lastOpened?.toString() ?: "null")
                append('}')
            }
            append("]}")
        }

        LOG.debug("[DelegationListTargets] returning ${all.size} targets")

        return ToolResult(
            content = json,
            summary = json,
            tokenEstimate = (json.length / 4).coerceAtLeast(10),
        )
    }

    companion object {
        private val LOG = Logger.getInstance(DelegationTool::class.java)

        /** action=wait blocking budget bounds (seconds). */
        const val DEFAULT_WAIT_SECONDS = 300
        const val MIN_WAIT_SECONDS = 5
        const val MAX_WAIT_SECONDS = 1800

        private fun canon(p: String): String =
            try { Path.of(p).toAbsolutePath().normalize().toString() } catch (_: Exception) { p }

        private fun quoteJson(s: String): String =
            '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"'

        /**
         * Resolve a recent target's status from socket reachability.
         *
         * Delegates to [TargetStatusResolver.resolveTargetStatusString] — the shared resolver
         * consumed by both this tool and the picker UI so the two surfaces cannot diverge.
         *
         * - `missing`   — path no longer exists on disk.
         * - `running`   — delegation socket bound (inbound ON / already accepting).
         * - `available` — delegation socket NOT bound but the doorbell IS: IDE open, inbound off.
         *                 A send rings the doorbell for consent.
         * - `closed`    — neither socket bound: the IDE is not running this project.
         */
        internal fun resolveTargetStatus(
            exists: Boolean,
            delegationReachable: Boolean,
            doorbellReachable: Boolean,
        ): String = TargetStatusResolver.resolveTargetStatusString(exists, delegationReachable, doorbellReachable)

        /**
         * Production recents provider.
         *
         * Reads [RecentProjectsManagerBase.getRecentPaths], probes each path's UDS socket
         * to determine "running" vs "closed". Paths that don't exist on disk are marked
         * "missing" (socket probe is skipped for non-existent paths).
         */
        suspend fun defaultRecentsProvider(project: Project): List<RecentEntry> =
            withContext(Dispatchers.IO) {
                val mgr = (RecentProjectsManager.getInstance() as? RecentProjectsManagerBase)
                    ?: return@withContext emptyList()
                val paths: List<String> = try {
                    mgr.getRecentPaths()
                } catch (_: Exception) {
                    emptyList()
                }
                paths.mapNotNull { pathStr ->
                    try {
                        val path = Path.of(pathStr)
                        val name: String = try {
                            mgr.getDisplayName(pathStr)?.takeIf { it.isNotBlank() }
                        } catch (_: Exception) { null }
                            ?: path.fileName?.toString()
                            ?: pathStr
                        // Use the shared dual-probe so picker and list_targets always agree.
                        val targetStatus = TargetStatusResolver.dualProbeStatus(path)
                        val status = TargetStatusResolver.resolveTargetStatusString(
                            exists = targetStatus != TargetStatusResolver.TargetStatus.MISSING,
                            delegationReachable = targetStatus == TargetStatusResolver.TargetStatus.RUNNING,
                            doorbellReachable = targetStatus == TargetStatusResolver.TargetStatus.AVAILABLE,
                        )
                        RecentEntry(
                            projectPath = pathStr,
                            repoName = name,
                            status = status,
                            lastOpened = null, // RecentProjectsManagerBase doesn't expose timestamps directly
                        )
                    } catch (e: Exception) {
                        LOG.debug("delegation list_targets: skipping malformed recent $pathStr", e)
                        null
                    }
                }
            }

        /**
         * Production discovered provider.
         *
         * Uses [SocketGlobDiscovery] to find IDE instances whose socket is reachable but
         * whose project path is not in the recents list. Callers filter out any paths
         * already covered by [defaultRecentsProvider].
         */
        suspend fun defaultDiscoveredProvider(project: Project): List<RecentEntry> =
            withContext(Dispatchers.IO) {
                try {
                    SocketGlobDiscovery(pingFn = { p -> DelegationClient.ping(p) })
                        .discover()
                        .map { d ->
                            RecentEntry(
                                projectPath = d.projectPath,
                                repoName = Path.of(d.projectPath).fileName?.toString() ?: d.projectPath,
                                status = "discovered",
                                lastOpened = null,
                            )
                        }
                } catch (e: Exception) {
                    LOG.warn("delegation list_targets: socket-glob discovery failed", e)
                    emptyList()
                }
            }
    }
}
