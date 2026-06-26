# Connector Tabs — Sprint (Jira) & PR (Bitbucket)

This section covers the two real-time connector tabs that talk to live external services (Jira and Bitbucket). Both tabs use token-authenticated HTTP calls against a licensed Jira DC instance and a Bitbucket DC instance. All scenarios here assume a Windows IntelliJ Ultimate session launched via `./gradlew runIde` with both tokens pre-configured in Settings → Workflow Orchestrator.

**Hard constraint:** certain controls send write requests to Jira or Bitbucket. Each such scenario describes the control and its confirm dialog, then stops with `⛔ SKIP-EXECUTION (write op) — verify dialog, then Cancel`. Do **not** click OK/Yes/Confirm on any write-op dialog.

**Reading the source:** all file:line citations reference the state of the `feature/plugin-split` branch at the time this plan was authored.

---

## Sprint (Jira)

### [SPR-1] Initial load — sprint header populates correctly
- **Component(s):** `SprintDashboardPanel` (sprint name label `sprintNameLabel`, meta label `sprintMetaLabel`, count badge `ticketCountLabel`) — `SprintDashboardPanel.kt:102–117`, `updateSprintHeader()` at line 844
- **Preconditions:** Jira token configured; at least one active sprint exists on the configured board.
- **Steps:**
  1. Open the Workflow tool window and click the **Sprint** tab.
  2. Wait for the animated loading icon to disappear and the status bar to show "N tickets loaded".
- **Expected — visual:**
  - Sprint name shown in bold 13pt text (e.g. "Sprint 42 (Active)"). If multiple sprints exist, a `ComboBox` replaces the label and auto-selects the active sprint.
  - A small pill badge shows `(N tickets)` where N equals the number of rows rendered in the list below — count must match exactly.
  - Below the name, a secondary meta label shows the date range `YYYY-MM-DD → YYYY-MM-DD` or the sprint state if dates are absent.
- **Expected — behavioral:**
  - Loading icon vanishes; status bar reads exactly `"N tickets loaded"` (not "Error:…").
  - `SprintTimeBar` below the header shows two columns: TIME ELAPSED (progress bar) and TICKET BREAKDOWN (segmented bar). Both render without zero-width collapse.
- **✅ Checks (tick each):**
  - [ ] Sprint name label text matches the active sprint name shown in Jira.
  - [ ] `(N tickets)` badge value equals the total visible row count (excluding assignee-group section-header rows).
  - [ ] Date range format is `YYYY-MM-DD → YYYY-MM-DD`; neither date is "null" or blank.
  - [ ] Status bar reads "N tickets loaded" (not "0 tickets loaded" when tickets exist, and not "Error:…").
  - [ ] No text in any label reads "null", "No board found", or shows a raw exception message.
- **🐞 Bug signals:** Count badge says "(0 tickets)" while rows are visible; sprint name shows "null"; status bar stays on "Loading sprint tickets…" indefinitely; time bar renders as a flat invisible strip.
- **Theme/size matrix:** light + dark; narrow tool-window width (badge and name must not overlap or clip).
- **⛔ Write note:** N/A — load only.

---

### [SPR-2] Ticket list row anatomy
- **Component(s):** `TicketListCellRenderer` — `TicketListCellRenderer.kt:101–265`
- **Preconditions:** Sprint loaded with at least three tickets in different statuses (To Do, In Progress, Done).
- **Steps:**
  1. Observe the list without clicking anything.
  2. Hover over each of the three status-category rows in turn.
- **Expected — visual:**
  - Row height is 56dp. Each row is a card with:
    - **Line 1:** colored priority dot (red=Highest/High/Critical/Blocker; amber=Medium; green=Low/Lowest/Trivial) + bold blue ticket key (e.g. `WO-123`) + truncated summary text.
    - **Line 2:** status pill (colored rounded rectangle with white uppercase text: `IN PROGRESS`=blue, `DONE`=green, `TO DO`=grey) + issue-type badge (e.g. `Story`, `Bug`) + assignee chip (name or "Unassigned") + optional red `⚠ N` blocker count.
  - A **Done** ticket's summary has a strikethrough line.
  - **In Progress** tickets have a 2dp blue left-border accent; **Done** tickets have a green accent; **To Do** tickets have no accent.
  - Hovered row background transitions to `HIGHLIGHT_BG`; un-hovered returns to card background.
- **Expected — behavioral:**
  - Moving the mouse out of the list resets hover highlight (no stale highlight).
  - Tooltip on hover shows `KEY: summary` (truncated to 200 chars).
- **✅ Checks (tick each):**
  - [ ] Priority dot color matches the priority level for each visible ticket.
  - [ ] Status pill text matches Jira status name (uppercased).
  - [ ] Done tickets show strikethrough summary text.
  - [ ] Left-border accent color matches status category (blue/green/absent).
  - [ ] Hover highlight appears and disappears cleanly.
  - [ ] Assignee chip shows display name; "Unassigned" if no assignee.
- **🐞 Bug signals:** All priority dots the same color; status pill missing or shows empty rectangle; strikethrough missing on Done tickets; hover state sticks after mouse exit; assignee shows raw JSON or "null".
- **Theme/size matrix:** light + dark; narrow width (summary truncation with ellipsis — no horizontal scrollbar).
- **⛔ Write note:** N/A.

---

### [SPR-3] Scroll — long ticket list
- **Component(s):** `JBScrollPane` wrapping `ticketList` in `SprintDashboardPanel.kt:402–408`
- **Preconditions:** Sprint with 20+ tickets loaded.
- **Steps:**
  1. Place cursor over the ticket list.
  2. Scroll to the bottom using mouse wheel and scroll bar.
  3. Scroll back to the top.
- **Expected — visual:** Scrolling is smooth; no rows render at incorrect positions; no duplicate or blank rows appear at the boundary.
- **Expected — behavioral:** Selecting a row near the bottom causes the detail panel to populate. After scroll-to-top and selecting the first row, the detail panel switches to that ticket.
- **✅ Checks (tick each):**
  - [ ] Vertical scrollbar appears when rows overflow.
  - [ ] All rows are fully visible when scrolled to their position (no half-rendered rows).
  - [ ] Selection follows the scroll position correctly — clicking a row at the bottom selects that row, not another.
- **🐞 Bug signals:** Scrollbar missing; rows flicker; selecting near the bottom scrolls back to top; blank white areas appear mid-list.
- **Theme/size matrix:** light + dark; narrow tool-window width.
- **⛔ Write note:** N/A.

---

### [SPR-4] Refresh button and status bar
- **Component(s):** `RefreshAction` in `SprintDashboardPanel.kt:901–909`; `loadingIcon`, `statusLabel`
- **Preconditions:** Sprint already loaded.
- **Steps:**
  1. Click the **Refresh** (circular arrow) toolbar button.
  2. Observe the status bar during load.
  3. Wait for load to complete.
- **Expected — visual:** Animated spinner icon appears immediately left of the status label. Status label shows "Loading sprint tickets…". After completion, spinner disappears and status label shows "N tickets loaded".
- **Expected — behavioral:** Ticket list is re-populated; if new tickets were added server-side between loads, they appear. Count badge re-syncs.
- **✅ Checks (tick each):**
  - [ ] Spinner visible during load.
  - [ ] Status label shows "Loading sprint tickets…" during load.
  - [ ] Spinner hidden after load.
  - [ ] Status label shows "N tickets loaded" after successful load.
  - [ ] Count badge `(N tickets)` matches the refreshed list count.
- **🐞 Bug signals:** Spinner never appears; status stuck on "Loading sprint tickets…" after >10 s; count badge and list count disagree post-refresh; status shows "Error:" with an exception on a valid token.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A.

---

### [SPR-5] Sort combo — all five options
- **Component(s):** `sortByCombo` in `SprintDashboardPanel.kt:154`, `sortIssues()` at line 815
- **Preconditions:** Sprint loaded with tickets of varied statuses, priorities, and update timestamps.
- **Steps:**
  1. Open the **Sort by** combo box (Default|Priority|Status|Updated|Key).
  2. Select each option in turn and observe the list order.
