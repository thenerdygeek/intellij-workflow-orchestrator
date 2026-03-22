# Multi-Repository Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform the plugin from single-repo to full multi-repo support. Users configure N Bitbucket repos, each with its own Bamboo plan, SonarQube project, and settings. The plugin auto-resolves which config to use based on the active file/VCS root.

**Architecture:** New `RepoConfig` data model replaces scalar settings. `RepoContextResolver` maps VCS roots to configs. Core service interfaces gain optional repo parameters with backward-compatible defaults. UI shows repo indicators on PRs, builds, and quality data. Migration preserves existing single-repo settings.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (GitRepositoryManager, PersistentStateComponent), Bamboo/Bitbucket/SonarQube REST APIs

---

## Phase Overview

```
Phase 1 (Model + Migration) — new RepoConfig, settings migration, resolver
  └── Phase 2 (Core wiring) — update service interfaces + implementations
        ├── Phase 3 (Event system) — add repo context to events
        └── Phase 4 (UI) — settings table, repo indicators in tabs
```

---

### Task 1: RepoConfig Data Model + Settings Migration

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/RepoConfig.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`

- [ ] **Step 1: Create RepoConfig data class**

```kotlin
package com.workflow.orchestrator.core.settings

import com.intellij.openapi.components.BaseState

/**
 * IMPORTANT: Must extend BaseState for IntelliJ's SimplePersistentStateComponent
 * serialization to work. Uses `by string()` / `by property()` delegates for
 * change tracking. A bare data class with @Tag will NOT persist.
 */
class RepoConfig : BaseState() {
    var name by string("")                         // display name, e.g., "backend"
    var bitbucketProjectKey by string("")
    var bitbucketRepoSlug by string("")
    var bambooPlanKey by string("")
    var sonarProjectKey by string("")
    var dockerTagKey by string("")                 // service name in automation tag matrix
    var defaultTargetBranch by string("develop")
    var localVcsRootPath by string("")             // path to match with GitRepository
    var isPrimary by property(false)               // the "default" repo when context is ambiguous

    val isConfigured: Boolean
        get() = !bitbucketProjectKey.isNullOrBlank() && !bitbucketRepoSlug.isNullOrBlank()

    val displayLabel: String
        get() = (name ?: "").ifBlank { "${bitbucketProjectKey ?: ""}/${bitbucketRepoSlug ?: ""}" }
}
```

- [ ] **Step 2: Add repos list to PluginSettings.State**

```kotlin
// In PluginSettings.State (which extends BaseState), add:
var repos by list<RepoConfig>()  // BaseState's list() delegate handles serialization
```

Keep the existing scalar fields (`bitbucketProjectKey`, `bitbucketRepoSlug`, `bambooPlanKey`, `sonarProjectKey`, etc.) for backward compatibility during migration.

- [ ] **Step 3: Add migration logic**

In `PluginSettings`, add a migration method called on `loadState()`:

```kotlin
private fun migrateToMultiRepo(state: State) {
    if (state.repos.isNotEmpty()) return // already migrated

    // If old scalar fields have values, create a RepoConfig from them
    if (state.bitbucketProjectKey.isNotBlank()) {
        state.repos.add(RepoConfig(
            name = state.bitbucketRepoSlug,
            bitbucketProjectKey = state.bitbucketProjectKey,
            bitbucketRepoSlug = state.bitbucketRepoSlug,
            bambooPlanKey = state.bambooPlanKey,
            sonarProjectKey = state.sonarProjectKey,
            dockerTagKey = state.dockerTagKey,
            defaultTargetBranch = state.defaultTargetBranch,
            isPrimary = true
            // NOTE: localVcsRootPath is blank after migration. The auto-detect
            // function in Settings UI (Task 6) should be prompted to fill it in,
            // or RepoContextResolver falls through to remote URL parsing.
        ))
    }
}
```

- [ ] **Step 4: Add convenience accessors**

```kotlin
// In PluginSettings, add helper methods:
fun getRepos(): List<RepoConfig> = state.repos.toList()

