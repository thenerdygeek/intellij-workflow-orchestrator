# Artifact Design Quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the artifact sandbox with Tailwind CSS, UI primitives, and a frontend-design skill to produce Claude.ai-level visual quality in generated artifacts.

**Architecture:** Inline Tailwind Play CDN as vendor file → add to sandbox HTML with theme-mapped config → define UI primitive components in sandbox-main.ts scope → create frontend-design skill → update tool description and system prompt to reference the skill.

**Tech Stack:** Tailwind Play CDN (vendor), React functional components (UI primitives), Markdown skill file.

**Spec:** `docs/superpowers/specs/2026-04-06-artifact-design-quality-design.md`

---

### Task 1: Vendor the Tailwind Play CDN

**Files:**
- Create: `agent/webview/vendor/tailwind-play.js`

- [ ] **Step 1: Download the Tailwind Play CDN script**

```bash
cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite/agent/webview
mkdir -p vendor
curl -sL https://cdn.tailwindcss.com/3.4.17 -o vendor/tailwind-play.js
```

Note: Version 3.4.17 is the latest stable Tailwind 3.x Play CDN. We pin the version by committing the file.

- [ ] **Step 2: Verify the file downloaded correctly**

```bash
head -5 vendor/tailwind-play.js
wc -c vendor/tailwind-play.js
```

Expected: File should be ~300-400KB, starts with a JS comment or IIFE.

- [ ] **Step 3: Commit**

```bash
git add agent/webview/vendor/tailwind-play.js
git commit -m "vendor(webview): add Tailwind Play CDN 3.4.17 for artifact sandbox"
```

---

### Task 2: Add Tailwind to Sandbox HTML

**Files:**
- Modify: `agent/webview/artifact-sandbox.html`

- [ ] **Step 1: Add Tailwind script and config to the sandbox HTML**

In `agent/webview/artifact-sandbox.html`, add two script tags BEFORE the existing `<script type="module" src="/src/sandbox-main.ts">` line:

```html
    <!-- Tailwind CSS (vendored, zero network) -->
    <script src="/vendor/tailwind-play.js"></script>
    <script>
      tailwind.config = {
        darkMode: 'class',
        theme: {
          extend: {
            colors: {
              accent: 'var(--accent, #6366f1)',
              success: 'var(--success, #22c55e)',
              error: 'var(--error, #ef4444)',
              warning: 'var(--warning, #eab308)',
              border: 'var(--border, #3c3c3c)',
              muted: 'var(--fg-muted, #888888)',
              'code-bg': 'var(--code-bg, #1a1a2e)',
            }
          }
        }
      }
    </script>
    <script type="module" src="/src/sandbox-main.ts"></script>
```

Remove the old standalone `<script type="module" src="/src/sandbox-main.ts"></script>` line since it's now included above.

- [ ] **Step 2: Update dark mode toggle in sandbox-main.ts**

In `agent/webview/src/sandbox-main.ts`, find the theme message handler (the `case 'theme':` block). After setting CSS variables, add the dark mode class toggle:

```typescript
// Toggle Tailwind dark mode class
document.documentElement.classList.toggle('dark', bridgeState.isDark)
```

- [ ] **Step 3: Commit**

```bash
git add agent/webview/artifact-sandbox.html agent/webview/src/sandbox-main.ts
git commit -m "feat(webview): add Tailwind CSS to artifact sandbox with theme-mapped config"
```

---

### Task 3: Add UI Primitive Components to Scope

**Files:**
- Modify: `agent/webview/src/sandbox-main.ts`

- [ ] **Step 1: Define UI primitive components**

In `agent/webview/src/sandbox-main.ts`, add the following component definitions AFTER the imports and BEFORE the `// ── State ──` section. These are simple functional components using Tailwind classes:

```typescript
// ── UI Primitives (injected into scope) ──

function Card({ className, children, onClick }: { className?: string; children: React.ReactNode; onClick?: () => void }) {
  return React.createElement('div', {
    className: `rounded-lg border border-[var(--border)] bg-[var(--code-bg)] shadow-sm ${onClick ? 'cursor-pointer' : ''} ${className || ''}`,
    onClick,
  }, children)
}

function CardHeader({ className, children }: { className?: string; children: React.ReactNode }) {
  return React.createElement('div', { className: `flex flex-col space-y-1.5 p-4 pb-2 ${className || ''}` }, children)
}

function CardTitle({ className, children }: { className?: string; children: React.ReactNode }) {
  return React.createElement('h3', { className: `text-sm font-semibold leading-none tracking-tight ${className || ''}` }, children)
}

function CardDescription({ className, children }: { className?: string; children: React.ReactNode }) {
  return React.createElement('p', { className: `text-xs text-[var(--fg-muted)] ${className || ''}` }, children)
}

function CardContent({ className, children }: { className?: string; children: React.ReactNode }) {
  return React.createElement('div', { className: `p-4 pt-0 ${className || ''}` }, children)
}

function Badge({ variant = 'default', className, children }: { variant?: 'default' | 'success' | 'warning' | 'error' | 'outline'; className?: string; children: React.ReactNode }) {
  const styles: Record<string, string> = {
    default: 'bg-[var(--accent)]/10 text-[var(--accent)] border-[var(--accent)]/20',
    success: 'bg-[var(--success)]/10 text-[var(--success)] border-[var(--success)]/20',
    warning: 'bg-[var(--warning)]/10 text-[var(--warning)] border-[var(--warning)]/20',
    error: 'bg-[var(--error)]/10 text-[var(--error)] border-[var(--error)]/20',
    outline: 'bg-transparent text-[var(--fg)] border-[var(--border)]',
  }
  return React.createElement('span', {
    className: `inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium transition-colors ${styles[variant] || styles.default} ${className || ''}`,
  }, children)
}

function Tabs({ value, onValueChange, children }: { value: string; onValueChange: (v: string) => void; children: React.ReactNode }) {
  return React.createElement('div', { className: 'w-full', 'data-active-tab': value },
    typeof children === 'object' && children !== null
      ? React.Children.map(children, (child: any) =>
          child ? React.cloneElement(child, { _activeTab: value, _onTabChange: onValueChange }) : null
        )
      : children
  )
}

function TabsList({ className, children, _activeTab, _onTabChange }: { className?: string; children: React.ReactNode; _activeTab?: string; _onTabChange?: (v: string) => void }) {
  return React.createElement('div', {
    className: `inline-flex h-9 items-center justify-center rounded-lg bg-[var(--code-bg)] p-1 ${className || ''}`,
  }, typeof children === 'object' && children !== null
    ? React.Children.map(children, (child: any) =>
        child ? React.cloneElement(child, { _activeTab, _onTabChange }) : null
      )
    : children
  )
}

function TabsTrigger({ value, children, _activeTab, _onTabChange }: { value: string; children: React.ReactNode; _activeTab?: string; _onTabChange?: (v: string) => void }) {
  const isActive = _activeTab === value
  return React.createElement('button', {
    className: `inline-flex items-center justify-center whitespace-nowrap rounded-md px-3 py-1 text-xs font-medium transition-all ${
      isActive
        ? 'bg-[var(--bg)] text-[var(--fg)] shadow-sm'
        : 'text-[var(--fg-muted)] hover:text-[var(--fg)]'
    }`,
    onClick: () => _onTabChange?.(value),
  }, children)
}

function TabsContent({ value, children, _activeTab }: { value: string; children: React.ReactNode; _activeTab?: string }) {
  if (_activeTab !== value) return null
  return React.createElement('div', { className: 'mt-3' }, children)
}

function Progress({ value, variant = 'default', className }: { value: number; variant?: 'default' | 'success' | 'warning' | 'error'; className?: string }) {
  const colors: Record<string, string> = {
    default: 'bg-[var(--accent)]',
    success: 'bg-[var(--success)]',
    warning: 'bg-[var(--warning)]',
    error: 'bg-[var(--error)]',
  }
  return React.createElement('div', {
    className: `w-full overflow-hidden rounded-full bg-[var(--code-bg)] ${className || ''}`,
    style: { height: 8 },
  }, React.createElement('div', {
    className: `h-full rounded-full transition-all duration-500 ${colors[variant] || colors.default}`,
    style: { width: `${Math.min(100, Math.max(0, value))}%` },
  }))
}

function Separator({ className }: { className?: string }) {
  return React.createElement('div', {
    className: `h-px w-full bg-[var(--border)] ${className || ''}`,
  })
}

function Accordion({ children }: { children: React.ReactNode }) {
  return React.createElement('div', { className: 'divide-y divide-[var(--border)] rounded-lg border border-[var(--border)]' }, children)
}

function AccordionItem({ title, children, defaultOpen = false }: { title: string; children: React.ReactNode; defaultOpen?: boolean }) {
  const [open, setOpen] = React.useState(defaultOpen)
  return React.createElement('div', {},
    React.createElement('button', {
      className: 'flex w-full items-center justify-between p-3 text-sm font-medium hover:bg-[var(--code-bg)] transition-colors',
      onClick: () => setOpen(!open),
    },
      title,
      React.createElement('span', {
        className: `text-xs transition-transform duration-200 ${open ? 'rotate-180' : ''}`,
      }, '\u25BC')
    ),
    open ? React.createElement('div', { className: 'p-3 pt-0 text-sm' }, children) : null
  )
}

function Tooltip({ content, children }: { content: string; children: React.ReactNode }) {
  const [show, setShow] = React.useState(false)
  return React.createElement('div', {
    className: 'relative inline-block',
    onMouseEnter: () => setShow(true),
    onMouseLeave: () => setShow(false),
  },
    children,
    show ? React.createElement('div', {
      className: 'absolute bottom-full left-1/2 -translate-x-1/2 mb-2 px-2 py-1 text-xs rounded bg-[var(--fg)] text-[var(--bg)] whitespace-nowrap z-50 pointer-events-none',
    }, content) : null
  )
}
```

