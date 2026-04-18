# claude-agent-sdk-python — Repository Analysis

**Date:** 2026-04-18
**Source:** https://github.com/anthropics/claude-agent-sdk-python
**Version analysed:** `v0.1.62` (HEAD `75cfe30`, bundles Claude Code CLI `2.1.113`)
**Clone path:** `/tmp/claude-agent-sdk-python` (depth-50 clone)
**Size:** ~6.7 KLOC of Python in `src/` + examples + tests

---

## 1. TL;DR

`claude-agent-sdk-python` is **not an agent framework**. It is a **thin Python client** that wraps the Claude Code CLI binary as a subprocess and exposes a typed, async-iterator API over its stdin/stdout JSON protocol. All reasoning, tool dispatch, context management, planning, compaction, and sub-agent orchestration happen **inside the bundled `claude` CLI** — the Python SDK only:

1. Finds the CLI binary (bundled in the wheel, or falls back to `PATH`),
2. Builds a command line from a `ClaudeAgentOptions` dataclass,
3. Streams JSON messages in/out over stdio (`--input-format stream-json --output-format stream-json`),
4. Runs a **control protocol** on the same channel for hooks, tool-permission callbacks, interrupts, model swap, permission-mode change, MCP server toggles, file rewinds, and task stops,
5. Hosts **in-process MCP servers** so user-defined Python functions can be offered to the model as tools without spinning up a subprocess.

The signature novel contribution is **in-process SDK MCP servers**: Python `@tool`-decorated async functions are registered with `mcp.server.Server`, and tool calls are routed through the control protocol instead of stdio-MCP subprocesses.

This architectural choice is the polar opposite of the Workflow Orchestrator `:agent` module, which re-implements Cline's full agent loop in Kotlin (see §13).

---

## 2. Repository facts

| | |
|---|---|
| Package name | `claude-agent-sdk` (on PyPI) |
| License | MIT |
| Python | 3.10+ (uses `typing_extensions` shim for 3.10 `NotRequired`) |
| Runtime dependencies | `anyio>=4.0.0`, `mcp>=0.1.0`, `typing_extensions` (3.10 only) |
| Optional extra | `otel` → `opentelemetry-api>=1.20.0` (for W3C trace context propagation into the CLI) |
| Release cadence | Weekly; each release typically bumps the bundled CLI version |
| Development status | `Alpha` (per `pyproject.toml` classifier) |
| Build backend | `hatchling` |
| Bundled binary | Platform-specific wheels ship the `claude` CLI inside the package (`_bundled/`) |
| History | Renamed from `claude-code-sdk` at v0.1.0 — breaking rename of `ClaudeCodeOptions` → `ClaudeAgentOptions` |
| Top-level files | `README.md`, `CLAUDE.md`, `CHANGELOG.md`, `Dockerfile.test`, `pyproject.toml`, `RELEASING.md` |

### Source tree

```
src/claude_agent_sdk/
├── __init__.py              (607 L — public API, tool decorator, in-process MCP server factory)
├── _version.py              (pkg version)
├── _cli_version.py          (bundled CLI version, e.g. "2.1.113")
├── _errors.py               (error hierarchy — see §14)
├── types.py                 (1376 L — ALL dataclasses/TypedDicts; wire protocol shapes)
├── query.py                 (126 L — the one-shot query() entrypoint)
├── client.py                (560 L — ClaudeSDKClient interactive class)
├── _bundled/                (platform-specific bundled CLI binary at wheel-build time)
└── _internal/
    ├── client.py            (InternalClient used by query())
    ├── query.py             (765 L — control protocol engine, hook dispatch, MCP routing)
    ├── message_parser.py    (dict → typed Message dataclass)
    ├── sessions.py          (1341 L — list/get session/subagent transcripts from disk)
    ├── session_mutations.py (671 L — rename/tag/fork/delete session)
    └── transport/
        ├── __init__.py      (Transport ABC — abstract interface)
        └── subprocess_cli.py(733 L — spawns the CLI, builds argv, streams stdio)
```

---

## 3. Architectural model (the key insight)

