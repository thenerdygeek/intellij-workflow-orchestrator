# Agentic Chat UI Library Migration — Design Spec

**Date:** 2026-03-23
**Supersedes:** `2026-03-22-agentic-chat-ui-overhaul-design.md` (kept for historical reference)
**Scope:** Replace all custom chat UI components with real open-source library components (prompt-kit, tool-ui, shadcn/ui). Redesign Plan UX to use IDE editor tab instead of in-chat rendering. Zero changes to Zustand stores or JCEF bridge layer.

## Context

Previous iterations of the chat UI overhaul promised prompt-kit aesthetics but delivered heavily-styled custom components that diverged from the library's actual look and feel. This spec corrects that by mandating use of **actual library components** — copied into the project per the shadcn/ui copy-paste pattern — rather than hand-built approximations.

The Plan UX is also redesigned: instead of rendering a full interactive plan card inside the chat scroll, the chat shows a small summary card with a button that opens the full plan in an IDE editor tab as read-only rendered markdown with inline comment annotations.

## Design Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | Use actual prompt-kit components (copy-paste) | Previous custom components diverged from the library aesthetic. Copy-paste ensures fidelity and maintainability. |
| 2 | Use tool-ui for agentic UI patterns | Same team as assistant-ui/prompt-kit. Provides Approval Card, Question Flow — components we'd otherwise hand-build. |
| 3 | Use shadcn/ui primitives via Radix | Command, Badge, Dialog, Select, Progress, Switch, DropdownMenu, Tooltip, Collapsible. Industry-standard accessible primitives. |
| 4 | Plan renders in IDE editor tab, not in chat | Inspired by Google Antigonus approach. Plans are documents — they belong in an editor, not a chat bubble. Enables inline comments, revision workflow, and proper reading experience. |
| 5 | Theme bridge via CSS variable aliasing | IntelliJ theme vars → shadcn token names. Zero hardcoded hex values in any component. All three libraries inherit IDE colors automatically. |
| 6 | Stores and bridge layer unchanged | Zustand stores and all 68 bridge functions (42 Kotlin→JS + 26 JS→Kotlin) stay exactly as-is. Only the rendering layer changes. |
| 7 | CSS transitions only, no motion library | All animations use CSS transitions/keyframes. No framer-motion dependency. |
| 8 | Approach A: full library install | Install all three libraries at once, replace all components in a single migration. Existing stores feed props to new components. |

---

## Architecture

### What Stays (Zero Changes)

- **Zustand stores:** `chatStore.ts`, `settingsStore.ts`, `themeStore.ts` — all state shapes, actions, and selectors unchanged
- **Bridge layer:** `jcef-bridge.ts` (42 Kotlin→JS functions), `bridge/types.ts` (all TypeScript types), all `JBCefJSQuery` handlers on Kotlin side
- **Rich visualization components:** `rich/MermaidDiagram.tsx`, `rich/ChartView.tsx`, `rich/FlowDiagram.tsx`, `rich/MathBlock.tsx`, `rich/InteractiveHtml.tsx`, `rich/DiffHtml.tsx`, `rich/AnsiOutput.tsx`, `rich/RichBlock.tsx`
- **Kotlin agent runtime:** Orchestrator, tool execution, ReAct loop, all 46 tools
- **Vite config:** Base config stays, one addition for plan-editor entry point
- **Existing npm deps:** React 18.3, Zustand 5, Tailwind v4, lucide-react, react-markdown, shiki, all viz libs

### What Gets Replaced

| Current Component | Replaced By | Source Library |
|---|---|---|
| `MessageCard.tsx` | `<Message>` | prompt-kit |
| `MessageList.tsx` | `<ChatContainer>` | prompt-kit |
| `ToolCallCard.tsx` | `<Tool>` | prompt-kit |
| `ThinkingBlock.tsx` | `<Reasoning>` | prompt-kit |
| `ChatInput.tsx` | `<PromptInput>` | prompt-kit |
| `ContextChip.tsx` | `<Badge>` | shadcn/ui |
| `MentionAutocomplete.tsx` | `<Command>` | shadcn/ui (cmdk) |
| `FullscreenOverlay.tsx` | `<Dialog>` | shadcn/ui (Radix) |
| `ApprovalGate.tsx` | `<ApprovalCard>` | tool-ui |
| `QuestionWizard.tsx` | `<QuestionFlow>` | tool-ui |
| `PlanCard.tsx` | Summary card in chat + IDE editor tab | Custom (~40 lines) + Kotlin `FileEditor` |

