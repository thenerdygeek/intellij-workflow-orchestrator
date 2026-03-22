# Agentic Chat UI Overhaul — Design Spec

**Date:** 2026-03-22
**Scope:** Complete migration of the agent chat interface from a monolithic 4,374-line HTML file to a React + TypeScript + Tailwind CSS application running inside JCEF (embedded Chromium in IntelliJ). No Swing fallback. Full feature parity in a single cutover.

## Context

The current agent chat UI is a single `agent-chat.html` file (4,374 lines) with inline CSS, inline JS, and a separate `agent-plan.html`. It uses Prism.js for syntax highlighting, marked.js for markdown, and manually manages DOM updates on every streaming token — re-parsing all markdown every 300ms. Rich visualizations (mermaid, charts, diffs) are bolted on with no unified loading, error handling, or configuration.

This spec defines the replacement: a React 18 application built with Vite, styled with Tailwind CSS v4, using prompt-kit components (shadcn/ui based, MIT licensed) as the foundation. The React app runs inside the same JCEF panel, served from the JAR via the existing `CefResourceSchemeHandler`. All existing JS-to-Kotlin (26 `JBCefJSQuery` bridges) and Kotlin-to-JS (40+ `callJs()` methods) bridges are preserved with zero changes to the Kotlin-side API.

## Design Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | React 18 + TypeScript + Tailwind CSS v4 + Vite | Industry-standard stack. Vite for fast dev iteration and optimized production builds. TypeScript for type safety across the bridge layer. |
| 2 | prompt-kit as component foundation | MIT-licensed, shadcn/ui-based copy-paste components. Provides Tool, Reasoning, Steps, ChatContainer, Message, PromptInput, CodeBlock, ScrollButton, Loader, FeedbackBar, PromptSuggestion. Customize with IDE theme colors. |
| 3 | Full feature parity — single milestone | No half-migrated state. Every existing capability migrates before cutover. |
| 4 | Shadow build transition | Develop standalone via `npm run dev` (Vite dev server). Old HTML stays working until React app is feature-complete. Single cutover. |
| 5 | JCEF only — no Swing fallback | Target IntelliJ 2025.1+ where JCEF is guaranteed. Delete `RichStreamingPanel.kt`. |
| 6 | Per-type visualization config | Toggles + behavior settings (auto-render, default expand/collapse, max height) per visualization type. |
| 7 | Single panel + fullscreen popout | Chat in one scrollable column. Rich blocks have expand (in-page overlay) and "Open in tab" (IDE editor tab). |
| 8 | Contextual input bar | Clean by default. Progressive disclosure: `@` triggers mentions, hover reveals model selector, mode toggle via icon. |
| 9 | Elevated IDE styling | prompt-kit aesthetics + JetBrains Mono for code + IDE background colors + subtle borders matching IDE chrome. |
| 10 | Configurable rich visualizations | All wrapped in `<RichBlock>` with skeleton loading, expand/fullscreen, error fallback, settings-aware rendering. |
| 11 | CSS transitions over motion library | All animations use CSS transitions/keyframes. No motion/framer-motion dependency — reduces bundle by ~30 KB and avoids JS animation overhead. |
| 12 | Shiki with curated language set | Pre-load 15 common languages at startup (~180 KB total), lazy-load others on demand. |

---

## Architecture

### Directory Structure

```
agent/
  webview/                              # NEW — React frontend
    src/
      main.tsx                          # Entry point
      App.tsx                           # Root: theme provider + chat container
      bridge/
        jcef-bridge.ts                  # Kotlin<->JS communication protocol
        types.ts                        # Message, ToolCall, Plan, Question types
        theme-controller.ts             # IDE theme CSS variable injection
      components/
        chat/                           # ChatContainer, MessageList, MessageCard
        agent/                          # ToolCallCard, ThinkingBlock, PlanCard,
                                        # ApprovalGate, StatusIndicator, StepsView
        markdown/                       # MarkdownRenderer, CodeBlock, InlineCode
        rich/                           # RichBlock, MermaidDiagram, ChartView,
                                        # FlowDiagram, MathBlock, InteractiveHtml,
                                        # DiffHtml, AnsiOutput
        input/                          # PromptInput, MentionAutocomplete, ContextChip
        common/                         # ScrollButton, FullscreenOverlay, Skeleton,
                                        # SessionMetrics, FeedbackBar, ToolsPanel,
                                        # VisualizationSettings
      hooks/
        useStreaming.ts                  # Token append + partial markdown
        useAutoScroll.ts                # Scroll-to-bottom with user override
        useTheme.ts                     # CSS variable injection from Kotlin
        useVirtualScroll.ts             # TanStack Virtual integration
        useRichBlock.ts                 # Lazy load + error boundary + settings
      stores/
        chatStore.ts                    # Zustand: messages, streaming, tool calls
        settingsStore.ts                # Zustand: visualization config
        themeStore.ts                   # Zustand: IDE theme variables
      config/
        visualization-defaults.ts      # Default per-type settings
        animation-constants.ts         # Duration, easing values
    index.html
    vite.config.ts
    tailwind.config.ts
    package.json
    tsconfig.json
  src/main/kotlin/.../ui/
    AgentCefPanel.kt                    # MODIFIED — serves React build output
    AgentColors.kt                      # KEPT — theme color source of truth
    CefResourceSchemeHandler.kt         # MODIFIED — serve from webview/dist/
    RichStreamingPanel.kt               # DELETED
    AgentDashboardPanel.kt              # SIMPLIFIED — JCEF only, no fallback
```

### Module Boundaries

The React app is a build artifact. It lives in `agent/webview/`, builds to `agent/src/main/resources/webview/dist/`, and is served from the JAR at runtime. The Kotlin side owns:

- Theme color definitions (`AgentColors.kt`)
- Bridge method implementations (all `JBCefJSQuery` handlers)
- Agent runtime, tool execution, orchestrator logic (unchanged)

The React side owns:

- All rendering, layout, animation
- State management (Zustand stores)
- Markdown parsing, syntax highlighting, rich visualization rendering

