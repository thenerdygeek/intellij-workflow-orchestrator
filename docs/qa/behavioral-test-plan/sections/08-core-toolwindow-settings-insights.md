# Core Shell — Tool Window, Settings, Insights, Theming

This section covers every user-visible surface provided by the `:core` module: the "Workflow"
tool window (factory, tabs, active-ticket bar, title and gear actions), empty-state panels,
the four core settings pages, the Insights sub-tab set, onboarding, gutter coverage markers,
SVG icons, and the full light/dark theme matrix. Scenarios reference source at the cited
`file:line` to prevent invented-feature drift.

Run environment: licensed **Windows IntelliJ Ultimate** via `./gradlew runIde`. All settings
(URLs, tokens) are pre-configured unless a scenario explicitly requires a blank-state start.

---

## Tool Window — Structure and Tab Loading

### [CORE-01] Tool window opens with Sprint tab eagerly loaded
- **Component(s):** `WorkflowToolWindowFactory` (`core/.../toolwindow/WorkflowToolWindowFactory.kt:349-396`), `LazyTabPlaceholder`
- **Preconditions:** Plugin installed; project open; any Sprint/Jira configuration present so a real `SprintTabProvider` panel is created.
- **Steps:**
  1. Open the IDE and wait for project indexing to finish.
  2. Click the "Workflow" stripe button (bottom dock) to open the tool window.
  3. Observe which tab is selected and what its content is.
  4. Note all tab labels in the tab bar.
- **Expected — visual:** The tool window opens with the **Sprint** tab selected and showing real Sprint content (not "Loading..."). The tab bar contains exactly 8 entries: Sprint, PR, Build, Quality, Automation, Handover, Agent, Insights (in that order).
- **Expected — behavioral:** Only Sprint's panel is fully constructed on open; all other tabs contain a lightweight "Loading..." placeholder (`LazyTabPlaceholder` at line 445) and are not materialized until clicked. No Chromium/JCEF process spawns until the Agent tab is first clicked.
- **✅ Checks (tick each):**
  - [ ] Sprint tab selected and shows real panel content on open
  - [ ] Exactly 8 tabs visible in order: Sprint, PR, Build, Quality, Automation, Handover, Agent, Insights
  - [ ] Clicking PR tab replaces "Loading..." with real PR panel (log line `[Workflow:UI] Lazy-loaded tab: PR` in idea.log)
  - [ ] Agent tab does not spawn a browser process until first click
  - [ ] Tab close buttons absent (all tabs are non-closeable)
- **🐞 Bug signals:** Fewer than 8 tabs → EP registration failure; Agent tab materializes immediately on open → lazy guard broken; "Loading..." never replaced → `selectionChanged` listener not firing.
- **Theme/size matrix:** light + dark; narrow (≈200 px) vs wide (≈600 px) — verify tab labels don't truncate to unreadable lengths.
- **⛔ Write note:** None.

---

### [CORE-02] Lazy tab materialization on first click for each tab
- **Component(s):** `WorkflowToolWindowFactory` (`selectionChanged` listener at line 64-83)
- **Preconditions:** Tool window just opened (Sprint tab active, all others un-materialized).
- **Steps:**
  1. Click the **PR** tab; wait up to 3 seconds.
  2. Click the **Build** tab; wait.
  3. Repeat for Quality, Automation, Handover.
  4. Click the **Agent** tab and wait for the JCEF webview to initialise (spinner/chat UI appears).
  5. Click the **Insights** tab.
- **Expected — visual:** Each tab transitions from "Loading..." to its real panel on first click only. Subsequent clicks to the same tab show the already-built panel immediately (no second "Loading...").
- **Expected — behavioral:** `rebuildInProgress` guard prevents phantom materialization during a concurrent rebuild. `content.setDisposer(realPanel)` is wired for Disposable panels so future rebuilds clean up correctly.
- **✅ Checks (tick each):**
  - [ ] Each of the 7 non-Sprint tabs transitions from "Loading..." to real content exactly once
  - [ ] Re-clicking the same tab after materialization shows the same panel without flicker
  - [ ] Agent tab shows JCEF chat UI (not a blank white panel)
  - [ ] idea.log shows `[Workflow:UI] Lazy-loaded tab: <title>` for each tab on first click
- **🐞 Bug signals:** "Loading..." persists forever → `selectionChanged` not firing or `materializeByTitle` returning null; double "Loading..." flash → listener registered more than once (stacked rebuild).
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None.

---

### [CORE-03] Tab selection persists across tool window hide/show
- **Component(s):** `WorkflowToolWindowFactory` (`toolWindowShown` listener at line 96-112)
- **Preconditions:** All services configured; tool window open; all tabs materialized.
- **Steps:**
  1. Click the **Build** tab.
  2. Close the tool window (click X or stripe button).
  3. Reopen the tool window.
  4. Observe which tab is selected.
  5. Now change a URL in Settings → Connections → Apply → reopen tool window.
- **Expected — visual:** After a plain hide/show the Build tab is selected. After a settings change that mutates the `settingsSnapshot`, tabs are rebuilt and the previously selected tab is restored.
- **Expected — behavioral:** `settingsSnapshot()` compares URL fields + board IDs + sonarKey + EP provider count. If unchanged, no rebuild occurs; if changed, `rebuildTabs()` runs and the selected tab name is restored by `displayName` match.
- **✅ Checks (tick each):**
  - [ ] Same tab still selected after hide/show with no settings change
  - [ ] After a URL change in Settings → Apply, tabs rebuild and the previously active tab is still selected
  - [ ] After rebuild, lazy tabs that were already materialized are re-materialised on next click (materializedTabs cleared at line 354)
- **🐞 Bug signals:** Tab resets to Sprint after every reopen → snapshot guard not working; tabs not rebuilt after URL change → snapshot comparison missed a field.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None.

---

### [CORE-04] Active ticket bar — visibility, content, and Sprint navigation
- **Component(s):** `WorkflowToolWindowFactory.setupActiveTicketBar` (line 119-168)
- **Preconditions:** A Jira ticket is set as the active ticket (e.g. via the Sprint tab "Start Work" action so `activeTicketFlow` emits a non-null value).
- **Steps:**
  1. Set an active ticket via the Sprint tab.
  2. Observe the top bar of the tool window.
  3. Read the displayed ticket key and summary.
  4. Click the bar.
  5. Via the gear menu, click **Clear Active Ticket**.
  6. Observe the bar.
- **Expected — visual:** When a ticket is active: a blue (`INFO_BG`) banner appears at the very top of the tool window (above the tab strip) showing a tag icon, the Jira key in bold, and the summary in secondary-text colour. After clearing: the banner becomes invisible.
- **Expected — behavioral:** Clicking the banner selects the Sprint tab regardless of which tab was active. "Clear Active Ticket" in the gear menu sets `activeTicketId` + `activeTicketSummary` to empty and hides the bar.
- **✅ Checks (tick each):**
  - [ ] Banner visible when a ticket is active; hidden when none
  - [ ] Ticket key and summary match the values on the Sprint tab
  - [ ] Clicking the banner navigates to Sprint tab
  - [ ] "Clear Active Ticket" in gear menu hides the banner immediately
  - [ ] After IDE restart with ticket persisted in XML, banner reappears on open
- **🐞 Bug signals:** Banner always visible (stuck) → `activeTicketFlow` collector not firing; banner invisible despite active ticket → `bar.parent?.revalidate()` not called; clicking bar selects wrong tab → displayName mismatch.
- **Theme/size matrix:** light + dark — `INFO_BG` is `JBColor(#E3F2FD, #1E3A5F)` — verify sufficient contrast in both themes.
- **⛔ Write note:** None.

---

