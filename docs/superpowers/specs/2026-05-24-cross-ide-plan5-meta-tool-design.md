# Plan 5 — Cross-IDE Delegation Meta-Tool Consolidation

**Status:** Design pinned · ready for implementation
**Branch:** `feature/cross-ide-delegation` @ `123c29a11` (post-`.3` smoke release)
**Worktree:** `.worktrees/cross-ide/`
**Supersedes:** the per-tool registration shape introduced in Plans 1–4

## 1. Why

The 5 separate `delegation_*` tools introduced across Plans 1–4 break the codebase's dominant pattern. Every other multi-operation domain in the plugin ships as a single meta-tool with an `action` enum: `jira` (17 actions), `sonar` (18), `bitbucket_pr` (19), `runtime_exec` (5), `debug_step` (10), etc. The current shape costs schema tokens on every LLM call where outbound delegation is active, and makes `tool_search` discovery emit 5 sibling hits instead of one.

## 2. Goal

Consolidate the 5 tools into one `delegation` meta-tool, mirroring `RuntimeExecTool`'s shape. Behavior preserved exactly; this is a refactor, not a feature change.

## 3. Non-goals

- No behavior change. Every error code, every dialog, every nudge stays identical.
- No protocol / wire change. `DelegationProtocol.kt`, `DelegationOutboundService`, `DelegationInboundService` are untouched.
- No new UI. The picker, Accept dialog, answer-confirm dialog stay as-is.
- No new tests for behaviors already covered. Existing tests get re-pointed at the meta-tool; no spec drift.

## 4. New tool shape

### Class

`agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/delegation/DelegationTool.kt`

```kotlin
class DelegationTool(
    // Test-override hooks lifted from DelegationListTargetsTool — meta-tool-level so
    // every test for the list_targets action constructs with these fakes.
    private val recentsProvider: suspend (Project) -> List<RecentEntry> =
        DelegationListTargetsTool::defaultRecentsProvider,
    private val discoveredProvider: suspend (Project) -> List<RecentEntry> =
        DelegationListTargetsTool::defaultDiscoveredProvider,
) : AgentTool {
    override val name = "delegation"
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)
    // tool description: one block describing all 5 actions, mirrors RuntimeExecTool.kt:84 shape
    override val description = """ ... """
    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf("send", "close", "answer", "fetch_transcript", "list_targets"),
            ),
            "request" to ParameterProperty(type = "string",
                description = "Full briefing for the remote agent — required for action=send"),
            "suggested_repo" to ParameterProperty(type = "string",
                description = "Optional repo-name hint for the picker — used by action=send only"),
            "handle" to ParameterProperty(type = "string",
                description = "Channel handle — required for close/answer/fetch_transcript; optional for send (continuation)"),
            "question_id" to ParameterProperty(type = "string",
                description = "Question id from a Question nudge — required for action=answer"),
            "answer" to ParameterProperty(type = "string",
                description = "Reply text — required for action=answer"),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        // single settings gate at the top
        if (!PluginSettings.getInstance(project).state.enableOutboundCrossIdeDelegation) {
            return ToolResult.error("DelegationOutboundDisabled: …")
        }
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.error("delegation: 'action' is required")
        return when (action) {
            "send"             -> handleSend(params, project)
            "close"            -> handleClose(params, project)
            "answer"           -> handleAnswer(params, project)
            "fetch_transcript" -> handleFetchTranscript(params, project)
            "list_targets"     -> handleListTargets(params, project)
            else -> ToolResult.error("delegation: unknown action '$action' — must be one of send|close|answer|fetch_transcript|list_targets")
        }
    }

    // ── per-action handlers (private suspend funs) ─────────────────────────
    private suspend fun handleSend(params: JsonObject, project: Project): ToolResult { /* lift body verbatim */ }
    private suspend fun handleClose(params: JsonObject, project: Project): ToolResult { /* lift body verbatim */ }
    private suspend fun handleAnswer(params: JsonObject, project: Project): ToolResult { /* lift body verbatim */ }
    private suspend fun handleFetchTranscript(params: JsonObject, project: Project): ToolResult { /* lift body verbatim */ }
    private suspend fun handleListTargets(params: JsonObject, project: Project): ToolResult { /* lift body verbatim */ }

    // Public test entry point preserved from DelegationFetchTranscriptTool.executeRaw
    suspend fun executeFetchTranscriptRaw(project: Project, handleId: String): ToolResult { /* same body */ }
}
```

### Important preserves

