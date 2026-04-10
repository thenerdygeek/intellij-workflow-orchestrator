# Smooth Streaming Text Animation

**Date:** 2026-04-11
**Branch:** feature/streaming-xml-port
**Status:** Design approved

## Problem

The agent chat UI renders streaming LLM text abruptly. Every SSE token triggers the full pipeline: one JCEF bridge IPC call, one Zustand state update, one React re-render, and one full markdown AST rebuild via react-markdown. For a 5000-token response, this means 5000 bridge calls, 5000 re-renders, and O(n^2) cumulative markdown parsing (~3.5-4.5s for 10KB). The result is visually jerky and computationally wasteful.

## Goals

1. Smooth, constant-cadence text appearance with per-character blur/fade materialization animation
2. Progressive markdown rendering during streaming (not deferred to stream end)
3. Significant reduction in bridge calls, state updates, and markdown parse cost
4. Settings toggle for animations (governs all current and future chat animations)
5. Graceful degradation when animations are disabled (still benefits from batching + streaming markdown)

## Non-Goals

- Automatic FPS-based animation degradation (rejected in favor of explicit settings toggle)
- Animating tool call output or code block content during streaming
- Server-side changes to SSE chunk size or delivery rate

## Architecture Overview

```
SSE chunk
  -> AgentLoop (delta extraction, partial XML strip)
  -> StreamBatcher (16ms coalescing on EDT)              [NEW - Kotlin]
  -> JCEF bridge (batched callJs)
  -> chatStore.appendToken() (unchanged API)
  -> PresentationBuffer (adaptive-rate character queue)  [NEW - React]
  -> StreamingMessage component:                         [NEW - React]
       Zone 1: Streaming markdown (completed blocks)
       Zone 2: Settled plain text (current in-progress block)
       Zone 3: <m.span> per char (materialization animation, ~15 nodes)
  -> endStream(): flush buffer -> full MarkdownRenderer (existing)
```

### Performance Budget

| Metric | Current | Target |
|---|---|---|
| Bridge calls per response | ~5000 | ~300 (one per frame) |
| React re-renders per response | ~5000 | ~300 (one per frame) |
| Markdown parse cost (10KB) | ~3.5-4.5s (O(n^2)) | ~88-600ms (O(n)) |
| Concurrent animated DOM nodes | 0 | ~15 (constant) |
| Max frame time during streaming | Spikes to 20ms+ | <10ms |

## Section 1: Kotlin-Side Stream Batching

### Component: StreamBatcher

**Location:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/StreamBatcher.kt`

**Purpose:** Coalesce rapid-fire `onStreamChunk()` calls into a single bridge dispatch per frame.

**Behavior:**
- Accumulates chunks in a `StringBuilder`
- Flushes to the bridge every 16ms via a coroutine-based timer on `Dispatchers.EDT`
- Immediate flush on `endStream()` (drain remaining buffer)
- Immediate flush on cancel (clear buffer, no dispatch)

**Integration point:** `AgentController.onStreamChunk()` (currently at line 833) changes from:

```kotlin
// Before
private fun onStreamChunk(chunk: String) {
    lastStreamSnippet = (lastStreamSnippet + chunk).takeLast(150)
    invokeLater {
        dashboard.appendStreamToken(chunk)
    }
}

// After
private fun onStreamChunk(chunk: String) {
    lastStreamSnippet = (lastStreamSnippet + chunk).takeLast(150)
    streamBatcher.append(chunk)  // batched, flushes every 16ms
}
```

`StreamBatcher` is tied to the `AgentController` Disposable lifecycle.

**No changes to:** `AgentLoop`, `LlmBrain`, `AgentCefPanel.appendStreamToken()`, or the JCEF bridge protocol. The bridge API stays the same — it just gets called less frequently with larger payloads.

## Section 2: Presentation Buffer (Adaptive-Rate Character Queue)

### Component: PresentationBuffer

**Location:** `agent/webview/src/hooks/usePresentationBuffer.ts`

**Purpose:** Accept batched text chunks from the store and release characters at a smooth, adaptive rate.

**Replaces:** `agent/webview/src/hooks/useStreaming.ts` (current RAF loop that just syncs store text to display text without any rate control).

### Adaptive Rate Algorithm

The buffer maintains a target queue depth of ~50-100 characters. Release rate adjusts to keep the queue near this target:

| Queue Depth | Behavior | Chars/Frame (~16ms) |
|---|---|---|
| 0 (empty) | Pause, wait for tokens | 0 |
| 1-50 | Base rate, smooth unhurried | 2-3 (~150 chars/sec) |
| 50-200 | Speed up, prevent bloat | 4-8 (~300-500 chars/sec) |
| 200+ | Fast drain, catch up | 12-20 (~750-1200 chars/sec) |
| Stream ended | Accelerated flush | 15-25 |

### Interface

```typescript
interface PresentationBufferState {
  // Internal queue of unreleased characters
  queue: string[];

