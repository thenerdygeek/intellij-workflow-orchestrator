# Plugin Split — Phase 0b-1 (LLM seam: `LlmProvider` + `ToolProtocol`) Implementation Plan — **rev 2**

> **rev 2 — 3-round opus review folded; old Task 5 (toolNameSet/paramNameSet relocation) deferred to Phase 4; GAP1-4 + B1-3 + I1 + WA-1 fixed.**

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract a `ToolProtocol` seam and widen the existing `LlmBrain` proto-seam into an `LlmProvider`, **wrapping today's Sourcegraph/XML behavior with ZERO user-visible or behavioral change**, and **shape (interface-only, no impl) the `NativeProtocol` surface** so Phase 4's Anthropic-direct provider drops in without reopening the seam. Every extraction is a characterization-test-first refactor: pin the CURRENT XML behavior with a test, prove it green against today's code, refactor behind the seam, prove the SAME test still green.

**Architecture:** The seam interfaces (`ToolProtocol`, `LlmProvider`, `NativeProtocol`) live in `:core` next to `LlmBrain` (the existing narrow proto-seam at `core/.../ai/LlmBrain.kt`). An `XmlToolProtocol` (in `:core`) wraps today's scattered XML concerns — `ToolPromptBuilder` tool presentation, `AssistantMessageParser` segmentation (both the end-of-stream decode AND the streaming-UI splitter helpers `stripPartialTag`/`endsWithIncompleteTag`), the `MessageSanitizer` `"TOOL RESULT:"` wire convention, the `requiresDialectGuard` flag, and `GatewayErrorDetector` error classification — behind one identity-preserving facade. Impls/wiring that touch `AgentLoop`/`AgentService`/`MessageStateHandler` are in `:agent` (which depends on `:core`). `LlmProvider` widens `LlmBrain` with model-catalog/capability/context-window accessors (delegating to today's `ModelCatalogService`) + HTTP-error/status/fallback-chain accessors and absorbs `BrainRouter`'s per-message image routing so the loop reads ONE provider.

> **rev-2 SCOPE NOTE (`toolNameSet`/`paramNameSet`):** the XML parser's known-tag inputs (`toolNameSet`/`paramNameSet`) are **NOT** relocated off `LlmBrain` in 0b-1 — that move is **deferred to Phase 4** (see "Out of scope"). Three independent review rounds converged on the reason: the authoritative XML tool-call DECODE is **not** the presentation/streaming parse in `AgentLoop`; it is `core/.../ai/SourcegraphChatClient.kt:365`/:513 `AssistantMessageParser.parse(rawText, knownToolNames, knownParamNames)` fed by `core/.../ai/OpenAiCompatBrain.kt:64-65`/:81-82 (`toolNameSet`/`paramNameSet`), plus `BrainRouter.buildRouterResponse` (`agent/.../loop/BrainRouter.kt:244-246`). The sets are written by `agent/.../brain/BrainFactory.kt:99-100`, `AgentService.kt:1980-1981` (primary orchestrator brain), `SpawnAgentTool.kt:826-827`/:946-947 (per-spawn fresh brains via `brainProvider`), and `SubagentRunner.kt:290-291`. Relocating the sets off the brain without rerouting these live transport readers/writers would stop the agent from dispatching tools (full breakage), and a single shared protocol instance would race across parallel sub-agent fan-out (sub-agent tool-scope isolation lost). 0b-1 keeps the sets on `LlmBrain` unchanged.

**Tech Stack:** Kotlin 2.1.10, Gradle + IntelliJ Platform Gradle plugin **2.12.0**, target IntelliJ IDEA Ultimate **2025.1.7**, JUnit 5 + JUnit-Vintage, kotlinx.serialization. Module test command `./gradlew :<module>:test`; green gate runs the full module set + `verifyPlugin` with `--rerun-tasks`.

