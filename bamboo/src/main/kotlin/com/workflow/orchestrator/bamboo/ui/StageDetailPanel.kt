package com.workflow.orchestrator.bamboo.ui

import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.RegexpFilter
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.SearchTextField
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.bamboo.model.BuildError
import com.workflow.orchestrator.core.model.bamboo.ArtifactData
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.ui.StatusColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.intellij.openapi.application.invokeLater
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
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

    // Log search
    private val logSearchField = SearchTextField(false).apply {
        textEditor.emptyText.text = "Search in log..."
    }
    private val matchCountLabel = JBLabel("").apply {
        font = JBUI.Fonts.smallFont()
        border = JBUI.Borders.emptyLeft(6)
    }
    private val prevMatchButton = JButton("<").apply {
        font = Font(font.family, Font.PLAIN, JBUI.scale(11))
        toolTipText = "Previous match (Shift+Enter)"
        margin = JBUI.insets(1, 4)
    }
    private val nextMatchButton = JButton(">").apply {
        font = Font(font.family, Font.PLAIN, JBUI.scale(11))
        toolTipText = "Next match (Enter)"
        margin = JBUI.insets(1, 4)
    }
    private val searchBarPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(2, 4)
        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
            add(prevMatchButton)
            add(nextMatchButton)
            add(matchCountLabel)
        }
        add(logSearchField, BorderLayout.CENTER)
        add(buttonsPanel, BorderLayout.EAST)
    }
    private var matchOffsets = mutableListOf<Int>()
    private var currentMatchIndex = -1
    private val searchHighlighters = mutableListOf<RangeHighlighter>()

    private val testsPlaceholder = JPanel(BorderLayout()).apply {
        add(JBLabel("No test results available.").apply {
            horizontalAlignment = SwingConstants.CENTER
        }, BorderLayout.CENTER)
    }

    // Artifacts tab
    private val bambooService = project.getService(BambooService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val artifactsModel = DefaultListModel<ArtifactData>()
    private val artifactsList = JBList(artifactsModel)
    private val artifactsPlaceholder = JBLabel("No artifacts for this build.").apply {
        horizontalAlignment = SwingConstants.CENTER
    }
    private val artifactsLoadingLabel = JBLabel("Loading artifacts...").apply {
        horizontalAlignment = SwingConstants.CENTER
        font = JBUI.Fonts.smallFont()
        foreground = StatusColors.SECONDARY_TEXT
    }
    private val artifactsPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(4)
        add(artifactsPlaceholder, BorderLayout.CENTER)
    }

    // "Open full log in editor" button bar
    private val logActionBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
        isVisible = false
    }
    private val truncationLabel = JBLabel("").apply {
        foreground = com.workflow.orchestrator.core.ui.StatusColors.WARNING
        font = JBUI.Fonts.smallFont()
    }
    private val openInEditorButton = JButton("Open full log in editor").apply {
        font = JBUI.Fonts.smallFont()
    }

    private val tabbedPane = JBTabbedPane().apply {
        val logTopPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(searchBarPanel)
            add(logActionBar)
        }
        val logTab = JPanel(BorderLayout()).apply {
            add(logTopPanel, BorderLayout.NORTH)
            add(consolePanel, BorderLayout.CENTER)
        }
        // Stitch design: uppercase tab headers
        addTab("LOG", logTab)
        addTab("TESTS", testsPlaceholder)
        addTab("ARTIFACTS", artifactsPanel)
    }

    // Store full log for "Open in editor"
    private var fullLogText: String? = null

    companion object {
        private const val MAX_DISPLAY_CHARS = 50_000
    }

    init {
        Disposer.register(parentDisposable, Disposable { scope.cancel() })

        border = JBUI.Borders.empty()
        add(tabbedPane, BorderLayout.CENTER)

        logActionBar.add(truncationLabel)
        logActionBar.add(openInEditorButton)

        openInEditorButton.addActionListener { openFullLogInEditor() }

        // Search field listeners
        logSearchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                performSearch(logSearchField.text.trim())
            }
        })
        logSearchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when {
                    e.keyCode == KeyEvent.VK_ENTER && e.isShiftDown -> {
                        navigateMatch(-1)
                        e.consume()
                    }
                    e.keyCode == KeyEvent.VK_ENTER -> {
                        navigateMatch(1)
                        e.consume()
                    }
                    e.keyCode == KeyEvent.VK_ESCAPE -> {
                        logSearchField.text = ""
                        clearSearchHighlights()
                        e.consume()
                    }
                }
            }
        })
        nextMatchButton.addActionListener { navigateMatch(1) }
        prevMatchButton.addActionListener { navigateMatch(-1) }

        // Artifacts list setup
        artifactsList.cellRenderer = ArtifactCellRenderer()
        artifactsList.selectionMode = ListSelectionModel.SINGLE_SELECTION

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

        // Release previous log immediately to avoid holding two logs in memory
        fullLogText = null
        val truncated = logText.length > MAX_DISPLAY_CHARS

        if (truncated) {
            // Only retain the full text when truncated (needed for "Open full log in editor")
            fullLogText = logText
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

    private fun performSearch(query: String) {
        clearSearchHighlights()
        matchOffsets.clear()
        currentMatchIndex = -1

        if (query.isEmpty()) {
            matchCountLabel.text = ""
            return
        }

        val editor = (consoleView as? ConsoleViewImpl)?.editor ?: return
        val text = editor.document.text
        var searchFrom = 0
        while (true) {
            val offset = text.indexOf(query, searchFrom, ignoreCase = true)
            if (offset < 0) break
            matchOffsets.add(offset)
            searchFrom = offset + query.length
        }

        if (matchOffsets.isEmpty()) {
            matchCountLabel.text = "No matches"
            matchCountLabel.foreground = com.workflow.orchestrator.core.ui.StatusColors.WARNING
            return
        }

        // Highlight all matches
        val highlightColor = JBColor(
            java.awt.Color(255, 200, 0, 80),
            java.awt.Color(255, 200, 0, 50)
        )
        val attrs = TextAttributes().apply {
            backgroundColor = highlightColor
        }
        val markupModel = editor.markupModel
        for (offset in matchOffsets) {
            val highlighter = markupModel.addRangeHighlighter(
                offset,
                offset + query.length,
                HighlighterLayer.SELECTION - 1,
                attrs,
                HighlighterTargetArea.EXACT_RANGE
            )
            searchHighlighters.add(highlighter)
        }

        // Navigate to first match
        currentMatchIndex = 0
        scrollToCurrentMatch(editor, query.length)
    }

    private fun navigateMatch(direction: Int) {
        if (matchOffsets.isEmpty()) return
        currentMatchIndex = (currentMatchIndex + direction + matchOffsets.size) % matchOffsets.size
        val editor = (consoleView as? ConsoleViewImpl)?.editor ?: return
        val query = logSearchField.text.trim()
        scrollToCurrentMatch(editor, query.length)
    }

    private fun scrollToCurrentMatch(editor: com.intellij.openapi.editor.Editor, queryLength: Int) {
        if (currentMatchIndex < 0 || currentMatchIndex >= matchOffsets.size) return
        val offset = matchOffsets[currentMatchIndex]

        editor.caretModel.moveToOffset(offset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        editor.selectionModel.setSelection(offset, offset + queryLength)

        matchCountLabel.text = "${currentMatchIndex + 1} of ${matchOffsets.size} matches"
        matchCountLabel.foreground = JBColor.foreground()
    }

    private fun clearSearchHighlights() {
        val editor = (consoleView as? ConsoleViewImpl)?.editor ?: return
        searchHighlighters.forEach { editor.markupModel.removeHighlighter(it) }
        searchHighlighters.clear()
    }

    private fun openFullLogInEditor() {
        val text = fullLogText ?: return
        try {
            val tempFile = java.io.File.createTempFile("bamboo-build-", ".log")
            tempFile.writeText(text)

            // Explicit cleanup when panel is disposed instead of unreliable deleteOnExit()
            Disposer.register(parentDisposable, Disposable {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            })

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

    /**
     * Load and display artifacts for a build result.
     */
    fun showArtifacts(resultKey: String) {
        // Show loading state
        artifactsPanel.removeAll()
        artifactsPanel.add(artifactsLoadingLabel, BorderLayout.CENTER)
        artifactsPanel.revalidate()
        artifactsPanel.repaint()

        scope.launch {
            val result = bambooService.getArtifacts(resultKey)
            invokeLater {
                artifactsPanel.removeAll()
                if (result.isError) {
                    val errorLabel = JBLabel("Error loading artifacts: ${result.summary}").apply {
                        horizontalAlignment = SwingConstants.CENTER
                        foreground = StatusColors.ERROR
                    }
                    artifactsPanel.add(errorLabel, BorderLayout.CENTER)
                } else if (result.data.isEmpty()) {
                    artifactsPanel.add(artifactsPlaceholder, BorderLayout.CENTER)
                } else {
                    artifactsModel.clear()
                    result.data.forEach { artifactsModel.addElement(it) }
                    val scrollPane = JScrollPane(artifactsList)
                    artifactsPanel.add(scrollPane, BorderLayout.CENTER)
                }
                artifactsPanel.revalidate()
                artifactsPanel.repaint()
            }
        }
    }

    private fun downloadArtifact(artifact: ArtifactData) {
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
            .withTitle("Select Download Directory")
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        val targetFile = java.io.File(chosen.path, artifact.name)

        scope.launch {
            val result = bambooService.downloadArtifact(artifact.downloadUrl, targetFile)
            invokeLater {
                if (result.data) {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Workflow Orchestrator")
                        .createNotification(
                            "Downloaded ${artifact.name} to ${targetFile.parentFile.absolutePath}",
                            NotificationType.INFORMATION
                        )
                        .notify(project)
                    log.info("[Build:Artifacts] Downloaded ${artifact.name} to ${targetFile.absolutePath}")
                } else {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Workflow Orchestrator")
                        .createNotification(
                            "Failed to download ${artifact.name}: ${result.summary}",
                            NotificationType.ERROR
                        )
                        .notify(project)
                }
            }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    /**
     * Release the retained full log text when this panel is removed from the
     * component hierarchy (e.g., the user switches away from the Build tab).
     * This prevents large log strings from staying in memory while invisible.
     */
    override fun removeNotify() {
        super.removeNotify()
        fullLogText = null
    }

    fun showEmpty() {
        consoleView?.clear()
        fullLogText = null
        logActionBar.isVisible = false
        tabbedPane.setComponentAt(1, testsPlaceholder)
        artifactsModel.clear()
        artifactsPanel.removeAll()
        artifactsPanel.add(artifactsPlaceholder, BorderLayout.CENTER)
        artifactsPanel.revalidate()
    }

    /**
     * Cached cell renderer for artifact list items.
     * Stitch design: left border accent (INFO color), monospace artifact name,
     * sharp corners, tonal background shifts.
     */
    private inner class ArtifactCellRenderer : ListCellRenderer<ArtifactData> {
        private val panel = JPanel(BorderLayout()).apply {
            border = javax.swing.border.CompoundBorder(
                StitchLeftAccentBorder(StatusColors.INFO, JBUI.scale(3)),
                JBUI.Borders.empty(4, 8, 4, 4)
            )
        }
        private val nameLabel = JBLabel().apply {
            // Monospace bold for artifact names
            font = Font(Font.MONOSPACED, Font.BOLD, JBUI.Fonts.label().size)
        }
        private val sizeLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            foreground = StatusColors.SECONDARY_TEXT
            border = JBUI.Borders.emptyLeft(8)
        }
        private val downloadButton = JButton("Download").apply {
            font = JBUI.Fonts.smallFont()
            margin = JBUI.insets(1, 6)
            isFocusable = false
        }
        private val openButton = JButton("Open").apply {
            font = JBUI.Fonts.smallFont()
            margin = JBUI.insets(1, 6)
            isFocusable = false
        }
        private val infoPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(nameLabel)
            add(sizeLabel)
        }
        private val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
        }

        init {
            panel.add(infoPanel, BorderLayout.CENTER)
            panel.add(actionsPanel, BorderLayout.EAST)

            downloadButton.addActionListener {
                val selectedArtifact = artifactsList.selectedValue
                if (selectedArtifact != null && selectedArtifact.downloadUrl.isNotEmpty()) {
                    downloadArtifact(selectedArtifact)
                }
            }
            openButton.addActionListener {
                val selectedArtifact = artifactsList.selectedValue
                if (selectedArtifact != null && selectedArtifact.downloadUrl.isNotEmpty()) {
                    BrowserUtil.browse(selectedArtifact.downloadUrl)
                }
            }
        }

        override fun getListCellRendererComponent(
            list: JList<out ArtifactData>,
            value: ArtifactData,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            nameLabel.text = value.name
            sizeLabel.text = formatFileSize(value.size)

            actionsPanel.removeAll()
            if (value.downloadUrl.isNotEmpty()) {
                actionsPanel.add(downloadButton)
                if (value.name.endsWith(".html", ignoreCase = true)) {
                    actionsPanel.add(openButton)
                }
            }

            panel.background = if (isSelected) list.selectionBackground else list.background
            nameLabel.foreground = if (isSelected) list.selectionForeground else StatusColors.LINK
            panel.isOpaque = true

            // Re-apply left accent border on each render (border instance is stable)
            panel.border = javax.swing.border.CompoundBorder(
                StitchLeftAccentBorder(StatusColors.INFO, JBUI.scale(3)),
                JBUI.Borders.empty(4, 8, 4, 4)
            )

            return panel
        }
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

            // Bail out during indexing to avoid IndexNotReadyException
            if (com.intellij.openapi.project.DumbService.getInstance(project).isDumb) return emptyList()

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