### [CORE-05] Title bar actions — Refresh, Open in Jira, Settings, Next Tab
- **Component(s):** `WorkflowToolWindowFactory.setupTitleActions` (line 175-226)
- **Preconditions:** Jira URL + active ticket configured.
- **Steps:**
  1. Click the **Refresh** icon (⟳) in the tool window title bar; check idea.log.
  2. Click the **Open in Jira** (globe) icon with an active ticket set; a browser tab should open.
  3. Click the **Open in Jira** icon with no active ticket set; observe the icon state.
  4. Click the **Settings** (⚙) icon; observe what opens.
  5. Click the **Next Tab** (→) icon repeatedly to cycle through all tabs.
- **Expected — visual:** Refresh icon always enabled. Open in Jira icon is greyed out when no active ticket or no Jira URL configured. Settings opens the Workflow Orchestrator settings dialog. Next Tab cycles linearly wrapping around index 0 after the last tab.
- **Expected — behavioral:** Refresh logs `[Workflow:UI] Refresh requested for tab: <title>` but does NOT trigger a data reload (the action body is log-only at line 182 — the actual data refresh is per-tab). Open in Jira constructs URL `<jiraUrl>/browse/<ticketId>`.
- **✅ Checks (tick each):**
  - [ ] Refresh icon present and clickable; triggers log entry (no exception)
  - [ ] Open in Jira icon disabled when activeTicketId is blank or jiraUrl is blank
  - [ ] Open in Jira opens `<jiraUrl>/browse/<ticketId>` in the default browser when both are set
  - [ ] Settings icon opens the IntelliJ Settings dialog at "Workflow Orchestrator"
  - [ ] Next Tab cycles through all 8 tabs, wrapping from last back to first
- **🐞 Bug signals:** Open in Jira fires with blank URL → enabled-state update thread not running; Next Tab skips indices → modulo arithmetic off by one.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** Opening browser (Open in Jira) is read-only navigation — not a backend write.

---

### [CORE-06] Gear menu — Refresh All Tabs and browser shortcut actions
- **Component(s):** `WorkflowToolWindowFactory.setupGearActions` (line 233-327)
- **Preconditions:** Jira, SonarQube, and Bamboo URLs configured; Build tab currently selected.
- **Steps:**
  1. Open the gear (wrench) dropdown from the tool window title bar.
  2. Verify all menu items are present: Settings, separator, Refresh All Tabs, Clear Active Ticket, separator, Open Jira Board, Open SonarQube, Open Bamboo.
  3. Click **Refresh All Tabs**; observe tab behaviour.
  4. Click **Open Jira Board**; verify browser opens to `.../secure/RapidBoard.jspa`.
  5. Remove the Bamboo URL in settings; reopen gear menu; observe Open Bamboo enabled state.
- **Expected — visual:** Gear dropdown matches the exact item list. Open Jira Board / Open SonarQube / Open Bamboo are greyed when the respective URL is blank. Refresh All Tabs rebuilds tabs and restores the selected tab name.
- **Expected — behavioral:** After Refresh All Tabs the previously selected tab (Build) is still selected. Enabled-state updates for URL-gated actions run on BGT (`ActionUpdateThread.BGT`).
- **✅ Checks (tick each):**
  - [ ] All 8 gear items/separators present in exact order from source
  - [ ] Refresh All Tabs restores selected tab identity after rebuild
  - [ ] Open Jira Board opens `<jiraUrl>/secure/RapidBoard.jspa`
  - [ ] Open SonarQube opens `<sonarUrl>` trimmed of trailing slash
  - [ ] Open Bamboo disabled when bambooUrl is blank
- **🐞 Bug signals:** Missing menu items → `gearGroup.add()` order changed; Open Bamboo enabled with blank URL → `update()` not called.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** Browser opens are navigation — no backend writes.

---

## Empty-State Panels

### [CORE-07] Empty state panels — one per unconfigured connector
- **Component(s):** `EmptyStatePanel` (`core/.../toolwindow/EmptyStatePanel.kt`), `WorkflowToolWindowFactory.materializeTab` (line 424-439)
- **Preconditions:** Clear all service URLs in Settings → Connections → Apply (or test with a fresh project). Re-open each tab.
- **Steps:**
  1. With all URLs blank, open each of the 6 connector tabs (Sprint, PR, Build, Quality, Automation, Handover).
  2. Read the empty-state message on each.
  3. Click the **Open Settings** button on the Sprint tab empty state.
  4. Configure Jira, Apply, hide and reopen the tool window; click the Sprint tab.
- **Expected — visual:** Each tab shows its configured empty message centred with `disabledForeground` colour and an "Open Settings" button beneath. Messages per tab (from `defaultTabs` at line 335-342):
  - Sprint: "No tickets assigned. Connect to Jira in Settings to get started."
  - PR: "No pull requests found. Connect to Bitbucket in Settings."
  - Build: "No builds found. Push your changes to trigger a CI build."
  - Quality: "No quality data available. Connect to SonarQube in Settings."
  - Automation: "Automation suite not configured. Set up Bamboo in Settings."
  - Handover: "No active task to hand over. Start work on a ticket first."
- **Expected — behavioral:** "Open Settings" button on any empty-state panel opens Settings → "Workflow Orchestrator" (`ShowSettingsUtil.showSettingsDialog(..., "Workflow Orchestrator")`). After URL is configured and tabs are rebuilt (tool-window shown with changed snapshot), the real tab panel replaces the empty state.
- **✅ Checks (tick each):**
  - [ ] Each of the 6 tabs shows the exact empty-state message from source
  - [ ] "Open Settings" button present on each empty-state panel
  - [ ] Clicking "Open Settings" opens the correct Settings page
  - [ ] After configuring Jira URL + Apply + reopening tool window, Sprint no longer shows empty state
  - [ ] Empty-state label text is centred and rendered via HTML (newline becomes `<br>`)
- **🐞 Bug signals:** Wrong message → `emptyMessage` mismatch in `defaultTabs`; button missing → layout issue in `EmptyStatePanel`; settings not opening → `project` reference stale.
- **Theme/size matrix:** light + dark; narrow window — verify HTML center wraps correctly.
- **⛔ Write note:** None.

---

## Settings — Root and Connections

### [CORE-08] Settings open paths — multiple entry points resolve to same dialog
- **Component(s):** `WorkflowSettingsConfigurable` (`core/.../settings/WorkflowSettingsConfigurable.kt`), `ConnectionsConfigurable`
- **Preconditions:** Plugin installed and tool window visible.
- **Steps:**
  1. Open Settings via **File → Settings → Tools → Workflow Orchestrator**.
  2. Close. Open via the **⚙ icon** in the tool window title bar.
  3. Close. Open via **Open Settings** on an empty-state panel.
  4. Close. Open via the gear menu → **Settings**.
  5. On each open, verify the left-panel tree shows sub-pages.
- **Expected — visual:** All four paths open the same IntelliJ Settings dialog with "Workflow Orchestrator" node selected. The left tree under that node shows sub-pages including at minimum: Connections, Repositories, Telemetry & Logs, Handover, Multimodal.
- **Expected — behavioral:** The `id="workflow.orchestrator"` anchor is stable; dependent plugin pages nest under it without gaps.
- **✅ Checks (tick each):**
  - [ ] All 4 entry points open the same dialog at the same node
  - [ ] Sub-pages are visible in the left tree: Connections, Repositories, Telemetry & Logs, Handover
  - [ ] No "page not found" or blank right panel
- **🐞 Bug signals:** Different entry points open at different nodes → `ShowSettingsUtil` call mismatches; missing sub-pages → parentId anchor renamed.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None.

---

