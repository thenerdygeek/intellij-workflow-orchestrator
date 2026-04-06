# Artifact Renderer — Interactive JSX Visualizations in Agent Chat

**Date:** 2026-04-06
**Status:** Approved (Rev 2 — added render_artifact tool)
**Scope:** Agent module — webview rendering + one new Kotlin tool + bridge method

## Summary

Add the ability for AI agents to produce live, interactive React components in the chat. The agent calls `render_artifact` (a tool alongside `attempt_completion`) when a response benefits from visualization. The webview transpiles the JSX and renders it as an interactive artifact — clickable class diagrams, animated sequence diagrams, sortable tables, charts, architecture overviews — all linked to IDE source navigation.

Two trigger paths:
1. **Tool path (primary):** Agent calls `render_artifact(title, source)` → Kotlin pushes to webview → ArtifactRenderer renders in chat
2. **Code fence path (fallback):** Agent outputs ` ```react ` code fence in markdown → MarkdownRenderer routes to ArtifactRenderer

The tool path is primary because it's structured, reliable, and gives the LLM an explicit decision point. The code fence path is the fallback for sub-agents (who communicate via `attempt_completion` text) and for cases where the LLM includes a visualization inline in its explanation.

Uses the same architecture as Claude.ai Artifacts: **react-runner + Sucrase in a sandboxed iframe**, ~300KB total, zero network dependency, everything inlined from plugin JAR resources.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Trigger Path 1: render_artifact Tool                        │
│  LLM calls render_artifact(title, source)                    │
│  → AgentLoop executes tool                                   │
│  → Tool returns ToolResult with artifact metadata             │
│  → AgentController pushes to webview via bridge               │
└────────────────────────────┬────────────────────────────────┘
                             │
│  Trigger Path 2: Code Fence (fallback)                       │
│  LLM outputs ```react code fence in markdown                 │
│  → MarkdownRenderer detects it, routes to ArtifactRenderer   │
└────────────────────────────┤
                             │ source string
┌────────────────────────────▼────────────────────────────────┐
│  ArtifactRenderer (React component in chat DOM)              │
│  - Wraps in RichBlock (header, expand, fullscreen, copy)     │
│  - Inline preview (constrained maxHeight: 400px)             │
│  - Click to expand → fullscreen dialog or editor tab         │
│  - Creates sandboxed iframe, sends source via postMessage    │
└────────────────────────────┬────────────────────────────────┘
                             │ postMessage({type:'render', source, scope})
┌────────────────────────────▼────────────────────────────────┐
│  Sandbox iframe (artifact-sandbox.html)                      │
│  - Loaded from plugin JAR resources (zero network)           │
│  - Contains: React 18 UMD + Sucrase + react-runner           │
│  - Pre-loaded libs: Lucide React icons, Recharts             │
│  - Receives JSX source, transpiles, renders                  │
│  - Sends back: height changes, errors, bridge calls          │
└────────────────────────────┬────────────────────────────────┘
                             │ postMessage({type:'bridge', action, args})
┌────────────────────────────▼────────────────────────────────┐
│  Bridge Relay (in ArtifactRenderer)                          │
│  - Listens for bridge calls from iframe                      │
│  - Routes to safe bridge functions (navigateToFile, theme)   │
│  - Blocks all unsafe calls                                   │
└─────────────────────────────────────────────────────────────┘
```

## The render_artifact Tool

### Why a Tool (Not Just Code Fences)

- **Explicit decision point:** The LLM calls a tool — structured, validated, reliable. Code fence conventions (use `react` not `jsx` not `tsx`) are fragile.
- **Structured metadata:** Title and source as separate parameters. A code fence is just raw source with no title.
- **Parallels attempt_completion:** The LLM already knows the pattern "when done, call attempt_completion." Adding "when a visual would help, call render_artifact" is natural.
- **Works alongside text:** The agent gives a text explanation AND optionally calls `render_artifact`. If artifact rendering is disabled, the user still gets the answer.

### Tool Definition (Kotlin)

```kotlin
class RenderArtifactTool : AgentTool {
    override val name = "render_artifact"
    override val description = """Render an interactive React component in the chat as a visual artifact. Use this when your response involves architecture, flows, hierarchies, relationships, or data comparisons that would be clearer as an interactive visual than plain text.

The component renders in a sandboxed iframe with access to:
- bridge.navigateToFile(path, line) — clicking opens file in IDE editor
- bridge.isDark, bridge.colors — theme-aware rendering
- Lucide React icons (FileCode, GitBranch, Database, Shield, Zap, Server, etc.)
- Recharts (BarChart, PieChart, LineChart, AreaChart, Tooltip, Legend, etc.)
- React hooks (useState, useEffect, useCallback, useMemo, useRef)

Use render_artifact when:
- Response describes 3+ entities with relationships (architecture, dependencies)
- A multi-step flow (request handling, data pipeline, auth chain)
- Data comparisons that benefit from charts (coverage, metrics, before/after)
- User explicitly asked for a visual ("show me", "diagram", "visualize")

Do NOT use when:
- Answer is a short text explanation (just respond normally)
- Data has fewer than 3 items (just list them in text)
- User asked a yes/no or simple factual question
- Text is sufficient to convey the answer

This tool works alongside your text response — give the text explanation first, then call render_artifact for the visual. If the artifact fails to render, the user still has your text."""

