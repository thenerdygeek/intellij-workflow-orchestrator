# Artifact Renderer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable AI agents to output live, interactive React components in chat via `react` code fences — transpiled with Sucrase, rendered in a sandboxed iframe with IDE navigation.

**Architecture:** react-runner + Sucrase in a `sandbox="allow-scripts"` iframe. Parent sends JSX source via postMessage, iframe transpiles and renders, sends back height/errors/bridge calls. ArtifactRenderer wraps in RichBlock for expand/fullscreen/copy.

**Tech Stack:** React 18, Sucrase 3.x, react-runner 1.x, Recharts (bundled in sandbox), Lucide React (already in project). Vite build for main webview. Sandbox HTML is a standalone file with all libs inlined.

**Spec:** `docs/superpowers/specs/2026-04-06-artifact-renderer-design.md`

---

### Task 1: Install Dependencies

**Files:**
- Modify: `agent/webview/package.json`

- [ ] **Step 1: Add sucrase and react-runner**

```bash
cd agent/webview && npm install sucrase@^3.35.0 react-runner@^1.0.5 recharts@^2.15.0
```

Note: `lucide-react` is already installed. `recharts` is added here — it will be used inside the sandbox HTML but we install it to get the UMD build and types.

- [ ] **Step 2: Verify install**

```bash
cd agent/webview && node -e "require('sucrase'); require('react-runner'); console.log('OK')"
```

Expected: `OK`

- [ ] **Step 3: Commit**

```bash
cd agent/webview && git add package.json package-lock.json
git commit -m "deps(webview): add sucrase, react-runner, recharts for artifact renderer"
```

---

### Task 2: Create the Sandbox HTML

**Files:**
- Create: `agent/webview/public/artifact-sandbox.html`

This is the self-contained HTML file that runs inside the iframe. It contains React 18, Sucrase, react-runner, and pre-loaded libraries — all inlined. Zero network dependency.

- [ ] **Step 1: Write the sandbox HTML**

Create `agent/webview/public/artifact-sandbox.html`:

