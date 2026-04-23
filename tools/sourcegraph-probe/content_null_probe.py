#!/usr/bin/env python3
"""
Content-null probe — does Sourcegraph still require the U+200B placeholder?
==========================================================================

SourcegraphChatClient.sanitizeMessages injects U+200B (zero-width space) as
the `content` field on outgoing assistant turns that have tool_calls but
null/blank content. The in-code comment (SourcegraphChatClient.kt:275-281)
says this is because "Anthropic/Sourcegraph rejects empty content in
conversation history". That comment may be stale — Sourcegraph's gateway
has had multiple translation-layer fixes since.

This probe tests four content encodings of a tool-call-only assistant turn
against a live Sourcegraph instance, so we can decide whether to drop the
ZWS workaround entirely:

    1. "​"     — our current placeholder (baseline, must still work)
    2. null         — OpenAI-native way of indicating "no prose"
    3. ""           — empty string (the historic failure case)
    4. <omitted>    — no content field at all

Each variant sends the same two-turn conversation (assistant tool_call +
user tool_result). The probe reports HTTP status, error body (if any),
response shape, and a verdict per variant.

Usage:
    python3 content_null_probe.py --url https://<sourcegraph> --token <sgp_token>

Optional:
    --model <model-id>     Override auto-discovered model
    --verbose              Print full response bodies

Exits non-zero if the baseline fails (something else is wrong).
Prints a clear recommendation at the end.
"""

import argparse
import copy
import json
import sys
import time
from dataclasses import dataclass, field
from typing import Any, Optional

try:
    import requests
except ImportError:
    print("ERROR: 'requests' library not installed. Run: pip install requests")
    sys.exit(1)


# ─────────────────────────────────────────────────
# Shared request helpers (mirrors probe.py style)
# ─────────────────────────────────────────────────

@dataclass
class VariantResult:
    name: str
    description: str
    status_code: Optional[int] = None
    elapsed_ms: Optional[float] = None
    error_excerpt: Optional[str] = None
    accepted: bool = False
    raw_body_len: int = 0


def _headers(token: str, auth_style: str = "token") -> dict:
    if auth_style == "token":
        return {"Authorization": f"token {token}", "Content-Type": "application/json"}
    elif auth_style == "bearer":
        return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    raise ValueError(f"Unknown auth_style {auth_style}")


def _request(method: str, url: str, headers: dict, json_body: Optional[dict] = None,
             timeout: int = 60) -> tuple:
    start = time.time()
    resp = requests.request(method, url, headers=headers, json=json_body, timeout=timeout)
    elapsed = (time.time() - start) * 1000
    return resp.status_code, resp.text, elapsed


# ─────────────────────────────────────────────────
# Model discovery (minimal — we only need one working model that supports tools)
# ─────────────────────────────────────────────────

def discover_model(base_url: str, token: str) -> Optional[str]:
    """Find the first Anthropic model in the catalogue. Prefers Opus > Sonnet."""
    url = f"{base_url}/.api/llm/models"
    try:
        status, body, _ = _request("GET", url, headers=_headers(token))
        if status != 200:
            return None
        data = json.loads(body)
        models = data.get("data", data.get("models", []))
        if not isinstance(models, list):
            return None

        def rank(m):
            mid = m.get("id", m.get("modelId", "")) if isinstance(m, dict) else str(m)
            mid_l = mid.lower()
            if "opus" in mid_l and "thinking" in mid_l:
                return 0
            if "opus" in mid_l:
                return 1
            if "sonnet" in mid_l and "thinking" in mid_l:
                return 2
            if "sonnet" in mid_l:
                return 3
            if "claude" in mid_l:
                return 4
            return 99

        models_sorted = sorted(
            [m for m in models if isinstance(m, dict)],
            key=rank,
        )
        if not models_sorted:
            return None
        return models_sorted[0].get("id") or models_sorted[0].get("modelId")
    except Exception:
        return None


# ─────────────────────────────────────────────────
# The probe
# ─────────────────────────────────────────────────

VARIANTS = [
    ("zws",      "content = \"\\u200B\"   (current placeholder)", "​"),
    ("null",     "content = null                           ", None),
    ("empty",    "content = \"\"                             ", ""),
    ("omitted",  "content field omitted entirely           ", "__OMIT__"),
]


def build_conversation(variant_content: Any) -> list:
    """
    Build the same two-turn exchange with a tool-call-only assistant turn,
    varying only how that assistant turn encodes empty content.

    Turn 1: user asks for a file read
    Turn 2: assistant emits ONLY a tool_call (no prose — the variant under test)
    Turn 3: user sends the tool result
    Turn 4: user follows up so the model has to produce something we can verify
    """
    assistant_msg = {
        "role": "assistant",
        "tool_calls": [{
            "id": "call_probe_001",
            "type": "function",
            "function": {
                "name": "read_file",
                "arguments": json.dumps({"path": "/tmp/probe.txt"}),
            },
        }],
    }
    if variant_content != "__OMIT__":
        assistant_msg["content"] = variant_content

    return [
        {"role": "user", "content": "Read /tmp/probe.txt and tell me the first line."},
        assistant_msg,
        {
            "role": "tool",
            "tool_call_id": "call_probe_001",
            "content": "First line of probe.txt: Hello, probe.",
        },
        {"role": "user", "content": "Now just reply with the single word OK."},
    ]


