#!/usr/bin/env python3
"""
Jira Server / Data Center API probe for the Workflow Orchestrator IntelliJ plugin.

Read-only. Verifies every endpoint the plugin currently calls + tests candidate
endpoints we may want to adopt. Writes per-endpoint JSON + a Markdown summary
to tools/atlassian-probe/Result_N/.

Usage examples:
    # Just detect version + permissions (~2 calls, no params needed)
    python probe_jira.py --url https://jira.company.com --token PAT --versions-only

    # Full sweep (needs a real issue, board, project for realistic responses)
    python probe_jira.py --url https://jira.company.com --token PAT \
        --issue-key PROJ-123 --board-id 42 --project-key PROJ

    # Self-signed cert
    python probe_jira.py ... --no-verify

The script never executes mutations (transitions, comments, worklogs, watchers, etc.)
— only HTTP GETs against the listed endpoints. Output JSON files capture body, status,
elapsed time, and (where applicable) capability hints.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.parse
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Any, Optional

try:
    import requests
except ImportError:
    print("This probe requires `requests`. Install with: pip install requests", file=sys.stderr)
    sys.exit(2)


# ---------------------------------------------------------------------------
# Probe definitions
# ---------------------------------------------------------------------------

@dataclass
class ProbeResult:
    name: str                 # short id, becomes raw/<id>.json
    description: str
    method: str
    path: str
    status: int = 0
    ok: bool = False
    elapsed_ms: int = 0
    payload_kind: str = ""    # "json" | "text" | "empty" | "error"
    payload_preview: Any = None
    error: Optional[str] = None
    notes: list[str] = field(default_factory=list)
    category: str = ""        # "version" | "existing" | "candidate" | "internal"


# ---------------------------------------------------------------------------
# Probe runner
# ---------------------------------------------------------------------------

class JiraProbe:
    def __init__(self, base_url: str, token: str, verify: bool, results_dir: Path):
        self.base = base_url.rstrip("/")
        self.session = requests.Session()
        self.session.headers.update({
            "Authorization": f"Bearer {token}",
            "Accept": "application/json",
            "User-Agent": "WorkflowOrchestrator-Probe/1.0 (read-only)",
        })
        self.session.verify = verify
        self.results_dir = results_dir
        self.raw_dir = results_dir / "raw"
        self.raw_dir.mkdir(parents=True, exist_ok=True)
        self.results: list[ProbeResult] = []

    # -- low-level request helper ----------------------------------------------

    def _request(self, name: str, description: str, path: str, category: str,
                 method: str = "GET", json_body: Optional[dict[str, Any]] = None,
                 notes: Optional[list[str]] = None, expect_json: bool = True) -> ProbeResult:
        """Issue a single HTTP request and persist its outcome.

        POST is allowed only for endpoints that are functionally search/lookup
        (e.g. /rest/api/3/search/jql, /rest/api/2/search/approximate-count) —
        no Jira state ever changes from a probe call. The User-Agent string
        and `(read-only)` marker make this auditable in Jira's access logs.
        """
        url = f"{self.base}{path}"
        result = ProbeResult(
            name=name, description=description, method=method, path=path,
            category=category, notes=list(notes or []),
        )
        start = time.perf_counter()
        raw_payload: Any = None
        try:
            if method == "GET":
                resp = self.session.get(url, timeout=30, allow_redirects=False)
            elif method == "POST":
                resp = self.session.post(
                    url,
                    json=json_body if json_body is not None else {},
                    timeout=30,
                    allow_redirects=False,
                )
            else:
                raise ValueError(f"Unsupported method: {method}")
            result.status = resp.status_code
            result.elapsed_ms = int((time.perf_counter() - start) * 1000)
            result.ok = 200 <= resp.status_code < 300

            content_type = (resp.headers.get("Content-Type") or "").lower()
            body = resp.text or ""
            if not body:
                result.payload_kind = "empty"
            elif expect_json and ("json" in content_type):
                try:
                    raw_payload = resp.json()
                    result.payload_kind = "json"
                    result.payload_preview = _summarize_json(raw_payload)
                except ValueError:
                    result.payload_kind = "text"
                    result.payload_preview = body[:500]
                    result.notes.append("Content-Type claimed JSON but body did not parse")
            else:
                result.payload_kind = "text"
                result.payload_preview = body[:500]
                if not expect_json:
                    result.notes.append(f"Non-JSON response (Content-Type={content_type})")

            if 300 <= resp.status_code < 400:
                result.notes.append(
                    f"Redirect to {resp.headers.get('Location', '?')} "
                    "(plugin's HttpClientFactory followRedirects=true; AuthTestService=false)"
                )
        except requests.RequestException as e:
            result.error = f"{type(e).__name__}: {e}"
            result.payload_kind = "error"
            result.elapsed_ms = int((time.perf_counter() - start) * 1000)

        # Write raw response for diffing across probe runs
        raw_file = self.raw_dir / f"{name}.json"
        raw_file.write_text(json.dumps({
            "result": asdict(result),
            "request_body": json_body,  # null for GET; helps reproduce POST probes later
            "raw_body": raw_payload if raw_payload is not None else None,
        }, indent=2, default=str), encoding="utf-8")
        self.results.append(result)
        return result

    def _get(self, name: str, description: str, path: str, category: str,
             notes: Optional[list[str]] = None, expect_json: bool = True) -> ProbeResult:
        return self._request(name, description, path, category,
                             method="GET", notes=notes, expect_json=expect_json)

    def _post(self, name: str, description: str, path: str, category: str,
              json_body: dict[str, Any],
              notes: Optional[list[str]] = None, expect_json: bool = True) -> ProbeResult:
        return self._request(name, description, path, category,
                             method="POST", json_body=json_body,
                             notes=notes, expect_json=expect_json)

    # -- modes ----------------------------------------------------------------

    def run_versions_only(self) -> None:
        print("[probe] versions-only mode\n")
        self._get(
            name="serverInfo",
            description="Server version + deployment type",
            path="/rest/api/2/serverInfo",
            category="version",
            notes=["Use this to fill in the Jira version question once you run the probe"],
        )
        self._get(
            name="myself",
            description="Connection check + current-user identity",
            path="/rest/api/2/myself",
            category="version",
        )
        self._get(
            name="myself_expanded",
            description="Current user + groups + applicationRoles (candidate)",
            path="/rest/api/2/myself?expand=groups,applicationRoles",
            category="candidate",
            notes=["Useful for showing role badges in the onboarding banner"],
        )
        self._get(
            name="mypermissions",
            description="Global permissions for the connected user (candidate)",
            path="/rest/api/2/mypermissions",
            category="candidate",
            notes=["Use to gate transition/comment buttons in UI before user clicks"],
        )

    def run_discover(self) -> None:
        """Discovery mode — derives values to feed into the full sweep from
        the user's actual work, not from a global dump of the Jira instance.

        Algorithm:
            1. Versions / connectivity (already implemented as run_versions_only).
            2. Find issues where the user is assignee, reporter, or watcher.
            3. Extract unique project keys from those issues.
            4. For each of those projects, list the *boards filtered to that
               project* (usually a handful, not dozens).
            5. For each scrum board found, list 3 most recent sprints.
            6. If steps 2-5 yield nothing (e.g. brand-new account), fall back
               to dumping the full project list with a warning.

        Read-only. Writes a discover.md digest at the result-dir root.
        """
        print("[probe] discover mode\n")
        self.run_versions_only()

        print("\n[probe] discover — your recent work")
        # 2. Three lightweight JQL searches — assignee/reporter/watcher.
        # Each is independent so even if one fails (e.g. JQL 'watcher='
        # disabled by admin), the others still feed the project-key extraction.
        for label, jql in (
            ("assigned", "assignee=currentUser() ORDER BY updated DESC"),
            ("reported", "reporter=currentUser() ORDER BY updated DESC"),
            ("watching", "watcher=currentUser() ORDER BY updated DESC"),
        ):
            self._get(
                name=f"discover_my_{label}",
                description=f"Issues you are {label} (recent 10)",
                path="/rest/api/2/search?jql="
                     + urllib.parse.quote(jql)
                     + "&maxResults=10&fields=summary,status,project",
                category="existing",
                notes=[f"Discovery — finds projects via your {label} issues"],
            )

        # 3. Extract project keys from the union of the three searches.
        my_project_keys = self._collect_project_keys_from_my_issues()
        # 4 + 5: for each project, fetch project meta + boards + sprints
        if my_project_keys:
            print(f"\n[probe] discover — found {len(my_project_keys)} project(s) "
                  f"you work in: {', '.join(my_project_keys)}")
            for pkey in my_project_keys[:5]:  # cap so we don't fan out forever
                pk_url = urllib.parse.quote(pkey)
                self._get(
                    name=f"discover_project_{pkey}",
                    description=f"Project {pkey} details",
                    path=f"/rest/api/2/project/{pk_url}",
                    category="existing",
                    notes=[f"Discovery — project {pkey} metadata"],
                )
                self._get(
                    name=f"discover_boards_{pkey}",
                    description=f"Boards in project {pkey}",
                    path=f"/rest/agile/1.0/board?projectKeyOrId={pk_url}&maxResults=20",
                    category="existing",
                    notes=[f"Discovery — boards filtered to project {pkey}"],
                )
                # Pull sprints for up to 3 scrum boards per project
                boards = _read_raw_body(self.raw_dir / f"discover_boards_{pkey}.json")
                board_values = (
                    boards.get("values") if isinstance(boards, dict) else None
                ) or []
                scrum_boards = [
                    b for b in board_values
                    if isinstance(b, dict)
                    and b.get("id") is not None
                    and b.get("type") in ("scrum", "scrum-and-kanban")
                ]
                for entry in scrum_boards[:3]:
                    bid = entry["id"]
                    self._get(
                        name=f"discover_sprints_board_{bid}",
                        description=f"Recent sprints on board {bid} ({pkey})",
                        path=f"/rest/agile/1.0/board/{bid}/sprint?maxResults=5",
                        category="existing",
                        notes=[f"Discovery — sprints from board {bid} in {pkey}"],
                    )
        else:
            # 6. Fallback for users with zero assigned/reported/watched issues.
            print("\n[probe] discover — no recent work found; falling back to project list")
            self._get(
                name="discover_projects_fallback",
                description="All visible projects (fallback when you have no issues)",
                path="/rest/api/2/project?maxResults=20",
                category="candidate",
                notes=[
                    "FALLBACK — you have no assigned/reported/watched issues. "
                    "The list below shows projects you can read; pick one you "
                    "actually want to use, or grab a ticket key from the Jira UI."
                ],
            )

        # Always write the digest, even on fallback path
        self._write_discover_digest(my_project_keys)

    def _collect_project_keys_from_my_issues(self) -> list[str]:
        """Pull unique project keys from the three discover_my_* searches.

        Prefers the order assigned > reported > watching so the most-relevant
        project ends up first in the suggested command line."""
        seen: dict[str, None] = {}  # ordered set
        for label in ("assigned", "reported", "watching"):
            body = _read_raw_body(self.raw_dir / f"discover_my_{label}.json")
            if not isinstance(body, dict):
                continue
            for issue in (body.get("issues") or []):
                if not isinstance(issue, dict):
                    continue
                # Prefer fields.project.key if present (more reliable than
                # parsing the issue key — handles project keys with digits etc).
                fields = issue.get("fields") or {}
                proj = fields.get("project") if isinstance(fields, dict) else None
                if isinstance(proj, dict) and proj.get("key"):
                    seen.setdefault(str(proj["key"]), None)
                    continue
                # Fallback: parse from issue key
                key = issue.get("key")
                if isinstance(key, str) and "-" in key:
                    seen.setdefault(key.split("-", 1)[0], None)
        return list(seen.keys())

    def run_full(self, issue_key: Optional[str], board_id: Optional[int],
                 project_key: Optional[str], sprint_id: Optional[int]) -> None:
        # 1) Always run the version block first
        self.run_versions_only()

        # 2) Existing endpoints — Sprint tab + boards
        print("\n[probe] existing — boards/sprints")
        self._get("boards", "List boards (Sprint tab board picker)",
                  "/rest/agile/1.0/board?maxResults=200",
                  category="existing")
        self._get("boards_scrum", "List scrum boards (used by SprintService)",
                  "/rest/agile/1.0/board?type=scrum&maxResults=200",
                  category="existing")
        self._get("boards_kanban", "List kanban boards",
                  "/rest/agile/1.0/board?type=kanban&maxResults=200",
                  category="existing")
        if board_id is not None:
            self._get("active_sprints",
                      "Active sprints for board (Sprint tab)",
                      f"/rest/agile/1.0/board/{board_id}/sprint?state=active",
                      category="existing")
            self._get("closed_sprints",
                      "Closed sprints (past-sprints pagination)",
                      f"/rest/agile/1.0/board/{board_id}/sprint?state=closed&startAt=0&maxResults=10",
                      category="existing")
            self._get("board_issues",
                      "Kanban board issues (current user, unresolved)",
                      f"/rest/agile/1.0/board/{board_id}/issue"
                      f"?jql={urllib.parse.quote('resolution=Unresolved AND assignee=currentUser()')}"
                      f"&maxResults=20",
                      category="existing")
        if sprint_id is not None:
            self._get("sprint_issues",
                      "Issues in sprint (current user)",
                      f"/rest/agile/1.0/sprint/{sprint_id}/issue"
                      f"?jql={urllib.parse.quote('assignee=currentUser()')}&maxResults=20",
                      category="existing")

        # 3) Existing endpoints — search / users / pickers
        print("\n[probe] existing — search/pickers")
        self._get("search_jql_v2",
                  "Issue search (legacy /api/2/search) — used by validateTicketKeys + searchIssues",
                  "/rest/api/2/search?jql="
                  + urllib.parse.quote("assignee=currentUser() ORDER BY updated DESC")
                  + "&maxResults=5&fields=summary,status",
                  category="existing")
        self._get("user_search",
                  "User search (transition assignee picker)",
                  "/rest/api/2/user/search?query=a&maxResults=5",
                  category="existing")
        self._get("groups_picker",
                  "Group picker (transition group field)",
                  "/rest/api/2/groups/picker?query=a&maxResults=5",
                  category="existing")
        self._get("labels_suggest_internal",
                  "Label autocomplete (INTERNAL — code already has 404 fallback)",
                  "/rest/api/1.0/labels/suggest?query=a",
                  category="internal",
                  notes=["INTERNAL endpoint; degrades to free-form input on 404"])

        # 4) Existing endpoints — issue (needs issue_key)
        if issue_key:
            print("\n[probe] existing — issue endpoints")
            ek = urllib.parse.quote(issue_key)
            self._get("issue_basic",
                      "Get issue with issuelinks expand",
                      f"/rest/api/2/issue/{ek}?expand=issuelinks",
                      category="existing")
            self._get("issue_rich",
                      "Rich fetch (commit-message gen, PR pre-fetch, detail panel)",
                      f"/rest/api/2/issue/{ek}"
                      "?fields=summary,description,status,priority,issuetype,assignee,reporter,"
                      "labels,components,fixVersions,comment&expand=renderedFields",
                      category="existing")
            self._get("issue_transitions",
                      "Available transitions with field schema",
                      f"/rest/api/2/issue/{ek}/transitions?expand=transitions.fields",
                      category="existing",
                      notes=["Capture autoCompleteUrl values from response — those become live endpoints"])
            self._get("issue_comments",
                      "List comments",
                      f"/rest/api/2/issue/{ek}/comment?maxResults=10&orderBy=-created",
                      category="existing")
            self._get("issue_worklogs",
                      "List worklogs",
                      f"/rest/api/2/issue/{ek}/worklog?maxResults=10",
                      category="existing")
            self._get("issue_assignable",
                      "Assignable user search (per-issue picker)",
                      f"/rest/api/2/user/assignable/search?issueKey={ek}&query=a",
                      category="existing")

            # 5) Internal — dev-status (need numeric issue id; fetch it first)
            issue_basic = self.results[-6] if len(self.results) >= 6 else None
            issue_id = _extract_issue_id_from_raw(self.raw_dir / "issue_basic.json")
            if issue_id:
                for data_type in ("branch", "pullrequest", "repository", "build", "deployment", "review"):
                    self._get(
                        f"devstatus_{data_type}",
                        f"Dev-status {data_type} (INTERNAL — Jira's own Dev Panel)",
                        f"/rest/dev-status/1.0/issue/detail"
                        f"?issueId={issue_id}&applicationType=stash&dataType={data_type}",
                        category="internal",
                        notes=["INTERNAL Atlassian API; no public deprecation policy"],
                    )
            else:
                self.results.append(ProbeResult(
                    name="devstatus_skipped",
                    description="Dev-status probe skipped — could not resolve numeric issueId",
                    method="-", path="-", payload_kind="error", category="internal",
                    error="issue_basic call did not yield an `id` field",
                ))

        # 6) Existing endpoints — project (needs project_key)
        if project_key:
            print("\n[probe] existing — project pickers")
            pk = urllib.parse.quote(project_key)
            self._get("project_versions",
                      "Project versions (5min cached in plugin)",
                      f"/rest/api/2/project/{pk}/versions",
                      category="existing")
            self._get("project_components",
                      "Project components (5min cached in plugin)",
                      f"/rest/api/2/project/{pk}/components",
                      category="existing")

        # 7) Candidate endpoints (recommendations to validate)
        print("\n[probe] candidate endpoints (recommendations)")
        self._get("field_list",
                  "List all fields (auto-discover acceptance-criteria custom field)",
                  "/rest/api/2/field",
                  category="candidate",
                  notes=["Drop manual customfield_10001 setting; let user pick"])
        self._get("filter_search",
                  "Search saved filters (let user pick saved JQL in Sprint tab)",
                  "/rest/api/2/filter/search?maxResults=5",
                  category="candidate")
        self._get("filter_favourite",
                  "User's favourite filters",
                  "/rest/api/2/filter/favourite",
                  category="candidate")
        self._get("issuetype_list",
                  "All issue types (badge palettes)",
                  "/rest/api/2/issuetype",
                  category="candidate")
        self._get("priority_list",
                  "All priorities",
                  "/rest/api/2/priority",
                  category="candidate")
        self._get("status_list",
                  "All statuses (color-code by category)",
                  "/rest/api/2/status",
                  category="candidate")
        self._get("statuscategory_list",
                  "Status categories (used to group statuses by color)",
                  "/rest/api/2/statuscategory",
                  category="candidate")
        if issue_key:
            ek = urllib.parse.quote(issue_key)
            self._get("issue_changelog",
                      "Issue history (changelog expand) — feature: history feed in detail panel",
                      f"/rest/api/2/issue/{ek}?fields=summary&expand=changelog",
                      category="candidate")
            self._get("issue_remotelink",
                      "External links on issue (Confluence pages, etc.)",
                      f"/rest/api/2/issue/{ek}/remotelink",
                      category="candidate")
            self._get("issue_watchers",
                      "Watchers list (could power 'Watch this ticket' toggle)",
                      f"/rest/api/2/issue/{ek}/watchers",
                      category="candidate")

        # 8) Jira 10–era candidate endpoints (added after probing user's instance
        #    confirmed Jira 10.3.16). These are the modern Cloud-style search
        #    endpoints that Atlassian started backporting to DC in the 10.x line.
        #    POST is required by the API contract — these are search-only and do
        #    not mutate any Jira state.
        print("\n[probe] candidate endpoints (Jira 10+ search APIs)")
        self._post(
            name="search_jql_v3_post",
            description="Modern cursor-based search POST /rest/api/3/search/jql (Jira 10+)",
            path="/rest/api/3/search/jql",
            category="candidate",
            json_body={
                "jql": "order by updated DESC",
                "fields": ["summary", "status"],
                "maxResults": 1,
            },
            notes=[
                "Cloud-style cursor pagination (nextPageToken). 404 = not backported on this DC. "
                "200 = we can plan a v2-search → v3-search migration in the plugin."
            ],
        )
        self._post(
            name="search_v2_post",
            description="POST variant of /rest/api/2/search — handles long JQL where GET fails",
            path="/rest/api/2/search",
            category="candidate",
            json_body={
                "jql": "order by updated DESC",
                "fields": ["summary", "status"],
                "maxResults": 1,
            },
            notes=[
                "Plugin currently uses GET. POST avoids URL-length limits when JQL is long "
                "(e.g. validateTicketKeys with 100 keys joined by IN clause)."
            ],
        )
        self._post(
            name="search_approximate_count",
            description="Approximate JQL hit count (Jira 10 — fast, no result fetch)",
            path="/rest/api/2/search/approximate-count",
            category="candidate",
            json_body={"jql": "order by updated DESC"},
            notes=[
                "Jira 10–native. Useful for 'N tickets match' UI without paying for a full search."
            ],
        )
        self._get(
            name="issue_picker",
            description="History-grouped suggestions for @-mention input",
            path="/rest/api/2/issue/picker?query=test&showSubTasks=true&showSubTaskParent=true",
            category="candidate",
            notes=[
                "Returns sections for 'currentSearch' and 'history' — could replace the "
                "agent's MentionSearchProvider's manual board+sprint walking."
            ],
        )

    # -- devstatus headers ----------------------------------------------------

    def run_devstatus_headers(self, issue_id: str) -> None:
        """Capture raw HTTP headers from /rest/dev-status/1.0/issue/detail.

        Issues a single GET and writes status + headers to
        Result_Jira/devstatus-headers.txt so we can check whether Jira DC
        sets ETag / Last-Modified / Cache-Control on dev-status responses.
        If yes, CachingInterceptor can be upgraded to conditional GET
        (If-None-Match); if not, the existing synthetic-ETag SHA-256 path
        is the ceiling.
        """
        print("[probe] devstatus-headers mode\n")
        url = (
            f"{self.base}/rest/dev-status/1.0/issue/detail"
            f"?issueId={issue_id}&applicationType=stash&dataType=branch"
        )
        print(f"[probe] GET {url}")
        try:
            resp = self.session.get(url, timeout=30)
        except requests.RequestException as exc:
            print(f"[probe] ERROR: {exc}", file=sys.stderr)
            raise

        print(f"\nStatus: {resp.status_code}")
        for key, value in resp.headers.items():
            print(f"{key}: {value}")

        # Build file content
        lines: list[str] = [
            f"GET {url}",
            "",
            f"Status: {resp.status_code}",
        ]
        for key, value in resp.headers.items():
            lines.append(f"{key}: {value}")

        out_path = self.results_dir / "devstatus-headers.txt"
        out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"\n[probe] wrote headers → {out_path}")

    # -- audit follow-up ------------------------------------------------------

    def run_audit_followup(self, issue_key: Optional[str], project_key: Optional[str],
                           filter_id: Optional[int]) -> None:
        """Targeted probes for the 5 gaps in the 2026-05-06 Jira recommendations doc.

        Each probe verifies the *exact* request shape the plugin would send if we
        adopted the corresponding R-* recommendation. Read-only:
          A — POST /rest/api/2/search with `key in (…)` body  → R-SWAP-1
          B — GET  /rest/api/2/issue/picker?query={proj}-     → R-SWAP-2
          C — GET  /rest/api/2/mypermissions?projectKey={k}   → R-ADD-1
          D — GET  /rest/api/2/filter/{id} (one favourite)    → R-ADD-7
          E — GET  /rest/api/2/issue/{key}?expand=rendered,changelog (combined) → R-ADD-3
        """
        print("[probe] audit-followup mode\n")

        # Always (re)pin the version in case the user shares this bundle stand-alone.
        self._get(
            name="serverInfo",
            description="Server version + deployment type",
            path="/rest/api/2/serverInfo",
            category="version",
        )

        # ---- A — R-SWAP-1: POST /rest/api/2/search with realistic body ------
        if issue_key:
            self._post(
                name="search_v2_post_keys",
                description="POST /rest/api/2/search with `key in (…)` body — validateTicketKeys migration target",
                path="/rest/api/2/search",
                category="candidate",
                json_body={
                    "jql": f"key in ({issue_key})",
                    "fields": ["summary", "status"],
                    "maxResults": 1,
                },
                notes=[
                    "R-SWAP-1: confirms POST body shape works. 200 with 1 issue + no "
                    "'errorMessages' = green light to drop the chunked(100) loop."
                ],
            )
        else:
            print("[probe] A skipped — pass --issue-key to verify POST search body shape")

        # ---- B — R-SWAP-2: issue-picker with project-key prefix --------------
        # Mention search typically queries by partial key (`PROJ-`), not free text.
        prefix = None
        if issue_key and "-" in issue_key:
            prefix = issue_key.split("-", 1)[0] + "-"
        elif project_key:
            prefix = project_key + "-"
        if prefix:
            self._get(
                name="issue_picker_keyprefix",
                description=f"Issue-picker with key-prefix query ('{prefix}') — MentionSearchProvider migration target",
                path=f"/rest/api/2/issue/picker?query={urllib.parse.quote(prefix)}"
                     f"&showSubTasks=true&showSubTaskParent=true",
                category="candidate",
                notes=[
                    "R-SWAP-2: confirms issue-picker returns a useful 'currentSearch' "
                    "section for partial-key queries (the realistic mention-search input)."
                ],
            )
        else:
            print("[probe] B skipped — need --issue-key or --project-key to derive a prefix")

        # ---- C — R-ADD-1: project-scoped permissions ------------------------
        if project_key:
            self._get(
                name="mypermissions_project",
                description="Project-scoped permissions — UI gating per-project",
                path=f"/rest/api/2/mypermissions?projectKey={urllib.parse.quote(project_key)}",
                category="candidate",
                notes=[
                    "R-ADD-1: confirms projectKey-scoped variant works. Same shape as "
                    "global call but the `havePermission` flags reflect this project's ACLs."
                ],
            )
        else:
            print("[probe] C skipped — pass --project-key to verify project-scoped permissions")

        # ---- D — R-ADD-7: filter detail (need filter id) --------------------
        # If the user didn't pass --filter-id, fetch their favourites and pick the first.
        chosen_filter_id: Optional[int] = filter_id
        if chosen_filter_id is None:
            fav = self._get(
                name="filter_favourite",
                description="User's favourite filters (used to pick a filter id for the detail probe)",
                path="/rest/api/2/filter/favourite",
                category="candidate",
            )
            if fav.ok:
                fav_body = _read_raw_body(self.raw_dir / "filter_favourite.json")
                if isinstance(fav_body, list):
                    for f in fav_body:
                        if isinstance(f, dict) and f.get("id"):
                            try:
                                chosen_filter_id = int(f["id"])
                                break
                            except (TypeError, ValueError):
                                continue
        if chosen_filter_id is not None:
            self._get(
                name="filter_detail",
                description=f"Filter {chosen_filter_id} detail (incl. JQL) — Sprint-tab favourites click target",
                path=f"/rest/api/2/filter/{chosen_filter_id}",
                category="candidate",
                notes=[
                    "R-ADD-7: confirms we can fetch a saved filter's full JQL after the "
                    "user clicks one in the favourites list."
                ],
            )
        else:
            print("[probe] D skipped — no filter id available "
                  "(pass --filter-id, or favourite at least one filter in Jira)")

        # ---- E — R-ADD-3 polish: combined rendered+changelog expand ---------
        if issue_key:
            self._get(
                name="issue_rich_with_changelog",
                description="Rich fetch + changelog in one call — single-call detail-panel feed",
                path=f"/rest/api/2/issue/{urllib.parse.quote(issue_key)}"
                     "?fields=summary,description,status,priority,issuetype,assignee,reporter,"
                     "labels,components,fixVersions,comment&expand=renderedFields,changelog",
                category="candidate",
                notes=[
                    "R-ADD-3 polish: confirms combining renderedFields + changelog in one "
                    "request. Saves a round-trip when the detail panel opens."
                ],
            )
        else:
            print("[probe] E skipped — pass --issue-key to verify combined-expand fetch")

    # -- summary --------------------------------------------------------------

    def write_summary(self, args_used: dict[str, Any]) -> None:
        summary_path = self.results_dir / "summary.md"
        lines: list[str] = []
        lines.append(f"# Jira probe results — {self.base}")
        lines.append("")
        lines.append(f"- **Run at:** {time.strftime('%Y-%m-%dT%H:%M:%S%z')}")
        lines.append(f"- **Args:** `{json.dumps(args_used)}`")
        lines.append(f"- **Total endpoints probed:** {len(self.results)}")
        lines.append(f"- **Successful (2xx):** {sum(1 for r in self.results if r.ok)}")
        lines.append(f"- **Failed (4xx/5xx/error):** {sum(1 for r in self.results if not r.ok)}")
        lines.append("")
        version_note = self._format_version_note()
        if version_note:
            lines.append("## Version detection")
            lines.append("")
            lines.append(version_note)
            lines.append("")

        for category in ("version", "existing", "internal", "candidate"):
            cat_results = [r for r in self.results if r.category == category]
            if not cat_results:
                continue
            lines.append(f"## {category.title()} endpoints")
            lines.append("")
            lines.append("| Status | Endpoint | Description | Time | Notes |")
            lines.append("|---|---|---|---|---|")
            for r in cat_results:
                status_label = (
                    f"✅ {r.status}" if r.ok
                    else f"❌ {r.status or 'ERR'}"
                )
                notes_str = "; ".join(r.notes) if r.notes else ""
                if r.error:
                    notes_str = (notes_str + " · " + r.error).strip(" ·")
                desc = r.description.replace("|", "\\|")
                notes_str = notes_str.replace("|", "\\|")
                lines.append(
                    f"| {status_label} | `{r.method} {r.path}` | {desc} | {r.elapsed_ms}ms | {notes_str} |"
                )
            lines.append("")

        lines.append("## Raw responses")
        lines.append("")
        lines.append("Each endpoint's full response (parsed JSON or text snippet) is saved to `raw/<name>.json`.")
        lines.append("These can be diffed against future probe runs to detect schema drift.")
        lines.append("")

        summary_path.write_text("\n".join(lines), encoding="utf-8")
        print(f"\n[probe] wrote summary → {summary_path}")
        print(f"[probe] wrote {len(self.results)} raw payloads → {self.raw_dir}")

    def _write_discover_digest(self, my_project_keys: list[str]) -> None:
        """Render a human-friendly discover.md scoped to the user's actual work."""
        lines: list[str] = ["# Discovery — pick values for the full sweep", ""]

        # Per-search "your issues" tables
        issue_section_total = 0
        # Also collect the first useful (key, projectKey) pair to seed suggestions
        first_issue_key: Optional[str] = None
        first_issue_proj: Optional[str] = None
        for label in ("assigned", "reported", "watching"):
            body = _read_raw_body(self.raw_dir / f"discover_my_{label}.json")
            rows: list[tuple[str, str, str, str]] = []
            if isinstance(body, dict):
                for i in (body.get("issues") or []):
                    if not isinstance(i, dict):
                        continue
                    k = i.get("key")
                    if not k:
                        continue
                    fields = i.get("fields") or {}
                    summary = str(fields.get("summary", "") if isinstance(fields, dict) else "")
                    status = ""
                    proj_key = ""
                    if isinstance(fields, dict):
                        if isinstance(fields.get("status"), dict):
                            status = str(fields["status"].get("name", ""))
                        if isinstance(fields.get("project"), dict):
                            proj_key = str(fields["project"].get("key", ""))
                    rows.append((str(k), summary, status, proj_key))
                    if first_issue_key is None:
                        first_issue_key = str(k)
                        first_issue_proj = proj_key or (str(k).split("-", 1)[0] if "-" in str(k) else None)
            if rows:
                issue_section_total += len(rows)
                lines.append(f"## Issues you are {label} (top {len(rows)})")
                lines.append("")
                lines.append("| Key | Summary | Status | Project |")
                lines.append("|---|---|---|---|")
                for k, s, st, pk in rows:
                    lines.append(
                        f"| `{k}` | {s.replace('|', '\\|')[:80]} | {st} | `{pk}` |"
                    )
                lines.append("")

        if issue_section_total == 0:
            lines.append("## Your recent work")
            lines.append("")
            lines.append(
                "_No issues found where you are assignee, reporter, or watcher. "
                "This usually means a brand-new account or a Jira admin who has "
                "disabled `watcher` JQL. See the fallback project list below._"
            )
            lines.append("")

        # Per-project sections — much smaller than dumping all visible projects
        if my_project_keys:
            lines.append("## Projects you work in (filtered)")
            lines.append("")
            for pkey in my_project_keys[:5]:
                proj_body = _read_raw_body(self.raw_dir / f"discover_project_{pkey}.json")
                proj_name = ""
                proj_lead = ""
                if isinstance(proj_body, dict):
                    proj_name = str(proj_body.get("name", ""))
                    lead = proj_body.get("lead")
                    if isinstance(lead, dict):
                        proj_lead = str(lead.get("displayName", ""))

                lines.append(f"### `{pkey}` — {proj_name}" + (f" (lead: {proj_lead})" if proj_lead else ""))
                lines.append("")

                # Boards in this project
                boards_body = _read_raw_body(self.raw_dir / f"discover_boards_{pkey}.json")
                board_rows = []
                if isinstance(boards_body, dict):
                    for b in (boards_body.get("values") or []):
                        if isinstance(b, dict) and b.get("id") is not None:
                            board_rows.append((b["id"], str(b.get("name", "")), str(b.get("type", ""))))
                if board_rows:
                    lines.append("**Boards:**")
                    lines.append("")
                    lines.append("| Board ID | Name | Type |")
                    lines.append("|---|---|---|")
                    for bid, name, btype in board_rows:
                        lines.append(f"| `{bid}` | {name} | {btype} |")
                    lines.append("")

                    # Sprints per scrum board (only those we actually fetched)
                    for bid, name, btype in board_rows:
                        if btype not in ("scrum", "scrum-and-kanban"):
                            continue
                        sprints_body = _read_raw_body(self.raw_dir / f"discover_sprints_board_{bid}.json")
                        sprint_rows = []
                        if isinstance(sprints_body, dict):
                            for s in (sprints_body.get("values") or []):
                                if isinstance(s, dict) and s.get("id") is not None:
                                    sprint_rows.append(
                                        (s["id"], str(s.get("name", "")), str(s.get("state", "")))
                                    )
                        if sprint_rows:
                            lines.append(f"**Sprints on board `{bid}` ({name}):**")
                            lines.append("")
                            lines.append("| Sprint ID | Name | State |")
                            lines.append("|---|---|---|")
                            for sid, sname, sstate in sprint_rows:
                                lines.append(f"| `{sid}` | {sname} | {sstate} |")
                            lines.append("")
                else:
                    lines.append("_No boards found in this project (you may not have agile-board permissions on it)._")
                    lines.append("")
        else:
            # Fallback — full project list
            fallback = _read_raw_body(self.raw_dir / "discover_projects_fallback.json")
            lines.append("## Projects you can read (fallback list)")
            lines.append("")
            lines.append(
                "_You have no recent assigned/reported/watched issues, so this is "
                "everything your token can browse. Pick the one you actually use._"
            )
            lines.append("")
            if isinstance(fallback, list) and fallback:
                lines.append("| Key | Name |")
                lines.append("|---|---|")
                for p in fallback[:20]:
                    if isinstance(p, dict) and p.get("key"):
                        lines.append(f"| `{p['key']}` | {p.get('name', '')} |")
                lines.append("")

        # Suggested command-line — seeded with the first project/issue/board/sprint we found
        suggested_proj = first_issue_proj or (my_project_keys[0] if my_project_keys else "PROJECT_KEY")
        suggested_issue = first_issue_key or (
            f"{suggested_proj}-1" if suggested_proj != "PROJECT_KEY" else "ISSUE-KEY"
        )
        # First scrum board in the suggested project
        suggested_board: Any = "BOARD_ID"
        suggested_sprint: Any = "SPRINT_ID"
        if suggested_proj != "PROJECT_KEY":
            boards_body = _read_raw_body(self.raw_dir / f"discover_boards_{suggested_proj}.json")
            if isinstance(boards_body, dict):
                for b in (boards_body.get("values") or []):
                    if isinstance(b, dict) and b.get("type") in ("scrum", "scrum-and-kanban"):
                        suggested_board = b["id"]
                        sprints_body = _read_raw_body(
                            self.raw_dir / f"discover_sprints_board_{suggested_board}.json"
                        )
                        if isinstance(sprints_body, dict):
                            sprints = sprints_body.get("values") or []
                            if sprints and isinstance(sprints[0], dict):
                                suggested_sprint = sprints[0].get("id", "SPRINT_ID")
                        break

        lines.append("---")
        lines.append("")
        lines.append("## Suggested command for the full sweep")
        lines.append("")
        lines.append(
            "_Seeded with the most-recent values from your work above. Replace "
            "any of them with whatever you'd rather use._"
        )
        lines.append("")
        lines.append("Windows `cmd`:")
        lines.append("")
        lines.append("```bat")
        lines.append(
            f"python probe_jira.py --url <YOUR_JIRA_URL> --token <YOUR_PAT> ^\n"
            f"    --issue-key {suggested_issue} ^\n"
            f"    --board-id {suggested_board} ^\n"
            f"    --sprint-id {suggested_sprint} ^\n"
            f"    --project-key {suggested_proj}"
        )
        lines.append("```")
        lines.append("")
        lines.append("PowerShell / Unix shells:")
        lines.append("")
        lines.append("```bash")
        lines.append(
            f"python probe_jira.py --url <YOUR_JIRA_URL> --token <YOUR_PAT> \\\n"
            f"    --issue-key {suggested_issue} \\\n"
            f"    --board-id {suggested_board} \\\n"
            f"    --sprint-id {suggested_sprint} \\\n"
            f"    --project-key {suggested_proj}"
        )
        lines.append("```")
        lines.append("")

        digest_path = self.results_dir / "discover.md"
        digest_path.write_text("\n".join(lines), encoding="utf-8")
        print(f"\n[probe] wrote discovery digest → {digest_path}")
        print("[probe] open it locally, pick values, then re-run with the suggested args.")
        print("[probe] (this file contains real project/board names — redact before sharing.)")

    def _format_version_note(self) -> str:
        info = next((r for r in self.results if r.name == "serverInfo"), None)
        if not info or not info.ok:
            return "_serverInfo did not respond — version unknown._"
        raw_file = self.raw_dir / "serverInfo.json"
        try:
            data = json.loads(raw_file.read_text(encoding="utf-8")).get("raw_body") or {}
        except Exception:
            return "_serverInfo response could not be parsed._"
        return (
            f"- **version:** `{data.get('version')}`\n"
            f"- **versionNumbers:** `{data.get('versionNumbers')}`\n"
            f"- **buildNumber:** `{data.get('buildNumber')}`\n"
            f"- **deploymentType:** `{data.get('deploymentType')}`  ← must say `Server` (not `Cloud`)\n"
            f"- **buildDate:** `{data.get('buildDate')}`\n"
            f"- **scmInfo:** `{data.get('scmInfo')}`\n"
        )


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _summarize_json(payload: Any, depth: int = 0, max_depth: int = 2,
                    max_items: int = 5) -> Any:
    """Produce a small preview suitable for Markdown without dumping huge payloads."""
    if depth >= max_depth:
        if isinstance(payload, list):
            return f"<list len={len(payload)}>"
        if isinstance(payload, dict):
            return f"<dict keys={list(payload.keys())[:max_items]}>"
        return _truncate_scalar(payload)
    if isinstance(payload, list):
        return [_summarize_json(p, depth + 1, max_depth, max_items)
                for p in payload[:max_items]] + (["…"] if len(payload) > max_items else [])
    if isinstance(payload, dict):
        out = {}
        for i, (k, v) in enumerate(payload.items()):
            if i >= max_items:
                out["…"] = f"+{len(payload) - max_items} more keys"
                break
            out[k] = _summarize_json(v, depth + 1, max_depth, max_items)
        return out
    return _truncate_scalar(payload)


