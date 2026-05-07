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

    # Full sweep — needs realistic ids so candidates exercise non-empty payloads
    python probe_bamboo.py --url https://bamboo.company.com --token PAT \\
        --plan-key PROJ-PLAN --result-key PROJ-PLAN-123 --project-key PROJ \\
        --branch-name feature/foo --commit-sha abc123def

    # Self-signed cert
    python probe_bamboo.py ... --no-verify

The script never executes mutations (build trigger, restart, queue cancel,
running-build stop). Bamboo's mutating endpoints — `POST /rest/api/latest/queue`,
`POST /build/admin/restartBuild.action`, `DELETE /rest/api/latest/queue/{key}`,
`PUT /rest/api/latest/result/{key}/stop` — are inventoried in summary.md but
never invoked. The User-Agent string includes `(read-only)` so admins can
audit probe traffic in Bamboo's access logs.
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

    mode_label = "versions-only" if args.versions_only else "full sweep"
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
    }

    if args.versions_only:
        probe.run_versions_only()
    else:
        probe.run_full_sweep(
            plan_key=args.plan_key,
            result_key=args.result_key,
            project_key=args.project_key,
            branch_name=args.branch_name,
            commit_sha=args.commit_sha,
        )

    probe.write_summary(args_used)
    print(f"[probe] done — open {results_dir / 'summary.md'} and paste back to me.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
