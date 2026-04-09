#!/usr/bin/env python3
"""
Sourcegraph Streaming + Tool-Parsing Lab
=========================================
Standalone investigation for the IntelliJ plugin agent's streaming reliability.

WHAT IT DOES
------------
Runs a matrix of [SCENARIOS x MODES] against /.api/llm/chat/completions on
your Sourcegraph instance and reports per-test PASS/FAIL with reasons.

Three tool-invocation strategies are compared:

  Mode A — NATIVE function calling   (`tools=[...]`, parses delta.tool_calls)
  Mode B — XML tool tags             (Cline-style: tools described in prompt)
  Mode C — JSON-in-fenced-block      (single ```json block in content)

For each (scenario, mode) the lab:
  - issues a streaming POST
  - records every SSE line with relative timestamps
  - reconstructs the response using the same parser the plugin would use
  - evaluates a list of assertions (PASS/FAIL with a human-readable reason)
  - dumps the raw SSE to a per-test file under raw_sse/

It also runs SIDE PROBES for unanswered gateway questions:
  - Does `stream_options.include_usage=true` actually emit a usage chunk?
  - Does `tool_choice` get accepted (auto / none / forced function)?
  - Does Anthropic `cache_control: ephemeral` pass through?
  - Are usage fields ever missing from the final chunk?
  - Does the gateway expose cache_read / cache_creation token counts?
  - Is there a real cap on `max_tokens` (8K / 16K / 64K)?
  - Does `response_format=json_object` work?
  - Is streaming reliable across 5 repeated runs? (streaming_reliability)
  - Is streaming TTFB low enough to prevent read timeouts? (streaming_vs_nonstreaming)
  - Does the Anthropic content-block format work for tool results? (Cline format)
  - At what conversation size does context overflow begin? (conversation_growth)
  - Are Cline's sanitizeMessages patterns accepted by the gateway? (cline_message_format)
  - Is include_usage reliably present in streaming? (include_usage_reliability)
  - Do parallel tool call deltas assemble correctly at 2/3/4/5 count? (parallel_index_assembly)

OUTPUT
------
  - Pretty per-test report on stdout (PASS/FAIL counts, why each failed)
  - Final assertion matrix for quick eyeballing
  - streaming_lab_results.json    structured results (modes, probes, asserts)
  - raw_sse/<scenario>_<mode>.txt one file per test run, with timestamps

REPORTING BACK
--------------
After running, the easiest way to share results is:
  1. Paste the SUMMARY MATRIX section (it's small).
  2. Attach streaming_lab_results.json.
  3. For any FAIL, attach raw_sse/<scenario>_<mode>.txt — that file has the
     exact bytes the gateway sent, with millisecond timestamps.

USAGE
-----
    pip install requests
    python3 streaming_lab.py --url https://sourcegraph.example.com --token sgp_xxx

Useful flags:
    --model anthropic::2024-10-22::claude-sonnet-4-20250514
    --only A                    only run native function calling
    --scenario parallel_2       only run one scenario (use --list to see names)
    --list                      print all scenario names and exit
    --raw                       also dump every SSE line live to stdout
    --no-probes                 skip side probes
    --no-verify                 disable TLS verify (self-signed)
    --out streaming_lab_results.json
    --raw-dir raw_sse           directory for per-test SSE dumps
"""

from __future__ import annotations

import argparse
import io
import json
import os
import re
import sys
import time
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Any, Callable, Iterator

# Force UTF-8 output on Windows (charmap codec can't handle Unicode in SSE responses)
if sys.stdout.encoding and sys.stdout.encoding.lower() not in ("utf-8", "utf8"):
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
if sys.stderr.encoding and sys.stderr.encoding.lower() not in ("utf-8", "utf8"):
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

try:
    import requests
except ImportError:
    print("ERROR: 'requests' not installed. Run: pip install requests")
    sys.exit(1)


CHAT_PATH = "/.api/llm/chat/completions"
MODELS_PATH = "/.api/llm/models"


def _mask_url(url: str) -> str:
    """Mask the hostname of a URL for safe printing/logging.
    https://internal.corp.example.com/foo  ->  https://***.****.com/foo
    Keeps the scheme and path so context is clear without leaking the hostname.
    """
    if not url:
        return "<no-url>"
    try:
        from urllib.parse import urlparse, urlunparse
        parsed = urlparse(url)
        host = parsed.hostname or ""
        parts = host.split(".")
        # Mask all parts except the TLD — keep .com/.net/.io visible for context
        masked_parts = ["***"] * max(len(parts) - 1, 1) + ([parts[-1]] if len(parts) > 1 else [])
        masked_host = ".".join(masked_parts)
        if parsed.port:
            masked_host = f"{masked_host}:{parsed.port}"
        masked = urlunparse((parsed.scheme, masked_host, parsed.path,
                             parsed.params, "", ""))
        return masked
    except Exception:
        return "***"


# ─────────────────────────────────────────────────────────────
# Tool definitions used by Mode A (native function calling).
# Same tools are described in prose for Modes B and C.
# ─────────────────────────────────────────────────────────────

NATIVE_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "Read the contents of a file at the given project-relative path.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {
                        "type": "string",
                        "description": "Project-relative file path",
                    }
                },
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "search_code",
            "description": "Search the workspace for a regex pattern.",
            "parameters": {
                "type": "object",
                "properties": {
                    "pattern": {
                        "type": "string",
                        "description": "Regex to search for",
                    },
                    "include": {
                        "type": "string",
                        "description": "Optional glob filter",
                    },
                },
                "required": ["pattern"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_directory",
            "description": "List files in a directory.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Directory path"},
                },
                "required": ["path"],
            },
        },
    },
]

XML_TOOL_INSTRUCTIONS = """\
You have access to the following tools. Invoke a tool by emitting an XML block
in your response. You can emit multiple tool blocks in one response to call
tools in parallel. The runtime parses these blocks out of your message.

<tool>
  <name>read_file</name>
  <args>
    <path>PROJECT_RELATIVE_PATH</path>
  </args>
</tool>

<tool>
  <name>search_code</name>
  <args>
    <pattern>REGEX</pattern>
    <include>OPTIONAL_GLOB</include>
  </args>
</tool>

<tool>
  <name>list_directory</name>
  <args>
    <path>DIRECTORY</path>
  </args>
</tool>

Rules:
- Always close every tag.
- Put each tool block on its own lines.
- After the tool blocks, briefly explain why in plain text (one short paragraph).
"""

JSON_TOOL_INSTRUCTIONS = """\
You have access to tools `read_file(path)`, `search_code(pattern, include?)`,
and `list_directory(path)`. Invoke them by emitting a SINGLE fenced JSON block:

```json
{
  "tool_calls": [
    {"name": "read_file", "args": {"path": "..."}},
    {"name": "search_code", "args": {"pattern": "...", "include": "..."}}
  ]
}
```

After the JSON block, briefly explain why in plain text.
"""


# ─────────────────────────────────────────────────────────────
# Scenarios — what we ask the LLM to do
# ─────────────────────────────────────────────────────────────

@dataclass
class Scenario:
    name: str
    description: str
    user_prompt: str

    # Assertion targets
    min_tools: int = 0
    max_tools: int = 99
    expected_tool_names: set[str] = field(default_factory=set)
    must_include_arg_keys: dict[str, set[str]] = field(default_factory=dict)  # tool_name -> required arg keys
    require_text_content: bool = False
    forbid_text_content: bool = False
    finish_reason_in: set[str] | None = None  # if set, finish_reason must be one of these

    # Output size override — None uses the default 2048.  Large code scenarios need 8K+.
    max_output_tokens: int | None = None


SCENARIOS: list[Scenario] = [
    Scenario(
        name="single_tool",
        description="single tool call — baseline; should always work",
        user_prompt=(
            "Use `read_file` to read `src/main/kotlin/Foo.kt`. Just call the tool. "
            "Don't say anything else."
        ),
        min_tools=1, max_tools=1,
        expected_tool_names={"read_file"},
        must_include_arg_keys={"read_file": {"path"}},
        finish_reason_in={"tool_calls", "stop"},
    ),
    Scenario(
        name="parallel_2",
        description="two PARALLEL tool calls — known bug repro",
        user_prompt=(
            "I need you to plan an investigation. In ONE response, call BOTH "
            "`read_file` for `src/main/kotlin/Foo.kt` AND `search_code` for the "
            "pattern `class Foo`. Issue both calls in the SAME response — do not "
            "wait for results. Then briefly say what you would look for."
        ),
        min_tools=2, max_tools=2,
        expected_tool_names={"read_file", "search_code"},
        must_include_arg_keys={"read_file": {"path"}, "search_code": {"pattern"}},
        finish_reason_in={"tool_calls", "stop"},
    ),
    Scenario(
        name="parallel_3",
        description="three parallel tool calls — stress test for index assembly",
        user_prompt=(
            "In ONE response, call ALL THREE of these in parallel:\n"
            "  1. `read_file` for `src/main/kotlin/A.kt`\n"
            "  2. `read_file` for `src/main/kotlin/B.kt`\n"
            "  3. `list_directory` for `src/main/kotlin`\n"
            "Issue all three in the SAME response. No commentary."
        ),
        min_tools=3, max_tools=3,
        expected_tool_names={"read_file", "list_directory"},
        finish_reason_in={"tool_calls", "stop"},
    ),
    Scenario(
        name="text_then_tool",
        description="explanatory text BEFORE tool call (interleaving test)",
        user_prompt=(
            "First, in 1-2 sentences, explain what you're about to do. Then call "
            "`read_file` for `README.md`. Both in the same response."
        ),
        min_tools=1, max_tools=1,
        expected_tool_names={"read_file"},
        require_text_content=True,
    ),
    Scenario(
        name="tool_then_text",
        description="tool call BEFORE explanatory text (XML hardest case)",
        user_prompt=(
            "Call `read_file` for `README.md` first. After the call, in 1-2 "
            "sentences explain what you'd look for in that file."
        ),
        min_tools=1, max_tools=1,
        expected_tool_names={"read_file"},
        require_text_content=True,
    ),
    Scenario(
        name="special_chars_args",
        description="tool args containing quotes/backslashes — JSON escaping torture",
        user_prompt=(
            "Call `search_code` with the EXACT regex pattern: "
            r'`hello\s+"world"` and no include filter. Just the tool, nothing else.'
        ),
        min_tools=1, max_tools=1,
        expected_tool_names={"search_code"},
        must_include_arg_keys={"search_code": {"pattern"}},
    ),
    Scenario(
        name="multiline_arg",
        description="tool arg containing a newline — embedded newline torture",
        user_prompt=(
            "Call `search_code` with this multi-line regex pattern (preserve the "
            "newline EXACTLY as part of the pattern):\n"
            "first line\\nsecond line\n"
            "Use no include filter. Just the tool, no other text."
        ),
        min_tools=1, max_tools=1,
        expected_tool_names={"search_code"},
    ),
    Scenario(
        name="text_only",
        description="no tool — should produce content only and stop with stop",
        user_prompt=(
            "What is 2+2? Reply with one short sentence. Do NOT call any tools."
        ),
        min_tools=0, max_tools=0,
        require_text_content=True,
        finish_reason_in={"stop", "end_turn", "length"},
    ),
    Scenario(
        name="large_arg",
        description="tool arg with a long value — tests streaming delta assembly at size",
        user_prompt=(
            "Call `search_code` with this pattern (copy it exactly, every character): "
            "`^(public|private|protected|internal)\\s+(suspend\\s+)?(fun|class|object|interface)"
            "\\s+[A-Z][a-zA-Z0-9_]*(<[^>]+>)?\\s*(\\([^)]*\\))?\\s*(:\\s*[A-Z][a-zA-Z0-9_<>, ?*]+)?\\s*\\{`"
            " and no include filter. Just the tool call."
        ),
        min_tools=1, max_tools=1,
        expected_tool_names={"search_code"},
        must_include_arg_keys={"search_code": {"pattern"}},
    ),
    Scenario(
        name="tool_then_parallel",
        description="text reasoning, then parallel tool calls — interleaving + parallel combined",
        user_prompt=(
            "In 1 sentence explain your plan. Then in the SAME response call BOTH "
            "`read_file` for `src/A.kt` AND `list_directory` for `src/` in parallel."
        ),
        min_tools=2, max_tools=2,
        expected_tool_names={"read_file", "list_directory"},
        require_text_content=True,
        finish_reason_in={"tool_calls", "stop"},
    ),

    # ── Streaming stability scenarios — tiered by output size ────────────────
    # The agent regularly generates 300-600 line implementations. Each tier
    # measures whether the stream stays alive and delivers complete output.
    Scenario(
        name="streaming_medium_text",
        description="~500 word prose — baseline long-text streaming stability",
        user_prompt=(
            "Write a detailed technical essay (at least 500 words, no bullet points) "
            "explaining how the Kotlin compiler transforms suspend functions into state "
            "machines, how continuation-passing style works, how coroutine dispatchers "
            "schedule resumptions, and how structured concurrency prevents leaks. "
            "Do NOT call any tools. Write in full paragraphs only."
        ),
        min_tools=0, max_tools=0,
        require_text_content=True,
        finish_reason_in={"stop", "end_turn", "length"},
        max_output_tokens=2048,
    ),
    Scenario(
        name="streaming_code_100_lines",
        description="~100-line Kotlin code block — small code generation test",
        user_prompt=(
            "Write a complete, runnable Kotlin implementation of a generic LRU cache "
            "using LinkedHashMap. Include: the LRUCache class with get/put/remove/clear, "
            "a companion factory, a thread-safe wrapper using ReentrantReadWriteLock, "
            "and a main() with 5 usage examples. Output ONLY a single ```kotlin ... ``` "
            "fenced block. No explanation before or after. Aim for ~100 lines. Do NOT call tools."
        ),
        min_tools=0, max_tools=0,
        require_text_content=True,
        finish_reason_in={"stop", "end_turn", "length"},
        max_output_tokens=2048,
    ),
    Scenario(
        name="streaming_code_300_lines",
        description="~300-line Kotlin code block — medium code generation (agent typical output)",
        user_prompt=(
            "Write a complete, production-quality Kotlin implementation of an HTTP retry "
            "client using OkHttp3 with ALL of the following:\n"
            "1. RetryInterceptor class with configurable max retries, backoff strategy "
            "(fixed, linear, exponential, jitter), retry-on-status codes list\n"
            "2. BackoffStrategy sealed class with 4 implementations\n"
            "3. RetryConfig data class with builder DSL\n"
            "4. Circuit breaker state machine (CLOSED/OPEN/HALF_OPEN) integrated into the interceptor\n"
            "5. Metrics interface with default NoOpMetrics and LoggingMetrics implementations\n"
            "6. Full KDoc comments on all public API\n"
            "7. A test helper factory function\n"
            "Output ONLY a single ```kotlin ... ``` fenced block. "
            "Aim for 280-320 lines. Do NOT call any tools."
        ),
        min_tools=0, max_tools=0,
        require_text_content=True,
        finish_reason_in={"stop", "end_turn", "length"},
        max_output_tokens=6144,
    ),
    Scenario(
        name="streaming_code_500_lines",
        description="~500-line Kotlin code block — large code gen (agent complex feature output)",
        user_prompt=(
            "Write a complete, production-quality Kotlin implementation of a generic "
            "event sourcing framework with ALL of the following:\n"
            "1. Event<T> sealed class hierarchy with DomainEvent, CommandEvent, IntegrationEvent\n"
            "2. AggregateRoot<ID, S> abstract class with apply(event), rehydrate(events), "
            "version tracking, and pending events list\n"
            "3. EventStore interface + InMemoryEventStore implementation with optimistic "
            "locking (version check on append)\n"
            "4. EventBus interface + SynchronousEventBus + AsyncEventBus (using "
            "CoroutineScope + Channel)\n"
            "5. EventHandler<T> functional interface + EventHandlerRegistry\n"
            "6. Saga<S> abstract class with state machine transitions and compensation actions\n"
            "7. Snapshot<S> support: SnapshotStore interface + InMemorySnapshotStore, "
            "aggregate takes snapshot every N events\n"
            "8. ProjectionManager that replays events to rebuild read models\n"
            "9. Full KDoc on all public API\n"
            "10. A concrete example: OrderAggregate with PlaceOrder/ShipOrder/CancelOrder events\n"
            "Output ONLY a single ```kotlin ... ``` fenced block. "
            "Aim for 480-520 lines. Do NOT call any tools."
        ),
        min_tools=0, max_tools=0,
        require_text_content=True,
        finish_reason_in={"stop", "end_turn", "length"},
        max_output_tokens=12288,
    ),

    # ── Agent think-before-act pattern ───────────────────────────────────────
    Scenario(
        name="think_then_tool",
        description="reasoning text THEN tool call — agent think-before-act (Cline pattern)",
        user_prompt=(
            "Think step-by-step: in 2-3 sentences reason about which file you need to read "
            "to understand the entry point of a Kotlin Spring Boot application. "
            "Then call `read_file` for `src/main/kotlin/Application.kt`. "
            "Reasoning text first, then the tool call in the same response."
        ),
        min_tools=1, max_tools=1,
        expected_tool_names={"read_file"},
        must_include_arg_keys={"read_file": {"path"}},
        require_text_content=True,
        finish_reason_in={"tool_calls", "stop"},
    ),

    # ── Parallel scale scenarios ──────────────────────────────────────────────
    Scenario(
        name="parallel_5",
        description="five parallel tool calls — max concurrent for agent research scope",
        user_prompt=(
            "In ONE response, call ALL FIVE in parallel (no waiting between them):\n"
            "1. read_file for src/main/kotlin/A.kt\n"
            "2. read_file for src/main/kotlin/B.kt\n"
            "3. read_file for src/main/kotlin/C.kt\n"
            "4. search_code for pattern `@SpringBootApplication`\n"
            "5. list_directory for src/main/kotlin\n"
            "All five in the SAME response. No commentary whatsoever."
        ),
        min_tools=5, max_tools=5,
        expected_tool_names={"read_file", "search_code", "list_directory"},
        finish_reason_in={"tool_calls", "stop"},
    ),
    Scenario(
        name="parallel_mixed_types",
        description="parallel calls across all three tool types — index assembly stress test",
        user_prompt=(
            "In ONE response call all three tools simultaneously:\n"
            "1. search_code with pattern `TODO` and include filter `*.kt`\n"
            "2. read_file for build.gradle.kts\n"
            "3. list_directory for src/test/kotlin\n"
            "All three in the SAME response. Just the tools, no text."
        ),
        min_tools=3, max_tools=3,
        expected_tool_names={"search_code", "read_file", "list_directory"},
        must_include_arg_keys={"search_code": {"pattern"}, "read_file": {"path"}},
        finish_reason_in={"tool_calls", "stop"},
    ),

    # ── Cline-specific patterns ───────────────────────────────────────────────
    Scenario(
        name="only_tool_no_text",
        description="pure tool call, zero text — forbid_text_content path (Cline tool-only turns)",
        user_prompt=(
            "Call `search_code` with pattern `fun main`. "
            "ONLY the tool call. Zero text. No preamble. No explanation. No trailing text."
        ),
        min_tools=1, max_tools=1,
        expected_tool_names={"search_code"},
        must_include_arg_keys={"search_code": {"pattern"}},
        forbid_text_content=True,
        finish_reason_in={"tool_calls", "stop"},
    ),
    Scenario(
        name="tool_with_xml_content_in_arg",
        description="tool arg contains XML-like text — XML parser collision test for Mode B",
        user_prompt=(
            r"Call `search_code` with this EXACT pattern (copy verbatim): "
            r"`<dependency>\s*<groupId>org\.springframework</groupId>` "
            r"and include filter `*.xml`. Just the tool call."
        ),
        min_tools=1, max_tools=1,
        expected_tool_names={"search_code"},
        must_include_arg_keys={"search_code": {"pattern", "include"}},
    ),

    # ── Agent loop simulation scenarios ──────────────────────────────────────
    Scenario(
        name="sequential_investigation",
        description="sequential dependency: read then search based on content (plan before act)",
        user_prompt=(
            "You are investigating a Kotlin project. "
            "First read `build.gradle.kts` to understand the project. "
            "Then ALSO (in the same response) search for `@RestController` to find "
            "web endpoints. Issue both tool calls in ONE response — the search doesn't "
            "depend on the file read result."
        ),
        min_tools=2, max_tools=2,
        expected_tool_names={"read_file", "search_code"},
        must_include_arg_keys={"read_file": {"path"}, "search_code": {"pattern"}},
        finish_reason_in={"tool_calls", "stop"},
    ),
    Scenario(
        name="attempt_completion_pattern",
        description="text-only final answer — simulates agent attempt_completion turn",
        user_prompt=(
            "You have already read all the files. Now write a final summary (2-3 sentences) "
            "of what you found. Do NOT call any tools — this is your final answer. "
            "Start with: 'Based on my investigation, '"
        ),
        min_tools=0, max_tools=0,
        require_text_content=True,
        finish_reason_in={"stop", "end_turn", "length"},
    ),
    Scenario(
        name="unicode_in_arg",
        description="tool arg with Unicode chars — tests UTF-8 encoding through streaming",
        user_prompt=(
            "Call `search_code` with the EXACT pattern `// \u4e2d\u6587\u6ce8\u91ca|// \u65e5\u672c\u8a9e` "
            "(Chinese/Japanese comment pattern). No include filter. Just the tool."
        ),
        min_tools=1, max_tools=1,
        expected_tool_names={"search_code"},
        must_include_arg_keys={"search_code": {"pattern"}},
    ),
    Scenario(
        name="max_tokens_truncation",
        description="response truncated by max_tokens — finish_reason=length recovery test",
        user_prompt=(
            "List every Kotlin keyword alphabetically with a one-sentence definition each. "
            "Do NOT call any tools. Write as many as you can."
        ),
        # NOTE: run_scenario uses max_tokens=512 by default; override via Scenario.max_tokens
        # This scenario will hit finish_reason=length — testing the agent's truncation detection
        min_tools=0, max_tools=0,
        require_text_content=True,
        finish_reason_in={"stop", "end_turn", "length"},
    ),
]


