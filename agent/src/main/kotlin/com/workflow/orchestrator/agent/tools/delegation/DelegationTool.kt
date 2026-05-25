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
import com.workflow.orchestrator.agent.delegation.FetchTranscriptResult
import com.workflow.orchestrator.agent.delegation.ui.DelegationAnswerConfirmDialog
import com.workflow.orchestrator.agent.delegation.ui.SocketGlobDiscovery
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.delegation.DelegationClient
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.delegation.DelegationPaths
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * `delegation` — meta-tool consolidating the cross-IDE delegation surface into a
 * single tool with an `action` enum, mirroring the [com.workflow.orchestrator.agent.tools.runtime.RuntimeExecTool]
 * pattern. Five actions:
 *
 * - `send` — request work from an agent in another running IntelliJ instance (fresh send
 *   opens a picker; continuation with `handle` skips it).
 * - `close` — close an active delegation channel by handle (idempotent).
 * - `answer` — reply to a clarifying question raised by a delegated session.
 * - `fetch_transcript` — retrieve the full message history of a delegated session
 *   (returns a path on IDE-B's filesystem plus a head preview).
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
     * @property status       One of: "running" (IDE reachable), "closed" (in recents, not running),
     *                        "discovered" (socket-glob only), "missing" (path doesn't exist on disk).
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
          a follow-up user turn on an existing channel (Plan 4 continuation). Returns
          a handle + "running" status; the actual result arrives later as a system nudge
          when the remote agent finishes. Do NOT poll.
        - close(handle) → Close an active delegation channel (idempotent — closing an
          already-closed handle is a no-op success).
        - answer(handle, question_id, answer) → Reply to a clarifying Question nudge from
          a delegated session. When auto-approve is off, a confirmation dialog opens.
        - fetch_transcript(handle) → Retrieve the full message history of a delegated
          session. Returns a path to transcript-export.json on IDE-B's filesystem plus
          a 2 KiB head preview; use read_file on the path for full content.
        - list_targets() → Read-only enumeration of potential delegation targets (same
          list the picker shows: running / closed / discovered / missing). No UI opens.

        Errors (across all actions):
          DelegationOutboundDisabled — outbound delegation off in settings.
          DelegationUserCanceledPicker / DelegationTargetNotReachable / DelegationLimitReached /
          DelegationRejected — send-specific picker / connection / quota failures.
          DelegationExpired — handle is gone (timed out, pruned, remote IDE closed).
          DelegationHandleNotFound — handle is unknown or already closed (answer).
          DelegationWriteFailed — IPC write failed (send continuation / answer).
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf("send", "close", "answer", "fetch_transcript", "list_targets"),
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
                    "action=close/answer/fetch_transcript; optional for action=send (continuation: " +
                    "skips the picker and Accept dialog, sends a new user turn on the existing channel)",
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
            "list_targets" -> handleListTargets(params, project)
            else -> ToolResult.error(
                "delegation: unknown action '$action' — must be one of " +
                    "send|close|answer|fetch_transcript|list_targets"
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

        // Plan 2 F10: distinguish "handle not in map" from "write failed" so the LLM
        // can decide whether to retry the same handle or open a new delegation.
        if (!outboundService.hasOpenChannel(handleId)) {
            return ToolResult.error(
                "DelegationHandleNotFound: $handleId — the handle is unknown or already closed. " +
                    "The delegated session may have already terminated; use delegation with action=send to start a new one."
            )
        }

        val sent = outboundService.sendAnswer(handleId, questionId, finalAnswer)

        val shortId = handleId.take(8)
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

        private fun canon(p: String): String =
            try { Path.of(p).toAbsolutePath().normalize().toString() } catch (_: Exception) { p }

        private fun quoteJson(s: String): String =
            '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"'

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
                        val status = when {
                            !Files.exists(path) -> "missing"
                            DelegationClient.ping(DelegationPaths.socketFor(path)) != null -> "running"
                            else -> "closed"
                        }
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
