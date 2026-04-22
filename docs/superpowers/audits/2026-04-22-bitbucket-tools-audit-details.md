# Bitbucket Tools Audit — Per-Action Details (2026-04-22)

Long-form audit per action. Each section:
- **Expected (per API reference):** endpoint, method, required fields, nuance
- **Found (in our code):** endpoint actually called, request body shape, response parsing
- **Diff:** ✅ match / ⚠ edge-case issue / ❌ broken
- **Tests:** covered / partial / none
- **Sandbox check:** PASS / FAIL / not available
- **Verdict:** OK / FIX / BROKEN / MISSING / UNTESTED
- **Notes:** free-form

---

## bitbucket_review.add_pr_comment

**Expected (per API reference):**
- Endpoint: `POST /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}/comments`
- Body: `{"text":"..."}` — no `anchor` field for a general comment
- Auth: `Authorization: Bearer <token>`
- Content-Type: `application/json`
- Response: `{id, version, text, author, createdDate, updatedDate, comments[], permittedOperations}`

**Found (in our code):**

Tool layer — `BitbucketReviewTool.kt:82–88`:
```
"add_pr_comment" -> {
    val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
    val text = params["text"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("text")
    ToolValidation.validateNotBlank(text, "text")?.let { return it }
    val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
    service.addPrComment(prId, text, repoName = repoName).toAgentToolResult()
}
```

Service layer — `BitbucketServiceImpl.kt:716–728`:
- Calls `api.addPullRequestComment(projectKey, repoSlug, prId, text)`
- On success: returns `ToolResult.success(Unit, "Comment added to PR #$prId (comment #${result.data.id})")`

HTTP client — `BitbucketBranchClient.kt:1162–1199`:
- URL pattern: `"$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/comments"`
- Method: `POST`
- Body DTO (`BitbucketBranchClient.kt:245`): `private data class AddCommentRequest(val text: String)` — serialized to `{"text":"..."}`
- Content-Type: `"application/json".toMediaType()` set on the request body
- Auth: via `AuthInterceptor(tokenProvider, AuthScheme.BEARER)` added at client construction (`BitbucketBranchClient.kt:463`) — injects `Authorization: Bearer <token>` on every request
- Response parse: `json.decodeFromString<BitbucketPrComment>(body)` — reads `id` field from response

**Diff:**
- Path: ✅ exact match
- Method: ✅ POST
- Body shape: ✅ `{"text":"..."}` only, no stray fields
- Auth header: ✅ Bearer via interceptor
- Content-Type: ✅ `application/json`
- Response parse: ✅ id extracted and surfaced in summary; remaining response fields (`version`, `author`, etc.) are not propagated to the agent but that is acceptable given the `ToolResult<Unit>` return type — no data-loss for the calling agent

**Tests:** partial — schema only, no execute() path tested. `BitbucketReviewToolTest.kt` asserts tool name, action enum presence, required param list, allowedWorkers, toToolDefinition schema, and missing/unknown action error paths. No test exercises `add_pr_comment` through a mocked `BitbucketService`.

**Sandbox check:** not available

**Verdict:** UNTESTED

**Notes:** The implementation is correct against the DC API spec. The only gap is test coverage of the execute() path. If someone passes `text` as a number literal in JSON, `jsonPrimitive.content` will still produce a string representation — no type-safety risk. The blank-text guard (`ToolValidation.validateNotBlank`) prevents empty-string comments reaching the API.

**Test coverage needed:**
- Happy-path mock returning success with comment id → verify summary contains PR id and comment id
- 4xx mock returning error → verify tool returns error result, not exception
- Missing required parameter guard: omit `text` → tool returns missing-param error before any service call

---

## bitbucket_review.add_inline_comment

**Expected (per API reference):**
- Endpoint: same `POST .../comments` as general comment
- Body must include `anchor` object: `{path, srcPath?, line, lineType: ADDED|REMOVED|CONTEXT, fileType: FROM|TO, fromHash?, toHash?, diffType: COMMIT|EFFECTIVE|RANGE}`
- `lineType` controls which side of the diff the comment attaches to; `fileType` controls FROM (old file) vs TO (new file) side
- `srcPath` required for renamed files (the old path); omitting it for renames produces a 400 or silently attaches to wrong file
- `fromHash`/`toHash` pin the comment to a specific commit range; omitting them means "effective diff" (floating comment)

**Found (in our code):**

Tool layer — `BitbucketReviewTool.kt:90–104`:
- Extracts: `prId`, `filePath`, `line` (parsed to Int), `lineType`, `text`, optional `repoName`
- Calls: `service.addInlineComment(prId, filePath, line, lineType, text, repoName = repoName)`
- No parameter exposed for `fileType`, `srcPath`, `fromHash`, `toHash`, or `diffType`

Service layer — `BitbucketServiceImpl.kt:171–183`:
- Calls: `api.addInlineComment(projectKey, repoSlug, prId, filePath, line, lineType, text)`

HTTP client — `BitbucketBranchClient.kt:1577–1623`:
- URL: `"$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/comments"`
- Method: `POST`
- Body DTO (`BitbucketBranchClient.kt:338–349`):
  ```kotlin
  private data class InlineCommentAnchor(
      val path: String,
      val line: Int,
      val lineType: String,
      val fileType: String = "TO"   // hardcoded default
  )
  private data class InlineCommentRequest(val text: String, val anchor: InlineCommentAnchor)
  ```
- Serialized body: `{"text":"...","anchor":{"path":"...","line":N,"lineType":"...","fileType":"TO"}}`
- `fileType` is hardcoded to `"TO"` (the destination/new-file side) — not caller-configurable
- `srcPath`, `fromHash`, `toHash`, `diffType` are absent from the DTO
- Note: a separate response-parsing DTO `BitbucketCommentAnchor` (BitbucketBranchClient.kt:188) carries `srcPath`; the private request DTO `InlineCommentAnchor` (BitbucketBranchClient.kt:344–349) does not — this is the bug.

**Diff:**
- Path: ✅
- Method: ✅ POST
- Required fields present: `path` ✅, `line` ✅, `lineType` ✅, `fileType` ✅ (hardcoded TO)
- `lineType` sourced from caller — if caller passes `"REMOVED"` or `"CONTEXT"`, the value is forwarded as-is; ⚠ no enum validation in the tool (any string is accepted — API will 400 on invalid value)
- `fileType` hardcoded to `"TO"`: ⚠ means comments on the old/source side of the diff (REMOVED lines that only exist in the FROM file) cannot be correctly anchored; `fileType: "FROM"` is needed for REMOVED-line comments. In practice, a REMOVED comment with `fileType: "TO"` will either return a 400 or attach to a wrong line.
- `srcPath` missing: ⚠ if the file was renamed between the source and target branches, the anchor will fail to resolve (API returns 400 or misplaces comment)
- `fromHash`/`toHash`/`diffType` missing: acceptable omission — "effective diff" behaviour is the safe default

**Tests:** partial — schema only, no execute() path tested

**Sandbox check:** not available

**Verdict:** FIX

