# Explanatory Animation System — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add step-by-step explanatory animations to FlowDiagram and MermaidDiagram so the LLM can visually walk users through data flows, request paths, and sequence interactions — helping them *understand* system architecture, not just see it.

**Architecture:** Derive animation order from the graph/diagram structure (BFS for flows, top-to-bottom for Mermaid sequences). The LLM adds a minimal `animated: true` flag and optional `highlightPath` (list of node IDs) to focus on specific paths. A shared `PlayControls` component provides step/play/pause UI. Graceful degradation: invalid paths skip steps, missing flags render static diagrams.

**Tech Stack:** React 18, TypeScript, existing SVG rendering in FlowDiagram.tsx and MermaidDiagram.tsx. No new npm dependencies.

**Working Directory:** All paths relative to worktree root. Webview source at `agent/webview/`.

**Key files:**
- `agent/webview/src/components/rich/FlowDiagram.tsx` (497 lines) — SVG flow rendering with dagre layout
- `agent/webview/src/components/rich/MermaidDiagram.tsx` (171 lines) — Mermaid SVG injection
- `agent/webview/src/styles/animations.css` (238 lines) — CSS keyframes

**Failure mode design:**
- `highlightPath` has misspelled node ID → that step is skipped, rest still works
- `highlightPath` omitted → falls back to BFS traversal from source nodes
- `animated` omitted → static diagram (existing behavior, zero regression)
- Cyclic graph + no highlightPath → static diagram with all-edges-animated (existing flowDash)
- Mermaid diagram is not a sequenceDiagram → no sequential animation, renders as-is

---

## Phase 1: Shared Play Controls (Task 1)

### Task 1: Create PlayControls Component

**Files:**
- Create: `agent/webview/src/components/rich/PlayControls.tsx`

This is a reusable step-through controller used by both FlowDiagram and MermaidDiagram.

- [ ] **Step 1: Create PlayControls component**

```typescript
import { useState, useEffect, useCallback, useRef } from 'react';
import { Button } from '@/components/ui/button';
import { Play, Pause, SkipBack, SkipForward, RotateCcw } from 'lucide-react';

interface PlayControlsProps {
  totalSteps: number;
  currentStep: number;
  onStepChange: (step: number) => void;
  autoPlayInterval?: number; // ms between steps, default 1200
  className?: string;
}

export function PlayControls({
  totalSteps,
  currentStep,
  onStepChange,
  autoPlayInterval = 1200,
  className,
}: PlayControlsProps) {
  const [playing, setPlaying] = useState(false);

  const handlePlay = () => setPlaying(true);
  const handlePause = () => setPlaying(false);
  const handleStepBack = () => { setPlaying(false); onStepChange(Math.max(0, currentStep - 1)); };
  const handleStepForward = () => { setPlaying(false); onStepChange(Math.min(totalSteps - 1, currentStep + 1)); };
  const handleReset = () => { setPlaying(false); onStepChange(0); };

  // Auto-advance: setTimeout (not setInterval) triggered by currentStep changes
  // Parent owns step state, we just call onStepChange(n)
  useEffect(() => {
    if (!playing) return;
    const timer = setTimeout(() => {
      if (currentStep < totalSteps - 1) {
        onStepChange(currentStep + 1);
      } else {
        setPlaying(false);
      }
    }, autoPlayInterval);
    return () => clearTimeout(timer);
  }, [playing, currentStep, totalSteps, autoPlayInterval, onStepChange]);

  return (
    <div
      className={`flex items-center justify-center gap-1 py-2 ${className ?? ''}`}
      style={{ borderTop: '1px solid var(--border)' }}
    >
      <Button variant="ghost" size="sm" className="h-7 w-7 p-0" onClick={handleReset} title="Reset">
        <RotateCcw className="h-3 w-3" />
      </Button>
      <Button variant="ghost" size="sm" className="h-7 w-7 p-0" onClick={handleStepBack} title="Previous step" disabled={currentStep <= 0}>
        <SkipBack className="h-3 w-3" />
      </Button>
      {playing ? (
        <Button variant="ghost" size="sm" className="h-7 w-7 p-0" onClick={handlePause} title="Pause">
          <Pause className="h-3.5 w-3.5" />
        </Button>
      ) : (
        <Button variant="ghost" size="sm" className="h-7 w-7 p-0" onClick={handlePlay} title="Play" disabled={currentStep >= totalSteps - 1}>
          <Play className="h-3.5 w-3.5" />
        </Button>
      )}
      <Button variant="ghost" size="sm" className="h-7 w-7 p-0" onClick={handleStepForward} title="Next step" disabled={currentStep >= totalSteps - 1}>
        <SkipForward className="h-3 w-3" />
      </Button>
      <span className="ml-2 text-[10px] font-mono tabular-nums" style={{ color: 'var(--fg-muted)' }}>
        {currentStep + 1} / {totalSteps}
      </span>
    </div>
  );
}
```

