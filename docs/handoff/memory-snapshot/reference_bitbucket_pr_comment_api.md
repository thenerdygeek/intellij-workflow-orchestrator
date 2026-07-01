---
name: Bitbucket PR Comment API capabilities (DC + Cloud)
description: Endpoint-by-endpoint map of Bitbucket Data Center and Cloud PR comment APIs — inline, threaded, resolve, tasks, suggestions, gaps — for building a review workflow in the :pullrequest module
type: reference
originSessionId: 87f699de-c64e-4b0f-91f4-53890c45be4f
---
# Bitbucket PR Comment API — Reference

Scope: everything needed to build a PR review workflow in the `:pullrequest` module. Covers both Bitbucket **Data Center (Server)** and **Bitbucket Cloud** because they have completely different payload shapes.

## Bitbucket Data Center / Server (DC)

- Base path: `/rest/api/1.0` (alias `/latest`)
- Auth: `Authorization: Bearer <token>` (HTTP-access-token) — matches `BitbucketBranchClient.fromConfiguredSettings()`
- Docs: https://docs.atlassian.com/bitbucket-server/rest/7.21.0/bitbucket-rest.html and https://developer.atlassian.com/server/bitbucket/rest/v905/api-group-pull-requests/

| Capability | Endpoint | Supported | Notes |
|---|---|---|---|
| General PR comment | `POST .../pull-requests/{prId}/comments` | Yes | Body `{"text":"..."}`; no `anchor` |
| Inline comment | same POST + `anchor` | Yes | `anchor: { path, srcPath, line, lineType: ADDED\|REMOVED\|CONTEXT, fileType: FROM\|TO, fromHash, toHash, diffType: COMMIT\|EFFECTIVE\|RANGE }` |
| File-level comment | same, omit `line`/`lineType` | Yes | Just `path` (and `srcPath` if moved) |
| Pin to commit SHA | `fromHash`/`toHash` on anchor | Yes | Omit for "effective diff" |
| Reply (threaded) | same POST + `parent: {id: N}` | Yes | Arbitrarily deep |
| Edit | `PUT .../comments/{commentId}` | Yes | Body must include current `version`; **409 on mismatch (optimistic lock)** |
| Delete | `DELETE .../comments/{commentId}?version=N` | Yes | Author or admin only |
| Resolve / thread state | `PUT .../comments/{commentId}` with `state: "RESOLVED"\|"OPEN"` and `severity` | Yes | Response exposes `threadResolvedDate`, `threadResolver` |
| Blocker / task comments | `POST/GET/PUT/DELETE .../pull-requests/{prId}/blocker-comments[/{id}]` | Yes | Alias that forces `severity:"BLOCKER"`; introduced 7.2 (Tasks→Comments merge). Read-only `GET .../tasks` and `.../tasks/count` remain for backward compat |
| Commit-level comments | `POST .../commits/{commitId}/comments` | Yes | Separate from PR comments; shows on commit activity, not PR unless commit is in PR range |
| Suggestions | markdown convention only | Partial | DC renders ` ```suggestion ` fenced blocks with an "Apply" button; no typed field |
| @mentions | `@username` in `text` | Yes | Resolved at render time |
| Attachments / images | — | **No** | UI drag-drop uses undocumented `/rest/ui/...`; no public endpoint |
| Reactions / emoji | — | **No** | Not a DC feature |
| PR description | `PUT .../pull-requests/{prId}` with `description` | Yes | Not a comment |

Response object (`comment`): `id, version, text, author, createdDate, updatedDate, comments[] (nested replies), anchor, severity, state, threadResolvedDate, threadResolver, permittedOperations`.

Version requirements: severity/state/threaded replies = Bitbucket Server 7.x+. `blocker-comments` endpoints = 7.2+.

## Bitbucket Cloud

- Base path: `/2.0`
- Auth: workspace/repo-access-token via `Authorization: Bearer`, app password via Basic, or OAuth 2
- Docs: https://developer.atlassian.com/cloud/bitbucket/rest/api-group-pullrequests/ and live OpenAPI at https://api.bitbucket.org/swagger.json

| Capability | Endpoint | Supported | Notes |
|---|---|---|---|
| List comments | `GET /2.0/repositories/{ws}/{repo}/pullrequests/{pr_id}/comments` | Yes | Paginated; tombstones via `deleted:true` |
| General PR comment | `POST .../pullrequests/{pr_id}/comments` | Yes | Body `{"content":{"raw":"..."}}`; markdown rendered into `content.html` |
| Inline comment | same POST + `inline` | Yes | `inline: { path, from, to }`. `from`=source-side (REMOVED/context old), `to`=destination-side (ADDED/context new). ADDED→only `to`; REMOVED→only `from`; CONTEXT→both |
| File-level comment | `inline: {path}` | Yes | No line |
| Pin to commit SHA | — | **No** | Always pins to PR's current diff |
| Reply | same POST + `parent: {id: N}` | Yes | Arbitrarily deep via parent chain |
| Edit | `PUT .../comments/{comment_id}` | Yes | No version/ETag — last-write-wins; author only |
| Delete | `DELETE .../comments/{comment_id}` | Yes | Soft delete |
| Draft / pending | `pending: true` on POST | Yes | Hidden until submitted with review |
| Resolve thread | `POST .../pullrequests/{pr_id}/comments/{comment_id}/resolve`; `DELETE .../resolve` to reopen | Yes | Added late 2023; nested `resolution: {type, user, date}` on reads. Not in public OpenAPI export — API-group doc only |
| Tasks (legacy) | `.../pullrequests/{pr_id}/tasks` | Deprecated | Merged into comments 2022; shims are read-only |
| Suggestions | markdown only | Partial | Same ` ```suggestion ` convention as GitHub/DC |
| @mentions | `@{account_id}` preferred; `@username` legacy | Yes | GDPR migration (2019) moved to account_id UUIDs |
| Attachments / images | — | **No** | UI uploads via internal `/xhr/...`; workaround = embed external image URL in markdown |
| Reactions / emoji | — | **No** | Not supported |
| Commit-level comments | `POST/GET/PUT/DELETE /2.0/repositories/{ws}/{repo}/commit/{commit}/comments[/{id}]` | Yes | Separate resource |
| PR description | `PUT /2.0/repositories/{ws}/{repo}/pullrequests/{pr_id}` with `description` | Yes | Not a comment |