# ─────────────────────────────────────────────────────────────
# Result data classes
# ─────────────────────────────────────────────────────────────

@dataclass
class ParsedToolCall:
    name: str
    args: dict
    raw: str = ""


@dataclass
class Assertion:
    name: str
    passed: bool
    reason: str = ""


@dataclass
class RunOutcome:
    scenario: str
    mode: str
    description: str          # mode description (e.g. "native function calling")
    scenario_description: str = ""   # human-readable scenario purpose
    request_body_size: int = 0

    http_status: int = 0
    transport_error: str | None = None

    time_to_first_byte_ms: float = 0.0
    total_time_ms: float = 0.0
    sse_chunks: int = 0

    finish_reason: str | None = None
    final_content: str = ""
    tool_calls: list[ParsedToolCall] = field(default_factory=list)

    parse_warnings: list[str] = field(default_factory=list)
    saw_usage_chunk: bool = False
    usage: dict | None = None
    cache_read_tokens: int | None = None
    cache_creation_tokens: int | None = None

    # Verbose streaming metrics — populated when --verbose-streaming is set
    chunk_timestamps_ms: list[float] = field(default_factory=list)   # ms since request start
    chunk_sizes_chars: list[int] = field(default_factory=list)        # content chars per chunk
    inter_chunk_gaps_ms: list[float] = field(default_factory=list)    # gaps between chunks
    max_inter_chunk_gap_ms: float = 0.0                               # largest gap (timeout risk)
    p95_inter_chunk_gap_ms: float = 0.0                               # 95th percentile gap
    content_length_chars: int = 0                                     # total content chars
    throughput_chars_per_sec: float = 0.0                             # generation throughput
    saw_done_sentinel: bool = False                                    # did [DONE] arrive?

    raw_sse_path: str = ""
    assertions: list[Assertion] = field(default_factory=list)

    @property
    def passed_count(self) -> int:
        return sum(1 for a in self.assertions if a.passed)

    @property
    def failed_count(self) -> int:
        return sum(1 for a in self.assertions if not a.passed)

    @property
    def all_pass(self) -> bool:
        return self.failed_count == 0 and self.transport_error is None

    def to_dict(self) -> dict:
        d = asdict(self)
        d["tool_calls"] = [asdict(tc) for tc in self.tool_calls]
        d["assertions"] = [asdict(a) for a in self.assertions]
        return d


# ─────────────────────────────────────────────────────────────
# HTTP plumbing
# ─────────────────────────────────────────────────────────────

class SourcegraphClient:
    def __init__(self, base_url: str, token: str, verify: bool = True, timeout: int = 240):
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        self.session.headers.update({
            "Authorization": f"token {token}",
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        })
        self.session.verify = verify
        self.timeout = timeout

    def list_models(self) -> list[str]:
        r = self.session.get(self.base_url + MODELS_PATH, timeout=30)
        r.raise_for_status()
        return [m["id"] for m in r.json().get("data", [])]

    def list_models_full(self) -> tuple[int, dict, dict]:
        """Return (status, full_json, response_headers) so we can field-walk."""
        r = self.session.get(self.base_url + MODELS_PATH, timeout=30)
        try:
            body = r.json()
        except Exception:
            body = {"raw": r.text[:2000]}
        return r.status_code, body, dict(r.headers)

    def get_model(self, model_id: str) -> tuple[int, Any, dict]:
        """GET /.api/llm/models/{id} — single-model retrieve endpoint."""
        url = f"{self.base_url}{MODELS_PATH}/{model_id}"
        r = self.session.get(url, timeout=30)
        try:
            body = r.json()
        except Exception:
            body = {"raw": r.text[:2000]}
        return r.status_code, body, dict(r.headers)

    def post_chat(self, body: dict, stream: bool) -> requests.Response:
        return self.session.post(self.base_url + CHAT_PATH, json=body, stream=stream, timeout=self.timeout)


# ─────────────────────────────────────────────────────────────
# Generic JSON walkers — for finding undocumented response fields
# ─────────────────────────────────────────────────────────────

def walk_keys(obj: Any, path: str = "") -> list[tuple[str, str]]:
    """Recursively walk a JSON object and return [(dotted_path, type_name)] tuples."""
    out: list[tuple[str, str]] = []
    if isinstance(obj, dict):
        for k, v in obj.items():
            child_path = f"{path}.{k}" if path else k
            out.append((child_path, type(v).__name__))
            out.extend(walk_keys(v, child_path))
    elif isinstance(obj, list):
        if obj:
            out.extend(walk_keys(obj[0], f"{path}[]"))
    return out


def find_thinking_fields(obj: Any, path: str = "") -> list[tuple[str, Any]]:
    """Recursively find any field whose key suggests reasoning/thinking content."""
    triggers = ("thinking", "reasoning", "thought", "reflect", "deliberat",
                "scratchpad", "chain_of_thought", "cot")
    out: list[tuple[str, Any]] = []
    if isinstance(obj, dict):
        for k, v in obj.items():
            child_path = f"{path}.{k}" if path else k
            kl = k.lower()
            if any(t in kl for t in triggers):
                preview = json.dumps(v)[:240] if not isinstance(v, str) else v[:240]
                out.append((child_path, preview))
            out.extend(find_thinking_fields(v, child_path))
    elif isinstance(obj, list):
        for i, item in enumerate(obj):
            out.extend(find_thinking_fields(item, f"{path}[{i}]"))
    return out


def find_unknown_keys(obj: Any, known: set[str]) -> list[str]:
    """Return dotted paths whose leaf key is NOT in the known set."""
    paths = walk_keys(obj)
    out = []
    for path, _t in paths:
        leaf = path.split(".")[-1].split("[")[0]
        if leaf not in known:
            out.append(path)
    return sorted(set(out))


def iter_sse_lines(resp: requests.Response) -> Iterator[str]:
    for raw in resp.iter_lines(decode_unicode=True, chunk_size=1):
        if raw is None:
            continue
        yield raw


def extract_sse_data(line: str) -> str | None:
    if not line.startswith("data:"):
        return None
    payload = line[5:].lstrip()
    if payload == "[DONE]":
        return None
    return payload


# ─────────────────────────────────────────────────────────────
# split_concat_json — brace-depth-aware splitter (better than the
# agent's substringBefore("}{") which silently drops 2nd/3rd calls)
# ─────────────────────────────────────────────────────────────

def split_concat_json(s: str) -> list[str]:
    out, buf, depth, in_str, esc = [], [], 0, False, False
    for ch in s:
        buf.append(ch)
        if esc:
            esc = False
            continue
        if ch == "\\" and in_str:
            esc = True
            continue
        if ch == '"':
            in_str = not in_str
            continue
        if in_str:
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                out.append("".join(buf))
                buf = []
    if buf:
        out.append("".join(buf))
    return [o for o in out if o.strip()]


# ─────────────────────────────────────────────────────────────
# Streaming XML parser (Mode B) — tolerant, accepts unclosed tags
# ─────────────────────────────────────────────────────────────

class StreamingXmlToolParser:
    def __init__(self) -> None:
        self.buf = ""
        self.tools: list[ParsedToolCall] = []

    def feed(self, text: str) -> None:
        self.buf += text
        while True:
            m = re.search(r"<tool>([\s\S]*?)</tool>", self.buf)
            if not m:
                return
            block = m.group(1)
            self.buf = self.buf[m.end():]
            self._parse_block(block)

    def finish(self) -> None:
        m = re.search(r"<tool>([\s\S]*)$", self.buf)
        if m:
            self._parse_block(m.group(1))
            self.buf = ""

    def _parse_block(self, block: str) -> None:
        name_m = re.search(r"<name>\s*([^<]+?)\s*</name>", block)
        if not name_m:
            return
        name = name_m.group(1).strip()
        args: dict[str, str] = {}
        args_m = re.search(r"<args>([\s\S]*?)</args>", block)
        body = args_m.group(1) if args_m else block
        for pm in re.finditer(r"<([a-zA-Z_][\w-]*)>([\s\S]*?)</\1>", body):
            tag = pm.group(1)
            if tag in ("name", "args"):
                continue
            args[tag] = pm.group(2).strip()
        self.tools.append(ParsedToolCall(name=name, args=args, raw=block))


FENCE_RE = re.compile(r"```json\s*([\s\S]*?)```", re.IGNORECASE)


# ─────────────────────────────────────────────────────────────
# Mode body builders
# ─────────────────────────────────────────────────────────────

DEFAULT_MAX_TOKENS = 2048


def build_body_a(scenario: Scenario, model: str) -> dict:
    return {
        "model": model,
        "stream": True,
        "stream_options": {"include_usage": True},
        "max_tokens": scenario.max_output_tokens or DEFAULT_MAX_TOKENS,
        "temperature": 0.2,
        "tools": NATIVE_TOOLS,
        "messages": [{"role": "user", "content": scenario.user_prompt}],
    }


def build_body_b(scenario: Scenario, model: str) -> dict:
    return {
        "model": model,
        "stream": True,
        "stream_options": {"include_usage": True},
        "max_tokens": scenario.max_output_tokens or DEFAULT_MAX_TOKENS,
        "temperature": 0.2,
        "messages": [
            {"role": "user", "content": XML_TOOL_INSTRUCTIONS + "\n\n" + scenario.user_prompt}
        ],
    }


def build_body_c(scenario: Scenario, model: str) -> dict:
    return {
        "model": model,
        "stream": True,
        "stream_options": {"include_usage": True},
        "max_tokens": scenario.max_output_tokens or DEFAULT_MAX_TOKENS,
        "temperature": 0.2,
        "messages": [
            {"role": "user", "content": JSON_TOOL_INSTRUCTIONS + "\n\n" + scenario.user_prompt}
        ],
    }


# ─────────────────────────────────────────────────────────────
# Unified streaming runner — handles SSE I/O, raw dump, timing
# ─────────────────────────────────────────────────────────────