### What Gets Restyled (Kept, Updated)

| Component | Changes |
|---|---|
| `ActionToolbar.tsx` | Restyle with shadcn `Button` + `DropdownMenu` |
| `VisualizationSettings.tsx` | Restyle with shadcn `Switch` + `Select` |

### What's New

| Component/File | Purpose |
|---|---|
| `PlanSummaryCard.tsx` | Small card in chat with plan title, step count, "View Plan" button (~40 lines) |
| `PlanProgressWidget.tsx` | Phase progress bars shown in chat after plan approval. shadcn `Progress` + `Badge` (~60 lines) |
| `theme-bridge.css` | CSS variable aliasing: IntelliJ vars → shadcn token names |
| `plan-editor.tsx` | Lightweight second Vite entry point for plan editor tab |
| `plan-editor.html` | HTML shell for plan editor |
| `PlanEditorProvider.kt` | Kotlin `FileEditorProvider` — opens plan in editor tab |
| `PlanEditorPanel.kt` | Kotlin JCEF panel for plan editor with comment gutters, Revise/Proceed buttons |
| `showcase.tsx` + `showcase.html` | Dev-only component showcase page — renders every component with mock data, hot-reloads on change |
| `showcase/mock-data.ts` | Mock messages, tool calls, plan, questions, approval requests for showcase |
| `showcase/theme-provider.ts` | Light/dark theme toggle for dev mode testing |

### Directory Structure (Updated)

```
agent/webview/
  src/
    main.tsx                          # Existing chat app entry
    plan-editor.tsx                   # NEW — plan editor entry (lightweight)
    App.tsx                           # Root: theme provider + chat layout
    bridge/
      jcef-bridge.ts                  # UNCHANGED
      types.ts                        # UNCHANGED
      theme-controller.ts             # UNCHANGED
    components/
      ui/                             # NEW — library components
        prompt-kit/                   # Copied from prompt-kit
          message.tsx
          chat-container.tsx
          tool.tsx
          reasoning.tsx
          prompt-input.tsx
          code-block.tsx
          scroll-button.tsx
          loader.tsx
          markdown.tsx
          thinking-bar.tsx
          feedback-bar.tsx
          prompt-suggestion.tsx
        tool-ui/                      # Copied from tool-ui
          approval-card.tsx
          question-flow.tsx
        badge.tsx                     # shadcn/ui
        button.tsx                    # shadcn/ui
        command.tsx                   # shadcn/ui (cmdk)
        dialog.tsx                    # shadcn/ui (Radix)
        dropdown-menu.tsx             # shadcn/ui (Radix)
        select.tsx                    # shadcn/ui (Radix)
        switch.tsx                    # shadcn/ui (Radix)
        collapsible.tsx               # shadcn/ui (Radix)
        tooltip.tsx                   # shadcn/ui (Radix)
        progress.tsx                  # shadcn/ui (Radix)
      chat/                           # Wiring layer — connects stores to library components
        ChatView.tsx                  # Replaces MessageList (wraps ChatContainer)
        AgentMessage.tsx              # Replaces MessageCard (wraps Message)
      agent/
        ToolCallView.tsx              # Replaces ToolCallCard (wraps Tool)
        ThinkingView.tsx              # Replaces ThinkingBlock (wraps Reasoning)
        PlanSummaryCard.tsx           # NEW — small card + "View Plan" button
        PlanProgressWidget.tsx        # NEW — phase progress bars
        ApprovalView.tsx              # Replaces ApprovalGate (wraps ApprovalCard)
        QuestionView.tsx              # Replaces QuestionWizard (wraps QuestionFlow)
      input/
        InputBar.tsx                  # Replaces ChatInput (wraps PromptInput)
        MentionDropdown.tsx           # Replaces MentionAutocomplete (wraps Command)
        ActionToolbar.tsx             # KEPT, restyled
      common/
        VisualizationSettings.tsx     # KEPT, restyled
      rich/                           # ALL KEPT AS-IS
        RichBlock.tsx
        MermaidDiagram.tsx
        ChartView.tsx
        FlowDiagram.tsx
        MathBlock.tsx
        InteractiveHtml.tsx
        DiffHtml.tsx
        AnsiOutput.tsx
    hooks/                            # UNCHANGED
    stores/                           # UNCHANGED
    styles/
      theme-bridge.css                # NEW — IntelliJ → shadcn variable aliasing
      globals.css                     # Existing Tailwind base
    showcase/
      mock-data.ts                    # Mock messages, tool calls, plan, questions
      theme-provider.ts              # Light/dark theme injection for dev mode
    showcase.tsx                      # NEW — component showcase entry (dev-only)
  index.html                          # Existing
  plan-editor.html                    # NEW
  showcase.html                       # NEW (dev-only, excluded from production build)
  vite.config.ts                      # Modified — add plan-editor + dev showcase entries
  package.json
  tsconfig.json
```

