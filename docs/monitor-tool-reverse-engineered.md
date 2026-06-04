# The Monitor Tool — Reverse-Engineered Reference & Possibilities

> A working document derived from the Monitor tool's specification (its JSON schema + behavioral
> description). Where a statement is *documented* in the spec it is stated plainly; where it is
> *inferred* from the contract it is marked **(inferred)**. Treat inferred items as hypotheses to
> confirm empirically, not guarantees.

---

## 1. One-sentence mental model

**Monitor turns a long-running shell script into an event stream: every line the script prints to
stdout becomes a chat notification, delivered asynchronously while the agent keeps working, until
the script exits or is killed.**

That single sentence is the whole tool. Everything else is consequence.

---

## 2. The interface (reverse-engineered from the schema)

| Parameter | Type | Required | Default | Meaning |
|---|---|---|---|---|
| `command` | string | ✅ | — | Shell command/script. **Each stdout line = one event.** Exit ends the watch. |
| `description` | string | ✅ | — | Human-readable label; **embedded in every notification**. |
| `timeout_ms` | number | ✅ in schema | 300000 (5 min) | Hard deadline; monitor is **killed** at this point. Max 3600000 (1 hr). Ignored if `persistent`. |
| `persistent` | boolean | ✅ in schema | false | If true, runs for the **lifetime of the session** (no timeout); stop with `TaskStop`. |

### What the schema tells us about the design

- `command`, `description`, `timeout_ms`, and `persistent` are **all in the `required` array** — the
  caller must consciously decide bounded-vs-persistent every time. There is no "accidental infinite
  monitor"; the unbounded mode is an explicit opt-in. This is a deliberate safety choice.
- `timeout_ms` has `minimum: 1000` and a documented max of `3600000`. So the bounded mode lives in a
  **1 second → 1 hour** window. Anything longer *must* go through `persistent: true`.
- There is **no parameter for stdin, environment, working directory, or output filtering**. All of
  that lives inside `command` — the tool is intentionally a thin wrapper around "run this shell line
  and stream its stdout."

```
★ Insight ─────────────────────────────────────
The schema is the clearest reverse-engineering artifact you have. The fact that BOTH timeout_ms and
persistent are required (not optional with a default) means the tool author wanted the dangerous
choice — "run forever" — to be impossible to make by omission. Compare to most APIs where infinite
is the lazy default. Reading required-vs-optional fields is how you infer a tool's risk model.
─────────────────────────────────────────────────
```

---

## 3. Execution model (mechanics)

These are stated or strongly implied by the spec:

1. **Same shell environment as the `Bash` tool.** The script inherits the same shell init (env vars,
   PATH, profile) as a normal Bash call. So anything you can run in Bash, you can monitor.
2. **stdout is the *only* event channel.** Each newline-terminated line on stdout becomes a
   notification.
3. **stderr is NOT an event channel.** stderr is written to an output file (readable via `Read`) but
   does **not** trigger notifications. To surface a command's errors as events you must merge with
   `2>&1` *before* your filter.
4. **Batching window:** stdout lines emitted within **200 ms** of each other are coalesced into a
   single notification. So multi-line output from one logical event groups naturally.
5. **Exit ends the watch**, and the **exit code is reported**.
6. **Timeout kills the process** (bounded mode). `persistent` mode has no timeout and ends only via
   `TaskStop` or session end.
7. **Auto-stop on flood:** monitors that emit too many events are automatically stopped. The remedy
   is to restart with a tighter filter.
8. **Events are not user messages.** An event arriving mid-conversation — even while the agent is
   waiting on a user question — is background data, not a reply from the user.

```
★ Insight ─────────────────────────────────────
Point 8 is subtle but architecturally important. The agent's loop has TWO asynchronous input
streams that can interleave: the human, and monitor events. The tool spec explicitly disambiguates
them so the model never mistakes "ERROR in deploy.log" for the user answering a question. This is
the kind of contract you only discover by reading the prose, not the schema.
─────────────────────────────────────────────────
```

---

## 4. The core decision: notification cardinality

The single most important design question the spec forces on the caller is **"how many
notifications do I want?"** This is the axis the whole tool is organized around.

| You want… | Tool to use | Command shape |
|---|---|---|
| **Exactly one** ("tell me when the build finishes / server is up") | `Bash` w/ `run_in_background` | An `until`/`while` loop that **exits** when the condition is true |
| **One per occurrence, indefinitely** ("every ERROR line, forever") | **Monitor** | Unbounded: `tail -f`, `inotifywait -m`, `while true` |
| **One per occurrence, until a known end** ("each CI step, stop when run completes") | **Monitor** | Emits lines, then **exits** at the terminal condition |

