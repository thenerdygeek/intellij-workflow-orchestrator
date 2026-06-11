package com.workflow.orchestrator.core.util

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Plugin-lifetime disposable anchors (P2-21, 2026-06-10 perf audit).
 *
 * XML-declared message-bus listeners ([com.workflow.orchestrator.core.events.BranchChangedEventEmitter],
 * [com.workflow.orchestrator.core.autodetect.AutoDetectFileListener]) used to register their
 * disposables directly against the bare [Project] / Application. That is hot-reload-hostile:
 * on dynamic plugin unload, entries parented to the Project/Application outlive the plugin
 * until the project/IDE closes. A light service is disposed BOTH on its scope closing AND on
 * plugin unload, making it the correct parent (the standard "plugin disposable" pattern from
 * the IntelliJ Platform docs).
 *
 * Dual-level: `getInstance()` returns the application-level anchor, `getInstance(project)`
 * the project-level one.
 */
@Service(Service.Level.APP, Service.Level.PROJECT)
class WorkflowPluginDisposable : Disposable {

    override fun dispose() = Unit

    companion object {
        fun getInstance(): WorkflowPluginDisposable =
            ApplicationManager.getApplication().service()

        fun getInstance(project: Project): WorkflowPluginDisposable = project.service()
    }
}