Response object: `id, content.{raw,markup,html}, user, created_on, updated_on, links, inline?, parent?, pending, deleted, resolution?`.

## Gaps where UI ≠ API (both products)

- **Attachments/images** — UI drag-drop only; no public endpoint
- **Reactions / emoji** — no API, no UI
- **Suggestions** — markdown convention only; no typed field
- **Cloud commit-SHA anchoring** — DC can pin via `fromHash/toHash`; Cloud cannot

## Implementation notes for `:pullrequest` module

Per CLAUDE.md service architecture rule (`core interface → ToolResult<T> → feature impl → agent tool wrapper`):

1. **Core interface**: `PullRequestCommentService` in `core/services/` returning `ToolResult<PrComment>`
2. **Sealed `CommentPayload`** — do NOT unify DC + Cloud payloads into one DTO. Per-flavor serializers live inside `:pullrequest`
3. **DC optimistic locking** — every edit/delete needs current `version`; fetch-then-mutate with retry-on-409. Silent failures otherwise when two reviewers collide
4. **Resolve semantics differ** — DC: `PUT` with `state:"RESOLVED"` on top-of-thread comment. Cloud: dedicated `POST/DELETE .../resolve` sub-resource
5. **Tasks** — DC: use `blocker-comments` alias so UI badges as task. Cloud: don't write to `tasks` (deprecated), use comments + resolve
6. **Cloud mentions** — prefer `account_id` over username (GDPR-safe)
7. **DC markdown** — modern DC renders PR comments as markdown, but older instances may render as Atlassian wiki-markup; confirm per customer version

## Bottom-line verdict

Full functional parity is achievable for everything users actually do (general + inline + file-level comments, reply, edit, delete, resolve, suggestions via markdown) on both DC and Cloud. Document attachments/reactions/Cloud-SHA-anchoring as known limitations.

## Authoritative citations

- DC 7.21 REST: https://docs.atlassian.com/bitbucket-server/rest/7.21.0/bitbucket-rest.html
- DC latest (9.x) pull-requests group: https://developer.atlassian.com/server/bitbucket/rest/v905/api-group-pull-requests/
- Cloud pull-requests group: https://developer.atlassian.com/cloud/bitbucket/rest/api-group-pullrequests/
- Cloud OpenAPI (authoritative): https://api.bitbucket.org/swagger.json
