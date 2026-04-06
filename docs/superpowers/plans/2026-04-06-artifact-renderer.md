# Artifact Renderer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable AI agents to produce live, interactive React components in chat — via a `render_artifact` tool (primary) or ` ```react ` code fences (fallback) — transpiled with Sucrase in a sandboxed iframe with IDE source navigation.

**Architecture:** Agent calls `render_artifact(title, source)` → Kotlin pushes to webview → ArtifactRenderer creates sandboxed iframe with react-runner + Sucrase → live interactive component with `navigateToFile` bridge. Fallback: ` ```react ` code fence in markdown routed by MarkdownRenderer.

**Tech Stack:** Kotlin (new tool + bridge), React 18, Sucrase 3.x, react-runner 1.x, Recharts, Lucide React. Vite build. Sandbox HTML is standalone with all libs inlined.

**Spec:** `docs/superpowers/specs/2026-04-06-artifact-renderer-design.md` (Rev 2)

---

### Task 1: Install Dependencies

**Files:**
- Modify: `agent/webview/package.json`

- [ ] **Step 1: Add sucrase, react-runner, and recharts**

```bash
cd agent/webview && npm install sucrase@^3.35.0 react-runner@^1.0.5 recharts@^2.15.0
```

Note: `lucide-react` is already installed.

- [ ] **Step 2: Verify install**

```bash
cd agent/webview && node -e "require('sucrase'); require('react-runner'); require('recharts'); console.log('OK')"
```

Expected: `OK`

- [ ] **Step 3: Commit**

```bash
git add agent/webview/package.json agent/webview/package-lock.json
git commit -m "deps(webview): add sucrase, react-runner, recharts for artifact renderer"
```

---

### Task 2: Create the Sandbox HTML

**Files:**
- Create: `agent/webview/public/artifact-sandbox.html`

Self-contained HTML file with React 18, Sucrase, react-runner inlined. Runs inside sandboxed iframe. Receives JSX source via postMessage, transpiles, renders, reports height/errors/bridge calls back to parent.

- [ ] **Step 1: Write artifact-sandbox.html**

Create `agent/webview/public/artifact-sandbox.html` with:
- `<script type="module">` that imports react, react-dom/client, react-runner
- postMessage listener for `render` and `theme` messages
- `sendToParent()` helper for `ready`, `rendered`, `error`, `bridge`, `console` messages
- Error Boundary class component
- Console interception (forward to parent)
- ResizeObserver on `#root` to report height changes
- Theme CSS variable injection from parent messages
- Bridge scope with `navigateToFile` that sends via postMessage

Reference the full sandbox code from the spec's architecture section. The sandbox should:
1. Initialize: import React, ReactDOMClient, react-runner
2. Create React root on `#root` div
3. Send `{ type: 'ready' }` to parent
4. On `render` message: build scope (React hooks + bridge + libraries), render via `react-runner`'s `Runner` component wrapped in ErrorBoundary
5. On `theme` message: apply CSS variables to `:root`
6. After every render: report `document.body.scrollHeight` via `{ type: 'rendered', height }`
7. On error: send `{ type: 'error', phase, message, line }` and show inline error display

- [ ] **Step 2: Commit**

```bash
git add agent/webview/public/artifact-sandbox.html
git commit -m "feat(webview): add artifact sandbox HTML with react-runner protocol"
```

---

### Task 3: Vite Build Integration for Sandbox

**Files:**
- Modify: `agent/webview/vite.config.ts`

- [ ] **Step 1: Read current vite.config.ts**

Read `agent/webview/vite.config.ts` to understand the current multi-entry build and manual chunks config.

- [ ] **Step 2: Add sandbox as a build entry**

Add `'artifact-sandbox'` to `build.rollupOptions.input`. Add `recharts` to `manualChunks`.

- [ ] **Step 3: Build and verify**

```bash
cd agent/webview && npm run build
```

Verify `agent/src/main/resources/webview/dist/artifact-sandbox.html` exists.

- [ ] **Step 4: Commit**

```bash
git add agent/webview/vite.config.ts
git commit -m "build(webview): add artifact-sandbox entry point to Vite build"
```

---

### Task 4: Add Artifact to VisualizationType, Settings, and RichBlock

**Files:**
- Modify: `agent/webview/src/bridge/types.ts`
- Modify: `agent/webview/src/stores/settingsStore.ts`
- Modify: `agent/webview/src/components/rich/RichBlock.tsx`

- [ ] **Step 1: Add 'artifact' to VisualizationType**

In `types.ts`:
```typescript
export type VisualizationType = '...' | 'artifact';
```

Add `ArtifactState` interface:
```typescript
export interface ArtifactState {
    title: string;
    source: string;
}
```

