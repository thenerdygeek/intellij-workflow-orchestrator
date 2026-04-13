# UiMessage-First Rendering — Cline-Faithful Session Rehydration

**Date:** 2026-04-12
**Branch:** `feature/session-persistence-cline-port`
**Scope:** Agent webview only (TypeScript/React). Zero Kotlin changes.

## Problem

The agent chat webview has two independent rendering paths that must produce identical output but don't:

1. **Live path** (during active session): 42 granular Kotlin→JS bridge calls (`appendToken`, `addToolCall`, `appendThinking`, etc.) invoke Zustand store actions that create `Message` objects with fields like `role`, `toolChain`, `subAgent`, `artifact`, `answeredQuestions`.

2. **Resume path** (session rehydration): `UiMessage[]` loaded from `ui_messages.json` → `convertUiMessageToStoreMessage()` → reconstructed `Message` objects → same React components.

The conversion function (`convertUiMessageToStoreMessage`) is a 160-line switch statement with 14+ SAY cases, 7+ ASK cases, tool chain grouping logic, and JSON-encoding hacks. It is the sole source of every session resume bug:

- Tool calls render as plain text (toolCallData not mapped to toolChain)
- Thinking blocks render as raw markdown (JSON.stringify hack not reconstructed)
- Plan cards lost on resume (PLAN_UPDATE falls through to default)
- Completion results invisible (ASK/COMPLETION_RESULT not handled)
- Artifact results as plain text (ARTIFACT_RESULT not handled)
- Regex stripping legitimate HTML content (overly broad stripToolCallXmlTags)
- Plan status mismatch (IN_PROGRESS vs EXECUTING enum name)

**Root cause:** Two message types exist — `UiMessage` (Kotlin domain model, persisted to disk) and `Message` (JS view model, used for rendering) — with a lossy translation layer between them.

**Cline's architecture:** One type (`ClineMessage`) used everywhere. The webview's `ChatRow` component reads `message.ask`/`message.say` directly and dispatches to the right renderer. No conversion. Persisted messages load directly into the webview. Resume = live.

## Solution

Eliminate the `Message` intermediate type. Make the webview store and render `UiMessage` directly, dispatching on `say`/`ask` enums — matching Cline's `ChatRow` pattern.

## Architecture

### Data Flow — Before (broken)

```
Live:    Kotlin → bridge calls → store actions → Message{role,toolChain,...} → React
Persist: Kotlin → UiMessage{type,say,ask,...} → disk
Resume:  disk → UiMessage → convertUiMessageToStoreMessage() → Message → React
                                    ↑ ALL BUGS HERE
```

### Data Flow — After (Cline-faithful)

```
Live:    Kotlin → bridge calls → store actions → UiMessage{type,say,ask,...} → React
Persist: Kotlin → UiMessage → disk (unchanged)
Resume:  disk → UiMessage → set({messages: filtered}) → React (same path)
```

One type. One render path. Resume = live.

### JCEF Bridge Constraint

Cline uses VS Code's `postMessage` which efficiently sends the full `ClineMessage[]` on every state change. JCEF's `callJs()` serializes to a JS string, making full-state pushes too expensive for large sessions.

Our approach:
- **Resume**: Full `UiMessage[]` push (already works via `loadSessionState` bridge)
- **Live**: Granular bridge calls stay for streaming performance. Store actions create `UiMessage` objects instead of `Message` objects.
- **Streaming**: `appendToken()` creates/updates a `UiMessage{say:'TEXT', partial:true}`. `endStream()` flips `partial:false`. Mechanism unchanged.

The drift from Cline is transport-only (individual push vs full array). The data model and rendering logic are identical.

## Detailed Changes

### 1. Store (chatStore.ts)

**State shape change:**
```typescript
// Before
messages: Message[]

// After
messages: UiMessage[]
```

**Store action changes (examples):**

| Action | Before (creates Message) | After (creates UiMessage) |
|---|---|---|
| `addMessage('agent', text)` | `{id, role:'agent', content:text, timestamp}` | `{ts, type:'SAY', say:'TEXT', text}` |
| `addMessage('user', text)` | `{id, role:'user', content:text, timestamp}` | `{ts, type:'SAY', say:'USER_MESSAGE', text}` |
| `addThinking(text)` | `{role:'system', content:JSON.stringify({type:'thinking',text})}` | `{ts, type:'SAY', say:'REASONING', text}` |
| `addCompletionSummary(result)` | `{role:'system', content:JSON.stringify({type:'completion',result})}` | `{ts, type:'ASK', ask:'COMPLETION_RESULT', text:result}` |
| `addStatus(msg, type)` | `{role:'system', content:JSON.stringify({type:'status',message})}` | `{ts, type:'SAY', say: statusToSay(type), text:msg}` |
| `finalizeToolChain()` | One `Message{toolChain:[...]}` | Multiple `UiMessage{say:'TOOL', toolCallData:{...}}`, one per tool |

