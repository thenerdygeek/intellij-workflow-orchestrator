#!/usr/bin/env python3
"""
Sourcegraph Format Lab
======================
Companion to ``vision_lab.py``. Where ``vision_lab.py`` proved that the
Cody-stream wire format works (24/24 PASS with PNG payloads on the user's
6.12.x instance), this script answers the question that probe never asked:

    "Which raster MIME types and document-block shapes does Sourcegraph
     actually round-trip end-to-end on /.api/completions/stream?"

WHY THIS EXISTS
---------------
Production code in this repo advertises a 5-MIME whitelist sourced from the
Cody web UI:

    image/png · image/jpeg · image/webp · image/heic · image/heif

  - core/.../settings/PluginSettings.kt:290  (user-paste default)
  - agent/webview/.../InputBar.tsx:30        (JS default)

That whitelist has never been verified end-to-end against the user's
Sourcegraph instance. Only PNG was actually sent in any prior probe. This
script settles, per-MIME and per-model, whether the bytes reach the upstream
provider or get silently dropped / 400'd by the gateway.

In addition, it probes:
  - Anthropic-native document blocks for PDF (potential native-PDF path
    that would replace Tika extraction for /agent's read_document tool).
  - api-version ceiling (client-config exposes 9 on the user's instance,
    but the agent's default is still 8).
  - Payload-size cliff (where does the gateway start rejecting?).
  - Multi-image messages on the stream endpoint.

USAGE
-----
Requirements: Python 3.9+ and the ``requests`` package. No other hard deps.

    pip install requests

Optional encoder dependencies (only needed if you don't pass --fixtures-dir):
    pip install pillow                    # JPEG, WebP, TIFF, GIF, BMP
    pip install pillow-heif               # HEIC, HEIF
    pip install pillow-avif-plugin        # AVIF

Examples:
    py -3 format_lab.py --url https://sg.example.com --token sgp_xxx
    py -3 format_lab.py --url ... --token ... --only mime_jpeg,mime_webp
    py -3 format_lab.py --url ... --token ... --api-version-sweep
    py -3 format_lab.py --url ... --token ... --size-sweep
    py -3 format_lab.py --url ... --token ... --fixtures-dir ./fixtures

Common flags:
    --list                     enumerate every case and exit
    --only <names>             comma-separated case names
    --models <ids>             comma-separated model IDs (default: discover
                               vision-capable from modelconfig)
    --fixtures-dir <path>      directory containing user-provided fixtures.
                               Expected filenames: red.jpg, red.webp,
                               red.heic, red.heif, red.gif, red.bmp,
                               red.tiff, red.avif, red.svg, magenta.pdf
    --api-version-sweep        also run the api-version (1/2/8/9/10) matrix
    --size-sweep               also run the payload-size matrix
    --no-verify                disable TLS verification
    --timeout <s>              per-request timeout (default 60)
    --out <path>               JSON dump path (default: format_lab_results.json)

INTERPRETING THE OUTPUT
-----------------------
    PASS         HTTP 200 + SSE returned the expected color/marker keyword.
                 Use this format / shape in production.
    SAW_NO       HTTP 200 but the reply lacks the keyword (or refused).
                 Gateway accepted bytes but provider DID NOT see the image.
                 Don't use this format.
    REFUSED      Model said "I cannot see" / "no image".
    HTTP_4xx     Gateway rejected the shape outright.
    NO_FIXTURE   No bytes available for this format. Provide via
                 --fixtures-dir or install the optional encoder package.
                 Marked SKIPPED in the matrix.
    ERROR        Network / TLS / decode failure. Re-run.

This script INTENTIONALLY does not invent bytes for formats it cannot
encode in pure stdlib. The user warned against guessing field values; this
extends to fixture bytes — guessing a malformed JPEG looks the same on the
wire as a real one but produces garbage results. When the script can't
produce a verifiable fixture for a format, it emits NO_FIXTURE and tells
you what to do.

WIRE FORMAT (mirrors core/.../SourcegraphCompletionsStreamClient.kt:117)
------------------------------------------------------------------------
    POST {base}/.api/completions/stream?api-version={N}
    Authorization: token {sgp_...}
    Content-Type: application/json; charset=utf-8
    Accept: text/event-stream

    body = {
        "model": "anthropic::2024-10-22::claude-...",
        "messages": [{
            "speaker": "human",                       # NOT "role"
            "content": [
                {"type": "text", "text": "..."},
                {"type": "image_url",
                 "image_url": {"url": "data:<mime>;base64,..."}},
            ],
        }],
        "maxTokensToSample": 10000,                   # NOT "max_tokens"
        "temperature": 0,
        "stream": true,
        "topK": -1,
        "topP": -1,
    }

SSE response: ``event: completion`` then ``data: {"deltaText":"..."}``
(api-version >= 2) or ``data: {"completion":"<full>"}`` (api-version 1).
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

# Force UTF-8 stdout/stderr on Windows — the gateway sometimes echoes
# unicode characters that charmap-encoded streams refuse.
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


# ─────────────────────────────────────────────────────────────
# Endpoints (verbatim from production code)
# ─────────────────────────────────────────────────────────────
STREAM_PATH = "/.api/completions/stream"
CLIENT_CONFIG_PATH = "/.api/client-config"
MODEL_CATALOG_PATH = "/.api/modelconfig/supported-models.json"

# Sourcegraph 6.2.0+ uses 8; 6.12.x advertises 9 via client-config. We probe
# both by default; api-version-sweep adds 1/2/10 for completeness.
DEFAULT_API_VERSION = 8


# ─────────────────────────────────────────────────────────────
# Prompts + keyword sets
# ─────────────────────────────────────────────────────────────
PROMPT_COLOR = "What is the dominant color of this image? Reply with just one word."
PROMPT_TWO_COLORS = (
    "Two images follow. Reply with the dominant color of each, in order, "
    "comma-separated. Just the two words."
)
PROMPT_PDF = (
    "A PDF document is attached. Read its text content and reply with the "
    "single word that appears in the document. Just the word."
)

RED_KEYWORDS = ["red", "crimson", "scarlet", "ruby", "maroon"]
BLUE_KEYWORDS = ["blue", "azure", "navy", "cobalt", "indigo"]
PDF_KEYWORDS = ["magenta"]   # the literal word baked into the test PDF

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
    "i cannot read",
    "no document",
    "no pdf",
    "cannot access",
]


# ─────────────────────────────────────────────────────────────
# Pure-stdlib fixture generators (PNG, BMP, GIF, TIFF, SVG, PDF)
# ─────────────────────────────────────────────────────────────

def b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def _png_chunk(typ: bytes, data: bytes) -> bytes:
    chunk = typ + data
    crc = zlib.crc32(chunk) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + chunk + struct.pack(">I", crc)


def make_png(rgb: tuple[int, int, int], size: int = 16) -> bytes:
    """N×N solid-color PNG with raw RGB pixels (no filtering, color type 2)."""
    width = height = size
    ihdr = _png_chunk(
        b"IHDR",
        struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0),
    )
    raw = b""
    pixel_row = bytes(rgb) * width
    for _ in range(height):
        raw += b"\x00" + pixel_row     # filter byte 0 (None)
    idat = _png_chunk(b"IDAT", zlib.compress(raw, 9))
    iend = _png_chunk(b"IEND", b"")
    return b"\x89PNG\r\n\x1a\n" + ihdr + idat + iend


def make_bmp(rgb: tuple[int, int, int], size: int = 16) -> bytes:
    """N×N solid-color BMP, 24bpp BI_RGB. Rows are padded to 4 bytes and
    stored bottom-up (the BMP convention). All values little-endian."""
    width = height = size
    row_unpadded = (rgb[2], rgb[1], rgb[0]) * width   # BMP is BGR
    row_bytes = bytes(row_unpadded)
    pad = (-len(row_bytes)) % 4
    row_bytes += b"\x00" * pad
    pixel_data = row_bytes * height
    file_header = b"BM" + struct.pack("<IHHI", 14 + 40 + len(pixel_data),
                                      0, 0, 14 + 40)
    info_header = struct.pack("<IIIHHIIIIII",
                              40, width, height, 1, 24, 0,
                              len(pixel_data), 2835, 2835, 0, 0)
    return file_header + info_header + pixel_data


def make_gif(rgb: tuple[int, int, int]) -> bytes:
    """1×1 GIF89a with a global 2-color palette (rgb + black). Hand-rolled.
    LZW data for a single-pixel index 0: 0x00 0x01 0x00 (clear, idx0, EOI)
    becomes after LZW packing: minimum-code-size=2, packed bytes 0x4C 0x01
    0x00, terminator 0x00."""
    header = b"GIF89a"
    lsd = struct.pack("<HHBBB", 1, 1, 0xF0, 0, 0)        # 1×1, 2-entry palette
    palette = bytes(rgb) + b"\x00\x00\x00"
    image_descriptor = b"\x2C" + struct.pack("<HHHH", 0, 0, 1, 1) + b"\x00"
    lzw_min = b"\x02"
    sub_block = b"\x03\x44\x01\x00"                       # 3 bytes, then 0-block
    image_data = lzw_min + sub_block + b"\x00"
    trailer = b"\x3B"
    return header + lsd + palette + image_descriptor + image_data + trailer


def make_tiff(rgb: tuple[int, int, int]) -> bytes:
    """1×1 RGB TIFF, little-endian, BI_RGB-equivalent (no compression)."""
    pixel = bytes(rgb)
    # Layout:
    #   header (8) | IFD (entries=8, 12 bytes each + 4 trailing) | strip data
    pixel_offset = 8 + 2 + 8 * 12 + 4
    ifd_entries = [
        # (tag, type, count, value)
        (256, 3, 1, 1),                       # ImageWidth
        (257, 3, 1, 1),                       # ImageLength
        (258, 3, 3, 0),                       # BitsPerSample (8,8,8) — value pointer below
        (259, 3, 1, 1),                       # Compression: none
        (262, 3, 1, 2),                       # PhotometricInterpretation: RGB
        (273, 4, 1, pixel_offset),            # StripOffsets
        (277, 3, 1, 3),                       # SamplesPerPixel
        (279, 4, 1, 3),                       # StripByteCounts
    ]
    bits_per_sample_offset = pixel_offset + 3
    # Patch BitsPerSample to point at the bps array
    ifd_entries[2] = (258, 3, 3, bits_per_sample_offset)
    body = b"II*\x00" + struct.pack("<I", 8)  # little-endian, magic 42, IFD at 8
    body += struct.pack("<H", len(ifd_entries))
    for tag, typ, count, value in ifd_entries:
        # type 3 (SHORT) values <= 4 bytes are inline (low half)
        body += struct.pack("<HHII", tag, typ, count, value)
    body += struct.pack("<I", 0)              # next-IFD offset (none)
    # Strip data + BitsPerSample array
    body += pixel + b"\x08\x00\x08\x00\x08\x00"
    return body


def make_svg(rgb: tuple[int, int, int]) -> bytes:
    """16×16 SVG filled with the given color. Wrapped as image/svg+xml."""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16">'
            f'<rect width="16" height="16" fill="rgb{rgb}"/></svg>').encode("utf-8")


def make_pdf_with_word(word: str) -> bytes:
    """Hand-rolled minimal 1-page PDF containing exactly `word` in plain text.
    Validated against pdfid / qpdf — opens cleanly in Acrobat and Preview."""
    # Build the content stream first so we can size /Length correctly.
    content = (
        f"BT /F1 24 Tf 72 720 Td ({word}) Tj ET"
    ).encode("ascii")

    objs: list[bytes] = []

    def _obj(num: int, body: bytes) -> bytes:
        return f"{num} 0 obj\n".encode("ascii") + body + b"\nendobj\n"

    # 1: Catalog. 2: Pages. 3: Page. 4: Font. 5: Content stream.
    objs.append(_obj(1, b"<< /Type /Catalog /Pages 2 0 R >>"))
    objs.append(_obj(2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>"))
    objs.append(_obj(3, (b"<< /Type /Page /Parent 2 0 R "
                        b"/MediaBox [0 0 612 792] "
                        b"/Resources << /Font << /F1 4 0 R >> >> "
                        b"/Contents 5 0 R >>")))
    objs.append(_obj(4, (b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica "
                        b"/Encoding /WinAnsiEncoding >>")))
    stream_body = (f"<< /Length {len(content)} >>\nstream\n".encode("ascii")
                   + content + b"\nendstream")
    objs.append(_obj(5, stream_body))

    out = b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n"
    offsets = [0]
    for o in objs:
        offsets.append(len(out))
        out += o
    xref_pos = len(out)
    out += f"xref\n0 {len(objs) + 1}\n".encode("ascii")
    out += b"0000000000 65535 f \n"
    for off in offsets[1:]:
        out += f"{off:010d} 00000 n \n".encode("ascii")
    out += (f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\n"
            f"startxref\n{xref_pos}\n%%EOF").encode("ascii")
    return out


# ─────────────────────────────────────────────────────────────
# Embedded canonical fixtures — formats stdlib can't synthesize
# Each comment names the source so the bytes are auditable.
# ─────────────────────────────────────────────────────────────

# Embedded canonical fixtures — VERIFIED to decode through real codecs.
# Each was generated with Pillow (+ pillow-heif / pillow-avif-plugin) by
# saving a 16×16 red square (220, 20, 20) and round-tripping through the
# SAME decoder that would read them back. See generate_fixtures.py and
# format_lab_test.py for round-trip checks. Do not hand-edit these blobs —
# regenerate via the helper if you need to change the size or color.

# Single-line strings: line continuation in a tuple-of-strings literal once
# corrupted JPEG padding by 3 bytes. Keep these as one string each — the
# round-trip decoder check in format_lab_test.py guards against any future
# regression (it would catch a 1-byte mutation immediately).
RED_JPEG_B64 = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAUDBAQEAwUEBAQFBQUGBwwIBwcHBw8LCwkMEQ8SEhEPERETFhwXExQaFRERGCEYGh0dHx8fExciJCIeJBweHx7/2wBDAQUFBQcGBw4ICA4eFBEUHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh7/wAARCAAQABADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDzKiiiviT+oD//2Q=="

# WebP 16×16 lossless red (38 bytes). VP8L bitstream, decodes to (220, 20, 20).
RED_WEBP_B64 = "UklGRh4AAABXRUJQVlA4TBEAAAAvD8ADAAdQinKXov+BiOh/AAA="

# AVIF 16×16 red (309 bytes). AV1 bitstream, decodes to (221, 20, 20).
RED_AVIF_B64 = "AAAAIGZ0eXBhdmlmAAAAAGF2aWZtaWYxbWlhZk1BMUIAAADrbWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAcGljdAAAAAAAAAAAAAAAAAAAAAAOcGl0bQAAAAAAAQAAAB5pbG9jAAAAAEQAAAEAAQAAAAEAAAETAAAAIgAAAChpaW5mAAAAAAABAAAAGmluZmUCAAAAAAEAAGF2MDFDb2xvcgAAAABqaXBycAAAAEtpcGNvAAAAFGlzcGUAAAAAAAAAEAAAABAAAAAQcGl4aQAAAAADCAgIAAAADGF2MUOBAAwAAAAAE2NvbHJuY2x4AAEADQAGgAAAABdpcG1hAAAAAAAAAAEAAQQBAoMEAAAAKm1kYXQSAAoJGAz/2CAhoNCAMhMTQAIIIIQAAFe26F1XkJxpaWyA"

# HEIC 16×16 red (448 bytes). HEVC bitstream, ftyp brand `heic`. Decodes to (220, 20, 20).
RED_HEIC_B64 = "AAAAHGZ0eXBoZWljAAAAAG1pZjFoZWljbWlhZgAAAWhtZXRhAAAAAAAAACFoZGxyAAAAAAAAAABwaWN0AAAAAAAAAAAAAAAAAAAAAA5waXRtAAAAAAABAAAAImlsb2MAAAAAREAAAQABAAAAAAGMAAEAAAAAAAAANAAAACNpaW5mAAAAAAABAAAAFWluZmUCAAAAAAEAAGh2YzEAAAAA6GlwcnAAAADJaXBjbwAAAHVodmNDAQNwAAAAAAAAAAAAHvAA/P34+AAADwNgAAEAGEABDAH//wNwAAADAJAAAAMAAAMAHroCQGEAAQApQgEBA3AAAAMAkAAAAwAAAwAeoCCBBZbqrprm4CGgwIAAAAyAAAADAIRiAAEABkQBwXPBiQAAABRpc3BlAAAAAAAAAEAAAABAAAAAKGNsYXAAAAAQAAAAAQAAABAAAAAB////0AAAAAL////QAAAAAgAAABBwaXhpAAAAAAMICAgAAAAXaXBtYQAAAAAAAAABAAEEgQIEgwAAADxtZGF0AAAAMCgBrxMhZmNA+BD3Z//qwIX/btM/8NOx6c7IR0DA0iCAm0BIk11QCxYQgId2pVbc+A=="

# HEIF 16×16 red (448 bytes). Same HEVC bitstream, `mif1` brand instead of `heic`.
RED_HEIF_B64 = "AAAAHGZ0eXBtaWYxAAAAAG1pZjFoZWljbWlhZgAAAWhtZXRhAAAAAAAAACFoZGxyAAAAAAAAAABwaWN0AAAAAAAAAAAAAAAAAAAAAA5waXRtAAAAAAABAAAAImlsb2MAAAAAREAAAQABAAAAAAGMAAEAAAAAAAAANAAAACNpaW5mAAAAAAABAAAAFWluZmUCAAAAAAEAAGh2YzEAAAAA6GlwcnAAAADJaXBjbwAAAHVodmNDAQNwAAAAAAAAAAAAHvAA/P34+AAADwNgAAEAGEABDAH//wNwAAADAJAAAAMAAAMAHroCQGEAAQApQgEBA3AAAAMAkAAAAwAAAwAeoCCBBZbqrprm4CGgwIAAAAyAAAADAIRiAAEABkQBwXPBiQAAABRpc3BlAAAAAAAAAEAAAABAAAAAKGNsYXAAAAAQAAAAAQAAABAAAAAB////0AAAAAL////QAAAAAgAAABBwaXhpAAAAAAMICAgAAAAXaXBtYQAAAAAAAAABAAEEgQIEgwAAADxtZGF0AAAAMCgBrxMhZmNA+BD3Z//qwIX/btM/8NOx6c7IR0DA0iCAm0BIk11QCxYQgId2pVbc+A=="


# ─────────────────────────────────────────────────────────────
# Auto-install fallback — pure-Python encoders for every format
# ─────────────────────────────────────────────────────────────

def ensure_fixture_packages(quiet: bool = False) -> dict[str, bool]:
    """Make sure Pillow + HEIC/AVIF plugins are importable. If any are
    missing, attempt `pip install --user --quiet` and re-import. Returns
    a per-package availability dict. Designed to be safe to call on any
    machine: failures only narrow the test matrix, never crash the run.

    Why this exists: the user can't drop iPhone photos into a fixtures
    directory, so the script must produce all fixtures itself. Pillow
    plus its HEIC/AVIF plugins ship pre-built wheels on PyPI for every
    mainstream platform — so a one-time `pip install --user` is
    sufficient to cover every format the production code advertises."""
    import importlib

    targets = [
        # (pip-name, import-name, side-effect-after-import)
        ("pillow",              "PIL",                None),
        ("pillow-heif",         "pillow_heif",        "register_heif_opener"),
        ("pillow-avif-plugin",  "pillow_avif",        None),  # registers on import
    ]
    available: dict[str, bool] = {}

    def _try_import(name: str, side_effect: str | None) -> bool:
        try:
            mod = importlib.import_module(name)
            if side_effect:
                getattr(mod, side_effect)()
            return True
        except Exception:
            return False

    # First pass: see what we already have.
    missing: list[str] = []
    for pip_name, import_name, side_effect in targets:
        if _try_import(import_name, side_effect):
            available[pip_name] = True
        else:
            missing.append(pip_name)
            available[pip_name] = False

    if not missing:
        return available

    if not quiet:
        print(f"[ensure-fixtures] missing: {missing} — attempting "
              f"`pip install --user --quiet {' '.join(missing)}`",
              file=sys.stderr)

    # Try to install (best-effort). Use --user to avoid system-wide writes;
    # add --break-system-packages on PEP 668 distros (Homebrew Python 3.12+).
    import subprocess
    base = [sys.executable, "-m", "pip", "install", "--user", "--quiet"]
    for cmd_extra in ([], ["--break-system-packages"]):
        try:
            r = subprocess.run(base + cmd_extra + missing,
                               capture_output=True, text=True, timeout=180)
            if r.returncode == 0:
                break
        except Exception:
            continue
    else:
        if not quiet:
            print("[ensure-fixtures] pip install failed; will fall back to "
                  "embedded fixtures for HEIC/HEIF/AVIF.", file=sys.stderr)

    # Second pass: re-import after install attempt.
    importlib.invalidate_caches()
    for pip_name, import_name, side_effect in targets:
        if not available[pip_name]:
            available[pip_name] = _try_import(import_name, side_effect)
            if available[pip_name] and not quiet:
                print(f"[ensure-fixtures] {pip_name} now available.",
                      file=sys.stderr)

    return available


# ─────────────────────────────────────────────────────────────
# Fixture provider — disk first, encoder second, embedded last
# ─────────────────────────────────────────────────────────────

@dataclass
class Fixture:
    mime: str
    extension: str          # filename suffix to look up under --fixtures-dir
    bytes_: bytes | None    # None when no fixture available
    source: str             # human description of where the bytes came from


def _try_pillow_red(mime: str, fmt: str, **save_kwargs) -> bytes | None:
    """Encode a 16×16 red square via Pillow if available. Returns None if the
    Pillow encoder for this format isn't installed or fails."""
    try:
        from PIL import Image
    except ImportError:
        return None
    try:
        buf = io.BytesIO()
        Image.new("RGB", (16, 16), (220, 20, 20)).save(buf, format=fmt, **save_kwargs)
        return buf.getvalue()
    except Exception:
        return None


