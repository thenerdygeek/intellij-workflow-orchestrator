# Phase 3 v3: Architecture Refactor — Single Agent Default

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement.

**Goal:** Refactor the `:agent` module from multi-worker default to single-agent-with-tools default, add streaming, structured output, dynamic prompts, loop guards, auto-verification, and LLM-powered summarization. Based on research at `~/Documents/Agentic_AI_Plugin_Architecture_Research_20260317/research_phase3_architecture_review_summary.md`.

**Architecture:** Single ReAct loop with all 16 tools as default mode. Orchestrated (plan + step-by-step) as opt-in for complex tasks. LLM IS the orchestrator — no separate orchestrator process for default mode. Enterprise Cody (150K input, 64K output) assumed.

**Key Principle:** The code we built in v2 is NOT thrown away. Tools, security, context manager, checkpoint, UI all stay. What changes is the execution mode and the quality of the agent loop.

---

## Task 1: Single Agent Mode — New Default Execution Path

**Files:**
- Create: `agent/src/.../runtime/SingleAgentSession.kt`
- Create: `agent/src/.../runtime/AgentMode.kt`
- Modify: `agent/src/.../orchestrator/AgentOrchestrator.kt`
- Modify: `agent/src/.../orchestrator/OrchestratorPrompts.kt`
- Create: `agent/src/.../runtime/BudgetEnforcer.kt`
- Test: `agent/src/test/.../runtime/SingleAgentSessionTest.kt`
- Test: `agent/src/test/.../runtime/BudgetEnforcerTest.kt`

**What:** A `SingleAgentSession` that has ALL tools available. The LLM decides whether to analyze, code, review, or call enterprise tools — all in one conversation. `BudgetEnforcer` monitors token usage and triggers compression at 40%.

`AgentOrchestrator.executeTask()` changes:
1. Default: create `SingleAgentSession` with all tools → run ReAct loop → return result
2. Only if user explicitly requests a plan OR auto-escalation triggers: use orchestrated mode (existing TaskGraph flow)

The existing `WorkerSession` stays but is used ONLY in orchestrated mode for individual steps.

---

## Task 2: Dynamic System Prompt Assembly

**Files:**
- Modify: `agent/src/.../orchestrator/OrchestratorPrompts.kt`
- Create: `agent/src/.../orchestrator/PromptAssembler.kt`
- Test: `agent/src/test/.../orchestrator/PromptAssemblerTest.kt`

**What:** Replace static system prompts with composable assembly:
1. Core identity (~500 tokens): "You are an AI coding assistant for IntelliJ..."
2. Tool summary (~200 tokens): dynamically generated from registered tools
3. Project context (~300 tokens): framework, module structure, Spring version
4. Environment info (~100 tokens): OS, JDK, project path
5. Previous step results (~500 tokens): only in orchestrated mode
6. Constraints and rules (~300 tokens): read-before-edit, verify-after-edit, security rules

Total: ~1,900 tokens dynamic prompt vs 1,500 tokens static. Better targeting, same budget.

---

## Task 3: Streaming Response Support

**Files:**
- Create: `agent/src/.../api/dto/StreamModels.kt`
- Modify: `agent/src/.../api/SourcegraphChatClient.kt` — add `sendMessageStream()`
- Modify: `agent/src/.../brain/LlmBrain.kt` — add `chatStream()`
- Modify: `agent/src/.../brain/OpenAiCompatBrain.kt` — implement streaming
- Test: `agent/src/test/.../api/SourcegraphChatClientStreamTest.kt`

**What:** Add `stream: true` support. Parse SSE events (`data: {...}` lines). Return `Flow<StreamChunk>` where each chunk has delta content or tool call fragments. The UI receives partial text in real-time. Tool calls are collected until complete, then executed.

---

## Task 4: Structured Output — Tool Forcing for Routing/Planning

**Files:**
- Modify: `agent/src/.../runtime/ComplexityRouter.kt`
- Modify: `agent/src/.../orchestrator/AgentOrchestrator.kt` (plan creation)
- Modify: `agent/src/.../api/dto/ChatCompletionModels.kt` — add `tool_choice` object format
- Test: `agent/src/test/.../runtime/ComplexityRouterTest.kt` (update)

**What:**
- ComplexityRouter: define `classify_task` tool with `complexity: enum["SIMPLE","COMPLEX"]`, use `tool_choice: {"type":"function","function":{"name":"classify_task"}}`
- Plan creation: define `create_plan` tool with task array schema
- No more regex extraction or string parsing

---

## Task 5: Agent Loop Guards

**Files:**
- Modify: `agent/src/.../runtime/SingleAgentSession.kt` (or WorkerSession)
- Create: `agent/src/.../runtime/LoopGuard.kt`
- Test: `agent/src/test/.../runtime/LoopGuardTest.kt`

