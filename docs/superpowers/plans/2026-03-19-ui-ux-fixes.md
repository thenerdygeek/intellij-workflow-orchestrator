# UI/UX Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 3 CRITICAL (dead/unwired code), 7 HIGH (missing features vs production tools), and 5 MEDIUM (UX polish) issues identified in the UI/UX audit.

**Architecture:** Most changes are in 2 files: `agent-chat.html` (JCEF frontend) and `AgentController.kt` (wiring). Group changes by file to minimize conflicts.

**Tech Stack:** HTML/CSS/JS (JCEF), Kotlin (Swing), IntelliJ Platform APIs

---

## File Structure

### Modified Files

| File | Changes |
|------|---------|
| `agent/src/main/resources/webview/agent-chat.html` | Markdown rendering, collapsible tool details, copy button, example prompts, undo button in footer, line numbers in diffs |
| `agent/src/main/kotlin/.../ui/AgentController.kt` | Wire EditApprovalDialog, wire session resume from History, wire rollback button |
| `agent/src/main/kotlin/.../ui/AgentCefPanel.kt` | Add JS bridge methods for undo callback, working set, model label |
| `agent/src/main/kotlin/.../ui/AgentDashboardPanel.kt` | Add model label, tools button, working set bar, keyboard shortcuts |
| `agent/src/main/kotlin/.../ui/HistoryPanel.kt` | Wire resume callback, add search field, auto-refresh |
| `agent/src/main/kotlin/.../ui/HistoryTabProvider.kt` | Pass project + controller reference for resume wiring |
| `agent/src/main/kotlin/.../ui/TokenBudgetWidget.kt` | Show budget when idle, tooltip |

---

## Task 1: JCEF Frontend — Markdown, Collapsible Tools, Copy, Undo, Examples

All changes in `agent-chat.html`. This is the highest-impact single task.

**File:** `agent/src/main/resources/webview/agent-chat.html`

### What to add:

**1a. Markdown rendering in streaming output:**
Add a `renderMarkdown(text)` function that converts:
- ` ```lang\ncode\n``` ` → `<div class="code-wrapper"><div class="code-lang">lang</div><button class="code-copy">Copy</button><div class="code-block">code</div></div>`
- `` `inline` `` → `<code>inline</code>`
- `**bold**` → `<strong>bold</strong>`
- `- item` / `* item` → `<li>item</li>` wrapped in `<ul>`
- `# Header` → `<h3>Header</h3>`
- Newlines → `<br>` (outside code blocks)

Call `renderMarkdown()` in `endStream()` to re-render the completed message. During streaming, keep raw text (re-rendering on every token is too expensive).

**1b. Copy button on code blocks:**
In `renderMarkdown()`, when generating code blocks, include the copy button HTML. Add click handler:
```javascript
document.addEventListener('click', function(e) {
  if (e.target.classList.contains('code-copy')) {
    const code = e.target.parentElement.querySelector('.code-block').textContent;
    navigator.clipboard.writeText(code);
    e.target.textContent = 'Copied!';
    setTimeout(() => { e.target.textContent = 'Copy'; }, 2000);
  }
});
```

**1c. Collapsible tool details:**
In `appendToolCall()`, make the tool detail section collapsed by default. Add chevron to header. Toggle on click:
```javascript
// In tool card header
<span class="chevron">▶</span>

// Click handler
document.addEventListener('click', function(e) {
  const header = e.target.closest('.tool-header');
  if (header) {
    const detail = header.nextElementSibling;
    if (detail && detail.classList.contains('tool-detail')) {
      detail.classList.toggle('collapsed');
      header.querySelector('.chevron').classList.toggle('open');
    }
  }
});
```

CSS additions:
```css
.tool-header .chevron { font-size: 10px; color: var(--fg-muted); transition: transform 0.2s; }
.tool-header .chevron.open { transform: rotate(90deg); }
.tool-detail.collapsed { display: none; }
```

**1d. Example prompts in empty state:**
Replace the generic text with clickable prompts:
```html
<div class="prompts">
  <div class="prompt-btn" onclick="submitPrompt('Fix the SonarQube issues in this project')">🔍 Fix the SonarQube issues in this project</div>
  <div class="prompt-btn" onclick="submitPrompt('Create a PR for my current changes')">🔀 Create a PR for my current changes</div>
  <div class="prompt-btn" onclick="submitPrompt('What is the status of the latest build?')">📋 What is the status of the latest build?</div>
  <div class="prompt-btn" onclick="submitPrompt('Run tests and fix any failures')">🧪 Run tests and fix any failures</div>
</div>
```

