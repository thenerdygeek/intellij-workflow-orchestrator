# Auto-Detection Improvements — Hand-off Brief

**Status:** research complete, implementation pending. Hand-off document for a new session.
**Scope:** improve the accuracy and coverage of `sonarProjectKey`, `bitbucketProjectKey`/`bitbucketRepoSlug`, and `bambooPlanKey` auto-detection in `RepoConfig`.
**Branch context:** authored on `refactor/cleanup-perf-caching` after the Phase 5 cross-tab focus unification fix shipped in v0.83.26-alpha.

This brief is self-contained — it does not require reading the conversation that produced it. Every claim is either tagged **(VERIFIED)** with a source, or **(INFERRED)** with a probe-before-shipping note.

---

## 1. Current state — what exists today

### Sonar key detection
**File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarKeyDetector.kt`
**Interface:** `core/src/main/kotlin/com/workflow/orchestrator/core/services/SonarKeyDetectorService.kt`

Two-step priority per repo:
1. Explicit `<sonar.projectKey>` property in root pom.xml (read via `MavenProject.properties`).
2. Synthesized `groupId:artifactId` from the root pom's MavenId.

**Limitations:**
- Only walks `MavenProjectsManager.rootProjects` — submodule overrides invisible.
- Only Maven — Gradle and CLI-scanner repos return null.
- `detectForPath` requires exact path equality between `repo.localVcsRootPath` and `mavenProject.directory`. Fails when root pom is at a non-git-root path (e.g., `repo/services/pom.xml`).

### Bitbucket project/repo detection
**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/settings/RepoContextResolver.kt:140-161`

Single-mechanism: regex on `GitRepository.remotes.firstOrNull().firstUrl`:
```kotlin
Regex(".*/([^/]+)/([^/]+?)(?:\\.git)?$")
```

**Limitations:**
- Picks first remote — non-deterministic with multiple remotes.
- No validation post-parse — wrong parses propagate silently.
- No detection of Bitbucket Cloud (`bitbucket.org`) → silent failure since the plugin's REST is hard-coded to Server's `/rest/api/1.0/`.
- No handling of PAT-in-URL (`https://<token>@server/scm/...`).
- No use of the server's authoritative `links.clone[]` from the repo response.

### Bamboo plan key detection
**File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionService.kt`

`PlanDetectionService.autoDetect(gitRemoteUrl)`:
1. `GET /rest/api/latest/plan` — fetch ALL plans (paginated).
2. For each plan, `GET /rest/api/latest/plan/{key}/specs` — fetch YAML specs.
3. Parse YAML for `url:` keys, normalize, compare to local git remote.
4. Return single match.

**Limitations:**
- N+1 API calls. Slow on instances with many plans.
- Misses plans with inline (non-Linked) repository definitions.
- Doesn't handle the user's branch-plan-key quirk (master = `PROJ-PLAN`, branches = `PROJ-PLAN-N`).
- `BitbucketBranchClient.extractPlanKey` already does the strip-trailing-`-digits` heuristic but can't distinguish "branch plan with id N, never built" from "master plan, build #N" — same string, different meaning. Misclassifies plans whose shortname ends in digits (`PROJ-SERVICE514`).

---

## 2. Sonar — recommended detection chain (4 tiers)

All four tiers work on **SonarQube Server Community Edition and up** (verified). Tiers 1–3 don't touch the network.

### Tier 0 — `.scannerwork/report-task.txt`
**Most authoritative local source.** Written by every successful scanner run (CLI, Maven, Gradle alike) at `<projectBaseDir>/.scannerwork/report-task.txt`. **(VERIFIED)** against Sonar docs.

Format: plain `key=value` with `projectKey`, `serverUrl`, `serverVersion`, `dashboardUrl`, `ceTaskId`, `ceTaskUrl`. The `projectKey` is the exact key the scanner just used.

**Implementation:** glob the repo (depth 3-4) for any `.scannerwork/` directory — some teams scan from `build/` or a deploy subfolder, not the repo root. Use `java.util.Properties().load(InputStreamReader(stream, UTF_8))`. Off-EDT.

**Caveat:** file only exists after at least one local scan. CI-only projects won't have it.

### Tier 1 — `sonar-project.properties`
**Authoritative for CLI-scanner repos** (Python, JS/TS, Go, polyglot). **(VERIFIED)**: Maven scanner and Gradle scanner **ignore** this file — only the CLI scanner honors it.

**Implementation:** glob for `sonar-project.properties` at repo root and one level deep (monorepos with `cd subproject && sonar-scanner` patterns). One file = one candidate `(subPath, sonarKey)`.

**Note:** the current `RepoConfig` model is 1 repo : 1 sonarKey. Monorepos with multiple Sonar projects per Git repo would need a model extension — out of scope for this pass; surface multiple candidates to the user via picker.

### Tier 2 — Maven model (extend current)
**(VERIFIED)** against `MavenProjectConverter.java` (sonar-scanner-maven master):
- `mavenProject.properties` is **already the resolved effective model** (Maven Embedder runs the same merge as `mvn help:effective-pom`). Submodule overrides AND parent inheritance are pre-applied. No need to walk parents.
- Sonar scanner reads `<sonar.projectKey>` from the **root reactor pom**, falling back to `groupId:artifactId` of the **root reactor**.

**Implementation:**
- Switch from `mavenManager.rootProjects` to `mavenManager.projects` so submodule overrides are visible.
- Use `mgr.findContainingProject(virtualFile)` for O(1) module lookup instead of iterating + path comparison.
- Replace `mavenProject.directory` (`@Obsolete(since = "2026.1")`) with `mavenProject.directoryPath: Path`.
- Per-submodule `sonar.projectKey` overrides win automatically because the properties map is post-resolution.

**Edge case:** properties inside an **inactive** Maven profile won't appear in `mavenProject.properties`. If CI activates a profile that sets `<sonar.projectKey>` but the IDE doesn't, detection misses it. Worth a warning in Settings.

### Tier 3 — Gradle model
**(VERIFIED)**: IntelliJ ships `GradleExtensionsDataService.KEY` populated during sync via `ProjectExtensionsDataBuilderImpl`. Read it without touching the Tooling API:

```kotlin
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import org.jetbrains.plugins.gradle.service.project.data.GradleExtensionsDataService