fun getPrimaryRepo(): RepoConfig? = state.repos.find { it.isPrimary } ?: state.repos.firstOrNull()

fun getRepoForPath(vcsRootPath: String): RepoConfig? =
    state.repos.find { it.localVcsRootPath == vcsRootPath }
```

- [ ] **Step 5: Build and commit**

Run: `./gradlew :core:compileKotlin`

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/
git commit -m "feat(core): RepoConfig data model with migration from single-repo settings"
```

---

### Task 2: RepoContextResolver

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/RepoContextResolver.kt`

- [ ] **Step 1: Create RepoContextResolver**

A utility that resolves which `RepoConfig` to use based on context:

```kotlin
package com.workflow.orchestrator.core.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

class RepoContextResolver(private val project: Project) {

    /**
     * Resolve RepoConfig from a VirtualFile (e.g., the file currently being edited).
     * Finds the VCS root containing this file, then matches to a configured repo.
     */
    fun resolveFromFile(file: VirtualFile): RepoConfig? {
        val gitRepo = GitRepositoryManager.getInstance(project).getRepositoryForFile(file)
            ?: return getPrimary()
        return resolveFromGitRepo(gitRepo)
    }

    /**
     * Resolve RepoConfig from a GitRepository (VCS root).
     */
    fun resolveFromGitRepo(gitRepo: GitRepository): RepoConfig? {
        val rootPath = gitRepo.root.path
        val settings = PluginSettings.getInstance(project)

        // First try: match by configured localVcsRootPath
        settings.getRepoForPath(rootPath)?.let { return it }

        // Second try: match by parsing git remote URL to extract project/repo
        val remoteUrl = gitRepo.remotes.firstOrNull()?.firstUrl ?: return getPrimary()
        val (parsedProject, parsedRepo) = parseRemoteUrl(remoteUrl) ?: return getPrimary()

        return settings.getRepos().find {
            it.bitbucketProjectKey.equals(parsedProject, ignoreCase = true) &&
            it.bitbucketRepoSlug.equals(parsedRepo, ignoreCase = true)
        } ?: getPrimary()
    }

    /**
     * Resolve from the currently focused editor file, or fall back to primary.
     */
    fun resolveFromCurrentEditor(): RepoConfig? {
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            .selectedEditor
        val file = editor?.file ?: return getPrimary()
        return resolveFromFile(file)
    }

    /**
     * Get all configured repos.
     */
    fun getAllRepos(): List<RepoConfig> = PluginSettings.getInstance(project).getRepos()

    /**
     * Get the primary (default) repo.
     */
    fun getPrimary(): RepoConfig? = PluginSettings.getInstance(project).getPrimaryRepo()

    /**
     * Auto-detect repos from all VCS roots and suggest RepoConfigs.
     */
    fun autoDetectRepos(): List<RepoConfig> {
        val gitRepos = GitRepositoryManager.getInstance(project).repositories
        return gitRepos.mapNotNull { repo ->
            val remoteUrl = repo.remotes.firstOrNull()?.firstUrl ?: return@mapNotNull null
            val (projectKey, repoSlug) = parseRemoteUrl(remoteUrl) ?: return@mapNotNull null
            RepoConfig(
                name = repoSlug,
                bitbucketProjectKey = projectKey,
                bitbucketRepoSlug = repoSlug,
                localVcsRootPath = repo.root.path,
                isPrimary = repo == gitRepos.first()
            )
        }
    }

    private fun parseRemoteUrl(url: String): Pair<String, String>? {
        // Parse SSH: ssh://git@server/PROJECT/repo.git
        // Parse HTTPS: https://server/scm/PROJECT/repo.git
        val sshPattern = Regex(".*/([^/]+)/([^/]+?)(?:\\.git)?$")
        val match = sshPattern.find(url) ?: return null
        return Pair(match.groupValues[1], match.groupValues[2])
    }

