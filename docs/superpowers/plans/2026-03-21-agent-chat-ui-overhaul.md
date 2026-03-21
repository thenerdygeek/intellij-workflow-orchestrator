# Agent Chat UI Overhaul — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the mixed Swing+JCEF agent chat with a unified full-JCEF panel featuring a Bolt-style glassmorphic input bar, JCEF toolbar, and all 34 UI/UX audit fixes baked in.

**Architecture:** The entire agent tab becomes a single `JBCefBrowser` hosting `agent-chat.html`. Swing toolbar and input bar are removed from `AgentDashboardPanel` (which becomes a thin wrapper). 8 new `JBCefJSQuery` bridges replace the Swing button listeners. The HTML gains a CSS toolbar at top and Bolt-style input at bottom with a flex column layout.

**Tech Stack:** HTML/CSS/JS (JCEF Chromium), Kotlin (IntelliJ Platform), JBCefJSQuery bridges, CSS variables for theming

**Spec:** `docs/superpowers/specs/2026-03-21-agent-chat-overhaul-design.md`

---

## File Structure

### Modified Files

| File | Changes |
|------|---------|
| `agent/src/main/resources/webview/agent-chat.html` (~2745 lines) | Add toolbar + input bar HTML/CSS/JS. Apply all 34 audit CSS fixes. Replace emojis. Add accessibility. Restructure to flex column layout. |
| `agent/src/main/kotlin/.../ui/AgentCefPanel.kt` (~575 lines) | Add 9 new JBCefJSQuery bridges + 5 new Kotlin→JS methods. |
| `agent/src/main/kotlin/.../ui/AgentDashboardPanel.kt` (~467 lines) | Strip to thin JCEF wrapper. Remove ~200 lines of Swing UI. |
| `agent/src/main/kotlin/.../ui/AgentController.kt` (~770 lines) | Rewire init from Swing to bridges. Add retry, overflow handling. |
| `agent/src/main/kotlin/.../ui/CommandApprovalDialog.kt` (72 lines) | Risk colors, allow-all checkbox, theme-aware colors. |
| `agent/src/main/kotlin/.../ui/EditApprovalDialog.kt` (81 lines) | Better fallback message. |
| `agent/src/main/kotlin/.../ui/HistoryPanel.kt` (244 lines) | Icon fix, context menu, tooltip. |

### Deleted Files

| File | Reason |
|------|--------|
| `agent/src/main/kotlin/.../ui/TokenBudgetWidget.kt` (53 lines) | Replaced by JCEF token budget display |

---

## Task 1: CSS Audit Fixes — Theme Variables, Light Mode, Accessibility

Apply all CSS-only audit fixes to agent-chat.html. No JS or Kotlin changes. This is the foundation for all subsequent tasks.

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html`

**Fixes covered:** C5, C6, H5-H7, H8, H9, M3, M4, M5, M14, L1-L5

- [ ] **Step 1: Add theme-aware overlay CSS variables to `:root` (lines 10-43)**

After `--font-mono` (line 42), add:
```css
  --hover-overlay: rgba(255,255,255,0.03);
  --hover-overlay-strong: rgba(255,255,255,0.05);
  --divider-subtle: rgba(255,255,255,0.05);
  --row-alt: rgba(255,255,255,0.02);
  --input-bg: #1a1c22;
  --input-border: rgba(255,255,255,0.08);
  --toolbar-bg: #1e2028;
  --chip-bg: rgba(255,255,255,0.03);
  --chip-border: rgba(255,255,255,0.07);
```

- [ ] **Step 2: Replace all hardcoded `rgba(255,255,255,x)` with CSS variables**

Use find-and-replace across the CSS section:
- `.tool-header:hover` (line ~150): `rgba(255,255,255,0.03)` → `var(--hover-overlay)`
- `.md-table th[data-sortable]:hover` (line ~273): `rgba(255,255,255,0.05)` → `var(--hover-overlay-strong)`
- `.md-table tr:nth-child(even) td` (line ~335): `rgba(255,255,255,0.02)` → `var(--row-alt)`
- `.footer-btn:hover` (line ~503): `rgba(255,255,255,0.05)` → `var(--hover-overlay-strong)`
- `.plan-step` border (line ~515): `rgba(255,255,255,0.05)` → `var(--divider-subtle)`
- `.question-option:hover` (line ~610): `rgba(255,255,255,0.03)` → `var(--hover-overlay)`
- `.question-summary-item` border (line ~716): `rgba(255,255,255,0.05)` → `var(--divider-subtle)`
- `.skill-banner-dismiss:hover` (line ~854): `rgba(255,255,255,0.1)` → `var(--hover-overlay-strong)`
- `.tool-row:hover` (line ~874): `rgba(255,255,255,0.04)` → `var(--hover-overlay)`

- [ ] **Step 3: Fix ANSI output background**

Line ~217: Replace `background: #0d1117;` with `background: var(--code-bg);`

- [ ] **Step 4: Fix skill banner hardcoded colors**

Lines ~834-845: Replace `.skill-banner` styles with:
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

- [ ] **Step 5: Add `prefers-reduced-motion` and `:focus-visible`**

Add at end of CSS (before `</style>`):
```css
/* Accessibility — Reduced Motion */
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

/* Accessibility — Keyboard Focus */
.prompt-btn:focus-visible, .plan-btn:focus-visible, .qnav-btn:focus-visible,
.question-option:focus-visible, .tool-row:focus-visible, .tab-btn:focus-visible,
.footer-btn:focus-visible, .code-copy:focus-visible, .md-table-filter:focus-visible,
.question-cancel:focus-visible, .skill-banner-dismiss:focus-visible,
.tools-panel-header .close-btn:focus-visible, .td-close:focus-visible {
  outline: 2px solid var(--link);
  outline-offset: 2px;
}
```

- [ ] **Step 6: Apply remaining CSS fixes**

