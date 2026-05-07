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

    # Workflow scope — verify ONLY the two Docker v2 endpoints the plugin's
    # per-repo verify flow uses (tags/list + manifest HEAD). 5 calls total.
    # Required: --docker-repo. Optional: --manifest-tag (else auto-derived).
    python probe_nexus.py --url https://nexus.company.com \\
        --docker-registry-url https://docker.company.com \\
        --basic-token <base64-of-user:pass> \\
        --docker-only --docker-repo company-team/service-a-repo \\
        --manifest-tag 1.2.3

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
    # Selected response headers — Nexus's `Server: Nexus/X.Y.Z-NN (EDITION)` is
    # the canonical version-detect signal (set on every response, including
    # 401/403). `Link` carries `_catalog`/`tags/list` pagination. `WWW-Authenticate`
    # exposes the Bearer challenge realm. `Location` documents redirects.
    response_headers: dict[str, str] = field(default_factory=dict)


# ---------------------------------------------------------------------------
# Probe runner
# ---------------------------------------------------------------------------

class NexusProbe:
    """Two-surface read-only probe: Nexus REST (Basic) + Docker Registry (challenge)."""

    def __init__(self, base_url: str, registry_url: str, user: str, password: str,
                 basic_token: str, verify: bool, results_dir: Path,
                 docker_base_path: str = ""):
        self.base = base_url.rstrip("/")
        self.registry = registry_url.rstrip("/")
        # Optional path prefix prepended to every /v2/* URL — supports Nexus 3
        # path-based Docker access (e.g. "/repository/docker-group" so the
        # final URL becomes "<registry>/repository/docker-group/v2/<repo>/..."
        # instead of "<registry>/v2/<repo>/..."). Empty = root-level /v2/.
        self.docker_base_path = ("/" + docker_base_path.strip("/")) if docker_base_path.strip("/") else ""
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
            result.response_headers = self._capture_headers(resp)
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
        full_path = f"{self.docker_base_path}{path}"
        url = f"{self.registry}{full_path}"
        notes = list(notes or [])
        result = ProbeResult(
            name=name, description=description, method=method, path=full_path,
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
            result.response_headers = self._capture_headers(resp)
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

    def _docker_head_accept(self, name: str, description: str, path: str,
                            accept: str, notes: Optional[list[str]] = None) -> ProbeResult:
        """HEAD a /v2/* path with an explicit Accept header — used for the OCI
        manifest media-type negotiation dance (4 variants per tag).

        Bypasses _docker_request because the standard helper doesn't accept a
        custom Accept header and the body-fill logic isn't needed for HEAD.
        Auth still goes through the same Basic-then-bearer-challenge flow.
        """
        full_path = f"{self.docker_base_path}{path}"
        url = f"{self.registry}{full_path}"
        result = ProbeResult(
            name=name, description=description, method="HEAD", path=full_path,
            category="swap", notes=list(notes or []),
        )
        start = time.perf_counter()
        try:
            headers = self._docker_initial_headers()
            headers["Accept"] = accept
            resp = self.docker_session.head(
                url, timeout=30, allow_redirects=False, headers=headers,
            )
            if resp.status_code == 401:
                challenge = self._parse_bearer_challenge(
                    resp.headers.get("WWW-Authenticate") or "",
                )
                if challenge is not None:
                    result.auth_scheme = "bearer-challenge"
                    token = self._fetch_bearer_token(challenge)
                    if token is not None:
                        retry = dict(headers)
                        retry["Authorization"] = f"Bearer {token}"
                        resp = self.docker_session.head(
                            url, timeout=30, allow_redirects=False, headers=retry,
                        )
                else:
                    result.auth_scheme = "basic" if self._basic_b64 else "anonymous"
            else:
                result.auth_scheme = "basic" if self._basic_b64 else "anonymous"

            result.status = resp.status_code
            result.elapsed_ms = int((time.perf_counter() - start) * 1000)
            result.ok = 200 <= resp.status_code < 300
            result.response_headers = self._capture_headers(resp)
            result.payload_kind = "empty"  # HEAD always
            result.notes.append(
                f"Sent Accept: {accept}; got Content-Type: "
                f"{resp.headers.get('Content-Type', '(none)')}"
            )
        except requests.RequestException as e:
            result.error = f"{type(e).__name__}: {e}"
            result.payload_kind = "error"
            result.elapsed_ms = int((time.perf_counter() - start) * 1000)
        self._persist(name, result, None)
        self.results.append(result)
        return result

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

    # Headers we always copy into raw/<name>.json — small set so the bundle
    # stays compact, but enough to power version-detect + pagination + auth
    # negotiation analysis offline.
    _HEADERS_TO_CAPTURE = (
        "Server",            # Nexus/X.Y.Z-NN (EDITION) — primary version-detect
        "WWW-Authenticate",  # Bearer realm / Basic challenge
        "Link",              # /v2/_catalog and /v2/.../tags/list pagination
        "Location",          # 3xx redirects
        "Content-Type",      # negotiation outcome
        "Content-Length",    # body size
        "ETag",              # cache state
        "Date",              # server clock skew
        "Docker-Content-Digest",  # /v2/.../manifests digest
        "Docker-Distribution-Api-Version",  # /v2/ root advertises registry/2.0
    )

    @classmethod
    def _capture_headers(cls, resp: requests.Response) -> dict[str, str]:
        out: dict[str, str] = {}
        for name in cls._HEADERS_TO_CAPTURE:
            v = resp.headers.get(name)
            if v:
                out[name] = v
        return out

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
        # Layered version-detect ladder per docs/research/2026-05-07-nexus-3.90-api-surface.md §1:
        #   1. Server: header on EVERY response  (primary — works on 401/403 too)
        #   2. /v1/status/check                   (renamed in 3.81; non-admin)
        #   3. /service/rest/swagger.json         (anonymous-readable; .info.version)
        #   4. Rapture HTML scrape on /           (final fallback — cache-buster `?<ver>`)
        # The phantom /v1/system/about (which 404'd in v0) has been removed —
        # it never existed in the v1/ namespace on Nexus 3.
        self._rest_get(
            name="rest_status",
            description="Connectivity / liveness (200 = up; 503 = unavailable)",
            path="/service/rest/v1/status",
            category="version",
            notes=["Spec: 200 with empty body when alive — empty body is correct, not a fault"],
        )
        self._rest_get(
            name="rest_status_writable",
            description="Read-write availability (200 = read-write; 503 = read-only mode)",
            path="/service/rest/v1/status/writable",
            category="version",
        )
        self._rest_get(
            name="rest_status_check",
            description="System health check JSON (renamed from /service/metrics/healthcheck in 3.81)",
            path="/service/rest/v1/status/check",
            category="version",
            notes=[
                "Tristate: 200 = namespace + auth ok; 401/403 = perm-fail; 404 = pre-3.81 install. "
                "Body is informational; status code is the primary signal."
            ],
        )
        self._rest_get(
            name="rest_swagger_json",
            description="Nexus's own OpenAPI 3 spec — anonymous-readable; advertises every endpoint",
            path="/service/rest/swagger.json",
            category="version",
            notes=[
                "Per Sonatype: 'does not require any privilege to access'. "
                "Body has .info.version (string) + .paths (every endpoint this instance ships). "
                "Single most-comprehensive capability-discovery signal."
            ],
        )
        self._rest_get(
            name="rest_security_realms_active",
            description="Active auth realms — admin-only on 3.90; expected 403 for read-only token",
            path="/service/rest/v1/security/realms/active",
            category="internal",
            notes=[
                "If the array contains 'DockerToken' the registry uses the bearer-challenge "
                "flow we mirror in DockerRegistryClient; 'NexusAuthenticatingRealm' = Basic. "
                "Non-admin tokens get 403 — that's a capability marker, not a failure."
            ],
        )
        self._rest_get(
            name="rest_root_html",
            description="Rapture HTML root — version cache-buster fallback (`?3.90.1-01`) on static asset URLs",
            path="/",
            category="version",
            notes=["Final-fallback version source — used when Server: header is stripped by a reverse proxy"],
            expect_json=False,
        )
        self._docker_get(
            name="docker_v2_root",
            description="Docker Registry v2 connectivity (already used by AuthTestService)",
            path="/v2/",
            category="version",
            notes=[
                "200 with empty body = registry online. 401 with Bearer challenge = "
                "Docker Bearer Token Realm enabled. 401 without challenge = Basic only. "
                "404 HTML = wrong host — Docker registry is on a different URL than the REST API."
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
            dr = urllib.parse.quote(chosen_docker, safe="/")
            safe_chosen = chosen_docker.replace("/", "_")
            self._docker_get(
                name=f"discover_tags_{safe_chosen}",
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
            dr = urllib.parse.quote(docker_repo, safe="/")
            safe_repo = docker_repo.replace("/", "_")
            print(f"\n[probe] existing/swap — Docker repo '{docker_repo}'")
            self._docker_get(
                name=f"docker_tags_{safe_repo}",
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
                self.raw_dir / f"docker_tags_{safe_repo}.json"
            )
            if chosen_tag:
                tg = urllib.parse.quote(chosen_tag, safe="")
                self._docker_head(
                    name=f"docker_manifest_head_{safe_repo}",
                    description=f"HEAD manifest for {docker_repo}:{chosen_tag} (cheaper digest check)",
                    path=f"/v2/{dr}/manifests/{tg}",
                    category="swap",
                    notes=[
                        "SWAP candidate: plugin uses GET /v2/{repo}/manifests/{tag} for tag "
                        "existence; HEAD avoids transferring the manifest body."
                    ],
                )
                self._docker_get(
                    name=f"docker_manifest_get_{safe_repo}",
                    description=f"GET manifest for {docker_repo}:{chosen_tag} (already used)",
                    path=f"/v2/{dr}/manifests/{tg}",
                    category="existing",
                    notes=["Plugin's tagExists() path"],
                )
            else:
                self.results.append(ProbeResult(
                    name=f"docker_manifest_{safe_repo}_skipped",
                    description="manifest probes skipped — no tag derivable",
                    method="-", path="-", payload_kind="error", category="existing",
                    error=f"docker_tags_{safe_repo} yielded no tags; pass --manifest-tag to force",
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

        # 6) System / health / observability — metrics surface (mostly admin)
        print("\n[probe] version/internal — metrics + system surface")
        self._rest_get(
            name="metrics_ping",
            description="Lowest-cost liveness ping (renamed from /service/metrics/ping in 3.81)",
            path="/service/rest/metrics/ping",
            category="version",
            notes=["Returns 'pong' for any authenticated user"],
            expect_json=False,
        )
        self._rest_get(
            name="metrics_data",
            description="JVM/JMX metrics — capacity counters",
            path="/service/rest/metrics/data",
            category="internal",
            notes=["nx-metrics-all OR admin; non-admin = 403"],
        )
        self._rest_get(
            name="metrics_threads",
            description="Thread dump diagnostic (admin)",
            path="/service/rest/metrics/threads",
            category="internal",
            expect_json=False,
        )
        self._rest_get(
            name="metrics_prometheus",
            description="Prometheus-format metrics text",
            path="/service/rest/metrics/prometheus",
            category="internal",
            notes=["nx-metrics-all OR admin"],
            expect_json=False,
        )
        self._rest_get(
            name="system_node",
            description="HA cluster NodeInformation (Pro-only)",
            path="/service/rest/v1/system/node",
            category="feature",
            notes=["Pro-only — confirms HA cluster membership when 200"],
        )
        self._rest_get(
            name="system_license",
            description="License details (Pro; admin-likely)",
            path="/service/rest/v1/system/license",
            category="internal",
            notes=["Definitive OSS-vs-Pro answer if reachable; expect 403 for non-admin"],
        )

        # 7) Security / capability mapping — beyond just /users
        print("\n[probe] internal — security capability mapping")
        self._rest_get(
            name="security_realms_available",
            description="All available realms (admin)",
            path="/service/rest/v1/security/realms/available",
            category="internal",
            notes=["Admin-only; pairs with realms/active for 'what could be enabled'"],
        )
        self._rest_get(
            name="security_user_sources",
            description="User backends configured (LDAP/SAML/Crowd) — non-admin readable",
            path="/service/rest/v1/security/user-sources",
            category="internal",
            notes=[
                "Per Sonatype: the ONLY documented non-admin GET in the security mgmt API. "
                "Tells us which auth backends exist."
            ],
        )
        self._rest_get(
            name="security_anonymous",
            description="Anonymous access state (admin)",
            path="/service/rest/v1/security/anonymous",
            category="internal",
            notes=["admin-only; affects whether some endpoints work without auth"],
        )
        self._rest_get(
            name="security_roles",
            description="Roles list (admin)",
            path="/service/rest/v1/security/roles",
            category="internal",
        )
        self._rest_get(
            name="security_privileges",
            description="Privileges list (admin)",
            path="/service/rest/v1/security/privileges",
            category="internal",
        )
        self._rest_get(
            name="security_content_selectors",
            description="Content selectors (CEL-style ACLs; nx-settings-read)",
            path="/service/rest/v1/security/content-selectors",
            category="internal",
            notes=["Tells us if content-selector firewalling is configured"],
        )
        self._rest_get(
            name="security_ssl_truststore",
            description="Trusted CA certs for outbound proxying (admin)",
            path="/service/rest/v1/security/ssl/truststore",
            category="internal",
        )
        self._rest_get(
            name="security_atlassian_crowd",
            description="Crowd integration config (admin, Pro)",
            path="/service/rest/v1/security/atlassian-crowd",
            category="internal",
            notes=["404 if Crowd plugin not active"],
        )

        # 8) Repository administration (read paths only)
        print("\n[probe] internal/feature — repo admin (read paths)")
        self._rest_get(
            name="blobstores",
            description="Blob stores (file/S3/Azure/GCP backends) (admin)",
            path="/service/rest/v1/blobstores",
            category="internal",
        )
        self._rest_get(
            name="routing_rules",
            description="Routing rules (admin, nx-all)",
            path="/service/rest/v1/routing-rules",
            category="internal",
            notes=["Empty array = none configured"],
        )
        self._rest_get(
            name="cleanup_policies",
            description="Cleanup policies (Pro 3.70+, nexus:settings.read)",
            path="/service/rest/v1/cleanup-policies",
            category="feature",
            notes=["Pro-only; lists auto-purge policies"],
        )
        self._rest_get(
            name="email_config",
            description="SMTP / email config (admin)",
            path="/service/rest/v1/email",
            category="internal",
        )
        self._rest_get(
            name="tasks_list",
            description="Scheduled tasks list (admin)",
            path="/service/rest/v1/tasks",
            category="internal",
            notes=["admin-only; capability marker"],
        )
        self._rest_get(
            name="capabilities_types",
            description="Capability types catalog (admin)",
            path="/service/rest/v1/capabilities/types",
            category="internal",
            notes=["admin-only; lists available capability plugins"],
        )

        # 9) Per-format direct repo paths (NOT under /service/rest/v1/)
        # These are what Maven/Gradle/Docker actually call. High-value swaps.
        if maven_repo:
            print(f"\n[probe] swap/feature — per-format paths in {maven_repo}")
            mv = urllib.parse.quote(maven_repo, safe="")
            coord = _read_first_maven_coord(self.raw_dir / f"components_{maven_repo}.json")
            if coord:
                group, artifact = coord
                group_path = group.replace(".", "/")
                self._rest_get(
                    name=f"maven_metadata_{maven_repo}",
                    description=(
                        f"maven-metadata.xml at {group}:{artifact} — Maven/Gradle's "
                        f"native 'find latest version' path"
                    ),
                    path=f"/repository/{mv}/{group_path}/{artifact}/maven-metadata.xml",
                    category="swap",
                    notes=[
                        "SWAP candidate — replaces /v1/search?sort=version dance with one XML fetch. "
                        "Repo-read perm only; returns release/latest/versions block."
                    ],
                    expect_json=False,
                )
                self._rest_get(
                    name=f"maven_metadata_sha256_{maven_repo}",
                    description=f"SHA256 sidecar for the maven-metadata.xml above",
                    path=f"/repository/{mv}/{group_path}/{artifact}/maven-metadata.xml.sha256",
                    category="feature",
                    notes=["Cheap integrity check; .sha1 sidecar is the older fallback"],
                    expect_json=False,
                )
            else:
                self.results.append(ProbeResult(
                    name=f"maven_metadata_{maven_repo}_skipped",
                    description="Maven format-specific probes skipped — no group/artifact derivable",
                    method="-", path="-", payload_kind="error", category="swap",
                    error=f"components_{maven_repo} yielded no items with both group and name",
                ))

        # 10) Search variants — high-value swaps + new features
        if maven_repo:
            mv = urllib.parse.quote(maven_repo, safe="")
            print(f"\n[probe] swap/feature — search variants on {maven_repo}")
            self._rest_get(
                name=f"search_sort_version_desc_{maven_repo}",
                description=f"Newest-first component search — 'what's the latest version' in one call",
                path=f"/service/rest/v1/search?repository={mv}&format=maven2&sort=version&direction=desc",
                category="swap",
                notes=["SWAP — replaces multi-call 'list then sort client-side'"],
            )
            self._rest_get(
                name=f"search_release_only_{maven_repo}",
                description=f"Release-only search (skip snapshots)",
                path=f"/service/rest/v1/search?repository={mv}&format=maven2&prerelease=false",
                category="feature",
            )
            self._rest_get(
                name=f"search_assets_download_{maven_repo}",
                description=f"One-call download — /search/assets/download with sort=version&direction=desc",
                path=(
                    f"/service/rest/v1/search/assets/download?repository={mv}"
                    f"&format=maven2&sort=version&direction=desc"
                ),
                category="swap",
                notes=[
                    "SWAP — replaces 'search → pick → fetch URL' dance with single 302 redirect. "
                    "expect_json=False since the success path is a redirect to a binary asset."
                ],
                expect_json=False,
            )

        # 3.88 SQL search wildcard semantics test — fires regardless of repo flag
        # so we always learn which search backend the instance is on.
        print("\n[probe] version — 3.88 SQL search wildcard test")
        self._rest_get(
            name="search_wildcard_trailing",
            description="Trailing-wildcard search ?q=neo* (3.88+ SQL search supports this)",
            path="/service/rest/v1/search?q=neo*",
            category="version",
            notes=[
                "Distinguishes search backend: 3.88+ SQL returns results; pre-3.88 also returns "
                "results but tolerates the other forms below."
            ],
        )
        self._rest_get(
            name="search_wildcard_leading",
            description="Leading-wildcard search ?q=*neo* (pre-3.88 only)",
            path="/service/rest/v1/search?q=*neo*",
            category="version",
            notes=[
                "3.88+: returns empty silently; pre-3.88: works. If empty AND trailing returns "
                "results, this instance is on the SQL search path."
            ],
        )

        # Hash-based reverse-lookup + asset-by-id — derive from components page 1
        if maven_repo:
            sha1 = _read_first_asset_sha1(self.raw_dir / f"components_{maven_repo}.json")
            if sha1:
                self._rest_get(
                    name=f"search_sha1_{sha1[:8]}",
                    description=f"Reverse-lookup by sha1 ({sha1[:8]}…) — 'what artifact is this jar?'",
                    path=f"/service/rest/v1/search?sha1={sha1}",
                    category="feature",
                    notes=["NEW capability — answers 'I have this jar; what is it?'"],
                )
            asset_id = _read_first_asset_id(self.raw_dir / f"components_{maven_repo}.json")
            if asset_id:
                aid = urllib.parse.quote(asset_id, safe="")
                self._rest_get(
                    name=f"asset_by_id_{asset_id[:12]}",
                    description=(
                        f"Single asset by id ({asset_id[:8]}…) — direct downloadUrl + "
                        "4-checksum trio (md5/sha1/sha256/sha512)"
                    ),
                    path=f"/service/rest/v1/assets/{aid}",
                    category="feature",
                    notes=["Returns md5/sha1/sha256/sha512 + downloadUrl in one call"],
                )

        # Per-format repo config — works for any repo, but we only have a name
        # if --maven-repo is set. Format/type are derived from repositories_all.
        if maven_repo:
            mv = urllib.parse.quote(maven_repo, safe="")
            ft = self._lookup_repo_format_type(maven_repo)
            if ft:
                fmt, repo_type = ft
                fq = urllib.parse.quote(fmt, safe="")
                tq = urllib.parse.quote(repo_type, safe="")
                self._rest_get(
                    name=f"repo_config_{maven_repo}",
                    description=(
                        f"Per-format config — full {fmt}/{repo_type} settings "
                        f"(remoteUrl, version-policy, deployment policy, group members)"
                    ),
                    path=f"/service/rest/v1/repositories/{fq}/{tq}/{mv}",
                    category="feature",
                    notes=["Currently the plugin sees only what's in /v1/repositories list"],
                )
            self._rest_get(
                name=f"repo_summary_{maven_repo}",
                description=f"Single-repo summary",
                path=f"/service/rest/v1/repositories/{mv}",
                category="feature",
                notes=["Quicker than full /repositories list"],
            )

        # 11) Docker v2 advanced — OCI Accept-header dance + blob HEAD + pagination
        if docker_repo:
            dr = urllib.parse.quote(docker_repo, safe="/")
            safe_repo = docker_repo.replace("/", "_")
            chosen_tag = manifest_tag or _read_first_tag(
                self.raw_dir / f"docker_tags_{safe_repo}.json"
            )
            if chosen_tag:
                tg = urllib.parse.quote(chosen_tag, safe="")
                print(f"\n[probe] swap — OCI Accept-header dance for {docker_repo}:{chosen_tag}")
                # Plugin's biggest latent bug: multi-arch images return an OCI
                # image-index, not a v2 manifest. Plugin sends one Accept; will
                # silently mishandle. Probe with all 4 variants and record which
                # the registry returns (Content-Type response header tells us).
                accepts = [
                    ("manifest_v2",
                     "application/vnd.docker.distribution.manifest.v2+json",
                     "Docker v2 single-arch manifest (what plugin requests today)"),
                    ("manifest_list_v2",
                     "application/vnd.docker.distribution.manifest.list.v2+json",
                     "Docker manifest list — multi-arch (multiple platforms)"),
                    ("oci_image_index",
                     "application/vnd.oci.image.index.v1+json",
                     "OCI image-index — multi-arch, OCI flavor"),
                    ("oci_image_manifest",
                     "application/vnd.oci.image.manifest.v1+json",
                     "OCI single-arch manifest — non-Docker images (BuildKit, buildah, kaniko)"),
                ]
                for short, mt, desc in accepts:
                    self._docker_head_accept(
                        name=f"docker_manifest_{short}_{safe_repo}",
                        description=f"HEAD manifest with Accept: {mt} ({desc})",
                        path=f"/v2/{dr}/manifests/{tg}",
                        accept=mt,
                        notes=["Records Content-Type response → tells us which media-type registry returns"],
                    )
                # Blob HEAD using the digest from the existing manifest probe
                first_digest = _read_first_blob_digest(
                    self.raw_dir / f"docker_manifest_get_{safe_repo}.json"
                )
                if first_digest:
                    fd = urllib.parse.quote(first_digest, safe="")
                    self._docker_head(
                        name=f"docker_blob_head_{safe_repo}",
                        description=f"HEAD blob {first_digest[:16]}… — cheapest layer existence check",
                        path=f"/v2/{dr}/blobs/{fd}",
                        category="feature",
                        notes=["OCI dist spec — answers 'does this layer exist' without downloading"],
                    )
                # OCI referrers (1.1) — Sigstore/cosign signatures, SBOM. Unverified for 3.90.
                if first_digest:
                    fd = urllib.parse.quote(first_digest, safe="")
                    self._docker_get(
                        name=f"docker_referrers_{safe_repo}",
                        description=f"OCI referrers index for {first_digest[:16]}…",
                        path=f"/v2/{dr}/referrers/{fd}",
                        category="feature",
                        notes=[
                            "Unverified for 3.90 (Nexus claims OCI 1.0/1.0.1 since 3.71; "
                            "1.1 referrers not explicitly stated). 404 = not supported."
                        ],
                    )
            # Tags pagination — exercises Link: rel="next"
            self._docker_get(
                name=f"docker_tags_pagination_{safe_repo}",
                description=f"Tag list page 1 with n=10 — exercises Link: rel=\"next\" pagination cursor",
                path=f"/v2/{dr}/tags/list?n=10",
                category="swap",
                notes=["SWAP — plugin caps at first 100 tags; Link header unlocks full enumeration"],
            )
        # _catalog pagination — independent of --docker-repo
        self._docker_get(
            name="docker_catalog_pagination",
            description="Docker catalog page 1 with n=10 — exercises last= pagination",
            path="/v2/_catalog?n=10",
            category="swap",
            notes=["SWAP — plugin caps at first 100 repos"],
        )

        # 12) Pro-only + 3.90-new + unverified probes
        print("\n[probe] feature/internal — Pro / 3.90-new / unverified")
        self._rest_get(
            name="recovery_mode",
            description="Recovery mode state (NEW in 3.90.0; admin-only)",
            path="/service/rest/v1/recovery-mode",
            category="feature",
            notes=[
                "3.90.0+; 404 on older. Active mode would explain weird search/upload behavior. "
                "Expect 403 for non-admin."
            ],
        )
        self._rest_get(
            name="tags_list",
            description="Pro tags list (logical artifact groups for staging-style workflows)",
            path="/service/rest/v1/tags",
            category="feature",
            notes=["Pro-only; user-readable when token has nx-all"],
        )
        self._rest_get(
            name="monthly_metrics",
            description="Monthly metrics (Pro?) — last-12-months counters",
            path="/service/rest/v1/monthly-metrics",
            category="feature",
            notes=["Unverified for 3.90; probe-and-see"],
        )
        # Sonatype Firewall — paths confirmed by swagger.json from the user's
        # 3.90.1 instance: /v1/firewall/* does NOT exist; the feature lives
        # under /v1/malicious-risk/*. Two GET-able paths:
        self._rest_get(
            name="malicious_risk_disk",
            description="Malicious risk on disk (Sonatype Firewall)",
            path="/service/rest/v1/malicious-risk/risk-on-disk",
            category="feature",
            notes=["Pro feature; admin-likely"],
        )
        self._rest_get(
            name="malicious_risk_registries",
            description="Registries with Sonatype Firewall scanning enabled",
            path="/service/rest/v1/malicious-risk/enabledRegistries",
            category="feature",
            notes=["Pro feature; tells us which proxy registries have malware scanning on"],
        )

        # IQ Server link — separate API family (NOT firewall). 5 paths in
        # /v1/iq/* per swagger.json; 2 are GET-readable (the rest are POSTs
        # for enable/disable/verify-connection — skipped per read-only policy).
        self._rest_get(
            name="iq_audit",
            description="Global IQ Server audit configuration",
            path="/service/rest/v1/iq/audit",
            category="feature",
            notes=[
                "Pro+IQ; tells us if IQ-server policy-driven artifact blocking is active "
                "AND surfaces the configured server URL + audit mode."
            ],
        )
        if maven_repo:
            mv = urllib.parse.quote(maven_repo, safe="")
            self._rest_get(
                name=f"iq_audit_repo_{maven_repo}",
                description=f"Per-repo IQ audit state for {maven_repo}",
                path=f"/service/rest/v1/iq/audit/{mv}",
                category="feature",
                notes=["Per-repo IQ policy enforcement state"],
            )

        # Replication API — confirmed NOT in user's 3.90 swagger.json. The
        # feature is admin-UI-only on this instance (Pro Replication is a
        # separate licensed module). Probes left in place as 'unverified'
        # capability markers — the 404 itself is the answer.
        self._rest_get(
            name="replication_connection",
            description="Replication connection config (Pro Replication module)",
            path="/service/rest/v1/replication/connection",
            category="feature",
            notes=["Not in 3.90 swagger.json for this user — confirms admin-UI-only on this instance"],
        )
        self._rest_get(
            name="replication_group",
            description="Replication group config (Pro Replication module)",
            path="/service/rest/v1/replication/group",
            category="feature",
            notes=["Not in 3.90 swagger.json for this user — confirms admin-UI-only on this instance"],
        )

    def run_docker_only(self, docker_repo: str, manifest_tag: Optional[str]) -> None:
        """Workflow-scope probe — exercises ONLY the two Docker Registry v2
        endpoints DockerRegistryClient hits during the per-repo verify flow:

          1. GET  /v2/{repo}/tags/list?n=100  → listTags() / getLatestReleaseTag()
          2. HEAD /v2/{repo}/manifests/{tag}   → tagExists()

        Plus three sanity probes:
          - GET  /v2/                                → registry root online + auth flow
          - GET  /v2/{repo}/manifests/{tag}          → documents manifest body shape
          - HEAD /v2/{repo}/manifests/<bogus-tag>    → confirms 404 distinguishes
                                                       'tag missing' from auth/network

        Total: 5 calls. REST API, repo browsing, security, metrics — all skipped.
        Required: --docker-repo. Optional: --manifest-tag (auto-derived from /tags/list).
        """
        print("[probe] docker-only mode — workflow scope (5 calls)\n")

        # 1. Registry root sanity
        self._docker_get(
            name="docker_v2_root",
            description="Docker Registry v2 connectivity",
            path="/v2/",
            category="version",
            notes=[
                "200 with empty body = registry online. 401 with Bearer challenge = "
                "Docker Bearer Token Realm enabled. 401 without challenge = Basic only. "
                "404 HTML = wrong host — Docker registry is on a different URL than the REST API."
            ],
        )

        # `docker_repo` may contain '/' (Nexus folder paths like
        # company-team/repo-name). For the wire path we keep '/' literal per
        # Docker Registry v2 spec (slashes are path-component separators inside
        # <name>, not characters to encode). For filename components we slash-
        # replace so /raw/<name>.json doesn't try to write into a non-existent
        # subdirectory.
        dr = urllib.parse.quote(docker_repo, safe="/")
        safe_repo = docker_repo.replace("/", "_")

        # 2. tags/list — primary endpoint #1
        print(f"\n[probe] existing — Docker repo '{docker_repo}'")
        self._docker_get(
            name=f"docker_tags_{safe_repo}",
            description=f"Tag list for {docker_repo} — drives listTags() and getLatestReleaseTag()",
            path=f"/v2/{dr}/tags/list?n=100",
            category="existing",
            notes=[
                "Plugin's listTags() — verify pagination Link header behaviour. "
                "getLatestReleaseTag() filters tags to /^\\d+\\.\\d+\\.\\d+.*$/ "
                "and semver-sorts; only this endpoint feeds it."
            ],
        )

        chosen_tag = manifest_tag or _read_first_tag(
            self.raw_dir / f"docker_tags_{safe_repo}.json"
        )
        if not chosen_tag:
            self.results.append(ProbeResult(
                name=f"docker_manifest_{safe_repo}_skipped",
                description="manifest probes skipped — no tag derivable",
                method="-", path="-", payload_kind="error", category="existing",
                error=f"docker_tags_{safe_repo} yielded no tags; pass --manifest-tag to force",
            ))
            return

        tg = urllib.parse.quote(chosen_tag, safe="")

        # 3. manifests HEAD — primary endpoint #2 (the SWAP from current GET)
        self._docker_head(
            name=f"docker_manifest_head_{safe_repo}",
            description=f"HEAD manifest for {docker_repo}:{chosen_tag} — drives tagExists()",
            path=f"/v2/{dr}/manifests/{tg}",
            category="existing",
            notes=[
                "Plugin's tagExists() path. 2xx = exists; 404 = not found. "
                "Docker-Content-Digest header carries the manifest digest."
            ],
        )

        # 4. manifests GET — body-shape reference (older DockerRegistryClient
        #    code paths used GET; HEAD is preferred. Capturing GET keeps
        #    schemaVersion/mediaType/layers visible for any future use.)
        self._docker_get(
            name=f"docker_manifest_get_{safe_repo}",
            description=f"GET manifest for {docker_repo}:{chosen_tag} — full body",
            path=f"/v2/{dr}/manifests/{tg}",
            category="existing",
            notes=[
                "Documents manifest body shape (schemaVersion, mediaType, layers). "
                "Plugin uses HEAD for tagExists; this is reference-only."
            ],
        )

        # 5. Negative case — bogus tag confirms 404 path is reliable
        bogus_tag = "wf-orchestrator-probe-nonexistent-tag-9999999"
        bg = urllib.parse.quote(bogus_tag, safe="")
        self._docker_head(
            name=f"docker_manifest_head_{safe_repo}_bogus",
            description=f"HEAD manifest for {docker_repo}:{bogus_tag} — verifies 404 path",
            path=f"/v2/{dr}/manifests/{bg}",
            category="existing",
            notes=[
                "Negative case — distinguishes 'tag missing' from 'auth/network failure'. "
                "DockerRegistryClient.tagExists() returns Success(false) on 404, "
                "Error on any other non-2xx."
            ],
        )

    # -- repo lookup helper for per-format config probe ----------------------

    def _lookup_repo_format_type(self, repo_name: str) -> Optional[tuple[str, str]]:
        """Walk the cached /v1/repositories result and return (format, type)
        for `repo_name`. Used by the per-format config probe in §10."""
        for fname in ("repositories_all", "discover_repositories"):
            body = _read_raw_body(self.raw_dir / f"{fname}.json")
            if isinstance(body, list):
                for r in body:
                    if isinstance(r, dict) and r.get("name") == repo_name:
                        fmt = r.get("format")
                        rtype = r.get("type")
                        if isinstance(fmt, str) and isinstance(rtype, str):
                            return (fmt, rtype)
        return None

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

    # Server: Nexus/3.90.1-01 (PRO)  →  ('3.90.1', '01', 'PRO')
    _SERVER_HEADER_RE = re.compile(
        r"Nexus/(?P<version>\d+\.\d+\.\d+)-(?P<build>\d+)\s*\((?P<edition>[^)]+)\)"
    )
    # rapture cache-buster:  href="../static/rapture/...?3.90.1-01"  →  '3.90.1-01'
    _RAPTURE_VERSION_RE = re.compile(r"/static/rapture/[^\"']*?\?([0-9A-Za-z\.\-]+)")

    def _format_version_note(self) -> str:
        # 1. Server: header — set on EVERY response, including 401/403.
        server_seen: list[tuple[str, str]] = []  # (probe-name, header-value)
        for r in self.results:
            sv = (r.response_headers or {}).get("Server")
            if sv and "Nexus/" in sv:
                server_seen.append((r.name, sv))
        primary = ""
        version = build = edition = ""
        if server_seen:
            primary = server_seen[0][1]
            m = self._SERVER_HEADER_RE.search(primary)
            if m:
                version = m.group("version")
                build = m.group("build")
                edition = m.group("edition")

        # 2. Fallback — /v1/status/check JSON has no documented version field on
        # 3.90, but if it 200'd we know the v1 namespace + auth are healthy.
        status_check_state = "(skipped)"
        for r in self.results:
            if r.name == "rest_status_check":
                status_check_state = (
                    "200 (alive + auth ok)" if r.ok
                    else f"{r.status} (auth/perm or pre-3.81)"
                )
                break

        # 3. Fallback — /swagger.json .info.version (anonymous-readable)
        swagger_version = ""
        try:
            swagger_body = _read_raw_body(self.raw_dir / "rest_swagger_json.json")
            if isinstance(swagger_body, dict):
                info = swagger_body.get("info") or {}
                if isinstance(info, dict):
                    swagger_version = str(info.get("version") or "")
        except Exception:
            pass

        # 4. Fallback — rapture HTML scrape for cache-buster on /static/ URLs
        rapture_version = ""
        for raw_name in ("rest_root_html", "docker_v2_root"):
            try:
                rec = json.loads(
                    (self.raw_dir / f"{raw_name}.json").read_text(encoding="utf-8")
                )
                preview = (rec.get("result") or {}).get("payload_preview") or ""
                if isinstance(preview, str) and preview:
                    m = self._RAPTURE_VERSION_RE.search(preview)
                    if m:
                        rapture_version = m.group(1)
                        break
            except Exception:
                continue

        # Auth realms hint (typically 403 for non-admin — that's the capability marker)
        realms_state = "(skipped)"
        try:
            for r in self.results:
                if r.name == "rest_security_realms_active":
                    if r.ok:
                        body = _read_raw_body(self.raw_dir / "rest_security_realms_active.json")
                        if isinstance(body, list):
                            realms_state = ", ".join(str(x) for x in body) or "(empty array)"
                    else:
                        realms_state = f"{r.status} (admin-only — non-admin token capability marker)"
                    break
        except Exception:
            pass

        # Docker /v2/ status
        docker_state = "(skipped)"
        for r in self.results:
            if r.name == "docker_v2_root":
                docker_state = f"status={r.status} auth={r.auth_scheme or '-'}"
                break

        # Compose the markdown block — primary first, fallbacks beneath, with
        # a clear "winner" line for at-a-glance reading.
        winner = (
            f"`Nexus {version}-{build} ({edition})` (from Server: header)" if version
            else f"`{swagger_version}` (from /swagger.json .info.version)" if swagger_version
            else f"`{rapture_version}` (from rapture HTML cache-buster)" if rapture_version
            else "**unknown** — every detect path failed; check reverse-proxy header stripping"
        )
        lines = [
            f"- **Detected:** {winner}",
            f"- **Server: header sample:** `{primary or '(not seen on any response — possibly stripped)'}`",
            f"- **/v1/status/check:** {status_check_state}",
            f"- **/swagger.json .info.version:** `{swagger_version or '(unreachable or non-JSON)'}`",
            f"- **rapture cache-buster scrape:** `{rapture_version or '(no /static/rapture/ URL in any HTML body)'}`",
            f"- **active auth realms:** {realms_state}",
            f"- **/v2/ probe:** `{docker_state}`",
        ]
        return "\n".join(lines) + "\n"


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


def _read_first_maven_coord(raw_path: Path) -> Optional[tuple[str, str]]:
    """Return (groupId, artifactId) from the first item with both set.

    Nexus 3 component-list payload shape:
      {"items": [{"id":..., "repository": "...", "format": "maven2",
                  "group": "com.example", "name": "my-artifact", "version": "...",
                  "assets": [...]}, ...]}
    """
    body = _read_raw_body(raw_path)
    if isinstance(body, dict):
        for it in (body.get("items") or []):
            if isinstance(it, dict):
                g = it.get("group")
                n = it.get("name")
                if isinstance(g, str) and g and isinstance(n, str) and n:
                    return (g, n)
    return None


def _read_first_asset_id(raw_path: Path) -> Optional[str]:
    """First asset id walking items[].assets[]. Asset ids are opaque base64
    strings — used by /v1/assets/{id} for direct downloadUrl + checksum trio."""
    body = _read_raw_body(raw_path)
    if isinstance(body, dict):
        for it in (body.get("items") or []):
            if isinstance(it, dict):
                for a in (it.get("assets") or []):
                    if isinstance(a, dict) and a.get("id"):
                        return str(a["id"])
    return None


def _read_first_asset_sha1(raw_path: Path) -> Optional[str]:
    """First non-empty SHA1 from items[].assets[].checksum.sha1.
    Used by /v1/search?sha1=... to demonstrate hash-based reverse lookup."""
    body = _read_raw_body(raw_path)
    if isinstance(body, dict):
        for it in (body.get("items") or []):
            if isinstance(it, dict):
                for a in (it.get("assets") or []):
                    if isinstance(a, dict):
                        cks = a.get("checksum") or {}
                        if isinstance(cks, dict):
                            v = cks.get("sha1")
                            if isinstance(v, str) and v:
                                return v
    return None


def _read_first_blob_digest(raw_path: Path) -> Optional[str]:
    """First layer/config blob digest from a Docker v2 manifest body.

    v2 manifest shape:
      {"schemaVersion":2, "config":{"digest":"sha256:..."},
       "layers":[{"digest":"sha256:..."}, ...]}
    OCI image-manifest is the same key shape; OCI image-index nests under
    .manifests[].digest (different concept — that's a per-arch manifest, not
    a layer). We prefer .config.digest then .layers[0].digest then .manifests[0].digest.
    """
    body = _read_raw_body(raw_path)
    if not isinstance(body, dict):
        return None
    config = body.get("config")
    if isinstance(config, dict) and isinstance(config.get("digest"), str):
        return config["digest"]
    layers = body.get("layers")
    if isinstance(layers, list):
        for layer in layers:
            if isinstance(layer, dict) and isinstance(layer.get("digest"), str):
                return layer["digest"]
    manifests = body.get("manifests")
    if isinstance(manifests, list):
        for m in manifests:
            if isinstance(m, dict) and isinstance(m.get("digest"), str):
                return m["digest"]
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
                   help="Only call the version-detect ladder (/status, /status/writable, "
                        "/status/check, /swagger.json, /, /v2/, /realms/active) and exit. "
                        "The Server: HTTP header on every response is the primary "
                        "version source; the rest are fallbacks + capability checks.")
    p.add_argument("--discover", action="store_true",
                   help="Discovery mode: list all repos + Docker catalog, pick a maven + docker "
                        "repo, write Result_N/discover.md with a copy-paste full-sweep command.")
    p.add_argument("--docker-only", action="store_true",
                   help="Workflow-scope mode: probe ONLY the 5 Docker v2 calls the plugin's "
                        "per-repo verify flow uses (/v2/, /v2/{repo}/tags/list, /v2/{repo}/manifests/{tag} "
                        "HEAD+GET, plus a bogus-tag HEAD to validate 404 semantics). "
                        "Requires --docker-repo. REST/maven/security/metrics surfaces are skipped.")
    p.add_argument("--docker-base-path", default="",
                   help="Path prefix prepended to every /v2/* URL — supports Nexus 3 path-based "
                        "Docker access where /v2/ lives under a sub-path instead of at the root "
                        "(e.g. '/repository/docker-group' so the probe hits "
                        "<registry>/repository/docker-group/v2/<repo>/manifests/<tag>). "
                        "Default empty (Docker at root /v2/).")
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

    exclusive_modes = [m for m in ("discover", "versions_only", "docker_only")
                       if getattr(args, m)]
    if len(exclusive_modes) > 1:
        print(f"ERROR: --discover, --versions-only, and --docker-only are mutually exclusive "
              f"(got: {', '.join('--' + m.replace('_', '-') for m in exclusive_modes)}).",
              file=sys.stderr)
        return 2

    if args.docker_only and not args.docker_repo:
        print("ERROR: --docker-only requires --docker-repo "
              "(the Nexus folder path, e.g. company-team/service-a-repo).",
              file=sys.stderr)
        return 2

    if args.basic_token and (args.user or args.password):
        print("ERROR: --basic-token is mutually exclusive with --user/--password.", file=sys.stderr)
        return 2

    if args.discover:
        mode_label = "discover"
    elif args.versions_only:
        mode_label = "versions-only"
    elif args.docker_only:
        mode_label = "docker-only"
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
        docker_base_path=args.docker_base_path,
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
        "docker_only": args.docker_only,
        "docker_base_path": args.docker_base_path,
    }

    if args.discover:
        probe.run_discover()
    elif args.versions_only:
        probe.run_versions_only()
    elif args.docker_only:
        probe.run_docker_only(
            docker_repo=args.docker_repo,
            manifest_tag=args.manifest_tag,
        )
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
