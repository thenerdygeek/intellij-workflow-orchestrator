---
name: SonarDataService.getCurrentBranch editor-fallback (TODO)
description: SonarDataService.getCurrentBranch falls back to editor/primary repo; should derive from caller's repo context
type: project
originSessionId: 37ba2642-e416-4e4e-b609-2145e6caa389
---
`sonar/service/SonarDataService.kt:getCurrentBranch()` resolves the SonarQube branch via `RepoContextResolver.resolveCurrentEditorRepoOrPrimary()?.currentBranchName`. Same anti-pattern fixed in the 2026-04-27 sweep at 11 other sites.

**Why:** the data service already subscribes to `PrSelected`, `BranchChanged`, `BuildFinished` and updates state from those events. `getCurrentBranch` is the *bootstrap / fallback* for when no event has set a branch yet. Fixing it cleanly means threading a `repoConfig` argument through every caller — out of scope for the resolver sweep.

**How to apply:** when picking this up:
1. Audit all callers of `SonarDataService.getCurrentBranch` (and `refresh*` paths that consume it).
2. Each caller should already know which repo it's operating on (the `QualityDashboardPanel` follows `WorkflowContextService.activeRepo`; the agent's Sonar tool takes a repo argument).
3. Pass `RepoConfig` or `GitRepository` through and look up `currentBranchName` from that — eliminate the editor-fallback resolver call.
4. Marker `// editor-fallback-allowed:` in `SonarDataService.kt` is a placeholder so `MultiRepoScopeInvariantTest` passes; remove it when this is fixed.

Discovered while extending `MultiRepoScopeInvariantTest` (2026-04-27, item 11 of the resolver sweep).