Add `artifact?: ArtifactState` to the `Message` interface.

- [ ] **Step 2: Add artifact config to settings defaults**

In `settingsStore.ts`:
```typescript
artifact: { enabled: true, autoRender: true, defaultExpanded: false, maxHeight: 400 },
```

- [ ] **Step 3: Add artifact to RichBlock TYPE_META**

In `RichBlock.tsx`:
```typescript
artifact: { icon: '\u2B22', label: 'Interactive' },
```

- [ ] **Step 4: Commit**

```bash
git add agent/webview/src/bridge/types.ts agent/webview/src/stores/settingsStore.ts agent/webview/src/components/rich/RichBlock.tsx
git commit -m "feat(webview): add artifact visualization type to settings, types, and RichBlock"
```

---

### Task 5: Create ArtifactRenderer Component

**Files:**
- Create: `agent/webview/src/components/rich/ArtifactRenderer.tsx`

Core React component. Creates sandboxed iframe, manages postMessage communication, handles errors, relays bridge calls.

- [ ] **Step 1: Write ArtifactRenderer.tsx**

Create `agent/webview/src/components/rich/ArtifactRenderer.tsx` with:
- Props: `{ source: string; title?: string }`
- Creates iframe pointing to `artifact-sandbox.html`
- Listens for postMessage from iframe (`ready`, `rendered`, `error`, `bridge`, `console`)
- On `ready`: sends theme + render message
- On `rendered`: updates iframe height, clears loading state
- On `error`: sets error state (RichBlock shows error card)
- On `bridge`: validates action against `ALLOWED_BRIDGE_ACTIONS` (`navigateToFile` only), routes to `kotlinBridge`
- Wraps in `RichBlock` with type `'artifact'`
- Re-sends theme on theme change
- Re-renders on source change

Reference the full component code in the spec.

- [ ] **Step 2: Verify TypeScript compiles**

```bash
cd agent/webview && npx tsc --noEmit
```

- [ ] **Step 3: Commit**

```bash
git add agent/webview/src/components/rich/ArtifactRenderer.tsx
git commit -m "feat(webview): add ArtifactRenderer component with sandbox iframe and bridge relay"
```

---

### Task 6: Wire ArtifactRenderer into MarkdownRenderer (Code Fence Path)

**Files:**
- Modify: `agent/webview/src/components/markdown/MarkdownRenderer.tsx`

- [ ] **Step 1: Add import and case**

Add import:
```typescript
import { ArtifactRenderer } from '@/components/rich/ArtifactRenderer';
```

Add cases in the `switch (language)` block before the default:
```typescript
case 'react':
case 'artifact':
    return <ArtifactRenderer source={codeString} />;
```

- [ ] **Step 2: Build and verify**

```bash
cd agent/webview && npm run build
```

- [ ] **Step 3: Commit**

```bash
git add agent/webview/src/components/markdown/MarkdownRenderer.tsx
git commit -m "feat(webview): route react/artifact code fences to ArtifactRenderer"
```

---

### Task 7: Add renderArtifact to JS Bridge and Chat Store (Tool Path)

**Files:**
- Modify: `agent/webview/src/bridge/jcef-bridge.ts`
- Modify: `agent/webview/src/stores/chatStore.ts`
- Modify: `agent/webview/src/components/chat/ChatView.tsx`

- [ ] **Step 1: Add renderArtifact bridge handler**

In `jcef-bridge.ts`, add to the bridge functions (Kotlin → JS):
```typescript
renderArtifact(payload: string) {
    stores?.getChatStore().addArtifact(payload);
},
```

Register it on `window` in the early registration block alongside existing bridge functions.

- [ ] **Step 2: Add addArtifact action to chatStore**

In `chatStore.ts`, add the action:
```typescript
addArtifact(payload: string) {
    const { title, source } = JSON.parse(payload);
    const msgId = nextId('artifact');
    set((state) => ({
        messages: [...state.messages, {
            id: msgId,
            role: 'system' as MessageRole,
            content: `artifact:${title}`,
            timestamp: Date.now(),
            artifact: { title, source },
        }],
    }));
},
```

Add `addArtifact` to the store interface.

- [ ] **Step 3: Add artifact rendering in ChatView**

In `ChatView.tsx`, in the message rendering logic, add a check before the existing message type checks:
```tsx
if (msg.artifact) {
    return (
        <ErrorBoundary key={msg.id}>
            <ArtifactRenderer source={msg.artifact.source} title={msg.artifact.title} />
        </ErrorBoundary>
    );
}
```

Add the ArtifactRenderer import.

- [ ] **Step 4: Build and verify**

```bash
cd agent/webview && npm run build
```

- [ ] **Step 5: Commit**

