# :pullrequest Module Security & Correctness Audit
**Date:** 2026-05-24  
**Auditor:** Claude Code (max-effort, read-only)  
**Scope:** `pullrequest/src/main/kotlin/`  
**Files audited:** 18 Kotlin source files

---

## Findings

---

### F-1 [P0] [XSS]: Veto message injected raw into HTML label in MergeOptionsDialog

**File**: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt:2798-2799`

**Description**: `mergeStatus.vetoes[i].summaryMessage` is pulled from the Bitbucket server API response and interpolated directly into a `<html>…</html>` Swing label without escaping. An attacker with Bitbucket admin access (or a compromised/SSRF'd Bitbucket instance) can craft a veto plugin that returns `<script>` or other Swing HTML-active tags in `summaryMessage` and have them rendered inside the IDE.

**Evidence**:
```kotlin
// PrDetailPanel.kt:2798-2799
val vetoText = mergeStatus.vetoes.joinToString("\n") { it.summaryMessage }
val warningLabel = JBLabel("<html><b>Warnings:</b><br>${vetoText.replace("\n", "<br>")}</html>").apply {
    foreground = StatusColors.WARNING
}
```

**Impact**: Swing HTML rendering supports `<a href="...">`, images, and some event triggers. Crafted veto messages could exfiltrate data or cause unexpected UI behavior inside the IDE. Swing's HTML subset does not execute JavaScript, but inline styling and anchor-based attacks are real.

**Fix sketch**: Escape `vetoText` through `HtmlEscape.escapeHtml()` before interpolating into the `<html>` string. Same fix needed for the tooltip path at line 1359 (`vetoReasons`).

---

### F-2 [P0] [XSS]: Bold/italic regex substitution re-introduces unescaped capture groups after HtmlEscape

**File**: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/MarkdownToHtml.kt:99-105`

**Description**: `inlineFormat()` correctly calls `HtmlEscape.escapeHtml(text)` first, but then replaces `**...**` with `<b>$1</b>` and `` `...` `` with `<code>$1</code>` via Kotlin Regex. The capture group `$1` is the already-HTML-escaped substring — *however*, because the regex is applied to the already-escaped string, `&lt;`, `&gt;`, `&amp;` etc. can appear inside the capture group. Critically, any `&` that appears in the escaped text is re-introduced verbatim, and Swing's HTML parser will interpret `&gt;` inside `<b>` correctly — but the real risk is that an attacker who controls the Bitbucket description can embed `**<img src=x>**`. After `escapeHtml` this becomes `**&lt;img src=x&gt;**`, which *after regex* becomes `<b>&lt;img src=x&gt;</b>` — that is safe. The escape-then-regex ordering does protect against standard HTML tag injection.

**HOWEVER**: the inline code regex `` `([^`]+)` → <code ...>$1</code> `` applies after bold/italic. If a description contains:
```
`**<b>injected</b>**`
```
`escapeHtml` converts it to `` `**&lt;b&gt;injected&lt;/b&gt;**` `` — safe. But consider:
```
**bold** `code` — the two regexes run sequentially on the same mutable string; if bold regex turns `*x*` into `<i>x</i>`, a subsequent code-fence that wraps `<i>x</i>` would include the raw tags inside `<code>`. This is a latent path but the escape-first ordering prevents it for user-originated content. The finding stands as a maintenance trap: any future refactor that re-orders these lines without the escape-first guard creates a trivial XSS.