- **Expected — visual/behavioral:**
  - **Default:** In-Progress floats first, then To-Do, then Done (matches `statusOrder` in code).
  - **Priority:** Highest/Blocker at top, Lowest at bottom (`priorityOrder` in code).
  - **Status:** Same category ordering as Default (indeterminate → new → done).
  - **Updated:** Most recently updated ticket at top.
  - **Key:** Alphabetical-numeric ascending by Jira key (e.g. WO-1, WO-10, WO-2 — note lexicographic, not numeric).
  - Selection persists after a tab switch and return (stored in `PluginSettings.state.sprintSortBy`).
- **✅ Checks (tick each):**
  - [ ] Default: all Done tickets are below all In Progress tickets.
  - [ ] Priority: Highest/Critical/Blocker rows precede Medium rows precede Low/Lowest rows.
  - [ ] Updated: the ticket with the most recent `updated` timestamp is at top.
  - [ ] Key: ascending alphabetical sort (verifiable by comparing first and last visible keys).
  - [ ] Sort preference survives closing and reopening the Sprint tab.
- **🐞 Bug signals:** Sort combo has no effect; list resets to server order after any sort; "Default" places Done tickets at the top; combo selection resets to "Default" after tab switch.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A.

---

### [SPR-6] Search / filter field — debounce and count badge
- **Component(s):** `searchField` in `SprintDashboardPanel.kt:97–99`; `applyFilter()` at line 880; `ticketCountLabel` update at line 893
- **Preconditions:** Sprint with 10+ tickets loaded.
- **Steps:**
  1. Type a partial ticket key (e.g. the project prefix "WO") in the search field.
  2. Observe that the list does NOT flicker on each keypress (250 ms debounce).
  3. After 300 ms, observe filtered results.
  4. Clear the field.
- **Expected — visual:**
  - While typing, list does not change immediately on each keystroke.
  - After debounce settles (~250 ms), list shows only matching rows (key, summary, or assignee name contains the query, case-insensitive).
  - Count badge updates to `(M/N tickets)` where M = matching count, N = total.
  - Clearing the field restores the full list and badge reverts to `(N tickets)`.
- **Expected — behavioral:** Filtering by assignee display name works. Typing a string that matches no ticket shows the "No tickets in sprint." empty state.
- **✅ Checks (tick each):**
  - [ ] Debounce: no intermediate flickering on rapid keystrokes.
  - [ ] `(M/N tickets)` badge format appears when query is non-empty.
  - [ ] `(N tickets)` restored after clear.
  - [ ] Filtering by assignee name works (partial match).
  - [ ] Empty state shown when no matches exist.
- **🐞 Bug signals:** List filters on every keypress with visible lag; badge stays at `(N tickets)` when filtering; badge shows wrong fraction; empty state never shows even with no matches.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A.

---

### [SPR-7] Sprint selector (multi-sprint scrum board)
- **Component(s):** `sprintSelector` ComboBox in `SprintDashboardPanel.kt:124–133`; `populateSprintSelector()` at line 609; `loadSprintBySelection()` at line 636
- **Preconditions:** Scrum board configured with multiple sprints (active + at least one future or closed sprint).
- **Steps:**
  1. Open the Sprint tab. Verify the sprint selector ComboBox is visible (if only one sprint exists, the label shows instead — that is correct).
  2. Open the selector dropdown and choose a non-active sprint.
  3. Observe the list reload.
- **Expected — visual:**
  - Selector shows sprint names with `(Active)` suffix for the active sprint (per `sprintLabel()` at line 120).
  - On selection, spinner and "Loading Sprint-name…" appear in the status bar.
  - After load, list and header update to the chosen sprint.
- **Expected — behavioral:** Auto-selection of the active sprint on first load (`activeIndex` logic at line 619). Choosing the active sprint re-loads it. `sprintNameLabel` is hidden when selector is visible and vice-versa (line 626–629).
- **✅ Checks (tick each):**
  - [ ] Active sprint shows `(Active)` suffix in the selector.
  - [ ] Non-active sprint selection triggers a spinner + status message.
  - [ ] After selection, header and count badge update to the selected sprint.
  - [ ] Sprint name label is hidden when selector is visible.
  - [ ] Single-sprint boards: selector hidden, label visible.
- **🐞 Bug signals:** Selector invisible on a multi-sprint board; selecting a sprint does nothing; count badge shows 0 when the selected sprint has tickets; `(Active)` missing from the active sprint entry.
- **Theme/size matrix:** light + dark; narrow width (selector must not grow beyond `ComboBoxWidth.DEFAULT`).
- **⛔ Write note:** N/A.

---

### [SPR-8] My Tickets / All Tickets toggle
- **Component(s):** `ToggleAllUsersAction` in `SprintDashboardPanel.kt:911–933`
- **Preconditions:** Sprint loaded; the board has tickets assigned to other team members.
- **Steps:**
  1. Note the initial list (showing only your tickets — `showAllUsers=false`).
  2. Click the **All Tickets** toolbar icon.
  3. Click it again (toggles back to **My Tickets**).
- **Expected — visual:**
  - Default: list contains only tickets assigned to the authenticated user.
  - After toggle: list shows all team tickets; assignee-group section-header rows appear (e.g. "── Jane Doe ──").
  - Icon tooltip text changes from "All Tickets" to "My Tickets" and back.
  - Count badge updates on each toggle.
- **Expected — behavioral:** Each toggle triggers a full `loadData()` cycle with spinner visible.
- **✅ Checks (tick each):**
  - [ ] Default view shows only the current user's tickets.
  - [ ] After first toggle, tickets from other assignees appear.
  - [ ] Assignee-group headers are non-selectable (clicking them does not open the detail panel).
  - [ ] Count badge matches the full list count after toggle.
  - [ ] Tooltip text matches the current state ("Show only tickets assigned to me" / "Show all team tickets grouped by assignee").
- **🐞 Bug signals:** Toggle has no effect; assignee headers are clickable; count badge unchanged after toggle; section-header row background looks like a normal ticket row.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A.

---

### [SPR-9] Ticket selection → TicketDetailPanel renders
- **Component(s):** `ticketList` selection listener in `SprintDashboardPanel.kt:486–495`; `TicketDetailPanel.showIssue()` at line 125
- **Preconditions:** Sprint loaded with at least one ticket visible.
- **Steps:**
  1. Click a ticket row.
  2. Observe the right-hand detail panel.
- **Expected — visual:**
  - "Select a ticket to view details" placeholder replaced by the ticket detail.
  - Header section shows: bold blue ticket key, status pill (colored), issue-type badge, priority colored tag, and summary in 16pt bold.
  - Info-card 2×2 grid shows: ASSIGNEE (avatar circle + display name), SPRINT (sprint name), CREATED (YYYY-MM-DD), UPDATED (YYYY-MM-DD).
  - Labels/components chips appear if present (styled as bordered pills).
  - Transition button shows current status name with `▾` dropdown arrow.
  - Watch button `👁 Watch` or `👁 Watching` appears.
  - "Open in Jira ↗" hyperlink visible.
- **Expected — behavioral:**
  - Clicking a section-header row (assignee group) does NOT populate the detail panel (header guard at `SprintDashboardPanel.kt:489`).
  - Switching to a different ticket cancels any in-flight lazy loads from the previous ticket.
- **✅ Checks (tick each):**
  - [ ] Key label text matches the selected ticket key.
  - [ ] Summary text is the full summary (or correctly truncated with tooltip if > view width).
  - [ ] Status pill color matches category (blue=In Progress, green=Done, grey=To Do).
  - [ ] Priority color tag color matches the ticket's priority.
  - [ ] ASSIGNEE card shows correct name; "Unassigned" if none.
  - [ ] SPRINT card shows sprint name or "No sprint".
  - [ ] CREATED and UPDATED dates are `YYYY-MM-DD` format and not "null".
  - [ ] Section-header rows: clicking does not populate detail panel.
- **🐞 Bug signals:** Detail panel stays on "Select a ticket…" after clicking a ticket; key/summary mismatch; status pill wrong color; info cards show "null"; clicking a section header crashes or opens a detail.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A — no server calls for immediate rendering (uses cached sprint data).

---

### [SPR-10] TicketDetailPanel — description, subtasks, dependencies
- **Component(s):** `addDescription()` at `TicketDetailPanel.kt:1118`; `addSubtasks()` at line 443; `addDependencies()` at line 1025
- **Preconditions:** Select a ticket that has a multi-paragraph description, at least one subtask, and at least one issue link.
- **Steps:**
  1. Click the ticket in the list.
  2. Scroll the right panel down through all sections.
