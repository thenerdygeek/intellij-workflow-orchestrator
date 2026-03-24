#!/usr/bin/env python3
"""
Sourcegraph LLM API — Comprehensive Request Tester

Tests ALL known API surfaces and features to determine what your
Sourcegraph instance actually supports vs what the spec says.

Usage:
    python3 api_test.py --url https://sourcegraph.yourcompany.com --token sgp_YOUR_TOKEN

    Optional:
        --model anthropic::2024-10-22::claude-sonnet-4-20250514   (override model)
        --no-verify                                                 (skip SSL verification)
        --quick                                                     (skip slow tests)

Results are printed to console AND saved to api_test_results.json
"""

import argparse
import json
import sys
import time
import requests
from datetime import datetime

# ─────────────────────────────────────────────
# Config
# ─────────────────────────────────────────────

DEFAULT_MODEL = "anthropic::2024-10-22::claude-sonnet-4-20250514"
SIMPLE_PROMPT = [{"role": "user", "content": "Say exactly: HELLO_TEST_OK"}]

results = []


def log(test_id, description, status, details=None, response_body=None, elapsed_ms=None):
    """Record a test result."""
    entry = {
        "test_id": test_id,
        "description": description,
        "status": status,  # PASS, FAIL, SKIP, ERROR
        "details": details,
        "elapsed_ms": elapsed_ms,
    }
    if response_body:
        # Truncate large responses
        body_str = json.dumps(response_body) if isinstance(response_body, dict) else str(response_body)
        entry["response_preview"] = body_str[:2000]
    results.append(entry)

    icon = {"PASS": "✓", "FAIL": "✗", "SKIP": "○", "ERROR": "⚠"}.get(status, "?")
    ms = f" ({elapsed_ms}ms)" if elapsed_ms else ""
    print(f"  {icon} {test_id}: {description} → {status}{ms}")
    if details:
        print(f"    └─ {details}")


def request(method, url, headers, json_body=None, timeout=60, verify=True):
    """Make HTTP request, return (status_code, response_json_or_text, elapsed_ms)."""
    try:
        start = time.time()
        resp = requests.request(
            method, url, headers=headers, json=json_body,
            timeout=timeout, verify=verify
        )
        elapsed = int((time.time() - start) * 1000)
        try:
            body = resp.json()
        except Exception:
            body = resp.text[:3000]
        return resp.status_code, body, elapsed
    except requests.exceptions.Timeout:
        return None, "TIMEOUT", None
    except Exception as e:
        return None, str(e), None


# ═══════════════════════════════════════════════
# SECTION 1: Endpoint Discovery
# ═══════════════════════════════════════════════

def test_endpoint_discovery(base_url, headers, verify):
    """
    Test which API endpoints exist on the instance.

    EXPECTED: At least one of these should return 200 or 405 (exists but wrong method).
    If /.api/llm/* returns 404, instance is likely Sourcegraph 6.8+ (endpoints removed).
    """
    print("\n═══ SECTION 1: Endpoint Discovery ═══")
    print("  Testing which LLM API endpoints exist on your instance...\n")

    endpoints = [
        # Public LLM API (removed in 6.8)
        ("1.1", "GET",  "/.api/llm/models",            "List models (public API)"),
        ("1.2", "POST", "/.api/llm/chat/completions",   "Chat completions (public API)"),
        # Internal Completions API (still available)
        ("1.3", "POST", "/.api/completions/stream",     "Streaming completions (internal API)"),
        ("1.4", "POST", "/.api/completions/code",       "Code completions (internal API)"),
        # Model config
        ("1.5", "GET",  "/.api/modelconfig/supported-models.json", "Supported models config"),
        ("1.6", "GET",  "/.api/client-config",          "Client configuration"),
        # Cody context
        ("1.7", "POST", "/.api/cody/context",           "Cody semantic context search"),
        # OpenAPI spec
        ("1.8", "GET",  "/api/openapi/public",          "OpenAPI public spec"),
        ("1.9", "GET",  "/.api/openapi",                "OpenAPI spec (alt path)"),
    ]

    working = {}
    for tid, method, path, desc in endpoints:
        url = base_url.rstrip("/") + path
        # For POST endpoints, send minimal body to avoid 400
        body = None
        if method == "POST":
            body = {"messages": [{"role": "user", "content": "test"}], "model": DEFAULT_MODEL}

        status, resp, elapsed = request(method, url, headers, json_body=body, timeout=15, verify=verify)

        if status == 200:
            log(tid, f"{desc} [{method} {path}]", "PASS",
                f"200 OK — endpoint exists and responded", resp, elapsed)
            working[path] = True
        elif status == 405:
            log(tid, f"{desc} [{method} {path}]", "PASS",
                f"405 Method Not Allowed — endpoint exists (wrong HTTP method)", resp, elapsed)
            working[path] = True
        elif status == 401:
            log(tid, f"{desc} [{method} {path}]", "PASS",
                f"401 Unauthorized — endpoint exists (auth issue)", resp, elapsed)
            working[path] = True
        elif status == 404:
            log(tid, f"{desc} [{method} {path}]", "FAIL",
                f"404 Not Found — endpoint does NOT exist (possibly removed in 6.8+)", resp, elapsed)
        elif status is None:
            log(tid, f"{desc} [{method} {path}]", "ERROR", str(resp))
        else:
            log(tid, f"{desc} [{method} {path}]", "FAIL",
                f"HTTP {status} — unexpected response", resp, elapsed)

    return working