### [CORE-09] Settings root page — Connection Status overview
- **Component(s):** `WorkflowSettingsConfigurable.buildConnectionRows` (line 42-60)
- **Preconditions:** Some services configured (URLs set), some not.
- **Steps:**
  1. Open Settings → Tools → Workflow Orchestrator (root page, not a sub-page).
  2. Observe the "Connection Status" group.
  3. Note the icon and text for each service.
  4. Add a Jira URL in Connections sub-page → Apply; return to root page.
- **Expected — visual:** Five rows — Jira, Bamboo, Bitbucket, SonarQube, Sourcegraph. Each row has a green checkmark icon (`AllIcons.General.InspectionsOK`) when the URL is set, or a red error icon (`AllIcons.General.Error`) when blank. Text is `"<Service> — configured"` or `"<Service> — not configured"`.
- **Expected — behavioral:** Root page has no editable fields; `isModified()` always returns false; Apply/OK do nothing. The status reflects the current `ConnectionSettings` (application-level).
- **✅ Checks (tick each):**
  - [ ] 5 rows present for Jira, Bamboo, Bitbucket, SonarQube, Sourcegraph
  - [ ] Green icon for configured services, red for unconfigured
  - [ ] After adding Jira URL in sub-page and Apply, returning to root shows Jira as configured
  - [ ] Root page has no editable fields and no Apply button state change
  - [ ] Comment text "Configure connections, workflow, CI/CD, and AI settings in the sub-pages below." visible
- **🐞 Bug signals:** Icons wrong → `AllIcons.General.InspectionsOK` not resolving; status stale after applying sub-page → `ConnectionSettings` singleton not re-read on create.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None.

---

### [CORE-10] Connections page — URL + token entry and Test Connection (Jira, Bamboo, SonarQube)
- **Component(s):** `ConnectionsConfigurable.tokenServiceGroup` (line 221-309)
- **Preconditions:** Connections page open; tokens already in PasswordSafe from prior run (test both pre-filled and freshly typed flows).
- **Steps:**
  1. Navigate to Settings → Connections.
  2. Observe that the Access Token fields for configured services are pre-filled (masked) on page load.
  3. For Jira: clear the token, type a new valid token, click **Test Connection**.
  4. Observe the status label next to the button during and after the test.
  5. Repeat with an invalid token.
  6. For SonarQube: enter an HTTP URL (not HTTPS); click OK/Apply; observe notification.
- **Expected — visual:** Status label shows "Testing..." during the background task. On success: "✓ Connected successfully". On failure: "✗ <error message>". HTTP URL triggers a warning notification banner ("SonarQube URL warning") with an action link "Open Connection settings".
- **Expected — behavioral:** Token fields are loaded from PasswordSafe in background (`executeOnPooledThread` at line 300); `isInitializing` guard prevents false `isModified` during load. On Apply: `CredentialStore.storeToken()` is called for each `pendingTokens` entry; failure triggers error notification (line 202-213). `CredentialStore.clearGlobalCache()` called on every Apply.
- **✅ Checks (tick each):**
  - [ ] Token fields are pre-filled (masked) on page open when tokens exist in PasswordSafe
  - [ ] "Testing..." label appears immediately when Test Connection clicked
  - [ ] "✓ Connected successfully" shown on valid credentials
  - [ ] "✗ <message>" shown on invalid credentials
  - [ ] HTTP URL warning notification ("Using HTTP is insecure...") appears in IntelliJ notification banner
  - [ ] SSRF-blocked URL (localhost, internal IP, non-http[s]) triggers error notification "Jira URL rejected" and Apply is aborted (settings NOT saved)
  - [ ] After Apply with valid token, reopening Connections shows token still pre-filled
- **🐞 Bug signals:** Token field blank on reopen → PasswordSafe write failed or wrong `CredentialAttributes`; "Testing..." never clears → background thread not calling `invokeLater`; HTTP warning missing → `validationOnApply` not executed.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** Entering a URL and clicking Test Connection sends an HTTP request to the configured server — READ-ONLY auth probe. Token save on Apply writes to PasswordSafe (local credential store) — allowed.

---

### [CORE-11] Connections page — Bitbucket URL, token, username and auto-detect
- **Component(s):** `ConnectionsConfigurable.bitbucketServiceGroup` (line 315-427)
- **Preconditions:** Bitbucket URL and token configured.
- **Steps:**
  1. Open Connections; observe Bitbucket section has three fields: Server URL, Access Token, Username.
  2. Check that Username is pre-filled from `ConnectionSettings.bitbucketUsername`.
  3. Clear the username field manually. Click **Test Connection**.
  4. Observe auto-detection: after a successful test, the Username field populates with the detected value and the status label shows "✓ Connected as <username>".
  5. Apply settings; reopen Connections; verify username persists.
- **Expected — visual:** Status label shows "✓ Connected as <username>" when auto-detect succeeds. Username field comment reads "Auto-detected on Test Connection, or enter manually".
- **Expected — behavioral:** `authTestService.fetchBitbucketUsername()` is called only on `ApiResult.Success`. Detected username is stored in `pendingBitbucketUsername` and written to `connSettings.state.bitbucketUsername` on Apply.
- **✅ Checks (tick each):**
  - [ ] Bitbucket group has exactly 3 fields: URL, Token, Username
  - [ ] Username auto-populates after successful Test Connection
  - [ ] Status label shows "✓ Connected as <username>" (not just "✓ Connected successfully")
  - [ ] Username persists after Apply and Settings reopen
  - [ ] Manual username override (type different name → Apply) persists
- **🐞 Bug signals:** Username not auto-detected → `fetchBitbucketUsername` returning null; username not persisting → `pendingBitbucketUsername` not applied.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** Test Connection sends a read-only auth probe to Bitbucket — allowed.

---

### [CORE-12] Connections page — token persists across IDE restart (PasswordSafe round-trip)
- **Component(s):** `CredentialStore`, `ConnectionsConfigurable.apply` (line 122-189)
- **Preconditions:** Fresh token not yet saved to PasswordSafe.
- **Steps:**
  1. Open Connections; enter a valid Jira URL and token.
  2. Click **Apply** (not OK — stay in Settings).
  3. Close and reopen Settings → Connections.
  4. Confirm Jira token field is pre-filled (masked dots).
  5. Fully close the IDE and reopen.
  6. Open Connections again; confirm token is still present.
- **Expected — visual:** Token field shows masked characters (password field) on every reopen after Apply.
- **Expected — behavioral:** `CredentialStore.storeToken()` writes to `PasswordSafe`; `credentialStore.getToken()` reads it back asynchronously. The `isInitializing` flag prevents the background-loaded token from triggering `isModified = true`.
- **✅ Checks (tick each):**
  - [ ] Token field pre-filled after Apply (same session)
  - [ ] Token field pre-filled after IDE restart
  - [ ] `isModified()` returns false immediately after opening Connections with a saved token (no spurious apply prompt)
  - [ ] Changing the token and clicking Reset reverts the field to the previously saved value
- **🐞 Bug signals:** Token not pre-filled → PasswordSafe read on wrong `ServiceType`; `isModified` returns true on open → `isInitializing` guard not working; token blank after restart → PasswordSafe on Windows not persisting (check `Settings → Appearance & Behavior → System Settings → Passwords`).
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** Token save writes to Windows credential store (PasswordSafe) — local configuration only, allowed.

---

### [CORE-13] Connections page — Reset reverts unsaved changes
- **Component(s):** `ConnectionsConfigurable.reset` (line 192-196)
- **Preconditions:** Connections page open with saved settings.
- **Steps:**
  1. Change the Jira URL to a new value; change the token.
  2. Click **Reset** (not Cancel — click the Reset button inside the settings dialog).
  3. Observe the Jira URL and token fields.
  4. Verify `isModified()` returns false (Apply button greyed out).
