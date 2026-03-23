# Chat UI Library Migration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace all custom chat UI components with real prompt-kit, tool-ui, and shadcn/ui library components, redesign Plan UX to use IDE editor tab, and add a dev-only component showcase page.

**Architecture:** Copy-paste library components into `src/components/ui/`, create thin wiring components that map Zustand store state to library component props, update App.tsx to render new components, delete old custom components. Zustand stores and JCEF bridge layer are unchanged.

**Tech Stack:** React 18.3, TypeScript, Tailwind CSS v4, Zustand 5, Vite 6, prompt-kit (copy), tool-ui (copy), shadcn/ui (copy), Radix UI primitives, cmdk

**Working Directory:** All paths are relative to this worktree root. The webview source is at `agent/webview/`.

**Spec:** `docs/superpowers/specs/2026-03-23-agentic-chat-ui-library-migration-design.md`

---

## Phase 1: Foundation (Tasks 1-3)

### Task 1: Install npm Dependencies

**Files:**
- Modify: `agent/webview/package.json`

- [ ] **Step 1: Install Radix primitives + utility packages**

```bash
cd agent/webview
npm install tailwindcss-animate class-variance-authority clsx tailwind-merge \
  @radix-ui/react-dialog @radix-ui/react-collapsible @radix-ui/react-select \
  @radix-ui/react-switch @radix-ui/react-tooltip @radix-ui/react-dropdown-menu \
  @radix-ui/react-progress @radix-ui/react-separator cmdk
```

- [ ] **Step 2: Verify install succeeded**

```bash
cd agent/webview && npm ls @radix-ui/react-dialog cmdk class-variance-authority
```

Expected: All packages listed without errors.

- [ ] **Step 3: Verify build still works**

```bash
cd agent/webview && npm run build
```

Expected: Build succeeds with no errors.

- [ ] **Step 4: Commit**

```bash
cd .worktrees/phase-3-agentic-ai
git add agent/webview/package.json agent/webview/package-lock.json
git commit -m "deps: add Radix UI primitives, cmdk, and shadcn utility packages"
```

---

### Task 2: Create Theme Bridge CSS + cn() Utility

**Files:**
- Create: `agent/webview/src/styles/theme-bridge.css`
- Create: `agent/webview/src/lib/utils.ts`
- Modify: `agent/webview/src/index.css`

- [ ] **Step 1: Create the cn() utility**

Create `agent/webview/src/lib/utils.ts`:

```typescript
import { type ClassValue, clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
```

- [ ] **Step 2: Create theme-bridge.css**

Create `agent/webview/src/styles/theme-bridge.css`:

```css
/*
 * Theme Bridge — Maps IntelliJ-injected CSS variables to shadcn/ui token names.
 * AgentCefPanel.kt injects --bg, --fg, --accent, --border, etc. as inline styles.
 * This file creates NEW shadcn/ui tokens that reference the IntelliJ vars.
 *
 * COLLISION NOTE: IntelliJ injects --accent (primary blue) and --border (border color).
 * shadcn/ui also uses --accent (hover state) and --border. Since IntelliJ injects via
 * inline styles (higher specificity), IntelliJ values WIN for colliding names.
 * This is fine: --accent stays as the primary accent, --border stays as the border color.
 * For shadcn hover states, components use Tailwind hover:bg utilities directly.
 *
 * IMPORTANT: Zero hardcoded hex values. Everything flows from IntelliJ themes.
 */

:root {
  /* === Core shadcn/ui tokens (NEW names, no collisions) === */
  --background: var(--bg);
  --foreground: var(--fg);
  --primary: var(--accent);
  --primary-foreground: var(--bg);
  --secondary: var(--tool-bg);
  --secondary-foreground: var(--fg);
  --muted: var(--tool-bg);
  --muted-foreground: var(--fg-secondary);
  /* NOTE: Don't redefine --accent or --border — IntelliJ injects these with inline
     styles. shadcn components using bg-accent get the primary accent color, which is
     acceptable for an IDE plugin. For hover states, use hover:bg-[var(--hover-overlay)]. */
  --accent-foreground: var(--fg);
  --destructive: var(--accent-cmd);
  --destructive-foreground: var(--bg);
  --input: var(--input-border);
  --ring: var(--accent);
  --card: var(--tool-bg);
  --card-foreground: var(--fg);
  --popover: var(--tool-bg);
  --popover-foreground: var(--fg);

  /* === Agent-specific semantic tokens === */
  --tool-read: var(--accent-read);
  --tool-write: var(--accent-write);
  --tool-edit: var(--accent-edit);
  --tool-cmd: var(--accent-cmd);
  --tool-search: var(--accent-search);
  --approval-border: var(--accent-edit);

  /* === Border radius — match IDE feel === */
  --radius: 0.375rem;
}
```

- [ ] **Step 3: Import theme-bridge.css and tailwindcss-animate in index.css**

Add at the top of `agent/webview/src/index.css` (before any other imports):

```css
@import 'tailwindcss-animate';
@import './styles/theme-bridge.css';
```

The `tailwindcss-animate` import enables animation classes used by shadcn Dialog enter/exit transitions and other animated components.

