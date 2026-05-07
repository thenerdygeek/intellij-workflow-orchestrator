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
  • Output filenames whose basename matches a CUSTOM_REDACT_WORDS marker
    are themselves redacted (extension preserved). Otherwise the bundle
    step would leak the marker via `path:` headers.

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
import random
import re
import string
import sys
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


# ---------------------------------------------------------------------------
# CUSTOM REDACT WORDS — edit this list freely
# ---------------------------------------------------------------------------
# Each entry is a **substring marker**. The redactor finds any run of
# letters/digits/underscores/hyphens/spaces that contains the marker
# (case-insensitively, anywhere within the run), and replaces the ENTIRE run
# with a same-length random string. Letters become random letters with case
# preserved per character, digits become random digits, and spaces/hyphens/
# underscores inside the run are kept verbatim at their exact positions.
#
# Concrete behaviour for marker "claud":
#   "claud"             → 5-char random lowercase
#   "claude"            → 6-char random lowercase
#   "claudia"           → 7-char random lowercase
#   "claude123"         → 6 lowercase + 3 digits, e.g. "abcdef987"
#   "CLAUD"             → 5-char UPPERCASE random
#   "ClAUdE"            → case mirrored u,l,u,u,l,u → e.g. "AbcDEf" (6 chars)
#   "CLauDe145"         → matches the whole run, 9 chars
#   "subclaude"         → matches the whole run "subclaude" (9 chars)
#   "1claude"           → matches the whole run "1claude" (7 chars: digit + 6 lower)
#   "ClaUDe-1234"       → matches "ClaUDe-1234" (hyphen inside the run);
#                         hyphen kept at position 7, letters/digits randomized
#   "AcmeCorp internal" → matches the whole phrase (space inside the run);
#                         space kept at position 8
#
# A "run" ends at any character that's NOT in [a-zA-Z0-9_\- ]. So:
#   "hello.subclaude.world"  → only "subclaude" is a run-with-marker;
#                              "hello." and ".world" preserved (dot breaks runs)
#   "v1.2.3 claud"           → "v1", "2", "3", " claud" — the last run
#                              contains the marker and gets redacted (along
#                              with its leading space)
#
# Stable per-run mapping: the same exact string maps to the same fake string
# within one redact.py invocation (so "Claude" in 5 files → same fake string).
# Different case variants ("Claude" vs "CLAUDE") map independently because
# their per-character case patterns differ. Markers like "claud" with
# different surrounding context (e.g. "claude" vs "claude123") produce
# different fakes because the matched RUN is different. Random output
# regenerates on every run.
#
# If a marker itself contains non-allowed characters (e.g. dots), only the
# first alphanumeric/space/hyphen/underscore piece is used as the marker.
# Use multiple entries for multi-segment names like "foo.bar.com".
#
# Add company names, internal product names, code names, team/repo names —
# anything that would identify your environment. Hostnames/emails/issue keys/
# user display names are already handled by the structured redactors;
# free-text fields are length-redacted.
CUSTOM_REDACT_WORDS: list[str] = [
    # Examples — replace with your own. Delete example lines before sharing.
    # "claud",
    # "acme",
    # "redroom",
]
# ---------------------------------------------------------------------------


# ---------------------------------------------------------------------------
# Stable mapping (per redact run; never persisted)
# ---------------------------------------------------------------------------