    override val parameters = FunctionParameters(
        properties = mapOf(
            "title" to ParameterProperty(
                type = "string",
                description = "Short title for the artifact (e.g., 'Authentication Flow', 'Module Dependencies')"
            ),
            "source" to ParameterProperty(
                type = "string",
                description = "JSX source code. Must export a default function component that receives { bridge } prop. All data must be inline."
            )
        ),
        required = listOf("title", "source")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)
}
```

### Tool Execution Flow

1. LLM calls `render_artifact(title="Auth Flow", source="export default function...")`
2. `RenderArtifactTool.execute()` validates parameters, returns `ToolResult` with:
   - `content`: `"[Artifact: Auth Flow] Rendered interactive component."` (for LLM context)
   - `artifact`: `ArtifactPayload(title, source)` (new field on ToolResult for UI rendering)
3. `AgentLoop` detects `artifact` on the tool result → calls `onArtifactRendered` callback
4. `AgentController.onArtifactRendered()` → calls `dashboard.renderArtifact(title, source)`
5. Webview receives → creates an artifact message in the chat → ArtifactRenderer mounts

### ToolResult Extension

```kotlin
data class ToolResult(
    val content: String,
    val summary: String,
    // ... existing fields ...
    val artifact: ArtifactPayload? = null,  // NEW — triggers UI artifact rendering
)

data class ArtifactPayload(
    val title: String,
    val source: String,
)
```

### Kotlin → JS Bridge Method

```kotlin
// AgentCefPanel.kt
fun renderArtifact(title: String, source: String) {
    val payload = buildJsonObject {
        put("title", title)
        put("source", source)
    }.toString()
    callJs("renderArtifact(${jsonStr(payload)})")
}
```

### JS Bridge Handler

```typescript
// jcef-bridge.ts
renderArtifact(payload: string) {
    stores?.getChatStore().addArtifact(payload);
},
```

### Chat Store Action

```typescript
// chatStore.ts
addArtifact(payload: string) {
    const { title, source } = JSON.parse(payload);
    const msgId = nextId('artifact');
    set((state) => ({
        messages: [...state.messages, {
            id: msgId,
            role: 'system',
            content: `artifact:${title}`,
            timestamp: Date.now(),
            artifact: { title, source },
        }],
    }));
},
```

### ChatView Rendering

```typescript
// ChatView.tsx — in the message rendering logic
if (msg.artifact) {
    return (
        <ErrorBoundary key={msg.id}>
            <ArtifactRenderer source={msg.artifact.source} title={msg.artifact.title} />
        </ErrorBoundary>
    );
}
```

## Code Fence Path (Fallback)

The code fence path remains for:
- **Sub-agents:** They include ` ```react ` fences in `attempt_completion` text, which the parent forwards in its markdown response
- **Inline explanations:** The LLM might include a small visualization inline in its text explanation

