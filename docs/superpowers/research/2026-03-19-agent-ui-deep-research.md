# S-Tier AI Coding Agent UI Research

**Date:** 2026-03-19
**Purpose:** Concrete UI patterns, measurements, and implementation details from the best AI coding agent interfaces, synthesized for JEditorPane/Swing implementation in IntelliJ.

---

## Part 1: Per-Tool Raw Research

### 1. Cursor (Composer / Agent Panel)

**Layout:**
- Three layout modes: Pane (sidebar left, editor right), Editor (full-width), Floating (draggable overlay)
- Cursor 2.0 introduced an agent-centric sidebar where agents are first-class objects, visible and manageable as processes
- Full-screen Composer opens with 3 panels: progress navigator (left), file content (center), chat (right)
- Agent sidebar shows multiple parallel agents (up to 8), each with inputs, logs, and outputs
- Aggregated multi-file diff view in one place

**Chat Input:**
- Mode picker in input box: Ask / Edit / Agent toggle
- Context Pills at top of chat show active file context
- `#` to reference files (appear as pills), `@` for participants
- Supports image attachment (screenshots)

**During Execution:**
- Progress panel lets you navigate files being modified
- Streaming text output with diff generation
- Each agent shows its plan steps and status
- Diff view: accept/reject at file level

**Visual Design:**
- Chat panel inherits editor foreground/background colors
- Users request independent color configuration for chat vs editor
- 4px base spacing unit (Tailwind-compatible)
- `p-4` (16px) for card padding, `p-6` (24px) for section padding
- `font-semibold` for headings, `text-2xl` for page titles, `text-lg` for sections, `text-sm` for labels

**Unique Patterns:**
- Plans as inspectable objects with inputs/logs/outputs
- Parallel agent isolation via git worktrees
- Embedded browser with DOM tools for visual testing

---

### 2. Claude Code (CLI + VS Code Extension)

**CLI Layout:**
- Terminal-based with rich markdown rendering
- Tool calls displayed with color-coded badges:
  - **Read**: Blue badge
  - **Write**: Green badge
  - **Edit**: Amber badge
  - **Bash**: Red badge
- Edit diffs show line-by-line diffs with syntax highlighting
- Token usage shown per-turn (input, output, cache creation, cache read)
- Status line adapts to terminal width (two-line layout on narrow terminals)

**VS Code Extension:**
- Dedicated sidebar panel with inline diffs
- Changes open in VS Code's native diff viewer (side-by-side)
- Conversation history scrollable
- Checkpoint system saves code state before each change
- Real-time file change visibility

**During Execution:**
- Streaming text with thinking blocks
- Tool calls expandable with full input/output
- Progress indicators during tool execution

**Unique Patterns:**
- `claude-esp`: separate terminal for hidden output (thinking, tool calls, subagents)
- Color-coded tool badges are the defining visual pattern
- Per-session burn rate and cost analysis
- Checkpoint-based undo (restore to any previous state)

---

### 3. Cline (VS Code Extension)

**Layout:**
- Chat panel in VS Code sidebar
- Every tool invocation transparently displayed in chat
- Diff view: inline blocks within chat (not side-by-side like Roo Code)
- Collapsible task history list
- Collapsible MCP response panels

**Approval Pattern:**
- Every action requires explicit approval in Act mode
- Approve button: checkmark icon
- Reject button: X icon
- File edits shown as diff view before approval
- User can edit diffs directly before accepting
- Auto-approve menu: expanding inline menu (not popup), gradient background fading top-to-bottom

**Plan/Act Mode:**
- Plan Mode: analyzes repo, maps dependencies, drafts ordered plan with file diffs, commands, actions
- Act Mode: executes after approval, each step reviewable
- Mode toggle prominent in UI

**Thinking Display:**
- Thinking/reasoning blocks displayed in real-time
- Collapsible after completion
- Shows elapsed time for thinking

**Unique Patterns:**
- Checkpoint after every single tool call
- Auto-approve has granular permission levels (read auto, write manual)
- Inline gradient menu design for auto-approve

---

### 4. Windsurf (Cascade)

**Layout:**
- VS Code-based sidebar panel
- Separate Chat mode (read-only, no changes) and Cascade mode (agentic, makes changes)
- Home screen panel for starting new conversations