val moduleNode: DataNode<ModuleData> = ...  // from project structure walk
val extNode = ExternalSystemApiUtil.find(moduleNode, GradleExtensionsDataService.KEY)
val data = extNode?.data
val sonarKey = data?.properties?.get("sonar.projectKey")?.value
```

**Sonar Gradle scanner default** (verified against `SonarPropertyComputer.java`): root key = `[${project.group}:]${project.name}`; submodule key = `<rootKey><submodulePath>` (with leading colon from `getPath()`). Synthesize this if no explicit key is set.

**Caveats:** sync must have completed (`ExternalSystemApiUtil.isInProgress` to gate). Values produced by lazy `Provider` chains may be missing.

### Tier 4 — server search (validation/fallback)
**`GET /api/components/search?qualifiers=TRK&q=<candidate>`** — **(VERIFIED)** Community Edition, no admin permission needed (just `Browse`), shipped since SonarQube 6.3 (2017). Pagination `ps=500` max.

**Use cases:**
- Validate the auto-detected key against the actual server.
- Fallback picker when local detection fails.
- **Critical** for SonarQube Cloud "Automatic Analysis" workflows where projectKey is set on the server during repo import and never appears in any local file. (Server users: not applicable — Server has no Automatic Analysis.)

**Existing infrastructure:** `sonar/.../SonarProjectPickerDialog.kt` already calls this endpoint. Wire it into the auto-detect failure path and add a "Validate on server" Settings button.

### Sources (Sonar)
- [SonarQube — Analysis parameters](https://docs.sonarsource.com/sonarqube-server/analyzing-source-code/analysis-parameters)
- [SonarScanner CLI](https://docs.sonarsource.com/sonarqube-server/analyzing-source-code/scanners/sonarscanner)
- [SonarScanner for Maven](https://docs.sonarsource.com/sonarqube-server/analyzing-source-code/scanners/sonarscanner-for-maven)
- [SonarScanner for Gradle](https://docs.sonarsource.com/sonarqube-server/analyzing-source-code/scanners/sonarscanner-for-gradle)
- [`MavenProjectConverter.java` (sonar-scanner-maven master)](https://github.com/SonarSource/sonar-scanner-maven/blob/master/sonar-maven-plugin/src/main/java/org/sonarsource/scanner/maven/bootstrap/MavenProjectConverter.java)
- [`SonarPropertyComputer.java` (sonar-scanner-gradle master)](https://github.com/SonarSource/sonar-scanner-gradle/blob/master/src/main/java/org/sonarqube/gradle/SonarPropertyComputer.java)
- [`GradleExtensionsDataService.java` (JetBrains)](https://github.com/JetBrains/intellij-community/blob/master/plugins/gradle/src/org/jetbrains/plugins/gradle/service/project/data/GradleExtensionsDataService.java)
- [`MavenProjectsManager.java` (JetBrains)](https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenProjectsManager.java)

---

## 3. Bitbucket — recommended improvements (5 actions)

Ordered by accuracy gain. Verified against Atlassian Bitbucket Server REST docs.

### Action 1 — Validate after parse, persist canonical URL
After regex parse, call `GET /rest/api/1.0/projects/{p}/repos/{s}` (auth: `Authorization: Bearer <PAT>`). **(VERIFIED)** The response includes `links.clone[]` with `{name: "http"|"ssh", href: "..."}` — the **server's canonical clone URLs**.

**Implementation:** add `BitbucketBranchClient.getRepository(projectKey, slug): RepoDetail?`. On success, persist `links.clone.http` to a new `RepoConfig.canonicalCloneUrl` field. Future detection passes can match against this canonical URL instead of re-parsing user-edited remotes — neutralises every URL-shape edge case (PAT-in-URL, smart mirrors, port variations, ssh vs https mismatches).

**404 handling:** parse was wrong → don't persist, surface error in Settings. **401/403:** network reachable, candidate plausible → persist with a "needs auth" flag.

### Action 2 — Iterate all remotes with priority order
Current code: `repo.remotes.firstOrNull()` — non-deterministic.

**Implementation:** iterate `git4idea.repo.GitRepository.getRemotes()`, prefer in this order:
1. `origin`
2. `upstream`
3. `bitbucket`
4. first remote

`GitRemote.getUrls()` returns all fetch URLs (multi-URL remotes are legal); fall back to `getPushUrls()` if all fetch URLs fail validation.

### Action 3 — Detect Bitbucket Cloud and abort
Host `bitbucket.org` (or `*.bitbucket.org`) means the plugin's `/rest/api/1.0/` endpoints don't exist. **(VERIFIED)** Cloud uses `/2.0/` under `api.bitbucket.org` with `workspace/repo_slug`, no project-key concept.

**Implementation:** one-line check before regex; emit an explicit user-facing warning ("This plugin targets Bitbucket Server. The remote `<url>` is Bitbucket Cloud — auto-detection is skipped."). Don't silently treat Cloud as Server.

### Action 4 — Strip userinfo from PAT-in-URL remotes
Common pattern: `https://<pat>@server/scm/PROJ/repo.git`. The current regex matches but includes the userinfo in the host.

