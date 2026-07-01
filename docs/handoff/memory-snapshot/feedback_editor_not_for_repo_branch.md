---
name: Editor file must not drive repo/branch resolution
description: activeRepo/activeBranch and any branch-vs-PR check must come from the IDE's checked-out git state on the PR's repo, never from the currently selected editor file
type: feedback
originSessionId: f0be2b06-ee23-4c0f-9d51-888ade86b67a
---
The currently selected editor file must NEVER be used to determine the "active repo" or "active branch" for workflow logic — especially the PR ReadOnly/Live mode check.

**Why:** The user reported the same bug class repeatedly. Concrete symptom: they had `branch_1` checked out on the PR's repo (visible in IntelliJ's top branch widget) and the PR was for `branch_1`, but the ReadOnly banner still appeared because they had a random `.txt` file open. The editor-derived `activeRepo` didn't match the PR's repo, so `interactionMode` flipped to ReadOnly even though VCS state was perfectly aligned. Multi-module checkouts make this even worse — opening any file in submodule B while a PR is focused for submodule A wrongly triggers ReadOnly. The user's exact words: "what if I have a random txt file open even though the ide has that branch checked out still it will show that you are on (branch_1) the PR is of branch_1. This happened to me and users will get confused."

**How to apply:**
- For PR/branch comparisons (`InteractionMode`, `ReadOnlyBanner`, `LiveOnlyEnablement`, similar), look up the PR's `GitRepository` by `pr.repoName` / `pr.localVcsRootPath` and read its `currentBranchName`. Compare to `pr.fromBranch`. Do NOT involve the editor.
- `editorModule` / "what file is open" is ONLY for telling the agent which file the user is looking at. Never let it influence repo or branch resolution for any other feature.
- The `InteractionModePurityTest` invariant (interactionMode must be a pure function of declared `WorkflowContext` fields) is what enabled this bug — it forced the check to use editor-cached state. If purity blocks the correct VCS-driven check, drop the purity test and re-emit the flow on `BranchChanged` events.
- Any new code that reads `FileEditorManager.selectedEditor` to derive a repo or a branch is a regression — flag it on sight.
