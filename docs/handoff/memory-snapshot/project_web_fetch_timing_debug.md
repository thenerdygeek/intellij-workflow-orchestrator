---
name: project_web_fetch_timing_debug
description: RESOLVED 2026-06-04 — TEMP web_fetch per-stage timing debug was stripped (revert of f3ed43574) and merged into bugfix; no longer present
metadata: 
  node_type: memory
  type: project
  originSessionId: c56d52c5-0158-48fc-818b-c4ad1c51dffd
---

✅ **RESOLVED 2026-06-04 — no longer actionable.** The TEMP web_fetch per-stage timing debug has been **removed** (strip commit `a5891135c`, a revert of the code changes from `f3ed43574`, keeping pluginVersion) and the `worktree-web-fetch-search` branch was **fast-forward-merged into `bugfix` locally** (bugfix now at `a5891135c`, 58 ahead of origin/bugfix, NOT pushed). `grep -rn "web-fetch-timing" web/` returns nothing; `:web:test` green after the strip. Do NOT try to remove it again — it's gone.

---

**Historical record (what it was):** `web_fetch` appended a per-stage timing breakdown to the tool-result summary, e.g. `… | ⏱ stages: http=380ms, sanitize-llm=58210ms`, to localize which stage was slow on timeouts. Added 2026-06-03, shipped in **v0.86.0-web-rc10** (also rode in rc11/rc12 releases — those rc ZIPs still contain it; only the bugfix branch is clean). All lines carried the greppable marker `web-fetch-timing` in `WebFetchEngine.kt` (a `fetch()` wrapper + `StageTimer` nested class + `timer.begin(...)` calls) plus one `⏱ stages:` assertion in `WebFetchPipelineE2ETest`.

Related: [[project_web_tools_rebase_release]] (the web branch this lived on).