```html
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <style>
    :root { color-scheme: light; }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
      font-size: 13px;
      color: var(--fg, #333);
      background: transparent;
      overflow: hidden;
    }
    #root { padding: 12px; }
    #error-display {
      display: none;
      padding: 12px;
      border-radius: 6px;
      background: rgba(239, 68, 68, 0.1);
      border: 1px solid rgba(239, 68, 68, 0.3);
      color: #ef4444;
      font-family: monospace;
      font-size: 12px;
      white-space: pre-wrap;
      word-break: break-word;
    }
  </style>
</head>
<body>
  <div id="root"></div>
  <div id="error-display"></div>

  <!--
    Libraries are loaded via <script> tags that Vite copies from node_modules
    at build time. For now we use CDN-free dynamic imports inside the script.
    The actual inlining of UMD builds will be done in Task 3 (build integration).
    This file bootstraps the protocol and rendering logic.
  -->
  <script type="module">
    // ── State ──
    let reactRoot = null;
    let React = null;
    let ReactDOM = null;
    let ReactDOMClient = null;
    let runner = null;
    let currentScope = {};
    let bridgeState = { isDark: false, colors: {}, projectName: '' };

    // ── Error display ──
    function showError(phase, message, line) {
      const el = document.getElementById('error-display');
      const rootEl = document.getElementById('root');
      el.style.display = 'block';
      rootEl.style.display = 'none';
      el.textContent = `[${phase}] ${line ? `Line ${line}: ` : ''}${message}`;
      sendToParent({ type: 'error', phase, message, line });
    }

    function clearError() {
      const el = document.getElementById('error-display');
      const rootEl = document.getElementById('root');
      el.style.display = 'none';
      rootEl.style.display = 'block';
    }

    // ── Console interception ──
    const originalConsole = { log: console.log, warn: console.warn, error: console.error };
    ['log', 'warn', 'error'].forEach(level => {
      console[level] = (...args) => {
        originalConsole[level](...args);
        sendToParent({ type: 'console', level, args: args.map(a => String(a)) });
      };
    });

    // ── Communication ──
    function sendToParent(msg) {
      try { window.parent.postMessage(msg, '*'); } catch {}
    }

    function reportHeight() {
      const height = document.body.scrollHeight;
      sendToParent({ type: 'rendered', height });
    }

    // ── Error Boundary (class component as string, compiled at init) ──
    let ErrorBoundaryClass = null;

    function createErrorBoundary(R) {
      class EB extends R.Component {
        constructor(props) {
          super(props);
          this.state = { hasError: false, error: null };
        }
        static getDerivedStateFromError(error) {
          return { hasError: true, error };
        }
        componentDidCatch(error, info) {
          showError('render', error.message);
        }
        render() {
          if (this.state.hasError) {
            return R.createElement('div', {
              style: { padding: 12, color: '#ef4444', fontSize: 12, fontFamily: 'monospace' }
            }, `Render error: ${this.state.error?.message || 'Unknown'}`);
          }
          return this.props.children;
        }
      }
      return EB;
    }

    // ── Initialization ──
    async function init() {
      try {
        // Import React and react-runner
        // These are resolved by the Vite build (see Task 3)
        const reactMod = await import('react');
        const reactDomMod = await import('react-dom');
        const reactDomClientMod = await import('react-dom/client');
        const runnerMod = await import('react-runner');

        React = reactMod;
        ReactDOM = reactDomMod;
        ReactDOMClient = reactDomClientMod;
        runner = runnerMod;

        ErrorBoundaryClass = createErrorBoundary(React);

        // Create React root
        const rootEl = document.getElementById('root');
        reactRoot = ReactDOMClient.createRoot(rootEl);

        sendToParent({ type: 'ready' });
      } catch (err) {
        showError('init', `Failed to initialize: ${err.message}`);
      }
    }

    // ── Render ──
    function renderComponent(source, scope) {
      if (!reactRoot || !runner || !React) {
        showError('runtime', 'Sandbox not initialized');
        return;
      }

      clearError();

      try {
        // Build the full scope with React globals + bridge + libraries
        const fullScope = {
          // React hooks
          React,
          useState: React.useState,
          useEffect: React.useEffect,
          useCallback: React.useCallback,
          useMemo: React.useMemo,
          useRef: React.useRef,
          Fragment: React.Fragment,
          // Bridge
          bridge: {
            navigateToFile(path, line) {
              sendToParent({ type: 'bridge', action: 'navigateToFile', args: [path, line] });
            },
            isDark: bridgeState.isDark,
            colors: bridgeState.colors,
            projectName: bridgeState.projectName,
          },
          // User-provided scope (libraries injected by parent)
          ...scope,
        };

        // Use react-runner's useRunner or direct Runner component
        const element = React.createElement(runner.Runner, {
          code: source,
          scope: fullScope,
          onRendered: (error) => {
            if (error) {
              showError('runtime', error.message);
            } else {
              // Report height after a frame to let layout settle
              requestAnimationFrame(() => {
                reportHeight();
              });
            }
          }
        });

        reactRoot.render(
          React.createElement(ErrorBoundaryClass, null, element)
        );
      } catch (err) {
        showError('transpile', err.message);
      }
    }

    // ── Message listener ──
    window.addEventListener('message', (e) => {
      const data = e.data;
      if (!data || typeof data !== 'object' || !data.type) return;

      switch (data.type) {
        case 'render':
          renderComponent(data.source, data.scope || {});
          break;

        case 'theme':
          bridgeState.isDark = data.isDark;
          bridgeState.colors = data.colors || {};
          bridgeState.projectName = data.projectName || '';
          // Apply CSS variables
          const root = document.documentElement;
          root.style.colorScheme = data.isDark ? 'dark' : 'light';
          if (data.colors) {
            Object.entries(data.colors).forEach(([key, value]) => {
              root.style.setProperty(`--${key}`, value);
            });
          }
          break;
      }
    });

    // ── Height observer ──
    const resizeObserver = new ResizeObserver(() => reportHeight());
    resizeObserver.observe(document.getElementById('root'));

    // ── Start ──
    init();
  </script>
</body>
</html>
```

- [ ] **Step 2: Verify the file is well-formed HTML**

