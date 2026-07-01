---
name: Sonar Coverage/Issue panels still use scalar fallback (TODO)
description: CoveragePreviewPanel + IssueDetailPanel call getLineCoverage(filePath) without projectKey or branch — both fall through to scalar settings + WorkflowContextService.activeBranch. Multi-repo gap.
type: project
originSessionId: 7a25496d-27f3-45b0-b67f-391311c8eec3
---
`CoveragePreviewPanel.kt:94` and `IssueDetailPanel.kt:266` call `SonarDataService.getLineCoverage(filePath)` with neither `projectKey` nor `branch`. Both fall through to the scalar `settings.state.sonarProjectKey` (wrong on multi-repo) and `WorkflowContextService.activeBranch` (laundered editor-fallback — same anti-pattern the 2026-04-27 sweep + commits c9fffd22 / ef2ecfee eliminated everywhere else).

**Why deferred:** the per-call-site fix touched 4 callers in commit ef2ecfee; these two are a unit because they need both `projectKey` AND `branch` plumbed from per-file repo context. The pattern is exactly what `CoverageLineMarkerProvider.kt` already does:
```kotlin
val repoConfig = resolver.resolveFromFile(file)
val projectKey = repoConfig?.sonarProjectKey?.takeIf { it.isNotBlank() } ?: scalarFallback
val branch = repoConfig?.localVcsRootPath?.let { resolver.findRepositoryForPath(it)?.currentBranchName }
service.getLineCoverage(relativePath, projectKey, branch)
```

**Why not caught by the audit:** `MultiRepoScopeInvariantTest` greps for `RepoContextResolver.resolveCurrentEditorRepoOrPrimary` calls. The editor-fallback that survives in `SonarDataService.getLineCoverage` is `WorkflowContextService.state.value.activeBranch` — same effect, different shape. The marker convention has a blind spot here. Either widen the audit regex to flag `WorkflowContextService.*activeBranch` reads outside the canonical service consumers, or accept that the marker only catches one form and these two sites need eyeball review.

**How to apply:**
1. `CoveragePreviewPanel:94`: panel has `filePath: String` (relative to project? aggregator? confirm). Resolve via `LocalFileSystem.findFileByPath` → `RepoContextResolver.resolveFromFile`. Thread `projectKey + branch` through.
2. `IssueDetailPanel:266`: panel has the issue's component string (`projectKey:relativePath`). The issue object likely already carries `projectKey` (look for it on `MappedIssue` or similar). Branch comes from the same per-file resolution.
3. Once both pass `projectKey` and `branch` explicitly, drop the `?: WorkflowContextService.activeBranch ?: "develop"` fallback in `SonarDataService.getLineCoverage` — make the param required.
4. Update `sonar/CLAUDE.md` "Architecture" to note that all `getLineCoverage` callers must pass per-file repo context.

Discovered while reviewing commit ef2ecfee (2026-04-28).
