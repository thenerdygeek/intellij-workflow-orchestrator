# Connector Tabs — Build, Quality, Automation, Handover

**Scope.** This document covers the four "right-side connector" tabs of the Workflow Orchestrator
tool window. The tester runs `./gradlew runIde` on licensed **Windows IntelliJ IDEA Ultimate 2025.1+**
with real Bamboo, SonarQube, and Jira tokens configured.

**Hard constraint — no state writes during testing.**  
Every scenario that reaches a write-capable control (build trigger, Jira closure, Fix All, queue run)
must be verified up to (but not including) the confirmation dialog or submit action, then **cancelled**.
These are marked **⛔ SKIP-EXECUTION** in the "Write note" field.

**Grounding.** Every expected behavior is traced to source. File paths are absolute; line numbers
reference the `feature/plugin-split` branch as read for this plan.

---

## Build (Bamboo)

Primary files:
- `bamboo/src/main/kotlin/…/bamboo/ui/BuildDashboardPanel.kt`
- `bamboo/src/main/kotlin/…/bamboo/ui/StageListPanel.kt`
- `bamboo/src/main/kotlin/…/bamboo/ui/StageDetailPanel.kt`
- `bamboo/src/main/kotlin/…/bamboo/ui/BuildDashboardActionGate.kt`

---

### [BLD-01] Tab Initial Load — Plan Auto-Resolution from Branch

- **Component(s):** `BuildDashboardPanel` header row; `headerLabel`; `hintLabel`; `loadingIcon`; `StageListPanel`
- **Preconditions:** Bamboo URL + PAT configured. Current Git branch has an open PR. No prior plan override. Network reachable.
- **Steps:**
  1. Open the Workflow tool window (bottom dock) and click the **Build** tab.
  2. Observe `loadingIcon` (animated spinner, `BuildDashboardPanel.kt:345`) and `headerLabel` text.
  3. Wait up to 10 s for plan-detection waterfall (T0–T4 tiers via `BambooService.autoDetectPlan`).
  4. Observe final header and stage list.
- **Expected — visual:** During detection: spinner visible, `headerLabel` reads "Resolving Bamboo plan for `<branch>`…" (`BuildDashboardPanel.kt:736`). After resolution: header shows **"Plan: {planKey} / {branch}  #{buildNumber}"** (`BuildDashboardPanel.kt:515`); `hintLabel` is `isVisible = false` (`BuildDashboardPanel.kt:508`).
- **Expected — behavioral:** `StageListPanel` transitions from "No stages found." to showing at least one stage group header (§-prefixed row) and one job row. `statusLabel` shows overall status + formatted duration.
- **✅ Checks (tick each):**
  - [ ] Spinner visible during detection, hidden afterwards
  - [ ] Header text matches `"Plan: X / branch  #N"` exactly (note double-space before `#`)
  - [ ] `hintLabel` invisible after successful resolution
  - [ ] Stage list shows at least one header row + one job row
  - [ ] Status bar (bottom) shows build status + duration
- **🐞 Bug signals:** Header stays "Build: loading…" indefinitely; `hintLabel` visible with "No PR" message even when a PR exists; stage list stays empty after 10 s; spinner never stops.
- **Theme/size matrix:** light + dark; narrow tool window (< 400 px) — header must not overlap with toolbar buttons.
- **⛔ Write note:** None — plan auto-detect is read-only.

---

### [BLD-02] Build Status Header Color Coding (Success / Failed / In-Progress)

- **Component(s):** `StageListPanel` cell renderer (`StageListPanel.kt:178–219`); `statusLabel`
- **Preconditions:** BLD-01 passed. Have access to a plan with at least one SUCCESS build, one FAILED build, and ideally one IN_PROGRESS build (or use build history to navigate).
- **Steps:**
  1. With a SUCCESS build loaded: observe the stage list rows.
  2. Select a **failed** build from the history list (BLD-10 prerequisite) and observe.
  3. If an IN_PROGRESS build exists on another plan: switch the repo selector to that plan and observe.
- **Expected — visual:**
  - SUCCESS jobs: green left accent border (`StatusColors.SUCCESS`), `AllIcons.RunConfigurations.TestPassed` icon.
  - FAILED jobs: red left accent border (`StatusColors.ERROR`), `AllIcons.RunConfigurations.TestFailed` icon.
  - IN_PROGRESS jobs: yellow accent border (`StatusColors.WARNING`), animated `AnimatedIcon.Default()` spinner icon.
  - PENDING/UNKNOWN jobs: grey accent border (`StatusColors.SECONDARY_TEXT`), `AllIcons.RunConfigurations.TestNotRan` icon.
  - Stage group headers (§-prefixed): bold SECONDARY_TEXT, no status icon, slightly indented from the job rows.
- **Expected — behavioral:** `statusLabel` foreground not directly set in code; verify it reads something like "FAILED — 2m 34s" for a failed build.
- **✅ Checks (tick each):**
  - [ ] SUCCESS rows: green left bar + green check icon
  - [ ] FAILED rows: red left bar + red × icon
  - [ ] IN_PROGRESS rows: yellow left bar + spinner icon
  - [ ] Stage group headers: uppercase bold, no icon, no accent border
  - [ ] Duration column rendered as "2m 34s" or similar (not raw milliseconds)
- **🐞 Bug signals:** All rows same grey color regardless of status; icons wrong (e.g., success shows TestFailed); group header rows selectable and trigger log fetch; duration shows "0" or raw ms value.
- **Theme/size matrix:** light + dark (accent colors must remain distinguishable in dark mode); long stage list (> 15 rows) — vertical scrollbar appears and list scrolls.
- **⛔ Write note:** None — status display is read-only.

---

### [BLD-03] Stage Selection → StageDetailPanel Log Load

- **Component(s):** `StageListPanel.stageList` selection listener (`BuildDashboardPanel.kt:420–433`); `StageDetailPanel.showLog`; LOG tab of `tabbedPane`
- **Preconditions:** BLD-01 passed. At least one FAILED or SUCCESS job row visible in the stage list.
- **Steps:**
  1. Single-click a **job row** (not a § header row) in the stage list.
  2. Observe the detail panel on the right side of the splitter.
  3. Click a **§ header row** and observe.
- **Expected — visual:** Clicking a job row: detail panel shows "Loading log…" momentarily then populates with colored console output. LOG tab is selected. Clicking a § header row: detail panel shows empty state (console cleared, no log text).
- **Expected — behavioral:** Log lines containing `[ERROR]` or `BUILD FAILURE` or `Exception` appear in red (`ConsoleViewContentType.ERROR_OUTPUT`, `StageDetailPanel.kt:399–409`). Lines with `[WARNING]` appear in yellow. `[INFO]` lines appear in normal output color. Clicking different job rows swaps the log content.
- **✅ Checks (tick each):**
  - [ ] Single-click on job row triggers log load (not double-click)
  - [ ] § header rows do NOT trigger a log load
  - [ ] ERROR lines rendered in red
  - [ ] WARNING lines rendered in yellow/orange
  - [ ] Switching rows replaces log content (old content not appended)
  - [ ] Splitter is draggable (resize stage list vs detail panel)
- **🐞 Bug signals:** Clicking a header row loads log (header should be unselectable); ERROR lines not colored; clicking second row appends log instead of replacing; detail panel blank for any row selection.
- **Theme/size matrix:** light + dark; narrow detail pane (splitter dragged left) — console view scrolls and search bar doesn't overflow.
- **⛔ Write note:** None — log fetch is read-only.

---

### [BLD-04] Log Search — Highlight, Match Count, Navigate

- **Component(s):** `StageDetailPanel.logSearchField`; `prevMatchButton`; `nextMatchButton`; `matchCountLabel` (`StageDetailPanel.kt:84–111`)
- **Preconditions:** BLD-03 passed. A log with at least 2 occurrences of a known string (e.g. `"INFO"` or a package name) is loaded.
- **Steps:**
  1. Click the search field at the top of the detail panel and type a string known to appear ≥ 2 times.
  2. Observe match count label and yellow highlights in the console.
  3. Click `>` (next match) button; observe scroll position and selection.
  4. Click `<` (prev match) button.
  5. Press **Enter** (next match) and **Shift+Enter** (prev match) via keyboard.
  6. Press **Escape** to clear the search.
- **Expected — visual:** Match count reads "1 of N matches" where N ≥ 2. All match offsets highlighted in yellow (`JBColor(255,200,0,80)` / `50` in dark, `StageDetailPanel.kt:439–444`). Active match scrolled into view with selection. Pressing Escape clears the search field and removes all highlights.
- **Expected — behavioral:** Counter increments cyclically (wraps from N back to 1 after last). Empty search field → "No matches" not shown; match count blank.
- **✅ Checks (tick each):**
  - [ ] Typing in search field immediately shows highlights
  - [ ] Match count label shows "X of N matches"
  - [ ] `>` button advances to next match + scrolls to it
  - [ ] `<` button retreats to previous match + scrolls to it
  - [ ] Keyboard Enter / Shift+Enter equivalent to `>` / `<`
  - [ ] Escape clears field and removes all highlights
  - [ ] Query with no matches shows "No matches" in `matchCountLabel` (warning foreground)
- **🐞 Bug signals:** Counter never advances beyond "1 of 1"; highlights disappear when the scroll position changes; Escape doesn't clear highlights; `matchCountLabel` stays blank for any query.
- **Theme/size matrix:** light + dark; verify yellow highlight visible in dark theme (opacity `50` should still be visible against dark background).
- **⛔ Write note:** None — log search is read-only.

---

### [BLD-05] Log Truncation Warning + "Open Full Log in Editor"

- **Component(s):** `StageDetailPanel.truncationLabel`; `logActionBar`; `openInEditorButton` (`StageDetailPanel.kt:141–150`, `350–376`)
- **Preconditions:** A Bamboo job with a large log (> 50,000 characters / ~50 KB) must exist on the target Bamboo server. Select that job row.
- **Steps:**
  1. Select a job row known to produce a large log (e.g. a long integration test run).
  2. Observe the `logActionBar` strip above the console.
  3. Click "Open full log in editor".
  4. Observe what opens in the editor.
- **Expected — visual:** `logActionBar` becomes visible (`isVisible = true`). `truncationLabel` reads "Log truncated ({N}K chars). Showing last 50K." First line of the console reads "… [Log truncated — showing last 50K chars] …". "Open full log in editor" button visible.
- **Expected — behavioral:** Clicking "Open full log in editor" opens a temp `.log` file in the IDE editor tab with the **full** log text (not the truncated 50 K). A `balloon notification` is NOT expected — the file simply opens. Temp file path visible in the editor tab title (e.g. `bamboo-build-12345.log`).
- **✅ Checks (tick each):**
  - [ ] `logActionBar` hidden for logs ≤ 50 K chars; visible for larger logs
  - [ ] Truncation label shows KB count of full log
  - [ ] Console pane shows the tail of the log, not the head
  - [ ] "Open full log in editor" opens a new editor tab with the full log
  - [ ] Full log file name begins with `bamboo-build-`
- **🐞 Bug signals:** `logActionBar` always hidden; truncation label always blank; "Open full log in editor" does nothing; file that opens is shorter than the displayed 50 K (shows truncated version); editor tab for full log not opened.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None — opening a file in the editor is read-only.

---

