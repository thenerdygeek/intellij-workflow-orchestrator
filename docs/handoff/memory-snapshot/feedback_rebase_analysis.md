---
name: Analyze main before rebasing
description: Before rebase, always analyze new commits on main for new tools/services/APIs that need agent integration
type: feedback
---

Before rebasing with main, always analyze what changed:
1. Check new commits on main (`git log --oneline --name-only HEAD..origin/main`)
2. Look for new service methods, tools, events, APIs added on main
3. Check if `:core` service interfaces changed (affects agent integration tools)
4. Only then rebase
5. After rebase: verify full test suite + verifyPlugin
6. If new capabilities were added on main, plan corresponding agent tools

**Why:** New tools/services on main might need corresponding agent integration tools. Rebasing blindly can miss opportunities to wire new capabilities into the agent.

**How to apply:** Every time user says "rebase", do the analysis first. Report what's new before executing the rebase.
