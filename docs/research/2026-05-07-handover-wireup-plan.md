# `:handover` wire-up plan — making the tab actually work

**Date:** 2026-05-07
**Branch:** `fix/automation-handover-quality-tabs`
**Mode:** RESEARCH + DESIGN ONLY. No `.kt` written, no tests touched, no commits.
**Audit doc this builds on:** `docs/research/2026-05-07-automation-handover-audit.md` (§2.5 H-P0-1/2/3, §3 H-P1-1..5).
**Audience:** the user (review, push back, approve), then a Sonnet implementer in a separate session.

---

## 1. Status quo

The Handover tab (`HandoverTabProvider.kt:9-28`) renders six sub-panels — Copyright, AI Review, Jira Comment, Time Log, QA Clipboard, Macro — plus a left-side `HandoverContextPanel`. `HandoverPanel.init` (`HandoverPanel.kt:42-71`) only wires the **left** sidebar to `HandoverStateService.stateFlow`; **none of the right-side detail panels are wired at all.** Every primary action button on every panel is hard-coded `isEnabled = false` with `toolTipText = "Coming soon"` (audit **H-P0-2**, see panel `:21-28` / `:19-22` / `:20-23` / `:38-41` / `:25-28` / `HandoverContextPanel.kt:42-45`). All six panel setters (`setEntries`, `setFindings`, `setCommentText`, `setTicket`, `setDockerTags`, `setFormattedText`, `setSteps`) are defined and unreachable from production (**H-P0-1**). Two well-tested services — `JiraClosureService.buildClosureComment` (`JiraClosureService.kt:26`) and `CompletionMacroService.executeMacro` (`CompletionMacroService.kt:69`) — exist as ~150 LOC of dead code (**H-P0-3**). The user's "totally broken" framing is accurate. Existing `:core` services already cover everything we need: `JiraService.addComment` / `logWork` / `getTransitions` / `transition` (`JiraService.kt:48,51,37,40`), `TicketTransitionService` for orchestrated transitions (`core/services/jira/TicketTransitionService.kt:9-11`), `TextGenerationService` EP (`core/ai/TextGenerationService.kt:25-99`). No new client class is required; `HandoverJiraClient` (named in `:handover/CLAUDE.md:8` per **H-P1-3**) does not exist and **should not be created** — `JiraService` is the canonical mutation surface.

---

## 2. Per-panel design

Ordering rationale: Jira Comment first because it's the most-asked-for action (the closure comment is what QA, PM, and Jira-watchers actually read) AND it has the highest implementation maturity (full formatter exists, just dead). Copyright second because it's pure local-IDE work — no network, no auth — and ships value on day one. QA Clipboard third because it's a pure read+format from already-collected state. Time Log fourth because it's a single Jira mutation but the UX needs a date-picker + validation. AI Review fifth because it depends on the AI EP being installed and the diff path adds VCS coupling. Macro last because the recommendation below is to delete it.

### 2.1 Jira Comment Panel

**User intent.** "I'm done with the work; record what shipped (suite results, docker tags, links) on the Jira ticket as a closure comment so QA / PM / future-me can see what was deployed."

**Inputs.**
- `HandoverState.ticketId` — from `HandoverStateService.stateFlow` (`HandoverStateService.kt:53`), populated via `WorkflowContextService.activeTicketFlow` (`HandoverStateService.kt:73-77`).
- `HandoverState.suiteResults` — accumulated from `WorkflowEvent.AutomationTriggered` + `AutomationFinished` in `HandoverStateService.handleEvent` (`HandoverStateService.kt:96-120`).
- `JiraClosureService` — already-implemented formatter (`JiraClosureService.kt:26-59`).

**Rendered content.** Bind `HandoverPanel.scope.launch { stateService.stateFlow.collect { state -> jiraCommentPanel.setCommentText(JiraClosureService.getInstance(project).buildClosureComment(state.suiteResults)) } }`. The panel's existing `JBTextArea commentPreview` renders the wiki-markup output: an h4 "Automation Results" table (suite plan key, PASS/FAIL/RUNNING icon-text, Bamboo link) and an h4 "Docker Tags" `{code:json}` block of merged tags across all suites. Empty `suiteResults` → `setCommentText("")` and the existing empty-text rendering kicks in.

**User actions.**
- `editButton` (already wired, `JiraCommentPanel.kt:29-32`). **Keep.** Existing toggle between read-only and editable on the textarea — no change.
- `postButton` ("Post Comment") — currently `isEnabled = false`. **Wire.** On click: disable button, show `statusLabel.text = "Posting..."`, launch on `Dispatchers.IO` via the panel's CoroutineScope, call `JiraService.getInstance(project).addComment(state.ticketId, commentPreview.text)`. On `ToolResult.success`: `markJiraCommentPosted` via `HandoverStateService.markJiraCommentPosted(...)` (new method — see §3) AND emit `WorkflowEvent.JiraCommentPosted(ticketKey)` on `EventBus` so the existing handler at `HandoverStateService.kt:127` flips the checklist dot. Show `statusLabel.text = "Posted"`. On error: `statusLabel.text = result.summary`, re-enable button. Enable rule: `state.ticketId.isNotBlank() && state.suiteResults.isNotEmpty() && commentPreview.text.isNotBlank()` (no point posting an empty closure comment).

