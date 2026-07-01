---
name: bisect-recent-commits-first
description: "When the user reports a regression with \"this was working before\" / \"recent change broke it\", run `git log --since=...` against the affected subsystem and revert-to-bisect FIRST, before deep architectural analysis."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 7696d26e-27c0-4230-969b-63059cc24e76
---

When the user qualifies a bug with "this was working before" or "recent changes caused this", treat that as a strong prior: bisect recent commits in the affected subsystem before any deep analysis.

**Why:** On 2026-05-12 a user reported tool-call XML leaking into the assistant text bubble. I spent significant turns analyzing the parser, the strip function, the FORMAT_INSTRUCTIONS template, three competing hypotheses about partial-tag handling, and even wrote unit tests pinning a *parser-missing-tool-name* failure mode that the live agent never actually hit. The actual cause was a 1-hour-old commit (`8619094d9`) that downgraded text-only requests to `/.api/completions/stream`, where Claude emits a different tool-call dialect. A `git log --since="today 10am"` filtered to BrainRouter / agent loop would have found it in 60 seconds.

**How to apply:**
- Step 1 when user says "regression" or "this was working": `git log --since="<window>" --pretty=format:"%h %ad %s" --date=format:"%H:%M" <affected paths>` to enumerate suspect commits.
- Step 2: revert the single most likely suspect commit on a throwaway branch (or test branch); build + ship + ask the user to verify. Repeat one commit at a time per the user's preference — they asked explicitly for one-at-a-time bisect on this issue.
- Step 3 only if bisect comes up empty: fall back to architectural analysis and unit-test reproductions.
- Deep-analysis-first wastes the user's time when the regression has a known timeframe. The unit tests for hypotheses that turn out wrong are not wasted — they pin behavior — but the *order* matters.
- The user also asked for "fresh build including dist UI" between bisect steps. That means: `cd agent/webview && npm run build` then `./gradlew clean buildPlugin` — the React dist gets baked into the JAR and a stale Vite output can mask the test. Always pair them.

**Related:**
- [[brainrouter-stream-downgrade-dialect-trap]] — the specific regression that motivated this rule.
- [[trust-user-infra-facts]] — same family: trust what the user just stated about the system.
