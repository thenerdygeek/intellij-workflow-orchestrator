---
name: Sourcegraph Cody Enterprise LLM API constraints
description: Sourcegraph Cody Enterprise API constraints. 150K input, max_tokens NOT capped at 4000 (accepts 8K/16K/100K per probe tests), max_tokens MANDATORY for thinking models, tools supported, no tool_choice
type: reference
---

Cody is Enterprise-only. All users have enhanced-context-window (150K input tokens).

**max_tokens behavior (corrected 2026-04-08 by user — both 4000 AND 8000 caps are WRONG/outdated):**
- There is NO hard cap of 4000 OR 8000 on the live Sourcegraph LLM gateway — both numbers come from stale OpenAPI specs and should not be propagated
- Probe scripts at `tools/sourcegraph-probe/api_test.py:208` empirically test 8000, 16000, 100000 — all accepted
- Treat the published `openapi.Sourcegraph.Latest.yaml` 8000 cap as a documentation artifact, not a real constraint
- **max_tokens is MANDATORY for thinking models** — omitting it causes HTTP 500 (thinking budget allocation)
- For non-thinking models max_tokens may be optional, but always sending it is safest
- Use whatever budget the model context window supports; do not artificially cap to 4000 or 8000

**API endpoints/format:**
- Endpoint: `/.api/llm/chat/completions` (NOT `/chat/completions`)
- Auth: `Authorization: token TOKEN_VALUE` (NOT `Bearer`)
- Model format: `provider::apiVersion::modelId` (e.g., `anthropic::2024-10-22::claude-sonnet-4-20250514`)
- `tools` field: SUPPORTED (undocumented in spec but works)
- `tool_choice` field: NOT in spec — omit from requests
- `stream: true` on same endpoint: labeled "Unsupported" but response format documented — works in practice but agent currently uses non-streaming (see OpenAiCompatBrain.chatStream)
- Response `object` field is `"object"` not `"chat.completion"`
- `tool` role in request messages: NOT in spec, must be converted to user role (sanitizeMessages handles this)
- Cody Context API at `/.api/cody/context` — semantic code search across repos

**How to apply:**
- Always pass `maxTokens` explicitly when calling thinking models, otherwise HTTP 500
- Don't propagate the "4000 cap" claim — it's wrong
- Never send tool_choice in requests
- Use `/.api/llm/chat/completions` path
- Single agent mode is the default for all users
- Three places in current code (as of 2026-04-07) still claim "max_tokens capped at 4000" in KDoc — these should be fixed: SourcegraphChatClient.kt:39, OpenAiCompatBrain.kt:14, GenerateCommitMessageAction.kt:158
