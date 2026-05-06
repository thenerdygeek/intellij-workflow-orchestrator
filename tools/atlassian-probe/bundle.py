#!/usr/bin/env python3
"""
Bundle / unbundle a probe result directory into a single text file.

The probe + redactor output is many small files (summary.md, raw/*.json,
discover.md, redaction_report.json). When sharing results in chat,
copy-pasting them one by one is annoying. This tool flattens the directory
into a single text file you can paste in one shot, and reverses the
operation on the other end.

Usage:
    # Pack: produces Result_N_redacted.bundle.txt next to the input dir.
    python bundle.py pack --in Result_2_redacted

    # Unpack: extracts to Result_N_redacted/ next to the bundle file.
    python bundle.py unpack --in Result_2_redacted.bundle.txt

Format (MIME-multipart-inspired):

    # atlassian-probe bundle v1
    # boundary: <uuid-hex>
    # files: 38
    # generated: 2026-05-06T10:00:00Z
    # source: Result_2_redacted
    # instructions: python bundle.py unpack --in <this-file>

    --<boundary>
    path: summary.md
    size: 1234
    sha256: abc123...

    <full file content>
    --<boundary>
    path: raw/serverInfo.json
    size: 567
    sha256: def456...

    <full file content>
    --<boundary>--

Notes:
- Every file's SHA256 is recorded in its part header. Unpack verifies them
  and refuses any file whose content doesn't round-trip — this catches
  truncation during paste, accidental edits, or boundary collisions.
- Empty files are supported (size: 0, no content lines between header and
  next boundary).
- Binary files are NOT supported (probe output is JSON + markdown; the
  bundler will refuse anything that isn't UTF-8 decodable).
"""

from __future__ import annotations

import argparse
import base64
import datetime
import gzip
import hashlib
import sys
import textwrap
import uuid
from pathlib import Path


HEADER_PREFIX = "# atlassian-probe bundle v"
COMPRESSED_PREFIX = "# atlassian-probe bundle (compressed) v"
BUNDLE_VERSION = "1"


# ---------------------------------------------------------------------------
# Pack
# ---------------------------------------------------------------------------

def _build_multipart(in_dir: Path, files: list[Path]) -> tuple[str, list[tuple[Path, str]]]:
    """Build the plain-text multipart body. Returns (text, list-of-skipped)."""
    boundary = "atlprobe-" + uuid.uuid4().hex[:16]
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    parts: list[str] = []
    parts.append(f"{HEADER_PREFIX}{BUNDLE_VERSION}")
    parts.append(f"# boundary: {boundary}")
    parts.append(f"# files: {len(files)}")
    parts.append(f"# generated: {now}")
    parts.append(f"# source: {in_dir.name}")
    parts.append(f"# instructions: python bundle.py unpack --in <this-file>")
    parts.append("")

    failed: list[tuple[Path, str]] = []
    for f in files:
        try:
            text = f.read_text(encoding="utf-8")
        except UnicodeDecodeError as e:
            failed.append((f, f"non-UTF-8 file (offset {e.start}); skipped"))
            continue
        rel = f.relative_to(in_dir).as_posix()
        digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
        size = len(text.encode("utf-8"))
        parts.append(f"--{boundary}")
        parts.append(f"path: {rel}")
        parts.append(f"size: {size}")
        parts.append(f"sha256: {digest}")
        parts.append("")
        parts.append(text)
    parts.append(f"--{boundary}--")
    parts.append("")
    return "\n".join(parts), failed


