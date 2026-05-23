package com.workflow.orchestrator.agent.delegation

/**
 * Injectable clock used by [HeartbeatScheduler] and [IdleTimer] so unit tests
 * can drive deterministic time. Production code uses [SystemClock].
 *
 * Plan 3 spec §6.3.
 */
interface Clock {
    fun nowMillis(): Long
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
