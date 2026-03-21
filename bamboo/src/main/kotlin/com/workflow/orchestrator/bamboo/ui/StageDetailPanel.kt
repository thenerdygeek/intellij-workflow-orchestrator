package com.workflow.orchestrator.bamboo.ui

import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.RegexpFilter
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.bamboo.model.BuildError
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.OutputStream
import javax.swing.*

/**
 * Detail panel for viewing build job output.
 *
 * Uses IntelliJ's native ConsoleView which provides:
 * - Efficient rendering for huge logs (virtual scrolling)
 * - Auto-clickable file paths + line numbers (navigate to source)
 * - Java stack trace parsing (clickable exception traces)
 * - Color-coded output (ERROR = red, WARNING = yellow)
 * - Built-in search, copy, scroll-to-end
 */
class StageDetailPanel(
    private val project: Project,
    private val parentDisposable: Disposable
) : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(StageDetailPanel::class.java)

    private var consoleView: ConsoleView? = null
    private val consolePanel = JPanel(BorderLayout())

    private val testsPlaceholder = JPanel(BorderLayout()).apply {
        add(JBLabel("No test results available.").apply {
            horizontalAlignment = SwingConstants.CENTER
        }, BorderLayout.CENTER)
    }

    // "Open full log in editor" button bar
    private val logActionBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
        isVisible = false
    }
    private val truncationLabel = JBLabel("").apply {
        foreground = com.workflow.orchestrator.core.ui.StatusColors.WARNING
        font = font.deriveFont(JBUI.scale(10).toFloat())
    }
    private val openInEditorButton = JButton("Open full log in editor").apply {
        font = font.deriveFont(JBUI.scale(11).toFloat())
    }

    private val tabbedPane = JBTabbedPane().apply {
        val logTab = JPanel(BorderLayout()).apply {
            add(logActionBar, BorderLayout.NORTH)
            add(consolePanel, BorderLayout.CENTER)
        }
        addTab("Log", logTab)
        addTab("Tests", testsPlaceholder)
    }

    // Store full log for "Open in editor"
    private var fullLogText: String? = null

    companion object {
        private const val MAX_DISPLAY_CHARS = 50_000
        private const val MAX_DOWNLOAD_CHARS = 2_000_000
    }

    init {
        border = JBUI.Borders.empty()
        add(tabbedPane, BorderLayout.CENTER)

        logActionBar.add(truncationLabel)
        logActionBar.add(openInEditorButton)

        openInEditorButton.addActionListener { openFullLogInEditor() }

        createConsoleView()
    }

    private fun createConsoleView() {
        // Dispose old console if exists
        consoleView?.let { Disposer.dispose(it) }

        val builder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
        builder.setViewer(true)

        val console = builder.getConsole()
        Disposer.register(parentDisposable, console)

        // Add filters for clickable file navigation
        try {
            // Maven-style file:line filter (clickable file paths in build output)
            console.addMessageFilter(
                RegexpFilter(project, RegexpFilter.FILE_PATH_MACROS + ":" + RegexpFilter.LINE_MACROS)
            )
        } catch (_: Exception) {}

        consoleView = console
        consolePanel.removeAll()
        consolePanel.add(console.component, BorderLayout.CENTER)
        consolePanel.revalidate()
    }

    /**
     * Show build log in the console.
     * @param logText The full log text
     * @param errors Parsed errors (unused now — ConsoleView handles highlighting natively)
     */
    fun showLog(logText: String, errors: List<BuildError>) {
        val console = consoleView ?: return
        console.clear()

        fullLogText = logText
        val truncated = logText.length > MAX_DISPLAY_CHARS

        if (truncated) {
            val displayText = logText.takeLast(MAX_DISPLAY_CHARS)
            truncationLabel.text = "Log truncated (${logText.length / 1000}K chars). Showing last ${MAX_DISPLAY_CHARS / 1000}K."
            logActionBar.isVisible = true

            console.print("... [Log truncated — showing last ${MAX_DISPLAY_CHARS / 1000}K chars] ...\n\n",
                ConsoleViewContentType.SYSTEM_OUTPUT)

            // Print lines with appropriate content types for coloring
            printLogLines(console, displayText)
        } else {
            logActionBar.isVisible = false
            printLogLines(console, logText)
        }

        console.scrollTo(0)
        tabbedPane.selectedIndex = 0
    }

    private fun printLogLines(console: ConsoleView, text: String) {
        // Feed lines in chunks for better performance
        val lines = text.lines()
        val sb = StringBuilder()
        var currentType = ConsoleViewContentType.NORMAL_OUTPUT

        for (line in lines) {
            val lineType = classifyLine(line)
            if (lineType != currentType && sb.isNotEmpty()) {
                console.print(sb.toString(), currentType)
                sb.clear()
            }
            currentType = lineType
            sb.append(line).append('\n')
        }
        if (sb.isNotEmpty()) {
            console.print(sb.toString(), currentType)
        }
    }

    private fun classifyLine(line: String): ConsoleViewContentType {
        return when {
            line.contains("[ERROR]") || line.contains("BUILD FAILURE") ||
            line.contains("FAILED") || line.contains("Exception") ||
            line.contains("error:") -> ConsoleViewContentType.ERROR_OUTPUT
            line.contains("[WARNING]") || line.contains("warning:") ->
                ConsoleViewContentType.LOG_WARNING_OUTPUT
            line.contains("[INFO]") -> ConsoleViewContentType.NORMAL_OUTPUT
            line.startsWith("\tat ") || line.startsWith("Caused by:") ->
                ConsoleViewContentType.ERROR_OUTPUT
            else -> ConsoleViewContentType.NORMAL_OUTPUT
        }
    }

    private fun openFullLogInEditor() {
        val text = fullLogText ?: return
        try {
            val tempFile = java.io.File.createTempFile("bamboo-build-", ".log")
            tempFile.writeText(text)
            tempFile.deleteOnExit()

            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tempFile)
            if (vf != null) {
                FileEditorManager.getInstance(project).openFile(vf, true)
                log.info("[Build:Detail] Opened full log in editor: ${tempFile.absolutePath}")
            }
        } catch (e: Exception) {
            log.warn("[Build:Detail] Failed to open log in editor: ${e.message}")
        }
    }

    /**
     * Shows test results using IntelliJ's native SMTRunnerConsoleView.
     */
    fun showTestResults(teamCityMessages: List<String>) {
        if (teamCityMessages.isEmpty()) return

        try {
            val processHandler = TeamCityMessageProcessHandler()
            val executor = DefaultRunExecutor.getRunExecutorInstance()

            val consoleProperties = object : SMTRunnerConsoleProperties(
                project,
                SurefireTestRunProfile,
                "Surefire Tests",
                executor
            ) {
                override fun getTestLocator(): SMTestLocator = BambooTestLocator
            }

            val testConsole = SMTestRunnerConnectionUtil.createAndAttachConsole(
                "Surefire Tests",
                processHandler,
                consoleProperties
            )
            Disposer.register(parentDisposable, testConsole)

            tabbedPane.setComponentAt(1, testConsole.component)
            tabbedPane.selectedIndex = 1

            processHandler.startNotify()
            for (message in teamCityMessages) {
                processHandler.notifyTextAvailable("$message\n", ProcessOutputTypes.STDOUT)
            }
            processHandler.destroyProcess()
        } catch (e: Exception) {
            log.warn("[Build:Detail] SMTRunner failed, using fallback", e)
            val fallbackLabel = JBLabel("Test results: ${teamCityMessages.size} messages").apply {
                border = JBUI.Borders.empty(8)
            }
            tabbedPane.setComponentAt(1, fallbackLabel)
            tabbedPane.selectedIndex = 1
        }
    }

    fun showEmpty() {
        consoleView?.clear()
        fullLogText = null
        logActionBar.isVisible = false
        tabbedPane.setComponentAt(1, testsPlaceholder)
    }
}