def run_scenario(
    client: SourcegraphClient,
    scenario: Scenario,
    mode: str,
    model: str,
    raw_dir: Path,
    print_raw_live: bool,
    verbose_streaming: bool = False,
) -> RunOutcome:

    description, body = {
        "A": ("native function calling (tools=[...] + tool_calls deltas)", build_body_a(scenario, model)),
        "B": ("XML tool tags inside content (Cline-style)",                 build_body_b(scenario, model)),
        "C": ("single ```json fenced block in content",                     build_body_c(scenario, model)),
    }[mode]
    body_str = json.dumps(body)

    raw_dir.mkdir(parents=True, exist_ok=True)
    raw_path = raw_dir / f"{scenario.name}_{mode}.txt"
    outcome = RunOutcome(
        scenario=scenario.name,
        mode=mode,
        description=description,
        scenario_description=scenario.description,
        request_body_size=len(body_str),
        raw_sse_path=str(raw_path),
    )

    raw_lines: list[str] = [
        f"# scenario: {scenario.name}",
        f"# mode:     {mode}",
        f"# model:    {model}",
        f"# prompt:",
        *("#   " + ln for ln in scenario.user_prompt.splitlines()),
        f"# request body ({len(body_str)} bytes):",
        json.dumps(body, indent=2),
        "# ─── SSE wire log (millis since request start) ───",
    ]

    # Per-mode parsing state
    tool_builders: dict[int, dict[str, Any]] = {}
    content_buf: list[str] = []
    xml_parser = StreamingXmlToolParser() if mode == "B" else None

    start = time.monotonic()
    first_byte_seen = False
    last_chunk_ts: float | None = None

    try:
        resp = client.post_chat(body, stream=True)
        outcome.http_status = resp.status_code
        if resp.status_code != 200:
            err_body = resp.text[:2000]
            outcome.transport_error = f"HTTP {resp.status_code}: {err_body[:300]}"
            raw_lines.append(f"!! HTTP {resp.status_code}")
            raw_lines.append(err_body)
            raw_path.write_text("\n".join(raw_lines), encoding="utf-8")
            return outcome

        for line in iter_sse_lines(resp):
            if line == "":
                continue
            if not first_byte_seen:
                outcome.time_to_first_byte_ms = (time.monotonic() - start) * 1000
                first_byte_seen = True
            ts = (time.monotonic() - start) * 1000
            raw_lines.append(f"{ts:8.1f}ms  {line}")
            if print_raw_live:
                print(f"[{mode}] {ts:8.1f}ms  {line}")

            # Track [DONE] sentinel
            if line.strip() == "data: [DONE]":
                outcome.saw_done_sentinel = True
                continue

            payload = extract_sse_data(line)
            if payload is None:
                continue
            outcome.sse_chunks += 1

            # Per-chunk timing for streaming health analysis
            if last_chunk_ts is not None:
                gap = ts - last_chunk_ts
                outcome.inter_chunk_gaps_ms.append(gap)
                if gap > outcome.max_inter_chunk_gap_ms:
                    outcome.max_inter_chunk_gap_ms = gap
            outcome.chunk_timestamps_ms.append(ts)
            last_chunk_ts = ts

            try:
                chunk = json.loads(payload)
            except json.JSONDecodeError as e:
                outcome.parse_warnings.append(f"chunk parse error: {e}")
                continue

            if chunk.get("usage"):
                outcome.usage = chunk["usage"]
                outcome.saw_usage_chunk = True
                outcome.cache_read_tokens = chunk["usage"].get("cache_read_input_tokens")
                outcome.cache_creation_tokens = chunk["usage"].get("cache_creation_input_tokens")

            for choice in chunk.get("choices", []):
                fr = choice.get("finish_reason")
                if fr:
                    outcome.finish_reason = fr
                delta = choice.get("delta", {}) or {}
                content_piece = delta.get("content")
                if content_piece:
                    content_buf.append(content_piece)
                    outcome.chunk_sizes_chars.append(len(content_piece))
                    if xml_parser is not None:
                        xml_parser.feed(content_piece)

                # Mode A only: assemble tool_call deltas by index
                if mode == "A":
                    for tc in delta.get("tool_calls", []) or []:
                        idx = tc.get("index", 0)
                        b = tool_builders.setdefault(idx, {"id": "", "name": "", "args": ""})
                        if tc.get("id"):
                            b["id"] = tc["id"]
                        fn = tc.get("function") or {}
                        if fn.get("name"):
                            b["name"] = fn["name"]
                        if fn.get("arguments"):
                            b["args"] += fn["arguments"]
    except requests.RequestException as e:
        outcome.transport_error = f"network: {e}"
    finally:
        outcome.total_time_ms = (time.monotonic() - start) * 1000
        # Compute derived streaming metrics
        outcome.content_length_chars = sum(outcome.chunk_sizes_chars)
        if outcome.total_time_ms > 0 and outcome.content_length_chars > 0:
            outcome.throughput_chars_per_sec = (
                outcome.content_length_chars / (outcome.total_time_ms / 1000.0)
            )
        gaps = sorted(outcome.inter_chunk_gaps_ms)
        if gaps:
            p95_idx = int(len(gaps) * 0.95)
            outcome.p95_inter_chunk_gap_ms = gaps[min(p95_idx, len(gaps) - 1)]

        raw_lines.append(f"# total: {outcome.total_time_ms:.0f}ms, "
                         f"chunks: {outcome.sse_chunks}, "
                         f"content_chars: {outcome.content_length_chars}, "
                         f"max_gap: {outcome.max_inter_chunk_gap_ms:.0f}ms, "
                         f"throughput: {outcome.throughput_chars_per_sec:.0f}chars/s, "
                         f"finish_reason: {outcome.finish_reason}, "
                         f"done_sentinel: {outcome.saw_done_sentinel}")
        raw_path.write_text("\n".join(raw_lines), encoding="utf-8")

        # Verbose streaming output
        if verbose_streaming and outcome.inter_chunk_gaps_ms:
            print(f"      [streaming-metrics] chunks={outcome.sse_chunks} "
                  f"content={outcome.content_length_chars}chars "
                  f"throughput={outcome.throughput_chars_per_sec:.0f}c/s "
                  f"max_gap={outcome.max_inter_chunk_gap_ms:.0f}ms "
                  f"p95_gap={outcome.p95_inter_chunk_gap_ms:.0f}ms "
                  f"done={outcome.saw_done_sentinel}")
            # Flag dangerous gaps (> 10s could approach timeout limits)
            danger_gaps = [(i, g) for i, g in enumerate(outcome.inter_chunk_gaps_ms) if g > 10_000]
            if danger_gaps:
                print(f"      [streaming-WARNING] {len(danger_gaps)} gap(s) > 10s: "
                      f"{[(i, f'{g:.0f}ms') for i, g in danger_gaps]}")

    outcome.final_content = "".join(content_buf)

    # Mode-specific tool extraction
    if mode == "A":
        for idx in sorted(tool_builders.keys()):
            b = tool_builders[idx]
            raw = b["args"]
            if not raw and not b["name"]:
                continue
            if "}{" in raw:
                outcome.parse_warnings.append(
                    f"index {idx}: concatenated JSON ({len(raw)} chars) — splitting "
                    f"with brace-depth counter"
                )
                for piece in split_concat_json(raw):
                    try:
                        outcome.tool_calls.append(
                            ParsedToolCall(name=b["name"], args=json.loads(piece), raw=piece)
                        )
                    except json.JSONDecodeError as e:
                        outcome.parse_warnings.append(f"  split piece failed: {e}")
            else:
                try:
                    args = json.loads(raw) if raw else {}
                    outcome.tool_calls.append(ParsedToolCall(name=b["name"], args=args, raw=raw))
                except json.JSONDecodeError as e:
                    outcome.parse_warnings.append(f"index {idx} ({b['name']}): args parse error: {e}")
    elif mode == "B":
        assert xml_parser is not None
        xml_parser.finish()
        outcome.tool_calls = xml_parser.tools
    elif mode == "C":
        m = FENCE_RE.search(outcome.final_content)
        if m is None:
            outcome.parse_warnings.append("no ```json fenced block found")
        else:
            try:
                parsed = json.loads(m.group(1))
                for tc in parsed.get("tool_calls", []) or []:
                    outcome.tool_calls.append(ParsedToolCall(
                        name=tc.get("name", ""),
                        args=tc.get("args", {}) or {},
                        raw=json.dumps(tc),
                    ))
            except json.JSONDecodeError as e:
                outcome.parse_warnings.append(f"json fence parse error: {e}")

    # Evaluate assertions
    evaluate_assertions(outcome, scenario)
    return outcome


# ─────────────────────────────────────────────────────────────
# Assertion engine
# ─────────────────────────────────────────────────────────────

def evaluate_assertions(outcome: RunOutcome, scenario: Scenario) -> None:
    A = outcome.assertions

    # 1. transport
    A.append(Assertion(
        "transport_ok",
        outcome.transport_error is None and outcome.http_status == 200,
        outcome.transport_error or f"HTTP {outcome.http_status}" if outcome.http_status != 200 else "ok",
    ))
    if outcome.transport_error or outcome.http_status != 200:
        return  # short-circuit — no point checking the rest

    # 2. SSE actually streamed
    A.append(Assertion(
        "sse_chunks_received",
        outcome.sse_chunks > 0,
        f"{outcome.sse_chunks} chunks",
    ))

    # 3. finish reason captured
    A.append(Assertion(
        "finish_reason_present",
        outcome.finish_reason is not None,
        outcome.finish_reason or "missing",
    ))

    # 4. finish reason in expected set
    if scenario.finish_reason_in is not None:
        ok = outcome.finish_reason in scenario.finish_reason_in
        A.append(Assertion(
            "finish_reason_expected",
            ok,
            f"got '{outcome.finish_reason}', want one of {sorted(scenario.finish_reason_in)}",
        ))

    # 5. tool count within expected range
    n = len(outcome.tool_calls)
    A.append(Assertion(
        "tool_count_min",
        n >= scenario.min_tools,
        f"got {n}, expected >= {scenario.min_tools}",
    ))
    A.append(Assertion(
        "tool_count_max",
        n <= scenario.max_tools,
        f"got {n}, expected <= {scenario.max_tools}",
    ))

    # 6. tool names match (subset check — we accept extras as long as min/max passes)
    if scenario.expected_tool_names:
        actual_names = {tc.name for tc in outcome.tool_calls}
        missing = scenario.expected_tool_names - actual_names
        A.append(Assertion(
            "expected_tool_names_present",
            not missing,
            "all present" if not missing else f"missing: {sorted(missing)} (got: {sorted(actual_names)})",
        ))

    # 7. required arg keys present per tool
    for tool_name, required_keys in scenario.must_include_arg_keys.items():
        matching = [tc for tc in outcome.tool_calls if tc.name == tool_name]
        if not matching:
            A.append(Assertion(
                f"arg_keys[{tool_name}]_tool_missing",
                False,
                f"no tool call with name '{tool_name}' found",
            ))
            continue
        for tc in matching:
            missing_keys = required_keys - set(tc.args.keys())
            A.append(Assertion(
                f"arg_keys[{tool_name}]",
                not missing_keys,
                "all keys present" if not missing_keys else f"missing keys: {sorted(missing_keys)} (got: {sorted(tc.args.keys())})",
            ))
            break  # only check first match

    # 8. content presence
    has_text = bool(outcome.final_content.strip())
    if scenario.require_text_content:
        A.append(Assertion("text_content_present", has_text, f"len={len(outcome.final_content)}"))
    if scenario.forbid_text_content:
        A.append(Assertion("text_content_absent", not has_text, f"len={len(outcome.final_content)}"))

    # 9. usage chunk (informational — only check that include_usage was honored)
    A.append(Assertion(
        "usage_chunk_emitted",
        outcome.saw_usage_chunk,
        "yes" if outcome.saw_usage_chunk else "NO usage chunk seen even though stream_options.include_usage=true",
    ))

    # 10. parse warnings (informational, but counted)
    A.append(Assertion(
        "no_parse_warnings",
        len(outcome.parse_warnings) == 0,
        "clean" if not outcome.parse_warnings else f"{len(outcome.parse_warnings)} warnings",
    ))


# ─────────────────────────────────────────────────────────────
# Side probes
# ─────────────────────────────────────────────────────────────

def _short(text: str, n: int = 200) -> str:
    return text[:n].replace("\n", " ")


def probe_tool_choice_variants(client: SourcegraphClient, model: str) -> dict:
    """tool_choice: auto / none / forced function — accepted AND actually honored?
    HTTP 200 alone is not enough; we parse the response to check whether the
    directive was silently ignored."""
    out = {}
    for label, tc in [
        ("auto",   "auto"),
        ("none",   "none"),
        ("forced", {"type": "function", "function": {"name": "read_file"}}),
    ]:
        body = {
            "model": model, "max_tokens": 64, "temperature": 0,
            "tools": NATIVE_TOOLS, "tool_choice": tc,
            "messages": [{"role": "user", "content": "Read the file src/main.kt using the read_file tool."}],
        }
        try:
            r = client.post_chat(body, stream=False)
            entry: dict = {"status": r.status_code, "preview": _short(r.text)}
            if r.status_code == 200:
                try:
                    resp = r.json()
                    msg = (resp.get("choices") or [{}])[0].get("message", {})
                    has_tool_calls = bool(msg.get("tool_calls"))
                    finish = (resp.get("choices") or [{}])[0].get("finish_reason")
                    entry["has_tool_calls"] = has_tool_calls
                    entry["finish_reason"] = finish
                    # Determine if the directive was honored
                    if label == "forced":
                        entry["honored"] = has_tool_calls
                        entry["verdict"] = "HONORED" if has_tool_calls else "SILENTLY_IGNORED"
                    elif label == "none":
                        entry["honored"] = not has_tool_calls
                        entry["verdict"] = "HONORED" if not has_tool_calls else "SILENTLY_IGNORED"
                    else:  # auto
                        entry["honored"] = True  # auto doesn't enforce a specific outcome
                        entry["verdict"] = "ACCEPTED"
                except Exception:
                    entry["verdict"] = "PARSE_ERROR"
            else:
                entry["verdict"] = "REJECTED"
            out[label] = entry
        except requests.RequestException as e:
            out[label] = {"status": 0, "error": str(e), "verdict": "TRANSPORT_ERROR"}
    return out


def probe_cache_control(client: SourcegraphClient, model: str) -> dict:
    """Anthropic prompt-cache hint inside an OpenAI-shape content part."""
    body = {
        "model": model, "max_tokens": 64, "temperature": 0,
        "messages": [{
            "role": "user",
            "content": [{
                "type": "text",
                "text": "Reply with 'ok'.",
                "cache_control": {"type": "ephemeral"},
            }],
        }],
    }
    try:
        r = client.post_chat(body, stream=False)
        usage = None
        try:
            usage = r.json().get("usage")
        except Exception:
            pass
        return {"status": r.status_code, "usage": usage, "preview": _short(r.text)}
    except requests.RequestException as e:
        return {"status": 0, "error": str(e)}


def probe_max_tokens(client: SourcegraphClient, model: str) -> dict:
    """Try max_tokens at 8K, 16K, 64K — confirm the spec cap is fiction."""
    out = {}
    for n in (8000, 16000, 64000):
        body = {
            "model": model, "max_tokens": n, "temperature": 0,
            "messages": [{"role": "user", "content": "Reply with one word."}],
        }
        try:
            r = client.post_chat(body, stream=False)
            out[str(n)] = {"status": r.status_code, "preview": _short(r.text, 160)}
        except requests.RequestException as e:
            out[str(n)] = {"status": 0, "error": str(e)}
    return out


def probe_response_format_json(client: SourcegraphClient, model: str) -> dict:
    body = {
        "model": model, "max_tokens": 64, "temperature": 0,
        "response_format": {"type": "json_object"},
        "messages": [{"role": "user", "content": 'Reply with a JSON object {"x": 1}.'}],
    }
    try:
        r = client.post_chat(body, stream=False)
        return {"status": r.status_code, "preview": _short(r.text)}
    except requests.RequestException as e:
        return {"status": 0, "error": str(e)}


def probe_system_role(client: SourcegraphClient, model: str) -> dict:
    """Confirm: does the gateway still reject 'system' role?"""
    body = {
        "model": model, "max_tokens": 32, "temperature": 0,
        "messages": [
            {"role": "system", "content": "You are terse."},
            {"role": "user",   "content": "Hi."},
        ],
    }
    try:
        r = client.post_chat(body, stream=False)
        return {"status": r.status_code, "preview": _short(r.text)}
    except requests.RequestException as e:
        return {"status": 0, "error": str(e)}


def probe_tool_role(client: SourcegraphClient, model: str) -> dict:
    """Does the gateway accept 'tool' role messages with tool_call_id?"""
    body = {
        "model": model, "max_tokens": 32, "temperature": 0,
        "tools": NATIVE_TOOLS,
        "messages": [
            {"role": "user", "content": "Read README.md"},
            {"role": "assistant", "content": None, "tool_calls": [
                {"id": "call_1", "type": "function",
                 "function": {"name": "read_file", "arguments": '{"path":"README.md"}'}}
            ]},
            {"role": "tool", "tool_call_id": "call_1", "content": "FILE CONTENTS HERE"},
            {"role": "user", "content": "Summarize."},
        ],
    }
    try:
        r = client.post_chat(body, stream=False)
        return {"status": r.status_code, "preview": _short(r.text)}
    except requests.RequestException as e:
        return {"status": 0, "error": str(e)}


def probe_include_usage_isolation(client: SourcegraphClient, model: str) -> dict:
    """Stream WITHOUT include_usage to see if usage is omitted then."""
    body = {
        "model": model, "max_tokens": 64, "temperature": 0, "stream": True,
        "messages": [{"role": "user", "content": "Reply with one word."}],
    }
    saw_usage = False
    try:
        r = client.post_chat(body, stream=True)
        if r.status_code != 200:
            return {"status": r.status_code, "preview": _short(r.text)}
        for line in iter_sse_lines(r):
            payload = extract_sse_data(line)
            if not payload:
                continue
            try:
                ch = json.loads(payload)
                if ch.get("usage"):
                    saw_usage = True
            except json.JSONDecodeError:
                pass
        return {"status": 200, "usage_emitted_without_include_usage": saw_usage}
    except requests.RequestException as e:
        return {"status": 0, "error": str(e)}


def probe_tool_result_formats(client: SourcegraphClient, model: str) -> dict:
    """Since role:tool gives HTTP 400, test every alternative format for delivering
    tool results. The goal is to find what actually works so the plugin can use it."""
    base = {"model": model, "max_tokens": 64, "temperature": 0, "tools": NATIVE_TOOLS}

    def run(label: str, messages: list) -> dict:
        try:
            r = client.post_chat({**base, "messages": messages}, stream=False)
            entry: dict = {"status": r.status_code}
            if r.status_code == 200:
                try:
                    resp = r.json()
                    msg = (resp.get("choices") or [{}])[0].get("message", {})
                    entry["content"] = (msg.get("content") or "")[:160]
                    entry["has_tool_calls"] = bool(msg.get("tool_calls"))
                    entry["finish_reason"] = (resp.get("choices") or [{}])[0].get("finish_reason")
                    entry["verdict"] = "WORKS"
                except Exception:
                    entry["verdict"] = "PARSE_ERROR"
            else:
                entry["error"] = _short(r.text, 200)
                entry["verdict"] = "REJECTED"
        except requests.RequestException as e:
            entry = {"status": 0, "error": str(e), "verdict": "TRANSPORT_ERROR"}
        return entry

    TOOL_CALLS_BLOCK = [{"id": "call_1", "type": "function",
                          "function": {"name": "read_file", "arguments": '{"path":"README.md"}'}}]
    out = {}

    # A: Standard OpenAI — role:tool with tool_call_id (known 400 from side probe)
    out["A_tool_role_null_content"] = run("A", [
        {"role": "user", "content": "Read README.md"},
        {"role": "assistant", "content": None, "tool_calls": TOOL_CALLS_BLOCK},
        {"role": "tool", "tool_call_id": "call_1", "content": "class Foo { fun bar() = 42 }"},
        {"role": "user", "content": "Summarize the file."},
    ])

    # B: assistant empty-string content instead of null (some gateways reject null)
    out["B_tool_role_empty_content"] = run("B", [
        {"role": "user", "content": "Read README.md"},
        {"role": "assistant", "content": "", "tool_calls": TOOL_CALLS_BLOCK},
        {"role": "tool", "tool_call_id": "call_1", "content": "class Foo { fun bar() = 42 }"},
        {"role": "user", "content": "Summarize the file."},
    ])

    # C: No tool role at all — assistant text + user carries the result
    out["C_user_msg_carries_result"] = run("C", [
        {"role": "user", "content": "Read README.md"},
        {"role": "assistant", "content": "I'll read that file now.", "tool_calls": TOOL_CALLS_BLOCK},
        {"role": "user", "content": "Tool result for read_file(path='README.md'):\nclass Foo { fun bar() = 42 }\n\nSummarize the file."},
    ])

    # D: Omit tool_calls from assistant entirely — pure conversational fallback
    out["D_pure_conversational"] = run("D", [
        {"role": "user", "content": "Read README.md and summarize it."},
        {"role": "assistant", "content": "Here is the content of README.md:\nclass Foo { fun bar() = 42 }"},
        {"role": "user", "content": "Give me a one-sentence summary."},
    ])

    # E: role:tool but WITHOUT tool_call_id field
    out["E_tool_role_no_call_id"] = run("E", [
        {"role": "user", "content": "Read README.md"},
        {"role": "assistant", "content": None, "tool_calls": TOOL_CALLS_BLOCK},
        {"role": "tool", "content": "class Foo { fun bar() = 42 }"},
        {"role": "user", "content": "Summarize the file."},
    ])

    # F: function role (legacy OpenAI format)
    out["F_function_role"] = run("F", [
        {"role": "user", "content": "Read README.md"},
        {"role": "assistant", "content": None, "function_call": {"name": "read_file", "arguments": '{"path":"README.md"}'}},
        {"role": "function", "name": "read_file", "content": "class Foo { fun bar() = 42 }"},
        {"role": "user", "content": "Summarize the file."},
    ])

    working = [k for k, v in out.items() if v.get("verdict") == "WORKS"]
    out["_working_formats"] = working
    out["_recommended"] = working[0] if working else None
    return out