  // Text released so far (consumed by renderer)
  releasedText: string;

  // Whether the buffer has pending characters
  isBuffering: boolean;

  // Actions
  enqueue(text: string): void;    // Called by chatStore.appendToken
  flush(): void;                   // Called on endStream
  clear(): void;                   // Called on cancel / new message
  tick(): void;                    // Called by RAF loop each frame
}
```

### Internal Mechanics

- Implemented as a Zustand store (separate from chatStore) for isolated re-renders
- RAF loop created on first `enqueue()`, destroyed on `clear()`
- Each `tick()`: calculate chars to release based on queue depth, append to `releasedText`, notify subscribers
- `flush()`: switch to accelerated drain rate, release all remaining within ~200-300ms
- `clear()`: stop RAF, empty queue, reset `releasedText` to empty string

### Integration with chatStore

`chatStore.appendToken()` is unchanged in its external API. Internally, it still accumulates `activeStream.text`. The PresentationBuffer subscribes to `activeStream.text` changes and enqueues the delta (new characters since last observation).

```
chatStore.activeStream.text: "Hello world"     (raw, all received text)
PresentationBuffer.releasedText: "Hello wo"     (what the UI shows, lagging behind)
PresentationBuffer.queue: ['r', 'l', 'd']       (pending release)
```

### When Animations Are Disabled

The PresentationBuffer still runs (smooths jittery SSE delivery) but with a much higher base release rate — effectively dumping chunks per frame rather than per character. No per-character splitting occurs.

## Section 3: BlurTextStream Component (Per-Letter Animation with Graduation)

### Component: BlurTextStream

**Location:** `agent/webview/src/components/chat/BlurTextStream.tsx`

**Purpose:** Render streaming text with per-character materialization animation using a two-zone model.

### Two-Zone Model

```
+--------------------------------------------------+
| "The quick brown fox jumps over the la"           |  <- Settled zone: plain <span>
|                                        "zy dog"   |  <- Trailing zone: <m.span> per char
+--------------------------------------------------+
```

- **Settled zone:** Characters whose animation completed. Single `<span>` with accumulated text. Zero overhead.
- **Trailing zone:** Last ~15 characters, each in its own `<m.span>`. Animating from blurred/invisible to sharp/visible.

### Animation Parameters

```typescript
const MATERIALIZE_ANIMATION = {
  initial: { opacity: 0, y: 3, filter: 'blur(2px)' },
  animate: { opacity: 1, y: 0, filter: 'blur(0px)' },
  transition: { duration: 0.12, ease: [0.25, 0.1, 0.25, 1] },
};
```

**Design rationale (from research):**
- `opacity` + `transform(y)` are 100% GPU-composited — can handle hundreds of concurrent animations
- `filter: blur(2px)` is a tiny radius, minimal GPU cost. Chromium has a known bug animating blur at higher radii (framer/motion#985), but 2px over 120ms is imperceptible even if it glitches
- 120ms duration is fast enough to keep up with the buffer's base release rate (~150 chars/sec)
- The visual effect: text fades in from slightly below with a soft focus, then snaps sharp

### Graduation

When `onAnimationComplete` fires on a `<m.span>`:
1. The character is removed from the trailing zone array
2. The character is appended to the settled zone string
3. Net effect: one `<m.span>` removed, settled `<span>` text grows by one char

The animated DOM node count stays at ~15 regardless of response length (100 chars or 50,000 chars).

### LazyMotion Optimization

```tsx
import { LazyMotion, domAnimation, m } from 'motion/react';

// Wrap once at the StreamingMessage level
<LazyMotion features={domAnimation}>
  <BlurTextStream ... />
