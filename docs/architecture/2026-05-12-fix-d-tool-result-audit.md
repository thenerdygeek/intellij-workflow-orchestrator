# Fix D — Audit: text-after-tool-result paths in sanitizeMessages and ContextManager

**Date:** 2026-05-12
**Branch:** `fix/automation-handover-quality-tabs`
**Scope:** Validate whether the plugin's tool-result handling can trigger the Anthropic "empty `end_turn` after tool_result" pattern documented in the research report at `docs/research/2026-05-12-empty-response-deep-research.md` §2.1.

## Background

Anthropic's stop-reason docs warn that adding a `text` content block after a `tool_result` content block in the same user turn primes the model to emit `stop_reason=end_turn` with no content blocks on the next assistant turn. Empirically reproduced in `langchain-ai/langgraph #3168` and `agno-agi/agno #3137`.

The fix proposal asked: do we have any code path that appends text after a tool result, either as native blocks or in a way that could be semantically equivalent?

## Audit findings

### Path 1: `ContextManager.addToolResult` → `ContextManager.addUserMessage` / `addUserMessageWithParts`

When the agent runs a tool, the loop calls `addToolResult(toolCallId, content, isError)`. The result lands as a `role="tool"` message with the literal content. The very next message added is usually either:

- Another `addToolResult` (parallel tool dispatch) — same role, no text-after-result issue, BUT consecutive tool results get merged at the wire layer (see Path 2).
- An `addUserMessage(...)` (steering message, env details, or nudge) — `role="user"`, distinct from the tool result.
- The next `addAssistantMessage(...)` from the LLM's next turn.

The in-memory representation is faithful: a `tool` message followed by a `user` or `assistant` message. **No prose is appended inside the tool-result entry itself.** `addToolResult` only prepends `[ERROR] ` for failed tool calls; otherwise the body is the verbatim tool output.

### Path 2: `SourcegraphChatClient.sanitizeMessages` Phase 1 (tool→user conversion)

```kotlin
"tool" -> {
    val toolContent = "TOOL RESULT:\n${msg.content ?: ""}"
    converted.add(ChatMessage(role = "user", content = toolContent))
}
```

`role: tool` is rejected with HTTP 400 on Sourcegraph 6.12 (probe-confirmed `probe_tool_role: REJECTED`). The conversion to `role: user` with a `"TOOL RESULT:\n..."` prefix is **mandatory** for the gateway to accept the request.

### Path 3: `SourcegraphChatClient.sanitizeMessages` Phase 2 (consecutive-same-role merge)

```kotlin
if (last != null && last.role == msg.role && last.toolCalls == null && msg.toolCalls == null) {
    merged[merged.size - 1] = ChatMessage(role = msg.role, content = "${last.content ?: ""}\n\n${msg.content ?: ""}")
}
```

After Phase 1, a `[tool, user]` pair becomes `[user("TOOL RESULT: …"), user(actual_text)]`. Phase 2 merges them into a single user turn: `"TOOL RESULT: …\n\n<actual_text>"`. **This is technically "text after a tool result" by content shape**, but:

1. **It is NOT Anthropic native tool_result blocks.** The Sourcegraph facade `/.api/llm/chat/completions` does not accept Anthropic content-block structures (verified by `probe_anthropic_tool_result_blocks`: REJECTED with `"message content cannot be empty"` and `decoder.decode` errors). Anthropic's documented empty-`end_turn` pattern fires on the native block shape, not on plain text.

2. **The merge is required by gateway constraints.** Anthropic mandates strict user/assistant alternation; Sourcegraph's facade enforces the same. We cannot leave `[user, user]` adjacent.

3. **The completion-tool variant is already specifically mitigated** by `ContextManager.collapseLastCompletionToolPair`, which collapses `[assistant + completion_tool_call, tool_result]` into a single plain `assistant` turn before the next user message lands. KDoc at `ContextManager.kt:340-407` documents this and references the same risk.

### Path 4: `addUserMessage(EMPTY_RESPONSE_ERROR)` after empty response (Case C)

`AgentLoop` adds an empty assistant message (`""` content, no tool calls) to context, then adds the nudge as a user message. In `sanitizeMessages` Phase 3 (drop), the empty assistant gets removed before the wire send. In the next iteration, `pruneAllNudgePairs(EMPTY_RESPONSE_ERROR)` strips the prior `[assistant_empty, user_nudge]` pair from in-memory state. **No empty-assistant turns reach the gateway.**

## Conclusion

**No code change required.** The implementation is constrained by probe-validated Sourcegraph shape requirements:

- `role: tool` is rejected → conversion to `user` is mandatory (Path 2).
- Consecutive same-role messages are rejected → merging is mandatory (Path 3).
- Anthropic native content blocks are rejected → the documented empty-`end_turn` shape cannot exist on the wire (Path 3 detail 1).

The remaining risk — that the model SEMANTICALLY recognizes the literal `"TOOL RESULT:"` prefix and mimics the empty-`end_turn` priming pattern even on plain text — is unmeasurable without targeted probing. Two mitigations are already in place:

1. `collapseLastCompletionToolPair` handles the most-common variant (completion-tool pair followed by user follow-up).
2. The new `BrainRouter` endpoint downgrade (Fix C, this same PR) provides recovery by switching to `/.api/completions/stream` after 2 consecutive empties — a different gateway code path with potentially different priming behavior.

## Follow-up work (if the empty-response problem recurs after this PR ships)

- Add a `probe_tool_result_priming.py` probe that sends `[tool_result, user_text]` sequences in both shapes (native blocks rejected, plain-text accepted) and measures empty-response rates per N iterations.
- Result drives whether `sanitizeMessages` Phase 2 should special-case the tool-result variant (e.g. wrap the user text in a marker like `<user_followup>...</user_followup>` so the model parses it as a fresh user turn, not a continuation of the tool result).

## Cross-references

- Code: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt:203-330` (sanitizeMessages); `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt:340-454` (addToolResult, collapseLastCompletionToolPair, addUserMessage); `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt:1474-1500` (Case C empty handler).
- Probes: `tools/sourcegraph-probe/Result_1/streaming_lab_results.json` (`anthropic_tool_result_blocks`, `tool_role`); `tools/sourcegraph-probe/STREAMING_LAB_CONTEXT.md` (gateway constraints table).
- Research: `docs/research/2026-05-12-empty-response-deep-research.md` §2.1, §2.6.