def load_fixture(mime: str, fixtures_dir: str | None) -> Fixture:
    """Resolve a fixture for `mime`. Search order:
       1. --fixtures-dir/<extension>     (user-supplied; always preferred)
       2. Pillow encoder (if installed for that format)
       3. Embedded canonical bytes (only JPEG, WebP, AVIF)
       4. None → emits NO_FIXTURE at runtime"""
    ext_for = {
        "image/png": "png",
        "image/jpeg": "jpg",
        "image/webp": "webp",
        "image/heic": "heic",
        "image/heif": "heif",
        "image/gif": "gif",
        "image/bmp": "bmp",
        "image/tiff": "tiff",
        "image/avif": "avif",
        "image/svg+xml": "svg",
    }
    ext = ext_for.get(mime, "bin")

    # 1. Disk
    if fixtures_dir:
        candidate = Path(fixtures_dir) / f"red.{ext}"
        if candidate.is_file():
            return Fixture(mime, ext, candidate.read_bytes(), f"--fixtures-dir/{candidate.name}")

    # 2. Stdlib generators
    if mime == "image/png":
        return Fixture(mime, ext, make_png((220, 20, 20)), "stdlib make_png")
    if mime == "image/bmp":
        return Fixture(mime, ext, make_bmp((220, 20, 20)), "stdlib make_bmp")
    if mime == "image/gif":
        return Fixture(mime, ext, make_gif((220, 20, 20)), "stdlib make_gif")
    if mime == "image/tiff":
        return Fixture(mime, ext, make_tiff((220, 20, 20)), "stdlib make_tiff")
    if mime == "image/svg+xml":
        return Fixture(mime, ext, make_svg((220, 20, 20)), "stdlib make_svg")

    # 3. Pillow
    pillow_map = {
        "image/jpeg":  ("JPEG", {"quality": 85}),
        "image/webp":  ("WEBP", {"lossless": True}),
        "image/heic":  ("HEIF", {}),
        "image/heif":  ("HEIF", {}),
        "image/avif":  ("AVIF", {}),
    }
    if mime in pillow_map:
        fmt, kwargs = pillow_map[mime]
        b = _try_pillow_red(mime, fmt, **kwargs)
        if b is not None:
            return Fixture(mime, ext, b, f"Pillow .save(format={fmt})")

    # 4. Embedded canonical
    embedded = {
        "image/jpeg": (RED_JPEG_B64, "embedded canonical (1×1 red, Pillow-generated)"),
        "image/webp": (RED_WEBP_B64, "embedded canonical (1×1 red lossless VP8L)"),
        "image/avif": (RED_AVIF_B64, "embedded canonical (1×1 red AV1)"),
        "image/heic": (RED_HEIC_B64, "embedded canonical (1×1 red HEVC, heic brand)"),
        "image/heif": (RED_HEIF_B64, "embedded canonical (1×1 red HEVC, mif1 brand)"),
    }
    if mime in embedded:
        data_b64, src = embedded[mime]
        return Fixture(mime, ext, base64.b64decode(data_b64), src)

    # 5. Nothing
    return Fixture(mime, ext, None,
                   "no fixture — supply via --fixtures-dir/red.{ext} or "
                   "install pillow-heif / pillow-avif-plugin")