# ═══════════════════════════════════════════════
# SECTION 2: Basic Chat Completions
# ═══════════════════════════════════════════════

def test_basic_chat(base_url, model, headers, verify):
    """
    Test basic chat completion on the public API.

    EXPECTED RESPONSE:
    {
      "id": "some-id",
      "object": "object",        ← NOT "chat.completion" like OpenAI
      "choices": [{
        "index": 0,
        "message": {"role": "assistant", "content": "HELLO_TEST_OK"},
        "finish_reason": ""      ← was buggy pre-6.7, returns empty string
      }],
      "usage": {"prompt_tokens": N, "completion_tokens": N, "total_tokens": N}
    }
    """
    print("\n═══ SECTION 2: Basic Chat Completions (/.api/llm/chat/completions) ═══\n")

    url = base_url.rstrip("/") + "/.api/llm/chat/completions"

    # 2.1: Minimal request
    body = {"model": model, "messages": SIMPLE_PROMPT, "max_tokens": 100, "temperature": 0}
    status, resp, elapsed = request("POST", url, headers, json_body=body, verify=verify)

    if status == 200 and isinstance(resp, dict) and "choices" in resp:
        content = resp.get("choices", [{}])[0].get("message", {}).get("content", "")
        usage = resp.get("usage", {})
        finish = resp.get("choices", [{}])[0].get("finish_reason", "MISSING")
        obj_field = resp.get("object", "MISSING")
        log("2.1", "Basic chat completion", "PASS",
            f"Got response: '{content[:100]}' | object='{obj_field}' | finish_reason='{finish}' | usage={usage}",
            resp, elapsed)
    elif status == 404:
        log("2.1", "Basic chat completion", "FAIL",
            "404 — endpoint removed (Sourcegraph 6.8+). Skip remaining Section 2 tests.", resp, elapsed)
        return False
    else:
        log("2.1", "Basic chat completion", "FAIL",
            f"HTTP {status} — {str(resp)[:200]}", resp, elapsed)
        return False

    # 2.2: With system message (should be converted to user)
    body_sys = {
        "model": model,
        "messages": [
            {"role": "system", "content": "You are a helpful assistant."},
            {"role": "user", "content": "Say exactly: SYSTEM_TEST_OK"}
        ],
        "max_tokens": 100, "temperature": 0
    }
    status, resp, elapsed = request("POST", url, headers, json_body=body_sys, verify=verify)
    if status == 200 and isinstance(resp, dict):
        content = resp.get("choices", [{}])[0].get("message", {}).get("content", "")
        log("2.2", "System message handling", "PASS",
            f"Response: '{content[:100]}' — system message accepted (may be converted to user internally)",
            resp, elapsed)
    else:
        log("2.2", "System message handling", "FAIL",
            f"HTTP {status} — system role may not be supported", resp, elapsed)

    # 2.3: max_tokens edge cases
    for mt, tid in [(8000, "2.3a"), (16000, "2.3b"), (100000, "2.3c")]:
        body_mt = {"model": model, "messages": SIMPLE_PROMPT, "max_tokens": mt, "temperature": 0}
        status, resp, elapsed = request("POST", url, headers, json_body=body_mt, timeout=15, verify=verify)
        if status == 200:
            log(tid, f"max_tokens={mt}", "PASS", "Accepted", None, elapsed)
        else:
            err = resp if isinstance(resp, str) else json.dumps(resp)[:200]
            log(tid, f"max_tokens={mt}", "FAIL", f"HTTP {status}: {err}", resp, elapsed)

    return True