- [ ] **Step 4: Add path alias for lib/**

Verify `vite.config.ts` already has `'@': path.resolve(__dirname, 'src')`. The `cn()` function will be imported as `@/lib/utils`.

- [ ] **Step 5: Verify build**

```bash
cd agent/webview && npm run build
```

Expected: Build succeeds.

- [ ] **Step 6: Commit**

```bash
cd .worktrees/phase-3-agentic-ai
git add agent/webview/src/styles/theme-bridge.css agent/webview/src/lib/utils.ts agent/webview/src/index.css
git commit -m "feat: add theme bridge CSS variable aliasing and cn() utility"
```

---

### Task 3: Copy shadcn/ui Primitive Components

**Files:**
- Create: `agent/webview/src/components/ui/button.tsx`
- Create: `agent/webview/src/components/ui/badge.tsx`
- Create: `agent/webview/src/components/ui/dialog.tsx`
- Create: `agent/webview/src/components/ui/command.tsx`
- Create: `agent/webview/src/components/ui/dropdown-menu.tsx`
- Create: `agent/webview/src/components/ui/select.tsx`
- Create: `agent/webview/src/components/ui/switch.tsx`
- Create: `agent/webview/src/components/ui/collapsible.tsx`
- Create: `agent/webview/src/components/ui/tooltip.tsx`
- Create: `agent/webview/src/components/ui/progress.tsx`
- Create: `agent/webview/src/components/ui/separator.tsx`

- [ ] **Step 1: Fetch and copy each shadcn/ui component**

For each component, fetch the source from the shadcn/ui GitHub repository (`shadcn-ui/ui`, branch `main`, path `packages/shadcn/src/registry/default/ui/`). Copy each file into `agent/webview/src/components/ui/`.

Every component follows the same pattern — it imports `cn` from `@/lib/utils` and wraps Radix primitives with Tailwind classes using the CSS variable tokens (e.g., `bg-background`, `text-foreground`, `border-border`).

Fetch from: `https://raw.githubusercontent.com/shadcn-ui/ui/main/packages/shadcn/src/registry/default/ui/{component}.tsx`

Components to copy: `button`, `badge`, `dialog`, `command`, `dropdown-menu`, `select`, `switch`, `collapsible`, `tooltip`, `progress`, `separator`.

After copying, verify each file imports `cn` from `"@/lib/utils"` (should already be correct).

- [ ] **Step 2: Verify TypeScript compilation**

```bash
cd agent/webview && npx tsc --noEmit
```

Fix any import path issues. Common fixes:
- Ensure `@/lib/utils` resolves (set up in Task 2)
- If any component imports from `@/components/ui/button`, ensure button.tsx exists

- [ ] **Step 3: Verify build**

```bash
cd agent/webview && npm run build
```

Expected: Build succeeds. Tree-shaking removes unused components from the bundle.

- [ ] **Step 4: Commit**

```bash
cd .worktrees/phase-3-agentic-ai
git add agent/webview/src/components/ui/
git commit -m "feat: copy shadcn/ui primitive components (button, badge, dialog, command, etc.)"
```

---

## Phase 2: Copy Library Components (Tasks 4-5)

### Task 4: Copy prompt-kit Components

**Files:**
- Create: `agent/webview/src/components/ui/prompt-kit/message.tsx`
- Create: `agent/webview/src/components/ui/prompt-kit/chat-container.tsx`
- Create: `agent/webview/src/components/ui/prompt-kit/prompt-input.tsx`
- Create: `agent/webview/src/components/ui/prompt-kit/tool.tsx`
- Create: `agent/webview/src/components/ui/prompt-kit/reasoning.tsx`
- Create: `agent/webview/src/components/ui/prompt-kit/code-block.tsx`
- Create: `agent/webview/src/components/ui/prompt-kit/scroll-button.tsx`
- Create: `agent/webview/src/components/ui/prompt-kit/loader.tsx`
- Create: `agent/webview/src/components/ui/prompt-kit/markdown.tsx`
- Create: `agent/webview/src/components/ui/prompt-kit/thinking-bar.tsx`
- Create: `agent/webview/src/components/ui/prompt-kit/feedback-bar.tsx`
- Create: `agent/webview/src/components/ui/prompt-kit/prompt-suggestion.tsx`

- [ ] **Step 1: Fetch prompt-kit component source**

Fetch from the prompt-kit GitHub repository: `https://github.com/prompt-kit/prompt-kit` (or `assistant-ui/prompt-kit`). The components are in the `components/prompt-kit/` directory.

Each prompt-kit component is a single `.tsx` file that follows the shadcn compound component pattern (e.g., `MessageContainer`, `MessageContent`, `MessageAvatar` exported from `message.tsx`).

Copy each file into `agent/webview/src/components/ui/prompt-kit/`.

- [ ] **Step 2: Update imports in copied files**

Each prompt-kit component imports `cn` from a relative utils path. Update all imports to:

```typescript
import { cn } from '@/lib/utils';
```

If any component imports other shadcn primitives (e.g., `Button`, `Collapsible`), update to:

```typescript
import { Button } from '@/components/ui/button';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
```

- [ ] **Step 3: Handle peer dependencies**

prompt-kit's `ChatContainer` may use `use-stick-to-bottom` for auto-scrolling. If so, either:
- Install it: `npm install use-stick-to-bottom`
- OR replace with the existing `useAutoScroll` hook from `src/hooks/useAutoScroll.ts`

Check the component source and decide based on which provides better behavior.

prompt-kit's `CodeBlock` may use a different syntax highlighter. Our project uses Shiki via `src/hooks/useShiki.ts`. Adapt the CodeBlock to use the existing Shiki setup rather than adding a new highlighter dependency.

prompt-kit's `Markdown` component may use `react-markdown`. Our project already has `react-markdown` + `remark-gfm` + `rehype-raw` installed. Ensure the component uses these existing deps.

- [ ] **Step 4: Verify TypeScript compilation**

```bash
cd agent/webview && npx tsc --noEmit
```

- [ ] **Step 5: Verify build**

```bash
cd agent/webview && npm run build
```

- [ ] **Step 6: Commit**

```bash
cd .worktrees/phase-3-agentic-ai
git add agent/webview/src/components/ui/prompt-kit/
git commit -m "feat: copy prompt-kit components (message, chat-container, tool, reasoning, etc.)"
```

---

### Task 5: Copy tool-ui Components

**Files:**
- Create: `agent/webview/src/components/ui/tool-ui/approval-card.tsx`
- Create: `agent/webview/src/components/ui/tool-ui/question-flow.tsx`
- Create: `agent/webview/src/components/ui/tool-ui/shared/action-buttons.tsx`
- Create: `agent/webview/src/components/ui/tool-ui/shared/schema.ts`

- [ ] **Step 1: Fetch tool-ui component source**

Fetch from: `https://github.com/assistant-ui/tool-ui`

Components are in `apps/www/components/tool-ui/`. Copy these directories:
- `approval-card/` → contents into `src/components/ui/tool-ui/approval-card.tsx`
- `question-flow/` → contents into `src/components/ui/tool-ui/question-flow.tsx`
- `shared/` → shared utilities into `src/components/ui/tool-ui/shared/`

Each component has an `_adapter.tsx` that re-exports shadcn primitives. Replace adapter imports to point to our local shadcn:

```typescript
// Replace _adapter imports like:
// import { Button } from '../_adapter'
// With:
import { Button } from '@/components/ui/button';
import { Separator } from '@/components/ui/separator';
import { cn } from '@/lib/utils';
```

- [ ] **Step 2: Handle zod dependency**

tool-ui uses Zod 4 for schema validation. The schema validation is only used for parse/safeParse of JSON payloads from the LLM. Since our components receive typed props from Zustand (already validated), we can either:
- Option A: Install zod: `npm install zod` (~12KB gzipped)
- Option B: Remove the schema validation files and use components directly with TypeScript types only

**Recommended: Option B** — remove schema files, keep only the component TSX files. Our data comes from typed Kotlin bridge, not raw JSON.

- [ ] **Step 3: Adapt ApprovalCard**

The tool-ui `ApprovalCard` has this API:

```typescript
interface ApprovalCardProps {
  id: string;
  title: string;
  description?: string;
  icon?: string;               // Lucide icon name as string
  metadata?: { key: string; value: string }[];
  variant?: 'default' | 'destructive';
  confirmLabel?: string;       // Default: "Approve"
  cancelLabel?: string;        // Default: "Deny"
  choice?: 'approved' | 'denied';  // Receipt mode when set
  onConfirm?: () => void | Promise<void>;
  onCancel?: () => void | Promise<void>;
}
```

Our existing `ApprovalGate.tsx` passes: `title`, `description`, `commandPreview`, `onApprove`, `onDeny`.

The wiring component (Task 9) will map these props.

- [ ] **Step 4: Adapt QuestionFlow**

The tool-ui `QuestionFlow` has a discriminated union API:

```typescript
// Progressive mode (our use case — agent controls step-by-step)
interface QuestionFlowProgressiveProps {
  id: string;
  step: number;
  title: string;
  description?: string;
  options: { id: string; label: string; description?: string; disabled?: boolean }[];
  selectionMode?: 'single' | 'multi';
  onSelect?: (optionIds: string[]) => void | Promise<void>;
  onBack?: () => void;
}

// Receipt mode (after completion)
interface QuestionFlowReceiptProps {
  id: string;
  choice: {
    title: string;
    summary: { label: string; value: string }[];
  };
}
```

Our existing `QuestionWizard.tsx` uses `Question[]` with `activeIndex`. The wiring component (Task 10) will map these.

- [ ] **Step 5: Verify TypeScript compilation and build**

```bash
cd agent/webview && npx tsc --noEmit && npm run build
```

- [ ] **Step 6: Commit**

```bash
cd .worktrees/phase-3-agentic-ai
git add agent/webview/src/components/ui/tool-ui/
git commit -m "feat: copy tool-ui components (approval-card, question-flow)"
```

---

## Phase 3: Wire Chat Components (Tasks 6-8)

### Task 6: Wire AgentMessage (replaces MessageCard)

**Files:**
- Create: `agent/webview/src/components/chat/AgentMessage.tsx`
- Reference: `agent/webview/src/components/chat/MessageCard.tsx` (to be deleted in Task 16)

- [ ] **Step 1: Create AgentMessage wiring component**

Create `agent/webview/src/components/chat/AgentMessage.tsx`:

```typescript
import { memo } from 'react';
import type { Message } from '@/bridge/types';
// Import prompt-kit Message compound components
// Exact sub-component names depend on the copied source — check message.tsx
// Typical pattern: MessageContainer, MessageContent, MessageAvatar, MessageActions
import {
  Message as PkMessage,
  MessageContent,
  MessageAvatar,
} from '@/components/ui/prompt-kit/message';
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer';

interface AgentMessageProps {
  message: Message;
  isStreaming?: boolean;
  streamText?: string;
}

export const AgentMessage = memo(function AgentMessage({
  message,
  isStreaming,
  streamText,
}: AgentMessageProps) {
  const isUser = message.role === 'user';
  const content = isStreaming && streamText ? streamText : message.content;

  return (
    <PkMessage className={isUser ? 'justify-end' : 'justify-start'}>
      {!isUser && (
        <MessageAvatar
          className="flex h-7 w-7 items-center justify-center rounded-md text-xs font-semibold"
          style={{ backgroundColor: 'var(--primary)', color: 'var(--primary-foreground)' }}
        >
          A
        </MessageAvatar>
      )}
      <MessageContent
        className={isUser ? 'bg-[var(--user-bg)]' : ''}
      >
        <MarkdownRenderer content={content} />
        {isStreaming && (
          <span className="inline-block h-4 w-0.5 animate-[cursor-blink_530ms_steps(1)_infinite] bg-[var(--fg)]" />
        )}
      </MessageContent>
    </PkMessage>
  );
});
```

**Note:** The exact prompt-kit sub-component names (MessageContainer vs Message, etc.) must match what's in the copied `message.tsx`. Adjust imports after inspecting the actual source in Task 4.

- [ ] **Step 2: Verify TypeScript compilation**

```bash
cd agent/webview && npx tsc --noEmit
```

- [ ] **Step 3: Commit**

```bash
cd .worktrees/phase-3-agentic-ai
git add agent/webview/src/components/chat/AgentMessage.tsx
git commit -m "feat: wire AgentMessage component (wraps prompt-kit Message)"
```

---

### Task 7: Wire ToolCallView (replaces ToolCallCard)

**Files:**
- Create: `agent/webview/src/components/agent/ToolCallView.tsx`

- [ ] **Step 1: Create ToolCallView wiring component**

Create `agent/webview/src/components/agent/ToolCallView.tsx`:

```typescript
import { memo, useState, useEffect, useRef } from 'react';
import type { ToolCall } from '@/bridge/types';
// Import prompt-kit Tool compound components — adjust names per actual source
import {
  Tool as PkTool,
  ToolContent,
} from '@/components/ui/prompt-kit/tool';
import { Badge } from '@/components/ui/badge';

// Map tool names to categories
const categoryMap: Record<string, { label: string; colorVar: string }> = {
  read_file: { label: 'READ', colorVar: '--tool-read' },
  glob_files: { label: 'READ', colorVar: '--tool-read' },
  search_code: { label: 'SEARCH', colorVar: '--tool-search' },
  find_definition: { label: 'SEARCH', colorVar: '--tool-search' },
  edit_file: { label: 'EDIT', colorVar: '--tool-edit' },
  format_code: { label: 'EDIT', colorVar: '--tool-edit' },
  run_command: { label: 'CMD', colorVar: '--tool-cmd' },
  run_tests: { label: 'CMD', colorVar: '--tool-cmd' },
  // Add remaining mappings as needed — the existing ToolCallCard.tsx has the full map
};

function getCategory(name: string) {
  return categoryMap[name] ?? { label: 'TOOL', colorVar: '--primary' };
}

interface ToolCallViewProps {
  toolCall: ToolCall;
  isLatest: boolean;
}

export const ToolCallView = memo(function ToolCallView({ toolCall, isLatest }: ToolCallViewProps) {
  const category = getCategory(toolCall.name);
  const isRunning = toolCall.status === 'RUNNING';
  const isError = toolCall.status === 'ERROR';
  const isComplete = toolCall.status === 'COMPLETED';

  // Live timer for running tools
  const [elapsed, setElapsed] = useState(0);
  const startRef = useRef(Date.now());

  useEffect(() => {
    if (!isRunning) return;
    startRef.current = Date.now();
    const interval = setInterval(() => setElapsed(Date.now() - startRef.current), 100);
    return () => clearInterval(interval);
  }, [isRunning]);

  const duration = isRunning
    ? `${(elapsed / 1000).toFixed(1)}s`
    : toolCall.durationMs
      ? `${(toolCall.durationMs / 1000).toFixed(1)}s`
      : undefined;

  // Auto-expand latest running tool, collapse others
  const [expanded, setExpanded] = useState(isLatest && isRunning);

  useEffect(() => {
    if (isLatest && isRunning) setExpanded(true);
    if (!isLatest && isComplete) setExpanded(false);
  }, [isLatest, isRunning, isComplete]);

  return (
    <PkTool
      name={toolCall.name}
      status={isRunning ? 'running' : isComplete ? 'completed' : isError ? 'error' : 'pending'}
      expanded={expanded}
      onToggle={() => setExpanded(e => !e)}
      className="my-1"
    >
      <div className="flex items-center gap-2">
        <Badge
          variant="outline"
          style={{ color: `var(${category.colorVar})`, borderColor: `var(${category.colorVar})` }}
          className="text-[10px] px-1.5 py-0"
        >
          {category.label}
        </Badge>
        <span className="text-xs text-[var(--fg-secondary)]">{toolCall.name}</span>
        {duration && (
          <span className="text-[10px] text-[var(--fg-muted)] ml-auto">{duration}</span>
        )}
      </div>
      {expanded && (
        <ToolContent>
          {toolCall.args && (
            <details className="mt-2">
              <summary className="text-[11px] text-[var(--fg-secondary)] cursor-pointer">Input</summary>
              <pre className="mt-1 text-[11px] bg-[var(--code-bg)] p-2 rounded overflow-x-auto">
                {typeof toolCall.args === 'string' ? toolCall.args : JSON.stringify(toolCall.args, null, 2)}
              </pre>
            </details>
          )}
          {toolCall.result && (
            <details className="mt-2" open>
              <summary className="text-[11px] text-[var(--fg-secondary)] cursor-pointer">Output</summary>
              <pre className="mt-1 text-[11px] bg-[var(--code-bg)] p-2 rounded overflow-x-auto max-h-60 overflow-y-auto">
                {toolCall.result}
              </pre>
            </details>
          )}
        </ToolContent>
      )}
    </PkTool>
  );
});
```

**Note:** The prompt-kit `Tool` component's exact API (props names, sub-components) must be verified against the copied source from Task 4. Adjust the JSX accordingly. The key logic (category badges, live timer, auto-expand/collapse) is preserved from the existing ToolCallCard.

- [ ] **Step 2: Verify TypeScript compilation**

```bash
cd agent/webview && npx tsc --noEmit
```

- [ ] **Step 3: Commit**

```bash
cd .worktrees/phase-3-agentic-ai
git add agent/webview/src/components/agent/ToolCallView.tsx
git commit -m "feat: wire ToolCallView component (wraps prompt-kit Tool)"
```

---

### Task 8: Wire ThinkingView (replaces ThinkingBlock)

**Files:**
- Create: `agent/webview/src/components/agent/ThinkingView.tsx`

- [ ] **Step 1: Create ThinkingView wiring component**

Create `agent/webview/src/components/agent/ThinkingView.tsx`:

```typescript
import { memo, useState, useEffect, useRef } from 'react';
// Import prompt-kit Reasoning — adjust names per actual source
import {
  Reasoning,
  ReasoningContent,
} from '@/components/ui/prompt-kit/reasoning';

interface ThinkingViewProps {
  content: string;
  isStreaming: boolean;
}

export const ThinkingView = memo(function ThinkingView({ content, isStreaming }: ThinkingViewProps) {
  const [collapsed, setCollapsed] = useState(false);
  const hasAutoCollapsed = useRef(false);
  const wasStreaming = useRef(isStreaming);

  // Auto-collapse 600ms after streaming ends
  useEffect(() => {
    if (wasStreaming.current && !isStreaming && !hasAutoCollapsed.current) {
      hasAutoCollapsed.current = true;
      const timer = setTimeout(() => setCollapsed(true), 600);
      return () => clearTimeout(timer);
    }
    wasStreaming.current = isStreaming;
  }, [isStreaming]);

  return (
    <Reasoning
      isOpen={!collapsed}
      onToggle={() => setCollapsed(c => !c)}
      className="my-2"
    >
      <ReasoningContent>
        <div className="text-[12px] text-[var(--fg-secondary)] whitespace-pre-wrap">
          {content}
          {isStreaming && (
            <span className="inline-block h-3 w-0.5 ml-0.5 animate-[cursor-blink_530ms_steps(1)_infinite] bg-[var(--fg-secondary)]" />
          )}
        </div>
      </ReasoningContent>
    </Reasoning>
  );
});
```

**Note:** Adjust prompt-kit Reasoning sub-component names per actual copied source. The auto-collapse-after-streaming behavior is preserved from the existing ThinkingBlock.

- [ ] **Step 2: Verify TypeScript compilation**

```bash
cd agent/webview && npx tsc --noEmit
```

- [ ] **Step 3: Commit**

```bash
cd .worktrees/phase-3-agentic-ai
git add agent/webview/src/components/agent/ThinkingView.tsx
git commit -m "feat: wire ThinkingView component (wraps prompt-kit Reasoning)"
```

---

## Phase 4: Wire Agent Components (Tasks 9-11)

### Task 9: Wire ApprovalView (replaces ApprovalGate)

**Files:**
- Create: `agent/webview/src/components/agent/ApprovalView.tsx`

- [ ] **Step 1: Create ApprovalView wiring component**

Create `agent/webview/src/components/agent/ApprovalView.tsx`:

```typescript
import { memo } from 'react';
import { ApprovalCard } from '@/components/ui/tool-ui/approval-card';

interface ApprovalViewProps {
  title: string;
  description?: string;
  commandPreview?: string;
  onApprove: () => void;
  onDeny: () => void;
}

export const ApprovalView = memo(function ApprovalView({
  title,
  description,
  commandPreview,
  onApprove,
  onDeny,
}: ApprovalViewProps) {
  return (
    <ApprovalCard
      id={`approval-${Date.now()}`}
      title={title}
      description={description || commandPreview}
      icon="shield-alert"
      variant="destructive"
      metadata={commandPreview ? [{ key: 'Command', value: commandPreview }] : undefined}
      confirmLabel="Approve"
      cancelLabel="Deny"
      onConfirm={onApprove}
      onCancel={onDeny}
    />
  );
});
```

- [ ] **Step 2: Verify TypeScript compilation**

```bash
cd agent/webview && npx tsc --noEmit
```

- [ ] **Step 3: Commit**

```bash
cd .worktrees/phase-3-agentic-ai
git add agent/webview/src/components/agent/ApprovalView.tsx
git commit -m "feat: wire ApprovalView component (wraps tool-ui ApprovalCard)"
```

---

### Task 10: Wire QuestionView (replaces QuestionWizard)

**Files:**
- Create: `agent/webview/src/components/agent/QuestionView.tsx`

- [ ] **Step 1: Create QuestionView wiring component**

Create `agent/webview/src/components/agent/QuestionView.tsx`:

```typescript
import { memo, useCallback } from 'react';
import type { Question } from '@/bridge/types';
import { QuestionFlow } from '@/components/ui/tool-ui/question-flow';

interface QuestionViewProps {
  questions: Question[];
  activeIndex: number;
}

export const QuestionView = memo(function QuestionView({
  questions,
  activeIndex,
}: QuestionViewProps) {
  const currentQuestion = questions[activeIndex];
  if (!currentQuestion) return null;

  // Map our Question type → tool-ui QuestionFlow progressive props
  const options = (currentQuestion.options ?? []).map(opt => ({
    id: opt.label, // use label as ID since our type doesn't have separate IDs
    label: opt.label,
    description: opt.description,
  }));

  const handleSelect = useCallback((optionIds: string[]) => {
    const qid = currentQuestion.id;
    const json = JSON.stringify(optionIds);
    window._questionAnswered?.(qid, json);
  }, [currentQuestion.id]);

  const handleBack = useCallback(() => {
    // Navigate to previous question — handled by Kotlin bridge via edit
    if (activeIndex > 0) {
      window._editQuestion?.(questions[activeIndex - 1].id);
    }
  }, [activeIndex, questions]);

  return (
    <div className="my-3">
      <QuestionFlow
        id={`question-${currentQuestion.id}`}
        step={activeIndex + 1}
        title={currentQuestion.text}
        options={options}
        selectionMode={currentQuestion.type === 'multi-select' ? 'multi' : 'single'}
        onSelect={handleSelect}
        onBack={activeIndex > 0 ? handleBack : undefined}
      />
      {/* Step indicator */}
      <div className="mt-2 flex justify-center gap-1">
        {questions.map((q, i) => (
          <div
            key={q.id}
            className="h-1.5 w-1.5 rounded-full transition-colors"
            style={{
              backgroundColor: i < activeIndex
                ? 'var(--success)'
                : i === activeIndex
                  ? 'var(--primary)'
                  : 'var(--fg-muted)',
            }}
          />
        ))}
      </div>
    </div>
  );
});
```

- [ ] **Step 2: Verify TypeScript compilation**

```bash
cd agent/webview && npx tsc --noEmit
```

- [ ] **Step 3: Commit**

```bash
cd .worktrees/phase-3-agentic-ai
git add agent/webview/src/components/agent/QuestionView.tsx
git commit -m "feat: wire QuestionView component (wraps tool-ui QuestionFlow)"
```

---

### Task 11: Create PlanSummaryCard + PlanProgressWidget

**Files:**
- Create: `agent/webview/src/components/agent/PlanSummaryCard.tsx`
- Create: `agent/webview/src/components/agent/PlanProgressWidget.tsx`

- [ ] **Step 1: Create PlanSummaryCard**

Create `agent/webview/src/components/agent/PlanSummaryCard.tsx`:

```typescript
import { memo } from 'react';
import type { Plan } from '@/bridge/types';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { FileText } from 'lucide-react';

