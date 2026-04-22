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