> **rev-1 provenance:** Grounded in the four read-only exploration maps under `.superpowers/phase0b/` (`explore-llm-call-streaming.md`, `explore-prompt-tool-presentation.md`, `explore-persistence-history.md`, `explore-modelcatalog-errors-drift-resume.md`) and the design synthesis (`SEAM-DESIGN-SYNTHESIS.md`), with **two spec corrections folded in** (both already amended in `docs/superpowers/specs/2026-06-22-plugin-split-design.md` §6, lines 110/114): (1) **`DialectDriftDetector` = 6 wiring sites, not 5** — `SubagentRunner` is a distinct file from `SubagentSystemPromptBuilder`; (2) **tool results are NOT persisted as `"TOOL RESULT:"` text** — on disk they are structured `ContentBlock.ToolResult(toolUseId, content="raw")` under `ApiRole.USER`; the `"TOOL RESULT:\n"` prefix is **wire-send only** (`MessageSanitizer.kt:74`). Every file:line below was re-verified against the real source on the `feature/plugin-split` branch.
>
> **rev-2 provenance:** folds three independent opus review rounds. Changes: (a) **old Task 5 deleted** — the `toolNameSet`/`paramNameSet` relocation is transport-coupled (live decode in `SourcegraphChatClient`/`OpenAiCompatBrain`/`BrainRouter`) and instance-scoped (per-spawn brains), so it is deferred to Phase 4 and replaced by an Out-of-scope entry; remaining tasks renumbered. (b) **bytecode blockers** B1 (`ApiResult.Error(ErrorType.SERVER_ERROR, …)`, not the non-existent `ApiResult.error`), B2 (defaulted `toolProtocol` ctor param APPENDED among `AgentLoop`'s defaulted params, not "next to brain"), B3 (BOTH `AgentLoop(...)` sites — `AgentService.kt:2424` + `SubagentRunner.kt:331`), I1 (`ParameterProperty(type=…, description=…)` — `type` has no default). (c) **completeness gaps** GAP1 (route `stripPartialTag`/`endsWithIncompleteTag` too), GAP2 (`classifyHttpError`), GAP3 (`getStatus` + `buildFallbackChain`), GAP4 (real characterization tests for the 2 gated detector sites, in `:agent`). (d) **maintainability** WA-1 (chokepoint guard via `consumeDialectDriftFlag`), the MessageSanitizer task (rev-2 Task 6) compaction no-regression assertion, green-gate adds `:automation:test`. (e) minor M1-M4 + I2. Old Tasks 6→13 renumbered down by one to 5→12.

## Global Constraints

- **Behavior-preservation is the prime directive.** This is a refactor-under-test, NOT classic red-green for new features. Per extraction task: (a) write a characterization test pinning CURRENT XML behavior, (b) run it GREEN against current code (proving the test captures real behavior), (c) refactor behind the seam, (d) prove the SAME test still GREEN. A task that cannot show its characterization test green before the refactor has not pinned the behavior and must not proceed.
- **`:core` ONE-`BasePlatformTestCase` INVARIANT (cost Phase-0a a green-gate failure).** `:core`'s test JVM is un-forked; a 2nd `BasePlatformTestCase` deterministically hangs on a headless "Indexing timeout". **Do NOT add ANY `BasePlatformTestCase` to `:core`.** All seam logic in `:core` MUST be pure-tested (plain JUnit5, no platform fixture). If a unit needs IDE state, design a PURE helper (Phase-0a precedent: `lowestOrderOf`) so it is unit-testable without a fixture. The seam interfaces and `XmlToolProtocol` are deliberately IDE-free (they take/return plain DTOs already in `:core`) precisely so they are pure-testable.
- **New EP/seam interfaces B may implement later are `public` + `@InternalApi`** (`com.workflow.orchestrator.core.api.InternalApi`) — **NEVER `internal`** (Kotlin `internal` is module-scoped; B can't compile against it). `LlmProvider`/`ToolProtocol`/`NativeProtocol` live in `:core` (where `LlmBrain` already is). `@InternalApi` = public but unfrozen-by-policy; there is no `CANDIDATE` level — only `@StableApi(since)` (reserved for the deferred Phase-5 external freeze) and `@InternalApi` exist in `core/.../core/api/ApiStability.kt`.
- **No `runBlocking` in `main/`** (pre-commit hook `block-runblocking.sh`). Use `com.intellij.openapi.progress.runBlockingCancellable`.
- **`--no-build-cache --rerun-tasks` on any commit changing a lambda/function type to/from `suspend`** (Gradle compile-avoidance keeps stale `Function0`/`FunctionN` bytecode → `NoSuchMethodError` at runtime). Tasks that change suspend signatures are flagged inline: **Task 7** (`LlmProvider` widening — adds a `suspend fun getCatalog` to the interface) and **Task 9** (`XmlLlmProvider` BrainRouter absorption — delegates a suspend wrapper). Run those with `--no-build-cache --rerun-tasks`.
- **Cross-module:** `:agent` depends on `:core`. Seam interfaces + `XmlToolProtocol` go in `:core` (with `LlmBrain`). Wiring through `AgentLoop`/`AgentService`/`MessageStateHandler`/`SubagentRunner`/`ResumeHelper` is in `:agent`.
- **The 6 `DialectDriftDetector` wiring sites (verbatim, re-verified):**
  1. `agent/.../session/MessageStateHandler.kt` — write guard `hasDialectDrift(message)` (:207) + `dialectDriftFlag.set(true)` (:217); `redactDialectXmlInHistory()` (:312) → `DialectDriftDetector.redactDialectMarkers(block.text)` (:322), flag set (:335); `hasDialectDrift` private fn (:351) → `DialectDriftDetector.hasDialectMarker(block.text)` (:354).
  2. `agent/.../prompt/SystemPrompt.kt` — `dialectDriftDetected: Boolean = false` param (:148); `<system-reminder>` emission `if (dialectDriftDetected)` (:177–196).
  3. `agent/.../tools/subagent/SubagentSystemPromptBuilder.kt` — `dialectDriftDetected: Boolean = false` param (:86); forwarded to `SystemPrompt.build(dialectDriftDetected = ...)` (:125).
  4. `agent/.../tools/subagent/SubagentRunner.kt` — `consumeDialectDriftFlag()` (:637) → `SubagentSystemPromptBuilder.build(dialectDriftDetected = ...)` (:652).
  5. `agent/.../AgentService.kt` — `executeTask` lambda: `consumeDialectDriftFlag()` (:2129) → `SystemPrompt.build(dialectDriftDetected = dialectDriftSnapshot)` (:2156).
  6. `agent/.../AgentService.kt` — resume: `ResumeHelper.redactDialectDriftInHistory(activeApiHistory)` (:2867), `dialectDriftDetectedOnResume = true` (:2871) → `SystemPrompt.build(dialectDriftDetected = dialectDriftDetectedOnResume)` (:3104).
- **WA-1 — drift re-arm chokepoint (verified writer/reader topology):** there are exactly **2** flag WRITERS — `MessageStateHandler.kt:217` and `:335` (both inside the detector-invoking paths) — and exactly **2** flag READERS — `AgentService.kt:2129` and `SubagentRunner.kt:637`, both via `MessageStateHandler.consumeDialectDriftFlag()` (`:349` = `dialectDriftFlag.getAndSet(false)`). `DialectDriftDetector` is a stateless `object`, so the flag is structurally always-false under native TODAY — but rather than leave sites 2/3/4/5 "unguarded-but-commented", **Task 5 gates the single chokepoint**: `consumeDialectDriftFlag()` returns `toolProtocol.requiresDialectGuard && dialectDriftFlag.getAndSet(false)`. One load-bearing guard covers both readers and prevents a future re-arm. The resume path uses a SEPARATE local (`dialectDriftDetectedOnResume`, `AgentService.kt:2866`/:2871) set off `redactedCount > 0` — Task 5 gates that assignment separately (site 6).
- **Tool-result persistence reality (verbatim):** on disk = `ContentBlock.ToolResult(toolUseId, content="raw output", isError)` under `ApiRole.USER` (`ApiMessage.kt:23`/:71). The `"TOOL RESULT:\n"` prefix is produced ONLY at wire-send in `MessageSanitizer.kt:74`. The seam owns the **wire** convention; it does NOT touch on-disk tool-result shape.

---

## File Structure (created / modified — one responsibility each)

**Created (`:core`):**
- `core/.../core/ai/protocol/ToolProtocol.kt` — the protocol seam interface (presentation/segmentation incl. UI-splitter helpers/result-format/drift-flag). **Parser name sets stay on `LlmBrain` in 0b-1** (relocation deferred to Phase 4 — see Out of scope). `public` + `@InternalApi`.
- `core/.../core/ai/protocol/NativeProtocol.kt` — marker sub-interface shaping the native paradigm (NO impl). `public` + `@InternalApi`.
- `core/.../core/ai/protocol/XmlToolProtocol.kt` — wraps `ToolPromptBuilder` + `AssistantMessageParser` (`parse` + `stripPartialTag` + `endsWithIncompleteTag`) + `MessageSanitizer` wire-convention + `GatewayErrorDetector`, behavior-identical.
- `core/.../core/ai/LlmProvider.kt` — widens `LlmBrain` with catalog/capability/context-window + `classifyStreamLine` + `classifyHttpError` (GAP2) + `getStatus`/`buildFallbackChain` (GAP3) + `val toolProtocol`. `public` + `@InternalApi`.
- `core/src/test/.../ai/protocol/XmlToolProtocolCharacterizationTest.kt` — pins presentation == `ToolPromptBuilder.build`, segmentation == `AssistantMessageParser.parse`, UI-splitter helpers == `stripPartialTag`/`endsWithIncompleteTag` (GAP1), result-format == `"TOOL RESULT:\n"`, drift flag == true, classify == `GatewayErrorDetector`. Pure JUnit5.
- `core/src/test/.../ai/protocol/LlmProviderContractTest.kt` — pure JUnit5 contract test: catalog/capability/context-window delegate to `ModelCatalogService`; `XmlLlmProvider` is an `LlmBrain`.
- `core/src/test/.../ai/protocol/SeamApiStabilityTest.kt` — pure JUnit5 reflection test: the seam interfaces are `public` (not `internal`) and `@InternalApi`-annotated.

**Created (`:agent`):**
- `agent/.../agent/loop/XmlLlmProvider.kt` — `LlmProvider` adapter wrapping the existing brain (`BrainRouter`/`OpenAiCompatBrain`) + `ModelCatalogService` + an `XmlToolProtocol`; absorbs `BrainRouter` image routing behind one provider.
- `agent/src/test/.../loop/XmlLlmProviderImageRoutingCharacterizationTest.kt` — pins that image-vs-no-image routing decision is preserved post-absorption.

**Modified (`:core`):**
- `core/.../core/ai/LlmBrain.kt` — **UNCHANGED in 0b-1.** `toolNameSet`/`paramNameSet` stay as-is (relocation deferred to Phase 4).
- `core/.../core/ai/MessageSanitizer.kt` — extract the `"TOOL RESULT:\n"` literal (`:74`) into a `ToolProtocol`-owned constant; behavior identical (Task 6).

**Modified (`:agent`):**
- `agent/.../loop/AgentLoop.kt` — route the streaming segmentation parse (`:1074`) AND the two UI-splitter helper calls `stripPartialTag` (`:1119`) + `endsWithIncompleteTag` (`:1137`) through `toolProtocol` (Task 4, incl. GAP1). The name-set reads (`:1019-1020 = brain.toolNameSet/paramNameSet`) stay on `brain` (relocation deferred to Phase 4). The `AgentLoop` ctor gains a defaulted `toolProtocol` param.
- `agent/.../AgentService.kt` — route the `ToolPromptBuilder.build` orchestrator call (:2205) through `toolProtocol.presentTools`; gate drift site 6 (resume redaction) on `requiresDialectGuard` (Task 5); construct `XmlLlmProvider` (Task 9). One `AgentLoop(...)` site at `:2424`.
- `agent/.../tools/subagent/SubagentRunner.kt` — route `ToolPromptBuilder.build(coreDefinitions)` (:625) through `toolProtocol.presentTools` (Task 3); second `AgentLoop(...)` site at `:331` (Task 4 threads `toolProtocol` here).
- `agent/.../session/MessageStateHandler.kt` — gate the drift CHOKEPOINT (`consumeDialectDriftFlag` at `:349`) on `requiresDialectGuard` (Task 5, WA-1) + accept a defaulted `toolProtocol` ctor param; gate the bulk-redact (`redactDialectXmlInHistory` at `:312`) + write guard (`:207`).
- `agent/.../session/ResumeHelper.kt` — add a pure `emptyRedaction()` factory for the gated resume branch (Task 5).
- `agent/.../session/ApiMessage.kt` — reserve `protocol: String? = null` discriminator on `ApiMessage` (default null ⇒ "xml"; NO migration logic) (Task 10).

---

### Task 1: `ToolProtocol` interface (`:core`) — shape only, no behavior

The protocol seam. Interface-only this task; `XmlToolProtocol` lands in Task 2.

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/protocol/ToolProtocol.kt`

**Interfaces — Produces:** `ToolProtocol` with `presentTools(tools): String?`, `parseToolCalls(text, toolNames, paramNames): List<AssistantMessageContent>`, `stripPartialTag(text): String` (GAP1), `endsWithIncompleteTag(text): Boolean` (GAP1), `toolResultWirePrefix: String`, `requiresDialectGuard: Boolean`, `classifyStreamLine(line): String?`. **Note:** the parser name sets (`toolNameSet`/`paramNameSet`) are deliberately **NOT** on this interface in 0b-1 — they stay on `LlmBrain` (Phase-4 relocation; see Out of scope). **Consumes:** `ToolDefinition`, `AssistantMessageContent` (both already in `:core`).

> Honest TDD framing: this task adds an interface with no implementation, so there is nothing behavioral to characterize yet. Its "test" is the compile + the Task-2 characterization test that exercises it. Commit the interface; Task 2 proves it.

- [ ] **Step 1: Create `ToolProtocol.kt`**

```kotlin
package com.workflow.orchestrator.core.ai.protocol

import com.workflow.orchestrator.core.ai.AssistantMessageContent
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.api.InternalApi

/**
 * Abstraction over a model's tool-calling PARADIGM (orthogonal to transport, which is [com.workflow.orchestrator.core.ai.LlmProvider]).
 *
 * Two paradigms (spec §6):
 *  - XML-in-content (Sourcegraph/Cody, today): tool defs injected into the system prompt as XML,
 *    tool calls emitted as XML in the model's text, parsed by [AssistantMessageContent], results
 *    sent as plain-text "TOOL RESULT:" user turns, kept on-dialect by DialectDriftDetector.
 *  - Native function-calling (Anthropic Messages API, Phase 4): tools in tools:[], structured
 *    tool_use blocks out / tool_result blocks back, no text parsing, no drift machinery.
 *
 * @InternalApi: public so plugin B may implement it later, but unfrozen — we may change it; B recompiles in lockstep.
 */
@InternalApi
interface ToolProtocol {

    /**
     * Tool presentation. XML returns the system-prompt markdown block (today's
     * `ToolPromptBuilder.build`). Native returns null (tools go in the `tools:[]` API
     * field, not the prompt) — the §6c gate in SystemPrompt.build already no-ops on null.
     */
    fun presentTools(tools: List<ToolDefinition>): String?

    /**
     * Response segmentation + tool-call extraction. For XML this is the per-SSE-chunk
     * streaming splitter AND the end-of-stream tool decoder (today's `AssistantMessageParser.parse`).
     *
     * Takes the parser's known tool/param name sets as PARAMETERS (0b-1 still sources them from
     * `LlmBrain.toolNameSet`/`paramNameSet` at the call site; their relocation onto the protocol is
     * a Phase-4 transport-rewiring concern — see the plan's Out-of-scope section).
     */
    fun parseToolCalls(
        text: CharSequence,
        toolNames: Set<String>,
        paramNames: Set<String>,
    ): List<AssistantMessageContent>

    /**
     * UI-splitter helper (GAP1): strip a trailing partial/incomplete XML tag from streaming display
     * text. XML delegates to `AssistantMessageParser.stripPartialTag` (signature `(CharSequence): String`,
     * verified `AssistantMessageParser.kt:192`). Presentation-path only — keeps the visible delta clean
     * while a tool tag is mid-stream. Native returns the text unchanged.
     */
    fun stripPartialTag(text: CharSequence): String

    /**
     * UI-splitter helper (GAP1): true when the accumulated streaming text ends inside an unclosed
     * `<…>` tag. XML delegates to `AssistantMessageParser.endsWithIncompleteTag` (signature
     * `(CharSequence): Boolean`, verified `AssistantMessageParser.kt:217`). Used by AgentLoop to
     * suppress leaking a tool tag's body to the display. Native returns false.
     */
    fun endsWithIncompleteTag(text: CharSequence): Boolean

    /**
     * Wire-send tool-result prefix (XML = "TOOL RESULT:\n"; native = "" because tool_result is
     * a structured block). This is the WIRE convention only — on-disk tool results are always the
     * structured `ContentBlock.ToolResult`, never this prefix.
     */
    val toolResultWirePrefix: String

    /**
     * True when DialectDriftDetector machinery applies (XML only). Gates the drift wiring sites
     * (chokepoint at `MessageStateHandler.consumeDialectDriftFlag` + the resume redaction).
     * Native is structurally drift-free → false.
     */
    val requiresDialectGuard: Boolean

    /**
     * Provider-/paradigm-specific stream-line error classification. XML wraps
     * `GatewayErrorDetector.isUpstreamTimeoutFrame` → "upstream_timeout"; returns null when the
     * line needs no special handling. Native maps HTTP error events (Phase 4).
     */
    fun classifyStreamLine(line: String): String?
}
```

- [ ] **Step 2: Compile + commit** — `./gradlew :core:compileKotlin` → SUCCESS.

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/protocol/ToolProtocol.kt
git commit -m "feat(core): ToolProtocol seam interface (shape only; @InternalApi public)"
```

---

### Task 2: `XmlToolProtocol` — wrap current XML behavior, characterization-pinned

Wraps `ToolPromptBuilder`, `AssistantMessageParser`, the `"TOOL RESULT:\n"` literal, and `GatewayErrorDetector` so the four scattered XML concerns sit behind one facade. **Behavior MUST be byte-identical** to calling the underlying objects directly — the characterization test proves it.

**Files:**
- Create: `core/.../core/ai/protocol/XmlToolProtocol.kt`
- Test: `core/src/test/.../ai/protocol/XmlToolProtocolCharacterizationTest.kt`

**Interfaces — Consumes:** `ToolPromptBuilder.build` (`core/.../ai/ToolPromptBuilder.kt:13`), `AssistantMessageParser.parse` (`core/.../ai/AssistantMessageParser.kt:42`), `GatewayErrorDetector.isUpstreamTimeoutFrame` (`core/.../ai/GatewayErrorDetector.kt:32`). **Produces:** `XmlToolProtocol : ToolProtocol`.

- [ ] **Step 1: Write the characterization test (pins CURRENT XML behavior)**

`core/src/test/kotlin/.../ai/protocol/XmlToolProtocolCharacterizationTest.kt`:

```kotlin
package com.workflow.orchestrator.core.ai.protocol

import com.workflow.orchestrator.core.ai.AssistantMessageParser
import com.workflow.orchestrator.core.ai.GatewayErrorDetector
import com.workflow.orchestrator.core.ai.ToolPromptBuilder
import com.workflow.orchestrator.core.ai.dto.FunctionDefinition
import com.workflow.orchestrator.core.ai.dto.FunctionParameters
import com.workflow.orchestrator.core.ai.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XmlToolProtocolCharacterizationTest {

    // DTO ctor shapes VERIFIED against core/.../ai/dto/ToolCallModels.kt:
    //   ParameterProperty(type: String /* no default */, description: String, enumValues?, items?)
    //   FunctionParameters(type="object" default, properties, required=emptyList() default)
    // `type` on ParameterProperty has NO default → it MUST be supplied (I1).
    private fun sampleTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = FunctionDefinition(
                name = "read_file",
                description = "Read a file.",
                parameters = FunctionParameters(
                    properties = mapOf("path" to ParameterProperty(type = "string", description = "the path")),
                    required = listOf("path"),
                ),
            ),
        ),
    )

    @Test fun `presentTools is byte-identical to ToolPromptBuilder build`() {
        val tools = sampleTools()
        assertEquals(ToolPromptBuilder.build(tools), XmlToolProtocol().presentTools(tools))
    }

    @Test fun `parseToolCalls is identical to AssistantMessageParser parse`() {
        val text = "thinking...\n<read_file>\n<path>/foo.kt</path>\n</read_file>"
        val toolNames = setOf("read_file")
        val paramNames = setOf("path")
        assertEquals(
            AssistantMessageParser.parse(text, toolNames, paramNames),
            XmlToolProtocol().parseToolCalls(text, toolNames, paramNames),
        )
    }

    @Test fun `stripPartialTag is identical to AssistantMessageParser stripPartialTag (GAP1)`() {
        val p = XmlToolProtocol()
        // A trailing incomplete tag must be stripped exactly as the parser does today.
        for (s in listOf("hello <read", "hello <read_file>\n<pa", "plain text, no tag", "<")) {
            assertEquals(AssistantMessageParser.stripPartialTag(s), p.stripPartialTag(s), "stripPartialTag mismatch on: $s")
        }
    }

    @Test fun `endsWithIncompleteTag is identical to AssistantMessageParser endsWithIncompleteTag (GAP1)`() {
        val p = XmlToolProtocol()
        // CharSequence inputs (StringBuilder + String) must classify identically to the parser.
        val cases: List<CharSequence> = listOf(StringBuilder("foo <read"), "complete <a>b</a>", "no tag here", StringBuilder("trailing <"))
        for (c in cases) {
            assertEquals(AssistantMessageParser.endsWithIncompleteTag(c), p.endsWithIncompleteTag(c), "endsWithIncompleteTag mismatch on: $c")
        }
    }

    @Test fun `tool result wire prefix is the current literal`() {
        assertEquals("TOOL RESULT:\n", XmlToolProtocol().toolResultWirePrefix)
    }

    @Test fun `xml protocol requires the dialect guard`() {
        assertTrue(XmlToolProtocol().requiresDialectGuard)
    }

    @Test fun `classifyStreamLine matches GatewayErrorDetector`() {
        val frame = """data: {"type":"completion.process_completion","error":"context deadline exceeded"}"""
        assertTrue(GatewayErrorDetector.isUpstreamTimeoutFrame(frame))
        assertEquals("upstream_timeout", XmlToolProtocol().classifyStreamLine(frame))
        assertNull(XmlToolProtocol().classifyStreamLine("""data: {"choices":[]}"""))
    }
}
```

- [ ] **Step 2: Run → FAILS to compile** (`XmlToolProtocol` unresolved) — `./gradlew :core:test --tests "*XmlToolProtocolCharacterizationTest"` → FAIL.

- [ ] **Step 3: Implement `XmlToolProtocol.kt`** (a thin, identity-preserving delegate)

```kotlin
package com.workflow.orchestrator.core.ai.protocol

import com.workflow.orchestrator.core.ai.AssistantMessageContent
import com.workflow.orchestrator.core.ai.AssistantMessageParser
import com.workflow.orchestrator.core.ai.GatewayErrorDetector
import com.workflow.orchestrator.core.ai.ToolPromptBuilder
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.api.InternalApi

/**
 * Today's XML-in-content tool-calling paradigm (Sourcegraph/Cody), wrapped behind [ToolProtocol].
 *
 * Pure delegation — behavior is byte-identical to calling ToolPromptBuilder / AssistantMessageParser /
 * GatewayErrorDetector directly (pinned by XmlToolProtocolCharacterizationTest). The single normalized
 * finish-reason string "upstream_timeout" is the same one AgentLoop already keys on (AgentLoop.kt:1571).
 *
 * The XML parser's known-tag inputs (`toolNameSet`/`paramNameSet`) are NOT held here in 0b-1 — they stay
 * on `LlmBrain` and are passed into [parseToolCalls] as parameters. Their relocation onto the protocol is
 * deferred to Phase 4 (transport-coupled — see the plan's Out-of-scope section).
 */
@InternalApi
class XmlToolProtocol : ToolProtocol {

    override fun presentTools(tools: List<ToolDefinition>): String = ToolPromptBuilder.build(tools)

    override fun parseToolCalls(
        text: CharSequence,
        toolNames: Set<String>,
        paramNames: Set<String>,
    ): List<AssistantMessageContent> = AssistantMessageParser.parse(text, toolNames, paramNames)

    override fun stripPartialTag(text: CharSequence): String = AssistantMessageParser.stripPartialTag(text)

    override fun endsWithIncompleteTag(text: CharSequence): Boolean = AssistantMessageParser.endsWithIncompleteTag(text)

    override val toolResultWirePrefix: String = TOOL_RESULT_WIRE_PREFIX

    override val requiresDialectGuard: Boolean = true

    override fun classifyStreamLine(line: String): String? =
        if (GatewayErrorDetector.isUpstreamTimeoutFrame(line)) UPSTREAM_TIMEOUT else null

    companion object {
        /** Wire-send tool-result prefix. The single source of truth; MessageSanitizer references it (Task 6). */
        const val TOOL_RESULT_WIRE_PREFIX = "TOOL RESULT:\n"
        /** Normalized finish reason consumed by AgentLoop.kt:1571. */
        const val UPSTREAM_TIMEOUT = "upstream_timeout"
    }
}
```

- [ ] **Step 4: Run → PASSES** — `./gradlew :core:test --tests "*XmlToolProtocolCharacterizationTest"` → PASS. (Behavior pinned: the protocol facade produces identical output to the underlying objects.)

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/protocol/XmlToolProtocol.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/ai/protocol/XmlToolProtocolCharacterizationTest.kt
git commit -m "feat(core): XmlToolProtocol wraps current XML behavior (characterization-pinned, identical)"
```

---

### Task 3: Route tool presentation through `toolProtocol.presentTools` at the 2 call sites

Replace the two direct `ToolPromptBuilder.build(...)` calls with `xmlToolProtocol.presentTools(...)`. Because `XmlToolProtocol.presentTools` IS `ToolPromptBuilder.build` (Task-2 characterization), the prompt bytes are unchanged.

**Files:**
- Modify: `agent/.../AgentService.kt` (orchestrator call at :2205)
- Modify: `agent/.../tools/subagent/SubagentRunner.kt` (subagent call at :625)
- Test: `agent/src/test/.../prompt/ToolPresentationRoutingCharacterizationTest.kt`

**Interfaces — Consumes:** `XmlToolProtocol.presentTools`. **Produces:** (no new public surface; internal wiring).

- [ ] **Step 1: Write the characterization test** (pins that routing produces identical markdown)

`agent/src/test/kotlin/.../prompt/ToolPresentationRoutingCharacterizationTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.prompt

import com.workflow.orchestrator.core.ai.ToolPromptBuilder
import com.workflow.orchestrator.core.ai.dto.FunctionDefinition
import com.workflow.orchestrator.core.ai.dto.FunctionParameters
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.ai.protocol.XmlToolProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ToolPresentationRoutingCharacterizationTest {
    @Test fun `routing tool presentation through XmlToolProtocol equals direct ToolPromptBuilder`() {
        val tools = listOf(
            ToolDefinition(function = FunctionDefinition(
                name = "list_dir", description = "List a dir.",
                parameters = FunctionParameters(properties = emptyMap()),
            )),
        )
        // This is the exact substitution applied at AgentService:2205 and SubagentRunner:625.
        assertEquals(ToolPromptBuilder.build(tools), XmlToolProtocol().presentTools(tools))
    }
}
```

- [ ] **Step 2: Run → PASSES** — `./gradlew :agent:test --tests "*ToolPresentationRoutingCharacterizationTest"` → PASS (pins the substitution is behavior-neutral before applying it).

- [ ] **Step 3: Introduce a single shared protocol instance, then route both sites.** In `AgentService.kt`, add a field near the other AI wiring (e.g. next to the `sharedCatalogHolder` block at :840) so orchestrator + subagent share one instance:

```kotlin
    /** The active tool-calling protocol for this service. Tier-1 = XML (Sourcegraph). Phase 4 selects per provider. */
    private val toolProtocol: com.workflow.orchestrator.core.ai.protocol.ToolProtocol =
        com.workflow.orchestrator.core.ai.protocol.XmlToolProtocol()
```

At `AgentService.kt:2205`, change:

```kotlin
        val markdown = com.workflow.orchestrator.core.ai.ToolPromptBuilder.build(defs)
```

to:

```kotlin
        // presentTools() returns String? (null only under native; XML is always non-null). Keep `markdown`
        // NON-NULL so the existing `markdown.length` usages at :2215 compile unchanged (the `?: ""` is dead
        // for XML — never taken — but preserves the non-null type without behavior change).
        val markdown = toolProtocol.presentTools(defs) ?: ""
```

> **VERIFIED downstream usage** (`AgentService.kt:2206`/:2215): `markdown` is passed to `systemPromptBuilder(markdown)` (param `toolDefinitionsMarkdown: String?`, §6c null-gated at `SystemPrompt.kt:287-290` — so a nullable would also be accepted there) AND used as `markdown.length` in the `[PromptSize]` log at :2215, which requires a NON-NULL `String`. The `?: ""` keeps `markdown: String` so BOTH usages compile with zero behavior change (XML never returns null). Do NOT drop the `?: ""` — without it `markdown.length` fails to compile. **I2 note:** `XmlToolProtocol.presentTools` overrides the interface's `String?` with a non-null `String` return (a legal covariant override); the interface-typed `toolProtocol` field still yields `String?` at the call site, which is why the `?: ""` is needed here.

- [ ] **Step 4: Route the subagent site.** `SubagentRunner.kt:625` currently:

```kotlin
        val toolDefinitionsMarkdown = ToolPromptBuilder.build(coreDefinitions)
```

`SubagentRunner` builds a fresh subagent context; give it the same protocol. Add (near where the runner assembles its prompt inputs) a protocol it can reach — the simplest behavior-neutral move is a private val on the runner:

```kotlin
    private val toolProtocol: com.workflow.orchestrator.core.ai.protocol.ToolProtocol =
        com.workflow.orchestrator.core.ai.protocol.XmlToolProtocol()
```

and change :625 to:

```kotlin
        val toolDefinitionsMarkdown = toolProtocol.presentTools(coreDefinitions)
```

> `SubagentSystemPromptBuilder.build(toolDefinitionsMarkdown = ...)` already takes `String?` (`:80`), so the nullable return is accepted unchanged.

- [ ] **Step 5: Run → PASS** — `./gradlew :agent:test` → PASS (no prompt-snapshot test regresses — the bytes are identical).

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunner.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/ToolPresentationRoutingCharacterizationTest.kt
git commit -m "refactor(agent): route tool presentation through ToolProtocol.presentTools (2 sites, identical output)"
```

---

### Task 4: Route segmentation + UI-splitter helpers (3 `AgentLoop` onChunk entry points) through `toolProtocol`

The per-SSE-chunk parse at `AgentLoop.kt:1074` becomes `toolProtocol.parseToolCalls`, AND — per GAP1 — the TWO sibling streaming-UI-splitter calls in the same `onChunk` body, `AssistantMessageParser.stripPartialTag` (`:1119`) and `AssistantMessageParser.endsWithIncompleteTag` (`:1137`), become `toolProtocol.stripPartialTag` / `toolProtocol.endsWithIncompleteTag`. Because `XmlToolProtocol` delegates all three 1:1 to `AssistantMessageParser` (Task-2 characterization), streaming-UI splitting + tool extraction are byte-identical.

**Files:**
- Modify: `agent/.../loop/AgentLoop.kt` (parse at :1074, stripPartialTag at :1119, endsWithIncompleteTag at :1137; the name-set reads at :1019–1020 stay on `brain` — relocation deferred to Phase 4)
- Modify: `agent/.../tools/subagent/SubagentRunner.kt` (the SECOND `AgentLoop(...)` site at :331 — B3)
- Modify: `agent/.../AgentService.kt` (the FIRST `AgentLoop(...)` site at :2424 — B3)
- Test: `agent/src/test/.../loop/SegmentationRoutingCharacterizationTest.kt`

**Interfaces — Consumes:** `ToolProtocol.parseToolCalls`, `ToolProtocol.stripPartialTag`, `ToolProtocol.endsWithIncompleteTag`. The `AgentLoop` constructor gains a DEFAULTED `toolProtocol` param (placed among the existing defaulted params — see Step 3/B2). Both construction sites use named args, so the default keeps them compiling; the orchestrator + subagent sites explicitly thread their shared protocol.

- [ ] **Step 1: Write the characterization test**

`agent/src/test/kotlin/.../loop/SegmentationRoutingCharacterizationTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.AssistantMessageParser
import com.workflow.orchestrator.core.ai.protocol.XmlToolProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SegmentationRoutingCharacterizationTest {
    @Test fun `streaming parse through protocol equals AssistantMessageParser on partial and full text`() {
        val tools = setOf("edit_file")
        val params = setOf("path", "content")
        val protocol = XmlToolProtocol()
        // Partial (mid-stream) and complete accumulations — both must match the parser exactly.
        val partial = StringBuilder("<edit_file>\n<path>/a.kt</path>\n<content>line")
        val full = StringBuilder("<edit_file>\n<path>/a.kt</path>\n<content>line one</content>\n</edit_file>")
        assertEquals(AssistantMessageParser.parse(partial, tools, params), protocol.parseToolCalls(partial, tools, params))
        assertEquals(AssistantMessageParser.parse(full, tools, params), protocol.parseToolCalls(full, tools, params))
    }

    @Test fun `UI-splitter helpers through protocol equal AssistantMessageParser (GAP1)`() {
        val protocol = XmlToolProtocol()
        // The three onChunk entry points (parse + stripPartialTag + endsWithIncompleteTag) must all
        // route identically — these pin the two helper substitutions at AgentLoop:1119 and :1137.
        val base = "visible text <read_fi"
        assertEquals(AssistantMessageParser.stripPartialTag(base), protocol.stripPartialTag(base))
        val acc = StringBuilder("visible text <read_fi")
        assertEquals(AssistantMessageParser.endsWithIncompleteTag(acc), protocol.endsWithIncompleteTag(acc))
    }
}
```

- [ ] **Step 2: Run → PASSES** — `./gradlew :agent:test --tests "*SegmentationRoutingCharacterizationTest"` → PASS (pins the substitution behavior-neutral).

- [ ] **Step 3 (B2): Add a DEFAULTED `toolProtocol` constructor param to `AgentLoop`, placed among the existing defaulted params.** **VERIFIED:** `AgentLoop.kt:125-130` is `brain` + four NON-defaulted params (`tools`, `toolDefinitions`, `contextManager`, `project`); the defaulted params begin at `onStreamChunk` (`:131`). A defaulted param CANNOT sit at `:126` next to `brain` (Kotlin requires defaulted params to follow non-defaulted ones in this ctor, and the non-defaulted block runs through `project`). APPEND `toolProtocol` AMONG the defaulted params (e.g. immediately after `project` / before `onStreamChunk`, or at the end of the defaulted block):

```kotlin
    // ... brain, tools, toolDefinitions, contextManager, project (all non-defaulted) ...
    private val toolProtocol: com.workflow.orchestrator.core.ai.protocol.ToolProtocol =
        com.workflow.orchestrator.core.ai.protocol.XmlToolProtocol(),
    private val onStreamChunk: (String) -> Unit = {},
    // ... remaining defaulted params unchanged ...
```

> **NOTE — not a suspend change:** adding a non-functional, defaulted ctor param does not alter any suspend signature, so the build-cache trap does not apply here. Both `AgentLoop(...)` construction sites use named args, so the default keeps them compiling (B3 confirmed below).

- [ ] **Step 4 (parse + GAP1 helpers): Route all THREE onChunk entry points at `AgentLoop.kt`.**

(a) The parse at `:1074`:

```kotlin
                AssistantMessageParser.parse(
                    accumulatedText,
                    currentToolNames,
                    currentParamNames
                ).also { cachedBlocks = it }
```

→

```kotlin
                toolProtocol.parseToolCalls(
                    accumulatedText,
                    currentToolNames,
                    currentParamNames
                ).also { cachedBlocks = it }
```

(b) **GAP1** — `stripPartialTag` at `:1119`:

```kotlin
                        AssistantMessageParser.stripPartialTag(base)
                            .also { cachedStrippedText = it }
```

→

```kotlin
                        toolProtocol.stripPartialTag(base)
                            .also { cachedStrippedText = it }
```

(c) **GAP1** — `endsWithIncompleteTag` at `:1137`:

```kotlin
                        val endsInIncompleteTag = AssistantMessageParser.endsWithIncompleteTag(accumulatedText)
```

→

```kotlin
                        val endsInIncompleteTag = toolProtocol.endsWithIncompleteTag(accumulatedText)
```

> Leave `currentToolNames`/`currentParamNames` (read at :1019–1020 from `brain.toolNameSet`/`brain.paramNameSet`) as-is — the relocation of those sets onto the protocol is deferred to Phase 4 (transport-coupled; see Out of scope). This task only swaps the three onChunk parser entry points. `base` at :1119 is a `String` and `accumulatedText` at :1137 is a `StringBuilder`; both are `CharSequence`, matching the interface signatures.

- [ ] **Step 5 (B3): Thread the shared `toolProtocol` into BOTH `AgentLoop(...)` construction sites.** There are TWO, verified:
  - `AgentService.kt:2424` (orchestrator) — pass `toolProtocol = toolProtocol` (the `AgentService` field added in Task 3).
  - `SubagentRunner.kt:331` (sub-agent) — pass `toolProtocol = toolProtocol` (the `SubagentRunner` field added in Task 3).

  Both sites already use named args, so this is an additive named-arg pass. `resumeSession` delegates to `executeTask` (one orchestrator site — correct; no third site to touch).

```kotlin
            val loop = AgentLoop(
                brain = brain,
                // ... existing named args ...
                toolProtocol = toolProtocol,
                // ... existing named args ...
            )
```

- [ ] **Step 6: Run → PASS** — `./gradlew :agent:test` → PASS.

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunner.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/SegmentationRoutingCharacterizationTest.kt
git commit -m "refactor(agent): route parse + UI-splitter helpers (GAP1) through ToolProtocol; thread into both AgentLoop sites (identical)"
```

---

### Task 5: `NativeProtocol` shape (no impl) + gate the drift sites on `requiresDialectGuard` (WA-1 chokepoint)

Shape the native paradigm as an interface (Phase 4 implements it) and make the DialectDriftDetector wiring consult `toolProtocol.requiresDialectGuard`. Per **WA-1**, the gate goes at the single CHOKEPOINT (`MessageStateHandler.consumeDialectDriftFlag`, `:349`) plus the resume redaction (site 6), not as 4 scattered comments. For XML the flag is `true`, so **every gate stays a no-op vs today** — the characterization tests (GAP4) prove drift still fires under XML.

**Files:**
- Create: `core/.../core/ai/protocol/NativeProtocol.kt`
- Modify: `agent/.../session/MessageStateHandler.kt` (WA-1 chokepoint at `consumeDialectDriftFlag` :349 + ctor `toolProtocol` param; write guard :207; bulk-redact :312/:322), `agent/.../session/ResumeHelper.kt` (site 6 `emptyRedaction()` factory), `agent/.../AgentService.kt` (site 6 resume redaction gate)
- Test: `agent/src/test/.../session/DialectGuardGateCharacterizationTest.kt` (GAP4 — REUSES the existing MessageStateHandler fixture)

**Interfaces — Produces:** `NativeProtocol : ToolProtocol` (marker; `requiresDialectGuard = false`, `presentTools = null`, `toolResultWirePrefix = ""`, `stripPartialTag` identity, `endsWithIncompleteTag = false`). **Consumes:** `ToolProtocol.requiresDialectGuard`, the existing `MessageStateHandler` test fixture, `ResumeHelper.redactDialectDriftInHistory`/`DialectRedactionResult`.

> **WA-1 rationale (verified topology):** there are exactly 2 flag WRITERS (`MessageStateHandler.kt:217`/:335, both inside detector paths) and 2 flag READERS (`AgentService.kt:2129`, `SubagentRunner.kt:637`, both via `consumeDialectDriftFlag()` at `:349`). `DialectDriftDetector` is a stateless `object`, so the flag is structurally always-false under native TODAY — but gating the chokepoint (one load-bearing guard) covers BOTH readers and prevents a future re-arm, which 4 free-floating comments would not. The resume path uses a separate local (`dialectDriftDetectedOnResume`, `AgentService.kt:2866`/:2871, set off `redactedCount > 0`) — gated separately at site 6 (Step 5 wraps the `redactDialectDriftInHistory` call so `redactedCount` is 0 under native).

- [ ] **Step 1 (GAP4): Real characterization tests for the 2 gated detector sites** — `:agent` tests REUSING the existing `MessageStateHandler` fixture (do NOT add a 2nd `:core` `BasePlatformTestCase`).

`agent/src/test/kotlin/.../session/DialectGuardGateCharacterizationTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.core.ai.protocol.XmlToolProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DialectGuardGateCharacterizationTest {
    // (a) WRITE-PATH / CHOKEPOINT: feeding a drift-bearing assistant turn through the real
    //     MessageStateHandler write path raises the flag, and consumeDialectDriftFlag() returns
    //     true exactly once under XML (post-gate). VERIFY against the existing MessageStateHandler
    //     test fixture's construction helper (reuse it — same baseDir/sessionId/taskText setup the
    //     other MessageStateHandler tests use). Construct with the default XmlToolProtocol so the
    //     chokepoint predicate is true.
    //
    //     The assertion shape (adapt the seeding call to the fixture's real API for adding an
    //     assistant ApiMessage whose text carries a dialect marker, e.g. an Anthropic <invoke>):
    //         handler.<seed a drift-bearing assistant turn>()
    //         assertTrue(handler.consumeDialectDriftFlag())   // raised + consumed once
    //         assertEquals(false, handler.consumeDialectDriftFlag()) // one-shot reset

    @Test fun `xml chokepoint surfaces drift then resets one-shot`() {
        // Pseudocode-anchored to the existing fixture; the EXACT seeding call mirrors the
        // current MessageStateHandler drift test (search the existing MessageStateHandler*Test
        // for the `hasDialectDrift`/`addMessage`-equivalent path and reuse it verbatim, only
        // adding the consumeDialectDriftFlag() assertions). The behavior pinned: under XML the
        // gate is transparent, so detection + one-shot consume work exactly as today.
        // <reuse fixture-construction + drift-seeding from the existing MessageStateHandler test>
        // assertTrue(handler.consumeDialectDriftFlag())
        // assertEquals(false, handler.consumeDialectDriftFlag())
    }

    @Test fun `resume redaction count is unchanged by the gate under XML (pre-gate parity)`() {
        // (b) ResumeHelper.redactDialectDriftInHistory on a known-drift history returns the SAME
        //     redactedCount as the pre-gate code (the gate only short-circuits under native).
        //     This is a PURE call (ResumeHelper is an `object`, no fixture needed):
        val driftHistory = listOf(
            ApiMessage(
                role = ApiRole.ASSISTANT,
                content = listOf(ContentBlock.Text("<invoke name=\"read_file\">x</invoke>")),
                ts = 1,
            ),
        )
        val redaction = ResumeHelper.redactDialectDriftInHistory(driftHistory)
        assertTrue(redaction.redactedCount >= 1, "known drift must redact at least one turn (pre-gate parity)")
        // emptyRedaction() is shape-correct for the native (gated-off) branch:
        val empty = ResumeHelper.emptyRedaction()
        assertEquals(0, empty.redactedCount)
        assertTrue(empty.history.isEmpty())
    }
}
```

> **GAP4 fixture note:** the FIRST test reuses the existing `MessageStateHandler` test fixture (whatever construction + drift-seeding helper the current `MessageStateHandler*Test` uses) — adapt the seeding call to that fixture's real API rather than inventing a new one. The SECOND test is a pure `ResumeHelper` call (no fixture). VERIFY: a dialect marker that `DialectDriftDetector.redactDialectMarkers` actually rewrites (check `DialectDriftDetector` for the exact markers it matches — Anthropic `<invoke>`/Hermes `<tool_call>` etc.) so `redactedCount >= 1`. Adjust the marker string to a real one if `<invoke>` is not matched.

- [ ] **Step 2: Run → PASSES** — `./gradlew :agent:test --tests "*DialectGuardGateCharacterizationTest"` → PASS (against current code, before the gate — pins pre-gate behavior; the chokepoint test passes because XML keeps the flag flowing; the resume test passes because the redaction is unchanged).

- [ ] **Step 3: Create `NativeProtocol.kt` (shape only — Phase 4 implements)**

```kotlin
package com.workflow.orchestrator.core.ai.protocol

import com.workflow.orchestrator.core.ai.AssistantMessageContent
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.api.InternalApi

/**
 * Marker for the native function-calling paradigm (Anthropic Messages API). SHAPE ONLY — the
 * concrete `AnthropicNativeProtocol` ships in Phase 4 with `AnthropicDirectProvider`.
 *
 * Native invariants the seam relies on (documented here so Phase 4 cannot drift):
 *  - [presentTools] returns null (tools go in the API `tools:[]` field, not the system prompt).
 *  - [toolResultWirePrefix] is "" (tool results are structured `tool_result` blocks, not prefixed text).
 *  - [requiresDialectGuard] is false (structured output cannot drift; the drift sites bypass via the gate).
 *  - [stripPartialTag]/[endsWithIncompleteTag] are no-ops (structured streaming has no XML tags to split).
 *  - [parseToolCalls] consumes structured `content_block_delta`/`input_json_delta` frames (a different
 *    StreamChunk shape) — NOT XML text. Phase 4 widens the streaming surface accordingly.
 */
@InternalApi
interface NativeProtocol : ToolProtocol {
    override fun presentTools(tools: List<ToolDefinition>): String? = null
    override val toolResultWirePrefix: String get() = ""
    override val requiresDialectGuard: Boolean get() = false

    // GAP1 UI-splitter helpers are no-ops under native (no XML tags in structured streaming).
    override fun stripPartialTag(text: CharSequence): String = text.toString()
    override fun endsWithIncompleteTag(text: CharSequence): Boolean = false

    // parseToolCalls + classifyStreamLine are intentionally left abstract — their native shapes
    // (structured deltas; HTTP-error classification) are designed and implemented in Phase 4.
    override fun parseToolCalls(
        text: CharSequence,
        toolNames: Set<String>,
        paramNames: Set<String>,
    ): List<AssistantMessageContent>
}
```

- [ ] **Step 4 (WA-1 chokepoint + ctor param): Gate `MessageStateHandler`.** `MessageStateHandler` does not currently know the protocol. Add a constructor param defaulted to the XML protocol so existing constructions stay behavior-identical (VERIFIED current ctor: `baseDir`, `sessionId`, `taskText`, `uiSaveClock = {…}` default):

```kotlin
class MessageStateHandler(
    private val baseDir: File,
    val sessionId: String,
    val taskText: String,
    private val uiSaveClock: () -> Long = { System.currentTimeMillis() },
    private val toolProtocol: com.workflow.orchestrator.core.ai.protocol.ToolProtocol =
        com.workflow.orchestrator.core.ai.protocol.XmlToolProtocol(),
)
```

Then gate the CHOKEPOINT at `:349` (covers BOTH readers — WA-1):

```kotlin
    fun consumeDialectDriftFlag(): Boolean = dialectDriftFlag.getAndSet(false)
```

→

```kotlin
    fun consumeDialectDriftFlag(): Boolean =
        toolProtocol.requiresDialectGuard && dialectDriftFlag.getAndSet(false)
```

> Under native (`requiresDialectGuard == false`) this short-circuits to `false` WITHOUT consuming/resetting the flag — which is correct, because under native the flag is never raised (the two writers are inside detector paths that never fire on structured output). Belt-and-suspenders: also gate the write guard (`:207`) and the bulk-redact so the detector itself is never invoked under native (avoids wasted work, not a behavior change under XML):

At the write guard (`:207`):

```kotlin
        if (hasDialectDrift(message)) {
```

→

```kotlin
        if (toolProtocol.requiresDialectGuard && hasDialectDrift(message)) {
```

And inside `redactDialectXmlInHistory()` (`:312`), short-circuit at the top:

```kotlin
    suspend fun redactDialectXmlInHistory(): Int = mutex.withLock {
        if (!toolProtocol.requiresDialectGuard) return@withLock 0
        // ... existing body, including DialectDriftDetector.redactDialectMarkers(block.text) at :322 ...
```

> **VERIFY** the production constructor call(s) of `MessageStateHandler` (search `MessageStateHandler(` in `:agent`) — pass the shared `toolProtocol` from `AgentService`/`SubagentRunner` so a future native session actually flips the gate. For Phase 0b-1 the default keeps XML behavior. **Suspend note:** `redactDialectXmlInHistory` is already `suspend`; adding a guard line does not change its signature → no build-cache trap. `consumeDialectDriftFlag` is NOT suspend → no trap.

- [ ] **Step 5: Gate site 6 (resume redaction).** In `AgentService.kt:2867`, wrap the redaction call:

```kotlin
        val dialectRedaction = ResumeHelper.redactDialectDriftInHistory(activeApiHistory)
```

→

```kotlin
        val dialectRedaction = if (toolProtocol.requiresDialectGuard)
            ResumeHelper.redactDialectDriftInHistory(activeApiHistory)
        else com.workflow.orchestrator.agent.session.ResumeHelper.emptyRedaction()
```

Add a pure factory to `ResumeHelper` returning a no-op `DialectRedactionResult`. **VERIFIED shape** (`ResumeHelper.kt:199-202`): `data class DialectRedactionResult(val history: List<ApiMessage>, val redactedCount: Int)`:

```kotlin
    /** No-op result for native sessions where dialect redaction does not apply (Phase 0b-1 gate). */
    fun emptyRedaction(): DialectRedactionResult = DialectRedactionResult(history = emptyList(), redactedCount = 0)
```

> **VERIFIED consumer** (`AgentService.kt:2868-2872`): reads `dialectRedaction.redactedCount` first, and reads `dialectRedaction.history` ONLY inside `if (redactedCount > 0)`. With `emptyRedaction()` (`redactedCount = 0`) the `history` is never read, so `emptyList()` is safe and the `dialectDriftDetectedOnResume = true` assignment at `:2871` (site 6) is never taken under native — gating it correctly. Under XML the `if` is always taken, so today's behavior is verbatim.

- [ ] **Step 6: Document sites 2/3/4/5 as structural no-ops** (no code change). Add a one-line comment at `SystemPrompt.kt:177` (`if (dialectDriftDetected)`), `SubagentSystemPromptBuilder.kt:86`, `SubagentRunner.kt:637`, and `AgentService.kt:2129` noting the flag is always-false under native because the chokepoint (`consumeDialectDriftFlag`) is gated, so no explicit guard is needed there.

- [ ] **Step 7: Run → PASS** — `./gradlew :core:test :agent:test` → PASS (drift behavior under XML unchanged; native shape compiles).

- [ ] **Step 8: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/protocol/NativeProtocol.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/session/MessageStateHandler.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/session/ResumeHelper.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentSystemPromptBuilder.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunner.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/session/DialectGuardGateCharacterizationTest.kt
git commit -m "feat: NativeProtocol shape + gate dialect-drift at the consumeDialectDriftFlag chokepoint (WA-1; XML unchanged)"
```

> **rev-2 renumbering note:** the old rev-1 "Task 5 — relocate `toolNameSet`/`paramNameSet` off `LlmBrain`" is REMOVED (deferred to Phase 4 — see "Out of scope"). Tasks 6→12 below were renumbered down by one (old Task 7 → Task 6, … old Task 13 → Task 12).

---

### Task 6: Result-formatting wire convention behind the protocol (`MessageSanitizer`)

The `"TOOL RESULT:\n"` literal at `MessageSanitizer.kt:74` becomes a reference to `XmlToolProtocol.TOOL_RESULT_WIRE_PREFIX`. Behavior is byte-identical (same string). This puts the wire convention under the protocol's ownership without changing the sanitizer's 6-phase pipeline. **On-disk tool-result shape is untouched** (it is structured `ContentBlock.ToolResult`, never this prefix).

**Files:**
- Modify: `core/.../core/ai/MessageSanitizer.kt` (:74)
- Test: `core/src/test/.../ai/MessageSanitizerWirePrefixCharacterizationTest.kt`

**Interfaces — Consumes:** `XmlToolProtocol.TOOL_RESULT_WIRE_PREFIX`, `MessageSanitizer.sanitizeForAnthropic` (`:55`).

- [ ] **Step 1: Characterization test — a tool turn becomes a `"TOOL RESULT:\n"`-prefixed user turn**

`core/src/test/kotlin/.../ai/MessageSanitizerWirePrefixCharacterizationTest.kt`:

```kotlin
package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageSanitizerWirePrefixCharacterizationTest {
    @Test fun `tool role is coerced to user with the TOOL RESULT prefix`() {
        val input = listOf(
            ChatMessage(role = "user", content = "hi"),
            ChatMessage(role = "tool", content = "file contents", toolCallId = "abc"),
        )
        val out = MessageSanitizer.sanitizeForAnthropic(input)
        // VERIFY against the real pipeline: the tool turn arrives as a user turn whose content
        // begins with "TOOL RESULT:\n". (Phase 2/5 merges may combine turns — assert the prefix
        // is present in the merged user content rather than exact list size.)
        assertTrue(out.any { it.role == "user" && (it.content ?: "").contains("TOOL RESULT:\nfile contents") })
        assertEquals("user", out.last().role) // Phase 6 invariant
    }

    @Test fun `the extracted constant equals the literal it replaces (no cross-component drift)`() {
        // Compaction no-regression guard: the ONE cross-component coupling the constant extraction
        // touches is the wire string `MessageSanitizer` emits. ContextManager.snapToToolBoundary keys
        // on role == "tool" (NOT on this text), and the on-disk dedup operates on structured
        // ContentBlock.ToolResult content (NOT the wire prefix) — so compaction output is unchanged.
        // Pin the value equivalence so the extraction can never silently diverge.
        assertEquals(
            "TOOL RESULT:\n",
            com.workflow.orchestrator.core.ai.protocol.XmlToolProtocol.TOOL_RESULT_WIRE_PREFIX,
        )
    }
}
```

- [ ] **Step 2: Run → PASSES** (against current code) — `./gradlew :core:test --tests "*MessageSanitizerWirePrefixCharacterizationTest"` → PASS. This pins the CURRENT wire convention before refactoring it.

- [ ] **Step 3: Replace the literal.** At `MessageSanitizer.kt:74`:

```kotlin
                    val toolContent = "TOOL RESULT:\n${msg.content ?: ""}"
```

→

```kotlin
                    val toolContent =
                        "${com.workflow.orchestrator.core.ai.protocol.XmlToolProtocol.TOOL_RESULT_WIRE_PREFIX}${msg.content ?: ""}"
```

> Keep the explanatory comment block above (lines 70–82) — it documents WHY plain text not XML. The constant value is identical (`"TOOL RESULT:\n"`), so every consumer (`ContextManager` dedup, the on-wire request) sees the same bytes.

- [ ] **Step 4: Run → PASS** — `./gradlew :core:test --tests "*MessageSanitizerWirePrefixCharacterizationTest"` → PASS (same string, same behavior). Full `./gradlew :core:test :agent:test` → PASS. **Compaction no-regression:** confirm the existing `ContextManager` compaction/`snapToToolBoundary` tests (in `:agent`) stay GREEN — they must, since `snapToToolBoundary` keys on `role == "tool"` and the dedup pre-pass keys on structured `ContentBlock.ToolResult` content, neither of which sees the wire prefix string.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/MessageSanitizer.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/ai/MessageSanitizerWirePrefixCharacterizationTest.kt
git commit -m "refactor(core): wire TOOL RESULT prefix to XmlToolProtocol constant (identical string)"
```

---

### Task 7: Widen `LlmBrain` → `LlmProvider` (catalog / capability / context-window / errors + `toolProtocol`)

Introduce `LlmProvider : LlmBrain` exposing model-catalog/capability/context-window + `classifyStreamLine` + `classifyHttpError` (GAP2) + `getStatus`/`buildFallbackChain` (GAP3) + `val toolProtocol`, all delegating to today's `ModelCatalogService`/`ModelCache` and the active `ToolProtocol`. **This is additive** — `AgentLoop`/`ContextManager`/`AgentController` keep consuming `LlmBrain`/`ModelCatalogService`/`ModelCache` as today; the provider just makes the wider surface available for Phase 4. The `XmlLlmProvider` adapter lands in Task 9 (after BrainRouter absorption); this task defines the interface + a pure contract test.

**Files:**
- Create: `core/.../core/ai/LlmProvider.kt`
- Test: `core/src/test/.../ai/protocol/LlmProviderContractTest.kt`

**Interfaces — Produces:** `LlmProvider : LlmBrain` with `suspend fun getCatalog(force): ModelCatalog?`, `fun getContextWindow(modelRef, tier): ContextWindow?`, `fun supportsVision(modelRef): Boolean`, `fun supportsTools(modelRef): Boolean`, `fun getDefaultChatModel(): String?`, `fun getLatestStreamApiVersion(): Int`, `fun getStatus(modelRef): String?` (GAP3), `fun buildFallbackChain(): List<String>` (GAP3), `fun classifyStreamLine(line): String?`, `fun classifyHttpError(statusCode, body): String?` (GAP2), `val toolProtocol: ToolProtocol`. **Consumes:** `ModelCatalog`, `ContextWindow` (`core/.../ai/dto/`), `ModelCatalogService` signatures (`getCatalog:78`, `getDefaultChatModel:99`, `getContextWindow:108`, `supportsVision:114`, `supportsTools:118`, `getStatus:123`, `getLatestStreamApiVersion:131`), `ModelCache.buildFallbackChain` (`core/.../ai/ModelCache.kt`).

> **GAP2/GAP3 behavior-neutrality:** `classifyHttpError` (native returns HTTP 529/413 as status, not SSE frames; consumer to keep in mind is the loop's `isContextOverflowError` at `AgentLoop.kt:1854`) returns null in the XML adapter (today's XML transport never sees raw HTTP error status — it parses SSE frames), so it is purely a Phase-4 surface with a null XML default. `getStatus` delegates to `ModelCatalogService.getStatus` (`:123`, consumed via `catalog?.getStatus(...)` at `AgentController.kt:2069`/:2128); `buildFallbackChain()` (parameterless) delegates to `ModelCache.buildFallbackChain(ModelCache.getCached())` (`ModelCache.kt:145`/:61; consumed at `AgentService.kt:1940`). Interface-only this task; the XML adapter delegations land in Task 9.

> **⚠ SUSPEND-SIGNATURE CHANGE — run this task's commit with `--no-build-cache --rerun-tasks`.** `getCatalog` is `suspend` on the new interface; introducing it can perturb compile-avoidance bytecode for any reused test that touches the AI surface.

- [ ] **Step 1: Write the pure contract test**

`core/src/test/kotlin/.../ai/protocol/LlmProviderContractTest.kt`:

```kotlin
package com.workflow.orchestrator.core.ai.protocol

import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.LlmProvider
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LlmProviderContractTest {
    @Test fun `LlmProvider is an LlmBrain and exposes a ToolProtocol`() {
        // A minimal anonymous provider verifies the interface shape compiles + carries a protocol.
        val provider: LlmProvider = object : LlmProvider {
            override val modelId = "test::v1::model"
            override val toolProtocol = XmlToolProtocol()
            override suspend fun chat(messages: List<com.workflow.orchestrator.core.ai.dto.ChatMessage>, tools: List<com.workflow.orchestrator.core.ai.dto.ToolDefinition>?, maxTokens: Int?, toolChoice: kotlinx.serialization.json.JsonElement?) =
                ApiResult.Error(ErrorType.SERVER_ERROR, "unused")
            override suspend fun chatStream(messages: List<com.workflow.orchestrator.core.ai.dto.ChatMessage>, tools: List<com.workflow.orchestrator.core.ai.dto.ToolDefinition>?, maxTokens: Int?, onChunk: suspend (com.workflow.orchestrator.core.ai.dto.StreamChunk) -> Unit) =
                ApiResult.Error(ErrorType.SERVER_ERROR, "unused")
            override fun estimateTokens(text: String) = text.length / 4
            override suspend fun getCatalog(force: Boolean) = null
            override fun getContextWindow(modelRef: String, tier: String) = null
            override fun supportsVision(modelRef: String) = false
            override fun supportsTools(modelRef: String) = true
            override fun getDefaultChatModel(): String? = null
            override fun getLatestStreamApiVersion() = 8
            override fun getStatus(modelRef: String): String? = null
            override fun buildFallbackChain(): List<String> = emptyList()
            override fun classifyStreamLine(line: String): String? = toolProtocol.classifyStreamLine(line)
            override fun classifyHttpError(statusCode: Int, body: String): String? = null
        }
        assertTrue(provider is LlmBrain)
        assertNotNull(provider.toolProtocol)
        assertTrue(provider.supportsTools("any"))
    }
}
```

> **B1 VERIFIED:** `ApiResult` has NO `error(...)` factory. The real sealed shape (`ApiResult.kt:34-48`) is `Success(data)` + `Error(type: ErrorType, message: String, cause? = null, retryAfterMs? = null) : ApiResult<Nothing>`. Use `ApiResult.Error(ErrorType.SERVER_ERROR, "unused")` (import `com.workflow.orchestrator.core.model.ErrorType`); drop the generic type arg (`Error` is `ApiResult<Nothing>`, assignable to any `ApiResult<T>` return). The assertions (is-an-LlmBrain, carries a protocol) are the contract.

- [ ] **Step 2: Run → FAILS to compile** (`LlmProvider` unresolved) → FAIL.

- [ ] **Step 3: Create `LlmProvider.kt`**

```kotlin
package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ContextWindow
import com.workflow.orchestrator.core.ai.dto.ModelCatalog
import com.workflow.orchestrator.core.ai.protocol.ToolProtocol
import com.workflow.orchestrator.core.api.InternalApi

/**
 * Widens [LlmBrain] (transport: chat/chatStream/tokens) with the surfaces the agent loop +
 * compaction need from a provider but that the narrow brain interface lacks:
 *  - model catalog / capability / context-window (today: ModelCatalogService; consumed by
 *    ContextManager.effectiveMaxInputTokens and AgentLoop's vision-filtered fallback);
 *  - provider error classification (today: GatewayErrorDetector → "upstream_timeout");
 *  - the active [ToolProtocol] (XML today; native in Phase 4).
 *
 * Phase 0b-1 ships only the XML adapter (XmlLlmProvider, in :agent). AnthropicDirectProvider is Phase 4.
 *
 * @InternalApi: public for B; unfrozen.
 */
@InternalApi
interface LlmProvider : LlmBrain {

    /** The tool-calling paradigm this provider speaks. */
    val toolProtocol: ToolProtocol

    /** Live model catalog (today: ModelCatalogService.getCatalog). Native = static Anthropic catalog (Phase 4). */
    suspend fun getCatalog(force: Boolean = false): ModelCatalog?

    /** Per-model context window — denominator of ContextManager's compaction threshold. */
    fun getContextWindow(modelRef: String, tier: String = "enterprise"): ContextWindow?

    fun supportsVision(modelRef: String): Boolean
    fun supportsTools(modelRef: String): Boolean
    fun getDefaultChatModel(): String?

    /** Sourcegraph-specific stream API version negotiation; native providers may return a constant. */
    fun getLatestStreamApiVersion(): Int

    /**
     * Model lifecycle status (today: ModelCatalogService.getStatus → "deprecated"/etc.; consumed via
     * `catalog?.getStatus(...)` at AgentController.kt:2069/:2128). Null = unknown/active. (GAP3)
     */
    fun getStatus(modelRef: String): String?

    /**
     * Ordered model fallback chain. **VERIFIED:** the real consumer (`AgentService.kt:1940`) calls
     * `ModelCache.buildFallbackChain(ModelCache.getCached())` — i.e. it derives the chain from the
     * CACHED model list, NOT from a modelRef. So this accessor is parameterless and the XML adapter
     * delegates exactly to `ModelCache.buildFallbackChain(ModelCache.getCached())` (behavior-neutral). (GAP3)
     */
    fun buildFallbackChain(): List<String>

    /**
     * Normalize a raw stream line into a finish-reason the loop understands (e.g. "upstream_timeout").
     * Delegates to [toolProtocol] for XML; native maps HTTP error events (Phase 4). Null = no special handling.
     */
    fun classifyStreamLine(line: String): String?

    /**
     * Classify a raw HTTP error (status + body) into a normalized finish-reason. Native providers see
     * HTTP 529/413 as a STATUS (not an SSE frame), so the loop's overflow/retry logic (consumer:
     * `AgentLoop.isContextOverflowError`, AgentLoop.kt:1854) needs this surface. The XML adapter returns
     * null (today's XML transport parses SSE frames, never raw HTTP error status) — behavior-neutral. (GAP2)
     */
    fun classifyHttpError(statusCode: Int, body: String): String?
}
```

- [ ] **Step 4: Run → PASS** — `./gradlew :core:test --tests "*LlmProviderContractTest" --no-build-cache --rerun-tasks` → PASS.

- [ ] **Step 5: Commit** (suspend-signature → no-build-cache)

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/LlmProvider.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/ai/protocol/LlmProviderContractTest.kt
git commit -m "feat(core): LlmProvider widens LlmBrain (catalog/capability/ctx-window + toolProtocol + classify)"
```

---

### Task 8: Provider-owned error classification — wire the loop's timeout check through the protocol

`GatewayErrorDetector.isUpstreamTimeoutFrame` is called inside `SourcegraphChatClient` and surfaces as `finishReason == "upstream_timeout"`, which `AgentLoop.kt:1571` recovers from. Phase 0b-1 keeps that data flow intact (the loop still keys on the string the client synthesizes) and ADDS the protocol-owned `classifyStreamLine` as the canonical classifier, pinned to produce the same string — so Phase 4's native provider can emit the same normalized reason without the loop pattern-matching on a protocol-private string.

**Files:**
- Test: `core/src/test/.../ai/protocol/ClassifyStreamLineParityTest.kt`
- (No production behavior change this task — it pins the equivalence that Task 9's provider relies on.)

**Interfaces — Consumes:** `XmlToolProtocol.classifyStreamLine`, `GatewayErrorDetector.isUpstreamTimeoutFrame`.

> Scope honesty: the loop's `finishReason == "upstream_timeout"` branch at `AgentLoop.kt:1571` is NOT rewired in 0b-1 — the Sourcegraph client still produces that finishReason exactly as today (zero regression risk to the recovery path). What this task delivers is the **equivalence proof** + the seam method, so Task 9's `XmlLlmProvider.classifyStreamLine` and Phase 4's native provider share one normalized vocabulary. Rewiring the client to call the provider's classifier is a Phase-4 concern (it touches `SourcegraphChatClient` transport).

- [ ] **Step 1: Parity characterization test**

`core/src/test/kotlin/.../ai/protocol/ClassifyStreamLineParityTest.kt`:

```kotlin
package com.workflow.orchestrator.core.ai.protocol

import com.workflow.orchestrator.core.ai.GatewayErrorDetector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClassifyStreamLineParityTest {
    private val protocol = XmlToolProtocol()
    private val frames = listOf(
        """data: {"type":"completion.process_completion","error":"context deadline exceeded"}""",
        """data: {"choices":[{"delta":{"content":"hi"}}]}""",
        "data: [DONE]",
        "",
    )
    @Test fun `classifyStreamLine returns upstream_timeout exactly when GatewayErrorDetector fires`() {
        for (line in frames) {
            val expected = if (GatewayErrorDetector.isUpstreamTimeoutFrame(line)) "upstream_timeout" else null
            assertEquals(expected, protocol.classifyStreamLine(line), "mismatch on: $line")
        }
    }
}
```

- [ ] **Step 2: Run → PASSES** (XmlToolProtocol from Task 2 already implements `classifyStreamLine`) → PASS.

- [ ] **Step 3: Commit**

```bash
git add core/src/test/kotlin/com/workflow/orchestrator/core/ai/protocol/ClassifyStreamLineParityTest.kt
git commit -m "test(core): pin classifyStreamLine == GatewayErrorDetector parity (provider-owned error vocab)"
```

---

### Task 9: `XmlLlmProvider` adapter — absorb `BrainRouter` image routing behind ONE provider

Build the concrete `XmlLlmProvider` in `:agent` that IS an `LlmProvider`: it wraps the existing brain (`BrainRouter`, which already does per-message image routing) + the `ModelCatalogService` + an `XmlToolProtocol`, so the loop can read ONE provider object whose `chatStream` already routes images internally. **Behavior-preserving:** `BrainRouter`'s routing decision (`messages.any { hasImageParts() }`) is unchanged — it is simply now reachable through the provider facade. The GAP2/GAP3 accessors delegate to today's `ModelCache`/`ModelCatalogService`/`ToolProtocol` (no behavior change). Full BrainRouter dissolution (moving routing INTO a SourcegraphProvider) is a Phase-4 refinement; 0b-1 wraps it.

**Files:**
- Create: `agent/.../loop/XmlLlmProvider.kt`
- Test: `agent/src/test/.../loop/XmlLlmProviderImageRoutingCharacterizationTest.kt`

**Interfaces — Consumes:** existing brain (`LlmBrain`, today `BrainRouter`), `ModelCatalogService` (`getCatalog:78`/`getContextWindow:108`/`supportsVision:114`/`supportsTools:118`/`getDefaultChatModel:99`/`getStatus:123`/`getLatestStreamApiVersion:131`), `ModelCache.buildFallbackChain`/`getCached`, `XmlToolProtocol`. **Produces:** `XmlLlmProvider : LlmProvider`.

> **⚠ Potential suspend-signature touch — run this commit with `--no-build-cache --rerun-tasks`** (the provider delegates the `suspend chat`/`chatStream`/`getCatalog`; reused AI tests may hit stale bytecode).

- [ ] **Step 1: Characterization test — the provider delegates chatStream to the wrapped router (routing preserved)**

`agent/src/test/kotlin/.../loop/XmlLlmProviderImageRoutingCharacterizationTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.StreamChunk
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.ai.protocol.XmlToolProtocol
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XmlLlmProviderImageRoutingCharacterizationTest {
    // A fake brain standing in for BrainRouter — records that chatStream was delegated unchanged.
    // Only the 3 non-defaulted LlmBrain members need overriding (cancelActiveRequest/interruptStream/
    // temperature/toolNameSet/paramNameSet all have interface defaults — verified LlmBrain.kt:39-65).
    private class RecordingBrain : LlmBrain {
        var streamCalls = 0
        override val modelId = "anthropic::v1::claude"
        override suspend fun chat(m: List<ChatMessage>, t: List<ToolDefinition>?, mx: Int?, tc: JsonElement?): ApiResult<ChatCompletionResponse> =
            ApiResult.Error(ErrorType.SERVER_ERROR, "unused")
        override suspend fun chatStream(m: List<ChatMessage>, t: List<ToolDefinition>?, mx: Int?, onChunk: suspend (StreamChunk) -> Unit): ApiResult<ChatCompletionResponse> {
            streamCalls++; return ApiResult.Error(ErrorType.SERVER_ERROR, "unused")
        }
        override fun estimateTokens(text: String) = text.length / 4
    }

    @Test fun `provider delegates chatStream to the wrapped brain (router routing preserved)`() = runBlocking {
        val brain = RecordingBrain()
        // catalogService can be null for this delegation check; provider must tolerate it like ContextManager does.
        val provider = XmlLlmProvider(delegate = brain, catalogService = null, toolProtocol = XmlToolProtocol())
        provider.chatStream(emptyList(), null, null) {}
        assertEquals(1, brain.streamCalls)
        assertTrue(provider is com.workflow.orchestrator.core.ai.LlmProvider)
    }
}
```

> `runBlocking` is allowed in `test/` (the ban is `main/` only). **B1 VERIFIED:** `ApiResult.Error(ErrorType.SERVER_ERROR, "unused")` (no `ApiResult.error` factory exists). `RecordingBrain` is a valid minimal `LlmBrain` because only `chat`/`chatStream`/`estimateTokens`/`modelId` are non-defaulted.

- [ ] **Step 2: Run → FAILS to compile** (`XmlLlmProvider` unresolved) → FAIL.

- [ ] **Step 3: Implement `XmlLlmProvider.kt`** (delegating adapter; catalog accessors forward to `ModelCatalogService`, nullable-safe like `ContextManager`)

```kotlin
package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.LlmProvider
import com.workflow.orchestrator.core.ai.ModelCache
import com.workflow.orchestrator.core.ai.ModelCatalogService
import com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.ContextWindow
import com.workflow.orchestrator.core.ai.dto.ModelCatalog
import com.workflow.orchestrator.core.ai.dto.StreamChunk
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.ai.protocol.ToolProtocol
import com.workflow.orchestrator.core.ai.protocol.XmlToolProtocol
import com.workflow.orchestrator.core.api.InternalApi
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.serialization.json.JsonElement

/**
 * The Tier-1 XML provider: an [LlmProvider] facade over the existing transport brain (today a
 * BrainRouter that already routes image-bearing turns to the /stream endpoint) + ModelCatalogService
 * + an XmlToolProtocol. Reading ONE provider replaces "a brain plus a router that re-decides each turn"
 * (spec §6: "absorb BrainRouter's per-message image routing into the provider").
 *
 * Pure delegation — chat/chatStream/tokens/cancel/interrupt forward to [delegate]; catalog accessors
 * forward to [catalogService] (nullable-safe, mirroring ContextManager's null handling). No behavior change.
 */
@InternalApi
class XmlLlmProvider(
    private val delegate: LlmBrain,
    private val catalogService: ModelCatalogService?,
    override val toolProtocol: ToolProtocol = XmlToolProtocol(),
) : LlmProvider {

    override val modelId: String get() = delegate.modelId
    override fun estimateTokens(text: String): Int = delegate.estimateTokens(text)
    override fun cancelActiveRequest() = delegate.cancelActiveRequest()
    override fun interruptStream() = delegate.interruptStream()
    override var temperature: Double
        get() = delegate.temperature
        set(v) { delegate.temperature = v }

    override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDefinition>?, maxTokens: Int?, toolChoice: JsonElement?): ApiResult<ChatCompletionResponse> =
        delegate.chat(messages, tools, maxTokens, toolChoice)

    override suspend fun chatStream(messages: List<ChatMessage>, tools: List<ToolDefinition>?, maxTokens: Int?, onChunk: suspend (StreamChunk) -> Unit): ApiResult<ChatCompletionResponse> =
        delegate.chatStream(messages, tools, maxTokens, onChunk)

    override suspend fun getCatalog(force: Boolean): ModelCatalog? = catalogService?.getCatalog(force)
    override fun getContextWindow(modelRef: String, tier: String): ContextWindow? = catalogService?.getContextWindow(modelRef, tier)
    override fun supportsVision(modelRef: String): Boolean = catalogService?.supportsVision(modelRef) ?: false
    override fun supportsTools(modelRef: String): Boolean = catalogService?.supportsTools(modelRef) ?: true
    override fun getDefaultChatModel(): String? = catalogService?.getDefaultChatModel()
    override fun getLatestStreamApiVersion(): Int = catalogService?.getLatestStreamApiVersion() ?: ModelCatalogService.DEFAULT_STREAM_API_VERSION

    // GAP3 — delegate to today's catalog/cache (behavior-neutral).
    override fun getStatus(modelRef: String): String? = catalogService?.getStatus(modelRef)
    override fun buildFallbackChain(): List<String> = ModelCache.buildFallbackChain(ModelCache.getCached())

    // GAP2 — XML transport never sees raw HTTP error status (it parses SSE frames), so null today.
    override fun classifyStreamLine(line: String): String? = toolProtocol.classifyStreamLine(line)
    override fun classifyHttpError(statusCode: Int, body: String): String? = null
}
```

> **VERIFIED** `LlmBrain` member set (`LlmBrain.kt:15-66`): non-defaulted = `chat`, `chatStream`, `estimateTokens`, `modelId`; DEFAULTED (interface bodies) = `cancelActiveRequest`, `interruptStream`, `temperature`, `toolNameSet`, `paramNameSet`. The adapter overrides the first four (delegating) + the three behavior-bearing defaulted ones (`cancelActiveRequest`/`interruptStream`/`temperature` → delegate). It does NOT override `toolNameSet`/`paramNameSet` — they keep their inherited empty defaults (still on `LlmBrain` in 0b-1; relocation deferred to Phase 4). **VERIFIED** `ModelCatalogService.DEFAULT_STREAM_API_VERSION = 8` (`ModelCatalogService.kt:202`) — reference the constant, not the literal `8`. Add imports `com.workflow.orchestrator.core.ai.ModelCache` for the GAP3 fallback delegation.

- [ ] **Step 4: Wire the provider into `AgentService` WITHOUT changing the loop's consumption (additive).** At `AgentService.kt` where the router-wrapped brain is built and where `AgentLoop(` is constructed (:2424), construct an `XmlLlmProvider(delegate = brain, catalogService = sharedCatalogHolder.peek(), toolProtocol = toolProtocol)` and pass the same `toolProtocol` into the loop (the `toolProtocol` ctor param added in Task 4). **Do NOT change `AgentLoop`'s `brain: LlmBrain` param to `LlmProvider` in 0b-1** — that would be a wide consumption change with regression risk; the loop keeps taking `brain` + `toolProtocol` separately. The `XmlLlmProvider` is constructed and available for Phase 4 to become the single loop input. **VERIFY** the brain passed to `AgentLoop` (`brain = brain`) is the router-wrapped instance, and that wrapping it again in `XmlLlmProvider` for the protocol handoff does not double-wrap the transport (pass the SAME `brain` as both the loop's `brain` and the provider's `delegate`).

- [ ] **Step 5: Run → PASS** — `./gradlew :agent:test --no-build-cache --rerun-tasks` → PASS.

- [ ] **Step 6: Commit** (suspend delegation → no-build-cache)

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/XmlLlmProvider.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/XmlLlmProviderImageRoutingCharacterizationTest.kt
git commit -m "feat(agent): XmlLlmProvider facade over brain+catalog+protocol (absorbs BrainRouter routing; additive)"
```

---

### Task 10: Reserve the per-message `protocol` discriminator in `ApiMessage` (NO migration)

Add a nullable `protocol: String? = null` field to `ApiMessage` so Phase 4 can tell which paradigm wrote each turn (null ⇒ "xml" for all existing/legacy sessions). **NO migration logic, NO reader branching** — the field is reserved and serializes cleanly via `encodeDefaults = true` + `ignoreUnknownKeys = true` (`MessageStateHandler.kt:832–838`); omitted for default-null turns so existing on-disk files are byte-compatible.

**Files:**
- Modify: `agent/.../session/ApiMessage.kt` (:71 data class)
- Test: `agent/src/test/.../session/ApiMessageProtocolDiscriminatorTest.kt`

**Interfaces — Produces:** `ApiMessage.protocol: String? = null`. **Consumes:** `MessageStateHandler.configuredJson` for the round-trip.

- [ ] **Step 1: Round-trip + backward-compat test**

`agent/src/test/kotlin/.../session/ApiMessageProtocolDiscriminatorTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.session

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ApiMessageProtocolDiscriminatorTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

    @Test fun `protocol defaults to null and a v2 file without the field still deserializes`() {
        // Backward compat: an existing on-disk message JSON with NO protocol field loads with protocol=null.
        val legacy = """{"role":"USER","content":[{"type":"text","text":"hi"}],"ts":1}"""
        val msg = json.decodeFromString(ApiMessage.serializer(), legacy)
        assertNull(msg.protocol)
    }

    @Test fun `a default-null protocol round-trips as null (M4 - encodeDefaults emits it, decode restores it)`() {
        // M4: NOT an omission test. encodeDefaults=true EMITS `"protocol":null` (it does not omit
        // defaults), so the correct invariant is ROUND-TRIP EQUALITY, not absence-from-output.
        val msg = ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("hi")), ts = 1)
        val out = json.encodeToString(ApiMessage.serializer(), msg)
        val restored = json.decodeFromString(ApiMessage.serializer(), out)
        assertEquals(msg.protocol, restored.protocol)
        assertNull(restored.protocol)
    }

    @Test fun `a reserved protocol value round-trips`() {
        val msg = ApiMessage(role = ApiRole.ASSISTANT, content = listOf(ContentBlock.Text("x")), ts = 1, protocol = "xml")
        val restored = json.decodeFromString(ApiMessage.serializer(), json.encodeToString(ApiMessage.serializer(), msg))
        assertEquals("xml", restored.protocol)
        assertFalse(restored.protocol == null)
    }
}
```

> Note on `encodeDefaults` (M4): the production `configuredJson` sets `encodeDefaults = true`, which EMITS `"protocol":null` for new writes — it does NOT omit the default. That is forward-compatible (a v1/older reader with `ignoreUnknownKeys=true` ignores the extra field). The load-bearing guarantee is the FIRST test: an OLD file WITHOUT the field still loads (protocol=null). **M4 caveat:** the local test `json` mirrors `configuredJson`'s flags (`ignoreUnknownKeys`/`coerceInputValues`/`encodeDefaults`) but the production `configuredJson` also installs a `contentBlockModule` `serializersModule` (`MessageStateHandler.kt:816`/:837) that this local `Json` omits. That is fine for characterizing the `protocol` field's round-trip (it does not affect a `String?` scalar), but do NOT claim byte-identity of the full serialized message against the production serializer — only the `protocol`-field round-trip is pinned here.

- [ ] **Step 2: Run → FAILS to compile** (`protocol` param unknown) → FAIL.

- [ ] **Step 3: Add the field.** In `ApiMessage.kt:71`:

```kotlin
@Serializable
data class ApiMessage(
    val role: ApiRole,
    val content: List<ContentBlock>,
    val ts: Long = System.currentTimeMillis(),
    val modelInfo: ModelInfo? = null,
    val metrics: ApiRequestMetrics? = null,
    /**
     * Tool-calling paradigm that produced this turn. RESERVED (Phase 0b-1): null ⇒ treat as "xml"
     * (every existing/legacy session). No reader branches on it yet — Phase 4 adds the migration +
     * selects toChatMessage()'s rendering path by this value. Nullable + default-null keeps all
     * pre-existing on-disk files loadable unchanged.
     */
    val protocol: String? = null,
)
```

> Do NOT touch `toChatMessage()` (:96) — it must behave identically (no branch on `protocol`). Do NOT bump `SCHEMA_VERSION_CURRENT` (:780) — the additive nullable field needs no version bump (per the persistence map §7: `ignoreUnknownKeys` + nullable default = backward-compatible).

- [ ] **Step 4: Run → PASS** — `./gradlew :agent:test --tests "*ApiMessageProtocolDiscriminatorTest"` → PASS. Full `./gradlew :agent:test` → PASS (no persistence test regresses — existing files still load; writes gain an omittable/ignorable field).

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/session/ApiMessage.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/session/ApiMessageProtocolDiscriminatorTest.kt
git commit -m "feat(agent): reserve per-message protocol discriminator on ApiMessage (nullable; no migration)"
```

---

### Task 11: Contract test for the seam surface (`@InternalApi`, public, not internal)

Pure reflection test (no fixture, no konsist platform dependency needed — runs in `:core`) asserting the three seam interfaces are public and `@InternalApi`-annotated.

**Files:**
- Test: `core/src/test/.../ai/protocol/SeamApiStabilityTest.kt`

**Interfaces — Consumes:** `ToolProtocol`, `NativeProtocol`, `LlmProvider`, `InternalApi` (all `:core`).

- [ ] **Step 1: Write the test**

`core/src/test/kotlin/.../ai/protocol/SeamApiStabilityTest.kt`:

```kotlin
package com.workflow.orchestrator.core.ai.protocol

import com.workflow.orchestrator.core.ai.LlmProvider
import com.workflow.orchestrator.core.api.InternalApi
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class SeamApiStabilityTest {
    private val seam = listOf(ToolProtocol::class.java, NativeProtocol::class.java, LlmProvider::class.java)

    @Test fun `seam interfaces are public, not internal, and @InternalApi-annotated`() {
        for (c in seam) {
            assertTrue(Modifier.isPublic(c.modifiers), "${c.simpleName} must be public")
            // Kotlin `internal` mangles names / is not public at the JVM level for top-level decls;
            // a public interface satisfies the "B can implement it" requirement.
            assertTrue(c.isInterface, "${c.simpleName} must be an interface")
            assertTrue(
                c.isAnnotationPresent(InternalApi::class.java),
                "${c.simpleName} must carry @InternalApi (public-but-unfrozen)",
            )
        }
        // Sanity: a concrete impl is NOT required to be @InternalApi (it's a class, not the EP surface).
        assertFalse(XmlToolProtocol::class.java.isInterface)
    }
}
```

> **M2 VERIFIED:** `@InternalApi` is declared at `ApiStability.kt:24` with `@Retention(AnnotationRetention.BINARY)` (`ApiStability.kt:21`), so it IS reflectable at runtime via `isAnnotationPresent`. The reflection test is valid. VERIFY by running.

- [ ] **Step 2: Run → PASS** — `./gradlew :core:test --tests "*SeamApiStabilityTest"` → PASS. To prove it bites: temporarily remove `@InternalApi` from `ToolProtocol`, re-run → FAIL, revert. Record that you verified it fails.

- [ ] **Step 3: Commit**

```bash
git add core/src/test/kotlin/com/workflow/orchestrator/core/ai/protocol/SeamApiStabilityTest.kt
git commit -m "test(core): contract — seam interfaces are public + @InternalApi (B-implementable)"
```

---

### Task 12: Phase 0b-1 green gate

- [ ] **Step 1: Full module set + verifyPlugin (with `--rerun-tasks`; `--no-build-cache` because Tasks 7 & 9 changed suspend signatures)**

Run (the full documented module set per CLAUDE.md — `core/jira/bamboo/sonar/pullrequest/automation/handover/agent` + `konsist`; **`:automation:test` added in rev 2** — the seam does not touch it, but the "full set" claim must be honest):

`./gradlew :core:test :jira:test :bamboo:test :sonar:test :pullrequest:test :automation:test :handover:test :agent:test :konsist:test verifyPlugin --rerun-tasks --no-build-cache`

Expected: all PASS / BUILD SUCCESSFUL.

> The full set is required because the seam edits touch shared `:core` AI surface (`LlmBrain`, `MessageSanitizer`) consumed by `:agent`, and the `:core` ONE-`BasePlatformTestCase` invariant means a stray platform fixture would surface here as an "Indexing timeout" — confirm none was added.

- [ ] **Step 2: Behavior-preservation audit** — confirm every extraction task showed its characterization test GREEN against current code BEFORE the refactor and GREEN again after (Tasks 2, 3, 4, 5, 6, 8 are refactor-under-test / parity-pin; Tasks 1, 7, 9, 10, 11 are additive/new-surface with their own green proof). Note evidence per task.

- [ ] **Step 3: Confirm scope boundaries held** — no `AnthropicNativeProtocol` impl, no persisted-format migration logic, no Bedrock/Vertex, no proxy-aware native HTTP client landed (all Phase 4), AND `toolNameSet`/`paramNameSet` still on `LlmBrain` (NOT relocated — Phase 4). The `protocol` discriminator is reserved (null/"xml") with no reader branching. `AgentLoop.brain` is still typed `LlmBrain` (not widened to `LlmProvider`) — the loop consumes `brain` + `toolProtocol` separately, as designed.

- [ ] **Step 4: `:core` fixture invariant check** — grep the new `:core` tests for `BasePlatformTestCase`; expect ZERO. All seam tests are plain JUnit5.

```bash
git grep -l "BasePlatformTestCase" core/src/test/kotlin/com/workflow/orchestrator/core/ai/protocol/ || echo "OK: no platform fixtures in seam tests"
```

---

## Out of scope (this plan — deferred to Phase 4 unless noted)

- **Relocate `toolNameSet`/`paramNameSet` off `LlmBrain` onto `ToolProtocol`** *(rev-2 — this was rev-1 Task 5; DEFERRED here after 3 review rounds)* — the parser name sets stay on `LlmBrain` in 0b-1. Phase 4 must do this carefully because it is **transport-coupled** and **per-brain-instance-scoped**:
  - **Live decode READERS** (would break if the sets move without rerouting): `core/.../ai/SourcegraphChatClient.kt:365` and `:513` (`AssistantMessageParser.parse(rawText, knownToolNames, knownParamNames)`, fed from `OpenAiCompatBrain.toolNameSet`/`paramNameSet` at `core/.../ai/OpenAiCompatBrain.kt:64-65`/:81-82) and `agent/.../loop/BrainRouter.kt:244-246` (`buildRouterResponse` parse using its own forwarded `toolNameSet`/`paramNameSet`). The `AgentLoop:1074` parse is presentation/streaming-UI only — NOT the authoritative decode.
  - **WRITERS** (populate the sets per brain instance): `agent/.../brain/BrainFactory.kt:99-100`; `AgentService.kt:1980-1981` (primary orchestrator brain ctor); `SpawnAgentTool.kt:826-827`/:946-947 (per-spawn fresh brains via `brainProvider`); `SubagentRunner.kt:290-291`; and the `BrainRouter.kt:116`/:122 forwarding override.
  - **Why a single shared `ToolProtocol` instance is wrong for this:** parallel sub-agent fan-out gives each spawn its OWN tool scope (different `toolNameSet`); a shared protocol instance would race/clobber across concurrent sub-agents, losing per-sub-agent tool isolation. Phase 4 must give each brain/provider its own protocol instance (or keep the sets per-brain) and reroute ALL readers+writers together in one atomic change.
- **`AnthropicDirectProvider` / `AnthropicNativeProtocol` implementation** — only the `NativeProtocol` interface shape ships in 0b-1; the concrete native impl (structured `tool_use`/`tool_result`, `content_block_delta`/`input_json_delta` streaming, native `parseToolCalls`) is Phase 4.
- **Persisted-format MIGRATION logic** — the `protocol` discriminator FIELD is reserved (Task 10), but the migration that branches `toChatMessage()` rendering on it, and any read/rewrite of legacy sessions, is Phase 4. No reader branches on `protocol` in 0b-1.
- **Bedrock / Vertex transports** — SigV4 / OAuth2 + their SDKs' own HTTP clients are Tier-2, after the program (spec §6/§13).
- **Proxy-aware native HTTP client** — `IdeProxy`/`IdeTrust`-routed OkHttp for the native provider is Phase 4 (today's Sourcegraph clients intentionally bypass them; the native client must not).
- **Widening `AgentLoop.brain` from `LlmBrain` to `LlmProvider`** — 0b-1 keeps the loop consuming `brain` + `toolProtocol` separately to minimize regression risk; making the provider the single loop input is a Phase-4 consolidation.
- **Full `BrainRouter` dissolution** — 0b-1 WRAPS the router behind `XmlLlmProvider` (routing preserved); moving routing logic INTO a `SourcegraphProvider` and deleting `BrainRouter` is Phase 4.
- **`ContextManager` dedup / `snapToToolBoundary` protocol-conditioning** — the `[tool_name for 'path'] Result:` dedup regex is tool-output-format-dependent (NOT protocol-dependent) and needs no change; `snapToToolBoundary`'s XML-sanitizer dependency is harmless to leave under native and is conditioned in Phase 4 alongside the native sanitizer path.
- **`OpenAiCompatBrain` debug/session hooks** (`setApiDebugDir`/`setSharedApiCallCounter`/`detachSharedApiCallCounter`) becoming a provider-agnostic surface — left as-is in 0b-1 (still `OpenAiCompatBrain`-specific); generalized in Phase 4 if the native provider needs them.

---

## Self-review (writing-plans) — rev 2

- **Spec-coverage check (every §6/§8.1 seam concern has a task):** tool presentation → Tasks 1/2/3; response segmentation (parse + UI-splitter helpers, **GAP1**) → Tasks 1/2/4; result-formatting + history wire convention → Tasks 1/2/6 (on-disk shape confirmed unchanged; compaction no-regression asserted); drift-guard bypass → Task 5 (all 6 sites enumerated in Global Constraints, gated at the `consumeDialectDriftFlag` CHOKEPOINT + resume redaction — **WA-1** — with real characterization tests — **GAP4**); model-catalog/capability/context-window → Task 7 (interface) + Task 9 (adapter delegating to `ModelCatalogService`); provider error classification (`classifyStreamLine` + `classifyHttpError` **GAP2**) → Tasks 7/8/9; `getStatus`/`buildFallbackChain` (**GAP3**) → Tasks 7/9; absorb `BrainRouter` → Task 9; `NativeProtocol` shape → Task 5 (+ GAP1 helper defaults); reserve `protocol` discriminator → Task 10; `@InternalApi` public contract → Task 11; green gate (incl. `:automation:test`) → Task 12. **`toolNameSet`/`paramNameSet` relocation → DEFERRED to Phase 4** (Out of scope — transport-coupled). ✔ all 0b-1 concerns covered.
- **Placeholder scan:** no "TBD"/"similar to Task N"/"...". Every code block is concrete; every "VERIFY"/"VERIFIED" is an explicit signature confirmation (most now resolved against real source on `feature/plugin-split`). The GAP4 chokepoint test's drift-seeding call is pseudocode-anchored to the EXISTING `MessageStateHandler` fixture (reuse the current drift-seeding helper) — flagged, not a silent placeholder. ✔
- **Type-consistency check:** `ToolProtocol.presentTools` returns `String?` everywhere (Task 1 interface; Task 2 XML returns non-null `String`, a legal covariant override assignable to `String?`; Task 3 sites consume `String?` matching `toolDefinitionsMarkdown: String?`; Task 5 `NativeProtocol` returns null). `parseToolCalls` returns `List<AssistantMessageContent>` consistently (Tasks 1/2/4/5). `stripPartialTag(CharSequence): String` + `endsWithIncompleteTag(CharSequence): Boolean` match `AssistantMessageParser` exactly (Tasks 1/2/4/5). `classifyStreamLine` returns `String?` (Tasks 1/2/7/8/9); `classifyHttpError(Int, String): String?` (Tasks 7/9). `getStatus(String): String?` + `buildFallbackChain(): List<String>` (parameterless — matches `ModelCache.buildFallbackChain(getCached())` reality) (Tasks 7/9). `XmlToolProtocol.TOOL_RESULT_WIRE_PREFIX` is the single `"TOOL RESULT:\n"` source (Tasks 2/6). `ApiResult.Error(ErrorType.SERVER_ERROR, …)` (NOT `ApiResult.error`) in all stubs (Tasks 7/9). `ParameterProperty(type=…, description=…)` — `type` non-default (Task 2). `LlmProvider : LlmBrain`, `XmlLlmProvider : LlmProvider` (Tasks 7/9). `ApiMessage.protocol: String? = null` (Task 10). ✔ consistent — all verified against real signatures.
- **`:core` fixture invariant:** every NEW `:core` test (Tasks 2, 7, 8, 11) is plain JUnit5; the GAP4 drift tests (Task 5) are `:agent` tests that REUSE the existing `MessageStateHandler` fixture (no NEW `:core` `BasePlatformTestCase` added). Task 12 Step 4 greps to confirm zero in the seam test dir. ✔
- **Suspend-signature flags:** Tasks 7 and 9 flagged inline + in Global Constraints + the green gate runs `--no-build-cache --rerun-tasks`. ✔
- **Bytecode/blocker verification (rev 2):** B1 (`ApiResult.Error`/`ErrorType` — `ApiResult.kt:34-48`), B2 (defaulted `toolProtocol` APPENDED among `AgentLoop`'s defaulted params; non-defaulted block = `brain`+4 through `project`, `AgentLoop.kt:125-130`), B3 (BOTH `AgentLoop(...)` sites — `AgentService.kt:2424` + `SubagentRunner.kt:331`), I1 (`ParameterProperty.type` required — `ToolCallModels.kt:27-32`), I2 (`presentTools` non-null override of `String?` is legal). ✔ all checked against source.
