# Plan Mode / Plan-Then-Execute: Mechanical Enforcement Comparison

**Date:** 2026-04-04
**Agents analyzed:** Claude Code, Cline, Aider, Codex CLI, Cursor (inferred)

---

## Executive Summary

Five distinct enforcement architectures exist across these tools:

| Agent | Enforcement Type | Tools Available in Plan | Approval Flow | Same LLM Call? |
|---|---|---|---|---|
| **Claude Code** | Tool schema filtering + server-side | Read-only tools only | Plan file editor + accept/reject modal | Same conversation, different tool schema |
| **Cline** | Execution guard (runtime block) | All tools sent, 4 blocked at execution | UI "Approve" on `plan_mode_respond` tool | Same conversation, mode toggleable |
| **Aider** | Separate LLM pipeline (architect->editor) | No tools (text-only architect) | User confirm_ask("Edit the files?") | Two separate LLM calls |
| **Codex CLI** | Prompt engineering + `<proposed_plan>` XML | All tools available, model told to plan | "Implement this plan?" selection popup | Same conversation, mode switch |
| **Cursor** | Task-based sub-agents (inferred) | Planner has no write tools | Background planner suggests, worker executes | Separate LLM calls |

---

## 1. Claude Code

### Source: Closed-source binary, behavior documented in CHANGELOG.md

**How the plan is presented:**
- User enters plan mode via `/plan` command (optionally with description: `/plan fix the auth bug`)
- Claude writes a plan to a plan file that the user can view and edit
- Plan is displayed in a dedicated editor/viewer in the TUI

**How the user approves/rejects/revises:**
- After plan generation, a plan-approval modal appears
- User can accept (starts implementation), reject (stays in plan mode), or provide feedback
- Multi-line text entry for feedback (backslash+Enter, Shift+Enter for newlines)
- Option to clear context on accept (hidden by default, enabled with `showClearContextOnPlanAccept: true`)

**Mechanical enforcement:**
- **Tool schema filtering**: Plan-mode tools are a restricted subset. The `AskUserQuestion` tool and other plan-mode tools are disabled when `--channels` is active (CHANGELOG v2.1.84)
- **Server-side enforcement**: Plan mode was "lost after context compaction" (bug fix in CHANGELOG), indicating the mode state is tracked and used to filter tool schemas server-side
- **Proactive ticks disabled**: "Fixed proactive ticks firing while in plan mode" -- the agent loop behaves differently

**Chat during pending plan:** Yes, the plan file editor allows feedback/revision.

**After revision:** Incremental -- the plan file is edited, not regenerated from scratch.

**Separate LLM call?** Same conversation, but with a different tool schema applied to the LLM request. The mode changes what tools are available, not which model is called.

---

## 2. Cline

### Source: `cline/src/core/task/ToolExecutor.ts`, `cline/src/core/prompts/responses.ts`, `cline/src/shared/tools.ts`

**How the plan is presented:**
- Cline has a toggle between "Plan" and "Act" modes
- In plan mode, the LLM gathers information (can use `read_file`, `ask_followup_question`) then uses the `plan_mode_respond` tool to present the plan
- Plan appears as conversational text in the chat UI

**How the user approves/rejects/revises:**
- The `plan_mode_respond` tool creates an `ask` event of type `"plan_mode_respond"` that shows the plan and waits for user response
- User can type feedback, approve, or switch to Act mode via a UI toggle button
- Switching to Act mode tells the LLM to start executing

**Mechanical enforcement -- EXECUTION GUARD:**
```typescript
// cline/src/core/task/ToolExecutor.ts:291-296
private static readonly PLAN_MODE_RESTRICTED_TOOLS: ClineDefaultTool[] = [
    ClineDefaultTool.FILE_NEW,      // write_to_file
    ClineDefaultTool.FILE_EDIT,     // editedExistingFile  
    ClineDefaultTool.NEW_RULE,      // new rule creation
    ClineDefaultTool.APPLY_PATCH,   // apply_patch
]
```

At execution time (not schema time), if the model tries to use a restricted tool:
```typescript
// cline/src/core/task/ToolExecutor.ts:342-357
if (
    this.stateManager.getGlobalSettingsKey("strictPlanModeEnabled") &&
    this.stateManager.getGlobalSettingsKey("mode") === "plan" &&
    block.name &&
    this.isPlanModeToolRestricted(block.name)
) {
    const errorMessage = `Tool '${block.name}' is not available in PLAN MODE. ...`
    await this.say("error", errorMessage)
    if (!block.partial) {
        this.pushToolResult(formatResponse.toolError(errorMessage), block)
    }
    return true
}
```

Key detail: This is **only active when `strictPlanModeEnabled` is true** in settings. Without it, the model CAN use write tools in plan mode.

**Prompt-level enforcement:**
```typescript
// cline/src/core/prompts/responses.ts:260-262
planModeInstructions: () => {
    return `In this mode you should focus on information gathering, asking questions, 
    and architecting a solution. Once you have a plan, use the plan_mode_respond tool...`
}
```

