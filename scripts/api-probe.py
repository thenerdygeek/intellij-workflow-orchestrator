#!/usr/bin/env python3
"""
API Response Probe for Workflow Orchestrator IntelliJ Plugin.

Hits all service APIs (Bamboo, Jira, SonarQube, Bitbucket, Docker Registry)
and saves censored JSON responses for analysis.

Usage:
    python3 scripts/api-probe.py --config scripts/api-probe-config.json

Config file format (create from template):
    python3 scripts/api-probe.py --init
"""

import argparse
import base64
import copy
import hashlib
import json
import os
import re
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

SENSITIVE_KEYS = {
    # Auth / credentials
    "password", "token", "secret", "credential", "authorization",
    "access_token", "refresh_token", "api_key", "apikey", "pat",
    # Personal info
    "email", "emailAddress", "email_address", "mail",
    # URLs that may contain tokens
    "avatarUrl", "avatar_url", "profileUrl",
}

SENSITIVE_KEY_PATTERNS = [
    re.compile(r"(?i).*password.*"),
    re.compile(r"(?i).*secret.*"),
    re.compile(r"(?i).*token.*"),
    re.compile(r"(?i).*credential.*"),
    re.compile(r"(?i).*api.?key.*"),
]

# Keys whose values are variable content that should be censored
VARIABLE_VALUE_KEYS = {
    # Build variables / parameters (these carry docker tags, env configs, etc.)
    "value",
}

# Keys to preserve as-is (structural, non-sensitive)
PRESERVE_KEYS = {
    "key", "name", "shortName", "displayName", "description",
    "state", "lifeCycleState", "status", "type", "enabled",
    "buildNumber", "buildResultKey", "planKey",
    "size", "start-index", "max-result", "startIndex", "maxResult",
    "total", "failed", "passed", "quarantined", "successful",
    "buildDurationInSeconds", "buildDuration",
    "manual", "restartable", "continuable",
    "id", "prId", "sprintId", "boardId", "issueId",
    "summary", "issuetype", "priority", "resolution",
    "role", "approved", "version",
    "open", "closed", "merged", "declined",
    "qualifiers", "qualifier",
    "metric", "metricKeys", "component",
    "severity", "rule", "ruleKey",
    "line", "startLine", "endLine", "startOffset", "endOffset",
    "period", "periods", "leakPeriodDate",
    "canMerge", "vetoes",
    "strategyId", "defaultStrategy",
}


def censor_string(val: str, key: str = "") -> str:
    """Censor a string value, preserving structure hints."""
    if not val:
        return val
    # Keep short structural values (statuses, types, booleans as strings)
    if len(val) <= 30 and val in (
        "true", "false", "null", "Successful", "Failed", "InProgress",
        "Queued", "Pending", "Finished", "OPEN", "MERGED", "DECLINED",
        "APPROVED", "UNAPPROVED", "NEEDS_WORK", "active", "closed",
        "OK", "ERROR", "WARN", "NONE", "BUG", "VULNERABILITY",
        "CODE_SMELL", "SECURITY_HOTSPOT", "BLOCKER", "CRITICAL",
        "MAJOR", "MINOR", "INFO", "scrum", "kanban",
    ):
        return val
    # Preserve plan key patterns (PROJ-PLAN, PROJ-PLAN-123)
    if re.match(r"^[A-Z][A-Z0-9]+-[A-Z][A-Z0-9]+(-\d+)?$", val):
        return val
    # Preserve issue key patterns (PROJ-123)
    if re.match(r"^[A-Z][A-Z0-9]+-\d+$", val):
        return val
    # Preserve numeric strings
    if val.isdigit():
        return val
    # Preserve date-like strings
    if re.match(r"^\d{4}-\d{2}-\d{2}", val):
        return val

    # Hash everything else for consistent but anonymous values
    h = hashlib.sha256(val.encode()).hexdigest()[:8]
    return f"<censored:{h}>"


def censor_value(val: Any, key: str = "", depth: int = 0) -> Any:
    """Recursively censor a JSON value."""
    if depth > 20:
        return "<censored:deep>"

    if isinstance(val, dict):
        return censor_dict(val, depth + 1)
    elif isinstance(val, list):
        # For large lists, keep first 3 items to show structure
        censored = [censor_value(item, key, depth + 1) for item in val[:3]]
        if len(val) > 3:
            censored.append(f"<...{len(val) - 3} more items>")
        return censored
    elif isinstance(val, str):
        return censor_string(val, key)
    elif isinstance(val, (int, float, bool)) or val is None:
        return val
    else:
        return str(val)


