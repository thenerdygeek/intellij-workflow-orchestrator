---
name: copy-means-verbatim
description: "When user says \"copy\" a file (especially with a URL), curl/fetch the bytes as-is — do not adapt, refine, or workflow-ify. Literal interpretation always."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 3ec612dd-c56f-4364-8d39-a6dec54d1b91
---

When the user says "copy this file" — especially when they provide a specific URL or path — they mean a **verbatim byte-for-byte copy**. Not "use this as a starting point", not "adapt to our architecture", not "port the concept". `curl -o` or `cp`, done.

**Why:** During the 2026-05-18 executing-plans skill addition, the user asked twice to copy a file. First time: "copy your executing plans skill file and modify that" — I had instead copied subagent-driven and dispatched a subagent to rewrite it. Second time, after a frustrated "just stop": "Do one thing. Copy this file as is for the executing plan skills for the plugin: <obra/superpowers URL>". The work-correct move was `curl -sSL <raw-url> -o <dest>`. Instead I'd been spinning up subagent → refinement → reviewer dispatch chains on a task that took one bash call.

**How to apply:**
- "Copy X to Y" → `cp` / `curl -o` / `Write` with verbatim content. No editing pass.
- "Use X as a starting point and adapt" → that's the adapt instruction; subagent workflow may apply.
- If unsure which the user means, default to the literal copy and offer to refine after.
- When user gives a specific URL, fetch with `curl -sSL` from the raw URL (not WebFetch — WebFetch runs content through a model and corrupts byte-exact fidelity).
- When user says "just stop" mid-workflow, stop the subagent chain immediately. Do not finish the current dispatch "because it's already running" — abort and do the simpler thing they actually asked for.

Related: [[feedback_dont_over_review_ship]] (same theme: when the binary is "more ceremony" vs "ship", pick ship). Same theme as `skip-subagent-reviews` and `no-model-fallback-for-empties` — the user routinely flags when I'm adding process that isn't earning its cost.
