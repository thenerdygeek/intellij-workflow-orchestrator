package com.workflow.orchestrator.bamboo.ui

import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.bamboo.model.BuildError
import com.workflow.orchestrator.bamboo.model.ErrorSeverity
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.io.OutputStream
import javax.swing.*
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class StageDetailPanel(
    private val project: Project,
    private val parentDisposable: Disposable
) : JPanel(BorderLayout()) {

    companion object {
        private val ERROR_COLOR = JBColor(Color(0xCC, 0x33, 0x33), Color(0xFF, 0x66, 0x66))
        private val WARNING_COLOR = JBColor(Color(0xCC, 0x99, 0x33), Color(0xFF, 0xCC, 0x66))
        private const val TESTS_TAB_INDEX = 2
    }

    private val logPane = JTextPane().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f).toInt())
        border = JBUI.Borders.empty(8)
    }

    private val errorListModel = DefaultListModel<BuildError>()
    private val errorList = JBList(errorListModel).apply {
        cellRenderer = ErrorListCellRenderer()
    }

    private val testsPlaceholder = JPanel(BorderLayout()).apply {
        add(JBLabel("No test results available.").apply {
            horizontalAlignment = SwingConstants.CENTER
        }, BorderLayout.CENTER)
    }

    private val tabbedPane = JBTabbedPane().apply {
        addTab("Log", JBScrollPane(logPane))
        addTab("Errors", JBScrollPane(errorList))
        addTab("Tests", testsPlaceholder)
    }

    init {
        border = JBUI.Borders.empty()
        add(tabbedPane, BorderLayout.CENTER)
    }

    fun showLog(log: String, errors: List<BuildError>) {
        val doc = logPane.styledDocument
        doc.remove(0, doc.length)
        for (line in log.lines()) {
            val attrs = SimpleAttributeSet()
            when {
                line.contains("[ERROR]") -> {
                    StyleConstants.setForeground(attrs, ERROR_COLOR)
                    StyleConstants.setBold(attrs, true)
                }
                line.contains("[WARNING]") -> {
                    StyleConstants.setForeground(attrs, WARNING_COLOR)
                }
            }
            doc.insertString(doc.length, line + "\n", attrs)
        }
        logPane.caretPosition = 0

        errorListModel.clear()
        errors.forEach { errorListModel.addElement(it) }

        if (errors.any { it.severity == ErrorSeverity.ERROR }) {
            tabbedPane.selectedIndex = 1
        }
    }

    /**
     * Shows test results using IntelliJ's native SMTRunnerConsoleView.
     * Creates an interactive test tree with green/red markers, timing, and
     * clickable navigation to test source. Falls back to plain text if
     * the SMTRunner integration fails.
     */
    fun showTestResults(teamCityMessages: List<String>) {
        if (teamCityMessages.isEmpty()) return

        try {
            val processHandler = TeamCityMessageProcessHandler()
            val executor = DefaultRunExecutor.getRunExecutorInstance()

            val consoleProperties = SMTRunnerConsoleProperties(
                project,
                SurefireTestRunProfile,
                "Surefire Tests",
                executor
            )

            val consoleView = SMTestRunnerConnectionUtil.createAndAttachConsole(
                "Surefire Tests",
                processHandler,
                consoleProperties
            )
            Disposer.register(parentDisposable, consoleView)

            tabbedPane.setComponentAt(TESTS_TAB_INDEX, consoleView.component)
            tabbedPane.selectedIndex = TESTS_TAB_INDEX

            processHandler.startNotify()
            for (message in teamCityMessages) {
                processHandler.notifyTextAvailable("$message\n", ProcessOutputTypes.STDOUT)
            }
            processHandler.destroyProcess()
        } catch (e: Exception) {
            // Fallback: plain text display of test results
            val fallbackPane = JTextPane().apply {
                isEditable = false
                font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f).toInt())
                border = JBUI.Borders.empty(8)
                text = teamCityMessages.joinToString("\n")
            }
            tabbedPane.setComponentAt(TESTS_TAB_INDEX, JBScrollPane(fallbackPane))
            tabbedPane.selectedIndex = TESTS_TAB_INDEX
        }
    }

    fun showEmpty() {
        logPane.text = ""
        errorListModel.clear()
        tabbedPane.setComponentAt(TESTS_TAB_INDEX, testsPlaceholder)
    }

    private class ErrorListCellRenderer : ColoredListCellRenderer<BuildError>() {
        override fun customizeCellRenderer(
            list: JList<out BuildError>,
            value: BuildError?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            value ?: return
            border = JBUI.Borders.empty(4, 8)

            val prefix = when (value.severity) {
                ErrorSeverity.ERROR -> "ERROR"
                ErrorSeverity.WARNING -> "WARN"
            }
            val location = if (value.filePath != null) {
                val line = value.lineNumber?.let { ":$it" } ?: ""
                " ${value.filePath}$line"
            } else ""

            val attrs = when (value.severity) {
                ErrorSeverity.ERROR -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, ERROR_COLOR)
                ErrorSeverity.WARNING -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, WARNING_COLOR)
            }
            append("[$prefix]$location — ${value.message}", attrs)
        }
    }
}

/**
 * Minimal [RunProfile] so we can construct [SMTRunnerConsoleProperties]
 * without a real [RunConfiguration]. The console only needs the project
 * and framework name — it never executes this profile.
 */
private object SurefireTestRunProfile : RunProfile {
    override fun getState(executor: Executor, environment: ExecutionEnvironment) = null
    override fun getName(): String = "Surefire Tests"
    override fun getIcon() = null
}

/**
 * Lightweight [ProcessHandler] that feeds pre-built TeamCity service
 * messages to [SMTRunnerConsoleView]. No real process is spawned.
 */
private class TeamCityMessageProcessHandler : ProcessHandler() {
    override fun destroyProcessImpl() { notifyProcessTerminated(0) }
    override fun detachProcessImpl() { notifyProcessDetached() }
    override fun detachIsDefault(): Boolean = false
    override fun getProcessInput(): OutputStream? = null
}
