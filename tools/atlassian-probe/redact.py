#!/usr/bin/env python3
"""
Redact identifying values from probe output before sharing.

Reads tools/atlassian-probe/Result_N/ and writes Result_N_redacted/ next to it.
The original directory is left untouched so you can keep the un-redacted copy
locally for your own use; only the redacted copy is meant to be shared.

What's preserved (do NOT touch — needed for analysis):
  • HTTP status codes, response timings, request paths
  • JSON keys, nesting, types, presence/absence of fields
  • serverInfo product fields (version, versionNumbers, buildNumber, deploymentType, buildDate)
  • Field IDs (customfield_*)
  • Enum values: status.name, priority.name, issuetype.name, resolution.name,
                 statusCategory.name, project.projectTypeKey, etc.
  • Numeric IDs, booleans, timestamps
  • Free-text *length* (we replace `description: "the bug is ..."` with
    `description: "<redacted-text:142-chars>"` so payload size is still visible)

What's replaced (with stable per-run mapping so the same value always maps
to the same placeholder across files):
  • Source Jira hostname  → jira.redacted.example
  • Email addresses        → user-N@redacted.example
  • Issue keys (PROJ-123)  → KEY-N (e.g., KEY-001)
  • Project keys (PROJ)    → PROJ, PROJ2 ...
  • User names/displaynames in user-object contexts → user-N / "User N"
  • Avatar URLs            → https://redacted.example/avatar/N
  • Free-text fields       → <redacted-text:N-chars>
  • Commit hashes          → <commit-N> (stable per hash)
  • Dev-status branch displayIds (branch names) → <branch-N> (stable per name)

Usage:
    python redact.py --in Result_1
    python redact.py --in Result_1 --out Result_1_share

The mapping itself is NEVER written to disk — there's no key file you have
to keep secret. Once redacted, the original values are unrecoverable from
the output.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


# ---------------------------------------------------------------------------
# Stable mapping (per redact run; never persisted)
# ---------------------------------------------------------------------------

class SecretMap:
    def __init__(self) -> None:
        self.host: dict[str, str] = {}
        self.email: dict[str, str] = {}
        self.issue_key: dict[str, str] = {}
        self.project_key: dict[str, str] = {}
        self.user_name: dict[str, str] = {}
        self.user_display: dict[str, str] = {}
        self.commit: dict[str, str] = {}
        self.branch: dict[str, str] = {}
        self.avatar: dict[str, str] = {}
        self.url_fallback: dict[str, str] = {}

    def map_host(self, host: str) -> str:
        if host not in self.host:
            self.host[host] = "jira.redacted.example" if not self.host else f"jira{len(self.host)}.redacted.example"
        return self.host[host]

    def map_email(self, email: str) -> str:
        if email not in self.email:
            self.email[email] = f"user-{len(self.email) + 1}@redacted.example"
        return self.email[email]

    def map_issue_key(self, key: str) -> str:
        if key not in self.issue_key:
            # Preserve project-key consistency: same project → same redacted prefix
            project, num = key.split("-", 1)
            self.map_project_key(project)  # ensure project is mapped
            redacted_proj = self.project_key[project]
            self.issue_key[key] = f"{redacted_proj}-{len(self.issue_key) + 1:03d}"
        return self.issue_key[key]

    def map_project_key(self, project: str) -> str:
        if project not in self.project_key:
            self.project_key[project] = "PROJ" if not self.project_key else f"PROJ{len(self.project_key) + 1}"
        return self.project_key[project]

    def map_user_name(self, name: str) -> str:
        if name not in self.user_name:
            self.user_name[name] = f"user-{len(self.user_name) + 1}"
        return self.user_name[name]

    def map_user_display(self, display: str) -> str:
        if display not in self.user_display:
            self.user_display[display] = f"User {len(self.user_display) + 1}"
        return self.user_display[display]

    def map_commit(self, sha: str) -> str:
        if sha not in self.commit:
            self.commit[sha] = f"<commit-{len(self.commit) + 1}>"
        return self.commit[sha]

    def map_branch(self, branch: str) -> str:
        if branch not in self.branch:
            self.branch[branch] = f"<branch-{len(self.branch) + 1}>"
        return self.branch[branch]

    def map_avatar(self, url: str) -> str:
        if url not in self.avatar:
            self.avatar[url] = f"https://redacted.example/avatar/{len(self.avatar) + 1}"
        return self.avatar[url]

    def map_url_fallback(self, url: str) -> str:
        if url not in self.url_fallback:
            self.url_fallback[url] = f"https://redacted.example/url/{len(self.url_fallback) + 1}"
        return self.url_fallback[url]

    # Returns *all* mappings as flat str→str pairs (longest-first) for summary.md text replace.
    def all_pairs(self) -> list[tuple[str, str]]:
        pairs: list[tuple[str, str]] = []
        for d in (
            self.host, self.email, self.issue_key, self.project_key,
            self.user_name, self.user_display, self.commit, self.branch,
            self.avatar, self.url_fallback,
        ):
            pairs.extend(d.items())
        # Longest first so we don't sub `PROJ` before `PROJ-123`
        pairs.sort(key=lambda kv: len(kv[0]), reverse=True)
        return pairs


# ---------------------------------------------------------------------------
# Patterns
# ---------------------------------------------------------------------------

EMAIL_RE = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")
# Jira issue key — strict: at least one letter, then any letters/digits, then dash, then digits
ISSUE_KEY_RE = re.compile(r"\b([A-Z][A-Z0-9]{1,9})-(\d{1,7})\b")
COMMIT_HASH_RE = re.compile(r"\b[0-9a-f]{7,40}\b")
URL_RE = re.compile(r"https?://[^\s\"<>]+")

# Field-context rules
USER_INDICATOR_KEYS = {"emailAddress", "avatarUrls", "accountId", "active"}
USER_NAME_FIELDS = {"name", "displayName", "key", "username", "emailAddress", "slug"}
# When `name` appears inside one of these parent keys, it's an enum value, NOT a user.
PRESERVE_NAME_PARENTS = {
    "status", "statusCategory", "priority", "issuetype", "resolution",
    "category", "category", "type", "permission", "permissionName",
    "scope", "watcher",  # NB: watcher.user.name still gets redacted via in_user_obj
}
TEXT_VALUE_FIELDS = {
    "description", "summary", "body", "message", "comment", "title",
    "renderedDescription", "renderedBody",
}
COMMIT_HASH_FIELDS = {"latestCommit", "id", "fromHash", "toHash"}
BRANCH_NAME_FIELDS = {"displayId"}


# ---------------------------------------------------------------------------
# Redaction
# ---------------------------------------------------------------------------

class Redactor:
    def __init__(self, source_host: str, smap: SecretMap):
        self.smap = smap
        # Pre-register the source host
        self.smap.map_host(source_host)
        self.source_host = source_host

    def redact_string(self, s: str) -> str:
        # Always: known hosts → mapped
        for original, mapped in list(self.smap.host.items()):
            s = s.replace(original, mapped)
        # Other URLs: replace whole URL (host portion not yet mapped)
        def url_sub(m: re.Match[str]) -> str:
            url = m.group(0)
            # Skip if already redacted (contains 'redacted.example')
            if "redacted.example" in url:
                return url
            try:
                host = urlparse(url).hostname or ""
                # If we already have this host mapped, swap host inline
                if host in self.smap.host:
                    return url.replace(host, self.smap.host[host])
            except Exception:
                pass
            return self.smap.map_url_fallback(url)
        s = URL_RE.sub(url_sub, s)
        # Emails
        s = EMAIL_RE.sub(lambda m: self.smap.map_email(m.group(0)), s)
        # Issue keys
        s = ISSUE_KEY_RE.sub(lambda m: self.smap.map_issue_key(m.group(0)), s)
        return s

    def redact_node(self, node: Any, parent_key: str | None = None,
                    in_user_obj: bool = False) -> Any:
        if isinstance(node, dict):
            return self._redact_dict(node, parent_key, in_user_obj)
        if isinstance(node, list):
            return [self.redact_node(x, parent_key=parent_key, in_user_obj=in_user_obj)
                    for x in node]
        if isinstance(node, str):
            return self._redact_str_in_context(node, parent_key, in_user_obj)
        # numbers / booleans / None — preserved verbatim
        return node

    def _redact_dict(self, node: dict[str, Any], parent_key: str | None,
                     in_user_obj: bool) -> dict[str, Any]:
        # Special case: the wrapper file format ({"result": {...}, "raw_body": {...}})
        # → keep wrapper keys, just recurse
        is_user_here = in_user_obj or any(k in node for k in USER_INDICATOR_KEYS)
        out: dict[str, Any] = {}
        for k, v in node.items():
            # avatarUrls is a dict of size→url; redact as URLs
            if k == "avatarUrls" and isinstance(v, dict):
                out[k] = {sz: self.smap.map_avatar(url) if isinstance(url, str) else url
                          for sz, url in v.items()}
                continue

            # Preserve enum names: parent_key in PRESERVE_NAME_PARENTS and key=='name'
            if k == "name" and parent_key in PRESERVE_NAME_PARENTS and isinstance(v, str):
                out[k] = v
                continue

            # User-object name/displayName/key fields
            if is_user_here and k in USER_NAME_FIELDS and isinstance(v, str):
                if k == "displayName":
                    out[k] = self.smap.map_user_display(v)
                elif k == "emailAddress":
                    out[k] = self.smap.map_email(v)
                elif k == "key" and ISSUE_KEY_RE.fullmatch(v):
                    out[k] = self.smap.map_issue_key(v)
                else:
                    out[k] = self.smap.map_user_name(v)
                continue

            # Free-text fields → length-preserving placeholder
            if k in TEXT_VALUE_FIELDS and isinstance(v, str):
                out[k] = f"<redacted-text:{len(v)}-chars>"
                continue

            # Commit hash fields
            if k in COMMIT_HASH_FIELDS and isinstance(v, str) and COMMIT_HASH_RE.fullmatch(v):
                out[k] = self.smap.map_commit(v)
                continue

            # Branch displayId in dev-status context (parent ~ branches/ref)
            if (k in BRANCH_NAME_FIELDS and isinstance(v, str)
                    and not COMMIT_HASH_RE.fullmatch(v)
                    and not ISSUE_KEY_RE.fullmatch(v)):
                # Avoid clobbering numeric/short ids
                if any(c in v for c in "/-_") or len(v) > 8:
                    out[k] = self.smap.map_branch(v)
                    continue

            out[k] = self.redact_node(v, parent_key=k, in_user_obj=is_user_here)
        return out

    def _redact_str_in_context(self, s: str, parent_key: str | None,
                                in_user_obj: bool) -> str:
        # User-name field at top level (some endpoints return arrays of users)
        if in_user_obj and parent_key in USER_NAME_FIELDS:
            return self.smap.map_user_name(s)
        return self.redact_string(s)


# ---------------------------------------------------------------------------
# File processing
# ---------------------------------------------------------------------------

# serverInfo is special — preserve everything except baseUrl/scmInfo (urls)
SERVER_INFO_PRESERVE = {
    "version", "versionNumbers", "buildNumber", "buildDate", "deploymentType",
    "serverTitle", "scmInfo", "databaseBuildNumber",
}


def redact_serverinfo_body(body: dict[str, Any], red: Redactor) -> dict[str, Any]:
    out = dict(body)
    for k in ("baseUrl", "serverTitle"):
        if k in out and isinstance(out[k], str):
            out[k] = red.redact_string(out[k])
    return out


def detect_source_host(result_dir: Path) -> str:
    """Sniff the source host from any raw response — typically the JSON body's `self` field."""
    # Try a few well-known files
    for candidate in ("serverInfo.json", "myself.json", "search_jql_v2.json",
                      "issue_basic.json", "boards.json"):
        f = result_dir / "raw" / candidate
        if not f.exists():
            continue
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
        except Exception:
            continue
        body = data.get("raw_body")
        # `self` URLs in Jira responses look like https://host/rest/api/2/...
        candidate_urls = []
        if isinstance(body, dict):
            if isinstance(body.get("self"), str):
                candidate_urls.append(body["self"])
            if isinstance(body.get("baseUrl"), str):
                candidate_urls.append(body["baseUrl"])
        # Also dig into nested objects for self URLs
        def walk(n: Any) -> None:
            if isinstance(n, dict):
                v = n.get("self")
                if isinstance(v, str):
                    candidate_urls.append(v)
                for vv in n.values():
                    walk(vv)
            elif isinstance(n, list):
                for x in n[:5]:
                    walk(x)
        walk(body)
        for url in candidate_urls:
            try:
                host = urlparse(url).hostname
                if host:
                    return host
            except Exception:
                continue
    raise SystemExit(
        "ERROR: Could not detect source host from raw responses. "
        "Pass --source-host explicitly."
    )


