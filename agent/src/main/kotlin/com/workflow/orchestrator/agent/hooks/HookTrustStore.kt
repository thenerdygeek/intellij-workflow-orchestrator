package com.workflow.orchestrator.agent.hooks

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

/**
 * Per-project persistence store for .agent-hooks.json trust decisions.
 *
 * Trust is keyed by SHA-256 hex of the file content, not the path.
 * This means a file change invalidates the cached decision and re-prompts.
 *
 * State is persisted via IntelliJ PersistentStateComponent to
 * workflow-agent-hook-trust.xml inside the project's .idea directory.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "WorkflowAgentHookTrust",
    storages = [Storage("workflow-agent-hook-trust.xml")]
)
class HookTrustStore : PersistentStateComponent<HookTrustStore.HookTrustState> {

    /** Possible trust outcomes for a given SHA-256 hex. */
    enum class TrustState { TRUSTED, REJECTED, UNKNOWN }

    /** Serializable state persisted to workflow-agent-hook-trust.xml. */
    class HookTrustState {
        /** SHA-256 hex → epoch millis of decision time (user said "trust"). */
        var trustedShas: MutableMap<String, Long> = mutableMapOf()
        /** SHA-256 hex → epoch millis of decision time (user said "reject"). */
        var rejectedShas: MutableMap<String, Long> = mutableMapOf()
    }

    private var state = HookTrustState()

    override fun getState(): HookTrustState = state

    override fun loadState(state: HookTrustState) {
        this.state = state
    }

    /**
     * Look up the trust decision for the given SHA-256 hex.
     * Returns UNKNOWN if no decision has been recorded for this content hash.
     */
    fun checkTrust(sha256: String): TrustState = when {
        state.trustedShas.containsKey(sha256) -> TrustState.TRUSTED
        state.rejectedShas.containsKey(sha256) -> TrustState.REJECTED
        else -> TrustState.UNKNOWN
    }

    /**
     * Record a TRUSTED decision for this content hash.
     * Removes any prior REJECTED decision for the same hash.
     */
    fun setTrusted(sha256: String) {
        state.rejectedShas.remove(sha256)
        state.trustedShas[sha256] = System.currentTimeMillis()
        pruneIfNeeded()
    }

    /**
     * Record a REJECTED decision for this content hash.
     * Removes any prior TRUSTED decision for the same hash.
     */
    fun setRejected(sha256: String) {
        state.trustedShas.remove(sha256)
        state.rejectedShas[sha256] = System.currentTimeMillis()
        pruneIfNeeded()
    }

    /**
     * Forget any trust decision for this content hash (resets to UNKNOWN).
     * Intended for future UI management of stored decisions.
     */
    fun forget(sha256: String) {
        state.trustedShas.remove(sha256)
        state.rejectedShas.remove(sha256)
    }

    /**
     * Cheap heuristic: when the combined store exceeds 1000 entries,
     * drop the oldest 100 by timestamp to prevent unbounded growth.
     */
    private fun pruneIfNeeded() {
        val total = state.trustedShas.size + state.rejectedShas.size
        if (total <= 1000) return

        // Drop oldest 100 entries across both maps combined.
        // Build a single list of (sha, timestamp, isTrusted), sort ascending, drop first 100.
        val all = buildList {
            state.trustedShas.forEach { (sha, ts) -> add(Triple(sha, ts, true)) }
            state.rejectedShas.forEach { (sha, ts) -> add(Triple(sha, ts, false)) }
        }.sortedBy { it.second }

        val toDrop = all.take(100)
        for ((sha, _, isTrusted) in toDrop) {
            if (isTrusted) state.trustedShas.remove(sha)
            else state.rejectedShas.remove(sha)
        }
    }

    companion object {
        fun getInstance(project: Project): HookTrustStore = project.service<HookTrustStore>()
    }
}