The `submitPrompt(text)` function calls a JBCefJSQuery bridge to send the prompt to Kotlin.

**1e. Undo button in session footer:**
In `completeSession()`, when files were modified, add an undo button:
```javascript
const undoBtn = files && files.length > 0
  ? '<button class="footer-btn undo" onclick="requestUndo()">↩ Undo All Changes</button>'
  : '';
```

CSS:
```css
.footer-btn { padding: 6px 14px; border-radius: 6px; font-size: 12px; cursor: pointer; border: 1px solid var(--border); background: transparent; color: var(--fg-secondary); margin-right: 8px; }
.footer-btn.undo { color: var(--error); border-color: #5c2020; }
.footer-btn.undo:hover { background: #3B1818; }
```

**1f. Line numbers in diffs:**
In `appendDiff()`, add line number spans:
```javascript
oldLines.forEach((l, i) => {
  diffHtml += '<div class="diff-line diff-rem"><span class="ln">' + (i+1) + '</span>- ' + esc(l) + '</div>';
});
```

CSS: `.diff-line .ln { display: inline-block; width: 32px; color: var(--fg-muted); text-align: right; margin-right: 8px; user-select: none; }`

- [ ] **Step 1:** Add `renderMarkdown(text)` function and CSS for markdown elements
- [ ] **Step 2:** Wire `renderMarkdown()` into `endStream()` to re-render completed messages
- [ ] **Step 3:** Add copy button logic (click handler + clipboard + "Copied!" feedback)
- [ ] **Step 4:** Add collapsible tool details (chevron + collapsed class + click handler)
- [ ] **Step 5:** Replace empty state with example prompts (clickable, styled)
- [ ] **Step 6:** Add undo button to session footer + `requestUndo()` JS function
- [ ] **Step 7:** Add line numbers to diff rendering
- [ ] **Step 8:** Test: verify all changes render correctly in JCEF
- [ ] **Step 9:** Commit: `feat(agent): JCEF — markdown, collapsible tools, copy, prompts, undo, line numbers`

---

## Task 2: Wire CRITICAL — Rollback UI, EditApprovalDialog, Session Resume

Three CRITICAL wiring fixes in the Kotlin layer.

**Files:**
- Modify: `agent/src/main/kotlin/.../ui/AgentController.kt`
- Modify: `agent/src/main/kotlin/.../ui/AgentCefPanel.kt`
- Modify: `agent/src/main/kotlin/.../ui/HistoryPanel.kt`
- Modify: `agent/src/main/kotlin/.../ui/HistoryTabProvider.kt`

### 2a. Wire rollback/undo button

In `AgentCefPanel.kt`, add a JBCefJSQuery for the undo callback:
```kotlin
private var undoQuery: JBCefJSQuery? = null

// In createBrowser():
undoQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
    addHandler { _ ->
        onUndoRequested?.invoke()
        JBCefJSQuery.Response("ok")
    }
}

// Inject into page after load:
val undoJs = undoQuery!!.inject("'undo'")
js("window.requestUndo = function() { $undoJs }")
```

Add callback: `var onUndoRequested: (() -> Unit)? = null`

In `AgentDashboardPanel.kt`: expose the undo callback delegation.

In `AgentController.kt`: wire undo to `AgentRollbackManager`:
```kotlin
dashboard.cefPanel?.onUndoRequested = {
    val checkpointId = session?.let { /* get latest checkpoint from rollback manager */ }
    if (checkpointId != null) {
        rollbackManager?.rollbackToCheckpoint(checkpointId)
        dashboard.appendStatus("All agent changes have been undone.", RichStreamingPanel.StatusType.SUCCESS)
    }
}
```

Keep a reference to `rollbackManager` across the session.

### 2b. Wire EditApprovalDialog

In `AgentController.showApprovalDialog()`, detect edit operations and use EditApprovalDialog:
```kotlin
private fun showApprovalDialog(description: String, riskLevel: RiskLevel): ApprovalResult {
    // ...
    if (riskLevel == RiskLevel.MEDIUM && description.contains("edit_file")) {
        // Extract file path and content from description
        // Show EditApprovalDialog with DiffManager
        val dialog = EditApprovalDialog(project, filePath, originalContent, proposedContent, description)
        dialog.show()
        result = if (dialog.approved) ApprovalResult.Approved else ApprovalResult.Rejected
    }
    // ...
}
```

