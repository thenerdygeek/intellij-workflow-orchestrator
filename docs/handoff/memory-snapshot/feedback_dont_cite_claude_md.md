---
name: dont-cite-claude-md
description: User does not want CLAUDE.md referenced or cited as a source in responses
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 330592c7-3278-4236-ae70-4e097278b4d5
---

Do not refer to or cite CLAUDE.md when explaining things or justifying decisions.

**Why:** User said "please do not refer claude.md" on 2026-05-18 mid-task. They want answers grounded in the actual code or research, not project-doc citations. CLAUDE.md is a guide for me, not a reference to quote back at them.

**How to apply:** When explaining scope, architecture, or rationale, point to the source code (paths + line numbers) or external research. Do not write phrases like "per CLAUDE.md", "the project's CLAUDE.md says", or "as documented in CLAUDE.md". Internal use is fine — I can still read it; I just shouldn't surface it as a citation.