- Code copy button (line ~377): Change `opacity: 0;` to `opacity: 0.3;`
- Add `tabindex="0"` — this is in the JS renderer, will be done in the JS task
- Diff card: Add `max-height: 300px; overflow-y: auto;` to `.diff-card` rule (line ~421)
- Scrollbar (line ~57): Change `width: 8px;` to `width: 10px;`
- Empty state (line ~75): Change `padding: 80px 24px;` to `padding: 48px 24px;`
- Tool detail param desc (line ~900): Change `color: var(--fg-muted)` to `color: var(--fg-secondary)`
- Table filter (line ~283): Change `width: 200px;` to `min-width: 200px; max-width: 50%;`
- User msg (line ~101): Change `margin-left: 48px;` to `margin-left: 0;`

- [ ] **Step 7: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 8: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html
git commit -m "fix(agent-ui): CSS audit fixes — theme variables, light mode, accessibility

Replace all rgba(255,255,255,x) with theme-aware CSS variables. Fix ANSI
output and skill banner hardcoded colors. Add prefers-reduced-motion and
focus-visible. Fix code copy visibility, diff limits, scrollbar, spacing."
```

---

## Task 2: JS Audit Fixes — Emojis, Streaming, Tool Timer, Thinking, Agent Label

Apply all JavaScript audit fixes. Builds on Task 1's CSS foundation.

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html`

**Fixes covered:** C1-C4, H1, H2, M2, M5, M6, M9, M11, L6

- [ ] **Step 1: Replace empty state emojis (lines ~998-1006)**

Replace the empty state HTML with unicode symbols:
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

- [ ] **Step 2: Replace emojis in JS functions**

In `renderPlan` (~line 1891): Replace `📋` with `&#x2630;`, `💬` with `&#x2709;`
In `completeSession` (~line 1631): Replace `📋 View Trace` with `&#x2630; View Trace`
In `showQuestion` (~line 2179): Replace `&#x1F4AC;` with `&#x2709;`
In `showQuestionSummaryFromState` (~line 2354): Replace `&#x1F4CB;` with `&#x2630;`
In `showQuestionSummary` (~line 2399): Replace `&#x1F4CB;` with `&#x2630;`

- [ ] **Step 3: Standardize all close buttons to `×` (U+00D7)**

In `renderToolsList` (~line 2000): Find `>x</button>` and change to `>&#xD7;</button>`
In `showToolDetail` (~line 2100): Find `>x</button>` and change to `>&#xD7;</button>`

- [ ] **Step 4: Fix scrollToBottom — target container, use `auto`**

Line ~1023: The flex layout (Task 3) makes `body` `overflow: hidden`, so `window.scrollTo` does nothing. The scrollable element is now `#chat-container`. Replace the function:

```javascript
function scrollToBottom() {
  container.scrollTop = container.scrollHeight;
}
```

- [ ] **Step 5: Add debounced markdown render to streaming**

Add module-level variable near `_streamRawText` (~line 1662):
```javascript
var _renderDebounce = null;
```

In `appendToken` (~line 1665), after the `insertAdjacentHTML` line, add:
```javascript
  clearTimeout(_renderDebounce);
  _renderDebounce = setTimeout(function() {
    if (activeStreamEl && activeStreamEl._inner && _streamRawText) {
      activeStreamEl._inner.innerHTML = renderMarkdown(_streamRawText);
      scrollToBottom();
    }
  }, 300);
```

- [ ] **Step 6: Add agent label before streaming messages**

In `appendToken`, in the `if (!activeStreamEl)` block, after `activeStreamEl.className = 'message';`, add:
```javascript
    var agentLabel = document.createElement('div');
    agentLabel.className = 'agent-label';
    agentLabel.textContent = 'Agent';
    activeStreamEl.appendChild(agentLabel);
```

Add CSS (in the Messages section):
```css
.agent-label { font-size: 10px; font-weight: 600; color: var(--fg-muted); margin-bottom: 2px; letter-spacing: 0.3px; }
```

- [ ] **Step 7: Add live elapsed timer to running tool cards**

Add module-level array near top of script:
```javascript
var _activeTimerIntervals = [];
```

Add CSS:
```css
.tool-timer { font-size: 10px; color: var(--fg-muted); font-family: var(--font-mono); padding: 1px 6px; }
```

Replace the `appendToolCall` function with a version that starts a live timer when RUNNING:

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

  // Auto-collapse all previous tool cards, expand this one
  var allCards = container.querySelectorAll('.tool-card');
  for (var i = 0; i < allCards.length - 1; i++) {
    var details = allCards[i].querySelectorAll('.tool-detail, .tool-result');
    details.forEach(function(d) { d.classList.add('collapsed'); });
    var chev = allCards[i].querySelector('.chevron');
    if (chev) chev.classList.remove('open');
  }

  if (statusStr === 'RUNNING') {
    var startMs = Date.now();
    card._timerInterval = setInterval(function() {
      var el = document.getElementById(timerId);
      if (el) {
        var elapsed = (Date.now() - startMs) / 1000;
        el.textContent = elapsed < 10 ? elapsed.toFixed(1) + 's' : Math.floor(elapsed) + 's';
      } else { clearInterval(card._timerInterval); }
    }, 100);
    _activeTimerIntervals.push(card._timerInterval);
  }
  scrollToBottom();
}
```

In `updateToolResult`, add at the beginning (after getting lastCard):
```javascript
  var parentMsg = lastCard.closest('.message');
  if (parentMsg && parentMsg._timerInterval) {
    clearInterval(parentMsg._timerInterval);
    _activeTimerIntervals = _activeTimerIntervals.filter(function(id) { return id !== parentMsg._timerInterval; });
    delete parentMsg._timerInterval;
  }
  var timerEl = lastCard.querySelector('.tool-timer');
  if (timerEl) timerEl.remove();
```

In `clearChat`, add at the top:
```javascript
  _activeTimerIntervals.forEach(function(id) { clearInterval(id); });
  _activeTimerIntervals = [];
```

- [ ] **Step 8: Make thinking blocks collapsible**

Replace the `appendThinking` function (~line 1813):
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

- [ ] **Step 9: Add code copy tabindex**

In the marked.js `renderer.code` function, change the copy button:
```javascript
'<button class="code-copy" tabindex="0" onclick="copyCode(this)">Copy</button>'
```

- [ ] **Step 10: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 11: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html
git commit -m "fix(agent-ui): JS audit fixes — emojis, streaming, timer, thinking, labels

Replace all emoji icons with unicode. Add debounced markdown render during
streaming. Add live elapsed timer on running tool cards. Make thinking blocks
collapsible. Add agent label before responses. Smart tool card expand/collapse.
Consistent close buttons. Fix scroll behavior."
```