**Refresh model.** Reactive: subscribe `stateFlow` in `HandoverPanel.init` (one collector for all panels — see §3). Re-renders comment text on every state change (suite triggered, suite finished, ticket changed). No manual refresh button needed.

**Failure modes.**
- No active ticket → `postButton.isEnabled = false`, tooltip "Select a Jira ticket in the Sprint tab first".
- `state.suiteResults.isEmpty()` → button disabled, tooltip "No automation results yet — run a suite first". The textarea shows `"(No automation results to summarise yet.)"` placeholder text.
- Jira auth failure → `ToolResult.error.summary` rendered in `statusLabel`. No toast (status label suffices for an inline action).
- Network error → same; existing `JiraServiceImpl` retry policy applies.

**Out of scope for v1.**
- Editing the comment template (e.g., custom h4 names). User can hand-edit in the textarea before posting via the Edit toggle.
- Verifying the comment was actually posted by re-fetching the ticket comments. Trust the API success.
- "Append vs replace existing closure comment" — Jira always appends, so no decision needed.

---

### 2.2 Copyright Panel

**User intent.** "Before I create my PR, fix the copyright headers in the files I changed so I don't lose 5 minutes during code review on a comment about year-not-current."

**Inputs.**
- VCS changelist: changed files in the active changelist via `ChangeListManager.getInstance(project).allChanges` (resolved to `VirtualFile` via `change.virtualFile` or `change.afterRevision?.file`). This matches the **memory `project_copyright_rules`** scope: "earliest-currentYear for changed/new files".
- `CopyrightFixService` (already-implemented, `CopyrightFixService.kt:50-140`).
- `PluginSettings.state.copyrightTemplate` (verify field exists — if not, settings UI must add a multiline field; see §6 OQ-3).

**Rendered content.** A `JBList<CopyrightFileEntry>` (existing `CopyrightPanel.kt:19-20`) showing each changed file with its `CopyrightStatus` (OK / YEAR_OUTDATED / MISSING_HEADER) and the year transition (e.g. `"2018-2024 -> 2018-2026"`). Custom cell renderer: green check for OK, amber pencil for YEAR_OUTDATED, red plus for MISSING_HEADER, mono path on the right.

**User actions.**
- `rescanButton` ("Rescan") — currently `isEnabled = false`. **Wire as the entry-point.** On click: launch on `Dispatchers.IO`, iterate `ChangeListManager.getInstance(project).allChanges` (reads safe via `readActionBlocking { ... }` per Phase 4 conventions in root `CLAUDE.md`), for each `VirtualFile` skip if `copyrightFixService.isGeneratedFile(vf)` or `!copyrightFixService.isSourceFile(vf)`, otherwise read content via `vf.readText()` and call `copyrightFixService.analyzeFile(vf.path, content)`. Marshal results to EDT, call `copyrightPanel.setEntries(entries)`. Always enabled (the empty-state is a valid post-scan outcome).
- `fixAllButton` ("Fix All") — currently `isEnabled = false`. **Wire.** Enabled when `entries.any { it.status != CopyrightStatus.OK }`. On click: for each non-OK entry, run `WriteCommandAction.runWriteCommandAction(project, "Fix copyright header", null, Runnable { ... })`. For YEAR_OUTDATED, replace the matched expression in the first 15 lines with the consolidated form (use the new-year string the service already computed). For MISSING_HEADER, prepend the rendered template (`copyrightFixService.wrapForLanguage(copyrightFixService.prepareHeader(template, currentYear), file)`). On completion, re-run the same scan logic to update the list AND call `HandoverStateService.markCopyrightFixed()` (already exists, `HandoverStateService.kt:134`).

**Refresh model.** Manual via Rescan button. Re-scan automatically when the active changelist changes? **Out of scope for v1** — see Out of scope below.

**Failure modes.**
- No project changelist (e.g., not a git repo) → empty state "No project files in changeset.". Both buttons disabled.
- Empty changelist (clean working tree) → empty state "No changed files.". `fixAllButton` disabled, `rescanButton` enabled (so clicking gets useful feedback).
- File read fails (binary, deleted) → skip silently, log at `INFO`. Don't break the loop.
- `WriteCommandAction` fails (file read-only, conflict) → toast `WorkflowNotificationService` "Could not fix N files". List those files in a status-area sub-label.
- No copyright template configured → empty state "No copyright template configured. Add one in Settings → Workflow Orchestrator → Code." (link the action; see OQ-3).

**Out of scope for v1.**
- Per-file Fix (only Fix All). Adding per-row inline Fix actions is a 30-LOC follow-up.
- Watching `ChangeListManager.addChangeListListener` for live updates. Manual rescan for now.
- Retroactively scanning the whole project. Strictly scoped to the changelist per the user's stated rule.

---

### 2.3 QA Clipboard Panel

**User intent.** "Hand off to QA via email/Slack: paste a single block with all docker tags + automation suite links + ticket IDs in a clean format."

