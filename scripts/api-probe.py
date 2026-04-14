#!/usr/bin/env python3
"""
API Response Probe for Workflow Orchestrator IntelliJ Plugin.

Hits all service APIs (Bamboo, Jira, SonarQube, Bitbucket, Docker Registry)
and saves censored JSON responses for analysis.

Usage:
    python3 api-probe.py --config api-probe-config.json

Config file format (create from template):
    python3 api-probe.py --init config.json
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


# ─── Censoring ───────────────────────────────────────────────────────────────

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


def probe_bamboo(cfg: dict, output_dir: Path, endpoint_filter: set = None):
    """Probe Bamboo API endpoints. Auto-discovers job keys and branch keys."""
    client = ApiClient(
        cfg["url"], {"Authorization": f"Bearer {cfg['token']}"}, "bamboo"
    )
    plan_key = cfg.get("plan_key", "")
    results = []
    raw_names = {"plan_specs", "build_log_plan", "job_log"}

    print(f"\n  [Bamboo] Base: {cfg['url']}")

    # ── Phase 1: Connection + Discovery ──────────────────────────────────
    phase1 = [
        ("currentUser", "/rest/api/latest/currentUser", {}),
        ("plans", "/rest/api/latest/plan", {"expand": "plans.plan", "max-results": "25"}),
        ("projects", "/rest/api/latest/project", {"max-results": "25"}),
    ]
    _run_endpoints(client, phase1, results, endpoint_filter, raw_names)

    if not plan_key:
        save_results("bamboo", results, output_dir)
        return

    # ── Phase 2: Plan-specific (includes latest_result for auto-discovery) ──
    phase2 = [
        ("plan_branches", f"/rest/api/latest/plan/{plan_key}/branch", {"max-results": "100"}),
        ("plan_specs", f"/rest/api/latest/plan/{plan_key}/specs", {"format": "YAML"}),
        ("plan_variables", f"/rest/api/latest/plan/{plan_key}/variable", {}),
        ("latest_result", f"/rest/api/latest/result/{plan_key}/latest",
         {"expand": "stages.stage.results.result"}),
        ("recent_results", f"/rest/api/latest/result/{plan_key}",
         {"max-results": "5", "expand": "results.result.stages.stage,results.result.variables"}),
        ("recent_results_minimal", f"/rest/api/latest/result/{plan_key}",
         {"max-results": "5"}),
        ("running_queued", f"/rest/api/latest/result/{plan_key}",
         {"includeAllStates": "true", "max-results": "5"}),
        ("search_plans", "/rest/api/latest/search/plans",
         {"searchTerm": plan_key.split("-")[0], "fuzzy": "true", "max-results": "10"}),
    ]

    branch = cfg.get("branch", "")
    if branch:
        encoded_branch = quote(branch, safe="")
        phase2.append(
            ("latest_result_branch",
             f"/rest/api/latest/result/{plan_key}/branch/{encoded_branch}/latest",
             {"expand": "stages.stage.results.result"})
        )

    _run_endpoints(client, phase2, results, endpoint_filter, raw_names)

    # ── Auto-discover from phase 2 results ───────────────────────────────
    latest_resp = next((r for r in results if r["endpoint"] == "latest_result"
                        and r.get("status_code") == 200), None)
    branches_resp = next((r for r in results if r["endpoint"] == "plan_branches"
                          and r.get("status_code") == 200), None)

    # Extract job result key from latest_result → stages → first job
    job_result_key = cfg.get("job_result_key", "")
    if not job_result_key and latest_resp:
        # Walk: body.stages.stage[0].results.result[0].buildResultKey
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
            print(f"    [auto] job_result_key = {job_result_key}")

    # Extract build result key (plan-level)
    result_key = cfg.get("result_key", "")
    if not result_key and latest_resp:
        result_key = _extract_from_body(latest_resp, "buildResultKey") or ""
        if result_key:
            print(f"    [auto] result_key = {result_key}")

    # Extract first branch plan key
    branch_plan_key = cfg.get("branch_plan_key", "")
    if not branch_plan_key and branches_resp:
        branches_list = _extract_from_body(branches_resp, "branches", "branch")
        if isinstance(branches_list, list):
            for b in branches_list:
                if isinstance(b, dict) and b.get("enabled", False) and b.get("key"):
                    branch_plan_key = b["key"]
                    break
        if branch_plan_key:
            print(f"    [auto] branch_plan_key = {branch_plan_key}")

    # ── Phase 3: Branch-specific ─────────────────────────────────────────
    if branch_plan_key:
        phase3 = [
            ("branch_latest_result", f"/rest/api/latest/result/{branch_plan_key}/latest",
             {"expand": "stages.stage.results.result"}),
            ("branch_recent_results", f"/rest/api/latest/result/{branch_plan_key}",
             {"max-results": "5", "expand": "results.result.stages.stage"}),
            ("branch_recent_results_minimal", f"/rest/api/latest/result/{branch_plan_key}",
             {"max-results": "5"}),
            ("branch_variables", f"/rest/api/latest/plan/{branch_plan_key}/variable", {}),
        ]
        _run_endpoints(client, phase3, results, endpoint_filter, raw_names)

    # ── Phase 4: Build result (plan-level) ───────────────────────────────
    if result_key:
        phase4 = [
            ("build_result", f"/rest/api/latest/result/{result_key}",
             {"expand": "stages.stage.results.result"}),
            ("build_variables", f"/rest/api/latest/result/{result_key}",
             {"expand": "variables.variable"}),
            ("build_log_plan", f"/download/{result_key}/build_logs/{result_key}.log", {}),
            ("build_artifacts", f"/rest/api/latest/result/{result_key}",
             {"expand": "artifacts.artifact"}),
            ("test_results", f"/rest/api/latest/result/{result_key}",
             {"expand": "testResults.failedTests.testResult,testResults.successfulTests.testResult"}),
        ]
        _run_endpoints(client, phase4, results, endpoint_filter, raw_names)

    # ── Phase 5: Job-level (auto-discovered or from config) ──────────────
    if job_result_key:
        phase5 = [
            ("job_result", f"/rest/api/latest/result/{job_result_key}",
             {"expand": "stages.stage"}),
            ("job_log", f"/download/{job_result_key}/build_logs/{job_result_key}.log", {}),
            ("job_test_results", f"/rest/api/latest/result/{job_result_key}",
             {"expand": "testResults.failedTests.testResult,testResults.successfulTests.testResult"}),
            ("job_artifacts", f"/rest/api/latest/result/{job_result_key}",
             {"expand": "artifacts.artifact"}),
        ]
        _run_endpoints(client, phase5, results, endpoint_filter, raw_names)

    save_results("bamboo", results, output_dir)


def probe_jira(cfg: dict, output_dir: Path, endpoint_filter: set = None):
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

    save_results("jira", results, output_dir)


def probe_sonar(cfg: dict, output_dir: Path, endpoint_filter: set = None):
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
            ("measures_tree", "/api/measures/component_tree",
             {"component": project_key, "metricKeys": "coverage,new_coverage,ncloc",
              "qualifiers": "FIL", "ps": "10", **branch_params}),
            ("project_measures", "/api/measures/component",
             {"component": project_key,
              "metricKeys": "coverage,new_coverage,ncloc,bugs,vulnerabilities,code_smells,duplicated_lines_density",
              **branch_params}),
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

    save_results("sonar", results, output_dir)


def probe_bitbucket(cfg: dict, output_dir: Path, endpoint_filter: set = None):
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

    save_results("bitbucket", results, output_dir)


def probe_docker_registry(cfg: dict, output_dir: Path, endpoint_filter: set = None):
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

    save_results("docker-registry", results, output_dir)


# ─── Output ──────────────────────────────────────────────────────────────────

def save_results(service: str, results: list, output_dir: Path):
    """Save censored results to file. Only keys, types, status codes, and errors survive."""
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
        "plan_key": "PROJ-PLAN",
        "branch": "develop",
        "branch_plan_key": "",
        "result_key": "PROJ-PLAN-123",
        "job_result_key": ""
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
            probe_fn(cfg, output_dir, endpoint_filter)
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
    print("Share the JSON files — all sensitive data has been censored.")


if __name__ == "__main__":
    main()