    companion object {
        fun getInstance(project: Project): RepoContextResolver {
            return project.getService(RepoContextResolver::class.java)
        }
    }
}
```

Register as a project-level service in `plugin.xml`.

- [ ] **Step 2: Build and commit**

Run: `./gradlew :core:compileKotlin`

```bash
git add core/
git commit -m "feat(core): RepoContextResolver — auto-resolves RepoConfig from file, editor, or VCS root"
```

---

### Task 3: Update Core Service Interfaces

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/services/BitbucketService.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/services/BambooService.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/services/SonarService.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/services/JiraService.kt`

- [ ] **Step 1: Add repoName parameter to BitbucketService methods**

Add an optional `repoName: String? = null` parameter (NOT `RepoConfig` — keeps the interface simple for agent consumption). When null, the implementation uses the primary repo. The implementation resolves `repoName` to `RepoConfig` via `settings.getRepos().find { it.name == repoName }`:

```kotlin
interface BitbucketService {
    suspend fun createPullRequest(title: String, description: String, fromBranch: String, toBranch: String, repoName: String? = null): ToolResult<PullRequestData>
    suspend fun getBranches(filter: String? = null, repoName: String? = null): ToolResult<List<BranchData>>
    suspend fun getMyPullRequests(state: String = "OPEN", repoName: String? = null): ToolResult<List<PullRequestData>>
    // ... all other methods get repoName: String? = null

    // New method for agent to discover available repos:
    suspend fun listRepos(): ToolResult<List<RepoInfo>>
}
```

Add `RepoInfo` data class to core models:
```kotlin
data class RepoInfo(val name: String, val projectKey: String, val repoSlug: String, val isPrimary: Boolean)
```

- [ ] **Step 2: Add repoName to BambooService**

Methods that reference a plan key should accept optional `repoName: String? = null`:

```kotlin
interface BambooService {
    suspend fun getLatestBuild(planKey: String? = null, repoName: String? = null): ToolResult<BuildResultData>
    // When planKey is null AND repoName is provided, derive planKey from repos.find { it.name == repoName }.bambooPlanKey
    // ...
}
```

- [ ] **Step 3: Add repoName to SonarService**

```kotlin
interface SonarService {
    suspend fun getIssues(projectKey: String? = null, filePath: String? = null, repoName: String? = null): ToolResult<List<SonarIssueData>>
    // When projectKey is null, derive from repos.find { it.name == repoName }.sonarProjectKey
    // ...
}
```

- [ ] **Step 4: JiraService — no repo parameter needed**

Jira operations are ticket-centric, not repo-centric. No changes needed.

**Note for agent branch:** When the agent module is rebased, tool wrappers should expose `repoName: String?` as an optional parameter. Add a `listRepos` tool that calls `BitbucketService.listRepos()` so the agent can discover available repos before operating on one.

- [ ] **Step 5: Build and commit**

Run: `./gradlew :core:compileKotlin`

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/services/
git commit -m "feat(core): add optional RepoConfig parameter to service interfaces for multi-repo support"
```

---

### Task 4: Update Service Implementations

**Files:**
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt`
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrListService.kt`
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrActionService.kt`
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrDetailService.kt`
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarServiceImpl.kt`

- [ ] **Step 1: Add resolveRepo helper to BitbucketServiceImpl**

```kotlin
private fun resolveRepo(repo: RepoConfig?): Pair<String, String>? {
    val resolved = repo ?: RepoContextResolver.getInstance(project).getPrimary()
    if (resolved == null || !resolved.isConfigured) return null
    return Pair(resolved.bitbucketProjectKey, resolved.bitbucketRepoSlug)
}
```

Replace all 25 occurrences of:
```kotlin
val projectKey = settings.state.bitbucketProjectKey.orEmpty()
val repoSlug = settings.state.bitbucketRepoSlug.orEmpty()
```
with:
```kotlin
val (projectKey, repoSlug) = resolveRepo(repo) ?: return notConfiguredError()
```

- [ ] **Step 2: Update PrListService for multi-repo**

Change `PrListService` to fetch PRs from ALL configured repos (or a selected one):

