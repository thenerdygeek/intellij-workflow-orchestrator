# Artifact Design Quality — Tailwind, UI Primitives, and Frontend-Design Skill

**Date:** 2026-04-06
**Status:** Approved
**Scope:** Sandbox infrastructure + skill file + prompt updates (no new Kotlin tools or React components)
**Depends on:** Artifact Renderer (v0.64.0-beta, already implemented)

## Summary

Upgrade the artifact renderer's sandbox and prompt guidance to produce Claude.ai-level visual quality. Three infrastructure additions (Tailwind CSS, UI primitives, theme mapping) plus a design skill that teaches the LLM design thinking before generating artifacts.

## What's Being Added

### 1. Tailwind CSS in Sandbox

**File:** `agent/webview/vendor/tailwind-play.js` (committed as vendor file, ~300KB)
**Modified:** `agent/webview/artifact-sandbox.html`

The Tailwind Play CDN script generates utility classes on-the-fly in the browser. Downloaded once, committed as a vendor file — zero runtime network.

Tailwind config maps to IDE theme CSS variables:

```javascript
tailwind.config = {
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        accent: 'var(--accent)',
        success: 'var(--success)',
        error: 'var(--error)',
        warning: 'var(--warning)',
        border: 'var(--border)',
        muted: 'var(--fg-muted)',
        'code-bg': 'var(--code-bg)',
      }
    }
  }
}
```

The dark mode class is toggled on `<html>` when theme messages arrive.

### 2. UI Primitive Components in Scope

**Modified:** `agent/webview/src/sandbox-main.ts`

~150 lines of simple Tailwind-styled React components injected into the react-runner scope. The LLM uses them directly as scope variables (not imports).

#### Component APIs

**Card / CardHeader / CardContent**
```jsx
<Card className="hover:shadow-lg transition-shadow">
  <CardHeader>
    <CardTitle>Title</CardTitle>
    <CardDescription>Subtitle</CardDescription>
  </CardHeader>
  <CardContent>
    Body content
  </CardContent>
</Card>
```

**Badge**
```jsx
<Badge variant="default">Label</Badge>
// variants: default, success, warning, error, outline
```

**Tabs / TabsList / TabsTrigger / TabsContent**
```jsx
const [activeTab, setActiveTab] = useState('overview');
<Tabs value={activeTab} onValueChange={setActiveTab}>
  <TabsList>
    <TabsTrigger value="overview">Overview</TabsTrigger>
    <TabsTrigger value="details">Details</TabsTrigger>
  </TabsList>
  <TabsContent value="overview">Overview content</TabsContent>
  <TabsContent value="details">Details content</TabsContent>
</Tabs>
```

**Progress**
```jsx
<Progress value={75} className="h-2" />
// Optional: variant="success" | "warning" | "error"
```

**Separator**
```jsx
<Separator className="my-4" />
```

**Accordion / AccordionItem**
```jsx
<Accordion>
  <AccordionItem title="Section 1">Content 1</AccordionItem>
  <AccordionItem title="Section 2">Content 2</AccordionItem>
</Accordion>
```

**Tooltip**
```jsx
<Tooltip content="Click to navigate to source">
  <button onClick={() => bridge.navigateToFile(path, line)}>OrderService</button>
</Tooltip>
```

All components:
- Use Tailwind classes for styling
- Respect `dark:` variants automatically via Tailwind's dark mode
- Accept optional `className` prop for customization
- Use CSS variables mapped from the IDE theme

### 3. Frontend-Design Skill

**File:** `agent/src/main/resources/skills/frontend-design/SKILL.md`

Ported from Anthropic's open-source skill, adapted for IDE artifact context.

#### Content Structure

**Design Thinking (before coding):**
- Identify purpose: what is this visualization communicating?
- Pick an intentional aesthetic direction (not generic)
- Identify what makes it distinctive

**Available Components:**
- Complete list of scope variables with their APIs
- Recharts components and usage patterns
- Lucide icon names organized by category
- bridge API reference
- "These are scope variables — use directly, NOT as props"