- [ ] **Step 2: Inject UI primitives into the scope**

In the `renderComponent` function, add all UI primitives to the `fullScope` object after the bridge section:

```typescript
const fullScope: Record<string, unknown> = {
  // React hooks
  React, useState, useEffect, useCallback, useMemo, useRef, Fragment,
  // Bridge
  bridge: { ... },  // existing bridge object
  // UI Primitives
  Card, CardHeader, CardTitle, CardDescription, CardContent,
  Badge,
  Tabs, TabsList, TabsTrigger, TabsContent,
  Progress,
  Separator,
  Accordion, AccordionItem,
  Tooltip,
  // User-provided scope (libraries)
  ...scope,
}
```

- [ ] **Step 3: Verify TypeScript compiles**

```bash
cd agent/webview && npx tsc --noEmit
```

- [ ] **Step 4: Commit**

```bash
git add agent/webview/src/sandbox-main.ts
git commit -m "feat(webview): add UI primitive components to artifact sandbox scope"
```

---

### Task 4: Create Frontend-Design Skill

**Files:**
- Create: `agent/src/main/resources/skills/frontend-design/SKILL.md`

- [ ] **Step 1: Create the skill directory and file**

Create `agent/src/main/resources/skills/frontend-design/SKILL.md`:

```markdown
---
name: frontend-design
description: Use when producing interactive visualizations or React artifacts via render_artifact. Provides component APIs, design guidelines, Tailwind patterns, and IDE-specific interaction patterns. Load this skill BEFORE calling render_artifact.
user-invocable: true
preferred-tools: [render_artifact, think]
---

# Frontend Design for Artifacts

Produce distinctive, polished, interactive React components for the artifact sandbox. Every artifact should be intentional, theme-aware, and IDE-integrated.

## Before Writing Code

1. **What is this communicating?** — architecture, data flow, comparison, status overview?
2. **Pick an aesthetic direction** — don't default to generic. Be intentional:
   - Clean and minimal (generous whitespace, subtle borders)
   - Data-dense dashboard (compact cards, metrics, badges)
   - Visual/diagrammatic (colored nodes, connecting lines, spatial layout)
3. **What's the interaction model?** — click to navigate, hover for details, tabs to switch views, accordion to expand?

## Available in Scope

Everything below is a scope variable. Use directly — NOT as imports, NOT as props.

### React
`React`, `useState`, `useEffect`, `useCallback`, `useMemo`, `useRef`, `Fragment`

### IDE Bridge
```
bridge.navigateToFile(path, line)  — click opens file in IDE editor
bridge.isDark                      — boolean, current theme
bridge.colors                      — { bg, fg, accent, success, error, warning, border, codeBg }
bridge.projectName                 — string
```

### UI Components

**Card / CardHeader / CardTitle / CardDescription / CardContent**
```jsx
<Card className="hover:shadow-lg transition-shadow" onClick={() => bridge.navigateToFile(path, line)}>
  <CardHeader>
    <CardTitle>OrderService</CardTitle>
    <CardDescription>src/main/kotlin/.../OrderService.kt</CardDescription>
  </CardHeader>
  <CardContent>
    <Badge variant="success">Healthy</Badge>
  </CardContent>
</Card>
```

**Badge** — variants: `default`, `success`, `warning`, `error`, `outline`
```jsx
<Badge variant="success">Passing</Badge>
<Badge variant="error">3 Critical</Badge>
```

**Tabs / TabsList / TabsTrigger / TabsContent**
```jsx
const [tab, setTab] = useState('overview');
<Tabs value={tab} onValueChange={setTab}>
  <TabsList>
    <TabsTrigger value="overview">Overview</TabsTrigger>
    <TabsTrigger value="details">Details</TabsTrigger>
  </TabsList>
  <TabsContent value="overview">...</TabsContent>
  <TabsContent value="details">...</TabsContent>