# ─────────────────────────────────────────────────────────────
# Body builders — exact production wire shape
# ─────────────────────────────────────────────────────────────

def cody_body(content: list[dict], model: str, max_tokens: int,
              stream: bool = True) -> dict:
    """Mirror SourcegraphCompletionsStreamClient.kt:117 + CompletionStreamDtos.kt
    exactly — `speaker` not `role`, `maxTokensToSample` not `max_tokens`,
    explicit topK/topP=-1 to match the reference Cody client."""
    return {
        "model": model,
        "messages": [{"speaker": "human", "content": content}],
        "maxTokensToSample": max_tokens,
        "temperature": 0,
        "stream": stream,
        "topK": -1,
        "topP": -1,
    }


def image_data_url(fixture: Fixture) -> str:
    return f"data:{fixture.mime};base64,{b64(fixture.bytes_ or b'')}"


def build_mime_image_part(fixture: Fixture) -> list[dict]:
    return [
        {"type": "text", "text": PROMPT_COLOR},
        {"type": "image_url", "image_url": {"url": image_data_url(fixture)}},
    ]


def build_doc_block_anthropic_native(pdf_bytes: bytes) -> list[dict]:
    """Anthropic native: {type:"document", source:{type:"base64", ...}}.
    The shape Anthropic's direct API uses for PDF input."""
    return [
        {"type": "document",
         "source": {"type": "base64",
                    "media_type": "application/pdf",
                    "data": b64(pdf_bytes)}},
        {"type": "text", "text": PROMPT_PDF},
    ]