def process_file(raw_path: Path, out_path: Path, red: Redactor) -> None:
    data = json.loads(raw_path.read_text(encoding="utf-8"))
    # Wrapper: {"result": {...metadata...}, "raw_body": <json>}
    result_meta = data.get("result", {})
    raw_body = data.get("raw_body")

    # Redact metadata.path is fine (no host)
    # Redact metadata.payload_preview which may contain sample content
    if isinstance(result_meta, dict):
        for k in ("payload_preview",):
            if k in result_meta:
                result_meta[k] = red.redact_node(result_meta[k], parent_key=k)
        # notes + description: text replace via mappings happens later via summary pass
        if isinstance(result_meta.get("notes"), list):
            result_meta["notes"] = [red.redact_string(n) if isinstance(n, str) else n
                                     for n in result_meta["notes"]]
        if isinstance(result_meta.get("description"), str):
            result_meta["description"] = red.redact_string(result_meta["description"])
        if isinstance(result_meta.get("error"), str):
            result_meta["error"] = red.redact_string(result_meta["error"])
        if isinstance(result_meta.get("path"), str):
            # Issue keys / project keys may appear in the path itself
            result_meta["path"] = red.redact_string(result_meta["path"])

    # Redact body
    if raw_path.name == "serverInfo.json" and isinstance(raw_body, dict):
        redacted_body = redact_serverinfo_body(raw_body, red)
    elif raw_body is None:
        redacted_body = None
    else:
        redacted_body = red.redact_node(raw_body)

    out = {"result": result_meta, "raw_body": redacted_body}
    out_path.write_text(json.dumps(out, indent=2, default=str), encoding="utf-8")


