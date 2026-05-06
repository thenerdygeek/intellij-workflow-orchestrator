#!/usr/bin/env python3
"""
API Response Probe for Workflow Orchestrator IntelliJ Plugin.

Read-only probe of the five backend services the plugin talks to:
    Bamboo · Jira · SonarQube · Bitbucket Server · Docker Registry (Nexus).

Use it to:
    - Verify the plugin's DTOs match what each server actually returns
    - Diagnose response-shape bugs (e.g. SonarQube period.value vs value)
    - Capture a baseline before/after a server upgrade

This script ONLY makes GET / HEAD requests — no writes, no triggers.
It works on macOS, Linux, and Windows. Standard library only — no pip install.

──────────────────────────────────────────────────────────────────────────────
Quick start
──────────────────────────────────────────────────────────────────────────────

  1. Generate a config template (once per machine):

         python3 scripts/api-probe.py --init scripts/api-probe-config.json

  2. Edit `scripts/api-probe-config.json` and fill in the URLs, tokens, and
     resource keys you want to probe. Sections you leave blank or with
     placeholder tokens are skipped automatically. The file is gitignored.

  3. Run the probe:

         # All five services, censored output (safe to share)
         python3 scripts/api-probe.py --config scripts/api-probe-config.json

         # One service only
         python3 scripts/api-probe.py --config scripts/api-probe-config.json \\
             --services sonar

         # One endpoint only (faster while iterating)
         python3 scripts/api-probe.py --config scripts/api-probe-config.json \\
             --services sonar --endpoints measures_tree

         # Full raw bodies for field-shape debugging (writes <svc>.raw.json
         # next to the censored <svc>.json — see "Sensitive data" below)
         python3 scripts/api-probe.py --config scripts/api-probe-config.json \\
             --raw

  Windows users: same commands, just use `python` instead of `python3` if the
  Python launcher is mapped that way. PowerShell/cmd both work.

──────────────────────────────────────────────────────────────────────────────
Output files (in scripts/api-probe-results/, gitignored)
──────────────────────────────────────────────────────────────────────────────

  bamboo.json            Censored — Bamboo CI/automation plan responses
  jira.json              Censored — Jira issue / sprint / dev-status responses
  sonar.json             Censored — SonarQube measures / quality gate / issues
  bitbucket.json         Censored — Bitbucket PR / branch / activity responses
  docker-registry.json   Censored — Nexus tag list / manifest HEAD checks
  _metadata.json         Run timestamp, services probed, config keys used

  When --raw is passed, each service ALSO writes <service>.raw.json with
  full response bodies, then runs a redaction pass over those files to mask
  obvious sensitive fields (tokens, emails, display names, user keys,
  avatar URLs). Numbers, structural fields, and metric names stay intact —
  that's the point of raw mode.

──────────────────────────────────────────────────────────────────────────────
Sensitive data
──────────────────────────────────────────────────────────────────────────────

  Default (no --raw):
      Every value in the response body is replaced with a type placeholder
      (`<str>`, `<int>`, etc.). Only keys, types, status codes, errors, and
      array lengths survive. These files are safe to commit or share.

  --raw mode:
      Real numbers and metric names are kept (needed for debugging field
      shapes). Known sensitive fields are still redacted before write —
      see REDACT_KEYS below for the exact list. If you suspect a field
      isn't covered, add it to REDACT_KEYS and re-run; the script never
      writes redaction-bypassed output.

  In all modes, request URL paths and Authorization headers are NOT logged.
  The config file (`api-probe-config.json`) is gitignored because it
  contains tokens. The results directory (`api-probe-results/`) is
  gitignored too.

──────────────────────────────────────────────────────────────────────────────
"""

import argparse
import base64
import json
import os
import ssl
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Optional
from urllib.parse import quote, urlencode, urljoin
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError


# ─── Redaction (raw mode) ────────────────────────────────────────────────────

# Field names that hold sensitive identifiers across the services we probe.
# Match is case-insensitive and exact (a key called "email" matches; "newEmail"
# does not). Add new entries here as new field types are discovered — this is
# the single source of truth for what gets masked in --raw output.
REDACT_KEYS = frozenset({
    # Auth / tokens
    "token", "accessToken", "refreshToken", "authToken", "apiToken",
    "password", "secret", "apiKey", "privateKey",
    # User identifiers
    "email", "emailAddress", "mail",
    "displayName", "fullName", "userName", "username",
    "authorName", "committerName", "creatorName", "ownerName",
    "authorEmail", "committerEmail",
    "userSlug",
    # Note: deliberately NOT redacting "key", "user", "name", "slug" — those
    # are too broad and would mask Sonar component keys, Jira issue keys,
    # project slugs, and metric names that we need to see for shape debugging.
    # URLs that often embed credentials or user IDs
    "avatarUrl", "selfUrl", "self",
    # Free-form text that may contain anything
    "description", "comment", "message", "summary",
    # Bamboo job artifacts and log paths sometimes embed user/project structure
    "buildLogUrl", "downloadUrl", "log",
})

