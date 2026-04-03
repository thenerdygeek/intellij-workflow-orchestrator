package com.workflow.orchestrator.agent.context

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.util.ProjectIdentifier
import java.io.File

/**
 * Persistent store for learned agent constraints.
 *
 * Constraints are discovered during agent sessions (doom loops, repeated failures)
 * or saved manually by the LLM. They persist to {projectBasePath}/.workflow/agent/guardrails.md
 * and are loaded into the system prompt at session start.
 *
 * The guardrails anchor in EventSourcedContextBridge ensures constraints survive context compression.
 *
 * THREAD SAFETY: Not thread-safe. Access from the ReAct loop's single coroutine context only.
 */
class GuardrailStore(
    private val projectBasePath: File,
    private val maxConstraints: Int = 50
) {
    companion object {
        private val LOG = Logger.getInstance(GuardrailStore::class.java)
        private const val GUARDRAILS_FILE = "guardrails.md"
    }

    private val constraints = mutableListOf<String>()

    val size: Int get() = constraints.size

    fun record(constraint: String) {
        val trimmed = constraint.trim()
        if (trimmed.isBlank()) return
        if (trimmed in constraints) return

        constraints.add(trimmed)
        while (constraints.size > maxConstraints) constraints.removeAt(0)
        LOG.info("GuardrailStore: recorded constraint (${constraints.size}/$maxConstraints): ${trimmed.take(80)}")
    }

    fun load() {
        try {
            val file = File(ProjectIdentifier.agentDir(projectBasePath.absolutePath), GUARDRAILS_FILE)
            if (!file.exists()) return

            val lines = file.readLines()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("- ")) {
                    val constraint = trimmed.removePrefix("- ").trim()
                    if (constraint.isNotBlank() && constraint !in constraints) {
                        constraints.add(constraint)
                    }
                }
            }
            while (constraints.size > maxConstraints) constraints.removeAt(0)
            LOG.info("GuardrailStore: loaded ${constraints.size} constraints from ${file.path}")
        } catch (e: Exception) {
            LOG.warn("GuardrailStore: failed to load guardrails", e)
        }
    }

    fun save() {
        try {
            val dir = ProjectIdentifier.agentDir(projectBasePath.absolutePath)
            dir.mkdirs()
            val file = File(dir, GUARDRAILS_FILE)
            val content = buildString {
                appendLine("# Agent Guardrails")
                appendLine()
                for (c in constraints) {
                    appendLine("- $c")
                }
            }
            // Atomic write: write to temp file, then move atomically
            val tmp = File(dir, "${GUARDRAILS_FILE}.tmp")
            tmp.writeText(content)
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
            LOG.info("GuardrailStore: saved ${constraints.size} constraints to ${file.path}")
        } catch (e: Exception) {
            LOG.warn("GuardrailStore: failed to save guardrails", e)
        }
    }

    fun toContextString(): String {
        if (constraints.isEmpty()) return ""
        return buildString {
            appendLine("<guardrails>")
            appendLine("Learned constraints from previous sessions (follow these strictly):")
            for (c in constraints) {
                appendLine("- $c")
            }
            appendLine("</guardrails>")
        }.trimEnd()
    }

    fun estimateTokens(): Int {
        val s = toContextString()
        return if (s.isEmpty()) 0 else TokenEstimator.estimate(s)
    }

    fun clear() {
        constraints.clear()
    }
}
