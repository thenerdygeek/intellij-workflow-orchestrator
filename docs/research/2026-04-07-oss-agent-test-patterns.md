# OSS Agent Test Patterns — Research Notes

**Date:** 2026-04-07
**Author:** research agent
**Scope:** How open-source AI coding agents (Cline, Aider, OpenHands, Codex CLI, Continue.dev, Goose) test their tool layer, with the goal of informing a new comprehensive test suite for the `:agent` module of the Workflow Orchestrator IntelliJ plugin (which is a Kotlin port of Cline).

**Sources.** All six repos were cloned shallowly at `HEAD` and read on disk. Exact commits used:

| Repo | Commit | Clone path |
|---|---|---|
| cline/cline | `5df470bf4883a6ddb9a3016a20d8fdb3b14558cc` | `/tmp/oss-agents/cline` |
| Aider-AI/aider | `bdb4d9ff8ef88c3015a9845119bff37f49c93d7b` | `/tmp/oss-agents/aider` |
| All-Hands-AI/OpenHands | `e46bcfa82ff82ce6ad21a4349fa2da9b8860f3a4` | `/tmp/oss-agents/openhands` |
| openai/codex | `89f1a44afab5fa1f2cee9b12f7d9bec444e2d8b0` | `/tmp/oss-agents/codex` |
| continuedev/continue | `a1ead04122664b308131bdf1c3345ead00146bbc` | `/tmp/oss-agents/continue` |
| block/goose | `0028d97c23cb2c850b11b42bd2afa5702c31aa6a` | `/tmp/oss-agents/goose` |

GitHub permalinks use these commit SHAs so the citations stay stable.

---

## 1. Executive Summary (actionable findings, 15 bullets)

1. **Script LLM responses as an async generator sequence, not as a complete fake.** Cline's best test (`SubagentRunner.test.ts`) uses `sinon.stub().onFirstCall().callsFake(async function* () { yield toolCallChunk })` and `onSecondCall()` to drive a multi-turn loop through a real `SubagentRunner`. Our `AgentLoopTest.SequenceBrain` already matches this idea; we should generalise it. See `cline/src/core/task/tools/subagent/__tests__/SubagentRunner.test.ts:149-210`.
2. **Trajectory replay beats mocking for end-to-end tests.** OpenHands ships a `replay_trajectory_path` config option and committed JSON trajectories (`basic.json`, `basic_gui_mode.json`) that drive the real controller loop through a pre-recorded sequence of actions+observations, asserting only on terminal state. We have nothing like this and should steal it directly. See `openhands/tests/runtime/test_replay.py:32-53` and `openhands/tests/runtime/trajs/basic.json`.
3. **Build a test DSL for LLM wire-format events** (one helper per event type). Codex has ~30 `ev_*` constructors — `ev_function_call`, `ev_assistant_message`, `ev_reasoning_item`, `ev_apply_patch_call`, `ev_local_shell_call` — plus `mount_sse_sequence`, `mount_sse_once_match`, and an SSE `sse(Vec<Value>)` builder. Tests then read like `mount_sse_sequence(&server, vec![sse(vec![ev_function_call(...), ev_completed(...)])])`. This is the single cleanest multi-step test ergonomic across all six frameworks. See `codex/codex-rs/core/tests/common/responses.rs:566-905`.
4. **Use `wiremock` / `MockWebServer` for the LLM itself, not for upstream APIs.** Codex mocks the OpenAI Responses endpoint with `wiremock::MockServer` and inspects captured requests via a `ResponseMock { requests: Arc<Mutex<Vec<ResponsesRequest>>> }` helper exposing `single_request()`, `saw_function_call(call_id)`, `function_call_output_text(call_id)`. We already use MockWebServer for Jira/Bamboo/Sonar/Bitbucket; we should point it at the Sourcegraph Cody endpoint too for end-to-end tests. See `codex/codex-rs/core/tests/common/responses.rs:40-83`.
5. **Extract pure functions from tool handlers and unit-test those separately.** Cline does this aggressively: `formatFileContentWithLineNumbers`, `getReadToolDisplayedLineRange`, `resolveCommandTimeoutSeconds`, `isLikelyLongRunningCommand` are all exported from handler files and tested without any handler/task stubbing. This gives pennies-per-test coverage of the gnarly logic (line-range clamping, timeout policy, pattern matching). See `cline/src/core/task/tools/handlers/ExecuteCommandToolHandler.ts` + `ExecuteCommandToolHandler.timeout.test.ts:1-31` and `ReadFileToolHandler.lineNumbers.test.ts:33-98`.
6. **One big golden-corpus test file per safety concern.** Cline's `CommandPermissionController.test.ts` is 990 lines — a flat catalogue of allow/deny rule tuples. This is the right shape for our `CommandSafetyAnalyzer` expansion: one file, structured by rule category, easy to extend without test-file churn. See `cline/src/core/permissions/CommandPermissionController.test.ts`.
7. **Stub tool *handlers*, not tools, for multi-step flow tests.** Cline's `SubagentToolHandler.test.ts` uses `sinon.stub(SubagentRunner.prototype, "run").callsFake(...)` to simulate parent-child flow without nesting LLM calls. It asserts on observable state — approval ask count, streaming say calls, aggregated usage. Our `SpawnAgentTool` test should stub `SubagentRunner`/`AgentLoop` at this boundary. See `cline/src/core/task/tools/handlers/__tests__/SubagentToolHandler.test.ts:202-328`.
8. **A `MockProvider : Provider` trait implementation is the cleanest context-management test harness.** Goose's `MockCompactionProvider` (180 lines) implements the real `Provider` trait and simulates a ContextLengthExceeded error when `input_tokens > 20000` with an `AtomicBool` flip once compaction has happened. This exercises the *full* compaction loop against the real agent. We should write an equivalent `FakeCodyClient : SourcegraphChatClient` that does the same. See `goose/crates/goose/tests/compaction.rs:21-180`.
9. **Aider's `coder.send = mock_send` monkey-patch pattern is remarkably effective** for file-mutation tests. It just writes to `coder.partial_response_content = "ok"` and returns `[]`. Aider then asserts on file-system side effects inside a `GitTemporaryDirectory()` context manager. It's ugly but cheap: one line of mocking per test. See `aider/tests/basic/test_coder.py:462-478`.
10. **Nobody has a fully in-memory FS (jimfs/memfs). Everyone uses real temp dirs.** Aider uses `GitTemporaryDirectory()` (creates a real on-disk git repo), Cline uses `os.tmpdir() + mkdir`, Continue runs actual `node --version` in CI, OpenHands uses `temp_dir` fixtures. For Kotlin/JUnit5 this means `@TempDir` is the right answer (which we already use); don't chase a `jimfs` port.
11. **Shell-exec tests in Continue.dev actually run real commands, branching by `process.platform`.** They do not sandbox or record — they run `echo "hello world"` on Unix and `Write-Output "hello world"` on Windows, assert on `.trim()`. This is acceptable for our `run_command` tool but means `:agent` tests must be CI-OS-aware. See `continue/extensions/cli/src/tools/runTerminalCommand.test.ts:11-109`.
12. **For shell exec with timeouts/cancellation: test the *policy*, not the subprocess.** Cline tests `resolveCommandTimeoutSeconds()` and `isLikelyLongRunningCommand()` as pure functions — no ProcessBuilder, no subprocess. The actual exec is covered separately in e2e smoke tests. See `cline/src/core/task/tools/handlers/__tests__/ExecuteCommandToolHandler.timeout.test.ts`.
13. **Cline has a 3-layer eval strategy we should copy verbatim.** Layer 1: contract unit tests (API transform, tool-call parsing); Layer 2: smoke tests (5 curated scenarios, 3 trials each for `pass@k` / `pass^k` metrics, runs via CLI with real keys, ~minutes); Layer 3: e2e with `cline-bench` (12 real-world bug fixes, Docker/Daytona, hours, nightly CI). The smoke test scenarios are tiny JSON configs. See `cline/evals/README.md` and `cline/evals/smoke-tests/scenarios/01-create-file/config.json`.
14. **Metadata-only tool tests are an anti-pattern everyone else has moved past.** Only `:agent`'s existing integration-tool tests (per `2026-04-07-existing-test-infrastructure.md` §4.1) still do "test name, test enum, test schema" without exercising execution. No OSS framework does this — they all stub dependencies and execute the handler.
15. **Invest in a shared `createTaskConfig()`-style fixture factory in Kotlin.** Cline's `createConfig()` in `SubagentToolHandler.test.ts:14-104` is 90 lines of relaxed stubs wrapping `TaskConfig`, `callbacks`, `services`, `autoApprover`. Every handler test calls it with overrides (`createConfig({ autoApproveSafe: true })`). Our tests repeatedly do `mockk<Project>(relaxed = true)` ad-hoc; a `TestToolContext` factory would collapse ~200 lines across the suite.