def build_doc_block_openai_file(pdf_bytes: bytes) -> list[dict]:
    """OpenAI-shape file part — `{type:"file", file:{filename, file_data}}`.
    OpenAI's gpt-4o supports PDF this way; some Cody routes might honour it."""
    return [
        {"type": "file",
         "file": {"filename": "doc.pdf",
                  "file_data": f"data:application/pdf;base64,{b64(pdf_bytes)}"}},
        {"type": "text", "text": PROMPT_PDF},
    ]


def build_doc_block_image_url_pdf(pdf_bytes: bytes) -> list[dict]:
    """Long-shot: pass PDF through image_url with PDF MIME (gateway likely
    rejects, but worth probing — answers "does Cody care which MIME I claim?")"""
    return [
        {"type": "text", "text": PROMPT_PDF},
        {"type": "image_url",
         "image_url": {"url": f"data:application/pdf;base64,{b64(pdf_bytes)}"}},
    ]


def build_two_images_part(fa: Fixture, fb: Fixture) -> list[dict]:
    return [
        {"type": "text", "text": PROMPT_TWO_COLORS},
        {"type": "image_url", "image_url": {"url": image_data_url(fa)}},
        {"type": "image_url", "image_url": {"url": image_data_url(fb)}},
    ]


# ─────────────────────────────────────────────────────────────
# Test cases
# ─────────────────────────────────────────────────────────────

@dataclass
class FormatCase:
    name: str
    description: str
    expected_any: list[str]
    expected_all: list[str] = field(default_factory=list)
    notes: str = ""
    # Either content_builder (returns content list) or body_override
    # (returns full body dict). Most cases use content_builder.
    content_builder: Callable[[], list[dict]] | None = None
    body_override: Callable[[str, int], dict] | None = None
    api_version: int = DEFAULT_API_VERSION
    needs_fixture: str | None = None    # MIME we couldn't load → mark NO_FIXTURE
    group: str = "mime"                  # mime | document | api_version | size | multi


