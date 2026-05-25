# :jira Module Security & Correctness Audit
**Date:** 2026-05-24  
**Auditor:** Claude Sonnet 4.6 (read-only)  
**Scope:** `jira/src/main/kotlin/`  
**Files audited:** 55 Kotlin source files

---

## Findings

---

### F-1 [P0] [Security/SSRF]: `getRawString` accepts and forwards arbitrary absolute URLs without origin validation

**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/api/JiraApiClient.kt:303`

**Description:**  
`getRawString` detects absolute URLs (`http://` or `https://`) and forwards them verbatim to the OkHttp client — including `Authorization: Bearer <token>` via the `AuthInterceptor`. The caller `JiraSearchServiceImpl.followAutoCompleteUrl` passes a server-supplied `autoCompleteUrl` field (from the transitions API response at `/rest/api/2/issue/{key}/transitions?expand=transitions.fields`) directly as the `url` argument, appending only the query parameter. If a compromised or misconfigured Jira server returns an `autoCompleteUrl` pointing to a non-Jira host, the plugin will forward the Bearer token to that host.

**Evidence:**
```kotlin
// JiraApiClient.kt:303-304
val url = if (path.startsWith("http://") || path.startsWith("https://")) path
          else "$baseUrl$path"
val request = Request.Builder().url(url).get().build()

// JiraSearchServiceImpl.kt:265-268
val fullUrl = "$url${separator}query=${enc(query)}"
log.debug("[JiraSearch] followAutoCompleteUrl -> $fullUrl")
return when (val result = api.getRawString(fullUrl)) {
```

**Impact:**  
Token exfiltration to attacker-controlled server. If Jira server is compromised (supply-chain or misconfiguration), the `autoCompleteUrl` field in a transition schema response could redirect the plugin's auth-bearing request to an external host, leaking the PAT.

**Fix sketch:**  
Validate that `fullUrl` is same-origin as `baseUrl` before calling `getRawString`. Reject (or strip absolute prefix and re-join to baseUrl) any `autoCompleteUrl` that does not start with the configured Jira base URL. Add a `sameOriginOrRelative(path: String, baseUrl: String): Boolean` helper.

---

### F-2 [P0] [Security]: JQL injection via unvalidated `keys` list in `validateTicketKeys`