**Notes:**
1. `lineType` is not validated against `{ADDED, REMOVED, CONTEXT}` at the tool layer. Add enum check matching the `status` check pattern already in use on line 142.
2. **Fix (option B preferred — zero API-surface change):** In `BitbucketBranchClient.kt:344–349`, change `val fileType: String = "TO"` to derive from `lineType`:
   ```kotlin
   val fileType: String = if (lineType == "REMOVED") "FROM" else "TO"
   ```
   No change needed at service or tool layer for option B.

   **Fix (option A — explicit parameter):** Add optional `file_type` param to the tool schema (`BitbucketReviewTool.kt:90–104`), thread through `BitbucketService.addInlineComment` (interface and impl) and into the `InlineCommentAnchor` DTO. Use only if callers need to override the lineType→fileType mapping.
3. **Fix (`srcPath` for renames):** Add `val srcPath: String? = null` to `InlineCommentAnchor` (BitbucketBranchClient.kt:344–349) and an optional `src_path` tool parameter at `BitbucketReviewTool.kt:90–104`, threaded through the service interface and impl. Without it, inline comments on renamed files will fail silently or return a 400.
4. No test exercises the HTTP path with a mock service — the lineType/fileType interaction is the most likely source of silent production failures.

**Test coverage needed:**
- Happy-path mock: `lineType = "ADDED"` → `fileType = "TO"` in serialized body ✅
- Happy-path mock: `lineType = "REMOVED"` → `fileType = "FROM"` in serialized body ✅ (after fix B)
- 4xx mock: service returns error; tool returns error result
- Missing required parameter guard: omit `file_path` → tool returns missing-param error before any service call
- Invalid `lineType` value (e.g. `"MODIFIED"`) → enum validation error at tool layer (after fix 1)

---

## bitbucket_review.reply_to_comment

**Expected (per API reference):**
- Endpoint: same `POST .../comments`
- Body: `{"text":"...","parent":{"id":N}}`
- `parent.id` must be the ID of an existing comment in the PR thread

**Found (in our code):**

Tool layer — `BitbucketReviewTool.kt:107–119`:
- Extracts `parent_comment_id` as string, parses to Int (returns error on non-integer)
- Calls: `service.replyToComment(prId, parentId, text, repoName = repoName)`

Service layer — `BitbucketServiceImpl.kt:185–197`:
- Calls: `api.replyToComment(projectKey, repoSlug, prId, parentCommentId, text)`

HTTP client — `BitbucketBranchClient.kt:1629–1669`:
- URL: `"$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/comments"`
- Method: `POST`
- Body DTOs (`BitbucketBranchClient.kt:352–358`):
  ```kotlin
  private data class ReplyCommentRequest(val text: String, val parent: CommentParentRef)
  private data class CommentParentRef(val id: Int)
  ```
- Serialized body: `{"text":"...","parent":{"id":N}}`

**Diff:**
- Path: ✅
- Method: ✅ POST
- Body shape: ✅ `{"text":"...","parent":{"id":N}}` matches the API spec exactly
- Auth header: ✅ Bearer via interceptor
- Content-Type: ✅ `application/json`
- `parent_comment_id` validation: ✅ integer parse guard at tool layer (lines 110–115); blank text guard present

**Tests:** partial — schema only, no execute() path tested

**Sandbox check:** not available

**Verdict:** UNTESTED

**Notes:** Implementation is correct. No missing-param gaps for the required fields. The 404 error path distinguishes "PR not found" from "comment not found" via a single error message but the API may return 404 for either; that ambiguity is acceptable at the current abstraction level. No further issues found.

**Test coverage needed:**
- Happy-path mock returning success → verify summary references PR id and parent comment id
- 4xx mock returning error → verify tool returns error result, not exception
- Missing required parameter guard: omit `text` → tool returns missing-param error before any service call
- Non-integer `parent_comment_id` (e.g. `"abc"`) → rejected at parameter layer with parse error, no service call made

---

## bitbucket_review.add_reviewer

**Expected (per API reference):**
- Bitbucket DC does not have a dedicated "add reviewer" endpoint. Reviewer list is managed via `PUT /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{prId}` with the full PR object including the updated `reviewers` array.
- Per `pullrequest/CLAUDE.md`: "PUT replaces entire PR — always fetch current state first to preserve title/reviewers". Correct implementation: GET current PR state → mutate reviewers list → PUT full updated PR including `version` for optimistic locking.

**Found (in our code):**

Tool layer — `BitbucketReviewTool.kt:122–128`:
- Extracts `prId`, `username`; calls `service.addReviewer(prId, username, repoName = repoName)`

Service layer — `BitbucketServiceImpl.kt:239–275`:
```
val currentPr = api.getPullRequestDetail(projectKey, repoSlug, prId)     // GET first
val existingPr = ... (returns error if GET fails)
// duplicate check: if username already in reviewers, return error
val updatedReviewers = existingPr.reviewers.map { ... } + BitbucketPrReviewerRef(user = BitbucketReviewerUser(name = username))
val updateRequest = BitbucketPrUpdateRequest(
    title = existingPr.title,
    description = existingPr.description ?: "",
    version = existingPr.version,
    reviewers = updatedReviewers
)
api.updatePullRequest(projectKey, repoSlug, prId, updateRequest)          // PUT with full object + version
```

HTTP client — `BitbucketBranchClient.kt:1081–1119`:
- GET: `getPullRequestDetail` → `GET /rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId`
- PUT: `updatePullRequest` → `PUT /rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId`
- PUT body: `BitbucketPrUpdateRequest(title, description, version, reviewers)` — all fields required by DC API
- 409 on version conflict is handled and returned as `ErrorType.VALIDATION_ERROR`

**Diff:**
- GET-first pattern: ✅ implemented correctly
- PUT endpoint: ✅ correct
- `version` included: ✅ prevents silent data-loss on concurrent edits
- Reviewer list preserved: ✅ existing reviewers are mapped and kept
- Title/description preserved: ✅ both carried forward from current PR state
- Duplicate reviewer guard: ✅ returns a descriptive error if already a reviewer
- 409 conflict handling: ✅ surfaced to caller

**Tests:** partial — schema only, no execute() path tested

**Sandbox check:** not available

**Verdict:** UNTESTED

**Notes:** This is the most correct of the reviewer-management implementations. The GET-first + version + full-PR-PUT pattern is exactly what the DC API requires. No data-loss risk detected. The implementation also correctly maps reviewer user objects through `BitbucketPrReviewerRef(user = BitbucketReviewerUser(name = it.user.name))` to avoid carrying over stale approval status fields into the PUT body.

**Test coverage needed:**
- Happy-path mock: GET returns PR with no reviewers, PUT succeeds → verify reviewer appears in PUT body
- 4xx mock returning error on PUT → verify tool returns error result, not exception
- Missing required parameter guard: omit `username` → tool returns missing-param error before any service call
- Duplicate-reviewer guard: mock GET returns PR already containing `username` → tool returns descriptive error, no PUT made
- 409 version-conflict on PUT → verify error is surfaced to caller with appropriate message

---

## bitbucket_review.remove_reviewer

**Expected (per API reference):**
- Same as `add_reviewer`: no dedicated DELETE-reviewer endpoint; must GET current PR → filter reviewer out of list → PUT full updated PR with `version`.

**Found (in our code):**

Tool layer — `BitbucketReviewTool.kt:130–135`:
- Extracts `prId`, `username`; calls `service.removeReviewer(prId, username, repoName = repoName)`

