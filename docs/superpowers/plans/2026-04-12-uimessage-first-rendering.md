# UiMessage-First Rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the lossy `Message` → `UiMessage` conversion layer by making the webview store and render `UiMessage` directly, so live sessions and resumed sessions render identically.

**Architecture:** Replace the intermediate `Message` type with `UiMessage` as the primary store type. Store actions build `UiMessage` objects. ChatView dispatches on `say`/`ask` enums (Cline's `ChatRow` pattern). The conversion function and all its bugs are deleted.

**Tech Stack:** TypeScript, React, Zustand, Vitest

**Spec:** `docs/superpowers/specs/2026-04-12-uimessage-first-rendering-design.md`

---

### Task 1: Update types.ts — Promote UiMessage, Remove Message

**Files:**
- Modify: `agent/webview/src/bridge/types.ts:1-24` (remove `Message` interface)
- Modify: `agent/webview/src/bridge/types.ts:28-38` (update `SubAgentState` to not reference `Message`)

This task removes the `Message` interface and makes `UiMessage` the primary message type. Sub-types (`ToolCall`, `Plan`, `SubAgentState`, etc.) are kept since child components use them.

- [ ] **Step 1: Remove the `Message` interface and `MessageRole` type**

In `agent/webview/src/bridge/types.ts`, delete lines 1-24:

```typescript
// DELETE these lines:
// export type MessageRole = 'user' | 'agent' | 'system';
// export interface Message { ... }
```

- [ ] **Step 2: Update `SubAgentState` to remove `Message` reference**

In `agent/webview/src/bridge/types.ts`, change the `messages` field in `SubAgentState` from `Message[]` to `UiMessage[]`:

```typescript
export interface SubAgentState {
  agentId: string;
  label: string;
  status: 'RUNNING' | 'COMPLETED' | 'ERROR' | 'KILLED';
  iteration: number;
  tokensUsed: number;
  messages: UiMessage[];       // was: Message[]
  activeToolChain: ToolCall[];
  summary?: string;
  startedAt: number;
}
```

- [ ] **Step 3: Update the UiMessage JSDoc comment**

Change the header comment at line 285 from "Kotlin→JS session rehydration" to reflect its new primary role:

```typescript
// ── UiMessage — primary message type for store, persistence, and rendering ──
// Mirrors the Kotlin UiMessage data class in agent/session/UiMessage.kt.
// Used by chatStore.messages[], persisted to ui_messages.json, and rendered
// directly by ChatView — no conversion layer.
```

- [ ] **Step 4: Verify no compile errors**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/session-persistence/agent/webview && npx tsc --noEmit 2>&1 | head -30`

Expected: TypeScript errors in files that still import `Message` (chatStore, AgentMessage, TopBar, tests). These are expected — they'll be fixed in subsequent tasks.

- [ ] **Step 5: Commit**

```bash
git add agent/webview/src/bridge/types.ts
git commit -m "refactor(webview): promote UiMessage to primary message type, remove Message interface"
```

---

### Task 2: Refactor chatStore State and Core Actions

**Files:**
- Modify: `agent/webview/src/stores/chatStore.ts:1-230` (imports, state interface, action signatures)

This task changes the store's state type from `Message[]` to `UiMessage[]`, updates the `ChatState` interface, and updates the streaming/message identity mechanism.

- [ ] **Step 1: Update imports**

In `agent/webview/src/stores/chatStore.ts`, replace the import block (lines 1-25):

```typescript
import { create } from 'zustand';
import type {
  UiMessage,
  UiSay,
  UiAsk,
  ToolCall,
  ToolCallStatus,
  Plan,
  PlanStep,
  PlanStepStatus,
  Question,
  SessionInfo,
  SessionStatus,
  Mention,
  MentionSearchResult,
  StatusType,
  EditDiff,
  Skill,
  ToastType,
  SubAgentState,
  EditStats,
  CheckpointInfo,
  RollbackInfo,
  HistoryItem,
  UiMessageToolCallData,
  UiMessagePlanData,
} from '../bridge/types';
```

- [ ] **Step 2: Update ChatState interface — state fields**

Change the `messages` field type and `activeStream` tracking (lines 72-80):

```typescript
interface ChatState {
  // State
  messages: UiMessage[];
  /**
   * Timestamp of the UiMessage currently being written by the token stream,
   * or null if no stream is active. The streaming message lives in `messages`
   * like any other, updated in place via `appendToken`.
   */
  activeStream: { messageTs: number } | null;
  activeToolCalls: Map<string, ToolCall>;
  // ... rest unchanged ...
```

- [ ] **Step 3: Update action signatures that reference Message**

The `addMessage` signature changes from `role: 'user' | 'agent'` to explicit say type. Replace lines 131-133:

```typescript
  addUserMessage(text: string, mentions?: Mention[]): void;
  addAgentText(text: string): void;
  appendToken(token: string): void;
```

- [ ] **Step 4: Commit**

```bash
git add agent/webview/src/stores/chatStore.ts
git commit -m "refactor(webview): update chatStore state and action signatures for UiMessage"
```

---

### Task 3: Rewrite Core Store Actions to Produce UiMessage

**Files:**
- Modify: `agent/webview/src/stores/chatStore.ts:411-770` (action implementations)

This is the largest task. Each store action that created `Message` objects now creates `UiMessage` objects.

- [ ] **Step 1: Rewrite `addUserMessage` and `addAgentText` (replacing `addMessage`)**

Replace the old `addMessage` implementation (lines 537-555) with two explicit actions:

```typescript
  addUserMessage(text: string, mentions?: Mention[]) {
    const msg: UiMessage = {
      ts: Date.now(),
      type: 'SAY',
      say: 'USER_MESSAGE',
      text,
    };
    set(state => ({ messages: [...state.messages, msg] }));
  },

  addAgentText(text: string) {
    const msg: UiMessage = {
      ts: Date.now(),
      type: 'SAY',
      say: 'TEXT',
      text,
    };
    set(state => ({ messages: [...state.messages, msg] }));
  },
```

- [ ] **Step 2: Rewrite `appendToken` and `endStream`**

Replace the streaming implementation (lines 556-621). The key change: `activeStream` tracks by `ts` instead of `id`, and placeholder is a `UiMessage`:

```typescript
  appendToken(token: string) {
    if (!token) return;
    set(state => {
      const current = state.activeStream;

      if (!current) {
        // First token — drain active tool calls into finalized TOOL messages
        let messages = state.messages;
        let activeToolCalls = state.activeToolCalls;
        let toolOutputStreams = state.toolOutputStreams;

        if (state.activeToolCalls.size > 0) {
          const streams = state.toolOutputStreams;
          const toolMsgs = Array.from(state.activeToolCalls.values()).map(tc => {
            const s = streams[tc.id];
            const toolData: UiMessageToolCallData = {
              toolCallId: tc.id,
              toolName: tc.name,
              args: tc.args,
              status: tc.status,
              result: tc.result,
              output: (s && !tc.output) ? s : tc.output,
              durationMs: tc.durationMs,
              diff: tc.diff,
              isError: tc.status === 'ERROR',
            };
            return { ts: Date.now(), type: 'SAY' as const, say: 'TOOL' as const, toolCallData: toolData };
          });
          messages = [...messages, ...toolMsgs];
          activeToolCalls = new Map();
          toolOutputStreams = {};
        }

        const messageTs = Date.now();
        const placeholder: UiMessage = {
          ts: messageTs,
          type: 'SAY',
          say: 'TEXT',
          text: token,
          partial: true,
        };

        return {
          messages: [...messages, placeholder],
          activeStream: { messageTs },
          activeToolCalls,
          toolOutputStreams,
        };
      }

      // Subsequent tokens — update existing placeholder in place by matching ts
      return {
        messages: state.messages.map(m =>
          m.ts === current.messageTs ? { ...m, text: (m.text || '') + token } : m
        ),
      };
    });
  },

  endStream() {
    const state = get();
    const shouldClearPlan = state.planCompletedPendingClear;
    // Flip partial to false on the streaming message
    const messageTs = state.activeStream?.messageTs;
    set({
      messages: messageTs
        ? state.messages.map(m => m.ts === messageTs ? { ...m, partial: false } : m)
        : state.messages,
      activeStream: null,
      ...(shouldClearPlan ? { plan: null, planCompletedPendingClear: false } : {}),
    });
  },
```

- [ ] **Step 3: Rewrite `addThinking`, `addCompletionSummary`, `addStatus`**

Replace lines 717-748. These no longer use JSON.stringify hacks:

```typescript
  addThinking(text: string) {
    const msg: UiMessage = { ts: Date.now(), type: 'SAY', say: 'REASONING', text };
    set(state => ({ messages: [...state.messages, msg] }));
  },

  addCompletionSummary(result: string, verifyCommand?: string) {
    const msg: UiMessage = {
      ts: Date.now(),
      type: 'ASK',
      ask: 'COMPLETION_RESULT',
      text: verifyCommand ? JSON.stringify({ result, verifyCommand }) : result,
    };
    set(state => ({
      messages: [...state.messages, msg],
      activeStream: null,
    }));
  },

  addStatus(message: string, type: StatusType) {
    const sayMap: Record<StatusType, UiSay> = {
      'INFO': 'CHECKPOINT_CREATED',
      'SUCCESS': 'CHECKPOINT_CREATED',
      'WARNING': 'CONTEXT_COMPRESSED',
      'ERROR': 'ERROR',
    };
    const msg: UiMessage = { ts: Date.now(), type: 'SAY', say: sayMap[type] || 'CHECKPOINT_CREATED', text: message };
    set(state => ({ messages: [...state.messages, msg] }));
  },
```

- [ ] **Step 4: Rewrite `addDiff` and `addDiffExplanation`**

Replace lines 697-715. These become SAY/TOOL messages with embedded data in text:

```typescript
  addDiff(diff: EditDiff) {
    const msg: UiMessage = {
      ts: Date.now(),
      type: 'SAY',
      say: 'TOOL',
      text: JSON.stringify(diff),
      toolCallData: {
        toolCallId: `diff-${Date.now()}`,
        toolName: 'edit_file',
        args: JSON.stringify({ path: diff.filePath }),
        status: 'COMPLETED',
        diff: JSON.stringify(diff),
      },
    };
    set(state => ({ messages: [...state.messages, msg] }));
  },

  addDiffExplanation(title: string, diffSource: string) {
    // Diff explanations are agent text with embedded diff — keep as TEXT with structured content
    const msg: UiMessage = {
      ts: Date.now(),
      type: 'SAY',
      say: 'TEXT',
      text: JSON.stringify({ type: 'diff-explanation', title, diffSource }),
    };
    set(state => ({ messages: [...state.messages, msg] }));
  },
```

- [ ] **Step 5: Rewrite `finalizeToolChain`**

Replace lines 750-771. Instead of one Message with toolChain array, emit one UiMessage per tool:

```typescript
  finalizeToolChain() {
    const state = get();
    const tools = Array.from(state.activeToolCalls.values());
    if (tools.length === 0) return;
    const streams = state.toolOutputStreams;
    const toolMessages: UiMessage[] = tools.map(tc => {
      const stream = streams[tc.id];
      return {
        ts: Date.now(),
        type: 'SAY' as const,
        say: 'TOOL' as const,
        toolCallData: {
          toolCallId: tc.id,
          toolName: tc.name,
          args: tc.args,
          status: tc.status,
          result: tc.result,
          output: (stream && !tc.output) ? stream : tc.output,
          durationMs: tc.durationMs,
          diff: tc.diff,
          isError: tc.status === 'ERROR',
        },
      };
    });
    set({
      messages: [...state.messages, ...toolMessages],
      activeToolCalls: new Map(),
      toolOutputStreams: {},
    });
  },
```

- [ ] **Step 6: Rewrite `addToolCall` — update activeStream tracking**

In the `addToolCall` action (lines 623-658), change the `activeStream` clearing to use the new field name:

```typescript
      // Clear the streaming caret
      if (state.activeStream != null) {
        return {
          activeStream: null,
          activeToolCalls: newMap,
        };
      }
```

This part is unchanged since `activeStream` is just set to null. The only change is ensuring any reference to `activeStream.messageId` becomes `activeStream.messageTs`.

- [ ] **Step 7: Commit**

```bash
git add agent/webview/src/stores/chatStore.ts
git commit -m "refactor(webview): rewrite core store actions to produce UiMessage objects"
```

---

### Task 4: Rewrite Artifact, SubAgent, and Rich Message Store Actions

**Files:**
- Modify: `agent/webview/src/stores/chatStore.ts:870-1528` (artifact, subagent, chart, and other rich actions)

- [ ] **Step 1: Rewrite `addArtifact`**

The artifact action currently creates a `Message` with `artifact` field. Now creates a `UiMessage{say:'ARTIFACT_RESULT'}`:

```typescript
  addArtifact(payload: string) {
    try {
      const { title, source, renderId } = JSON.parse(payload);
      const msg: UiMessage = {
        ts: Date.now(),
        type: 'SAY',
        say: 'ARTIFACT_RESULT',
        text: source,
        artifactId: renderId,
      };
      set(state => ({ messages: [...state.messages, msg] }));
    } catch (e) {
      console.warn('[chatStore] addArtifact: malformed payload', e);
    }
  },
```

- [ ] **Step 2: Rewrite `spawnSubAgent`**

```typescript
  spawnSubAgent(payload: string) {
    try {
      const { agentId, label } = JSON.parse(payload);
      const msg: UiMessage = {
        ts: Date.now(),
        type: 'SAY',
        say: 'SUBAGENT_STARTED',
        subagentData: {
          agentId,
          agentType: 'general-purpose',
          description: label || agentId,
          status: 'RUNNING',
          iterations: 0,
        },
      };
      set(state => ({ messages: [...state.messages, msg] }));
    } catch (e) {
      console.warn('[chatStore] spawnSubAgent: malformed payload', e);
    }
  },
```

- [ ] **Step 3: Rewrite `completeSubAgent`**

```typescript
  completeSubAgent(payload: string) {
    try {
      const { agentId, summary, tokensUsed } = JSON.parse(payload);
      set(state => ({
        messages: state.messages.map(m => {
          if (m.subagentData?.agentId === agentId) {
            return {
              ...m,
              say: 'SUBAGENT_COMPLETED' as const,
              subagentData: {
                ...m.subagentData!,
                status: 'COMPLETED',
                summary,
              },
            };
          }
          return m;
        }),
      }));
    } catch (e) {
      console.warn('[chatStore] completeSubAgent: malformed payload', e);
    }
  },
```

- [ ] **Step 4: Rewrite `updateSubAgentIteration`, `addSubAgentToolCall`, `updateSubAgentToolCall`, `updateSubAgentMessage`, `killSubAgent`**

Each of these finds the matching subagent message by `subagentData.agentId` and updates it. The pattern is the same as above — `state.messages.map(m => m.subagentData?.agentId === agentId ? {...m, subagentData: {...}} : m)`.

- [ ] **Step 5: Rewrite rich message actions (addChart, addAnsiOutput, addTabs, addTimeline, addProgressBar, addJiraCard, addSonarBadge)**

Each of these currently creates `Message{role:'system', content:JSON.stringify({type:'chart',...})}`. Convert to use `UiMessage{say:'TEXT', text:JSON.stringify({type:'chart',...})}`. The rendering dispatch for these structured text types stays in `AgentMessage` since they're rendered from parsed JSON in the text field.

Example for `addChart`:
```typescript
  addChart(chartConfigJson: string) {
    const msg: UiMessage = {
      ts: Date.now(),
      type: 'SAY',
      say: 'TEXT',
      text: JSON.stringify({ type: 'chart', config: JSON.parse(chartConfigJson) }),
    };
    set(state => ({ messages: [...state.messages, msg] }));
  },
```

- [ ] **Step 6: Rewrite `applyRollback`**

The rollback action marks messages as rolled back. Update to use `ts` matching:

```typescript
  applyRollback(rollback: RollbackInfo) {
    set(state => ({
      messages: state.messages.map(m => {
        // Mark tool messages whose IDs are in the rolled-back set
        if (m.toolCallData && rollback.rolledBackEntryIds.includes(m.toolCallData.toolCallId)) {
          return { ...m, toolCallData: { ...m.toolCallData, status: 'ROLLED_BACK' } };
        }
        return m;
      }),
      rollbackEvents: [...state.rollbackEvents, rollback],
    }));
  },
```

- [ ] **Step 7: Commit**

```bash
git add agent/webview/src/stores/chatStore.ts
git commit -m "refactor(webview): rewrite artifact, subagent, and rich message store actions for UiMessage"
```

---

### Task 5: Delete Conversion Layer and Simplify Hydration

**Files:**
- Modify: `agent/webview/src/stores/chatStore.ts:236-409` (delete convertUiMessageToStoreMessage)
- Modify: `agent/webview/src/stores/chatStore.ts:1530-1582` (simplify hydrateFromUiMessages)

- [ ] **Step 1: Delete `stripToolCallXmlTags` function**

Delete lines 239-248 entirely.

- [ ] **Step 2: Delete `convertUiMessageToStoreMessage` function**

Delete lines 250-409 entirely (~160 lines).

- [ ] **Step 3: Rewrite `hydrateFromUiMessages`**

Replace lines 1530-1582 with the simplified version:

```typescript
  hydrateFromUiMessages(uiMessages: UiMessage[]) {
    // Filter out internal tracking messages and partial (interrupted) messages
    const visible = uiMessages.filter(
      m => m.say !== 'API_REQ_STARTED' && m.say !== 'API_REQ_FINISHED' && !m.partial
    );

    // Restore plan from last PLAN_UPDATE message
    let restoredPlan: Plan | null = null;
    for (let i = visible.length - 1; i >= 0; i--) {
      const m = visible[i]!;
      if (m.planData) {
        const pd = m.planData;
        restoredPlan = {
          title: 'Plan',
          steps: pd.steps.map((s, si) => ({
            id: `plan-step-${si}`,
            title: s.title,
            status: (s.status as PlanStepStatus) || 'pending',
            comment: pd.comments?.[si] ?? undefined,
          })),
          approved: pd.status === 'APPROVED' || pd.status === 'EXECUTING',
        };
        break;
      }
    }

    set({
      messages: visible,
      activeStream: null,
      viewMode: 'chat',
      ...(restoredPlan ? { plan: restoredPlan } : {}),
    });
  },
```

- [ ] **Step 4: Commit**

```bash
git add agent/webview/src/stores/chatStore.ts
git commit -m "refactor(webview): delete conversion layer, simplify hydration to direct UiMessage load"
```

---

### Task 6: Rewrite ChatView Rendering Dispatch

**Files:**
- Modify: `agent/webview/src/components/chat/ChatView.tsx:1-10` (imports)
- Modify: `agent/webview/src/components/chat/ChatView.tsx:370-412` (message rendering loop)

- [ ] **Step 1: Add `useMemo` import and UiMessage type import**

Update the imports at the top of ChatView.tsx:

```typescript
import { memo, useCallback, useRef, useEffect, useState, useMemo } from 'react';
import type { UiMessage, ToolCall } from '@/bridge/types';
```

- [ ] **Step 2: Add render-time tool grouping**

Add a `useMemo` before the return statement (before line 364) that groups consecutive TOOL messages:

```typescript
  // Group consecutive TOOL messages into tool chains at render time (like Cline's combineCommandSequences).
  // This is a display concern — persistence stores one UiMessage per tool call.
  type RenderItem = { kind: 'message'; msg: UiMessage; idx: number }
                  | { kind: 'toolGroup'; tools: UiMessage[]; idx: number };

  const renderItems: RenderItem[] = useMemo(() => {
    const result: RenderItem[] = [];
    let toolBuffer: UiMessage[] = [];
    let toolStartIdx = 0;

    for (let i = 0; i < messages.length; i++) {
      const msg = messages[i]!;
      if (msg.say === 'TOOL' && msg.toolCallData) {
        if (toolBuffer.length === 0) toolStartIdx = i;
        toolBuffer.push(msg);
      } else {
        if (toolBuffer.length > 0) {
          result.push({ kind: 'toolGroup', tools: toolBuffer, idx: toolStartIdx });
          toolBuffer = [];
        }
        result.push({ kind: 'message', msg, idx: i });
      }
    }
    if (toolBuffer.length > 0) {
      result.push({ kind: 'toolGroup', tools: toolBuffer, idx: toolStartIdx });
    }
    return result;
  }, [messages]);
```

- [ ] **Step 3: Rewrite the message rendering loop**

Replace the `messages.map(...)` block (lines 372-412) with the new `say`/`ask`-based dispatch:

```tsx
        {renderItems.map((item) => {
          if (item.kind === 'toolGroup') {
            const toolCalls: ToolCall[] = item.tools.map(m => {
              const tc = m.toolCallData!;
              return {
                id: tc.toolCallId,
                name: tc.toolName,
                args: tc.args || '',
                status: (tc.isError ? 'ERROR' : tc.status || 'COMPLETED') as any,
                result: tc.result,
                output: tc.output,
                durationMs: tc.durationMs,
                diff: tc.diff,
              };
            });
            return (
              <ErrorBoundary key={`tg-${item.idx}`}>
                <ToolCallChain toolCalls={toolCalls} />
              </ErrorBoundary>
            );
          }

          const msg = item.msg;
          const key = `msg-${msg.ts}-${item.idx}`;
          const type = msg.type === 'ASK' ? msg.ask : msg.say;
          const isStreamingMsg = activeStream?.messageTs === msg.ts;

          switch (type) {
            case 'USER_MESSAGE':
              return (
                <ErrorBoundary key={key}>
                  <AgentMessage message={msg} />
                </ErrorBoundary>
              );

            case 'TEXT':
              return (
                <ErrorBoundary key={key}>
                  <div
                    ref={item.idx === messages.length - 1 ? lastAgentMsgRef : undefined}
                    style={{ animationDelay: `${Math.min(item.idx * 40, 200)}ms` }}
                  >
                    <AgentMessage message={msg} isStreaming={isStreamingMsg} />
                  </div>
                </ErrorBoundary>
              );

            case 'REASONING':
              return (
                <ErrorBoundary key={key}>
                  <ThinkingView content={msg.text ?? ''} isStreaming={false} />
                </ErrorBoundary>
              );

            case 'COMPLETION_RESULT': {
              // Parse verifyCommand if embedded
              let result = msg.text || '';
              let verifyCommand: string | undefined;
              try {
                const parsed = JSON.parse(msg.text || '');
                if (parsed.result) { result = parsed.result; verifyCommand = parsed.verifyCommand; }
              } catch { /* plain text result */ }
              return (
                <ErrorBoundary key={key}>
                  <CompletionCard result={result} verifyCommand={verifyCommand} />
                </ErrorBoundary>
              );
            }

            case 'PLAN_UPDATE':
              // Plan card rendered globally via chatStore.plan, skip individual message
              return null;

            case 'ARTIFACT_RESULT':
              return (
                <ErrorBoundary key={key}>
                  <ArtifactRenderer source={msg.text || ''} title="Artifact" renderId={msg.artifactId} />
                </ErrorBoundary>
              );

            case 'SUBAGENT_STARTED':
            case 'SUBAGENT_PROGRESS':
            case 'SUBAGENT_COMPLETED':
              if (!msg.subagentData) return null;
              return (
                <ErrorBoundary key={key}>
                  <SubAgentView subAgent={{
                    agentId: msg.subagentData.agentId,
                    label: msg.subagentData.description || msg.subagentData.agentType,
                    status: msg.subagentData.status as any,
                    iteration: msg.subagentData.iterations || 0,
                    tokensUsed: 0,
                    messages: [],
                    activeToolChain: [],
                    summary: msg.subagentData.summary,
                    startedAt: msg.ts,
                  }} />
                </ErrorBoundary>
              );

            case 'ERROR':
            case 'CHECKPOINT_CREATED':
            case 'CONTEXT_COMPRESSED':
            case 'MEMORY_SAVED':
            case 'ROLLBACK_PERFORMED':
            case 'STEERING_RECEIVED':
              return (
                <ErrorBoundary key={key}>
                  <div className="px-1 py-0.5 text-[11px]" style={{ color: 'var(--fg-muted, #888)' }}>
                    {msg.text}
                  </div>
                </ErrorBoundary>
              );

            case 'QUESTION_WIZARD':
              if (!msg.questionData || msg.questionData.status !== 'COMPLETED') return null;
              return (
                <ErrorBoundary key={key}>
                  <AnsweredQuestionsCard questions={msg.questionData.questions.map((q, i) => ({
                    id: `q-${msg.ts}-${i}`,
                    text: q.text,
                    type: 'single-select' as const,
                    options: q.options.map(opt => ({ label: opt })),
                    answer: msg.questionData!.answers?.[i] ?? undefined,
                    skipped: !msg.questionData!.answers?.[i],
                  }))} />
                </ErrorBoundary>
              );

            case 'APPROVAL_GATE':
              if (!msg.approvalData) return null;
              // Approval gate messages are historical — show as completed tool
              return (
                <ErrorBoundary key={key}>
                  <ToolCallChain toolCalls={[{
                    id: `approval-${msg.ts}`,
                    name: msg.approvalData.toolName,
                    args: msg.approvalData.toolInput || '',
                    status: msg.approvalData.status === 'PENDING' ? 'RUNNING' : 'COMPLETED',
                    result: msg.approvalData.status,
                  }]} />
                </ErrorBoundary>
              );

            // Hidden message types (filtered by visibility, but safety fallthrough)
            case 'API_REQ_STARTED':
            case 'API_REQ_FINISHED':
            case 'RESUME_TASK':
            case 'RESUME_COMPLETED_TASK':
              return null;

            default:
              // Structured text (charts, diffs, etc.) — delegate to AgentMessage
              if (msg.text) {
                return (
                  <ErrorBoundary key={key}>
                    <AgentMessage message={msg} />
                  </ErrorBoundary>
                );
              }
              return null;
          }
        })}
```

- [ ] **Step 4: Add missing component imports**

Add these imports if not already present:

```typescript
import { ThinkingView } from '@/components/agent/ThinkingView';
import { CompletionCard } from '@/components/agent/CompletionCard';
import { AnsweredQuestionsCard } from '@/components/agent/AnsweredQuestionsCard';
```

- [ ] **Step 5: Commit**

```bash
git add agent/webview/src/components/chat/ChatView.tsx
git commit -m "refactor(webview): rewrite ChatView dispatch on say/ask enums with render-time tool grouping"
```

---

### Task 7: Simplify AgentMessage to Pure Text Renderer

**Files:**
- Modify: `agent/webview/src/components/chat/AgentMessage.tsx`

AgentMessage no longer handles system message dispatch (thinking, completion, status). It only renders user bubbles (USER_MESSAGE) and agent text (TEXT with possible structured JSON).

- [ ] **Step 1: Update the component props and imports**

```typescript
import type { UiMessage } from '@/bridge/types';
// ... keep existing UI component imports ...

interface AgentMessageProps {
  message: UiMessage;
  isStreaming?: boolean;
}
```

- [ ] **Step 2: Rewrite the component body**

Replace the rendering logic. Remove the JSON.parse system message dispatch. The component now handles:
- `USER_MESSAGE` → user bubble (right-aligned)
- `TEXT` → agent bubble with markdown (may contain structured JSON for charts, diffs)

```typescript
export const AgentMessage = memo(function AgentMessage({
  message,
  isStreaming = false,
}: AgentMessageProps) {
  const isUser = message.say === 'USER_MESSAGE';
  const content = message.text || '';

  // Check for structured content in agent text (charts, diffs, etc.)
  if (!isUser && content.startsWith('{')) {
    try {
      const parsed = JSON.parse(content) as Record<string, any>;
      if (parsed.type === 'chart' && parsed.config) {
        return <ChartView source={parsed.config} />;
      }
      if (parsed.type === 'diff-explanation' && parsed.diffSource) {
        return (
          <div style={{ marginBottom: 8 }}>
            {parsed.title && (
              <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 6, color: 'var(--fg-secondary, #94a3b8)' }}>
                {parsed.title}
              </div>
            )}
            <DiffHtml diffSource={parsed.diffSource} />
          </div>
        );
      }
      if (parsed.filePath && Array.isArray(parsed.oldLines) && Array.isArray(parsed.newLines)) {
        return (
          <EditDiffView
            filePath={parsed.filePath}
            oldLines={parsed.oldLines}
            newLines={parsed.newLines}
            accepted={parsed.accepted ?? null}
          />
        );
      }
    } catch {
      // Not JSON — render as markdown
    }
  }

  return (
    <PkMessage className={cn('group w-full animate-[message-enter_220ms_ease-out_both]', isUser ? 'flex-row-reverse' : '')}>
      {!isUser && (
        <MessageAvatar fallback="A" className="h-5 w-5 bg-[var(--accent,#6366f1)] text-[10px] font-bold text-[var(--bg)]" />
      )}
      <div className={cn(
        'relative max-w-[85%] rounded-lg px-4 py-3 whitespace-normal [overflow-wrap:anywhere]',
        isUser ? 'bg-[var(--user-bg)] text-[var(--fg)]' : 'bg-transparent text-[var(--fg)]',
      )}>
        {!isUser && (
          <span className="mb-1 block text-[11px] font-medium text-[var(--fg-secondary)]">Agent</span>
        )}

        {message.answeredQuestions && message.answeredQuestions.length > 0 ? (
          <AnsweredQuestionsCard questions={message.answeredQuestions} />
        ) : isUser ? (
          <UserContent content={content} mentions={message.mentions} />
        ) : (
          <MarkdownRenderer content={content} isStreaming={isStreaming} />
        )}

        {!isStreaming && content && (
          <CopyButton text={content} hoverOnly label="Copy message" className="absolute top-2 right-2" />
        )}
      </div>
    </PkMessage>
  );
});
```

Note: `message.mentions` doesn't exist on `UiMessage` — for user messages with mentions, store them as a runtime `Map<number, Mention[]>` in the chatStore keyed by `ts`, or add `mentions?: Mention[]` as an optional field on `UiMessage` in types.ts (preferred, since it matches Kotlin-side persistence). The `answeredQuestions` rendering moves to ChatView (Task 6, QUESTION_WIZARD case).

- [ ] **Step 3: Commit**

```bash
git add agent/webview/src/components/chat/AgentMessage.tsx
git commit -m "refactor(webview): simplify AgentMessage to pure text renderer for UiMessage"
```

---

### Task 8: Update Bridge Functions and TopBar

**Files:**
- Modify: `agent/webview/src/bridge/jcef-bridge.ts:5` (imports)
- Modify: `agent/webview/src/bridge/jcef-bridge.ts:120-165` (bridge functions that call addMessage)
- Modify: `agent/webview/src/components/chat/TopBar.tsx:4,25` (Message → UiMessage)

- [ ] **Step 1: Update jcef-bridge imports**

Remove `Message` from imports if present. `UiMessage` is already imported.

- [ ] **Step 2: Update bridge functions that call `addMessage`**

In `jcef-bridge.ts`, find calls to `store.addMessage(role, text)` and replace:
- `addMessage('user', text)` → `addUserMessage(text)`
- `addMessage('agent', text)` → `addAgentText(text)`
- `addMessage('user', text, mentions)` → `addUserMessage(text, mentions)`

Search for these in the `startSession`, `appendUserMessage`, `appendUserMessageWithMentions` bridge functions.

- [ ] **Step 3: Update TopBar.tsx**

Replace `Message` import and usage:

```typescript
import type { UiMessage } from '@/bridge/types';
// ...
// Change the subagent filter:
return messages.filter((m: UiMessage) => m.subagentData?.status === 'RUNNING').length;
```

- [ ] **Step 4: Commit**

```bash
git add agent/webview/src/bridge/jcef-bridge.ts agent/webview/src/components/chat/TopBar.tsx
git commit -m "refactor(webview): update bridge and TopBar for UiMessage"
```

---

### Task 9: Update Tests

**Files:**
- Modify: `agent/webview/src/__tests__/chat-store-streaming.test.ts`
- Modify: `agent/webview/src/__tests__/chat-store-test-utils.ts`
- Modify: `agent/webview/src/__tests__/streaming-markdown.test.tsx`
- Create: `agent/webview/src/__tests__/hydration-roundtrip.test.ts`

- [ ] **Step 1: Update test utils**

In `chat-store-test-utils.ts`, update the `ToolCall` import (keep as-is, it's still a valid type).

- [ ] **Step 2: Update streaming tests**

In `chat-store-streaming.test.ts`, update assertions to use UiMessage shape:

```typescript
  it('first appendToken creates a placeholder UiMessage and points activeStream at it', () => {
    chatState().appendToken('Hello');

    const state = chatState();
    expect(state.messages).toHaveLength(1);
    expect(state.messages[0]!.say).toBe('TEXT');
    expect(state.messages[0]!.text).toBe('Hello');
    expect(state.messages[0]!.partial).toBe(true);
    expect(state.activeStream).not.toBeNull();
    expect(state.activeStream!.messageTs).toBe(state.messages[0]!.ts);
  });
