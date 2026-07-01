---
name: When user says "previous attempts failed," gather symptoms before touching code
description: User-frustrated + "previous fixes failed" is a signal to invoke systematic-debugging, ask for specific symptoms + logs + prior attempts, and investigate to root cause before any edits — NOT to show progress with speculative fixes
type: feedback
originSessionId: b23313f4-7864-4748-b4e8-c4fbecd260d6
---
When the user brings a bug with language like "there are multiple bugs, previous attempts failed, I have no idea why you cannot fix these issues" — the correct move is NOT to start editing code to show progress under pressure. It's to:

1. Invoke the `superpowers:systematic-debugging` skill before anything else
2. Do a quick branch-local investigation (git log, memory files, existing plans) so your follow-up question is informed
3. Ask ONE focused message back with: (a) what the concrete symptoms are, (b) how to reproduce, (c) logs / devtools output / session id, (d) which previous fix attempts were tried and rejected
4. Only after you can state a specific hypothesis tied to a specific file and line number do you touch code
5. Write regression tests that would have caught the bug, not just tests that mirror the implementation

**Why:** Validated on 2026-04-11 on `feature/streaming-xml-port` for two user-reported bugs (parallel tool cards overwriting each other + streaming text flash on finalize). Previous attempts in other sessions had failed. This session skipped speculative edits, gathered symptoms, traced Bug 1 to a scope-mismatch between a per-response XML tool id counter and a session-scoped JS store map, and Bug 2 to two different React components rendering the same content with different wrappers. Both fixes landed in one commit with regression tests, shipped as `v0.76.1-beta`, and the user confirmed "bug is not present anymore." User's explicit framing was "fix these bugs goddammit, but if you want logs then ask me" — which is the exact permission to ask before acting.

**How to apply:** Any time the user opens with multiple-bug frustration + references to prior failed fixes, treat it as a systematic-debugging situation, not a speed situation. Ask for symptoms + logs in ONE message (don't drip-feed), and include a short "here's what I already know from the branch" preamble so the user sees you did homework before asking. Do not start any code edit until you can name the file path and line of the root cause.
