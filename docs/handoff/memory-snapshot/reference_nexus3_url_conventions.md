---
name: Nexus 3 URL conventions — /repository/ prefix + path-based Docker
description: Nexus 3 hardcodes /repository/<name>/ as the HTTP route for ALL repo content; path-based Docker access is /repository/<name>/v2/...; admin UI browse URLs use a separate #browse/browse: JS route
type: reference
originSessionId: ef626b87-1ed6-4f84-90da-16cbac5980ee
---
## The /repository/ prefix is hardcoded

Every Nexus 3 repository — Maven, npm, Docker, raw, NuGet, PyPI, apt — is reachable at the same URL shape:

```
<nexus-host>/repository/<repo-name>/<path-inside-repo>
```

The `/repository/` segment is **fixed** by Nexus. Not configurable, not per-format. Sonatype's docs call this the "URL path of the Repository." Examples:

| Format | URL |
|---|---|
| Maven | `<host>/repository/maven-releases/com/foo/bar/1.2.3/bar-1.2.3.jar` |
| npm | `<host>/repository/npm-public/some-package/-/some-package-1.0.0.tgz` |
| Raw | `<host>/repository/raw-internal/some-file.txt` |
| Docker (path-based) | `<host>/repository/docker-group/v2/<image>/manifests/<tag>` |

## Nexus admin UI browse URLs are different

The Nexus admin UI uses **client-side JS routing** with a separate URL shape:

```
<nexus-host>/#browse/browse:<repo-name>:<asset-path-url-encoded>
```

Everything after `#` is JavaScript routing — **never sent to the server**. The `:` separators are part of the JS router path. Decoded payload `v2%2Fcompany-team%2Frepo-name%2Ftags` → `v2/company-team/repo-name/tags` is the asset's storage path inside the named repo.

**Not interchangeable with the `/repository/` route.** A Docker client / curl / probe must use `/repository/<repo>/v2/...`, never `#browse/browse:<repo>:v2/...`.

## Docker access modes (Nexus 3)

Nexus 3 exposes Docker repos via three connector types:
1. **HTTP connector (path-based)** — Docker clients hit `<host>/repository/<repo-name>/v2/...`. Same URL as everything else. Opt-in: admin sets connector type to "Path" on the repo. Most common in 2025+ deployments.
2. **HTTP connector (port-based)** — Docker clients hit `<host>:<port>/v2/...` (`<port>` typically 8082 / 8083). The traditional Docker-on-Nexus pattern. Set per repo by the admin.
3. **Subdomain connector** — Docker clients hit `<docker-subdomain>/v2/...`. Rare; requires DNS + reverse proxy.

If `<host>/v2/` returns 404 HTML on a Nexus instance, you're NOT necessarily on the wrong host — Docker is just on a non-root route. Try path-based (`/repository/<docker-group>/v2/`) before assuming a separate hostname.

## Spotting which mode is in use

- Nexus admin UI shows the *repository's* `url` field — that's the canonical client URL for that repo. Hidden behind admin auth on `/service/rest/v1/repositories`.
- For path-based: the URL ends in `/repository/<name>/`, no port.
- For port-based: the URL has `:<port>/` and no `/repository/<name>` segment.
- A repo's browse URL (`#browse/browse:<name>:v2/...`) only confirms storage layout, not access mode.

## When applying

If a Nexus probe gets HTML 404 on `/v2/`:
1. Check if the user can navigate to the Docker repo in the Nexus UI. Their browse URL reveals the repo name (after `browse:`).
2. Try path-based access: `<host>/repository/<repo-name>/v2/...`.
3. If that also 404s, ask for the connector port from the Nexus admin (or grep the user's `docker-compose` / k8s manifests for an existing pull URL).
