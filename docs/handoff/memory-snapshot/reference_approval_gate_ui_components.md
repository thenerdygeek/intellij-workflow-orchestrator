# Approval Gate UI Components for AI Agent Chat Interfaces

Research date: 2026-03-25. Context: IntelliJ plugin with React-based chat UI (JCEF webview).

---

## 1. REACT CHAT UI LIBRARIES WITH APPROVAL PATTERNS

### 1a. assistant-ui (BEST FIT)
- **GitHub:** https://github.com/assistant-ui/assistant-ui
- **Stars:** ~9,000 | **License:** MIT | **Activity:** Very active (updated daily)
- **NPM:** @assistant-ui/react (~50K+ monthly downloads)
- **Has approval gate built-in:** YES - first-class support

**Approval Pattern:**
- `makeAssistantToolUI` with `render({ args, result, status, addResult, interrupt, resume })`
- `status.type === "requires-action"` triggers approval UI
- `addResult()` sends user decision back to the LLM
- `interrupt` / `resume` pattern for human-in-the-loop via `human()` callback in execute
- Can show tool name + args, Approve/Reject buttons, optional rejection reason input

**Key Code Pattern:**
```tsx
const ApprovalTool = makeAssistantToolUI({
  toolName: "dangerousAction",
  render: ({ args, result, status, addResult, interrupt, resume }) => {
    if (interrupt) {
      return (
        <div>
          <p>Action: {interrupt.payload.action}</p>
          <pre>{JSON.stringify(interrupt.payload.details, null, 2)}</pre>
          <button onClick={() => resume({ approved: true })}>Approve</button>
          <button onClick={() => resume({ approved: false, reason })}>Reject</button>
        </div>
      );
    }
    if (result) return <div>{result.approved ? "Approved" : "Rejected"}</div>;
    return <div>Processing...</div>;
  },
});
```

**Also provides:** makeAssistantTool (combined definition + render), Toolkit pattern for grouping tools, LangGraph runtime integration with interrupt/resume.

### 1b. tool-ui (companion to assistant-ui)
- **GitHub:** https://github.com/assistant-ui/tool-ui
- **Stars:** ~596 | **License:** MIT | **Activity:** Active
- **Has approval gate built-in:** YES - `@tool-ui/approval-card`

**Pre-built components:**
- `@tool-ui/approval-card` - Standalone approval card component
- `@tool-ui/code-block` - Code display
- `@tool-ui/data-table` - Tabular data
- `@tool-ui/plan` - Plan display
- `@tool-ui/progress-tracker` - Step tracking
- Install via: `npx shadcn@latest add @tool-ui/approval-card`
- Each component ships with Zod schema for validation

### 1c. prompt-kit
- **GitHub:** https://github.com/ibelick/prompt-kit
- **Stars:** ~2,710 | **License:** MIT | **Activity:** Active
- **Has approval gate built-in:** NO - but has building blocks

**Relevant components:**
- `ToolCall` - Displays tool call details (input, output, status, errors)
- `Steps` - Collapsible sequence of operations
- `SystemMessage` - Banner for warnings/instructions
- Built on shadcn/ui + Tailwind CSS
- Would need to BUILD approval UI using these primitives

### 1d. chatscope
- **GitHub:** https://github.com/chatscope/chat-ui-kit-react
- **Stars:** ~1,730 | **License:** MIT
- **Has approval gate built-in:** NO - generic chat components only
- Good for message bubbles, typing indicators, but no tool/approval awareness

---

## 2. AI SDK / FRAMEWORK APPROVAL PATTERNS

### 2a. Vercel AI SDK v6 (needsApproval)
- **GitHub:** https://github.com/vercel/ai
- **Stars:** ~23,000 | **License:** Apache-2.0 | **Activity:** Very active
- **Has approval gate built-in:** YES - `needsApproval` flag on tool definitions

**Pattern:**
```typescript
// Server: flag tool as needing approval
const myTool = tool({
  needsApproval: true, // or async (args) => args.amount > 1000
  execute: async (args) => { ... },
});

// Client: useChat hook provides addToolApprovalResponse
const { addToolApprovalResponse } = useChat();
// When tool part has state "approval-requested", render UI
// Call addToolApprovalResponse(approvalId, { approved: true/false })
```

**Features:**
- Conditional approval (async function checks args)
- Server-side execution only after approval
- Integrates with useChat hook
- NOT a standalone UI library - provides the protocol, you build the UI

### 2b. AG-UI Protocol + CopilotKit
- **GitHub (AG-UI):** https://github.com/ag-ui-protocol/ag-ui (~12,660 stars, MIT)
- **GitHub (CopilotKit):** https://github.com/CopilotKit/CopilotKit (~29,760 stars, MIT)
- **Has approval gate built-in:** YES - interrupt events + renderAndWait