**Context System:**
- On every interaction loads: Rules, Memories, open files, codebase retrieval, recent actions
- Memories auto-generated during conversation (persistent across sessions)
- Rules manually defined by user
- Customizations icon in top-right slider menu

**Tool Suite:**
- Search, Analyze, Web Search, MCP integration, built-in terminal
- "Thinking" models show reasoning steps in real-time (collapsible)

**Unique Patterns:**
- Automatic memory generation from conversations
- Rules vs Memories distinction in persistent context
- Chat mode for safe exploration (no file changes)

---

### 5. GitHub Copilot Chat

**Layout:**
- Chat panel with keyboard shortcut (Ctrl+Alt+I / Cmd+Ctrl+I)
- Chat participants via `@` mentions (@vscode, @terminal)
- Context via `#` references (#file, #codebase, #terminalSelection)
- Tools shown inline (#fetch, directory listing, file editing, terminal commands)

**During Execution:**
- Spinner/animation during thinking (stops when complete)
- Streaming response size counter updates during tool calls
- Edited files listed in "Total changes" list
- Individual file review from changes list
- Multi-agent: can spin up parallel subagents (Jan 2026)

**JetBrains Specific (March 2026):**
- Custom agents, sub-agents, plan agent (GA)
- Agent hooks in preview
- Auto-approve support for MCP

**Unique Patterns:**
- `@` participant model (different agents for different domains)
- `#` context model (explicit file/folder references)
- Streaming token counter visible during generation

---

### 6. Devin (Browser-Based Agent)

**Layout:**
- Split view: Chat (left half), Workspace (right half)
- Workspace has 4 tabbed views: Shell, Browser, Editor, Planner
- "Follow Devin" tab highlights actions in real-time with magnifying glass icons to jump to associated tool

**Progress Tracking:**
- Clickable steps within session
- Progress tab shows details per step
- Shell commands, code edits, browser activity in unified view
- Interactive timelapse with "Live" indicator and progress bar at bottom
- Session replay capability

**Chat Features:**
- Chat history on left side
- Inline voice recording button
- Session Insights button (on-demand analysis)
- @Devin tagging from Slack/Teams

**Unique Patterns:**
- Session timelapse/replay is unique to Devin
- "Follow Devin" real-time action highlighting
- Browser tab for visual testing within the agent UI
- Sandboxed environment visible to user (shell, editor, browser all observable)

---

### 7. Augment Code

**Layout:**
- Agent Tabs in chat header: Thread, Tasks, Edits
- Each tab uses full vertical space for maximum readability
- Dedicated view per workflow stage

**Thread Tab:**
- Conversation flow with agent
- Streaming animation with polished cylinder scroller
- Smooth collapse/expand/scroll behavior for response groups

**Tasks Tab:**
- Shows agent's task breakdown
- Progress per task visible

**Edits Tab:**
- All file edits in one view
- "Keep All" button to apply all changes
- Baseline timestamp tracking

**Context Engine:**
- Indexes entire codebase in real-time
- Commit history, cross-repo dependencies, architectural patterns
- "Next Edit" suggestions based on ripple effects across workspace

**JetBrains Plugin:**
- Uses JCEF for chat UI
- Chat mode persists across IDE restarts
- File creation without confirmation popup (agent mode)

**Unique Patterns:**
- Three-tab header (Thread/Tasks/Edits) is the most organized approach
- Agent Skills: discovers SKILL.md files for repo-specific capabilities
- "Next Edit" predictions based on change ripple effects
- Intent desktop app: multi-agent orchestration centered on living spec

---

## Part 2: Synthesis

### A. Must-Have UI Elements (Every S-Tier Agent Has These)

1. **Streaming text output** -- token-by-token or chunk-by-chunk text appearance with typing animation
2. **Tool call visibility** -- every file read, edit, command execution shown (not hidden)
3. **Collapsible detail blocks** -- thinking/reasoning, tool call details, MCP responses expand/collapse
4. **Diff view for file changes** -- inline or side-by-side, with accept/reject per file or per hunk
5. **Progress indicators** -- spinner/animation during agent work, stops when complete
6. **Context indicators** -- show what files/context the agent is using (pills, badges, references)
7. **Approval gates** -- explicit accept/reject for destructive actions (file writes, commands)
8. **Chat input with mode switching** -- at minimum: chat (read-only) vs agent (makes changes)
9. **Conversation history** -- scrollable, searchable, persistent across sessions
10. **Token/cost awareness** -- some indication of resource usage (token count, cost, burn rate)

### B. Visual Design Patterns

#### Color Coding (Tool Call Badges)
| Tool Type | Color | Hex (Dark Theme) | Hex (Light Theme) |
|-----------|-------|-------------------|---------------------|
| File Read | Blue | `#3B82F6` / `#1E3A5F` bg | `#2563EB` / `#DBEAFE` bg |
| File Write/Create | Green | `#22C55E` / `#14532D` bg | `#16A34A` / `#DCFCE7` bg |
| File Edit | Amber/Orange | `#F59E0B` / `#451A03` bg | `#D97706` / `#FEF3C7` bg |
| Command/Shell | Red/Purple | `#EF4444` / `#450A0A` bg | `#DC2626` / `#FEE2E2` bg |
| Search/Analyze | Cyan | `#06B6D4` / `#083344` bg | `#0891B2` / `#CFFAFE` bg |
| Thinking/Reasoning | Gray/Muted | `#6B7280` / `#1F2937` bg | `#6B7280` / `#F3F4F6` bg |

#### Message Bubbles
- **User messages**: Right-aligned or full-width with distinct background
  - Dark: `#1E293B` background, `#E2E8F0` text
  - Light: `#F1F5F9` background, `#1E293B` text
- **Agent messages**: Full-width, no bubble (just text on panel background)
  - Dark: `#0F172A` background (panel bg), `#CBD5E1` text
  - Light: `#FFFFFF` background, `#334155` text
- **Code blocks**: Slightly darker/lighter than surrounding with monospace font
  - Dark: `#1E1E2E` background with `#E5E7EB` text
  - Light: `#F8FAFC` background with `#1E293B` text

#### Spacing (4px Base Grid)
| Element | Spacing |
|---------|---------|
| Message vertical gap | 12px (`3 * 4px`) |
| Message internal padding | 12px horizontal, 8px vertical |
| Tool call block padding | 8px all sides |
| Code block padding | 12px |
| Section separator | 16px vertical |
| Chat input padding | 12px |
| Icon-to-text gap | 8px |
| Badge internal padding | 4px horizontal, 2px vertical |

#### Typography
| Element | Font | Size | Weight |
|---------|------|------|--------|
| User message | System/Sans | 13px | Normal (400) |
| Agent response | System/Sans | 13px | Normal (400) |
| Code blocks | Monospace (JetBrains Mono / editor font) | 12px | Normal (400) |
| Tool call label | System/Sans | 11px | Semi-bold (600) |
| Section headers | System/Sans | 14px | Semi-bold (600) |
| Badge text | System/Sans | 10px | Medium (500) |
| Token counter | Monospace | 11px | Normal (400) |
| Timestamp | System/Sans | 10px | Normal (400), muted color |

#### Icons (12-16px, SVG)
| Concept | Icon Style |
|---------|-----------|
| File read | Open book / eye icon |
| File write | Pencil / document-plus |
| File edit | Pencil-square / diff icon |
| Command run | Terminal / chevron-right |
| Search | Magnifying glass |
| Thinking | Brain / gear spinning |
| Success | Checkmark in circle (green) |
| Failure | X in circle (red) |
| Warning | Triangle with ! (amber) |
| Expand/Collapse | Chevron down/right |
| Copy | Clipboard icon |
| Accept | Checkmark |
| Reject | X |

### C. Information Density

#### Always Visible (Never Hide)
- Current agent status (idle / thinking / executing tool / waiting for approval)
- Active tool call name and target (e.g., "Reading src/main/App.kt")
- Streaming text output
- Approval buttons when action requires it
- Error messages

#### Visible but Collapsible (Show Summary, Expand for Detail)
- Tool call input/output (show "Read 45 lines from App.kt" -- expand to see content)
- Thinking/reasoning blocks (show first line -- expand for full reasoning)
- Diff content (show "Modified 3 files" -- expand to see diffs)
- MCP response payloads
- Search results

#### Hidden Until Requested
- Token usage / cost (show in status bar or on hover)
- Full conversation history (scrollable, not all loaded)
- Debug logs
- Agent configuration details
- Checkpoint/restore UI

### D. Interaction Patterns

#### Keyboard Shortcuts
| Action | Shortcut (Standard) |
|--------|---------------------|
| Open/focus agent panel | `Ctrl+Shift+A` or `Ctrl+L` |
| Submit message | `Enter` |
| New line in input | `Shift+Enter` |
| Cancel current operation | `Escape` |
| Accept all changes | `Ctrl+Shift+Enter` |
| Reject all changes | `Ctrl+Shift+Backspace` |
| Toggle thinking visibility | `Ctrl+T` |
| Copy code block | Click copy icon (top-right of block) |
| Reference file in input | `#` then type filename |
| Switch mode (Chat/Agent) | Tab or mode picker |

#### Inline Actions (On Hover / In Context)
- **Code blocks**: Copy button (top-right), Apply button (insert into editor), Diff button
- **Tool calls**: Expand/collapse, "Show in editor" link, retry button on failure
- **File references**: Click to open file, hover to preview
- **Diff hunks**: Accept/reject per hunk, edit before accepting
- **Error messages**: "Retry" and "Show details" links

#### Approval Flow
1. Agent proposes action (e.g., "Edit file X")
2. Show diff/command preview in collapsible block (auto-expanded)
3. Two buttons: [Accept checkmark] [Reject X]
4. Optional: [Accept All] for batch operations
5. On reject: focus returns to chat input with context preserved

### E. JEditorPane Implementation Details for IntelliJ

#### Technology Choice: JCEF vs JEditorPane

**Industry standard for JetBrains plugins**: JCEF (embedded Chromium) with React
- Continue, Augment Code, Cody, Windsurf JetBrains -- all use JCEF
- Advantages: full CSS3, flexbox, animations, markdown rendering, syntax highlighting
- Disadvantages: JCEF dependency, memory overhead, communication complexity

**JEditorPane approach** (if JCEF is not an option):
- Limited to HTML 3.2 + CSS1 (no flexbox, no CSS3, no animations)
- Must use `<table>` layouts and inline styles
- No JavaScript, no dynamic updates without full HTML reload
- Theme colors must be injected as inline styles at render time

#### JEditorPane HTML Template Structure

```html
<html>
<head>
<style>
  body {
    font-family: '%FONT_FAMILY%';
    font-size: %FONT_SIZE%px;
    color: %FG_COLOR%;
    background-color: %BG_COLOR%;
    margin: 0;
    padding: 8px;
  }
  .message { margin-bottom: 12px; }
  .user-msg {
    background-color: %USER_BG%;
    border-radius: 8px;
    padding: 8px 12px;
    margin-left: 40px;
  }
  .agent-msg { padding: 8px 0; }
  .tool-call {
    background-color: %TOOL_BG%;
    border-left: 3px solid %TOOL_ACCENT%;
    padding: 6px 10px;
    margin: 8px 0;
    font-size: %SMALL_FONT%px;
  }
  .tool-badge {
    display: inline;
    padding: 1px 6px;
    border-radius: 3px;
    font-size: %BADGE_FONT%px;
    font-weight: 600;
    color: %BADGE_TEXT%;
  }
  .badge-read { background-color: %BLUE_BG%; }
  .badge-write { background-color: %GREEN_BG%; }
  .badge-edit { background-color: %AMBER_BG%; }
  .badge-cmd { background-color: %RED_BG%; }
  .code-block {
    background-color: %CODE_BG%;
    font-family: '%MONO_FONT%';
    font-size: %CODE_FONT%px;
    padding: 10px 12px;
    border-radius: 6px;
    overflow-x: auto;
    white-space: pre;
  }
  .thinking {
    color: %MUTED_FG%;
    font-style: italic;
    border-left: 2px solid %MUTED_FG%;
    padding-left: 8px;
    margin: 8px 0;
  }
  .separator { border-top: 1px solid %BORDER_COLOR%; margin: 16px 0; }
  .status { font-size: %SMALL_FONT%px; color: %MUTED_FG%; }
  .tokens { font-family: '%MONO_FONT%'; font-size: 11px; color: %MUTED_FG%; }
</style>
</head>
<body>
  <!-- Messages rendered here -->
</body>
</html>
```

#### JBColor Mappings for Theme Compatibility

```kotlin
object AgentColors {
    // Panel backgrounds
    val panelBg = JBColor.namedColor("Panel.background", JBColor(0xFFFFFF, 0x2B2D30))
    val userMsgBg = JBColor(0xF1F5F9, 0x1E293B)
    val toolCallBg = JBColor(0xF8FAFC, 0x1A1D23)
    val codeBg = JBColor(0xF1F5F9, 0x1E1E2E)

    // Tool badge backgrounds
    val badgeRead = JBColor(0xDBEAFE, 0x1E3A5F)
    val badgeWrite = JBColor(0xDCFCE7, 0x14532D)
    val badgeEdit = JBColor(0xFEF3C7, 0x451A03)
    val badgeCmd = JBColor(0xFEE2E2, 0x450A0A)
    val badgeSearch = JBColor(0xCFFAFE, 0x083344)

    // Tool badge text
    val badgeReadText = JBColor(0x2563EB, 0x60A5FA)
    val badgeWriteText = JBColor(0x16A34A, 0x4ADE80)
    val badgeEditText = JBColor(0xD97706, 0xFBBF24)
    val badgeCmdText = JBColor(0xDC2626, 0xF87171)

    // Text colors
    val primaryText = JBColor.namedColor("Label.foreground", JBColor(0x1E293B, 0xCBD5E1))
    val mutedText = JBColor(0x64748B, 0x6B7280)
    val borderColor = JBColor.namedColor("Borders.color", JBColor(0xE2E8F0, 0x3F3F46))

    // Status colors
    val success = JBColor(0x16A34A, 0x22C55E)
    val error = JBColor(0xDC2626, 0xEF4444)
    val warning = JBColor(0xD97706, 0xF59E0B)
}
```

#### Incremental HTML Updates (JEditorPane)

Since JEditorPane has no JavaScript, use `HTMLDocument.insertBeforeEnd()` for streaming:

```kotlin
class AgentChatPane : JEditorPane() {
    private val htmlDoc get() = document as HTMLDocument

    init {
        contentType = "text/html"
        isEditable = false
        // Set base HTML with body element
        text = buildBaseHtml()
    }

    fun appendMessage(role: String, content: String) {
        val html = when (role) {
            "user" -> """<div class="message"><div class="user-msg">${escapeHtml(content)}</div></div>"""
            "assistant" -> """<div class="message"><div class="agent-msg">${renderMarkdown(content)}</div></div>"""
            else -> ""
        }
        val body = htmlDoc.getElement(htmlDoc.defaultRootElement, StyleConstants.NameAttribute, HTML.Tag.BODY)
        htmlDoc.insertBeforeEnd(body, html)
        // Auto-scroll to bottom
        caretPosition = document.length
    }

    fun appendToolCall(type: String, target: String, detail: String) {
        val badgeClass = when (type) {
            "read" -> "badge-read"
            "write" -> "badge-write"
            "edit" -> "badge-edit"
            "command" -> "badge-cmd"
            else -> "badge-read"
        }
        val html = """
            <div class="tool-call">
                <span class="tool-badge $badgeClass">${type.uppercase()}</span>
                &nbsp; $target
                <div class="status">$detail</div>
            </div>
        """.trimIndent()
        val body = htmlDoc.getElement(htmlDoc.defaultRootElement, StyleConstants.NameAttribute, HTML.Tag.BODY)
        htmlDoc.insertBeforeEnd(body, html)
        caretPosition = document.length
    }

    fun appendCodeBlock(code: String, language: String) {
        val html = """<div class="code-block">${escapeHtml(code)}</div>"""
        val body = htmlDoc.getElement(htmlDoc.defaultRootElement, StyleConstants.NameAttribute, HTML.Tag.BODY)
        htmlDoc.insertBeforeEnd(body, html)
        caretPosition = document.length
    }
}
```

#### Recommended: Hybrid JEditorPane + Swing Overlay Approach

Since JEditorPane CSS is limited, combine it with Swing components for interactive elements:

```
+--------------------------------------------------+
| [Agent Status Bar]  Thinking... [Stop]   12.3k tokens  |  <-- JBPanel with JBLabel + AnimatedIcon
+--------------------------------------------------+
|                                                    |
|  [JEditorPane - scrollable HTML content]           |  <-- Conversation history (read-only HTML)
|                                                    |
|  User: How do I fix the null check?                |
|                                                    |
|  [THINKING] Analyzing the codebase...              |  <-- Collapsible via HTML anchor + HyperlinkListener
|                                                    |
|  [READ] src/main/App.kt (45 lines)                |  <-- Color-coded tool badge in HTML
|                                                    |
|  [EDIT] src/main/App.kt                            |
|  |- val x = foo?.bar ?: default                    |  <-- Inline diff in monospace
|                                                    |
|  The null check was missing because...             |
|                                                    |
+--------------------------------------------------+
| [Accept All] [Reject All]                          |  <-- JBPanel with JButton (only when pending)
+--------------------------------------------------+
| [#] Add context...  [Chat v] [Agent]               |  <-- Mode toggle
| +----------------------------------------------+  |
| | Type your message...                 [Send >] |  |  <-- JBTextArea + JBButton
| +----------------------------------------------+  |
| Shift+Enter for newline | Esc to cancel            |  <-- Hint label
+--------------------------------------------------+
```

#### Critical JEditorPane Workarounds

1. **No CSS border-radius in JEditorPane**: Use `<table>` with cell backgrounds instead of divs
2. **No CSS flexbox**: Use nested `<table>` for horizontal layouts (badge + text)
3. **Theme color injection**: Rebuild stylesheet on theme change via `LafManagerListener`
4. **Scrolling**: Wrap JEditorPane in JBScrollPane; auto-scroll by setting caret to end
5. **Hyperlink clicks**: Use `HyperlinkListener` to handle expand/collapse and file navigation
6. **Streaming**: Use `HTMLDocument.insertBeforeEnd()` for incremental updates without full re-render
7. **Copy support**: JEditorPane natively supports text selection and Ctrl+C
8. **Font**: Use `EditorColorsManager.getInstance().globalScheme.editorFontName` for code blocks

#### Alternative: JTextPane with StyledDocument

For richer inline styling without HTML limitations:

```kotlin
// StyledDocument approach gives more control than HTML
val doc = DefaultStyledDocument()
val style = doc.addStyle("tool-badge-read", null)
StyleConstants.setBackground(style, AgentColors.badgeRead)
StyleConstants.setForeground(style, AgentColors.badgeReadText)
StyleConstants.setFontSize(style, 10)
StyleConstants.setBold(style, true)
doc.insertString(doc.length, " READ ", style)
```

This avoids HTML/CSS limitations entirely but requires manual layout management.

---

## Part 3: Recommendation for IntelliJ Implementation

### Best Approach: JCEF (Embedded Chromium)

Every major JetBrains AI plugin (Augment, Continue, Copilot, Windsurf, Cody) uses JCEF with a web-based UI (React/HTML). This is the industry standard because:

1. Full CSS3 support (flexbox, animations, transitions, border-radius)
2. Markdown rendering with syntax highlighting (via Prism.js, highlight.js)
3. Dynamic updates without full re-render
4. Rich interactive elements (collapsible sections, buttons, hover effects)
5. Consistent rendering across themes

### Fallback: JEditorPane + Swing Hybrid

If staying pure Swing:
1. Use JEditorPane for conversation history (read-only HTML with inline styles)
2. Use Swing panels for interactive elements (buttons, input, status bar)
3. Inject theme colors at render time via CSS template variables
4. Use `HTMLDocument.insertBeforeEnd()` for streaming
5. Use `HyperlinkListener` for expand/collapse interactions
6. Accept that visual polish will be limited (no rounded corners, no animations, no syntax highlighting in code blocks)

### Key Takeaway

The single most impactful UI pattern across all S-tier agents is **color-coded tool call badges with collapsible detail blocks**. This turns a black-box agent into a transparent, trustworthy assistant. Implement this first, regardless of technology choice.