Open the file in a browser (it won't render components yet — it needs to be served through Vite for the imports to resolve). Check that the console shows no syntax errors.

- [ ] **Step 3: Commit**

```bash
git add agent/webview/public/artifact-sandbox.html
git commit -m "feat(webview): add artifact sandbox HTML with react-runner protocol"
```

---

### Task 3: Vite Build Integration for Sandbox

**Files:**
- Modify: `agent/webview/vite.config.ts`

The sandbox HTML needs its own entry point so Vite resolves and bundles the `import('react')`, `import('react-runner')` etc. inside it into a self-contained file.

- [ ] **Step 1: Read current vite.config.ts**

Read `agent/webview/vite.config.ts` to understand the current multi-entry build setup.

- [ ] **Step 2: Add sandbox as a build entry**

Add `artifact-sandbox` as an additional entry in the `build.rollupOptions.input` config:

```typescript
input: {
  main: resolve(__dirname, 'index.html'),
  'plan-editor': resolve(__dirname, 'plan-editor.html'),
  'artifact-sandbox': resolve(__dirname, 'public/artifact-sandbox.html'),
},
```

Also add a manual chunk for recharts:

```typescript
manualChunks: {
  // ... existing chunks ...
  recharts: ['recharts'],
},
```

- [ ] **Step 3: Build and verify**

```bash
cd agent/webview && npm run build
```

Verify that `../src/main/resources/webview/dist/artifact-sandbox.html` is generated and contains the bundled imports.

- [ ] **Step 4: Commit**

```bash
git add agent/webview/vite.config.ts
git commit -m "build(webview): add artifact-sandbox entry point to Vite build"
```

---

### Task 4: Add Artifact to VisualizationType and Settings

**Files:**
- Modify: `agent/webview/src/bridge/types.ts`
- Modify: `agent/webview/src/stores/settingsStore.ts`

- [ ] **Step 1: Add 'artifact' to VisualizationType**

In `agent/webview/src/bridge/types.ts`, update the type:

```typescript
export type VisualizationType = 'mermaid' | 'chart' | 'flow' | 'math' | 'diff' | 'interactiveHtml' | 'table' | 'output' | 'progress' | 'timeline' | 'image' | 'artifact';
```

- [ ] **Step 2: Add artifact config to settings defaults**

In `agent/webview/src/stores/settingsStore.ts`, add to the defaults object:

```typescript
artifact: { enabled: true, autoRender: true, defaultExpanded: false, maxHeight: 400 },
```

- [ ] **Step 3: Add artifact to RichBlock TYPE_META**

In `agent/webview/src/components/rich/RichBlock.tsx`, add:

```typescript
artifact: { icon: '\u2B22', label: 'Interactive' },
```

- [ ] **Step 4: Commit**

```bash
git add agent/webview/src/bridge/types.ts agent/webview/src/stores/settingsStore.ts agent/webview/src/components/rich/RichBlock.tsx
git commit -m "feat(webview): add artifact visualization type to settings and RichBlock"
```

---

### Task 5: Create ArtifactRenderer Component

**Files:**
- Create: `agent/webview/src/components/rich/ArtifactRenderer.tsx`

This is the core component. It creates the sandbox iframe, manages postMessage communication, handles errors, and relays bridge calls.

- [ ] **Step 1: Write ArtifactRenderer.tsx**

Create `agent/webview/src/components/rich/ArtifactRenderer.tsx`:

```tsx
import { useEffect, useRef, useState, useCallback, useMemo } from 'react';
import { RichBlock } from './RichBlock';
import { useThemeStore } from '@/stores/themeStore';
import { kotlinBridge } from '@/bridge/jcef-bridge';

// ── Bridge action allowlist ──
const ALLOWED_BRIDGE_ACTIONS = new Set(['navigateToFile']);

// ── Sandbox URL (Vite resolves from build output) ──
const SANDBOX_URL = new URL('/artifact-sandbox.html', import.meta.url).href;

// ── Component ──

interface ArtifactRendererProps {
  source: string;
}

export function ArtifactRenderer({ source }: ArtifactRendererProps) {
  const isDark = useThemeStore((s) => s.isDark);
  const cssVariables = useThemeStore((s) => s.cssVariables);
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const [iframeHeight, setIframeHeight] = useState(200);
  const readyRef = useRef(false);
  const pendingRenderRef = useRef<string | null>(null);

  // Theme colors for bridge
  const bridgeColors = useMemo(() => ({
    bg: cssVariables['bg'] ?? (isDark ? '#1e1e1e' : '#ffffff'),
    fg: cssVariables['fg'] ?? (isDark ? '#d4d4d4' : '#333333'),
    accent: cssVariables['accent'] ?? '#6366f1',
    success: cssVariables['success'] ?? '#22c55e',
    error: cssVariables['error'] ?? '#ef4444',
    warning: cssVariables['warning'] ?? '#eab308',
    border: cssVariables['border'] ?? (isDark ? '#444444' : '#dddddd'),
    codeBg: cssVariables['code-bg'] ?? (isDark ? '#1a1a2e' : '#f5f5f5'),
  }), [cssVariables, isDark]);

  // Send theme to iframe
  const sendTheme = useCallback(() => {
    iframeRef.current?.contentWindow?.postMessage({
      type: 'theme',
      isDark,
      colors: bridgeColors,
      projectName: '', // Will be populated if needed
    }, '*');
  }, [isDark, bridgeColors]);

  // Send render command to iframe
  const sendRender = useCallback((src: string) => {
    if (!readyRef.current) {
      pendingRenderRef.current = src;
      return;
    }
    iframeRef.current?.contentWindow?.postMessage({
      type: 'render',
      source: src,
      scope: {}, // Libraries are pre-loaded in sandbox
    }, '*');
  }, []);

  // Listen for messages from iframe
  useEffect(() => {
    const handler = (e: MessageEvent) => {
      if (!iframeRef.current || e.source !== iframeRef.current.contentWindow) return;
      const data = e.data;
      if (!data || typeof data !== 'object' || typeof data.type !== 'string') return;

      switch (data.type) {
        case 'ready':
          readyRef.current = true;
          sendTheme();
          // Send pending render if we were waiting
          if (pendingRenderRef.current) {
            sendRender(pendingRenderRef.current);
            pendingRenderRef.current = null;
          } else {
            sendRender(source);
          }
          break;

        case 'rendered':
          setIsLoading(false);
          setError(null);
          if (typeof data.height === 'number' && data.height > 0) {
            setIframeHeight(Math.max(60, data.height));
          }
          break;

        case 'error':
          setIsLoading(false);
          const errorMsg = data.line
            ? `Line ${data.line}: ${data.message}`
            : data.message;
          setError(new Error(`[${data.phase}] ${errorMsg}`));
          break;

        case 'bridge':
          // Relay allowed bridge calls to Kotlin
          if (ALLOWED_BRIDGE_ACTIONS.has(data.action)) {
            const fn = (kotlinBridge as any)[data.action];
            if (typeof fn === 'function') {
              fn(...(data.args || []));
            }
          }
          break;

        case 'console':
          // Forward console output (optional: could display in UI)
          if (data.level === 'error') {
            console.warn('[Artifact]', ...(data.args || []));
          }
          break;
      }
    };

    window.addEventListener('message', handler);
    return () => window.removeEventListener('message', handler);
  }, [source, sendTheme, sendRender]);

  // Re-send theme on theme change
  useEffect(() => {
    if (readyRef.current) sendTheme();
  }, [sendTheme]);

  // Re-render on source change
  useEffect(() => {
    if (readyRef.current) {
      setIsLoading(true);
      setError(null);
      sendRender(source);
    }
  }, [source, sendRender]);

  // Retry handler
  const handleRetry = useCallback(() => {
    setIsLoading(true);
    setError(null);
    readyRef.current = false;
    // Force iframe reload by changing key
    const iframe = iframeRef.current;
    if (iframe) {
      iframe.src = iframe.src;
    }
  }, []);

  return (
    <RichBlock
      type="artifact"
      source={source}
      isLoading={isLoading}
      error={error}
      onRetry={handleRetry}
    >
      <iframe
        ref={iframeRef}
        src={SANDBOX_URL}
        sandbox="allow-scripts"
        title="Interactive artifact"
        className="w-full border-0"
        style={{
          height: `${iframeHeight}px`,
          transition: 'height 200ms ease-out',
          background: 'transparent',
        }}
      />
    </RichBlock>
  );
}
```

- [ ] **Step 2: Verify the component compiles**

```bash
cd agent/webview && npx tsc --noEmit
```

Expected: No type errors in ArtifactRenderer.tsx.

- [ ] **Step 3: Commit**

```bash
git add agent/webview/src/components/rich/ArtifactRenderer.tsx
git commit -m "feat(webview): add ArtifactRenderer component with sandbox iframe and bridge relay"
```

---

### Task 6: Wire ArtifactRenderer into MarkdownRenderer

**Files:**
- Modify: `agent/webview/src/components/markdown/MarkdownRenderer.tsx`

- [ ] **Step 1: Add import**

At the top of `MarkdownRenderer.tsx`, add:

```typescript
import { ArtifactRenderer } from '@/components/rich/ArtifactRenderer';
```

- [ ] **Step 2: Add case to code fence switch**

In the `switch (language)` block, add before the default case:

```typescript
case 'react':
case 'artifact':
  return <ArtifactRenderer source={codeString} />;
```

- [ ] **Step 3: Build and verify**

```bash
cd agent/webview && npm run build
```

Expected: Build succeeds, `artifact-sandbox.html` in dist, main bundle includes ArtifactRenderer.

- [ ] **Step 4: Commit**

```bash
git add agent/webview/src/components/markdown/MarkdownRenderer.tsx
git commit -m "feat(webview): route react/artifact code fences to ArtifactRenderer"
```

---

### Task 7: Test the Renderer End-to-End

**Files:**
- Create: `agent/webview/src/__tests__/ArtifactRenderer.test.tsx`

- [ ] **Step 1: Write integration test**

Create `agent/webview/src/__tests__/ArtifactRenderer.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { ArtifactRenderer } from '@/components/rich/ArtifactRenderer';

// Mock the theme store
vi.mock('@/stores/themeStore', () => ({
  useThemeStore: (selector: any) => {
    const state = {
      isDark: false,
      cssVariables: { bg: '#ffffff', fg: '#333333', accent: '#6366f1' },
    };
    return selector(state);
  },
}));

// Mock the kotlin bridge
vi.mock('@/bridge/jcef-bridge', () => ({
  kotlinBridge: {
    navigateToFile: vi.fn(),
  },
}));

describe('ArtifactRenderer', () => {
  it('renders an iframe with sandbox attribute', () => {
    const source = 'export default function App() { return <div>Hello</div>; }';
    const { container } = render(<ArtifactRenderer source={source} />);
    
    const iframe = container.querySelector('iframe');
    expect(iframe).toBeTruthy();
    expect(iframe?.getAttribute('sandbox')).toBe('allow-scripts');
    expect(iframe?.title).toBe('Interactive artifact');
  });

  it('shows loading state initially', () => {
    const source = 'export default function App() { return <div>Hello</div>; }';
    render(<ArtifactRenderer source={source} />);
    
    // RichBlock shows loading skeleton when isLoading=true
    // The shimmer animation div should be present
    expect(document.querySelector('[aria-label="Loading visualization"]')).toBeTruthy();
  });

  it('does not allow same-origin in sandbox', () => {
    const source = 'export default function App() { return <div>Test</div>; }';
    const { container } = render(<ArtifactRenderer source={source} />);
    
    const iframe = container.querySelector('iframe');
    const sandbox = iframe?.getAttribute('sandbox') ?? '';
    expect(sandbox).not.toContain('allow-same-origin');
  });
});
```

- [ ] **Step 2: Run tests**

```bash
cd agent/webview && npm test
```

Expected: All tests pass.

- [ ] **Step 3: Commit**

```bash
git add agent/webview/src/__tests__/ArtifactRenderer.test.tsx
git commit -m "test(webview): add ArtifactRenderer unit tests"
```

---

### Task 8: Add Artifact Visualization Guidance to Agent Prompts

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt`
- Modify: `agent/src/main/resources/agents/explorer.md`
- Modify: `agent/src/main/resources/agents/code-reviewer.md`
- Modify: `agent/src/main/resources/agents/architect-reviewer.md`
- Modify: `agent/src/main/resources/agents/performance-engineer.md`
- Modify: `agent/src/main/resources/agents/security-auditor.md`

- [ ] **Step 1: Add capability note to SystemPrompt.kt**

In the `capabilities()` function, add after the "Usage tips" section:

```kotlin
- You can output interactive visualizations using ```react code fences. The chat UI transpiles and renders them as live React components. Use this for architecture diagrams, sequence flows, class hierarchies, charts, and any data that's clearer as a visual. The component receives a bridge prop with navigateToFile(path, line) for IDE navigation, theme colors, and access to Lucide icons and Recharts.
```

- [ ] **Step 2: Add visualization section to explorer.md**

Add a new section before the "Process" section:

```markdown
## Producing Visualizations

When the answer involves architecture, flows, hierarchies, relationships, or comparisons that would be clearer as a visual, output a `react` code fence with an interactive component.

**When to visualize:**
- Architecture overview → clickable module/service boxes
- Request flow → animated sequence with clickable endpoints
- Class hierarchy → expandable tree with source navigation
- Data comparisons → charts (bar, pie, line) with Recharts
- Dependency graph → connected nodes with clickable modules

**When NOT to visualize:**
- Simple text answers, short lists, single-file explanations
- When the data has fewer than 3 items (just list them)
- When the user asked a yes/no question

**Component contract:**
- Must export a default function component
- Receives `{ bridge }` prop
- `bridge.navigateToFile(path, line)` — clicking opens file in IDE
- `bridge.isDark` and `bridge.colors` for theming
- Lucide icons available: `FileCode`, `GitBranch`, `Database`, `Shield`, `Zap`, `Server`, `Globe`, etc.
- Recharts available: `BarChart`, `PieChart`, `LineChart`, `AreaChart`, `Tooltip`, `Legend`, `Cell`, etc.
- ALL DATA MUST BE INLINE — no fetch, no file reads from inside the component

**Example — service architecture with clickable navigation:**

` ` `react
export default function ServiceMap({ bridge }) {
  const services = [
    { name: "OrderController", path: "src/main/kotlin/.../OrderController.kt", line: 15, type: "controller" },
    { name: "OrderService", path: "src/main/kotlin/.../OrderService.kt", line: 8, type: "service" },
  ];
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      {services.map(s => (
        <div key={s.name}
          onClick={() => bridge.navigateToFile(s.path, s.line)}
          style={{ padding: 12, borderRadius: 8, border: '1px solid var(--border)',
                   cursor: 'pointer', background: 'var(--code-bg)' }}>
          <strong>{s.name}</strong>
          <span style={{ color: 'var(--fg-muted)', marginLeft: 8 }}>{s.type}</span>
        </div>
      ))}
    </div>
  );
}
` ` `
```