interface PlanSummaryCardProps {
  plan: Plan;
}

export const PlanSummaryCard = memo(function PlanSummaryCard({ plan }: PlanSummaryCardProps) {
  const totalSteps = plan.steps.length;
  const completedSteps = plan.steps.filter(s => s.status === 'completed').length;

  // If plan is approved and execution has started, show progress widget instead
  if (plan.approved) return null; // PlanProgressWidget handles this state

  return (
    <div
      className="my-3 rounded-lg border p-4"
      style={{ borderColor: 'var(--border)', backgroundColor: 'var(--card)' }}
    >
      <div className="flex items-start gap-3">
        <div
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md"
          style={{ backgroundColor: 'var(--primary)', color: 'var(--primary-foreground)' }}
        >
          <FileText className="h-4 w-4" />
        </div>
        <div className="flex-1 min-w-0">
          <h3 className="text-sm font-medium" style={{ color: 'var(--fg)' }}>
            {plan.title || 'Implementation Plan'}
          </h3>
          <p className="mt-0.5 text-xs" style={{ color: 'var(--fg-secondary)' }}>
            {totalSteps} steps
            {completedSteps > 0 && ` · ${completedSteps} completed`}
          </p>
        </div>
        <Badge variant="outline" className="text-[10px]">
          Review
        </Badge>
      </div>
      <Button
        className="mt-3 w-full"
        variant="outline"
        size="sm"
        onClick={() => window._approvePlan?.()}
      >
        View Implementation Plan
      </Button>
    </div>
  );
});
```

**Note:** The "View Implementation Plan" button currently calls `_approvePlan` as a placeholder. In Task 14, the Kotlin-side plan editor tab will be updated, and this button will instead call a new bridge function `_openPlanEditor()` that opens the plan in an editor tab.

- [ ] **Step 2: Create PlanProgressWidget**

Create `agent/webview/src/components/agent/PlanProgressWidget.tsx`:

```typescript
import { memo } from 'react';
import type { Plan, PlanStep } from '@/bridge/types';
import { Progress } from '@/components/ui/progress';
import { Badge } from '@/components/ui/badge';
import { Check } from 'lucide-react';