---

## Components

### From prompt-kit (copy + customize)

These are copied into the project (not installed as a dependency) and restyled with IDE theme CSS variables.

| Component | Purpose | Customization |
|-----------|---------|---------------|
| `Tool` | Tool call cards with status (pending/running/completed/error), expandable input/output | IDE colors, JetBrains Mono for args/output |
| `Reasoning` | Collapsible thinking blocks, auto-close when stream ends | Dimmed background matching `--thinking-bg` |
| `ThinkingBar` | Animated thinking indicator during processing | Accent color shimmer |
| `Steps` | Plan step visualization with status indicators | Status colors from AgentColors |
| `TextShimmer` | Shimmer effect for streaming/loading text | Skeleton colors from theme |
| `CodeBlock` | Syntax-highlighted code with copy button | Shiki highlighter, JetBrains Mono, `--code-bg` |
| `ChatContainer` | Scrollable message container | Virtual scroll integration at 100+ messages |
| `Message` | Message card (user/agent) — NOT chat bubbles | `--user-bg` / `--tool-bg` backgrounds |
| `PromptInput` | Text input with auto-resize | IDE input styling, contextual disclosure |
| `Loader` | Typing/loading indicators | Accent color dots |
| `ScrollButton` | "Scroll to bottom" floating indicator | Subtle shadow, accent border |
| `FeedbackBar` | Thumbs up/down on messages | Muted icons, accent on hover |
| `PromptSuggestion` | Follow-up suggestion chips | Surface background, accent border on hover |

### Custom Components

