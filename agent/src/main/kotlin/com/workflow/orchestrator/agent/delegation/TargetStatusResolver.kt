package com.workflow.orchestrator.agent.delegation

import com.workflow.orchestrator.core.delegation.DelegationClient
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.delegation.DelegationPaths
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared doorbell-aware status resolver for cross-IDE delegation targets.
 *
 * This is the single source of truth consumed by BOTH the picker UI
 * ([com.workflow.orchestrator.agent.delegation.ui.DelegationPicker]) and the
 * `list_targets` tool action
 * ([com.workflow.orchestrator.agent.tools.delegation.DelegationTool]) so the two
 * surfaces cannot drift out of parity again.
 *
 * ## Status model (four states)
 *
 * | Status      | Socket state                              | Action when sending         |
 * |-------------|-------------------------------------------|-----------------------------|
 * | `running`   | delegation socket bound (inbound ON)      | connect directly            |
 * | `available` | doorbell bound, delegation socket NOT bound (inbound OFF) | ring doorbell → consent |
 * | `closed`    | neither socket bound, path exists         | IDE not running this project |
 * | `missing`   | path does not exist on disk               | path was deleted / moved    |
 *
 * The "available" status fixes the first-open picker bug: a running IDE with inbound
 * delegation OFF (the common case) had its delegation socket unbound and doorbell bound —
 * the old three-state picker reported it as "closed" (indistinguishable from dead) until
 * the user consented and the delegation socket was transiently bound.
 *
 * ## Design
 *
 * - [resolveTargetStatus] is **pure** (no I/O) so it is trivially unit-testable.
 * - [dualProbeStatus] wraps the I/O: probe delegation socket → if unreachable, probe
 *   doorbell socket → call [resolveTargetStatus] with the results. An injectable
 *   [pingFn] allows tests to replace real socket pings with stubs.
 */
object TargetStatusResolver {

    /**
     * Picker status enum (matches the four possible states above).
     *
     * AVAILABLE means: the IDE is running this project but has inbound delegation disabled —
     * sending to it will ring the doorbell and prompt the user for consent.
     *
     * String representations used by the `list_targets` tool action:
     * RUNNING → "running", AVAILABLE → "available", CLOSED → "closed", MISSING → "missing".
     */
    enum class TargetStatus { RUNNING, AVAILABLE, CLOSED, MISSING }

    /**
     * Resolved status PLUS the ADVISORY busy hint carried back by the doorbell/delegation Pong.
     *
     * [busy] is meaningful primarily for [TargetStatus.RUNNING] (inbound ON, reachable on the work
     * socket): `true` → the target's agent tab is running another task right now; `false` → idle;
     * `null` → UNKNOWN (old peer that omits the field, or the target couldn't resolve its own state).
     * For [TargetStatus.AVAILABLE] the bit may also ride back on the doorbell Pong (secondary).
     * It is a point-in-time (TOCTOU) snapshot — the target may change between probe and send.
     */
    data class ProbeResult(val status: TargetStatus, val busy: Boolean?)

    /**
     * Human-readable status label that folds in the advisory busy hint for the running case:
     * `running (busy)` / `running (idle)` when [busy] is known, plain `running` when null (old peer).
     * Non-running statuses are returned unchanged ([busy] is not surfaced in their label).
     */
    fun statusLabel(status: TargetStatus, busy: Boolean?): String = when (status) {
        TargetStatus.RUNNING -> when (busy) {
            true -> "running (busy)"
            false -> "running (idle)"
            null -> "running"
        }
        TargetStatus.AVAILABLE -> "available"
        TargetStatus.CLOSED -> "closed"
        TargetStatus.MISSING -> "missing"
    }

    /** String-label variant keyed off the canonical four-state strings. */
    fun statusLabel(statusString: String, busy: Boolean?): String = when (statusString) {
        "running" -> when (busy) {
            true -> "running (busy)"
            false -> "running (idle)"
            null -> "running"
        }
        else -> statusString
    }