private object SurefireTestRunProfile : RunProfile {
    override fun getState(executor: Executor, environment: ExecutionEnvironment) = null
    override fun getName(): String = "Surefire Tests"
    override fun getIcon() = null
}

private class TeamCityMessageProcessHandler : ProcessHandler() {
    override fun destroyProcessImpl() { notifyProcessTerminated(0) }
    override fun detachProcessImpl() { notifyProcessDetached() }
    override fun detachIsDefault(): Boolean = false
    override fun getProcessInput(): OutputStream? = null
}

/**
 * Resolves java:test:// and java:suite:// location hints to PSI elements.
 * Enables double-click navigation from Bamboo test results to source code.
 *
 * Uses generic PSI APIs (FilenameIndex) instead of JavaPsiFacade to avoid
 * a compile dependency on the Java plugin.
 *
 * Format:
 *   java:suite://com.example.ClassName
 *   java:test://com.example.ClassName/methodName
 */
private object BambooTestLocator : SMTestLocator {
    override fun getLocation(
        protocol: String,
        path: String,
        project: com.intellij.openapi.project.Project,
        scope: GlobalSearchScope
    ): List<com.intellij.execution.Location<*>> {
        val results = mutableListOf<com.intellij.execution.Location<*>>()
        try {
            val cleanPath = path.removePrefix("//")
            val className = when (protocol) {
                "java:suite" -> cleanPath
                "java:test" -> cleanPath.split("/", limit = 2)[0]
                else -> return emptyList()
            }
            val methodName = if (protocol == "java:test") {
                cleanPath.split("/", limit = 2).getOrNull(1)
            } else null

            val simpleClassName = className.substringAfterLast('.')
            val expectedPackage = className.substringBeforeLast('.', "")

            // Find files matching the simple class name (.java or .kt)
            for (ext in listOf("$simpleClassName.java", "$simpleClassName.kt")) {
                @Suppress("DEPRECATION")
                val files = com.intellij.psi.search.FilenameIndex.getFilesByName(
                    project, ext, scope
                )
                for (psiFile in files) {
                    // Verify package matches by checking the file path
                    val packagePath = expectedPackage.replace('.', '/')
                    if (packagePath.isEmpty() || psiFile.virtualFile.path.contains(packagePath)) {
                        if (methodName != null) {
                            // Try to find the method by text search in the file
                            val methodElement = findMethodInFile(psiFile, methodName)
                            if (methodElement != null) {
                                results.add(com.intellij.execution.PsiLocation.fromPsiElement(methodElement))
                            } else {
                                results.add(com.intellij.execution.PsiLocation.fromPsiElement(psiFile))
                            }
                        } else {
                            results.add(com.intellij.execution.PsiLocation.fromPsiElement(psiFile))
                        }
                        break
                    }
                }
                if (results.isNotEmpty()) break
            }
        } catch (_: Exception) {}
        return results
    }

    private fun findMethodInFile(psiFile: com.intellij.psi.PsiFile, methodName: String): com.intellij.psi.PsiElement? {
        // Walk the PSI tree to find a method/function with the given name
        var found: com.intellij.psi.PsiElement? = null
        psiFile.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                if (found != null) return
                // Match method declarations: "void methodName(" or "fun methodName("
                if (element is com.intellij.psi.PsiNamedElement && element.name == methodName) {
                    val text = element.text
                    if (text.contains("(")) {
                        found = element
                        return
                    }
                }
                super.visitElement(element)
            }
        })
        return found
    }
}