**CopilotKit Pattern:**
```tsx
useCopilotAction({
  name: "confirmAction",
  renderAndWaitForResponse: ({ args, status, respond }) => (
    <ConfirmationDialog
      action={args.action}
      onApprove={() => respond({ approved: true })}
      onDeny={() => respond({ approved: false })}
    />
  ),
});
```

**AG-UI Protocol:** Event-driven, agent emits interrupt event on sensitive action, frontend renders approval UI, sends back approval event. Supported by Microsoft, Oracle.

### 2c. LangGraph (Backend) + assistant-ui (Frontend)
- **GitHub:** https://github.com/langchain-ai/langgraph (~27,450 stars, MIT)
- **Approval:** `interrupt()` function pauses graph, `Command` resumes

**Pattern:** LangGraph `interrupt()` -> graph pauses -> state persisted -> frontend renders approval UI -> user responds -> `Command(resume=value)` continues graph. assistant-ui has first-class LangGraph runtime support for this.

---

## 3. IDE AGENT APPROVAL PATTERNS (REFERENCE)

### 3a. Claude Code Terminal UI
- Permission system: deny > ask > allow (first match wins)
- Three modes: Normal (prompt everything), Auto-accept (edits allowed), Plan (read-only)
- "Yes, don't ask again" saves per-subcommand allow rules
- `/permissions` command to view/manage
- Compound commands decomposed into individual rules
- Config stored in settings.json `permissions` object

### 3b. Cline (VS Code Extension)
- **GitHub:** https://github.com/cline/cline (~59,300 stars, Apache-2.0)
- Every consequential action presented for approval
- `clineAsk` property in ChatView = pending approval request
- Promise resolves on Approve/Reject/Feedback
- Auto-Approve / YOLO mode for trusted operations
- gRPC-over-postMessage bridge between webview and extension
- React + TailwindCSS in webview-ui/

### 3c. Continue (VS Code/JetBrains)
- **GitHub:** https://github.com/continuedev/continue (~32,000 stars, Apache-2.0)
- Less explicit approval gates, more focused on code suggestions

---

## 4. DIFF VIEWER COMPONENTS (for code change approval)

### 4a. react-diff-viewer-continued (RECOMMENDED)
- **GitHub:** https://github.com/Aeolun/react-diff-viewer-continued
- **Stars:** ~211 | **License:** MIT | **NPM:** react-diff-viewer-continued (v4.1.2)
- Actively maintained fork of original react-diff-viewer
- Split/unified view, syntax highlighting, line numbers
- `widgets` prop for adding approve/reject buttons per hunk

### 4b. Monaco Editor Diff
- Use Monaco's built-in diff editor for full IDE-like experience
- Heavier weight but supports inline editing before approval
- Good for "modify parameters before approving" requirement

### 4c. react-diff-view
- **GitHub:** https://github.com/otakustay/react-diff-view
- Collapsed code expansion, code comments, lazy load for large diffs

---

## 5. RECOMMENDATION FOR JCEF WEBVIEW PLUGIN

### Primary: assistant-ui + tool-ui
**Why:**
1. `@tool-ui/approval-card` is a ready-made approval component
2. `makeAssistantToolUI` provides the exact pattern needed (show tool name/args, approve/deny)
3. `interrupt`/`resume` enables parameter modification before approval
4. MIT licensed, very active, 9K+ stars
5. Designed for exactly this use case (AI agent chat with tool calls)
6. Can work standalone without Vercel AI SDK backend

**What you get for free:**
- Show tool name + parameters (**yes**)
- Allow/Deny buttons (**yes**)
- "Always allow this tool" (**needs custom implementation** - not built-in, but easy to add via state)
- Modify parameters before approving (**yes** - interrupt payload is editable before resume)
- Visual diff for code changes (**no** - pair with react-diff-viewer-continued)

### For JCEF Integration:
- assistant-ui components render as standard React
- Bundle with Vite/webpack, load into JCEF webview
- Bridge approval decisions to Kotlin via JBCefJSQuery callbacks
- Store "always allow" preferences in PluginSettings

### Alternative: Build custom with prompt-kit primitives
If assistant-ui is too opinionated or heavy:
- Use prompt-kit's ToolCall + Steps components as base
- Add custom ApprovalCard component
- Pair with react-diff-viewer-continued for diffs
- More work but more control over JCEF integration specifics

### Architecture for approval flow:
```
LLM -> tool_call -> Kotlin agent loop -> bridge to React webview
  -> React renders ApprovalCard (tool name, args, diff if applicable)
  -> User clicks Approve/Deny (optionally edits args)
  -> Bridge back to Kotlin -> agent loop continues/aborts
  -> "Always allow" stored in PluginSettings.allowedTools set
```
