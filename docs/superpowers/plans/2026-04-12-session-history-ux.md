# Session History UX — Integrated Webview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move session history from a separate Swing tab into the JCEF chat webview as a React view, matching Cline's integrated one-panel UX.

**Architecture:** The webview's Zustand store gains a `viewMode: 'history' | 'chat'` state. `App.tsx` conditionally renders either `HistoryView` (session cards + search) or the existing `ChatView`. Kotlin pushes `HistoryItem[]` JSON via a new `_loadSessionHistory` bridge function. User actions (resume, delete, favorite) flow back via `JBCefJSQuery` bridges. The old Swing `HistoryPanel` and `HistoryTabProvider` are deleted.

**Tech Stack:** React 19, Zustand, Tailwind CSS, Lucide React icons, Kotlin, JBCefJSQuery, kotlinx.serialization

**Design reference:** Stitch mockups at `http://localhost:8765/history-panel-400px.html` (single-column 400px panel)

**Branch:** `feature/session-persistence-cline-port`
**Worktree:** `.worktrees/session-persistence`

---

## File Map

### New Files (React — webview)
| File | Responsibility |
|------|---------------|
| `agent/webview/src/components/history/HistoryView.tsx` | Top-level history view: header bar, search input, card list, empty state |
| `agent/webview/src/components/history/SessionCard.tsx` | Single session card: title, metadata, favorite star, hover actions |

### Modified Files (React — webview)
| File | Change |
|------|--------|
| `agent/webview/src/stores/chatStore.ts` | Add `viewMode`, `historyItems`, `historySearch` state + actions |
| `agent/webview/src/bridge/jcef-bridge.ts` | Add `loadSessionHistory`, `showHistoryView` K→JS functions + `showSession`, `deleteSession`, `toggleFavorite`, `startNewSession` JS→K functions |
| `agent/webview/src/bridge/types.ts` | Add `HistoryItem` TypeScript interface |
| `agent/webview/src/App.tsx` | Conditionally render `HistoryView` vs existing chat layout based on `viewMode` |

### Modified Files (Kotlin)
| File | Change |
|------|--------|
| `agent/.../session/MessageStateHandler.kt` | Add `deleteSession()`, `toggleFavorite()` static methods |
| `agent/.../ui/AgentCefPanel.kt` | Add `loadSessionHistory()`, `showHistoryView()` K→JS + 4 new JS→K `JBCefJSQuery` bridges |
| `agent/.../ui/AgentDashboardPanel.kt` | Delegate new bridge methods |
| `agent/.../ui/AgentController.kt` | Add `showHistory()`, `handleDeleteSession()`, `handleToggleFavorite()`, `handleStartNewSession()` |

### Deleted Files
| File | Reason |
|------|--------|
| `agent/.../ui/HistoryPanel.kt` | Replaced by React HistoryView |
| `agent/.../ui/HistoryTabProvider.kt` | 7th tab removed, restoring 6-tab UX constraint |

### Test Files
| File | What it tests |
|------|--------------|
| `agent/src/test/.../session/MessageStateHandlerDeleteFavoriteTest.kt` | `deleteSession()` and `toggleFavorite()` on disk |

---

## Task 1: Add `deleteSession` and `toggleFavorite` to MessageStateHandler

