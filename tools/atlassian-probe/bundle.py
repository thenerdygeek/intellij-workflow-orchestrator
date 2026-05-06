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
import datetime
import hashlib
import sys
import uuid
from pathlib import Path


HEADER_PREFIX = "# atlassian-probe bundle v"
BUNDLE_VERSION = "1"


# ---------------------------------------------------------------------------
# Pack
# ---------------------------------------------------------------------------

def pack(in_dir: Path, out_file: Path) -> int:
    if not in_dir.is_dir():
        print(f"ERROR: {in_dir} is not a directory", file=sys.stderr)
        return 2

    files: list[Path] = sorted(p for p in in_dir.rglob("*") if p.is_file())
    if not files:
        print(f"ERROR: {in_dir} is empty", file=sys.stderr)
        return 2

    # 16-hex-char boundary (~64 bits entropy). Statistically zero chance of
    # collision with any line of content in a probe result.
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
        parts.append("")  # blank line separating headers from body
        parts.append(text)
        # If the body did not end in a newline, the next boundary still
        # appears on its own line because we emit it as a separate parts entry
    parts.append(f"--{boundary}--")
    parts.append("")  # trailing newline

    out_file.write_text("\n".join(parts), encoding="utf-8")

    print(f"[bundle] packed {len(files)} files → {out_file}")
    print(f"[bundle] size: {out_file.stat().st_size} bytes")
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
                        help="Output file (default: <in>.bundle.txt)")

    unpack_p = sub.add_parser("unpack", help="extract a bundle file back into a directory")
    unpack_p.add_argument("--in", dest="in_path", required=True,
                          help="Input bundle file")
    unpack_p.add_argument("--out", dest="out_path", default=None,
                          help="Output directory (default: <in>.unpacked)")

    args = p.parse_args()
    src = Path(args.in_path).resolve()

    if args.cmd == "pack":
        out = (Path(args.out_path).resolve() if args.out_path
               else src.with_name(src.name + ".bundle.txt"))
        return pack(src, out)
    else:  # unpack
        # Default: strip the trailing .bundle.txt or use .unpacked suffix
        if args.out_path:
            out_dir = Path(args.out_path).resolve()
        else:
            stem = src.name
            for suffix in (".bundle.txt", ".bundle", ".txt"):
                if stem.endswith(suffix):
                    stem = stem[: -len(suffix)]
                    break
            out_dir = src.with_name(stem + ".unpacked")
        return unpack(src, out_dir)


if __name__ == "__main__":
    sys.exit(main())