interface PlanProgressWidgetProps {
  plan: Plan;
}

// Group steps into phases by splitting on phase-like titles or evenly
function groupIntoPhases(steps: PlanStep[]): { name: string; steps: PlanStep[] }[] {
  // Simple heuristic: group consecutive steps, split when title starts with "Phase"
  const phases: { name: string; steps: PlanStep[] }[] = [];
  let current: PlanStep[] = [];
  let phaseName = 'Phase 1';
  let phaseNum = 1;

  for (const step of steps) {
    const phaseMatch = step.title.match(/^Phase\s+(\d+)[:\s]*(.*)/i);
    if (phaseMatch && current.length > 0) {
      phases.push({ name: phaseName, steps: current });
      current = [];
      phaseNum = parseInt(phaseMatch[1]);
      phaseName = phaseMatch[2] || `Phase ${phaseNum}`;
    }
    if (phaseMatch) {
      phaseName = phaseMatch[2] || `Phase ${phaseNum}`;
    }
    current.push(step);
  }
  if (current.length > 0) {
    phases.push({ name: phaseName, steps: current });
  }

  // If no phases detected, create one
  if (phases.length === 0) {
    return [{ name: 'Execution', steps }];
  }

  return phases;
}

export const PlanProgressWidget = memo(function PlanProgressWidget({ plan }: PlanProgressWidgetProps) {
  if (!plan.approved) return null;

  const phases = groupIntoPhases(plan.steps);

  return (
    <div
      className="my-3 rounded-lg border p-4 space-y-3"
      style={{ borderColor: 'var(--border)', backgroundColor: 'var(--card)' }}
    >
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-medium" style={{ color: 'var(--fg)' }}>
          {plan.title || 'Implementation Plan'}
        </h3>
        <Badge variant="outline" className="text-[10px]" style={{ color: 'var(--success)' }}>
          In Progress
        </Badge>
      </div>

      {phases.map((phase, idx) => {
        const completed = phase.steps.filter(s => s.status === 'completed').length;
        const total = phase.steps.length;
        const pct = total > 0 ? Math.round((completed / total) * 100) : 0;
        const isAllDone = completed === total;
        const isCurrent = !isAllDone && phase.steps.some(s => s.status === 'running' || s.status === 'pending');

        return (
          <div key={idx} className="space-y-1">
            <div className="flex items-center justify-between text-xs">
              <span
                className="font-medium"
                style={{ color: isCurrent ? 'var(--primary)' : isAllDone ? 'var(--success)' : 'var(--fg-secondary)' }}
              >
                {isAllDone && <Check className="inline h-3 w-3 mr-1" />}
                {phase.name}
              </span>
              <span style={{ color: 'var(--fg-muted)' }}>{completed}/{total}</span>
            </div>
            <Progress
              value={pct}
              className="h-1.5"
            />
          </div>
        );
      })}
    </div>
  );
});
```

- [ ] **Step 3: Verify TypeScript compilation**

```bash
cd agent/webview && npx tsc --noEmit
```

- [ ] **Step 4: Commit**

```bash
cd .worktrees/phase-3-agentic-ai
git add agent/webview/src/components/agent/PlanSummaryCard.tsx agent/webview/src/components/agent/PlanProgressWidget.tsx
git commit -m "feat: add PlanSummaryCard and PlanProgressWidget for plan UX"
```

---

## Phase 5: Wire Input Components (Tasks 12-13)

### Task 12: Wire MentionDropdown (replaces MentionAutocomplete)

**Files:**
- Create: `agent/webview/src/components/input/MentionDropdown.tsx`

- [ ] **Step 1: Create MentionDropdown wiring component**

Create `agent/webview/src/components/input/MentionDropdown.tsx`:

```typescript
import { memo, useEffect, useCallback } from 'react';
import type { MentionSearchResult } from '@/bridge/types';
import { useChatStore } from '@/stores/chatStore';
import {
  Command,
  CommandInput,
  CommandList,
  CommandEmpty,
  CommandGroup,
  CommandItem,
} from '@/components/ui/command';

const typeIcons: Record<string, string> = {
  file: '📄',
  folder: '📁',
  symbol: '#',
  tool: '🔧',
  skill: '✨',
};

const typeLabels: Record<string, string> = {
  file: 'Files',
  folder: 'Folders',
  symbol: 'Symbols',
  tool: 'Tools',
  skill: 'Skills',
};

interface MentionDropdownProps {
  query: string;
  onSelect: (result: MentionSearchResult) => void;
  onDismiss: () => void;
}

export const MentionDropdown = memo(function MentionDropdown({
  query,
  onSelect,
  onDismiss,
}: MentionDropdownProps) {
  const mentionResults = useChatStore(s => s.mentionResults);

  // Request search results from Kotlin
  useEffect(() => {
    if (query) {
      window._searchMentions?.(query);
    } else {
      window._searchMentions?.('categories:');
    }
  }, [query]);

  // Group results by type
  const groups = mentionResults.reduce<Record<string, MentionSearchResult[]>>((acc, r) => {
    (acc[r.type] ??= []).push(r);
    return acc;
  }, {});

  const handleSelect = useCallback((value: string) => {
    const result = mentionResults.find(r => r.label === value || r.path === value);
    if (result) onSelect(result);
  }, [mentionResults, onSelect]);

  return (
    <div className="absolute bottom-full left-0 mb-1 w-72 z-50">
      <Command
        className="rounded-lg border shadow-lg"
        style={{ backgroundColor: 'var(--popover)', borderColor: 'var(--border)' }}
      >
        <CommandInput
          placeholder="Search files, symbols, tools..."
          value={query}
          className="text-xs"
        />
        <CommandList className="max-h-60">
          <CommandEmpty className="text-xs py-4 text-center" style={{ color: 'var(--fg-muted)' }}>
            No results found.
          </CommandEmpty>
          {Object.entries(groups).map(([type, results]) => (
            <CommandGroup key={type} heading={typeLabels[type] ?? type}>
              {results.map((r) => (
                <CommandItem
                  key={r.path ?? r.label}
                  value={r.path ?? r.label}
                  onSelect={handleSelect}
                  className="text-xs gap-2"
                >
                  <span className="text-[10px] opacity-60">{r.icon ?? typeIcons[r.type] ?? '@'}</span>
                  <span className="truncate">{r.label}</span>
                  {r.description && (
                    <span className="ml-auto text-[10px] truncate max-w-[120px]" style={{ color: 'var(--fg-muted)' }}>
                      {r.description}
                    </span>
                  )}
                </CommandItem>
              ))}
            </CommandGroup>
          ))}
        </CommandList>
      </Command>
    </div>
  );
});
```

- [ ] **Step 2: Verify TypeScript compilation**

```bash
cd agent/webview && npx tsc --noEmit
```

- [ ] **Step 3: Commit**

```bash
cd .worktrees/phase-3-agentic-ai
git add agent/webview/src/components/input/MentionDropdown.tsx
git commit -m "feat: wire MentionDropdown component (wraps shadcn Command)"
```

---

### Task 13: Wire InputBar (replaces ChatInput)

**Files:**
- Create: `agent/webview/src/components/input/InputBar.tsx`

- [ ] **Step 1: Create InputBar wiring component**

Create `agent/webview/src/components/input/InputBar.tsx`. This is the most complex wiring component because ChatInput.tsx (373 lines) has significant logic: model/plan/skills chip dropdowns, mention trigger detection, auto-resize, keyboard shortcuts, send/stop toggle.

```typescript
import { memo, useState, useRef, useCallback, useEffect, type KeyboardEvent } from 'react';
import { useChatStore } from '@/stores/chatStore';
import type { Mention, MentionSearchResult } from '@/bridge/types';
// prompt-kit PromptInput — adjust imports per actual source
import {
  PromptInput,
  PromptInputTextarea,
  PromptInputActions,
} from '@/components/ui/prompt-kit/prompt-input';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from '@/components/ui/dropdown-menu';
import { MentionDropdown } from './MentionDropdown';
import { Send, Square, ChevronDown } from 'lucide-react';