---

## 2. Per-framework sections

### 2.1 Cline (priority 1)

**Stack.** TypeScript, `mocha` + `should` + `sinon` + `chai` (mixed; newer tests use `node:assert/strict` or `chai.expect`). 155 `*.test.ts` files in `src/`, plus a separate `evals/` tree (smoke + e2e) and a `webview-ui/` test bed. `package.json` script `test:unit` runs mocha via the VSCode test harness.

#### A. LLM mocking / determinism

Two distinct patterns:

**Pattern A1 — API provider layer.** Stub the raw SDK client that the provider wraps and return async iterables:

```ts
// cline/src/core/api/providers/__tests__/anthropic.test.ts:12-80
const createAsyncIterable = (data: readonly unknown[] = []) => ({
    [Symbol.asyncIterator]: async function* () { yield* data },
})

sinon.stub(handler as any, "ensureClient").returns({
    messages: { create: sinon.stub().resolves(createAsyncIterable()) },
    beta: { messages: { create: betaCreate } },
})
```

Source: https://github.com/cline/cline/blob/5df470bf4883a6ddb9a3016a20d8fdb3b14558cc/src/core/api/providers/__tests__/anthropic.test.ts#L12-L80

**Pattern A2 — Full agent-loop layer.** Stub `coreApi.buildApiHandler` to return an object with a `createMessage` that is a sinon stub with per-call async generator scripts:

```ts
// cline/src/core/task/tools/subagent/__tests__/SubagentRunner.test.ts:149-210
const createMessage = sinon.stub()
createMessage.onFirstCall().callsFake(async function* () {
    yield { type: "tool_calls", tool_call: { function: {
        id: "toolu_subagent_1",
        name: ClineDefaultTool.LIST_FILES,
        arguments: JSON.stringify({ path: ".", recursive: false }),
    } } }
})
createMessage.onSecondCall().callsFake(async function* (_systemPrompt, conversation) {
    // Assert that the second call received the tool_result from turn 1
    const userMessage = conversation[2]
    const toolResult = userMessage.content.find(b => b.type === "tool_result")
    assert.equal(toolResult.tool_use_id, "toolu_subagent_1")
    yield { type: "tool_calls", tool_call: { function: {
        id: "toolu_subagent_complete_1",
        name: ClineDefaultTool.ATTEMPT,
        arguments: JSON.stringify({ result: "done" }),
    } } }
})
```

Source: https://github.com/cline/cline/blob/5df470bf4883a6ddb9a3016a20d8fdb3b14558cc/src/core/task/tools/subagent/__tests__/SubagentRunner.test.ts#L149-L210

This pattern simultaneously (a) drives multi-turn execution through the real `SubagentRunner.run()`, (b) asserts on `createMessage.callCount`, and (c) validates that tool-use / tool-result IDs match across turns — i.e. it catches the most common ReAct-loop bug (mismatched `tool_use_id`) without any network.

**Contract tests for tool-call parsing.** `src/core/api/transform/__tests__/tool-parsing.test.ts` exercises the Anthropic ↔ OpenAI format converter with synthetic `ClineStorageMessage` blocks, verifying things like "truncate long tool IDs to 40 chars" and "transform `fc_`-prefixed Responses API IDs". Pure function tests, no IO. See https://github.com/cline/cline/blob/5df470bf4883a6ddb9a3016a20d8fdb3b14558cc/src/core/api/transform/__tests__/tool-parsing.test.ts#L23-L100.

**Streaming/cancellation tests.** `src/core/api/providers/__tests__/openrouter.test.ts` and `src/core/api/transform/__tests__/openrouter-stream.test.ts` use recorded SSE chunks replayed through the stream transformer.

**Malformed output.** `src/core/task/__tests__/Task.processNativeToolCalls.test.ts` and the context-error handler tests (`src/core/context/context-management/__tests__/context-error-handling.test.ts`) exercise dropped tool calls, invalid JSON args, and missing tool_use_ids.

#### B. Tool-layer unit tests

**Organisation.** One test file per concern, not per tool: `ReadFileToolHandler.fileNotFound.test.ts` and `ReadFileToolHandler.lineNumbers.test.ts` are *separate files* for the same handler. Test files are scoped tightly — the largest file-tool test is ~250 lines.

**Metadata vs execution.** No tests check only metadata. Every handler test actually runs the handler (via `handler.execute(config, block)`) or calls an extracted pure function (`formatFileContentWithLineNumbers`, `resolveCommandTimeoutSeconds`). Example: https://github.com/cline/cline/blob/5df470bf4883a6ddb9a3016a20d8fdb3b14558cc/src/core/task/tools/handlers/__tests__/ReadFileToolHandler.lineNumbers.test.ts#L33-L98

**Happy:error ratio.** Roughly 40:60 — error paths dominate. `SubagentToolHandler.test.ts` has 9 tests; only 2 are pure happy paths. `ReadFileToolHandler.lineNumbers.test.ts` has ~15 assertions, most exercise edge cases (inverted start/end, clamped start, clamped end, zero start, end past file).

#### C. Multi-step / end-to-end scenarios

Multi-step scenarios live in two places:

1. **SubagentRunner test** (cited above) — single test file, drives a real 2-turn loop through `SubagentRunner.run()` with scripted LLM responses.
2. **`evals/smoke-tests/scenarios/`** — JSON configs like `{name, description, prompt, expectedFiles, expectedContent, timeout}` shelled to the `cline` CLI with `-s` flags.

Example: https://github.com/cline/cline/blob/5df470bf4883a6ddb9a3016a20d8fdb3b14558cc/evals/smoke-tests/scenarios/01-create-file/config.json

Full trajectory assertion (sequence of tool calls) is done in one place: `SubagentRunner.test.ts` inspects `createMessage.callCount` and argument order in `onSecondCall`. There is no "record/replay of arbitrary tool-sequence" harness — Cline is more of a "scripted LLM + real loop" shop.

**Eval harness:** separate from unit tests, runs through the CLI binary, uses real API keys, calculates `pass@k` / `pass^k` / flakiness metrics across 3 trials per scenario. Docs at https://github.com/cline/cline/blob/5df470bf4883a6ddb9a3016a20d8fdb3b14558cc/evals/README.md.

