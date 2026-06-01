---
name: cross-ide-delegation
description: Use when the user's task requires changes across two repos that are open in different IntelliJ windows on this machine (e.g. backend + frontend, library + consumer). Covers `delegation` (actions: send, close, answer, fetch_transcript, status, wait, list_targets, list_handles), continuation-after-completion, and the picker UX.
---

# Cross-IDE Delegation Workflow

You can delegate work to an agent running in a separate IntelliJ window holding a different repository. The remote agent runs independently; results flow back as a system nudge in this session when the remote agent finishes.

## When to use

- The user's task touches code in another repo currently open in a different IDE window.
- You need to run tests, inspect code, or make commits in a repo that isn't this one.
- The user mentions "the other IDE" or "the frontend repo" while you're in the backend repo (or vice versa).

## When NOT to use

- Single-repo tasks. Use your normal tools.
- The other repo is not open anywhere — `delegation(action="send")` can auto-launch closed projects, but if the user hasn't established the IDE+repo combo before, ask first.
- Cross-machine work. Cross-IDE delegation is local-only, same-user, same-machine.

## The picker is the trust + discovery gate

`delegation(action="send")` does NOT enumerate available IDEs for you. It opens a modal picker dialog in the requesting IDE so the human picks the target IDE/repo. Three implications:

1. You don't need to know what's running before calling `delegation(action="send")`. Pass your intent via `request` and an optional `suggested_repo` hint.
2. The human is the trust gate on both sides — they pick the target, AND the target IDE shows an Accept dialog before the remote agent starts.
3. If you want to pre-flight what's available (e.g. to tell the user which repos are options), use `delegation(action="list_targets")` — it **lists candidate target IDEs and their inbound readiness** (the same list the picker shows) without opening any UI. It does NOT list your active delegation handles (use `list_handles` for that). Status meanings:
   - `running` — IDE open AND inbound delegation is ON (accepts a delegation directly, no consent prompt). Each target also carries an **advisory** busy hint: `status_label` shows `running (busy)` vs `running (idle)` and a machine `busy` field is `true`/`false`/`null`. `busy: true` = the target's agent tab is already running another task (a `send` may be declined as `ide_b_busy` or queued); `busy: false` = idle and ready. It is a **point-in-time snapshot** (the target could pick up work between this probe and your `send`); `busy: null` = unknown (older peer) → treat as plain `running`. Prefer an idle target when one is offered.
   - `available` — IDE open but inbound delegation is OFF — a `send` rings its doorbell and the **user is asked to consent** first.
   - `closed` — in recents, IDE not running this project.
   - `missing` — project path no longer exists on disk.
   - `discovered` — socket-glob found a live socket not in recents.

## Typical workflow

1. **Discover (optional):** Call `delegation(action="list_targets")` to see candidate IDEs and their inbound readiness (`running` / `available` / `closed` / etc.). Useful when the user asks "what other repos can you reach?" — surface the list and ask them which one. Remember: `running` means inbound delegation is ON (no consent prompt); `available` means the IDE is open but inbound is OFF (a `send` will ring the doorbell and ask for consent).

2. **First contact:** Call `delegation(action="send", request="<full briefing>", suggested_repo="<hint>")`. The picker opens; the user picks. You get back a handle and a "running" status. The remote agent works asynchronously; you continue your own task. `suggested_repo` is a **pre-selection hint for the picker only** — it pre-highlights the matching target but does NOT hard-select or force it. If the user picks a different target, that selection wins; the tool never fails because a suggestion wasn't honoured. The success result narrates what happened ("as suggested" / "user selected … via the picker").

3. **Result arrives:** A system nudge appears in your conversation when the remote agent finishes (`COMPLETED` / `CANCELED` / `FAILED` / `REJECTED`). **Do NOT poll for it.** The nudge contains a summary; if you need the full message history, call `delegation(action="fetch_transcript", handle=...)` and read the returned file.
   - **Need the result inline (next step depends on it)?** Instead of continuing other work, ATTACH: call `delegation(action="wait", handle=..., timeout_seconds=...)`. `timeout_seconds` is an integer, **allowed range 5–1800 (default 300); values outside are clamped and the clamp is reported in the result**. It blocks the turn and returns the result — or a clarifying question — inline. A wait **timeout is not a failure**: it just means "still running", and the async nudge still auto-delivers when the remote finishes (your session auto-resumes).
   - **Just want a cheap liveness check?** Call `delegation(action="status", handle=...)` → `active` (with last state), `closed` (with the terminal last_state: COMPLETED/FAILED/CANCELED/REJECTED **and `retention_expires_in_seconds`** — how many seconds of the 30-minute retention window remain, so you know how urgently to fetch the transcript or start a continuation), or not-found once the 30-minute window elapses. Don't call it in a tight poll loop.

4. **Follow-up turn (if needed):** To send a new user turn on the SAME session without re-opening the picker, call `delegation(action="send", handle="<previous-handle>", request="<follow-up>")`. **This works even if the remote session already COMPLETED** (this requires the remote IDE to still be OPEN — only the session completed, not the IDE; a closed IDE returns `ide_b_not_running`) — within the 30-minute retention window the remote IDE resurrects the persisted session and resumes it from where it left off. Reuse the handle rather than opening a fresh delegation (which loses the prior context). Check `delegation(action="status")` for `retention_expires_in_seconds` if you need to know how long the window has left.

