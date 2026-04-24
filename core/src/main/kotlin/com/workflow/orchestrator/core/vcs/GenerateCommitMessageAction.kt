package com.workflow.orchestrator.core.vcs

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.ui.CommitMessage
import com.intellij.openapi.vcs.ui.Refreshable
import com.intellij.ui.AnimatedIcon
import com.intellij.vcs.commit.CommitWorkflowUi
import com.intellij.openapi.command.CommandProcessor
import com.workflow.orchestrator.core.psi.PsiContextEnricher
import com.workflow.orchestrator.core.ai.LlmBrainFactory
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.prompts.CommitMessagePromptBuilder
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoContextResolver
import com.workflow.orchestrator.core.ui.CommitMessageFlash
import com.workflow.orchestrator.core.ui.CommitMessageStreamBatcher
import com.workflow.orchestrator.core.workflow.JiraTicketProvider
import com.workflow.orchestrator.core.workflow.TicketDetails
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Action that appears in the commit dialog's message toolbar (Vcs.MessageActionGroup).
 * Generates a commit message using AI from the staged/unstaged changes.
 */
class GenerateCommitMessageAction : AnAction(
    "Generate with Workflow",
    "Generate commit message using AI",
    AllIcons.Actions.Lightning
), com.intellij.openapi.project.DumbAware {

    private val log = Logger.getInstance(GenerateCommitMessageAction::class.java)
    private val generating = AtomicBoolean(false)

    private companion object {
        private val TICKET_PATTERN = Regex("([A-Z][A-Z0-9]+-\\d+)")
        private const val NOTIFICATION_GROUP = "Workflow Commit AI"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val commitMessage = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) as? CommitMessage ?: return
        val modalityState = ModalityState.stateForComponent(commitMessage.editorField)

        if (!generating.compareAndSet(false, true)) {
            log.info("[AI:CommitMsg] Already generating — ignoring")
            return
        }

        log.info("[AI:CommitMsg] Generate commit message triggered")

        // Resolve the target repo on EDT (before launching background work)
        val targetRepo = resolveTargetRepo(project)

        // Resolve the user's checked (included-for-commit) changes on EDT.
        // Priority: modern commit tool-window UI → legacy checkin panel → SELECTED_CHANGES.
        // CHANGES is deliberately excluded — it returns the whole changelist regardless of
        // what the user has checked, which was the root-cause bug.
        val (selectedChanges, sourceTag) = resolveCheckedChanges(e)
        log.info("[AI:CommitMsg] Checked changes: ${selectedChanges.size} (source: $sourceTag)")

        if (selectedChanges.isEmpty()) {
            ApplicationManager.getApplication().invokeLater({
                commitMessage.setCommitMessage(
                    "// No files checked for commit. Check at least one file and try again.\n" +
                    "// (The AI only uses the files you've checked — not the whole changelist.)"
                )
            }, modalityState)
            generating.set(false)
            return
        }

        // Run as a backgroundable task with progress in the status bar.
        // Uses runBlocking inside the background thread (NOT EDT — safe per project rules).
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Generating commit message...",
            true // cancellable
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Analyzing changes..."

                val renderer = CommitMessageProgressRenderer(commitMessage, modalityState)
                renderer.update("Starting...")

                try {
                    // runBlocking is safe here — we're on a background thread, not EDT
                    val message = runBlocking {
                        generateMessage(
                            project, targetRepo, selectedChanges, indicator, renderer,
                            commitMessage, modalityState
                        )
                    }

                    if (indicator.isCanceled) {
                        log.info("[AI:CommitMsg] Generation cancelled by user")
                        renderer.cancelled()
                        return
                    }

                    if (message != null) {
                        renderer.success(message)
                        CommitMessageFlash.flashSuccess(commitMessage, modalityState)
                        log.info("[AI:CommitMsg] Commit message generated (${message.length} chars)")
                    } else {
                        renderer.failed()
                        log.warn("[AI:CommitMsg] Failed to generate commit message")
                        showErrorBalloon(project, "No message returned — check idea.log")
                    }
                } catch (ex: ProcessCanceledException) {
                    log.info("[AI:CommitMsg] Generation cancelled (ProcessCanceledException)")
                    renderer.cancelled()
                } catch (ex: Exception) {
                    log.warn("[AI:CommitMsg] Generation failed: ${ex.message}")
                    renderer.failed()
                    showErrorBalloon(project, ex.message ?: "Unknown error — check idea.log")
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
            e.presentation.icon = AnimatedIcon.Default()
        } else {
            e.presentation.text = "Generate with Workflow"
            e.presentation.icon = AllIcons.Actions.Lightning
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun showErrorBalloon(project: Project, reason: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            ?.createNotification(
                "Commit message generation failed",
                reason,
                NotificationType.ERROR
            )
            ?.notify(project)
    }

    private suspend fun generateMessage(
        project: Project,
        targetRepo: git4idea.repo.GitRepository? = null,
        selectedChanges: List<Change> = emptyList(),
        indicator: ProgressIndicator,
        renderer: CommitMessageProgressRenderer,
        commitMessage: CommitMessage? = null,
        modalityState: ModalityState? = null
    ): String? {
        return try {
            val settings = PluginSettings.getInstance(project)

            // Scope selection to the target repo — guards against multi-repo projects
            // where the user may have changes from another root in the list.
            val repoRoot = targetRepo?.root?.path
            val scopedChanges = if (repoRoot != null && selectedChanges.isNotEmpty()) {
                selectedChanges.filter { change ->
                    val path = (change.afterRevision ?: change.beforeRevision)?.file?.path
                    path != null && (path == repoRoot || path.startsWith("$repoRoot/"))
                }
            } else selectedChanges

            val selectedAbsPaths = scopedChanges.mapNotNull { change ->
                (change.afterRevision ?: change.beforeRevision)?.file?.path
            }

            log.info(
                "[AI:CommitMsg] Scoped: ${scopedChanges.size}/${selectedChanges.size} changes under repoRoot='$repoRoot' " +
                    "(first: ${selectedAbsPaths.firstOrNull() ?: "<none>"})"
            )

            // Bail BEFORE getGitDiff if nothing is in scope — otherwise getGitDiff would run
            // unfiltered and silently return whatever global diff exists, producing a commit
            // message for files the user did NOT check.
            if (scopedChanges.isEmpty()) {
                val firstOriginal = selectedChanges.firstNotNullOfOrNull {
                    (it.afterRevision ?: it.beforeRevision)?.file?.path
                }
                log.warn(
                    "[AI:CommitMsg] All ${selectedChanges.size} checked file(s) are outside repoRoot='$repoRoot' " +
                        "(first checked: $firstOriginal) — aborting"
                )
                return null
            }

            // Phase 1 — git diff
            withPhase(indicator, renderer, "Fetching git diff...") {}
            val diff = getGitDiff(project, targetRepo, selectedAbsPaths)
            if (diff.isNullOrBlank()) {
                log.warn("[AI:CommitMsg] No diff found for ${selectedAbsPaths.size} path(s) in repoRoot='$repoRoot'")
                return null
            }
            val filesSummary = try {
                scopedChanges.mapNotNull { change ->
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

            // Phase 2 — PSI code context
            val codeContext = withPhase(indicator, renderer, "Analyzing code structure...") {
                buildCodeContext(project, selectedAbsPaths)
            }

            // Ticket resolution — settings first, then branch-name extraction.
            // Reading from PluginSettings (not ActiveTicketService) matches how the rest
            // of the plugin persists the active ticket; clearing in the UI writes "" here.
            val ticketId = settings.state.activeTicketId.orEmpty().takeIf { it.isNotBlank() }
                ?: targetRepo?.currentBranch?.name?.let { extractTicketIdFromBranch(it) }
                ?: ""

            // Phase 3 — Jira ticket (only if relevant)
            val ticketDetails = if (ticketId.isNotBlank()) {
                withPhase(indicator, renderer, "Fetching Jira ticket...") {
                    fetchTicketDetails(ticketId)
                }
            } else null

            // Phase 4 — recent commits
            val recentCommits = withPhase(indicator, renderer, "Reading recent commits...") {
                getRecentCommits(project, targetRepo)
            }

            log.info("[AI:CommitMsg] Generating: ${diff.length} char diff, ticket='$ticketId' details=${ticketDetails != null}, ${recentCommits.size} recent commits, codeContext=${codeContext.length} chars")

            // Phase 5 — AI generation (streaming)
            // Stream tokens live into the commit field so the user sees the message
            // being written character-by-character. Partial writes are undo-transparent;
            // the final renderer.success() write participates in undo.
            withPhase(indicator, renderer, "Generating with AI...") {
                val brain = LlmBrainFactory.createForTextGeneration(project)
                val messages = CommitMessagePromptBuilder.buildMessages(
                    diff = diff,
                    ticketId = ticketId,
                    filesSummary = filesSummary,
                    recentCommits = recentCommits,
                    codeContext = codeContext,
                    ticketDetails = ticketDetails
                )

                val accumulated = StringBuilder()

                // Set up the 16ms EDT coalescing batcher only when we have a live
                // commit field to write to (null during unit tests without a UI).
                // Partial writes are wrapped in runUndoTransparentAction so each streaming
                // token does NOT pollute IntelliJ's undo stack. Only the final
                // renderer.success(finalText) write participates in undo.
                val batcher: CommitMessageStreamBatcher? = if (commitMessage != null && modalityState != null) {
                    CommitMessageStreamBatcher(modalityState) { partial ->
                        CommandProcessor.getInstance().runUndoTransparentAction {
                            commitMessage.setCommitMessage(partial)
                        }
                    }.also { it.start() }
                } else null

                try {
                    // maxTokens MUST be set explicitly — omitting it causes Sourcegraph to
                    // return HTTP 500 for thinking models (the thinking budget needs to be
                    // allocated up front). 8000 gives thinking models ~7500 tokens for
                    // reasoning plus ~500 for the commit message itself.
                    val result = brain.chatStream(messages, tools = null, maxTokens = 8000) { chunk ->
                        // StreamChunk → choices[0].delta.content carries the partial text delta.
                        // Append each delta to the accumulator, then push the full accumulated
                        // text to the batcher (so the field always shows accumulated state,
                        // never a raw delta fragment).
                        val delta = chunk.choices.firstOrNull()?.delta?.content
                        if (delta != null) {
                            accumulated.append(delta)
                            batcher?.submit(accumulated.toString())
                        }

                        // Cooperative cancellation — stop streaming immediately if the
                        // user cancels the progress indicator.
                        if (indicator.isCanceled) {
                            brain.interruptStream()
                            brain.cancelActiveRequest()
                        }
                    }

                    // Flush any last partial token that arrived between timer ticks
                    batcher?.flush()

                    when (result) {
                        is ApiResult.Success -> {
                            // Code-fence stripping is deferred to the final text — partial
                            // fences look ugly mid-stream. Strip once on the complete output.
                            accumulated.toString()
                                .replace(Regex("^```[a-z]*\\n?"), "")
                                .replace(Regex("\\n?```$"), "")
                                .trim()
                                .takeIf { it.isNotBlank() }
                        }
                        is ApiResult.Error -> null
                    }
                } finally {
                    batcher?.dispose()
                }
            }
        } catch (ex: ProcessCanceledException) {
            throw ex
        } catch (ex: Exception) {
            log.warn("[AI:CommitMsg] Generation failed: ${ex.message}")
            null
        }
    }

    /**
     * Checks for cancellation, updates the progress indicator text, notifies the live
     * placeholder renderer, and then executes [block]. Throws [ProcessCanceledException]
     * if the indicator is already cancelled before the phase begins.
     */
    internal suspend fun <T> withPhase(
        indicator: ProgressIndicator,
        renderer: CommitMessageProgressRenderer,
        phase: String,
        block: suspend () -> T
    ): T {
        if (indicator.isCanceled) throw ProcessCanceledException()
        indicator.text = phase
        renderer.update(phase)
        return block()
    }

    /**
     * Build code intelligence context from IntelliJ's PSI model.
     * Provides class names, annotations (@Service, @RestController, @Test),
     * Maven module, and whether files are test or production code.
     *
     * Enriches up to 5 files in parallel to keep processing reasonable
     * while avoiding sequential 500ms-2s PSI reads per file.
     */
    private suspend fun buildCodeContext(project: Project, selectedPaths: List<String> = emptyList()): String {
        return try {
            val enricher = PsiContextEnricher(project)
            // selectedPaths is always non-empty when called from generateMessage (the empty
            // guard in actionPerformed ensures this). The default-empty signature is kept for
            // testability. No ChangeListManager fallback — that was a silent whole-changelist
            // backdoor and has been removed.
            val changedFiles = selectedPaths.take(5)

            // Enrich all files in parallel instead of sequentially
            val contexts = coroutineScope {
                changedFiles.map { path ->
                    async {
                        try {
                            val ctx = enricher.enrich(path)
                            if (ctx.className == null && ctx.classAnnotations.isEmpty()) return@async null
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
                }.awaitAll().filterNotNull()
            }
            contexts.joinToString("\n")
        } catch (_: Exception) { "" }
    }

    /**
     * Resolve the user's checked (included-for-commit) changes from the action event.
     *
     * Priority chain:
     * 1. [VcsDataKeys.COMMIT_WORKFLOW_UI] → [CommitWorkflowUi.getIncludedChanges] — modern
     *    non-modal commit tool window. Non-null UI means checkbox state is authoritative;
     *    an empty list means "nothing checked", which is a legitimate state.
     * 2. [Refreshable.PANEL_KEY] cast to [CheckinProjectPanel] → [CheckinProjectPanel.getSelectedChanges] —
     *    legacy commit dialog. "Selected" here means checked for commit (same semantic as
     *    [CommitWorkflowUi.getIncludedChanges] — confirmed by HealthCheckCheckinHandlerFactory usage).
     * 3. [VcsDataKeys.SELECTED_CHANGES] — tertiary fallback for unusual action contexts.
     * 4. All three null/empty → `(emptyList(), "NONE")`.
     *
     * [VcsDataKeys.CHANGES] is deliberately excluded — it returns the whole active changelist
     * regardless of what the user has checked, which was the root-cause bug.
     *
     * Returns a [Pair] of (list of included changes, source tag string).
     * The source tag is one of: "COMMIT_WORKFLOW_UI", "CHECKIN_PROJECT_PANEL",
     * "SELECTED_CHANGES", or "NONE".
     */
    @Suppress("UnstableApiUsage")
    internal fun resolveCheckedChanges(e: AnActionEvent): Pair<List<Change>, String> {
        // 1. Modern commit tool window — VcsDataKeys.COMMIT_WORKFLOW_UI is stable (no
        //    @ApiStatus.Internal annotation in IntelliJ 2025.1.7 app.jar).
        val commitWorkflowUi: CommitWorkflowUi? = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        if (commitWorkflowUi != null) {
            return Pair(commitWorkflowUi.getIncludedChanges(), "COMMIT_WORKFLOW_UI")
        }

        // 2. Legacy modal commit dialog — CheckinProjectPanel via Refreshable.PANEL_KEY.
        val panel = e.getData(Refreshable.PANEL_KEY) as? CheckinProjectPanel
        if (panel != null) {
            return Pair(panel.selectedChanges.toList(), "CHECKIN_PROJECT_PANEL")
        }

        // 3. Tertiary fallback — SELECTED_CHANGES in unusual action contexts.
        val selected = e.getData(VcsDataKeys.SELECTED_CHANGES)
        if (selected != null) {
            return Pair(selected.toList(), "SELECTED_CHANGES")
        }

        // 4. No authoritative source found — caller should show empty-state feedback.
        return Pair(emptyList(), "NONE")
    }

    /**
     * Get the git diff for uncommitted changes.
     * Tries staged diff first, falls back to unstaged diff.
     */
    private fun resolveTargetRepo(project: Project): git4idea.repo.GitRepository? =
        RepoContextResolver.getInstance(project).resolveCurrentEditorRepoOrPrimary()

    private fun getGitDiff(
        project: Project,
        preResolvedRepo: git4idea.repo.GitRepository? = null,
        selectedPaths: List<String> = emptyList()
    ): String? {
        val repo = preResolvedRepo ?: resolveTargetRepo(project) ?: return null
        val root = repo.root
        val rootPath = root.path

        // Convert absolute paths to repo-relative for the pathspec.
        val relativePaths = selectedPaths.mapNotNull { abs ->
            when {
                abs == rootPath -> null
                abs.startsWith("$rootPath/") -> abs.removePrefix("$rootPath/")
                else -> null
            }
        }

        // Refuse to run unfiltered when the caller asked for specific paths but none
        // could be made repo-relative — previously this fell through to an unfiltered
        // `git diff` and silently returned the wrong diff.
        if (selectedPaths.isNotEmpty() && relativePaths.isEmpty()) {
            log.warn(
                "[AI:CommitMsg] ${selectedPaths.size} selected path(s) could not be made relative to " +
                    "rootPath='$rootPath' (first: '${selectedPaths.first()}') — refusing to run unfiltered diff"
            )
            return null
        }

        fun applyPathspec(handler: GitLineHandler) {
            if (relativePaths.isNotEmpty()) {
                handler.endOptions()
                handler.addParameters(*relativePaths.toTypedArray())
            }
        }

        // Try staged changes first (what would be committed)
        val stagedHandler = GitLineHandler(project, root, GitCommand.DIFF).apply {
            addParameters("--cached", "--no-color")
            applyPathspec(this)
        }
        val stagedResult = Git.getInstance().runCommand(stagedHandler)
        if (stagedResult.success() && stagedResult.output.isNotEmpty()) {
            return stagedResult.output.joinToString("\n")
        }

        // Fall back to unstaged changes (for tracked-but-unstaged files)
        val unstagedHandler = GitLineHandler(project, root, GitCommand.DIFF).apply {
            addParameters("--no-color")
            applyPathspec(this)
        }
        val unstagedResult = Git.getInstance().runCommand(unstagedHandler)
        if (unstagedResult.success() && unstagedResult.output.isNotEmpty()) {
            return unstagedResult.output.joinToString("\n")
        }

        // Both tracked-diff commands returned empty. This typically means the checked
        // file is an untracked (unversioned) file — IntelliJ shows those as checkable
        // Change rows in the commit dialog, but `git diff` and `git diff --cached` can't
        // see them. Fall through to `git diff --no-index /dev/null <file>` which produces
        // a diff comparing the file to an empty blob.
        if (relativePaths.isNotEmpty()) {
            val untrackedDiff = diffUntrackedFiles(project, root, relativePaths)
            if (!untrackedDiff.isNullOrBlank()) return untrackedDiff
        }

        log.warn(
            "[AI:CommitMsg] Empty git diff (staged exit=${stagedResult.exitCode} " +
                "stderr='${stagedResult.errorOutputAsJoinedString.take(200)}'; " +
                "unstaged exit=${unstagedResult.exitCode} " +
                "stderr='${unstagedResult.errorOutputAsJoinedString.take(200)}') " +
                "for paths=${relativePaths.take(3)}"
        )
        return null
    }

    /**
     * Fall-back diff for files that IntelliJ lists as checked Change rows but git cannot
     * see via `git diff`/`git diff --cached` — most commonly newly-created unversioned
     * files. Runs `git diff --no-index --no-color -- /dev/null <relPath>` per file and
     * concatenates the results. `--no-index` exits with status 1 when files differ,
     * which git4idea reports as non-success; we accept any output regardless of status.
     */
    private fun diffUntrackedFiles(
        project: Project,
        root: com.intellij.openapi.vfs.VirtualFile,
        relativePaths: List<String>
    ): String? {
        val nullDevice = if (System.getProperty("os.name").lowercase().contains("windows")) "NUL" else "/dev/null"
        val parts = relativePaths.mapNotNull { rel ->
            val handler = GitLineHandler(project, root, GitCommand.DIFF).apply {
                addParameters("--no-index", "--no-color")
                endOptions()
                addParameters(nullDevice, rel)
            }
            val result = Git.getInstance().runCommand(handler)
            // `git diff --no-index` exits 1 when files differ — that's success for us.
            result.output.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /**
     * Extract a Jira ticket key from a branch name (e.g. "feature/ABC-123-foo" → "ABC-123").
     * Mirrors the regex used in :jira's ActiveTicketService — duplicated here because :core
     * cannot depend on :jira, and the pattern is four lines of code.
     */
    private fun extractTicketIdFromBranch(branchName: String): String? =
        TICKET_PATTERN.find(branchName)?.groupValues?.get(1)

    /**
     * Fetch ticket summary/description via the JiraTicketProvider extension point.
     * Returns null if the :jira module is not loaded or the lookup fails — the prompt
     * still generates, it just omits the TICKET CONTEXT section.
     */
    private suspend fun fetchTicketDetails(ticketId: String): TicketDetails? = try {
        JiraTicketProvider.getInstance()?.getTicketDetails(ticketId)
    } catch (ex: Exception) {
        log.warn("[AI:CommitMsg] Ticket details fetch failed for $ticketId: ${ex.message}")
        null
    }

    /**
     * Get recent commits with message + changed file names.
     * Format: "commit message | file1.kt, file2.kt, file3.java"
     * This gives the AI content awareness (what was recently changed)
     * plus style reference (how commit messages are formatted).
     */
    private fun getRecentCommits(project: Project, preResolvedRepo: git4idea.repo.GitRepository? = null): List<String> {
        return try {
            val repo = preResolvedRepo ?: resolveTargetRepo(project) ?: return emptyList()
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

}

/**
 * Writes live placeholder text into the commit message field during generation phases,
 * then replaces with the generated message (or clears on cancel/failure).
 *
 * All updates are marshalled to the EDT via [ApplicationManager.getApplication().invokeLater]
 * with the supplied [ModalityState] so they run while the commit dialog is open.
 *
 * Draft-preservation policy: REPLACE SILENTLY — no prompt is shown to save existing text.
 */
internal class CommitMessageProgressRenderer(
    private val commitMessage: CommitMessage,
    private val modalityState: ModalityState
) {
    private val prefix = "[Workflow AI] "
    fun update(phase: String) {
        ApplicationManager.getApplication().invokeLater({
            commitMessage.setCommitMessage("$prefix$phase")
        }, modalityState)
    }
    fun success(generated: String) {
        ApplicationManager.getApplication().invokeLater({
            commitMessage.setCommitMessage(generated)
        }, modalityState)
    }
    fun cancelled() {
        ApplicationManager.getApplication().invokeLater({
            commitMessage.setCommitMessage("")
        }, modalityState)
    }
    fun failed() {
        ApplicationManager.getApplication().invokeLater({
            commitMessage.setCommitMessage("")
        }, modalityState)
    }
}