- **Expected — visual:**
  - **Description:** Rendered in a card with rounded corners and `CARD_BG` background. Line breaks preserved (`\n` → `<br>`). No raw HTML tags visible.
  - **Subtasks (N):** Section header appears. Each subtask row shows a status icon (`✓`, `⟳`, `○`) in the appropriate color, the subtask key in bold blue, and a truncated summary. Long summaries (>50 chars) show a tooltip on hover.
  - **Dependencies (N):** Each dependency row has a colored background tint (red for "blocked by", amber for "blocks", neutral for other link types), a status dot, the linked ticket key, a truncated summary, and the link direction + status on the right.
- **Expected — behavioral:** Scrolling the detail panel is smooth. Tooltip on truncated subtask/dependency summary shows the full text on hover.
- **✅ Checks (tick each):**
  - [ ] Description text is human-readable (not raw wiki markup or JSON).
  - [ ] Subtask count in the section header matches the number of subtask rows.
  - [ ] "Blocked by" rows have a red tint background in light mode.
  - [ ] "Blocks" rows have an amber tint background in light mode.
  - [ ] Subtask key shown in bold; summary truncated at 50 chars with tooltip for longer text.
- **🐞 Bug signals:** Description shows raw `{noformat}` or HTML tags; subtask count header says "Subtasks (2)" but only 1 row is rendered; dependency tint colors appear in wrong order; all dependency rows have a red tint regardless of link direction.
- **Theme/size matrix:** light + dark (tint colors must remain readable in dark mode — see `BLOCKED_BY_TINT` JBColor).
- **⛔ Write note:** N/A.

---

### [SPR-11] TicketDetailPanel — lazy sections load (Dev Status, Worklog, History, Comments)
- **Component(s):** `DevStatusSection.loadDevStatus()` at `DevStatusSection.kt:48`; `WorklogSection`; `ChangelogSection`; `lazyLoadComments()` at `TicketDetailPanel.kt:745`
- **Preconditions:** Select a ticket that has at least one comment and has dev-status data (linked branch or PR).
- **Steps:**
  1. Click the ticket.
  2. Wait ~2 s while lazy sections load in the background.
  3. Scroll down to reveal Dev Status, Time Logged, History, and Comments sections.
- **Expected — visual:**
  - Dev Status: shows chip-strip for branches/PRs/commits/builds (or "Could not load dev status." if endpoint unavailable).
  - Time Logged: worklog entries or "No time logged."
  - History: changelog entries with author, action, timestamp.
  - Comments: "Loading comments..." placeholder replaced by comment rows (or "No comments"). Comment rows show a colored avatar circle (letter), bold author name, relative timestamp, and body text.