- **Expected — visual:** Jira URL reverts to the previously saved value. Token field returns to the previously loaded (masked) value.
- **Expected — behavioral:** `dialogPanel?.reset()` restores all Kotlin UI DSL bound fields. `pendingTokens.clear()` and `pendingBitbucketUsername = null` ensure credential changes are discarded.
- **✅ Checks (tick each):**
  - [ ] URL field reverts to saved value after Reset
  - [ ] Token field reverts (no new pending token in `pendingTokens`)
  - [ ] Apply button becomes greyed out immediately after Reset
  - [ ] Bitbucket username also reverts
- **🐞 Bug signals:** URL reverts but token stays → `pendingTokens.clear()` not called; Apply still enabled after Reset → `isModified` still returning true.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None.

---

### [CORE-14] Connections page — URL validation (SSRF guard and HTTP warning)
- **Component(s):** `ConnectionsConfigurable.apply` SSRF block (line 126-168), `BaseUrlValidator`
- **Preconditions:** Connections page open.
- **Steps:**
  1. Enter `http://jira.company.com` (HTTP, not HTTPS) as the Jira URL. Click Apply.
  2. Observe: a WARNING notification banner appears. Settings are still saved (non-blocking).
  3. Enter `http://localhost:8080` or a private-range IP as the Jira URL. Click Apply.
  4. Observe: an ERROR notification banner appears and settings are NOT saved (Apply aborted).
  5. Click the "Open Connection settings" action link in the soft warning notification.
- **Expected — visual:** HTTP soft warning: notification type WARNING, title "Jira URL warning", body from `BaseUrlValidator`. SSRF hard block: notification type ERROR, title "Jira URL rejected", Apply aborts entirely (no token write, no URL persist). Action link "Open Connection settings" in warning notification opens the Connections page.
- **Expected — behavioral:** `BaseUrlValidator.validate()` returns `Invalid` (abort Apply) or `SoftWarning` (allow but notify). The `return` at line 148 prevents any subsequent token saves when `Invalid` is returned.
- **✅ Checks (tick each):**
  - [ ] HTTP URL produces WARNING notification with action link
  - [ ] Settings are saved despite HTTP warning (soft)
  - [ ] SSRF-blocked URL (localhost, 127.x, 10.x, 192.168.x) produces ERROR notification
  - [ ] Settings are NOT saved after SSRF rejection — re-opening shows old URL
  - [ ] "Open Connection settings" action link in warning notification navigates back to Connections page
- **🐞 Bug signals:** SSRF URL saved despite error → `return` statement at line 148 missing or bypassed; notification not appearing → `NotificationGroupManager` lookup returning null (group not registered in plugin.xml).
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None.

---

### [CORE-15] Connections page — Sourcegraph collapsible group and Network Advanced
- **Component(s):** `ConnectionsConfigurable.createComponent` (lines 84-103)
- **Preconditions:** Connections page open.
- **Steps:**
  1. Observe Sourcegraph group; by default it should be collapsed.
  2. Click to expand Sourcegraph; enter a URL and token; Test Connection.
  3. Collapse Sourcegraph; click Apply.
  4. Expand the "Network (Advanced)" collapsible group.
  5. Change Connect timeout to 5 and Read timeout to 60; Apply.
  6. Reopen Settings → Connections → Network (Advanced); verify values persisted.
- **Expected — visual:** Sourcegraph group starts collapsed. Network (Advanced) group starts collapsed. Both expand/collapse with a click on the group header. Timeout fields show spinners limited to 1–300 and 1–600 respectively.
- **Expected — behavioral:** Values bind to `pluginSettings.state.httpConnectTimeoutSeconds` and `httpReadTimeoutSeconds` via `bindIntText`. They apply to all API clients via `HttpClientFactory`.
- **✅ Checks (tick each):**
  - [ ] Sourcegraph group is collapsed by default
  - [ ] Expanding Sourcegraph shows URL + token + Test Connection
  - [ ] Network (Advanced) group is collapsed by default
  - [ ] Connect timeout field accepts range 1–300 only (rejects 0 and 301)
  - [ ] Read timeout field accepts range 1–600 only
  - [ ] Timeout values persist after Apply and Settings reopen
- **🐞 Bug signals:** Group starts expanded → `collapsible = true` parameter not passed; out-of-range value accepted → `intTextField(range=...)` range not enforced.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None.

---

## Settings — Repositories

### [CORE-16] Repositories page — table operations and auto-detect
- **Component(s):** `RepositoriesConfigurable` (`core/.../settings/RepositoriesConfigurable.kt:38-80`)
- **Preconditions:** At least one repository configured in the project.
- **Steps:**
  1. Open Settings → Repositories.
  2. Observe the JBTable with columns: Name, Bitbucket, Bamboo Plan, Docker Tag, SonarQube, Canonical URL, Primary.
  3. Click **Add**; fill in the dialog; click OK.
  4. Select the new row; click **Edit**; modify a field; click OK.
  5. Select a non-primary row; click **Remove**; confirm.
  6. Click **Auto-detect**; observe the progress and result.
  7. Verify the Primary column uses a checkbox rendering (boolean column class).
- **Expected — visual:** Table renders 7 columns including a checkbox in the Primary column. Add/Edit/Remove buttons below or beside the table. Auto-detect shows a progress indicator.
- **Expected — behavioral:** `repoTableModel` column 6 is `java.lang.Boolean` class → rendered as checkbox. `editedRepos` is the in-memory working copy; changes apply to `pluginSettings` only on Apply.
- **✅ Checks (tick each):**
  - [ ] All 7 columns present with correct labels
  - [ ] Primary column renders as checkbox, not "true"/"false" text
  - [ ] Added repo appears in table immediately; Apply persists it
  - [ ] Remove removes from table; Cancel does not persist
  - [ ] Auto-detect populates at least one repo row
- **🐞 Bug signals:** Primary column shows "true"/"false" text → `getColumnClass` override broken; edits lost after Cancel → in-memory copy applied prematurely.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None.

---

## Settings — Telemetry & Logs

### [CORE-17] Telemetry page — log level, diagnostic log toggle (applies after restart)
- **Component(s):** `TelemetryConfigurable` (`core/.../settings/TelemetryConfigurable.kt`)
- **Preconditions:** Telemetry & Logs settings page open.
- **Steps:**
  1. Observe the Log Level combo (INFO/DEBUG/TRACE); note current value.
  2. Change to TRACE; Apply; reopen page; verify TRACE persists.
  3. Check "Write a separate shareable plugin diagnostic log (for support; applies after restart)".
  4. Read the annotation text carefully: "applies after restart".
  5. Click Apply; verify checkbox state persists on reopen.
  6. Uncheck "Enable diagnostic JSONL logging"; Apply.
  7. Change Log retention to 14 days; Apply; reopen; verify value.
- **Expected — visual:** All three sections visible: Logging (5 rows), Privacy (1 row), Display (1 row), HTTP Cache (stats table + 2 buttons). Diagnostic log checkbox annotation clearly reads "applies after restart".
- **Expected — behavioral:** `logLevel`, `diagnosticJsonlEnabled`, `pluginDiagnosticLogEnabled`, `retentionDays` all bind via `bindItem`/`bindSelected`/`bindIntText` to `settings.state.*`. `isModified` tracks the `dialogPanel`.
- **✅ Checks (tick each):**
  - [ ] Log Level combo shows 3 options: INFO, DEBUG, TRACE
  - [ ] Log Level persists after Apply and Settings reopen
  - [ ] "applies after restart" annotation text present on the diagnostic log checkbox row
  - [ ] Diagnostic log checkbox state persists
  - [ ] Log retention field rejects values outside 1–365
  - [ ] "Show estimated cost in UI" checkbox in Display section present and persists
- **🐞 Bug signals:** Log level resets to INFO on reopen → `bindItem` setter not called; annotation text missing → label removed from source.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None.

---

