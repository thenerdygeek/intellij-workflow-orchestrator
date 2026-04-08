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
import json
import os
import re
import sys
import time
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Any, Callable, Iterator

try:
    import requests
except ImportError:
    print("ERROR: 'requests' not installed. Run: pip install requests")
    sys.exit(1)


CHAT_PATH = "/.api/llm/chat/completions"
MODELS_PATH = "/.api/llm/models"


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
    description: str
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

def build_body_a(scenario: Scenario, model: str) -> dict:
    return {
        "model": model,
        "stream": True,
        "stream_options": {"include_usage": True},
        "max_tokens": 2048,
        "temperature": 0.2,
        "tools": NATIVE_TOOLS,
        "messages": [{"role": "user", "content": scenario.user_prompt}],
    }


def build_body_b(scenario: Scenario, model: str) -> dict:
    return {
        "model": model,
        "stream": True,
        "stream_options": {"include_usage": True},
        "max_tokens": 2048,
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
        "max_tokens": 2048,
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
    try:
        resp = client.post_chat(body, stream=True)
        outcome.http_status = resp.status_code
        if resp.status_code != 200:
            err_body = resp.text[:2000]
            outcome.transport_error = f"HTTP {resp.status_code}: {err_body[:300]}"
            raw_lines.append(f"!! HTTP {resp.status_code}")
            raw_lines.append(err_body)
            raw_path.write_text("\n".join(raw_lines))
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

            payload = extract_sse_data(line)
            if payload is None:
                continue
            outcome.sse_chunks += 1
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
        raw_lines.append(f"# total: {outcome.total_time_ms:.0f}ms, "
                         f"chunks: {outcome.sse_chunks}, "
                         f"finish_reason: {outcome.finish_reason}")
        raw_path.write_text("\n".join(raw_lines))

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
    """tool_choice: auto / none / forced function — does the gateway accept any?"""
    out = {}
    for label, tc in [
        ("auto",   "auto"),
        ("none",   "none"),
        ("forced", {"type": "function", "function": {"name": "read_file"}}),
    ]:
        body = {
            "model": model, "max_tokens": 64, "temperature": 0,
            "tools": NATIVE_TOOLS, "tool_choice": tc,
            "messages": [{"role": "user", "content": "Pick a file. Use the tool if you have one."}],
        }
        try:
            r = client.post_chat(body, stream=False)
            out[label] = {"status": r.status_code, "preview": _short(r.text)}
        except requests.RequestException as e:
            out[label] = {"status": 0, "error": str(e)}
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
# Reporting
# ─────────────────────────────────────────────────────────────

def print_outcome(o: RunOutcome) -> None:
    head_status = "PASS" if o.all_pass else "FAIL"
    print()
    print("─" * 78)
    print(f"[{head_status}] scenario={o.scenario}  mode={o.mode}  ({o.passed_count} pass / {o.failed_count} fail)")
    print(f"        {o.description}")
    if o.transport_error:
        print(f"        TRANSPORT ERROR: {o.transport_error}")
    print(f"        ttfb={o.time_to_first_byte_ms:.0f}ms  total={o.total_time_ms:.0f}ms  "
          f"chunks={o.sse_chunks}  finish={o.finish_reason}  "
          f"usage={'yes' if o.saw_usage_chunk else 'no'}")
    if o.usage:
        print(f"        usage: {o.usage}")
    if o.cache_read_tokens is not None or o.cache_creation_tokens is not None:
        print(f"        cache: read={o.cache_read_tokens} create={o.cache_creation_tokens}")
    print(f"        tool_calls ({len(o.tool_calls)}):")
    for i, tc in enumerate(o.tool_calls):
        args_preview = json.dumps(tc.args)[:120]
        print(f"          [{i}] {tc.name}  args={args_preview}")
    if o.final_content.strip():
        preview = o.final_content.strip().replace("\n", "\\n")[:200]
        print(f"        content: {preview}")
    if o.parse_warnings:
        print(f"        parse warnings ({len(o.parse_warnings)}):")
        for w in o.parse_warnings:
            print(f"          - {w}")
    print(f"        assertions:")
    for a in o.assertions:
        marker = "✓" if a.passed else "✗"
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
    p.add_argument("--no-verify", action="store_true", help="Disable TLS verify")
    p.add_argument("--no-probes", action="store_true", help="Skip side probes")
    p.add_argument("--no-advanced", action="store_true", help="Skip advanced probes (thinking, metadata, vision, headers)")
    p.add_argument("--thinking-model", default=None,
                   help="Model id to use for thinking probes (auto-detect if omitted)")
    p.add_argument("--out", default="streaming_lab_results.json", help="JSON results file")
    p.add_argument("--raw-dir", default="raw_sse", help="Directory for per-test SSE dumps")
    args = p.parse_args()

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
    print(f"# url:      {args.url}")

    modes = args.only or ["A", "B", "C"]
    scenarios = SCENARIOS
    if args.scenario:
        wanted = set(args.scenario)
        scenarios = [s for s in SCENARIOS if s.name in wanted]
        if not scenarios:
            print(f"ERROR: no scenarios matched {sorted(wanted)}. Use --list.")
            return 2

    raw_dir = Path(args.raw_dir)
    print(f"# raw_dir:  {raw_dir.resolve()}")
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
                o = run_scenario(client, s, m, model, raw_dir, args.raw)
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
            print(f"  tool_choice[{label:<6}]: HTTP {r.get('status'):<4} {r.get('preview', r.get('error', ''))[:160]}")

        probes["cache_control"] = probe_cache_control(client, model)
        c = probes["cache_control"]
        print(f"  cache_control:        HTTP {c.get('status'):<4} usage={c.get('usage')}")

        probes["max_tokens"] = probe_max_tokens(client, model)
        for n, r in probes["max_tokens"].items():
            print(f"  max_tokens[{n:>5}]:    HTTP {r.get('status'):<4} {r.get('preview', '')[:120]}")

        probes["response_format_json"] = probe_response_format_json(client, model)
        r = probes["response_format_json"]
        print(f"  response_format_json: HTTP {r.get('status'):<4} {r.get('preview', '')[:120]}")

        probes["system_role"] = probe_system_role(client, model)
        r = probes["system_role"]
        print(f"  system_role:          HTTP {r.get('status'):<4} {r.get('preview', '')[:120]}")

        probes["tool_role"] = probe_tool_role(client, model)
        r = probes["tool_role"]
        print(f"  tool_role:            HTTP {r.get('status'):<4} {r.get('preview', '')[:120]}")

        probes["include_usage_isolation"] = probe_include_usage_isolation(client, model)
        r = probes["include_usage_isolation"]
        flag = r.get("usage_emitted_without_include_usage")
        print(f"  include_usage_isol:   HTTP {r.get('status'):<4} usage_without_flag={flag}")

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
            tchunks = rec.get("thinking_chunks_count")
            line = f"    [{label:<36}] HTTP {status}"
            if rec.get("error_preview"):
                line += f"  ERR: {rec['error_preview'][:120]}"
            else:
                if hits:
                    line += f"  thinking_fields={[h['path'] for h in hits]}"
                if usage_extras:
                    line += f"  usage_extras={usage_extras}"
                if tchunks:
                    line += f"  stream_thinking_chunks={tchunks}"
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
        print(f"    HTTP {v.get('status')}  {v.get('preview', v.get('error', ''))[:200]}")

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

    out_path = Path(args.out)
    out_path.write_text(json.dumps({
        "model": model,
        "url": args.url,
        "outcomes": [o.to_dict() for o in outcomes],
        "probes": probes,
        "advanced": advanced,
    }, indent=2))
    print()
    print(f"Full structured results: {out_path.resolve()}")
    print(f"Per-test raw SSE dumps:  {raw_dir.resolve()}")
    return 0 if all(o.all_pass for o in outcomes) else 1


if __name__ == "__main__":
    sys.exit(main())