**Implementation:** preprocess remote URLs with `java.net.URI` parsing; strip userinfo before regex match.

### Action 5 (optional) — Bulk index
`GET /rest/api/1.0/repos?name=<substring>` returns all visible repos (paginated). Cache `(href → key/slug)` in `PluginSettings` once per session via a "Refresh Bitbucket cache" Settings action. Not worth it for typical setups, but useful for users with unusual remotes that fail steps 1–4.

### URL forms to handle
**(VERIFIED)** against Atlassian docs and observed `links.clone[]` responses:

| Form | Example |
|---|---|
| HTTPS project repo | `https://server/scm/PROJ/repo.git` |
| HTTPS with PAT | `https://<pat>@server/scm/PROJ/repo.git` |
| SSH default port | `ssh://git@server:7999/PROJ/repo.git` |
| SSH scp-style | `git@server:PROJ/repo.git` |
| Personal repo | `https://server/scm/~userslug/repo.git` |

`~userslug` IS the project key in personal repos — the current regex handles this correctly, but no test covers it. Add a unit test.

### Edge cases
- **Submodules:** `GitRepositoryManager.repositories` includes them. Filter `RepoConfig` candidates by checking if `.git` is a `gitdir:` pointer file inside another repo's tree.
- **Worktrees:** `git4idea` exposes worktrees as separate `GitRepository` instances since 2022.x — no extra handling needed.
- **HTTP-redirect after server-side moves:** Bitbucket DC issues 301s on rename; git follows but doesn't rewrite `remote.origin.url`. The validation GET in Action 1 will succeed at the new key — store `links.clone.http` as canonical (Action 1 already covers this).
- **Smart mirrors:** clone URL host is the mirror's, not upstream's. **(INFERRED)** from user reports, not Atlassian docs. Probe-before-shipping.

