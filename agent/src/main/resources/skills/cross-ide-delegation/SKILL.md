---
name: cross-ide-delegation
description: Use when the user's task requires changes across two repos that are open in different IntelliJ windows on this machine (e.g. backend + frontend, library + consumer). Covers `delegation` (actions: send, close, answer, fetch_transcript, list_targets) and the picker UX.
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
3. If you want to pre-flight what's available (e.g. to tell the user which repos are options), use `delegation(action="list_targets")` — it returns the same list the picker shows (running / closed / discovered) without opening any UI.

## Typical workflow

1. **Discover (optional):** Call `delegation(action="list_targets")` to see available IDEs and statuses. Useful when the user asks "what other repos can you reach?" — surface the list and ask them which one.

2. **First contact:** Call `delegation(action="send", request="<full briefing>", suggested_repo="<hint>")`. The picker opens; the user picks. You get back a handle and a "running" status. The remote agent works asynchronously; you continue your own task.

3. **Result arrives:** A system nudge appears in your conversation when the remote agent finishes (`COMPLETED` / `CANCELED` / `FAILED` / `REJECTED` / `TIMED_OUT`). The nudge contains a summary; if you need the full message history, call `delegation(action="fetch_transcript", handle=...)` and read the returned file.

4. **Follow-up turn (if needed):** To send a new user turn on the SAME channel without re-opening the picker, call `delegation(action="send", handle="<previous-handle>", request="<follow-up>")`. The remote agent's session continues from where it left off.

5. **Questions from the remote agent:** If the remote agent needs clarification, you'll receive a nudge with the question text and a question id. Form an answer; call `delegation(action="answer", handle=..., question_id=..., answer=...)`. The user may need to confirm your answer via a dialog unless auto-approve is on. The remote human can also short-circuit by typing an answer directly in the remote IDE — you'll get a "canceled, answered locally" nudge.

6. **Cleanup:** If you no longer need the channel (e.g. the work is fully reflected in your session), call `delegation(action="close", handle=...)`. Idempotent. Otherwise the channel naturally closes on idle timeout (default 30 min, configurable) or when the user cancels.

## Errors you may see

- `DelegationOutboundDisabled` — cross-IDE delegation is off in settings. Tell the user.
- `DelegationUserCanceledPicker` — user dismissed the picker. They probably changed their mind; do not retry without asking.
- `DelegationTargetNotReachable` — auto-launch failed or the user picked a target that isn't running. Ask the user.
- `DelegationLimitReached` — already 5 open channels. Close one with `delegation(action="close")` first.
- `DelegationRejected` — the remote human declined the Accept dialog. Do not retry without asking.
- `DelegationExpired` — handle is dead (timed out, remote IDE-B gone, session pruned). For `continue_with` cases on a restarted IDE-A, the CHANNEL_RESUME protocol attempts re-attach automatically — if you still see this error, the remote session is genuinely gone.

## Gotchas

- Delegation is local-only, same user. Do not suggest it for remote/team workflows.
- You can hold up to 5 open channels at once.
- Don't poll. The result nudge will arrive automatically.
- `continue_with` skips the Accept dialog (the human already accepted this channel earlier). Don't treat it as a fresh trust boundary.