def probe_multi_turn_tool_use(client: SourcegraphClient, model: str) -> dict:
    """Full 2-turn tool call round-trip: user → model tool call → tool result → model text.
    This is the core agent loop. Tests the complete flow our plugin depends on."""
    out: dict = {}

    # Step 1: Get the model to actually call a tool
    step1 = {
        "model": model, "max_tokens": 128, "temperature": 0,
        "tools": NATIVE_TOOLS,
        "messages": [{"role": "user", "content":
                       "Use the read_file tool to read src/main/kotlin/Foo.kt. "
                       "Just call the tool, do not explain."}],
    }
    try:
        r1 = client.post_chat(step1, stream=False)
        out["step1_status"] = r1.status_code
        if r1.status_code != 200:
            out["step1_error"] = _short(r1.text, 300)
            out["verdict"] = "STEP1_HTTP_ERROR"
            return out

        resp1 = _decode_json(r1)
        msg1 = (resp1.get("choices") or [{}])[0].get("message", {})
        tool_calls = msg1.get("tool_calls") or []
        out["step1_tool_calls_count"] = len(tool_calls)

        if not tool_calls:
            out["step1_content_preview"] = (msg1.get("content") or "")[:200]
            out["verdict"] = "STEP1_NO_TOOL_CALL_RETURNED"
            return out

        tc = tool_calls[0]
        call_id = tc.get("id", "call_1")
        out["step1_tool_name"] = tc.get("function", {}).get("name")
        out["step1_call_id"] = call_id

        FAKE_RESULT = "class Foo {\n    fun bar(): Int = 42\n}\n"

        # Step 2a: Standard OpenAI tool role
        step2a = {
            "model": model, "max_tokens": 64, "temperature": 0,
            "tools": NATIVE_TOOLS,
            "messages": [
                {"role": "user", "content": "Use the read_file tool to read src/main/kotlin/Foo.kt."},
                {"role": "assistant", "content": msg1.get("content"), "tool_calls": tool_calls},
                {"role": "tool", "tool_call_id": call_id, "content": FAKE_RESULT},
                {"role": "user", "content": "What does the file contain?"},
            ],
        }
        r2a = client.post_chat(step2a, stream=False)
        out["step2a_tool_role_status"] = r2a.status_code
        if r2a.status_code == 200:
            resp2a = _decode_json(r2a)
            msg2a = (resp2a.get("choices") or [{}])[0].get("message", {})
            out["step2a_content"] = (msg2a.get("content") or "")[:200]
            out["step2a_verdict"] = "TOOL_ROLE_WORKS"
        else:
            out["step2a_error"] = _short(r2a.text, 200)
            out["step2a_verdict"] = "TOOL_ROLE_REJECTED"

            # Step 2b: Fallback — user message carries the tool result
            step2b = {
                "model": model, "max_tokens": 64, "temperature": 0,
                "tools": NATIVE_TOOLS,
                "messages": [
                    {"role": "user", "content": "Use the read_file tool to read src/main/kotlin/Foo.kt."},
                    {"role": "assistant", "content": "Reading the file now.", "tool_calls": tool_calls},
                    {"role": "user", "content":
                     f"Tool result for {out['step1_tool_name']}:\n{FAKE_RESULT}\nWhat does the file contain?"},
                ],
            }
            r2b = client.post_chat(step2b, stream=False)
            out["step2b_user_msg_status"] = r2b.status_code
            if r2b.status_code == 200:
                resp2b = _decode_json(r2b)
                msg2b = (resp2b.get("choices") or [{}])[0].get("message", {})
                out["step2b_content"] = (msg2b.get("content") or "")[:200]
                out["step2b_verdict"] = "USER_MSG_FALLBACK_WORKS"
            else:
                out["step2b_error"] = _short(r2b.text, 200)
                out["step2b_verdict"] = "BOTH_FORMATS_FAILED"

        out["verdict"] = out.get("step2a_verdict") or out.get("step2b_verdict", "UNKNOWN")

    except requests.RequestException as e:
        out["verdict"] = "TRANSPORT_ERROR"
        out["error"] = str(e)

    return out


# ═════════════════════════════════════════════════════════════
# ADVANCED PROBES — thinking, model metadata, headers, vision,
# Anthropic native fields, prompt-cache verification
# ═════════════════════════════════════════════════════════════

# Known OpenAI-spec leaf keys for chat completions / models endpoints.
# Anything outside this set is "interesting".
KNOWN_RESPONSE_KEYS = {
    "id", "object", "created", "model", "choices", "index", "message", "delta",
    "role", "content", "tool_calls", "tool_call_id", "function", "name",
    "arguments", "type", "finish_reason", "usage", "prompt_tokens",
    "completion_tokens", "total_tokens", "system_fingerprint", "service_tier",
    "data", "owned_by", "permission", "root", "parent", "logprobs",
    "top_logprobs", "text", "image_url", "url",
}


def _decode_json(resp: requests.Response) -> Any:
    try:
        return resp.json()
    except Exception:
        return {"_raw": resp.text[:2000]}


def probe_thinking_strategies(client: SourcegraphClient, model: str,
                              thinking_model: str | None) -> dict:
    """Try every known way to activate extended thinking / reasoning across
    Anthropic, OpenAI o-series, and DeepSeek-style models. For each strategy
    we look at the response for fields whose name suggests reasoning content."""
    target = thinking_model or model
    out: dict[str, Any] = {"target_model": target, "strategies": {}}

    base = {
        "model": target,
        "max_tokens": 2048,
        "temperature": 1.0,  # Anthropic thinking REQUIRES temperature=1
        "messages": [{
            "role": "user",
            "content": (
                "Explain step by step why the sum of the first 100 positive "
                "integers is 5050. Show your reasoning."
            ),
        }],
    }

    strategies = {
        "baseline_no_thinking_param": {},
        "anthropic_thinking_block": {
            "thinking": {"type": "enabled", "budget_tokens": 1024}
        },
        "anthropic_thinking_top_only": {
            "thinking": {"budget_tokens": 1024}
        },
        "extended_thinking_flag": {
            "extended_thinking": True
        },
        "openai_reasoning_effort_high": {
            "reasoning_effort": "high"
        },
        "openai_reasoning_object": {
            "reasoning": {"effort": "high"}
        },
        "deepseek_reasoning_content_hint": {
            "include_reasoning": True
        },
        "anthropic_thinking_streaming": {
            "thinking": {"type": "enabled", "budget_tokens": 1024},
            "stream": True,
            "stream_options": {"include_usage": True},
        },
    }

    for label, extra in strategies.items():
        body = {**base, **extra}
        record: dict[str, Any] = {"sent": list(extra.keys()) or ["(none)"]}
        try:
            stream = bool(extra.get("stream"))
            r = client.post_chat(body, stream=stream)
            record["status"] = r.status_code
            if r.status_code != 200:
                record["error_preview"] = _short(r.text, 300)
                out["strategies"][label] = record
                continue

            if not stream:
                resp = _decode_json(r)
                # Look for thinking/reasoning fields anywhere in the response
                hits = find_thinking_fields(resp)
                record["thinking_field_hits"] = [
                    {"path": p, "preview": v} for p, v in hits
                ]
                # Top-level usage extras (Anthropic adds reasoning_tokens etc.)
                usage = resp.get("usage") if isinstance(resp, dict) else None
                if usage:
                    record["usage"] = usage
                    record["usage_extra_keys"] = [
                        k for k in usage.keys()
                        if k not in {"prompt_tokens", "completion_tokens", "total_tokens"}
                    ]
                # Unknown keys overall
                record["unknown_keys"] = find_unknown_keys(resp, KNOWN_RESPONSE_KEYS)
                # Save full body trimmed for forensic review
                record["full_body_preview"] = json.dumps(resp)[:800]
            else:
                # Streaming variant: collect deltas, look for thinking-shaped delta keys
                delta_keys: set[str] = set()
                thinking_chunks: list[str] = []
                content_chunks: list[str] = []
                last_usage: dict | None = None
                first_chunk_dump: list[str] = []
                for line in iter_sse_lines(r):
                    payload = extract_sse_data(line)
                    if not payload:
                        continue
                    if len(first_chunk_dump) < 3:
                        first_chunk_dump.append(payload[:300])
                    try:
                        ch = json.loads(payload)
                    except json.JSONDecodeError:
                        continue
                    if ch.get("usage"):
                        last_usage = ch["usage"]
                    for choice in ch.get("choices", []):
                        delta = choice.get("delta") or {}
                        for k in delta.keys():
                            delta_keys.add(k)
                        # Common thinking-delta locations
                        for k in ("thinking", "reasoning", "reasoning_content",
                                  "thought", "thinking_content"):
                            v = delta.get(k)
                            if isinstance(v, str) and v:
                                thinking_chunks.append(v)
                            elif isinstance(v, dict):
                                inner = v.get("text") or v.get("content")
                                if isinstance(inner, str):
                                    thinking_chunks.append(inner)
                        c = delta.get("content")
                        if isinstance(c, str):
                            content_chunks.append(c)
                record["delta_keys_seen"] = sorted(delta_keys)
                record["thinking_chunks_count"] = len(thinking_chunks)
                record["thinking_preview"] = ("".join(thinking_chunks))[:500]
                record["content_preview"] = ("".join(content_chunks))[:300]
                record["usage"] = last_usage
                record["first_chunks_raw"] = first_chunk_dump
        except requests.RequestException as e:
            record["error"] = str(e)
        out["strategies"][label] = record
    return out


def probe_model_metadata(client: SourcegraphClient) -> dict:
    """Dump full /.api/llm/models payload, the per-model retrieve endpoint,
    and look for context_window / max_output_tokens / capability fields."""
    out: dict[str, Any] = {}

    # 1. List models — dump first 5 in full + report all unique keys observed
    status, body, headers = client.list_models_full()
    out["list_status"] = status
    out["list_response_headers"] = {
        k: v for k, v in headers.items() if k.lower().startswith(("x-", "ratelimit", "retry"))
    }
    if isinstance(body, dict) and "data" in body:
        models = body["data"]
        out["model_count"] = len(models)
        out["first_5_models_full"] = models[:5]

        # Collect every leaf key seen across all models
        all_keys: set[str] = set()
        for m in models:
            for path, _t in walk_keys(m):
                all_keys.add(path)
        out["all_observed_model_keys"] = sorted(all_keys)

        # Look for capability/context-window-shaped keys
        capability_hits: dict[str, list[str]] = {}
        for m in models:
            for path, _t in walk_keys(m):
                leaf = path.split(".")[-1].lower()
                if any(t in leaf for t in (
                    "context", "window", "max_token", "max_output",
                    "capability", "supports", "modality", "vision",
                    "tool", "input_limit", "output_limit", "limit",
                )):
                    capability_hits.setdefault(path, []).append(m.get("id", "?"))
        out["capability_field_hits"] = capability_hits
    else:
        out["list_body_preview"] = json.dumps(body)[:500]

    # 2. Per-model retrieve — try with the first available id
    if isinstance(body, dict) and body.get("data"):
        first_id = body["data"][0]["id"]
        status2, single_body, _h = client.get_model(first_id)
        out["retrieve_status"] = status2
        out["retrieve_first_id"] = first_id
        if status2 == 200:
            out["retrieve_full"] = single_body
            out["retrieve_unknown_keys"] = find_unknown_keys(single_body, KNOWN_RESPONSE_KEYS)
        else:
            out["retrieve_preview"] = json.dumps(single_body)[:400]
    return out


def probe_response_headers(client: SourcegraphClient, model: str) -> dict:
    """Dump every response header from a basic chat call. Hunt for x-* hints
    about rate limits, request id, served-by, model used, cache state, etc."""
    body = {
        "model": model, "max_tokens": 16, "temperature": 0,
        "messages": [{"role": "user", "content": "Reply with 'ok'."}],
    }
    try:
        r = client.post_chat(body, stream=False)
        headers = dict(r.headers)
        # Categorize
        rate_limit = {k: v for k, v in headers.items() if "ratelimit" in k.lower() or "retry" in k.lower()}
        request_id = {k: v for k, v in headers.items() if "request-id" in k.lower() or "trace" in k.lower()}
        served_by = {k: v for k, v in headers.items() if "served" in k.lower() or "via" in k.lower() or "cf-" in k.lower()}
        anthropic = {k: v for k, v in headers.items() if "anthropic" in k.lower()}
        sourcegraph = {k: v for k, v in headers.items() if "sourcegraph" in k.lower() or "x-sg-" in k.lower()}
        cache = {k: v for k, v in headers.items() if "cache" in k.lower() or "age" in k.lower() or "etag" in k.lower()}
        return {
            "status": r.status_code,
            "all_headers": headers,
            "rate_limit": rate_limit,
            "request_id": request_id,
            "served_by": served_by,
            "anthropic_passthrough": anthropic,
            "sourcegraph_specific": sourcegraph,
            "cache_headers": cache,
        }
    except requests.RequestException as e:
        return {"status": 0, "error": str(e)}


# 1x1 transparent PNG (smallest valid PNG)
TINY_PNG_B64 = (
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkAAIAAAUAAen63NgAAAAASUVORK5CYII="
)


def probe_vision_input(client: SourcegraphClient, model: str) -> dict:
    """Send a tiny base64 PNG as a content part. Does the gateway accept
    multimodal input? If not, what error?"""
    body = {
        "model": model, "max_tokens": 64, "temperature": 0,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "text", "text": "What is in this image? Reply in 5 words."},
                {"type": "image_url",
                 "image_url": {"url": f"data:image/png;base64,{TINY_PNG_B64}"}},
            ],
        }],
    }
    try:
        r = client.post_chat(body, stream=False)
        return {"status": r.status_code, "preview": _short(r.text, 400)}
    except requests.RequestException as e:
        return {"status": 0, "error": str(e)}


def probe_anthropic_native_fields(client: SourcegraphClient, model: str) -> dict:
    """Try sending Anthropic-native fields that aren't in the OpenAI shape:
    top-level system, top_k, metadata.user_id."""
    out = {}
    cases = {
        "top_level_system": {
            "model": model, "max_tokens": 32, "temperature": 0,
            "system": "You are terse. Reply with one word.",
            "messages": [{"role": "user", "content": "Hi."}],
        },
        "top_k": {
            "model": model, "max_tokens": 32, "temperature": 0, "top_k": 5,
            "messages": [{"role": "user", "content": "Reply 'ok'."}],
        },
        "metadata_user_id": {
            "model": model, "max_tokens": 32, "temperature": 0,
            "metadata": {"user_id": "lab-test-user"},
            "messages": [{"role": "user", "content": "Reply 'ok'."}],
        },
        "stop_sequences": {
            "model": model, "max_tokens": 32, "temperature": 0,
            "stop": ["STOP_HERE"],
            "messages": [{"role": "user", "content": "Count 1 to 10 then say STOP_HERE then 11."}],
        },
        "seed_determinism_first": {
            "model": model, "max_tokens": 32, "temperature": 0, "seed": 42,
            "messages": [{"role": "user", "content": "Tell me a random number."}],
        },
        "seed_determinism_second": {
            "model": model, "max_tokens": 32, "temperature": 0, "seed": 42,
            "messages": [{"role": "user", "content": "Tell me a random number."}],
        },
        "logprobs": {
            "model": model, "max_tokens": 16, "temperature": 0,
            "logprobs": True, "top_logprobs": 3,
            "messages": [{"role": "user", "content": "Reply 'ok'."}],
        },
        "n_multiple_completions": {
            "model": model, "max_tokens": 16, "temperature": 0.5, "n": 2,
            "messages": [{"role": "user", "content": "Tell me a colour."}],
        },
        "service_tier": {
            "model": model, "max_tokens": 16, "temperature": 0,
            "service_tier": "auto",
            "messages": [{"role": "user", "content": "Reply 'ok'."}],
        },
    }
    for label, body in cases.items():
        try:
            r = client.post_chat(body, stream=False)
            entry: dict[str, Any] = {"status": r.status_code}
            if r.status_code == 200:
                resp = _decode_json(r)
                entry["unknown_keys"] = find_unknown_keys(resp, KNOWN_RESPONSE_KEYS)
                if isinstance(resp, dict):
                    msg = (resp.get("choices") or [{}])[0].get("message", {})
                    entry["content_preview"] = (msg.get("content") or "")[:200]
                    entry["choices_count"] = len(resp.get("choices") or [])
                    entry["usage"] = resp.get("usage")
                    entry["finish_reason"] = (resp.get("choices") or [{}])[0].get("finish_reason")
            else:
                entry["preview"] = _short(r.text, 300)
            out[label] = entry
        except requests.RequestException as e:
            out[label] = {"status": 0, "error": str(e)}

    # Cross-check seed determinism
    a = out.get("seed_determinism_first", {}).get("content_preview", "")
    b = out.get("seed_determinism_second", {}).get("content_preview", "")
    out["_seed_deterministic"] = bool(a) and bool(b) and a == b
    return out