- The `DelegationListTargetsTool.RecentEntry` data class moves to the new file (or a top-level `DelegationTargets.kt` if cleaner — implementer's call).
- `DelegationListTargetsTool.defaultRecentsProvider` + `defaultDiscoveredProvider` companion functions move alongside.
- `DelegationFetchTranscriptTool.executeRaw` becomes `DelegationTool.executeFetchTranscriptRaw(project, handleId)` — same signature, same body, used by `DelegationFetchTranscriptToolTest`.
- `DelegationSendTool.buildNudgeText` (private fun) moves verbatim — it's only called from `handleSend`.

### Argument validation

Each handler validates the args it needs, returning `ToolResult.error("delegation: '<arg>' is required for action=<action>")` on missing values. The settings gate runs ONCE at the top of `execute()` — no need to re-check inside each handler.

## 5. Files to modify

| File | Change |
|---|---|
| `agent/.../tools/delegation/DelegationTool.kt` | **NEW** — single meta-tool, ~450 lines |
| `agent/.../tools/delegation/DelegationSendTool.kt` | **DELETE** |
| `agent/.../tools/delegation/DelegationCloseTool.kt` | **DELETE** |
| `agent/.../tools/delegation/DelegationAnswerTool.kt` | **DELETE** |
| `agent/.../tools/delegation/DelegationFetchTranscriptTool.kt` | **DELETE** |
| `agent/.../tools/delegation/DelegationListTargetsTool.kt` | **DELETE** |
| `agent/AgentService.kt:1301-1336` | Replace 5 register/unregister blocks with 1 `DelegationTool` block |
| `agent/.../prompt/SystemPrompt.kt:553-557` | Update 3 task-to-tool rows + UX note to reference `delegation(action=…)` form |
| `agent/.../resources/skills/cross-ide-delegation/SKILL.md` | Replace every `delegation_*` reference with `delegation(action="*")` form |

## 6. Test strategy

**Decision: keep existing test file names, update internals.** Lower churn, preserves git history per test, future bisect-friendly.

All 7 files under `agent/src/test/.../tools/delegation/` get the same shape change: `DelegationSendTool()` → `DelegationTool()`, and parameters JSON gains `"action": "send"` (etc.) field. Where tests inject providers (currently `DelegationListTargetsTool(recentsProvider = …)`), the new constructor is `DelegationTool(recentsProvider = …)`.

Files to update:
- `DelegationSendToolTest.kt` — adds `"action": "send"` param to JSON; verifies behavior unchanged
- `DelegationSendToolContinueWithTest.kt` — same, `"action": "send"` + `"handle": "x"` for continuation
- `DelegationCloseToolTest.kt` — adds `"action": "close"`
- `DelegationAnswerToolTest.kt` — adds `"action": "answer"`
- `DelegationAnswerToolAutoApproveTest.kt` — adds `"action": "answer"`
- `DelegationFetchTranscriptToolTest.kt` — adds `"action": "fetch_transcript"`; calls to `executeRaw` become `executeFetchTranscriptRaw`
- `DelegationListTargetsToolTest.kt` — adds `"action": "list_targets"`; constructor now `DelegationTool(recentsProvider = …, discoveredProvider = …)`

**`SystemPromptDelegationGateTest`** (`agent/src/test/.../prompt/`): assertions on the new `delegation(action=…)` strings. The gate-on / gate-off contract (8 assertions) stays — only the literal strings being asserted change.

**Cross-cutting test:** `AgentServiceCrossIdeDelegationGateTest` — if it pins specific tool names registered/unregistered, update to expect single `"delegation"` registration (vs 5).

## 7. Prompt snapshot regeneration

7 orchestrator snapshots + 5 sub-agent snapshots may shift because section 5 hints change literal tool names. Procedure (from `:agent/CLAUDE.md`):

```bash
./gradlew :agent:test --tests "*SNAPSHOT*"              # expect failures on changed variants
./gradlew :agent:test --tests "*generate all golden snapshots*"
./gradlew :agent:test --tests "*generate all golden*"   # subagent variant
./gradlew :agent:test --tests "*SNAPSHOT*"              # all green
```

Subagent variants: sub-agents pass `delegationOutboundEnabled = false` unconditionally, so the section 5 hints are absent in sub-agent snapshots — those should NOT change. Orchestrator snapshots change only in the rows showing delegation hints.

## 8. Verification

```bash
./gradlew :agent:test --rerun --no-build-cache
./gradlew verifyPlugin
```

Build-cache trap (see `:agent` CLAUDE.md → Rebase): if `suspend` lambda signature changes, must use `--no-build-cache`. The action-dispatch refactor should NOT change suspend signatures, so the trap shouldn't hit — but rerun-with-no-cache is cheap insurance.

## 9. Commit

Single commit on `feature/cross-ide-delegation`:

```
refactor(agent): consolidate cross-IDE delegation into a single meta-tool

The 5 delegation_* tools introduced across Plans 1–4 are now a single
`delegation` tool with action=send|close|answer|fetch_transcript|list_targets,
mirroring runtime_exec / jira / sonar / debug_step. No behavior change.

Schema cost: 5 entries → 1 entry. tool_search returns one hit covering
all five operations. SystemPrompt section-5 hints + the bundled
cross-ide-delegation skill updated to reference the new shape.
```

No `Co-Authored-By` trailer per project convention.

## 10. What this does NOT change

- The two-toggle settings model. Outbound + inbound stay independent.
- The Configurable wiring fixed in `.3`.
- `DelegationOutboundService` / `DelegationInboundService` / `DelegationServer` / wire protocol.
- Picker UX, Accept dialog, answer-confirm dialog, idle timeout, cascade-cancel, CHANNEL_RESUME, history badge, input banner.
- Plan 4's `continue_with` semantics — same path, just gated by `action=send` + `handle` present.