# Regex masks applied to ALL string leaves in --raw mode (independent of key
# name). These catch sensitive patterns embedded inside larger strings —
# e.g. a token referenced in a free-form description, or an email inside a
# git log message.
import re as _re
_REDACT_PATTERNS = [
    # Email addresses
    (_re.compile(r"[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}"), "<email>"),
    # Bearer/Basic tokens accidentally in URLs
    (_re.compile(r"(Bearer\s+|Basic\s+|token\s+)[A-Za-z0-9._\-]{8,}", _re.IGNORECASE), r"\1<redacted>"),
    # Long base64-ish blobs (likely tokens)
    (_re.compile(r"\b[A-Za-z0-9+/]{40,}={0,2}\b"), "<redacted-blob>"),
]


def redact_value(val: Any, parent_key: str = "") -> Any:
    """Mask sensitive fields in raw responses while preserving numeric values
    and structural shape (the things we actually need for debugging).

    A key matches REDACT_KEYS case-insensitively. String leaves are also
    pattern-matched against email/token regexes regardless of key name."""
    if isinstance(val, dict):
        return {k: redact_value(v, parent_key=k) for k, v in val.items()}
    if isinstance(val, list):
        return [redact_value(v, parent_key=parent_key) for v in val]
    if isinstance(val, str):
        if parent_key.lower() in {k.lower() for k in REDACT_KEYS} and val:
            return "<redacted>"
        out = val
        for pattern, replacement in _REDACT_PATTERNS:
            out = pattern.sub(replacement, out)
        return out
    return val


# ─── Censoring (default mode) ────────────────────────────────────────────────

def censor_value(val: Any, depth: int = 0) -> Any:
    """Recursively censor all values. Preserves only keys, types, and array lengths."""
    if depth > 20:
        return "<...>"

    if isinstance(val, dict):
        return {k: censor_value(v, depth + 1) for k, v in val.items()}
    elif isinstance(val, list):
        if not val:
            return []
        # Show structure of first item only + count
        sample = [censor_value(val[0], depth + 1)]
        if len(val) > 1:
            sample.append(f"<...{len(val) - 1} more>")
        return sample
    elif isinstance(val, str):
        return "<str>"
    elif isinstance(val, bool):
        return val
    elif isinstance(val, int):
        return "<int>"
    elif isinstance(val, float):
        return "<float>"
    elif val is None:
        return None
    else:
        return "<...>"

    return result


# ─── HTTP helpers ────────────────────────────────────────────────────────────

class ApiClient:
    def __init__(self, base_url: str, auth_header: dict, name: str, timeout: int = 30):
        self.base_url = base_url.rstrip("/")
        self.auth_header = auth_header
        self.name = name
        self.timeout = timeout
        # Allow self-signed certs in corporate environments
        self.ssl_ctx = ssl.create_default_context()
        self.ssl_ctx.check_hostname = False
        self.ssl_ctx.verify_mode = ssl.CERT_NONE

    def _request(self, method: str, path: str, params: dict = None,
                 raw: bool = False) -> dict:
        """Make a read-only HTTP request, returns {status, url, body/error}.
        SAFETY: Only GET and HEAD are allowed — no writes, no triggers."""
        if method not in ("GET", "HEAD"):
            return {"url": path, "method": method, "error": "Only GET/HEAD allowed (safety guard)"}

        url = f"{self.base_url}{path}"
        if params:
            url += ("&" if "?" in url else "?") + urlencode(params)

        headers = {**self.auth_header, "Accept": "application/json"}
        req = Request(url, headers=headers, method=method)

        try:
            resp = urlopen(req, timeout=self.timeout, context=self.ssl_ctx)
            content_type = resp.headers.get("Content-Type", "")
            body_bytes = resp.read()
            body_text = body_bytes.decode("utf-8", errors="replace")

            result = {
                "url": path,
                "method": method,
                "status_code": resp.status,
                "content_type": content_type,
            }

            if raw or "text/" in content_type or "yaml" in content_type:
                result["body_type"] = "text"
                result["body_length"] = len(body_text)
                result["body_preview"] = f"<text content, length={len(body_text)}>"
            else:
                try:
                    result["body"] = json.loads(body_text)
                except Exception:
                    result["body_type"] = "non-json"
                    result["body_length"] = len(body_text)
                    result["body_preview"] = body_text[:200]
            return result

        except HTTPError as e:
            body = ""
            try:
                body = e.read().decode("utf-8", errors="replace")[:500]
            except Exception:
                pass
            return {
                "url": path, "method": method,
                "status_code": e.code, "error": e.reason,
                "error_body_preview": body,
            }
        except (URLError, OSError) as e:
            return {"url": path, "method": method, "error": str(e)}

    def get(self, path: str, params: dict = None, raw: bool = False) -> dict:
        return self._request("GET", path, params=params, raw=raw)

    def head(self, path: str) -> dict:
        return self._request("HEAD", path)