Note: The `useEffect` for auto-advance should use `setTimeout` (not `setInterval`) triggered by `currentStep` changes when `playing=true`. This avoids stale closure issues. The parent component owns `currentStep` state and passes it down.

- [ ] **Step 2: Verify TypeScript**

```bash
cd agent/webview && npx tsc --noEmit
```

- [ ] **Step 3: Commit**

```bash
git add agent/webview/src/components/rich/PlayControls.tsx
git commit -m "feat: add shared PlayControls component for step-through animations"
```

---

## Phase 2: Animated Flow Diagram (Tasks 2-4)

### Task 2: Add Animation Types to FlowConfig

**Files:**
- Modify: `agent/webview/src/components/rich/FlowDiagram.tsx` — extend types + add BFS utility

- [ ] **Step 1: Extend FlowConfig interface**

Add to `FlowConfig` (after `groups?`):
```typescript
animated?: boolean;          // Enable step-through animation
highlightPath?: string[];    // Optional ordered list of node IDs to animate
pathLabels?: string[];       // Optional captions per edge in the path
```

- [ ] **Step 2: Add BFS traversal utility**

Add a function that computes animation steps from the graph:

```typescript
interface AnimationStep {
  activeNodeId: string;
  activeEdgeIndex: number | null; // index into layout.edges
  reverseEdge?: boolean;          // true if animating an edge backwards (response path)
  label?: string;
}

function computeAnimationSteps(
  config: FlowConfig,
  layoutEdges: LayoutEdge[],
): AnimationStep[] {
  const { nodes, edges, highlightPath, pathLabels } = config;

  // If highlightPath provided, follow it
  if (highlightPath && highlightPath.length > 0) {
    const steps: AnimationStep[] = [];
    for (let i = 0; i < highlightPath.length; i++) {
      const nodeId = highlightPath[i]!;
      // Validate node exists
      if (!nodes.some(n => n.id === nodeId)) continue; // skip invalid IDs

      // Find edge from previous node to this node
      // Also check reverse direction for request-response patterns
      let edgeIdx: number | null = null;
      let reverseEdge = false;
      if (i > 0) {
        const prevId = highlightPath[i - 1]!;
        // Forward edge
        edgeIdx = layoutEdges.findIndex(
          e => e.from === prevId && e.to === nodeId
        );
        if (edgeIdx === -1) {
          // Try reverse edge (response path — animate the same edge backwards)
          edgeIdx = layoutEdges.findIndex(
            e => e.from === nodeId && e.to === prevId
          );
          if (edgeIdx !== -1) {
            reverseEdge = true;
          } else {
            edgeIdx = null;
          }
        }
      }

      // pathLabels correspond to edges (transitions), not nodes
      // Label at index i-1 describes the edge arriving at node i
      steps.push({
        activeNodeId: nodeId,
        activeEdgeIndex: edgeIdx,
        reverseEdge,
        label: i > 0 ? pathLabels?.[i - 1] : undefined,
      });
    }
    return steps;
  }

  // BFS from source nodes (nodes with no incoming edges)
  const incomingCount = new Map<string, number>();
  nodes.forEach(n => incomingCount.set(n.id, 0));
  edges.forEach(e => incomingCount.set(e.to, (incomingCount.get(e.to) ?? 0) + 1));
  const sources = nodes.filter(n => (incomingCount.get(n.id) ?? 0) === 0);

  if (sources.length === 0) return []; // cyclic graph, no clear start

  const visited = new Set<string>();
  const queue: string[] = sources.map(s => s.id);
  const steps: AnimationStep[] = [];

  while (queue.length > 0) {
    const nodeId = queue.shift()!;
    if (visited.has(nodeId)) continue;
    visited.add(nodeId);

    // Find the edge that brought us here
    let edgeIdx: number | null = null;
    if (steps.length > 0) {
      edgeIdx = layoutEdges.findIndex(
        e => e.to === nodeId && visited.has(e.from)
      );
      if (edgeIdx === -1) edgeIdx = null;
    }

    steps.push({ activeNodeId: nodeId, activeEdgeIndex: edgeIdx });

    // Enqueue neighbors
    edges
      .filter(e => e.from === nodeId && !visited.has(e.to))
      .forEach(e => queue.push(e.to));
  }

  return steps;
}
```