```typescript
// MarkdownRenderer.tsx
case 'react':
case 'artifact':
    return <ArtifactRenderer source={codeString} />;
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

### Props

```typescript
interface ArtifactRendererProps {
    source: string;
    title?: string;  // From tool path; absent for code fence path
}
```

### Lifecycle

1. ArtifactRenderer mounts with `source` (and optional `title`) prop
2. Creates iframe pointing to `artifact-sandbox.html`
3. Waits for iframe `ready` message
4. Sends `{ type: 'render', source, scope }` via postMessage
5. iframe transpiles with Sucrase, renders with react-runner
6. iframe sends `{ type: 'rendered', height }` → auto-resize iframe
7. On error → RichBlock shows error state with "Show source" button
8. On bridge call → validates against allowlist → routes to kotlinBridge

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

## Agent Prompt Guidance

### Tool Heuristics (in tool description)

The `render_artifact` tool description includes explicit heuristics:

**Use when:**
- Response describes 3+ entities with relationships (architecture, dependencies)
- A multi-step flow (request handling, data pipeline, auth chain)
- Data comparisons that benefit from charts (coverage, metrics, before/after)
- User explicitly asked for a visual ("show me", "diagram", "visualize")

**Do NOT use when:**
- Answer is a short text explanation
- Data has fewer than 3 items
- User asked a yes/no or simple factual question
- Text is sufficient

**Decision is single-LLM, prompt-driven.** No router model. The agent that researched the answer has full context to decide whether visualization helps. Failure is cheap — bad artifact shows error card, text response is still visible.

### System prompt

Add capability note in Capabilities section and reference in Subagent Delegation section.

### Explorer agent

Add "Producing Visualizations" section with when to use, component contract, available libraries, and a code example.

### Other agents (short note)

code-reviewer, architect-reviewer, performance-engineer, security-auditor: one-line note that `render_artifact` is available.

## Files Changed/Created

| File | Action | Purpose |
|------|--------|---------|
| **Kotlin (agent module)** | | |
| `agent/.../tools/builtin/RenderArtifactTool.kt` | Create | New tool — validates params, returns artifact payload |
| `agent/.../tools/ToolResult.kt` | Modify | Add `artifact: ArtifactPayload?` field |
| `agent/.../AgentService.kt` | Modify | Register render_artifact tool |
| `agent/.../ui/AgentController.kt` | Modify | Handle artifact callback, push to webview |
| `agent/.../ui/AgentCefPanel.kt` | Modify | Add `renderArtifact(title, source)` bridge method |
| `agent/.../loop/AgentLoop.kt` | Modify | Detect artifact on ToolResult, fire callback |
| `agent/.../prompt/SystemPrompt.kt` | Modify | Add artifact capability note |
| **Webview** | | |
| `agent/webview/src/components/rich/ArtifactRenderer.tsx` | Create | Main React component |
| `agent/webview/public/artifact-sandbox.html` | Create | Self-contained sandbox |
| `agent/webview/src/components/markdown/MarkdownRenderer.tsx` | Modify | Add `case 'react':` routing |
| `agent/webview/src/bridge/types.ts` | Modify | Add `'artifact'` to VisualizationType, add ArtifactState |
| `agent/webview/src/bridge/jcef-bridge.ts` | Modify | Add `renderArtifact` bridge handler |
| `agent/webview/src/stores/chatStore.ts` | Modify | Add `addArtifact` action |
| `agent/webview/src/stores/settingsStore.ts` | Modify | Add artifact visualization config |
| `agent/webview/src/components/rich/RichBlock.tsx` | Modify | Add artifact to TYPE_META |
| `agent/webview/src/components/chat/ChatView.tsx` | Modify | Render artifact messages |
| **Agent configs** | | |
| `agent/src/main/resources/agents/explorer.md` | Modify | Add visualization guidance |
| `agent/src/main/resources/agents/code-reviewer.md` | Modify | Short artifact note |
| `agent/src/main/resources/agents/architect-reviewer.md` | Modify | Short artifact note |
| `agent/src/main/resources/agents/performance-engineer.md` | Modify | Short artifact note |
| `agent/src/main/resources/agents/security-auditor.md` | Modify | Short artifact note |

## Out of Scope

- Multi-file artifacts (each artifact is self-contained)
- Artifact versioning/history
- User editing of artifacts in-chat
- Network access from artifacts (no fetch)
- Persistent state between artifacts
- Side panel rendering (future enhancement)
- Second LLM router for visualization decisions (single-LLM, prompt-driven)

## Dependencies to Add

```json
// agent/webview/package.json
"dependencies": {
  "sucrase": "^3.35.0",
  "react-runner": "^1.0.5",
  "recharts": "^2.15.0"
}
```
