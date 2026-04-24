package com.workflow.orchestrator.jira.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-scoped, in-memory store of branches the user has dismissed ticket
 * detection for. Resets on IDE restart — mirrors the previous behavior when
 * this set lived as a `companion object` on `BranchChangeTicketDetector`.
 *
 * Thread-safe: backed by a concurrent set so it can be read/written from
 * coroutine IO dispatchers and the EDT.
 */
@Service(Service.Level.PROJECT)
class DismissedBranchStore {

    private val dismissed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun isDismissed(branch: String): Boolean = branch in dismissed

    fun markDismissed(branch: String) {
        dismissed.add(branch)
    }

    fun remove(branch: String) {
        dismissed.remove(branch)
    }

    companion object {
        fun getInstance(project: Project): DismissedBranchStore = project.service()
    }
}