```

Update all tests that reference `message.role`, `message.content`, or `message.id` to use `message.say`, `message.text`, and `message.ts`.

- [ ] **Step 3: Update streaming-markdown.test.tsx**

Change `Message` import to `UiMessage`. Update mock message creation to use UiMessage shape.

- [ ] **Step 4: Write hydration round-trip test**

Create `agent/webview/src/__tests__/hydration-roundtrip.test.ts`:

```typescript
/**
 * Round-trip test: messages created via live store actions must produce
 * the same render output as messages loaded via hydrateFromUiMessages().
 * This is the core contract that eliminates the conversion-layer bugs.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { chatState, resetChatStore } from './chat-store-test-utils';
import type { UiMessage } from '@/bridge/types';

describe('hydration round-trip', () => {
  beforeEach(resetChatStore);

  it('live agent text messages survive hydration unchanged', () => {
    chatState().addAgentText('Hello from the agent');
    const liveMessages = [...chatState().messages];

    resetChatStore();
    chatState().hydrateFromUiMessages(liveMessages);

    const hydrated = chatState().messages;
    expect(hydrated).toHaveLength(1);
    expect(hydrated[0]!.say).toBe('TEXT');
    expect(hydrated[0]!.text).toBe('Hello from the agent');
  });

  it('live user messages survive hydration unchanged', () => {
    chatState().addUserMessage('User question');
    const liveMessages = [...chatState().messages];

    resetChatStore();
    chatState().hydrateFromUiMessages(liveMessages);

    const hydrated = chatState().messages;
    expect(hydrated).toHaveLength(1);
    expect(hydrated[0]!.say).toBe('USER_MESSAGE');
    expect(hydrated[0]!.text).toBe('User question');
  });

  it('finalized tool chain messages survive hydration', () => {
    chatState().addToolCall('tc-1', 'read_file', '{"path":"/foo"}', 'RUNNING');
    chatState().updateToolCall('read_file', 'COMPLETED', 'file contents', 100, undefined, undefined, 'tc-1');
    chatState().finalizeToolChain();
    const liveMessages = [...chatState().messages];

    resetChatStore();
    chatState().hydrateFromUiMessages(liveMessages);

    const hydrated = chatState().messages;
    expect(hydrated.length).toBeGreaterThan(0);
    const toolMsg = hydrated.find(m => m.say === 'TOOL');
    expect(toolMsg).toBeDefined();
    expect(toolMsg!.toolCallData!.toolName).toBe('read_file');
    expect(toolMsg!.toolCallData!.result).toBe('file contents');
  });

  it('thinking messages survive hydration', () => {
    chatState().addThinking('Let me analyze this...');
    const liveMessages = [...chatState().messages];

    resetChatStore();
    chatState().hydrateFromUiMessages(liveMessages);

    const hydrated = chatState().messages;
    expect(hydrated[0]!.say).toBe('REASONING');
    expect(hydrated[0]!.text).toBe('Let me analyze this...');
  });

  it('partial (streaming) messages are filtered during hydration', () => {
    chatState().appendToken('In progress...');
    // Don't call endStream — message stays partial
    const liveMessages = [...chatState().messages];
    expect(liveMessages[0]!.partial).toBe(true);

    resetChatStore();
    chatState().hydrateFromUiMessages(liveMessages);

    // Partial messages are filtered out
    expect(chatState().messages).toHaveLength(0);
  });

  it('plan state is restored from PLAN_UPDATE messages', () => {
    const planMsg: UiMessage = {
      ts: Date.now(),
      type: 'SAY',
      say: 'PLAN_UPDATE',
      planData: {
        steps: [{ title: 'Step 1', status: 'completed' }, { title: 'Step 2', status: 'pending' }],
        status: 'EXECUTING',
        comments: {},
      },
    };

    chatState().hydrateFromUiMessages([planMsg]);

    const plan = chatState().plan;
    expect(plan).not.toBeNull();
    expect(plan!.steps).toHaveLength(2);
    expect(plan!.approved).toBe(true); // EXECUTING = approved
  });
});
```

- [ ] **Step 5: Run all tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/session-persistence/agent/webview && npx vitest run 2>&1 | tail -20`

Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add agent/webview/src/__tests__/
git commit -m "test(webview): update streaming tests and add hydration round-trip tests for UiMessage"
```

---

### Task 10: Final Verification and Cleanup

**Files:**
- Modify: `agent/webview/src/showcase/mock-data.ts` (update mock data if needed)

- [ ] **Step 1: TypeScript compilation check**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/session-persistence/agent/webview && npx tsc --noEmit`

Expected: No errors.

- [ ] **Step 2: Build check**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/session-persistence/agent/webview && npm run build`

Expected: Build succeeds, output in `agent/src/main/resources/webview/dist/`.

- [ ] **Step 3: Run full test suite**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/session-persistence/agent/webview && npx vitest run`

Expected: All tests pass.

- [ ] **Step 4: Gradle build check**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/session-persistence && ./gradlew :agent:test`

Expected: All agent tests pass (Kotlin side unchanged).

- [ ] **Step 5: Update showcase mock data if needed**

If `agent/webview/src/showcase/mock-data.ts` imports `Message`, update it to use `UiMessage` shape. This file is only used for the dev showcase page, not production.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "chore(webview): final cleanup and verification for UiMessage-first rendering"
```
