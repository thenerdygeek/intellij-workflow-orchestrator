# `run_command` — extended notes

`run_command` is the most architecturally complex single-action tool in the codebase.
On the surface it just runs a shell command, but the implementation has to solve four
distinct hard problems — shell selection across three operating systems, hard-blocking
genuinely dangerous patterns before a process is even spawned, sanitising and capping
arbitrarily large output streams, and handing the LLM a sterilised environment that
neither leaks the user's credentials nor lets the model hijack the build by setting
`PATH`. Each concern is owned by its own component so the tool itself stays
auditable.

## The four-component architecture

### 1. `ShellResolver` — pick the right shell, every time

Path: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/process/ShellResolver.kt`

Priority order is platform-specific:

- **Windows:** Git Bash → PowerShell 7+ → Windows PowerShell 5.1 → cmd.exe.
- **Unix (Linux):** `/bin/bash` → whatever `$SHELL` points at → `/bin/sh`.
- **macOS:** `/bin/bash` deliberately, *not* `/bin/sh`. On macOS `sh` is a symlink to
  `zsh` running in POSIX-compatibility mode; quirks around array word-splitting and
  trap handling differ enough to break common Homebrew shellouts. We pin to bash to
  give the LLM one stable target.

Bash on Unix is launched with `-l` (login shell). Without it, IDE-launched processes
inherit a stripped-down `PATH` that omits everything Homebrew, asdf, nvm, pyenv, etc.
configure in `.bash_profile` / `.zshrc`. The LLM types `gradlew` and gets
`command not found`. The login flag costs ~50ms per spawn and prevents that whole
category of confusion.

The resolver also classifies the command (`isLikelyBuildCommand`) so the tool can
apply a different idle-timeout default for long builds (60s) versus normal commands
(15s) — Gradle / Maven / npm / docker / pytest don't print anything for minutes at a
time and we don't want to misclassify quiet builds as stuck stdin prompts.

### 2. `DefaultCommandFilter` — hard-block before spawn

Path: `agent/src/main/kotlin/com/workflow/orchestrator/agent/security/DefaultCommandFilter.kt`

Returns a binary `Allow` / `Reject`. Rejected commands are *never* executed,
regardless of any user approval. This is intentionally distinct from
`CommandSafetyAnalyzer` (see "Two safety layers" below).

The blocklist is small and intentional — fork bombs, `rm -rf /`, `sudo`, `mkfs`,
`dd if=`, redirects to `/dev/sd*`, `curl … | sh`, `chmod -R 777 /`. These are
patterns that have no legitimate workflow use; they are never something a coding
agent should do. The list deliberately doesn't try to enumerate "risky" commands —
that's the approval gate's job, not this filter's.

The filter consults `CommandSafetyAnalyzer.tokenize` to distinguish unquoted
(structural) text from quoted (literal) text, so `grep "rm -rf" file.txt` is allowed
even though the literal string contains a blocked pattern.

### 3. `OutputCollector` — tail-biased truncation

Path: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/process/OutputCollector.kt`

Most tools in this codebase use **middle-truncation** — keep the first 60% and the
last 40%, drop the middle. That works well for diagnostics output where you want
both the summary header and the trailing count.

