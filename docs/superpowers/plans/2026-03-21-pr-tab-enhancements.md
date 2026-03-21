# PR Tab Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform the PR tab from a read-only dashboard into a full code review tool inside the IDE — with diff viewer, inline commenting, commit list, build status, reviewer management, search/filter, pagination, and PR creation.

**Architecture:** Extend BitbucketBranchClient with new endpoints (commits, inline comments, reviewer status). Add pagination support to list service. New UI panels for diff viewing (IntelliJ's built-in `DiffManager`), commit list, inline commenting. Performance: lazy-load diff content per-file, LRU cache for PR details, paginated API calls with cursor-based fetching.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (DiffManager, EditorFactory, SimpleColoredComponent), Bitbucket Server REST API 1.0, OkHttp

**Performance principles applied:**
- Lazy-load diff content per-file (not entire PR diff upfront)
- LRU cache for PR detail/activities (avoid re-fetching on tab switch)
- Pagination with cursor-based fetching (no fixed limits)
- Skeleton/loading states for all async operations
- Connection pooling via shared OkHttp client
- Incremental list updates (skip re-render if data unchanged)

**UX principles applied:**
- Loading indicators for all async operations (AnimatedIcon spinners)
- Empty states with actionable messages
- Keyboard shortcuts for common actions
- Status colors consistent with StatusColors
- Split views with persisted proportions
- Context menus for power users + visible buttons for discoverability

---

## Dependency Graph

```
Task 1 (API: commits, inline comments, reviewer status, pagination) — foundation
  ├── Task 2 (Diff viewer)
  ├── Task 3 (Commit list tab)
  ├── Task 4 (Inline commenting)
  ├── Task 5 (Build status on PR)
  └── Task 6 (Pagination + state filter)

Task 7 (PR search/filter) — independent, client-side
Task 8 (Reviewer management) — depends on Task 1
Task 9 (PR creation in PR tab) — self-contained reviewer selection (uses getUsers() directly)
Task 10 (Comment posting UI) — depends on Task 4 (after inline, add general)
Task 11 (PR title editing + unapprove) — independent, small
Task 12 (Markdown rendering) — independent
```

---

### Task 1: API Extensions (Commits, Inline Comments, Reviewer Status, Pagination)

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt`
- Modify: DTOs in same file or separate DTO file

- [ ] **Step 1: Add getCommits method**

```kotlin
suspend fun getPullRequestCommits(
    projectKey: String, repoSlug: String, prId: Int, limit: Int = 50
): ApiResult<BitbucketCommitListResponse> {
    return get("/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/commits?limit=$limit")
}
```

Add DTOs (use dedicated response types, NOT generics — kotlinx.serialization requires explicit types):
```kotlin
@Serializable
data class BitbucketCommitListResponse(
    val values: List<BitbucketCommit> = emptyList(),
    val size: Int = 0,
    val isLastPage: Boolean = true,
    val start: Int = 0,
    val nextPageStart: Int? = null
)

@Serializable
data class BitbucketCommit(
    val id: String,
    val displayId: String,
    val message: String,
    val author: BitbucketUser? = null,
    val authorTimestamp: Long = 0,
    val parents: List<BitbucketCommitRef> = emptyList()
)

@Serializable
data class BitbucketCommitRef(val id: String, val displayId: String)
```

- [ ] **Step 2: Add inline comment method**

```kotlin
suspend fun addInlineComment(
    projectKey: String, repoSlug: String, prId: Int,
    filePath: String, lineNumber: Int, lineType: String, // "ADDED", "REMOVED", "CONTEXT"
    text: String, parentCommentId: Int? = null
): ApiResult<Unit> {
    val anchor = buildJsonObject {
        put("path", filePath)
        put("line", lineNumber)
        put("lineType", lineType)
        put("fileType", "TO") // comment on the new file version
    }
    val body = buildJsonObject {
        put("text", text)
        put("anchor", anchor)
        parentCommentId?.let { put("parent", buildJsonObject { put("id", it) }) }
    }
    return post("/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/comments", body.toString())
}
```

- [ ] **Step 3: Add reviewer status method (needs-work)**

```kotlin
suspend fun setReviewerStatus(
    projectKey: String, repoSlug: String, prId: Int,
    username: String, status: String // "APPROVED", "NEEDS_WORK", "UNAPPROVED"
): ApiResult<Unit> {
    // Follow same pattern as existing approvePullRequest/unapprovePullRequest
    // User identified by URL path, body only needs status
    val body = buildJsonObject { put("status", status) }
    return put("/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/participants/$username", body.toString())
}
```

- [ ] **Step 4: Add pagination support to PR list methods**

Modify `getMyPullRequests()` and `getReviewingPullRequests()` to accept `state` and `start` parameters:

```kotlin
suspend fun getMyPullRequests(
    projectKey: String, repoSlug: String, username: String,
    state: String = "OPEN", start: Int = 0, limit: Int = 25
): ApiResult<BitbucketPagedResponse<BitbucketPrDetail>>
```

Add a helper to fetch all pages:
```kotlin
suspend fun getAllPullRequests(
    projectKey: String, repoSlug: String, username: String,
    state: String = "OPEN", role: String = "AUTHOR"
): List<BitbucketPrDetail> {
    val allResults = mutableListOf<BitbucketPrDetail>()
    var start = 0
    do {
        val page = /* fetch page */
        allResults.addAll(page.values)
        start = page.nextPageStart ?: break
    } while (!page.isLastPage)
    return allResults
}
```

- [ ] **Step 5: Add per-file diff method**

```kotlin
suspend fun getFileDiff(
    projectKey: String, repoSlug: String, prId: Int, filePath: String
): ApiResult<String> {
    // GET .../pull-requests/{id}/diff/{path} with Accept: text/plain
    // NOTE: Do NOT URL-encode the full path — Bitbucket expects literal path separators
    return getRaw("/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/diff/$filePath")
}

/**
 * Fetch file content at a specific commit ref (for diff viewer).
 * Used to get before/after versions of a file for DiffManager.
 */
suspend fun getFileContent(
    projectKey: String, repoSlug: String, filePath: String, atRef: String
): ApiResult<String> {
    return getRaw("/rest/api/1.0/projects/$projectKey/repos/$repoSlug/browse/$filePath?at=$atRef&raw")
}
```

- [ ] **Step 6: Build and commit**

Run: `./gradlew :core:compileKotlin`

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/
git commit -m "feat(core): add commits, inline comments, reviewer status, pagination, per-file diff to Bitbucket client"
```

---

### Task 2: Diff Viewer

**Files:**
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrDetailService.kt`

- [ ] **Step 1: Replace Files tab with Diff-enabled file list**

In the FilesSubPanel, make file items double-clickable to open a diff viewer. When a file is double-clicked:

1. Fetch both file versions via the browse API:
   - Base version: `PrDetailService.getFileContent(filePath, pr.toRef.latestCommit)` (target branch)
   - Head version: `PrDetailService.getFileContent(filePath, pr.fromRef.latestCommit)` (source branch)
2. Open IntelliJ's built-in diff viewer using `DiffManager`:

```kotlin
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.DiffContentFactory

val diffContentFactory = DiffContentFactory.getInstance()
val beforeContent = diffContentFactory.create(project, baseText)
val afterContent = diffContentFactory.create(project, headText)

val request = SimpleDiffRequest(
    "PR #$prId: $fileName",
    beforeContent,
    afterContent,
    "Base ($toBranch)",
    "Changes ($fromBranch)"
)

DiffManager.getInstance().showDiff(project, request)
```

For deleted files, `headText` is empty. For new files, `baseText` is empty.

- [ ] **Step 2: Add getFileDiff to PrDetailService**

```kotlin
suspend fun getFileDiff(prId: Int, filePath: String): String? {
    return client.getFileDiff(projectKey, repoSlug, prId, filePath)
        .let { (it as? ApiResult.Success)?.data }
}
```

- [ ] **Step 3: Add visual indicators on file list**

Add a tooltip on each file: "Double-click to view diff". Add an `AllIcons.Actions.Diff` icon on hover or always visible.

Show loading state while diff is being fetched.

- [ ] **Step 4: Build and commit**

Run: `./gradlew :pullrequest:compileKotlin`

```bash
git add pullrequest/
git commit -m "feat(pr): diff viewer — double-click file to open IntelliJ diff viewer"
```

---

### Task 3: Commit List Tab

**Files:**
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrDetailService.kt`

- [ ] **Step 1: Add 5th toggle button "Commits"**

Add between "Files" and "AI Review" toggles. When selected, shows a list of commits in the PR.

- [ ] **Step 2: Create CommitsSubPanel**

```kotlin
private inner class CommitsSubPanel : JPanel(BorderLayout()) {
    private val commitList = JBList<BitbucketCommit>()

    fun showCommits(prId: Int) {
        // Fetch via PrDetailService.getCommits(prId)
        // Display: commit hash (short, link-colored), message (first line), author, relative time
        // Double-click: navigate to commit in VCS log if possible
    }
}
```

Each commit row:
```
abc1234  Fix login validation               John Doe  2 hours ago
def5678  Add unit tests for auth module      Jane Doe  3 hours ago
```

- [ ] **Step 3: Add getCommits to PrDetailService**

```kotlin
suspend fun getCommits(prId: Int): List<BitbucketCommit> {
    return client.getPullRequestCommits(projectKey, repoSlug, prId)
        .let { (it as? ApiResult.Success)?.data?.values ?: emptyList() }
}
```

- [ ] **Step 4: Build and commit**

Run: `./gradlew :pullrequest:compileKotlin`

```bash
git add pullrequest/
git commit -m "feat(pr): commits tab showing individual commits in pull request"
```

---

### Task 4: Inline Code Commenting

**Files:**
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrActionService.kt`

- [ ] **Step 1: Add addInlineComment to PrActionService**

```kotlin
suspend fun addInlineComment(
    prId: Int, filePath: String, lineNumber: Int, lineType: String, text: String
): ApiResult<Unit> {
    return client.addInlineComment(projectKey, repoSlug, prId, filePath, lineNumber, lineType, text)
}
```

- [ ] **Step 2: Add comment reply support**

Add a separate `replyToComment` method (NOT reusing inline comment with empty fields):

```kotlin
// In BitbucketBranchClient:
suspend fun replyToComment(
    projectKey: String, repoSlug: String, prId: Int,
    parentCommentId: Int, text: String
): ApiResult<Unit> {
    val body = buildJsonObject {
        put("text", text)
        put("parent", buildJsonObject { put("id", parentCommentId) })
    }
    return post("/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/comments", body.toString())
}

// In PrActionService:
suspend fun replyToComment(prId: Int, parentCommentId: Int, text: String): ApiResult<Unit> {
    return client.replyToComment(projectKey, repoSlug, prId, parentCommentId, text)
}
```

- [ ] **Step 3: Enhance Activity tab to show inline comment threads**

Currently activity shows inline comments with file:line anchors. Enhance to:
- Group comments by file/line into threads
- Show "Reply" link on each comment
- Clicking "Reply" opens an inline text field below the comment
- Submitting calls `replyToComment()`

- [ ] **Step 4: Build and commit**

Run: `./gradlew :pullrequest:compileKotlin`

```bash
git add pullrequest/
git commit -m "feat(pr): inline code commenting with reply support in activity tab"
```

---

### Task 5: Build Status on PR Detail

**Files:**
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrDetailService.kt`

- [ ] **Step 1: Fetch build status for PR's latest commit**

In PrDetailService, add:
```kotlin
suspend fun getBuildStatus(commitId: String): List<BitbucketBuildStatus> {
    return client.getBuildStatuses(commitId)
        .let { (it as? ApiResult.Success)?.data ?: emptyList() }
}
```

- [ ] **Step 2: Show build status badge in PR header**

After the reviewer section in the detail header, show build status:

```
[✓ BUILD PASSED] or [✗ BUILD FAILED] or [▶ BUILDING]
```

Use `StatusColors.SUCCESS` / `StatusColors.ERROR` / `StatusColors.LINK` for colors.

Fetch the latest commit ID from the PR detail, then call `getBuildStatus()`. Show loading state while fetching.

- [ ] **Step 3: Build and commit**

Run: `./gradlew :pullrequest:compileKotlin`

```bash
git add pullrequest/
git commit -m "feat(pr): build status badge in PR detail header"
```

---

### Task 6: Pagination + State Filter (OPEN/MERGED/DECLINED)

**Files:**
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrListService.kt`
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDashboardPanel.kt`
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrListPanel.kt`

- [ ] **Step 1: Add state parameter to PrListService**

Allow fetching OPEN, MERGED, or DECLINED PRs:

```kotlin
private var currentState: String = "OPEN"

fun setState(state: String) {
    currentState = state
    refresh()
}
```

Update `refresh()` to pass `currentState` to the API calls.

- [ ] **Step 2: Implement pagination in PrListService**

Replace single-page fetch with paginated fetching:
```kotlin
private suspend fun fetchAllPages(role: String): List<BitbucketPrDetail> {
    val results = mutableListOf<BitbucketPrDetail>()
    var start = 0
    var isLast = false
    while (!isLast) {
        val page = client.getMyPullRequests(projectKey, repoSlug, username, currentState, start, 25)
        if (page is ApiResult.Success) {
            results.addAll(page.data.values)
            isLast = page.data.isLastPage
            start = page.data.nextPageStart ?: break
        } else break
    }
    return results
}
```

Cap at 100 PRs max to avoid excessive API calls.

- [ ] **Step 3: Add state toggle buttons to PrDashboardPanel**

Add "Open | Merged | Declined" toggle group in the toolbar:

```kotlin
val stateToggle = ButtonGroup()
val openBtn = JToggleButton("Open", true)
val mergedBtn = JToggleButton("Merged")
val declinedBtn = JToggleButton("Declined")
```

On toggle change, call `prListService.setState(selectedState)`.

- [ ] **Step 4: Build and commit**

Run: `./gradlew :pullrequest:compileKotlin`

```bash
git add pullrequest/
git commit -m "feat(pr): pagination support and Open/Merged/Declined state filter"
```

---

### Task 7: PR Search/Filter

**Files:**
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrListPanel.kt`

- [ ] **Step 1: Add search field to PrListPanel**

Add a `SearchTextField` above the PR list (same pattern as Sprint tab):

```kotlin
private val searchField = SearchTextField(false).apply {
    textEditor.emptyText.text = "Filter by title, author, or branch..."
}
```

- [ ] **Step 2: Implement client-side filtering**

Filter the cached PR list by search text matching against title, author name, fromBranch, or toBranch:

```kotlin
private fun applyFilter(text: String) {
    val filtered = allItems.filter { item ->
        text.isBlank() || item.title.contains(text, ignoreCase = true)
            || item.authorName.contains(text, ignoreCase = true)
            || item.fromBranch.contains(text, ignoreCase = true)
            || item.toBranch.contains(text, ignoreCase = true)
    }
    updateListModel(filtered)
}
```

Add debounce (250ms) on text changes.

- [ ] **Step 3: Build and commit**

Run: `./gradlew :pullrequest:compileKotlin`

```bash
git add pullrequest/
git commit -m "feat(pr): search/filter PRs by title, author, or branch"
```

---

### Task 8: Reviewer Management

**Files:**
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrActionService.kt`

- [ ] **Step 1: Add reviewer management UI in PR header**

Next to the reviewer list, add:
- **"+ Add"** link — opens a search popup (reuse `getUsers()` from BitbucketBranchClient)
- **"x" icon** on each reviewer — removes them
- **"Needs Work"** button — sets reviewer status to NEEDS_WORK

```kotlin
// Add reviewer: fetch current reviewers, add new one, PUT update
suspend fun addReviewer(prId: Int, username: String): ApiResult<Unit>

// Remove reviewer: fetch current, remove, PUT update
suspend fun removeReviewer(prId: Int, username: String): ApiResult<Unit>

// Set needs-work
suspend fun setNeedsWork(prId: Int, username: String): ApiResult<Unit>
```

- [ ] **Step 2: Add user search popup**

When clicking "+ Add", show a `JBPopupFactory` popup with a search field that calls `getUsers(filter)` as the user types (debounced 300ms). Display results in a list, click to add as reviewer.

- [ ] **Step 3: Build and commit**

Run: `./gradlew :pullrequest:compileKotlin`

```bash
git add pullrequest/
git commit -m "feat(pr): reviewer management — add, remove, needs-work in PR detail"
```

---

### Task 9: PR Creation in PR Tab

**Files:**
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDashboardPanel.kt`

- [ ] **Step 1: Wire the "Create PR" button**

The Create PR button currently shows a notification. Replace with opening the existing `CreatePrDialog` from the Bamboo module:

```kotlin
// Reuse the existing dialog via reflection or by moving it to :core
// Or create a simpler inline version in the PR tab
```

Since `CreatePrDialog` is in `:bamboo` and PR tab can't depend on `:bamboo`, create a lightweight `CreatePrPanel` in the PR tab that:
1. Shows source branch (current branch) and target branch (dropdown from `getBranches()`)
2. Title field (auto-filled from branch name or last commit)
3. Description text area
4. Reviewer search + add (reuse pattern from Task 8)
5. "Create" button calls `BitbucketBranchClient.createPullRequest()`

- [ ] **Step 2: Show create form as a card in the detail panel area**

When "Create PR" is clicked, show the form in the right panel (where detail normally appears). On success, refresh the PR list and show the new PR's detail.

- [ ] **Step 3: Build and commit**

Run: `./gradlew :pullrequest:compileKotlin`

```bash
git add pullrequest/
git commit -m "feat(pr): create pull request directly from PR tab"
```

---

### Task 10: Comment Posting UI

**Files:**
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`

- [ ] **Step 1: Add general comment input to Activity tab**

At the bottom of the Activity sub-panel, add a comment input (same pattern as Sprint tab's QuickCommentPanel):

```kotlin
val commentField = JBTextField().apply {
    emptyText.text = "Add a comment..."
}
val sendButton = JButton(AllIcons.Actions.Execute).apply {
    toolTipText = "Post comment"
}
```

On submit: call `PrActionService.addComment(prId, text)`, clear field, refresh activities.

- [ ] **Step 2: Build and commit**

Run: `./gradlew :pullrequest:compileKotlin`

```bash
git add pullrequest/
git commit -m "feat(pr): comment posting UI in activity tab"
```

---

### Task 11: PR Title Editing + Unapprove Button

**Files:**
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`

- [ ] **Step 1: Make PR title editable**

In the header, make the title label double-clickable to enter edit mode (same pattern as description edit). On save, call `PrActionService.updateTitle()`.

Add `updateTitle()` to PrActionService: fetch current PR, update title field, PUT.

- [ ] **Step 2: Add Unapprove button**

When the user has already approved, the Approve button shows "Approved" and is disabled. Change to: show "Unapprove" button that calls `PrActionService.unapprove()`. Toggle between Approve/Unapprove states.

- [ ] **Step 3: Build and commit**

Run: `./gradlew :pullrequest:compileKotlin`

```bash
git add pullrequest/
git commit -m "feat(pr): editable PR title and unapprove toggle button"
```

---

### Task 12: Markdown Rendering for PR Description

**Files:**
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`

- [ ] **Step 1: Render description as HTML from Markdown**

Replace `JBTextArea` with `JEditorPane("text/html", "")` for description display. Convert Markdown to HTML using a lightweight parser.

Option A: Use IntelliJ's built-in Markdown support if available (`org.intellij.markdown`).
Option B: Simple regex-based conversion for common patterns (headers, bold, italic, code blocks, links, lists).

For simplicity, use Option B — a small utility function:

```kotlin
fun markdownToHtml(md: String): String {
    return md
        .replace(Regex("^### (.+)$", RegexOption.MULTILINE), "<h4>$1</h4>")
        .replace(Regex("^## (.+)$", RegexOption.MULTILINE), "<h3>$1</h3>")
        .replace(Regex("^# (.+)$", RegexOption.MULTILINE), "<h2>$1</h2>")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        .replace(Regex("\\*(.+?)\\*"), "<i>$1</i>")
        .replace(Regex("`(.+?)`"), "<code>$1</code>")
        .replace(Regex("((?:^- .+\n?)+)", RegexOption.MULTILINE)) { match ->
            "<ul>" + match.value.replace(Regex("^- (.+)$", RegexOption.MULTILINE), "<li>$1</li>") + "</ul>"
        }
        .replace(Regex("\\[(.+?)\\]\\((.+?)\\)"), "<a href='$2'>$1</a>")
        .replace("\n\n", "<br><br>")
}
```

Style the JEditorPane with theme-aware colors using `StatusColors.htmlColor()`.

- [ ] **Step 2: Build and commit**

Run: `./gradlew :pullrequest:compileKotlin`

```bash
git add pullrequest/
git commit -m "feat(pr): markdown rendering for PR descriptions"
```

---

## Final Verification

- [ ] Run full build: `./gradlew buildPlugin`
- [ ] Run tests: `./gradlew :pullrequest:test`
- [ ] Verify in IDE: `./gradlew runIde`
  - PR list: search, filter by state (Open/Merged/Declined), pagination
  - PR detail: diff viewer (double-click file), commits tab, build status badge
  - Commenting: general comment, inline comment reply
  - Reviewer: add, remove, needs-work, unapprove
  - Create PR: form in right panel
  - Description: markdown rendered
- [ ] Update `pullrequest/CLAUDE.md` with new API endpoints and UI features
- [ ] Update `core/CLAUDE.md` with new BitbucketBranchClient methods