</LazyMotion>
```

Reduces initial animation bundle from ~34KB to ~4.6KB with features loaded async.

### Special Characters

| Character | Handling |
|---|---|
| Space | `\u00A0` during animation (prevents collapse), `' '` after graduation |
| Newline `\n` | Graduates immediately (no animation), inserts `<br/>` |
| Tab | Graduates immediately, renders as spaces |

### When Animations Are Disabled

`BlurTextStream` checks `settingsStore.chatAnimationsEnabled`. When false, it renders text as a plain `<span>` updated each frame from the PresentationBuffer — no `<m.span>`, no graduation logic. Effectively the same as the current `useStreaming` behavior but with batching benefits.

## Section 4: Streaming Markdown (Progressive Block Rendering)

### Library Evaluation

Three production-ready streaming markdown parsers were evaluated:

| Library | Approach | Perf (10KB) | Perf (20KB) | Handles Unterminated | React Component |
|---|---|---|---|---|---|
| **Streamdown** (Vercel) | Block-level incremental + remend | ~400-600ms | ~1.4-5.7s | Yes | `<Streamdown>` |
| **Incremark** | True O(n) incremental, locks stable blocks | ~88ms | ~88ms | Yes | `@incremark/react` |
| **Semidown** | Semi-incremental, LLM-specific | ~200-400ms | ~400-800ms | Yes | Framework-agnostic |

**Decision:** Evaluate Incremark and Streamdown during implementation. Pick based on bundle size compatibility with our existing marked.js setup and actual benchmarks in JCEF. Incremark is the performance leader; Streamdown has better ecosystem support.

**Fallback:** If neither integrates cleanly, Semidown or a minimal custom block scanner (~30 lines) can serve as Zone 1.

### Three-Zone Rendering Model

```
+---------------------------------------------------------+
| ## Analysis                                              |  Zone 1: Streaming markdown library
| The function has **two** issues:                         |  (completed blocks, full formatting)
| 1. The loop doesn't handle `null`                        |
+---------------------------------------------------------+
| 2. The return type is wr                                 |  Zone 2: Settled plain text
+---------------------------------------------------------+
|                          ong beca                        |  Zone 3: Blur-animated trailing chars
+---------------------------------------------------------+
```

**Zone 1 — Markdown rendered:** Completed structural blocks (paragraphs, headings, code fences, lists) parsed and rendered by the streaming markdown library. Immutable once rendered — never re-parsed.

**Zone 2 — Settled plain:** Current in-progress block. Characters that graduated from blur animation but whose block isn't structurally complete yet. Rendered as plain text.

**Zone 3 — Animated trailing:** Last ~15 characters wrapped in `<m.span>` with materialization animation.

### Block Boundary Detection

The streaming markdown library handles this. A block is "complete" when:
- A blank line follows a paragraph
- A closing code fence (```) matches an opening one
- A newline follows a heading (`# ...`)
- List/blockquote patterns terminate

Once complete, the block text moves from Zone 2 to Zone 1 for markdown rendering.

### Component Structure

```tsx
const StreamingMessage: React.FC<{ buffer: PresentationBufferState }> = ({ buffer }) => {
  const animationsEnabled = useSettingsStore(s => s.chatAnimationsEnabled);
  const { completedBlocks, currentBlock } = useBlockSplitter(buffer.releasedText);

  return (
    <div className="streaming-message">
      {/* Zone 1: completed blocks with full markdown */}
      <StreamingMarkdown text={completedBlocks} />

      {/* Zone 2 + 3: current block with optional animation */}
      {animationsEnabled ? (
        <BlurTextStream text={currentBlock} />
      ) : (
        <span>{currentBlock}</span>
      )}
    </div>
  );
};
```

### Transition on Stream End

When `endStream()` fires:
1. PresentationBuffer flushes all remaining characters (accelerated drain)
2. All characters graduate from Zone 3 to Zone 2
3. All text moves to Zone 1 (final block completes)
4. The `StreamingMessage` component unmounts
5. The finalized message renders through the existing `AgentMessage -> MarkdownRenderer` pipeline

This transition should be visually seamless — the streaming markdown output in Zone 1 should closely match the final MarkdownRenderer output for the same content.

## Section 5: Animation Settings Toggle

### Setting Definition

**Name:** `chatAnimationsEnabled`
**Type:** Boolean
**Default:** `true`
**Location:** Settings > Tools > Workflow Orchestrator > AI & Advanced

Respects `prefers-reduced-motion` OS media query as default — if the OS requests reduced motion, the setting defaults to `false`. Users can override in either direction.

### What It Controls

| Animation | Enabled | Disabled |
|---|---|---|
| Streaming text blur/fade-in | Per-char `<m.span>` materialization | Plain text append |
| *(Future)* Message entrance | Slide/fade in | Instant appear |
| *(Future)* Tool call transitions | Animated expand/collapse | Instant toggle |
| *(Future)* Plan step updates | Animated status changes | Instant swap |

Single gate for all chat animations. No per-animation sub-toggles.

### Data Flow