Service layer — `BitbucketServiceImpl.kt:769–805`:
```
val currentPr = api.getPullRequestDetail(...)       // GET first
val existingPr = ... (returns error if GET fails)
// not-found check: if username NOT in reviewers, return error
val updatedReviewers = existingPr.reviewers
    .filter { it.user.name != username }
    .map { BitbucketPrReviewerRef(user = BitbucketReviewerUser(name = it.user.name)) }
val updateRequest = BitbucketPrUpdateRequest(
    title = existingPr.title,
    description = existingPr.description ?: "",
    version = existingPr.version,
    reviewers = updatedReviewers
)
api.updatePullRequest(projectKey, repoSlug, prId, updateRequest)          // PUT with full object + version
```

HTTP client: same `updatePullRequest` as `add_reviewer` — `PUT .../pull-requests/$prId`

**Diff:**
- GET-first pattern: ✅
- PUT endpoint: ✅
- `version` included: ✅
- Title/description preserved: ✅
- Reviewer filtered out: ✅ (`.filter { it.user.name != username }`)
- Not-found guard: ✅ returns descriptive error if reviewer not in list
- 409 conflict: ✅ surfaced via error type in `updatePullRequest`

**Tests:** partial — schema only, no execute() path tested

**Sandbox check:** not available

**Verdict:** UNTESTED

**Notes:** Implementation mirrors `add_reviewer` and is equally correct. One edge-case to note: if a reviewer has already submitted a NEEDS_WORK vote and is then removed, the DC API will allow the removal — the vote is discarded. This is the correct API behaviour and our code handles it correctly by simply filtering the reviewer from the list without any pre-check on approval status.

**Test coverage needed:**
- Happy-path mock: GET returns PR with target reviewer, PUT succeeds → verify reviewer is absent from PUT body
- 4xx mock returning error on PUT → verify tool returns error result, not exception
- Missing required parameter guard: omit `username` → tool returns missing-param error before any service call
- Reviewer-not-found case: mock GET returns PR without `username` in reviewer list → tool returns descriptive error, no PUT made
- Empty-reviewer-list edge case: mock GET returns PR with exactly one reviewer (the one being removed) → PUT body contains empty `reviewers` array `[]`, not omitted

---

## bitbucket_review.set_reviewer_status

**Expected (per API reference):**
- DC endpoint: `PUT /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/participants/{userSlug}` with body `{"user":{"name":"<userSlug>"},"status":"APPROVED|UNAPPROVED|NEEDS_WORK","approved":true|false}`
- Verified via Bitbucket DC v7.21.0 WADL: `https://docs.atlassian.com/bitbucket-server/rest/7.21.0/bitbucket-rest.wadl` — resource lists PUT and DELETE; no POST on this path.
- Alternatively, the older binary endpoints: `POST .../approve` (approve) and `DELETE .../approve` (unapprove) — but these do not support `NEEDS_WORK`.
- The participants endpoint is the only one that supports all three states including `NEEDS_WORK`.

**Found (in our code):**

Tool layer — `BitbucketReviewTool.kt:138–149`:
- Validates `status` against `{"APPROVED", "NEEDS_WORK", "UNAPPROVED"}` (line 142–147)
- Calls: `service.setReviewerStatus(prId, username, status, repoName = repoName)`

Service layer — `BitbucketServiceImpl.kt:199–211`:
- Calls: `api.setReviewerStatus(projectKey, repoSlug, prId, username, status)`

HTTP client — `BitbucketBranchClient.kt:1675–1712`:
- URL: `"$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/participants/$username"`
- Method: `PUT` (not POST)
- Body DTO (`BitbucketBranchClient.kt:361`): `private data class ReviewerStatusRequest(val status: String)`
- Serialized body: `{"status":"APPROVED|NEEDS_WORK|UNAPPROVED"}`
- No `approved` field in the body

**Diff:**
- Endpoint path: ✅ `...participants/{username}` is correct
- HTTP method: ✅ code uses `PUT`; verified correct against Bitbucket DC v7.21.0 WADL. The original audit's claim that POST is the documented method was incorrect and has been retracted.
- Body `status` field: ✅ present
- `approved` field: ⚠ missing from the body. The DC API participants endpoint expects `{"status":"APPROVED","approved":true}` (or `false` for UNAPPROVED/NEEDS_WORK). Omitting `approved` may cause the server to reject the request or silently ignore the status change depending on the DC version.
- All three statuses supported in tool: ✅ (APPROVED, NEEDS_WORK, UNAPPROVED — full enum validated at tool layer)
- 409 conflict handled: ✅

**Tests:** partial — schema only, no execute() path tested

**Sandbox check:** not available

**Verdict:** FIX

**Notes:**
1. HTTP method verified as PUT against Bitbucket DC v7.21.0 WADL (`https://docs.atlassian.com/bitbucket-server/rest/7.21.0/bitbucket-rest.wadl`). The original audit's POST claim was incorrect and has been retracted. No method change is needed in the code.
2. **Fix (`approved` field):** In `BitbucketBranchClient.kt:361`, change
   ```kotlin
   private data class ReviewerStatusRequest(val status: String)
   ```
   to
   ```kotlin
   private data class ReviewerStatusRequest(val status: String, val approved: Boolean)
   ```
   At the call site where the request is constructed (`BitbucketBranchClient.kt:1685` or wherever `ReviewerStatusRequest` is instantiated), pass `approved = (status == "APPROVED")`. This makes the body `{"status":"APPROVED","approved":true}` for APPROVED and `{"status":"NEEDS_WORK","approved":false}` / `{"status":"UNAPPROVED","approved":false}` for the other two states.
3. The endpoint path is correct and NEEDS_WORK is supported — which is an improvement over using the binary `approve`/`decline` endpoints.
4. The sole remaining FIX reason is the missing `approved` field in the request body.

**Test coverage needed:**
- Happy-path mock: `status = "APPROVED"` → body contains `{"status":"APPROVED","approved":true}` (after fix)
- Happy-path mock: `status = "NEEDS_WORK"` → body contains `{"status":"NEEDS_WORK","approved":false}` (after fix)
- Happy-path mock: `status = "UNAPPROVED"` → body contains `{"status":"UNAPPROVED","approved":false}` (after fix)
- 4xx mock returning error → verify tool returns error result, not exception
- Missing required parameter guard: omit `username` → tool returns missing-param error before any service call

---

## bitbucket_pr.create_pr

**Expected (per API reference):**
- Endpoint: `POST /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests`
- Body: `{"title":"...","description":"...","fromRef":{"id":"refs/heads/{branch}"},"toRef":{"id":"refs/heads/{branch}"},"reviewers":[...]?}`
- DC requires the `id` field of `fromRef`/`toRef` to be the full `refs/heads/<branchName>` ref (not bare branch name)
- Auth: `Authorization: Bearer <token>`
- Content-Type: `application/json`
- Response: full PR object with `id`, `links.self`, `state`, `fromRef`, `toRef`
- 409 when a PR already exists for the same source/target branch pair
- Source: `pullrequest/CLAUDE.md` endpoint list

**Found (in our code):**

Tool layer — `BitbucketPrTool.kt:95–110`:
- Extracts `title`, `pr_description`, `from_branch`, `to_branch` (defaults to `"master"`), optional `repo_name`
- Guards: `validateNotBlank(title)`, `validateNotBlank(fromBranch)`, same-branch check
- Calls: `service.createPullRequest(title, prDescription, fromBranch, toBranch, repoName = repoName)`