The challenge: `showApprovalDialog` only receives `description` (a string), not the actual file content. The approval gate fires BEFORE the tool executes, so we don't have old/new content yet.

**Pragmatic fix:** When `approvalRequiredForEdits` is true, the `EditFileTool` already returns `APPROVAL_REQUIRED` with a diff preview in the tool result. The approval dialog should fire from the CONTROLLER when it sees this result, not from the gate.

Actually — re-reading the code, `EditFileTool` returns `isError = true` with `APPROVAL_REQUIRED` in the content. The current flow: the tool result goes to context as an error, the LLM sees the error, and... nothing useful happens.

**Better approach:** Don't use the ApprovalGate for edit approval. Instead, when `EditFileTool` detects `approvalRequiredForEdits`, it should return the old/new content as structured data, and the controller shows EditApprovalDialog, then re-executes the edit if approved. This is a larger refactor — for now, just wire the basic case.

**Simplest viable fix:** In the AgentController's approval callback, for MEDIUM risk (file edits), show a dialog that says "The agent wants to edit [file]. Allow?" with a "View Diff" button that opens the file in IntelliJ's diff viewer. Not the full EditApprovalDialog, but much better than a plain Yes/No.

### 2c. Wire session resume in History

In `HistoryTabProvider.createPanel()`:
```kotlin
override fun createPanel(project: Project): JComponent {
    val panel = HistoryPanel()
    panel.onResumeSession = { sessionId ->
        // Find or create AgentController, load session, switch to Agent tab
        // This requires access to the Agent tab's controller
    }
    return panel
}
```

The challenge: HistoryTabProvider doesn't have access to the AgentController. Solution: use a project-level service to store the active controller reference, or use the event bus.

**Simplest approach:** Store the AgentController in a project-level service that both tabs can access:
```kotlin
// In AgentService.kt, add:
var activeController: AgentController? = null
```

Then in AgentTabProvider, set it:
```kotlin
val controller = AgentController(project, dashboard)
AgentService.getInstance(project).activeController = controller
```

And in HistoryTabProvider:
```kotlin
panel.onResumeSession = { sessionId ->
    val controller = AgentService.getInstance(project).activeController
    if (controller != null) {
        controller.resumeSession(sessionId)
        // Switch to Agent tab
        ToolWindowManager.getInstance(project).getToolWindow("Workflow")?.let { tw ->
            tw.contentManager.contents.firstOrNull { it.displayName == "Agent" }?.let {
                tw.contentManager.setSelectedContent(it)
            }
        }
    }
}
```

Add `resumeSession(sessionId)` to AgentController:
```kotlin
fun resumeSession(sessionId: String) {
    val agentService = AgentService.getInstance(project)
    val loaded = ConversationSession.load(sessionId, project, agentService) ?: run {
        dashboard.appendError("Failed to load session $sessionId")
        return
    }
    session?.markCompleted(true) // close current
    session = loaded
    dashboard.startSession(loaded.title)
    // Replay messages to UI
    loaded.contextManager.getMessages().forEach { msg ->
        when (msg.role) {
            "user" -> dashboard.appendUserMessage(msg.content ?: "")
            "assistant" -> if (!msg.content.isNullOrBlank()) dashboard.appendStreamToken(msg.content!!)
            // tool results shown as status
        }
    }
    dashboard.flushStreamBuffer()
    dashboard.appendStatus("Session resumed. ${loaded.messageCount} messages restored.", RichStreamingPanel.StatusType.SUCCESS)
    dashboard.focusInput()
}
```

- [ ] **Step 1:** Add `onUndoRequested` callback to AgentCefPanel, create JBCefJSQuery bridge
- [ ] **Step 2:** Wire undo button in AgentController to AgentRollbackManager
- [ ] **Step 3:** Improve showApprovalDialog for MEDIUM-risk file edits (better dialog than Yes/No)
- [ ] **Step 4:** Add `activeController` to AgentService for cross-tab access
- [ ] **Step 5:** Add `resumeSession(sessionId)` to AgentController
- [ ] **Step 6:** Wire HistoryPanel.onResumeSession in HistoryTabProvider via AgentService
- [ ] **Step 7:** Test: complete a task with edits → undo button appears → click undoes changes
- [ ] **Step 8:** Test: interrupt a session → History shows it → click Resume → conversation restored
- [ ] **Step 9:** Commit: `fix(agent): wire rollback UI, edit approval, session resume — 3 CRITICAL fixes`

---

## Task 3: Toolbar Enhancements — Model, Tools, Working Set, Token Widget