```bash
git add agent/webview/src/bridge/jcef-bridge.ts agent/webview/src/stores/chatStore.ts agent/webview/src/components/chat/ChatView.tsx
git commit -m "feat(webview): add renderArtifact bridge handler and chat store integration"
```

---

### Task 8: Create RenderArtifactTool (Kotlin)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RenderArtifactTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolResult.kt`

- [ ] **Step 1: Add ArtifactPayload to ToolResult**

Read `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolResult.kt`. Add:

```kotlin
data class ArtifactPayload(
    val title: String,
    val source: String,
)
```

Add `val artifact: ArtifactPayload? = null` to the `ToolResult` data class.

- [ ] **Step 2: Create RenderArtifactTool**

Create `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RenderArtifactTool.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.ArtifactPayload
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class RenderArtifactTool : AgentTool {
    override val name = "render_artifact"
    override val description = """Render an interactive React component in the chat as a visual artifact. Use alongside your text response when a visualization would help the user understand architecture, flows, hierarchies, or data comparisons.

The component renders in a sandboxed iframe with:
- bridge.navigateToFile(path, line) — click to open file in IDE
- bridge.isDark, bridge.colors — theme-aware rendering
- Lucide React icons (FileCode, GitBranch, Database, Shield, Zap, Server, etc.)
- Recharts (BarChart, PieChart, LineChart, AreaChart, Tooltip, Legend, etc.)
- React hooks (useState, useEffect, useCallback, useMemo, useRef)

Use when: 3+ entities with relationships, multi-step flows, data comparisons as charts, or user explicitly asked for a visual.
Do NOT use when: short text answers, fewer than 3 items, yes/no questions, or text is sufficient.

The source must export a default function component receiving { bridge } prop. All data must be inline — no fetch or file reads."""

    override val parameters = FunctionParameters(
        properties = mapOf(
            "title" to ParameterProperty(
                type = "string",
                description = "Short title for the artifact (e.g., 'Authentication Flow', 'Module Dependencies')"
            ),
            "source" to ParameterProperty(
                type = "string",
                description = "JSX source code. Must export a default function component that receives { bridge } prop. All data must be inline — no fetch, no file reads."
            )
        ),
        required = listOf("title", "source")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val title = params["title"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Error: 'title' parameter is required.",
                summary = "Error: missing title",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val source = params["source"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Error: 'source' parameter is required.",
                summary = "Error: missing source",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        // Basic validation: source should contain export default
        if (!source.contains("export default") && !source.contains("export default function")) {
            return ToolResult(
                content = "Error: source must export a default function component (e.g., 'export default function MyComponent({ bridge }) { ... }').",
                summary = "Error: missing default export",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return ToolResult(
            content = "[Artifact: $title] Interactive component rendered in chat.",
            summary = "Rendered artifact: $title",
            tokenEstimate = 15,
            artifact = ArtifactPayload(title = title, source = source)
        )
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RenderArtifactTool.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolResult.kt
git commit -m "feat(agent): add RenderArtifactTool and ArtifactPayload on ToolResult"
```

---

### Task 9: Register Tool and Wire Artifact Callback in AgentLoop

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt`

- [ ] **Step 1: Register RenderArtifactTool in AgentService**

In `AgentService.registerAllTools()`, add:
```kotlin
registry.registerCore(RenderArtifactTool())
```

- [ ] **Step 2: Add artifact detection in AgentLoop**

In `AgentLoop`, after a tool result is received, check for `artifact` payload:
```kotlin
if (toolResult.artifact != null) {
    onArtifactRendered?.invoke(toolResult.artifact)
}
```

Add `onArtifactRendered: ((ArtifactPayload) -> Unit)?` to AgentLoop's callback parameters (following the same pattern as `onToolCall`, `onTokenUpdate`, etc.).

- [ ] **Step 3: Add renderArtifact bridge method to AgentCefPanel**

```kotlin
fun renderArtifact(title: String, source: String) {
    val payload = buildJsonObject {
        put("title", title)
        put("source", source)
    }.toString()
    callJs("renderArtifact(${jsonStr(payload)})")
}
```

- [ ] **Step 4: Wire callback in AgentController**

In AgentController's `executeTask` call, wire `onArtifactRendered`:
```kotlin
onArtifactRendered = { payload ->
    invokeLater {
        dashboard.renderArtifact(payload.title, payload.source)
    }
}
```

- [ ] **Step 5: Verify compilation**

```bash
./gradlew :agent:compileKotlin
```

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt
git commit -m "feat(agent): register RenderArtifactTool, wire artifact callback through AgentLoop to webview"
```

---

### Task 10: Add render_artifact to Bundled Agent Tool Lists

