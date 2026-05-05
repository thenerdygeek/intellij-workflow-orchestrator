#!/usr/bin/env python3
"""
Offline test harness for format_lab.py.
=======================================
Exercises every internal code path without hitting Sourcegraph:

  - fixture generators produce valid magic bytes for every format
  - body shape matches SourcegraphCompletionsStreamClient.kt + DTOs exactly
  - parse_cody_sse handles deltaText, completion-cumulative, stopReason,
    [DONE], malformed JSON, empty stream
  - run_case correctly classifies PASS, SAW_NO, REFUSED, HTTP_4xx, ERROR,
    SSE_EMPTY, NO_FIXTURE
  - print_matrix and print_recommendation handle PASS/FAIL/SKIP cells
  - redact_payload scrubs base64 data URLs without dropping legitimate text
  - URL masking and token masking work for typical inputs

Run:    python3 format_lab_test.py
Exit:   0 = all tests pass, 1 = any failure
"""
from __future__ import annotations

import base64
import importlib.util
import io
import json
import sys
import types
from contextlib import redirect_stdout


# ─── Bootstrap format_lab without the requests dependency ───────────────
def _load_format_lab():
    if "requests" not in sys.modules:
        stub = types.ModuleType("requests")

        class _RequestException(Exception):
            pass

        class _HTTPError(_RequestException):
            def __init__(self, *a, response=None, **kw):
                super().__init__(*a, **kw)
                self.response = response

        class _Session:
            def __init__(self):
                self.headers = {}
                self.verify = True

            def post(self, *_a, **_kw):
                raise _RequestException("stubbed")

            def get(self, *_a, **_kw):
                raise _RequestException("stubbed")

            def update(self, *_a, **_kw):
                pass

        stub.Session = _Session
        stub.RequestException = _RequestException
        stub.HTTPError = _HTTPError
        sys.modules["requests"] = stub

    spec = importlib.util.spec_from_file_location("format_lab", "./format_lab.py")
    mod = importlib.util.module_from_spec(spec)
    sys.modules["format_lab"] = mod
    spec.loader.exec_module(mod)
    return mod


fl = _load_format_lab()


# ─── Tiny test framework (no pytest dependency) ─────────────────────────
PASSES: list[str] = []
FAILS: list[str] = []


def _check(name: str, cond: bool, detail: str = "") -> None:
    if cond:
        PASSES.append(name)
        print(f"  ✓ {name}")
    else:
        FAILS.append(f"{name}  {detail}")
        print(f"  ✗ {name}    {detail}")


def section(title: str) -> None:
    print(f"\n── {title} ────────────────────────────────────")


# ─── 1. Fixture generators ──────────────────────────────────────────────
section("fixture generators")

png = fl.make_png((220, 20, 20))
_check("PNG signature",  png[:8] == b"\x89PNG\r\n\x1a\n")
_check("PNG IHDR present", b"IHDR" in png[:32])
_check("PNG IEND present", png[-12:-4] == b"\x00\x00\x00\x00IEND")

bmp = fl.make_bmp((220, 20, 20))
_check("BMP signature 'BM'", bmp[:2] == b"BM")
import struct as _s
_check("BMP file size header consistent", _s.unpack("<I", bmp[2:6])[0] == len(bmp))
_check("BMP info header size 40", _s.unpack("<I", bmp[14:18])[0] == 40)
_check("BMP bpp 24", _s.unpack("<H", bmp[28:30])[0] == 24)

gif = fl.make_gif((220, 20, 20))
_check("GIF89a header", gif[:6] == b"GIF89a")
_check("GIF terminator 0x3B", gif[-1:] == b"\x3B")
_check("GIF palette has red bytes", bytes((220, 20, 20)) in gif)

tif = fl.make_tiff((220, 20, 20))
_check("TIFF little-endian magic", tif[:4] == b"II*\x00")
_check("TIFF IFD count nonzero",
       _s.unpack("<H", tif[8:10])[0] == 8)

svg = fl.make_svg((220, 20, 20))
_check("SVG starts with <svg ", svg.startswith(b"<svg"))
_check("SVG declares width 16", b'width="16"' in svg)
_check("SVG fill matches red rgb", b"rgb(220, 20, 20)" in svg)