(Remove spaces between backticks in the actual file — shown with spaces here to avoid markdown nesting issues.)

- [ ] **Step 3: Add short note to other agent prompts**

Add this line to the end of the system prompt section (before "## Completion") in `code-reviewer.md`, `architect-reviewer.md`, `performance-engineer.md`, and `security-auditor.md`:

```markdown
> **Visualization:** You can output `react` code fences for interactive visualizations. The component receives `{ bridge }` with `navigateToFile(path, line)` for IDE navigation, Lucide icons, and Recharts for charts. Use for findings dashboards, architecture diagrams, or comparison charts when they'd be clearer than text.
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt \
       agent/src/main/resources/agents/explorer.md \
       agent/src/main/resources/agents/code-reviewer.md \
       agent/src/main/resources/agents/architect-reviewer.md \
       agent/src/main/resources/agents/performance-engineer.md \
       agent/src/main/resources/agents/security-auditor.md
git commit -m "feat(agent): add artifact visualization guidance to agent prompts"
```

---

### Task 9: Build, Test, and Release

**Files:**
- Modify: `agent/webview/` (build output)
- Modify: `gradle.properties` (version bump)

- [ ] **Step 1: Full webview build**

```bash
cd agent/webview && npm run build
```

Expected: Build succeeds. Check that `agent/src/main/resources/webview/dist/artifact-sandbox.html` exists.