5. **Questions from the remote agent:** If the remote agent needs clarification, you'll receive a nudge (or a `wait` returns a question) with the question text and a question id. Form an answer; call `delegation(action="answer", handle=..., question_id=..., answer=...)` (the reply param is `answer`, not `request`). After answering, `wait` again if you're attached. The user may need to confirm your answer via a dialog unless auto-approve is on. The remote human can also short-circuit by typing an answer directly in the remote IDE — you'll get a "canceled, answered locally" nudge.

6. **Cleanup:** If you no longer need the channel (e.g. the work is fully reflected in your session), call `delegation(action="close", handle=...)`. Idempotent (`{"closed":false}` when already closed is still a success). After close, the handle stays retained ~30 min — `status`, `fetch_transcript`, and `send`-continuation all keep working in that window. Otherwise the channel naturally closes on idle timeout (default 30 min, configurable) or when the user cancels.

7. **Lost track of a handle?** Call `delegation(action="list_handles")` to enumerate every delegation you currently hold for THIS session (active + closed-but-retained), each with its repo, project path, `b_session_id`, and `last_state`. Takes no handle. Empty → "no active or retained delegations in this session". Use it to recover a handle or correlate which delegation is which — and to match a busy-decline blocker (see below) against your own outstanding delegations.

## Errors you may see

- `DelegationOutboundDisabled` — cross-IDE delegation is off in settings. Tell the user.
- `DelegationUserCanceledPicker` — user dismissed the picker. They probably changed their mind; do not retry without asking.
- `DelegationTargetNotReachable` — auto-launch failed or the user picked a target that isn't running. Ask the user (the most common real cause is inbound delegation being OFF on the target — ask them to enable "Accept incoming delegations").
- `DelegationLimitReached` — already 5 open channels. Close one with `delegation(action="close")` first.
- `DelegationRejected` / `DelegationDeclined` — **RETRYABLE; the handle (if any) is still valid.** Either the remote human declined consent, or the target tab is busy. A **busy** rejection is self-describing: the reason leads with `ide_b_busy:` and NAMES the in-flight task the target tab is busy with (`ide_b_busy: agent tab is busy running session <sid> ('<title>')[, delegated by <repo> session <delegatorSessionId>]; user did not click Start within Ns`). Busy is **always `DelegationRejected`** — on a fresh send AND on a continuation send (`handle` present). If the in-flight task is itself delegated, the reason echoes ITS delegator session id — match that against your own `list_handles` `b_session_id` to recognize the blocker as YOUR OWN earlier task (and `wait` on it / let it finish instead of retrying blindly). You can retry shortly once it's free — reuse the same handle on a continuation.
- `DelegationExpired` — **the handle is GONE / no longer usable** (NOT a busy target — that's `DelegationRejected`). On a **continuation send** (`handle` present) this carries a specific terminal-gone reason so you know how to recover:
  - `session_closed` / `session_not_found` — the remote session is genuinely gone or pruned (past retention). Start a fresh `send`.
  - `ide_b_not_running` — the target IDE is down. Ask the user to reopen it (or send fresh, which can cold-launch a closed project).
  - `resume_failed` — the persisted session is locked or missing on disk. Start a fresh `send`.
- `DelegationHandleClosed` (on `answer`) — the session has already COMPLETED; you can't answer a finished session. To ask it more, use `send` with the same handle (continuation resumes it); to read what it did, use `fetch_transcript`.
- `DelegationHandleNotFound` (on `answer` / `status` / `wait`) — the handle is unknown or its ~30 min retention window elapsed. Start a fresh `send`.

## Gotchas

- Delegation is local-only, same user. Do not suggest it for remote/team workflows.
- You can hold up to 5 open channels at once.
- **Don't poll.** The result nudge arrives automatically. If you must have the result inline, ATTACH with `action="wait"` (a timeout is not a failure) — never loop on `status`/`fetch_transcript`.
- **Continuation works after completion.** Reusing a handle with `action="send"` resumes the SAME remote session even once it COMPLETED, within the ~30 min retention window (this requires the remote IDE to still be OPEN — only the session completed, not the IDE; a closed IDE returns `ide_b_not_running`). Don't open a fresh delegation for follow-ups.
- **Handle retention is exactly 30 minutes after close.** Within that window `status`, `fetch_transcript`, and `send`-continuation all keep working; `status` now also reports `retention_expires_in_seconds` so you know how long is left. `status`, `answer`, and continuation agree about whether a handle exists. After it, the handle is consistently "not found".
- **Cold launch of a closed IDE works** — after the launched IDE boots and finishes indexing, the consent dialog appears (the launch request stays valid ~30 min). On a large repo this can take a while; the dialog won't show until indexing completes.
- A continuation send skips the Accept dialog (the human already accepted this channel earlier). Don't treat it as a fresh trust boundary.