export const InputBar = memo(function InputBar() {
  const inputState = useChatStore(s => s.inputState);
  const busy = useChatStore(s => s.busy);
  const focusTrigger = useChatStore(s => s.focusInputTrigger);

  const [text, setText] = useState('');
  const [mentions, setMentions] = useState<Mention[]>([]);
  const [showMentions, setShowMentions] = useState(false);
  const [mentionQuery, setMentionQuery] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // Focus on trigger
  useEffect(() => {
    if (focusTrigger > 0) textareaRef.current?.focus();
  }, [focusTrigger]);

  // Detect @ mention trigger
  const handleChange = useCallback((value: string) => {
    setText(value);
    const atMatch = value.match(/@(\S*)$/);
    if (atMatch) {
      setShowMentions(true);
      setMentionQuery(atMatch[1]);
    } else {
      setShowMentions(false);
    }
  }, []);

  const handleMentionSelect = useCallback((result: MentionSearchResult) => {
    const mention: Mention = { type: result.type, label: result.label, path: result.path, icon: result.icon };
    setMentions(prev => [...prev, mention]);
    // Remove the @query from text
    setText(prev => prev.replace(/@\S*$/, ''));
    setShowMentions(false);
    textareaRef.current?.focus();
  }, []);

  const removeMention = useCallback((idx: number) => {
    setMentions(prev => prev.filter((_, i) => i !== idx));
  }, []);

  const handleSend = useCallback(() => {
    const trimmed = text.trim();
    if (!trimmed && mentions.length === 0) return;
    useChatStore.getState().sendMessage(trimmed, mentions);
    setText('');
    setMentions([]);
  }, [text, mentions]);

  const handleStop = useCallback(() => {
    window._cancelTask?.();
  }, []);

  const handleKeyDown = useCallback((e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      if (busy) return;
      handleSend();
    }
  }, [busy, handleSend]);

  const isLocked = inputState.locked || busy;

  return (
    <div className="relative border-t px-3 py-2" style={{ borderColor: 'var(--border)', backgroundColor: 'var(--input-bg)' }}>
      {/* Mention chips */}
      {mentions.length > 0 && (
        <div className="flex flex-wrap gap-1 mb-2">
          {mentions.map((m, i) => (
            <Badge
              key={`${m.label}-${i}`}
              variant="secondary"
              className="text-[11px] gap-1 pr-1"
            >
              <span className="text-[10px] opacity-60">{m.icon ?? '@'}</span>
              <span className="max-w-[100px] truncate">{m.label}</span>
              <button
                onClick={() => removeMention(i)}
                className="ml-0.5 h-3 w-3 flex items-center justify-center rounded-sm opacity-60 hover:opacity-100"
              >
                ×
              </button>
            </Badge>
          ))}
        </div>
      )}

      {/* Mention dropdown */}
      {showMentions && (
        <MentionDropdown
          query={mentionQuery}
          onSelect={handleMentionSelect}
          onDismiss={() => setShowMentions(false)}
        />
      )}

      {/* Input area */}
      <div className="flex items-end gap-2">
        {/* Model selector chip */}
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="sm" className="text-[10px] h-7 px-2 shrink-0">
              {inputState.model || 'Model'} <ChevronDown className="ml-1 h-3 w-3" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent>
            <DropdownMenuItem onClick={() => window._changeModel?.('claude-sonnet-4-6')}>
              Claude Sonnet 4.6
            </DropdownMenuItem>
            <DropdownMenuItem onClick={() => window._changeModel?.('claude-opus-4-6')}>
              Claude Opus 4.6
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>

        <textarea
          ref={textareaRef}
          value={text}
          onChange={e => handleChange(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={isLocked ? 'Agent is working...' : 'Ask anything... (@ to mention)'}
          disabled={isLocked}
          rows={1}
          className="flex-1 resize-none bg-transparent text-sm outline-none placeholder:text-[var(--fg-muted)] disabled:opacity-50"
          style={{ color: 'var(--fg)', minHeight: '24px', maxHeight: '120px' }}
        />

        {busy ? (
          <Button variant="destructive" size="sm" className="h-7 w-7 p-0 shrink-0" onClick={handleStop}>
            <Square className="h-3 w-3" />
          </Button>
        ) : (
          <Button
            size="sm"
            className="h-7 w-7 p-0 shrink-0"
            onClick={handleSend}
            disabled={!text.trim() && mentions.length === 0}
          >
            <Send className="h-3 w-3" />
          </Button>
        )}
      </div>
    </div>
  );
});
```

**Note:** This is a simplified version. The existing ChatInput has additional chip dropdowns (PlanChip, SkillsChip, MoreChip) that can be added as follow-up refinements using shadcn DropdownMenu. The core input/send/stop/mention flow is preserved. The prompt-kit `PromptInput` component may provide the textarea + actions layout — check the copied source and use it if the API fits, or use the raw textarea approach above.

- [ ] **Step 2: Verify TypeScript compilation**

```bash
cd agent/webview && npx tsc --noEmit
```

- [ ] **Step 3: Commit**

```bash
cd .worktrees/phase-3-agentic-ai
git add agent/webview/src/components/input/InputBar.tsx
git commit -m "feat: wire InputBar component (wraps prompt-kit PromptInput + shadcn)"
```

---

## Phase 6: Wire ChatView + Update App.tsx (Task 14)

### Task 14: Wire ChatView and Replace Root Components

**Files:**
- Create: `agent/webview/src/components/chat/ChatView.tsx`
- Modify: `agent/webview/src/App.tsx`

- [ ] **Step 1: Create ChatView wiring component**

Create `agent/webview/src/components/chat/ChatView.tsx`:

```typescript
import { memo, useRef, useEffect } from 'react';
import { useChatStore } from '@/stores/chatStore';
import { AgentMessage } from './AgentMessage';
import { ToolCallView } from '@/components/agent/ToolCallView';
import { ThinkingView } from '@/components/agent/ThinkingView';
import { PlanSummaryCard } from '@/components/agent/PlanSummaryCard';
import { PlanProgressWidget } from '@/components/agent/PlanProgressWidget';
import { QuestionView } from '@/components/agent/QuestionView';
import { useAutoScroll } from '@/hooks/useAutoScroll';
// Import prompt-kit ChatContainer if it provides scroll management
// Otherwise use a plain scrollable div with useAutoScroll
// import { ChatContainer } from '@/components/ui/prompt-kit/chat-container';

export const ChatView = memo(function ChatView() {
  const messages = useChatStore(s => s.messages);
  const activeStream = useChatStore(s => s.activeStream);
  const activeToolCalls = useChatStore(s => s.activeToolCalls);
  const plan = useChatStore(s => s.plan);
  const questions = useChatStore(s => s.questions);
  const activeQuestionIndex = useChatStore(s => s.activeQuestionIndex);

  const scrollRef = useRef<HTMLDivElement>(null);
  const { scrollToBottom, showScrollButton } = useAutoScroll(scrollRef, messages);

  // Convert tool calls map to array sorted by insertion
  const toolCallsArray = Array.from(activeToolCalls.values());

  return (
    <div ref={scrollRef} className="flex-1 overflow-y-auto px-4 py-3 space-y-2">
      {/* Messages */}
      {messages.map((msg, idx) => (
        <AgentMessage
          key={msg.id ?? idx}
          message={msg}
          isStreaming={activeStream?.isStreaming && idx === messages.length - 1}
          streamText={activeStream?.isStreaming && idx === messages.length - 1 ? activeStream.text : undefined}
        />
      ))}

      {/* Active tool calls */}
      {toolCallsArray.map((tc, idx) => (
        <ToolCallView
          key={tc.name + idx}
          toolCall={tc}
          isLatest={idx === toolCallsArray.length - 1}
        />
      ))}

      {/* Plan */}
      {plan && !plan.approved && <PlanSummaryCard plan={plan} />}
      {plan && plan.approved && <PlanProgressWidget plan={plan} />}

      {/* Questions */}
      {questions && questions.length > 0 && (
        <QuestionView questions={questions} activeIndex={activeQuestionIndex} />
      )}

      {/* Scroll-to-bottom button */}
      {showScrollButton && (
        <button
          onClick={scrollToBottom}
          className="fixed bottom-20 right-6 z-40 flex h-8 w-8 items-center justify-center rounded-full border shadow-md animate-[scroll-button-enter_220ms_ease-out]"
          style={{
            backgroundColor: 'var(--card)',
            borderColor: 'var(--border)',
            color: 'var(--fg-secondary)',
          }}
        >
          ↓
        </button>
      )}
    </div>
  );
});
```

**Note:** If prompt-kit's `ChatContainer` provides useful scroll management (stick-to-bottom, scroll detection), use it instead of the raw div. Check the copied source from Task 4.

- [ ] **Step 2: Update App.tsx to use new components**

Modify `agent/webview/src/App.tsx`:

```typescript
import { useEffect } from 'react';
import { initBridge } from '@/bridge/jcef-bridge';
import { installMockBridge } from '@/bridge/mock-bridge';
import { simulateTheme } from '@/bridge/theme-controller';
import { useChatStore } from '@/stores/chatStore';
import { useThemeStore } from '@/stores/themeStore';
import { useSettingsStore } from '@/stores/settingsStore';
import { ScreenReaderAnnouncer } from '@/components/common/ScreenReaderAnnouncer';
import { ChatView } from '@/components/chat/ChatView';
import { InputBar } from '@/components/input/InputBar';

export default function App() {
  useEffect(() => {
    initBridge(
      useChatStore.getState,
      useThemeStore.getState,
      useSettingsStore.getState,
    );

    // Dev mode: mock bridge + theme
    if (typeof window._sendMessage === 'undefined') {
      installMockBridge();
      simulateTheme(true);
    }
  }, []);

  // Ctrl/Cmd+K to focus input
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        useChatStore.getState().focusInput();
      }
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, []);

  return (
    <div className="flex h-screen flex-col bg-[var(--bg)] text-[var(--fg)]">
      <ScreenReaderAnnouncer />
      <ChatView />
      <InputBar />
    </div>
  );
}
```

- [ ] **Step 3: Verify build compiles and runs**

```bash
cd agent/webview && npm run build
```

Expected: Build succeeds. The app now uses all new components.

- [ ] **Step 4: Commit**

```bash
cd .worktrees/phase-3-agentic-ai
git add agent/webview/src/components/chat/ChatView.tsx agent/webview/src/App.tsx
git commit -m "feat: wire ChatView and update App.tsx to use all new library components"
```

---

## Phase 7: Restyle Kept Components (Task 15)

### Task 15: Restyle ActionToolbar and VisualizationSettings

**Files:**
- Modify: `agent/webview/src/components/input/ActionToolbar.tsx`
- Modify: `agent/webview/src/components/common/VisualizationSettings.tsx`

- [ ] **Step 1: Restyle ActionToolbar with shadcn Button + DropdownMenu**

Replace the custom button styling in `ActionToolbar.tsx` with shadcn `Button` components. The existing toolbar has 5 actions (Stop, Undo, New, Traces, Settings) with conditional visibility.

Read the current `ActionToolbar.tsx`, then update it to use:
- `Button` from `@/components/ui/button` (variant="ghost", size="sm")
- `DropdownMenu` for the Settings action if needed
- Keep the same `window._cancelTask?.()`, `window._requestUndo?.()`, etc. bridge calls
- Keep the same `isHovered` / `busy` conditional visibility logic

- [ ] **Step 2: Restyle VisualizationSettings with shadcn Switch + Select**

Read the current `VisualizationSettings.tsx`, then update it to use:
- `Switch` from `@/components/ui/switch` for per-type enable/disable toggles
- `Select` from `@/components/ui/select` for dropdown options
- Keep the same `useSettingsStore` reads and actions

- [ ] **Step 3: Verify build**

```bash
cd agent/webview && npm run build
```

- [ ] **Step 4: Commit**

```bash
cd .worktrees/phase-3-agentic-ai
git add agent/webview/src/components/input/ActionToolbar.tsx agent/webview/src/components/common/VisualizationSettings.tsx
git commit -m "style: restyle ActionToolbar and VisualizationSettings with shadcn primitives"
```

---

## Phase 8: Component Showcase (Tasks 16-17)

### Task 16: Create Showcase Infrastructure

**Files:**
- Create: `agent/webview/showcase.html`
- Create: `agent/webview/src/showcase.tsx`
- Create: `agent/webview/src/showcase/mock-data.ts`
- Create: `agent/webview/src/showcase/theme-provider.ts`
- Modify: `agent/webview/vite.config.ts`

- [ ] **Step 1: Create showcase.html**

Create `agent/webview/showcase.html`:

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Agent UI — Component Showcase</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/showcase.tsx"></script>
  </body>
</html>
```