**Streaming (appendToken/endStream):** Mechanism unchanged. Creates `UiMessage{say:'TEXT', text:token, partial:true}` instead of `Message{role:'agent', content:token}`. The `activeStream` tracks the index of the streaming message in the `messages` array (since `UiMessage` uses `ts` for identity, not a generated `id`). `appendToken` finds and updates the streaming message by matching `ts`. `endStream` flips `partial:false` and clears `activeStream`.

**Tool call staging:** `activeToolCalls: Map<string, ToolCall>` stays as a live staging area. On `finalizeToolChain()`, each tool produces its own `UiMessage{say:'TOOL', toolCallData:{...}}`. This matches how Kotlin persists tool calls — one UiMessage per tool.

**hydrateFromUiMessages() becomes trivial:**
```typescript
hydrateFromUiMessages(uiMessages: UiMessage[]) {
  const visible = uiMessages.filter(
    m => m.say !== 'API_REQ_STARTED' && m.say !== 'API_REQ_FINISHED' && !m.partial
  );
  // Restore plan from last PLAN_UPDATE message
  let plan: Plan | null = null;
  for (let i = visible.length - 1; i >= 0; i--) {
    if (visible[i].planData) {
      plan = mapPlanData(visible[i].planData);
      break;
    }
  }
  set({ messages: visible, activeStream: null, viewMode: 'chat', ...(plan ? { plan } : {}) });
}
```

**Deleted code:**
- `convertUiMessageToStoreMessage()` — entire function (~160 lines)
- `stripToolCallXmlTags()` — the overly broad regex
- Tool chain grouping logic in old `hydrateFromUiMessages()` (~50 lines)
- All `JSON.stringify({type:'thinking'|'completion'|'status',...})` encoding hacks

### 2. Rendering (ChatView.tsx + AgentMessage.tsx)

**ChatView message dispatch** changes from field-presence checks to `say`/`ask` enum dispatch:

```typescript
// Before (field-presence based)
if (msg.subAgent) return <SubAgentView subAgent={msg.subAgent} />;
if (msg.artifact) return <ArtifactRenderer ... />;
if (msg.toolChain) return <ToolCallChain toolCalls={msg.toolChain} />;
return <AgentMessage message={msg} />;  // AgentMessage parses JSON for system msgs

// After (say/ask enum dispatch, matching Cline's ChatRow)
const type = msg.type === 'ASK' ? msg.ask : msg.say;
switch (type) {
  case 'USER_MESSAGE': return <UserBubble text={msg.text} mentions={msg.mentions} />;
  case 'TEXT':         return <AgentTextBubble text={msg.text} isStreaming={...} />;
  case 'REASONING':    return <ThinkingView content={msg.text} />;
  case 'TOOL':         return null; // handled by tool grouping (see below)
  case 'ERROR':        return <StatusLine text={msg.text} type="ERROR" />;
  case 'PLAN_UPDATE':  return <PlanCard data={msg.planData} />;
  case 'ARTIFACT_RESULT': return <ArtifactRenderer source={msg.text} id={msg.artifactId} />;
  case 'SUBAGENT_STARTED':
  case 'SUBAGENT_PROGRESS':
  case 'SUBAGENT_COMPLETED': return <SubAgentView data={msg.subagentData} />;
  case 'COMPLETION_RESULT':  return <CompletionCard result={msg.text} />;
  case 'QUESTION_WIZARD':    return <AnsweredQuestionsCard data={msg.questionData} />;
  case 'APPROVAL_GATE':      return <ApprovalView data={msg.approvalData} />;
  case 'CHECKPOINT_CREATED':
  case 'CONTEXT_COMPRESSED':
  case 'MEMORY_SAVED':
  case 'ROLLBACK_PERFORMED':
  case 'STEERING_RECEIVED': return <StatusLine text={msg.text} />;
  default: return null; // API_REQ_STARTED, API_REQ_FINISHED filtered by visibility
}
```

