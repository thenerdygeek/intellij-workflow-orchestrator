#!/usr/bin/env python3
"""
Bamboo Server / Data Center API probe for the Workflow Orchestrator IntelliJ plugin.

Read-only. Verifies every endpoint the plugin currently calls + tests a focused
set of candidate endpoints we may want to adopt (deployments, agent health,
build/Jira links, build comments, build labels, build VCS changes). Writes
per-endpoint JSON + a Markdown summary to tools/atlassian-probe/Result_N/.

Usage examples:

    # Just detect version + connectivity (4 calls, no params needed)
    python probe_bamboo.py --url https://bamboo.company.com --token PAT --versions-only

    # Discover mode — walks projects -> plans -> branches -> recent builds and
    # writes Result_N/discover.md with copy-pasteable IDs. Bamboo has no per-
    # user filter equivalent to Jira's `assignee=currentUser()`, so on large
    # instances pass --project-key (or --plan-key for a single plan) to scope
    # the walk; otherwise discover lists the first 5 projects alphabetically.
    python probe_bamboo.py --url https://bamboo.company.com --token PAT --discover \\
        --project-key MYPROJ      # scope to one project (recommended)
    python probe_bamboo.py --url https://bamboo.company.com --token PAT --discover \\
        --plan-key MYPROJ-CI      # scope to a single plan

    # Full sweep — needs realistic ids so candidates exercise non-empty payloads.
    # Use a JOB-level result key (PROJ-PLAN-JOBSHORT-N) for the build-log probe;
    # plan-level keys (PROJ-PLAN-N) yield tiny log stubs.
    python probe_bamboo.py --url https://bamboo.company.com --token PAT \\
        --plan-key PROJ-PLAN --result-key PROJ-PLAN-JOBSHORT-123 --project-key PROJ \\
        --branch-name feature/foo --commit-sha abc123def

    # Self-signed cert
    python probe_bamboo.py ... --no-verify

By default, the script never executes mutations (build trigger, restart,
queue cancel, running-build stop). Bamboo's mutating endpoints —
`POST /rest/api/latest/queue`, `POST /build/admin/restartBuild.action`,
`DELETE /rest/api/latest/queue/{key}`, `PUT /rest/api/latest/result/{key}/stop`
— are inventoried in summary.md but never invoked unless --write-test is
passed. The User-Agent string includes `(read-only)` for read modes and
`(write-test)` when --write-test is active, so admins can audit probe
traffic in Bamboo's access logs.

--write-test mode (added 2026-05-08):
    Verifies the queue-API variable encoding contract on your actual Bamboo
    by triggering ONE build with a sentinel value and confirming the value
    landed on the build. Four phases:
      A) read-only: declared plan variables + last 10 builds applied vars
      B) Y/N stdin gate showing exact request to be sent
      C) single form-encoded POST to /queue/{planKey}
      D) poll the new build's variables until match/timeout

    python probe_bamboo.py --url ... --token ... --write-test \\
        --plan-key AUTOSUITE-PLAN \\
        --variable-name dockerTagsAsJson \\
        --variable-value '{"audit-probe-2026-05-07":"v0.0.0-sentinel"}' \\
        --let-build-finish
"""

from __future__ import annotations

import argparse
import json
import re
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
# Probe result dataclass (kept compatible with redact.py + bundle.py — same
# shape as probe_bitbucket.py / probe_jira.py so tooling needs no changes).
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