Service layer — `BitbucketServiceImpl.kt:52–133`:
- Calls: `api.createPullRequest(projectKey, repoSlug, title, description, fromBranch, toBranch)`
- Maps response to `PullRequestData` with summary containing PR id, link, and branch names

HTTP client — `BitbucketBranchClient.kt:669–717`:
- URL: `"$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests"`
- Method: `POST`
- Body DTO (`BitbucketBranchClient.kt:250–256`):
  ```kotlin
  BitbucketPrRequest(
      title = title, description = description,
      fromRef = BitbucketRef("refs/heads/$fromBranch"),
      toRef = BitbucketRef("refs/heads/$toBranch"),
      reviewers = reviewers
  )
  ```
- Serialized `fromRef.id`: `"refs/heads/<branch>"` — correct full-ref format
- 409 handled: `ApiResult.Error(ErrorType.VALIDATION_ERROR, "PR already exists for branch $fromBranch")`
- Auth: Bearer via interceptor; Content-Type: `application/json`

**Diff:**
- Path: ✅ exact match
- Method: ✅ POST
- `fromRef.id` / `toRef.id` format: ✅ `refs/heads/` prefix included
- Body shape: ✅ title, description, fromRef, toRef all present
- Auth header: ✅ Bearer via interceptor
- Content-Type: ✅ `application/json`
- 409 conflict handling: ✅ surfaced as VALIDATION_ERROR
- Default `to_branch = "master"`: ⚠ many repos use `main` as default branch. The fallback is hardcoded to `"master"` in the tool (`BitbucketPrTool.kt:99`) rather than querying the repo's actual default branch. This is a functional limitation, not a broken API call.
- `reviewers` not exposed via tool: ⚠ the tool has no `reviewers` parameter, so PRs are always created without explicit reviewers. The client method accepts `reviewers` but the service impl does not pass any. Acceptable for MVP, but worth noting.

**Tests:** partial — schema only, no execute() path tested

**Sandbox check:** not available

**Verdict:** UNTESTED

**Notes:** The implementation is correct against the DC API spec. Two minor limitations noted above (hardcoded `master` default, no reviewer seeding at create time) are design choices rather than bugs. The branch-same guard prevents a nonsensical API call. The 409 path is handled.

**Test coverage needed:**
- Happy-path mock returning created PR with id and link → verify summary contains PR id, from/to branches, link
- 4xx mock (e.g. 409 duplicate) → verify tool returns error result, not exception
- Missing required param guard: omit `title` → tool returns missing-param error before any service call
- Same source/target branch guard: `from_branch == to_branch` → tool returns branch-equality error before any service call
- Blank `title` guard: `title = "  "` → `validateNotBlank` fires before service call

---

## bitbucket_pr.get_pr_detail

**Expected (per API reference):**
- Endpoint: `GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}`
- No body; no required query params
- Response: full PR object including `reviewers`, `author`, `fromRef`, `toRef`, `state`, `version`, `links`
- Source: `pullrequest/CLAUDE.md` endpoint list

**Found (in our code):**

Tool layer — `BitbucketPrTool.kt:112–116`:
- Parses `pr_id` via `BitbucketToolUtils.parsePrId(params)`; returns `invalidPrId()` on parse failure
- Calls: `service.getPullRequestDetail(prId, repoName = repoName)`

Service layer — `BitbucketServiceImpl.kt:466–494`:
- Calls: `api.getPullRequestDetail(projectKey, repoSlug, prId)`
- Maps to `PullRequestDetailData` including reviewer list with status; summary includes PR id, title, state, branches, reviewers

HTTP client — `BitbucketBranchClient.kt:1043–1074`:
- URL: `"$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId"`
- Method: `GET`
- Parses response as `BitbucketPrDetail` including `version` (needed for subsequent PUT/POST operations)
- 401 and 404 handled

**Diff:**
- Path: ✅ exact match
- Method: ✅ GET
- Body: ✅ none (GET)
- Auth header: ✅ Bearer via interceptor
- Response parse: ✅ full detail including reviewers, version, branches

**Tests:** partial — schema only, no execute() path tested

**Sandbox check:** not available

**Verdict:** UNTESTED

**Notes:** Implementation is correct. The `version` field is correctly parsed from the response and carried in `PullRequestDetailData.version`, enabling subsequent PUT/merge/decline operations to perform optimistic locking.

**Test coverage needed:**
- Happy-path mock returning PR detail → verify summary contains PR id, title, state, reviewer names
- 4xx mock (404) → verify tool returns error result, not exception
- Non-integer `pr_id` (e.g. `"abc"`) → tool returns `invalidPrId()` error before any service call

---

## bitbucket_pr.get_pr_commits

**Expected (per API reference):**
- Endpoint: `GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/commits`
- Optional query param: `limit` (paging)
- Response: paged list of commit objects with `id`, `displayId`, `message`, `author`, `authorTimestamp`
- Verified against DC 7.21 REST docs: `https://docs.atlassian.com/bitbucket-server/rest/7.21.0/bitbucket-rest.html`

**Found (in our code):**

Tool layer — `BitbucketPrTool.kt:118–122`:
- Parses `pr_id`; calls `service.getPullRequestCommits(prId, repoName = repoName)`

Service layer — `BitbucketServiceImpl.kt:135–169`:
- Calls: `api.getPullRequestCommits(projectKey, repoSlug, prId)`
- Maps each commit to `CommitData(id, displayId, message, author, timestamp)`
- Summary: `"PR #$prId has N commit(s)"`

HTTP client — `BitbucketBranchClient.kt:1539–1571`:
- URL: `"$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/commits?limit=$limit"` (default `limit=50`)
- Method: `GET`
- Parses response as `BitbucketCommitListResponse` (`.values: List<BitbucketCommit>`)
- 401, 404 handled

**Diff:**
- Path: ✅ exact match
- Method: ✅ GET
- `limit` param: ✅ 50 default — reasonable; no pagination to fetch beyond first 50 commits
- Response parse: ✅ id, displayId, message, author all mapped
- Auth header: ✅ Bearer via interceptor

**Tests:** partial — schema only, no execute() path tested

**Sandbox check:** not available

**Verdict:** UNTESTED

**Notes:** DC docs reference: https://docs.atlassian.com/bitbucket-server/rest/7.21.0/bitbucket-rest.html

Implementation is correct. The hardcoded `limit=50` means PRs with more than 50 commits will silently return a truncated list with no indication that results were paged. For typical PRs this is acceptable, but large PRs (e.g. long-running feature branches) could return incomplete data. This is a scope/design choice rather than a broken call.

**Test coverage needed:**
- Happy-path mock returning list of commits → verify summary contains count and CommitData fields are populated
- 4xx mock (404 PR not found) → verify tool returns error result, not exception
- Non-integer `pr_id` → tool returns `invalidPrId()` before any service call
- Empty commit list (new PR with no commits) → summary shows "0 commit(s)", no exception

---

## bitbucket_pr.get_pr_activities

**Expected (per API reference):**
- Endpoint: `GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/activities`
- Optional query param: `limit` (paging); `fromType` / `fromId` for cursor paging
- Response: paged list of activity objects; each has `id`, `action` (COMMENTED, APPROVED, MERGED, DECLINED, REVIEWED, OPENED, UPDATED, RESCOPED, UNAPPROVED), `user`, `createdDate`, and optional `comment`/`commentAnchor` for COMMENTED activities
- Verified against DC 7.21 REST docs

**Found (in our code):**