**Inputs.**
- `HandoverState.suiteResults` — for tags + links (`HandoverStateService.kt:96-120`).
- `HandoverState.ticketId` — for the ticket section.
- `QaClipboardService.buildPayloadFromSuiteResults` (already-implemented, `QaClipboardService.kt:51-86`) and `formatForClipboard` (`:27-49`).

**Rendered content.** Two-section layout (already exists, `QaClipboardPanel.kt:62-64`): the top `tagListPanel` with one row per service (`service: tag` + per-row Copy button — `:84-93`), and the bottom `textArea` with the full formatted payload. Bind to state: on each `stateFlow` emission, build payload and call `qaClipboardPanel.setDockerTags(payload.dockerTags)` and `setFormattedText(qaClipboardService.formatForClipboard(payload))`.

**User actions.**
- `copyAllButton` (already wired, `QaClipboardPanel.kt:44-50`). **Keep.** No change.
- Per-row `Copy` buttons (already wired, `:87-89`). **Keep.**
- `addServiceButton` ("Add Service") — currently `isEnabled = false`. **Delete this button.** The premise — manually adding extra services to the clipboard payload — has no clear user value: the docker-tags map already comes from automation suite results, and adding a "service" without a tag value would be useless. Removing it simplifies the panel and removes one of the seven "Coming soon" tooltips. Replace the eastComponent in the panel header with `null` (panel header signature already supports it, `PanelHeaders.kt:17`).

**Refresh model.** Reactive (state flow). No manual refresh.

**Failure modes.**
- No `suiteResults` → empty state "No services configured." (already exists at `:35-39`). Buttons disabled.
- Malformed `dockerTagsJson` for a suite → service skips that suite's tags silently (already-handled, `QaClipboardService.kt:64-66`).
- Clipboard write fails (rare) → silent — `ClipboardUtil.copyToClipboard` already catches.

**Out of scope for v1.**
- Customising the format (markdown vs Slack vs plain).
- Including build links from `BuildSummary` (only suite/automation links for now).
- "Send to email/Slack directly" — clipboard is the documented mechanism per `project_workflow_sequence.md`.

---

### 2.4 Time Log Panel

**User intent.** "Log my time on the active ticket via Jira's worklog so I don't have to leave the IDE for time tracking."

**Inputs.**
- `HandoverState.ticketId` — for `setTicket`.
- `HandoverState.startWorkTimestamp` — populated from `PluginSettings.state.startWorkTimestamp` (`HandoverStateService.kt:61`); the `:jira` Start Work flow sets this.
- `TimeTrackingService` — validation + ISO formatting (`TimeTrackingService.kt:25-60`).
- `JiraService.logWork(key, timeSpent, comment)` (`JiraService.kt:51`) — note Jira's `timeSpent` is a string like `"1h 30m"` or `"3600"` (seconds); the service should accept the seconds form via `TimeTrackingService.hoursToSeconds(hours).toString() + "s"` or `"${seconds}"`. **Verify in implementation** which of these `JiraServiceImpl` already produces; if it expects "1h 30m" form, add a `secondsToJiraTimeString` helper to `TimeTrackingService`.

**Rendered content.** Already-built form: ticket label, date field, hours field with +/- steppers (default 1.0, max 7.0, step 0.5 — `TimeLogPanel.kt:26-36, 110-115`), comment field, elapsed-hint label. Bind:
- `setTicket(state.ticketId)` on every state change — toggles `cardLayout` between empty and form (existing logic, `:101-108`).
- `dateField.text = LocalDate.now().toString()` on first render (one-shot in `init`, not reactive).
- `elapsedHintLabel.text =` "Suggested: ${hours} (since Start Work)" if `state.startWorkTimestamp > 0`, computed via `TimeTrackingService.computeElapsedHours(state.startWorkTimestamp, System.currentTimeMillis())` and clamped via `clampHours`.

**User actions.**
- `+` / `-` steppers (already wired, `:30-35`). **Keep.**
- `logButton` ("Log Work") — currently `isEnabled = false`. **Wire.** Enable when `state.ticketId.isNotBlank() && hoursField.text.toDoubleOrNull() in (0, 7]`. On click: parse hours, parse date as `LocalDate.parse(dateField.text)` (with format error → `statusLabel.text = "Invalid date"`), validate via `timeTrackingService.validateHours` and `!isFutureDate`, build `started` ISO string via `formatStartedDate(year, month, day, 9, 0)` (start-of-business default), call `JiraService.logWork(state.ticketId, "${seconds}", commentField.text.takeIf { it.isNotBlank() })`. On success: `HandoverStateService.markWorkLogged()` (already exists, `:147`) and `statusLabel.text = "Logged ${hours}h"`. On failure: render `result.summary`.

**Refresh model.** Reactive on `ticketId` change (toggles form↔empty). No polling.

**Failure modes.**
- No active ticket → form hidden, "Select a ticket to log time." (existing, `:21-25`).
- Invalid hours → inline validation, `logButton` disabled when out of range.
- Future date → `statusLabel.text = "Cannot log future date"`, button stays clickable but the click is rejected.
- Jira auth failure → result.summary in statusLabel.
- `worklog` permission missing → handled by the same error path; we do NOT pre-check `getMyPermissions` for v1 (would add a network call on every panel open). Match the pattern in `:jira/QuickCommentPanel`.

