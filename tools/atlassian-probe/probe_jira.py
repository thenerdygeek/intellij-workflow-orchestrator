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

    # -- low-level GET helper --------------------------------------------------

    def _get(self, name: str, description: str, path: str, category: str,
             notes: Optional[list[str]] = None, expect_json: bool = True) -> ProbeResult:
        url = f"{self.base}{path}"
        result = ProbeResult(
            name=name, description=description, method="GET", path=path,
            category=category, notes=list(notes or []),
        )
        start = time.perf_counter()
        raw_payload: Any = None
        try:
            resp = self.session.get(url, timeout=30, allow_redirects=False)
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
            "raw_body": raw_payload if raw_payload is not None else None,
        }, indent=2, default=str), encoding="utf-8")
        self.results.append(result)
        return result

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

        # 8) Forward-compat: Cloud-style v3 search (does this DC have it backported?)
        self._get("search_jql_v3_post",
                  "Forward-compat: POST /rest/api/3/search/jql (Cloud-style cursor search)",
                  "/rest/api/3/search/jql?maxResults=1",  # GET probe is enough — 404/405 tells us if backported
                  category="candidate",
                  notes=["Cloud deprecated GET /api/2/search May-2024; checking if DC has the new endpoint yet"])

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
    p.add_argument("--out", default=str(Path(__file__).parent),
                   help="Parent dir for Result_N/ output (default: alongside the script)")
    args = p.parse_args()

    if not args.token:
        print("ERROR: --token must be non-empty", file=sys.stderr)
        return 2

    if args.no_verify:
        # Suppress urllib3 SSL warnings since user explicitly opted out
        try:
            from urllib3.exceptions import InsecureRequestWarning
            requests.packages.urllib3.disable_warnings(InsecureRequestWarning)  # type: ignore[attr-defined]
        except Exception:
            pass

    out_parent = Path(args.out)
    out_parent.mkdir(parents=True, exist_ok=True)
    results_dir = _allocate_results_dir(out_parent)

    print(f"[probe] target: {args.url}")
    print(f"[probe] output: {results_dir}")
    print(f"[probe] mode:   {'versions-only' if args.versions_only else 'full sweep'}")
    print()

    probe = JiraProbe(args.url, args.token, verify=not args.no_verify, results_dir=results_dir)

    args_used = {
        "url": args.url,
        "issue_key": args.issue_key,
        "board_id": args.board_id,
        "sprint_id": args.sprint_id,
        "project_key": args.project_key,
        "no_verify": args.no_verify,
        "versions_only": args.versions_only,
    }

    if args.versions_only:
        probe.run_versions_only()
    else:
        probe.run_full(
            issue_key=args.issue_key,
            board_id=args.board_id,
            project_key=args.project_key,
            sprint_id=args.sprint_id,
        )

    probe.write_summary(args_used)
    print(f"[probe] done — open {results_dir / 'summary.md'} and paste back to me.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
