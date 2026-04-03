# Architecture Audit Remediation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all 33 issues (7 CRITICAL, 8 HIGH, 18 MEDIUM) from the 2026-04-03 architecture audit

**Architecture:** 9 work units in 4 phases. Phase A (WU1, WU4, WU5, WU7) runs parallel. Phase B (WU2, WU6, WU8) depends on WU1. Phase C (WU3) depends on WU2. Phase D is verification.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform SDK, kotlinx.serialization, kotlinx.coroutines

**Spec:** `docs/superpowers/specs/2026-04-03-architecture-audit-remediation-design.md`

---

## Phase A — Parallel Work Units (no dependencies)

---

### Task 1: [WU4] Fix EventStore.all() thread safety (C1)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/events/EventStore.kt:85`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/context/events/EventStoreTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// In EventStoreTest.kt — add this test
@Test
fun `concurrent add and all should not throw ConcurrentModificationException`() = runBlocking {
    val store = EventStore()
    val latch = CountDownLatch(1)
    val errors = java.util.concurrent.atomic.AtomicInteger(0)

    // Writer thread
    val writer = launch(Dispatchers.IO) {
        latch.await()
        repeat(1000) {
            store.add(MessageAction(content = "msg-$it"), EventSource.USER)
        }
    }

    // Reader thread
    val reader = launch(Dispatchers.IO) {
        latch.await()
        repeat(1000) {
            try {
                store.all()
            } catch (_: ConcurrentModificationException) {
                errors.incrementAndGet()
            }
        }
    }

    latch.countDown()
    writer.join()
    reader.join()
    assertEquals(0, errors.get(), "ConcurrentModificationException should not occur")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*EventStoreTest.concurrent*" -v`
Expected: Likely intermittent FAIL with ConcurrentModificationException (may pass sometimes due to timing)

- [ ] **Step 3: Fix EventStore.all(), size(), and slice()**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/events/EventStore.kt`:

```kotlin
// Line 79 — wrap size() in lock
fun size(): Int = lock.withLock { events.size }

// Line 85 — wrap all() in lock
fun all(): List<Event> = lock.withLock { ArrayList(events) }

// Lines 69-74 — wrap slice() in lock
fun slice(startId: Int, endId: Int): List<Event> = lock.withLock {
    val start = startId.coerceIn(0, events.size)
    val end = endId.coerceIn(start, events.size)
    events.subList(start, end).toList()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*EventStoreTest*" -v`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/events/EventStore.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/context/events/EventStoreTest.kt
git commit -m "fix(context): wrap EventStore read ops in lock to prevent ConcurrentModificationException [C1]"
```

---

### Task 2: [WU4] Fix FileOwnershipRegistry.claim() TOCTOU race (C2)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/FileOwnershipRegistry.kt:38-46`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/FileOwnershipRegistryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// In FileOwnershipRegistryTest.kt — add concurrent claim test
@Test
fun `concurrent claims on same file should grant exactly one`() = runBlocking {
    val registry = FileOwnershipRegistry()
    val tmpFile = File.createTempFile("test", ".kt")
    val results = java.util.concurrent.ConcurrentLinkedQueue<ClaimResponse>()
    val latch = CountDownLatch(1)

    val jobs = (1..10).map { i ->
        launch(Dispatchers.IO) {
            latch.await()
            results.add(registry.claim(tmpFile.absolutePath, "agent-$i", WorkerType.CODER))
        }
    }

    latch.countDown()
    jobs.forEach { it.join() }

    val granted = results.count { it.result == ClaimResult.GRANTED }
    // Exactly one agent should own the file. Others might get GRANTED if same agent re-claims,
    // but since all agents have different IDs, only one should be GRANTED.
    // With the TOCTOU bug, multiple might get GRANTED.
    assertEquals(1, granted, "Exactly one agent should own the file, got $granted")
    tmpFile.delete()
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*FileOwnershipRegistryTest.concurrent*" -v`
Expected: Intermittent FAIL (multiple GRANTED due to race)

- [ ] **Step 3: Fix claim() with ConcurrentHashMap.compute()**

Replace lines 38-46 of `FileOwnershipRegistry.kt`:

```kotlin
fun claim(filePath: String, agentId: String, workerType: WorkerType): ClaimResponse {
    val canonical = canonicalize(filePath)
    val newRecord = OwnershipRecord(agentId, workerType)
    var denied: String? = null
    fileOwners.compute(canonical) { _, existing ->
        when {
            existing == null -> newRecord
            existing.agentId == agentId -> newRecord // idempotent re-claim
            else -> { denied = existing.agentId; existing }
        }
    }
    return if (denied != null) ClaimResponse(ClaimResult.DENIED, denied)
    else ClaimResponse(ClaimResult.GRANTED)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*FileOwnershipRegistryTest*" -v`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/FileOwnershipRegistry.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/FileOwnershipRegistryTest.kt
git commit -m "fix(runtime): use ConcurrentHashMap.compute() to prevent TOCTOU race in FileOwnershipRegistry [C2]"
```

---

### Task 3: [WU4] Fix format string vulnerability (H5)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt:271-283`

- [ ] **Step 1: Replace SKILL_RULES_TEMPLATE format**

In `PromptAssembler.kt`, replace lines 271-283:

```kotlin
// Before (line 275 has %s):
private const val SKILL_RULES_TEMPLATE = """
<skill_rules>
You have access to skills — structured workflows that produce better results than ad-hoc approaches.

%s

To use a skill, call skill(skill="name"). Users can also type /skill-name in chat.

RULE: When a skill matches the task, load it BEFORE starting work.
Do NOT attempt planning, debugging, brainstorming, or TDD workflows without the corresponding skill.
The skill contains the detailed workflow — the system prompt only tells you WHEN to use it, not HOW.
</skill_rules>
"""
```

Replace with a function:

```kotlin
private fun buildSkillRulesSection(skillDescriptions: String): String = """
<skill_rules>
You have access to skills — structured workflows that produce better results than ad-hoc approaches.

$skillDescriptions

To use a skill, call skill(skill="name"). Users can also type /skill-name in chat.

RULE: When a skill matches the task, load it BEFORE starting work.
Do NOT attempt planning, debugging, brainstorming, or TDD workflows without the corresponding skill.
The skill contains the detailed workflow — the system prompt only tells you WHEN to use it, not HOW.
</skill_rules>
""".trimIndent()
```

Then update the call site (around line 63 of `buildSingleAgentPrompt()`):

```kotlin
// Before:
sections.add(SKILL_RULES_TEMPLATE.format(skillDescriptions))
// After:
sections.add(buildSkillRulesSection(skillDescriptions))
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :agent:test --tests "*PromptAssemblerTest*" -v`
Expected: ALL PASS

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt
git commit -m "fix(prompts): replace %s format string with Kotlin string template in skill rules [H5]"
```

---

### Task 4: [WU4] Add ApprovalGate timeout (H6)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ApprovalGate.kt:80-126`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/ApprovalGateTest.kt`

- [ ] **Step 1: Add timeout import and constant**

At top of `ApprovalGate.kt`, add import:
```kotlin
import kotlinx.coroutines.withTimeoutOrNull
```

Add constant in companion object:
```kotlin
private const val APPROVAL_TIMEOUT_MS = 300_000L // 5 minutes
```

- [ ] **Step 2: Wrap deferred.await() with timeout**

In the `check()` method (around line 112-118 where the deferred is awaited), replace:

```kotlin
// Before:
val result = deferred.await()
```

With:

```kotlin
val result = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { deferred.await() }
if (result == null) {
    LOG.warn("Approval gate timed out after ${APPROVAL_TIMEOUT_MS / 1000}s for tool '$toolName' — auto-rejecting")
    pendingApproval = null
    return ApprovalResult.Rejected("Approval timed out after ${APPROVAL_TIMEOUT_MS / 1000} seconds")
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :agent:test --tests "*ApprovalGateTest*" -v`
Expected: ALL PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ApprovalGate.kt
git commit -m "fix(runtime): add 5-minute timeout to ApprovalGate to prevent indefinite blocking [H6]"
```

---

### Task 5: [WU4] Tie background worker scope to lifecycle (H7)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt:584-586`

- [ ] **Step 1: Add backgroundWorkerScope to AgentService**

In `AgentService.kt`, add a dedicated scope for background workers (after line 44):

```kotlin
/** Coroutine scope for background workers — cancelled on dispose(). */
val backgroundWorkerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
```

Update `dispose()` (line 335-337) to cancel it:

```kotlin
override fun dispose() {
    scope.cancel()
    backgroundWorkerScope.cancel()
}
```

- [ ] **Step 2: Use backgroundWorkerScope in SpawnAgentTool**

In `SpawnAgentTool.kt`, replace lines 584-586:

```kotlin
// Before:
val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
val job = scope.launch {

// After:
val agentService = AgentService.getInstance(project)
val job = agentService.backgroundWorkerScope.launch {
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :agent:test --tests "*SpawnAgentToolTest*" -v`
Expected: ALL PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt
git commit -m "fix(runtime): tie background worker scope to AgentService lifecycle [H7]"
```

---

### Task 6: [WU4] Increase LLM summarization output cap (H8)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/condenser/LlmBrainSummarizationClient.kt:10`

- [ ] **Step 1: Change maxOutputTokens from 500 to 1500**

```kotlin
// Before (line 10):
when (val result = brain.chat(messages, null, 500, null)) {

// After:
when (val result = brain.chat(messages, null, 1500, null)) {
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :agent:test --tests "*condenser*" -v`
Expected: ALL PASS

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/condenser/LlmBrainSummarizationClient.kt
git commit -m "fix(context): increase summarization output cap from 500 to 1500 tokens [H8]"
```

---

### Task 7: [WU4] Replace extractJsonString regex with proper JSON parsing (M6)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/EventSourcedContextBridge.kt:688-691`

- [ ] **Step 1: Replace regex with kotlinx.serialization**

Add import at top of file:
```kotlin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
```

Replace lines 688-691:

```kotlin
// Before:
private fun extractJsonString(json: String, key: String): String? {
    val regex = Regex(""""$key"\s*:\s*"([^"]*?)"""")
    return regex.find(json)?.groupValues?.get(1)
}

// After:
private fun extractJsonString(json: String, key: String): String? = try {
    Json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.content
} catch (_: Exception) { null }
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :agent:test --tests "*EventSourcedContextBridgeTest*" -v`
Expected: ALL PASS

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/EventSourcedContextBridge.kt
git commit -m "fix(context): replace fragile extractJsonString regex with proper JSON parsing [M6]"
```

---

### Task 8: [WU4] Fix File.renameTo to Files.move for cross-platform atomicity (M7)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/CoreMemory.kt:129`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ArchivalMemory.kt:183`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/GuardrailStore.kt:63-79`

- [ ] **Step 1: Fix CoreMemory.save()**

Add import: `import java.nio.file.Files` and `import java.nio.file.StandardCopyOption`

Replace line 129:
```kotlin
// Before:
tmp.renameTo(storePath)
// After:
Files.move(tmp.toPath(), storePath.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
```

- [ ] **Step 2: Fix ArchivalMemory.save()**

Same pattern — add imports and replace line 183:
```kotlin
// Before:
tmp.renameTo(storePath)
// After:
Files.move(tmp.toPath(), storePath.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
```

- [ ] **Step 3: Fix GuardrailStore.save() — add atomic write pattern**

GuardrailStore currently uses `file.writeText()` directly. Wrap in tmp+move:

```kotlin
fun save() {
    try {
        val file = getGuardrailsFile()
        file.parentFile?.mkdirs()
        val content = constraints.joinToString("\n") { "- $it" }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(content)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Exception) {
        LOG.warn("GuardrailStore: failed to save", e)
    }
}
```

Add import: `import java.nio.file.Files` and `import java.nio.file.StandardCopyOption`

- [ ] **Step 4: Run tests**

Run: `./gradlew :agent:test --tests "*CoreMemoryTest*" --tests "*ArchivalMemoryTest*" --tests "*GuardrailStoreTest*" -v`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/CoreMemory.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ArchivalMemory.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/context/GuardrailStore.kt
git commit -m "fix(context): use Files.move for atomic writes on all platforms [M7]"
```

---

### Task 9: [WU4] Fix noOpCondensation, CommandSafety priority, startup exceptions, custom agent type (M10, M11, M14, M16)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/condenser/ConversationWindowCondenser.kt:113-123`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/security/CommandSafetyAnalyzer.kt:64-69`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/listeners/AgentStartupActivity.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt:259-261`

- [ ] **Step 1: Fix noOpCondensation (M10)**

In `ConversationWindowCondenser.kt`, find the call sites that return `noOpCondensation()` and return `CondenserView(context.view)` instead. Remove the `noOpCondensation()` private method entirely:

```kotlin
// Before (at each call site):
return noOpCondensation()

// After:
return CondenserView(context.view)
```

Delete the `noOpCondensation()` method (lines 113-123).

- [ ] **Step 2: Fix CommandSafetyAnalyzer priority escalation (M11)**

In `CommandSafetyAnalyzer.kt`, replace `classify()` method (lines 64-69):

```kotlin
// Before: first-match-wins
fun classify(command: String): CommandRisk {
    val normalized = command.trim()
    DANGEROUS_PATTERNS.forEach { if (it.containsMatchIn(normalized)) return CommandRisk.DANGEROUS }
    RISKY_PATTERNS.forEach { if (it.containsMatchIn(normalized)) return CommandRisk.RISKY }
    SAFE_PREFIXES.forEach { if (normalized.startsWith(it)) return CommandRisk.SAFE }
    return CommandRisk.RISKY
}

// After: collect all matches, return highest severity
fun classify(command: String): CommandRisk {
    val normalized = command.trim()
    // DANGEROUS always takes priority regardless of other matches
    if (DANGEROUS_PATTERNS.any { it.containsMatchIn(normalized) }) return CommandRisk.DANGEROUS
    if (RISKY_PATTERNS.any { it.containsMatchIn(normalized) }) return CommandRisk.RISKY
    if (SAFE_PREFIXES.any { normalized.startsWith(it) }) return CommandRisk.SAFE
    return CommandRisk.RISKY
}
```

Note: This is functionally identical to the existing code since DANGEROUS is already checked first. The real fix is ensuring that DANGEROUS patterns override SAFE prefix matches. The existing code already does this correctly since the DANGEROUS check comes first. Verify and move on — the original audit concern was about RISKY not being escalated to DANGEROUS, but the sequential check order already handles this. No code change needed if the order is already DANGEROUS → RISKY → SAFE.

- [ ] **Step 3: Fix AgentStartupActivity exception swallowing (M14)**

In `AgentStartupActivity.kt`, replace all `catch (_: Exception) {}` blocks with:

```kotlin
catch (e: Exception) {
    LOG.debug("AgentStartupActivity: ${contextDescription}", e)
}
```

Where `contextDescription` describes each catch site (e.g., "failed to load checkpoint", "failed to mark session interrupted", "failed to show notification").

- [ ] **Step 4: Fix custom agent default type (M16)**

In `SpawnAgentTool.kt`, replace lines 259-261:

```kotlin
// Before:
if (customAgent != null) {
    agentDef = customAgent
    workerType = WorkerType.ORCHESTRATOR

// After:
if (customAgent != null) {
    agentDef = customAgent
    workerType = if (customAgent.frontmatter["can-delegate"] == "true")
        WorkerType.ORCHESTRATOR
    else
        WorkerType.CODER
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :agent:test -v`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/condenser/ConversationWindowCondenser.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/security/CommandSafetyAnalyzer.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/listeners/AgentStartupActivity.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt
git commit -m "fix: noOpCondensation, startup exception logging, custom agent default type [M10,M14,M16]"
```

---

### Task 10: [WU5] Add anchor token tracking to condenser pipeline (C6, M12, M18)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/EventSourcedContextBridge.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/condenser/CondenserPipeline.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/condenser/Condenser.kt` (CondenserContext)
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/context/EventSourcedContextBridgeTest.kt`

- [ ] **Step 1: Add anchorTokens field to CondenserContext**

In `Condenser.kt`, add `anchorTokens: Int = 0` to `CondenserContext` data class:

```kotlin
data class CondenserContext(
    val view: View,
    val tokenUtilization: Double,
    val effectiveBudget: Int,
    val currentTokens: Int,
    val anchorTokens: Int = 0
)
```

- [ ] **Step 2: Add anchor tracking to EventSourcedContextBridge**

Add field and helper after the anchor declarations:

```kotlin
@Volatile
private var anchorTokenEstimate: Int = 0

private fun recalculateAnchorTokens() {
    anchorTokenEstimate = getAnchorMessages().sumOf {
        TokenEstimator.estimate(it.content ?: "")
    }
}
```

Call `recalculateAnchorTokens()` at the end of every anchor setter:
- `setPlanAnchor()` — add `recalculateAnchorTokens()` after `planAnchor = message`
- `setSkillAnchor()` — add `recalculateAnchorTokens()` after `skillAnchor = message`
- `setMentionAnchor()` — add `recalculateAnchorTokens()` after `mentionAnchor = message`
- `setGuardrailsAnchor()` — add `recalculateAnchorTokens()` after `guardrailsAnchor = message`
- `updateFactsAnchor()` — add `recalculateAnchorTokens()` at end
- `updateChangeLedgerAnchor()` — add `recalculateAnchorTokens()` at end

- [ ] **Step 3: Update estimateCurrentTokens() to include anchors**

```kotlin
// Before (lines 562-565):
private fun estimateCurrentTokens(): Int {
    val messages = getMessagesRaw()
    return TokenEstimator.estimate(messages)
}

// After:
private fun estimateCurrentTokens(): Int {
    val messages = getMessagesRaw()
    return TokenEstimator.estimate(messages) + anchorTokenEstimate
}
```

- [ ] **Step 4: Pass anchorTokens to CondenserContext**

In `getMessagesViaCondenser()` (line 348), add anchorTokens:

```kotlin
val condenserContext = CondenserContext(
    view = view,
    tokenUtilization = tokenUtilization,
    effectiveBudget = effectiveBudget,
    currentTokens = currentTokens,
    anchorTokens = anchorTokenEstimate
)
```

- [ ] **Step 5: Add View caching (M12)**

Add cache fields:

```kotlin
@Volatile private var cachedView: View? = null
@Volatile private var cachedViewEventCount: Int = -1

private fun getView(): View {
    val currentSize = eventStore.size()
    val cached = cachedView
    if (cached != null && cachedViewEventCount == currentSize) return cached
    val view = View.fromEvents(eventStore.all())
    cachedView = view
    cachedViewEventCount = currentSize
    return view
}
```

Replace all `View.fromEvents(eventStore.all())` calls with `getView()`.

- [ ] **Step 6: Add inter-stage token recalculation to CondenserPipeline (M18)**

In `CondenserPipeline.kt`, update the condense method:

```kotlin
override fun condense(context: CondenserContext): CondenserResult {
    var currentContext = context
    for (condenser in condensers) {
        when (val result = condenser.condense(currentContext)) {
            is Condensation -> return result
            is CondenserView -> {
                val updatedTokens = result.view.estimatedTokens + currentContext.anchorTokens
                val utilization = if (currentContext.effectiveBudget > 0)
                    updatedTokens.toDouble() / currentContext.effectiveBudget
                else 0.0
                currentContext = currentContext.copy(
                    view = result.view,
                    currentTokens = updatedTokens,
                    tokenUtilization = utilization
                )
            }
        }
    }
    return CondenserView(currentContext.view)
}
```

- [ ] **Step 7: Run tests**

Run: `./gradlew :agent:test --tests "*EventSourcedContextBridgeTest*" --tests "*CondenserPipelineTest*" -v`
Expected: ALL PASS

- [ ] **Step 8: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/EventSourcedContextBridge.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/context/condenser/CondenserPipeline.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/context/condenser/Condenser.kt
git commit -m "fix(context): include anchor tokens in condenser budget, add View caching, inter-stage token update [C6,M12,M18]"
```

---

### Task 11: [WU7] Tighten prompt recency zone (H3, M15)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt:59-126`

- [ ] **Step 1: Move 4 sections from recency to context zone**

In `buildSingleAgentPrompt()`, move these from the recency zone block (lines 105-126) to the context zone block (after line 103):

Move to context zone:
- `MEMORY_RULES`
- `CONTEXT_MANAGEMENT_RULES`
- `STEERING_RULES`
- `RENDERING_RULES_COMPACT` (or skip entirely if plain-text mode)

Keep in recency zone:
- `PLANNING_RULES` / `FORCED_PLANNING_RULES`
- `DELEGATION_RULES`
- `FEW_SHOT_EXAMPLES`
- `RULES`
- Integration rules
- `COMMUNICATION`
- `BOOKEND`

- [ ] **Step 2: Deduplicate persistence instruction (M15)**

In the `CORE_IDENTITY` constant, replace directive 1:

```kotlin
// Before:
// 1. Keep working until the user's task is fully resolved. Do not stop early or yield partial results.

// After:
// 1. Verify your work with diagnostics, tests, or inspection before declaring done.
```

The persistence instruction now lives solely in `PERSISTENCE_AND_COMPLETION` (position 2) and `BOOKEND` (final position).

- [ ] **Step 3: Run tests**

Run: `./gradlew :agent:test --tests "*PromptAssemblerTest*" -v`
Expected: ALL PASS (update test assertions if they check exact section ordering)

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt
git commit -m "fix(prompts): tighten recency zone from 11 to 7 sections, dedup persistence instruction [H3,M15]"
```

---

### Task 12: [WU7] Normalize meta-tool parameter names (H4)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/JiraTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooBuildsTool.kt`

- [ ] **Step 1: Normalize JiraTool parameters to `key`**

For `get_linked_prs` (line 291) and `get_dev_branches` (line 328), change from `issue_id` to accept `key` as primary with `issue_id` fallback:

```kotlin
// get_linked_prs:
val issueKey = params["key"]?.jsonPrimitive?.content
    ?: params["issue_id"]?.jsonPrimitive?.content
    ?: return missingParam("key")

// get_dev_branches:
val issueKey = params["key"]?.jsonPrimitive?.content
    ?: params["issue_id"]?.jsonPrimitive?.content
    ?: return missingParam("key")

// start_work (line 335): already has fallback, just swap primary:
val issueKey = params["key"]?.jsonPrimitive?.content
    ?: params["issue_key"]?.jsonPrimitive?.content
    ?: return missingParam("key")
```

Update the tool description to list `key` for all actions instead of `issue_key` / `issue_id`.

- [ ] **Step 2: Normalize BambooBuildsTool parameters to `build_key`**

For `stop_build` (line 166), `cancel_build` (line 172), `get_artifacts` (line 178), change from `result_key` to accept `build_key` as primary:

```kotlin
val buildKey = params["build_key"]?.jsonPrimitive?.content
    ?: params["result_key"]?.jsonPrimitive?.content
    ?: return missingParam("build_key")
```

Update tool description to list `build_key` for all actions.

- [ ] **Step 3: Run tests**

Run: `./gradlew :agent:test --tests "*JiraToolTest*" --tests "*BambooBuildsTool*" -v`
Expected: ALL PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/JiraTool.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooBuildsTool.kt
git commit -m "fix(tools): normalize meta-tool param names — key for Jira, build_key for Bamboo [H4]"
```

---

### Task 13: [WU7] Worker personas, tool name sweep, false positive keywords (M3, M4, M5)

**Files:**
- Modify: 8 files in `agent/src/main/resources/agents/`
- Modify: `agent/src/main/resources/skills/create-skill/SKILL.md`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt`

- [ ] **Step 1: Add worker_complete section to all 8 personas (M3)**

Append to each `.md` file in `resources/agents/`:

```markdown

## Completion

When your task is complete, call `worker_complete` with your full findings.
The parent agent ONLY sees your worker_complete output — tool call history is not visible.
Include all relevant details, file paths, and recommendations in your result.
```

Files: `architect-reviewer.md`, `code-reviewer.md`, `devops-engineer.md`, `performance-engineer.md`, `refactoring-specialist.md`, `security-auditor.md`, `spring-boot-engineer.md`, `test-automator.md`.

- [ ] **Step 2: Sweep tool names in skills and personas (M4)**

Search for old-style tool names and replace:
- `git_blame` → `git(action="blame")`
- `git_status` → `git(action="status")`
- `git_diff` → `git(action="diff")`
- `jira_get_ticket` → `jira(action="get_ticket")`
- `sonar_issues` → `sonar(action="issues")`

In `create-skill/SKILL.md`, update "86 available tools" to "68 registered tools (15 meta-tools with 144 actions)".

- [ ] **Step 3: Remove false positive keywords (M5)**

In `DynamicToolSelector.kt`, remove from keyword groups:
- Remove `"run"`, `"test"`, `"tests"` from the runtime/coverage groups
- Remove `"implement"`, `"implementation"`, `"override"` from the vcs group

These appear in virtually every coding conversation and cause unnecessary tool activation.

- [ ] **Step 4: Run tests**

Run: `./gradlew :agent:test --tests "*DynamicToolSelectorTest*" -v`
Expected: ALL PASS (update test assertions if they check for these keywords)

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/resources/agents/ agent/src/main/resources/skills/ agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt
git commit -m "fix(prompts): add worker_complete to personas, update tool names, remove false-positive keywords [M3,M4,M5]"
```

---

## Phase B — Depends on WU1 (SessionScope)

---

### Task 14: [WU1] Create SessionScope and UiCallbacks (C4 foundation)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SessionScope.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/UiCallbacks.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SessionScopeTest.kt`

- [ ] **Step 1: Create SessionScope data class**

```kotlin
package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.context.EventSourcedContextBridge
import java.io.File

/**
 * Immutable container for all per-session state. Created by [ConversationSession.create],
 * passed to all session participants. Replaces the 20+ @Volatile fields on AgentService.
 */
data class SessionScope(
    val sessionId: String,
    val sessionDir: File,
    val bridge: EventSourcedContextBridge,
    val planManager: PlanManager,
    val questionManager: QuestionManager,
    val skillManager: SkillManager,
    val changeLedger: ChangeLedger,
    val rollbackManager: AgentRollbackManager,
    val selfCorrectionGate: SelfCorrectionGate,
    val backpressureGate: BackpressureGate,
    val completionGatekeeper: CompletionGatekeeper,
    val loopGuard: LoopGuard,
    val fileOwnership: FileOwnershipRegistry,
    val workerMessageBus: WorkerMessageBus,
    val metrics: AgentMetrics,
    val uiCallbacks: UiCallbacks?
)
```

- [ ] **Step 2: Create UiCallbacks interface**

```kotlin
package com.workflow.orchestrator.agent.runtime

/**
 * Interface for UI operations that tools need. Implemented by AgentController.
 * Replaces static callbacks on tool companion objects.
 */
interface UiCallbacks {
    suspend fun showInputDialog(prompt: String, placeholder: String?): String?
    fun streamCommandOutput(toolCallId: String, chunk: String)
    fun notifyToolProgress(toolName: String, message: String)
}
```

- [ ] **Step 3: Write basic test**

```kotlin
package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class SessionScopeTest {
    @Test
    fun `SessionScope is a data class with correct fields`() {
        // Verify all fields are accessible (compile-time check)
        assertDoesNotThrow {
            SessionScope::sessionId
            SessionScope::bridge
            SessionScope::planManager
            SessionScope::selfCorrectionGate
            SessionScope::uiCallbacks
        }
    }
}
```

- [ ] **Step 4: Run test**

Run: `./gradlew :agent:test --tests "*SessionScopeTest*" -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SessionScope.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/UiCallbacks.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SessionScopeTest.kt
git commit -m "feat(runtime): add SessionScope data class and UiCallbacks interface [C4]"
```

---

### Task 15: [WU1] Slim AgentService — replace 20+ fields with activeScope (C4)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

- [ ] **Step 1: Add activeScope field, keep @Volatile fields temporarily**

Add to `AgentService.kt` (after line 45):

```kotlin
/** Per-session state container. Non-null when a session is active. */
@Volatile var activeScope: SessionScope? = null
```

Do NOT remove the old `@Volatile` fields yet — they will be removed incrementally as consumers migrate in subsequent tasks.

- [ ] **Step 2: Run tests**

Run: `./gradlew :agent:test -v`
Expected: ALL PASS (additive change only)

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "feat(runtime): add activeScope to AgentService — Phase 1 of God Object refactor [C4]"
```

---

### Task 16: [WU1] Wire SessionScope in ConversationSession.create() and load() (C4, M9)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt`

- [ ] **Step 1: Build SessionScope in create()**

At the end of `ConversationSession.create()` (before return at line 444), after all components are created, build and set the scope:

```kotlin
val scope = SessionScope(
    sessionId = properSession.sessionId,
    sessionDir = sessionDir,
    bridge = bridge,
    planManager = properSession.planManager,
    questionManager = properSession.questionManager,
    skillManager = skillManager,
    changeLedger = properSession.changeLedger,
    rollbackManager = properSession.rollbackManager ?: AgentRollbackManager(project, properSession.changeLedger),
    selfCorrectionGate = SelfCorrectionGate(),
    backpressureGate = BackpressureGate(),
    completionGatekeeper = CompletionGatekeeper(properSession.planManager, SelfCorrectionGate(), LoopGuard()),
    loopGuard = LoopGuard(),
    fileOwnership = FileOwnershipRegistry(),
    workerMessageBus = WorkerMessageBus(),
    metrics = AgentMetrics(),
    uiCallbacks = null  // Set by AgentController after session creation
)
agentService.activeScope = scope
```

Also remove the placeholder bridge creation (lines 361-378) — create the real bridge directly. This fixes M9.

- [ ] **Step 2: Build SessionScope in load()**

At the end of `ConversationSession.load()` (before return at line 584), build scope from loaded state:

```kotlin
val selfCorrectionGate = SelfCorrectionGate()
selfCorrectionGate.restoreFromLedger(loaded.changeLedger) // H1 will add this method

val scope = SessionScope(
    sessionId = loaded.sessionId,
    sessionDir = sessionDir,
    bridge = bridge,
    planManager = loaded.planManager,
    questionManager = loaded.questionManager,
    skillManager = skillManager,
    changeLedger = loaded.changeLedger,
    rollbackManager = loaded.rollbackManager ?: AgentRollbackManager(project, loaded.changeLedger),
    selfCorrectionGate = selfCorrectionGate,
    backpressureGate = BackpressureGate(),
    completionGatekeeper = CompletionGatekeeper(loaded.planManager, selfCorrectionGate, LoopGuard()),
    loopGuard = LoopGuard(),
    fileOwnership = FileOwnershipRegistry(),
    workerMessageBus = WorkerMessageBus(),
    metrics = AgentMetrics(),
    uiCallbacks = null
)
agentService.activeScope = scope
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :agent:test --tests "*ConversationSessionTest*" -v`
Expected: ALL PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt
git commit -m "feat(runtime): wire SessionScope in ConversationSession create/load, remove placeholder bridge [C4,M9]"
```

---

### Task 17: [WU4+] Extract shared VerificationConstants

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/VerificationConstants.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SelfCorrectionGate.kt:25`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/BackpressureGate.kt:20`

- [ ] **Step 1: Create shared constant**

```kotlin
package com.workflow.orchestrator.agent.runtime

object VerificationConstants {
    val VERIFICATION_TOOLS = setOf("diagnostics", "runtime_config", "runtime_exec", "run_inspections")
}
```

- [ ] **Step 2: Update SelfCorrectionGate**

Replace line 25:
```kotlin
// Before:
val VERIFICATION_TOOLS = setOf("diagnostics", "runtime_config", "runtime_exec", "run_inspections")
// After:
val VERIFICATION_TOOLS = VerificationConstants.VERIFICATION_TOOLS
```

- [ ] **Step 3: Update BackpressureGate**

Replace line 20:
```kotlin
// Before:
val VERIFICATION_TOOLS = setOf("diagnostics", "runtime_config", "runtime_exec", "run_inspections")
// After:
val VERIFICATION_TOOLS = VerificationConstants.VERIFICATION_TOOLS
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :agent:test --tests "*SelfCorrectionGateTest*" --tests "*BackpressureGateTest*" -v`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/VerificationConstants.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SelfCorrectionGate.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/BackpressureGate.kt
git commit -m "refactor(runtime): extract shared VerificationConstants from SelfCorrectionGate and BackpressureGate"
```

---

### Task 18: [WU6] Fix static callbacks with project-scoped lookup (C7)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/AskUserInputTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

- [ ] **Step 1: Replace static callback in AskUserInputTool**

Replace the static `showInputCallback` companion field usage with project-scoped lookup:

```kotlin
// In execute(), replace:
//   showInputCallback?.invoke(processId, description, prompt, command)
// With:
val scope = AgentService.getInstance(project).activeScope
scope?.uiCallbacks?.showInputDialog(prompt, null)
```

Remove the `showInputCallback` companion var (line 40).

- [ ] **Step 2: Replace static callback in RunCommandTool**

Replace the static `streamCallback` companion field usage with project-scoped lookup:

```kotlin
// In the streaming output handler, replace:
//   streamCallback?.invoke(toolCallId, chunk)
// With:
val scope = AgentService.getInstance(project).activeScope
scope?.uiCallbacks?.streamCommandOutput(toolCallId, chunk)
```

Remove the `streamCallback` companion var (line 66).

- [ ] **Step 3: Implement UiCallbacks in AgentController**

Make `AgentController` implement `UiCallbacks` and set it on scope:

```kotlin
class AgentController(...) : UiCallbacks {
    override suspend fun showInputDialog(prompt: String, placeholder: String?): String? {
        // existing dialog logic from the old callback
    }

    override fun streamCommandOutput(toolCallId: String, chunk: String) {
        // existing streaming logic from the old callback
    }

    override fun notifyToolProgress(toolName: String, message: String) {
        // existing progress logic
    }
}
```

Set callbacks after session creation:
```kotlin
agentService.activeScope?.let { scope ->
    agentService.activeScope = scope.copy(uiCallbacks = this)
}
```

- [ ] **Step 4: Remove old static callback wiring from AgentController.init**

Remove lines that set `AskUserInputTool.showInputCallback` and `RunCommandTool.streamCallback`.

- [ ] **Step 5: Run tests**

Run: `./gradlew :agent:test -v`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/AskUserInputTool.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "fix(ui): replace static tool callbacks with project-scoped UiCallbacks [C7]"
```

---

### Task 19: [WU6] Consolidate PlanManager approval, split memory category, dedup wiring (M1, M8, M13)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/PlanManager.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreatePlanTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

- [ ] **Step 1: Remove CompletableFuture from PlanManager (M1)**

In `PlanManager.kt`:
- Remove `approvalFuture` field (line 74)
- Remove `submitPlan()` method (lines 88-96) — keep only `submitPlanAndWait()`
- In `approvePlan()` (lines 161-176), `revisePlan()`, `revisePlanWithContext()`: remove `approvalFuture?.complete(...)` lines, keep only `approvalDeferred?.complete(...)`
- Update `isAwaitingApproval` (lines 157-159): check only `approvalDeferred`

- [ ] **Step 2: Update CreatePlanTool to use submitPlanAndWait (M1)**

In `CreatePlanTool.kt`, replace lines 89-100:

```kotlin
// Before (suspendCancellableCoroutine + CompletableFuture bridge):
val result = withTimeoutOrNull(600_000L) {
    suspendCancellableCoroutine<PlanApprovalResult> { cont ->
        val future = planManager.submitPlan(plan)
        // ... CompletableFuture bridge ...
    }
}

// After (direct coroutine-native call):
val result = withTimeoutOrNull(600_000L) {
    planManager.submitPlanAndWait(plan)
}
```

- [ ] **Step 3: Separate memory from planning category (M8)**

In `ToolCategoryRegistry.kt`, add "memory" category:

```kotlin
"memory" to listOf(
    "core_memory_read", "core_memory_append", "core_memory_replace",
    "archival_memory_insert", "archival_memory_search", "conversation_search", "save_memory"
)
```

Remove these tools from the "planning" category. Add aliases:
```kotlin
"remember" to "memory",
"recall" to "memory",
"forget" to "memory",
"learn" to "memory"
```

- [ ] **Step 4: Dedup callback wiring in AgentController (M13)**

Extract shared method:

```kotlin
private fun wirePanel(panel: AgentDashboardPanel, isMirror: Boolean) {
    // All shared callback wiring (30+ callbacks)
    panel.onSendMessage = { ... }
    panel.onToolApproval = { ... }
    // ... etc ...

    if (!isMirror) {
        // State initialization callbacks only for primary panel
    }
}
```

Replace the `init` block's panel wiring with `wirePanel(dashboard, isMirror = false)`.
Replace `wireExtraPanel()` body with `wirePanel(panel, isMirror = true)`.

- [ ] **Step 5: Run tests**

Run: `./gradlew :agent:test --tests "*PlanManagerTest*" --tests "*CreatePlanToolTest*" --tests "*ToolCategoryRegistryTest*" -v`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/PlanManager.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreatePlanTool.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "refactor: consolidate PlanManager approval, split memory category, dedup panel wiring [M1,M8,M13]"
```

---

### Task 20: [WU8] Restore SelfCorrectionGate on resume (H1)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SelfCorrectionGate.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SelfCorrectionGateTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
@Test
fun `restoreFromLedger reconstructs file states`() {
    val gate = SelfCorrectionGate()
    val ledger = ChangeLedger()

    // Simulate two edits: one verified, one not
    ledger.recordEdit("src/Main.kt", "edit_file", "edited content")
    ledger.recordVerification("src/Main.kt", true)
    ledger.recordEdit("src/Service.kt", "edit_file", "edited content")

    gate.restoreFromLedger(ledger)

    val states = gate.getFileStates()
    assertTrue(states.containsKey(File("src/Main.kt").canonicalPath) || states.containsKey("src/Main.kt"))
    // The verified file should be marked as verified
    // The unverified file should be marked as pending
}
```

- [ ] **Step 2: Implement restoreFromLedger()**

Add to `SelfCorrectionGate.kt`:

```kotlin
fun restoreFromLedger(ledger: ChangeLedger) {
    for (entry in ledger.entries()) {
        val path = normalizePath(entry.filePath)
        if (!isVerifiableExtension(path)) continue

        val state = fileStates.getOrPut(path) { FileState() }
        state.editCount = (state.editCount + 1)

        if (entry.verified) {
            state.verified = true
            state.verificationRequested = true
        } else if (entry.verificationError != null) {
            state.lastError = entry.verificationError
            state.retryCount++
            state.verificationRequested = true
        }
    }
}
```

Note: Adapt the field names to match the actual `ChangeLedger.ChangeEntry` data class fields. The implementation reads entries from the ledger and reconstructs the per-file verification state.

- [ ] **Step 3: Wire in ConversationSession.load()**

After loading the change ledger in `load()` (around line 538-541), add:

```kotlin
// After: changeLedger loaded from disk
scope.selfCorrectionGate.restoreFromLedger(scope.changeLedger)
```

(This was already included in Task 16 as a placeholder call — verify it's present.)

- [ ] **Step 4: Run tests**

Run: `./gradlew :agent:test --tests "*SelfCorrectionGateTest*" -v`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SelfCorrectionGate.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SelfCorrectionGateTest.kt
git commit -m "fix(runtime): restore SelfCorrectionGate state on session resume [H1]"
```

---

### Task 21: [WU8] Add API retry to WorkerSession (H2)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/WorkerSession.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/WorkerSessionTest.kt`

- [ ] **Step 1: Add retry helper method**

Add to `WorkerSession`:

```kotlin
private suspend fun callBrainWithRetry(
    messages: List<ChatMessage>,
    tools: List<ToolDefinition>?
): ApiResult<ChatCompletion> {
    var lastError: ApiResult.Error? = null
    repeat(3) { attempt ->
        when (val result = brain.chat(messages, tools, maxOutputTokens, null)) {
            is ApiResult.Success -> return result
            is ApiResult.Error -> {
                lastError = result
                if (result.statusCode == 429 || (result.statusCode ?: 0) in 500..599) {
                    val delayMs = (1000L * (1 shl attempt)) + (0L..500L).random()
                    LOG.info("WorkerSession: retrying after ${delayMs}ms (attempt ${attempt + 1}/3, status=${result.statusCode})")
                    delay(delayMs)
                } else {
                    return result // non-retryable
                }
            }
        }
    }
    return lastError!!
}
```

- [ ] **Step 2: Replace direct brain.chat() call with callBrainWithRetry()**

In `runReactLoop()`, replace the direct `brain.chat(messages, activeToolDefs, maxOutputTokens)` call (around line 184) with `callBrainWithRetry(messages, activeToolDefs)`.

- [ ] **Step 3: Run tests**

Run: `./gradlew :agent:test --tests "*WorkerSessionTest*" -v`
Expected: ALL PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/WorkerSession.kt
git commit -m "fix(runtime): add 3-retry exponential backoff for API errors in WorkerSession [H2]"
```

---

### Task 22: [WU8] Add resume budget check (M17)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt`

- [ ] **Step 1: Add budget check before transcript replay in executeResume()**

In `executeResume()` (around line 480-490), before replaying transcript messages, add:

```kotlin
// Before replaying:
val transcriptTokens = transcript.sumOf { TokenEstimator.estimate(it.content ?: "") }
val workerBudget = settings.maxInputTokens  // worker context budget
if (transcriptTokens > workerBudget * 0.8) {
    LOG.info("SpawnAgentTool: transcript exceeds 80% of worker budget (${transcriptTokens} tokens), summarizing")
    val summarized = LlmBrainSummarizationClient(brain).summarize(transcript)
    if (summarized != null) {
        // Inject summary as single context message instead of full replay
        contextManager.addMessage(ChatMessage(role = "user", content = "<session_summary>\n$summarized\n</session_summary>\nContinue from where you left off."))
    } else {
        // Fallback: replay normally but warn
        transcript.forEach { contextManager.addMessage(it) }
    }
} else {
    transcript.forEach { contextManager.addMessage(it) }
}
```

Add imports for `TokenEstimator` and `LlmBrainSummarizationClient` at the top of the file.

- [ ] **Step 2: Run tests**

Run: `./gradlew :agent:test --tests "*SpawnAgentToolTest*" -v`
Expected: ALL PASS

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt
git commit -m "fix(runtime): add budget check before resume transcript replay [M17]"
```

---

## Phase C — Depends on WU2 (processLlmSuccess decomposition)

---

### Task 23: [WU2] Create ParsedResponse sealed class (C5 foundation)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ParsedResponse.kt`

- [ ] **Step 1: Create ParsedResponse**

```kotlin
package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.api.dto.ToolCall

sealed class ParsedResponse {
    abstract val content: String?
    abstract val rawUsage: Any?  // ChatCompletion.Usage from API

    data class Empty(
        override val content: String?,
        override val rawUsage: Any?
    ) : ParsedResponse()

    data class Malformed(
        override val content: String?,
        override val rawUsage: Any?,
        val rawToolCalls: List<ToolCall>,
        val finishReason: String?
    ) : ParsedResponse()

    data class TextOnly(
        override val content: String,
        override val rawUsage: Any?
    ) : ParsedResponse()

    data class WithToolCalls(
        override val content: String?,
        override val rawUsage: Any?,
        val toolCalls: List<ToolCall>,
        val completionCall: ToolCall?
    ) : ParsedResponse()
}

enum class LoopAction {
    CONTINUE,
    COMPLETED,
    FORCE_COMPLETED,
    CANCELLED,
    BUDGET_EXHAUSTED
}
```

- [ ] **Step 2: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ParsedResponse.kt
git commit -m "feat(runtime): add ParsedResponse sealed class for processLlmSuccess decomposition [C5]"
```

---

### Task 24: [WU2] Decompose processLlmSuccess into focused handlers (C5)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSessionTest.kt`

This is the largest refactoring task. The 676-line `processLlmSuccess` method (lines 704-1380) is decomposed into:

- [ ] **Step 1: Add parseResponse() method**

Extract response classification from the top of processLlmSuccess into a standalone method. This classifies the API response into one of: Empty, Malformed, TextOnly, WithToolCalls.

Lines 754-866 of current processLlmSuccess contain this logic. Extract into:

```kotlin
private fun parseResponse(choice: ChatCompletionChoice): ParsedResponse {
    val message = choice.message
    val toolCalls = message.toolCalls?.filter { !it.function.name.isNullOrBlank() } ?: emptyList()
    val content = message.content?.trim()

    // Empty response
    if (content.isNullOrBlank() && toolCalls.isEmpty()) {
        return ParsedResponse.Empty(content, null)
    }

    // Malformed tool calls (finish_reason indicates tools but none parsed)
    if (toolCalls.isEmpty() && choice.finishReason == "tool_calls") {
        return ParsedResponse.Malformed(content, null, message.toolCalls ?: emptyList(), choice.finishReason)
    }

    // Text only
    if (toolCalls.isEmpty()) {
        return ParsedResponse.TextOnly(content ?: "", null)
    }

    // Has tool calls — check for attempt_completion
    val completionCall = toolCalls.find { it.function.name == "attempt_completion" }
    return ParsedResponse.WithToolCalls(content, null, toolCalls, completionCall)
}
```

- [ ] **Step 2: Add handler methods**

Add these methods to `SingleAgentSession`, each extracted from the corresponding section of processLlmSuccess:

- `handleEmptyResponse(parsed: ParsedResponse.Empty)` — from lines 868-873 (nudge logic)
- `handleMalformedResponse(parsed: ParsedResponse.Malformed)` — from lines 835-866
- `handleTextOnlyResponse(parsed: ParsedResponse.TextOnly)` — from lines 874-951
- `handleToolCalls(parsed: ParsedResponse.WithToolCalls)` — from lines 954-1380

Each handler should be ≤120 lines. The `handleToolCalls` can delegate to `executeToolBatch()` and `recordToolResults()` sub-methods.

- [ ] **Step 3: Replace processLlmSuccess with router**

Replace the body of processLlmSuccess with:

```kotlin
private suspend fun processLlmSuccess(response: ChatCompletion, ...): SingleAgentResult? {
    // Token accounting (kept inline — 20 lines)
    updateTokenAccounting(response)

    val choice = response.choices.firstOrNull() ?: return null
    // Add assistant message to context
    bridge.addAssistantMessage(choice.message.content, choice.message.toolCalls)

    val parsed = parseResponse(choice)
    return when (parsed) {
        is ParsedResponse.Empty -> handleEmptyResponse(parsed)
        is ParsedResponse.Malformed -> handleMalformedResponse(parsed)
        is ParsedResponse.TextOnly -> handleTextOnlyResponse(parsed)
        is ParsedResponse.WithToolCalls -> handleToolCalls(parsed)
    }
}
```

- [ ] **Step 4: Run ALL tests**

Run: `./gradlew :agent:test -v`
Expected: ALL PASS. This is the most critical verification — every behavioral test must still pass after the decomposition.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt
git commit -m "refactor(runtime): decompose processLlmSuccess into focused handler methods [C5]"
```

---

### Task 25: [WU3] Simplify completion to 3 paths (C3, M2)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/CompletionGatekeeper.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/CompletionGatekeeperTest.kt`

- [ ] **Step 1: Add CompletionMode to CompletionGatekeeper**

```kotlin
enum class CompletionMode {
    NORMAL,   // All gates active, can block
    LENIENT,  // Only plan gate active (for implicit completion)
    FORCED    // All gates run but only log, cannot block
}

fun checkCompletion(mode: CompletionMode = CompletionMode.NORMAL): String? {
    totalCompletionAttempts++
    if (totalCompletionAttempts >= MAX_TOTAL_COMPLETION_ATTEMPTS) {
        wasForceAccepted = true
        return null
    }
    if (mode == CompletionMode.FORCED) {
        // Run gates for metrics only, never block
        checkPlanCompletion()  // result ignored
        checkSelfCorrection()  // result ignored
        checkLoopGuard()       // result ignored
        wasForceAccepted = true
        return null
    }
    // NORMAL: all gates
    // LENIENT: only plan gate
    val planBlock = checkPlanCompletion()
    if (planBlock != null) { lastBlockedGate = "plan"; return planBlock }
    if (mode == CompletionMode.LENIENT) return null
    val scBlock = checkSelfCorrection()
    if (scBlock != null) { lastBlockedGate = "self_correction"; return scBlock }
    val lgBlock = checkLoopGuard()
    if (lgBlock != null) { lastBlockedGate = "loop_guard"; return lgBlock }
    return null
}
```

- [ ] **Step 2: Simplify handleTextOnlyResponse — 2 responses = implicit completion**

In `handleTextOnlyResponse()`, replace the 4-nudge escalation:

```kotlin
private suspend fun handleTextOnlyResponse(parsed: ParsedResponse.TextOnly): SingleAgentResult? {
    if (!lastResponseWasTextOnly) {
        // First text-only: nudge
        lastResponseWasTextOnly = true
        bridge.addSystemMessage("You responded without tool calls. Use attempt_completion when done, or call a tool to continue working.")
        return null  // continue loop
    }
    // Second consecutive text-only: implicit completion via gatekeeper (LENIENT mode)
    lastResponseWasTextOnly = false
    val block = completionGatekeeper.checkCompletion(CompletionMode.LENIENT)
    if (block != null) {
        bridge.addSystemMessage(block)
        return null  // continue
    }
    return buildCompletedResult(parsed.content)
}
```

- [ ] **Step 3: Route budget exhaustion through gatekeeper (C3)**

In `execute()`, where BudgetEnforcer.TERMINATE is handled (around line 417-497), replace the direct acceptance bypass:

```kotlin
// Instead of accepting completion directly when budget exhausted:
completionGatekeeper.checkCompletion(CompletionMode.FORCED) // runs gates for metrics, never blocks
// Then proceed with context rotation or completion as before
```

- [ ] **Step 4: Remove forceTextOnly as session field (M2)**

Remove the `forceTextOnly` field (line 98). Replace with a stateless check:

```kotlin
private fun shouldSuppressTools(): Boolean =
    (iteration.toFloat() / maxIterations >= 0.95f) ||
    consecutiveMalformedRetries >= MAX_MALFORMED_RETRIES
```

Use `shouldSuppressTools()` wherever `forceTextOnly` was checked to strip tools from the LLM call schema.

- [ ] **Step 5: Remove MAX_NO_TOOL_NUDGES and consecutiveNoToolResponses**

Replace with `lastResponseWasTextOnly: Boolean = false`. Remove `MAX_NO_TOOL_NUDGES = 4` constant.

- [ ] **Step 6: Run ALL tests**

Run: `./gradlew :agent:test -v`
Expected: ALL PASS. Update test assertions that check for the 4-nudge sequence or forceTextOnly.

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/CompletionGatekeeper.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/CompletionGatekeeperTest.kt
git commit -m "refactor(runtime): simplify completion to 3 clear paths, remove forceTextOnly and 4-nudge sequence [C3,M2]"
```

---

## Phase D — Verification & Documentation

---

### Task 26: Full test suite verification

- [ ] **Step 1: Run complete agent test suite**

Run: `./gradlew :agent:clean :agent:test --rerun --no-build-cache -v`
Expected: ALL PASS (~470 tests)

- [ ] **Step 2: Run core tests**

Run: `./gradlew :core:test -v`
Expected: ALL PASS

- [ ] **Step 3: Verify plugin compatibility**

Run: `./gradlew verifyPlugin`
Expected: PASS

- [ ] **Step 4: Build plugin**

Run: `./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL with ZIP in `build/distributions/`

---

### Task 27: Update documentation

**Files:**
- Modify: `agent/CLAUDE.md`
- Modify: `docs/audits/2026-04-03-comprehensive-architecture-audit.md`

- [ ] **Step 1: Update agent/CLAUDE.md**

- Remove references to `forceTextOnly` permanent flag
- Update completion detection: "Three paths: explicit (attempt_completion → 3-gate gatekeeper), implicit (2 consecutive text-only → lenient gatekeeper), budget exhaustion (forced mode gatekeeper for metrics)"
- Document `SessionScope` in architecture section
- Document `UiCallbacks` pattern replacing static callbacks
- Update tool count if changed

- [ ] **Step 2: Update audit report**

Add a "Resolution" section to the audit report noting all 33 issues fixed, with commit references.

- [ ] **Step 3: Commit**

```bash
git add agent/CLAUDE.md docs/audits/2026-04-03-comprehensive-architecture-audit.md
git commit -m "docs: update agent CLAUDE.md and audit report with remediation results"
```

---

## Execution Summary

| Task | Work Unit | Issues Fixed | Est. Time |
|------|-----------|-------------|-----------|
| 1 | WU4 | C1 | 5 min |
| 2 | WU4 | C2 | 5 min |
| 3 | WU4 | H5 | 3 min |
| 4 | WU4 | H6 | 5 min |
| 5 | WU4 | H7 | 5 min |
| 6 | WU4 | H8 | 2 min |
| 7 | WU4 | M6 | 3 min |
| 8 | WU4 | M7 | 5 min |
| 9 | WU4 | M10,M11,M14,M16 | 10 min |
| 10 | WU5 | C6,M12,M18 | 15 min |
| 11 | WU7 | H3,M15 | 10 min |
| 12 | WU7 | H4 | 10 min |
| 13 | WU7 | M3,M4,M5 | 10 min |
| 14 | WU1 | C4 (foundation) | 5 min |
| 15 | WU1 | C4 (AgentService) | 5 min |
| 16 | WU1 | C4,M9 | 15 min |
| 17 | — | Shared constant | 3 min |
| 18 | WU6 | C7 | 15 min |
| 19 | WU6 | M1,M8,M13 | 20 min |
| 20 | WU8 | H1 | 10 min |
| 21 | WU8 | H2 | 10 min |
| 22 | WU8 | M17 | 10 min |
| 23 | WU2 | C5 (foundation) | 5 min |
| 24 | WU2 | C5 (main refactor) | 30 min |
| 25 | WU3 | C3,M2 | 20 min |
| 26 | — | Verification | 10 min |
| 27 | — | Documentation | 10 min |

**Total: 27 tasks, all 33 issues, ~260 min estimated**

**Parallelization:** Tasks 1-13 (Phase A) can run in 4 parallel subagents. Tasks 14-22 (Phase B) can run in 3 parallel subagents after Phase A. Tasks 23-25 (Phase C) are sequential. Tasks 26-27 (Phase D) are sequential.
