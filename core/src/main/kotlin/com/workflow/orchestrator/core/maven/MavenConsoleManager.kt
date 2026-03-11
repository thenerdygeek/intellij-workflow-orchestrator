package com.workflow.orchestrator.core.maven

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project

class MavenConsoleManager(private val project: Project) {

    data class MavenConsoleResult(
        val consoleView: ConsoleView,
        val processHandler: OSProcessHandler
    )

    fun createAndAttach(
        goals: String,
        modules: List<String> = emptyList()
    ): MavenConsoleResult {
        val commandLine = MavenBuildService.getInstance(project)
            .buildCommandLine(goals, modules)

        val consoleView = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .apply {
                addFilter(MavenErrorFilter(project))
                addFilter(MavenWarningFilter(project))
                addFilter(MavenTestFailureFilter(project))
            }
            .console

        val processHandler = OSProcessHandler(commandLine)
        consoleView.attachToProcess(processHandler)
        processHandler.startNotify()

        return MavenConsoleResult(consoleView, processHandler)
    }
}
