package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.security.CommandShape
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-session allowlist of command PREFIXES the user approved via
 * "Approve all <prefix> this session". In-memory, cleared on new chat
 * (decision: session-only, no settings.json persistence). Mirrors
 * [SessionApprovalStore]'s ownership/lifecycle (and its `ConcurrentHashMap.newKeySet`
 * thread-safety) but keyed on prefixes.
 *
 * approve() is the SINGLE normalization point (trim → collapse spaces → lowercase)
 * so the stored key and CommandShape's matcher always agree.
 */
class SessionCommandAllowlist {
    private val prefixes = ConcurrentHashMap.newKeySet<String>()

    fun approve(prefix: String) {
        val n = prefix.trim().replace(WHITESPACE, " ").lowercase()
        if (n.isNotBlank()) prefixes.add(n)
    }

    fun covers(command: String): List<String>? = CommandShape.coveringPrefixes(command, snapshot())

    fun clear() { prefixes.clear() }

    fun snapshot(): Set<String> = prefixes.toSet()

    companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}
