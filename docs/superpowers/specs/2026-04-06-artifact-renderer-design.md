# Artifact Renderer — Interactive JSX Visualizations in Agent Chat

**Date:** 2026-04-06
**Status:** Approved
**Scope:** Agent module webview only (no Kotlin backend changes)

## Summary

Add the ability for AI agents to output live, interactive React components in the chat. When the LLM writes a `react` code fence, the webview transpiles and renders it as an interactive artifact — clickable class diagrams, animated sequence diagrams, sortable tables, charts, architecture overviews — all linked to IDE source navigation.

Uses the same architecture as Claude.ai Artifacts: **react-runner + Sucrase in a sandboxed iframe**, ~300KB total, zero network dependency, everything inlined from plugin JAR resources.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Layer 1: LLM Output                                        │
│  Agent outputs ```react code fence with JSX source           │
│  MarkdownRenderer detects it, routes to ArtifactRenderer     │
└────────────────────────────┬────────────────────────────────┘
                             │ source string
┌────────────────────────────▼────────────────────────────────┐
│  Layer 2: ArtifactRenderer (React component in chat DOM)     │
│  - Wraps in RichBlock (header, expand, fullscreen, copy)     │
│  - Inline preview (constrained maxHeight: 400px)             │
│  - Click to expand → fullscreen dialog or editor tab         │
│  - Creates sandboxed iframe, sends source via postMessage    │
└────────────────────────────┬────────────────────────────────┘
                             │ postMessage({type:'render', source, scope})
┌────────────────────────────▼────────────────────────────────┐
│  Layer 3: Sandbox iframe (artifact-sandbox.html)             │
│  - Loaded from plugin JAR resources (zero network)           │
│  - Contains: React 18 UMD + Sucrase + react-runner           │
│  - Pre-loaded libs: Lucide React icons, Recharts             │
│  - Receives JSX source, transpiles, renders                  │
│  - Sends back: height changes, errors, bridge calls          │
└────────────────────────────┬────────────────────────────────┘
                             │ postMessage({type:'bridge', action, args})
┌────────────────────────────▼────────────────────────────────┐
│  Layer 4: Bridge Relay (in ArtifactRenderer)                 │
│  - Listens for bridge calls from iframe                      │
│  - Routes to safe bridge functions (navigateToFile, theme)   │
│  - Blocks all unsafe calls                                   │
└─────────────────────────────────────────────────────────────┘
```

## Sandbox HTML

Single self-contained HTML file: `agent/src/main/resources/webview/dist/artifact-sandbox.html`

### Contents (~300KB gzipped, all inlined)

| Library | Purpose | Size (gzipped) |
|---------|---------|----------------|
| React 18 UMD | react + react-dom production builds | ~42KB |
| Sucrase | JSX + TypeScript transpilation | ~90KB |
| react-runner | Transpile → eval → render thin wrapper | ~5KB |
| Lucide React | Icon library (LLM generates UIs with icons) | ~50KB (tree-shaken subset) |
| Recharts | Charts (bar, line, pie, area, scatter) | ~100KB |
| Error Boundary | Catches render errors, reports via postMessage | <1KB |
| Theme CSS + listener | CSS variables injected from parent | <1KB |

### Communication Protocol

**Parent → iframe:**

| Message | Fields | When |
|---------|--------|------|
| `render` | `source: string`, `scope: Record<string, any>` | On mount, on source change |
| `theme` | `isDark: boolean`, `colors: Record<string, string>` | On theme change |

**iframe → Parent:**

| Message | Fields | When |
|---------|--------|------|
| `ready` | — | iframe loaded, ready to receive |
| `rendered` | `height: number` | After successful render (for auto-resize) |
| `error` | `phase: 'transpile'\|'runtime'\|'render'`, `message: string`, `line?: number` | On any error |
| `bridge` | `action: string`, `args: any[]` | Component calls bridge function |
| `console` | `level: 'log'\|'warn'\|'error'`, `args: any[]` | Console output forwarding |

### Scope (what the LLM's component can access)

```typescript
const scope = {
  // React hooks (always available)
  React, useState, useEffect, useCallback, useMemo, useRef, Fragment,

  // IDE bridge (curated, safe, read-only)
  bridge: {
    navigateToFile(path: string, line?: number): void,
    isDark: boolean,
    colors: {
      bg: string, fg: string, accent: string, success: string,
      error: string, warning: string, border: string, codeBg: string,
    },
    projectName: string,
  },

  // Pre-loaded libraries
  ...lucideIcons,   // FileCode, GitBranch, Database, Shield, Zap, etc.
  ...recharts,      // BarChart, PieChart, LineChart, AreaChart, Tooltip, etc.
};
```

### Security

- iframe: `sandbox="allow-scripts"` (no `allow-same-origin` — opaque origin)
- No `fetch()`, no external network access
- No DOM access outside the component tree
- Bridge call allowlist enforced on parent side:
  ```typescript
  const ALLOWED_BRIDGE_ACTIONS = new Set(['navigateToFile']);
  ```
- All other bridge calls silently dropped
- react-runner uses `new Function()` (not `eval()`) with frozen prototype chain

## ArtifactRenderer Component

**File:** `agent/webview/src/components/rich/ArtifactRenderer.tsx`

### Lifecycle

