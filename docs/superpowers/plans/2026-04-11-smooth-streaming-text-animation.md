# Smooth Streaming Text Animation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform the agent chat's abrupt token-by-token streaming into a smooth, animated text flow with per-character blur/fade materialization, progressive markdown rendering, and a settings toggle for animations.

**Architecture:** Five-layer pipeline — Kotlin StreamBatcher (16ms coalescing) → JCEF bridge (batched) → PresentationBuffer (adaptive-rate character queue) → StreamingMessage (three-zone renderer with blur animation via motion/react) → streaming markdown (Incremark or Streamdown). Settings toggle gates all animation.

**Tech Stack:** Kotlin coroutines (EDT timer), React 18, Zustand, motion/react (already bundled), streaming markdown library (evaluate Incremark vs Streamdown), Tailwind CSS

**Spec:** `docs/superpowers/specs/2026-04-11-smooth-streaming-text-animation-design.md`

---

## File Map

### New Files

| File | Responsibility |
|---|---|
| `agent/src/main/kotlin/.../agent/ui/StreamBatcher.kt` | 16ms EDT coalescing of stream chunks before JCEF bridge |
| `agent/webview/src/hooks/usePresentationBuffer.ts` | Adaptive-rate character queue (Zustand store + RAF loop) |
| `agent/webview/src/components/chat/BlurTextStream.tsx` | Per-character motion.span animation with graduation |
| `agent/webview/src/components/chat/StreamingMessage.tsx` | Three-zone orchestrator (markdown + settled + animated) |
| `agent/webview/src/hooks/useBlockSplitter.ts` | Splits released text into completed blocks + current block |

### Modified Files

| File | Change |
|---|---|
| `agent/.../agent/settings/AgentSettings.kt` | Add `chatAnimationsEnabled` field |
| `agent/.../agent/settings/AgentAdvancedConfigurable.kt` | Add checkbox in "Debugging & Diagnostics" group |
| `agent/.../agent/ui/AgentController.kt:833-839` | Replace direct `invokeLater` dispatch with `StreamBatcher` |
| `agent/.../agent/ui/AgentCefPanel.kt` | Add `setChatAnimationsEnabled()` bridge call |
| `agent/webview/src/bridge/jcef-bridge.ts:68-70` | Add `setChatAnimationsEnabled()` bridge function |
| `agent/webview/src/stores/settingsStore.ts` | Add `chatAnimationsEnabled` field + setter |
| `agent/webview/src/stores/chatStore.ts:334-383` | No API change — PresentationBuffer subscribes externally |
| `agent/webview/src/components/chat/ChatView.tsx:430-437` | Replace streaming `<AgentMessage>` with `<StreamingMessage>` |
| `agent/webview/src/hooks/useStreaming.ts` | Delete (replaced by usePresentationBuffer) |
| `agent/webview/package.json` | Add streaming markdown library |

---

### Task 1: Settings Toggle (Kotlin + Bridge + React)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt:13-55`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentAdvancedConfigurable.kt:27-57`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt:776-796`
- Modify: `agent/webview/src/stores/settingsStore.ts:26-57`
- Modify: `agent/webview/src/bridge/jcef-bridge.ts:257-264`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt` (init section)

- [ ] **Step 1: Add `chatAnimationsEnabled` to AgentSettings.State**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt`, add after `toolExecutionMode` (line 54):

```kotlin
/** Enable blur/fade animations on streaming text in the chat UI. */
var chatAnimationsEnabled by property(true)
```

- [ ] **Step 2: Add checkbox to AgentAdvancedConfigurable**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentAdvancedConfigurable.kt`:

Add mutable copy field after `powershellEnabled` (line 29):

```kotlin
private var chatAnimationsEnabled = agentSettings.state.chatAnimationsEnabled
```

Add checkbox in the "Debugging & Diagnostics" group, after the PowerShell row (after line 56):

```kotlin
row {
    checkBox("Enable chat animations")
        .bindSelected(::chatAnimationsEnabled)
        .comment("Adds smooth blur/fade-in effects to streaming text. Disable for reduced motion.")
}
```

Update `apply()` (after line 122):

```kotlin
agentSettings.state.chatAnimationsEnabled = chatAnimationsEnabled
```

Update `reset()` (after line 128):

```kotlin
chatAnimationsEnabled = agentSettings.state.chatAnimationsEnabled
```

- [ ] **Step 3: Add `setChatAnimationsEnabled()` to AgentCefPanel**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt`, add after `setRalphLoop()` (~line 796):