**What:**
1. **Loop detection:** Track last 3 tool calls (name + args hash). If same call 3x → inject redirect message
2. **Instruction-fade reminders:** Every 3-4 iterations, inject brief rules reminder (~50 tokens)
3. **Error nudge:** After `isError` tool result, inject "Address this error before proceeding"
4. **Auto-verification:** After edit tool calls with no more pending tool calls, inject "Run diagnostics on modified files before completing"
5. **Budget gate:** If >40% utilization after compression attempt → signal orchestration needed

---

## Task 6: WorkerResult.isError Fix + Better Error Messages

**Files:**
- Modify: `agent/src/.../runtime/WorkerSession.kt` — add `isError` to WorkerResult
- Modify: `agent/src/.../orchestrator/AgentOrchestrator.kt` — use `isError` instead of `startsWith("Error:")`
- Modify: all tools returning errors — include available tool list in "tool not found" messages

---

## Task 7: LLM-Powered Summarization

**Files:**
- Modify: `agent/src/.../context/ContextManager.kt`
- Test: `agent/src/test/.../context/ContextManagerTest.kt` (update)

**What:** Replace `content.take(500) + "..."` default summarizer with an LLM call:
```kotlin
summarizer = { messages ->
    val prompt = "Summarize the key findings and decisions from this conversation in under 200 tokens."
    val result = brain.chat(listOf(ChatMessage("system", prompt), ChatMessage("user", messages.joinToString("\n") { it.content ?: "" })))
    // extract summary from response
}
```
One extra LLM call per compression event. Only triggers when approaching budget.

---

## Task 8: Spring Context Tools

**Files:**
- Create: `agent/src/.../tools/psi/SpringContextTool.kt`
- Create: `agent/src/.../tools/psi/SpringEndpointsTool.kt`
- Create: `agent/src/.../tools/psi/SpringBeanGraphTool.kt`
- Test: `agent/src/test/.../tools/psi/SpringContextToolTest.kt`

**What:** Leverage IntelliJ Ultimate's Spring APIs:
- `spring_context` — list all beans with types, scopes, injection points
- `spring_endpoints` — list all HTTP endpoints with paths, methods, handler classes
- `spring_bean_graph` — show injection dependencies for a specific bean
- Uses `SpringManager.getInstance(project).getCombinedModel(project)`

---

## Task 9: Approval Dialogs + Diff Preview

**Files:**
- Create: `agent/src/.../ui/ApprovalDialog.kt` — DialogWrapper with DiffManager
- Create: `agent/src/.../ui/CommandApprovalDialog.kt` — for shell commands
- Create: `agent/src/.../runtime/ApprovalGate.kt`
- Modify: `agent/src/.../tools/builtin/EditFileTool.kt`
- Modify: `agent/src/.../tools/builtin/RunCommandTool.kt`

**What:** Risk-based approval:
- Read-only tools: auto-approve
- File edits: show diff via `DiffManager`, Accept/Reject
- Shell commands: show command + risk assessment, Accept/Reject
- Destructive actions: typed confirmation

---

## Task 10: Plan Display with Markdown + Progress UI

**Files:**
- Modify: `agent/src/.../ui/AgentDashboardPanel.kt`
- Create: `agent/src/.../ui/PlanMarkdownRenderer.kt`
- Create: `agent/src/.../ui/StreamingOutputPanel.kt`

**What:**
- Plan displayed as rendered markdown via `JBCefBrowser` (JCEF)
- Streaming output panel shows LLM reasoning token-by-token
- Step progress with status icons (pending/running/done/failed)
- Real-time token budget visualization

---

## Task 11: Checkpoint Resume on IDE Startup

**Files:**
- Create: `agent/src/.../listeners/AgentStartupActivity.kt`
- Modify: `agent/src/.../runtime/CheckpointStore.kt`
- Modify: plugin.xml — register startup activity

**What:** On IDE open, check for `.workflow/agent/checkpoint-*.json`. If found, show `EditorNotificationPanel`: "Agent task was interrupted. Resume or Discard?"

---

## Task 12: Cost Tracking + Budget Enforcement

**Files:**
- Create: `agent/src/.../runtime/TokenUsageTracker.kt`
- Modify: `agent/src/.../settings/AgentSettings.kt` — add usage persistence
- Modify: `agent/src/.../ui/TokenBudgetWidget.kt` — wire to real data

**What:** Pre-task estimate, live tracking during execution, per-task cost summary, daily usage dashboard.

---

## Task 13: Final Integration + Tests

- Wire everything together
- Run full test suite
- Verify in runIde
- Code review checkpoint
- Commit