**Design Guidelines:**
- Typography: `font-semibold`/`font-bold` for hierarchy, `text-xs` for secondary, `text-sm` for body
- Color: theme-aware (`text-accent`, `bg-success/10`), dominant + sharp accent pattern
- Spacing: consistent (`p-4`, `p-6`, `gap-3`, `gap-4`), generous negative space
- Layout: `grid` for 3+ items, `flex` for 2 items or inline
- Hover/transitions: `transition-all duration-150`, `hover:shadow-md`, `hover:border-accent`
- Borders: `rounded-lg` for cards, `rounded-full` for badges
- Dark mode: use Tailwind `dark:` variants, `bridge.isDark` for conditional logic

**IDE-Specific Patterns:**
- Every clickable entity calls `bridge.navigateToFile(path, line)`
- Color-code by type: controller=accent, service=success, repository=warning, external=error
- File paths shown in `text-xs text-muted` below entity names
- Hover cursor on navigable elements

**Anti-patterns (NEVER):**
- Inline styles (use Tailwind classes)
- Generic grey-on-white layouts
- Walls of text — visualize, don't describe
- Missing hover states on clickable elements
- Hardcoded colors — use theme variables
- Emojis as icons — use Lucide

#### Trigger

The `render_artifact` tool description says: "Before calling render_artifact, load the frontend-design skill for component APIs and design guidelines."

The skill also appears in the available skills list with description: "Use when producing interactive visualizations or React artifacts. Provides component APIs, design guidelines, and IDE-specific patterns."

### 4. System Prompt Artifact Rules

**Modified:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt`

~8 lines added to the capabilities section:

```
# Artifact Visualizations
When producing interactive visualizations via render_artifact:
- Load the frontend-design skill first for component APIs and design guidelines
- Available in sandbox: React 18, Tailwind CSS, Recharts, Lucide React icons
- Available UI components (scope variables): Card, CardHeader, CardContent, CardTitle,
  CardDescription, Badge, Tabs, TabsList, TabsTrigger, TabsContent, Progress,
  Separator, Accordion, AccordionItem, Tooltip
- bridge.navigateToFile(path, line) for IDE navigation
- No fetch, no localStorage, no external scripts — all data inline
```

### 5. Explorer Prompt Update

**Modified:** `agent/src/main/resources/agents/explorer.md`

Update the "Component contract" in the "Producing Visualizations" section to list the available UI components and mention Tailwind classes.

## Files Changed/Created

| File | Action | Purpose |
|------|--------|---------|
| `agent/webview/vendor/tailwind-play.js` | Create | Tailwind Play CDN (vendor, ~300KB) |
| `agent/webview/artifact-sandbox.html` | Modify | Add Tailwind script + config with theme mapping |
| `agent/webview/src/sandbox-main.ts` | Modify | Add UI primitive components to scope |
| `agent/src/main/resources/skills/frontend-design/SKILL.md` | Create | Design skill with guidelines + component APIs |
| `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt` | Modify | Add artifact rules to capabilities |
| `agent/src/main/resources/agents/explorer.md` | Modify | Update component contract |

## Network Dependencies

**None at runtime.** Everything is bundled:

| Piece | Bundled How | Network at Runtime |
|-------|-----------|-------------------|
| Tailwind Play CDN | Committed as `vendor/tailwind-play.js`, inlined in sandbox HTML | No |
| UI primitives | Defined in `sandbox-main.ts`, bundled by Vite | No |
| React, react-runner, Recharts, Lucide | Bundled by Vite from `node_modules` | No |
| Skill file | In plugin JAR resources | No |

## Out of Scope

- Custom web fonts (Tailwind's default font stack is sufficient for IDE context)
- D3.js, Three.js, Plotly (can add later)
- Multi-file artifacts
- Persistent storage API
- New Kotlin tools or bridge methods
- New React components in the main webview