# ═══════════════════════════════════════════════
# SECTION 3: Tool/Function Calling
# ═══════════════════════════════════════════════

def test_tool_calling(base_url, model, headers, verify):
    """
    Test whether tools/function calling is supported.

    The OpenAPI spec does NOT document tools, but your plugin sends them
    and they work empirically. Let's verify.

    EXPECTED: Either works (undocumented feature) or returns error.
    """
    print("\n═══ SECTION 3: Tool/Function Calling ═══\n")

    url = base_url.rstrip("/") + "/.api/llm/chat/completions"

    # 3.1: Simple tool definition
    tools = [{
        "type": "function",
        "function": {
            "name": "get_weather",
            "description": "Get the current weather in a city",
            "parameters": {
                "type": "object",
                "properties": {
                    "city": {"type": "string", "description": "City name"}
                },
                "required": ["city"]
            }
        }
    }]

    body = {
        "model": model,
        "messages": [{"role": "user", "content": "What's the weather in London?"}],
        "tools": tools,
        "max_tokens": 500,
        "temperature": 0
    }

    status, resp, elapsed = request("POST", url, headers, json_body=body, verify=verify)

    if status == 200 and isinstance(resp, dict):
        choices = resp.get("choices", [{}])
        msg = choices[0].get("message", {}) if choices else {}
        tool_calls = msg.get("tool_calls", None)
        content = msg.get("content", "")
        finish = choices[0].get("finish_reason", "") if choices else ""

        if tool_calls:
            tc_info = json.dumps(tool_calls)[:300]
            log("3.1", "Tool calling (single tool)", "PASS",
                f"LLM returned tool_calls! finish_reason='{finish}' | tool_calls={tc_info}",
                resp, elapsed)
        elif content:
            log("3.1", "Tool calling (single tool)", "FAIL",
                f"LLM responded with text instead of tool call: '{content[:150]}'. Tools may not be supported.",
                resp, elapsed)
        else:
            log("3.1", "Tool calling (single tool)", "FAIL",
                f"No tool_calls and no content in response", resp, elapsed)
    elif status == 400:
        log("3.1", "Tool calling (single tool)", "FAIL",
            f"400 Bad Request — 'tools' parameter rejected by API: {str(resp)[:200]}", resp, elapsed)
        return
    elif status == 404:
        log("3.1", "Tool calling (single tool)", "SKIP", "Endpoint not available", None, elapsed)
        return
    else:
        log("3.1", "Tool calling (single tool)", "ERROR", f"HTTP {status}", resp, elapsed)
        return

    # 3.2: tool_choice parameter
    body_tc = {**body, "tool_choice": {"type": "function", "function": {"name": "get_weather"}}}
    status, resp, elapsed = request("POST", url, headers, json_body=body_tc, verify=verify)
    if status == 200:
        msg = resp.get("choices", [{}])[0].get("message", {})
        tool_calls = msg.get("tool_calls", None)
        log("3.2", "tool_choice (force specific tool)", "PASS" if tool_calls else "FAIL",
            f"tool_calls={'present' if tool_calls else 'absent'}", resp, elapsed)
    else:
        log("3.2", "tool_choice (force specific tool)", "FAIL",
            f"HTTP {status} — tool_choice may not be supported: {str(resp)[:200]}", resp, elapsed)

    # 3.3: Multiple tools
    multi_tools = tools + [{
        "type": "function",
        "function": {
            "name": "search_code",
            "description": "Search for code patterns in the repository",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string"},
                    "file_pattern": {"type": "string"}
                },
                "required": ["query"]
            }
        }
    }]
    body_multi = {
        "model": model,
        "messages": [{"role": "user", "content": "Search for 'TODO' in all .kt files"}],
        "tools": multi_tools,
        "max_tokens": 500,
        "temperature": 0
    }
    status, resp, elapsed = request("POST", url, headers, json_body=body_multi, verify=verify)
    if status == 200:
        msg = resp.get("choices", [{}])[0].get("message", {})
        tool_calls = msg.get("tool_calls", None)
        if tool_calls:
            names = [tc.get("function", {}).get("name", "?") for tc in tool_calls]
            log("3.3", "Multiple tools (2 defined)", "PASS",
                f"LLM selected: {names}", resp, elapsed)
        else:
            log("3.3", "Multiple tools (2 defined)", "FAIL",
                "No tool call in response", resp, elapsed)
    else:
        log("3.3", "Multiple tools (2 defined)", "FAIL", f"HTTP {status}", resp, elapsed)

    # 3.4: Tool result round-trip
    body_roundtrip = {
        "model": model,
        "messages": [
            {"role": "user", "content": "What's the weather in London?"},
            {"role": "assistant", "content": None, "tool_calls": [{
                "id": "call_123",
                "type": "function",
                "function": {"name": "get_weather", "arguments": '{"city": "London"}'}
            }]},
            {"role": "tool", "tool_call_id": "call_123", "content": '{"temp": 15, "condition": "cloudy"}'}
        ],
        "tools": tools,
        "max_tokens": 200,
        "temperature": 0
    }
    status, resp, elapsed = request("POST", url, headers, json_body=body_roundtrip, verify=verify)
    if status == 200:
        content = resp.get("choices", [{}])[0].get("message", {}).get("content", "")
        log("3.4", "Tool result round-trip", "PASS",
            f"LLM processed tool result: '{content[:150]}'", resp, elapsed)
    else:
        log("3.4", "Tool result round-trip", "FAIL",
            f"HTTP {status} — tool role messages may not be accepted: {str(resp)[:200]}", resp, elapsed)