**Key design decisions:**
- `highlightPath` with invalid IDs → silently skipped (graceful degradation)
- No sources found (fully cyclic) → empty steps → no animation → static diagram
- BFS visits each node once → works for DAGs, fails for request-response cycles (documented limitation)
- `highlightPath` allows node repetition → solves request-response (Client→Gateway→Auth→DB→Auth→Gateway→Client)

- [ ] **Step 3: Verify + Commit**

---

### Task 3: Render Animated SVG in FlowDiagram

**Files:**
- Modify: `agent/webview/src/components/rich/FlowDiagram.tsx` — add animation state + visual treatment

- [ ] **Step 1: Add animation state**

Inside the FlowDiagram component, add state for the parsed config (currently only `layout` is stored, but animation needs access to the original `FlowConfig`):

```typescript
const [config, setConfig] = useState<FlowConfig | null>(null);
const [animStep, setAnimStep] = useState(0);
```

In the `parseAndLayout` callback, after `setLayout(result)`, also store the config:
```typescript
setConfig(parsedConfig); // store alongside layout
```

Then compute animation steps (memoized):

```typescript
// Compute animation steps (memoized)
const animSteps = useMemo(() => {
  if (!config?.animated || !layout) return [];
  return computeAnimationSteps(config, layout.edges);
}, [config, layout]);

const isAnimated = animSteps.length > 0;
```

- [ ] **Step 2: Modify SVG node rendering for animation states**

Each node gets a visual state based on the current animation step:

```typescript
type NodeVisualState = 'active' | 'visited' | 'unvisited' | 'normal';

function getNodeState(nodeId: string, step: number, steps: AnimationStep[]): NodeVisualState {
  if (steps.length === 0) return 'normal'; // not animated
  const activeStep = steps[step];
  if (activeStep?.activeNodeId === nodeId) return 'active';
  // Check if visited in any previous step
  for (let i = 0; i < step; i++) {
    if (steps[i]?.activeNodeId === nodeId) return 'visited';
  }
  return 'unvisited';
}
```

Visual treatment per state:

| State | Fill | Stroke | Opacity | Extra |
|-------|------|--------|---------|-------|
| `normal` | existing | existing | 1 | existing behavior |
| `active` | subtle glow bg | node color, 2.5px | 1 | pulsing glow filter |
| `visited` | existing | node color, 1.5px | 1 | checkmark or solid border |
| `unvisited` | existing | border color | 0.3 | dimmed |

For the active node, add an SVG filter for glow:
```xml
<filter id="glow">
  <feGaussianBlur stdDeviation="3" result="blur"/>
  <feMerge>
    <feMergeNode in="blur"/>
    <feMergeNode in="SourceGraphic"/>
  </feMerge>
</filter>
```

Apply `filter="url(#glow)"` to the active node's `<rect>`.

- [ ] **Step 3: Modify SVG edge rendering for animation states**

Similarly, edges get visual states:

```typescript
type EdgeVisualState = 'active' | 'visited' | 'unvisited' | 'normal';

function getEdgeState(edgeIndex: number, step: number, steps: AnimationStep[]): EdgeVisualState {
  if (steps.length === 0) return 'normal';
  const activeStep = steps[step];
  if (activeStep?.activeEdgeIndex === edgeIndex) return 'active';
  for (let i = 0; i < step; i++) {
    if (steps[i]?.activeEdgeIndex === edgeIndex) return 'visited';
  }
  return 'unvisited';
}
```

| State | Stroke | Width | Opacity | Animation |
|-------|--------|-------|---------|-----------|
| `normal` | existing | 1.5 | existing | flowDash |
| `active` | accent color | 2.5 | 1 | traveling dot (SVG `<circle>` + `<animateMotion>`) |
| `visited` | muted | 1.5 | 0.8 | none (solid) |
| `unvisited` | border | 1 | 0.2 | none |