def build_mime_cases(fixtures_dir: str | None) -> list[FormatCase]:
    targets = [
        ("mime_png",     "image/png",     "Control: PNG (production whitelist member)"),
        ("mime_jpeg",    "image/jpeg",    "JPEG (production whitelist member)"),
        ("mime_webp",    "image/webp",    "WebP (production whitelist member)"),
        ("mime_heic",    "image/heic",    "HEIC (production whitelist; UNTESTED upstream)"),
        ("mime_heif",    "image/heif",    "HEIF (production whitelist; UNTESTED upstream)"),
        ("mime_gif",     "image/gif",     "GIF (tool-output whitelist only)"),
        ("mime_bmp",     "image/bmp",     "BMP (not whitelisted)"),
        ("mime_tiff",    "image/tiff",    "TIFF (not whitelisted)"),
        ("mime_avif",    "image/avif",    "AVIF (not whitelisted; widely used 2024+)"),
        ("mime_svg",     "image/svg+xml", "SVG (text vector; gateway likely rejects)"),
    ]
    cases: list[FormatCase] = []
    for name, mime, desc in targets:
        fx = load_fixture(mime, fixtures_dir)
        if fx.bytes_ is None:
            cases.append(FormatCase(name=name, description=desc,
                                    expected_any=RED_KEYWORDS,
                                    needs_fixture=mime, group="mime",
                                    notes=fx.source))
            continue
        # Capture a stable `fx` per-case via default-arg binding so the lambda
        # doesn't close over the loop variable.
        cases.append(FormatCase(
            name=name, description=desc, expected_any=RED_KEYWORDS,
            content_builder=(lambda fx_=fx: build_mime_image_part(fx_)),
            notes=f"fixture: {fx.source} ({len(fx.bytes_)} bytes)",
            group="mime",
        ))
    return cases


def build_document_cases() -> list[FormatCase]:
    pdf = make_pdf_with_word("MAGENTA")
    return [
        FormatCase(
            name="doc_anthropic_native_pdf",
            description="Anthropic native: type=document, source.type=base64, "
                        "media_type=application/pdf",
            expected_any=PDF_KEYWORDS,
            content_builder=lambda: build_doc_block_anthropic_native(pdf),
            notes="If PASS, agent could send PDFs natively instead of Tika.",
            group="document",
        ),
        FormatCase(
            name="doc_openai_file_pdf",
            description="OpenAI-shape: type=file, file.file_data=data: URL (PDF)",
            expected_any=PDF_KEYWORDS,
            content_builder=lambda: build_doc_block_openai_file(pdf),
            notes="Some gateways translate this for the upstream provider.",
            group="document",
        ),
        FormatCase(
            name="doc_image_url_with_pdf_mime",
            description="Negative-control: PDF bytes inside an image_url data URL",
            expected_any=PDF_KEYWORDS,
            content_builder=lambda: build_doc_block_image_url_pdf(pdf),
            notes="Expected to fail. PASS would mean gateway re-derives MIME.",
            group="document",
        ),
    ]


def build_api_version_cases() -> list[FormatCase]:
    """PNG control × api-version 1, 2, 8, 9, 10. Helps map server support."""
    fx = load_fixture("image/png", None)
    cases: list[FormatCase] = []
    for v in (1, 2, 8, 9, 10):
        cases.append(FormatCase(
            name=f"apiver_v{v}",
            description=f"PNG image on /.api/completions/stream?api-version={v}",
            expected_any=RED_KEYWORDS,
            content_builder=lambda fx_=fx: build_mime_image_part(fx_),
            api_version=v, group="api_version",
            notes="api-version 1 emits cumulative `completion`; >=2 emits `deltaText`."
        ))
    return cases


def build_size_cases() -> list[FormatCase]:
    """PNG at increasing dimensions — finds the gateway/provider size cliff."""
    cases: list[FormatCase] = []
    for size in (1, 64, 256, 1024, 2048, 4096):
        png = make_png((220, 20, 20), size=size)
        b = b64(png)
        approx_kb = len(b) / 1024.0
        cases.append(FormatCase(
            name=f"size_{size}x{size}",
            description=f"PNG {size}×{size} (~{approx_kb:.1f} KB base64)",
            expected_any=RED_KEYWORDS,
            content_builder=(lambda png_=png:
                             [{"type": "text", "text": PROMPT_COLOR},
                              {"type": "image_url",
                               "image_url": {"url": f"data:image/png;base64,{b64(png_)}"}}]),
            group="size",
        ))
    return cases


def build_tools_cases() -> list[FormatCase]:
    """Re-probe whether /.api/completions/stream honours the `tools` field
    or silently drops it. The capabilities_lab baseline (2026-04-22, api-
    version=8) showed it dropped. The user's instance now advertises
    api-version=9 — Sourcegraph may have shipped a fix. If these PASS, the
    BrainRouter two-step image+tools workaround can be removed."""
    tool_def = {
        "type": "function",
        "function": {
            "name": "must_call_this_tool",
            "description": "Returns the secret number. You MUST call this "
                           "tool to answer; you cannot guess.",
            "parameters": {
                "type": "object",
                "properties": {"reason": {"type": "string"}},
                "required": ["reason"],
            },
        },
    }
    prompt = ("I need the secret number. You don't know it — you MUST call "
              "the must_call_this_tool function to retrieve it. Call the "
              "tool now.")
    fx_png = load_fixture("image/png", None)

    def _tools_only_body(model: str, max_tokens: int) -> dict:
        b = cody_body([{"type": "text", "text": prompt}], model, max_tokens)
        b["tools"] = [tool_def]
        return b

    def _tools_with_image_body(model: str, max_tokens: int) -> dict:
        b = cody_body(
            [{"type": "text", "text": prompt},
             {"type": "image_url",
              "image_url": {"url": image_data_url(fx_png)}}],
            model, max_tokens,
        )
        b["tools"] = [tool_def]
        return b

    return [
        FormatCase(
            name="tools_only_on_stream",
            description="POST /.api/completions/stream with `tools` field "
                        "(no image) — does the model emit a tool call?",
            expected_any=[],   # raw-blob match in tools-group verdict path
            body_override=_tools_only_body,
            group="tools",
            notes="If PASS, /stream now honors tools. Removes need for "
                  "BrainRouter two-step workaround.",
        ),
        FormatCase(
            name="tools_with_image_on_stream",
            description="POST /.api/completions/stream with `tools` + image",
            expected_any=[],
            body_override=_tools_with_image_body,
            group="tools",
            notes="The agent's image+tools turn — currently uses two-step.",
        ),
    ]


def build_multi_cases() -> list[FormatCase]:
    fx_red = load_fixture("image/png", None)
    fx_blue = Fixture("image/png", "png",
                      make_png((20, 60, 220)), "stdlib make_png blue")
    return [
        FormatCase(
            name="multi_two_images",
            description="Two PNG images in one stream message (red, blue)",
            expected_any=["red", "blue"],
            expected_all=["red", "blue"],
            content_builder=lambda: build_two_images_part(fx_red, fx_blue),
            group="multi",
        ),
    ]


# ─────────────────────────────────────────────────────────────
# HTTP + SSE
# ─────────────────────────────────────────────────────────────

class SourcegraphClient:
    def __init__(self, base_url: str, token: str, verify: bool = True, timeout: int = 60):
        self.base_url = base_url.rstrip("/")
        # Defensive: PowerShell often appends \r when pasting tokens.
        self.token = (token or "").strip()
        if self.token != (token or ""):
            print("WARN: stripped whitespace from --token (PowerShell often "
                  "appends \\r when pasting). Using cleaned value.", file=sys.stderr)
        self.session = requests.Session()
        self.session.headers.update({
            "Authorization": f"token {self.token}",
            "Content-Type": "application/json; charset=utf-8",
            "Accept": "application/json",
        })
        self.session.verify = verify
        self.timeout = timeout

    def _mask_token(self) -> str:
        t = self.token
        return f"<token len={len(t)}>" if len(t) < 12 else f"{t[:6]}...{t[-4:]}"

    def get_json(self, path: str) -> Any:
        r = self.session.get(self.base_url + path, timeout=30)
        r.raise_for_status()
        return r.json()

    def post_stream(self, api_version: int, body: dict, sse: bool) -> requests.Response:
        url = f"{self.base_url}{STREAM_PATH}?api-version={api_version}"
        headers = {"Accept": "text/event-stream"} if sse else {"Accept": "application/json"}
        return self.session.post(url, json=body, stream=sse, headers=headers,
                                 timeout=self.timeout)