---

## Task 3: JCEF Toolbar + Bolt Input Bar (HTML/CSS/JS)

The main feature: add JCEF toolbar at top and Bolt-style input at bottom.

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html`

- [ ] **Step 1: Add toolbar and input bar CSS**

Add to the CSS section (after the accessibility block from Task 1):

```css
/* ═══════════════════════════════════════════════════
   JCEF Toolbar
   ═══════════════════════════════════════════════════ */
.agent-toolbar {
  display: flex;
  align-items: center;
  padding: 6px 14px;
  gap: 6px;
  border-bottom: 1px solid var(--border);
  background: var(--toolbar-bg);
  flex-shrink: 0;
  position: sticky;
  top: 0;
  z-index: 10;
}
.tb-btn {
  background: var(--chip-bg);
  border: 1px solid var(--chip-border);
  border-radius: 6px;
  color: var(--fg-secondary);
  padding: 4px 10px;
  font-size: 11px;
  font-family: inherit;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 4px;
  transition: border-color 0.15s, color 0.15s;
}
.tb-btn:hover { border-color: var(--fg-muted); color: var(--fg); }
.tb-btn.stop { color: var(--error); border-color: rgba(239,68,68,0.2); }
.tb-btn.stop:hover { background: rgba(239,68,68,0.1); }
.tb-btn.hidden { display: none; }
.tb-divider { width: 1px; height: 16px; background: var(--border); margin: 0 2px; }
.tb-overflow {
  background: none;
  border: 1px solid transparent;
  border-radius: 6px;
  color: var(--fg-muted);
  padding: 4px 6px;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.15s;
  position: relative;
}
.tb-overflow:hover { color: var(--fg-secondary); background: var(--hover-overlay); border-color: var(--chip-border); }
.tb-spacer { flex: 1; }
.tb-budget {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  font-family: var(--font-mono);
  color: var(--fg-muted);
}
.tb-budget.hidden { display: none; }
.budget-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--success); flex-shrink: 0; }
.budget-dot.warn { background: var(--warning); }
.budget-dot.danger { background: var(--error); }
.budget-label { font-size: 10px; font-weight: 600; color: var(--error); margin-left: 2px; }

