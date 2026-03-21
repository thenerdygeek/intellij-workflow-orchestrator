# Agent Chat UI Overhaul — Design Spec

## Goal

Replace the mixed Swing+JCEF agent chat with a unified full-JCEF panel: Bolt-style glassmorphic input bar, JCEF-rendered toolbar, and all 40+ UI/UX audit fixes baked into the new design. The entire agent tab becomes a single HTML page rendered by `JBCefBrowser`, enabling future reuse in editor tabs and popup windows.

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Architecture | Full JCEF takeover | Zero Swing/JCEF disconnect. Same HTML works in tool window, editor tab, popup. |
| Input style | Bolt glassmorphic | Gradient glow border, auto-expanding textarea, premium feel |
| Toolbar layout | Split: toolbar + input bar | Toolbar = session actions (Stop, New, overflow, budget). Input bar = message actions (Model, Plan, Skills, Send). |
| Token budget | Inline text + colored dot | "24.5K / 190K" with green/yellow/red dot. Compact, IDE-native. |
| Light mode | Full theme adaptation | Input bar adapts to light theme (gray border, blue accent). No dark-only elements. |
| Thinking blocks | Collapsible | `<details>` collapsed by default, click to expand. |
| Emojis | Unicode symbols only | No emoji anywhere — universal rendering across platforms. |
| Future | @ mentions, editor tab, popup | JCEF input enables cursor-positioned autocomplete dropdown. Same `JBCefBrowser` hosts in any Swing container. |

## Architecture

### Current (Mixed Swing+JCEF)

```
AgentDashboardPanel (Swing JPanel)
├── toolbar (Swing: JButton × 7 + JBLabel + JProgressBar)
├── AgentCefPanel (JCEF: agent-chat.html)
│   └── Chat messages, tool cards, plan cards, question wizard
└── inputBar (Swing: JBTextArea + JButton)
```

### New (Full JCEF)

```
AgentDashboardPanel (Swing JPanel — thin wrapper)
└── AgentCefPanel (JCEF: agent-chat.html — controls everything)
    ├── JCEF toolbar (Stop, New, ⋯ overflow, token budget)
    ├── Chat area (messages, tool cards, plan cards, question wizard)
    └── JCEF input bar (Bolt-style: textarea, model selector, Plan, Skills, Send)
```

`AgentDashboardPanel` becomes a minimal Swing `JPanel` that just hosts `AgentCefPanel`. All UI logic moves into the HTML/CSS/JS + Kotlin bridge layer.

### JS↔Kotlin Bridge Inventory

**Existing bridges (keep):**
- `_requestUndo()`, `_requestViewTrace()`, `_submitPrompt(text)`
- `_approvePlan()`, `_revisePlan(comments)`
- `_toggleTool(data)`, `_questionAnswered(qid, opts)`, etc.
- `_navigateToFile(path)`, `_deactivateSkill()`

**New bridges needed:**
- `_cancelTask()` — Stop button in JCEF toolbar → `AgentController.cancelTask()`
- `_newChat()` — New button → `AgentController.newChat()`
- `_openOverflowMenu()` — ⋯ button → Shows tools/traces/settings popup
- `_changeModel(modelId)` — Model selector → Updates `AgentSettings.sourcegraphChatModel`
- `_togglePlanMode(enabled)` — Plan chip toggle → Sets plan mode flag
- `_activateSkill(name)` — Skills chip → Opens skill popup or activates skill
- `_sendMessage(text)` — Enter key in JCEF textarea → `AgentController.executeTask(text)`
- `_requestFocusIde()` — Escape key → Returns focus to IDE editor

**Kotlin→JS calls (existing + new):**
- Existing: `appendToken`, `endStream`, `appendToolCall`, `updateToolResult`, `appendDiff`, `renderPlan`, `showQuestions`, etc.
- New: `setBusy(busy)` — enables/disables input + shows/hides Stop button
- New: `updateTokenBudget(used, max)` — updates the toolbar budget display
- New: `setModelName(name)` — updates the model chip text
- New: `updateSkillsList(json)` — populates the skills dropdown

## Component Design

### 1. JCEF Toolbar