- [ ] **Step 2: Create theme-provider.ts**

Create `agent/webview/src/showcase/theme-provider.ts`:

```typescript
import { darkThemeDefaults, lightThemeDefaults, fontDefaults } from '@/config/theme-defaults';
import type { ThemeVars } from '@/bridge/types';

export function applyShowcaseTheme(dark: boolean) {
  const vars: ThemeVars = dark ? darkThemeDefaults : lightThemeDefaults;
  const root = document.documentElement;

  for (const [key, value] of Object.entries(vars)) {
    root.style.setProperty(`--${key}`, value);
  }
  for (const [key, value] of Object.entries(fontDefaults)) {
    root.style.setProperty(`--${key}`, value);
  }

  // Store preference
  localStorage.setItem('showcase-theme', dark ? 'dark' : 'light');
}

export function getStoredTheme(): boolean {
  return localStorage.getItem('showcase-theme') !== 'light';
}
```

- [ ] **Step 3: Create mock-data.ts**

Create `agent/webview/src/showcase/mock-data.ts`:

```typescript
import type { Message, ToolCall, Plan, Question, Mention, MentionSearchResult } from '@/bridge/types';

export const mockMessages: Message[] = [
  { id: '1', role: 'user', content: 'Can you read the main configuration file and check for any deprecated settings?', timestamp: Date.now() - 60000 },
  { id: '2', role: 'agent', content: 'I\'ll read the configuration file and analyze it for deprecated settings.\n\n```typescript\nconst config = loadConfig("app.config.ts");\n```\n\nFound **3 deprecated settings** that should be updated.', timestamp: Date.now() - 55000 },
  { id: '3', role: 'user', content: 'Show me a markdown example with all formatting.', timestamp: Date.now() - 50000 },
  { id: '4', role: 'agent', content: '## Markdown Demo\n\nHere\'s **bold**, *italic*, `inline code`, and a [link](https://example.com).\n\n- List item 1\n- List item 2\n  - Nested item\n\n> Blockquote text\n\n```python\ndef hello():\n    print("world")\n```', timestamp: Date.now() - 45000 },
];

export const mockStreamingMessage: Message = {
  id: '5', role: 'agent', content: 'Analyzing the test results and preparing a summary of the findings...', timestamp: Date.now(),
};

export const mockToolCalls: ToolCall[] = [
  { name: 'read_file', args: '{"path": "src/config.ts"}', status: 'COMPLETED', result: 'File content (247 lines)', durationMs: 120 },
  { name: 'search_code', args: '{"query": "deprecated", "glob": "**/*.ts"}', status: 'COMPLETED', result: '3 matches found', durationMs: 340 },
  { name: 'edit_file', args: '{"path": "src/config.ts", "old": "legacy: true", "new": "modern: true"}', status: 'RUNNING', result: undefined, durationMs: undefined },
  { name: 'run_command', args: '{"command": "npm test"}', status: 'PENDING', result: undefined, durationMs: undefined },
  { name: 'run_command', args: '{"command": "rm -rf dist/"}', status: 'ERROR', result: 'Permission denied', durationMs: 50 },
];

export const mockPlanPending: Plan = {
  title: 'Refactor Authentication Module',
  steps: [
    { id: '1', title: 'Extract token validation into separate service', status: 'pending' },
    { id: '2', title: 'Add unit tests for token service', status: 'pending' },
    { id: '3', title: 'Update API routes to use new service', status: 'pending' },
    { id: '4', title: 'Remove deprecated auth middleware', status: 'pending' },
  ],
  approved: false,
};

export const mockPlanInProgress: Plan = {
  title: 'Refactor Authentication Module',
  steps: [
    { id: '1', title: 'Phase 1: Extract token validation', status: 'completed' },
    { id: '2', title: 'Phase 1: Add unit tests', status: 'completed' },
    { id: '3', title: 'Phase 2: Update API routes', status: 'running' },
    { id: '4', title: 'Phase 2: Remove deprecated middleware', status: 'pending' },
    { id: '5', title: 'Phase 3: Integration tests', status: 'pending' },
  ],
  approved: true,
};

export const mockQuestions: Question[] = [
  {
    id: 'q1',
    text: 'Which authentication strategy should we use?',
    type: 'single-select',
    options: [
      { label: 'JWT with refresh tokens', description: 'Stateless, scalable', selected: false },
      { label: 'Session-based with Redis', description: 'Simple, server-side state', selected: false },
      { label: 'OAuth 2.0 with PKCE', description: 'Third-party delegation', selected: false },
    ],
  },
  {
    id: 'q2',
    text: 'Which features should be included?',
    type: 'multi-select',
    options: [
      { label: 'Two-factor authentication', selected: false },
      { label: 'Password reset flow', selected: false },
      { label: 'Social login (Google, GitHub)', selected: false },
      { label: 'API key management', selected: false },
    ],
  },
];

export const mockMentions: Mention[] = [
  { type: 'file', label: 'config.ts', path: 'src/config.ts', icon: '📄' },
  { type: 'symbol', label: 'AuthService', path: 'src/auth.ts', icon: '#' },
  { type: 'tool', label: 'run_tests', icon: '🔧' },
];

export const mockMentionResults: MentionSearchResult[] = [
  { type: 'file', label: 'config.ts', path: 'src/config.ts', description: 'Main configuration' },
  { type: 'file', label: 'auth.ts', path: 'src/auth.ts', description: 'Authentication module' },
  { type: 'symbol', label: 'AuthService', path: 'src/auth.ts', description: 'class' },
  { type: 'symbol', label: 'TokenValidator', path: 'src/token.ts', description: 'interface' },
  { type: 'tool', label: 'read_file', description: 'Read a file from the filesystem' },
  { type: 'tool', label: 'edit_file', description: 'Edit a file with search/replace' },
  { type: 'skill', label: 'systematic-debugging', description: 'Step-by-step debugging workflow' },
];
```

- [ ] **Step 4: Create showcase.tsx entry point**

Create `agent/webview/src/showcase.tsx`:

```typescript
import { StrictMode, useState, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import { applyShowcaseTheme, getStoredTheme } from './showcase/theme-provider';
import {
  mockMessages, mockStreamingMessage, mockToolCalls,
  mockPlanPending, mockPlanInProgress, mockQuestions,
  mockMentions, mockMentionResults,
} from './showcase/mock-data';
import { AgentMessage } from './components/chat/AgentMessage';
import { ToolCallView } from './components/agent/ToolCallView';
import { ThinkingView } from './components/agent/ThinkingView';
import { ApprovalView } from './components/agent/ApprovalView';
import { QuestionView } from './components/agent/QuestionView';
import { PlanSummaryCard } from './components/agent/PlanSummaryCard';
import { PlanProgressWidget } from './components/agent/PlanProgressWidget';
import { Badge } from './components/ui/badge';
import { Button } from './components/ui/button';
import { Sun, Moon } from 'lucide-react';

// Install mock bridge globals so components don't crash
window._sendMessage = window._sendMessage ?? (() => {});
window._approvePlan = window._approvePlan ?? (() => alert('Plan approved'));
window._revisePlan = window._revisePlan ?? (() => alert('Plan revised'));
window._cancelTask = window._cancelTask ?? (() => {});
window._searchMentions = window._searchMentions ?? (() => {});
window._questionAnswered = window._questionAnswered ?? ((id, json) => alert(`Answered ${id}: ${json}`));
window._questionSkipped = window._questionSkipped ?? (() => {});
window._editQuestion = window._editQuestion ?? (() => {});

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="mb-8">
      <h2 className="text-lg font-semibold mb-3 pb-1 border-b" style={{ color: 'var(--fg)', borderColor: 'var(--border)' }}>
        {title}
      </h2>
      <div className="space-y-3">{children}</div>
    </div>
  );
}

function Showcase() {
  const [dark, setDark] = useState(getStoredTheme);

  const toggleTheme = () => {
    const next = !dark;
    setDark(next);
    applyShowcaseTheme(next);
  };

  // Apply theme on mount and when toggled
  useEffect(() => { applyShowcaseTheme(dark); }, [dark]);

  return (
    <div className="min-h-screen p-6 max-w-3xl mx-auto" style={{ backgroundColor: 'var(--bg)', color: 'var(--fg)' }}>
      {/* Theme toggle */}
      <div className="fixed top-4 right-4 z-50">
        <Button variant="outline" size="sm" onClick={toggleTheme}>
          {dark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
          <span className="ml-2 text-xs">{dark ? 'Light' : 'Dark'}</span>
        </Button>
      </div>

      <h1 className="text-2xl font-bold mb-6">Agent UI — Component Showcase</h1>

      <Section title="Messages">
        {mockMessages.map(msg => (
          <AgentMessage key={msg.id} message={msg} />
        ))}
        <p className="text-xs mt-2" style={{ color: 'var(--fg-muted)' }}>↓ Streaming message:</p>
        <AgentMessage
          message={mockStreamingMessage}
          isStreaming={true}
          streamText={mockStreamingMessage.content}
        />
      </Section>

      <Section title="Tool Calls">
        {mockToolCalls.map((tc, i) => (
          <ToolCallView key={i} toolCall={tc} isLatest={i === 2} />
        ))}
      </Section>

      <Section title="Thinking / Reasoning">
        <ThinkingView content="Let me analyze the codebase structure to understand the authentication flow..." isStreaming={true} />
        <ThinkingView content="The authentication module uses JWT tokens with a 24-hour expiry. The refresh token mechanism is implemented in auth-middleware.ts." isStreaming={false} />
      </Section>

      <Section title="Approval Gate">
        <ApprovalView
          title="Delete Production Database"
          description="This will permanently remove all data from the production environment."
          commandPreview="DROP DATABASE production_db;"
          onApprove={() => alert('Approved')}
          onDeny={() => alert('Denied')}
        />
      </Section>

      <Section title="Question Wizard">
        <QuestionView questions={mockQuestions} activeIndex={0} />
      </Section>

      <Section title="Plan — Pending Review">
        <PlanSummaryCard plan={mockPlanPending} />
      </Section>

      <Section title="Plan — In Progress">
        <PlanProgressWidget plan={mockPlanInProgress} />
      </Section>

      <Section title="Context Chips (Badge)">
        <div className="flex flex-wrap gap-2">
          {(['file', 'folder', 'symbol', 'tool', 'skill'] as const).map(type => (
            <Badge key={type} variant="secondary" className="text-[11px]">
              {type === 'file' ? '📄' : type === 'folder' ? '📁' : type === 'symbol' ? '#' : type === 'tool' ? '🔧' : '✨'}
              <span className="ml-1">{type}</span>
            </Badge>
          ))}
        </div>
      </Section>
    </div>
  );
}

const root = createRoot(document.getElementById('root')!);
root.render(
  <StrictMode>
    <Showcase />
  </StrictMode>
);
```