class SecretMap:
    """Bidirectional mapping from original→redacted values.

    All `map_*` methods are **idempotent**: passing in an already-redacted value
    returns it unchanged. This is what stops re-redaction (e.g. `user-2@…`
    getting re-mapped to `user-3@…` if the regex sweep runs twice).
    """

    def __init__(self, custom_words: list[str] | None = None,
                 rng: random.Random | None = None) -> None:
        """
        rng: random source for custom-word replacement. Default is
        random.SystemRandom() which is backed by os.urandom and is
        cryptographically secure — no internal PRNG state to reconstruct.
        Tests pass a seeded random.Random for reproducibility.
        """
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
        # Custom-word mapping is keyed by the lowercased original so case
        # variants ("AcmeCorp" / "ACMECORP") collapse to the same redaction.
        self.custom: dict[str, str] = {}
        # Inverse sets — values that are already redacted placeholders
        self._redacted_values: set[str] = set()

        # SystemRandom is os.urandom-backed → cryptographically secure, no
        # MT state for an attacker to recover from observing the output.
        # Tests can inject a seeded random.Random for reproducibility.
        self._rng = rng or random.SystemRandom()
        # Each user entry is a SUBSTRING MARKER. The redactor finds any run of
        # [a-zA-Z0-9_\- ] characters that contains the marker anywhere inside
        # it, and redacts the whole run. Markers with non-allowed characters
        # (e.g. dots) are split on those characters; the first non-empty piece
        # is used (so "foo.bar" registers as "foo"; users wanting both pieces
        # should add them as separate entries).
        markers: list[str] = []
        for w in (custom_words or []):
            if not w or not w.strip():
                continue
            # Strip non-allowed chars; if the marker has internal dots/etc.,
            # only the first piece is taken.
            pieces = [p for p in re.split(r"[^a-zA-Z0-9_\- ]+", w.strip()) if p.strip()]
            for piece in pieces:
                # Strip leading/trailing whitespace from the marker itself,
                # but preserve internal spaces (so "Internal Project" stays as is).
                cleaned = piece.strip(" -_")
                if cleaned:
                    markers.append(cleaned)
        # Dedupe (case-insensitive) preserving longest-first so a longer
        # marker wins over a shorter substring of itself when both are listed.
        seen: set[str] = set()
        unique_markers: list[str] = []
        for m in sorted(markers, key=len, reverse=True):
            ml = m.lower()
            if ml not in seen:
                seen.add(ml)
                unique_markers.append(m)
        self._custom_words = unique_markers
        if unique_markers:
            joined = "|".join(re.escape(m) for m in unique_markers)
            # ALLOWED  — characters that constitute a "run": letters, digits,
            #            underscores, hyphens, and spaces.
            # The pattern matches:
            #   (?<!ALLOWED)            — start at a run boundary
            #   ALLOWED*?               — any leading run characters (non-greedy)
            #   (?:m1|m2|...)           — the marker substring (case-insensitive)
            #   ALLOWED*                — any trailing run characters (greedy)
            #   (?!ALLOWED)             — end at a run boundary
            allowed_class = r"[a-zA-Z0-9_\- ]"
            self._custom_re = re.compile(
                rf"(?<!{allowed_class}){allowed_class}*?(?:{joined}){allowed_class}*(?!{allowed_class})",
                re.IGNORECASE,
            )
        else:
            self._custom_re = None

    def _remember(self, redacted: str) -> str:
        self._redacted_values.add(redacted)
        return redacted

    def is_already_redacted(self, value: str) -> bool:
        return value in self._redacted_values

    def map_host(self, host: str) -> str:
        if host in self._redacted_values:
            return host
        if host not in self.host:
            redacted = "jira.redacted.example" if not self.host else f"jira{len(self.host)}.redacted.example"
            self.host[host] = self._remember(redacted)
        return self.host[host]

    def map_email(self, email: str) -> str:
        if email in self._redacted_values:
            return email
        if email not in self.email:
            redacted = f"user-{len(self.email) + 1}@redacted.example"
            self.email[email] = self._remember(redacted)
        return self.email[email]

    def map_issue_key(self, key: str) -> str:
        if key in self._redacted_values:
            return key
        if key not in self.issue_key:
            project, _ = key.split("-", 1)
            self.map_project_key(project)
            redacted_proj = self.project_key[project]
            redacted = f"{redacted_proj}-{len(self.issue_key) + 1:03d}"
            self.issue_key[key] = self._remember(redacted)
        return self.issue_key[key]

    def map_project_key(self, project: str) -> str:
        if project in self._redacted_values:
            return project
        if project not in self.project_key:
            redacted = "PROJ" if not self.project_key else f"PROJ{len(self.project_key) + 1}"
            self.project_key[project] = self._remember(redacted)
        return self.project_key[project]

    def map_user_name(self, name: str) -> str:
        if name in self._redacted_values:
            return name
        if name not in self.user_name:
            redacted = f"user-{len(self.user_name) + 1}"
            self.user_name[name] = self._remember(redacted)
        return self.user_name[name]

    def map_user_display(self, display: str) -> str:
        if display in self._redacted_values:
            return display
        if display not in self.user_display:
            redacted = f"User {len(self.user_display) + 1}"
            self.user_display[display] = self._remember(redacted)
        return self.user_display[display]

    def map_commit(self, sha: str) -> str:
        if sha in self._redacted_values:
            return sha
        if sha not in self.commit:
            redacted = f"<commit-{len(self.commit) + 1}>"
            self.commit[sha] = self._remember(redacted)
        return self.commit[sha]

    def map_branch(self, branch: str) -> str:
        if branch in self._redacted_values:
            return branch
        if branch not in self.branch:
            redacted = f"<branch-{len(self.branch) + 1}>"
            self.branch[branch] = self._remember(redacted)
        return self.branch[branch]

    def map_avatar(self, url: str) -> str:
        if url in self._redacted_values:
            return url
        if url not in self.avatar:
            redacted = f"https://redacted.example/avatar/{len(self.avatar) + 1}"
            self.avatar[url] = self._remember(redacted)
        return self.avatar[url]

    def map_url_fallback(self, url: str) -> str:
        if url in self._redacted_values:
            return url
        if url not in self.url_fallback:
            redacted = f"https://redacted.example/url/{len(self.url_fallback) + 1}"
            self.url_fallback[url] = self._remember(redacted)
        return self.url_fallback[url]

    def map_custom_word(self, original: str) -> str:
        """Generate (and cache) a same-length random replacement for a matched run.

        Letters become random letters with case preserved per character, digits
        become random digits. The same exact input always maps to the same
        output within one run (so "Claude" in three files → same fake string).
        Different case variants of the same word ("Claude" vs "CLAUDE") map
        independently because their per-character case patterns differ — and a
        random replacement that mirrors case is the goal of this feature.
        """
        if original in self.custom:
            return self.custom[original]
        if original in self._redacted_values:
            return original
        chars: list[str] = []
        for ch in original:
            if ch.isupper():
                chars.append(self._rng.choice(string.ascii_uppercase))
            elif ch.islower():
                chars.append(self._rng.choice(string.ascii_lowercase))
            elif ch.isdigit():
                chars.append(self._rng.choice(string.digits))
            else:
                # Defense in depth — alphanumeric runs shouldn't contain
                # anything else, but if they ever do, keep the char verbatim.
                chars.append(ch)
        replacement = "".join(chars)
        self.custom[original] = self._remember(replacement)
        return replacement

    def apply_custom_words(self, s: str) -> str:
        """Replace every match of the configured custom-word regex in `s`."""
        if self._custom_re is None:
            return s
        return self._custom_re.sub(lambda m: self.map_custom_word(m.group(0)), s)

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
        # Order matters here:
        #   1. Hostname substring replace runs FIRST so a custom word that
        #      happens to overlap the host (e.g. "AcmeCorp" inside
        #      "jira.acmecorp.com") doesn't shred the host pattern before the
        #      structured replacer sees it. After this pass the host is the
        #      clean placeholder "jira.redacted.example".
        #   2. URL/email/issue-key structured replacements run next.
        #   3. Custom words run LAST so they can only catch what the
        #      structured replacers left behind (free-text fields, error
        #      messages, label values, custom workflow status names, etc.).
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
        # Custom words last — only catches what's still recognizable as
        # company/product names after structured replacement
        s = self.smap.apply_custom_words(s)
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
            # We still apply custom words here — a custom workflow status like
            # "AcmeCorp Reviewed" should have "AcmeCorp" redacted while keeping
            # the rest of the enum value visible.
            if k == "name" and parent_key in PRESERVE_NAME_PARENTS and isinstance(v, str):
                out[k] = self.smap.apply_custom_words(v)
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

            # `displayId` is overloaded in dev-status responses:
            #  - inside a *branch*: branch name (sometimes path-like, sometimes a short ref)
            #  - inside a *commit*: short commit hash (e.g. `8a7b6c5`)
            # Dispatch by content shape, not field name alone.
            if k == "displayId" and isinstance(v, str):
                if COMMIT_HASH_RE.fullmatch(v):
                    out[k] = self.smap.map_commit(v)
                    continue
                if "/" in v or len(v) > 8:
                    out[k] = self.smap.map_branch(v)
                    continue

            # `name` field can be a branch path when it contains slashes —
            # branch names like `feature/ACME-1234-fix-safari-jwt` would otherwise
            # leak the descriptor even though the issue key gets swapped.
            if (k == "name" and isinstance(v, str) and "/" in v
                    and not is_user_here
                    and parent_key not in PRESERVE_NAME_PARENTS):
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


