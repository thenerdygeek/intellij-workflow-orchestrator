#!/usr/bin/env python3
"""
Sourcegraph Vision (Image-Input) Lab
====================================
Standalone probe that determines WHICH multimodal request shapes the Sourcegraph
LLM gateway (`/.api/llm/chat/completions`) accepts, and — for each accepted shape
— whether the routed model actually SEES the image.

WHY THIS EXISTS
---------------
The Sourcegraph instance is OpenAI-shape on the wire but proxies to Anthropic /
OpenAI / Google models behind that surface. Each provider has its own canonical
image content-block format:

    OpenAI       {"type":"image_url","image_url":{"url":"data:..."}}
    Anthropic    {"type":"image","source":{"type":"base64","media_type":"...","data":"..."}}
    Anthropic    {"type":"image","source":{"type":"url","url":"https://..."}}
    Gemini       {"type":"inline_data","inline_data":{"mime_type":"...","data":"..."}}

The gateway translates some of those shapes for the routed provider and 400s
the rest. We don't know which until we ask. So this script asks — once per
(format × model) combination — and prints a PASS/FAIL matrix.

WHAT IT DOES
------------
1. Generates two deterministic test images IN PURE PYTHON (no PIL): a 16x16
   solid red PNG and a 16x16 solid blue PNG. The model's expected answer is the
   color word — easy to verify in the response text.
2. For every (format, model) cell, sends a single non-streaming chat completion
   asking "What is the dominant color of this image? Reply with just one word."
3. PASS = HTTP 200 AND response contains the expected color keyword AND the
   reply does NOT contain a refusal phrase (e.g. "I cannot see images").
4. Reports per-cell outcome + summary matrix + dumps a JSON blob with the full
   request/response previews for failed cells.

USAGE
-----
Requirements: Python 3.9+ and the `requests` package. No other dependencies
(test images are generated in pure stdlib — no PIL/Pillow needed).

    pip install requests

Windows (CMD):
    py -3 vision_lab.py --url https://sourcegraph.example.com --token sgp_xxx
    py -3 vision_lab.py --url ... --token ... > vision_output.txt 2>&1

Windows (PowerShell):
    python vision_lab.py --url ... --token ... | Tee-Object vision_output.txt

macOS / Linux:
    python3 vision_lab.py --url ... --token ... | tee vision_output.txt

Common flags (work identically on all platforms):

    --list                          print every format case and exit
    --only openai_data_url,anthropic_b64
                                    run only these format cases
    --models "anthropic::2024-10-22::claude-sonnet-4-20250514,openai::2024-02-01::gpt-4o"
                                    probe a specific set of models
    --discover                      list models from /.api/llm/models and probe them all
    --no-url-tests                  RECOMMENDED for most on-prem Sourcegraph instances.
                                    Skips the 2 URL-source scenarios (openai_http_url,
                                    anthropic_url_source) which require the gateway to
                                    egress to the public internet. The 11 base64
                                    scenarios still cover everything we need.
    --image-url https://example.com/cat.jpg --image-url-keyword cat
                                    Override the remote test image. The default points
                                    at a Wikipedia-hosted cat photo; if you see
                                    "no image found" or 404 in the report, pick any
                                    public JPG/PNG and pass it here with a keyword
                                    you'd expect the model to mention.
    --no-verify                     disable TLS verification (self-signed certs)
    --out vision_lab_results.json   path for the JSON dump (any path, any platform)

Output files (`vision_lab_results.json`) are written to the current working
directory by default; pass an absolute path to `--out` to control where.

INTERPRETING THE OUTPUT
-----------------------
    PASS    The format reached the model AND the model described the image.
            -> Use this format in production.
    SAW_NO  HTTP 200 but the reply has no color keyword (or refused).
            -> Gateway accepted the request but the image was DROPPED before
               it reached the model. Don't use this format.
    HTTP_4xx The gateway rejected the shape outright. Don't use this format.
    ERROR    Network / TLS / decoding failure. Re-run.

If at least ONE row PASSes for a model you care about, image input is viable
for that model via the lab-confirmed format. If every format SAW_NO, the
gateway is stripping images entirely and you must use a different transport
(direct provider API, file upload + reference, etc.).
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import os
import struct
import sys
import time
import zlib
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Any, Callable

# UTF-8 stdout on Windows (charmap can't handle some response chars).
try:
    if sys.stdout.encoding and sys.stdout.encoding.lower() not in ("utf-8", "utf8"):
        sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
except AttributeError:
    pass
try:
    if sys.stderr.encoding and sys.stderr.encoding.lower() not in ("utf-8", "utf8"):
        sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")
except AttributeError:
    pass

try:
    import requests
except ImportError:
    print("ERROR: 'requests' not installed. Run: pip install requests")
    sys.exit(1)


CHAT_PATH = "/.api/llm/chat/completions"
MODELS_PATH = "/.api/llm/models"

# ─────────────────────────────────────────────────────────────
# Defaults
# ─────────────────────────────────────────────────────────────

# Curated list of "likely vision-capable" models. The instance may not host all
# of these — missing ones will surface as 4xx and be reported, not crash.
DEFAULT_MODELS = [
    "anthropic::2024-10-22::claude-sonnet-4-20250514",
    "anthropic::2024-10-22::claude-3-7-sonnet-latest",
    "anthropic::2023-06-01::claude-3-5-sonnet-latest",
    "openai::2024-02-01::gpt-4o",
    "openai::2024-02-01::gpt-4o-mini",
    "google::v1::gemini-2.5-pro",
    "google::v1::gemini-2.5-flash",
]

# Default remote test image (used ONLY when URL-source formats are enabled and
# --image-url is not overridden). Wikipedia "Cat_November_2010-1a.jpg" — a
# stable, widely-cached image of a cat hosted on upload.wikimedia.org since
# 2014. If your gateway can't reach this URL (most on-prem instances block
# egress), pass --no-url-tests to skip the two URL-source scenarios.
DEFAULT_REMOTE_IMAGE_URL = (
    "https://upload.wikimedia.org/wikipedia/commons/4/4d/Cat_November_2010-1a.jpg"
)
DEFAULT_REMOTE_KEYWORDS = ["cat", "kitten", "feline", "tabby"]

PROMPT_COLOR = "What is the dominant color of this image? Reply with just one word."
PROMPT_TWO_COLORS = (
    "Two images follow. Reply with the dominant color of each, in order, "
    "comma-separated. Just the two words."
)
PROMPT_DESCRIBE = "What is in this image? Reply with one word."

REFUSAL_PHRASES = [
    "i cannot see",
    "i can't see",
    "i don't see",
    "no image",
    "unable to see",
    "i'm not able to view",
    "i am not able to view",
    "i cannot view",
    "can't process image",
    "no attachment",
    "i don't have the ability",
    "i'm unable to process",
]


# ─────────────────────────────────────────────────────────────
# Self-contained PNG generator (pure stdlib, no PIL required)
# ─────────────────────────────────────────────────────────────

def _png_chunk(typ: bytes, data: bytes) -> bytes:
    chunk = typ + data
    crc = zlib.crc32(chunk) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + chunk + struct.pack(">I", crc)


def make_solid_png(rgb: tuple[int, int, int], size: int = 16) -> bytes:
    """Build a `size x size` solid-color PNG with raw RGB pixels (no filtering)."""
    width = height = size
    ihdr = _png_chunk(
        b"IHDR",
        struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0),  # 8-bit, color type 2 (RGB)
    )
    raw = b""
    pixel_row = bytes(rgb) * width
    for _ in range(height):
        raw += b"\x00" + pixel_row  # filter byte 0 (None) per scanline
    idat = _png_chunk(b"IDAT", zlib.compress(raw, 9))
    iend = _png_chunk(b"IEND", b"")
    return b"\x89PNG\r\n\x1a\n" + ihdr + idat + iend


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


# Pre-build the test images once at import time.
RED_PNG = make_solid_png((220, 20, 20))
BLUE_PNG = make_solid_png((20, 60, 220))
RED_PNG_B64 = b64(RED_PNG)
BLUE_PNG_B64 = b64(BLUE_PNG)

RED_KEYWORDS = ["red", "crimson", "scarlet", "ruby", "maroon"]
BLUE_KEYWORDS = ["blue", "azure", "navy", "cobalt", "indigo"]


# ─────────────────────────────────────────────────────────────
# Format builders. Each returns the user-message `content` array.
# ─────────────────────────────────────────────────────────────

def fmt_openai_data_url(img_b64: str, media_type: str = "image/png") -> list[dict]:
    return [
        {"type": "text", "text": PROMPT_COLOR},
        {"type": "image_url",
         "image_url": {"url": f"data:{media_type};base64,{img_b64}"}},
    ]


def fmt_openai_data_url_detail(img_b64: str, detail: str,
                               media_type: str = "image/png") -> list[dict]:
    return [
        {"type": "text", "text": PROMPT_COLOR},
        {"type": "image_url",
         "image_url": {"url": f"data:{media_type};base64,{img_b64}", "detail": detail}},
    ]


def fmt_openai_http_url(http_url: str, prompt: str = PROMPT_DESCRIBE) -> list[dict]:
    return [
        {"type": "text", "text": prompt},
        {"type": "image_url", "image_url": {"url": http_url}},
    ]


def fmt_openai_bare_string(img_b64: str, media_type: str = "image/png") -> list[dict]:
    """Legacy form some SDKs emitted: image_url as a bare string, not an object."""
    return [
        {"type": "text", "text": PROMPT_COLOR},
        {"type": "image_url", "image_url": f"data:{media_type};base64,{img_b64}"},
    ]


def fmt_anthropic_b64(img_b64: str, media_type: str = "image/png") -> list[dict]:
    return [
        {"type": "text", "text": PROMPT_COLOR},
        {"type": "image",
         "source": {"type": "base64", "media_type": media_type, "data": img_b64}},
    ]


def fmt_anthropic_url(http_url: str, prompt: str = PROMPT_DESCRIBE) -> list[dict]:
    return [
        {"type": "text", "text": prompt},
        {"type": "image", "source": {"type": "url", "url": http_url}},
    ]


def fmt_anthropic_wrong_media_type(img_b64: str) -> list[dict]:
    """Sanity probe: declare PNG bytes as image/jpeg. If gateway forwards
    strictly, Anthropic 400s. If gateway re-derives MIME, this might still
    work — that's a useful signal for our own client code."""
    return [
        {"type": "text", "text": PROMPT_COLOR},
        {"type": "image",
         "source": {"type": "base64", "media_type": "image/jpeg", "data": img_b64}},
    ]


