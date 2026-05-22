# Cross-IDE Agent Delegation — Design Spec

**Status:** Draft for review · **Date:** 2026-05-22 · **Scope:** v1

## 1. Purpose

Enable an agent running in one IntelliJ instance (Agent-A in IDE-A, holding repo-A) to delegate work to another agent running in a separate IntelliJ instance (Agent-B in IDE-B, holding repo-B) on the same machine, owned by the same user.

The motivating flow: a single developer has two related repos open in two IntelliJ windows (e.g., `backend-api` and `frontend-app`). A task in repo-A requires changes in repo-B. Today, the developer has to manually copy context between sessions. With this feature, Agent-A can hand the repo-B slice to Agent-B with a briefing, continue its own work, and receive a summary back when Agent-B finishes.

## 2. Non-goals (v1)

These are deferred — not "we won't ever do them," but "v1 ships without them":

1. **Cross-machine delegation.** Local-only IPC. No network protocol.
2. **Cross-user delegation.** Same OS user only.
3. **Resume a delegated session across an IDE-B restart.** Once IDE-B dies, the handle is dead.
4. **Multi-hop chains (A → B → C).** Agent-B cannot itself delegate while in a delegated session. The delegation tools are absent (or reject with a clear error) inside delegated sessions.
5. **Per-delegation tool restrictions.** Agent-B runs with whatever per-tool auto-approve config IDE-B's human has set. Agent-A cannot impose "read-only" or "no run_command" scopes on Agent-B. Note: a one-time *Accept delegation?* prompt in IDE-B gates the session before Agent-B begins executing (§3.2, §6.1, §6.2) — this is the v1 trust gate. Per-tool restrictions are a future refinement.
6. **Encryption / TLS on the IPC.** Local-only, filesystem-permissioned socket files (mode 0700 in the user's home dir).
7. **Conversation-as-default delegation.** First-contact is one-shot; multi-turn requires explicit `continue_with`. We do not implicitly maintain long-lived channels.
8. **Delegation to non-plugin IDEs.** Only IntelliJ instances with this plugin installed. No VSCode, no Cursor, no IntelliJ without the plugin.
9. **Web / system-notification / Slack surfaces.** Everything happens in IDE chrome.

Explicitly NOT what this feature is: it is not Code With Me, JetBrains Gateway, or a pair-programming tool. It is not a way to share an editor with a colleague. It is the same developer, on the same machine, with two repos in two IDE windows.

## 3. User-visible behavior

**Feature is opt-in via two settings (§3.3): *outbound delegation* and *inbound acceptance*. Both default off.** Users must explicitly enable each in `Tools → Workflow Orchestrator` before the feature has any effect. This section describes behavior with the relevant gate(s) enabled.

### 3.1 The human's view in IDE-A

When Agent-A wants to delegate, the user sees a **delegation tool-call card** in the chat (the same UI lane as other tool calls). The card progresses through visible states:

- *Waiting for delegation target…* — picker is open
- *Connecting to `frontend-app` (IDE-B)…* — IPC handshake in flight
- *`frontend-app` working…* — Agent-B iterating; live status updates as steps complete
- *`frontend-app` asked a question…* — Agent-B is paused waiting for an answer
- *`frontend-app` completed* / *canceled* / *failed* / *timed out* — terminal states with summary inline

While the card is active, Agent-A's own loop continues normally — the user can keep chatting with Agent-A and it can do other work in repo-A. If the user cancels Agent-A's session, all open delegation cards cascade-cancel to their respective IDE-B sessions (see §6.4).

If Agent-B asks a clarifying question that Agent-A cannot answer, the question surfaces in IDE-A's main chat as a normal user-clarification prompt, tagged `[delegated · frontend-app]` so the user knows where it came from. The user's answer is forwarded back through Agent-A to Agent-B automatically.

### 3.2 The human's view in IDE-B

When IDE-B receives a delegation, a **new session tab** appears in their chat panel labeled `Delegated by IDE-A — backend-api session`, with an "incoming" badge so it's distinguishable from sessions the user started themselves. The tab opens with an **Accept delegation?** prompt overlay showing the incoming briefing and the delegating IDE/repo. Agent-B does NOT start executing until the human clicks **Accept**. The human may also **Reject** — in which case the delegation is canceled, the Agent-B session is not created, and Agent-A is notified (state: `REJECTED`). For continuations on an existing accepted channel (`continue_with`), the prompt is skipped — the human already accepted this channel when it was first established.

Once accepted, the human has the same controls as on their own sessions:
- Watch the stream live
- Pause / cancel
- Take over (switch the session into user-driven mode, ending the delegation)
- Answer Agent-B's clarifying questions directly (short-circuits the routing back to Agent-A — see §6.3)

Their own ability to start new chats and use Agent-B for their own work is unaffected. The delegated session is one of N concurrent sessions.

### 3.3 The agent's view (Agent-A)

Agent-A interacts via four tools, all under the `delegation_*` namespace:

| Tool | Purpose |
|---|---|
| `delegation_send` | Send a request — first contact, or continuation if `continue_with` is supplied. Returns immediately with a handle; never blocks Agent-A's loop. |
| `delegation_answer` | Send a reply to a clarifying question Agent-B raised. |
| `delegation_close` | Explicitly finalize a delegation channel. Idempotent. |
| `delegation_fetch_transcript` | Retrieve the full message history of a delegated session, live or closed. |

Agent-A does NOT poll for results. Result delivery is push-driven via the existing nudge mechanism (see §6.5).

Settings exposed to the user (under `Tools → Workflow Orchestrator`):

- **Allow this IDE to delegate to other IDEs** (off by default) — outbound gate. When off, the four `delegation_*` tools are NOT registered in Agent-A's tool registry; the LLM does not know delegation exists. No picker, no outbound IPC. Toggling off mid-session leaves any in-flight nudges deliverable, but new `delegation_*` calls fail with `DelegationOutboundDisabled` (§10).
- **Accept incoming delegations from other IDEs** (off by default) — inbound gate. When off, the plugin does NOT bind the deterministic per-project socket when projects open. Other IDEs probing for liveness see this IDE as Closed (§5.2) and indistinguishable from "not running." Toggling off mid-session keeps existing accepted channels live until they naturally terminate; new inbound `CONNECT` requests are refused.
- **Auto-approve Agent-A's answers in delegations** (off by default) — when on, Agent-A's proposed answer to a question Agent-B raises is forwarded without an IDE-A confirmation prompt. See §6.3.

## 4. Tool surface (detailed)

### 4.1 `delegation_send`

| Arg | Required | Description |
|---|---|---|
| `request` | Yes | Free-form briefing — what's been done, what Agent-B should do, any context Agent-B needs. Same shape as a user prompt. |
| `suggested_repo` | No | Hint for picker pre-selection (e.g., `"frontend-app"`). Picker uses it to pre-highlight a match in recents; the human always confirms on first contact. Ignored when `continue_with` is supplied. |
| `continue_with` | No | An existing channel handle. If provided, the picker is skipped and the message is appended to the existing Agent-B session as a new user turn. If the handle is dead, the tool returns `DelegationExpired` immediately. |

**Returns** (synchronously):
```
{ handle: <opaque>, status: "running" | "picking" | "expired", repo: <name>, ide_pid: <int> }
```

The tool returns as soon as the message has been accepted by IDE-B (or, for first-contact, as soon as the picker is opened). The actual Agent-B work happens asynchronously; results come back via nudges (§6.5).

### 4.2 `delegation_answer`

| Arg | Required | Description |
|---|---|---|
| `handle` | Yes | The channel handle. |
| `response` | Yes | Agent-A's answer to the most recently-raised question on this channel. |

Used by Agent-A when it has formed an answer to a question Agent-B raised. Separate from `delegation_send` because the protocol semantics differ — `send` initiates work, `answer` unblocks a paused loop. If no question is pending, returns `DelegationNoPendingQuestion`.

**Race semantics with the IDE-B short-circuit answer (§6.3):** the IPC layer maintains a per-channel *pending question token* using compare-and-clear. Whichever answer (Agent-A's via `delegation_answer`, or the IDE-B human's via the session tab) arrives first wins; the other receives `DelegationNoPendingQuestion`. There is no double-answer path. Implementers MUST use atomic compare-and-clear; a non-atomic check-then-clear is a known race.

### 4.3 `delegation_close`

| Arg | Required | Description |
|---|---|---|
| `handle` | Yes | The channel to close. |
| `reason` | No | Optional note recorded on IDE-B side. |

Triggers Agent-B to finalize: write its summary, mark its session tab as complete, free channel resources. Idempotent. Closing an already-closed channel is a no-op success.

### 4.4 `delegation_fetch_transcript`

| Arg | Required | Description |
|---|---|---|
| `handle` | Yes | Channel handle (live or already-closed). |

Returns the full message history of the delegated Agent-B session — every assistant turn, every tool call, every tool result. Used when the summary-level result isn't enough. Available both during and after the session.

### 4.5 Why not `delegation_list_active`

Not included in v1. Agent-A tracks its own handles via its own session state; the recent-projects picker in IDE-A's UI provides a human-visible list of running delegations. A list tool would only matter for an Agent-A that mis-tracked its own handles, which is a bug to fix rather than an API to add. Easy to add later if needed.

## 5. Discovery & picker

### 5.1 Source of project listings

The picker is populated from `RecentProjectsManager.getInstance()` — the same projects the user sees in `File → Recent Projects → Manage Recent Projects…`. We do **not** maintain a separate presence file or background registry.

**Cross-installation supplement.** `RecentProjectsManager` is per-IntelliJ-installation; different JetBrains Toolbox slots maintain different recents lists, so a project open in IDE-B may be absent from IDE-A's recents. To cover this, the picker also globs `~/.workflow-orchestrator/ipc/*.sock` for live plugin instances. Entries discovered this way but not in `RecentProjectsManager` are shown under a *"Discovered (not in recents)"* section in the picker. The project path is resolved by asking the responding plugin via the IPC `PING` handshake (it includes the project path in the `PONG` reply).

**Inbound-disabled IDEs are invisible.** An IDE with the *Accept incoming delegations* setting (§3.3) turned off does not bind its deterministic socket — so the socket-glob discovery sees nothing for that IDE, and probes for its currently-open projects time out as if the IDE were not running. This is by design: a user who has opted out of accepting delegations should not appear as a delegation target.

### 5.2 Liveness detection

Each plugin instance, when it opens a project, starts listening on a **deterministic IPC endpoint derived from the project's absolute path** (e.g., a Unix Domain Socket at `~/.workflow-orchestrator/ipc/<sha256(projectPath).short>.sock`). When the project closes, it stops listening. The OS reaps the socket file on process death — no stale-file cleanup needed.

To determine "is this project currently open in some running IDE?":

1. Compute the deterministic IPC path for the project root.
2. Attempt a non-blocking connect with a `PING` exchange, ~50ms timeout.
3. `PONG` → **Running**. Connection refused / timeout → **Closed** (which could mean: IDE not running, project not open in any running IDE, or the target IDE has *Accept incoming delegations* disabled — these are deliberately indistinguishable). Project path doesn't exist on disk → **Missing**.

Probing happens **on picker open**, not in a background loop. N probes for N recent projects is fine as a one-shot dialog cost; it would be unacceptable as a continuous poll.

### 5.3 Picker UI

A modal dialog in IDE-A's main window, listing recent projects. Columns:

| Column | Value |
|---|---|
| Project name | From `RecentProjectsManager.getDisplayName(path)` |
| Path | Truncated with tooltip |
| Status badge | `● Running` / `○ Closed` / `⚠ Missing` |
| Last opened | Relative time |

A search box filters by name or path. Below the list, two action buttons:
- **Delegate** — enabled when a Running row is selected
- **Launch & Delegate** — enabled when a Closed row is selected

If `suggested_repo` is set, matching rows are pre-highlighted at the top of the list. The user still must click to confirm.

### 5.4 Closed-repo flow (auto-launch with manual fallback)

When the user picks a Closed row and clicks **Launch & Delegate**:

1. Plugin spawns a new IntelliJ process at the project path:
   - Resolve launcher via `PathManager.getHomePath()` plus platform-specific suffix (`bin/idea.sh` on mac/linux, `bin/idea64.exe` on Windows)
   - `ProcessBuilder` with the project path as argv
2. Plugin polls the deterministic socket every 500ms for up to **90 seconds**. The picker shows a progress indicator (*"Waiting for IDE-B to start… 45s / 90s"*) and a Cancel button so the user can abort early if the launch is clearly failing.
3. **Success:** socket comes up green → picker auto-progresses → delegation is dispatched.
4. **Failure** (timeout, launcher missing, spawn error, socket never comes up): fall through to the manual flow with a clear inline reason. The user opens the project manually (`File → Recent Projects` works, or double-click in their file manager), then clicks **Retry probe**. Once the probe goes green, the Delegate button enables.

**Named risk — JetBrains Toolbox launcher resolution.** `PathManager.getHomePath()` returns the *current* IDE's install root. JetBrains Toolbox installs each IDE under a versioned, IDE-flavored path (e.g., `…/Toolbox/apps/IDEA-U/ch-0/<version>/`). If IDE-A is Ultimate 2025.1 and IDE-B's project was last opened with Community 2024.3, blindly reusing IDE-A's launcher will spawn the wrong IDE binary, which may fail to open the project or may silently use a different toolchain than the user expects.

v1 mitigation: detect Toolbox layout (presence of `…/Toolbox/apps/…/ch-0/` in `PathManager.getHomePath()`). If detected, check the project's IntelliJ workspace metadata for the IDE flavor/version last used. If a mismatch is detected, show a confirm dialog: *"This project was last opened with `IDEA Community 2024.3`. Auto-launch will use `IDEA Ultimate 2025.1`. Continue anyway, or open manually?"* If unknown (no recorded last-used flavor), proceed with a softer banner inside the picker, and let the user cancel into the manual path. **Auto-launch must never silently spawn a mismatched IDE.**

### 5.5 Missing-repo handling

`⚠ Missing` rows are disabled with a tooltip explaining the path no longer exists. They are not removed from `RecentProjectsManager` automatically — the user removes them through the standard IntelliJ "Manage Recent Projects" UI.

## 6. Behavior & data flow

### 6.1 Lifecycle states

```
PICKING → CONNECTING → AWAITING_ACCEPT → RUNNING ⇄ AWAITING_ANSWER → COMPLETED
                            │
                            └→ REJECTED
                                       │                            │
                                       └──── CANCELED / FAILED / TIMED_OUT ───┘
```

| State | Meaning |
|---|---|
| `PICKING` | First-contact only. Picker dialog open in IDE-A. |
| `CONNECTING` | Picker dismissed; IPC handshake in flight (or auto-launch waiting for IDE-B to boot). |
| `AWAITING_ACCEPT` | Connection established; delegation session tab open in IDE-B with the *Accept delegation?* prompt. Agent-B is NOT yet executing. Waits for the IDE-B human to Accept or Reject. Skipped on `continue_with` reuse. |
| `RUNNING` | Agent-B is iterating. Session tab live in IDE-B. |
| `AWAITING_ANSWER` | Agent-B raised a question. Either Agent-A is forming a proposed answer, or it's awaiting human confirmation in IDE-A (or the IDE-B human is answering directly). Agent-B's LLM loop is paused. |
| `COMPLETED` | Agent-B finished naturally (called `attempt_completion`). Summary delivered to Agent-A. |
| `REJECTED` | The IDE-B human declined the incoming delegation via the *Accept delegation?* prompt. Agent-A is notified. Terminal. |
| `CANCELED` | Canceled mid-work — IDE-A side (Agent-A's session canceled, cascades), IDE-B side (human clicked stop on the running delegated tab), or Agent-A via `delegation_close` on a live session. |
| `FAILED` | Unrecoverable error in IDE-B (tool errors Agent-B couldn't handle, crash, project window closed mid-session, IPC connection lost, etc.). |
| `TIMED_OUT` | No message exchange for the configured idle window (~30 min default; configurable). |

**Prerequisite for implementation — per-session plan mode.** This spec assumes plan-mode state is *per-session*. The current `:agent` implementation tracks `AgentService.planModeActive` as an `AtomicBoolean` at the service level (per CLAUDE.md, "Plan Mode" section), which would mean a delegated session inherits or clobbers IDE-B's existing plan-mode toggle. Before this spec can ship, plan-mode must be moved into per-session state. This is called out here so the implementation plan picks it up as a hard prerequisite, not a v2 cleanup.

### 6.2 The send flow

```
Agent-A calls delegation_send
  │
  ├── continue_with provided?
  │     ├── yes → skip picker AND Accept prompt, route to existing handle
  │     └── no  → show picker in IDE-A (state: PICKING)
  │              │
  │              └── human picks target
  │
  ├── attempt IPC to target IDE (state: CONNECTING)
  │     ├── live with repo open → connect
  │     └── otherwise → Launch & Delegate
  │       (auto-launch a new IDE process for that project;
  │        see §5.4 for timing, progress UI, Toolbox caveats)
  │
  ├── IDE-B opens session tab with Accept prompt (state: AWAITING_ACCEPT)
  │     ├── human Accepts → proceed
  │     └── human Rejects → state: REJECTED, Agent-A notified, terminal
  │
  ├── deliver message into Agent-B's session (state: RUNNING)
  │     ├── new session → first user turn
  │     └── existing session → next user turn (steering-queue style if Agent-B is mid-stream)
  │
  └── return { handle, status: "running" } to Agent-A immediately
```

### 6.3 Question routing

When Agent-B emits a clarifying question, the question is **always surfaced to the human in IDE-A** by default, with Agent-A's proposed answer pre-filled for one-click confirmation. This avoids the LLM-hallucination failure mode where Agent-A would otherwise generate a plausible-sounding answer without realizing it's wrong, and that wrong answer would silently flow back to Agent-B.

Flow:

```
Agent-B emits question
  │
  ├── IPC delivers "question event" on the channel
  │
  ├── Agent-A's loop picks it up via addNudgeMessage:
  │   "Delegated session {handle} asks: <question>"
  │
  ├── Agent-A's LLM forms a proposed answer (or declares "I don't know")
  │
  ├── Question + proposed answer surface in IDE-A's chat as a
  │   clarifying prompt, tagged "[delegated · frontend-app]":
  │     "frontend-app asks: <question>
  │      Suggested answer: <Agent-A's proposed text>
  │      [Confirm & send]   [Edit]   [Write my own]   [Cancel delegation]"
  │
  ├── Human one-clicks Confirm (or edits, or writes their own) →
  │   answer routed back through Agent-A → Agent-B
  │
  └── Agent-B's loop resumes (state: AWAITING_ANSWER → RUNNING)
```

**Opt-in auto-approve.** Users who explicitly trust Agent-A's answers can enable the per-session or global setting *Auto-approve Agent-A's answers in delegations* (§3.3). With this on, Agent-A's proposed answer is forwarded to Agent-B as soon as Agent-A's LLM forms it, without the human-confirmation step. Off by default. This is the only configuration in which the original "Agent-A as user-proxy" flow operates — and it is opt-in precisely because LLM-driven answers without human verification can introduce silent errors.

**Short-circuit:** the human in IDE-B can answer Agent-B's question directly via the session tab. If they do, the question is satisfied before the IDE-A confirmation prompt is rendered (or before Agent-A's auto-approve answer arrives); the IPC channel emits a "question canceled — answered locally" event, the IDE-A prompt is dismissed automatically, and Agent-B's loop resumes. See §4.2 for the compare-and-clear race semantics that prevent double-answers.

### 6.4 Cancel propagation

Cancel sources and their effects:

| Cancel source | Effect on the delegation |
|---|---|
| Agent-A calls `delegation_close` on a live session | State: `CANCELED` with `closeReason: "closed_by_delegator"`. Agent-B writes a partial-progress summary before exit. Idempotent on already-terminal sessions (no-op success). |
| Agent-B reaches `attempt_completion` naturally | State: `COMPLETED`. Summary + files-changed delivered to Agent-A. |
| Human in IDE-B Rejects the *Accept delegation?* prompt | State: `REJECTED`. Agent-A notified eagerly. Agent-B does not execute at all. |
| Agent-A's session is canceled by the human in IDE-A | All open child channels receive a "parent canceled" signal. Each Agent-B session moves to `CANCELED` with cleanup. Session tab in IDE-B shows "Canceled: parent session ended." |
| Human in IDE-B clicks stop on the running delegated tab | Agent-B's loop cancels. State: `CANCELED`. Agent-A receives a `CANCELED` nudge with the reason. |
| Project window closed in IDE-B (project disposal) | All delegated sessions hosted in that project transition to `FAILED { reason: "project_closed" }`. The IPC listener for that project shuts down and its deterministic socket disappears. Agent-A receives the `FAILED` nudge eagerly. This is distinct from full IDE-B process death — the IDE may still be alive with other projects loaded. |
| IDE-B's process dies / IPC connection lost | Channel transitions to `FAILED` from Agent-A's perspective. The Agent-B session may persist in IDE-B's on-disk session history (it was a real session) but is no longer recoverable as a live channel. |
| Idle timeout (~30 min default) | State: `TIMED_OUT`. Agent-A is notified eagerly if running, lazily otherwise. |

### 6.5 Close-event delivery to Agent-A

For every terminal state (`COMPLETED`, `CANCELED`, `REJECTED`, `FAILED`, `TIMED_OUT`):

- **Eager delivery:** if Agent-A is actively iterating, a nudge is injected into its conversation via `addNudgeMessage`. For `COMPLETED`, the nudge includes the summary + files-changed + branch/commit ref inline so Agent-A can act on the result immediately.
- **Lazy delivery:** if Agent-A is paused/checkpointed when the event fires, it's persisted on the channel and replayed when Agent-A resumes. Additionally, any subsequent `delegation_send(continue_with=deadHandle, …)` or `delegation_answer(deadHandle, …)` returns `DelegationExpired` with the recorded reason.

### 6.6 Concurrency

Agent-A may hold up to **5 open channels concurrently** — matching the existing parallel sub-agent ceiling in `:agent`. Each channel is independent; each has its own handle. The 6th `delegation_send` (without `continue_with`) returns a `DelegationLimitReached` error until one of the live channels closes.

There is no concurrency limit on the IDE-B side beyond what already governs normal sessions — IDE-B can host an arbitrary number of concurrent sessions including delegated ones, just like it can today with user-driven sessions. In aggregate, an IDE-B instance can receive up to `N × 5` concurrent delegated sessions, where N is the number of distinct IDE-A instances reaching it. If resource pressure is observed in practice, a per-IDE-B incoming cap can be added without a protocol change.

## 7. Response shape

When a delegated session reaches `COMPLETED`, the nudge to Agent-A contains:

```
{
  status: "completed",
  handle: <opaque>,
  repo: "frontend-app",
  summary: "<natural-language report from Agent-B>",
  files_changed: ["src/api/users.ts", "src/api/users.test.ts"],
  branch: "feature/users-endpoint" | null,
  commit: "abc123def" | null,
  duration_seconds: <int>,
  tokens_used: { input: <int>, output: <int> }
}
```

The full transcript is fetchable separately via `delegation_fetch_transcript` if Agent-A needs it (e.g., to debug a partial success or understand a surprising decision Agent-B made).

For terminal non-success states, the response is similar but the `summary` may be a partial-progress description and `status` is one of `canceled` / `rejected` / `failed` / `timed_out` with a `reason`. For `rejected`, `summary` is empty and `files_changed` is `[]` (Agent-B never executed).

## 8. Trust scope

Agent-B runs a delegated session with **the same tool access and per-tool auto-approve settings** that IDE-B's human has configured for their own sessions. There is no separate "delegated session" permission profile. The reasoning is the "same human on both IDEs" assumption — trust is uniform.

The human in IDE-B can intervene at any time (cancel, take over, answer questions) by virtue of the session tab being live and interactive. That is the trust safety valve.

## 9. Persistence

### 9.1 IDE-B side

A delegated session is a **first-class `AgentSession`** in IDE-B, stored identically to user-driven sessions:

```
~/.workflow-orchestrator/{proj}/agent/
├── sessions.json
└── sessions/{delegatedSessionId}/
    ├── api_conversation_history.json
    ├── ui_messages.json
    └── checkpoints/
```

The session's metadata records the delegation:

```json
{
  "id": "abc-123",
  "title": "Add createUser client + tests",
  "delegated": {
    "delegatorIde": "<IDE-A pid + project hash>",
    "delegatorRepo": "backend-api",
    "delegatorSessionId": "xyz-789",
    "startedAt": "2026-05-22T14:32:00Z",
    "closedAt": "2026-05-22T14:51:00Z",
    "closeReason": "completed"
  }
}
```

The presence of the `delegated` key drives UI affordances:
- The session list shows an "incoming" badge on the row.
- The session detail view shows a banner: *"Delegated by IDE-A (backend-api). Started 14:32, completed 14:51."*

After the session closes, it remains in history (subject to the user's normal session-retention settings). The human in IDE-B can scroll back, re-open the transcript, or fork it into a new session to continue the work themselves.

### 9.2 IDE-A side

Agent-A's own session records the delegation handles it has held. On session checkpoint + resume, the handles are restored — but liveness is re-probed on next use (§6.5).

### 9.3 Handle stability across restarts

| Event | Handle behavior |
|---|---|
| Agent-A's session checkpoints and resumes | Handle survives in persisted state. Liveness re-probed on next use. |
| IDE-A process restarts | Handle survives in Agent-A's persisted session state, but the live IPC channel is gone. First subsequent use re-probes IDE-B; if IDE-B is still running and the session is still around, the explicit re-association protocol below establishes a new live channel to the same on-disk session. If IDE-B has restarted or the session is gone, returns `DelegationExpired`. |
| IDE-B process restarts | Handle is dead. The Agent-B session is closed by IDE-B's natural shutdown flow (same as a user session being interrupted by IDE close). Agent-A receives `DelegationExpired` on next use. Resuming a delegated session across an IDE-B restart is explicitly a non-goal for v1 (§2). |

**Re-association protocol (after IDE-A restart).** When Agent-A's first subsequent tool call (typically `delegation_send` with `continue_with` or `delegation_fetch_transcript`) targets a persisted handle:

1. IDE-A computes the deterministic IPC socket path for the handle's target project and probes it (§5.2).
2. On `PONG`, IDE-A sends `CHANNEL_RESUME { sessionId: <handle.sessionId>, lastSeenState: <handle.lastKnownState> }`.
3. IDE-B looks up the session by `sessionId`:
   - **Live** (state `AWAITING_ACCEPT` / `RUNNING` / `AWAITING_ANSWER`) → respond `CHANNEL_RESUMED { sessionId, currentState }`. IDE-A re-attaches. Agent-A receives a single coalesced nudge synthesizing any events (close, completion, question raised) that fired while detached.
   - **Terminal** (`COMPLETED` / `CANCELED` / `REJECTED` / `FAILED` / `TIMED_OUT`) → respond `SESSION_CLOSED { sessionId, closeReason, summary? }`. IDE-A treats the handle as expired and emits `DelegationExpired` to Agent-A on next tool use, with the recorded reason and summary in the error payload (so Agent-A still gets the outcome even if it missed the live close event).
   - **Unknown** (session was never seen, or has been pruned from history) → respond `SESSION_NOT_FOUND`. IDE-A treats the handle as expired.
4. The protocol is idempotent: repeated `CHANNEL_RESUME` for the same `sessionId` is safe and returns the current state.

### 9.4 Logs

Existing `:agent` 7-day log retention applies to delegated sessions on both ends.

- IDE-A side logs every `delegation_*` tool call (args, return, handle) in Agent-A's session log.
- IDE-B side logs the inbound delegation message + every Agent-B tool call in the delegated session's log.

Lined up by handle, the full exchange is reconstructable from disk.

## 10. Error model

Specific error kinds Agent-A may receive from delegation tools:

| Error | When |
|---|---|
| `DelegationExpired` | `continue_with` handle is no longer live (timed out, canceled, IDE-B gone). Includes recorded `reason`. |
| `DelegationLimitReached` | Agent-A already has 5 open channels. |
| `DelegationTargetNotReachable` | Picker target IDE not running and auto-launch failed or was canceled by the user. |
| `DelegationNoPendingQuestion` | `delegation_answer` called when no question is pending on the handle. |
| `DelegationUserCanceledPicker` | First-contact picker was dismissed without picking. |
| `DelegationOutboundDisabled` | The *Allow this IDE to delegate to other IDEs* setting (§3.3) was turned off after Agent-A's session started, so the LLM still has the `delegation_*` tools cached but their runtime is gated. New calls fail; in-flight nudges still deliver. |

All errors are clearly distinguishable so Agent-A's LLM can reason about whether to retry, fall back, or escalate to the human.

## 11. Out of scope, repeated for emphasis

This v1 spec covers the local same-user, same-machine, same-plugin case only. Anything that crosses a machine boundary, a user boundary, or a plugin boundary is out of scope. Anything that requires resuming a delegated session across an IDE-B restart is out of scope. Anything that lets Agent-B itself delegate further (multi-hop) is out of scope.

See §2 for the full list.

## 12. Open questions (none blocking)

None at spec-write time. Implementation will surface implementation-level questions (e.g., exact socket framing, exact deterministic-path hashing scheme, exact format of the workspace-state IDE-version marker for the Toolbox flavor check) — those belong in the implementation plan, not here. The JetBrains Toolbox launcher-resolution risk previously listed here has been promoted to a named risk in §5.4.

---

*This spec deliberately leaves the IPC transport choice (UDS via JEP 380 was the previous research conclusion) and concrete tool-registry wiring to the implementation plan. The "what" lives here; the "how" lives there.*