/* Overflow dropdown */
.overflow-menu {
  display: none;
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 4px;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(0,0,0,0.3);
  z-index: 100;
  min-width: 160px;
  overflow: hidden;
}
.overflow-menu.visible { display: block; }
.overflow-item {
  padding: 8px 14px;
  font-size: 12px;
  color: var(--fg-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  transition: background 0.1s;
}
.overflow-item:hover { background: var(--hover-overlay-strong); color: var(--fg); }

/* ═══════════════════════════════════════════════════
   Bolt-Style Input Bar
   ═══════════════════════════════════════════════════ */
.input-area {
  padding: 10px 16px 14px;
  flex-shrink: 0;
  position: sticky;
  bottom: 0;
  z-index: 20;
  background: var(--bg);
}
.input-wrapper {
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: 14px;
  padding: 12px 14px 8px;
  position: relative;
  box-shadow: 0 0 20px rgba(59,130,246,0.04), 0 4px 12px rgba(0,0,0,0.15), inset 0 1px 0 rgba(255,255,255,0.03);
}
.input-wrapper::before {
  content: '';
  position: absolute;
  inset: -1px;
  border-radius: 15px;
  padding: 1px;
  background: linear-gradient(135deg, rgba(59,130,246,0.22), transparent 40%, transparent 60%, rgba(139,92,246,0.18));
  -webkit-mask: linear-gradient(#fff 0 0) content-box, linear-gradient(#fff 0 0);
  mask: linear-gradient(#fff 0 0) content-box, linear-gradient(#fff 0 0);
  -webkit-mask-composite: xor;
  mask-composite: exclude;
  pointer-events: none;
}
.input-wrapper:focus-within { border-color: var(--link); }
.input-ta {
  width: 100%;
  background: transparent;
  border: none;
  color: var(--fg);
  font-size: 13px;
  font-family: var(--font-body);
  outline: none;
  resize: none;
  line-height: 1.5;
  min-height: 24px;
  max-height: 200px;
}
.input-ta::placeholder { color: var(--fg-muted); }
.input-ta:disabled { opacity: 0.5; }
.input-bottom {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 8px;
}
.input-chip {
  background: var(--chip-bg);
  border: 1px solid var(--chip-border);
  border-radius: 7px;
  color: var(--fg-secondary);
  padding: 4px 10px;
  font-size: 11px;
  font-family: inherit;
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  transition: all 0.15s;
}
.input-chip:hover { border-color: var(--fg-muted); color: var(--fg); }
.input-chip.active { background: rgba(59,130,246,0.1); border-color: rgba(59,130,246,0.3); color: var(--link); }
.input-chip .model-dot { width: 6px; height: 6px; border-radius: 50%; background: #8b5cf6; }
.send-btn {
  margin-left: auto;
  background: var(--link);
  border: none;
  border-radius: 8px;
  color: white;
  padding: 5px 14px;
  font-size: 12px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  box-shadow: 0 0 16px rgba(59,130,246,0.25);
  transition: all 0.15s;
}
.send-btn:hover { background: #2563eb; box-shadow: 0 0 24px rgba(59,130,246,0.35); }
.send-btn:disabled { opacity: 0.4; cursor: default; box-shadow: none; pointer-events: none; }
.input-hint { text-align: center; font-size: 10px; color: var(--fg-muted); padding: 5px 0 0; }

/* Model/Skills dropdown (shared with overflow) */
.chip-dropdown {
  display: none;
  position: absolute;
  bottom: 100%;
  left: 0;
  margin-bottom: 6px;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(0,0,0,0.3);
  z-index: 100;
  min-width: 200px;
  max-height: 240px;
  overflow-y: auto;
}
.chip-dropdown.visible { display: block; }
.chip-dropdown-item {
  padding: 8px 14px;
  font-size: 12px;
  color: var(--fg-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  transition: background 0.1s;
}
.chip-dropdown-item:hover { background: var(--hover-overlay-strong); color: var(--fg); }
.chip-dropdown-item.selected { color: var(--link); }

/* Retry button */
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

- [ ] **Step 2: Restructure body to flex column layout**

Change the `<body>` and `<div id="chat-container">` structure. Replace the current body content (from `<body>` to the empty state) with:

```html
<body>
<!-- JCEF Toolbar -->
<div class="agent-toolbar" id="agent-toolbar">
  <button class="tb-btn stop hidden" id="tb-stop" onclick="if(window._cancelTask)window._cancelTask()">&#x23F9; Stop</button>
  <button class="tb-btn" id="tb-new" onclick="if(window._newChat)window._newChat()">+ New</button>
  <div class="tb-divider"></div>
  <div class="tb-overflow" id="tb-overflow" onclick="toggleOverflow()">
    &#x22EF;
    <div class="overflow-menu" id="overflow-menu">
      <div class="overflow-item" onclick="event.stopPropagation();closeOverflow();if(window._openToolsPanel)window._openToolsPanel()">&#x2261; Tools</div>
      <div class="overflow-item" onclick="event.stopPropagation();closeOverflow();if(window._requestViewTrace)window._requestViewTrace()">&#x2630; Traces</div>
      <div class="overflow-item" onclick="event.stopPropagation();closeOverflow();if(window._openSettings)window._openSettings()">&#x2699; Settings</div>
    </div>
  </div>
  <div class="tb-spacer"></div>
  <div class="tb-budget hidden" id="tb-budget">
    <span class="budget-dot" id="budget-dot"></span>
    <span id="budget-text">0 / 190K</span>
  </div>
</div>

<!-- Chat Area -->
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

<!-- Bolt-Style Input Bar -->
<div class="input-area" id="input-area">
  <div class="input-wrapper">
    <textarea class="input-ta" id="chat-input" placeholder="Ask the agent to do something..." rows="1"></textarea>
    <div class="input-bottom">
      <div class="input-chip" id="model-chip" onclick="toggleModelDropdown()" style="position:relative;">
        <span class="model-dot"></span>
        <span id="model-chip-text">Model</span> ▾
        <div class="chip-dropdown" id="model-dropdown"></div>
      </div>
      <div class="input-chip" id="plan-chip" onclick="togglePlan()">&#x2630; Plan</div>
      <div class="input-chip" id="skills-chip" onclick="toggleSkillsDropdown()" style="position:relative;">
        &#x26A1; Skills
        <div class="chip-dropdown" id="skills-dropdown"></div>
      </div>
      <button class="send-btn" id="send-btn" onclick="sendMessage()" disabled>Send &#x27A4;</button>
    </div>
  </div>
  <div class="input-hint">Enter to send · Shift+Enter for new line</div>
</div>
</body>
```

- [ ] **Step 3: Update base body CSS for flex column layout**

Replace the `body` CSS rule (line ~49):
```css
body {
  font-family: var(--font-body);
  font-size: 13px;
  color: var(--fg);
  background: var(--bg);
  line-height: 1.6;
  overflow-x: hidden;
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
}
```

Update `#chat-container`:
```css
#chat-container {
  padding: 16px;
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
}
```

- [ ] **Step 4: Add toolbar and input bar JS functions**

Add after the existing JS functions:

```javascript
/* ═══════════════════════════════════════════════════
   JCEF Toolbar Functions
   ═══════════════════════════════════════════════════ */
function setBusy(busy) {
  var stopBtn = document.getElementById('tb-stop');
  if (stopBtn) stopBtn.classList.toggle('hidden', !busy);
}

function updateTokenBudget(used, max) {
  var budgetEl = document.getElementById('tb-budget');
  var dotEl = document.getElementById('budget-dot');
  var textEl = document.getElementById('budget-text');
  if (!budgetEl || !dotEl || !textEl) return;
  if (max <= 0) { budgetEl.classList.add('hidden'); return; }
  budgetEl.classList.remove('hidden');
  var percent = Math.floor(used * 100 / max);
  textEl.textContent = formatBudget(used) + ' / ' + formatBudget(max);
  dotEl.className = 'budget-dot' + (percent >= 80 ? ' danger' : percent >= 60 ? ' warn' : '');
  // Colorblind: show "Low" label at >80%
  var label = budgetEl.querySelector('.budget-label');
  if (percent >= 80) {
    if (!label) { label = document.createElement('span'); label.className = 'budget-label'; budgetEl.appendChild(label); }
    label.textContent = 'Low';
  } else if (label) { label.remove(); }
}

function formatBudget(n) {
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
  if (n >= 1000) return (n / 1000).toFixed(1) + 'K';
  return n.toString();
}

function toggleOverflow() {
  var menu = document.getElementById('overflow-menu');
  if (menu) menu.classList.toggle('visible');
}
function closeOverflow() {
  var menu = document.getElementById('overflow-menu');
  if (menu) menu.classList.remove('visible');
}
// Close overflow on click outside
document.addEventListener('click', function(e) {
  if (!e.target.closest('.tb-overflow')) closeOverflow();
});

/* ═══════════════════════════════════════════════════
   Bolt Input Bar Functions
   ═══════════════════════════════════════════════════ */
var _planMode = false;

function sendMessage() {
  var ta = document.getElementById('chat-input');
  var text = ta ? ta.value.trim() : '';
  if (!text) return;
  // Disable send immediately to prevent double-submit
  var sendBtn = document.getElementById('send-btn');
  if (sendBtn) sendBtn.disabled = true;
  ta.value = '';
  ta.style.height = 'auto';
  if (window._sendMessage) window._sendMessage(text);
}

// Auto-expand textarea
var chatInput = document.getElementById('chat-input');
if (chatInput) {
  chatInput.addEventListener('input', function() {
    this.style.height = 'auto';
    this.style.height = Math.min(this.scrollHeight, 200) + 'px';
    var sendBtn = document.getElementById('send-btn');
    if (sendBtn) sendBtn.disabled = !this.value.trim();
  });
  chatInput.addEventListener('keydown', function(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
    if (e.key === 'Escape') {
      e.preventDefault();
      if (window._requestFocusIde) window._requestFocusIde();
    }
  });
}

function setInputLocked(locked) {
  var ta = document.getElementById('chat-input');
  var sendBtn = document.getElementById('send-btn');
  if (ta) { ta.disabled = locked; if (locked) ta.value = ''; }
  if (sendBtn) sendBtn.disabled = locked;
}

function setModelName(name) {
  var chip = document.getElementById('model-chip-text');
  if (chip) chip.textContent = name || 'Model';
}

function togglePlan() {
  _planMode = !_planMode;
  var chip = document.getElementById('plan-chip');
  if (chip) chip.classList.toggle('active', _planMode);
  if (window._togglePlanMode) window._togglePlanMode(_planMode);
}

// Model dropdown
function toggleModelDropdown() {
  var dd = document.getElementById('model-dropdown');
  if (dd) dd.classList.toggle('visible');
  // Close skills dropdown
  var sd = document.getElementById('skills-dropdown');
  if (sd) sd.classList.remove('visible');
}
function updateModelList(models) {
  var dd = document.getElementById('model-dropdown');
  if (!dd) return;
  var data = typeof models === 'string' ? JSON.parse(models) : models;
  dd.innerHTML = data.map(function(m) {
    return '<div class="chip-dropdown-item" onclick="selectModel(\'' + esc(m.id) + '\')">' + esc(m.name) + '</div>';
  }).join('');
}
function selectModel(modelId) {
  var dd = document.getElementById('model-dropdown');
  if (dd) dd.classList.remove('visible');
  if (window._changeModel) window._changeModel(modelId);
}

// Skills dropdown
function toggleSkillsDropdown() {
  var dd = document.getElementById('skills-dropdown');
  if (dd) dd.classList.toggle('visible');
  var md = document.getElementById('model-dropdown');
  if (md) md.classList.remove('visible');
}
function updateSkillsList(skills) {
  var dd = document.getElementById('skills-dropdown');
  if (!dd) return;
  var data = typeof skills === 'string' ? JSON.parse(skills) : skills;
  if (data.length === 0) {
    document.getElementById('skills-chip').style.display = 'none';
    return;
  }
  document.getElementById('skills-chip').style.display = '';
  dd.innerHTML = data.map(function(s) {
    return '<div class="chip-dropdown-item" onclick="activateSkill(\'' + esc(s.name) + '\')">' + esc('/' + s.name) + ' — ' + esc(s.description) + '</div>';
  }).join('');
}
function activateSkill(name) {
  var dd = document.getElementById('skills-dropdown');
  if (dd) dd.classList.remove('visible');
  if (window._activateSkill) window._activateSkill(name);
}

// Close dropdowns on click outside
document.addEventListener('click', function(e) {
  if (!e.target.closest('#model-chip')) {
    var md = document.getElementById('model-dropdown');
    if (md) md.classList.remove('visible');
  }
  if (!e.target.closest('#skills-chip')) {
    var sd = document.getElementById('skills-dropdown');
    if (sd) sd.classList.remove('visible');
  }
});

// Retry button
function showRetryButton(lastMessage) {
  var btn = document.createElement('div');
  btn.className = 'message';
  btn.innerHTML = '<button class="retry-btn" data-retry-message="' + esc(lastMessage) + '">&#x21BB; Retry last message</button>';
  container.appendChild(btn);
  scrollToBottom();
}
document.addEventListener('click', function(e) {
  var retryBtn = e.target.closest('.retry-btn');
  if (retryBtn && window._sendMessage) {
    var msg = retryBtn.dataset.retryMessage;
    retryBtn.closest('.message').remove();
    window._sendMessage(msg);
  }
});

// Focus input
function focusInput() {
  var ta = document.getElementById('chat-input');
  if (ta) ta.focus();
}
```

- [ ] **Step 5: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html
git commit -m "feat(agent-ui): add JCEF toolbar and Bolt-style glassmorphic input bar

JCEF toolbar with Stop, New, overflow menu (Tools/Traces/Settings), token
budget with colored dot + 'Low' label. Bolt-style input with gradient glow,
auto-expanding textarea, model/plan/skills chips, glowing Send button.
Flex column layout. Enter to send, Escape to IDE, dropdowns with z-index."
```

---

## Task 4: New JBCefJSQuery Bridges in AgentCefPanel.kt

Add the 9 new bridges and 5 new Kotlin→JS methods.

**Files:**
- Modify: `agent/src/main/kotlin/.../ui/AgentCefPanel.kt`

- [ ] **Step 1: Add new query fields and callbacks**

After the existing query fields (~line 54), add:
```kotlin
private var cancelTaskQuery: JBCefJSQuery? = null
private var newChatQuery: JBCefJSQuery? = null
private var sendMessageQuery: JBCefJSQuery? = null
private var changeModelQuery: JBCefJSQuery? = null
private var togglePlanModeQuery: JBCefJSQuery? = null
private var activateSkillQuery: JBCefJSQuery? = null
private var requestFocusIdeQuery: JBCefJSQuery? = null
private var openSettingsQuery: JBCefJSQuery? = null
private var openToolsPanelQuery: JBCefJSQuery? = null

var onCancelTask: (() -> Unit)? = null
var onNewChat: (() -> Unit)? = null
var onSendMessage: ((String) -> Unit)? = null
var onChangeModel: ((String) -> Unit)? = null
var onTogglePlanMode: ((Boolean) -> Unit)? = null
var onActivateSkill: ((String) -> Unit)? = null
var onRequestFocusIde: (() -> Unit)? = null
var onOpenSettings: (() -> Unit)? = null
var onOpenToolsPanel: (() -> Unit)? = null
```

- [ ] **Step 2: Create queries in `createBrowser()`**

After the existing query creations (after `deactivateSkillQuery`), add:
```kotlin
cancelTaskQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
    addHandler { _ -> onCancelTask?.invoke(); JBCefJSQuery.Response("ok") }
}
newChatQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
    addHandler { _ -> onNewChat?.invoke(); JBCefJSQuery.Response("ok") }
}
sendMessageQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
    addHandler { text -> onSendMessage?.invoke(text); JBCefJSQuery.Response("ok") }
}
changeModelQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
    addHandler { modelId -> onChangeModel?.invoke(modelId); JBCefJSQuery.Response("ok") }
}
togglePlanModeQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
    addHandler { enabled -> onTogglePlanMode?.invoke(enabled == "true"); JBCefJSQuery.Response("ok") }
}
activateSkillQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
    addHandler { name -> onActivateSkill?.invoke(name); JBCefJSQuery.Response("ok") }
}
requestFocusIdeQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
    addHandler { _ -> onRequestFocusIde?.invoke(); JBCefJSQuery.Response("ok") }
}
openSettingsQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
    addHandler { _ -> onOpenSettings?.invoke(); JBCefJSQuery.Response("ok") }
}
openToolsPanelQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
    addHandler { _ -> onOpenToolsPanel?.invoke(); JBCefJSQuery.Response("ok") }
}
```

- [ ] **Step 3: Inject bridge functions in `onLoadingStateChange`**

After the existing bridge injections, add:
```kotlin
cancelTaskQuery?.let { q ->
    val cancelJs = q.inject("'cancel'")
    js("window._cancelTask = function() { $cancelJs }")
}
newChatQuery?.let { q ->
    val newJs = q.inject("'new'")
    js("window._newChat = function() { $newJs }")
}
sendMessageQuery?.let { q ->
    val sendJs = q.inject("text")
    js("window._sendMessage = function(text) { $sendJs }")
}
changeModelQuery?.let { q ->
    val modelJs = q.inject("modelId")
    js("window._changeModel = function(modelId) { $modelJs }")
}
togglePlanModeQuery?.let { q ->
    val planJs = q.inject("String(enabled)")
    js("window._togglePlanMode = function(enabled) { $planJs }")
}
activateSkillQuery?.let { q ->
    val skillJs = q.inject("name")
    js("window._activateSkill = function(name) { $skillJs }")
}
requestFocusIdeQuery?.let { q ->
    val focusJs = q.inject("'focus'")
    js("window._requestFocusIde = function() { $focusJs }")
}
openSettingsQuery?.let { q ->
    val settingsJs = q.inject("'settings'")
    js("window._openSettings = function() { $settingsJs }")
}
openToolsPanelQuery?.let { q ->
    val toolsJs = q.inject("'tools'")
    js("window._openToolsPanel = function() { $toolsJs }")
}
```

- [ ] **Step 4: Add new Kotlin→JS methods**

In the public API section, add:
```kotlin
fun setBusy(busy: Boolean) {
    callJs("setBusy(${if (busy) "true" else "false"})")
}