</Tabs>
```

**Progress** — variants: `default`, `success`, `warning`, `error`
```jsx
<Progress value={75} variant="success" />
```

**Separator**
```jsx
<Separator className="my-4" />
```

**Accordion / AccordionItem**
```jsx
<Accordion>
  <AccordionItem title="Authentication Flow" defaultOpen>Content</AccordionItem>
  <AccordionItem title="Database Access">Content</AccordionItem>
</Accordion>
```

**Tooltip**
```jsx
<Tooltip content="Click to open in editor">
  <span onClick={() => bridge.navigateToFile(path, line)} className="cursor-pointer underline">
    OrderService.kt:45
  </span>
</Tooltip>
```

### Recharts
`BarChart`, `Bar`, `LineChart`, `Line`, `PieChart`, `Pie`, `Cell`, `AreaChart`, `Area`, `XAxis`, `YAxis`, `CartesianGrid`, `Tooltip` (as `RechartsTooltip`), `Legend`, `ResponsiveContainer`

### Lucide Icons
`FileCode`, `GitBranch`, `Database`, `Shield`, `Zap`, `Server`, `Globe`, `Check`, `X`, `AlertTriangle`, `Info`, `ChevronRight`, `ChevronDown`, `Search`, `Filter`, `Layers`, `Box`, `Activity`, `Lock`, `Unlock`, `Eye`, `Code`, `Terminal`, `Settings`, `RefreshCw`

## Design Guidelines

### Typography
- **Titles:** `text-sm font-semibold` or `text-base font-bold`
- **Body:** `text-sm` (13px)
- **Secondary:** `text-xs text-[var(--fg-muted)]`
- **Code/paths:** `font-mono text-xs`

### Color
- Use theme-aware colors: `text-[var(--accent)]`, `bg-[var(--success)]/10`
- Color-code by entity type:
  - Controller/API: `accent`
  - Service/business logic: `success`
  - Repository/data: `warning`
  - External/integration: `error`
- Dominant color + sharp accent beats evenly distributed palette

### Layout
- `grid grid-cols-2 gap-3` or `grid grid-cols-3 gap-4` for card grids
- `flex flex-col gap-2` for vertical lists
- `flex items-center gap-2` for inline items with icons
- Generous padding: `p-4` for cards, `p-6` for outer container

### Interaction
- Every clickable entity: `onClick={() => bridge.navigateToFile(path, line)}`
- Hover feedback: `hover:shadow-md hover:border-[var(--accent)] transition-all duration-150`
- Cursor: `cursor-pointer` on all navigable elements
- Selected state: `border-[var(--accent)] bg-[var(--accent)]/5`

### Spacing & Borders
- Cards: `rounded-lg border border-[var(--border)]`
- Badges: `rounded-full`
- Consistent gaps: `gap-2` (tight), `gap-3` (normal), `gap-4` (spacious)

## Anti-Patterns — NEVER

- Inline `style={{}}` — use Tailwind classes
- Hardcoded colors (`#333`, `blue`) — use CSS variables
- Generic grey-on-white — make it intentional
- Walls of text — visualize, don't describe
- Missing hover states on clickable elements
- Missing `cursor-pointer` on navigable elements
- Emojis as icons — use Lucide components
- `border-gray-200` — use `border-[var(--border)]`

## Component Pattern

```jsx
export default function MyVisualization() {
  const [selected, setSelected] = useState(null);

  const data = [
    // ALL DATA INLINE — no fetch, no file reads
  ];

  return (
    <div className="p-6 space-y-4">
      <h2 className="text-base font-bold">Title</h2>
      <div className="grid grid-cols-2 gap-3">
        {data.map(item => (
          <Card
            key={item.name}
            className={`hover:shadow-md transition-all duration-150 ${
              selected === item.name ? 'border-[var(--accent)] bg-[var(--accent)]/5' : ''
            }`}
            onClick={() => {
              setSelected(item.name);
              bridge.navigateToFile(item.path, item.line);
            }}
          >
            <CardHeader>
              <CardTitle>{item.name}</CardTitle>
              <CardDescription>{item.path}:{item.line}</CardDescription>
            </CardHeader>
            <CardContent>
              <Badge variant={item.healthy ? 'success' : 'error'}>
                {item.healthy ? 'Healthy' : 'Issues'}
              </Badge>
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  );
}
```
```

- [ ] **Step 2: Commit**

```bash
git add agent/src/main/resources/skills/frontend-design/SKILL.md
git commit -m "feat(agent): add frontend-design skill for artifact visualization quality"
```

---

### Task 5: Update Tool Description and System Prompt

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RenderArtifactTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt`
- Modify: `agent/src/main/resources/agents/explorer.md`