# ═══════════════════════════════════════════════
# SECTION 4: Streaming
# ═══════════════════════════════════════════════

def test_streaming(base_url, model, headers, verify):
    """
    Test SSE streaming on the public API.

    EXPECTED: Response is SSE with lines like:
        data: {"id":"...","choices":[{"delta":{"content":"HELLO"},"index":0}]}
        data: [DONE]
    """
    print("\n═══ SECTION 4: Streaming ═══\n")

    url = base_url.rstrip("/") + "/.api/llm/chat/completions"

    body = {
        "model": model,
        "messages": SIMPLE_PROMPT,
        "max_tokens": 100,
        "temperature": 0,
        "stream": True
    }

    try:
        start = time.time()
        resp = requests.post(url, headers=headers, json=body, stream=True, timeout=30, verify=verify)
        elapsed_first = None
        chunks = []
        content_parts = []

        if resp.status_code != 200:
            body_text = resp.text[:500]
            log("4.1", "SSE streaming", "FAIL", f"HTTP {resp.status_code}: {body_text}")
            return

        for line in resp.iter_lines(decode_unicode=True):
            if elapsed_first is None:
                elapsed_first = int((time.time() - start) * 1000)

            if line and line.startswith("data: ") and line != "data: [DONE]":
                chunk_json = line[6:]
                chunks.append(chunk_json)
                try:
                    chunk = json.loads(chunk_json)
                    delta = chunk.get("choices", [{}])[0].get("delta", {})
                    if "content" in delta and delta["content"]:
                        content_parts.append(delta["content"])
                except json.JSONDecodeError:
                    pass

        total_elapsed = int((time.time() - start) * 1000)
        full_content = "".join(content_parts)

        log("4.1", "SSE streaming", "PASS",
            f"Got {len(chunks)} chunks, TTFB={elapsed_first}ms, total={total_elapsed}ms, content='{full_content[:100]}'")

        # Check chunk structure
        if chunks:
            try:
                first_chunk = json.loads(chunks[0])
                has_delta = "delta" in json.dumps(first_chunk)
                has_choices = "choices" in first_chunk
                log("4.2", "Stream chunk format", "PASS" if has_choices else "FAIL",
                    f"has_choices={has_choices}, has_delta={has_delta}, sample={json.dumps(first_chunk)[:300]}")
            except Exception as e:
                log("4.2", "Stream chunk format", "ERROR", str(e))
    except Exception as e:
        log("4.1", "SSE streaming", "ERROR", str(e))


# ═══════════════════════════════════════════════
# SECTION 5: Extended Thinking / Reasoning
# ═══════════════════════════════════════════════