- [ ] **Step 5: Update vite.config.ts for multi-page**

Modify `agent/webview/vite.config.ts` to add the plan-editor and showcase entries:

```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { resolve } from 'path'

export default defineConfig(({ mode }) => ({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  build: {
    outDir: '../src/main/resources/webview/dist',
    emptyOutDir: true,
    rollupOptions: {
      input: {
        main: resolve(__dirname, 'index.html'),
        // plan-editor added in Task 18 after plan-editor.html is created
        // Showcase is dev-only — excluded from production build
        ...(mode === 'development' ? { showcase: resolve(__dirname, 'showcase.html') } : {}),
      },
      output: {
        manualChunks(id) {
          if (id.includes('mermaid') || id.includes('dagre')) return 'mermaid'
          if (id.includes('katex')) return 'katex'
          if (id.includes('chart.js')) return 'chartjs'
          if (id.includes('diff2html')) return 'diff2html'
        },
      },
    },
    assetsInlineLimit: 8192,
    minify: 'terser',
    terserOptions: {
      compress: { drop_console: true, drop_debugger: true },
    },
  },
}))
```

**Note:** The `plan-editor.html` and `plan-editor.tsx` files are created in Task 18 (Plan Editor Tab). For now, comment out the plan-editor input or create a placeholder.

- [ ] **Step 6: Verify showcase runs in dev mode**

```bash
cd agent/webview && npm run dev
```

Open `http://localhost:5173/showcase.html` in a browser. Verify all components render.

- [ ] **Step 7: Verify production build still works**

```bash
cd agent/webview && npm run build
```

Expected: Build succeeds. Showcase is NOT in the output.

- [ ] **Step 8: Commit**

```bash
cd .worktrees/phase-3-agentic-ai
git add agent/webview/showcase.html agent/webview/src/showcase.tsx agent/webview/src/showcase/ agent/webview/vite.config.ts
git commit -m "feat: add dev-only component showcase page with mock data and theme toggle"
```

---

## Phase 9: Cleanup (Tasks 17-18)

### Task 17: Delete Old Components + THIRD_PARTY_LICENSES

**Files:**
- Delete: `agent/webview/src/components/chat/MessageCard.tsx`
- Delete: `agent/webview/src/components/chat/MessageList.tsx`
- Delete: `agent/webview/src/components/agent/ToolCallCard.tsx`
- Delete: `agent/webview/src/components/agent/ToolCallList.tsx`
- Delete: `agent/webview/src/components/agent/ThinkingBlock.tsx`
- Delete: `agent/webview/src/components/agent/PlanCard.tsx`
- Delete: `agent/webview/src/components/agent/ApprovalGate.tsx`
- Delete: `agent/webview/src/components/agent/QuestionWizard.tsx`
- Delete: `agent/webview/src/components/input/ChatInput.tsx`
- Delete: `agent/webview/src/components/input/ContextChip.tsx`
- Delete: `agent/webview/src/components/input/MentionAutocomplete.tsx`
- Delete: `agent/webview/src/components/common/FullscreenOverlay.tsx`
- Create: `agent/src/main/resources/THIRD_PARTY_LICENSES.md`

- [ ] **Step 1: Verify no remaining imports of old components**

```bash
cd agent/webview && grep -r "MessageCard\|MessageList\|ToolCallCard\|ToolCallList\|ThinkingBlock\|PlanCard\|ApprovalGate\|QuestionWizard\|ChatInput\|ContextChip\|MentionAutocomplete\|FullscreenOverlay" src/ --include="*.tsx" --include="*.ts" -l
```

Expected: Only the files being deleted should appear. If any other file imports them, update that file first.

- [ ] **Step 2: Delete old component files**

```bash
cd agent/webview
rm src/components/chat/MessageCard.tsx
rm src/components/chat/MessageList.tsx
rm src/components/agent/ToolCallCard.tsx
rm src/components/agent/ToolCallList.tsx
rm src/components/agent/ThinkingBlock.tsx
rm src/components/agent/PlanCard.tsx
rm src/components/agent/ApprovalGate.tsx
rm src/components/agent/QuestionWizard.tsx
rm src/components/input/ChatInput.tsx
rm src/components/input/ContextChip.tsx
rm src/components/input/MentionAutocomplete.tsx
rm src/components/common/FullscreenOverlay.tsx
```

- [ ] **Step 3: Create THIRD_PARTY_LICENSES.md**

Create `agent/src/main/resources/THIRD_PARTY_LICENSES.md`:

```markdown
# Third-Party Licenses

This plugin includes source code from the following MIT-licensed projects:

## shadcn/ui
- License: MIT
- Source: https://github.com/shadcn-ui/ui
- Components: Button, Badge, Command, Dialog, DropdownMenu, Select, Switch, Collapsible, Tooltip, Progress, Separator

## prompt-kit
- License: MIT
- Source: https://github.com/prompt-kit/prompt-kit
- Components: Message, ChatContainer, Tool, Reasoning, PromptInput, CodeBlock, ScrollButton, Loader, Markdown, ThinkingBar, FeedbackBar, PromptSuggestion

## tool-ui
- License: MIT
- Source: https://github.com/assistant-ui/tool-ui
- Components: ApprovalCard, QuestionFlow

## Radix UI
- License: MIT
- Source: https://github.com/radix-ui/primitives
- Packages: react-dialog, react-collapsible, react-select, react-switch, react-tooltip, react-dropdown-menu, react-progress, react-separator

## cmdk
- License: MIT
- Source: https://github.com/pacocoursey/cmdk

## class-variance-authority
- License: Apache-2.0
- Source: https://github.com/joe-bell/cva

## clsx
- License: MIT
- Source: https://github.com/lukeed/clsx

## tailwind-merge
- License: MIT
- Source: https://github.com/dcastil/tailwind-merge

## tailwindcss-animate
- License: MIT
- Source: https://github.com/jamiebuilds/tailwindcss-animate
```

- [ ] **Step 4: Verify build**

```bash
cd agent/webview && npm run build
```

Expected: Build succeeds with no missing import errors.

- [ ] **Step 5: Commit**

```bash
cd .worktrees/phase-3-agentic-ai
git add -A agent/webview/src/components/ agent/src/main/resources/THIRD_PARTY_LICENSES.md
git commit -m "chore: delete old custom components, add THIRD_PARTY_LICENSES"
```

---

### Task 18: Plan Editor Tab (Kotlin + Lightweight React Page)

**Files:**
- Create: `agent/webview/plan-editor.html`
- Create: `agent/webview/src/plan-editor.tsx`
- Modify: `agent/src/main/kotlin/.../ui/plan/AgentPlanEditor.kt`

- [ ] **Step 1: Create plan-editor.html**

Create `agent/webview/plan-editor.html`:

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Implementation Plan</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/plan-editor.tsx"></script>
  </body>
</html>
```

- [ ] **Step 2: Create plan-editor.tsx**

Create `agent/webview/src/plan-editor.tsx`. This is a lightweight React app that renders a plan as read-only markdown with inline comment gutters.

```typescript
import { StrictMode, useState, useCallback } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import { Button } from './components/ui/button';
import { MessageSquare, Check, RotateCcw } from 'lucide-react';

// Plan data injected by Kotlin via window.renderPlan(json)
interface PlanData {
  title: string;
  markdown: string;
  approved: boolean;
  comments: Record<string, string>; // lineId -> comment text
}

let planData: PlanData | null = null;
let rootInstance: ReturnType<typeof createRoot> | null = null;

// Kotlin calls this to inject plan data
(window as any).renderPlan = (json: string) => {
  planData = JSON.parse(json);
  // Re-render using existing root
  if (rootInstance) {
    rootInstance.render(<StrictMode><PlanEditor /></StrictMode>);
  }
};

(window as any).updatePlanStep = (stepId: string, status: string) => {
  // Update step status in UI — handled by re-render from Kotlin
};