**Chat during pending plan:** Yes, fully interactive. The `plan_mode_respond` tool result includes user's response.

**After revision:** Same conversation, incremental. The model sees the feedback and responds.

**Separate LLM call?** Same conversation, but Cline supports **separate models per mode**:
```typescript
// cline/src/core/api/index.ts:87
apiModelId: mode === "plan" ? options.planModeApiModelId : options.actModeApiModelId
```
The user can configure a different (potentially cheaper/smarter) model for planning vs. execution.

---

## 3. Aider (Architect Mode)

### Source: `aider/aider/coders/architect_coder.py`, `aider/aider/coders/architect_prompts.py`

**How the plan is presented:**
- Aider's "architect mode" uses a two-LLM pipeline
- The ArchitectCoder (extends AskCoder) uses a "planning" LLM with this system prompt:
```python
# aider/aider/coders/architect_prompts.py:7-13
main_system = """Act as an expert architect engineer and provide direction 
to your editor engineer.
Study the change request and the current code.
Describe how to modify the code to complete the request.
The editor engineer will rely solely on your instructions..."""
```
- The architect LLM produces text-only output (NO tools, NO edit format)

**How the user approves/rejects/revises:**
```python
# aider/aider/coders/architect_coder.py:17
if not self.auto_accept_architect and not self.io.confirm_ask("Edit the files?"):
    return
```
Simple terminal yes/no prompt: "Edit the files?" If the user says no, the plan is discarded.

**Mechanical enforcement -- SEPARATE LLM PIPELINE:**
- The architect LLM has NO edit tools. It inherits from `AskCoder` which produces text-only responses
- If approved, a **completely new editor Coder** is created:
```python
# aider/aider/coders/architect_coder.py:23-38
editor_model = self.main_model.editor_model or self.main_model
kwargs["main_model"] = editor_model
kwargs["edit_format"] = self.main_model.editor_edit_format
kwargs["map_tokens"] = 0
kwargs["cache_prompts"] = False
editor_coder = Coder.create(**new_kwargs)
editor_coder.cur_messages = []
editor_coder.done_messages = []
editor_coder.run(with_message=content, preproc=False)
```