    /**
     * Pure (no I/O) status resolution from socket-reachability booleans.
     *
     * This is the canonical mapping; both the picker probe loop and [defaultRecentsProbe]
     * call this function so the mapping can never diverge.
     */
    fun resolveTargetStatus(
        exists: Boolean,
        delegationReachable: Boolean,
        doorbellReachable: Boolean,
    ): TargetStatus = when {
        !exists -> TargetStatus.MISSING
        delegationReachable -> TargetStatus.RUNNING
        doorbellReachable -> TargetStatus.AVAILABLE
        else -> TargetStatus.CLOSED
    }

    /**
     * String-valued version of [resolveTargetStatus] for the `list_targets` JSON output.
     */
    fun resolveTargetStatusString(
        exists: Boolean,
        delegationReachable: Boolean,
        doorbellReachable: Boolean,
    ): String = when (resolveTargetStatus(exists, delegationReachable, doorbellReachable)) {
        TargetStatus.MISSING -> "missing"
        TargetStatus.RUNNING -> "running"
        TargetStatus.AVAILABLE -> "available"
        TargetStatus.CLOSED -> "closed"
    }

    /**
     * I/O probe: dual-probe a project path and return its [TargetStatus].
     *
     * Probes [DelegationPaths.socketFor] first; if that fails, probes
     * [DelegationPaths.doorbellSocketFor] so a running-but-inbound-OFF IDE is
     * classified as [TargetStatus.AVAILABLE] rather than [TargetStatus.CLOSED].
     *
     * @param projectPath Absolute path to the project root on disk.
     * @param pingFn      Suspending function that attempts to PING a socket path;
     *                    returns non-null on pong, null on timeout/unreachable.
     *                    Defaults to [DelegationClient.ping] with a 200ms timeout.
     */
    suspend fun dualProbeStatus(
        projectPath: Path,
        pingFn: suspend (Path) -> Any? = { socketPath ->
            DelegationClient.ping(socketPath, timeoutMillis = 200)
        },
    ): TargetStatus = dualProbeStatusDetailed(projectPath, pingFn).status

    /**
     * I/O probe variant that ALSO surfaces the ADVISORY busy hint from the responding Pong.
     *
     * Same probe order/logic as [dualProbeStatus] (delegation socket first; doorbell only if the
     * delegation socket is silent) so the running/available/closed mapping is byte-identical — the
     * busy bit is read OFF the SAME Pong that established reachability, never via an extra probe.
     *
     * [busy] is taken from the Pong of whichever socket established the status:
     *  - RUNNING → the delegation-socket Pong's [DelegationMessage.Pong.busy].
     *  - AVAILABLE → the doorbell-socket Pong's busy (secondary; doorbell always answers).
     *  - CLOSED / MISSING → null (no live peer).
     * Old peers omit the field → null → plain `running` downstream. A non-[DelegationMessage.Pong]
     * ping result (e.g. a test stub string) yields null busy while still counting as reachable.
     */
    suspend fun dualProbeStatusDetailed(
        projectPath: Path,
        pingFn: suspend (Path) -> Any? = { socketPath ->
            DelegationClient.ping(socketPath, timeoutMillis = 200)
        },
    ): ProbeResult {
        val exists = Files.exists(projectPath)
        if (!exists) return ProbeResult(TargetStatus.MISSING, null)

        val delegationSocket = DelegationPaths.socketFor(projectPath)
        val delegationPong = try { pingFn(delegationSocket) } catch (_: Exception) { null }
        val delegationReachable = delegationPong != null

        val doorbellPong = if (delegationReachable) {
            null // Skip doorbell probe when delegation socket already responded
        } else {
            val doorbellSocket = DelegationPaths.doorbellSocketFor(projectPath)
            try { pingFn(doorbellSocket) } catch (_: Exception) { null }
        }
        val doorbellReachable = doorbellPong != null

        val status = resolveTargetStatus(exists, delegationReachable, doorbellReachable)
        val busy = when (status) {
            TargetStatus.RUNNING -> busyOf(delegationPong)
            TargetStatus.AVAILABLE -> busyOf(doorbellPong)
            else -> null
        }
        return ProbeResult(status, busy)
    }

    /** Extracts the advisory [DelegationMessage.Pong.busy] from a ping result; null for non-Pong. */
    private fun busyOf(pingResult: Any?): Boolean? =
        (pingResult as? DelegationMessage.Pong)?.busy
}
