#!/usr/bin/env python3
"""
Bitbucket Server / Data Center API probe for the Workflow Orchestrator IntelliJ plugin.

Read-only. Verifies every endpoint the plugin currently calls + tests a wide set
of candidate endpoints we may want to adopt. Writes per-endpoint JSON + a
Markdown summary to tools/atlassian-probe/Result_N/.

Usage examples:
    # Just detect version + connectivity (~3 calls, no params needed)
    python probe_bitbucket.py --url https://bb.company.com --token PAT --versions-only

    # Discovery — find a real project/repo/PR/commit/branch from your access
    python probe_bitbucket.py --url https://bb.company.com --token PAT --discover

    # Full sweep — needs realistic ids so candidates exercise non-empty payloads
    python probe_bitbucket.py --url https://bb.company.com --token PAT \\
        --project-key PROJ --repo-slug my-repo --pr-id 42 \\
        --commit-id abc123def --branch-name feature/foo

    # Self-signed cert
    python probe_bitbucket.py ... --no-verify

The script never executes mutations (branch/PR creation, merge, decline, approve,
comment writes, participant changes, etc.) — only HTTP GETs against the listed
endpoints, plus two read-only POSTs (search, markup preview) that don't mutate
state. The User-Agent string and `(read-only)` marker make this auditable in
Bitbucket's access logs.
"""

from __future__ import annotations

import argparse
import json
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
    category: str = ""        # "version" | "existing" | "swap" | "feature" | "internal"


# ---------------------------------------------------------------------------
# Probe runner
# ---------------------------------------------------------------------------

