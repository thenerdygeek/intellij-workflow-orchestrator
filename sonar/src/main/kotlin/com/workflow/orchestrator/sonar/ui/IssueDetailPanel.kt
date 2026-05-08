package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ai.AgentChatRedirect
import com.workflow.orchestrator.core.model.sonar.SonarRuleData
import com.workflow.orchestrator.core.services.SonarService
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.ui.TimeFormatter
import com.workflow.orchestrator.sonar.model.IssueSeverity
import com.workflow.orchestrator.sonar.model.LineCoverageStatus
import com.workflow.orchestrator.sonar.service.SonarDataService
import kotlinx.coroutines.*
import java.awt.*
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*

/**
 * Detail panel for a selected issue or hotspot. Shows on the right side of a JBSplitter
 * in IssueListPanel: title, metadata, code snippet, rule info, and action buttons.
 */
class IssueDetailPanel(
    private val project: Project
) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ruleCache = ConcurrentHashMap<String, SonarRuleData>()

    /** Callbacks for prev/next navigation, wired by IssueListPanel. */
    var onNavigatePrev: (() -> Unit)? = null
    var onNavigateNext: (() -> Unit)? = null

    // --- UI Components ---

    private val titleLabel = JBLabel().apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat())
        border = JBUI.Borders.emptyBottom(4)
    }

    private val metadataLabel = JBLabel().apply {
        font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        foreground = StatusColors.SECONDARY_TEXT
        border = JBUI.Borders.emptyBottom(8)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    /**
     * SonarQube 9.6+ Clean Code section: cleanCodeAttributeCategory → cleanCodeAttribute
     * breadcrumb plus per-software-quality impact chips. Hidden entirely (HTML empty)
     * when the issue carries no taxonomy data — graceful degradation for older Sonar.
     */
    private val cleanCodeLabel = JBLabel().apply {
        font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        border = JBUI.Borders.emptyBottom(8)
        isVisible = false
    }

    private val codeArea = JTextArea().apply {
        isEditable = false
        font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(12))
        rows = 12
        border = JBUI.Borders.empty(4)
    }

    private val ruleInfoLabel = JBLabel().apply {
        font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        border = JBUI.Borders.empty(8, 0)
        verticalAlignment = SwingConstants.TOP
    }

    private val fixWithAgentButton = JButton("Fix with AI Agent")
    private val openInEditorButton = JButton("Open in Editor")
    private val prevButton = JButton("\u25C0 Prev")
    private val nextButton = JButton("Next \u25B6")

    private val emptyStateLabel = JBLabel("Select an issue to view details").apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
    }

    /** Currently displayed item — needed for action button handlers. */
    private var currentItem: QualityListItem? = null

    /** The content panel shown when an item is selected. */
    private val contentPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(8)

        // Top: title + metadata + clean code section
        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            titleLabel.alignmentX = Component.LEFT_ALIGNMENT
            metadataLabel.alignmentX = Component.LEFT_ALIGNMENT
            cleanCodeLabel.alignmentX = Component.LEFT_ALIGNMENT
            add(titleLabel)
            add(metadataLabel)
            add(cleanCodeLabel)
        }
        add(headerPanel, BorderLayout.NORTH)

        // Center: code snippet + rule info in scrollable area
        val centerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            val codeScroll = JBScrollPane(codeArea).apply {
                preferredSize = Dimension(0, JBUI.scale(200))
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor.border()),
                    JBUI.Borders.empty(2)
                )
            }
            codeScroll.alignmentX = Component.LEFT_ALIGNMENT
            ruleInfoLabel.alignmentX = Component.LEFT_ALIGNMENT
            add(codeScroll)
            add(ruleInfoLabel)
        }
        add(JBScrollPane(centerPanel).apply {
            border = JBUI.Borders.empty()
        }, BorderLayout.CENTER)

        // Bottom: action buttons
        val actionsBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            border = JBUI.Borders.emptyTop(8)
            add(fixWithAgentButton)
            add(openInEditorButton)
            add(Box.createHorizontalStrut(16))
            add(prevButton)
            add(nextButton)
        }
        add(actionsBar, BorderLayout.SOUTH)
    }

    init {
        border = JBUI.Borders.empty()
        showEmptyState()

        fixWithAgentButton.addActionListener { currentItem?.let { fixWithAgent(it) } }
        openInEditorButton.addActionListener { currentItem?.let { navigateToItem(it) } }
        prevButton.addActionListener { onNavigatePrev?.invoke() }
        nextButton.addActionListener { onNavigateNext?.invoke() }

        // Metadata label click navigates to file
        metadataLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                currentItem?.let { navigateToItem(it) }
            }
        })
    }

    /** Show detail for a selected issue or hotspot. */
    fun showItem(item: QualityListItem) {
        currentItem = item
        removeAll()
        add(contentPanel, BorderLayout.CENTER)

        when (item) {
            is QualityListItem.IssueItem -> renderIssue(item)
            is QualityListItem.HotspotItem -> renderHotspot(item)
        }

        revalidate()
        repaint()
    }

    /** Show the empty state when nothing is selected. */
    fun showEmptyState() {
        currentItem = null
        removeAll()
        add(emptyStateLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    // --- Render logic ---

    private fun renderIssue(item: QualityListItem.IssueItem) {
        val issue = item.issue
        val severityColor = when (issue.severity) {
            IssueSeverity.BLOCKER, IssueSeverity.CRITICAL -> StatusColors.ERROR
            IssueSeverity.MAJOR, IssueSeverity.MINOR -> StatusColors.WARNING
            IssueSeverity.INFO -> StatusColors.INFO
        }
        val htmlColor = StatusColors.htmlColor(severityColor as JBColor)
        val typeStr = issue.type.name.replace("_", " ")

        titleLabel.text = "<html><font color='$htmlColor'><b>[${issue.severity}]</b></font> " +
            "$typeStr \u2014 ${issue.message}</html>"

        // Metadata: file path, line, effort, age, rule key
        val metaParts = mutableListOf<String>()
        metaParts.add("${issue.filePath}:${issue.startLine}")
        issue.effort?.let { metaParts.add("Effort: $it") }
        issue.creationDate?.let {
            try {
                val relativeTime = TimeFormatter.relative(Instant.parse(it).toEpochMilli())
                if (relativeTime.isNotEmpty()) metaParts.add(relativeTime)
            } catch (_: Exception) { }
        }
        metaParts.add(issue.rule)
        metadataLabel.text = metaParts.joinToString(" \u2022 ")

        // Clean Code taxonomy (Sonar 9.6+). Hidden when no taxonomy data is present.
        renderCleanCodeSection(issue)

        // AI fix button only for issues
        fixWithAgentButton.isVisible = true
        fixWithAgentButton.isEnabled = AgentChatRedirect.getInstance() != null

        // Load code snippet and rule info asynchronously
        loadCodeSnippet(issue.filePath, issue.startLine, issue.projectKey)
        loadRuleInfo(issue.rule)
    }

    private fun renderCleanCodeSection(issue: com.workflow.orchestrator.sonar.model.MappedIssue) {
        val hasBreadcrumb = !issue.cleanCodeAttribute.isNullOrBlank() && !issue.cleanCodeAttributeCategory.isNullOrBlank()
        val hasImpacts = issue.impacts.isNotEmpty()
        if (!hasBreadcrumb && !hasImpacts) {
            cleanCodeLabel.isVisible = false
            cleanCodeLabel.text = ""
            return
        }
        val sb = StringBuilder("<html>")
        if (hasBreadcrumb) {
            // Breadcrumb in dim secondary color so it reads as a label, not a value
            val crumbColor = StatusColors.htmlColor(StatusColors.SECONDARY_TEXT as JBColor)
            sb.append("<font color='$crumbColor'>Clean Code: <b>")
            sb.append(issue.cleanCodeAttributeCategory)
            sb.append(" \u2192 ")
            sb.append(issue.cleanCodeAttribute)
            sb.append("</b></font>")
        }
        if (hasImpacts) {
            if (hasBreadcrumb) sb.append("&nbsp;&nbsp;\u2022&nbsp;&nbsp;")
            sb.append(issue.impacts.joinToString("&nbsp;&nbsp;") { impact ->
                val color = StatusColors.htmlColor(ImpactRendering.colorFor(impact.severity))
                "<font color='$color'><b>${impact.softwareQuality.name} \u00b7 ${impact.severity.name}</b></font>"
            })
        }
        sb.append("</html>")
        cleanCodeLabel.text = sb.toString()
        cleanCodeLabel.isVisible = true
    }

    private fun renderHotspot(item: QualityListItem.HotspotItem) {
        val hotspot = item.hotspot
        val probabilityColor = when (hotspot.probability) {
            "HIGH" -> StatusColors.ERROR
            "MEDIUM" -> StatusColors.WARNING
            else -> StatusColors.INFO
        }
        val htmlColor = StatusColors.htmlColor(probabilityColor as JBColor)

        titleLabel.text = "<html><font color='$htmlColor'><b>[${hotspot.probability}]</b></font> " +
            "SECURITY HOTSPOT \u2014 ${hotspot.message}</html>"

        // Metadata: file path, line, review status, security category
        // hotspot.component is "projectKey:relativePath" — split into both halves so
        // the snippet loader can resolve the file's owning repo.
        val hotspotProjectKey = hotspot.component.substringBeforeLast(':')
        val filePath = hotspot.component.substringAfterLast(':')
        val lineStr = hotspot.line?.toString() ?: "?"
        val statusText = when (hotspot.status) {
            "TO_REVIEW" -> "To Review"
            "REVIEWED" -> hotspot.resolution?.let { "Reviewed ($it)" } ?: "Reviewed"
            else -> hotspot.status
        }
        metadataLabel.text = "$filePath:$lineStr \u2022 $statusText \u2022 ${hotspot.securityCategory.replace("-", " ")}"

        // Clean Code taxonomy doesn't apply to hotspots — hide the section.
        cleanCodeLabel.isVisible = false
        cleanCodeLabel.text = ""

        // No AI fix for hotspots
        fixWithAgentButton.isVisible = false

        // Load code snippet
        loadCodeSnippet(filePath, hotspot.line ?: 1, hotspotProjectKey)
        // Hotspots use security category rule key — try loading
        ruleInfoLabel.text = ""
    }

    // --- Async loading ---

    private fun loadCodeSnippet(relativePath: String, issueLine: Int, projectKey: String) {
        codeArea.text = "Loading..."
        scope.launch {
            val snippet = buildCodeSnippet(relativePath, issueLine, projectKey)
            withContext(Dispatchers.Main) {
                codeArea.text = snippet
                codeArea.caretPosition = 0
            }
        }
    }

    private suspend fun buildCodeSnippet(
        relativePath: String,
        issueLine: Int,
        projectKey: String,
    ): String {
        // Resolve the file's owning repo via projectKey — `relativePath` is repo-relative
        // (Sonar component path), so the aggregator basePath is wrong on multi-repo setups.
        val settings = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project)
        val owningRepo = settings.getRepos().firstOrNull { it.sonarProjectKey == projectKey }
        val basePath = owningRepo?.localVcsRootPath?.takeIf { it.isNotBlank() }
            ?: project.basePath
            ?: return "Project base path not available"
        val resolver = com.workflow.orchestrator.core.settings.RepoContextResolver.getInstance(project)
        val branch = owningRepo?.localVcsRootPath?.let { resolver.findRepositoryForPath(it)?.currentBranchName }

        val file = File(basePath, relativePath)
        if (!file.exists()) return "File not found: $relativePath"

        val lines = try {
            file.readLines()
        } catch (e: Exception) {
            return "Error reading file: ${e.message}"
        }

        val contextBefore = 5
        val contextAfter = 5
        val startLine = maxOf(1, issueLine - contextBefore)
        val endLine = minOf(lines.size, issueLine + contextAfter)

        // Try to load line coverage data for uncovered line markers
        val coverageMap = try {
            SonarDataService.getInstance(project).getLineCoverage(relativePath, projectKey, branch)
        } catch (_: Exception) {
            emptyMap()
        }

        val sb = StringBuilder()
        for (lineNum in startLine..endLine) {
            val lineIndex = lineNum - 1
            if (lineIndex < 0 || lineIndex >= lines.size) continue

            val lineContent = lines[lineIndex]
            val prefix = when {
                lineNum == issueLine -> ">>>"
                coverageMap[lineNum] == LineCoverageStatus.UNCOVERED -> "  !"
                else -> "   "
            }
            val lineNumStr = lineNum.toString().padStart(4)
            sb.appendLine("$prefix $lineNumStr | $lineContent")
        }
        return sb.toString().trimEnd()
    }

    private fun loadRuleInfo(ruleKey: String) {
        // Check cache first
        val cached = ruleCache[ruleKey]
        if (cached != null) {
            displayRuleInfo(cached)
            return
        }

        ruleInfoLabel.text = "<html><i>Loading rule info...</i></html>"
        scope.launch {
            try {
                val sonarService = project.getService(SonarService::class.java)
                val result = sonarService.getRule(ruleKey)
                if (!result.isError) {
                    val rule = result.data!!
                    ruleCache[ruleKey] = rule
                    withContext(Dispatchers.Main) {
                        displayRuleInfo(rule)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        ruleInfoLabel.text = "<html><i>Could not load rule info</i></html>"
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    ruleInfoLabel.text = ""
                }
            }
        }
    }

    private fun displayRuleInfo(rule: SonarRuleData) {
        val desc = rule.description.take(300).let {
            if (rule.description.length > 300) "$it..." else it
        }
        // Strip HTML tags from description for clean display
        val cleanDesc = desc.replace(Regex("<[^>]*>"), "")
        val remediation = rule.remediation?.let { " \u2022 Remediation: $it" } ?: ""
        ruleInfoLabel.text = "<html><b>${rule.name}</b>$remediation<br/><i>${cleanDesc}</i></html>"
    }

    // --- Navigation ---

    private fun navigateToItem(item: QualityListItem) {
        when (item) {
            is QualityListItem.IssueItem -> {
                val basePath = project.basePath ?: return
                val vf = LocalFileSystem.getInstance().findFileByPath(File(basePath, item.issue.filePath).path) ?: return
                OpenFileDescriptor(project, vf, item.issue.startLine - 1, item.issue.startOffset).navigate(true)
            }
            is QualityListItem.HotspotItem -> {
                val basePath = project.basePath ?: return
                val filePath = item.hotspot.component.substringAfterLast(':')
                val vf = LocalFileSystem.getInstance().findFileByPath(File(basePath, filePath).path) ?: return
                val line = item.hotspot.line?.let { it - 1 } ?: 0
                OpenFileDescriptor(project, vf, line, 0).navigate(true)
            }
        }
    }

    private fun fixWithAgent(item: QualityListItem) {
        if (item !is QualityListItem.IssueItem) return
        val issue = item.issue
        val basePath = project.basePath ?: return
        val absolutePath = File(basePath, issue.filePath).absolutePath
        navigateToItem(item)

        val prompt = buildString {
            appendLine("Fix this SonarQube issue:")
            appendLine()
            appendLine("**Rule:** ${issue.rule}")
            appendLine("**Message:** ${issue.message}")
            appendLine("**File:** ${issue.filePath}")
            appendLine("**Line:** ${issue.startLine}")
            appendLine()
            appendLine("Read the file, understand the context, apply a minimal fix that resolves the issue without changing behavior, and verify it compiles with diagnostics.")
        }

        val redirect = AgentChatRedirect.getInstance()
        if (redirect != null) {
            redirect.sendToAgent(project, prompt, listOf(absolutePath))
        } else {
            com.workflow.orchestrator.core.notifications.WorkflowNotificationService.getInstance(project).notifyError(
                com.workflow.orchestrator.core.notifications.WorkflowNotificationService.GROUP_QUALITY,
                "AI Agent Not Available",
                "Enable the Agent tab in Settings to use AI-powered fixes."
            )
        }
    }

    override fun dispose() {
        scope.cancel()
    }
}