### [CORE-18] Telemetry page — HTTP Cache stats and Purge action
- **Component(s):** `TelemetryConfigurable` (lines 76-93, 127-179)
- **Preconditions:** Plugin has made at least one API call (Jira/Bamboo/Sonar) so cache stats are non-empty.
- **Steps:**
  1. Open Telemetry & Logs.
  2. Observe the HTTP Cache stats label initial state (may show "No cache activity recorded yet...").
  3. Click **Refresh stats**.
  4. If stats available: verify the HTML table shows columns: Service, Fresh hits, Stale-match, Stale-differ, Miss, Hit %, Entries, Bytes, Mut. inv., Evicted.
  5. Note the total bytes shown.
  6. Click **Purge HTTP cache**.
  7. Observe the confirmation dialog (title "HTTP Cache Purged", message includes entry count and bytes freed).
  8. Click OK; click Refresh stats; verify entry counts reset to 0 (but hit/miss counters remain).
- **Expected — visual:** Stats table uses monospace HTML. Purge dialog shows "Cleared X cached response(s), freeing Y KB." Hit/miss columns remain non-zero after purge.
- **Expected — behavioral:** `HttpResponseCache.invalidateAll()` clears entries. `HttpCacheMetrics` counters are NOT reset by purge (per the dialog message). Bytes shown use the `formatBytes` helper (B/KB/MB).
- **✅ Checks (tick each):**
  - [ ] Stats label shows italic placeholder text when no cache activity yet
  - [ ] Refresh stats updates the label
  - [ ] All 10 column headers present when data exists
  - [ ] Purge dialog shows entry count and bytes freed
  - [ ] After purge, Refresh stats shows Entries = 0 and Bytes = "0 B"
  - [ ] Hit/miss counters remain non-zero after purge (only entries cleared)
- **🐞 Bug signals:** Stats always empty → `HttpCacheMetrics.getAllStats()` not wired; Purge dialog not appearing → `Messages.showInfoMessage` call missing; bytes always "0 B" → `bytesInCache` metric not being incremented.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None.

---

### [CORE-19] Telemetry page — Open Log Folder button
- **Component(s):** `TelemetryConfigurable.openLogsFolder` (line 116-125)
- **Preconditions:** Project open with a valid basePath.
- **Steps:**
  1. Open Telemetry & Logs; click **Open Log Folder**.
  2. Verify a file explorer window opens at `~/.workflow-orchestrator/<project-id>/logs/`.
  3. Verify the directory is created if it did not exist.
- **Expected — visual:** OS file explorer (Windows Explorer) opens at the logs directory.
- **Expected — behavioral:** `logsDir.mkdirs()` ensures directory exists; `Desktop.getDesktop().open(logsDir)` opens the system file manager.
- **✅ Checks (tick each):**
  - [ ] File explorer opens at the expected path
  - [ ] If logs directory was absent it is created first
  - [ ] No exception logged when clicking button
- **🐞 Bug signals:** File explorer doesn't open → `Desktop.isDesktopSupported()` returning false on Windows → fallback needed; path is null → `project.basePath` null guard.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None.

---

## Settings — Handover

### [CORE-20] Handover settings page — all four sections
- **Component(s):** `HandoverConfigurable` (`core/.../settings/HandoverConfigurable.kt`)
- **Preconditions:** Handover settings page open; some handover overrides recorded in the past 30 days (or zero).
- **Steps:**
  1. Open Settings → Handover.
  2. Observe the "Quick clipboard chips" group; count checkboxes — should match the 14-item `PLACEHOLDER_CATALOG`.
  3. Uncheck `ticket.id`; Apply; reopen; verify it remains unchecked.
  4. Observe "Templates" group: two read-only path fields and two "Open" links.
  5. Click **Open global folder**; verify file explorer opens at `~/.workflow-orchestrator/handover/templates`.
  6. Observe "AI summaries" checkbox; toggle it; Apply.
  7. Observe "Override audit" count; click **Clear**; verify count drops to 0.
- **Expected — visual:** Exactly 14 checkbox rows matching `PLACEHOLDER_CATALOG` keys. Template path fields are non-editable (grey). Override audit shows count + "Clear" link on same row.
- **Expected — behavioral:** Chip checkboxes bind via `bindSelected` getters/setters that add/remove keys from `state.quickClipboardChips`. Clear link calls `state.handoverOverrideLog.clear()` guarded by `synchronized`.
- **✅ Checks (tick each):**
  - [ ] Exactly 14 chip checkboxes present matching `PLACEHOLDER_CATALOG` list
  - [ ] Unchecked chip persists after Apply and reopen
  - [ ] Template path fields are read-only
  - [ ] Open global folder opens file explorer at correct path
  - [ ] AI summaries checkbox state persists after Apply
  - [ ] Override audit count is a non-negative integer
  - [ ] Clicking Clear sets count to 0 immediately (no Apply needed — reads live state)
- **🐞 Bug signals:** More or fewer than 14 checkboxes → `PLACEHOLDER_CATALOG` size changed; template fields editable → `isEditable = false` not set; Clear doesn't update → view not refreshed after clear.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None.

---

## Settings — Multimodal

### [CORE-21] Multimodal settings — master checkbox grays dependent fields
- **Component(s):** `MultimodalSettingsConfigurable` (`core/.../settings/MultimodalSettingsConfigurable.kt`)
- **Preconditions:** Multimodal settings page open (under Agent sub-tree).
- **Steps:**
  1. Navigate to Settings → Agent → Multimodal.
  2. Uncheck "Enable visual support"; observe which fields grey out.
  3. Verify: "Auto-load images from tool results", Max image size, Max images per turn, Allowed MIME types are all greyed.
  4. Verify: Max file attachment size and Max files per message remain enabled (not tied to master).
  5. Re-check "Enable visual support"; verify greyed fields become active.
  6. Change Max images per turn to 5; change Allowed MIME types to "image/png"; Apply.
  7. Reopen page; verify values persisted.
- **Expected — visual:** Three fields/sections with `enabledIf(masterCheckbox.selected)`: Auto-load checkbox, Max image size field, Max images per turn field, Allowed MIME types field. Two fields NOT gated: Max file attachment size, Max files per message.
- **Expected — behavioral:** On Apply, `notifyAgentControllerOfChange()` is called reflectively to push settings into any running Agent JCEF webview. This is best-effort; if the Agent tab is not loaded it silently succeeds.
- **✅ Checks (tick each):**
  - [ ] Unchecking master checkbox greys 4 dependent controls
  - [ ] Max file attachment size and Max files per message remain enabled regardless of master
  - [ ] Rechecking master restores all 4 controls
  - [ ] Max images per turn value persists (range 1–10; 0 rejected)
  - [ ] MIME types comma-separated list parses and persists correctly
  - [ ] Token estimate per image field present and persists
- **🐞 Bug signals:** All fields always enabled → `enabledIf` lambdas not applied; persistence lost → `dialogPanel?.apply()` not called in `apply()` override.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None.

---

## Insights Tab

### [CORE-22] Insights — Today sub-tab: tiles, success rate bar, top tools, recent errors
- **Component(s):** `InsightsPanel`, `TodayPanel` (`core/.../toolwindow/insights/TodayPanel.kt`)
- **Preconditions:** At least one agent session recorded today (files in `~/.workflow-orchestrator/<project>/agent/sessions/`). Agent module active (SessionHistoryReader EP registered).
- **Steps:**
  1. Click the **Insights** tab.
  2. Ensure the "Today" sub-tab is selected.
  3. Observe the four StatTiles: Sessions, Tokens, Est. Cost, Tool Calls.
  4. Observe the success rate progress bar.
  5. Observe the "Top Tools" table and "Recent Errors" list.
  6. Click the **Refresh** button in the toolbar.
