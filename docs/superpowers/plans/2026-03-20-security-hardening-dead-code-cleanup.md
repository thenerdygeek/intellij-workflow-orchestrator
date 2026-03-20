# Security Hardening + Dead Code Cleanup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the P0 path traversal vulnerability, 7 security concerns, and 6 dead code / unwired UI items found in the dual audit.

**Architecture:** Mostly surgical fixes — add path validation to file tools, fix approval defaults, add credential redaction, wire dead code. No new files needed except a PathValidator utility.

**Tech Stack:** Kotlin, IntelliJ Platform APIs

---

## File Structure

### New Files (1)

| File | Responsibility |
|------|---------------|
| `agent/tools/builtin/PathValidator.kt` | Centralized path validation — ensures resolved paths stay within project directory |

### Modified Files (12)

| File | Change |
|------|--------|
| `agent/tools/builtin/EditFileTool.kt` | Add path traversal check |
| `agent/tools/builtin/ReadFileTool.kt` | Add path traversal check |
| `agent/tools/builtin/SearchCodeTool.kt` | Add path traversal check |
| `agent/tools/builtin/RunCommandTool.kt` | Remove `sed` from ALLOWED_PREFIXES |
| `agent/tools/builtin/DiagnosticsTool.kt` | Add path traversal check |
| `agent/runtime/ApprovalGate.kt` | Default to Rejected when callback is null |
| `agent/runtime/SingleAgentSession.kt` | Redact sensitive content in output before returning |
| `agent/runtime/SessionTrace.kt` | Add credential redaction before writing JSONL |
| `agent/runtime/AgentEventLog.kt` | Add credential redaction before writing JSONL |
| `agent/ui/AgentController.kt` | Fix JSON parse failure to reject (not approve), wire WorkingSet, wire appendThinking |
| `agent/ui/AgentCefPanel.kt` | Remove unused sendQuery, wire appendThinking |
| `agent/context/ContextManager.kt` | Replace runBlocking with suspendCancellableCoroutine |

---

## Task 1: P0 — Path Traversal Fix

The single blocking security issue. All file tools must validate that the resolved path stays within the project directory.

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/PathValidator.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ReadFileTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SearchCodeTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/DiagnosticsTool.kt`

### PathValidator

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.tools.ToolResult
import java.io.File

/**
 * Validates that file paths resolved from LLM tool calls stay within
 * the project directory. Prevents path traversal attacks (../../etc/passwd).
 */
object PathValidator {

    /**
     * Resolve and validate a file path.
     * @param rawPath The raw path from the LLM (absolute or relative)
     * @param projectBasePath The project root directory
     * @return The canonical path if valid, or a ToolResult error if traversal detected
     */
    fun resolve(rawPath: String, projectBasePath: String): Result {
        val resolved = if (rawPath.startsWith("/")) rawPath else "$projectBasePath/$rawPath"
        val canonical = File(resolved).canonicalPath
        val projectCanonical = File(projectBasePath).canonicalPath

        return if (canonical.startsWith(projectCanonical)) {
            Result.Valid(canonical)
        } else {
            Result.Rejected(
                "Error: Path '$rawPath' resolves outside the project directory. " +
                "Resolved to: $canonical. Project root: $projectCanonical. " +
                "File operations are restricted to the project directory."
            )
        }
    }

    sealed class Result {
        data class Valid(val path: String) : Result()
        data class Rejected(val error: String) : Result()
    }

    /** Convenience: resolve and return ToolResult error if rejected. */
    fun resolveOrError(rawPath: String, projectBasePath: String): Pair<String?, ToolResult?> {
        return when (val result = resolve(rawPath, projectBasePath)) {
            is Result.Valid -> result.path to null
            is Result.Rejected -> null to ToolResult(
                result.error, "Path traversal blocked", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }
    }
}
```

### Apply to each file tool

In each tool's `execute()`, find where the path is resolved. Replace the raw resolution with PathValidator.

**EditFileTool.kt** — find:
```kotlin
val path = if (rawPath.startsWith("/")) rawPath else "${project.basePath}/$rawPath"
```
Replace with:
```kotlin
val basePath = project.basePath ?: return ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
val (path, pathError) = PathValidator.resolveOrError(rawPath, basePath)
if (pathError != null) return pathError
```