#### D. Shell execution testing

**Policy tests only.** `ExecuteCommandToolHandler.timeout.test.ts` is 31 lines and tests `resolveCommandTimeoutSeconds(cmd, explicit, managedEnabled)` and `isLikelyLongRunningCommand(cmd)` as pure functions. No subprocess spawn.

**Command permissions.** `CommandPermissionController.test.ts` is 990 lines of allow/deny rule tests against parsed env-var config. It is the single largest test file in the project — explicitly a golden corpus. No subprocess spawn either; it tests the *decision* layer.

**Actual exec** is covered in `src/integrations/terminal/CommandOrchestrator.test.ts` and indirectly by the smoke-test layer.

No sandboxing tests (Cline doesn't sandbox; the timeout test file notes this).

#### E. File ops

**TempDir-backed.** `src/core/storage/__tests__/disk.test.ts` uses `path.join(os.tmpdir(), "disk-test-${Date.now()}-${random}")` with `fs.mkdir(... { recursive: true })` and cleans up in `afterEach`. See https://github.com/cline/cline/blob/5df470bf4883a6ddb9a3016a20d8fdb3b14558cc/src/core/storage/__tests__/disk.test.ts#L24-L40.

**Diff apply.** Unified-diff parsing and fuzzy matching are tested inside tool-handler tests against string fixtures, not real files. The `AttemptCompletionHandler.doubleCheck.test.ts` file exercises the double-check logic that reverts to the previous state if a newly written file doesn't match the requested diff.

#### F. HTTP-backed tool tests

Tools that hit HTTP (e.g. `fetch_instructions`, MCP stream handling) use **nock**-style stubs at the `fetch` layer:
- `src/services/mcp/__tests__/StreamableHttpReconnectHandler.test.ts` stubs transport-level errors
- `src/core/api/providers/__tests__/*.test.ts` stubs the SDK's `ensureClient()` return value

Cline doesn't use `MockWebServer` equivalents — it goes one level up and stubs the typed client.

#### G. Sub-agent / delegation

Tested in two files:
- `SubagentToolHandler.test.ts` (parent-side: approval, fan-out, aggregation, failure partial reporting)
- `SubagentRunner.test.ts` (child-side: scripted 2-turn loops, token accounting, compaction check)

**Key approach:** the parent-side tests stub `SubagentRunner.prototype.run` entirely (`sinon.stub(SubagentRunner.prototype, "run").callsFake(...)`), so the test runs the parent handler against a fake runner. The child-side tests stub only the API layer, so they run the real `SubagentRunner`. Two different seams for two different concerns.

Cancellation is tested via "denied at approval" (`taskAskResponse: "noButtonClicked"`) and by asserting `runStub` was `notCalled`.

Context handoff is tested via the `new_task` handler tests (`src/core/task/__tests__/Task.ask.test.ts` and similar).

#### H. Memory / state persistence

- `SessionStoreTest` equivalent: `src/core/storage/__tests__/disk.test.ts` exercises the JSONL/task-history disk layout.
- `ContextManager.test.ts` (495 lines) uses a minimal `createMockApi(contextWindow: number)` that only has `.getModel().info.contextWindow`, and drives `getNextTruncationRange(messages, prevRange, "half" | "quarter")` through synthetic `createMessages(count)` fixtures. Pure-function testing of the truncation math. See https://github.com/cline/cline/blob/5df470bf4883a6ddb9a3016a20d8fdb3b14558cc/src/core/context/context-management/__tests__/ContextManager.test.ts#L1-L80.

#### I. Safety / guardrails

`CommandPermissionController.test.ts` is the golden corpus — flat catalogue of `{env config, command, expected allow/deny reason}` tuples, 990 lines.

#### J. Eval harnesses

`evals/` tree:
- `evals/smoke-tests/scenarios/` — 8 JSON scenarios, each `config.json` with prompt + expectedFiles + expectedContent + timeout
- `evals/e2e/run-cline-bench.ts` — runs the external `cline/cline-bench` git submodule (12 tasks) in Docker via Harbor
- `evals/analysis/src/` — pass@k, classifier, reporters; has its own `__tests__/` for the metric math

CI: PR gate runs contract + smoke (~3min); nightly e2e is documented as TODO.

#### K. Test infrastructure & patterns

- **Test framework:** mocha + should/chai + sinon. Mixed style (some files use `node:assert`).
- **No shared base classes.** Tests declare their own `createConfig()` helpers inline.
- **`createConfig()` factory** is the de-facto base class: 90 lines of relaxed stubs wrapping `TaskConfig`. Overridden per-test via options pattern. See https://github.com/cline/cline/blob/5df470bf4883a6ddb9a3016a20d8fdb3b14558cc/src/core/task/tools/handlers/__tests__/SubagentToolHandler.test.ts#L14-L104.
- **Speed:** the unit layer is fast (~minutes).
- **Flakiness:** the `openrouter-stream.test.ts` and `SubagentRunner.test.ts` files both have `afterEach(() => sinon.restore())` — sinon sandbox discipline is uniform.

---

### 2.2 Aider (priority 2)

**Stack.** Python, stdlib `unittest` + `unittest.mock`. ~30 test files under `tests/basic/`. Runs via `pytest` in CI but tests use `unittest.TestCase`.

#### A. LLM mocking / determinism

**Pattern:** monkey-patch `coder.send` directly with a 4-line function. This is the trick that makes Aider's tests so lean:

```python
# aider/tests/basic/test_coder.py:462-478
def mock_send(*args, **kwargs):
    coder.partial_response_content = "ok"
    coder.partial_response_function_call = dict()
    return []

coder.send = mock_send
coder.run(with_message="hi")
self.assertEqual(len(coder.abs_fnames), 2)
```

Source: https://github.com/Aider-AI/aider/blob/bdb4d9ff8ef88c3015a9845119bff37f49c93d7b/tests/basic/test_coder.py#L462-L478

The `Coder.run()` method checks `self.partial_response_content` after "sending", so setting it inline lets the test drive file-add / file-rename / encoding logic without ever invoking a model.

**LiteLLM retry / rate-limit tests** use `@patch("litellm.completion")` with `side_effect = [RateLimitError(...), mock_response]`. See https://github.com/Aider-AI/aider/blob/bdb4d9ff8ef88c3015a9845119bff37f49c93d7b/tests/basic/test_sendchat.py#L22-L55.

**Malformed output.** `test_editblock.py` (618 lines) tests the SEARCH/REPLACE block parser with hand-crafted strings containing fence-inside-content, missing filenames, fuzzy matches, and inverted blocks. No LLM at all — the parser is a pure function.

#### B. Tool-layer unit tests

Aider doesn't have "tools" per se — it has Coders (`EditBlockCoder`, `UnifiedDiffCoder`, `WholeFileCoder`, `ArchitectCoder`, etc.). Each has a dedicated test file: `test_editblock.py`, `test_udiff.py`. Tests focus on the edit-format parser + applier.

Happy:error ratio ~25:75 for the parsers.

#### C. Multi-step / E2E

Separate `benchmark/` tree with `benchmark.py` driving the polyglot-benchmark exercise corpus (Exercism exercises in Python/Rust/Go/JS/C++/Java) through Docker. Not part of the unit test run. See https://github.com/Aider-AI/aider/blob/bdb4d9ff8ef88c3015a9845119bff37f49c93d7b/benchmark/benchmark.py.

#### D. Shell execution

`tests/basic/test_run_cmd.py` is only 11 lines. Most shell testing is via `test_commands.py` which tests the `/run`, `/add`, `/drop` slash commands. Aider's shell exec is a thin wrapper around `subprocess`, and the tests don't try to stub it.

#### E. File ops

`GitTemporaryDirectory()` context manager creates a real `git init` repo in `tempfile.mkdtemp()`. Tests assert on file presence and repo state after calling `coder.run()`. See https://github.com/Aider-AI/aider/blob/bdb4d9ff8ef88c3015a9845119bff37f49c93d7b/tests/basic/test_coder.py#L25-L52.

#### F. HTTP

Stubbed at `litellm.completion` level with `@patch`.

#### G. Sub-agent

Aider's architect/editor is a 2-LLM pipeline, not a sub-agent. Tests in `test_coder.py` cover the `ArchitectCoder` via the same `mock_send` trick.

#### H. Memory

`tests/basic/test_history.py` tests the chat-history summarizer (Aider's auto-compaction) with stubbed model calls.

#### I. Safety

`tests/basic/test_ssl_verification.py` and `test_aws_credentials.py`. No command safety analyzer — Aider trusts the user to approve shell commands interactively.

#### J. Eval

The `benchmark/` tree is Aider's eval. Non-CI; runs against `polyglot-benchmark` exercises.

#### K. Infra

- Lean, flat `tests/basic/` directory.
- `GitTemporaryDirectory` + `ChdirTemporaryDirectory` utilities.
- Tests are slow-ish because they create real git repos per test, but still well under a minute total.

---

### 2.3 OpenHands (priority 3)

**Stack.** Python + `pytest` + `unittest.mock` + `litellm`. Large test tree: `tests/unit/` (~150 files), `tests/runtime/` (integration with docker/runtime), `evaluation/` (SWE-bench + others).

#### A. LLM mocking / determinism

**Pattern:** `@patch('openhands.llm.llm.litellm_completion')` at the module-import point. Tests either `.return_value = mock_response` or `.side_effect = [...]` for multi-call. See `openhands/tests/unit/llm/test_llm.py:169-250`.

**Agent controller tests** use heavy `MagicMock(spec=Agent)` instances with `agent.llm = MagicMock(spec=LLM)` and `agent.get_system_message.return_value = system_message`. The event stream and state flow are then driven manually by pushing events into a real `EventStream`. See `openhands/tests/unit/controller/test_agent_delegation.py:75-120`.

#### B. Tool-layer unit tests

`tests/unit/agenthub/test_function_calling.py` tests the OpenAI function-calling adapter (274 lines). `test_str_replace_editor_tool.py` tests the editor tool.

`tests/unit/controller/test_is_stuck.py` is 1029 lines and the model for how to test a stuck detector — 16 named test cases, each constructs a synthetic action/observation history and asserts `stuck_detector.is_stuck()`.

#### C. Multi-step / E2E — TRAJECTORY REPLAY

**The single most valuable pattern in this entire research.** OpenHands has a `replay_trajectory_path` config that feeds a pre-recorded JSON array of `{source, action, args}` / observation events into the controller. Tests call `run_controller(config, initial_user_action=NullAction(), runtime=runtime)` and assert only on `state.agent_state == AgentState.FINISHED`:

```python
# openhands/tests/runtime/test_replay.py:32-53
def test_simple_replay(temp_dir, runtime_cls, run_as_openhands):
    runtime, config = _load_runtime(temp_dir, runtime_cls, run_as_openhands)
    config.replay_trajectory_path = str(
        (Path(__file__).parent / 'trajs' / 'basic.json').resolve()
    )
    state = asyncio.run(run_controller(config=config, initial_user_action=NullAction(), runtime=runtime))
    assert state.agent_state == AgentState.FINISHED
```

Source: https://github.com/All-Hands-AI/OpenHands/blob/e46bcfa82ff82ce6ad21a4349fa2da9b8860f3a4/tests/runtime/test_replay.py#L32-L53

Trajectory files are real agent runs captured from production and committed to git (`trajs/basic.json`, `trajs/basic_gui_mode.json`, `trajs/basic_interactions.json`, `trajs/wrong_initial_state.json`). Each file is several hundred KB of JSON events.

#### D. Shell execution

`tests/runtime/test_bash.py` is 1469 lines and actually spins up docker runtimes to run real bash sessions. `tests/unit/runtime/test_cmd_retry.py` is the unit-level cousin — it mocks the `runtime` with a `mock_runtime` fixture and tests timeout/retry policy as pure functions (`is_bash_session_timeout_with_timeout_exit_code`, `calculate_retry_delay_exponential`, `extract_error_content_from_error_observation`). Same pattern as Cline's timeout tests.

#### E. File ops

`tests/test_fileops.py`, `tests/runtime/test_aci_edit.py`. Uses `tempfile` + the real runtime.

#### F. HTTP

`@patch('requests.post')` at module boundary; `httpx` mocking in MCP tests.

#### G. Sub-agent / delegation

`test_agent_delegation.py` (489 lines) is the reference implementation for testing hub-and-spoke delegation. Uses `MagicMock(spec=Agent)` for parent and child, in-memory `EventStream` with `EventStreamSubscriber`, and a `create_mock_agent_factory` that registers the mock agent's LLM in the `llm_registry`. The test drives `AgentDelegateAction` through the controller and verifies the state transitions plus metrics aggregation.

#### H. Memory / state

`tests/unit/storage/` and `tests/unit/memory/`. `InMemoryFileStore` (from `openhands.storage.memory`) is the in-memory backing store used throughout — avoids touching disk.

#### I. Safety

`tests/unit/security/` has LLM-based risk analyzer tests and a command confirmation mode.

#### J. Eval

`evaluation/` tree is huge — SWE-bench integration, polyglot benchmarks, agent-bench, GPQA. Run manually via Makefile targets; not part of CI.

#### K. Infra

- **`conftest.py` with shared fixtures.** `tests/unit/controller/conftest.py` provides `llm_registry`, `mock_event_stream`, `mock_parent_agent`, `mock_child_agent` etc. This is the pattern we should port to Kotlin with JUnit5 `@RegisterExtension` or a shared test utilities module.
- **`InMemoryFileStore`** — built for tests.
- Slow suite (~20min unit + hours for runtime tests).

---

### 2.4 Codex CLI (priority 4)

**Stack.** Rust, `cargo test` + `wiremock` + `tokio` + `pretty_assertions`. The test layout is the cleanest of the six.

#### A. LLM mocking / determinism

**`wiremock::MockServer` + a rich DSL of event constructors.** The helper module at `core/tests/common/responses.rs` (1630 lines) exposes:

- **Event constructors** (`ev_*`): `ev_function_call(call_id, name, arguments)`, `ev_assistant_message(id, text)`, `ev_reasoning_item(id, summary, raw)`, `ev_apply_patch_call(...)`, `ev_local_shell_call(call_id, status, command)`, `ev_web_search_call_*`, `ev_image_generation_call`, `ev_custom_tool_call`, etc. ~30 in total.
- **SSE builders**: `sse(Vec<Value>) -> String`, `sse_completed(id)`, `sse_failed(id, code, msg)`, `sse_response(body)`.
- **Mount helpers**: `mount_sse_once`, `mount_sse_sequence`, `mount_sse_once_match`, `mount_compact_json_once`, `mount_models_once`.
- **Request assertions**: `ResponseMock::single_request()`, `saw_function_call(call_id)`, `function_call_output_text(call_id)`, `last_request()`.

Test then reads like:

```rust
// conceptual — drawn from core/tests/suite/compact.rs
mount_sse_sequence(&server, vec![
    sse(vec![ev_function_call("call_1", "apply_patch", patch_json), ev_completed("r1")]),
    sse(vec![ev_assistant_message("m1", SUMMARY_TEXT), ev_completed("r2")]),
]);
test_codex.submit_turn("please edit the file").await?;
wait_for_event(&mut rx, |ev| matches!(ev, EventMsg::ItemCompleted(_))).await;
assert!(response_mock.saw_function_call("call_1"));
```

Sources:
- Event constructors: https://github.com/openai/codex/blob/89f1a44afab5fa1f2cee9b12f7d9bec444e2d8b0/codex-rs/core/tests/common/responses.rs#L566-L905
- `ResponseMock` capture: https://github.com/openai/codex/blob/89f1a44afab5fa1f2cee9b12f7d9bec444e2d8b0/codex-rs/core/tests/common/responses.rs#L40-L83
- Full test using the DSL: https://github.com/openai/codex/blob/89f1a44afab5fa1f2cee9b12f7d9bec444e2d8b0/codex-rs/core/tests/suite/compact.rs#L1-L80

#### B. Tool-layer unit tests

`core/tests/suite/` has 30+ suite files, one per concern: `compact.rs`, `abort_tasks.rs`, `apply_patch_cli.rs`, `approvals.rs`, `codex_delegate.rs`, `compact_remote.rs`, `compact_resume_fork.rs`, `fork_thread.rs`, `hooks.rs`, `image_rollout.rs`, `memories.rs`, `agent_jobs.rs`, `agent_websocket.rs`, etc.

#### C. Multi-step / E2E

The entire `core/tests/suite/` tree IS the multi-step test surface. Every suite test boots a real `Codex` process via `test_codex::test_codex()` against a wiremock mockserver, then drives multi-turn flows through the mocked Responses API. `core/tests/common/test_codex.rs` is 966 lines and provides `TestCodex::submit_turn(prompt)`, `submit_turn_with_policy`, `submit_turn_with_service_tier`, `resume(...)`, `build_with_streaming_server`, `build_with_websocket_server`.

#### D. Shell execution

- **Exec policy:** `execpolicy/tests/basic.rs` is 963 lines of rule parser + decision-engine tests. Pure function tests against a parsed `Policy`. See https://github.com/openai/codex/blob/89f1a44afab5fa1f2cee9b12f7d9bec444e2d8b0/codex-rs/execpolicy/tests/basic.rs.
- **Linux sandbox:** `linux-sandbox/tests/` runs real landlock-sandboxed processes with syscall expectations.
- **Seatbelt (macOS):** `core/tests/suite/seatbelt.rs` tests the seatbelt sandbox profile.

#### E. File ops

`apply-patch/tests/` exercises Codex's unified-diff apply implementation with golden input/output pairs.

#### F. HTTP

All HTTP testing goes through `wiremock::MockServer`. Tests build `MockBuilder::new().and(method("POST")).and(path_regex(...))` and mount per-test expectations.

#### G. Sub-agent / delegation

`core/tests/suite/codex_delegate.rs` and `hierarchical_agents.rs` test the `spawn_agent` / `wait_agent` / `send_input` / `close_agent` tool family end-to-end through wiremock, verifying depth limits, parent-child fork state, and shared rollouts.

#### H. Memory / state

`core/tests/suite/memories.rs`, `compact.rs`, `compact_resume_fork.rs`, `fork_thread.rs`. The `context_snapshot` helper in `core/tests/common/context_snapshot.rs` captures and diffs internal state across turns for rollback assertions.

#### I. Safety

- `execpolicy/tests/basic.rs` — 963 lines of policy tests
- `core/tests/suite/approvals.rs` — approval-gate flow
- `core/tests/suite/exec_policy.rs` — integration of exec policy with exec flow

#### J. Eval

No committed SWE-bench harness inside this repo; Codex CLI is tested purely via the wiremock-mocked suite tests. This works because the suite is large (30+ files) and uses real end-to-end binaries.

#### K. Infra

- **`core_test_support` crate.** All the helpers live in `core/tests/common/` as modules (`responses.rs`, `test_codex.rs`, `context_snapshot.rs`, `apps_test_server.rs`, `tracing.rs`, `process.rs`). Imported via `use core_test_support::responses::*`.
- **`skip_if_no_network!()` macro** gates tests that need real network.
- `pretty_assertions::assert_eq` for readable diffs.
- Tests run via `cargo nextest run` (parallel).

---

### 2.5 Continue.dev (priority 5)

**Stack.** TypeScript, `vitest` (and `jest` in some places). Two code paths: `core/tools/` (shared core) and `extensions/cli/src/tools/` (CLI-specific wrapping layer).

#### A. LLM mocking / determinism

**Module-level `vi.mock()`.** The dominant pattern:

```ts
// continue/extensions/cli/src/tools/subagent.test.ts:10-24
vi.mock("../subagent/get-agents.js")
vi.mock("../subagent/executor.js")
vi.mock("../services/ServiceContainer.js", () => ({
    serviceContainer: { get: vi.fn() },
}))
```

Then per-test: `vi.mocked(executeSubAgent).mockResolvedValue({ success: true, response: "subagent-output" })`. See https://github.com/continuedev/continue/blob/a1ead04122664b308131bdf1c3345ead00146bbc/extensions/cli/src/tools/subagent.test.ts#L10-L120.

#### B. Tool-layer unit tests

**One `.test.ts` per tool.** Strictly one-to-one. `edit.test.ts`, `fetch.test.ts`, `searchCode.test.ts`, `runTerminalCommand.test.ts`, `subagent.test.ts`, `readFile.ts`, `multiEdit.test.ts`, `skills.test.ts`, `preprocess.test.ts`.

Each tool is tested via its exported tool object: `tool.preprocess!(args)` and `tool.run(args, context)`.

Metadata is not tested separately — the tool-definition registry (`builtInToolNames.ts`) is a static list.

#### C. Multi-step / E2E

No end-to-end scenario tests in the OSS tree — Continue's e2e is in its closed-source platform tests.

#### D. Shell execution — REAL COMMANDS IN CI

Continue's `runTerminalCommand.test.ts` runs actual shell commands branched on `process.platform`:

```ts
// continue/extensions/cli/src/tools/runTerminalCommand.test.ts:11-80
if (isWindows) {
    command = 'Write-Output "hello world"'
} else {
    command = 'echo "hello world"'
}
const result = await runTerminalCommandTool.run({ command })
expect(result.trim()).toBe(expectedOutput)
```

No subprocess stubbing. Tests verify real shell behaviour including `node --version` regex matching, non-existent-command error messages in English (regex allows "not found" OR "not recognized" for cross-platform). See https://github.com/continuedev/continue/blob/a1ead04122664b308131bdf1c3345ead00146bbc/extensions/cli/src/tools/runTerminalCommand.test.ts#L11-L109.

#### E. File ops

`tempfile` + real fs writes. Nothing fancy.

#### F. HTTP

`vi.mock("../../context/providers/URLContextProvider", () => ({ getUrlContextItems: vi.fn() }))` and per-test `mockResolvedValue(...)`. See https://github.com/continuedev/continue/blob/a1ead04122664b308131bdf1c3345ead00146bbc/core/tools/implementations/fetchUrlContent.vitest.ts#L4-L60.

#### G. Sub-agent

`subagent.test.ts` mocks `executeSubAgent` at module boundary and tests the parent-side orchestration (preprocess → run → format output, including `onOutputUpdate` streaming callback).

#### H. Memory

Chat history provider tested in `core/util/` tests.

#### I. Safety

Minimal. Continue has permission prompts at runtime but no golden-corpus test file for dangerous commands.

#### J. Eval

No committed eval harness in the OSS tree.

#### K. Infra

- Two file extensions in use: `.test.ts` and `.vitest.ts` (latter gates the vitest-only runner). Some files have `.integration.vitest.ts` for the slower suite.
- Shared `ToolExtras` mock object passed per test.
- Very little shared fixture infrastructure — tests are independent and terse.

---

### 2.6 Goose (priority 6)

**Stack.** Rust, `cargo test` + `tokio` + `async_trait` + `tempfile`. Tests in `crates/goose/tests/` and `crates/goose-test-support/`.

#### A. LLM mocking / determinism

**`impl Provider for MockFoo` trait implementations.** Goose has a real `Provider` trait (`goose/crates/goose/src/providers/base.rs`); tests implement it directly with custom logic:

```rust
// goose/crates/goose/tests/compaction.rs:21-180
struct MockCompactionProvider {
    has_compacted: Arc<AtomicBool>,
}

#[async_trait]
impl Provider for MockCompactionProvider {
    async fn stream(&self, _cfg, _sid, system_prompt, messages, _tools) -> Result<MessageStream, ProviderError> {
        let is_compaction = messages.iter().any(|m| /* looks for "summarize" */);
        let input_tokens = self.calculate_input_tokens(system_prompt, messages);

        // Simulate real context limit
        if !is_compaction && input_tokens > 20000 && !self.has_compacted.load(Ordering::SeqCst) {
            return Err(ProviderError::ContextLengthExceeded(format!("{} > {}", input_tokens, 20000)))
        }
        if is_compaction { self.has_compacted.store(true, Ordering::SeqCst); }

        let msg = if is_compaction {
            Message::assistant().with_text("<mock summary>")
        } else { /* ... */ };
        Ok(stream_from_single_message(msg, usage))
    }
}
```

Source: https://github.com/block/goose/blob/0028d97c23cb2c850b11b42bd2afa5702c31aa6a/crates/goose/tests/compaction.rs#L21-L180

This is the **highest-fidelity LLM mock of the six frameworks**. It simulates context-length exceeded errors with state, which is what drives the full compaction loop of the real Agent.

#### B. Tool-layer unit tests

`crates/goose/tests/tool_inspection_manager_tests.rs`, `repetition_inspector_tests.rs` (35 lines, tight golden corpus for tool-repetition detection), `adversary_inspector_tests.rs`.

#### C. Multi-step / E2E

`crates/goose/tests/agent.rs` (1091 lines) drives full agent event loops via `Agent::reply(...)` streamed through `futures::StreamExt::next`. Uses real in-process `Agent` + mock provider + mock scheduler.

#### D. Shell execution

`crates/goose/src/agents/execute_commands.rs` (443 lines) has inline `#[cfg(test)] mod tests` — co-located unit tests for command parsing and tokenisation.

#### E. File ops

TempDir (`tempfile::TempDir`) + real fs.

#### F. HTTP

`crates/goose/tests/providers.rs` (919 lines) stubs provider HTTP layers.

#### G. Sub-agent

`goose/src/agents/subagent_handler.rs` + `subagent_execution_tool/` have inline tests. `crates/goose/tests/agent.rs` exercises the full `schedule_tool` delegation end-to-end with a `MockScheduler` implementing the `SchedulerTrait`.

#### H. Memory / state

`crates/goose/tests/compaction.rs` (751 lines) is the reference memory/compaction test file (cited above).

#### I. Safety

`adversary_inspector_tests.rs` and `repetition_inspector_tests.rs` — the latter is the cleanest 35-line example of a safety-layer unit test.

#### J. Eval

No committed eval harness; tests are thorough enough to serve as the regression gate.

#### K. Infra

- **`crates/goose-test-support/`** crate exists but only has `test_assets/` + 8-line `lib.rs`. Most test utilities live inline.
- **MCP replay**: `crates/goose/tests/mcp_replays/` holds recorded MCP server transcripts (one per server: github, npx server-everything, fastmcp, mcp-server-fetch) and a `.results.json` per replay. This is a second form of trajectory replay, but for the MCP tool wire protocol.

---

## 3. Patterns We Should Steal (ranked, opinionated)

**Reminder of what we already have** (from `docs/research/2026-04-07-existing-test-infrastructure.md`): JUnit 5 + MockK + kotlinx-coroutines-test + OkHttp MockWebServer + Turbine; a `SequenceBrain` fake LLM in `AgentLoopTest.kt:106`; JSONL contract tests in `BridgeContractTest.kt`; TempDir for all file tests; `runTest` for all coroutines. No test base classes, no shared fixtures beyond `TestModels.kt`. 38 tools have no direct test. Integration meta-tools have metadata-only tests.

### Recommendation 1 (MUST): Build an `LlmEventBuilder` DSL + scripted sequence runner

**Source:** Codex's `core/tests/common/responses.rs` event constructors + `mount_sse_sequence`, and Cline's `SubagentRunner.test.ts` multi-call `createMessage` pattern.

**Concrete shape for us:**

```kotlin
// new file: agent/src/test/kotlin/com/workflow/orchestrator/agent/testing/LlmEventBuilder.kt
object LlmEvent {
    fun toolCall(id: String, name: String, args: JsonObject): LlmChunk = ...
    fun assistantText(text: String): LlmChunk = ...
    fun usage(inputTokens: Int, outputTokens: Int): LlmChunk = ...
    fun done(): LlmChunk = ...
}

class ScriptedCodyClient(private val turns: List<List<LlmChunk>>) : SourcegraphChatClient {
    private var turn = 0
    override fun streamChat(...): Flow<LlmChunk> = flow { turns[turn++].forEach { emit(it) } }
}
```

This gives us the Cline/Codex benefit (deterministic multi-turn loops driving the *real* `AgentLoop`) while staying idiomatic Kotlin.

**Rationale:** our current `SequenceBrain` only covers the happy path and doesn't model streaming, usage accounting, or tool_use_id correlation. The Cline test at `SubagentRunner.test.ts:163-190` catches the most common ReAct bug (mismatched tool_use_id across turns) — we cannot catch that today.

**Applicability: 10/10.** This is a direct port.

### Recommendation 2 (MUST): Adopt OpenHands-style trajectory replay for full end-to-end coverage

**Source:** `openhands/tests/runtime/test_replay.py` + `openhands/tests/runtime/trajs/*.json`.

**Concrete shape:**

1. Add a `replayTrajectoryPath` option to `AgentService` (or a test-only subclass)
2. Commit JSON trajectory files to `agent/src/test/resources/trajectories/` — each file is `[{role, content, toolCall?, toolResult?}]`
3. A new `TrajectoryReplayTest.kt` loops `AgentLoop` through each trajectory and asserts only on terminal state (session completed, files written, no exceptions)

Start with 5 trajectories capturing the 5 scenarios we already care about: create-file, read-and-edit, run-tests-then-fix, spawn-subagent, plan-then-act.

**Rationale:** this is the only way to get coverage of the *full* loop (LLM + ToolExecutor + SessionStore + hooks + context manager) without per-class stubbing. Our current `AgentLoopTest` covers individual paths; trajectory tests guarantee no regression in the end-to-end path.

**Applicability: 9/10.** Needs a small amount of AgentLoop plumbing.

### Recommendation 3 (MUST): Kill metadata-only tests; replace with handler-execution tests

**Source:** nobody else does metadata-only tests. Cline's `SubagentToolHandler.test.ts` is the positive example.

**Concrete shape:** for every `JiraTool`/`SonarTool`/`BambooBuildsTool`/etc., write a test that:
1. Creates a fake `JiraService` (MockK `mockk<JiraService>().apply { coEvery { listSprints(any()) } returns ToolResult.ok(...) }`)
2. Constructs the `Tool` with the fake service injected (may require refactoring to accept the service via ctor or a test-only factory)
3. Calls `tool.execute(paramsJson, project)` and asserts on the returned `ToolResult`
4. Covers both happy path and `ToolResult.error` path

**Rationale:** the metadata tests we have catch nothing — they fail only if someone renames a string constant. The handler-exec tests catch action-dispatch bugs, parameter validation bugs, and service-call translation bugs, which is where the agent actually breaks.

**Applicability: 10/10.** Mechanical refactor.

### Recommendation 4 (SHOULD): Create a `TestToolContext` factory + `createTaskConfig()` equivalent

**Source:** Cline's `SubagentToolHandler.test.ts:14-104` `createConfig()` and OpenHands's `conftest.py` fixtures.

**Concrete shape:**

```kotlin
// new file: agent/src/test/kotlin/com/workflow/orchestrator/agent/testing/TestToolContext.kt
fun createTestToolContext(
    autoApprove: Boolean = false,
    planMode: Boolean = false,
    nativeToolCall: Boolean = true,
    services: TestServices = TestServices(),
): ToolContext {
    val project = mockk<Project>(relaxed = true)
    every { project.basePath } returns System.getProperty("java.io.tmpdir")
    // ...
    return ToolContext(project, services, ...)
}
```

Every handler test uses `createTestToolContext(autoApprove = true)` with overrides. Saves ~50 lines per test file.

**Applicability: 8/10.** Immediate dev-velocity win once the test count grows.

### Recommendation 5 (SHOULD): Extract pure functions from tools and unit-test them cheaply

**Source:** Cline's `resolveCommandTimeoutSeconds`, `isLikelyLongRunningCommand`, `formatFileContentWithLineNumbers`, `getReadToolDisplayedLineRange`.

**Concrete shape:** go through our `tools/builtin/ExecuteCommandTool.kt`, `tools/builtin/ReadFileTool.kt`, `tools/vcs/Git*Tool.kt` and extract the pure decision functions (timeout policy, line-range clamping, path sanitisation, glob matching) into top-level functions or a companion object. Test them with `@ParameterizedTest` + `@CsvSource`.

**Rationale:** kilo-cheap per test, huge coverage bump for the git/file/exec tools which today have ~0% coverage.

**Applicability: 9/10.**

### Recommendation 6 (SHOULD): Build a `FakeCodyClient` with stateful error simulation for context-management tests

**Source:** Goose's `MockCompactionProvider` at `crates/goose/tests/compaction.rs:21-180`.

**Concrete shape:**

```kotlin
class FakeCodyClient(
    private val contextLimitTokens: Int = 20_000,
) : SourcegraphChatClient {
    private val hasCompacted = AtomicBoolean(false)

    override fun streamChat(messages: List<Message>, ...): Flow<LlmChunk> {
        val isCompaction = messages.lastOrNull()?.contains("summarize") == true
        val inputTokens = estimateTokens(messages)
        if (!isCompaction && inputTokens > contextLimitTokens && !hasCompacted.get()) {
            throw ContextLengthExceededException(inputTokens, contextLimitTokens)
        }
        if (isCompaction) hasCompacted.set(true)
        return scriptedResponse(...)
    }
}
```

Then write a `ContextManagerEndToEndTest` that feeds oversized conversations into the real `AgentLoop` + `ContextManager` and asserts that compaction is triggered, the loop recovers, and the eventual turn succeeds.

**Applicability: 8/10.** Addresses the biggest gap in our current tests — the compaction pipeline is only unit-tested.

### Recommendation 7 (SHOULD): Add a golden-corpus test file for `CommandSafetyAnalyzer`

**Source:** Cline's 990-line `CommandPermissionController.test.ts`.

**Concrete shape:** one `CommandSafetyAnalyzerCorpusTest.kt` file with a `@ParameterizedTest` fed from a resource file `command-safety-corpus.csv` — ~200 command/expected-verdict pairs across 12 categories (rm -rf, curl | sh, sudo, docker, git force-push, piped shell injection, env leaks, etc.).

**Rationale:** our current `CommandSafetyAnalyzerTest` has ~20 cases. The golden corpus is the correct shape for safety work: easy to grow, easy to review as a PR diff, serves as executable documentation.

**Applicability: 9/10.**

### Recommendation 8 (SHOULD): Port Cline's 3-layer eval strategy

**Source:** `cline/evals/README.md`.

**Concrete shape:**
- **Layer 1 (contract unit tests):** already in place.
- **Layer 2 (smoke tests):** add `agent/src/test/resources/scenarios/*.json` with `{name, prompt, expectedFiles, expectedContent, timeout}`. Run via a new `AgentSmokeTest.kt` that boots a real `AgentService` against a real Cody endpoint (gated by `SOURCEGRAPH_ACCESS_TOKEN` env var; `@DisabledIf` otherwise). 5 scenarios to start.
- **Layer 3 (e2e/bench):** eventually, a curated bench of 10-20 workflow-orchestrator-specific tasks ("triage this Jira ticket and propose a fix", "update Bamboo plan N to use the new image"). Defer.

**Applicability: 7/10.** Layer 2 is a weekend project; Layer 3 is months.

### Recommendation 9 (NICE): Stub at `SubagentRunner` boundary for parent-side delegation tests

**Source:** Cline's `SubagentToolHandler.test.ts:202-328` which does `sinon.stub(SubagentRunner.prototype, "run").callsFake(...)`.

**Concrete shape:** our `SpawnAgentToolTest` should MockK-stub `SubagentRunner.run()` and verify the parent orchestration (approval gate, parallel fan-out up to 5, failure aggregation, status streaming). Test the child-side `SubagentRunner` separately with the scripted LLM pattern from Recommendation 1.

**Applicability: 8/10.** Unblocks `SpawnAgentTool` coverage.

### Recommendation 10 (NICE): Add MCP-replay-style fixtures for integration tools

**Source:** Goose's `crates/goose/tests/mcp_replays/` — recorded transcripts per server.

**Concrete shape:** capture live Jira / Bamboo / Sonar API responses into JSON fixture files once, then replay them via `MockWebServer.enqueue()` in tests. We already have some fixtures in `jira/src/test/resources/fixtures/`; make this the convention and expand to Bamboo/Sonar.

**Applicability: 7/10.** Incremental but high value.

---

## 4. Anti-patterns to avoid

1. **Metadata-only tests** (our current `JiraToolTest`, `SonarToolTest`, `BambooBuildsToolTest` pattern). Every OSS framework has moved past this. Zero signal vs maintenance cost.
2. **Re-implementing the logic inline in the test** (Cline's `ToolExecutor.test.ts:5-150` does this for the `addHookContextToConversation` method — the test body is a copy of the implementation, which means a refactor in the impl silently leaves the test green). Don't copy this. Test behaviour through the exported API.
3. **Tests that require real network** without a `@DisabledIf` gate. Codex uses `skip_if_no_network!()` macro; Cline smoke tests require `CLINE_API_KEY` and are excluded from unit runs. Don't silently rely on network.
4. **Monkey-patching private methods** — Cline's `AnthropicHandler` test stubs `ensureClient` as a private-ish method, which breaks on refactor. Prefer public seams (constructor injection, factory functions).
5. **One test class per trivial pure function** — Aider's `test_run_cmd.py` is 11 lines and adds no value beyond the parent test file. If the function is small, put the test co-located or parameterized.
6. **Running actual shell commands in CI without platform branching** — Continue.dev's `runTerminalCommand.test.ts` is OK because it branches on `process.platform`. Don't assume Linux.
7. **Over-invested test harness** (Goose's `goose-test-support` crate is 8 lines of real code despite being a standalone crate). Keep shared utilities in place until a third test needs them.
8. **Huge base classes that stub 30 dependencies** — Cline's `createConfig()` at 90 lines is already near the limit. Beyond that, tests become unreadable. Prefer composing small factories.
9. **Testing by file name instead of by behaviour** — don't replicate the anti-pattern of `ReadFileToolHandler.fileNotFound.test.ts` + `ReadFileToolHandler.lineNumbers.test.ts` + `ReadFileToolHandler.specialPaths.test.ts` if all three could be one file with `@Nested` classes. Cline split them for git-merge reasons; we don't have that problem at our scale.
10. **Capturing huge trajectory JSONs that nobody will ever update.** OpenHands's `trajs/basic.json` is already stale — it uses `claude-3-5-sonnet-20241022`. Set a refresh cadence and consider generating them in CI instead of committing.

---

## 5. Open questions / deeper investigation later

1. **How does Cline test its webview (React) bridge end-to-end?** The `webview-ui/` tests use vitest + React Testing Library, but we couldn't locate a test that drives the JCEF-equivalent message bus in Cline's native host. For our JCEF layer we may need a custom harness.
2. **How does OpenHands handle non-determinism in trajectory replays** when the model ID changes? The `trajs/basic.json` file has `model: claude-3-5-sonnet-20241022` hardcoded. What's the upgrade path?
3. **Does Codex CLI's `wiremock` layer cover streaming back-pressure and mid-stream errors?** `sse_failed(id, code, message)` suggests yes; worth reading `core/tests/suite/compact_remote.rs` and `cli_stream.rs` before building our Kotlin equivalent.
4. **Is there an OSS test for tool-approval UI flows?** Closest is Cline's `handlePartialBlock` tests that assert on `callbacks.ask` vs `callbacks.say`. None of the frameworks test the approval-UI diff preview directly. Our JCEF bridge tests may need to be bespoke.
5. **SWE-bench integration.** OpenHands has `evaluation/benchmarks/swe_bench/` but we didn't spelunk. If we go after Layer 3 eval, start there.
6. **Goose's `MockCompactionProvider` token-estimation heuristic** (6000 + syslen/4) is ad hoc — is there a better way to simulate token pressure without real tokenization?
7. **Should we write our fake Cody client against a subset of the Sourcegraph Cody SSE schema**, or a generic event stream? Decision depends on how much of the wire format we want to pin.
8. **How do the tree-sitter-based tools get tested?** Our PSI tools will face similar challenges. Cline has `src/services/tree-sitter/__tests__/index.test.ts` — worth reading when we tackle the PSI tool gap.
9. **Parallel sub-agent cancellation semantics** — Cline's test asserts `maxActiveRuns > 1` but doesn't test cancellation of an in-flight parallel set. We should add that gap to our own `SpawnAgentToolTest`.
10. **Flakiness budget.** None of the six frameworks publish a flakiness metric in their CI. We should decide upfront what our tolerance is before we start adding layer-2 smoke tests.

---

## Appendix: Notable single-file references

For future quick lookup. All paths are repo-relative; prefix with the commit SHA from the table at the top.

| Pattern | File | Lines |
|---|---|---|
| Cline scripted multi-turn LLM | `cline/src/core/task/tools/subagent/__tests__/SubagentRunner.test.ts` | 149-270 |
| Cline parent-side delegation | `cline/src/core/task/tools/handlers/__tests__/SubagentToolHandler.test.ts` | 202-328 |
| Cline pure-function tool test | `cline/src/core/task/tools/handlers/__tests__/ReadFileToolHandler.lineNumbers.test.ts` | 33-98 |
| Cline timeout policy test | `cline/src/core/task/tools/handlers/__tests__/ExecuteCommandToolHandler.timeout.test.ts` | all (31 lines) |
| Cline command permission corpus | `cline/src/core/permissions/CommandPermissionController.test.ts` | all (990 lines) |
| Cline tool-call parsing contract | `cline/src/core/api/transform/__tests__/tool-parsing.test.ts` | 23-100 |
| Cline context manager truncation | `cline/src/core/context/context-management/__tests__/ContextManager.test.ts` | 1-80 |
| Cline createConfig fixture | `cline/src/core/task/tools/handlers/__tests__/SubagentToolHandler.test.ts` | 14-104 |
| Cline smoke scenario config | `cline/evals/smoke-tests/scenarios/01-create-file/config.json` | all |
| Cline eval framework overview | `cline/evals/README.md` | all |
| Aider mock_send monkey-patch | `aider/tests/basic/test_coder.py` | 462-478 |
| Aider GitTemporaryDirectory | `aider/tests/basic/test_coder.py` | 25-52 |
| Aider litellm patch | `aider/tests/basic/test_sendchat.py` | 22-55 |
| Aider editblock parser tests | `aider/tests/basic/test_editblock.py` | 20-80 |
| Aider benchmark harness | `aider/benchmark/benchmark.py` | all |
| OpenHands trajectory replay | `openhands/tests/runtime/test_replay.py` | 32-80 |
| OpenHands trajectory JSON fixture | `openhands/tests/runtime/trajs/basic.json` | all |
| OpenHands agent delegation | `openhands/tests/unit/controller/test_agent_delegation.py` | 75-180 |
| OpenHands stuck detector | `openhands/tests/unit/controller/test_is_stuck.py` | all (1029 lines) |
| OpenHands LLM patch | `openhands/tests/unit/llm/test_llm.py` | 169-250 |
| OpenHands cmd retry unit | `openhands/tests/unit/runtime/test_cmd_retry.py` | 46-150 |
| Codex event constructors DSL | `codex/codex-rs/core/tests/common/responses.rs` | 566-905 |
| Codex ResponseMock capture | `codex/codex-rs/core/tests/common/responses.rs` | 40-83 |
| Codex TestCodex driver | `codex/codex-rs/core/tests/common/test_codex.rs` | 690-800 |
| Codex compact suite test | `codex/codex-rs/core/tests/suite/compact.rs` | 1-80 |
| Codex execpolicy corpus | `codex/codex-rs/execpolicy/tests/basic.rs` | all (963 lines) |
| Continue subagent test | `continue/extensions/cli/src/tools/subagent.test.ts` | 10-120 |
| Continue real-shell test | `continue/extensions/cli/src/tools/runTerminalCommand.test.ts` | 11-109 |
| Continue vi.mock HTTP | `continue/core/tools/implementations/fetchUrlContent.vitest.ts` | 4-60 |
| Goose MockCompactionProvider | `goose/crates/goose/tests/compaction.rs` | 21-180 |
| Goose repetition inspector | `goose/crates/goose/tests/repetition_inspector_tests.rs` | all (35 lines) |
| Goose agent event loop | `goose/crates/goose/tests/agent.rs` | all (1091 lines) |
| Goose MCP replay fixtures | `goose/crates/goose/tests/mcp_replays/` | directory |
