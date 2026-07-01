---
name: Update docs immediately with architecture changes
description: Whenever architecture changes are made, update docs in the same commit — never defer
type: feedback
---

Whenever there is a change in the architecture (modules, events, services, APIs, UI structure, threading, tools), update the documentation immediately in the same commit.

**Why:** User found docs were out of date after multiple features were built without updating. Documentation drift makes the codebase misleading for future work.

**How to apply:** After writing code that affects architecture, update these before committing:
- `agent/CLAUDE.md` (module-level)
- `docs/architecture/*.md` (relevant markdown files)
- `docs/architecture/index.html` and `interactive.html` (if diagrams changed)
- Root `CLAUDE.md` (if module list, build commands, or UX constraints changed)