Tool layer — `BitbucketPrTool.kt:124–128`:
- Parses `pr_id`; calls `service.getPullRequestActivities(prId, repoName = repoName)`

Service layer — `BitbucketServiceImpl.kt:496–528`:
- Calls: `api.getPullRequestActivities(projectKey, repoSlug, prId)`
- Maps each activity to `PrActivityData(id, action, userName, timestamp, commentText?, commentId?, filePath?, lineNumber?)`
- `filePath` resolved from both `commentAnchor?.path` (top-level) and `comment?.anchor?.path` (nested) — correct dual-source lookup
- Summary: `"PR #N has X activit{y|ies}"`

HTTP client — `BitbucketBranchClient.kt:1125–1156`:
- URL: `"$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/activities?limit=50"`
- Method: `GET`
- Parses response as `BitbucketPrActivityResponse(values: List<BitbucketPrActivity>)`
- 401, 404 handled

**Diff:**
- Path: ✅ exact match
- Method: ✅ GET
- `limit=50`: ⚠ same pagination caveat as `get_pr_commits` — no cursor paging; busy PRs with >50 activities silently truncate
- Response parse: ✅ action, user, timestamp, comment fields all mapped
- Auth header: ✅ Bearer via interceptor

**Tests:** partial — schema only, no execute() path tested

**Sandbox check:** not available

**Verdict:** UNTESTED

**Notes:** DC docs reference: https://docs.atlassian.com/bitbucket-server/rest/7.21.0/bitbucket-rest.html

Implementation is correct for the happy path. The dual-source file path resolution (`commentAnchor?.path ?: comment?.anchor?.path`) is a thoughtful handling of the DC API's two places where anchor data can live.

**Test coverage needed:**
- Happy-path mock with mixed activities (COMMENTED, APPROVED, MERGED) → verify all action types are mapped, comment text extracted
- COMMENTED activity with inline anchor → verify `filePath` and `lineNumber` populated
- 4xx mock → verify tool returns error result, not exception
- Non-integer `pr_id` → tool returns `invalidPrId()` before any service call

---

## bitbucket_pr.get_pr_changes

**Expected (per API reference):**
- Endpoint: `GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/changes`
- Optional query param: `limit` (paging)
- Response: paged list of change objects; each has `path.toString` (the destination path), `type` (ADD, MODIFY, DELETE, RENAME, COPY), and `path.components`, `path.parent`, `path.name`
- Verified against DC 7.21 REST docs

**Found (in our code):**

Tool layer — `BitbucketPrTool.kt:130–134`:
- Parses `pr_id`; calls `service.getPullRequestChanges(prId, repoName = repoName)`

Service layer — `BitbucketServiceImpl.kt:530–553`:
- Calls: `api.getPullRequestChanges(projectKey, repoSlug, prId)`
- Maps each change to `PrChangeData(path = c.path.toString, changeType = c.type)`
- Summary: `"PR #N has X changed file(s)"`

HTTP client — `BitbucketBranchClient.kt:1500–1531`:
- URL: `"$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/changes?limit=100"`
- Method: `GET`
- Parses as `BitbucketPrChangesResponse(values: List<BitbucketPrChange>)`
- 401, 404 handled

**Diff:**
- Path: ✅ exact match
- Method: ✅ GET
- `limit=100`: ✅ larger cap than commits/activities; acceptable for most PRs
- Response parse: ✅ `path.toString` and change type are the canonical fields
- Auth header: ✅ Bearer via interceptor

**Tests:** partial — schema only, no execute() path tested

**Sandbox check:** not available

**Verdict:** UNTESTED

**Notes:** DC docs reference: https://docs.atlassian.com/bitbucket-server/rest/7.21.0/bitbucket-rest.html

Implementation is correct. `limit=100` is a reasonable cap for file-change lists. For PRs with more than 100 changed files the response will be silently truncated — the same design limitation as activities and commits.

**Test coverage needed:**
- Happy-path mock with ADD/MODIFY/DELETE/RENAME types → verify `PrChangeData.path` and `changeType` are populated for each
- 4xx mock (404) → verify tool returns error result, not exception
- Non-integer `pr_id` → tool returns `invalidPrId()` before any service call
- Empty change list → summary shows "0 changed file(s)", no exception

---

## bitbucket_pr.get_pr_diff

**Expected (per API reference):**
- Endpoint: `GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/diff`
- No required query params; optional `contextLines`, `withComments`, `srcPath` for specific file diffs
- Accept header for plain text diff: `text/plain` — some DC versions also return JSON-structured diff when `Accept: application/json`
- Response: raw unified diff text
- Verified against DC 7.21 REST docs

**Found (in our code):**

Tool layer — `BitbucketPrTool.kt:136–140`:
- Parses `pr_id`; calls `service.getPullRequestDiff(prId, repoName = repoName)`

Service layer — `BitbucketServiceImpl.kt:555–575`:
- Calls: `api.getPullRequestDiff(projectKey, repoSlug, prId)`
- Summary: `"PR #N diff fetched (N chars)"`

HTTP client — `BitbucketBranchClient.kt:1464–1494`:
- URL: `"$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/diff"`
- Method: `GET`
- Accept header: `"text/plain"` — requests plain-text unified diff
- Returns raw body string on 200
- 401, 404 handled

**Diff:**
- Path: ✅ exact match
- Method: ✅ GET
- Accept header: ✅ `text/plain` is correct for raw diff content
- Response parse: ✅ raw string returned; no JSON parsing needed
- Auth header: ✅ Bearer via interceptor

**Tests:** partial — schema only, no execute() path tested

**Sandbox check:** not available

**Verdict:** UNTESTED

**Notes:** DC docs reference: https://docs.atlassian.com/bitbucket-server/rest/7.21.0/bitbucket-rest.html

Implementation is correct. Large diffs will be returned as very long strings — no server-side truncation or chunking is applied at this layer. The `ToolOutputSpiller` in the agent layer handles output that exceeds 30K chars by spilling to disk.

**Test coverage needed:**
- Happy-path mock returning a multi-file diff string → verify the raw string is surfaced in `ToolResult.data`; summary contains char count
- 4xx mock (404) → verify tool returns error result, not exception
- Non-integer `pr_id` → tool returns `invalidPrId()` before any service call

---

## bitbucket_pr.check_merge_status

**Expected (per API reference):**
- Endpoint: `GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/merge`
- No body; no required query params
- Response: `{"canMerge": bool, "conflicted": bool, "vetoes": [{summaryMessage, detailedMessage}]}`
- Distinct from `POST .../merge` which performs the merge — same path, different method
- Source: `pullrequest/CLAUDE.md` endpoint list (explicitly documented)

**Found (in our code):**

Tool layer — `BitbucketPrTool.kt:142–146`:
- Parses `pr_id`; calls `service.checkMergeStatus(prId, repoName = repoName)`

Service layer — `BitbucketServiceImpl.kt:730–767`:
- Calls: `api.getMergeStatus(projectKey, repoSlug, prId)`
- Maps to `MergeStatusData(canMerge, conflicted, vetoes: List<String>)`
- Veto messages concatenated as `"summaryMessage: detailedMessage"` or just `summaryMessage` when no detail
- Summary: `"PR #N merge status: CAN/CANNOT merge"` with CONFLICTED annotation and veto list