class BitbucketProbe:
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

        POST is allowed only for endpoints that are functionally search / lookup /
        rendering and never mutate Bitbucket state:
          - POST /rest/search/1.0/search       (code/PR search)
          - POST /rest/api/1.0/markup/preview  (render Markdown)
        Both are documented as read-only. The User-Agent string includes
        `(read-only)` so admins can audit probe traffic in Bitbucket's logs.
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
            name="application_properties",
            description="Server version + display name (mirrors Jira's /serverInfo)",
            path="/rest/api/1.0/application-properties",
            category="version",
            notes=["Use this to fill in the Bitbucket version question once you run the probe"],
        )
        self._get(
            name="admin_license",
            description="License info — confirms Server vs Data Center (must be DC for our use)",
            path="/rest/api/1.0/admin/license",
            category="version",
            notes=["Returns 401/403 for non-admin tokens; that's fine — we just need the existence check"],
        )
        self._get(
            name="users_self_check",
            description="Connection check + identity (lists 1 user just to confirm auth)",
            path="/rest/api/1.0/users?limit=1",
            category="version",
        )
        self._get(
            name="capabilities",
            description="Installed plugins / capabilities — gates which candidates are usable",
            path="/rest/capabilities",
            category="version",
            notes=[
                "Tells us which optional plugins are installed: Code Insights, "
                "Jira link, Sonar, default-reviewers, etc. Used to decide which "
                "swap/feature recommendations are even applicable on this instance."
            ],
        )
        # Capability discovery — DC 7.4+ added first-party build & deployment
        # capability endpoints under /rest/api/latest/. These advertise the
        # actual schema each provider supports (e.g. whether testResults are
        # accepted on this instance) and give us the canonical versioned base
        # path for follow-up probes (especially Code Insights, which can shift
        # base-path between point releases).
        self._get(
            name="build_capabilities",
            description="Build-status fields the instance advertises (DC 7.4+, capability='build')",
            path="/rest/api/latest/build/capabilities",
            category="version",
            notes=["Discovery for the rich /commits/{cid}/builds API; field set varies by version"],
        )
        self._get(
            name="deployment_capabilities",
            description="Deployment-status capabilities (DC 7.16+, capability='deployment')",
            path="/rest/api/latest/deployment/capabilities",
            category="version",
            notes=["Gates whether the deployments-by-commit probe is meaningful"],
        )
        self._get(
            name="insights_capabilities",
            description="Code Insights capabilities — gives the actual versioned base path",
            path="/rest/insights/latest/capabilities",
            category="version",
            notes=[
                "Solves the base-path-shifting-between-point-releases problem. "
                "Subsequent insights probes use the path advertised here, not "
                "the hard-coded /rest/insights/1.0/."
            ],
        )

    def _resolve_insights_base(self) -> str:
        """Return the Code Insights API base path advertised by the instance.

        Falls back to /rest/insights/1.0 if the capabilities call did not
        surface a `url` field — that's the documented stable path through
        DC 9.x, but we ask the server first because Atlassian has shifted
        base paths between point releases on other plugin-provided APIs.
        """
        body = _read_raw_body(self.raw_dir / "insights_capabilities.json")
        if isinstance(body, dict) and isinstance(body.get("url"), str):
            return body["url"].rstrip("/")
        return "/rest/insights/1.0"

    def run_discover(self) -> None:
        """Discovery mode — derives values to feed into the full sweep from the
        user's own access (PRs they author/review, projects they can browse).

        Algorithm:
            1. Versions / connectivity (already implemented as run_versions_only).
            2. Cross-repo dashboard PRs as AUTHOR + REVIEWER (open) to find
               project/repo/PR ids the user actually works with.
            3. Fall back to /projects + first project's /repos list if no PRs.
            4. For the chosen repo: pull default branch, latest commit SHA, top
               recent branch — those are the args the full sweep wants.
            5. If we have a PR id, fetch its activities to surface a real
               comment id (so the existing-comment-by-id endpoint exercises
               a 200 instead of a 404).
            6. Write Result_N/discover.md with a copy-paste full-sweep command.

        Read-only.
        """
        print("[probe] discover mode\n")
        self.run_versions_only()

        print("\n[probe] discover — your active PRs (cross-repo)")
        # 2. Two dashboard searches — author + reviewer.
        for label, role in (("author", "AUTHOR"), ("reviewer", "REVIEWER")):
            self._get(
                name=f"discover_dashboard_{label}",
                description=f"Open PRs where you are {role} (cross-repo dashboard)",
                path=f"/rest/api/1.0/dashboard/pull-requests?role={role}&state=OPEN&limit=10",
                category="swap",
                notes=[
                    f"Discovery — also a SWAP candidate. Plugin currently iterates "
                    f"per-repo via /projects/.../repos/.../pull-requests?role.1={role}; "
                    f"this dashboard variant returns matching PRs across all repos in one call."
                ],
            )

        # 3. Extract the most recent project / repo / PR from the dashboard payloads.
        chosen = self._first_pr_from_dashboards()

        # 4. Fallback if user has no open PRs anywhere
        if not chosen:
            print("\n[probe] discover — no active PRs found, falling back to project + repo list")
            projects_call = self._get(
                name="discover_projects_fallback",
                description="All visible projects (fallback when you have no open PRs)",
                path="/rest/api/1.0/projects?limit=10",
                category="existing",
                notes=["FALLBACK — pick a project key you actually use"],
            )
            project_key = self._first_project_key(projects_call)
            if project_key:
                pk = urllib.parse.quote(project_key)
                repos_call = self._get(
                    name=f"discover_repos_{project_key}",
                    description=f"Repos in project {project_key}",
                    path=f"/rest/api/1.0/projects/{pk}/repos?limit=10",
                    category="existing",
                    notes=[f"Discovery — repos in {project_key}"],
                )
                repo_slug = self._first_repo_slug(repos_call)
                if repo_slug:
                    chosen = (project_key, repo_slug, None)

        # 5. For the chosen repo, fetch default branch + latest commit + a branch
        if chosen:
            project_key, repo_slug, pr_id = chosen
            pk = urllib.parse.quote(project_key)
            rs = urllib.parse.quote(repo_slug)
            print(f"\n[probe] discover — chosen repo: {project_key}/{repo_slug}"
                  + (f" (PR #{pr_id})" if pr_id else ""))
            self._get(
                name=f"discover_default_branch_{repo_slug}",
                description=f"Default branch of {project_key}/{repo_slug}",
                path=f"/rest/api/1.0/projects/{pk}/repos/{rs}/default-branch",
                category="existing",
                notes=["Discovery — gives us a realistic --branch-name + a ref for browse"],
            )
            self._get(
                name=f"discover_recent_commits_{repo_slug}",
                description=f"Latest commit on {project_key}/{repo_slug} default branch",
                path=f"/rest/api/1.0/projects/{pk}/repos/{rs}/commits?limit=1",
                category="feature",
                notes=["Discovery — gives us a real --commit-id for build-status / commit candidates"],
            )
            self._get(
                name=f"discover_recent_branches_{repo_slug}",
                description=f"Most-recently-modified branch on {project_key}/{repo_slug}",
                path=f"/rest/api/1.0/projects/{pk}/repos/{rs}/branches?limit=1&orderBy=MODIFICATION",
                category="existing",
                notes=["Discovery — gives us a realistic at=<branchRef> for the OUTGOING-PR query"],
            )
            # 5b. If we have a PR, fetch one comment id so the comment-by-id endpoint
            # in the full sweep returns a real payload instead of a 404.
            if pr_id is not None:
                self._get(
                    name=f"discover_pr_activities_{pr_id}",
                    description=f"Activities on PR #{pr_id} (used to surface a comment id)",
                    path=f"/rest/api/1.0/projects/{pk}/repos/{rs}/pull-requests/{pr_id}/activities?limit=20",
                    category="existing",
                    notes=["Discovery — extracts a comment id from the activity stream"],
                )

        # 6. Always write the digest, even on fallback path
        self._write_discover_digest(chosen)

    def _first_pr_from_dashboards(self) -> Optional[tuple[str, str, int]]:
        """Pull (projectKey, repoSlug, prId) from the first dashboard PR we found.

        Prefers AUTHOR over REVIEWER so the chosen PR is one the user wrote
        (more likely to have rich activity / comments to probe)."""
        for label in ("author", "reviewer"):
            body = _read_raw_body(self.raw_dir / f"discover_dashboard_{label}.json")
            if not isinstance(body, dict):
                continue
            for pr in (body.get("values") or []):
                if not isinstance(pr, dict):
                    continue
                pr_id = pr.get("id")
                to_ref = pr.get("toRef") or {}
                repo = to_ref.get("repository") if isinstance(to_ref, dict) else None
                if not isinstance(repo, dict):
                    continue
                slug = repo.get("slug")
                proj = repo.get("project") if isinstance(repo, dict) else None
                proj_key = proj.get("key") if isinstance(proj, dict) else None
                if pr_id and slug and proj_key:
                    try:
                        return (str(proj_key), str(slug), int(pr_id))
                    except (TypeError, ValueError):
                        continue
        return None

    def _first_project_key(self, _result: ProbeResult) -> Optional[str]:
        body = _read_raw_body(self.raw_dir / "discover_projects_fallback.json")
        if isinstance(body, dict):
            for p in (body.get("values") or []):
                if isinstance(p, dict) and p.get("key"):
                    return str(p["key"])
        return None

    def _first_repo_slug(self, result: ProbeResult) -> Optional[str]:
        # raw filename uses the project key suffix
        # find the most recent discover_repos_*.json
        candidates = sorted(self.raw_dir.glob("discover_repos_*.json"))
        if not candidates:
            return None
        body = _read_raw_body(candidates[-1])
        if isinstance(body, dict):
            for r in (body.get("values") or []):
                if isinstance(r, dict) and r.get("slug"):
                    return str(r["slug"])
        return None

    def run_full(self, project_key: Optional[str], repo_slug: Optional[str],
                 pr_id: Optional[int], commit_id: Optional[str],
                 branch_name: Optional[str], comment_id: Optional[int],
                 file_path: Optional[str], my_username: Optional[str]) -> None:
        # 1) Always run the version block first
        self.run_versions_only()

        # 2) Repo / project enumeration (no params required)
        print("\n[probe] existing — projects & repos (no params)")
        self._get("projects_list",
                  "List visible projects (used by repo picker / commit-msg gen)",
                  "/rest/api/1.0/projects?limit=100",
                  category="existing")
        self._get("users_filter",
                  "User filter search (mention picker / reviewer add)",
                  "/rest/api/1.0/users?filter=a&limit=10",
                  category="existing")

        # 3) Project-scoped reads (need --project-key)
        if project_key:
            pk = urllib.parse.quote(project_key)

            # Project-only candidate: list repos in this project (we'll need it for
            # the repo-scoped probes anyway, and it's documented).
            self._get("project_repos",
                      f"List repos in project {project_key}",
                      f"/rest/api/1.0/projects/{pk}/repos?limit=20",
                      category="existing",
                      notes=["Used by branch-create flow + agent's BitbucketRepoTool"])

            if repo_slug:
                rs = urllib.parse.quote(repo_slug)

                # 4) Existing repo-level reads
                print(f"\n[probe] existing — {project_key}/{repo_slug} repo reads")
                self._get("repo_get",
                          "Repo metadata",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}",
                          category="existing")
                self._get("repo_default_branch",
                          "Default branch ref",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/default-branch",
                          category="existing")
                self._get("repo_branches",
                          "Branches sorted by modification (Sprint tab branch picker)",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/branches?limit=20&orderBy=MODIFICATION",
                          category="existing")
                self._get("repo_branches_filtered",
                          "Branch filter by name (commit-msg gen branch lookup)",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/branches?limit=20&orderBy=MODIFICATION&filterText=master",
                          category="existing",
                          notes=["filterText=master is a stand-in; the plugin uses filter for branch-by-name lookup"])
                self._get("repo_pull_requests_open",
                          "List open PRs (PR dashboard)",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/pull-requests?state=OPEN&limit=10",
                          category="existing")
                self._get("repo_pull_requests_role_author",
                          "PRs where current user is AUTHOR (per-repo)",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/pull-requests?state=OPEN&role.1=AUTHOR&start=0&limit=10",
                          category="existing")
                self._get("repo_pull_requests_role_reviewer",
                          "PRs where current user is REVIEWER (per-repo)",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/pull-requests?state=OPEN&role.1=REVIEWER&start=0&limit=10",
                          category="existing")
                self._get("repo_settings_pull_requests_git",
                          "Repo-level merge strategy settings",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/settings/pull-requests/git",
                          category="existing")
                self._get("default_reviewers_conditions",
                          "Default-reviewers plugin conditions",
                          f"/rest/default-reviewers/1.0/projects/{pk}/repos/{rs}/conditions",
                          category="existing",
                          notes=["404 if default-reviewers plugin not installed; check capabilities probe"])

                # 5) Existing — repo-level browse (needs file_path; falls back to README.md)
                browse_path = file_path or "README.md"
                bp = urllib.parse.quote(browse_path)
                self._get("repo_browse_raw",
                          f"Raw file contents at default branch ({browse_path})",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/browse/{bp}?raw",
                          category="existing",
                          notes=["404 if file does not exist — pass --file-path with a known file"],
                          expect_json=False)

                # 6) Branch-targeted PR lookup (needs --branch-name)
                if branch_name:
                    branch_ref = f"refs/heads/{branch_name}"
                    encoded_ref = urllib.parse.quote(branch_ref, safe="")
                    self._get("repo_pull_requests_outgoing",
                              f"Open OUTGOING PRs from branch {branch_name}",
                              f"/rest/api/1.0/projects/{pk}/repos/{rs}/pull-requests"
                              f"?direction=OUTGOING&at={encoded_ref}&state=OPEN",
                              category="existing",
                              notes=["Used by 'is there already a PR for this branch' check"])

                # 7) PR-scoped existing reads (need --pr-id)
                if pr_id is not None:
                    print(f"\n[probe] existing — PR #{pr_id} reads")
                    self._get("pr_get",
                              "Get PR metadata (title, desc, reviewers, version)",
                              f"/rest/api/1.0/projects/{pk}/repos/{rs}/pull-requests/{pr_id}",
                              category="existing")
                    self._get("pr_activities",
                              "Activity timeline (reviewer status, comment events)",
                              f"/rest/api/1.0/projects/{pk}/repos/{rs}/pull-requests/{pr_id}/activities?limit=50&start=0",
                              category="existing")
                    self._get("pr_diff",
                              "Full PR diff",
                              f"/rest/api/1.0/projects/{pk}/repos/{rs}/pull-requests/{pr_id}/diff",
                              category="existing",
                              expect_json=False,
                              notes=["text/plain unified diff"])
                    self._get("pr_changes",
                              "Files changed in PR",
                              f"/rest/api/1.0/projects/{pk}/repos/{rs}/pull-requests/{pr_id}/changes?limit=100&start=0",
                              category="existing")
                    self._get("pr_commits",
                              "Commits in PR",
                              f"/rest/api/1.0/projects/{pk}/repos/{rs}/pull-requests/{pr_id}/commits?limit=50&start=0",
                              category="existing")
                    self._get("pr_comments",
                              "All PR comments (root threads)",
                              f"/rest/api/1.0/projects/{pk}/repos/{rs}/pull-requests/{pr_id}/comments?limit=50&start=0",
                              category="existing")
                    self._get("pr_merge_check",
                              "Mergeability check (vetoes, conflicts)",
                              f"/rest/api/1.0/projects/{pk}/repos/{rs}/pull-requests/{pr_id}/merge",
                              category="existing")

                    # 7b) Single comment by id — needs comment_id (can be derived from activities)
                    derived_comment_id = comment_id or _extract_comment_id_from_activities(
                        self.raw_dir / "pr_activities.json"
                    )
                    if derived_comment_id is not None:
                        self._get("pr_comment_by_id",
                                  f"Single comment by id ({derived_comment_id})",
                                  f"/rest/api/1.0/projects/{pk}/repos/{rs}/pull-requests/{pr_id}/comments/{derived_comment_id}",
                                  category="existing")
                    else:
                        self.results.append(ProbeResult(
                            name="pr_comment_by_id_skipped",
                            description="comment-by-id probe skipped — no comment id derivable",
                            method="-", path="-", payload_kind="error", category="existing",
                            error="pr_activities yielded no comment ids; pass --comment-id to force",
                        ))

                    # 8) PR-scoped SWAP candidates
                    print(f"\n[probe] swap candidates — PR #{pr_id}")
                    self._get("pr_blocker_comments",
                              "Blocker-only comments (vs scanning all comments)",
                              f"/rest/api/1.0/projects/{pk}/repos/{rs}/pull-requests/{pr_id}/blocker-comments?count=true",
                              category="swap",
                              notes=[
                                  "SWAP candidate for showing 'this PR has N blockers' badges. "
                                  "Plugin currently filters /comments client-side."
                              ])
                    self._get("pr_diff_filescoped",
                              "File-scoped diff (smaller payload than full /diff)",
                              f"/rest/api/1.0/projects/{pk}/repos/{rs}/pull-requests/{pr_id}/diff/README.md?contextLines=10",
                              category="swap",
                              expect_json=False,
                              notes=[
                                  "SWAP candidate for huge PRs — fetch diff per file instead of "
                                  "monolithic. Path 'README.md' is a stand-in; real use scopes "
                                  "to whatever file the user clicks in the changes tree."
                              ])

                    # 9) PR-scoped FEATURE candidates
                    print(f"\n[probe] feature candidates — PR #{pr_id}")
                    self._get("pr_tasks",
                              "PR tasks (deprecated but DC kept it)",
                              f"/rest/api/1.0/projects/{pk}/repos/{rs}/pull-requests/{pr_id}/tasks?limit=50",
                              category="feature",
                              notes=[
                                  "DEPRECATED in Bitbucket 7+. Atlassian replaced tasks with "
                                  "'commentable comments'. 410 Gone or 200 — both are useful: "
                                  "tells us whether the user's instance still has tasks data."
                              ])
                    self._get("pr_watchers",
                              "PR watchers list",
                              f"/rest/api/1.0/projects/{pk}/repos/{rs}/pull-requests/{pr_id}/watchers?limit=20",
                              category="feature",
                              notes=["Could power 'watch this PR' toggle in the PR detail panel"])
                    self._get("pr_linked_jira_issues",
                              "Jira issues linked to PR (requires Jira link plugin)",
                              f"/rest/jira/1.0/projects/{pk}/repos/{rs}/pull-requests/{pr_id}/issues",
                              category="feature",
                              notes=[
                                  "If 200 — we can show 'this PR fixes JIRA-123, JIRA-456' WITHOUT "
                                  "the plugin's manual regex over title/branch/commits. 404 if Jira "
                                  "link plugin not installed (check capabilities)."
                              ])
                    self._get("pr_properties",
                              "PR extension properties (custom apps store data here)",
                              f"/rest/api/1.0/projects/{pk}/repos/{rs}/pull-requests/{pr_id}/properties",
                              category="feature",
                              notes=["Useful for 3rd-party integrations like Code Insights, Sonar"])
                    self._get("pr_participants",
                              "Full reviewer list with approval state + lastReviewedCommit",
                              f"/rest/api/1.0/projects/{pk}/repos/{rs}/pull-requests/{pr_id}"
                              f"/participants?limit=20",
                              category="existing",
                              notes=[
                                  "Cleaner than parsing pr_get.reviewers — explicit endpoint, "
                                  "includes per-reviewer state + lastReviewedCommit timestamp."
                              ])
                    self._get("pr_patch",
                              "PR as a patch (alt to /diff)",
                              f"/rest/api/1.0/projects/{pk}/repos/{rs}/pull-requests/{pr_id}.patch",
                              category="swap",
                              expect_json=False,
                              notes=[
                                  "text/plain patch output. Useful for 'apply this PR locally' "
                                  "agent flows or offline review."
                              ])

                # 10) Repo-level SWAP candidates
                print(f"\n[probe] swap candidates — repo {project_key}/{repo_slug}")
                self._get("repo_branches_with_details",
                          "Branches with metadata (?details=true)",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/branches?limit=10&orderBy=MODIFICATION&details=true",
                          category="swap",
                          notes=[
                              "SWAP candidate for the branch-list call — adds aheadBehind, "
                              "lastModified, etc. in one call (vs current call + N follow-ups)."
                          ])
                self._get("dashboard_pull_requests_author",
                          "Cross-repo dashboard — your authored PRs",
                          "/rest/api/1.0/dashboard/pull-requests?role=AUTHOR&state=OPEN&limit=10",
                          category="swap",
                          notes=[
                              "SWAP candidate for the per-repo role.1=AUTHOR loop in "
                              "BitbucketBranchClient.getOpenPullRequestsAuthoredByMe(). "
                              "Single call vs N calls (one per repo)."
                          ])
                self._get("dashboard_pull_requests_reviewer",
                          "Cross-repo dashboard — PRs you're reviewing",
                          "/rest/api/1.0/dashboard/pull-requests?role=REVIEWER&state=OPEN&limit=10",
                          category="swap",
                          notes=[
                              "SWAP candidate for getOpenPullRequestsForReviewByMe()'s per-repo loop"
                          ])
                self._get("dashboard_pr_suggestions",
                          "Suggested PRs to review (DC 8+)",
                          "/rest/api/1.0/dashboard/pull-request-suggestions?limit=10",
                          category="swap",
                          notes=[
                              "Atlassian's algorithmic 'you should look at this' list. "
                              "Could replace / augment the manual reviewer-list filter."
                          ])
                self._get("repo_raw_alt",
                          "Alternative raw-file endpoint (cleaner shape than /browse?raw)",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/raw/README.md",
                          category="swap",
                          expect_json=False,
                          notes=[
                              "SWAP candidate for /browse?raw. /raw/{path} returns the file body "
                              "directly with proper Content-Type; /browse?raw wraps it in JSON-like text."
                          ])
                self._get("users_by_slug",
                          "User by slug (cleaner than ?filter=)",
                          "/rest/api/1.0/users/admin",
                          category="swap",
                          notes=[
                              "SWAP candidate where plugin already knows a username — by-slug is "
                              "exact-match, ?filter= is fuzzy. 'admin' is a stand-in here."
                          ])

                # 11) Commit-scoped reads + candidates (need --commit-id)
                if commit_id:
                    print(f"\n[probe] commit reads — {commit_id[:12]}…")
                    cid = urllib.parse.quote(commit_id)
                    # Existing
                    self._get("build_status_v1",
                              "Build status v1 (current plugin path)",
                              f"/rest/build-status/1.0/commits/{cid}",
                              category="existing",
                              notes=["Plugin currently uses this for the PR build-status badge"])
                    # SWAP candidates around build status
                    self._get("commit_builds_rich",
                              "Rich build status (DC 7.4+) — testResults, parent SHA, ref",
                              f"/rest/api/latest/projects/{pk}/repos/{rs}/commits/{cid}/builds",
                              category="swap",
                              notes=[
                                  "REPLACES the misnamed v0 'build_status_v2' probe. There is no "
                                  "/rest/build-status/2.0/ path — the canonical 'rich' endpoint "
                                  "lives under /rest/api/latest/. Returns testResults "
                                  "{successful, failed, skipped}, parent SHA, ref, duration, "
                                  "lastUpdated. Bamboo agents publish here in DC 8+; adopting "
                                  "this lets the PR badge show 'tests: 142/144 passed' inline."
                              ])
                    self._get("commit_deployments",
                              "Deployments associated with this commit (DC 7.16+)",
                              f"/rest/api/latest/projects/{pk}/repos/{rs}/commits/{cid}/deployments",
                              category="feature",
                              notes=[
                                  "NEW SURFACE — completely missing from v0 probe. Gated by "
                                  "the 'deployment' capability (confirmed advertised on this "
                                  "instance). Returns environment + state + url per deployment. "
                                  "Powers 'this commit shipped to staging at 14:32' inline indicators."
                              ])
                    self._post("commit_builds_stats_bulk",
                               "Bulk build-status stats — read-only POST",
                               "/rest/build-status/1.0/commits/stats",
                               category="feature",
                               json_body={"commits": [commit_id]},
                               notes=[
                                   "Read-only POST: body is a list of commit IDs, returns "
                                   "{successful, failed, inProgress} counts per commit. Useful for "
                                   "coloring a commit-list red/green without N round-trips."
                               ])
                    self._get("build_status_v1_stats",
                              "Build status v1 aggregate counts (PASS/FAIL/INPROGRESS)",
                              f"/rest/build-status/1.0/commits/stats/{cid}",
                              category="swap",
                              notes=[
                                  "SWAP candidate for cheap dashboard counters — returns "
                                  "{successful, failed, inProgress} without listing each build."
                              ])
                    # FEATURE candidates around commit
                    self._get("commit_get",
                              "Commit metadata",
                              f"/rest/api/1.0/projects/{pk}/repos/{rs}/commits/{cid}",
                              category="feature",
                              notes=["New surface — could power 'show last commit on branch' badges"])
                    self._get("commit_changes",
                              "Files changed in commit",
                              f"/rest/api/1.0/projects/{pk}/repos/{rs}/commits/{cid}/changes?limit=20",
                              category="feature")
                    self._get("commit_pull_requests_reverse",
                              "REVERSE: PRs that touched this commit",
                              f"/rest/api/1.0/projects/{pk}/repos/{rs}/commits/{cid}/pull-requests",
                              category="feature",
                              notes=[
                                  "Bamboo build fails for commit X → look up the PRs containing X → "
                                  "notify those authors. Direct Bamboo↔PR bridge candidate."
                              ])
                    self._get("commit_jira_issues",
                              "Jira issues auto-extracted from commit message (Jira link plugin)",
                              f"/rest/api/1.0/projects/{pk}/repos/{rs}/commits/{cid}/jira-issues",
                              category="swap",
                              notes=[
                                  "SWAP candidate for the plugin's manual regex over commit messages. "
                                  "Atlassian's link plugin extracts keys + validates them against Jira "
                                  "in one step. 404 if Jira link plugin not installed."
                              ])

                # 12) Repo-level FEATURE candidates
                print(f"\n[probe] feature candidates — repo {project_key}/{repo_slug}")
                self._get("repo_tags",
                          "Tag list (compare-with-tag, release-notes feature)",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/tags?limit=20",
                          category="feature")
                self._get("repo_labels",
                          "Repo labels (newer DC)",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/labels?limit=20",
                          category="feature",
                          notes=["404 if not on a DC version that supports labels"])
                self._get("repo_forks",
                          "Forks of this repo",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/forks?limit=10",
                          category="feature")
                self._get("repo_related",
                          "Related repos (Atlassian-curated)",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/related?limit=10",
                          category="feature",
                          notes=["May 404 on older DC versions"])
                self._get("repo_webhooks",
                          "Webhooks configured on this repo",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/webhooks?limit=10",
                          category="feature",
                          notes=[
                              "Useful for 'why isn't my Bamboo build triggering?' debug. "
                              "May 401/403 for non-admin tokens — that's still informative."
                          ])
                # Pick the first webhook id from the response above and probe its
                # statistics-summary — turns the bare list into a 'is delivery
                # actually working?' diagnostic.
                webhooks_body = _read_raw_body(self.raw_dir / "repo_webhooks.json")
                first_webhook_id: Optional[int] = None
                if isinstance(webhooks_body, dict):
                    for w in (webhooks_body.get("values") or []):
                        if isinstance(w, dict) and w.get("id") is not None:
                            try:
                                first_webhook_id = int(w["id"])
                                break
                            except (TypeError, ValueError):
                                continue
                if first_webhook_id is not None:
                    self._get("repo_webhook_statistics_summary",
                              f"Webhook {first_webhook_id} delivery success/failure (rolled up)",
                              f"/rest/api/1.0/projects/{pk}/repos/{rs}/webhooks/"
                              f"{first_webhook_id}/statistics/summary",
                              category="feature",
                              notes=[
                                  "Diagnostic for 'why aren't my Bamboo builds triggering?' — "
                                  "shows success/failure ratio across recent deliveries."
                              ])
                self._get("repo_permissions_users",
                          "Users with explicit perms on this repo",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/permissions/users?limit=10",
                          category="feature",
                          notes=["Powers 'who can write to this repo' in the PR detail panel"])
                self._get("repo_permissions_groups",
                          "Groups with explicit perms on this repo",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/permissions/groups?limit=10",
                          category="feature")
                self._get("repo_branch_restrictions",
                          "Branch protection rules (can I push to main?)",
                          f"/rest/branch-permissions/2.0/projects/{pk}/repos/{rs}/restrictions?limit=20",
                          category="feature",
                          notes=[
                              "Lets the plugin warn before push: 'main is restricted, you'll need a "
                              "PR.' Could replace the silent push-rejection UX."
                          ])
                self._get("repo_required_builds",
                          "Required-builds merge-check conditions (correct base path)",
                          f"/rest/required-builds/latest/projects/{pk}/repos/{rs}/conditions",
                          category="feature",
                          notes=[
                              "v0 used /rest/api/1.0/.../required-builds which 404s on DC 9.4 — "
                              "the canonical path is /rest/required-builds/latest/.../conditions."
                          ])
                self._get("repo_recent_commits_feed",
                          "Recent commits (default branch) — activity feed feature",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/commits?limit=10",
                          category="feature")
                self._get("repo_ref_change_activities",
                          "Recent push activity feed (replaces ad-hoc commits scan)",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/ref-change-activities?limit=20",
                          category="feature",
                          notes=[
                              "Push-events stream — who pushed what, when. Cleaner than scanning "
                              "/commits because it includes branch-create / branch-delete / "
                              "force-push events."
                          ])
                self._get("repo_attachments",
                          "PR attachment listing (image attachments on review comments)",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/attachments?limit=20",
                          category="feature",
                          notes=[
                              "Bitbucket DC stores PR comment image attachments here. The plugin "
                              "could surface these in the AI-review summary."
                          ])
                # File-targeted feature probes
                file_for_targeted = file_path or "README.md"
                fp = urllib.parse.quote(file_for_targeted)
                self._get("repo_last_modified",
                          f"Last commit per file in path ({file_for_targeted})",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/last-modified/{fp}",
                          category="feature",
                          notes=[
                              "Powers 'who knows this code' UX — agent could surface code owners "
                              "without scanning git log client-side."
                          ])
                self._get("repo_files_browser",
                          f"Files in repo path ({file_for_targeted})",
                          f"/rest/api/1.0/projects/{pk}/repos/{rs}/files/{fp}?limit=50",
                          category="feature",
                          notes=["File-tree browser endpoint; alt to /browse with cleaner pagination"])

                # 13) Code Insights (per-commit) — confirmed installed via 'code-insights' capability.
                # Use the capabilities-discovered base path so probes survive base-path shifts
                # between point releases (Atlassian has moved insights paths in the past).
                if commit_id:
                    cid = urllib.parse.quote(commit_id)
                    insights_base = self._resolve_insights_base()
                    self._get("insights_reports",
                              "Code Insights reports for this commit",
                              f"{insights_base}/projects/{pk}/repos/{rs}/commits/{cid}/reports",
                              category="feature",
                              notes=[
                                  "Sonar / SCA tools publish findings here. Capability "
                                  "'code-insights' confirmed installed on this instance — "
                                  "this is now a real ADOPT candidate, not 'defer'."
                              ])
                    # Single report by key — uses the Sonar key as a stand-in. If the
                    # instance has no Sonar integration this 404s; that's still useful
                    # because it confirms which providers ARE publishing reports here.
                    self._get("insights_report_by_key_sonar",
                              "Single Code Insights report (sonar key as stand-in)",
                              f"{insights_base}/projects/{pk}/repos/{rs}/commits/{cid}"
                              f"/reports/io.atlassian.bitbucket.code-insights.sonar",
                              category="feature",
                              notes=[
                                  "Stand-in key. v2 should iterate keys returned by "
                                  "insights_reports above; for v1 we just confirm the "
                                  "endpoint shape works."
                              ])
                    self._get("insights_annotations_all",
                              "Cross-report annotations on this commit",
                              f"{insights_base}/projects/{pk}/repos/{rs}/commits/{cid}"
                              f"/annotations?limit=50",
                              category="feature",
                              notes=[
                                  "Returns every annotation across all reports (with optional "
                                  "?key=, ?path=, ?severity=, ?type= filters). Powers a "
                                  "'show me every Sonar+SCA finding on this commit' tab."
                              ])

        # 14) Cross-cutting search / preview / capability probes (no params)
        print("\n[probe] feature candidates — cross-cutting (search / markup / capabilities)")
        self._post("search_code",
                   "Code search (read-only POST)",
                   "/rest/search/1.0/search",
                   category="feature",
                   json_body={
                       "query": "TODO",
                       "entities": {"code": {}},
                       "limits": {"primary": 5, "secondary": 0},
                   },
                   notes=[
                       "Like Jira's /search/jql — POST for body but does not mutate. Could power "
                       "an in-IDE 'find this string across all repos' feature. 404 if "
                       "Bitbucket's search backend (Atlassian-internal indexer) isn't enabled."
                   ])
        self._post("markup_preview",
                   "Render Markdown the way Bitbucket does (read-only POST)",
                   "/rest/api/1.0/markup/preview",
                   category="feature",
                   json_body={"markup": "**hello** _world_ from probe"},
                   notes=[
                       "Useful for the agent's commit-message preview / PR-description editor — "
                       "lets us render exactly the way Bitbucket will render, no client-side guessing."
                   ])

        # 15) Inbox + profile — calling-user-scoped, no params required, return
        # personalised data. Cleaner than walking projects/repos for a "what
        # should I look at" UX.
        print("\n[probe] swap candidates — inbox + profile (calling-user scoped)")
        self._get("inbox_pr_count",
                  "Inbox PR count — single integer, perfect for tool-window badges",
                  "/rest/api/1.0/inbox/pull-requests/count",
                  category="swap",
                  notes=["Cheap badge counter (no body parsing, just int)"])
        self._get("inbox_pr_list",
                  "Inbox PRs requiring my action",
                  "/rest/api/1.0/inbox/pull-requests?limit=10",
                  category="swap",
                  notes=[
                      "Atlassian's algorithmic 'PRs that need YOUR attention' list. "
                      "More targeted than the role=REVIEWER dashboard variant."
                  ])
        self._get("profile_recent_repos",
                  "Recently-touched repos for the calling user",
                  "/rest/api/1.0/profile/recent/repos?limit=10",
                  category="swap",
                  notes=[
                      "Better than projects+repos for warm-state UX — the IntelliJ plugin's "
                      "repo picker could rank by 'recent for me' instead of alphabetical."
                  ])

        # 16) Personal access tokens — calling-user can read their own without
        # admin rights. Needs the user's username (PATs are user-scoped).
        if my_username:
            user_slug = urllib.parse.quote(my_username)
            self._get("access_tokens_self",
                      f"PATs owned by {my_username} — token-expiry inventory",
                      f"/rest/access-tokens/1.0/users/{user_slug}",
                      category="feature",
                      notes=[
                          "Surfaces token expiry dates so the plugin can warn 'your "
                          "Bitbucket token expires in 7 days' before it actually expires."
                      ])

    # -- summary --------------------------------------------------------------

    def write_summary(self, args_used: dict[str, Any]) -> None:
        summary_path = self.results_dir / "summary.md"
        lines: list[str] = []
        lines.append(f"# Bitbucket probe results — {self.base}")
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

        # Order chosen so the recommendations doc reads top-down:
        # 1) version (context) → 2) existing (what works today)
        # → 3) swap (better paths for what we already do)
        # → 4) feature (new surfaces) → 5) internal (bonus)
        for category in ("version", "existing", "swap", "feature", "internal"):
            cat_results = [r for r in self.results if r.category == category]
            if not cat_results:
                continue
            lines.append(f"## {category.title()} endpoints")
            lines.append("")
            lines.append("| Status | Endpoint | Description | Time | Notes |")
            lines.append("|---|---|---|---|---|")
            for r in cat_results:
                status_label = (
                    f"OK {r.status}" if r.ok
                    else f"FAIL {r.status or 'ERR'}"
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

        # Inventory of writes the plugin issues today — never called by the probe.
        lines.append("## Writes inventoried but NOT called (read-only probe)")
        lines.append("")
        lines.append("| Method | Endpoint | Plugin caller |")
        lines.append("|---|---|---|")
        for method, path, caller in _WRITES_INVENTORY:
            lines.append(f"| `{method}` | `{path}` | {caller} |")
        lines.append("")
        lines.append(
            "_These body shapes are captured from `BitbucketBranchClient.kt` so the "
            "recommendations doc can reason about migration risk without the probe "
            "ever mutating server state._"
        )
        lines.append("")

        lines.append("## Raw responses")
        lines.append("")
        lines.append("Each endpoint's full response (parsed JSON or text snippet) is saved to `raw/<name>.json`.")
        lines.append("These can be diffed against future probe runs to detect schema drift.")
        lines.append("")

        summary_path.write_text("\n".join(lines), encoding="utf-8")
        print(f"\n[probe] wrote summary -> {summary_path}")
        print(f"[probe] wrote {len(self.results)} raw payloads -> {self.raw_dir}")

    def _write_discover_digest(self, chosen: Optional[tuple[str, str, Optional[int]]]) -> None:
        """Render a human-friendly discover.md scoped to the user's actual access."""
        lines: list[str] = ["# Discovery — pick values for the full sweep", ""]

        # -- "Your active PRs" tables (cross-repo) -------------------------
        any_dashboard_rows = False
        for label, role in (("author", "AUTHOR"), ("reviewer", "REVIEWER")):
            body = _read_raw_body(self.raw_dir / f"discover_dashboard_{label}.json")
            rows: list[tuple[str, str, str, str, str]] = []
            if isinstance(body, dict):
                for pr in (body.get("values") or []):
                    if not isinstance(pr, dict):
                        continue
                    pr_id = pr.get("id")
                    title = str(pr.get("title", ""))
                    state = str(pr.get("state", ""))
                    to_ref = pr.get("toRef") or {}
                    repo = to_ref.get("repository") if isinstance(to_ref, dict) else None
                    proj_key = ""
                    slug = ""
                    if isinstance(repo, dict):
                        slug = str(repo.get("slug", ""))
                        proj = repo.get("project")
                        if isinstance(proj, dict):
                            proj_key = str(proj.get("key", ""))
                    if pr_id:
                        rows.append((str(pr_id), proj_key, slug, title, state))
            if rows:
                any_dashboard_rows = True
                lines.append(f"## PRs where you are {role.lower()} (top {len(rows)})")
                lines.append("")
                lines.append("| PR | Project | Repo | Title | State |")
                lines.append("|---|---|---|---|---|")
                for pid, pk, sl, ti, st in rows:
                    lines.append(
                        f"| `#{pid}` | `{pk}` | `{sl}` | {ti.replace('|', '\\|')[:80]} | {st} |"
                    )
                lines.append("")

        if not any_dashboard_rows:
            lines.append("## Your active PRs")
            lines.append("")
            lines.append(
                "_No open PRs found where you are author or reviewer. The fallback "
                "list below shows projects/repos you can browse._"
            )
            lines.append("")
            # Render fallback project + repo list if discovery took that path
            fb = _read_raw_body(self.raw_dir / "discover_projects_fallback.json")
            if isinstance(fb, dict):
                lines.append("### Projects you can browse")
                lines.append("")
                lines.append("| Key | Name |")
                lines.append("|---|---|")
                for p in (fb.get("values") or [])[:20]:
                    if isinstance(p, dict) and p.get("key"):
                        lines.append(f"| `{p['key']}` | {p.get('name', '')} |")
                lines.append("")

        # -- Repo + branch + commit details for the chosen pair -----------
        suggested_proj = "PROJECT_KEY"
        suggested_repo = "REPO_SLUG"
        suggested_pr: Any = "PR_ID"
        suggested_commit: Any = "COMMIT_SHA"
        suggested_branch: Any = "BRANCH_NAME"
        suggested_comment: Any = None

        if chosen:
            proj, slug, pr_id = chosen
            suggested_proj = proj
            suggested_repo = slug
            if pr_id is not None:
                suggested_pr = pr_id

            lines.append(f"## Chosen repo for the full sweep: `{proj}/{slug}`")
            lines.append("")

            # Default branch
            db = _read_raw_body(self.raw_dir / f"discover_default_branch_{slug}.json")
            if isinstance(db, dict):
                ref_id = db.get("id") or ""
                display = db.get("displayId") or ref_id
                if ref_id and ref_id.startswith("refs/heads/"):
                    suggested_branch = ref_id[len("refs/heads/"):]
                lines.append(f"- **Default branch:** `{display}` (`{ref_id}`)")

            # Latest commit
            rc = _read_raw_body(self.raw_dir / f"discover_recent_commits_{slug}.json")
            if isinstance(rc, dict):
                vs = rc.get("values") or []
                if vs and isinstance(vs[0], dict):
                    sha = vs[0].get("id")
                    msg = str(vs[0].get("message", ""))[:80]
                    if sha:
                        suggested_commit = sha
                    lines.append(f"- **Latest commit:** `{sha}` — {msg.replace('|', '\\|')}")

            # Latest branch (different from default — useful for OUTGOING-PR query)
            rb = _read_raw_body(self.raw_dir / f"discover_recent_branches_{slug}.json")
            if isinstance(rb, dict):
                vs = rb.get("values") or []
                if vs and isinstance(vs[0], dict):
                    bid = vs[0].get("displayId") or vs[0].get("id", "")
                    # Prefer a non-default branch for the outgoing-PR probe; if the
                    # most-recently-modified branch IS the default, the suggested
                    # --branch-name still stands but the user can override.
                    if bid and bid != suggested_branch:
                        # Don't overwrite suggested_branch with a feature branch by
                        # default — keep the default branch as primary; print the
                        # alternative for the user to pick if they want.
                        lines.append(f"- **Most recent branch:** `{bid}` (alternative for --branch-name)")

            # Comment id if PR was chosen
            if pr_id is not None:
                derived_comment = _extract_comment_id_from_activities(
                    self.raw_dir / f"discover_pr_activities_{pr_id}.json"
                )
                if derived_comment is not None:
                    suggested_comment = derived_comment
                    lines.append(f"- **Sample comment id (from PR #{pr_id}):** `{derived_comment}`")

            lines.append("")

        # -- Suggested command --------------------------------------------
        comment_arg = f" --comment-id {suggested_comment}" if suggested_comment else ""
        lines.append("---")
        lines.append("")
        lines.append("## Suggested command for the full sweep")
        lines.append("")
        lines.append(
            "_Seeded with the most-recent values from your access above. Replace "
            "any of them with whatever you'd rather use._"
        )
        lines.append("")
        lines.append("Windows `cmd`:")
        lines.append("")
        lines.append("```bat")
        lines.append(
            f"python probe_bitbucket.py --url <YOUR_BB_URL> --token <YOUR_PAT> ^\n"
            f"    --project-key {suggested_proj} ^\n"
            f"    --repo-slug {suggested_repo} ^\n"
            f"    --pr-id {suggested_pr} ^\n"
            f"    --commit-id {suggested_commit} ^\n"
            f"    --branch-name {suggested_branch}"
            + (f" ^\n    --comment-id {suggested_comment}" if suggested_comment else "")
        )
        lines.append("```")
        lines.append("")
        lines.append("PowerShell / Unix shells:")
        lines.append("")
        lines.append("```bash")
        lines.append(
            f"python probe_bitbucket.py --url <YOUR_BB_URL> --token <YOUR_PAT> \\\n"
            f"    --project-key {suggested_proj} \\\n"
            f"    --repo-slug {suggested_repo} \\\n"
            f"    --pr-id {suggested_pr} \\\n"
            f"    --commit-id {suggested_commit} \\\n"
            f"    --branch-name {suggested_branch}"
            + comment_arg
        )
        lines.append("```")
        lines.append("")

        digest_path = self.results_dir / "discover.md"
        digest_path.write_text("\n".join(lines), encoding="utf-8")
        print(f"\n[probe] wrote discovery digest -> {digest_path}")
        print("[probe] open it locally, pick values, then re-run with the suggested args.")
        print("[probe] (this file contains real project/repo names — redact before sharing.)")

    def _format_version_note(self) -> str:
        info = next((r for r in self.results if r.name == "application_properties"), None)
        if not info or not info.ok:
            return "_application-properties did not respond — version unknown._"
        raw_file = self.raw_dir / "application_properties.json"
        try:
            data = json.loads(raw_file.read_text(encoding="utf-8")).get("raw_body") or {}
        except Exception:
            return "_application-properties response could not be parsed._"
        edition, edition_source = self._detect_edition()
        return (
            f"- **version:** `{data.get('version')}`\n"
            f"- **buildNumber:** `{data.get('buildNumber')}`\n"
            f"- **buildDate:** `{data.get('buildDate')}`\n"
            f"- **displayName:** `{data.get('displayName')}`\n"
            f"- **edition:** `{edition}` _(detected via {edition_source})_\n"
        )

    def _detect_edition(self) -> tuple[str, str]:
        """Return (edition, source). Prefers `/rest/capabilities` over
        `/admin/license` because capabilities is anonymous-readable while
        admin/license requires an admin token (so non-admin PATs always 401
        there). Smart-mirroring (`repository-mirroring`) is DC-exclusive — its
        presence in capabilities is sufficient proof of edition.
        """
        body = _read_raw_body(self.raw_dir / "capabilities.json")
        if isinstance(body, dict):
            caps = body.get("capabilities")
            if isinstance(caps, dict):
                for k in caps.keys():
                    if isinstance(k, str) and "mirroring" in k:
                        return ("Data Center", "/rest/capabilities (`repository-mirroring` is DC-exclusive)")
        # Fall back to admin/license if it happened to return content
        ldata = _read_raw_body(self.raw_dir / "admin_license.json")
        if isinstance(ldata, dict) and ldata.get("dataCenter") is True:
            return ("Data Center", "/rest/api/1.0/admin/license")
        # Server SKU has been EOL since 8.x EOL — if neither signal is present,
        # the safe assumption on a 9.x instance is still DC.
        return ("Data Center (assumed — Server SKU is EOL after 8.x)",
                "fallback (capabilities did not surface mirroring; admin/license unreadable)")


# ---------------------------------------------------------------------------
# Inventory of write endpoints — never called by the probe
# ---------------------------------------------------------------------------

_WRITES_INVENTORY: list[tuple[str, str, str]] = [
    # Plugin's current writes
    ("POST",   "/rest/api/1.0/projects/{p}/repos/{r}/branches",                     "createBranch()"),
    ("POST",   "/rest/api/1.0/projects/{p}/repos/{r}/pull-requests",                "createPullRequest()"),
    ("PUT",    "/rest/api/1.0/projects/{p}/repos/{r}/pull-requests/{prId}",         "updatePullRequest()"),
    ("POST",   "/rest/api/1.0/projects/{p}/repos/{r}/pull-requests/{prId}/comments","createComment() / replyToComment()"),
    ("PUT",    "/rest/api/1.0/projects/{p}/repos/{r}/pull-requests/{prId}/comments/{commentId}", "editComment() / resolveComment()"),
    ("DELETE", "/rest/api/1.0/projects/{p}/repos/{r}/pull-requests/{prId}/comments/{commentId}", "deleteComment()"),
    ("POST",   "/rest/api/1.0/projects/{p}/repos/{r}/pull-requests/{prId}/approve", "approvePullRequest()"),
    ("DELETE", "/rest/api/1.0/projects/{p}/repos/{r}/pull-requests/{prId}/approve", "unapprovePullRequest()"),
    ("POST",   "/rest/api/1.0/projects/{p}/repos/{r}/pull-requests/{prId}/merge",   "mergePullRequest()"),
    ("POST",   "/rest/api/1.0/projects/{p}/repos/{r}/pull-requests/{prId}/decline", "declinePullRequest()"),
    ("PUT",    "/rest/api/1.0/projects/{p}/repos/{r}/pull-requests/{prId}/participants/{username}", "setReviewerStatus()"),
    # New surfaces the plugin doesn't write to TODAY but the agent could be tempted
    # to wrap. Inventoried so any future tool wrapper trips the safety review.
    ("POST",   "/rest/api/latest/projects/{p}/repos/{r}/commits/{cid}/builds",      "(future) submitBuildStatus — replaces deprecated /rest/build-status/1.0/ POST"),
    ("DELETE", "/rest/api/latest/projects/{p}/repos/{r}/commits/{cid}/builds",      "(future) deleteBuildStatusByKey"),
    ("POST",   "/rest/api/latest/projects/{p}/repos/{r}/commits/{cid}/deployments", "(future) registerDeployment — gated by 'deployment' capability"),
    ("DELETE", "/rest/api/latest/projects/{p}/repos/{r}/commits/{cid}/deployments", "(future) deleteDeployment — REPO_ADMIN required"),
    ("POST",   "/rest/insights/1.0/projects/{p}/repos/{r}/commits/{cid}/reports/{key}/annotations", "(future) addCodeInsightsAnnotations"),
    ("PUT",    "/rest/insights/1.0/projects/{p}/repos/{r}/commits/{cid}/reports/{key}",            "(future) createOrUpdateCodeInsightsReport"),
    ("DELETE", "/rest/insights/1.0/projects/{p}/repos/{r}/commits/{cid}/reports/{key}",            "(future) deleteCodeInsightsReport"),
    ("POST",   "/rest/api/1.0/projects/{p}/repos/{r}/pull-requests/{prId}/comments/{cid}/apply-suggestion", "(future) applyCodeSuggestionFromComment — DC 8.x+ feature"),
]
# Footnote: POST /rest/api/1.0/tasks was REMOVED in DC 9.0 — do not migrate to it.
# POST /rest/build-status/1.0/commits/{cid} is soft-deprecated; the GET still works
# but plugins should write to /rest/api/latest/.../commits/{cid}/builds instead.
# GET /rest/mirroring/latest/mirrorServers/{id}/token was REMOVED in 9.x for security.


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
                for p in payload[:max_items]] + (["..."] if len(payload) > max_items else [])
    if isinstance(payload, dict):
        out = {}
        for i, (k, v) in enumerate(payload.items()):
            if i >= max_items:
                out["..."] = f"+{len(payload) - max_items} more keys"
                break
            out[k] = _summarize_json(v, depth + 1, max_depth, max_items)
        return out
    return _truncate_scalar(payload)