fun setInputLocked(locked: Boolean) {
    callJs("setInputLocked(${if (locked) "true" else "false"})")
}

fun updateTokenBudget(used: Int, max: Int) {
    callJs("updateTokenBudget($used,$max)")
}

fun setModelName(name: String) {
    callJs("setModelName(${jsonStr(name)})")
}

fun updateSkillsList(skillsJson: String) {
    callJs("updateSkillsList(${jsonStr(skillsJson)})")
}

fun showRetryButton(lastMessage: String) {
    callJs("showRetryButton(${jsonStr(lastMessage)})")
}

fun focusInput() {
    callJs("focusInput()")
}
```

- [ ] **Step 5: Update `dispose()` to clean up new queries**

Add to dispose:
```kotlin
cancelTaskQuery?.dispose(); cancelTaskQuery = null
newChatQuery?.dispose(); newChatQuery = null
sendMessageQuery?.dispose(); sendMessageQuery = null
changeModelQuery?.dispose(); changeModelQuery = null
togglePlanModeQuery?.dispose(); togglePlanModeQuery = null
activateSkillQuery?.dispose(); activateSkillQuery = null
requestFocusIdeQuery?.dispose(); requestFocusIdeQuery = null
openSettingsQuery?.dispose(); openSettingsQuery = null
openToolsPanelQuery?.dispose(); openToolsPanelQuery = null
```

- [ ] **Step 6: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt
git commit -m "feat(agent-ui): add 9 new JBCefJSQuery bridges for JCEF toolbar+input

Bridges: _cancelTask, _newChat, _sendMessage, _changeModel, _togglePlanMode,
_activateSkill, _requestFocusIde, _openSettings, _openToolsPanel.
Kotlin→JS: setBusy, setInputLocked, updateTokenBudget, setModelName,
updateSkillsList, showRetryButton, focusInput."
```