```kotlin
fun setChatAnimationsEnabled(enabled: Boolean) {
    callJs("setChatAnimationsEnabled(${if (enabled) "true" else "false"})")
}
```

- [ ] **Step 4: Add `chatAnimationsEnabled` to settingsStore**

In `agent/webview/src/stores/settingsStore.ts`, add to `SettingsState` interface (after line 28):

```typescript
chatAnimationsEnabled: boolean;
setChatAnimationsEnabled(enabled: boolean): void;
```

Add to the store creation (after `visualizations` on line 34):

```typescript
chatAnimationsEnabled: !window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? true,

setChatAnimationsEnabled(enabled: boolean) {
    set({ chatAnimationsEnabled: enabled });
},
```

- [ ] **Step 5: Register bridge function**

In `agent/webview/src/bridge/jcef-bridge.ts`, add after `applyVisualizationSettings` (~line 264):

```typescript
setChatAnimationsEnabled(enabled: boolean) {
    stores?.getSettingsStore().setChatAnimationsEnabled(enabled);
},
```

Also add to the `getSettingsStore` accessor by adding `getSettingsStore` to the stores interface if not already exposed — it's already on line 13.

- [ ] **Step 6: Sync setting on panel init**

In `AgentController.kt`, find where the panel is initialized/shown and add a sync call to push the current setting value:

```kotlin
dashboard.setChatAnimationsEnabled(agentSettings.state.chatAnimationsEnabled)
```

This should be called in the same location where `setModelName()`, `setPlanMode()`, etc. are called during initialization.

- [ ] **Step 7: Verify the setting round-trips**

Run: `./gradlew :agent:clean :agent:test --rerun --no-build-cache`
Expected: BUILD SUCCESSFUL (no existing tests break)

Build and launch: `./gradlew runIde`
Open agent tab → Settings > AI & Advanced → verify "Enable chat animations" checkbox appears, toggles, persists across IDE restart.

- [ ] **Step 8: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentAdvancedConfigurable.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt \
       agent/webview/src/stores/settingsStore.ts \
       agent/webview/src/bridge/jcef-bridge.ts
git commit -m "feat(settings): add chatAnimationsEnabled toggle for streaming text animation"
```

---

### Task 2: Kotlin StreamBatcher

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/StreamBatcher.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt:833-839`

- [ ] **Step 1: Create StreamBatcher**

Create `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/StreamBatcher.kt`:

```kotlin
package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Timer

/**
 * Coalesces rapid-fire stream chunks into a single bridge dispatch per frame (~16ms).
 * Reduces JCEF callJs() calls from ~5000 per response to ~300.
 */
class StreamBatcher(
    private val onFlush: (String) -> Unit,
    private val intervalMs: Int = 16
) : Disposable {

    private val buffer = StringBuilder()
    private val lock = Any()
    private val disposed = AtomicBoolean(false)

    private val timer = Timer(intervalMs) {
        flushIfNeeded()
    }.apply {
        isRepeats = true
        isCoalesce = true
    }

    fun start() {
        if (!disposed.get()) timer.start()
    }

    fun stop() {
        timer.stop()
    }

    fun append(chunk: String) {
        synchronized(lock) {
            buffer.append(chunk)
        }
        if (!timer.isRunning && !disposed.get()) {
            timer.start()
        }
    }

    /** Flush remaining buffer immediately (called on endStream / cancel). */
    fun flush() {
        timer.stop()
        flushIfNeeded()
    }

    fun clear() {
        timer.stop()
        synchronized(lock) {
            buffer.setLength(0)
        }
    }

    private fun flushIfNeeded() {
        val text: String
        synchronized(lock) {
            if (buffer.isEmpty()) return
            text = buffer.toString()
            buffer.setLength(0)
        }
        if (!disposed.get()) {
            invokeLater {
                onFlush(text)
            }
        }
    }

    override fun dispose() {
        disposed.set(true)
        timer.stop()
        synchronized(lock) {
            buffer.setLength(0)
        }
    }
}
```