**Files:**
- Modify: `agent/src/main/resources/agents/explorer.md`
- Modify: `agent/src/main/resources/agents/code-reviewer.md`
- Modify: `agent/src/main/resources/agents/architect-reviewer.md`
- Modify: `agent/src/main/resources/agents/performance-engineer.md`
- Modify: `agent/src/main/resources/agents/security-auditor.md`

The `render_artifact` tool needs to be in the tool whitelist of agents that should produce visualizations.

- [ ] **Step 1: Add render_artifact to tool lists**

Add `render_artifact` to the `tools:` line in the YAML frontmatter of:
- `explorer.md`
- `code-reviewer.md`
- `architect-reviewer.md`
- `performance-engineer.md`
- `security-auditor.md`

Do NOT add to write agents (spring-boot-engineer, test-automator, refactoring-specialist, devops-engineer, general-purpose) — they implement, not visualize.

- [ ] **Step 2: Commit**

```bash
git add agent/src/main/resources/agents/*.md
git commit -m "feat(agent): add render_artifact to explorer and reviewer agent tool lists"
```

---

### Task 11: Add Visualization Guidance to Agent Prompts

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt`
- Modify: `agent/src/main/resources/agents/explorer.md`
- Modify: `agent/src/main/resources/agents/code-reviewer.md`
- Modify: `agent/src/main/resources/agents/architect-reviewer.md`
- Modify: `agent/src/main/resources/agents/performance-engineer.md`
- Modify: `agent/src/main/resources/agents/security-auditor.md`

- [ ] **Step 1: Add capability note to SystemPrompt.kt**

In the `capabilities()` function, add:
```
- render_artifact tool: produce interactive React visualizations in chat. Use for architecture diagrams, sequence flows, class hierarchies, charts. Component gets bridge.navigateToFile for IDE navigation, theme colors, Lucide icons, and Recharts.
```

- [ ] **Step 2: Add "Producing Visualizations" section to explorer.md**

Add before the "Process" section. Include: when to visualize, when not to, component contract, available libraries, and a code example showing a service map with `bridge.navigateToFile`.

- [ ] **Step 3: Add short visualization note to other agents**

Add to code-reviewer, architect-reviewer, performance-engineer, security-auditor (before "## Completion"):

```markdown
> **Visualization:** Use `render_artifact` for interactive visuals when findings involve 3+ entities with relationships, flows, or data comparisons. Component receives `{ bridge }` with `navigateToFile(path, line)`, Lucide icons, and Recharts.
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt agent/src/main/resources/agents/*.md
git commit -m "feat(agent): add artifact visualization guidance to system prompt and agent configs"
```

---

### Task 12: Write Tests

**Files:**
- Create: `agent/webview/src/__tests__/ArtifactRenderer.test.tsx`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/RenderArtifactToolTest.kt`

- [ ] **Step 1: Write ArtifactRenderer React tests**

Test: renders iframe with sandbox attribute, shows loading state initially, no allow-same-origin in sandbox, accepts source prop.

- [ ] **Step 2: Write RenderArtifactTool Kotlin tests**

Test: returns artifact payload on valid input, error on missing title, error on missing source, error on source without default export, allowedWorkers includes ORCHESTRATOR and ANALYZER.

- [ ] **Step 3: Run tests**

```bash
cd agent/webview && npm test
./gradlew :agent:test --tests "*.RenderArtifactToolTest"
```

- [ ] **Step 4: Commit**

```bash
git add agent/webview/src/__tests__/ArtifactRenderer.test.tsx agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/RenderArtifactToolTest.kt
git commit -m "test(agent): add RenderArtifactTool and ArtifactRenderer tests"
```

---

### Task 13: Build, Test, and Release

**Files:**
- Modify: `gradle.properties` (version bump)

- [ ] **Step 1: Full webview build**

```bash
cd agent/webview && npm run build
```

- [ ] **Step 2: Run all webview tests**

```bash
cd agent/webview && npm test
```

- [ ] **Step 3: Run Kotlin agent tests**

```bash
./gradlew :agent:test
```

- [ ] **Step 4: Full plugin build**

```bash
./gradlew clean buildPlugin
```

- [ ] **Step 5: Bump version to 0.64.0-beta**

- [ ] **Step 6: Commit, push, release**

```bash
git add -A
git commit -m "feat(agent): artifact renderer — interactive JSX visualizations in chat

render_artifact tool + react code fence fallback. react-runner + Sucrase in
sandboxed iframe. IDE source navigation via curated bridge. Recharts for
data viz, Lucide for icons. Zero network dependency."

git push origin rewrite/lean-agent

gh release create v0.64.0-beta \
  --title "v0.64.0-beta — Interactive Artifact Renderer" \
  --notes "..." \
  --target rewrite/lean-agent \
  build/distributions/intellij-workflow-orchestrator-0.64.0-beta.zip
```