function PlanEditor() {
  const [comments, setComments] = useState<Record<string, string>>(planData?.comments ?? {});
  const [activeComment, setActiveComment] = useState<string | null>(null);

  if (!planData) {
    return (
      <div className="flex h-screen items-center justify-center" style={{ backgroundColor: 'var(--bg)', color: 'var(--fg-muted)' }}>
        Loading plan...
      </div>
    );
  }

  const lines = planData.markdown.split('\n');

  const addComment = useCallback((lineId: string, text: string) => {
    setComments(prev => ({ ...prev, [lineId]: text }));
    setActiveComment(null);
  }, []);

  const handleProceed = useCallback(() => {
    (window as any)._approvePlan?.();
  }, []);

  const handleRevise = useCallback(() => {
    const json = JSON.stringify(comments);
    (window as any)._revisePlan?.(json);
  }, [comments]);

  const hasComments = Object.keys(comments).length > 0;

  return (
    <div className="min-h-screen" style={{ backgroundColor: 'var(--bg)', color: 'var(--fg)' }}>
      {/* Header */}
      <div className="sticky top-0 z-10 flex items-center justify-between border-b px-6 py-3"
           style={{ backgroundColor: 'var(--bg)', borderColor: 'var(--border)' }}>
        <h1 className="text-base font-semibold">{planData.title || 'Implementation Plan'}</h1>
        <div className="flex gap-2">
          {hasComments && (
            <Button variant="outline" size="sm" onClick={handleRevise}>
              <RotateCcw className="h-3 w-3 mr-1" /> Revise
            </Button>
          )}
          <Button size="sm" onClick={handleProceed}>
            <Check className="h-3 w-3 mr-1" /> Proceed
          </Button>
        </div>
      </div>

      {/* Plan content with comment gutters */}
      <div className="px-6 py-4 max-w-4xl mx-auto">
        {lines.map((line, idx) => {
          const lineId = `line-${idx}`;
          const comment = comments[lineId];

          return (
            <div key={idx} className="group relative flex">
              {/* Comment gutter */}
              <div className="w-8 shrink-0 flex justify-center pt-1">
                {comment ? (
                  <button
                    onClick={() => setActiveComment(lineId)}
                    className="h-4 w-4 rounded-full flex items-center justify-center"
                    style={{ backgroundColor: 'var(--accent-edit)', color: 'var(--bg)' }}
                  >
                    <MessageSquare className="h-2.5 w-2.5" />
                  </button>
                ) : (
                  <button
                    onClick={() => setActiveComment(lineId)}
                    className="h-4 w-4 rounded-full flex items-center justify-center opacity-0 group-hover:opacity-40 transition-opacity"
                    style={{ color: 'var(--fg-muted)' }}
                  >
                    +
                  </button>
                )}
              </div>

              {/* Line content */}
              <div className="flex-1 py-0.5">
                <MarkdownLine content={line} />
                {/* Inline comment editor */}
                {activeComment === lineId && (
                  <CommentEditor
                    value={comment ?? ''}
                    onSave={(text) => addComment(lineId, text)}
                    onCancel={() => setActiveComment(null)}
                  />
                )}
                {/* Show existing comment */}
                {comment && activeComment !== lineId && (
                  <div
                    className="mt-1 ml-2 text-xs px-2 py-1 rounded border-l-2"
                    style={{ color: 'var(--accent-edit)', borderColor: 'var(--accent-edit)', backgroundColor: 'rgba(245,158,11,0.05)' }}
                  >
                    {comment}
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function MarkdownLine({ content }: { content: string }) {
  // Simple markdown line rendering — headings, bold, code, lists
  if (content.startsWith('### ')) return <h3 className="text-base font-semibold mt-4 mb-1">{content.slice(4)}</h3>;
  if (content.startsWith('## ')) return <h2 className="text-lg font-bold mt-6 mb-2">{content.slice(3)}</h2>;
  if (content.startsWith('# ')) return <h1 className="text-xl font-bold mt-6 mb-2">{content.slice(2)}</h1>;
  if (content.startsWith('- [ ] ')) return <div className="flex gap-2 ml-4"><span style={{ color: 'var(--fg-muted)' }}>☐</span><span>{content.slice(6)}</span></div>;
  if (content.startsWith('- [x] ')) return <div className="flex gap-2 ml-4"><span style={{ color: 'var(--success)' }}>☑</span><span className="line-through opacity-60">{content.slice(6)}</span></div>;
  if (content.startsWith('- ')) return <div className="ml-4">• {content.slice(2)}</div>;
  if (content.trim() === '') return <div className="h-3" />;
  if (content.startsWith('```')) return <div className="font-mono text-xs" style={{ color: 'var(--fg-muted)' }}>{content}</div>;
  return <div className="text-sm leading-relaxed">{content}</div>;
}

function CommentEditor({ value, onSave, onCancel }: { value: string; onSave: (text: string) => void; onCancel: () => void }) {
  const [text, setText] = useState(value);
  return (
    <div className="mt-1 ml-2 flex gap-1">
      <input
        autoFocus
        value={text}
        onChange={e => setText(e.target.value)}
        onKeyDown={e => { if (e.key === 'Enter') onSave(text); if (e.key === 'Escape') onCancel(); }}
        placeholder="Add comment..."
        className="flex-1 text-xs px-2 py-1 rounded border bg-transparent outline-none"
        style={{ borderColor: 'var(--border)', color: 'var(--fg)' }}
      />
      <Button variant="ghost" size="sm" className="h-6 text-[10px]" onClick={() => onSave(text)}>Save</Button>
      <Button variant="ghost" size="sm" className="h-6 text-[10px]" onClick={onCancel}>Cancel</Button>
    </div>
  );
}

// Initialize root once
const container = document.getElementById('root');
if (container) {
  rootInstance = createRoot(container);
  rootInstance.render(<StrictMode><PlanEditor /></StrictMode>);
}
```

- [ ] **Step 3: Update AgentPlanEditor.kt to serve React plan editor**

Modify `AgentPlanEditor.kt` line 75 to load the React-built plan editor instead of the old `agent-plan.html`:

Change:
```kotlin
val htmlUrl = javaClass.getResource("/webview/agent-plan.html")
```
To:
```kotlin
val htmlUrl = javaClass.getResource("/webview/dist/plan-editor.html")
```

The rest of the Kotlin file stays the same — it already injects `window._approvePlan`, `window._revisePlan`, `window._openFile` bridges and calls `renderPlan(json)`.

- [ ] **Step 4: Verify build produces plan-editor output**

```bash
cd agent/webview && npm run build
ls -la ../src/main/resources/webview/dist/plan-editor.html
```

Expected: File exists.

- [ ] **Step 5: Commit**

```bash
cd .worktrees/phase-3-agentic-ai
git add agent/webview/plan-editor.html agent/webview/src/plan-editor.tsx agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/plan/AgentPlanEditor.kt
git commit -m "feat: React plan editor tab with inline comments, revise/proceed workflow"
```

---

## Phase 10: Final Verification (Task 19)

### Task 19: End-to-End Build Verification

**Files:** None (verification only)

- [ ] **Step 1: Clean build**

```bash
cd agent/webview && rm -rf node_modules/.vite && npm run build
```

Expected: Build succeeds with no errors.

- [ ] **Step 2: Check bundle output**

```bash
ls -la agent/src/main/resources/webview/dist/
```

Expected: `index.html`, `plan-editor.html`, JS/CSS assets present.

- [ ] **Step 3: TypeScript check**

```bash
cd agent/webview && npx tsc --noEmit
```

Expected: No type errors.

- [ ] **Step 4: Verify showcase in dev mode**

```bash
cd agent/webview && npm run dev
```

Open `http://localhost:5173/showcase.html` — all components should render with theme toggle working.

- [ ] **Step 5: Verify Gradle build**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
```

Expected: Agent tests pass (they test Kotlin-side logic, not UI).

- [ ] **Step 6: Build plugin**

```bash
./gradlew buildPlugin
```

Expected: ZIP produced successfully.

- [ ] **Step 7: Commit any remaining fixes**

If any fixes were needed, commit them.

- [ ] **Step 8: Final summary commit**

```bash
cd .worktrees/phase-3-agentic-ai
git add -A
git status  # verify only expected files
git commit -m "feat: complete chat UI library migration — prompt-kit, tool-ui, shadcn/ui

Replaces all custom chat components with real open-source library components.
Plan UX redesigned to use IDE editor tab with inline comments.
Dev-only component showcase at /showcase.html.
Theme bridge maps IntelliJ vars to shadcn tokens — zero hardcoded colors.
Zustand stores and JCEF bridge layer unchanged."
```

---

## Implementation Notes

**FullscreenOverlay → Dialog:** The `FullscreenOverlay.tsx` is deleted in Task 17. Any parent component that imports it should switch to shadcn `Dialog` (copied in Task 3). The main usage is in `RichBlock.tsx` for expanding rich visualizations. When wiring ChatView (Task 14), check if `RichBlock` imports `FullscreenOverlay` and update it to use `Dialog` + `DialogContent` + `DialogHeader` instead.

**Window type declarations:** The existing `bridge/globals.d.ts` declares all `window._xxx` Kotlin bridge functions. New wiring components (QuestionView, InputBar, etc.) that call `window._questionAnswered`, `window._cancelTask`, etc. will typecheck correctly because these are already declared. Verify this during TypeScript checks.

**ChatContainer vs plain div:** Task 14 uses a plain `<div>` with `useAutoScroll`. If prompt-kit's `ChatContainer` (copied in Task 4) provides better scroll behavior (stick-to-bottom), swap the div for `ChatContainer` during implementation.

**Showcase completeness:** The showcase in Task 16 covers the core components. The full spec calls for 16 sections including Dialog, FeedbackBar, PromptSuggestion, InputBar, MentionDropdown, ActionToolbar, and Rich Blocks. Expand the showcase during implementation to cover all sections listed in the spec.

**PlanEditorProvider.kt:** The existing Kotlin files `AgentPlanEditorProvider.kt` and `AgentPlanEditor.kt` already exist in the worktree. Task 18 only modifies `AgentPlanEditor.kt` to serve the React-built plan editor instead of the old static HTML.

**Gradle build paths:** Task 19 commands reference `.worktrees/phase-3-agentic-ai/`. When executing from within the worktree, use relative paths instead (just `./gradlew`).

**Add plan-editor to vite config:** In Task 18, after creating `plan-editor.html`, update `vite.config.ts` to add `'plan-editor': resolve(__dirname, 'plan-editor.html')` to the `rollupOptions.input` object.
