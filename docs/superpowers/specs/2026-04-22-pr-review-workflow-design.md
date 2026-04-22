# PR Review Workflow — Design Spec

**Date:** 2026-04-22
**Status:** Draft, pending user review
**Scope:** Pull-request review workflow inside the `:pullrequest` module — Comments sub-tab, AI Review sub-tab, agent tools for ad-hoc comment operations.

---

## 1. Goals

- Give the plugin full functional parity with the Bitbucket web UI for PR comment operations: list, general + inline + file-level comments, reply, edit, delete, resolve / reopen — both Bitbucket Data Center and (where API supports it) Bitbucket Cloud.
- Let the user run an AI-driven code review against a PR, stage findings locally, edit them, and push them to Bitbucket as real comments.
- Let the agent, when asked in the normal agent chat, read and act on Bitbucket PR comments (reply, resolve, edit code to address them).
- Reuse as much of the existing agent infrastructure (persona, tools, session persistence, approval gates) as feasible.

## 2. Non-goals

- Attachments / images on comments — Bitbucket does not expose these via REST.
- Reactions / emoji — not supported on either product.
- Cloud commit-SHA anchoring — Cloud API limitation.
- A second agent engine. The review is a regular agent session.
- Settings UI. No new user-configurable settings.
- Gutter-marker editor decorations (possible later enhancement).

## 3. Architecture at a glance

```
┌─ PR tab (existing) ────────────────────────────────────────┐
│ PrDashboardPanel                                           │
│  ├─ PrListPanel   ← now renders unread-count badge per PR  │
│  └─ PrDetailPanel ← new tabbed sub-panel:                  │
│       [Overview | Comments | AI Review]                    │
│                                                            │
│  Comments   → BitbucketService (direct; no agent)          │
│  AI Review  → PrReviewFindingsStore (reads),               │
│               "Run AI review" button starts agent session  │
└────────────────────────────────────────────────────────────┘

┌─ Core layer (`:core`) ─────────────────────────────────────┐
│ BitbucketService                                           │
│   + listPrComments / getPrComment / editPrComment          │
│   + deletePrComment / resolvePrComment / reopenPrComment   │
│ PrReviewFindingsStore (new) — disk-backed, per-PR, per-session │
└────────────────────────────────────────────────────────────┘

┌─ Agent layer (`:agent`) ──────────────────────────────────┐
│ bitbucket_review meta-tool                                 │
│   existing 6 actions (untrusted until Phase 0 audit)       │
│   + list_comments / get_comment / edit_comment             │
│   + delete_comment / resolve_comment / reopen_comment      │
│ ai_review meta-tool (new) — local findings staging         │
│   add_finding / list_findings / clear_findings             │
│ code-reviewer persona (existing) — add `ai_review` to tools│
└────────────────────────────────────────────────────────────┘

┌─ Feature layer (`:pullrequest`) ───────────────────────────┐
│ PrReviewTaskBuilder — assembles initial user message       │
│ PrReviewSessionRegistry — prId ↔ sessionId per-PR mapping  │
│ UI: CommentsTabPanel + AiReviewTabPanel                    │
└────────────────────────────────────────────────────────────┘
```

## 4. Key design decisions