- **Expected — behavioral:**
  - Switching to a different ticket while sections are loading cancels the in-flight loads (no stale data from the previous ticket appears in the new ticket's panel).
  - Comments cache TTL: 60 s. A second selection of the same ticket within 60 s should render from cache (no visible spinner).
- **✅ Checks (tick each):**
  - [ ] "Loading comments..." placeholder is gone within 5 s.
  - [ ] Comment author avatars show the first letter of the author name in a colored circle.
  - [ ] Relative timestamp (e.g. "3 minutes ago") appears next to the author name.
  - [ ] If >20 comments exist, the row "X more comments — open in Jira to see all" appears.
  - [ ] Dev Status section header is hidden when no dev-status content exists (per `onContent` callback at `TicketDetailPanel.kt:164`).
- **🐞 Bug signals:** "Loading comments..." never replaced; comment body shows raw wiki markup; avatar circle shows "?" for all authors; switching tickets shows previous ticket's comments; "20 more comments" message appears when there are exactly 20 comments.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A — read-only fetches.

---

### [SPR-12] TicketDetailPanel — attachments panel
- **Component(s):** `addAttachmentsHeader()` / `addAttachments()` at `TicketDetailPanel.kt:495–683`
- **Preconditions:** Select a ticket that has at least one image attachment and one non-image attachment (e.g. a PDF).
- **Steps:**
  1. Click the ticket.
  2. Scroll down to the Attachments section.
  3. Hover over the thumbnail card.
  4. Right-click the card to open the context menu.
  5. Observe "Download All" link.
- **Expected — visual:**
  - Image attachments: 80×60dp placeholder initially; thumbnail loads within ~3 s.
  - Non-image attachments: a file-type icon (`AllIcons.FileTypes.Text` for PDF).
  - Filename truncated to 16 chars with tooltip for longer names.
  - File size shown below filename (B / KB / MB).
  - Three-dot `⋯` button in the top-right corner of each card.
  - "Download All" link in the section header.
- **Expected — behavioral:**
  - Left-clicking the attachment card opens it in the browser (via `BrowserUtil.browse(att.content)`).
  - Right-clicking or clicking `⋯` opens a popup with: "Open in Editor", "Open in Browser", "Download".
- **✅ Checks (tick each):**
  - [ ] Image thumbnail renders (not a placeholder spinner indefinitely).
  - [ ] Non-image shows a file icon (not a broken image).
  - [ ] Tooltip on truncated filename shows the full filename.
  - [ ] Size displays in human-readable units.
  - [ ] Right-click popup has exactly three menu items.
- **🐞 Bug signals:** Thumbnail never loads; all files show "Any_type" icon; context menu missing; file size shows "0 B" for a known non-zero file; clicking the card navigates to a blank URL.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A — read-only rendering. (Clicking "Open in Editor" / "Open in Browser" / "Download" triggers downloads; treat those as read-only; do NOT click "Download" for large files without disk space confirmation from the team.)

---

### [SPR-13] Transition button — verify dialog, skip execution
- **Component(s):** `transitionBtn` in `TicketDetailPanel.kt:238–248`; `TicketTransitionDialog`
- **Preconditions:** Select a ticket that has at least one available transition.
- **Steps:**
  1. Select a ticket.
  2. Click the transition button (shows current status + `▾`).
  3. Verify the `TicketTransitionDialog` opens.
  4. Inspect the dialog: it should show available transitions in a dropdown, and field widgets for any required fields.
  5. **⛔ SKIP-EXECUTION:** click Cancel.
- **Expected — visual:**
  - Button label: `"<current status name> ▾"` (e.g. `"In Progress ▾"`).
  - Dialog title contains the ticket key.
  - Available transitions listed (e.g. "Done", "To Do", "In Review").
  - If a transition requires a comment or resolution field, widget is visible.
- **Expected — behavioral:**
  - Disabled by `PermissionGate.canTransition == false` (tooltip: "You don't have permission to transition this issue") when the user lacks TRANSITION permission.
- **✅ Checks (tick each):**
  - [ ] Button label matches the ticket's current status.
  - [ ] Dialog opens on click.
  - [ ] At least one transition option is visible in the dialog.
  - [ ] Cancel closes the dialog without changing the ticket status.
- **🐞 Bug signals:** Button has no `▾` suffix; dialog opens empty (no transitions); dialog crashes; transition button absent from the panel.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** Transition button → `TicketTransitionDialog` opened → **SKIP-EXECUTION (write op — Jira status transition) — verify dialog content, then Cancel**.

---

### [SPR-14] Watch button — state reflects server watch status
- **Component(s):** `watchBtn` in `TicketDetailPanel.kt:255–259`; `loadInitialWatchState()` at line 332
- **Preconditions:** Select a ticket. (The test user may or may not already be watching it.)
- **Steps:**
  1. Select a ticket and wait for the watch button to settle (loads async after permission check).
  2. Observe button label (`👁 Watch` or `👁 Watching`) and tooltip (watcher count).
  3. **⛔ SKIP-EXECUTION — do not click the watch button.**
- **Expected — visual:**
  - Label = `"👁 Watching"` if the current user is watching; `"👁 Watch"` if not.
  - Tooltip = `"Watching: N user(s)"` with correct count; or `"Add yourself as a watcher"` on load failure.
  - Button hidden entirely if `PermissionGate.canViewWatchers == false`.
- **✅ Checks (tick each):**
  - [ ] Button label is one of `"👁 Watch"` or `"👁 Watching"` (not a default).
  - [ ] Tooltip shows a watcher count or a readable message.
  - [ ] Button visible when user has VIEW_WATCHERS permission.
- **🐞 Bug signals:** Button stays at default text "👁 Watch" regardless of server state; tooltip shows raw exception; button always hidden even when user has permissions.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** Watch button click → **SKIP-EXECUTION (write op — adds/removes Jira watcher) — verify label and tooltip, do not click**.

---

### [SPR-15] QuickCommentPanel — verify input bar, skip posting
- **Component(s):** `QuickCommentPanel` in `QuickCommentPanel.kt`; wired at `TicketDetailPanel.kt:210–216`
- **Preconditions:** Select a ticket. The user has COMMENT permission.
- **Steps:**
  1. Select a ticket.
  2. Observe the comment input bar pinned to the bottom of the detail panel.
  3. Click the visibility dropdown (default: "Public").
  4. Type a comment in the field.
  5. **⛔ SKIP-EXECUTION:** press Escape or click elsewhere to dismiss without sending. Do NOT click the send button or press Enter.
- **Expected — visual:**
  - Comment text field with placeholder "Add a comment...".
  - Visibility combo on the right (defaults to "Public"). If the project has roles/groups, additional entries appear (e.g. "Role: Developers", "Group: jira-users").
  - Send button (green play icon) to the right of visibility combo.
  - If `PermissionGate.canComment == false`: field is disabled, placeholder shows "You don't have permission to comment".
- **Expected — behavioral:** Visibility options load async when a ticket is first shown; the dropdown starts with just "Public" and populates lazily.
- **✅ Checks (tick each):**
  - [ ] Comment field is present at bottom of panel.
  - [ ] Default visibility is "Public".
  - [ ] Field is enabled when the user has COMMENT permission.
  - [ ] Disabled state shows the permission-denied placeholder text.
- **🐞 Bug signals:** Comment bar missing; visibility combo empty (no "Public" option); field enabled when user has no comment permission; send button absent.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** Send button / Enter key → **SKIP-EXECUTION (write op — posts Jira comment) — verify field content and visibility selection, do not submit**.

---

### [SPR-16] Start Work button — verify dialog, skip execution
- **Component(s):** `StartWorkAction` in `SprintDashboardPanel.kt:936–1233`; `StartWorkDialog`
- **Preconditions:** Select a ticket in the list. Bitbucket URL, project key, and repo slug are configured in Settings.
- **Steps:**
  1. Select a ticket.
  2. Click the **Start Work** (play/execute) toolbar button.
  3. Observe the `StartWorkDialog` (may show a short "Fetching branches…" status while loading).
  4. Verify dialog fields: branch name, source branch selector, repo selector.
  5. If `{ai-summary}` is in the branch pattern, verify the AI-generation spinner appears.
  6. **⛔ SKIP-EXECUTION:** click Cancel.
- **Expected — visual:**
  - Dialog shows the ticket key in the title area.
  - Suggested branch name pre-populated from the branch pattern (e.g. `feature/WO-123-short-summary`).
  - Source branch combo shows the list of remote branches.
  - If multiple repos configured, a repo selector is present.
  - If AI is generating a branch slug, a spinner is visible in the field.
- **Expected — behavioral:**
  - Button is disabled (greyed out) when no ticket is selected or when a section-header row is selected.
  - Cancel closes the dialog without creating a branch or transitioning the Jira ticket.
- **✅ Checks (tick each):**
  - [ ] Button is disabled when no ticket selected.
  - [ ] Dialog opens after clicking with a ticket selected.
  - [ ] Branch name field is pre-populated (not blank).
  - [ ] Source branch dropdown is not empty.
  - [ ] Cancel closes the dialog; no notification appears.
- **🐞 Bug signals:** Button always enabled even with no selection; dialog opens with blank branch name; source branch dropdown empty; dialog crashes; Cancel triggers a Jira transition notification.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** Start Work dialog OK button → **SKIP-EXECUTION (write op — creates Bitbucket branch + Jira transition) — verify dialog fields, then Cancel**.

---

### [SPR-17] "Currently Working On" section
- **Component(s):** `CurrentWorkSection` referenced at `SprintDashboardPanel.kt:358–375`; `CollapsibleSection` wrapper
- **Preconditions:** An active ticket has been set (via `ActiveTicketService`).
- **Steps:**
  1. Open the Sprint tab.
  2. Observe the "CURRENTLY WORKING ON" collapsible section at the top of the left panel.
  3. Click the section header to collapse and re-expand it.
- **Expected — visual:**
  - Shows the active ticket key, summary, and branch context.
  - Clicking the active ticket chip scrolls the list to that ticket and selects it.
  - When collapsed, the content is hidden; header remains visible.
- **Expected — behavioral:** If no active ticket, the section shows an appropriate empty state. Clicking a ticket in the chip navigates the list selection.
- **✅ Checks (tick each):**
  - [ ] Active ticket key and summary are visible.
  - [ ] Collapse/expand toggle works; content hides and shows correctly.
  - [ ] Clicking the active ticket navigates the list to that row.
- **🐞 Bug signals:** Section missing when an active ticket is set; collapse button does nothing; clicking the chip does not scroll the list; section visible but empty when an active ticket IS set.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A.

---

### [SPR-18] Saved Filters section
- **Component(s):** `SavedFiltersSection`, `savedFiltersCollapsible` at `SprintDashboardPanel.kt:422–429`; `onFilterClicked()` at line 684; `exitFilterResultsView()` at line 758
- **Preconditions:** At least one Jira favourite filter exists for the authenticated user.
- **Steps:**
  1. Open the Sprint tab and wait for full load.
  2. Observe the "SAVED FILTERS" collapsible section below "CURRENTLY WORKING ON".
  3. Click a filter name to run it.
  4. Observe the "Filter: &lt;name&gt;" header and "← Back to sprint" link.
  5. Click "← Back to sprint".
- **Expected — visual:**
  - Section hidden when no favourite filters exist; visible when at least one exists.
  - Each filter row shows the filter name.
  - On filter activation: sprint list replaced by JQL results; a header bar shows "Filter: &lt;name&gt;" + "← Back to sprint" link. Count badge updates.
  - On "← Back to sprint": sprint list restored; filter header hidden.
- **Expected — behavioral:** Running a filter with more than 50 tickets: only the first 50 are shown (API search limit at `SprintDashboardPanel.kt:731`). JQL with control characters or length >MAX_JQL_LENGTH shows an error in the status bar.
- **✅ Checks (tick each):**
  - [ ] Section visible when favourite filters exist.
  - [ ] Section hidden when no favourite filters exist.
  - [ ] Clicking a filter shows "Loading filter 'name'…" in status bar, then results.
  - [ ] "Filter: name" header replaces sprint header during filter view.
  - [ ] "← Back to sprint" restores the sprint list and hides the filter header.
  - [ ] Count badge shows the filter result count.
- **🐞 Bug signals:** Section visible with no favourite filters; clicking a filter shows 0 results when the JQL should match tickets; "Back to sprint" leaves the filter header visible; status bar stays on "Loading filter…" indefinitely.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A — filter execution is read-only JQL search.

---

### [SPR-19] Empty state — no tickets in sprint
- **Component(s):** `emptyLabel` at `SprintDashboardPanel.kt:145–149`; `listCardLayout` card "empty"
- **Preconditions:** Either use a sprint with zero tickets, or type a search query that matches nothing.
- **Steps:**
  1. Type a nonsense string (e.g. "zzzzzzzzz") in the search field.
  2. Observe the list area.
  3. Clear the search field.
- **Expected — visual:** "No tickets in sprint." centered label appears in the list area (replaces the list). After clearing, list returns.
- **✅ Checks (tick each):**
  - [ ] "No tickets in sprint." text is centered in the list area.
  - [ ] Text color uses `disabledForeground()` (greyed out, not black).
  - [ ] Clearing the search field restores the full list.
- **🐞 Bug signals:** Empty state shows a blank white area with no text; "No tickets in sprint." appears while tickets are loading; text is same color as body text.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A.

---

### [SPR-20] "Jira not configured" and auth error states
- **Component(s):** `loadData()` error branch at `SprintDashboardPanel.kt:597–604`; `EmptyStatePanel`
- **Preconditions:** Can be reproduced by temporarily removing the Jira token in Settings, or by observing the initial state before configuration.
- **Steps:**
  1. Without configuring Jira (or by viewing in a project that has no board configured), open the Sprint tab.
  2. Observe status bar and list area.
- **Expected — visual:**
  - Status bar shows `"Error: <message>"` — the message should be human-readable (e.g. "401 Unauthorized" or "No board configured").
  - List is empty. Optionally a Settings shortcut link is visible.
- **Expected — behavioral:** No crash. Re-configuring the token and clicking Refresh recovers the tab.
- **✅ Checks (tick each):**
  - [ ] Status bar shows "Error: …" (not a raw exception stack).
  - [ ] List area shows empty state (not a partially rendered list).
  - [ ] Refresh button works after re-configuring the token.
- **🐞 Bug signals:** Tab crashes on startup; stack trace visible in the status bar; status bar stuck on "Loading sprint tickets…" indefinitely for an unconfigured state.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A.

---

## PR (Bitbucket)

### [PRT-1] Initial load — PR list and status bar
- **Component(s):** `PrDashboardPanel.startDataCollection()` at `PrDashboardPanel.kt:379`; `statusLabel`
- **Preconditions:** Bitbucket token configured; at least one open PR exists authored by the current user.
- **Steps:**
  1. Click the **PR** tab.
  2. Wait for the animated spinner to disappear.
- **Expected — visual:**
  - "My Pull Requests (N)" section header row appears with N matching the number of PR rows below it.
  - Status bar reads "N PRs loaded • Updated Xs ago".
  - Each PR row shows `#ID` in monospace bold blue, truncated title, `author → target` branch snippet, reviewer count (if any), relative time, and an outline status badge (`OPEN`/`MERGED`/`DECLINED`).
- **Expected — behavioral:** Polling starts automatically; the "Updated Xs ago" timestamp increments.
- **✅ Checks (tick each):**
  - [ ] "My Pull Requests (N)" header count equals the visible PR rows below it.
  - [ ] Status bar reads "N PRs loaded • Updated Xs ago".
  - [ ] Status badge for open PRs reads "OPEN" in the correct accent color.
  - [ ] No PR row shows `#-1` or `#-2` or `#-3` (those are section-header sentinel IDs).
- **🐞 Bug signals:** Status bar stuck on "Loading pull requests…"; section header shows "(0)" when PRs are visible; status badge shows raw "OPEN" text without border/color styling; `#-1` visible as a clickable row.
- **Theme/size matrix:** light + dark; narrow width (title truncated at 45 chars with ellipsis).
- **⛔ Write note:** N/A.

---

### [PRT-2] Role filter — My PRs / Reviewing / All
- **Component(s):** `myPrsToggle`, `reviewingToggle`, `allToggle` in `PrDashboardPanel.kt:61–64`; `refreshListView()` at line 436
- **Preconditions:** Bitbucket has open PRs in all three categories for the current user.
- **Steps:**
  1. Observe "My PRs" selected by default.
  2. Click **Reviewing** toggle.
  3. Click **All** toggle.
  4. Click **My PRs** toggle to return.
- **Expected — visual:**
  - Each toggle is visually distinct (selected = pressed/highlighted, unselected = flat).
  - My PRs: shows PRs where current user is author — section header "My Pull Requests (N)".
  - Reviewing: shows PRs where current user is a reviewer — section header "Reviewing (N)".
  - All: flat list with header "All Pull Requests (N)".
  - Toggle buttons are mutually exclusive (ButtonGroup).
- **Expected — behavioral:** Switching toggles does NOT trigger a new Bitbucket API call — it re-filters from the in-memory `currentMyPrs` / `currentReviewingPrs` / `currentAllRepoPrs` caches.
- **✅ Checks (tick each):**
  - [ ] Only one toggle is selected at a time.
  - [ ] "My PRs" section header changes text depending on active filter.
  - [ ] Reviewing toggle shows PRs where the current user is a reviewer.
  - [ ] All toggle shows a flat list without My/Reviewing sub-headers.
  - [ ] Counts in section headers match the visible PR rows.
- **🐞 Bug signals:** Multiple toggles appear selected simultaneously; switching to "Reviewing" shows the same PRs as "My PRs"; section header count does not match visible rows; switching filter triggers a visible spinner.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A.

---

### [PRT-3] State filter — Open / Merged / Declined
- **Component(s):** `openToggle`, `mergedToggle`, `declinedToggle` in `PrDashboardPanel.kt:67–70`; state toggle action listeners at lines 338–349
- **Preconditions:** Bitbucket has PRs in all three states.
- **Steps:**
  1. Observe "Open" selected by default.
  2. Click **Merged** toggle.
  3. Click **Declined** toggle.
  4. Click **Open** to return.
- **Expected — visual:**
  - Merged PRs: status badges show `MERGED` in purple/merged color; left-border accent in merged color.
  - Declined PRs: status badges show `DECLINED` in red/declined color.
  - Status bar shows "Loading merged PRs…" / "Loading declined PRs…" during each switch.
- **Expected — behavioral:** Each state toggle calls `PrListService.setState(PrState.X)` and triggers a service refresh (unlike the role filter, this DOES hit the API because it changes the query parameter).
- **✅ Checks (tick each):**
  - [ ] Status badge text is "MERGED" for merged PRs and "DECLINED" for declined PRs.
  - [ ] Left-border accent color matches status (purple for merged, red for declined, green/blue for open).
  - [ ] Status bar shows "Loading…" during switch.
  - [ ] Empty state "No pull requests merged." shown if no merged PRs.
- **🐞 Bug signals:** Merged PRs still show `OPEN` badges; state filter has no effect (same list shown); empty state label still says "No pull requests open." when showing merged results.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A.

---

### [PRT-4] Search / filter field in PR list
- **Component(s):** `searchField` in `PrListPanel.kt:49–55`; `applyFilter()` at line 227
- **Preconditions:** PR list loaded with at least 5 PRs.
- **Steps:**
  1. Type a partial PR title in the search field.
  2. Observe the filtered list.
  3. Type the author's display name.
  4. Clear the field.
- **Expected — visual:** Matching PRs remain; section headers are preserved only when their section has at least one match (per `applyFilter()` logic at line 231). Clearing restores all PRs.
- **Expected — behavioral:** 250 ms debounce (`filterDebounceTimer`). Filter matches: title, authorName, fromBranch, toBranch, repoName.
- **✅ Checks (tick each):**
  - [ ] Filter matches partial title (case-insensitive).
  - [ ] Filter matches author name.
  - [ ] Section header hidden when all its PRs are filtered out.
  - [ ] Clearing restores full list and section headers.
  - [ ] Selection is preserved when filter matches the currently selected PR.
- **🐞 Bug signals:** Filter removes section headers even when matches remain; clearing the field shows an empty list; filter is case-sensitive; author name filter does not work.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A.

---

### [PRT-5] Repo filter dropdown (multi-repo setup)
- **Component(s):** `repoFilter` in `PrDashboardPanel.kt:85–88`; `setupRepoFilter()` at line 203
- **Preconditions:** Multiple repos configured in Settings → Repositories. Each has a display label.
- **Steps:**
  1. Observe that the repo filter dropdown is visible ("All Repos" selected by default).
  2. Select a specific repo from the dropdown.
  3. Observe the filtered list.
  4. Return to "All Repos".
- **Expected — visual:**
  - Dropdown is visible ONLY when multiple repos are configured (hidden for single-repo setups).
  - Each PR row shows a colored repo name badge when the dropdown is visible.
  - Selecting a repo filters the list to only that repo's PRs.
- **Expected — behavioral:** Repo filter works in combination with role filter (My PRs / Reviewing / All).
- **✅ Checks (tick each):**
  - [ ] Dropdown hidden in single-repo projects.
  - [ ] Repo badge appears on PR rows in multi-repo setup.
  - [ ] Selecting a repo shows only that repo's PRs.
  - [ ] "All Repos" shows PRs from all configured repos.
- **🐞 Bug signals:** Dropdown visible in a single-repo setup; selecting a repo shows no PRs when that repo has open PRs; repo badge missing when multiple repos are configured; badge overflows the row height.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A.

---

### [PRT-6] PR selection → PrDetailPanel renders (overview)
- **Component(s):** `listPanel.onPrSelected` callback in `PrDashboardPanel.kt:229–293`; `PrDetailPanel.showPrDetail()` at line 362
- **Preconditions:** PR list loaded. Select an open PR.
- **Steps:**
  1. Click a PR row.
  2. Observe the right-hand detail panel.
- **Expected — visual:**
  - "Select a pull request to view details." placeholder replaced.
  - Header: bold 16pt title; `PR #NNN` in monospace blue; status badge (colored filled rectangle with white text); branch label `from → to`.
  - Reviewers row: "Reviewers:" label + avatar-chips. Each reviewer chip shows `✓ Name` (green, approved), `✗ Name` (red, needs work), or `○ Name` (grey, pending). For open PRs: a `× ` remove icon per reviewer and a `+ Add` link.
  - Build status badge row: colored badge (green/red/amber/grey) with icon prefix (`✓ `, `✗ `, `▶ `) + text. Badge has a hand cursor (clickable to open build URL).
  - Six toggle buttons: Description, Activity, Files, Commits, Comments, AI Review.
  - Description pane visible by default.
  - `← Back to list` link at the top.
- **Expected — behavioral:** `← Back to list` clears the list selection and returns to the empty state. Switching between PRs loads the new detail without showing stale data.
- **✅ Checks (tick each):**
  - [ ] Title text matches the selected PR's title in Bitbucket.
  - [ ] `PR #NNN` ID is monospace and matches the PR number.
  - [ ] Branch label shows `source-branch → target-branch`.
  - [ ] Status badge color: green fill for OPEN, purple for MERGED, red for DECLINED.
  - [ ] Reviewer chips show approval status via icon and color.
  - [ ] Build status badge renders (even if the text is "No build status").
  - [ ] Six toggle buttons are visible and mutually exclusive.
  - [ ] `← Back to list` returns to empty state + clears list selection.
- **🐞 Bug signals:** Title is "null" or blank; PR ID shows `#0`; branch label shows `? → ?`; reviewer chips missing on a PR with known reviewers; build badge absent from the detail header; clicking `← Back to list` does not clear the list selection.
- **Theme/size matrix:** light + dark; narrow detail panel (title and branch must wrap, not clip).
- **⛔ Write note:** N/A — read-only view.

---

### [PRT-7] PrDetailPanel — Description tab (view, edit, AI enhance)
- **Component(s):** `DescriptionSubPanel` in `PrDetailPanel.kt:1430–1667`; `markdownToHtml()` at line 1539
- **Preconditions:** Select a PR with a multi-paragraph markdown description.
- **Steps:**
  1. Select the PR and observe the Description tab (default).
  2. Click **Edit** button.
  3. Observe the edit textarea.
  4. Click **Cancel** (not Update).
  5. Click **Enhance with AI** (if Sourcegraph configured — if not, verify tooltip changes to "AI service is not available").
- **Expected — visual:**
  - Markdown rendered as HTML: `**bold**` → `<b>bold</b>`, newlines → `<br>`, links are clickable.
  - Edit mode: a raw-text textarea with the original markdown, `Update` and `Cancel` buttons.
  - Cancel returns to the rendered view without changes.
  - Enhance with AI: button becomes "Generating…" (disabled) while AI runs; on completion, textarea opens with AI text; streaming partial text visible in textarea as it arrives.
  - If description is blank: "No description provided." label shown; Edit and Enhance with AI buttons still visible.
- **Expected — behavioral:** Double-clicking the title label (on an OPEN PR) enters title-edit mode — separate from the description edit (see `TicketDetailPanel.kt:637`).
- **✅ Checks (tick each):**
  - [ ] Markdown rendered visually (bold, newlines, links).
  - [ ] Edit mode shows raw markdown source.
  - [ ] Cancel returns to rendered view.
  - [ ] "No description provided." shown for PRs with blank description.
  - [ ] AI button disabled during generation.
- **🐞 Bug signals:** Description shows raw markdown syntax (asterisks, hashes); Edit mode textarea is empty; Cancel triggers "Failed to update description" error; "Enhance with AI" crashes; AI text appears in a separate dialog instead of the textarea.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** **Update** button (after editing description) → **SKIP-EXECUTION (write op — updates Bitbucket PR description) — verify textarea contents, click Cancel instead of Update**. **Enhance with AI** itself is local-generation only (no Bitbucket write until Update is clicked) — OK to click Run, but do not click Update after.

---

### [PRT-8] PrDetailPanel — Activity tab (inline + general comments, reply)
- **Component(s):** `ActivitySubPanel.showActivities()` in `PrDetailPanel.kt:1794`; `PrActivityGrouping.partition()` / `groupInlineByAnchor()`; comment input panel
- **Preconditions:** Select a PR with both inline code comments and general activity (approved, commented).
- **Steps:**
  1. Click **Activity** toggle button.
  2. Observe the activity feed.
  3. Expand a file:line header to see inline comment threads.
  4. Click "Reply" on a comment to open the inline reply field.
  5. **⛔ SKIP-EXECUTION:** type a reply but do not click Send.
- **Expected — visual:**
  - Inline comments grouped under a `filename.kt:N` file:line header (hand cursor, tool tip).
  - Each inline comment row is indented (24dp left padding) relative to the file header.
  - General activities (approved, updated, merged) appear below inline groups.
  - Each activity row shows: author name, action verb ("commented", "approved", "merged"…), timestamp.
  - "Reply" link below each comment; clicking it expands into a `↳` input row with a Send button.
  - A comment input bar at the bottom of the activity panel.
  - A thin separator between file-comment groups.
- **Expected — behavioral:**
  - Clicking the file:line header navigates the IDE editor to that file and line (ReadOnly mode shows a notification instead).
  - Reply field is hidden by default; clicking "Reply" shows it.
- **✅ Checks (tick each):**
  - [ ] Inline comments appear under file:line headers.
  - [ ] File header shows filename (not full path) and line number.
  - [ ] General activities are shown after inline comment groups.
  - [ ] Action verb is human-readable ("commented", "approved", not "COMMENTED").
  - [ ] Reply link expands to show reply input field.
  - [ ] Comment input at bottom of the panel is present.
- **🐞 Bug signals:** All comments appear as general (no inline grouping); file:line header shows full path instead of filename; action verb shows raw uppercase "COMMENTED"; Reply link invisible; separator between file groups missing.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** Reply Send button, bottom-bar Send button → **SKIP-EXECUTION (write op — posts Bitbucket comment) — type text to verify field accepts input, do not click Send**.

---

### [PRT-9] PrDetailPanel — Files tab
- **Component(s):** `FilesSubPanel` in `PrDetailPanel.kt` (after line 2077)
- **Preconditions:** Select a PR that has at least one changed file.
- **Steps:**
  1. Click **Files** toggle.
  2. Observe the list of changed files.
  3. Double-click a file row to open the diff viewer.
- **Expected — visual:**
  - List of changed files: each row shows the file path (or `FileCellRenderer`-truncated path) and a change indicator.
  - Double-clicking opens IntelliJ's built-in diff viewer (`DiffManager.getInstance().showDiff()`).
- **Expected — behavioral:** Diff opens in a floating or embedded diff window. In ReadOnly mode (when the local branch does not match the PR's source branch), file navigation emits a notification.
- **✅ Checks (tick each):**
  - [ ] At least one file row visible for a PR with changes.
  - [ ] Double-clicking a file opens the diff viewer.
  - [ ] Diff viewer shows before/after content (not a blank panel).
- **🐞 Bug signals:** Files tab shows no rows for a PR with known changes; double-click has no effect; diff viewer opens but shows identical before/after content for a PR that has changes.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A — diff is read-only.

---

### [PRT-10] PrDetailPanel — Commits tab
- **Component(s):** `CommitsSubPanel` in `PrDetailPanel.kt`; `commitsToggle.addActionListener` at line 742
- **Preconditions:** Select a PR with at least two commits.
- **Steps:**
  1. Click **Commits** toggle.
  2. Observe the commits list.
- **Expected — visual:** Each commit row shows the short SHA, author, message, and timestamp.
- **Expected — behavioral:** Commits are loaded on first tab click (`commitsSubPanel.showCommits(it)` at line 743), not on PR selection (lazy). Switching away and back does not re-fetch.
- **✅ Checks (tick each):**
  - [ ] Commit list populates after clicking the Commits toggle.
  - [ ] Short SHA is visible and non-empty.
  - [ ] Author name is human-readable.
  - [ ] Commits are in reverse-chronological order (most recent first).
- **🐞 Bug signals:** Commits tab shows empty list for a PR with known commits; commit rows missing SHA; commits in wrong order.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A.

---

### [PRT-11] PrDetailPanel — Comments tab (CommentsTabPanel)
- **Component(s):** `CommentsTabPanel` at `CommentsTabPanel.kt`; `rebuildCommentsTab()` at `PrDetailPanel.kt:555`
- **Preconditions:** Select a PR with at least one general comment.
- **Steps:**
  1. Click **Comments** toggle.
  2. Wait for comments to load.
  3. Select a comment row; observe "Toggle Resolved" button state.
  4. Click "Refresh" button.
  5. **⛔ SKIP-EXECUTION:** type in the "Add general comment:" area but do not click Post.
- **Expected — visual:**
  - Toolbar: "Refresh" button + status label ("N comment(s)" after load).
  - List of comments rendered by `CommentRowRenderer`.
  - Below list: "Reply" and "Toggle Resolved" buttons (Toggle Resolved disabled when no selection or when the selected comment is not transitionable).
  - Bottom area: "Add general comment:" label, text area, "Post" button.
  - Status label shows "Loading…" during fetch.
- **Expected — behavioral:**
  - SmartPoller auto-refreshes comments every 30 s while the tab is visible.
  - Switching to another PR rebuilds the CommentsTabPanel (new instance via `rebuildCommentsTab`).
  - "Toggle Resolved" only enabled when a transitionable comment is selected (server-side authorship guard: `permittedOperations.transitionable`).
- **✅ Checks (tick each):**
  - [ ] Comments load (status label changes from "Loading…" to "N comment(s)").
  - [ ] Each comment row shows author name, content, and state (OPEN/RESOLVED).
  - [ ] "Toggle Resolved" is disabled when no comment is selected.
  - [ ] "Toggle Resolved" is enabled only for comments where `transitionable=true`.
  - [ ] Post text area accepts text input.
- **🐞 Bug signals:** Comments tab always shows "Loading…"; Toggle Resolved enabled for all comments regardless of ownership; Post button triggers a Bitbucket call when the user has not clicked it; comment rows show raw JSON.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** Post button → **SKIP-EXECUTION (write op — posts Bitbucket general comment)**; Reply button → **SKIP-EXECUTION (write op — posts Bitbucket reply comment)**; Toggle Resolved → **SKIP-EXECUTION (write op — toggles comment resolved state)**. For all three: verify the relevant UI controls, then dismiss without clicking.

---

### [PRT-12] PrDetailPanel — AI Review tab (AiReviewTabPanel)
- **Component(s):** `AiReviewTabPanel` at `AiReviewTabPanel.kt`; `rebuildAiReviewTab()` at `PrDetailPanel.kt:419`; `runAiReview()` at line 456
- **Preconditions:** Select an open PR.
- **Steps:**
  1. Click **AI Review** toggle.
  2. If no session exists: verify the status label reads "No AI review run for this PR yet. Click 'Run AI review' to start."
  3. Click **Run AI review** button.
  4. Verify the confirmation dialog appears: "Start a new agent session to review PR-N? (You'll be switched to the Agent tab.)"
  5. **⛔ SKIP-EXECUTION:** click **No** in the confirmation dialog.
- **Expected — visual (no session):**
  - Status label: "No AI review run for this PR yet. Click 'Run AI review' to start."
  - Empty finding list.
  - Toolbar buttons: Run AI review, Refresh, Push selected, Push all kept, Discard selected.
  - "Push selected" and "Push all kept" are inert (no `viewModel` bound yet).
- **Expected — visual (session exists):**
  - Status label: "N findings (session UUID, running/done)".
  - Finding rows rendered by `FindingRowRenderer`.
  - "Push selected" enabled when a row is selected.
- **Expected — behavioral:** "Run AI review" always shows the confirmation dialog before starting an agent session. AI review generates findings **locally** via the agent — it does NOT post comments to Bitbucket unless the user clicks "Push selected" or "Push all kept". Therefore "Run AI review" is borderline: it starts an agent session (write to session storage) but does not write to Bitbucket. Confirm dialog allows safe cancellation.
- **✅ Checks (tick each):**
  - [ ] Status label "No AI review run…" on first open.
  - [ ] Confirmation dialog appears on "Run AI review" click.
  - [ ] Confirmation dialog shows the correct PR number.
  - [ ] Clicking No closes the dialog and no agent session is started.
  - [ ] Findings list is empty until a review session completes.
- **🐞 Bug signals:** AI Review tab missing; status label shows raw exception; "Run AI review" starts an agent session without showing the confirmation dialog; confirmation dialog does not show the PR number; "Push selected" / "Push all kept" enabled with no findings.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** "Run AI review" → confirmation dialog "Start a new agent session to review PR-N?" → **SKIP-EXECUTION for the session start (agent + session storage write) — verify the dialog wording + PR number match, then click No**. "Push selected" / "Push all kept" → **SKIP-EXECUTION (write op — posts inline comments to Bitbucket) — do not click if findings are present**.

---

### [PRT-13] Action buttons — Approve / Needs Work (LABEL CHECK ONLY — ⚠ no dialog)
- **Component(s):** `approveButton`, `needsWorkButton` in `PrDetailPanel.kt:209–225`; action listeners at lines 764–921
- **Preconditions:** Select an open PR where the current user has reviewer rights. The approve button should read "Approve" (or "Unapprove" if already approved).
- **🚨 DANGER — NO CONFIRMATION DIALOG:** Approve / Unapprove / Needs Work call the Bitbucket API **immediately on click** — there is **no** dialog and **no undo**. This scenario is **read the labels only**. Do NOT hover-then-click; do not "just see what the dialog says" (there is none). Keep the mouse away from these buttons while inspecting.
- **Steps:**
  1. Select an open PR.
  2. **Read** (do not click) `approveButton` — it should say "Approve" or "Unapprove" (matching server state).
  3. **Read** `needsWorkButton` — it should say "Needs Work".
  4. **⛔ SKIP-EXECUTION:** do not click either button under any circumstance.
- **Expected — visual:**
  - Both buttons visible and enabled for an open PR.
  - Both buttons disabled for MERGED or DECLINED PRs (set at `PrDetailPanel.kt:1006–1009`).
  - After approval (if the test user has previously approved), button reads "Unapprove" with undo icon.
- **Expected — behavioral:** No confirm dialog for Approve — it fires immediately on click. Needs Work fires immediately on click (resolves current user then calls `setNeedsWork`).
- **✅ Checks (tick each):**
  - [ ] Approve button enabled for open PRs, disabled for merged/declined.
  - [ ] Button label reflects actual approval state from server (Approve vs Unapprove).
  - [ ] Needs Work button enabled for open PRs, disabled for merged/declined.
- **🐞 Bug signals:** Both buttons always disabled; button stays "Approve" even when the current user has approved the PR; buttons enabled on a MERGED PR.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** Approve / Unapprove / Needs Work buttons → **SKIP-EXECUTION (write op — Bitbucket approval API) — verify button state and label match server, do not click**.

---

### [PRT-14] Action buttons — Merge (verify Merge Options dialog, skip execution)
- **Component(s):** `mergeButton` in `PrDetailPanel.kt:213–216`; merge action listener at line 810; `MergeOptionsDialog`; `MergeButtonStateDeriver`
- **Preconditions:** Select an open PR. The merge button state depends on `MergeButtonStateDeriver.derive(mergeStatus)`.
- **Steps:**
  1. Select an open PR.
  2. Observe the merge button: icon (Merge or Warning), tooltip (if merge vetoed).
  3. If the button is enabled: click **Merge** to open the `MergeOptionsDialog`.
  4. Inspect the dialog: merge strategy combo, commit message field, delete source branch checkbox.
  5. **⛔ SKIP-EXECUTION:** click Cancel.
- **Expected — visual:**
  - If mergeable: Merge icon, no tooltip.
  - If conflicted: Warning icon, tooltip explains conflict.
  - If vetoed (e.g. missing approvals): Warning icon, tooltip lists veto reasons (HTML-escaped).
  - Dialog: strategy dropdown (e.g. "no-ff", "squash"), commit message text field (pre-filled or empty), delete-source-branch checkbox.
- **Expected — behavioral:** Button force-disabled when `MergeButtonStateDeriver.State.forceDisable=true`.
- **✅ Checks (tick each):**
  - [ ] Merge button shows Merge icon when PR is cleanly mergeable.
  - [ ] Merge button shows Warning icon when conflicts or vetoes exist.
  - [ ] Tooltip is readable (not raw HTML like `&amp;`).
  - [ ] MergeOptionsDialog shows at least one strategy option.
  - [ ] Cancel closes the dialog with no notification.
- **🐞 Bug signals:** Merge button icon never changes regardless of merge status; tooltip shows raw HTML entities; dialog has no strategy options; Cancel triggers a "Merge Failed" notification.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** MergeOptionsDialog OK button → **SKIP-EXECUTION (write op — merges the Bitbucket PR) — verify dialog fields, click Cancel**.

---

### [PRT-15] Action buttons — Decline (verify confirm dialog, skip execution)
- **Component(s):** `declineButton` in `PrDetailPanel.kt:219–220`; decline action listener at line 861
- **Preconditions:** Select an open PR.
- **Steps:**
  1. Click **Decline**.
  2. Verify the confirmation dialog: "Decline PR #N?" with a warning icon.
  3. **⛔ SKIP-EXECUTION:** click **No** in the dialog.
- **Expected — visual:** IntelliJ `showYesNoDialog` appears with title "Confirm Decline" and message "Decline PR #N?". Warning icon visible.
- **Expected — behavioral:** Clicking No: dialog closes, no status change, no notification. Clicking Yes (do not): triggers decline API call; on success, all action buttons disable.
- **✅ Checks (tick each):**
  - [ ] Confirmation dialog appears on Decline click.
  - [ ] Dialog title is "Confirm Decline".
  - [ ] Dialog body shows the PR number.
  - [ ] Clicking No closes dialog and PR remains open in the list.
- **🐞 Bug signals:** Decline fires immediately without a dialog; dialog body shows `#0` instead of the actual PR number; clicking No triggers a decline notification.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** Decline → "Decline PR #N?" dialog → **SKIP-EXECUTION (write op — Bitbucket PR decline) — verify dialog wording, click No**.

---

### [PRT-16] Open in Browser button
- **Component(s):** `openInBrowserButton` in `PrDetailPanel.kt:225–228`; action listener at line 929
- **Preconditions:** Select a PR in any state. Bitbucket URL is configured.
- **Steps:**
  1. Click **Open in Browser**.
  2. Observe which URL opens in the default browser.
- **Expected:** Browser opens `<bitbucketUrl>/projects/<projectKey>/repos/<repoSlug>/pull-requests/<prId>`. For multi-repo setups, the URL uses the PR's own repo coordinates (not the project default).
- **✅ Checks (tick each):**
  - [ ] Browser opens on click.
  - [ ] URL contains the correct PR number.
  - [ ] URL uses the PR's own `projectKey`/`repoSlug` (verifiable in the address bar; cross-check with the repo badge on the PR row).
- **🐞 Bug signals:** Browser does not open; URL contains the wrong project/repo key (e.g. the default project's key instead of the PR's own repo); URL format is malformed.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A — Opens browser only (read-only view).

---

### [PRT-17] Create PR button — verify dialog, skip execution
- **Component(s):** `CreatePrAction` in `PrDashboardPanel.kt:506–524`; `CreatePrLauncherImpl` → `CreatePrPrefetch` → `CreatePrDialog`
- **Preconditions:** Current local branch has commits not merged to the default branch.
- **Steps:**
  1. Click the **Create PR** (plus sign) toolbar button.
  2. Wait for the prefetch (branch list, ticket keys, default reviewers) to complete.
  3. Observe the `CreatePrDialog`.
  4. **⛔ SKIP-EXECUTION:** click Cancel.
- **Expected — visual:**
  - Title field pre-populated from the branch name or active Jira ticket.
  - Description area empty (AI generate button present if `enableAiTitleGeneration=true`).
  - Source branch = current local branch.
  - Target branch = configured default target branch.
  - Reviewers: chips for any default reviewers resolved from Bitbucket.
  - Ticket chip input (pre-filled from active ticket if available).
- **Expected — behavioral:** Clicking Cancel with an empty description returns without posting. If the "AI sparkle" icon is present, clicking it generates a description (does not post until "Create" is clicked).
- **✅ Checks (tick each):**
  - [ ] Dialog opens (or shows "unavailable" warning if EP not registered).
  - [ ] Source branch field shows the current git branch.
  - [ ] Title field is not blank (auto-filled from branch/ticket).
  - [ ] Cancel closes dialog with no notification.
- **🐞 Bug signals:** Dialog has a blank source branch; title is empty with no auto-fill; Cancel triggers a "PR created" notification; dialog crashes with a NullPointerException.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** CreatePrDialog "Create" button → **SKIP-EXECUTION (write op — creates a new Bitbucket PR) — verify prefilled fields, click Cancel**.

---

### [PRT-18] Auto-select on branch switch
- **Component(s):** `tryAutoSelect()` in `PrDashboardPanel.kt:549`; `BranchChangeListener` at line 324
- **Preconditions:** A feature branch that has an open PR is currently checked out locally.
- **Steps:**
  1. Open the PR tab while the feature branch is checked out.
  2. Observe whether a PR is automatically selected.
  3. Switch to a different local branch (e.g. `main`) and return to the PR tab.
- **Expected — behavioral:**
  - On first tab open: if the current branch matches a PR's source branch, that PR is auto-selected and the detail panel populates.
  - Auto-select fires once per branch session (`autoSelectDone=true` flag).
  - On branch switch back to `main`, no auto-select (main has no open PR).
  - On branch switch to the feature branch again, auto-select re-fires (flag reset on `branchHasChanged`).
- **✅ Checks (tick each):**
  - [ ] PR matching the current branch is selected on initial load.
  - [ ] Detail panel populates without manual click.
  - [ ] After branch switch to main, the PR detail panel clears or shows the prior selection.
  - [ ] Switching back to the feature branch re-selects the matching PR.
- **🐞 Bug signals:** PR never auto-selects despite a matching open PR; auto-select picks the wrong PR; auto-select fires more than once per branch session (list flickers); auto-select does not reset after branch switch.
- **Theme/size matrix:** N/A (behavioral).
- **⛔ Write note:** N/A.

---

### [PRT-19] Empty state — no PRs open
- **Component(s):** `PrListPanel.showEmpty()` at `PrListPanel.kt:135`; `emptyStateLabel`, `emptyStateRefreshLink`
- **Preconditions:** Switch to the "Reviewing" filter when the current user is not a reviewer on any open PR; or switch to "Declined" with no declined PRs.
- **Steps:**
  1. Click the Reviewing toggle (or switch to Declined state).
  2. If no PRs exist for that filter, observe the empty state.
  3. Click the **Refresh** action link in the empty state.
- **Expected — visual:**
  - "No pull requests reviewing." (or "open." / "merged." / "declined." depending on state) centered label in greyed-out `disabledForeground()` color.
  - A clickable "Refresh" link below the label.
- **Expected — behavioral:** Clicking "Refresh" triggers `PrListService.refresh()` (wired via `onRefreshClicked` at `PrDashboardPanel.kt:302`).
- **✅ Checks (tick each):**
  - [ ] Empty state text says "No pull requests &lt;stateWord&gt;." not a generic "No pull requests open." regardless of active filter.
  - [ ] Text color is `disabledForeground()` (greyed, not black).
  - [ ] "Refresh" link is clickable (cursor changes to hand).
  - [ ] Clicking Refresh triggers a load cycle (spinner appears in status bar).
- **🐞 Bug signals:** Empty state always reads "No pull requests open." even when filtering by Merged; Refresh link missing; Refresh link click has no visible effect.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A.

---

### [PRT-20] "Bitbucket not configured" state
- **Component(s):** Error handling in `PrDashboardPanel.startDataCollection()` → `PrListService.refresh()` path; `EmptyStatePanel`
- **Preconditions:** Simulate by temporarily removing the Bitbucket token in Settings, then refreshing.
- **Steps:**
  1. Remove the Bitbucket token.
  2. Click Refresh.
  3. Observe the status bar and list area.
- **Expected — visual:** Status bar shows an error or "0 PRs loaded"; list shows empty state (not a crash).
- **Expected — behavioral:** Re-adding the token and clicking Refresh recovers the tab.
- **✅ Checks (tick each):**
  - [ ] No crash or unhandled exception dialog.
  - [ ] Status bar updates after Refresh with a token present.
  - [ ] Empty state label appears (not a blank white area).
- **🐞 Bug signals:** NullPointerException in IDE log; spinning never stops; status bar stuck on "Loading pull requests…" indefinitely.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** N/A.

---

*End of section 06 — Connector Tabs: Sprint (Jira) & PR (Bitbucket).*