def pack(in_dir: Path, out_file: Path, compress: bool = False) -> int:
    if not in_dir.is_dir():
        print(f"ERROR: {in_dir} is not a directory", file=sys.stderr)
        return 2

    files: list[Path] = sorted(p for p in in_dir.rglob("*") if p.is_file())
    if not files:
        print(f"ERROR: {in_dir} is empty", file=sys.stderr)
        return 2

    multipart_text, failed = _build_multipart(in_dir, files)

    if not compress:
        out_file.write_text(multipart_text, encoding="utf-8")
    else:
        # gzip → base64 → 76-col-wrapped text. JSON compresses 5–10×; base64
        # then expands by 4/3, net ~4–8× shrink. The wrapped output remains
        # paste-safe in clipboards that strip overlong lines.
        raw_bytes = multipart_text.encode("utf-8")
        compressed = gzip.compress(raw_bytes, compresslevel=9)
        b64 = base64.b64encode(compressed).decode("ascii")
        wrapped = "\n".join(textwrap.wrap(b64, 76))
        original_sha = hashlib.sha256(raw_bytes).hexdigest()
        now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        header_lines = [
            f"{COMPRESSED_PREFIX}{BUNDLE_VERSION}",
            f"# generated: {now}",
            f"# source: {in_dir.name}",
            f"# files: {len(files)}",
            f"# original-bytes: {len(raw_bytes)}",
            f"# compressed-bytes: {len(compressed)}",
            f"# original-sha256: {original_sha}",
            f"# encoding: gzip+base64 (76-col wrapped)",
            f"# instructions: python bundle.py unpack --in <this-file>",
        ]
        # Write header lines, then a blank-line separator (unpack uses the
        # blank line to detect end-of-header), then the wrapped base64.
        out_file.write_text(
            "\n".join(header_lines) + "\n\n" + wrapped + "\n",
            encoding="utf-8",
        )

    final_size = out_file.stat().st_size
    print(f"[bundle] packed {len(files)} files → {out_file}")
    if compress:
        ratio = len(multipart_text.encode("utf-8")) / final_size
        print(f"[bundle] size: {final_size:,} bytes "
              f"(compressed {ratio:.1f}× from {len(multipart_text.encode('utf-8')):,})")
    else:
        print(f"[bundle] size: {final_size:,} bytes")
    if failed:
        print(f"[bundle] WARNING: {len(failed)} file(s) skipped:", file=sys.stderr)
        for path, reason in failed:
            print(f"  - {path}: {reason}", file=sys.stderr)
    return 0


# ---------------------------------------------------------------------------
# Unpack
# ---------------------------------------------------------------------------