- [ ] **Step 2: Wire StreamBatcher into AgentController**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`:

Add field near other instance fields:

```kotlin
private val streamBatcher = StreamBatcher(
    onFlush = { batched -> dashboard.appendStreamToken(batched) }
).also { Disposer.register(this, it) }
```

Replace `onStreamChunk` (lines 833-839):

```kotlin
private fun onStreamChunk(chunk: String) {
    lastStreamSnippet = (lastStreamSnippet + chunk).takeLast(150)
    streamBatcher.append(chunk)
}
```

Also find where `endStream` is called on the panel and add `streamBatcher.flush()` before it:

```kotlin
// Before calling dashboard.flushStreamBuffer() or dashboard.completeSession()
streamBatcher.flush()
```

And on cancel/new-session, call `streamBatcher.clear()`.

- [ ] **Step 3: Verify tests still pass**

Run: `./gradlew :agent:clean :agent:test --rerun --no-build-cache`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/StreamBatcher.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "feat(streaming): add StreamBatcher for 16ms bridge call coalescing"
```

---

### Task 3: PresentationBuffer (Adaptive-Rate Character Queue)

**Files:**
- Create: `agent/webview/src/hooks/usePresentationBuffer.ts`
- Delete: `agent/webview/src/hooks/useStreaming.ts`

- [ ] **Step 1: Create usePresentationBuffer**

Create `agent/webview/src/hooks/usePresentationBuffer.ts`:

```typescript
import { useEffect, useRef, useCallback, useState } from 'react';
import { useChatStore } from '@/stores/chatStore';
import { useSettingsStore } from '@/stores/settingsStore';

interface PresentationBufferState {
  displayText: string;
  isBuffering: boolean;
}

/**
 * Adaptive-rate character queue that smooths streaming text delivery.
 *
 * Accepts raw accumulated text from chatStore.activeStream and releases
 * characters at a constant adaptive rate based on queue depth.
 *
 * Queue depth → chars per frame (~16ms):
 *   0        → pause (wait for tokens)
 *   1-50     → 2-3 chars (~150 chars/sec)
 *   50-200   → 4-8 chars (~300-500 chars/sec)
 *   200+     → 12-20 chars (~750-1200 chars/sec)
 *   flushing → 15-25 chars (accelerated drain on stream end)
 */
export function usePresentationBuffer(): PresentationBufferState {
  const rawText = useChatStore(s => s.activeStream?.text ?? '');
  const isStreaming = useChatStore(s => s.activeStream?.isStreaming ?? false);
  const animationsEnabled = useSettingsStore(s => s.chatAnimationsEnabled);

  const [displayText, setDisplayText] = useState('');
  const queueRef = useRef<string[]>([]);
  const rafRef = useRef<number | null>(null);
  const lastRawLengthRef = useRef(0);
  const flushingRef = useRef(false);

  // Enqueue new characters when rawText grows
  useEffect(() => {
    const newChars = rawText.slice(lastRawLengthRef.current);
    if (newChars.length > 0) {
      if (animationsEnabled) {
        // Per-character queuing for animated mode
        for (const ch of newChars) {
          queueRef.current.push(ch);
        }
      } else {
        // Chunk queuing for non-animated mode
        queueRef.current.push(newChars);
      }
    }
    lastRawLengthRef.current = rawText.length;
  }, [rawText, animationsEnabled]);

  // Calculate chars to release per frame based on queue depth
  const getCharsPerFrame = useCallback((): number => {
    const depth = queueRef.current.length;
    if (depth === 0) return 0;

    if (!animationsEnabled) {
      // No animation: dump everything available
      return depth;
    }

    if (flushingRef.current) {
      return Math.min(depth, 25);
    }
    if (depth > 200) return Math.min(depth, 20);
    if (depth > 50) return Math.min(depth, Math.max(4, Math.floor(depth / 25)));
    return Math.min(depth, 3);
  }, [animationsEnabled]);

  // RAF tick loop
  useEffect(() => {
    if (!isStreaming && queueRef.current.length === 0) {
      // No stream and no pending queue — show final text directly
      if (rafRef.current) {
        cancelAnimationFrame(rafRef.current);
        rafRef.current = null;
      }
      setDisplayText(rawText);
      lastRawLengthRef.current = rawText.length;
      queueRef.current = [];
      flushingRef.current = false;
      return;
    }

    const tick = () => {
      const n = getCharsPerFrame();
      if (n > 0) {
        const released = queueRef.current.splice(0, n).join('');
        setDisplayText(prev => prev + released);
      }

      // Stop RAF when queue is drained and stream is done
      if (queueRef.current.length === 0 && !isStreaming) {
        rafRef.current = null;
        flushingRef.current = false;
        return;
      }

      rafRef.current = requestAnimationFrame(tick);
    };

    if (!rafRef.current) {
      rafRef.current = requestAnimationFrame(tick);
    }

    return () => {
      if (rafRef.current) {
        cancelAnimationFrame(rafRef.current);
        rafRef.current = null;
      }
    };
  }, [isStreaming, getCharsPerFrame, rawText]);

  // Stream ended: switch to flush mode
  useEffect(() => {
    if (!isStreaming && queueRef.current.length > 0) {
      flushingRef.current = true;
    }
  }, [isStreaming]);

  // Reset on new stream
  useEffect(() => {
    if (!isStreaming && rawText === '') {
      setDisplayText('');
      queueRef.current = [];
      lastRawLengthRef.current = 0;
      flushingRef.current = false;
    }
  }, [isStreaming, rawText]);

  return {
    displayText,
    isBuffering: queueRef.current.length > 0,
  };
}
```