- **Expected — visual:** Four `StatTilePanel` tiles in a 1×4 grid, each with a card border (`CARD_BG` background), label, and bold 20pt value. Progress bar shows "X% success" string. "Top Tools" table has 2 columns: Tool, Calls. "Recent Errors" list shows "No errors recorded." when none.
- **Expected — behavioral:** `SmartPoller` polls every 30s (backing off to 300s when unchanged). `refresh()` triggers an immediate reload. Token format uses `InsightsFormatters.formatTokenPair` (e.g. "1.2K↑ / 0.5K↓"). Cost shows "≈ $X.XX" only when `hasRealCost=true`, otherwise "—".
- **✅ Checks (tick each):**
  - [ ] 4 StatTiles visible with non-zero values when sessions exist
  - [ ] Tile background colour (`CARD_BG`) distinct from panel background
  - [ ] Success rate bar shows integer percentage with "% success" suffix
  - [ ] Top Tools table not empty when tool calls exist; columns "Tool" and "Calls"
  - [ ] "No errors recorded." shown in Recent Errors list when no errors
  - [ ] Refresh button triggers immediate data reload (tiles update)
  - [ ] Empty state "No sessions today." shown when no sessions for today
- **🐞 Bug signals:** All tiles show "—" despite sessions → `InsightsServiceImpl` not reading the correct `baseDir`; cost shows "$0.00" instead of "—" → `hasRealCost` logic incorrect; success bar shows "No data" when data exists → `totalToolCalls` not populated.
- **Theme/size matrix:** light + dark — `CARD_BG` is `JBColor(#F7F8FA, #2B2D30)`; `SECONDARY_TEXT` for tile labels.
- **⛔ Write note:** None.

---

### [CORE-23] Insights — This Week sub-tab: sparkline and model usage
- **Component(s):** `WeekPanel` (`core/.../toolwindow/insights/WeekPanel.kt`)
- **Preconditions:** Sessions exist across multiple days in the past 7 days.
- **Steps:**
  1. Click "This Week" sub-tab in Insights.
  2. Observe the 4 StatTiles (same layout as Today but sourced from 7-day window).
  3. Observe the "Sessions per Day (7 days)" sparkline (Unicode block chars).
  4. Observe the "Model Usage" list.
  5. Verify sparkline length is exactly 7 characters.
- **Expected — visual:** Sparkline uses Unicode block characters `▁▂▃▄▅▆▇█` — exactly 7 characters followed by "  (7 days)". Model usage list shows `<modelId>  ×<count>` rows sorted descending. If no data, shows "No sessions this week." centred empty state.
- **Expected — behavioral:** Sparkline max-normalises per-day counts; if all days are zero → "▁▁▁▁▁▁▁". Sessions counted from last 7 × 24 × 60 × 60 × 1000 ms from now (not calendar week).
- **✅ Checks (tick each):**
  - [ ] Sparkline is exactly 7 characters long
  - [ ] Highest day = "█", zero day = "▁" (or all "▁" when max=0)
  - [ ] Model usage list is sorted descending by session count
  - [ ] Each model row format: "<model-id>  ×<count>"
  - [ ] StatTiles show 7-day aggregated values (may differ from Today tiles)
  - [ ] "No sessions this week." empty state shown when 7-day window is empty
- **🐞 Bug signals:** Sparkline wrong length → `(0..6).map{...}.reversed()` off; model counts unsorted → `sortedByDescending` missing; tiles show same values as Today → wrong stats object passed.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None.

---

### [CORE-24] Insights — Sessions table: columns, truncation, max 200 rows, footer
- **Component(s):** `SessionsPanel` (`core/.../toolwindow/insights/SessionsPanel.kt`)
- **Preconditions:** Sessions exist (at least 1; test with >200 if possible).
- **Steps:**
  1. Click "Sessions" sub-tab in Insights.
  2. Verify 6 column headers: Time, Task, Model, Tokens In, Tokens Out, Est. Cost.
  3. Observe the footer label.
  4. Observe a session with a long task description.
  5. If >200 sessions exist: verify footer says "Showing last 200 of X sessions".
  6. If ≤200 sessions: footer says "X session(s)".
  7. Verify table is not editable (clicking a cell does not activate edit mode).
- **Expected — visual:** JBTable with no grid lines. Task column max 60 chars + "…". Footer label in secondary text colour. Rows sorted newest first.
- **Expected — behavioral:** `tableModel.isCellEditable` always returns false. Tasks > 60 chars are truncated to 57 + "…". `TimeFormatter.relative` renders human-readable relative time (e.g. "2 min ago").
- **✅ Checks (tick each):**
  - [ ] 6 columns in order: Time, Task, Model, Tokens In, Tokens Out, Est. Cost
  - [ ] Tasks > 60 chars are truncated with "…"
  - [ ] Table cells are not editable
  - [ ] Footer shows correct count or "Showing last 200 of X" for >200 sessions
  - [ ] Empty state "No sessions yet." shown when session list is empty
  - [ ] Rows sorted newest first (most recent timestamp at top)
- **🐞 Bug signals:** Table editable → `isCellEditable` override not present; truncation missing → `take(57) + "…"` conditional wrong; wrong sort order → `sortedByDescending` removed.
- **Theme/size matrix:** light + dark; narrow window — verify column auto-resize on last column.
- **⛔ Write note:** None.

---

### [CORE-25] Insights — Reliability sub-tab: Open Trace Folder and Report Issue
- **Component(s):** `ReliabilityPanel` (`core/.../toolwindow/insights/ReliabilityPanel.kt`)
- **Preconditions:** Insights tab and Reliability sub-tab opened.
- **Steps:**
  1. Click "Reliability" sub-tab.
  2. Read all three section headers: HTTP METRICS, TRACES, DIAGNOSTICS.
  3. Click **Open Trace Folder**.
  4. Verify Windows Explorer opens at `~/.workflow-orchestrator/<project>/agent/logs/raw-api`.
  5. Click **Report Issue…**; wait for the background task.
  6. Verify a dialog appears (DiagnosticDialog) after the build completes.
- **Expected — visual:** Three sections with uppercase headers in secondary text colour + bold 10pt font. HTTP Metrics and Traces sections show placeholder italic text. Two buttons: "Open Trace Folder" and "Report Issue…".
- **Expected — behavioral:** Trace folder is created via `mkdirs()` if absent before being opened. `DiagnosticBundleBuilder.build()` runs in a `runBackgroundableTask` on a background thread; `DiagnosticDialog` is shown on EDT via `invokeLater`.
- **✅ Checks (tick each):**
  - [ ] Section headers "HTTP METRICS", "TRACES", "DIAGNOSTICS" present
  - [ ] Placeholder text visible in HTTP Metrics and Traces sections
  - [ ] Open Trace Folder opens at `…/logs/raw-api` (created if absent)
  - [ ] Report Issue shows progress indicator during bundle build
  - [ ] DiagnosticDialog appears with bundle path after completion
- **🐞 Bug signals:** Trace folder not opened → `Desktop.isDesktopSupported()` false; DiagnosticDialog not appearing → `invokeLater` missing; background task throws and is swallowed silently → check idea.log for "DiagnosticBundleBuilder failed".
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** "Report Issue…" bundles local logs/settings into a ZIP — local file operation only, no network write.

---

### [CORE-26] Insights — polling lifecycle and EventBus refresh
- **Component(s):** `InsightsPanel` (`core/.../toolwindow/insights/InsightsPanel.kt:50-103`)
- **Preconditions:** Insights tab previously opened (panel materialized).
- **Steps:**
  1. With Insights tab active, watch StatTile values.
  2. Complete an agent session in a different tab (triggering `WorkflowEvent.TaskChanged`).
  3. Return to the Insights tab; observe whether tiles update without manual refresh.
  4. Switch away from the Insights tab (e.g. to Sprint); wait 60 seconds.
  5. Switch back; observe polling resumes.
  6. Close the project (or tool window); verify no poller thread is left orphaned (no exception in idea.log on project close).