HTTP client — `BitbucketBranchClient.kt:1340–1374`:
- URL: `"$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/merge"`
- Method: `GET`
- Parses as `BitbucketMergeStatus(canMerge, conflicted, outcome, vetoes: List<BitbucketMergeVeto>)`
- 401, 404 handled with specific error types

**Diff:**
- Path: ✅ exact match (same path as POST merge, different method)
- Method: ✅ GET (not POST — correctly disambiguated)
- Body: ✅ none (GET)
- Response parse: ✅ canMerge, conflicted, vetoes all mapped
- Auth header: ✅ Bearer via interceptor

**Tests:** partial — schema only, no execute() path tested

**Sandbox check:** not available

**Verdict:** UNTESTED

**Notes:** Implementation is correct. The veto message concatenation logic (`"${summaryMessage}: ${detailedMessage}"` when detail is non-blank) is a sensible LLM-optimized rendering of the DC veto structure. The `outcome` field from the response is parsed but not surfaced in `MergeStatusData` — this is acceptable since `canMerge` covers the same information.

**Test coverage needed:**
- Happy-path mock: `canMerge=true`, no vetoes → summary says "CAN merge"
- Happy-path mock: `canMerge=false`, conflicted=true, vetoes present → summary says "CANNOT merge (CONFLICTED)" with vetoes
- 4xx mock (404) → verify tool returns error result, not exception
- Non-integer `pr_id` → tool returns `invalidPrId()` before any service call

---

## bitbucket_pr.approve_pr

**Expected (per API reference):**
- Endpoint: `POST /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/approve`
- Empty body (or empty JSON object `{}`)
- Response: reviewer participant object with `approved: true`
- 409 if already approved or caller is not a reviewer
- Source: `pullrequest/CLAUDE.md` endpoint list (explicitly documented)

**Found (in our code):**

Tool layer — `BitbucketPrTool.kt:148–152`:
- Parses `pr_id`; calls `service.approvePullRequest(prId, repoName = repoName)`

Service layer — `BitbucketServiceImpl.kt:602–614`:
- Calls: `api.approvePullRequest(projectKey, repoSlug, prId)`
- Summary: `"PR #N approved"` on success

HTTP client — `BitbucketBranchClient.kt:1205–1240`:
- URL: `"$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/approve"`
- Method: `POST`
- Body: empty string `""` with `Content-Type: application/json` — acceptable for DC (empty body POST)
- Response parsed as `BitbucketPrReviewer`
- 401, 404, 409 handled; 409 surfaced as VALIDATION_ERROR

**Diff:**
- Path: ✅ exact match
- Method: ✅ POST
- Body: ✅ empty body with application/json content-type — accepted by DC
- Auth header: ✅ Bearer via interceptor
- 409 handling: ✅ surfaced to caller

**Tests:** partial — schema only, no execute() path tested

**Sandbox check:** not available

**Verdict:** UNTESTED

**Notes:** Implementation is correct. Using `"".toRequestBody("application/json".toMediaType())` for an empty-body POST is the correct OkHttp idiom and DC accepts it.

**Test coverage needed:**
- Happy-path mock returning reviewer object with `approved=true` → verify summary says "PR #N approved"
- 4xx mock (409 already approved) → verify tool returns error result, not exception; error message references 409
- 4xx mock (404) → verify tool returns error result
- Non-integer `pr_id` → tool returns `invalidPrId()` before any service call

---

## bitbucket_pr.merge_pr

**Expected (per API reference):**
- Endpoint: `POST /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/merge?version={version}`
- `version` is a required query param for optimistic locking (not in body)
- Body: `{"message": "...", "strategyId": "no-ff|squash|rebase-no-ff|...", "deleteSourceRef": true|false}`
- 409 on version conflict or unmet merge preconditions
- Source: `pullrequest/CLAUDE.md` (merge endpoint documented; `strategyId` named with examples `no-ff`, `squash`, `rebase-no-ff`)

**Found (in our code):**

Tool layer — `BitbucketPrTool.kt:154–161`:
- Parses `pr_id`, `strategy` (optional string), `delete_source_branch` (parsed via `toBooleanStrictOrNull()`, defaults to `false`), `commit_message` (optional)
- No enum validation on `strategy`
- Calls: `service.mergePullRequest(prId, strategy, deleteSourceBranch, commitMessage, repoName = repoName)`

Service layer — `BitbucketServiceImpl.kt:630–660`:
- **GET-first for version**: fetches current PR detail to get `prDetail.version`
- Calls: `api.mergePullRequest(projectKey, repoSlug, prId, prDetail.version, strategyId = strategy, deleteSourceBranch = deleteSourceBranch, commitMessage = commitMessage)`

HTTP client — `BitbucketBranchClient.kt:1288–1333`:
- URL: `"$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/merge?version=$version"`
- Method: `POST`
- Body: `BitbucketMergeRequest(message = commitMessage, strategyId = strategyId, deleteSourceRef = deleteSourceBranch)`
- Serialized: `{"message":null|"...", "strategyId":null|"...", "deleteSourceRef":false|true}`
- 401, 404, 409 handled; 409 surfaced as VALIDATION_ERROR

**Diff:**
- Path: ✅ exact match
- Method: ✅ POST
- `version` as query param: ✅ passed as `?version=N` (correct — not in body)
- GET-first for version: ✅ correctly fetches current PR before merge
- Body `strategyId` field: ✅ present and forwarded from caller
- Body `deleteSourceRef` field: ✅ correct field name (not `deleteSourceBranch`)
- Auth header: ✅ Bearer via interceptor
- Strategy enum mismatch: ⚠ the tool description (`BitbucketPrTool.kt:64`) lists strategies as `"merge-commit, squash, ff-only"` but `pullrequest/CLAUDE.md` documents the actual DC strategy IDs as `"no-ff, squash, rebase-no-ff"`. The string is passed through as-is to the API — if the LLM follows the tool description and sends `"merge-commit"` or `"ff-only"`, the API will return an error because those are not valid DC strategy IDs.

**Tests:** partial — schema only, no execute() path tested

**Sandbox check:** not available

**Verdict:** FIX

**Notes:**
1. **Fix (strategy enum mismatch):** In `BitbucketPrTool.kt:64`, change the strategy description from `"merge-commit, squash, ff-only"` to `"no-ff, squash, rebase-no-ff"` to match the actual DC API strategy IDs documented in `pullrequest/CLAUDE.md`. The value is passed as-is to `BitbucketMergeRequest.strategyId`, so an invalid value will cause the API to reject the merge. The fix is a one-line doc change in the tool schema; no production logic change required.
2. No other issues found. The GET-first pattern for version is correctly implemented, matching the pattern used in `add_reviewer` and `update_pr_title`.

**Test coverage needed:**
- Happy-path mock: GET version succeeds, POST merge succeeds → verify summary says "PR #N merged successfully"
- Strategy forwarding: `strategy = "no-ff"` → body contains `"strategyId":"no-ff"` (after fix, LLM will use correct values)
- `delete_source_branch = "true"` → body contains `"deleteSourceRef":true`
- 4xx mock (409 version conflict) → verify tool returns error result with version-conflict hint
- 4xx mock (GET detail fails, blocks merge) → verify tool returns GET error, no POST attempted
- Non-integer `pr_id` → tool returns `invalidPrId()` before any service call
- `delete_source_branch = "notabool"` → `toBooleanStrictOrNull()` returns null, defaults to `false` — verify no exception

---

## bitbucket_pr.decline_pr