---

## Theme Bridge

All component colors flow through CSS variable aliasing. Zero hardcoded hex values.

### Flow

```
AgentColors.kt (defines semantic colors)
  → AgentCefPanel.kt (injects as CSS variables: --bg, --fg, --accent, --accent-read, etc.)
    → theme-bridge.css (aliases to shadcn token names)
      → All components consume shadcn tokens
```

### theme-bridge.css

```css
:root {
  /* Map IntelliJ injected vars → shadcn/ui token names */
  --background: var(--bg);
  --foreground: var(--fg);
  --primary: var(--accent);
  --primary-foreground: var(--bg);
  --secondary: var(--surface);
  --secondary-foreground: var(--fg);
  --muted: var(--surface);
  --muted-foreground: var(--fg-secondary);
  --accent: var(--surface-hover);
  --accent-foreground: var(--fg);
  --destructive: var(--accent-cmd);
  --destructive-foreground: var(--bg);
  --border: var(--border-color);
  --input: var(--border-color);
  --ring: var(--accent);
  --card: var(--surface);
  --card-foreground: var(--fg);
  --popover: var(--surface);
  --popover-foreground: var(--fg);

  /* Semantic tokens for agent-specific components */
  --tool-read: var(--accent-read);
  --tool-write: var(--accent-write);
  --tool-edit: var(--accent-edit);
  --tool-cmd: var(--accent-cmd);
  --tool-search: var(--accent-search);
  --thinking-bg: var(--surface);
  --approval-border: var(--accent-edit);
  --plan-accent: var(--accent);

  /* Border radius — match IDE feel */
  --radius: 6px;
}
```

All prompt-kit, tool-ui, and shadcn/ui components consume these tokens natively since they're built on the same CSS variable convention.

---

## Plan UX

The plan is NOT rendered as a card inside chat. Three distinct states:

### State 1: Summary Card in Chat

When the agent generates a plan, the chat shows a small `PlanSummaryCard`:
- Plan title (from plan metadata)
- Step count: "12 steps across 4 phases"
- **"View Implementation Plan"** button (shadcn `Button`)
- Clicking the button calls a Kotlin bridge function that opens the plan in an IDE editor tab

### State 2: Plan Editor Tab (IDE)

A `FileEditorProvider` opens a JCEF panel in a standard IDE editor tab:
- Renders the plan markdown as **read-only HTML** using `marked.js`
- Each line/section has a **comment gutter** — click to add inline annotations
- Comments are stored in the agent's plan state
- Two action buttons at the top:
  - **"Revise"** — sends the plan + user comments back to the LLM for revision. Updated plan re-renders in the same tab.
  - **"Proceed"** — approves the plan, dispatches `plan_approved` event via EventBus, closes the editor tab