Position: Fixed at top of chat panel. Height: ~36px.

```
┌─────────────────────────────────────────────────────┐
│ [⏹ Stop] [+ New]  │  [⋯]              ● 24.5K/190K │
└─────────────────────────────────────────────────────┘
```

- **Stop** — Red-tinted button. Visible only when agent is busy (`setBusy(true)`). Calls `_cancelTask()`.
- **New** — Creates new chat session. Calls `_newChat()`.
- **Divider** — 1px vertical line separating primary actions from overflow.
- **⋯ Overflow** — Dropdown menu containing: Tools, Traces, Settings. Each calls its respective bridge.
- **Token budget** — Right-aligned. Format: `● 24.5K / 190K`. Dot color: green (<60%), yellow (60-80%), red (>80%). At >80%, also show text label "Low" next to the budget for colorblind accessibility (color alone must not be the only indicator). Hidden when no session active.

**Light mode:** Background `#f8fafc`, border `#e2e8f0`, text `#475569`.

### 2. Bolt-Style Input Bar

Position: Fixed at bottom of chat panel. Auto-expands from 60px to 200px max.

```
┌──────────────────────────────────────────────────┐
│ Ask the agent to do something...                 │
│                                                  │
│ [◆ Sonnet 4.5 ▾] [☰ Plan] [⚡ Skills] [Send ➤] │
└──────────────────────────────────────────────────┘
```

**Visual style (dark mode):**
- Background: `#1a1c22`
- Border: `1px solid rgba(255,255,255,0.08)` with gradient glow pseudo-element
- Gradient glow: `linear-gradient(135deg, rgba(59,130,246,0.22), transparent 40%, transparent 60%, rgba(139,92,246,0.18))`
- Border radius: 14px
- Box shadow: `0 0 20px rgba(59,130,246,0.04), 0 4px 12px rgba(0,0,0,0.15), inset 0 1px 0 rgba(255,255,255,0.03)`

**Visual style (light mode):**
- Background: `#ffffff`
- Border: `1px solid #e2e8f0` (no gradient glow — use blue focus highlight instead)
- Focus state: `border-color: #3b82f6; box-shadow: 0 0 0 3px rgba(59,130,246,0.1)`
- Border radius: 14px

**Textarea:**
- Auto-expanding: starts at 1 row, grows to max ~8 rows (200px max-height)
- Enter sends, Shift+Enter inserts newline
- Escape calls `_requestFocusIde()` to return focus to IDE editor
- Placeholder: "Ask the agent to do something..."
- Disabled state: reduced opacity, non-editable (when agent is busy, user can still type — messages queue)

**Bottom bar chips:**
- **Model selector** — Shows current model with colored dot. Click opens dropdown with available models. Selection calls `_changeModel(modelId)`.
- **Plan chip** — Toggle. Active state: blue background + border. Calls `_togglePlanMode(enabled)`.
- **Skills chip** — Click opens dropdown of available skills. Selection calls `_activateSkill(name)`.
- **Send button** — Blue with glow shadow. Calls `_sendMessage(text)`. Disabled states:
  - Empty textarea: `opacity: 0.4; cursor: default; box-shadow: none; pointer-events: none`
  - After click (pre-busy): Immediately disabled in JS before bridge call returns (prevents double-submit)
  - Agent busy (`setBusy(true)`): same disabled visual

**Hint line:** "Enter to send · Shift+Enter for new line" — 10px muted text below the wrapper. Color: `var(--fg-muted)` in both themes.

### 3. Chat Area

Position: Scrollable area between toolbar and input bar. The overall page uses a flex column layout:

```css
body { display: flex; flex-direction: column; height: 100vh; }
.toolbar { flex-shrink: 0; position: sticky; top: 0; z-index: 10; }
.chat-area { flex: 1; overflow-y: auto; }
.input-area { flex-shrink: 0; position: sticky; bottom: 0; z-index: 20; }
```

This ensures toolbar and input stay anchored while chat scrolls between them. No content is hidden behind fixed elements.

All existing components are retained with audit fixes applied:

