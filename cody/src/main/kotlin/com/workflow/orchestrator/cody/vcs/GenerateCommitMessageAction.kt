package com.workflow.orchestrator.cody.vcs

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.ui.CommitMessage
import com.workflow.orchestrator.cody.protocol.ContextFile
import com.workflow.orchestrator.cody.protocol.Range
import com.workflow.orchestrator.cody.protocol.Position
import com.workflow.orchestrator.cody.service.CodyChatService
import com.workflow.orchestrator.cody.service.PsiContextEnricher
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoContextResolver
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Action that appears in the commit dialog's message toolbar (Vcs.MessageActionGroup).
 * Generates a commit message using Cody AI from the staged/unstaged changes.
 */
class GenerateCommitMessageAction : AnAction(
    "Generate with Workflow",
    "Generate commit message using Cody AI",
    AllIcons.Actions.Lightning
), com.intellij.openapi.project.DumbAware {

    private val log = Logger.getInstance(GenerateCommitMessageAction::class.java)
    private val generating = AtomicBoolean(false)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val commitMessage = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) as? CommitMessage ?: return
        val modalityState = ModalityState.stateForComponent(commitMessage.editorField)

        if (!generating.compareAndSet(false, true)) {
            log.info("[Cody:CommitMsg] Already generating — ignoring")
            return
        }

        log.info("[Cody:CommitMsg] Generate commit message triggered")

        // Run as a backgroundable task with progress in the status bar.
        // Uses runBlocking inside the background thread (NOT EDT — safe per project rules).
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Generating commit message with Cody...",
            true // cancellable
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Analyzing changes..."

                try {
                    // runBlocking is safe here — we're on a background thread, not EDT
                    val message = runBlocking { generateMessage(project) }

                    if (indicator.isCanceled) {
                        log.info("[Cody:CommitMsg] Generation cancelled by user")
                        return
                    }

                    ApplicationManager.getApplication().invokeLater({
                        if (message != null) {
                            commitMessage.setCommitMessage(message)
                            log.info("[Cody:CommitMsg] Commit message generated (${message.length} chars)")
                        } else {
                            log.warn("[Cody:CommitMsg] Failed to generate commit message")
                        }
                    }, modalityState)
                } catch (e: Exception) {
                    log.warn("[Cody:CommitMsg] Generation failed: ${e.message}")
                } finally {
                    generating.set(false)
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        val hasCommitControl = e.project != null &&
            e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
        e.presentation.isEnabledAndVisible = hasCommitControl
        e.presentation.isEnabled = hasCommitControl && !generating.get()
        if (generating.get()) {
            e.presentation.text = "Generating..."
            e.presentation.icon = AllIcons.Process.Step_1
        } else {
            e.presentation.text = "Generate with Workflow"
            e.presentation.icon = AllIcons.Actions.Lightning
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private suspend fun generateMessage(project: Project): String? {
        return try {
            val settings = PluginSettings.getInstance(project)
            val ticketId = settings.state.activeTicketId.orEmpty()

            // Get the actual git diff
            val diff = getGitDiff(project)
            if (diff.isNullOrBlank()) {
                log.warn("[Cody:CommitMsg] No diff found")
                return null
            }

            // Truncate diff if too large (keep under ~8K chars for the analysis prompt)
            val truncatedDiff = if (diff.length > 8000) {
                diff.take(8000) + "\n... (diff truncated, ${diff.length - 8000} chars omitted)"
            } else diff

            // Build context items with changed line ranges for file-level understanding
            val contextItems = try {
                val changeListManager = ChangeListManager.getInstance(project)
                changeListManager.allChanges.mapNotNull { change ->
                    val afterRevision = change.afterRevision ?: return@mapNotNull null
                    val filePath = afterRevision.file.path
                    val afterContent = try { afterRevision.content } catch (_: Exception) { null }
                        ?: return@mapNotNull ContextFile.fromPath(filePath)
                    val beforeContent = try { change.beforeRevision?.content } catch (_: Exception) { null }

                    val range = if (beforeContent != null) {
                        computeChangedRange(beforeContent, afterContent)
                    } else {
                        val lineCount = minOf(afterContent.count { it == '\n' } + 1, 100)
                        Range(Position(0, 0), Position(lineCount - 1, 0))
                    }
                    ContextFile.fromPath(filePath, range)
                }.take(15)
            } catch (_: Exception) { emptyList() }

            // Build a short summary of changed files for the analysis step
            val filesSummary = try {
                val changeListManager = ChangeListManager.getInstance(project)
                changeListManager.allChanges.mapNotNull { change ->
                    val path = (change.afterRevision ?: change.beforeRevision)?.file?.path ?: return@mapNotNull null
                    val fileName = path.substringAfterLast('/')
                    val changeType = when {
                        change.beforeRevision == null -> "new"
                        change.afterRevision == null -> "deleted"
                        else -> "modified"
                    }
                    "$fileName ($changeType)"
                }.joinToString(", ")
            } catch (_: Exception) { "" }

            // Fetch recent commits for context + style
            val recentCommits = getRecentCommits(project)

            // Gather PSI code intelligence for changed files
            val codeContext = buildCodeContext(project)

            log.info("[Cody:CommitMsg] Generating: ${truncatedDiff.length} char diff, ${contextItems.size} context items, ${recentCommits.size} recent commits, codeContext=${codeContext.length} chars")

            CodyChatService(project).generateCommitMessageChained(
                diff = truncatedDiff,
                contextItems = contextItems,
                ticketId = ticketId,
                filesSummary = filesSummary,
                recentCommits = recentCommits,
                codeContext = codeContext
            )
        } catch (ex: Exception) {
            log.warn("[Cody:CommitMsg] Generation failed: ${ex.message}")
            null
        }
    }

    /**
     * Build code intelligence context from IntelliJ's PSI model.
     * Provides class names, annotations (@Service, @RestController, @Test),
     * Maven module, and whether files are test or production code.
     */
    private suspend fun buildCodeContext(project: Project): String {
        return try {
            val enricher = PsiContextEnricher(project)
            val changeListManager = ChangeListManager.getInstance(project)
            val contexts = changeListManager.allChanges.take(10).mapNotNull { change ->
                val path = change.afterRevision?.file?.path ?: return@mapNotNull null
                try {
                    val ctx = enricher.enrich(path)
                    if (ctx.className == null && ctx.classAnnotations.isEmpty()) return@mapNotNull null
                    buildString {
                        val fileName = path.substringAfterLast('/')
                        append(fileName)
                        if (ctx.isTestFile) append(" [TEST]")
                        if (ctx.mavenModule != null) append(" (module: ${ctx.mavenModule})")
                        if (ctx.className != null) append(" — ${ctx.className}")
                        if (ctx.classAnnotations.isNotEmpty()) {
                            append(" @${ctx.classAnnotations.joinToString(", @")}")
                        }
                        val interestingMethods = ctx.methodAnnotations.entries
                            .filter { (_, anns) -> anns.any { it in listOf("GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "RequestMapping", "Test", "BeforeEach", "Transactional") } }
                            .take(5)
                        if (interestingMethods.isNotEmpty()) {
                            append("\n  methods: ${interestingMethods.map { (m, a) -> "$m(${a.joinToString(",")})" }.joinToString(", ")}")
                        }
                    }
                } catch (_: Exception) { null }
            }
            contexts.joinToString("\n")
        } catch (_: Exception) { "" }
    }

    /**
     * Get the git diff for uncommitted changes.
     * Tries staged diff first, falls back to unstaged diff.
     */
    private fun resolveTargetRepo(project: Project): git4idea.repo.GitRepository? {
        val resolver = RepoContextResolver.getInstance(project)
        val repoConfig = resolver.resolveFromCurrentEditor() ?: resolver.getPrimary()
        val repos = GitRepositoryManager.getInstance(project).repositories
        return if (repoConfig?.localVcsRootPath != null) {
            repos.find { it.root.path == repoConfig.localVcsRootPath }
        } else {
            repos.firstOrNull()
        } ?: repos.firstOrNull()
    }

    private fun getGitDiff(project: Project): String? {
        val repo = resolveTargetRepo(project) ?: return null
        val root = repo.root

        // Try staged changes first (what would be committed)
        val stagedHandler = GitLineHandler(project, root, GitCommand.DIFF).apply {
            addParameters("--cached", "--no-color")
        }
        val stagedResult = Git.getInstance().runCommand(stagedHandler)
        if (stagedResult.success() && stagedResult.output.isNotEmpty()) {
            return stagedResult.output.joinToString("\n")
        }

        // Fall back to unstaged changes
        val unstagedHandler = GitLineHandler(project, root, GitCommand.DIFF).apply {
            addParameters("--no-color")
        }
        val unstagedResult = Git.getInstance().runCommand(unstagedHandler)
        if (unstagedResult.success() && unstagedResult.output.isNotEmpty()) {
            return unstagedResult.output.joinToString("\n")
        }

        return null
    }

    /**
     * Get recent commits with message + changed file names.
     * Format: "commit message | file1.kt, file2.kt, file3.java"
     * This gives Cody content awareness (what was recently changed)
     * plus style reference (how commit messages are formatted).
     */
    private fun getRecentCommits(project: Project): List<String> {
        return try {
            val repo = resolveTargetRepo(project) ?: return emptyList()
            // Get message + file names in one call: --name-only shows files after each commit
            val handler = GitLineHandler(project, repo.root, GitCommand.LOG).apply {
                addParameters("--format=COMMIT_START%n%s", "--name-only", "-5", "--no-merges")
            }
            val result = Git.getInstance().runCommand(handler)
            if (!result.success()) return emptyList()

            val commits = mutableListOf<String>()
            var currentMessage: String? = null
            val currentFiles = mutableListOf<String>()

            for (line in result.output) {
                when {
                    line == "COMMIT_START" -> {
                        // Flush previous commit
                        if (currentMessage != null) {
                            val filesSuffix = if (currentFiles.isNotEmpty()) {
                                " | ${currentFiles.take(5).joinToString(", ") { it.substringAfterLast('/') }}"
                            } else ""
                            commits.add("$currentMessage$filesSuffix")
                        }
                        currentMessage = null
                        currentFiles.clear()
                    }
                    currentMessage == null && line.isNotBlank() -> {
                        currentMessage = line
                    }
                    line.isNotBlank() && currentMessage != null -> {
                        currentFiles.add(line)
                    }
                }
            }
            // Flush last commit
            if (currentMessage != null) {
                val filesSuffix = if (currentFiles.isNotEmpty()) {
                    " | ${currentFiles.take(5).joinToString(", ") { it.substringAfterLast('/') }}"
                } else ""
                commits.add("$currentMessage$filesSuffix")
            }

            commits
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Compute the line range covering all changes between before and after content.
     * Adds 5-line padding for surrounding context.
     */
    private fun computeChangedRange(before: String, after: String): Range {
        val beforeLines = before.lines()
        val afterLines = after.lines()

        var firstChanged = 0
        val minLen = minOf(beforeLines.size, afterLines.size)
        while (firstChanged < minLen && beforeLines[firstChanged] == afterLines[firstChanged]) {
            firstChanged++
        }

        var lastChangedBefore = beforeLines.size - 1
        var lastChangedAfter = afterLines.size - 1
        while (lastChangedBefore > firstChanged && lastChangedAfter > firstChanged &&
            beforeLines[lastChangedBefore] == afterLines[lastChangedAfter]) {
            lastChangedBefore--
            lastChangedAfter--
        }

        val contextPadding = 5
        val startLine = maxOf(0, firstChanged - contextPadding)
        val endLine = minOf(afterLines.size - 1, lastChangedAfter + contextPadding)

        return Range(
            start = Position(line = startLine, character = 0),
            end = Position(line = endLine, character = 0)
        )
    }

}