### Sources (Bitbucket)
- [Bitbucket Data Center REST API (developer.atlassian.com)](https://developer.atlassian.com/server/bitbucket/rest/v1002/intro/)
- [Bitbucket Server REST 7.9 (docs.atlassian.com)](http://docs.atlassian.com/bitbucket-server/rest/7.9.0/bitbucket-rest.html)
- [Bitbucket Server REST 4.5.2 — personal repos via `~userslug`](https://docs.atlassian.com/bitbucket-server/rest/4.5.2/bitbucket-rest.html)
- [Repository slug normalization — Atlassian KB](https://support.atlassian.com/bitbucket-cloud/kb/what-is-a-repository-slug/)
- [JetBrains git4idea — `GitRepository.java`](https://github.com/JetBrains/intellij-community/blob/master/plugins/git4idea/src/git4idea/repo/GitRepository.java)

---

## 4. Bamboo — recommended waterfall (5 tiers)

Replaces current 1+N specs scan. Steady-state cost: **1 HTTP call** vs current **1 + N**.

### Tier 0 — Local `bamboo-specs/bamboo.yml` parse
**Zero HTTP calls.** Many teams check Bamboo Specs into source. Canonical schema:

```yaml
version: 2
plan:
  project-key: PROJ
  key: PLAN
  name: My Plan
repositories:
  - my-repo:
      type: bitbucket-server
      url: ssh://git@bitbucket.example.com/proj/repo.git
```

Plan key = `project-key + "-" + key` → `PROJ-PLAN`. Branch-agnostic — the master plan key.

**Implementation:** parse with `org.yaml.snakeyaml.Yaml` (already a dependency — used by `PlanDetectionService.extractRepoUrls`). Skip Java/Kotlin Specs (would require AST parsing or `mvn -q exec`).

**Files to look for:** `bamboo-specs/bamboo.yml`, `bamboo-specs/*.yaml`, `bamboo-specs/*.yml`.

### Tier 1 — Bitbucket build statuses (commit walk)
**1–10 HTTP calls.** **(VERIFIED)** `GET /rest/build-status/1.0/commits/{sha}` (Bitbucket Server) returns build statuses keyed by commit SHA. Each status has `key` (e.g., `PROJ-PLAN-123-7`) and `url` (e.g., `https://bamboo.example.com/browse/PROJ-PLAN-123-7`).

The existing `BitbucketBranchClient.extractPlanKey` strips one trailing `-<digits>` segment → `PROJ-PLAN-123` (the **branch** plan key for branch builds, or `PROJ-PLAN` for master builds). This is **exactly the right granularity** for branch-aware polling on the user's Bamboo (master = `PROJ-PLAN`, branches = `PROJ-PLAN-N`).

**Implementation:** walk recent commits via `git rev-list -n 10 HEAD`, query each. Most CI systems push status on the branch tip within minutes. Currently only used when a PR is selected — promote to primary detector for any branch.

**Failure mode:** empty for never-built branches and instances where Bamboo's "Notify Bitbucket" feature is disabled.

### Tier 2 — Bamboo `byChangeset`
**1–10 HTTP calls.** **(VERIFIED)** `GET /rest/api/latest/result/byChangeset/{vcs-revision}` returns `Results` with `plan` and `planResultKey` for every plan that built the commit. Goes through Bamboo directly — useful when Bitbucket build statuses are stale or disabled.

**Branch-vs-master disambiguation is free** because `plan.key` returns the branch plan key (`PROJ-PLAN-123`) for branch builds.

**Implementation:** same commit-walk as Tier 1; fall through when Tier 1 returns empty.

### Tier 3 — Linked repositories
**2 HTTP calls.** **(VERIFIED)** `GET /rest/api/latest/repository` — global "Linked Repositories" list with VCS URLs. Then `GET /rest/api/latest/repository/{id}/usedBy` — enumerates plans (and deployment projects) consuming that repository.

Catches plans linked to the repo as a "Linked Repository" but **misses inline plan-local repository definitions**.

**Implementation:** match `repository[].url` against local git remote (use existing `PlanDetectionService.normalizeRepoUrl`).

### Tier 4 — Existing N+1 specs scan
**1 + N HTTP calls.** Last resort. Hide behind a "Deep scan plans" Settings toggle. Catches plans with inline repository definitions that Tier 3 misses.

### Branch-plan resolution
Once master plan key is known (any tier), resolve the branch plan key:

**(VERIFIED)** `GET /rest/api/latest/plan/{masterPlanKey}/branch?max-results=200` returns `PlanBranches` with each branch's `key` (the `PROJ-PLAN-N` form) and `shortName` (the VCS branch name). Filter `shortName == localBranchName`.

**Why this beats the current strip-trailing-digits heuristic:**
- Distinguishes `PROJ-SERVICE514` (plan name ending in digits) from `PROJ-SERVICE-514` (branch plan id 514). Current heuristic over-strips.
- Distinguishes `PROJ-PLAN-7` interpreted as "master plan, build #7" vs "branch plan with id 7". Same string, structural truth from API.

### Validation pattern
After any candidate key is produced, **(VERIFIED)** `GET /rest/api/latest/plan/{candidate}` returns 200 (valid) or 404 (over-stripped). Cache positive validations indefinitely; negatives ~5 min.

### Settings autocomplete
**(VERIFIED)** `GET /rest/api/latest/search/plans?searchTerm=...` for the Settings manual-override dropdown. Cheap, paginated, returns `searchEntity.key` + name. Not appropriate for auto-detection (no VCS info).

### What does NOT work
- **No bulk-specs endpoint exists.** **(VERIFIED)** `expand=` parameters don't include specs. The N+1 scan cannot be batched server-side. Only way to drop it is to use the alternative tiers.
- **`Bamboo.disabledBranchPlanCreation`** — some teams disable auto branch-plan creation. There's no public REST flag for this; **(INFERRED)** infer from absence of branch in `/plan/{master}/branch` list. Surface as a "no branch plan exists; falling back to master plan" hint in the Build tab.

### Server vs Data Center
All endpoints above present in both Bamboo Server (perpetual, EOL Feb 2024) and Bamboo Data Center 9.x. DC adds a richer v2 build-status path. No deprecations affecting this plan as of 9.6.

### Sources (Bamboo)
- [Bamboo REST API Reference (Atlassian developer)](https://developer.atlassian.com/server/bamboo/bamboo-rest-resources/)
- [Bamboo Specs YAML reference](https://confluence.atlassian.com/bamboo/bamboo-specs-reference-938641678.html)
- [Bitbucket build-status API (`/rest/build-status/1.0/commits/{sha}`)](https://developer.atlassian.com/server/bitbucket/rest/v1002/api-group-builds-and-deployments/)

---

## 5. Suggested implementation order

This is the recommended phasing. Each phase is independently shippable.

### Phase A — Sonar tiers 0+1+2 (small, high coverage)
- Tier 0: `.scannerwork/report-task.txt` parser + glob.
- Tier 1: `sonar-project.properties` parser + glob.
- Tier 2: extend `SonarKeyDetector` to walk `mavenManager.projects` instead of `rootProjects`.
- Files: `sonar/.../SonarKeyDetector.kt`, plus a new `core/.../scannerwork/` reader.
- Tests: extend `SonarKeyDetectorTest` with cases for each new tier.

### Phase B — Sonar tier 3 (Gradle)
- Read `GradleExtensionsDataService.KEY → properties["sonar.projectKey"].value`.
- Synthesize `${project.group}:${project.name}` fallback per Gradle scanner default.
- New file: `sonar/.../GradleSonarKeyDetector.kt`.

### Phase C — Sonar tier 4 (server validation)
- Wire existing `SonarProjectPickerDialog` into the auto-detect failure path.
- Add "Validate on server" button in Settings.

### Phase D — Bitbucket validation + canonical URL persistence
- Add `RepoConfig.canonicalCloneUrl: String?`.
- Add `BitbucketBranchClient.getRepository(projectKey, slug)` returning `links.clone[]`.
- Modify `RepoContextResolver.autoDetectRepos` to validate post-parse and persist canonical URL.
- Iterate all remotes with priority order; strip userinfo; detect Cloud and abort.

### Phase E — Bamboo tier 0+1 (local specs + commit walk)
- Tier 0: parse `bamboo-specs/bamboo.yml` for `project-key + key`.
- Tier 1: promote existing commit-walk via Bitbucket build-status to the primary detector.
- Modify `PlanDetectionService` to try tiers in order; gate the existing N+1 scan behind a "Deep scan" toggle.

### Phase F — Bamboo tier 2+3 + branch resolution
- Tier 2: `result/byChangeset/{sha}` walk.
- Tier 3: `repository` + `repository/{id}/usedBy`.
- Branch resolution: `plan/{master}/branch` filter by `shortName`.
- Add validation `GET /plan/{candidate}` → cache positive results.

---

## 6. Cross-cutting concerns

### Threading
All file reads and HTTP calls must run off-EDT. Use `Dispatchers.IO` inside `scope.launch`. Wrap any post-detection PSI/index access in `readAction { }` per `:core` "Service & threading conventions."

### Caching
- Sonar: cache server-search results in `PluginSettings`; invalidate on auth-token change.
- Bitbucket: cache `(href → projectKey/slug)` index; invalidate via "Refresh" Settings action.
- Bamboo: cache positive plan-validation results indefinitely (plan keys don't change); negatives ~5 min.

### Settings UX
- Per the user's `feedback_settings_ui` memory: every new field needs a Settings UI surface.
- New `RepoConfig.canonicalCloneUrl` field needs a Settings row (read-only display).
- Picker dialogs should default to auto-detected candidate, allow override.

### Error surfacing
- Auto-detection failures should surface as actionable hints in the affected tab, not silent nulls.
- Pattern from existing code: `showHint("...not configured for X — configure in Settings > CI/CD")`.

### Test infrastructure
- Existing tests use `mockk` + `runTest` for service-level testing.
- For VFS-based file reads, use `LightPlatformTestCase` or `BasePlatformTestCase`.
- Avoid testing UI panels — see `project_deferred_ui_refactors` memory.

---

## 7. What NOT to do

- **Don't** parse raw `pom.xml` / `build.gradle` text. The IntelliJ Maven/Gradle models already have placeholders pre-resolved. Falling back to text parsing reintroduces a class of bugs (`${project.artifactId}` expansion, profile activation, etc.).
- **Don't** add scalar fallbacks at writer sites (the bug fixed in v0.83.26-alpha). All key resolution should happen inside `WorkflowContextService` or its detector services. Keep multi-repo isolation.
- **Don't** treat the current `extractPlanKey` strip-trailing-digits heuristic as authoritative. Validate via API after stripping.
- **Don't** silently skip on detection failure. Surface "could not detect" with a link to manual configuration.
- **Don't** bundle Phases together — each is independently shippable, and bundling makes review and rollback harder.

---

## 8. Files to read before implementing

Required context for any agent picking up this work:

- `core/CLAUDE.md` — service architecture, threading conventions, EP patterns
- `bamboo/CLAUDE.md` — Bamboo REST endpoints already plumbed
- `sonar/CLAUDE.md` — Sonar REST endpoints already plumbed
- `pullrequest/CLAUDE.md` — Bitbucket REST endpoints already plumbed
- `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextService.kt` — key resolution happens here (the v0.83.26 fix)
- `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectOrchestrator.kt` — orchestrates the detection passes; new tiers plug in here
- `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionService.kt` — the file to refactor for the Bamboo waterfall
- `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarKeyDetector.kt` — the file to extend with tiers
- `core/src/main/kotlin/com/workflow/orchestrator/core/settings/RepoContextResolver.kt:140-161` — the regex parser to replace
- `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt:500` — `extractPlanKey` heuristic; keep but augment with API validation
- `core/src/main/kotlin/com/workflow/orchestrator/core/settings/RepoConfig.kt` — add `canonicalCloneUrl` field

---

## 9. Open questions for the next session

These were noted but not resolved during research:

1. Should `RepoConfig` evolve to support multiple Sonar projects per Git repo (monorepo case)? Or keep 1 repo : 1 key and force users to configure separate `RepoConfig` entries for sub-paths?
2. For SonarQube Server users on **9.9 LTS or earlier** — the plugin uses `Authorization: Bearer <token>` which works on 10.0+. Older versions used HTTP Basic with token-as-username. Is this a pre-existing concern (Quality tab already broken on 9.x)? Need to verify with users.
3. The Bamboo `Bamboo.disabledBranchPlanCreation` setting can't be queried via REST. Inference (absence in `/plan/{master}/branch`) is the only fallback — is that good enough UX?
4. Bitbucket smart-mirror clone URL behaviour is reported but not in official docs — can we get a probe test on a real smart-mirror instance before relying on host-based detection?