**Files:**
- Modify: `agent/src/main/kotlin/.../ui/AgentDashboardPanel.kt`
- Modify: `agent/src/main/kotlin/.../ui/TokenBudgetWidget.kt`
- Modify: `agent/src/main/kotlin/.../ui/AgentController.kt`

### 3a. Model indicator in toolbar
Add a `JBLabel` showing current model name, clickable to open settings:
```kotlin
val modelLabel = JBLabel("claude-sonnet-4").apply {
    font = JBUI.Fonts.smallFont()
    foreground = JBColor.GRAY
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    toolTipText = "Current LLM model — click to change"
}
```

Load the model name from `AgentSettings.getInstance(project).state.sourcegraphChatModel` on init.

### 3b. Tools button showing active count
```kotlin
val toolsButton = JButton("Tools").apply {
    icon = AllIcons.Nodes.Plugin
    toolTipText = "View available tools"
    addActionListener { showToolsPopup() }
}
```

`showToolsPopup()` creates a `JBPopupMenu` listing all tools with their descriptions.

### 3c. Working set bar (collapsible)
A thin bar below the toolbar showing files the agent has worked with:
```kotlin
val workingSetBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
    border = JBUI.Borders.merge(JBUI.Borders.empty(2, 8), JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0), true)
    isVisible = false // Hidden until files are tracked
}
```

Update from controller when tool results have artifacts.

### 3d. TokenBudgetWidget — show budget when idle
```kotlin
fun update(usedTokens: Int, maxTokens: Int) {
    if (maxTokens <= 0) {
        label.text = "Budget: 150K"
        progressBar.isVisible = false
        return
    }
    progressBar.isVisible = true
    // ... existing logic ...
}

// Add tooltip
init {
    toolTipText = "Token budget — shows how much context the agent has used"
}
```

- [ ] **Step 1:** Add model label to toolbar, load from settings
- [ ] **Step 2:** Add Tools button with popup listing all 28 tools
- [ ] **Step 3:** Add working set bar (collapsible, hidden until files tracked)
- [ ] **Step 4:** Fix TokenBudgetWidget idle state and add tooltip
- [ ] **Step 5:** Commit: `feat(agent): toolbar — model indicator, tools button, working set, token tooltip`

---

## Task 4: History Panel Polish + Keyboard Shortcuts

**Files:**
- Modify: `agent/src/main/kotlin/.../ui/HistoryPanel.kt`
- Modify: `agent/src/main/kotlin/.../ui/AgentDashboardPanel.kt`
- Create: `agent/src/main/resources/META-INF/plugin.xml` (action registrations)

### 4a. History search
Add a `SearchTextField` at the top of HistoryPanel that filters sessions by title/project:
```kotlin
val searchField = com.intellij.ui.SearchTextField().apply {
    addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) { filterSessions(text) }
    })
}
```

### 4b. History auto-refresh
Add a timer that refreshes every 30 seconds when visible:
```kotlin
val refreshTimer = Timer(30_000) { if (isShowing) refresh() }
refreshTimer.start()
```

### 4c. Keyboard shortcuts
Register IntelliJ actions in plugin.xml:
```xml
<action id="agent.focusInput" class="...FocusAgentInputAction" text="Focus Agent Input">
    <keyboard-shortcut first-keystroke="meta L" keymap="$default"/>
</action>
<action id="agent.newChat" class="...NewAgentChatAction" text="New Agent Chat">
    <keyboard-shortcut first-keystroke="meta shift N" keymap="$default"/>
</action>
```

In AgentDashboardPanel, add Escape key handler on the chat input to call cancel.

- [ ] **Step 1:** Add search field to HistoryPanel with filtering
- [ ] **Step 2:** Add 30-second auto-refresh timer
- [ ] **Step 3:** Add Escape key handler for cancel in dashboard
- [ ] **Step 4:** Register keyboard shortcut actions in plugin.xml (if feasible without touching main plugin.xml)
- [ ] **Step 5:** Commit: `feat(agent): history search + auto-refresh + keyboard shortcuts`

---

## Verification

```bash
./gradlew :agent:compileKotlin
./gradlew :agent:test --rerun --no-build-cache
./gradlew buildPlugin
```

Manual verification in browser (open agent-chat.html):
- Markdown renders: bold, code, code blocks, headers ✓
- Code blocks have copy button ✓
- Tool details collapse/expand on click ✓
- Example prompts in empty state ✓
- Session footer has Undo button ✓
- Diffs have line numbers ✓