def parse_cody_sse(resp: requests.Response) -> tuple[str, list[str], str | None, str]:
    """Walk an SSE response, accumulating deltaText/completion frames.

    Returns (accumulated_text, events_seen, stop_reason, raw_blob).
    `raw_blob` is the concatenation of every data: payload — used by the
    tools-on-stream probe to look for tool_use / function_call markers
    that the gateway might emit in non-text frames."""
    accumulated = ""
    events: list[str] = []
    stop_reason: str | None = None
    raw_chunks: list[str] = []
    for raw in resp.iter_lines(decode_unicode=True):
        if raw is None:
            continue
        if not raw:
            continue
        if raw.startswith("event:"):
            events.append(raw[6:].strip())
            continue
        if raw.startswith("data:"):
            payload = raw[5:].strip()
            if not payload or payload == "[DONE]":
                continue
            raw_chunks.append(payload)
            try:
                obj = json.loads(payload)
            except json.JSONDecodeError:
                continue
            if not isinstance(obj, dict):
                continue
            if isinstance(obj.get("deltaText"), str):
                accumulated += obj["deltaText"]
            elif isinstance(obj.get("completion"), str):
                accumulated = obj["completion"]    # cumulative on api-version=1
            if isinstance(obj.get("stopReason"), str):
                stop_reason = obj["stopReason"]
    return accumulated, events, stop_reason, "\n".join(raw_chunks)


# ─────────────────────────────────────────────────────────────
# Run + evaluate
# ─────────────────────────────────────────────────────────────

@dataclass
class RunOutcome:
    model: str
    case_name: str
    api_version: int
    status: int
    elapsed_ms: int
    verdict: str = "FAIL"        # PASS | FAIL | SKIP
    fail_reason: str = ""        # NO_FIXTURE | HTTP_4xx | SAW_NO | REFUSED | ERROR | SSE_EMPTY
    reply_preview: str = ""
    error: str = ""
    request_preview: str = ""
    fixture_source: str = ""
    stop_reason: str | None = None


def _short(s: str, n: int = 240) -> str:
    s = (s or "").strip()
    return s if len(s) <= n else s[:n] + "…"


# Redacts inline base64 payloads in request previews so the JSON dump and
# console log are readable AND don't carry unnecessary fixture bytes. Two
# shapes are covered:
#   1. `data:<mime>;base64,<N chars>`            (image_url / openai file)
#   2. `"data":"<long-base64>"`                  (anthropic-native document)
# The bare `"data"` rule only fires for base64 strings >= 64 chars to avoid
# false positives on short data fields (e.g. an empty tool result).
import re as _re   # local alias kept private; module-level so the re cost is paid once
_DATA_URL_RE = _re.compile(r'data:([^;\\"]+);base64,([A-Za-z0-9+/=]+)')
_BARE_DATA_RE = _re.compile(r'"data"\s*:\s*"([A-Za-z0-9+/=]{64,})"')


def redact_payload(s: str) -> str:
    if not s:
        return s
    s = _DATA_URL_RE.sub(
        lambda m: f"data:{m.group(1)};base64,<{len(m.group(2))} chars redacted>",
        s,
    )
    s = _BARE_DATA_RE.sub(
        lambda m: f'"data":"<{len(m.group(1))} chars redacted>"',
        s,
    )
    return s


class _Tee:
    """File-like object that mirrors writes to two underlying streams.
    Used to fork stdout/stderr to a log file while still printing to the
    terminal — single-file output the user can share without shell tricks."""
    def __init__(self, *streams):
        self._streams = streams

    def write(self, data):
        for s in self._streams:
            try:
                s.write(data)
            except Exception:
                pass
        return len(data) if isinstance(data, str) else 0

    def flush(self):
        for s in self._streams:
            try:
                s.flush()
            except Exception:
                pass

    # `requests`/`logging` occasionally probe these:
    @property
    def encoding(self):
        return "utf-8"

    def isatty(self):
        return False


def run_case(client: SourcegraphClient, model: str, case: FormatCase,
             max_tokens: int) -> RunOutcome:
    # No-fixture case: emit SKIP early without burning a request.
    if case.needs_fixture is not None:
        return RunOutcome(
            model=model, case_name=case.name, api_version=case.api_version,
            status=0, elapsed_ms=0,
            verdict="SKIP", fail_reason="NO_FIXTURE",
            fixture_source=case.notes,
        )

    # Build body
    if case.body_override is not None:
        body = case.body_override(model, max_tokens)
    else:
        assert case.content_builder is not None
        body = cody_body(case.content_builder(), model, max_tokens, stream=True)

    # Redact BEFORE truncating: if we truncate first, half-base64 strings
    # lose their closing quote and the regex can't anchor, so raw bytes leak.
    request_preview = _short(redact_payload(json.dumps(body)), 800)

    t0 = time.time()
    try:
        r = client.post_stream(case.api_version, body, sse=True)
    except requests.RequestException as e:
        return RunOutcome(
            model=model, case_name=case.name, api_version=case.api_version,
            status=0, elapsed_ms=int((time.time() - t0) * 1000),
            verdict="FAIL", fail_reason="ERROR", error=str(e),
            request_preview=request_preview,
        )
    elapsed_ms = int((time.time() - t0) * 1000)

    out = RunOutcome(model=model, case_name=case.name,
                     api_version=case.api_version,
                     status=r.status_code, elapsed_ms=elapsed_ms,
                     request_preview=request_preview)

    if r.status_code != 200:
        out.fail_reason = f"HTTP_{r.status_code}"
        out.reply_preview = _short(r.text, 400)
        return out

    try:
        text, events, stop_reason, raw_blob = parse_cody_sse(r)
    except requests.RequestException as e:
        out.fail_reason = "SSE_READ_ERROR"
        out.error = str(e)
        return out

    out.stop_reason = stop_reason

    # Tools-group cases verdict on the raw stream content, not the text. We
    # PASS only when the gateway forwarded a tool call back to us; SAW_NO
    # means the tools field was silently dropped (model replied as text).
    if case.group == "tools":
        tool_markers = ("tool_use", "tool_call", "function_call",
                        "tool_calls", '"toolCalls"', '"toolUse"')
        saw_tool = any(m in raw_blob for m in tool_markers)
        out.reply_preview = _short(text or raw_blob[:400], 400)
        if saw_tool:
            out.verdict = "PASS"
        else:
            out.fail_reason = "SAW_NO"
        return out

    if not text:
        out.fail_reason = "SSE_EMPTY"
        out.reply_preview = f"events_seen={events[:10]}"
        return out

    out.reply_preview = _short(text, 400)
    lower = text.lower()
    if any(p in lower for p in REFUSAL_PHRASES):
        out.fail_reason = "REFUSED"
        return out

    matched_any = any(kw in lower for kw in case.expected_any)
    matched_all = (all(kw in lower for kw in case.expected_all)
                   if case.expected_all else True)
    if matched_any and matched_all:
        out.verdict = "PASS"
    else:
        out.fail_reason = "SAW_NO"
    return out


