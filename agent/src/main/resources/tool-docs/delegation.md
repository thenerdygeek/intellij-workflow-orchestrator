# `delegation` — extended notes

## Why this is a meta-tool

Five verbs share one schema entry: `send`, `close`, `answer`, `fetch_transcript`,
and `list_targets`. They are bundled because every action operates on the same
anchor — a delegation *channel* identified by a handle — and shares the same
substrate: `DelegationOutboundService` over a local Unix-domain-socket (UDS) IPC
transport to another running IntelliJ instance. Splitting them into five sibling
tools would scatter the channel-lifecycle and settings-gating logic and add
schema tokens *every iteration*, even though most sessions never delegate at all.

This mirrors the `runtime_exec` / `jira` consolidation pattern: the action-enum
keeps the cost flat and presents "coordinate with another IDE" as one capability
surface — the natural mental model.

The tool is also **conditionally registered**: it only appears when
`PluginSettings.enableOutboundCrossIdeDelegation` is on, and it is re-registered
dynamically when that setting is toggled (no IDE restart). The settings gate is
re-checked once at the top of `execute()` so a setting flipped off mid-session
fails closed with `DelegationOutboundDisabled`.

## What "cross-IDE" actually means here

This is **local-only, same-machine, same-user** delegation. "Another IDE" means a
second IntelliJ process the same user is running on the same box, with a
*different repository* open. The two processes talk over a UDS socket whose path
is derived from the target project path (`DelegationPaths.socketFor(path)`); there
is no HTTP, no TCP, no network egress.

Explicit v1 non-goals (do not suggest these as fixes): cross-machine,
cross-user, multi-hop (B delegating onward to C), per-delegation tool
restrictions, encryption, and non-plugin IDEs.

Despite being localhost IPC, the blast-radius classification is `NETWORK`, not
`AGENT_CONTROL`: the call crosses the process boundary and causes *another user's
IDE* to pop a consent dialog and run an agent task. That is real outbound effect —
`AGENT_CONTROL` is reserved for tools with "zero blast radius outside the agent
loop" (`use_skill`, `task_update`), which delegation plainly is not.

## The async-result model is the part the LLM gets wrong

`send` does **not** return the remote agent's work. It returns immediately with a
one-line header:

```json
{"handle":"<id>","status":"running","repo":"<targetRepoName>"}
```

The actual result is delivered *later*, out of band, as a system **nudge**
injected into the delegator's loop when the remote session terminates:

```
[DELEGATION RESULT — <repo> (<shortId>)]
Status: COMPLETED | CANCELED | REJECTED | FAILED
Summary: ...
Files changed (N): ...
Branch: ... / Commit: ...
Duration: <n>s
```

The plumbing: at `send` time the tool captures the *delegator* session ID and
registers an `onResult` callback that calls
`AgentService.enqueueNudgeForSession(delegatorSessionId, nudge)`. The session ID
is captured into a local because the active session may change by the time the
remote finishes.

The consequence for the LLM: **do not poll.** After a `send`, the loop should
yield and continue other work; the nudge wakes it when the result is ready.
`fetch_transcript` exists for *on-demand inspection* of an in-flight or finished
session — not as a completion-polling mechanism. An agent that reads the
`status:"running"` header as if it were the result, or that spins on
`fetch_transcript`/`list_targets` waiting for completion, is misusing the tool.

## `send`: two branches on `handle`

`send` is the only action with branching behavior, keyed on whether `handle` is
present:

| Branch | Trigger | Behavior |
|---|---|---|
| Fresh send | no `handle` | `DelegationOutboundService.send` opens the target **picker**, runs the inbound-consent handshake on the chosen target, creates a new channel, and registers the result nudge. |
| Continuation | `handle` present | `sendContinuation` skips the picker *and* the Accept dialog and writes a fresh `UserTurn` onto the existing channel (Plan 4). |

The continuation branch is the correct way to send a *follow-up* to a delegation
that is already running. Opening a fresh `send` for a follow-up spins up a new
picker and a new remote session, losing the conversational context the remote
agent already has.

## Reachability is ambiguous by construction

`DelegationTargetNotReachable` deliberately enumerates causes rather than
asserting one, because at the socket layer **"inbound delegation disabled" and
"IDE not running" are indistinguishable**. The single most common real cause is
the former: the target IDE is running fine but has not enabled "Accept incoming
delegations from other IDEs" in Settings → Tools → Workflow Orchestrator →
Cross-IDE Delegation. The error message lists causes in likelihood order and
tells the LLM to ask the user to verify the inbound setting *first* — because
that is the cause a user cannot diagnose from this side and the one a naive
"the other IDE must be closed" reading gets wrong.

This is also why `list_targets` reports a coarse `status` per target
(`running` / `closed` / `discovered` / `missing`) derived from a socket probe
(`DelegationClient.ping`): it tells you a socket answered, not that the target
will accept.

## Consent and the human-in-the-loop on `answer`

`answer` forwards a reply to a clarifying-question nudge from the remote session.
It honors `PluginSettings.autoApproveDelegationAnswers`:

- **off (default-safe):** opens `DelegationAnswerConfirmDialog` showing the
  remote question, the proposed answer, and the target repo. The human can edit
  the answer or decline entirely (decline → error, nothing is sent).
- **on:** the answer is forwarded directly without interrupting the loop.

`answer` also distinguishes two failure shapes so recovery differs:

- `DelegationHandleNotFound` — the channel is unknown or already closed (the
  remote session terminated since the question arrived). **Recovery: start a new
  `send`, do not retry the dead handle.**
- `DelegationWriteFailed` — the channel exists but the IPC write was rejected
  (channel shutting down). **Recovery: retry, or fall back to a new `send`.**

## `fetch_transcript`: path + bounded preview

`fetch_transcript` exports/locates `transcript-export.json` **on the target
IDE's filesystem** and returns its path, byte size, a token estimate, and the
first 2 KiB under `head`. If the file exceeds 2 KiB it appends a truncation
marker pointing the LLM at `read_file` on the path for the full content.

The path-plus-preview shape is deliberate: a long remote session can produce a
large transcript, and returning it inline would blow the context budget on every
inspection. The preview answers "what is this session doing?" cheaply; the full
read is opt-in. `NotFound` maps to `DelegationExpired` — the handle has timed
out, been pruned, or the remote IDE closed.

## `list_targets`: read-only, never throws

`list_targets` returns the same set the picker would show, without opening any
UI. It is the union of:

1. **Recents** — `RecentProjectsManagerBase.getRecentPaths()`, each path
   UDS-socket-probed to classify `running` vs `closed`, or `missing` if the path
   no longer exists on disk (the socket probe is skipped for nonexistent paths).
2. **Discovery** — `SocketGlobDiscovery` finds reachable sockets whose project
   path is *not* already in recents (`discovered`).

Both providers are wrapped in try/catch and degrade to an empty list on failure —
`list_targets` never errors. Its purpose is situational awareness before a blind
`send`: pick a `repoName` from the result to pass as `suggested_repo`.

> Note: the production recents provider does not currently expose per-path
> timestamps, so `lastOpened` is `null` for recents entries even though the field
> is present in the schema. Treat a present-but-null `lastOpened` as "unknown",
> not "never opened".

## Channel limit and hygiene

There is a cap of `DelegationOutboundService.MAX_CHANNELS` concurrent open
channels; exceeding it returns `DelegationLimitReached`. `close` is idempotent
(`{"closed":false,...}` when already closed is still a success), so it is safe to
call defensively after a result has been consumed to free a slot. The teardown
path also releases the channel automatically when a remote session ends, but
explicit `close` is the way to reclaim a slot for a session you're done with
before the timeout prunes it.

## Error taxonomy

The first token of every error is the category, on purpose: branch on the prefix,
don't retry blindly.

| Category | Action(s) | Cause | Recovery |
|---|---|---|---|
| `DelegationOutboundDisabled` | all | outbound delegation off in Settings | Ask the user to enable it; nothing to retry. |
| `DelegationUserCanceledPicker` | send | user dismissed the picker | Don't auto-retry; confirm intent with the user. |
| `DelegationTargetNotReachable` | send | socket unreachable — usually inbound disabled on target | Ask user to enable "Accept incoming delegations" on the target IDE. |
| `DelegationLimitReached` | send | `MAX_CHANNELS` already open | `close` an existing channel, then resend. |
| `DelegationDeclined` / `DelegationRejected` | send | target user declined consent / channel rejected | Surface to user; don't retry blindly. |
| `DelegationExpired` | send (continuation), fetch_transcript | handle gone (timeout / prune / IDE closed) | Start a fresh `send`. |
| `DelegationHandleNotFound` | answer | handle unknown or already closed | Start a fresh `send`. |
| `DelegationWriteFailed` | send (continuation), answer | IPC write rejected | Retry, or fall back to a fresh `send`. |

## Counterfactual: dropping `delegation`

There is no `run_command` substitute the way there is for `runtime_exec`. An
agent in IDE-A simply has no path to make work happen in a repo it does not have
open: it cannot open another project, cannot reach IDE-B's services, and
`run_command` only sees the current working tree. Without this tool the only
fallback is to tell the human to switch windows and drive the other agent by
hand — which discards the three things the tool adds: the asynchronous hand-off
(IDE-A keeps working), the inbound-consent gate (IDE-B's user opts in), and the
transcript trail for auditing what the remote agent did.

## Drop-decision summary

Net verdict: **STRONG keep** for the tool overall.

- `send` is the entire feature — the only way to hand work across the IDE
  boundary, async and consent-gated by design.
- `answer` keeps a delegated session unblocked while preserving a human review
  step (unless the user opts into auto-approve).
- `fetch_transcript` is the only window into the remote session, with bounded
  token cost.
- `close` is cheap channel hygiene against the concurrent-channel cap.
- `list_targets` is side-effect-free situational awareness that avoids a blind
  send into a picker.

All five anchor on the same channel/handle substrate and IPC transport, so they
belong in one tool. The feature is off by default, so the schema cost is only
paid by users who opt in.