def detect_source_host(result_dir: Path) -> str | None:
    """Sniff the source host from any raw response — typically the JSON body's `self` field.

    Returns None if no host could be found (e.g. every probe failed with a network
    error so all raw_body values are null). Caller decides whether that's fatal."""
    # Walk every raw file, not just well-known names — handles partial probe runs.
    raw_dir = result_dir / "raw"
    for f in sorted(raw_dir.glob("*.json")):
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
        except Exception:
            continue
        body = data.get("raw_body")
        if not body:
            continue
        candidate_urls: list[str] = []
        if isinstance(body, dict):
            if isinstance(body.get("self"), str):
                candidate_urls.append(body["self"])
            if isinstance(body.get("baseUrl"), str):
                candidate_urls.append(body["baseUrl"])

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
    return None


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


def redact_filename(name: str, smap: SecretMap) -> str:
    """Apply CUSTOM_REDACT_WORDS markers to a filename basename.

    The extension (last dot-separated segment) is preserved verbatim —
    extensions are structural (json/md/txt) and shouldn't carry company or
    product names. Hidden files (`.bashrc`) and extensionless names are
    redacted as a single token. Mapping is shared with `apply_custom_words`,
    so the same marker run produces the same fake string in filenames and in
    file content (e.g. summary.md cross-references stay consistent).
    """
    if "." not in name or name.startswith("."):
        return smap.apply_custom_words(name)
    stem, dot, ext = name.rpartition(".")
    return smap.apply_custom_words(stem) + dot + ext