class BambooProbe:
    def __init__(self, base_url: str, token: str, verify: bool, results_dir: Path):
        self.base = base_url.rstrip("/")
        self.session = requests.Session()
        self.session.headers.update({
            "Authorization": f"Bearer {token}",
            "Accept": "application/json",
            "User-Agent": "WorkflowOrchestrator-BambooProbe/1.0 (read-only)",
        })
        self.session.verify = verify
        self.results_dir = results_dir
        self.raw_dir = results_dir / "raw"
        self.raw_dir.mkdir(parents=True, exist_ok=True)
        self.results: list[ProbeResult] = []

    # -- low-level request helper ---------------------------------------------

    def _request(self, name: str, description: str, path: str, category: str,
                 method: str = "GET",
                 notes: Optional[list[str]] = None, expect_json: bool = True,
                 accept: Optional[str] = None) -> ProbeResult:
        """Issue a single HTTP request and persist its outcome.

        Bamboo's plugin-side surface uses only GET for all read paths. There are
        no read-only POST/search endpoints exposed publicly (unlike Bitbucket's
        /rest/search/1.0/search). The probe therefore restricts itself to GET.

        `expect_json=False` is used for the raw build-log download endpoint
        (`/download/{key}/build_logs/{key}.log`), which returns plain text.
        """
        url = f"{self.base}{path}"
        result = ProbeResult(
            name=name, description=description, method=method, path=path,
            category=category, notes=list(notes or []),
        )
        start = time.perf_counter()
        raw_payload: Any = None
        per_request_headers = {"Accept": accept} if accept else None
        try:
            if method == "GET":
                resp = self.session.get(url, timeout=30, allow_redirects=False,
                                        headers=per_request_headers)
            else:
                raise ValueError(f"Unsupported method (probe is read-only): {method}")
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
                # Plain-text endpoints (build log download). Capture only a
                # short preview + total byte count — full text would explode
                # raw/*.json sizes (build logs are 20-30KB each).
                result.payload_kind = "text"
                result.payload_preview = body[:500]
                if len(body) > 500:
                    result.notes.append(f"Truncated text body ({len(body)} bytes total)")
                if not expect_json:
                    result.notes.append(f"Non-JSON response (Content-Type={content_type})")

            if 300 <= resp.status_code < 400:
                result.notes.append(
                    f"Redirect to {resp.headers.get('Location', '?')} "
                    "(plugin's HttpClientFactory follows redirects by default)"
                )
        except requests.RequestException as e:
            result.error = f"{type(e).__name__}: {e}"
            result.payload_kind = "error"
            result.elapsed_ms = int((time.perf_counter() - start) * 1000)

        # Write raw response for diffing across probe runs.
        # NOTE: text/log endpoints store only the truncated preview in raw_body
        # to keep result files diffable; the size is recorded in notes.
        raw_file = self.raw_dir / f"{name}.json"
        raw_file.write_text(json.dumps({
            "result": asdict(result),
            "raw_body": raw_payload if raw_payload is not None else None,
        }, indent=2, default=str), encoding="utf-8")
        self.results.append(result)
        return result

    def _get(self, name: str, description: str, path: str, category: str,
             notes: Optional[list[str]] = None, expect_json: bool = True,
             accept: Optional[str] = None) -> ProbeResult:
        return self._request(name, description, path, category,
                             method="GET", notes=notes, expect_json=expect_json,
                             accept=accept)

    # -- modes ----------------------------------------------------------------

    def run_versions_only(self) -> None:
        """Minimal version-detection sweep — 4 calls, no params required.

        Bamboo's canonical version-detect endpoint is `/rest/api/latest/info`.
        It returns `version`, `buildDate`, `buildNumber`, `state`. There is no
        Bamboo equivalent of Bitbucket's `/rest/capabilities` (Bamboo's plugin
        surface is more uniform across deployments) so edition detection
        relies on `/info` plus the configurationProperties endpoint when the
        token has admin scope.
        """
        print("[probe] versions-only mode\n")
        self._get(
            name="info",
            description="Server version + build + state (canonical Bamboo version detect)",
            path="/rest/api/latest/info",
            category="version",
            notes=["Returns {version, buildDate, buildNumber, state} — paste this back to fix the version"],
        )
        self._get(
            name="server_info",
            description="Alias check — some Bamboo deployments expose /serverInfo (Jira-style)",
            path="/rest/api/latest/serverInfo",
            category="version",
            notes=["Expected 404 on most Bamboo Servers; only present if a 3rd-party plugin adds it"],
        )
        self._get(
            name="current_user",
            description="Connection check + identity (auth + permissions baseline)",
            path="/rest/api/latest/currentUser",
            category="version",
            notes=["If this 401s, the PAT is invalid / IDP enforces SSO + PAT auth disabled"],
        )
        self._get(
            name="info_configuration_properties",
            description="Configuration properties — admin-gated, used to gate which features are usable",
            path="/rest/api/latest/info/configurationProperties",
            category="version",
            notes=[
                "Returns 401/403 for non-admin tokens; that's fine — we just need the existence check.",
                "On admin tokens this surfaces baseUrl, smtp, build expiry policy, etc.",
            ],
        )
        print("[probe] versions-only done — open Result_N/summary.md and paste the Version detection block back.")

    def run_full_sweep(
        self,
        plan_key: Optional[str],
        result_key: Optional[str],
        project_key: Optional[str],
        branch_name: Optional[str],
        commit_sha: Optional[str],
    ) -> None:
        """Full sweep — covers all 24 read-only endpoints from BambooApiClient
        + 8 feature-discovery candidates. Endpoints needing params are skipped
        cleanly when the param is absent (logged in summary.md as 'skipped')."""
        print("[probe] full sweep mode\n")

        # --- always-runnable version block (same as run_versions_only) ------
        self._get(
            name="info",
            description="Server version + build + state",
            path="/rest/api/latest/info",
            category="version",
        )
        self._get(
            name="server_info",
            description="Alias check — /serverInfo (Jira-style alias, usually 404)",
            path="/rest/api/latest/serverInfo",
            category="version",
        )
        self._get(
            name="current_user",
            description="Auth + identity baseline",
            path="/rest/api/latest/currentUser",
            category="version",
        )
        self._get(
            name="info_configuration_properties",
            description="Admin-gated server config (baseUrl, smtp, expiry policy)",
            path="/rest/api/latest/info/configurationProperties",
            category="version",
            notes=["Expected 401/403 for non-admin PATs"],
        )

        # --- existing endpoints (mirroring BambooApiClient.kt 1:1) ----------

        # Plans + projects
        self._get(
            name="plan_list",
            description="getPlans() — all plans, expand plans.plan",
            path="/rest/api/latest/plan?expand=plans.plan&max-results=100",
            category="existing",
        )
        self._get(
            name="project_list",
            description="getProjects() — all projects",
            path="/rest/api/latest/project?max-results=100",
            category="existing",
        )
        if project_key:
            self._get(
                name="project_plans",
                description=f"getProjectPlans({project_key}) — plans inside a project",
                path=f"/rest/api/latest/project/{project_key}?expand=plans.plan",
                category="existing",
            )
        else:
            self._skip("project_plans", "getProjectPlans() — needs --project-key", "existing")

        if plan_key:
            self._get(
                name="search_plans",
                description=f"searchPlans({plan_key}) — fuzzy plan search",
                path=f"/rest/api/latest/search/plans?searchTerm={urllib.parse.quote(plan_key)}&fuzzy=true&max-results=25",
                category="existing",
            )
            self._get(
                name="plan_specs_yaml",
                description=f"getPlanSpecs({plan_key}) — YAML specs export",
                path=f"/rest/api/latest/plan/{plan_key}/specs?format=YAML",
                category="existing",
                expect_json=False,
                notes=["Returns YAML; we capture only a snippet to keep raw/* small"],
            )
            self._get(
                name="plan_branches",
                description=f"getBranches({plan_key}) — list branch plans",
                path=f"/rest/api/latest/plan/{plan_key}/branch?max-results=100",
                category="existing",
            )
            self._get(
                name="plan_validate",
                description=f"validatePlan({plan_key}) — does the plan exist?",
                path=f"/rest/api/latest/plan/{plan_key}",
                category="existing",
                notes=["404 here means the key is bogus; useful for plan-detection waterfall"],
            )

            # Plan variables — primary + fallback (memory: project_bamboo_api_probe_findings notes
            # both 404 on user's CI plan; we still probe both so the recommendations doc can confirm)
            self._get(
                name="plan_variables_via_context",
                description="getPlanVariableContext() — plan variables via expand=variableContext",
                path=f"/rest/api/latest/plan/{plan_key}?expand=variableContext",
                category="existing",
                notes=["Primary path; works on all Bamboo versions"],
            )
            self._get(
                name="plan_variables_direct",
                description="getPlanVariableDirect() — fallback /plan/{key}/variable",
                path=f"/rest/api/latest/plan/{plan_key}/variable",
                category="existing",
                notes=["Fallback path; April 2026 probe found 404 on user's CI plan"],
            )

            # Latest result by plan (and optionally by branch)
            self._get(
                name="result_latest_plan",
                description=f"getLatestResult({plan_key}) — most recent build for the plan",
                path=f"/rest/api/latest/result/{plan_key}/latest?expand=stages.stage.results.result",
                category="existing",
            )
            if branch_name:
                self._get(
                    name="result_latest_branch",
                    description=f"getLatestResult({plan_key}, branch={branch_name})",
                    path=f"/rest/api/latest/result/{plan_key}/branch/{urllib.parse.quote(branch_name, safe='')}/latest?expand=stages.stage.results.result",
                    category="existing",
                )
            else:
                self._skip("result_latest_branch", "getLatestResult(branch) — needs --branch-name", "existing")

            self._get(
                name="result_running_queued",
                description=f"getRunningAndQueuedBuilds({plan_key})",
                path=f"/rest/api/latest/result/{plan_key}?includeAllStates=true&max-results=5&expand=stages.stage.results.result",
                category="existing",
            )
            self._get(
                name="result_recent",
                description=f"getRecentResults({plan_key}) — list with nested stage + variable expand",
                path=(f"/rest/api/latest/result/{plan_key}?max-results=10"
                      "&expand=results.result.stages.stage.results.result,results.result.variables.variable"),
                category="existing",
            )
        else:
            for n, d in [
                ("search_plans", "searchPlans()"),
                ("plan_specs_yaml", "getPlanSpecs()"),
                ("plan_branches", "getBranches()"),
                ("plan_validate", "validatePlan()"),
                ("plan_variables_via_context", "getPlanVariableContext()"),
                ("plan_variables_direct", "getPlanVariableDirect()"),
                ("result_latest_plan", "getLatestResult()"),
                ("result_latest_branch", "getLatestResult(branch)"),
                ("result_running_queued", "getRunningAndQueuedBuilds()"),
                ("result_recent", "getRecentResults()"),
            ]:
                self._skip(n, f"{d} — needs --plan-key", "existing")

        # Result-level endpoints
        if result_key:
            self._get(
                name="result_full",
                description=f"getBuildResult({result_key})",
                path=f"/rest/api/latest/result/{result_key}?expand=stages.stage.results.result",
                category="existing",
            )
            self._get(
                name="result_vcs_revisions",
                description=f"getResultVcsRevision({result_key}) — Bamboo→Bitbucket bridge",
                path=f"/rest/api/latest/result/{result_key}?expand=vcsRevisions",
                category="existing",
                notes=["Used by BuildFailureBridgeStartupActivity to map failed build → PR ids"],
            )
            self._get(
                name="result_test_results",
                description=f"getTestResults({result_key}) — failed + successful tests",
                path=(f"/rest/api/latest/result/{result_key}"
                      "?expand=testResults.failedTests.testResult,testResults.successfulTests.testResult"),
                category="existing",
            )
            self._get(
                name="result_variables",
                description=f"getBuildVariables({result_key}) — variables.variable[]",
                path=f"/rest/api/latest/result/{result_key}?expand=variables",
                category="existing",
                notes=["Used to find dockerTagsAsJson / per-build trigger vars"],
            )
            self._get(
                name="result_artifacts",
                description=f"getArtifacts({result_key})",
                path=f"/rest/api/latest/result/{result_key}?expand=artifacts.artifact",
                category="existing",
            )
            # Build log download — the only text endpoint we hit. April 2026
            # probe found job-level keys give 29KB logs; plan-level keys give
            # 101-byte stubs. Probe captures the URL contract; full text is
            # deliberately truncated in raw/*.json (see _request).
            self._get(
                name="build_log_download",
                description=f"getBuildLog({result_key}) — /download/.../build_logs/*.log",
                path=f"/download/{result_key}/build_logs/{result_key}.log",
                category="existing",
                expect_json=False,
                accept="text/plain",
                notes=[
                    "Plain-text endpoint. Job-level result_key (e.g. PROJ-PLAN-JOBSHORT-NNN)"
                    " yields the real ~30KB log; plan-level key yields a tiny wrapper.",
                ],
            )
        else:
            for n, d in [
                ("result_full", "getBuildResult()"),
                ("result_vcs_revisions", "getResultVcsRevision()"),
                ("result_test_results", "getTestResults()"),
                ("result_variables", "getBuildVariables()"),
                ("result_artifacts", "getArtifacts()"),
                ("build_log_download", "getBuildLog()"),
            ]:
                self._skip(n, f"{d} — needs --result-key", "existing")

        # Bamboo→Bitbucket bridge: byChangeset
        if commit_sha:
            self._get(
                name="result_by_changeset",
                description=f"getResultsByChangeset({commit_sha})",
                path=f"/rest/api/latest/result/byChangeset/{commit_sha}?expand=results.result.plan",
                category="existing",
                notes=["Bridge endpoint — maps SHA → all branch-aware build results"],
            )
        else:
            self._skip("result_by_changeset", "getResultsByChangeset() — needs --commit-sha", "existing")

        # Linked repositories
        self._get(
            name="repository_list",
            description="getLinkedRepositories() — all linked repos",
            path="/rest/api/latest/repository?max-results=200",
            category="existing",
        )
        # repository/{id}/usedBy — needs an id we'd have to discover; skip in v0
        # rather than parse repository_list and re-call. Recommendations doc can
        # propose a `--repo-id` flag in v1 if usedBy is wanted in the sweep.
        self._skip("repository_used_by", "getRepositoryUsedBy() — needs repo id from repository_list (v1 enhancement)", "existing")

        # --- candidate / feature-discovery endpoints ------------------------
        # These are NOT in BambooApiClient today. Goal of probing them: find
        # surfaces that could power features the plugin doesn't have yet.

        self._get(
            name="agent_list",
            description="(candidate) /agent — list build agents + their state",
            path="/rest/api/latest/agent",
            category="feature",
            notes=[
                "Could power an 'agent health' badge on the Build tab when a build",
                " sits in Queued for too long because no agent is online for its",
                " required capabilities.",
            ],
        )
        self._get(
            name="queue_global",
            description="(candidate) /queue — global build queue",
            path="/rest/api/latest/queue",
            category="feature",
            notes=[
                "Plugin polls per-plan running/queued today; a single global call",
                " could replace N per-plan calls when monitoring many services.",
            ],
        )
        self._get(
            name="deploy_project_all",
            description="(candidate) /deploy/project/all — deployment projects",
            path="/rest/api/latest/deploy/project/all",
            category="feature",
            notes=[
                "Bamboo deployment projects are fully separate from build plans.",
                " Plugin has no deployment surface today; this is feature-discovery.",
            ],
        )
        self._get(
            name="labels_global",
            description="(candidate) /labels — all labels in the instance",
            path="/rest/api/latest/labels?max-results=20",
            category="feature",
            notes=["Could power label-based filtering in plan picker"],
        )

        if result_key:
            self._get(
                name="result_jira_issues",
                description="(candidate) /result/{key}/jiraIssues — Jira issues linked to build",
                path=f"/rest/api/latest/result/{result_key}/jiraIssues",
                category="feature",
                notes=[
                    "Returns the smart-commit-detected Jira issue keys for this build.",
                    " Could replace the plugin's own commit-message scan + give us",
                    " the Jira link directly from Bamboo.",
                ],
            )
            self._get(
                name="result_comments",
                description="(candidate) /result/{key}/comment — comments on a build",
                path=f"/rest/api/latest/result/{result_key}/comment",
                category="feature",
                notes=["Could power 'leave a build comment' from the Build tab"],
            )
            self._get(
                name="result_changes",
                description="(candidate) /result/{key}?expand=changes.change — VCS changes per build",
                path=f"/rest/api/latest/result/{result_key}?expand=changes.change",
                category="feature",
                notes=[
                    "Per-build commit list. Today plugin only captures vcsRevisions",
                    " (one commit). changes gives the full delta + author.",
                ],
            )
            self._get(
                name="result_labels",
                description="(candidate) /result/{key}?expand=labels.label — labels on a build",
                path=f"/rest/api/latest/result/{result_key}?expand=labels.label",
                category="feature",
                notes=["Could power 'label this build as release-ready / broken'"],
            )
        else:
            for n, d in [
                ("result_jira_issues", "/result/{key}/jiraIssues"),
                ("result_comments", "/result/{key}/comment"),
                ("result_changes", "/result/{key}?expand=changes.change"),
                ("result_labels", "/result/{key}?expand=labels.label"),
            ]:
                self._skip(n, f"{d} — needs --result-key", "feature")

        print(f"[probe] full sweep done — {sum(1 for r in self.results if r.ok)}/"
              f"{len(self.results)} OK, "
              f"{sum(1 for r in self.results if not r.ok and r.error is None)} non-2xx, "
              f"{sum(1 for r in self.results if r.error)} errored.")

    def run_discover(
        self,
        project_key: Optional[str] = None,
        plan_key: Optional[str] = None,
    ) -> None:
        """Discovery walk — finds the IDs the full sweep needs from the user's
        actual permissions, not from a global dump. Read-only.

        Scoping (most-specific first):
            - `--plan-key X`   : walk only that plan. Project key derived
              from `X.split('-', 1)[0]`. Skips project listing entirely.
            - `--project-key X`: walk only that project's plans (up to 10).
              Skips the global `/project` listing.
            - neither: walk the first 5 projects globally (alphabetical-by-
              key). Bamboo has no per-user filter equivalent to Jira's
              `assignee=currentUser()`, so on instances with hundreds of
              projects this often surfaces unrelated plans — pass scope.

        Common steps after scoping:
            * For each candidate plan, list branches + recent results
              (`/plan/{key}/branch`, `/result/{key}?expand=...`).
            * From the first plan with recent builds, fetch
              `?expand=vcsRevisions` to extract a sample commit SHA.
            * Write `Result_N/discover.md` with a copy-paste full-sweep
              command.

        The plugin uses Bamboo across two tabs with different needs (Build vs
        Automation). The digest flags plans whose recent builds expose a
        `dockerTagsAsJson` variable as automation suite candidates (matches
        `automation/service/ConflictDetectorService.kt` +
        `TagBuilderService.kt`).
        """
        print("[probe] discover mode\n")
        self.run_versions_only()

        scope_hint: Optional[str] = None
        plan_specs: list[dict]
        plan_keys: list[str]
        project_keys: list[str]

        if plan_key:
            scope_hint = f"`--plan-key {plan_key}` (single plan)"
            print(f"\n[probe] discover — scoped to plan {plan_key}")
            derived_proj = plan_key.split("-", 1)[0] if "-" in plan_key else ""
            plan_specs = [{
                "key": plan_key,
                "name": "(scoped via --plan-key)",
                "project_key": derived_proj,
            }]
            plan_keys = [plan_key]
            project_keys = [derived_proj] if derived_proj else []
        elif project_key:
            scope_hint = f"`--project-key {project_key}` (one project)"
            print(f"\n[probe] discover — scoped to project {project_key}")
            self._get(
                name=f"discover_project_{project_key}_plans",
                description=f"Plans in project {project_key}",
                path=f"/rest/api/latest/project/{project_key}?expand=plans.plan",
                category="existing",
                notes=[f"Discovery — plans inside {project_key}"],
            )
            project_keys = [project_key]
            plan_specs = _collect_candidate_plans(
                self.raw_dir, project_keys, max_total=10,
            )
            if not plan_specs:
                print(f"[probe] discover — project {project_key} has no plans "
                      "visible to your PAT (or the project key is wrong)")
                self._write_discover_digest(
                    project_keys, [], None, scope_hint=scope_hint,
                )
                return
            plan_keys = [p["key"] for p in plan_specs]
        else:
            scope_hint = (
                "_unscoped — first 5 projects alphabetically. "
                "Re-run with `--project-key YOURPROJ` for an accurate walk._"
            )
            print("\n[probe] discover — unscoped walk (first 5 projects "
                  "alphabetically)")
            print("[probe] tip: re-run with --project-key YOURPROJ to scope")
            self._get(
                name="discover_projects",
                description="All visible projects (paginated, first 200)",
                path="/rest/api/latest/project?max-results=200",
                category="existing",
                notes=["Discovery — projects readable by your PAT"],
            )
            project_keys = _collect_top_project_keys(
                self.raw_dir / "discover_projects.json", limit=5,
            )
            if not project_keys:
                print("[probe] discover — no projects visible; cannot continue walk")
                self._write_discover_digest([], [], None, scope_hint=scope_hint)
                return
            print(f"[probe] discover — inspecting first {len(project_keys)} "
                  f"project(s): {', '.join(project_keys)}")
            for pkey in project_keys:
                self._get(
                    name=f"discover_project_{pkey}_plans",
                    description=f"Plans in project {pkey}",
                    path=f"/rest/api/latest/project/{pkey}?expand=plans.plan",
                    category="existing",
                    notes=[f"Discovery — plans inside {pkey}"],
                )
            plan_specs = _collect_candidate_plans(
                self.raw_dir, project_keys, max_total=5,
            )
            if not plan_specs:
                print("[probe] discover — projects had no plans visible to your PAT")
                self._write_discover_digest(
                    project_keys, [], None, scope_hint=scope_hint,
                )
                return
            plan_keys = [p["key"] for p in plan_specs]

        print(f"[probe] discover — inspecting {len(plan_keys)} plan(s): "
              f"{', '.join(plan_keys)}")
        for plkey in plan_keys:
            self._get(
                name=f"discover_plan_{plkey}_branches",
                description=f"Branches for plan {plkey}",
                path=f"/rest/api/latest/plan/{plkey}/branch?max-results=10",
                category="existing",
                notes=[f"Discovery — branches on {plkey}"],
            )
            self._get(
                name=f"discover_plan_{plkey}_recent",
                description=f"Recent results for plan {plkey}",
                path=(f"/rest/api/latest/result/{plkey}?max-results=5"
                      "&expand=results.result.stages.stage.results.result,"
                      "results.result.variables.variable"),
                category="existing",
                notes=[f"Discovery — recent build results for {plkey}"],
            )

        sample = _extract_sample_from_results(self.raw_dir, plan_keys)
        if sample and sample.get("plan_result_key"):
            sample_rk = sample["plan_result_key"]
            self._get(
                name=f"discover_result_{sample_rk}_vcs",
                description=f"VCS revisions for sample result {sample_rk}",
                path=f"/rest/api/latest/result/{sample_rk}?expand=vcsRevisions",
                category="existing",
                notes=["Discovery — sample commit SHA for --commit-sha hint"],
            )
            sha = _extract_commit_sha(
                self.raw_dir / f"discover_result_{sample_rk}_vcs.json"
            )
            if sha:
                sample["commit_sha"] = sha

        # When scoped to a single plan, replay the automation plugin's
        # baseline detection so the user can verify the read paths AND
        # see how the plugin's scoring compares to the proposed two-tier
        # algorithm (tier 1 = all-release tags; tier 2 = ranked by lowest
        # failed-test count).
        if plan_key:
            self.mirror_baseline_detection(plan_key=plan_key)

        self._write_discover_digest(
            project_keys, plan_specs, sample, scope_hint=scope_hint,
        )

    # -- write-test mode (opt-in via --write-test) ----------------------------

    def _post_form(self, name: str, description: str, path: str,
                   form_body: str, category: str) -> ProbeResult:
        """Form-encoded POST. Used ONLY by --write-test mode.

        Mirrors _request's instrumentation (raw file, ProbeResult) so the
        write-test fits cleanly into summary.md alongside the read probes.
        Sets allow_redirects=False so an HTML auth-redirect is detected,
        not silently followed.
        """
        url = f"{self.base}{path}"
        result = ProbeResult(
            name=name, description=description, method="POST", path=path,
            category=category,
        )
        start = time.perf_counter()
        raw_payload: Any = None
        try:
            # X-Atlassian-Token: no-check is required by Atlassian apps
            # (Bamboo / Jira / Confluence) to bypass XSRF protection on
            # mutating API endpoints. Without it, Bamboo's queue endpoint
            # returns 403 before checking BUILD permission. Plugin's
            # BambooApiClient is missing this header too — see audit.
            resp = self.session.post(
                url,
                data=form_body,
                headers={
                    "Content-Type": "application/x-www-form-urlencoded",
                    "X-Atlassian-Token": "no-check",
                },
                timeout=30,
                allow_redirects=False,
            )
            result.status = resp.status_code
            result.elapsed_ms = int((time.perf_counter() - start) * 1000)
            result.ok = 200 <= resp.status_code < 300

            content_type = (resp.headers.get("Content-Type") or "").lower()
            body = resp.text or ""
            if not body:
                result.payload_kind = "empty"
            elif "json" in content_type:
                try:
                    raw_payload = resp.json()
                    result.payload_kind = "json"
                    result.payload_preview = _summarize_json(raw_payload)
                except ValueError:
                    result.payload_kind = "text"
                    result.payload_preview = body[:500]
            else:
                result.payload_kind = "text"
                result.payload_preview = body[:500]
                if "html" in content_type:
                    result.notes.append(
                        "Response was HTML — likely auth redirect to login. "
                        "If your token is a PAT, re-check that PAT auth is enabled."
                    )

            if 300 <= resp.status_code < 400:
                result.notes.append(
                    f"Redirect to {resp.headers.get('Location', '?')} — "
                    "likely auth failure (write-test does not follow redirects)."
                )
        except requests.RequestException as e:
            result.error = f"{type(e).__name__}: {e}"
            result.payload_kind = "error"
            result.elapsed_ms = int((time.perf_counter() - start) * 1000)

        raw_file = self.raw_dir / f"{name}.json"
        raw_file.write_text(json.dumps({
            "result": asdict(result),
            "request_body": form_body,
            "raw_body": raw_payload if raw_payload is not None else None,
        }, indent=2, default=str), encoding="utf-8")
        self.results.append(result)
        return result

    # -- baseline-detection mirror (mirrors TagBuilderService.scoreAndRankRuns) ----

    _SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+(\.\d+)?$")

    def _process_build_for_mirror(
        self,
        plan_key: str,
        build: dict,
        variable_name: str,
    ) -> tuple[dict, Optional[dict]]:
        """Process one build dict for the baseline mirror. Issues per-build
        GET for variables and computes scoring inputs. Returns
        (entry, divergence_or_none). `entry` is parseable when it carries
        `plugin_score` — non-parseable entries get a `skipped` field.
        """
        build_num = build.get("buildNumber", "?")
        raw_result_key = build.get("buildResultKey") or ""
        result_key = raw_result_key or f"{plan_key}-{build_num}"
        state = build.get("state", "?")
        life = build.get("lifeCycleState", "?")

        # Per-build vars (the path the plugin uses for the lookup)
        self._get(
            name=f"baseline_vars_{result_key}",
            description=f"getBuildVariables({result_key}) — per-build expand=variables",
            path=f"/rest/api/latest/result/{result_key}?expand=variables",
            category="baseline_mirror",
        )
        per_build_blob = _read_raw_body(
            self.raw_dir / f"baseline_vars_{result_key}.json"
        )
        per_build_vars: dict = {}
        if per_build_blob:
            per_build_vars = {
                v.get("name"): v.get("value")
                for v in (per_build_blob.get("variables") or {}).get("variable") or []
                if v.get("name")
            }

        # Embedded vars (from the collection expand) for divergence check
        embedded_vars = {
            v.get("name"): v.get("value")
            for v in (build.get("variables") or {}).get("variable") or []
            if v.get("name")
        }
        only_in_embedded = sorted(set(embedded_vars) - set(per_build_vars))
        only_in_per_build = sorted(set(per_build_vars) - set(embedded_vars))
        differing_values = sorted(
            k for k in (set(embedded_vars) & set(per_build_vars))
            if embedded_vars[k] != per_build_vars[k]
        )
        divergence: Optional[dict] = None
        if only_in_embedded or only_in_per_build or differing_values:
            divergence = {
                "build": build_num,
                "result_key": result_key,
                "only_in_embedded": only_in_embedded,
                "only_in_per_build": only_in_per_build,
                "differing_values": differing_values,
            }

        # Plugin's case-insensitive lookup on per-build vars
        docker_tags_value: Optional[str] = next(
            (v for k, v in per_build_vars.items()
             if k.lower() == variable_name.lower()),
            None,
        )

        # Stages live at build["stages"]["stage"] when expanded
        stages_node = build.get("stages") or {}
        stages = stages_node.get("stage") if isinstance(stages_node, dict) else stages_node
        if not isinstance(stages, list):
            stages = []
        success_stages = sum(
            1 for s in stages
            if (s.get("state") or "").lower() == "successful"
        )
        failed_stages = sum(
            1 for s in stages
            if (s.get("state") or "").lower() == "failed"
        )

        # Test counts — try build-level first, fall back to summing nested
        # job results. Different Bamboo configs populate one or the other.
        failed_tests_top = build.get("failedTestCount")
        success_tests_top = build.get("successfulTestCount")
        failed_tests_summed = 0
        success_tests_summed = 0
        for s in stages:
            results_node = s.get("results") or {}
            jobs = results_node.get("result") if isinstance(results_node, dict) else results_node
            if not isinstance(jobs, list):
                jobs = []
            for j in jobs:
                failed_tests_summed += int(j.get("failedTestCount") or 0)
                success_tests_summed += int(j.get("successfulTestCount") or 0)
        failed_tests = (
            failed_tests_top if isinstance(failed_tests_top, int) else failed_tests_summed
        )
        success_tests = (
            success_tests_top if isinstance(success_tests_top, int) else success_tests_summed
        )

        entry = {
            "build_num": build_num,
            "result_key": result_key,
            "raw_result_key_was_empty": raw_result_key == "",
            "state": state,
            "lifecycle": life,
            "var_count_per_build": len(per_build_vars),
            "var_count_embedded": len(embedded_vars),
            "success_stages": success_stages,
            "failed_stages": failed_stages,
            "failed_tests": failed_tests,
            "success_tests": success_tests,
            "failed_tests_source": (
                "build-level" if isinstance(failed_tests_top, int)
                else "summed-from-jobs"
            ),
        }

        if docker_tags_value is None:
            entry["skipped"] = (
                f"no '{variable_name}' (case-insensitive) in "
                f"{len(per_build_vars)} per-build vars"
            )
            return entry, divergence

        try:
            tags = json.loads(docker_tags_value)
        except (json.JSONDecodeError, ValueError):
            entry["skipped"] = "value present but did not parse as JSON"
            entry["raw_value_preview"] = docker_tags_value[:120]
            return entry, divergence
        if not isinstance(tags, dict):
            entry["skipped"] = "value parsed but not a JSON object"
            return entry, divergence
        if not tags:
            entry["skipped"] = "value parsed to empty object"
            return entry, divergence

        release_count = sum(
            1 for v in tags.values()
            if isinstance(v, str) and self._SEMVER_RE.match(v)
        )
        total_services = len(tags)
        all_release = total_services > 0 and release_count == total_services

        # Plugin's current scoring (TagBuilderService.kt:110)
        plugin_score = (release_count * 10) + (success_stages * 5) - (failed_stages * 20)

        entry.update({
            "total_services": total_services,
            "release_count": release_count,
            "all_release": all_release,
            "plugin_score": plugin_score,
            "docker_tags_sample": dict(list(tags.items())[:3]),
            "docker_tags_full": tags,
        })
        return entry, divergence

    def _print_full_dockertags(self, label: str, entry: dict) -> None:
        """Pretty-print a baseline pick's full dockerTagsAsJson with a
        ✓ marker on each tag that matches the semver release pattern."""
        tags = entry.get("docker_tags_full") or {}
        print(f"\n    {label} dockerTagsAsJson "
              f"(build #{entry['build_num']}, {entry['result_key']}):")
        if not tags:
            print(f"      (empty)")
            return
        longest = max(len(k) for k in tags)
        for service, tag in sorted(tags.items()):
            tag_str = tag if isinstance(tag, str) else json.dumps(tag)
            is_release = isinstance(tag, str) and self._SEMVER_RE.match(tag)
            marker = "  ✓ release" if is_release else ""
            print(f"      {service:<{longest}}  =  {tag_str}{marker}")

    def mirror_baseline_detection(
        self,
        plan_key: str,
        variable_name: str = "DockerTagsAsJSON",
        target_parseable: int = 10,
        max_walk: int = 50,
    ) -> dict:
        """Replays automation/TagBuilderService.kt:scoreAndRankRuns against
        the live Bamboo so the probe verifies the plugin's read paths are
        producing what the plugin assumes.

        Paginates recent builds until target_parseable parseable entries
        are accumulated or max_walk total builds have been examined. The
        plugin's TagBuilderService hardcodes maxResults=10 with no
        pagination, so any sandbox / dev / partial run in the recent 10
        reduces the effective baseline pool. The mirror walks further so
        you can see what would actually be available with proper paging.

        Returns a digest with the plugin's pick (current scoring) AND the
        proposed two-tier pick side by side, plus a divergence report
        between the two variable-fetch shapes the plugin uses.
        """
        page_size = 10
        start_index = 0
        pages_fetched = 0
        ranked: list[dict] = []
        parseable: list[dict] = []
        divergences: list[dict] = []

        outer_break = False
        while not outer_break and len(parseable) < target_parseable and len(ranked) < max_walk:
            page_label = f"p{start_index:04d}"
            self._get(
                name=f"baseline_recent_{plan_key}_{page_label}",
                description=(f"getRecentResults({plan_key}) page "
                             f"start_index={start_index} size={page_size}"),
                path=(f"/rest/api/latest/result/{plan_key}"
                      f"?max-results={page_size}&start-index={start_index}"
                      f"&expand=results.result.stages.stage.results.result,"
                      f"results.result.variables.variable"),
                category="baseline_mirror",
            )
            page_blob = _read_raw_body(
                self.raw_dir / f"baseline_recent_{plan_key}_{page_label}.json"
            )
            pages_fetched += 1
            if not page_blob:
                if pages_fetched == 1:
                    return {
                        "plan_key": plan_key, "variable_name": variable_name,
                        "error": "could not fetch recent builds",
                        "walked": [], "parseable": [],
                        "plugin_pick": None, "two_tier_pick": None,
                        "divergences": [], "pages_fetched": pages_fetched,
                    }
                break
            page_builds = (page_blob.get("results") or {}).get("result") or []
            if not page_builds:
                break

            for build in page_builds:
                entry, divergence = self._process_build_for_mirror(
                    plan_key, build, variable_name,
                )
                ranked.append(entry)
                if divergence:
                    divergences.append(divergence)
                if "plugin_score" in entry:
                    parseable.append(entry)
                if len(parseable) >= target_parseable or len(ranked) >= max_walk:
                    outer_break = True
                    break

            if len(page_builds) < page_size:
                break
            start_index += len(page_builds)

        # ---- Two ranking views (only over parseable entries) ----
        plugin_ranked = sorted(parseable, key=lambda r: r["plugin_score"], reverse=True)
        plugin_pick = plugin_ranked[0] if plugin_ranked else None

        def _two_tier_key(r: dict) -> tuple:
            return (
                1 if r["all_release"] else 0,
                -r["failed_tests"],
                r["success_tests"],
                r["release_count"],
            )
        two_tier_ranked = sorted(parseable, key=_two_tier_key, reverse=True)
        two_tier_pick = two_tier_ranked[0] if two_tier_ranked else None

        # ---- Console output ----
        print(f"\n--- Baseline-detection mirror ({plan_key}, var='{variable_name}') ---")
        print(f"  {len(parseable)} parseable build(s) found across "
              f"{len(ranked)} walked ({pages_fetched} page(s) of {page_size}).")
        if len(parseable) < target_parseable:
            print(f"  ⚠ Walked {len(ranked)} builds (cap={max_walk}) but only "
                  f"{len(parseable)} are parseable. Plugin's hardcoded "
                  f"maxResults=10 + no pagination would see even fewer.")
        if divergences:
            print(f"  ⚠ {len(divergences)} build(s) showed embedded vs per-build "
                  f"variable divergence — see raw/baseline_mirror_digest.json.")
        else:
            print(f"  ✓ Embedded and per-build variable shapes agree on every walked build.")

        empty_keys = sum(1 for r in ranked if r["raw_result_key_was_empty"])
        if empty_keys:
            print(f"  ⚠ {empty_keys} build(s) returned empty buildResultKey "
                  f"(plugin's `.ifBlank` fallback hides this).")

        if parseable:
            print(f"\n  {'Build':>7}  {'state':<11} {'rel/total':>9} "
                  f"{'failTst':>7} {'okTst':>6} {'failStg':>7} {'okStg':>5} "
                  f"{'plugin':>7} {'tier':>4}")
            print(f"  {'-'*7}  {'-'*11} {'-'*9} {'-'*7} {'-'*6} {'-'*7} {'-'*5} "
                  f"{'-'*7} {'-'*4}")
            for r in two_tier_ranked:
                tier = "1" if r["all_release"] else "2"
                print(f"  {('#' + str(r['build_num'])):>7}  {r['state']:<11} "
                      f"{r['release_count']:>4}/{r['total_services']:<4} "
                      f"{r['failed_tests']:>7} {r['success_tests']:>6} "
                      f"{r['failed_stages']:>7} {r['success_stages']:>5} "
                      f"{r['plugin_score']:>7} {tier:>4}")

        # Pick comparison + full dockerTagsAsJson for the picks
        print()
        if plugin_pick and two_tier_pick:
            if plugin_pick["build_num"] == two_tier_pick["build_num"]:
                print(f"  Both algorithms agree: build #{plugin_pick['build_num']} "
                      f"({plugin_pick['result_key']}) is the baseline.")
                self._print_full_dockertags("Baseline", plugin_pick)
            else:
                print(f"  ⚠ Algorithms DISAGREE:")
                print(f"    Plugin (current) picks: build #{plugin_pick['build_num']} "
                      f"(score={plugin_pick['plugin_score']}, "
                      f"{plugin_pick['release_count']}/{plugin_pick['total_services']} "
                      f"release tags, {plugin_pick['failed_tests']} failed tests)")
                self._print_full_dockertags("Plugin pick", plugin_pick)
                print()
                print(f"    Two-tier (proposed) picks: build #{two_tier_pick['build_num']} "
                      f"(tier={'1' if two_tier_pick['all_release'] else '2'}, "
                      f"{two_tier_pick['release_count']}/{two_tier_pick['total_services']} "
                      f"release tags, {two_tier_pick['failed_tests']} failed tests)")
                self._print_full_dockertags("Two-tier pick", two_tier_pick)
                print(f"\n    → Evidence the plugin's failed-stage penalty may be "
                      f"misranking. Review before adopting either.")
        elif two_tier_pick:
            print(f"  Two-tier picks build #{two_tier_pick['build_num']} as baseline.")
            self._print_full_dockertags("Baseline", two_tier_pick)
        else:
            print(f"  No build qualifies as a baseline (no parseable '{variable_name}').")

        print(f"\n  Full per-build data: raw/baseline_mirror_digest.json")

        digest = {
            "plan_key": plan_key,
            "variable_name": variable_name,
            "target_parseable": target_parseable,
            "max_walk": max_walk,
            "pages_fetched": pages_fetched,
            "walked": ranked,
            "parseable": parseable,
            "plugin_pick": plugin_pick,
            "two_tier_pick": two_tier_pick,
            "divergences": divergences,
        }
        (self.raw_dir / "baseline_mirror_digest.json").write_text(
            json.dumps(digest, indent=2, default=str), encoding="utf-8",
        )
        return digest

    # -- variable scope diagnosis (plan / global / recent-builds) -------------

    def diagnose_variable_scopes(
        self,
        plan_key: str,
        variable_name: str,
        recent_builds_have_it: bool,
    ) -> dict:
        """Probe multiple Bamboo variable scopes to determine where a
        variable is declared (plan, global, or runtime-injected only).

        Bamboo has multiple variable scopes:
          - Plan-level: declared on the plan, returned by
            /plan/{key}?expand=variableContext
          - Global: instance-wide, returned by /admin/variable
            (admin-gated, often 401/403 for non-admin PATs)
          - Manual override at trigger time: NOT declared anywhere; just
            recorded on the build's applied variables when passed via
            /queue/{key}?bamboo.variable.X=Y

        This method checks all three (the third via the mirror's
        recent-builds flag passed in by the caller) and prints a
        consolidated diagnosis.
        """
        diagnosis = {
            "variable_name": variable_name,
            "in_plan_context": False,
            "plan_keys_observed": [],
            "in_global": None,            # None = not checked / unknown
            "global_check_status": None,
            "global_keys_observed": [],
            "in_recent_builds": recent_builds_have_it,
        }

        # Plan-level (re-read the variableContext fetch already issued upstream)
        plan_blob = _read_raw_body(self.raw_dir / "write_test_plan_variables.json")
        if plan_blob:
            plan_vars = (plan_blob.get("variableContext") or {}).get("variable") or []
            diagnosis["plan_keys_observed"] = [v.get("key") for v in plan_vars if v.get("key")]
            diagnosis["in_plan_context"] = any(
                (v.get("key") or "").lower() == variable_name.lower()
                for v in plan_vars
            )

        # Global variables — admin-gated. Try the documented endpoint;
        # surface 401/403 as "unknown" rather than "not declared".
        global_result = self._get(
            name="scope_global_vars",
            description="Global variables (/admin/variable — admin-gated)",
            path="/rest/api/latest/admin/variable",
            category="variable_scope",
            notes=[
                "Returns 401/403 for non-admin PATs — that's expected.",
                "Used to determine whether a variable absent from plan-level "
                "is actually globally declared.",
            ],
        )
        diagnosis["global_check_status"] = global_result.status

        if global_result.ok:
            global_blob = _read_raw_body(self.raw_dir / "scope_global_vars.json")
            if global_blob:
                # Bamboo's global-variable response varies by version.
                # Common shapes: {"variable": [{key, value}]} or a list.
                gvars = (
                    global_blob.get("variable")
                    or global_blob.get("variables")
                    or global_blob.get("globalVariables")
                    or []
                )
                if isinstance(gvars, dict):
                    gvars = gvars.get("variable") or gvars.get("globalVariable") or []
                if isinstance(gvars, list):
                    diagnosis["global_keys_observed"] = [
                        v.get("key") or v.get("name")
                        for v in gvars
                        if isinstance(v, dict) and (v.get("key") or v.get("name"))
                    ]
                    diagnosis["in_global"] = any(
                        ((v.get("key") or v.get("name") or "").lower()
                         == variable_name.lower())
                        for v in gvars if isinstance(v, dict)
                    )
        elif global_result.status in (401, 403):
            diagnosis["in_global"] = None  # explicitly unknown
        else:
            diagnosis["in_global"] = None

        # ---- Console output ----
        print(f"\n--- '{variable_name}' declaration scope ---")
        if diagnosis["in_plan_context"]:
            print(f"  ✓ Plan-level: declared in {plan_key}'s variableContext")
        else:
            print(f"  ✗ Plan-level: not in {plan_key}'s variableContext "
                  f"({len(diagnosis['plan_keys_observed'])} other vars declared)")

        if diagnosis["in_global"] is True:
            print(f"  ✓ Global: declared at instance-level "
                  f"({len(diagnosis['global_keys_observed'])} global vars total)")
        elif diagnosis["in_global"] is False:
            print(f"  ✗ Global: not declared "
                  f"({len(diagnosis['global_keys_observed'])} other global vars seen)")
        else:
            status = diagnosis["global_check_status"]
            if status in (401, 403):
                print(f"  ? Global: PAT lacks admin scope (got {status}) — "
                      f"can't enumerate global vars")
            elif status == 404:
                print(f"  ? Global: /admin/variable not found on this Bamboo "
                      f"(got 404) — try a different endpoint or accept the gap")
            else:
                print(f"  ? Global: check returned {status} — see "
                      f"raw/scope_global_vars.json")

        if diagnosis["in_recent_builds"]:
            print(f"  ✓ Runtime: applied on recent builds (mirror confirmed)")
        else:
            print(f"  ✗ Runtime: not seen on any recent build")

        # ---- Verdict ----
        declared_anywhere = (
            diagnosis["in_plan_context"]
            or diagnosis["in_global"] is True
            or diagnosis["in_recent_builds"]
        )
        if not declared_anywhere and diagnosis["in_global"] is None:
            # Global is unknown AND we have no other evidence
            print(f"\n  ⚠ Cannot confirm '{variable_name}' is declared anywhere we can see. "
                  f"Global scope is unreadable (admin-only) and the variable doesn't "
                  f"appear at plan-level or in recent builds. Trigger may no-op if the "
                  f"build script ignores undeclared vars.")
        elif not declared_anywhere:
            print(f"\n  ⚠ '{variable_name}' is not declared in any scope we can read AND "
                  f"never appeared on a recent build. Verify the variable name spelling "
                  f"and the plan key.")
        elif diagnosis["in_recent_builds"] and not (
            diagnosis["in_plan_context"] or diagnosis["in_global"] is True
        ):
            print(f"\n  → '{variable_name}' is injected at trigger time only "
                  f"(not declared at plan or global scope). Bamboo records it on the "
                  f"build regardless. Whether your build script consumes it depends on "
                  f"the script — but for THIS probe (verifying that our value lands as "
                  f"the build's applied variable), it works correctly.")
        else:
            print(f"\n  → '{variable_name}' is properly declared. Trigger will pass "
                  f"the value cleanly.")

        return diagnosis

    def run_write_test(
        self,
        plan_key: str,
        variable_name: str,
        variable_value: str,
        let_build_finish: bool,
    ) -> int:
        """Verify queue-trigger variable encoding by triggering ONE build
        with a sentinel value and confirming the value landed on the build.

        Returns 0 on confirmed match, 1 on mismatch / failure, 2 on user abort.
        Only this mode uses a write User-Agent; read probes keep `(read-only)`.
        """
        # Switch User-Agent so admins can distinguish write-test traffic.
        self.session.headers["User-Agent"] = (
            "WorkflowOrchestrator-BambooProbe/1.0 (write-test)"
        )

        print("[probe] write-test mode\n")
        print(f"=== PHASE A — read-only baseline for {plan_key} ===\n")

        # A.1: declared plan variableContext (not part of mirror — distinct
        # information about what the plan SHOULD have, including isPassword).
        self._get(
            name="write_test_plan_variables",
            description=f"Declared variables for {plan_key} (variableContext)",
            path=f"/rest/api/latest/plan/{plan_key}?expand=variableContext",
            category="write_test",
        )
        declared = _read_raw_body(self.raw_dir / "write_test_plan_variables.json")
        print("--- Declared plan variables ---")
        ctx_list = []
        if declared:
            ctx_list = (declared.get("variableContext") or {}).get("variable") or []
            if not ctx_list:
                print(f"  (no variableContext for {plan_key} — plan may have no "
                      f"vars, OR PAT lacks read perm)")
            for v in ctx_list:
                pw = " [PASSWORD]" if v.get("isPassword") else ""
                vt = f" ({v.get('variableType', '?')})"
                print(f"  {v.get('key', '?')} = "
                      f"{'***' if v.get('isPassword') else v.get('value', '?')}"
                      f"{pw}{vt}")
        else:
            print(f"  (failed to read declared variables — see raw/write_test_plan_variables.json)")

        # A.2: replay the automation plugin's baseline detection. The mirror
        # walks recent builds, fetches per-build variables (the path the
        # plugin uses for its case-insensitive lookup), surfaces any
        # divergence between the embedded and per-build variable shapes,
        # and shows BOTH the plugin's current scoring AND the proposed
        # two-tier scoring side by side.
        digest = self.mirror_baseline_detection(
            plan_key=plan_key,
            variable_name=variable_name,
        )

        # A.3: wider-scope diagnosis. The plan-level variableContext at A.1
        # only covers ONE of three scopes Bamboo variables can live in.
        # diagnose_variable_scopes additionally checks /admin/variable
        # (global) and uses the mirror's "did recent builds carry this
        # variable" signal to determine whether the variable is
        # runtime-injected. Replaces the old plan-only warning that
        # mistook "absent from plan-level" for "absent everywhere".
        recent_builds_have_it = bool(digest.get("parseable"))
        self.diagnose_variable_scopes(
            plan_key=plan_key,
            variable_name=variable_name,
            recent_builds_have_it=recent_builds_have_it,
        )

        # === PHASE B — confirmation gate ===
        print("\n=== PHASE B — confirmation gate ===\n")
        form_pairs = [
            (f"bamboo.variable.{variable_name}", variable_value),
            ("executeAllStages", "true"),
        ]
        encoded = urllib.parse.urlencode(form_pairs)
        post_path = f"/rest/api/latest/queue/{plan_key}"
        full_url = f"{self.base}{post_path}"

        print("About to send the following request:")
        print()
        print(f"  Method:  POST")
        print(f"  URL:     {full_url}")
        print(f"  Headers: Content-Type: application/x-www-form-urlencoded")
        print(f"           Authorization: Bearer <redacted>")
        print(f"           User-Agent: WorkflowOrchestrator-BambooProbe/1.0 (write-test)")
        print(f"  Body:    {encoded}")
        print()
        print(f"  Sentinel: {variable_name} = {variable_value}")
        print(f"  This will queue ONE real build on your CI.")
        if let_build_finish:
            print(f"  The probe will let it run to completion (~suite duration) "
                  f"before reporting.")
        else:
            print(f"  The probe will not cancel the build but will exit after "
                  f"capturing applied variables.")
        print()
        try:
            answer = input("Proceed? Type 'y' (anything else aborts): ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            print("\n[probe] aborted at gate.")
            return 2
        if answer != "y":
            print("[probe] aborted at gate.")
            return 2

        # === PHASE C — single trigger ===
        print("\n=== PHASE C — triggering build ===\n")
        post_result = self._post_form(
            name="write_test_post",
            description=f"Trigger {plan_key} with form-encoded {variable_name} sentinel",
            path=post_path,
            form_body=encoded,
            category="write_test",
        )
        print(f"  POST → {post_result.status} in {post_result.elapsed_ms}ms")

        post_raw = _read_raw_body(self.raw_dir / "write_test_post.json")
        body = (post_raw or {}).get("raw_body")  # nested under raw_body in our payload
        # _post_form writes {"result": ..., "request_body": ..., "raw_body": ...}
        # so re-read the raw response specifically:
        post_blob = json.loads(
            (self.raw_dir / "write_test_post.json").read_text(encoding="utf-8")
        )
        body = post_blob.get("raw_body")

        if not post_result.ok:
            # Surface Bamboo's response body so the user can tell what
            # kind of failure this is (CSRF rejection, permission denied,
            # plan locked, etc.) instead of digging through raw/.
            preview = post_result.payload_preview
            if isinstance(preview, (dict, list)):
                preview_text = json.dumps(preview, indent=2)[:800]
            else:
                preview_text = str(preview or "")[:800]
            print(f"  [probe] non-success status {post_result.status}.")
            if preview_text.strip():
                print(f"  Bamboo response body:")
                for line in preview_text.splitlines():
                    print(f"    {line}")
            if post_result.status == 403:
                print(f"\n  Disambiguation guide for 403:")
                print(f"    • If body mentions 'XSRF' / 'token' / 'cross-site' → "
                      f"X-Atlassian-Token header issue (probe now sends it; "
                      f"if still failing, your Bamboo may require an additional "
                      f"header).")
                print(f"    • If body mentions 'Permission' / 'not authorised' / "
                      f"'BUILD' → your PAT lacks BUILD permission on this plan. "
                      f"Read perms ≠ trigger perms in Bamboo.")
                print(f"    • If body is HTML (login page) → PAT auth disabled "
                      f"on your Bamboo's API surface; you'd need session cookies.")
                print(f"  Try triggering the same plan via Bamboo's Web UI with "
                      f"the same account. If UI works → permission-aware fix needed; "
                      f"if UI also fails → genuine permission gap.")
            print(f"  Full response: raw/write_test_post.json. Aborting verification.")
            return 1

        new_result_key = None
        if isinstance(body, dict):
            new_result_key = (
                body.get("buildResultKey")
                or (body.get("triggerReason") or {}).get("buildResultKey")
            )

        if not new_result_key:
            print("  [probe] could not extract buildResultKey from response. "
                  "See raw/write_test_post.json.")
            return 1
        print(f"  Build result key: {new_result_key}")

        # === PHASE D — verify ===
        print("\n=== PHASE D — verifying applied variables ===\n")
        timeout_s = 1800 if let_build_finish else 300
        poll_interval_s = 15
        deadline = time.time() + timeout_s
        last_lifecycle: Optional[str] = None
        attempt = 0
        applied_value: Optional[str] = None
        terminal = False

        while time.time() < deadline:
            attempt += 1
            self._get(
                name=f"write_test_verify_{attempt:02d}",
                description=f"Applied variables for new build {new_result_key} (poll #{attempt})",
                path=f"/rest/api/latest/result/{new_result_key}?expand=variables",
                category="write_test",
            )
            verify_blob = _read_raw_body(
                self.raw_dir / f"write_test_verify_{attempt:02d}.json"
            )
            if verify_blob is None:
                print(f"  [poll #{attempt}] could not parse verify response, retrying...")
                time.sleep(poll_interval_s)
                continue

            lifecycle = verify_blob.get("lifeCycleState", "?")
            state = verify_blob.get("state", "?")
            if lifecycle != last_lifecycle:
                print(f"  [poll #{attempt}] {new_result_key}: {lifecycle}/{state}")
                last_lifecycle = lifecycle

            applied = (verify_blob.get("variables") or {}).get("variable") or []
            if applied:
                applied_value = next(
                    (v.get("value") for v in applied if v.get("name") == variable_name),
                    None,
                )
                if applied_value is not None:
                    if attempt == 1 or attempt % 4 == 0:
                        print(f"  [poll #{attempt}] applied {variable_name}: "
                              f"{applied_value!r}")

            terminal = lifecycle in ("Finished", "NotBuilt")

            if applied_value == variable_value and (terminal or not let_build_finish):
                print()
                print("  >>> MATCH — form-encoded body works on this Bamboo.")
                print("  >>> PR 2 fix shape (form-encoded body) confirmed end-to-end.")
                return 0
            if terminal:
                print()
                if applied_value == variable_value:
                    print("  >>> MATCH (post-completion) — fix shape confirmed.")
                    return 0
                else:
                    print("  >>> MISMATCH — sentinel did NOT land on the completed build.")
                    print(f"      Expected: {variable_value!r}")
                    print(f"      Applied:  {applied_value!r}")
                    print("      Form-encoded body shape may also be wrong, OR the "
                          "build script rewrote the variable. Investigate before fixing.")
                    return 1

            time.sleep(poll_interval_s)

        print(f"\n  [probe] timeout after {timeout_s}s — build did not reach a terminal state.")
        print(f"  Last seen applied {variable_name}: {applied_value!r}")
        print(f"  Re-run with the same buildResultKey to continue verification:")
        print(f"    GET /rest/api/latest/result/{new_result_key}?expand=variables")
        return 1

    # -- skip helper for endpoints that need missing CLI args -----------------

    def _skip(self, name: str, description: str, category: str) -> None:
        """Record a 'not run' entry so the summary still shows the gap."""
        result = ProbeResult(
            name=name, description=description, method="GET", path="(skipped)",
            category=category, status=0, ok=False, payload_kind="empty",
            notes=["Skipped — required CLI argument not provided"],
        )
        # Don't write a raw file for skipped endpoints — there's no payload.
        self.results.append(result)

    # -- summary writer -------------------------------------------------------

    def write_summary(self, args_used: dict[str, Any]) -> None:
        summary_path = self.results_dir / "summary.md"
        lines: list[str] = []
        lines.append(f"# Bamboo probe results — {self.base}")
        lines.append("")
        lines.append(f"- **Run at:** {time.strftime('%Y-%m-%dT%H:%M:%S%z')}")
        lines.append(f"- **Args:** `{json.dumps(args_used)}`")
        lines.append(f"- **Total endpoints probed:** {len(self.results)}")
        lines.append(f"- **Successful (2xx):** {sum(1 for r in self.results if r.ok)}")
        lines.append(f"- **Failed (4xx/5xx/error):** "
                     f"{sum(1 for r in self.results if not r.ok and r.path != '(skipped)')}")
        lines.append(f"- **Skipped (missing CLI arg):** "
                     f"{sum(1 for r in self.results if r.path == '(skipped)')}")
        lines.append("")
        version_note = self._format_version_note()
        if version_note:
            lines.append("## Version detection")
            lines.append("")
            lines.append(version_note)
            lines.append("")

        # Order: version (context) → existing (correctness) →
        #        feature (new surfaces) → swap (better paths) → internal.
        # Bamboo v0 uses only version/existing/feature; swap/internal reserved
        # for future probe versions after we know the server version.
        for category in ("version", "existing", "swap", "feature", "internal"):
            cat_results = [r for r in self.results if r.category == category]
            if not cat_results:
                continue
            lines.append(f"## {category.title()} endpoints")
            lines.append("")
            lines.append("| Status | Endpoint | Description | Time | Notes |")
            lines.append("|---|---|---|---|---|")
            for r in cat_results:
                if r.path == "(skipped)":
                    status_label = "SKIP"
                elif r.ok:
                    status_label = f"OK {r.status}"
                else:
                    status_label = f"FAIL {r.status or 'ERR'}"
                notes_str = "; ".join(r.notes) if r.notes else ""
                if r.error:
                    notes_str = (notes_str + " · " + r.error).strip(" ·")
                desc = r.description.replace("|", "\\|")
                notes_str = notes_str.replace("|", "\\|")
                method_path = (
                    f"`(skipped)`" if r.path == "(skipped)"
                    else f"`{r.method} {r.path}`"
                )
                lines.append(
                    f"| {status_label} | {method_path} | {desc} | {r.elapsed_ms}ms | {notes_str} |"
                )
            lines.append("")

        # Inventory of writes the plugin issues today — never called by probe.
        lines.append("## Writes inventoried but NOT called (read-only probe)")
        lines.append("")
        lines.append("| Method | Endpoint | Plugin caller |")
        lines.append("|---|---|---|")
        for method, path, caller in _WRITES_INVENTORY:
            lines.append(f"| `{method}` | `{path}` | {caller} |")
        lines.append("")
        lines.append(
            "_These four mutating endpoints in `BambooApiClient.kt` are the only "
            "writes the plugin makes against Bamboo. The probe never invokes "
            "them — User-Agent ends in `(read-only)` for audit-log proof._"
        )
        lines.append("")

        lines.append("## Raw responses")
        lines.append("")
        lines.append("Each endpoint's full response (parsed JSON or text snippet) is saved to `raw/<name>.json`.")
        lines.append("Skipped endpoints have no raw file. Plain-text endpoints (build log) capture only a 500-byte preview + total size in notes — the full body would explode raw/* sizes.")
        lines.append("")

        summary_path.write_text("\n".join(lines), encoding="utf-8")
        print(f"\n[probe] wrote summary -> {summary_path}")
        print(f"[probe] wrote {sum(1 for r in self.results if r.path != '(skipped)')} raw payloads -> {self.raw_dir}")

    def _format_version_note(self) -> str:
        info = next((r for r in self.results if r.name == "info"), None)
        if not info or not info.ok:
            return "_`/rest/api/latest/info` did not respond — version unknown._"
        raw_file = self.raw_dir / "info.json"
        try:
            data = json.loads(raw_file.read_text(encoding="utf-8")).get("raw_body") or {}
        except Exception:
            return "_`/rest/api/latest/info` response could not be parsed._"
        # Edition heuristic: Bamboo Server SKU was discontinued for new sales
        # in 2024; on-prem deployments are now Data Center exclusively. We
        # surface what /info reports verbatim and let the recommendations doc
        # make the call once the user confirms.
        return (
            f"- **version:** `{data.get('version')}`\n"
            f"- **buildNumber:** `{data.get('buildNumber')}`\n"
            f"- **buildDate:** `{data.get('buildDate')}`\n"
            f"- **state:** `{data.get('state')}`\n"
            f"- **edition:** _Bamboo's `/info` does not report an edition flag; "
            f"on-prem Bamboo is Data Center only since 2024 (Server SKU EOL)._\n"
        )

    # -- discover digest writer ----------------------------------------------

    def _write_discover_digest(
        self,
        project_keys: list[str],
        plan_specs: list[dict],
        sample: Optional[dict],
        scope_hint: Optional[str] = None,
    ) -> None:
        """Render Result_N/discover.md scoped to the user's actual permissions.

        Lists projects, candidate plans (with recent build state, branch
        counts, dockerTagsAsJson hint to disambiguate Build-tab vs
        Automation-tab plans), and a copy-paste full-sweep command seeded
        from the most recent build.

        `scope_hint` (when set) is rendered at the top so the reader can tell
        whether the digest is from a scoped or unscoped walk."""
        lines: list[str] = ["# Bamboo discovery — pick values for the full sweep", ""]

        info = _read_raw_body(self.raw_dir / "info.json") or {}
        cu = _read_raw_body(self.raw_dir / "current_user.json") or {}
        version = info.get("version", "?") if isinstance(info, dict) else "?"
        build_no = info.get("buildNumber", "?") if isinstance(info, dict) else "?"
        full_name = cu.get("fullName", "") if isinstance(cu, dict) else ""
        login = cu.get("name", "") if isinstance(cu, dict) else ""
        if full_name or login:
            lines.append(f"**You are**: {full_name} (`{login}`)")
        lines.append(f"**Bamboo**: `{version}` (build `{build_no}`)")
        if scope_hint:
            lines.append(f"**Scope**: {scope_hint}")
        lines.append("")

        projects_body = _read_raw_body(self.raw_dir / "discover_projects.json") or {}
        all_projects: list[Any] = []
        if isinstance(projects_body, dict):
            inner = projects_body.get("projects") or {}
            if isinstance(inner, dict):
                all_projects = inner.get("project") or []
        if all_projects:
            shown = min(len(all_projects), 5)
            lines.append(f"## Projects you can see ({shown} of {len(all_projects)})")
            lines.append("")
            lines.append("| Key | Name |")
            lines.append("|---|---|")
            for p in all_projects[:5]:
                if isinstance(p, dict):
                    pk = str(p.get("key", ""))
                    pn = str(p.get("name", "")).replace("|", "\\|")
                    lines.append(f"| `{pk}` | {pn} |")
            lines.append("")

        if plan_specs:
            lines.append(f"## Candidate plans ({len(plan_specs)} sampled)")
            lines.append("")
            lines.append(
                "> **Tip:** plans whose recent builds expose `dockerTagsAsJson` "
                "are likely **automation suite** plans (Automation tab — see "
                "`ConflictDetectorService` / `TagBuilderService`). Plans "
                "without are usually **service CI** plans (Build tab — auto-"
                "detected from VCS via `BambooService.autoDetectPlan`)."
            )
            lines.append("")
            lines.append(
                "| Plan key | Project | Plan name | Latest build | Branches "
                "| dockerTagsAsJson? |"
            )
            lines.append("|---|---|---|---|---|---|")
            for spec in plan_specs:
                plkey = spec["key"]
                project = spec.get("project_key", "")
                plan_name = str(spec.get("name", "")).replace("|", "\\|")[:40]
                latest_label = "—"
                has_docker = "—"
                recent_body = _read_raw_body(
                    self.raw_dir / f"discover_plan_{plkey}_recent.json"
                ) or {}
                if isinstance(recent_body, dict):
                    inner = recent_body.get("results") or {}
                    if isinstance(inner, dict):
                        rl = inner.get("result") or []
                        if rl and isinstance(rl[0], dict):
                            r0 = rl[0]
                            latest_label = (
                                f"#{r0.get('buildNumber', '?')} "
                                f"{r0.get('state', '?')}"
                            )
                            vars_block = r0.get("variables")
                            if isinstance(vars_block, dict):
                                vlist = vars_block.get("variable") or []
                                names = {
                                    v.get("name") for v in vlist
                                    if isinstance(v, dict)
                                }
                                has_docker = (
                                    "yes" if "dockerTagsAsJson" in names else "no"
                                )
                branch_count = "—"
                branches_body = _read_raw_body(
                    self.raw_dir / f"discover_plan_{plkey}_branches.json"
                ) or {}
                if isinstance(branches_body, dict):
                    inner = branches_body.get("branches") or {}
                    if isinstance(inner, dict):
                        size = inner.get("size")
                        if isinstance(size, int):
                            branch_count = str(size)
                lines.append(
                    f"| `{plkey}` | `{project}` | {plan_name} | "
                    f"{latest_label} | {branch_count} | {has_docker} |"
                )
            lines.append("")

            # Per-plan branches detail — surface each branch's *branch plan
            # key* so the user can pass a specific branch's key as --plan-key
            # on a follow-up discover or full-sweep run. Bamboo's master plan
            # key only returns master-branch results; other branches live
            # under their own branch plan keys.
            for spec in plan_specs:
                plkey = spec["key"]
                branches_body = _read_raw_body(
                    self.raw_dir / f"discover_plan_{plkey}_branches.json"
                ) or {}
                branch_rows: list[tuple[str, str, Any]] = []
                if isinstance(branches_body, dict):
                    inner = branches_body.get("branches") or {}
                    if isinstance(inner, dict):
                        for b in (inner.get("branch") or []):
                            if isinstance(b, dict):
                                branch_rows.append((
                                    str(b.get("key", "")),
                                    str(b.get("shortName", "")),
                                    b.get("enabled"),
                                ))
                if branch_rows:
                    lines.append(f"### Branches under `{plkey}` ({len(branch_rows)})")
                    lines.append("")
                    lines.append(
                        "> In Bamboo each Git branch has its own **branch "
                        "plan key** — separate from the master plan key. "
                        "To audit a specific branch's builds (e.g., "
                        "`develop`), pass that branch's key below as "
                        "`--plan-key`, NOT the master plan key. The plugin "
                        "calls `/result/{plan}/branch/{branch}/latest` "
                        "internally; the probe needs the branch plan key "
                        "directly to walk that branch's recent results."
                    )
                    lines.append("")
                    lines.append("| Branch | Branch plan key | Enabled |")
                    lines.append("|---|---|---|")
                    for bkey, bshort, benabled in branch_rows[:10]:
                        if benabled is True:
                            enabled_str = "yes"
                        elif benabled is False:
                            enabled_str = "no"
                        else:
                            enabled_str = "—"
                        lines.append(
                            f"| `{bshort}` | `{bkey}` | {enabled_str} |"
                        )
                    lines.append("")

        if sample and sample.get("plan_result_key"):
            lines.append(
                f"## Sample IDs from the most recent run on "
                f"`{sample.get('plan_key', '?')}`"
            )
            lines.append("")
            lines.append(
                f"- **Plan-level result key**: `{sample.get('plan_result_key', '—')}`"
            )
            jrk = sample.get("job_result_key")
            if jrk:
                lines.append(
                    f"- **Job-level result key**: `{jrk}` ← use this for "
                    "`--result-key` (yields a real ~30 KB build log; "
                    "plan-level keys give a 101-byte stub)"
                )
            else:
                lines.append(
                    "- _No job-level result key found in stages — using the "
                    "plan-level key will yield only a tiny log stub. Pick a "
                    "plan with a different stage layout if you need real log "
                    "content._"
                )
            br = sample.get("branch")
            if br:
                lines.append(f"- **Branch**: `{br}`")
            else:
                lines.append(
                    "- **Branch**: _not reported by Bamboo. The "
                    "`planBranchName` field is populated only for builds "
                    "on **branch plans** (the numbered keys in the "
                    "'Branches under' table); builds on the **master "
                    "plan** itself omit it. This does NOT mean the build "
                    "was on a different branch — it means the master plan "
                    "is tracking whatever Git branch your team configured "
                    "(often `develop` or `main`), and Bamboo's REST API "
                    "does not expose that mapping. Pass `--branch-name "
                    "<whatever-your-master-plan-tracks>` to the full "
                    "sweep and keep the master plan key as `--plan-key` "
                    "— that's the right combination to audit the master "
                    "plan's builds. Use a numbered branch plan key from "
                    "the 'Branches under' table only if you want to "
                    "audit a different (non-master) branch instead._"
                )
            sha = sample.get("commit_sha")
            if sha:
                lines.append(f"- **Latest commit SHA**: `{sha}`")
            lines.append("")
        else:
            lines.append("## Sample IDs")
            lines.append("")
            lines.append(
                "_No recent builds were found on the candidate plans. Fill "
                "in `--result-key` and `--commit-sha` manually from the "
                "Bamboo UI — open the plan in the browser, click into a "
                "recent build, and grab the result key from the URL._"
            )
            lines.append("")

        suggested_plan = (sample or {}).get("plan_key") or (
            plan_specs[0]["key"] if plan_specs else None
        )
        suggested_proj = (sample or {}).get("project_key") or (
            (plan_specs[0].get("project_key") if plan_specs else None)
            or (suggested_plan.split("-", 1)[0] if suggested_plan and "-" in suggested_plan else None)
        )
        suggested_rk = (
            (sample or {}).get("job_result_key")
            or (sample or {}).get("plan_result_key")
        )
        suggested_branch = (sample or {}).get("branch")
        suggested_sha = (sample or {}).get("commit_sha")

        lines.append("---")
        lines.append("")
        lines.append("## Suggested full-sweep command")
        lines.append("")
        if not suggested_plan:
            lines.append(
                "**Discovery found no plans.** Most common causes: (a) `--project-key` "
                "got a plan key by mistake — strip everything after the first dash and "
                "re-run; (b) PAT lacks project-level read but can list individual plans "
                "— pass `--plan-key PROJ-PLAN` directly to the next discover run; "
                "(c) the project genuinely has no plans visible to your PAT. No "
                "command suggested below — fix scope and re-run."
            )
            lines.append("")
            return "\n".join(lines) + "\n"
        lines.append(
            "_Seeded with the most-recent values from your work above. "
            "Replace any with whatever you'd rather audit — typically you'll "
            "pick **either** a Build-tab service-CI plan **or** an "
            "Automation-tab suite plan based on which use case you're "
            "validating. Run twice if you want both._"
        )
        lines.append("")
        # Fall through with suggested_plan set; remaining vars use placeholders only when sample empty
        suggested_proj = suggested_proj or "<project-key>"
        suggested_rk = suggested_rk or "<result-key — open a build in Bamboo UI>"
        suggested_branch = suggested_branch or "<your-branch>"
        suggested_sha = suggested_sha or "<commit-sha>"
        if suggested_branch == "<your-branch>":
            lines.append(
                "> **Branch placeholder note** — Bamboo did not return "
                "`planBranchName` for this sample, which is normal for "
                "builds on the **master plan**. The job key and commit "
                "SHA above ARE from your master plan's most-recent build "
                "(which is whatever Git branch your master plan tracks — "
                "often `develop` or `main`); only the branch *name* "
                "needs filling in. Replace `<your-branch>` below with "
                "your master plan's tracked branch and proceed — no "
                "need to switch to a numbered branch plan key. Use a "
                "numbered branch plan key from the 'Branches under' "
                "table above ONLY if you want to audit a non-master "
                "branch (e.g., `release`) instead."
            )
            lines.append("")
        lines.append("Unix shell / PowerShell:")
        lines.append("")
        lines.append("```bash")
        lines.append(
            f"python probe_bamboo.py --url <YOUR_BAMBOO_URL> --token \"$BAMBOO_PAT\" \\\n"
            f"    --plan-key {suggested_plan} \\\n"
            f"    --result-key {suggested_rk} \\\n"
            f"    --project-key {suggested_proj} \\\n"
            f"    --branch-name {suggested_branch} \\\n"
            f"    --commit-sha {suggested_sha}"
        )
        lines.append("```")
        lines.append("")
        lines.append("Windows `cmd`:")
        lines.append("")
        lines.append("```bat")
        lines.append(
            f"python probe_bamboo.py --url <YOUR_BAMBOO_URL> --token \"%BAMBOO_PAT%\" ^\n"
            f"    --plan-key {suggested_plan} ^\n"
            f"    --result-key {suggested_rk} ^\n"
            f"    --project-key {suggested_proj} ^\n"
            f"    --branch-name {suggested_branch} ^\n"
            f"    --commit-sha {suggested_sha}"
        )
        lines.append("```")
        lines.append("")

        digest_path = self.results_dir / "discover.md"
        digest_path.write_text("\n".join(lines), encoding="utf-8")
        print(f"\n[probe] wrote discovery digest → {digest_path}")
        print("[probe] open it locally, pick values, then re-run with the suggested args.")
        print("[probe] (this file contains real project/plan names — redact before sharing.)")


# ---------------------------------------------------------------------------
# Inventory of write endpoints — never called by the probe
# ---------------------------------------------------------------------------

_WRITES_INVENTORY: list[tuple[str, str, str]] = [
    ("POST",   "/rest/api/latest/queue/{planKey}",                          "BambooApiClient.triggerBuild()"),
    ("POST",   "/build/admin/restartBuild.action?planKey={k}&buildNumber={n}", "BambooApiClient.rerunFailedJobs() — admin action, returns 302 on success"),
    ("DELETE", "/rest/api/latest/queue/{resultKey}",                        "BambooApiClient.cancelBuild() — cancel queued build"),
    ("PUT",    "/rest/api/latest/result/{resultKey}/stop",                  "BambooApiClient.stopBuild() — stop running build"),
]


# ---------------------------------------------------------------------------
# Helpers (copied verbatim from probe_bitbucket.py for redact.py / bundle.py
# compatibility — same JSON shape, same Result_N convention)
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


def _allocate_results_dir(parent: Path) -> Path:
    n = 1
    while True:
        candidate = parent / f"Result_{n}"
        if not candidate.exists():
            candidate.mkdir(parents=True)
            return candidate
        n += 1


def _read_raw_body(raw_path: Path) -> Any:
    """Return the parsed `raw_body` from a saved probe response file, or None."""
    try:
        data = json.loads(raw_path.read_text(encoding="utf-8"))
        return data.get("raw_body")
    except Exception:
        return None


def _collect_top_project_keys(raw_path: Path, limit: int) -> list[str]:
    """Pull the first `limit` project keys from a /project response.

    Bamboo's response shape: `{"projects": {"project": [{"key": ...}, ...]}}`.
    """
    body = _read_raw_body(raw_path)
    if not isinstance(body, dict):
        return []
    inner = body.get("projects")
    if not isinstance(inner, dict):
        return []
    out: list[str] = []
    for p in (inner.get("project") or []):
        if isinstance(p, dict) and isinstance(p.get("key"), str):
            out.append(p["key"])
            if len(out) >= limit:
                break
    return out


def _collect_candidate_plans(
    raw_dir: Path, project_keys: list[str], max_total: int,
) -> list[dict]:
    """For each project's plan list, pluck the first plans up to `max_total`.

    Returns dicts shaped {"key", "name", "project_key"}.
    Bamboo's `/project/{key}?expand=plans.plan` response nests plans under
    `{"plans": {"plan": [...]}}`."""
    out: list[dict] = []
    for pkey in project_keys:
        body = _read_raw_body(raw_dir / f"discover_project_{pkey}_plans.json")
        if not isinstance(body, dict):
            continue
        plans_block = body.get("plans")
        if not isinstance(plans_block, dict):
            continue
        for plan in (plans_block.get("plan") or []):
            if not isinstance(plan, dict):
                continue
            plkey = plan.get("key")
            if not isinstance(plkey, str):
                continue
            out.append({
                "key": plkey,
                "name": str(plan.get("name", plan.get("shortName", ""))),
                "project_key": pkey,
            })
            if len(out) >= max_total:
                return out
    return out


def _extract_sample_from_results(
    raw_dir: Path, plan_keys: list[str],
) -> Optional[dict]:
    """Return {plan_key, project_key, plan_result_key, job_result_key, branch}
    from the first plan that has at least one recent build. None when no plan
    has any recent build visible."""
    for plkey in plan_keys:
        body = _read_raw_body(raw_dir / f"discover_plan_{plkey}_recent.json")
        if not isinstance(body, dict):
            continue
        results_block = body.get("results")
        if not isinstance(results_block, dict):
            continue
        results = results_block.get("result") or []
        if not results:
            continue
        first = results[0]
        if not isinstance(first, dict):
            continue
        plan_rk = first.get("buildResultKey") or first.get("key")
        if not plan_rk:
            continue
        job_rk: Optional[str] = None
        stages = first.get("stages")
        if isinstance(stages, dict):
            for st in (stages.get("stage") or []):
                if not isinstance(st, dict):
                    continue
                rblock = st.get("results")
                if not isinstance(rblock, dict):
                    continue
                for jr in (rblock.get("result") or []):
                    if isinstance(jr, dict) and isinstance(jr.get("key"), str):
                        job_rk = jr["key"]
                        break
                if job_rk:
                    break
        branch = first.get("planBranchName")
        proj_key = plkey.split("-", 1)[0] if "-" in plkey else ""
        return {
            "plan_key": plkey,
            "project_key": proj_key,
            "plan_result_key": str(plan_rk),
            "job_result_key": job_rk,
            "branch": branch if isinstance(branch, str) else None,
        }
    return None


def _extract_commit_sha(raw_path: Path) -> Optional[str]:
    """Pull the first vcsRevisionKey from a result?expand=vcsRevisions response."""
    body = _read_raw_body(raw_path)
    if not isinstance(body, dict):
        return None
    block = body.get("vcsRevisions")
    if not isinstance(block, dict):
        return None
    for rev in (block.get("vcsRevision") or []):
        if isinstance(rev, dict):
            sha = rev.get("vcsRevisionKey")
            if isinstance(sha, str) and len(sha) >= 7:
                return sha
    return None


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> int:
    p = argparse.ArgumentParser(
        description="Read-only Bamboo Server / DC probe for Workflow Orchestrator plugin",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    p.add_argument("--url", required=True, help="Bamboo base URL, e.g. https://bamboo.example.com")
    p.add_argument("--token", required=True, help="Personal Access Token (Bearer)")
    p.add_argument("--plan-key",   help="Plan key, e.g. PROJ-PLAN (enables plan-scoped probes)")
    p.add_argument("--result-key", help="Build/job result key, e.g. PROJ-PLAN-123 or "
                                         "PROJ-PLAN-JOBSHORT-123 (enables result-scoped probes; "
                                         "use a job-level key to get real build logs)")
    p.add_argument("--project-key", help="Project key, e.g. PROJ (enables project-scoped probes)")
    p.add_argument("--branch-name", help="Branch short name, e.g. feature/foo "
                                          "(enables /result/{plan}/branch/{name}/latest probe)")
    p.add_argument("--commit-sha",  help="Commit SHA (enables /result/byChangeset/{sha} probe)")
    p.add_argument("--no-verify",   action="store_true",
                   help="Disable TLS verification (self-signed certs)")
    p.add_argument("--versions-only", action="store_true",
                   help="Probe only /info + /serverInfo + /currentUser + "
                        "/info/configurationProperties and exit. Recommended first run.")
    p.add_argument("--discover", action="store_true",
                   help="Discovery walk — finds plan / result / project / "
                        "branch / SHA values from your actual permissions and "
                        "writes Result_N/discover.md with a copy-paste full-"
                        "sweep command. Combine with --project-key X to scope "
                        "to one project, or --plan-key X to scope to a single "
                        "plan; without either it walks the first 5 projects "
                        "alphabetically (often unrelated on large instances).")
    p.add_argument("--write-test", action="store_true",
                   help="Write-test mode — verifies queue-trigger variable "
                        "encoding by triggering ONE build with a sentinel "
                        "value. Prints exact request and waits for Y/N at "
                        "stdin before posting. Requires --plan-key, "
                        "--variable-name, --variable-value.")
    p.add_argument("--variable-name",
                   help="(write-test) Variable to set, e.g. dockerTagsAsJson")
    p.add_argument("--variable-value",
                   help="(write-test) Sentinel value to send for the variable")
    p.add_argument("--let-build-finish", action="store_true",
                   help="(write-test) Poll until the build reaches a terminal "
                        "state (timeout 30 min). Otherwise polls for up to 5 "
                        "min and reports as soon as variables are observable.")
    p.add_argument("--out", default=str(Path(__file__).parent),
                   help="Parent dir for Result_N/ output (default: alongside the script)")
    args = p.parse_args()

    if not args.token:
        print("ERROR: --token must be non-empty", file=sys.stderr)
        return 2

    mode_flags = sum([bool(args.discover), bool(args.versions_only),
                      bool(args.write_test)])
    if mode_flags > 1:
        print("ERROR: --discover, --versions-only, --write-test are mutually exclusive.",
              file=sys.stderr)
        return 2

    if args.write_test:
        missing = [name for name, val in (
            ("--plan-key", args.plan_key),
            ("--variable-name", args.variable_name),
            ("--variable-value", args.variable_value),
        ) if not val]
        if missing:
            print(f"ERROR: --write-test requires {', '.join(missing)}",
                  file=sys.stderr)
            return 2

    if args.project_key and "-" in args.project_key:
        derived = args.project_key.split("-", 1)[0]
        print(
            f"ERROR: --project-key '{args.project_key}' contains a dash and "
            f"looks like a plan key. The project key is the parent (everything "
            f"before the first dash). Try --project-key {derived}.\n"
            f"To probe a single plan instead, pass it via --plan-key {args.project_key}.",
            file=sys.stderr,
        )
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

    if args.discover:
        mode_label = "discover"
    elif args.versions_only:
        mode_label = "versions-only"
    elif args.write_test:
        mode_label = "write-test"
    else:
        mode_label = "full sweep"
    print(f"[probe] target: {args.url}")
    print(f"[probe] output: {results_dir}")
    print(f"[probe] mode:   {mode_label}")
    print()

    probe = BambooProbe(args.url, args.token, verify=not args.no_verify, results_dir=results_dir)

    args_used = {
        "url": args.url,
        "plan_key": args.plan_key,
        "result_key": args.result_key,
        "project_key": args.project_key,
        "branch_name": args.branch_name,
        "commit_sha": args.commit_sha,
        "no_verify": args.no_verify,
        "versions_only": args.versions_only,
        "discover": args.discover,
        "write_test": args.write_test,
        "variable_name": args.variable_name,
        "variable_value": args.variable_value if not args.write_test else "<redacted>",
        "let_build_finish": args.let_build_finish,
    }

    write_test_exit_code = None
    if args.discover:
        probe.run_discover(
            project_key=args.project_key,
            plan_key=args.plan_key,
        )
    elif args.versions_only:
        probe.run_versions_only()
    elif args.write_test:
        write_test_exit_code = probe.run_write_test(
            plan_key=args.plan_key,
            variable_name=args.variable_name,
            variable_value=args.variable_value,
            let_build_finish=args.let_build_finish,
        )
    else:
        probe.run_full_sweep(
            plan_key=args.plan_key,
            result_key=args.result_key,
            project_key=args.project_key,
            branch_name=args.branch_name,
            commit_sha=args.commit_sha,
        )

    probe.write_summary(args_used)
    if args.discover:
        print(f"[probe] done — open {results_dir / 'discover.md'} for copy-paste IDs.")
    elif args.write_test:
        print(f"[probe] done — open {results_dir / 'summary.md'} for the audit trail.")
        return write_test_exit_code or 0
    else:
        print(f"[probe] done — open {results_dir / 'summary.md'} and paste back to me.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