- [ ] **Step 2: Delete useStreaming.ts**

Delete `agent/webview/src/hooks/useStreaming.ts` — it's fully replaced by `usePresentationBuffer`.

Search for any imports of `useStreaming` in the codebase and update them to use `usePresentationBuffer` if found:

```bash
grep -r "useStreaming" agent/webview/src/ --include="*.ts" --include="*.tsx"
```

If any file imports `useStreaming`, replace the import and usage.

- [ ] **Step 3: Verify webview builds**

```bash
cd agent/webview && npm run build
```

Expected: Build succeeds with no errors.

- [ ] **Step 4: Commit**

```bash
git add agent/webview/src/hooks/usePresentationBuffer.ts
git rm agent/webview/src/hooks/useStreaming.ts
git add -u agent/webview/src/  # catch any import updates
git commit -m "feat(streaming): add PresentationBuffer with adaptive-rate character queue

Replaces useStreaming.ts RAF loop with a proper character queue
that releases text at a smooth adaptive rate based on queue depth.
When animations disabled, dumps chunks immediately (current behavior)."
```

---

### Task 4: BlurTextStream Component (Per-Character Animation with Graduation)

**Files:**
- Create: `agent/webview/src/components/chat/BlurTextStream.tsx`

- [ ] **Step 1: Create BlurTextStream component**

Create `agent/webview/src/components/chat/BlurTextStream.tsx`:

```tsx
import React, { useState, useCallback, useMemo, memo } from 'react';
import { LazyMotion, domAnimation, m } from 'motion/react';

/** Animation parameters — opacity + transform primary, blur as garnish. */
const MATERIALIZE_INITIAL = { opacity: 0, y: 3, filter: 'blur(2px)' };
const MATERIALIZE_ANIMATE = { opacity: 1, y: 0, filter: 'blur(0px)' };
const MATERIALIZE_TRANSITION = { duration: 0.12, ease: [0.25, 0.1, 0.25, 1] as const };

/**
 * Single animated character — memoized to prevent re-render when siblings update.
 */
const AnimatedChar = memo<{
  char: string;
  index: number;
  onComplete: (index: number) => void;
}>(({ char, index, onComplete }) => {
  const display = char === ' ' ? '\u00A0' : char;
  return (
    <m.span
      className="inline will-change-[transform,filter,opacity]"
      initial={MATERIALIZE_INITIAL}
      animate={MATERIALIZE_ANIMATE}
      transition={MATERIALIZE_TRANSITION}
      onAnimationComplete={() => onComplete(index)}
    >
      {display}
    </m.span>
  );
});
AnimatedChar.displayName = 'AnimatedChar';

interface BlurTextStreamProps {
  /** Full text released by the PresentationBuffer so far. */
  text: string;
}

/**
 * Two-zone streaming text renderer:
 * - Settled zone: plain <span> with all graduated characters
 * - Trailing zone: last ~N characters as animated <m.span> elements
 *
 * Graduation: when a character's animation completes, it moves from
 * the trailing zone to the settled zone. This keeps animated DOM node
 * count constant (~15) regardless of total text length.
 */
export const BlurTextStream: React.FC<BlurTextStreamProps> = ({ text }) => {
  // How many characters have graduated to the settled zone
  const [settledCount, setSettledCount] = useState(0);

  const handleGraduate = useCallback((index: number) => {
    // Graduate all characters up to and including this index
    setSettledCount(prev => Math.max(prev, index + 1));
  }, []);

  const settledText = text.slice(0, settledCount);
  const trailingChars = text.slice(settledCount);

  // Convert newlines in settled text to <br/> for display
  const settledHtml = useMemo(() => {
    return settledText.split('\n').map((line, i, arr) => (
      <React.Fragment key={i}>
        {line}
        {i < arr.length - 1 && <br />}
      </React.Fragment>
    ));
  }, [settledText]);

  return (
    <LazyMotion features={domAnimation}>
      <span className="whitespace-pre-wrap break-words">
        {/* Zone 1: Settled — plain text, zero overhead */}
        <span>{settledHtml}</span>

        {/* Zone 2: Trailing — per-character animation */}
        {Array.from(trailingChars).map((char, i) => {
          const absoluteIndex = settledCount + i;
          // Newlines graduate immediately
          if (char === '\n') {
            return <br key={absoluteIndex} />;
          }
          return (
            <AnimatedChar
              key={absoluteIndex}
              char={char}
              index={absoluteIndex}
              onComplete={handleGraduate}
            />
          );
        })}
      </span>
    </LazyMotion>
  );
};
```