### [BLD-06] TESTS Tab — SMT Runner Native UI

- **Component(s):** `StageDetailPanel.tabbedPane` TESTS tab; `showTestResults` (`StageDetailPanel.kt:515–554`); `BambooTestLocator`
- **Preconditions:** A Bamboo job with failing test results exists. Select that job.
- **Steps:**
  1. Select a job that has test failures (red X icon) in the stage list.
  2. Click the **TESTS** tab in the detail panel.
  3. Observe the test runner view.
  4. If a test class appears, double-click a test name to navigate.
- **Expected — visual:** TESTS tab shows IntelliJ's native SMT runner tree (suites → test methods). Passing tests have green icons; failing tests have red icons with message detail. If no test data available, placeholder "No test results available." is shown.
- **Expected — behavioral:** Double-clicking a failing test navigates to the corresponding source file + line (via `BambooTestLocator`, `StageDetailPanel.kt:887–960`). Navigation via `java:test://com.example.Class/method` protocol resolves to a Kotlin/Java file in the project.
- **✅ Checks (tick each):**
  - [ ] TESTS tab auto-selected when test data is loaded
  - [ ] Native SMT tree visible with suite/method hierarchy
  - [ ] Failing tests shown with red icon + failure message
  - [ ] Passing tests shown with green icon
  - [ ] Double-click on failing test navigates to source (if file in project)
  - [ ] TESTS tab shows placeholder text when no test data
- **🐞 Bug signals:** TESTS tab shows plain "Test results: N messages" fallback label instead of tree view; double-click on test does nothing; TESTS tab never auto-selected; fallback label for any build even when tests ran.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None — test result view is read-only.

---

### [BLD-07] ARTIFACTS Tab — List, Download, Open (HTML)

- **Component(s):** `StageDetailPanel.artifactsList`; `ArtifactCellRenderer`; artifact download/open hit-test (`StageDetailPanel.kt:559–697`)
- **Preconditions:** A Bamboo job with at least one artifact exists. Select that job.
- **Steps:**
  1. Select a job that produces artifacts (e.g. a test-report or JAR job).
  2. Click the **ARTIFACTS** tab.
  3. Observe the artifact list items (name, file size, Download button).
  4. Hover over the "Download" button in a row — observe cursor change.
  5. Click the "Download" button for a small artifact and note the behavior (file chooser opens).
     **Immediately cancel the file chooser dialog** — do not download.
  6. If an `.html` artifact exists, verify the "Open" button is present next to "Download".