def _truncate_scalar(v: Any) -> Any:
    if isinstance(v, str) and len(v) > 120:
        return v[:120] + f"…(+{len(v) - 120} chars)"
    return v


def _extract_issue_id_from_raw(raw_path: Path) -> Optional[str]:
    try:
        data = json.loads(raw_path.read_text(encoding="utf-8"))
        body = data.get("raw_body") or {}
        return str(body["id"]) if isinstance(body, dict) and "id" in body else None
    except Exception:
        return None


def _read_raw_body(raw_path: Path) -> Any:
    """Return the parsed `raw_body` from a saved probe response file, or None."""
    try:
        data = json.loads(raw_path.read_text(encoding="utf-8"))
        return data.get("raw_body")
    except Exception:
        return None


def _allocate_results_dir(parent: Path) -> Path:
    n = 1
    while True:
        candidate = parent / f"Result_{n}"
        if not candidate.exists():
            candidate.mkdir(parents=True)
            return candidate
        n += 1


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> int:
    p = argparse.ArgumentParser(
        description="Read-only Jira Server / DC probe for Workflow Orchestrator plugin",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    p.add_argument("--url", required=True, help="Jira base URL, e.g. https://jira.example.com")
    p.add_argument("--token", required=True, help="Personal Access Token (Bearer)")
    p.add_argument("--issue-key", help="A real issue key, e.g. PROJ-123 (enables issue-scoped probes)")
    p.add_argument("--board-id", type=int, help="Board id (enables sprint/board-issue probes)")
    p.add_argument("--sprint-id", type=int, help="Sprint id (enables sprint-issue probe)")
    p.add_argument("--project-key", help="Project key (enables versions/components probes)")
    p.add_argument("--no-verify", action="store_true", help="Disable TLS verification (self-signed certs)")
    p.add_argument("--versions-only", action="store_true",
                   help="Only call /serverInfo + /myself + /mypermissions and exit")
    p.add_argument("--discover", action="store_true",
                   help="Discovery mode: list visible projects, boards, sprints, "
                        "and recent issues so you can pick values for the full "
                        "sweep without digging through Jira URLs. Writes "
                        "Result_N/discover.md with a copy-paste command.")
    p.add_argument("--audit-followup", action="store_true",
                   help="Targeted follow-up probes for the 2026-05-06 Jira "
                        "recommendations doc (R-SWAP-1, R-SWAP-2, R-ADD-1, "
                        "R-ADD-7, R-ADD-3 polish). Read-only. Needs "
                        "--issue-key + --project-key; --filter-id optional.")
    p.add_argument("--devstatus-headers", action="store_true",
                   help="Single GET to /rest/dev-status/1.0/issue/detail and "
                        "write the response status + headers to "
                        "Result_Jira/devstatus-headers.txt. Used to determine "
                        "whether Jira DC sets ETag / Last-Modified so the "
                        "plugin can upgrade to conditional GET. Requires "
                        "--issue-id (numeric Jira issue id, not the key).")
    p.add_argument("--issue-id", type=str,
                   help="Numeric Jira issue id for --devstatus-headers "
                        "(e.g. 10042). NOT the issue key (PROJ-123). "
                        "If omitted with --devstatus-headers, prompted interactively.")
    p.add_argument("--filter-id", type=int,
                   help="Specific Jira filter id to fetch (audit-followup probe D). "
                        "If omitted, the first favourite filter is used.")
    p.add_argument("--out", default=str(Path(__file__).parent),
                   help="Parent dir for Result_N/ output (default: alongside the script)")
    args = p.parse_args()

    if not args.token:
        print("ERROR: --token must be non-empty", file=sys.stderr)
        return 2

    if args.no_verify:
        # Suppress urllib3 SSL warnings since user explicitly opted out.
        # `requests.packages.urllib3` was deprecated and may not exist on newer
        # urllib3 — use the urllib3 module directly.
        try:
            import urllib3
            urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
        except Exception:
            pass

    out_parent = Path(args.out)
    out_parent.mkdir(parents=True, exist_ok=True)
    if args.devstatus_headers:
        results_dir = out_parent / "Result_Jira"
        results_dir.mkdir(parents=True, exist_ok=True)
    else:
        results_dir = _allocate_results_dir(out_parent)

    exclusive = sum(bool(x) for x in (
        args.discover, args.versions_only, args.audit_followup, args.devstatus_headers,
    ))
    if exclusive > 1:
        print("ERROR: --discover, --versions-only, --audit-followup, and "
              "--devstatus-headers are mutually exclusive.",
              file=sys.stderr)
        return 2

    if args.discover:
        mode_label = "discover"
    elif args.versions_only:
        mode_label = "versions-only"
    elif args.audit_followup:
        mode_label = "audit-followup"
    elif args.devstatus_headers:
        mode_label = "devstatus-headers"
    else:
        mode_label = "full sweep"

    print(f"[probe] target: {args.url}")
    print(f"[probe] output: {results_dir}")
    print(f"[probe] mode:   {mode_label}")
    print()

    probe = JiraProbe(args.url, args.token, verify=not args.no_verify, results_dir=results_dir)

    args_used = {
        "url": args.url,
        "issue_key": args.issue_key,
        "issue_id": args.issue_id,
        "board_id": args.board_id,
        "sprint_id": args.sprint_id,
        "project_key": args.project_key,
        "filter_id": args.filter_id,
        "no_verify": args.no_verify,
        "versions_only": args.versions_only,
        "discover": args.discover,
        "audit_followup": args.audit_followup,
        "devstatus_headers": args.devstatus_headers,
    }

    if args.devstatus_headers:
        issue_id = args.issue_id
        if not issue_id:
            issue_id = input("Numeric Jira issue id (e.g. 10042): ").strip()
        if not issue_id:
            print("ERROR: --issue-id is required for --devstatus-headers", file=sys.stderr)
            return 2
        probe.run_devstatus_headers(issue_id)
        return 0

    if args.discover:
        probe.run_discover()
    elif args.versions_only:
        probe.run_versions_only()
    elif args.audit_followup:
        probe.run_audit_followup(
            issue_key=args.issue_key,
            project_key=args.project_key,
            filter_id=args.filter_id,
        )
    else:
        probe.run_full(
            issue_key=args.issue_key,
            board_id=args.board_id,
            project_key=args.project_key,
            sprint_id=args.sprint_id,
        )

    probe.write_summary(args_used)
    if args.discover:
        print(f"[probe] done — open {results_dir / 'discover.md'} to pick values for the full sweep.")
    else:
        print(f"[probe] done — open {results_dir / 'summary.md'} and paste back to me.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
