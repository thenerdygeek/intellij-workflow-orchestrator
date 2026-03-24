# Anti-Hallucination Context Management Overhaul

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the 3 critical hallucination vectors in the agent's context management by adopting proven strategies from Claude Code and OpenCode — rich pruning placeholders, a meaningful fallback summarizer, compression boundary markers, smart pruning strategies, raised thresholds, and a compression-aware system prompt.

**Architecture:** The changes are concentrated in 4 files: `ContextManager.kt` (core compression logic), `BudgetEnforcer.kt` (threshold tuning), `LoopGuard.kt` (re-read tracking after pruning), and `PromptAssembler.kt` (compression-aware prompt). A new `SmartPruner.kt` encapsulates deduplication, error purging, and write superseding. All changes are backward-compatible — no API/interface changes, no new module dependencies.

**Tech Stack:** Kotlin, JUnit 5, MockK, coroutines (`runTest`)

---

## File Structure

| Action | File | Responsibility | Tasks |
|--------|------|---------------|-------|
| Modify | `agent/src/main/kotlin/.../context/ContextManager.kt` (406 lines) | Rich placeholders, improved summarizer, compression markers, tiered pruning, protected tools, min savings, facts anchor | 2,3,4,7,8,10,11,12 |
| Modify | `agent/src/main/kotlin/.../runtime/BudgetEnforcer.kt` (82 lines) | Raise COMPRESS threshold from 60% → 80% | 1 |
| Modify | `agent/src/main/kotlin/.../runtime/LoopGuard.kt` (144 lines) | Clear re-read tracking after pruning events | 7 |
| Modify | `agent/src/main/kotlin/.../orchestrator/PromptAssembler.kt` (414 lines) | Compression-aware context management rules, facts system prompt | 5,13 |
| Modify | `agent/src/main/kotlin/.../runtime/SingleAgentSession.kt` (928 lines) | Wire pruning to LoopGuard, cap system warnings, orphan protection, fact recording | 7,12,13 |
| Create | `agent/src/main/kotlin/.../context/SmartPruner.kt` | Deduplication, error purging, write superseding — Phase 0 | 6 |
| Create | `agent/src/main/kotlin/.../context/FactsStore.kt` | Compression-proof structured knowledge store | 12 |
| Modify | `agent/src/main/kotlin/.../context/ToolOutputStore.kt` (70 lines) | middleTruncate() utility | 9 |
| Modify | `agent/src/main/kotlin/.../tools/builtin/RunCommandTool.kt` | Middle-truncation for command output | 9 |
| Modify | `agent/src/main/kotlin/.../tools/vcs/Git*Tool.kt` (5 files) | Middle-truncation for git output | 9 |
| Modify | `agent/src/test/.../context/ContextManagerTest.kt` | Tests for rich placeholders, summarizer, protected tools, tiered pruning, min savings | 2,3,8,10,11 |
| Modify | `agent/src/test/.../context/ContextManagerCompressionTest.kt` (189 lines) | Tests for orphan protection, boundary markers | 4,7 |
| Create | `agent/src/test/.../context/SmartPrunerTest.kt` | Tests for dedup, error purging, write superseding | 6 |
| Create | `agent/src/test/.../context/FactsStoreTest.kt` | Tests for facts recording, dedup, capping, token estimate | 12 |
| Modify | `agent/src/test/.../runtime/BudgetEnforcerTest.kt` | Tests for new thresholds | 1 |
| Modify | `agent/src/test/.../runtime/LoopGuardTest.kt` | Tests for re-read clearing after pruning | 7 |

---

## Task 1: Raise Budget Thresholds (Stop Premature Compression)

**Why:** We compress at 60% — Claude Code waits until 95%. We're destroying 35% of usable context unnecessarily. Most tasks under 15 iterations never need compression at all.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/BudgetEnforcer.kt:23-26`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt:28-29`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/BudgetEnforcerTest.kt`

- [ ] **Step 1: Write failing test — new thresholds**

```kotlin
// BudgetEnforcerTest.kt — add new test
@Test
fun `returns OK below 80 percent`() {
    // With 10000 budget, 80% = 8000 tokens
    every { mockContextManager.currentTokens } returns 7999
    assertEquals(BudgetEnforcer.BudgetStatus.OK, enforcer.check())
}

@Test
fun `returns COMPRESS between 80 and 88 percent`() {
    every { mockContextManager.currentTokens } returns 8500
    assertEquals(BudgetEnforcer.BudgetStatus.COMPRESS, enforcer.check())
}

@Test
fun `returns NUDGE between 88 and 93 percent`() {
    every { mockContextManager.currentTokens } returns 9000
    assertEquals(BudgetEnforcer.BudgetStatus.NUDGE, enforcer.check())
}

@Test
fun `returns STRONG_NUDGE between 93 and 97 percent`() {
    every { mockContextManager.currentTokens } returns 9500
    assertEquals(BudgetEnforcer.BudgetStatus.STRONG_NUDGE, enforcer.check())
}

@Test
fun `returns TERMINATE above 97 percent`() {
    every { mockContextManager.currentTokens } returns 9800
    assertEquals(BudgetEnforcer.BudgetStatus.TERMINATE, enforcer.check())
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*BudgetEnforcerTest*" -x verifyPlugin`
Expected: FAIL — old thresholds (60/75/85/95) don't match new expectations

- [ ] **Step 3: Update thresholds in BudgetEnforcer.kt**

Change `BudgetEnforcer.kt` lines 23-26:
```kotlin
private const val COMPRESSION_RATIO = 0.80   // Was 0.60 — Claude Code uses ~0.95, we use 0.80 as balanced
private const val NUDGE_RATIO = 0.88         // Was 0.75
private const val STRONG_NUDGE_RATIO = 0.93  // Was 0.85
private const val TERMINATE_RATIO = 0.97     // Was 0.95 — leave only 3% emergency buffer
```

Update `ContextManager.kt` lines 28-29 to match:
```kotlin
private val tMaxRatio: Double = 0.93,    // Was 0.85 — match STRONG_NUDGE, triggers auto-compress in addMessage()
private val tRetainedRatio: Double = 0.70, // Was 0.60 — compress down to 70% (keep more context)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*BudgetEnforcerTest*" -x verifyPlugin`
Expected: PASS

- [ ] **Step 5: Update existing BudgetEnforcerTest assertions for new thresholds**

The existing `BudgetEnforcerTest.kt` has ~12 tests asserting against OLD thresholds (60/75/85/95%). These WILL break. Update every existing test to match new thresholds:

- `"returns COMPRESS when token usage is at compression threshold"` — was 60%, now 80%. Update token value from 90,000 to 120,000 (80% of 150,000).
- `"returns NUDGE when token usage reaches nudge threshold"` — was 75%, now 88%. Update token value from 112,500 to 132,000.
- `"returns STRONG_NUDGE when token usage reaches strong nudge threshold"` — was 85%, now 93%. Update token value from 127,500 to 139,500.
- `"returns TERMINATE when token usage reaches terminate threshold"` — was 95%, now 97%. Update token value from 142,500 to 145,500.
- Update all boundary tests similarly (values just below/above each threshold).

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*BudgetEnforcerTest*" -x verifyPlugin`
Expected: ALL existing + new tests PASS

- [ ] **Step 6: Verify ContextManager tests still pass**

The existing `ContextManagerTest` and `ContextManagerCompressionTest` use explicit `tMaxRatio = 0.70` and `tRetainedRatio = 0.40` overrides, so they should be unaffected by default changes. Verify:

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*ContextManagerTest*" --tests "*ContextManagerCompressionTest*" -x verifyPlugin`
Expected: PASS (tests override ratios explicitly)

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/BudgetEnforcer.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/BudgetEnforcerTest.kt
git commit -m "fix(agent): raise compression thresholds to prevent premature context loss

COMPRESS 60%→80%, NUDGE 75%→88%, STRONG_NUDGE 85%→93%, TERMINATE 95%→97%.
tMax 85%→93%, tRetained 60%→70%. Aligned with Claude Code (~95%) and OpenCode (~85%)
research — previous 60% threshold was destroying usable context unnecessarily."
```

---

## Task 2: Rich Pruning Placeholders (Fix Critical Hallucination #1)

**Why:** Current placeholder `"[Old tool result cleared to save context]"` contains zero metadata. The LLM cannot know what tool was called, what it returned, or how to recover the data. This is the #1 hallucination source.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt:346-364`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/context/ContextManagerTest.kt`

- [ ] **Step 1: Write failing test — rich placeholder contains tool metadata**

```kotlin
// ContextManagerTest.kt — add tests for rich placeholders
@Test
fun `pruneOldToolResults preserves tool name and args in placeholder`() {
    // Use a larger budget so we can control pruning precisely
    val cm = ContextManager(maxInputTokens = 5000, tMaxRatio = 0.90, tRetainedRatio = 0.50)
    cm.addMessage(ChatMessage(role = "system", content = "System prompt"))

    // Add an assistant message with a tool call, then the tool result
    cm.addMessage(ChatMessage(
        role = "assistant",
        content = null,
        toolCalls = listOf(ToolCall(
            id = "call-1",
            type = "function",
            function = FunctionCall(name = "read_file", arguments = """{"path": "/src/AuthService.kt", "offset": 1}""")
        ))
    ))
    cm.addMessage(ChatMessage(
        role = "tool",
        content = "<external_data>\npackage com.example.auth\nclass AuthService {\n  fun login() { ... }\n}\n</external_data>",
        toolCallId = "call-1"
    ))

    // Add enough content to push old tool result out of protection window
    for (i in 1..15) {
        cm.addMessage(ChatMessage(role = "user", content = "Msg $i: ${"X".repeat(200)}"))
        cm.addMessage(ChatMessage(role = "assistant", content = "Reply $i: ${"Y".repeat(200)}"))
    }

    // Prune with a small protection window so our tool result gets pruned
    cm.pruneOldToolResults(protectedTokens = 500)

    val messages = cm.getMessages()
    val prunedTool = messages.first { it.toolCallId == "call-1" }

    // Rich placeholder must contain:
    assertTrue(prunedTool.content!!.contains("read_file"), "Placeholder should contain tool name")
    assertTrue(prunedTool.content!!.contains("AuthService.kt"), "Placeholder should contain file path from args")
    assertTrue(prunedTool.content!!.contains("package com.example.auth"), "Placeholder should contain first lines preview")
    assertTrue(prunedTool.content!!.contains("re-read") || prunedTool.content!!.contains("read_file"),
        "Placeholder should contain recovery hint")
}

