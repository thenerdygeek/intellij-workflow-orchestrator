---
name: brainrouter-stream-downgrade-dialect-trap
description: "Routing Claude through `/.api/completions/stream` for text-only turns causes tool-call XML to leak as assistant text because Claude emits Anthropic / Hermes dialects there, not the registered `<tool_name>` format the parser understands. Reverted as v0.85.16-alpha; do not re-add the downgrade without parser dialect support."
metadata: 
  node_type: memory
  type: project
  originSessionId: 7696d26e-27c0-4230-969b-63059cc24e76
---

The endpoint downgrade in BrainRouter is a one-way trap for text-only tool calls.

**Why:** Sourcegraph serves Claude two ways. `/.api/llm/chat/completions` is OpenAI-compatible — structured `tool_calls` come back via the API field, and when the model emits them as text it uses the registered `<tool_name>...</tool_name>` format the system prompt teaches. `/.api/completions/stream` is Anthropic's legacy completions API behind a thin Cody wrapper — under that API, Claude emits tool calls in its **pretraining dialects**:

- Anthropic: `<function_calls><invoke name="X"><parameter name="Y">…</parameter></invoke></function_calls>`
- Hermes / generic JSON-in-XML: `<tool_call>{"tool_name":"X","parameters":{...}}</tool_call>`

`AssistantMessageParser.parse()` only recognizes the registered format. The dialects fall through as `TextContent`, leak into the assistant text bubble, and **the tool never executes**.

**How to apply:**
- Never re-enable text-only downgrade to `/completions/stream` without first extending `AssistantMessageParser` to recognize at least the Anthropic `<invoke name="…">` dialect.
- Image-bearing turns already go through `/stream` by design (Phase 6 BrainRouter), but they don't engage the text-only counter and their tool-call risk is mitigated because image+tools rarely involves tool calls — still, the parser fix would harden this path too.
- The 2026-05-12 "empty response recovery" commit `8619094d9` introduced this downgrade at threshold 2; the followup `442da901e` tightened it to 1 (one bad response → next call goes to `/stream`). Both reverted in v0.85.16-alpha (commits `b579a5574` + `d2312dc94`).
- Sibling commit `7d2512215` ("strip leaked tool-use XML from chat") was a cosmetic band-aid for the *same* chain — it hid the leaked dialect XML from the chat but the tool still didn't run. Don't re-introduce the strip without the parser dialect support either.

**Related:**
- [[brainrouter-simplification-shipped]] — the earlier two-step image+tools removal on 2026-05-05.
- Reproduction tests pinned in `core/src/test/.../AssistantMessageParserReadDocumentReproTest.kt` and `SourcegraphChatClientToolResultPrefixTest.kt` (commit `4bc6364cd`).
- Diagnostic `[parser]` warn in `AgentLoop.kt` proved the parser HAD the tool names — disproving the registration-race hypothesis and pointing to the dialect-mismatch theory.

**Open follow-up:** Re-introduce the empty-response recovery + parser dialect support in a single change so the LiteLLM-pattern (200 OK + empty content + clean TCP-FIN) recovery comes back without re-breaking tool calls.