Apply the same pattern to ReadFileTool, SearchCodeTool, and DiagnosticsTool.

- [ ] **Step 1:** Create `PathValidator.kt` with resolve/resolveOrError methods
- [ ] **Step 2:** Apply PathValidator to EditFileTool
- [ ] **Step 3:** Apply PathValidator to ReadFileTool
- [ ] **Step 4:** Apply PathValidator to SearchCodeTool (for the `scope` param if it resolves to a directory)
- [ ] **Step 5:** Apply PathValidator to DiagnosticsTool
- [ ] **Step 6:** Test: verify `../../etc/passwd` is rejected, `src/Main.kt` is allowed
- [ ] **Step 7:** Commit: `security(agent): fix path traversal — validate all file paths stay within project`

---

## Task 2: Security Fixes — Approval, Redaction, Allowlist

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ApprovalGate.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SessionTrace.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentEventLog.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt`

### 2a. Null ApprovalGate callback → default to Rejected

In `ApprovalGate.kt`, find line ~49-50:
```kotlin
return onApprovalNeeded?.invoke(description, riskLevel)
    ?: ApprovalResult.Approved  // auto-approve if no callback
```
Change to:
```kotlin
return onApprovalNeeded?.invoke(description, riskLevel)
    ?: ApprovalResult.Rejected  // BLOCK if no callback (safer default)
```

### 2b. JSON parse failure → reject plan, not approve

In `AgentController.kt`, find where plan revision JSON is parsed. Change the catch fallback from `approvePlan()` to showing an error:
```kotlin
onRevise = { commentsJson ->
    try {
        val comments = Json.decodeFromString<Map<String, String>>(commentsJson)
        session?.planManager?.revisePlan(comments)
    } catch (e: Exception) {
        // DON'T approve on parse failure — show error
        dashboard.appendError("Failed to parse plan comments: ${e.message}")
    }
}
```

### 2c. OutputValidator — redact sensitive content

In `SingleAgentSession.kt`, find where OutputValidator is called (around line 346-349). Currently it logs warnings but delivers content unchanged. Change to redact:

```kotlin
val securityIssues = OutputValidator.validate(content)
if (securityIssues.isNotEmpty()) {
    LOG.warn("SingleAgentSession: output validation flagged: ${securityIssues.joinToString()}")
    // Redact sensitive patterns from the content
    var redacted = content
    // Common patterns: private keys, AWS keys, tokens
    redacted = redacted.replace(Regex("-----BEGIN [A-Z ]+ KEY-----[\\s\\S]*?-----END [A-Z ]+ KEY-----"), "[REDACTED: private key]")
    redacted = redacted.replace(Regex("AKIA[0-9A-Z]{16}"), "[REDACTED: AWS key]")
    redacted = redacted.replace(Regex("ghp_[a-zA-Z0-9]{36}"), "[REDACTED: GitHub token]")
    redacted = redacted.replace(Regex("sgp_[a-fA-F0-9]{40,}"), "[REDACTED: Sourcegraph token]")
    // Use redacted content for the response
    content = redacted
}
```

Note: `content` is a `val` — need to introduce a `var sanitizedContent` instead.

### 2d. Credential redaction in trace/event logs

Create a shared redaction utility. Add to both SessionTrace and AgentEventLog:

In `SessionTrace.kt`, add a private method:
```kotlin
private fun redactSensitive(text: String): String {
    var s = text
    s = s.replace(Regex("-----BEGIN [A-Z ]+ KEY-----[\\s\\S]{0,500}?-----END [A-Z ]+ KEY-----"), "[REDACTED:key]")
    s = s.replace(Regex("AKIA[0-9A-Z]{16}"), "[REDACTED:aws]")
    s = s.replace(Regex("ghp_[a-zA-Z0-9]{36}"), "[REDACTED:gh]")
    s = s.replace(Regex("sgp_[a-fA-F0-9]{40,}"), "[REDACTED:sg]")
    s = s.replace(Regex("(?i)password\\s*[=:]\\s*\\S+"), "password=[REDACTED]")
    s = s.replace(Regex("(?i)token\\s*[=:]\\s*['\"]?[a-zA-Z0-9_\\-]{20,}"), "token=[REDACTED]")
    return s
}
```

Apply in `append()` before writing: `val sanitized = entry.copy(detail = redactSensitive(enriched.detail ?: ""))`.

Same pattern in `AgentEventLog.kt`.

### 2e. Remove `sed` from command allowlist

In `RunCommandTool.kt`, remove `"sed"` from `ALLOWED_PREFIXES`. `sed -i` can destructively modify files, bypassing the edit approval gate.

Also remove `"awk"` for the same reason — `awk` can write to files with `>`.

- [ ] **Step 1:** Fix ApprovalGate null callback → Rejected
- [ ] **Step 2:** Fix AgentController JSON parse failure → error not approve
- [ ] **Step 3:** Add output redaction in SingleAgentSession
- [ ] **Step 4:** Add credential redaction in SessionTrace and AgentEventLog
- [ ] **Step 5:** Remove `sed` and `awk` from RunCommandTool.ALLOWED_PREFIXES
- [ ] **Step 6:** Test: verify null callback rejects, verify private key content is redacted
- [ ] **Step 7:** Commit: `security(agent): fix approval defaults, redact credentials, remove sed from allowlist`

---

## Task 3: Dead Code Cleanup — Wire or Remove

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`