**Messages:**
- User messages: `YOU` label, background bubble, no left margin (audit M1 fix)
- Agent messages: `AGENT` label before first streaming block (audit M2 fix)
- Streaming: accumulate raw text, debounced markdown render every 300ms (audit H1 fix)
- `scrollToBottom` uses `behavior: 'auto'` not `'smooth'` (audit L6 fix)

**Tool cards:**
- Live elapsed timer while RUNNING (audit H2 fix)
- Most recent card auto-expanded, older ones collapsed (audit M6 fix)
- Tool args display limit: 800 chars (audit M11 fix)
- ANSI output background: `var(--code-bg)` not hardcoded `#0d1117` (audit C5 fix)

**Diff cards:**
- Max height 300px with `overflow-y: auto` (audit M4 fix)

**Thinking blocks:**
- Wrapped in `<details>` collapsed by default (audit M5 fix)

**Code blocks:**
- Copy button: `opacity: 0.3` default, `1` on hover (audit M3 fix)
- `tabindex="0"` for keyboard accessibility

**Tables:**
- Sortable columns + filter input (already implemented)

**Session footer:**
- Unicode symbols: ↩ Undo All, ☰ View Trace (audit C4 fix)
- Retry button appears on failure (audit M10 fix)

**Plan card, Question wizard, Tools panel overlay:**
- Unicode symbols throughout (audit C2, C3 fixes)
- Close buttons: `×` (U+00D7) consistently (audit M9 fix)

### 4. Theme-Aware CSS Variables

All `rgba(255,255,255,x)` replaced with CSS variables injected from Kotlin:

| Variable | Dark Mode | Light Mode |
|----------|-----------|------------|
| `--hover-overlay` | `rgba(255,255,255,0.03)` | `rgba(0,0,0,0.03)` |
| `--hover-overlay-strong` | `rgba(255,255,255,0.05)` | `rgba(0,0,0,0.05)` |
| `--divider-subtle` | `rgba(255,255,255,0.05)` | `rgba(0,0,0,0.05)` |
| `--row-alt` | `rgba(255,255,255,0.02)` | `rgba(0,0,0,0.02)` |
| `--input-bg` | `#1a1c22` | `#ffffff` |
| `--input-border` | `rgba(255,255,255,0.08)` | `#e2e8f0` |
| `--input-glow` | `0 0 20px rgba(59,130,246,0.04)` | `none` |
| `--input-focus-glow` | `0 0 20px rgba(59,130,246,0.08)` | `0 0 0 3px rgba(59,130,246,0.1)` |
| `--toolbar-bg` | `#1e2028` | `#f8fafc` |
| `--chip-bg` | `rgba(255,255,255,0.03)` | `rgba(0,0,0,0.03)` |
| `--chip-border` | `rgba(255,255,255,0.07)` | `#e2e8f0` |

### 5. Z-Index Scale & Interaction Rules

**Z-index scale (consistent across all components):**

| Layer | z-index | Elements |
|-------|---------|----------|
| Base content | 0 | Chat messages, tool cards, diffs |
| Sticky toolbar | 10 | Top toolbar, skill banner |
| Sticky input | 20 | Bottom input bar |
| Dropdowns | 100 | Model selector, skills dropdown, overflow menu |
| Overlays | 1000 | Tools panel, tool detail panel |
| Toasts | 2000 | Toast notifications |

**Dropdown positioning:** Model selector, skills dropdown, and overflow menu dropdowns are positioned absolutely relative to their trigger chip/button, opening upward (for input bar) or downward (for toolbar). Each dropdown has `z-index: 100` and a click-outside listener to dismiss.

**Interaction rules (all interactive elements):**
- All clickable elements (buttons, chips, close icons, tool headers, options, links) must have `cursor: pointer`
- Hover states use `color`, `opacity`, `background`, `border-color` transitions only — **no `scale` or `transform` on hover** (causes layout shift)
- Active/pressed state: `opacity: 0.85` (no `scale(0.95)` — layout shift in a dense IDE panel is jarring)
- Transitions: `150ms` for micro-interactions (hover, focus), `200ms` for state changes (expand/collapse), `300ms` for enter/exit animations (toast, fadeIn)