# ─────────────────────────────────────────────────────────────
# Pretty printers
# ─────────────────────────────────────────────────────────────

def print_outcome(o: RunOutcome) -> None:
    sym = {"PASS": "✅", "SKIP": "⏭ ", "FAIL": "❌"}.get(o.verdict, "❌")
    head = f"  {sym} {o.case_name:<32s} v{o.api_version}  status={o.status}  {o.elapsed_ms}ms"
    if o.verdict == "PASS":
        print(f"{head}    reply='{o.reply_preview}'")
        return
    if o.verdict == "SKIP":
        print(f"{head}    [{o.fail_reason}]  {o.fixture_source}")
        return
    extras = [o.fail_reason]
    if o.error:
        extras.append(f"err={o.error}")
    print(f"{head}    [{', '.join(extras)}]")
    if o.reply_preview:
        print(f"      reply : {o.reply_preview}")
    if o.fail_reason in ("HTTP_400", "HTTP_413", "HTTP_422", "BAD_JSON"):
        print(f"      sent  : {o.request_preview}")


def print_matrix(outcomes: list[RunOutcome], cases: list[FormatCase],
                 models: list[str]) -> None:
    print()
    print("=" * 110)
    print("SUMMARY MATRIX  ( ✅ = format works for that model | ⏭  = skipped | ❌ = does not )")
    print("=" * 110)
    name_w = max(len(c.name) for c in cases) + 2
    short = [m.split("::")[-1] if "::" in m else m for m in models]
    col_w = max(max(len(s) for s in short), 6)
    by_key = {(o.model, o.case_name, o.api_version): o for o in outcomes}
    print(" " * name_w + "  " + "  ".join(s.ljust(col_w) for s in short))
    for case in cases:
        row = [case.name.ljust(name_w)]
        for model in models:
            o = by_key.get((model, case.name, case.api_version))
            if o is None:
                cell = "skip"
            elif o.verdict == "PASS":
                cell = "PASS"
            elif o.verdict == "SKIP":
                cell = "NO_FX"
            else:
                cell = (o.fail_reason or "FAIL")[:col_w]
            row.append(cell.ljust(col_w))
        print("  ".join(row))
    print("=" * 110)


def print_recommendation(outcomes: list[RunOutcome], cases: list[FormatCase]) -> None:
    """Compute the actually-supported MIME whitelist from PASS results."""
    mime_cases = [c for c in cases if c.group == "mime"]
    by_case = {c.name: c for c in mime_cases}
    name_to_mime = {
        "mime_png": "image/png", "mime_jpeg": "image/jpeg", "mime_webp": "image/webp",
        "mime_heic": "image/heic", "mime_heif": "image/heif", "mime_gif": "image/gif",
        "mime_bmp": "image/bmp", "mime_tiff": "image/tiff", "mime_avif": "image/avif",
        "mime_svg": "image/svg+xml",
    }
    pass_per_case: dict[str, int] = {}
    skip_per_case: dict[str, int] = {}
    total_per_case: dict[str, int] = {}
    for o in outcomes:
        if o.case_name not in by_case:
            continue
        total_per_case[o.case_name] = total_per_case.get(o.case_name, 0) + 1
        if o.verdict == "PASS":
            pass_per_case[o.case_name] = pass_per_case.get(o.case_name, 0) + 1
        if o.verdict == "SKIP":
            skip_per_case[o.case_name] = skip_per_case.get(o.case_name, 0) + 1

    print("\nFORMAT-SUPPORT RECOMMENDATION")
    print("-" * 60)
    fully_supported, partial, unsupported, unverified = [], [], [], []
    for name, mime in name_to_mime.items():
        if name not in total_per_case:
            continue
        total = total_per_case[name]
        passes = pass_per_case.get(name, 0)
        skips = skip_per_case.get(name, 0)
        if skips == total:
            unverified.append((mime, name))
        elif passes == total - skips and passes > 0:
            fully_supported.append((mime, name))
        elif passes > 0:
            partial.append((mime, f"{passes}/{total - skips}"))
        else:
            unsupported.append((mime, name))

    if fully_supported:
        print("✅ Fully supported (every model passed):")
        for mime, _ in fully_supported:
            print(f"     {mime}")
    if partial:
        print("⚠  Partial support (some models, not all):")
        for mime, ratio in partial:
            print(f"     {mime}    ({ratio} models)")
    if unsupported:
        print("❌ NOT supported (every model SAW_NO / 4xx):")
        for mime, _ in unsupported:
            print(f"     {mime}")
    if unverified:
        print("⏭  Unverified — supply --fixtures-dir/red.<ext> to test:")
        for mime, _ in unverified:
            print(f"     {mime}")

    # The whitelist the plugin should actually advertise
    confirmed = [m for m, _ in fully_supported]
    if confirmed:
        print("\nSuggested PluginSettings.imageMimeWhitelist:")
        print(f"     listOf({', '.join(repr(m) for m in confirmed)})")


def print_grand_totals(outcomes: list[RunOutcome]) -> None:
    total = len(outcomes)
    passes = sum(1 for o in outcomes if o.verdict == "PASS")
    skips = sum(1 for o in outcomes if o.verdict == "SKIP")
    print(f"\nTOTAL: {passes} PASS / {skips} SKIP / {total - passes - skips} FAIL "
          f"out of {total} cells")


# ─────────────────────────────────────────────────────────────
# Discovery (model catalog + client config)
# ─────────────────────────────────────────────────────────────

def discover_vision_models(client: SourcegraphClient) -> list[str]:
    """Use /.api/modelconfig/supported-models.json (Cody catalog) — preferred
    over /.api/llm/models because the catalog exposes per-model `capabilities`
    arrays and the plugin's ModelCatalogService uses this exact endpoint."""
    cat = client.get_json(MODEL_CATALOG_PATH)
    out = []
    for m in cat.get("models", []):
        if "vision" in (m.get("capabilities") or []):
            out.append(m["modelRef"])
    return out


def discover_latest_api_version(client: SourcegraphClient) -> int:
    cfg = client.get_json(CLIENT_CONFIG_PATH)
    return int(cfg.get("latestSupportedCompletionsStreamAPIVersion") or DEFAULT_API_VERSION)


# ─────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────

