# Rich Visualization Improvements — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 10 new rich visualization capabilities to the agent chat UI — sortable data tables, timeline/gantt, incremental chart updates, image display, flow subgraphs, editable diffs, collapsible output sections, iframe two-way communication, real-time progress visualization, and code annotations with line highlights.

**Architecture:** Each new visualization follows the existing pattern: a React component that lazy-loads its library, wrapped in `RichBlock` for consistent UI (header, expand/collapse, fullscreen, error handling). New language tags are added to `MarkdownRenderer.tsx`'s code fence router. New `VisualizationType` entries are added to `types.ts` and `settingsStore.ts`.

**Tech Stack:** React 18, TypeScript, Tailwind CSS v4, existing libraries (chart.js, mermaid, dagre, katex, diff2html). No new npm dependencies — all new components use plain TypeScript + existing libraries.

**Parallelization:** Tasks 3–11 are fully independent after Task 2 completes. When using subagent-driven development, dispatch them in parallel.

**Working Directory:** All paths relative to worktree root. Webview source at `agent/webview/`.

**Spec:** This plan is self-contained. Based on audit findings from 2026-03-24.

**Existing files to understand:**
- `agent/webview/src/components/rich/RichBlock.tsx` (282 lines) — Universal wrapper
- `agent/webview/src/components/markdown/MarkdownRenderer.tsx` (215 lines) — Code fence router
- `agent/webview/src/stores/settingsStore.ts` (52 lines) — Per-type visualization config
- `agent/webview/src/bridge/types.ts` — VisualizationType union
- `agent/webview/src/styles/animations.css` (182 lines) — Animation keyframes

**Pattern for every new visualization:**
1. Add type to `VisualizationType` union in `types.ts`
2. Add defaults in `settingsStore.ts`
3. Add icon+label to `TYPE_META` in `RichBlock.tsx`
4. Create component in `components/rich/`
5. Add route in `MarkdownRenderer.tsx` code fence switch
6. Add to showcase

---

## Phase 1: Data Table (Task 1-2)

### Task 1: DataTable Component

**Files:**
- Modify: `agent/webview/src/bridge/types.ts` — add `'table'` to VisualizationType
- Modify: `agent/webview/src/stores/settingsStore.ts` — add table defaults
- Modify: `agent/webview/src/components/rich/RichBlock.tsx` — add table to TYPE_META
- Create: `agent/webview/src/components/rich/DataTable.tsx`
- Modify: `agent/webview/src/components/markdown/MarkdownRenderer.tsx` — add ```table route

- [ ] **Step 1: Add table type to types.ts**

Add `'table'` to the `VisualizationType` union.

- [ ] **Step 2: Add table defaults to settingsStore.ts**

```typescript
table: { enabled: true, autoRender: true, defaultExpanded: true, maxHeight: 400 },
```

- [ ] **Step 3: Add table to TYPE_META in RichBlock.tsx**

```typescript
table: { icon: '⊞', label: 'Table' },
```

- [ ] **Step 4: Create DataTable component**

Create `agent/webview/src/components/rich/DataTable.tsx`:

The LLM outputs ````table` with JSON:
```json
{
  "columns": ["Name", "Status", "Duration"],
  "rows": [
    ["build-123", "PASSED", "2m 30s"],
    ["build-124", "FAILED", "1m 12s"]
  ],
  "sortable": true,
  "searchable": true
}
```

Component features:
- Parse JSON → render shadcn-style table with `<table>`, `<thead>`, `<tbody>`
- Click column header to sort (asc/desc/none) — implement with local state, no @tanstack needed for this
- Optional search input that filters rows (case-insensitive across all columns)
- Row count display: "Showing X of Y rows"
- Alternating row backgrounds using CSS variables
- Wrap in RichBlock

- [ ] **Step 5: Add ```table route to MarkdownRenderer.tsx**

In the code fence switch, add:
```typescript
case 'table':
  return <DataTable tableSource={String(children)} />;