**Tool chain grouping** moves to a `useMemo` in `ChatView` (like Cline's `combineCommandSequences`):

```typescript
const groupedMessages = useMemo(() => {
  const result: (UiMessage | { _toolGroup: true; tools: UiMessage[] })[] = [];
  let toolBuffer: UiMessage[] = [];

  for (const msg of messages) {
    if (msg.say === 'TOOL') {
      toolBuffer.push(msg);
    } else {
      if (toolBuffer.length > 0) {
        result.push({ _toolGroup: true, tools: toolBuffer });
        toolBuffer = [];
      }
      result.push(msg);
    }
  }
  if (toolBuffer.length > 0) {
    result.push({ _toolGroup: true, tools: toolBuffer });
  }
  return result;
}, [messages]);
```

This groups consecutive TOOL messages at render-time, not at persistence-time. The grouping happens identically for live and resumed sessions.

**AgentMessage.tsx** is simplified — the JSON.parse dispatch for system messages (`type:'thinking'`, `type:'completion'`, `type:'status'`) is removed entirely since each message type has its own explicit case in ChatView.

### 3. Types (types.ts)

**`Message` interface removed.** `UiMessage` (already defined in types.ts at line 359) becomes the primary message type consumed by all components.

**Sub-types preserved:** `ToolCall`, `SubAgentState`, `Plan`, `PlanStep`, `Question`, `Mention`, `ArtifactState` — these are used by child components and don't change. The ChatView dispatch extracts the relevant data from `UiMessage` fields and passes it as props.

**`UiMessage` sub-types renamed** (drop the `UiMessage` prefix for cleanliness):
- `UiMessageToolCallData` → `ToolCallData` (or keep as-is for Kotlin alignment)
- `UiMessagePlanData` → `PlanData`
- etc.

### 4. Bridge (jcef-bridge.ts)

**`loadSessionState`** — already sends `UiMessage[]`. The only change is the store now keeps them directly instead of converting.

**No other bridge changes needed.** The bridge functions call store actions; the store actions change internally but their signatures stay the same.

### 5. Kotlin Side

**Zero changes.** `AgentCefPanel`, `AgentLoop`, `AgentService`, `MessageStateHandler`, `UiMessage.kt` — all unchanged. The Kotlin side already creates `UiMessage` objects and persists them correctly. The bug was entirely in the JS-side conversion.

## Files Changed

| File | Change type | Estimated lines |
|---|---|---|
| `stores/chatStore.ts` | Refactor store actions, delete conversion | ~300 lines touched, ~200 deleted |
| `components/chat/ChatView.tsx` | New say/ask dispatch + tool grouping | ~80 lines changed |
| `components/chat/AgentMessage.tsx` | Simplify to pure text bubble | ~60 lines deleted |
| `bridge/types.ts` | Remove `Message`, promote `UiMessage` | ~30 lines changed |
| `bridge/jcef-bridge.ts` | Simplify `loadSessionState` handler | ~5 lines changed |
| `components/chat/TopBar.tsx` | Filter by `say` instead of `subAgent` field | ~3 lines changed |
| `__tests__/streaming-markdown.test.tsx` | Update for `UiMessage` shape | ~20 lines changed |
| `__tests__/chat-store-streaming.test.ts` | Update for `UiMessage` shape | ~15 lines changed |

**Net: ~250 lines deleted, ~150 lines changed, ~50 lines added.**

## Bugs Fixed Structurally

All bugs from the code review are eliminated because the conversion layer that caused them no longer exists:

| Bug | Severity | How it's fixed |
|---|---|---|
| C1: `stripToolCallXmlTags` strips legitimate HTML | CRITICAL | Function deleted — no stripping needed when UiMessage renders directly |
| H1: `IN_PROGRESS` plan status doesn't exist | HIGH | Plan renders from `msg.planData.status` which uses Kotlin's `PlanStatus` enum directly |
| H2: `PLAN_UPDATE` falls through to default | HIGH | Explicit `case 'PLAN_UPDATE'` in dispatch |
| H4: `ARTIFACT_RESULT` not handled | HIGH | Explicit `case 'ARTIFACT_RESULT'` in dispatch |
| M1: `resolvedTaskText` looks for wrong enum | MEDIUM | Not in webview scope (Kotlin side), but the general class of enum mismatch bugs is eliminated |
| M2: Tool chain grouping merges across turns | MEDIUM | Render-time grouping uses the persisted message order which preserves turn boundaries |
| M3: Approval data clobbers tool chain | MEDIUM | Each UiMessage carries one typed data field — no field clobbering possible |

## Testing Strategy

1. **Streaming tests** — Update existing `streaming-markdown.test.tsx` and `chat-store-streaming.test.ts` to use `UiMessage` shape. Verify: single placeholder, in-place updates, `endStream` semantics, non-streaming message reference identity.

2. **Render-time tool grouping** — New test: consecutive TOOL messages grouped, non-TOOL message breaks group, single TOOL not grouped, empty messages array.

3. **Hydration round-trip** — New test: create messages via store actions (live path), serialize to `UiMessage[]`, call `hydrateFromUiMessages()`, verify identical rendered output. This is the key regression test that proves live = resume.

4. **Manual testing** — Run a full session with tool calls, thinking, plan, completion, subagents, artifacts. Resume it. Verify every component renders identically to the live session.

## Migration

**Old sessions on disk** (`ui_messages.json` in UiMessage format) work without migration — the persisted format IS the new store format. No schema change, no migration needed.

**The only breaking change** is for any code that imports the `Message` type from `types.ts`. These are:
- `AgentMessage.tsx` (changed in this work)
- `TopBar.tsx` (changed in this work)
- `streaming-markdown.test.tsx` (changed in this work)
- `mock-data.ts` (showcase, updated)

No external consumers.

## Out of Scope

- Kotlin-side changes (none needed)
- New persistence format (stays `ui_messages.json`)
- New bridge protocol (granular calls stay for performance)
- Streaming animation changes
- New UI components or features