```
┌─────────────────────────────────┐                      ┌──────────────────────────────────────┐
│ User Python app                 │  subprocess stdio    │ Claude Code CLI (Node.js binary)     │
│                                 │  stream-json both    │                                       │
│  ┌─ query() / ClaudeSDKClient ─┐│  ways                │  • Agent loop                         │
│  │ ClaudeAgentOptions dataclass││                      │  • Tool registry (Read, Edit, Bash…)  │
│  │ @tool fn + create_sdk_mcp_  ││ ──── argv ──────────▶│  • Context / compaction               │
│  │   server()                  ││                      │  • Planning / sub-agents              │
│  │ HookMatcher callbacks       ││ ──── stdin JSONL ───▶│  • MCP client (stdio + SSE + HTTP     │
│  │ can_use_tool callback       ││                      │    + SDK servers over control proto) │
│  └─────────────────────────────┘│ ◀─── stdout JSONL ───│  • Session storage on disk            │
│                                 │                      │  • Permission system                  │
│  SubprocessCLITransport         │ ◀─── stderr (opt) ──│                                       │
│  Query (control protocol)       │                      │                                       │
└─────────────────────────────────┘                      └──────────────────────────────────────┘
```

- **Two channels share one pipe.** `stdout` carries both user-visible messages (`user`, `assistant`, `system`, `result`, `stream_event`, task progress/notifications) and **control messages** (`control_request` / `control_response`). `_internal/query.py:Query` demultiplexes them.
- **Control protocol is bidirectional.** The CLI sends control requests to the SDK (e.g. `can_use_tool`, `hook_callback`, `mcp_message`); the SDK sends them to the CLI (`interrupt`, `set_permission_mode`, `set_model`, `mcp_reconnect`, `mcp_toggle`, `rewind_files`, `stop_task`, `initialize`). See §10 for the full list.
- **Everything LLM-adjacent is remote.** No token counting, no compaction, no model routing, no tool registry exists in the Python SDK. `get_context_usage()` is a pass-through to the CLI's `/context` implementation.
- **In-process MCP servers bypass the subprocess pipe.** When a user registers an SDK MCP server (§6), the CLI routes tool calls back to the SDK via the control protocol instead of launching a second subprocess and speaking stdio-MCP to it.

---

## 4. Public API surface

Exported from `claude_agent_sdk/__init__.py` (`__all__` has ~80 names). Two entrypoints:

### 4.1 `query()` — one-shot

```python
async def query(
    *,
    prompt: str | AsyncIterable[dict[str, Any]],
    options: ClaudeAgentOptions | None = None,
    transport: Transport | None = None,
) -> AsyncIterator[Message]
```

- **Unidirectional**: send all prompts, receive all responses.
- **Stateless**: each call is independent (unless `resume=` / `continue_conversation=` is set).
- **Cannot interrupt**, cannot use `can_use_tool` callback (requires streaming mode), **can** use hooks and SDK MCP servers.
- Under the hood: always runs the CLI in streaming mode and wraps the stream into an `AsyncIterator`.

### 4.2 `ClaudeSDKClient` — interactive

Async context manager. Supports the full feature set:

| Method | Does |
|---|---|
| `connect(prompt=None)` | Spawn CLI, run initialize handshake, optionally send first message |
| `query(prompt, session_id="default")` | Send a follow-up user message |
| `receive_messages()` | Iterate all messages indefinitely |
| `receive_response()` | Iterate until a `ResultMessage` (inclusive) then stop |
| `interrupt()` | Send SIGINT-equivalent via control protocol |
| `set_permission_mode(mode)` | Change mode mid-session (`default`/`acceptEdits`/`plan`/`bypassPermissions`/`dontAsk`/`auto`) |
| `set_model(model)` | Swap model mid-session |
| `rewind_files(user_message_id)` | Roll tracked files back to a checkpoint — requires `enable_file_checkpointing=True` and `extra_args={"replay-user-messages": None}` |
| `reconnect_mcp_server(name)` | Retry a failed MCP server connection |
| `toggle_mcp_server(name, enabled)` | Hide or restore a server's tools |
| `stop_task(task_id)` | Stop a long-running Task (sub-agent) |
| `get_mcp_status()` | Live MCP server states and their tool lists |
| `get_context_usage()` | Token breakdown per category (mirrors CLI `/context`) |
| `get_server_info()` | Server initialization result (commands, output style) |
| `disconnect()` | Tear down |

Caveat (from the docstring): a single `ClaudeSDKClient` cannot cross async runtime contexts (e.g. different trio nurseries) because of an internal persistent anyio task group.