- [ ] **Step 2: Run webview tests**

```bash
cd agent/webview && npm test
```

Expected: All tests pass including new ArtifactRenderer tests.

- [ ] **Step 3: Full plugin build**

```bash
cd /path/to/project && ./gradlew clean buildPlugin
```

Expected: BUILD SUCCESSFUL, ZIP generated in `build/distributions/`.

- [ ] **Step 4: Bump version**

In `gradle.properties`, bump `pluginVersion` to `0.64.0-beta`.

- [ ] **Step 5: Commit build artifacts and version bump**

```bash
git add -A
git commit -m "feat(agent): artifact renderer — interactive JSX visualizations in chat

react-runner + Sucrase in sandboxed iframe. LLM outputs react code fences,
webview transpiles and renders as live interactive components with IDE
source navigation via curated bridge."
```

- [ ] **Step 6: Push and release**

```bash
git push origin rewrite/lean-agent
gh release create v0.64.0-beta \
  --title "v0.64.0-beta — Interactive Artifact Renderer" \
  --notes "Artifact renderer: LLM outputs react code fences → live interactive React components in chat. Clickable source navigation, theme-aware, Recharts for data viz, Lucide for icons. Sandboxed iframe, zero network dependency." \
  --target rewrite/lean-agent \
  build/distributions/intellij-workflow-orchestrator-0.64.0-beta.zip
```