def probe_cache_hit_verification(client: SourcegraphClient, model: str) -> dict:
    """Send the SAME request twice with cache_control on a long stable prefix.
    If the gateway honors prompt caching, the second call should report
    cache_read_input_tokens > 0 (Anthropic-style)."""
    long_prefix = "Background context for caching test. " * 200  # ~6KB stable prefix
    msg = [{
        "role": "user",
        "content": [
            {"type": "text", "text": long_prefix, "cache_control": {"type": "ephemeral"}},
            {"type": "text", "text": "Reply with the single word 'ok'."},
        ],
    }]
    body = {
        "model": model, "max_tokens": 16, "temperature": 0, "messages": msg,
    }
    out: dict[str, Any] = {}
    for label in ("first_call", "second_call"):
        try:
            r = client.post_chat(body, stream=False)
            entry: dict[str, Any] = {"status": r.status_code}
            if r.status_code == 200:
                resp = _decode_json(r)
                usage = resp.get("usage") if isinstance(resp, dict) else None
                entry["usage"] = usage
                if isinstance(usage, dict):
                    entry["cache_read"] = usage.get("cache_read_input_tokens")
                    entry["cache_create"] = usage.get("cache_creation_input_tokens")
            else:
                entry["preview"] = _short(r.text, 300)
            out[label] = entry
        except requests.RequestException as e:
            out[label] = {"status": 0, "error": str(e)}

    second = out.get("second_call", {})
    out["_cache_hit_observed"] = bool(second.get("cache_read"))
    return out


def probe_endpoint_discovery(client: SourcegraphClient) -> dict:
    """Try a handful of plausible Sourcegraph paths to see what responds.
    Useful for finding undocumented endpoints (model details, embeddings, etc.)."""
    candidates = [
        "/.api/llm/v1/models",
        "/.api/llm/completions",
        "/.api/llm/embeddings",
        "/.api/llm/v1/chat/completions",
        "/.api/cody/context",  # documented but rarely tested
        "/.api/completions/code",
        "/.api/completions/stream",
        "/.api/graphql",
    ]
    out = {}
    for path in candidates:
        try:
            r = client.session.get(client.base_url + path, timeout=15)
            out[path] = {"GET_status": r.status_code,
                         "preview": _short(r.text, 200)}
        except requests.RequestException as e:
            out[path] = {"error": str(e)}
    return out


# ─────────────────────────────────────────────────────────────
# NEW PROBES — streaming reliability, Cline compat, agent loop
# ─────────────────────────────────────────────────────────────

def probe_streaming_reliability(client: SourcegraphClient, model: str, runs: int = 5) -> dict:
    """Run the SAME streaming request N times to measure reliability.
    Detects: premature stream closes, missing [DONE], missing usage chunks,
    chunk-count variance, and TTFB distribution.
    This is the most important probe for diagnosing the agent's timeout problem:
    streaming is currently disabled because of fragility — this tells us if it's
    stable enough to enable."""
    prompt = "Reply with exactly this sentence: 'The streaming connection is working correctly.'"
    body = {
        "model": model, "max_tokens": 32, "temperature": 0, "stream": True,
        "stream_options": {"include_usage": True},
        "messages": [{"role": "user", "content": prompt}],
    }
    results = []
    for i in range(runs):
        rec: dict[str, Any] = {"run": i + 1}
        try:
            start = time.monotonic()
            r = client.post_chat(body, stream=True)
            rec["status"] = r.status_code
            if r.status_code != 200:
                rec["error"] = _short(r.text, 200)
                rec["verdict"] = "HTTP_ERROR"
                results.append(rec)
                continue

            ttfb: float | None = None
            chunks = 0
            saw_done = False
            saw_usage = False
            content_buf: list[str] = []

            for line in iter_sse_lines(r):
                elapsed = (time.monotonic() - start) * 1000
                if ttfb is None and line.strip():
                    ttfb = elapsed
                if line.strip() == "data: [DONE]":
                    saw_done = True
                    continue
                payload = extract_sse_data(line)
                if not payload:
                    continue
                chunks += 1
                try:
                    ch = json.loads(payload)
                    if ch.get("usage"):
                        saw_usage = True
                    for choice in ch.get("choices", []):
                        c = (choice.get("delta") or {}).get("content") or ""
                        if c:
                            content_buf.append(c)
                except json.JSONDecodeError:
                    pass

            rec["ttfb_ms"] = round(ttfb or 0)
            rec["total_ms"] = round((time.monotonic() - start) * 1000)
            rec["chunks"] = chunks
            rec["saw_done"] = saw_done
            rec["saw_usage"] = saw_usage
            rec["content"] = "".join(content_buf)[:120]
            rec["verdict"] = "OK" if (saw_done and chunks > 0) else (
                "MISSING_DONE" if (not saw_done and chunks > 0) else "EMPTY_STREAM"
            )
        except requests.RequestException as e:
            rec["verdict"] = "TRANSPORT_ERROR"
            rec["error"] = str(e)
        results.append(rec)

    ok = [r for r in results if r.get("verdict") == "OK"]
    ttfbs = [r["ttfb_ms"] for r in results if "ttfb_ms" in r]
    totals = [r["total_ms"] for r in results if "total_ms" in r]
    return {
        "runs": results,
        "ok_count": len(ok),
        "fail_count": runs - len(ok),
        "ttfb_min_ms": min(ttfbs) if ttfbs else None,
        "ttfb_max_ms": max(ttfbs) if ttfbs else None,
        "total_min_ms": min(totals) if totals else None,
        "total_max_ms": max(totals) if totals else None,
        "usage_always_present": all(r.get("saw_usage") for r in results if r.get("verdict") == "OK"),
        "done_always_present": all(r.get("saw_done") for r in results if r.get("verdict") == "OK"),
        "overall_verdict": "STABLE" if len(ok) == runs else f"FLAKY ({len(ok)}/{runs} ok)",
    }


def probe_streaming_vs_nonstreaming_latency(client: SourcegraphClient, model: str) -> dict:
    """Compare streaming TTFB vs non-streaming total latency.
    Key insight: if streaming TTFB is much lower than non-streaming total time,
    enabling streaming in the agent would fix timeout errors even if total
    generation time is the same — because the agent can start processing
    immediately and the read timeout resets with each chunk."""
    prompt = (
        "List 5 Kotlin best practices. Each on a new line, prefixed with the number. "
        "No preamble."
    )
    out: dict[str, Any] = {}

    # Non-streaming (current agent behavior)
    body_ns = {
        "model": model, "max_tokens": 256, "temperature": 0,
        "messages": [{"role": "user", "content": prompt}],
    }
    try:
        start = time.monotonic()
        r = client.post_chat(body_ns, stream=False)
        out["non_streaming_total_ms"] = round((time.monotonic() - start) * 1000)
        out["non_streaming_status"] = r.status_code
        if r.status_code == 200:
            try:
                resp = r.json()
                content = (resp.get("choices") or [{}])[0].get("message", {}).get("content", "")
                out["non_streaming_tokens"] = (resp.get("usage") or {}).get("completion_tokens")
                out["non_streaming_content_preview"] = content[:100]
            except Exception:
                pass
    except requests.RequestException as e:
        out["non_streaming_error"] = str(e)

    # Streaming — measure TTFB and total
    body_s = {**body_ns, "stream": True, "stream_options": {"include_usage": True}}
    try:
        start = time.monotonic()
        r = client.post_chat(body_s, stream=True)
        out["streaming_status"] = r.status_code
        if r.status_code == 200:
            ttfb: float | None = None
            chunks = 0
            content_buf: list[str] = []
            for line in iter_sse_lines(r):
                elapsed = (time.monotonic() - start) * 1000
                if ttfb is None and extract_sse_data(line):
                    ttfb = elapsed
                payload = extract_sse_data(line)
                if not payload:
                    continue
                chunks += 1
                try:
                    ch = json.loads(payload)
                    for choice in ch.get("choices", []):
                        c = (choice.get("delta") or {}).get("content") or ""
                        content_buf.append(c)
                except Exception:
                    pass
            out["streaming_ttfb_ms"] = round(ttfb or 0)
            out["streaming_total_ms"] = round((time.monotonic() - start) * 1000)
            out["streaming_chunks"] = chunks
            out["streaming_content_preview"] = "".join(content_buf)[:100]
    except requests.RequestException as e:
        out["streaming_error"] = str(e)

    # Verdict: if TTFB << non-streaming total, streaming would prevent timeouts
    ttfb = out.get("streaming_ttfb_ms", 0)
    ns_total = out.get("non_streaming_total_ms", 0)
    if ttfb and ns_total:
        ratio = ttfb / ns_total if ns_total else 1
        out["ttfb_to_total_ratio"] = round(ratio, 2)
        out["verdict"] = (
            "STREAMING_PREVENTS_TIMEOUTS" if ratio < 0.3 else
            "STREAMING_MARGINAL_BENEFIT" if ratio < 0.7 else
            "STREAMING_NO_BENEFIT"
        )
    return out


def probe_anthropic_tool_result_content_block(client: SourcegraphClient, model: str) -> dict:
    """Test Anthropic's native content-block format for tool results.
    Cline uses this: {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "..."}]}
    This is different from OpenAI's role:tool (which Sourcegraph rejects with 400).
    If this works, it's the correct way to do multi-turn tool use via Sourcegraph.

    Also tests Anthropic's tool_use content block in assistant message:
    {"role": "assistant", "content": [{"type": "tool_use", "id": "...", "name": "...", "input": {...}}]}
    """
    out: dict[str, Any] = {}

    # Format A: Anthropic content blocks — tool_use in assistant + tool_result in user
    # This is the Anthropic API native format (not OpenAI compatibility layer)
    format_a_msgs = [
        {"role": "user", "content": "Read README.md"},
        {
            "role": "assistant",
            "content": [
                {
                    "type": "tool_use",
                    "id": "toolu_01",
                    "name": "read_file",
                    "input": {"path": "README.md"},
                }
            ],
        },
        {
            "role": "user",
            "content": [
                {
                    "type": "tool_result",
                    "tool_use_id": "toolu_01",
                    "content": "# README\nThis is the readme content.",
                }
            ],
        },
    ]

    # Format B: Mixed — OpenAI tool_calls in assistant + Anthropic tool_result in user
    format_b_msgs = [
        {"role": "user", "content": "Read README.md"},
        {
            "role": "assistant",
            "content": None,
            "tool_calls": [
                {"id": "call_1", "type": "function",
                 "function": {"name": "read_file", "arguments": '{"path":"README.md"}'}}
            ],
        },
        {
            "role": "user",
            "content": [
                {
                    "type": "tool_result",
                    "tool_use_id": "call_1",
                    "content": "# README\nThis is the readme content.",
                }
            ],
        },
    ]

    # Format C: Anthropic tool_result with error flag
    format_c_msgs = [
        {"role": "user", "content": "Read NonExistent.kt"},
        {
            "role": "assistant",
            "content": [{"type": "tool_use", "id": "toolu_02", "name": "read_file",
                          "input": {"path": "NonExistent.kt"}}],
        },
        {
            "role": "user",
            "content": [
                {
                    "type": "tool_result",
                    "tool_use_id": "toolu_02",
                    "content": "Error: file not found: NonExistent.kt",
                    "is_error": True,
                }
            ],
        },
    ]

    base = {"model": model, "max_tokens": 64, "temperature": 0, "tools": NATIVE_TOOLS}

    for label, msgs in [
        ("anthropic_tool_use_block", format_a_msgs),
        ("mixed_oai_calls_anthropic_result", format_b_msgs),
        ("anthropic_tool_result_with_error", format_c_msgs),
    ]:
        try:
            r = client.post_chat({**base, "messages": msgs}, stream=False)
            entry: dict[str, Any] = {"status": r.status_code}
            if r.status_code == 200:
                try:
                    resp = r.json()
                    msg = (resp.get("choices") or [{}])[0].get("message", {})
                    entry["content"] = (msg.get("content") or "")[:200]
                    entry["has_tool_calls"] = bool(msg.get("tool_calls"))
                    entry["finish_reason"] = (resp.get("choices") or [{}])[0].get("finish_reason")
                    entry["verdict"] = "WORKS"
                except Exception:
                    entry["verdict"] = "PARSE_ERROR"
            else:
                entry["error"] = _short(r.text, 200)
                entry["verdict"] = "REJECTED"
            out[label] = entry
        except requests.RequestException as e:
            out[label] = {"status": 0, "error": str(e), "verdict": "TRANSPORT_ERROR"}

    working = [k for k, v in out.items() if v.get("verdict") == "WORKS"]
    out["_working_formats"] = working
    out["_recommended"] = working[0] if working else None
    return out


def probe_conversation_growth(client: SourcegraphClient, model: str) -> dict:
    """Simulate growing agent conversation to find context limit failure point.
    The agent accumulates messages over many turns; this probe finds:
    - At what turn count or token count does the API start failing?
    - Does it fail gracefully (context_length_exceeded) or with a generic error?
    - Does performance degrade before failure?
    This directly models the agent's timeout problem: longer context = slower response."""
    out: dict[str, Any] = {"turns": []}
    conversation: list[dict] = []
    filler = "This is a file content: " + ("x" * 400)  # ~400 tokens of filler per turn

    for turn in range(1, 11):  # up to 10 turns
        # Grow context by adding a large assistant+user pair each round
        if turn > 1:
            conversation.append({"role": "assistant",
                                  "content": f"Turn {turn - 1} result: {filler}"})
            conversation.append({"role": "user",
                                  "content": f"Continue investigation. Turn {turn}."})
        else:
            conversation.append({"role": "user",
                                  "content": "Start the investigation. Turn 1."})

        body = {
            "model": model, "max_tokens": 32, "temperature": 0,
            "messages": conversation,
        }
        # Rough token estimate
        total_chars = sum(len(m.get("content") or "") for m in conversation)
        est_tokens = total_chars // 4

        rec: dict[str, Any] = {"turn": turn, "messages": len(conversation),
                                "est_tokens": est_tokens}
        try:
            start = time.monotonic()
            r = client.post_chat(body, stream=False)
            rec["status"] = r.status_code
            rec["latency_ms"] = round((time.monotonic() - start) * 1000)
            if r.status_code == 200:
                try:
                    resp = r.json()
                    usage = resp.get("usage") or {}
                    rec["prompt_tokens"] = usage.get("prompt_tokens")
                    rec["verdict"] = "OK"
                    # Add the real assistant response to conversation for next turn
                    msg = (resp.get("choices") or [{}])[0].get("message", {})
                    conversation.append({"role": "assistant",
                                          "content": msg.get("content") or "ok"})
                except Exception:
                    rec["verdict"] = "PARSE_ERROR"
            else:
                rec["verdict"] = "HTTP_ERROR"
                rec["error"] = _short(r.text, 300)
                out["turns"].append(rec)
                break  # Stop on failure
        except requests.RequestException as e:
            rec["verdict"] = "TRANSPORT_ERROR"
            rec["error"] = str(e)
            out["turns"].append(rec)
            break

        out["turns"].append(rec)
        if rec.get("verdict") != "OK":
            break

    ok_turns = [t for t in out["turns"] if t.get("verdict") == "OK"]
    latencies = [t["latency_ms"] for t in ok_turns if "latency_ms" in t]
    out["max_successful_turn"] = max((t["turn"] for t in ok_turns), default=0)
    out["latency_trend"] = latencies  # increasing latency = context growth effect
    out["verdict"] = (
        f"SURVIVED_{out['max_successful_turn']}_TURNS" if out["max_successful_turn"] >= 10
        else f"FAILED_AT_TURN_{out['max_successful_turn'] + 1}"
    )
    return out


