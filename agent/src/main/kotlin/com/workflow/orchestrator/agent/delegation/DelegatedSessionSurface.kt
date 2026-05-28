package com.workflow.orchestrator.agent.delegation

/** Where an accepted inbound delegation should surface. There is NO background execution — a
 *  delegated session runs only while it is the focused session. */
enum class DelegatedSurface { RUN_NOW, QUEUE_INCOMING }

object DelegatedSessionSurface {
    /** @param tabBusy true if the IDE-B agent tab currently has a running or displayed session. */
    fun decide(tabBusy: Boolean): DelegatedSurface =
        if (tabBusy) DelegatedSurface.QUEUE_INCOMING else DelegatedSurface.RUN_NOW
}
