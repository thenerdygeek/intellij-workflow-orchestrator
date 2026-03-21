# UI/UX Audit Fixes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all 40+ UI/UX findings from the comprehensive agent tab audit — covering light mode, accessibility, emojis, approval dialogs, streaming UX, and polish.

**Architecture:** Changes are batched by file to minimize merge conflicts. agent-chat.html (CSS+JS) changes are grouped first, then Kotlin files (dialogs, controller, widgets). Each task is independently committable and testable.

**Tech Stack:** HTML/CSS/JS (JCEF), Kotlin/Swing (IntelliJ Platform), JBColor for theme-aware colors

---

## File Structure

### Modified Files

| File | Changes |
|------|---------|
| `agent/src/main/resources/webview/agent-chat.html` | CSS: light-mode-safe hovers/dividers, ANSI bg, emoji→unicode, focus-visible, reduced-motion, skill banner, scrollbar, diff max-height, thinking collapsible, code-copy visibility, user avatar, agent label, table filter width. JS: streaming flash fix, live tool timer, tool expand/collapse, retry button, scroll behavior, close button consistency, tool args truncation. |
| `agent/src/main/kotlin/.../ui/AgentCefPanel.kt` | New methods for retry, expand/collapse tools. |
| `agent/src/main/kotlin/.../ui/AgentController.kt` | Approval dialog improvements (risk colors, allow-all for commands), error retry wiring, resume session message replay, queued message cancel. |
| `agent/src/main/kotlin/.../ui/AgentDashboardPanel.kt` | Toolbar overflow menu, retry delegation, queued message cancel UI. |
| `agent/src/main/kotlin/.../ui/CommandApprovalDialog.kt` | Risk level color distinction, allow-all option, theme-aware colors. |
| `agent/src/main/kotlin/.../ui/EditApprovalDialog.kt` | Better fallback when regex parsing fails. |
| `agent/src/main/kotlin/.../ui/TokenBudgetWidget.kt` | Fix hardcoded "Budget: 150K" label. |
| `agent/src/main/kotlin/.../ui/HistoryPanel.kt` | Fix wrong icon, add resume button, right-click context menu. |

---

## Task 1: Light Mode CSS Fixes (C5, C6, H5, H6, H7, M14, L7)

Fixes all hardcoded dark-only colors that break on light IDE themes.

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html`

- [ ] **Step 1: Add theme-aware hover/divider CSS variables to `:root`**

In the `:root` CSS block (lines 10-43), add after the `--link` variable:

```css
  --hover-overlay: rgba(255,255,255,0.03);
  --hover-overlay-strong: rgba(255,255,255,0.05);
  --divider-subtle: rgba(255,255,255,0.05);
  --row-alt: rgba(255,255,255,0.02);
```

These will be overridden by the Kotlin theme injection for light mode.

- [ ] **Step 2: Replace all hardcoded `rgba(255,255,255,x)` with CSS variables**

Replace these occurrences:
- Line 150: `.tool-header:hover` — `rgba(255,255,255,0.03)` → `var(--hover-overlay)`
- Line 273: `.md-table th[data-sortable]:hover` — `rgba(255,255,255,0.05)` → `var(--hover-overlay-strong)`
- Line 335: `.md-table tr:nth-child(even) td` — `rgba(255,255,255,0.02)` → `var(--row-alt)`
- Line 503: `.footer-btn:hover` — `rgba(255,255,255,0.05)` → `var(--hover-overlay-strong)`
- Line 515: `.plan-step` — `rgba(255,255,255,0.05)` → `var(--divider-subtle)`
- Line 610: `.question-option:hover` — `rgba(255,255,255,0.03)` → `var(--hover-overlay)`
- Line 716: `.question-summary-item` — `rgba(255,255,255,0.05)` → `var(--divider-subtle)`
- Line 854: `.skill-banner-dismiss:hover` — `rgba(255,255,255,0.1)` → `var(--hover-overlay-strong)`
- Line 874: `.tool-row:hover` — `rgba(255,255,255,0.04)` → `var(--hover-overlay)`

- [ ] **Step 3: Fix ANSI output background**

Line 217: Replace `background: #0d1117;` with `background: var(--code-bg);`

- [ ] **Step 4: Fix skill banner hardcoded colors**

Lines 834-842: Replace `.skill-banner` styles:
```css
.skill-banner {
    background: var(--badge-edit-bg);
    border-bottom: 1px solid var(--border);
    padding: 6px 16px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: 12px;
    color: var(--badge-edit-fg);
    position: sticky;
    top: 0;
    z-index: 10;
}
```

