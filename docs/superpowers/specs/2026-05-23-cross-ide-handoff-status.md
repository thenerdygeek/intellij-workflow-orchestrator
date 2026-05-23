# Cross-IDE Agent Delegation — Session Handoff

**Date:** 2026-05-23 · **Status:** Plans 0+1+2 shipped, branched out for isolated work · **Branch:** `feature/cross-ide-delegation`

---

## 1. Orientation (read this first)

You are picking up a multi-session implementation of cross-IntelliJ agent delegation. The feature lets Agent-A in one IDE delegate work to Agent-B in a separate IDE on the same machine, with question routing back from Agent-B to Agent-A. Three plans have shipped:

- **Plan 0** — per-session plan-mode refactor (prereq to clean up an in-memory state foot-gun)
- **Plan 1** — MVP one-shot delegation (settings, IPC over UDS, tools, dialogs, lifecycle)
- **Plan 2** — question routing (Question/Answer wire messages, atomic CAS for race-safety, IDE-A confirmation dialog, IDE-B short-circuit)

Each plan went through implementer-per-task subagent dispatch + a final Sonnet code review + a cleanup commit for HIGH+MED findings. The feature is currently behind two opt-in settings (both default off): outbound delegation and inbound acceptance.

## 2. Branch and worktree layout

```
/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin                            <- bugfix branch (clean, no cross-IDE work)
/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/cross-ide       <- THIS WORKTREE — feature/cross-ide-delegation
/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/agent-bugfixes  <- unrelated worktree (fix/agent-iteration-bugs)
```

- **`bugfix`** is at `7d6c633bf` — byte-identical to `origin/bugfix`. No cross-IDE commits.
- **`feature/cross-ide-delegation`** is at `c7a89156c` — has all 32 cross-IDE commits ahead of `bugfix`.
- **NOT pushed to origin.** Local-only branch.
- **`.worktrees/`** is in `.gitignore`.

When working on cross-IDE delegation, work IN the worktree at `.worktrees/cross-ide/`. When bugfix accumulates unrelated changes, rebase the feature branch onto the new bugfix tip:

```bash
cd .worktrees/cross-ide
git fetch
git rebase bugfix
```

Potential rebase-conflict surfaces (files touched by both this feature and likely other work):
- `agent/src/main/kotlin/.../AgentService.kt` (heavy edits)
- `agent/src/main/kotlin/.../AgentController.kt` (steering-input intercept)
- `agent/src/main/kotlin/.../session/Session.kt` + `HistoryItem.kt` + `MessageStateHandler.kt` (schema additions)
- `agent/src/main/kotlin/.../tools/builtin/AskQuestionsTool.kt` (delegated-session branch)
- `agent/src/main/kotlin/.../session/PerSessionAgentState.kt` (new file but referenced widely)
- `core/src/main/kotlin/.../settings/PluginSettings.kt` (3 new fields)

## 3. What's shipped — commit map

Spec (3 commits): `fe5515f27` → `b287ecc41` → `7ad06da07`
- `docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md` is the canonical design

Plan 0 — per-session plan-mode (6 commits): `1c6b26030` → `02bc0dc1f`

Plan 1 — MVP delegation (13 commits): `747445fcb` → `d768a5b2b`

Plan 2 — question routing (10 commits): `9db4cbc17` → `c7a89156c`

Full commit list: `git log --oneline 7d6c633bf..feature/cross-ide-delegation`

## 4. What works end-to-end

- **Two opt-in settings** in `Tools → Workflow Orchestrator → Cross-IDE Delegation`:
  - "Allow this IDE to delegate to other IDEs" (outbound)
  - "Accept incoming delegations from other IDEs" (inbound)
  - "Auto-approve Agent-A's answers to delegated-session questions" (auto-approve)
  - All three default off