### 4.3 Module-level session helpers

Read-only functions over the CLI's on-disk session store:

- `list_sessions(cwd=..., limit=..., offset=...)`
- `get_session_info(session_id)`
- `get_session_messages(session_id)`
- `list_subagents(session_id)` — list sub-agent transcripts spawned by a session
- `get_subagent_messages(session_id, subagent_id)`

And mutations: `rename_session`, `tag_session`, `delete_session` (cascades to subagent dir since 0.1.60), `fork_session` (returns new session id).

---

## 5. `ClaudeAgentOptions` — the configuration domain

A single dataclass with 40+ fields. Most just translate to CLI flags in `subprocess_cli._build_command()`. Grouped:

**Prompt / tools**
- `system_prompt: str | SystemPromptPreset | SystemPromptFile | None`
   - `SystemPromptPreset(type="preset", preset="claude_code", append=..., exclude_dynamic_sections=...)` — the preset pulls the full Claude Code system prompt; `exclude_dynamic_sections=True` strips per-user dynamic sections (cwd, auto-memory, git status) so the prefix is cross-user-cacheable, and re-injects them as the first user message (added in 0.1.57).
   - `SystemPromptFile(type="file", path=...)` — `--system-prompt-file`.
- `tools: list[str] | ToolsPreset | None` — base tool set (`--tools …` or `--tools default`).
- `allowed_tools: list[str]` — auto-approve allowlist.
- `disallowed_tools: list[str]` — hard block.
- `skills: list[str] | "all" | None` — **context filter** (not sandbox); when set, also auto-adds the `Skill` tool to `allowed_tools` and defaults `setting_sources=["user","project"]` (0.1.62).

**Runtime / budgets**
- `model`, `fallback_model`
- `max_turns`, `max_budget_usd`
- `task_budget: TaskBudget` — token budget aware to the model (beta `task-budgets-2026-03-13`).
- `thinking: ThinkingConfig` (`enabled`/`disabled`/`adaptive`, with `budget_tokens` for `enabled`) + legacy `max_thinking_tokens`.
- `effort: "low" | "medium" | "high" | "max"`
- `betas: list["context-1m-2025-08-07" | …]`
- `output_format={"type":"json_schema","schema":{...}}` → `--json-schema`
- `permission_mode`

**Session / continuity**
- `continue_conversation` (`--continue`)
- `resume`, `session_id`, `fork_session`
- `enable_file_checkpointing`

**Integration**
- `mcp_servers: dict[name, McpServerConfig] | str | Path`
   - Each value can be an external config (`stdio`/`sse`/`http`) or an `McpSdkServerConfig` wrapping an in-process server.
- `agents: dict[name, AgentDefinition]` — custom sub-agents, sent via the **initialize** request (not argv).
- `setting_sources: list["user"|"project"|"local"] | None` — **empty list explicitly disables all sources** (0.1.60 fixed a bug where `[]` was silently dropped).
- `plugins: list[SdkPluginConfig]` (local only, currently).
- `sandbox: SandboxSettings` — bash-command isolation (macOS/Linux only); merged into `--settings` JSON before launch.
- `can_use_tool: CanUseTool` — Python callback for tool permission decisions (auto-sets `permission_prompt_tool_name="stdio"`; mutually exclusive with an explicit tool name; requires streaming mode).
- `hooks: dict[HookEvent, list[HookMatcher]]` — see §7.

**Process plumbing**
- `cwd`, `cli_path`, `env`, `extra_args`, `add_dirs`, `max_buffer_size`
- `stderr: Callable[[str], None]` — callback for CLI stderr lines (preferred over deprecated `debug_stderr`).
- `settings: str` (JSON string or path) — merged with `sandbox` if present.
- `user: str`

**Misc**
- `include_partial_messages` — emit `stream_event` chunks as the model streams (0.1.63 consumer `StreamEvent`).

**Shape of `AgentDefinition`** (`dict[str, AgentDefinition]` in `agents=`):
```python
@dataclass
class AgentDefinition:
    description: str
    prompt: str
    tools: list[str] | None                  # per-agent tool allowlist
    disallowedTools: list[str] | None
    model: str | None                        # alias ("sonnet"/"opus"/"haiku"/"inherit") or full id
    skills: list[str] | None
    memory: "user"|"project"|"local" | None
    mcpServers: list[str | dict] | None      # names or inline configs
    initialPrompt: str | None
    maxTurns: int | None
    background: bool | None
    effort: "low"|"medium"|"high"|"max" | int | None
    permissionMode: PermissionMode | None
```
Sent to the CLI inside the `initialize` control request (so it bypasses argv length limits).