| Component | Purpose |
|-----------|---------|
| `ApprovalGate` | Warning-bordered card with approve/deny buttons for destructive actions (PR creation, merges, deploys). Yellow/orange border with caution icon. |
| `DiffView` | Inline code diff with accept/reject per-hunk or per-file. Uses diff2html for rendering. |
| `PlanCard` | Full plan with numbered steps, per-step status, per-step comment input, approve/revise buttons, clickable file links. |
| `QuestionWizard` | Multi-page question flow with back/next navigation, single/multi-select options, text input, summary page before submit. |
| `RichBlock` | Unified wrapper for ALL rich visualizations. See [Rich Visualization Framework](#rich-visualization-framework). |
| `MermaidDiagram` | Mermaid rendering with zoom, pan, expand, node click events. |
| `ChartView` | Chart.js canvas with hover tooltips, legend toggle, responsive resize. |
| `FlowDiagram` | dagre-based directed graph with animated edges, node hover tooltips. |
| `MathBlock` | KaTeX inline/block rendering with "Copy as LaTeX" button. |
| `InteractiveHtml` | Sandboxed iframe for custom visualizations with theme variable injection. |
| `DiffHtml` | diff2html side-by-side viewer with per-hunk actions. |
| `AnsiOutput` | Terminal output with ANSI color rendering via ansi_up. |
| `ContextChip` | File/tool/service context badges in the input area. |
| `MentionAutocomplete` | `@`-mention dropdown with file, symbol, and tool search. Results from Kotlin via bridge. |
| `SessionMetrics` | Tokens used, duration, iteration count, files modified, completion status. |
| `ToolsPanel` | Tool enable/disable checklist with search filter. |
| `VisualizationSettings` | Per-type toggles and behavior config UI. |
| `FullscreenOverlay` | Modal overlay for expanded rich blocks. Close on Escape or click outside. |

---

## State Management

Zustand stores with no boilerplate. Each store is a single file with typed state and actions.

### ChatStore

```typescript
interface ChatStore {
  // State
  messages: Message[];
  activeStream: { text: string; isStreaming: boolean } | null;
  activeToolCalls: Map<string, ToolCall>;
  plan: Plan | null;
  session: SessionInfo;
  inputState: {
    locked: boolean;
    mentions: Mention[];
    model: string;
    mode: 'agent' | 'plan';
  };

  // Actions
  appendToken(token: string): void;
  endStream(): void;
  addToolCall(name: string, args: string, status: string): void;
  updateToolCall(name: string, status: string, result: string, duration: number): void;
  addMessage(role: 'user' | 'agent', content: string): void;
  setPlan(plan: Plan): void;
  updatePlanStep(stepId: string, status: string): void;
  sendMessage(text: string, mentions: Mention[]): void;
  startSession(task: string): void;
  completeSession(info: SessionInfo): void;
}
```

### SettingsStore

```typescript
interface VisualizationConfig {
  enabled: boolean;
  autoRender: boolean;       // false = show "Click to render" button
  defaultExpanded: boolean;  // true = no max-height constraint
  maxHeight: number;         // pixels, 0 = unlimited
}

interface SettingsStore {
  visualizations: Record<VisualizationType, VisualizationConfig>;
  updateVisualization(type: VisualizationType, config: Partial<VisualizationConfig>): void;
}

type VisualizationType = 'mermaid' | 'chart' | 'flow' | 'math' | 'diff' | 'interactiveHtml';
```

### ThemeStore

```typescript
interface ThemeStore {
  cssVariables: Record<string, string>;  // Injected from AgentColors.kt
  isDark: boolean;
  applyTheme(cssVarsJson: string): void;
}
```

---

## JCEF Bridge Protocol — Complete Audit

This section is an exhaustive audit of every bridge in `AgentCefPanel.kt`. The React frontend must implement every function listed here.

### JS to Kotlin Bridges (26 `JBCefJSQuery` handlers)

Each `JBCefJSQuery` field in `AgentCefPanel.kt` is injected as a `window._xxx` global function on page load. The React bridge layer (`jcef-bridge.ts`) calls these globals.

| # | JBCefJSQuery field | Injected JS function | Signature | Kotlin callback |
|---|---|---|---|---|
| 1 | `undoQuery` | `window._requestUndo()` | `() => void` | `onUndoRequested` |
| 2 | `traceQuery` | `window._requestViewTrace()` | `() => void` | `onViewTraceRequested` |
| 3 | `promptQuery` | `window._submitPrompt(text)` | `(text: string) => void` | `onPromptSubmitted` |
| 4 | `planApproveQuery` | `window._approvePlan()` | `() => void` | `onPlanApproved` |
| 5 | `planReviseQuery` | `window._revisePlan(comments)` | `(comments: string) => void` | `onPlanRevised` — JSON string of `{stepId: comment}` |
| 6 | `toolToggleQuery` | `window._toggleTool(data)` | `(data: string) => void` | `onToolToggled` — data format: `"tool_name:1"` or `"tool_name:0"` |
| 7 | `questionAnsweredQuery` | `window._questionAnswered(qid, opts)` | `(qid: string, opts: string) => void` | `onQuestionAnswered` — sends `"questionId:optionsJson"` |
| 8 | `questionSkippedQuery` | `window._questionSkipped(qid)` | `(qid: string) => void` | `onQuestionSkipped` |
| 9 | `chatAboutQuery` | `window._chatAboutOption(qid, label, msg)` | `(qid: string, label: string, msg: string) => void` | `onChatAboutOption` — joined with `\x1F` (unit separator) |
| 10 | `questionsSubmittedQuery` | `window._questionsSubmitted()` | `() => void` | `onQuestionsSubmitted` |
| 11 | `questionsCancelledQuery` | `window._questionsCancelled()` | `() => void` | `onQuestionsCancelled` |
| 12 | `editQuestionQuery` | `window._editQuestion(qid)` | `(qid: string) => void` | `onEditQuestion` |
| 13 | `deactivateSkillQuery` | `window._deactivateSkill()` | `() => void` | `onSkillDismissed` |
| 14 | `navigateToFileQuery` | `window._navigateToFile(path)` | `(path: string) => void` | `onNavigateToFile` — parses `"filePath:lineNum"` from `path` |
| 15 | `cancelTaskQuery` | `window._cancelTask()` | `() => void` | `onCancelTask` |
| 16 | `newChatQuery` | `window._newChat()` | `() => void` | `onNewChat` |
| 17 | `sendMessageQuery` | `window._sendMessage(text)` | `(text: string) => void` | `onSendMessage` |
| 18 | `changeModelQuery` | `window._changeModel(modelId)` | `(modelId: string) => void` | `onChangeModel` |
| 19 | `togglePlanModeQuery` | `window._togglePlanMode(enabled)` | `(enabled: boolean) => void` | `onTogglePlanMode` — sends `String(enabled)`, parsed as `== "true"` |
| 20 | `activateSkillQuery` | `window._activateSkill(name)` | `(name: string) => void` | `onActivateSkill` |
| 21 | `requestFocusIdeQuery` | `window._requestFocusIde()` | `() => void` | `onRequestFocusIde` |
| 22 | `openSettingsQuery` | `window._openSettings()` | `() => void` | `onOpenSettings` |
| 23 | `openToolsPanelQuery` | `window._openToolsPanel()` | `() => void` | `onOpenToolsPanel` |
| 24 | `searchMentionsQuery` | `window._searchMentions(data)` | `(data: string) => void` | `mentionSearchProvider?.search()` — data format: `"type:query"` (e.g., `"file:Login"`, `"categories:"`) |
| 25 | `sendMessageWithMentionsQuery` | `window._sendMessageWithMentions(payload)` | `(payload: string) => void` | `onSendMessageWithMentions` — payload is JSON: `{"text": "...", "mentions": [...]}` |

> **Note (bridge #24 — GlobalScope fix):** The `searchMentionsQuery` handler currently uses `GlobalScope.launch(Dispatchers.IO)` which leaks coroutines if the panel is disposed during a search. During this migration, replace with a project-scoped `CoroutineScope` tied to the panel's `Disposable` lifecycle:
> ```kotlin
> private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
> // In searchMentionsQuery handler:
> scope.launch { ... }
> // In dispose():
> scope.cancel()
> ```

### Kotlin to JS Bridges (40 public methods calling `callJs()`)

These are all public methods on `AgentCefPanel` that call JavaScript functions in the frontend. The React app must expose every one of these as a global function (or on `window.__bridge`).

| # | Kotlin method | JS function called | Signature |
|---|---|---|---|
| 1 | `startSession(task)` | `startSession(task)` | `(task: string) => void` |
| 2 | `appendUserMessage(text)` | `appendUserMessage(text)` | `(text: string) => void` |
| 3 | `completeSession(...)` | `endStream(); completeSession(status, tokensUsed, durationMs, iterations, [files])` | `(status: string, tokens: number, duration: number, iterations: number, files: string[]) => void` |
| 4 | `appendStreamToken(token)` | `appendToken(token)` | `(token: string) => void` |
| 5 | `flushStreamBuffer()` | `endStream()` | `() => void` |
| 6 | `appendToolCall(toolName, args, status)` | `appendToolCall(toolName, args, status)` | `(name: string, args: string, status: string) => void` |
| 7 | `updateLastToolCall(status, result, durationMs, toolName)` | `updateToolResult(result, durationMs, toolName)` | `(result: string, durationMs: number, toolName: string) => void` |
| 8 | `appendEditDiff(filePath, oldText, newText, accepted)` | `appendDiff(filePath, [oldLines], [newLines], accepted)` | `(path: string, oldLines: string[], newLines: string[], accepted: boolean\|null) => void` |
| 9 | `appendStatus(message, type)` | `appendStatus(message, type)` | `(message: string, type: string) => void` |
| 10 | `appendError(message)` | `appendStatus(message, 'ERROR')` | via `appendStatus` |
| 11 | `appendThinking(text)` | `appendThinking(text)` | `(text: string) => void` |
| 12 | `clear()` | `clearChat()` | `() => void` |
| 13 | `showToolsPanel(toolsJson)` | `showToolsPanel(toolsJson)` | `(toolsJson: string) => void` |
| 14 | `hideToolsPanel()` | `closeToolsPanel()` | `() => void` |
| 15 | `renderPlan(planJson)` | `renderPlan(planJson)` | `(planJson: string) => void` |
| 16 | `updatePlanStep(stepId, status)` | `updatePlanStep(stepId, status)` | `(stepId: string, status: string) => void` |
| 17 | `showQuestions(questionsJson)` | `showQuestions(questionsJson)` | `(questionsJson: string) => void` |
| 18 | `showQuestion(index)` | `showQuestion(index)` | `(index: number) => void` |
| 19 | `showQuestionSummary(summaryJson)` | `showQuestionSummary(summaryJson)` | `(summaryJson: string) => void` |
| 20 | `enableChatInput()` | `enableChatInput()` | `() => void` |
| 21 | `setBusy(busy)` | `setBusy(busy)` | `(busy: boolean) => void` |
| 22 | `setInputLocked(locked)` | `setInputLocked(locked)` | `(locked: boolean) => void` |
| 23 | `updateTokenBudget(used, max)` | `updateTokenBudget(used, max)` | `(used: number, max: number) => void` |
| 24 | `setModelName(name)` | `setModelName(name)` | `(name: string) => void` |
| 25 | `updateSkillsList(skillsJson)` | `updateSkillsList(skillsJson)` | `(skillsJson: string) => void` |
| 26 | `showRetryButton(lastMessage)` | `showRetryButton(lastMessage)` | `(lastMessage: string) => void` |
| 27 | `focusInput()` | `focusInput()` | `() => void` |
| 28 | `showSkillBanner(name)` | `showSkillBanner(name)` | `(name: string) => void` |
| 29 | `hideSkillBanner()` | `hideSkillBanner()` | `() => void` |
| 30 | `appendChart(chartConfigJson)` | `appendChart(chartConfigJson)` | `(configJson: string) => void` |
| 31 | `appendAnsiOutput(text)` | `appendAnsiOutput(text)` | `(text: string) => void` |
| 32 | `showSkeleton()` | `showSkeleton()` | `() => void` |
| 33 | `hideSkeleton()` | `hideSkeleton()` | `() => void` |
| 34 | `showToast(message, type, durationMs)` | `showToast(message, type, durationMs)` | `(message: string, type: string, durationMs: number) => void` |
| 35 | `appendTabs(tabsJson)` | `appendTabs(tabsJson)` | `(tabsJson: string) => void` |
| 36 | `appendTimeline(itemsJson)` | `appendTimeline(itemsJson)` | `(itemsJson: string) => void` |
| 37 | `appendProgressBar(percent, type)` | `appendProgressBar(percent, type)` | `(percent: number, type: string) => void` |
| 38 | `appendJiraCard(cardJson)` | `appendJiraCard(cardJson)` | `(cardJson: string) => void` |
| 39 | `appendSonarBadge(badgeJson)` | `appendSonarBadge(badgeJson)` | `(badgeJson: string) => void` |
| 40 | `setText(text)` | `clearChat(); appendToken(text); endStream()` | composite call |
| 41 | `receiveMentionResults(resultsJson)` | `receiveMentionResults(resultsJson)` | `(resultsJson: string) => void` — called from `searchMentionsQuery` handler after async search completes |

**Theme-specific JS calls (from `applyCurrentTheme()`):**

| # | JS function called | Signature |
|---|---|---|
| 42 | `applyTheme({...})` | `(vars: Record<string, string>) => void` |
| 43 | `setPrismTheme(isDark)` | `(isDark: boolean) => void` — React replaces with Shiki theme switch |
| 44 | `setMermaidTheme(isDark)` | `(isDark: boolean) => void` — React handles via `themeStore.isDark` |

### Bridge Migration Notes

- The React app registers all JS functions as both globals (`window.functionName`) and on `window.__bridge` for structured access.
- The `jcef-bridge.ts` module provides the mapping layer: each global function delegates to the appropriate Zustand store action or React callback.
- For browser dev mode, `jcef-bridge.ts` detects `!window._sendMessage` and installs mock implementations.
- The total bridge count is **26 JS-to-Kotlin** + **44 Kotlin-to-JS** = **70 bridge functions** (not 24 as originally estimated).

---

## Streaming Pipeline

The current implementation re-parses all markdown on every token batch (300ms throttle). The React implementation eliminates this by isolating re-renders to only the active streaming component.

```
Kotlin: appendStreamToken(token)
  -> callJs("appendToken(escaped-token)")
    -> window.appendToken(token)  // global function
      -> Zustand: chatStore.activeStream.text += token
        -> React: ONLY the active streaming MessageCard re-renders
          -> react-markdown renders partial markdown incrementally
          -> Open code fences show skeleton placeholder until closed
          -> Blinking cursor at insertion point
          -> No other MessageCard or component re-renders
```

### Token escaping

Tokens are escaped via `AgentCefPanel.jsonStr()` before injection into `executeJavaScript`. The method escapes backslashes, single quotes, double quotes, newlines (`\n`), carriage returns (`\r`), and tabs (`\t`), wrapping the result in single quotes. The bridge layer receives already-unescaped strings from the JS engine.

### Throttling

Zustand state updates are batched by React 18's automatic batching. If token throughput exceeds 60fps rendering capacity, `requestAnimationFrame` gates visual updates while the store accumulates tokens synchronously.

---

## Rich Visualization Framework

### RichBlock Wrapper

Every rich visualization is wrapped in a `<RichBlock>` component that provides:

1. **Skeleton loading** — `TextShimmer` from prompt-kit while the visualization library lazy-loads
2. **Header bar** — Type label (e.g., "Diagram", "Chart") + action buttons
3. **Actions** — Copy content, Expand (in-page overlay), Open in Tab (IDE editor tab via bridge), Fullscreen
4. **Configurable max-height** — "Show more" gradient fade at bottom when content exceeds `maxHeight`
5. **Error boundary** — If rendering fails, show raw source code with "Retry" button
6. **Settings-aware** — Respects `visualizations.<type>.enabled`, `.autoRender`, `.defaultExpanded`, `.maxHeight`
7. **Theme-aware** — Re-renders when IDE theme changes (via `themeStore` subscription)
8. **Entrance animation** — Fade-in (200ms, ease-out) via CSS transition

### Lazy Loading Strategy

Heavy visualization libraries are code-split by Vite into separate chunks and loaded on first use via dynamic `import()`. All library code is **bundled into chunks at build time** — no runtime fetching from CDNs or external URLs. This is enforced by the CSP header `connect-src: 'none'` on the JCEF resource handler.

| Library | Gzip Size | Load Trigger | Bundling Notes |
|---------|-----------|-------------|----------------|
| mermaid | ~250 KB | First `mermaid` code fence | Single chunk |
| katex | ~320 KB | First `math` or `$$` block | **KaTeX fonts must be bundled** — configure `copy-webpack-plugin` or Vite `publicDir` to include `katex/dist/fonts/` in the output. Fonts are referenced by CSS `@font-face` rules. |
| chart.js | ~65 KB | First `chart` code fence | Single chunk |
| dagre | ~284 KB | First `flow` code fence | Single chunk |
| diff2html | ~40 KB | First diff block | CSS bundled alongside JS |

Each uses dynamic `import()`. The `RichBlock` wrapper shows a skeleton shimmer during load. Libraries are loaded once and cached for the session lifetime.

### Interaction Patterns

| Type | Compact View | Expanded View | Interactions |
|------|-------------|---------------|-------------|
| Mermaid | SVG with max-height fade | Full SVG in overlay | Zoom, pan, node click, copy SVG |
| Chart | Canvas at standard size | Full chart in overlay | Hover tooltips, legend toggle, responsive resize |
| Flow | SVG with max-height fade | Full SVG in overlay | Zoom, pan, node hover, animated edges |
| Math | Rendered inline/block | N/A (always fits) | Copy as LaTeX |
| Diff | Compact side-by-side | Full diff in overlay or IDE tab | Accept/reject per hunk, copy |
| Interactive HTML | iframe at fixed height | Full iframe in overlay | Sandboxed interactivity, theme vars injected |

### Per-Type Settings

Stored in `PluginSettings` and synced to the React app via bridge:

```
visualizations.mermaid.enabled = true
visualizations.mermaid.autoRender = true
visualizations.mermaid.defaultExpanded = false
visualizations.mermaid.maxHeight = 300

visualizations.chart.enabled = true
visualizations.chart.autoRender = true
visualizations.chart.defaultExpanded = false
visualizations.chart.maxHeight = 300

// Same pattern for: flow, math, diff, interactiveHtml
```

A new "Visualizations" section in the AI & Advanced settings page exposes these toggles.

---

## Input Bar Design

### Default State

A clean text area with a send button. Nothing else visible. Placeholder text: "Ask anything..."

### Progressive Disclosure

| Trigger | Reveals | Behavior |
|---------|---------|----------|
| Type `@` | `MentionAutocomplete` dropdown | Shows files, folders, symbols, tools. Results fetched from Kotlin via `window._searchMentions("type:query")`, results returned via `receiveMentionResults(json)`. Arrow keys to navigate, Enter to select, Escape to dismiss. |
| Hover or focus input | Model selector icon (bottom-left) | Small icon appears. Click opens model dropdown. |
| Click model icon | Model dropdown | List of available models. Selection persists via `window._changeModel(modelId)`. |
| Mode toggle icon | Agent/Plan mode switch | Small icon at bottom-left, next to model. Toggles between `agent` and `plan` mode via `window._togglePlanMode(enabled)`. |
| First use | Keyboard shortcut hint | GotItTooltip: "Enter = send, Shift+Enter = newline" |

### Action Toolbar

Below the input, visible on hover or when contextually relevant:

- **Always on hover:** Undo, New Chat, Traces, Settings
- **During execution:** Stop button (visible without hover)

---

## Animation Specifications

All animations use **CSS transitions and keyframes only** — no JavaScript animation library. All use GPU-accelerated properties only (`transform`, `opacity`). All respect `prefers-reduced-motion: reduce` by disabling or shortening to 0ms via a global media query.

```css
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    transition-duration: 0.01ms !important;
  }
}
```

| Element | Property | Duration | Easing | Notes |
|---------|----------|----------|--------|-------|
| Message enter | `translateY(8px)` + `opacity` | 220ms | ease-out | Stagger 40ms between consecutive via `animation-delay` |
| Tool card expand | `max-height` + `opacity` | 300ms | cubic-bezier(0.4, 0, 0.2, 1) | CSS `will-change: max-height` |
| Thinking shimmer | `background-position` sweep | 1800ms | ease-in-out | `@keyframes` infinite loop |
| Streaming cursor | `opacity` blink | 530ms | step-end | `@keyframes` infinite, stops on stream end |
| Rich block enter | `opacity` | 200ms | ease-out | After skeleton resolves |
| Status icon transition | `opacity` crossfade | 200ms | ease | Between pending/running/done |
| Overlay open | `opacity` + `scale(0.97 -> 1)` | 200ms | ease-out | Backdrop fade concurrent |
| Overlay close | `opacity` | 150ms | ease-in | Faster than open |
| Scroll button | `translateY` + `opacity` | 200ms | ease | Slide up from bottom |
| Button press | `scale(0.97)` | 80ms | ease | Tactile feedback via `:active` pseudo-class |

---

## Virtual Scrolling

At 100+ messages, `ChatContainer` switches to virtual scrolling via `@tanstack/react-virtual`.

- **Viewport:** renders only visible messages + 5 overscan above/below
- **Dynamic height:** each message measures its own height; the virtualizer tracks measured sizes
- **Scroll anchoring:** when the user is scrolled to bottom, new messages auto-scroll. When the user scrolls up, position is preserved and the `ScrollButton` appears.
- **Smooth scroll:** `scrollToBottom` uses `behavior: 'smooth'` with a 300ms duration
- **Performance target:** 60fps with 1000+ messages in the store

---

## Theme Integration

`AgentColors.kt` remains the source of truth for all theme colors. When the IDE theme changes (or on initial page load), `AgentCefPanel.applyCurrentTheme()` calls `applyTheme({...})` with a JS object mapping CSS variable names (without `--` prefix) to values. The JS-side `applyTheme()` function iterates the object and sets `document.documentElement.style.setProperty('--' + key, value)` for each entry.

After `applyTheme()`, two additional calls are made:
- `setPrismTheme(isDark)` — in React, this maps to switching the Shiki theme
- `setMermaidTheme(isDark)` — in React, this maps to `themeStore.isDark` for mermaid theme selection

The `ThemeStore` receives theme updates, applies CSS variables, and triggers re-renders for theme-dependent components (primarily rich visualizations that need to re-render SVGs or canvases).

### CSS Variable Map — Actual Variables from AgentCefPanel.kt

These are the **exact** variable names sent by `applyCurrentTheme()` in `AgentCefPanel.kt`. The React app's CSS must use these names, not invented alternatives.

**Core layout:**
- `--bg` — panel background (from `AgentColors.panelBg`)
- `--fg` — primary text (from `AgentColors.primaryText`)
- `--fg-secondary` — secondary text
- `--fg-muted` — muted/dimmed text
- `--border` — standard border color

**Semantic backgrounds:**
- `--user-bg` — user message background (from `AgentColors.userMsgBg`)
- `--tool-bg` — tool call card background (from `AgentColors.toolCallBg`)
- `--code-bg` — code block background (from `AgentColors.codeBg`)
- `--thinking-bg` — thinking block background (from `AgentColors.thinkingBg`)

**Tool category badges (background + foreground pairs):**
- `--badge-read-bg`, `--badge-read-fg`
- `--badge-write-bg`, `--badge-write-fg`
- `--badge-edit-bg`, `--badge-edit-fg`
- `--badge-cmd-bg`, `--badge-cmd-fg`
- `--badge-search-bg`, `--badge-search-fg`

**Tool category accent colors:**
- `--accent-read`, `--accent-write`, `--accent-edit`, `--accent-cmd`, `--accent-search`

**Diff colors:**
- `--diff-add-bg`, `--diff-add-fg`
- `--diff-rem-bg`, `--diff-rem-fg`

**Status colors:**
- `--success`, `--error`, `--warning`, `--link`

**UI chrome:**
- `--hover-overlay` — subtle hover overlay (`rgba(255,255,255,0.03)` dark / `rgba(0,0,0,0.03)` light)
- `--hover-overlay-strong` — stronger hover overlay
- `--divider-subtle` — thin divider lines
- `--row-alt` — alternating row background
- `--input-bg` — input field background
- `--input-border` — input field border
- `--toolbar-bg` — toolbar background
- `--chip-bg` — chip/pill background
- `--chip-border` — chip/pill border

**Fonts (from `:root` defaults in HTML, not sent by Kotlin):**
- `--font-body` — system sans-serif stack
- `--font-mono` — `'JetBrains Mono', Menlo, Consolas, 'Courier New', monospace`

**Variables from `agent-plan.html` (additional):**
- `--accent` — accent/highlight color
- `--running` — running state color
- `--pending` — pending state color

> **Kotlin-side change required:** The spec adds these variables to `applyCurrentTheme()` to support the React app's broader needs. These are new additions, not renames:
> - `--accent` (map to a suitable accent from `AgentColors`)
> - `--running` (alias for `--link` or a dedicated running color)
> - `--pending` (alias for `--fg-muted`)
> - `--font-mono` (hardcoded `'JetBrains Mono', Menlo, Consolas, monospace'`)

Tailwind CSS v4 references these variables directly in `tailwind.config.ts`, so all utility classes (e.g., `bg-[var(--tool-bg)]`, `text-[var(--fg-secondary)]`) resolve to the current theme.

---

## Build Pipeline

### Vite Configuration

```typescript
// agent/webview/vite.config.ts
export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    outDir: '../src/main/resources/webview/dist',
    emptyOutDir: true,
    rollupOptions: {
      output: {
        // Allow code splitting — CefResourceSchemeHandler serves ALL files
        // under webview/dist/ by path, so multiple chunks work natively.
        // Do NOT use inlineDynamicImports: true — it prevents code splitting.
        manualChunks: {
          // Viz libraries get predictable chunk names for debugging
          mermaid: ['mermaid'],
          katex: ['katex'],
          chartjs: ['chart.js'],
          dagre: ['dagre'],
          diff2html: ['diff2html'],
        },
      }
    },
    assetsInlineLimit: 8192,           // Inline assets < 8KB as base64 (Vite default)
    minify: 'terser',
    terserOptions: {
      compress: { drop_console: true, drop_debugger: true }
    }
  }
});
```

#### Why directory-based serving works with code splitting

`CefResourceSchemeHandler.processRequest()` already resolves any URL path under `http://workflow-agent/` to `webview/$path` in the classloader. This means Vite's code-split chunks (e.g., `assets/mermaid-abc123.js`) are served correctly — the handler loads `webview/dist/assets/mermaid-abc123.js` from the JAR. No special configuration is needed. The `assetsInlineLimit` is set to 8192 (8 KB) rather than 100 KB because:
1. Large base64-inlined assets bloat the main bundle and slow initial parse
2. KaTeX fonts (~80-200 KB each) must remain as separate files for `@font-face` to work
3. The resource handler serves files efficiently from the JAR — there is no network cost to separate files

### Gradle Integration

```kotlin
// agent/build.gradle.kts
tasks.register<Exec>("buildWebview") {
    workingDir = file("webview")
    commandLine("npm", "run", "build")
}

tasks.named("processResources") {
    dependsOn("buildWebview")
}
```

The `processResources` task depends on `buildWebview`, so `./gradlew buildPlugin` automatically builds the React app. During development, `npm run dev` in `agent/webview/` runs the Vite dev server independently.

### Resource Serving

`CefResourceSchemeHandler.kt` is modified to serve files from `webview/dist/` instead of the old `webview/` directory. The change is minimal:

```kotlin
// In processRequest():
val path = url.removePrefix(BASE_URL).takeIf { it.isNotBlank() } ?: "index.html"  // was "agent-chat.html"
val resourcePath = "webview/dist/$path"  // was "webview/$path"
```

The entry point changes from `agent-chat.html` to `index.html`. CSP headers are preserved (`connect-src: 'none'`). All Vite-generated chunks, assets, and font files are served by the same path-based resolution.

---

## Syntax Highlighting — Shiki Strategy

Shiki replaces Prism.js for VS Code-quality syntax highlighting. Because all resources are served from the JAR (no network), the Shiki WASM engine and all grammar files must be bundled.

### Loading Strategy

Use `createHighlighter()` with a curated set of 15 common languages pre-loaded at startup:

```typescript
const PRELOADED_LANGUAGES = [
  'typescript', 'javascript', 'python', 'java', 'kotlin',
  'html', 'css', 'json', 'yaml', 'xml',
  'bash', 'sql', 'markdown', 'diff', 'groovy'
];

const highlighter = await createHighlighter({
  themes: ['github-dark', 'github-light'],
  langs: PRELOADED_LANGUAGES,
});
```

### Size Estimate

- Shiki core + WASM engine: ~1.5 MB (gzip ~500 KB) — loaded once at startup
- Each language grammar: ~5-50 KB (15 preloaded = ~200 KB total gzip)
- Two themes: ~30 KB total
- **Total initial load: ~730 KB gzip** — comparable to Prism.js with 297-language autoloader

Additional languages are lazy-loaded via `highlighter.loadLanguage('rust')` on first encounter of a code fence with that language identifier.

---

## Dependencies

All MIT or Apache 2.0 licensed.

### Core (always loaded)

| Package | Gzip Size | Purpose |
|---------|-----------|---------|
| react + react-dom | 42 KB | UI framework |
| zustand | 3 KB | State management |
| react-markdown + remark-gfm | 16 KB | Markdown rendering |
| shiki | ~730 KB | VS Code-quality syntax highlighting (WASM + 15 languages + 2 themes) |
| @tanstack/react-virtual | 4 KB | Virtual scrolling |
| tailwindcss (purged) | 6 KB | Utility CSS |
| ansi_up | 3 KB | ANSI terminal color rendering |
| DOMPurify | 18 KB | XSS prevention for user-generated HTML |

> **No motion/framer-motion dependency.** All animations are handled by CSS transitions and keyframes. This saves ~30 KB gzip and avoids JS animation overhead. CSS transitions cover all specified animations (fade, slide, scale, shimmer). If a future feature requires spring physics or complex timeline orchestration that CSS cannot handle, motion can be reconsidered — but no current animation spec requires it.

### Lazy-loaded (on first use)

| Package | Gzip Size | Purpose | Bundling Notes |
|---------|-----------|---------|----------------|
| mermaid | 250 KB | Diagrams | Single Vite chunk |
| katex | 320 KB | Math rendering | Fonts (~1 MB) bundled in `dist/fonts/` |
| chart.js | 65 KB | Charts | Single Vite chunk |
| dagre | 284 KB | Graph layout for flow diagrams | Single Vite chunk |
| diff2html | 40 KB | Diff visualization | CSS bundled with JS chunk |

---

## File Changes Summary

### Deleted

| File | Reason |
|------|--------|
| `agent-chat.html` (4,374 lines) | Replaced by React app |
| `agent-plan.html` | Merged into `PlanCard` component |
| `RichStreamingPanel.kt` | Swing fallback removed |
| `lib/marked.min.js` | Replaced by react-markdown |
| `lib/prism-core.min.js` + `prism-languages/` + `prism-themes/` | Replaced by Shiki |
| Fallback detection logic in `AgentDashboardPanel.kt` | JCEF only, no fallback needed |

### Modified

| File | Change |
|------|--------|
| `AgentCefPanel.kt` | Load `index.html` from `webview/dist/` instead of `agent-chat.html`. Fix `GlobalScope` to project-scoped coroutine. Same bridge API. |
| `CefResourceSchemeHandler.kt` | Default path `index.html`, resource prefix `webview/dist/`. |
| `AgentDashboardPanel.kt` | Simplified to JCEF only. Remove fallback panel selection logic. |
| `PluginSettings` | Add `visualizations.*` settings fields. |
| AI & Advanced settings page | Add "Visualizations" section with per-type toggles. |
| `agent/CLAUDE.md` | Update to document React webview architecture, build commands, bridge protocol. |

### Kept (unchanged)

| File | Reason |
|------|--------|
| `AgentColors.kt` | Source of truth for theme colors. |
| All 26 `JBCefJSQuery` bridge registrations | Same Kotlin API, same method signatures. |
| `CefResourceSchemeHandler` offline serving pattern | Same architecture, different source directory. |
| CSP headers | Security preserved. |
| All Kotlin-side agent logic | No changes to runtime, tools, orchestrator. |

### Created

The entire `agent/webview/` directory tree as described in [Architecture](#architecture).

---

## Development Workflow

### During Development

1. `cd agent/webview && npm run dev` — Vite dev server on `localhost:5173`
2. Develop and test in a regular browser with mock bridge data
3. `jcef-bridge.ts` detects browser vs JCEF environment and provides mock implementations for browser testing
4. Periodically test in JCEF by running `./gradlew runIde`

### Testing Strategy

- **Unit tests:** Vitest for stores, hooks, bridge protocol, token escaping
- **Component tests:** Vitest + Testing Library for component rendering
- **Bridge contract tests:** Verify that every JS-to-Kotlin bridge function name matches the injected `window._xxx` name in `AgentCefPanel.kt`, and every Kotlin-to-JS `callJs()` function name has a corresponding implementation in `jcef-bridge.ts`. These tests parse the Kotlin source to extract function names and compare against the TypeScript bridge registry.
- **Visual testing:** Storybook (optional) for component catalog
- **Integration testing:** Manual verification in `runIde` against mock-server
- **Performance testing:** Chrome DevTools profiling in JCEF (DevTools enabled in sandbox builds)

### Bridge Contract Test Example

```typescript
// bridge.contract.test.ts
import { KOTLIN_TO_JS_FUNCTIONS, JS_TO_KOTLIN_FUNCTIONS } from './bridge-registry';

describe('Bridge contract', () => {
  test('all Kotlin-to-JS functions are registered', () => {
    // These are the function names called by AgentCefPanel.callJs()
    const expectedFunctions = [
      'startSession', 'appendUserMessage', 'appendToken', 'endStream',
      'appendToolCall', 'updateToolResult', 'appendDiff', 'appendStatus',
      'appendThinking', 'clearChat', 'showToolsPanel', 'closeToolsPanel',
      'renderPlan', 'updatePlanStep', 'showQuestions', 'showQuestion',
      'showQuestionSummary', 'enableChatInput', 'setBusy', 'setInputLocked',
      'updateTokenBudget', 'setModelName', 'updateSkillsList', 'showRetryButton',
      'focusInput', 'showSkillBanner', 'hideSkillBanner', 'appendChart',
      'appendAnsiOutput', 'showSkeleton', 'hideSkeleton', 'showToast',
      'appendTabs', 'appendTimeline', 'appendProgressBar', 'appendJiraCard',
      'appendSonarBadge', 'completeSession', 'applyTheme', 'setPrismTheme',
      'setMermaidTheme', 'receiveMentionResults',
    ];
    for (const fn of expectedFunctions) {
      expect(KOTLIN_TO_JS_FUNCTIONS).toContain(fn);
    }
  });

  test('all JS-to-Kotlin bridge functions are defined', () => {
    const expectedBridges = [
      '_requestUndo', '_requestViewTrace', '_submitPrompt',
      '_approvePlan', '_revisePlan', '_toggleTool',
      '_questionAnswered', '_questionSkipped', '_chatAboutOption',
      '_questionsSubmitted', '_questionsCancelled', '_editQuestion',
      '_deactivateSkill', '_navigateToFile', '_cancelTask', '_newChat',
      '_sendMessage', '_changeModel', '_togglePlanMode', '_activateSkill',
      '_requestFocusIde', '_openSettings', '_openToolsPanel',
      '_searchMentions', '_sendMessageWithMentions',
    ];
    for (const fn of expectedBridges) {
      expect(typeof (window as any)[fn]).toBe('function');
    }
  });
});
```

### Cutover Process

1. React app reaches full feature parity (all 26 JS-to-Kotlin + 44 Kotlin-to-JS bridges functional, all components implemented)
2. Swap `AgentCefPanel.kt` to load `index.html` from `webview/dist/`
3. Fix `GlobalScope` usage in `searchMentionsQuery` handler
4. Delete old HTML files and Prism/marked libraries
5. Delete `RichStreamingPanel.kt` and fallback logic
6. Update `agent/CLAUDE.md` to document the new architecture
7. Run full plugin test suite
8. Build and verify installable ZIP

---

## Accessibility

### Keyboard Navigation

- **Tab order:** Input bar -> action buttons -> message list. Within messages, Tab moves between interactive elements (code copy buttons, tool card expand/collapse, diff accept/reject).
- **Escape:** Closes any open overlay, dropdown, or popover. Cascading: fullscreen overlay -> mention dropdown -> tool detail panel.
- **Arrow keys:** Navigate mention autocomplete items, question wizard options, tool list items.
- **Enter:** Submit message (input bar), select item (autocomplete), activate button.
- **Shift+Enter:** New line in input bar.
- **Ctrl/Cmd+K:** Focus input bar from anywhere in the chat panel.

### ARIA Attributes

- Chat container: `role="log"`, `aria-live="polite"`, `aria-label="Agent chat messages"`
- Message cards: `role="article"`, `aria-label="[User|Agent] message"`
- Tool call cards: `role="region"`, `aria-expanded`, `aria-label="Tool: [name]"`
- Streaming indicator: `aria-busy="true"` on the active message during streaming
- Mention autocomplete: `role="listbox"` with `role="option"` children, `aria-activedescendant`
- Question wizard: `role="form"`, radio groups with `role="radiogroup"`, checkboxes with proper labels
- Overlays: `role="dialog"`, `aria-modal="true"`, focus trap while open
- Status messages (toast, error): `role="alert"`, `aria-live="assertive"`

### Focus Management

- When a new message appears, focus remains on the input bar (no focus stealing).
- When an overlay opens, focus moves to the overlay. On close, focus returns to the triggering element.
- When the question wizard appears, focus moves to the first question option.
- Screen reader announcements for: new tool call started, tool call completed, session completed, error occurred.

---

## Documentation Maintenance

As part of the cutover commit, update these documentation files:

1. **`agent/CLAUDE.md`** — Update the "Rich Chat UI" section to document:
   - React + Vite + Tailwind stack (replacing inline HTML/CSS/JS)
   - New build command: `cd agent/webview && npm run build`
   - Bridge protocol reference (point to this spec)
   - Shiki replacing Prism, react-markdown replacing marked
   - `npm run dev` for standalone development

2. **Root `CLAUDE.md`** — Add `npm run build` to the `:agent` build chain if needed.

3. **`docs/architecture/`** — Update the agent module diagram to show the `webview/` build artifact pipeline.

---

## Success Criteria

1. All existing features work identically in the React app — no regressions
2. Streaming is smooth — no jank, no full-document markdown re-parse
3. Virtual scrolling handles 1000+ messages at 60fps
4. Rich visualizations render with skeleton loading, expand/fullscreen, error fallback
5. Light and dark themes work correctly, switching live without reload
6. All 26 JS-to-Kotlin + 44 Kotlin-to-JS bridges work — same Kotlin API, zero changes to agent runtime
7. Bundle loads in <100ms from JAR (comparable to current HTML load time)
8. Visualization settings are configurable per-type in the Settings UI
9. Plugin builds (`./gradlew buildPlugin`) and all existing tests pass
10. `RichStreamingPanel.kt` deleted, zero Swing fallback code remaining
11. `GlobalScope` usage in `AgentCefPanel.kt` replaced with project-scoped coroutine
12. `agent/CLAUDE.md` updated to reflect new architecture
13. Bridge contract tests pass — all function names match between Kotlin and TypeScript
14. Keyboard navigation works for all interactive elements (input, tools, questions, overlays)
