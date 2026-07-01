---
name: focusPr stays stale when its repo is removed from settings (TODO)
description: WorkflowContextService.focusPr is not invalidated when user deletes the PR's repo from PluginSettings.repos
type: project
originSessionId: 37ba2642-e416-4e4e-b609-2145e6caa389
---
`WorkflowContextService` does not subscribe to `PluginSettings.repos` changes, so `focusPr` can outlive the settings entry that gave it meaning. PrBar then keeps rendering the stale `PR #N` strip and `BuildDashboardActionGate` keeps gating against a repo that's no longer configured. "Open in browser" silently no-ops because there's no `RepoConfig` to build the URL from.

**Why:** flagged as H2 in the 2026-04-27 sweep code review. Defensive issue — only fires when a user mid-session removes a repo they've already focused a PR for. Real but uncommon. Defended scope of the sweep PR by deferring.

**How to apply:** when picking this up:
1. In `WorkflowContextService.init`, subscribe to settings change (likely via `PluginSettings.addChangeListener` or message-bus topic — check `RepositoriesConfigurable.kt` for the existing change-broadcast mechanism, then call `RepoContextResolver.invalidateCache()` is already a hook).
2. On change: if `state.value.focusPr?.repoName` no longer maps to any `PluginSettings.getRepos().displayLabel`, mutate `focusPr = null` (under `cascadeMutex`).
3. Test: focus a PR, then delete the repo from settings, assert `focusPr` becomes null and `interactionMode` returns to Live.