---

## 6. In-process MCP servers (the signature feature)

`create_sdk_mcp_server(name, version, tools)` builds a real `mcp.server.Server` instance inside the Python process. Tools are authored with the `@tool` decorator, registered as MCP `Tool` objects with JSON schemas derived from Python type hints (dict-style, `TypedDict`, or explicit JSON Schema — all supported, including `typing.Annotated[type, "description"]` for per-parameter docs).

```python
@tool("greet", "Greet a user", {"name": str})
async def greet_user(args):
    return {"content": [{"type": "text", "text": f"Hello, {args['name']}!"}]}

server = create_sdk_mcp_server(name="my-tools", tools=[greet_user])

options = ClaudeAgentOptions(
    mcp_servers={"tools": server},
    allowed_tools=["mcp__tools__greet"],
)
```

Wire mechanics:

1. The SDK config is passed to the CLI via `--mcp-config '{"mcpServers": {...}}'` with the `instance` field stripped — the CLI only sees `{type: "sdk", name: ...}`.
2. When the CLI wants to call a tool on an SDK server, it sends a `control_request` with `subtype="mcp_message"` carrying either a `ListToolsRequest` or a `CallToolRequest`.
3. The SDK's `Query` class finds the named server in `self.sdk_mcp_servers`, invokes the handler, and returns the `CallToolResult` as a `control_response`.

This is why the Python SDK imports `mcp` as a hard dependency even if you never write a custom tool — the CLI may still route MCP traffic through it for configured SDK servers.

**Large-result annotation (0.1.55):** If an `SdkMcpTool` has `annotations=ToolAnnotations(maxResultSizeChars=N)`, the SDK forwards it via MCP's `_meta: {"anthropic/maxResultSizeChars": N}` to bypass Zod's annotation stripping and unlock the CLI's layer-2 tool-result spill.

Content types accepted back from a handler: `text`, `image`, `resource_link`, `resource` (text-only; binary is dropped with a warning). `"is_error": True` marks the result as an error.

---

## 7. Hooks system

**A hook is a user-written Python function that the CLI calls at specific agent-loop events**, inside the control protocol. Events (`HookEvent` literal):

| Event | Meaning |
|---|---|
| `PreToolUse` | Before any tool executes. Can set `permissionDecision: "allow"\|"deny"\|"ask"` + `permissionDecisionReason`. |
| `PostToolUse` | After a tool's result is available. |
| `PostToolUseFailure` | After a tool errors (was split from `PostToolUse`). |
| `UserPromptSubmit` | User message received. Can inject additional context into the conversation. |
| `Stop` / `SubagentStop` | Main session or sub-agent has finished. |
| `PreCompact` | Before the CLI auto-compacts history. |
| `Notification` | Generic CLI notification (idle, permission needed, etc.). |
| `SubagentStart` | A sub-agent is starting. |
| `PermissionRequest` | A tool permission decision is needed (parallel channel to `can_use_tool`). |

Wire shape: `hooks={"PreToolUse": [HookMatcher(matcher="Bash", hooks=[check_bash_command])], ...}`. The SDK registers callback ids with the CLI during `initialize`; the CLI emits `hook_callback` control requests with the event payload; the SDK's `_convert_hook_output_for_cli` also translates `async_`/`continue_` → `async`/`continue` because Python can't use JS-reserved identifiers directly.

Hook I/O is fully typed (`PreToolUseHookInput`, `PostToolUseHookInput`, `HookJSONOutput`, etc.). Sub-agent-attributable events also carry `agent_id`/`agent_type` to disambiguate interleaved parallel sub-agents on the shared control channel.

---

## 8. Permission / tool-gate model

Three overlapping mechanisms, resolved in this order inside the CLI:

1. **`allowed_tools` / `disallowed_tools`** — static allow/deny lists (CLI flags `--allowedTools`, `--disallowedTools`).
2. **`permission_mode`** — one of `default`, `acceptEdits`, `plan`, `bypassPermissions`, `dontAsk`, `auto`. `plan` forbids write tools; `dontAsk` is a parity alias for "allow all without prompting"; `auto` (added 0.1.57) lets the CLI decide.
3. **`can_use_tool(tool_name, input, context) -> PermissionResult`** — per-call Python callback. `PermissionResultAllow` can return `updated_input` (let the SDK rewrite the tool call) and `updated_permissions` (attach `PermissionUpdate` rules that modify future calls). `PermissionResultDeny` can set `interrupt=True` to abort the session.

`PermissionUpdate` (dataclass) is a structured rule mutation: `addRules`/`replaceRules`/`removeRules`/`setMode`/`addDirectories`/`removeDirectories`, scoped to `userSettings`/`projectSettings`/`localSettings`/`session`.

`ToolPermissionContext` carries `tool_use_id`, `agent_id`, `suggestions: list[PermissionUpdate]` — the last is the CLI's own suggestion to the caller (e.g., "add this to your always-allow list").

---

## 9. Sub-agents — `AgentDefinition`

See §5 for the dataclass. Observations:

- The SDK sends the whole agent dict on `initialize` (not argv), so agent definitions can be arbitrarily large.
- Hooks scoped to a sub-agent fire with `agent_id`/`agent_type` populated — parallel sub-agents are disambiguated by id on the shared channel.
- Sub-agent transcripts are stored on disk separately; `list_subagents()` / `get_subagent_messages()` read them back (0.1.60).
- Deleting a parent session cascades to its sub-agent transcripts (0.1.60, matching TS SDK).

The SDK does **not** implement sub-agent delegation, parallel fan-out, budget distribution, or result reconciliation. All of that lives in the CLI.

---

## 10. Control protocol (wire format)

All control messages are line-delimited JSON on the stdio pipe, distinguished from user-facing messages by `type: "control_request"` / `type: "control_response"`. Declared in `types.py`:

**SDK → CLI requests** (`SDKControlRequest`):
- `interrupt`, `set_permission_mode`, `set_model`, `mcp_reconnect`, `mcp_toggle`, `rewind_files`, `stop_task`, `initialize`.

**CLI → SDK requests** (handled inside `_internal/query.py`):
- `can_use_tool` → resolves to `PermissionResultAllow`/`PermissionResultDeny`.
- `hook_callback` → dispatches to user hook functions by callback id.
- `mcp_message` → routes to an SDK MCP server's `Server.request_handler` (list tools / call tool).

**Responses**: `ControlResponse` (`subtype="success"`, optional `response: dict`) or `ControlErrorResponse` (`subtype="error"`, `error: str`), both keyed by `request_id`.

The `initialize` request is where the SDK announces its hook event ids, agent definitions, skills allowlist, and `exclude_dynamic_sections`. It has a timeout controlled by env var `CLAUDE_CODE_STREAM_CLOSE_TIMEOUT` (ms, min 60 s).

Resilience: 0.1.51 added a **SIGKILL fallback** when `SIGTERM` handlers block on close; 0.1.52 added proper `control_cancel_request` handling so in-flight hook callbacks are cancelled when the CLI gives up on them.

---

## 11. Session management — where things live

Session data lives on disk in the CLI's per-project directory (not inside the Python SDK). `_internal/sessions.py` (1341 L — nearly a quarter of the SDK!) is a large ad-hoc reader of the CLI's JSON transcripts. It exposes structured `SDKSessionInfo` and `SessionMessage` types, supports filtering by cwd, offset-pagination (0.1.51), tags, and lazy loading of full message histories.

`session_mutations.py` handles `rename_session`, `tag_session`, `delete_session` (with cascade to sub-agent dir), and `fork_session` (copy history under a new id).

This is the only place in the SDK where a meaningful chunk of "business logic" exists independent of the CLI.

---

## 12. Transport abstraction

`_internal/transport/__init__.py` defines a small `Transport` ABC (`connect`, `write`, `read_messages`, `close`, `is_ready`, `end_input`). It is marked as an "exposed internal API" for users who want to plug in a **remote Claude Code** (e.g. over SSH, over a network, to a managed service). You can pass `transport=MyTransport()` to `query()` or `ClaudeSDKClient(...)` and it bypasses `SubprocessCLITransport` entirely.