---

## Task 5: Strip Dashboard + Rewire Controller (Atomic)

**IMPORTANT:** Tasks 5 and 6 from the original plan are merged into one atomic task. Stripping Swing fields from `AgentDashboardPanel` and rewiring `AgentController` must happen in the same commit — doing them separately breaks compilation (controller references deleted Swing fields).

**Files:**
- Modify: `agent/src/main/kotlin/.../ui/AgentDashboardPanel.kt`
- Modify: `agent/src/main/kotlin/.../ui/AgentController.kt`
- Delete: `agent/src/main/kotlin/.../ui/TokenBudgetWidget.kt`

- [ ] **Step 1: Remove Swing fields and toolbar/input construction**

Remove these fields (lines 56-112): `tokenWidget`, `modelLabel`, `chatInput`, `sendButton`, `cancelButton`, `newChatButton`, `planModeToggle`, `toolsButton`, `tracesButton`, `skillsButton`, `settingsLink`, `savedChatInputText`.

Remove the `isPlanMode` property.

Rewrite `init` to just host the JCEF panel:
```kotlin
init {
    border = JBUI.Borders.empty()
    val outputComponent = cefPanel ?: fallbackPanel!!
    add(outputComponent, BorderLayout.CENTER)
}
```

- [ ] **Step 2: Remove Swing-specific methods**

Remove: `buildInputBar()`, `submitMessage()`, `disableChatInput()`, `enableSwingChatInput()`, `showSkillsPopup()`, `updateSkillsList(skills, scope)`.

- [ ] **Step 3: Rewrite delegation methods to use JCEF only**

```kotlin
fun setBusy(busy: Boolean) = runOnEdt {
    cefPanel?.setBusy(busy)
}

fun updateProgress(step: String, tokensUsed: Int, maxTokens: Int) = runOnEdt {
    cefPanel?.updateTokenBudget(tokensUsed, maxTokens)
}

fun setModelName(name: String) = runOnEdt {
    val shortName = name.substringAfterLast("::").ifBlank { name }
    cefPanel?.setModelName(shortName)
}

fun setInputLocked(locked: Boolean) = runOnEdt {
    cefPanel?.setInputLocked(locked)
}

fun showRetryButton(lastMessage: String) {
    cefPanel?.showRetryButton(lastMessage)
}

fun focusInput() = runOnEdt {
    cefPanel?.focusInput()
}

fun updateSkillsList(skillsJson: String) {
    cefPanel?.updateSkillsList(skillsJson)
}
```