**Out of scope for v1.**
- Worklog permission gating via `getMyPermissions` (the user discovers via the error message).
- Multi-day logging (currently one log per click).
- Auto-fill comment from active ticket's branch / commit summary.
- Auto-log on PR merge or ticket transition.
- Editing existing worklogs (`JiraServiceImpl` doesn't expose update worklog, only add).

---

### 2.5 AI Pre-Review Panel

**User intent.** "Before I push for code review, run an AI pass over my diff to catch obvious mistakes (missing @Transactional, unclosed resources, etc.) so the human reviewer doesn't have to."

**Inputs.**
- VCS diff: between current branch and the PR target branch (or, if no PR is focused, between current branch and the configured default branch via `core.git.DefaultBranchResolver`).
- `PreReviewService.buildEnrichedReviewPrompt` + `parseFindings` (`PreReviewService.kt:75-114, 31-50`).
- `TextGenerationService.getInstance()` — the AI EP (`core/ai/TextGenerationService.kt:96-98`).
- `HandoverState.prUrl` (optional — informs whether to use PR target or default branch).

**Rendered content.** `JBList<ReviewFinding>` (already exists, `PreReviewPanel.kt:17-18`) sorted HIGH → MEDIUM → LOW. Custom cell renderer: severity chip (red/amber/grey), file:line in mono, message, pattern in faded text. `statusLabel.text = "${findings.size} finding(s)"` (already wired into `setFindings`, `:35-39`).

**User actions.**
- `analyzeButton` ("Analyze with AI") — currently `isEnabled = false`. **Wire.** Enable when `TextGenerationService.getInstance() != null && currentBranchHasDiff()`. On click: disable button, `statusLabel.text = "Computing diff..."`, launch on Dispatchers.IO. Resolve target branch (PR-derived or default), get the diff via `git4idea.commands.Git` `runCommand("diff", target..source)` (mirror `pullrequest/service/PrDescriptionGenerator.getDiffBetweenBranches`). Validate via `preReviewService.validateDiff(diff)` — if `EMPTY` show "No diff to review", if `TOO_LARGE` show "Diff exceeds ${maxDiffLinesForReview} lines (configurable in Settings)". Resolve `changedFiles` via `git diff --name-only` then `LocalFileSystem.findFileByPath(...)`. Call `preReviewService.buildEnrichedReviewPrompt(diff, changedFiles)`, then `textGen.generateText(project, prompt, changedFilePaths.take(20))`. On success: parse via `parseFindings(aiResponse)`, call `preReviewPanel.setFindings(findings)`. On failure: `statusLabel.text = "AI review unavailable"`.

**Refresh model.** Manual via Analyze button. Diffs change too often to auto-poll.

**Failure modes.**
- No `TextGenerationService` registered (Sourcegraph not configured) → button disabled, tooltip "Configure Sourcegraph in Settings to enable AI review".
- Git command fails → `statusLabel.text = "Could not compute diff"`.
- Diff TOO_LARGE → status label says so, list cleared.
- AI returns empty/unparseable text → `setFindings(emptyList())`, label "0 finding(s) (review may be inconclusive)". Keep raw text accessible via a "View raw" link (out of scope v1; just log to `idea.log`).
- LLM rate limit / 429 → result.summary surfaces; user retries.

**Out of scope for v1.**
- Click-to-jump from finding to file:line in editor (a 20-LOC follow-up using `OpenFileDescriptor`).
- Severity filter chips above the list.
- "Re-analyze just one file" granular re-runs.
- Preserving findings across panel switches via state.

---

### 2.6 Macro Panel — DELETE

**Recommendation: delete the panel and the service.**

**Reasoning.** The macro panel orchestrates "Copyright → Comment → Transition → Log" in a single button. But:
1. None of those four are currently wired (this plan wires the first three; transition lives in the Context sidebar — see H-P1-1 fold-in).
2. Each has different success/failure semantics (Copyright = local, idempotent; Comment = single API call; Transition = workflow-dependent, may have required fields like resolution; Log = needs a date the user picks). Wrapping them in a single sequential macro hides the divergent UX needs.
3. The `:handover/CLAUDE.md:23-27` doc explicitly says "No single 'Complete Task' button. The real workflow is sequential" — the macro contradicts the documented intent (audit **H-P1-4**).
4. ~120 LOC of dead code (panel + service + tests) for a feature the user has never used and that fights the documented workflow.

**Action.** Delete `CompletionMacroPanel.kt` (88 LOC), `CompletionMacroService.kt` (115 LOC), `CompletionMacroServiceTest.kt` (verify), `MacroStep` + `MacroStepStatus` from `HandoverModels.kt` (lines 80-87). Remove `PANEL_MACRO` from `HandoverToolbar.kt:22, 32` and the `completionMacroPanel` field/registration from `HandoverPanel.kt:40, 51`. Drop the macro paragraph from `:handover/CLAUDE.md`.

**Net deletion.** ~250 LOC including tests.

**If user pushes back:** acceptable alternative is "leave it in place but disabled with tooltip 'Run individual steps via the panels above'" — but that's worse than deletion (still confuses users, still occupies a tab slot). My strong recommendation is delete.

---

### 2.7 Context sidebar's transition control (folds in H-P1-1)

The audit's H-P1-1 calls out that `HandoverContextPanel.transitionComboBox` (`:41`) is never populated and `transitionButton` (`:42-45`) is permanently disabled. This plan **folds it into the Jira Comment panel's flow**:

After Post Comment succeeds, run a follow-up: fetch transitions via `JiraService.getTransitions(state.ticketId)`, and if there's a workflow mapping for `intent = "MARK_DONE"` (per the existing `PluginSettings.state.workflowMappings` JSON inspected by `CompletionMacroService.getReviewTransitionLabel` at `:32-44`), execute it via `TicketTransitionService.executeTransition`. Drop `transitionComboBox` and `transitionButton` from `HandoverContextPanel.kt:41-45, 501-503` entirely; the user's workflow doesn't need a manual transition picker on Handover (the Sprint tab already has one — `:jira/TicketTransitionDialog`). Net deletion ~20 LOC and removes one "Coming soon" button.

**If user pushes back** ("I want a manual transition control on Handover"): keep the combobox + button, populate via `getTransitions` on every `ticketId` change, wire button to open `TransitionDialogOpener.open(project, ticketKey, transitionId)` (existing infrastructure, `core/services/jira/TransitionDialogOpener.kt:5-7`).

---

## 3. Service layer changes

### 3.1 `HandoverStateService` — add `markJiraCommentPosted` (small)

Symmetry with `markCopyrightFixed` / `markJiraTransitioned` / `markWorkLogged` (`HandoverStateService.kt:134-150`). Currently the only path to `jiraCommentPosted = true` is the `WorkflowEvent.JiraCommentPosted` handler at `:127`. Adding a direct setter lets the Jira Comment panel also flip the flag without round-tripping through `EventBus`. **Optional** — could equally just emit the event from the panel; but the symmetry is clean.

### 3.2 `JiraClosureService` — canonicalise constructor (drop no-arg)

Currently `JiraClosureService.kt:13-22` has both `constructor(project: Project)` and `constructor()` — the no-arg is a test-only artifact (audit **H-P1-2**). Same anti-pattern in `CompletionMacroService.kt:19-23`, `CopyrightFixService.kt:23-28`, `PreReviewService.kt:18-22`, `QaClipboardService.kt:18-22`, `TimeTrackingService.kt:17-21`.

**Action.** Drop the no-arg constructor on all six services. Update tests to either:
- Construct via `LightProjectFixture.project` (preferred — exercises the real DI path), or
- Test the pure-logic methods statically (e.g., `JiraClosureService.buildClosureComment` could be a top-level function or an `object` since it has no project state — verify).

Specifically `JiraClosureService` has `private var project: Project? = null` as a field but **never reads it** — the entire class can become an `object` or top-level function. Same for `CompletionMacroService` (after deletion, moot). `QaClipboardService` similarly never reads `project`. **Audit recommendation: convert these three to `object` or extension-free top-level functions** since they hold no project state — the `@Service` annotation is overkill.

Services that DO need project: `CopyrightFixService` (uses `ProjectFileIndex`), `PreReviewService` (uses `PluginSettings`, `PsiContextEnricher`), `TimeTrackingService` (uses `PluginSettings`).

### 3.3 No new `:core` interface methods needed

`JiraService.addComment` / `logWork` / `getTransitions` / `transition` already exist (`core/services/JiraService.kt:48,51,37,40`). `TextGenerationService.generateText` exists (`core/ai/TextGenerationService.kt:34-38`). `BambooService.getBuild` exists for the QA Clipboard / Jira Comment flows that need build data. No new EP, no new contract.

If the implementer finds `JiraService.logWork`'s `timeSpent` string format is wrong (Jira accepts both `"1h 30m"` and `"3600"` seconds), the fix is in `TimeTrackingService` not `JiraService`.

### 3.4 No `HandoverJiraClient` — delete the `:handover/CLAUDE.md` line (H-P1-3)

The doc claims `HandoverJiraClient — Jira REST API client for comments and worklogs` (`:handover/CLAUDE.md:8`). The class doesn't exist. Plan: delete that line. All Jira mutations route through `JiraService`. Document in `:handover/CLAUDE.md` instead: `JiraClosureService — formats wiki-markup closure comments from suite results; calls JiraService.addComment via panel action.`

### 3.5 Documentation drift fixes (H-P1-4)

Update `:handover/CLAUDE.md`:
- Remove the "No single 'Complete Task' button" paragraph if Macro is deleted.
- Add a "Wire-up status" section summarising which panels have which actions (post-Phase 1).
- Remove the `HandoverJiraClient` line.
- Replace `CompletionMacroService` line with deletion note (or remove entirely).
- Keep the rest as-is.

### 3.6 H-P1-5 (`escapeWikiMarkup` doesn't escape `[`/`]`) — fold in

While we're touching `JiraClosureService`, fix the regex in `:74-75`. One-line addition: `.replace("[", "\\[").replace("]", "\\]")`. Benign today (no current data path produces `[`-bearing suite plan keys), trivial to fix.

### 3.7 Deletions

- `CompletionMacroService.kt` (115 LOC).
- `CompletionMacroPanel.kt` (88 LOC).
- `CompletionMacroServiceTest.kt`.
- `MacroStep` + `MacroStepStatus` from `HandoverModels.kt` (lines 80-87).
- `addServiceButton` from `QaClipboardPanel.kt:25-28`.
- `transitionComboBox` + `transitionButton` from `HandoverContextPanel.kt:41-45, 501-503` IF user agrees per §2.7.

---

## 4. Cross-module integration points

`HandoverPanel` will read from / call into:

- **`WorkflowContextService.activeTicketFlow`** — already wired via `HandoverStateService.kt:73-77`. No new direct read in `HandoverPanel`; goes through `HandoverStateService.stateFlow`.
- **`HandoverStateService.stateFlow`** — single subscription in `HandoverPanel.init`; one collector per panel binding (or one collector that fans out — implementer's call). **This is the load-bearing change.** Currently only `contextPanel.updateState(state)` is called (`HandoverPanel.kt:63-67`); add five more setter calls inside the same `collect`.
- **`JiraService.addComment` / `logWork` / `getTransitions` / `transition`** — direct calls from panels via `JiraService.getInstance(project)`.
- **`TicketTransitionService.executeTransition`** — only if §2.7 keeps the transition control or for the post-comment auto-transition.
- **`TextGenerationService.getInstance()`** — AI Review panel.
- **`BambooService.getBuild`** — only if AI Review wants to attach failing-test context (out of scope v1).
- **`BitbucketService.getPullRequestDiff`** (if focusPr is set) — AI Review may use this instead of git CLI in some setups (out of scope v1; use `git4idea.commands.Git` to mirror `:pullrequest/PrDescriptionGenerator`).
- **`ChangeListManager.getInstance(project).allChanges`** — Copyright panel scan.
- **`EventBus`** — emit `WorkflowEvent.JiraCommentPosted` after successful comment post, so `HandoverStateService.handleEvent` flips the checklist dot.
- **`PluginSettings`** — `copyrightTemplate` (verify exists), `maxDiffLinesForReview` (already exists per `PreReviewService.kt:137`), `workflowMappings` (already used in `CompletionMacroService:34`), `startWorkTimestamp` (already used in `HandoverStateService:61`).
- **`WorkflowNotificationService`** — for terminal toasts on multi-file write failures (Copyright Fix All).

---

## 5. Implementation phasing

Total work, coarse-grained: 6-9 commits, ~600-900 LOC added, ~250 LOC deleted, net ~+400-650 LOC.

**Phase 1 — Reactive plumbing + Jira Comment + Macro deletion (medium)**

Files: `HandoverPanel.kt`, `JiraCommentPanel.kt`, `JiraClosureService.kt`, `HandoverStateService.kt`, `:handover/CLAUDE.md`, plus deletions.

Single commit (or two — split by deletion vs addition):
1. Wire `stateFlow.collect` in `HandoverPanel.init` to call `jiraCommentPanel.setCommentText(...)` on each emission.
2. Wire `postButton` action listener (call `JiraService.addComment`, emit event).
3. Drop `JiraClosureService` no-arg constructor + add `[`/`]` escaping (H-P1-5).
4. Delete Macro panel + service + tests + tab entry (§2.6).
5. Fix `:handover/CLAUDE.md` (drop `HandoverJiraClient` line, drop macro mention, drop "No single Complete Task" paragraph).

**Verification.** Run with a real Jira instance + an automation suite that's fired; click Post Comment; confirm the comment appears on the ticket and the checklist dot flips green. Run `:handover:test` — expect the macro tests to be deleted, no other regressions.

**Risk.** Lowest. Pure additive on the panel side; deletions are well-contained (macro is dead code per audit).

**Effort.** Small-to-medium. ~150 LOC added, ~250 LOC deleted.

---

**Phase 2 — Copyright + QA Clipboard wire-up (medium)**

Files: `CopyrightPanel.kt`, `QaClipboardPanel.kt`, `HandoverPanel.kt`, possibly `PluginSettings.kt`.

1. Wire `rescanButton` to iterate `ChangeListManager.allChanges` + call `analyzeFile`. Use `readActionBlocking { }` for VFS reads in coroutines per Phase 4.
2. Wire `fixAllButton` to `WriteCommandAction.runWriteCommandAction` block, calling existing `wrapForLanguage` / `prepareHeader` / `consolidateYears`.
3. Custom cell renderer for `JBList<CopyrightFileEntry>` (status icon + path + year transition).
4. Wire `stateFlow.collect` for QA Clipboard (`setDockerTags` + `setFormattedText`).
5. Delete `addServiceButton` from `QaClipboardPanel`.
6. Add `copyrightTemplate` field to `PluginSettings.State` if not present, with a settings-UI textarea (per `feedback_settings_ui`).

**Verification.** With a sample changelist of mixed-status files, click Rescan → see correct statuses, click Fix All → all files updated, file system shows updated headers. With a fired suite, switch to QA Clipboard tab → see services + textarea populated.

**Risk.** Medium — `WriteCommandAction` + multi-file writes on EDT need care. The service's pure-logic functions are well-tested; only the new I/O wrapper is new.

**Effort.** Medium. ~250 LOC added, ~30 LOC deleted.

---

**Phase 3 — Time Log (medium)**

Files: `TimeLogPanel.kt`, `TimeTrackingService.kt`.

1. Wire `setTicket` from `stateFlow`.
2. Wire `logButton` action listener: parse hours + date, validate, call `JiraService.logWork`.
3. Add `secondsToJiraTimeString` helper to `TimeTrackingService` if `JiraServiceImpl.logWork` requires the `"1h 30m"` form (verify in implementation).
4. Default `dateField` to today's date on init; populate `elapsedHintLabel` from `startWorkTimestamp`.

**Verification.** With an active ticket, fill in 2.5h + today + a comment, click Log Work → confirm worklog appears on the Jira issue.

**Risk.** Medium — date parsing has edge cases (locale-dependent formats); pin to ISO `yyyy-MM-dd`.

**Effort.** Small-to-medium. ~120 LOC added.

---

**Phase 4 — AI Pre-Review — SKIPPED**

The plan as authored called for a one-shot AI pass over `git diff target...source`,
prompt built via `PreReviewService.buildEnrichedReviewPrompt`, response parsed via
`PreReviewService.parseFindings`, findings rendered in a `JBList<ReviewFinding>`
inside `PreReviewPanel`.

**Why skipped:** the PR tab already ships a fully-built AI Review surface
(`pullrequest/ui/PrDetailPanel.aiReviewToggle` + `AiReviewTabPanel` +
`PrReviewSessionRegistry` + `core.prreview.PrReviewFindingsStore`). It is strictly
more capable than what Phase 4 would have built:

| | PR tab AI Review (shipped) | Handover AI Pre-Review (Phase 4 plan) |
|---|---|---|
| Mechanism | Agentic loop with `ai_review.add_finding` + `bitbucket_review.list_comments` tools | One-shot prompt → regex parser |
| Findings | Persistent in `PrReviewFindingsStore` keyed by `(prId, sessionId)` | Ephemeral in-panel JBList |
| Output | Pushable as inline Bitbucket comments via the AI Review sub-tab | Read-only |
| Diff source | Bitbucket PR API | Local git CLI |

Both surfaces would have run on the same Sourcegraph backend, just at different
fidelities. Maintaining two would have meant maintaining two prompt formats, two
finding parsers, and two ways to dismiss findings — with no story for which to
trust when they disagreed. Original wireup plan was authored without auditing the
PR-tab side first.

**What was deleted in the SKIP** (commit follows this doc update):

- `handover/.../service/PreReviewService.kt` (~160 LOC) and its test (~80 LOC)
- `handover/.../ui/panels/PreReviewPanel.kt` (~50 LOC)
- `ReviewFinding` + `FindingSeverity` from `handover/model/HandoverModels.kt`
- `ReviewFinding sorts by severity` test from `HandoverModelsTest.kt`
- `PANEL_AI_REVIEW` const + toolbar action from `HandoverToolbar.kt`
- `preReviewPanel` field + import + card-layout registration from `HandoverPanel.kt`

The `core.psi.PsiContextEnricher` that `PreReviewService` consumed stays — it has
other consumers in `:agent` and `:pullrequest`.

**If a use case re-emerges** (e.g. "I want to review without ever opening a PR")
the right move is to extend the PR-tab AI Review to accept a "pre-PR" mode
(branch-vs-target diff with no PR id), not to reintroduce a parallel handover-side
implementation.

---

**Phase 5 — Service constructor canonicalisation + doc fixes (small)**

Files: all six handover service files, `:handover/CLAUDE.md`, updated tests.

1. Drop no-arg constructors from all six services per H-P1-2.
2. For `JiraClosureService` and `QaClipboardService`, consider converting to `object` (no project state).
3. Update tests to use `LightProjectFixture.project` or static-method tests.
4. Final sweep of `:handover/CLAUDE.md`.

**Verification.** `:handover:test` green. `verifyPlugin` clean.

**Risk.** Low — mechanical refactor.

**Effort.** Small. ~50 LOC modified across services + tests.

---

**Recommended commit ordering for the implementer:** **Phase 1 → Phase 2 → Phase 3 → Phase 5 → Phase 4.** Phases 1-3 ship visible user value with low risk; Phase 5 is pure cleanup that benefits from coming before Phase 4 (so the AI Review panel uses the canonical service shape from the start); Phase 4 is the biggest and most failure-prone — last, to minimise rebase pain.

**As shipped:** Phase 1 → 2 → 3 → 5 → Phase 4 SKIPPED (see the Phase 4 section above for rationale — PR-tab AI Review covers the use case strictly more capably).

---

## 6. Open questions

These block all phases unless explicitly answered. Each has a candidate answer + my recommendation.

1. **OQ-1 (BLOCKS PHASE 1): Should the Macro panel + service be deleted, or kept-and-wired?**
   - Candidate A: delete (~250 LOC reduction; aligns with `:handover/CLAUDE.md:23-27` "no single Complete Task button").
   - Candidate B: keep but wire (~250 LOC of new wiring, plus all four sub-actions need to be already-wired first).
   - **My recommendation: A. Delete.** The doc explicitly contradicts the macro's existence; the user has never asked for it; per-step actions cover the same need.

2. **OQ-2 (BLOCKS PHASE 1 / §2.7): Should `HandoverContextPanel` keep its transition control?**
   - Candidate A: delete the combobox + button; auto-transition via `MARK_DONE` workflow mapping after Post Comment succeeds.
   - Candidate B: keep them, populate via `getTransitions`, wire button to `TransitionDialogOpener.open(...)` (mirrors Sprint tab UX).
   - Candidate C: delete entirely; users transition from Sprint tab.
   - **My recommendation: C.** The Sprint tab already has a battle-tested `TicketTransitionDialog`; replicating it here adds maintenance burden. Keep Handover focused on what only Handover does (closure comment, copyright, QA clipboard, time log).

3. **OQ-3 (BLOCKS PHASE 2): Where does the copyright template live?**
   - Candidate A: a `copyrightTemplate` string field in `PluginSettings.State` (project-level); settings UI exposes a multiline textarea.
   - Candidate B: read from a `.idea/copyright/` profile XML (IntelliJ's built-in copyright plugin format).
   - Candidate C: hard-coded fallback ("Copyright {year} CompanyName. All rights reserved.") with optional override.
   - **My recommendation: A.** Simplest. Implementer confirms whether `copyrightTemplate` already exists in `PluginSettings`; if not, adds it per `feedback_settings_ui`.

4. **OQ-4 (BLOCKS PHASE 3): Does `JiraService.logWork` accept seconds-with-suffix (`"3600s"`), seconds-numeric (`"3600"`), or `"1h 30m"` form?**
   - Resolves with: 5-line read of `JiraServiceImpl.logWork` + matching `JiraApiClient` request body. **Implementer's first read in Phase 3.** Don't speculate now.

5. **OQ-5 (BLOCKED PHASE 4 — moot now Phase 4 is SKIPPED): Is the AI Review's diff scoped to the **active changelist** (uncommitted) or **branch-vs-target** (committed-but-unmerged)?**
   - Resolution: not applicable. Phase 4 was skipped because PR-tab AI Review covers the use case (see Phase 4 section).

6. **OQ-6 (does NOT block any phase): Should we add a "what shipped" section to the closure comment that includes the PR URL + final build link?**
   - Candidate A: keep `JiraClosureService.buildClosureComment` as-is (suite results + docker tags).
   - Candidate B: prepend a "Shipped:" section with `state.prUrl` + `state.buildStatus.planKey + buildNumber + Bamboo link`.
   - **My recommendation: A for v1, defer B.** Don't gold-plate the formatter; v1 ships exactly what's already implemented + tested.

---

## 7. What this plan does NOT cover

P1 bugs from the audit that are NOT in this plan, with rationale:

- **H-P1-1** (transitionComboBox / transitionButton dead) — **folded into §2.7 (recommend deletion).**
- **H-P1-2** (no-arg / Project mongrel constructors) — **folded into §3.2 / Phase 5.**
- **H-P1-3** (`HandoverJiraClient` doc claim, class missing) — **folded into §3.4 / Phase 1 doc fixes.** No new client class; remove the doc line.
- **H-P1-4** (services flag a non-existent workflow) — **folded into §3.5 / Phase 1 doc fixes.**
- **H-P1-5** (`escapeWikiMarkup` missing `[` / `]`) — **folded into §3.6 / Phase 1.** One-line fix while we're touching the file.

P2 bugs (`H-P2-1` — `dockerTagLabel` truncates at 50 chars without `…`) — **explicitly out of scope.** Cosmetic; affects only the small Docker section of the context sidebar; can be a one-line follow-up after Phase 1-4 ship. Recommend filing as a `Polish` issue, not in this stream.

Out of scope:
- New `:handover` modules. The plan stays inside the existing module.
- Cross-module sweeps for the same anti-patterns elsewhere (the audit's §7 cross-module table flags this work for other modules — out of scope here).
- Bamboo-side audit P0/P1 bugs (A-P0-* / A-P1-*) — those have a separate plan or are already fixed in `59c9ea8d`.
- New tests beyond what's needed to not break existing green tests during deletion / signature changes. The implementer should add panel-level tests as a follow-up.

---

## 8. References

- Audit doc: `docs/research/2026-05-07-automation-handover-audit.md` (§2.5, §3 H-P1-*).
- Module CLAUDE.md (current, drift to be fixed): `handover/CLAUDE.md`.
- Project root CLAUDE.md (Service Architecture, Threading, ToolResult, Settings rules).
- Memory: `project_workflow_sequence.md` (PR before automation; QA via email/Slack), `project_copyright_rules.md` (earliest-currentYear for changed files), `feedback_settings_ui.md`, `feedback_reuse_code.md`, `feedback_no_coauthor.md`.
- Source files: every panel under `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/panels/`, every service under `…/handover/service/`, the model file `…/handover/model/HandoverModels.kt`, the toolbar `…/handover/ui/HandoverToolbar.kt`, the tab provider `…/handover/ui/HandoverTabProvider.kt`.
- `:core` services consumed: `JiraService`, `TextGenerationService`, `TicketTransitionService`, `BambooService` (only if AI Review wants build context, v1 doesn't), `BitbucketService` (out of scope v1).