### 3a. Wire WorkingSet into tool execution

In `SingleAgentSession.processLlmSuccess()`, after successful tool execution, update the working set on the session:

```kotlin
// After tool execution success, track in working set
if (toolName == "read_file" && !toolResult.isError) {
    // Extract path from tool args
    val filePath = params["path"]?.jsonPrimitive?.content
    if (filePath != null) {
        // Access working set via callback or directly
        // For now, just track via artifacts
    }
}
```

Actually, the simplest approach: the `ConversationSession.workingSet` is accessible from the controller. Track files from the `ToolCallInfo` in `handleProgress()`:

In `AgentController.handleProgress()`, when a tool call completes:
```kotlin
if (toolInfo != null && !toolInfo.isError) {
    val path = toolInfo.editFilePath ?: toolInfo.args.let {
        Regex(""""path"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1)
    }
    if (path != null) {
        when {
            toolInfo.toolName.contains("read") -> session?.workingSet?.recordRead(path, 0)
            toolInfo.toolName.contains("edit") -> session?.workingSet?.recordEdit(path)
        }
    }
}
```

### 3b. Wire EditApprovalDialog for file edit approvals

**DO NOT remove EditApprovalDialog — wire it.** It's an 80-line component with IntelliJ DiffManager side-by-side diff. Currently the edit approval shows a plain Yes/No/AllowAll dialog with no diff preview.

The fix: in `SingleAgentSession.processLlmSuccess()`, when the approval gate is about to fire for `edit_file`, extract `path`, `old_string`, `new_string` from the tool call arguments. Pass these through the approval callback so the controller can show EditApprovalDialog.

In `AgentController.showApprovalDialog()`, detect edit_file and use EditApprovalDialog:
```kotlin
if (description.contains("edit_file") && riskLevel == RiskLevel.MEDIUM) {
    // Parse tool args from description to extract path, old/new strings
    // Show EditApprovalDialog with DiffManager
    val dialog = EditApprovalDialog(project, filePath, originalContent, proposedContent, description)
    dialog.show()
    result = if (dialog.approved) ApprovalResult.Approved else ApprovalResult.Rejected
}
```

The challenge: the approval callback only receives `description: String` and `riskLevel`. To pass file content, either:
- Encode the path/old/new in the description string (parse it out)
- Or extend ApprovalGate to pass tool args

Simplest approach: when the description contains `edit_file`, read the file from disk (we have the path), and construct the proposed content by applying the edit. Then show EditApprovalDialog with real before/after content.

### 3c. Remove unused sendQuery in AgentCefPanel

In `AgentCefPanel.kt`, find where `sendQuery` is created (around line 96). It's allocated, has a handler for `onUserMessage`, but is never injected into JS. Remove the creation and disposal since input is handled by Swing.