def test_thinking(base_url, model, headers, verify):
    """
    Test whether extended thinking / reasoning is supported.

    Anthropic's API supports:
        "thinking": {"type": "enabled", "budget_tokens": 10000}

    EXPECTED: Most likely rejected (not in Sourcegraph's OpenAPI spec).
    But worth testing — some proxies pass through vendor-specific params.
    """
    print("\n═══ SECTION 5: Extended Thinking / Reasoning ═══\n")

    url = base_url.rstrip("/") + "/.api/llm/chat/completions"

    # 5.1: Anthropic-style thinking parameter
    body_thinking = {
        "model": model,
        "messages": [{"role": "user", "content": "What is 15 * 37? Think step by step."}],
        "max_tokens": 2000,
        "temperature": 1,  # Anthropic requires temperature=1 for thinking
        "thinking": {
            "type": "enabled",
            "budget_tokens": 5000
        }
    }
    status, resp, elapsed = request("POST", url, headers, json_body=body_thinking, verify=verify)

    if status == 200 and isinstance(resp, dict):
        choices = resp.get("choices", [{}])
        msg = choices[0].get("message", {}) if choices else {}
        content = msg.get("content", "")
        # Check if response has thinking content blocks
        thinking_content = msg.get("thinking", None) or msg.get("thinking_content", None)
        if thinking_content:
            log("5.1", "Anthropic thinking parameter", "PASS",
                f"Thinking content returned! Length: {len(str(thinking_content))} chars",
                resp, elapsed)
        else:
            log("5.1", "Anthropic thinking parameter", "PASS",
                f"Request accepted but no thinking content in response. Content: '{content[:150]}'. "
                f"Thinking parameter may be silently ignored.",
                resp, elapsed)
    elif status == 400:
        log("5.1", "Anthropic thinking parameter", "FAIL",
            f"400 — 'thinking' parameter rejected: {str(resp)[:300]}", resp, elapsed)
    elif status == 404:
        log("5.1", "Anthropic thinking parameter", "SKIP", "Endpoint not available")
    else:
        log("5.1", "Anthropic thinking parameter", "ERROR",
            f"HTTP {status}: {str(resp)[:200]}", resp, elapsed)

    # 5.2: OpenAI-style reasoning_effort
    body_reasoning = {
        "model": model,
        "messages": [{"role": "user", "content": "What is 15 * 37? Think step by step."}],
        "max_tokens": 2000,
        "temperature": 0,
        "reasoning_effort": "high"
    }
    status, resp, elapsed = request("POST", url, headers, json_body=body_reasoning, verify=verify)

    if status == 200:
        content = resp.get("choices", [{}])[0].get("message", {}).get("content", "") if isinstance(resp, dict) else ""
        log("5.2", "OpenAI reasoning_effort parameter", "PASS",
            f"Accepted (may be silently ignored). Content: '{content[:150]}'", resp, elapsed)
    elif status == 400:
        log("5.2", "OpenAI reasoning_effort parameter", "FAIL",
            f"400 — rejected: {str(resp)[:200]}", resp, elapsed)
    else:
        log("5.2", "OpenAI reasoning_effort parameter", "FAIL",
            f"HTTP {status}", resp, elapsed)

    # 5.3: Try a "thinking" model variant if available
    thinking_models = [
        model.replace("claude-sonnet-4", "claude-sonnet-4-thinking"),
        model.replace("claude-sonnet-4", "claude-opus-4-thinking"),
        "anthropic::2024-10-22::claude-sonnet-4-20250514-thinking",
    ]

    for tm in thinking_models:
        body_tm = {
            "model": tm,
            "messages": [{"role": "user", "content": "Say OK"}],
            "max_tokens": 100,
            "temperature": 0
        }
        status, resp, elapsed = request("POST", url, headers, json_body=body_tm, timeout=15, verify=verify)
        if status == 200:
            log("5.3", f"Thinking model variant: {tm}", "PASS",
                f"Model exists and responds", resp, elapsed)
            break
        else:
            log("5.3", f"Thinking model variant: {tm}", "FAIL",
                f"HTTP {status}: {str(resp)[:150]}", None, elapsed)


# ═══════════════════════════════════════════════
# SECTION 6: Internal Completions API
# ═══════════════════════════════════════════════

