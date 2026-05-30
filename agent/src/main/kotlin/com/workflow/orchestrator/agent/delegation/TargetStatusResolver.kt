package com.workflow.orchestrator.agent.delegation

import com.workflow.orchestrator.core.delegation.DelegationClient
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
    ): TargetStatus {
        val exists = Files.exists(projectPath)
        if (!exists) return TargetStatus.MISSING

        val delegationSocket = DelegationPaths.socketFor(projectPath)
        val delegationReachable = try {
            pingFn(delegationSocket) != null
        } catch (_: Exception) { false }

        val doorbellReachable = if (delegationReachable) {
            false // Skip doorbell probe when delegation socket already responded
        } else {
            val doorbellSocket = DelegationPaths.doorbellSocketFor(projectPath)
            try { pingFn(doorbellSocket) != null } catch (_: Exception) { false }
        }

        return resolveTargetStatus(exists, delegationReachable, doorbellReachable)
    }
}