def fmt_gemini_inline_snake(img_b64: str, media_type: str = "image/png") -> list[dict]:
    return [
        {"type": "text", "text": PROMPT_COLOR},
        {"type": "inline_data",
         "inline_data": {"mime_type": media_type, "data": img_b64}},
    ]


def fmt_gemini_inline_camel(img_b64: str, media_type: str = "image/png") -> list[dict]:
    return [
        {"type": "text", "text": PROMPT_COLOR},
        {"type": "inlineData",
         "inlineData": {"mimeType": media_type, "data": img_b64}},
    ]


def fmt_multiple_images_openai(b64a: str, b64b: str,
                               media_type: str = "image/png") -> list[dict]:
    return [
        {"type": "text", "text": PROMPT_TWO_COLORS},
        {"type": "image_url",
         "image_url": {"url": f"data:{media_type};base64,{b64a}"}},
        {"type": "image_url",
         "image_url": {"url": f"data:{media_type};base64,{b64b}"}},
    ]


def fmt_image_only_openai(img_b64: str, media_type: str = "image/png") -> list[dict]:
    """No text part — only the image. Some providers reject this."""
    return [
        {"type": "image_url",
         "image_url": {"url": f"data:{media_type};base64,{img_b64}"}},
    ]


# ─────────────────────────────────────────────────────────────
# Test-case definitions
# ─────────────────────────────────────────────────────────────

