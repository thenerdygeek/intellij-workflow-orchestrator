package com.workflow.orchestrator.agent.loop.queue

import java.util.concurrent.ConcurrentHashMap

object UserQueuePolicy : QueueSourcePolicy {
    /** Canonical mid-turn steering prefix. The AgentLoop copy is removed in Phase 1. */
    const val STEERING_MESSAGE_PREFIX =
        "The user sent an additional message while you were working. " +
            "Incorporate their feedback while continuing your current task:\n\n"
    override val kind = QueueSourceKind.USER
    override val priority = 100
    override val resetsUserSilenceCounter = true
    override val autoWakesIdle = false
    override val durable = false
    override val defersCompletion = true
    override fun frame(group: List<QueuedMessage>): String =
        STEERING_MESSAGE_PREFIX + group.joinToString("\n\n") { it.body }
}

object DelegationQueuePolicy : QueueSourcePolicy {
    override val kind = QueueSourceKind.DELEGATION
    override val priority = 70
    override val resetsUserSilenceCounter = false
    override val autoWakesIdle = true
    override val durable = true
    override val defersCompletion = true
    override fun frame(group: List<QueuedMessage>): String =
        "[DELEGATION RESULTS]\n" +
            "These cross-IDE delegation results/questions arrived. Decide whether each needs " +
            "action; if a question is included, answer it via delegation(action=\"answer\"):\n\n" +
            group.joinToString("\n\n---\n\n") { it.body }
}

object BackgroundQueuePolicy : QueueSourcePolicy {
    override val kind = QueueSourceKind.BACKGROUND
    override val priority = 50
    override val resetsUserSilenceCounter = false
    override val autoWakesIdle = true
    override val durable = true
    override val defersCompletion = true
    override fun frame(group: List<QueuedMessage>): String = buildString {
        group.forEach { appendLine(it.body) }
        appendLine()
        append(
            "Decide whether each completion needs action. If it completes the original task or " +
                "requires no follow-up, call attempt_completion. Otherwise continue working.",
        )
    }
}

object MonitorQueuePolicy : QueueSourcePolicy {
    override val kind = QueueSourceKind.MONITOR
    override val priority = 30
    override val resetsUserSilenceCounter = false
    override val autoWakesIdle = true
    override val durable = true
    override val defersCompletion = false
    override fun frame(group: List<QueuedMessage>): String =
        "# Monitor notifications\n" +
            "While you were working, these monitor events fired:\n\n" +
            group.joinToString("\n") { it.body }
}

/** Resolves a [QueueSourcePolicy] by [QueueSourceKind]. Future source = add a value + register here. */
object QueueSourceRegistry {
    private val policies = ConcurrentHashMap<QueueSourceKind, QueueSourcePolicy>()

    init {
        listOf(UserQueuePolicy, DelegationQueuePolicy, BackgroundQueuePolicy, MonitorQueuePolicy)
            .forEach { register(it) }
    }

    fun register(p: QueueSourcePolicy) { policies[p.kind] = p }

    fun policyFor(kind: QueueSourceKind): QueueSourcePolicy =
        policies[kind] ?: error("No QueueSourcePolicy registered for $kind")
}