- [ ] **Step 1: Add skill loading instruction to RenderArtifactTool description**

In `RenderArtifactTool.kt`, add this line to the end of the `description` string (before the closing `"""`):

```
Before calling render_artifact, load the frontend-design skill via use_skill("frontend-design") for component APIs and design guidelines. Use Tailwind CSS classes (not inline styles). Available UI components: Card, CardHeader, CardTitle, CardDescription, CardContent, Badge, Tabs, TabsList, TabsTrigger, TabsContent, Progress, Separator, Accordion, AccordionItem, Tooltip.
```

- [ ] **Step 2: Add artifact rules to SystemPrompt.kt capabilities**

In `SystemPrompt.kt`, in the `capabilities()` function, find the existing `render_artifact` line in the usage tips. Replace it with:

```kotlin
- render_artifact tool: produce interactive React visualizations in chat. Load the frontend-design skill first for component APIs and design guidelines. Available: Tailwind CSS, Recharts, Lucide icons, UI components (Card, Badge, Tabs, Progress, Accordion, Tooltip). All scope variables — use directly, not as props.
```

- [ ] **Step 3: Update explorer.md component contract**

In `agent/src/main/resources/agents/explorer.md`, update the "Component contract" in the "Producing Visualizations" section to list available UI components:

```markdown
**Component contract:**
- Export a default function component: `export default function MyViz() { ... }`
- `bridge` is available as a scope variable (NOT a prop — do not destructure from params)
- `bridge.navigateToFile(path, line)` — click opens file in IDE
- `bridge.isDark` and `bridge.colors` for theme-aware rendering
- React hooks (`useState`, `useEffect`, etc.) are also scope variables — use directly
- **Tailwind CSS** — use className with utility classes, not inline styles
- **UI Components** (scope variables): Card, CardHeader, CardTitle, CardDescription, CardContent, Badge, Tabs, TabsList, TabsTrigger, TabsContent, Progress, Separator, Accordion, AccordionItem, Tooltip
- Lucide icons: FileCode, GitBranch, Database, Shield, Zap, Server, Globe, etc.
- Recharts: BarChart, PieChart, LineChart, AreaChart, Tooltip, Legend, Cell, etc.
- ALL DATA MUST BE INLINE — no fetch, no file reads from inside the component
- **Load the frontend-design skill** before calling render_artifact for design guidelines
```

- [ ] **Step 4: Verify Kotlin compiles**

```bash
./gradlew :agent:compileKotlin
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RenderArtifactTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt \
       agent/src/main/resources/agents/explorer.md
git commit -m "feat(agent): update tool description and prompts with Tailwind + UI components + skill loading"
```

---

### Task 6: Build, Test, and Verify

**Files:**
- Modify: `gradle.properties` (version bump)

- [ ] **Step 1: Build webview**

```bash
cd agent/webview && npm run build
```

Verify `artifact-sandbox.html` in dist includes Tailwind script reference.

- [ ] **Step 2: Run webview tests**

```bash
cd agent/webview && npx vitest run --reporter=verbose
```

- [ ] **Step 3: Run Kotlin tests**

```bash
./gradlew :agent:test
```

- [ ] **Step 4: Full plugin build**

```bash
./gradlew clean buildPlugin
```

- [ ] **Step 5: Bump version to 0.65.0-beta**

In `gradle.properties`, change `pluginVersion = 0.64.0-beta` to `pluginVersion = 0.65.0-beta`.

- [ ] **Step 6: Commit, push, release**

```bash
git add -A
git commit -m "feat(agent): artifact design quality — Tailwind, UI primitives, frontend-design skill

Tailwind Play CDN vendored in sandbox, 10 UI primitive components in scope,
frontend-design skill with design guidelines and component APIs.
Zero runtime network — all bundled."

git push origin rewrite/lean-agent

gh release create v0.65.0-beta \
  --title "v0.65.0-beta — Artifact Design Quality" \
  --notes "Tailwind CSS + UI primitives (Card, Badge, Tabs, Progress, Accordion, Tooltip) + frontend-design skill. Produces Claude.ai-level visual quality in generated artifacts." \
  --target rewrite/lean-agent \
  build/distributions/intellij-workflow-orchestrator-0.65.0-beta.zip
```
