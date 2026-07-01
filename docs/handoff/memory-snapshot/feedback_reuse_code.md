---
name: Reuse code, avoid duplication
description: User explicitly called out duplicate code doing similar things with separate functions — consolidate rather than add parallel paths
type: feedback
originSessionId: 0ca84a78-6a66-41a0-956a-d08ae6ef2e4a
---
Always reuse existing code rather than creating parallel paths. Fix the root function instead of working around it in the caller.

**Why:** The plugin already has "loads of duplicate code which does almost similar things." Adding more workarounds makes it worse. If `getLatestBuild()` has a bug with branch resolution, fix `getLatestBuild()`, don't switch callers to `getRecentBuilds()`.

**How to apply:** Before writing new code, check if the same capability exists elsewhere. If a function is broken, fix it rather than calling a different function. Consolidate settings resolution, branch detection, and other patterns into single reusable methods.
