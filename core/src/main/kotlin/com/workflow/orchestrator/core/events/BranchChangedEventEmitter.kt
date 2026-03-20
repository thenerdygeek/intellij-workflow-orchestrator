package com.workflow.orchestrator.core.events

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.BranchChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Listens for git branch changes and emits [WorkflowEvent.BranchChanged] via [EventBus].
 *
 * Registered as a project listener in plugin-withGit.xml so it only activates when
 * Git4Idea is available. Other modules (sonar, bamboo) subscribe to the event to
 * refresh their branch context.
 */
class BranchChangedEventEmitter(private val project: Project) : BranchChangeListener, Disposable {

    private val log = Logger.getInstance(BranchChangedEventEmitter::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        Disposer.register(project, this)
    }

    override fun branchWillChange(branchName: String) {
        // No action needed before branch change
    }

    override fun branchHasChanged(branchName: String) {
        log.info("[Core:Events] Git branch changed to '$branchName', emitting BranchChanged event")
        scope.launch {
            project.getService(EventBus::class.java)
                .emit(WorkflowEvent.BranchChanged(branchName))
        }
    }

    override fun dispose() {
        scope.cancel()
    }
}