### 6. Accessibility

**`prefers-reduced-motion`:**
```css
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
}
```

**`:focus-visible`:**
All interactive elements (buttons, chips, options, tool rows, close buttons, code copy) get:
```css
outline: 2px solid var(--link);
outline-offset: 2px;
```

**Keyboard navigation:**
- Tab cycles through toolbar buttons → chat interactive elements → input bar chips → send button
- Escape in textarea returns focus to IDE editor
- Enter in textarea sends message (Shift+Enter for newline)

### 7. Kotlin Side Changes

**`AgentDashboardPanel.kt`:**
- **Remove fields:** `chatInput`, `sendButton`, `cancelButton`, `newChatButton`, `planModeToggle`, `toolsButton`, `skillsButton`, `tracesButton`, `settingsLink`, `modelLabel`, `tokenWidget`, `savedChatInputText`
- **Remove methods:** `buildInputBar()`, `submitMessage()`, `disableChatInput()`, `enableSwingChatInput()`, `showSkillsPopup()`, toolbar/input bar construction in `init`
- **Keep:** `AgentCefPanel` hosting, `onSendMessage` callback, all `setCef*Callbacks` delegation methods, `fallbackPanel` (see below)
- **Rewrite `setBusy(busy)`:** Replace Swing field manipulation with `cefPanel?.setBusy(busy)`. Remove all `chatInput.isEnabled`/`sendButton.isEnabled`/`cancelButton.isEnabled` references.
- **Rewrite `updateProgress(step, tokensUsed, maxTokens)`:** Replace `tokenWidget.update(tokensUsed, maxTokens)` with `cefPanel?.updateTokenBudget(tokensUsed, maxTokens)`.
- **Add `setInputLocked(locked)`:** New method for question wizard state. Calls `cefPanel?.setInputLocked(locked)`. Replaces `disableChatInput()`/`enableSwingChatInput()`.
- **Add:** New bridge wiring methods for the 8 new JS→Kotlin bridges
- **Fallback decision:** `RichStreamingPanel` fallback is **preserved** for environments without JCEF. The fallback only renders chat messages (no toolbar, no Bolt input — just the JEditorPane chat + a basic Swing input). The `?: fallbackPanel?.xxx()` delegation pattern stays. If JCEF is unavailable, users get a degraded but functional experience.

**`AgentCefPanel.kt`:**
- Add 8 new `JBCefJSQuery` fields for the new bridges
- Add Kotlin→JS methods: `setBusy(busy)`, `setInputLocked(locked)`, `updateTokenBudget(used, max)`, `setModelName(name)`, `updateSkillsList(json)`
- Remove backward-compat Swing methods that no longer apply

**`AgentController.kt`:**
- **Remove Swing wires from `init`:** Delete these lines that reference deleted Swing fields:
  - `dashboard.onSendMessage = { message -> executeTask(message) }` → replaced by `_sendMessage` bridge
  - `dashboard.cancelButton.addActionListener { cancelTask() }` → replaced by `_cancelTask` bridge
  - `dashboard.newChatButton.addActionListener { newChat() }` → replaced by `_newChat` bridge
  - `dashboard.tracesButton.addActionListener { openLatestTrace() }` → moved to overflow menu
  - `dashboard.settingsLink.addMouseListener(...)` → moved to overflow menu
  - `dashboard.toolsButton.addActionListener { showToolsPanel() }` → moved to overflow menu
- **Wire new bridges instead:** In `init`, call `dashboard.setCefActionCallbacks(onCancel = { cancelTask() }, onNewChat = { newChat() }, onSendMessage = { executeTask(it) }, onOverflow = { item -> handleOverflowItem(item) }, ...)`. The bridge setup in `AgentCefPanel` routes JS calls to these Kotlin callbacks.
- **Add `handleOverflowItem(item: String)`:** Dispatches to existing methods based on item name:
  - `"tools"` → `showToolsPanel()`
  - `"traces"` → `openLatestTrace()`
  - `"settings"` → `ShowSettingsUtil.getInstance().showSettingsDialog(project, "workflow.orchestrator.agent")`