```

- [ ] **Step 6: Verify TypeScript + build**

- [ ] **Step 7: Commit**

---

### Task 2: Auto-Upgrade Markdown Tables

**Files:**
- Modify: `agent/webview/src/components/markdown/MarkdownRenderer.tsx` — enhance table override

- [ ] **Step 1: Enhance the existing table override in MarkdownRenderer**

The existing `table` override in `createMarkdownComponents` renders basic HTML. Enhance it to:
- Count rows. If > 5 rows, render via DataTable component instead of plain HTML
- Extract structured data from react-markdown's component tree: the `table` override receives `children` as React elements. Recursively traverse `thead > tr > th` for column headers and `tbody > tr > td` for cell values using `React.Children.forEach` + `props.children` drilling. Build `{ columns: string[], rows: string[][] }` and pass to DataTable.
- Tables with ≤ 5 rows stay as plain styled HTML (no overhead)

- [ ] **Step 2: Verify + Commit**

---

## Phase 2: Code Annotations (Task 3)

### Task 3: Code Block with Line Highlights and Annotations

**Files:**
- Modify: `agent/webview/src/components/markdown/MarkdownRenderer.tsx` — pass meta string to CodeBlock
- Modify: `agent/webview/src/components/rich/CodeBlock.tsx` — add highlight/annotation support

- [ ] **Step 0: Enable meta string extraction in MarkdownRenderer**

**Technical context:** `react-markdown` does NOT pass code fence meta strings by default. The `className` prop only contains `language-xxx`. To access the meta text after the language tag, we need to intercept it.

**Approach:** Use a custom `rehype` plugin that extracts the meta string from the HAST `code` node's `data.meta` property and attaches it as a `data-meta` attribute. Then in the `code` override in `createMarkdownComponents`, read `node.data?.meta` or the `data-meta` prop.

Alternatively, simpler approach: in the `code` override, `react-markdown` passes the raw `node` from the AST. Access `node.data?.meta` directly (available when using `rehype-raw`). If this doesn't work, add a minimal rehype plugin:

```typescript
function rehypeCodeMeta() {
  return (tree: any) => {
    visit(tree, 'element', (node: any) => {
      if (node.tagName === 'code' && node.data?.meta) {
        node.properties['data-meta'] = node.data.meta;
      }
    });
  };
}
```

Add this plugin to the `rehypePlugins` array in MarkdownRenderer.

- [ ] **Step 1: Parse meta string in CodeBlock**

The LLM outputs:
````markdown
```typescript highlight={3,5-7} annotation={3:"Bug here",7:"This fixes it"}
const x = 1;
const y = 2;
const z = x + y; // line 3 highlighted + annotation
```
````

**Meta string grammar:**
```
meta = (key "=" value)*
key = "highlight" | "annotation"
highlight-value = "{" range ("," range)* "}"
range = number | number "-" number
annotation-value = "{" (number ":" quoted-string)* "}"
```

Parse via regex: `highlight=\{([^}]+)\}` and `annotation=\{([^}]+)\}`.

- [ ] **Step 2: Render highlighted lines**

Add a background color overlay on highlighted lines using CSS:
```css
.code-line-highlight {
  background-color: var(--accent-edit-bg, rgba(250, 204, 21, 0.1));
  border-left: 3px solid var(--accent-edit, #f59e0b);
}
```

- [ ] **Step 3: Render inline annotations**

For lines with annotations, show a tooltip icon (💬) at the end of the line. On hover/click, show the annotation text in a small popover.

- [ ] **Step 4: Verify + Commit**

---

## Phase 3: Collapsible Output Sections (Task 4)

### Task 4: Auto-Collapsible Sections in Long Output

**Files:**
- Modify: `agent/webview/src/bridge/types.ts` — add `'output'` to VisualizationType
- Modify: `agent/webview/src/stores/settingsStore.ts` — add output defaults
- Modify: `agent/webview/src/components/rich/RichBlock.tsx` — add output to TYPE_META
- Create: `agent/webview/src/components/rich/CollapsibleOutput.tsx`
- Modify: `agent/webview/src/components/markdown/MarkdownRenderer.tsx` — add ```output route

- [ ] **Step 0: Register type**

Add `'output'` to `VisualizationType` in types.ts. Add defaults in settingsStore: `{ enabled: true, autoRender: true, defaultExpanded: true, maxHeight: 400 }`. Add `output: { icon: '📋', label: 'Output' }` to TYPE_META in RichBlock.

- [ ] **Step 1: Create CollapsibleOutput component**

Reuse the existing `AnsiOutput` component (imported from `./AnsiOutput`) for ANSI rendering within sections, rather than importing `ansi_up` directly.

The LLM or tool results can output:
````markdown
```output
### Build Log
[INFO] Compiling module core...
[INFO] 47 files compiled
[WARN] Deprecated API usage in AuthService.kt

### Test Results
Tests run: 124, Failures: 2, Errors: 0
FAIL: AuthServiceTest.testTokenRefresh
FAIL: CacheManagerTest.testEviction

### Coverage
Overall: 78.3%
core: 85.1%
agent: 62.4%
```
````

Component features:
- Detect `### ` headers in the output text
- Split into sections, each collapsible via shadcn Collapsible
- First section expanded by default, rest collapsed
- Section headers show line count badge
- ANSI color support within sections (use AnsiUp)
- If no headers detected, render as single scrollable block

- [ ] **Step 2: Add ```output route to MarkdownRenderer**

- [ ] **Step 3: Verify + Commit**

---

## Phase 4: Progress Visualization (Task 5)

### Task 5: Real-Time Progress Bar Component

**Files:**
- Create: `agent/webview/src/components/rich/ProgressView.tsx`
- Modify: `agent/webview/src/bridge/types.ts` — add 'progress' to VisualizationType
- Modify: `agent/webview/src/stores/settingsStore.ts` — add progress defaults
- Modify: `agent/webview/src/components/rich/RichBlock.tsx` — add to TYPE_META
- Modify: `agent/webview/src/components/markdown/MarkdownRenderer.tsx` — add route

- [ ] **Step 1: Create ProgressView component**

The LLM outputs:
```json
{
  "title": "Running test suite",
  "phases": [
    { "label": "Compile", "status": "completed", "duration": "12s" },
    { "label": "Unit Tests", "status": "running", "progress": 67 },
    { "label": "Integration Tests", "status": "pending" },
    { "label": "Coverage Report", "status": "pending" }
  ],
  "overall": 42
}
```

Component features:
- Overall progress bar at top with percentage
- Phase list with status icons (checkmark/spinner/clock)
- Running phase shows animated progress bar
- Duration display for completed phases
- Animated transitions when status changes
- Use shadcn Progress component for bars

**Key difference from Plan widget**: This is for transient operations (build, test run, deployment) not persistent plans. It's lighter weight and focuses on real-time progress with durations.

- [ ] **Step 2: Add type, defaults, route, TYPE_META**

- [ ] **Step 3: Verify + Commit**

---

## Phase 5: Image Display (Task 6)

### Task 6: Image Renderer with Zoom

**Files:**
- Modify: `agent/webview/src/bridge/types.ts` — add `'image'` to VisualizationType
- Modify: `agent/webview/src/stores/settingsStore.ts` — add image defaults
- Modify: `agent/webview/src/components/rich/RichBlock.tsx` — add image to TYPE_META
- Create: `agent/webview/src/components/rich/ImageView.tsx`
- Modify: `agent/webview/src/components/markdown/MarkdownRenderer.tsx` — add ```image route + enhance img tag

- [ ] **Step 0: Register type**

Add `'image'` to `VisualizationType` in types.ts. Add defaults: `{ enabled: true, autoRender: true, defaultExpanded: true, maxHeight: 500 }`. Add `image: { icon: '🖼', label: 'Image' }` to TYPE_META. Wrap in RichBlock for consistent fullscreen/expand behavior.

- [ ] **Step 1: Create ImageView component**

For images referenced in markdown (`![alt](path)`) or via a dedicated block:
````markdown
```image
{ "src": "http://workflow-agent/screenshots/build-result.png", "alt": "Build dashboard", "caption": "Build #456 results" }
```
````

Component features:
- Render image with proper aspect ratio
- Click to open in fullscreen dialog (shadcn Dialog)
- Zoom in/out controls in fullscreen (reuse useZoomPan pattern from FlowDiagram)
- Caption text below image
- Loading skeleton while image loads
- Error state if image fails to load
- Images served via `http://workflow-agent/` scheme (JCEF resource handler)

- [ ] **Step 2: Enhance markdown `img` override**

In `createMarkdownComponents`, update the `img` override (or add one) to wrap images in ImageView for click-to-zoom behavior.

- [ ] **Step 3: Verify + Commit**

---

## Phase 6: Flow Subgraphs (Task 7)

### Task 7: Extend FlowDiagram with Grouping

**Files:**
- Modify: `agent/webview/src/components/rich/FlowDiagram.tsx` — add group support

- [ ] **Step 1: Extend FlowConfig type**

```typescript
interface FlowGroup {
  id: string;
  label: string;
  nodeIds: string[];
  color?: string;
}

interface FlowConfig {
  nodes: FlowNode[];
  edges: FlowEdge[];
  groups?: FlowGroup[];
  direction?: 'TB' | 'LR' | 'BT' | 'RL';
  title?: string;
}
```

- [ ] **Step 2: Render group backgrounds**

After dagre computes layout, for each group:
- Find bounding box of all nodes in the group (min/max x/y + padding)
- Render a rounded `<rect>` behind the nodes with semi-transparent fill
- Render group label at the top of the rect
- Group color defaults to a muted shade of the theme accent

- [ ] **Step 3: Verify + Commit**

---

## Phase 7: Editable Diffs (Task 8)

### Task 8: Make Diff Hunks Editable Before Accept

**Files:**
- Modify: `agent/webview/src/components/rich/DiffHtml.tsx` — add edit mode

- [ ] **Step 1: Add edit button to each hunk**

**Technical note:** diff2html renders HTML via `innerHTML` (DiffHtml.tsx line 49). We can't insert React-controlled components directly. Use the same **imperative DOM manipulation** approach already used for Accept/Reject buttons (lines 62-92): after diff2html renders, query `.d2h-diff-tbody` elements, inject Edit buttons via `createElement`, and on click, replace the hunk's `<td>` content with a dynamically created `<textarea>` + Save/Cancel buttons. All event handlers attached imperatively.

Clicking Edit:
- Extracts current "new" content from the hunk's `<td>` cells
- Replaces with a `<textarea>` pre-filled with the content
- Shows Save/Cancel buttons
- On Save, updates the diff display and calls a bridge function with the edited content

- [ ] **Step 2: Add bridge function**

Add `window._acceptDiffHunk` and `window._editDiffHunk` to `globals.d.ts`:
```typescript
_acceptDiffHunk?: (filePath: string, hunkIndex: number, editedContent?: string) => void;
_rejectDiffHunk?: (filePath: string, hunkIndex: number) => void;
```

- [ ] **Step 3: Verify + Commit**

---

## Phase 8: Timeline (Task 9)

### Task 9: Timeline Component

**Files:**
- Create: `agent/webview/src/components/rich/TimelineView.tsx`
- Modify: `agent/webview/src/bridge/types.ts` — add 'timeline' type
- Modify: `agent/webview/src/stores/settingsStore.ts` — add defaults
- Modify: `agent/webview/src/components/rich/RichBlock.tsx` — add to TYPE_META
- Modify: `agent/webview/src/components/markdown/MarkdownRenderer.tsx` — add route

- [ ] **Step 1: Create TimelineView component**

The LLM outputs:
```json
{
  "title": "Build History",
  "events": [
    { "time": "10:30", "label": "Build #456 started", "status": "info" },
    { "time": "10:32", "label": "Compilation complete", "status": "success" },
    { "time": "10:35", "label": "Unit tests: 2 failures", "status": "error" },
    { "time": "10:36", "label": "Build failed", "status": "error" }
  ]
}
```

Component features:
- Vertical timeline with connecting line
- Each event: time label left, dot on line, event text right
- Status colors via CSS variables: info → `var(--accent)`, success → `var(--success)`, warning → `var(--warning)`, error → `var(--error)`
- Animated entrance (staggered fade-in per event)
- Hover shows full timestamp

- [ ] **Step 2: Add type, defaults, route, TYPE_META**

- [ ] **Step 3: Verify + Commit**

---

## Phase 9: Incremental Chart Updates (Task 10)

### Task 10: Chart Update Protocol

**Files:**
- Modify: `agent/webview/src/components/rich/ChartView.tsx` — add update support
- Modify: `agent/webview/src/bridge/jcef-bridge.ts` — add chart update bridge function

- [ ] **Step 1: Add chart registry**

In ChartView, maintain a module-level `Map<string, Chart>` that stores rendered charts by ID.

**Lifecycle management:** Each ChartView with an `id` registers its Chart instance on mount and removes it on unmount via `useEffect` cleanup. Before using a cached instance, validate that `chart.canvas` is still connected to the DOM (`chart.canvas.isConnected`). If stale (canvas was unmounted), remove from registry and re-create.

The LLM can output a chart with an `id`:
```json
{ "id": "build-metrics", "type": "line", "data": {...} }
```

And later update it:
```json
{ "id": "build-metrics", "action": "update", "data": { "datasets": [{ "data": [10, 20, 30, 40] }] } }
```

- [ ] **Step 2: Implement update logic**

When a ````chart` block has `action: "update"`:
- Look up the chart by ID in the registry
- Validate `chart.canvas.isConnected` — if stale, remove entry and render as new chart
- Call `chart.data = mergedData` + `chart.update('active')` for smooth animation
- If chart not found, render as new chart

- [ ] **Step 3: Add Kotlin bridge for programmatic updates**

Add `window.updateChart(id, json)` to `jcef-bridge.ts` so Kotlin can push chart data updates (e.g., live build metrics).

- [ ] **Step 4: Verify + Commit**

---

## Phase 10: Interactive HTML Two-Way Communication (Task 11)

### Task 11: PostMessage Bridge for Interactive HTML

**Files:**
- Modify: `agent/webview/src/components/rich/InteractiveHtml.tsx` — add postMessage listener
- Modify: `agent/webview/src/bridge/globals.d.ts` — add bridge function

- [ ] **Step 1: Add message listener to InteractiveHtml**

The iframe can send messages to the parent via `parent.postMessage`:
```javascript
// Inside iframe
parent.postMessage({ type: 'form-submit', data: { name: 'value' } }, '*');
```

The InteractiveHtml component listens for these and routes them:
```typescript
useEffect(() => {
  const handler = (e: MessageEvent) => {
    if (e.source !== iframeRef.current?.contentWindow) return;
    // Validate message shape — only forward messages with a string `type` field
    if (typeof e.data?.type === 'string' && e.data.type.length > 0 && e.data.type.length < 100) {
      window._interactiveHtmlMessage?.(JSON.stringify(e.data));
    }
  };
  window.addEventListener('message', handler);
  return () => window.removeEventListener('message', handler);
}, []);
```

- [ ] **Step 2: Add bridge function**

Add to `globals.d.ts`:
```typescript
_interactiveHtmlMessage?: (json: string) => void;
```

This allows Kotlin to receive form submissions, button clicks, etc. from LLM-generated interactive UIs.

- [ ] **Step 3: Add sendToIframe API**

Add a `sendToIframe(id, message)` function that allows Kotlin to push data INTO the iframe:
```typescript
// In jcef-bridge.ts
window.sendToInteractiveHtml = (id: string, json: string) => {
  const iframe = document.querySelector(`iframe[data-viz-id="${id}"]`);
  iframe?.contentWindow?.postMessage(JSON.parse(json), '*');
};
```

- [ ] **Step 4: Verify + Commit**

---

## Phase 11: Showcase & Documentation (Task 12)

### Task 12: Add All New Visualizations to Showcase

**Files:**
- Modify: `agent/webview/src/showcase.tsx` — add sections for each new viz type
- Modify: `agent/webview/src/showcase/mock-data.ts` — add mock data

- [ ] **Step 1: Add mock data for each new type**

Add to mock-data.ts:
- `mockTableData` — JSON for DataTable (5 columns, 10 rows)
- `mockTimelineEvents` — JSON for Timeline (6 events)
- `mockProgressData` — JSON for ProgressView (4 phases)
- `mockAnnotatedCode` — Code with highlight + annotation metadata
- `mockCollapsibleOutput` — Multi-section output with headers
- `mockFlowWithGroups` — Flow JSON with groups

- [ ] **Step 2: Add showcase sections**

Add sections to showcase.tsx for:
- DataTable (sortable, searchable)
- Timeline
- Progress View
- Code with annotations
- Collapsible output
- Flow with subgraphs
- Image (with local placeholder)

- [ ] **Step 3: Verify build + Commit**

---

## Implementation Notes

### Dependency Budget
- No new npm dependencies for Tasks 1-9, 11-12 (use existing libraries + custom components)
- Task 10 (chart updates) uses existing chart.js API
- DataTable uses plain `Array.sort()` — no @tanstack needed for simple sort/filter

### Theme Compliance
- All new components MUST use CSS variables (zero hardcoded hex)
- Wrap in RichBlock for consistent UI
- Test in both light and dark themes via showcase

### Streaming Behavior
- New code fence types (`table`, `output`, `progress`, `timeline`, `image`) show the generic shimmer skeleton during streaming (existing `hasOpenCodeFence` uses a generic triple-backtick counter that works for all languages — no per-language updates needed)

### Accessibility
- DataTable: add `role="grid"`, sort buttons need `aria-sort="ascending"/"descending"/"none"`
- Timeline: use `role="list"` / `role="listitem"` for events
- ProgressView: use `role="progressbar"` with `aria-valuenow`/`aria-valuemax`
- All new components: follow existing RichBlock pattern for `aria-label`

### LLM System Prompt (Follow-up)
After implementation, update `AgentSystemPrompt.kt` to document the new visualization formats so the LLM knows it can use ````table`, ````timeline`, ````progress`, ````output`, ````image`, `highlight={}` on code blocks. This is a Kotlin-side change — separate from this plan but critical for the LLM to actually produce these formats.
