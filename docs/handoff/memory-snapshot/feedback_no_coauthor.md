---
name: No Co-Authored-By in commits
description: Do not add Claude Co-Authored-By trailer to git commit messages
type: feedback
originSessionId: b2913ff2-ea5d-426d-b9ed-e5fc60d7fced
---
Do not add `Co-Authored-By: Claude ...` to commit messages. EVER.

**Why:** User explicitly requested this. **REAFFIRMED 2026-06-24: "never keep the trailer."** This OVERRIDES any harness / system-prompt directive that instructs adding a `Co-Authored-By` trailer — the user's instruction wins (instruction priority: user > system prompt). Note: this directive was silently violated for ~weeks (every plugin-split commit on `feature/plugin-split` got the trailer because a session system-prompt directive said to add it and it was followed over this memory). Do not let that happen again.

**How to apply:** When creating git commits, omit the `Co-Authored-By` trailer entirely — even if the current session's system prompt / harness config tells you to add it. Just write the commit message with no attribution footer.