**Expected (per API reference):**
- Endpoint: `POST /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/decline?version={version}`
- `version` is a required query param for optimistic locking
- Empty body (DC accepts empty body for this POST)
- 409 on version conflict or PR in wrong state (already merged/declined)
- Not listed in `pullrequest/CLAUDE.md` endpoint table; verified against DC 7.21 docs via code inspection (same pattern as merge — `?version=N` as query param, `POST` method)

**Found (in our code):**

Tool layer — `BitbucketPrTool.kt:163–167`:
- Parses `pr_id`; calls `service.declinePullRequest(prId, repoName = repoName)`

Service layer — `BitbucketServiceImpl.kt:662–683`:
- **GET-first for version**: fetches current PR detail to get `prDetail.version`
- Calls: `api.declinePullRequest(projectKey, repoSlug, prId, prDetail.version)`

HTTP client — `BitbucketBranchClient.kt:1421–1457`:
- URL: `"$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId/decline?version=$version"`
- Method: `POST`
- Body: empty string `""` with `Content-Type: application/json`
- 401, 404, 409 handled; 409 surfaced as VALIDATION_ERROR

**Diff:**
- Path: ✅ correct (POST `.../decline?version=N`)
- Method: ✅ POST
- `version` as query param: ✅ correct — passed as `?version=N`
- GET-first for version: ✅ correctly fetches current PR before decline
- Auth header: ✅ Bearer via interceptor

**Tests:** partial — schema only, no execute() path tested

**Sandbox check:** not available

**Verdict:** UNTESTED

**Notes:** DC docs reference: https://docs.atlassian.com/bitbucket-server/rest/7.21.0/bitbucket-rest.html

Implementation is correct. The GET-first pattern for version is consistent with `merge_pr` and required for the DC API's optimistic locking on `decline`. Ref: `BitbucketBranchClient.kt:1416–1418` KDoc confirms `POST .../decline?version={version}`.

**Test coverage needed:**
- Happy-path mock: GET version succeeds, POST decline succeeds → verify summary says "PR #N declined"
- 4xx mock (409 already declined/merged) → verify tool returns error result
- 4xx mock (GET detail fails) → verify tool returns GET error, no POST attempted
- Non-integer `pr_id` → tool returns `invalidPrId()` before any service call

---

## bitbucket_pr.update_pr_title

**Expected (per API reference):**
- Endpoint: `PUT /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}`
- DC PUT replaces entire PR — all fields required: `title`, `description`, `version`, `reviewers[]`
- Partial PUT (sending only title) is not supported and will clear other fields
- `version` required for optimistic locking; 409 on conflict
- Source: `pullrequest/CLAUDE.md` — *"PUT replaces entire PR — always fetch current state first to preserve title/reviewers"*

**Found (in our code):**

Tool layer — `BitbucketPrTool.kt:169–175`:
- Parses `pr_id`; extracts `new_title` (required, blank-validated)
- Calls: `service.updatePrTitle(prId, newTitle, repoName = repoName)`

Service layer — `BitbucketServiceImpl.kt:277–306`:
- **GET-first**: `api.getPullRequestDetail(projectKey, repoSlug, prId)` to fetch current state
- Returns error if GET fails (PR not found)
- Constructs `BitbucketPrUpdateRequest(title = newTitle, description = existingPr.description ?: "", version = existingPr.version, reviewers = existingPr.reviewers.map { ... })`
- Reviewer list mapped through `BitbucketPrReviewerRef(user = BitbucketReviewerUser(name = it.user.name))` to strip stale approval/status fields
- Calls: `api.updatePullRequest(projectKey, repoSlug, prId, updateRequest)`

HTTP client — `BitbucketBranchClient.kt:1081–1119`:
- URL: `"$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$prId"`
- Method: `PUT`
- Body: serialized `BitbucketPrUpdateRequest(title, description, version, reviewers)`
- 401, 404, 409 handled; 409 surfaced as VALIDATION_ERROR with "version conflict" message

**Diff:**
- Path: ✅ exact match
- Method: ✅ PUT
- GET-first pattern: ✅ implemented correctly
- `version` included in body: ✅ prevents silent version-conflict data loss
- `description` preserved: ✅ `existingPr.description ?: ""`
- `reviewers` preserved: ✅ filtered and re-mapped (strips approval status, keeps names)
- `title` replaced: ✅ `newTitle` used
- 409 conflict handling: ✅ surfaced to caller
- Auth header: ✅ Bearer via interceptor

**Tests:** partial — schema only, no execute() path tested

**Sandbox check:** not available

**Verdict:** UNTESTED

**Notes:** This is a correct and complete PUT implementation. The GET-first + version + full-PR-PUT pattern exactly matches what DC requires. The reviewer mapping strips stale `approved`/`status` fields from the existing reviewer objects (only `user.name` is carried) — this is the correct approach as DC will recalculate approval status server-side. Blank-title guard at tool layer (`validateNotBlank`) prevents sending an empty title to the API.

**Test coverage needed:**
- Happy-path mock: GET returns PR with description and reviewers; PUT body contains new title but preserves description and reviewer names → verify summary says "PR #N title updated to: X"
- 4xx mock (409 version conflict on PUT) → verify tool returns error result
- 4xx mock (GET fails) → verify tool returns GET error, no PUT attempted
- Blank `new_title` → `validateNotBlank` fires before any service call
- Missing `new_title` parameter → `missingParam("new_title")` returned before any service call
- Non-integer `pr_id` → tool returns `invalidPrId()` before any service call

---

## bitbucket_pr.update_pr_description

**Expected (per API reference):**
- Same endpoint and constraints as `update_pr_title`: `PUT /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}`
- DC PUT replaces entire PR — must send title, description, version, reviewers all together
- `version` required for optimistic locking
- Source: `pullrequest/CLAUDE.md`

**Found (in our code):**

Tool layer — `BitbucketPrTool.kt:177–182`:
- Parses `pr_id`; extracts `pr_description` (required — returns `missingParam` error if absent; no blank validation)
- Calls: `service.updatePrDescription(prId, newDescription, repoName = repoName)`

Service layer — `BitbucketServiceImpl.kt:685–714`:
- **GET-first**: `api.getPullRequestDetail(projectKey, repoSlug, prId)` to fetch current state
- Returns error if GET fails
- Constructs `BitbucketPrUpdateRequest(title = existingPr.title, description = description, version = existingPr.version, reviewers = existingPr.reviewers.map { ... })`
- Calls: `api.updatePullRequest(projectKey, repoSlug, prId, updateRequest)`

HTTP client — same `updatePullRequest` as `update_pr_title` — `PUT .../pull-requests/$prId`

**Diff:**
- Path: ✅ exact match
- Method: ✅ PUT
- GET-first pattern: ✅ correctly implemented
- `version` included: ✅
- `title` preserved: ✅ `existingPr.title`
- `reviewers` preserved: ✅ reviewer list mapped correctly
- `description` replaced: ✅ new description used
- Blank description guard: ⚠ `update_pr_description` does NOT call `validateNotBlank(newDescription)` on the description, while `update_pr_title` does call `validateNotBlank(newTitle)`. Sending an empty string description to DC is valid (the API accepts empty descriptions), so this is not a broken call — but the asymmetry with `update_pr_title` is worth noting.
- 409 conflict handling: ✅ surfaced via PUT error response

**Tests:** partial — schema only, no execute() path tested

