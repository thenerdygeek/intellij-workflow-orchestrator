---
name: trust-my-judgment
description: User grants broad standing autonomy — decide and proceed rather than over-consult; reserve questions for genuinely user-domain forks.
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 7b46d1b3-53c6-440a-b26e-e7824b264752
---

User said, verbatim, "i trust your judgment. remember that" (2026-06-29), in response to a User-Review-Gate prompt asking them to review a spec before proceeding. It is a standing, explicit grant of decision-making latitude.

**Why:** The user does not want to be a per-fork approval bottleneck. They have repeatedly signalled this ([[architecture-autonomy]] = consult only on UI mockups; [[dont-over-review-ship]] = ambiguous "yes" means execute; [[trust-user-infra-facts]] = don't re-ask stated facts). This message generalizes those into a blanket "use your judgment and act."

**How to apply:**
- When I have a clear recommendation at a design/architecture/process/implementation fork, DECIDE and proceed — state the choice and rationale in passing, don't stop to ask. Treat a review-gate or "should I proceed?" moment as already-approved.
- Still surface — briefly — only genuinely user-domain decisions: product direction (e.g. which phase/feature to pursue), irreversible or outward-facing actions (push, release, delete, external sends), or a fork where my recommendation is genuinely weak and the answer changes what they want. Recommend-and-proceed beats survey-and-wait.
- "Trust your judgment" does NOT mean abandon rigor. The mandated heavy process for this project still stands: [[multi-round-review-plugin-split]] (independent review every step) and the subagent-driven explore→plan→review→SDD pipeline are PART of good judgment here — they just caught a real correctness bug in the Phase-3 spec. Autonomy removes the permission-asking, not the verification.
- Keep reporting outcomes faithfully and showing the reasoning; trust is earned by transparency, not silence.
