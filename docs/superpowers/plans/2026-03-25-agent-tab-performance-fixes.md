# Agent Tab Performance Fixes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 12 performance findings from three concurrent audits (React perf, Vercel best practices, IntelliJ plugin performance) across the agent chat tab.

**Architecture:** Targeted fixes to existing code — no architectural changes. Fixes address memory leaks (MutationObserver, coroutine scope), unnecessary re-renders (missing memo, unstable references), and missing safety guards (error boundaries, timeouts, debounce).

**Tech Stack:** React 19, TypeScript, Zustand, Tailwind CSS, Kotlin, IntelliJ Platform SDK, JCEF

---

## File Map

| File | Changes |
|------|---------|
| `agent/webview/src/main.tsx` | Move MutationObserver into useEffect with cleanup |
| `agent/webview/src/components/input/RichInput.tsx` | Single editor-level MutationObserver, event delegation for chip removal |
| `agent/webview/src/components/agent/ToolCallChain.tsx` | Extract memoized ToolCallItem component |
| `agent/webview/src/components/agent/ThinkingView.tsx` | Change timer from 100ms to 1000ms |
| `agent/webview/src/components/input/InputBar.tsx` | Wrap ModelChip, PlanChip, MoreChip in memo() |
| `agent/webview/src/components/input/MentionDropdown.tsx` | useMemo on scoring, hoist regex + typeOrder, add debounce |
| `agent/webview/src/components/input/TicketDropdown.tsx` | Use unique callback IDs instead of global |
| `agent/webview/src/components/chat/ChatView.tsx` | Add ErrorBoundary wrapper per message |
| `agent/webview/src/components/chat/ErrorBoundary.tsx` | New: simple class-based error boundary |
| `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt` | Tie scope to Disposable, add withTimeout to JSQuery handlers |

---

### Task 1: Fix global MutationObserver leak in main.tsx

**Files:**
- Modify: `agent/webview/src/main.tsx:10-28`

- [ ] **Step 1:** Move the `popperObserver` into `App.tsx` inside a `useEffect` with cleanup return that calls `popperObserver.disconnect()`. Remove it from `main.tsx`.

- [ ] **Step 2:** Verify the Radix dropdown positioning still works by running `npm run dev` and clicking the Model / ··· / + dropdowns.

- [ ] **Step 3:** Commit: `fix(perf): move MutationObserver into React lifecycle with cleanup`

---

### Task 2: Fix RichInput per-chip MutationObserver leak + inline onclick

**Files:**
- Modify: `agent/webview/src/components/input/RichInput.tsx:181, 184-190`

- [ ] **Step 1:** Replace per-chip `new MutationObserver()` with a single editor-level observer. Add a `useEffect` that creates ONE `MutationObserver` on the editor div, tracks all chip removals by checking `querySelectorAll('[data-mention-label]')` against `mentionsRef.current`. Store observer in a `useRef` and disconnect on cleanup.

- [ ] **Step 2:** Replace inline `onclick="this.parentElement.remove()"` with event delegation. Add a single click handler on the editor div that detects clicks on `.chip-remove-btn` elements, removes the parent chip, and updates `mentionsRef`.

- [ ] **Step 3:** Ensure `clear()` method disconnects the observer ref properly.

- [ ] **Step 4:** Test: insert 3 chips, remove 1 via ×, clear all, insert again. Verify no console errors, mentions tracked correctly.

- [ ] **Step 5:** Commit: `fix(perf): single MutationObserver + event delegation in RichInput`

---

### Task 3: Fix AgentCefPanel coroutine scope lifecycle

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt:46, ~init block`

- [ ] **Step 1:** In the `init` block or constructor, register scope cancellation with `Disposer.register(parentDisposable) { scope.cancel() }`. This ensures the scope is cancelled when the panel's parent is disposed, even if `dispose()` is not called directly.

- [ ] **Step 2:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 3:** Commit: `fix(perf): tie AgentCefPanel coroutine scope to Disposable lifecycle`

---

### Task 4: Add timeout + error handling to JBCefJSQuery handlers

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt:287-330` (searchMentions, searchTickets, validateTicket handlers)

- [ ] **Step 1:** Wrap each `scope.launch` body in `try { withTimeout(5000L) { ... } } catch (e: Exception) { LOG.debug(...) }`. Import `kotlinx.coroutines.withTimeout`.

- [ ] **Step 2:** For `searchMentionsQuery` handler: on timeout, call `callJs("receiveMentionResults('[]')")` so the dropdown shows empty instead of hanging.

- [ ] **Step 3:** For `searchTicketsQuery` handler: on timeout, call `callJs("(window.__ticketSearchCallback)('[]')")`.

- [ ] **Step 4:** For `validateTicketQuery` handler: on timeout, call the callback with `{"valid":false}`.

- [ ] **Step 5:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 6:** Commit: `fix(perf): add 5s timeout + error handling to JCEF bridge handlers`

---

### Task 5: Memoize ToolCallChain items

**Files:**
- Modify: `agent/webview/src/components/agent/ToolCallChain.tsx:157-210`