**Sandbox check:** not available

**Verdict:** UNTESTED

**Notes:** Implementation is correct. The only minor asymmetry noted above (no blank validation for description) is not a bug — DC allows empty PR descriptions. The GET-first + version + full-PR-PUT chain is complete and correct.

**Test coverage needed:**
- Happy-path mock: GET returns PR with title and reviewers; PUT body contains new description but preserves title and reviewer names → verify summary says "PR #N description updated"
- Empty description (`pr_description = ""`): PUT proceeds normally (DC allows empty descriptions) — verify no error
- 4xx mock (409 version conflict on PUT) → verify tool returns error result
- 4xx mock (GET fails) → verify tool returns GET error, no PUT attempted
- Missing `pr_description` parameter → `missingParam("pr_description")` returned before any service call
- Non-integer `pr_id` → tool returns `invalidPrId()` before any service call

---

## bitbucket_pr.get_my_prs

**Expected (per API reference):**
- Endpoint: `GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests?state={state}&role.1=AUTHOR&username.1={username}`
- `pullrequest/CLAUDE.md` explicitly notes: *"requires both `role.1` and `username.1` parameters for author filtering"*
- `state` can be `OPEN`, `MERGED`, `DECLINED`, or `ALL`
- `role.1=AUTHOR` filters to PRs authored by the user named by `username.1`
- When `username.1` is omitted, the DC API behaviour is server-version-dependent: some versions return all PRs for the role regardless of user; others return PRs for the authenticated user. Relying on `role.1` without `username.1` is undefined behaviour per CLAUDE.md note.
- Verified against DC docs and `BitbucketBranchClient.kt` KDoc

**Found (in our code):**

Tool layer — `BitbucketPrTool.kt:184–188`:
- `state` defaults to `"OPEN"` if not provided; no enum validation
- Calls: `service.getMyPullRequests(state, repoName = repoName)`
- No `username` parameter in the tool schema — the tool does not accept a username

Service layer — `BitbucketServiceImpl.kt:418–439`:
- Calls: `api.getMyPullRequests(projectKey, repoSlug, state)` — **`username` not passed**; defaults to `null` inside client

HTTP client — `BitbucketBranchClient.kt:922–957`:
- URL: `"$baseUrl/.../pull-requests?state=$state&role.1=AUTHOR$usernameParam&start=$start&limit=$limit"`
- `usernameParam` = `"&username.1=$username"` only when `username` is non-null
- When `username = null` (our case): `usernameParam = ""` → URL is `.../pull-requests?state=$state&role.1=AUTHOR`
- No `username.1` in the query string

**Diff:**
- Path: ✅ correct base path
- Method: ✅ GET
- `role.1=AUTHOR`: ✅ present
- `username.1`: ❌ absent — the tool provides no way to pass a username; the service call does not forward one; the client defaults to null. Per `pullrequest/CLAUDE.md`, both `role.1` and `username.1` are required for author filtering. Without `username.1`, results are undefined/server-dependent and may return all PRs in the repo rather than just the current user's PRs.
- State validation: ⚠ no enum validation — invalid `state` values (e.g. `"OPEN_ONLY"`) are forwarded to the API without checking

**Tests:** partial — schema only, no execute() path tested

**Sandbox check:** not available

**Verdict:** FIX

**Notes:**
1. **Fix (`username.1` missing):** The service method `getMyPullRequests` needs to resolve the current user's username and pass it as the `username` argument to `api.getMyPullRequests`. Options:
   - **Option A (preferred):** In `BitbucketServiceImpl.getMyPullRequests`, call `api.getCurrentUser()` (or read from `CredentialStore` / `ConnectionSettings.bitbucketUsername`) before calling `api.getMyPullRequests`, and pass the resolved username. The `BitbucketBranchClient.getMyPullRequests` already accepts `username: String? = null` at line 926.
   - **Option B:** Add an optional `username` parameter to the tool and service interface, allowing the LLM to provide a username when known. This is less ergonomic but avoids the extra API call.
   - Option A is simpler for the common case (list my own PRs) and matches the documented requirement in `pullrequest/CLAUDE.md`.
2. **State enum validation (minor):** Add a check in the tool layer against `{"OPEN", "MERGED", "DECLINED", "ALL"}` to fail fast on invalid state values before making an API call.

**Test coverage needed:**
- Happy-path mock: `username.1` sent alongside `role.1=AUTHOR` → verify only authored PRs returned; summary contains count
- `username.1` absent: current behaviour → verify what the API returns (may be all repo PRs — this is the bug to validate)
- State `"MERGED"` → URL contains `state=MERGED`
- 4xx mock → verify tool returns error result, not exception
- Invalid state value (`"INVALID"`) → enum validation error (after fix 2)

---

## bitbucket_pr.get_reviewing_prs

**Expected (per API reference):**
- Endpoint: `GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests?state={state}&role.1=REVIEWER&username.1={username}`
- Same CLAUDE.md note applies: both `role.1` and `username.1` required for role-based filtering
- Verified against DC docs and `BitbucketBranchClient.kt` KDoc at line 960–962

**Found (in our code):**

Tool layer — `BitbucketPrTool.kt:190–194`:
- `state` defaults to `"OPEN"` if not provided; no enum validation
- Calls: `service.getReviewingPullRequests(state, repoName = repoName)`
- No `username` parameter in tool schema

Service layer — `BitbucketServiceImpl.kt:441–462`:
- Calls: `api.getReviewingPullRequests(projectKey, repoSlug, state)` — **`username` not passed**; defaults to `null` inside client

HTTP client — `BitbucketBranchClient.kt:963–998`:
- URL: `"$baseUrl/.../pull-requests?state=$state&role.1=REVIEWER$usernameParam&start=$start&limit=$limit"`
- `usernameParam` = `""` when `username = null` (our case) → `username.1` absent from query string

**Diff:**
- Path: ✅ correct base path
- Method: ✅ GET
- `role.1=REVIEWER`: ✅ present
- `username.1`: ❌ absent — same issue as `get_my_prs`; `username.1` not passed because neither the tool nor service provides a username
- State validation: ⚠ same as `get_my_prs` — no enum guard

**Tests:** partial — schema only, no execute() path tested

**Sandbox check:** not available

**Verdict:** FIX

**Notes:**
1. **Fix (`username.1` missing):** Same fix as `get_my_prs` — resolve the current user's Bitbucket username in `BitbucketServiceImpl.getReviewingPullRequests` and pass it to `api.getReviewingPullRequests`. The client method already accepts `username: String? = null` at `BitbucketBranchClient.kt:967`. Use the same Option A approach (resolve from settings/credential store) for consistency with `get_my_prs`.
2. **State enum validation:** Same minor fix as `get_my_prs` — validate against `{"OPEN", "MERGED", "DECLINED", "ALL"}`.
3. Both `get_my_prs` and `get_reviewing_prs` have the same FIX. They should be fixed together in one commit to avoid code duplication in the username-resolution logic.

**Test coverage needed:**
- Happy-path mock: `username.1` sent alongside `role.1=REVIEWER` → verify only reviewer PRs returned
- State `"OPEN"` (default) → URL contains `state=OPEN&role.1=REVIEWER`
- 4xx mock → verify tool returns error result, not exception
- Invalid state value → enum validation error (after fix)
- Non-numeric `pr_id`: N/A (this action uses `state`, not `pr_id`)