@Test
fun `pruneOldToolResults includes disk path when available`() {
    val cm = ContextManager(maxInputTokens = 5000, tMaxRatio = 0.90, tRetainedRatio = 0.50)
    val store = ToolOutputStore(tempDir) // use @TempDir
    cm.toolOutputStore = store

    cm.addMessage(ChatMessage(role = "system", content = "System prompt"))
    cm.addToolResult("call-1", "Full file content here\nline 2\nline 3", "summary")

    // Pad context
    for (i in 1..15) {
        cm.addMessage(ChatMessage(role = "user", content = "Msg $i: ${"X".repeat(200)}"))
        cm.addMessage(ChatMessage(role = "assistant", content = "Reply $i: ${"Y".repeat(200)}"))
    }

    cm.pruneOldToolResults(protectedTokens = 500)

    val prunedTool = cm.getMessages().first { it.toolCallId == "call-1" }
    assertTrue(prunedTool.content!!.contains("tool-outputs"), "Placeholder should contain disk path")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*ContextManagerTest.pruneOldToolResults*" -x verifyPlugin`
Expected: FAIL — current placeholder has no metadata

- [ ] **Step 3: Implement rich placeholders in ContextManager.kt**

Add a helper method and data class, then update `pruneOldToolResults()`:

```kotlin
// Add inside ContextManager class, before pruneOldToolResults()

/** Metadata extracted from the assistant's tool_call that triggered a tool result. */
private data class ToolCallMeta(val toolName: String, val arguments: String)

/**
 * Walk backward from a tool result message to find the assistant message
 * that triggered it, extracting the tool name and arguments.
 */
private fun findToolCallMetadata(toolResultIndex: Int, toolCallId: String?): ToolCallMeta? {
    if (toolCallId == null) return null
    for (j in (toolResultIndex - 1) downTo 0) {
        val msg = messages[j]
        if (msg.role == "assistant" && msg.toolCalls != null) {
            val tc = msg.toolCalls.find { it.id == toolCallId }
            if (tc != null) {
                return ToolCallMeta(tc.function.name, tc.function.arguments)
            }
        }
    }
    return null
}

/**
 * Build a rich placeholder that preserves tool metadata, content preview,
 * and recovery instructions. ~200-400 tokens — worth it to prevent hallucination.
 */
private fun buildRichPlaceholder(
    originalContent: String?,
    toolResultIndex: Int,
    toolCallId: String?
): String {
    val meta = findToolCallMetadata(toolResultIndex, toolCallId)
    val diskPath = toolCallId?.let { toolOutputStore?.getPath(it) }
    val content = originalContent ?: ""
    // Strip external_data wrapper for preview
    val rawContent = content
        .removePrefix("<external_data>").removePrefix("\n")
        .removeSuffix("</external_data>").removeSuffix("\n")
    val firstLines = rawContent.lineSequence().take(5).joinToString("\n")
    val contentLength = rawContent.length

    return buildString {
        appendLine("[Tool result pruned to save context]")
        if (meta != null) {
            appendLine("Tool: ${meta.toolName}")
            appendLine("Arguments: ${meta.arguments.take(300)}")
        }
        if (firstLines.isNotBlank()) {
            appendLine("Preview: ${firstLines.take(500)}")
        }
        appendLine("Original size: $contentLength chars")
        if (diskPath != null) {
            appendLine("Full output saved: $diskPath")
        }
        if (meta?.toolName == "read_file") {
            // Extract file path from args for actionable recovery hint
            val pathMatch = Regex(""""path"\s*:\s*"([^"]+)"""").find(meta.arguments)
            val filePath = pathMatch?.groupValues?.get(1)
            if (filePath != null) {
                appendLine("Recovery: use read_file on '$filePath' to re-read this file.")
            }
        } else {
            appendLine("Recovery: re-run the original tool if you need this data.")
        }
    }.trimEnd()
}
```

Then update `pruneOldToolResults()` (line 346-364):
```kotlin
fun pruneOldToolResults(protectedTokens: Int = 40_000) {  // Raised from 30K to 40K (OpenCode uses 40K)
    var protectedSoFar = 0
    for (i in messages.indices.reversed()) {
        val msg = messages[i]
        if (msg.role != "tool") continue
        val msgTokens = TokenEstimator.estimate(listOf(msg))
        if (protectedSoFar + msgTokens <= protectedTokens) {
            protectedSoFar += msgTokens
            continue
        }
        val toolCallId = msg.toolCallId
        val richPlaceholder = buildRichPlaceholder(msg.content, i, toolCallId)
        messages[i] = ChatMessage(
            role = "tool",
            content = "<external_data>$richPlaceholder</external_data>",
            toolCallId = toolCallId
        )
    }
    totalTokens = TokenEstimator.estimate(getMessages())
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*ContextManagerTest*" -x verifyPlugin`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/context/ContextManagerTest.kt
git commit -m "fix(agent): rich pruning placeholders with tool name, args, preview, and recovery hints

Replaces blank '[Old tool result cleared]' with metadata-rich placeholder containing
tool name, arguments (300 char cap), first 5 lines preview, disk path, and actionable
recovery hint. Raises protection window from 30K to 40K tokens (OpenCode parity).
Prevents hallucination from pruned tool results — LLM now knows what was pruned and
how to recover it."
```

---

## Task 3: Meaningful Fallback Summarizer (Fix Critical Hallucination #2)

**Why:** The fallback summarizer reduces tool results to `"Tool result (N chars)"` — zero content. This is the safety net when LLM compression fails (API errors, rate limits). A safety net that preserves nothing is not a safety net.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt:32-47`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/context/ContextManagerTest.kt`

- [ ] **Step 1: Write failing test — summarizer preserves tool content preview**

```kotlin
// ContextManagerTest.kt
@Test
fun `fallback summarizer includes tool result preview not just char count`() {
    val cm = ContextManager(
        maxInputTokens = 3000,
        tMaxRatio = 0.70,
        tRetainedRatio = 0.30
    )
    cm.addMessage(ChatMessage(role = "system", content = "System"))
    cm.addMessage(ChatMessage(role = "user", content = "Analyze AuthService"))
    cm.addMessage(ChatMessage(
        role = "tool",
        content = "package com.example.auth\nclass AuthService {\n  fun login() {\n    val query = \"SELECT * FROM users\"\n  }\n}",
        toolCallId = "tc-1"
    ))

    // Add enough to trigger auto-compression
    for (i in 1..15) {
        cm.addMessage(ChatMessage(role = "user", content = "Pad $i ${"X".repeat(200)}"))
        cm.addMessage(ChatMessage(role = "assistant", content = "Reply $i ${"Y".repeat(200)}"))
    }

    // After auto-compression, the anchored summary should have tool content preview
    val allMsgs = cm.getMessages()
    val summaryMsg = allMsgs.firstOrNull { it.content?.contains("Compressed Context") == true }
    assertNotNull(summaryMsg, "Should have a compressed context summary")
    assertTrue(summaryMsg!!.content!!.contains("package com.example"), "Summary should include tool result preview")
    assertFalse(summaryMsg.content!!.matches(Regex(".*Tool result \\(\\d+ chars\\).*")),
        "Summary should NOT reduce tool results to just char count")
}

@Test
fun `fallback summarizer extracts file paths into separate section`() {
    val cm = ContextManager(
        maxInputTokens = 3000,
        tMaxRatio = 0.70,
        tRetainedRatio = 0.30
    )
    cm.addMessage(ChatMessage(role = "system", content = "System"))
    cm.addMessage(ChatMessage(role = "user", content = "Read src/main/AuthService.kt"))
    cm.addMessage(ChatMessage(
        role = "tool",
        content = "Content of src/main/AuthService.kt\nimport com/example/UserModel.kt",
        toolCallId = "tc-1"
    ))

    // Trigger compression
    for (i in 1..15) {
        cm.addMessage(ChatMessage(role = "user", content = "Pad $i ${"X".repeat(200)}"))
        cm.addMessage(ChatMessage(role = "assistant", content = "Reply $i ${"Y".repeat(200)}"))
    }

    val allMsgs = cm.getMessages()
    val summaryMsg = allMsgs.firstOrNull { it.content?.contains("Compressed Context") == true }
    assertNotNull(summaryMsg)
    assertTrue(summaryMsg!!.content!!.contains("Referenced Files") || summaryMsg.content!!.contains("AuthService"),
        "Summary should extract file paths")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*ContextManagerTest.fallback*" -x verifyPlugin`
Expected: FAIL — current summarizer produces `"Tool result (N chars)"` with no preview

- [ ] **Step 3: Replace the fallback summarizer**

Replace lines 32-47 of `ContextManager.kt`:

```kotlin
private val summarizer: (List<ChatMessage>) -> String = { msgs ->
    val sb = StringBuilder()
    sb.appendLine("## Compressed Context Summary")
    sb.appendLine("WARNING: This is a lossy summary. File contents may be incomplete. Re-read files before editing.\n")

    val filePaths = mutableSetOf<String>()
    val filePathRegex = Regex("""[\w./\\-]+\.\w{1,10}""")

    for (msg in msgs) {
        val content = msg.content ?: continue
        // Extract file paths from all messages
        filePathRegex.findAll(content)
            .map { it.value }
            .filter { it.contains("/") || it.contains("\\") }
            .forEach { filePaths.add(it) }

        when (msg.role) {
            "user" -> sb.appendLine("- User: ${content.take(500)}")
            "assistant" -> {
                if (content.length > 5) sb.appendLine("- Agent: ${content.take(500)}")
            }
            "tool" -> {
                // Preserve first 10 lines of tool result — not just char count
                val rawContent = content
                    .removePrefix("<external_data>").removePrefix("\n")
                    .removeSuffix("</external_data>").removeSuffix("\n")
                val preview = rawContent.lineSequence().take(10).joinToString("\n")
                sb.appendLine("- Tool result (${content.length} chars):")
                sb.appendLine("  ${preview.take(500)}")
            }
            "system" -> {} // Skip system messages
        }
        if (sb.length > 6000) {
            sb.appendLine("... [${msgs.size} total messages, older ones omitted]")
            break
        }
    }

    if (filePaths.isNotEmpty()) {
        sb.appendLine("\n## Referenced Files")
        filePaths.take(30).forEach { sb.appendLine("- $it") }
    }

    sb.toString().take(8000)
}
```

**Key changes from the old summarizer:**
- Tool results get first 10 lines (500 chars) instead of just character count
- User messages get 500 chars instead of 200
- Assistant messages get 500 chars instead of 300
- Total cap raised from 2500 → 8000 chars (~2K tokens — still small, but 3x more useful)
- Break threshold raised from 2000 → 6000
- File paths extracted and listed in a separate section
- Explicit "lossy summary" warning so LLM knows to re-read before editing

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*ContextManagerTest*" -x verifyPlugin`
Expected: PASS

- [ ] **Step 5: Run compression tests too (they depend on summarizer output)**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*ContextManagerCompressionTest*" -x verifyPlugin`
Expected: PASS — the compression test at line 150 checks for `"Previous conversation summary:"`. Update that assertion to check for `"Compressed Context Summary"` instead.

- [ ] **Step 6: Fix ContextManagerCompressionTest assertion if needed**

In `ContextManagerCompressionTest.kt` line 150, change:
```kotlin
// Old:
allMsgs.any { it.role == "system" && it.content?.contains("Previous conversation summary:") == true }
// New:
allMsgs.any { it.role == "system" && it.content?.contains("Compressed Context Summary") == true }
```

Same for line 170 (the fallback test).

- [ ] **Step 7: Run all context tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*ContextManager*" -x verifyPlugin`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/context/ContextManagerTest.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/context/ContextManagerCompressionTest.kt
git commit -m "fix(agent): meaningful fallback summarizer preserves tool result previews

Replaces catastrophically lossy summarizer (tool results → 'N chars') with one that
preserves first 10 lines of each tool result, 500-char user/assistant messages, extracts
file paths into separate section, and warns LLM that summary is lossy. Cap raised
from 2500 to 8000 chars."
```

---

## Task 4: Compression Boundary Markers (Fix Critical Hallucination #3)

**Why:** When compression happens, the LLM doesn't know. It treats summarized context with the same confidence as fresh tool output, leading to confabulation when details are approximate or missing.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt:189-229` (compress) and `239-339` (compressWithLlm)
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/context/ContextManagerCompressionTest.kt`

- [ ] **Step 1: Write failing test — compression adds boundary marker**

```kotlin
// ContextManagerCompressionTest.kt
@Test
fun `compress adds boundary marker warning about lossy context`() {
    val cm = ContextManager(
        maxInputTokens = 3000,
        tMaxRatio = 0.70,
        tRetainedRatio = 0.30
    )
    cm.addMessage(ChatMessage(role = "system", content = "System prompt"))
    cm.addMessage(ChatMessage(role = "user", content = "Do something"))
    cm.addMessage(ChatMessage(role = "tool", content = "Result data", toolCallId = "tc-1"))

    // Trigger auto-compression by adding enough messages
    for (i in 1..15) {
        cm.addMessage(ChatMessage(role = "user", content = "Pad $i ${"X".repeat(200)}"))
        cm.addMessage(ChatMessage(role = "assistant", content = "Reply $i ${"Y".repeat(200)}"))
    }

    val allMsgs = cm.getMessages()
    val summaryContent = allMsgs.filter { it.role == "system" }.joinToString("\n") { it.content ?: "" }

    assertTrue(summaryContent.contains("CONTEXT COMPRESSED") || summaryContent.contains("lossy summary"),
        "Should contain compression boundary marker")
    assertTrue(summaryContent.contains("re-read") || summaryContent.contains("Re-read"),
        "Should instruct LLM to re-read files before editing")
}

@Test
fun `compressWithLlm adds boundary marker`() = runTest {
    val mockBrain = createMockBrain(successResponse("## Goal\nFix bug\n## Discoveries\nFound NPE"))
    val cm = buildAboveRetainedWithTool()

    cm.compressWithLlm(mockBrain)

    val allMsgs = cm.getMessages()
    val summaryContent = allMsgs.filter { it.role == "system" }.joinToString("\n") { it.content ?: "" }

    assertTrue(summaryContent.contains("CONTEXT COMPRESSED") || summaryContent.contains("approximate"),
        "LLM compression should include boundary marker about approximate content")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*ContextManagerCompressionTest.compress adds*" --tests "*ContextManagerCompressionTest.compressWithLlm adds boundary*" -x verifyPlugin`
Expected: FAIL — no boundary markers exist

- [ ] **Step 3: Add compression boundary marker constant and inject it**

Add constant to `ContextManager`:
```kotlin
companion object {
    /** Injected after compression so the LLM knows earlier context is approximate. */
    private const val COMPRESSION_BOUNDARY = """[CONTEXT COMPRESSED] Messages above this point are a lossy summary of earlier work.
- Line numbers, code snippets, and variable names may be approximate
- ALWAYS re-read a file before editing it, even if the summary mentions it
- If you need exact details from the summary, verify with a tool call first
- Treat summarized content as a starting point for re-investigation, not as ground truth"""
}
```

In `compress()` (line 216), after `anchoredSummaries.add(summary)`:
```kotlin
anchoredSummaries.add(summary + "\n\n" + COMPRESSION_BOUNDARY)
```

In `compressWithLlm()` (line 326), after `anchoredSummaries.add(summary)`:
```kotlin
anchoredSummaries.add(summary + "\n\n" + COMPRESSION_BOUNDARY)
```

Also update the LLM summarization prompt (line 267-290) to add provenance guidance:
```kotlin
val summarizePrompt = """
Summarize the conversation so far into a structured continuation prompt.
IMPORTANT: Mark any detail you are uncertain about with [APPROX].
Do NOT invent line numbers or code patterns — only include what is explicitly stated.
Use this exact format:

## Goal
...
""".trimIndent()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*ContextManagerCompressionTest*" -x verifyPlugin`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/context/ContextManagerCompressionTest.kt
git commit -m "fix(agent): inject compression boundary markers so LLM knows context is lossy

After any compression event, a boundary marker is appended warning the LLM that
earlier content is approximate and instructing it to re-read files before editing.
LLM summarization prompt updated to mark uncertain details with [APPROX]."
```

---

## Task 5: Compression-Aware System Prompt

**Why:** Teaching the LLM about compression in the system prompt primes it to self-verify. This is ~100 tokens — zero downside, significant hallucination reduction. Both Claude Code and OpenCode have equivalent mechanisms.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt:392-411`
- No new tests needed — this is a prompt content addition

- [ ] **Step 1: Add CONTEXT_MANAGEMENT_RULES constant to PromptAssembler**

Add before the existing `RULES` constant (around line 392):

```kotlin
val CONTEXT_MANAGEMENT_RULES = """
    <context_management>
    Your conversation history may be compressed during long tasks to stay within the context window.
    When this happens:
    - Old tool results are replaced with metadata placeholders (tool name, args, preview)
    - Earlier messages may be summarized — details could be approximate
    - ALWAYS re-read a file before editing it, even if you believe you know its contents
    - If a tool result shows "[Tool result pruned]", use the original tool to re-read
    - Treat information from compressed summaries as a starting point — verify before acting on specifics
    - File paths in summaries are reliable; line numbers and code snippets may be stale
    </context_management>
""".trimIndent()
```

- [ ] **Step 2: Wire it into buildSingleAgentPrompt()**

In `buildSingleAgentPrompt()`, add after the rendering rules line (around line 88):

```kotlin
// 14. Context management awareness (anti-hallucination)
sections.add(CONTEXT_MANAGEMENT_RULES)
```

Renumber subsequent sections in comments accordingly.

- [ ] **Step 3: Run agent compile check**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:compileKotlin -x verifyPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt
git commit -m "fix(agent): add context management rules to system prompt

Teaches the LLM about compression: re-read before editing, treat summaries as
approximate, verify specifics with tool calls. ~100 tokens — primes self-correction."
```

---

## Task 6: Smart Pruning — Dedup, Error Purge, Write Supersede

**Why:** Before resorting to lossy compression, eliminate genuinely redundant content. OpenCode uses three strategies that can save 10-30K tokens without losing any unique information: deduplication (duplicate file reads), error purging (failed tool call inputs), and write superseding (write inputs after confirmed reads).

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/SmartPruner.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/context/SmartPrunerTest.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt:147-154` (wire SmartPruner before Phase 1)

- [ ] **Step 1: Write SmartPruner tests**

```kotlin
// SmartPrunerTest.kt
package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.ToolCall
import com.workflow.orchestrator.agent.api.dto.FunctionCall
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SmartPrunerTest {

    private fun assistantWithToolCall(toolCallId: String, toolName: String, args: String) = ChatMessage(
        role = "assistant", content = null,
        toolCalls = listOf(ToolCall(id = toolCallId, type = "function",
            function = FunctionCall(name = toolName, arguments = args)))
    )

    private fun toolResult(toolCallId: String, content: String) = ChatMessage(
        role = "tool", content = content, toolCallId = toolCallId
    )

    @Test
    fun `dedup keeps latest read_file for same path`() {
        val messages = mutableListOf(
            ChatMessage(role = "system", content = "prompt"),
            assistantWithToolCall("c1", "read_file", """{"path": "/src/A.kt"}"""),
            toolResult("c1", "version 1 content"),
            ChatMessage(role = "assistant", content = "Found issue"),
            assistantWithToolCall("c2", "read_file", """{"path": "/src/A.kt"}"""),
            toolResult("c2", "version 2 content"),
        )

        val savings = SmartPruner.deduplicateFileReads(messages)

        assertTrue(savings > 0, "Should report token savings")
        // First read (c1) should be replaced with dedup marker
        assertTrue(messages[2].content!!.contains("[Deduplicated]"), "First read should be marked as dedup")
        // Second read (c2) should be kept
        assertEquals("version 2 content", messages[5].content)
    }

    @Test
    fun `error purge removes large input from failed tool calls`() {
        val messages = mutableListOf(
            ChatMessage(role = "system", content = "prompt"),
            assistantWithToolCall("c1", "edit_file", """{"path": "/src/A.kt", "old_string": "${"X".repeat(2000)}", "new_string": "fixed"}"""),
            toolResult("c1", "<external_data>ERROR: old_string not found in file</external_data>"),
            ChatMessage(role = "assistant", content = "Let me try a different approach"),
            // 3 more messages after the error (meets the "N turns later" threshold)
            ChatMessage(role = "user", content = "Continue"),
            ChatMessage(role = "assistant", content = "Trying again"),
        )

        val savings = SmartPruner.purgeFailedToolInputs(messages, turnsAfterError = 2)

        assertTrue(savings > 0, "Should report token savings")
        // The large args in the assistant tool call should be truncated
        val assistantMsg = messages[1]
        assertTrue(assistantMsg.toolCalls!![0].function.arguments.length < 500,
            "Failed tool call arguments should be truncated")
    }

    @Test
    fun `write supersede removes write input after confirmed read`() {
        val messages = mutableListOf(
            ChatMessage(role = "system", content = "prompt"),
            // Write operation
            assistantWithToolCall("c1", "edit_file", """{"path": "/src/A.kt", "old_string": "old code block here", "new_string": "new code block here"}"""),
            toolResult("c1", "Edit applied successfully to /src/A.kt"),
            // Subsequent read confirms the write
            assistantWithToolCall("c2", "read_file", """{"path": "/src/A.kt"}"""),
            toolResult("c2", "file content with new code block here"),
        )

        val savings = SmartPruner.supersedeConfirmedWrites(messages)

        assertTrue(savings > 0, "Should report token savings")
        // The write's tool result should be compacted
        assertTrue(messages[2].content!!.contains("[Write confirmed by subsequent read]"),
            "Write result should note confirmation")
    }
}
```

- [ ] **Step 2: Run test to verify it fails (SmartPruner doesn't exist)**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*SmartPrunerTest*" -x verifyPlugin`
Expected: FAIL — class not found

- [ ] **Step 3: Implement SmartPruner.kt**

```kotlin
package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.ToolCall
import com.workflow.orchestrator.agent.api.dto.FunctionCall

/**
 * Pre-compression pruning strategies that eliminate genuinely redundant content
 * before resorting to lossy compression. Runs before Phase 1 (tool result pruning).
 *
 * Three strategies (from OpenCode research):
 * 1. Deduplication — if the same file was read twice, keep only the latest read
 * 2. Error purging — after N turns, truncate large inputs from failed tool calls
 * 3. Write superseding — if a write was confirmed by a subsequent read, compact the write result
 */
object SmartPruner {

    private val FILE_PATH_REGEX = Regex(""""path"\s*:\s*"([^"]+)"""")

    /**
     * Deduplicate file reads: if the same file path was read multiple times,
     * replace older reads with a compact marker. Keeps the latest read.
     *
     * @return estimated tokens saved
     */
    fun deduplicateFileReads(messages: MutableList<ChatMessage>): Int {
        // Map: file path → list of (tool result index)
        val readsByPath = mutableMapOf<String, MutableList<Int>>()
        // Track files that were edited — reads across edit boundaries are NOT duplicates
        val editedFiles = mutableSetOf<String>()
        var tokensSaved = 0

        for (i in messages.indices) {
            val msg = messages[i]
            if (msg.role != "tool" || msg.toolCallId == null) continue

            val toolCall = findToolCall(messages, i, msg.toolCallId) ?: continue

            // Track edits — any read before an edit is NOT a duplicate of a read after
            if (toolCall.name == "edit_file") {
                val editPath = FILE_PATH_REGEX.find(toolCall.arguments)?.groupValues?.get(1)
                if (editPath != null) {
                    editedFiles.add(editPath)
                    readsByPath.remove(editPath) // Reset reads for this file — post-edit reads are fresh
                }
                continue
            }

            if (toolCall.name != "read_file") continue

            val filePath = FILE_PATH_REGEX.find(toolCall.arguments)?.groupValues?.get(1) ?: continue
            readsByPath.getOrPut(filePath) { mutableListOf() }.add(i)
        }

        // For each file read more than once, replace all but the latest with a marker
        for ((path, indices) in readsByPath) {
            if (indices.size < 2) continue
            val latestIndex = indices.last()
            for (idx in indices) {
                if (idx == latestIndex) continue
                val oldContent = messages[idx].content ?: continue
                val oldTokens = TokenEstimator.estimate(oldContent)
                val marker = "<external_data>[Deduplicated — '$path' was re-read later. See latest read for current content.]</external_data>"
                messages[idx] = ChatMessage(role = "tool", content = marker, toolCallId = messages[idx].toolCallId)
                tokensSaved += oldTokens - TokenEstimator.estimate(marker)
            }
        }

        return tokensSaved
    }

    /**
     * Purge large inputs from failed tool calls after N turns have passed.
     * Keeps the error message but truncates the (often huge) arguments.
     *
     * @param turnsAfterError Only purge if at least this many messages have passed since the error
     * @return estimated tokens saved
     */
    fun purgeFailedToolInputs(messages: MutableList<ChatMessage>, turnsAfterError: Int = 4): Int {
        var tokensSaved = 0

        for (i in messages.indices) {
            val msg = messages[i]
            if (msg.role != "tool" || msg.toolCallId == null) continue
            val content = msg.content ?: continue

            // Check if this is an error result — use specific patterns to avoid
            // false positives on file contents that happen to contain "error" or "failed"
            val stripped = content.removePrefix("<external_data>").removePrefix("\n").trimStart()
            val isError = stripped.startsWith("ERROR") ||
                stripped.startsWith("error:") ||
                stripped.startsWith("Failed") ||
                stripped.contains("old_string not found") ||
                stripped.contains("Exception:") ||
                stripped.contains("Command failed with exit code")
            if (!isError) continue

            // Check if enough turns have passed
            val turnsAfter = messages.size - 1 - i
            if (turnsAfter < turnsAfterError) continue

            // Find and truncate the assistant's tool call arguments
            for (j in (i - 1) downTo 0) {
                val assistantMsg = messages[j]
                if (assistantMsg.role != "assistant" || assistantMsg.toolCalls == null) continue
                val tc = assistantMsg.toolCalls.find { it.id == msg.toolCallId } ?: continue
                val argsLen = tc.function.arguments.length
                if (argsLen < 500) break // Not worth truncating small args

                val truncatedArgs = tc.function.arguments.take(200) + "... [args truncated — tool call failed]"
                val newToolCall = ToolCall(
                    id = tc.id, type = tc.type,
                    function = FunctionCall(name = tc.function.name, arguments = truncatedArgs)
                )
                val newToolCalls = assistantMsg.toolCalls.map { if (it.id == tc.id) newToolCall else it }
                messages[j] = ChatMessage(
                    role = "assistant", content = assistantMsg.content,
                    toolCalls = newToolCalls, toolCallId = assistantMsg.toolCallId
                )
                tokensSaved += TokenEstimator.estimate(tc.function.arguments) -
                    TokenEstimator.estimate(truncatedArgs)
                break
            }
        }

        return tokensSaved
    }

    /**
     * Supersede write operations that were confirmed by a subsequent read.
     * If a file was edited and then re-read (confirming the edit applied),
     * the write's detailed tool result can be compacted.
     *
     * @return estimated tokens saved
     */
    fun supersedeConfirmedWrites(messages: MutableList<ChatMessage>): Int {
        var tokensSaved = 0
        val writeTools = setOf("edit_file")
        val readTools = setOf("read_file")

        // Track writes: path → tool result index
        val pendingWrites = mutableMapOf<String, Int>()

        for (i in messages.indices) {
            val msg = messages[i]
            if (msg.role != "tool" || msg.toolCallId == null) continue
            val toolCall = findToolCall(messages, i, msg.toolCallId) ?: continue

            val filePath = FILE_PATH_REGEX.find(toolCall.arguments)?.groupValues?.get(1) ?: continue

            if (toolCall.name in writeTools) {
                // Record this write
                val content = msg.content ?: continue
                if (content.contains("applied") || content.contains("success")) {
                    pendingWrites[filePath] = i
                }
            } else if (toolCall.name in readTools && filePath in pendingWrites) {
                // This read confirms the earlier write
                val writeIdx = pendingWrites.remove(filePath) ?: continue
                val writeMsg = messages[writeIdx]
                val oldContent = writeMsg.content ?: continue
                val oldTokens = TokenEstimator.estimate(oldContent)

                val compacted = "<external_data>[Write confirmed by subsequent read — edit to '$filePath' was applied successfully.]</external_data>"
                messages[writeIdx] = ChatMessage(role = "tool", content = compacted, toolCallId = writeMsg.toolCallId)
                tokensSaved += oldTokens - TokenEstimator.estimate(compacted)
            }
        }

        return tokensSaved
    }

    /**
     * Run all smart pruning strategies. Returns total tokens saved.
     */
    fun pruneAll(messages: MutableList<ChatMessage>, turnsAfterError: Int = 4): Int {
        var total = 0
        total += deduplicateFileReads(messages)
        total += purgeFailedToolInputs(messages, turnsAfterError)
        total += supersedeConfirmedWrites(messages)
        return total
    }

    /** Find the ToolCall that triggered a tool result by walking backward. */
    private fun findToolCall(messages: List<ChatMessage>, toolResultIndex: Int, toolCallId: String): FunctionCall? {
        for (j in (toolResultIndex - 1) downTo 0) {
            val msg = messages[j]
            if (msg.role == "assistant" && msg.toolCalls != null) {
                val tc = msg.toolCalls.find { it.id == toolCallId }
                if (tc != null) return tc.function
            }
        }
        return null
    }
}
```

- [ ] **Step 4: Run SmartPruner tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*SmartPrunerTest*" -x verifyPlugin`
Expected: PASS

- [ ] **Step 5: Wire SmartPruner into ContextManager.addMessage()**

In `ContextManager.kt`, update the compression block in `addMessage()` (lines 147-154):

```kotlin
if (totalTokens > tMax) {
    // Phase 0: Smart pruning — eliminate genuinely redundant content first (no info loss)
    SmartPruner.pruneAll(messages)
    totalTokens = TokenEstimator.estimate(getMessages())

    if (totalTokens > tMax) {
        // Phase 1: Prune old tool results (keeps metadata placeholder)
        pruneOldToolResults()
        // Phase 2: Full compression if still over budget
        if (totalTokens > tMax) {
            compress()
        }
    }
}
```

- [ ] **Step 6: Run all context tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*ContextManager*" --tests "*SmartPruner*" -x verifyPlugin`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/SmartPruner.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/context/SmartPrunerTest.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt
git commit -m "feat(agent): smart pruning — dedup, error purge, write supersede before lossy compression

New Phase 0 runs before Phase 1: deduplicates identical file reads (keeps latest),
truncates large args from failed tool calls after 4 turns, and compacts write results
confirmed by subsequent reads. Eliminates redundant content with zero information loss,
reducing the frequency of lossy Phase 1/2 compression."
```

---

## Task 7: Orphan Protection & System Warning Cap

**Why:** Two structural issues: (1) when compression drops an assistant message with `toolCalls`, the corresponding tool result becomes orphaned — it exists with no context for why that tool was called. (2) System warnings accumulate, consuming 2K+ tokens of repetitive guidance that wastes the already-tight summary budget during compression.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt:189-229` (compress) and `239-339` (compressWithLlm)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt:273-283` (system warning injection)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/LoopGuard.kt` (clear readFiles after pruning)
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/context/ContextManagerCompressionTest.kt`

- [ ] **Step 1: Write failing test — orphaned tool results are also dropped**

```kotlin
// ContextManagerCompressionTest.kt
@Test
fun `compress also drops tool results when their assistant tool_call is dropped`() {
    val cm = ContextManager(
        maxInputTokens = 3000,
        tMaxRatio = 0.70,
        tRetainedRatio = 0.30
    )
    cm.addMessage(ChatMessage(role = "system", content = "System"))
    // Assistant with tool call
    cm.addMessage(ChatMessage(
        role = "assistant", content = null,
        toolCalls = listOf(ToolCall(id = "tc-1", type = "function",
            function = FunctionCall(name = "read_file", arguments = """{"path": "A.kt"}""")))
    ))
    // Tool result
    cm.addMessage(ChatMessage(role = "tool", content = "File content", toolCallId = "tc-1"))

    // Pad to trigger compression — these should survive, the above should be dropped
    for (i in 1..12) {
        cm.addMessage(ChatMessage(role = "user", content = "Pad $i ${"X".repeat(200)}"))
        cm.addMessage(ChatMessage(role = "assistant", content = "Reply $i ${"Y".repeat(200)}"))
    }

    // After compression, no orphaned tool result should remain
    val messages = cm.getMessages()
    val toolResults = messages.filter { it.role == "tool" }
    for (toolResult in toolResults) {
        val callId = toolResult.toolCallId ?: continue
        // Every tool result should have a corresponding assistant tool_call
        val hasParent = messages.any { msg ->
            msg.role == "assistant" && msg.toolCalls?.any { it.id == callId } == true
        }
        assertTrue(hasParent || toolResult.content?.contains("Compressed") == true,
            "Tool result $callId should have a parent assistant message or be in summary")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*ContextManagerCompressionTest.compress also drops*" -x verifyPlugin`
Expected: FAIL — orphaned tool results may remain

- [ ] **Step 3: Add orphan protection to compress() and compressWithLlm()**

In `compress()`, after the main loop that builds `indicesToRemove` (around line 208), add orphan detection:

```kotlin
// Orphan protection: if we're dropping an assistant message with tool calls,
// also drop the corresponding tool results (prevents orphaned context)
val droppedToolCallIds = mutableSetOf<String>()
for (idx in indicesToRemove) {
    val msg = messages[idx]
    if (msg.role == "assistant" && msg.toolCalls != null) {
        msg.toolCalls.forEach { tc -> droppedToolCallIds.add(tc.id) }
    }
}
if (droppedToolCallIds.isNotEmpty()) {
    for (i in messages.indices) {
        if (i in indicesToRemove) continue
        val msg = messages[i]
        if (msg.role == "tool" && msg.toolCallId in droppedToolCallIds) {
            messagesToSummarize.add(msg)
            indicesToRemove.add(i)
        }
    }
}
```

In `compressWithLlm()`, after the equivalent loop (around line 259), add the same logic but using `messagesToDrop`:

```kotlin
// Orphan protection (same logic, different variable name)
val droppedToolCallIds = mutableSetOf<String>()
for (idx in indicesToRemove) {
    val msg = messages[idx]
    if (msg.role == "assistant" && msg.toolCalls != null) {
        msg.toolCalls.forEach { tc -> droppedToolCallIds.add(tc.id) }
    }
}
if (droppedToolCallIds.isNotEmpty()) {
    for (i in messages.indices) {
        if (i in indicesToRemove) continue
        val msg = messages[i]
        if (msg.role == "tool" && msg.toolCallId in droppedToolCallIds) {
            messagesToDrop.add(msg)
            indicesToRemove.add(i)
        }
    }
}
```

- [ ] **Step 4: Cap system warning messages in SingleAgentSession**

Add a `removeOldestSystemWarning()` method to `ContextManager` that operates on the internal `messages` list (not `getMessages()`, which prepends anchors and would cause index mismatch):

```kotlin
/**
 * Remove the oldest system warning message (contains "system_warning") from the
 * internal messages list. Used to cap system warnings at 2 per session.
 *
 * Note: operates on internal `messages` list, NOT `getMessages()` which prepends
 * anchored summaries, planAnchor, skillAnchor, mentionAnchor and would cause
 * index mismatch.
 */
fun removeOldestSystemWarning(): Boolean {
    val idx = messages.indexOfFirst { msg ->
        msg.role == "system" && msg.content?.contains("system_warning") == true
    }
    if (idx >= 0) {
        messages.removeAt(idx)
        totalTokens = TokenEstimator.estimate(getMessages())
        return true
    }
    return false
}

/** Count system warning messages in context. */
fun countSystemWarnings(): Int = messages.count { msg ->
    msg.role == "system" && msg.content?.contains("system_warning") == true
}
```

Then in `SingleAgentSession.kt`, around line 273-283, add a cap before injecting new system warnings:

```kotlin
// Cap system warnings at 2 — remove oldest when adding new (prevents token bloat)
while (contextManager.countSystemWarnings() >= 2) {
    contextManager.removeOldestSystemWarning()
}
```

- [ ] **Step 5: Add LoopGuard.clearAllFileReads() and wire it to pruning**

In `LoopGuard.kt`, add:
```kotlin
/** Clear all file read tracking (called after pruning — agent may need to re-read pruned files). */
fun clearAllFileReads() {
    readFiles.clear()
}
```

In `SingleAgentSession.kt`, after any call to `pruneOldToolResults()` or `compress()`/`compressWithLlm()`, add:
```kotlin
loopGuard.clearAllFileReads()  // Pruned files may need re-reading
```

- [ ] **Step 6: Run all tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*ContextManager*" --tests "*LoopGuardTest*" -x verifyPlugin`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/LoopGuard.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/context/ContextManagerCompressionTest.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/LoopGuardTest.kt
git commit -m "fix(agent): orphan protection, system warning cap, re-read tracking after pruning

When compression drops an assistant tool_call, also drops the orphaned tool result.
Caps system warning messages at 2 (removes oldest). Clears LoopGuard file-read tracking
after pruning so agent can re-read pruned files without 're-read' warning."
```

---

## Task 8: Protected Tool Types (Never Prune Critical Results)

**Why:** OpenCode protects certain tool types from EVER being pruned — subagent results (30+ iterations of work), plan operations, and task management. Our system prunes everything equally, meaning a subagent's comprehensive analysis gets the same treatment as a trivial `git_status`. Subagent results are irreplaceable (can't just "re-run" 30 iterations).

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/context/ContextManagerTest.kt`

- [ ] **Step 1: Write failing test — protected tool results are never pruned**

```kotlin
// ContextManagerTest.kt
@Test
fun `pruneOldToolResults never prunes protected tool types`() {
    val cm = ContextManager(maxInputTokens = 5000, tMaxRatio = 0.90, tRetainedRatio = 0.50)
    cm.addMessage(ChatMessage(role = "system", content = "System prompt"))

    // Add a protected tool result (agent/delegate_task/create_plan)
    cm.addMessage(ChatMessage(
        role = "assistant", content = null,
        toolCalls = listOf(ToolCall(id = "agent-1", type = "function",
            function = FunctionCall(name = "agent", arguments = """{"description": "analyze auth", "prompt": "..."}""")))
    ))
    cm.addMessage(ChatMessage(
        role = "tool",
        content = "<external_data>Agent completed: Found 3 security vulnerabilities across 5 files...</external_data>",
        toolCallId = "agent-1"
    ))

    // Add a regular tool result (should be prunable)
    cm.addMessage(ChatMessage(
        role = "assistant", content = null,
        toolCalls = listOf(ToolCall(id = "read-1", type = "function",
            function = FunctionCall(name = "read_file", arguments = """{"path": "/src/A.kt"}""")))
    ))
    cm.addMessage(ChatMessage(
        role = "tool",
        content = "<external_data>File content here...</external_data>",
        toolCallId = "read-1"
    ))

    // Pad context to push things out of protection window
    for (i in 1..15) {
        cm.addMessage(ChatMessage(role = "user", content = "Pad $i ${"X".repeat(200)}"))
        cm.addMessage(ChatMessage(role = "assistant", content = "Reply $i ${"Y".repeat(200)}"))
    }

    // Prune with tiny protection window — forces pruning of old results
    cm.pruneOldToolResults(protectedTokens = 500)

    val messages = cm.getMessages()
    val agentResult = messages.first { it.toolCallId == "agent-1" }
    val readResult = messages.first { it.toolCallId == "read-1" }

    // Agent result should NEVER be pruned
    assertTrue(agentResult.content!!.contains("Agent completed"),
        "Protected tool result (agent) should not be pruned")
    // Regular read_file should be pruned (outside protection window)
    assertTrue(readResult.content!!.contains("pruned") || readResult.content!!.contains("Tool result pruned"),
        "Regular tool result should be pruned")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*ContextManagerTest.pruneOldToolResults never prunes*" -x verifyPlugin`
Expected: FAIL — current code prunes everything equally

- [ ] **Step 3: Add protected tool set and skip logic to pruneOldToolResults()**

Add a companion object constant and modify `pruneOldToolResults()` in `ContextManager.kt`:

```kotlin
companion object {
    private const val COMPRESSION_BOUNDARY = """...""" // existing

    /** Tools whose results are too valuable to prune — they represent irreplaceable work. */
    val PROTECTED_TOOLS = setOf(
        "agent",           // Subagent results (30+ iterations of work)
        "delegate_task",   // Legacy subagent results
        "create_plan",     // Plan creation output
        "update_plan_step", // Plan step updates
        "save_memory",     // Memory operations
        "activate_skill",  // Skill activation
        "ask_questions",   // Question wizard results
    )
}
```

In `pruneOldToolResults()`, add a check before replacing:

```kotlin
fun pruneOldToolResults(protectedTokens: Int = 40_000) {
    var protectedSoFar = 0
    for (i in messages.indices.reversed()) {
        val msg = messages[i]
        if (msg.role != "tool") continue
        val msgTokens = TokenEstimator.estimate(listOf(msg))
        if (protectedSoFar + msgTokens <= protectedTokens) {
            protectedSoFar += msgTokens
            continue
        }

        // Never prune results from protected tools (subagents, plans, etc.)
        val toolCallId = msg.toolCallId
        val meta = findToolCallMetadata(i, toolCallId)
        if (meta != null && meta.toolName in PROTECTED_TOOLS) {
            protectedSoFar += msgTokens // Count toward protected budget but don't prune
            continue
        }

        val richPlaceholder = buildRichPlaceholder(msg.content, i, toolCallId)
        messages[i] = ChatMessage(
            role = "tool",
            content = "<external_data>$richPlaceholder</external_data>",
            toolCallId = toolCallId
        )
    }
    totalTokens = TokenEstimator.estimate(getMessages())
}
```

- [ ] **Step 4: Run tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*ContextManagerTest*" -x verifyPlugin`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/context/ContextManagerTest.kt
git commit -m "feat(agent): protected tool types — never prune subagent, plan, or memory results

Agent, delegate_task, create_plan, update_plan_step, save_memory, activate_skill,
and ask_questions results are protected from pruning. These represent irreplaceable
work (subagent 30+ iterations, user-approved plans) that cannot be recovered by
re-running the tool."
```

---

## Task 9: Middle-Truncation for Command Output (Claude Code Pattern)

**Why:** Claude Code keeps both the start AND end of command output, truncating the middle. Our current head-only truncation (`rawOutput.take(30000)`) loses error messages, exit codes, and summaries that typically appear at the END of long output (compilation errors, test results, stack traces). The end is often more informative than the middle.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt:212-217`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/vcs/GitDiffTool.kt:91-96`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/vcs/GitShowCommitTool.kt:75-80`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/vcs/GitLogTool.kt:97-102`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/vcs/GitFileHistoryTool.kt:69-74`

- [ ] **Step 1: Create a shared middle-truncation utility**

Create a utility function that all tools can use. Add to `ToolOutputStore.kt` (it already handles output capping):

```kotlin
companion object {
    // ... existing MAX_LINES, MAX_CHARS

    /**
     * Middle-truncate output: keep the first `headChars` and last `tailChars` characters,
     * replacing the middle with a truncation marker.
     *
     * Claude Code pattern: start has context (what command ran, initial output),
     * end has results (errors, summaries, exit codes). Middle is usually verbose logs.
     */
    fun middleTruncate(content: String, maxChars: Int, headRatio: Double = 0.6): String {
        if (content.length <= maxChars) return content
        val headChars = (maxChars * headRatio).toInt()
        val tailChars = maxChars - headChars - 200 // reserve 200 for the marker
        val omitted = content.length - headChars - tailChars
        return content.take(headChars) +
            "\n\n[... $omitted characters omitted from middle. Showing first $headChars + last $tailChars chars ...]\n\n" +
            content.takeLast(tailChars)
    }
}
```

- [ ] **Step 2: Update RunCommandTool.kt to use middle-truncation**

Replace lines 212-217:

```kotlin
val truncatedOutput = if (rawOutput.length > MAX_OUTPUT_CHARS) {
    ToolOutputStore.middleTruncate(rawOutput, MAX_OUTPUT_CHARS) +
        "\n\n[Total output: ${rawOutput.length} chars. Use a more targeted command to see specific sections.]"
} else {
    rawOutput
}
```

- [ ] **Step 3: Update all git tools to use middle-truncation**

Apply the same pattern to each git tool. For `GitDiffTool.kt` (lines 91-96):
```kotlin
val truncatedOutput = if (rawOutput.length > MAX_OUTPUT_CHARS) {
    ToolOutputStore.middleTruncate(rawOutput, MAX_OUTPUT_CHARS)
} else {
    rawOutput
}
```

Repeat for `GitShowCommitTool.kt`, `GitLogTool.kt`, `GitFileHistoryTool.kt`.

- [ ] **Step 4: Run compile check**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:compileKotlin -x verifyPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ToolOutputStore.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/vcs/GitDiffTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/vcs/GitShowCommitTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/vcs/GitLogTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/vcs/GitFileHistoryTool.kt
git commit -m "feat(agent): middle-truncation for command and git output (Claude Code pattern)

Keep first 60% + last 40% of output, truncate the verbose middle. Error messages,
exit codes, and summaries that appear at the end of long output are now preserved.
Applied to run_command, git_diff, git_show_commit, git_log, git_file_history."
```

---

## Task 10: Minimum Savings Threshold (Avoid Micro-Pruning)

**Why:** OpenCode only prunes if it saves ≥20K tokens — micro-pruning (saving 500 tokens by clearing a small tool result) isn't worth the information loss. Our system prunes any tool result outside the protection window regardless of savings.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt` (pruneOldToolResults)
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/context/ContextManagerTest.kt`

- [ ] **Step 1: Write failing test — small tool results are not worth pruning**

```kotlin
// ContextManagerTest.kt
@Test
fun `pruneOldToolResults skips small results below minimum savings threshold`() {
    val cm = ContextManager(maxInputTokens = 10000, tMaxRatio = 0.90, tRetainedRatio = 0.50)
    cm.addMessage(ChatMessage(role = "system", content = "System"))

    // Add a small tool result (< PRUNE_MINIMUM tokens)
    cm.addMessage(ChatMessage(
        role = "assistant", content = null,
        toolCalls = listOf(ToolCall(id = "c1", type = "function",
            function = FunctionCall(name = "git_status", arguments = "{}")))
    ))
    cm.addMessage(ChatMessage(
        role = "tool",
        content = "<external_data>On branch main\nnothing to commit</external_data>",
        toolCallId = "c1"
    ))

    // Pad context
    for (i in 1..30) {
        cm.addMessage(ChatMessage(role = "user", content = "Pad $i ${"X".repeat(200)}"))
        cm.addMessage(ChatMessage(role = "assistant", content = "Reply $i ${"Y".repeat(200)}"))
    }

    cm.pruneOldToolResults(protectedTokens = 500)

    // Small result (< 200 tokens) should NOT be pruned — not worth the info loss
    val toolMsg = cm.getMessages().first { it.toolCallId == "c1" }
    assertTrue(toolMsg.content!!.contains("nothing to commit"),
        "Small tool result should not be pruned — savings too small")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*ContextManagerTest.pruneOldToolResults skips small*" -x verifyPlugin`
Expected: FAIL — current code prunes everything outside protection window

- [ ] **Step 3: Add minimum savings check to pruneOldToolResults()**

```kotlin
companion object {
    // ... existing constants
    /** Minimum tokens a tool result must contain to be worth pruning. */
    private const val PRUNE_MINIMUM_TOKENS = 200
}

fun pruneOldToolResults(protectedTokens: Int = 40_000) {
    var protectedSoFar = 0
    for (i in messages.indices.reversed()) {
        val msg = messages[i]
        if (msg.role != "tool") continue
        val msgTokens = TokenEstimator.estimate(listOf(msg))
        if (protectedSoFar + msgTokens <= protectedTokens) {
            protectedSoFar += msgTokens
            continue
        }

        // Skip small results — not worth the information loss
        if (msgTokens < PRUNE_MINIMUM_TOKENS) continue

        // Never prune results from protected tools
        val toolCallId = msg.toolCallId
        val meta = findToolCallMetadata(i, toolCallId)
        if (meta != null && meta.toolName in PROTECTED_TOOLS) {
            protectedSoFar += msgTokens
            continue
        }

        val richPlaceholder = buildRichPlaceholder(msg.content, i, toolCallId)
        messages[i] = ChatMessage(
            role = "tool",
            content = "<external_data>$richPlaceholder</external_data>",
            toolCallId = toolCallId
        )
    }
    totalTokens = TokenEstimator.estimate(getMessages())
}
```

- [ ] **Step 4: Run tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*ContextManagerTest*" -x verifyPlugin`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/context/ContextManagerTest.kt
git commit -m "feat(agent): minimum savings threshold — skip pruning small tool results

Tool results under 200 tokens (~700 chars) are not worth pruning — the placeholder
would be nearly as large as the original. Follows OpenCode pattern of only pruning
when savings are meaningful."
```

---

## Task 11: Tiered Pruning (Gradual Degradation Instead of Cliff)

**Why:** Our pruning is binary: full content OR metadata-only placeholder. The expert recommended 3 tiers for gradual degradation — medium-age results keep a compressed form (first 20 + last 5 lines) before being fully pruned. This preserves more context in the critical "middle zone" between fresh and stale.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt` (pruneOldToolResults)
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/context/ContextManagerTest.kt`

- [ ] **Step 1: Write failing test — medium-age results are compressed, not fully pruned**

```kotlin
// ContextManagerTest.kt
@Test
fun `pruneOldToolResults uses tiered compression - compressed tier keeps head and tail`() {
    val cm = ContextManager(maxInputTokens = 10000, tMaxRatio = 0.90, tRetainedRatio = 0.50)
    cm.addMessage(ChatMessage(role = "system", content = "System"))

    // Add a large tool result
    val largeContent = (1..100).joinToString("\n") { "Line $it: code content here for testing" }
    cm.addMessage(ChatMessage(
        role = "assistant", content = null,
        toolCalls = listOf(ToolCall(id = "c1", type = "function",
            function = FunctionCall(name = "read_file", arguments = """{"path": "/src/Big.kt"}""")))
    ))
    cm.addMessage(ChatMessage(
        role = "tool",
        content = "<external_data>\n$largeContent\n</external_data>",
        toolCallId = "c1"
    ))

    // Pad to push c1 out of full protection but within compressed tier
    for (i in 1..20) {
        cm.addMessage(ChatMessage(role = "user", content = "Pad $i ${"X".repeat(200)}"))
        cm.addMessage(ChatMessage(role = "assistant", content = "Reply $i ${"Y".repeat(200)}"))
    }

    // Use protection windows that put c1 in the compressed tier:
    // fullProtection = 500 (c1 is outside this)
    // compressedProtection = 5000 (c1 is inside this)
    cm.pruneOldToolResults(protectedTokens = 500, compressedProtectionTokens = 5000)

    val toolMsg = cm.getMessages().first { it.toolCallId == "c1" }
    val content = toolMsg.content!!

    // Should contain head lines
    assertTrue(content.contains("Line 1:"), "Compressed tier should preserve first lines")
    // Should contain tail lines
    assertTrue(content.contains("Line 100:") || content.contains("Line 99:"),
        "Compressed tier should preserve last lines")
    // Should NOT be the full 100 lines
    assertFalse(content.contains("Line 50:"), "Compressed tier should NOT have middle lines")
    // Should have a truncation marker
    assertTrue(content.contains("compressed") || content.contains("lines omitted"),
        "Compressed tier should indicate truncation")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*ContextManagerTest.pruneOldToolResults uses tiered*" -x verifyPlugin`
Expected: FAIL — current pruneOldToolResults has no compressed tier

- [ ] **Step 3: Implement tiered pruning**

Update `pruneOldToolResults()` to accept a second threshold and support 3 tiers:

```kotlin
/**
 * Phase 1 compression: tiered pruning of old tool results.
 *
 * Three tiers (from most to least preserved):
 * 1. FULL — within fullProtection window, kept intact
 * 2. COMPRESSED — within compressedProtection window, keep first 20 + last 5 lines
 * 3. METADATA — beyond both windows, replaced with rich placeholder (tool name, args, preview)
 *
 * Both protected tool types and minimum savings checks apply at all tiers.
 */
fun pruneOldToolResults(
    protectedTokens: Int = 40_000,
    compressedProtectionTokens: Int = 60_000
) {
    var fullProtected = 0
    var compressedProtected = 0

    for (i in messages.indices.reversed()) {
        val msg = messages[i]
        if (msg.role != "tool") continue
        val msgTokens = TokenEstimator.estimate(listOf(msg))

        // Tier 1: FULL — within full protection window
        if (fullProtected + msgTokens <= protectedTokens) {
            fullProtected += msgTokens
            continue
        }

        // Skip small results and protected tools at all tiers
        if (msgTokens < PRUNE_MINIMUM_TOKENS) continue
        val toolCallId = msg.toolCallId
        val meta = findToolCallMetadata(i, toolCallId)
        if (meta != null && meta.toolName in PROTECTED_TOOLS) {
            fullProtected += msgTokens
            continue
        }

        // Tier 2: COMPRESSED — within compressed protection window
        if (compressedProtected + msgTokens <= compressedProtectionTokens) {
            compressedProtected += msgTokens
            val compressed = compressToolResult(msg.content, meta)
            messages[i] = ChatMessage(
                role = "tool",
                content = "<external_data>$compressed</external_data>",
                toolCallId = toolCallId
            )
            continue
        }

        // Tier 3: METADATA — full replacement with rich placeholder
        val richPlaceholder = buildRichPlaceholder(msg.content, i, toolCallId)
        messages[i] = ChatMessage(
            role = "tool",
            content = "<external_data>$richPlaceholder</external_data>",
            toolCallId = toolCallId
        )
    }
    totalTokens = TokenEstimator.estimate(getMessages())
}

/**
 * Compress a tool result to first 20 + last 5 lines (Tier 2).
 * Preserves enough context for the LLM to decide if re-reading is needed.
 */
private fun compressToolResult(content: String?, meta: ToolCallMeta?): String {
    val raw = (content ?: "")
        .removePrefix("<external_data>").removePrefix("\n")
        .removeSuffix("</external_data>").removeSuffix("\n")
    val lines = raw.lines()

    if (lines.size <= 30) return raw // Small enough to keep entirely

    val head = lines.take(20).joinToString("\n")
    val tail = lines.takeLast(5).joinToString("\n")
    val omitted = lines.size - 25

    return buildString {
        appendLine("[Compressed tool result — ${lines.size} lines, showing first 20 + last 5]")
        if (meta != null) appendLine("Tool: ${meta.toolName}")
        appendLine(head)
        appendLine("\n[... $omitted lines omitted ...]")
        appendLine(tail)
    }.trimEnd()
}
```

- [ ] **Step 4: Run tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*ContextManagerTest*" -x verifyPlugin`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/context/ContextManagerTest.kt
git commit -m "feat(agent): tiered pruning — gradual degradation instead of cliff edge

Three tiers: FULL (40K protection), COMPRESSED (60K, first 20 + last 5 lines),
METADATA (rich placeholder). Medium-age tool results now keep head+tail instead
of being fully replaced. Reduces information loss during long sessions."
```

---

## Task 12: Facts Store (Structured Truth That Survives Compression)

**Why:** This is the most impactful architectural change. The fundamental problem with lossy compression is that the LLM's knowledge is tied to the conversation history — compress the history, lose the knowledge. A Facts Store decouples verified findings from the message stream. It's an append-only, compression-proof log that records what the agent discovered, what it changed, and what files it touched. Injected as an anchored message (like planAnchor), it survives all compression events.

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/FactsStore.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/context/FactsStoreTest.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt` (add factsAnchor)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt` (record facts from tool results)

- [ ] **Step 1: Write FactsStore tests**

```kotlin
// FactsStoreTest.kt
package com.workflow.orchestrator.agent.context

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FactsStoreTest {

    @Test
    fun `record and retrieve facts`() {
        val store = FactsStore()
        store.record(Fact(FactType.FILE_READ, "/src/Auth.kt", "245 lines, class AuthService extends BaseService", 1))
        store.record(Fact(FactType.EDIT_MADE, "/src/Auth.kt", "Line 47: changed SQL query to use parameterized binding", 3))

        assertEquals(2, store.size)
        val context = store.toContextString()
        assertTrue(context.contains("FILE_READ"))
        assertTrue(context.contains("Auth.kt"))
        assertTrue(context.contains("parameterized binding"))
    }

    @Test
    fun `toContextString caps at maxFacts`() {
        val store = FactsStore(maxFacts = 5)
        for (i in 1..10) {
            store.record(Fact(FactType.FILE_READ, "/src/File$i.kt", "Content $i", i))
        }

        // Should keep only the last 5 facts
        val context = store.toContextString()
        assertFalse(context.contains("File1.kt"), "Oldest facts should be dropped")
        assertTrue(context.contains("File10.kt"), "Most recent facts should be kept")
    }

    @Test
    fun `toContextString returns empty string when no facts`() {
        val store = FactsStore()
        assertEquals("", store.toContextString())
    }

    @Test
    fun `facts are deduped by path and type`() {
        val store = FactsStore()
        store.record(Fact(FactType.FILE_READ, "/src/Auth.kt", "First read: 245 lines", 1))
        store.record(Fact(FactType.FILE_READ, "/src/Auth.kt", "Second read: 250 lines (after edit)", 5))

        // Same path + same type should keep only the latest
        assertEquals(1, store.size)
        val context = store.toContextString()
        assertTrue(context.contains("250 lines"), "Should keep latest fact")
        assertFalse(context.contains("245 lines"), "Should drop older duplicate")
    }

    @Test
    fun `different fact types for same path are kept`() {
        val store = FactsStore()
        store.record(Fact(FactType.FILE_READ, "/src/Auth.kt", "245 lines", 1))
        store.record(Fact(FactType.EDIT_MADE, "/src/Auth.kt", "Fixed SQL injection line 47", 3))

        assertEquals(2, store.size) // Different types, both kept
    }

    @Test
    fun `token estimate is reasonable`() {
        val store = FactsStore()
        store.record(Fact(FactType.FILE_READ, "/src/Auth.kt", "245 lines, class AuthService", 1))
        assertTrue(store.estimateTokens() > 0)
        assertTrue(store.estimateTokens() < 100) // Single fact shouldn't be huge
    }
}
```

- [ ] **Step 2: Run test to verify it fails (FactsStore doesn't exist)**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*FactsStoreTest*" -x verifyPlugin`
Expected: FAIL — class not found

- [ ] **Step 3: Implement FactsStore.kt**

```kotlin
package com.workflow.orchestrator.agent.context

/**
 * Structured, append-only store of verified facts discovered during the agent session.
 *
 * Decouples the LLM's knowledge from the conversation history. Facts survive all
 * compression events because they're injected as a dedicated anchor (like planAnchor).
 *
 * Tools record facts automatically:
 * - read_file → FILE_READ: "Auth.kt has 245 lines, class AuthService"
 * - edit_file → EDIT_MADE: "Auth.kt line 47: changed SQL to parameterized"
 * - search_code → CODE_PATTERN: "SQL injection pattern found in 3 files"
 * - run_command → COMMAND_RESULT: "Tests: 42 passed, 3 failed"
 * - diagnostics → ERROR_FOUND: "NPE at Auth.kt:47"
 *
 * Facts are deduped by (type, path) — re-reading a file updates the fact, not duplicates it.
 * Capped at [maxFacts] entries. Oldest facts beyond the cap are dropped.
 */
class FactsStore(private val maxFacts: Int = 50) {

    private val facts = mutableListOf<Fact>()

    val size: Int get() = facts.size

    /**
     * Record a new fact. Deduplicates by (type, path) — same type + same path
     * replaces the older fact with the newer one.
     */
    fun record(fact: Fact) {
        // Dedup: if same type + path exists, replace it
        if (fact.path != null) {
            facts.removeAll { it.type == fact.type && it.path == fact.path }
        }
        facts.add(fact)
        // Cap at maxFacts — drop oldest
        while (facts.size > maxFacts) {
            facts.removeAt(0)
        }
    }

    /**
     * Generate a compact context string for injection into the LLM's context.
     * Returns empty string if no facts recorded.
     */
    fun toContextString(): String {
        if (facts.isEmpty()) return ""
        return buildString {
            appendLine("<agent_facts>")
            appendLine("Verified facts from this session (compression-proof):")
            for (fact in facts) {
                val pathStr = if (fact.path != null) " ${fact.path}" else ""
                appendLine("- [${fact.type}]$pathStr: ${fact.content.take(200)}")
            }
            appendLine("</agent_facts>")
        }.trimEnd()
    }

    /** Estimate token cost of the facts context string. */
    fun estimateTokens(): Int {
        val contextStr = toContextString()
        return if (contextStr.isEmpty()) 0 else TokenEstimator.estimate(contextStr)
    }

    /** Clear all facts (for session reset). */
    fun clear() {
        facts.clear()
    }
}

/** Types of facts the agent can record. */
enum class FactType {
    FILE_READ,       // File was read — record structure summary
    EDIT_MADE,       // File was edited — record what changed
    CODE_PATTERN,    // Pattern found via search — record pattern + locations
    ERROR_FOUND,     // Error/issue discovered — record error + location
    COMMAND_RESULT,  // Command executed — record outcome
    DISCOVERY,       // General finding — record insight
}

/** A single verified fact. */
data class Fact(
    val type: FactType,
    val path: String?,       // File path (null for general facts)
    val content: String,     // Concise description of the fact
    val iteration: Int       // Which iteration discovered this
)
```

- [ ] **Step 4: Run FactsStore tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*FactsStoreTest*" -x verifyPlugin`
Expected: PASS

- [ ] **Step 5: Add factsAnchor to ContextManager**

In `ContextManager.kt`, add a new anchor slot:

```kotlin
/** Dedicated facts anchor — verified findings, survives compression. */
private var factsAnchor: ChatMessage? = null
var factsStore: FactsStore? = null

/** Update the facts anchor from the facts store. Call after recording new facts. */
fun updateFactsAnchor() {
    val store = factsStore ?: return
    val contextStr = store.toContextString()
    factsAnchor = if (contextStr.isNotEmpty()) {
        ChatMessage(role = "system", content = contextStr)
    } else null
    totalTokens = TokenEstimator.estimate(getMessages())
}
```

Add `factsAnchor` to `getMessages()` (after mentionAnchor):
```kotlin
fun getMessages(): List<ChatMessage> {
    val result = mutableListOf<ChatMessage>()
    if (anchoredSummaries.isNotEmpty()) {
        result.add(ChatMessage(role = "system", content = anchoredSummaries.joinToString("\n\n")))
    }
    planAnchor?.let { result.add(it) }
    skillAnchor?.let { result.add(it) }
    mentionAnchor?.let { result.add(it) }
    factsAnchor?.let { result.add(it) }  // NEW
    result.addAll(messages)
    return result
}
```

Add to `reset()`:
```kotlin
fun reset() {
    messages.clear()
    anchoredSummaries.clear()
    planAnchor = null
    skillAnchor = null
    mentionAnchor = null
    factsAnchor = null   // NEW
    factsStore?.clear()  // NEW
    totalTokens = 0
}
```

- [ ] **Step 6: Wire fact recording into SingleAgentSession tool result processing**

In `SingleAgentSession.kt`, after tool results are added to context (around line 583-587), add fact recording:

```kotlin
// After: contextManager.addToolResult(toolCallId, content, summary)
// Record facts from tool results for compression-proof knowledge retention
recordFactFromToolResult(toolName, toolArgs, content, summary, iteration)

// ...

/** Extract and record a structured fact from a tool result. */
private fun recordFactFromToolResult(
    toolName: String,
    toolArgs: String,
    content: String,
    summary: String,
    iteration: Int
) {
    val factsStore = contextManager.factsStore ?: return
    val pathRegex = Regex(""""path"\s*:\s*"([^"]+)"""")
    val filePath = pathRegex.find(toolArgs)?.groupValues?.get(1)

    when (toolName) {
        "read_file" -> {
            if (filePath != null) {
                val lineCount = content.lines().size
                val firstLine = content.lineSequence().firstOrNull()?.take(80) ?: ""
                factsStore.record(Fact(
                    FactType.FILE_READ, filePath,
                    "$lineCount lines. Starts with: $firstLine",
                    iteration
                ))
            }
        }
        "edit_file" -> {
            if (filePath != null) {
                factsStore.record(Fact(
                    FactType.EDIT_MADE, filePath,
                    summary.take(200),
                    iteration
                ))
            }
        }
        "search_code", "glob_files", "find_references", "find_definition" -> {
            factsStore.record(Fact(
                FactType.CODE_PATTERN, filePath,
                summary.take(200),
                iteration
            ))
        }
        "run_command", "run_tests" -> {
            factsStore.record(Fact(
                FactType.COMMAND_RESULT, null,
                summary.take(200),
                iteration
            ))
        }
        "diagnostics", "run_inspections" -> {
            if (content.contains("error") || content.contains("warning")) {
                factsStore.record(Fact(
                    FactType.ERROR_FOUND, filePath,
                    summary.take(200),
                    iteration
                ))
            }
        }
    }
    contextManager.updateFactsAnchor()
}
```

- [ ] **Step 7: Initialize FactsStore in session setup**

In `SingleAgentSession.execute()` (or the session initialization), add:

```kotlin
val factsStore = FactsStore(maxFacts = 50)
contextManager.factsStore = factsStore
```

- [ ] **Step 8: Run all tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*ContextManager*" --tests "*FactsStore*" -x verifyPlugin`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/FactsStore.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/context/FactsStoreTest.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt
git commit -m "feat(agent): FactsStore — compression-proof structured knowledge retention

Append-only store of verified facts (FILE_READ, EDIT_MADE, CODE_PATTERN, ERROR_FOUND,
COMMAND_RESULT). Injected as a dedicated anchor that survives all compression events.
Tools auto-record facts. Deduped by (type, path). Capped at 50 entries.
Decouples the LLM's verified knowledge from the lossy conversation history."
```

---

## Task 13: Agent-Controlled Compression via think Tool

**Why:** OpenCode lets the LLM decide WHEN to compress via a `/compress` tool. While we won't add a full compress tool (our automatic system handles the mechanics), we can enhance the existing `think` tool to let the LLM checkpoint its understanding before compression happens. When the LLM uses `think` to reason about findings, those thoughts can be captured as facts — making critical reasoning compression-proof.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt`

- [ ] **Step 1: Update think tool to optionally record facts**

In `SingleAgentSession.kt`, where the `think` tool result is processed (it's a no-op tool), add fact extraction from the thinking content:

```kotlin
// When processing think tool result:
if (toolName == "think") {
    val thinkContent = toolCall.function.arguments
    // Extract any file paths or key findings mentioned in thinking
    val factsStore = contextManager.factsStore
    if (factsStore != null && thinkContent.length > 100) {
        factsStore.record(Fact(
            FactType.DISCOVERY, null,
            "Agent reasoning: ${thinkContent.take(300)}",
            iteration
        ))
        contextManager.updateFactsAnchor()
    }
}
```

- [ ] **Step 2: Update system prompt to mention fact recording**

In `PromptAssembler.kt`, update the `CONTEXT_MANAGEMENT_RULES` to mention the facts system:

```kotlin
val CONTEXT_MANAGEMENT_RULES = """
    <context_management>
    Your conversation history may be compressed during long tasks to stay within the context window.
    When this happens:
    - Old tool results are replaced with metadata placeholders (tool name, args, preview)
    - Earlier messages may be summarized — details could be approximate
    - ALWAYS re-read a file before editing it, even if you believe you know its contents
    - If a tool result shows "[Tool result pruned]", use the original tool to re-read
    - Treat information from compressed summaries as a starting point — verify before acting on specifics
    - File paths in summaries are reliable; line numbers and code snippets may be stale

    Your key findings are automatically preserved in a compression-proof <agent_facts> section.
    This section tracks files you've read, edits you've made, errors you've found, and commands you've run.
    After compression, use <agent_facts> as your source of truth for what you've done and discovered.
    Use the think tool to record important reasoning before long sequences of tool calls.
    </context_management>
""".trimIndent()
```

- [ ] **Step 3: Run compile check**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:compileKotlin -x verifyPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt
git commit -m "feat(agent): think tool records facts + system prompt mentions facts system

Think tool now records agent reasoning as DISCOVERY facts (compression-proof).
System prompt updated to inform LLM about the <agent_facts> section and how to use it."
```

---

## Task 14: Update Documentation & Run Full Test Suite

**Files:**
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Update agent/CLAUDE.md context management section**

Update the "Context Management" section to reflect all new features:

```markdown
## Context Management

- **Tool results**: Full content in context (2000 lines / 50KB cap via ToolOutputStore). Full content saved to disk for re-reads after pruning.
- **Facts Store**: Compression-proof append-only log of verified findings (FILE_READ, EDIT_MADE, CODE_PATTERN, ERROR_FOUND, COMMAND_RESULT, DISCOVERY). Injected as `factsAnchor`. Deduped by (type, path), capped at 50 entries. Think tool also records DISCOVERY facts.
- **Phase 0 (smart pruning)**: Deduplication (keep latest file read, respects edit boundaries), error purging (truncate failed tool args after 4 turns), write superseding (compact writes confirmed by subsequent reads). Zero information loss.
- **Phase 1 (tiered tool result pruning)**: Three tiers:
  - FULL: within 40K protection window — kept intact
  - COMPRESSED: within 60K window — first 20 + last 5 lines kept
  - METADATA: beyond both — rich placeholder (tool name, args, preview, disk path, recovery hint)
  - Protected tools (agent, delegate_task, create_plan, etc.) are NEVER pruned
  - Minimum savings threshold: skip results under 200 tokens
- **Phase 2 (summarization)**: Structured LLM summary (Goal/Discoveries/Accomplished/Files template) with [APPROX] markers. Fallback summarizer preserves first 10 lines of tool results (8K char cap).
- **Compression boundary**: After any compression, a `[CONTEXT COMPRESSED]` marker warns LLM earlier content is approximate.
- **Compression trigger**: 93% of effective budget (auto-compress in addMessage)
- **Budget thresholds**: OK (<80%), COMPRESS (80-88%), NUDGE (88-93%), STRONG_NUDGE (93-97%), TERMINATE (>97%)
- **Middle-truncation**: Command and git output keeps first 60% + last 40%, truncating verbose middle.
- **System messages**: Old LoopGuard/budget warnings compressible, capped at 2 active warnings
- **Orphan protection**: When compression drops an assistant tool_call, its tool result is also dropped
- **Re-read tracking**: Cleared after pruning events so agent can re-read pruned files
```

- [ ] **Step 2: Run full agent test suite**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:clean :agent:test --rerun --no-build-cache -x verifyPlugin`
Expected: All ~470+ tests PASS

- [ ] **Step 3: Build plugin to verify no regressions**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew buildPlugin -x verifyPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit documentation**

```bash
git add agent/CLAUDE.md
git commit -m "docs(agent): update context management documentation for anti-hallucination overhaul"
```

---

## Summary of Changes

| What Changed | Before | After | Why | Task |
|---|---|---|---|---|
| COMPRESS threshold | 60% | 80% | Stop destroying usable context prematurely | 1 |
| NUDGE threshold | 75% | 88% | Aligned with research | 1 |
| STRONG_NUDGE threshold | 85% | 93% | Aligned with research | 1 |
| TERMINATE threshold | 95% | 97% | More emergency buffer | 1 |
| tMax (auto-compress) | 85% | 93% | Match STRONG_NUDGE | 1 |
| tRetained (compress target) | 60% | 70% | Keep more context after compression | 1 |
| Protection window | 30K tokens | 40K (full) + 60K (compressed) | OpenCode parity + tiered degradation | 2, 11 |
| Pruning placeholder | `"[Old tool result cleared]"` | Tool name, args, 5-line preview, disk path, recovery hint | LLM can decide to re-read | 2 |
| Fallback summarizer | `"Tool result (N chars)"`, 2500 cap | First 10 lines of tool results, file paths section, 8000 cap | Preserves actionable information | 3 |
| Compression awareness | None | Boundary markers + system prompt rules | LLM knows to self-verify | 4, 5 |
| Smart pruning | None | Dedup + error purge + write supersede | Eliminates redundancy before lossy compression | 6 |
| Orphan protection | None | Tool results dropped with their assistant message | Prevents structural corruption | 7 |
| System warnings | Unlimited accumulation | Capped at 2 | Reduces noise in context | 7 |
| Re-read tracking | Persists after pruning | Cleared after pruning | Allows re-reading pruned files | 7 |
| Protected tool types | None | agent, delegate_task, create_plan, etc. never pruned | Irreplaceable work (subagent results) | 8 |
| Command/git output truncation | Head-only (first 30K chars) | Middle-truncation (60% head + 40% tail) | Preserves end-of-output errors and summaries | 9 |
| Minimum savings threshold | None | Skip pruning results under 200 tokens | Micro-pruning not worth info loss | 10 |
| Tiered pruning | Binary (full or placeholder) | 3 tiers: full → compressed (20+5 lines) → metadata | Gradual degradation instead of cliff | 11 |
| Facts Store | None | Compression-proof structured knowledge log (50 entries) | Decouples truth from conversation history | 12 |
| Think tool facts | No-op only | Records DISCOVERY facts for compression-proof retention | Critical reasoning survives compression | 13 |