- [ ] **Step 5: Update AgentCefPanel.kt to inject light-mode overlay values**

In `AgentCefPanel.applyCurrentTheme()`, add the new variables to both light and dark maps:

For dark map, add:
```kotlin
"hover-overlay" to "rgba(255,255,255,0.03)",
"hover-overlay-strong" to "rgba(255,255,255,0.05)",
"divider-subtle" to "rgba(255,255,255,0.05)",
"row-alt" to "rgba(255,255,255,0.02)"
```

For light map, add:
```kotlin
"hover-overlay" to "rgba(0,0,0,0.03)",
"hover-overlay-strong" to "rgba(0,0,0,0.05)",
"divider-subtle" to "rgba(0,0,0,0.05)",
"row-alt" to "rgba(0,0,0,0.02)"
```

- [ ] **Step 6: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt
git commit -m "fix(agent-ui): light mode CSS fixes for hovers, dividers, ANSI, skill banner

Replace all hardcoded rgba(255,255,255,x) with theme-aware CSS variables
injected from Kotlin. Fix ANSI output background and skill banner colors
to use theme variables instead of hardcoded dark-mode values."
```

---

## Task 2: Replace Emojis with Unicode Symbols (C1-C4, M3, L5)

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html`

- [ ] **Step 1: Replace empty state emojis**

Lines 998-1006. Replace the empty state HTML:

```html
<div id="chat-container">
  <div class="empty-state" id="empty-state">
    <div class="icon" style="font-size:28px;opacity:0.4;">&#x2726;</div>
    <div class="title">AI Agent</div>
    <div class="desc">Ask me to analyze code, fix bugs, run builds, check quality, or manage tickets.</div>
    <div class="prompts">
      <div class="prompt-btn" onclick="submitPrompt(this.dataset.prompt)" data-prompt="Fix the SonarQube issues in this project"><span style="opacity:0.6">&#x25B7;</span> Fix the SonarQube issues in this project</div>
      <div class="prompt-btn" onclick="submitPrompt(this.dataset.prompt)" data-prompt="Create a PR for my current changes"><span style="opacity:0.6">&#x25B7;</span> Create a PR for my current changes</div>
      <div class="prompt-btn" onclick="submitPrompt(this.dataset.prompt)" data-prompt="What is the status of the latest build?"><span style="opacity:0.6">&#x25B7;</span> What is the status of the latest build?</div>
      <div class="prompt-btn" onclick="submitPrompt(this.dataset.prompt)" data-prompt="Run tests and fix any failures"><span style="opacity:0.6">&#x25B7;</span> Run tests and fix any failures</div>
    </div>
  </div>
</div>
```

`&#x2726;` = ✦ (four-pointed star), `&#x25B7;` = ▷ (right-pointing triangle) — both are universal Unicode, not emoji.

- [ ] **Step 2: Replace plan card emojis**

In the `renderPlan` JS function (~line 1916), find `<span class="plan-icon">📋</span>` and replace with:
```javascript
'<span class="plan-icon" style="font-size:16px;">&#x2630;</span> Implementation Plan'
```
`&#x2630;` = ☰ (trigram for heaven — commonly used as list/menu icon).

In the plan step comment button (~line 1910), find `💬` and replace with `&#x2709;` (✉ envelope).

- [ ] **Step 3: Replace session footer emojis**