```
PluginSettings.chatAnimationsEnabled (Kotlin, IDE settings XML)
  -> AgentCefPanel.syncSettings() -> callJs("updateSettings({...})")
  -> settingsStore.chatAnimationsEnabled (React Zustand)
  -> consumed by BlurTextStream, PresentationBuffer, future components
```

### Kotlin Side

Add to `PluginSettings` (existing settings class in `:core`):

```kotlin
var chatAnimationsEnabled: Boolean = true
```

Add corresponding checkbox to the AI & Advanced settings page.

### React Side

Add to `settingsStore` (existing Zustand store):

```typescript
interface SettingsState {
  // ... existing fields ...
  chatAnimationsEnabled: boolean;
}
```

Bridge function `updateSettings()` already exists and syncs settings from Kotlin to React.

### When Disabled

- PresentationBuffer still runs (smooths SSE jitter) but with higher release rate (chunks, not characters)
- Streaming markdown still runs (progressive parsing is a performance win regardless)
- No `<m.span>` elements created
- No graduation logic
- Net effect: current behavior + batching + streaming markdown improvements

## File Changes Summary

### New Files

| File | Module | Purpose |
|---|---|---|
| `agent/ui/StreamBatcher.kt` | agent (Kotlin) | 16ms bridge call coalescing |
| `webview/src/hooks/usePresentationBuffer.ts` | webview (React) | Adaptive-rate character queue |
| `webview/src/components/chat/BlurTextStream.tsx` | webview (React) | Per-char animation with graduation |
| `webview/src/components/chat/StreamingMessage.tsx` | webview (React) | Three-zone orchestrator component |
| `webview/src/hooks/useBlockSplitter.ts` | webview (React) | Splits released text into completed blocks + current block |

### Modified Files

| File | Change |
|---|---|
| `agent/ui/AgentController.kt` | Use `StreamBatcher` instead of direct `invokeLater` dispatch |
| `webview/src/stores/chatStore.ts` | No API change; PresentationBuffer subscribes to `activeStream.text` |
| `webview/src/stores/settingsStore.ts` | Add `chatAnimationsEnabled` field |
| `webview/src/components/chat/ChatView.tsx` | Replace `<AgentMessage>` streaming placeholder with `<StreamingMessage>` |
| `webview/src/hooks/useStreaming.ts` | Replaced by `usePresentationBuffer.ts` (delete or thin wrapper) |
| `core/.../PluginSettings.kt` | Add `chatAnimationsEnabled: Boolean` |
| AI & Advanced settings UI | Add checkbox for chat animations |
| `webview/package.json` | Add streaming markdown library (Incremark or Streamdown) |

### No Changes To

- `AgentLoop.kt` — streaming callback interface unchanged
- `LlmBrain.kt` — SSE handling unchanged
- `AgentCefPanel.kt` — `appendStreamToken()` API unchanged
- `jcef-bridge.ts` — `appendToken()` function unchanged
- `MarkdownRenderer.tsx` — still used for finalized (non-streaming) messages

## Dependencies

| Package | Purpose | Size (gzipped) | Notes |
|---|---|---|---|
| `motion` (framer-motion) | Per-character animation | ~32KB | Already being added on main for chart rendering |
| Streaming markdown lib | Progressive block rendering | TBD | Evaluate Incremark vs Streamdown during implementation |

## Research References

- [Chrome DevRel: Best practices to render streamed LLM responses](https://developer.chrome.com/docs/ai/render-llm-responses)
- [Vercel Streamdown](https://github.com/vercel/streamdown) — streaming markdown renderer
- [Incremark](https://www.incremark.com/) — O(n) incremental markdown parser
- [FlowToken](https://github.com/Ephibbs/flowtoken) — LLM text streaming animation library
- [ReactBits BlurText](https://reactbits.dev/text-animations/blur-text) — inspiration for materialization animation (MIT + Commons Clause)
- [Framer Motion blur bug in Chromium](https://github.com/framer/motion/issues/985) — reason for using blur(2px) not blur(8px)
- [Semidown](https://github.com/chuanqisun/semidown) — semi-incremental markdown parser
- [From O(n^2) to O(n): Building a Streaming Markdown Renderer](https://dev.to/kingshuaishuai/from-on2-to-on-building-a-streaming-markdown-renderer-for-the-ai-era-3k0f)
- [Streaming Backends & React: Controlling Re-render Chaos](https://www.sitepoint.com/streaming-backends-react-controlling-re-render-chaos/)
- [Vercel AI SDK Memoization Cookbook](https://ai-sdk.dev/cookbook/next/markdown-chatbot-with-memoization)
- [Smooth Text Streaming in AI SDK v5](https://upstash.com/blog/smooth-streaming)