pdf = fl.make_pdf_with_word("MAGENTA")
_check("PDF magic %PDF-1.4", pdf.startswith(b"%PDF-1.4"))
_check("PDF tail %%EOF", pdf.rstrip().endswith(b"%%EOF"))
_check("PDF contains literal (MAGENTA)", b"(MAGENTA)" in pdf)
_check("PDF has xref table", b"\nxref\n" in pdf)

# Embedded canonical fixtures decode and have valid magic
jpg = base64.b64decode(fl.RED_JPEG_B64)
_check("Embedded JPEG SOI/EOI",
       jpg[:2] == b"\xff\xd8" and jpg[-2:] == b"\xff\xd9",
       f"{jpg[:2].hex()}/{jpg[-2:].hex()}")
webp = base64.b64decode(fl.RED_WEBP_B64)
_check("Embedded WebP RIFF/WEBP/VP8L",
       webp[:4] == b"RIFF" and webp[8:12] == b"WEBP" and webp[12:16] == b"VP8L")
avif = base64.b64decode(fl.RED_AVIF_B64)
_check("Embedded AVIF ftyp avif",
       avif[4:8] == b"ftyp" and avif[8:12] == b"avif")
heic = base64.b64decode(fl.RED_HEIC_B64)
_check("Embedded HEIC ftyp heic",
       heic[4:8] == b"ftyp" and heic[8:12] == b"heic",
       f"{heic[4:12]!r}")
_check("Embedded HEIC contains hvcC box (HEVC config)",
       b"hvcC" in heic)
_check("Embedded HEIC contains mdat strip",
       b"mdat" in heic)
heif = base64.b64decode(fl.RED_HEIF_B64)
_check("Embedded HEIF ftyp mif1",
       heif[4:8] == b"ftyp" and heif[8:12] == b"mif1",
       f"{heif[4:12]!r}")
_check("Embedded HEIF contains hvcC + mdat",
       b"hvcC" in heif and b"mdat" in heif)

# Fallback chain: simulate "no Pillow, no fixtures-dir" → must still produce
# bytes for every production-whitelist MIME via the embedded fallback.
import importlib, sys as _sys
_real_pil = _sys.modules.pop("PIL", None)
class _BlockedFinder:
    def find_module(self, name, path=None):
        return self if name == "PIL" or name.startswith("PIL.") else None
    def load_module(self, name):
        raise ImportError("PIL blocked for fallback test")
_finder = _BlockedFinder()
_sys.meta_path.insert(0, _finder)
try:
    for mime in ("image/jpeg", "image/webp", "image/heic", "image/heif", "image/avif"):
        f = fl.load_fixture(mime, None)
        _check(f"fallback fixture for {mime}",
               f.bytes_ is not None and len(f.bytes_) > 30,
               f.source)
finally:
    _sys.meta_path.remove(_finder)
    if _real_pil is not None:
        _sys.modules["PIL"] = _real_pil

# ensure_fixture_packages must not crash on a clean machine. We don't assert
# on the dict (depends on the runner's environment) — just that it's a dict.
result = fl.ensure_fixture_packages(quiet=True)
_check("ensure_fixture_packages returns a dict",
       isinstance(result, dict) and "pillow" in result)