Subprocess transport highlights:
- Finds the CLI via bundled path → `shutil.which("claude")` → common locations (npm-global, `/usr/local/bin`, `~/.local/bin`, etc.).
- Version-checks against `MINIMUM_CLAUDE_CODE_VERSION = "2.0.0"` (can skip with `CLAUDE_AGENT_SDK_SKIP_VERSION_CHECK=1`).
- Always spawns with `--output-format stream-json --verbose --input-format stream-json`, even for one-shot `query()` — streaming is the only mode the SDK speaks.
- Filters `CLAUDECODE` out of inherited env (so spawned sub-processes don't think they're running inside a Claude Code parent — issue #573).
- Sets `CLAUDE_CODE_ENTRYPOINT=sdk-py`, `CLAUDE_AGENT_SDK_VERSION=<ver>`.
- Forwards W3C trace context (`TRACEPARENT`/`TRACESTATE`) when an OpenTelemetry span is active (0.1.60, `[otel]` extra).
- Skips non-JSON lines on stdout to tolerate stray CLI output (0.1.51).

---

## 13. Comparison with the Workflow Orchestrator `:agent` module

The two projects target similar users (a developer building an agent-driven coding assistant) but sit on opposite ends of the "where does the agent live?" axis:

| Dimension | `claude-agent-sdk-python` | Workflow Orchestrator `:agent` (Kotlin) |
|---|---|---|
| **Agent loop** | Delegated to bundled `claude` CLI | `AgentLoop.kt` — own ReAct loop, faithful Cline port |
| **Tool registry** | Not in SDK (CLI owns it) | 3-tier `ToolRegistry` (~30 core + ~50 deferred via `tool_search`) |
| **System prompt** | Preset or plain string passed to CLI | Own 11-section `SystemPrompt` ported from Cline |
| **Context mgmt** | Not in SDK (CLI autocompacts) | Event-sourced 3-stage `ContextManager` under rebuild (`feature/context-management`) |
| **Sub-agents** | `AgentDefinition` dict shipped to CLI | Own `SubagentRunner` + `AgentConfigLoader`, up to 5 parallel |
| **Hooks** | Python callbacks on typed events via control protocol | 8-type `HookManager` with `.agent-hooks.json` in project root |
| **Plan mode** | `permission_mode="plan"` flag | Two-layer enforcement (schema filter + runtime guard), same Cline semantics |
| **Skills** | `skills=` filter + `Skill` tool (CLI executes) | Own `InstructionLoader` + bundled/project/global skills |
| **Persistence** | Reads CLI transcripts off disk | Own atomic two-file Cline-port with streaming crash safety |
| **Permissions** | 3-layer: allowlist + mode + `can_use_tool` callback | Approval gate UI + auto-approve settings |
| **Session storage** | CLI-managed, read-only SDK helpers | Fully owned under `~/.workflow-orchestrator/…` |
| **Transport** | Pluggable `Transport` ABC (subprocess default, remote possible) | Direct HTTP to Sourcegraph Cody enterprise API |
| **Model backend** | Claude via CLI (Anthropic) | Sourcegraph Cody enterprise (150 K input, no tool_choice) |
| **LOC** | ~6.7 K Python | Tens of thousands of Kotlin |

**Upshot for us:** The Python SDK is architecturally uninformative as a blueprint for our `:agent` module — we deliberately re-implement everything the SDK delegates. But several isolated patterns **are** worth studying:

- **Control protocol design.** A single duplex stream multiplexing user messages + JSON-RPC-ish control messages (§10). Maps cleanly onto our JCEF bridge where Kotlin ↔ JS already does something similar (`_loadSessionHistory`, etc.). If we ever decouple the agent from the UI — e.g. expose the `:agent` module to external Python scripts — this is a proven wire format. Note particularly the `async_`/`continue_` keyword-escape shim as a forced lesson on wire compatibility.
- **In-process MCP routing (§6).** If our `:agent` ever accepts external MCP servers, routing tool calls from the agent into Kotlin-native tool implementations via a single pipe (instead of spinning up stdio subprocesses) is the same pattern.
- **`ClaudeAgentOptions` ergonomics (§5).** A single flat dataclass with sensible defaults and a clear separation between model-side (`model`, `thinking`, `effort`) and harness-side (`cwd`, `hooks`, `mcp_servers`) config is a good template for our Kotlin `AgentOptions`-equivalent. Compare to our agent config, which is spread across `PluginSettings`, `AgentConfigLoader` YAML, and project-local `.agent-*.json` files.
- **`TaskBudget` / `max_budget_usd` / `max_turns`** as first-class knobs (§5). We have `subagent` context budgets but no top-level budget type on `AgentLoop`.
- **`exclude_dynamic_sections`** (cross-user cacheability of preset prompt prefix by lifting cwd/memory/git-status to the first user message). Directly applicable to our Sourcegraph caching story if cross-user caching becomes available.
- **`enable_file_checkpointing` + `rewind_files(user_message_id)`.** Structurally similar to our checkpoint system (`agent/sessions/{sessionId}/checkpoints/`). The "point to a `UserMessage.uuid` and the CLI restores all files edited since" UX is a nicer user-facing API than a raw checkpoint id.
- **Session / sub-agent transcript read API** (`list_sessions`, `list_subagents`, `get_subagent_messages`) — cleanly split from mutations and from the active client, so external tooling can inspect without owning a live session. We have part of this inside the webview's `HistoryView` but no stable headless API.
- **Stream-JSON as the canonical wire format** — both stdin and stdout. Our `:agent` ↔ webview bridge uses JCEF queries for commands and a different path for streaming assistant text; unifying on one line-delimited JSON stream is a cheaper mental model.
- **The `Transport` ABC** (§12) is a textbook example of making subprocess vs network vs remote pluggable behind one interface. If we ever want `cross-IDE agent communication` (see memory on UDS IPC), this is what the client side should look like.

Things **not** to copy:
- Delegating the agent loop to a subprocess CLI — we'd lose every IntelliJ-specific integration (PSI, VFS, ExternalAnnotator, gutter icons, approval UI, `runBackgroundableTask`).
- Speaking to Anthropic directly — we're locked to Sourcegraph Cody per enterprise constraint.
- The `_internal/sessions.py` approach (ad-hoc transcript reader) — our persistence is first-class under `MessageStateHandler`, which is the correct layering.

---

## 14. Miscellany

**Error hierarchy** (`_errors.py`): `ClaudeSDKError` (base) > `CLIConnectionError`, `CLINotFoundError`, `ProcessError` (with `exit_code`), `CLIJSONDecodeError` (with `line`, `original_error`). All user-catchable.

**Examples** (`examples/`, ~16 scripts): `quick_start`, `streaming_mode` (primary guide), `streaming_mode_ipython`, `streaming_mode_trio` (shows trio compat), `mcp_calculator` (SDK MCP), `hooks` (all 10 events), `agents` (custom agents), `filesystem_agents`, `tool_permission_callback`, `max_budget_usd`, `tools_option`, `system_prompt`, `setting_sources`, `include_partial_messages`, `stderr_callback_example`, `plugin_example`, `plugins/`.

**Tests:** 18 unit test files under `tests/` (mocked transport, message parser, sessions, wheel-build). 11 end-to-end tests under `e2e-tests/` that actually spawn the CLI. Strict `mypy` (all `disallow_*_defs`, `warn_unreachable`), `ruff` with `E,W,F,I,N,UP,B,C4,PTH,SIM` selection, line-length 88.

**Release process** (from `README.md` + `RELEASING.md`): GitHub Actions `publish.yml` builds platform-specific wheels, bundles a specific CLI version in each, publishes to PyPI, opens a release PR with updated `pyproject.toml`, `_version.py`, `_cli_version.py`, and auto-generated `CHANGELOG.md` entry.

**Cadence signal:** CHANGELOG shows ~weekly releases; most are CLI-version bumps. Notable recent feature releases: 0.1.62 top-level `skills`, 0.1.60 subagent transcript helpers + OTEL + cascade delete, 0.1.57 `exclude_dynamic_sections` + `auto` permission mode, 0.1.52 `get_context_usage()` + `Annotated` descriptions + `tool_use_id`/`agent_id` on permission context, 0.1.51 session mutations + task budget + `SystemPromptFile`.

---

## 15. One-line conclusion

`claude-agent-sdk-python` is **a remote-control dataclass for the Claude Code CLI with a clever in-process MCP shim** — its useful ideas for us are (a) the control-protocol wire design, (b) the ergonomic flat `Options` dataclass, and (c) the pluggable `Transport` ABC; its architectural model is deliberately the inverse of our `:agent` module and should not be imitated at the loop level.