# ─── Probe definitions ───────────────────────────────────────────────────────

def _run_endpoints(client: ApiClient, endpoints: list, results: list,
                    endpoint_filter: set = None, raw_names: set = None):
    """Run a list of (name, path, params) endpoints, appending to results."""
    raw_names = raw_names or set()
    if endpoint_filter:
        endpoints = [(n, p, q) for n, p, q in endpoints if n in endpoint_filter]
    for name, path, params in endpoints:
        print(f"    {name}...", end=" ", flush=True)
        is_raw = name in raw_names
        resp = client.get(path, params=params, raw=is_raw)
        results.append({"endpoint": name, **resp})
        status = resp.get("status_code", resp.get("error", "?"))
        print(f"{status}")
        time.sleep(0.2)


def _extract_from_body(resp: dict, *keys):
    """Walk a nested dict path to extract a value. Returns None on any miss."""
    val = resp.get("body")
    for k in keys:
        if isinstance(val, dict):
            val = val.get(k)
        elif isinstance(val, list) and isinstance(k, int) and k < len(val):
            val = val[k]
        else:
            return None
    return val


def _probe_bamboo_plan(label: str, client: ApiClient, plan_key: str, branch: str,
                       results: list, endpoint_filter: set, raw_names: set):
    """Probe a single Bamboo plan — auto-discovers job keys and branch keys."""
    prefix = f"{label}_" if label else ""

    # ── Plan-specific endpoints ──────────────────────────────────────────
    endpoints = [
        (f"{prefix}plan_branches", f"/rest/api/latest/plan/{plan_key}/branch", {"max-results": "100"}),
        (f"{prefix}plan_variables", f"/rest/api/latest/plan/{plan_key}/variable", {}),
        (f"{prefix}latest_result", f"/rest/api/latest/result/{plan_key}/latest",
         {"expand": "stages.stage.results.result"}),
        (f"{prefix}recent_results", f"/rest/api/latest/result/{plan_key}",
         {"max-results": "5", "expand": "results.result.stages.stage,results.result.variables"}),
        (f"{prefix}recent_results_minimal", f"/rest/api/latest/result/{plan_key}",
         {"max-results": "5"}),
        (f"{prefix}running_queued", f"/rest/api/latest/result/{plan_key}",
         {"includeAllStates": "true", "max-results": "5"}),
    ]

    if branch:
        encoded_branch = quote(branch, safe="")
        endpoints.append(
            (f"{prefix}latest_result_branch",
             f"/rest/api/latest/result/{plan_key}/branch/{encoded_branch}/latest",
             {"expand": "stages.stage.results.result"})
        )

    _run_endpoints(client, endpoints, results, endpoint_filter, raw_names)

    # ── Auto-discover from results ───────────────────────────────────────
    latest_resp = next((r for r in results if r["endpoint"] == f"{prefix}latest_result"
                        and r.get("status_code") == 200), None)
    branches_resp = next((r for r in results if r["endpoint"] == f"{prefix}plan_branches"
                          and r.get("status_code") == 200), None)

    # Extract job result key from stages
    job_result_key = None
    if latest_resp:
        stages = _extract_from_body(latest_resp, "stages", "stage")
        if isinstance(stages, list):
            for stage in stages:
                stage_results = stage.get("results", {}).get("result", []) if isinstance(stage, dict) else []
                for job in stage_results:
                    if isinstance(job, dict) and job.get("buildResultKey"):
                        job_result_key = job["buildResultKey"]
                        break
                if job_result_key:
                    break
        if job_result_key:
            print(f"    [auto:{label or 'plan'}] job_result_key = {job_result_key}")

    # Extract build result key (plan-level)
    result_key = None
    if latest_resp:
        result_key = _extract_from_body(latest_resp, "buildResultKey")
        if result_key:
            print(f"    [auto:{label or 'plan'}] result_key = {result_key}")

    # Extract first enabled branch plan key
    branch_plan_key = None
    if branches_resp:
        branches_list = _extract_from_body(branches_resp, "branches", "branch")
        if isinstance(branches_list, list):
            for b in branches_list:
                if isinstance(b, dict) and b.get("enabled", False) and b.get("key"):
                    branch_plan_key = b["key"]
                    break
        if branch_plan_key:
            print(f"    [auto:{label or 'plan'}] branch_plan_key = {branch_plan_key}")

    # ── Branch-specific ──────────────────────────────────────────────────
    if branch_plan_key:
        branch_eps = [
            (f"{prefix}branch_latest_result", f"/rest/api/latest/result/{branch_plan_key}/latest",
             {"expand": "stages.stage.results.result"}),
            (f"{prefix}branch_recent_results", f"/rest/api/latest/result/{branch_plan_key}",
             {"max-results": "5", "expand": "results.result.stages.stage"}),
            (f"{prefix}branch_recent_results_minimal", f"/rest/api/latest/result/{branch_plan_key}",
             {"max-results": "5"}),
            (f"{prefix}branch_variables", f"/rest/api/latest/plan/{branch_plan_key}/variable", {}),
        ]
        _run_endpoints(client, branch_eps, results, endpoint_filter, raw_names)

    # ── Build result (plan-level) ────────────────────────────────────────
    if result_key:
        build_eps = [
            (f"{prefix}build_result", f"/rest/api/latest/result/{result_key}",
             {"expand": "stages.stage.results.result"}),
            (f"{prefix}build_variables", f"/rest/api/latest/result/{result_key}",
             {"expand": "variables.variable"}),
            (f"{prefix}build_log_plan", f"/download/{result_key}/build_logs/{result_key}.log", {}),
            (f"{prefix}build_artifacts", f"/rest/api/latest/result/{result_key}",
             {"expand": "artifacts.artifact"}),
        ]
        _run_endpoints(client, build_eps, results, endpoint_filter, raw_names)

    # ── Job-level ────────────────────────────────────────────────────────
    if job_result_key:
        job_eps = [
            (f"{prefix}job_result", f"/rest/api/latest/result/{job_result_key}",
             {"expand": "stages.stage"}),
            (f"{prefix}job_log", f"/download/{job_result_key}/build_logs/{job_result_key}.log", {}),
            (f"{prefix}job_test_results", f"/rest/api/latest/result/{job_result_key}",
             {"expand": "testResults.failedTests.testResult,testResults.successfulTests.testResult"}),
            (f"{prefix}job_artifacts", f"/rest/api/latest/result/{job_result_key}",
             {"expand": "artifacts.artifact"}),
        ]
        _run_endpoints(client, job_eps, results, endpoint_filter, raw_names)