def probe_cline_message_format(client: SourcegraphClient, model: str) -> dict:
    """Test Cline's specific message format conventions that differ from OpenAI spec:
    1. System prompt as XML-wrapped user message (Sourcegraph rejects system role)
    2. Tool results as 'TOOL RESULT: ...' prefixed user messages
    3. Consecutive same-role message merging
    4. Empty assistant message placeholder (zero-width space)
    These are the exact transformations SourcegraphChatClient.sanitizeMessages() applies."""
    out: dict[str, Any] = {}
    base = {"model": model, "max_tokens": 64, "temperature": 0}

    # Test 1: System prompt as XML user message (Cline + our sanitizeMessages pattern)
    try:
        r = client.post_chat({**base, "messages": [
            {"role": "user", "content":
             "<system_instructions>\nYou are a terse assistant. Reply with one word.\n"
             "</system_instructions>\n\nHello."},
        ]}, stream=False)
        entry: dict = {"status": r.status_code}
        if r.status_code == 200:
            resp = r.json()
            msg = (resp.get("choices") or [{}])[0].get("message", {})
            entry["content"] = (msg.get("content") or "")[:100]
            entry["verdict"] = "WORKS"
        else:
            entry["verdict"] = "REJECTED"
            entry["error"] = _short(r.text, 150)
        out["system_as_xml_user_msg"] = entry
    except requests.RequestException as e:
        out["system_as_xml_user_msg"] = {"verdict": "TRANSPORT_ERROR", "error": str(e)}

    # Test 2: Tool result as 'TOOL RESULT:' prefixed user message
    try:
        r = client.post_chat({**base, "tools": NATIVE_TOOLS, "messages": [
            {"role": "user", "content": "Read README.md"},
            {"role": "assistant", "content": None,
             "tool_calls": [{"id": "call_1", "type": "function",
                              "function": {"name": "read_file",
                                           "arguments": '{"path":"README.md"}'}}]},
            {"role": "user",
             "content": "TOOL RESULT [read_file(path='README.md')]:\nFILE CONTENTS HERE\n\nSummarize."},
        ]}, stream=False)
        entry = {"status": r.status_code}
        if r.status_code == 200:
            resp = r.json()
            msg = (resp.get("choices") or [{}])[0].get("message", {})
            entry["content"] = (msg.get("content") or "")[:150]
            entry["verdict"] = "WORKS"
        else:
            entry["verdict"] = "REJECTED"
            entry["error"] = _short(r.text, 150)
        out["tool_result_as_user_prefix"] = entry
    except requests.RequestException as e:
        out["tool_result_as_user_prefix"] = {"verdict": "TRANSPORT_ERROR", "error": str(e)}

    # Test 3: Zero-width space placeholder for empty assistant content with tool calls
    try:
        r = client.post_chat({**base, "tools": NATIVE_TOOLS, "messages": [
            {"role": "user", "content": "Read README.md"},
            {"role": "assistant", "content": "\u200b",  # zero-width space
             "tool_calls": [{"id": "call_1", "type": "function",
                              "function": {"name": "read_file",
                                           "arguments": '{"path":"README.md"}'}}]},
            {"role": "user", "content": "TOOL RESULT [read_file]:\nFILE CONTENTS\n\nSummarize."},
        ]}, stream=False)
        entry = {"status": r.status_code}
        if r.status_code == 200:
            resp = r.json()
            msg = (resp.get("choices") or [{}])[0].get("message", {})
            entry["content"] = (msg.get("content") or "")[:150]
            entry["verdict"] = "WORKS"
        else:
            entry["verdict"] = "REJECTED"
            entry["error"] = _short(r.text, 150)
        out["zero_width_space_placeholder"] = entry
    except requests.RequestException as e:
        out["zero_width_space_placeholder"] = {"verdict": "TRANSPORT_ERROR", "error": str(e)}

    # Test 4: Consecutive user messages (merged by sanitizer into one)
    try:
        r = client.post_chat({**base, "messages": [
            {"role": "user", "content": "Part 1 of the question."},
            {"role": "user", "content": "Part 2 of the question. What is 2+2?"},
        ]}, stream=False)
        entry = {"status": r.status_code}
        if r.status_code == 200:
            resp = r.json()
            msg = (resp.get("choices") or [{}])[0].get("message", {})
            entry["content"] = (msg.get("content") or "")[:100]
            entry["verdict"] = "WORKS"
        else:
            entry["verdict"] = "REJECTED"
            entry["error"] = _short(r.text, 150)
        out["consecutive_user_messages"] = entry
    except requests.RequestException as e:
        out["consecutive_user_messages"] = {"verdict": "TRANSPORT_ERROR", "error": str(e)}

    working = [k for k, v in out.items() if v.get("verdict") == "WORKS"]
    out["_working_patterns"] = working
    return out


def probe_stream_options_include_usage(client: SourcegraphClient, model: str,
                                       runs: int = 3) -> dict:
    """Run streaming N times WITH include_usage=true and check if usage chunk
    always arrives. The agent depends on usage for token tracking — if it's
    missing some of the time, token budgets become inaccurate."""
    out: dict[str, Any] = {"runs": []}
    body = {
        "model": model, "max_tokens": 32, "temperature": 0, "stream": True,
        "stream_options": {"include_usage": True},
        "messages": [{"role": "user", "content": "Reply with one word."}],
    }
    for i in range(runs):
        rec: dict[str, Any] = {"run": i + 1}
        try:
            r = client.post_chat(body, stream=True)
            rec["status"] = r.status_code
            if r.status_code != 200:
                rec["verdict"] = "HTTP_ERROR"
                out["runs"].append(rec)
                continue
            saw_usage = False
            usage_obj: dict | None = None
            chunks = 0
            for line in iter_sse_lines(r):
                payload = extract_sse_data(line)
                if not payload:
                    continue
                chunks += 1
                try:
                    ch = json.loads(payload)
                    if ch.get("usage"):
                        saw_usage = True
                        usage_obj = ch["usage"]
                except Exception:
                    pass
            rec["saw_usage"] = saw_usage
            rec["usage"] = usage_obj
            rec["chunks"] = chunks
            rec["verdict"] = "USAGE_PRESENT" if saw_usage else "USAGE_MISSING"
        except requests.RequestException as e:
            rec["verdict"] = "TRANSPORT_ERROR"
            rec["error"] = str(e)
        out["runs"].append(rec)

    present = sum(1 for r in out["runs"] if r.get("verdict") == "USAGE_PRESENT")
    out["usage_present_count"] = present
    out["usage_always_present"] = present == runs
    out["verdict"] = "RELIABLE" if present == runs else f"UNRELIABLE ({present}/{runs})"
    return out


def probe_parallel_tool_call_index_assembly(client: SourcegraphClient, model: str) -> dict:
    """Deep probe for parallel tool call streaming: sends requests for 2, 3, 4, 5
    parallel tool calls and checks delta index assembly for each count.
    The agent had a known bug where parallel tool deltas arrive out-of-order or
    with duplicate indices — this probe finds the breaking point."""
    out: dict[str, Any] = {}
    file_sets = {
        2: ["src/A.kt", "src/B.kt"],
        3: ["src/A.kt", "src/B.kt", "src/C.kt"],
        4: ["src/A.kt", "src/B.kt", "src/C.kt", "src/D.kt"],
        5: ["src/A.kt", "src/B.kt", "src/C.kt", "src/D.kt", "src/E.kt"],
    }
    for count, files in file_sets.items():
        files_list = "\n".join(f"{i+1}. read_file for {f}" for i, f in enumerate(files))
        prompt = (
            f"In ONE response, call ALL {count} read_file tools in parallel:\n"
            f"{files_list}\nAll in the SAME response. No commentary."
        )
        body = {
            "model": model, "max_tokens": 128, "temperature": 0,
            "stream": True, "stream_options": {"include_usage": True},
            "tools": NATIVE_TOOLS,
            "messages": [{"role": "user", "content": prompt}],
        }
        rec: dict[str, Any] = {}
        try:
            r = client.post_chat(body, stream=True)
            rec["status"] = r.status_code
            if r.status_code != 200:
                rec["verdict"] = "HTTP_ERROR"
                rec["error"] = _short(r.text, 200)
                out[f"parallel_{count}"] = rec
                continue

            # Accumulate tool call deltas by index (mirroring SourcegraphChatClient logic)
            builders: dict[int, dict] = {}
            finish_reason = None
            raw_indices_seen: list[int] = []

            for line in iter_sse_lines(r):
                payload = extract_sse_data(line)
                if not payload:
                    continue
                try:
                    ch = json.loads(payload)
                    for choice in ch.get("choices", []):
                        fr = choice.get("finish_reason")
                        if fr:
                            finish_reason = fr
                        delta = choice.get("delta") or {}
                        for tc in (delta.get("tool_calls") or []):
                            idx = tc.get("index", 0)
                            raw_indices_seen.append(idx)
                            if idx not in builders:
                                builders[idx] = {"id": "", "name": "", "args": ""}
                            if tc.get("id"):
                                builders[idx]["id"] = tc["id"]
                            fn = tc.get("function") or {}
                            if fn.get("name"):
                                builders[idx]["name"] = fn["name"]
                            if fn.get("arguments"):
                                builders[idx]["args"] += fn["arguments"]
                except json.JSONDecodeError:
                    pass

            # Validate assembled tool calls
            assembled = sorted(builders.items())
            rec["expected_count"] = count
            rec["assembled_count"] = len(assembled)
            rec["finish_reason"] = finish_reason
            rec["unique_indices"] = sorted(set(raw_indices_seen))
            rec["tool_calls"] = [
                {"idx": idx, "name": b["name"], "args_len": len(b["args"]),
                 "args_valid_json": _is_valid_json(b["args"])}
                for idx, b in assembled
            ]
            # Check for concatenated JSON bug (known Sourcegraph issue)
            concat_bug = any("}{" in b["args"] for _, b in assembled)
            rec["concat_json_bug_detected"] = concat_bug
            rec["verdict"] = (
                "PASS" if (len(assembled) == count and not concat_bug and
                           all(t["args_valid_json"] for t in rec["tool_calls"]))
                else f"FAIL (assembled={len(assembled)}/{count}, concat_bug={concat_bug})"
            )
        except requests.RequestException as e:
            rec["verdict"] = "TRANSPORT_ERROR"
            rec["error"] = str(e)
        out[f"parallel_{count}"] = rec

    return out


def _is_valid_json(s: str) -> bool:
    if not s:
        return False
    try:
        json.loads(s)
        return True
    except json.JSONDecodeError:
        return False


# ─────────────────────────────────────────────────────────────
# Reporting
# ─────────────────────────────────────────────────────────────

def print_outcome(o: RunOutcome) -> None:
    head_status = "PASS" if o.all_pass else "FAIL"
    print()
    print("-" * 78)
    print(f"[{head_status}] scenario={o.scenario}  mode={o.mode}  ({o.passed_count} pass / {o.failed_count} fail)")
    if o.scenario_description:
        print(f"        what: {o.scenario_description}")
    print(f"        how:  {o.description}")
    if o.transport_error:
        print(f"        TRANSPORT ERROR: {o.transport_error}")
    print(f"        ttfb={o.time_to_first_byte_ms:.0f}ms  total={o.total_time_ms:.0f}ms  "
          f"chunks={o.sse_chunks}  finish={o.finish_reason}  "
          f"done={o.saw_done_sentinel}  usage={'yes' if o.saw_usage_chunk else 'no'}")
    # Streaming health metrics
    if o.content_length_chars > 0:
        print(f"        stream: content={o.content_length_chars}chars  "
              f"throughput={o.throughput_chars_per_sec:.0f}c/s  "
              f"max_gap={o.max_inter_chunk_gap_ms:.0f}ms  "
              f"p95_gap={o.p95_inter_chunk_gap_ms:.0f}ms")
        # Warn about dangerous gaps
        if o.max_inter_chunk_gap_ms > 30_000:
            print(f"        *** DANGER: max inter-chunk gap {o.max_inter_chunk_gap_ms:.0f}ms "
                  f"— approaches OkHttp read timeout (120s)")
        elif o.max_inter_chunk_gap_ms > 10_000:
            print(f"        *** WARNING: max inter-chunk gap {o.max_inter_chunk_gap_ms:.0f}ms "
                  f"— monitor under load")
    if o.usage:
        usage = o.usage
        print(f"        usage: prompt={usage.get('prompt_tokens')}  "
              f"completion={usage.get('completion_tokens')}  "
              f"total={usage.get('total_tokens')}  credits={usage.get('credits')}")
    if o.cache_read_tokens is not None or o.cache_creation_tokens is not None:
        print(f"        cache: read={o.cache_read_tokens} create={o.cache_creation_tokens}")
    print(f"        tool_calls ({len(o.tool_calls)}):")
    for i, tc in enumerate(o.tool_calls):
        args_preview = json.dumps(tc.args)[:120]
        print(f"          [{i}] {tc.name}  args={args_preview}")
    if o.final_content.strip():
        # Show more content for large responses
        preview_len = 400 if o.content_length_chars > 2000 else 200
        preview = o.final_content.strip().replace("\n", "\\n")[:preview_len]
        line_count = o.final_content.count("\n")
        print(f"        content ({o.content_length_chars}chars, {line_count}lines): {preview}")
    if o.parse_warnings:
        print(f"        parse warnings ({len(o.parse_warnings)}):")
        for w in o.parse_warnings:
            print(f"          - {w}")
    print(f"        assertions:")
    for a in o.assertions:
        marker = "PASS" if a.passed else "FAIL"
        print(f"          {marker} {a.name:<32}  {a.reason}")
    print(f"        raw SSE: {o.raw_sse_path}")


def print_matrix(outcomes: list[RunOutcome], scenarios: list[Scenario], modes: list[str]) -> None:
    print()
    print("=" * 78)
    print("ASSERTION MATRIX  (P=all pass, F=any fail, X=transport error, -=skipped)")
    print("=" * 78)
    by_key = {(o.scenario, o.mode): o for o in outcomes}
    header = f"  {'scenario':<24}" + "".join(f"  Mode {m}" for m in modes)
    print(header)
    for s in scenarios:
        row = f"  {s.name:<24}"
        for m in modes:
            o = by_key.get((s.name, m))
            if o is None:
                row += "       -"
                continue
            if o.transport_error:
                cell = "X"
            elif o.all_pass:
                cell = f"P {o.passed_count}/{o.passed_count}"
            else:
                cell = f"F {o.passed_count}/{o.passed_count + o.failed_count}"
            row += f"  {cell:>6}"
        print(row)
    print()


def print_grand_totals(outcomes: list[RunOutcome]) -> None:
    total_runs = len(outcomes)
    runs_fully_passed = sum(1 for o in outcomes if o.all_pass)
    total_assertions = sum(len(o.assertions) for o in outcomes)
    passed_assertions = sum(o.passed_count for o in outcomes)
    print("=" * 78)
    print("GRAND TOTALS")
    print("=" * 78)
    print(f"  runs:         {runs_fully_passed}/{total_runs} fully passed")
    print(f"  assertions:   {passed_assertions}/{total_assertions} passed")
    failed = [o for o in outcomes if not o.all_pass]
    if failed:
        print(f"  failures:")
        for o in failed:
            failed_names = [a.name for a in o.assertions if not a.passed]
            err = f" (transport: {o.transport_error})" if o.transport_error else ""
            print(f"    - {o.scenario}/{o.mode}: {','.join(failed_names) or 'transport'}{err}")


# ─────────────────────────────────────────────────────────────
# Entry
# ─────────────────────────────────────────────────────────────

