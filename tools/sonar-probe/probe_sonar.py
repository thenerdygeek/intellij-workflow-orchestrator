#!/usr/bin/env python3
"""
SonarQube Server / Community Build Web API probe for the Workflow
Orchestrator IntelliJ plugin.

Read-only. Verifies every endpoint the plugin currently calls (14 paths in
``SonarApiClient.kt``) plus a focused set of candidate endpoints we may want
to adopt (analysis history, metric history, quality gate listing, language
listing, issue tag listing). Writes per-endpoint JSON + a Markdown summary
to ``tools/sonar-probe/Result_N/``.

The probe is **edition-aware**. The summary's "Version detection" block
surfaces the edition (from ``/api/navigation/global``) so the
recommendations doc can flag any plugin surfaces that degrade on the
user's tier.

> **Note on Community Build 25.x**: prior to 25.x, Community Edition
> silently ignored ``branch=`` on most endpoints, 404'd on
> ``/api/hotspots/search``, and returned only the main branch from
> ``/api/project_branches/list``. With the 25.x rebrand to "Community
> Build", Sonar moved multi-branch analysis (and therefore those
> branch-aware endpoints) to the free tier. The probe was originally
> written against the pre-25.x feature gate; per-endpoint notes below
> reflect the **validated 25.x Community Build** behaviour.

Usage examples:

    # Just detect version + edition + connectivity (4 calls, no params)
    python probe_sonar.py --url https://sonar.company.com --token PAT --versions-only

    # Discovery walk — finds project / branch / sample CE task ID from your
    # PAT's actual permissions. Without --project-key it walks the first 5
    # projects alphabetically (often unrelated on large instances).
    python probe_sonar.py --url https://sonar.company.com --token PAT --discover \\
        --project-key MY_PROJECT_KEY    # scope to one project (recommended)

    # Full sweep — needs realistic ids so candidates exercise non-empty payloads.
    # File key format is "<projectKey>:<path-from-repo-root>", e.g.
    # "com.example:my-service:src/main/java/com/example/Foo.java".
    python probe_sonar.py --url https://sonar.company.com --token PAT \\
        --project-key MY_PROJECT_KEY \\
        --branch main \\
        --file-key 'MY_PROJECT_KEY:src/main/java/Foo.java' \\
        --rule-key java:S1135

    # Self-signed cert
    python probe_sonar.py ... --no-verify

The script never executes mutations. The plugin's :sonar module makes ZERO
write calls against SonarQube — it's a pure read consumer (validate,
search, branches, gate, issues, measures, ce, new code, hotspots,
duplications, rules, sources). The User-Agent string includes
``(read-only)`` so admins can audit probe traffic in SonarQube's access
logs.
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
# Probe result dataclass (kept compatible with redact.py / bundle.py — same
# shape as probe_bamboo.py / probe_bitbucket.py / probe_jira.py).
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
    category: str = ""        # "version" | "existing" | "feature"


# Default rule used when --rule-key is absent. java:S1135 ("Track uses of
# 'TODO' tags") is part of the bundled Sonar Way profile on every standard
# install regardless of edition, so /api/rules/show is virtually always
# safe to probe with this key.
DEFAULT_RULE_KEY = "java:S1135"


# ---------------------------------------------------------------------------
# Probe runner
# ---------------------------------------------------------------------------

class SonarProbe:
    def __init__(self, base_url: str, token: str, verify: bool, results_dir: Path):
        self.base = base_url.rstrip("/")
        self.session = requests.Session()
        self.session.headers.update({
            "Authorization": f"Bearer {token}",
            "Accept": "application/json",
            "User-Agent": "WorkflowOrchestrator-SonarProbe/1.0 (read-only)",
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

        SonarQube's plugin-side surface uses only GET. There are no read-only
        POST/search endpoints we hit (/api/issues/search etc. are all GET).
        ``expect_json=False`` covers `/api/server/version`, which returns
        plain text (the version string verbatim, no JSON wrapper).
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
                # Plain-text endpoints (/api/server/version returns ~12 bytes
                # like "10.4.1.88267"). Capture the body verbatim — it's tiny.
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

        raw_file = self.raw_dir / f"{name}.json"
        raw_file.write_text(json.dumps({
            "result": asdict(result),
            "raw_body": raw_payload if raw_payload is not None
            else (result.payload_preview if result.payload_kind == "text" else None),
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
        """Minimal version + edition detection — 4 calls, no params required.

        SonarQube's version+edition signal is split across endpoints:
        ``/api/server/version`` returns the bare version string as plain
        text; ``/api/system/status`` returns ``{id, version, status}``;
        ``/api/navigation/global`` returns the ``edition`` field
        (``community|developer|enterprise|datacenter``) which is the
        feature gate the plugin actually cares about.
        """
        print("[probe] versions-only mode\n")
        self._get(
            name="server_version",
            description="Plain-text server version string (canonical version detect)",
            path="/api/server/version",
            category="version",
            expect_json=False,
            accept="text/plain",
            notes=[
                "Returns version verbatim, e.g. '10.4.1.88267' — no JSON wrapper.",
                "No auth required on most installs; safe to call anonymously.",
            ],
        )
        self._get(
            name="system_status",
            description="Server status + id + version (lightweight liveness)",
            path="/api/system/status",
            category="version",
            notes=[
                "Returns {id, version, status} where status ∈ "
                "{UP, STARTING, DOWN, RESTARTING, DB_MIGRATION_NEEDED, "
                "DB_MIGRATION_RUNNING}. Plugin doesn't use this today; "
                "candidate for a startup health probe.",
            ],
        )
        self._get(
            name="navigation_global",
            description="Global nav payload — exposes 'edition' (THE feature gate)",
            path="/api/navigation/global",
            category="version",
            notes=[
                "edition ∈ {community, developer, enterprise, datacenter}.",
                "Sonar 25.x: 'community' is Community Build (renamed from "
                "Community Edition) and supports multi-branch analysis at "
                "the free tier — most endpoints now honour `branch=`.",
                "Paste the 'edition' + 'version' values back so the "
                "recommendations doc can call out tier- and version-specific "
                "behaviour.",
            ],
        )
        self._get(
            name="auth_validate",
            description="Token validity check (plugin's validateConnection())",
            path="/api/authentication/validate",
            category="version",
            notes=["Returns {valid: bool}; if false, the PAT is bogus or revoked"],
        )
        print("[probe] versions-only done — open Result_N/summary.md and paste the "
              "Version detection block back.")

    def run_full_sweep(
        self,
        project_key: Optional[str],
        branch: Optional[str],
        file_key: Optional[str],
        rule_key: Optional[str],
        ce_task_id: Optional[str],
    ) -> None:
        """Full sweep — covers every endpoint in ``SonarApiClient.kt`` plus
        feature-discovery candidates. Endpoints that need parameters are
        skipped cleanly when the parameter is absent (logged in summary.md
        as 'skipped')."""
        print("[probe] full sweep mode\n")

        # --- always-runnable version block (same as run_versions_only) ------
        self._get(
            name="server_version",
            description="Plain-text server version (canonical version detect)",
            path="/api/server/version",
            category="version",
            expect_json=False,
            accept="text/plain",
        )
        self._get(
            name="system_status",
            description="Server status + version (lightweight liveness)",
            path="/api/system/status",
            category="version",
        )
        self._get(
            name="navigation_global",
            description="Global nav — exposes 'edition' (community/developer/enterprise/datacenter)",
            path="/api/navigation/global",
            category="version",
        )
        self._get(
            name="auth_validate",
            description="Token validity (plugin's validateConnection())",
            path="/api/authentication/validate",
            category="version",
        )

        # --- existing endpoints (mirroring SonarApiClient.kt 1:1) -----------

        # Project search — used by the project picker dialog. ps=25 matches
        # the plugin's hard-coded page size.
        self._get(
            name="components_search",
            description="searchProjects() — picker dialog uses qualifiers=TRK&q=",
            path="/api/components/search?qualifiers=TRK&ps=25",
            category="existing",
            notes=["Lists ALL TRK components when q is omitted; plugin sends a query string at runtime"],
        )

        proj_param = (
            f"?project={urllib.parse.quote(project_key, safe='')}"
            if project_key else None
        )
        comp_key_param = (
            f"?component={urllib.parse.quote(project_key, safe='')}"
            if project_key else None
        )
        comp_keys_param = (
            f"?componentKeys={urllib.parse.quote(project_key, safe='')}"
            if project_key else None
        )
        proj_key_param = (
            f"?projectKey={urllib.parse.quote(project_key, safe='')}"
            if project_key else None
        )
        branch_qs = (
            f"&branch={urllib.parse.quote(branch, safe='')}" if branch else ""
        )

        if project_key:
            self._get(
                name="project_branches_list",
                description=f"getBranches({project_key}) — list project branches",
                path=f"/api/project_branches/list{proj_param}",
                category="existing",
                notes=[
                    "Edition gate (UPDATED for 25.x): Community Build 25.x "
                    "returns ALL branches with full metadata "
                    "({name, isMain, type, status, analysisDate, branchId, "
                    "excludedFromPurge}). Multi-branch was Developer+ only "
                    "pre-25.x; Sonar moved it to the free tier in the "
                    "Community Build rebrand.",
                ],
            )
            self._get(
                name="quality_gate_status",
                description=f"getQualityGateStatus({project_key}, branch={branch or '—'})",
                path=f"/api/qualitygates/project_status{proj_key_param}{branch_qs}",
                category="existing",
                notes=[
                    "Sonar 25.x Community Build: branch= IS honored and "
                    "returns per-branch gate status. Pre-25.x Community "
                    "ignored it. caycStatus + period.{mode,parameter} are "
                    "in the response.",
                ],
            )
            # /api/issues/search with the same params the plugin uses (paged).
            issues_qs = f"{comp_keys_param}&resolved=false&ps=10{branch_qs}"
            self._get(
                name="issues_search",
                description=f"getIssues({project_key}) — open issues, resolved=false, ps=10",
                path=f"/api/issues/search{issues_qs}",
                category="existing",
                notes=[
                    "Plugin uses ps=500 in production; ps=10 here keeps the "
                    "raw payload sane while still validating the contract.",
                    "Sonar 25.x Community Build: branch= IS honored. Each "
                    "issue gets `branch:<X>` tagged in the response, but "
                    "this means 'present in the X branch snapshot' — NOT "
                    "'unique to branch X'. To get the new-code DELTA "
                    "(issues introduced on this branch since the reference "
                    "branch), use the issues_search_new_code variant below.",
                    "Issues now carry CCT fields (cleanCodeAttribute, "
                    "cleanCodeAttributeCategory, impacts[]) added in 25.x; "
                    "plugin DTO may silently drop them via ignoreUnknownKeys.",
                ],
            )
            self._get(
                name="issues_search_new_code",
                description="getIssues(... inNewCodePeriod=true) — variant the New-Code tab uses",
                path=f"/api/issues/search{issues_qs}&inNewCodePeriod=true",
                category="existing",
                notes=[
                    "inNewCodePeriod=true requires either a configured new "
                    "code period for the branch or a branch=<X> fallback.",
                ],
            )
            # /api/measures/component_tree — file-level metrics. Plugin paginates
            # up to 10 pages × 500 ps; we send ps=20&p=1 here.
            metrics = (
                "coverage,line_coverage,branch_coverage,uncovered_lines,"
                "uncovered_conditions,lines_to_cover,new_coverage,"
                "new_branch_coverage,new_uncovered_lines,"
                "new_uncovered_conditions,new_lines_to_cover,bugs,"
                "vulnerabilities,code_smells,new_bugs,new_vulnerabilities,"
                "new_code_smells,sqale_index,sqale_rating,"
                "duplicated_lines_density,complexity,cognitive_complexity,"
                "reliability_rating,security_rating"
            )
            self._get(
                name="measures_component_tree",
                description="getMeasures() — file-level metrics with new_* (period field)",
                path=(
                    f"/api/measures/component_tree{comp_key_param}"
                    f"&metricKeys={metrics}&qualifiers=FIL&ps=20&p=1"
                    f"&additionalFields=period"
                    f"&s=metric&metricSort=new_lines_to_cover&asc=false"
                    f"{branch_qs}"
                ),
                category="existing",
                notes=[
                    "Pagination contract: components[], paging.{pageIndex,pageSize,total}.",
                    "additionalFields=period required for new_* metrics; "
                    "without it Sonar returns the value but no period meta.",
                ],
            )
            self._get(
                name="measures_component",
                description="getProjectMeasures() — project-level aggregate metrics",
                path=(
                    f"/api/measures/component{comp_key_param}"
                    f"&metricKeys=sqale_index,sqale_rating,"
                    f"duplicated_lines_density,cognitive_complexity,"
                    f"reliability_rating,security_rating,coverage,"
                    f"branch_coverage&additionalFields=period{branch_qs}"
                ),
                category="existing",
                notes=[
                    "Plugin uses this for the OverviewPanel cards (project-level).",
                    "Returns {component: {measures: [...]}} not a list.",
                ],
            )
            self._get(
                name="ce_activity",
                description=f"getAnalysisTasks({project_key}) — recent CE tasks",
                path=f"/api/ce/activity{comp_key_param}&ps=5",
                category="existing",
                notes=[
                    "Returns tasks[] with status (SUCCESS/FAILED/CANCELED/PENDING/IN_PROGRESS), "
                    "branch (Developer+), submittedAt, executedAt.",
                ],
            )
            self._get(
                name="new_code_period_show",
                description=f"getNewCodePeriod({project_key}, branch={branch or '—'})",
                path=f"/api/new_code_periods/show{proj_param}{branch_qs}",
                category="existing",
                notes=[
                    "Returns {projectKey, branchKey, type, value, inherited}.",
                    "type ∈ {PREVIOUS_VERSION, NUMBER_OF_DAYS, REFERENCE_BRANCH, SPECIFIC_ANALYSIS}.",
                    "Community: returns project-level only; branch param silently ignored.",
                ],
            )
            self._get(
                name="hotspots_search",
                description=f"getSecurityHotspots({project_key}) — Developer Edition+",
                path=f"/api/hotspots/search{proj_param}&ps=10{branch_qs}",
                category="existing",
                notes=[
                    "Sonar 25.x Community Build: endpoint IS available "
                    "(returns hotspots[] with key, component, project, "
                    "securityCategory, vulnerabilityProbability, status, "
                    "line, message, assignee, ruleKey, textRange). Pre-25.x "
                    "Community returned 404.",
                    "First hotspot's key is auto-lifted into hotspots_show "
                    "below for full risk + fix detail.",
                ],
            )
        else:
            for n, d in [
                ("project_branches_list", "getBranches()"),
                ("quality_gate_status", "getQualityGateStatus()"),
                ("issues_search", "getIssues()"),
                ("issues_search_new_code", "getIssues(inNewCodePeriod=true)"),
                ("measures_component_tree", "getMeasures()"),
                ("measures_component", "getProjectMeasures()"),
                ("ce_activity", "getAnalysisTasks()"),
                ("new_code_period_show", "getNewCodePeriod()"),
                ("hotspots_search", "getSecurityHotspots()"),
            ]:
                self._skip(n, f"{d} — needs --project-key", "existing")

        # CE task by id — needs --ce-task-id; if absent, try to lift one
        # from ce_activity's first SUCCESS task so the sweep can still
        # validate the endpoint contract.
        resolved_task_id = ce_task_id
        if not resolved_task_id and project_key:
            resolved_task_id = _extract_ce_task_id(self.raw_dir / "ce_activity.json")
            if resolved_task_id:
                print(f"[probe] reusing CE task id from ce_activity → {resolved_task_id}")
        if resolved_task_id:
            self._get(
                name="ce_task",
                description=f"getCeTask({resolved_task_id}) — single task detail",
                path=f"/api/ce/task?id={urllib.parse.quote(resolved_task_id, safe='')}"
                     f"&additionalFields=stacktrace,scannerContext,warnings",
                category="existing",
                notes=[
                    "Used by plugin to poll local-scanner analysis completion.",
                    "additionalFields here exercise the scannerContext + stacktrace "
                    "fields (admin-gated; non-admin tokens get 200 without them).",
                ],
            )
        else:
            self._skip(
                "ce_task",
                "getCeTask() — needs --ce-task-id (or a SUCCESS task in ce_activity)",
                "existing",
            )

        # /api/hotspots/show — auto-lift first hotspot key from
        # hotspots_search above. Plugin doesn't use this today (only
        # hotspots_search), but the agent needs the full risk + fix
        # detail per hotspot for autonomous remediation.
        resolved_hotspot_key: Optional[str] = None
        if project_key:
            resolved_hotspot_key = _extract_hotspot_key(self.raw_dir / "hotspots_search.json")
            if resolved_hotspot_key:
                print(f"[probe] reusing hotspot key from hotspots_search → {resolved_hotspot_key}")
        if resolved_hotspot_key:
            self._get(
                name="hotspots_show",
                description=f"(agent) /api/hotspots/show?hotspot={resolved_hotspot_key[:8]}…",
                path=f"/api/hotspots/show?hotspot={urllib.parse.quote(resolved_hotspot_key, safe='')}",
                category="feature",
                notes=[
                    "AGENT-TARGETED: full hotspot detail for autonomous fix.",
                    "Returns {key, component, project, rule:{key, name, "
                    "vulnerabilityProbability, securityCategory, "
                    "riskDescription, vulnerabilityDescription, "
                    "fixRecommendations}, status, line, message, assignee, "
                    "author, creationDate, updateDate, changelog[], "
                    "comment[], users[], textRange, flows[]}.",
                    "rule.fixRecommendations is what the agent needs to "
                    "produce a fix. Plugin only uses hotspots_search today.",
                ],
            )
        else:
            self._skip(
                "hotspots_show",
                "(agent) /api/hotspots/show — no hotspot found in "
                "hotspots_search to auto-lift",
                "feature",
            )

        if file_key:
            file_qs = f"?key={urllib.parse.quote(file_key, safe='')}"
            self._get(
                name="duplications_show",
                description=f"getDuplications({file_key})",
                path=f"/api/duplications/show{file_qs}{branch_qs}",
                category="existing",
                notes=[
                    "Returns {duplications:[{blocks:[{from,size,_ref}]}], "
                    "files:{<ref>:{key,name,project}}} — maps each "
                    "duplicated block to (line, length, file). The agent "
                    "uses this to locate exact lines + the duplicate "
                    "files to refactor toward.",
                    "Sonar 25.x: branch= IS honored.",
                ],
            )
            # /api/sources/lines — line range required (from/to).
            self._get(
                name="sources_lines",
                description=f"getSourceLines({file_key}, from=1, to=50)",
                path=f"/api/sources/lines{file_qs}&from=1&to=50{branch_qs}",
                category="existing",
                notes=[
                    "Returns sources[] with line, code, scmAuthor, scmDate, "
                    "scmRevision, lineHits, conditions, coveredConditions, "
                    "isNew (since new code period).",
                    "AGENT-TARGETED: lineHits=0 + conditions>0 means "
                    "uncovered branch — the agent uses this to know exactly "
                    "which lines need a test. coveredConditions vs "
                    "conditions tells the agent which branches of a "
                    "compound predicate weren't exercised.",
                    "from/to are 1-indexed and inclusive.",
                ],
            )
            # /api/sources/scm — per-line author + date + revision. Plugin
            # does not call this. AGENT use: identify which lines fall
            # inside the new-code period by date/revision attribution.
            self._get(
                name="sources_scm",
                description=f"(agent) /api/sources/scm({file_key}) — per-line blame",
                path=f"/api/sources/scm{file_qs}&from=1&to=50&commits_by_line=true",
                category="feature",
                notes=[
                    "AGENT-TARGETED: returns scm[[lineNumber, author, "
                    "date, revision], ...]. With commits_by_line=true, "
                    "every line gets attribution (without it, only the "
                    "first line of each contiguous commit-block does).",
                    "The agent uses this to filter out 'pre-existing' "
                    "lines when targeting new-code-only fixes — line is "
                    "in new code iff date >= newCodePeriod.startDate (or "
                    "revision is post-reference-branch on REFERENCE_BRANCH "
                    "mode).",
                ],
            )
        else:
            for n, d in [
                ("duplications_show", "getDuplications()"),
                ("sources_lines", "getSourceLines()"),
                ("sources_scm", "(agent) /api/sources/scm"),
            ]:
                self._skip(n, f"{d} — needs --file-key", "existing")

        effective_rule_key = rule_key or DEFAULT_RULE_KEY
        self._get(
            name="rules_show",
            description=f"getRule({effective_rule_key}) — rule definition",
            path=f"/api/rules/show?key={urllib.parse.quote(effective_rule_key, safe='')}",
            category="existing",
            notes=[
                f"Defaulted to '{DEFAULT_RULE_KEY}' (Sonar Way bundled rule, present on every install).",
                "Pass --rule-key to probe a custom rule.",
            ] if not rule_key else [
                "Rule keys are case-sensitive; 'java:S1135' vs 'JAVA:S1135' differ.",
            ],
        )

        # --- candidate / feature-discovery endpoints ------------------------
        # NOT in SonarApiClient today. Goal: surfaces that could power
        # features the plugin doesn't have yet.

        self._get(
            name="languages_list",
            description="(candidate) /api/languages/list — installed languages",
            path="/api/languages/list",
            category="feature",
            notes=[
                "Could power a 'what scanners cover this project' chip on "
                "the Quality tab. Tiny payload, always available.",
            ],
        )
        self._get(
            name="qualitygates_list",
            description="(candidate) /api/qualitygates/list — all configured quality gates",
            path="/api/qualitygates/list",
            category="feature",
            notes=[
                "Plugin only reads project_status today; this would let the "
                "settings page show 'this project's gate is X' before analysis.",
            ],
        )
        if project_key:
            self._get(
                name="project_analyses_search",
                description=f"(candidate) /api/project_analyses/search — analysis history",
                path=f"/api/project_analyses/search{proj_param}&ps=10{branch_qs}",
                category="feature",
                notes=[
                    "Each entry = {key, date, projectVersion, events[]}.",
                    "Could replace ce_activity polling for the 'last successful "
                    "analysis' chip + power a sparkline of analysis cadence.",
                    "Sonar 25.x: param is `project=` (NOT projectKey).",
                ],
            )
            self._get(
                name="measures_search_history",
                description="(candidate) /api/measures/search_history — metric over time",
                path=(
                    f"/api/measures/search_history{comp_key_param}"
                    f"&metrics=coverage,new_coverage,sqale_index"
                    f"&ps=20{branch_qs}"
                ),
                category="feature",
                notes=[
                    "Returns measures[].history[] = [{date, value}, ...].",
                    "Could power a 'coverage trend' sparkline on the Overview "
                    "card without polling component_tree.",
                ],
            )
            self._get(
                name="issues_tags",
                description="(candidate) /api/issues/tags — tag autocomplete on a project",
                path=f"/api/issues/tags{proj_param}&ps=20",
                category="feature",
                notes=[
                    "Could power a tag filter chip in the Issue list. "
                    "Returns {tags: [name, ...]}.",
                    "Sonar 25.x: param is `project=` (NOT projectKey). With "
                    "the wrong param Sonar silently returns global tags "
                    "instead of project-scoped tags.",
                ],
            )
            # AGENT-TARGETED: facet counts on new-code issues only.
            # ps=1 because we want the facets, not the issue payload.
            # Sonar's valid 25.x facet names (per /api/issues/search 400
            # error message when an unknown one is sent): projects, files,
            # assigned_to_me, severities, statuses, resolutions, rules,
            # assignees, author, directories, scopes, languages, tags,
            # types, pciDss-3.2, pciDss-4.0, owaspAsvs-4.0,
            # owaspMobileTop10-2024, stig-ASD_V5R3, casa, sansTop25, cwe,
            # createdAt, sonarsourceSecurity, codeVariants,
            # cleanCodeAttributeCategories, impactSoftwareQualities,
            # impactSeverities, issueStatuses, prioritizedRule.
            # Note `files` (NOT fileUuids — that was a pre-25.x name).
            facets = (
                "severities,types,tags,impactSoftwareQualities,"
                "impactSeverities,cleanCodeAttributeCategories,"
                "assignees,files,rules"
            )
            self._get(
                name="issues_search_facets_new_code",
                description="(agent) /api/issues/search facets (inNewCodePeriod=true) — triage breakdown",
                path=(
                    f"/api/issues/search?componentKeys="
                    f"{urllib.parse.quote(project_key, safe='')}"
                    f"&resolved=false&ps=1&inNewCodePeriod=true"
                    f"&facets={facets}{branch_qs}"
                ),
                category="feature",
                notes=[
                    "AGENT-TARGETED: returns {issues:[1], facets:[{property,"
                    " values:[{val,count}]},...]} — counts per softwareQuality"
                    " / severity / tag / file / rule for new code only.",
                    "Lets the agent prioritize: 'I have 3 RELIABILITY/HIGH "
                    "issues, 2 MAINTAINABILITY/MEDIUM, fix the reliability "
                    "ones first.' Without facets the agent has to walk all "
                    "issues to compute the same summary.",
                    "Pair with measures_search_history.new_violations to "
                    "see the new-code issue trend.",
                ],
            )
        else:
            for n, d in [
                ("project_analyses_search", "/api/project_analyses/search"),
                ("measures_search_history", "/api/measures/search_history"),
                ("issues_tags", "/api/issues/tags"),
                ("issues_search_facets_new_code",
                 "(agent) /api/issues/search facets (new-code triage)"),
            ]:
                self._skip(n, f"{d} — needs --project-key", "feature")

        self._get(
            name="users_current",
            description="(candidate) /api/users/current — current user identity",
            path="/api/users/current",
            category="feature",
            notes=[
                "Plugin doesn't surface 'who am I' for Sonar today (only Bitbucket/Jira).",
                "Returns {login, name, email, groups[], permissions, externalProvider}.",
            ],
        )

        # AI Code Fix capability detection. Sonar's AI Code Assurance APIs
        # land under /api/v2/ai-codefix/* (added in 25.x). Most paths are
        # POST endpoints that need an issue/hotspot key + body, but the
        # availability/feature-flag check is a GET. Probe a few likely
        # paths; on Community Build with no AI Code Assurance license,
        # all should 404 or 403 — that itself is a useful negative finding.
        for path, name in [
            ("/api/v2/ai-codefix/feature", "ai_codefix_feature"),
            ("/api/v2/ai-codefix/availability", "ai_codefix_availability"),
        ]:
            self._get(
                name=name,
                description=f"(agent) AI Code Fix capability detect: {path}",
                path=path,
                category="feature",
                notes=[
                    "AGENT-TARGETED: Sonar 25.x AI Code Fix would let the "
                    "plugin's agent skip the LLM round-trip and use Sonar's "
                    "own generative fix. Likely 404 on Community Build (the "
                    "qualitygates/list response shows isAiCodeSupported:false "
                    "on every gate, suggesting the feature is not licensed).",
                    "Either path 200 → AI Code Fix exists; both 404/403 → "
                    "feature absent, agent must use its own fix path.",
                ],
            )

        print(f"[probe] full sweep done — {sum(1 for r in self.results if r.ok)}/"
              f"{len(self.results)} OK, "
              f"{sum(1 for r in self.results if not r.ok and r.error is None and r.path != '(skipped)')} non-2xx, "
              f"{sum(1 for r in self.results if r.error)} errored, "
              f"{sum(1 for r in self.results if r.path == '(skipped)')} skipped.")

    def run_discover(self, project_key: Optional[str] = None) -> None:
        """Discovery walk — finds the IDs the full sweep needs from the
        user's actual permissions, not from a global dump. Read-only.

        Scoping:
            - ``--project-key X``: walk only that project's branches +
              recent CE activity. Skips the components_search listing.
            - none: walk the first 5 visible projects alphabetically.
              Sonar has no per-user filter equivalent to Jira's
              ``assignee=currentUser()``, so on instances with hundreds of
              projects this often surfaces unrelated entries — pass
              ``--project-key`` for an accurate walk.

        Common steps after scoping:
            * For the candidate project, list branches
              (``/api/project_branches/list``) + recent CE tasks
              (``/api/ce/activity``).
            * From the most recent successful CE task, capture the task id
              + branch for the suggested full-sweep command.
            * Write ``Result_N/discover.md`` with copy-paste full-sweep
              command.
        """
        print("[probe] discover mode\n")
        self.run_versions_only()

        scope_hint: Optional[str]
        candidate_keys: list[str]

        if project_key:
            scope_hint = f"`--project-key {project_key}` (single project)"
            print(f"\n[probe] discover — scoped to project {project_key}")
            candidate_keys = [project_key]
        else:
            scope_hint = (
                "_unscoped — first 5 projects alphabetically. "
                "Re-run with `--project-key YOURKEY` for an accurate walk._"
            )
            print("\n[probe] discover — unscoped walk (first 5 projects)")
            print("[probe] tip: re-run with --project-key YOURKEY to scope")
            self._get(
                name="discover_projects",
                description="All TRK projects visible to PAT (paginated, first 100)",
                path="/api/components/search?qualifiers=TRK&ps=100",
                category="existing",
                notes=["Discovery — projects readable by your PAT"],
            )
            candidate_keys = _collect_top_project_keys(
                self.raw_dir / "discover_projects.json", limit=5,
            )
            if not candidate_keys:
                print("[probe] discover — no projects visible; cannot continue walk")
                self._write_discover_digest([], None, scope_hint=scope_hint)
                return
            print(f"[probe] discover — inspecting first {len(candidate_keys)} "
                  f"project(s): {', '.join(candidate_keys)}")

        # Walk each candidate's branches + recent CE first, THEN pick a
        # branch, THEN walk a sample file scoped to that branch. Walking
        # files without a branch param returns main-branch files which
        # may not exist on the picked feature branch (Sonar 404s on
        # /api/duplications/show + /api/sources/lines when the file isn't
        # in the requested branch's snapshot).
        for pkey in candidate_keys:
            enc = urllib.parse.quote(pkey, safe='')
            self._get(
                name=f"discover_branches_{_safe_filename(pkey)}",
                description=f"Branches for project {pkey}",
                path=f"/api/project_branches/list?project={enc}",
                category="existing",
                notes=[f"Discovery — branches under {pkey}"],
            )
            self._get(
                name=f"discover_ce_{_safe_filename(pkey)}",
                description=f"Recent CE tasks for {pkey}",
                path=f"/api/ce/activity?component={enc}&ps=5",
                category="existing",
                notes=[f"Discovery — recent CE tasks for {pkey}"],
            )

            # Pick the audit branch from the just-fetched branches list,
            # then walk one sample file scoped to that branch. Falls back
            # to a main-branch walk (no branch param) when no branch
            # could be picked.
            branches_body = _read_raw_body(
                self.raw_dir / f"discover_branches_{_safe_filename(pkey)}.json"
            )
            picked = _pick_audit_branch(branches_body)
            picked_branch = picked.get("branch")
            file_branch_qs = (
                f"&branch={urllib.parse.quote(picked_branch, safe='')}"
                if picked_branch else ""
            )
            self._get(
                name=f"discover_files_{_safe_filename(pkey)}",
                description=(
                    f"Sample file for {pkey} on branch "
                    f"`{picked_branch or '(main, default)'}` (--file-key seed)"
                ),
                path=(
                    f"/api/measures/component_tree?component={enc}"
                    f"&qualifiers=FIL&ps=1&p=1&metricKeys=ncloc"
                    f"{file_branch_qs}"
                ),
                category="existing",
                notes=[
                    f"Discovery — first FIL component under {pkey} on "
                    f"branch {picked_branch or '(main)'}, used to seed "
                    f"--file-key in discover.md. Branch-scoped so the "
                    f"file is guaranteed to exist on the audit branch.",
                ],
            )
            # If branch-scoped walk returned nothing useful (some Sonar
            # setups disable component_tree per-branch for non-admin
            # tokens), fall back to a main-branch walk so we have *some*
            # file-key to suggest, with a warning.
            if picked_branch and not _extract_first_file_key(
                self.raw_dir / f"discover_files_{_safe_filename(pkey)}.json"
            ):
                self._get(
                    name=f"discover_files_main_{_safe_filename(pkey)}",
                    description=(
                        f"Fallback file-key walk for {pkey} (main branch)"
                    ),
                    path=(
                        f"/api/measures/component_tree?component={enc}"
                        f"&qualifiers=FIL&ps=1&p=1&metricKeys=ncloc"
                    ),
                    category="existing",
                    notes=[
                        f"Discovery fallback — branch-scoped file walk on "
                        f"{picked_branch} returned no FIL components; "
                        f"trying main. This file-key may not exist on the "
                        f"audit branch — duplications + sources probes "
                        f"will 404 if so.",
                    ],
                )

        sample = _extract_discover_sample(self.raw_dir, candidate_keys)
        self._write_discover_digest(candidate_keys, sample, scope_hint=scope_hint)

    # -- skip helper for endpoints that need missing CLI args -----------------

    def _skip(self, name: str, description: str, category: str) -> None:
        """Record a 'not run' entry so the summary still shows the gap."""
        result = ProbeResult(
            name=name, description=description, method="GET", path="(skipped)",
            category=category, status=0, ok=False, payload_kind="empty",
            notes=["Skipped — required CLI argument not provided"],
        )
        self.results.append(result)

    # -- summary writer -------------------------------------------------------

    def write_summary(self, args_used: dict[str, Any]) -> None:
        summary_path = self.results_dir / "summary.md"
        lines: list[str] = []
        lines.append(f"# SonarQube probe results — {self.base}")
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
            lines.append("## Version + edition detection")
            lines.append("")
            lines.append(version_note)
            lines.append("")

        for category in ("version", "existing", "feature"):
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

        # Plugin makes ZERO writes against Sonar — note it explicitly so
        # the recommendations doc doesn't search for a write inventory
        # that doesn't exist.
        lines.append("## Writes inventoried but NOT called (read-only probe)")
        lines.append("")
        lines.append(
            "**None.** The plugin's `:sonar` module is a pure read consumer of "
            "the SonarQube Web API — `SonarApiClient.kt` exposes only `GET` "
            "calls (validate, search, branches, gate, issues, measures, ce, "
            "new code, hotspots, duplications, rules, sources). There is no "
            "`POST`/`PUT`/`DELETE` against Sonar anywhere in the plugin. "
            "User-Agent ends in `(read-only)` for audit-log proof."
        )
        lines.append("")

        lines.append("## Raw responses")
        lines.append("")
        lines.append("Each endpoint's full response (parsed JSON or text) is saved to `raw/<name>.json`.")
        lines.append("Skipped endpoints have no raw file. `/api/server/version` returns plain text — captured verbatim.")
        lines.append("")

        summary_path.write_text("\n".join(lines), encoding="utf-8")
        print(f"\n[probe] wrote summary -> {summary_path}")
        print(f"[probe] wrote {sum(1 for r in self.results if r.path != '(skipped)')} raw payloads -> {self.raw_dir}")

    def _format_version_note(self) -> str:
        version_str = "?"
        ver_file = self.raw_dir / "server_version.json"
        if ver_file.exists():
            try:
                data = json.loads(ver_file.read_text(encoding="utf-8"))
                # plain-text body lands in raw_body or payload_preview
                body = data.get("raw_body")
                if isinstance(body, str) and body.strip():
                    version_str = body.strip()
                else:
                    pv = data.get("result", {}).get("payload_preview")
                    if isinstance(pv, str) and pv.strip():
                        version_str = pv.strip()
            except Exception:
                pass

        status_payload: dict[str, Any] = {}
        status_file = self.raw_dir / "system_status.json"
        if status_file.exists():
            try:
                data = json.loads(status_file.read_text(encoding="utf-8"))
                if isinstance(data.get("raw_body"), dict):
                    status_payload = data["raw_body"]
            except Exception:
                pass

        nav_payload: dict[str, Any] = {}
        nav_file = self.raw_dir / "navigation_global.json"
        if nav_file.exists():
            try:
                data = json.loads(nav_file.read_text(encoding="utf-8"))
                if isinstance(data.get("raw_body"), dict):
                    nav_payload = data["raw_body"]
            except Exception:
                pass

        edition = nav_payload.get("edition") if isinstance(nav_payload, dict) else None

        if version_str == "?" and not status_payload and not nav_payload:
            return "_None of the version endpoints responded — server unreachable, auth failure, or non-Sonar URL._"

        out: list[str] = []
        out.append(f"- **version (`/api/server/version`):** `{version_str}`")
        if status_payload:
            out.append(
                f"- **status (`/api/system/status`):** "
                f"`{status_payload.get('status', '?')}` "
                f"(id `{status_payload.get('id', '?')}`)"
            )
        if isinstance(edition, str):
            out.append(f"- **edition (`/api/navigation/global`):** `{edition}`")
            out.append("")
            out.append(_edition_capability_note(edition))
        elif nav_payload:
            out.append(
                "- **edition:** _`/api/navigation/global` returned a payload "
                "but no `edition` field — check raw/navigation_global.json._"
            )
        else:
            out.append(
                "- **edition:** _`/api/navigation/global` did not respond — "
                "edition unknown. Plugin will assume Community-tier behaviour "
                "(no branch params, no hotspots) until edition is known._"
            )
        return "\n".join(out)

    # -- discover digest writer ----------------------------------------------

    def _write_discover_digest(
        self,
        candidate_keys: list[str],
        sample: Optional[dict],
        scope_hint: Optional[str] = None,
    ) -> None:
        """Render Result_N/discover.md scoped to the user's actual permissions.

        Lists projects (when unscoped), candidate branches per project, and
        a copy-paste full-sweep command seeded from the most recent CE task.
        """
        lines: list[str] = ["# SonarQube discovery — pick values for the full sweep", ""]

        ver_file = self.raw_dir / "server_version.json"
        nav_file = self.raw_dir / "navigation_global.json"
        version = "?"
        edition = "?"
        if ver_file.exists():
            try:
                data = json.loads(ver_file.read_text(encoding="utf-8"))
                body = data.get("raw_body")
                if isinstance(body, str) and body.strip():
                    version = body.strip()
            except Exception:
                pass
        if nav_file.exists():
            try:
                data = json.loads(nav_file.read_text(encoding="utf-8"))
                if isinstance(data.get("raw_body"), dict):
                    e = data["raw_body"].get("edition")
                    if isinstance(e, str):
                        edition = e
            except Exception:
                pass

        lines.append(f"**SonarQube**: `{version}` · **edition**: `{edition}`")
        if scope_hint:
            lines.append(f"**Scope**: {scope_hint}")
        lines.append("")

        # Listing of visible projects (only present when we did a global walk)
        projects_body = _read_raw_body(self.raw_dir / "discover_projects.json")
        if isinstance(projects_body, dict):
            comps = projects_body.get("components") or []
            if isinstance(comps, list) and comps:
                shown = min(len(comps), 5)
                paging = projects_body.get("paging") or {}
                total = paging.get("total") if isinstance(paging, dict) else "?"
                lines.append(f"## Projects you can see ({shown} of {total})")
                lines.append("")
                lines.append("| Key | Name | Qualifier |")
                lines.append("|---|---|---|")
                for c in comps[:5]:
                    if isinstance(c, dict):
                        ck = str(c.get("key", "")).replace("|", "\\|")
                        cn = str(c.get("name", "")).replace("|", "\\|")
                        cq = str(c.get("qualifier", ""))
                        lines.append(f"| `{ck}` | {cn} | `{cq}` |")
                lines.append("")

        # Per-candidate-project branch + CE summary
        if candidate_keys:
            lines.append(f"## Candidate projects ({len(candidate_keys)} sampled)")
            lines.append("")
            lines.append("| Project key | Branches | Latest CE task | CE status |")
            lines.append("|---|---|---|---|")
            for pkey in candidate_keys:
                branches_body = _read_raw_body(
                    self.raw_dir / f"discover_branches_{_safe_filename(pkey)}.json"
                )
                ce_body = _read_raw_body(
                    self.raw_dir / f"discover_ce_{_safe_filename(pkey)}.json"
                )
                branch_count = "—"
                main_branch_label = "—"
                if isinstance(branches_body, dict):
                    blist = branches_body.get("branches") or []
                    if isinstance(blist, list):
                        branch_count = str(len(blist))
                        for b in blist:
                            if isinstance(b, dict) and b.get("isMain"):
                                main_branch_label = (
                                    f"`{b.get('name', '?')}` (main)"
                                )
                                break
                ce_label = "—"
                ce_status = "—"
                if isinstance(ce_body, dict):
                    tasks = ce_body.get("tasks") or []
                    if isinstance(tasks, list) and tasks:
                        first = tasks[0]
                        if isinstance(first, dict):
                            ce_label = (
                                f"`{str(first.get('id', '?'))[:12]}…`"
                            )
                            ce_status = str(first.get("status", "?"))
                lines.append(
                    f"| `{pkey}` | {branch_count} ({main_branch_label}) | "
                    f"{ce_label} | {ce_status} |"
                )
            lines.append("")

            # Per-project branch detail (first project only — usually scoped)
            for pkey in candidate_keys[:1]:
                branches_body = _read_raw_body(
                    self.raw_dir / f"discover_branches_{_safe_filename(pkey)}.json"
                )
                if not isinstance(branches_body, dict):
                    continue
                blist = branches_body.get("branches") or []
                if not isinstance(blist, list) or not blist:
                    continue
                lines.append(f"### Branches under `{pkey}`")
                lines.append("")
                lines.append("| Branch | isMain | analysisDate | excluded? |")
                lines.append("|---|---|---|---|")
                for b in blist[:10]:
                    if not isinstance(b, dict):
                        continue
                    name = str(b.get("name", "?")).replace("|", "\\|")
                    is_main = "yes" if b.get("isMain") else "no"
                    adate = str(b.get("analysisDate", ""))
                    excl = "yes" if b.get("excludedFromPurge") else "no"
                    lines.append(f"| `{name}` | {is_main} | {adate} | {excl} |")
                lines.append("")

        if sample:
            lines.append(
                f"## Sample IDs from `{sample.get('project_key', '?')}`"
            )
            lines.append("")
            tk = sample.get("ce_task_id")
            br = sample.get("branch")
            br_reason = sample.get("branch_reason") or ""
            main_br = sample.get("main_branch")
            stat = sample.get("ce_status")
            if tk:
                lines.append(f"- **CE task id**: `{tk}` (status: `{stat or '?'}`)")
            else:
                lines.append(
                    "- **CE task id**: _no recent CE task found — "
                    "/api/ce/activity returned empty or 403'd for this "
                    "project's PAT (admin-gated on Sonar 25.x)._"
                )
            if br:
                lines.append(
                    f"- **Branch**: `{br}` — _{br_reason}_"
                )
                if main_br and br != main_br:
                    lines.append(
                        f"  - Main branch is `{main_br}`. To audit main "
                        f"instead of the feature branch above, pass "
                        f"`--branch {main_br}` — but `inNewCodePeriod=true` "
                        f"will return empty/self-comparison there, so prefer "
                        f"a feature branch for new-code audits."
                    )
            else:
                lines.append(
                    "- **Branch**: _no branch info available. Pass "
                    "`--branch <name>` manually from your Sonar UI's "
                    "Branches tab._"
                )
            lines.append("")
        else:
            lines.append("## Sample IDs")
            lines.append("")
            lines.append(
                "_No CE task found on candidate projects. Fill in "
                "`--ce-task-id` manually from the SonarQube UI — open the "
                "project's Activity tab, click into a recent task, and "
                "grab the id from the URL or the task detail panel._"
            )
            lines.append("")

        suggested_proj = (sample or {}).get("project_key") or (
            candidate_keys[0] if candidate_keys else "MY_PROJECT_KEY"
        )
        suggested_branch = (sample or {}).get("branch") or "<main-or-feature>"
        suggested_task = (sample or {}).get("ce_task_id") or "<ce-task-id>"
        # Discover-mode lifts a real --file-key from the project's first
        # FIL component (post-2026-05-07 follow-up). Falls back to a
        # placeholder only when the file walk returned nothing (e.g.
        # admin-gated component_tree on some Sonar setups).
        suggested_file_key = (sample or {}).get("file_key") or (
            f"{suggested_proj}:src/main/java/<path>/<File>.java"
        )
        file_key_was_discovered = bool((sample or {}).get("file_key"))

        lines.append("---")
        lines.append("")
        lines.append("## Suggested full-sweep command")
        lines.append("")
        seed_note = (
            "_Seeded from the most-recent values discovered above. "
            "`--rule-key` is optional and defaults to `java:S1135` (Sonar Way)._"
        )
        if file_key_was_discovered:
            file_key_branch_scoped = (sample or {}).get("file_key_branch_scoped")
            if file_key_branch_scoped:
                seed_note += (
                    f" The `--file-key` below is a real file from "
                    f"`{suggested_branch}`'s snapshot — duplications, "
                    f"sources/lines, and sources/scm will all hit valid "
                    f"data. Replace if you'd rather audit a different file."
                )
            else:
                seed_note += (
                    f" The `--file-key` below comes from your project's "
                    f"**main branch** snapshot — the branch-scoped walk on "
                    f"`{suggested_branch}` returned no files. The audit "
                    f"branch may have renamed or removed this file, in "
                    f"which case duplications + sources_lines + sources_scm "
                    f"will return 404 with `Component '<key>' on branch "
                    f"'<X>' not found`. Replace with a file you know exists "
                    f"on the audit branch, or drop the flag entirely."
                )
        else:
            seed_note += (
                " `--file-key` could not be discovered — replace the "
                "placeholder below with a real file key from your Sonar UI's "
                "Code tab (URL contains it as `id=`), or drop the flag and "
                "let duplications + sources_lines + sources_scm skip."
            )
        lines.append(seed_note)
        lines.append("")
        lines.append("Unix shell / PowerShell:")
        lines.append("")
        lines.append("```bash")
        lines.append(
            f"python probe_sonar.py --url <YOUR_SONAR_URL> --token \"$SONAR_TOKEN\" \\\n"
            f"    --project-key {suggested_proj} \\\n"
            f"    --branch {suggested_branch} \\\n"
            f"    --ce-task-id {suggested_task} \\\n"
            f"    --file-key '{suggested_file_key}' \\\n"
            f"    --rule-key {DEFAULT_RULE_KEY}"
        )
        lines.append("```")
        lines.append("")
        lines.append("Windows `cmd`:")
        lines.append("")
        lines.append("```bat")
        lines.append(
            f"python probe_sonar.py --url <YOUR_SONAR_URL> --token \"%SONAR_TOKEN%\" ^\n"
            f"    --project-key {suggested_proj} ^\n"
            f"    --branch {suggested_branch} ^\n"
            f"    --ce-task-id {suggested_task} ^\n"
            f"    --file-key \"{suggested_file_key}\" ^\n"
            f"    --rule-key {DEFAULT_RULE_KEY}"
        )
        lines.append("```")
        lines.append("")

        digest_path = self.results_dir / "discover.md"
        digest_path.write_text("\n".join(lines), encoding="utf-8")
        print(f"\n[probe] wrote discovery digest → {digest_path}")
        print("[probe] open it locally, pick values, then re-run with the suggested args.")
        print("[probe] (this file contains real project keys — redact before sharing.)")


# ---------------------------------------------------------------------------
# Helpers (copied verbatim from probe_bamboo.py for redact.py / bundle.py
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


def _safe_filename(key: str) -> str:
    """Sanitize a Sonar project/component key for use as a filename.

    Sonar keys can contain ``:``, ``/``, ``.``, ``-``. Replace anything
    that's not alphanumeric / dash / underscore with underscore so the
    resulting raw/<name>.json path stays portable across OSes.
    """
    return "".join(c if c.isalnum() or c in "-_" else "_" for c in key)


def _collect_top_project_keys(raw_path: Path, limit: int) -> list[str]:
    """Pull the first `limit` project keys from a /api/components/search response.

    Sonar's response shape: ``{"components": [{"key": ..., "qualifier": "TRK"}, ...], "paging": {...}}``.
    """
    body = _read_raw_body(raw_path)
    if not isinstance(body, dict):
        return []
    comps = body.get("components")
    if not isinstance(comps, list):
        return []
    out: list[str] = []
    for c in comps:
        if isinstance(c, dict) and isinstance(c.get("key"), str):
            out.append(c["key"])
            if len(out) >= limit:
                break
    return out


def _extract_hotspot_key(raw_path: Path) -> Optional[str]:
    """Pull the first hotspot key from a /api/hotspots/search response.

    Used to auto-seed /api/hotspots/show without an extra CLI flag.
    Sonar's hotspot keys are UUIDs, e.g. ``61675d7c-8dce-4ff6-9066-…``.
    """
    body = _read_raw_body(raw_path)
    if not isinstance(body, dict):
        return None
    hotspots = body.get("hotspots")
    if not isinstance(hotspots, list):
        return None
    for h in hotspots:
        if isinstance(h, dict) and isinstance(h.get("key"), str):
            return h["key"]
    return None


def _extract_first_file_key(raw_path: Path) -> Optional[str]:
    """Pull the first file (qualifier=FIL) key from a measures/component_tree
    response saved during discover. Used to auto-seed --file-key in the
    discover digest.
    """
    body = _read_raw_body(raw_path)
    if not isinstance(body, dict):
        return None
    comps = body.get("components")
    if not isinstance(comps, list):
        return None
    for c in comps:
        if isinstance(c, dict) and c.get("qualifier") == "FIL":
            key = c.get("key")
            if isinstance(key, str):
                return key
    return None


def _pick_audit_branch(branches_body: Any) -> dict:
    """Pick a branch suitable for an agent / new-code audit from a
    /api/project_branches/list response.

    Preference (highest first):
        1. Most recent non-main branch with qualityGateStatus=ERROR
           (preferred — failing gate = real fixes for the agent to find).
        2. Most recent non-main branch (any gate status).
        3. Main branch (last resort — `inNewCodePeriod=true` returns
           empty/self-comparison there, so this picks a degraded audit).

    Returns ``{branch, reason, main_branch}``. ``branch`` is None when
    the input is malformed or empty.
    """
    main_branch: Optional[str] = None
    recent_failing_branch: Optional[str] = None
    recent_failing_date: Optional[str] = None
    recent_any_branch: Optional[str] = None
    recent_any_date: Optional[str] = None

    if isinstance(branches_body, dict):
        blist = branches_body.get("branches") or []
        if isinstance(blist, list):
            for b in blist:
                if not isinstance(b, dict):
                    continue
                name = b.get("name")
                if not isinstance(name, str):
                    continue
                if b.get("isMain"):
                    main_branch = name
                    continue
                adate = b.get("analysisDate")
                if not isinstance(adate, str):
                    continue
                status_block = b.get("status")
                qg_status = (
                    status_block.get("qualityGateStatus")
                    if isinstance(status_block, dict) else None
                )
                if qg_status == "ERROR" and (
                    recent_failing_date is None or adate > recent_failing_date
                ):
                    recent_failing_date = adate
                    recent_failing_branch = name
                if recent_any_date is None or adate > recent_any_date:
                    recent_any_date = adate
                    recent_any_branch = name

    if recent_failing_branch:
        return {
            "branch": recent_failing_branch,
            "reason": (
                f"most recently analyzed non-main branch with "
                f"qualityGateStatus=ERROR (analysisDate "
                f"{recent_failing_date}); preferred for the agent's "
                f"fix-to-green workflow — a passing branch has nothing "
                f"to validate"
            ),
            "main_branch": main_branch,
        }
    if recent_any_branch:
        return {
            "branch": recent_any_branch,
            "reason": (
                f"most recently analyzed non-main branch "
                f"(analysisDate {recent_any_date}); no non-main branch "
                f"is currently failing the quality gate, so the agent "
                f"audit will mostly exercise read paths"
            ),
            "main_branch": main_branch,
        }
    if main_branch:
        return {
            "branch": main_branch,
            "reason": (
                f"main branch (no non-main branches found); WARNING: "
                f"`inNewCodePeriod=true` on main returns empty/self-"
                f"comparison and doesn't validate new-code surfaces"
            ),
            "main_branch": main_branch,
        }
    return {"branch": None, "reason": "no branch information available", "main_branch": None}


def _extract_ce_task_id(raw_path: Path) -> Optional[str]:
    """Pull the first SUCCESS task id from a /api/ce/activity response.

    Falls back to the first task of any status if none succeeded — better to
    exercise the contract against a FAILED task than skip the probe entirely.
    """
    body = _read_raw_body(raw_path)
    if not isinstance(body, dict):
        return None
    tasks = body.get("tasks")
    if not isinstance(tasks, list):
        return None
    success_id: Optional[str] = None
    fallback_id: Optional[str] = None
    for t in tasks:
        if not isinstance(t, dict):
            continue
        tid = t.get("id")
        if not isinstance(tid, str):
            continue
        if fallback_id is None:
            fallback_id = tid
        if t.get("status") == "SUCCESS":
            success_id = tid
            break
    return success_id or fallback_id


def _extract_discover_sample(
    raw_dir: Path, candidate_keys: list[str],
) -> Optional[dict]:
    """Return ``{project_key, ce_task_id, ce_status, branch, branch_reason,
    file_key, file_key_branch_scoped, main_branch}`` from the first
    candidate that has any data we can latch onto. CE history may be 403
    (admin-gated); branch list and file probe are typically available —
    so we still emit a partial sample even when CE is empty/forbidden.

    Branch picking is delegated to ``_pick_audit_branch``. CE branch is
    used as a soft override when the helper picks main but CE recorded a
    non-main analysis (rare but possible).
    """
    for pkey in candidate_keys:
        ce_body = _read_raw_body(
            raw_dir / f"discover_ce_{_safe_filename(pkey)}.json"
        )
        branches_body = _read_raw_body(
            raw_dir / f"discover_branches_{_safe_filename(pkey)}.json"
        )

        # Branch-scoped file walk is the primary source; fallback walk
        # (against main) is the secondary. The fallback is only present
        # when the branch-scoped walk returned no FIL components.
        file_key_branch_scoped = _extract_first_file_key(
            raw_dir / f"discover_files_{_safe_filename(pkey)}.json"
        )
        file_key_main_fallback = _extract_first_file_key(
            raw_dir / f"discover_files_main_{_safe_filename(pkey)}.json"
        )
        file_key = file_key_branch_scoped or file_key_main_fallback

        ce_task_id: Optional[str] = None
        ce_status: Optional[str] = None
        ce_branch: Optional[str] = None
        if isinstance(ce_body, dict):
            tasks = ce_body.get("tasks")
            if isinstance(tasks, list):
                first = next((t for t in tasks if isinstance(t, dict)), None)
                if first:
                    ce_task_id = first.get("id")
                    ce_status = first.get("status")
                    ce_branch = first.get("branch")

        picked = _pick_audit_branch(branches_body)
        branch = picked.get("branch")
        branch_reason = picked.get("reason") or "no branch information available"
        main_branch = picked.get("main_branch")

        # CE branch override is rare-but-possible: if the helper picked main
        # but CE recorded a non-main analysis, prefer the CE one. This only
        # happens on Sonar setups that include `branch` on main-branch CE
        # tasks (most don't).
        if branch == main_branch and ce_branch and ce_branch != main_branch:
            branch = ce_branch
            branch_reason = (
                f"branch reported by latest CE task (overriding main pick)"
            )

        if not (ce_task_id or branch or file_key):
            continue
        return {
            "project_key": pkey,
            "ce_task_id": ce_task_id,
            "ce_status": ce_status,
            "branch": branch,
            "branch_reason": branch_reason,
            "file_key": file_key,
            "file_key_branch_scoped": bool(file_key_branch_scoped),
            "main_branch": main_branch,
        }
    return None


def _edition_capability_note(edition: str) -> str:
    """Render the implications of `edition` for plugin features.

    Sonar edition gates significant API surface area. The note below is what
    the recommendations doc will key off of when deciding which plugin
    features should degrade or hide on the user's tier.
    """
    edition_lower = edition.lower()
    if edition_lower == "community":
        return (
            "_**Community implications for the plugin (post-25.x rebrand):**_\n"
            "- SonarQube 25.x renamed Community Edition → **Community Build** "
            "and **moved multi-branch analysis to the free tier**. The `edition` "
            "field still reports `community`.\n"
            "- `branch=` IS honored on `/api/issues/search`, "
            "`/api/measures/component_tree`, `/api/qualitygates/project_status`, "
            "`/api/measures/component`, `/api/hotspots/search` → branch-aware "
            "Quality tab views work as expected.\n"
            "- `/api/project_branches/list` returns **all branches** with full "
            "metadata (name, isMain, type, status, analysisDate, branchId, "
            "excludedFromPurge).\n"
            "- `/api/hotspots/search` is **available** → Security Hotspots "
            "section populates. Was 404 pre-25.x.\n"
            "- `/api/new_code_periods/show` and `/api/ce/activity` may return "
            "**403 Insufficient privileges** for non-admin tokens (permission-"
            "gated). Plugin's `getNewCodePeriod()` and `getAnalysisTasks()` "
            "fail silently for non-admin users — needs graceful empty-state "
            "handling.\n"
            "- Pre-25.x Community Edition behaved very differently (branch "
            "params ignored, hotspots 404, only main branch in branches/list); "
            "if you see those symptoms your server is on an older Sonar."
        )
    if edition_lower == "developer":
        return (
            "_**Developer implications for the plugin:**_\n"
            "- Full branch + new-code-period support on issues / measures / "
            "gate / new_code_periods.\n"
            "- `/api/hotspots/search` available → Security Hotspots tile lights up.\n"
            "- Pull-request analyses also exposed via `pullRequest=` on issues "
            "+ measures (not currently used by plugin — feature candidate)."
        )
    if edition_lower == "enterprise":
        return (
            "_**Enterprise implications for the plugin:**_\n"
            "- All Developer-edition surfaces +\n"
            "- `/api/governance/*`, `/api/portfolios/*`, "
            "`/api/views/show` available — none of these are wired in the "
            "plugin today. Possible feature: portfolio-level Quality tab."
        )
    if edition_lower == "datacenter":
        return (
            "_**Data Center implications for the plugin:**_\n"
            "- All Enterprise-edition surfaces +\n"
            "- `/api/system/health` (system-passcode-gated, NOT user-token).\n"
            "- HA-aware endpoints — plugin is HA-agnostic so this is no-op for now."
        )
    return (
        f"_Unknown edition value `{edition}`. Treat as Community-tier "
        "(no branch params, no hotspots) until disambiguated._"
    )


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> int:
    p = argparse.ArgumentParser(
        description="Read-only SonarQube Server / Community Build probe for Workflow Orchestrator plugin",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    p.add_argument("--url", required=True,
                   help="SonarQube base URL, e.g. https://sonar.example.com")
    p.add_argument("--token", required=True,
                   help="User token (sent as Authorization: Bearer)")
    p.add_argument("--project-key",
                   help="Sonar project key (enables project-scoped probes — "
                        "branches, gate, issues, measures, ce, hotspots)")
    p.add_argument("--branch",
                   help="Branch name (enables branch param on issues / measures / "
                        "gate / new_code_periods; ignored on Community)")
    p.add_argument("--file-key",
                   help="File component key, e.g. 'PROJ:src/main/java/Foo.java' "
                        "(enables /api/duplications/show + /api/sources/lines)")
    p.add_argument("--rule-key",
                   help=f"Rule key for /api/rules/show (default: {DEFAULT_RULE_KEY})")
    p.add_argument("--ce-task-id",
                   help="CE task id for /api/ce/task probe. Without this, the "
                        "sweep lifts a task id from /api/ce/activity if any.")
    p.add_argument("--no-verify", action="store_true",
                   help="Disable TLS verification (self-signed certs)")
    p.add_argument("--versions-only", action="store_true",
                   help="Probe only /api/server/version + /api/system/status + "
                        "/api/navigation/global + /api/authentication/validate "
                        "and exit. Recommended first run.")
    p.add_argument("--discover", action="store_true",
                   help="Discovery walk — finds project / branch / CE task id "
                        "from your PAT's actual permissions and writes "
                        "Result_N/discover.md with a copy-paste full-sweep "
                        "command. Combine with --project-key X to scope to one "
                        "project; without it, walks the first 5 projects "
                        "alphabetically (often unrelated on large instances).")
    p.add_argument("--out", default=str(Path(__file__).parent),
                   help="Parent dir for Result_N/ output (default: alongside the script)")
    args = p.parse_args()

    if not args.token:
        print("ERROR: --token must be non-empty", file=sys.stderr)
        return 2

    if args.discover and args.versions_only:
        print("ERROR: --discover and --versions-only are mutually exclusive.",
              file=sys.stderr)
        return 2

    if args.no_verify:
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
    else:
        mode_label = "full sweep"
    print(f"[probe] target: {args.url}")
    print(f"[probe] output: {results_dir}")
    print(f"[probe] mode:   {mode_label}")
    print()

    probe = SonarProbe(args.url, args.token, verify=not args.no_verify, results_dir=results_dir)

    args_used = {
        "url": args.url,
        "project_key": args.project_key,
        "branch": args.branch,
        "file_key": args.file_key,
        "rule_key": args.rule_key,
        "ce_task_id": args.ce_task_id,
        "no_verify": args.no_verify,
        "versions_only": args.versions_only,
        "discover": args.discover,
    }

    if args.discover:
        probe.run_discover(project_key=args.project_key)
    elif args.versions_only:
        probe.run_versions_only()
    else:
        probe.run_full_sweep(
            project_key=args.project_key,
            branch=args.branch,
            file_key=args.file_key,
            rule_key=args.rule_key,
            ce_task_id=args.ce_task_id,
        )

    probe.write_summary(args_used)
    if args.discover:
        print(f"[probe] done — open {results_dir / 'discover.md'} for copy-paste IDs.")
    else:
        print(f"[probe] done — open {results_dir / 'summary.md'} and paste back to me.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
