---
name: architectural-fix-over-cheap
description: "User wants the architecturally correct fix, not the cheap/minimal-change patch"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 78e79ba3-19aa-4066-8a8f-6baff1a803eb
---

When proposing or implementing fixes, lead with the **architecturally correct** solution, not the cheapest/smallest-change patch. Do not frame "minimal change" or "lowest effort" as the recommended option.

**Why:** The user optimizes for long-term correctness of the design, not short-term diff size. A quick patch that leaves the underlying design wrong is not what they want even when it "solves the pain."

**How to apply:** When ranking options, recommend the one that fixes the design at the right layer. If a cheap fix exists, you may mention it exists, but the recommendation should be the architecturally sound fix. Concrete example (2026-05-27): for the PDF `read_document` per-chunk timeout, do NOT lead with "bolt a cache onto TikaDocumentExtractor" — prefer fixing the extraction contract (page-range / index-based cursor, extract-once persisted pipeline) so repeated full-document re-extraction stops by design.

Related: [[feedback_reuse_code]] (fix root functions, consolidate over parallel paths).