- **Expected — visual:** "Loading artifacts…" placeholder shown briefly, then replaced by list. Each row: monospace bold artifact name (left, blue foreground), file size in grey (`formatFileSize`, StageDetailPanel.kt:699–706`), "Download" button on right, "Open" button only for `.html` artifacts. Cursor becomes hand cursor on hover over buttons.
- **Expected — behavioral:** Clicking "Download" shows a **"Select Download Directory"** folder chooser (JB FileChooser). Non-`.html` rows have no "Open" button. Empty artifact result shows "No artifacts for this build." placeholder.
- **✅ Checks (tick each):**
  - [ ] "Loading artifacts…" visible while fetching
  - [ ] Artifact names in monospace bold
  - [ ] File sizes shown in human-readable form (B / KB / MB)
  - [ ] "Download" button present for all artifacts with a download URL
  - [ ] "Open" button present ONLY for `.html` artifacts
  - [ ] Hover over button: cursor → hand cursor
  - [ ] Clicking "Download": folder chooser dialog appears
  - [ ] "No artifacts for this build." shown when list is empty
- **🐞 Bug signals:** Artifact list stays on "Loading artifacts…"; file sizes show raw bytes; "Open" button appears on non-HTML artifacts; cursor doesn't change on button hover; folder chooser doesn't open on click; error label not shown on fetch failure.
- **Theme/size matrix:** light + dark; long artifact names (> 50 chars) — verify no horizontal overflow.
- **⛔ Write note:** **Download artifact** (write op: writes file to disk) — verify folder chooser dialog appears, then **Cancel** without selecting a directory.

---

### [BLD-08] Divergence Warning Banner

- **Component(s):** `BuildDashboardPanel.warningLabel` (`BuildDashboardPanel.kt:131–135`); `checkDivergence` logic (`BuildDashboardPanel.kt:290–334`)
- **Preconditions:** The current branch has local commits not pushed, OR the remote branch has commits not pulled (intentionally create ahead/behind state before testing).
- **Steps:**
  1. Ensure local branch is **N commits ahead** of remote (make a local commit, don't push).
  2. Open Build tab (or click Refresh if already open).
  3. Observe the warning label area below the PR bar.
  4. Then create a scenario where the branch is **N commits behind** (or both ahead + behind).
- **Expected — visual:** Warning label shows `AllIcons.General.Warning` icon + amber/orange text (`StatusColors.WARNING` foreground):
  - Ahead: "Local branch is N commit(s) ahead of PR. Push to trigger new builds."
  - Behind: "PR has N commit(s) not in your local branch. Pull to sync."
  - Diverged: "Local and PR have diverged (N local, M remote). Pull and push to sync."
  - In sync: `warningLabel.isVisible = false`.
- **Expected — behavioral:** Warning appears automatically without clicking Refresh (fires on plan load). `isVisible = false` when head SHAs match.
- **✅ Checks (tick each):**
  - [ ] Warning label hidden when branch is in sync
  - [ ] Ahead warning shows correct commit count
  - [ ] Behind warning shows correct commit count
  - [ ] Diverged warning shows both counts
  - [ ] Warning icon (AllIcons.General.Warning) present
  - [ ] Warning foreground is amber/orange (StatusColors.WARNING)
- **🐞 Bug signals:** Warning never shown even when ahead/behind; warning always visible even when in sync; wrong commit counts; no icon on the warning label.
- **Theme/size matrix:** light + dark; narrow width — warning text wraps without cutting off count.
- **⛔ Write note:** None — divergence check is read-only.

---

### [BLD-09] Build History List + Historical Build Navigation

- **Component(s):** `BuildDashboardPanel.historyPanel`; `historyList`; `historicalBuildBanner`; "View Latest" link (`BuildDashboardPanel.kt:176–208`, `824–888`)
- **Preconditions:** BLD-01 passed. The current plan has at least 3 historical builds (most plans will satisfy this).
- **Steps:**
  1. Wait for the Build tab to load (BLD-01). Observe "BUILD HISTORY" section below the PR bar.
  2. Click a **historical** build entry (not the most recent one).
  3. Observe header, status bar, stage list, and the amber "Viewing build #N" banner.
  4. Click **"View Latest"** link in the amber banner.
  5. Observe that the view returns to the latest build.
- **Expected — visual:** History section shows "BUILD HISTORY" label (`AllIcons.Vcs.History` icon) and a scrollable list of ≤ 10 entries. Historical build banner reads "Viewing build #N" (`AllIcons.General.Information` icon) + "View Latest" link (`StatusColors.LINK` foreground, hand cursor). After "View Latest": banner hidden, header reverts to latest build.
- **Expected — behavioral:** Clicking a history row triggers `loadHistoricalBuild` — header changes to "Plan: {key}  #{N} (historical)" (`BuildDashboardPanel.kt:841`). Stage list repopulates with that build's stages. After "View Latest", the current monitor state is re-displayed without a re-fetch.
- **✅ Checks (tick each):**
  - [ ] History section visible with ≤ 10 entries
  - [ ] "BUILD HISTORY" header with clock icon
  - [ ] Clicking history row: header changes to "(historical)" suffix
  - [ ] Historical banner visible with build number
  - [ ] "View Latest" link has hand cursor
  - [ ] Clicking "View Latest" dismisses banner and shows current build
  - [ ] Stage list repopulates correctly for the historical build
- **🐞 Bug signals:** History section never appears; clicking history row doesn't change header; "(historical)" suffix missing; "View Latest" does nothing; history shows more than 10 entries; stage list stays empty after clicking a historical row.
- **Theme/size matrix:** light + dark; scroll within history list when > 5 entries.
- **⛔ Write note:** None — viewing historical builds is read-only.

---

### [BLD-10] Newer Build Running Banner

- **Component(s):** `BuildDashboardPanel.newerBuildBanner` (`BuildDashboardPanel.kt:139–148`); state collector for `newerBuild` field (`BuildDashboardPanel.kt:538–552`)
- **Preconditions:** A build is actively **running** on the monitored plan while another (older) build was previously completed.
- **Steps:**
  1. To produce a newer build you **must trigger it from the Bamboo web UI in a browser** (or the Bamboo REST API) while the Build tab is showing the previous completed build. **Do NOT click the in-plugin Trigger Build button** — this plan is read-only against backends and the in-plugin trigger enqueues a real build.
  2. Wait for the next poll cycle (default 30 s, or click Refresh).
  3. Observe the "newer build" banner.
- **Expected — visual:** Banner (`background = StatusColors.INFO_BG`) appears above the stage list area. Label text: "Build #{N} is running — will update automatically when complete" (or "queued" for PENDING status, `BuildDashboardPanel.kt:540–549`). `AllIcons.Toolwindows.ToolWindowRun` icon on the label.
- **Expected — behavioral:** Banner disappears automatically when the newer build completes and becomes the latest. Banner `isVisible = false` when no newer build exists.
- **✅ Checks (tick each):**
  - [ ] Banner appears when a newer build is IN_PROGRESS
  - [ ] Banner text includes correct newer build number
  - [ ] "running" vs "queued" verbiage correct per Bamboo state
  - [ ] Banner disappears after build completes and monitor updates
  - [ ] Run icon present
- **🐞 Bug signals:** Banner never appears; banner stays visible after build completes; wrong build number in text; "running" shown for a queued build or vice versa.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** Observing the banner is read-only, but the build trigger MUST be done externally (Bamboo web UI / REST API) — **do NOT click the in-plugin Trigger Build button**, which enqueues a real build against the backend.

---

### [BLD-11] ReadOnly Mode — Amber Banner + Toolbar Gating

- **Component(s):** `ReadOnlyBanner`; `BuildDashboardActionGate` (`BuildDashboardActionGate.kt:18–29`); action toolbar (Refresh, Trigger Build)
- **Preconditions:** A PR is focused in the PR tab but the local branch **does not match** the PR's `fromBranch` (e.g. user is on `main` while the focused PR is from `feature/XYZ`). `WorkflowContext.interactionMode == ReadOnly`.
- **Steps:**
  1. Focus a PR in the PR tab that belongs to a branch different from the current checkout.
  2. Switch to the Build tab.
  3. Observe the amber banner at the very top.
  4. Hover over the **Refresh** toolbar button and observe tooltip.
  5. Hover over the **Trigger Build** button and observe tooltip.
  6. Hover over the **Stop Build** button (should be enabled when build is running regardless of mode? — check `update()` method).
- **Expected — visual:** Amber `ReadOnlyBanner` visible at the top of the panel (`ReadOnlyBanner(project)`, `BuildDashboardPanel.kt:79–81`). Refresh button: `isEnabled = false`. Trigger Build button: `isEnabled = false`. Tooltip on both disabled buttons: "Disabled: local branch doesn't match the focused PR ({branch})" (`BuildDashboardActionGate.kt:24–27`).
- **Expected — behavioral:** Build list, stage list, and log viewer remain fully readable — only write-capable actions are gated. Stop/Cancel buttons' `update()` checks `state.overallStatus`, not `interactionMode`, so they remain enabled when a build is actually running.
- **✅ Checks (tick each):**
  - [ ] Amber ReadOnlyBanner visible at panel top
  - [ ] Refresh button grayed out
  - [ ] Trigger Build button grayed out
  - [ ] Tooltip on disabled buttons mentions the focused PR's branch name
  - [ ] Stage list and log viewer remain usable (read-only viewing allowed)
  - [ ] Compile and Test (local Maven) buttons unaffected (no `isLiveMode()` check)
- **🐞 Bug signals:** No amber banner in ReadOnly mode; Refresh or Trigger Build buttons remain enabled; tooltip doesn't mention the branch; stage list cleared instead of staying readable.
- **Theme/size matrix:** light + dark (amber banner must be visible in dark mode).
- **⛔ Write note:** None — this test only verifies UI state, not triggering any actions.

---

### [BLD-12] "No Plan Resolved" and "Not Configured" Empty States

- **Component(s):** `BuildDashboardPanel.hintLabel` (`BuildDashboardPanel.kt:360–365`); `showHint()` (`BuildDashboardPanel.kt:749–755`); `splitter.isVisible`
- **Preconditions:** Test two sub-cases separately:  
  (a) Bamboo URL configured but no PR open for current branch (`BuildPlanResolutionPolicy.Resolution.NoPlan`).  
  (b) No Bamboo URL/PAT configured at all.
- **Steps:**
  1. (a) Check out a branch with no open PR and no configured Bamboo plan key → open Build tab.
  2. (b) Open Settings > CI/CD > Bamboo, clear the URL, close Settings → switch to Build tab.
- **Expected — visual (a):** `hintLabel` visible with italic grey text and a contextual message like "No plan resolved for branch 'X' and no configured planKey". Splitter (stage list + detail) hidden (`splitter.isVisible = false`, `BuildDashboardPanel.kt:752`).
- **Expected — visual (b):** `hintLabel` visible with guidance to configure Bamboo in Settings. No spinner.
- **Expected — behavioral:** Hint label text varies by resolution outcome (`Resolution.NoPlan.hintMessage`). No error balloon notification.
- **✅ Checks (tick each):**
  - [ ] hintLabel visible with non-empty italic text
  - [ ] Splitter (stage list + detail) hidden when hint shown
  - [ ] loadingIcon not spinning when hint shown
  - [ ] Different hint text for "no PR" vs "no config" scenarios
- **🐞 Bug signals:** Hint label blank; splitter still visible (empty stage list shown instead of hint); spinner keeps running; generic "null" text in hint label.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None — this is observing empty-state rendering.

---

### [BLD-13] Stop Build / Cancel Build / Rerun Failed Jobs — Confirmation Dialogs

- **Component(s):** Stop Build action (`BuildDashboardPanel.kt:923–955`); Cancel Build action (`BuildDashboardPanel.kt:957–991`); Rerun Failed Jobs action (`BuildDashboardPanel.kt:993–1039`)
- **Preconditions:**
  - For Stop: a build must be IN_PROGRESS (`state.overallStatus == BuildStatus.IN_PROGRESS`).
  - For Cancel: a build must be PENDING (`state.overallStatus == BuildStatus.PENDING`).
  - For Rerun: a build must be FAILED.
- **Steps:**
  1. When a build is IN_PROGRESS: click the **Stop Build** (suspend icon) toolbar button. Observe the `Messages.showYesNoDialog`.
  2. Read dialog title ("Stop Build") and message ("Stop build {planKey} #{N}?"). Click **No** / Cancel.
  3. When a build is PENDING: click **Cancel Build** (× icon). Observe the dialog ("Cancel queued build {planKey} #{N}?"). Click **No**.
  4. When a build is FAILED: click **Rerun Failed Jobs** (restart icon). Observe dialog ("Rerun failed jobs for {planKey} #{N}?"). Click **No**.
  5. After each "No" click: verify the `statusLabel` has NOT changed to "Stopping build…" / "Cancelling build…" / "Rerunning failed jobs…".
- **Expected — visual:** Each action produces a `Messages.showYesNoDialog` (platform dialog) with a question icon. Title and body match `BuildDashboardPanel.kt:928`, `965`, `1010`.
- **Expected — behavioral:** Clicking **No** leaves `statusLabel` unchanged and does NOT call the corresponding Bamboo API.
- **✅ Checks (tick each):**
  - [ ] Stop Build button enabled ONLY when build is IN_PROGRESS
  - [ ] Cancel Build button enabled ONLY when build is PENDING
  - [ ] Rerun Failed Jobs button enabled ONLY when build is FAILED
  - [ ] Each confirm dialog shows correct title, plan key, and build number
  - [ ] Clicking "No" leaves statusLabel unchanged
  - [ ] Dialog uses question icon (not warning/error)
- **🐞 Bug signals:** Dialog appears without a plan key or build number; confirm dialog absent (action fires immediately on click); buttons enabled in wrong states; "No" click still updates statusLabel.
- **Theme/size matrix:** light + dark (dialog inherits IDE LAF).
- **⛔ Write note:** **Stop Build**, **Cancel Build**, **Rerun Failed Jobs** (write ops) — verify the confirmation dialog appears with correct text, then **click No / Cancel**. Do not click Yes.

---

## Quality (Sonar)

Primary files:
- `sonar/src/main/kotlin/…/sonar/ui/QualityDashboardPanel.kt`
- `sonar/src/main/kotlin/…/sonar/ui/OverviewPanel.kt`
- `sonar/src/main/kotlin/…/sonar/ui/IssueListPanel.kt`
- `sonar/src/main/kotlin/…/sonar/ui/IssueDetailPanel.kt`
- `sonar/src/main/kotlin/…/sonar/ui/CoverageTablePanel.kt`
- `sonar/src/main/kotlin/…/sonar/ui/CoveragePreviewPanel.kt`
- `sonar/src/main/kotlin/…/sonar/ui/GateStatusBanner.kt`

---

### [QAL-01] Overview Panel — Four Metric Cards + Numeric Accuracy

- **Component(s):** `OverviewPanel` (`OverviewPanel.kt`); `gateStatusLabel`; `coverageLabel`; `issueCountLabel`; `issueBreakdownLabel`; `healthRatingLabel`
- **Preconditions:** SonarQube URL + token configured. A PR is focused with a configured `sonarProjectKey`. Data has loaded (loadingIcon gone, `statusLabel` shows "Updated Ns ago").
- **Steps:**
  1. Open the Quality tab → Overview sub-tab (default).
  2. Read the **QUALITY GATE** card: status text and background color.
  3. Read the **COVERAGE** card: line % and branch %.
  4. Cross-check: open SonarQube in a browser for the same branch and compare the three percentages.
  5. Read the **ISSUES** card: total count and the B/V/S/H breakdown.
  6. Cross-check issue count against the Issues sub-tab's count label.
  7. Read the **PROJECT HEALTH** card: maintainability rating (A–E).
- **Expected — visual:**
  - QUALITY GATE: "✓ PASSED" in green or "✗ FAILED" in red; background is `StatusColors.SUCCESS_BG` or a red-tint (`0xFDE7E9` / `0x4A1A1A`). Font bold 18pt.
  - COVERAGE card: `"%.1f%%"` format (e.g. "74.3%"). Progress bar below — filled with green/red/grey per gate threshold.
  - ISSUES card: total count large (bold 18pt), breakdown line `"2B 0V 5S 1H\nTotal effort: 3h"` (`OverviewPanel.kt:195`).
  - PROJECT HEALTH card: single letter A/B/C/D/E bold 18pt; debt, reliability, security, duplication below.
- **Expected — behavioral:** Numbers in the Overview panel must exactly match the SonarQube web UI for the same branch + new/overall mode.
- **✅ Checks (tick each):**
  - [ ] Gate card background red/green per actual gate status
  - [ ] Coverage % matches SonarQube web UI (±0.1 % rounding)
  - [ ] Branch coverage shown on second line of Coverage card
  - [ ] Issue count in Overview matches issue count in Issues tab
  - [ ] B/V/S/H breakdown sums to total count
  - [ ] Project Health letter matches SonarQube Maintainability rating
  - [ ] Technical debt shown in human-readable form (e.g. "2d 3h")
- **🐞 Bug signals:** Gate card stays grey ("—") when gate data exists; coverage shows 0.0% when project has coverage; issue total ≠ Issues tab count; B/V/S/H breakdown sums differ from total; health card blank when rating is available.
- **Theme/size matrix:** light + dark (card backgrounds must be visible); narrow window (cards in 2×2 grid should scroll horizontally rather than overflow).
- **⛔ Write note:** None — overview is read-only.

---

### [QAL-02] New Code / Overall Toggle — Data Switch

- **Component(s):** `QualityDashboardPanel.newCodeButton`; `overallButton` (`QualityDashboardPanel.kt:136–145`); `updateToggleAppearance` (`QualityDashboardPanel.kt:352–368`)
- **Preconditions:** QAL-01 passed.
- **Steps:**
  1. Verify initial state: "New Code" button is highlighted (blue background), "Overall" is grey.
  2. Click **Overall**.
  3. Observe button appearance swap and data change.
  4. Click **New Code** to restore.
- **Expected — visual:** Active button: `background = StatusColors.LINK` (blue), `foreground = white` (light) / `0x1e1e2e` (dark). Inactive button: `background = 0xE0E0E0` / `0x45475a`, `foreground = StatusColors.SECONDARY_TEXT`. Coverage % in the header label changes between new-code and overall values. Issue count changes to reflect only new-code issues.
- **Expected — behavioral:** `headerLabel` rebuilds to show the correct mode label: `"NEW CODE"` or `"OVERALL"` (`QualityDashboardPanel.kt:434`). Coverage and issue sub-tab data reload to match selected mode.
- **✅ Checks (tick each):**
  - [ ] "New Code" highlighted on load (initial state)
  - [ ] Clicking "Overall" swaps button colors correctly
  - [ ] Header label changes from "NEW CODE" to "OVERALL"
  - [ ] Coverage % in header changes between modes
  - [ ] Issue count in header changes between modes
  - [ ] Clicking "New Code" restores initial state
- **🐞 Bug signals:** Both buttons same color after switching; header label doesn't change mode text; coverage/issue counts identical in both modes (data not filtered); button click has no visible effect.
- **Theme/size matrix:** light + dark (button colors must contrast with both backgrounds).
- **⛔ Write note:** None — toggle is read-only.

---

### [QAL-03] Gate Status Banner — "Show Blocking Issues" Cross-Tab Navigation

- **Component(s):** `GateStatusBanner` (`GateStatusBanner.kt`); `showIssuesLink`; `onShowBlockingIssues` callback
- **Preconditions:** The project has a FAILED quality gate (gate status = FAILED). The banner should be visible between the header row and the sub-tabs.
- **Steps:**
  1. Open Quality tab. Verify the red banner is visible.
  2. Read the banner text: failing condition(s) must be listed (e.g. "New Coverage: 68.2% (threshold: 80%)").
  3. Note whether `caycStatus == "non-compliant"` is appended.
  4. Click **"Show Blocking Issues"** link.
  5. Observe which sub-tab is now selected and whether any filter is pre-applied.
- **Expected — visual:** Banner: red background (`0xFDE7E9` / `0x4A1A1A`), ⚠ icon + "Quality Gate Failed" title (bold red), conditions text, "Show Blocking Issues" link (blue underline, hand cursor). Banner is `isVisible = false` when gate PASSES.
- **Expected — behavioral:** Clicking the link invokes `onShowBlockingIssues`. If the failing condition is coverage/duplication → Coverage sub-tab selected. If failing condition is bugs/vulnerabilities/hotspots → Issues sub-tab selected with a pre-applied type filter (`GateStatusBanner.kt:111–126`). New-code mode set to `true` if metric name starts with `new_`.
- **✅ Checks (tick each):**
  - [ ] Banner visible only when gate is FAILED
  - [ ] At least one failing condition listed (e.g. "New Coverage: X% (threshold: Y%)")
  - [ ] "Show Blocking Issues" link has hand cursor
  - [ ] Clicking link navigates to Coverage tab when coverage condition fails
  - [ ] Clicking link navigates to Issues tab (with Bug/Vulnerability filter) when issue condition fails
  - [ ] Banner absent when gate PASSES
- **🐞 Bug signals:** Banner visible even when gate passes; banner text blank (no conditions listed); "Show Blocking Issues" click does nothing; wrong tab selected after click; filter not applied after navigation.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None — banner navigation is read-only.

---

### [QAL-04] Issue List — Filter Combos, Count Label, Pagination Warning

- **Component(s):** `IssueListPanel.filterCombo`; `severityCombo`; `countLabel`; `paginationWarning` (`IssueListPanel.kt:39–50`, `260–287`)
- **Preconditions:** QAL-01 passed. The project has > 0 issues of mixed types and severities.
- **Steps:**
  1. Switch to the **Issues** sub-tab. Observe default filter ("All" type, "All" severity) and count label.
  2. Select **Bug** in the type combo. Observe count label and list.
  3. Select **Critical** in the severity combo. Observe "Showing X of Y items" format.
  4. Reset both combos to "All". Verify full list returns.
  5. If total issue count > list size, verify the pagination warning strip is visible.
- **Expected — visual:** Count label: when no filter active: "{N} item(s)". When filters active: "Showing {n} of {total} items" (`IssueListPanel.kt:262–265`). Pagination warning (if applicable): "⚠ Showing first {N} of {M} issues. More exist on the server." in amber italic font.
- **Expected — behavioral:** Filter combos are instantaneous (no network call — filters locally from `allIssues` list). List updates with the current filtered + sorted set.
- **✅ Checks (tick each):**
  - [ ] Default count label shows total item count
  - [ ] Selecting type filter reduces visible rows
  - [ ] Selecting severity filter further reduces visible rows
  - [ ] "Showing X of Y" format shown when filters active
  - [ ] Resetting both combos shows full unfiltered list
  - [ ] Pagination warning visible when server has more issues than loaded
- **🐞 Bug signals:** Count label blank; count doesn't change when filter applied; "Showing X of Y" format shown even without filters; pagination warning always visible even when all issues loaded; filter combo selection causes a network round-trip.
- **Theme/size matrix:** light + dark; narrow window — filter row must not overflow off-screen.
- **⛔ Write note:** None — filtering is read-only.

---

### [QAL-05] Issue List Cell Rendering — Severity Badge, Impact Chip, Detail Line

- **Component(s):** `IssueListCellRenderer` (`IssueListPanel.kt:399–600`); severity accent border; `ImpactRendering` badge; detail line (effort + relative time)
- **Preconditions:** QAL-01 passed. Issues of multiple severities loaded, including BLOCKER/CRITICAL (red), MAJOR/MINOR (yellow), INFO (grey).
- **Steps:**
  1. Scroll through the Issues list observing rows.
  2. Find a BLOCKER or CRITICAL issue row. Verify appearance.
  3. Find a MAJOR issue row.
  4. Find a SECURITY HOTSPOT row (if present).
  5. Hover over a row to see its tooltip.
  6. Click a row to select it; verify selection background is applied.
- **Expected — visual:**
  - BLOCKER/CRITICAL: 2 px red left accent border, `[BLOCKER]` or `[CRITICAL]` badge in red bold.
  - MAJOR/MINOR: yellow left accent border, yellow badge.
  - INFO: grey left accent border, grey badge.
  - Impact chip (Sonar 9.6+): `[SECURITY · HIGH]` appended in severity-appropriate color.
  - Detail line: effort ("30min to fix") and relative time ("2 hours ago") in SECONDARY_TEXT.
  - HOTSPOT rows: "SECURITY HOTSPOT [HIGH]" (red) or "[MEDIUM]" (yellow), probability badge, review status in detail line.
  - Tooltip: `"[rule-key] message — filePath:line | Effort: Xmin | Status: OPEN"`.
- **Expected — behavioral:** Selected row: selection background replaces card background; text foreground uses `list.selectionForeground` for all labels.
- **✅ Checks (tick each):**
  - [ ] BLOCKER/CRITICAL rows have red left border + red badge
  - [ ] MAJOR rows have yellow left border + yellow badge
  - [ ] Detail line shows effort and relative time
  - [ ] HOTSPOT rows labeled "SECURITY HOTSPOT" (not "CODE_SMELL")
  - [ ] Tooltip shows rule key + message + file path + line
  - [ ] Selected row has list selection background
- **🐞 Bug signals:** All rows same grey border regardless of severity; badges not colored; HTML `<b>` tags visible in rendered text (unescaped HTML); detail line blank; tooltip empty.
- **Theme/size matrix:** light + dark; long message text — verify it doesn't cause row height inflation or clip.
- **⛔ Write note:** None — list rendering is read-only.

---

### [QAL-06] Issue Selection → IssueDetailPanel (Code Snippet, Rule Info, Navigation)

- **Component(s):** `IssueDetailPanel` (`IssueDetailPanel.kt`); `titleLabel`; `metadataLabel`; `codeArea`; `ruleInfoLabel`; `openInEditorButton`; `prevButton`/`nextButton`
- **Preconditions:** QAL-05 passed. At least one issue is visible in the list.
- **Steps:**
  1. Click an issue row in the list. Observe the right side of the splitter (detail panel).
  2. Verify title label color matches severity.
  3. Verify metadata line (file:line, effort, age, rule key).
  4. Verify code snippet area loads (monospace font, shows lines around the issue line, ">>>" marker on the issue line).
  5. Wait for rule info to load below the code area.
  6. Click **"Open in Editor"** button.
  7. Click **▶ Next** then **◀ Prev** navigation buttons.
  8. For an issue with Clean Code taxonomy (Sonar 9.6+): verify the `cleanCodeLabel` shows the breadcrumb.
- **Expected — visual:** Title: `"[SEVERITY] TYPE — message"` with severity color. Metadata: `"filePath:line • Effort: Xmin • N hours ago • rule-key"`. Code area: JetBrains Mono 12pt, `">>>  42 | public void foo() {"` on the issue line, normal lines prefixed with `"    "`. Rule info: `"<RuleName> • Remediation: …\n<description>"`. Clean Code (if present): `"Clean Code: CATEGORY → ATTRIBUTE  •  QUALITY · SEVERITY"` above metadata.
- **Expected — behavioral:** "Open in Editor" navigates to the file at the reported line. Next/Prev buttons move selection in the list to the adjacent item.
- **✅ Checks (tick each):**
  - [ ] Title color matches severity (red for CRITICAL, yellow for MAJOR)
  - [ ] Metadata includes file path, line, rule key
  - [ ] Code area shows ">>>" on issue line and context lines above/below
  - [ ] Rule info loads with rule name and description
  - [ ] "Open in Editor" opens the correct file at the issue line
  - [ ] Next/Prev change the selected issue in the list
  - [ ] Empty state ("Select an issue to view details") shown before any selection
- **🐞 Bug signals:** Detail panel stays blank after selection; code area shows "File not found"; rule info shows "Could not load rule info" always; "Open in Editor" does nothing; title color wrong (all same color); Next/Prev do nothing.
- **Theme/size matrix:** light + dark; code area must show monospace font (not proportional); narrow detail panel — scrollable.
- **⛔ Write note:** None — viewing and navigating to an issue is read-only.

---

### [QAL-07] Coverage Table Panel — Search, Sort, Columns, Row Selection

- **Component(s):** `CoverageTablePanel` (`CoverageTablePanel.kt`); `searchField`; `table`; `sortRow`; `summaryLabel`; `paginationWarning`
- **Preconditions:** QAL-01 passed. At least 5 files have coverage data. Click the **Coverage** sub-tab.
- **Steps:**
  1. Observe the table: verify 7 columns in Overall mode ("FILE", "LINE %", "BRANCH %", "UNCOVERED LINES", "UNCOVERED COND.", "COMPLEXITY", "COGNITIVE").
  2. Observe the summary bar (e.g. "12 files | 74.5% avg coverage | 3 files below 80%").
  3. Click the "LINE %" column header to sort descending; click again to sort ascending.
  4. Type a partial file name in the search field (wait 300 ms debounce). Observe filtered list.
  5. Clear the search field; verify full list restored.
  6. Click a row. Observe the `CoveragePreviewPanel` in the lower pane.
  7. Switch to "New Code" mode (via the toggle at the top) and return to Coverage tab.
- **Expected — visual:** Uppercase bold table headers (`CoverageTableModel.kt:395–396`). Summary bar below headers. Complexity column: values > 20 orange, > 50 red. Cognitive column: > 15 orange, > 25 red. Search 300 ms after last keystroke (no immediate filter on each keypress). New Code mode: columns change to "NEW COVERAGE %", "NEW BRANCH %", "NEW UNCOV. LINES", "NEW LINES" (columns 1–4 renamed).
- **Expected — behavioral:** Row click populates the CoveragePreviewPanel without resetting the search (if a search is active, selected file must still be visible). Double-click opens file in editor.
- **✅ Checks (tick each):**
  - [ ] 7 columns present in Overall mode; correct headers (uppercase)
  - [ ] Summary bar shows file count, avg coverage, files below threshold
  - [ ] Column sort works (click header cycles asc/desc)
  - [ ] Search filter reduces rows (with 300 ms debounce)
  - [ ] Clear search restores full list
  - [ ] Single-click row populates preview panel below
  - [ ] New Code mode changes column 1–4 headers
- **🐞 Bug signals:** Table has < 7 columns; "COMPLEXITY" column values all 0; search field filters immediately on keypress (no debounce); search clears preview panel even when filtered-in file is selected; New Code mode columns same as Overall.
- **Theme/size matrix:** light + dark (complexity color thresholds must show on both backgrounds); narrow window — horizontal scrollbar appears rather than column truncation.
- **⛔ Write note:** None — coverage browsing is read-only.

---

### [QAL-08] CoveragePreviewPanel — Metrics Header, Uncovered Line Markers, "Open in Editor"

- **Component(s):** `CoveragePreviewPanel` (`CoveragePreviewPanel.kt`); `metricsLabel`; `codeArea`; `filePathLabel`; `openInEditorButton`
- **Preconditions:** QAL-07 passed. A file row is selected in the coverage table.
- **Steps:**
  1. With a file selected in the Coverage table, observe the lower pane.
  2. Read the metrics header label.
  3. Scroll through the code area (monospace font, line numbers, "!" markers for uncovered lines).
  4. Click **"Open in Editor"** button.
- **Expected — visual:** Metrics header: `"Line: 74.3% | Branch: 68.1% | Uncovered: 14 lines, 6 conds | Complexity: 12"` (format from `CoveragePreviewPanel.kt:79–86`). Code area: JetBrains Mono 11pt. Lines with `LineCoverageStatus.UNCOVERED` prefixed with `"  !"` (two spaces + exclamation mark). `filePathLabel` shows Sonar-relative path in grey.
- **Expected — behavioral:** "Open in Editor" navigates to line 1 of the file. Empty state shows "Select a file to preview coverage" when no row selected.
- **✅ Checks (tick each):**
  - [ ] Metrics label shows line coverage, branch coverage, uncovered count
  - [ ] Complexity shown if > 0
  - [ ] "!" prefix on uncovered lines in code area
  - [ ] File path label shows relative path
  - [ ] "Open in Editor" button navigates to the file
  - [ ] Empty state shown before any selection
- **🐞 Bug signals:** Code area shows "File not found"; metrics header blank; uncovered markers absent; "Open in Editor" disabled or does nothing; preview pane stays empty after row selection.
- **Theme/size matrix:** light + dark; tall preview pane — code area must be scrollable.
- **⛔ Write note:** None — coverage preview is read-only.

---

### [QAL-09] Branch Warning and Analysis Status Labels

- **Component(s):** `QualityDashboardPanel.branchInfoLabel`; `branchWarningLabel`; `analysisStatusLabel`; `analysisPermissionHintLabel`; `newCodePeriodLabel` (`QualityDashboardPanel.kt:83–113`)
- **Preconditions:** Test three sub-cases: (a) branch analyzed; (b) branch NOT analyzed; (c) analysis history forbidden (non-admin token).
- **Steps:**
  1. (a) Load Quality tab for a branch that has been analyzed. Observe `branchInfoLabel`.
  2. (b) Check out a branch that has not been analyzed in Sonar. Observe `branchWarningLabel` visibility.
  3. (c) Verify `analysisPermissionHintLabel` visibility (shown only when `analysisHistoryForbidden = true` AND no `lastAnalysisForBranch`).
  4. Hover over `branchInfoLabel` tooltip to see list of analyzed branches.
- **Expected — visual:**
  - (a) `branchInfoLabel`: "Branch: {name} — Last analyzed: 2026-06-24 14:30" (formatted `yyyy-MM-dd HH:mm`). `branchWarningLabel.isVisible = false`.
  - (b) `branchInfoLabel` in `StatusColors.WARNING` foreground. `branchWarningLabel`: "⚠ This branch has not been analyzed by SonarQube. Data shown is from the last available analysis."
  - (c) `analysisPermissionHintLabel`: "Analysis history hidden — token lacks Administer Project permission. Open Sonar permissions" (with link).
  - `newCodePeriodLabel`: "New code: last 30 days (inherited)" or similar human-readable period.
- **Expected — behavioral:** `analysisPermissionHintLabel` click → opens browser to the Sonar project permissions page.
- **✅ Checks (tick each):**
  - [ ] Analyzed branch: `branchInfoLabel` shows name + formatted date
  - [ ] Unanalyzed branch: warning label visible with ⚠ text, `branchInfoLabel` in warning color
  - [ ] `newCodePeriodLabel` visible and describes period in plain English
  - [ ] `analysisPermissionHintLabel` visible only when permission is missing
  - [ ] `branchInfoLabel` tooltip lists all analyzed branches (if `state.branches` non-empty)
- **🐞 Bug signals:** `branchWarningLabel` always visible regardless of analysis status; date format shows raw ISO string; `newCodePeriodLabel` shows "REFERENCE_BRANCH" literal instead of human-readable; permission hint always visible.
- **Theme/size matrix:** light + dark; long branch name — label truncates gracefully.
- **⛔ Write note:** None — these are read-only status labels.

---

### [QAL-10] No PR / Project Key Not Configured — Hint States

- **Component(s):** `QualityDashboardPanel.qualityHintLabel`; `showQualityHint()` (`QualityDashboardPanel.kt:660–666`); `tabbedPane.isVisible`
- **Preconditions:** Two sub-cases: (a) No PR selected in the PR tab; (b) A PR is focused but its `sonarProjectKey` is blank in Settings.
- **Steps:**
  1. (a) Clear the focused PR (click a non-PR branch or deselect in PR tab). Switch to Quality tab.
  2. (b) Clear the `sonarProjectKey` in Settings for the active repo. Switch to Quality tab.
- **Expected — visual:** `qualityHintLabel` visible, italic, grey text. Sub-tabbed pane hidden (`tabbedPane.isVisible = false`). loadingIcon hidden.
  - (a): "No PR selected for {repoName} — select one in the PR tab"
  - (b): "SonarQube project key not configured for {repoName} — configure in Settings > CI/CD"
- **Expected — behavioral:** Selecting a valid PR in the PR tab while on Quality tab triggers a refresh and hides the hint.
- **✅ Checks (tick each):**
  - [ ] `qualityHintLabel` visible with italic text in both sub-cases
  - [ ] Sub-tabs hidden (Overview/Issues/Coverage tabs not visible)
  - [ ] Different hint messages for the two sub-cases
  - [ ] Hint disappears and tabs appear after a valid PR is focused
- **🐞 Bug signals:** Sub-tabs visible with empty data when hint should be shown; hint text blank; loadingIcon spinning when hint shown; hint doesn't disappear when PR is selected.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None — hint display is read-only.

---

### [QAL-11] In-editor Sonar issue annotation + AI fix IntentionAction

- **Component(s):** `SonarIssueAnnotator` (`SonarIssueAnnotator.kt` — `ExternalAnnotator`; inline squiggle/gutter annotation, tooltip = `[{rule}] {message}`, gated at `SonarIssueAnnotator.kt:63` on `sonarInlineAnnotationsEnabled`); `SonarFixIntentionAction` (`SonarFixIntentionAction.kt:17` → `getText` = "Fix with AI Agent (Workflow)", `getFamilyName` = "Workflow Orchestrator"; `isAvailable` gated on `sonarIntentionActionEnabled` + `LlmBrainFactory.isAvailable()` + a Sonar issue at caret); settings on `CodeQualityConfigurable.kt` → "Editor Integration" group. (Distinct from the coverage gutter dots in CORE-29.)
- **Preconditions:**
  - SonarQube configured and a project/branch analysis available so issues are loaded for the open repo.
  - **Both editor-integration toggles enabled first** (default OFF): Settings → Tools > Workflow Orchestrator → **Code Quality** → "Editor Integration" → check **"Enable Sonar inline annotations in editor"** (`sonarInlineAnnotationsEnabled`) and **"Enable Sonar AI quick-fix intention action"** (`sonarIntentionActionEnabled`). An AI Agent connection must be available (the intention's `isAvailable` also requires `LlmBrainFactory.isAvailable()`).
  - Open a project source file that has a **known Sonar issue** on a specific line.
- **Steps:**
  1. Open the source file with the known Sonar issue. Wait for analysis to apply (a restart/refresh may be needed — see `SonarDataService.kt:207`).
  2. Locate the inline **squiggle / gutter annotation** on the affected line.
  3. **Hover** over the annotated range and read the tooltip.
  4. Place the caret on the annotated line and press **Alt+Enter** to open the intention/quick-fix popup.
  5. Read the available intention labels — **do NOT select/apply any** (see ⛔ note). Press **Escape** to dismiss.
- **Expected — visual:** Inline annotation (squiggle, severity-colored — ERROR red / WARNING / WEAK_WARNING per `mapSeverity`) on the issue line. Hover tooltip text begins with `[{rule}] {message}`. Alt+Enter popup lists an intention labeled **"Fix with AI Agent (Workflow)"** grouped under family **"Workflow Orchestrator"**.
- **Expected — behavioral:** With either toggle OFF, the corresponding affordance is absent (no annotation, or no intention in the Alt+Enter list). With both ON and an issue at the caret, the intention is offered. Dismissing with Escape applies nothing.
- **✅ Checks (tick each):**
  - [ ] Inline squiggle/gutter annotation appears on the issue line
  - [ ] Hover tooltip shows `[{rule}] {message}`
  - [ ] Annotation severity color matches the issue type/severity
  - [ ] Alt+Enter lists the "Fix with AI Agent (Workflow)" intention with correct label
  - [ ] Intention is grouped under "Workflow Orchestrator" family
  - [ ] Escape dismisses without applying anything
- **🐞 Bug signals:** No annotation despite the toggle ON and a loaded issue; tooltip missing the rule key or message; intention absent when an issue is at the caret (and toggle ON + agent available); intention mislabeled; intention shown on lines with no issue.
- **Theme/size matrix:** light + dark (squiggle/gutter colors must read in both).
- **⛔ Write note:** **Applying the fix EDITS the source file** — `SonarFixIntentionAction.invoke` hands a fix prompt to the AI Agent, which can then modify the file. Verify the intention **appears and its label only**, then press **Escape**; do **NOT** apply it. If you must exercise the apply path, do so only against a throwaway scratch file.

---

## Automation

Primary files:
- `automation/src/main/kotlin/…/automation/ui/AutomationPanel.kt`
- `automation/src/main/kotlin/…/automation/ui/TagStagingPanel.kt`
- `automation/src/main/kotlin/…/automation/ui/QueueStatusPanel.kt`
- `automation/src/main/kotlin/…/automation/ui/MonitorPanel.kt`
- `automation/src/main/kotlin/…/automation/ui/SuiteConfigPanel.kt`

---

### [AUT-01] Suite Dropdown Load + Baseline Diagnostic Banner

- **Component(s):** `AutomationPanel.suiteCombo`; `diagnosticPanel`; `baselineInfoLabel`; `statusLabel` (`AutomationPanel.kt:77–78`, `84–130`)
- **Preconditions:** At least one suite configured in Settings > Automation. Bamboo accessible.
- **Steps:**
  1. Open the Automation tab.
  2. Observe the suite combo: items should be sorted alphabetically by display name.
  3. Wait for baseline to load (up to 15 s) — observe `diagnosticPanel` and `statusLabel`.
  4. Observe `baselineInfoLabel`: success or failure text.
  5. Change suite selection in `suiteCombo` to a second suite.
- **Expected — visual:**
  - Suite combo items: alphabetically sorted (`AutomationPanel.kt:401`). Canonical Bamboo shortName shown (not long-form "Project — Plan") after background fetch.
  - On success: `baselineInfoLabel` reads "✓ Baseline: build #{N} (X/Y release tags, score Z)" in green.
  - On failure: "✗ {diagnostic text}" in red.
  - `statusLabel`: "● Idle" in green, or amber warning text.
  - Switching suite: `diagnosticPanel` stays with previous suite's baseline (sticky, no rescan). Status: "" (cleared).
- **Expected — behavioral:** Suite switch does NOT trigger a Bamboo rescan (baseline is sticky until Refresh is clicked). `branchCombo` repopulates for the new suite's plan.
- **✅ Checks (tick each):**
  - [ ] Suite items sorted alphabetically
  - [ ] Canonical shortName shown (not "Project — Plan" long form)
  - [ ] `diagnosticPanel` visible after initial load
  - [ ] Baseline info shows build number + release tag fraction + score
  - [ ] Status dot green when baseline found
  - [ ] Suite switch does not re-trigger scan (sticky baseline)
- **🐞 Bug signals:** Suite combo empty; items in arbitrary order; long-form "Project — Plan" names instead of shortName; `diagnosticPanel` stays hidden after load; baseline info blank; suite switch clears baseline and shows loading.
- **Theme/size matrix:** light + dark; many suites (> 5) — combo scrollable.
- **⛔ Write note:** None — suite selection and baseline display are read-only.

---

### [AUT-02] Branch Dropdown — Default, Alternatives, Disabled Branch Dialog

- **Component(s):** `AutomationPanel.branchCombo`; `BranchComboItem.enabled`; `onDisabledBranchSelected` dialog (`AutomationPanel.kt:150–185`, `1070–1128`)
- **Preconditions:** The selected suite's plan has at least one non-default branch in Bamboo. At least one branch should be disabled (greyed) if possible.
- **Steps:**
  1. Open Automation tab with a suite selected. Observe `branchCombo`.
  2. Verify "default" is pinned first in the list.
  3. Click the dropdown and type a partial branch name (speed-search).
  4. Select an **enabled** non-default branch. Verify it persists when switching away and back.
  5. If a **disabled** branch exists: select it. Observe the Yes/No dialog.
  6. Click **No** (cancel enabling). Verify combo reverts to the previously committed selection.
- **Expected — visual:**
  - Combo closed: caps at `ComboBoxWidth.DEFAULT` with truncation tooltip on hover.
  - Combo open: expands to `ComboBoxWidth.WIDE`; disabled branches rendered in grey with " (disabled)" suffix (`AutomationPanel.kt:163–167`).
  - Disabled branch dialog: title "Branch Disabled", message mentioning the branch plan key and offering to enable it in Bamboo. Buttons: "Enable" / "Cancel".
- **Expected — behavioral:** Selecting an enabled branch commits immediately and persists per-suite across IDE restarts. Selecting a disabled branch only shows the dialog on **commit** (dropdown close or focus-loss), not mid-keystroke during speed-search.
- **✅ Checks (tick each):**
  - [ ] "default" always at index 0
  - [ ] Branches sorted alphabetically after "default"
  - [ ] Disabled branches grey + "(disabled)" suffix
  - [ ] Speed-search filters by branch label
  - [ ] Enabled branch selection persists (sticky per suite)
  - [ ] Disabled branch: dialog appears with "Enable" / "Cancel"
  - [ ] Clicking "Cancel" on the dialog reverts combo to previous selection
- **🐞 Bug signals:** Branches in arbitrary order; disabled branches not visually distinct; dialog pops on every keystroke during speed-search; "Cancel" click doesn't revert combo; "default" not at position 0.
- **Theme/size matrix:** light + dark; long branch names — closed combo shows ellipsis + full-name tooltip.
- **⛔ Write note:** **Enable disabled branch** (write op: calls `BambooService.enablePlanBranch`) — verify the dialog appears with correct branch key, then **click Cancel**.

---

### [AUT-03] TagStagingPanel — Table Display, Copy, Paste, Revert

- **Component(s):** `TagStagingPanel` (`TagStagingPanel.kt`); `table`; `copyButton`; `pasteButton`; `revertButton`
- **Preconditions:** AUT-01 passed. A baseline was loaded successfully (table has rows). Configure tab is visible.
- **Steps:**
  1. Observe the tag table: columns SERVICE / TAG / SOURCE (uppercase bold headers).
  2. Verify "Sent to Bamboo as build variable: {variableName}" label below the "DOCKER TAGS" header.
  3. Click **Copy**. Verify system clipboard contains valid JSON.
  4. Manually edit a tag value in the TAG column (double-click cell). Verify **Revert** becomes enabled.
  5. Click **Revert**. Verify table reverts to the baseline snapshot.
  6. Click **Paste** with a valid JSON string `{"service-a": "1.2.3"}` in clipboard. Verify table updates.
  7. Click **Paste** with invalid text in clipboard. Verify a warning dialog ("Paste Failed") appears.
- **Expected — visual:** Table headers: uppercase bold `SECONDARY_TEXT` color. Row height 28px. No grid lines. SOURCE column shows the tag source (e.g. "BASELINE", "OVERRIDE", "FEATURE"). `revertButton.isEnabled = false` initially; `true` after any edit.
- **Expected — behavioral:** Copy puts a `{"service": "tag"}` JSON string on the clipboard. Paste with valid JSON replaces the table (does NOT update the baseline snapshot — only `setBaseline` does). Paste with invalid text shows `Messages.showWarningDialog` but does NOT change the table.
- **✅ Checks (tick each):**
  - [ ] Table has 3 columns: SERVICE, TAG, SOURCE (uppercase)
  - [ ] Build variable name label visible below "DOCKER TAGS"
  - [ ] Copy puts JSON string on clipboard
  - [ ] Revert button disabled before any edit
  - [ ] Revert restores values to baseline after cell edit
  - [ ] Paste with valid JSON updates the table
  - [ ] Paste with invalid JSON shows "Paste Failed" warning dialog
- **🐞 Bug signals:** Table empty (no rows) even when baseline loaded; headers lowercase; Revert always disabled; Paste with invalid JSON silently fails (no dialog); Copy puts invalid JSON on clipboard.
- **Theme/size matrix:** light + dark; long service names (> 30 chars) — verify cell truncation or column resize.
- **⛔ Write note:** None — Copy/Paste/Revert operate on local staging data only, not Bamboo state.

---

### [AUT-04] Baseline Picker Dropdown (2+ Parseable Builds)

- **Component(s):** `AutomationPanel.baselinePickerRow`; `baselineCombo` (`AutomationPanel.kt:102–123`, `843–861`)
- **Preconditions:** The suite's Bamboo plan has at least 2 recent builds that each contain parseable `dockerTagsAsJson`. The `baselinePickerRow` must be visible.
- **Steps:**
  1. Open Automation tab with a suitable suite. Verify `baselinePickerRow` is visible (only when ≥ 2 alternatives).
  2. Observe the `baselineCombo` entries (format: "#847 — 3/5 release tags, score 35").
  3. Auto-picked build should be at index 0.
  4. Select a **different** build entry (not the auto-picked one).
  5. Observe the tag staging table updating to that build's tags.
- **Expected — visual:** `baselinePickerRow` label: "Baseline:" in SECONDARY_TEXT. Combo entry format: `"#{N} — {releaseTagCount}/{totalServices} release tags, score {score}"` (`BaselinePickerItem.toString()`, `AutomationPanel.kt:1161–1163`). Auto-picked build is first entry.
- **Expected — behavioral:** Selecting a different build calls `tagStagingPanel.setBaseline(...)` with that build's tags. Tag table updates to show the chosen build's values. `baselinePickerRow` hidden when < 2 alternatives.
- **✅ Checks (tick each):**
  - [ ] `baselinePickerRow` visible only when ≥ 2 parseable builds
  - [ ] Combo entries show build number, tag count, and score
  - [ ] Auto-picked build at index 0
  - [ ] Selecting alternative build updates tag table
  - [ ] `baselinePickerRow` hidden when only 1 baseline alternative
- **🐞 Bug signals:** `baselinePickerRow` always hidden; combo entries show plan key instead of build number; selecting alternative doesn't update tag table; auto-picked build not at index 0.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None — picker swaps local staging data only.

---

### [AUT-05] Docker Tag Detection Banner States

- **Component(s):** `AutomationPanel.dockerTagInfoLabel` (`AutomationPanel.kt:87–90`); `updateDockerTagBanner` (`AutomationPanel.kt:823–836`)
- **Preconditions:** AUT-01 passed. Test three states: (a) no build context (no focused PR); (b) CI build succeeded with docker tag in log; (c) CI build failed.
- **Steps:**
  1. (a) Clear focused PR. Open Automation tab. Observe `dockerTagInfoLabel`.
  2. (b) Focus a PR whose build has succeeded and the log contains a docker tag marker. Observe `dockerTagInfoLabel`.
  3. (c) Focus a PR whose build has FAILED. Observe `dockerTagInfoLabel`.
- **Expected — visual:**
  - (a): "⏳ Waiting for CI build…" in `StatusColors.INFO`.
  - (b): "✓ Docker tag: {tag} (from {resultKey})" in `StatusColors.SUCCESS`.
  - (c): "✗ CI build failed: {planKey} #{N}" in `StatusColors.ERROR`.
  - (Other failures): "⚠ {reason}" in `StatusColors.WARNING`.
- **Expected — behavioral:** On state (b), the tag for the "current repo" service is automatically overlaid in the staging table (the entry matching `dockerTagKey` setting has its TAG column updated to the detected feature-branch tag).
- **✅ Checks (tick each):**
  - [ ] "⏳" text for no build context
  - [ ] "✓" green text with detected tag + result key
  - [ ] "✗" red text for build failure
  - [ ] "⚠" amber text for log parse failure (tag not found in log)
  - [ ] Staging table tag updated when tag detected
- **🐞 Bug signals:** `dockerTagInfoLabel` always blank; success state shows "⚠" symbol; staging table NOT updated when tag detected; "✓" shown for a failed build.
- **Theme/size matrix:** light + dark; long docker tag strings — label wraps or scrolls, not clipped.
- **⛔ Write note:** None — tag detection display is read-only.

---

### [AUT-06] Trigger Split-Button — Customized Dialog and Payload Preview

- **Component(s):** `AutomationPanel.buildTriggerSplitButton` (`AutomationPanel.kt:873–901`); `ManualStageDialog`; `onTriggerCustomized` (`AutomationPanel.kt:951–985`)
- **Preconditions:** AUT-01 passed. A suite is selected with valid tags in the staging table.
- **Steps:**
  1. Click the **▾** (arrow) button to open the split-button popup.
  2. Click **"Trigger Customized…"** menu item.
  3. Observe `ManualStageDialog`: stage checkboxes, variable preview section, "Save as default for this suite" checkbox. **Inspect the payload preview** (variable preview with `dockerTagsAsJson` key + JSON value) and confirm it is correct — this is your read-only way to verify the trigger payload.
  4. Click **Cancel** without selecting stages.
  5. Click **▾** again and **verify the "Trigger All Stages" menu item is present and correctly labeled — do NOT click it.** Clicking it enqueues a build immediately with no confirmation dialog (see ⛔ note). Press Escape to dismiss the popup.
- **Expected — visual:** Popup menu has 2 items: "Trigger Customized…" and "Trigger All Stages". Dialog shows: plan stage list (checkboxes), variable preview with `dockerTagsAsJson` key + JSON value, "Save as default" checkbox.
- **Expected — behavioral:**
  - "Trigger Customized…" → opens dialog; Cancel → no enqueue.
  - "Trigger All Stages" → (NOT exercised in this read-only run) would call `enqueueWith(stages = null)` **immediately with no confirmation dialog** → Monitor tab selected, `statusLabel` reads "⟳ Queued" (`AutomationPanel.kt:1019–1021`). Verify only that the menu item is present and labeled correctly.
  - Primary "Trigger" button: uses saved default stages if configured, else opens dialog.
- **✅ Checks (tick each):**
  - [ ] Arrow button opens popup with 2 items
  - [ ] "Trigger Customized…" opens dialog with stage list
  - [ ] Variable preview (payload preview) in dialog includes `dockerTagsAsJson` and shows correct JSON
  - [ ] Cancelling dialog → no entry in Monitor list
  - [ ] "Trigger All Stages" menu item present and correctly labeled (verified WITHOUT clicking)
  - [ ] Primary "Trigger" button opens dialog when no default saved
- **🐞 Bug signals:** Arrow button does nothing; popup has wrong items; dialog blank (no stages loaded); variable preview missing; "Trigger All Stages" menu item missing or mislabeled.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** **Trigger Customized** opens a dialog you can safely **Cancel** (no enqueue until you confirm). **Trigger All Stages has NO such safety**: clicking it calls `enqueueWith(stages = null)` **immediately, with no abort/confirmation dialog**, dispatching a real build run on Bamboo. Therefore do **not** click it — verify presence + label only. If a designated **safe test plan key** is in use and it is triggered anyway, the only recovery is to **immediately right-click the new Monitor entry → Remove before the poller dispatches** it to Bamboo.

---

### [AUT-07] Monitor Panel — Filter Chips, List, Detail, QueueStatusPanel

- **Component(s):** `MonitorPanel` (`MonitorPanel.kt`); filter chip toggles; `runListModel`; `detailPanel`; `QueueStatusPanel` (`QueueStatusPanel.kt`)
- **Preconditions:** AUT-06 has been run at least once to create a queue entry (even if cancelled before Bamboo trigger). Alternatively, queue entries may have been created in a previous session and persist via `TagHistoryService`.
- **Steps:**
  1. Click the **Monitor** sub-tab of the Automation panel.
  2. Observe the filter chip row (All / Queued / Running / Failed / Completed).
  3. Click each chip to verify filtering: "Queued" shows only WAITING_LOCAL / QUEUED_ON_BAMBOO entries.
  4. Click a row in the run list. Observe the detail pane on the right.
  5. Observe `QueueStatusPanel` (QUEUE STATUS header, colored dot, text) at the top of the Monitor tab.
  6. Right-click a row — verify context menu appears.
- **Expected — visual:**
  - Filter chips: radio-style, "All" selected by default. Selected chip visually differentiated from others.
  - Run list: sorted latest-first. Each row shows suite name, status badge, build number (if available).
  - Detail pane: suite name + plan key + status + stage list (if RUNNING/COMPLETED) + test summary (if available) + Bamboo URL link + Cancel (live entries) or Remove (terminal entries).
  - `QueueStatusPanel`: colored dot + text:
    - Empty queue: grey dot + "Queue idle."
    - Running: green dot + "Running — {suite} #{N}"
    - WAITING_LOCAL: grey/blue dot + "Waiting (local)"
  - Right-click context menu: "Cancel" for live entries, "Remove" for terminal entries.
- **Expected — behavioral:** Filter chips update list without network calls. Selecting a row in the run list drives `QueueStatusPanel` to mirror that selection.
- **✅ Checks (tick each):**
  - [ ] "All" chip selected by default; all entries visible
  - [ ] Filter chips reduce visible entries correctly
  - [ ] Entries sorted latest-first by enqueue time
  - [ ] Selecting a row populates detail pane
  - [ ] `QueueStatusPanel` dot changes color per active status
  - [ ] Right-click context menu appears
  - [ ] Terminal entries (Completed/Failed) show "Remove" not "Cancel"
- **🐞 Bug signals:** Monitor list empty even with prior queue entries; filter chips all deselected simultaneously; entries in arbitrary order; detail pane stays blank after row selection; `QueueStatusPanel` always shows "Queue idle." regardless of entries; right-click has no menu.
- **Theme/size matrix:** light + dark; long suite names — truncate in list cell.
- **⛔ Write note:** **Cancel** (on a live RUNNING entry) and **Remove** (on a terminal entry) are write ops — verify the button is present with correct label, then **click elsewhere** or use Escape without invoking the action.

---

## Handover

Primary files:
- `handover/src/main/kotlin/…/handover/ui/HandoverPanel.kt`
- `handover/src/main/kotlin/…/handover/ui/HandoverTicketHeader.kt`
- `handover/src/main/kotlin/…/handover/ui/HandoverOverrideBanner.kt`
- `handover/src/main/kotlin/…/handover/ui/tabs/ChecksTab.kt`
- `handover/src/main/kotlin/…/handover/ui/tabs/ActionsTab.kt`
- `handover/src/main/kotlin/…/handover/ui/tabs/ShareTab.kt`
- `handover/src/main/kotlin/…/handover/ui/chips/QuickValueChipsPanel.kt`
- `handover/src/main/kotlin/…/handover/ui/cards/CopyrightFixCard.kt`

---

### [HND-01] Ticket Header — Key Pill, Summary, Status Pill

- **Component(s):** `HandoverTicketHeader` (`HandoverTicketHeader.kt`); `keyPill`; `summaryLabel`; `statusPill`
- **Preconditions:** A Jira ticket is active (set as the sprint ticket in the Sprint tab). Jira token configured.
- **Steps:**
  1. Open the Handover tab.
  2. Observe the one-row header at the top of the panel.
  3. Read the key pill (WEST), summary (CENTER), and status pill (EAST).
  4. Hover over the summary label if it is truncated.
- **Expected — visual:** Key pill: monospace bold 12pt, blue foreground (`StatusColors.LINK`), blue-tinted background (`0xE8F0FE` / `0x004786`), 2 px left + right padding. Summary: plain 12pt, normal foreground. Status pill: bold 11pt, status-colored foreground on grey background (`0xEEF0F2` / `0x1C2740`). Example: key = "PROJ-123", summary = "Implement login feature", status = "In Progress".
- **Expected — behavioral (no active ticket):** Key pill shows "NO ACTIVE TICKET" in `SECONDARY_TEXT` foreground + grey background; summary shows "—"; status shows "Unknown".
- **✅ Checks (tick each):**
  - [ ] Key pill shows ticket key in monospace bold with blue accent background
  - [ ] Summary shows ticket description (not blank, not key)
  - [ ] Status pill shows current Jira workflow status
  - [ ] "NO ACTIVE TICKET" state shown when no ticket active
  - [ ] Summary truncates gracefully when tool window is narrow
- **🐞 Bug signals:** Key pill shows blank; summary shows the key instead of the description; status pill blank; both pills same grey when a ticket exists; "NO ACTIVE TICKET" shown even when a ticket is set.
- **Theme/size matrix:** light + dark; narrow tool window — header must remain single-line.
- **⛔ Write note:** None — header display is read-only.

---

### [HND-02] Default Tab Heuristic + Override Banner

- **Component(s):** `HandoverPanel` init block (`HandoverPanel.kt:87–88`); `HandoverOverrideBanner` (`HandoverOverrideBanner.kt`); `failedFromState` (`HandoverPanel.kt:184–208`)
- **Preconditions:** Two scenarios: (a) at least one check is red (e.g. quality gate failed); (b) all checks green.
- **Steps:**
  1. (a) With a failed quality gate: close and re-open the Handover tab. Observe which tab is selected.
  2. (b) With all checks green: close and re-open. Observe which tab is selected.
  3. In scenario (a): observe the amber override banner between tab nav and content.
  4. Read the banner text and click the "View {tab}" link.
- **Expected — visual:**
  - (a) Default tab: **Checks** (index 0). Banner visible: amber background (`StatusColors.WARNING_BG`), 3 px left amber accent, balloon warning icon, "**N check(s) not green:** Quality gate FAILED" + "View Quality" link.
  - (b) Default tab: **Share** (index 2). Banner hidden.
  - Banner link: `StatusColors.LINK` foreground, underlined, hand cursor.
- **Expected — behavioral:** Clicking the "View {tab}" link in the banner switches the IDE tool window to the named tab (e.g. "Quality" → switches to the Quality tab in the tool window).
- **✅ Checks (tick each):**
  - [ ] Checks tab auto-selected when any check is failed
  - [ ] Share tab auto-selected when all checks green
  - [ ] Amber banner visible with failed check count when failures exist
  - [ ] Banner hidden when no failures
  - [ ] Banner lists failed check labels
  - [ ] "View {tab}" link navigates to the named tab
- **🐞 Bug signals:** Always opens to Checks tab even when all green; banner always hidden even when failures exist; banner text blank; "View" link does nothing; banner shown even when no checks are failed.
- **Theme/size matrix:** light + dark (amber banner must contrast with dark background).
- **⛔ Write note:** None — tab selection and banner display are read-only.

---

### [HND-03] Checks Tab — 8-Row Status Grid + Colored Dots

- **Component(s):** `ChecksTab` (`ChecksTab.kt`); `rowMeta[]`; `rowStatus[]`; status grid panel
- **Preconditions:** HND-01 passed. At least some checks have data: PR created, Build run, quality gate result known.
- **Steps:**
  1. Open the Handover tab → **Checks** sub-tab.
  2. Observe the "Pre-handoff status checks" card: 8 rows.
  3. For each row: read the row label (left column), status text (right column), meta text (middle).
  4. Cross-check: Build row status matches what the Build tab shows; Quality Gate row matches Quality tab.
- **Expected — visual:** Rows (in order, `ChecksTab.kt:73–82`):
  1. "Copyright headers" — OK/needs-fix + count
  2. "Pull request" — PR #N or "—"
  3. "Build" — plan + #buildNumber + status
  4. "Quality gate" — PASSED/FAILED
  5. "Suite: API smoke" — PASS/FAIL/running or "—"
  6. "Suite: API integration" — PASS/FAIL or "—"
  7. "Suite: Web E2E" — PASS/FAIL or "—"
  8. "Docker tags" — tag summary or "—"
  - Status labels use color: green (`StatusColors.SUCCESS`) for pass, red (`StatusColors.ERROR`) for fail, amber for in-progress, grey for unknown.
- **Expected — behavioral:** Status dots and meta text update automatically as `HandoverStateService.stateFlow` emits (no manual refresh needed). Equality gate prevents re-render when data is unchanged.
- **✅ Checks (tick each):**
  - [ ] All 8 rows labeled correctly
  - [ ] Build row shows plan key + build number
  - [ ] Quality gate row matches Quality tab gate status
  - [ ] PR row shows PR number if created
  - [ ] Status text colored green/red/amber appropriately
  - [ ] Meta text not blank for rows with data
  - [ ] Rows update when data changes (e.g. after a build completes)
- **🐞 Bug signals:** Fewer than 8 rows; wrong row labels; all status texts grey regardless of state; Build row doesn't show build number; Quality gate row always shows "—"; meta column blank for all rows.
- **Theme/size matrix:** light + dark; narrow window — meta text truncates rather than wrapping to multiple lines.
- **⛔ Write note:** None — checks grid is read-only.

---

### [HND-04] Ritual Checklist — Four Items + DONE/PENDING Badges

- **Component(s):** `ChecksTab.checklistPanel`; `CHECKLIST_LABELS` (`ChecksTab.kt:84–89`); `lastChecklistFlags` equality gate (`ChecksTab.kt:59–103`)
- **Preconditions:** HND-03 open. Ideally test with some checklist items DONE and some PENDING.
- **Steps:**
  1. In the Checks tab, scroll below the "Pre-handoff status checks" card to the "Ritual checklist" card.
  2. Observe the 4 checklist items: labels and badge/dot.
  3. Cross-check the status of each item against actual state:
     - "Copyright fixed" → toggled after Fix All in Actions tab
     - "PR created" → toggled after a PR exists
     - "Jira comment posted" → toggled after posting a comment (⛔ never post)
     - "Time logged" → toggled after logging time (⛔ never log)
- **Expected — visual:** Each checklist row: colored dot + label + "DONE" badge (green) or "PENDING" badge (grey/amber). "DONE" items: green dot, "DONE" label in `StatusColors.SUCCESS`. "PENDING" items: grey or amber dot, "PENDING" label in `SECONDARY_TEXT`.
- **Expected — behavioral:** Row appearance is driven by `HandoverStateService.stateFlow` and rebuilds only when the four boolean flags actually change (equality gate, `ChecksTab.kt:102–103`).
- **✅ Checks (tick each):**
  - [ ] Exactly 4 rows present with correct labels
  - [ ] "PR created" shows DONE when a PR exists for the ticket
  - [ ] DONE items have green dot + "DONE" text
  - [ ] PENDING items have grey/amber dot + "PENDING" text
  - [ ] No unnecessary re-render when flags unchanged (performance — no flickering on every state emit)
- **🐞 Bug signals:** Fewer/more than 4 rows; wrong labels; DONE and PENDING badges same color; "PR created" shows PENDING even when a PR was created; checklist rebuilds visibly on every second.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** "Jira comment posted" and "Time logged" flip via write ops (post comment / log work). Do NOT perform those write ops during this test — verify the PENDING state only.

---

### [HND-05] CopyrightFixCard — Rescan, File List, Fix All Dialog

- **Component(s):** `CopyrightFixCard` (`CopyrightFixCard.kt`); `rescanButton`; `fixAllButton`; `fileList`; `statusLabel`
- **Preconditions:** The active changelist has at least one modified file. Some files should be missing or have outdated copyright headers.
- **⛔ STOP before "Fix All":** Fix All rewrites source files in the project via `WriteCommandAction` with **NO confirmation dialog** (one click = files changed). This scenario is **read-only and ends at step 5** — never click Fix All.
- **Steps:**
  1. Open Handover tab → **Actions** sub-tab.
  2. Observe the "COPYRIGHT HEADER STATUS" card.
  3. Click **Rescan** (read-only scan — allowed). Observe: button disabled during scan, status label shows "Scanning…", then results.
  4. Observe the file list: each row shows filename + copyright status icon/color.
  5. If `fixAllButton.isEnabled = true`: **read** the affected-file list + the status count, and verify the button's enabled state/tooltip. **Do NOT click Fix All.** Stop here.
- **Expected — visual:**
  - Initial: empty card (`"No files to check."` or list of files from previous scan).
  - During scan: `rescanButton.isEnabled = false`, `statusLabel` shows "Scanning…".
  - After scan: file list populated. Each row: filename, `CopyrightStatus.OK` (green check?) or needs-fix (red/amber). Status label: "{N} file(s) — {M} need fixing". `fixAllButton.isEnabled = true` when M > 0.
- **Expected — behavioral:** *(informational — NOT executed in this read-only run)* If Fix All were clicked it would modify files via `WriteCommandAction` (single undo step), call `HandoverStateService.markCopyrightFixed()`, flip the "Copyright fixed" checklist item to DONE, and disable `fixAllButton`. There is **no** confirmation dialog guarding it — which is exactly why this scenario stops at step 5.
- **✅ Checks (tick each):**
  - [ ] Rescan button triggers scan (disabled during scan)
  - [ ] "Scanning…" label visible during scan
  - [ ] File list populated after scan with at least one row
  - [ ] Status label shows file + fix count
  - [ ] Fix All button enabled when M > 0 files need fixing
  - [ ] Fix All button disabled before first scan ("Click Rescan first" tooltip)
  - [ ] Confirm there is **no** confirmation dialog protecting Fix All (report as a UX risk if so — but do not click to find out; this is known from source)
- **🐞 Bug signals:** Rescan button never disables; "Scanning…" not shown; file list empty even with changed files having missing headers; `fixAllButton` always enabled or always disabled; status label blank after scan.
- **Theme/size matrix:** light + dark; many files — list scrollable.
- **⛔ Write note:** **Fix All = write op (rewrites source files, NO dialog).** Verify the button's enabled state + the affected-file list only, then **STOP — do NOT click Fix All**.

---

### [HND-06] QuickValueChipsPanel — Chip Rendering, Click-to-Copy, Flash Animation

- **Component(s):** `QuickValueChipsPanel` (`QuickValueChipsPanel.kt`); `ChipView`; `simulateClick`; flash animation
- **Preconditions:** HND-01 passed. Active ticket + focused PR so that placeholder values are resolvable. Open Handover tab → **Share** sub-tab → scroll to the bottom chip area.
- **Steps:**
  1. Observe the "QUICK VALUES" header row + "customise in Settings" link.
  2. Count visible chips and read each chip: key label (uppercase) + value label (monospace).
  3. Hover over a chip — observe background color change (hover highlight).
  4. Click a chip with an **available** value. Observe the 200 ms flash animation.
  5. Paste clipboard contents somewhere (e.g. Notepad) and verify it matches the chip's displayed value.
  6. Observe a chip with an **unavailable** value (shows "—"). Hover over it to read the tooltip.
  7. Click the "customise in Settings" link.
- **Expected — visual:**
  - Chip: rounded rectangle background (`StatusColors.CARD_BG`), border (`StatusColors.BORDER`). Key label: uppercase `SECONDARY_TEXT` bold, small font. Value label: monospace font, trimmed to 60 chars (`CHIP_VALUE_MAX_CHARS`, `QuickValueChipsPanel.kt:183`) with "…" for longer values.
  - Hover: background = `StatusColors.HIGHLIGHT_BG`, border = `StatusColors.LINK`.
  - Click flash: background flashes to `StatusColors.HIGHLIGHT_BG` for 200 ms (`FLASH_DURATION_MS`, `QuickValueChipsPanel.kt:188`), then reverts.
  - Unavailable chip: value label shows "—"; tooltip shows `unavailableReason`.
- **Expected — behavioral:** Click copies the **full** (untruncated) value to clipboard, not the displayed 57-char truncation. "customise in Settings" link opens the plugin Settings page at the Handover section.
- **✅ Checks (tick each):**
  - [ ] Chip key labels in uppercase bold
  - [ ] Chip value labels in monospace font
  - [ ] Long values truncated to 57 chars + "…" in display
  - [ ] Hover changes chip background to highlight color
  - [ ] Click flash animation visible (200 ms background change)
  - [ ] Clipboard contains full untruncated value after click
  - [ ] Unavailable chip shows "—" with tooltip explaining reason
  - [ ] "customise in Settings" link opens Settings dialog
- **🐞 Bug signals:** No chips rendered; key labels lowercase; monospace font not applied; hover has no visual change; flash not visible (background unchanged on click); clipboard empty after click; clipboard contains truncated (57-char) value instead of full value; "customise in Settings" link does nothing.
- **Theme/size matrix:** light + dark (chip border must contrast in both themes); many chips — flow layout wraps to multiple rows.
- **⛔ Write note:** None — chip click copies to clipboard only (read-side interaction).

---

### [HND-07] Share Tab — Jira Template Editor Preview + Copy to Clipboard

- **Component(s):** `ShareTab` (`ShareTab.kt`); `TemplateEditorCard` (Jira); `JiraPreviewPane`; clipboard copy action
- **Preconditions:** HND-01 passed. A Jira template is configured. Placeholders (`ticket.id`, `ticket.summary`, `pr.id`, etc.) should resolve from the active ticket and focused PR.
- **Steps:**
  1. Open Handover tab → **Share** sub-tab.
  2. Observe the **Jira** template editor card at the top.
  3. Verify the preview pane shows rendered Jira wiki-markup (or HTML preview).
  4. Verify placeholder values are substituted: `{{ticket.id}}` → actual ticket key; `{{pr.id}}` → actual PR ID.
  5. Click the **Copy** action on the Jira card (copies wiki-markup to clipboard).
  6. Paste into a text editor and verify the copied content matches the preview.
  7. If a **Post to Jira** button exists: verify it is present and labeled correctly; **do NOT click it**.
- **Expected — visual:** Template editor: editable text area at top; preview pane below (rendered Jira markup or HTML). Placeholders in the preview are resolved to actual values. If a template dropdown (TemplatePicker) is present, it shows named templates from `HandoverTemplateStore`.
- **Expected — behavioral:** Editing the template text updates the preview in real time (or near-real-time). Resolved placeholders in the preview match the exact values from the chip panel (QAL-06 cross-check). Copy action puts the resolved wiki-markup on the clipboard.
- **✅ Checks (tick each):**
  - [ ] Jira editor card visible at top of Share tab
  - [ ] Preview pane shows resolved placeholder values (not raw `{{ticket.id}}` literals)
  - [ ] `{{ticket.id}}` resolves to the active Jira ticket key
  - [ ] `{{pr.id}}` resolves to the focused PR number
  - [ ] Clipboard after Copy matches the visible preview text
  - [ ] Template picker (if present) shows at least one named template
- **🐞 Bug signals:** Preview pane blank; `{{ticket.id}}` literal shown in preview (not resolved); Copy puts empty string or raw template on clipboard; template picker empty even when templates are configured; preview not updating after ticket/PR change.
- **Theme/size matrix:** light + dark; narrow Share tab — editor and preview scroll independently.
- **⛔ Write note:** **Post to Jira** / "Send Jira comment" (write op: posts a comment to the Jira ticket) — verify the button is present with correct label, read the preview content, then **do NOT click the post button**. Observe only.

---

### [HND-08] Confirm by design: Handover has NO AI pre-review card

- **Component(s):** `HandoverPanel` (`HandoverPanel.kt:67–69` → sub-tabs: Checks, Actions, Share). Pre-review is owned by the PR tab's `AiReviewTabPanel` (`pullrequest/…/ui/AiReviewTabPanel.kt`), NOT Handover. (Confirm-the-gap scenario; mirrors HIS-2 / HIS-7.)
- **Preconditions:** HND-01 passed. An active ticket / focused PR so the Handover tab populates normally.
- **Steps:**
  1. Open the **Handover** tab.
  2. Inspect every sub-tab: **Checks**, **Actions**, **Share**.
  3. Confirm there is **no AI pre-review card / summary** anywhere in the Handover tab (no "AI review", "pre-review", or generated-summary panel).
- **Expected — visual:** Handover shows only the three sub-tabs and their documented content (HND-01…07). No AI pre-review card/summary is present in any sub-tab.
- **Expected — behavioral:** Absent **by design** — the AI pre-review was removed from Handover; pre-review now lives solely in the PR tab (`AiReviewTabPanel`). This is the intended state, not a missing feature.
- **✅ Checks (tick each):**
  - [ ] Checks sub-tab has no AI pre-review card
  - [ ] Actions sub-tab has no AI pre-review card
  - [ ] Share sub-tab has no AI pre-review card
  - [ ] No "AI review" / "pre-review" / generated-summary panel anywhere in Handover
- **🐞 Bug signals:** An AI pre-review card / summary **DOES** appear in any Handover sub-tab → unexpected (it should only exist in the PR tab) → report it.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A — read-only inspection (no actions taken).