def test_internal_api(base_url, model, headers, verify):
    """
    Test the internal completions API (/.api/completions/stream).
    This endpoint uses a DIFFERENT format from the OpenAI-compatible one.

    EXPECTED REQUEST:
    {
      "model": "anthropic/claude-3-5-sonnet-20240620",
      "messages": [{"speaker": "human", "text": "..."}],
      "maxTokensToSample": 1000,
      "temperature": 0.0,
      "topK": -1,
      "topP": -1
    }

    EXPECTED RESPONSE (non-streaming):
    {
      "completion": "response text",
      "stopReason": "end_turn"
    }

    EXPECTED RESPONSE (streaming SSE):
    event: completion
    data: {"completion": "partial", "stopReason": ""}
    ...
    event: done
    data: {}
    """
    print("\n═══ SECTION 6: Internal Completions API (/.api/completions/stream) ═══\n")

    # Convert model format: anthropic::2024-10-22::claude-sonnet-4 → anthropic/claude-sonnet-4
    parts = model.split("::")
    if len(parts) == 3:
        internal_model = f"{parts[0]}/{parts[2]}"
    else:
        internal_model = model

    url = base_url.rstrip("/") + "/.api/completions/stream?api-version=2&client-name=WorkflowOrchestrator&client-version=1.0"

    # 6.1: Non-streaming request
    body = {
        "model": internal_model,
        "messages": [
            {"speaker": "human", "text": "Say exactly: INTERNAL_API_OK"}
        ],
        "maxTokensToSample": 200,
        "temperature": 0,
        "topK": -1,
        "topP": -1,
        "stream": False
    }

    status, resp, elapsed = request("POST", url, headers, json_body=body, verify=verify)

    if status == 200 and isinstance(resp, dict):
        completion = resp.get("completion", resp.get("deltaText", ""))
        stop = resp.get("stopReason", "MISSING")
        log("6.1", f"Internal API non-streaming (model={internal_model})", "PASS",
            f"completion='{completion[:150]}' | stopReason='{stop}'", resp, elapsed)
    elif status == 200 and isinstance(resp, str) and "event:" in resp:
        log("6.1", f"Internal API non-streaming", "PASS",
            f"Got SSE response even with stream=false (API may always stream). Preview: {resp[:200]}", None, elapsed)
    elif status == 404:
        log("6.1", f"Internal API non-streaming", "FAIL",
            "404 — endpoint not available", resp, elapsed)
        return
    elif status == 401:
        log("6.1", f"Internal API non-streaming", "FAIL",
            "401 — authentication failed for internal API", resp, elapsed)
        return
    else:
        log("6.1", f"Internal API non-streaming", "FAIL",
            f"HTTP {status}: {str(resp)[:200]}", resp, elapsed)

    # 6.2: Try with OpenAI-format messages (some instances accept both)
    body_openai = {
        "model": internal_model,
        "messages": [
            {"role": "user", "content": "Say exactly: FORMAT_TEST_OK"}
        ],
        "maxTokensToSample": 200,
        "temperature": 0,
        "stream": False
    }
    status, resp, elapsed = request("POST", url, headers, json_body=body_openai, verify=verify)
    if status == 200:
        log("6.2", "Internal API with OpenAI-format messages", "PASS",
            f"OpenAI message format also accepted", resp, elapsed)
    else:
        log("6.2", "Internal API with OpenAI-format messages", "FAIL",
            f"HTTP {status} — must use speaker/text format: {str(resp)[:200]}", resp, elapsed)

    # 6.3: Streaming via SSE
    body_stream = {**body, "stream": True}
    try:
        start = time.time()
        resp_stream = requests.post(url, headers=headers, json=body_stream, stream=True, timeout=30, verify=verify)
        chunks = []
        for line in resp_stream.iter_lines(decode_unicode=True):
            if line and (line.startswith("data:") or line.startswith("event:")):
                chunks.append(line)
        total_elapsed = int((time.time() - start) * 1000)

        if chunks:
            log("6.3", "Internal API streaming (SSE)", "PASS",
                f"Got {len(chunks)} SSE lines. Sample: {chunks[0][:200]}", None, total_elapsed)
        else:
            log("6.3", "Internal API streaming (SSE)", "FAIL", "No SSE chunks received")
    except Exception as e:
        log("6.3", "Internal API streaming (SSE)", "ERROR", str(e))


# ═══════════════════════════════════════════════
# SECTION 7: Model Discovery
# ═══════════════════════════════════════════════