def redact_summary_md(summary_text: str, smap: SecretMap) -> str:
    out = summary_text
    for original, mapped in smap.all_pairs():
        out = out.replace(original, mapped)
    # Catch-all email regex (in case any escaped through)
    out = EMAIL_RE.sub(lambda m: smap.map_email(m.group(0)), out)
    out = ISSUE_KEY_RE.sub(lambda m: smap.map_issue_key(m.group(0)), out)
    return out


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> int:
    p = argparse.ArgumentParser(
        description="Redact identifying values from probe Result_N/ output before sharing.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    p.add_argument("--in", dest="in_dir", required=True,
                   help="Probe result dir (e.g., Result_1)")
    p.add_argument("--out", dest="out_dir", default=None,
                   help="Output dir (default: <in>_redacted)")
    p.add_argument("--source-host", default=None,
                   help="Jira hostname to redact (default: auto-detect from raw bodies)")
    args = p.parse_args()

    in_dir = Path(args.in_dir).resolve()
    if not in_dir.is_dir():
        print(f"ERROR: {in_dir} is not a directory", file=sys.stderr)
        return 2

    raw_in = in_dir / "raw"
    if not raw_in.is_dir():
        print(f"ERROR: {raw_in} not found — is this a probe Result_N dir?", file=sys.stderr)
        return 2

    out_dir = Path(args.out_dir).resolve() if args.out_dir else in_dir.with_name(in_dir.name + "_redacted")
    raw_out = out_dir / "raw"
    raw_out.mkdir(parents=True, exist_ok=True)

    source_host = args.source_host or detect_source_host(in_dir)
    print(f"[redact] source host: {source_host} → jira.redacted.example")
    print(f"[redact] in:  {in_dir}")
    print(f"[redact] out: {out_dir}")

    smap = SecretMap()
    red = Redactor(source_host, smap)

    raw_files = sorted(raw_in.glob("*.json"))
    for raw_path in raw_files:
        out_path = raw_out / raw_path.name
        try:
            process_file(raw_path, out_path, red)
        except Exception as e:
            print(f"  ! {raw_path.name}: {type(e).__name__}: {e}", file=sys.stderr)
            continue
        print(f"  ✓ {raw_path.name}")

    # Process summary.md — text replacement using accumulated map
    summary_in = in_dir / "summary.md"
    if summary_in.exists():
        redacted = redact_summary_md(summary_in.read_text(encoding="utf-8"), smap)
        (out_dir / "summary.md").write_text(redacted, encoding="utf-8")
        print(f"  ✓ summary.md")

    # Mapping report — counts only, never the original→mapped values
    report = {
        "redacted_counts": {
            "hosts": len(smap.host),
            "emails": len(smap.email),
            "issue_keys": len(smap.issue_key),
            "project_keys": len(smap.project_key),
            "user_names": len(smap.user_name),
            "user_display_names": len(smap.user_display),
            "commits": len(smap.commit),
            "branches": len(smap.branch),
            "avatars": len(smap.avatar),
            "fallback_urls": len(smap.url_fallback),
        },
        "files_processed": len(raw_files),
    }
    (out_dir / "redaction_report.json").write_text(
        json.dumps(report, indent=2), encoding="utf-8"
    )

    print(f"\n[redact] done — share {out_dir / 'summary.md'} and any specific {out_dir / 'raw'} files.")
    print(f"[redact] redaction_report.json shows counts (no original values).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
