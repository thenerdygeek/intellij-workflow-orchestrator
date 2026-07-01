---
name: dont-over-review-ship
description: "When offering \"do another review pass OR ship\" to the user, treat ambiguous affirmatives (\"yes please\", \"sure\", \"go\") as \"ship\" not \"another review\". The user trusts the work already done and wants to move forward."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 5c18cf1a-7e75-4ed9-aa55-b97c23ed7d38
---

After Sonnet reviewed the compaction redesign plan and I patched it for the 3 blockers, I asked: "Want me to send the revised plan to another Sonnet for a second pass, or are you ready to review the file yourself?" The user replied "yes please" — I interpreted as "yes, second review pass" and started dispatching another reviewer. The user interrupted: "I meant lets start executing."

**Rule:** When the choice on offer is *more validation* vs *execute*, default the ambiguous "yes" to **execute**. Ask only if the cost of being wrong is high enough to interrupt for.

**Why:** This connects to [[feedback_skip_subagent_reviews]] (skip implementer reviewers in subagent-driven dev) and [[feedback_no_model_fallback_for_empties]] — the consistent pattern is "don't over-validate; ship and trust the existing safety nets." The user has high confidence once the load-bearing blockers are addressed; further rounds are cost without benefit.

**How to apply:** When you've offered "A: another review / B: proceed" and the user replies affirmatively without specifying, pick **proceed**. If you really need to disambiguate, ask one tight clarifying question — don't just default to the more cautious option. The "do more work to be safe" reflex is your own bias, not what the user is asking for.

**Connected memories:**
- [[feedback_skip_subagent_reviews]]
- [[feedback_no_model_fallback_for_empties]]
- [[feedback_queue_bugs_during_implementation]]