def probe_bamboo(cfg: dict, output_dir: Path, endpoint_filter: set = None, raw: bool = False):
    """Probe Bamboo API endpoints for CI plan and automation suite plan."""
    client = ApiClient(
        cfg["url"], {"Authorization": f"Bearer {cfg['token']}"}, "bamboo"
    )
    results = []
    raw_names = {"plan_specs", "build_log_plan", "job_log",
                 "ci_build_log_plan", "ci_job_log",
                 "suite_build_log_plan", "suite_job_log"}

    print(f"\n  [Bamboo] Base: {cfg['url']}")

    # ── Phase 1: Connection + Discovery ──────────────────────────────────
    phase1 = [
        ("currentUser", "/rest/api/latest/currentUser", {}),
        ("plans", "/rest/api/latest/plan", {"expand": "plans.plan", "max-results": "25"}),
        ("projects", "/rest/api/latest/project", {"max-results": "25"}),
    ]
    _run_endpoints(client, phase1, results, endpoint_filter, raw_names)

    # ── Phase 2: CI plan (builds the service, has docker tag in job log) ──
    ci_plan_key = cfg.get("ci_plan_key", "") or cfg.get("plan_key", "")
    ci_branch = cfg.get("ci_branch", "") or cfg.get("branch", "")
    if ci_plan_key:
        print(f"    --- CI plan: {ci_plan_key} ---")
        _probe_bamboo_plan("ci", client, ci_plan_key, ci_branch,
                           results, endpoint_filter, raw_names)

    # ── Phase 3: Automation suite plan (has dockerTagsAsJson in variables) ──
    suite_plan_key = cfg.get("suite_plan_key", "")
    suite_branch = cfg.get("suite_branch", "")
    if suite_plan_key:
        print(f"    --- Suite plan: {suite_plan_key} ---")
        _probe_bamboo_plan("suite", client, suite_plan_key, suite_branch,
                           results, endpoint_filter, raw_names)

    if not ci_plan_key and not suite_plan_key:
        print("    [SKIP] No ci_plan_key or suite_plan_key configured")

    save_results("bamboo", results, output_dir, raw=raw)