- **Expected — visual:** After `TaskChanged` event, tiles auto-update (no manual Refresh click needed). Polling is paused while the tab is not visible (`ancestorRemoved` stops poller) and restarts when visible again (`ancestorAdded`).
- **Expected — behavioral:** `SmartPoller` base=30s, max=300s with 1.5× backoff when data unchanged. EventBus collector fires on `WorkflowEvent.TaskChanged` and forces an immediate reload. `dispose()` calls `scope.cancel()` to clean up the EventBus collector.
- **✅ Checks (tick each):**
  - [ ] Tiles update within 5 seconds of a TaskChanged event without manual Refresh
  - [ ] Switching away from Insights and back starts polling again
  - [ ] No "coroutine was cancelled" errors on project close
  - [ ] idea.log shows no lingering threads or exceptions after close
- **🐞 Bug signals:** No auto-update after TaskChanged → EventBus subscriber not wiring to `InsightsPanel.refresh()`; stale data after tab switch → `ancestorAdded` not restarting poller.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None.

---

## Onboarding

### [CORE-27] Onboarding GotItTooltip — first run, dismiss, no reappear
- **Component(s):** `OnboardingStartupListener` (`core/.../onboarding/OnboardingStartupListener.kt`), `OnboardingService`
- **Preconditions:** No services configured (all URLs blank in Connections) — simulates a fresh install. Reset by clearing `workflowOrchestratorConnections.xml` from IDE config if necessary.
- **Steps:**
  1. Open the project; wait for startup activities to complete.
  2. Observe whether a `GotItTooltip` balloon appears anchored to the "Workflow" tool window stripe button.
  3. Read the header ("Welcome to Workflow Orchestrator!") and body text.
  4. Read the "Start Setup" link.
  5. Click **Got It** (dismiss button) without clicking Start Setup.
  6. Close and reopen the project.
  7. Observe whether the tooltip appears again.
- **Expected — visual:** GotItTooltip appears at `BOTTOM_MIDDLE` of the Workflow tool window component. Header: "Welcome to Workflow Orchestrator!". Body: "Connect your development tools to get started." Link: "Start Setup".
- **Expected — behavioral:** `markOnboardingShown()` is called immediately after `tooltip.show()` (line 43 before any user interaction). Therefore, whether the user dismisses or clicks Start Setup, the tooltip does not reappear in the same IDE session or after restart (`hasShownOnboarding` flag set in-memory; but since `OnboardingService` is a project service, it resets on IDE restart — the only true guard against re-show is that services will now be configured after setup).
- **✅ Checks (tick each):**
  - [ ] Tooltip appears on first project open with no services configured
  - [ ] Header text "Welcome to Workflow Orchestrator!" correct
  - [ ] Body text "Connect your development tools to get started." correct
  - [ ] "Start Setup" link present
  - [ ] Tooltip does NOT appear again in the same session after dismissal
  - [ ] No startup modal dialog (only a tooltip — no blocking dialog on first run unless "Start Setup" is clicked)
- **🐞 Bug signals:** Tooltip appears every startup → `markOnboardingShown()` not called or `hasShownOnboarding` reset; tooltip not anchored to tool window → `toolWindow.component` null; Tooltip appears when services ARE configured → `shouldShowOnboarding()` check incorrect.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None.

---

### [CORE-28] Onboarding SetupDialog — Test Connection flow and Finish/Skip
- **Component(s):** `SetupDialog` (`core/.../onboarding/SetupDialog.kt`)
- **Preconditions:** GotItTooltip visible; "Start Setup" link clickable.
- **Steps:**
  1. Click **Start Setup** from the GotItTooltip.
  2. Observe the dialog: title "Setup Workflow Orchestrator", OK = "Finish Setup", Cancel = "Skip".
  3. Enter valid Jira URL and token; click the Jira Test button; wait.
  4. Observe success confirmation for Jira.
  5. Click **Skip** (Cancel); verify no credentials are saved.
  6. Reopen via `OnboardingService.showSetupDialog()` (next time); click **Finish Setup** after testing at least Jira.
  7. Verify Jira URL and token are persisted in Connections settings.
- **Expected — visual:** Dialog shows connection sections for Jira, Bamboo, Bitbucket, SonarQube. Each section has URL + token fields + Test button + status label. Success highlighted. Instruction text at top: "Connect your development tools. You can change these later in Settings → Tools → Workflow Orchestrator → Connections".
- **Expected — behavioral:** `SetupDialog` uses `ModalityState.stateForComponent(contentPane)` for EDT dispatches inside the modal dialog (invokeLater issue fix at line 44-48). Successful tests are cached in `successfulTests` map; only written to settings on Finish Setup.
- **✅ Checks (tick each):**
  - [ ] Dialog title is "Setup Workflow Orchestrator"
  - [ ] OK button labelled "Finish Setup", Cancel labelled "Skip"
  - [ ] Test Connection works from within the modal dialog (status label updates while dialog is open)
  - [ ] Skip cancels without saving any credentials
  - [ ] Finish Setup saves credentials for all successfully tested services
  - [ ] Instruction text references Settings path correctly
- **🐞 Bug signals:** Status label never updates while dialog is open → `invokeLater` using wrong `ModalityState.NON_MODAL`; credentials saved on Skip → write not gated by OK button.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** Test Connection sends a read-only auth probe; Finish Setup writes tokens to PasswordSafe — local config write, allowed.

---

## Gutter Markers and ExternalAnnotator

### [CORE-29] SonarQube coverage gutter markers — toggle, icon variants, light/dark
- **Component(s):** `CoverageLineMarkerProvider` (`sonar/.../ui/CoverageLineMarkerProvider.kt`), `SonarIssueAnnotator`
- **Preconditions:** SonarQube configured and Quality tab shows coverage data. A Java/Kotlin file in the project has lines with known coverage status.
- **Steps:**
  1. Open Settings → Code Quality (SonarQube sub-page); verify "Enable coverage gutter markers" is unchecked by default.
  2. Check it; Apply; close Settings.
  3. Open a Java/Kotlin file that has SonarQube coverage data.
  4. Wait for the daemon pass to complete (no spinning activity indicator).
  5. Observe the gutter for covered, uncovered, and partially-covered lines.
  6. Hover over a gutter icon; read the tooltip.
  7. Switch to Dark theme; verify icons swap to `_dark` SVG variants.
  8. Uncheck the setting; Apply; observe icons disappear.
- **Expected — visual:** Three gutter icon types: green-circle (covered → tooltip "Line covered"), red-circle (uncovered → "Line not covered"), yellow half (partial → "Partially covered (some branches uncovered)"). Exactly one icon per covered/marked line (not one per token). Dark theme: icons use `coverage-covered_dark.svg`, `coverage-uncovered_dark.svg`, `coverage-partial_dark.svg` from `sonar/src/main/resources/icons/`.
- **Expected — behavioral:** `coverageGutterMarkersEnabled` defaults to `false`; setting to `true` in settings enables the provider. First daemon pass triggers async fetch (`fetchLineCoverageAsync`); icons appear on the NEXT natural daemon pass (no forced restart). `CoverageFileHeaderCache` caches per-file header after first resolution.
- **✅ Checks (tick each):**
  - [ ] No gutter icons when `coverageGutterMarkersEnabled = false`
  - [ ] Icons appear after enabling the setting and a daemon pass
  - [ ] Exactly one icon per marked line (not duplicate icons per token)
  - [ ] Covered lines show green icon; uncovered red; partial yellow/orange
  - [ ] Tooltip text matches the three expected strings exactly
  - [ ] Dark theme shows distinct `_dark` SVG variants (visually different from light)
  - [ ] Disabling the setting removes all icons after next daemon pass