| # | Decision | Rationale |
|---|---|---|
| 1 | Comments and AI Review live as sub-tabs inside the existing `PrDetailPanel`, not as a new tool-window tab. | Matches the phrase "if a PR is selected"; keeps the 6-tool-window-tabs layout stable. |
| 2 | Clicking an inline comment uses best-effort local-file navigation with a "view original snapshot" affordance. | Editable-first (matches IntelliJ convention); fidelity on demand for drift cases. |
| 3 | "Run AI review" triggers a new agent chat session with `code-reviewer` persona; tab switches to agent chat. | Reuses existing agent engine and UI; one implementation for button and ad-hoc paths. |
| 4 | Findings are emitted via a structured `ai_review.add_finding` tool, not parsed from text. | Avoids brittle text-parsing; aligns with per-write-approval model. |
| 5 | `bitbucket_review` gets 6 new actions; a new `ai_review` meta-tool is added. | Two meta-tools keeps local-vs-remote write semantics cleanly separated. |
| 6 | Per-finding edit capabilities: discard / edit text / change severity / push-per-row or bulk / Cloud drafts / ` ```suggestion ` block rendering. | User-approved scope (Q4=B + suggestions). |
| 7 | Per-write approval on every agent-initiated remote write (post, reply, edit, delete, resolve, reopen). | User-approved (Q5 revised). Matches existing approval-gate patterns. |
| 8 | Polling via `SmartPoller` only while Comments tab is visible; optimistic local updates; EventBus publishes unread counts. | User-approved (Q6); matches existing plugin patterns. |
| 9 | Zero new settings. | Sane defaults; YAGNI on config surface (Q7 revised). |
| 10 | No new persona; reuse `code-reviewer`. No new skill file; workflow prose assembled in Kotlin. | `code-reviewer` already has the pipeline and tool allowlist; a skill file with `disable-model-invocation: true` would duplicate it. |
| 11 | Phase 0 audit of existing `bitbucket_review` + `bitbucket_pr` tools is blocking. | User flagged these as unverified. Fix-in-place default; escalate to rebuild only on findings. |

## 5. Detailed UI

### 5.1 Comments sub-tab

Data source: `BitbucketService.listPrComments(prId)`. `SmartPoller` 30s start, 1.5× backoff, 5m cap — only while tab visible.

Layout:

```
┌─ Comments ─────────────────────────────────────────────┐
│ Toolbar: [Refresh] [Filter ▾] [Only open ☐]  3 unread │
├────────────────────────────────────────────────────────┤
│ File: src/foo/Bar.kt                                   │
│   Line 42 · alice · 2h · OPEN                          │
│     "This null-check looks off..."                     │
│     ↳ bob · 1h: "Agreed, fixing"                       │
│     [Reply] [Resolve] [View snapshot]                  │
│                                                        │
│ General PR comments                                    │
│   dave · 3h: "Please add a changelog entry"            │
│     [Reply] [Resolve]                                  │
│                                                        │
│ [+ Add general comment]                                │
└────────────────────────────────────────────────────────┘
```

Grouping: by file → line → time; then a separate General group. Threads nested up to 3 levels deep, rest collapse with *"Show N more replies"*.

Per-comment actions — Reply, Resolve/Reopen, Edit (author-only), Delete (author-only), View snapshot. No approval gate (direct user action).

DC `version` conflict on edit/delete → 3-way merge modal (ours / theirs / original).

Inline comment click → opens local file at best-effort-mapped line + banner *"Comment anchored to line 42 — now closest to line 47. [View original snapshot]"* + badge *"commit a3f9b2 · [view original]"*.

Filters: *Only open* (default on), *All / Mine / Mentions of me / Blocker*, author facet.

Optimistic updates with EventBus publication (`PrCommentsUpdated(prId, total, unreadCount)`, `PrCommentAdded(prId, commentId)`).

Unread badge on `PrListPanel` rows driven by `PrCommentsUpdated` events.

Empty state: *"No comments on this PR yet."* + link to add general comment.

Keyboard: `R` reply, `V` resolve, `G` go-to file, `Esc` close editor.

### 5.2 AI Review sub-tab

Two states:

**State A — no session for this PR:** empty area + [ Run AI review ] + explanation line.

**State B — session exists:**

```
┌─ AI Review ────────────────────────────────────────────┐
│ Session "Review PR-1234"   running · 2m 14s            │
│ [Open in agent tab] [Cancel] [Run again ▾]             │
├────────────────────────────────────────────────────────┤
│ Findings (8)                                           │
│                                                        │
│ ┌ BLOCKER · src/foo/Bar.kt:42 ───────────────────────┐ │
│ │ Null-deref on empty list when the cache misses.    │ │
│ │ ```suggestion                                      │ │
│ │   return items.firstOrNull()?.value ?: default    │ │
│ │ ```                                                │ │
│ │ [Edit] [Discard] [Severity ▾] [Push]               │ │
│ └─────────────────────────────────────────────────────┘ │
│ ...                                                    │
├────────────────────────────────────────────────────────┤
│ [Push all kept (6)] [Push as draft (Cloud)] [Clear]   │
└────────────────────────────────────────────────────────┘
```

Severity pill (BLOCKER / NORMAL) with red outline for BLOCKER; click to change via dropdown.
File:line link uses same hybrid navigation as Comments tab.
Suggestion blocks render with pale purple left border and monospace font; no local Apply button.

Streaming: rows fade in as `AiReviewFindingAdded` events arrive from `ai_review.add_finding` calls.

Dedup: the workflow prose asks the agent to call `bitbucket_review.list_comments` and `ai_review.list_findings` before emitting, and to skip findings it judges to match an existing comment on the same file+line. The similarity threshold is a judgment call by the LLM, not a hard-coded algorithmic check — the workflow phrases it as "if a comment on the same line already expresses this concern, skip it". If the agent reports `skipped_duplicates=N` in its completion summary, the UI shows a quiet note.

Per-PR, per-session isolation: findings keyed by `(prId, sessionId)`. Second review archives prior findings; "Show prior runs ▾" surfaces them read-only.

Bulk push: per-write approval per finding; sequential dispatch with per-row progress (pending / in-flight / pushed / failed). Cloud-only *Push as draft* (sets `pending:true`).

Restart re-hydration: `PrReviewSessionRegistry` + `PrReviewFindingsStore` are disk-backed; opening the sub-tab after an IDE restart shows State B exactly as before (minus running state which flips to finished/cancelled depending on last persisted status).

Keyboard: `P` push, `D` discard, `E` edit, `V` toggle severity.

## 6. Services and tools

### 6.1 `PrReviewFindingsStore` (`:core`)

Location: `~/.workflow-orchestrator/{dirName}-{hash6}/agent/sessions/{sessionId}/ai-review-findings.json`
Atomic two-file write (`.tmp` + `Files.move(ATOMIC_MOVE)`). Per-session `kotlinx.coroutines.sync.Mutex`. EventBus publication on every mutation.

```kotlin
data class AiReviewFinding(
    val id: String,
    val prId: String,
    val file: String?,
    val lineStart: Int?,
    val lineEnd: Int?,
    val anchor: AnchorSide?,          // ADDED | REMOVED | CONTEXT | null
    val severity: Severity,            // BLOCKER | NORMAL
    val message: String,
    val pushed: Boolean = false,
    val pushedCommentId: String? = null,
    val pushedAt: Long? = null,
    val discarded: Boolean = false,
    val editedLocally: Boolean = false,
    val createdAt: Long,
    val sessionId: String,
    val archived: Boolean = false
)

