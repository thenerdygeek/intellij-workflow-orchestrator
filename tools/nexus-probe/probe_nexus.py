#!/usr/bin/env python3
"""
Sonatype Nexus Repository Manager 3 API probe for the Workflow Orchestrator
IntelliJ plugin.

Read-only. Verifies the single endpoint family the plugin uses today (Docker
Registry v2 via DockerRegistryClient) and tests a wide set of native Nexus
REST v1 endpoints we may want to adopt (artifact browsing, search, health).
Writes per-endpoint JSON + a Markdown summary to tools/nexus-probe/Result_N/.

Two auth schemes are exercised in one run:

  - **HTTP Basic** for /service/rest/v1/* (Nexus REST)
        Three input shapes, all resolve to `Authorization: Basic <base64>`:
          a) Plain credentials:  --user my.user  --password p4ss
          b) Nexus user token:   --user <token-name>  --password <token-pass-code>
                                 (generated under "Settings → User Token" in Nexus UI;
                                 treated identically to plain creds at the wire level)
          c) Pre-encoded blob:   --basic-token <base64-of-user:password>
                                 (matches what the plugin stores in PasswordSafe)
        --basic-token is mutually exclusive with --user/--password.

  - **OAuth bearer-challenge** for /v2/* (Docker Registry)
        Behaviour mirrors DockerRegistryClient.executeWithAuth: first request
        goes out with the same Basic credential (when supplied); on 401 with a
        `WWW-Authenticate: Bearer realm=...,service=...` header the probe
        fetches a bearer token from the realm using the Basic credentials
        and retries the original request.

Usage examples:
    # Just detect Nexus version + connectivity (~4 calls, no repo args)
    python probe_nexus.py --url https://nexus.company.com \\
        --user my.user --password PAT --versions-only

    # Same, using a Nexus user token instead of a plain password
    python probe_nexus.py --url https://nexus.company.com \\
        --user <token-name> --password <token-pass-code> --versions-only

    # Same, pasting the pre-encoded base64 blob the plugin stores
    python probe_nexus.py --url https://nexus.company.com \\
        --basic-token <base64-of-user:pass> --versions-only

    # Discovery — pick a Maven repo + a Docker repo from the user's access
    python probe_nexus.py --url https://nexus.company.com \\
        --user my.user --password PAT --discover

    # Full sweep — needs realistic repo names so list/search probes
    # exercise non-empty payloads
    python probe_nexus.py --url https://nexus.company.com \\
        --user my.user --password PAT \\
        --maven-repo maven-releases --docker-repo my-images

    # Separate Docker registry hostname (some setups split them)
    python probe_nexus.py --url https://nexus.company.com \\
        --docker-registry-url https://registry.company.com \\
        --user my.user --password PAT --maven-repo maven-releases \\
        --docker-repo my-images

    # Self-signed cert
    python probe_nexus.py ... --no-verify

The probe never executes mutations (component upload, repo create, asset
delete, user changes, etc.) — only HTTP GET/HEAD. The User-Agent string
includes `(read-only)` so admins can audit probe traffic in Nexus's request
log. Output JSON files capture body, status, elapsed time, and
(where applicable) capability hints.
"""

from __future__ import annotations

import argparse
import base64
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
    auth_scheme: str = ""     # "basic" | "bearer-challenge" | "anonymous"


# ---------------------------------------------------------------------------
# Probe runner
# ---------------------------------------------------------------------------