def test_model_discovery(base_url, headers, verify):
    """
    Discover available models and their capabilities.

    EXPECTED: JSON with model list including capabilities like
    'chat', 'tools', 'vision', 'reasoning'
    """
    print("\n═══ SECTION 7: Model Discovery ═══\n")

    # 7.1: Public models endpoint
    url = base_url.rstrip("/") + "/.api/llm/models"
    status, resp, elapsed = request("GET", url, headers, verify=verify)
    if status == 200 and isinstance(resp, dict):
        models = resp.get("data", resp.get("models", []))
        if isinstance(models, list):
            log("7.1", f"Public models endpoint", "PASS",
                f"Found {len(models)} models. First 5: {[m.get('id', '?') for m in models[:5]]}", None, elapsed)
            # Check for thinking/reasoning models
            thinking = [m for m in models if "thinking" in m.get("id", "").lower() or "reasoning" in str(m.get("capabilities", "")).lower()]
            if thinking:
                log("7.1b", "Thinking/reasoning models available", "PASS",
                    f"Found {len(thinking)}: {[m.get('id', '?') for m in thinking[:5]]}")
            else:
                log("7.1b", "Thinking/reasoning models available", "FAIL", "None found in model list")
        else:
            log("7.1", f"Public models endpoint", "PASS", f"Response: {str(resp)[:300]}", resp, elapsed)
    elif status == 404:
        log("7.1", "Public models endpoint", "FAIL", "404 — endpoint removed", resp, elapsed)
    else:
        log("7.1", "Public models endpoint", "FAIL", f"HTTP {status}", resp, elapsed)

    # 7.2: Internal supported-models.json
    url2 = base_url.rstrip("/") + "/.api/modelconfig/supported-models.json"
    status, resp, elapsed = request("GET", url2, headers, verify=verify)
    if status == 200 and isinstance(resp, dict):
        # Look for model capabilities
        models = resp.get("models", resp.get("chat", []))
        if isinstance(models, list):
            log("7.2", "Internal model config", "PASS",
                f"Found config with {len(models)} models", None, elapsed)
            # Check for tools capability
            with_tools = [m for m in models if "tools" in str(m.get("capabilities", []))]
            log("7.2b", "Models with 'tools' capability", "PASS" if with_tools else "FAIL",
                f"{len(with_tools)} models: {[m.get('modelRef', m.get('id', '?')) for m in with_tools[:5]]}")
            # Check for reasoning capability
            with_reasoning = [m for m in models if "reasoning" in str(m.get("capabilities", []))]
            log("7.2c", "Models with 'reasoning' capability", "PASS" if with_reasoning else "FAIL",
                f"{len(with_reasoning)} models: {[m.get('modelRef', m.get('id', '?')) for m in with_reasoning[:5]]}")
        else:
            log("7.2", "Internal model config", "PASS", f"Response keys: {list(resp.keys())[:10]}", None, elapsed)
    else:
        log("7.2", "Internal model config", "FAIL", f"HTTP {status}", resp, elapsed)


# ═══════════════════════════════════════════════
# SECTION 8: Vendor-Specific Feature Passthrough
# ═══════════════════════════════════════════════

def test_vendor_passthrough(base_url, model, headers, verify):
    """
    Test whether Sourcegraph passes through vendor-specific parameters
    that aren't in their own OpenAPI spec.

    Some proxies silently pass unknown parameters to the upstream provider.
    """
    print("\n═══ SECTION 8: Vendor-Specific Passthrough ═══\n")

    url = base_url.rstrip("/") + "/.api/llm/chat/completions"

    # 8.1: response_format (in spec)
    body = {
        "model": model,
        "messages": [{"role": "user", "content": "Return a JSON object with key 'status' and value 'ok'"}],
        "max_tokens": 100,
        "temperature": 0,
        "response_format": {"type": "json_object"}
    }
    status, resp, elapsed = request("POST", url, headers, json_body=body, verify=verify)
    if status == 200:
        content = resp.get("choices", [{}])[0].get("message", {}).get("content", "") if isinstance(resp, dict) else ""
        is_json = False
        try:
            json.loads(content)
            is_json = True
        except Exception:
            pass
        log("8.1", "response_format: json_object", "PASS" if is_json else "FAIL",
            f"Content: '{content[:150]}' | valid_json={is_json}", resp, elapsed)
    else:
        log("8.1", "response_format: json_object", "FAIL", f"HTTP {status}", resp, elapsed)

    # 8.2: Unknown parameter (should be ignored or passed through)
    body_unknown = {
        "model": model,
        "messages": SIMPLE_PROMPT,
        "max_tokens": 100,
        "temperature": 0,
        "totally_fake_parameter": True,
        "another_nonsense": {"nested": "value"}
    }
    status, resp, elapsed = request("POST", url, headers, json_body=body_unknown, verify=verify)
    if status == 200:
        log("8.2", "Unknown parameters silently ignored", "PASS",
            "Proxy accepts and ignores unknown parameters — vendor passthrough possible", None, elapsed)
    elif status == 400:
        log("8.2", "Unknown parameters rejected", "FAIL",
            "400 — proxy validates strictly, no vendor passthrough", resp, elapsed)
    else:
        log("8.2", "Unknown parameters", "FAIL", f"HTTP {status}", resp, elapsed)

    # 8.3: Anthropic-style metadata
    body_meta = {
        "model": model,
        "messages": SIMPLE_PROMPT,
        "max_tokens": 100,
        "temperature": 0,
        "metadata": {"user_id": "test-user-123"}
    }
    status, resp, elapsed = request("POST", url, headers, json_body=body_meta, verify=verify)
    if status == 200:
        log("8.3", "Anthropic metadata parameter", "PASS",
            "Accepted — metadata may be passed to Anthropic", None, elapsed)
    else:
        log("8.3", "Anthropic metadata parameter", "FAIL", f"HTTP {status}", resp, elapsed)