interface PrReviewFindingsStore {
    suspend fun add(finding: AiReviewFinding): ToolResult<AiReviewFinding>
    suspend fun update(id: String, mutate: (AiReviewFinding) -> AiReviewFinding): ToolResult<AiReviewFinding>
    suspend fun discard(id: String): ToolResult<Unit>
    suspend fun markPushed(id: String, bitbucketCommentId: String): ToolResult<Unit>
    suspend fun list(prId: String, sessionId: String? = null, includeArchived: Boolean = false): ToolResult<List<AiReviewFinding>>
    suspend fun archiveSession(prId: String, sessionId: String): ToolResult<Unit>
    suspend fun clear(prId: String, sessionId: String): ToolResult<Unit>
}
```

### 6.2 `ai_review` meta-tool (`:agent`)

Core-tier (always registered, no external dependency). Hook-exempt per the `task_*` precedent. No approval gate (local only).

| Action | Params |
|---|---|
| `add_finding` | `pr_id`, `file?`, `line_start?`, `line_end?`, `anchor?`, `severity`, `message`, `suggestion?` |
| `list_findings` | `pr_id`, `include_pushed?=false`, `include_discarded?=false` |
| `clear_findings` | `pr_id`, `session_id` — excluded from `code-reviewer` persona allowlist |

### 6.3 `bitbucket_review` extensions

Add 6 actions to the existing meta-tool:

| Action | Params | DC / Cloud dispatch |
|---|---|---|
| `list_comments` | `pr_id`, `only_open?`, `only_inline?` | DC: `GET .../comments` via activity stream. Cloud: `GET .../pullrequests/{id}/comments` paginated. |
| `get_comment` | `pr_id`, `comment_id` | DC: `GET .../comments/{id}`. Cloud: `GET .../comments/{id}`. |
| `edit_comment` | `pr_id`, `comment_id`, `text`, `expected_version?` | DC PUT with `version`, 409→`STALE_VERSION`. Cloud PUT. |
| `delete_comment` | `pr_id`, `comment_id`, `expected_version?` | DC DELETE `?version=N`. Cloud DELETE. |
| `resolve_comment` | `pr_id`, `comment_id` | DC PUT `state:"RESOLVED"`. Cloud POST `.../resolve`. |
| `reopen_comment` | `pr_id`, `comment_id` | DC PUT `state:"OPEN"`. Cloud DELETE `.../resolve`. |

All mutating new actions use per-write approval. `list_comments` / `get_comment` are read-only.

Sealed `CommentPayload` with per-flavor serializers inside `BitbucketServiceImpl` — agent tool stays flavor-agnostic.

### 6.4 `BitbucketService` interface additions (`:core`)

```kotlin
suspend fun listPrComments(prId: PrId, onlyOpen: Boolean = false, onlyInline: Boolean = false): ToolResult<List<PrComment>>
suspend fun getPrComment(prId: PrId, commentId: String): ToolResult<PrComment>
suspend fun editPrComment(prId: PrId, commentId: String, text: String, expectedVersion: Int? = null): ToolResult<PrComment>
suspend fun deletePrComment(prId: PrId, commentId: String, expectedVersion: Int? = null): ToolResult<Unit>
suspend fun resolvePrComment(prId: PrId, commentId: String): ToolResult<PrComment>
suspend fun reopenPrComment(prId: PrId, commentId: String): ToolResult<PrComment>
```

`PrComment` model in `:core`: includes `id`, `version`, `text`, `author`, `createdDate`, `updatedDate`, `anchor`, `state`, `severity`, `replies`, `permittedOperations`.

### 6.5 `code-reviewer` persona tweak

File: `agent/src/main/resources/agents/code-reviewer.md`.

- Append `ai_review` to the `tools:` frontmatter line.
- Append one conditional instruction to Phase 6 of the Review Pipeline:
  > When invoked for a PR review via the plugin's "Run AI review" button, emit findings via `ai_review.add_finding` instead of the markdown report format. Do not call `bitbucket_review.add_pr_comment` / `add_inline_comment` / `reply_to_comment` — those are triggered by the user from the AI Review tab after review, not by you.

Regenerate the persona snapshot test fixture (`SubagentSystemPromptSnapshotTest` — code-reviewer variant).

### 6.6 `PrReviewTaskBuilder` (`:pullrequest`)

```kotlin
class PrReviewTaskBuilder(
    private val prDetailService: PrDetailService,
    private val bitbucketService: BitbucketService,
    private val ticketContext: TicketContextResolver,
) {
    suspend fun build(prId: PrId, project: Project): ToolResult<String>
}
```

Assembles:

```
You are conducting a PR review. Follow your persona's review pipeline (Phases 1–6) with these adaptations:
- Source of truth for the diff is the <pr_diff> block below — do not re-fetch it.
- Emit each finding as one call to ai_review.add_finding(...). Do NOT produce a markdown report.
- Before emitting, call bitbucket_review.list_comments(pr_id) and ai_review.list_findings(pr_id) to skip duplicates (≥90% match at same file+line).
- Do NOT post to Bitbucket. Pushing is the user's action from the AI Review tab.
- When done (or when there are no findings), call attempt_completion with a 1-sentence summary.