### 3d. Remove legacy compat methods in AgentDashboardPanel

Remove `getRichPanel()`, `getStreamingPanel()`, `getPlanRenderer()` — they create detached instances and are never called.

### 3e. Fix History resume with no controller

In `HistoryTabProvider.kt`, add user feedback when controller is null:
```kotlin
panel.onResumeSession = { sessionId ->
    try {
        val controller = AgentService.getInstance(project).activeController
        if (controller != null) {
            controller.resumeSession(sessionId)
            // switch to Agent tab...
        } else {
            // Show notification: open Agent tab first
            NotificationGroupManager.getInstance()
                .getNotificationGroup("workflow.agent")
                .createNotification("Open the Agent tab first, then try Resume again.", NotificationType.INFORMATION)
                .notify(project)
        }
    } catch (e: Exception) { /* ... */ }
}
```

### 3e. Wire appendThinking (optional)

If the Sourcegraph API ever returns thinking/reasoning tokens, they should be displayed. For now, the API doesn't support extended thinking, so `appendThinking()` is correct to leave unwired. Add a comment explaining this:

In `AgentController.handleProgress()`, add:
```kotlin
// Note: appendThinking() exists but is not wired because Sourcegraph's
// chat completions API does not return thinking/reasoning tokens.
// When/if extended thinking is supported, wire it here.
```

- [ ] **Step 1:** Wire WorkingSet.recordRead/recordEdit from handleProgress
- [ ] **Step 2:** Remove unused sendQuery from AgentCefPanel
- [ ] **Step 3:** Remove legacy getRichPanel/getStreamingPanel/getPlanRenderer
- [ ] **Step 4:** Fix History resume when controller is null (show notification)
- [ ] **Step 5:** Add comment explaining appendThinking is intentionally unwired
- [ ] **Step 6:** Run `./gradlew :agent:compileKotlin && ./gradlew :agent:test --rerun --no-build-cache`
- [ ] **Step 7:** Commit: `refactor(agent): wire WorkingSet, remove dead code, fix History resume fallback`

---

## Task 4: Replace runBlocking in ContextManager

The `runBlocking` in `ContextManager.summarizeMessages()` is called from `compress()` which is called from `addMessage()`. This creates nested blocking inside a coroutine context.

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt`

### Fix

Make `compress()` and `summarizeMessages()` suspend functions. This requires making `addMessage()` a suspend function too, which propagates up to callers.

**Actually, the simpler fix:** Don't use LLM summarization. The `brain` parameter is never passed to ContextManager anyway (it's always `null` in the current code — check `AgentOrchestrator.kt`). The `runBlocking` path only executes when `brain != null`, which never happens.

So the real fix is just removing the dead code:

```kotlin
private fun summarizeMessages(messagesToSummarize: List<ChatMessage>): String {
    // LLM-powered summarization removed — brain is never passed to ContextManager.
    // The default truncation summarizer is sufficient and avoids runBlocking.
    return summarizer(messagesToSummarize)
}
```

Remove the `brain` parameter from the constructor (or deprecate it). Remove the `runBlocking` import.

- [ ] **Step 1:** Verify brain is never passed to ContextManager (grep for `brain =` in ContextManager construction)
- [ ] **Step 2:** Remove the LLM summarization branch in `summarizeMessages()`
- [ ] **Step 3:** Remove `runBlocking` import
- [ ] **Step 4:** Keep `brain` constructor param for backward compat but ignore it
- [ ] **Step 5:** Commit: `refactor(agent): remove runBlocking from ContextManager — LLM summarization was dead code`

---

## Verification

```bash
./gradlew :agent:compileKotlin
./gradlew :agent:test --rerun --no-build-cache
```

Expected: all tests pass, no compilation errors.

Manual verification:
1. `edit_file(path="../../etc/passwd")` → rejected with "resolves outside project directory"
2. LLM output containing `-----BEGIN RSA PRIVATE KEY-----` → redacted before showing to user
3. Trace JSONL files don't contain raw credentials
4. Approval dialog with null callback → action rejected (not approved)
