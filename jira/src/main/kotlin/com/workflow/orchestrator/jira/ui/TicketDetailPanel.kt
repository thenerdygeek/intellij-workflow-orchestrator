package com.workflow.orchestrator.jira.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.jira.api.dto.JiraAttachment
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import com.workflow.orchestrator.jira.api.dto.JiraIssueLink
import com.workflow.orchestrator.jira.service.AttachmentDownloadService
import com.workflow.orchestrator.jira.service.IssueDetailCache
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.ImageIcon
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTextPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

/**
 * Rich detail panel shown when a ticket is selected in the Sprint Dashboard.
 *
 * Displays ticket header, info cards (assignee, sprint, dates),
 * dependency list, and description.
 */
class TicketDetailPanel(private val project: com.intellij.openapi.project.Project) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {

    private val log = Logger.getInstance(TicketDetailPanel::class.java)

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(16, 16)
    }

    private val scrollPane = JBScrollPane(
        contentPanel,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    ).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
    }

    private val emptyLabel = JBLabel("Select a ticket to view details").apply {
        foreground = JBColor.GRAY
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
    }

    private val attachmentDownloadService: AttachmentDownloadService by lazy { AttachmentDownloadService(project) }

    private val quickCommentPanel = QuickCommentPanel(project).apply {
        isVisible = false
    }

    init {
        isOpaque = false
        background = JBColor.PanelBackground
        showEmpty()
    }

    fun showEmpty() {
        removeAll()
        add(emptyLabel, BorderLayout.CENTER)
        quickCommentPanel.isVisible = false
        revalidate()
        repaint()
    }

    private var currentIssueKey: String? = null
    private var currentWorklogSection: WorklogSection? = null
    private var currentDevStatusSection: DevStatusSection? = null
    private var lazyLoadJob: kotlinx.coroutines.Job? = null
    private val lazyScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    // Lazy-loaded section placeholders
    private val commentsPlaceholder = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(JBLabel("Loading comments...").apply {
            foreground = JBColor.GRAY; border = JBUI.Borders.empty(8)
        }, BorderLayout.CENTER)
    }

    fun showIssue(issue: JiraIssue) {
        log.info("[Jira:UI] Showing detail for ${issue.key}")
        currentIssueKey = issue.key
        lazyLoadJob?.cancel()

        // Dispose previous lazy-loaded sections to prevent scope leaks
        currentWorklogSection?.dispose()
        currentWorklogSection = null
        currentDevStatusSection?.dispose()
        currentDevStatusSection = null

        contentPanel.removeAll()

        // Immediate sections (from cached sprint data)
        addHeader(issue)
        addVerticalSpace(8)
        addTransitionButton(issue)
        addVerticalSpace(12)
        addInfoCards(issue)
        addLabelsAndComponents(issue)
        addDescription(issue)
        addSubtasks(issue)
        addDependencies(issue)

        // Pull Requests (lazy-loaded via dev-status API)
        addVerticalSpace(12)
        addSectionHeader("Pull Requests")
        val devStatusSection = DevStatusSection(project)
        currentDevStatusSection = devStatusSection
        addFullWidthComponent(devStatusSection)
        devStatusSection.loadDevStatus(issue.id)

        // Worklog summary (lazy-loaded)
        addVerticalSpace(12)
        addSectionHeader("Time Logged")
        val worklogSection = WorklogSection(project)
        currentWorklogSection = worklogSection
        addFullWidthComponent(worklogSection)
        worklogSection.loadWorklogs(issue.key)

        // Lazy-loaded sections (show placeholders, fetch in background)
        if (issue.fields.attachment.isNotEmpty()) {
            addVerticalSpace(12)
            addAttachmentsHeader(issue.fields.attachment)
            addAttachments(issue.fields.attachment)
        }

        addVerticalSpace(12)
        addSectionHeader("Comments")
        addVerticalSpace(4)
        addFullWidthComponent(commentsPlaceholder)

        removeAll()
        add(scrollPane, BorderLayout.CENTER)

        // Quick comment bar pinned to bottom
        quickCommentPanel.issueKey = issue.key
        quickCommentPanel.isVisible = true
        quickCommentPanel.onCommentPosted = {
            // Refresh comments section after posting
            lazyLoadComments(issue.key)
        }
        add(quickCommentPanel, BorderLayout.SOUTH)

        revalidate()
        repaint()

        // Lazy load comments
        lazyLoadComments(issue.key)
    }

    private fun addTransitionButton(issue: JiraIssue) {
        val buttonPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
        }

        val transitionBtn = javax.swing.JButton("${issue.fields.status.name} ▾").apply {
            addActionListener {
                com.workflow.orchestrator.core.workflow.JiraTicketProvider.getInstance()
                    ?.showTransitionDialog(
                        project,
                        issue.key
                    ) {
                        // Refresh after transition
                        log.info("[Jira:UI] Ticket ${issue.key} transitioned, refreshing")
                    }
            }
        }
        buttonPanel.add(transitionBtn)

        val jiraUrl = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(
            project
        ).connections.jiraUrl.orEmpty().trimEnd('/')
        if (jiraUrl.isNotBlank()) {
            val openLink = JBLabel("<html><a href=''>Open in Jira ↗</a></html>").apply {
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                        com.intellij.ide.BrowserUtil.browse("$jiraUrl/browse/${issue.key}")
                    }
                })
            }
            buttonPanel.add(openLink)
        }

        addFullWidthComponent(buttonPanel)
    }

    private fun addLabelsAndComponents(issue: JiraIssue) {
        val labels = issue.fields.labels
        val components = issue.fields.components
        if (labels.isEmpty() && components.isEmpty()) return

        addVerticalSpace(12)
        val tagsPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply {
            isOpaque = false
        }

        for (comp in components) {
            tagsPanel.add(JBLabel(comp.name, AllIcons.Nodes.Module, SwingConstants.LEFT).apply {
                font = font.deriveFont(JBUI.scale(10).toFloat())
                foreground = StatusColors.SECONDARY_TEXT
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(StatusColors.BORDER, 1, true),
                    JBUI.Borders.empty(2, 6)
                )
            })
        }
        for (label in labels) {
            tagsPanel.add(JBLabel(label).apply {
                foreground = StatusColors.SECONDARY_TEXT
                font = font.deriveFont(JBUI.scale(10).toFloat())
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(StatusColors.BORDER, 1, true),
                    JBUI.Borders.empty(2, 6)
                )
            })
        }

        addFullWidthComponent(tagsPanel)
    }

    private fun addSubtasks(issue: JiraIssue) {
        val subtasks = issue.fields.subtasks
        if (subtasks.isEmpty()) return

        addVerticalSpace(12)
        addSectionHeader("Subtasks (${subtasks.size})")
        addVerticalSpace(4)

        val subtaskPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        for (subtask in subtasks) {
            val row = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(4, 8)
                maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(28))
            }

            val leftPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                isOpaque = false
            }

            val statusKey = subtask.fields.status.statusCategory?.key ?: ""
            val statusIcon = when (statusKey) {
                "done" -> "✓"
                "indeterminate" -> "⟳"
                else -> "○"
            }
            val statusColor = TicketListCellRenderer.getStatusColor(statusKey)
            leftPanel.add(JBLabel(statusIcon).apply { foreground = statusColor })
            leftPanel.add(JBLabel(subtask.key).apply {
                font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
                foreground = StatusColors.LINK
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            })
            leftPanel.add(JBLabel(truncate(subtask.fields.summary, 50)).apply {
                font = font.deriveFont(JBUI.scale(11).toFloat())
                foreground = StatusColors.SECONDARY_TEXT
                if (subtask.fields.summary.length > 50) toolTipText = subtask.fields.summary
            })

            row.add(leftPanel, BorderLayout.CENTER)
            row.add(createStatusPill(subtask.fields.status.name, statusKey), BorderLayout.EAST)

            subtaskPanel.add(row)
        }

        addFullWidthComponent(subtaskPanel)
    }

    private fun addAttachmentsHeader(attachments: List<JiraAttachment>) {
        val headerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        headerPanel.add(JBLabel("Attachments (${attachments.size})").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat())
            foreground = JBColor.foreground()
            border = JBUI.Borders.emptyLeft(2)
        }, BorderLayout.WEST)

        val downloadAllLabel = JBLabel("Download All").apply {
            foreground = StatusColors.LINK
            font = JBUI.Fonts.smallFont()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.emptyRight(4)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    val label = e?.source as? JBLabel ?: return
                    if (!label.isEnabled) return
                    val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
                        .withTitle("Select Download Directory")
                    FileChooser.chooseFile(descriptor, project, null) { chosenDir ->
                        val targetDir = File(chosenDir.path)
                        label.isEnabled = false
                        label.text = "Downloading..."
                        label.foreground = StatusColors.SECONDARY_TEXT
                        label.cursor = Cursor.getDefaultCursor()
                        lazyScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val (results, summary) = attachmentDownloadService
                                .downloadAll(attachments, targetDir)
                            withContext(kotlinx.coroutines.Dispatchers.EDT) {
                                label.isEnabled = true
                                label.text = "Download All"
                                label.foreground = StatusColors.LINK
                                label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

                                val notificationType = when {
                                    results.isEmpty() -> NotificationType.ERROR
                                    results.size < attachments.size -> NotificationType.WARNING
                                    else -> NotificationType.INFORMATION
                                }
                                NotificationGroupManager.getInstance()
                                    .getNotificationGroup("Workflow Orchestrator")
                                    .createNotification(
                                        "Attachments Downloaded",
                                        summary,
                                        notificationType
                                    )
                                    .notify(project)
                            }
                        }
                    }
                }
            })
        }
        headerPanel.add(downloadAllLabel, BorderLayout.EAST)

        contentPanel.add(headerPanel)
    }

    private fun addAttachments(attachments: List<JiraAttachment>) {
        addVerticalSpace(4)
        val attPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
            isOpaque = false
        }

        val thumbW = JBUI.scale(80)
        val thumbH = JBUI.scale(60)

        for (att in attachments) {
            val sizeStr = when {
                att.size < 1024 -> "${att.size} B"
                att.size < 1024 * 1024 -> "${att.size / 1024} KB"
                else -> "${"%.1f".format(att.size / (1024.0 * 1024.0))} MB"
            }

            val isImage = att.mimeType?.startsWith("image/") == true && att.thumbnail != null

            val card = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(StatusColors.BORDER, 1, true),
                    JBUI.Borders.empty(4, 8)
                )
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

                // Top row with three-dot menu button
                val topRow = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(16))
                    alignmentX = Component.LEFT_ALIGNMENT
                }
                val moreBtn = JBLabel(AllIcons.Actions.More).apply {
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    toolTipText = "Actions"
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            showAttachmentPopupMenu(att, e.component, e.x, e.y)
                        }
                    })
                }
                topRow.add(JPanel().apply { isOpaque = false }, BorderLayout.CENTER)
                topRow.add(moreBtn, BorderLayout.EAST)
                add(topRow)

                if (isImage) {
                    // Placeholder gray box for thumbnail
                    val thumbnailLabel = JBLabel().apply {
                        preferredSize = Dimension(thumbW, thumbH)
                        minimumSize = Dimension(thumbW, thumbH)
                        maximumSize = Dimension(thumbW, thumbH)
                        horizontalAlignment = SwingConstants.CENTER
                        verticalAlignment = SwingConstants.CENTER
                        isOpaque = true
                        background = JBColor(0xE8E8E8, 0x3C3C3C)
                        icon = AllIcons.Actions.ShowAsTree // small loading placeholder icon
                        alignmentX = Component.CENTER_ALIGNMENT
                    }
                    add(thumbnailLabel)

                    // Lazy-load thumbnail
                    lazyScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val image = attachmentDownloadService.downloadThumbnail(att)
                            if (image != null) {
                                val scaled = scaleToFit(image, thumbW, thumbH)
                                val imageIcon = ImageIcon(scaled)
                                withContext(kotlinx.coroutines.Dispatchers.EDT) {
                                    thumbnailLabel.icon = imageIcon
                                    thumbnailLabel.isOpaque = false
                                    thumbnailLabel.revalidate()
                                    thumbnailLabel.repaint()
                                }
                            }
                        } catch (e: Exception) {
                            log.warn("[Jira:UI] Failed to load thumbnail for ${att.filename}", e)
                        }
                    }
                } else {
                    // Non-image: show file type icon
                    val fileIcon = when {
                        att.filename.endsWith(".pdf") -> AllIcons.FileTypes.Text
                        else -> AllIcons.FileTypes.Any_type
                    }
                    add(JBLabel(fileIcon).apply {
                        alignmentX = Component.CENTER_ALIGNMENT
                    })
                }

                // Filename label
                add(JBLabel(truncate(att.filename, 16)).apply {
                    font = font.deriveFont(JBUI.scale(11).toFloat())
                    alignmentX = Component.CENTER_ALIGNMENT
                    if (att.filename.length > 16) toolTipText = att.filename
                })
                // Size label
                add(JBLabel(sizeStr).apply {
                    font = font.deriveFont(JBUI.scale(9).toFloat())
                    foreground = StatusColors.SECONDARY_TEXT
                    alignmentX = Component.CENTER_ALIGNMENT
                })

                // Right-click context menu
                val popupListener = object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        if (e.isPopupTrigger) showAttachmentPopupMenu(att, e.component, e.x, e.y)
                    }
                    override fun mouseReleased(e: MouseEvent) {
                        if (e.isPopupTrigger) showAttachmentPopupMenu(att, e.component, e.x, e.y)
                    }
                    override fun mouseClicked(e: MouseEvent) {
                        if (!e.isPopupTrigger && e.button == MouseEvent.BUTTON1) {
                            BrowserUtil.browse(att.content)
                        }
                    }
                }
                addMouseListener(popupListener)
            }
            attPanel.add(card)
        }

        addFullWidthComponent(attPanel)
    }

    private fun showAttachmentPopupMenu(att: JiraAttachment, component: Component, x: Int, y: Int) {
        val menu = JPopupMenu()

        menu.add(JMenuItem("Open in Editor").apply {
            addActionListener {
                lazyScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val result = attachmentDownloadService.downloadAttachment(att)
                    if (result != null) {
                        withContext(kotlinx.coroutines.Dispatchers.EDT) {
                            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(result.file)
                            if (vf != null) {
                                FileEditorManager.getInstance(project).openFile(vf, true)
                            }
                        }
                    }
                }
            }
        })

        menu.add(JMenuItem("Open in Browser").apply {
            addActionListener {
                BrowserUtil.browse(att.content)
            }
        })

        menu.add(JMenuItem("Download").apply {
            addActionListener {
                val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
                    .withTitle("Select Download Directory")
                FileChooser.chooseFile(descriptor, project, null) { chosenDir ->
                    val targetDir = File(chosenDir.path)
                    lazyScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val result = attachmentDownloadService.downloadAttachment(att, targetDir)
                        if (result != null) {
                            withContext(kotlinx.coroutines.Dispatchers.EDT) {
                                NotificationGroupManager.getInstance()
                                    .getNotificationGroup("Workflow Orchestrator")
                                    .createNotification(
                                        "Attachment Downloaded",
                                        "${att.filename} saved to ${targetDir.absolutePath}",
                                        NotificationType.INFORMATION
                                    )
                                    .notify(project)
                            }
                        }
                    }
                }
            }
        })

        menu.show(component, x, y)
    }

    private fun scaleToFit(image: BufferedImage, maxW: Int, maxH: Int): java.awt.Image {
        val srcW = image.width
        val srcH = image.height
        val scale = minOf(maxW.toDouble() / srcW, maxH.toDouble() / srcH, 1.0)
        val targetW = (srcW * scale).toInt().coerceAtLeast(1)
        val targetH = (srcH * scale).toInt().coerceAtLeast(1)
        return image.getScaledInstance(targetW, targetH, java.awt.Image.SCALE_SMOOTH)
    }

    private fun lazyLoadComments(issueKey: String) {
        lazyLoadJob = lazyScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(200) // debounce
            if (currentIssueKey != issueKey) return@launch

            val cache = IssueDetailCache.getInstance(project)
            val cached = cache.get(issueKey)

            val comments = if (cached?.comments != null) {
                cached.comments
            } else {
                val jiraService = project.getService(com.workflow.orchestrator.core.services.JiraService::class.java)
                val result = jiraService.getComments(issueKey)
                if (!result.isError) {
                    cache.updateComments(issueKey, result.data)
                    result.data
                } else {
                    log.warn("[Jira:UI] Failed to load comments for $issueKey: ${result.summary}")
                    emptyList()
                }
            }

            javax.swing.SwingUtilities.invokeLater {
                if (currentIssueKey != issueKey) return@invokeLater
                renderComments(comments)
            }
        }
    }

    private fun renderComments(comments: List<com.workflow.orchestrator.core.model.jira.JiraCommentData>) {
        // Replace placeholder with actual comments
        contentPanel.remove(commentsPlaceholder)

        if (comments.isEmpty()) {
            val emptyPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JBLabel("No comments").apply {
                    foreground = StatusColors.SECONDARY_TEXT
                    border = JBUI.Borders.empty(8)
                }, BorderLayout.CENTER)
            }
            addFullWidthComponent(emptyPanel)
        } else {
            addSectionHeader("Comments (${comments.size})")
            addVerticalSpace(4)
            for (comment in comments.take(20)) {
                addComment(comment)
            }
            if (comments.size > 20) {
                val morePanel = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(JBLabel("${comments.size - 20} more comments — open in Jira to see all").apply {
                        foreground = StatusColors.SECONDARY_TEXT
                        font = font.deriveFont(JBUI.scale(10).toFloat())
                        border = JBUI.Borders.empty(4, 8)
                    }, BorderLayout.CENTER)
                }
                addFullWidthComponent(morePanel)
            }
        }

        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun addComment(comment: com.workflow.orchestrator.core.model.jira.JiraCommentData) {
        val commentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 8)
        }

        val authorName = comment.author
        val timeAgo = formatRelativeTime(comment.created)

        val headerRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
        }
        headerRow.add(AvatarCircle(authorName).apply {
            preferredSize = java.awt.Dimension(JBUI.scale(20), JBUI.scale(20))
            minimumSize = preferredSize
            maximumSize = preferredSize
        })
        headerRow.add(JBLabel(authorName).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
        })
        headerRow.add(JBLabel("• $timeAgo").apply {
            foreground = StatusColors.SECONDARY_TEXT
            font = font.deriveFont(JBUI.scale(10).toFloat())
        })

        commentPanel.add(headerRow, BorderLayout.NORTH)

        val bodyLabel = JBLabel("<html><body style='font-size:${JBUI.scale(11)}px; color:${colorToHex(StatusColors.SECONDARY_TEXT)};'>" +
            escapeHtml(comment.body).replace("\n", "<br>") +
            "</body></html>").apply {
            border = JBUI.Borders.emptyLeft(JBUI.scale(26))
        }
        commentPanel.add(bodyLabel, BorderLayout.CENTER)

        addFullWidthComponent(commentPanel)
    }

    override fun dispose() {
        lazyLoadJob?.cancel()
        lazyScope.cancel()
        currentWorklogSection?.dispose()
        currentWorklogSection = null
        currentDevStatusSection?.dispose()
        currentDevStatusSection = null
        quickCommentPanel.dispose()
    }

    private fun formatRelativeTime(isoDate: String): String {
        return try {
            val created = java.time.Instant.parse(isoDate.replace("+0000", "Z"))
            val duration = java.time.Duration.between(created, java.time.Instant.now())
            when {
                duration.toMinutes() < 60 -> "${duration.toMinutes()}m ago"
                duration.toHours() < 24 -> "${duration.toHours()}h ago"
                duration.toDays() < 30 -> "${duration.toDays()}d ago"
                else -> isoDate.take(10)
            }
        } catch (_: Exception) {
            isoDate.take(10)
        }
    }

    // ---------------------------------------------------------------
    // Header: key + summary, status pill, priority, issue type
    // ---------------------------------------------------------------

    private fun addHeader(issue: JiraIssue) {
        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyBottom(8)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // Key + badges row
        val keyRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        keyRow.add(JBLabel(issue.key).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
            foreground = StatusColors.LINK
        })

        // Separator
        val sep = JPanel().apply {
            isOpaque = true
            background = StatusColors.BORDER
            preferredSize = Dimension(1, JBUI.scale(14))
        }
        keyRow.add(sep)

        keyRow.add(createStatusPill(
            issue.fields.status.name,
            issue.fields.status.statusCategory?.key ?: ""
        ))

        val issueType = issue.fields.issuetype?.name ?: "Task"
        keyRow.add(createTextTag(issueType))

        val priorityName = issue.fields.priority?.name ?: "Medium"
        val priorityColor = TicketListCellRenderer.getPriorityColor(priorityName)
        keyRow.add(createColoredTag(priorityName, priorityColor))

        headerPanel.add(keyRow)

        // Title (large, below key)
        headerPanel.add(JBLabel(issue.fields.summary).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(16).toFloat())
            foreground = JBColor.foreground()
            border = JBUI.Borders.empty(4, 0, 0, 0)
            alignmentX = Component.LEFT_ALIGNMENT
        })

        addFullWidthComponent(headerPanel)
    }

    // ---------------------------------------------------------------
    // Info cards: Assignee, Sprint, Dates
    // ---------------------------------------------------------------

    private fun addInfoCards(issue: JiraIssue) {
        val cardsPanel = object : JPanel(GridLayout(2, 2, JBUI.scale(8), JBUI.scale(8))) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(0)
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = StatusColors.CARD_BG
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(),
                    JBUI.scale(6).toFloat(), JBUI.scale(6).toFloat()))
                // Subtle border
                g2.color = JBColor(0xE8EAED, 0x2D3035)
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f,
                    JBUI.scale(6).toFloat(), JBUI.scale(6).toFloat()))
                g2.dispose()
            }
        }
        cardsPanel.border = JBUI.Borders.empty(12)

        // Assignee
        val assignee = issue.fields.assignee
        cardsPanel.add(createInfoCell("ASSIGNEE") {
            if (assignee != null) {
                add(AvatarCircle(assignee.displayName).apply {
                    val s = JBUI.scale(20)
                    preferredSize = Dimension(s, s)
                    minimumSize = preferredSize
                    maximumSize = preferredSize
                })
                add(createSpacerH(6))
                add(JBLabel(assignee.displayName).apply {
                    foreground = JBColor.foreground()
                    font = font.deriveFont(JBUI.scale(11).toFloat())
                })
            } else {
                add(JBLabel("Unassigned").apply {
                    foreground = StatusColors.SECONDARY_TEXT
                    font = font.deriveFont(JBUI.scale(11).toFloat())
                })
            }
        })

        // Sprint
        val sprint = issue.fields.sprint
        cardsPanel.add(createInfoCell("SPRINT") {
            add(JBLabel(sprint?.name ?: "No sprint").apply {
                foreground = if (sprint != null) JBColor.foreground() else StatusColors.SECONDARY_TEXT
                font = font.deriveFont(JBUI.scale(11).toFloat())
            })
        })

        // Created
        val created = issue.fields.created?.take(10) ?: "-"
        cardsPanel.add(createInfoCell("CREATED") {
            add(JBLabel(created).apply {
                foreground = JBColor.foreground()
                font = font.deriveFont(JBUI.scale(11).toFloat())
            })
        })

        // Updated
        val updated = issue.fields.updated?.take(10) ?: "-"
        cardsPanel.add(createInfoCell("UPDATED") {
            add(JBLabel(updated).apply {
                foreground = JBColor.foreground()
                font = font.deriveFont(JBUI.scale(11).toFloat())
            })
        })

        addFullWidthComponent(cardsPanel)
    }

    private fun createInfoCell(title: String, contentBuilder: JPanel.() -> Unit): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4)

            add(JBLabel(title).apply {
                foreground = StatusColors.SECONDARY_TEXT
                font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
                border = JBUI.Borders.emptyBottom(4)
                alignmentX = Component.LEFT_ALIGNMENT
            })

            val body = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                contentBuilder()
            }
            add(body)
        }
    }

    // ---------------------------------------------------------------
    // Dependencies section
    // ---------------------------------------------------------------

    private fun addDependencies(issue: JiraIssue) {
        val links = issue.fields.issuelinks
        if (links.isEmpty()) return

        addVerticalSpace(12)
        addSectionHeader("Dependencies (${links.size})")
        addVerticalSpace(4)

        val depsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        for (link in links) {
            depsPanel.add(createDependencyRow(link))
            depsPanel.add(createVerticalSpacer(4))
        }

        addFullWidthComponent(depsPanel)
    }

    private fun createDependencyRow(link: JiraIssueLink): JPanel {
        val linkedIssue = link.inwardIssue ?: link.outwardIssue ?: return JPanel()
        val isBlockedBy = link.type.inward.contains("block", ignoreCase = true) && link.inwardIssue != null
        val isBlocking = link.type.outward.contains("block", ignoreCase = true) && link.outwardIssue != null
        val direction = if (link.inwardIssue != null) link.type.inward else link.type.outward

        val tint = when {
            isBlockedBy -> BLOCKED_BY_TINT
            isBlocking -> BLOCKS_TINT
            else -> StatusColors.CARD_BG
        }

        return object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(4, 8)
                preferredSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = tint
                g2.fill(RoundRectangle2D.Float(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    JBUI.scale(4).toFloat(), JBUI.scale(4).toFloat()
                ))
                g2.dispose()
            }
        }.apply {
            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                isOpaque = false
            }

            // Status dot
            val statusKey = linkedIssue.fields.status.statusCategory?.key ?: ""
            leftPanel.add(StatusDot(TicketListCellRenderer.getStatusColor(statusKey)))

            // Key + summary
            leftPanel.add(JBLabel(linkedIssue.key).apply {
                font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
                foreground = JBColor.foreground()
            })
            leftPanel.add(JBLabel(truncate(linkedIssue.fields.summary, 50)).apply {
                font = font.deriveFont(JBUI.scale(11).toFloat())
                foreground = StatusColors.SECONDARY_TEXT
                if (linkedIssue.fields.summary.length > 50) toolTipText = linkedIssue.fields.summary
            })

            add(leftPanel, BorderLayout.CENTER)

            // Direction + status label on right
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                isOpaque = false
            }
            rightPanel.add(JBLabel(direction).apply {
                font = font.deriveFont(Font.ITALIC, JBUI.scale(10).toFloat())
                foreground = StatusColors.SECONDARY_TEXT
            })
            rightPanel.add(JBLabel("[${linkedIssue.fields.status.name}]").apply {
                font = font.deriveFont(JBUI.scale(10).toFloat())
                foreground = StatusColors.SECONDARY_TEXT
            })
            add(rightPanel, BorderLayout.EAST)
        }
    }

    // ---------------------------------------------------------------
    // Description section
    // ---------------------------------------------------------------

    private fun addDescription(issue: JiraIssue) {
        val desc = issue.fields.description
        if (desc.isNullOrBlank()) return

        addVerticalSpace(12)
        addSectionHeader("Description")
        addVerticalSpace(4)

        val textPane = JTextPane().apply {
            contentType = "text/html"
            text = "<html><body style='font-family: sans-serif; font-size: ${JBUI.scale(12)}px; " +
                    "color: ${colorToHex(JBColor.foreground())}; margin: 0; padding: 0;'>" +
                    escapeHtml(desc).replace("\n", "<br>") +
                    "</body></html>"
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty(4, 8)
            background = StatusColors.CARD_BG
        }

        val descPanel = object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(4)
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = StatusColors.CARD_BG
                g2.fill(RoundRectangle2D.Float(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    JBUI.scale(6).toFloat(), JBUI.scale(6).toFloat()
                ))
                g2.dispose()
            }
        }
        descPanel.add(textPane, BorderLayout.CENTER)
        addFullWidthComponent(descPanel)
    }

    // ---------------------------------------------------------------
    // Reusable UI builders
    // ---------------------------------------------------------------

    private fun createStatusPill(text: String, categoryKey: String): JPanel {
        val pillColor = TicketListCellRenderer.getStatusColor(categoryKey)
        return object : JPanel() {
            init {
                isOpaque = false
                val fm = getFontMetrics(font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat()))
                val textW = fm.stringWidth(text.uppercase())
                preferredSize = Dimension(
                    textW + JBUI.scale(12),
                    fm.height + JBUI.scale(6)
                )
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                com.workflow.orchestrator.core.ui.RenderingUtils.applyDesktopHints(g2)
                g2.color = pillColor
                g2.fill(RoundRectangle2D.Float(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    JBUI.scale(4).toFloat(), JBUI.scale(4).toFloat()
                ))
                g2.color = JBColor.WHITE
                g2.font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
                val fm = g2.fontMetrics
                val textX = (width - fm.stringWidth(text.uppercase())) / 2
                val textY = (height + fm.ascent - fm.descent) / 2
                g2.drawString(text.uppercase(), textX, textY)
                g2.dispose()
            }
        }
    }

    private fun createColoredTag(text: String, color: Color): JPanel {
        return object : JPanel() {
            init {
                isOpaque = false
                val fm = getFontMetrics(font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat()))
                preferredSize = Dimension(
                    fm.stringWidth(text) + JBUI.scale(12),
                    fm.height + JBUI.scale(6)
                )
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                com.workflow.orchestrator.core.ui.RenderingUtils.applyDesktopHints(g2)
                // Semi-transparent background (resolve JBColor for current theme)
                val resolved = color
                g2.color = Color(resolved.red, resolved.green, resolved.blue, 30)
                g2.fill(RoundRectangle2D.Float(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    JBUI.scale(4).toFloat(), JBUI.scale(4).toFloat()
                ))
                g2.color = color
                g2.font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
                val fm = g2.fontMetrics
                g2.drawString(text, (width - fm.stringWidth(text)) / 2, (height + fm.ascent - fm.descent) / 2)
                g2.dispose()
            }
        }
    }

    private fun createTextTag(text: String): JBLabel {
        return JBLabel(text).apply {
            font = font.deriveFont(JBUI.scale(10).toFloat())
            foreground = StatusColors.SECONDARY_TEXT
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(StatusColors.BORDER, 1, true),
                JBUI.Borders.empty(2, 6)
            )
        }
    }

    private fun createInfoCard(title: String, contentBuilder: JPanel.() -> Unit): JPanel {
        return object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(8)
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = StatusColors.CARD_BG
                g2.fill(RoundRectangle2D.Float(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    JBUI.scale(6).toFloat(), JBUI.scale(6).toFloat()
                ))
                g2.dispose()
            }
        }.apply {
            val titleLabel = JBLabel(title).apply {
                foreground = StatusColors.SECONDARY_TEXT
                font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
                border = JBUI.Borders.emptyBottom(4)
            }
            add(titleLabel, BorderLayout.NORTH)

            val body = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                contentBuilder()
            }
            add(body, BorderLayout.CENTER)
        }
    }

    private fun addSectionHeader(text: String) {
        val label = JBLabel(text.uppercase()).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
            foreground = StatusColors.SECONDARY_TEXT
            border = JBUI.Borders.emptyLeft(2)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        contentPanel.add(label)
    }

    private fun addFullWidthComponent(component: JPanel) {
        component.alignmentX = Component.LEFT_ALIGNMENT
        component.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        contentPanel.add(component)
    }

    private fun addVerticalSpace(dp: Int) {
        contentPanel.add(createVerticalSpacer(dp))
    }

    private fun createVerticalSpacer(dp: Int): JPanel {
        return JPanel().apply {
            isOpaque = false
            preferredSize = Dimension(0, JBUI.scale(dp))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(dp))
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }

    private fun createSpacerH(dp: Int): JPanel {
        return JPanel().apply {
            isOpaque = false
            preferredSize = Dimension(JBUI.scale(dp), 0)
        }
    }

    // ---------------------------------------------------------------
    // Mini custom components
    // ---------------------------------------------------------------

    /** Circular avatar with the first letter of the name. */
    private class AvatarCircle(name: String) : JPanel() {
        private val letter = name.firstOrNull()?.uppercase() ?: "?"
        private val avatarColor = AVATAR_COLORS[name.hashCode().and(0x7FFFFFFF) % AVATAR_COLORS.size]

        init {
            isOpaque = false
            val size = JBUI.scale(24)
            preferredSize = Dimension(size, size)
            minimumSize = preferredSize
            maximumSize = preferredSize
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            com.workflow.orchestrator.core.ui.RenderingUtils.applyDesktopHints(g2)
            val size = minOf(width, height).toFloat()
            g2.color = avatarColor
            g2.fill(Ellipse2D.Float(0f, 0f, size, size))
            g2.color = JBColor.WHITE
            g2.font = g2.font.deriveFont(Font.BOLD, size * 0.45f)
            val fm = g2.fontMetrics
            val textX = ((size - fm.stringWidth(letter)) / 2).toInt()
            val textY = ((size + fm.ascent - fm.descent) / 2).toInt()
            g2.drawString(letter, textX, textY)
            g2.dispose()
        }
    }

    /** Small colored dot indicating status. */
    private class StatusDot(private val color: Color) : JPanel() {
        init {
            isOpaque = false
            val size = JBUI.scale(8)
            preferredSize = Dimension(size, size)
            minimumSize = preferredSize
            maximumSize = preferredSize
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fill(Ellipse2D.Float(0f, 0f, width.toFloat(), height.toFloat()))
            g2.dispose()
        }
    }

    // ---------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------

    companion object {
        private val BLOCKED_BY_TINT = JBColor(0xFFF0F0, 0x3D2020)
        private val BLOCKS_TINT = JBColor(0xFFF8F0, 0x3D3020)

        private val AVATAR_COLORS = listOf(
            JBColor(0x0969DA, 0x58A6FF),
            JBColor(0x1B7F37, 0x3FB950),
            JBColor(0xBF5700, 0xDB6D28),
            JBColor(0x8250DF, 0xBC8CFF),
            JBColor(0xCF222E, 0xF85149),
            JBColor(0x0E8A16, 0x2EA043)
        )

        private fun truncate(text: String, maxLength: Int): String {
            return if (text.length <= maxLength) text
            else text.substring(0, maxLength - 1) + "\u2026"
        }

        private fun escapeHtml(text: String): String {
            return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
        }

        private fun colorToHex(color: Color): String {
            return String.format("#%02x%02x%02x", color.red, color.green, color.blue)
        }
    }
}