For the active edge, add a traveling dot:
```xml
<circle r="3" fill={accentColor}>
  <animateMotion dur="0.8s" repeatCount="indefinite">
    <mpath href={`#edge-path-${edgeIndex}`}/>
  </animateMotion>
</circle>
```

This requires giving each edge path an `id` attribute: `id={`edge-path-${i}`}`.

- [ ] **Step 4: Add caption display**

Below the SVG, show the current step's label (if any):

```typescript
{isAnimated && animSteps[animStep]?.label && (
  <div className="text-center text-[12px] py-1 animate-[fade-in_200ms_ease-out]"
       key={animStep}
       style={{ color: 'var(--fg-secondary)' }}>
    {animSteps[animStep].label}
  </div>
)}
```

The `key={animStep}` forces re-mount on step change, triggering the fade-in animation for each new caption.

- [ ] **Step 5: Verify + Commit**

---

### Task 4: Add PlayControls to FlowDiagram

**Files:**
- Modify: `agent/webview/src/components/rich/FlowDiagram.tsx` — integrate PlayControls

- [ ] **Step 1: Import and render PlayControls**

```typescript
import { PlayControls } from './PlayControls';
```

After the SVG container (inside the RichBlock), conditionally render:

```typescript
{isAnimated && (
  <PlayControls
    totalSteps={animSteps.length}
    currentStep={animStep}
    onStepChange={setAnimStep}
    autoPlayInterval={1200}
  />
)}
```

- [ ] **Step 2: Auto-play on mount**

When the diagram first renders with `animated: true`, auto-start playback after a 500ms delay so the user sees the initial state first:

```typescript
useEffect(() => {
  if (isAnimated && animSteps.length > 1) {
    const timer = setTimeout(() => setAutoPlay(true), 500);
    return () => clearTimeout(timer);
  }
}, [isAnimated, animSteps.length]);
```

This requires PlayControls to accept an `autoPlay` prop, or we manage it by passing `playing` state down. Simpler: just start at step 0 and let the user click Play. No auto-play — explicit user control is safer.

- [ ] **Step 3: Verify full flow end-to-end**

Test with this mock data:
```json
{
  "nodes": [
    { "id": "client", "label": "Client" },
    { "id": "gw", "label": "API Gateway" },
    { "id": "auth", "label": "Auth Service" },
    { "id": "db", "label": "Database" }
  ],
  "edges": [
    { "from": "client", "to": "gw", "label": "POST /login" },
    { "from": "gw", "to": "auth", "label": "validate" },
    { "from": "auth", "to": "db", "label": "query" }
  ],
  "animated": true,
  "highlightPath": ["client", "gw", "auth", "db", "auth", "gw", "client"],
  "pathLabels": ["Login request", "Forward to auth", "Query user DB", "Return user record", "Issue JWT", "200 OK + token"]
}
```

Expected: Play button at bottom. Clicking Play steps through: Client glows → edge animates to Gateway → Gateway glows → edge to Auth → Auth glows → edge to DB → DB glows → edge BACK to Auth (reverse, node re-highlights) → Auth → edge to Gateway → Gateway → edge to Client. Each step shows its caption. Unvisited nodes are dimmed.

- [ ] **Step 4: Commit**

```bash
git add agent/webview/src/components/rich/FlowDiagram.tsx
git commit -m "feat: animated flow diagrams with step-through, highlight path, traveling dots, captions"
```

---

## Phase 3: Animated Mermaid Sequence Diagrams (Tasks 5-6)

### Task 5: Parse Mermaid SVG for Sequential Animation

**Files:**
- Modify: `agent/webview/src/components/rich/MermaidDiagram.tsx` — add post-processing

- [ ] **Step 1: Detect sequence diagrams**

After mermaid renders the SVG string, check if the source starts with `sequenceDiagram`:

```typescript
const isSequenceDiagram = source.trim().startsWith('sequenceDiagram');
```

- [ ] **Step 2: Parse message elements from SVG**

Mermaid sequence diagram SVGs contain message arrows as `<line>` or `<path>` elements inside `<g>` groups, often with class names like `.messageLine0`, `.messageLine1`, etc. The message text is in `<text>` elements with class `.messageText`.

After injecting the SVG, if it's a sequence diagram, query the DOM for message groups:

```typescript
// After setSvgContent(svg) and DOM is rendered
useEffect(() => {
  if (!isSequenceDiagram || !containerRef.current) return;

  const svgEl = containerRef.current.querySelector('svg');
  if (!svgEl) return;

  // Mermaid 11.x renders each message as a group of elements:
  // - TWO <line> elements per message (line + arrowhead): messageLine0/messageLine1 alternating
  // - One <text> element: .messageText
  //
  // Strategy: Use .messageText elements as the anchor (one per message).
  // For each messageText, walk to its parent <g> and hide/show the entire group.
  // This captures the line, arrowhead, and text together.

  const messageTexts = svgEl.querySelectorAll('.messageText');
  const messageElements: Element[][] = [];

  messageTexts.forEach(textEl => {
    // Walk up to the parent group that contains this message's line + arrowhead + text
    const parentG = textEl.closest('g');
    if (parentG) {
      // Collect all children of this group (line, path, text, etc.)
      messageElements.push(Array.from(parentG.children));
    } else {
      // Fallback: just the text element
      messageElements.push([textEl]);
    }
  });

  // Fallback: if no .messageText found, try alternate selectors
  if (messageElements.length === 0) {
    // Not a sequence diagram we can animate — skip
    return;
  }

  setMessageGroups(messageElements);

  // Initially hide all messages
  messageElements.forEach(group => {
    group.forEach(el => {
      (el as HTMLElement).style.opacity = '0';
      (el as HTMLElement).style.transition = 'opacity 300ms ease-out';
    });
  });
}, [svgContent, isSequenceDiagram]);
```

**Important caveat**: Mermaid's SVG structure varies between versions. The class names (`messageLine0`, `messageText`) are based on Mermaid 11.x. Read the actual rendered SVG in the browser to verify class names. If they differ, adjust selectors.

**Fallback**: If no message elements found, don't animate — show static diagram.

- [ ] **Step 3: Commit**

---

### Task 6: Add Step-Through to Mermaid Sequences

**Files:**
- Modify: `agent/webview/src/components/rich/MermaidDiagram.tsx` — integrate PlayControls + reveal logic

- [ ] **Step 1: Add animation state + PlayControls**

```typescript
const [seqStep, setSeqStep] = useState(0);
const [messageGroups, setMessageGroups] = useState<Element[][]>([]);

