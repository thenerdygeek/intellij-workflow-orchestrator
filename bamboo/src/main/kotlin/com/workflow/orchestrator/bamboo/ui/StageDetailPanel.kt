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
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
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
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import javax.swing.JButton
import com.intellij.util.ui.JBUI
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
/**
 * Implements [Disposable] and registers itself with [parentDisposable] so that
 * [scope] is always cancelled when the parent is disposed, even if disposal races
 * with construction (Disposer.register no-ops when the parent is already disposed,
 * which would silently leak the scope). By making the panel itself the Disposable,
 * the scope lifetime is tied to the panel's own identity rather than a lambda
 * closure. Closes audit finding bamboo:F-10.
 */
class StageDetailPanel(
    private val project: Project,
    private val parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

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

        /** Action name returned by [hitTestArtifactButton] for the Download button. */
        internal const val ARTIFACT_ACTION_DOWNLOAD = "download"

        /** Action name returned by [hitTestArtifactButton] for the Open button. */
        internal const val ARTIFACT_ACTION_OPEN = "open"

        /**
         * Pure hit-test function: returns the action name for the button whose
         * bounds (in local coordinates) contain [pt], or null if no button is hit.
         *
         * Extracted as a pure function so it can be tested without a running IDE
         * (see [ArtifactButtonHitTestTest]).
         *
         * @param pt the click point in the local coordinate space of the actionsPanel.
         * @param downloadBounds bounds of the Download button in actionsPanel coords.
         * @param openBounds bounds of the Open button in actionsPanel coords, or null
         *   when the artifact has no Open button (non-HTML artifacts).
         */
        internal fun resolveButtonAction(
            pt: Point,
            downloadBounds: java.awt.Rectangle?,
            openBounds: java.awt.Rectangle?
        ): String? {
            if (downloadBounds != null && downloadBounds.contains(pt)) return ARTIFACT_ACTION_DOWNLOAD
            if (openBounds != null && openBounds.contains(pt)) return ARTIFACT_ACTION_OPEN
            return null
        }

        /**
         * Returns the safe basename for a server-supplied artifact name, stripping
         * all path separators and `..` components so the resulting name can be
         * appended to a user-chosen directory without escaping it.
         *
         * Returns `null` when the stripped name is blank, `.`, or `..`.
         * (Audit finding bamboo:F-12)
         *
         * @param rawName  The artifact name from the Bamboo API response.
         * @return         The sanitised single filename component, or `null` if the
         *                 name is unsafe.
         */
        internal fun safeArtifactBasename(rawName: String): String? {
            // java.io.File(name).name extracts the final path component on the
            // current OS.  We also normalise forward and backward slashes
            // explicitly so the check is OS-independent.
            val stripped = rawName
                .replace('/', java.io.File.separatorChar)
                .replace('\\', java.io.File.separatorChar)
            val basename = java.io.File(stripped).name
            return if (basename.isBlank() || basename == ".." || basename == ".") null
            else basename
        }

        /**
         * Returns `true` iff [targetFile] is canonically contained within
         * [chosenDir] — i.e. the resolved absolute path starts with the resolved
         * absolute path of [chosenDir] followed by the platform separator.
         *
         * A malformed artifact name that somehow survives [safeArtifactBasename]
         * would still be caught here.
         * (Audit finding bamboo:F-12)
         */
        internal fun isContainedIn(targetFile: java.io.File, chosenDir: java.io.File): Boolean {
            val root = chosenDir.canonicalPath
            val target = targetFile.canonicalPath
            return target.startsWith(root + java.io.File.separator) || target == root
        }
    }

    init {
        // Register the panel itself as the Disposable child so dispose() is called when
        // the parent is disposed. The previous lambda Disposable { scope.cancel() } was
        // created after scope allocation; if parentDisposable was already disposed at
        // that moment the lambda was never registered and the scope leaked permanently.
        Disposer.register(parentDisposable, this)

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

        // B5 — artifact buttons are inside a rubber-stamp renderer and cannot receive
        // mouse events directly. We hit-test the click point ourselves via a
        // MouseAdapter on the list, configure the renderer for the target row (to get
        // its laid-out bounds), then invoke the correct action.
        artifactsList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                // Left-button only — JButtons only ever responded to left-click,
                // and right-click/middle-click must not trigger downloads.
                if (!SwingUtilities.isLeftMouseButton(e)) return
                dispatchArtifactClick(e)
            }
        })
        artifactsList.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                val action = hitTestArtifactButton(e.point)
                artifactsList.cursor = if (action != null) {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else {
                    Cursor.getDefaultCursor()
                }
            }
        })

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

        // PathLinkResolver-backed filter: turns file-path substrings in log lines
        // (e.g. "src/main/kotlin/Foo.kt:42: error:") into clickable hyperlinks that
        // open the file at the given line. Validates via the same security rules used
        // by the agent webview so only in-project paths are made clickable.
        console.addMessageFilter(FilePathHyperlinkFilter(project))

        consoleView = console
        consolePanel.removeAll()
        consolePanel.add(console.component, BorderLayout.CENTER)
        consolePanel.revalidate()
    }

    /**
     * Show build log in the console.
     * @param logText The full log text
     */
    fun showLog(logText: String) {
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
                } else if (result.data!!.isEmpty()) {
                    artifactsPanel.add(artifactsPlaceholder, BorderLayout.CENTER)
                } else {
                    artifactsModel.clear()
                    result.data!!.forEach { artifactsModel.addElement(it) }
                    val scrollPane = JBScrollPane(artifactsList).apply { border = JBUI.Borders.empty() }
                    artifactsPanel.add(scrollPane, BorderLayout.CENTER)
                }
                artifactsPanel.revalidate()
                artifactsPanel.repaint()
            }
        }
    }

    /**
     * Identifies which artifact action ("download" or "open") is under [point]
     * in [artifactsList] coordinates, or null if the point is not over a button.
     *
     * NOT pure — this mutates the shared rubber-stamp renderer (it reconfigures
     * the stamp for the target row and re-lays it out). The pure decision
     * function is [resolveButtonAction] (see [ArtifactButtonHitTestTest]).
     *
     * The cell renderer is configured for the row at [point] so that its layout
     * reflects the row's actual content (some rows show only "Download", others
     * show both buttons). The renderer then deterministically lays out the
     * stamp AND its nested containers at the cell size, and button sub-rects
     * are tested.
     *
     * @return "download", "open", or null.
     */
    internal fun hitTestArtifactButton(point: Point): String? {
        val list = artifactsList
        val index = list.locationToIndex(point)
        if (index < 0) return null
        val cellBounds = list.getCellBounds(index, index) ?: return null
        if (!cellBounds.contains(point)) return null

        val artifact = artifactsModel.getElementAt(index)
        if (artifact.downloadUrl.isEmpty()) return null

        // Configure the renderer for this row so actionsPanel layout reflects the
        // current row's data (number of buttons present).
        val renderer = list.cellRenderer as? ArtifactCellRenderer ?: return null
        renderer.getListCellRendererComponent(
            list,
            artifact,
            index,
            list.isSelectedIndex(index),
            list.hasFocus()
        )
        renderer.layoutForHitTest(cellBounds.width, cellBounds.height)

        // Translate click point into cell-relative coordinates.
        val cellPt = Point(point.x - cellBounds.x, point.y - cellBounds.y)
        return renderer.buttonActionAt(cellPt)
    }

    private fun dispatchArtifactClick(e: java.awt.event.MouseEvent) {
        val action = hitTestArtifactButton(e.point) ?: return
        val index = artifactsList.locationToIndex(e.point)
        if (index < 0) return
        val artifact = artifactsModel.getElementAt(index)
        when (action) {
            ARTIFACT_ACTION_DOWNLOAD -> downloadArtifact(artifact)
            ARTIFACT_ACTION_OPEN -> BrowserUtil.browse(artifact.downloadUrl)
        }
    }

    private fun downloadArtifact(artifact: ArtifactData) {
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
            .withTitle("Select Download Directory")
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return

        // Security: strip all path separators and `..` components from the
        // server-supplied artifact name, then verify containment.
        // (Audit finding bamboo:F-12)
        val safeBasename = safeArtifactBasename(artifact.name)
        if (safeBasename == null) {
            log.warn("[Build:Artifacts] Rejected unsafe artifact name='${artifact.name.take(200)}'")
            WorkflowNotificationService.getInstance(project).notifyError(
                WorkflowNotificationService.GROUP_BUILD,
                "Artifact Download Failed",
                "Artifact name '${artifact.name}' is unsafe and was rejected."
            )
            return
        }
        val chosenDir = java.io.File(chosen.path)
        val targetFile = java.io.File(chosenDir, safeBasename)
        if (!isContainedIn(targetFile, chosenDir)) {
            log.warn(
                "[Build:Artifacts] Containment check failed for artifact='${artifact.name.take(200)}'; " +
                    "resolved='${targetFile.canonicalPath}' is outside chosen='${chosenDir.canonicalPath}'"
            )
            WorkflowNotificationService.getInstance(project).notifyError(
                WorkflowNotificationService.GROUP_BUILD,
                "Artifact Download Failed",
                "Artifact '${artifact.name}' resolved outside the selected directory and was rejected."
            )
            return
        }

        scope.launch {
            val result = bambooService.downloadArtifact(artifact.downloadUrl, targetFile)
            invokeLater {
                val ns = WorkflowNotificationService.getInstance(project)
                if (result.data == true) {
                    ns.notifyInfo(
                        WorkflowNotificationService.GROUP_BUILD,
                        "Artifact Downloaded",
                        "Downloaded $safeBasename to ${targetFile.parentFile.absolutePath}"
                    )
                    log.info("[Build:Artifacts] Downloaded $safeBasename to ${targetFile.absolutePath}")
                } else {
                    ns.notifyError(
                        WorkflowNotificationService.GROUP_BUILD,
                        "Artifact Download Failed",
                        "Failed to download $safeBasename: ${result.summary}"
                    )
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

    override fun dispose() {
        scope.cancel()
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
     * Rubber-stamp cell renderer for artifact list items.
     * Stitch design: left border accent (INFO color), monospace artifact name,
     * sharp corners, tonal background shifts.
     *
     * The "Download" and "Open" buttons are PAINTED ONLY — they are part of a
     * rubber-stamp component and cannot receive mouse events. Actual click
     * dispatch is handled by the [MouseAdapter] on [artifactsList] via
     * [hitTestArtifactButton] + [dispatchArtifactClick].
     *
     * P2-20: the [cachedBorder] is allocated once at class init (stable across
     * the renderer's lifetime) instead of on every render call.
     */
    internal inner class ArtifactCellRenderer : ListCellRenderer<ArtifactData> {

        // P2-20: border cached once — avoids re-allocating CompoundBorder + inner
        // JBUI border on every rendered row.
        private val cachedBorder: javax.swing.border.Border = javax.swing.border.CompoundBorder(
            StitchLeftAccentBorder(StatusColors.INFO, JBUI.scale(3)),
            JBUI.Borders.empty(4, 8, 4, 4)
        )

        private val panel = JPanel(BorderLayout()).apply { border = cachedBorder }
        private val nameLabel = JBLabel().apply {
            // Monospace bold for artifact names
            font = Font(Font.MONOSPACED, Font.BOLD, JBUI.Fonts.label().size)
        }
        private val sizeLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            foreground = StatusColors.SECONDARY_TEXT
            border = JBUI.Borders.emptyLeft(8)
        }

        // Buttons are pure rubber-stamp widgets — no ActionListeners. Clicks are
        // forwarded by the list-level MouseAdapter via hitTestArtifactButton().
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

            // Reset ALL per-row properties on every call (rubber-stamp hygiene).
            panel.background = if (isSelected) list.selectionBackground else list.background
            panel.isOpaque = true
            nameLabel.foreground = if (isSelected) list.selectionForeground else StatusColors.LINK
            // border is stable — no re-allocation needed (P2-20 fix)
            panel.border = cachedBorder

            return panel
        }

        /**
         * Deterministically lays out the rubber-stamp [panel] and its nested
         * containers at the given cell size, so that button bounds reflect the
         * CURRENTLY CONFIGURED row.
         *
         * `Container.doLayout()` is NOT recursive — it positions only direct
         * children. Laying out [panel] positions infoPanel/actionsPanel, but
         * the buttons INSIDE [actionsPanel] would keep stale bounds from the
         * last paint-pass `CellRendererPane.validate()` (i.e. the layout of the
         * last painted row, not the row being hit-tested). So [actionsPanel] is
         * laid out explicitly as well. (W6-D3 review I1)
         */
        fun layoutForHitTest(cellWidth: Int, cellHeight: Int) {
            panel.setBounds(0, 0, cellWidth, cellHeight)
            panel.doLayout()
            actionsPanel.doLayout()
        }

        /**
         * Returns the action name for the button whose rendered bounds contain
         * [cellPt] (in cell-local coordinates), or null if no button is hit.
         *
         * Must be called AFTER [getListCellRendererComponent] has configured the
         * stamp for the same row AND [layoutForHitTest] has laid it out at the
         * cell size. Delegates the actual decision to the pure, unit-tested
         * [resolveButtonAction] (W6-D3 review I2): a button removed from
         * [actionsPanel] for the current row (`parent == null`) is passed as
         * null bounds so its stale rectangle can never produce a hit.
         */
        fun buttonActionAt(cellPt: Point): String? {
            // actionsPanel is in BorderLayout.EAST of panel; translate into its
            // local coordinate space and bail early if the click misses it entirely.
            if (!actionsPanel.bounds.contains(cellPt)) return null
            val apLoc = actionsPanel.location
            val apPt = Point(cellPt.x - apLoc.x, cellPt.y - apLoc.y)
            return resolveButtonAction(
                apPt,
                downloadBounds = downloadButton.bounds.takeIf { downloadButton.parent != null },
                openBounds = openButton.bounds.takeIf { openButton.parent != null }
            )
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