def censor_dict(d: dict, depth: int = 0) -> dict:
    """Censor a dictionary, handling sensitive keys and variable values."""
    result = {}
    for k, v in d.items():
        k_lower = k.lower()

        # Always censor sensitive keys
        if k_lower in {s.lower() for s in SENSITIVE_KEYS}:
            result[k] = "<redacted>"
            continue

        # Check pattern-based sensitive keys
        if any(p.match(k) for p in SENSITIVE_KEY_PATTERNS):
            result[k] = "<redacted>"
            continue

        # Censor variable values (build variables, env vars, etc.)
        if k_lower in {s.lower() for s in VARIABLE_VALUE_KEYS}:
            if isinstance(v, str):
                result[k] = f"<variable:{hashlib.sha256(v.encode()).hexdigest()[:8]}>"
            else:
                result[k] = censor_value(v, k, depth)
            continue

        # Preserve known structural keys
        if k in PRESERVE_KEYS:
            if isinstance(v, (dict, list)):
                result[k] = censor_value(v, k, depth)
            else:
                result[k] = v
            continue

        # Default: recurse
        result[k] = censor_value(v, k, depth)

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

def probe_bamboo(cfg: dict, output_dir: Path):
    """Probe all Bamboo API endpoints."""
    client = ApiClient(
        cfg["url"], {"Authorization": f"Bearer {cfg['token']}"}, "bamboo"
    )
    plan_key = cfg.get("plan_key", "")
    results = []

    print(f"\n  [Bamboo] Base: {cfg['url']}")

    # --- Connection / Discovery ---
    endpoints = [
        ("currentUser", "/rest/api/latest/currentUser", {}),
        ("plans", "/rest/api/latest/plan", {"expand": "plans.plan", "max-results": "25"}),
        ("projects", "/rest/api/latest/project", {"max-results": "25"}),
    ]

    if plan_key:
        # --- Plan-specific ---
        endpoints += [
            ("plan_branches", f"/rest/api/latest/plan/{plan_key}/branch", {"max-results": "100"}),
            ("plan_specs", f"/rest/api/latest/plan/{plan_key}/specs", {"format": "YAML"}),
            ("plan_variables", f"/rest/api/latest/plan/{plan_key}/variable", {}),
            ("latest_result", f"/rest/api/latest/result/{plan_key}/latest",
             {"expand": "stages.stage.results.result"}),
            ("recent_results", f"/rest/api/latest/result/{plan_key}",
             {"max-results": "5", "expand": "stages.stage,variables"}),
            ("running_queued", f"/rest/api/latest/result/{plan_key}",
             {"includeAllStates": "true", "max-results": "5"}),
            ("search_plans", "/rest/api/latest/search/plans",
             {"searchTerm": plan_key.split("-")[0], "fuzzy": "true", "max-results": "10"}),
        ]

    # Branch-specific (if branch provided)
    branch = cfg.get("branch", "")
    if plan_key and branch:
        encoded_branch = quote(branch, safe="")
        endpoints.append(
            ("latest_result_branch",
             f"/rest/api/latest/result/{plan_key}/branch/{encoded_branch}/latest",
             {"expand": "stages.stage.results.result"})
        )

    # Build result key specific
    result_key = cfg.get("result_key", "")
    if result_key:
        endpoints += [
            ("build_result", f"/rest/api/latest/result/{result_key}",
             {"expand": "stages.stage"}),
            ("build_variables", f"/rest/api/latest/result/{result_key}",
             {"expand": "variables"}),
            ("build_log", f"/download/{result_key}/build_logs/{result_key}.log", {}),
            ("build_artifacts", f"/rest/api/latest/result/{result_key}",
             {"expand": "artifacts.artifact"}),
            ("test_results", f"/rest/api/latest/result/{result_key}",
             {"expand": "testResults.failedTests.testResult,testResults.successfulTests.testResult"}),
        ]

    for name, path, params in endpoints:
        print(f"    {name}...", end=" ", flush=True)
        is_raw = name in ("plan_specs", "build_log")
        resp = client.get(path, params=params, raw=is_raw)
        results.append({"endpoint": name, **resp})
        status = resp.get("status_code", resp.get("error", "?"))
        print(f"{status}")
        time.sleep(0.2)  # Be gentle

    save_results("bamboo", results, output_dir)