def probe_jira(cfg: dict, output_dir: Path, endpoint_filter: set = None, raw: bool = False):
    """Probe Jira API endpoints."""
    client = ApiClient(
        cfg["url"], {"Authorization": f"Bearer {cfg['token']}"}, "jira"
    )
    results = []

    print(f"\n  [Jira] Base: {cfg['url']}")

    # --- Connection ---
    endpoints = [
        ("myself", "/rest/api/2/myself", {}),
    ]

    # --- Boards & Sprints ---
    board_id = cfg.get("board_id", "")
    if board_id:
        endpoints += [
            ("boards", "/rest/agile/1.0/board", {"maxResults": "10"}),
            ("active_sprints", f"/rest/agile/1.0/board/{board_id}/sprint",
             {"state": "active"}),
            ("closed_sprints", f"/rest/agile/1.0/board/{board_id}/sprint",
             {"state": "closed", "startAt": "0", "maxResults": "5"}),
        ]

    sprint_id = cfg.get("sprint_id", "")
    if sprint_id:
        endpoints.append(
            ("sprint_issues", f"/rest/agile/1.0/sprint/{sprint_id}/issue",
             {"maxResults": "10"})
        )

    # --- Issues ---
    issue_key = cfg.get("issue_key", "")
    if issue_key:
        endpoints += [
            ("issue_detail", f"/rest/api/2/issue/{issue_key}", {"expand": "issuelinks"}),
            ("issue_transitions", f"/rest/api/2/issue/{issue_key}/transitions",
             {"expand": "transitions.fields"}),
            ("issue_comments", f"/rest/api/2/issue/{issue_key}/comment",
             {"maxResults": "5", "orderBy": "-created"}),
            ("issue_worklogs", f"/rest/api/2/issue/{issue_key}/worklog",
             {"maxResults": "5"}),
        ]

    # --- Search ---
    project_key = cfg.get("project_key", "")
    if project_key:
        jql = quote(f"project = {project_key} ORDER BY updated DESC", safe="")
        endpoints.append(
            ("search", "/rest/api/2/search",
             {"jql": f"project = {project_key} ORDER BY updated DESC",
              "maxResults": "5",
              "fields": "summary,status,issuetype,priority,assignee"})
        )

    # --- Dev Status ---
    issue_id = cfg.get("issue_id", "")
    if issue_id:
        endpoints += [
            ("dev_status_branches",
             "/rest/dev-status/1.0/issue/detail",
             {"issueId": issue_id, "applicationType": "stash", "dataType": "branch"}),
            ("dev_status_prs",
             "/rest/dev-status/1.0/issue/detail",
             {"issueId": issue_id, "applicationType": "stash", "dataType": "pullrequest"}),
        ]

    if endpoint_filter:
        endpoints = [(n, p, q) for n, p, q in endpoints if n in endpoint_filter]

    for name, path, params in endpoints:
        print(f"    {name}...", end=" ", flush=True)
        resp = client.get(path, params=params)
        results.append({"endpoint": name, **resp})
        status = resp.get("status_code", resp.get("error", "?"))
        print(f"{status}")
        time.sleep(0.2)

    save_results("jira", results, output_dir, raw=raw)