- **Replace `dashboard.disableChatInput()`** with `dashboard.setInputLocked(true)` in `wireSessionCallbacks()`
- **Replace `dashboard.enableSwingChatInput()`** with `dashboard.setInputLocked(false)` in question wizard callbacks
- **Add retry wiring:** In `handleResult()` for `Failed`, show retry button with last user message
- Move model change logic into controller

**`CommandApprovalDialog.kt`:**
- Add risk-level color distinction (RED/ORANGE/GRAY with icons)
- Add "Allow All Commands" checkbox (`var allowAll = false; private set`)
- Theme-aware terminal colors

**`EditApprovalDialog.kt`:**
- Improve fallback message (human-readable instead of raw JSON)

**`TokenBudgetWidget.kt`:**
- Delete entirely — replaced by JCEF budget display via `updateTokenBudget(used, max)`

**`HistoryPanel.kt`:**
- Fix cleanup icon (`AllIcons.Actions.GC`)
- Add right-click context menu with Resume/Delete
- Add tooltip on entries

### 8. Overflow Menu Implementation

The `_openOverflowMenu()` JS→Kotlin bridge is handled **entirely in JS** — the HTML renders a dropdown menu positioned below the ⋯ button. Each menu item calls a specific bridge:

```
⋯ click → show dropdown:
  "≡ Tools"    → _openToolsPanel()   (existing bridge, already wired)
  "☰ Traces"   → _requestViewTrace() (existing bridge)
  "⚙ Settings" → _openSettings()     (new bridge → ShowSettingsUtil)
```

This means `_openOverflowMenu()` is NOT a single bridge — it's a JS-side dropdown that calls existing bridges per item. Only `_openSettings()` needs a new bridge (1 new `JBCefJSQuery`, not a composite method).

## Audit Findings Coverage

All 34 non-deferred findings from the audit are addressed:

| Category | Findings | How Addressed |
|----------|----------|---------------|
| Critical (C1-C6) | Emojis, ANSI bg, light mode rgba | Unicode symbols, var(--code-bg), theme-aware CSS variables |
| High (H1-H6, H8-H9) | Streaming flash, tool timer, approvals, budget, motion, focus | Debounced render, live timer, dialog improvements, JCEF budget, reduced-motion, focus-visible |
| Medium (M1-M6, M9-M14) | Agent label, copy button, diffs, thinking, tool expand, close buttons, retry, history | All baked into the new design |
| Low (L1-L6) | Scrollbar, padding, contrast, fonts, filter, scroll | CSS adjustments |

**4 findings deferred:**
- H7: Queued message cancel — complex state management
- H10: Session resume replay — needs message replay architecture
- L8: Fallback panel degraded — JCEF required for full experience (fallback preserved but minimal)

**2 findings resolved by new architecture (no longer deferred):**
- M7: Swing/JCEF disconnect — **resolved by full JCEF takeover**
- M8: Toolbar crowding — **resolved by overflow menu**

## File Changes Summary

| File | Action |
|------|--------|
| `agent/src/main/resources/webview/agent-chat.html` | Major rewrite: add toolbar HTML/CSS/JS, add Bolt input HTML/CSS/JS, apply all audit CSS fixes, add new bridges |
| `agent/src/main/kotlin/.../ui/AgentCefPanel.kt` | Add 8 new JBCefJSQuery bridges, add new Kotlin→JS methods |
| `agent/src/main/kotlin/.../ui/AgentDashboardPanel.kt` | Strip to thin JCEF wrapper, remove all Swing UI components |
| `agent/src/main/kotlin/.../ui/AgentController.kt` | Wire new bridges, improve approval dialogs, add retry |
| `agent/src/main/kotlin/.../ui/CommandApprovalDialog.kt` | Risk colors, allow-all checkbox, theme-aware colors |
| `agent/src/main/kotlin/.../ui/EditApprovalDialog.kt` | Better fallback message |
| `agent/src/main/kotlin/.../ui/TokenBudgetWidget.kt` | Delete — replaced by JCEF |
| `agent/src/main/kotlin/.../ui/HistoryPanel.kt` | Icon fix, context menu, tooltip |