@dataclass
class FormatCase:
    name: str
    description: str
    builder: Callable[[], list[dict]]
    expected_any: list[str]      # response must contain at least one of these (lowercased)
    expected_all: list[str] = field(default_factory=list)  # response must contain all of these
    needs_url: bool = False      # skip when --no-url-tests
    notes: str = ""


def build_cases(remote_image_url: str, remote_keywords: list[str]) -> list[FormatCase]:
    """All format cases. Mostly use RED so a one-word reply is unambiguous."""
    cases: list[FormatCase] = [
        FormatCase(
            name="openai_data_url",
            description="OpenAI canonical: image_url with data: URL (PNG/base64)",
            builder=lambda: fmt_openai_data_url(RED_PNG_B64),
            expected_any=RED_KEYWORDS,
        ),
        FormatCase(
            name="openai_data_url_detail_low",
            description="OpenAI image_url + detail=low",
            builder=lambda: fmt_openai_data_url_detail(RED_PNG_B64, "low"),
            expected_any=RED_KEYWORDS,
        ),
        FormatCase(
            name="openai_data_url_detail_high",
            description="OpenAI image_url + detail=high",
            builder=lambda: fmt_openai_data_url_detail(RED_PNG_B64, "high"),
            expected_any=RED_KEYWORDS,
        ),
        FormatCase(
            name="openai_data_url_detail_auto",
            description="OpenAI image_url + detail=auto (default)",
            builder=lambda: fmt_openai_data_url_detail(RED_PNG_B64, "auto"),
            expected_any=RED_KEYWORDS,
        ),
        FormatCase(
            name="openai_bare_image_url_string",
            description="Legacy: image_url field is a bare string, not an object",
            builder=lambda: fmt_openai_bare_string(RED_PNG_B64),
            expected_any=RED_KEYWORDS,
            notes="Some old SDKs emitted this shape. Probably 400s today.",
        ),
        FormatCase(
            name="openai_http_url",
            description="OpenAI image_url with public HTTP URL",
            builder=lambda: fmt_openai_http_url(remote_image_url),
            expected_any=remote_keywords,
            needs_url=True,
            notes="Requires the gateway egress to reach the public URL.",
        ),
        FormatCase(
            name="openai_image_only_no_text",
            description="OpenAI image_url with NO text part in the message",
            builder=lambda: fmt_image_only_openai(RED_PNG_B64),
            expected_any=RED_KEYWORDS,
            notes="Some providers reject zero-text messages.",
        ),
        FormatCase(
            name="openai_two_images",
            description="Two image_url parts in one message (red then blue)",
            builder=lambda: fmt_multiple_images_openai(RED_PNG_B64, BLUE_PNG_B64),
            expected_all=["red", "blue"],
            expected_any=["red", "blue"],
        ),
        FormatCase(
            name="anthropic_b64_source",
            description="Anthropic native: type=image, source.type=base64",
            builder=lambda: fmt_anthropic_b64(RED_PNG_B64),
            expected_any=RED_KEYWORDS,
        ),
        FormatCase(
            name="anthropic_url_source",
            description="Anthropic native: type=image, source.type=url",
            builder=lambda: fmt_anthropic_url(remote_image_url),
            expected_any=remote_keywords,
            needs_url=True,
        ),
        FormatCase(
            name="anthropic_wrong_media_type",
            description="Anthropic base64 source, declared image/jpeg but bytes are PNG",
            builder=lambda: fmt_anthropic_wrong_media_type(RED_PNG_B64),
            expected_any=RED_KEYWORDS,
            notes="Negative-control: PASS here would mean the gateway rewrites MIME.",
        ),
        FormatCase(
            name="gemini_inline_data_snake",
            description="Gemini native: inline_data with snake_case fields",
            builder=lambda: fmt_gemini_inline_snake(RED_PNG_B64),
            expected_any=RED_KEYWORDS,
        ),
        FormatCase(
            name="gemini_inline_data_camel",
            description="Gemini native: inlineData with camelCase fields",
            builder=lambda: fmt_gemini_inline_camel(RED_PNG_B64),
            expected_any=RED_KEYWORDS,
        ),
    ]
    return cases