const isAnimatedSequence = isSequenceDiagram && messageGroups.length > 0;
```

- [ ] **Step 2: Reveal messages based on current step**

```typescript
useEffect(() => {
  messageGroups.forEach((group, i) => {
    group.forEach(el => {
      (el as HTMLElement).style.opacity = i <= seqStep ? '1' : '0';
    });
  });
}, [seqStep, messageGroups]);
```

Messages at index ≤ currentStep are visible, rest hidden. Stepping forward reveals the next message with a 300ms fade-in transition.

- [ ] **Step 3: Render PlayControls**

```typescript
{isAnimatedSequence && (
  <PlayControls
    totalSteps={messageGroups.length}
    currentStep={seqStep}
    onStepChange={setSeqStep}
    autoPlayInterval={1500} // slower for reading message labels
  />
)}
```

- [ ] **Step 4: Verify with mock**

Test with:
```
sequenceDiagram
    Client->>Gateway: POST /login
    Gateway->>Auth: validateToken(jwt)
    Auth->>DB: SELECT * FROM users WHERE id=?
    DB-->>Auth: UserRecord
    Auth-->>Gateway: { valid: true, user: {...} }
    Gateway-->>Client: 200 OK
```

Expected: Initially, only the participant boxes show (no arrows). Clicking Play reveals messages one by one top to bottom. Each message fades in with 300ms transition. PlayControls show "1 / 6", "2 / 6", etc.

- [ ] **Step 5: Commit**

```bash
git add agent/webview/src/components/rich/MermaidDiagram.tsx
git commit -m "feat: step-through animation for Mermaid sequence diagrams"
```

---

## Phase 4: Showcase + CSS (Task 7)

### Task 7: Add Animated Examples to Showcase

**Files:**
- Modify: `agent/webview/src/showcase/mock-data.ts` — add animated flow mock
- Modify: `agent/webview/src/showcase.tsx` — add animated sections
- Modify: `agent/webview/src/styles/animations.css` — add glow keyframe if needed

- [ ] **Step 1: Add animated flow mock data**

```typescript
export const mockAnimatedFlow = JSON.stringify({
  title: 'Authentication Request Flow',
  direction: 'LR',
  nodes: [
    { id: 'client', label: 'Client', color: '#3b82f6' },
    { id: 'gateway', label: 'API Gateway' },
    { id: 'auth', label: 'Auth Service', color: '#8b5cf6' },
    { id: 'db', label: 'User DB', color: '#f59e0b' },
  ],
  edges: [
    { from: 'client', to: 'gateway', label: 'POST /login' },
    { from: 'gateway', to: 'auth', label: 'validate' },
    { from: 'auth', to: 'db', label: 'query user' },
  ],
  animated: true,
  highlightPath: ['client', 'gateway', 'auth', 'db', 'auth', 'gateway', 'client'],
  pathLabels: ['Login request', 'Forward to auth', 'Query user DB', 'Return user record', 'Issue JWT token', '200 OK + JWT'],
});