def probe_sonar(cfg: dict, output_dir: Path, endpoint_filter: set = None, raw: bool = False):
    """Probe all SonarQube API endpoints."""
    client = ApiClient(
        cfg["url"], {"Authorization": f"Bearer {cfg['token']}"}, "sonar"
    )
    results = []

    print(f"\n  [SonarQube] Base: {cfg['url']}")

    endpoints = [
        ("validate", "/api/authentication/validate", {}),
    ]

    project_key = cfg.get("project_key", "")
    branch = cfg.get("branch", "")
    # Mirrors SonarApiClient.DEFAULT_METRIC_KEYS so the probe reproduces what
    # the plugin actually requests. The Coverage tab + new-code filter break
    # silently if any of these are missing from the response.
    plugin_metric_keys = (
        "coverage,line_coverage,branch_coverage,uncovered_lines,uncovered_conditions,lines_to_cover,"
        "new_coverage,new_branch_coverage,new_uncovered_lines,new_uncovered_conditions,new_lines_to_cover,"
        "bugs,vulnerabilities,code_smells,"
        "new_bugs,new_vulnerabilities,new_code_smells,"
        "sqale_index,sqale_rating,duplicated_lines_density,complexity,cognitive_complexity,"
        "reliability_rating,security_rating"
    )
    project_health_metric_keys = (
        "sqale_index,sqale_rating,duplicated_lines_density,cognitive_complexity,"
        "reliability_rating,security_rating,coverage,branch_coverage"
    )
    if project_key:
        branch_params = {"branch": branch} if branch else {}
        endpoints += [
            ("branches", "/api/project_branches/list",
             {"project": project_key}),
            ("quality_gate", "/api/qualitygates/project_status",
             {"projectKey": project_key, **branch_params}),
            ("issues", "/api/issues/search",
             {"componentKeys": project_key, "resolved": "false", "ps": "10",
              **branch_params}),
            # Full plugin metric set, with additionalFields=period — required for
            # new_* metrics, which Sonar returns under `period.value` not `value`.
            # If the plugin's CoverageMapper reads `value` directly while this
            # call is in flight, every new_* field comes back null/empty.
            ("measures_tree", "/api/measures/component_tree",
             {"component": project_key, "metricKeys": plugin_metric_keys,
              "qualifiers": "FIL", "ps": "10",
              "additionalFields": "period", **branch_params}),
            ("project_measures", "/api/measures/component",
             {"component": project_key,
              "metricKeys": project_health_metric_keys,
              "additionalFields": "period", **branch_params}),
            ("ce_activity", "/api/ce/activity",
             {"component": project_key, "ps": "5"}),
            ("new_code_period", "/api/new_code_periods/show",
             {"project": project_key, **({"branch": branch} if branch else {})}),
            ("hotspots", "/api/hotspots/search",
             {"project": project_key, "ps": "10", **branch_params}),
            ("search_projects", "/api/components/search",
             {"qualifiers": "TRK", "q": project_key.split(":")[-1], "ps": "10"}),
        ]

    if endpoint_filter:
        endpoints = [(n, p, q) for n, p, q in endpoints if n in endpoint_filter]

    for name, path, params in endpoints:
        print(f"    {name}...", end=" ", flush=True)
        resp = client.get(path, params=params)
        results.append({"endpoint": name, **resp})
        status = resp.get("status_code", resp.get("error", "?"))
        print(f"{status}")
        time.sleep(0.2)

    save_results("sonar", results, output_dir, raw=raw)


def probe_bitbucket(cfg: dict, output_dir: Path, endpoint_filter: set = None, raw: bool = False):
    """Probe all Bitbucket Server API endpoints."""
    client = ApiClient(
        cfg["url"], {"Authorization": f"Bearer {cfg['token']}"}, "bitbucket"
    )
    results = []

    print(f"\n  [Bitbucket] Base: {cfg['url']}")

    endpoints = [
        ("whoami", "/plugins/servlet/applinks/whoami", {}),
        ("projects", "/rest/api/1.0/projects", {"limit": "10"}),
    ]

    project_key = cfg.get("project_key", "")
    repo_slug = cfg.get("repo_slug", "")
    if project_key and repo_slug:
        endpoints += [
            ("branches", f"/rest/api/1.0/projects/{project_key}/repos/{repo_slug}/branches",
             {"limit": "25", "orderBy": "MODIFICATION"}),
            ("default_branch",
             f"/rest/api/1.0/projects/{project_key}/repos/{repo_slug}/default-branch", {}),
            ("prs_open",
             f"/rest/api/1.0/projects/{project_key}/repos/{repo_slug}/pull-requests",
             {"state": "OPEN", "limit": "10"}),
            ("prs_merged",
             f"/rest/api/1.0/projects/{project_key}/repos/{repo_slug}/pull-requests",
             {"state": "MERGED", "limit": "5"}),
            ("users", "/rest/api/1.0/users", {"filter": "a", "limit": "5"}),
        ]

        # PR-specific
        pr_id = cfg.get("pr_id", "")
        if pr_id:
            pr_base = f"/rest/api/1.0/projects/{project_key}/repos/{repo_slug}/pull-requests/{pr_id}"
            endpoints += [
                ("pr_detail", pr_base, {}),
                ("pr_activities", f"{pr_base}/activities", {"limit": "10"}),
                ("pr_merge_status", f"{pr_base}/merge", {}),
                ("pr_changes", f"{pr_base}/changes", {"limit": "25"}),
                ("pr_commits", f"{pr_base}/commits", {"limit": "10"}),
                ("pr_diff", f"{pr_base}/diff", {}),
            ]

        # Merge strategies
        endpoints.append(
            ("merge_strategies",
             f"/rest/api/1.0/projects/{project_key}/repos/{repo_slug}/settings/pull-requests/git",
             {})
        )

        # Build status for a commit
        commit_id = cfg.get("commit_id", "")
        if commit_id:
            endpoints.append(
                ("build_status", f"/rest/build-status/1.0/commits/{commit_id}", {})
            )

    if endpoint_filter:
        endpoints = [(n, p, q) for n, p, q in endpoints if n in endpoint_filter]

    for name, path, params in endpoints:
        print(f"    {name}...", end=" ", flush=True)
        is_raw = name == "pr_diff"
        resp = client.get(path, params=params, raw=is_raw)
        results.append({"endpoint": name, **resp})
        status = resp.get("status_code", resp.get("error", "?"))
        print(f"{status}")
        time.sleep(0.2)

    save_results("bitbucket", results, output_dir, raw=raw)