- **🐞 Bug signals:** Multiple icons on the same line → `CoverageMarkerEmitGate` not filtering correctly; icons never appear → `SonarDataService` not providing data or `fetchLineCoverageAsync` failing silently; icons wrong variant in dark mode → `IconLoader.getIcon` path missing `_dark` suffix resolution.
- **Theme/size matrix:** Light and dark (explicit icon variant check required).
- **⛔ Write note:** None.

---

## Theming

### [CORE-30] Full theme switch — all panels repaint without stale colours
- **Component(s):** `StatusColors` (`core/.../ui/StatusColors.kt`), all `JBColor` usages in tool window panels
- **Preconditions:** Tool window open with all tabs materialized; Insights Today sub-tab visible with data.
- **Steps:**
  1. With IntelliJ in **Light** theme, open the tool window; note tile backgrounds, secondary text, active ticket bar colour.
  2. Switch to **Dark** theme (Settings → Appearance & Behavior → Appearance → Theme: IntelliJ Light → Darcula or vice versa); click Apply.
  3. Without reopening the tool window, observe all visible panels.
  4. Click through all 8 tabs; observe each panel's colours.
  5. Open Settings; observe all settings pages.
  6. Return to Insights; verify `CARD_BG` tiles repainted.
  7. If Agent tab is open, verify the JCEF webview also switches to dark colours.
- **Expected — visual:** All `JBColor` values resolve to their dark-mode colours: `CARD_BG` dark = `#2B2D30`; `INFO_BG` dark = `#1E3A5F`; `SECONDARY_TEXT` dark = `#8B949E`; `BORDER` dark = `#444D56`. No panels retain light-mode hardcoded hex colours. No white-on-white or black-on-black unreadable text. SVG icons use dark variants where applicable.
- **Expected — behavioral:** `JBColor` resolves lazily at paint time so repaint after theme switch updates all colours without requiring a restart. `StatusColors.htmlColor()` must be called at render time (not cached) to resolve correctly after theme switch.
- **✅ Checks (tick each):**
  - [ ] StatTile backgrounds change from `#F7F8FA` (light) to `#2B2D30` (dark) after theme switch
  - [ ] Active ticket bar background changes from `#E3F2FD` to `#1E3A5F`
  - [ ] Secondary text labels readable in both themes (no invisible text)
  - [ ] Gutter coverage icons switch to dark variants (if enabled)
  - [ ] Agent webview updates to dark colour scheme (no white-flash or stale background)
  - [ ] Settings pages re-render with correct system colours
  - [ ] No console exceptions related to `LookAndFeel` or `UIManager` during theme switch
- **🐞 Bug signals:** Tiles retain light background after dark switch → `StatusColors` using `new Color()` instead of `JBColor`; unreadable text → hardcoded foreground colour bypassing `JBUI.CurrentTheme`; JCEF stays white in dark → webview not receiving theme-change notification.
- **Theme/size matrix:** Both themes (this scenario IS the matrix).
- **⛔ Write note:** None.

---

### [CORE-31] Tool window resize — narrow and wide reflow for each tab
- **Component(s):** All tab panels (layouts via `BorderLayout`, `GridLayout`, `BoxLayout`)
- **Preconditions:** All tabs materialized with data.
- **Steps:**
  1. Undock or float the "Workflow" tool window.
  2. Resize to approximately **200 px wide** (narrow).
  3. Click through Sprint, PR, Build, Quality, Automation, Handover, Insights (Today), Agent tabs; observe at each width.
  4. Resize to approximately **900 px wide** (wide); repeat.
  5. Resize back to the default docked width.
- **Expected — visual:** At 200 px: tab labels may abbreviate or scroll but must not overflow the window frame. Content panels should scroll vertically rather than clip horizontally (JBScrollPane wrappers present on most settings pages and Insights sub-panels). StatTile row (`GridLayout(1, 4, 8, 0)`) may compress tiles but text must not clip outside tile bounds.
- **Expected — behavioral:** No `NullPointerException` from layout managers at extreme widths. JBScrollPane in EmptyStatePanel allows vertical scrolling on very tall empty states.
- **✅ Checks (tick each):**
  - [ ] No horizontal overflow/clipping at 200 px for any tab
  - [ ] StatTile 4-column grid remains visible (may shrink) at narrow widths
  - [ ] Insights sub-tabs scrollable vertically at narrow widths
  - [ ] Settings pages (if opened at narrow dialog width) scrollable via JBScrollPane
  - [ ] No layout exceptions in idea.log during resize
- **🐞 Bug signals:** Tile text clipped to zero width → `GridLayout` minimum not set; scroll bars absent at narrow width → `JBScrollPane` removed; IDE freeze during resize → layout computation on EDT with expensive data read.
- **Theme/size matrix:** light + dark at both narrow and wide.
- **⛔ Write note:** None.

---

### [CORE-32] Weekly Insights digest notification — Monday startup trigger
- **Component(s):** `WeeklyDigestStartupActivity` (`core/.../insights/WeeklyDigestStartupActivity.kt`)
- **Preconditions:** `weeklyDigestEnabled = true` in PluginSettings; sessions exist from the past 7 days; today is **Monday** (or simulate by checking the logic manually); no report already generated this week.
- **⏰ Note (day-of-week + enable guards):** The activity returns early unless `today.dayOfWeek == DayOfWeek.MONDAY` (guard at `WeeklyDigestStartupActivity.kt:31`). **If today is not Monday, either temporarily set the system clock to a Monday and restart the IDE, or DEFER this scenario.** Also note `weeklyDigestEnabled` **defaults OFF** (`PluginSettings.kt:166`, `property(false)`) and is checked first (`WeeklyDigestStartupActivity.kt:22`) — **enable it in settings before testing**, otherwise the digest never runs regardless of the day.
- **Steps:**
  1. On a Monday, open IntelliJ with the plugin loaded.
  2. Wait at least 4 minutes after project open (startup quiesce delay = 4 × 60 × 1000 ms).
  3. Observe the IDE notification area for a balloon notification.
  4. Read the notification title and content.
  5. Locate the generated HTML file at `~/.workflow-orchestrator/<project>/reports/insights-weekly-<epoch>.html`.
  6. Open the HTML file in a browser; verify it renders.
  7. Reopen IntelliJ on the same Monday; verify the digest does NOT run again (duplicate guard).
- **Expected — visual:** Notification group `GenerateReportAction.GROUP_INSIGHTS`, type INFO, title "Weekly Insights Report Ready", content includes the filename `insights-weekly-<epoch>.html`.
- **Expected — behavioral:** Activity delays 4 minutes; only runs on `DayOfWeek.MONDAY`; `reportExistsForThisWeek()` checks week number to prevent duplicate generation. HTML report is written to `<rootDir>/reports/`.
- **✅ Checks (tick each):**
  - [ ] Notification appears approximately 4 minutes after project open on a Monday
  - [ ] Notification title and content correct
  - [ ] HTML file created at expected path
  - [ ] HTML renders without blank page in browser
  - [ ] Reopening IDE on the same Monday does NOT generate a second report
  - [ ] On a non-Monday, NO notification or file is generated
- **🐞 Bug signals:** Notification never fires on Monday → `weeklyDigestEnabled` default false (confirm in settings); duplicate reports → `reportExistsForThisWeek()` week-number comparison broken; blank HTML → `HtmlReportRenderer.render` receiving empty data.
- **Theme/size matrix:** Notification balloon light + dark (inherits IDE theme).
- **⛔ Write note:** `InsightsNarrativeService().generate()` with `includeAI = true` makes an LLM API call — READ-ONLY analysis request to configured Sourcegraph endpoint.
