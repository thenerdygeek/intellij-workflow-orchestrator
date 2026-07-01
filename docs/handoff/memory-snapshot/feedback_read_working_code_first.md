---
name: Read working code before fixing broken code
description: When a feature works in one tab but not another, read the WORKING code path first and replicate it exactly — don't guess or skim
type: feedback
originSessionId: 0ca84a78-6a66-41a0-956a-d08ae6ef2e4a
---
When the user says "X works in tab A but not tab B", IMMEDIATELY read tab A's full code path before touching tab B. Trace the exact mechanism — don't skim, don't assume, don't guess.

**Why:** During automation tab fixes, the Build tab already had working branch resolution (resolves branch plan key via Bitbucket build status, then fetches by key — no branch name in URL). But instead of reading that code first, I guessed at fixes, went back and forth 5+ times, and wasted the user's time. The answer was in the working code the entire time.

**How to apply:**
1. When told "this works elsewhere", read that code FIRST — trace the full call chain
2. Understand WHY it works (what mechanism, what API calls, what data flow)
3. Then replicate that exact approach — don't invent a different one
4. If the same underlying function is broken, fix it to match the working approach
5. Never revert a correct fix just because it didn't work on first try — check if the inputs were wrong instead