- [ ] **Step 1:** Extract the inline `toolCalls.map()` body into a new `ToolCallItem = memo(function ToolCallItem({ tc }: { tc: ToolCall }) { ... })` component defined above `ToolCallChain`.

- [ ] **Step 2:** Update the map to: `{toolCalls.map(tc => <ToolCallItem key={tc.id} tc={tc} />)}`

- [ ] **Step 3:** Type-check: `npx tsc --noEmit`

- [ ] **Step 4:** Commit: `fix(perf): extract memoized ToolCallItem to prevent re-render storms`

---

### Task 6: Reduce ThinkingView timer from 100ms to 1000ms

**Files:**
- Modify: `agent/webview/src/components/agent/ThinkingView.tsx:20`

- [ ] **Step 1:** Change `setInterval(..., 100)` to `setInterval(..., 1000)`.

- [ ] **Step 2:** Commit: `fix(perf): reduce thinking timer from 100ms to 1000ms (10x fewer re-renders)`

---

### Task 7: Wrap InputBar chip components in memo()

**Files:**
- Modify: `agent/webview/src/components/input/InputBar.tsx:26-59, 63-88, 92-127, 128-152`

- [ ] **Step 1:** Wrap `ModelChip` in `memo()`: `const ModelChip = memo(function ModelChip(...) { ... })`.
- [ ] **Step 2:** Wrap `PlanChip` in `memo()`.
- [ ] **Step 3:** Wrap `SkillsChip` in `memo()`.
- [ ] **Step 4:** Wrap `MoreChip` in `memo()`.

- [ ] **Step 5:** Type-check: `npx tsc --noEmit`

- [ ] **Step 6:** Commit: `fix(perf): memo() on ModelChip, PlanChip, SkillsChip, MoreChip`

---

### Task 8: Add error boundary to ChatView message rendering

**Files:**
- Create: `agent/webview/src/components/chat/ErrorBoundary.tsx`
- Modify: `agent/webview/src/components/chat/ChatView.tsx:149-164`

- [ ] **Step 1:** Create `ErrorBoundary.tsx` — a class component with `componentDidCatch` that renders a fallback message styled with `var(--error)` color, showing "Failed to render message" with a "Dismiss" button.

- [ ] **Step 2:** In `ChatView.tsx`, wrap each message in `<ErrorBoundary key={msg.id}>`. Import from `./ErrorBoundary`.

- [ ] **Step 3:** Type-check: `npx tsc --noEmit`

- [ ] **Step 4:** Commit: `fix(perf): add per-message ErrorBoundary to prevent chat crash on render failure`

---

### Task 9: Memoize MentionDropdown scoring + add debounce

**Files:**
- Modify: `agent/webview/src/components/input/MentionDropdown.tsx:44-108`

- [ ] **Step 1:** Hoist `typeOrder` to module-level constant: `const TYPE_ORDER: Record<string, number> = { file: 0, folder: 1, symbol: 2 };`

- [ ] **Step 2:** Wrap the scoring + sorting chain in `useMemo(() => { ... }, [mentionResults, query])`.

- [ ] **Step 3:** Add 200ms debounce to the `useEffect` that calls `_searchMentions`: wrap the call in `setTimeout` with cleanup `clearTimeout`.

- [ ] **Step 4:** Type-check: `npx tsc --noEmit`

- [ ] **Step 5:** Commit: `fix(perf): memoize mention scoring, hoist constants, debounce search`

---

### Task 10: Fix TicketDropdown global callback race condition

**Files:**
- Modify: `agent/webview/src/components/input/TicketDropdown.tsx:44-59`

- [ ] **Step 1:** Replace `(window as any).__ticketSearchCallback = handler` with a unique callback ID: `const cbId = '__ticketSearch_' + Math.random().toString(36).slice(2)`. Set `(window as any)[cbId] = handler`. Pass `cbId` to `_searchTickets` if the bridge supports it, or keep the current global but add a guard that checks component mount state before calling `setResults`.

- [ ] **Step 2:** In cleanup: `delete (window as any)[cbId]`.

- [ ] **Step 3:** Type-check: `npx tsc --noEmit`

- [ ] **Step 4:** Commit: `fix(perf): unique callback ID per TicketDropdown instance to prevent race`

---

### Task 11: Add pendingCalls queue cap to AgentCefPanel

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt:832-839`

- [ ] **Step 1:** In `callJs()`, add a size check: `if (pendingCalls.size >= 10_000) { LOG.warn("Pending JS calls queue exceeded 10K"); return }` before `pendingCalls.add(code)`.

- [ ] **Step 2:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 3:** Commit: `fix(perf): cap pendingCalls queue at 10K to prevent memory spike`

---

### Task 12: Final verification

- [ ] **Step 1:** Full TypeScript check: `cd agent/webview && npx tsc --noEmit`
- [ ] **Step 2:** Full Kotlin compile: `./gradlew :agent:compileKotlin :core:compileKotlin :jira:compileKotlin`
- [ ] **Step 3:** Run agent tests: `./gradlew :agent:test`
- [ ] **Step 4:** Build webview: `cd agent/webview && npm run build`
- [ ] **Step 5:** Build plugin ZIP: `./gradlew clean buildPlugin`
- [ ] **Step 6:** Commit version bump + push + release
