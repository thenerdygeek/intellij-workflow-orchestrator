# Six-Tab Audit — Findings (Pass 1, PARTIAL)

**Date:** 2026-05-31  **Run:** `wf_7bedc88e-36a`  **Status:** ⚠️ PARTIAL — interrupted by account session usage limit (HTTP 429)

## ⚠️ Coverage warning

This sweep **did not complete**. The account hit its session usage limit mid-run (~15:25 IST, resets 18:00 IST) after consuming **5.76M tokens / 229 agents**. Of 181 verification attempts, **179 were starved by the rate limit** and their underlying findings were lost.

- **Substantially covered:** `:jira` (Sprint), `:pullrequest` (PR)
- **NOT covered (rate-limited before verification):** `:bamboo` (Build), `:sonar` (Quality), `:automation` (Automation), `:handover` (Handover)
- Treat the confirmed list below as a **partial lower bound**, not a complete audit.

## Summary

- Raw findings that survived to a verdict: **26**
- Confirmed (real + safe to fix): **22**
- Rejected by adversarial verify: **4**
- By severity (confirmed): {"Low": 17, "Medium": 5}
- By module (confirmed): {"jira": 12, "pullrequest": 10}

## Confirmed findings

### `:jira`

#### JIRA-CLE-1 — Dead section-header rendering path: no JiraIssue with a "header-" id is ever created

- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketListCellRenderer.kt:55`
- **Problem:** TicketListCellRenderer.isHeader() / paintSectionHeader() and SprintDashboardPanel.isHeader() implement an assignee-grouping feature (section header rows) that was never wired up. A repo-wide grep confirms no code anywhere constructs a JiraIssue whose id starts with "header-" — the "All Tickets" toggle (ToggleAllUsersAction) just re-fetches a flat list with allUsers=true, it never injects header rows. Consequently the entire paintSectionHeader() method (TicketListCellRenderer.kt:57-99), the HEADER_LINE_COLOR constant (line 282, used only there), and the positive branch of every isHeader() check are unreachable. The isHeader() guards in SprintDashboardPanel (lines 489, 930, 1222) always return false, so they are defensive code for an impossible state.
- **Evidence:**
  ```
  private fun isHeader(issue: JiraIssue): Boolean = issue.id.startsWith("header-")  // never matches — no "header-" issue is ever produced
if (isHeader(issue)) { paintSectionHeader(g, issue); return }
  ```
- **Suggested fix:** Delete paintSectionHeader() and HEADER_LINE_COLOR from TicketListCellRenderer, remove both isHeader() helpers, and drop the `&& !isHeader(selected)` / `if (isHeader(...)) return` guards in SprintDashboardPanel (lines 489, 930, 1222) and the renderer's paintComponent header dispatch (lines 105-108). If assignee grouping is a desired future feature, track it as a backlog item rather than keeping the half-built unreachable path.
- **Verifier recommendation:** Accept as a low-priority cleanup. Safe to delete paintSectionHeader() and HEADER_LINE_COLOR from TicketListCellRenderer.kt, remove both isHeader() helpers, and drop the now-trivial guards at SprintDashboardPanel.kt:489/930/1222 plus the paintComponent header dispatch (lines 105-108). Git history (227284188) confirms group headers were intentionally removed per user feedback, so there is no future-feature reason to retain the scaffolding; if assignee grouping is ever revived, track it as a backlog item. No tests guard this path, so no test changes are needed beyond a clean compile.

#### JIRA-CLE-2 — Dead function SprintService.getCachedIssues() — zero callers

- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/SprintService.kt:169`
- **Problem:** getCachedIssues() (and by extension the private cachedIssues field that backs only it) has no callers anywhere in the repository — verified by grep across main and test. SprintService writes cachedIssues in three load paths but nothing ever reads it back; the SprintDashboardPanel keeps its own allIssues list. The cachedIssues writes plus this accessor are pure dead weight.
- **Evidence:**
  ```
  fun getCachedIssues(): List<JiraIssue> = cachedIssues   // 0 callers repo-wide
  ```
- **Suggested fix:** Delete getCachedIssues() and the private `cachedIssues` field, and remove the `cachedIssues = issuesResult.data` assignments in loadScrumBoardIssues / loadKanbanBoardIssues / loadIssuesForSprint (the load methods already return the ApiResult that the caller uses).
- **Verifier recommendation:** Accept as a valid Low-severity dead-code cleanup. Delete getCachedIssues() and the private cachedIssues field, and remove the three `cachedIssues = issuesResult.data` assignments; when removing the assignments, update the adjacent log lines to use `issuesResult.data.size` (or drop the size from the log) so the module still compiles.

#### JIRA-CLE-3 — Dead field SprintDashboardPanel.availableSprints — assigned but never read

- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt:130`
- **Problem:** The `availableSprints` field is assigned once in populateSprintSelector (line 610: `availableSprints = sprints`) but is never read anywhere afterward. The sprint selector is populated directly from the `sprints` parameter and the active-sprint auto-select uses `sprintService.activeSprint`, so this stored copy serves no purpose.
- **Evidence:**
  ```
  private var availableSprints: List<JiraSprint> = emptyList()