- **`delegation_send(request, suggested_repo?)`** — opens a picker, user picks a Running target, IPC handshake, returns handle
- **`delegation_close(handle)`** — idempotent close
- **`delegation_answer(handle, question_id, answer)`** — sends Agent-A's answer back; gated by `DelegationAnswerConfirmDialog` unless auto-approve is on
- **Inbound Accept dialog** — every incoming delegation requires explicit human acceptance in the receiving IDE
- **Delegated session is first-class** — appears in `sessions.json` with `HistoryItem.delegated` populated; survives in history
- **Question routing** — Agent-B's `ask_followup_question` routes over IPC; Agent-A's loop receives a nudge; LLM calls `delegation_answer`; dialog confirms (or auto-approves)
- **IDE-B short-circuit** — human in receiving IDE can answer the pending question by typing in the session tab; CAS via `PendingQuestionToken` ensures exactly one of (Agent-A's answer / local answer) wins

## 5. Test state (this branch)

```bash
cd .worktrees/cross-ide
./gradlew :core:test :agent:test --rerun
```

Expected: BUILD SUCCESSFUL, 3921 `:agent` tests pass, ~1063 `:core` tests pass, 0 failures. There are NO pre-existing flakes on this branch as of `c7a89156c` (the MEMORY.md mentions `ToolDslSchemaParityTest` and `AgentDebugControllerTest.getStackFrames` as pre-existing fails on a different branch; they don't appear here).

If you see test failures after rebasing, check:
- Did `AskQuestionsTool` get re-touched by bugfix? Plan 2 cleanup added a delegated-session branch that requires `project.service<AgentService>()` to be stubbed in tests using `mockk<Project>(relaxed = true)`. The `@BeforeEach stubAgentService()` pattern is in `AskFollowupQuestionFlowTest`, `AskQuestionsToolTest`, `AskQuestionsEnrichmentTest`.

## 6. What is NOT done (deferred per spec non-goals + future plans)

### Plan 3 candidates (next obvious work)
- Auto-launch for Closed-repo picker rows (currently shows "Open it manually first")
- Socket-glob cross-installation supplement — discover IDEs across different JetBrains Toolbox slots
- Idle timeout (`TIMED_OUT` state) — currently channels stay open until explicit close
- Cascade-cancel from parent session cancel — when Agent-A's session is canceled, all open child channels should auto-close
- Project-window-close distinct from process-death — closing just the project window in IDE-B should fail delegated sessions cleanly
- `delegation_fetch_transcript` tool — Agent-A retrieves full Agent-B transcript

### Plan 4 candidates
- `continue_with` argument on `delegation_send` — reuse an existing channel for follow-up work
- `CHANNEL_RESUME` protocol — re-attach delegation handles after IDE-A restart
- History-list UI affordance for delegated sessions in IDE-B's chat panel (badge + click-through)
- Banner / placeholder in IDE-B's input when a question is pending (Plan 2 review F7 was MVP-fixed with an inline nudge; full UX is deferred)

### Explicit non-goals from spec §2
1. Cross-machine delegation
2. Cross-user delegation
3. Multi-hop chains (A → B → C)
4. Per-delegation tool restrictions
5. Encryption / TLS on the IPC (local-only, filesystem-permissioned)
6. Auto-default-to-conversation (continuation requires explicit `continue_with`)
7. Delegation to non-plugin IDEs
8. Web / system-notification / Slack surfaces

## 7. Open issues (NOT addressed)

These came from the Sonnet code reviews and were deliberately not fixed:

| # | From | Severity | Item |
|---|---|---|---|
| Plan 1 F7 | review | LOW | Double-bind race in `DelegationInboundService.start()` — two startup paths can both bind. Mitigation: `@Synchronized` on `start()`. |
| Plan 1 F8 | review | LOW | No `finally { closeChannel() }` on all exception paths in `startDelegatedSession`. Partially mitigated by Plan 1 cleanup F1 fix, but exception paths in `cs.launch` body don't all have it. |
| Plan 1 F9 | review | LOW | Commit message `153537bd6` claims `TIMED_OUT` handling (it's actually a Plan 3 state). Won't-fix (git history). |
| Plan 1 F10 | review | LOW | `DelegationException.Expired` defined but never thrown (scaffolding for Plan 4). Should add a `// TODO Plan 4` comment. |
| Plan 2 F8 | review | LOW | Outbound reader's `else` branch on unexpected message types keeps FD open under garbage input. Suggested fix: counter + break after N unknown messages. |
| Plan 2 F9 | review | LOW | `DelegationOutboundService.close()` removes from `handleToSessionId` before `activeChannels` — non-atomic, can produce spurious warn-log entries during concurrent question delivery. |
| Plan 2 F10 | review | LOW | `delegation_answer` returns the same error text for "handle not in map" vs "write failed" — LLM can't distinguish "give up" from "retry". |

## 8. Manual smoke test — NOT yet done

Subagents can't drive interactive IDEs. Before merging, recommend:

1. Build: `./gradlew clean buildPlugin` (in the worktree)
2. Install the built `build/distributions/*.zip` into two separate IntelliJ instances (or two `runIde` sandboxes pointing at different projects)
3. Enable both `enableOutboundCrossIdeDelegation` AND `enableInboundCrossIdeDelegation` on both IDEs
4. Restart both IDEs (the inbound socket binds at project open)
5. In IDE-A's agent chat, ask the agent to call `delegation_send` with a request that mentions the other IDE's repo by name
6. Verify the picker appears and shows the other IDE as `● Running`
7. Click Delegate → verify Accept dialog appears in IDE-B
8. Click Accept → verify a new session tab opens in IDE-B labeled "Delegated by IDE-A …"
9. Wait for Agent-B to complete → verify a result nudge appears in IDE-A's chat
10. Test question routing: ask Agent-B to do something that requires user clarification → verify the question routes back to IDE-A
11. Test the IDE-B short-circuit: have Agent-B ask a question → before IDE-A responds, type an answer directly in IDE-B's session tab → verify Agent-B receives the typed answer

Capture surprises in `docs/superpowers/reviews/2026-05-23-cross-ide-mvp-smoke-test.md`.

## 9. Key files map

### `:core/delegation` (transport layer)
- `DelegationPaths.kt` — deterministic UDS socket path computer (`~/.workflow-orchestrator/ipc/<hash>.sock`)
- `DelegationProtocol.kt` — sealed `DelegationMessage` (8 variants: Ping, Pong, Connect, AcceptResult, Result, Question, Answer, AnswerCanceled) + `DelegationFraming` (4-byte length prefix + JSON, 10 MiB cap)
- `DelegationServer.kt` — UDS server. `onConnect` lambda is `(connect, replyWith, readMessage, closeChannel) -> Unit`
- `DelegationClient.kt` — UDS client. `ping()` for liveness + `connectAndAwaitAccept()` for handshake

### `:core/settings`
- `PluginSettings.kt` — three new properties (`enableOutboundCrossIdeDelegation`, `enableInboundCrossIdeDelegation`, `autoApproveDelegationAnswers`)
- `CrossIdeDelegationConfigurable.kt` — settings UI (three checkboxes)
- `CrossIdeDelegationSettingsListener.kt` — MessageBus topic (`inboundSettingChanged` + `outboundSettingChanged`)

### `:agent/delegation`
- `DelegationHandle.kt` — opaque handle (id, targetProjectPath, targetRepoName)
- `DelegationException.kt` — sealed exceptions (UserCanceledPicker, TargetNotReachable, LimitReached, Rejected, Expired)
- `DelegationInboundService.kt` — per-project service; manages `DelegationServer` lifecycle; routes Connect to Accept dialog; tracks per-session `SessionChannel`+`PendingQuestionToken`; exposes `routeQuestion`, `deliverAnswer`, `localAnswer`, `hasPendingQuestion`
- `DelegationOutboundService.kt` — per-project service; manages outbound channels (max 5); message-loop reader handles Question/AnswerCanceled/Result; exposes `send`, `close`, `sendAnswer`, `lookupPendingQuestionText`, `targetRepoName`
- `DelegationInboundStartupActivity.kt` — `ProjectActivity` that calls `inbound.start()` on project open
- `PendingQuestionToken.kt` — atomic CAS primitive (armIfClear / tryResolve / tryResolveCurrent / cancel)
- `ui/AcceptDelegationDialog.kt` — modal Accept/Reject prompt for inbound
- `ui/DelegationPicker.kt` — modal picker for outbound (RecentProjectsManager + PING probe)
- `ui/DelegationAnswerConfirmDialog.kt` — modal confirm for `delegation_answer` when auto-approve is off

### `:agent/tools/delegation`
- `DelegationSendTool.kt` — `delegation_send`
- `DelegationCloseTool.kt` — `delegation_close`
- `DelegationAnswerTool.kt` — `delegation_answer` (gates on auto-approve setting; shows confirm dialog when off)

### `:agent/session` (existing files modified)
- `Session.kt` — added `DelegationMetadata` data class + `delegated` field
- `HistoryItem.kt` — added `delegated: DelegationMetadata?` field for sessions.json index
- `PerSessionAgentState.kt` — created in Plan 0; added `@Volatile delegated` field in Plan 2 Task 4
- `MessageStateHandler.kt` — added `updateSessionDelegationMetadata`, `findHistoryItem`, `updateSessionPlanMode` methods

### `:agent` (existing files modified)
- `AgentService.kt` — `startDelegatedSession` (returns sessionId synchronously, registers per-session channel), `reregisterCrossIdeDelegationTools`, `isPlanModeActive`/`setPlanModeActive` (Plan 0), `perSessionStates` registry
- `tools/builtin/AskQuestionsTool.kt` — delegated-session branch calls `DelegationInboundService.routeQuestion`
- `ui/AgentController.kt` — `executeTaskInternal` intercepts typed input for delegated sessions with pending questions, routes to `inbound.localAnswer`

### Spec + handoff
- `docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md` — design spec
- `docs/superpowers/specs/2026-05-23-cross-ide-handoff-status.md` — THIS FILE
- `docs/superpowers/plans/2026-05-22-plan-mode-per-session.md` — Plan 0 (gitignored, local-only)
- `docs/superpowers/plans/2026-05-22-cross-ide-delegation-mvp.md` — Plan 1 (gitignored, local-only)
- `docs/superpowers/plans/2026-05-23-cross-ide-delegation-questions.md` — Plan 2 (gitignored, local-only)

## 10. Recommended next steps (in order)

1. **Manual smoke test** in two real IDEs (§8). If anything breaks, file as `reviews/<date>-cross-ide-smoke-findings.md` and fold into Plan 3.
2. **Address LOW review findings** (§7) — small batch commit, maybe 1-2 hours.
3. **Brainstorm + write Plan 3** — auto-launch + socket-glob + idle timeout + cascade-cancel + project-window-close + `delegation_fetch_transcript`. This is the "robustness pass."
4. **Brainstorm + write Plan 4** — `continue_with` + `CHANNEL_RESUME` + history-list UI + IDE-B input-banner. This is the "stateful continuity pass."
5. **Push the branch** to origin when ready for collaborative review or backup.

## 11. How to continue in a new session

When you start a new session to keep working on this:

1. `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/cross-ide`
2. `git log --oneline -5` — confirm you're at `c7a89156c` (or wherever the branch tip is)
3. Open this file (`docs/superpowers/specs/2026-05-23-cross-ide-handoff-status.md`) to re-orient
4. Open `docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md` for the design rationale
5. If picking up Plans 3 or 4, the plan files (gitignored) live in `docs/superpowers/plans/` — they may or may not have been written yet
6. Run `./gradlew :core:test :agent:test` to confirm the test state is clean before adding work

If the new session is a Claude Code session: tell Claude "I'm continuing the cross-IDE delegation work — read `docs/superpowers/specs/2026-05-23-cross-ide-handoff-status.md` and the design spec first."

## 12. Settings (for runtime use + manual smoke)

In each IDE you want to participate in cross-IDE delegation:

`Settings → Tools → Workflow Orchestrator → Cross-IDE Delegation`

| Setting | What it does | Default |
|---|---|---|
| Allow this IDE to delegate to other IDEs | Registers `delegation_send` / `delegation_close` / `delegation_answer` tools in the agent's tool registry | Off |
| Accept incoming delegations from other IDEs | Binds the UDS socket on project open; shows Accept dialog for incoming Connect | Off |
| Auto-approve Agent-A's answers to delegated-session questions | Skips the `DelegationAnswerConfirmDialog`; the LLM's drafted answer goes straight to Agent-B | Off |

All three are per-project (stored in `workflowOrchestrator.xml`). The first two take effect on toggle (no restart needed — `CrossIdeDelegationSettingsListener` rewires the tool registry / re-binds the socket). The third is read at every `delegation_answer` execute, so toggle takes effect immediately.

---

*This handoff is a snapshot at `c7a89156c` (2026-05-23). When you fold this work into bugfix later, this file can be deleted — it's a session-to-session bridge, not a permanent artifact.*