**Files:**
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/session/MessageStateHandlerDeleteFavoriteTest.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/MessageStateHandler.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.workflow.orchestrator.agent.session

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MessageStateHandlerDeleteFavoriteTest {

    @TempDir
    lateinit var tempDir: File

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun writeIndex(items: List<HistoryItem>) {
        File(tempDir, "sessions.json").writeText(json.encodeToString(items))
    }

    private fun readIndex(): List<HistoryItem> =
        MessageStateHandler.loadGlobalIndex(tempDir)

    private fun createSessionDir(sessionId: String) {
        val dir = File(tempDir, "sessions/$sessionId")
        dir.mkdirs()
        File(dir, "ui_messages.json").writeText("[]")
        File(dir, "api_conversation_history.json").writeText("[]")
    }

    private fun sessionDirExists(sessionId: String): Boolean =
        File(tempDir, "sessions/$sessionId").exists()

    // --- deleteSession tests ---

    @Test
    fun `deleteSession removes entry from global index`() {
        val items = listOf(
            HistoryItem(id = "s1", ts = 1000, task = "Task 1"),
            HistoryItem(id = "s2", ts = 2000, task = "Task 2"),
            HistoryItem(id = "s3", ts = 3000, task = "Task 3"),
        )
        writeIndex(items)
        createSessionDir("s2")

        MessageStateHandler.deleteSession(tempDir, "s2")

        val remaining = readIndex()
        assertEquals(2, remaining.size)
        assertTrue(remaining.none { it.id == "s2" })
        assertEquals("s1", remaining[0].id)
        assertEquals("s3", remaining[1].id)
    }

    @Test
    fun `deleteSession removes session directory from disk`() {
        writeIndex(listOf(HistoryItem(id = "s1", ts = 1000, task = "Task 1")))
        createSessionDir("s1")
        assertTrue(sessionDirExists("s1"))

        MessageStateHandler.deleteSession(tempDir, "s1")

        assertFalse(sessionDirExists("s1"))
    }

    @Test
    fun `deleteSession is no-op for unknown session id`() {
        val items = listOf(HistoryItem(id = "s1", ts = 1000, task = "Task 1"))
        writeIndex(items)

        MessageStateHandler.deleteSession(tempDir, "unknown")

        assertEquals(1, readIndex().size)
    }

    @Test
    fun `deleteSession works when sessions json does not exist`() {
        assertDoesNotThrow {
            MessageStateHandler.deleteSession(tempDir, "nonexistent")
        }
    }

    // --- toggleFavorite tests ---

    @Test
    fun `toggleFavorite flips false to true`() {
        writeIndex(listOf(HistoryItem(id = "s1", ts = 1000, task = "Task 1", isFavorited = false)))

        MessageStateHandler.toggleFavorite(tempDir, "s1")

        val item = readIndex().single()
        assertTrue(item.isFavorited)
    }

    @Test
    fun `toggleFavorite flips true to false`() {
        writeIndex(listOf(HistoryItem(id = "s1", ts = 1000, task = "Task 1", isFavorited = true)))

        MessageStateHandler.toggleFavorite(tempDir, "s1")

        val item = readIndex().single()
        assertFalse(item.isFavorited)
    }

    @Test
    fun `toggleFavorite only affects target session`() {
        writeIndex(listOf(
            HistoryItem(id = "s1", ts = 1000, task = "Task 1", isFavorited = false),
            HistoryItem(id = "s2", ts = 2000, task = "Task 2", isFavorited = true),
        ))

        MessageStateHandler.toggleFavorite(tempDir, "s1")

        val items = readIndex()
        assertTrue(items.first { it.id == "s1" }.isFavorited)
        assertTrue(items.first { it.id == "s2" }.isFavorited) // unchanged
    }

    @Test
    fun `toggleFavorite is no-op for unknown session id`() {
        writeIndex(listOf(HistoryItem(id = "s1", ts = 1000, task = "Task 1", isFavorited = false)))

        MessageStateHandler.toggleFavorite(tempDir, "unknown")

        assertFalse(readIndex().single().isFavorited) // unchanged
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd .worktrees/session-persistence && ./gradlew :agent:test --tests "*.MessageStateHandlerDeleteFavoriteTest" -x verifyPlugin`
Expected: Compilation error — `deleteSession` and `toggleFavorite` not defined.

- [ ] **Step 3: Implement `deleteSession` and `toggleFavorite`**

Add to the companion object in `MessageStateHandler.kt`, after `loadGlobalIndex`:

```kotlin
fun deleteSession(baseDir: File, sessionId: String) {
    val indexFile = File(baseDir, "sessions.json")
    if (indexFile.exists()) {
        try {
            val items = loaderJson.decodeFromString<List<HistoryItem>>(indexFile.readText())
            val filtered = items.filter { it.id != sessionId }
            AtomicFileWriter.write(indexFile) { loaderJson.encodeToString(filtered) }
        } catch (_: Exception) { /* corrupted index, skip */ }
    }
    val sessionDir = File(baseDir, "sessions/$sessionId")
    if (sessionDir.exists()) {
        sessionDir.deleteRecursively()
    }
}

fun toggleFavorite(baseDir: File, sessionId: String) {
    val indexFile = File(baseDir, "sessions.json")
    if (!indexFile.exists()) return
    try {
        val items = loaderJson.decodeFromString<List<HistoryItem>>(indexFile.readText())
        val updated = items.map { item ->
            if (item.id == sessionId) item.copy(isFavorited = !item.isFavorited) else item
        }
        if (updated != items) {
            AtomicFileWriter.write(indexFile) { loaderJson.encodeToString(updated) }
        }
    } catch (_: Exception) { /* corrupted index, skip */ }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd .worktrees/session-persistence && ./gradlew :agent:test --tests "*.MessageStateHandlerDeleteFavoriteTest" -x verifyPlugin`
Expected: All 8 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd .worktrees/session-persistence
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/session/MessageStateHandlerDeleteFavoriteTest.kt
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/session/MessageStateHandler.kt
git commit -m "feat(session): add deleteSession and toggleFavorite to MessageStateHandler"
```

---

## Task 2: Add `HistoryItem` TypeScript interface

**Files:**
- Modify: `agent/webview/src/bridge/types.ts`

- [ ] **Step 1: Add the HistoryItem interface**

Add at the end of `types.ts`, after the `UiMessage` interface:

```typescript
/** Mirrors Kotlin HistoryItem from sessions.json */
export interface HistoryItem {
  id: string;
  ts: number;
  task: string;
  tokensIn: number;
  tokensOut: number;
  cacheWrites?: number | null;
  cacheReads?: number | null;
  totalCost: number;
  modelId?: string | null;
  isFavorited: boolean;
}
```

- [ ] **Step 2: Commit**

```bash
cd .worktrees/session-persistence
git add agent/webview/src/bridge/types.ts
git commit -m "feat(webview): add HistoryItem TypeScript interface"
```

---

## Task 3: Add `viewMode` and history state to chatStore

**Files:**
- Modify: `agent/webview/src/stores/chatStore.ts`

- [ ] **Step 1: Add history state fields to ChatState interface**

Add these fields to the `ChatState` interface (after `restoredInputText`):

```typescript
viewMode: 'history' | 'chat';
historyItems: HistoryItem[];
historySearch: string;
```

Add the import for `HistoryItem` at the top:
```typescript
import type { HistoryItem } from '../bridge/types';
```

- [ ] **Step 2: Add initial values in the store creation**

In the `create<ChatState & ChatActions>` call, add the initial values:

```typescript
viewMode: 'chat',
historyItems: [],
historySearch: '',
```

- [ ] **Step 3: Add history actions to ChatActions interface**

Add these to the `ChatActions` interface:

```typescript
setViewMode(mode: 'history' | 'chat'): void;
setHistoryItems(items: HistoryItem[]): void;
setHistorySearch(query: string): void;
```

- [ ] **Step 4: Implement the actions**

In the store creation, add:

```typescript
setViewMode(mode) {
  set({ viewMode: mode });
},
setHistoryItems(items) {
  set({ historyItems: items });
},
setHistorySearch(query) {
  set({ historySearch: query });
},
```

- [ ] **Step 5: Update `hydrateFromUiMessages` to switch to chat view**

In the existing `hydrateFromUiMessages` action, add `viewMode: 'chat'` to the `set()` call:

```typescript
hydrateFromUiMessages(uiMessages: UiMessage[]) {
  const visible = uiMessages.filter(
    m => m.say !== 'API_REQ_STARTED' && m.say !== 'API_REQ_FINISHED'
  );
  const converted = visible.map((msg, idx) => convertUiMessageToStoreMessage(msg, idx));
  set({ messages: converted, activeStream: null, viewMode: 'chat' });
},
```

- [ ] **Step 6: Update `startSession` to switch to chat view**

In the existing `startSession` action, add `viewMode: 'chat'` to the `set()` call so new sessions show the chat.

- [ ] **Step 7: Commit**

```bash
cd .worktrees/session-persistence
git add agent/webview/src/stores/chatStore.ts
git commit -m "feat(webview): add viewMode and history state to chatStore"
```

---

## Task 4: Create `SessionCard` React component

**Files:**
- Create: `agent/webview/src/components/history/SessionCard.tsx`

- [ ] **Step 1: Create the component**

```tsx
import { Clock, Star, MessageSquare, Trash2 } from 'lucide-react';
import { useState } from 'react';
import type { HistoryItem } from '../../bridge/types';

function formatTimeAgo(ts: number): string {
  const seconds = Math.floor((Date.now() - ts) / 1000);
  if (seconds < 60) return 'just now';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d ago`;
  const weeks = Math.floor(days / 7);
  return `${weeks}w ago`;
}

function formatTokens(count: number): string {
  if (count >= 1000) return `${(count / 1000).toFixed(1)}K tok`;
  return `${count} tok`;
}

function formatCost(cost: number): string {
  if (cost <= 0) return '';
  return `$${cost.toFixed(2)}`;
}

function formatModelId(modelId?: string | null): string {
  if (!modelId) return '';
  // "claude-opus-4" → "opus-4", "claude-sonnet-4" → "sonnet-4"
  return modelId.replace(/^claude-/, '');
}

interface SessionCardProps {
  item: HistoryItem;
  onResume: (id: string) => void;
  onDelete: (id: string) => void;
  onToggleFavorite: (id: string) => void;
}

export function SessionCard({ item, onResume, onDelete, onToggleFavorite }: SessionCardProps) {
  const [hovered, setHovered] = useState(false);

  const modelLabel = formatModelId(item.modelId);
  const costLabel = formatCost(item.totalCost);
  const tokenLabel = formatTokens(item.tokensIn + item.tokensOut);

  return (
    <div
      className={`
        rounded-[10px] p-2.5 cursor-pointer transition-colors duration-150
        bg-[var(--toolbar-bg)] border border-[var(--border)]
        ${hovered ? 'bg-[var(--hover-overlay-strong)]' : ''}
      `}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      onClick={() => onResume(item.id)}
    >
      {/* Title + Star */}
      <div className="flex items-start gap-2">
        <p className="flex-1 text-[13px] font-semibold text-[var(--fg)] leading-tight line-clamp-2">
          {item.task}
        </p>
        <button
          className="shrink-0 mt-0.5 p-0.5 hover:opacity-80 transition-opacity"
          onClick={(e) => {
            e.stopPropagation();
            onToggleFavorite(item.id);
          }}
          title={item.isFavorited ? 'Unfavorite' : 'Favorite'}
        >
          <Star
            size={14}
            className={item.isFavorited
              ? 'fill-[var(--warning)] text-[var(--warning)]'
              : 'text-[var(--fg-muted)]'
            }
          />
        </button>
      </div>

      {/* Metadata row */}
      <div className="flex items-center gap-1.5 mt-1.5 text-[11px] text-[var(--fg-muted)] tracking-wide">
        <Clock size={11} className="shrink-0" />
        <span>{formatTimeAgo(item.ts)}</span>

        {modelLabel && (
          <span className="px-1.5 py-0.5 rounded-full bg-[var(--chip-bg)] text-[var(--fg-secondary)] text-[9px] font-medium uppercase">
            {modelLabel}
          </span>
        )}

        {costLabel && <span>{costLabel}</span>}
        <span>{tokenLabel}</span>
      </div>

      {/* Hover action bar */}
      {hovered && (
        <div className="flex items-center gap-3 mt-2 pt-1.5 border-t border-[var(--border)]">
          <button
            className="flex items-center gap-1 text-[11px] text-[var(--accent)] hover:underline"
            onClick={(e) => { e.stopPropagation(); onResume(item.id); }}
          >
            <MessageSquare size={11} />
            Resume
          </button>
          <button
            className="flex items-center gap-1 text-[11px] text-[var(--error)] hover:underline"
            onClick={(e) => { e.stopPropagation(); onDelete(item.id); }}
          >
            <Trash2 size={11} />
            Delete
          </button>
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
cd .worktrees/session-persistence
git add agent/webview/src/components/history/SessionCard.tsx
git commit -m "feat(webview): add SessionCard component for history view"
```

---

## Task 5: Create `HistoryView` React component

**Files:**
- Create: `agent/webview/src/components/history/HistoryView.tsx`

- [ ] **Step 1: Create the component**

```tsx
import { Search, Plus, MessageSquareDashed, X } from 'lucide-react';
import { useMemo } from 'react';
import { useChatStore } from '../../stores/chatStore';
import { SessionCard } from './SessionCard';
import { kotlinBridge } from '../../bridge/jcef-bridge';

export function HistoryView() {
  const historyItems = useChatStore((s) => s.historyItems);
  const historySearch = useChatStore((s) => s.historySearch);
  const setHistorySearch = useChatStore((s) => s.setHistorySearch);

  const filteredItems = useMemo(() => {
    if (!historySearch.trim()) return historyItems;
    const query = historySearch.toLowerCase();
    return historyItems.filter((item) =>
      item.task.toLowerCase().includes(query)
    );
  }, [historyItems, historySearch]);

  const handleResume = (id: string) => {
    kotlinBridge.showSession(id);
  };

  const handleDelete = (id: string) => {
    kotlinBridge.deleteSession(id);
  };

  const handleToggleFavorite = (id: string) => {
    kotlinBridge.toggleFavorite(id);
  };

  const handleNewChat = () => {
    kotlinBridge.startNewSession();
  };

  const isEmpty = historyItems.length === 0;

  return (
    <div className="flex flex-col h-full bg-[var(--bg)]">
      {/* Header */}
      <div className="flex items-center justify-between px-3 py-2 shrink-0">
        <h2 className="text-sm font-semibold text-[var(--fg)]">History</h2>
        <button
          onClick={handleNewChat}
          className="flex items-center gap-1 px-2.5 py-1 rounded-md text-xs font-medium
            bg-[var(--accent)] text-white hover:opacity-90 transition-opacity"
        >
          <Plus size={12} />
          New Chat
        </button>
      </div>

      {/* Search bar (only when there are items) */}
      {!isEmpty && (
        <div className="px-3 pb-2 shrink-0">
          <div className="relative">
            <Search size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[var(--fg-muted)]" />
            <input
              type="text"
              placeholder="Search sessions..."
              value={historySearch}
              onChange={(e) => setHistorySearch(e.target.value)}
              className="w-full h-8 pl-8 pr-8 rounded-md text-xs
                bg-[var(--input-bg)] text-[var(--fg)] border border-[var(--input-border)]
                placeholder:text-[var(--fg-muted)]
                focus:outline-none focus:border-[var(--accent)] focus:ring-1 focus:ring-[var(--accent)]/20
                transition-colors"
            />
            {historySearch && (
              <button
                onClick={() => setHistorySearch('')}
                className="absolute right-2.5 top-1/2 -translate-y-1/2 text-[var(--fg-muted)] hover:text-[var(--fg)]"
              >
                <X size={14} />
              </button>
            )}
          </div>
        </div>
      )}

      {/* Content area */}
      <div className="flex-1 overflow-y-auto px-3 pb-3">
        {isEmpty ? (
          /* Empty state */
          <div className="flex flex-col items-center justify-center h-full text-center">
            <MessageSquareDashed size={48} className="text-[var(--fg-muted)] mb-3" />
            <p className="text-base font-semibold text-[var(--fg-secondary)] mb-1">No sessions yet</p>
            <p className="text-xs text-[var(--fg-muted)] max-w-[240px] mb-4">
              Start a conversation with the AI agent to see your session history here.
            </p>
            <button
              onClick={handleNewChat}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium
                bg-[var(--accent)] text-white hover:opacity-90 transition-opacity"
            >
              <Plus size={12} />
              Start New Chat
            </button>
          </div>
        ) : filteredItems.length === 0 ? (
          /* No search results */
          <div className="flex flex-col items-center justify-center h-full text-center">
            <Search size={32} className="text-[var(--fg-muted)] mb-3" />
            <p className="text-sm text-[var(--fg-secondary)]">No matching sessions</p>
            <p className="text-xs text-[var(--fg-muted)] mt-1">
              Try a different search term
            </p>
          </div>
        ) : (
          /* Session card list */
          <div className="flex flex-col gap-1.5">
            {filteredItems.map((item) => (
              <SessionCard
                key={item.id}
                item={item}
                onResume={handleResume}
                onDelete={handleDelete}
                onToggleFavorite={handleToggleFavorite}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
cd .worktrees/session-persistence
git add agent/webview/src/components/history/HistoryView.tsx
git commit -m "feat(webview): add HistoryView component with search and empty state"
```

---

## Task 6: Update `App.tsx` to toggle between History and Chat views

**Files:**
- Modify: `agent/webview/src/App.tsx`

- [ ] **Step 1: Add HistoryView import**

```typescript
import { HistoryView } from './components/history/HistoryView';
```

- [ ] **Step 2: Read viewMode from store**

Inside the `App` component, add:

```typescript
const viewMode = useChatStore((s) => s.viewMode);
```

- [ ] **Step 3: Conditionally render HistoryView vs Chat layout**

Wrap the existing chat layout (everything between the outer flex container and `ErrorBoundary`) in a conditional:

```tsx
{viewMode === 'history' ? (
  <HistoryView />
) : (
  <>
    <TopBar />
    {skillBanner && <SkillBanner ... />}
    <ChatView />
    <DebugPanel ... />
    <EditStatsBar ... />
    <ErrorBoundary ...>
      <InputBar ... />
    </ErrorBoundary>
  </>
)}
```

The outer `div` with `flex flex-col h-screen bg-[var(--bg)]` should remain — both views share it.

- [ ] **Step 4: Commit**

```bash
cd .worktrees/session-persistence
git add agent/webview/src/App.tsx
git commit -m "feat(webview): toggle between HistoryView and ChatView based on viewMode"
```

---

## Task 7: Add bridge functions for history

**Files:**
- Modify: `agent/webview/src/bridge/jcef-bridge.ts`

- [ ] **Step 1: Add Kotlin→JS bridge functions**

In the `bridgeFunctions` object, add:

```typescript
loadSessionHistory(historyItemsJson: string) {
  try {
    const items: HistoryItem[] = JSON.parse(historyItemsJson);
    stores?.getChatStore().setHistoryItems(items);
    stores?.getChatStore().setViewMode('history');
  } catch (e) {
    console.error('[bridge] loadSessionHistory error:', e);
  }
},

showHistoryView() {
  stores?.getChatStore().setViewMode('history');
},

showChatView() {
  stores?.getChatStore().setViewMode('chat');
},
```

Add the import at the top:
```typescript
import type { HistoryItem } from './types';
```

- [ ] **Step 2: Add JS→Kotlin bridge functions**

In the `kotlinBridge` object, add:

```typescript
showSession(sessionId: string) {
  callKotlin('_showSession', sessionId);
},

deleteSession(sessionId: string) {
  callKotlin('_deleteSession', sessionId);
},

toggleFavorite(sessionId: string) {
  callKotlin('_toggleFavorite', sessionId);
},

startNewSession() {
  callKotlin('_startNewSession');
},
```

- [ ] **Step 3: Register global aliases for Kotlin→JS functions**

In the early registration block (where `_loadSessionState` is registered), add:

```typescript
(window as any)._loadSessionHistory = bridgeFunctions.loadSessionHistory;
(window as any)._showHistoryView = bridgeFunctions.showHistoryView;
(window as any)._showChatView = bridgeFunctions.showChatView;
```

- [ ] **Step 4: Commit**

```bash
cd .worktrees/session-persistence
git add agent/webview/src/bridge/jcef-bridge.ts
git commit -m "feat(webview): add history bridge functions (K→JS and JS→K)"
```

---

## Task 8: Wire Kotlin-side bridge — `AgentCefPanel`

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt`

- [ ] **Step 1: Add Kotlin→JS methods**

Add alongside existing `loadSessionState`:

```kotlin
fun loadSessionHistory(historyItemsJson: String) {
    callJs("_loadSessionHistory(${JsEscape.toJsString(historyItemsJson)})")
}

fun showHistoryView() {
    callJs("_showHistoryView()")
}

fun showChatView() {
    callJs("_showChatView()")
}
```

- [ ] **Step 2: Add JS→Kotlin JBCefJSQuery bridges**

Add 4 new query fields (following existing pattern with `registerQuery`):

```kotlin
private var showSessionQuery: JBCefJSQuery? = null
private var deleteSessionQuery: JBCefJSQuery? = null
private var toggleFavoriteQuery: JBCefJSQuery? = null
private var startNewSessionQuery: JBCefJSQuery? = null
```

In the query registration section (where other queries are created), add:

```kotlin
showSessionQuery = registerQuery(client) { sessionId ->
    onShowSession?.invoke(sessionId)
    JBCefJSQuery.Response("")
}

deleteSessionQuery = registerQuery(client) { sessionId ->
    onDeleteSession?.invoke(sessionId)
    JBCefJSQuery.Response("")
}

toggleFavoriteQuery = registerQuery(client) { sessionId ->
    onToggleFavorite?.invoke(sessionId)
    JBCefJSQuery.Response("")
}

startNewSessionQuery = registerQuery(client) { _ ->
    onStartNewSession?.invoke()
    JBCefJSQuery.Response("")
}
```

- [ ] **Step 3: Add callback properties**

```kotlin
var onShowSession: ((String) -> Unit)? = null
var onDeleteSession: ((String) -> Unit)? = null
var onToggleFavorite: ((String) -> Unit)? = null
var onStartNewSession: (() -> Unit)? = null
```

- [ ] **Step 4: Inject JS globals in bridge injection section**

In the `onLoadingStateChange` bridge injection block, add:

```kotlin
showSessionQuery?.let { q ->
    val js = q.inject("sessionId")
    js("window._showSession = function(sessionId) { $js }")
}
deleteSessionQuery?.let { q ->
    val js = q.inject("sessionId")
    js("window._deleteSession = function(sessionId) { $js }")
}
toggleFavoriteQuery?.let { q ->
    val js = q.inject("sessionId")
    js("window._toggleFavorite = function(sessionId) { $js }")
}
startNewSessionQuery?.let { q ->
    val js = q.inject("''")
    js("window._startNewSession = function() { $js }")
}
```

- [ ] **Step 5: Commit**

```bash
cd .worktrees/session-persistence
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt
git commit -m "feat(agent): wire history bridge functions in AgentCefPanel (K→JS + JS→K)"
```

---

## Task 9: Wire `AgentDashboardPanel` delegation

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt`

- [ ] **Step 1: Add delegation methods for Kotlin→JS**

Following the existing `loadSessionState` pattern:

```kotlin
fun loadSessionHistory(historyItemsJson: String) {
    runOnEdt { cefPanel?.loadSessionHistory(historyItemsJson) }
    broadcast(replay = false) { it.loadSessionHistory(historyItemsJson) }
}

fun showHistoryView() {
    runOnEdt { cefPanel?.showHistoryView() }
    broadcast(replay = false) { it.showHistoryView() }
}

fun showChatView() {
    runOnEdt { cefPanel?.showChatView() }
    broadcast(replay = false) { it.showChatView() }
}
```

- [ ] **Step 2: Commit**

```bash
cd .worktrees/session-persistence
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt
git commit -m "feat(agent): delegate history bridge methods in AgentDashboardPanel"
```

---

## Task 10: Wire `AgentController` history actions

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

- [ ] **Step 1: Add `showHistory()` method**

```kotlin
fun showHistory() {
    val baseDir = service.getSessionBaseDir() ?: return
    val items = MessageStateHandler.loadGlobalIndex(baseDir)
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    val historyJson = json.encodeToString(items)
    dashboard.loadSessionHistory(historyJson)
}
```

Add import: `import com.workflow.orchestrator.agent.session.MessageStateHandler`
Add import: `import com.workflow.orchestrator.agent.session.HistoryItem`

- [ ] **Step 2: Add `handleDeleteSession()` method**

```kotlin
fun handleDeleteSession(sessionId: String) {
    val baseDir = service.getSessionBaseDir() ?: return
    scope.launch(Dispatchers.IO) {
        MessageStateHandler.deleteSession(baseDir, sessionId)
        withContext(Dispatchers.EDT) {
            showHistory() // refresh the list
        }
    }
}
```

- [ ] **Step 3: Add `handleToggleFavorite()` method**

```kotlin
fun handleToggleFavorite(sessionId: String) {
    val baseDir = service.getSessionBaseDir() ?: return
    scope.launch(Dispatchers.IO) {
        MessageStateHandler.toggleFavorite(baseDir, sessionId)
        withContext(Dispatchers.EDT) {
            showHistory() // refresh the list
        }
    }
}
```

- [ ] **Step 4: Add `handleStartNewSession()` method**

```kotlin
fun handleStartNewSession() {
    newChat() // existing method that resets state
    dashboard.showChatView()
}
```

- [ ] **Step 5: Wire callbacks from AgentCefPanel**

In the section where `AgentCefPanel` callbacks are wired (in `init` or dashboard setup), add:

```kotlin
cefPanel.onShowSession = { sessionId -> resumeSession(sessionId) }
cefPanel.onDeleteSession = { sessionId -> handleDeleteSession(sessionId) }
cefPanel.onToggleFavorite = { sessionId -> handleToggleFavorite(sessionId) }
cefPanel.onStartNewSession = { handleStartNewSession() }
```

- [ ] **Step 6: Update `newChat()` to show history after reset**

In the existing `newChat()` method, after the reset logic, add:

```kotlin
showHistory()
```

This ensures clicking "+" in the chat always saves current state and shows the history list.

- [ ] **Step 7: Commit**

```bash
cd .worktrees/session-persistence
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "feat(agent): wire history actions in AgentController (show, delete, favorite, new)"
```

---

## Task 11: Remove old Swing History tab

**Files:**
- Delete: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/HistoryPanel.kt`
- Delete: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/HistoryTabProvider.kt`
- Modify: `agent/src/main/resources/META-INF/plugin.xml` (if HistoryTabProvider is registered there)

- [ ] **Step 1: Check for plugin.xml registration**

Search for `HistoryTabProvider` in `plugin.xml` or any extension point registration. If found, remove the registration line.

- [ ] **Step 2: Delete files**

```bash
cd .worktrees/session-persistence
rm agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/HistoryPanel.kt
rm agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/HistoryTabProvider.kt
```

- [ ] **Step 3: Search for remaining references**

Grep for `HistoryPanel` and `HistoryTabProvider` across the codebase. Remove any imports or references.

- [ ] **Step 4: Verify build**

Run: `cd .worktrees/session-persistence && ./gradlew :agent:compileKotlin -x verifyPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
cd .worktrees/session-persistence
git add -A
git commit -m "refactor(agent): remove Swing HistoryPanel and HistoryTabProvider (fixes 7th tab UX violation)"
```

---

## Task 12: Run full test suite and verify

**Files:** None (verification only)

- [ ] **Step 1: Run agent module tests**

Run: `cd .worktrees/session-persistence && ./gradlew :agent:test -x verifyPlugin`
Expected: All tests PASS (existing 51+ tests + 8 new delete/favorite tests)

- [ ] **Step 2: Build the webview**

Run: `cd .worktrees/session-persistence/agent/webview && npm run build`
Expected: Build succeeds with no TypeScript errors.

- [ ] **Step 3: Verify plugin builds**

Run: `cd .worktrees/session-persistence && ./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL, ZIP generated.

- [ ] **Step 4: Commit any fixes**

If any step required fixes, commit them.

---

## Task 13: Update documentation

**Files:**
- Modify: `.worktrees/session-persistence/CLAUDE.md` — update UX Constraints to note "History integrated into Agent chat webview"
- Modify: `.worktrees/session-persistence/agent/CLAUDE.md` — document new bridge functions and HistoryView component

- [ ] **Step 1: Update root CLAUDE.md UX Constraints**

Change:
```
- ONE tool window "Workflow" (bottom-docked), 6 tabs: Sprint, PR, Build, Quality, Automation, Handover
```

This remains correct (History tab removed, back to 6).

Add under Agent Storage or a new section:
```
**Session History UI:** Integrated into Agent chat webview as `HistoryView` React component. Webview toggles
between `viewMode: 'history'` (session cards, search, empty state) and `viewMode: 'chat'` (active session).
Bridge functions: `_loadSessionHistory` (K→JS), `_showSession`/`_deleteSession`/`_toggleFavorite`/`_startNewSession` (JS→K).
```

- [ ] **Step 2: Update agent/CLAUDE.md**

Document the new React components and bridge protocol additions.

- [ ] **Step 3: Commit**

```bash
cd .worktrees/session-persistence
git add CLAUDE.md agent/CLAUDE.md
git commit -m "docs: document integrated history view architecture"
```