export const mockSequenceDiagram = `sequenceDiagram
    Client->>Gateway: POST /login {username, password}
    Gateway->>Auth: validateCredentials(username, password)
    Auth->>DB: SELECT * FROM users WHERE username=?
    DB-->>Auth: UserRecord {id, hash, roles}
    Auth->>Auth: bcrypt.compare(password, hash)
    Auth-->>Gateway: {valid: true, token: "eyJ..."}
    Gateway-->>Client: 200 OK {token, refreshToken}`;
```

- [ ] **Step 2: Add showcase sections**

```tsx
<Section title="Animated Flow (Request Path)">
  <p className="text-[10px] mb-2" style={{ color: 'var(--fg-muted)' }}>
    Click Play to step through the authentication flow. Uses highlightPath for request + response path.
  </p>
  <FlowDiagram source={mockAnimatedFlow} />
</Section>

<Section title="Animated Sequence Diagram">
  <p className="text-[10px] mb-2" style={{ color: 'var(--fg-muted)' }}>
    Messages reveal one at a time. Click Play or use step controls.
  </p>
  <MermaidDiagram source={mockSequenceDiagram} />
</Section>
```

- [ ] **Step 3: Add glow animation CSS**

In `animations.css`:
```css
@keyframes node-glow-pulse {
  0%, 100% { filter: drop-shadow(0 0 4px var(--accent)); }
  50% { filter: drop-shadow(0 0 10px var(--accent)); }
}
```

- [ ] **Step 4: Verify build + Commit**

---

## Implementation Notes

### LLM Integration

After this is implemented, add to the agent's system prompt (`AgentSystemPrompt.kt`):
```
When explaining system architecture or request flows, use animated flow diagrams:
- Add "animated": true to your ```flow JSON
- Optionally add "highlightPath": ["nodeId1", "nodeId2", ...] to focus on a specific path
- Node IDs in highlightPath can repeat (e.g., request path then response path back)
- Add "pathLabels": ["caption1", "caption2", ...] to explain each step

For sequence interactions, use standard Mermaid sequenceDiagram syntax — the UI automatically adds step-through animation.
```

### Graceful Degradation Chain

```
highlightPath valid + animated:true  →  Animate specific path with captions
         ↓ invalid IDs skipped
highlightPath partial               →  Animate valid portion
         ↓ all invalid or omitted
animated:true + acyclic graph        →  BFS animation from sources
         ↓ cyclic or no sources
animated:true + cyclic               →  Static diagram with flowDash (existing)
         ↓ animated omitted
No animated flag                     →  Standard static diagram
```

### Performance

- Animation state is simple integer (`animStep`) — no heavy computation per frame
- SVG elements are styled via inline properties, not re-rendered (no React reconciliation per step)
- Mermaid message show/hide uses CSS opacity transitions, not DOM manipulation
- PlayControls uses `setTimeout`, not `setInterval` — avoids drift and stale closures

### Accessibility + Reduced Motion

- PlayControls buttons have `title` attributes for screen readers
- Step counter provides numeric context ("3 / 7")
- Keyboard: Tab focuses play controls, Space/Enter triggers buttons
- **`prefers-reduced-motion` handling** (IMPORTANT): The existing CSS rule in `animations.css` disables CSS animations, but SVG `<animateMotion>` (traveling dots) and JS `setTimeout` (auto-advance) are unaffected. Implementers MUST:
  1. Add a `useReducedMotion()` hook: `const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches`
  2. When `reduced` is true: show all steps at once (all nodes full opacity, all edges visible), hide PlayControls, omit `<animateMotion>` elements
  3. For Mermaid: skip the hide/reveal logic, show all messages immediately