```kotlin
private suspend fun fetchAllRepos(): Pair<List<BitbucketPrDetail>, List<BitbucketPrDetail>> {
    val allMyPrs = mutableListOf<BitbucketPrDetail>()
    val allReviewingPrs = mutableListOf<BitbucketPrDetail>()

    for (repo in settings.getRepos().filter { it.isConfigured }) {
        val myResult = fetchAllPages("AUTHOR", repo)
        val reviewingResult = fetchAllPages("REVIEWER", repo)
        allMyPrs.addAll(myResult)
        allReviewingPrs.addAll(reviewingResult)
    }

    return Pair(allMyPrs, allReviewingPrs)
}
```

Each PR item should carry its repo info (projectKey/repoSlug) so the detail view knows which repo to query.

- [ ] **Step 3: Update BambooServiceImpl**

Add `resolveRepo` pattern. When `repo` param is provided, use `repo.bambooPlanKey`. When null, fall back to primary.

- [ ] **Step 4: Update SonarServiceImpl**

Same pattern. When `repo` param is provided, use `repo.sonarProjectKey`.

- [ ] **Step 5: Build and commit**

Run: `./gradlew compileKotlin`

```bash
git add pullrequest/ bamboo/ sonar/
git commit -m "feat: update service implementations to resolve repo from RepoConfig"
```

---

### Task 5: Fix Git Repository Resolution (20 callsites)

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/BranchingService.kt`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/listeners/BranchChangeTicketDetector.kt`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/CurrentWorkSection.kt`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt`
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt`
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/PrBar.kt`
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/PrDescriptionGenerator.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt`
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/vcs/GenerateCommitMessageAction.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/GitRemoteParser.kt`

- [ ] **Step 1: Replace all `repositories.first()` with context-aware resolution**

For each file, replace:
```kotlin
val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
```
with context-appropriate resolution:

- **BranchingService:** Use the repo matching the configured Bitbucket settings (or the one being branched)
- **BranchChangeTicketDetector:** Determine which VCS root changed and only act if it matches a configured repo
- **CurrentWorkSection:** Show branch from the primary repo (or the one matching the active ticket's branch)
- **BuildDashboardPanel:** Use the repo matching the Bamboo plan's configured Bitbucket repo
- **SonarDataService:** Use the repo matching the Sonar project key
- **GenerateCommitMessageAction:** Use the repo containing the currently edited file
- **GitRemoteParser:** Return all detected repos, not just the first

General pattern:
```kotlin
// Instead of:
val repo = repos.firstOrNull()

// Use:
val resolver = RepoContextResolver.getInstance(project)
val repoConfig = resolver.resolveFromCurrentEditor() ?: resolver.getPrimary()
val gitRepo = repos.find { it.root.path == repoConfig?.localVcsRootPath } ?: repos.firstOrNull()
```

- [ ] **Step 2: Fix BranchChangeTicketDetector to filter by repo**

Only process branch changes for configured repos:

```kotlin
override fun branchHasChanged(branchName: String) {
    val gitRepos = GitRepositoryManager.getInstance(project).repositories
    val changedRepo = gitRepos.find { it.currentBranchName == branchName }
    val resolver = RepoContextResolver.getInstance(project)
    val repoConfig = changedRepo?.let { resolver.resolveFromGitRepo(it) } ?: return

    // Only detect tickets for configured repos
    if (!repoConfig.isConfigured) return

    // Existing ticket detection logic...
}
```

- [ ] **Step 3: Add repo context to BranchChanged event**

In `WorkflowEvent.kt`, add repo info to `BranchChanged`:

```kotlin
data class BranchChanged(
    val branchName: String,
    val projectKey: String? = null,   // which Bitbucket project
    val repoSlug: String? = null      // which repo
) : WorkflowEvent()
```

Update `BranchChangedEventEmitter` to include repo context.

- [ ] **Step 4: Build and commit**

Run: `./gradlew compileKotlin`

```bash
git add jira/ bamboo/ sonar/ cody/ core/
git commit -m "fix: replace repositories.first() with RepoContextResolver across all modules"
```

---

### Task 6: Settings UI — Multi-Repo Configuration

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/GeneralConfigurable.kt`

- [ ] **Step 1: Replace single repo fields with a table**

Replace the single `projectKeyCombo` + `repoSlugCombo` with a JBTable showing all configured repos:

```
| Name     | Bitbucket         | Bamboo Plan  | SonarQube    | Primary |
|----------|-------------------|--------------|--------------|---------|
| backend  | PROJ/backend      | PROJ-BACK    | com:backend  | ✓       |
| frontend | PROJ/frontend     | PROJ-FRONT   | com:frontend |         |
| shared   | PROJ/shared-lib   | PROJ-SHARED  |              |         |
```

Buttons: [Add] [Edit] [Remove] [Auto-Detect]

- [ ] **Step 2: Add/Edit dialog**

A `DialogWrapper` form for editing a `RepoConfig`:
- Name field
- Bitbucket project key + repo slug
- Bamboo plan key (with search)
- SonarQube project key (with picker)
- Docker tag key
- Default target branch
- Primary checkbox
- Auto-detect VCS root path

- [ ] **Step 3: Auto-Detect button**

Calls `RepoContextResolver.autoDetectRepos()` to discover all VCS roots and pre-populate the table.

- [ ] **Step 4: Build and commit**

Run: `./gradlew :core:compileKotlin`

```bash
git add core/
git commit -m "feat(settings): multi-repo configuration table with add/edit/remove/auto-detect"
```

---

### Task 7: PR Tab — Repo Indicator

**Files:**
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrListPanel.kt`
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDashboardPanel.kt`

- [ ] **Step 1: Show repo name on each PR in the list**

When multiple repos are configured, each PR row shows a small repo badge:

```
[backend] #123  Fix login flow        John  2h ago
[frontend] #45  Update styles          Jane  5h ago
```

Use `StatusColors.INFO` for the repo badge. Only show when `repos.size > 1`.

- [ ] **Step 2: Add repo filter to PR dashboard**

Add a repo filter dropdown alongside the state toggle:

```
[All Repos ▾] [Open | Merged | Declined] [My PRs | Reviewing | All]
```

When a specific repo is selected, filter the PR list to that repo only.

- [ ] **Step 3: Build and commit**

Run: `./gradlew :pullrequest:compileKotlin`

```bash
git add pullrequest/
git commit -m "feat(pr): repo indicators and repo filter for multi-repo support"
```

---

### Task 8: Build + Quality Tabs — Repo Awareness

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt`

- [ ] **Step 1: Build tab — repo selector**

When multiple repos with Bamboo plans are configured, add a repo selector in the Build tab header. Switching repos changes the monitored plan.

- [ ] **Step 2: Quality tab — repo selector**

When multiple repos with SonarQube project keys are configured, add a repo selector. Switching repos changes the quality data source.

- [ ] **Step 3: Auto-switch on editor focus**

When the user switches to a file in a different repo, optionally auto-switch the Build and Quality tabs to show data for that repo. Use a subtle notification: "Switched to {repoName}" rather than forcing the switch.

- [ ] **Step 4: Build and commit**

Run: `./gradlew :bamboo:compileKotlin :sonar:compileKotlin`

```bash
git add bamboo/ sonar/
git commit -m "feat: Build and Quality tabs repo selector for multi-repo support"
```

---

## Final Verification

- [ ] Run full build: `./gradlew buildPlugin`
- [ ] Test migration: Verify existing single-repo settings are migrated to `repos[0]` on first load
- [ ] Test multi-repo: Configure 2+ repos, verify each tab shows correct data per repo
- [ ] Test auto-detect: Verify VCS roots are correctly discovered
- [ ] Test context resolution: Switch between files in different repos, verify correct config is used
- [ ] Update `CLAUDE.md` with multi-repo architecture docs
- [ ] Update module-level CLAUDE.md files