...
availableSprints = sprints  // only write; no read anywhere
  ```
- **Suggested fix:** Delete the `availableSprints` field declaration (line 130) and the `availableSprints = sprints` assignment (line 610).
- **Verifier recommendation:** Delete the field declaration at line 130 (`private var availableSprints: List<JiraSprint> = emptyList()`) and the sole assignment at line 610 (`availableSprints = sprints`). Trivial, safe dead-code removal.

#### JIRA-CLE-4 — Redundant no-op conditional: finalBranchClient if/else both return branchClient

- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt:1150`
- **Problem:** The `finalBranchClient` if/else evaluates `dialogResult.selectedRepoIndex != detectedIndex` but both branches return the identical `branchClient` value. The inline comment even states a new client "isn't needed". This is a no-op conditional that adds reader confusion (it implies branching behavior that does not exist).
- **Evidence:**
  ```
  val finalBranchClient = if (dialogResult.selectedRepoIndex != detectedIndex) {
    branchClient // same HTTP client, different projectKey/repoSlug passed below
} else branchClient
  ```
- **Suggested fix:** Replace the if/else with `val finalBranchClient = branchClient`, or inline `branchClient` directly into the startWork(...) call at line 1188 and delete the local. The correct project/repo are already passed via finalProjectKey/finalRepoSlug.
- **Verifier recommendation:** Apply the suggested fix: replace lines 1150-1154 with `val finalBranchClient = branchClient`, or inline `branchClient` directly into the startWork(...) call at line 1188 and delete the local plus its misleading comment. Correct repo targeting is preserved by finalProjectKey/finalRepoSlug. Low priority style cleanup.

#### JIRA-CLE-5 — Dead field StartWorkDialog.existingBranchLabel — always null, read is a perpetual no-op

- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/StartWorkDialog.kt:83`
- **Problem:** `existingBranchLabel` is declared as `JBLabel? = null` and is never assigned a non-null value anywhere in the class. Its only read, `existingBranchLabel?.isEnabled = !enabled` in updateCreatePanelEnabled (line 349), is therefore always a no-op safe-call on null. The actual 'no linked branches' label is `noExistingBranchesLabel`, which is correctly built and used; `existingBranchLabel` is a leftover.
- **Evidence:**
  ```
  private var existingBranchLabel: JBLabel? = null   // never assigned
...
existingBranchLabel?.isEnabled = !enabled          // always no-op (null)
  ```
- **Suggested fix:** Delete the `existingBranchLabel` field (line 83) and the `existingBranchLabel?.isEnabled = !enabled` line (349).
- **Verifier recommendation:** Apply the suggested fix: delete the unused field at jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/StartWorkDialog.kt:83 and the perpetual no-op read at line 349. Safe, behavior-preserving cleanup with no test impact.

#### JIRA-CLE-6 — Dead production function BranchNameValidator.isValidBranchName — only its own test calls it

- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/BranchNameValidator.kt:77`
- **Problem:** isValidBranchName(name) has no production callers anywhere in jira/core/agent main sources; the only references are in BranchNameValidatorTest. It is dead code kept alive solely by its unit test, giving a false impression of coverage. (This matches the still-open audit item jira:F-15 in project memory.)
- **Evidence:**
  ```
  fun isValidBranchName(name: String): Boolean { ... }  // referenced only by BranchNameValidatorTest, no main caller
  ```
- **Suggested fix:** Either delete isValidBranchName() and its test, or — per the jira:F-15 judgment call — wire it into the agent/Start-Work validation path if branch-name validation is actually wanted. As pure cleanup, deleting it (and the test) is the DELETE-first option.
- **Verifier recommendation:** Accept as a Low-severity cleanup. Either delete isValidBranchName() (BranchNameValidator.kt:77-87), its now-orphaned TICKET_PATTERN regex (line 9), and the single test (BranchNameValidatorTest.kt:50-55); or, per the jira:F-15 judgment call, wire it into the Start-Work/agent validation path if branch-name validation is actually desired. Deletion is the safe default and breaks nothing.

#### JIRA-CLE-7 — Redundant duplicate settings write in TicketDetectionPresenter.onAccept

- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketDetectionPresenter.kt:65`
- **Problem:** onAccept writes settings.state.activeTicketId / activeTicketSummary directly and then immediately calls ActiveTicketService.setActiveTicket(...). Per the Phase 5 T13 facade design (ActiveTicketService docs) the canonical write goes through WorkflowContextService.setActiveTicket, which itself writes those exact same settings fields (WorkflowContextService.kt:341-342). The sibling accept path in SprintDashboardPanel was already cleaned up (line 266-268 comment: 'facade now persists via WorkflowContextService ... so the inline settings writes + EventBus emit are gone'); this presenter still carries the duplicate write.
- **Evidence:**
  ```
  onAccept = {
    settings.state.activeTicketId = event.ticketKey
    settings.state.activeTicketSummary = event.ticketSummary
    ActiveTicketService.getInstance(project).setActiveTicket(event.ticketKey, event.ticketSummary)
}
  ```
- **Suggested fix:** Drop the two inline `settings.state.activeTicket*` assignments and keep only the setActiveTicket(...) call, mirroring the already-cleaned SprintDashboardPanel.onAccept path. (Note: setActiveTicket dispatches the canonical persist on a background scope; if a synchronous settings value is required before the next read, confirm CurrentWorkSection refreshes off the TicketChanged event — which it does — before removing, so behavior is preserved.)
- **Verifier recommendation:** Apply the fix: drop the two inline settings.state.activeTicket* assignments at TicketDetectionPresenter.kt:65-66, keeping only the setActiveTicket(...) call (mirroring SprintDashboardPanel:265-273). Downgrade severity to Low.

#### JIRA-CLE-8 — Dead constant CurrentWorkSection.CHIP_BG — declared, never used

- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/CurrentWorkSection.kt:56`
- **Problem:** The companion-object color constant CHIP_BG is never referenced anywhere in the class (only ACTIVE_BG, ACTIVE_BORDER and EMPTY_BG are used). Branch chips are now rendered by the separate CurrentWorkChipRenderer, leaving this constant orphaned.
- **Evidence:**
  ```
  private val CHIP_BG = JBColor(0xE0E0E0, 0x2D3035)   // no references
  ```