def probe_docker_registry(cfg: dict, output_dir: Path, endpoint_filter: set = None, raw: bool = False):
    """Probe Docker Registry v2 endpoints (Nexus uses Basic auth with username:passcode)."""
    username = cfg.get("username", "")
    passcode = cfg.get("passcode", "")
    cred = base64.b64encode(f"{username}:{passcode}".encode()).decode()
    client = ApiClient(
        cfg["url"], {"Authorization": f"Basic {cred}"}, "docker"
    )
    results = []

    print(f"\n  [Docker Registry] Base: {cfg['url']}")

    endpoints = [
        ("v2_check", "/v2/", {}),
    ]

    repo_name = cfg.get("repo_name", "")
    if repo_name:
        endpoints += [
            ("tags_list", f"/v2/{repo_name}/tags/list", {"n": "25"}),
        ]
        tag = cfg.get("tag", "")
        if tag:
            if not endpoint_filter or "manifest_check" in endpoint_filter:
                print(f"    manifest_check...", end=" ", flush=True)
                resp = client.head(f"/v2/{repo_name}/manifests/{tag}")
                results.append({"endpoint": "manifest_check", **resp})
                status = resp.get("status_code", resp.get("error", "?"))
                print(f"{status}")

    if endpoint_filter:
        endpoints = [(n, p, q) for n, p, q in endpoints if n in endpoint_filter]

    for name, path, params in endpoints:
        print(f"    {name}...", end=" ", flush=True)
        resp = client.get(path, params=params)
        results.append({"endpoint": name, **resp})
        status = resp.get("status_code", resp.get("error", "?"))
        print(f"{status}")
        time.sleep(0.2)

    save_results("docker-registry", results, output_dir, raw=raw)


# ─── Output ──────────────────────────────────────────────────────────────────

def save_results(service: str, results: list, output_dir: Path, raw: bool = False):
    """Save results to file. Default behavior censors all values (only keys,
    types, status codes, and errors survive). When raw=True, dumps actual
    response bodies — required when debugging field shapes (e.g. confirming
    SonarQube returns new_* metrics in `period.value` vs top-level `value`).
    Raw output contains real API data; do not commit the file."""
    if raw:
        # Mask known sensitive fields (REDACT_KEYS) and inline patterns
        # (emails, bearer tokens, long base64 blobs) before writing. Numbers
        # and structural fields stay intact — that's the point of raw mode.
        redacted = redact_value(results)
        filepath = output_dir / f"{service}.raw.json"
        with open(filepath, "w") as f:
            json.dump(redacted, f, indent=2, default=str)
        print(f"  -> Saved RAW (sensitive fields redacted) to {filepath}")
        return

    censored = []
    for r in results:
        entry = {}
        for k, v in r.items():
            if k == "body":
                entry[k] = censor_value(v)
            elif k in ("endpoint", "url", "method", "status_code", "content_type",
                        "error", "body_type", "body_length"):
                # Structural metadata — keep as-is
                entry[k] = v
            elif k in ("body_preview", "error_body_preview"):
                entry[k] = "<censored>"
            else:
                entry[k] = v
        censored.append(entry)

    filepath = output_dir / f"{service}.json"
    with open(filepath, "w") as f:
        json.dump(censored, f, indent=2, default=str)
    print(f"  -> Saved to {filepath}")


# ─── Config ──────────────────────────────────────────────────────────────────

CONFIG_TEMPLATE = {
    "_comment": "API Probe config. Fill in URLs and tokens. All fields are optional except url+token per service.",
    "bamboo": {
        "url": "https://bamboo.example.com",
        "token": "YOUR_BAMBOO_PAT",
        "ci_plan_key": "PROJ-BUILD",
        "ci_branch": "develop",
        "suite_plan_key": "PROJ-AUTOMATIONTESTS",
        "suite_branch": ""
    },
    "jira": {
        "url": "https://jira.example.com",
        "token": "YOUR_JIRA_PAT",
        "board_id": "",
        "sprint_id": "",
        "issue_key": "PROJ-123",
        "issue_id": "",
        "project_key": "PROJ"
    },
    "sonar": {
        "url": "https://sonar.example.com",
        "token": "YOUR_SONAR_TOKEN",
        "project_key": "com.example:my-project",
        "branch": ""
    },
    "bitbucket": {
        "url": "https://bitbucket.example.com",
        "token": "YOUR_BITBUCKET_PAT",
        "project_key": "PROJ",
        "repo_slug": "my-repo",
        "pr_id": "",
        "commit_id": ""
    },
    "docker_registry": {
        "url": "https://registry.example.com",
        "username": "YOUR_NEXUS_USER_TOKEN",
        "passcode": "YOUR_NEXUS_USER_TOKEN_PASSCODE",
        "repo_name": "my-service",
        "tag": "1.0.0"
    }
}