class NexusProbe:
    """Two-surface read-only probe: Nexus REST (Basic) + Docker Registry (challenge)."""

    def __init__(self, base_url: str, registry_url: str, user: str, password: str,
                 basic_token: str, verify: bool, results_dir: Path):
        self.base = base_url.rstrip("/")
        self.registry = registry_url.rstrip("/")
        self.user = user
        self.password = password
        # Pre-built Basic credential — used for both REST calls and (if the
        # registry sits behind the same auth realm) for fetching Docker bearer
        # tokens from a Bearer-challenge realm. Three input shapes:
        #   - --basic-token wins if supplied (already base64-encoded)
        #   - else --user + --password are encoded here
        #   - else anonymous (some Nexus instances allow read-only anon access)
        if basic_token:
            # Strip an accidentally-pasted "Basic " prefix; users sometimes copy
            # the full Authorization header value out of the plugin's debug logs.
            self._basic_b64 = basic_token.removeprefix("Basic ").strip() or None
        elif user or password:
            self._basic_b64 = base64.b64encode(
                f"{user}:{password}".encode("utf-8")
            ).decode("ascii")
        else:
            self._basic_b64 = None

        # REST session: persistent Basic auth header.
        self.rest_session = requests.Session()
        if self._basic_b64:
            self.rest_session.headers["Authorization"] = f"Basic {self._basic_b64}"
        self.rest_session.headers.update({
            "Accept": "application/json",
            "User-Agent": "WorkflowOrchestrator-Probe/1.0 (read-only)",
        })
        self.rest_session.verify = verify

        # Docker session: starts unauthenticated; auth is negotiated per-call.
        self.docker_session = requests.Session()
        self.docker_session.headers.update({
            "Accept": "application/json",
            "User-Agent": "WorkflowOrchestrator-Probe/1.0 (read-only)",
        })
        self.docker_session.verify = verify

        # Cache of bearer tokens keyed by (realm, service, scope) so a
        # multi-tag sweep doesn't re-authenticate per request.
        self._bearer_cache: dict[tuple[str, str, str], str] = {}

        self.results_dir = results_dir
        self.raw_dir = results_dir / "raw"
        self.raw_dir.mkdir(parents=True, exist_ok=True)
        self.results: list[ProbeResult] = []

    # -- low-level REST helper (Basic auth) -----------------------------------

    def _rest_request(self, name: str, description: str, path: str, category: str,
                      method: str = "GET", notes: Optional[list[str]] = None,
                      expect_json: bool = True) -> ProbeResult:
        """Issue one REST request against /service/rest/v1/* with Basic auth."""
        url = f"{self.base}{path}"
        result = ProbeResult(
            name=name, description=description, method=method, path=path,
            category=category, notes=list(notes or []),
            auth_scheme="basic" if self._basic_b64 else "anonymous",
        )
        start = time.perf_counter()
        raw_payload: Any = None
        try:
            if method == "GET":
                resp = self.rest_session.get(url, timeout=30, allow_redirects=False)
            elif method == "HEAD":
                resp = self.rest_session.head(url, timeout=30, allow_redirects=False)
            else:
                raise ValueError(f"Unsupported method for REST: {method}")
            result.status = resp.status_code
            result.elapsed_ms = int((time.perf_counter() - start) * 1000)
            result.ok = 200 <= resp.status_code < 300
            raw_payload = self._fill_payload(result, resp, expect_json)
            self._note_redirect(result, resp)
        except requests.RequestException as e:
            result.error = f"{type(e).__name__}: {e}"
            result.payload_kind = "error"
            result.elapsed_ms = int((time.perf_counter() - start) * 1000)
        self._persist(name, result, raw_payload)
        self.results.append(result)
        return result

    def _rest_get(self, name: str, description: str, path: str, category: str,
                  notes: Optional[list[str]] = None, expect_json: bool = True) -> ProbeResult:
        return self._rest_request(name, description, path, category,
                                  method="GET", notes=notes, expect_json=expect_json)

    # -- low-level Docker helper (anonymous / Basic / bearer-challenge) -------

    def _docker_request(self, name: str, description: str, path: str, category: str,
                        method: str = "GET", notes: Optional[list[str]] = None,
                        expect_json: bool = True) -> ProbeResult:
        """Issue one Docker Registry v2 request, handling Basic + bearer-challenge.

        Mirrors the auth flow in `automation/.../DockerRegistryClient.kt`:
          1. First attempt sends the request with Basic auth (if creds given)
             so registries accepting Basic answer in one round-trip.
          2. On 401 with `WWW-Authenticate: Bearer realm=..., service=..., scope=...`,
             we fetch a token from the realm using Basic creds, then retry
             with Authorization: Bearer <token>.
          3. Any other failure short-circuits.
        """
        url = f"{self.registry}{path}"
        notes = list(notes or [])
        result = ProbeResult(
            name=name, description=description, method=method, path=path,
            category=category, notes=notes,
        )
        start = time.perf_counter()
        raw_payload: Any = None
        try:
            headers = self._docker_initial_headers()
            if method == "GET":
                resp = self.docker_session.get(
                    url, timeout=30, allow_redirects=False, headers=headers,
                )
            elif method == "HEAD":
                resp = self.docker_session.head(
                    url, timeout=30, allow_redirects=False, headers=headers,
                )
            else:
                raise ValueError(f"Unsupported method for Docker: {method}")

            if resp.status_code == 401:
                challenge = self._parse_bearer_challenge(
                    resp.headers.get("WWW-Authenticate") or "",
                )
                if challenge is not None:
                    result.auth_scheme = "bearer-challenge"
                    notes.append(
                        f"401 with Bearer challenge → realm={challenge['realm']!r}, "
                        f"service={challenge['service']!r}, scope={challenge['scope']!r}"
                    )
                    token = self._fetch_bearer_token(challenge)
                    if token is not None:
                        retry_headers = dict(headers or {})
                        retry_headers["Authorization"] = f"Bearer {token}"
                        if method == "GET":
                            resp = self.docker_session.get(
                                url, timeout=30, allow_redirects=False, headers=retry_headers,
                            )
                        else:
                            resp = self.docker_session.head(
                                url, timeout=30, allow_redirects=False, headers=retry_headers,
                            )
                    else:
                        notes.append("Bearer-token fetch failed; surface raw 401")
                else:
                    # 401 without a Bearer challenge — typical when registry uses
                    # plain Basic auth and our credentials are wrong/missing.
                    result.auth_scheme = "basic" if self._basic_b64 else "anonymous"
                    notes.append("401 with no Bearer challenge — registry expects Basic auth")
            else:
                result.auth_scheme = "basic" if self._basic_b64 else "anonymous"

            result.status = resp.status_code
            result.elapsed_ms = int((time.perf_counter() - start) * 1000)
            result.ok = 200 <= resp.status_code < 300
            raw_payload = self._fill_payload(result, resp, expect_json)
            self._note_redirect(result, resp)
        except requests.RequestException as e:
            result.error = f"{type(e).__name__}: {e}"
            result.payload_kind = "error"
            result.elapsed_ms = int((time.perf_counter() - start) * 1000)
        self._persist(name, result, raw_payload)
        self.results.append(result)
        return result

    def _docker_get(self, name: str, description: str, path: str, category: str,
                    notes: Optional[list[str]] = None, expect_json: bool = True) -> ProbeResult:
        return self._docker_request(name, description, path, category,
                                    method="GET", notes=notes, expect_json=expect_json)

    def _docker_head(self, name: str, description: str, path: str, category: str,
                     notes: Optional[list[str]] = None) -> ProbeResult:
        return self._docker_request(name, description, path, category,
                                    method="HEAD", notes=notes, expect_json=False)

    # -- shared helpers --------------------------------------------------------

    def _docker_initial_headers(self) -> dict[str, str]:
        """First-attempt headers for /v2/* — include Basic if creds are set so
        registries that accept Basic answer in one round-trip."""
        if self._basic_b64:
            return {"Authorization": f"Basic {self._basic_b64}"}
        return {}

    @staticmethod
    def _parse_bearer_challenge(header: str) -> Optional[dict[str, str]]:
        """Parse `Bearer realm="...",service="...",scope="..."` into a dict.

        Returns None unless the header begins with `Bearer `. Quoted values
        have surrounding quotes stripped. Unknown params are dropped.
        """
        if not header.startswith("Bearer "):
            return None
        body = header[len("Bearer "):]
        out: dict[str, str] = {"realm": "", "service": "", "scope": ""}
        for raw in body.split(","):
            kv = raw.strip().split("=", 1)
            if len(kv) != 2:
                continue
            k = kv[0].strip()
            v = kv[1].strip().strip('"')
            if k in out:
                out[k] = v
        if not out["realm"]:
            return None
        return out

    def _fetch_bearer_token(self, challenge: dict[str, str]) -> Optional[str]:
        """Fetch a Bearer token from the challenge realm using Basic creds."""
        cache_key = (challenge["realm"], challenge["service"], challenge["scope"])
        cached = self._bearer_cache.get(cache_key)
        if cached:
            return cached

        params: list[tuple[str, str]] = []
        if challenge["service"]:
            params.append(("service", challenge["service"]))
        if challenge["scope"]:
            params.append(("scope", challenge["scope"]))
        sep = "&" if "?" in challenge["realm"] else "?"
        url = challenge["realm"] + (sep + urllib.parse.urlencode(params) if params else "")

        headers: dict[str, str] = {"Accept": "application/json"}
        if self._basic_b64:
            headers["Authorization"] = f"Basic {self._basic_b64}"

        try:
            r = self.docker_session.get(url, timeout=30, headers=headers, allow_redirects=False)
            if not r.ok:
                return None
            data = r.json()
        except (requests.RequestException, ValueError):
            return None

        token = data.get("token") or data.get("access_token")
        if not isinstance(token, str) or not token:
            return None
        self._bearer_cache[cache_key] = token
        return token

    @staticmethod
    def _fill_payload(result: ProbeResult, resp: requests.Response,
                      expect_json: bool) -> Any:
        content_type = (resp.headers.get("Content-Type") or "").lower()
        body = resp.text or ""
        raw_payload: Any = None
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
        return raw_payload

    @staticmethod
    def _note_redirect(result: ProbeResult, resp: requests.Response) -> None:
        if 300 <= resp.status_code < 400:
            result.notes.append(
                f"Redirect to {resp.headers.get('Location', '?')} "
                "(probe leaves redirects manual to surface auth-scheme drift)"
            )

    def _persist(self, name: str, result: ProbeResult, raw_payload: Any) -> None:
        raw_file = self.raw_dir / f"{name}.json"
        raw_file.write_text(json.dumps({
            "result": asdict(result),
            "raw_body": raw_payload if raw_payload is not None else None,
        }, indent=2, default=str), encoding="utf-8")

    # -- modes ----------------------------------------------------------------

    def run_versions_only(self) -> None:
        print("[probe] versions-only mode\n")
        # Status: Nexus 3 returns 200 anonymously when "Anonymous Access" is
        # enabled; otherwise 401 — both are informative.
        self._rest_get(
            name="rest_status",
            description="Connectivity / liveness (mirrors Atlassian's /serverInfo)",
            path="/service/rest/v1/status",
            category="version",
            notes=["May respond 200 anonymously if 'Anonymous Access' is enabled"],
        )
        self._rest_get(
            name="rest_status_writable",
            description="Read-write availability (used for capacity planning)",
            path="/service/rest/v1/status/writable",
            category="version",
            notes=["200 = node is read-write; 503 = read-only mode"],
        )
        self._rest_get(
            name="rest_system_about",
            description="Nexus version + edition (Pro/OSS) — primary version-detect endpoint",
            path="/service/rest/v1/system/about",
            category="version",
            notes=[
                "Use this to fill in the Nexus version question once you run the probe. "
                "Look at .data.applicationVersion and .data.editionShort."
            ],
        )
        self._rest_get(
            name="rest_security_realms_active",
            description="Active auth realms — confirms Basic vs token vs Docker bearer support",
            path="/service/rest/v1/security/realms/active",
            category="version",
            notes=[
                "If the array contains 'DockerToken' the registry accepts the bearer-challenge "
                "flow we mirror in DockerRegistryClient. 'NexusAuthenticatingRealm' = Basic."
            ],
        )
        self._docker_get(
            name="docker_v2_root",
            description="Docker Registry v2 connectivity (already used by AuthTestService)",
            path="/v2/",
            category="version",
            notes=[
                "200 with empty body = registry online. 401 with Bearer challenge = "
                "Docker Bearer Token Realm enabled. 401 without challenge = Basic only."
            ],
        )

    def run_discover(self) -> None:
        """Discover mode — finds one Maven repo + one Docker repo so the
        full sweep can exercise both REST and Docker surfaces.

        Algorithm:
          1. Run versions-only first (connectivity + version + auth realms).
          2. List all repositories via /service/rest/v1/repositories.
          3. List all Docker repos via /v2/_catalog.
          4. From (2) pick the first hosted maven2 repo (writes are common but
             we never call write endpoints — just the first format=maven2).
          5. From (3) pick the first repo name.
          6. Hit one component listing + one tag list to confirm reachability
             with the chosen names.
          7. Write Result_N/discover.md with a copy-paste full-sweep command.
        """
        print("[probe] discover mode\n")
        self.run_versions_only()

        print("\n[probe] discover — repository inventory")
        self._rest_get(
            name="discover_repositories",
            description="All repositories visible to your token",
            path="/service/rest/v1/repositories",
            category="feature",
            notes=["Used by repo picker — name, format (maven2/docker/raw), type (hosted/proxy/group)"],
        )
        self._docker_get(
            name="discover_docker_catalog",
            description="All Docker repos in the registry",
            path="/v2/_catalog?n=100",
            category="feature",
            notes=["Lets a tag-picker UI enumerate repos before listing tags"],
        )

        chosen_maven = self._first_repo_by_format(
            "discover_repositories.json", expected_format="maven2",
        )
        chosen_docker = self._first_docker_repo("discover_docker_catalog.json")

        if chosen_maven:
            mv = urllib.parse.quote(chosen_maven, safe="")
            self._rest_get(
                name=f"discover_components_{chosen_maven}",
                description=f"First page of components in {chosen_maven}",
                path=f"/service/rest/v1/components?repository={mv}",
                category="feature",
                notes=[
                    "Discovery — confirms /components reachability with a real repo name. "
                    "Continuation token (if any) drives pagination."
                ],
            )
        if chosen_docker:
            dr = urllib.parse.quote(chosen_docker, safe="")
            self._docker_get(
                name=f"discover_tags_{chosen_docker}",
                description=f"First page of tags in {chosen_docker}",
                path=f"/v2/{dr}/tags/list?n=10",
                category="feature",
                notes=["Discovery — confirms /v2/{repo}/tags/list with a real Docker repo name"],
            )

        self._write_discover_digest(chosen_maven, chosen_docker)

    def run_full(self, maven_repo: Optional[str], docker_repo: Optional[str],
                 component_id: Optional[str], maven_group: Optional[str],
                 manifest_tag: Optional[str]) -> None:
        # 1) Always run versions block first
        self.run_versions_only()

        # 2) Repository enumeration (no params required)
        print("\n[probe] feature — repository inventory")
        self._rest_get(
            name="repositories_all",
            description="All repositories — would power a repo picker beyond just docker",
            path="/service/rest/v1/repositories",
            category="feature",
            notes=["Plugin currently doesn't call this — the entire opportunity surface"],
        )
        self._rest_get(
            name="repository_settings",
            description="Detailed repo settings (admin-only — informational)",
            path="/service/rest/v1/repositorySettings",
            category="internal",
            notes=["403 for non-admin; documents what admin tooling could read if needed"],
        )

        # 3) Maven-repo-scoped probes (need --maven-repo)
        if maven_repo:
            mv = urllib.parse.quote(maven_repo, safe="")
            print(f"\n[probe] feature — Maven repo '{maven_repo}'")
            self._rest_get(
                name=f"components_{maven_repo}",
                description=f"List components in {maven_repo} (page 1)",
                path=f"/service/rest/v1/components?repository={mv}",
                category="feature",
                notes=["First page; continuationToken in body unlocks pagination"],
            )
            # Continuation pagination — only fires if first page returned a token.
            cont = _read_continuation_token(self.raw_dir / f"components_{maven_repo}.json")
            if cont:
                ct = urllib.parse.quote(cont, safe="")
                self._rest_get(
                    name=f"components_{maven_repo}_page2",
                    description=f"List components in {maven_repo} (page 2 via continuationToken)",
                    path=f"/service/rest/v1/components?repository={mv}&continuationToken={ct}",
                    category="feature",
                    notes=["Confirms continuation-token pagination contract"],
                )
            self._rest_get(
                name=f"assets_{maven_repo}",
                description=f"List assets (file-level) in {maven_repo}",
                path=f"/service/rest/v1/assets?repository={mv}",
                category="feature",
                notes=["Asset rows expose downloadUrl directly — useful for jar download"],
            )
            self._rest_get(
                name=f"search_{maven_repo}_maven2",
                description=f"Search Maven artifacts in {maven_repo}",
                path=f"/service/rest/v1/search?repository={mv}&format=maven2"
                     + (f"&maven.groupId={urllib.parse.quote(maven_group, safe='')}"
                        if maven_group else ""),
                category="feature",
                notes=[
                    "Powers 'find latest snapshot' UI. Pass --maven-group for a non-empty payload.",
                ],
            )
            self._rest_get(
                name=f"search_assets_{maven_repo}",
                description=f"Asset-level search in {maven_repo}",
                path=f"/service/rest/v1/search/assets?repository={mv}"
                     + (f"&maven.groupId={urllib.parse.quote(maven_group, safe='')}"
                        if maven_group else ""),
                category="feature",
                notes=["Returns downloadUrl per match — more useful than /search for jar fetch"],
            )
            self._rest_get(
                name=f"repo_health_{maven_repo}",
                description=f"Repository health-check status",
                path=f"/service/rest/v1/repositories/{mv}/health-check",
                category="feature",
                notes=["403/404 if Health Check feature is disabled — informative"],
            )
            # Component-by-id (needs an id; auto-derive from /components page 1)
            derived_id = component_id or _read_first_component_id(
                self.raw_dir / f"components_{maven_repo}.json"
            )
            if derived_id:
                cid = urllib.parse.quote(derived_id, safe="")
                self._rest_get(
                    name=f"component_by_id_{maven_repo}",
                    description=f"Single component by id ({derived_id})",
                    path=f"/service/rest/v1/components/{cid}",
                    category="feature",
                    notes=["Returns full asset list incl. downloadUrl"],
                )
            else:
                self.results.append(ProbeResult(
                    name=f"component_by_id_{maven_repo}_skipped",
                    description="component-by-id probe skipped — no component id derivable",
                    method="-", path="-", payload_kind="error", category="feature",
                    error=f"components_{maven_repo} yielded no items; pass --component-id to force",
                ))

        # 4) Docker-repo-scoped probes (need --docker-repo)
        if docker_repo:
            dr = urllib.parse.quote(docker_repo, safe="")
            print(f"\n[probe] existing/swap — Docker repo '{docker_repo}'")
            self._docker_get(
                name=f"docker_tags_{docker_repo}",
                description=f"Tag list for {docker_repo} (already used by plugin)",
                path=f"/v2/{dr}/tags/list?n=100",
                category="existing",
                notes=["Plugin's listTags() — verify pagination Link header on full repos"],
            )
            self._docker_get(
                name="docker_catalog",
                description="List ALL Docker repos in the registry",
                path="/v2/_catalog?n=100",
                category="feature",
                notes=["SWAP/FEATURE candidate — lets the tag picker enumerate repos first"],
            )
            # Pick a tag for manifest probes
            chosen_tag = manifest_tag or _read_first_tag(
                self.raw_dir / f"docker_tags_{docker_repo}.json"
            )
            if chosen_tag:
                tg = urllib.parse.quote(chosen_tag, safe="")
                self._docker_head(
                    name=f"docker_manifest_head_{docker_repo}",
                    description=f"HEAD manifest for {docker_repo}:{chosen_tag} (cheaper digest check)",
                    path=f"/v2/{dr}/manifests/{tg}",
                    category="swap",
                    notes=[
                        "SWAP candidate: plugin uses GET /v2/{repo}/manifests/{tag} for tag "
                        "existence; HEAD avoids transferring the manifest body."
                    ],
                )
                self._docker_get(
                    name=f"docker_manifest_get_{docker_repo}",
                    description=f"GET manifest for {docker_repo}:{chosen_tag} (already used)",
                    path=f"/v2/{dr}/manifests/{tg}",
                    category="existing",
                    notes=["Plugin's tagExists() path"],
                )
            else:
                self.results.append(ProbeResult(
                    name=f"docker_manifest_{docker_repo}_skipped",
                    description="manifest probes skipped — no tag derivable",
                    method="-", path="-", payload_kind="error", category="existing",
                    error=f"docker_tags_{docker_repo} yielded no tags; pass --manifest-tag to force",
                ))

        # 5) Cross-cutting: token capability check
        print("\n[probe] internal — token capability check")
        self._rest_get(
            name="security_users",
            description="List users (admin-only) — surfaces token's permission level",
            path="/service/rest/v1/security/users",
            category="internal",
            notes=[
                "200 = your token has admin/security read; 403 = read-only token. "
                "Never exposed in plugin UI; just informs scope of feasible features."
            ],
        )

    # -- discovery digest -----------------------------------------------------

    def _first_repo_by_format(self, raw_filename: str,
                              expected_format: str) -> Optional[str]:
        body = _read_raw_body(self.raw_dir / raw_filename)
        if not isinstance(body, list):
            return None
        # Prefer hosted over proxy/group so we exercise repos with components.
        for repo_type in ("hosted", "proxy", "group"):
            for r in body:
                if (isinstance(r, dict)
                        and r.get("format") == expected_format
                        and r.get("type") == repo_type
                        and r.get("name")):
                    return str(r["name"])
        # Fallback: first repo of the format regardless of type
        for r in body:
            if isinstance(r, dict) and r.get("format") == expected_format and r.get("name"):
                return str(r["name"])
        return None

    def _first_docker_repo(self, raw_filename: str) -> Optional[str]:
        body = _read_raw_body(self.raw_dir / raw_filename)
        if isinstance(body, dict):
            repos = body.get("repositories")
            if isinstance(repos, list):
                for r in repos:
                    if isinstance(r, str) and r:
                        return r
        return None

    def _write_discover_digest(self, maven_repo: Optional[str],
                               docker_repo: Optional[str]) -> None:
        lines: list[str] = ["# Discovery — pick repos for the full sweep", ""]

        # Maven-format hosted repos table
        repos_body = _read_raw_body(self.raw_dir / "discover_repositories.json")
        rows: list[tuple[str, str, str, str]] = []
        if isinstance(repos_body, list):
            for r in repos_body:
                if not isinstance(r, dict):
                    continue
                rows.append((
                    str(r.get("name", "")),
                    str(r.get("format", "")),
                    str(r.get("type", "")),
                    str(r.get("url", "")),
                ))
        if rows:
            lines.append("## Repositories visible to your token")
            lines.append("")
            lines.append("| Name | Format | Type | URL |")
            lines.append("|---|---|---|---|")
            for n, f, t, u in rows[:50]:
                lines.append(f"| `{n}` | `{f}` | `{t}` | `{u}` |")
            if len(rows) > 50:
                lines.append(f"| _…{len(rows) - 50} more_ |  |  |  |")
            lines.append("")
        else:
            lines.append("## Repositories visible to your token")
            lines.append("")
            lines.append("_None — your token has no repository read permissions._")
            lines.append("")

        # Docker catalog table
        cat_body = _read_raw_body(self.raw_dir / "discover_docker_catalog.json")
        cat_repos: list[str] = []
        if isinstance(cat_body, dict):
            for n in (cat_body.get("repositories") or []):
                if isinstance(n, str):
                    cat_repos.append(n)
        if cat_repos:
            lines.append("## Docker repos in the registry")
            lines.append("")
            lines.append("| # | Name |")
            lines.append("|---|---|")
            for i, n in enumerate(cat_repos[:50], start=1):
                lines.append(f"| {i} | `{n}` |")
            if len(cat_repos) > 50:
                lines.append(f"| _…{len(cat_repos) - 50} more_ |  |")
            lines.append("")

        # Suggested command
        suggested_maven = maven_repo or "MAVEN_REPO_NAME"
        suggested_docker = docker_repo or "DOCKER_REPO_NAME"
        lines.append("---")
        lines.append("")
        lines.append("## Suggested command for the full sweep")
        lines.append("")
        lines.append(
            "_Seeded with the most-likely values from your access above. "
            "Replace either name with whatever you'd rather use._"
        )
        lines.append("")
        lines.append("Windows `cmd`:")
        lines.append("")
        lines.append("```bat")
        lines.append(
            f"python probe_nexus.py --url <YOUR_NEXUS_URL> ^\n"
            f"    --user <YOUR_USER> --password <YOUR_PAT> ^\n"
            f"    --maven-repo {suggested_maven} ^\n"
            f"    --docker-repo {suggested_docker}"
        )
        lines.append("```")
        lines.append("")
        lines.append("PowerShell / Unix shells:")
        lines.append("")
        lines.append("```bash")
        lines.append(
            f"python probe_nexus.py --url <YOUR_NEXUS_URL> \\\n"
            f"    --user <YOUR_USER> --password <YOUR_PAT> \\\n"
            f"    --maven-repo {suggested_maven} \\\n"
            f"    --docker-repo {suggested_docker}"
        )
        lines.append("```")
        lines.append("")

        digest_path = self.results_dir / "discover.md"
        digest_path.write_text("\n".join(lines), encoding="utf-8")
        print(f"\n[probe] wrote discovery digest -> {digest_path}")
        print("[probe] open it locally, pick values, then re-run with the suggested args.")
        print("[probe] (this file contains real repo names — redact before sharing.)")

    # -- summary --------------------------------------------------------------

    def write_summary(self, args_used: dict[str, Any]) -> None:
        summary_path = self.results_dir / "summary.md"
        lines: list[str] = []
        lines.append(f"# Nexus probe results — {self.base}")
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

        # Order: version → existing (what plugin uses today) → swap (better
        # paths for what it does) → feature (new capability) → internal.
        for category in ("version", "existing", "swap", "feature", "internal"):
            cat_results = [r for r in self.results if r.category == category]
            if not cat_results:
                continue
            lines.append(f"## {category.title()} endpoints")
            lines.append("")
            lines.append("| Status | Auth | Endpoint | Description | Time | Notes |")
            lines.append("|---|---|---|---|---|---|")
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
                auth_label = r.auth_scheme or "-"
                lines.append(
                    f"| {status_label} | `{auth_label}` | `{r.method} {r.path}` "
                    f"| {desc} | {r.elapsed_ms}ms | {notes_str} |"
                )
            lines.append("")

        lines.append("## Writes inventoried but NOT called (read-only probe)")
        lines.append("")
        lines.append("| Method | Endpoint | Plugin caller |")
        lines.append("|---|---|---|")
        for method, path, caller in _WRITES_INVENTORY:
            lines.append(f"| `{method}` | `{path}` | {caller} |")
        lines.append("")
        lines.append(
            "_The plugin currently issues **zero write calls** to Nexus. The Docker tag "
            "validation flow is read-only (HEAD/GET manifests). This table is therefore "
            "empty by design — listed for parity with the Jira/Bitbucket probes._"
        )
        lines.append("")

        lines.append("## Raw responses")
        lines.append("")
        lines.append("Each endpoint's full response (parsed JSON or text snippet) is "
                     "saved to `raw/<name>.json`. These can be diffed across probe runs "
                     "to detect schema drift.")
        lines.append("")

        summary_path.write_text("\n".join(lines), encoding="utf-8")
        print(f"\n[probe] wrote summary -> {summary_path}")
        print(f"[probe] wrote {len(self.results)} raw payloads -> {self.raw_dir}")

    def _format_version_note(self) -> str:
        about = next((r for r in self.results if r.name == "rest_system_about"), None)
        if not about or not about.ok:
            return "_/system/about did not respond — Nexus version unknown._"
        try:
            data = json.loads(
                (self.raw_dir / "rest_system_about.json").read_text(encoding="utf-8")
            ).get("raw_body") or {}
        except Exception:
            return "_/system/about response could not be parsed._"
        # Nexus 3 nests fields under .data
        d = data.get("data") if isinstance(data, dict) else None
        if not isinstance(d, dict):
            d = data if isinstance(data, dict) else {}
        edition = d.get("editionShort") or d.get("edition") or ""
        version = d.get("applicationVersion") or d.get("version") or ""
        build_rev = d.get("buildRevision") or ""
        # Auth realms hint
        realms_kind = "(unknown)"
        try:
            ra = json.loads(
                (self.raw_dir / "rest_security_realms_active.json").read_text(encoding="utf-8")
            ).get("raw_body")
            if isinstance(ra, list):
                realms_kind = ", ".join(str(x) for x in ra) or "(empty)"
        except Exception:
            pass
        # Docker /v2/ status
        docker_kind = "(unknown)"
        try:
            v2 = json.loads(
                (self.raw_dir / "docker_v2_root.json").read_text(encoding="utf-8")
            ).get("result") or {}
            docker_status = v2.get("status")
            docker_auth = v2.get("auth_scheme")
            docker_kind = f"status={docker_status} auth={docker_auth}"
        except Exception:
            pass
        return (
            f"- **applicationVersion:** `{version}`\n"
            f"- **edition:** `{edition}`  ← OSS/Pro/CommunityEdition\n"
            f"- **buildRevision:** `{build_rev}`\n"
            f"- **active auth realms:** `{realms_kind}`\n"
            f"- **/v2/ probe:** `{docker_kind}`\n"
        )


