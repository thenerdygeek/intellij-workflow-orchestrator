# Approval Gate Enhancements — Design Spec

**Date:** 2026-03-25
**Module:** `:agent` (Kotlin runtime + React webview)
**Branch:** `feature/phase-3-agentic-ai`

## Problem

The approval gate shows minimal context — just tool name and risk level. Users can't see *what* the tool is about to do (e.g., what edit, what command). There's also no way to allow a tool for the rest of the session without approving every single call.

## Fix 1: Rich Tool Context in Approval Card

### Current Bridge
```
showApproval(title: String, description: String, commandPreview: String)
```

### New Bridge
```
showApproval(
  toolName: String,
  riskLevel: String,
  description: String,
  metadata: List<MetadataItem>   // JSON array of {key, value} pairs
)
```

### Kotlin — Rich Metadata Builder (AgentController.approvalCallback)

Build human-readable metadata per tool type from the `params: Map<String, Any?>`:

| Tool | Metadata |
|------|----------|
| `edit_file` | File: path, Old (first 3 lines of old_string), New (first 3 lines of new_string) |
| `run_command` | Command: full command, Directory: working dir |
| `search_code` | Pattern: regex, Scope: path |
| `read_file` | File: path, Lines: from-to |
| `glob_files` | Pattern: glob, Path: directory |
| `git_diff` / `git_log` / `git_*` | Operation: git subcommand + key args |
| `refactor_rename` | Old: old name, New: new name, Scope: path |
| `jira_transition` | Ticket: key, Transition: target status |
| `jira_comment` | Ticket: key, Comment: first 50 chars |
| `bamboo_trigger_build` | Plan: key |
| `bitbucket_create_pr` | Title: pr title, From: source, To: target |
| `bitbucket_merge_pr` | PR: #id, Strategy: merge strategy |
| All others | Each param as key:value (truncated at 100 chars per value) |

Values are truncated to keep the card compact — full args are still visible in the expandable ToolCallView.

### React — Enhanced ApprovalView

`ApprovalView` receives the metadata array directly and passes to `ApprovalCard`. No changes to `ApprovalCard` itself — it already supports `metadata: MetadataItem[]`.

### Bridge Changes

**Kotlin → JS:**
- `showApproval` changes from 3 string params to: `showApproval(toolName, riskLevel, description, metadataJson)`
- `metadataJson` is a JSON string: `[{"key":"File","value":"/path"},{"key":"Command","value":"npm test"}]`

**JS side:**
- `chatStore.showApproval` updated to parse metadata
- `PendingApproval` type gains `toolName`, `riskLevel`, `metadata` fields

## Fix 2: "Allow for This Session" Button

### React — Third Button

Add between Deny and Approve in `ApprovalCard`:

```
[Deny]  [Allow for session]  [Approve]
```

- "Allow for session" = approve this call AND skip future approvals for this tool name
- Styled as outline/secondary (between ghost Deny and solid Approve)

### Kotlin — Session Allowed Tools

`ApprovalGate`:
- New field: `private val sessionAllowedTools = mutableSetOf<String>()`
- In `check()`: if `toolName in sessionAllowedTools`, return `Approved` immediately (before showing UI)
- New method: `fun allowToolForSession(toolName: String)` — adds to set
- Set cleared in `reset()` (called when session ends)

### Bridge

**JS → Kotlin:**
- New bridge function: `allowToolForSession(toolName: String)` — calls `approvalGate.allowToolForSession(toolName)` + resolves the pending approval as Approved

**React:**
- `chatStore.resolveApproval` gains a third option: `allowForSession`
- When "Allow for session" clicked: calls `kotlinBridge.allowToolForSession(toolName)` then resolves approval

## Files Changed

| File | Change |
|------|--------|
| **Kotlin** | |
| `agent/runtime/ApprovalGate.kt` | Add `sessionAllowedTools` set, check in `check()`, add `allowToolForSession()` |
| `agent/ui/AgentController.kt` | Build rich metadata in `approvalCallback`, wire `allowToolForSession` |
| `agent/ui/AgentCefPanel.kt` | Update `showApproval` signature, add `_allowToolForSession` bridge |
| `agent/ui/AgentDashboardPanel.kt` | Update `showApproval` passthrough |
| **React** | |
| `stores/chatStore.ts` | Update `PendingApproval` type, update `showApproval`/`resolveApproval` |
| `bridge/jcef-bridge.ts` | Update `showApproval` handler, add `allowToolForSession` to `kotlinBridge` |
| `bridge/globals.d.ts` | Add `_allowToolForSession` declaration |
| `components/agent/ApprovalView.tsx` | Accept metadata + toolName, pass to card, wire session button |
| `components/ui/tool-ui/approval-card.tsx` | Add "Allow for session" button |
| `components/chat/ChatView.tsx` | Pass new fields to ApprovalView |