Keep existing delegation methods: `startSession`, `appendUserMessage`, `completeSession`, `appendStreamToken`, `flushStreamBuffer`, `appendToolCall`, `updateLastToolCall`, `appendEditDiff`, `appendStatus`, `appendError`, `renderPlan`, `updatePlanStep`, `showQuestions`, `showQuestion`, `showQuestionSummary`, `enableChatInput`, `showSkillBanner`, `hideSkillBanner`, `showToolsPanel`, `setCef*` callback methods.

- [ ] **Step 4: Add new bridge wiring methods**

```kotlin
fun setCefActionCallbacks(
    onCancel: () -> Unit,
    onNewChat: () -> Unit,
    onSendMessage: (String) -> Unit,
    onChangeModel: (String) -> Unit,
    onTogglePlanMode: (Boolean) -> Unit,
    onActivateSkill: (String) -> Unit,
    onRequestFocusIde: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenToolsPanel: () -> Unit
) {
    cefPanel?.onCancelTask = onCancel
    cefPanel?.onNewChat = onNewChat
    cefPanel?.onSendMessage = onSendMessage
    cefPanel?.onChangeModel = onChangeModel
    cefPanel?.onTogglePlanMode = onTogglePlanMode
    cefPanel?.onActivateSkill = onActivateSkill
    cefPanel?.onRequestFocusIde = onRequestFocusIde
    cefPanel?.onOpenSettings = onOpenSettings
    cefPanel?.onOpenToolsPanel = onOpenToolsPanel
}
```

- [ ] **Step 5: Delete TokenBudgetWidget.kt**

```bash
rm agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/TokenBudgetWidget.kt
```

- [ ] **Step 6: Rewire AgentController — replace Swing wires in `init` (lines 51-98)**

**Do this IN THE SAME TASK before compiling.** The controller references Swing fields being removed above.

Remove these lines:
```kotlin
dashboard.onSendMessage = { message -> executeTask(message) }
dashboard.cancelButton.addActionListener { cancelTask() }
dashboard.newChatButton.addActionListener { newChat() }
dashboard.tracesButton.addActionListener { openLatestTrace() }
dashboard.settingsLink.addMouseListener(...)
dashboard.toolsButton.addActionListener { showToolsPanel() }
```

Replace with:
```kotlin
// Wire JCEF bridges for toolbar + input bar actions
dashboard.setCefActionCallbacks(
    onCancel = { cancelTask() },
    onNewChat = { newChat() },
    onSendMessage = { text -> executeTask(text) },
    onChangeModel = { modelId ->
        try {
            val settings = AgentSettings.getInstance(project)
            settings.state.sourcegraphChatModel = modelId
            dashboard.setModelName(modelId)
        } catch (_: Exception) {}
    },
    onTogglePlanMode = { enabled ->
        // Store plan mode state for next session creation
        planModeEnabled = enabled
    },
    onActivateSkill = { name -> executeTask("/$name") },
    onRequestFocusIde = {
        ApplicationManager.getApplication().invokeLater {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                .selectedTextEditor?.contentComponent?.requestFocusInWindow()
        }
    },
    onOpenSettings = {
        ApplicationManager.getApplication().invokeLater {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, "workflow.orchestrator.agent")
        }
    },
    onOpenToolsPanel = { showToolsPanel() }
)
```

Add field: `private var planModeEnabled = false`

- [ ] **Step 2: Replace `disableChatInput`/`enableSwingChatInput` in `wireSessionCallbacks`**

Find `dashboard.disableChatInput()` (~line 390) and replace with `dashboard.setInputLocked(true)`

Find `dashboard.enableChatInput()` and `dashboard.enableSwingChatInput()` (~lines 402-403) and replace both with `dashboard.setInputLocked(false)`

- [ ] **Step 3: Fix `handleProgress` to use JCEF budget**

The `dashboard.updateProgress()` call already delegates to `cefPanel?.updateTokenBudget()` after Task 5. No change needed here — just verify it compiles.

- [ ] **Step 4: Add retry button on failure**

In `handleResult()`, in the `is AgentResult.Failed` branch (~line 558), after `dashboard.appendError(result.error)`, add:
```kotlin
// Show retry button with last user message
session?.let { s ->
    val messages = try { s.contextManager.getMessages() } catch (_: Exception) { emptyList() }
    val lastUserMsg = messages.lastOrNull { it.role == "user" }?.content
    if (!lastUserMsg.isNullOrBlank()) {
        dashboard.showRetryButton(lastUserMsg)
    }
}
```

- [ ] **Step 5: Update `setBusy` calls**

The existing `dashboard.setBusy(true/false)` calls in `executeTask` and the `finally` block should now work correctly since `AgentDashboardPanel.setBusy()` delegates to `cefPanel?.setBusy()`. Verify no compilation issues.

- [ ] **Step 6: Fix session creation to use `planModeEnabled` instead of `dashboard.isPlanMode`**

In `executeTask()`, find `planMode = dashboard.isPlanMode` and replace with `planMode = planModeEnabled`.

- [ ] **Step 7: Update skills wiring**

In `wireSessionCallbacks`, find the skills toolbar section that calls `dashboard.updateSkillsList(userSkills, scopes)`. Replace with:
```kotlin
val skillsJson = kotlinx.serialization.json.buildJsonArray {
    currentSession.skillManager?.registry?.getUserInvocableSkills()?.forEach { skill ->
        add(kotlinx.serialization.json.buildJsonObject {
            put("name", kotlinx.serialization.json.JsonPrimitive(skill.name))
            put("description", kotlinx.serialization.json.JsonPrimitive(skill.description))
        })
    }
}.toString()
cefPanel?.updateSkillsList(skillsJson)
```

Use the delegation method added in Task 5 Step 3: `dashboard.updateSkillsList(skillsJson)`

- [ ] **Step 13: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 14: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git rm agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/TokenBudgetWidget.kt
git commit -m "refactor(agent-ui): strip dashboard + rewire controller for full JCEF