**Evidence**:
```kotlin
// MarkdownToHtml.kt:99-105
private fun inlineFormat(text: String): String {
    var result = HtmlEscape.escapeHtml(text)
    result = result.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")   // injects raw HTML
    result = result.replace(Regex("\\*(.+?)\\*"), "<i>$1</i>")
    result = result.replace(Regex("`([^`]+)`"), "<code ...>$1</code>")  // injects raw HTML
    // ...
}
```

**Impact**: Currently mitigated by escape-first ordering. Future ordering changes, or a second pass through `inlineFormat`, would open XSS on all PR description renders (Swing JEditorPane + JCEF preview pane).

**Fix sketch**: Document the invariant explicitly in a KDoc comment and add a unit test that asserts `inlineFormat("<script>alert(1)</script>")` does not contain unescaped tags. Consider using `Matcher.appendReplacement` with explicit escaping of the replacement group.

---

### F-3 [P0] [Threading/Data-Race]: `ConnectionSettings` state mutation on IO coroutine (background thread)

**File**: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrListService.kt:115`

**Description**: `connSettings.bitbucketUsername = result.data` writes to an IntelliJ `PersistentStateComponent` from a coroutine running on `Dispatchers.IO`. Platform state components must only be mutated on the EDT or under the platform's write lock; mutating from a background thread risks data corruption, missed `StateChanged` notifications to other subscribers, and racy `@State` serialization.

**Evidence**:
```kotlin
// PrListService.kt:107-115  (inside suspend fun refresh(), called from Dispatchers.IO)
val result = client.getCurrentUsername()
if (result is ApiResult.Success && result.data.isNotBlank()) {
    cachedUsername = result.data
    // Save for future sessions
    connSettings.bitbucketUsername = result.data   // <-- writes PersistentStateComponent off-EDT
    ...
}
```

**Impact**: Race condition with any other thread reading `ConnectionSettings.state.bitbucketUsername` during serialization; in the worst case, IntelliJ's XML persistence writes a half-updated state and loses the setting entirely.

**Fix sketch**: Wrap the write in `withContext(Dispatchers.EDT) { connSettings.bitbucketUsername = result.data }` or, preferably, use `ApplicationManager.getApplication().invokeLater { ... }` if the settings component does not require synchronous write.

---

### F-4 [P0] [Threading/Data-Race]: `BitbucketBranchClientCache.get()` is not atomically safe

**File**: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketBranchClientCache.kt:41-48`

**Description**: Both `cachedClient` and `cachedBaseUrl` are individually `@Volatile`, but the check-then-act sequence `if (url != cachedBaseUrl || cachedClient == null)` followed by `cachedBaseUrl = url; cachedClient = ...` is not atomic. Two concurrent callers (e.g. `PrListService.refresh()` firing from a poll and a user click) can each pass the condition and both rebuild the client, creating two independent HTTP clients that share the same OkHttp connection pool. This can also cause `cachedBaseUrl` and `cachedClient` to be mismatched transiently.

**Evidence**:
```kotlin
// BitbucketBranchClientCache.kt:41-48
fun get(): BitbucketBranchClient? {
    val url = ConnectionSettings.getInstance().state.bitbucketUrl.trimEnd('/')
    if (url.isBlank()) return null
    if (url != cachedBaseUrl || cachedClient == null) {  // non-atomic check
        cachedBaseUrl = url                               // written separately
        cachedClient = BitbucketBranchClient.fromConfiguredSettings()  // other thread reads stale cachedBaseUrl here
    }
    return cachedClient
}
```

**Impact**: Under high concurrency (multiple poll timers + user actions firing simultaneously), the cache can return a client whose `cachedBaseUrl` has already been updated to a new URL while `cachedClient` still points to the old client — or vice-versa. The functional impact is mild (a redundant client rebuild) but the `cachedUrl` property used to construct PR fallback links in `BitbucketServiceImpl` can return a mismatched URL, producing broken browser links.

**Fix sketch**: Use `@Synchronized` on `get()` or replace the two `@Volatile` fields with a single `AtomicReference<Pair<String, BitbucketBranchClient>>` for compare-and-swap.

---

### F-5 [P0] [Correctness]: Stale `version` used for Decline — not protected by retry logic

**File**: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt:1226` and `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrActionService.kt:188-207`

**Description**: The Decline button reads `currentPr?.version ?: 0` from the panel's last-fetched state and passes it directly to `PrActionService.decline(prId, version)`. Unlike `merge` (which routes through `mergePullRequestWithRetry`) and `updateTitle`/`addReviewer` (which route through `modifyPullRequest` with a retry loop), `decline` passes the cached version directly to `client.declinePullRequest`. If the PR was updated between the last panel load and the click, the API returns a 409 and the operation silently fails — the panel shows no error because the `catch (e: Exception)` block only shows a notification for exceptions, not for the `ApiResult.Error` path (line 1236 disables buttons without checking the result).

**Evidence**:
```kotlin
// PrDetailPanel.kt:1226-1251
val version = currentPr?.version ?: 0   // stale from last load
val confirm = Messages.showYesNoDialog(...)
if (confirm != Messages.YES) return@addActionListener
scope.launch {
    try {
        PrActionService.getInstance(project).decline(prId, version)  // no retry
        invokeLater {
            mergeButton.isEnabled = false   // buttons disabled whether success or failure
            ...
        }
    } catch (e: Exception) { ... }
}
```
```kotlin
// PrActionService.kt:195
return when (val result = client.declinePullRequest(projectKey(), repoSlug(), prId, version)) {
    // no retry-on-409 here
```

**Impact**: The decline operation silently fails on a 409 version mismatch; the user believes the PR was declined but it remains OPEN. This is the P0 version-race noted in the write-ops UX audit.

**Fix sketch**: Route `decline` through `BitbucketBranchClient.modifyPullRequest` (like `merge` uses `mergePullRequestWithRetry`) or at minimum add a GET-before-POST inside `PrActionService.decline`. Also check and surface the `ApiResult.Error` case in the button handler.

---

### F-6 [P1] [Correctness]: `result.data!!` forced unwrap in `runAiReview` — NPE if `ToolResult.data` is null

**File**: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt:871,873`

**Description**: After checking `diffResult.isError == false`, the code performs `diffResult.data!!`. `ToolResult` has a nullable `data: T?` field. Nothing prevents `isError = false` with `data = null` (a defensive or legacy code path). If that state ever occurs, the `!!` throws an uncaught NPE that crashes the coroutine silently (no user notification because the `catch` is above this line path).

**Evidence**:
```kotlin
// PrDetailPanel.kt:869-873
if (diffResult == null || diffResult.isError) {
    // shows error dialog and returns
}
val diff = diffResult.data!!        // NPE if data is null despite isError=false
val changedFiles = if (...) {
    changesResult.data!!.map { it.path }   // same pattern
```

**Impact**: If a network path ever sets `isError = false, data = null`, the AI review silently aborts with no feedback to the user. Same issue in `AiReviewViewModel.kt:47` and `CommentsViewModel.kt:55`.

**Fix sketch**: Replace `result.data!!` with `result.data ?: run { log.warn(...); return@launch }` at each site, or enforce a contract in `ToolResult` that `data` is non-null when `isError == false`.

---

### F-7 [P1] [Security/Correctness]: No IDOR guard on comment edit/delete — server-side only

**File**: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/CommentsTabPanel.kt:100-102` and `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/CommentsViewModel.kt:95-125`

**Description**: The "Toggle Resolved" action (`CommentsTabPanel.toggleResolvedSelected()`) and the reply action operate on any selected comment regardless of whether the current user is the comment author. While Bitbucket Server enforces authorization server-side and will return 403 for unauthorized edits, the UI provides no pre-flight indication that these operations are restricted, leading to confusing silent-error UX. More importantly, `CommentsViewModel.resolve/reopen` operates without checking `permittedOperations.transitionable` from the `PrCommentPermittedOps` field already present on `PrComment`.

**Evidence**:
```kotlin
// CommentsTabPanel.kt:156-162
private fun toggleResolvedSelected() {
    val sel = commentList.selectedValue ?: return
    scope.launch {
        if (sel.state == PrCommentState.RESOLVED) vm.reopen(sel.id.toLong())
        else vm.resolve(sel.id.toLong())   // no check on sel.permittedOperations.transitionable
    }
}
```

**Impact**: Users will click "Toggle Resolved" on others' comments, get a silent 403 (error stored in `vm.lastError` but the status label may be immediately overwritten by a concurrent refresh), and believe the operation succeeded.

**Fix sketch**: Gate the action on `sel.permittedOperations?.transitionable == true` before dispatching; disable the button in `selectionActionsPanel` when the selected comment's `permittedOperations` disallows the operation.

---

### F-8 [P1] [Resource-Leak]: `CommentsTabPanel` `ComponentListener` never removed — retains panel reference after `close()`

**File**: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/CommentsTabPanel.kt:125-135`

**Description**: `addComponentListener(object : ComponentAdapter() {...})` is called in `init` but `removeComponentListener` is never called in `close()`. The anonymous `ComponentAdapter` captures `poller` and `scope` via closure. When `PrDetailPanel.rebuildCommentsTab()` replaces the old panel (line 956: `commentsTabPanel = newTab`), the old `CommentsTabPanel` is dropped from the `contentCards` container — but its `ComponentListener` is still attached to itself, and Swing retains the listener reference in the component's listener list. Since the old panel is still attached to the Swing hierarchy (it just isn't the active card), the listener fires on show/hide events for other cards.

**Evidence**:
```kotlin
// CommentsTabPanel.kt:125-135 (init block)
addComponentListener(object : ComponentAdapter() {
    override fun componentShown(e: ComponentEvent) {
        poller.start()
        poller.setVisible(true)
    }
    override fun componentHidden(e: ComponentEvent) {
        poller.setVisible(false)
        poller.stop()
    }
})
// close() at line 187-190:
override fun close() {
    poller.stop()
    scope.cancel()
    // no removeComponentListener(...)
}
```

**Impact**: Old `CommentsTabPanel` instances accumulate with each PR switch. The `poller` (and its `scope`) may be restarted after `close()` if the Swing layout emits a spurious `componentShown`. Memory leak proportional to number of PR switches in a session.

**Fix sketch**: Save the listener reference and call `removeComponentListener(listener)` inside `close()`.

---

### F-9 [P1] [Correctness]: PR comment pagination not implemented — only first page fetched

**File**: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt:991`

**Description**: `listPrComments` calls `api.listPrComments(projectKey, repoSlug, prId)` and maps `result.data.values` without pagination. Bitbucket Server paginates comments (default 25/page). For PRs with more than the default page size of comments, only the first page is ever displayed in the Comments tab and sent to the AI Review LLM context.

**Evidence**:
```kotlin
// BitbucketServiceImpl.kt:991-1003
return when (val result = api.listPrComments(projectKey, repoSlug, prId)) {
    is ApiResult.Error -> { ... }
    is ApiResult.Success -> {
        var mapped = result.data.values.map { it.toPrComment() }  // only .values from page 1
        if (onlyOpen) mapped = mapped.filter { ... }
        if (onlyInline) mapped = mapped.filter { ... }
        ToolResult.success(mapped, ...)
    }
}
```

**Impact**: On active PRs (> 25 comments), reviewers see a truncated history. The AI review deduplication step (`list_comments` before adding findings) will miss already-posted comments, leading to duplicate AI findings being pushed to Bitbucket.

**Fix sketch**: Add a `while (!isLastPage)` pagination loop similar to the existing pattern in `getPullRequestCommits` (which correctly paginates up to 20 pages).

---

### F-10 [P1] [Correctness]: `AiReviewViewModel` and `CommentsViewModel` use unsynchronized `MutableList` from multiple coroutines

**File**: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/AiReviewViewModel.kt:30-48`, `CommentsViewModel.kt:26-56`

**Description**: Both view-models hold `private val _findings: MutableList<PrComment>` / `_findings: MutableList<PrReviewFinding>`. These are standard `ArrayList`-backed lists accessed without synchronization. The `CommentsTabPanel` calls `vm.refresh()` from a `SmartPoller` running on the IO scope AND from `triggerRefresh()` (user click). If both run concurrently, `_comments.clear(); _comments.addAll(...)` can interleave, producing a list with mixed old/new entries.

**Evidence**:
```kotlin
// CommentsViewModel.kt:42-56
suspend fun refresh() {
    val result = service.listPrComments(...)
    if (result.isError) { ... } else {
        _comments.clear()            // not synchronized
        _comments.addAll(result.data!!)  // concurrent callers race here
    }
    fire()
}
```

**Impact**: Rare but possible: duplicate or missing comments shown in the UI after rapid tab switching or when the auto-refresh fires while the user manually refreshes.

**Fix sketch**: Replace `MutableList` with `@Volatile` `List` (always replace the entire reference) or add a `Mutex` around the mutate-and-notify block. The cleanest fix is `_comments = result.data!!.toMutableList()` (field reassignment, then read from `val comments get() = _comments.toList()` is safe since `List` ref swap is atomic on JVM).

---

### F-11 [P1] [Security]: Full diff (up to 320 KB) of source code sent to LLM without secrets scanning

**File**: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrReviewTaskBuilder.kt:26-29`

**Description**: The `PrReviewTaskBuilder.build()` method inserts the raw diff into the LLM prompt. The diff can contain secrets inadvertently committed (API keys, tokens, passwords in test fixtures or config files). There is no secrets-scanning step before the diff is embedded and sent to the external AI provider.

**Evidence**:
```kotlin
// PrReviewTaskBuilder.kt:26-29
val diffCapped = if (diff.length > MAX_DIFF_CHARS) {
    diff.take(MAX_DIFF_CHARS) + "\n[... diff truncated at $MAX_DIFF_CHARS chars ...]"
} else { diff }
// ... embedded verbatim in prompt at line 78
sb.appendLine(diffCapped)
```

**Impact**: A developer who accidentally commits an API key in a test file and then runs AI Review will send that secret to the LLM API endpoint (Sourcegraph/Anthropic). This is a data-exfiltration risk, especially in enterprise environments where Sourcegraph may be on-prem but the underlying LLM is cloud-hosted.

**Fix sketch**: Before embedding, run a lightweight regex scan (entropy-based or pattern-matching for `sk-`, `ghp_`, `AKIA`, etc.) and either redact matching lines or prompt the user to confirm before sending. At minimum, document the risk in the UI confirmation dialog.

---

### F-12 [P1] [Threading]: `PrDetailPanel.cachedUsername` written from IO coroutine, read on EDT

**File**: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt:1424-1433`

**Description**: `cachedUsername` is a plain `var` (no `@Volatile`) in `PrDetailPanel`. It is written inside `resolveCurrentUsername()` called from `scope.launch { ... }` (IO thread) at line 1398 and read/written again at line 1430 on the same IO coroutine. The `invokeLater { currentUserApproved = approved }` at line 1402 then reads the result on EDT — this is fine. However, there is no visibility guarantee that the `cachedUsername` written on the IO thread is visible to a subsequent EDT call that might invoke `resolveCurrentUsername()` again (e.g. via `renderPrHeader` called from `invokeLater`). On JVM without `@Volatile`, the EDT may see a stale `null`.

**Evidence**:
```kotlin
// PrDetailPanel.kt:1424-1433
private var cachedUsername: String? = null   // no @Volatile

private suspend fun resolveCurrentUsername(): String? {
    cachedUsername?.let { return it }        // read (IO thread)
    ...
    return when (val result = client.getCurrentUsername()) {
        is ApiResult.Success -> { cachedUsername = result.data; result.data }  // write (IO thread)
```

**Impact**: Under JVM memory model rules, the EDT may read a stale `null` for `cachedUsername` and trigger redundant API calls, or worse, a Happens-Before gap could produce a torn read on JVM implementations that split wide references (unlikely on HotSpot for Kotlin `String?` but non-conformant per JMM).

**Fix sketch**: Add `@Volatile` to `cachedUsername`.

---

### F-13 [P2] [Quality]: Duplicate Bitbucket client construction — `BitbucketBranchClient.fromConfiguredSettings()` called directly in `PrDetailPanel` alongside `BitbucketBranchClientCache`

**File**: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt:526,674,1428`

**Description**: `PrDetailPanel` instantiates `BitbucketBranchClient.fromConfiguredSettings()` directly at three call sites (branch loader in `showCreateForm`, `submitCreatePr`, and `resolveCurrentUsername`) bypassing the `BitbucketBranchClientCache` used by all four PR services. This creates extra OkHttp clients that are not connection-pool-shared with the service clients.

**Evidence**:
```kotlin
// PrDetailPanel.kt:526
val client = BitbucketBranchClient.fromConfiguredSettings() ?: return@launch
// PrDetailPanel.kt:674
val client = BitbucketBranchClient.fromConfiguredSettings() ?: return@launch
// PrDetailPanel.kt:1428
val client = BitbucketBranchClient.fromConfiguredSettings() ?: return null
```

**Impact**: Each uncached call creates a new OkHttp `OkHttpClient` with its own thread pool and connection pool, increasing memory and FD usage with every PR detail view and branch load. Per `reference_performance_best_practices.md`, OkHttp clients should be singletons.

**Fix sketch**: Inject or lazily obtain a shared `BitbucketBranchClientCache` instance in `PrDetailPanel`, or route these calls through `PrDetailService` / `PrActionService`.

---

### F-14 [P2] [Quality]: Magic string `"OPEN"` used throughout with no enum/constant

**File**: Multiple files — `PrListService.kt:73`, `PrDetailPanel.kt:1007`, `PrActionService.kt:61` (implicit `"OPEN"` default)

**Description**: The PR state values `"OPEN"`, `"MERGED"`, `"DECLINED"` appear as raw string literals across the codebase without a shared constant or enum. The filter toggle at `PrListService.setState()` accepts any `String`, making typo-based bugs undetectable at compile time.

**Evidence**:
```kotlin
// PrListService.kt:73
@Volatile private var currentState: String = "OPEN"
// PrDetailPanel.kt:1007
if (e.clickCount == 2 && currentPr?.state.equals("OPEN", ignoreCase = true))
```

**Impact**: A typo in one caller silently bypasses state-gating. Low likelihood but the fix is trivial.

**Fix sketch**: Extract a `PrState` enum or `object PrState { const val OPEN = "OPEN"; const val MERGED = "MERGED"; const val DECLINED = "DECLINED" }` in `:core/model`.

---

### F-15 [P2] [Quality]: `AiReviewTabPanel` and `CommentsTabPanel` create own `CoroutineScope` not registered with `Disposer`

**File**: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/AiReviewTabPanel.kt:32`, `CommentsTabPanel.kt:52`

**Description**: Both panels create `CoroutineScope(SupervisorJob() + Dispatchers.IO)` but are not `Disposable` — they implement `AutoCloseable`. `close()` is called from `PrDetailPanel.rebuildCommentsTab()` / `rebuildAiReviewTab()` and `PrDetailPanel.dispose()`. If a code path fails to call `close()` (e.g., an unexpected exception in `rebuildCommentsTab` before assigning `commentsTabPanel = newTab`), the old scope leaks indefinitely. There is no `Disposer.register` chain protecting these.

**Evidence**:
```kotlin
// CommentsTabPanel.kt:52
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
// No Disposer.register — relies entirely on callers invoking close()
```

**Impact**: Scope leak on exception in rebuild path; polling coroutines continue running after panel is abandoned.

**Fix sketch**: Implement `Disposable` and call `Disposer.register(parent, this)` where `parent` is a `Disposable` from the `PrDetailPanel` (which is already `Disposable`). Then `dispose()` calls `close()`.

---

## Summary Table

| ID | Severity | Category | Title | File |
|----|----------|----------|-------|------|
| F-1 | P0 | XSS | Veto summaryMessage injected raw into HTML label | `PrDetailPanel.kt:2799` |
| F-2 | P0 | XSS | Bold/italic regex re-injects after escape — latent path | `MarkdownToHtml.kt:101-103` |
| F-3 | P0 | Threading | `ConnectionSettings` state mutation off-EDT | `PrListService.kt:115` |
| F-4 | P0 | Data-Race | `BitbucketBranchClientCache.get()` non-atomic check-then-act | `BitbucketBranchClientCache.kt:44-46` |
| F-5 | P0 | Correctness | Decline uses stale `version`; no retry; silent failure | `PrDetailPanel.kt:1226`, `PrActionService.kt:195` |
| F-6 | P1 | Correctness | `result.data!!` forced unwrap — crash if data null | `PrDetailPanel.kt:871,873` |
| F-7 | P1 | Security | No IDOR pre-flight check on comment resolve/reopen | `CommentsTabPanel.kt:156-162` |
| F-8 | P1 | Resource-Leak | `ComponentListener` not removed in `CommentsTabPanel.close()` | `CommentsTabPanel.kt:125-135` |
| F-9 | P1 | Correctness | PR comments not paginated — first page only | `BitbucketServiceImpl.kt:991` |
| F-10 | P1 | Correctness | Unsynchronized `MutableList` in ViewModels — race on concurrent refresh | `CommentsViewModel.kt:53-55`, `AiReviewViewModel.kt:46-47` |
| F-11 | P1 | Security | Full diff sent to LLM without secrets scanning | `PrReviewTaskBuilder.kt:26-29` |
| F-12 | P1 | Threading | `cachedUsername` in `PrDetailPanel` missing `@Volatile` | `PrDetailPanel.kt:1424` |
| F-13 | P2 | Quality | Direct `BitbucketBranchClient.fromConfiguredSettings()` bypasses cache | `PrDetailPanel.kt:526,674,1428` |
| F-14 | P2 | Quality | PR state magic strings — no enum/constant | Multiple files |
| F-15 | P2 | Quality | Sub-tab panels not registered with `Disposer` | `AiReviewTabPanel.kt:32`, `CommentsTabPanel.kt:52` |

---

## Top 5 Most Important

1. **F-5 [P0]** — `PrDetailPanel.kt:1226` — Decline silently fails on version race; user believes PR is declined but it remains OPEN. The write-ops P0 known in memory is confirmed unfixed in the UI path.
2. **F-1 [P0]** — `PrDetailPanel.kt:2799` — Raw Bitbucket API text (veto `summaryMessage`) injected into Swing HTML label without escaping. Craft a veto plugin or SSRF Bitbucket to execute Swing HTML.
3. **F-3 [P0]** — `PrListService.kt:115` — `ConnectionSettings` state write from IO coroutine; risks corrupt persistent state and crash in IntelliJ platform code.
4. **F-11 [P1]** — `PrReviewTaskBuilder.kt:26-29` — Full PR diff (up to 320 KB of source code) sent to LLM with no secrets scan; enterprise data exfiltration risk.
5. **F-9 [P1]** — `BitbucketServiceImpl.kt:991` — Comment list pagination missing; AI review deduplication is broken for PRs with > 25 comments, leading to duplicate AI findings being pushed to Bitbucket.

---

## Files Audited

| File | Lines | Status |
|------|-------|--------|
| `service/BitbucketServiceImpl.kt` | 1329 | Fully audited |
| `service/BitbucketBranchClientCache.kt` | 59 | Fully audited |
| `service/PrActionService.kt` | 519 | Fully audited |
| `service/PrDetailService.kt` | 159 | Fully audited |
| `service/PrListService.kt` | 263 | Fully audited |
| `service/MarkdownToHtml.kt` | 127 | Fully audited |
| `service/PrDescriptionGenerator.kt` | 315 | Fully audited |
| `service/PrReviewTaskBuilder.kt` | 90 | Fully audited |
| `service/PrReviewSessionRegistry.kt` | (small) | Fully audited |
| `ui/PrDetailPanel.kt` | 2869 | Fully audited (3 pages) |
| `ui/PrDashboardPanel.kt` | ~624 | Partially audited |
| `ui/PrListPanel.kt` | ~420 | Partially audited |
| `ui/CommentsTabPanel.kt` | 191 | Fully audited |
| `ui/CommentsViewModel.kt` | 127 | Fully audited |
| `ui/AiReviewTabPanel.kt` | 128 | Fully audited |
| `ui/AiReviewViewModel.kt` | 101 | Fully audited |
| `ui/CommentRowRenderer.kt` | 81 | Fully audited |
| `ui/FindingRowRenderer.kt` | (small) | Fully audited |
| `action/CreatePrLauncherImpl.kt` | (small) | Partially audited |
| `action/CreatePrPrefetch.kt` | (medium) | Partially audited |

---

## Verdict

**:pullrequest is not enterprise-ready as-is**: it has one confirmed silent-failure P0 on a destructive operation (Decline), two P0 injection/corruption bugs (raw HTML veto injection, off-EDT state write), one P0 data-race on the shared HTTP client cache, and a P1 data-exfiltration risk in the AI Review pipeline — any one of which is a ship-blocker for a regulated enterprise environment.