def run_variant(base_url: str, token: str, model: str, variant: tuple, verbose: bool) -> VariantResult:
    name, description, variant_content = variant
    result = VariantResult(name=name, description=description)

    messages = build_conversation(variant_content)
    body = {
        "model": model,
        "messages": messages,
        "max_tokens": 64,
        "temperature": 0,
        "stream": False,
        "tools": [{
            "type": "function",
            "function": {
                "name": "read_file",
                "description": "Read a file from disk",
                "parameters": {
                    "type": "object",
                    "properties": {"path": {"type": "string"}},
                    "required": ["path"],
                },
            },
        }],
    }

    url = f"{base_url}/.api/llm/chat/completions"
    try:
        status, resp_body, elapsed = _request("POST", url, headers=_headers(token), json_body=body, timeout=60)
    except Exception as e:
        result.error_excerpt = f"network error: {e}"
        return result

    result.status_code = status
    result.elapsed_ms = elapsed
    result.raw_body_len = len(resp_body)

    if verbose:
        print(f"\n--- RAW RESPONSE ({name}) ---")
        print(resp_body[:2000])
        print("--- END ---\n")

    if status == 200:
        try:
            data = json.loads(resp_body)
            choice = data.get("choices", [{}])[0]
            content = choice.get("message", {}).get("content", "") or ""
            if content or choice.get("message", {}).get("tool_calls"):
                result.accepted = True
            else:
                result.error_excerpt = "200 OK but response body has no content/tool_calls"
        except Exception as e:
            result.error_excerpt = f"200 OK but unparseable body: {e}"
    else:
        # Keep error concise but include enough to diagnose
        result.error_excerpt = resp_body[:500].replace("\n", " ")

    return result


# ─────────────────────────────────────────────────
# CLI + reporting
# ─────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True, help="Sourcegraph base URL (e.g. https://sourcegraph.example.com)")
    ap.add_argument("--token", required=True, help="Sourcegraph access token (sgp_...)")
    ap.add_argument("--model", help="Override auto-discovered model")
    ap.add_argument("--verbose", action="store_true", help="Print raw response bodies")
    args = ap.parse_args()

    base_url = args.url.rstrip("/")

    print("=" * 72)
    print(" Sourcegraph content-null probe")
    print("=" * 72)
    print(f" Instance: {base_url}")

    model = args.model or discover_model(base_url, args.token)
    if not model:
        print(" ERROR: could not discover a model. Try --model <id> explicitly.")
        sys.exit(2)
    print(f" Model:    {model}")
    print()

    results = []
    for variant in VARIANTS:
        print(f" Running variant: {variant[0]:<8}  {variant[1]}")
        r = run_variant(base_url, args.token, model, variant, args.verbose)
        results.append(r)
        verdict = "ACCEPT" if r.accepted else f"REJECT ({r.status_code})"
        print(f"   → {verdict:<15}  {int(r.elapsed_ms or 0)}ms")
        if not r.accepted and r.error_excerpt:
            print(f"     error: {r.error_excerpt[:200]}")

    # ─────────────────────────────────────────────────
    # Verdict
    # ─────────────────────────────────────────────────
    print()
    print("=" * 72)
    print(" Summary")
    print("=" * 72)
    baseline = next(r for r in results if r.name == "zws")
    if not baseline.accepted:
        print(" BASELINE FAILED — zws placeholder is also rejected. Something else")
        print(" is wrong (auth, model, network). Re-run with --verbose to inspect.")
        sys.exit(3)
    print(" Baseline (current zws workaround) works. ✓")
    print()

    null_r = next(r for r in results if r.name == "null")
    empty_r = next(r for r in results if r.name == "empty")
    omit_r = next(r for r in results if r.name == "omitted")

    print(f" content = null     : {'ACCEPT' if null_r.accepted else 'REJECT'}")
    print(f" content = \"\"       : {'ACCEPT' if empty_r.accepted else 'REJECT'}")
    print(f" content omitted    : {'ACCEPT' if omit_r.accepted else 'REJECT'}")
    print()

    print(" Recommendation:")
    if null_r.accepted:
        print("   Drop the ZWS workaround and send `content: null` directly.")
        print("   Change target: SourcegraphChatClient.sanitizeMessages Phase 3 Case 2")
        print("   (remove the injection loop at line 282-291).")
    elif omit_r.accepted:
        print("   Omit the content field entirely when tool_calls is non-empty.")
        print("   Requires adjusting ChatMessage serialization to skip `content` when null.")
    elif empty_r.accepted:
        print("   Replace ZWS with \"\" (empty string) — no debug-dump artifact.")
    else:
        print("   Keep the ZWS workaround. Gateway still requires a non-empty content")
        print("   string when tool_calls is present. isEffectivelyBlank guard on our side")
        print("   is the correct long-term mitigation.")

    # Machine-readable record for future comparison
    record = {
        "instance": base_url,
        "model": model,
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "results": [r.__dict__ for r in results],
    }
    with open("content_null_probe_results.json", "w") as f:
        json.dump(record, f, indent=2)
    print()
    print(" Wrote: content_null_probe_results.json")


if __name__ == "__main__":
    main()