- **Suggested fix:** Delete the CHIP_BG constant from CurrentWorkSection's companion object.
- **Verifier recommendation:** Accept and fix: delete the CHIP_BG line from CurrentWorkSection's companion object. Safe, no behavioral risk.

#### JIRA-CLE-9 — Fully unused service TicketKeyCache — registered in plugin.xml but never instantiated

- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/TicketKeyCache.kt:19`
- **Problem:** TicketKeyCache is a @Service(PROJECT) registered in plugin.xml (line 237) intended for hyperlinking ticket keys in the ticket-detail description/comments. However no production code anywhere calls TicketKeyCache.getInstance(...) or any of its methods (extractKeys / get / isValidated / getUnvalidated / validateAndCache / clear) — verified repo-wide excluding tests. The DI registration keeps it compiling, but nothing ever obtains the service, so the entire class is dead. clear() and isValidated() in particular have no callers at all.
- **Evidence:**
  ```
  @Service(Service.Level.PROJECT) class TicketKeyCache { ... }
// plugin.xml:237 serviceImplementation="...jira.service.TicketKeyCache"
// grep: TicketKeyCache.getInstance — 0 production callers
  ```
- **Suggested fix:** Confirm the ticket-key hyperlinking feature is truly unwired, then delete TicketKeyCache.kt, its plugin.xml service registration (line 237), and its test. If hyperlinking is intended, wire it into the description/comment renderers (the architecturally-correct fix) rather than leaving a registered-but-dead service.
- **Verifier recommendation:** Delete TicketKeyCache.kt and its plugin.xml:237 service registration (no test file exists to delete, contrary to the finding's wording). Optionally also prune the now-orphaned validateTicketKeys/TicketKeyInfo chain and the ticketKeyRegex setting if the ticket-key hyperlinking feature is confirmed abandoned; otherwise wire the cache into the description/comment renderers (architecturally-correct alternative). Either way the change is safe.

#### JIRA-ARC-1 — WorklogSection bypasses the JiraService ToolResult contract and reaches into the raw JiraApiClient

- **Severity:** Low  **Category:** architecture  **Lens:** architecture
- **File:** `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/cross-ide/jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/WorklogSection.kt:62`
- **Problem:** WorklogSection (a Sprint-tab ticket-detail UI component) obtains the low-level HTTP client via JiraServiceImpl.getApiClient() and calls apiClient.getWorklogs(issueKey) directly, consuming the internal ApiResult<JiraWorklogResponse> DTO. The core JiraService interface already exposes `getWorklogs(issueKey): ToolResult<List<WorklogData>>` (core/services/JiraService.kt:96) backed by the core model WorklogData. The sibling detail sections (DevStatusSection, ChangelogSection, LinkedDocsSection) all correctly go through project.getService(JiraService) and the ToolResult facade; WorklogSection is the lone outlier. This couples the UI to the raw transport/DTO layer instead of the core-model contract, duplicates response mapping, and means any agent/UI parity (LLM-optimized .summary, WorklogData model) is skipped for worklogs.
- **Evidence:**
  ```
  val apiClient = jiraServiceImpl.getApiClient() ... val result = apiClient.getWorklogs(issueKey)  // returns ApiResult<JiraWorklogResponse>, not ToolResult<List<WorklogData>>
  ```
- **Suggested fix:** Replace the getApiClient()/apiClient.getWorklogs() path with project.getService(JiraService::class.java).getWorklogs(issueKey), branch on ToolResult.isError, and render from List<WorklogData> (mirroring ChangelogSection.loadHistory / DevStatusSection.loadDevStatus). This removes the raw-client dependency from the UI and reuses the existing core contract.
- **Verifier recommendation:** Apply the suggested fix: route WorklogSection through project.getService(JiraService::class.java).getWorklogs(issueKey), branch on ToolResult.isError, and render from List<WorklogData> (mirroring ChangelogSection.loadHistory). Low-priority cleanup, not urgent.

#### JIRA-ARC-3 — TicketDetectionPresenter writes activeTicket settings directly, duplicating ActiveTicketService's canonical persistence

- **Severity:** Low  **Category:** architecture  **Lens:** architecture
- **File:** `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/cross-ide/jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketDetectionPresenter.kt:65`
- **Problem:** In the popup's onAccept handler the presenter writes settings.state.activeTicketId / activeTicketSummary inline AND then calls ActiveTicketService.setActiveTicket(...). ActiveTicketService is documented as the facade that owns the canonical write (it dispatches to WorkflowContextService.setActiveTicket and re-emits the legacy TicketChanged event). The parallel inline-write path was explicitly removed elsewhere for this exact reason — see SprintDashboardPanel.kt:266-267 ('facade now persists via WorkflowContextService ... so the inline settings writes + EventBus emit are gone'). Keeping the duplicate write here means two writers of the same persisted field with no ordering guarantee against the facade's async background write to WorkflowContextService, risking the project-local PluginSettings.state value and the canonical WorkflowContextService state diverging (e.g. if the canonical service later normalizes/clears).
- **Evidence:**
  ```
  onAccept = {
                    settings.state.activeTicketId = event.ticketKey
                    settings.state.activeTicketSummary = event.ticketSummary
                    ActiveTicketService.getInstance(project)
                        .setActiveTicket(event.ticketKey, event.ticketSummary)
  ```
- **Suggested fix:** Drop the two inline settings.state writes and call only ActiveTicketService.getInstance(project).setActiveTicket(event.ticketKey, event.ticketSummary), matching the cleanup already applied in SprintDashboardPanel.setupDetectionBanner (line 266-271). The facade is the single source of truth for activeTicket persistence.
- **Verifier recommendation:** Apply the suggested cleanup (drop the two inline settings.state writes at TicketDetectionPresenter.kt:65-66, keep only the setActiveTicket call), but downgrade to Low — it is dead-redundant code, not a divergence/data-loss risk.

#### JIRA-ARC-4 — QuickCommentPanel uses raw Swing JButton/JComboBox instead of JetBrains components

- **Severity:** Low  **Category:** architecture  **Lens:** architecture
- **File:** `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/cross-ide/jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/QuickCommentPanel.kt:41`
- **Problem:** The convention is 'JetBrains components only' for UI. QuickCommentPanel (pinned to the bottom of the Sprint-tab ticket detail) declares `private val sendButton = JButton(...)` and `private val visibilityCombo = JComboBox(visibilityModel)`. The codebase has JB equivalents in use elsewhere (e.g. com.intellij.openapi.ui.ComboBox, used in SprintDashboardPanel.sprintSelector and StartWorkDialog). Raw javax.swing.JComboBox does not pick up JB look-and-feel niceties (it works, hence Low). The text field already correctly uses JBTextField.
- **Evidence:**
  ```
  private val sendButton = JButton(AllIcons.Actions.Execute)
...
    private val visibilityCombo = JComboBox(visibilityModel).apply {
  ```
- **Suggested fix:** Replace javax.swing.JComboBox with com.intellij.openapi.ui.ComboBox (already imported across the module) for visibilityCombo. JButton has no direct JB replacement and is acceptable; leaving it is fine, but the combo should use the platform ComboBox for theme consistency.
- **Verifier recommendation:** Optional cosmetic cleanup, not a defect. If pursued for consistency, swap only visibilityCombo to com.intellij.openapi.ui.ComboBox(visibilityModel) (API-compatible: extends JComboBox, so bindBoundedWidth/selectedItem/isEnabled all keep working). Leave JButton(AllIcons.Actions.Execute) as-is — it is the established icon-button pattern. Note the finding's rationale is overstated: raw JComboBox already inherits the platform LAF delegate, and the cited "clean example" StartWorkDialog itself uses raw JComboBox at lines 238/296, as does PrDashboardPanel:85. Lowest priority.

### `:pullrequest`

#### PULLREQUEST-COR-3 — Comment write paths (post/reply/inline) pass repoName=null while reads use the PR's repo — writes land in the primary repo

- **Severity:** Medium  **Category:** bug  **Lens:** correctness
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/CommentsViewModel.kt:81-105`
- **Problem:** CommentsViewModel is constructed with the PR's own `projectKey`/`repoSlug` and uses them for `listPrComments`/`resolvePrComment`/`reopenPrComment`. But `postGeneralComment` calls `service.addPrComment(prId, text)` and `reply` calls `service.replyToComment(prId, parentCommentId, text)` — both leave `repoName` at its default `null`. In BitbucketServiceImpl, `addPrComment`/`replyToComment`/`addInlineComment` resolve the repo via `resolveRepo(repoName)`, and `repoName=null` falls back to the PRIMARY repo (resolveRepo → getPrimaryRepo). So in a multi-repo project, comments are LISTED from the correct repo but POSTED/REPLIED to a different (primary) repo's PR with the same id. AiReviewViewModel.pushFinding (AiReviewViewModel.kt:62-93) has the identical defect — it passes `repoName = null` to `addInlineComment`/`addPrComment` while listing findings against the PR's own coords. The AiReviewViewModelTest even asserts `repoName = null`, pinning the buggy behavior.
- **Evidence:**
  ```
  suspend fun postGeneralComment(text: String): Boolean {
    val result = service.addPrComment(prId = prId, text = text)   // repoName defaults to null -> primary repo
  ```
- **Suggested fix:** BitbucketService's comment write methods take `repoName` (a display name) but the ViewModels only have `projectKey`/`repoSlug`. Either add a projectKey/repoSlug overload for the write methods (consistent with listPrComments) and pass the PR's coords, or resolve the display `repoName` from the coords and pass it through. Then update the tests to assert the PR's repo is targeted rather than null.
- **Verifier recommendation:** Add projectKey/repoSlug overloads (or pass-through) for addPrComment / replyToComment / addInlineComment in BitbucketService, mirroring listPrComments, and have CommentsViewModel + AiReviewViewModel pass the PR's own coords. Update AiReviewViewModelTest and CommentsViewModelTest to assert the PR's repo is targeted instead of null.

#### PULLREQUEST-COR-4 — AiReviewTabPanel resolves toHash via getPullRequestCommits(prId, repoName=null) — wrong repo, mis-anchored inline comments

- **Severity:** Medium  **Category:** bug  **Lens:** correctness
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/AiReviewTabPanel.kt:82-100`
- **Problem:** AiReviewTabPanel is constructed with the PR's own `projectKey`/`repoSlug`, but to compute `toHash` (the commit used to pin inline review comments) it calls `service.getPullRequestCommits(prId, repoName = null)`. With `repoName=null`, BitbucketServiceImpl.getPullRequestCommits resolves to the PRIMARY repo. In a multi-repo project this fetches the commits of a DIFFERENT repo's PR #prId, yielding either an unrelated commit hash or empty. The resulting `toHash` is then used by AiReviewViewModel.pushFinding to pin inline comments with diffType=COMMIT — anchoring AI findings to the wrong commit (or losing the pin entirely). Same root cause as the comment-write mismatch.
- **Evidence:**
  ```
  val commits = service.getPullRequestCommits(prId, repoName = null)
if (commits.isError) "" else commits.data!!.firstOrNull()?.id.orEmpty()
  ```
- **Suggested fix:** Pass the panel's `projectKey`/`repoSlug` to a repo-aware commits lookup (resolve the display repoName from those coords, or add a coords overload) so toHash comes from the correct repo's PR head commit.
- **Verifier recommendation:** Fix the whole AI-review flow to be repo-aware, not just toHash. Add a coords-based overload (projectKey/repoSlug) to BitbucketService.getPullRequestCommits / addInlineComment / addPrComment (and getPullRequestDiff/getPullRequestChanges used in runAiReview), OR resolve the display repoName from coords once and thread it through. AiReviewTabPanel:84, AiReviewViewModel:76/81, and PrDetailPanel.runAiReview:866-867 must all target the panel's projectKey/repoSlug instead of repoName=null. Update AiReviewViewModelTest (lines 64/71/82/94) to assert the resolved repo target rather than null.

#### PULLREQUEST-COR-5 — PrReviewSessionRegistry.get() does blocking file IO on the EDT during PR detail rendering

- **Severity:** Medium  **Category:** bug  **Lens:** correctness
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/AiReviewTabPanel.kt:62-69`
- **Problem:** AiReviewTabPanel's constructor calls `bindSessionIfExists()`, which synchronously calls `registry?.get(prKey)`. `PrReviewSessionRegistry.get` (PrReviewSessionRegistry.kt:40,55-61) acquires a ReentrantLock and reads the JSON registry file from disk (`Files.exists` + `Files.readString`). AiReviewTabPanel is constructed inside `PrDetailPanel.rebuildAiReviewTab`, which is invoked from `invokeLater {}` blocks in `showPr`/`showPrDetail` — i.e. on the EDT. Therefore disk IO (and lock contention with the background register/updateStatus writers) runs on the EDT every time a PR is selected. Although the file is small, this violates the project's threading convention (file/IO off the EDT) and is on a hot UI path that fires on every PR row click; under a slow/contended filesystem or a large registry it can stutter the UI.
- **Evidence:**
  ```
  private fun bindSessionIfExists() {
    val prKey = "$projectKey/$repoSlug/PR-$prId"
    val entry = registry?.get(prKey)   // blocking file read on the EDT
  ```
- **Suggested fix:** In bindSessionIfExists, move the `registry.get(prKey)` read onto the existing IO `scope.launch` (the method already launches a coroutine for the toHash fetch) and only touch Swing inside `invokeLater`. Alternatively make the registry expose a suspend `get`.
- **Verifier recommendation:** Apply the suggested fix: in AiReviewTabPanel.bindSessionIfExists (pullrequest/.../ui/AiReviewTabPanel.kt:62-69) move the registry?.get(prKey) read onto the existing scope.launch (Dispatchers.IO, line 82) and wrap the null/empty-state Swing mutations (statusLabel/listModel) in invokeLater. The registry itself and PrReviewSessionRegistryTest need no changes. Optionally also route onSessionChanged() (PrDetailPanel.kt:943) through the same off-EDT path since it re-enters bindSessionIfExists from invokeLater.

#### PULLREQUEST-CLE-1 — Entire inline Create-PR form in PrDetailPanel is dead — no caller invokes showCreateForm()

- **Severity:** Medium  **Category:** cleanup  **Lens:** cleanup
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt:466`
- **Problem:** PrDetailPanel contains a complete second Create-PR implementation (the `CARD_CREATE` card) reachable only through `fun showCreateForm(repoConfig: RepoConfig)`. A repo-wide grep shows `showCreateForm` has ZERO callers (the only match is its own definition). The module's documented single entry point to PR creation is `CreatePrDialog` via the `CreatePrLauncher` EP (CLAUDE.md: "the only entry point — Build tab's was removed 2026-04-27"), and PrDashboardPanel only ever calls `detailPanel.showPrDetail/showPr/showEmpty`. This makes a large subsystem dead: the `showCreateForm`, `buildCreatePanel` (line 555), `submitCreatePr` (line 682), `renderCreateReviewers` (line 760), and `showCreateReviewerPopup` (line 792) methods; the `CARD_CREATE` card (lines 118, 312-313); and all backing fields — `createRepoConfig` (103), `createPanel` (162), `createSourceBranchLabel` (165), `createTargetBranchCombo` (168), `createTitleField` (172), `createDescriptionArea` (175), `createReviewersPanel` (182), `createAddReviewerLink` (185), `createButton` (190), `createBackLabel` (193), `selectedReviewerUsernames` (199), `selectedReviewerDisplayNames` (200). No plugin.xml/EP/reflective usage exists. `showUserSearchPopup` and `showNotification` are also used by the live add-reviewer / detail paths, so those stay.
- **Evidence:**
  ```
  fun showCreateForm(repoConfig: RepoConfig) {  // grep: only self-reference repo-wide
...
buildCreatePanel()
add(createPanel, CARD_CREATE)
  ```
- **Suggested fix:** Delete the dead Create-PR subsystem from PrDetailPanel: remove `showCreateForm`, `buildCreatePanel`, `submitCreatePr`, `renderCreateReviewers`, `showCreateReviewerPopup`, the `CARD_CREATE` constant + its `add(createPanel, CARD_CREATE)` registration + the `buildCreatePanel()` call in init, and the 13 `create*`/`selectedReviewer*`/`createRepoConfig` fields. Keep `showUserSearchPopup`/`showNotification` (still used by the live reviewer path). This removes ~200 lines and the duplicate PR-creation path, leaving CreatePrDialog as the sole creator.
- **Verifier recommendation:** Apply the fix as described: delete showCreateForm, buildCreatePanel, submitCreatePr, renderCreateReviewers, showCreateReviewerPopup, the CARD_CREATE constant + its add(createPanel, CARD_CREATE) registration + the buildCreatePanel() call in init, and the 13 create*/selectedReviewer*/createRepoConfig fields. Keep showUserSearchPopup and showNotification (still used by the live add-reviewer and action paths). No tests reference the create-form path, so this is safe. Treat as Medium-priority cleanup, not High.

#### PULLREQUEST-CLE-3 — Dead AUTHOR/REVIEWER branches and unused `username` param in PrListService.fetchAllPages()

- **Severity:** Medium  **Category:** cleanup  **Lens:** cleanup
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrListService.kt:238`
- **Problem:** After the R-SWAP dashboard refactor (lines 145-148), AUTHOR and REVIEWER buckets are fetched via `fetchDashboardPrs`, and `fetchAllPages` is now called from exactly one site (line 170) with `role = "ALL"` and `username = null`. The `when (role)` inside `fetchAllPages` (lines 249-253) therefore can never take the `"AUTHOR"` or `"REVIEWER"` branches, and the `username: String?` parameter (line 242) is always null — both are dead. The method has degenerated into a single-role (`getRepoPullRequests`) paginator.
- **Evidence:**
  ```
  val results = fetchAllPages(client, projectKey, repoSlug, null, "ALL")  // only caller
...
"AUTHOR" -> client.getMyPullRequests(projectKey, repoSlug, currentState, username, start, 25)
"REVIEWER" -> client.getReviewingPullRequests(projectKey, repoSlug, currentState, username, start, 25)
  ```
- **Suggested fix:** Simplify `fetchAllPages` to drop the dead `username` param and the dead role branches: rename it (e.g. `fetchAllRepoPrPages`), remove the `when (role)` switch and call `client.getRepoPullRequests(projectKey, repoSlug, currentState, start, 25)` directly, and drop the now-unused `username`/`role` params at the single call site (line 170).
- **Verifier recommendation:** Apply the suggested fix as scoped: remove the dead AUTHOR/REVIEWER when-branches and the unused username param from PrListService.fetchAllPages, calling client.getRepoPullRequests directly. Optionally rename to fetchAllRepoPrPages. Do NOT touch the core BitbucketBranchClient.getMyPullRequests/getReviewingPullRequests methods — those remain live on the agent path.

#### PULLREQUEST-COR-6 — TicketChipInput.handlePastedText non-local return on MAX_CHIPS skips renderChips()/fireChange() for already-added chips

- **Severity:** Low  **Category:** bug  **Lens:** correctness
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/TicketChipInput.kt:481-491`
- **Problem:** In handlePastedText, the `tokens.forEach { ... }` loop adds chips and starts async resolution. When the limit is hit, `if (chips.size >= MAX_CHIPS) return` is a NON-LOCAL return out of `handlePastedText` (Kotlin `return` inside a `forEach` lambda returns from the enclosing function). This skips the trailing `renderChips()` and `fireChange()`. Any chips added by earlier iterations of the loop are therefore appended to the `chips` model but NOT rendered into the panel and do NOT fire the onChange snapshot, so the UI shows stale chips and the dialog's ticket state (provenance, validation) is out of sync until the next render is triggered by some other interaction.
- **Evidence:**
  ```
  tokens.forEach { token ->
    ...
    if (chips.size >= MAX_CHIPS) return   // non-local return: skips renderChips()/fireChange() below
    chips.add(Chip(normalized, Chip.Status.PENDING))
    resolveChipAsync(normalized)
}
renderChips()
fireChange()
  ```
- **Suggested fix:** Use `return@forEach` to skip the over-limit token while continuing the loop, then let the trailing `renderChips()`/`fireChange()` run; or break out of the loop and still call renderChips()/fireChange() afterward. The single-add path (addKey/commitInput) already returns BEFORE adding, so only this paste path is affected.
- **Verifier recommendation:** Apply the suggested fix: change line 485 from `if (chips.size >= MAX_CHIPS) return` to `if (chips.size >= MAX_CHIPS) return@forEach`, leaving the trailing renderChips()/fireChange() to run. Safe, idiomatic, no test relies on the current behavior.

#### PULLREQUEST-CLE-2 — Dead public method CreatePrPrefetch.resolveDefaultReviewersForBranch() — never called, doc references a non-existent method

- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/action/CreatePrPrefetch.kt:106`
- **Problem:** `suspend fun resolveDefaultReviewersForBranch(...)` has no callers anywhere in the repository (grep finds only its own definition and its internal log lines). Its `project` parameter is already marked `@Suppress("UNUSED_PARAMETER")`, and its KDoc plus the cross-reference at line 356 claim "The dialog re-runs this on target-change via [reloadDefaultReviewersForBranch]" — but no method named `reloadDefaultReviewersForBranch` exists, and CreatePrDialog.onTargetChanged() (CreatePrDialog.kt:915) only updates the provenance label; it never reloads reviewers. So both the method and the stale doc-comment describe behavior that was never wired. This is dead code left from a half-implemented target-change reviewer-refresh feature.
- **Evidence:**
  ```
      suspend fun resolveDefaultReviewersForBranch(
        @Suppress("UNUSED_PARAMETER") project: Project,
... // line 356: "The dialog re-runs this on target-change via [reloadDefaultReviewersForBranch]"
  ```
- **Suggested fix:** Delete `resolveDefaultReviewersForBranch` (lines 95-132) and remove the stale `[reloadDefaultReviewersForBranch]` sentence from the comment at lines 353-358 (keep the rest describing prefetchOneRepo's per-branch reviewer resolution, which is real). If per-target-branch reviewer refresh is genuinely wanted, implement it as a real wiring in CreatePrDialog.onTargetChanged() instead of leaving an unused helper.
- **Verifier recommendation:** Delete the dead public helper resolveDefaultReviewersForBranch (CreatePrPrefetch.kt:95-132) and strip the stale "[reloadDefaultReviewersForBranch]" sentence from the comment at lines 353-358, keeping the rest of that comment (it correctly describes the real per-branch reviewer resolution that prefetchOneRepo performs at lines 359-377). No test or wiring needs updating.

#### PULLREQUEST-CLE-4 — Computed-but-unused `username` value in PrListService.refresh()

- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrListService.kt:110`
- **Problem:** The `val username = cachedUsername ?: ... ?: run { client.getCurrentUsername(); ... }` block (lines 110-128) binds a result that is never read after assignment — the three fetch paths in refresh() use `fetchDashboardPrs` (no username arg) and `fetchAllPages(..., null, "ALL")`. The only meaningful effect of the block is its side-effects: warming `cachedUsername` and persisting `connSettings.bitbucketUsername` (which BitbucketServiceImpl.resolveCurrentUsername later reads). As written, the `val username =` binding plus the `?: connSettings.bitbucketUsername.takeIf {...}` fallback compute a value purely to discard it, which obscures intent and triggers an unused-variable smell.
- **Evidence:**
  ```
          // Auto-detect username on first call (or use saved setting)
        val username = cachedUsername
            ?: connSettings.bitbucketUsername.takeIf { it.isNotBlank() }
            ?: run {  // ... never read afterward
  ```
- **Suggested fix:** Refactor the value-returning expression into a side-effect-only `private suspend fun ensureUsernameCached(client)` that only warms `cachedUsername` + persists `connSettings.bitbucketUsername`, and call it without binding a result. This preserves the username-persistence behavior BitbucketServiceImpl depends on while removing the dead binding and the dead `connSettings.bitbucketUsername.takeIf{}` fallback that returns into nothing.
- **Verifier recommendation:** Downgrade to Low and rename the binding to make intent explicit (e.g. ensureUsernameCached side-effect helper) while preserving the cachedUsername short-circuit AND the exact `connSettings.bitbucketUsername = result.data` text the contract test pins. Low priority; not a bug.

#### PULLREQUEST-CLE-5 — Dead compat-shim parameter PrActionService.decline(cachedVersion) — never read, contradicts no-compat-shim convention

- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrActionService.kt:196`
- **Problem:** `suspend fun decline(prId: Int, cachedVersion: Int = 0)` never reads `cachedVersion` — `fetchAndDecline` always GETs a fresh version (lines 249-263), and the KDoc states the param is "intentionally unused ... retained only for API compatibility." The sole caller (PrDetailPanel.kt:1263) and the pinned test (PrActionServiceDeclineRetryTest) both call `decline(prId)` without it. The same file explicitly cites `feedback_no_compat_shims.md` when it dropped the analogous `version` param from `merge`/`updateDescription` (lines 146-151, 300-306), so this lingering shim is inconsistent with the module's own convention.
- **Evidence:**
  ```
  suspend fun decline(prId: Int, cachedVersion: Int = 0): ApiResult<Unit> {
...
 * posting the decline request, so [cachedVersion] is intentionally unused
 * in the primary attempt — passing it is optional and retained only for API
 * compatibility.
  ```
- **Suggested fix:** Drop the `cachedVersion: Int = 0` parameter (signature → `decline(prId: Int)`) and trim the KDoc lines describing it, matching the no-compat-shim treatment already applied to merge/updateDescription. No caller or test passes it, so this is safe.
- **Verifier recommendation:** Apply the suggested fix: drop the `cachedVersion: Int = 0` parameter so the signature becomes `decline(prId: Int)`, and trim the KDoc lines (188-194) that describe the unused param. This matches the no-compat-shim treatment already applied to merge (line 150) and updateDescription (line 304).

#### PULLREQUEST-CLE-6 — Redundant null-coalescing on non-null repoName in PrDashboardPanel

- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDashboardPanel.kt:253`
- **Problem:** `val repoName = prDetail.repoName ?: ""` applies an Elvis fallback to `BitbucketPrDetail.repoName`, which is declared `var repoName: String = ""` in core (BitbucketBranchClient.kt:340) — a non-null type. The `?: ""` can never fire and produces a Kotlin "unnecessary non-null assertion / Elvis on non-null" smell. Elsewhere in the same class repoName is used directly without the guard (e.g. line 473 `repoName = repoName`), confirming it is non-null.
- **Evidence:**
  ```
  val repoName = prDetail.repoName ?: ""
  ```
- **Suggested fix:** Replace with `val repoName = prDetail.repoName` (drop the redundant `?: ""`).
- **Verifier recommendation:** Apply the suggested fix: drop the redundant `?: ""` so line 253 reads `val repoName = prDetail.repoName`. Low priority; a clean style cleanup with no behavioral risk. Optionally fold into a broader cleanup pass rather than a standalone commit.

## Rejected findings (audit trail)

Findings the adversarial verifier refuted — kept for transparency (verify-don't-trust).

- **JIRA-ARC-2** (`:jira`, Medium) — Saved-filter run in SprintDashboardPanel bypasses JiraService.searchTickets and calls the raw JiraApiClient  
  - harmfulToFix=True — The literal code at SprintDashboardPanel.kt:711-718 matches the evidence: onFilterClicked() calls `service.getApiClient()` then `api.searchByJql(jql, 50)`, yielding `List<JiraIssue>`. So the bypass technically exists. BUT the finding mischaracterizes it as an isolated/intentional-coupling layering leak with real maintainability impact. Adversarial trace shows otherwise:

1. The ENTIRE Sprint dashb
- **PULLREQUEST-COR-1** (`:pullrequest`, High) — Merge action discards the ApiResult, swallowing merge failures and disabling buttons as if merge succeeded  
  - harmfulToFix=False — I attempted to refute this finding but independently confirmed it from the code. The merge button handler in PrDetailPanel.kt:1223-1247 calls PrActionService.getInstance(project).merge(...) (lines 1227-1232) and discards the returned value. The merge() method at PrActionService.kt:153-183 returns ApiResult<Unit> and returns ApiResult.Error (NOT throws) on every normal failure path: not-configured 
- **PULLREQUEST-COR-2** (`:pullrequest`, High) — PR detail body + all detail-panel actions ignore the selected PR's repo and hit the primary repo (multi-repo)  
  - harmfulToFix=False — I tried to refute this and could not; every claim checks out against the code.

CONFIRMED — multi-repo is real and feeds non-primary PRs into the panel: PrListService.refresh() iterates `pluginSettings.getRepos().filter { it.isConfigured }` (PrListService.kt:131) and pulls PRs via the cross-repo `getDashboardPullRequests(...)` endpoint (line 210). PrDashboardPanel's onPrSelected resolves each PR's
- **PULLREQUEST-COR-7** (`:pullrequest`, Low) — renderPrHeader reads non-volatile currentPr from an IO coroutine — cross-thread visibility gap  
  - harmfulToFix=False — REFUTED. The finding's central premise — "There is no happens-before edge between the EDT write of currentPr and this IO read, so the coroutine can observe a stale/older currentPr" — is factually wrong under the Java/Kotlin memory model.\n\nThreading topology of currentPr vs cachedUsername is OPPOSITE, which is the crux:\n- cachedUsername (PrDetailPanel.kt:1456-1465) is WRITTEN inside the IO corou