def analyze_results(results_path: Path) -> int:
    """Post-run deep analysis of streaming_lab_results.json.
    Produces a structured report covering:
      - Streaming health (gaps, throughput, DONE sentinel reliability)
      - What the gateway supports vs silently ignores vs rejects
      - Tool call reliability by parallel count
      - Agent-specific recommendations
    """
    if not results_path.exists():
        print(f"ERROR: {results_path} not found. Run the lab first.")
        return 2

    with results_path.open(encoding="utf-8") as f:
        data = json.load(f)

    model = data.get("model", "unknown")
    url = data.get("url", "unknown")
    outcomes = data.get("outcomes", [])
    probes = data.get("probes", {})
    advanced = data.get("advanced", {})

    print()
    print("=" * 78)
    print("DEEP ANALYSIS REPORT")
    print("=" * 78)
    print(f"  model: {model}")
    print(f"  url:   {url}")   # already masked when saved
    print(f"  outcomes: {len(outcomes)}")

    # ── 1. Streaming health ─────────────────────────────────────────────────
    print()
    print("=" * 78)
    print("1. STREAMING HEALTH")
    print("=" * 78)

    streaming_outcomes = [o for o in outcomes if o.get("content_length_chars", 0) > 0]
    if not streaming_outcomes:
        print("  No streaming outcomes with content data found.")
    else:
        max_gaps = [o.get("max_inter_chunk_gap_ms", 0) for o in streaming_outcomes]
        p95_gaps = [o.get("p95_inter_chunk_gap_ms", 0) for o in streaming_outcomes]
        throughputs = [o.get("throughput_chars_per_sec", 0) for o in streaming_outcomes
                       if o.get("throughput_chars_per_sec", 0) > 0]
        content_sizes = [o.get("content_length_chars", 0) for o in streaming_outcomes]
        done_pct = 100 * sum(1 for o in streaming_outcomes if o.get("saw_done_sentinel")) / len(streaming_outcomes)

        print(f"  Runs with content:      {len(streaming_outcomes)}")
        print(f"  [DONE] sentinel:        {done_pct:.0f}% of runs received it")
        print(f"  Max inter-chunk gap:    max={max(max_gaps):.0f}ms  avg={sum(max_gaps)/len(max_gaps):.0f}ms")
        print(f"  P95 inter-chunk gap:    max={max(p95_gaps):.0f}ms  avg={sum(p95_gaps)/len(p95_gaps):.0f}ms")
        if throughputs:
            print(f"  Throughput (chars/s):   min={min(throughputs):.0f}  max={max(throughputs):.0f}  avg={sum(throughputs)/len(throughputs):.0f}")
        print(f"  Content size range:     min={min(content_sizes)}  max={max(content_sizes)}chars")

        # Timeout risk assessment
        danger = [o for o in streaming_outcomes if o.get("max_inter_chunk_gap_ms", 0) > 30_000]
        warn = [o for o in streaming_outcomes if 10_000 < o.get("max_inter_chunk_gap_ms", 0) <= 30_000]
        print()
        print(f"  Timeout risk:")
        if danger:
            print(f"    CRITICAL ({len(danger)} runs had gap >30s): "
                  f"{[o['scenario']+'/'+o['mode'] for o in danger]}")
        elif warn:
            print(f"    WARNING ({len(warn)} runs had gap 10-30s): "
                  f"{[o['scenario']+'/'+o['mode'] for o in warn]}")
        else:
            print(f"    LOW (no gaps >10s detected)")

        # Large code streaming — did the big scenarios complete?
        big_scenarios = ["streaming_code_300_lines", "streaming_code_500_lines"]
        for sname in big_scenarios:
            matches = [o for o in streaming_outcomes if o["scenario"] == sname]
            if matches:
                o = matches[0]
                truncated = o.get("finish_reason") == "length"
                print(f"  {sname}: {o.get('content_length_chars')}chars  "
                      f"finish={o.get('finish_reason')}  "
                      f"{'TRUNCATED' if truncated else 'COMPLETE'}  "
                      f"max_gap={o.get('max_inter_chunk_gap_ms', 0):.0f}ms")

    # Streaming reliability probe
    sr = probes.get("streaming_reliability", {})
    if sr:
        print()
        print(f"  Reliability probe ({sr.get('ok_count', '?')}/5 runs OK): {sr.get('overall_verdict')}")
        print(f"    TTFB range: {sr.get('ttfb_min_ms')}ms – {sr.get('ttfb_max_ms')}ms")
        print(f"    Total range: {sr.get('total_min_ms')}ms – {sr.get('total_max_ms')}ms")
        print(f"    Usage always present: {sr.get('usage_always_present')}")

    # Streaming vs non-streaming latency
    svn = probes.get("streaming_vs_nonstreaming", {})
    if svn:
        print()
        print(f"  Streaming vs non-streaming:")
        print(f"    Non-streaming total:  {svn.get('non_streaming_total_ms')}ms")
        print(f"    Streaming TTFB:       {svn.get('streaming_ttfb_ms')}ms")
        print(f"    TTFB/total ratio:     {svn.get('ttfb_to_total_ratio')}")
        verdict = svn.get('verdict', '')
        if verdict == "STREAMING_PREVENTS_TIMEOUTS":
            print(f"    => STREAMING_PREVENTS_TIMEOUTS: enabling streaming would fix agent timeouts")
        elif verdict == "STREAMING_MARGINAL_BENEFIT":
            print(f"    => STREAMING_MARGINAL_BENEFIT: partial improvement expected")
        else:
            print(f"    => {verdict}")

    # ── 2. Gateway capabilities ────────────────────────────────────────────
    print()
    print("=" * 78)
    print("2. GATEWAY CAPABILITIES — WHAT WORKS / WHAT DOESN'T")
    print("=" * 78)

    supported = []
    silently_ignored = []
    rejected = []

    # tool_choice
    tc_probes = probes.get("tool_choice_variants", {})
    for label, rec in tc_probes.items():
        v = rec.get("verdict", "")
        if v == "HONORED":
            supported.append(f"tool_choice[{label}]")
        elif v == "SILENTLY_IGNORED":
            silently_ignored.append(f"tool_choice[{label}] (HTTP 200 but directive ignored)")
        elif v == "REJECTED":
            rejected.append(f"tool_choice[{label}]")

    # system role
    sys_r = probes.get("system_role", {})
    if sys_r.get("status") == 400:
        rejected.append("system role in messages array (HTTP 400)")
    else:
        supported.append("system role in messages array")

    # tool role
    tool_r = probes.get("tool_role", {})
    if tool_r.get("status") == 400:
        rejected.append("role:tool messages (HTTP 400) — multi-turn tool use broken with OpenAI format")
    else:
        supported.append("role:tool messages")

    # response_format
    rf = probes.get("response_format_json", {})
    if rf.get("status") == 400:
        rejected.append("response_format=json_object")
    else:
        supported.append("response_format=json_object")

    # cache_control
    cache_usage = ((probes.get("cache_control") or {}).get("usage") or {})
    cached_tok = (cache_usage.get("prompt_tokens_details") or {}).get("cached_tokens", None)
    if cached_tok == 0:
        silently_ignored.append("cache_control:ephemeral (accepted but cached_tokens always 0)")
    elif cached_tok:
        supported.append("cache_control:ephemeral (cache hits observed)")

    # include_usage
    iur = probes.get("include_usage_reliability", {})
    if iur.get("usage_always_present"):
        supported.append(f"stream_options.include_usage=true (reliable {iur.get('usage_present_count')}/3)")
    elif iur:
        silently_ignored.append(f"stream_options.include_usage (only {iur.get('usage_present_count')}/3 runs had usage)")

    # max_tokens
    mt = probes.get("max_tokens", {})
    mt_works = [k for k, v in mt.items() if v.get("status") == 200]
    if mt_works:
        supported.append(f"max_tokens up to {max(mt_works)} (no hard cap)")

    # Anthropic content blocks
    atb = probes.get("anthropic_tool_result_blocks", {})
    if atb.get("_working_formats"):
        supported.append(f"Anthropic tool_result content blocks: {atb['_working_formats']}")
    elif atb:
        rejected.append("Anthropic tool_result content blocks (all formats rejected)")

    # Cline message format
    cmf = probes.get("cline_message_format", {})
    for label, rec in cmf.items():
        if label.startswith("_"):
            continue
        if rec.get("verdict") == "WORKS":
            supported.append(f"cline_msg.{label}")
        elif rec.get("verdict") == "REJECTED":
            rejected.append(f"cline_msg.{label}")

    # Vision
    adv_vis = advanced.get("vision", {})
    if adv_vis.get("status") == 200:
        preview = adv_vis.get("preview", "")
        if any(p in preview.lower() for p in ("don't see", "no image", "cannot see")):
            silently_ignored.append("vision/image input (HTTP 200 but images stripped by gateway)")
        else:
            supported.append("vision/image input")

    print()
    print(f"  SUPPORTED ({len(supported)}):")
    for item in supported:
        print(f"    [OK]  {item}")
    print()
    print(f"  SILENTLY IGNORED ({len(silently_ignored)}):")
    for item in silently_ignored:
        print(f"    [~~]  {item}")
    print()
    print(f"  REJECTED / NOT SUPPORTED ({len(rejected)}):")
    for item in rejected:
        print(f"    [NO]  {item}")

    # ── 3. Tool call reliability ───────────────────────────────────────────
    print()
    print("=" * 78)
    print("3. TOOL CALL RELIABILITY")
    print("=" * 78)

    tool_outcomes = [o for o in outcomes if o.get("mode") == "A"]
    tool_pass = [o for o in tool_outcomes if not o.get("transport_error") and o.get("http_status") == 200]

    # Group by scenario
    tool_scenarios = {}
    for o in tool_pass:
        sname = o["scenario"]
        tool_scenarios.setdefault(sname, []).append(o)

    for sname, runs in sorted(tool_scenarios.items()):
        r = runs[0]
        n_tools = len(r.get("tool_calls", []))
        assertions = r.get("assertions", [])
        pass_count = sum(1 for a in assertions if a["passed"])
        total = len(assertions)
        warn_concat = any("concatenated" in w for w in r.get("parse_warnings", []))
        print(f"  {sname:<35} tool_calls={n_tools}  assertions={pass_count}/{total}  "
              f"concat_bug={'YES' if warn_concat else 'no'}")

    # Parallel assembly probe
    pia = probes.get("parallel_index_assembly", {})
    if pia:
        print()
        print("  Parallel tool call assembly (streaming deltas):")
        for key in ("parallel_2", "parallel_3", "parallel_4", "parallel_5"):
            rec = pia.get(key, {})
            if rec:
                print(f"    {key}: {rec.get('verdict')}  assembled={rec.get('assembled_count')}/{rec.get('expected_count')}  "
                      f"concat_bug={rec.get('concat_json_bug_detected')}  "
                      f"all_args_valid={all(t.get('args_valid_json') for t in rec.get('tool_calls', []))}")

    # Multi-turn
    mtu = probes.get("multi_turn_tool_use", {})
    if mtu:
        print()
        print(f"  Multi-turn tool use: {mtu.get('verdict')}")
        if mtu.get("step2a_verdict"):
            print(f"    OpenAI tool role:    {mtu.get('step2a_verdict')}")
        if mtu.get("step2b_verdict"):
            print(f"    User-msg fallback:   {mtu.get('step2b_verdict')}")

    trf = probes.get("tool_result_formats", {})
    if trf:
        print()
        print(f"  Tool result formats tested:")
        for fmt in ("A_tool_role_null_content", "B_tool_role_empty_content",
                    "C_user_msg_carries_result", "D_pure_conversational",
                    "E_tool_role_no_call_id", "F_function_role"):
            rec = trf.get(fmt, {})
            if rec:
                print(f"    {fmt:<30}: {rec.get('verdict')}")
        print(f"  Working formats: {trf.get('_working_formats')}")
        print(f"  Recommended:     {trf.get('_recommended')}")

    # ── 4. Context growth ─────────────────────────────────────────────────
    cg = probes.get("conversation_growth", {})
    if cg:
        print()
        print("=" * 78)
        print("4. CONTEXT GROWTH / CONVERSATION SIZE LIMITS")
        print("=" * 78)
        print(f"  Verdict: {cg.get('verdict')}")
        turns = cg.get("turns", [])
        if turns:
            latencies = [t.get("latency_ms", 0) for t in turns if t.get("verdict") == "OK"]
            if len(latencies) >= 2:
                trend = "INCREASING" if latencies[-1] > latencies[0] * 1.5 else "STABLE"
                print(f"  Latency trend: {trend}  ({latencies[0]}ms -> {latencies[-1]}ms over {len(latencies)} turns)")
            for t in turns:
                status = "OK" if t.get("verdict") == "OK" else f"FAILED ({t.get('verdict')})"
                print(f"    turn {t['turn']:>2}: msgs={t['messages']:>2}  "
                      f"prompt_tokens={t.get('prompt_tokens', '?'):>6}  "
                      f"latency={t.get('latency_ms', '?'):>5}ms  {status}")

    # ── 5. Recommendations for the agent ──────────────────────────────────
    print()
    print("=" * 78)
    print("5. RECOMMENDATIONS FOR THE AGENT")
    print("=" * 78)

    recs = []

    # Streaming re-enable recommendation
    svn_verdict = (probes.get("streaming_vs_nonstreaming") or {}).get("verdict", "")
    sr_verdict = (probes.get("streaming_reliability") or {}).get("overall_verdict", "")
    if "PREVENTS_TIMEOUTS" in svn_verdict and "STABLE" in sr_verdict:
        recs.append(("HIGH", "Re-enable real streaming in OpenAiCompatBrain.chatStream()",
                     "TTFB is low and streaming is stable. Non-streaming causes timeouts on large context."))
    elif "FLAKY" in sr_verdict:
        recs.append(("MEDIUM", "Keep streaming disabled until reliability improves",
                     f"Streaming reliability: {sr_verdict}"))

    # Tool result format
    trf_rec = (probes.get("tool_result_formats") or {}).get("_recommended")
    tool_role_rejected = (probes.get("tool_role") or {}).get("status") == 400
    atb_working = (probes.get("anthropic_tool_result_blocks") or {}).get("_working_formats")
    if tool_role_rejected:
        if atb_working:
            recs.append(("HIGH", f"Use Anthropic content-block format for tool results: {atb_working[0]}",
                         "role:tool rejected (HTTP 400). Anthropic format works."))
        elif trf_rec:
            recs.append(("HIGH", f"Use format '{trf_rec}' for tool results in sanitizeMessages()",
                         "role:tool rejected (HTTP 400). This alternative format works."))
        else:
            recs.append(("CRITICAL", "No working tool result format found — multi-turn tool use is broken",
                         "All tested formats failed. Agent cannot complete tool call round-trips."))

    # System prompt
    if (probes.get("system_role") or {}).get("status") == 400:
        cline_sys = (probes.get("cline_message_format") or {}).get("system_as_xml_user_msg", {})
        if cline_sys.get("verdict") == "WORKS":
            recs.append(("MEDIUM", "System prompt as XML user message is confirmed working",
                         "Already implemented in sanitizeMessages() — no change needed."))

    # tool_choice
    tc_ignored = [k for k, v in (probes.get("tool_choice_variants") or {}).items()
                  if v.get("verdict") == "SILENTLY_IGNORED"]
    if tc_ignored:
        recs.append(("MEDIUM", "Do not send tool_choice in requests",
                     f"Gateway silently ignores it for: {tc_ignored}. "
                     f"Use prompt instructions to guide tool use instead."))

    # Parallel tool calls
    pia = probes.get("parallel_index_assembly", {})
    safe_parallel = 0
    for count in (5, 4, 3, 2):
        rec = pia.get(f"parallel_{count}", {})
        if rec.get("verdict") == "PASS":
            safe_parallel = count
            break
    if safe_parallel:
        recs.append(("INFO", f"Parallel tool calls safe up to {safe_parallel} concurrent",
                     "Higher counts may hit concatenated-JSON bug."))

    # Usage reliability
    iur_verdict = (probes.get("include_usage_reliability") or {}).get("verdict", "")
    if "UNRELIABLE" in iur_verdict:
        recs.append(("MEDIUM", "Add token estimation fallback when usage chunk is absent",
                     f"include_usage unreliable: {iur_verdict}"))

    for priority, title, detail in recs:
        print(f"  [{priority}] {title}")
        print(f"          {detail}")

    print()
    print("  Files for further investigation:")
    print("    streaming_lab_results.json  — all structured data")
    print("    raw_sse/<scenario>_<mode>.txt — per-chunk SSE bytes with timestamps")
    print("    (look for large gap lines in raw_sse to find where stream stalled)")

    return 0


