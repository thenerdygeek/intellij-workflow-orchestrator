package com.workflow.orchestrator.core.vcs

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
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.ui.CommitMessage
import com.workflow.orchestrator.core.psi.PsiContextEnricher
import com.workflow.orchestrator.core.ai.LlmBrainFactory
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.prompts.CommitMessagePromptBuilder
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoContextResolver
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

        // Capture the user's ticked/selected changes on EDT — SELECTED_CHANGES reflects
        // checkbox selection in the commit dialog; CHANGES is the active changelist as
        // fallback (some dialog layouts expose only one of the two).
        val selectedChanges: List<Change> =
            e.getData(VcsDataKeys.SELECTED_CHANGES)?.toList()
                ?: e.getData(VcsDataKeys.CHANGES)?.toList()
                ?: emptyList()
        log.info("[AI:CommitMsg] Selected changes: ${selectedChanges.size}")

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

                try {
                    // runBlocking is safe here — we're on a background thread, not EDT
                    val message = runBlocking { generateMessage(project, targetRepo, selectedChanges) }

                    if (indicator.isCanceled) {
                        log.info("[AI:CommitMsg] Generation cancelled by user")
                        return
                    }

                    ApplicationManager.getApplication().invokeLater({
                        if (message != null) {
                            commitMessage.setCommitMessage(message)
                            log.info("[AI:CommitMsg] Commit message generated (${message.length} chars)")
                        } else {
                            log.warn("[AI:CommitMsg] Failed to generate commit message")
                        }
                    }, modalityState)
                } catch (e: Exception) {
                    log.warn("[AI:CommitMsg] Generation failed: ${e.message}")
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

    private suspend fun generateMessage(
        project: Project,
        targetRepo: git4idea.repo.GitRepository? = null,
        selectedChanges: List<Change> = emptyList()
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

            // Get the actual git diff (no truncation — Sourcegraph supports 150K input tokens)
            val diff = getGitDiff(project, targetRepo, selectedAbsPaths)
            if (diff.isNullOrBlank()) {
                log.warn("[AI:CommitMsg] No diff found")
                return null
            }

            // Build a short summary of changed files for the analysis step.
            // Prefer scoped selection; fall back to the whole changelist when the
            // toolbar action fires without VcsDataKeys.SELECTED_CHANGES/CHANGES populated.
            val filesSummary = try {
                val source: Collection<Change> = if (scopedChanges.isNotEmpty()) {
                    scopedChanges
                } else {
                    ChangeListManager.getInstance(project).allChanges
                }
                source.mapNotNull { change ->
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
            val recentCommits = getRecentCommits(project, targetRepo)

            // Gather PSI code intelligence — scoped to selection when present.
            val codeContext = buildCodeContext(project, selectedAbsPaths)

            // Ticket resolution — settings first, then branch-name extraction.
            // Reading from PluginSettings (not ActiveTicketService) matches how the rest
            // of the plugin persists the active ticket; clearing in the UI writes "" here.
            val ticketId = settings.state.activeTicketId.orEmpty().takeIf { it.isNotBlank() }
                ?: targetRepo?.currentBranch?.name?.let { extractTicketIdFromBranch(it) }
                ?: ""

            val ticketDetails = if (ticketId.isNotBlank()) {
                fetchTicketDetails(ticketId)
            } else null

            log.info("[AI:CommitMsg] Generating: ${diff.length} char diff, ticket='$ticketId' details=${ticketDetails != null}, ${recentCommits.size} recent commits, codeContext=${codeContext.length} chars")

            // Use Sonnet thinking model for better commit message quality
            val brain = LlmBrainFactory.createForTextGeneration(project)
            val messages = CommitMessagePromptBuilder.buildMessages(
                diff = diff,
                ticketId = ticketId,
                filesSummary = filesSummary,
                recentCommits = recentCommits,
                codeContext = codeContext,
                ticketDetails = ticketDetails
            )
            // maxTokens MUST be set explicitly — omitting it causes Sourcegraph to
            // return HTTP 500 for thinking models (the thinking budget needs to be
            // allocated up front). 8000 gives thinking models ~7500 tokens for
            // reasoning plus ~500 for the commit message itself. Sourcegraph has no
            // fixed cap here; the agent sends up to 64K successfully.
            val result = brain.chat(messages, tools = null, maxTokens = 8000)
            when (result) {
                is ApiResult.Success ->
                    result.data.choices.firstOrNull()?.message?.content
                        ?.replace(Regex("^```[a-z]*\\n?"), "")
                        ?.replace(Regex("\\n?```$"), "")
                        ?.trim()
                is ApiResult.Error -> null
            }
        } catch (ex: Exception) {
            log.warn("[AI:CommitMsg] Generation failed: ${ex.message}")
            null
        }
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
            val changedFiles = if (selectedPaths.isNotEmpty()) {
                selectedPaths.take(5)
            } else {
                ChangeListManager.getInstance(project).allChanges.take(5).mapNotNull { change ->
                    change.afterRevision?.file?.path
                }
            }

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

        // Convert absolute paths to repo-relative for the pathspec. Paths outside
        // the repo root are dropped silently.
        val relativePaths = selectedPaths.mapNotNull { abs ->
            when {
                abs == rootPath -> null
                abs.startsWith("$rootPath/") -> abs.removePrefix("$rootPath/")
                else -> null
            }
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

        // Fall back to unstaged changes
        val unstagedHandler = GitLineHandler(project, root, GitCommand.DIFF).apply {
            addParameters("--no-color")
            applyPathspec(this)
        }
        val unstagedResult = Git.getInstance().runCommand(unstagedHandler)
        if (unstagedResult.success() && unstagedResult.output.isNotEmpty()) {
            return unstagedResult.output.joinToString("\n")
        }

        return null
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