<pr_id>{prId}</pr_id>
<pr_metadata>...</pr_metadata>
<linked_jira_ticket>...</linked_jira_ticket>
<changed_files>...</changed_files>
<pr_diff>...</pr_diff>

Begin.
```

Truncation: diff >~80K tokens truncates at file boundary with marker; changed-file list always full. Jira block omitted if ticket id can't be parsed from branch/title (not an error).

### 6.7 `PrReviewSessionRegistry` (`:pullrequest`)

Project-level service. Map `prId → (sessionId, status)`, persisted to disk at `~/.workflow-orchestrator/{proj}/agent/pr-review-sessions.json` (atomic write).

Used to: re-hydrate AI Review sub-tab State B after restart; disable Run-AI-review button while a session is active for the same PR; surface "prior runs" in the dropdown.

## 7. Flows (summary — see section 5 of brainstorm for detail)

- **A** Run-AI-review happy path: confirm → build prompt → start session → tab-switch → stream findings → edit/push from sub-tab.
- **B** Mid-session steering: existing `ConcurrentLinkedQueue<SteeringMessage>` — no new code.
- **C** Cancel: `AgentService.cancelSession(sessionId)` — already emitted findings stay.
- **D** Ad-hoc "address comments on PR-X" in agent tab: standard ReAct loop + `bitbucket_review` tools + per-write approval on each write. No special workflow.
- **E** Manual reply/edit/resolve in Comments tab: direct `BitbucketService` calls, optimistic updates, no agent.
- **F** DC 409 on edit: 3-way merge modal.
- **G** Restart re-hydration: `PrReviewFindingsStore` + `PrReviewSessionRegistry` on disk.
- **H** Second review on same PR: archives prior findings; "Show prior runs" reveals them read-only.

## 8. Error handling

Principle: no silent corruption. Every write is atomic or optimistic-with-rollback; agent errors return structured `ToolResult.error`; user errors have actionable toasts with recovery path (Settings link, Retry, or fix-connectivity prompt).

| Failure site | Surface | User recovery |
|---|---|---|
| Bitbucket auth expired / invalid | Banner + toast | Link to Settings → Connections |
| Bitbucket 4xx on action | Inline row/comment error + Retry | Fix payload or retry |
| Bitbucket 5xx / timeout | `SmartPoller` backs off; buttons show Retry | Wait / retry |
| DC 409 (stale `version`) on edit/delete | 3-way merge modal (§5.1) | Merge or cancel |
| Agent session start fails | Toast on button click; AI Review stays in State A | Retry; check Sourcegraph token |
| Agent session fails mid-run | Top bar: "Session failed" + Open-in-agent-tab + Run-again | Open session for context |
| `ai_review.add_finding` bad payload | `ToolResult.error("INVALID_PAYLOAD")` back to LLM | Agent self-corrects |
| Finding push to Bitbucket fails | Row red with error + Retry | Per-row retry or Push-all re-dispatch |
| `PrReviewFindingsStore` disk write fails | AgentFileLogger + toast | Free disk / check perms |
| Run-AI-review clicked while session already running for same PR | Button disabled; tooltip | Open existing in agent tab, or cancel |
| Local file at anchored line missing/different | Editor banner + snapshot affordance | View snapshot or proceed |
| Agent hits context limit mid-review | Existing `ContextManager` compacts automatically; emitted findings preserved | None — automatic |
| Cancel during push-all | Already-pushed stay; pending revert to "kept, not pushed" | Click Push-all again |
| Jira ticket resolution fails | `PrReviewTaskBuilder` omits ticket block (not an error) | Review proceeds without ticket context |
| Diff fetch fails | `PrReviewTaskBuilder` returns `ToolResult.error`; toast on button click | Fix connectivity; retry |

DC `version` conflicts surface as `STALE_VERSION` with the server's latest comment attached, so UI can render the 3-way merge modal and the agent (via `bitbucket_review`) can fetch-and-retry.

## 9. Testing

Per TDD = test-requirements rule:

**Unit tests** — `PrReviewFindingsStore` (Mutex concurrency, atomic write survival), `AiReviewTool` (schema + state), new `bitbucket_review` actions (DC + Cloud payload shapes, 409 handling), `PrReviewTaskBuilder` (assembly + truncation + missing-Jira graceful skip).

**E2E scenario tests (from spec):**
1. Happy path against a dev Bitbucket.
2. Restart preserves unpushed findings.
3. Run-again archives prior.
4. Cancel preserves emitted.
5. DC 409 surfaces merge modal.
6. Push failure on one row does not block siblings.
7. Dedup: zero duplicate pushes when re-run against existing comments.
8. Ad-hoc agent path succeeds without pr-review involvement.

**Snapshot:** regenerate `code-reviewer` persona variant in `SubagentSystemPromptSnapshotTest`. Do not add snapshots for `PrReviewTaskBuilder` output (data-dependent).

**Regression:** exercise the pre-existing 6 `bitbucket_review` actions after the extension lands.

## 10. Phase 0 audit — blocking

User has flagged the existing `bitbucket_review` + `bitbucket_pr` meta-tools as *"could be broken, never tested"*. Phase 0 is therefore not a rubber-stamp — it's a verification + correctness pass that may uncover the need to rewrite endpoints against the authoritative API reference (`memory/reference_bitbucket_pr_comment_api.md`).

Deliverable: `docs/superpowers/audits/2026-04-22-bitbucket-tools-audit.md`.

Scope — for each of the ~20 PR-adjacent actions across `bitbucket_review` + `bitbucket_pr`, **plus** the existing diff-fetch method used by `PrDetailService`:

1. Read tool source + underlying `BitbucketService` method.
2. Cross-reference against the Bitbucket Data Center API reference (endpoint path, HTTP method, required headers, request body shape, version handling on mutating calls).
3. Count unit + integration tests.
4. Execute against a real Bitbucket DC sandbox (and Cloud if available) using a known PR — record actual response / error.
5. Classify each action:
   - **OK** — works, tested, matches spec
   - **FIX** — works for happy path but has edge-case bugs (wrong error handling, missing version field, etc.)
   - **BROKEN** — does not work against real API; endpoint shape or auth wrong
   - **MISSING** — method doesn't exist or throws `NotImplementedError`

Decision gate:
- If ≤ 25% of actions are BROKEN/MISSING → proceed; fix-in-place in Phase 1.
- If 25–50% → pause, re-scope Phase 1 to include substantial rewrite work.
- If > 50% → escalate to user: consider rebuilding `bitbucket_review` as a new sibling meta-tool from scratch, with the old one deprecated on a path.

Outputs feed Phase 1 (FIX + BROKEN tasks) and Phase 2 (MISSING alongside the 6 new comment actions).

## 11. Rollout plan

| Phase | Scope | Exit criteria |
|---|---|---|
| 0 | Bitbucket tools audit | Audit doc merged; decision gate passed |
| 1 | `PrReviewFindingsStore`, new `BitbucketService` methods, Phase-0 fixes, unit tests | All green on `./gradlew :core:test :pullrequest:test`; no UI visible |
| 2 | `ai_review` meta-tool, `bitbucket_review` 6 new actions, `code-reviewer` persona tweak, snapshot update | Tool tests green; snapshot regenerated |
| 3 | Comments sub-tab UI + poller + unread badge + snapshot viewer | Manual verify: post/reply/edit/delete/resolve against dev Bitbucket |
| 4 | AI Review sub-tab + Run button + `PrReviewTaskBuilder` + `PrReviewSessionRegistry` + streaming + bulk push | Flow A end-to-end works against dev Bitbucket |
| 5 | Polish: DC 409 merge modal, restart re-hydration, second-review archival, ad-hoc agent path verified | All 8 E2E scenarios pass |
| 6 | Release: bump patch, `./gradlew clean buildPlugin`, `gh release create` with ZIP. Update `:pullrequest/CLAUDE.md` + `docs/architecture/` in same commit as changes. | Release created |

## 12. Out of scope (explicit)

- Attachments / images (API doesn't support).
- Reactions / emoji (API doesn't support).
- Cloud commit-SHA anchoring (API doesn't support).
- Settings UI / per-repo overrides.
- Gutter-marker editor decorations.
- Separate "address PR comments" agent workflow (the agent already has the tools and ReAct loop).

## 13. Resolved / open questions

**Resolved:**
1. ~~Does `BitbucketService.getPrDiff` exist?~~ — A diff-fetch method signature exists on `BitbucketService` / `BitbucketServiceImpl` / `PrDetailService`. Per user: *"could be broken, never tested"* → Phase 0 audits correctness against real Bitbucket DC; may require rewrite.
2. ~~Is `TicketContext` accessible from `:pullrequest`?~~ — Yes. Lives in `:core` at `core/workflow/TicketContext.kt`; already used by `:pullrequest`. No module promotion needed.

**Open, resolved by Phase 0 outcome:**
3. If Phase 0 finds `bitbucket_review` actions fundamentally broken, rebuild as sibling meta-tool or fix in place? — Default: **fix in place**. Escalation threshold is >50% BROKEN/MISSING in the audit (per §10 decision gate). Decision held until audit data is available.

## 14. References

- `memory/reference_bitbucket_pr_comment_api.md` — endpoint map for DC + Cloud
- `agent/CLAUDE.md` — tool registry, persona system, skill system
- `pullrequest/CLAUDE.md` — existing service structure
- `CLAUDE.md` (root) — architecture rules (`core interface → ToolResult<T> → feature impl → agent tool wrapper`)
