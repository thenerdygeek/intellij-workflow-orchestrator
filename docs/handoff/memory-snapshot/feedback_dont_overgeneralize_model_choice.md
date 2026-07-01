---
name: dont-overgeneralize-model-choice
description: "When user gives a per-dispatch model preference (use opus / use sonnet), treat it as one-off — do NOT save it as a new permanent default that overrides the long-standing rules"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: e321b9f7-e6e9-4c31-9a61-e187cc1bd425
---

When the user says "use opus subagent please" or "next time use sonnet subagent", they mean **for the next dispatch** — not as a permanent override of the existing model-choice rules ([[sonnet-for-small-tasks]] + [[opus-max-effort-subagents]]).

**Why:** Twice in one session the user gave opposite instructions:
1. After a Sonnet implementer landed the conditional-registration multi-file change, user said "use opus subagent please" → I overcorrected and saved a "default to Opus for implementers" memory.
2. After an Opus implementer landed the 11 audit fixes (305K tokens, ~47 min, expensive), user said "next time use sonnet subagent" → which contradicted the memory I'd just saved.

**How to apply:**
- One-off model instructions: apply to the NEXT dispatch only, then revert to the canonical default.
- Canonical default ([[sonnet-for-small-tasks]] still holds): Sonnet for mechanical implementation, Opus for design/ambiguity/exploration/security-critical review.
- If a pattern emerges across multiple sessions (e.g. user consistently asks for Opus on security work), THEN it's worth memorizing as a context-specific rule, not a blanket override.
- Don't save a model-preference memory unless the user explicitly says "always do X from now on" — a single instruction is not "always".