- [ ] **Step 2: Verify webview builds**

```bash
cd agent/webview && npm run build
```

Expected: Build succeeds. The component isn't wired in yet — just verifying it compiles.

- [ ] **Step 3: Commit**

```bash
git add agent/webview/src/components/chat/BlurTextStream.tsx
git commit -m "feat(streaming): add BlurTextStream with per-character animation and graduation

Two-zone renderer: settled plain text + trailing ~15 animated <m.span>
elements using opacity + transform + blur(2px). Graduation on
onAnimationComplete keeps animated node count constant."
```

---

### Task 5: Install Streaming Markdown Library

**Files:**
- Modify: `agent/webview/package.json`

- [ ] **Step 1: Evaluate and install streaming markdown library**

Try Streamdown first (Vercel-backed, robust):

```bash
cd agent/webview && npm install @assistant-ui/react-streamdown
```

If unavailable or too large, try Incremark:

```bash
cd agent/webview && npm install @incremark/react
```

If neither works cleanly, fall back to Semidown:

```bash
cd agent/webview && npm install semidown
```

Check the installed package compiles with our Vite setup:

```bash
cd agent/webview && npm run build
```

- [ ] **Step 2: Add chunk splitting in Vite config (if needed)**

If the library is large enough to warrant its own chunk, add to `agent/webview/vite.config.ts` in the `manualChunks` function (after the `motion` line):

```typescript
if (id.includes('streamdown') || id.includes('incremark') || id.includes('semidown')) return 'streaming-md'
```

- [ ] **Step 3: Commit**

```bash
git add agent/webview/package.json agent/webview/package-lock.json agent/webview/vite.config.ts
git commit -m "build(webview): add streaming markdown library for progressive block rendering"
```

---

### Task 6: useBlockSplitter Hook

**Files:**
- Create: `agent/webview/src/hooks/useBlockSplitter.ts`

- [ ] **Step 1: Create useBlockSplitter**

This hook splits released text into "completed blocks" (ready for markdown) and "current block" (in-progress, plain text).

Create `agent/webview/src/hooks/useBlockSplitter.ts`:

```typescript
import { useMemo } from 'react';

interface BlockSplit {
  /** Completed blocks — structurally closed, safe for markdown parsing. */
  completedBlocks: string;
  /** Current in-progress block — not yet closed, rendered as plain text. */
  currentBlock: string;
}

/**
 * Splits streaming text into completed markdown blocks and the current
 * in-progress block. A block is "complete" when followed by a blank line,
 * a closing code fence, or another block-level element.
 *
 * This is a lightweight boundary scanner, not a full markdown parser.
 */
export function useBlockSplitter(text: string): BlockSplit {
  return useMemo(() => splitBlocks(text), [text]);
}

/** Find the last safe split point in the text. */
export function splitBlocks(text: string): BlockSplit {
  if (text.length === 0) {
    return { completedBlocks: '', currentBlock: '' };
  }

  // Track code fence state to avoid splitting inside fenced blocks
  let inCodeFence = false;
  let lastSafeSplit = 0;
  const lines = text.split('\n');
  let charIndex = 0;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const trimmed = line.trimStart();

    // Detect code fence boundaries
    if (trimmed.startsWith('```') || trimmed.startsWith('~~~')) {
      inCodeFence = !inCodeFence;
      // If we just closed a code fence, the end of this line is a safe split
      if (!inCodeFence) {
        lastSafeSplit = charIndex + line.length + 1; // +1 for \n
      }
    } else if (!inCodeFence) {
      // Outside code fences: blank lines are safe split points
      if (trimmed === '' && i > 0) {
        lastSafeSplit = charIndex + line.length + 1;
      }
    }

    charIndex += line.length + 1; // +1 for \n
  }

  // If we're inside a code fence, don't split — the whole thing is in-progress
  if (inCodeFence) {
    // Find the opening fence and split before it
    const fenceMatch = text.match(/^([\s\S]*?\n)(```|~~~)/m);
    if (fenceMatch && fenceMatch.index !== undefined) {
      const splitAt = fenceMatch.index + fenceMatch[1].length;
      // Only split if there's completed content before the fence
      if (splitAt > 0 && text.slice(0, splitAt).trim().length > 0) {
        return {
          completedBlocks: text.slice(0, splitAt),
          currentBlock: text.slice(splitAt),
        };
      }
    }
    return { completedBlocks: '', currentBlock: text };
  }

  if (lastSafeSplit === 0) {
    // No safe split found — everything is in-progress
    return { completedBlocks: '', currentBlock: text };
  }

  return {
    completedBlocks: text.slice(0, lastSafeSplit),
    currentBlock: text.slice(lastSafeSplit),
  };
}
```

- [ ] **Step 2: Verify build**

```bash
cd agent/webview && npm run build
```

- [ ] **Step 3: Commit**

```bash
git add agent/webview/src/hooks/useBlockSplitter.ts
git commit -m "feat(streaming): add useBlockSplitter for progressive markdown boundary detection"
```

---

### Task 7: StreamingMessage Component (Three-Zone Orchestrator)

**Files:**
- Create: `agent/webview/src/components/chat/StreamingMessage.tsx`
- Modify: `agent/webview/src/components/chat/ChatView.tsx:362-437`

- [ ] **Step 1: Create StreamingMessage component**

Create `agent/webview/src/components/chat/StreamingMessage.tsx`:

```tsx
import React from 'react';
import { useSettingsStore } from '@/stores/settingsStore';
import { useBlockSplitter } from '@/hooks/useBlockSplitter';
import { usePresentationBuffer } from '@/hooks/usePresentationBuffer';
import { BlurTextStream } from './BlurTextStream';
import { MarkdownRenderer } from '../markdown/MarkdownRenderer';

/**
 * Three-zone streaming message renderer:
 *
 * Zone 1 (Markdown):  Completed blocks rendered with full markdown formatting
 * Zone 2 (Settled):   Current in-progress block — plain text (graduated from blur)
 * Zone 3 (Animated):  Trailing ~15 chars with blur/fade animation
 *
 * When animations are disabled, the entire text is rendered as Zone 1+2
 * without per-character animation.
 */
export const StreamingMessage: React.FC = () => {
  const { displayText } = usePresentationBuffer();
  const animationsEnabled = useSettingsStore(s => s.chatAnimationsEnabled);
  const { completedBlocks, currentBlock } = useBlockSplitter(displayText);

  if (displayText.length === 0) return null;

  return (
    <div className="agent-message streaming-message">
      {/* Zone 1: Completed blocks — full markdown rendering */}
      {completedBlocks.length > 0 && (
        <MarkdownRenderer content={completedBlocks} />
      )}

      {/* Zone 2+3: Current block — animated or plain */}
      {currentBlock.length > 0 && (
        animationsEnabled ? (
          <BlurTextStream text={currentBlock} />
        ) : (
          <span className="whitespace-pre-wrap break-words">{currentBlock}</span>
        )
      )}
    </div>
  );
};
```

- [ ] **Step 2: Wire StreamingMessage into ChatView**

In `agent/webview/src/components/chat/ChatView.tsx`, replace the streaming placeholder block (lines 362-437).

Remove the `streamPlaceholder` computation (lines 362-370):

```typescript
// DELETE these lines:
// const streamPlaceholder: Message | null = activeStream
//   ? { id: '__streaming__', role: 'agent', content: activeStream.text, timestamp: Date.now() }
//   : null;
```

Replace the streaming render block (lines 430-437):

```tsx
{/* BEFORE: */}
{/* {streamPlaceholder && (
  <AgentMessage
    key="__streaming__"
    message={streamPlaceholder}
    isStreaming={activeStream?.isStreaming ?? false}
    streamText={activeStream?.text}
  />
)} */}

{/* AFTER: */}
{activeStream && (
  <StreamingMessage />
)}
```

Add the import at the top of ChatView.tsx:

```typescript
import { StreamingMessage } from './StreamingMessage';
```

- [ ] **Step 3: Remove useStreaming import from ChatView if present**

Search ChatView.tsx for any `useStreaming` import and remove it.

- [ ] **Step 4: Verify webview builds and renders**

```bash
cd agent/webview && npm run build
```

Expected: Build succeeds.

- [ ] **Step 5: Commit**

```bash
git add agent/webview/src/components/chat/StreamingMessage.tsx \
       agent/webview/src/components/chat/ChatView.tsx
git commit -m "feat(streaming): wire StreamingMessage three-zone renderer into ChatView

Replaces direct AgentMessage streaming with three-zone architecture:
progressive markdown (Zone 1) + settled plain text (Zone 2) +
animated trailing chars (Zone 3). Respects chatAnimationsEnabled setting."
```

---

### Task 8: Build Webview Dist + Integration Test

**Files:**
- Modify: `agent/webview/` (build output)

- [ ] **Step 1: Rebuild webview dist**

```bash
cd agent/webview && npm run build
```

- [ ] **Step 2: Run full agent test suite**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run plugin verification**

```bash
./gradlew verifyPlugin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Manual smoke test**

```bash
./gradlew runIde
```

Test in the sandbox IDE:
1. Open agent tab, send a message that produces a long response
2. Verify text appears smoothly with blur/fade animation (not abrupt token dumps)
3. Verify markdown renders progressively (headings, bold, code blocks appear as blocks complete)
4. Verify code fences render correctly after closing
5. Toggle Settings > AI & Advanced > "Enable chat animations" off
6. Send another message — verify text appears without animation but still smooth (batched)
7. Cancel mid-stream — verify buffer clears, no stale text animates

- [ ] **Step 5: Commit built dist**

```bash
git add agent/src/main/resources/webview/dist/
git commit -m "build(webview): rebuild dist with streaming animation pipeline"
```

---

### Task 9: Documentation Update

**Files:**
- Modify: `CLAUDE.md`
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Update root CLAUDE.md**

Add to the UX Constraints section:

```markdown
- Streaming text: 5-layer pipeline (StreamBatcher → bridge → PresentationBuffer → BlurTextStream → streaming markdown). Gated by `chatAnimationsEnabled` setting.
```

- [ ] **Step 2: Update agent/CLAUDE.md**

Add a new section "Streaming Text Pipeline" in the React Webview Architecture area:

```markdown
## Streaming Text Pipeline

Five-layer smoothing pipeline from SSE to rendered text:

1. **StreamBatcher** (Kotlin, `agent/ui/StreamBatcher.kt`): 16ms EDT timer coalesces rapid chunks into single bridge calls (~5000 → ~300 per response)
2. **JCEF Bridge**: `appendToken()` unchanged API, receives larger batched payloads
3. **PresentationBuffer** (`hooks/usePresentationBuffer.ts`): Adaptive-rate character queue. Base ~150 chars/sec, speeds up with queue depth, fast drain on stream end.
4. **StreamingMessage** (`components/chat/StreamingMessage.tsx`): Three-zone renderer:
   - Zone 1: Completed blocks → MarkdownRenderer (progressive, O(n))
   - Zone 2: Current block settled text (graduated from animation)
   - Zone 3: Trailing ~15 chars → BlurTextStream (`<m.span>` per char, opacity+transform+blur(2px))
5. **BlurTextStream** (`components/chat/BlurTextStream.tsx`): Per-character `motion/react` animation with graduation (animated node count constant at ~15)

**Settings:** `AgentSettings.chatAnimationsEnabled` (default: true). Synced to webview via `setChatAnimationsEnabled()` bridge call. When disabled, PresentationBuffer dumps chunks immediately, no `<m.span>` created.
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md agent/CLAUDE.md
git commit -m "docs: add streaming text pipeline architecture to CLAUDE.md"
```