The enforcement is **architectural** -- the architect model physically cannot make edits because:
1. It uses `AskCoder` which has no edit capabilities
2. A separate Coder instance with a potentially different model handles the actual edits
3. The editor Coder gets fresh message history (only the architect's output)

**Chat during pending plan:** No. It's a blocking yes/no prompt.

**After revision:** Full restart. The user must submit a new request.

**Separate LLM call?** YES -- completely separate. The architect and editor can be different models. The editor sees only the architect's instructions, not the conversation history.

**Auto-accept option:** `--auto-accept-architect` flag skips the confirmation, making it fully automatic (architect -> editor pipeline with no human gate).

---

## 4. Codex CLI (OpenAI)

### Source: `codex/codex-rs/utils/stream-parser/src/proposed_plan.rs`, `codex/codex-rs/core/src/codex.rs`, `codex/codex-rs/tui/src/chatwidget.rs`, `codex/codex-rs/protocol/src/config_types.rs`

**How the plan is presented:**
- Codex has `ModeKind::Plan` and `ModeKind::Default` modes, togglable via keyboard shortcut
- In plan mode, the model wraps its plan in `<proposed_plan>...</proposed_plan>` XML tags
- The TUI parses these tags in a streaming parser (`ProposedPlanParser`) and renders the plan content in a styled panel (separate from normal text)
- Normal assistant text outside the tags is also shown

**How the user approves/rejects/revises:**
- After a plan turn completes (detected by `saw_plan_item_this_turn` flag), the TUI shows a selection popup:
```rust
// codex/codex-rs/tui/src/chatwidget.rs:244-247
const PLAN_IMPLEMENTATION_TITLE: &str = "Implement this plan?";
const PLAN_IMPLEMENTATION_YES: &str = "Yes, implement this plan";
const PLAN_IMPLEMENTATION_NO: &str = "No, stay in Plan mode";
```
- "Yes" switches to Default mode and sends "Implement the plan." as user message
- "No" stays in Plan mode for further discussion

**Mechanical enforcement -- PROMPT ENGINEERING + COLLABORATION MODE:**
- Codex CLI does NOT filter tool schemas for plan mode. `ToolsConfig` has no plan mode flag
- The `CollaborationMode` carries `developer_instructions` that can be set per-mode (plan mode gets different instructions)
- The model is told via instructions to produce `<proposed_plan>` blocks instead of using tools
- The `update_plan` tool (a TODO/checklist tool) is **explicitly blocked** in plan mode at the execution level:
```rust
// codex/codex-rs/core/src/tools/handlers/plan.rs:86-89
if turn_context.collaboration_mode.mode == ModeKind::Plan {
    return Err(FunctionCallError::RespondToModel(
        "update_plan is a TODO/checklist tool and is not allowed in Plan mode".to_string(),
    ));
}
```
- All other tools (shell, file writes, etc.) remain technically available -- the model just isn't supposed to use them based on instructions

**Chat during pending plan:** Yes. The user can type messages while in Plan mode.

**After revision:** Incremental -- same conversation, user provides feedback, model revises plan.

**Separate LLM call?** Same conversation, same model. But plan mode can have a **different reasoning effort** level:
```rust
// codex/codex-rs/tui/src/chatwidget.rs:8210
self.config.plan_mode_reasoning_effort
```

---

## 5. Cursor (Inferred from architecture research)

### Source: Previous research in `docs/research/` (no public source code available)

**How the plan is presented:**
- Pre-2.4: Single ReAct loop, no formal plan mode
- Post-2.4: Task-based sub-agents. A background planner agent creates the plan
- The planner uses `todo_write` to record planned steps
- Plans shown in a "Plan" panel in the IDE

**How the user approves/rejects/revises:**
- In Agent mode: each tool use requires approval (like Cline's act mode)
- In background agent mode: up to 8 parallel agents execute, each with its own approval queue

**Mechanical enforcement:**
- Planner agent has read-only tools (no write capabilities)
- Worker agents have write tools but require user approval per action
- `multi_tool_use.parallel` for batch execution
- System prompt: "keep searching until CONFIDENT" prevents premature action

**Separate LLM call?** Yes -- hierarchical planner/worker/judge pattern at scale.

---

## Comparative Analysis: Enforcement Mechanisms

### Mechanism 1: Tool Schema Filtering (Claude Code)
**Strongest enforcement.** The LLM physically cannot call tools that aren't in its schema. The model never even sees the write tools during plan mode.

### Mechanism 2: Execution Guard (Cline)  
**Runtime enforcement.** The LLM can request restricted tools, but execution is blocked with an error message fed back to the model. Weaker than schema filtering because:
- The model wastes tokens attempting blocked tool calls
- Requires `strictPlanModeEnabled` to be on (opt-in)
- Error feedback loop may confuse some models

### Mechanism 3: Separate LLM Pipeline (Aider)
**Architectural enforcement.** The planning and execution are separate Coder instances with separate models and separate capabilities. The architect physically cannot edit because it uses AskCoder. Strongest isolation but no conversation continuity.

### Mechanism 4: Prompt Engineering + XML Tags (Codex CLI)
**Weakest enforcement.** The model is instructed to plan via `developer_instructions` and produce `<proposed_plan>` XML blocks. Tools remain available. Only the `update_plan` checklist tool is blocked. A sufficiently "rebellious" model could still call shell/file tools in plan mode.

### Our Implementation Comparison (from CLAUDE.md)
Our plugin uses **two-layer enforcement** (schema filtering + execution guard), which is the most robust approach:
1. **Schema filtering**: Write tools removed from LLM's tool schema before the call
2. **Execution guard**: Safety net that blocks meta-tool write actions at execution time

This matches Claude Code's approach (schema filtering) plus Cline's approach (execution guard) as a fallback, making it the most robust of all five.

---

## Key Design Decisions for Our Plugin

### What we do right:
- Two-layer enforcement (schema filtering + execution guard) = most robust
- Plan mode as a toggle (like Cline/Codex), not a separate pipeline (like Aider)
- Read/analysis tools remain available during planning

### What we could adopt:
1. **From Cline**: Separate models for plan vs. act mode (cost optimization)
2. **From Codex CLI**: `<proposed_plan>` XML tags for structured plan output that can be parsed and rendered differently from normal text
3. **From Aider**: Option for auto-accept (skip approval for automated workflows)
4. **From Codex CLI**: Per-mode reasoning effort configuration
5. **From Claude Code**: Plan file editor for user revision (instead of just chat-based revision)

### Anti-patterns to avoid:
1. **Codex CLI's prompt-only enforcement**: Too weak, relies entirely on model compliance
2. **Cline's opt-in strict mode**: Should be strict by default in plan mode
3. **Aider's no-revision flow**: Blocking yes/no with no ability to provide feedback before decision

---

## File References

| Agent | Key Files |
|---|---|
| Claude Code | Closed source; `CHANGELOG.md` for behavior documentation |
| Cline | `src/core/task/ToolExecutor.ts:291-357` (enforcement), `src/core/prompts/responses.ts:260` (instructions), `src/shared/tools.ts:23` (PLAN_MODE tool), `src/core/api/index.ts:87` (per-mode model) |
| Aider | `aider/coders/architect_coder.py` (full pipeline), `aider/coders/architect_prompts.py` (system prompt) |
| Codex CLI | `codex-rs/utils/stream-parser/src/proposed_plan.rs` (XML parser), `codex-rs/protocol/src/config_types.rs:390` (ModeKind enum), `codex-rs/core/src/tools/handlers/plan.rs:86` (update_plan block), `codex-rs/tui/src/chatwidget.rs:2403` (approval popup) |