The repeated warning in the spec: **don't use an unbounded command for a single notification.** A
`tail -f | grep` stays armed until timeout even after the one event you cared about already fired —
wasting the monitor slot. Worse, `tail -f log | grep -m 1` does *not* fix it: if the log goes quiet
after the match, `tail` never gets SIGPIPE and the pipeline hangs.

For a single "is it ready yet" signal, the correct tool is `Bash run_in_background` with:

```bash
until grep -q "Ready in" dev.log; do sleep 0.5; done
```

— one completion notification, ends in seconds.

---

## 5. Capability catalog — what Monitor makes possible

Grouping the latent possibilities by *what's being watched*:

### 5.1 Local file / log streams
- **Error tailing:** `tail -f app.log | grep -E --line-buffered "ERROR|WARN|Exception"`
- **Build/test output:** stream a long `gradle`/`npm`/`pytest` run's interesting lines as they print.
- **Filesystem change feed:** `inotifywait -m --format '%e %f' /watched/dir` (Linux) — one event per
  change. **(inferred for macOS:** `fswatch` is the equivalent; this dev box is darwin, so prefer
  `fswatch` over `inotifywait`.)
- **Growth/threshold watches:** poll a file size / line count and emit only when it crosses a bound.

### 5.2 Process / job lifecycle
- **CI pipeline progress:** poll `gh pr checks`/`gh run view`, emit each check as it leaves
  `pending`, exit when all terminal.
- **Deploy watching:** poll a deploy API/k8s rollout and emit on each phase transition.
- **Background training/long compute:** `python train.py 2>&1 | grep -E --line-buffered
  "epoch=|loss=|Traceback|OOM|Killed"` — progress *and* crash signatures.

### 5.3 Remote / network state
- **PR comment feed:** poll `gh api .../comments?since=$last`, emit one line per new comment.
- **Issue/ticket state changes:** poll an API on an interval, diff against last snapshot, emit deltas.
- **Endpoint health:** poll a URL, emit only on status change (up→down, down→up).
- **Queue depth / rate alarms:** poll a metric, emit when it breaches a threshold.

### 5.4 Event-driven (push) sources
- **WebSocket / SSE listeners:** `node watch-for-events.js` — a script that holds a connection and
  prints a line per inbound event. Monitor relays each line as a notification.
- **Message-bus / log-shipper tails:** anything that can be expressed as "a process that prints a
  line when something happens."

```
★ Insight ─────────────────────────────────────
Notice the unifying abstraction: Monitor doesn't know about CI, WebSockets, or inotify. It only
knows "lines on stdout." Every capability above is really "find a shell incantation that prints one
line per event." That's the reverse-engineering payoff — once you see the abstraction, the catalog
of uses is open-ended: any event source with a CLI or a tiny wrapper script is monitorable.
─────────────────────────────────────────────────
```

---

## 6. Patterns & recipes

### 6.1 The poll-and-diff loop (for sources with no native stream)
```bash
prev=""
while true; do
  cur=$(some_command_that_lists_current_state | sort)
  comm -13 <(echo "$prev") <(echo "$cur")   # emit only NEW lines
  prev=$cur
  sleep 30
done
```
Turns a stateless "list everything" command into a "tell me what changed" stream.

### 6.2 The terminal-exit poll (one-per-occurrence, until done)
```bash
prev=""
while true; do
  s=$(gh pr checks 123 --json name,bucket)
  cur=$(jq -r '.[] | select(.bucket!="pending") | "\(.name): \(.bucket)"' <<<"$s" | sort)
  comm -13 <(echo "$prev") <(echo "$cur")
  prev=$cur
  jq -e 'all(.bucket!="pending")' <<<"$s" >/dev/null && break   # exit when all settled
  sleep 30
done
```

### 6.3 Coverage filter (progress + every failure mode)
```bash
# WRONG — silent on crash/hang; silence looks identical to "still running"
tail -f run.log | grep --line-buffered "elapsed_steps="

# RIGHT — one alternation covering progress AND the failure signatures you'd act on
tail -f run.log | grep -E --line-buffered "elapsed_steps=|Traceback|Error|FAILED|assert|Killed|OOM"
```

---

## 7. Constraints, gotchas & anti-patterns

From the spec, distilled:

- **Always `--line-buffered`** (or `stdbuf`/`unbuffer`) in pipes. Without it, pipe buffering delays
  events by *minutes*. This is the #1 footgun.
- **stderr is invisible to notifications.** Merge with `2>&1` before your filter for any command
  whose failures land on stderr.
- **Silence ≠ success.** Your filter must match *terminal/failure* states, not just the happy path.
  Ask: "if this crashed right now, would my filter emit anything?" If no — widen the alternation.
- **Be selective, not optimistic.** "Selective" means the lines you'd *act on* (success **and**
  failure), not "only good news." Never pipe raw logs — flood = auto-stop.
- **Transient-failure tolerance** in poll loops: `curl ... || true` so one failed request doesn't
  kill the whole monitor.
- **Poll intervals:** 30 s+ for remote APIs (rate limits); 0.5–1 s for local checks.
- **Specific `description`:** it appears in *every* notification. "errors in deploy.log" beats
  "watching logs."

### Anti-pattern table
| Anti-pattern | Why it's wrong | Fix |
|---|---|---|
| `tail -f` for a single "ready?" signal | Never exits; ties up the monitor till timeout | `Bash run_in_background` + `until` loop |
| `grep -m 1` to "stop after first match" | If log goes quiet, `tail` never gets SIGPIPE → hangs | Bounded poll loop that `break`s |
| Filtering only the success marker | Silent through crashloop/hang | Add `Traceback|FAILED|OOM|Killed` |
| Raw `tail -f log` (no grep) | Floods chat → auto-stopped | `grep --line-buffered` a tight pattern |
| Forgetting `2>&1` on a noisy-stderr cmd | Failures never notify | Merge stderr into the filtered stdout |

---

## 8. Relationship to sibling tools

- **`Bash` (`run_in_background`)** — the *one-notification* counterpart. Use when a single completion
  signal suffices. Monitor is its *many-notifications* sibling.
- **`TaskStop`** — how you cancel a Monitor early (mandatory for `persistent: true`).
- **`Read`** — reads the monitor's output file, including stderr that never became notifications.
- **`ScheduleWakeup` / cron** — *time-driven* re-invocation of the agent; Monitor is *event-driven*
  streaming. Different axis: "wake me on a schedule" vs. "ping me when a line appears."

```
★ Insight ─────────────────────────────────────
The cleanest way to choose: ask "what re-activates me?"
 • A clock        → ScheduleWakeup / loop
 • One condition  → Bash run_in_background (exits when true)
 • A stream of conditions → Monitor
The three cover the whole space of asynchronous re-entry into the agent loop.
─────────────────────────────────────────────────
```

---

## 9. Applied to *this* repo (Workflow Orchestrator IntelliJ plugin)

Concrete, ready-to-use monitors for this codebase (darwin host — note `fswatch` over `inotifywait`):

```bash
# Watch a runIde session for plugin errors/exceptions while you keep coding
tail -f build/idea-sandbox/system/log/idea.log \
  | grep -E --line-buffered "ERROR|WARN - .*orchestrator|Exception|at com.workflow"
```

```bash
# Stream a long Gradle module test run; emit progress + every failure signature
./gradlew :agent:test --rerun 2>&1 \
  | grep -E --line-buffered "PASSED|FAILED|BUILD SUCCESSFUL|BUILD FAILED|> Task|Exception"
```

```bash
# One-per-occurrence until done: watch a buildPlugin to completion
./gradlew clean buildPlugin 2>&1 \
  | grep -E --line-buffered "BUILD SUCCESSFUL|BUILD FAILED|FAILURE:|\.zip"
# (exits when gradle exits → natural terminal end)
```

```bash
# React/webview vitest watch — surface failing specs as they occur
npm run test 2>&1 | grep -E --line-buffered "✓|✗|FAIL|PASS|Error"
```

> ⚠️ For a *single* "build done" ping, prefer `Bash run_in_background` with an `until` loop on a log
> grep — per §4, an unbounded `tail -f` would over-stay. The `./gradlew … buildPlugin` recipe above
> is fine because gradle **exits**, ending the watch naturally.

---

## 10. Summary — the possibility space in three lines

1. **Anything expressible as "a shell process that prints one line per event" is monitorable.**
2. **Choose by notification cardinality:** one → `Bash` background; many → Monitor (bounded if there's
   a known end, `persistent` if open-ended).
3. **The two failure modes are buffering (use `--line-buffered`) and optimistic filters (match
   failures too) — both make a monitor *silently useless*, which is worse than noisy.**