def redact_summary_md(summary_text: str, smap: SecretMap) -> str:
    """Apply the accumulated mapping to summary.md as plain text replacement.

    We do **not** run EMAIL_RE / ISSUE_KEY_RE here. By the time summary.md is
    processed, every email/key the probe captured is already in `smap` from the
    raw-file pass, so the text-replace covers them. Re-running the regex would
    catch already-redacted values (e.g. `user-2@redacted.example`) and re-map
    them, producing inconsistent IDs across raw files vs. summary."""
    # Structured replacements first so the host/email/issue-key patterns
    # in notes columns get clean placeholders before custom words can shred
    # the host string. Custom words run last to catch leftover company/
    # product names in free-text columns.
    out = summary_text
    for original, mapped in smap.all_pairs():
        out = out.replace(original, mapped)
    out = smap.apply_custom_words(out)
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
    if not source_host:
        print(
            "[redact] WARNING: could not auto-detect source host — every probe response "
            "appears to be empty/error. The redactor will still run (emails, issue keys, "
            "free text get replaced) but URLs from your Jira instance will pass through "
            "unchanged. If you actually had successful probes, pass --source-host yourjira.example.com.",
            file=sys.stderr,
        )
        source_host = "unknown.host.example"
    print(f"[redact] source host: {source_host} → jira.redacted.example")
    print(f"[redact] in:  {in_dir}")
    print(f"[redact] out: {out_dir}")

    smap = SecretMap(custom_words=CUSTOM_REDACT_WORDS)
    red = Redactor(source_host, smap)
    if CUSTOM_REDACT_WORDS:
        print(f"[redact] custom words active ({len(CUSTOM_REDACT_WORDS)}): "
              f"{', '.join(repr(w) for w in CUSTOM_REDACT_WORDS[:5])}"
              f"{' …' if len(CUSTOM_REDACT_WORDS) > 5 else ''}")

    raw_files = sorted(raw_in.glob("*.json"))
    for raw_path in raw_files:
        out_name = redact_filename(raw_path.name, smap)
        out_path = raw_out / out_name
        try:
            process_file(raw_path, out_path, red)
        except Exception as e:
            print(f"  ! {raw_path.name}: {type(e).__name__}: {e}", file=sys.stderr)
            continue
        if out_name == raw_path.name:
            print(f"  ✓ {out_name}")
        else:
            print(f"  ✓ {out_name}  (filename redacted)")

    # Process summary.md — text replacement using accumulated map.
    # Filename also goes through the same redactor for consistency, though
    # "summary" itself is unlikely to match a CUSTOM_REDACT_WORDS marker.
    summary_in = in_dir / "summary.md"
    if summary_in.exists():
        redacted = redact_summary_md(summary_in.read_text(encoding="utf-8"), smap)
        summary_out_name = redact_filename("summary.md", smap)
        (out_dir / summary_out_name).write_text(redacted, encoding="utf-8")
        if summary_out_name == "summary.md":
            print(f"  ✓ {summary_out_name}")
        else:
            print(f"  ✓ {summary_out_name}  (filename redacted)")

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
            "custom_words": len(smap.custom),
        },
        "custom_words_configured": len(CUSTOM_REDACT_WORDS),
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