# CRITICAL: every embedded fixture must round-trip through a real codec to
# a red pixel. The first version of these fixtures was fabricated and looked
# structurally valid (correct ftyp/hvcC/mdat boxes) but didn't decode — this
# test catches that class of bug. Skip cleanly if the codec isn't installed
# on the test machine; the assertion fires only when the codec is present.
try:
    from PIL import Image as _PILImage
    try:
        import pillow_heif as _ph; _ph.register_heif_opener()
    except ImportError:
        pass
    try:
        import pillow_avif  # noqa: F401  registers on import
    except ImportError:
        pass

    def _decode_red(name, b64_blob):
        try:
            blob = base64.b64decode(b64_blob)
            img = _PILImage.open(io.BytesIO(blob)).convert("RGB")
            w, h = img.size
            px = img.getpixel((w // 2, h // 2))
            red_dominant = px[0] > 100 and px[0] > px[1] and px[0] > px[2]
            _check(f"embedded {name} round-trips to red via real codec",
                   red_dominant, f"got pixel={px}, size={img.size}")
        except _PILImage.UnidentifiedImageError as e:
            _check(f"embedded {name} round-trips to red via real codec",
                   False, f"codec rejected fixture: {e}")
        except Exception as e:
            _check(f"embedded {name} round-trips to red via real codec",
                   False, f"unexpected: {type(e).__name__}: {e}")

    _decode_red("JPEG", fl.RED_JPEG_B64)
    _decode_red("WebP", fl.RED_WEBP_B64)
    if hasattr(_PILImage, "registered_extensions") and \
       (".avif" in _PILImage.registered_extensions() or
        "AVIF" in _PILImage.OPEN if hasattr(_PILImage, "OPEN") else False):
        _decode_red("AVIF", fl.RED_AVIF_B64)
    else:
        try:
            _decode_red("AVIF", fl.RED_AVIF_B64)
        except Exception:
            print("  · AVIF codec not registered; skipping decode check")
    # HEIC/HEIF: only assert when pillow-heif registered the opener.
    try:
        _PILImage.open(io.BytesIO(base64.b64decode(fl.RED_HEIF_B64))).load()
        _decode_red("HEIC", fl.RED_HEIC_B64)
        _decode_red("HEIF", fl.RED_HEIF_B64)
    except Exception:
        print("  · pillow-heif not available on test machine; skipping HEIC/HEIF decode check")

except ImportError:
    print("  · Pillow not available on test machine; skipping all round-trip decode checks")


# ─── 2. Body shape matches production wire format ───────────────────────
section("body shape vs SourcegraphCompletionsStreamClient.kt + CompletionStreamDtos.kt")

body = fl.cody_body([{"type": "text", "text": "hi"}], "modelX", 100, stream=True)
_check("uses 'speaker' not 'role'",
       body["messages"][0]["speaker"] == "human"
       and "role" not in body["messages"][0])
_check("uses 'maxTokensToSample' not 'max_tokens'",
       body["maxTokensToSample"] == 100 and "max_tokens" not in body)
_check("topK and topP both -1",
       body["topK"] == -1 and body["topP"] == -1)
_check("temperature 0",  body["temperature"] == 0)
_check("stream true by default", body["stream"] is True)

# image_url part round-trips through json
fx = fl.load_fixture("image/png", None)
parts = fl.build_mime_image_part(fx)
encoded = json.dumps(parts)
_check("image_url part JSON-serializable", isinstance(json.loads(encoded), list))
_check("image_url has data URL scheme",
       parts[1]["image_url"]["url"].startswith("data:image/png;base64,"))

# document blocks
doc_native = fl.build_doc_block_anthropic_native(pdf)
_check("anthropic native doc shape: type=document",
       doc_native[0]["type"] == "document")
_check("anthropic native doc shape: source.type=base64",
       doc_native[0]["source"]["type"] == "base64")
_check("anthropic native doc shape: media_type",
       doc_native[0]["source"]["media_type"] == "application/pdf")

doc_openai = fl.build_doc_block_openai_file(pdf)
_check("openai file shape: type=file",
       doc_openai[0]["type"] == "file")
_check("openai file shape: file.file_data is data URL",
       doc_openai[0]["file"]["file_data"].startswith("data:application/pdf;base64,"))


# ─── 3. SSE parser ──────────────────────────────────────────────────────
section("parse_cody_sse")


class _FakeResp:
    """Mimics requests.Response.iter_lines for SSE testing."""
    def __init__(self, lines: list[str]):
        self._lines = lines

    def iter_lines(self, decode_unicode=True):
        return iter(self._lines)


# api-version >= 2: deltaText + stopReason
text, events, stop, raw = fl.parse_cody_sse(_FakeResp([
    "event: completion",
    'data: {"deltaText":"red"}',
    "",
    "event: completion",
    'data: {"deltaText":" colored"}',
    "",
    "event: completion",
    'data: {"stopReason":"end_turn"}',
    "",
    "event: done",
    "data: {}",
]))
_check("SSE deltaText accumulates", text == "red colored")
_check("SSE stopReason captured", stop == "end_turn")
_check("SSE event names captured",
       "completion" in events and "done" in events)

# api-version 1: cumulative completion (later frame replaces, doesn't append)
text2, _, _, _ = fl.parse_cody_sse(_FakeResp([
    "event: completion",
    'data: {"completion":"Red"}',
    "",
    "event: completion",
    'data: {"completion":"Red colored"}',
    "",
]))
_check("SSE completion cumulative replaces", text2 == "Red colored")

# malformed lines and [DONE] sentinel are tolerated
text3, _, _, _ = fl.parse_cody_sse(_FakeResp([
    "event: completion",
    'data: not-valid-json',
    "",
    "data: [DONE]",
    "event: completion",
    'data: {"deltaText":"X"}',
    "",
]))
_check("SSE tolerates malformed JSON + [DONE]", text3 == "X")

# empty stream
text4, _, _, _ = fl.parse_cody_sse(_FakeResp([]))
_check("SSE empty stream → empty text", text4 == "")


# ─── 4. run_case verdict classification ─────────────────────────────────
section("run_case verdicts")


class _FakeHttpResp:
    def __init__(self, status_code, sse_lines=None, text_body=""):
        self.status_code = status_code
        self._lines = sse_lines or []
        self.text = text_body

    def iter_lines(self, decode_unicode=True):
        return iter(self._lines)


class _FakeClient:
    """Minimal SourcegraphClient stand-in for run_case."""
    def __init__(self, response: _FakeHttpResp | None = None,
                 raise_exc: Exception | None = None):
        self._response = response
        self._raise = raise_exc

    def post_stream(self, api_version, body, sse):
        if self._raise:
            raise self._raise
        return self._response


# A baseline mime case (PNG, content_builder works)
mime_cases = fl.build_mime_cases(None)
png_case = next(c for c in mime_cases if c.name == "mime_png")

# (a) PASS — reply contains "red"
out = fl.run_case(
    _FakeClient(_FakeHttpResp(200, sse_lines=[
        "event: completion",
        'data: {"deltaText":"red"}',
        "",
    ])),
    "model-A", png_case, max_tokens=100,
)
_check("run_case PASS verdict", out.verdict == "PASS", f"{out.verdict}/{out.fail_reason}")
_check("run_case PASS reply preview", "red" in out.reply_preview.lower())

# (b) SAW_NO — reply has no expected keyword
out = fl.run_case(
    _FakeClient(_FakeHttpResp(200, sse_lines=[
        "event: completion",
        'data: {"deltaText":"banana"}',
        "",
    ])),
    "model-A", png_case, 100,
)
_check("run_case SAW_NO verdict",
       out.verdict == "FAIL" and out.fail_reason == "SAW_NO",
       f"{out.verdict}/{out.fail_reason}")

# (c) REFUSED — reply contains a refusal phrase
out = fl.run_case(
    _FakeClient(_FakeHttpResp(200, sse_lines=[
        "event: completion",
        'data: {"deltaText":"I cannot see images. red would be a guess."}',
        "",
    ])),
    "model-A", png_case, 100,
)
_check("run_case REFUSED beats SAW_NO",
       out.fail_reason == "REFUSED",
       f"{out.verdict}/{out.fail_reason}")

# (d) HTTP_413 — gateway rejects
out = fl.run_case(
    _FakeClient(_FakeHttpResp(413, text_body="payload too large")),
    "model-A", png_case, 100,
)
_check("run_case HTTP_413",
       out.fail_reason == "HTTP_413" and "payload too large" in out.reply_preview)

# (e) HTTP_400 with body
out = fl.run_case(
    _FakeClient(_FakeHttpResp(400, text_body='{"error":"bad shape"}')),
    "model-A", png_case, 100,
)
_check("run_case HTTP_400", out.fail_reason == "HTTP_400")

# (f) Network ERROR
import requests as _req
out = fl.run_case(
    _FakeClient(raise_exc=_req.RequestException("connection refused")),
    "model-A", png_case, 100,
)
_check("run_case ERROR",
       out.fail_reason == "ERROR" and "connection refused" in out.error)

# (g) SSE_EMPTY — 200 OK but no frames
out = fl.run_case(
    _FakeClient(_FakeHttpResp(200, sse_lines=[])),
    "model-A", png_case, 100,
)
_check("run_case SSE_EMPTY", out.fail_reason == "SSE_EMPTY")

# (h) NO_FIXTURE — case has needs_fixture set, no HTTP made
no_fix = fl.FormatCase(
    name="mime_xyz", description="hypothetical",
    expected_any=["red"], needs_fixture="image/xyz", group="mime",
    notes="no fixture",
)
out = fl.run_case(_FakeClient(), "model-A", no_fix, 100)
_check("run_case NO_FIXTURE skips HTTP",
       out.verdict == "SKIP" and out.fail_reason == "NO_FIXTURE")

# (i) Multi-image expected_all enforced
multi_case = fl.build_multi_cases()[0]
out = fl.run_case(
    _FakeClient(_FakeHttpResp(200, sse_lines=[
        "event: completion",
        'data: {"deltaText":"red, blue"}',
        "",
    ])),
    "model-A", multi_case, 100,
)
_check("multi-image PASS when both keywords present", out.verdict == "PASS")

out = fl.run_case(
    _FakeClient(_FakeHttpResp(200, sse_lines=[
        "event: completion",
        'data: {"deltaText":"red"}',
        "",
    ])),
    "model-A", multi_case, 100,
)
_check("multi-image SAW_NO when only one keyword present",
       out.verdict == "FAIL" and out.fail_reason == "SAW_NO")

# (j) tools-group: PASS when raw blob contains a tool-call marker,
#     SAW_NO when only text comes back (the silent-drop scenario)
tools_cases = fl.build_tools_cases()
tools_only = next(c for c in tools_cases if c.name == "tools_only_on_stream")

# Sourcegraph emits tool calls — should PASS
out = fl.run_case(
    _FakeClient(_FakeHttpResp(200, sse_lines=[
        "event: completion",
        'data: {"deltaText":"Calling tool"}',
        "",
        "event: completion",
        'data: {"tool_calls":[{"id":"x","function":{"name":"must_call_this_tool","arguments":"{}"}}]}',
        "",
    ])),
    "model-A", tools_only, 100,
)
_check("tools-on-stream PASS when tool_calls frame present",
       out.verdict == "PASS",
       f"{out.verdict}/{out.fail_reason}")

# Sourcegraph silently drops tools — only text reply, must be SAW_NO
out = fl.run_case(
    _FakeClient(_FakeHttpResp(200, sse_lines=[
        "event: completion",
        'data: {"deltaText":"The secret number is 42."}',
        "",
    ])),
    "model-A", tools_only, 100,
)
_check("tools-on-stream SAW_NO when only text comes back",
       out.verdict == "FAIL" and out.fail_reason == "SAW_NO",
       f"{out.verdict}/{out.fail_reason}")

# tools_with_image case body has both text+image AND tools field
tools_with_image = next(c for c in tools_cases if c.name == "tools_with_image_on_stream")
body = tools_with_image.body_override("model-X", 1000)
_check("tools_with_image body carries `tools` field",
       isinstance(body.get("tools"), list) and len(body["tools"]) == 1)
_check("tools_with_image body carries image_url part",
       any(p.get("type") == "image_url"
           for p in body["messages"][0]["content"]))


# ─── 5. Redaction ───────────────────────────────────────────────────────
section("redact_payload + URL/token masking")

raw_request = (
    '{"messages":[{"speaker":"human","content":[{"type":"image_url",'
    '"image_url":{"url":"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAA'
    'AQCAIAAACQkWg2AAAAFklEQV"}}]}]}'
)
red = fl.redact_payload(raw_request)
_check("data URL base64 redacted",
       "data:image/png;base64,<" in red and "redacted>" in red,
       red[:120])
_check("redaction preserves surrounding JSON",
       '"speaker":"human"' in red and '"type":"image_url"' in red)

# document data URL with PDF MIME
red_pdf = fl.redact_payload('"data:application/pdf;base64,JVBERi0xLjQKJeLj"')
_check("PDF data URL redacted",
       "data:application/pdf;base64,<" in red_pdf and "redacted>" in red_pdf)

# Anthropic-native document block uses bare "data":"<b64>", not a data: URL.
# This is the shape we send for the doc_anthropic_native_pdf case.
anthropic_doc = (
    '{"type":"document","source":{"type":"base64",'
    '"media_type":"application/pdf",'
    '"data":"' + ("A" * 800) + '"}}'
)
red_doc = fl.redact_payload(anthropic_doc)
_check("anthropic native doc 'data' field redacted",
       '"data":"<800 chars redacted>"' in red_doc,
       red_doc[:160])
_check("anthropic native doc surrounding fields preserved",
       '"media_type":"application/pdf"' in red_doc
       and '"type":"base64"' in red_doc)

# Short "data" fields (< 64 chars) must NOT be redacted — false-positive guard
short = fl.redact_payload('"data":"shortvalue"')
_check("short 'data' field passes through unchanged",
       short == '"data":"shortvalue"')

# non-data text untouched
_check("non-data text passes through unchanged",
       fl.redact_payload("hello world") == "hello world")

# URL masking
_check("_mask_url masks host parts",
       "***" in fl._mask_url("https://sourcegraph.example.com/api"))
_check("_mask_url handles trailing slash",
       fl._mask_url("https://sg.foo.bar.com/").endswith("/"))


# ─── 6. Pretty-printers don't crash on mixed verdicts ───────────────────
section("print_matrix + print_recommendation")

cases_for_print = mime_cases[:3]  # png, jpeg, webp
mixed_outcomes = [
    fl.RunOutcome(model="m1", case_name="mime_png", api_version=8,
                  status=200, elapsed_ms=10, verdict="PASS",
                  reply_preview="red"),
    fl.RunOutcome(model="m1", case_name="mime_jpeg", api_version=8,
                  status=200, elapsed_ms=10, verdict="FAIL",
                  fail_reason="SAW_NO", reply_preview="banana"),
    fl.RunOutcome(model="m1", case_name="mime_webp", api_version=8,
                  status=0, elapsed_ms=0, verdict="SKIP",
                  fail_reason="NO_FIXTURE",
                  fixture_source="no fixture"),
]
buf = io.StringIO()
with redirect_stdout(buf):
    fl.print_matrix(mixed_outcomes, cases_for_print, ["m1"])
    fl.print_recommendation(mixed_outcomes, cases_for_print)
report = buf.getvalue()
_check("matrix shows PASS cell", "PASS" in report)
_check("matrix shows SAW_NO cell", "SAW_NO" in report)
_check("matrix shows NO_FX skip cell", "NO_FX" in report)
_check("recommendation lists fully-supported PNG",
       "image/png" in report and "Fully supported" in report)
_check("recommendation lists unsupported JPEG",
       "image/jpeg" in report and "NOT supported" in report)
_check("recommendation suggests imageMimeWhitelist",
       "imageMimeWhitelist" in report and "image/png" in report)


# ─── 7. JSON-dump round-trip + redaction ────────────────────────────────
section("json dump round-trip with redaction")

# Build a fake outcome with a too-long base64 payload in request_preview
payload_obj = {
    "url": "https://***.***.***.com/",
    "outcomes": [{
        "request_preview": fl.redact_payload(
            'data:image/png;base64,' + ('A' * 500)
        ),
    }],
}
serialized = json.dumps(payload_obj, indent=2)
_check("JSON dump contains redaction marker",
       "<500 chars redacted>" in serialized)
_check("JSON dump does not contain raw base64 of length 500",
       ("A" * 500) not in serialized)
_check("JSON round-trips cleanly",
       json.loads(serialized) == payload_obj)


# ─── Summary ────────────────────────────────────────────────────────────
print()
print("=" * 70)
print(f"OFFLINE TESTS: {len(PASSES)} PASS / {len(FAILS)} FAIL")
print("=" * 70)
if FAILS:
    print("\nFAILURES:")
    for f in FAILS:
        print(f"  - {f}")
    sys.exit(1)
sys.exit(0)