def main() -> int:
    p = argparse.ArgumentParser(description="Sourcegraph streaming + tool parsing lab")
    p.add_argument("--url", required=False, help="Sourcegraph base URL (no trailing slash)")
    p.add_argument("--token", required=False, help="Sourcegraph access token (sgp_...)")
    p.add_argument("--model", default=None, help="Model id; auto-picks first Anthropic if omitted")
    p.add_argument("--only", choices=["A", "B", "C"], action="append",
                   help="Run only this mode (repeatable: --only A --only B)")
    p.add_argument("--scenario", action="append", help="Run only named scenarios (repeatable)")
    p.add_argument("--list", action="store_true", help="List scenarios and exit")
    p.add_argument("--raw", action="store_true", help="Dump raw SSE lines live to stdout")
    p.add_argument("--verbose-streaming", action="store_true",
                   help="Print per-chunk timing metrics inline during scenario runs")
    p.add_argument("--no-verify", action="store_true", help="Disable TLS verify")
    p.add_argument("--no-probes", action="store_true", help="Skip side probes")
    p.add_argument("--no-advanced", action="store_true", help="Skip advanced probes (thinking, metadata, vision, headers)")
    p.add_argument("--thinking-model", default=None,
                   help="Model id to use for thinking probes (auto-detect if omitted)")
    p.add_argument("--out", default="streaming_lab_results.json", help="JSON results file")
    p.add_argument("--raw-dir", default="raw_sse", help="Directory for per-test SSE dumps")
    p.add_argument("--analyze", metavar="RESULTS_JSON", default=None,
                   help="Skip running — analyze an existing results JSON file and exit")
    args = p.parse_args()

    # Analysis-only mode: read existing results and produce report
    if args.analyze:
        return analyze_results(Path(args.analyze))

    if args.list:
        print("Available scenarios:")
        for s in SCENARIOS:
            print(f"  {s.name:<24} {s.description}")
        return 0

    if not args.url or not args.token:
        print("ERROR: --url and --token are required (use --list to skip)")
        return 2

    client = SourcegraphClient(args.url, args.token, verify=not args.no_verify)

    if args.model:
        model = args.model
    else:
        try:
            models = client.list_models()
        except Exception as e:
            print(f"ERROR: failed to list models: {e}")
            return 1
        anthropic = [m for m in models if m.startswith("anthropic::")]
        if not anthropic:
            print("ERROR: no anthropic models found and --model not specified")
            print("Available:", models[:10])
            return 1
        model = anthropic[0]

    print(f"# model:    {model}")
    print(f"# url:      {_mask_url(args.url)}")

    modes = args.only or ["A", "B", "C"]
    scenarios = SCENARIOS
    if args.scenario:
        wanted = set(args.scenario)
        scenarios = [s for s in SCENARIOS if s.name in wanted]
        if not scenarios:
            print(f"ERROR: no scenarios matched {sorted(wanted)}. Use --list.")
            return 2

    raw_dir = Path(args.raw_dir)
    print(f"# raw_dir:  {raw_dir}")
    print(f"# scenarios: {[s.name for s in scenarios]}")
    print(f"# modes:    {modes}")

    outcomes: list[RunOutcome] = []
    for s in scenarios:
        print()
        print("#" * 78)
        print(f"# SCENARIO: {s.name} — {s.description}")
        print("#" * 78)
        for m in modes:
            try:
                o = run_scenario(client, s, m, model, raw_dir, args.raw,
                                 verbose_streaming=args.verbose_streaming)
            except Exception as e:
                o = RunOutcome(
                    scenario=s.name, mode=m, description=f"crash: {e}",
                    transport_error=f"unexpected exception: {e}",
                )
            outcomes.append(o)
            print_outcome(o)

    print_matrix(outcomes, scenarios, modes)
    print_grand_totals(outcomes)

    probes: dict[str, Any] = {}
    if not args.no_probes:
        print()
        print("=" * 78)
        print("SIDE PROBES")
        print("=" * 78)
        probes["tool_choice_variants"] = probe_tool_choice_variants(client, model)
        for label, r in probes["tool_choice_variants"].items():
            verdict = r.get("verdict", "?")
            has_tc = r.get("has_tool_calls")
            fin = r.get("finish_reason", "")
            detail = f"verdict={verdict}  has_tool_calls={has_tc}  finish={fin}"
            print(f"  tool_choice[{label:<6}]: HTTP {r.get('status'):<4} {detail}")

        probes["cache_control"] = probe_cache_control(client, model)
        c = probes["cache_control"]
        usage = c.get("usage") or {}
        cached = (usage.get("prompt_tokens_details") or {}).get("cached_tokens", "?")
        verdict_c = "CACHE_SILENTLY_STRIPPED" if cached == 0 else ("CACHE_ACTIVE" if cached else "UNKNOWN")
        print(f"  cache_control:        HTTP {c.get('status'):<4} cached_tokens={cached}  verdict={verdict_c}")

        probes["max_tokens"] = probe_max_tokens(client, model)
        for n, r in probes["max_tokens"].items():
            print(f"  max_tokens[{n:>5}]:    HTTP {r.get('status'):<4} {r.get('preview', '')[:80]}")

        probes["response_format_json"] = probe_response_format_json(client, model)
        r = probes["response_format_json"]
        print(f"  response_format_json: HTTP {r.get('status'):<4} {r.get('preview', '')[:100]}")

        probes["system_role"] = probe_system_role(client, model)
        r = probes["system_role"]
        sys_verdict = "REJECTED_USE_TOP_LEVEL_SYSTEM" if r.get("status") == 400 else "ACCEPTED"
        print(f"  system_role:          HTTP {r.get('status'):<4} verdict={sys_verdict}")

        probes["tool_role"] = probe_tool_role(client, model)
        r = probes["tool_role"]
        tool_role_verdict = "REJECTED_NEED_ALTERNATIVE" if r.get("status") == 400 else "ACCEPTED"
        print(f"  tool_role:            HTTP {r.get('status'):<4} verdict={tool_role_verdict}")

        probes["include_usage_isolation"] = probe_include_usage_isolation(client, model)
        r = probes["include_usage_isolation"]
        flag = r.get("usage_emitted_without_include_usage")
        print(f"  include_usage_isol:   HTTP {r.get('status'):<4} usage_without_flag={flag}")

        print()
        print("  -- Tool result format discovery (CRITICAL for multi-turn agent) --")
        probes["tool_result_formats"] = probe_tool_result_formats(client, model)
        trf = probes["tool_result_formats"]
        for fmt_label in ("A_tool_role_null_content", "B_tool_role_empty_content",
                          "C_user_msg_carries_result", "D_pure_conversational",
                          "E_tool_role_no_call_id", "F_function_role"):
            rec = trf.get(fmt_label, {})
            verdict = rec.get("verdict", "?")
            content = rec.get("content", "")[:60]
            print(f"  {fmt_label:<30}: HTTP {rec.get('status', '?'):<4} verdict={verdict}  content={content!r}")
        print(f"  working_formats: {trf.get('_working_formats')}")
        print(f"  recommended:     {trf.get('_recommended')}")

        print()
        print("  -- Multi-turn tool use round-trip (complete agent loop) --")
        probes["multi_turn_tool_use"] = probe_multi_turn_tool_use(client, model)
        mtu = probes["multi_turn_tool_use"]
        print(f"  step1: HTTP {mtu.get('step1_status')}  tool_calls={mtu.get('step1_tool_calls_count')}  tool={mtu.get('step1_tool_name')}")
        if mtu.get("step2a_tool_role_status"):
            print(f"  step2a (tool_role):    HTTP {mtu.get('step2a_tool_role_status')}  verdict={mtu.get('step2a_verdict')}")
        if mtu.get("step2b_user_msg_status"):
            print(f"  step2b (user_msg):     HTTP {mtu.get('step2b_user_msg_status')}  verdict={mtu.get('step2b_verdict')}")
        print(f"  OVERALL VERDICT: {mtu.get('verdict')}")

        print()
        print("  -- Streaming reliability ({} runs) --".format(5))
        probes["streaming_reliability"] = probe_streaming_reliability(client, model, runs=5)
        sr = probes["streaming_reliability"]
        print(f"  overall: {sr.get('overall_verdict')}  ok={sr.get('ok_count')}/5")
        print(f"  ttfb: min={sr.get('ttfb_min_ms')}ms  max={sr.get('ttfb_max_ms')}ms")
        print(f"  total: min={sr.get('total_min_ms')}ms  max={sr.get('total_max_ms')}ms")
        print(f"  usage_always_present={sr.get('usage_always_present')}  done_always_present={sr.get('done_always_present')}")
        for run in sr.get("runs", []):
            print(f"    run#{run['run']}: {run.get('verdict')}  ttfb={run.get('ttfb_ms')}ms  chunks={run.get('chunks')}")

        print()
        print("  -- Streaming vs non-streaming latency comparison --")
        probes["streaming_vs_nonstreaming"] = probe_streaming_vs_nonstreaming_latency(client, model)
        svn = probes["streaming_vs_nonstreaming"]
        print(f"  non-streaming total:   {svn.get('non_streaming_total_ms')}ms")
        print(f"  streaming TTFB:        {svn.get('streaming_ttfb_ms')}ms")
        print(f"  streaming total:       {svn.get('streaming_total_ms')}ms")
        print(f"  ttfb/total ratio:      {svn.get('ttfb_to_total_ratio')}")
        print(f"  verdict: {svn.get('verdict')}")

        print()
        print("  -- Anthropic content-block tool result format (Cline's format) --")
        probes["anthropic_tool_result_blocks"] = probe_anthropic_tool_result_content_block(client, model)
        atb = probes["anthropic_tool_result_blocks"]
        for label in ("anthropic_tool_use_block", "mixed_oai_calls_anthropic_result",
                      "anthropic_tool_result_with_error"):
            rec = atb.get(label, {})
            print(f"  {label:<42}: HTTP {rec.get('status', '?'):<4} verdict={rec.get('verdict')}  {rec.get('content', rec.get('error', ''))[:60]!r}")
        print(f"  working_formats: {atb.get('_working_formats')}")
        print(f"  recommended:     {atb.get('_recommended')}")

        print()
        print("  -- Conversation growth / context limit test (10 turns) --")
        probes["conversation_growth"] = probe_conversation_growth(client, model)
        cg = probes["conversation_growth"]
        print(f"  verdict: {cg.get('verdict')}")
        for t in cg.get("turns", []):
            print(f"    turn {t['turn']:>2}: msgs={t['messages']:>2}  est_tokens~{t.get('est_tokens','?')}  "
                  f"prompt_tokens={t.get('prompt_tokens','?')}  latency={t.get('latency_ms','?')}ms  {t.get('verdict')}")

        print()
        print("  -- Cline message format patterns (sanitizeMessages compatibility) --")
        probes["cline_message_format"] = probe_cline_message_format(client, model)
        cmf = probes["cline_message_format"]
        for label in ("system_as_xml_user_msg", "tool_result_as_user_prefix",
                      "zero_width_space_placeholder", "consecutive_user_messages"):
            rec = cmf.get(label, {})
            print(f"  {label:<40}: HTTP {rec.get('status', '?'):<4} verdict={rec.get('verdict')}  {rec.get('content', rec.get('error', ''))[:50]!r}")

        print()
        print("  -- include_usage reliability (3 streaming runs) --")
        probes["include_usage_reliability"] = probe_stream_options_include_usage(client, model, runs=3)
        iur = probes["include_usage_reliability"]
        print(f"  verdict: {iur.get('verdict')}  present={iur.get('usage_present_count')}/3")
        for run in iur.get("runs", []):
            print(f"    run#{run['run']}: {run.get('verdict')}  usage={run.get('usage')}")

        print()
        print("  -- Parallel tool call index assembly (2/3/4/5 concurrent) --")
        probes["parallel_index_assembly"] = probe_parallel_tool_call_index_assembly(client, model)
        pia = probes["parallel_index_assembly"]
        for key in ("parallel_2", "parallel_3", "parallel_4", "parallel_5"):
            rec = pia.get(key, {})
            print(f"  {key}: verdict={rec.get('verdict')}  assembled={rec.get('assembled_count')}/{rec.get('expected_count')}  "
                  f"concat_bug={rec.get('concat_json_bug_detected')}  finish={rec.get('finish_reason')}")

    advanced: dict[str, Any] = {}
    if not args.no_probes and not args.no_advanced:
        print()
        print("=" * 78)
        print("ADVANCED PROBES (thinking, metadata, headers, vision, native fields)")
        print("=" * 78)

        # Auto-pick a thinking model if the user didn't specify one
        thinking_model = args.thinking_model
        if thinking_model is None:
            try:
                all_models = client.list_models()
                candidates = [
                    m for m in all_models
                    if any(t in m.lower() for t in ("thinking", "o1", "o3", "reasoner", "deep-think"))
                ]
                if candidates:
                    thinking_model = candidates[0]
            except Exception:
                pass
        print(f"  thinking probe model: {thinking_model or '(none auto-detected — using --model)'}")

        # 1. Thinking strategies
        print()
        print("  -- Thinking / reasoning extraction --")
        advanced["thinking_strategies"] = probe_thinking_strategies(client, model, thinking_model)
        for label, rec in advanced["thinking_strategies"]["strategies"].items():
            status = rec.get("status")
            hits = rec.get("thinking_field_hits") or []
            usage_extras = rec.get("usage_extra_keys") or []
            delta_keys = rec.get("delta_keys_seen") or []
            tchunks = rec.get("thinking_chunks_count") or 0
            line = f"    [{label:<36}] HTTP {status}"
            if rec.get("error_preview"):
                line += f"  ERR: {rec['error_preview'][:120]}"
            elif status == 200:
                thinking_active = bool(hits) or tchunks > 0
                if thinking_active:
                    if hits:
                        line += f"  thinking_fields={[h['path'] for h in hits]}"
                    if tchunks:
                        line += f"  stream_thinking_chunks={tchunks}"
                    line += "  verdict=THINKING_ACTIVE"
                else:
                    line += "  verdict=THINKING_SILENTLY_DROPPED"
                if usage_extras:
                    line += f"  usage_extras={usage_extras}"
                if delta_keys:
                    line += f"  delta_keys={delta_keys}"
            print(line)

        # 2. Model metadata
        print()
        print("  -- Model metadata extraction --")
        advanced["model_metadata"] = probe_model_metadata(client)
        meta = advanced["model_metadata"]
        print(f"    list HTTP {meta.get('list_status')}  models={meta.get('model_count')}")
        keys = meta.get("all_observed_model_keys") or []
        print(f"    keys observed across all models: {keys}")
        cap_hits = meta.get("capability_field_hits") or {}
        if cap_hits:
            print(f"    CAPABILITY-SHAPED FIELDS FOUND:")
            for path, ids in cap_hits.items():
                print(f"      {path}  (in {len(ids)} models)")
        else:
            print(f"    no context_window/max_token/capability fields found in /models")
        print(f"    retrieve HTTP {meta.get('retrieve_status')} for {meta.get('retrieve_first_id')}")
        if meta.get("retrieve_unknown_keys"):
            print(f"    retrieve unknown keys: {meta['retrieve_unknown_keys']}")

        # 3. Response headers
        print()
        print("  -- Response header inspection --")
        advanced["response_headers"] = probe_response_headers(client, model)
        h = advanced["response_headers"]
        for category in ("rate_limit", "request_id", "served_by",
                         "anthropic_passthrough", "sourcegraph_specific", "cache_headers"):
            v = h.get(category) or {}
            if v:
                print(f"    {category}: {v}")
            else:
                print(f"    {category}: (none)")

        # 4. Vision input
        print()
        print("  -- Vision (image input) --")
        advanced["vision"] = probe_vision_input(client, model)
        v = advanced["vision"]
        preview = v.get("preview", v.get("error", ""))
        # Detect silent image stripping: gateway accepts (200) but model says no image
        no_image_phrases = ("don't see any image", "no image", "didn't receive", "cannot see",
                            "can't see", "no picture", "not see an image")
        silently_stripped = (v.get("status") == 200 and
                             any(p in preview.lower() for p in no_image_phrases))
        vision_verdict = ("IMAGE_SILENTLY_STRIPPED" if silently_stripped
                          else ("VISION_WORKS" if v.get("status") == 200 else "REJECTED"))
        print(f"    HTTP {v.get('status')}  verdict={vision_verdict}  {preview[:160]}")

        # 5. Anthropic-native + extra OpenAI fields
        print()
        print("  -- Anthropic-native + extra OpenAI fields --")
        advanced["native_fields"] = probe_anthropic_native_fields(client, model)
        for label, rec in advanced["native_fields"].items():
            if label.startswith("_"):
                continue
            status = rec.get("status")
            extra = ""
            if rec.get("unknown_keys"):
                extra += f"  unknown_keys={rec['unknown_keys'][:5]}"
            if rec.get("preview"):
                extra += f"  preview={rec['preview'][:120]}"
            if rec.get("choices_count"):
                extra += f"  choices={rec['choices_count']}"
            if rec.get("content_preview"):
                extra += f"  content={rec['content_preview'][:80]!r}"
            print(f"    [{label:<32}] HTTP {status}{extra}")
        print(f"    seed deterministic: {advanced['native_fields'].get('_seed_deterministic')}")

        # 6. Prompt-cache hit verification
        print()
        print("  -- Prompt-cache hit verification (2 identical calls) --")
        advanced["cache_hit"] = probe_cache_hit_verification(client, model)
        for label in ("first_call", "second_call"):
            rec = advanced["cache_hit"].get(label, {})
            print(f"    {label:<12}: HTTP {rec.get('status')}  "
                  f"cache_read={rec.get('cache_read')}  "
                  f"cache_create={rec.get('cache_create')}  "
                  f"usage={rec.get('usage')}")
        print(f"    cache hit observed on 2nd call: {advanced['cache_hit'].get('_cache_hit_observed')}")

        # 7. Endpoint discovery
        print()
        print("  -- Undocumented endpoint discovery --")
        advanced["endpoint_discovery"] = probe_endpoint_discovery(client)
        for path, rec in advanced["endpoint_discovery"].items():
            print(f"    {path:<32} GET {rec.get('GET_status', rec.get('error', '?'))}  "
                  f"{rec.get('preview', '')[:120]}")

    # Gateway limitations summary
    if probes or advanced:
        print()
        print("=" * 78)
        print("GATEWAY LIMITATIONS SUMMARY")
        print("=" * 78)
        limitations = []
        workarounds = []

        sys_r = probes.get("system_role", {})
        if sys_r.get("status") == 400:
            limitations.append("CRITICAL: system role in messages array rejected (HTTP 400)")
            workarounds.append("  -> Use top-level 'system' field in request body instead")

        tool_r = probes.get("tool_role", {})
        if tool_r.get("status") == 400:
            limitations.append("CRITICAL: tool role messages rejected (HTTP 400) — multi-turn tool use broken")
            rec = (probes.get("tool_result_formats") or {}).get("_recommended")
            if rec:
                workarounds.append(f"  -> Use format '{rec}' for tool results (see tool_result_formats probe)")
            else:
                workarounds.append("  -> No working format found; agent cannot do multi-turn tool calls")

        mtu = probes.get("multi_turn_tool_use", {})
        if mtu.get("verdict") and "FAILED" in mtu.get("verdict", ""):
            limitations.append(f"CRITICAL: multi-turn tool use failed: {mtu.get('verdict')}")

        tc = probes.get("tool_choice_variants", {})
        silently_ignored = [k for k, v in tc.items() if v.get("verdict") == "SILENTLY_IGNORED"]
        if silently_ignored:
            limitations.append(f"WARNING: tool_choice silently ignored for: {silently_ignored}")
            workarounds.append("  -> Do not rely on tool_choice; use prompt instructions instead")

        cache_usage = ((probes.get("cache_control") or {}).get("usage") or {})
        cached_tok = (cache_usage.get("prompt_tokens_details") or {}).get("cached_tokens", None)
        if cached_tok == 0:
            limitations.append("WARNING: cache_control stripped — prompt caching not available")

        adv_think = (advanced.get("thinking_strategies") or {}).get("strategies") or {}
        all_dropped = all(
            v.get("status") == 200 and not (v.get("thinking_field_hits") or v.get("thinking_chunks_count"))
            for v in adv_think.values() if v.get("status") == 200
        )
        if all_dropped and adv_think:
            limitations.append("INFO: all thinking/reasoning strategies silently dropped by gateway")

        adv_vis = advanced.get("vision", {})
        if adv_vis.get("status") == 200:
            preview = adv_vis.get("preview", "")
            if any(p in preview.lower() for p in ("don't see any image", "no image", "cannot see")):
                limitations.append("INFO: vision input silently stripped — images not forwarded to model")

        rf = probes.get("response_format_json", {})
        if rf.get("status") == 400:
            limitations.append("INFO: response_format (json_object) not supported")

        if not limitations:
            print("  No critical limitations detected.")
        else:
            for item in limitations:
                print(f"  {item}")
        if workarounds:
            print()
            print("  Workarounds:")
            for w in workarounds:
                print(f"  {w}")

    out_path = Path(args.out)
    out_path.write_text(json.dumps({
        "model": model,
        "url": _mask_url(args.url),
        "outcomes": [o.to_dict() for o in outcomes],
        "probes": probes,
        "advanced": advanced,
    }, indent=2), encoding="utf-8")
    print()
    print(f"Full structured results: {out_path}")
    print(f"Per-test raw SSE dumps:  {raw_dir}/")

    # Auto-run deep analysis after all tests complete
    print()
    print("=" * 78)
    print("AUTO-ANALYSIS (re-run anytime: python3 streaming_lab.py --analyze streaming_lab_results.json)")
    print("=" * 78)
    analyze_results(out_path)

    return 0 if all(o.all_pass for o in outcomes) else 1


if __name__ == "__main__":
    sys.exit(main())