**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/api/JiraApiClient.kt:498`

**Description:**  
`validateTicketKeys` concatenates the caller-supplied `keys` list directly into a JQL `key in (...)` clause without escaping. The keys originate from branch-name extraction (`extractTicketIdFromBranch`) and text parsing (`extractMentionedTickets`), which apply `Regex("[A-Z][A-Z0-9]+-\\d+")`. However the regex only validates _matched substrings_, not the complete key string. A branch name like `feature/PROJ-1) OR project = ADMIN AND (key` would still pass the regex (the regex finds `PROJ-1` as a substring), and the surrounding shell-unsafe characters would be included in the list element assembled elsewhere. In contrast, if keys are sourced from agent tool input (no regex gate), arbitrary JQL injection is straightforward.

**Evidence:**
```kotlin
// JiraApiClient.kt:497-499
val jql = "key in (${keys.joinToString(",")})"
val body = buildJsonObject {
    put("jql", jql)
```

**Impact:**  
Attacker-controlled JQL can enumerate or exfiltrate tickets outside the authorized project scope, bypass assignment filters, or cause denial-of-service via expensive JQL queries.

**Fix sketch:**  
Validate each element of `keys` against `Regex("^[A-Z][A-Z0-9]+-\\d+$")` (anchored) before building the JQL clause, and reject any key that does not match. This mirrors the `looksLikeKey` check already in `searchIssues`.

---

### F-3 [P0] [Security/XSS]: Unescaped Jira ticket summary rendered in Swing HTML context

**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketDetailPanel.kt:908`  
**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketListCellRenderer.kt:47`

**Description:**  
`TicketDetailPanel.addHeader` sets a `JBLabel` directly from `issue.fields.summary` (no escape). Swing `JLabel`/`JBLabel` render any text starting with `<html>` as HTML. A Jira ticket whose summary begins with `<html><script>...` or contains `<img src=x onerror=...>` would execute in the Swing HTML renderer. Similarly `TicketListCellRenderer` sets `toolTipText = "${value.key}: ${value.fields.summary}"` and subtask/linked-issue labels use `toolTipText = subtask.fields.summary` directly.

**Evidence:**
```kotlin
// TicketDetailPanel.kt:908
headerPanel.add(JBLabel(issue.fields.summary).apply {
    font = font.deriveFont(Font.BOLD, JBUI.scale(16).toFloat())
    ...
})

// TicketListCellRenderer.kt:47
toolTipText = "${value.key}: ${value.fields.summary}"

// TicketDetailPanel.kt:482
if (subtask.fields.summary.length > 50) toolTipText = subtask.fields.summary
```

**Impact:**  
Malicious Jira ticket summaries (creatable by any Jira user with CREATE_ISSUE permission) can render arbitrary HTML in the IDE, potentially executing local actions via custom Swing HTML renderers or leaking context via embedded network requests.

**Fix sketch:**  
Wrap all summary/description text placed into `JBLabel` or `toolTipText` with `HtmlEscape.escapeHtml()` (already imported and used for comment bodies at line 840). The `toolTipText` setter in particular must escape because Swing processes it through an HTML engine.

---

### F-4 [P0] [Security/SSRF]: No scheme/host validation on Jira base URL from settings before HTTP calls

**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/JiraServiceImpl.kt:102-108`  
**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/JiraSearchServiceImpl.kt:51`

**Description:**  
`JiraServiceImpl.client` builds a `JiraApiClient` from `settings.connections.jiraUrl` with no scheme or host validation. There is no check that the URL starts with `https://` (or at minimum `http://`). A settings value of `file:///etc/passwd` or `ldap://internal-host` would be passed directly to `OkHttpClient`, which would follow the protocol. The auth token is injected by `AuthInterceptor` on all requests regardless of destination.

**Evidence:**
```kotlin
// JiraServiceImpl.kt:102-108
val url = settings.connections.jiraUrl.orEmpty().trimEnd('/')
if (url.isBlank()) return null
if (url != cachedBaseUrl || cachedClient == null) {
    cachedBaseUrl = url
    cachedClient = JiraApiClient(
        baseUrl = url,
        tokenProvider = { credentialStore.getToken(ServiceType.JIRA) }
    )
}
```

**Impact:**  
If an attacker modifies settings.xml (e.g., via a compromised IDE plugin or settings sync), they can redirect all Jira API calls — including auth token headers — to an internal network resource or file-system path. Combined with F-1, this allows full SSRF.

**Fix sketch:**  
Before constructing `JiraApiClient`, validate `url` with `URL(url).also { require(it.protocol in setOf("http", "https")) }`. Throw or return null if the protocol is anything else. Mirror this in `JiraSearchServiceImpl`.

---

### F-5 [P0] [Resource Leak]: `ChangelogSection`, `WorklogSection`, `DevStatusSection`, `LinkedDocsSection` declare `dispose()` but do NOT implement `Disposable` — dispose is never called by the JB platform

**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/ChangelogSection.kt:37,138`  
**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/WorklogSection.kt:34,215`  
**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/DevStatusSection.kt:38,344`  
**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/LinkedDocsSection.kt:37,143`

**Description:**  
Each section class allocates a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` and defines a plain `fun dispose()` that calls `scope.cancel()`. However, none of these classes implements `com.intellij.openapi.Disposable`. `TicketDetailPanel.showIssue` calls `currentXxxSection?.dispose()` on _the previous_ section before replacing it — so the replace path is covered — but these scopes are **not** registered with the JB `Disposer` tree. If `TicketDetailPanel.dispose()` is called without first having called `showIssue` again, the currently active sections will never have their `dispose()` called via the platform. The platform's `Disposer.register(this, child)` in `TicketDetailPanel` applies only to the `TicketDetailPanel` itself, not to the sections (which are not registered).

**Evidence:**
```kotlin
// ChangelogSection.kt:37,40
class ChangelogSection(private val project: Project) : JPanel(BorderLayout()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    ...
    fun dispose() { scope.cancel() }   // no 'override', not Disposable

// TicketDetailPanel.kt:849-860 — calls currentXxxSection?.dispose() but
// does NOT register sections with Disposer.register(this, section)
```

**Impact:**  
Every ticket selection that results in panel disposal (IDE close, tab close, project close) leaks one `SupervisorJob` per section type (4 scopes × N ticket views). Each scope holds an OkHttp dispatcher thread until GC, potentially exhausting the shared thread pool in long IDE sessions.

**Fix sketch:**  
Either (a) implement `Disposable` on each section and `Disposer.register(ticketDetailPanel, section)` after construction, or (b) have each section accept the `TicketDetailPanel`'s `lazyScope` and launch within it instead of owning a standalone scope.

---

### F-6 [P0] [Resource Leak]: `PostCommitTransitionHandler` and `TimeTrackingCheckinHandler` create fire-and-forget `CoroutineScope` with no lifecycle handle

**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/PostCommitTransitionHandlerFactory.kt:42`  
**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/TimeTrackingCheckinHandlerFactory.kt:105`

**Description:**  
Both checkin handlers create a new `CoroutineScope(SupervisorJob() + Dispatchers.IO)` on each commit and immediately launch a coroutine. The scope object is never stored or cancelled. While `project.isDisposed` guard is present, the scope itself — along with any in-flight network call — leaks until the coroutine completes. On a slow network with a rapid commit sequence, many such scopes accumulate.

**Evidence:**
```kotlin
// PostCommitTransitionHandlerFactory.kt:42-75
CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
    if (project.isDisposed) return@launch
    ...
    val jiraService = JiraServiceImpl.getInstance(project)
    val issueResult = jiraService.getTicket(ticketId)
    ...
}

// TimeTrackingCheckinHandlerFactory.kt:105-118
CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
    if (project.isDisposed) return@launch
    ...
}
```

**Impact:**  
Scope leak per commit. Low severity individually but accumulates in CI-heavy workflows. If the Jira API is unreachable, the coroutine may hang for the full read timeout (30 s default), blocking the thread pool slot.

**Fix sketch:**  
Use `project.coroutineScope.launch(Dispatchers.IO)` (platform-injected project-level scope) so cancellation is automatically tied to project lifecycle. Alternatively use `com.intellij.openapi.progress.runBlockingCancellable` on a pooled thread as the existing `JiraTaskRepository` does.

---

### F-7 [P1] [Correctness]: `JiraTaskFunnel.buildJql` passes `query` as regex-matched key but the `summary ~ "..."` branch uses `escapeJql`; the `key = "..."` branch double-quotes without re-checking validity

**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/tasks/JiraTaskFunnel.kt:70-76`

**Description:**  
When `query` matches `[A-Z][A-Z0-9]+-\d+` exactly (`.matches` — anchored), the JQL clause is `key = "${escapeJql(query)}"`. The `escapeJql` function escapes JQL reserved characters like `"` and `\`. However, the anchored regex guarantees no reserved chars are present, making `escapeJql` a no-op here. More importantly, when `query` does NOT match the pattern, the `summary ~ "…"` branch also uses `escapeJql(query)`, which escapes `"` by prepending `\`. But Jira JQL `text ~ "…"` does not support backslash-escaped quotes inside double-quoted values on all DC versions (DC 10.x rejects them). Result: a query containing a double-quote character fails with a confusing 400 from Jira instead of a graceful "no results".

**Evidence:**
```kotlin
// JiraTaskFunnel.kt:70-76
if (Regex("[A-Z][A-Z0-9]+-\\d+").matches(query)) {
    parts.add("key = \"${escapeJql(query)}\"")
} else {
    parts.add("summary ~ \"${escapeJql(query)}\"")
}
```

**Impact:**  
IntelliJ Tasks integration search fails for queries with special characters (especially `"`). Users see a 400 or empty results with no indication the query was malformed.

**Fix sketch:**  
Strip double quotes (and other chars Jira `~` does not support inside quoted strings) before interpolation in the `summary ~` clause, or encode the search term as a POST body query via `POST /rest/api/2/search` to avoid URL and JQL quoting issues.

---

### F-8 [P1] [Correctness]: `BranchChangeTicketDetector` reads `activeTicketId` from `PluginSettings.state` instead of `ActiveTicketService`

**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/listeners/BranchChangeTicketDetector.kt:68-75`

**Description:**  
The detector skips branch processing if `settings.state.activeTicketId == ticketId`. However, `ActiveTicketService` (Phase 5 T13) updates a `_localFlow` synchronously and dispatches the canonical write to `WorkflowContextService` asynchronously. The settings state may lag behind the in-memory `ActiveTicketService` state by up to one event loop. If a user rapidly switches branches, the "already active" guard may miss the skip and emit a spurious `TicketDetectedInteractive` event for the current ticket.

**Evidence:**
```kotlin
// BranchChangeTicketDetector.kt:68-75
val currentActiveTicket = settings.state.activeTicketId
if (currentActiveTicket == ticketId) {
    log.debug("[Jira:Branch] Ticket $ticketId is already active, skipping detection")
    return
}
```

**Impact:**  
Users see an unexpected "detected ticket" popup for the ticket they just activated via Start Work, requiring an extra dismiss action.

**Fix sketch:**  
Read from `ActiveTicketService.getInstance(project).activeTicketId` (the synchronous local cache) instead of `settings.state.activeTicketId`.

---

### F-9 [P1] [Correctness]: `startWork` in `JiraServiceImpl` does not create the branch — it only transitions the ticket; callers expecting branch creation via this path are misled

**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/JiraServiceImpl.kt:737-831`

**Description:**  
The `JiraService.startWork(issueKey, branchName, sourceBranch)` override is the agent-facing entry point. Despite the `branchName` and `sourceBranch` parameters, the method only transitions the Jira ticket (via `TicketTransitionService`) and returns a `StartWorkResultData`. Branch creation on Bitbucket and local Git checkout are done exclusively by `BranchingService.startWork`, which is only wired via the UI dialog (`SprintDashboardPanel`). The agent calling `startWork` will get a success result with `branchName` in the data but no actual branch will exist. This is a semantic contract violation visible in `StartWorkResultData.branchName` populated from the caller's input, not from any created branch.

**Evidence:**
```kotlin
// JiraServiceImpl.kt:737-831
override suspend fun startWork(
    issueKey: String,
    branchName: String,   // parameter used only in return value
    sourceBranch: String  // not used at all
): ToolResult<StartWorkResultData> {
    // ...only calls transitionSvc.executeTransition(...)
    val data = StartWorkResultData(
        branchName = branchName,  // echoes input, no branch created
        ticketKey = issueKey,
        transitioned = transitioned
    )
}
```

**Impact:**  
Agent tool `start_work` returns a false success — branch never exists — causing subsequent `git checkout` failures. Misleading `summary` string `"Branch: $branchName (from $sourceBranch)"` implies branch was created.

**Fix sketch:**  
Either (a) route `BranchingService.startWork` through a core interface so the agent can invoke it, or (b) rename the method `transitionToInProgress` and update the tool schema/summary to make clear it only transitions; drop the branch-related parameters.

---

### F-10 [P1] [Performance / Correctness]: `SprintPaginationCache` has a TOCTOU race in `getOrLoadCache` between the double-checked lock read and disk write

**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/SprintPaginationCache.kt:76-87`

**Description:**  
`getOrLoadCache` uses a double-checked locking pattern: first checks `cache` inside `synchronized`, releases lock, reads disk, then re-checks inside `synchronized`. The `readFromDisk` call happens outside the lock — this is intentional to avoid blocking. However, `saveCachedStartAt` also writes to disk outside its `synchronized` block (line 72). If two IDE instances (or two coroutines) call `saveCachedStartAt` concurrently while `getOrLoadCache` is performing the disk read, the file write and file read are unsynchronized at the OS level, potentially producing a partial/corrupted read. The `.json` file is also written with `cacheFile.writeText(...)` — not atomic — so a crash mid-write leaves a corrupt file that throws on next startup.

**Evidence:**
```kotlin
// SprintPaginationCache.kt:76-87
private fun getOrLoadCache(): CacheData {
    synchronized(lock) { cache?.let { return it } }
    val loaded = readFromDisk()   // unprotected file read
    synchronized(lock) {
        cache?.let { return it }
        cache = loaded
        return loaded
    }
}
// saveCachedStartAt calls writeToDisk(snapshot) outside lock at line 72
```

**Impact:**  
Corrupted sprint pagination cache causes `json.decodeFromString` to throw on next IDE start, resetting to offset 0 and forcing a full sprint re-walk (O(N) API calls). Low likelihood but zero recovery path beyond manual file deletion.

**Fix sketch:**  
Write to a `.tmp` file then `Files.move(ATOMIC_MOVE)` (matching the agent session persistence pattern). Additionally, catch `SerializationException` in `readFromDisk` and delete the corrupt file before returning `CacheData()`.

---

### F-11 [P1] [Performance]: `IssueDetailCache.evictIfNeeded` performs O(N log N) sort on the hot ConcurrentHashMap every time an entry is added after reaching MAX_SIZE (200)

**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/IssueDetailCache.kt:49-54`

**Description:**  
`evictIfNeeded` is called after every `put` and `updateComments`/`updateAttachments`. When the cache is full (size >= 200), it iterates all entries, sorts them by `fetchedAt` (O(N log N)), removes the oldest. This runs on the EDT in the `updateComments` path because `lazyLoadComments` eventually calls `cache.updateComments` from a coroutine dispatched to `Dispatchers.IO`, but the `evictIfNeeded` itself is synchronous and the `ConcurrentHashMap` iteration creates a snapshot of all 200 entries per invocation.

**Evidence:**
```kotlin
// IssueDetailCache.kt:49-54
private fun evictIfNeeded() {
    if (cache.size > MAX_SIZE) {
        val sorted = cache.entries.sortedBy { it.value.fetchedAt }
        val toRemove = cache.size - MAX_SIZE
        sorted.take(toRemove).forEach { cache.remove(it.key) }
    }
}
```

**Impact:**  
In a large sprint (200+ tickets viewed), every cache update after the 200th entry triggers a 200-element sort. In fast keyboard navigation, this runs multiple times per second on the IO dispatcher but contends on the `ConcurrentHashMap` snapshot, adding latency to comment rendering.

**Fix sketch:**  
Use a `LinkedHashMap(16, 0.75f, true)` (access-ordered) wrapped in `Collections.synchronizedMap` (as `AttachmentDownloadService.thumbnailCache` already does), setting `removeEldestEntry` to enforce the cap. This gives O(1) eviction.

---

### F-12 [P1] [Security]: `searchTickets(jql, maxResults)` passes a raw agent-supplied JQL string directly to Jira without any validation or sanitization

**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/JiraServiceImpl.kt:1206-1230`

**Description:**  
The `searchTickets` method is the agent-facing API. It accepts an arbitrary `jql: String` from the LLM/agent and forwards it unmodified to `api.searchByJql`. While this is intentional for flexibility, combined with the absence of any `ToolResult` validation or permission pre-check, a manipulated or hallucinated JQL (e.g., `project = ADMIN AND issuetype = SecurityVulnerability`) allows the agent to enumerate sensitive tickets across any project the configured token has access to, including projects not in scope.

**Evidence:**
```kotlin
// JiraServiceImpl.kt:1206-1214
override suspend fun searchTickets(jql: String, maxResults: Int): ToolResult<List<JiraTicketData>> {
    val api = client ?: ...
    return when (val result = api.searchByJql(jql, maxResults)) {
        is ApiResult.Success -> {
            val tickets = result.data.map { it.toTicketData() }
            ToolResult.success(data = tickets, summary = "${tickets.size} ticket(s) found")
        }
```

**Impact:**  
Jira-scoped information disclosure. An agent session that has been compromised or receives a crafted user prompt can exfiltrate ticket data from any project the PAT has READ access to. This is particularly sensitive in enterprise environments where security/vulnerability tickets are in separate restricted projects.

**Fix sketch:**  
Add a `currentUserOnly` clause to `searchTickets` (defaulting to `true`) that appends `AND assignee = currentUser()` unless the agent explicitly opts out with a documented flag. Alternatively, surface a `projectKey` scope parameter so cross-project JQL must be explicit.

---

### F-13 [P2] [Quality/Redundancy]: Duplicate ticket → `TicketData` mapping in `JiraServiceImpl`

**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/JiraServiceImpl.kt:922-965`

**Description:**  
There are two private extension functions that convert `JiraIssue` to `JiraTicketData`: the private `JiraIssue.toTicketData()` at line 922 inside `JiraServiceImpl`, and the internal `JiraIssue.toJiraTicketData()` at line 1862 (package-level). Both do equivalent field mapping with minor differences (the package-level one omits `transitions`, `subtasks`, `linkedIssues`). This duplication increases maintenance risk of divergence.

**Evidence:**
```kotlin
// JiraServiceImpl.kt:922 — private in class
private fun com.workflow.orchestrator.jira.api.dto.JiraIssue.toTicketData(): JiraTicketData { ... }

// JiraServiceImpl.kt:1862 — internal package-level
internal fun com.workflow.orchestrator.jira.api.dto.JiraIssue.toJiraTicketData(): JiraTicketData { ... }
```

**Impact:**  
When the Jira DTO schema changes (e.g., a new field), only one mapping is updated, causing silent data drift in one consumer path.

**Fix sketch:**  
Consolidate to a single `toJiraTicketData(full: Boolean = false)` function with the richer fields gated on `full`.

---

### F-14 [P2] [Quality]: Magic status strings in `PostCommitTransitionLogic.NEEDS_TRANSITION_STATUSES`

**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/PostCommitTransitionHandlerFactory.kt:81`

**Description:**  
`NEEDS_TRANSITION_STATUSES` is a hardcoded set of lowercase status names: `"to do", "open", "new", "backlog", "selected for development"`. Jira is highly configurable; enterprise instances frequently use custom status names. A team using "Awaiting Development" instead of "To Do" will never see the post-commit transition suggestion.

**Evidence:**
```kotlin
// PostCommitTransitionHandlerFactory.kt:81
private val NEEDS_TRANSITION_STATUSES = setOf("to do", "open", "new", "backlog", "selected for development")
```

**Impact:**  
Feature silently non-functional for teams with custom workflows.

**Fix sketch:**  
Expose a configurable "trigger statuses" field in `JiraWorkflowConfigurable` (similar to `ticketTransitionDefaultStartWorkStatusName`) and default it to the current hardcoded list.

---

### F-15 [P2] [Quality]: `BranchNameValidator.isValidBranchName` only checks for ticket-key presence, not overall branch name validity

**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/BranchNameValidator.kt:77-87`

**Description:**  
`isValidBranchName` returns `true` for any string containing a Jira ticket pattern, including names with spaces, `..`, leading `.` or `-`, or ending in `.lock` — all of which Git rejects. The `doValidate` in `StartWorkDialog` does check for spaces, but that only covers the dialog flow; agent-invoked `startWork` has no validation gate before calling Bitbucket's `createBranch`.

**Evidence:**
```kotlin
// BranchNameValidator.kt:77-87
fun isValidBranchName(name: String): Boolean {
    if (name.isBlank()) { ... return false }
    val valid = TICKET_PATTERN.containsMatchIn(name)
    ...
    return valid
}
```

**Impact:**  
Agent can pass an invalid Git branch name (e.g., `feature/PROJ-1 fix whitespace`) to Bitbucket's `createBranch`, which will reject it with a 400 — surfaced as a generic error with no helpful message.

**Fix sketch:**  
Add Git-valid branch name checks: no spaces, no `..`, no leading/trailing `.` or `-`, does not end in `.lock`, no `@{`, no backslash, using git's own rules per `git check-ref-format`.

---

### F-16 [P2] [Performance]: `CurrentWorkSection.showBranchPicker` uses deprecated `runReadAction` synchronously on EDT in a MouseAdapter

**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/CurrentWorkSection.kt:185`

**Description:**  
`showBranchPicker` is called from `MouseAdapter.mouseClicked` (EDT). It calls `runReadAction { GitRepositoryManager.getInstance(project).repositories }`. `runReadAction` is deprecated for IntelliJ Platform 2026.1+ and blocks the EDT while waiting for a read lock. The CLAUDE.md documents this as a known TODO but it is still present in production code.

**Evidence:**
```kotlin
// CurrentWorkSection.kt:185
val repo = runReadAction {
    GitRepositoryManager.getInstance(project).repositories.firstOrNull()
}
```

**Impact:**  
EDT freeze during branch picker open. Duration scales with repository size and current indexing load.

**Fix sketch:**  
Migrate to a coroutine launched from an EDT-attached scope with `readAction { ... }` inside, then build the popup on EDT with the result (as `BranchingService.useExistingBranch` already does).

---

## Summary Table

| ID | Severity | Category | File | Line |
|---|---|---|---|---|
| F-1 | P0 | Security/SSRF | `JiraApiClient.kt` | 303 |
| F-2 | P0 | Security/JQL Injection | `JiraApiClient.kt` | 498 |
| F-3 | P0 | Security/XSS | `TicketDetailPanel.kt` | 908 |
| F-4 | P0 | Security/SSRF | `JiraServiceImpl.kt` | 102 |
| F-5 | P0 | Resource Leak | `ChangelogSection.kt` et al. | 37 |
| F-6 | P0 | Resource Leak | `PostCommitTransitionHandlerFactory.kt` | 42 |
| F-7 | P1 | Correctness | `JiraTaskFunnel.kt` | 70 |
| F-8 | P1 | Correctness | `BranchChangeTicketDetector.kt` | 68 |
| F-9 | P1 | Correctness | `JiraServiceImpl.kt` | 737 |
| F-10 | P1 | Performance/Correctness | `SprintPaginationCache.kt` | 76 |
| F-11 | P1 | Performance | `IssueDetailCache.kt` | 49 |
| F-12 | P1 | Security | `JiraServiceImpl.kt` | 1206 |
| F-13 | P2 | Quality | `JiraServiceImpl.kt` | 922 |
| F-14 | P2 | Quality | `PostCommitTransitionHandlerFactory.kt` | 81 |
| F-15 | P2 | Quality | `BranchNameValidator.kt` | 77 |
| F-16 | P2 | Performance | `CurrentWorkSection.kt` | 185 |

---

## Totals by Severity

| Severity | Count |
|---|---|
| P0 | 6 |
| P1 | 6 |
| P2 | 4 |
| **Total** | **16** |

---

## Top 5 Most Critical

1. **F-1 [P0]** — SSRF via `getRawString` forwarding auth-bearing requests to server-supplied `autoCompleteUrl`. `JiraApiClient.kt:303`.
2. **F-2 [P0]** — JQL injection in `validateTicketKeys` via unsanitized key list concatenation. `JiraApiClient.kt:498`.
3. **F-3 [P0]** — Unescaped Jira summary rendered in Swing HTML context (XSS). `TicketDetailPanel.kt:908`, `TicketListCellRenderer.kt:47`.
4. **F-4 [P0]** — No URL scheme validation on Jira base URL; token exfiltration via file:/ldap: SSRF. `JiraServiceImpl.kt:102`.
5. **F-5 [P0]** — Four lazy-loaded section classes allocate `CoroutineScope` but implement no `Disposable` contract — scopes leak on IDE close. `ChangelogSection.kt:37`, `WorklogSection.kt:34`, `DevStatusSection.kt:38`, `LinkedDocsSection.kt:37`.

---

## Files Audited

```
api/JiraApiClient.kt
api/DevStatusFetcher.kt
api/JiraTransitionResponseParser.kt
api/TransitionInputSerializer.kt
api/dto/JiraDtos.kt
api/dto/JiraExtensionDtos.kt
listeners/BranchChangeTicketDetector.kt
listeners/TicketDetectionStartupActivity.kt
model/AttachmentDownloadResult.kt
search/JiraSearchContributorFactory.kt
service/ActiveTicketService.kt
service/AttachmentDownloadService.kt
service/BranchingService.kt
service/BranchNameValidator.kt
service/DismissedBranchStore.kt
service/IssueDetailCache.kt
service/JiraSearchServiceImpl.kt
service/JiraServiceImpl.kt (1877 lines)
service/JiraTicketProviderImpl.kt
service/SprintPaginationCache.kt
service/SprintService.kt
service/TicketKeyCache.kt
service/TicketTransitionServiceImpl.kt
settings/JiraWorkflowConfigurable.kt
tasks/JiraTask.kt
tasks/JiraTaskFunnel.kt
tasks/JiraTaskMapping.kt
tasks/JiraTaskRepository.kt
tasks/JiraTaskRepositoryType.kt
ui/ChangelogSection.kt
ui/CollapsibleSection.kt
ui/CurrentWorkChipRenderer.kt
ui/CurrentWorkSection.kt
ui/DevStatusSection.kt
ui/LinkedDocsSection.kt
ui/PermissionGate.kt
ui/QuickCommentPanel.kt
ui/SavedFiltersSection.kt
ui/SprintDashboardPanel.kt
ui/SprintTabProvider.kt
ui/SprintTimeBar.kt
ui/StartWorkDialog.kt
ui/TicketDetailPanel.kt
ui/TicketDetectionPopup.kt
ui/TicketDetectionPresenter.kt
ui/TicketListCellRenderer.kt
ui/TicketTransitionDialog.kt
ui/TransitionDialogOpenerImpl.kt
ui/widgets/EntityPickerWidgets.kt
ui/widgets/FieldWidget.kt
ui/widgets/FieldWidgetFactory.kt
ui/widgets/OptionWidgets.kt
ui/widgets/SearchableChooser.kt
ui/widgets/SimpleWidgets.kt
ui/WorklogSection.kt
vcs/PostCommitTransitionHandlerFactory.kt
vcs/TimeTrackingCheckinHandlerFactory.kt
workflow/TransitionMappingStore.kt
```

---

## Verdict

**The `:jira` module is NOT production-ready for enterprise deployment.** It has 4 P0 security findings (2 SSRF vectors, 1 JQL injection, 1 XSS via unescaped Jira content in Swing HTML context) and 2 P0 resource leak findings (coroutine scopes detached from platform lifecycle). The SSRF combination of F-1 + F-4 together form a complete token exfiltration path exploitable by a compromised Jira server or modified settings. These must be resolved before the plugin handles production PATs.