`run_command` is the exception: it uses `processOutputTailBiased` which keeps only
the last N characters. The rationale is empirical — when a build or test command
fails, the relevant information (failed assertion, stack trace, exit summary, "X
tests failed of Y") is *always* at the tail. Middle-truncation on a 50K-line
`./gradlew test` output reliably loses the failure counts. Tail-truncation reliably
preserves them.

The pipeline is: empty-check → `stripAnsi` (regex-strip ESC sequences) → Unicode
sanitisation (zero-width spaces, RTL overrides, BOM, format controls — all things
that look fine to a human but confuse the LLM and could be used for prompt
injection) → optional disk spill (when output exceeds 1MB, write the full content to
the session's spill directory and return a head/tail preview with the file path) →
truncate-to-tail. Cap defaults to 100K chars (twice the default 50K) because
build/test output legitimately fills more.

### 4. `ProcessEnvironment` — sterilise the env

Path: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/process/ProcessEnvironment.kt`

Three concerns, three sets of variables:

- **Sensitive vars stripped from the inherited environment** (~35 entries):
  `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `SOURCEGRAPH_TOKEN`, `GITHUB_TOKEN`,
  `AWS_SECRET_ACCESS_KEY`, `KUBECONFIG`, `VAULT_TOKEN`, `DATABASE_URL`,
  `PGPASSWORD`, `SSH_AUTH_SOCK`, etc. These exist in the IDE's process environment
  (because the IDE was launched from a shell that loaded `.zshrc`) but must not
  flow to spawned commands. `git push` doesn't need `OPENAI_API_KEY` and could
  exfiltrate it via a malicious post-commit hook.
- **Vars blocked from LLM-provided `env`** (~25 entries): `PATH`, `HOME`,
  `LD_PRELOAD`, `DYLD_INSERT_LIBRARIES`, `JAVA_TOOL_OPTIONS`, `CLASSPATH`,
  `PYTHONPATH`, `MAVEN_HOME`, etc. These are vectors the LLM could use to hijack
  the user's build — set `LD_PRELOAD` to a malicious .so, override `PATH` to point
  at a wrapper, swap out `MAVEN_HOME`. We reject these regardless of user approval.
- **Anti-interactive overrides applied to every spawn** (~15 entries):
  `PAGER=cat`, `GIT_PAGER=cat`, `EDITOR=cat`, `TERM=dumb`, `NO_COLOR=1`,
  `GIT_TERMINAL_PROMPT=0`, `GIT_ASKPASS=/bin/false`,
  `GIT_SSH_COMMAND=ssh -o BatchMode=yes`. Without these, `git log` opens `less`
  and the process hangs forever waiting for the LLM to press `q`.

## Two safety layers — and why they live in different packages

This is the single biggest source of confusion for new contributors, so it's worth
spelling out:

| Layer | Path | When it runs | What it does |
| --- | --- | --- | --- |
| `DefaultCommandFilter` | `agent/security/` | Inside `RunCommandTool.execute` after shell resolution, before process spawn | Hard binary `Allow`/`Reject`. Blocked commands are never executed. |
| `CommandSafetyAnalyzer` | `agent/security/` | Inside `AgentLoop`'s approval-gate dispatch, *before* `RunCommandTool.execute` is invoked | Risk classification (LOW / MEDIUM / HIGH) used to choose the approval-dialog wording and severity. Does not block. |

They share a package (`security/`) but execute in different layers. The filter is
defence-in-depth — even if a user approves a hard-blocked command in the UI, the
filter still rejects it. The analyser is the user-experience layer — it warns the
user about what they're about to approve.

A future contributor may notice that `RunCommandTool` directly instantiates
`DefaultCommandFilter` while it never references `CommandSafetyAnalyzer` at all.
That's by design; the analyser belongs to the loop's approval flow, not to the
tool's execution flow. **Observation surfaced as an audit note** so the asymmetry
shows up in the docs UI.

## Per-invocation approval, no session-trust

Approval policy: `ALWAYS_PER_INVOCATION` (hardcoded — see
`agent/loop/ApprovalPolicy.kt`). Every call requires a fresh approval. There is no
"allow `run_command` for this session" option in the UI. The other write tools
(`edit_file`, `create_file`, `revert_file`) can be allow-listed for the session, but
not this one.

The reasoning: `run_command` is unbounded. Approving "edit a file" once tells the
agent the user trusts the agent to edit files within the project — bounded, scoped,
reversible via revert_file or `git checkout`. Approving "run a command" once would
authorise *every* future command the agent dreams up, including `git push --force`,
`docker rm -f`, `gcloud sql databases delete`, `npm publish`. The blast radius isn't
a function of the file system any more — it's a function of whatever the operating
system can do. So we make the user re-confirm each time.

This is heavy on common workflows (running tests after every edit prompts approval
each time) but the alternative is silently delegating the whole machine to the LLM.
We err on the side of friction.

## Timeouts and the long-tail

Default per-tool timeout is 120s. `run_command` overrides to `LONG_TOOL_TIMEOUT_MS =
600_000` (10 minutes). The agent CLAUDE.md keeps the only `agent` (sub-agent
spawn) tool unlimited; everything else fits in one of the two tiers.

10 minutes is enough for a typical Gradle build, a non-pathological test suite, a
docker image build with a warm cache. It's *not* enough for first-time docker pulls
on a slow link, multi-module Spring Boot integration tests, or large `mvn package`
runs with assembly plugins. For those cases the agent should use `background=true`
and poll via `background_process` — that path has no wall-clock timeout, only the
configured ceiling.

The `timeout` parameter lets callers shorten this further (clamped to the
configured maximum from `AgentSettings.runCommandMaxTimeoutMinutes`, default 10
minutes). Lengthening past the configured ceiling silently clamps.

## Idle detection — passwords, prompts, and slow builds

The monitor loop polls every 500ms and tracks "last output time". If no output for
`idle_timeout` seconds (default 15s, 60s for build commands), the tail is classified
by `PromptHeuristics.classify`:

- `LikelyPasswordPrompt` ("Password:", "Enter passphrase", etc.) → kill immediately.
  Recovery is `ask_user_input` to obtain the secret, then re-run with the secret in
  the `env` parameter (never inline in `command` — that would log the secret).
- `LikelyStdinPrompt` (cmd.exe banner, REPL, anything that looks like an interactive
  prompt) → notify once; if the same shape persists across two idle windows with no
  output in between, kill. Recovery is `background=true` + `send_stdin`.
- `GenericIdle` → notify and keep waiting. Build commands legitimately go quiet
  during compilation.

Setting `on_idle=wait` disables idle handling entirely and just blocks until exit
or timeout. Useful for cases where a quiet stretch is expected (slow integration
tests, long sleeps in the script).

## Background mode — escape hatch for long-running

`background=true` returns immediately with a `bgId`. The process is registered in
`BackgroundPool[sessionId]` (max concurrent enforced) and the agent uses
`background_process` to monitor / read output / send stdin / kill. There's no
wall-clock timeout in this mode. Used for: dev servers, watcher modes (`gradle
--continuous`), long-running test suites the LLM doesn't want to block on.

Background processes are scoped to the session — when the user starts a new chat,
all background processes from the previous session are killed. The exception is
processes that were detached on ready (via `runtime_exec`'s readiness-then-detach
flow); those are owned by the IDE's `ExecutionManager` instead and survive session
boundaries.

## Anti-double-wrap defence

Path: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/process/CommandPrefixStripper.kt`

When `run_command` wraps the command in the resolved shell, an LLM-emitted leading
`cmd /c …`, `bash -c …`, or `powershell -Command …` becomes double-wrapping. On
Windows this *breaks quoting* — the inner `cmd` strips one layer, the outer cmd
strips another, and a command like `git commit -m "fix: x"` ends up running `git
commit -m fix: x` (broken into three args). The stripper detects and removes the
redundant prefix, prepends a `[NOTE]` warning to the result, and lets the command
proceed correctly. Not a security feature — a quoting feature.

## Post-mutation VFS refresh

Some commands mutate files outside the agent's edit_file path: `git checkout`,
`git stash pop`, `git pull`, `./gradlew clean`. After such a command, the IDE's
VFS cache and earlier `read_file` results in the conversation are stale.
`CommandMutationClassifier` classifies the command, `PostMutationRefresh` triggers
a VFS refresh at the appropriate scope (working dir for most, whole project for git
mutators), and the tool result is prepended with a `[VFS NOTE]` block telling the
LLM to re-read any file before editing. For `BuildClean` we additionally drop the
JPS state cache so the next build doesn't reuse stale incremental output.

## Counterfactual — what if we dropped this?

The dedicated runtime tools (`runtime_exec(action=run_config)`, `java_runtime_exec`,
`python_runtime_exec`, `coverage`, `build`) cover the *named* execution paths —
launching a known run config, running JUnit/TestNG, pytest, coverage, gradle
tasks. Probably 60% of the agent's day-to-day execution.

The other 40% is the long tail: `git status`, `gh pr list`, `docker ps`, `kubectl
logs`, `terraform plan`, `jq` over a downloaded JSON, `grep` (when `search_code`
isn't quite right), `find`, `du`, `df`, `ls -la`, `cat /etc/hosts`. Without
`run_command`, every one of these would need its own dedicated tool — an
unmaintainable explosion. Or the LLM would lose access to them entirely.

Net verdict: **strong keep**. The complexity is an honest reflection of the
problem. The four-component split is the right architecture — each piece is small
and testable, the responsibilities don't blur, and the blast-radius is contained
in `DefaultCommandFilter` + the per-invocation approval gate.