1. MarkdownRenderer sees ` ```react ` code fence
2. ArtifactRenderer mounts with `source` prop
3. Creates iframe pointing to `artifact-sandbox.html`
4. Waits for iframe `ready` message
5. Sends `{ type: 'render', source, scope }` via postMessage
6. iframe transpiles with Sucrase, renders with react-runner
7. iframe sends `{ type: 'rendered', height }` → auto-resize iframe
8. On error → RichBlock shows error state with "Show source" button
9. On bridge call → validates against allowlist → routes to kotlinBridge

### States

| State | User Sees |
|-------|-----------|
| Loading | RichBlock shimmer skeleton |
| Rendered | Live interactive component, constrained to maxHeight with "Show more" |
| Error (transpile) | Error card: syntax error + line number + "Show source" |
| Error (runtime) | Error card: runtime error + stack trace + "Show source" |
| Error (render) | Error boundary inside iframe, error forwarded to parent |
| Disabled | Raw JSX as syntax-highlighted code block (if user disables artifacts in settings) |

### RichBlock Actions

- **Copy source** — copies the JSX to clipboard
- **Expand/collapse** — toggle height constraint
- **Open in editor tab** — opens live preview in IntelliJ editor tab
- **Fullscreen** — dialog with full-viewport rendering

### Auto-resize

iframe sends `document.body.scrollHeight` after every render. ArtifactRenderer sets iframe height to match — no inner scrollbar. RichBlock's maxHeight constraint handles overflow with "Show more" button.

## MarkdownRenderer Integration

**File:** `agent/webview/src/components/markdown/MarkdownRenderer.tsx`

One new case in the existing code fence switch:

```typescript
case 'react':
case 'artifact':
  return <ArtifactRenderer source={codeString} />;
```

### Streaming Behavior

While the LLM is still generating the JSX (fence unclosed), the existing `closeOpenFences()` function shows it as a syntax-highlighted code block. Once the fence closes, it renders as a live component.

### Settings

Add to `VisualizationType` union and settings store:

```typescript
artifact: { enabled: true, autoRender: true, defaultExpanded: false, maxHeight: 400 }
```

Users can disable artifact rendering (falls back to raw JSX code block).

## LLM Component Contract

Components must follow this contract:

```jsx
// Must export a default function component
// Receives { bridge } prop
export default function MyVisualization({ bridge }) {
  // bridge.navigateToFile(path, line) — open file in IDE
  // bridge.isDark — boolean, current theme
  // bridge.colors — { bg, fg, accent, success, error, warning, border, codeBg }
  // bridge.projectName — string

  // Lucide icons available: FileCode, GitBranch, Database, Shield, etc.
  // Recharts available: BarChart, PieChart, LineChart, Tooltip, Legend, etc.
  // React hooks available: useState, useEffect, useCallback, useMemo, useRef

  // ALL DATA MUST BE INLINE — no fetch(), no file reads
  return (
    <div>...</div>
  );
}
```

## Agent Prompt Updates

### System prompt (SystemPrompt.kt Capabilities section)

Add a line noting that `react` code fences render as live interactive components with IDE navigation.

### Explorer agent

Add a "Visualization" section with:
- When to produce artifacts (architecture, flows, hierarchies, comparisons)
- When NOT to (simple answers, short lists)
- The component contract (default export, bridge prop, inline data)
- Available libraries (Lucide icons, Recharts)
- Pattern guidance (sequence vs class vs flowchart vs chart)

### Other agents (short note)

code-reviewer, architect-reviewer, performance-engineer, security-auditor:
"You can output `react` code fences for interactive visualizations. Component receives `{ bridge }` with `navigateToFile(path, line)`, theme colors, Lucide icons, and Recharts."

## Files Changed/Created

| File | Action | Purpose |
|------|--------|---------|
| `agent/webview/src/components/rich/ArtifactRenderer.tsx` | Create | Main React component |
| `agent/src/main/resources/webview/dist/artifact-sandbox.html` | Create | Self-contained sandbox |
| `agent/webview/src/components/markdown/MarkdownRenderer.tsx` | Modify | Add `case 'react':` routing |
| `agent/webview/src/bridge/types.ts` | Modify | Add `'artifact'` to VisualizationType |
| `agent/webview/src/stores/settingsStore.ts` | Modify | Add artifact visualization config |
| `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt` | Modify | Add artifact capability note |
| `agent/src/main/resources/agents/explorer.md` | Modify | Add visualization guidance |
| `agent/src/main/resources/agents/code-reviewer.md` | Modify | Short artifact note |
| `agent/src/main/resources/agents/architect-reviewer.md` | Modify | Short artifact note |
| `agent/src/main/resources/agents/performance-engineer.md` | Modify | Short artifact note |
| `agent/src/main/resources/agents/security-auditor.md` | Modify | Short artifact note |

**No Kotlin backend changes.** Zero changes to AgentService, AgentController, AgentCefPanel, SpawnAgentTool, or any tool.

## Out of Scope

- Multi-file artifacts (each code fence is self-contained)
- Artifact versioning/history
- User editing of artifacts in-chat
- Network access from artifacts (no fetch)
- Persistent state between artifacts
- New Kotlin bridge methods
- New agent tools
- Side panel rendering (future enhancement)

## Dependencies to Add

```json
// agent/webview/package.json
"dependencies": {
  "sucrase": "^3.35.0",      // JSX + TypeScript transpilation
  "react-runner": "^1.0.5"   // Transpile → render thin wrapper
}
// Lucide React and Recharts are bundled INTO artifact-sandbox.html only,
// not into the main webview bundle
```