# ═══════════════════════════════════════════════
# Main
# ═══════════════════════════════════════════════

def main():
    parser = argparse.ArgumentParser(description="Sourcegraph LLM API Comprehensive Tester")
    parser.add_argument("--url", required=True, help="Sourcegraph instance URL")
    parser.add_argument("--token", required=True, help="Sourcegraph access token")
    parser.add_argument("--model", default=DEFAULT_MODEL, help="Model to test with")
    parser.add_argument("--no-verify", action="store_true", help="Skip SSL verification")
    parser.add_argument("--quick", action="store_true", help="Skip slow tests")
    args = parser.parse_args()

    verify = not args.no_verify
    headers = {
        "Authorization": f"token {args.token}",
        "Content-Type": "application/json"
    }

    print(f"╔══════════════════════════════════════════════════╗")
    print(f"║  Sourcegraph LLM API Tester                     ║")
    print(f"║  Instance: {args.url[:38]:38s} ║")
    print(f"║  Model:    {args.model[:38]:38s} ║")
    print(f"║  Time:     {datetime.now().isoformat()[:38]:38s} ║")
    print(f"╚══════════════════════════════════════════════════╝")

    # Run all sections
    working = test_endpoint_discovery(args.url, headers, verify)
    test_model_discovery(args.url, headers, verify)

    llm_available = test_basic_chat(args.url, args.model, headers, verify)

    if llm_available:
        test_tool_calling(args.url, args.model, headers, verify)
        if not args.quick:
            test_streaming(args.url, args.model, headers, verify)
        test_thinking(args.url, args.model, headers, verify)
        test_vendor_passthrough(args.url, args.model, headers, verify)

    test_internal_api(args.url, args.model, headers, verify)

    # Summary
    print(f"\n{'═' * 50}")
    print(f"SUMMARY")
    print(f"{'═' * 50}")
    passed = sum(1 for r in results if r["status"] == "PASS")
    failed = sum(1 for r in results if r["status"] == "FAIL")
    errors = sum(1 for r in results if r["status"] == "ERROR")
    skipped = sum(1 for r in results if r["status"] == "SKIP")
    print(f"  ✓ {passed} passed  ✗ {failed} failed  ⚠ {errors} errors  ○ {skipped} skipped")

    # Key findings
    print(f"\nKEY FINDINGS:")
    llm_endpoint = "/.api/llm/chat/completions" in str(working)
    internal_endpoint = "/.api/completions/stream" in str(working)
    tools_work = any(r["test_id"] == "3.1" and r["status"] == "PASS" for r in results)
    thinking_works = any(r["test_id"] == "5.1" and r["status"] == "PASS" for r in results)
    streaming_works = any(r["test_id"] == "4.1" and r["status"] == "PASS" for r in results)

    print(f"  {'✓' if llm_endpoint else '✗'} Public LLM API (/.api/llm) {'available' if llm_endpoint else 'NOT available (6.8+ removed it)'}")
    print(f"  {'✓' if internal_endpoint else '✗'} Internal API (/.api/completions) {'available' if internal_endpoint else 'NOT available'}")
    print(f"  {'✓' if tools_work else '✗'} Tool/function calling {'WORKS' if tools_work else 'NOT working'}")
    print(f"  {'✓' if thinking_works else '✗'} Extended thinking {'WORKS (or silently ignored)' if thinking_works else 'NOT supported'}")
    print(f"  {'✓' if streaming_works else '✗'} Streaming {'WORKS' if streaming_works else 'NOT working'}")

    # Save results
    output = {
        "instance": args.url,
        "model": args.model,
        "timestamp": datetime.now().isoformat(),
        "summary": {"passed": passed, "failed": failed, "errors": errors, "skipped": skipped},
        "findings": {
            "public_llm_api": llm_endpoint,
            "internal_api": internal_endpoint,
            "tools": tools_work,
            "thinking": thinking_works,
            "streaming": streaming_works
        },
        "results": results
    }

    with open("api_test_results.json", "w") as f:
        json.dump(output, f, indent=2)
    print(f"\nFull results saved to api_test_results.json")


if __name__ == "__main__":
    main()