# ─────────────────────────────────────────────────────────────
# HTTP client (shape-compatible with streaming_lab.py)
# ─────────────────────────────────────────────────────────────

class SourcegraphClient:
    def __init__(self, base_url: str, token: str, verify: bool = True, timeout: int = 60):
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        self.session.headers.update({
            "Authorization": f"token {token}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        })
        self.session.verify = verify
        self.timeout = timeout

    def list_models(self) -> list[str]:
        r = self.session.get(self.base_url + MODELS_PATH, timeout=30)
        r.raise_for_status()
        return [m["id"] for m in r.json().get("data", [])]

    def post_chat(self, body: dict) -> requests.Response:
        return self.session.post(self.base_url + CHAT_PATH, json=body,
                                 stream=False, timeout=self.timeout)


def _mask_url(url: str) -> str:
    if not url:
        return "<no-url>"
    try:
        from urllib.parse import urlparse, urlunparse
        parsed = urlparse(url)
        host = parsed.hostname or ""
        parts = host.split(".")
        masked_parts = ["***"] * max(len(parts) - 1, 1) + (
            [parts[-1]] if len(parts) > 1 else [])
        masked_host = ".".join(masked_parts)
        if parsed.port:
            masked_host = f"{masked_host}:{parsed.port}"
        return urlunparse((parsed.scheme, masked_host, parsed.path,
                           parsed.params, "", ""))
    except Exception:
        return "***"


def _short(text: str, n: int = 240) -> str:
    text = (text or "").strip()
    return text if len(text) <= n else text[:n] + "…"


def _decode_json(resp: requests.Response) -> Any:
    try:
        return resp.json()
    except Exception:
        return {"raw": resp.text[:1000]}


# ─────────────────────────────────────────────────────────────
# Run + evaluate
# ─────────────────────────────────────────────────────────────

@dataclass
class RunOutcome:
    model: str
    case_name: str
    status: int
    elapsed_ms: int
    verdict: str = "FAIL"        # PASS | FAIL
    fail_reason: str = ""        # why it failed (HTTP_4xx, SAW_NO, ERROR, REFUSED)
    reply_preview: str = ""
    error: str = ""
    request_preview: str = ""    # short JSON of the request body, for failed cells
    finish_reason: str | None = None
    usage: dict | None = None


def run_case(client: SourcegraphClient, model: str, case: FormatCase,
             max_tokens: int) -> RunOutcome:
    body = {
        "model": model,
        "max_tokens": max_tokens,
        "temperature": 0,
        "messages": [{"role": "user", "content": case.builder()}],
    }
    request_preview = _short(json.dumps(body)[:800], 800)

    t0 = time.time()
    try:
        r = client.post_chat(body)
        elapsed_ms = int((time.time() - t0) * 1000)
    except requests.RequestException as e:
        return RunOutcome(
            model=model, case_name=case.name,
            status=0, elapsed_ms=int((time.time() - t0) * 1000),
            verdict="FAIL", fail_reason="ERROR", error=str(e),
            request_preview=request_preview,
        )

    out = RunOutcome(
        model=model, case_name=case.name,
        status=r.status_code, elapsed_ms=elapsed_ms,
        request_preview=request_preview,
    )

    if r.status_code != 200:
        out.fail_reason = f"HTTP_{r.status_code}"
        out.reply_preview = _short(r.text, 400)
        return out

    data = _decode_json(r)
    if not isinstance(data, dict):
        out.fail_reason = "BAD_JSON"
        out.reply_preview = _short(str(data), 400)
        return out

    choices = data.get("choices") or []
    if not choices:
        out.fail_reason = "NO_CHOICES"
        out.reply_preview = _short(json.dumps(data), 400)
        return out

    message = choices[0].get("message") or {}
    out.finish_reason = choices[0].get("finish_reason")
    out.usage = data.get("usage")
    content = message.get("content") or ""
    # `content` may be a string OR a list of parts (Anthropic-style passthrough)
    if isinstance(content, list):
        text_chunks = [p.get("text", "") for p in content if isinstance(p, dict)]
        content = " ".join(text_chunks)
    out.reply_preview = _short(content, 400)

    lower = content.lower()
    if any(p in lower for p in REFUSAL_PHRASES):
        out.fail_reason = "REFUSED"
        return out

    matched_any = any(kw in lower for kw in case.expected_any)
    matched_all = all(kw in lower for kw in case.expected_all) if case.expected_all else True

    if matched_any and matched_all:
        out.verdict = "PASS"
    else:
        out.fail_reason = "SAW_NO"  # gateway accepted but image didn't reach model
    return out


# ─────────────────────────────────────────────────────────────
# Pretty printers
# ─────────────────────────────────────────────────────────────

def print_outcome(o: RunOutcome) -> None:
    sym = "✅" if o.verdict == "PASS" else "❌"
    head = f"  {sym} {o.case_name:<32s}  status={o.status}  {o.elapsed_ms}ms"
    if o.verdict == "PASS":
        print(f"{head}    reply='{o.reply_preview}'")
        return
    extras = [o.fail_reason]
    if o.error:
        extras.append(f"err={o.error}")
    print(f"{head}    [{', '.join(extras)}]")
    if o.reply_preview:
        print(f"      reply : {o.reply_preview}")
    if o.fail_reason in ("HTTP_400", "HTTP_422", "BAD_JSON"):
        print(f"      sent  : {o.request_preview}")


def print_matrix(outcomes: list[RunOutcome], cases: list[FormatCase],
                 models: list[str]) -> None:
    print()
    print("=" * 100)
    print("SUMMARY MATRIX  ( ✅ = format works for that model | ❌ = does not )")
    print("=" * 100)
    name_w = max(len(c.name) for c in cases) + 2
    by_key = {(o.model, o.case_name): o for o in outcomes}
    # Header: model column headers (model id is long; show short tail)
    short = [m.split("::")[-1] if "::" in m else m for m in models]
    col_w = max(max(len(s) for s in short), 6)
    header = " " * name_w + "  " + "  ".join(s.ljust(col_w) for s in short)
    print(header)
    for case in cases:
        row = [case.name.ljust(name_w)]
        for model in models:
            o = by_key.get((model, case.name))
            if o is None:
                cell = "skip".ljust(col_w)
            elif o.verdict == "PASS":
                cell = "PASS".ljust(col_w)
            else:
                cell = (o.fail_reason or "FAIL")[:col_w].ljust(col_w)
            row.append(cell)
        print("  ".join(row))
    print("=" * 100)


def print_grand_totals(outcomes: list[RunOutcome]) -> None:
    total = len(outcomes)
    passes = sum(1 for o in outcomes if o.verdict == "PASS")
    print(f"\nTOTAL: {passes}/{total} cells PASS "
          f"({(100.0 * passes / total) if total else 0:.0f}%)")
    by_case: dict[str, list[RunOutcome]] = {}
    for o in outcomes:
        by_case.setdefault(o.case_name, []).append(o)
    print("\nPer-format pass rate (across all probed models):")
    for name, items in by_case.items():
        p = sum(1 for o in items if o.verdict == "PASS")
        print(f"  {name:<32s}  {p}/{len(items)}")


# ─────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser(
        description="Probe which image-input formats the Sourcegraph LLM gateway accepts.",
    )
    ap.add_argument("--url", required=True, help="Sourcegraph base URL (e.g. https://sg.example.com)")
    ap.add_argument("--token", required=True, help="Sourcegraph access token (sgp_...)")
    ap.add_argument("--models", default="",
                    help="Comma-separated model IDs. Defaults to a curated vision-likely list.")
    ap.add_argument("--discover", action="store_true",
                    help="Fetch every model from /.api/llm/models and probe all of them.")
    ap.add_argument("--only", default="",
                    help="Run only these format cases (comma-separated names; see --list).")
    ap.add_argument("--list", action="store_true", help="List all format cases and exit.")
    ap.add_argument("--no-url-tests", action="store_true",
                    help="Skip cases that require the gateway to fetch a public URL.")
    ap.add_argument("--image-url", default=DEFAULT_REMOTE_IMAGE_URL,
                    help=f"Public image URL for URL-source tests (default: {DEFAULT_REMOTE_IMAGE_URL}).")
    ap.add_argument("--image-url-keyword", default=",".join(DEFAULT_REMOTE_KEYWORDS),
                    help="Comma-separated keywords expected in the model's reply for the URL image.")
    ap.add_argument("--max-tokens", type=int, default=64)
    ap.add_argument("--timeout", type=int, default=60)
    ap.add_argument("--no-verify", action="store_true", help="Disable TLS verification.")
    ap.add_argument("--out", default="vision_lab_results.json")
    args = ap.parse_args()

    remote_keywords = [k.strip().lower() for k in args.image_url_keyword.split(",") if k.strip()]
    cases = build_cases(args.image_url, remote_keywords)

    if args.list:
        for c in cases:
            print(f"  {c.name:<32s}  {c.description}")
            if c.notes:
                print(f"      note: {c.notes}")
        return 0

    if args.only:
        wanted = {n.strip() for n in args.only.split(",") if n.strip()}
        unknown = wanted - {c.name for c in cases}
        if unknown:
            print(f"ERROR: unknown case names: {sorted(unknown)}", file=sys.stderr)
            return 2
        cases = [c for c in cases if c.name in wanted]

    if args.no_url_tests:
        cases = [c for c in cases if not c.needs_url]

    client = SourcegraphClient(args.url, args.token, verify=not args.no_verify,
                               timeout=args.timeout)

    if args.discover:
        try:
            models = client.list_models()
        except requests.RequestException as e:
            print(f"ERROR: failed to list models from gateway: {e}", file=sys.stderr)
            return 3
    elif args.models:
        models = [m.strip() for m in args.models.split(",") if m.strip()]
    else:
        models = DEFAULT_MODELS

    print(f"Sourcegraph URL : {_mask_url(args.url)}")
    print(f"Models          : {len(models)} {'(discovered)' if args.discover else ''}")
    for m in models:
        print(f"  - {m}")
    print(f"Format cases    : {len(cases)}")
    if any(c.needs_url for c in cases):
        print(f"URL test image  : {args.image_url}  (keywords: {remote_keywords})")
    print(f"Test images     : red 16x16 PNG ({len(RED_PNG)} B), blue 16x16 PNG ({len(BLUE_PNG)} B)")
    print()

    outcomes: list[RunOutcome] = []
    for model in models:
        print(f"── {model} ─────────────────────────────────────")
        for case in cases:
            o = run_case(client, model, case, args.max_tokens)
            outcomes.append(o)
            print_outcome(o)
        print()

    print_matrix(outcomes, cases, models)
    print_grand_totals(outcomes)

    out_path = Path(args.out)
    payload = {
        "url": _mask_url(args.url),
        "models": models,
        "cases": [{"name": c.name, "description": c.description,
                   "needs_url": c.needs_url, "notes": c.notes,
                   "expected_any": c.expected_any, "expected_all": c.expected_all}
                  for c in cases],
        "remote_image_url": args.image_url,
        "remote_keywords": remote_keywords,
        "outcomes": [asdict(o) for o in outcomes],
    }
    out_path.write_text(json.dumps(payload, indent=2))
    print(f"\nWrote {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