Remove all Swing toolbar/input from AgentDashboardPanel (~200 lines).
Rewire AgentController from Swing listeners to JCEF bridges. Delete
TokenBudgetWidget. Add setCefActionCallbacks, retry button, focus-IDE.
Replace disableChatInput with setInputLocked."
```

---

## Task 6: Kotlin Fixes — Approval Dialogs & History Panel

**Files:**
- Modify: `agent/src/main/kotlin/.../ui/CommandApprovalDialog.kt`
- Modify: `agent/src/main/kotlin/.../ui/EditApprovalDialog.kt`
- Modify: `agent/src/main/kotlin/.../ui/HistoryPanel.kt`
- Modify: `agent/src/main/kotlin/.../ui/AgentController.kt`

- [ ] **Step 1: Fix CommandApprovalDialog — risk colors + allow-all**

Replace `createCenterPanel()` body with risk-level colored label and theme-aware command area. Add `var allowAll = false; private set` field and "Allow All Commands" checkbox. See the audit fix plan Task 5 for complete code.

Add import: `import com.intellij.icons.AllIcons`

- [ ] **Step 2: Wire allow-all in AgentController**

In `showApprovalDialog()`, after `dialog.approved` check for command approval, add:
```kotlin
if (dialog.allowAll) sessionAutoApprove = true
```

- [ ] **Step 3: Fix EditApprovalDialog fallback**

In the fallback `Messages.showYesNoCancelDialog` calls, replace the raw JSON message with:
```kotlin
"The agent wants to edit a file.\n\nFile: ${filePath ?: "unknown"}\nAction: Replace text content\n\nYou can undo this change after it's applied."
```

- [ ] **Step 4: Fix HistoryPanel — icon + context menu + tooltip**

Replace `AllIcons.Actions.ProfileCPU` with `AllIcons.Actions.GC` on the cleanup button.

Add right-click context menu after the double-click listener:
```kotlin
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

Add tooltip to cell renderer: `panel.toolTipText = "Double-click to resume"`

- [ ] **Step 5: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/CommandApprovalDialog.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/EditApprovalDialog.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/HistoryPanel.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "fix(agent-ui): approval dialog risk colors, history context menu

CommandApprovalDialog: color-coded risk (RED/ORANGE/GRAY) with icons,
allow-all checkbox, theme-aware terminal. EditApprovalDialog: human-readable
fallback. HistoryPanel: fix GC icon, right-click Resume/Delete menu, tooltip."
```

---

## Task 7: Theme Integration & Final Testing

Update `AgentCefPanel.applyCurrentTheme()` with new variables and run all tests.

**Files:**
- Modify: `agent/src/main/kotlin/.../ui/AgentCefPanel.kt`
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Add new CSS variables to theme injection**

In `applyCurrentTheme()`, add to the dark map:
```kotlin
"hover-overlay" to "rgba(255,255,255,0.03)",
"hover-overlay-strong" to "rgba(255,255,255,0.05)",
"divider-subtle" to "rgba(255,255,255,0.05)",
"row-alt" to "rgba(255,255,255,0.02)",
"input-bg" to "#1a1c22",
"input-border" to "rgba(255,255,255,0.08)",
"toolbar-bg" to "#1e2028",
"chip-bg" to "rgba(255,255,255,0.03)",
"chip-border" to "rgba(255,255,255,0.07)"
```

Add to the light map:
```kotlin
"hover-overlay" to "rgba(0,0,0,0.03)",
"hover-overlay-strong" to "rgba(0,0,0,0.05)",
"divider-subtle" to "rgba(0,0,0,0.05)",
"row-alt" to "rgba(0,0,0,0.02)",
"input-bg" to "#ffffff",
"input-border" to "#e2e8f0",
"toolbar-bg" to "#f8fafc",
"chip-bg" to "rgba(0,0,0,0.03)",
"chip-border" to "#e2e8f0"
```

- [ ] **Step 2: Run all tests**

```bash
./gradlew :agent:test --rerun --no-build-cache -x verifyPlugin
```

- [ ] **Step 3: Run verifyPlugin**

```bash
./gradlew verifyPlugin
```

- [ ] **Step 4: Update agent/CLAUDE.md**

In the "Rich Chat UI" section, add:
```markdown
**Full JCEF Architecture:**
- Entire agent tab is a single `JBCefBrowser` — toolbar, chat, and input all rendered in HTML/CSS/JS
- `AgentDashboardPanel` is a thin Swing wrapper hosting `AgentCefPanel`
- 24 `JBCefJSQuery` bridges for JS→Kotlin communication
- Same HTML page reusable in editor tabs and popup windows
- Bolt-style glassmorphic input bar with gradient glow, auto-expand, model/plan/skills chips
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt agent/CLAUDE.md
git commit -m "feat(agent-ui): theme integration for new components, update docs

Inject light/dark CSS variables for toolbar, input bar, chips, overlays.
Update CLAUDE.md with full JCEF architecture documentation."
```

---

## Verification

After all tasks:

```bash
./gradlew :agent:test --rerun --no-daemon
./gradlew :agent:compileKotlin
./gradlew verifyPlugin
```

Manual verification in `runIde`:
1. Open agent tab — verify toolbar at top, chat in middle, Bolt input at bottom
2. Type and send a message — verify Enter sends, Shift+Enter newlines, Escape returns to editor
3. Verify Stop button appears when agent is busy, hides when idle
4. Click ⋯ overflow — verify Tools, Traces, Settings dropdown
5. Click model chip — verify dropdown with models
6. Click Plan chip — verify toggle active state (blue)
7. Verify token budget updates during task (green dot, text)
8. Trigger >80% budget — verify "Low" label appears
9. Trigger error — verify retry button appears
10. Switch dark/light theme — verify input bar, toolbar, all hover states adapt
11. Tab through elements — verify focus outlines
12. Set `prefers-reduced-motion: reduce` in DevTools — verify animations stop
13. Check no emojis anywhere — all unicode symbols
14. Verify tool cards show live timer, auto-expand most recent
15. Verify thinking blocks are collapsible
16. Verify diff cards have max-height scroll
17. Open history panel — verify GC icon, right-click menu