def unpack(in_file: Path, out_dir: Path) -> int:
    if not in_file.is_file():
        print(f"ERROR: {in_file} is not a file", file=sys.stderr)
        return 2

    raw = in_file.read_text(encoding="utf-8")

    # Auto-detect compressed bundles. The compressed header lives above the
    # base64-encoded gzip blob; we strip the header, decode + decompress, and
    # then fall through to the plain-text multipart parser.
    if raw.startswith(COMPRESSED_PREFIX):
        header_lines: list[str] = []
        body_lines: list[str] = []
        in_header = True
        expected_sha: str | None = None
        for line in raw.split("\n"):
            if in_header:
                header_lines.append(line)
                if line.startswith("# original-sha256:"):
                    expected_sha = line.split(":", 1)[1].strip()
                if line == "":
                    in_header = False
                continue
            body_lines.append(line)
        try:
            compressed = base64.b64decode("".join(body_lines))
            decoded = gzip.decompress(compressed).decode("utf-8")
        except Exception as e:
            print(f"ERROR: failed to decompress bundle: {type(e).__name__}: {e}",
                  file=sys.stderr)
            return 2
        if expected_sha:
            actual = hashlib.sha256(decoded.encode("utf-8")).hexdigest()
            if actual != expected_sha:
                print(f"ERROR: decompressed SHA256 mismatch "
                      f"(expected {expected_sha[:16]}…, got {actual[:16]}…) — "
                      f"bundle was modified or truncated in transit",
                      file=sys.stderr)
                return 2
        raw = decoded

    lines = raw.split("\n")

    # Parse header
    if not lines or not lines[0].startswith(HEADER_PREFIX):
        print(f"ERROR: not a bundle file (no '{HEADER_PREFIX}' on first line)",
              file=sys.stderr)
        return 2

    boundary = None
    expected_files = None
    for line in lines[:10]:
        if line.startswith("# boundary:"):
            boundary = line.split(":", 1)[1].strip()
        elif line.startswith("# files:"):
            try:
                expected_files = int(line.split(":", 1)[1].strip())
            except ValueError:
                pass

    if not boundary:
        print("ERROR: bundle header missing 'boundary' field", file=sys.stderr)
        return 2

    open_marker = f"--{boundary}"
    close_marker = f"--{boundary}--"

    # Find part start indices (lines exactly equal to the open marker)
    starts: list[int] = [i for i, ln in enumerate(lines) if ln == open_marker]
    end_idx = next((i for i, ln in enumerate(lines) if ln == close_marker), None)
    if not starts or end_idx is None:
        print("ERROR: malformed bundle (no parts or no closing boundary)",
              file=sys.stderr)
        return 2

    out_dir.mkdir(parents=True, exist_ok=True)
    written = 0
    failed: list[tuple[str, str]] = []

    # Pair each start with its terminator (either the next start or the close).
    boundaries = starts + [end_idx]
    for i in range(len(starts)):
        part_start = boundaries[i]
        part_end = boundaries[i + 1]

        # Headers are key: value lines until a blank line.
        headers: dict[str, str] = {}
        body_start = part_start + 1
        for j in range(part_start + 1, part_end):
            ln = lines[j]
            if ln == "":
                body_start = j + 1
                break
            if ":" in ln:
                k, v = ln.split(":", 1)
                headers[k.strip()] = v.strip()
        else:
            body_start = part_end  # no body, e.g. empty file

        rel_path = headers.get("path")
        if not rel_path:
            failed.append((f"part-{i}", "missing 'path' header"))
            continue

        # Reconstruct body. We split on '\n' so joining with '\n' yields the
        # original text, BUT the original text was followed by '\n' before the
        # next boundary line. We need to recover that exact byte stream.
        # Strategy: take lines [body_start..part_end), join with '\n'.
        # If the original file ended without a trailing newline, the join is
        # exactly correct (last line was "". then boundary). If it ended
        # WITH a trailing newline, the join still produces the right bytes
        # because that trailing newline shows up as an extra "" line before
        # the boundary, and joining ["a","b",""] == "a\nb\n".
        body_lines = lines[body_start:part_end]
        body = "\n".join(body_lines)
        # Remove the single '\n' we added between body and next boundary line.
        # When the file is captured as text + "\n--boundary", split-on-'\n' for
        # the section becomes [text-line-1, ..., text-last-line, "" iff file
        # ended in newline]. The join above re-creates the exact text.
        # So no trim needed unless an extra trailing newline crept in.

        # Verify size + SHA256
        actual_size = len(body.encode("utf-8"))
        actual_sha = hashlib.sha256(body.encode("utf-8")).hexdigest()
        expected_size = headers.get("size")
        expected_sha = headers.get("sha256")
        size_ok = expected_size is None or actual_size == int(expected_size)
        sha_ok = expected_sha is None or actual_sha == expected_sha

        if not size_ok or not sha_ok:
            failed.append((
                rel_path,
                f"integrity check failed (size {actual_size} vs {expected_size}, "
                f"sha {actual_sha[:16]}… vs {expected_sha[:16] if expected_sha else '?'}…)"
            ))
            continue

        target = out_dir / rel_path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body, encoding="utf-8")
        written += 1

    print(f"[bundle] unpacked {written} files → {out_dir}")
    if expected_files is not None and written != expected_files:
        print(f"[bundle] WARNING: header said {expected_files} files, "
              f"unpacked {written}", file=sys.stderr)
    if failed:
        print(f"[bundle] WARNING: {len(failed)} part(s) failed:", file=sys.stderr)
        for path, reason in failed:
            print(f"  - {path}: {reason}", file=sys.stderr)
        return 1
    return 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> int:
    p = argparse.ArgumentParser(
        description="Pack/unpack a probe result directory into a single text file.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    sub = p.add_subparsers(dest="cmd", required=True)

    pack_p = sub.add_parser("pack", help="bundle a directory into a single text file")
    pack_p.add_argument("--in", dest="in_path", required=True,
                        help="Input directory (e.g., Result_2_redacted)")
    pack_p.add_argument("--out", dest="out_path", default=None,
                        help="Output file (default: <in>.bundle.txt or "
                             "<in>.bundle.b64.txt with --compress)")
    pack_p.add_argument("--compress", action="store_true",
                        help="gzip + base64-encode the bundle. Typical 4–8× "
                             "shrink. Use this when the plain bundle is too "
                             "big to paste into chat. Unpack auto-detects.")

    unpack_p = sub.add_parser("unpack", help="extract a bundle file back into a directory")
    unpack_p.add_argument("--in", dest="in_path", required=True,
                          help="Input bundle file")
    unpack_p.add_argument("--out", dest="out_path", default=None,
                          help="Output directory (default: <in>.unpacked)")

    args = p.parse_args()
    src = Path(args.in_path).resolve()

    if args.cmd == "pack":
        if args.out_path:
            out = Path(args.out_path).resolve()
        else:
            suffix = ".bundle.b64.txt" if args.compress else ".bundle.txt"
            out = src.with_name(src.name + suffix)
        return pack(src, out, compress=args.compress)
    else:  # unpack
        # Default: strip any known bundle suffix and append .unpacked
        if args.out_path:
            out_dir = Path(args.out_path).resolve()
        else:
            stem = src.name
            for suffix in (".bundle.b64.txt", ".bundle.txt", ".bundle", ".txt"):
                if stem.endswith(suffix):
                    stem = stem[: -len(suffix)]
                    break
            out_dir = src.with_name(stem + ".unpacked")
        return unpack(src, out_dir)


if __name__ == "__main__":
    sys.exit(main())