def init_config(path: str):
    """Write config template."""
    with open(path, "w") as f:
        json.dump(CONFIG_TEMPLATE, f, indent=2)
    print(f"Config template written to {path}")
    print("Edit it with your URLs, tokens, and resource IDs, then run:")
    print(f"  python3 scripts/api-probe.py --config {path}")


# ─── Main ────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="API Response Probe for Workflow Orchestrator")
    parser.add_argument("--config", type=str, help="Path to config JSON file")
    parser.add_argument("--init", type=str, nargs="?", const="api-probe-config.json",
                        metavar="PATH", help="Generate config template (default: api-probe-config.json)")
    parser.add_argument("--services", type=str, default="all",
                        help="Comma-separated services to probe: bamboo,jira,sonar,bitbucket,docker (default: all)")
    parser.add_argument("--endpoints", type=str, default="",
                        help="Comma-separated endpoint names to run (default: all). E.g. --endpoints job_log,job_result")
    parser.add_argument("--raw", action="store_true",
                        help="Save full response bodies to <service>.raw.json (instead of "
                             "type-placeholder censoring). Numbers and structural fields are "
                             "preserved; sensitive fields (tokens, emails, displayName, etc.) "
                             "are redacted. Required for debugging field-shape questions like "
                             "SonarQube period.value vs value. Output is gitignored.")
    args = parser.parse_args()

    if args.init is not None:
        init_config(args.init)
        return

    if not args.config:
        print("Usage: python3 api-probe.py --config <config.json>")
        print("       python3 api-probe.py --init [path]  (to create config template)")
        sys.exit(1)

    with open(args.config) as f:
        config = json.load(f)

    # Default output dir next to the config file
    config_dir = str(Path(args.config).parent)
    default_output = str(Path(config_dir) / "api-probe-results")
    output_dir = Path(config.get("output_dir", default_output))
    output_dir.mkdir(parents=True, exist_ok=True)

    services = args.services.split(",") if args.services != "all" else [
        "bamboo", "jira", "sonar", "bitbucket", "docker"
    ]
    endpoint_filter = set(args.endpoints.split(",")) if args.endpoints else None

    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"API Probe — {timestamp}")
    print(f"Output: {output_dir}/")
    if endpoint_filter:
        print(f"Endpoints: {', '.join(sorted(endpoint_filter))}")

    probes = {
        "bamboo": (probe_bamboo, "bamboo"),
        "jira": (probe_jira, "jira"),
        "sonar": (probe_sonar, "sonar"),
        "bitbucket": (probe_bitbucket, "bitbucket"),
        "docker": (probe_docker_registry, "docker_registry"),
    }

    for svc in services:
        svc = svc.strip()
        if svc not in probes:
            print(f"\n  [SKIP] Unknown service: {svc}")
            continue
        probe_fn, cfg_key = probes[svc]
        cfg = config.get(cfg_key, {})
        if not cfg.get("url"):
            print(f"\n  [SKIP] {svc}: no url configured")
            continue
        # Docker registry uses username+passcode, others use token
        if cfg_key == "docker_registry":
            if not cfg.get("username") or not cfg.get("passcode"):
                print(f"\n  [SKIP] {svc}: no username/passcode configured")
                continue
        elif not cfg.get("token"):
            print(f"\n  [SKIP] {svc}: no token configured")
            continue
        try:
            probe_fn(cfg, output_dir, endpoint_filter, raw=args.raw)
        except Exception as e:
            print(f"\n  [ERROR] {svc}: {e}")

    # Write metadata
    meta = {
        "timestamp": timestamp,
        "services_probed": services,
        "config_keys": {k: list(v.keys()) if isinstance(v, dict) else type(v).__name__
                        for k, v in config.items() if k != "_comment"},
    }
    with open(output_dir / "_metadata.json", "w") as f:
        json.dump(meta, f, indent=2)

    print(f"\nDone. Results in {output_dir}/")
    if args.raw:
        print("RAW mode: <service>.raw.json files keep numeric values and shape;")
        print("known sensitive fields (tokens, emails, displayName, etc.) are redacted.")
        print("If you spot a sensitive field that wasn't masked, add its key to")
        print("REDACT_KEYS at the top of this script and re-run before sharing.")
    else:
        print("Share the JSON files — all values replaced with type placeholders.")


if __name__ == "__main__":
    main()
