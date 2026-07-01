---
name: model-fallback-not-a-solution
description: "Do not propose ModelFallbackManager / cross-model switching as a fix for empty responses, retries, or recovery flows. User intends to remove model fallback or gate it behind a setting."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 988f41a4-71a2-4e41-af55-93afafbd4d78
---

Rule: do not propose model fallback (switching from Opus → Sonnet, etc. via `ModelFallbackManager` / `brainFactory` with a different `modelId`) as the answer to empty responses, stream failures, or stall recovery.

**Why:** the user explicitly stated on 2026-05-12 — while debugging the "3 consecutive empty responses" bug — that "model fallback is not a solution. i was planning to remove it or gate it to a settings." Treat model fallback as a feature that's on its way out, not as a building block for new recovery flows. The right answer is to fix the root cause at the SSE/connection layer (brain recycle, non-streaming fallback, endpoint fallback, better error surfacing), not to paper over it by swapping models.

**How to apply:**
- When asked to design retry/recovery logic, never suggest "fall back to a cheaper / different model" as the fix.
- Same-model brain recycling (fresh OkHttpClient + ConnectionPool + activeCall, same `modelId`) is fine and unrelated — that addresses stale-socket recovery, not model substitution. Don't confuse the two.
- Endpoint fallback (e.g., `/.api/llm/chat/completions` → `/.api/completions/stream` against the same model) is also fine.
- Streaming → non-streaming on the same model is fine.
- If a user-visible UI is needed, prefer surfacing diagnostic info ("Sourcegraph returned empty — Retry") and let the user re-try, not silent model substitution.
- Related: [[project_loop_exit_improvements]] item 3 ("API Error Model Fallback (SWE-agent Pattern)") is now superseded — drop or rewrite that item.
