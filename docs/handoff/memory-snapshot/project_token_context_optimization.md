---
name: project-token-context-optimization
description: Active branch perf/token-context-optimization — system-prompt + env_details token cuts; rank 4 + skills work queued
metadata: 
  node_type: memory
  type: project
  originSessionId: 3b898abc-451a-406c-9c58-e42e1be12f54
---

Branch `perf/token-context-optimization` (pushed to origin). Multi-phase token/context-window optimization driven by a judge-council workflow (see analysis at session start).

**Shipped (13 commits, full :agent suite green):**
- 9 system-prompt cuts (council ranks 1,2,3,5,6,7,8,9,10,11,12) — measured ~5.7–5.9K tok off the act-mode first message. Biggest: rank 2 (memory protocol → stub + enriched MemoryReviewGate nudge, ~3.35K). All snapshot-pinned (7 main + 7 sub-agent goldens).
- env_details: actively-running background processes section; time DECOUPLED from env_details (user msgs always stamp full datetime + retained, tool results minute-gated); env_details DEDUP (only latest block survives, stripped+persisted via onHistoryOverwrite).
- Crash fix: `GradleSonarKeyDetector` NoClassDefFoundError (LinkageError not caught) — guarded.

**Released:** `0.86.0-token-ctx.3` (per-tool §6c instrumentation). Earlier: .1, .2.

**KEY MEASURED FACTS (from [PromptSize]/[TokenWindow] on .2, an act-mode first message):**
- Input window is **132K**, NOT 93K. First message = **33,379 real tokens = 25% fill** — NOT starving the window. So this is now a COST/LATENCY lever, not a window-pressure fix. Thinking budget is output-side (separate axis).
- chars/4 ≈ real tokenizer (~3.97 ch/tok) — all our estimates validated.
- **§6c tool defs = 65,762 ch ≈ 16.4K tok = HALF the system prompt** (the giant).

**DONE (released 0.86.0-token-ctx.4, full :agent green):**
1. **Rank 4** (§6c trim) — `[PromptSizeByTool]` showed §6c Pareto-skewed: agent=7816ch render_artifact=4877 project_structure=4757 build=4534 run_command=4262 runtime_config=3706 (top6=45%). Trimmed the 5 safe ones (agent re-listed Rules §7 + persona descs; render_artifact re-listed SCOPE_HINT; run_command idle essay; search_code; build redundant suffixes). ~1.6–2K tok. LEFT project_structure/runtime_config (functional action schema → malformed-call risk) + new_task/attempt_completion (faithful Cline ports per user pref). Council's 3–7K was optimistic — half of §6c is functional schema, not trimmable prose. Commit `fb93b5208`.
2. **Rank 13 skills** — ⚠ my earlier `[PromptSizeByTool]`-pre measurement INFLATED (awk ran past `---` into body); REAL totals: all skill descriptions = 9,566 ch (~2.4K tok), meta-skill 4,533. Trimmed 8 verbose descriptions (8083→3161 ch) keeping ALL trigger keywords; meta-skill 4533→2260 (Red Flags 16-row table→1 sentence, removed Trigger table = dup of descriptions). ~1.8K tok. Frontmatter parser splits on FIRST colon only so "Triggers: …" in a value is safe. Commit `56f2b222f`. ⚠ UNVERIFIED: skill auto-triggering in-IDE (kept keywords, but real behavioral surface — user to spot-check).

**Also queued (smaller):** merge the two capabilities routing tables (~0.5–0.8K; rank 5 only deduped rows within them, never merged); compress delegation table+UX paragraph (~0.4K, only when delegationOutboundEnabled).

**Still parked:** 2b externally-modified-files in env_details (needs read-time tracking in ReadFileTool); caching transport (~40K/turn, blocked — Sourcegraph gateway strips cache_control).

**Rejected/not-worth (don't redo):** structural prompt reorganization (high risk/churn); removing "before-recommending-from-memory" (no equivalent RULES rule — keep); removing "parallel tool calls" tip (plugin-specific to XML dialect).