# ---------------------------------------------------------------------------
# Inventory of write endpoints — never called by the probe
# ---------------------------------------------------------------------------

_WRITES_INVENTORY: list[tuple[str, str, str]] = [
    # Empty by design — the plugin's only Nexus writes today are docker
    # registry pushes triggered by Bamboo (server-side, not from the plugin).
]


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


def _read_continuation_token(raw_path: Path) -> Optional[str]:
    body = _read_raw_body(raw_path)
    if isinstance(body, dict):
        tok = body.get("continuationToken")
        if isinstance(tok, str) and tok:
            return tok
    return None


def _read_first_component_id(raw_path: Path) -> Optional[str]:
    body = _read_raw_body(raw_path)
    if isinstance(body, dict):
        items = body.get("items") or []
        for it in items:
            if isinstance(it, dict) and it.get("id"):
                return str(it["id"])
    return None


def _read_first_tag(raw_path: Path) -> Optional[str]:
    body = _read_raw_body(raw_path)
    if isinstance(body, dict):
        tags = body.get("tags") or []
        for t in tags:
            if isinstance(t, str) and t:
                return t
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
        description="Read-only Nexus 3 + Docker Registry v2 probe for Workflow Orchestrator plugin",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    p.add_argument("--url", required=True,
                   help="Nexus base URL, e.g. https://nexus.example.com")
    p.add_argument("--docker-registry-url", default=None,
                   help="Docker registry base URL (defaults to --url). "
                        "Some setups split Nexus REST and the Docker registry "
                        "across separate hostnames or ports.")
    p.add_argument("--user", default="",
                   help="Nexus username, or a Nexus user-token name (for HTTP Basic on "
                        "/service/rest/v1/* and as Basic credential for fetching Docker bearer tokens)")
    p.add_argument("--password", default="",
                   help="Plain password OR Nexus user-token pass-code "
                        "(generated under Settings → User Token in the Nexus UI)")
    p.add_argument("--basic-token", default="",
                   help="Pre-encoded base64(user:password) — paste the same blob "
                        "the plugin stores in PasswordSafe. Mutually exclusive with "
                        "--user/--password. Accepts a leading 'Basic ' prefix and strips it.")
    p.add_argument("--maven-repo", help="Maven repo name (enables /components, /assets, /search probes)")
    p.add_argument("--docker-repo", help="Docker repo name (enables /v2/{repo}/tags + manifest probes)")
    p.add_argument("--component-id",
                   help="Component id (auto-derived from /components page 1 if omitted)")
    p.add_argument("--maven-group",
                   help="Maven groupId, e.g. com.example.foo (narrows /search payloads)")
    p.add_argument("--manifest-tag",
                   help="Specific tag for manifest probes (auto-derived from tags/list if omitted)")
    p.add_argument("--no-verify", action="store_true",
                   help="Disable TLS verification (self-signed certs)")
    p.add_argument("--versions-only", action="store_true",
                   help="Only call /status, /system/about, /security/realms/active, /v2/ and exit")
    p.add_argument("--discover", action="store_true",
                   help="Discovery mode: list all repos + Docker catalog, pick a maven + docker "
                        "repo, write Result_N/discover.md with a copy-paste full-sweep command.")
    p.add_argument("--out", default=str(Path(__file__).parent),
                   help="Parent dir for Result_N/ output (default: alongside the script)")
    args = p.parse_args()

    if args.no_verify:
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

    if args.basic_token and (args.user or args.password):
        print("ERROR: --basic-token is mutually exclusive with --user/--password.", file=sys.stderr)
        return 2

    if args.discover:
        mode_label = "discover"
    elif args.versions_only:
        mode_label = "versions-only"
    else:
        mode_label = "full sweep"

    registry_url = args.docker_registry_url or args.url
    print(f"[probe] target (REST):     {args.url}")
    print(f"[probe] target (Docker):   {registry_url}")
    print(f"[probe] output:            {results_dir}")
    print(f"[probe] mode:              {mode_label}")
    print()

    probe = NexusProbe(
        base_url=args.url,
        registry_url=registry_url,
        user=args.user,
        password=args.password,
        basic_token=args.basic_token,
        verify=not args.no_verify,
        results_dir=results_dir,
    )

    args_used = {
        "url": args.url,
        "docker_registry_url": registry_url,
        "auth_input": (
            "basic-token" if args.basic_token
            else "user+password" if (args.user or args.password)
            else "anonymous"
        ),
        "maven_repo": args.maven_repo,
        "docker_repo": args.docker_repo,
        "component_id": args.component_id,
        "maven_group": args.maven_group,
        "manifest_tag": args.manifest_tag,
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
            maven_repo=args.maven_repo,
            docker_repo=args.docker_repo,
            component_id=args.component_id,
            maven_group=args.maven_group,
            manifest_tag=args.manifest_tag,
        )

    probe.write_summary(args_used)
    if args.discover:
        print(f"[probe] done — open {results_dir / 'discover.md'} to pick repos for the full sweep.")
    else:
        print(f"[probe] done — open {results_dir / 'summary.md'} and paste back to me.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