In `completeSession` JS function (~line 1645), find:
- `↩ Undo All Changes` → `&#x21A9; Undo All Changes` (↩ is already unicode, but ensure it's entity-coded)
- `📋 View Trace` → `&#x2630; View Trace` (☰ list icon — consistent with plan/summary usage)

- [ ] **Step 4: Replace question wizard emojis**

In `showQuestion` JS (~line 2290), find `&#x1F4AC;` (💬) in chat-about button and replace with `&#x2709;` (✉).

In `showQuestionSummaryFromState` JS (~line 2354), find `&#x1F4CB;` (📋) in summary header and replace with `&#x2630;` (☰).

In `showQuestionSummary` JS (~line 2424), same replacement.

- [ ] **Step 5: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html
git commit -m "fix(agent-ui): replace emoji icons with universal Unicode symbols

Replace all emoji usage (⚡🔍🔀📋🧪💬) with Unicode technical symbols
that render consistently across all platforms and JVM versions. Enterprise
environments with restricted emoji fonts will now display correctly."
```

---

## Task 3: Accessibility — Reduced Motion & Focus Indicators (H8, H9)

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html`

- [ ] **Step 1: Add `prefers-reduced-motion` media query**

Add at the end of the CSS section (before `</style>`):

```css
/* ═══════════════════════════════════════════════════
   Accessibility — Reduced Motion
   ═══════════════════════════════════════════════════ */
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
  .stream-cursor::after { animation: none; opacity: 1; }
  .tool-status-icon.running { animation: none; border-color: var(--link); }
  .skeleton { animation: none; background: var(--tool-bg); }
  .timeline-dot.running { animation: none; }
}
```

- [ ] **Step 2: Add `:focus-visible` styles for all interactive elements**

Add after the reduced motion block:

```css
/* ═══════════════════════════════════════════════════
   Accessibility — Keyboard Focus Indicators
   ═══════════════════════════════════════════════════ */
.prompt-btn:focus-visible,
.plan-btn:focus-visible,
.qnav-btn:focus-visible,
.question-option:focus-visible,
.tool-row:focus-visible,
.tab-btn:focus-visible,
.footer-btn:focus-visible,
.code-copy:focus-visible,
.md-table-filter:focus-visible,
.question-cancel:focus-visible,
.skill-banner-dismiss:focus-visible,
.tools-panel-header .close-btn:focus-visible,
.td-close:focus-visible {
  outline: 2px solid var(--link);
  outline-offset: 2px;
}
```

- [ ] **Step 3: Add `tabindex="0"` to interactive elements that need it**

In the `code-copy` button (rendered by `renderer.code`), add `tabindex="0"`:
```javascript
'<button class="code-copy" tabindex="0" onclick="copyCode(this)">Copy</button>'
```

- [ ] **Step 4: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html
git commit -m "fix(agent-ui): add prefers-reduced-motion and focus-visible indicators

Respect user's motion preferences by disabling animations when
prefers-reduced-motion is set. Add visible focus outlines for all
interactive elements for keyboard navigation accessibility."
```

---

## Task 4: Streaming Flash Fix & Live Tool Timer (H1, H2, L6)

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html`

- [ ] **Step 1: Fix streaming-to-markdown re-render flash**

The issue: `endStream()` replaces the entire streaming content with `renderMarkdown(_streamRawText)`, causing a visible layout reflow.

The existing `appendToken` accumulates raw text and appends escaped HTML during streaming. `endStream()` does a single `renderMarkdown()` re-render. The "flash" is primarily caused by smooth scrolling lagging behind. Step 4 (scroll fix) addresses the root cause. We add a debounced markdown render to reduce the visual gap between streaming and final output.

Add a module-level debounce variable near `_streamRawText`:
```javascript
var _renderDebounce = null;
```

In `appendToken`, after `_streamRawText += token;` and the `insertAdjacentHTML` line, add debounced rendering:

```javascript
  _streamRawText += token;
  const html = esc(token).replace(/\n/g, '<br>');
  activeStreamEl._inner.insertAdjacentHTML('beforeend', html);
  // Debounced markdown render — reduces flash on endStream by keeping display close to final
  clearTimeout(_renderDebounce);
  _renderDebounce = setTimeout(function() {
    if (activeStreamEl && activeStreamEl._inner && _streamRawText) {
      activeStreamEl._inner.innerHTML = renderMarkdown(_streamRawText);
      scrollToBottom();
    }
  }, 300);
  scrollToBottom();
```

The `endStream()` function stays as-is — its final `renderMarkdown` call is still needed, but now the visual difference between streamed content and final render is minimal since debounced renders keep them in sync.
```

- [ ] **Step 2: Add live elapsed timer to running tool cards**

Add a CSS class for the timer:
```css
.tool-timer { font-size: 10px; color: var(--fg-muted); font-family: var(--font-mono); margin-left: auto; padding: 1px 6px; }
```

Modify `appendToolCall` to start a timer when status is RUNNING:

Find the `appendToolCall` function (~line 1700). After creating the card element, add timer logic:

```javascript
function appendToolCall(toolName, args, statusStr) {
  endStream(); hideEmpty();
  var type = toolType(toolName);
  var badge = badgeFor(type);
  var target = extractTarget(toolName, args);
  var statusIcon = statusStr === 'SUCCESS' ? '<span class="tool-status-icon ok">✓</span>'
    : statusStr === 'FAILED' ? '<span class="tool-status-icon fail">✗</span>'
    : '<span class="tool-status-icon running"></span>';

  var timerId = 'timer-' + Date.now();
  var timerHtml = statusStr === 'RUNNING' ? '<span class="tool-timer" id="' + timerId + '">0.0s</span>' : '';

  // Note: also add module-level array near top of script:
  // var _activeTimerIntervals = [];

  var argsHtml = args && args.length > 2
    ? '<div class="tool-detail collapsed">' + esc(args.substring(0, 800)) + '</div>' : '';

  var card = document.createElement('div');
  card.className = 'message';
  card.innerHTML =
    '<div class="tool-card type-' + type + '" id="tool-' + Date.now() + '">' +
      '<div class="tool-header">' +
        '<span class="chevron">▶</span>' +
        badge + '<span class="tool-target">' + esc(target) + '</span>' +
        timerHtml + statusIcon +
      '</div>' + argsHtml +
    '</div>';
  container.appendChild(card);

  // Start live timer if running
  if (statusStr === 'RUNNING') {
    var startMs = Date.now();
    card._timerId = timerId;
    card._timerInterval = setInterval(function() {
      var el = document.getElementById(timerId);
      if (el) {
        var elapsed = (Date.now() - startMs) / 1000;
        el.textContent = elapsed < 10 ? elapsed.toFixed(1) + 's' : Math.floor(elapsed) + 's';
      } else {
        clearInterval(card._timerInterval);
      }
    }, 100);
    _activeTimerIntervals.push(card._timerInterval);
  }

  scrollToBottom();
}
```

Also add timer cleanup to `clearChat()` — find the existing `clearChat` function and add at the top:
```javascript
function clearChat() {
  // Clean up any running tool timers
  _activeTimerIntervals.forEach(function(id) { clearInterval(id); });
  _activeTimerIntervals = [];
  // ... rest of existing clearChat code
```

- [ ] **Step 3: Stop timer and show final duration in updateToolResult**

In `updateToolResult` (~line 1726), add at the beginning:

```javascript
function updateToolResult(result, durationMs) {
  var toolCards = container.querySelectorAll('.tool-card');
  if (toolCards.length === 0) return;
  var lastCard = toolCards[toolCards.length - 1];
  var parentMsg = lastCard.closest('.message');

  // Stop live timer
  if (parentMsg && parentMsg._timerInterval) {
    clearInterval(parentMsg._timerInterval);
    delete parentMsg._timerInterval;
  }
  // Remove live timer element (will be replaced by duration badge)
  var timerEl = lastCard.querySelector('.tool-timer');
  if (timerEl) timerEl.remove();

  // ... rest of existing updateToolResult logic ...
```

- [ ] **Step 4: Fix smooth scroll lag during streaming**

In `scrollToBottom` (~line 1023), use `auto` instead of `smooth` to avoid lag:

```javascript
function scrollToBottom() {
  window.scrollTo({ top: document.body.scrollHeight, behavior: 'auto' });
}
```

- [ ] **Step 5: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html
git commit -m "fix(agent-ui): reduce streaming flash, add live tool timer, fix scroll lag

Incrementally render markdown during streaming every ~500 chars to
minimize the re-layout flash on endStream(). Add live elapsed time
counter on running tool cards. Switch scrollToBottom to 'auto' behavior
to prevent scroll lag during rapid tool call sequences."
```

---

## Task 5: Approval Dialog Improvements (H3, H4, H5, L7)

**Files:**
- Modify: `agent/src/main/kotlin/.../ui/CommandApprovalDialog.kt`
- Modify: `agent/src/main/kotlin/.../ui/AgentController.kt`

- [ ] **Step 1: Add risk-level color distinction and "Allow All" to CommandApprovalDialog**

Replace `CommandApprovalDialog.kt` `createCenterPanel()`:

```kotlin
override fun createCenterPanel(): JComponent {
    val panel = JPanel(BorderLayout())
    panel.preferredSize = Dimension(JBUI.scale(600), JBUI.scale(300))
    panel.border = JBUI.Borders.empty(12)

    // Risk label with severity-specific color and icon
    val (riskColor, riskIcon) = when (riskAssessment.uppercase()) {
        "HIGH" -> JBColor.RED to AllIcons.General.BalloonError
        "MEDIUM" -> JBColor.ORANGE to AllIcons.General.BalloonWarning
        else -> JBColor.GRAY to AllIcons.General.BalloonInformation
    }
    val riskLabel = JBLabel("<html><b>Risk:</b> $riskAssessment</html>", riskIcon, JBLabel.LEFT)
    riskLabel.foreground = riskColor
    riskLabel.border = JBUI.Borders.emptyBottom(8)
    panel.add(riskLabel, BorderLayout.NORTH)

    // Command display — theme-aware terminal style
    val cmdArea = JBTextArea().apply {
        text = "Working directory: $workingDir\n\n$ $command"
        isEditable = false
        font = JBUI.Fonts.create("Monospaced", 13)
        border = JBUI.Borders.empty(8)
        background = JBColor(Color(0xF1F5F9), Color(40, 44, 52))
        foreground = JBColor(Color(0x1E293B), Color(171, 178, 191))
    }
    panel.add(JBScrollPane(cmdArea), BorderLayout.CENTER)

    val warning = JBLabel("<html><i>The agent wants to execute this command. Review it carefully.</i></html>")
    warning.border = JBUI.Borders.emptyTop(8)
    panel.add(warning, BorderLayout.SOUTH)

    return panel
}
```

Add import: `import com.intellij.icons.AllIcons`

- [ ] **Step 2: Add "Allow All" button to command approval in AgentController**

In `AgentController.showApprovalDialog()` (~line 584), find the command approval section. Replace:

```kotlin
if (riskLevel == RiskLevel.HIGH && description.contains("run_command")) {
    val cmdMatch = Regex("run_command\\((.+?)\\)").find(description)
    val dialog = CommandApprovalDialog(project, cmdMatch?.groupValues?.get(1) ?: description, project.basePath ?: ".", riskLevel.name)
    dialog.show()
    result = if (dialog.approved) ApprovalResult.Approved else ApprovalResult.Rejected
}
```

With:

```kotlin
if (description.contains("run_command")) {
    val cmdMatch = Regex("run_command\\((.+?)\\)").find(description)
    val dialog = CommandApprovalDialog(project, cmdMatch?.groupValues?.get(1) ?: description, project.basePath ?: ".", riskLevel.name)
    dialog.show()
    result = if (dialog.approved) ApprovalResult.Approved else ApprovalResult.Rejected
    // Check if user selected "Allow All Commands (This Session)"
    if (dialog.allowAll) {
        sessionAutoApprove = true
    }
}
```

Add `var allowAll = false; private set` field (matching the `approved` pattern) and an "Allow All Commands" checkbox to `CommandApprovalDialog`:

```kotlin
private val allowAllCheckbox = com.intellij.ui.components.JBCheckBox("Allow all commands this session").apply {
    font = JBUI.Fonts.smallFont()
    isVisible = riskAssessment.uppercase() != "HIGH"  // Don't offer for HIGH risk
}

// In createCenterPanel, add before the warning:
val bottomPanel = JPanel(BorderLayout())
bottomPanel.add(allowAllCheckbox, BorderLayout.WEST)
bottomPanel.add(warning, BorderLayout.CENTER)
panel.add(bottomPanel, BorderLayout.SOUTH)

// In doOKAction:
override fun doOKAction() {
    approved = true
    allowAll = allowAllCheckbox.isSelected
    super.doOKAction()
}
```

- [ ] **Step 3: Improve edit approval fallback**

In `AgentController.showApprovalDialog()` (~line 618), the fallback dialog shows raw JSON. Replace the fallback message:

```kotlin
val answer = Messages.showYesNoCancelDialog(
    project,
    "The agent wants to edit a file.\n\nFile: ${filePath ?: "unknown"}\nAction: Replace text content\n\nYou can undo this change after it's applied.",
    "Agent File Edit",
    "Allow", "Block", "Allow All (This Session)",
    Messages.getQuestionIcon()
)
```

- [ ] **Step 4: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/CommandApprovalDialog.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "fix(agent-ui): improve approval dialogs with risk colors and allow-all

Add color-coded risk levels (red HIGH, orange MEDIUM, gray LOW) with icons
to command approval. Add 'Allow All Commands' checkbox for session-level
auto-approval. Fix edit approval fallback to show human-readable message
instead of raw JSON. Theme-aware terminal colors in command preview."
```

---

## Task 6: Token Budget Widget & Error Retry (H6, M10)

**Files:**
- Modify: `agent/src/main/kotlin/.../ui/TokenBudgetWidget.kt`
- Modify: `agent/src/main/resources/webview/agent-chat.html`
- Modify: `agent/src/main/kotlin/.../ui/AgentCefPanel.kt`
- Modify: `agent/src/main/kotlin/.../ui/AgentController.kt`
- Modify: `agent/src/main/kotlin/.../ui/AgentDashboardPanel.kt`

- [ ] **Step 1: Fix TokenBudgetWidget hardcoded label**

In `TokenBudgetWidget.kt`, line 16, change:
```kotlin
private val label = JBLabel("Budget: 150K  ")
```
to:
```kotlin
private val label = JBLabel("")
```

In the `update()` method, line 28, change:
```kotlin
label.text = "Budget: 150K  "
```
to:
```kotlin
label.text = ""
```

This shows nothing when idle (no misleading "150K").

- [ ] **Step 2: Add error retry button to JCEF**

Add CSS:
```css
.retry-btn {
    display: inline-block;
    padding: 6px 14px;
    border-radius: 6px;
    font-size: 12px;
    cursor: pointer;
    border: 1px solid var(--link);
    background: transparent;
    color: var(--link);
    margin-top: 8px;
    transition: background 0.15s, color 0.15s;
}
.retry-btn:hover { background: var(--link); color: white; }
```

Add JS function:
```javascript
function showRetryButton(lastMessage) {
    var btn = document.createElement('div');
    btn.className = 'message';
    btn.innerHTML = '<button class="retry-btn" data-retry-message="' + esc(lastMessage) + '">&#x21BB; Retry last message</button>';
    container.appendChild(btn);
    scrollToBottom();
}

document.addEventListener('click', function(e) {
    var retryBtn = e.target.closest('.retry-btn');
    if (retryBtn && window._submitPrompt) {
        var msg = retryBtn.dataset.retryMessage;
        retryBtn.closest('.message').remove();
        window._submitPrompt(msg);
    }
});
```

- [ ] **Step 3: Add showRetryButton to AgentCefPanel.kt**

```kotlin
fun showRetryButton(lastMessage: String) {
    callJs("showRetryButton(${jsonStr(lastMessage)})")
}
```

- [ ] **Step 4: Add delegation in AgentDashboardPanel.kt**

```kotlin
fun showRetryButton(lastMessage: String) {
    cefPanel?.showRetryButton(lastMessage)
}
```

- [ ] **Step 5: Wire retry in AgentController.handleResult for Failed results**

In `handleResult()` (~line 558), after `dashboard.appendError(result.error)`, add:

```kotlin
// Show retry button with the last user message
session?.let { s ->
    val lastUserMsg = s.contextManager.getMessages()
        .lastOrNull { it.role == "user" }?.content
    if (lastUserMsg != null) {
        dashboard.showRetryButton(lastUserMsg)
    }
}
```

- [ ] **Step 6: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/TokenBudgetWidget.kt agent/src/main/resources/webview/agent-chat.html agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "fix(agent-ui): fix token budget label, add retry button on error

Remove hardcoded 'Budget: 150K' when idle — widget now shows nothing until
tokens are actually used. Add retry button after agent failures so users
can re-send the last message without retyping."
```

---

## Task 7: Message Identity, Diff Limits, Thinking Collapse (M1, M2, M4, M5, M11)

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html`

- [ ] **Step 1: Add agent label before streaming messages**

**NOTE:** Task 4 already modified `appendToken` to add debounced rendering. Apply this change ON TOP of Task 4's version. The change is only in the `if (!activeStreamEl)` block — add 3 lines for the label element before the `inner` div creation. The debounced rendering code added by Task 4 (after `insertAdjacentHTML`) is untouched.

Add CSS:
```css
.agent-label { font-size: 11px; font-weight: 600; color: var(--fg-muted); margin-bottom: 3px; letter-spacing: 0.3px; }
```

In `appendToken` function, find the `if (!activeStreamEl)` block. After `activeStreamEl.className = 'message';`, insert these 3 lines before `const inner = ...`:

```javascript
    var label = document.createElement('div');
    label.className = 'agent-label';
    label.textContent = 'Agent';
    activeStreamEl.appendChild(label);
```

The rest of `appendToken` (inner div creation, _streamRawText, insertAdjacentHTML, debounced render) stays as-is from Task 4.

- [ ] **Step 2: Remove user message left margin (no avatar = no indent)**

Line 101: Change `margin-left: 48px;` to `margin-left: 0;`

- [ ] **Step 3: Add max-height to diff cards and diff2html**

Add CSS:
```css
.diff-card { max-height: 300px; overflow-y: auto; }
.diff2html-wrapper { max-height: 400px; overflow-y: auto; }
```

- [ ] **Step 4: Make thinking blocks collapsible**

In the `appendThinking` JS function, wrap in `<details>`:

```javascript
function appendThinking(text) {
  endStream(); hideEmpty();
  var el = document.createElement('div');
  el.className = 'message';
  el.innerHTML = '<details class="thinking-details"><summary class="thinking-summary">Thinking...</summary><div class="thinking">' + esc(text) + '</div></details>';
  container.appendChild(el);
  scrollToBottom();
}
```

Add CSS:
```css
.thinking-details { margin: 8px 0; }
.thinking-summary { font-size: 12px; color: var(--fg-muted); cursor: pointer; font-style: italic; padding: 4px 0; }
.thinking-summary:hover { color: var(--fg-secondary); }
```

- [ ] **Step 5: Increase tool args display limit**

In `appendToolCall`, change `args.substring(0, 300)` to `args.substring(0, 800)`. Already done in Task 4 step 2 — verify it's present.

Add CSS for scrollable detail:
```css
.tool-detail { max-height: 200px; overflow-y: auto; }
```

- [ ] **Step 6: Make code copy button always slightly visible**

Change line ~377: `opacity: 0;` to `opacity: 0.3;`
Change line ~380: `.code-wrapper:hover .code-copy { opacity: 1; }` stays the same.

- [ ] **Step 7: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 8: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html
git commit -m "fix(agent-ui): agent label, diff limits, collapsible thinking, copy visibility

Add 'Agent' label before streaming messages for visual identity. Remove
orphaned user message indent. Add max-height+scroll to diffs and diff2html.
Make thinking blocks collapsible via details/summary. Increase tool args
display limit to 800 chars. Make copy button always slightly visible."
```

---

## Task 8: Tool Card Smart Expand & Close Button Consistency (M6, M9)

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html`

- [ ] **Step 1: Auto-expand most recent tool card**

In `appendToolCall`, after appending the card, collapse all previous tool cards:

```javascript
// After container.appendChild(card):
// Collapse all previous tool details, expand this one
var allCards = container.querySelectorAll('.tool-card');
for (var i = 0; i < allCards.length - 1; i++) {
    var details = allCards[i].querySelectorAll('.tool-detail, .tool-result');
    details.forEach(function(d) { d.classList.add('collapsed'); });
    var chevron = allCards[i].querySelector('.chevron');
    if (chevron) chevron.classList.remove('open');
}
// Expand the new card's details if present
var newDetails = card.querySelectorAll('.tool-detail');
newDetails.forEach(function(d) { d.classList.remove('collapsed'); });
var newChevron = card.querySelector('.chevron');
if (newChevron) newChevron.classList.add('open');
```

- [ ] **Step 2: Standardize all close buttons to use `×` (U+00D7)**

In `renderToolsList` JS, find `close-btn" onclick="closeToolsPanel()">x</button>` and change to `>&#xD7;</button>`.

In `showToolDetail` JS, find `td-close" onclick="closeToolDetail()">x</button>` and change to `>&#xD7;</button>`.

- [ ] **Step 3: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html
git commit -m "fix(agent-ui): smart tool card expand, consistent close buttons

Auto-expand most recent tool card while collapsing older ones. Standardize
all close buttons to use × (U+00D7) instead of plain 'x' character."
```

---

## Task 9: History Panel Fixes (M12, M13)

**Files:**
- Modify: `agent/src/main/kotlin/.../ui/HistoryPanel.kt`

- [ ] **Step 1: Fix wrong icon for Cleanup button**

Line 103: Change `AllIcons.Actions.ProfileCPU` to `AllIcons.Actions.GC`

- [ ] **Step 2: Add Resume button and right-click context menu**

In the cell renderer, add a hover-visible "Resume" button. And add a right-click popup menu to the list:

After line 86 (the double-click listener), add:

```kotlin
// Right-click context menu
sessionList.componentPopupMenu = javax.swing.JPopupMenu().apply {
    add(javax.swing.JMenuItem("Resume Session").apply {
        icon = AllIcons.Actions.Execute
        addActionListener {
            val entry = sessionList.selectedValue ?: return@addActionListener
            onResumeSession?.invoke(entry.sessionId)
        }
    })
    add(javax.swing.JMenuItem("Delete Session").apply {
        icon = AllIcons.Actions.GC
        addActionListener { deleteSelected() }
    })
}
```

In the cell renderer, add a tooltip:

After line 186 (iconLabel), add to the panel:
```kotlin
panel.toolTipText = "Double-click to resume"
```

- [ ] **Step 3: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/HistoryPanel.kt
git commit -m "fix(agent-ui): history panel icon fix, right-click menu, resume tooltip

Replace CPU profiling icon with GC icon for cleanup button. Add right-click
context menu with Resume/Delete actions. Add tooltip 'Double-click to resume'
on session entries for discoverability."
```

---

## Task 10: Low-Priority Polish (L1-L6)

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html`

- [ ] **Step 1: Increase scrollbar width**

Line 57: Change `width: 8px;` to `width: 10px;`

- [ ] **Step 2: Reduce empty state padding**

Line 75: Change `padding: 80px 24px;` to `padding: 48px 24px;`

- [ ] **Step 3: Fix tool detail param description contrast**

Line 900: Change `.td-param .pdesc { color: var(--fg-muted);` to `color: var(--fg-secondary);`

- [ ] **Step 4: Increase plan step file list font size**

Line 521: Change the plan-step-files font size from `11px` to `12px` (within the `.plan-step-files` rule).

- [ ] **Step 5: Make table filter width responsive**

Lines 283-284: Change `width: 200px;` to `min-width: 200px; max-width: 50%;`

- [ ] **Step 6: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html
git commit -m "fix(agent-ui): polish — scrollbar, padding, contrast, font sizes

Widen scrollbar to 10px for easier grabbing on Windows. Reduce empty state
padding. Improve parameter description contrast. Increase plan step file
font size. Make table filter width responsive."
```

---

## Task 11: Run All Tests & Update Documentation

**Files:**
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Run all agent tests**

```bash
./gradlew :agent:test --rerun --no-build-cache -x verifyPlugin
```
Expected: All tests pass.

- [ ] **Step 2: Run verifyPlugin**

```bash
./gradlew verifyPlugin
```
Expected: No API compatibility issues.

- [ ] **Step 3: Commit**

No code changes — just verification.

---

## Verification

After all tasks:

```bash
./gradlew :agent:test --rerun --no-daemon    # All tests pass
./gradlew :agent:compileKotlin               # No compilation errors
./gradlew verifyPlugin                        # Plugin API compatibility
```

Manual verification in `runIde`:
1. Switch between dark and light IDE themes — verify all hover states, dividers, ANSI output visible in both
2. Type a prompt — verify no emojis in empty state, plan cards, question wizard
3. Run a task — verify live timer on tool cards, streaming doesn't flash on endStream
4. Trigger an error — verify retry button appears
5. Check command approval — verify risk color distinction (HIGH=red, MEDIUM=orange)
6. Tab through interactive elements — verify blue focus outlines appear
7. Set `prefers-reduced-motion: reduce` in DevTools — verify animations stop
8. Check token budget widget — verify no "150K" when idle
9. Open history panel — verify GC icon, right-click menu, double-click tooltip
10. Open tools panel — verify `×` close buttons instead of `x`

## Findings Coverage Matrix

| Finding | Task | Status |
|---------|------|--------|
| C1-C4: Emoji icons | Task 2 | Covered |
| C5: ANSI hardcoded bg | Task 1 | Covered |
| C6: Light mode rgba | Task 1 | Covered |
| H1: Streaming flash | Task 4 | Covered |
| H2: No live timer | Task 4 | Covered |
| H3: No risk distinction | Task 5 | Covered |
| H4: No Allow All for commands | Task 5 | Covered |
| H5: Edit approval fallback | Task 5 | Covered |
| H6: Token budget 150K | Task 6 | Covered |
| H7: No queued cancel | Deferred — low ROI, complex state |
| H8: prefers-reduced-motion | Task 3 | Covered |
| H9: No focus indicators | Task 3 | Covered |
| H10: Session resume empty | Deferred — needs message replay arch |
| M1: User msg no avatar | Task 7 (margin removal) | Covered |
| M2: No agent label | Task 7 | Covered |
| M3: Code copy opacity | Task 7 | Covered |
| M4: Diff max-height | Task 7 | Covered |
| M5: Thinking collapsible | Task 7 | Covered |
| M6: All tools collapsed | Task 8 | Covered |
| M7: Swing/JCEF disconnect | Deferred — needs Swing LAF work |
| M8: Toolbar crowding | Deferred — needs user testing data |
| M9: Close button inconsistency | Task 8 | Covered |
| M10: No error retry | Task 6 | Covered |
| M11: Tool args truncation | Task 7 | Covered |
| M12: History wrong icon | Task 9 | Covered |
| M13: History no resume button | Task 9 | Covered |
| M14: Skill banner color | Task 1 | Covered |
| L1: Scrollbar width | Task 10 | Covered |
| L2: Empty state padding | Task 10 | Covered |
| L3: Param description contrast | Task 10 | Covered |
| L4: Plan step font | Task 10 | Covered |
| L5: Table filter width | Task 10 | Covered |
| L6: Scroll lag | Task 4 | Covered |
| L7: Command approval colors | Task 5 | Covered |
| L8: Fallback panel | Deferred — JCEF required |