def probe_jira(cfg: dict, output_dir: Path):
    """Probe all Jira API endpoints."""
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

    for name, path, params in endpoints:
        print(f"    {name}...", end=" ", flush=True)
        resp = client.get(path, params=params)
        results.append({"endpoint": name, **resp})
        status = resp.get("status_code", resp.get("error", "?"))
        print(f"{status}")
        time.sleep(0.2)

    save_results("jira", results, output_dir)


def probe_sonar(cfg: dict, output_dir: Path):
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

    for name, path, params in endpoints:
        print(f"    {name}...", end=" ", flush=True)
        resp = client.get(path, params=params)
        results.append({"endpoint": name, **resp})
        status = resp.get("status_code", resp.get("error", "?"))
        print(f"{status}")
        time.sleep(0.2)

    save_results("sonar", results, output_dir)


def probe_bitbucket(cfg: dict, output_dir: Path):
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

    for name, path, params in endpoints:
        print(f"    {name}...", end=" ", flush=True)
        is_raw = name == "pr_diff"
        resp = client.get(path, params=params, raw=is_raw)
        results.append({"endpoint": name, **resp})
        status = resp.get("status_code", resp.get("error", "?"))
        print(f"{status}")
        time.sleep(0.2)

    save_results("bitbucket", results, output_dir)


def probe_docker_registry(cfg: dict, output_dir: Path):
    """Probe Docker Registry v2 endpoints."""
    cred = base64.b64encode(f"{cfg['token']}:".encode()).decode()
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
            # HEAD request for manifest check
            print(f"    manifest_check...", end=" ", flush=True)
            resp = client.head(f"/v2/{repo_name}/manifests/{tag}")
            results.append({"endpoint": "manifest_check", **resp})
            status = resp.get("status_code", resp.get("error", "?"))
            print(f"{status}")

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
    """Save censored results to file."""
    censored = []
    for r in results:
        entry = copy.deepcopy(r)
        if "body" in entry:
            entry["body"] = censor_value(entry["body"])
        censored.append(entry)

    filepath = output_dir / f"{service}.json"
    with open(filepath, "w") as f:
        json.dump(censored, f, indent=2, default=str)
    print(f"  -> Saved to {filepath}")


# ─── Config ──────────────────────────────────────────────────────────────────

CONFIG_TEMPLATE = {
    "_comment": "API Probe config. Fill in URLs and tokens. All fields are optional except url+token per service.",
    "output_dir": "scripts/api-probe-results",
    "bamboo": {
        "url": "https://bamboo.example.com",
        "token": "YOUR_BAMBOO_PAT",
        "plan_key": "PROJ-PLAN",
        "branch": "develop",
        "result_key": "PROJ-PLAN-123"
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
        "token": "YOUR_NEXUS_TOKEN",
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
    parser.add_argument("--init", action="store_true", help="Generate config template")
    parser.add_argument("--services", type=str, default="all",
                        help="Comma-separated services to probe: bamboo,jira,sonar,bitbucket,docker (default: all)")
    args = parser.parse_args()

    if args.init:
        init_config("scripts/api-probe-config.json")
        return

    if not args.config:
        print("Usage: python3 scripts/api-probe.py --config <config.json>")
        print("       python3 scripts/api-probe.py --init  (to create config template)")
        sys.exit(1)

    with open(args.config) as f:
        config = json.load(f)

    output_dir = Path(config.get("output_dir", "scripts/api-probe-results"))
    output_dir.mkdir(parents=True, exist_ok=True)

    services = args.services.split(",") if args.services != "all" else [
        "bamboo", "jira", "sonar", "bitbucket", "docker"
    ]

    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"API Probe — {timestamp}")
    print(f"Output: {output_dir}/")

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
        if not cfg.get("url") or not cfg.get("token"):
            print(f"\n  [SKIP] {svc}: no url/token configured")
            continue
        try:
            probe_fn(cfg, output_dir)
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