def _truncate_scalar(v: Any) -> Any:
    if isinstance(v, str) and len(v) > 120:
        return v[:120] + f"...(+{len(v) - 120} chars)"
    return v


def _read_raw_body(raw_path: Path) -> Any:
    """Return the parsed `raw_body` from a saved probe response file, or None."""
    try:
        data = json.loads(raw_path.read_text(encoding="utf-8"))
        return data.get("raw_body")
    except Exception:
        return None


def _extract_comment_id_from_activities(raw_path: Path) -> Optional[int]:
    """Walk a /pull-requests/{id}/activities payload and return the first
    comment id (preferring a top-level COMMENTED action, falling back to any
    nested commentAction.comment.id)."""
    body = _read_raw_body(raw_path)
    if not isinstance(body, dict):
        return None
    for act in (body.get("values") or []):
        if not isinstance(act, dict):
            continue
        # Top-level COMMENTED actions have action="COMMENTED" + comment={id, text...}
        if act.get("action") == "COMMENTED":
            comment = act.get("comment")
            if isinstance(comment, dict) and comment.get("id"):
                try:
                    return int(comment["id"])
                except (TypeError, ValueError):
                    continue
        # Some PRs surface comments under commentAction
        comment_action = act.get("commentAction")
        if isinstance(comment_action, dict):
            inner = act.get("comment")
            if isinstance(inner, dict) and inner.get("id"):
                try:
                    return int(inner["id"])
                except (TypeError, ValueError):
                    continue
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
        description="Read-only Bitbucket Server / DC probe for Workflow Orchestrator plugin",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    p.add_argument("--url", required=True, help="Bitbucket base URL, e.g. https://bb.example.com")
    p.add_argument("--token", required=True, help="Personal Access Token (Bearer)")
    p.add_argument("--project-key", help="Project key, e.g. PROJ (enables project-scoped probes)")
    p.add_argument("--repo-slug", help="Repo slug, e.g. my-repo (enables repo-scoped probes)")
    p.add_argument("--pr-id", type=int, help="PR id (enables PR-scoped probes)")
    p.add_argument("--commit-id", help="Real commit SHA (enables commit + build-status probes)")
    p.add_argument("--branch-name", help="Branch short name, e.g. feature/foo "
                                          "(enables OUTGOING-PR-by-branch probe)")
    p.add_argument("--comment-id", type=int,
                   help="Specific comment id to fetch by id (auto-derived from PR activities if omitted)")
    p.add_argument("--file-path", help="Path of a file in the repo (default: README.md) — "
                                       "used by browse / raw / files / last-modified probes")
    p.add_argument("--my-username", help="Your own Bitbucket username/slug (enables the "
                                          "access-tokens-self probe so we can see token "
                                          "expiry dates without admin rights)")
    p.add_argument("--no-verify", action="store_true", help="Disable TLS verification (self-signed certs)")
    p.add_argument("--versions-only", action="store_true",
                   help="Probe application-properties + admin/license + users + 4 capability "
                        "endpoints (rest/capabilities, build, deployment, insights) and exit")
    p.add_argument("--discover", action="store_true",
                   help="Discovery mode: list active PRs, projects, repos, and "
                        "extract values for the full sweep so you don't have to "
                        "dig through Bitbucket URLs. Writes Result_N/discover.md "
                        "with a copy-paste command.")
    p.add_argument("--out", default=str(Path(__file__).parent),
                   help="Parent dir for Result_N/ output (default: alongside the script)")
    args = p.parse_args()

    if not args.token:
        print("ERROR: --token must be non-empty", file=sys.stderr)
        return 2

    if args.no_verify:
        # Suppress urllib3 SSL warnings since user explicitly opted out.
        try:
            import urllib3
            urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
        except Exception:
            pass

    out_parent = Path(args.out)
    out_parent.mkdir(parents=True, exist_ok=True)
    results_dir = _allocate_results_dir(out_parent)

    if args.discover and args.versions_only:
        print("ERROR: --discover and --versions-only are mutually exclusive.", file=sys.stderr)
        return 2

    if args.discover:
        mode_label = "discover"
    elif args.versions_only:
        mode_label = "versions-only"
    else:
        mode_label = "full sweep"

    print(f"[probe] target: {args.url}")
    print(f"[probe] output: {results_dir}")
    print(f"[probe] mode:   {mode_label}")
    print()

    probe = BitbucketProbe(args.url, args.token, verify=not args.no_verify, results_dir=results_dir)

    args_used = {
        "url": args.url,
        "project_key": args.project_key,
        "repo_slug": args.repo_slug,
        "pr_id": args.pr_id,
        "commit_id": args.commit_id,
        "branch_name": args.branch_name,
        "comment_id": args.comment_id,
        "file_path": args.file_path,
        "my_username": args.my_username,
        "no_verify": args.no_verify,
        "versions_only": args.versions_only,
        "discover": args.discover,
    }

    if args.discover:
        probe.run_discover()
    elif args.versions_only:
        probe.run_versions_only()
    else:
        probe.run_full(
            project_key=args.project_key,
            repo_slug=args.repo_slug,
            pr_id=args.pr_id,
            commit_id=args.commit_id,
            branch_name=args.branch_name,
            comment_id=args.comment_id,
            file_path=args.file_path,
            my_username=args.my_username,
        )

    probe.write_summary(args_used)
    if args.discover:
        print(f"[probe] done — open {results_dir / 'discover.md'} to pick values for the full sweep.")
    else:
        print(f"[probe] done — open {results_dir / 'summary.md'} and paste back to me.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