- Served from the same JAR resources as the chat UI, using a separate lightweight Vite entry point (`plan-editor.html`)

### State 3: Progress Widget in Chat

After the plan is approved (Proceed clicked), the `PlanSummaryCard` in chat transforms into `PlanProgressWidget`:
- Shows phase names with progress bars (shadcn `Progress`)
- Current phase highlighted with accent color
- Completed phases show checkmark badge
- Step count per phase: "3/5 complete"
- Clicking "View Plan" still opens the editor tab (now showing progress annotations)

---

## Dependencies

### New npm Packages (installed via npm)

| Package | Purpose | License | Size (gzip) |
|---|---|---|---|
| `tailwindcss-animate` | Animation utilities for shadcn | MIT | ~2KB |
| `class-variance-authority` | Variant styling (cva) | Apache 2.0 | ~2KB |
| `clsx` | Conditional classes | MIT | <1KB |
| `tailwind-merge` | Merge Tailwind classes | MIT | ~3KB |
| `@radix-ui/react-dialog` | Dialog primitive | MIT | ~5KB |
| `@radix-ui/react-collapsible` | Collapsible primitive | MIT | ~2KB |
| `@radix-ui/react-select` | Select primitive | MIT | ~8KB |
| `@radix-ui/react-switch` | Switch primitive | MIT | ~2KB |
| `@radix-ui/react-tooltip` | Tooltip primitive | MIT | ~4KB |
| `@radix-ui/react-dropdown-menu` | Dropdown primitive | MIT | ~6KB |
| `@radix-ui/react-progress` | Progress bar primitive | MIT | ~2KB |
| `cmdk` | Command palette | MIT | ~3KB |

### Copied Source (not npm packages)

| Library | Components Copied | License |
|---|---|---|
| prompt-kit | Message, ChatContainer, Tool, Reasoning, PromptInput, CodeBlock, ScrollButton, Loader, Markdown, ThinkingBar, FeedbackBar, PromptSuggestion | MIT |
| tool-ui | ApprovalCard, QuestionFlow | MIT |
| shadcn/ui | Badge, Button, Command, Dialog, DropdownMenu, Select, Switch, Collapsible, Tooltip, Progress | MIT |

### Bundle Impact

- Radix primitives: ~15-25KB gzipped (tree-shaken)
- cmdk: ~3KB gzipped
- Copied components: zero additional runtime cost (compiled into existing bundle)
- **Net increase: ~20-30KB gzipped** over current bundle

### Legal

All dependencies are MIT or Apache 2.0. No copyleft. A `THIRD_PARTY_LICENSES.md` file is added to `agent/src/main/resources/` listing all attributions per MIT/Apache requirements.

---

## Component Showcase (Dev Mode)

A standalone page that renders **every React component** on a single scrollable page with mock data. Designed for rapid visual iteration without launching IntelliJ.

### How It Works

- **Entry point:** `agent/webview/showcase.html` → `src/showcase.tsx`
- **URL:** `http://localhost:5173/showcase.html` during `npm run dev`
- **Same source components:** Imports directly from `src/components/` — not copies, not snapshots. Change a component file, Vite hot-reloads the showcase instantly.
- **Mock data via Zustand:** Uses the same store instances, pre-populated with representative mock state at showcase boot. A `src/showcase/mock-data.ts` file defines all mock messages, tool calls, plan metadata, questions, approval requests, etc.
- **Dev-only:** Excluded from production build via Vite's `build.rollupOptions.input` (only `main` and `plan-editor` are production entries). The showcase entry is only served by Vite dev server.

### Page Layout

A single scrollable page with labeled sections. Each section shows a component in multiple states:

| Section | Component | States Shown |
|---|---|---|
| Messages | `<Message>` (via `AgentMessage`) | User message, agent message, agent message with markdown, streaming message |
| Chat Container | `<ChatContainer>` (via `ChatView`) | Few messages, many messages (scroll behavior), empty state |
| Tool Calls | `<Tool>` (via `ToolCallView`) | Pending, running (with timer), completed, error — one per category (READ/WRITE/EDIT/CMD/SEARCH) |
| Thinking | `<Reasoning>` (via `ThinkingView`) | Expanded (streaming), collapsed (complete) |
| Input Bar | `<PromptInput>` (via `InputBar`) | Empty, with text, with context badges, disabled (agent busy) |
| Mention Autocomplete | `<Command>` (via `MentionDropdown`) | Open with file results, symbol results, tool results, empty results |
| Context Chips | `<Badge>` | File, folder, symbol, tool, skill types |
| Approval Gate | `<ApprovalCard>` (via `ApprovalView`) | Pending approval, approved, denied |
| Question Wizard | `<QuestionFlow>` (via `QuestionView`) | Single-select, multi-select, text input, multi-step |
| Plan Summary | `PlanSummaryCard` | Pending review, approved |
| Plan Progress | `PlanProgressWidget` | In-progress (mixed phases), all complete |
| Action Toolbar | `ActionToolbar` | Idle state, agent-running state |
| Dialog | `<Dialog>` (via fullscreen overlay) | Open with rich content |
| Feedback | `<FeedbackBar>` | Default, thumbs-up selected |
| Suggestions | `<PromptSuggestion>` | Row of 3 suggestions |
| Rich Blocks | `RichBlock` wrappers | Mermaid diagram, Chart, Math equation, Diff view, ANSI output |

### Theme Toggle

A floating toolbar at the top of the showcase page with:
- **Light / Dark toggle** — swaps CSS variables to simulate IntelliJ Light vs Darcula themes
- **Accent color picker** — override `--accent` to test different primary colors
- Theme variables are injected the same way `AgentCefPanel.kt` does it, but from a `src/showcase/theme-provider.ts` that reads from localStorage for persistence across reloads

### File Structure

```
agent/webview/
  showcase.html                       # HTML shell (dev-only)
  src/
    showcase.tsx                      # Entry point — renders all sections
    showcase/
      mock-data.ts                   # Mock messages, tool calls, plan, questions, etc.
      theme-provider.ts              # Light/dark theme variable injection for dev
      sections/                      # One file per showcase section (optional, can be inline)
```

---

## Build Pipeline

### Vite Multi-Page Config

```typescript
// vite.config.ts
export default defineConfig(({ mode }) => ({
  build: {
    rollupOptions: {
      input: {
        main: resolve(__dirname, 'index.html'),
        'plan-editor': resolve(__dirname, 'plan-editor.html'),
        // showcase is NOT included — dev-only
        ...(mode === 'development' ? { showcase: resolve(__dirname, 'showcase.html') } : {}),
      },
    },
  },
}));
```

Three pages in dev, two in production:
- **Chat app** (`main`): Full React app with all chat components (~current size + 20-30KB)
- **Plan editor** (`plan-editor`): Lightweight page with `marked.js` + minimal interaction JS (~30KB)
- **Showcase** (`showcase`): Dev-only, all components with mock data. Not bundled for production.

### Serving

Production pages served from JAR via `CefResourceSchemeHandler`. CSP `connect-src: 'none'` unchanged. Gradle build task copies `dist/` into JAR resources — no Gradle changes needed. Showcase is only accessible via `npm run dev`.

---

## Migration Strategy

Each component replacement follows the same pattern:

1. Copy library component source into `src/components/ui/`
2. Create a thin wiring component (e.g., `AgentMessage.tsx`) that:
   - Reads from the existing Zustand store
   - Maps store state to library component props
   - Passes event handlers that call existing store actions
3. Update the parent to render the new wiring component instead of the old custom component
4. Delete the old custom component

This ensures each replacement is independently testable and the app works at every intermediate step.

---

## What This Spec Does NOT Cover

- Changes to the Kotlin agent runtime, orchestrator, or tool implementations
- Changes to the JCEF bridge protocol or Zustand store shapes
- New agent capabilities or tools
- Rich visualization components (Mermaid, Chart, Math, Flow, ANSI, Diff) — these are kept as-is
- Mobile or remote IDE support
