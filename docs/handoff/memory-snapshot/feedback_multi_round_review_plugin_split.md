---
name: multi-round-review-plugin-split
description: "User wants MULTIPLE independent rounds of review at EVERY step of the open-source plugin-split project, not a single pass."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 9cf2aa49-dc66-4dda-8ed1-afa1d058e59a
---

For the plugin-split project ([[project-plugin-split-open-source-backbone]]) the user directed: "as this is a very crucial thing we are working on, every step needs to have multiple round of review."

**Why:** The split (open-source backbone plugin A + private workflow plugin B) is high-stakes — public IP exposure, a stable public API that other companies will depend on, and open-sourcing that is hard to reverse. Mistakes are costly.

**How to apply:** At each step (design spec, each phase plan, each implementation) run MULTIPLE INDEPENDENT review rounds before declaring done or moving on — e.g. parallel reviewers with distinct lenses (architecture/feasibility, completeness/assumptions, security/IP, devil's-advocate), synthesize, fix, re-review if needed, THEN bring to the user. One self-review pass is not enough. Default to thoroughness over speed here. Never haiku for those reviewers ([[subagent-model-no-haiku]]).

**REAFFIRMED 2026-06-23** (user, after Phase-0a merged): "this split needs proper and thorough review" — keep this standing. Phase-0a execution honored it (per-task two-stage review [implementer self-review + independent task reviewer] + controller independent build/test verification + a final whole-branch opus review + full green gate). The green gate caught a real failure all per-task reviews missed (the :core multi-BasePlatformTestCase Indexing-timeout — an emergent aggregate property) — concrete proof that the layered/independent rounds matter; DO NOT shortcut them. The same rigor applies to the Phase-0b plan (multi-lens plan review like the spec/Phase-0a plan got: plan-accuracy bytecode-verified + plan-completeness + skeptic) and every Phase-0b implementation task.