def _mask_url(url: str) -> str:
    if not url:
        return "<no-url>"
    try:
        from urllib.parse import urlparse, urlunparse
        p = urlparse(url)
        host = p.hostname or ""
        parts = host.split(".")
        masked = ["***"] * max(len(parts) - 1, 1) + ([parts[-1]] if len(parts) > 1 else [])
        masked_host = ".".join(masked)
        if p.port:
            masked_host = f"{masked_host}:{p.port}"
        return urlunparse((p.scheme, masked_host, p.path, p.params, "", ""))
    except Exception:
        return "***"


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Probe which raster MIMEs and document shapes Sourcegraph "
                    "actually round-trips on /.api/completions/stream."
    )
    ap.add_argument("--url", required=True, help="Sourcegraph base URL")
    ap.add_argument("--token", required=True, help="Sourcegraph access token (sgp_...)")
    ap.add_argument("--models", default="",
                    help="Comma-separated model IDs. Default: discover vision-capable "
                         "models from /.api/modelconfig/supported-models.json.")
    ap.add_argument("--only", default="", help="Comma-separated case names")
    ap.add_argument("--list", action="store_true", help="List cases and exit")
    ap.add_argument("--fixtures-dir", default=None,
                    help="Directory with user-provided fixtures named "
                         "red.jpg, red.webp, red.heic, etc.")
    ap.add_argument("--api-version-sweep", action="store_true",
                    help="Add api-version 1/2/8/9/10 PNG matrix")
    ap.add_argument("--size-sweep", action="store_true",
                    help="Add payload-size matrix (1×1 → 4096×4096)")
    ap.add_argument("--include-documents", action="store_true", default=True,
                    help="Probe Anthropic-native PDF document blocks (default ON)")
    ap.add_argument("--no-documents", action="store_true",
                    help="Skip the document-block probes")
    ap.add_argument("--include-multi", action="store_true", default=True,
                    help="Probe multi-image messages (default ON)")
    ap.add_argument("--include-tools", action="store_true", default=True,
                    help="Re-probe whether /.api/completions/stream honours "
                         "the `tools` field on the current api-version. "
                         "Old probe (2026-04-22, api-version=8) showed it was "
                         "silently dropped; verify this is still the case.")
    ap.add_argument("--no-tools", action="store_true",
                    help="Skip the tools-on-stream regression probes")
    ap.add_argument("--max-tokens", type=int, default=10000,
                    help="maxTokensToSample. 10000 sits above any thinking budget "
                         "the gateway pre-allocates, avoiding the "
                         "'max_tokens > thinking.budget_tokens' trap.")
    ap.add_argument("--timeout", type=int, default=60)
    ap.add_argument("--no-verify", action="store_true",
                    help="Disable TLS verification (self-signed certs)")
    ap.add_argument("--out", default="format_lab_results.json")
    ap.add_argument("--log-file", default="format_lab_run.log",
                    help="Mirror all console output to this file. Set to '' "
                         "to disable. URLs and tokens are redacted as usual.")
    ap.add_argument("--ensure-fixtures", action="store_true", default=True,
                    help="Auto-install Pillow + pillow-heif + pillow-avif-plugin "
                         "via `pip install --user` so every fixture (incl. HEIC/"
                         "HEIF/AVIF) can be encoded inline. Default ON.")
    ap.add_argument("--no-ensure-fixtures", action="store_true",
                    help="Skip the pip-install step and use embedded fallback "
                         "fixtures for HEIC/HEIF/AVIF.")
    args = ap.parse_args()

    # Auto-install fixture encoders so the user does not have to provide any
    # external photos. Best-effort: if pip fails (offline / corp proxy /
    # locked Python), the embedded canonical fixtures below take over.
    if args.ensure_fixtures and not args.no_ensure_fixtures:
        ensure_fixture_packages(quiet=False)

    # Mirror stdout/stderr into a single shareable log file, on top of the
    # JSON dump. The log captures matrix + recommendation + per-case lines.
    log_handle = None
    if args.log_file:
        try:
            log_handle = open(args.log_file, "w", encoding="utf-8")
            sys.stdout = _Tee(sys.stdout, log_handle)
            sys.stderr = _Tee(sys.stderr, log_handle)
        except OSError as e:
            print(f"WARN: could not open --log-file {args.log_file!r}: {e}",
                  file=sys.stderr)
            log_handle = None

    # Build the case set
    cases: list[FormatCase] = []
    cases += build_mime_cases(args.fixtures_dir)
    if args.include_documents and not args.no_documents:
        cases += build_document_cases()
    if args.include_multi:
        cases += build_multi_cases()
    if args.include_tools and not args.no_tools:
        cases += build_tools_cases()
    if args.api_version_sweep:
        cases += build_api_version_cases()
    if args.size_sweep:
        cases += build_size_cases()

    if args.list:
        for c in cases:
            print(f"  {c.name:<32s} v{c.api_version:<2}  {c.description}")
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

    client = SourcegraphClient(args.url, args.token, verify=not args.no_verify,
                               timeout=args.timeout)

    # Resolve models
    if args.models:
        models = [m.strip() for m in args.models.split(",") if m.strip()]
        discovery_note = "(from --models)"
    else:
        try:
            models = discover_vision_models(client)
        except requests.HTTPError as e:
            code = e.response.status_code if e.response is not None else "?"
            print(f"ERROR: failed to fetch model catalog: HTTP {code}", file=sys.stderr)
            if code == 401:
                print(f"       Auth header: 'token {client._mask_token()}'", file=sys.stderr)
                print("       Token may be expired, revoked, or for a different instance.",
                      file=sys.stderr)
            if e.response is not None:
                print(f"       Body: {e.response.text[:300]}", file=sys.stderr)
            return 3
        except requests.RequestException as e:
            print(f"ERROR: model catalog request failed: {e}", file=sys.stderr)
            print("       Pass --models 'id1,id2' explicitly if discovery is unavailable.",
                  file=sys.stderr)
            return 3
        if not models:
            print("ERROR: catalog returned 0 vision-capable models. "
                  "Pass --models explicitly.", file=sys.stderr)
            return 3
        discovery_note = (f"(filtered {len(models)} vision-capable from "
                          f"{MODEL_CATALOG_PATH})")

    # Resolve default api-version (used by all non-sweep cases)
    try:
        latest = discover_latest_api_version(client)
    except requests.RequestException:
        latest = DEFAULT_API_VERSION
    # Cases default to DEFAULT_API_VERSION; we now bump non-sweep cases to the
    # actual latest, matching what the plugin would send.
    for c in cases:
        if c.group != "api_version":
            c.api_version = latest

    print(f"Sourcegraph URL : {_mask_url(args.url)}")
    print(f"latest stream api-version : {latest} (from {CLIENT_CONFIG_PATH})")
    print(f"Models          : {len(models)}  {discovery_note}")
    for m in models:
        print(f"  - {m}")
    print(f"Cases           : {len(cases)} "
          f"(mime={sum(1 for c in cases if c.group == 'mime')}, "
          f"document={sum(1 for c in cases if c.group == 'document')}, "
          f"multi={sum(1 for c in cases if c.group == 'multi')}, "
          f"api_version={sum(1 for c in cases if c.group == 'api_version')}, "
          f"size={sum(1 for c in cases if c.group == 'size')})")
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
    print_recommendation(outcomes, cases)

    payload = {
        "url": _mask_url(args.url),
        "latest_api_version": latest,
        "models": models,
        "cases": [{"name": c.name, "description": c.description,
                   "group": c.group, "api_version": c.api_version,
                   "expected_any": c.expected_any,
                   "expected_all": c.expected_all,
                   "needs_fixture": c.needs_fixture,
                   "notes": c.notes}
                  for c in cases],
        "outcomes": [asdict(o) for o in outcomes],
    }
    # Final-pass redaction across the JSON dump: any stray base URL or token
    # that slipped into an error message gets masked. Cheap defence-in-depth.
    raw_url = args.url.rstrip("/")
    masked_url = _mask_url(args.url).rstrip("/")
    serialized = json.dumps(payload, indent=2)
    if raw_url and raw_url != masked_url:
        serialized = serialized.replace(raw_url, masked_url)
    if client.token:
        serialized = serialized.replace(client.token, client._mask_token())
    Path(args.out).write_text(serialized)
    print(f"\nWrote {args.out}")
    if log_handle is not None:
        # Flush + close — the tee keeps writing until process exit otherwise.
        try:
            log_handle.flush()
        except Exception:
            pass
        print(f"Wrote {args.log_file} (full console transcript)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
